"""Experiment: what happens when valhalla_build_tiles gets the two overlapping state
extracts directly instead of one merged file (build.py tiles-unmerged).

Compares, at every border crossing in trips.json, the edges loki finds at the
crossing point (way id, direction) in the merged and the unmerged graph, plus
whole-graph counts from the two build logs.

usage: python compare_unmerged.py
"""
from __future__ import annotations

import json
import re
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common as C  # noqa: E402
import route_eval as E  # noqa: E402
import route_lib as R  # noqa: E402

UNMERGED = C.DATA / "exp_unmerged"


def edges_at(actor, lat, lon):
    r = actor.locate({"locations": [{"lat": lat, "lon": lon}], "costing": "auto", "verbose": True})
    out = []
    for e in r[0].get("edges") or []:
        info = e["edge_info"]
        out.append((info.get("way_id"), e["edge"].get("forward"), tuple(info.get("names", []))))
    return out


def log_counts(log: Path) -> dict:
    txt = log.read_text(errors="replace")
    pats = {"routable_ways": r"Finished with (\d+) routable ways", "graph_edges": r"Finished with (\d+) graph edges",
            "graph_nodes": r"Finished with (\d+) graph nodes", "turn_restrictions": r"Finished with (\d+) simple turn",
            "dup_level0": r"duplicates at level: 0 = (\d+)", "dup_level1": r"duplicates at level: 1 = (\d+)",
            "dup_level2": r"duplicates at level: 2 = (\d+)"}
    return {k: int(m.group(1)) if (m := re.search(p, txt)) else None for k, p in pats.items()}


def main():
    merged = R.actor(E.combined_config())
    ucfg = E.combined_config(**{"mjolnir.tile_dir": str(UNMERGED / "valhalla_tiles")})
    unmerged = R.actor(ucfg)
    rows = []
    for c in R.load_trips()["border_crossings"]:
        lat, lon = c["crossing_lat"], -80.5194
        m, u = edges_at(merged, lat, lon), edges_at(unmerged, lat, lon)
        rows.append({"crossing": c["id"], "merged_edges": len(m), "unmerged_edges": len(u),
                     "unmerged_same_way_and_direction_repeated": sum(v - 1 for v in Counter(u).values() if v > 1),
                     "merged_same_way_and_direction_repeated": sum(v - 1 for v in Counter(m).values() if v > 1)})
    res = {"crossing_points": rows,
           "merged_build": log_counts(C.LOGS / "build_tiles.log"),
           "unmerged_build": log_counts(C.LOGS / "build_tiles_unmerged.log"),
           "unmerged_crossings": E.run_crossings(unmerged)}
    C.save_json(C.RESULTS / "unmerged_comparison.json", res)
    print(json.dumps({k: v for k, v in res.items() if k != "unmerged_crossings"}, indent=1))
    for r in res["unmerged_crossings"]:
        print(r["id"], r["direction"], r.get("ok"), r.get("length_km"), r.get("error"))


if __name__ == "__main__":
    main()
