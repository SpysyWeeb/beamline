"""List roads that cross the Pennsylvania / Ohio state line, from the merged extract.

The PA/OH line is the meridian at about 80.5194 W between the Ohio River
(about 40.638 N) and Lake Erie (about 41.98 N). Any highway way with a segment
crossing that meridian inside the latitude range is a border crossing.
The output (data/routing/results/border_crossings.json) was used once to pick
the crossing test points in trips.json; it is kept so the choice can be redone
for a new snapshot.

usage: python find_crossings.py [merged.osm.pbf]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import osmium

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common as C  # noqa: E402

BORDER_LON = -80.5194
LAT_MIN, LAT_MAX = 40.63, 41.985
DRIVABLE = {"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
            "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"}


def find(pbf: Path) -> list[dict]:
    out = []
    fp = (osmium.FileProcessor(str(pbf), osmium.osm.NODE | osmium.osm.WAY)
          .with_locations("sparse_mem_array")
          .with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
          .with_filter(osmium.filter.KeyFilter("highway")))
    for w in fp:
        hw = w.tags.get("highway")
        if hw not in DRIVABLE:
            continue
        pts = [(n.lon, n.lat) for n in w.nodes if n.location.valid()]
        for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
            if (x1 - BORDER_LON) * (x2 - BORDER_LON) < 0:
                t = (BORDER_LON - x1) / (x2 - x1)
                y = y1 + t * (y2 - y1)
                if LAT_MIN <= y <= LAT_MAX:
                    out.append({"way": w.id, "highway": hw, "ref": w.tags.get("ref", ""),
                                "name": w.tags.get("name", ""), "oneway": w.tags.get("oneway", ""),
                                "lat": round(y, 6), "lon": BORDER_LON})
    out.sort(key=lambda r: -r["lat"])
    return out


def _dist_m(a, b):
    import math
    dy = (a[0] - b[0]) * 111320
    dx = (a[1] - b[1]) * 111320 * math.cos(math.radians(a[0]))
    return math.hypot(dx, dy)


def pick_points(pbf: Path, picks: list[dict], target_m: float = 2500) -> list[dict]:
    """For each pick {id, way, match_key, match_value}, find a node on the same road
    (ways sharing that tag value) about target_m from the crossing on each side."""
    crossings = {r["way"]: r for r in C.load_json(C.RESULTS / "border_crossings.json")}
    cands = {p["id"]: {"oh": None, "pa": None} for p in picks}
    fp = (osmium.FileProcessor(str(pbf), osmium.osm.NODE | osmium.osm.WAY)
          .with_locations("sparse_mem_array")
          .with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
          .with_filter(osmium.filter.KeyFilter("highway")))
    for w in fp:
        for p in picks:
            if w.id != p["way"] and w.tags.get(p["match_key"]) != p["match_value"]:
                continue
            cx = crossings[p["way"]]
            c = (cx["lat"], cx["lon"])
            for n in w.nodes:
                if not n.location.valid():
                    continue
                pt = (n.lat, n.lon)
                d = _dist_m(pt, c)
                if d > 2 * target_m:
                    continue
                side = "oh" if n.lon < BORDER_LON else "pa"
                score = abs(d - target_m)
                best = cands[p["id"]][side]
                if best is None or score < best[0]:
                    cands[p["id"]][side] = (score, round(n.lat, 6), round(n.lon, 6), round(d))
    out = []
    for p in picks:
        cx = crossings[p["way"]]
        row = {"id": p["id"], "road": p["road"], "crossing": {"lat": cx["lat"], "lon": cx["lon"]},
               "expect_names": p["expect_names"], "min_road_class": p.get("min_road_class", "service_other")}
        for side in ("oh", "pa"):
            b = cands[p["id"]][side]
            row[side] = {"lat": b[1], "lon": b[2], "m_from_line": b[3]} if b else None
        out.append(row)
    return out


# The crossings used by the tests (picked from border_crossings.json, snapshot 261114).
PICKS = [
    {"id": "I90", "road": "I-90", "way": 18962733, "match_key": "ref", "match_value": "I 90",
     "expect_names": ["I 90"], "min_road_class": "motorway"},
    {"id": "I80", "road": "I-80", "way": 19265527, "match_key": "ref", "match_value": "I 80",
     "expect_names": ["I 80"], "min_road_class": "motorway"},
    {"id": "I76", "road": "I-76 (Ohio / Pennsylvania Turnpike)", "way": 59967511, "match_key": "ref",
     "match_value": "I 76", "expect_names": ["I 76", "Pennsylvania Turnpike", "Ohio Turnpike"],
     "min_road_class": "motorway"},
    {"id": "US20", "road": "US-20", "way": 328492622, "match_key": "ref", "match_value": "US 20",
     "expect_names": ["US 20"]},
    {"id": "US6", "road": "US-6", "way": 149544054, "match_key": "ref", "match_value": "US 6",
     "expect_names": ["US 6"]},
    # small local road: the only named local road with the same name for 2.5 km on both sides
    {"id": "LOCAL", "road": "Columbiana Road (county road, tertiary)", "way": 818098139, "match_key": "name",
     "match_value": "Columbiana Road", "expect_names": ["Columbiana Road"]},
]


if __name__ == "__main__":
    pbf = Path(sys.argv[1]) if len(sys.argv) > 1 else C.MERGED_PBF
    if "--points" in sys.argv:
        pbf = Path([a for a in sys.argv[1:] if a != "--points"][0]) if len(sys.argv) > 2 else C.MERGED_PBF
        pts = pick_points(pbf, PICKS)
        C.save_json(C.RESULTS / "border_points.json", pts)
        print(json.dumps(pts, indent=1))
        sys.exit(0)
    rows = find(pbf)
    C.save_json(C.RESULTS / "border_crossings.json", rows)
    for r in rows:
        print(f"{r['lat']:.5f} {r['highway']:<14} {r['ref']:<14} {r['name']:<40} way {r['way']}")
    print(f"{len(rows)} crossing segments")
