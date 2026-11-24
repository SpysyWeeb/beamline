"""Convert an Osmosis .poly boundary file (as published by Geofabrik) to GeoJSON.

Usage:
    python packbuilder/map/poly2geojson.py INPUT.poly OUTPUT.geojson

The Protomaps profile's --clip option takes a GeoJSON Polygon/MultiPolygon.
Sections whose name starts with "!" are holes of the preceding outer ring.
"""
import json
import sys
from pathlib import Path


def parse_poly(text: str) -> dict:
    lines = [l.strip() for l in text.splitlines()]
    i = 1  # line 0 is the file name
    polygons = []  # list of [outer, hole, hole...]
    while i < len(lines):
        name = lines[i]
        i += 1
        if name == "END" or name == "":
            if name == "END":
                break
            continue
        ring = []
        while lines[i] != "END":
            parts = lines[i].split()
            ring.append([float(parts[0]), float(parts[1])])
            i += 1
        i += 1
        if ring[0] != ring[-1]:
            ring.append(ring[0])
        if name.startswith("!"):
            polygons[-1].append(ring)
        else:
            polygons.append([ring])
    return {"type": "MultiPolygon", "coordinates": polygons}


def main() -> int:
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    geom = parse_poly(src.read_text())
    fc = {"type": "FeatureCollection",
          "features": [{"type": "Feature", "properties": {"source": src.name}, "geometry": geom}]}
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(fc))
    n = sum(len(r) for p in geom["coordinates"] for r in p)
    print(f"{src.name}: {len(geom['coordinates'])} polygon(s), {n} points -> {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
