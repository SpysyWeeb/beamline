"""Retired (round 3): this tool assembled format-2 packs, which the app now refuses
("manifest_version 2 is an old pack format; rebuild the pack").

Build a format-3 pack (docs/pack-format.md) with the pack assembler instead, from the repo root:

    python packbuilder/pack/assemble.py pa          # data/r3-packs/us-pa/ and us-pa-2026-11-14.tar
    python packbuilder/pack/assemble.py oh
    python android/scripts/read_pack.py data/r3-packs/us-pa-2026-11-14.tar --verify

The format-2 tool is in git history (commit 11549aa and earlier) for anyone who needs to read how
the round-2 packs under data/android/ were made.
"""
import sys

if __name__ == "__main__":
    sys.exit(__doc__)
