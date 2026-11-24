"""Route with pyvalhalla 3.9.1 on tile sets written by the Kotlin pack manager (:routing:packs).

Checks that the engine reads them: the 21 trips on <work>/pa-oh/routing.tar must give the same
time, length and maneuvers as reference/trips.json (recorded on packs.py's device), and the
PA-only edge probes of trips.json on <work>/pa-only/routing.tar must give route / 170 / 171 as
docs/experiments/01-routing.md found.

    .venv/routing/Scripts/python android/routing/scripts/check_kotlin_tileset.py [--work data/routing-packs-test]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "packbuilder" / "routing"))
import valhalla  # noqa: E402

import route_lib as R  # noqa: E402

TESTDATA = REPO / "android" / "routing" / "testdata"


def actor(tar: Path) -> valhalla.Actor:
    base = json.loads((REPO / "android/routing/core/src/main/resources/io/github/spysyweeb/beamline/routing/valhalla_base.json")
                      .read_text(encoding="utf-8"))
    base["mjolnir"]["tile_extract"] = str(tar.resolve())
    return valhalla.Actor(base)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", type=Path, default=REPO / "data" / "routing-packs-test")
    a = ap.parse_args()
    trips = R.load_trips()
    P = trips["places"]
    ref = json.loads((TESTDATA / "reference" / "trips.json").read_text(encoding="utf-8"))["presets"]["fastest"]
    act = actor(a.work / "pa-oh" / "routing.tar")
    bad = 0
    for t in trips["trips"]:
        resp = R.route(act, R.request([R.loc(P[t["from"]]), R.loc(P[t["to"]])]))
        s = R.summarize(resp)
        w = ref[t["id"]]
        same = s["ok"] and (s["time_s"], s["length_km"], s["maneuvers"]) == (w["time_s"], w["length_km"], w["maneuvers"])
        bad += not same
        print(f"{t['id']:3} {'same' if same else 'DIFFERS'} {s.get('time_s')} s {s.get('length_km')} km {s.get('maneuvers')} m")
    act = actor(a.work / "pa-only" / "routing.tar")
    probes = trips["pa_only_edge_probes"]
    for p in probes["points"]:
        resp = R.route(act, R.request([R.loc(P[probes["from"]]), {"lat": p["lat"], "lon": p["lon"]}]))
        got = "route" if "error" not in resp else str(resp["error"]).split()[0]
        bad += got != p["expect"]
        print(f"PA only, Sharon -> {p['lat']},{p['lon']}: {got} (expected {p['expect']})")
    print("all as expected" if not bad else f"{bad} differences")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
