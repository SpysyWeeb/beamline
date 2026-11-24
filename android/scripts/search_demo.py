"""Drive the search demo app (:demo:search) on a device or emulator with adb.

Usage (from the repo root; adb on PATH or in $ANDROID_HOME/platform-tools; with several
devices attached set ANDROID_SERIAL):

    python android/scripts/search_demo.py push --search-db <pa-search.sqlite> --addresses-db <pa-addresses-slim.sqlite>
    python android/scripts/search_demo.py shots --out data/search-demo/screenshots
    python android/scripts/search_demo.py bench --out data/search-demo/bench-device.json
    python android/scripts/search_demo.py dump --out data/search-demo/kotlin-search-device.json
    python android/scripts/search_demo.py meminfo --out data/search-demo/meminfo.txt

push     copies the two pack files to Android/data/io.github.spysyweeb.beamline.searchdemo/files/search/ (the app's own
         external files folder: adb can write there without any permission). Install the app first.
shots    for each query, starts the app with the query and position as extras (as if typed), waits
         for the app's log line that the results are shown, and saves a screenshot
         (<n>-<query>-<place>.png). The keyboard stays hidden, so the whole list is visible.
bench    starts the app in bench mode, waits for BENCH_DONE in the log (several minutes) and pulls
         bench-device.json (same protocol as the JVM: ./gradlew :core:search-bench:bench).
meminfo  dumpsys meminfo of the app after typing the screenshot queries (run after shots).
Standard library only.
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

APP = os.environ.get("BEAMLINE_SEARCHDEMO_ID", "io.github.spysyweeb.beamline.searchdemo")
ACTIVITY = f"{APP}/io.github.spysyweeb.beamline.searchdemo.MainActivity"
REMOTE = f"/sdcard/Android/data/{APP}/files/search"
SHOTS = [("wal", "erie"), ("rest stop", "erie"), ("sheetz", "erie"), ("4319 peach st", "erie"), ("erie city hall", "erie"),
         ("wal", "pittsburgh"), ("rest stop", "pittsburgh"), ("sheetz", "pittsburgh"), ("4319 peach st", "pittsburgh"),
         ("erie city hall", "pittsburgh")]


def adb_exe() -> str:
    exe = shutil.which("adb")
    if exe:
        return exe
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            cand = Path(os.environ[var]) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if cand.exists():
                return str(cand)
    sys.exit("adb not found")


def adb(*args: str, binary: bool = False, check: bool = True):
    p = subprocess.run([adb_exe(), *args], capture_output=True, text=not binary)
    if check and p.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {p.stderr if not binary else p.stderr.decode(errors='replace')}")
    return p.stdout


def wait_log(pattern: str, timeout_s: float) -> str:
    """Poll logcat (tag BeamlineSearch) until a line contains pattern; stop on an error line."""
    t_end = time.time() + timeout_s
    while time.time() < t_end:
        out = adb("logcat", "-d", "-s", "BeamlineSearch:I")
        for line in out.splitlines():
            if pattern in line:
                return line
            if " E BeamlineSearch" in line:
                sys.exit("the app reported an error:\n" + "\n".join(out.splitlines()[-15:]))
        time.sleep(1.0)
    sys.exit(f"timed out waiting for '{pattern}' in the log")


def start(*extras: str) -> None:
    adb("shell", "am", "start", "-n", ACTIVITY, *extras)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cmd", choices=["push", "shots", "bench", "dump", "meminfo"])
    ap.add_argument("--search-db")
    ap.add_argument("--addresses-db")
    ap.add_argument("--out")
    args = ap.parse_args()
    if args.cmd == "push":
        if not (args.search_db and args.addresses_db):
            sys.exit("push needs --search-db and --addresses-db")
        # Start the app once first, so that it creates its own external files folder: on a fresh
        # install, files adb pushes into a folder the app has not created are not seen by the app
        # ("database not found"; seen on the API 36 emulator).
        adb("shell", "am", "start", "-W", "-n", ACTIVITY)
        time.sleep(2)
        adb("shell", "am", "force-stop", APP)
        adb("shell", "mkdir", "-p", REMOTE)
        for src, name in ((args.search_db, "pa-search.sqlite"), (args.addresses_db, "pa-addresses-slim.sqlite")):
            t0 = time.time()
            adb("push", src, f"{REMOTE}/{name}")
            print(f"pushed {name} ({Path(src).stat().st_size / 1e6:.1f} MB) in {time.time() - t0:.1f} s")
        print(adb("shell", "ls", "-l", REMOTE))
        return 0
    out = Path(args.out or "data/search-demo")
    if args.cmd == "shots":
        out.mkdir(parents=True, exist_ok=True)
        adb("shell", "am", "force-stop", APP)
        adb("logcat", "-c")
        start()
        wait_log("opened in", 120)
        for i, (q, at) in enumerate(SHOTS, 1):
            adb("logcat", "-c")
            # 'am start' splits on spaces inside the device shell: quote the value for it
            start("--es", "q", "'" + q.replace("'", "'\\''") + "'", "--es", "at", at)
            line = wait_log(f"'{q}' near {at}:", 60)
            time.sleep(1.5)   # let the list settle and draw
            png = adb("exec-out", "screencap", "-p", binary=True)
            f = out / f"{i:02d}-{q.replace(' ', '_')}-{at}.png"
            f.write_bytes(png)
            print(f"{f}  ({line.split(': ', 1)[-1].strip()})")
        return 0
    if args.cmd == "bench":
        out.parent.mkdir(parents=True, exist_ok=True)
        adb("shell", "am", "force-stop", APP)
        adb("logcat", "-c")
        t0 = time.time()
        start("--ez", "bench", "true")
        line = wait_log("BENCH_DONE", 3600)
        print(f"{line.strip()} (wall {time.time() - t0:.0f} s)")
        target = out if out.suffix == ".json" else out / "bench-device.json"
        target.write_bytes(adb("exec-out", "cat", f"{REMOTE}/bench-device.json", binary=True))
        print(f"pulled {target}")
        adb("shell", "am", "force-stop", APP)
        return 0
    if args.cmd == "dump":
        adb("shell", "am", "force-stop", APP)
        adb("logcat", "-c")
        start("--ez", "dump", "true")
        line = wait_log("DUMP_DONE", 1800)
        print(line.strip())
        target = out if out.suffix == ".json" else out / "kotlin-search-device.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(adb("exec-out", "cat", f"{REMOTE}/kotlin-search-device.json", binary=True))
        print(f"pulled {target}; check it with conformance/compare.py --kotlin {target}")
        adb("shell", "am", "force-stop", APP)
        return 0
    if args.cmd == "meminfo":
        txt = adb("shell", "dumpsys", "meminfo", APP)
        target = out if out.suffix == ".txt" else out / "meminfo.txt"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(txt, encoding="utf-8")
        for line in txt.splitlines():
            if any(k in line for k in ("TOTAL PSS", "Native Heap", "Java Heap", "TOTAL RSS", "Code:", ".so mmap", "Other mmap")):
                print(line.rstrip())
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
