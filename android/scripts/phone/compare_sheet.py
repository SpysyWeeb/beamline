"""Side-by-side sheets of the zoom-compare screenshots of report 11 (z0-15 map against z0-14).

    .venv/map/Scripts/python android/scripts/phone/compare_sheet.py      (needs Pillow)

Reads data/phone/screenshots/zoom-compare/<place>-z<zoom>-<style>-<tag>.png (written by
run_sets.py shots) and writes, per place and style, into data/phone/screenshots/sheets/:
  <place>-<style>-full.png     rows: zoom 15, 16, 17; columns: v1 (z0-15 map) | v2 (z0-14 map), whole screen at 45 %
  <place>-<style>-detail.png   the same, the middle 640 x 640 px of the screen at full size
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / "data" / "phone" / "screenshots" / "zoom-compare"
OUT = REPO / "data" / "phone" / "screenshots" / "sheets"
TAGS = [("v1", "z0-15 map (installed pack 1)"), ("v2", "z0-14 map (installed pack 2)")]
TOP, BOTTOM = 180, 150  # status bar and gesture bar, cut off


def font(size: int):
    for name in ("arial.ttf", "DejaVuSans.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def sheet(place: str, style: str, zooms: list[str], detail: bool) -> Path | None:
    cells = []
    for z in zooms:
        row = []
        for tag, _ in TAGS:
            p = SRC / f"{place}-z{z}-{style}-{tag}.png"
            if not p.exists():
                return None
            im = Image.open(p).convert("RGB")
            w, h = im.size
            if detail:
                cx, cy = w // 2, (TOP + h - BOTTOM) // 2
                im = im.crop((cx - 320, cy - 320, cx + 320, cy + 320))
            else:
                im = im.crop((0, TOP, w, h - BOTTOM))
                im = im.resize((int(im.width * 0.45), int(im.height * 0.45)), Image.LANCZOS)
            row.append(im)
        cells.append((z, row))
    cw, ch = cells[0][1][0].size
    pad, head, side = 16, 70, 90
    W = side + 2 * cw + 3 * pad
    H = head + len(cells) * (ch + pad) + pad
    bg = (24, 24, 24) if style == "night" else (240, 240, 240)
    fg = (230, 230, 230) if style == "night" else (20, 20, 20)
    s = Image.new("RGB", (W, H), bg)
    d = ImageDraw.Draw(s)
    f, fs = font(26), font(22)
    d.text((pad, 10), f"{place} {style}{' (middle 640 px, full size)' if detail else ''}", fill=fg, font=f)
    for i, (_, label) in enumerate(TAGS):
        d.text((side + pad + i * (cw + pad), 42), label, fill=fg, font=fs)
    for r, (z, row) in enumerate(cells):
        y = head + r * (ch + pad)
        d.text((pad, y + ch // 2 - 12), f"z{z}", fill=fg, font=f)
        for i, im in enumerate(row):
            s.paste(im, (side + pad + i * (cw + pad), y))
    OUT.mkdir(parents=True, exist_ok=True)
    out = OUT / f"{place}-{style}-{'detail' if detail else 'full'}.png"
    s.save(out, optimize=True)
    return out


def main() -> int:
    zooms = sys.argv[1:] or ["15", "16", "17"]
    for place in ("erie", "pgh"):
        for style in ("day", "night"):
            for detail in (False, True):
                out = sheet(place, style, zooms, detail)
                print(out.relative_to(REPO).as_posix() if out else f"{place} {style}: screenshots missing")
    return 0


if __name__ == "__main__":
    sys.exit(main())
