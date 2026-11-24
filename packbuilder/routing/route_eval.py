"""Routing experiments on the built graph: border crossings, presets, alternates,
multi-stop and stop types, matrix timing, latency and memory.

usage (repo root, routing venv):
    python packbuilder/routing/route_eval.py crossings   # step 2: route across the PA/OH line
    python packbuilder/routing/route_eval.py presets     # step 4: presets x trips table
    python packbuilder/routing/route_eval.py tune        # step 4: parameter sweeps used to pick preset values
    python packbuilder/routing/route_eval.py extras      # step 4: alternates, 4-stop trip, stop types
    python packbuilder/routing/route_eval.py matrix      # step 5: 1x40 and 40x1 sources_to_targets
    python packbuilder/routing/route_eval.py latency     # step 6: cold/warm latency and process memory
Results go to data/routing/results/*.json and *.md.
By default the combined PA+OH graph (data/routing/build/valhalla_tiles) is used.
"""
from __future__ import annotations

import argparse
import json
import math
import statistics
import subprocess
import sys
import time
from pathlib import Path

import psutil

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common as C  # noqa: E402
import packs  # noqa: E402
import route_lib as R  # noqa: E402


def combined_config(**over) -> dict:
    cfg = C.load_json(C.BUILD_CONFIG)
    cfg["mjolnir"]["tile_extract"] = ""
    cfg["mjolnir"]["tile_dir"] = str(C.TILE_DIR)
    cfg["mjolnir"]["traffic_extract"] = ""
    cfg["service_limits"]["allow_hard_exclusions"] = True
    cfg["logging"]["type"] = ""
    for k, v in over.items():
        node = cfg
        parts = k.split(".")
        for p in parts[:-1]:
            node = node[p]
        node[parts[-1]] = v
    return cfg


def places():
    return R.load_trips()["places"]


def strip(s: dict) -> dict:
    return {k: v for k, v in s.items() if k not in ("shape", "street_names")}


# ---------------------------------------------------------------- step 2
def run_crossings(a=None, cfg=None) -> list[dict]:
    actor = a or R.actor(cfg or combined_config())
    rows = []
    for c in R.load_trips()["border_crossings"]:
        for direction, (p0, p1) in (("OH->PA", (c["oh"], c["pa"])), ("PA->OH", (c["pa"], c["oh"]))):
            # heading = direction of travel, so a point on a divided highway snaps to the
            # carriageway that goes the right way (else the route has to turn around at an exit)
            hd = round(R.bearing_deg(p0, p1))
            sf = {"min_road_class": c.get("min_road_class", "service_other")}
            # heading only helps if loki may look past the nearest carriageway: radius 60 m
            ll = [R.loc(p, search_filter=sf, heading=hd, heading_tolerance=75, radius=60) for p in (p0, p1)]
            s = R.summarize(R.route(actor, R.request(ll, admin_crossings=True)))
            row = {"id": c["id"], "road": c["road"], "direction": direction}
            if s["ok"]:
                straight = R.haversine_m((ll[0]["lat"], ll[0]["lon"]), (ll[1]["lat"], ll[1]["lon"]))
                states = [ad.get("state_code") for ad in s.get("admins", [])]
                row.update(ok=True, length_km=s["length_km"], straight_km=round(straight / 1000, 3),
                           detour_ratio=round(s["length_km"] * 1000 / straight, 2),
                           on_expected_road=R.names_match(c["expect_names"], s["street_names"]),
                           admin_states=states, crosses_state_line=len(s.get("admin_crossings", [])) > 0,
                           street_names=s["street_names"][:8])
            else:
                row.update(ok=False, error=s["error"])
            rows.append(row)
    return rows


def cmd_crossings(args) -> None:
    rows = run_crossings()
    C.save_json(C.RESULTS / "crossings.json", rows)
    lines = ["| crossing | dir | ok | route km | straight km | ratio | on road | states | state-line crossing |",
             "|---|---|---|---|---|---|---|---|---|"]
    for r in rows:
        if r["ok"]:
            lines.append(f"| {r['id']} ({r['road']}) | {r['direction']} | yes | {r['length_km']:.2f} | {r['straight_km']:.2f} | "
                         f"{r['detour_ratio']} | {r['on_expected_road']} | {','.join(map(str, r['admin_states']))} | {r['crosses_state_line']} |")
        else:
            lines.append(f"| {r['id']} ({r['road']}) | {r['direction']} | NO: {r['error']} | | | | | | |")
    (C.RESULTS / "crossings.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


# ---------------------------------------------------------------- step 4: presets
def run_presets(actor, presets: dict, trips: list[dict]) -> dict:
    P = places()
    out = {}
    for t in trips:
        locs = [R.loc(P[t["from"]]), R.loc(P[t["to"]])]
        res = {}
        for pid, p in presets.items():
            t0 = time.perf_counter()
            s = R.summarize(R.run_preset(actor, locs, p))
            s["latency_ms"] = round((time.perf_counter() - t0) * 1000, 1)
            res[pid] = s
        base = res.get("fastest")
        for pid, s in res.items():
            if s["ok"] and base and base["ok"]:
                s["overlap_with_fastest"] = round(min(R.overlap(s["shape"], base["shape"]),
                                                      R.overlap(base["shape"], s["shape"])), 3)
        out[t["id"]] = res
    return out


def preset_table(results: dict, trips: list[dict], presets: dict) -> str:
    kinds = {t["id"]: t["kind"] for t in trips}
    lines = ["| trip | kind | " + " | ".join(presets) + " |", "|---|---|" + "---|" * len(presets)]
    for tid, res in results.items():
        cells = []
        for pid in presets:
            s = res[pid]
            if not s["ok"]:
                cells.append("error")
                continue
            same = "" if pid == "fastest" else (" =" if s["overlap_with_fastest"] >= 0.97 else f" ({s['overlap_with_fastest']:.0%})")
            cells.append(f"{s['time_s'] / 60:.0f} min / {s['length_km']:.1f} km / {s['maneuvers']} m{same}")
        lines.append(f"| {tid} | {kinds[tid]} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def preset_summary(results: dict, presets: dict) -> list[dict]:
    rows = []
    for pid in presets:
        dt, dd, dm, diff, toll, hw, n = [], [], [], 0, [], [], 0
        for tid, res in results.items():
            b, s = res["fastest"], res[pid]
            if not (b["ok"] and s["ok"]):
                continue
            n += 1
            dt.append(s["time_s"] / b["time_s"] - 1)
            dd.append(s["length_km"] / b["length_km"] - 1)
            dm.append(s["maneuvers"] - b["maneuvers"])
            diff += s["overlap_with_fastest"] < 0.97
            toll.append(s["toll_km"])
            hw.append(s["highway_km"])
        rows.append({"preset": pid, "trips": n, "routes_differing_from_fastest": diff,
                     "mean_time_change_pct": round(100 * statistics.mean(dt), 1) if dt else None,
                     "mean_distance_change_pct": round(100 * statistics.mean(dd), 1) if dd else None,
                     "mean_maneuver_change": round(statistics.mean(dm), 1) if dm else None,
                     "total_toll_km": round(sum(toll), 1), "total_highway_km": round(sum(hw), 1)})
    return rows


def summary_table(rows: list[dict]) -> str:
    lines = ["| preset | routes differing from Fastest | mean time vs Fastest | mean distance vs Fastest | mean maneuvers vs Fastest | toll km (all trips) | highway km (all trips) |",
             "|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['preset']} | {r['routes_differing_from_fastest']}/{r['trips']} | {r['mean_time_change_pct']:+.1f}% | "
                     f"{r['mean_distance_change_pct']:+.1f}% | {r['mean_maneuver_change']:+.1f} | {r['total_toll_km']} | {r['total_highway_km']} |")
    return "\n".join(lines)


def cmd_presets(args) -> None:
    presets = R.load_presets()
    trips = R.load_trips()["trips"]
    actor = R.actor(combined_config())
    res = run_presets(actor, presets, trips)
    C.save_json(C.RESULTS / "presets.json", {t: {p: strip(s) for p, s in r.items()} for t, r in res.items()})
    summ = preset_summary(res, presets)
    md = ("Cell: time / distance / maneuvers (turns etc., excluding depart and arrive). "
          "'=' means the same path as Fastest (>= 97% overlap), otherwise the overlap share.\n\n"
          + preset_table(res, trips, presets) + "\n\n" + summary_table(summ) + "\n")
    (C.RESULTS / "presets.md").write_text(md)
    print(md)


def cmd_tune(args) -> None:
    """Sweeps for the presets whose values are a judgement call."""
    trips = R.load_trips()["trips"]
    actor = R.actor(combined_config())
    sweeps = {
        "simpler": [{"maneuver_penalty": v} for v in (5, 30, 60, 120, 300)],
        "eco": [{"top_speed": ts, "use_distance": ud} for ts in (140, 113, 105, 97) for ud in (0.0, 0.3, 0.5)],
        "shortest": [{"shortest": True}, {"use_distance": 1.0}, {"use_distance": 0.7},
                     {"shortest": True, "disable_hierarchy_pruning": True}],
        "avoid_highways": [{"use_highways": v} for v in (0.0, 0.1, 0.25)] + [{"exclude_highways": True}],
        "avoid_tolls": [{"use_tolls": v} for v in (0.0, 0.2)] + [{"use_tolls": 0.0, "toll_booth_penalty": 600},
                                                                  {"exclude_tolls": True}],
    }
    # candidate-pool presets (see route_lib.run_preset)
    select_variants = {
        "simpler_select": [
            {"select": "min_maneuvers", "max_time_increase": x, "alternates": 2,
             "candidates": [{}, {"maneuver_penalty": 60}, {"maneuver_penalty": 300}]} for x in (0.05, 0.10, 0.15)],
        "eco_select": [
            {"select": "min_distance", "max_time_increase": x, "alternates": 2,
             "candidates": [{}, {"use_distance": 0.5}, {"use_distance": 0.7}]} for x in (0.03, 0.05, 0.10)],
    }
    if args and getattr(args, "only_select", False):
        sweeps = {}
    presets = {f"base": {"costing": "auto", "costing_options": {}}}
    for name, variants in sweeps.items():
        for i, v in enumerate(variants):
            presets[f"{name}#{i}"] = {"costing": "auto", "costing_options": v}
    for name, variants in select_variants.items():
        for i, v in enumerate(variants):
            presets[f"{name}#{i}"] = {"costing": "auto", **v}
    # run with "fastest" key expected by helpers
    presets_run = {"fastest": presets.pop("base"), **presets}
    res = run_presets(actor, presets_run, trips)
    summ = preset_summary(res, presets_run)
    for r in summ:
        name = r["preset"]
        p = presets_run[name]
        r["options"] = p["costing_options"] if "select" not in p else {k: v for k, v in p.items() if k != "costing"}
        lat = [res[t][name]["latency_ms"] for t in res if res[t][name]["ok"]]
        r["mean_latency_ms"] = round(statistics.mean(lat), 1) if lat else None
    C.save_json(C.RESULTS / ("tune_select.json" if getattr(args, "only_select", False) else "tune.json"),
                {"summary": summ, "per_trip": {t: {p: strip(s) for p, s in r.items()} for t, r in res.items()}})
    lines = ["| variant | options | differ | time | distance | maneuvers | toll km | highway km | mean latency ms |",
             "|---|---|---|---|---|---|---|---|---|"]
    for r in summ:
        lines.append(f"| {r['preset']} | `{json.dumps(r['options'])}` | {r['routes_differing_from_fastest']}/{r['trips']} | "
                     f"{r['mean_time_change_pct']:+.1f}% | {r['mean_distance_change_pct']:+.1f}% | {r['mean_maneuver_change']:+.1f} | "
                     f"{r['total_toll_km']} | {r['total_highway_km']} | {r['mean_latency_ms']} |")
    name = "tune_select" if getattr(args, "only_select", False) else "tune"
    (C.RESULTS / f"{name}.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


# ---------------------------------------------------------------- step 4: extras
def run_alternates(actor, trip_ids, n=2) -> list[dict]:
    P = places()
    trips = {t["id"]: t for t in R.load_trips()["trips"]}
    rows = []
    for tid in trip_ids:
        t = trips[tid]
        resp = R.route(actor, R.request([R.loc(P[t["from"]]), R.loc(P[t["to"]])], alternates=n))
        if "error" in resp:
            rows.append({"trip": tid, "error": resp["error"]})
            continue
        main = R.summarize(resp)
        alts = [R.summarize(x) for x in resp.get("alternates", [])]
        rows.append({"trip": tid, "requested": n, "returned": len(alts),
                     "main": {"time_min": round(main["time_s"] / 60, 1), "km": main["length_km"]},
                     "alternates": [{"time_min": round(a["time_s"] / 60, 1), "km": a["length_km"],
                                     "overlap_with_main": round(R.overlap(a["shape"], main["shape"]), 2)} for a in alts]})
    return rows


def maneuver_list(resp) -> list[str]:
    out = []
    for i, leg in enumerate(resp["trip"]["legs"]):
        for m in leg["maneuvers"]:
            out.append(f"leg{i}:type{m['type']}:{m['instruction']}")
    return out


def run_stop_types(actor) -> dict:
    P = places()
    st = R.load_trips()["stop_type_trip"]
    out = {}
    for typ in ("break", "through", "via", "break_through"):
        locs = [R.loc(P[st["from"]]), R.loc(P[st["via"]], type=typ), R.loc(P[st["to"]])]
        resp = R.route(actor, R.request(locs))
        if "error" in resp:
            out[typ] = {"error": resp["error"]}
            continue
        s = R.summarize(resp)
        mans = maneuver_list(resp)
        out[typ] = {"legs": s["legs"], "maneuvers_total": s["maneuvers_total"], "time_min": round(s["time_s"] / 60, 1),
                    "km": s["length_km"],
                    "arrive_or_depart_mid_route": sum(1 for m in mans[1:-1] if any(f":type{k}:" in m for k in (1, 2, 3, 4, 5, 6))),
                    "uturns": sum(1 for m in mans if any(f":type{k}:" in m for k in (12, 13))),
                    "maneuvers": mans}
    return out


def run_multistop(actor) -> dict:
    P = places()
    ms = R.load_trips()["multi_stop"]
    locs = [R.loc(P[s]) for s in ms["stops"]]
    t0 = time.perf_counter()
    resp = R.route(actor, R.request(locs))
    ms_time = (time.perf_counter() - t0) * 1000
    if "error" in resp:
        return {"error": resp["error"]}
    s = R.summarize(resp)
    legs = [{"km": round(l["summary"]["length"], 1), "min": round(l["summary"]["time"] / 60, 1),
             "maneuvers": len(l["maneuvers"])} for l in resp["trip"]["legs"]]
    # optimized_route (reorders the middle stops) for comparison
    opt = json.loads(actor.optimized_route(json.dumps(R.request(locs))))
    return {"stops": ms["stops"], "legs": legs, "total_km": s["length_km"], "total_min": round(s["time_s"] / 60, 1),
            "latency_ms": round(ms_time, 1),
            "optimized_route_order": [l.get("original_index") for l in opt["trip"]["locations"]],
            "optimized_route_km": round(opt["trip"]["summary"]["length"], 1)}


def cmd_extras(args) -> None:
    actor = R.actor(combined_config())
    trips = R.load_trips()
    out = {"alternates": run_alternates(actor, trips["alternates_trips"], 2),
           "alternates_ask_3": run_alternates(actor, trips["alternates_trips"][:2], 3),
           "multi_stop": run_multistop(actor),
           "stop_types": run_stop_types(actor)}
    C.save_json(C.RESULTS / "extras.json", out)
    print(json.dumps({k: v for k, v in out.items() if k != "stop_types"}, indent=1))
    for typ, v in out["stop_types"].items():
        print(typ, {k: v[k] for k in v if k != "maneuvers"} if "error" not in v else v)


# ---------------------------------------------------------------- step 5: matrix
def corridor_targets(shape, n, offsets, start_frac=0.0, end_frac=1.0):
    """n points beside the route, evenly spaced by distance, alternating left/right offsets."""
    cum = [0.0]
    for a, b in zip(shape, shape[1:]):
        cum.append(cum[-1] + R.haversine_m(a, b))
    total = cum[-1]
    pts = []
    j = 0
    for i in range(n):
        d = total * (start_frac + (end_frac - start_frac) * (i + 0.5) / n)
        while cum[j + 1] < d:
            j += 1
        a, b = shape[j], shape[j + 1]
        t = (d - cum[j]) / max(cum[j + 1] - cum[j], 1e-6)
        lat, lon = a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])
        # unit normal in metres
        dy, dx = (b[0] - a[0]) * 111320, (b[1] - a[1]) * 111320 * math.cos(math.radians(lat))
        L = math.hypot(dx, dy) or 1
        off = offsets[i % len(offsets)] * (1 if i % 2 == 0 else -1)
        pts.append({"lat": round(lat + (dx / L) * off / 111320, 6),
                    "lon": round(lon - (dy / L) * off / (111320 * math.cos(math.radians(lat))), 6)})
    return pts


def time_matrix(actor, sources, targets, reps=3):
    req = {"sources": sources, "targets": targets, "costing": "auto", "units": "kilometers"}
    times, resp = [], None
    for _ in range(reps):
        t0 = time.perf_counter()
        try:
            resp = json.loads(actor.matrix(json.dumps(req)))
        except RuntimeError as e:
            return {"error": str(e)}
        times.append((time.perf_counter() - t0) * 1000)
    cells = [c for row in resp["sources_to_targets"] for c in row]
    nulls = sum(1 for c in cells if c.get("time") is None or c.get("distance") is None)
    return {"first_ms": round(times[0], 1), "warm_ms_median": round(statistics.median(times[1:]), 1),
            "cells": len(cells), "nulls": nulls, "algorithm": resp.get("algorithm"),
            "warnings": resp.get("warnings")}


def cmd_matrix(args) -> None:
    P = places()
    trips = {t["id"]: t for t in R.load_trips()["trips"]}
    mt = R.load_trips()["matrix_trip"]
    t = trips[mt["id"]]
    out = {"trip": mt["id"]}
    variants = {
        "default_config": combined_config(),  # select_optimal -> costmatrix for auto in 3.9.1
        # CostMatrix stops each tree at max_matrix_distance / 2 of *path* distance (costmatrix.cc);
        # raising the limit and allowing the second pass removes the nulls
        "costmatrix_tuned": combined_config(**{"thor.source_to_target_algorithm": "costmatrix",
                                               "service_limits.auto.max_matrix_distance": 1000000.0,
                                               "thor.costmatrix.allow_second_pass": True}),
        "timedistancematrix": combined_config(**{"thor.source_to_target_algorithm": "timedistancematrix"}),
    }
    if (C.DEVICE / "valhalla.json").exists():
        # what the device actually runs: installed packs + device_config.json settings
        variants["device_config"] = packs.engine_config(C.DEVICE)
    for algo, cfg in variants.items():
        actor = R.actor(cfg)
        base = R.summarize(R.route(actor, R.request([R.loc(P[t["from"]]), R.loc(P[t["to"]])])))
        out["route_km"] = base["length_km"]
        res = {}
        for label, (f0, f1) in {"whole_route": (0.0, 1.0), "next_100km": (0.0, min(1.0, 100 / base["length_km"]))}.items():
            targets = corridor_targets(base["shape"], mt["targets"], mt["corridor_offset_m"], f0, f1)
            src = [R.loc(P[t["from"]])]
            dst = [R.loc(P[t["to"]])]
            res[label] = {"1x40": time_matrix(actor, src, targets), "40x1": time_matrix(actor, targets, dst)}
        out[algo] = res
        del actor
    C.save_json(C.RESULTS / "matrix.json", out)
    print(json.dumps(out, indent=1))


# ---------------------------------------------------------------- step 6: latency + memory
LATENCY_CHILD = r'''
import json, sys, time, gc
sys.path.insert(0, sys.argv[1])
import psutil, valhalla
import route_lib as R
cfg = json.loads(open(sys.argv[2]).read())
reqs = json.loads(open(sys.argv[3]).read())
p = psutil.Process()
out = {"rss_before_mb": round(p.memory_info().rss / 2**20, 1)}
t0 = time.perf_counter(); a = valhalla.Actor(sys.argv[2]); out["actor_init_ms"] = round((time.perf_counter() - t0) * 1000, 1)
out["rss_after_init_mb"] = round(p.memory_info().rss / 2**20, 1)
res = {}
for name, req in reqs.items():
    ts = []
    for i in range(6):
        t0 = time.perf_counter(); a.route(json.dumps(req)); ts.append((time.perf_counter() - t0) * 1000)
    res[name] = {"cold_ms": round(ts[0], 1), "warm_ms_median": round(sorted(ts[1:])[2], 1)}
out["routes"] = res
mi = p.memory_info()
out["rss_after_routes_mb"] = round(mi.rss / 2**20, 1)
out["peak_wset_mb"] = round(getattr(mi, "peak_wset", 0) / 2**20, 1)
out["private_mb"] = round(getattr(mi, "private", 0) / 2**20, 1)
print(json.dumps(out))
'''


def cmd_latency(args) -> None:
    P = places()
    trips = {t["id"]: t for t in R.load_trips()["trips"]}
    reqs = {}
    for tid in ("U1", "U3", "R1", "I1", "T1", "B3"):
        t = trips[tid]
        reqs[f"{tid}"] = R.request([R.loc(P[t["from"]]), R.loc(P[t["to"]])])
    rdir = C.RESULTS / "latency"
    rdir.mkdir(parents=True, exist_ok=True)
    (rdir / "reqs.json").write_text(json.dumps(reqs))
    (rdir / "child.py").write_text(LATENCY_CHILD)
    out = {}
    layouts = {"combined_tile_dir": combined_config()}
    if (C.DEVICE / "valhalla.json").exists():
        layouts["device_" + packs.device_info(C.DEVICE)["layout"]] = packs.engine_config(C.DEVICE)
    for name, cfg in layouts.items():
        cpath = rdir / f"{name}.json"
        C.save_json(cpath, cfg)
        runs = []
        for rep in range(3):  # three fresh processes: process-cold each time, OS file cache warm after the first
            r = subprocess.run([sys.executable, str(rdir / "child.py"), str(C.HERE), str(cpath), str(rdir / "reqs.json")],
                               capture_output=True, text=True)
            if r.returncode != 0:
                runs.append({"error": r.stderr[-800:]})
            else:
                runs.append(json.loads(r.stdout.strip().splitlines()[-1]))
        out[name] = runs
    C.save_json(C.RESULTS / "latency.json", out)
    print(json.dumps(out, indent=1))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["crossings", "presets", "tune", "extras", "matrix", "latency"])
    ap.add_argument("--only-select", action="store_true", help="tune: only the candidate-pool variants")
    a = ap.parse_args()
    {"crossings": cmd_crossings, "presets": cmd_presets, "tune": cmd_tune, "extras": cmd_extras,
     "matrix": cmd_matrix, "latency": cmd_latency}[a.command](a)


if __name__ == "__main__":
    main()
