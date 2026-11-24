"""Copy the search and address rule files from packbuilder/ into the Android search module.

    python android/scripts/copy_search_rules.py           # copy (run from the repo root or anywhere)
    python android/scripts/copy_search_rules.py --check   # exit 1 if a copy differs from its source

The Python pack builders under packbuilder/ (and, for poi_icons.json, the art folder assets/) are
the single source of truth for these data files; --check covers every row, search_app.json and
poi_icons.json included. The Android project never edits its copies: change the file in packbuilder/, rebuild the
packs that depend on it, then run this script. The copies go to
android/core/search/src/main/resources/io/github/spysyweeb/beamline/search/rules/ together with SOURCES.json,
which records where each file came from and its SHA-256 (computed on LF line endings, so a
Windows checkout with CRLF gives the same hash). The unit test RulesAndVersionsTest fails when a copy
and its source drift apart. Standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEST = REPO / "android" / "core" / "search" / "src" / "main" / "resources" / "io" / "github" / "spysyweeb" / "beamline" / "search" / "rules"

# (source in the repo, name of the copy, what reads it)
FILES = [
    ("packbuilder/search/abbreviations.json", "abbreviations.json",
     "search key normaliser: abbreviations, stopwords, state names, street types, connectors"),
    ("packbuilder/search/ranking.json", "ranking.json",
     "search engine: every ranking weight and search limit, the house-number join config"),
    ("packbuilder/addresses/addrlib/us_street_norm.json", "us_street_norm.json",
     "address normaliser and parser: street types, directionals, units, states, ordinals"),
    ("packbuilder/addresses/addrlib/lookup_config.json", "lookup_config.json",
     "address lookup: locality, match, accuracy and source scores"),
    # docs/architecture.md 2.6 and docs/architecture-saved-places.md 7.1 (Round SP0): read by
    # :core:search (SavedRules) and :feature:search through :core:search's resources
    ("packbuilder/search/search_app.json", "search_app.json",
     "search screen and saved places: near, typing, merge, recents, saved, shortcuts"),
    # docs/architecture.md 2.11 and decision 6.11 (round 3, track S): the street-key fingerprint's
    # spelling list (track P's file), read by :core:search's fingerprint check at open
    ("packbuilder/addresses/addrlib/fingerprint_spellings.json", "fingerprint_spellings.json",
     "fingerprint check at open: the spellings of the street-key fingerprint"),
    # docs/architecture.md 5.1 S and decision 6.20: the search rows' and the place sheet's icons
    # are cut from the style's sprite sheet by this map (search category id -> sprite name)
    ("assets/poi_icons.json", "poi_icons.json",
     "search rows and the place sheet: search category id -> sprite icon name"),
]


def lf_bytes(p: Path) -> bytes:
    return p.read_bytes().replace(b"\r\n", b"\n")


def sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="only compare, do not copy")
    args = ap.parse_args()
    bad = []
    sources = {}
    for src_rel, name, used_by in FILES:
        src = REPO / src_rel
        data = lf_bytes(src)
        sources[name] = {"source": src_rel, "sha256_lf": sha256(data), "used_by": used_by}
        dst = DEST / name
        if args.check:
            if not dst.exists() or lf_bytes(dst) != data:
                bad.append(name)
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(data)
        print(f"copied {src_rel} -> {dst.relative_to(REPO).as_posix()} ({len(data)} bytes)")
    manifest = {"_doc": "Written by android/scripts/copy_search_rules.py. Do not edit the files in this folder: "
                        "edit the source in packbuilder/ and run the script again.",
                "files": sources}
    text = json.dumps(manifest, indent=2) + "\n"
    if args.check:
        m = DEST / "SOURCES.json"
        if not m.exists() or m.read_text(encoding="utf-8").replace("\r\n", "\n") != text:
            bad.append("SOURCES.json")
        if bad:
            print("out of date: " + ", ".join(bad) + " (run android/scripts/copy_search_rules.py)")
            return 1
        print("all copies match packbuilder/")
        return 0
    (DEST / "SOURCES.json").write_bytes(text.encode("utf-8"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
