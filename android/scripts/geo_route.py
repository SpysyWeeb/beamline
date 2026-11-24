"""Drive the emulator's GPS along a GPX track with `adb emu geo fix` (emulator only).

Usage (from the repo root; ANDROID_SERIAL=emulator-5554 when a phone is attached too):

    python android/scripts/geo_route.py data/android/routes/peach-st.gpx               # whole track, real time
    python android/scripts/geo_route.py data/android/routes/peach-st.gpx --start 0 --count 60 --every 4 --interval 1
    python android/scripts/geo_route.py --fix 42.1292 -80.0851                          # one fix

--every N sends every Nth point; --interval sets the seconds between fixes (default: the track's
own times). Each fix is printed with the time it was sent.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from device_ui import adb_path  # noqa: E402


def points(gpx: Path) -> list[tuple[float, float, float | None]]:
    text = gpx.read_text(encoding="utf-8")
    out = []
    for m in re.finditer(r'<trkpt\s+lat="([-\d.]+)"\s+lon="([-\d.]+)"\s*>(.*?)</trkpt>', text, re.S):
        t = re.search(r"<time>([^<]+)</time>", m.group(3))
        ts = datetime.fromisoformat(t.group(1).replace("Z", "+00:00")).timestamp() if t else None
        out.append((float(m.group(1)), float(m.group(2)), ts))
    return out


def fix(adb: str, lat: float, lon: float) -> None:
    # The console takes longitude first. Altitude 200 m, 10 satellites.
    p = subprocess.run([adb, "emu", "geo", "fix", f"{lon:.6f}", f"{lat:.6f}", "200", "10"], capture_output=True, text=True)
    if p.returncode != 0 or "KO" in p.stdout:
        sys.exit(f"geo fix failed: {p.stdout.strip()} {p.stderr.strip()}")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("gpx", nargs="?", type=Path)
    ap.add_argument("--fix", nargs=2, type=float, metavar=("LAT", "LON"))
    ap.add_argument("--start", type=int, default=0)
    ap.add_argument("--count", type=int)
    ap.add_argument("--every", type=int, default=1)
    ap.add_argument("--interval", type=float, help="seconds between fixes (default: the track's times)")
    a = ap.parse_args()
    adb = adb_path()
    if a.fix:
        fix(adb, *a.fix)
        print(f"fix {a.fix[0]:.6f},{a.fix[1]:.6f}")
        return 0
    if not a.gpx:
        ap.error("give a GPX file or --fix")
    pts = points(a.gpx)[a.start:]
    if a.count:
        pts = pts[: a.count]
    pts = pts[:: a.every]
    t0 = time.time()
    for i, (lat, lon, ts) in enumerate(pts):
        if i > 0:
            gap = a.interval if a.interval is not None else (ts - pts[i - 1][2] if ts and pts[i - 1][2] else 1.0)
            target = t0 + gap
            time.sleep(max(0.0, target - time.time()))
        t0 = time.time()
        fix(adb, lat, lon)
        print(f"{time.strftime('%H:%M:%S')} fix {i + 1}/{len(pts)} {lat:.6f},{lon:.6f}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
