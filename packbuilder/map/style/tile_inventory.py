"""List what the map tiles carry for shields and POIs in an area (read-only).

Usage (from the repo root):
    .venv/art/Scripts/python packbuilder/map/style/tile_inventory.py data/map/packs/pa-261114-z0-15.pmtiles
        [--bbox -80.25,41.95,-79.95,42.17] [--zoom 14] [--out data/art/inventory/erie.json]

For every tile of --zoom inside --bbox it counts:
  - roads: (network, shield_text) pairs and the shield_text lengths per network,
    with the road kinds that carry them and a few example refs;
  - POIs: kind -> count and the min_zoom values seen.
The style uses these to pick shield artwork per network and icon per POI kind.
Reads tiles with the pmtiles reader and packbuilder/map/mvt.py (both read-only).
"""
import argparse
import gzip
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

from pmtiles.reader import MmapSource, Reader

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "packbuilder" / "map"))
import mvt  # noqa: E402  (the map track's reader, used read-only)


def tile_range(bbox, z):
    w, s, e, n = bbox

    def xy(lon, lat):
        x = (lon + 180) / 360 * 2 ** z
        y = (1 - math.log(math.tan(math.radians(lat)) + 1 / math.cos(math.radians(lat))) / math.pi) / 2 * 2 ** z
        return int(x), int(y)
    x0, y0 = xy(w, n)
    x1, y1 = xy(e, s)
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            yield x, y


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pack")
    ap.add_argument("--bbox", default="-80.25,41.95,-79.95,42.17", help="w,s,e,n (default: Erie, PA)")
    ap.add_argument("--zoom", type=int, default=14)
    ap.add_argument("--out")
    a = ap.parse_args()
    bbox = [float(v) for v in a.bbox.split(",")]
    shields = Counter()
    lengths = defaultdict(Counter)
    kinds_by_network = defaultdict(Counter)
    refs = defaultdict(set)
    pois = Counter()
    poi_minzoom = defaultdict(Counter)
    tiles = 0
    with open(a.pack, "rb") as f:
        reader = Reader(MmapSource(f))
        for x, y in tile_range(bbox, a.zoom):
            data = reader.get(a.zoom, x, y)
            if not data:
                continue
            if data[:2] == b"\x1f\x8b":
                data = gzip.decompress(data)
            tiles += 1
            for layer in mvt.parse_tile(data):
                if layer.name == "roads":
                    for fb in layer.features:
                        _, p = layer.feature_props(fb)
                        if "shield_text" in p or "network" in p:
                            net = p.get("network", "-")
                            st = p.get("shield_text", "")
                            shields[(net, st)] += 1
                            lengths[net][len(st)] += 1
                            kinds_by_network[net][p.get("kind", "?")] += 1
                            if p.get("ref") and len(refs[net]) < 12:
                                refs[net].add(p["ref"])
                elif layer.name == "pois":
                    for fb in layer.features:
                        _, p = layer.feature_props(fb)
                        k = p.get("kind", "?")
                        pois[k] += 1
                        poi_minzoom[k][p.get("min_zoom")] += 1
    result = {
        "pack": Path(a.pack).name, "bbox": bbox, "zoom": a.zoom, "tiles": tiles,
        "shield_text_lengths_by_network": {n: dict(sorted(c.items())) for n, c in sorted(lengths.items())},
        "road_kinds_by_network": {n: dict(c) for n, c in sorted(kinds_by_network.items())},
        "example_refs_by_network": {n: sorted(v) for n, v in sorted(refs.items())},
        "shields_top": [[n, s, c] for (n, s), c in shields.most_common(60)],
        "poi_kinds": dict(pois.most_common()),
        "poi_min_zoom": {k: dict(sorted(v.items(), key=lambda kv: (kv[0] is None, kv[0]))) for k, v in poi_minzoom.items()},
    }
    text = json.dumps(result, indent=1, ensure_ascii=False)
    if a.out:
        Path(a.out).parent.mkdir(parents=True, exist_ok=True)
        Path(a.out).write_text(text, encoding="utf-8")
        print(f"{tiles} tiles -> {a.out}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
