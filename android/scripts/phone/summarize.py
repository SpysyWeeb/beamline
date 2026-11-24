"""Tables for report 11 from data/phone/results/*.jsonl (written by phone.py).

    python android/scripts/phone/summarize.py pan [--tag v1] [--since 2026-11-15T21:00]
    python android/scripts/phone/summarize.py compact --tag v1 [--tags v1 r1 ...]
    python android/scripts/phone/summarize.py memory | startup | install

Each row groups the runs of one configuration and gives the median and the range (min-max) over
the runs. Pan columns:
  surface fps    frames the map's own surface presented per second while drawing (SurfaceFlinger
                 present times; intervals over 150 ms are left out and counted under "long")
  late           share of those intervals longer than 1.5 x the frame time the setting asks for
  late while moving   the same, leaving out the intervals that start within 0.45 s after a camera
                 stop (MapLibre's label fades); runs from after the review of report 11 only
  long > 150 ms  intervals over 150 ms, and in brackets how many of them came while the camera moved
                 (no "camera idle" in the 0.8 s before): those would be stalls, not pauses.
                 reanalyse_gaps.py does the same check for the runs saved before the review
  worst ML cpu   the slowest MapLibre frame in the run's windows (since the review the window
                 holding the view's load is closed before the run: phone.py pan --settle)
  ML fps         the app's own one-second windows (MapLibre frames per second, pauses included:
                 the number report 08 gives for the emulator)
  ML cpu         MapLibre's CPU time per frame (encoding + rendering), median of window averages
  display        share of SurfaceFlinger's vsync-period samples during the run at 8.33 ms (120 Hz)
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from pathlib import Path

RESULTS = Path(__file__).resolve().parents[3] / "data" / "phone" / "results"
VIEW_ORDER = ["pgh-z14", "pgh-z14-zoom", "pgh-travel", "erie-z16", "erie-z16-zoom", "border-z9.5", "border-z9.5-zoom",
              "roc-z14", "roc-z14-zoom", "us-z2.3", "us-z2.3-zoom"]


def load(name: str, a) -> list[dict]:
    p = RESULTS / f"{name}.jsonl"
    if not p.exists():
        return []
    rows = [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]
    if a.tag:
        rows = [r for r in rows if r.get("tag") == a.tag]
    if a.tags:
        rows = [r for r in rows if r.get("tag") in a.tags]
    if a.since:
        rows = [r for r in rows if r.get("time", "") >= a.since]
    return rows


def rng(vals, fmt="{:.0f}") -> str:
    vals = [v for v in vals if v is not None]
    if not vals:
        return "-"
    med = statistics.median(vals)
    if len(vals) == 1:
        return fmt.format(med)
    return f"{fmt.format(med)} ({fmt.format(min(vals))}-{fmt.format(max(vals))})"


def pan(a) -> None:
    rows = [r for r in load("pan", a) if r.get("view") and r.get("view") != "trial"]
    groups: dict[tuple, list[dict]] = {}
    for r in rows:
        fps = {120: "screen", 60: "60", 30: "30"}.get(r.get("max_fps"), str(r.get("max_fps")))
        k = (r.get("tag"), r.get("renderer"), r.get("style"), fps, r.get("view"), r.get("swipes"))
        groups.setdefault(k, []).append(r)

    def order(k):
        tag, ren, sty, fps, view, _ = k
        return (tag or "", ren or "", ["screen", "60", "30"].index(fps) if fps in ("screen", "60", "30") else 9,
                sty or "", VIEW_ORDER.index(view) if view in VIEW_ORDER else 99)

    print("| tag | renderer | frame rate | map | view | gestures | runs | surface fps | late | late while moving | "
          "long > 150 ms (moving) | ML fps | ML cpu ms | worst ML cpu ms | display at 120 Hz |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for k in sorted(groups, key=order):
        g = groups[k]
        sf = [r["surface"].get("fps_while_drawing") for r in g]
        late = [100 * r["surface"]["late_share"] if "late_share" in r["surface"] else None for r in g]
        lmov = [100 * r["surface"]["late_share_moving"] if "late_share_moving" in r["surface"] else None for r in g]
        lis = [r["surface"].get("long_intervals") for r in g if r["surface"].get("long_intervals")]
        if lis:
            lng = f"{sum(x['n'] for x in lis)} ({sum(x.get('while_moving', 0) for x in lis)})"
        else:  # runs from before the review: only the count of pauses was kept
            lng = f"{sum(r['surface'].get('pauses', 0) for r in g)} (not checked)"
        ml = [r.get("maplibre_fps_median") for r in g]
        cpu = [r.get("maplibre_cpu_ms_median") for r in g]
        worst = [r.get("maplibre_cpu_ms_worst") for r in g]
        disp = []
        for r in g:
            per = r.get("display_period_samples", {})
            tot = sum(per.values())
            disp.append(100 * per.get("8.33 ms", 0) / tot if tot else None)
        tag, ren, sty, fps, view, sw = k
        print(f"| {tag} | {ren} | {fps} | {sty} | {view} | {sw} | {len(g)} | {rng(sf, '{:.1f}')} | {rng(late, '{:.1f}')} % | "
              f"{rng(lmov, '{:.1f}')} % | {lng} | {rng(ml, '{:.1f}')} | {rng(cpu, '{:.1f}')} | {rng(worst, '{:.0f}')} | {rng(disp)} % |")


def memory(a) -> None:
    rows = load("memory", a)
    groups: dict[tuple, list[dict]] = {}
    for r in rows:
        groups.setdefault((r.get("tag"), r.get("phase")), []).append(r)
    print("| tag | when | runs | TOTAL PSS MB | Graphics MB | PSS without graphics MB | Native heap MB | TOTAL RSS MB |")
    print("|---|---|---|---|---|---|---|---|")
    for (tag, phase), g in groups.items():
        kb = [r["kB"] for r in g]
        mb = lambda key: [k.get(key, 0) / 1024 for k in kb]
        nog = [(k.get("TOTAL PSS", 0) - k.get("Graphics", 0)) / 1024 for k in kb]
        print(f"| {tag} | {phase} | {len(g)} | {rng(mb('TOTAL PSS'))} | {rng(mb('Graphics'))} | {rng(nog)} | "
              f"{rng(mb('Native Heap'))} | {rng(mb('TOTAL RSS'))} |")


def startup(a) -> None:
    rows = load("startup", a)
    print("| tag | renderer | map | view | runs | activity drawn ms | first map frame ms | fully rendered ms |")
    print("|---|---|---|---|---|---|---|---|")
    for r in rows:
        runs = r.get("runs", [])
        cols = [rng([x[i] for x in runs]) for i in range(3)]
        print(f"| {r.get('tag')} | {r.get('renderer')} | {r.get('style')} | {r.get('view')} | {len(runs)} | {cols[0]} | {cols[1]} | {cols[2]} |")


def install(a) -> None:
    for r in load("install", a):
        print({k: r.get(k) for k in ("time", "archive", "variant", "renderer", "first_bytes_to_done_s", "picked_to_end_s", "ok")})


def compact(a) -> None:
    """One table per frame-rate setting: views down, renderer x map style across; each cell
    'surface fps / late %' (medians over the runs, matrix gesture counts only)."""
    rows = [r for r in load("pan", a) if r.get("view") in VIEW_ORDER and (not a.views or r.get("view") in a.views)]
    std = {"pgh-z14": 20, "pgh-travel": 30}
    rows = [r for r in rows if r["view"].endswith("-zoom") or r.get("swipes") == std.get(r["view"], 20)]
    cols = [(ren, sty) for ren in ("vulkan", "opengl") for sty in ("day", "night")]
    names = {"pgh-z14": "Pittsburgh z14, pan", "pgh-z14-zoom": "Pittsburgh z14, zoom", "pgh-travel": "Pittsburgh, travelling pan",
             "erie-z16": "Erie z16, pan", "erie-z16-zoom": "Erie z16, zoom", "border-z9.5": "PA/NY border z9.5, pan",
             "border-z9.5-zoom": "PA/NY border z9.5, zoom", "roc-z14": "Rochester NY z14, pan", "roc-z14-zoom": "Rochester NY z14, zoom",
             "us-z2.3": "whole US z2.3, pan", "us-z2.3-zoom": "whole US z2.3, zoom"}
    for fps, label in ((120, "Screen rate (120 Hz)"), (60, "60"), (30, "30")):
        sub = [r for r in rows if r.get("max_fps") == fps]
        if not sub:
            continue
        print(f"\n**{label}** (surface frames per second while drawing / late frames, median of the runs; runs in brackets)\n")
        print("| View | " + " | ".join(f"{ren.replace('opengl', 'OpenGL').replace('vulkan', 'Vulkan')} {sty}" for ren, sty in cols) + " |")
        print("|---|" + "---|" * len(cols))
        for v in VIEW_ORDER:
            cells = []
            for ren, sty in cols:
                g = [r for r in sub if r["view"] == v and r.get("renderer") == ren and r.get("style") == sty]
                if not g:
                    cells.append("-")
                    continue
                fs = [r["surface"]["fps_while_drawing"] for r in g]
                ls = [100 * r["surface"]["late_share"] for r in g]
                f, l = statistics.median(fs), statistics.median(ls)
                if a.spread and len(g) > 1:
                    cells.append(f"{f:.0f} ({min(fs):.0f}-{max(fs):.0f}) / {l:.1f} % ({min(ls):.1f}-{max(ls):.1f}), n={len(g)}")
                else:
                    cells.append(f"{f:.0f} / {l:.1f} % ({len(g)})")
            if any(c != "-" for c in cells):
                print(f"| {names.get(v, v)} | " + " | ".join(cells) + " |")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", choices=("pan", "compact", "memory", "startup", "install"))
    ap.add_argument("--tag")
    ap.add_argument("--tags", nargs="+", help="several tags together (e.g. a set and its repeats)")
    ap.add_argument("--since")
    ap.add_argument("--views", nargs="+", help="compact: only these views")
    ap.add_argument("--spread", action="store_true", help="compact: give the range over the runs too")
    a = ap.parse_args()
    {"pan": pan, "compact": compact, "memory": memory, "startup": startup, "install": install}[a.what](a)
    return 0


if __name__ == "__main__":
    sys.exit(main())
