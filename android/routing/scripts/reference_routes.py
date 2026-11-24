"""Reference results from pyvalhalla 3.9.1 for the Kotlin routing modules (android/routing/).

Runs the Python reference (packbuilder/routing/route_lib.py) on a device folder written by
packbuilder/routing/packs.py (PA+OH tar, device valhalla.json) and records, as test fixtures:

  reference/trips.json              the old 126 plans: 21 trips x the 6 presets of format 1
                                    (reference/presets-format1.json, kept as it was): summary of the
                                    route route_lib.run_preset picks, the alternates of every preset
                                    (alternates_for), four trips that start on a motorway (Avoid
                                    highways' fallback), the 1x40 matrix targets. No timings: they go
                                    only to the file --timings names (none without it)
  reference/recordings.json.gz      every engine request the app makes for those trips, with the
                                    engine's response (the "recorded real responses" of the JVM tests)
  reference/plans.json              presets format 2 (packbuilder/routing/presets.json, compose()):
                                    21 trips x {fastest, simpler, eco} x {none, highways, tolls,
                                    both} (Shortest left in batch D), and the cases of
                                    docs/architecture.md 2.11 (Carlisle to King of Prussia, the two end
                                    cases, a start on the Turnpike, the C1 routing fix, batch D's
                                    Erie-region Simpler trips)
  reference/recordings-plans.json.gz  the requests of plans.json that recordings.json.gz does not hold
  reference/banner-routes.json.gz   the banner trips of banner_cases.json (Valhalla JSON with lanes)
  reference/exit24-osrm.json        the owner's exit 24 trip in OSRM format with banner and voice
                                    instructions, as Ferrostar's route adapter asks for it
  reference/reorder.json            an 8-stop matrix, the best order by brute force, Valhalla's
                                    optimized_route order
  reference/errors.json             requests that fail (stop outside the installed packs) and a stop
                                    snapped far away, with the requests the planner asks for the two that succeed

The app's request is route_lib.request() plus "turn_lanes": true (lanes do not change the route).

Usage, from the repo root, with the routing venv (Python 3.12, pyvalhalla 3.9.1), on the device
folder that packbuilder/routing/packs.py wrote (report 01; in a worktree without data/, point
--device at the checkout that has it):
    .venv/routing/Scripts/python android/routing/scripts/reference_routes.py --device data/routing/device \
        [--timings data/r3-n/reference-timings.json]
"""
from __future__ import annotations

import argparse
import gzip
import itertools
import json
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "packbuilder" / "routing"))
import valhalla  # noqa: E402

import route_lib as R  # noqa: E402

OUT = REPO / "android" / "routing" / "testdata"
APP_EXTRA = {"turn_lanes": True}
# The format-1 presets the old 126 plans were recorded with (a copy of presets.json before format 2).
FORMAT1_FILE = OUT / "reference" / "presets-format1.json"
# Option sets of the format-2 plans, in this order: key -> option ids.
OPTION_SETS = {"none": [], "highways": ["avoid_highways"], "tolls": ["avoid_tolls"], "both": ["avoid_highways", "avoid_tolls"]}
# docs/architecture.md 2.11: the points the review's pyvalhalla runs used (not the trips.json ones).
CARLISLE = {"lat": 40.2, "lon": -77.19}
KING_OF_PRUSSIA = {"lat": 40.09, "lon": -75.38}
# A stop on a motorway on purpose (the option fallback cases of 2.11): the engine's default
# search_filter, so presets.json every_request does not tie it to a street nearby (batch C1).
ON_MOTORWAY = {"search_filter": {"min_road_class": "service_other", "max_road_class": "motorway"}}
TURNPIKE_STOP = {"lat": 40.149533, "lon": -75.844894, **ON_MOTORWAY}   # on the Turnpike (a stop there, Avoid tolls)
TURNPIKE_START = {"lat": 40.229690, "lon": -76.350721}  # on the Turnpike (a trip that starts there)
ERIE = {"lat": 42.1292, "lon": -80.0851}
I90_STOP = {"lat": 42.032716, "lon": -80.125726, **ON_MOTORWAY}        # on I-90 (a stop there, Avoid highways)
# The C1 routing fix (docs/experiments/23-batch-c.md): a place tied to its address street (the
# owner's case: Family Dollar, 155 East 12th Street, Erie, nearer to Holland Street), a place whose
# address street is out of reach (Ulta Beauty inside Millcreek Mall, Interchange Road more than 300 m
# away: the nearest street, then the tie check), and a dropped pin at the same point (not tied).
FAMILY_DOLLAR = {"lat": 42.1243521, "lon": -80.0777055}
ULTA_BEAUTY = {"lat": 42.0685317, "lon": -80.1040745}
# Batch D (docs/next-round.md item 13): downtown Butler, which Simpler must keep reaching by I-79 exit 99
# and US 422, and Center School, Center Township (search pack), a public place near the owner's case
# (a road north of Butler), where Fastest leaves I-79 at exit 113 for PA 258.
BUTLER = {"lat": 40.8612, "lon": -79.8953}
CENTER_SCHOOL = {"lat": 40.9208972, "lon": -79.913666}
# Batch D, the arrival side without loops (docs/next-round.md item 16; places of the C1 review's sample):
# a place on a residential street (no side request: the plain route), a shop on a busy secondary
# street (the side route, one block, +10 s, +1 instruction) and a place on a primary street the side
# route reached only by a longer loop (+44 s, +4 instructions: not kept).
X24 = {"lat": 42.0726, "lon": -80.0623}   # the C1 review's south-east origin (data/review/batchC1-routing/p4.py "x24")
MORNING_STAR = {"lat": 42.1273475, "lon": -80.0490850, "_street": "East 20th Street"}
AMBIENCE = {"lat": 42.1273552, "lon": -80.0735438, "_street": "Parade Street"}
SMOKE_CAVE = {"lat": 42.1250197, "lon": -80.082664, "_street": "State Street"}
# Batch E (docs/next-round.md item 18): a point of interest ends in its own lot (`_poi`: a business of the
# search pack). A restaurant on Peach Street, Millcreek, whose lot lies behind it (Eat'n Park, 7355 Peach
# Street: a driveway 15 m away, Mandy Lane 41 m); a store in a strip plaza (Shoe Fly, 7520 Peach Street: a
# parking aisle 22 m away, Scott Drive 45 m); Family Dollar (the C1 alley case) as a point of interest: its
# own lot from East 12th Street, never the alley; a downtown business with a driveway 20 m away but its
# street 12 m away (Domino's, 1402 Turnpike Street): no lot, its street as before; Five Below in the
# Millcreek Mall plaza, whose lot route is more than 90 s longer than its street end: the tie check keeps
# the street end (Interchange Road).
EATN_PARK = {"lat": 42.0537626, "lon": -80.0853645, "_street": "Peach Street", "_poi": True}
SHOE_FLY = {"lat": 42.0517956, "lon": -80.0848752, "_street": "Peach Street", "_poi": True}
DOMINOS_TURNPIKE = {"lat": 42.1213778, "lon": -80.0809081, "_street": "Turnpike Street", "_poi": True}
FIVE_BELOW = {"lat": 42.0680696, "lon": -80.1040303, "_street": "Interchange Road", "_poi": True}
# Batch E2 (docs/next-round.md item 19): one end point for every route type. A doughnut shop on Peach Street
# (0.1.6's Simpler ended on Peach Street from the west while Fastest ended in the lot), the C1 review's
# origins, a car wash whose lot is reached only through its wash lane, a drive-through restaurant.
KRISPY_KREME = {"lat": 42.0525432, "lon": -80.0840009, "_street": "Peach Street", "_poi": True}
WEST_26 = {"lat": 42.1050, "lon": -80.1080}
SOUTH = {"lat": 42.0010, "lon": -80.0700}
PERRY = {"lat": 42.127, "lon": -80.0852}
CAR_WASH_W12 = {"lat": 42.1120289, "lon": -80.1136557, "_street": "West 12th Street", "_poi": True}
MCDONALDS_PEACH = {"lat": 42.0893217, "lon": -80.0854826, "_street": "Peach Street", "_poi": True}
# Front doors (docs/next-round.md item 28): a large store's centroid is the middle of its roof, and the
# lane nearest to it is as often the service road behind the building. `_door` is where customers go in
# (the search pack's poi_door table). Two Walmart Supercenters near Erie: on Downs Drive the front is
# known from the parking aisles (0.1.13 ended on the lane behind the building, 112 m from the door); on
# Elm Street a main entrance is mapped (0.1.13: 121 m from it).
WALMART_DOWNS = {"lat": 42.0520658, "lon": -80.0798629, "_street": "Downs Drive", "_poi": True,
                 "_door": {"lat": 42.0518263, "lon": -80.0804005}}
# Item 35: from the north-west the engine goes into this store's lot by a way that the other one beats by the clock.
ELM_NW = {"lat": 42.14274, "lon": -80.067754}
WALMART_ELM = {"lat": 42.122977, "lon": -80.0411089, "_street": "Elm Street", "_poi": True,
               "_door": {"lat": 42.1225111, "lon": -80.0416271}}
# The same on Buffalo Road from the south-east: the way to the lane by the door is 97 s longer than the route
# to Buffalo Road, which ends 224 m from the door; the walk saved allows it (tie_check.door_walk_m_per_s).
WALMART_BUFFALO = {"lat": 42.1594394, "lon": -79.9680847, "_street": "Buffalo Road", "_poi": True,
                   "_door": {"lat": 42.1590627, "lon": -79.9685117}}
BUFFALO_SE = {"lat": 42.139676, "lon": -79.941422}
BOTH = [("fastest", "none"), ("simpler", "none")]
# id -> (stops, [(type, option set key)], what 2.11 says)
PLAN_CASES = {
    "carlisle_kop": ([CARLISLE, KING_OF_PRUSSIA], [("fastest", "none"), ("fastest", "tolls")],
                     "Avoid tolls has no tolls (exclude_tolls; review F2, found with Shortest, which left in batch D)"),
    "carlisle_turnpike": ([CARLISLE, TURNPIKE_STOP], [("fastest", "none"), ("fastest", "tolls")],
                          "a destination on a toll road with Avoid tolls: the fallback route with its warning, not No route "
                          "(fastest 141.5 km; without the option 126.3 km)"),
    "erie_i90": ([ERIE, I90_STOP], [("fastest", "none"), ("fastest", "highways")],
                 "a destination on I-90 with Avoid highways: the fallback route with its warning, not No route"),
    "turnpike_start_kop": ([TURNPIKE_START, KING_OF_PRUSSIA], [("fastest", "none"), ("fastest", "tolls")],
                           "a trip that starts on a toll road may stay on it (93.9 km with tolls either way; owner accepted)"),
    "address_street": ([ERIE, {**FAMILY_DOLLAR, "_street": "East 12th Street"}], [("fastest", "none"), ("simpler", "none")],
                       "Family Dollar, 155 East 12th Street: tied to East 12th Street (49 m), not Holland Street (37 m)"),
    "address_out_of_reach": ([ERIE, {**ULTA_BEAUTY, "_street": "Interchange Road"}], [("fastest", "none")],
                             "Ulta Beauty in Millcreek Mall: Interchange Road is not within 300 m, so the nearest street; "
                             "that tie adds a U-turn on Millcreek Mall Boulevard, so the tie check takes the untied route"),
    "dropped_pin": ([ERIE, {**FAMILY_DOLLAR, "_tie": "none"}], [("fastest", "none")],
                    "a dropped pin is not tied: the route goes to the road at the pin (alley_factor still applies)"),
    "erie_butler": ([ERIE, BUTLER], [("fastest", "none"), ("simpler", "none")],
                    "downtown Erie to downtown Butler: both keep I-79 exit 99 and US 422 (batch D)"),
    "erie_center_township": ([ERIE, CENTER_SCHOOL], [("fastest", "none"), ("simpler", "none")],
                             "downtown Erie to Center School, Center Township, near the owner's case: Fastest takes exit 113 "
                             "and PA 258; Simpler's fourth candidate (use_highways 1.0, maneuver_penalty 60) brings exit 99 "
                             "and US 422 into the pool (batch D)"),
    "side_residential": ([X24, MORNING_STAR], [("fastest", "none")],
                         "Morning Star Baptist Church, East 20th Street (residential): no side, the plain route "
                         "arrives on the left (780 s)"),
    "side_one_block": ([ERIE, AMBIENCE], [("fastest", "none")],
                       "a shop on Parade Street (secondary): the side route, one block more (167 s, +10 s, +1 instruction)"),
    "side_long_loop": ([X24, SMOKE_CAVE], [("fastest", "none")],
                       "a shop on State Street (primary): the side route is a longer loop (+44 s, +4 instructions), "
                       "so the plain route arrives on the left (677 s)"),
    "lot_restaurant": ([ERIE, EATN_PARK], [("fastest", "none"), ("simpler", "none")],
                       "a restaurant on Peach Street: the route ends in its lot beside the building (driveway 15 m), "
                       "entered from Mandy Lane behind it, not on Peach Street (batch E)"),
    "lot_plaza": ([X24, SHOE_FLY], [("fastest", "none")],
                  "a store in a strip plaza: Downs Drive, Scott Drive, into the plaza lot, 22 m from the store (batch E)"),
    "lot_alley_case": ([ERIE, {**FAMILY_DOLLAR, "_street": "East 12th Street", "_poi": True}], BOTH,
                       "Family Dollar as a point of interest: East 12th Street, then its own lot by a driveway; "
                       "never the alley (batch E)"),
    "lot_none_downtown": ([X24, DOMINOS_TURNPIKE], BOTH,
                          "a downtown business whose street is nearer than the driveway behind it: no lot, its "
                          "address street as before (batch E)"),
    "lot_back_to_street": ([ERIE, FIVE_BELOW], [("fastest", "none")],
                           "Five Below in the Millcreek Mall plaza: the lot route is more than 90 s longer than the "
                           "street end, so the tie check keeps Interchange Road (batch E)"),
    "door_parking": ([WEST_26, WALMART_DOWNS], BOTH,
                     "a large store whose front is known from its parking: the route ends at the lane by its door, "
                     "not on the lane behind the building (item 28)"),
    "door_entrance": ([ERIE, WALMART_ELM], BOTH,
                      "a large store with a mapped main entrance: the route ends at the lane by that entrance (item 28)"),
    "entrance_other": ([ELM_NW, WALMART_ELM], BOTH,
                       "two ways into one lot: the engine's own choice is 15 s slower by the clock than the other way in "
                       "and costs it hardly less, so the plan leaves that way in out (item 35)"),
    "door_walk": ([BUFFALO_SE, WALMART_BUFFALO], BOTH,
                  "a large store whose lot route is 97 s longer than its street end, 224 m from the door: the walk "
                  "saved allows it, the route ends by the door (item 28)"),
    "door_none": ([WEST_26, {k: v for k, v in WALMART_DOWNS.items() if k != "_door"}], [("fastest", "none")],
                  "the same store without its door (a pack built before 2026-11-21): as 0.1.13, the lane nearest to "
                  "the middle of the roof (item 28)"),
    "end_same_west": ([WEST_26, KRISPY_KREME], BOTH,
                      "a doughnut shop on Peach Street from the west: 0.1.6's Simpler ended on Peach Street (its own way "
                      "into the lot was more than 90 s slower than its own street end); now the end is decided once with "
                      "Fastest and Simpler ends at the same lot point (batch E2)"),
    "end_same_north": ([ERIE, KRISPY_KREME], BOTH, "the same shop from the north: both types end at the same lot point (batch E2)"),
    "end_same_south": ([SOUTH, KRISPY_KREME], BOTH, "the same shop from the south: both types end at the same lot point (batch E2)"),
    "end_same_avoid": ([WEST_26, KRISPY_KREME], [("simpler", "tolls"), ("fastest", "tolls")],
                       "with an option the end is decided with Fastest and that option (batch E2)"),
    "car_wash_street": ([PERRY, CAR_WASH_W12], BOTH,
                        "a car wash on West 12th Street: the way into its lot is its wash lane (drive_through), so the lot "
                        "fails and the route ends on West 12th Street at the entrance (batch E2)"),
    "drive_through_lot": ([X24, MCDONALDS_PEACH], BOTH,
                          "a drive-through restaurant on Peach Street with an ordinary entrance: ends in its lot, its "
                          "drive-through lanes left out of the route (exclude_polygons; batch E2)"),
}


class Recorder:
    """Wraps a pyvalhalla Actor: adds the app's request fields and records request + response."""

    def __init__(self, actor, log: list):
        self.a, self.log = actor, log

    def route(self, s: str) -> str:
        req = json.loads(s)
        req.update(APP_EXTRA)
        try:
            resp = self.a.route(json.dumps(req))
        except valhalla.ValhallaError as e:
            self.log.append({"request": req, "error": {"code": e.code, "message": e.message}})
            raise
        self.log.append({"request": req, "response": json.loads(resp)})
        return resp

    def trace_attributes(self, s: str) -> str:
        """The address street's probe (route_lib.stop_ends); recorded as asked (no app fields)."""
        req = json.loads(s)
        try:
            resp = self.a.trace_attributes(s)
        except valhalla.ValhallaError as e:
            self.log.append({"request": req, "error": {"code": e.code, "message": e.message}})
            raise
        self.log.append({"request": req, "response": json.loads(resp)})
        return resp


def engine(device: Path) -> valhalla.Actor:
    cfg = json.loads((device / "valhalla.json").read_text(encoding="utf-8"))
    cfg["mjolnir"]["tile_extract"] = str((device / cfg["mjolnir"]["tile_extract"]).resolve())
    return valhalla.Actor(cfg)


def summary(resp: dict) -> dict:
    s = R.summarize(resp)
    if not s["ok"]:
        return {"ok": False, "error": s["error"]}
    out = {k: s[k] for k in ("time_s", "length_km", "legs", "maneuvers", "maneuvers_total", "highway_km",
                             "toll_km", "endpoint_gap_m")}
    out["ok"] = True
    for k in ("used_fallback", "candidates_considered"):
        if k in resp:
            out[k] = resp[k]
    return out


def plan_summary(resp: dict) -> dict:
    """summary() plus what format 2 adds: the route's toll and highway flags, why the fallback ran
    ("no_route" or the fallback_when reason) and the warning a no-route fallback carries."""
    out = summary(resp)
    if not out["ok"]:
        return out
    s = resp["trip"]["summary"]
    out["has_toll"] = s.get("has_toll", False)
    out["has_highway"] = s.get("has_highway", False)
    for k in ("fallback_reason", "fallback_warning", "tie_check", "arrival_side"):
        if k in resp:
            out[k] = resp[k]
    if resp.get("_index", 0):
        out["route_index"] = resp["_index"]  # an alternate of its request (a pool's pick; batch D)
    # batch E2: the end the plan decided for the last stop and where the route ends (every route type the same)
    ends = resp.get("_ends") or []
    if len(ends) > 1 and ends[-1].get(R.END_KEY):
        out["end"] = ends[-1][R.END_KEY]
    # item 35: whether the plan leaves the engine's first way into the last stop's lot out
    if len(ends) > 1 and ends[-1].get(R.END_KEY) == "lot":
        out["entry_closed"] = bool(ends[-1].get(R.ENTRY_KEY))
    shape = R.decode_polyline6(resp["trip"]["legs"][-1]["shape"])
    if shape:
        out["end_point"] = [round(shape[-1][0], 6), round(shape[-1][1], 6)]
    return out


def write_json(path: Path, obj, gz: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps(obj, ensure_ascii=False, separators=(",", ":") if gz else (", ", ": "),
                      indent=None if gz else 1).encode("utf-8")
    if gz:
        # mtime=0: the same input gives the same bytes
        with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as f:
            f.write(data)
    else:
        path.write_bytes(data + b"\n")
    print(f"wrote {path.relative_to(REPO).as_posix()} ({path.stat().st_size:,} bytes)")


def same_route(a: dict, b: dict) -> bool:
    """RoutePlanner.sameRoute: length within 1 m, time within 0.5 s, the same number of shape points."""
    ta, tb = a["trip"], b["trip"]
    shape_a = [pt for leg in ta["legs"] for pt in R.decode_polyline6(leg["shape"])]
    shape_b = [pt for leg in tb["legs"] for pt in R.decode_polyline6(leg["shape"])]
    return (abs(ta["summary"]["length"] - tb["summary"]["length"]) * 1000 < 1.0
            and abs(ta["summary"]["time"] - tb["summary"]["time"]) < 0.5 and len(shape_a) == len(shape_b))


def alternates_for(a, locs: list[dict], preset: dict, chosen: dict, n_alt: int) -> list[dict]:
    """The alternates rule of RoutePlanner.alternatesFor (route_lib has no alternates rule).

    One request with "alternates": n_alt, with the options that produced the chosen route: the
    pool's first candidate for a pool preset, the fallback options when run_preset used them,
    else the preset's own. The chosen route is dropped from the answer, and so is any route that
    keeps more of a fallback_when road than both the limit and the chosen route (1 m tolerance)."""
    if "select" in preset:
        # format 2: a pool whose fallback produced the route asks with the fallback pool's first request
        use_fb = chosen.get("used_fallback") and "fallback_candidates" in preset
        opts = preset["fallback_candidates" if use_fb else "candidates"][0]
    elif chosen.get("used_fallback"):
        opts = preset["fallback_costing_options"]
    else:
        opts = preset.get("costing_options", {})
    locs = chosen.get("_ends") or locs  # batch E2: the ends the plan decided (RoutePlanner.alternatesFor gets them)
    resp = R.route(a, R.request(locs, {"costing": preset.get("costing", "auto"), "costing_options": opts},
                                alternates=n_alt))
    if "error" in resp:
        return []
    routes = [{"trip": resp["trip"]}] + [{"trip": x["trip"]} for x in resp.get("alternates", [])]
    routes = [r for r in routes if not same_route(r, chosen)]
    for cond, limit in preset.get("fallback_when", {}).items():
        flag = cond.split("_km_over")[0]
        routes = [r for r in routes if R.flagged_km(r, flag) <= max(limit, R.flagged_km(chosen, flag)) + 0.001]
    return routes[:n_alt]


def motorway_origin_trips() -> dict[str, list[dict]]:
    """Trips that start ON a motorway, where Avoid highways' fallback_when rule fires: the three of
    packbuilder/routing/test_routing.py (step 4) and the owner's exit 24 trip."""
    trips = R.load_trips()
    P = trips["places"]
    bc = {c["id"]: c for c in trips["border_crossings"]}
    exit24 = json.loads((OUT / "banner_cases.json").read_text(encoding="utf-8"))["cases"][0]
    assert exit24["id"] == "exit24"
    return {"exit24": [exit24["start"], exit24["end"]],
            "I90_pa_to_erie": [R.loc(bc["I90"]["pa"]), R.loc(P["erie_perry_sq"])],
            "I90_pa_to_I90_oh": [R.loc(bc["I90"]["pa"]), R.loc(bc["I90"]["oh"], **ON_MOTORWAY)],  # on I-90 on purpose
            "I80_pa_to_sharon": [R.loc(bc["I80"]["pa"]), R.loc(P["sharon"])]}


def run_trips(actor, log: list, timings: dict) -> dict:
    """The old 126 plans, with the format-1 presets they were recorded with (FORMAT1_FILE)."""
    trips = R.load_trips()
    P = trips["places"]
    presets = R.load_presets_format1(FORMAT1_FILE)
    rec = Recorder(actor, log)
    n_alt = json.loads(FORMAT1_FILE.read_text(encoding="utf-8"))["requires_config"]["service_limits.max_alternates"]
    out = {"presets": {}, "alternates": {}, "motorway_origin": {}}
    chosen: dict[tuple[str, str], dict] = {}
    for pid, preset in presets.items():
        rows = {}
        for t in trips["trips"]:
            locs = [R.loc(P[t["from"]]), R.loc(P[t["to"]])]
            t0 = time.perf_counter()
            resp = R.run_preset(rec, locs, preset)
            timings.setdefault("python_ms", {}).setdefault(pid, {})[t["id"]] = round((time.perf_counter() - t0) * 1000, 1)
            rows[t["id"]] = summary(resp)
            chosen[(pid, t["id"])] = resp
        out["presets"][pid] = rows
    # alternates of the two-stop trips, every preset (RoutePlanner with withAlternates = true)
    for pid, preset in presets.items():
        out["alternates"][pid] = {}
        for t in trips["trips"]:
            locs = [R.loc(P[t["from"]]), R.loc(P[t["to"]])]
            alts = alternates_for(rec, locs, preset, chosen[(pid, t["id"])], n_alt)
            out["alternates"][pid][t["id"]] = {"alternates": [summary(x) for x in alts]}
    # trips that start on a motorway: Fastest and Avoid highways (fallback_when fires), with alternates
    for name, locs in motorway_origin_trips().items():
        row = {"stops": locs}
        for pid in ("fastest", "avoid_highways"):
            resp = R.run_preset(rec, locs, presets[pid])
            alts = alternates_for(rec, locs, presets[pid], resp, n_alt) if "error" not in resp else []
            row[pid] = dict(summary(resp), alternates=[summary(x) for x in alts])
        out["motorway_origin"][name] = row
    return out


def run_plans(actor, log: list, timings: dict) -> dict:
    """Presets format 2: every route type with every option set on the 21 trips, and PLAN_CASES."""
    trips = R.load_trips()
    P = trips["places"]
    pf = R.load_preset_file()
    rec = Recorder(actor, log)
    out: dict = {"presets_format": pf["format"], "option_sets": OPTION_SETS, "plans": {}, "cases": {}}
    for t in pf["route_types"]:
        out["plans"][t["id"]] = {}
        for key, opts in OPTION_SETS.items():
            preset = R.compose(t["id"], opts, pf)
            rows = {}
            for trip in trips["trips"]:
                locs = [R.loc(P[trip["from"]]), R.loc(P[trip["to"]])]
                t0 = time.perf_counter()
                rows[trip["id"]] = plan_summary(R.run_preset(rec, locs, preset))
                timings.setdefault("python_ms_plans", {}).setdefault(preset["id"], {})[trip["id"]] = \
                    round((time.perf_counter() - t0) * 1000, 1)
            out["plans"][t["id"]][key] = rows
    for cid, (stops, runs, note) in PLAN_CASES.items():
        row: dict = {"stops": stops, "note": note}
        for tid, key in runs:
            row[f"{tid}/{key}"] = plan_summary(R.run_preset(rec, stops, R.compose(tid, OPTION_SETS[key], pf)))
        out["cases"][cid] = row
    return out


def matrix_targets(actor) -> dict:
    trips = R.load_trips()
    P = trips["places"]
    mt = trips["matrix_trip"]
    t = {x["id"]: x for x in trips["trips"]}[mt["id"]]
    base = R.summarize(R.route(actor, R.request([R.loc(P[t["from"]]), R.loc(P[t["to"]])])))
    sys.path.insert(0, str(REPO / "packbuilder" / "routing"))
    import route_eval  # noqa: E402  (corridor_targets only)

    targets = route_eval.corridor_targets(base["shape"], mt["targets"], mt["corridor_offset_m"])
    src = [R.loc(P[t["from"]])]
    req = {"sources": src, "targets": targets, "costing": "auto", "units": "kilometers"}
    resp = json.loads(actor.matrix(json.dumps(req)))
    cells = [c for row in resp["sources_to_targets"] for c in row]
    return {"trip": mt["id"], "request": req,
            "times_s": [c.get("time") for c in cells], "distances_km": [c.get("distance") for c in cells],
            "nulls": sum(1 for c in cells if c.get("time") is None)}


def run_reorder(actor) -> dict:
    P = R.load_trips()["places"]
    names = ["erie_perry_sq", "youngstown", "meadville", "pgh_market_sq", "sharon", "franklin", "hubbard",
             "ridgway"]
    locs = [R.loc(P[n]) for n in names]
    req = {"sources": locs, "targets": locs, "costing": "auto", "units": "kilometers"}
    resp = json.loads(actor.matrix(json.dumps(req)))
    T = [[c["time"] for c in row] for row in resp["sources_to_targets"]]

    def cost(order):
        return sum(T[a][b] for a, b in zip(order, order[1:]))

    best = {}
    n = len(names)
    # first stop fixed; last stop fixed, or free
    fixed = min(([0] + list(p) + [n - 1] for p in itertools.permutations(range(1, n - 1))), key=cost)
    free = min(([0] + list(p) for p in itertools.permutations(range(1, n))), key=cost)
    best["keep_last"] = {"order": fixed, "time_s": round(cost(fixed), 3)}
    best["free_last"] = {"order": free, "time_s": round(cost(free), 3)}
    opt = json.loads(actor.optimized_route(json.dumps({"locations": locs, "costing": "auto",
                                                        "units": "kilometers"})))
    opt_order = [loc["original_index"] for loc in opt["trip"]["locations"]]
    return {"stops": names, "request": req, "response": resp, "brute_force": best,
            "optimized_route": {"order": opt_order, "matrix_time_s": round(cost(opt_order), 3)}}


def run_banner_cases(actor) -> dict:
    cases = json.loads((OUT / "banner_cases.json").read_text(encoding="utf-8"))["cases"]
    log: list = []
    rec = Recorder(actor, log)
    for c in cases:
        resp = R.route(rec, R.request([c["start"], c["end"]]))
        if "error" in resp:
            raise SystemExit(f"banner case {c['id']}: {resp['error']}")
        instr = [m["instruction"] for leg in resp["trip"]["legs"] for m in leg["maneuvers"]]
        if not any(c["expect_instruction"] in i for i in instr):
            raise SystemExit(f"banner case {c['id']}: no maneuver '{c['expect_instruction']}' in {instr}")
    return {"format": "beamline-recordings/1", "recordings": [dict(r, case=c["id"]) for r, c in zip(log, cases)]}


def osrm_request(case: dict) -> dict:
    """What Ferrostar 0.59's Valhalla adapter sends (common/ferrostar/src/routing_adapters/valhalla.rs),
    plus our units and costing options."""
    return {"locations": [case["start"], case["end"]], "costing": "auto", "costing_options": {"auto": {}},
            "format": "osrm", "banner_instructions": True, "voice_instructions": True,
            "filters": {"action": "include", "attributes": ["shape_attributes.speed", "shape_attributes.speed_limit",
                                                            "shape_attributes.time", "shape_attributes.length"]},
            "units": "miles", "language": "en-US"}


def run_errors(actor) -> dict:
    P = R.load_trips()["places"]
    out = []
    probes = [
        ("buffalo_ny", [R.loc(P["erie_perry_sq"]), {"lat": 42.8864, "lon": -78.8784}]),
        ("lake_erie", [R.loc(P["erie_perry_sq"]), {"lat": 42.30, "lon": -80.05}]),
        ("jamestown_ny", [R.loc(P["erie_perry_sq"]), {"lat": 42.0970, "lon": -79.2353}]),
    ]
    log: list = []
    rec = Recorder(actor, log)
    for name, locs in probes:
        n0 = len(log)
        resp = R.route(rec, R.request(locs))
        # the stop tie first (every_request), then without it when it found no street (171)
        for extra in log[n0:-1]:
            out.append({"id": f"{name}/tied", **extra})
        out.append({"id": name, "summary": summary(resp), **log[-1]})
        if "error" in resp:
            continue
        # batch H: the planner decides the end (the nearest street against the place itself, untied, and the
        # tail call of the end it keeps); those requests too, once each
        seen = {json.dumps(r["request"], sort_keys=True) for r in log}
        n0 = len(log)
        R.run_preset(rec, locs, R.compose("fastest", (), R.load_preset_file()))
        for k, extra in enumerate(log[n0:]):
            key = json.dumps(extra["request"], sort_keys=True)
            if key not in seen:
                seen.add(key)
                out.append({"id": f"{name}/plan/{k}", **extra})
    return {"format": "beamline-recordings/1", "recordings": out}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", type=Path, default=REPO / "data" / "routing" / "device",
                    help="device folder written by packbuilder/routing/packs.py (PA+OH)")
    ap.add_argument("--only", choices=["trips", "plans", "banner", "osrm", "matrix", "reorder", "errors"], nargs="*")
    ap.add_argument("--timings", type=Path, default=None,
                    help="where the Python timings go (not committed: they change on every run). No default: a run "
                         "writes no timings unless it names the file, so it never overwrites another run's output "
                         "(in a parallel round: a file under data/r3-<key>/)")
    a = ap.parse_args()
    if valhalla.__version__ != "3.9.1":
        sys.exit(f"pyvalhalla {valhalla.__version__}; the device engine is 3.9.1")
    if not (a.device / "valhalla.json").is_file():
        sys.exit(f"no device folder at {a.device}: pass --device <checkout with the built packs>/data/routing/device "
                 "(written by packbuilder/routing/packs.py, docs/experiments/01-routing.md)")
    actor = engine(a.device)
    device_info = json.loads((a.device / "device.json").read_text(encoding="utf-8"))
    builds = sorted({json.loads(p.read_text(encoding="utf-8"))["graph_build"] for p in (a.device / "packs").glob("*.json")})
    meta = {"engine": f"pyvalhalla {valhalla.__version__}", "device_layout": device_info["layout"],
            "graph_build": builds, "packs": sorted(p.stem for p in (a.device / "packs").glob("*.json"))}
    ref = OUT / "reference"
    only = set(a.only or ["trips", "plans", "banner", "osrm", "matrix", "reorder", "errors"])
    timings: dict = {}
    if a.timings is not None and a.timings.is_file():
        timings = json.loads(a.timings.read_text(encoding="utf-8"))
    if "trips" in only:
        log: list = []
        t0 = time.perf_counter()
        trips = run_trips(actor, log, timings)
        timings["wall_s"] = round(time.perf_counter() - t0, 1)
        # each distinct request once (the replay engine answers by request)
        unique: dict[str, dict] = {}
        for r in log:
            unique.setdefault(json.dumps(r["request"], sort_keys=True), r)
        trips["meta"] = dict(meta, requests=len(log), distinct_requests=len(unique))
        if "matrix" in only:
            trips["matrix_1x40"] = matrix_targets(actor)
        write_json(ref / "trips.json", trips)
        write_json(ref / "recordings.json.gz", {"format": "beamline-recordings/1", "meta": meta,
                                                "recordings": list(unique.values())}, gz=True)
    if "plans" in only:
        log = []
        t0 = time.perf_counter()
        plans = run_plans(actor, log, timings)
        timings["plans_wall_s"] = round(time.perf_counter() - t0, 1)
        # recordings.json.gz already answers the requests format 1 made (the same request is the same
        # answer); the new file holds only the others, each once
        old = gzip.decompress((ref / "recordings.json.gz").read_bytes())
        known = {json.dumps(r["request"], sort_keys=True) for r in json.loads(old)["recordings"]}
        unique = {}
        for r in log:
            k = json.dumps(r["request"], sort_keys=True)
            if k not in known:
                unique.setdefault(k, r)
        plans["meta"] = dict(meta, requests=len(log), new_distinct_requests=len(unique))
        write_json(ref / "plans.json", plans)
        write_json(ref / "recordings-plans.json.gz", {"format": "beamline-recordings/1", "meta": meta,
                                                      "recordings": list(unique.values())}, gz=True)
    if ("trips" in only or "plans" in only) and a.timings is not None:
        a.timings.parent.mkdir(parents=True, exist_ok=True)
        a.timings.write_text(json.dumps(timings, indent=1) + "\n", encoding="utf-8")
        print(f"timings in {a.timings} (not committed)")
    elif "trips" in only or "plans" in only:
        print("timings not written (pass --timings FILE to keep them)")
    if "banner" in only:
        write_json(ref / "banner-routes.json.gz", dict(run_banner_cases(actor), meta=meta), gz=True)
    if "osrm" in only:
        case = json.loads((OUT / "banner_cases.json").read_text(encoding="utf-8"))["cases"][0]
        req = osrm_request(case)
        write_json(ref / "exit24-osrm.json", {"meta": meta, "request": req,
                                              "response": json.loads(actor.route(json.dumps(req)))})
    if "reorder" in only:
        write_json(ref / "reorder.json", dict(run_reorder(actor), meta=meta))
    if "errors" in only:
        write_json(ref / "errors.json", dict(run_errors(actor), meta=meta))
    return 0


if __name__ == "__main__":
    sys.exit(main())
