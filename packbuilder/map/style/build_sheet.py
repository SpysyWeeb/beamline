"""Build the contact sheet for the owner: one self-contained HTML page (no network).

Usage (from the repo root, after build_sprites.py, build_markers.py and
render_art.py views):
    .venv/art/Scripts/python packbuilder/map/style/build_sheet.py

Reads  assets/ (icons, shields, markers, poi_icons.json, route_colors.json),
       data/art/build/<flavor>/*.svg (recoloured icon SVGs from build_sprites.py),
       data/art/markers/states/*.png, data/art/views/*.png,
       packbuilder/map/style-bundle/{day,night}.json (map colours for the metrics)
Writes assets/sheet/index.html          (every image embedded as a data: URI)
       data/art/route/metrics.json      (contrast and colour distance of the route candidates)

Then render it: render_art.py page assets/sheet/index.html data/art/sheet/sheet.png --tiles
"""
import base64
import html
import io
import json
import math
import sys
from datetime import date
from pathlib import Path

from PIL import Image

REPO = Path(__file__).resolve().parents[3]
ART = REPO / "data" / "art"
OUT = REPO / "assets" / "sheet" / "index.html"


def load(p):
    return json.loads(Path(p).read_text(encoding="utf-8"))


# ---------------------------------------------------------------- colour maths
def _lin(h):
    c = [int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    return [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in c]


def contrast(a, b):
    la = sum(w * c for w, c in zip((0.2126, 0.7152, 0.0722), _lin(a)))
    lb = sum(w * c for w, c in zip((0.2126, 0.7152, 0.0722), _lin(b)))
    hi, lo = max(la, lb), min(la, lb)
    return (hi + 0.05) / (lo + 0.05)


def lab(h):
    r, g, b = _lin(h)
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
    f = lambda t: t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
    return 116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))


def de00(c1, c2):
    """CIEDE2000 colour difference (Sharma et al. 2005 formulation)."""
    L1, a1, b1 = lab(c1)
    L2, a2, b2 = lab(c2)
    C1, C2 = math.hypot(a1, b1), math.hypot(a2, b2)
    Cb = (C1 + C2) / 2
    G = 0.5 * (1 - math.sqrt(Cb ** 7 / (Cb ** 7 + 25 ** 7)))
    a1p, a2p = (1 + G) * a1, (1 + G) * a2
    C1p, C2p = math.hypot(a1p, b1), math.hypot(a2p, b2)
    h1p = math.degrees(math.atan2(b1, a1p)) % 360
    h2p = math.degrees(math.atan2(b2, a2p)) % 360
    dLp, dCp = L2 - L1, C2p - C1p
    dh = h2p - h1p
    if C1p * C2p == 0:
        dh = 0
    elif dh > 180:
        dh -= 360
    elif dh < -180:
        dh += 360
    dHp = 2 * math.sqrt(C1p * C2p) * math.sin(math.radians(dh / 2))
    Lbp, Cbp = (L1 + L2) / 2, (C1p + C2p) / 2
    if C1p * C2p == 0:
        hbp = h1p + h2p
    elif abs(h1p - h2p) <= 180:
        hbp = (h1p + h2p) / 2
    elif h1p + h2p < 360:
        hbp = (h1p + h2p + 360) / 2
    else:
        hbp = (h1p + h2p - 360) / 2
    T = (1 - 0.17 * math.cos(math.radians(hbp - 30)) + 0.24 * math.cos(math.radians(2 * hbp))
         + 0.32 * math.cos(math.radians(3 * hbp + 6)) - 0.20 * math.cos(math.radians(4 * hbp - 63)))
    dth = 30 * math.exp(-((hbp - 275) / 25) ** 2)
    RC = 2 * math.sqrt(Cbp ** 7 / (Cbp ** 7 + 25 ** 7))
    SL = 1 + 0.015 * (Lbp - 50) ** 2 / math.sqrt(20 + (Lbp - 50) ** 2)
    SC, SH = 1 + 0.045 * Cbp, 1 + 0.015 * Cbp * T
    RT = -math.sin(math.radians(2 * dth)) * RC
    return math.sqrt((dLp / SL) ** 2 + (dCp / SC) ** 2 + (dHp / SH) ** 2 + RT * (dCp / SC) * (dHp / SH))


# ---------------------------------------------------------------- embedding
def png_uri(path: Path, fmt="PNG", quality=85, scale=1.0) -> str:
    im = Image.open(path)
    if scale != 1.0:
        im = im.resize((round(im.width * scale), round(im.height * scale)), Image.LANCZOS)
    buf = io.BytesIO()
    if fmt == "WEBP":
        im.save(buf, "WEBP", quality=quality, method=6)
        mime = "image/webp"
    else:
        im.save(buf, "PNG", optimize=True)
        mime = "image/png"
    return f"data:{mime};base64," + base64.b64encode(buf.getvalue()).decode()


def svg_uri(path: Path) -> str:
    return "data:image/svg+xml;base64," + base64.b64encode(path.read_bytes()).decode()


def esc(s):
    return html.escape(str(s))


def at_route_zoom(e):
    """A colour, or the last output of a zoom step (style_config "overview_legibility" gives
    roads_highway a low-zoom colour; the route is drawn at the zooms above it)."""
    return e[-1] if isinstance(e, list) and e and e[0] == "step" else e


def map_colours(variant):
    style = load(REPO / "packbuilder" / "map" / "style-bundle" / f"{variant}.json")
    L = {l["id"]: l for l in style["layers"]}
    return {
        "land": L["earth"]["paint"]["fill-color"],
        "water": L["water"]["paint"]["fill-color"],
        "highway": at_route_zoom(L["roads_highway"]["paint"]["line-color"]),
        "major road": L["roads_major"]["paint"]["line-color"],
        "park": L["landuse_urban_green"]["paint"]["fill-color"],
        "rail": L["roads_rail"]["paint"]["line-color"],
    }


NOT_ROADS = ("roads_rail", "roads_runway", "roads_taxiway", "roads_pier")


def road_colours(variant):
    """Every line colour of every road layer (roads_* except rail, runways, taxiways and piers:
    fills and casings of highways, major and minor roads, links, bridges, tunnels), as
    {colour: [layer ids]}."""
    style = load(REPO / "packbuilder" / "map" / "style-bundle" / f"{variant}.json")
    out = {}

    def walk(e, lid):
        if isinstance(e, str) and e.startswith("#") and len(e) == 7:
            out.setdefault(e.lower(), []).append(lid)
        elif isinstance(e, list):
            for x in e:
                walk(x, lid)
    for l in style["layers"]:
        if l["type"] == "line" and l["id"].startswith("roads_") and l["id"] not in NOT_ROADS:
            walk(l["paint"].get("line-color"), l["id"])
    return out


def main() -> int:
    icons = load(REPO / "assets" / "icons" / "icons.json")
    shields = load(REPO / "assets" / "shields" / "shields.json")
    poi = load(REPO / "assets" / "poi_icons.json")
    rc = load(REPO / "assets" / "route_colors.json")
    markers = load(ART / "markers" / "markers.json")
    views = load(ART / "views" / "views.json")
    bad = [n for n, v in views.items() if not v["idle"] or v["errors"] or v["console"] or v["failed_requests"] or v["external_requests"]]
    if bad:
        raise SystemExit(f"views with problems, re-render first: {bad}")

    used_by = {}
    for cid, ic in poi["categories"].items():
        used_by.setdefault(ic, {"cats": [], "kinds": []})["cats"].append(cid)
    for k, ic in poi["map_kinds"].items():
        used_by.setdefault(ic, {"cats": [], "kinds": []})["kinds"].append(k)

    # ------------------------------------------------ route metrics
    metrics = {}
    for th, variant in (("day", "day"), ("night", "night")):
        mc = map_colours(variant)
        roads = road_colours(variant)
        for k, c in rc["candidates"].items():
            f, cs = c[th]["fill"], c[th]["casing"]
            near = min(roads, key=lambda r: de00(f, r))
            metrics.setdefault(k, {})[th] = {
                "fill": f, "casing": cs,
                # CIELAB L* of the fill: higher is lighter
                "lightness_L": round(lab(f)[0], 1),
                "contrast_fill_vs": {n: round(contrast(f, v), 2) for n, v in mc.items()},
                "contrast_casing_vs_land": round(contrast(cs, mc["land"]), 2),
                "de00_fill_vs": {n: round(de00(f, v), 1) for n, v in mc.items()},
                # "highway" above is the fill of roads_highway only; this is the nearest of all road colours
                "de00_fill_vs_nearest_road_colour": {"de00": round(de00(f, near), 1), "colour": near,
                                                     "layers": sorted(set(roads[near]))[:4]},
            }
    names = list(rc["candidates"])
    for th in ("day", "night"):
        for i, a in enumerate(names):
            for b in names[i + 1:]:
                metrics.setdefault("between", {})[f"{a}-{b} {th}"] = round(
                    de00(rc["candidates"][a][th]["fill"], rc["candidates"][b][th]["fill"]), 1)
    (ART / "route").mkdir(parents=True, exist_ok=True)
    (ART / "route" / "metrics.json").write_text(json.dumps(metrics, indent=1), encoding="utf-8")

    parts = []
    P = parts.append
    today = date.today().isoformat()
    P(f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Map artwork sheet</title>
<style>
:root {{ --bg:#f4f4f2; --surface:#ffffff; --ink:#1d2329; --muted:#5d6670; --line:#d9dcdf; --accent:#2a6fd0;
        --day:#e2dfda; --night:#1f1f1f; color-scheme: light; }}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{ --bg:#14171a; --surface:#1c2024; --ink:#e8ecef;
        --muted:#9aa4ad; --line:#30363c; --accent:#7aaaf0; color-scheme: dark; }} }}
:root[data-theme="dark"] {{ --bg:#14171a; --surface:#1c2024; --ink:#e8ecef; --muted:#9aa4ad; --line:#30363c; --accent:#7aaaf0; color-scheme: dark; }}
* {{ box-sizing: border-box; }}
body {{ margin:0; background:var(--bg); color:var(--ink); font:15px/1.45 "Segoe UI", system-ui, sans-serif; }}
main {{ max-width:1180px; margin:0 auto; padding:24px 16px 64px; }}
h1 {{ font-size:28px; margin:0 0 4px; }} h2 {{ font-size:22px; margin:40px 0 6px; padding-top:12px; border-top:1px solid var(--line); }}
h3 {{ font-size:16px; margin:22px 0 6px; }}
p, li {{ max-width:80ch; }} .muted {{ color:var(--muted); }} code {{ font-size:13px; }}
.panel {{ background:var(--surface); border:1px solid var(--line); border-radius:10px; padding:12px; }}
.real img {{ display:block; max-width:100%; height:auto; }}
.boards {{ display:grid; gap:12px; }}
table {{ border-collapse:collapse; width:100%; font-size:13px; }}
th, td {{ border-bottom:1px solid var(--line); padding:4px 6px; text-align:left; vertical-align:middle; }}
.icongrid {{ display:grid; grid-template-columns:repeat(auto-fill, minmax(340px, 1fr)); gap:8px; }}
.icon {{ display:grid; grid-template-columns:auto auto 1fr; gap:8px; align-items:center; background:var(--surface);
        border:1px solid var(--line); border-radius:8px; padding:6px; }}
.tile {{ border-radius:6px; padding:6px; display:flex; gap:8px; align-items:center; }}
.tile.day {{ background:var(--day); }} .tile.night {{ background:var(--night); }}
.icon .meta {{ font-size:12px; line-height:1.3; min-width:0; overflow-wrap:anywhere; }}
.icon .meta b {{ font-size:13px; }}
.new {{ color:#fff; background:#c2410c; border-radius:4px; padding:0 4px; font-size:11px; font-weight:600; }}
.states {{ display:grid; grid-template-columns:repeat(auto-fill, minmax(200px, 1fr)); gap:10px; }}
.state {{ background:var(--surface); border:1px solid var(--line); border-radius:8px; padding:8px; font-size:13px; }}
.state .stage {{ border-radius:6px; display:flex; align-items:center; justify-content:center; overflow:hidden; }}
.state .stage img {{ flex:none; }}
.cand {{ display:grid; grid-template-columns:repeat(auto-fill, minmax(260px, 1fr)); gap:10px; }}
.cand figure {{ margin:0; }} .cand img {{ width:100%; height:auto; border-radius:8px; display:block; }}
.cand figcaption {{ font-size:12px; color:var(--muted); }}
.sw {{ display:inline-block; width:44px; height:14px; border-radius:7px; vertical-align:middle; border:3px solid; }}
.scroll {{ overflow-x:auto; }}
</style>
</head>
<body><main>
<h1>Map artwork: contact sheet for approval</h1>
<p class="muted">Beamline, built {today} by <code>packbuilder/map/style/build_sheet.py</code>. Everything on this page is embedded; it needs no network.
Real size means 1 CSS pixel = 1 dp: on a phone the icons are drawn at this size. The map images are renders of the
regenerated style bundle (MapLibre GL JS, headless Edge) over the Pennsylvania pack.</p>
<p>What to approve: the new icons (marked <span class="new">new</span>), the US route and Pennsylvania keystone shields,
the position-marker artwork, and one of the three route blues (A, B or C).</p>
""")

    # ------------------------------------------------ icons
    n_new = sum(1 for s in icons["icons"].values() if s["source"] == "beamline")
    P(f"""<h2 id="icons">1. Icons ({len(icons['icons'])} in the sprite, {n_new} new)</h2>
<p>Rounded square, coloured outline, pale fill, one flat symbol, colour by category. Upstream icons come from the source
of the bundled set (Protomaps <code>refill.svg</code>, derived from tangrams/icons, MIT) and keep its exact frame; the
<span class="new">new</span> ones are drawn here in the same 18 &times; 18 frame. Below: every icon as MapLibre draws it
from the sprite sheet, at real size, on the day and night map.</p>
<div class="boards">
<div class="panel real"><img alt="All icons, day map, real size" width="1080" src="{png_uri(ART / 'views' / 'board-icons-day.png')}"></div>
<div class="panel real"><img alt="All icons, night map, real size" width="1080" src="{png_uri(ART / 'views' / 'board-icons-night.png')}"></div>
</div>
<h3>Enlarged (4&times;), day and night, with what uses each icon</h3>
<div class="icongrid">""")
    for name, spec in icons["icons"].items():
        if name in ("arrow", "townspot", "capital"):
            continue
        safe = name.replace(":", "_") + ".svg"
        light = svg_uri(ART / "build" / "light" / safe)
        dark = svg_uri(ART / "build" / "dark" / safe)
        u = used_by.get(name, {"cats": [], "kinds": []})
        uses = []
        if u["cats"]:
            uses.append("search: " + ", ".join(u["cats"]))
        if u["kinds"]:
            uses.append("map: " + ", ".join(u["kinds"]))
        if not uses:
            uses.append("stock map layer" if spec["source"] == "upstream" else "")
        tag = ' <span class="new">new</span>' if spec["source"] == "beamline" else ""
        P(f"""<div class="icon"><div class="tile day"><img alt="" width="76" height="76" src="{light}"></div>
<div class="tile night"><img alt="" width="76" height="76" src="{dark}"></div>
<div class="meta"><b>{esc(name)}</b>{tag}<br><span class="muted">{esc(spec['color'])}{' &middot; ' + esc(spec['_note']) if spec.get('_note') else ''}</span><br>{esc('; '.join(uses))}</div></div>""")
    P("</div>")

    # ------------------------------------------------ shields
    P(f"""<h2 id="shields">2. Route shields</h2>
<p>US route (federal M1-4 outline) and Pennsylvania route (M1-5 keystone), drawn here from the sign dimensions in
PennDOT Publication 236; the Interstate and the generic fallback are the bundled ones. Five widths each, picked by the
length of the road's <code>shield_text</code>. Real size, numerals drawn by MapLibre (Noto Sans Medium 10).</p>
<div class="boards" style="grid-template-columns:repeat(auto-fit, minmax(300px, 1fr))">
<div class="panel real"><img alt="Shields, day" width="520" src="{png_uri(ART / 'views' / 'board-shields-day.png')}"></div>
<div class="panel real"><img alt="Shields, night" width="520" src="{png_uri(ART / 'views' / 'board-shields-night.png')}"></div>
</div>
<h3>Enlarged (4&times;)</h3><div class="panel scroll"><table><tr><th>shield</th>""")
    for n in range(1, 6):
        P(f"<th>{n} char</th>")
    P("</tr>")
    for sh in ("US:US", "US:PA", "US:I", "generic_shield"):
        for flavor, bgc in (("light", "var(--day)"), ("dark", "var(--night)")):
            P(f"<tr><td>{esc(sh)} ({'day' if flavor == 'light' else 'night'})</td>")
            for n in range(1, 6):
                f = ART / "build" / flavor / f"{sh.replace(':', '_')}-{n}char.svg"
                vb = f.read_text(encoding="utf-8").split('viewBox="')[1].split('"')[0].split()
                w = float(vb[2]) * 4
                P(f'<td style="background:{bgc}"><img alt="" width="{w:.0f}" src="{svg_uri(f)}"></td>')
            P("</tr>")
    P("</table></div>")
    P("<h3>Which network gets which shield</h3><table><tr><th>shield</th><th>networks (from the tiles)</th><th>numerals day / night</th></tr>")
    for r in shields["networks"]:
        P(f"<tr><td>{esc(r['shield'])}</td><td>{esc(', '.join(r['networks']))}</td><td>{r['text_color']['day']} / {r['text_color']['night']}</td></tr>")
    fb = shields["fallback"]
    P(f"<tr><td>{esc(fb['shield'])}</td><td>every other network ({esc(shields['_not_drawn_yet'])})</td><td>{fb['text_color']['day']} / {fb['text_color']['night']}</td></tr></table>")
    P(f"""<h3>On the map near Erie (z12.3)</h3><div class="boards" style="grid-template-columns:repeat(auto-fit, minmax(320px, 1fr))">
<figure class="panel" style="margin:0"><img alt="Erie shields day" style="width:100%" src="{png_uri(ART / 'views' / 'check-shields-erie-day.png', 'WEBP', 82, 0.75)}"></figure>
<figure class="panel" style="margin:0"><img alt="Erie shields night" style="width:100%" src="{png_uri(ART / 'views' / 'check-shields-erie-night.png', 'WEBP', 82, 0.75)}"></figure></div>""")

    # ------------------------------------------------ markers
    P("""<h2 id="markers">3. Position markers</h2>
<p>Built from the approved sketch (<code>docs/design/position-markers.html</code>): its path data and colours, cut into
pieces the app stacks. Real size below (1 CSS px = 1 dp; the arrow is 43 dp wide), then twice as large. The stale
variants are new: neutral grey, no cone, no lights, for when the position is old.</p>
<div class="states">""")
    labels = {"browse_day": "Browsing, day (cone pointing north)", "browse_night": "Browsing, night", "browse_stale": "Browsing, stale position",
              "drive_day_moving": "Driving, day, moving", "drive_day_stopped": "Driving, day, stopped (brake lenses lit)",
              "drive_night_moving": "Driving, night, moving (headlight beam)", "drive_night_stopped": "Driving, night, stopped",
              "drive_stale": "Driving, stale position"}
    # windows around the anchor (the image centre), in dp: x from -w to +w, y from top to bottom
    windows = (("real size", 1, (90, -175, 65)), ("2&times;", 2, (46, -60, 52)))
    for zoom_label, scale, (wx, top, bottom) in windows:
        for state, stack in markers["states"].items():
            im = Image.open(ART / "markers" / "states" / f"{state}@3x.png")
            cx, cy = im.width / 2, im.height / 2
            crop = im.crop((round(cx - wx * 3), round(cy + top * 3), round(cx + wx * 3), round(cy + bottom * 3)))
            buf = io.BytesIO()
            crop.save(buf, "PNG", optimize=True)
            uri = "data:image/png;base64," + base64.b64encode(buf.getvalue()).decode()
            bg = "var(--night)" if "night" in state else "var(--day)"
            w, h = 2 * wx * scale, (bottom - top) * scale
            P(f"""<div class="state"><div class="stage" style="background:{bg};height:{h}px"><img alt="" width="{w}" height="{h}" src="{uri}"></div>
<b>{labels[state]}</b> <span class="muted">({zoom_label})</span><br><span class="muted">{esc(' + '.join(stack))}</span></div>""")
    P("</div>")
    P(f"""<h3>On the map</h3><div class="cand">
<figure><img alt="" src="{png_uri(ART / 'views' / 'check-puck-erie-day.png', 'WEBP', 82)}"><figcaption>Browsing puck, day, downtown Erie</figcaption></figure>
<figure><img alt="" src="{png_uri(ART / 'views' / 'check-puck-erie-night.png', 'WEBP', 82)}"><figcaption>Browsing puck, night</figcaption></figure>
<figure><img alt="" src="{png_uri(ART / 'views' / 'check-stopped-exit24-night.png', 'WEBP', 82)}"><figcaption>Driving, night, stopped, tilted: beam, glow and lit lenses</figcaption></figure>
</div>
<p class="muted">Anchor, sizes, layer order and the tilted view: <code>assets/markers/SPEC.md</code>.</p>""")

    # ------------------------------------------------ route blues
    P("""<h2 id="route">4. Route line: three candidate blues</h2>
<p>Each candidate on the day and night map: downtown Erie (top-down) and I-90 exit 24 to Peach St / US 19 (tilted,
heading up, as in guidance). The faded part behind the arrow is the part already driven; the grey line is an
alternative route. Line widths follow <code>assets/route_colors.json</code>.</p>""")
    for k, c in rc["candidates"].items():
        m = metrics[k]
        P(f"""<h3>{k}: {esc(c['name'])} <span class="sw" style="background:{c['day']['fill']};border-color:{c['day']['casing']}"></span>
<span class="sw" style="background:{c['night']['fill']};border-color:{c['night']['casing']}"></span></h3>
<p class="muted">{esc(c['note'])}. Day fill {c['day']['fill']} / casing {c['day']['casing']}; night fill {c['night']['fill']} / casing {c['night']['casing']}.
Lightness of the fill (CIELAB L*, higher is lighter): day {m['day']['lightness_L']}, night {m['night']['lightness_L']}.
Colour distance (CIEDE2000) of the fill from water: day {m['day']['de00_fill_vs']['water']}, night {m['night']['de00_fill_vs']['water']};
from the highway fill: day {m['day']['de00_fill_vs']['highway']}, night {m['night']['de00_fill_vs']['highway']};
from the nearest of all road fill and casing colours: day {m['day']['de00_fill_vs_nearest_road_colour']['de00']}
({m['day']['de00_fill_vs_nearest_road_colour']['colour']}), night {m['night']['de00_fill_vs_nearest_road_colour']['de00']}
({m['night']['de00_fill_vs_nearest_road_colour']['colour']});
from the rail line: day {m['day']['de00_fill_vs']['rail']}, night {m['night']['de00_fill_vs']['rail']}.
Contrast against the land: fill day {m['day']['contrast_fill_vs']['land']}:1, casing day {m['day']['contrast_casing_vs_land']}:1, fill night {m['night']['contrast_fill_vs']['land']}:1.</p>
<div class="cand">""")
        for loc in ("erie-downtown", "i90-exit24"):
            for th in ("day", "night"):
                f = ART / "views" / f"route-{k}-{th}-{loc}.png"
                P(f'<figure><img alt="" src="{png_uri(f, "WEBP", 82)}"><figcaption>{k}, {th}, {"downtown Erie" if loc == "erie-downtown" else "I-90 exit 24, tilted"}</figcaption></figure>')
        P("</div>")
    cands = list(rc["candidates"])
    lo_water = min(metrics[k]["day"]["de00_fill_vs"]["water"] for k in cands)
    lo_road = min(metrics[k][th]["de00_fill_vs_nearest_road_colour"]["de00"] for k in cands for th in ("day", "night"))
    lo_rail = min(metrics[k][th]["de00_fill_vs"]["rail"] for k in cands for th in ("day", "night"))
    by_light = sorted(cands, key=lambda k: -metrics[k]["day"]["lightness_L"])
    P(f"""<p class="muted">A difference of 10 or more in CIEDE2000 is an obvious difference in colour; all three are at least
{lo_water:.0f} from the day water, at least {lo_road:.0f} from every road colour of the map (fills and casings of
highways, major and minor roads, links, bridges and tunnels: white and light grey by day, dark grey at night) and at
least {lo_rail:.0f} from the grey-blue rail line. Lightest to darkest by day: {', '.join(by_light)}. The casing carries
the line's edge on the light day map (fill contrast is low there by design: a light blue); at night the fill alone stands
out.</p>""")

    # ------------------------------------------------ footer
    P("""<h2 id="notes">5. Licences and basis</h2><ul>
<li>Upstream icons, the Interstate and generic shields: Protomaps <code>refill.svg</code> (BSD-3-Clause code, CC0 design),
derived from tangrams/icons, MIT, Copyright (c) 2017 Mapzen. Notice kept in the style bundle.</li>
<li>New icons, US and Pennsylvania shields, markers: drawn for Beamline, MIT.</li>
<li>US route and Pennsylvania route shapes follow public sign specifications: MUTCD 11th Edition, Section 1D.06, says the
devices in the Manual are not protected by patent, trademark or copyright, except the Interstate Shield and a few named
items; the shapes were drawn from PennDOT Publication 236 sheets M1-4 and M1-5. Details and open points:
<code>docs/experiments/06-map-art.md</code>.</li>
<li>Map data &copy; OpenStreetMap contributors (ODbL).</li></ul>
</main></body></html>
""")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("".join(parts), encoding="utf-8", newline="\n")
    print(f"{OUT.relative_to(REPO).as_posix()}: {OUT.stat().st_size} bytes")
    print(f"route metrics -> data/art/route/metrics.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
