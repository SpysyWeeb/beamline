"""What makes a pack big, and what dropping things would save.

Usage (from the repo root):
    # bytes per zoom and layer (and per sub-group: building polygons vs house numbers, ...)
    .venv/map/Scripts/python packbuilder/map/size_report.py breakdown data/map/packs-v2/pa-261114-z15.pmtiles
    # write a copy with some features removed and measure it (rules: size_options.json)
    .venv/map/Scripts/python packbuilder/map/size_report.py option data/map/packs-v2/pa-261114-z15.pmtiles poi_drop
    options: --procs 4, --keep (keep the rewritten copy in data/map/work/size-options/),
             --out-dir DIR (breakdown: write the .breakdown.json there instead of next to the pack;
             option: work in DIR instead of data/map/work/size-options)

breakdown: every unique tile is decompressed and each layer is gzipped on its
own (level 6, like Planetiler); a tile's stored bytes are shared out over its
layers in proportion to those per-layer sizes ("stored MB"). Sub-groups inside
a layer (e.g. house numbers vs building polygons) share the layer's stored
bytes in proportion to their uncompressed feature bytes. Results go to
<pack>.breakdown.json.

option: applies one named rule set from size_options.json to every tile
(features removed by layer, kind, geometry type or polygon area at the
pack's max zoom), writes a full PMTiles copy with the pmtiles Python writer
(gzip level 6) and reports its size next to the input's. The input is also
re-written with no rule ("identity") so the comparison is not skewed by the
writer: Planetiler and Python gzip and deduplicate slightly differently.
"""
import argparse
import gzip
import json
import mmap
import sys
import time
from collections import defaultdict
from multiprocessing import Pool
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mvt  # noqa: E402
from pmtiles.reader import MmapSource, Reader  # noqa: E402
from pmtiles.tile import deserialize_header, tileid_to_zxy  # noqa: E402
from pmtiles.writer import Writer  # noqa: E402
from tile_stats import entries  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
OPTIONS = json.loads((HERE / "size_options.json").read_text())

_mm = _hdr = None
_rules = None


def _init(path, rules=None):
    global _mm, _hdr, _rules
    f = open(path, "rb")
    _mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
    _hdr = deserialize_header(_mm[0:127])
    _rules = rules


def _tile(off, ln):
    raw = _mm[_hdr["tile_data_offset"] + off:_hdr["tile_data_offset"] + off + ln]
    return gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw


def subgroup(layer_name, props, ftype):
    if layer_name == "buildings":
        return "house numbers" if props.get("kind") == "address" else "building polygons"
    if layer_name == "pois":
        drop = set(json.loads((HERE / "build_config.json").read_text())["poi_drop_kinds"])
        return "kinds in poi_drop_kinds" if props.get("kind") in drop else "other POIs"
    if layer_name == "roads":
        return f"roads {props.get('kind')}"
    if layer_name == "landuse":
        return f"landuse {props.get('kind')}"
    if layer_name == "water":
        return "water lines" if ftype == 2 else ("water labels" if ftype == 1 else "water polygons")
    return layer_name


def _breakdown(job):
    acc = defaultdict(lambda: [0, 0.0, 0, 0])  # (z, layer, group) -> [features, stored bytes, raw bytes, gz bytes]
    for off, ln, z, run in job:
        layers = mvt.parse_tile(_tile(off, ln))
        gz = [len(gzip.compress(mvt.enc_field(3, bytes(l.raw)), 6, mtime=0)) for l in layers]
        tot = sum(gz) or 1
        for l, g in zip(layers, gz):
            stored = ln * g / tot
            groups = defaultdict(lambda: [0, 0])
            for f in l.features:
                fid, ftype, tags, geom = l.feature_parts(f)
                props = {l.keys[tags[i]]: mvt.decode_value(l.values[tags[i + 1]]) for i in range(0, len(tags), 2)} \
                    if l.name in ("buildings", "pois", "roads", "landuse") else {}
                gr = groups[subgroup(l.name, props, ftype)]
                gr[0] += 1
                gr[1] += len(f)
            fb = sum(v[1] for v in groups.values()) or 1
            for name, (n, b) in groups.items():
                a = acc[(z, l.name, name)]
                a[0] += n * run
                a[1] += stored * b / fb * run
                a[2] += b * run
                a[3] += g * b / fb * run
    return {"|".join(map(str, k)): v for k, v in acc.items()}


def breakdown(pack: Path, procs: int):
    with open(pack, "rb") as f:
        src = MmapSource(f)
        h = Reader(src).header()
        ents = list(entries(src, h))
    uniq = {}
    for tid, off, ln, run in ents:
        if off in uniq:
            uniq[off][3] += run
        else:
            uniq[off] = [off, ln, tileid_to_zxy(tid)[0], run]
    jobs = list(uniq.values())
    chunks = [jobs[i::procs * 16] for i in range(procs * 16)]
    total = defaultdict(lambda: [0, 0.0, 0, 0])
    with Pool(procs, initializer=_init, initargs=(str(pack),)) as pool:
        for part in pool.imap_unordered(_breakdown, chunks):
            for k, v in part.items():
                t = total[k]
                for i in range(4):
                    t[i] += v[i]
    rows = []
    for k, (n, stored, rawb, gzb) in total.items():
        z, layer, group = k.split("|")
        rows.append({"zoom": int(z), "layer": layer, "group": group, "features": n,
                     "stored_mb": round(stored / 1e6, 3), "raw_mb": round(rawb / 1e6, 3)})
    rows.sort(key=lambda r: (r["zoom"], -r["stored_mb"]))
    per_zoom = defaultdict(float)
    per_layer = defaultdict(float)
    for r in rows:
        per_zoom[r["zoom"]] += r["stored_mb"]
        per_layer[r["layer"]] += r["stored_mb"]
    return {"pack": pack.name, "file_mb": round(pack.stat().st_size / 1e6, 2),
            "per_zoom_mb": {z: round(v, 2) for z, v in sorted(per_zoom.items())},
            "per_layer_mb": {k: round(v, 2) for k, v in sorted(per_layer.items(), key=lambda kv: -kv[1])},
            "rows": rows}


# ---------------- rewrite with rules ----------------

def polygon_area_px(geom, extent):
    """Area of a polygon feature in pixels of a 256-px tile."""
    rings = mvt.decode_geometry(geom)
    a = sum(mvt.ring_area(r) for r in rings)  # exterior positive, holes negative
    return abs(a) * (256 / extent) ** 2


def apply_rules(layer, props, ftype, geom, z, maxzoom, rules):
    """None to drop the feature, else its (possibly simplified) geometry."""
    for r in rules:
        if r.get("layer") != layer.name:
            continue
        if "zooms" in r and z not in r["zooms"]:
            continue
        if r.get("only_max_zoom") and z != maxzoom:
            continue
        if "kind" in r and props.get("kind") not in r["kind"]:
            continue
        if "kind_not" in r and props.get("kind") in r["kind_not"]:
            continue
        if "kind_detail" in r and props.get("kind_detail") not in r["kind_detail"]:
            continue
        if "geometry" in r and ftype != {"point": 1, "line": 2, "polygon": 3}[r["geometry"]]:
            continue
        if "max_area_px" in r:
            if ftype != 3 or polygon_area_px(geom, layer.extent) >= r["max_area_px"]:
                continue
        if "simplify_px" in r:
            if ftype in (2, 3):
                geom = simplify_geometry(geom, ftype, r["simplify_px"] * layer.extent / 256)
                if geom is None:
                    return None
            continue
        return None
    return geom


def encode_parts(parts, closed):
    """Parts in tile units -> MVT command stream (rings: MoveTo, LineTo, ClosePath)."""
    out, cx, cy = [], 0, 0
    zz = lambda n: (n << 1) ^ (n >> 31)  # noqa: E731
    for part in parts:
        pts = part[:-1] if closed else part
        out += [(1 & 7) | (1 << 3), zz(pts[0][0] - cx), zz(pts[0][1] - cy)]
        cx, cy = pts[0]
        out.append((2 & 7) | ((len(pts) - 1) << 3))
        for x, y in pts[1:]:
            out += [zz(x - cx), zz(y - cy)]
            cx, cy = x, y
        if closed:
            out.append((7 & 7) | (1 << 3))
    return b"".join(mvt.enc_varint(v) for v in out)


def simplify_geometry(geom, ftype, tol):
    """Douglas-Peucker (shapely, topology preserving) on a line or polygon feature, in tile units."""
    from shapely.geometry import LineString, Polygon
    from shapely.geometry.polygon import orient
    parts = mvt.decode_geometry(geom)
    out = []
    if ftype == 2:
        for p in parts:
            if len(p) < 3:
                out.append(p)
                continue
            q = [(round(x), round(y)) for x, y in LineString(p).simplify(tol).coords]
            q = [pt for i, pt in enumerate(q) if i == 0 or pt != q[i - 1]]
            if len(q) >= 2:
                out.append(q)
        return encode_parts(out, False) if out else None
    # polygons: an exterior ring (positive area) followed by its holes
    polys = []
    for ring in parts:
        if len(ring) < 4:
            continue
        if mvt.ring_area(ring) > 0 or not polys:
            polys.append([ring])
        else:
            polys[-1].append(ring)
    for rings in polys:
        poly = Polygon(rings[0], rings[1:]).simplify(tol)
        if poly.is_empty or poly.geom_type != "Polygon":
            continue
        poly = orient(poly, 1.0)
        for i, ring in enumerate([poly.exterior] + list(poly.interiors)):
            q = [(round(x), round(y)) for x, y in ring.coords]
            q = [pt for k, pt in enumerate(q) if k == 0 or pt != q[k - 1]]
            if len(q) >= 4 and mvt.ring_area(q) != 0:
                if (mvt.ring_area(q) > 0) != (i == 0):
                    continue  # rounding flipped a sliver: leave it out
                out.append(q)
    return encode_parts(out, True) if out else None


def rewrite_tile(data, z, maxzoom, rules):
    out = b""
    changed = False
    for l in mvt.parse_tile(data):
        if any(r.get("layer") == l.name and r.get("drop_layer") and (not r.get("only_max_zoom") or z == maxzoom)
               for r in rules):
            changed = True
            continue
        feats = []
        touched = False
        for f in l.features:
            fid, ftype, tags, geom = l.feature_parts(f)
            props = {l.keys[tags[i]]: mvt.decode_value(l.values[tags[i + 1]]) for i in range(0, len(tags), 2)}
            g = apply_rules(l, props, ftype, geom, z, maxzoom, rules)
            if g is None:
                touched = True
            elif g is geom:
                feats.append(f)
            else:
                touched = True
                feats.append(mvt.enc_feature(fid, ftype, tags, g))
        if not touched:
            out += mvt.enc_field(3, bytes(l.raw))
            continue
        changed = True
        if not feats:
            continue
        # keep the key/value tables as they are (unused entries cost a few bytes; fine for a measurement)
        body = mvt.enc_varint(15 << 3) + mvt.enc_varint(l.version) + mvt.enc_field(1, l.name.encode())
        body += b"".join(mvt.enc_field(2, f) for f in feats)
        body += b"".join(mvt.enc_field(3, k.encode()) for k in l.keys)
        body += b"".join(mvt.enc_field(4, v) for v in l.values)
        body += mvt.enc_varint(5 << 3) + mvt.enc_varint(l.extent)
        out += mvt.enc_field(3, body)
    return out, changed


def _rewrite(job):
    res = []
    maxzoom = _hdr["max_zoom"]
    for off, ln, z in job:
        data, changed = rewrite_tile(_tile(off, ln), z, maxzoom, _rules)
        res.append((off, gzip.compress(data, 6, mtime=0) if data else None, changed))
    return res


def rewrite(pack: Path, rules, dest: Path, procs: int):
    with open(pack, "rb") as f:
        src = MmapSource(f)
        rd = Reader(src)
        h, meta = rd.header(), rd.metadata()
        ents = sorted(entries(src, h))
    uniq = {}
    for tid, off, ln, run in ents:
        uniq.setdefault(off, (off, ln, tileid_to_zxy(tid)[0]))
    jobs = list(uniq.values())
    chunks = [jobs[i:i + 256] for i in range(0, len(jobs), 256)]
    new = {}
    changed = 0
    with Pool(procs, initializer=_init, initargs=(str(pack), rules)) as pool:
        for part in pool.imap_unordered(_rewrite, chunks):
            for off, data, ch in part:
                new[off] = data
                changed += ch
    dest.parent.mkdir(parents=True, exist_ok=True)
    tiles = 0
    with open(dest, "wb") as fo:
        w = Writer(fo)
        for tid, off, ln, run in ents:
            data = new[off]
            if data is None:
                continue
            for k in range(run):
                w.write_tile(tid + k, data)
                tiles += 1
        w.finalize(dict(h), meta)
    return {"bytes": dest.stat().st_size, "tiles_changed": changed, "tiles_written": tiles}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("mode", choices=["breakdown", "option"])
    ap.add_argument("pack")
    ap.add_argument("option", nargs="?")
    ap.add_argument("--procs", type=int, default=4)
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--out-dir", help="breakdown: write <pack>.breakdown.json here instead of next to the pack; "
                    "option: use this folder instead of data/map/work/size-options")
    a = ap.parse_args()
    pack = Path(a.pack)
    t0 = time.time()
    if a.out_dir:
        Path(a.out_dir).mkdir(parents=True, exist_ok=True)
    if a.mode == "breakdown":
        rep = breakdown(pack, a.procs)
        rep["seconds"] = round(time.time() - t0, 1)
        dest = (Path(a.out_dir) if a.out_dir else pack.parent) / (pack.stem + ".breakdown.json")
        dest.write_text(json.dumps(rep, indent=1), encoding="utf-8")
        print(f"{pack.name}: {rep['file_mb']} MB")
        print("per zoom (stored MB):", rep["per_zoom_mb"])
        print("per layer (stored MB):", rep["per_layer_mb"])
        top = sorted(rep["rows"], key=lambda r: -r["stored_mb"])[:25]
        for r in top:
            print(f"  z{r['zoom']:<2} {r['layer']:10} {r['group']:28} {r['features']:>9} features  {r['stored_mb']:8.2f} MB")
        return 0
    opt = OPTIONS[a.option]
    work = Path(a.out_dir) if a.out_dir else REPO / "data" / "map" / "work" / "size-options"
    work.mkdir(parents=True, exist_ok=True)
    ident = work / f"{pack.stem}.identity.pmtiles"
    res = {"pack": pack.name, "option": a.option, "rules": opt["rules"], "what_is_lost": opt.get("what_is_lost")}
    if not ident.exists():
        res["identity"] = rewrite(pack, [], ident, a.procs)
    res["identity_bytes"] = ident.stat().st_size
    dest = work / f"{pack.stem}.{a.option}.pmtiles"
    res["result"] = rewrite(pack, opt["rules"], dest, a.procs)
    res["input_bytes"] = pack.stat().st_size
    res["saved_bytes_vs_identity"] = res["identity_bytes"] - res["result"]["bytes"]
    res["saved_pct"] = round(100 * res["saved_bytes_vs_identity"] / res["identity_bytes"], 1)
    res["estimated_pack_bytes"] = res["input_bytes"] - res["saved_bytes_vs_identity"]
    res["seconds"] = round(time.time() - t0, 1)
    out = work / f"{pack.stem}.{a.option}.json"
    out.write_text(json.dumps(res, indent=1), encoding="utf-8")
    if not a.keep:
        dest.unlink()
    print(f"{pack.name} + {a.option}: identity rewrite {res['identity_bytes'] / 1e6:.1f} MB -> "
          f"{res['result']['bytes'] / 1e6:.1f} MB ({-res['saved_pct']}%), estimated pack "
          f"{res['estimated_pack_bytes'] / 1e6:.1f} MB (input {res['input_bytes'] / 1e6:.1f} MB); "
          f"{res['result']['tiles_changed']} tiles changed, {res['seconds']} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
