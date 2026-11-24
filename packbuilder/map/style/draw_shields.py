"""Draw the US route shield and the Pennsylvania keystone as SVG templates.

Usage (from the repo root; standard library only):
    python packbuilder/map/style/draw_shields.py

Writes assets/shields/beamline/US_US-<n>char.svg and US_PA-<n>char.svg for
n = 1..5 (the width variants the bundled sheet uses for 1 to 5 characters).
The shapes are drawn here from the published sign dimensions, not copied from
any artwork:

  US route (M1-4): PennDOT Publication 236 (March 2026), sheet M1-4, sign
    24 x 24 in: top peaks 5.0 in from the edges, top scallops of radius 5.4 in,
    45-degree shoulders, a waist and a round lower bulge, bottom point at the
    centre. The 30 x 24 in sheet for three digits keeps the sides and widens the
    top scallops and the bottom.
  Pennsylvania route (M1-5): Pub 236 sheet M1-5, sign 24 x 24 in: keystone top
    1.0 in below the sign edge, top corners 5.0 in from the edges, shoulders
    5.0 in below the sign edge, bottom corners 3.6 in from the edges and 1.0 in
    above the bottom edge, every side at 80.5 degrees to the horizontal. The
    30 x 24 in sheet for three digits widens the shape, keeping these offsets.

For a map label only the shape is kept (no black sign background): white
shape, thin dark outline, dark numerals. Template colours: #000 = outline
(flavor pair[0]), #fff = fill (pair[1]); build_sprites.py recolours them.
Height 20 px plus 0.5 px for the outline on each side (21 px at 1x, the height
of the bundled Interstate shield). The numerals are not part of the sprite:
the style draws shield_text on top.
"""
import math
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
OUT = REPO / "assets" / "shields" / "beamline"
HEADER = ("Drawn for Beamline by packbuilder/map/style/draw_shields.py from the sign dimensions in "
          "PennDOT Publication 236 ({sheet}). MIT License; see assets/LICENSES.md for the basis. "
          "Edit the script, not this file.")

# sign widths (inches) per character count: 1-2 chars use the 24 in sheet
# (1 char slightly narrower), 3 chars the 30 in sheet (US a little wider), 4-5 chars are
# widened until 10 px Noto Sans Medium numerals fit (checked on the shield board).
US_WIDTHS = {1: 21.5, 2: 23.2, 3: 31.5, 4: 43.0, 5: 51.0}   # shield width without the 0.4 in margins
PA_SIGN = {1: 22.0, 2: 24.0, 3: 30.0, 4: 43.0, 5: 51.0}      # sign width A


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def us_shield(w: float) -> tuple[str, float, float]:
    """US route shield outline in inches (origin = shield top-left), width w, height 23.2."""
    h = 23.2
    c = w / 2
    peak = 4.6
    sag = 1.29                       # depth of the top scallops (5.4 in radius over 7 in at 24 in)
    chord = c - peak
    r_top = (chord * chord / 4 + sag * sag) / (2 * sag)
    sh_y = 4.5                       # shoulder corner (45 degrees from the peak)
    bl_x, bl_y = 6.6, 21.1           # where the lower bulge meets the bottom curve
    bot_y = 22.95                    # bottom point
    d = [
        f"M{f(peak)},0",
        f"A{f(r_top)},{f(r_top)} 0 0 0 {f(c)},0.35",
        f"A{f(r_top)},{f(r_top)} 0 0 0 {f(w - peak)},0",
        f"L{f(w - 0.2)},{f(sh_y)}",
        f"C{f(w - 1.15)},{f(6.0)} {f(w - 1.75)},{f(7.8)} {f(w - 1.6)},{f(9.3)}",
        f"C{f(w - 1.45)},{f(10.8)} {f(w)},{f(11.6)} {f(w)},{f(13.3)}",
        f"C{f(w)},{f(17.6)} {f(w - 3.2)},{f(20.5)} {f(w - bl_x)},{f(bl_y)}",
        f"Q{f(c + (c - bl_x) * 0.35)},{f(21.3)} {f(c)},{f(bot_y)}",
        f"Q{f(c - (c - bl_x) * 0.35)},{f(21.3)} {f(bl_x)},{f(bl_y)}",
        f"C{f(3.2)},{f(20.5)} 0,{f(17.6)} 0,{f(13.3)}",
        f"C0,{f(11.6)} {f(1.45)},{f(10.8)} {f(1.6)},{f(9.3)}",
        f"C{f(1.75)},{f(7.8)} {f(1.15)},{f(6.0)} {f(0.2)},{f(sh_y)}",
        "Z",
    ]
    return " ".join(d), w, h


def keystone(a: float) -> tuple[str, float, float]:
    """PA keystone outline in inches, origin = shape top-left; a = sign width."""
    run = 1 / math.tan(math.radians(80.5))      # horizontal run per inch of height
    top, shoulder, bottom = 1.0, 5.0, 23.0       # y on the 24 in sign
    sh_out = 3.6 - (bottom - shoulder) * run     # outer shoulder corner x (0.59 in)
    sh_in = 5.0 + (shoulder - top) * run         # inner shoulder corner x (5.67 in)
    pts = [(5.0, top), (a - 5.0, top), (a - sh_in, shoulder), (a - sh_out, shoulder),
           (a - 3.6, bottom), (3.6, bottom), (sh_out, shoulder), (sh_in, shoulder)]
    pts = [(x - sh_out, y - top) for x, y in pts]
    d = "M" + " L".join(f"{f(x)},{f(y)}" for x, y in pts) + " Z"
    return d, a - 2 * sh_out, bottom - top


def write(name: str, d: str, w_in: float, h_in: float, sheet: str) -> None:
    s = 20.0 / h_in                  # 20 px tall at 1x
    wpx, hpx = w_in * s, 20.0
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {f(wpx + 1)} {f(hpx + 1)}">\n'
           f"  <!-- {HEADER.format(sheet=sheet)} -->\n"
           f'  <path transform="translate(0.5 0.5) scale({f(s)})" d="{d}" fill="#fff" stroke="#000" '
           f'stroke-width="{f(1 / s)}" stroke-linejoin="round"/>\n</svg>\n')
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / name).write_text(svg, encoding="utf-8", newline="\n")
    print(f"{name}: {wpx + 1:.1f} x {hpx + 1:.0f} px at 1x")


def main() -> None:
    for n, w in US_WIDTHS.items():
        d, wi, hi = us_shield(w)
        write(f"US_US-{n}char.svg", d, wi, hi, "sheet M1-4, U.S. Route Marker")
    for n, a in PA_SIGN.items():
        d, wi, hi = keystone(a)
        write(f"US_PA-{n}char.svg", d, wi, hi, "sheet M1-5, Pennsylvania Route Marker")


if __name__ == "__main__":
    main()
