"""How well the map's truck-stop name rule agrees with the search track's truck_stop category.

Usage (from the repo root; reads the search track's PA index read-only):
    python packbuilder/map/style/check_truck_stops.py [--index data/search/pa-search.sqlite] [--out data/art/truck_stops.json]

The map cannot use brand or hgv tags (the tiles carry only kind and name), so
the style gives a 'fuel' or 'services' POI the truck_stop icon by its name
(assets/poi_icons.json "truck_stop_names"; generate_style.mjs turns the same
rule into a MapLibre expression). Search uses brand, name and HGV tags
(packbuilder/search/categories.json). This script applies the map rule to the
display name of every POI the search index files under fuel, service_plaza or
truck_stop and counts where the two agree. The display name is the OSM name,
or the brand or category label for unnamed POIs, so it is close to, not the
same as, the tile's name.
"""
import argparse
import json
import sqlite3
import sys
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]


def rule(cfg):
    starts = [s.lower() for s in cfg["starts_with"]]
    contains = [s.lower() for s in cfg["contains"]]

    def match(name: str) -> bool:
        n = (name or "").lower()
        return any(n == s or n.startswith(s + " ") for s in starts) or any(c in n for c in contains)
    return match


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--index", default="data/search/pa-search.sqlite")
    ap.add_argument("--out", default="data/art/truck_stops.json")
    a = ap.parse_args()
    cfg = json.loads((REPO / "assets" / "poi_icons.json").read_text(encoding="utf-8"))["truck_stop_names"]
    match = rule(cfg)
    con = sqlite3.connect(f"file:{(REPO / a.index).as_posix()}?mode=ro", uri=True)
    cats = {code: i for i, code in con.execute("select id, code from category")}
    rows = {}
    for code in ("fuel", "service_plaza", "truck_stop"):
        for eid, name in con.execute("select e.id, e.name from poi_cat pc join entry e on e.id = pc.entry_id "
                                     "where pc.cat_id = ?", (cats[code],)):
            rows.setdefault(eid, {"name": name, "cats": set()})["cats"].add(code)
    table = Counter()
    disagree = {"map truck, search not": Counter(), "search truck, map not": Counter()}
    for r in rows.values():
        s = "truck_stop" in r["cats"]
        m = match(r["name"])
        table[(s, m)] += 1
        if m and not s:
            disagree["map truck, search not"][r["name"]] += 1
        elif s and not m:
            disagree["search truck, map not"][r["name"]] += 1
    out = {
        "index": a.index, "pois": len(rows),
        "both_truck_stop": table[(True, True)], "neither": table[(False, False)],
        "search_only": table[(True, False)], "map_only": table[(False, True)],
        "disagreements": {k: dict(v.most_common()) for k, v in disagree.items()},
    }
    (REPO / a.out).parent.mkdir(parents=True, exist_ok=True)
    (REPO / a.out).write_text(json.dumps(out, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"{len(rows)} fuel/service/truck-stop POIs in {a.index}")
    print(f"truck stop in both: {out['both_truck_stop']}, search only: {out['search_only']}, "
          f"map only: {out['map_only']}, neither: {out['neither']}")
    for k, v in disagree.items():
        print(f"{k}: " + "; ".join(f"{n} x{c}" for n, c in v.most_common(20)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
