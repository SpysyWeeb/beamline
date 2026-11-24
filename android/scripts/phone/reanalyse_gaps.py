"""Re-read the saved gesture runs of report 11 (data/phone/raw/pan-*.json) and check what the
150 ms rule of phone.py leaves out: are the long present intervals pauses after the camera
stopped, or stalls while the map moved? Needs no phone.

    python android/scripts/phone/reanalyse_gaps.py                 # tags v1 and v2 (the report's runs)
    python android/scripts/phone/reanalyse_gaps.py --tags r1 --jsonl

Runs saved before the review of report 11 have the app's log in wall-clock time only. Their
clock is lined up with SurfaceFlinger's (CLOCK_MONOTONIC) by the app's first "frames:" window,
which spans the wait at the view and is logged at the first frame of the first gesture. The check
of that alignment: every later "frames:" line must fall next to a present (printed as a spread).
Runs saved later carry the camera stops on SurfaceFlinger's clock (phone.py, logcat -v monotonic).

Per run it gives what phone.py now gives (phone.frame_stats): the long intervals and how many
were while the map moved, the late share while moving (leaving out the label-fade frames just
after a camera stop), and the worst MapLibre CPU frame after the window that holds the load.
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import phone  # noqa: E402

RAW = phone.OUT / "raw"
WIN = re.compile(r"frames: n=(\d+) in (\d+) ms, cpu avg=([\d.]+) ms max=([\d.]+) ms")


def analyse(d: dict) -> dict | None:
    r = d["result"]
    p = sorted(d["presents"])
    if len(p) < 10:
        return None
    wins = [(phone.log_time(l), int(m.group(2)), float(m.group(4))) for l in d["log"] if (m := WIN.search(l))]
    target = 1000 / r["max_fps"]
    check = []
    if d.get("camera_stops_mono") is not None:
        stops, first = d["camera_stops_mono"], d.get("first_gesture_ns")
        later = [w[2] for w in wins[1:]]
    else:
        iv = [(b - a) / 1e6 for a, b in zip(p, p[1:])]
        g0 = next((i + 1 for i, v in enumerate(iv) if v > 3000), 0)
        w0 = next((i for i, w in enumerate(wins) if w[1] > 3000), None)
        if w0 is None:
            return {"aligned": False}
        off = wins[w0][0] - p[g0] / 1e9
        stops = [phone.log_time(l) - off for l in d["log"] if "camera idle" in l]
        first = p[g0]
        later = [w[2] for w in wins[w0 + 1:]]
        for w in wins[w0 + 1:]:
            t = w[0] - off
            near = min((x / 1e9 - t for x in p if abs(x / 1e9 - t) < 0.1), key=abs, default=None)
            if near is not None:
                check.append(near * 1000)
    s = phone.frame_stats(p, target, stops, first)
    return {"aligned": True, "surface": s, "worst_cpu_after_load_window": max(later, default=None), "check_ms": check}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tags", nargs="+", default=["v1", "v2"])
    ap.add_argument("--jsonl", action="store_true", help="also write data/phone/results/gaps-reanalysis.jsonl")
    a = ap.parse_args()
    groups: dict[tuple, list[dict]] = {}
    checks, lags, unaligned, rows_out = [], [], 0, []
    for f in sorted(RAW.glob("pan-*.json")):
        d = json.loads(f.read_text(encoding="utf-8"))
        r = d["result"]
        if r.get("tag") not in a.tags:
            continue
        res = analyse(d)
        if res is None:
            continue
        if not res["aligned"]:
            unaligned += 1
            continue
        checks += res["check_ms"]
        li = res["surface"]["long_intervals"]
        lags += li.get("ms_after_last_stop", [])
        k = (r["tag"], r["renderer"], r["max_fps"], r["style"], r["view"])
        groups.setdefault(k, []).append(res)
        rows_out.append({"file": f.name, "tag": r["tag"], "renderer": r["renderer"], "max_fps": r["max_fps"], "style": r["style"],
                         "view": r["view"], "late_share_old": r["surface"].get("late_share"),
                         "late_share_moving": res["surface"].get("late_share_moving"), "long": li["n"],
                         "long_while_moving": li.get("while_moving"), "worst_cpu_after_load_window": res["worst_cpu_after_load_window"]})
    n_runs = sum(len(g) for g in groups.values())
    print(f"runs: {n_runs} aligned, {unaligned} not (no 'frames:' window spanning the wait)")
    if checks:
        c = sorted(checks)
        print(f"alignment check, present minus 'frames:' line (ms): n={len(c)}, median {statistics.median(c):.1f}, "
              f"5-95 % {c[len(c) // 20]:.1f} to {c[len(c) * 19 // 20]:.1f}")
    tot_long = sum(g["surface"]["long_intervals"]["n"] for v in groups.values() for g in v)
    tot_moving = sum(g["surface"]["long_intervals"].get("while_moving", 0) for v in groups.values() for g in v)
    print(f"intervals over 150 ms: {tot_long}; of them while the map moved (no camera stop in the 0.8 s before): {tot_moving}")
    if lags:
        lg = sorted(lags)
        print(f"start of a long interval after the last camera stop (ms): median {statistics.median(lg):.0f}, "
              f"90 % {lg[len(lg) * 9 // 10]}, 99 % {lg[len(lg) * 99 // 100]}, max {lg[-1]}")
    print("\n| tag | renderer | rate | map | view | runs | late % (report) | late % while moving | long > 150 ms | of them moving | worst CPU ms after load window |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for k in sorted(groups, key=lambda k: (k[0], k[1], -k[2], k[3], k[4])):
        g = groups[k]
        old = [100 * x["surface"]["late_share"] for x in g]
        mov = [100 * x["surface"]["late_share_moving"] for x in g if "late_share_moving" in x["surface"]]
        lng = [x["surface"]["long_intervals"]["n"] for x in g]
        mvg = [x["surface"]["long_intervals"].get("while_moving", 0) for x in g]
        cpu = [x["worst_cpu_after_load_window"] for x in g if x["worst_cpu_after_load_window"] is not None]
        print(f"| {k[0]} | {k[1]} | {k[2]} | {k[3]} | {k[4]} | {len(g)} | {statistics.median(old):.1f} | "
              f"{statistics.median(mov):.1f} | {sum(lng)} | {sum(mvg)} | {max(cpu) if cpu else '-'} |")
    if a.jsonl:
        out = phone.OUT / "results" / "gaps-reanalysis.jsonl"
        with open(out, "w", encoding="utf-8") as fh:
            for row in rows_out:
                fh.write(json.dumps(row) + "\n")
        print(f"\nwrote {out.relative_to(phone.REPO).as_posix()} ({len(rows_out)} runs)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
