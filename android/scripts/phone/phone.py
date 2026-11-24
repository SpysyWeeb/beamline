"""Measure Beamline on a real phone over adb: the methods of ../measure.py and ../device_ui.py,
made safe for somebody's own phone (report docs/experiments/11-phone-baseline.md).

From the repo root, with a test-extras build installed (phone kit, data/android/phone-kit.json):

    set BEAMLINE_PHONE_SERIAL=<adb serial>       (or --serial before the command; there is no default)
    python android/scripts/phone/phone.py device                              # Android, display modes, page size, storage
    python android/scripts/phone/phone.py settings --style day --fps screen   # the app's own Settings screen, by taps
    python android/scripts/phone/phone.py install us-pa-2026-11-14.tar        # States > Add a state > system file picker
    python android/scripts/phone/phone.py startup --runs 7 --warmup 1 --lat 42.121 --lon -80.082 --zoom 13
    python android/scripts/phone/phone.py pan --swipes 40 --lat 40.44 --lon -80.0 --zoom 14 --view pgh-z14
    python android/scripts/phone/phone.py pan --swipes 60 --pattern travel --lat 40.44 --lon -80.0 --zoom 14 --view pgh-travel
    python android/scripts/phone/phone.py memory --lat 40.44 --lon -80.0 --zoom 14 --wait 8
    python android/scripts/phone/phone.py shot data/phone/screenshots/x.png --lat 42.129 --lon -80.085 --zoom 16
    python android/scripts/phone/phone.py gestures                            # double tap, long press, Back on Settings
    python android/scripts/phone/location_check.py --runs 3 --revoke-after    # location: writes only under data/phone/private/

What is measured is what measure.py measures on the emulator (am start -W, the app's own "first
map frame" / "frames:" log lines, dumpsys meminfo), so the numbers compare. On top, `pan` reads the
present times of the map's own surface from SurfaceFlinger (`dumpsys SurfaceFlinger --latency
<the map's SurfaceView layer>`, polled every 0.35 s, 128 frames kept by SurfaceFlinger) and the
display's refresh period: the map draws on its own SurfaceView, which the view system's frame
statistics (`dumpsys gfxinfo`, also recorded) do not see.

Since the review of report 11, `pan` also (1) moves the camera by a few metres with the test
extras before the gestures (--no-settle skips it), so that the app's first "frames:" window, which
holds the view's load frames, is closed before the run and the run's worst MapLibre frame is a
gesture frame; (2) reads the app's log a second time with `logcat -v monotonic`, the clock of
SurfaceFlinger's present times, and checks every present interval over 150 ms against the
app's "camera idle" lines: a pause follows a camera stop, a stall does not ("long_intervals",
"while_moving"); and gives the late share without the label-fade frames just after a camera
stop ("late_share_moving"). reanalyse_gaps.py runs the same check on the runs saved before.

The phone is the owner's. This script therefore:
- talks only to the phone named by --serial or BEAMLINE_PHONE_SERIAL, never to "the only device";
- reads logcat only for the app's own process (--pid) and never clears the log;
- writes files on the phone only into /sdcard/Download/beamline-test/ (the UI dumps);
- sends an input event only while the app, or the system file picker it opened, has the focus:
  the check runs on the phone in the same shell command as the event;
- refuses to save logs or screenshots outside data/phone/private/ while the app holds the
  location permission (the puck and the camera lines would carry the phone's real position);
- prints only label matches from the file picker, never what else is listed there.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ANDROID_DIR = REPO / "android"
OUT = REPO / "data" / "phone"
PRIVATE = OUT / "private"
PHONE_DIR = "/sdcard/Download/beamline-test"
PICKERS = ("com.google.android.documentsui", "com.android.documentsui")
INT64_MAX = 9223372036854775807
# A present-to-present interval longer than this is left out of "fps while drawing" and the late
# share. Such intervals are reported on their own ("long_intervals") and each is checked against
# the app's "camera idle" log lines: one that does not follow a camera stop is a stall while the
# map moved ("while_moving"). In report 11 none of 1,109 did.
GAP_NS = 150_000_000
STOP_BEFORE_GAP_S = 0.8  # a long interval counts as a pause if the camera stopped at most this long before it
SETTLE_S = 0.45  # after a camera stop MapLibre still draws for about 0.3 s (label fades): "settling" frames


def _install_end(line: str) -> str | None:
    """The end state of a pack install in a BeamlinePacks log line (Done, Failed, Cancelled), or None."""
    m = re.search(r"install(?: status|:) (Done|Failed|Cancelled)", line)
    return m.group(1) if m else None


def app_id() -> str:
    if os.environ.get("BEAMLINE_APPLICATION_ID"):
        return os.environ["BEAMLINE_APPLICATION_ID"]
    for line in (ANDROID_DIR / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("beamline.applicationId="):
            return line.split("=", 1)[1].strip()
    sys.exit("beamline.applicationId missing from android/gradle.properties")


APP = app_id()
ACTIVITY = f"{APP}/io.github.spysyweeb.beamline.app.MainActivity"


def adb_path() -> str:
    exe = shutil.which("adb")
    if exe:
        return exe
    local = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
    if local.exists():
        return str(local)
    sys.exit("adb not found")


class Phone:
    def __init__(self, serial: str) -> None:
        if not serial:
            sys.exit("name the phone: --serial <adb serial> or BEAMLINE_PHONE_SERIAL")
        self.serial = serial
        self.adb = adb_path()

    # ---- adb -------------------------------------------------------------------------------
    def run(self, *args: str, binary: bool = False, timeout: float = 180) -> str | bytes:
        p = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, timeout=timeout,
                           text=not binary, encoding=None if binary else "utf-8", errors=None if binary else "replace")
        return p.stdout

    def sh(self, cmd: str, timeout: float = 180) -> str:
        return self.run("shell", cmd, timeout=timeout)

    # ---- state -----------------------------------------------------------------------------
    def front(self) -> str:
        m = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^/\s}]+)", self.sh("dumpsys window displays | grep mCurrentFocus"))
        return m.group(1) if m else "?"

    def pid(self) -> str | None:
        p = self.sh(f"pidof {APP}").strip()
        return p or None

    def location_granted(self) -> bool:
        out = self.sh(f"dumpsys package {APP} | grep -E 'ACCESS_(FINE|COARSE)_LOCATION: granted=true'")
        return "granted=true" in out

    def guard_private(self, path: Path) -> None:
        """Never save logs or pictures outside data/phone/private/ while the app may show the position."""
        if self.location_granted() and PRIVATE.resolve() not in path.resolve().parents:
            sys.exit(f"the app holds the location permission: {path} must be under {PRIVATE}")

    def app_log(self, pid: str) -> list[str]:
        return self.run("logcat", "-d", "-v", "threadtime", f"--pid={pid}").splitlines()

    def app_log_mono(self, pid: str) -> list[tuple[float, str]]:
        """The app's log with CLOCK_MONOTONIC times in seconds (`logcat -v monotonic`), the clock of
        SurfaceFlinger's present times. logcat converts with the current realtime-monotonic offset,
        which is exact for lines written since the phone last slept (it stays awake on the cable)."""
        rows = []
        for l in self.run("logcat", "-d", "-v", "monotonic", f"--pid={pid}").splitlines():
            m = re.match(r"\s*(\d+\.\d+)\s+\d+\s+\d+\s+[VDIWEF]\s+(.*)", l)
            if m:
                rows.append((float(m.group(1)), m.group(2)))
        return rows

    # ---- input, only with our app (or its file picker) in front ------------------------------
    def guarded(self, cmd: str, allowed: tuple[str, ...] = ()) -> bool:
        """Run an input command on the phone only if the focused window belongs to our app (or
        one of `allowed`). The check runs in the same shell command, just before the event."""
        pkgs = "|".join(re.escape(p) for p in (APP, *allowed))
        out = self.sh(f"if dumpsys window displays | grep mCurrentFocus | grep -q -E ' ({pkgs})/'; "
                      f"then {cmd}; echo SENT; else echo NOTFRONT; fi")
        return "SENT" in out

    # ---- the app ---------------------------------------------------------------------------
    def start(self, lat=None, lon=None, zoom=None, screen=None, stop_first=True) -> str:
        if stop_first:
            self.sh(f"am force-stop {APP}")
            time.sleep(1.5)
        extra = ""
        if lat is not None and lon is not None:
            extra += f" --ef lat {lat} --ef lon {lon} --ef zoom {zoom if zoom is not None else 12}"
        if screen:
            extra += f" --es screen {screen}"
        return self.sh(f"am start -W -n {ACTIVITY}{extra}")

    # ---- SurfaceFlinger --------------------------------------------------------------------
    def map_layer(self) -> str | None:
        out = self.sh(f"dumpsys SurfaceFlinger --list | grep 'SurfaceView\\[{APP}' | grep BLAST | grep -v Background")
        for line in out.splitlines():
            m = re.match(r"\s*RequestedLayerState\{(.*?) parentId=", line)
            name = m.group(1) if m else line.strip()
            if name:
                return name
        return None

    def sf_latency(self, layer: str) -> tuple[int, list[tuple[int, int, int]]]:
        out = self.sh(f"dumpsys SurfaceFlinger --latency '{layer}'", timeout=30).split()
        if not out:
            return 0, []
        period = int(out[0])
        nums = [int(v) for v in out[1:]]
        rows = [tuple(nums[i:i + 3]) for i in range(0, len(nums) - 2, 3)]
        return period, [r for r in rows if 0 < r[1] < INT64_MAX]

    def display_period(self) -> int:
        out = self.sh("dumpsys SurfaceFlinger --latency").split()
        return int(out[0]) if out else 0

    # ---- UI (uiautomator dump kept in our test folder) ----------------------------------------
    def nodes(self) -> list["Node"]:
        for _ in range(3):
            out = self.sh(f"mkdir -p {PHONE_DIR}; uiautomator dump {PHONE_DIR}/ui.xml >/dev/null 2>&1; cat {PHONE_DIR}/ui.xml")
            if out.strip().startswith("<?xml"):
                break
            time.sleep(1)
        else:
            return []
        root = ET.fromstring(out[out.index("<?xml"):].split("</hierarchy>")[0] + "</hierarchy>")
        res = []
        for n in root.iter("node"):
            b = [int(v) for v in re.findall(r"-?\d+", n.get("bounds", "[0,0][0,0]"))[:4]]
            res.append(Node(n.get("text", ""), n.get("content-desc", ""), n.get("package", ""),
                            n.get("clickable") == "true", n.get("checked") == "true", tuple(b)))
        return res

    def find(self, labels: tuple[str, ...], contains=False, timeout=10.0) -> "Node | None":
        end = time.time() + timeout
        while True:
            for n in self.nodes():
                lab = n.label()
                if lab and any((l in lab) if contains else (lab == l) for l in labels):
                    return n
            if time.time() > end:
                return None
            time.sleep(0.7)

    def tap_label(self, label: str, contains=False, timeout=10.0, allowed: tuple[str, ...] = ()) -> bool:
        n = self.find((label,), contains, timeout)
        if n is None:
            return False
        x, y = n.centre
        return self.guarded(f"input tap {x} {y}", allowed)


@dataclass
class Node:
    text: str
    desc: str
    package: str
    clickable: bool
    checked: bool
    bounds: tuple

    @property
    def centre(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    def label(self) -> str:
        return self.text or self.desc


def stamp() -> str:
    return datetime.now().strftime("%Y%m%d-%H%M%S")


def log_time(line: str) -> float | None:
    """Seconds within the day from a threadtime logcat line (MM-DD HH:MM:SS.mmm)."""
    m = re.match(r"\d\d-\d\d (\d\d):(\d\d):(\d\d)\.(\d\d\d)", line)
    if not m:
        return None
    h, mi, s, ms = (int(g) for g in m.groups())
    return h * 3600 + mi * 60 + s + ms / 1000


def start_info(lines: list[str]) -> dict:
    info = {}
    for l in lines:
        if "MapLibre renderer:" in l:
            info["renderer"] = l.split("MapLibre renderer:", 1)[1].strip()
        if "frame rate requested:" in l:
            info["frame_rate_requested"] = l.split("frame rate requested:", 1)[1].strip()
            m = re.search(r"max (\d+) fps", l)
            if m:
                info["max_fps"] = int(m.group(1))
        if "style loaded:" in l:
            m = re.search(r"style loaded: (\d+) layers.*\((day|night)\)", l)
            if m:
                info["layers"], info["style"] = int(m.group(1)), m.group(2)
    return info


# ---- commands -------------------------------------------------------------------------------

def cmd_device(ph: Phone, a) -> None:
    props = ["ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.build.id", "ro.soc.model",
             "ro.hardware.egl", "ro.hardware.vulkan", "ro.product.cpu.abilist"]
    for p in props:
        print(f"{p}={ph.sh(f'getprop {p}').strip()}")
    print(f"page size={ph.sh('getconf PAGE_SIZE').strip()} bytes")
    for ns, key in (("system", "peak_refresh_rate"), ("system", "min_refresh_rate"), ("global", "low_power")):
        print(f"settings {ns} {key}={ph.sh(f'settings get {ns} {key}').strip()}")
    disp = ph.sh("dumpsys display")
    seen = set()
    for mode in re.findall(r"\{id=(\d+),.*?width=(\d+), height=(\d+), fps=([\d.]+)", disp):
        if mode[0] not in seen:
            seen.add(mode[0])
            print(f"display mode id={mode[0]} {mode[1]}x{mode[2]} {float(mode[3]):.1f} Hz")
    rf = re.search(r"mBaseDisplayInfo=.*?renderFrameRate ([\d.]+)", disp)
    if rf:
        print(f"render frame rate now: {rf.group(1)}")
    rr = re.search(r"supportedRefreshRates \[([^\]]*)\]", disp)
    if rr:
        print(f"supported refresh rates: {rr.group(1)}")
    am = re.search(r"mActiveModeId=(\d+)", disp)
    print(f"active mode now: {am.group(1) if am else '?'}; display vsync period now {ph.display_period() / 1e6:.2f} ms")
    print(ph.sh("df -h /data /storage/emulated/0").rstrip())
    path = ph.sh(f"pm path {APP}").strip()
    print(f"{APP}: {'installed' if path else 'not installed'}")


def cmd_settings(ph: Phone, a) -> None:
    """Set Map style and Frame rate on the app's Settings screen by taps; check the log."""
    ph.start(screen="settings")
    pid = ph.pid()
    time.sleep(2.5)
    labels = {"day": "Day", "night": "Night", "automatic": "Automatic",
              "screen": "Screen rate", "60": "60 frames a second", "30": "30 frames a second"}
    for want in (a.style, a.fps):
        if want and not ph.tap_label(labels[want], timeout=10):
            sys.exit(f"could not tap {labels[want]!r} (not on screen, or the app is not in front)")
        time.sleep(1.0)
    ph.tap_label("Back", timeout=5)
    time.sleep(2.5)
    info = start_info(ph.app_log(pid))
    print(f"settings now: style {info.get('style')}, frame rate requested {info.get('frame_rate_requested')}, renderer {info.get('renderer')}")


def cmd_install(ph: Phone, a) -> None:
    """Add a pack through the app's own flow: States > Add a state from a pack file (.tar) > picker."""
    ph.start(screen="packs")
    pid = ph.pid()
    time.sleep(2.5)
    t_tap = time.time()
    if not ph.tap_label("Add a state from a pack file (.tar)", timeout=10):
        sys.exit("'Add a state from a pack file (.tar)' not tapped")
    # The picker: our file if it is listed, else our folder, else Downloads from the roots.
    folder = PHONE_DIR.rsplit("/", 1)[1]
    end = time.time() + 60
    done = False
    while time.time() < end and not done:
        time.sleep(1.5)
        f = ph.front()
        if f not in PICKERS:
            continue
        nodes = ph.nodes()
        by = {n.label(): n for n in nodes if n.label()}
        if a.archive in by:
            x, y = by[a.archive].centre
            done = ph.guarded(f"input tap {x} {y}", PICKERS)
            print("picker: tapped the archive")
        elif folder in by:
            x, y = by[folder].centre
            ph.guarded(f"input tap {x} {y}", PICKERS)
            print("picker: opened the test folder")
        elif "Downloads" in by:
            x, y = by["Downloads"].centre
            ph.guarded(f"input tap {x} {y}", PICKERS)
            print("picker: opened Downloads")
        elif "Show roots" in by:
            x, y = by["Show roots"].centre
            ph.guarded(f"input tap {x} {y}", PICKERS)
            print("picker: opened the roots list")
        else:
            print("picker: none of the expected labels on screen yet")
    if not done:
        sys.exit("the archive was not picked")
    t_picked = time.time()
    lines: list[str] = []
    end = time.time() + a.timeout
    while time.time() < end:
        time.sleep(1.0)
        lines = [l for l in ph.app_log(pid) if "BeamlinePacks" in l]
        # the app logs "install status Done|Failed|Cancelled" (AppGraph); "install: X" was the older form
        if any(_install_end(l) for l in lines):
            break
    t_end = time.time()
    run = [l for l in lines if " bytes" in l or "install: " in l or "install status " in l]
    first = next((l for l in run if re.search(r"install .*: [1-9]\d* of \d+ bytes", l)), None)
    last = next((l for l in reversed(run) if _install_end(l) in ("Done", "Failed")), None)
    for l in run[-14:]:
        print(l.split("BeamlinePacks:", 1)[-1].strip()[:200])
    result = {"archive": a.archive, "picked_to_end_s": round(t_end - t_picked, 1), "tap_to_end_s": round(t_end - t_tap, 1)}
    if first and last:
        result["first_bytes_to_done_s"] = round(log_time(last) - log_time(first), 2)
    start_line = next((l for l in run if "Running" in l or re.search(r"install .*: 0 of", l)), None)
    if start_line and last:
        result["start_to_done_s"] = round(log_time(last) - log_time(start_line), 2)
    result["ok"] = bool(last and _install_end(last) == "Done")
    print(json.dumps(result))
    save_jsonl(ph, "install", {**result, "log": run})


def save_jsonl(ph: Phone, name: str, obj: dict, private: bool = False) -> None:
    folder = (PRIVATE if private else OUT) / "results"
    path = folder / f"{name}.jsonl"
    ph.guard_private(path)
    folder.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps({"time": datetime.now().isoformat(timespec="seconds"), **obj}) + "\n")


def cmd_startup(ph: Phone, a) -> None:
    rows = []
    info = {}
    for i in range(a.warmup + a.runs):
        out = ph.start(a.lat, a.lon, a.zoom)
        total = re.search(r"TotalTime: (\d+)", out)
        pid = ph.pid()
        first = full = None
        end = time.time() + 60
        while time.time() < end and pid:
            lines = ph.app_log(pid)
            for l in lines:
                m = re.search(r"first map frame (\d+) ms", l)
                if m:
                    first = int(m.group(1))
                m = re.search(r"first fully rendered map frame (\d+) ms", l)
                if m:
                    full = int(m.group(1))
            if full is not None:
                info = start_info(lines)
                break
            time.sleep(0.5)
        row = (int(total.group(1)) if total else None, first, full)
        warm = i < a.warmup
        if not warm:
            rows.append(row)
        label = f"warm-up {i + 1} (not counted)" if warm else f"run {i + 1 - a.warmup}"
        print(f"{label}: activity drawn {row[0]} ms, first map frame {row[1]} ms, fully rendered {row[2]} ms", flush=True)
        time.sleep(1)
    print(f"settings: {info}")
    summary = {"view": a.view, "tag": a.tag, **info, "runs": rows}
    for k, name in enumerate(("activity_drawn", "first_map_frame", "fully_rendered")):
        vals = [r[k] for r in rows if r[k] is not None]
        if vals:
            summary[name] = {"median": statistics.median(vals), "min": min(vals), "max": max(vals), "n": len(vals)}
            print(f"{name}: median {statistics.median(vals):.0f} ms, min {min(vals)}, max {max(vals)} (n={len(vals)})")
    save_jsonl(ph, "startup", summary)


class LatencyPoller(threading.Thread):
    """Polls SurfaceFlinger's last 128 present times of the map layer and the display period."""

    def __init__(self, ph: Phone, layer: str, baseline: int) -> None:
        super().__init__(daemon=True)
        self.ph, self.layer, self.baseline = ph, layer, baseline
        self.rows: dict[int, tuple[int, int, int]] = {}
        self.periods: list[int] = []
        self.stop_flag = threading.Event()
        self.overruns = 0  # polls where the oldest new row followed a gap we could not see

    def run(self) -> None:
        last_max = self.baseline
        while not self.stop_flag.is_set():
            period, rows = self.ph.sf_latency(self.layer)
            if period:
                self.periods.append(period)
            new = [r for r in rows if r[1] > self.baseline]
            if new and rows and len(rows) >= 127 and min(r[1] for r in rows) > last_max:
                self.overruns += 1
            for r in new:
                self.rows[r[1]] = r
            if new:
                last_max = max(last_max, max(r[1] for r in new))
            time.sleep(0.35)


def frame_stats(presents: list[int], target_ms: float, stops: list[float] | None = None,
                first_gesture_ns: int | None = None) -> dict:
    """Statistics of the present-to-present intervals of the map's surface.

    stops: the app's "camera idle" times (CLOCK_MONOTONIC seconds, as the presents). With them,
    every interval over GAP_NS is checked: a pause follows a camera stop (at most
    STOP_BEFORE_GAP_S before it); one that does not is a stall while the map moved. And the
    intervals that start within SETTLE_S after a camera stop (MapLibre's label fades) are told
    apart from those while the map moves ("late_share_moving").
    first_gesture_ns: intervals that end before it (the view's own load) are left out."""
    p = sorted(presents)
    if first_gesture_ns is not None:
        p = [x for x in p if x >= first_gesture_ns]
    pairs = [(a / 1e9, (b - a) / 1e6) for a, b in zip(p, p[1:])]
    iv = [v for _, v in pairs]
    active = [v for v in iv if v * 1e6 < GAP_NS]
    if not active:
        return {"frames": len(p)}
    late = [v for v in active if v > 1.5 * target_ms]
    buckets = {"<=12.5ms": 0, "12.5-25ms": 0, "25-50ms": 0, "50-150ms": 0}
    for v in active:
        k = "<=12.5ms" if v <= 12.5 else "12.5-25ms" if v <= 25 else "25-50ms" if v <= 50 else "50-150ms"
        buckets[k] += 1
    long_iv = [(a, v) for a, v in pairs if v * 1e6 >= GAP_NS]
    res = {
        "frames": len(p),
        "intervals_active": len(active),
        "pauses": len(iv) - len(active),
        "fps_while_drawing": round(len(active) / (sum(active) / 1000), 1),
        "median_interval_ms": round(statistics.median(active), 2),
        "p95_interval_ms": round(sorted(active)[int(0.95 * (len(active) - 1))], 2),
        "late_share": round(len(late) / len(active), 3),
        "late_rule": f"interval > 1.5 x {target_ms:.2f} ms",
        "buckets": buckets,
        "long_intervals": {"n": len(long_iv), "total_ms": round(sum(v for _, v in long_iv)),
                           "max_ms": round(max((v for _, v in long_iv), default=0)),
                           "ms": [round(v) for _, v in long_iv][:60]},
    }
    if stops is not None:
        st = sorted(stops)
        moving_long = [v for a, v in long_iv if not any(a - STOP_BEFORE_GAP_S <= t <= a + v / 1000 for t in st)]
        lags = []
        for a, v in long_iv:
            before = [t for t in st if t <= a + v / 1000]
            if before:
                lags.append(round((a - before[-1]) * 1000))
        res["long_intervals"].update({"while_moving": len(moving_long), "while_moving_ms": [round(v) for v in moving_long],
                                      "ms_after_last_stop": lags[:60]})
        moving = [v for a, v in pairs if v * 1e6 < GAP_NS and not any(a - SETTLE_S <= t <= a for t in st)]
        if moving:
            res["intervals_moving"] = len(moving)
            res["fps_moving"] = round(len(moving) / (sum(moving) / 1000), 1)
            res["late_share_moving"] = round(sum(1 for v in moving if v > 1.5 * target_ms) / len(moving), 3)
        res["camera_stops"] = len([t for t in st if p and p[0] / 1e9 <= t <= p[-1] / 1e9])
    return res


def gfx_summary(text: str) -> dict:
    keys = {"Total frames rendered": "total", "Janky frames": "janky", "50th percentile": "p50", "90th percentile": "p90",
            "99th percentile": "p99"}
    out = {}
    for line in text.splitlines():
        for k, v in keys.items():
            if line.strip().startswith(k + ":") and v not in out:
                out[v] = line.split(":", 1)[1].strip()
    return out


def go_to_view(ph: Phone, a) -> tuple[str | None, dict]:
    if a.lat is None or a.lon is None:
        return ph.pid(), {}
    ph.start(a.lat, a.lon, a.zoom)
    time.sleep(a.wait)
    pid = ph.pid()
    return pid, start_info(ph.app_log(pid)) if pid else {}


def cmd_pan(ph: Phone, a) -> None:
    raw_dir = (PRIVATE if a.private else OUT) / "raw"
    ph.guard_private(raw_dir / "x")
    pid, info = go_to_view(ph, a)
    if not pid:
        sys.exit("app not running")
    if ph.front() != APP:
        sys.exit(f"the app is not in front ({ph.front()}): no input sent")
    if a.settle and a.lat is not None:
        # Move the camera by a few metres (the test extras, not a touch) and let it settle: the
        # frame closes the app's first "frames:" window, which holds the view's load frames, so
        # the windows of the run hold gesture frames only. The load window is reported apart.
        ph.start(a.lat, round(a.lon + 0.00002, 6), a.zoom, stop_first=False)
        time.sleep(1.5)
    layer = ph.map_layer()
    if not layer:
        sys.exit("map layer not found in SurfaceFlinger")
    size = re.search(r"(\d+)x(\d+)", ph.sh("wm size"))
    w, h = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)
    ph.sh(f"dumpsys gfxinfo {APP} reset")
    _, before = ph.sf_latency(layer)
    baseline = max((r[1] for r in before), default=0)
    poller = LatencyPoller(ph, layer, baseline)
    poller.start()
    if a.pattern == "travel":
        moves = [(0.8, 0.45, 0.2, 0.35)]
    else:
        moves = [(0.2, 0.7, 0.8, 0.3), (0.8, 0.3, 0.2, 0.7), (0.5, 0.8, 0.5, 0.2), (0.5, 0.2, 0.5, 0.8),
                 (0.8, 0.5, 0.2, 0.5), (0.2, 0.5, 0.8, 0.5)]
    t0 = time.time()
    clock0 = log_time("01-01 " + ph.sh("date +%H:%M:%S.%3N").strip()) or 0.0
    sent = 0
    for i in range(a.swipes):
        x1, y1, x2, y2 = moves[i % len(moves)]
        if a.pattern == "zoom":
            # double tap and drag (one-finger zoom): a tap, then a swipe whose touch-down is the
            # second tap; dragging down zooms in, up zooms out (about one zoom level each)
            x, y, d = w // 2, int(0.45 * h), (1 if i % 2 == 0 else -1)
            cmd = f"input tap {x} {y}; input swipe {x} {y} {x} {y + d * 500} 800"
        else:
            cmd = f"input swipe {int(x1 * w)} {int(y1 * h)} {int(x2 * w)} {int(y2 * h)} 600"
        if not ph.guarded(cmd):
            print("stopped: the app lost the focus")
            break
        sent += 1
        time.sleep(0.3)
    secs = time.time() - t0
    time.sleep(2)
    poller.stop_flag.set()
    poller.join(timeout=10)
    lines = ph.app_log(pid)
    mono = ph.app_log_mono(pid)
    gfx = ph.sh(f"dumpsys gfxinfo {APP}")
    info = {**info, **start_info(lines)} if not info else info
    fps, avg, mx, load_mx = [], [], [], []
    for l in lines:
        m = re.search(r"frames: n=(\d+) in (\d+) ms, cpu avg=([\d.]+) ms max=([\d.]+) ms", l)
        if m and log_time(l) is not None and log_time(l) >= clock0:
            fps.append(int(m.group(1)) * 1000 / int(m.group(2)))
            avg.append(float(m.group(3)))
            mx.append(float(m.group(4)))
        elif m:
            load_mx.append(float(m.group(4)))
    # Only the windows logged during this run: the pan starts after the view's own load frames.
    max_fps = info.get("max_fps", 60)
    target_ms = 1000 / max_fps  # the target is what the setting asks for; the display period is reported apart
    # realtime - monotonic from the same log lines read both ways, to place the run's start (clock0)
    # and the camera stops on SurfaceFlinger's clock
    stops, first_gesture_ns, offsets = None, None, []
    tt: dict[str, list[float]] = {}
    for l in lines:
        if log_time(l) is not None and "frames: n=" in l:
            tt.setdefault(l.split(": ", 1)[-1], []).append(log_time(l))
    mm: dict[str, list[float]] = {}
    for t, msg in mono:
        if "frames: n=" in msg:
            mm.setdefault(msg.split(": ", 1)[-1], []).append(t)
    for msg, ts in tt.items():
        if len(ts) == 1 and len(mm.get(msg, [])) == 1:
            offsets.append(ts[0] - mm[msg][0])
    if offsets:
        off = statistics.median(offsets[:50])
        first_gesture_ns = int((clock0 - off) * 1e9)
        stops = [t for t, msg in mono if "camera idle" in msg]
    sf = frame_stats(list(poller.rows), target_ms, stops, first_gesture_ns)
    per = {}
    for p in poller.periods:
        k = f"{p / 1e6:.2f} ms"
        per[k] = per.get(k, 0) + 1
    res = {
        "view": a.view, "tag": a.tag, "pattern": a.pattern, "swipes": sent, "seconds": round(secs, 1),
        "renderer": info.get("renderer"), "style": info.get("style"), "max_fps": max_fps,
        "frame_rate_requested": info.get("frame_rate_requested"), "layers": info.get("layers"),
        "maplibre_windows": len(fps),
        "maplibre_fps_median": round(statistics.median(fps), 1) if fps else None,
        "maplibre_fps_min": round(min(fps), 1) if fps else None,
        "maplibre_fps_max": round(max(fps), 1) if fps else None,
        "maplibre_cpu_ms_median": round(statistics.median(avg), 2) if avg else None,
        "maplibre_cpu_ms_worst": round(max(mx), 1) if mx else None,
        "maplibre_cpu_ms_worst_before_run": round(max(load_mx), 1) if load_mx else None,
        "settle": bool(a.settle and a.lat is not None), "clock_lines_matched": len(offsets),
        "surface": sf, "display_period_samples": per, "poll_overruns": poller.overruns,
        "view_system_gfxinfo": gfx_summary(gfx),
    }
    print(json.dumps(res))
    raw_dir.mkdir(parents=True, exist_ok=True)
    raw = raw_dir / f"pan-{a.view}-{stamp()}.json"
    raw.write_text(json.dumps({"result": res, "presents": sorted(poller.rows), "first_gesture_ns": first_gesture_ns,
                               "camera_stops_mono": stops,
                               "log": [l for l in lines if "Beamline" in l or " E " in l or " W " in l]}), encoding="utf-8")
    save_jsonl(ph, "pan", res, private=a.private)


def meminfo(ph: Phone) -> dict:
    out = ph.sh(f"dumpsys meminfo {APP}")
    res = {}
    for line in out.splitlines():
        s = line.strip()
        for key in ("Native Heap", "Dalvik Heap", "Graphics", "GL mtrack", "EGL mtrack", "Gfx dev", "Other mtrack"):
            if s.startswith(key) and key not in res:
                nums = re.findall(r"\d+", s[len(key):])
                if nums:
                    res[key] = int(nums[0])  # first column: PSS (or the summary's value), kB
        m = re.match(r"TOTAL PSS:\s+(\d+)\s+TOTAL RSS:\s+(\d+)", s)
        if m:
            res["TOTAL PSS"], res["TOTAL RSS"] = int(m.group(1)), int(m.group(2))
        m = re.match(r"TOTAL:\s+(\d+)", s)
        if m and "TOTAL" not in res:
            res["TOTAL"] = int(m.group(1))
    return res


def cmd_memory(ph: Phone, a) -> None:
    pid, info = go_to_view(ph, a)
    if a.idle:
        time.sleep(a.idle)
    res = {"view": a.view, "tag": a.tag, "phase": a.phase, "wait_s": a.wait if a.lat is not None else None,
           "idle_s": a.idle, "renderer": info.get("renderer"), "style": info.get("style"), "kB": meminfo(ph)}
    print(json.dumps(res))
    save_jsonl(ph, "memory", res)


def cmd_shot(ph: Phone, a) -> None:
    out = Path(a.path)
    ph.guard_private(out)
    if a.lat is not None or a.screen:
        ph.start(a.lat, a.lon, a.zoom, screen=a.screen, stop_first=a.restart)
    time.sleep(a.wait)
    before = ph.front()
    png = ph.run("exec-out", "screencap", "-p", binary=True)
    after = ph.front()
    if not (before == APP and after == APP):
        sys.exit(f"not saved: {before} / {after} was in front, not {APP}")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(png)
    print(f"screenshot {out.as_posix()} ({len(png)} bytes)")


def camera_lines(ph: Phone, pid: str) -> list[str]:
    return [l.split("camera idle ", 1)[1] for l in ph.app_log(pid) if "camera idle " in l]


def cmd_gestures(ph: Phone, a) -> None:
    """Gestures adb can drive: double tap, long press, the system Back key on Settings."""
    ph.guard_private(OUT / "x")
    w, h = 1080, 2424
    size = re.search(r"(\d+)x(\d+)", ph.sh("wm size"))
    if size:
        w, h = int(size.group(1)), int(size.group(2))
    x, y = w // 2, int(0.45 * h)
    ph.start(42.121, -80.082, 13)
    time.sleep(4)
    pid = ph.pid()
    before = camera_lines(ph, pid)[-1:]
    sent = ph.guarded(f"input tap {x} {y}; input tap {x} {y}")
    time.sleep(2)
    print(f"double tap (sent {sent}): camera {before} -> {camera_lines(ph, pid)[-1:]}")
    before = camera_lines(ph, pid)
    sent = ph.guarded(f"input swipe {x} {y} {x} {y} 1200")
    time.sleep(2)
    after = camera_lines(ph, pid)
    print(f"long press (sent {sent}): camera lines added {len(after) - len(before)}, front {ph.front()}")
    ph.start(screen="settings", stop_first=False)
    time.sleep(2)
    on_settings = ph.find(("Frame rate",), timeout=5) is not None
    sent = ph.guarded("input keyevent KEYCODE_BACK")
    time.sleep(2)
    still = ph.find(("Frame rate",), timeout=2) is not None
    print(f"Back on Settings (sent {sent}): Settings shown before {on_settings}, after {still}; front {ph.front()}")
    errs = [l for l in ph.app_log(pid) if re.search(r"\s[EF]\s", l[:40])]
    print(f"error lines of the app's process: {len(errs)}")
    for l in errs[:10]:
        print("  " + l[:200])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default=os.environ.get("BEAMLINE_PHONE_SERIAL", ""))
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("device")
    sub.add_parser("gestures")
    s = sub.add_parser("settings")
    s.add_argument("--style", choices=("day", "night", "automatic"))
    s.add_argument("--fps", choices=("screen", "60", "30"))
    i = sub.add_parser("install")
    i.add_argument("archive", help="file name inside /sdcard/Download/beamline-test/")
    i.add_argument("--timeout", type=float, default=600)
    for name in ("startup", "pan", "memory", "shot"):
        p = sub.add_parser(name)
        if name == "shot":
            p.add_argument("path")
            p.add_argument("--restart", action="store_true", help="force-stop first (else the running app gets the camera extras)")
            p.add_argument("--screen", choices=("map", "packs", "settings"))
        p.add_argument("--lat", type=float)
        p.add_argument("--lon", type=float)
        p.add_argument("--zoom", type=float, default=12)
        p.add_argument("--view", default="")
        p.add_argument("--tag", default="")
        if name == "startup":
            p.add_argument("--runs", type=int, default=5)
            p.add_argument("--warmup", type=int, default=1)
        if name == "pan":
            p.add_argument("--swipes", type=int, default=20)
            p.add_argument("--pattern", choices=("back-and-forth", "travel", "zoom"), default="back-and-forth",
                           help="zoom: --swipes one-finger zoom gestures, alternately in and out")
            p.add_argument("--private", action="store_true")
            p.add_argument("--no-settle", dest="settle", action="store_false",
                           help="skip the small camera move that closes the load window before the gestures "
                                "(runs before the review of report 11 had none)")
        if name in ("pan", "memory"):
            p.add_argument("--wait", type=float, default=8)
        if name == "memory":
            p.add_argument("--idle", type=float, default=0, help="seconds to wait before reading (idle)")
            p.add_argument("--phase", default="")
        if name == "shot":
            p.add_argument("--wait", type=float, default=6)
    a = ap.parse_args()
    ph = Phone(a.serial)
    {"device": cmd_device, "gestures": cmd_gestures, "settings": cmd_settings, "install": cmd_install, "startup": cmd_startup, "pan": cmd_pan,
     "memory": cmd_memory, "shot": cmd_shot}[a.cmd](ph, a)
    return 0


if __name__ == "__main__":
    sys.exit(main())
