"""Describe the border seam between two state packs: the tiles both packs contain.

Usage (from the repo root):
    .venv/map/Scripts/python packbuilder/map/seam_report.py data/map/packs/pa-261114-z0-15.pmtiles data/map/packs/oh-261114-z0-15.pmtiles

For every tile present in both packs it counts, per zoom: features from each
pack, features that are byte-identical in both (what merge_packs.py drops), and
named features (places, POIs, roads) whose layer, kind and name occur in both
packs' copy of the tile but with different geometry: these survive a merge and
are the candidates for duplicated labels. Writes data/map/work/seam_report.json,
or the file given with --out.

Second round: also counts, per zoom, POIs and places of each pack that lie
outside that pack's own state outline (data/map/work/clip-v2/<state>.geojson,
TIGER) by more than 50 m: the overlap band of the first round's buffered
Geofabrik outlines. Pass --states pa oh to name the two packs' states.
"""
import gzip
import json
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mvt  # noqa: E402
from pmtiles.reader import Reader, MmapSource  # noqa: E402
from pmtiles.tile import tileid_to_zxy  # noqa: E402
from tile_stats import entries  # noqa: E402

REPO = Path(__file__).resolve().parents[2]


def index(src, header):
    out = {}
    for tid, off, ln, run in entries(src, header):
        for k in range(run):
            out[tid + k] = (off, ln)
    return out


def outline(state):
    from shapely.geometry import shape
    from shapely.prepared import prep
    p = REPO / "data" / "map" / "work" / "clip-v2" / f"{state}.geojson"
    g = shape(json.loads(p.read_text())["features"][0]["geometry"])
    # about 50 m inwards tolerance: a point counts as outside only if it is 50 m beyond the line
    return prep(g.buffer(0.0006))


def tile_point(geom, z, x, y, extent):
    import math
    parts = mvt.decode_geometry(geom)
    if not parts:
        return None
    px, py = parts[0][0]
    if not (0 <= px < extent and 0 <= py < extent):  # in the tile buffer: counted in the neighbour tile
        return None
    n = 2 ** z
    lon = (x + px / extent) / n * 360 - 180
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + py / extent) / n))))
    return lon, lat


def main() -> int:
    import argparse
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("a")
    ap.add_argument("b")
    ap.add_argument("--out", default=str(REPO / "data" / "map" / "work" / "seam_report.json"))
    ap.add_argument("--states", nargs=2, help="state keys of the two packs, e.g. pa oh: count points outside each state")
    args = ap.parse_args()
    a_path, b_path = args.a, args.b
    shapes = [outline(s) for s in args.states] if args.states else None
    fa, fb = open(a_path, "rb"), open(b_path, "rb")
    sa, sb = MmapSource(fa), MmapSource(fb)
    ha, hb = Reader(sa).header(), Reader(sb).header()
    ia, ib = index(sa, ha), index(sb, hb)
    common = sorted(set(ia) & set(ib))
    per_zoom = defaultdict(lambda: defaultdict(int))
    examples = defaultdict(list)
    for tid in common:
        z, x, y = tileid_to_zxy(tid)
        ta = gzip.decompress(sa(ha["tile_data_offset"] + ia[tid][0], ia[tid][1]))
        tb = gzip.decompress(sb(hb["tile_data_offset"] + ib[tid][0], ib[tid][1]))
        la = {l.name: l for l in mvt.parse_tile(ta)}
        lb = {l.name: l for l in mvt.parse_tile(tb)}
        pz = per_zoom[z]
        pz["tiles"] += 1
        if shapes:
            from shapely.geometry import Point
            for tag, layers, shp in (("a", la, shapes[0]), ("b", lb, shapes[1])):
                for lname in ("pois", "places"):
                    if lname not in layers:
                        continue
                    l = layers[lname]
                    for fb in l.features:
                        _, ftype, _, geom = l.feature_parts(fb)
                        pt = tile_point(geom, z, x, y, l.extent) if ftype == 1 else None
                        if pt and not shp.contains(Point(pt)):
                            pz[f"{lname}_outside_own_state_{tag}"] += 1
        for name in set(la) | set(lb):
            fa_ = la[name].features if name in la else []
            fb_ = lb[name].features if name in lb else []
            pz["features_a"] += len(fa_)
            pz["features_b"] += len(fb_)
            if name in la and name in lb:
                _, d = mvt.merge_layers(la[name], lb[name])
                pz["identical"] += d
            if name not in ("places", "pois", "roads") or name not in la or name not in lb:
                continue
            na = defaultdict(int)
            for f in fa_:
                _, p = la[name].feature_props(f)
                if p.get("name"):
                    na[(p.get("kind"), p["name"])] += 1
            nb = defaultdict(int)
            for f in fb_:
                _, p = lb[name].feature_props(f)
                if p.get("name"):
                    nb[(p.get("kind"), p["name"])] += 1
            both = set(na) & set(nb)
            pz[f"same_name_{name}"] += len(both)
            if len(examples[f"{z}:{name}"]) < 5:
                examples[f"{z}:{name}"] += [f"{k[0]}: {k[1]}" for k in sorted(both)][:5]
    rep = {
        "a": Path(a_path).name, "b": Path(b_path).name,
        "common_tiles": len(common),
        "per_zoom": {z: dict(v) for z, v in sorted(per_zoom.items())},
        "examples_same_name_both_packs": dict(examples),
        "note": "same_name_X counts (kind, name) pairs present in both packs' copy of a border tile; "
                "identical = byte-identical features dropped by merge_packs.py",
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(rep, indent=1, ensure_ascii=False), encoding="utf-8")
    for z, v in rep["per_zoom"].items():
        print(z, v)
    print(f"-> {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
