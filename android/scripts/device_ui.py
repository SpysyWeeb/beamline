"""Drive the app's screens over adb for the emulator checks: read the screen, tap by text, screenshot.

Usage (from the repo root; adb on PATH or in the SDK; ANDROID_SERIAL picks the device):

    python android/scripts/device_ui.py texts                    # every text / description on screen
    python android/scripts/device_ui.py tap "Add a state"        # tap the element with this text or description
    python android/scripts/device_ui.py tap "Downloads" --contains --timeout 20
    python android/scripts/device_ui.py shot data/android/screenshots/x.png

The screenshot refuses to save when an unexpected app is in front (a shared emulator); the app
itself, the system file picker and the permission dialog are expected.
As a module: `from device_ui import Device`, then d.tap(...), d.shot(...), d.texts().
The app reads its test extras (camera position, screen) only in a debug build or a release built
with -Pbeamline.testExtras=true (android/README.md).
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

ANDROID_DIR = Path(__file__).resolve().parents[1]
EXPECTED_FRONT = ("com.android.documentsui", "com.google.android.documentsui", "com.android.permissioncontroller",
                  "com.google.android.permissioncontroller", "com.android.settings")


def app_id() -> str:
    if os.environ.get("BEAMLINE_APPLICATION_ID"):
        return os.environ["BEAMLINE_APPLICATION_ID"]
    for line in (ANDROID_DIR / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("beamline.applicationId="):
            return line.split("=", 1)[1].strip()
    sys.exit("beamline.applicationId missing from android/gradle.properties")


def adb_path() -> str:
    exe = shutil.which("adb")
    if exe:
        return exe
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            cand = Path(os.environ[var]) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if cand.exists():
                return str(cand)
    local = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
    if local.exists():
        return str(local)
    sys.exit("adb not found")


@dataclass
class Node:
    text: str
    desc: str
    res: str
    cls: str
    package: str
    enabled: bool
    bounds: tuple[int, int, int, int]

    @property
    def centre(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return (x1 + x2) // 2, (y1 + y2) // 2

    def label(self) -> str:
        return self.text or self.desc


class Device:
    def __init__(self) -> None:
        self.adb = adb_path()
        self.app = app_id()

    def run(self, *args: str, binary: bool = False, check: bool = False):
        p = subprocess.run([self.adb, *args], capture_output=True, text=not binary,
                           encoding=None if binary else "utf-8", errors=None if binary else "replace")
        if check and p.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)}: {p.stderr}")
        return p.stdout

    def shell(self, cmd: str) -> str:
        return self.run("shell", cmd)

    def nodes(self) -> list[Node]:
        for _ in range(3):
            out = self.shell("uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1; cat /sdcard/window_dump.xml")
            if out.strip().startswith("<?xml"):
                break
            time.sleep(1)
        else:
            return []
        root = ET.fromstring(out[out.index("<?xml"):].split("</hierarchy>")[0] + "</hierarchy>")
        res = []
        for n in root.iter("node"):
            b = re.findall(r"\d+", n.get("bounds", "[0,0][0,0]"))
            res.append(Node(n.get("text", ""), n.get("content-desc", ""), n.get("resource-id", ""), n.get("class", ""),
                            n.get("package", ""), n.get("enabled", "true") == "true", tuple(int(v) for v in b[:4])))
        return res

    def texts(self) -> list[str]:
        return [n.label() for n in self.nodes() if n.label()]

    def find(self, label: str, contains: bool = False, timeout: float = 10) -> Node | None:
        end = time.time() + timeout
        while True:
            for n in self.nodes():
                lab = n.label()
                if lab and (label in lab if contains else lab == label):
                    return n
            if time.time() > end:
                return None
            time.sleep(0.7)

    def tap(self, label: str, contains: bool = False, timeout: float = 10) -> Node:
        n = self.find(label, contains, timeout)
        if n is None:
            raise RuntimeError(f"'{label}' not on screen; seen: {self.texts()[:40]}")
        x, y = n.centre
        self.shell(f"input tap {x} {y}")
        return n

    def front(self) -> str:
        m = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^/\s}]+)", self.shell("dumpsys window | grep mCurrentFocus"))
        return m.group(1) if m else "?"

    def shot(self, path: str | Path) -> Path:
        before = self.front()
        png = self.run("exec-out", "screencap", "-p", binary=True)
        after = self.front()
        ok = lambda f: f in (self.app, "?") or f in EXPECTED_FRONT or f.startswith("com.android.systemui")
        if not (ok(before) and ok(after)):
            sys.exit(f"not saved: {before} / {after} was in front, not {self.app}")
        out = Path(path)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(png)
        print(f"screenshot {out.as_posix()} ({len(png)} bytes, front: {after})")
        return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("texts")
    t = sub.add_parser("tap")
    t.add_argument("label")
    t.add_argument("--contains", action="store_true")
    t.add_argument("--timeout", type=float, default=10)
    s = sub.add_parser("shot")
    s.add_argument("path")
    a = ap.parse_args()
    d = Device()
    if a.cmd == "texts":
        for n in d.nodes():
            if n.label():
                print(f"{n.label()!r} [{n.package}] {n.bounds}")
    elif a.cmd == "tap":
        n = d.tap(a.label, a.contains, a.timeout)
        print(f"tapped {n.label()!r} at {n.centre}")
    else:
        d.shot(a.path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
