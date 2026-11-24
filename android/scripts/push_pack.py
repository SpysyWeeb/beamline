"""Copy a pack folder to the app's pack folder on a device or emulator with adb.

Usage (from the repo root; adb on PATH or in $ANDROID_HOME/platform-tools):

    python android/scripts/push_pack.py data/android/packs/us-pa-sample
    python android/scripts/push_pack.py --remove us-pa-sample      # delete one installed pack
    python android/scripts/push_pack.py --list                     # show installed packs

The app reads packs from Android/data/<application id>/files/packs/<pack folder>/ (its own
external files folder, which adb can write to without any app permission). The application id
is read from android/gradle.properties (or BEAMLINE_APPLICATION_ID). Install and start the app
once first so Android creates the folder.

The device is always the one ANDROID_SERIAL names (`. tools/round/env.sh <key>` sets it to the
track's emulator): without it the script refuses to run, so it can never reach another device.
A pushed pack has no install record: the app shows it as not checked and does not merge its
routing tiles (format 3, docs/pack-format.md 5). To test the real install, push the archive to
/sdcard/Download/ and use "Use a file instead".
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ANDROID_DIR = Path(__file__).resolve().parents[1]


def app_id() -> str:
    # BEAMLINE_APPLICATION_ID: a second install built with -Pbeamline.applicationId=...
    if os.environ.get("BEAMLINE_APPLICATION_ID"):
        return os.environ["BEAMLINE_APPLICATION_ID"]
    for line in (ANDROID_DIR / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("beamline.applicationId="):
            return line.split("=", 1)[1].strip()
    sys.exit("beamline.applicationId missing from android/gradle.properties")


def adb() -> str:
    exe = shutil.which("adb")
    if exe:
        return exe
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            cand = Path(os.environ[var]) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if cand.exists():
                return str(cand)
    sys.exit("adb not found")


def serial() -> str:
    s = os.environ.get("ANDROID_SERIAL", "").strip()
    if not s:
        sys.exit("ANDROID_SERIAL is not set: run `. tools/round/env.sh <key>` first (this script never picks a device)")
    return s


def sh(*args: str, check: bool = True) -> str:
    p = subprocess.run([adb(), "-s", serial(), *args], capture_output=True, text=True)
    if check and p.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {p.stderr.strip() or p.stdout.strip()}")
    return p.stdout


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pack", nargs="?", type=Path, help="local pack folder (contains manifest.json)")
    ap.add_argument("--remove", metavar="FOLDER", help="remove an installed pack folder")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()

    remote_root = f"/sdcard/Android/data/{app_id()}/files/packs"
    if a.list:
        print(sh("shell", f"ls -la {remote_root}/ 2>/dev/null || echo 'no packs folder'", check=False))
        return 0
    if a.remove:
        if "/" in a.remove or a.remove in ("", ".", ".."):
            sys.exit("give a pack folder name")
        sh("shell", f"rm -rf {remote_root}/{a.remove}")
        print(f"removed {remote_root}/{a.remove}")
        return 0
    if not a.pack:
        ap.error("give a pack folder, --remove or --list")
    manifest = json.loads((a.pack / "manifest.json").read_text(encoding="utf-8"))
    folder = a.pack.resolve().name
    print(f"pack {manifest['pack_id']} ({manifest['name']}, OSM {manifest['osm_snapshot']}) -> {remote_root}/{folder}")
    sh("shell", f"mkdir -p {remote_root}")
    # Push into a temporary name and rename, so the app never sees a half-copied pack.
    tmp = f"{remote_root}/.incoming-{folder}"
    sh("shell", f"rm -rf {tmp}")
    out = sh("push", str(a.pack.resolve()), tmp)
    print(out.strip().splitlines()[-1] if out.strip() else "pushed")
    sh("shell", f"rm -rf {remote_root}/{folder} && mv {tmp} {remote_root}/{folder}")
    print("done; the app picks it up on its next resume")
    return 0


if __name__ == "__main__":
    sys.exit(main())
