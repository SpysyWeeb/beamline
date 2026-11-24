"""Build the second Pennsylvania archive of report 11: the same pack (id us-pa, so the app replaces
the installed one) with the z0-14 map file from data/map/packs-v2/ in place of the z0-15 one.

    python android/scripts/phone/make_z14_archive.py [--pmtiles data/map/packs-v2/pa-261114-z14.pmtiles]

It runs tools/fetch_android_map_pack.py (the assembly script named in docs/pack-format.md)
unchanged, with its two output folders pointed elsewhere so the phone kit's own us-pa folder and
archive are not overwritten:
    data/android/packs-z14/us-pa/                 the pack folder
    data/android/archives-z14/us-pa-<osm>.tar     the archive
Routing and search come from the same inputs as the kit's archive; the script prints the
SHA-256 of each part so that can be checked against the first archive's manifest.
"""
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pmtiles", default="data/map/packs-v2/pa-261114-z14.pmtiles")
    a = ap.parse_args()
    spec = importlib.util.spec_from_file_location("fetch_android_map_pack", REPO / "tools" / "fetch_android_map_pack.py")
    tool = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tool)
    tool.OUT_PACKS = REPO / "data" / "android" / "packs-z14"
    tool.OUT_ARCHIVES = REPO / "data" / "android" / "archives-z14"
    sys.argv = ["fetch_android_map_pack.py", "--state", "pa", "--pmtiles", str(REPO / a.pmtiles)]
    return tool.main()


if __name__ == "__main__":
    sys.exit(main())
