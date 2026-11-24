"""Run the routing demo and its on-device tests on an emulator or phone (adb). Standard library only.

    python android/routing/scripts/device.py install
    python android/routing/scripts/device.py push --tiles data/routing-packs-test/pa-oh/routing.tar \
        --pa-only data/routing-packs-test/pa-only/routing.tar --map-pack data/android/packs/us-pa
    python android/routing/scripts/device.py test [--class io.github.spysyweeb.beamline.routing.demo.DeviceRoutingTest]
    python android/routing/scripts/device.py measure --runs 3 [--drop-caches] [--release]
    python android/routing/scripts/device.py screenshot data/routing-device/demo.png [--from LAT,LON --to LAT,LON]
    python android/routing/scripts/device.py meminfo

The APKs come from `./gradlew :routing:demo:assembleDebug :routing:demo:assembleDebugAndroidTest`
(the Ferrostar check, :routing:ferrostarcheck, was deleted on 2026-11-17). Tests are run with `am instrument` and the apps are
never uninstalled, so the pushed tiles (1.8 GB) stay in the demo app's files folder between runs
(Gradle's connectedAndroidTest would uninstall the app and delete them). Results the tests write
are pulled into data/routing-device/results/ (or --out). adb is taken from PATH or $ANDROID_HOME.
--drop-caches needs a device where `adb root` works (the AOSP emulator images).

The script runs only with ANDROID_SERIAL set and talks only to that device (`adb -s`): another
device attached to the same PC is never addressed. In a parallel round (docs/architecture.md 5.3)
source `tools/round/env.sh <key>` first (it sets ANDROID_SERIAL to the track's own emulator), build
with `tools/round/gradle.sh`, and pass `--build-root data/gradle/<key>/build` so the APKs are found
there, and `--out data/r3-<key>/device`, for example (track N):

    . tools/round/env.sh n
    python android/routing/scripts/device.py --build-root data/gradle/n/build --out data/r3-n/device install --only demo demo-test
    python android/routing/scripts/device.py --build-root data/gradle/n/build --out data/r3-n/device test --class io.github.spysyweeb.beamline.routing.demo.DeviceRoutingTest
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
APP = "io.github.spysyweeb.beamline.routingdemo"
RUNNER = "androidx.test.runner.AndroidJUnitRunner"
FILES = f"/sdcard/Android/data/{APP}/files"
OUT = REPO / "data" / "routing-device"
APKS = {
    "demo": REPO / "android/routing/demo/build/outputs/apk/debug/demo-debug.apk",
    "demo-test": REPO / "android/routing/demo/build/outputs/apk/androidTest/debug/demo-debug-androidTest.apk",
}


def use_build_root(root: Path) -> None:
    """APKs of a build with -Pbeamline.buildRoot=<root> (docs/architecture.md 5.3): <root>/<project path>/outputs."""
    for name, apk in list(APKS.items()):
        rel = apk.relative_to(REPO / "android").as_posix().replace("/build/", "/", 1)
        APKS[name] = root / rel


def adb_exe() -> str:
    found = shutil.which("adb")
    if found:
        return found
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if home:
        p = Path(home) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if p.exists():
            return str(p)
    sys.exit("adb not found: put platform-tools on PATH or set ANDROID_HOME")


SERIAL: list[str] = []


def adb(*args: str, check: bool = True, capture: bool = False) -> str:
    cmd = [adb_exe(), *SERIAL, *args]
    r = subprocess.run(cmd, capture_output=capture, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        sys.exit(f"failed: {' '.join(cmd)}\n{r.stdout or ''}{r.stderr or ''}")
    return r.stdout or ""


def install(a) -> None:
    if not APKS["demo"].exists():  # built with -Pbeamline.abiSplits: take the universal APK
        APKS["demo"] = APKS["demo"].with_name("demo-universal-debug.apk")
    for name, apk in APKS.items():
        if a.only and name not in a.only:
            continue
        if not apk.exists():
            sys.exit(f"{apk} missing: build it first (see the docstring)")
        print(f"install {name}")
        adb("install", "-r", "-t", str(apk))


def push(a) -> None:
    adb("shell", "am", "start", "-W", "-n", f"{APP}/io.github.spysyweeb.beamline.routing.demo.DemoActivity")  # creates the files folder
    time.sleep(2)
    adb("shell", "am", "force-stop", APP)
    if a.tiles:
        adb("shell", "mkdir", "-p", f"{FILES}/routing")
        t0 = time.time()
        adb("push", str(a.tiles), f"{FILES}/routing/routing.tar")
        print(f"tiles pushed in {time.time() - t0:.0f} s")
    if a.pa_only:
        adb("shell", "mkdir", "-p", f"{FILES}/routing-pa-only")
        adb("push", str(a.pa_only), f"{FILES}/routing-pa-only/routing.tar")
    # trips for the measurement runs of the release build (DemoMeasure reads files/testdata)
    td = REPO / "android" / "routing" / "testdata"
    adb("shell", "mkdir", "-p", f"{FILES}/testdata/reference")
    adb("push", str(td / "trips.json"), f"{FILES}/testdata/trips.json")
    adb("push", str(td / "reference" / "trips.json"), f"{FILES}/testdata/reference/trips.json")
    if a.routing_pack:  # a pack folder from packbuilder/routing/build.py, for PackInstallOnDeviceTest
        rp = Path(a.routing_pack)
        state = rp.name
        adb("shell", "mkdir", "-p", f"{FILES}/packs-routing/{state}")
        for f in rp.iterdir():
            adb("push", str(f), f"{FILES}/packs-routing/{state}/{f.name}")
    if a.map_pack:
        pack = Path(a.map_pack)
        adb("shell", "mkdir", "-p", f"{FILES}/packs")
        adb("push", str(pack), f"{FILES}/packs/")
    print(adb("shell", "ls", "-l", f"{FILES}/routing", f"{FILES}/routing-pa-only", f"{FILES}/packs", capture=True, check=False))


def instrument(target: str, extras: list[str]) -> str:
    out = adb("shell", "am", "instrument", "-w", "-r", *extras, target, capture=True, check=False)
    return out


def pull_results() -> None:
    (OUT / "results").mkdir(parents=True, exist_ok=True)
    adb("pull", f"{FILES}/results/.", str(OUT / "results"), check=False)


def summarize(out: str) -> bool:
    ok = "OK (" in out and "FAILURES" not in out
    for line in out.splitlines():
        if line.startswith(("INSTRUMENTATION_STATUS: test=", "INSTRUMENTATION_STATUS_CODE: -2", "OK (", "FAILURES", "Tests run",
                            "INSTRUMENTATION_FAILED", "INSTRUMENTATION_CODE")) or "Exception" in line or "Error" in line:
            print(line)
    return ok


def test(a) -> None:
    extras = ["-e", "class", a.cls] if a.cls else ["-e", "package", "io.github.spysyweeb.beamline.routing.demo"]
    for kv in a.arg or []:
        k, v = kv.split("=", 1)
        extras += ["-e", k, v]
    t0 = time.time()
    out = instrument(f"{APP}.test/{RUNNER}", extras)
    (OUT / "logs").mkdir(parents=True, exist_ok=True)
    log = OUT / "logs" / f"instrument-{int(t0)}.txt"
    log.write_text(out, encoding="utf-8")
    ok = summarize(out)
    pull_results()
    print(f"{'passed' if ok else 'FAILED'} in {time.time() - t0:.0f} s; full output {log.relative_to(REPO).as_posix()}")
    if not ok:
        sys.exit(1)


def measure(a) -> None:
    """Each run in a fresh process. Debug: the instrumented MeasureTest. --release: the installed
    release build of the demo, started with the extra `measure` (install it first with
    adb install -r .../demo-<abi>-release.apk; reinstall the debug APK for the tests afterwards)."""
    for first in ("U1", "I1"):
        for i in range(a.runs):
            adb("shell", "am", "force-stop", APP)
            if a.drop_caches:
                adb("root", check=False)
                time.sleep(2)
                adb("shell", "sync; echo 3 > /proc/sys/vm/drop_caches")
            if a.release:
                adb("logcat", "-c")
                adb("shell", "am", "start", "-n", f"{APP}/io.github.spysyweeb.beamline.routing.demo.DemoActivity", "--es", "measure", first)
                t0 = time.time()
                log = ""
                while time.time() - t0 < 300 and "MEASURE DONE" not in log:
                    time.sleep(2)
                    log = adb("logcat", "-d", "-s", "RoutingDemo:I", capture=True, check=False)
                done = [l for l in log.splitlines() if "MEASURE DONE" in l]
                print(f"run {first} {i + 1}: {done[-1] if done else 'timed out'}")
            else:
                out = instrument(f"{APP}.test/{RUNNER}", ["-e", "class", "io.github.spysyweeb.beamline.routing.demo.MeasureTest", "-e", "first", first])
                print(f"run {first} {i + 1}: {'ok' if 'OK (' in out else out[-800:]}")
    pull_results()


def screenshot(a) -> None:
    extras = []
    if a.from_ and a.to:
        (fl, fo), (tl, to) = (map(float, a.from_.split(",")), map(float, a.to.split(",")))
        extras = ["--ed", "from_lat", str(fl), "--ed", "from_lon", str(fo), "--ed", "to_lat", str(tl), "--ed", "to_lon", str(to)]
    adb("shell", "am", "force-stop", APP)
    adb("shell", "am", "start", "-W", "-n", f"{APP}/io.github.spysyweeb.beamline.routing.demo.DemoActivity", *extras)
    time.sleep(a.wait)
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    png = subprocess.run([adb_exe(), *SERIAL, "exec-out", "screencap", "-p"], capture_output=True).stdout
    out.write_bytes(png)
    print(f"wrote {out} ({len(png)} bytes)")
    print(adb("logcat", "-d", "-s", "RoutingDemo:I", capture=True, check=False)[-1500:])


def meminfo(_a) -> None:
    print(adb("shell", "dumpsys", "meminfo", APP, capture=True))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--build-root", type=Path, help="the -Pbeamline.buildRoot folder the APKs were built in")
    ap.add_argument("--out", type=Path, help="where results and logs go (default data/routing-device)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("install")
    p.add_argument("--only", nargs="*", choices=sorted(APKS), help="install only these APKs")
    p.set_defaults(fn=install)
    p = sub.add_parser("push")
    p.add_argument("--tiles", type=Path)
    p.add_argument("--pa-only", type=Path)
    p.add_argument("--map-pack", type=Path)
    p.add_argument("--routing-pack", type=Path, help="a routing pack folder (manifest.json + tar), e.g. data/routing/packs/pa")
    p.set_defaults(fn=push)
    p = sub.add_parser("test")
    p.add_argument("--class", dest="cls")
    p.add_argument("--arg", action="append", help="instrumentation argument key=value")
    p.set_defaults(fn=test)
    p = sub.add_parser("measure")
    p.add_argument("--runs", type=int, default=3)
    p.add_argument("--drop-caches", action="store_true")
    p.add_argument("--release", action="store_true", help="measure the installed release build through DemoActivity")
    p.set_defaults(fn=measure)
    p = sub.add_parser("screenshot")
    p.add_argument("out")
    p.add_argument("--from", dest="from_")
    p.add_argument("--to")
    p.add_argument("--wait", type=float, default=12)
    p.set_defaults(fn=screenshot)
    sub.add_parser("meminfo").set_defaults(fn=meminfo)
    a = ap.parse_args()
    serial = os.environ.get("ANDROID_SERIAL", "").strip()
    if not serial:
        sys.exit("ANDROID_SERIAL is not set: this script talks only to the device it names "
                 "(in a round: . tools/round/env.sh <key>)")
    SERIAL.extend(["-s", serial])
    if a.build_root:
        use_build_root(a.build_root if a.build_root.is_absolute() else REPO / a.build_root)
    if a.out:
        global OUT
        OUT = a.out if a.out.is_absolute() else REPO / a.out
    a.fn(a)
    return 0


if __name__ == "__main__":
    sys.exit(main())
