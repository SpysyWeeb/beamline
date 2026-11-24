"""Measurements for the Android app on a connected device or emulator (adb).

Usage (from the repo root, app installed and packs added), with the views of report 08:

    python android/scripts/measure.py startup --runs 7 --warmup 1 --lat 42.121 --lon -80.082 --zoom 13   # Erie
    python android/scripts/measure.py memory --lat 40.44 --lon -80.0 --zoom 14                          # Pittsburgh
    python android/scripts/measure.py pan --swipes 40 --lat 40.44 --lon -80.0 --zoom 14
    python android/scripts/measure.py pan --swipes 40 --pattern travel --lat 40.44 --lon -80.0 --zoom 14
    python android/scripts/measure.py screenshot data/android/screenshots/erie-z13.png [--lat .. --lon .. --zoom ..] [--wait 8]

--lat/--lon/--zoom restart the app at that view first (pan and memory then wait --wait seconds,
default 8, for the tiles to load); without them the app is measured as and where it is (e.g. memory
right after a pan run). The camera extras are read
only by a debug build or a release built with -Pbeamline.testExtras=true (android/README.md).
Every command prints the app's last frame-rate request (the Frame rate setting) and, on Windows,
the host CPU load, because emulator numbers depend heavily on both.

The application id comes from android/gradle.properties; set BEAMLINE_APPLICATION_ID to measure
a second install built with -Pbeamline.applicationId=... . With several devices, set ANDROID_SERIAL.

startup:    force-stops the app, starts it with `am start -W` and reads
            - TotalTime from am (time to the activity's first drawn frame, the map not yet drawn)
            - the app's own log lines "first map frame N ms" and "first fully rendered map frame
              N ms" (N = SystemClock.uptimeMillis() - Process.getStartUptimeMillis(), i.e. counted
              from the moment the process was forked), written by app/.../map/PackMap.kt.
            The first --warmup runs (default 1) are printed but not counted: right after an
            install or a pack push the file cache is cold and that run is several times slower.
pan:        swipes across the screen and collects the app's per-second "frames:" log lines
            (number of map frames drawn and the CPU time MapLibre reports per frame).
memory:     `dumpsys meminfo` of the app (PSS total, native heap, graphics).
screenshot: `screencap` to a PNG on this machine. Refuses to save it if another app is in front
            (e.g. a second install on a shared emulator), before or after the capture.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

ANDROID_DIR = Path(__file__).resolve().parents[1]
TAG = "BeamlineMap"


def app_id() -> str:
    if os.environ.get("BEAMLINE_APPLICATION_ID"):
        return os.environ["BEAMLINE_APPLICATION_ID"]
    for line in (ANDROID_DIR / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("beamline.applicationId="):
            return line.split("=", 1)[1].strip()
    sys.exit("beamline.applicationId missing from android/gradle.properties")


APP = app_id()
ACTIVITY = f"{APP}/io.github.spysyweeb.beamline.app.MainActivity"
ADB = shutil.which("adb") or "adb"


def adb(*args: str, binary: bool = False):
    p = subprocess.run([ADB, *args], capture_output=True, text=not binary)
    return p.stdout


def start_args(a) -> list[str]:
    extra = []
    if a.lat is not None and a.lon is not None:
        extra = ["--ef", "lat", str(a.lat), "--ef", "lon", str(a.lon), "--ef", "zoom", str(a.zoom)]
    return extra


def host_load() -> str:
    """The host's CPU load right now (Windows: Win32_Processor.LoadPercentage; else the 1-min load average)."""
    try:
        if os.name == "nt":
            p = subprocess.run(["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_Processor).LoadPercentage"],
                               capture_output=True, text=True, timeout=30)
            return f"host CPU {p.stdout.strip()} %"
        return f"host load average {os.getloadavg()[0]:.1f}"
    except Exception as e:  # informational only
        return f"host load unknown ({e})"


def context_line() -> str:
    """The app's last frame-rate request (it logs one at start and on every change) and the host load."""
    req = [l for l in adb("logcat", "-d", "-s", f"{TAG}:I").splitlines() if "frame rate requested" in l]
    fr = req[-1].split("frame rate requested: ", 1)[1].strip() if req else "frame-rate request not in the log"
    return f"settings: {fr}; {host_load()}"


def go_to_view(a) -> None:
    """Restart the app at --lat/--lon/--zoom (if given) and wait for the tiles to load."""
    if a.lat is None or a.lon is None:
        return
    adb("shell", "am", "force-stop", APP)
    time.sleep(1.5)
    adb("shell", "am", "start", "-W", "-n", ACTIVITY, *start_args(a))
    time.sleep(a.wait)


def wait_for_log(pattern: str, timeout: float) -> str | None:
    end = time.time() + timeout
    rx = re.compile(pattern)
    while time.time() < end:
        out = adb("logcat", "-d", "-s", f"{TAG}:I")
        m = rx.search(out)
        if m:
            return m.group(1)
        time.sleep(0.5)
    return None


def startup(a) -> None:
    rows = []
    for i in range(a.warmup + a.runs):
        adb("shell", "am", "force-stop", APP)
        time.sleep(1.5)
        adb("logcat", "-c")
        out = adb("shell", "am", "start", "-W", "-n", ACTIVITY, *start_args(a))
        total = re.search(r"TotalTime: (\d+)", out)
        first = wait_for_log(r"first map frame (\d+) ms", 30)
        full = wait_for_log(r"first fully rendered map frame (\d+) ms", 60)
        row = (int(total.group(1)) if total else None, int(first) if first else None, int(full) if full else None)
        warm = i < a.warmup
        if not warm:
            rows.append(row)
        label = f"warm-up {i + 1} (not counted)" if warm else f"run {i + 1 - a.warmup}"
        print(f"{label}: activity drawn {row[0]} ms, first map frame {row[1]} ms, fully rendered {row[2]} ms"
              f" ({host_load()})", flush=True)
        time.sleep(1)
    print(context_line())
    for k, name in enumerate(("activity drawn (am TotalTime)", "first map frame", "first fully rendered map frame")):
        vals = [r[k] for r in rows if r[k] is not None]
        if vals:
            print(f"{name}: median {statistics.median(vals):.0f} ms, min {min(vals)}, max {max(vals)} (n={len(vals)})")


def pan(a) -> None:
    go_to_view(a)
    print(context_line())
    size = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    w, h = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)
    adb("logcat", "-c")
    if a.pattern == "travel":
        # always drag right-to-left and a little upwards: the camera keeps moving east-south-east,
        # so new tiles are loaded all the time (about 0.6 screen widths per swipe)
        moves = [(0.8, 0.45, 0.2, 0.35)]
    else:
        # back and forth: the swipes cancel out and mostly redraw tiles already loaded
        moves = [(0.2, 0.7, 0.8, 0.3), (0.8, 0.3, 0.2, 0.7), (0.5, 0.8, 0.5, 0.2), (0.5, 0.2, 0.5, 0.8),
                 (0.8, 0.5, 0.2, 0.5), (0.2, 0.5, 0.8, 0.5)]
    t0 = time.time()
    for i in range(a.swipes):
        x1, y1, x2, y2 = moves[i % len(moves)]
        adb("shell", "input", "swipe", str(int(x1 * w)), str(int(y1 * h)), str(int(x2 * w)), str(int(y2 * h)), "600")
        time.sleep(0.3)
    secs = time.time() - t0
    time.sleep(2)
    lines = [l for l in adb("logcat", "-d", "-s", f"{TAG}:I").splitlines() if "frames:" in l]
    fps, avg, mx = [], [], []
    for l in lines:
        m = re.search(r"n=(\d+) in (\d+) ms, cpu avg=([\d.]+) ms max=([\d.]+) ms", l)
        if m:
            fps.append(int(m.group(1)) * 1000 / int(m.group(2)))
            avg.append(float(m.group(3)))
            mx.append(float(m.group(4)))
    print(f"{a.swipes} swipes in {secs:.0f} s, {len(lines)} one-second windows logged")
    if fps:
        print(f"map frames per second while drawing: median {statistics.median(fps):.1f}, min {min(fps):.1f}, max {max(fps):.1f}")
        print(f"MapLibre CPU time per frame (encoding+rendering): median of window averages {statistics.median(avg):.2f} ms, "
              f"worst frame {max(mx):.1f} ms")


def memory(a) -> None:
    go_to_view(a)
    print(context_line())
    out = adb("shell", "dumpsys", "meminfo", APP)
    keep = ("Native Heap", "Dalvik Heap", "Graphics", "GL mtrack", "EGL mtrack", "TOTAL PSS", "TOTAL RSS", "TOTAL:")
    for line in out.splitlines():
        if any(k in line for k in keep):
            print(line.rstrip())


def top_activity() -> str:
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", adb("shell", "dumpsys", "activity", "activities"))
    return m.group(1) if m else "?"


def screenshot(a) -> None:
    if a.lat is not None:
        adb("shell", "am", "start", "-n", ACTIVITY, *start_args(a))
    time.sleep(a.wait)
    before = top_activity()
    png = adb("exec-out", "screencap", "-p", binary=True)
    after = top_activity()
    if not (before.startswith(APP + "/") and after.startswith(APP + "/")):
        sys.exit(f"not saved: the app in front was {before} / {after}, not {APP}")
    out = Path(a.path)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(png)
    print(f"{out} ({len(png)} bytes)")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("startup", "pan", "memory", "screenshot"):
        p = sub.add_parser(name)
        p.add_argument("--lat", type=float)
        p.add_argument("--lon", type=float)
        p.add_argument("--zoom", type=float, default=12)
        if name == "startup":
            p.add_argument("--runs", type=int, default=5)
            p.add_argument("--warmup", type=int, default=1, help="cold-cache runs printed but not counted")
        if name == "pan":
            p.add_argument("--swipes", type=int, default=20)
            p.add_argument("--pattern", choices=("back-and-forth", "travel"), default="back-and-forth")
        if name in ("pan", "memory"):
            p.add_argument("--wait", type=float, default=8, help="seconds after going to --lat/--lon/--zoom")
        if name == "screenshot":
            p.add_argument("path")
            p.add_argument("--wait", type=float, default=6)
    a = ap.parse_args()
    {"startup": startup, "pan": pan, "memory": memory, "screenshot": screenshot}[a.cmd](a)
    return 0


if __name__ == "__main__":
    sys.exit(main())
