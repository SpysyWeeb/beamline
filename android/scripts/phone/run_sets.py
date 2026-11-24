"""The measurement sets of report 11 (docs/experiments/11-phone-baseline.md), run through phone.py.

From the repo root, BEAMLINE_PHONE_SERIAL set, a test-extras build installed and Pennsylvania added:

    python android/scripts/phone/run_sets.py memory --reps 3 --tag v1
    python android/scripts/phone/run_sets.py matrix --tag v1                     # 3 frame rates x day/night x the views
    python android/scripts/phone/run_sets.py matrix --tag v2 --fps screen        # one frame rate only
    python android/scripts/phone/run_sets.py matrix --tag r1 --fps screen 60 --views pgh-z14 border-z9.5 roc-z14 --zoom-views border-z9.5
    python android/scripts/phone/run_sets.py shots --tag v1 --zooms 15 16 17     # Erie and Pittsburgh, day and night
    python android/scripts/phone/run_sets.py memstart --reps 6 --tag r1          # 8 s and 20 s after a fresh start only

Every phone.py call is printed before it runs and its output is appended to
data/phone/logs/<set>-<tag>.log; the results go to data/phone/results/*.jsonl (phone.py).
The renderer is whichever build is installed (each result records the renderer the app logged).
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
PHONE = [sys.executable, str(HERE / "phone.py")]
LOGS = REPO / "data" / "phone" / "logs"

# name, lat, lon, zoom, pan pattern, swipes (report 08's views; whole US added)
VIEWS = [
    ("pgh-z14", 40.44, -80.0, 14, "back-and-forth", 20),
    ("pgh-travel", 40.44, -80.0, 14, "travel", 30),
    ("erie-z16", 42.129, -80.085, 16, "back-and-forth", 20),
    ("border-z9.5", 42.0, -77.0, 9.5, "back-and-forth", 20),
    ("roc-z14", 43.15, -77.6, 14, "back-and-forth", 20),
    ("us-z2.3", 39.0, -96.5, 2.3, "back-and-forth", 20),
]
ZOOM_GESTURES = 8
SHOT_PLACES = [("erie", 42.129, -80.085), ("pgh", 40.4406, -79.9959)]


def call(log: Path, *args: str) -> int:
    line = " ".join(["phone.py", *args])
    stamp = datetime.now().strftime("%H:%M:%S")
    print(f"[{stamp}] {line}", flush=True)
    p = subprocess.run([*PHONE, *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    with open(log, "a", encoding="utf-8") as f:
        f.write(f"[{stamp}] {line}\n{p.stdout}{p.stderr}\n")
    out = (p.stdout + p.stderr).strip().splitlines()
    if out:
        print("   " + out[-1][:300], flush=True)
    return p.returncode


def view_args(v) -> list[str]:
    name, lat, lon, zoom, _, _ = v
    return ["--lat", str(lat), "--lon", str(lon), "--zoom", str(zoom), "--view", name]


def memory_set(a) -> None:
    log = LOGS / f"memory-{a.tag}.log"
    pgh = ["--lat", "40.44", "--lon", "-80.0", "--zoom", "14", "--view", "pgh-z14", "--tag", a.tag]
    for r in range(a.reps):
        call(log, "memory", *pgh, "--wait", "8", "--phase", "start+8s")
        call(log, "memory", "--view", "pgh-z14", "--tag", a.tag, "--idle", "12", "--phase", "start+20s")
        call(log, "pan", *pgh, "--swipes", "40", "--wait", "8")
        call(log, "memory", "--view", "pgh-z14", "--tag", a.tag, "--phase", "after 40 swipes")
        call(log, "memory", "--view", "pgh-z14", "--tag", a.tag, "--idle", "180", "--phase", "after pan + 180 s idle")
        call(log, "pan", *pgh[:-4], "--view", "pgh-travel", "--tag", a.tag, "--swipes", "60", "--pattern", "travel", "--wait", "8")
        call(log, "memory", "--view", "pgh-travel", "--tag", a.tag, "--phase", "after 60 travelling swipes")


def memstart_set(a) -> None:
    """Only the first two readings of memory_set (8 s and about 20 s after a fresh start), repeated:
    for checking how much they vary, e.g. right after an install against after many starts."""
    log = LOGS / f"memstart-{a.tag}.log"
    pgh = ["--lat", "40.44", "--lon", "-80.0", "--zoom", "14", "--view", "pgh-z14", "--tag", a.tag]
    for r in range(a.reps):
        call(log, "memory", *pgh, "--wait", "8", "--phase", "start+8s")
        call(log, "memory", "--view", "pgh-z14", "--tag", a.tag, "--idle", "12", "--phase", "start+20s")


def matrix(a) -> None:
    log = LOGS / f"matrix-{a.tag}.log"
    for fps in a.fps:
        for style in a.styles:
            if call(log, "settings", "--style", style, "--fps", fps) != 0:
                sys.exit("settings failed; stopping")
            for v in VIEWS:
                if a.views and v[0] not in a.views:
                    continue
                call(log, "pan", *view_args(v), "--tag", a.tag, "--pattern", v[4], "--swipes", str(v[5]))
                if v[4] != "travel" and not a.no_zoom and (not a.zoom_views or v[0] in a.zoom_views):
                    call(log, "pan", *view_args(v)[:-1], v[0] + "-zoom", "--tag", a.tag, "--pattern", "zoom",
                         "--swipes", str(ZOOM_GESTURES))


def shots(a) -> None:
    log = LOGS / f"shots-{a.tag}.log"
    out = REPO / "data" / "phone" / "screenshots" / "zoom-compare"
    for style in a.styles:
        call(log, "settings", "--style", style)
        for place, lat, lon in SHOT_PLACES:
            for z in a.zooms:
                path = out / f"{place}-z{z}-{style}-{a.tag}.png"
                call(log, "shot", str(path), "--lat", str(lat), "--lon", str(lon), "--zoom", str(z), "--restart", "--wait", "7")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("memory")
    m.add_argument("--reps", type=int, default=3)
    m.add_argument("--tag", default="v1")
    ms = sub.add_parser("memstart")
    ms.add_argument("--reps", type=int, default=5)
    ms.add_argument("--tag", default="v1")
    x = sub.add_parser("matrix")
    x.add_argument("--tag", default="v1")
    x.add_argument("--fps", nargs="+", default=["screen", "60", "30"], choices=["screen", "60", "30"])
    x.add_argument("--styles", nargs="+", default=["day", "night"], choices=["day", "night"])
    x.add_argument("--views", nargs="*", default=[])
    x.add_argument("--no-zoom", action="store_true")
    x.add_argument("--zoom-views", nargs="*", default=[], help="run the zoom gestures only at these views")
    s = sub.add_parser("shots")
    s.add_argument("--tag", default="v1")
    s.add_argument("--zooms", nargs="+", default=["15", "16", "17"])
    s.add_argument("--styles", nargs="+", default=["day", "night"], choices=["day", "night"])
    a = ap.parse_args()
    LOGS.mkdir(parents=True, exist_ok=True)
    t = time.time()
    {"memory": memory_set, "memstart": memstart_set, "matrix": matrix, "shots": shots}[a.cmd](a)
    print(f"done in {(time.time() - t) / 60:.1f} min")
    return 0


if __name__ == "__main__":
    sys.exit(main())
