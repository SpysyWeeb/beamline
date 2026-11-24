"""Export the position-marker pieces as Android PNGs (1x to 4x) and render the composed states.

Usage (from the repo root, after `python tools/fetch_art_tools.py`):
    .venv/art/Scripts/python packbuilder/map/style/build_markers.py [--route-candidate A|B|C]

Reads  assets/markers/*.svg (vector masters, see assets/markers/SPEC.md)
Writes data/art/markers/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/marker_<piece>.png
       data/art/markers/states/<state>@3x.png  (pieces stacked as the app stacks them)
       data/art/markers/markers.json           (sizes in dp and px, bytes, layer order)

Every piece is drawn on a square canvas centred on the anchor (the vehicle
position), so the app can stack and rotate pieces about the image centre.
The SVGs declare their size in dp (1 sketch unit = 0.8 dp); resvg renders them
at zoom 1, 1.5, 2, 3 and 4 for the Android density buckets.

The puck and its cone are "in the route blue". The masters carry the sketch's
blue, which is route candidate A (assets/route_colors.json). With
--route-candidate B (or C) the puck and cone are exported in that candidate's
day and night fill instead; the masters are not changed.
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[3]
RESVG = REPO / "tools" / "bin" / "resvg" / ("resvg.exe" if sys.platform == "win32" else "resvg")
SRC = REPO / "assets" / "markers"
OUT = REPO / "data" / "art" / "markers"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# bottom to top, as the app draws them (see SPEC.md)
STATES = {
    "browse_day": ["puck_cone_day", "puck_day"],
    "browse_night": ["puck_cone_night", "puck_night"],
    "browse_stale": ["puck_stale"],
    "drive_day_moving": ["arrow"],
    "drive_day_stopped": ["arrow_brake_glow_day", "arrow", "arrow_brakes_lit"],
    "drive_night_moving": ["arrow_beam", "arrow"],
    "drive_night_stopped": ["arrow_beam", "arrow_brake_glow_night", "arrow", "arrow_brakes_lit"],
    "drive_stale": ["arrow_stale"],
}


def render(svg: Path, zoom: float, png: Path) -> None:
    png.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(RESVG), "-z", str(zoom), str(svg), str(png)], check=True)


SKETCH_BLUE = {"day": "#5BAAFF", "night": "#4B96EE"}   # puck colours of the approved sketch


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--route-candidate", choices=["A", "B", "C"])
    a = ap.parse_args()
    if not RESVG.exists():
        raise SystemExit("resvg missing: run python tools/fetch_art_tools.py")
    t0 = time.time()
    pieces = sorted(p.stem for p in SRC.glob("*.svg"))
    record = {"pieces": {}, "states": STATES, "route_candidate": a.route_candidate or "sketch (= A)"}
    tmp = REPO / "data" / "art" / "build" / "markers"   # recoloured copies for --route-candidate
    tmp.mkdir(parents=True, exist_ok=True)
    cand = None
    if a.route_candidate:
        cand = json.loads((REPO / "assets" / "route_colors.json").read_text(encoding="utf-8"))["candidates"][a.route_candidate]
    for name in pieces:
        svg = SRC / f"{name}.svg"
        if cand and name.startswith("puck") and name != "puck_stale":
            theme = "night" if name.endswith("night") else "day"
            text = svg.read_text(encoding="utf-8").replace(SKETCH_BLUE[theme], cand[theme]["fill"])
            svg = tmp / f"{name}.svg"
            svg.write_text(text, encoding="utf-8")
        rec = {}
        for dpi, z in DENSITIES.items():
            png = OUT / "res" / f"drawable-{dpi}" / f"marker_{name}.png"
            render(svg, z, png)
            w, h = Image.open(png).size
            rec[dpi] = {"px": [w, h], "bytes": png.stat().st_size}
        rec["dp"] = rec["mdpi"]["px"]
        record["pieces"][name] = rec
    # composed states at 3x (xxhdpi), centred stacking
    for state, stack in STATES.items():
        imgs = [Image.open(OUT / "res" / "drawable-xxhdpi" / f"marker_{n}.png").convert("RGBA") for n in stack]
        size = max(max(i.size) for i in imgs)
        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        for im in imgs:
            layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            layer.paste(im, ((size - im.width) // 2, (size - im.height) // 2))
            canvas = Image.alpha_composite(canvas, layer)
        dest = OUT / "states" / f"{state}@3x.png"
        dest.parent.mkdir(parents=True, exist_ok=True)
        canvas.save(dest)
    total = sum(r[d]["bytes"] for r in record["pieces"].values() for d in DENSITIES)
    record["total_png_bytes"] = total
    record["seconds"] = round(time.time() - t0, 1)
    (OUT / "markers.json").write_text(json.dumps(record, indent=1), encoding="utf-8")
    for name, r in record["pieces"].items():
        print(f"{name}: {r['dp'][0]} dp square, xxxhdpi {r['xxxhdpi']['px'][0]} px, "
              f"{sum(r[d]['bytes'] for d in DENSITIES)} B over 5 densities")
    print(f"{len(pieces)} pieces x {len(DENSITIES)} densities, {total} B, {record['seconds']} s -> data/art/markers/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
