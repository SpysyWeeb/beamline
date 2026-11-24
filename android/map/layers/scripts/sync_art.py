"""Copy the position-marker PNGs and the route colours into :map:layers (track M).

    python android/map/layers/scripts/sync_art.py           # copy what differs
    python android/map/layers/scripts/sync_art.py --check   # compare only; exit 1 when a copy differs

Sources (single source of truth; the module never edits its copies):
  data/art/markers/res/drawable-<density>/marker_<piece>.png
      written by `.venv/art/Scripts/python packbuilder/map/style/build_markers.py --route-candidate B`
      from assets/markers/*.svg (assets/markers/SPEC.md); data/ is not in git, so run that first
  data/art/markers/markers.json   (which route candidate the export is in)
  assets/route_colors.json        (route line colours; "selected" names the candidate the app draws)

Copies (in git, docs/architecture.md decision 6.21):
  android/map/layers/src/main/res/drawable-<density>/marker_<piece>.png   (Android picks the density
      and MapLibre's Style.addImage draws a bitmap at its density, so each piece shows at its dp size)
  android/map/layers/src/main/res/raw/route_colors.json                   (R.raw.route_colors)

Refused: an export whose route candidate is not the one route_colors.json selects (the puck and cone
are in the route blue). A marker PNG in the module that the export does not have is reported as a
difference (and removed when copying). Bytes are compared exactly; route_colors.json with LF line
endings. Standard library only.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
EXPORT = REPO / "data" / "art" / "markers"
COLORS = REPO / "assets" / "route_colors.json"
MODULE = REPO / "android" / "map" / "layers" / "src" / "main"
DENSITIES = ["mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"]
# assets/markers/SPEC.md: the eleven pieces
PIECES = [
    "arrow", "arrow_beam", "arrow_brake_glow_day", "arrow_brake_glow_night", "arrow_brakes_lit", "arrow_stale",
    "puck_cone_day", "puck_cone_night", "puck_day", "puck_night", "puck_stale",
]


def rel(p: Path) -> str:
    return p.relative_to(REPO).as_posix()


def pairs() -> list[tuple[Path, Path, bytes | None]]:
    """(source, copy, the bytes to write or None to read the source as it is)."""
    out: list[tuple[Path, Path, bytes | None]] = []
    for d in DENSITIES:
        for piece in PIECES:
            name = f"marker_{piece}.png"
            out.append((EXPORT / "res" / f"drawable-{d}" / name, MODULE / "res" / f"drawable-{d}" / name, None))
    colors = COLORS.read_bytes().replace(b"\r\n", b"\n")
    out.append((COLORS, MODULE / "res" / "raw" / "route_colors.json", colors))
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="compare only; exit 1 when a copy differs")
    a = ap.parse_args()

    selected = json.loads(COLORS.read_text(encoding="utf-8"))["selected"]
    record = EXPORT / "markers.json"
    if not record.is_file():
        print(f"{rel(record)} missing: run .venv/art/Scripts/python packbuilder/map/style/build_markers.py "
              f"--route-candidate {selected} first (data/ is not in git)", file=sys.stderr)
        return 2
    candidate = json.loads(record.read_text(encoding="utf-8")).get("route_candidate")
    if candidate != selected:
        print(f"the marker export is in route candidate {candidate!r}, route_colors.json selects {selected!r}: "
              f"run build_markers.py --route-candidate {selected}", file=sys.stderr)
        return 2

    differ = 0
    wanted: set[Path] = set()
    for src, dst, data in pairs():
        wanted.add(dst)
        if data is None:
            if not src.is_file():
                print(f"MISSING {rel(src)}", file=sys.stderr)
                differ += 1
                continue
            data = src.read_bytes()
        same = dst.is_file() and dst.read_bytes() == data
        if a.check:
            print(("same    " if same else "DIFFERS ") + rel(dst))
            differ += not same
        elif not same:
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(data)
            print(f"copied  {rel(src)} -> {rel(dst)}")
        else:
            print(f"same    {rel(dst)}")
    # PNGs in the module that the export does not have (a piece renamed or removed)
    for d in DENSITIES:
        for extra in sorted((MODULE / "res" / f"drawable-{d}").glob("marker_*.png")):
            if extra in wanted:
                continue
            if a.check:
                print(f"EXTRA   {rel(extra)}")
                differ += 1
            else:
                extra.unlink()
                print(f"removed {rel(extra)}")
    n = len(DENSITIES) * len(PIECES)
    if a.check:
        print(f"{n} marker PNGs and route_colors.json checked (route candidate {selected}): "
              + ("all the same" if not differ else f"{differ} differ"))
    return 1 if differ else 0


if __name__ == "__main__":
    sys.exit(main())
