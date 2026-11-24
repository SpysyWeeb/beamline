"""Compute the sample routes used to show the route colours on real map views.

Usage (from the repo root; needs the routing track's environment and tiles,
used read-only: .venv/routing with pyvalhalla 3.9.1, data/routing/build/):
    .venv/routing/Scripts/python packbuilder/map/style/sample_routes.py

Writes data/art/route/routes.geojson: for each sample view a main route, an
alternative and the position of the arrow marker on the main route (with the
heading of the road there). The geometry is OpenStreetMap data (ODbL) routed
by Valhalla; it is only used for the art renders.
"""
import json
import math
import sys
from pathlib import Path

import valhalla

REPO = Path(__file__).resolve().parents[3]
OUT = REPO / "data" / "art" / "route" / "routes.geojson"

# start (lat, lon), start_heading (degrees, picks the carriageway), end, a via point that makes the
# alternative, and the fraction of the main route's length where the arrow sits
SAMPLES = {
    "erie-downtown": {
        "note": "Downtown Erie: north-west on Peach St, left onto W 7th St, left onto Sassafras St",
        "start": (42.12248, -80.08231), "start_heading": 326, "end": (42.12611, -80.08765),
        "alt_via": (42.12400, -80.08700),
        "arrow_at": 0.30,
    },
    "i90-exit24": {
        "note": "I-90 eastbound, exit 24 to Peach St / US 19 north, right onto Downs Dr",
        "start": (42.03990, -80.10400), "start_heading": 60, "end": (42.05190, -80.07880),
        "alt_via": (42.05530, -80.06900),
        "arrow_at": 0.55,
    },
}


def decode6(s):
    out, i, lat, lon = [], 0, 0, 0
    while i < len(s):
        for which in (0, 1):
            shift = result = 0
            while True:
                b = ord(s[i]) - 63
                i += 1
                result |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            d = ~(result >> 1) if result & 1 else result >> 1
            if which == 0:
                lat += d
            else:
                lon += d
        out.append([lon / 1e6, lat / 1e6])
    return out


def config():
    cfg = json.loads((REPO / "data" / "routing" / "build" / "valhalla.json").read_text(encoding="utf-8"))
    cfg["mjolnir"]["tile_extract"] = ""
    cfg["mjolnir"]["tile_dir"] = str(REPO / "data" / "routing" / "build" / "valhalla_tiles")
    cfg["mjolnir"]["traffic_extract"] = ""
    cfg["logging"]["type"] = ""
    return cfg


def route(actor, pts, heading=None):
    locs = [{"lat": a, "lon": b} for a, b in pts]
    if heading is not None:  # put the start on the carriageway going this way
        locs[0].update({"heading": heading, "heading_tolerance": 45})
    req = {"locations": locs, "costing": "auto",
           "units": "kilometers", "directions_options": {"units": "kilometers"}}
    resp = json.loads(actor.route(json.dumps(req)))
    coords = []
    for leg in resp["trip"]["legs"]:
        c = decode6(leg["shape"])
        coords.extend(c if not coords else c[1:])
    return coords, resp["trip"]["summary"]


def point_at(coords, frac):
    def dist(a, b):
        kx = math.cos(math.radians(a[1])) * 111320
        return math.hypot((b[0] - a[0]) * kx, (b[1] - a[1]) * 110540)
    seg = [dist(coords[i], coords[i + 1]) for i in range(len(coords) - 1)]
    target, run = sum(seg) * frac, 0.0
    for i, d in enumerate(seg):
        if run + d >= target and d > 0:
            t = (target - run) / d
            a, b = coords[i], coords[i + 1]
            p = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]
            kx = math.cos(math.radians(a[1]))
            heading = math.degrees(math.atan2((b[0] - a[0]) * kx, b[1] - a[1])) % 360
            return p, heading, i + 1
        run += d
    return coords[-1], 0.0, len(coords) - 1


def main() -> int:
    actor = valhalla.Actor(config())
    features = []
    for name, s in SAMPLES.items():
        h = s.get("start_heading")
        main_coords, summary = route(actor, [s["start"], s["end"]], h)
        alt_coords, alt_summary = route(actor, [s["start"], s["alt_via"], s["end"]], h)
        p, heading, idx = point_at(main_coords, s["arrow_at"])
        features += [
            {"type": "Feature", "properties": {"sample": name, "role": "main", "note": s["note"],
                                               "km": summary["length"], "s": summary["time"]},
             "geometry": {"type": "LineString", "coordinates": main_coords}},
            {"type": "Feature", "properties": {"sample": name, "role": "traveled"},
             "geometry": {"type": "LineString", "coordinates": main_coords[:idx] + [p]}},
            {"type": "Feature", "properties": {"sample": name, "role": "alternative",
                                               "km": alt_summary["length"], "s": alt_summary["time"]},
             "geometry": {"type": "LineString", "coordinates": alt_coords}},
            {"type": "Feature", "properties": {"sample": name, "role": "arrow", "heading": round(heading, 1)},
             "geometry": {"type": "Point", "coordinates": p}},
        ]
        print(f"{name}: main {summary['length']:.2f} km / {summary['time']:.0f} s, {len(main_coords)} points; "
              f"alternative {alt_summary['length']:.2f} km; arrow at {p[1]:.5f},{p[0]:.5f} heading {heading:.0f}")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"type": "FeatureCollection", "features": features}), encoding="utf-8")
    print(f"-> {OUT.relative_to(REPO).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
