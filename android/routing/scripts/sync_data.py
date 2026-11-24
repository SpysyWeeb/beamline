"""Copy the routing data files from packbuilder/ (their single source) into the Android project.

The Android modules never edit their copies; change the source and run this again. With --check
it only compares and exits 1 when a copy differs (the JVM tests run the same comparison).

    python android/routing/scripts/sync_data.py [--device data/routing/device] [--check] [--only NAME ...]

Copied:
  packbuilder/routing/presets.json        -> routing/core/src/main/resources/io/github/spysyweeb/beamline/routing/presets.json
  packbuilder/routing/device_config.json  -> routing/core/src/main/resources/io/github/spysyweeb/beamline/routing/device_config.json
  packbuilder/routing/banner.json         -> routing/banner/src/main/resources/io/github/spysyweeb/beamline/routing/banner/banner.json
  packbuilder/routing/trips.json          -> routing/testdata/trips.json   (test trips, tests only)
  packbuilder/routing/units.json          -> core/units/src/main/resources/io/github/spysyweeb/beamline/units/units.json
  packbuilder/routing/guidance.json       -> nav/session/src/main/resources/io/github/spysyweeb/beamline/nav/guidance.json
  packbuilder/routing/camera.json         -> map/scene/src/main/resources/io/github/spysyweeb/beamline/map/scene/camera.json
  packbuilder/catalog/download_rules.json -> core/download/src/main/resources/io/github/spysyweeb/beamline/download/download_rules.json
  <device>/valhalla.json                  -> routing/core/src/main/resources/io/github/spysyweeb/beamline/routing/valhalla_base.json

(units.json, guidance.json and camera.json: docs/architecture.md sections 2.9, 3 and 3.7;
download_rules.json: docs/architecture-downloads.md 6.3, track G, added in Round G0; paths
above the routing/ lines are under android/.)

The last one is written by packbuilder/routing/packs.py into the device folder (Valhalla 3.9.1
defaults + build settings + device settings, tile path relative to the device folder). It is
refused if it holds an absolute path. Standard library only.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / "packbuilder" / "routing"
ANDROID = REPO / "android" / "routing"
RES = ANDROID / "core" / "src" / "main" / "resources" / "io" / "github" / "spysyweeb" / "beamline" / "routing"
PKG = Path("src") / "main" / "resources" / "io" / "github" / "spysyweeb" / "beamline"
APP_SIDE = REPO / "android"

FILES = [
    (SRC / "presets.json", RES / "presets.json"),
    (SRC / "device_config.json", RES / "device_config.json"),
    (SRC / "banner.json", ANDROID / "banner" / "src" / "main" / "resources" / "io" / "github" / "spysyweeb" / "beamline" / "routing" / "banner" / "banner.json"),
    (SRC / "trips.json", ANDROID / "testdata" / "trips.json"),
    # docs/architecture.md: the unit formatter, the navigation session and the camera rules
    (SRC / "units.json", APP_SIDE / "core" / "units" / PKG / "units" / "units.json"),
    (SRC / "guidance.json", APP_SIDE / "nav" / "session" / PKG / "nav" / "guidance.json"),
    (SRC / "camera.json", APP_SIDE / "map" / "scene" / PKG / "map" / "scene" / "camera.json"),
    # docs/architecture-downloads.md 6.3: the pack downloader's rules (track G)
    (REPO / "packbuilder" / "catalog" / "download_rules.json",
     APP_SIDE / "core" / "download" / PKG / "download" / "download_rules.json"),
]


def absolute_paths(node, path=""):
    if isinstance(node, dict):
        return [p for k, v in node.items() for p in absolute_paths(v, f"{path}.{k}" if path else k)]
    if isinstance(node, str) and (node.startswith("/") or re.match(r"^[A-Za-z]:[\\/]", node)):
        return [f"{path}={node}"]
    return []


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", type=Path, default=REPO / "data" / "routing" / "device",
                    help="device folder written by packbuilder/routing/packs.py (for valhalla.json)")
    ap.add_argument("--check", action="store_true", help="compare only; exit 1 when a copy differs")
    ap.add_argument("--only", action="append", metavar="NAME",
                    help="only this source file name (repeatable), e.g. --only camera.json: a track copies "
                         "its own rule file without touching the copies other tracks own")
    a = ap.parse_args()
    pairs = list(FILES)
    dev_cfg = a.device / "valhalla.json"
    if a.only:
        pairs = [(s, d) for s, d in pairs if s.name in a.only]
        if not pairs:
            sys.exit(f"--only {a.only}: no such source file")
    elif dev_cfg.exists():
        cfg = json.loads(dev_cfg.read_text(encoding="utf-8"))
        bad = absolute_paths(cfg)
        if bad:
            sys.exit(f"{dev_cfg} holds absolute paths: {bad}")
        pairs.append((dev_cfg, RES / "valhalla_base.json"))
    elif not a.check:
        print(f"note: {dev_cfg} not found; valhalla_base.json left as it is")
    differ = 0
    for src, dst in pairs:
        # LF line endings: on Windows packs.py writes valhalla.json with CRLF, and git checks the
        # copies out with LF (.gitattributes), so a CRLF/LF difference alone is not a difference.
        data = src.read_bytes().replace(b"\r\n", b"\n")
        same = dst.exists() and dst.read_bytes() == data
        if a.check:
            print(("same    " if same else "DIFFERS ") + dst.relative_to(REPO).as_posix())
            differ += not same
        elif not same:
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(data)
            print(f"copied  {src.name} -> {dst.relative_to(REPO).as_posix()}")
        else:
            print(f"same    {dst.relative_to(REPO).as_posix()}")
    return 1 if differ else 0


if __name__ == "__main__":
    sys.exit(main())
