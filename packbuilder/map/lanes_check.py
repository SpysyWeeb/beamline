"""Check the lane attributes in a pack's roads layer against the OSM extract it was built from.

Usage (from the repo root):
    .venv/map/Scripts/python packbuilder/map/lanes_check.py data/map/packs-v2/pa-261114-z14.pmtiles --state pa
        [--zoom 14] [--coverage] [--direction] [--procs 4] [--out-dir data/map/work/checks]

Checkpoints (packbuilder/map/lanes_checkpoints.json): for each listed OSM way,
the way is read from the state's .osm.pbf with pyosmium, the zoom-Z tile that
holds the middle of the way is decoded, and the road feature nearest to that
point (within 8 m) is compared with the way's tags:
  - lanes, lanes:forward, lanes:backward, lanes:both_ways must be integers
    equal to the tag (tags that are not a plain whole number must be absent);
  - turn:lanes and its :forward, :backward and :both_ways forms must equal the tag;
  - the feature must run in the way's direction (turn:lanes lists lanes from
    left to right in the way's direction, so a reversed line would swap them):
    the way's first and last node are projected onto the feature's line.
--coverage also compares, per highway class, the share of OSM ways (and of
their length) that carry lanes / any turn:lanes* key with the share of road
length in the tiles that carries the attribute.
--direction checks the direction of EVERY zoom-Z road feature that carries a
direction-dependent lane attribute (lanes:forward/backward, turn:lanes,
turn:lanes:forward/backward): each of its segments inside the tile is matched
to the OSM ways (same class scope) that carry exactly the same eight lane
values and lie within 3 m of the segment's midpoint, and votes for "same" or
"reversed" by projecting its two ends onto the way. Segments whose candidate
ways disagree are skipped as ambiguous. A feature is reversed when most of its
votes say so; a reversal matters when the feature has a turn:lanes* value or
lanes:forward != lanes:backward.
Writes <pack>.lanes-z<Z>.json (next to the pack, or into --out-dir) and exits 1
on any mismatch or any reversal that matters.
"""
import argparse
import gzip
import json
import math
import mmap
import sys
from collections import defaultdict
from multiprocessing import Pool
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mvt  # noqa: E402
from pmtiles.reader import MmapSource, Reader  # noqa: E402
from pmtiles.tile import deserialize_header, tileid_to_zxy  # noqa: E402
from tile_stats import entries  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
CONFIG = json.loads((HERE / "build_config.json").read_text())
CLASSES = ("motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link")
INT_KEYS = ("lanes", "lanes:forward", "lanes:backward", "lanes:both_ways")
STR_KEYS = ("turn:lanes", "turn:lanes:forward", "turn:lanes:backward", "turn:lanes:both_ways")
# lane attributes whose meaning depends on the line's direction (the profile does not line-merge these features)
DIR_KEYS = ("lanes:forward", "lanes:backward", "turn:lanes", "turn:lanes:forward", "turn:lanes:backward")
BOTH_WAYS_KEYS = ("lanes:both_ways", "turn:lanes:both_ways")


def lane_count(v):
    if v is None:
        return None
    v = v.strip()
    return int(v) if v.isdigit() and 1 <= int(v) <= 30 else None


def osm_lane_values(tags) -> tuple:
    """The eight lane values as the profile writes them: integers for counts, trimmed strings for turn lanes."""
    return tuple([lane_count(tags.get(k)) for k in INT_KEYS] +
                 [(tags.get(k) or "").strip() or None for k in STR_KEYS])


def tile_lane_values(props) -> tuple:
    vals = []
    for k in INT_KEYS:
        v = props.get(k)
        vals.append(int(v) if isinstance(v, float) and v.is_integer() else v)
    return tuple(vals + [props.get(k) for k in STR_KEYS])


def direction_matters(vals: tuple) -> bool:
    """A reversed line changes the meaning of these values (turn lanes, or unequal forward/backward counts)."""
    d = dict(zip(INT_KEYS + STR_KEYS, vals))
    return (any(d[k] is not None for k in ("turn:lanes", "turn:lanes:forward", "turn:lanes:backward"))
            or d["lanes:forward"] != d["lanes:backward"])


def lonlat_to_tile(lon, lat, z):
    n = 2 ** z
    x = (lon + 180) / 360 * n
    y = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n
    return x, y


def metres_per_unit(lat, z, extent):
    return 40075016.686 * math.cos(math.radians(lat)) / (2 ** z) / extent


def read_ways(pbf: Path, ids: set):
    import osmium
    out = {}
    fp = osmium.FileProcessor(str(pbf)).with_locations().with_filter(osmium.filter.KeyFilter("highway"))
    for o in fp:
        if o.is_way() and o.id in ids:
            out[o.id] = {"tags": dict(o.tags), "coords": [(n.lon, n.lat) for n in o.nodes if n.location.valid()]}
    return out


def along(poly, p):
    """(distance, position along the line) of point p projected on polyline poly."""
    best, pos, acc = float("inf"), 0.0, 0.0
    for (x1, y1), (x2, y2) in zip(poly, poly[1:]):
        dx, dy = x2 - x1, y2 - y1
        L2 = dx * dx + dy * dy
        t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((p[0] - x1) * dx + (p[1] - y1) * dy) / L2))
        d = math.hypot(x1 + t * dx - p[0], y1 + t * dy - p[1])
        if d < best:
            best, pos = d, acc + t * math.sqrt(L2)
        acc += math.sqrt(L2)
    return best, pos


def midpoint(coords):
    k = math.cos(math.radians(coords[0][1]))
    segs = [math.hypot((b[0] - a[0]) * k, b[1] - a[1]) for a, b in zip(coords, coords[1:])]
    half, acc = sum(segs) / 2, 0.0
    for (a, b), s in zip(zip(coords, coords[1:]), segs):
        if acc + s >= half and s > 0:
            t = (half - acc) / s
            return (a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]))
        acc += s
    return coords[0]


def check_way(reader, way_id, way, zoom):
    lon, lat = midpoint(way["coords"])
    fx, fy = lonlat_to_tile(lon, lat, zoom)
    tx, ty = int(fx), int(fy)
    raw = reader.get(zoom, tx, ty)
    if raw is None:
        return {"way": way_id, "error": f"no tile {zoom}/{tx}/{ty}"}
    data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
    roads = [l for l in mvt.parse_tile(data) if l.name == "roads"]
    if not roads:
        return {"way": way_id, "error": "no roads layer"}
    layer = roads[0]
    ext = layer.extent
    to_tile = lambda lo, la: tuple((v - t) * ext for v, t in zip(lonlat_to_tile(lo, la, zoom), (tx, ty)))  # noqa: E731
    mid = to_tile(lon, lat)
    best = None
    for f in layer.features:
        fid, ftype, tags, geom = layer.feature_parts(f)
        if ftype != 2:
            continue
        for part in mvt.decode_geometry(geom):
            d, _ = along(part, mid)
            if best is None or d < best[0]:
                props = {layer.keys[tags[i]]: mvt.decode_value(layer.values[tags[i + 1]]) for i in range(0, len(tags), 2)}
                best = (d, part, props)
    mpu = metres_per_unit(lat, zoom, ext)
    t = way["tags"]
    res = {"way": way_id, "tile": f"{zoom}/{tx}/{ty}", "highway": t.get("highway"), "name": t.get("name"),
           "ref": t.get("ref"), "osm": {k: t[k] for k in INT_KEYS + STR_KEYS + ("oneway",) if k in t}}
    if best is None or best[0] * mpu > 8:
        res["error"] = "no road feature within 8 m of the way's midpoint"
        return res
    d, part, props = best
    res["distance_m"] = round(d * mpu, 2)
    res["tile_props"] = {k: props[k] for k in ("kind", "kind_detail", "oneway") + INT_KEYS + STR_KEYS if k in props}
    problems = []
    for k in INT_KEYS:
        want, got = lane_count(t.get(k)), props.get(k)
        if isinstance(got, float) and got.is_integer():
            got = int(got)
        if want != got:
            problems.append(f"{k}: OSM {t.get(k)!r} -> expected {want!r}, tile {props.get(k)!r}")
        elif got is not None and not isinstance(props.get(k), int):
            problems.append(f"{k}: tile value {props.get(k)!r} is not an integer")
    for k in STR_KEYS:
        want = t.get(k, "").strip() or None
        if want != props.get(k):
            problems.append(f"{k}: OSM {want!r}, tile {props.get(k)!r}")
    _, p0 = along(part, to_tile(*way["coords"][0]))
    _, p1 = along(part, to_tile(*way["coords"][-1]))
    res["same_direction"] = p1 > p0
    if not res["same_direction"]:
        problems.append("feature runs against the way's direction")
    res["problems"] = problems
    return res


# ---------------- coverage ----------------

_mm = _hdr = None


def _init(path, zoom):
    global _mm, _hdr, _hdr_zoom
    _hdr_zoom = zoom
    f = open(path, "rb")
    _mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
    _hdr = deserialize_header(_mm[0:127])


def _cov(job):
    acc = defaultdict(lambda: defaultdict(float))
    for off, ln, run, lat in job:
        raw = _mm[_hdr["tile_data_offset"] + off:_hdr["tile_data_offset"] + off + ln]
        data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
        for l in mvt.parse_tile(data):
            if l.name != "roads":
                continue
            mpu = metres_per_unit(lat, _hdr_zoom, l.extent)
            for f in l.features:
                fid, ftype, tags, geom = l.feature_parts(f)
                props = {l.keys[tags[i]]: mvt.decode_value(l.values[tags[i + 1]]) for i in range(0, len(tags), 2)}
                kd = props.get("kind_detail")
                if kd not in CLASSES:
                    continue
                length = 0.0
                for part in mvt.decode_geometry(geom):
                    # count only the part inside the tile (the buffer is counted by the neighbour)
                    for (x1, y1), (x2, y2) in zip(part, part[1:]):
                        mx, my = (x1 + x2) / 2, (y1 + y2) / 2
                        if 0 <= mx < l.extent and 0 <= my < l.extent:
                            length += math.hypot(x2 - x1, y2 - y1) * mpu
                length *= run
                acc[kd]["length_m"] += length
                if "lanes" in props:
                    acc[kd]["lanes_m"] += length
                if any(k in props for k in STR_KEYS):
                    acc[kd]["turn_m"] += length
                if any(k in props for k in BOTH_WAYS_KEYS):
                    acc[kd]["both_ways_m"] += length
                if any(k in props for k in INT_KEYS + STR_KEYS):
                    acc[kd]["any_lane_attr_m"] += length
    return {k: dict(v) for k, v in acc.items()}


_hdr_zoom = 14


def tile_coverage(pack: Path, zoom: int, procs: int):
    with open(pack, "rb") as f:
        src = MmapSource(f)
        h = Reader(src).header()
        ents = list(entries(src, h))
    jobs = []
    for tid, off, ln, run in ents:
        z, x, y = tileid_to_zxy(tid)
        if z == zoom:
            lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + 0.5) / 2 ** z))))
            jobs.append((off, ln, run, lat))
    chunks = [jobs[i::procs * 8] for i in range(procs * 8)]
    total = defaultdict(lambda: defaultdict(float))
    with Pool(procs, initializer=_init, initargs=(str(pack), zoom)) as pool:
        for part in pool.imap_unordered(_cov, chunks):
            for kd, d in part.items():
                for k, v in d.items():
                    total[kd][k] += v
    return {kd: {k[:-2] + "_km": round(v / 1000, 1) for k, v in d.items()} for kd, d in total.items()}


def osm_coverage(pbf: Path):
    import osmium
    acc = {c: defaultdict(float) for c in CLASSES}
    fp = osmium.FileProcessor(str(pbf)).with_locations().with_filter(osmium.filter.KeyFilter("highway"))
    for o in fp:
        if not o.is_way():
            continue
        hw = o.tags.get("highway")
        if hw not in acc:
            continue
        pts = [(n.lon, n.lat) for n in o.nodes if n.location.valid()]
        L = 0.0
        for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
            k = math.cos(math.radians((y1 + y2) / 2))
            L += math.hypot((x2 - x1) * k * 111320, (y2 - y1) * 110574)
        a = acc[hw]
        a["ways"] += 1
        a["length_km"] += L / 1000
        if "lanes" in o.tags:
            a["ways_lanes"] += 1
            a["lanes_km"] += L / 1000
        if any(k in o.tags for k in STR_KEYS):
            a["ways_turn"] += 1
            a["turn_km"] += L / 1000
        if any(k in o.tags for k in STR_KEYS if k != "turn:lanes:both_ways"):
            a["ways_turn_no_both_ways"] += 1
        if any(k in o.tags for k in BOTH_WAYS_KEYS):
            a["ways_both_ways"] += 1
            a["both_ways_km"] += L / 1000
    return {c: {k: round(v, 1) for k, v in d.items()} for c, d in acc.items()}


# ---------------- direction of every direction-dependent feature ----------------

def tile_to_lonlat(z, x, y, px, py, ext):
    n = 2 ** z
    lon = (x + px / ext) / n * 360 - 180
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + py / ext) / n))))
    return lon, lat


def _dir_feats(job):
    """Road features of some tiles that carry a direction-dependent lane value: (values, oneway, segments, tile)."""
    out = []
    for tid, off, ln in job:
        z, x, y = tileid_to_zxy(tid)
        raw = _mm[_hdr["tile_data_offset"] + off:_hdr["tile_data_offset"] + off + ln]
        data = gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw
        for l in mvt.parse_tile(data):
            if l.name != "roads" or not set(l.keys) & set(DIR_KEYS):
                continue
            ext = l.extent
            for f in l.features:
                fid, ftype, tags, geom = l.feature_parts(f)
                if ftype != 2:
                    continue
                props = {l.keys[tags[i]]: mvt.decode_value(l.values[tags[i + 1]]) for i in range(0, len(tags), 2)}
                if not any(k in props for k in DIR_KEYS):
                    continue
                # one verdict per line (part of a multi-line feature): a merged feature can hold one reversed
                # line next to correct ones
                for part in mvt.decode_geometry(geom):
                    segs = []
                    for (x1, y1), (x2, y2) in zip(part, part[1:]):
                        # only segments inside the tile: the buffer is checked in the neighbouring tile
                        if 0 <= (x1 + x2) / 2 < ext and 0 <= (y1 + y2) / 2 < ext and (x1, y1) != (x2, y2):
                            segs.append((tile_to_lonlat(z, x, y, x1, y1, ext), tile_to_lonlat(z, x, y, x2, y2, ext)))
                    if segs:
                        out.append((tile_lane_values(props), props.get("oneway"), segs, f"{z}/{x}/{y}"))
    return out


def _metric(lon, lat):
    """Local metric coordinates (m); good to well under 1 % over the few metres compared here."""
    return lon * 111320 * math.cos(math.radians(lat)), lat * 110574


def direction_check(pack: Path, pbf: Path, zoom: int, procs: int, max_d: float = 3.0):
    import osmium
    from shapely.geometry import LineString, Point
    from shapely.strtree import STRtree
    with open(pack, "rb") as f:
        src = MmapSource(f)
        rd = Reader(src)
        h = rd.header()
        meta = rd.metadata()
        ents = list(entries(src, h))
    # compare only the lane keys this pack carries at all (packs of profile patch 1 have no :both_ways keys)
    fields = next((l.get("fields", {}) for l in meta.get("vector_layers", []) if l.get("id") == "roads"), {})
    used = [i for i, k in enumerate(INT_KEYS + STR_KEYS) if k in fields] or list(range(len(INT_KEYS + STR_KEYS)))
    pick = lambda vals: tuple(vals[i] for i in used)  # noqa: E731
    jobs = [(tid + r, off, ln) for tid, off, ln, run in ents for r in range(run) if tileid_to_zxy(tid)[0] == zoom]
    chunks = [jobs[i::procs * 8] for i in range(procs * 8)]
    feats = []
    with Pool(procs, initializer=_init, initargs=(str(pack), zoom)) as pool:
        for part in pool.imap_unordered(_dir_feats, chunks):
            feats.extend(part)
    ways_vals, ways_ids, lines = [], [], []
    fp = osmium.FileProcessor(str(pbf)).with_locations().with_filter(osmium.filter.KeyFilter("highway"))
    for o in fp:
        if not o.is_way() or o.tags.get("highway") not in CLASSES:
            continue
        vals = osm_lane_values(o.tags)
        d = dict(zip(INT_KEYS + STR_KEYS, vals))
        if not any(d[k] is not None for k in DIR_KEYS):
            continue
        pts = [_metric(n.lon, n.lat) for n in o.nodes if n.location.valid()]
        if len(pts) < 2:
            continue
        ways_vals.append(pick(vals))
        ways_ids.append(o.id)
        lines.append(LineString(pts))
    tree = STRtree(lines)
    summary = {"keys_compared": [k for i, k in enumerate(INT_KEYS + STR_KEYS) if i in used],
               "tile_lines": len(feats), "osm_ways": len(lines), "segments": 0, "segments_voting": 0,
               "segments_ambiguous": 0, "segments_unmatched": 0, "lines_unmatched": 0, "lines_ok": 0,
               "lines_reversed_matters": 0, "lines_reversed_harmless": 0,
               "lines_oneway": 0, "lines_twoway": 0}
    bad = []
    for vals, oneway, segs, tile in feats:
        same = opp = 0
        ways_hit = set()
        for a_, b_ in segs:
            summary["segments"] += 1
            pa, pb = Point(_metric(*a_)), Point(_metric(*b_))
            mid = Point((pa.x + pb.x) / 2, (pa.y + pb.y) / 2)
            signs = set()
            for ci in tree.query(mid.buffer(max_d)):
                if ways_vals[ci] != pick(vals) or lines[ci].distance(mid) > max_d:
                    continue
                dpos = lines[ci].project(pb) - lines[ci].project(pa)
                if dpos != 0:
                    signs.add(1 if dpos > 0 else -1)
                    ways_hit.add(ways_ids[ci])
            if not signs:
                summary["segments_unmatched"] += 1
            elif len(signs) > 1:
                summary["segments_ambiguous"] += 1
            else:
                summary["segments_voting"] += 1
                if signs.pop() > 0:
                    same += 1
                else:
                    opp += 1
        summary["lines_oneway" if str(oneway) in ("yes", "1", "true") else "lines_twoway"] += 1
        if same + opp == 0:
            summary["lines_unmatched"] += 1
        elif opp > same:
            key = "lines_reversed_matters" if direction_matters(vals) else "lines_reversed_harmless"
            summary[key] += 1
            if len(bad) < 30:
                bad.append({"tile": tile, "oneway": oneway, "votes_same": same, "votes_reversed": opp,
                            "osm_ways": sorted(ways_hit)[:5],
                            "values": {k: v for k, v in zip(INT_KEYS + STR_KEYS, vals) if v is not None}})
        else:
            summary["lines_ok"] += 1
    summary["reversed_examples"] = bad
    return summary


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pack")
    ap.add_argument("--state", default="pa")
    ap.add_argument("--zoom", type=int, default=14)
    ap.add_argument("--coverage", action="store_true")
    ap.add_argument("--direction", action="store_true",
                    help="check the direction of every feature with a direction-dependent lane attribute")
    ap.add_argument("--procs", type=int, default=4)
    ap.add_argument("--out-dir", help="write the report here instead of next to the pack")
    a = ap.parse_args()
    pack = Path(a.pack)
    pbf = REPO / CONFIG["states"][a.state]["osm"]
    cps = json.loads((HERE / "lanes_checkpoints.json").read_text()).get(a.state, [])
    ids = {w for cp in cps for w in cp["ways"]}
    ways = read_ways(pbf, ids)
    out = {"pack": pack.name, "zoom": a.zoom, "osm": Path(pbf).name, "checkpoints": []}
    bad = 0
    with open(pack, "rb") as f:
        reader = Reader(MmapSource(f))
        for cp in cps:
            rows = []
            for wid in cp["ways"]:
                if wid not in ways:
                    rows.append({"way": wid, "error": "way not in the extract"})
                    bad += 1
                    continue
                r = check_way(reader, wid, ways[wid], a.zoom)
                for k, v in cp.get("expect", {}).get(str(wid), {}).items():
                    tv = r.get("tile_props", {}).get(k)
                    if tv != v:
                        r.setdefault("problems", []).append(f"expected {k}={v!r} (checkpoint), tile {tv!r}")
                if r.get("error") or r.get("problems"):
                    bad += 1
                rows.append(r)
            out["checkpoints"].append({"name": cp["name"], "ways": rows})
    for cp in out["checkpoints"]:
        print(f"== {cp['name']}")
        for r in cp["ways"]:
            tp = r.get("tile_props", {})
            lane_txt = ", ".join(f"{k}={tp[k]!r}" for k in INT_KEYS + STR_KEYS if k in tp) or "no lane attributes"
            status = "OK" if not (r.get("error") or r.get("problems")) else "MISMATCH " + "; ".join(
                [r.get("error", "")] + r.get("problems", []))
            print(f"  way {r['way']} {r.get('highway')} {r.get('ref') or r.get('name') or ''} tile {r.get('tile')} "
                  f"d={r.get('distance_m')} m dir={'same' if r.get('same_direction') else 'REVERSED' if 'same_direction' in r else '?'}: "
                  f"{lane_txt}  [{status}]")
    if a.coverage:
        out["coverage_osm"] = osm_coverage(pbf)
        out["coverage_tiles"] = tile_coverage(pack, a.zoom, a.procs)
        print("ways% = share of OSM ways; km% = share of length; turn = any turn:lanes* key; "
              "both = lanes:both_ways or turn:lanes:both_ways")
        print(f"{'class':14} {'OSM ways':>9} {'lanes%':>7} {'turn%':>6} {'both%':>6} {'OSM km':>8} {'lanes%':>7} "
              f"{'turn%':>6} {'both%':>6} | {'tile km':>8} {'lanes%':>7} {'turn%':>6} {'both%':>6}")
        for c in CLASSES:
            o = out["coverage_osm"].get(c, {})
            t = out["coverage_tiles"].get(c, {})
            pct = lambda a_, b_: f"{100 * a_ / b_:.1f}" if b_ else "-"  # noqa: E731
            print(f"{c:14} {o.get('ways', 0):>9.0f} {pct(o.get('ways_lanes', 0), o.get('ways', 0)):>7} "
                  f"{pct(o.get('ways_turn', 0), o.get('ways', 0)):>6} "
                  f"{pct(o.get('ways_both_ways', 0), o.get('ways', 0)):>6} {o.get('length_km', 0):>8.0f} "
                  f"{pct(o.get('lanes_km', 0), o.get('length_km', 0)):>7} {pct(o.get('turn_km', 0), o.get('length_km', 0)):>6} "
                  f"{pct(o.get('both_ways_km', 0), o.get('length_km', 0)):>6} | "
                  f"{t.get('length_km', 0):>8.0f} {pct(t.get('lanes_km', 0), t.get('length_km', 0)):>7} "
                  f"{pct(t.get('turn_km', 0), t.get('length_km', 0)):>6} "
                  f"{pct(t.get('both_ways_km', 0), t.get('length_km', 0)):>6}")
    if a.direction:
        d = direction_check(pack, pbf, a.zoom, a.procs)
        out["direction"] = d
        print(f"direction: {d['tile_lines']} lines (feature parts) with direction-dependent lane values "
              f"({d['lines_oneway']} oneway, {d['lines_twoway']} two-way), {d['osm_ways']} OSM ways; "
              f"{d['lines_ok']} in the way's direction, {d['lines_reversed_matters']} reversed where it matters, "
              f"{d['lines_reversed_harmless']} reversed but symmetric, {d['lines_unmatched']} not matched; "
              f"segments {d['segments']}: {d['segments_voting']} voting, {d['segments_ambiguous']} ambiguous, "
              f"{d['segments_unmatched']} unmatched")
        for e in d["reversed_examples"][:10]:
            print("  reversed:", json.dumps(e, ensure_ascii=False))
        bad += d["lines_reversed_matters"]
    out["mismatches"] = bad
    dest_dir = Path(a.out_dir) if a.out_dir else pack.parent
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / (pack.stem + f".lanes-z{a.zoom}.json")
    dest.write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"{bad} mismatches -> {dest}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
