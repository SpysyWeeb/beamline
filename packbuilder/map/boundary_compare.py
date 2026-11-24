"""How far OpenStreetMap's state line (as drawn in a pack) is from the Census TIGER/Line outline.

Usage (from the repo root):
    .venv/map/Scripts/python packbuilder/map/boundary_compare.py data/map/packs/pa-261114-z0-15.pmtiles --state pa
        [--zoom 14] [--box -80.53,39.70,-80.50,42.00]

Decodes the "boundaries" layer (kind_detail 4 = state lines) of every zoom-Z
tile in the box, samples the lines every few metres and measures the distance
of each sample to the TIGER/Line state outline (census_clip.py). Use a pack
whose clip did not cut the OSM line (the first round's packs were clipped with
Geofabrik's buffered .poly, so their state lines are complete). Prints the
median, 95th percentile and maximum distance in metres.
"""
import argparse
import gzip
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mvt  # noqa: E402
from census_clip import CONFIG, state_outline  # noqa: E402
from pmtiles.reader import MmapSource, Reader  # noqa: E402


def tile_range(lon0, lat0, lon1, lat1, z):
    def xy(lon, lat):
        n = 2 ** z
        return int((lon + 180) / 360 * n), int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)
    (x0, y1), (x1, y0) = xy(lon0, lat0), xy(lon1, lat1)
    return [(x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pack")
    ap.add_argument("--state", default="pa")
    ap.add_argument("--zoom", type=int, default=14)
    ap.add_argument("--box", default="-80.53,39.70,-80.50,42.00", help="lon0,lat0,lon1,lat1 to sample")
    a = ap.parse_args()
    from shapely.geometry import Point
    _, outline = state_outline(CONFIG["states"][a.state]["usps"])
    ring = outline.boundary
    lon0, lat0, lon1, lat1 = map(float, a.box.split(","))
    z = a.zoom
    dists = []
    with open(a.pack, "rb") as f:
        r = Reader(MmapSource(f))
        for x, y in tile_range(lon0, lat0, lon1, lat1, z):
            raw = r.get(z, x, y)
            if not raw:
                continue
            for l in mvt.parse_tile(gzip.decompress(raw)):
                if l.name != "boundaries":
                    continue
                for fb in l.features:
                    _, props = l.feature_props(fb)
                    if props.get("kind_detail") != 4:
                        continue
                    _, _, _, geom = l.feature_parts(fb)
                    for part in mvt.decode_geometry(geom):
                        for (px, py), (qx, qy) in zip(part, part[1:]):
                            for t in (0.0, 0.5):
                                ux, uy = px + t * (qx - px), py + t * (qy - py)
                                if not (0 <= ux < l.extent and 0 <= uy < l.extent):
                                    continue
                                n = 2 ** z
                                lon = (x + ux / l.extent) / n * 360 - 180
                                lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + uy / l.extent) / n))))
                                if not (lon0 <= lon <= lon1 and lat0 <= lat <= lat1):
                                    continue
                                d = ring.distance(Point(lon, lat))  # degrees; convert with the local scale
                                p = ring.interpolate(ring.project(Point(lon, lat)))
                                k = math.cos(math.radians(lat))
                                dists.append(math.hypot((p.x - lon) * 111320 * k, (p.y - lat) * 110574) if d else 0.0)
    if not dists:
        print("no state-line samples in the box")
        return 1
    dists.sort()
    q = lambda f: dists[min(len(dists) - 1, int(f * len(dists)))]  # noqa: E731
    print(f"{len(dists)} samples of the OSM state line (z{z} tiles) in {a.box}: distance to the TIGER/Line outline "
          f"median {q(0.5):.1f} m, 95th percentile {q(0.95):.1f} m, max {dists[-1]:.1f} m")
    return 0


if __name__ == "__main__":
    sys.exit(main())
