"""presets.json format 2 and its composition (docs/architecture.md 2.11), without an engine.

    .venv/routing/Scripts/python -m unittest packbuilder/routing/test_presets.py      (repo root)

The same rules are checked in Kotlin (android/routing/core PresetsFormat2Test); the reference
recordings (android/routing/scripts/reference_routes.py) compare the two planners on real routes.
"""
from __future__ import annotations

import copy
import json
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import route_lib as R  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
FORMAT1 = REPO / "android" / "routing" / "testdata" / "reference" / "presets-format1.json"


class FakeActor:
    """Answers route requests from a function of the parsed request; records them."""

    def __init__(self, answer):
        self.answer, self.requests = answer, []

    def route(self, s: str) -> str:
        req = json.loads(s)
        self.requests.append(req)
        out = self.answer(req)
        if isinstance(out, Exception):
            raise out
        return json.dumps(out)


def trip(*mans, time=600.0, alternates=(), shape=""):
    """A minimal Valhalla answer: one leg, maneuvers (type, km, flags...)."""
    ms = [dict({"type": t, "length": km, "time": 10}, **{f: True for f in flags}) for t, km, *flags in mans]
    km = sum(m["length"] for m in ms)
    tr = {"locations": [{"lat": 40.0, "lon": -77.0}, {"lat": 40.01, "lon": -77.0}], "units": "kilometers",
          "legs": [{"maneuvers": ms, "summary": {"length": km, "time": time}, "shape": shape}],
          "summary": {"length": km, "time": time}}
    out = {"trip": tr}
    if alternates:
        out["alternates"] = [{"trip": a["trip"]} for a in alternates]
    return out


NO_PATH = RuntimeError('{"error_code": 442, "error": "No path could be found for input"}')
LOCS = [{"lat": 40.0, "lon": -77.0}, {"lat": 40.01, "lon": -77.0}]


def auto(req):
    return req["costing_options"]["auto"]


class PresetsFormat2(unittest.TestCase):
    def setUp(self):
        self.pf = R.load_preset_file()
        self.f1 = R.load_presets_format1(FORMAT1)

    def test_file_is_format_2_and_valid(self):
        self.assertEqual("beamline-route-presets/2", self.pf["format"])
        self.assertEqual([], R.validate_preset_file(self.pf))
        self.assertEqual(["fastest", "simpler", "eco"], [t["id"] for t in self.pf["route_types"]], "Shortest left in batch D")
        self.assertEqual(["fastest", "simpler"], [t["id"] for t in self.pf["route_types"] if t["offered"]])
        self.assertEqual(["avoid_highways", "avoid_tolls"], [o["id"] for o in self.pf["options"]])
        self.assertEqual({"route_type": "fastest", "options": []}, self.pf["default"])

    def test_compose_without_options_equals_format_1(self):
        # Simpler changed in batch D (window, cap, a fourth candidate); Shortest left
        for tid in ("fastest", "eco"):
            c = R.compose(tid, (), self.pf)
            self.assertTrue(R.same_run(c, self.f1[tid]), tid)
            self.assertNotIn("options", c)
            self.assertEqual(tid, c["id"])

    def test_fastest_with_avoid_highways_equals_format_1_avoid_highways(self):
        c = R.compose("fastest", ["avoid_highways"], self.pf)
        self.assertTrue(R.same_run(c, self.f1["avoid_highways"]))
        self.assertEqual("fastest+avoid_highways", c["id"])
        self.assertEqual(["highway"], c["avoids"])

    def test_avoid_tolls_is_the_hard_exclusion_with_the_soft_fallback(self):
        for tid in ("fastest", "eco"):
            c = R.compose(tid, ["avoid_tolls"], self.pf)
            self.assertIs(True, c["costing_options"]["exclude_tolls"], tid)
            self.assertEqual(0.0, c["fallback_costing_options"]["use_tolls"], tid)
            self.assertNotIn("exclude_tolls", c["fallback_costing_options"])
            self.assertNotIn("fallback_when", c, "Avoid tolls has no fallback_when")
        e = R.compose("eco", ["avoid_tolls"], self.pf)
        self.assertEqual({"use_distance": 0.5, "exclude_tolls": True}, e["costing_options"])
        self.assertEqual({"use_distance": 0.5, "use_tolls": 0.0}, e["fallback_costing_options"])

    def test_option_fallback_when_false_drops_fallback_when(self):
        # no type sets it since Shortest left (batch D); the format still allows it
        pf = copy.deepcopy(self.pf)
        pf["route_types"][2]["option_fallback_when"] = False
        self.assertEqual([], R.validate_preset_file(pf))
        s = R.compose("eco", ["avoid_highways"], pf)
        self.assertNotIn("fallback_when", s)
        self.assertEqual({"use_distance": 0.5, "use_highways": 0.0}, s["fallback_costing_options"])
        f = R.compose("fastest", ["avoid_highways", "avoid_tolls"], self.pf)
        self.assertEqual({"highway_km_over": 0.5}, f["fallback_when"])
        self.assertEqual({"exclude_highways": True, "exclude_tolls": True}, f["costing_options"])
        self.assertEqual({"use_highways": 0.0, "use_tolls": 0.0}, f["fallback_costing_options"])
        self.assertEqual(["highway", "toll"], f["avoids"])

    def test_pool_type_merges_every_candidate(self):
        c = R.compose("simpler", ["avoid_tolls"], self.pf)
        self.assertEqual([{"exclude_tolls": True}, {"maneuver_penalty": 60, "exclude_tolls": True},
                          {"maneuver_penalty": 300, "exclude_tolls": True},
                          {"use_highways": 1.0, "maneuver_penalty": 60, "exclude_tolls": True}], c["candidates"])
        self.assertEqual([{"use_tolls": 0.0}, {"maneuver_penalty": 60, "use_tolls": 0.0},
                          {"maneuver_penalty": 300, "use_tolls": 0.0},
                          {"use_highways": 1.0, "maneuver_penalty": 60, "use_tolls": 0.0}], c["fallback_candidates"])
        self.assertEqual((0.10, 600), (c["max_time_increase"], c["max_time_extra_s"]))
        h = R.compose("simpler", ["avoid_highways"], self.pf)
        self.assertEqual({"use_highways": 0.0, "maneuver_penalty": 60}, h["fallback_candidates"][3],
                         "the option's fallback wins over the candidate's use_highways")
        self.assertNotIn("costing_options", c)
        self.assertEqual({"highway_km_over": 0.5}, R.compose("simpler", ["avoid_highways"], self.pf)["fallback_when"])

    def test_options_in_file_order_whatever_the_order_asked(self):
        self.assertEqual(R.compose("fastest", ["avoid_tolls", "avoid_highways"], self.pf),
                         R.compose("fastest", ["avoid_highways", "avoid_tolls"], self.pf))

    def test_refusals(self):
        with self.assertRaises(KeyError):
            R.compose("quickest", (), self.pf)
        with self.assertRaises(KeyError):
            R.compose("fastest", ["avoid_ferries"], self.pf)
        bad = copy.deepcopy(self.pf)
        bad["options"][1]["costing_options"]["exclude_highways"] = True
        self.assertTrue(any("both set" in e for e in R.validate_preset_file(bad)), R.validate_preset_file(bad))
        bad = copy.deepcopy(self.pf)
        bad["route_types"][2]["costing_options"]["use_distnace"] = 0.5
        self.assertTrue(any("use_distnace" in e for e in R.validate_preset_file(bad)))
        bad = copy.deepcopy(self.pf)
        bad["default"]["route_type"] = "eco"
        self.assertTrue(any("not an offered type" in e for e in R.validate_preset_file(bad)))
        bad = copy.deepcopy(self.pf)
        bad["route_types"][0]["request_options"] = {"date_time": {"type": 0}}
        self.assertTrue(any("request_options" in e for e in R.validate_preset_file(bad)))
        bad = copy.deepcopy(self.pf)
        bad["route_types"][1]["max_time_extra_s"] = -1
        self.assertTrue(any("max_time_extra_s" in e for e in R.validate_preset_file(bad)))
        bad = dict(self.pf, format="beamline-route-presets/1")
        self.assertEqual(["format is not beamline-route-presets/2"], R.validate_preset_file(bad))

    def test_legacy_five(self):
        six = R.load_presets()
        self.assertEqual(["fastest", "simpler", "eco", "avoid_highways", "avoid_tolls"], list(six))
        for k in ("fastest", "eco", "avoid_highways"):
            self.assertTrue(R.same_run(six[k], self.f1[k]), k)
        self.assertEqual({"exclude_tolls": True}, six["avoid_tolls"]["costing_options"])


class RunComposed(unittest.TestCase):
    def setUp(self):
        self.pf = R.load_preset_file()

    def test_no_route_fallback_for_every_type_with_the_warning(self):
        # a destination on a toll road: every request with exclude_tolls finds no path
        def answer(req):
            if auto(req).get("exclude_tolls"):
                return NO_PATH
            return trip((1, 0.1), (10, 2.0, "toll"), (4, 0.0))
        for tid in ("fastest", "simpler", "eco"):
            a = FakeActor(answer)
            r = R.run_preset(a, LOCS, R.compose(tid, ["avoid_tolls"], self.pf))
            self.assertNotIn("error", r, tid)
            self.assertTrue(r["used_fallback"], tid)
            self.assertEqual("no_route", r["fallback_reason"])
            self.assertEqual("No route to this place avoids toll roads. This route uses them.", r["fallback_warning"])
            self.assertNotIn("exclude_tolls", auto(r["_request"]), tid)

    def test_warning_names_what_the_route_uses(self):
        p = R.compose("fastest", ["avoid_highways", "avoid_tolls"], self.pf)
        hw = trip((1, 0.1), (10, 2.0, "highway"), (4, 0.0))
        self.assertEqual("No route to this place avoids highways. This route uses them.", R.option_fallback_warning(p, hw))
        both = trip((1, 0.1), (10, 2.0, "highway", "toll"), (4, 0.0))
        self.assertEqual("No route to this place avoids highways and toll roads. This route uses them.",
                         R.option_fallback_warning(p, both))
        neither = trip((1, 0.1), (4, 0.0))
        self.assertEqual("No route to this place avoids highways and toll roads.", R.option_fallback_warning(p, neither))
        self.assertIsNone(R.option_fallback_warning(R.compose("fastest", (), self.pf), hw))

    def test_fallback_when_rerun_has_no_warning(self):
        def answer(req):
            if auto(req).get("exclude_highways"):
                return trip((1, 0.1), (10, 5.0, "highway"), (4, 0.0))
            return trip((1, 0.1), (10, 0.4, "highway"), (15, 6.0), (4, 0.0), time=900.0)
        a = FakeActor(answer)
        r = R.run_preset(a, LOCS, R.compose("fastest", ["avoid_highways"], self.pf))
        self.assertTrue(r["used_fallback"])
        self.assertNotIn("fallback_warning", r)
        self.assertEqual(4, len(a.requests), "the nearest street and the place itself (batch H), each with its fallback")

    def test_pool_fallback_when_keeps_the_route_with_less_motorway(self):
        def answer(req):
            o = auto(req)
            if o.get("exclude_highways"):
                return trip((1, 0.1), (10, 5.0, "highway"), (4, 0.0), time=600.0)
            return trip((1, 0.1), (10, 0.2, "highway"), (15, 6.0), (4, 0.0), time=650.0)
        a = FakeActor(answer)
        r = R.run_preset(a, LOCS, R.compose("simpler", ["avoid_highways"], self.pf))
        self.assertTrue(r["used_fallback"])
        self.assertNotIn("fallback_warning", r)
        self.assertEqual(12, len(a.requests), "the decision's Fastest and its fallback to the nearest street and to the place (batch H), "
                         "the pool, the pool again with the fallback options")
        self.assertEqual(2, a.requests[8]["alternates"], "the fallback pool's first request asks for alternates too")
        self.assertAlmostEqual(0.2, R.flagged_km(r, "highway"))

    def test_pool_without_a_better_fallback_keeps_the_first(self):
        same = trip((1, 0.1), (10, 5.0, "highway"), (4, 0.0))
        a = FakeActor(lambda req: same)
        r = R.run_preset(a, LOCS, R.compose("simpler", ["avoid_highways"], self.pf))
        self.assertNotIn("used_fallback", r, "tie: the first pool's route")
        self.assertEqual(12, len(a.requests))


class SimplerBatchD(unittest.TestCase):
    """Batch D (docs/next-round.md item 13): the window (10 %, at most 10 minutes) and the tie check
    and arrival side on an alternate the pool picked."""

    def setUp(self):
        self.p = R.compose("simpler", (), R.load_preset_file())

    def test_window_is_ten_percent_and_never_more_than_ten_minutes(self):
        def pool(*times):
            return [{"trip": {"summary": {"time": t}}} for t in times]
        self.assertAlmostEqual(660.0, R.pool_limit_s(pool(600.0, 700.0), self.p))
        self.assertAlmostEqual(6600.0, R.pool_limit_s(pool(6000.0), self.p), msg="100 min: +10 min, both limits")
        self.assertAlmostEqual(8600.0, R.pool_limit_s(pool(8000.0), self.p), msg="133 min: +10 min, not +13.3")
        self.assertAlmostEqual(8800.0, R.pool_limit_s(pool(8000.0), dict(self.p, max_time_extra_s=None)))

    def test_picks_fewest_turns_within_the_cap(self):
        def answer(req):
            mp = auto(req).get("maneuver_penalty")
            if mp == 300:   # 9.5 min longer and 2 turns fewer: inside 10 % of 100 min and 10 min
                return trip((1, 0.1), (10, 1.0), (5, 0.0), time=6570.0)
            if mp == 60 and auto(req).get("use_highways") == 1.0:   # 10.5 min longer: past the cap
                return trip((1, 0.1), (5, 0.0), time=6630.0)
            return trip((1, 0.1), (10, 1.0), (10, 1.0), (10, 1.0), (5, 0.0), time=6000.0)
        r = R.run_preset(FakeActor(answer), LOCS, self.p)
        self.assertEqual(6570.0, r["trip"]["summary"]["time"])

    def _answer(self, side_ok=True):
        main_shape = R_encode_pts([(40.0, -77.0), (40.0, -76.99), (40.01, -76.99), (40.01, -77.0)])
        alt_pts = [(40.0, -77.0), (40.003, -77.0), (40.006, -77.0), (40.009, -77.0), (40.01, -77.0)]
        alt_shape = R_encode_pts(alt_pts)
        side_shape = R_encode_pts(alt_pts[:4] + [(40.0095, -76.999), (40.01, -76.9995)])

        def answer(req):
            loc = req["locations"][1]
            main = trip((1, 0.1), (10, 1.0), (15, 1.0), (10, 1.0), (5, 0.0), time=600.0, shape=main_shape)
            if "alternates" not in req:
                return main
            if "preferred_side" in loc:
                alt = trip((1, 0.1), (5 if side_ok else 6, 0.0), time=640.0, shape=side_shape)
            else:
                alt = trip((1, 0.1), (6, 0.0), time=620.0, shape=alt_shape)
            return trip((1, 0.1), (10, 1.0), (15, 1.0), (10, 1.0), (5, 0.0), time=600.0, shape=main_shape, alternates=[alt])
        return answer

    def test_simpler_routes_to_the_end_fastest_decided_with_its_side(self):
        # batch E2 (docs/next-round.md item 19): the side is asked once, on the decision's Fastest route; the
        # pool then routes to that same end with the same side options and asks nothing more
        def answer(req):
            loc = req["locations"][1]
            if "preferred_side" in loc:
                return trip((1, 0.1), (10, 1.0), (5, 0.0), time=630.0)
            return trip((1, 0.1), (10, 1.0), (6, 0.0), time=600.0)
        a = FakeActor(answer)
        r = R.run_preset(a, LOCS, self.p)
        self.assertEqual("side", r["arrival_side"], "the decision's side request")
        self.assertEqual("kept", r["tie_check"], "the nearest street, as fast as the place itself (batch H)")
        pool = [q for q in a.requests if "alternates" in q or auto(q).get("maneuver_penalty") != auto(a.requests[0]).get("maneuver_penalty")]
        self.assertTrue(pool and all(q["locations"][1].get("preferred_side") == "same" for q in pool), "every pool request goes to the decided end")
        again = FakeActor(answer)
        r2 = R.run_preset(again, r["_ends"], self.p)
        self.assertTrue(all(q["locations"][1] == r["_request"]["locations"][1] for q in again.requests), "a decided end is sent as it is")
        self.assertNotIn("arrival_side", r2, "decided before: no side request")

    def test_same_way_needs_half_of_the_route(self):
        pts = [(40.0, -77.0), (40.003, -77.0), (40.006, -77.0), (40.009, -77.0), (40.01, -77.0)]
        picked = trip((1, 0.1), (5, 0.0), shape=R_encode_pts(pts))
        early = trip((1, 0.1), (5, 0.0), shape=R_encode_pts(pts[:2] + [(40.004, -76.99), (40.01, -76.99)]))
        late = trip((1, 0.1), (5, 0.0), shape=R_encode_pts(pts[:4] + [(40.01, -76.999)]))
        self.assertIsNone(R.same_way(picked, [early]), "parts after 3 of 10 km")
        self.assertEqual(1, R.same_way(picked, [early, late]))
        self.assertIsNone(R.same_way(trip((1, 0.1), (5, 0.0)), [late]), "no shape")


def R_encode_pts(pts):
    return R_encode(pts)


class EveryRequestFix(unittest.TestCase):
    """The C1 routing fix (docs/experiments/23-batch-c.md "C1 routing fix"): the tie check, the side
    route only when really on the right, map points not tied, the address street's name."""

    def setUp(self):
        self.p = R.compose("fastest", (), R.load_preset_file())

    def _street_actor(self, answer, tail_uses=("road",)):
        """An engine whose address probe finds Main Street 60 m north of LOCS[1] and whose tail trace answers [tail_uses]."""
        lat, lon = LOCS[1]["lat"], LOCS[1]["lon"]
        probe = self._lot_answer(lat, lon, [("road", "residential", "Main Street", 60)])

        class Probe(FakeActor):
            def trace_attributes(self, s):
                q = json.loads(s)
                self.probes = getattr(self, "probes", []) + [q]
                if q.get("shape_match") == "edge_walk":
                    return json.dumps({"edges": [{"use": u} for u in tail_uses]})
                return json.dumps(probe)
        return Probe(answer)

    def test_address_street_end_kept_within_90_s_of_the_nearest_street_and_no_more_u_turns(self):
        # batch E2: the ends are tried best first; the street end against the next end, the nearest street
        def answer(req):
            street = "display_lat" in req["locations"][1]
            return trip((1, 0.1), (5, 0.0), time=680.0 if street else 600.0, shape=R_encode([(40.0, -77.0), (40.01, -77.0)]))
        r = R.run_preset(self._street_actor(answer), [LOCS[0], dict(LOCS[1], _street="Main Street")], self.p)
        self.assertEqual("kept", r["tie_check"])
        self.assertIn("display_lat", r["_request"]["locations"][1])
        self.assertIn("search_filter", r["_request"]["locations"][1], "rule 2's tie stays on the street end")
        self.assertEqual("street", r["_ends"][1][R.END_KEY])
        self.assertTrue(r["_ends"][1][R.FIXED_KEY])

    def test_next_end_when_slower_a_u_turn_more_or_an_alley_in_the_last_300_m(self):
        shape = R_encode([(40.0, -77.0), (40.01, -77.0)])
        for street_trip, uses in ((trip((1, 0.1), (5, 0.0), time=691.0, shape=shape), ("road",)),
                                  (trip((1, 0.1), (13, 0.1), (5, 0.0), time=590.0, shape=shape), ("road",)),
                                  (trip((1, 0.1), (5, 0.0), time=600.0, shape=shape), ("road", "alley"))):
            def answer(req, street_trip=street_trip):
                if "display_lat" in req["locations"][1]:
                    return street_trip
                return trip((1, 0.1), (5, 0.0), time=600.0, shape=shape)
            r = R.run_preset(self._street_actor(answer, uses), [LOCS[0], dict(LOCS[1], _street="Main Street")], self.p)
            self.assertEqual("untied", r["tie_check"])
            self.assertNotIn("display_lat", r["_request"]["locations"][1])
            self.assertIn("search_filter", r["_request"]["locations"][1], "the nearest street, tied by rule 2")
            self.assertEqual("near", r["_ends"][1][R.END_KEY])

    def test_the_place_itself_untied_is_the_last_end_and_never_wins_with_an_alley(self):
        # batch H (docs/next-round.md, the owner's answers after batch G): the nearest street is checked
        # against the place itself, untied; the place does not win with an alley or a lane in its last 300 m
        near_shape = R_encode([(40.0, -77.0), (40.01, -77.0)])
        place_shape = R_encode([(40.0, -77.0), (40.0102, -77.0)])

        class Tail(FakeActor):
            place_tail = ("road",)
            near_way = True

            def trace_attributes(self, s):
                q = json.loads(s)
                self.tails = getattr(self, "tails", 0) + 1
                at_place = abs(q["shape"][-1]["lat"] - 40.0102) < 1e-6
                return json.dumps({"edges": [{"use": u} for u in (self.place_tail if at_place else ("road",))]})

        for near_trip, place_tail, near_way, end in (
                (trip((1, 0.1), (13, 0.1), (5, 0.0), time=586.6, shape=near_shape), ("road",), True, "place"),
                (trip((1, 0.1), (5, 0.0), time=712.0, shape=near_shape), ("road",), True, "place"),
                (trip((1, 0.1), (5, 0.0), time=711.0, shape=near_shape), ("road",), True, "near"),
                (trip((1, 0.1), (13, 0.1), (5, 0.0), time=586.6, shape=near_shape), ("road", "alley"), True, "near"),
                (trip((1, 0.1), (13, 0.1), (5, 0.0), time=586.6, shape=near_shape), ("drive_through", "parking_aisle"), True, "near"),
                (None, ("road", "alley"), False, "place")):
            def answer(req, near_trip=near_trip):
                if "search_filter" not in req["locations"][1]:
                    return trip((1, 0.1), (5, 0.0), time=621.9, shape=place_shape)
                return near_trip if near_trip is not None else RuntimeError("no path")
            a = Tail(answer)
            a.place_tail = place_tail
            r = R.run_preset(a, [LOCS[0], dict(LOCS[1])], self.p)
            self.assertEqual(end, r["_ends"][1][R.END_KEY], (near_trip and near_trip["trip"]["summary"]["time"], place_tail))
            got = r["_request"]["locations"][1]
            if end == "place":
                self.assertNotIn("search_filter", got, "the place itself: no stop tie")
                self.assertEqual("untied", r["tie_check"])
            else:
                self.assertIn("search_filter", got, "the nearest street, tied by rule 2")
                self.assertEqual("kept", r["tie_check"])
            self.assertLessEqual(a.tails, 2, "at most the place's tail and the kept end's tail")
        # a map point has no list: no place end, nothing checked
        a = Tail(lambda req: trip((1, 0.1), (5, 0.0), shape=near_shape))
        r = R.run_preset(a, [LOCS[0], dict(LOCS[1], _tie="none")], self.p)
        self.assertEqual("pin", r["_ends"][1][R.END_KEY])
        self.assertFalse(hasattr(a, "tails"))

    def test_side_route_is_kept_only_when_it_arrives_on_the_right(self):
        for side_end, kept in ((5, "side"), (4, "asked"), (6, "asked")):
            def answer(req, side_end=side_end):
                return trip((1, 0.1), (side_end if "preferred_side" in req["locations"][1] else 6, 0.0), time=600.0)
            r = R.run_preset(FakeActor(answer), LOCS, self.p)
            self.assertEqual(kept, r["arrival_side"], side_end)

    def test_side_is_asked_on_tertiary_and_above_only(self):
        # batch D (next-round item 16): on residential and below the engine ignores the side
        rule = R.every_request()["arrival_side"]
        self.assertEqual("tertiary", rule["street_side_cutoff"])
        a = FakeActor(lambda req: trip((1, 0.1), (6, 0.0)))
        R.run_preset(a, [LOCS[0], dict(LOCS[1], _tie="none")], self.p)
        self.assertEqual("tertiary", a.requests[-1]["locations"][1]["street_side_cutoff"])
        self.assertEqual("same", a.requests[-1]["locations"][1]["preferred_side"])

    def test_side_route_one_block_at_most(self):
        # batch D: at most max_extra_maneuvers (2) more guidance instructions and 60 s; never a longer loop
        self.assertEqual(2, R.every_request()["arrival_side"]["max_extra_maneuvers"])
        for extra, kept in ((2, "side"), (3, "asked")):
            def answer(req, extra=extra):
                if "preferred_side" in req["locations"][1]:
                    return trip((1, 0.1), *[(10, 0.1)] * (1 + extra), (5, 0.0), time=630.0)
                return trip((1, 0.1), (10, 0.1), (6, 0.0), time=600.0)
            r = R.run_preset(FakeActor(answer), [LOCS[0], dict(LOCS[1], _tie="none")], self.p)
            self.assertEqual(kept, r["arrival_side"], extra)

    def test_a_map_point_is_not_tied(self):
        a = FakeActor(lambda req: trip((1, 0.1), (5, 0.0)))
        R.run_preset(a, [LOCS[0], dict(LOCS[1], _tie="none")], self.p)
        self.assertEqual(1, len(a.requests), "no tie, so no tie check")
        self.assertEqual({"lat": 40.01, "lon": -77.0}, a.requests[0]["locations"][1])
        self.assertEqual(5, a.requests[0]["costing_options"]["auto"]["alley_factor"], "alleys still avoided")

    def test_street_key(self):
        w = R.every_request()["address_street"]["words"]
        self.assertEqual(R.street_key("E 12th St.", w), R.street_key("East 12th Street", w))
        self.assertNotEqual(R.street_key("Holland Street", w), R.street_key("East 12th Street", w))

    def test_address_tie_point_picks_the_named_street_inside_its_edge(self):
        rule = R.every_request()["address_street"]
        lat, lon = 42.0, -80.0
        dy = 1 / 111320.0
        # a street named Holland 30 m north (east-west), East 12th Street 60 m east (north-south, 200 m long)
        holland = [(lat + 30 * dy, lon - 0.002), (lat + 30 * dy, lon + 0.002)]
        e12 = [(lat - 100 * dy, lon + 60 * dy / math.cos(math.radians(lat))), (lat + 100 * dy, lon + 60 * dy / math.cos(math.radians(lat)))]
        resp = {"shape": R_encode(holland + e12), "edges": [
            {"names": ["Holland Street"], "road_class": "residential", "begin_shape_index": 0, "end_shape_index": 1},
            {"names": ["East 12th Street", "PA 5"], "road_class": "primary", "begin_shape_index": 2, "end_shape_index": 3}]}
        q = R.address_tie_point(resp, lat, lon, "E 12th St", rule, R.ROAD_CLASSES[1:7])
        self.assertIsNotNone(q)
        self.assertAlmostEqual(60.0, R.haversine_m((lat, lon), q), delta=1.0)
        self.assertIsNone(R.address_tie_point(resp, lat, lon, "French Street", rule, R.ROAD_CLASSES[1:7]))

    # batch E (docs/next-round.md item 18): a point of interest ends in its own lot
    def _lot_answer(self, lat, lon, edges):
        """A probe answer: edges [(use, road_class, name, metres north of the place)], each east-west, 100 m long."""
        dy, dx = 1 / 111320.0, 1 / (111320.0 * math.cos(math.radians(lat)))
        pts, out = [], []
        for k, (use, rc, name, north) in enumerate(edges):
            pts += [(lat + north * dy, lon - 50 * dx), (lat + north * dy, lon + 50 * dx)]
            out.append({"names": [name] if name else [], "road_class": rc, "use": use,
                        "begin_shape_index": 2 * k, "end_shape_index": 2 * k + 1})
        return {"shape": R_encode(pts), "edges": out}

    def test_lot_tie_point_takes_a_driveway_or_aisle_nearer_than_the_streets_never_an_alley(self):
        lot, classes, lat, lon = R.every_request()["lot"], R.ROAD_CLASSES[1:7], 42.0, -80.0
        self.assertEqual(["driveway", "parking_aisle"], lot["uses"])
        near = lambda *e: R.lot_tie_point([self._lot_answer(lat, lon, e[:1]), self._lot_answer(lat, lon, e[1:])], lat, lon, lot, 10, classes)
        q = near(("driveway", "service_other", "", 20), ("road", "residential", "Mandy Lane", 45), ("road", "primary", "Peach Street", -48))
        self.assertAlmostEqual(20.0, R.haversine_m((lat, lon), q), delta=1.0)
        q = near(("alley", "service_other", "", 5), ("parking_aisle", "service_other", "", 30), ("road", "primary", "Peach Street", 60))
        self.assertAlmostEqual(30.0, R.haversine_m((lat, lon), q), delta=1.0, msg="the aisle, never the nearer alley")
        self.assertIsNone(near(("drive_through", "service_other", "", 10), ("road", "residential", "A Street", 50)), "never a drive-through")
        self.assertIsNone(near(("driveway", "service_other", "", 20), ("road", "primary", "State Street", 25)),
                          "a street less than street_margin_m farther: the street")
        self.assertIsNone(near(("driveway", "service_other", "", 85)), "farther than within_m")

    def test_a_point_of_interest_ends_in_its_lot_else_its_street_and_every_type_ends_there(self):
        lat, lon = 40.01, -77.0
        probe = self._lot_answer(lat, lon, [("driveway", "service_other", "", 20), ("road", "residential", "Main Street", 60)])

        class Probe(FakeActor):
            tail = ("road", "driveway")

            def trace_attributes(self, s):
                q = json.loads(s)
                if "filters" in q and q["shape_match"] == "edge_walk" and "edge.begin_shape_index" in q["filters"]["attributes"]:
                    # item 35: the way into the lot is looked at once the end is decided (no other way in here)
                    self.entrances = getattr(self, "entrances", 0) + 1
                    return json.dumps({"edges": []})
                if "filters" in q and len(q["shape"]) < 9:
                    self.tails = getattr(self, "tails", 0) + 1
                    lot_way = abs(q["shape"][-1]["lat"] - 40.0099) < 1e-6
                    return json.dumps({"edges": [{"use": u} for u in (self.tail if lot_way else ("road",))]})
                self.probes = getattr(self, "probes", []) + [q]
                return json.dumps(probe)

        shape = R_encode([(40.0, -77.0), (40.01, -77.0)])
        lot_shape = R_encode([(40.0, -77.0), (40.0099, -77.0)])
        for street_time, tail, kept, end in ((560.0, ("road", "driveway"), "kept", "lot"),
                                             (500.0, ("road", "driveway"), "untied", "street"),
                                             (560.0, ("road", "drive_through", "parking_aisle"), "untied", "street")):
            def answer(req, street_time=street_time):
                d = req["locations"][1]
                if d.get("preferred_side") == "either":
                    return trip((1, 0.1), (10, 0.1), (6, 0.0), time=600.0, shape=lot_shape)
                if "display_lat" in d:
                    return trip((1, 0.1), (5, 0.0), time=street_time, shape=shape)
                return trip((1, 0.1), (5, 0.0), time=590.0, shape=shape)
            a = Probe(answer)
            a.tail = tail
            r = R.run_preset(a, [LOCS[0], {"lat": lat, "lon": lon, "_street": "Main Street", "_poi": True}], self.p)
            self.assertIn("edge.use", a.probes[0]["filters"]["attributes"])
            self.assertEqual(2, len(a.probes), "the address probe (with edge.use) and the small lot probe near the place")
            self.assertLess(len(a.probes[1]["shape"]), len(a.probes[0]["shape"]))
            first = a.requests[0]["locations"][1]
            self.assertEqual("either", first["preferred_side"])
            self.assertNotIn("search_filter", first, "no stop tie in the lot")
            self.assertEqual((lat, lon), (first["display_lat"], first["display_lon"]))
            self.assertAlmostEqual(20.0, R.haversine_m((lat, lon), (first["lat"], first["lon"])), delta=1.0)
            check = a.requests[1]["locations"][1]
            self.assertIn("search_filter", check, "compared with the next end, the street, tied as before")
            self.assertAlmostEqual(60.0, R.haversine_m((lat, lon), (check["lat"], check["lon"])), delta=1.0)
            self.assertEqual(kept, r["tie_check"], street_time)
            self.assertEqual(end, r["_ends"][1][R.END_KEY], (street_time, tail))
            if end == "lot":
                self.assertEqual(2, len(a.requests), "no side request for a lot stop (it arrives on the left)")
                self.assertEqual(1, a.tails, "one tail check, for the end about to be kept")
                self.assertEqual(1, a.entrances, "and one look at the way into the lot")
            # Simpler goes to the same end with no probe and no check
            b = Probe(answer)
            s2 = R.run_preset(b, r["_ends"], R.compose("simpler", (), R.load_preset_file()))
            self.assertFalse(hasattr(b, "probes") or hasattr(b, "tails"))
            self.assertTrue(all(q["locations"][1] == r["_request"]["locations"][1] for q in b.requests))
            self.assertNotIn("tie_check", s2)
        # not a point of interest: no edge.use, the address street as before
        a = Probe(lambda req: trip((1, 0.1), (5, 0.0), shape=shape))
        R.run_preset(a, [LOCS[0], {"lat": lat, "lon": lon, "_street": "Main Street"}], self.p)
        self.assertNotIn("edge.use", a.probes[0]["filters"]["attributes"])
        self.assertNotIn("preferred_side", a.requests[0]["locations"][1])

    # item 28 (docs/next-round.md): a large building's lot is looked for around its door
    def test_lot_tie_point_by_a_door_takes_a_service_road_too(self):
        lot, classes, lat, lon = R.every_request()["lot"], R.ROAD_CLASSES[1:7], 42.0, -80.0
        self.assertEqual(["driveway", "parking_aisle", "service_road"], lot["door_uses"])
        ans = [self._lot_answer(lat, lon, [("service_road", "service_other", "", 8), ("parking_aisle", "service_other", "", 40),
                                           ("road", "primary", "Peach Street", 70)])]
        q = R.lot_tie_point(ans, lat, lon, lot, 10, classes, door=True)
        self.assertAlmostEqual(8.0, R.haversine_m((lat, lon), q), delta=1.0, msg="the lane by the door, mapped as a plain service road")
        q = R.lot_tie_point(ans, lat, lon, lot, 10, classes)
        self.assertAlmostEqual(40.0, R.haversine_m((lat, lon), q), delta=1.0, msg="around a centroid a service road never counts")
        ans = [self._lot_answer(lat, lon, [("alley", "service_other", "", 5), ("drive_through", "service_other", "", 6)])]
        self.assertIsNone(R.lot_tie_point(ans, lat, lon, lot, 10, classes, door=True), "never an alley or a drive-through lane")

    def test_a_point_of_interest_with_a_door_ends_at_the_lane_by_the_door(self):
        lat, lon = 40.01, -77.0
        door = {"lat": 40.0105, "lon": -77.0}  # 55.7 m north of the centroid
        # behind the building a driveway 20 m south of the centroid; along its front a service road 65 m north
        # of the centroid (9 m from the door); the street 130 m north
        probe = self._lot_answer(lat, lon, [("driveway", "service_other", "", -20), ("service_road", "service_other", "", 65),
                                            ("road", "residential", "Main Street", 130)])

        class Probe(FakeActor):
            def trace_attributes(self, s):
                q = json.loads(s)
                if "filters" in q and len(q["shape"]) < 9:
                    return json.dumps({"edges": [{"use": "road"}, {"use": "service_road"}]})
                self.probes = getattr(self, "probes", []) + [q]
                return json.dumps(probe)

        shape = R_encode([(40.0, -77.0), (40.01, -77.0)])
        answer = lambda req: trip((1, 0.1), (5, 0.0), shape=shape)
        a = Probe(answer)
        r = R.run_preset(a, [LOCS[0], {"lat": lat, "lon": lon, "_poi": True, "_door": door}], self.p)
        first = a.requests[0]["locations"][1]
        self.assertEqual("lot", r["_ends"][1][R.END_KEY])
        self.assertAlmostEqual(9.3, R.haversine_m((door["lat"], door["lon"]), (first["lat"], first["lon"])), delta=1.0,
                               msg="the lane by the door")
        self.assertEqual((lat, lon), (first["display_lat"], first["display_lon"]), "the place itself is still the display point")
        self.assertTrue(all("_door" not in loc for q in a.requests for loc in q["locations"]), "never sent to the engine")
        self.assertNotIn("_door", r["_ends"][1], "a decided stop carries no door")
        small = a.probes[1]["shape"]
        self.assertAlmostEqual(door["lat"], sum(p["lat"] for p in small) / len(small), delta=1e-5, msg="the small probe is around the door")
        big = a.probes[0]["shape"]
        self.assertAlmostEqual(lat, sum(p["lat"] for p in big) / len(big), delta=1e-5, msg="the address probe stays around the place")
        # the same place without a door (a pack built before 2026-11-21): the driveway behind the building, as before
        b = Probe(answer)
        R.run_preset(b, [LOCS[0], {"lat": lat, "lon": lon, "_poi": True}], self.p)
        first = b.requests[0]["locations"][1]
        self.assertAlmostEqual(20.0, R.haversine_m((lat, lon), (first["lat"], first["lon"])), delta=1.0)
        self.assertLess(first["lat"], lat)

    def test_a_lot_by_the_door_may_be_slower_by_the_walk_it_saves(self):
        lat, lon = 40.01, -77.0
        door = {"lat": 40.0105, "lon": -77.0}
        probe = self._lot_answer(lat, lon, [("service_road", "service_other", "", 65), ("road", "residential", "Main Street", 300)])

        class Probe(FakeActor):
            def trace_attributes(self, s):
                q = json.loads(s)
                if "filters" in q and len(q["shape"]) < 9:
                    return json.dumps({"edges": [{"use": "road"}, {"use": "service_road"}]})
                return json.dumps(probe)

        by_door = R_encode([(40.0, -77.0), (40.01058, -77.0)])       # ends 9 m from the door
        on_street = R_encode([(40.0, -77.0), (40.0127, -77.0)])      # ends 245 m from the door
        self.assertEqual(1.4, R.every_request()["tie_check"]["door_walk_m_per_s"])
        # 236 m of walking saved at 1.4 m/s allow 168 s more than max_extra_s (90): 258 s in all
        for extra, end in ((250.0, "lot"), (265.0, "near")):
            def answer(req, extra=extra):
                if req["locations"][1].get("preferred_side") == "either":
                    return trip((1, 0.1), (5, 0.0), time=600.0 + extra, shape=by_door)
                return trip((1, 0.1), (5, 0.0), time=600.0, shape=on_street)
            r = R.run_preset(Probe(answer), [LOCS[0], {"lat": lat, "lon": lon, "_poi": True, "_door": door}], self.p)
            self.assertEqual(end, r["_ends"][1][R.END_KEY], extra)
        # a route that ends no nearer to the door gets max_extra_s alone
        def answer_far(req):
            if req["locations"][1].get("preferred_side") == "either":
                return trip((1, 0.1), (5, 0.0), time=700.0, shape=on_street)
            return trip((1, 0.1), (5, 0.0), time=600.0, shape=on_street)
        r = R.run_preset(Probe(answer_far), [LOCS[0], {"lat": lat, "lon": lon, "_poi": True, "_door": door}], self.p)
        self.assertEqual("near", r["_ends"][1][R.END_KEY])
        bad = dict(R.every_request(), tie_check=dict(R.every_request()["tie_check"], door_walk_m_per_s=0))
        self.assertTrue(any("door_walk_m_per_s" in e for e in R.validate_every_request(bad)))

    def test_validate_lot_door_uses(self):
        er = dict(R.every_request())
        bad = dict(er, lot=dict(er["lot"], door_uses=["driveway", "alley"]))
        self.assertTrue(any("lot door_uses" in e for e in R.validate_every_request(bad)))
        old = dict(er, lot={k: v for k, v in er["lot"].items() if k != "door_uses"})
        self.assertEqual([], R.validate_every_request(old), "a file without door_uses: the lot uses")

    def test_left_out_rings_on_lanes_and_alleys_and_every_request_leaves_them_out(self):
        rule, lat, lon = R.every_request()["left_out"], 42.0, -80.0
        self.assertEqual(["drive_through", "alley"], rule["uses"])
        resp = self._lot_answer(lat, lon, [("drive_through", "service_other", "", 15), ("alley", "service_other", "", 40),
                                           ("driveway", "service_other", "", 25), ("road", "primary", "Peach Street", 60)])
        rings = R.left_out_rings([resp], lat, lon, rule)
        self.assertEqual(2, len(rings), "the lane and the alley, not the driveway or the street")
        for ring, north in zip(rings, (15, 40)):
            self.assertEqual(5, len(ring))
            self.assertEqual(ring[0], ring[-1])
            mid = (sum(p[1] for p in ring[:4]) / 4, sum(p[0] for p in ring[:4]) / 4)
            self.assertAlmostEqual(north, R.haversine_m((lat, lon), mid), delta=1.0, msg="the middle of the edge, nearest first")
            side = R.haversine_m((ring[0][1], ring[0][0]), (ring[1][1], ring[1][0]))
            self.assertAlmostEqual(2 * rule["box_half_m"], side, delta=0.5)
        close = self._lot_answer(lat, lon, [("drive_through", "service_other", "", 15), ("driveway", "service_other", "", 17)])
        self.assertEqual([], R.left_out_rings([close], lat, lon, rule), "a box that would touch another edge is not made")
        pf = R.load_preset_file()
        req = R.request([LOCS[0], dict(LOCS[1], **{R.AVOID_KEY: rings})], R.compose("fastest", (), pf))
        self.assertEqual(rings, req["exclude_polygons"])
        self.assertNotIn(R.AVOID_KEY, req["locations"][1])
        many = [[[float(k), 0.0]] * 5 for k in range(30)]
        req = R.request([LOCS[0], dict(LOCS[1], **{R.AVOID_KEY: many})], R.compose("fastest", (), pf))
        self.assertEqual(rule["max_rings"], len(req["exclude_polygons"]), "at most 100 vertices (the app's engine)")

    def test_no_route_with_the_lanes_left_out_asks_once_more_without_them(self):
        def answer(req):
            return NO_PATH if "exclude_polygons" in req else trip((1, 0.1), (5, 0.0))
        a = FakeActor(answer)
        ring = [[-77.0, 40.0], [-77.0, 40.0001], [-76.9999, 40.0001], [-76.9999, 40.0], [-77.0, 40.0]]
        r = R.route(a, R.request([LOCS[0], dict(LOCS[1], **{R.AVOID_KEY: [ring]})], R.compose("fastest", (), R.load_preset_file())))
        self.assertNotIn("error", r)
        self.assertEqual(2, len(a.requests))
        self.assertNotIn("exclude_polygons", r["_request"])

    def test_validate_lot(self):
        er = dict(R.every_request())
        self.assertEqual([], R.validate_every_request(er))
        bad = dict(er, lot=dict(er["lot"], uses=["driveway", "alley"]))
        self.assertTrue(any("lot uses" in e for e in R.validate_every_request(bad)))
        bad = dict(er, lot=dict(er["lot"], within_m=0))
        self.assertTrue(any("lot within_m" in e for e in R.validate_every_request(bad)))


def R_encode(pts):
    """Polyline6 of [(lat, lon)] (for the test answers)."""
    out, plat, plon = [], 0, 0
    for la, lo in pts:
        ila, ilo = round(la * 1e6), round(lo * 1e6)
        for v in (ila - plat, ilo - plon):
            v = ~(v << 1) if v < 0 else v << 1
            while v >= 0x20:
                out.append(chr((0x20 | (v & 0x1f)) + 63)); v >>= 5
            out.append(chr(v + 63))
        plat, plon = ila, ilo
    return "".join(out)


class Entrance(unittest.TestCase):
    """Item 35 (presets.json every_request.entrance): the way into a lot. A stop at a store 400 m east of
    a road that runs north; the route comes up the road, turns in and drives 100 m of lot lanes."""

    RULE = {"tail_m": 800, "lot_uses": ["service_road", "parking_aisle", "driveway"], "into_m": 12, "box_half_m": 6,
            "min_gain_s": 10, "within_cost": 1.1, "same_end_m": 5}
    # the route's shape: 300 m of road north, then east into the lot (two lanes of 60 m and 40 m)
    PTS = [(40.0, -77.0), (40.0027, -77.0), (40.0027, -76.9993), (40.0027, -76.99883)]

    def trace(self, uses, indexes=((0, 1), (1, 2), (2, 3)), with_index=True):
        edges = [dict({"use": u}, **({"begin_shape_index": i0, "end_shape_index": i1} if with_index else {}))
                 for u, (i0, i1) in zip(uses, indexes)]

        class A:
            asked = []

            def trace_attributes(inner, s):
                inner.asked.append(json.loads(s))
                return json.dumps({"edges": edges, "shape": R_encode(self.PTS)})
        return A()

    def route(self):
        return {"trip": {"legs": [{"shape": R_encode(self.PTS)}]}}

    def test_the_ring_lies_just_inside_the_lot(self):
        a = self.trace(("road", "service_road", "parking_aisle"))
        ring = R.entrance_ring(a, self.route(), 0, self.RULE)
        q = a.asked[0]
        self.assertEqual("edge_walk", q["shape_match"])
        self.assertEqual(["edge.use", "edge.begin_shape_index", "edge.end_shape_index", "shape"], q["filters"]["attributes"])
        self.assertEqual(5, len(ring))
        self.assertEqual(ring[0], ring[-1])
        lons, lats = [c[0] for c in ring], [c[1] for c in ring]
        mid = ((min(lats) + max(lats)) / 2, (min(lons) + max(lons)) / 2)
        # 12 m east of where the route leaves the road, on the lane
        self.assertAlmostEqual(12.0, R.haversine_m(mid, self.PTS[1]), delta=0.3)
        self.assertAlmostEqual(40.0027, mid[0], places=5)
        # a square of 12 m, so 6 m of lane stay between it and the road
        self.assertAlmostEqual(12.0, R.haversine_m((min(lats), mid[1]), (max(lats), mid[1])), delta=0.3)
        self.assertGreater(min(lons), self.PTS[1][1])
        for c in ring:
            self.assertEqual(c, [round(c[0], 6), round(c[1], 6)], "whole micro-degrees")

    def test_no_ring_without_a_way_in_to_close(self):
        r = self.route()
        self.assertIsNone(R.entrance_ring(self.trace(("road", "road", "road")), r, 0, self.RULE), "ends on a road")
        self.assertIsNone(R.entrance_ring(self.trace(("service_road", "parking_aisle", "parking_aisle")), r, 0, self.RULE), "all of it inside a lot")
        self.assertIsNone(R.entrance_ring(self.trace(("road", "service_road", "parking_aisle"), with_index=False), r, 0, self.RULE))
        short = dict(self.RULE, into_m=95)
        self.assertIsNone(R.entrance_ring(self.trace(("road", "service_road", "parking_aisle")), r, 0, short), "the square would not fit inside")

        class Broken:
            def trace_attributes(self, s):
                raise RuntimeError("no match")
        self.assertIsNone(R.entrance_ring(Broken(), r, 0, self.RULE))
        self.assertIsNone(R.entrance_ring(self.trace(("road", "service_road")), {"trip": {"legs": []}}, 0, self.RULE))

    def plan(self, other_time, other_cost, other_uturns=0, end="lot", old_ring=None):
        """decide_entrances with an engine whose route costs 374 and takes 211 s, and whose route with the
        entrance closed takes [other_time] s and costs [other_cost]."""
        trace = self.trace(("road", "service_road", "parking_aisle"))

        def answer(req):
            closed = "exclude_polygons" in req
            t = trip((1, 0.3), *([(13, 0.1)] * (other_uturns if closed else 0)), (4, 0.0), time=other_time if closed else 211.0, shape=R_encode(self.PTS))
            t["trip"]["summary"]["cost"] = other_cost if closed else 374.0
            return t

        class A(FakeActor):
            def trace_attributes(inner, s):
                return trace.trace_attributes(s)
        a = A(answer)
        stop = {"lat": 40.0027, "lon": -76.99883, "_end": end, "_fixed": True}
        if old_ring:
            stop["_entry"] = [old_ring]
        return a, R.decide_entrances(a, [LOCS[0], stop], R.decision_preset(R.compose("fastest")))

    def test_the_other_way_in_is_taken_when_faster_and_hardly_dearer(self):
        a, ends = self.plan(193.0, 389.0)
        ring = ends[1]["_entry"]
        self.assertEqual(1, len(ring))
        self.assertEqual([ring[0]], a.requests[1]["exclude_polygons"])
        self.assertNotIn("exclude_polygons", a.requests[0])
        self.assertEqual(2, len(a.requests))
        # every request of the plan then leaves that way in out, before the lanes left out for other reasons
        avoid = [[-77.1, 40.1], [-77.09, 40.1], [-77.09, 40.11], [-77.1, 40.11], [-77.1, 40.1]]
        req = R.request([LOCS[0], dict(ends[1], _avoid=[avoid])], R.compose("fastest"))
        self.assertEqual([ring[0], avoid], req["exclude_polygons"])
        self.assertNotIn("_entry", req["locations"][1])

    def test_the_first_way_in_stays_otherwise(self):
        for why, args in (("only 5 s faster", (206.0, 380.0)), ("slower", (230.0, 360.0)), ("much dearer", (190.0, 420.0)),
                          ("a U-turn more", (193.0, 389.0, 1))):
            _, ends = self.plan(*args)
            self.assertNotIn("_entry", ends[1], why)
        a, ends = self.plan(193.0, 389.0, end="street")
        self.assertNotIn("_entry", ends[1])
        self.assertEqual([], a.requests, "a stop that does not end in a lot asks nothing")

    def test_an_answer_found_without_the_exclusions_is_not_another_way_in(self):
        # the lot has one way in: with it closed there is no route, the engine is asked again without any
        # exclusion and answers with a quick way down the alley the plan leaves out (the review of 2026-11-21)
        trace = self.trace(("road", "service_road", "parking_aisle"))
        alley = [[-77.1, 40.1], [-77.09, 40.1], [-77.09, 40.11], [-77.1, 40.11], [-77.1, 40.1]]

        def answer(req):
            rings = req.get("exclude_polygons", [])
            if len(rings) > 1:
                return NO_PATH
            t = trip((1, 0.3), (4, 0.0), time=211.0 if rings else 150.0, shape=R_encode(self.PTS))
            t["trip"]["summary"]["cost"] = 374.0 if rings else 300.0
            return t

        class A(FakeActor):
            def trace_attributes(inner, s):
                return trace.trace_attributes(s)
        a = A(answer)
        stop = {"lat": 40.0027, "lon": -76.99883, "_end": "lot", "_fixed": True, "_avoid": [alley]}
        ends = R.decide_entrances(a, [LOCS[0], stop], R.decision_preset(R.compose("fastest")))
        self.assertEqual(3, len(a.requests), "the route, the route with the way in closed, and that one again with nothing left out")
        self.assertNotIn("exclude_polygons", a.requests[2])
        self.assertNotIn("_entry", ends[1], "150 s down the alley is no second way in")

    def test_a_route_that_ends_somewhere_else_is_not_another_way_in(self):
        trace = self.trace(("road", "service_road", "parking_aisle"))
        elsewhere = self.PTS[:-1] + [(40.0030, -76.99883)]   # 33 m north of where the first route ends

        def answer(req):
            closed = "exclude_polygons" in req
            t = trip((1, 0.3), (4, 0.0), time=193.0 if closed else 211.0, shape=R_encode(elsewhere if closed else self.PTS))
            t["trip"]["summary"]["cost"] = 389.0 if closed else 374.0
            return t

        class A(FakeActor):
            def trace_attributes(inner, s):
                return trace.trace_attributes(s)
        ends = R.decide_entrances(A(answer), [LOCS[0], {"lat": 40.0027, "lon": -76.99883, "_end": "lot", "_fixed": True}],
                                  R.decision_preset(R.compose("fastest")))
        self.assertNotIn("_entry", ends[1])

    def test_a_ring_of_an_earlier_plan_is_dropped_first(self):
        old = [[-77.2, 40.2], [-77.19, 40.2], [-77.19, 40.21], [-77.2, 40.21], [-77.2, 40.2]]
        a, ends = self.plan(230.0, 360.0, old_ring=old)
        self.assertNotIn("_entry", ends[1], "the way in closed for the plan before is open again")
        self.assertNotIn("exclude_polygons", a.requests[0], "and the first route of this plan may use it")

    def test_the_rule_is_in_the_presets_file(self):
        rule = R.every_request()["entrance"]
        self.assertEqual(self.RULE, rule)
        self.assertEqual([], R.validate_every_request(R.every_request()))


if __name__ == "__main__":
    unittest.main()
