"""Take headless screenshots of the offline viewer to check the rendering for real.

Usage (from the repo root):
    .venv/map/Scripts/python packbuilder/map/screenshots.py [--views packbuilder/map/screenshot_views.json]
                                                            [--only NAME ...] [--browser msedge|chrome]

Starts viewer/serve.py on a free port, drives the locally installed Microsoft
Edge (or Chrome) headless through Playwright with WebGL on SwiftShader
(software GL, no GPU), waits until MapLibre reports "idle", and saves
<out>/<name>.png (default data/map/screenshots-v2; packs from data/map/packs-v2
unless --packs). A summary per view goes to <out>/screenshots.json: MapLibre errors, console messages,
every request that failed or answered HTTP 400 or more (for example a glyph
range missing from the style bundle), and every request that left
127.0.0.1. The exit code is 1 if any view has one of these or never got idle.

A view with "file": "data/map/packs/X.pmtiles" also exercises the viewer's
"open a .pmtiles file" path (the panel stays visible for that view).

Second-round view options (handled here, the viewer itself is unchanged):
  "overlay": "lanes"   after the map is idle, add two debug layers on the
                       first pack source: road lines whose width is
                       2.5 px x the "lanes" number (fails if lanes were
                       strings) coloured by lane count, and the turn:lanes
                       strings as labels; then wait for idle again.
  "overlay": "lanesdir"  the lanes overlay plus red ">" marks drawn in each
                       line's own direction on features with a direction-
                       dependent lane value, and a forward / backward /
                       both_ways label (checks the lane-direction fix).
  "encoding": "mlt"    the style templates are served with "encoding": "mlt"
                       on their vector source (MapLibre Tiles packs).
"""
import argparse
import json
import socket
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlencode

from playwright.sync_api import sync_playwright

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "data" / "map" / "screenshots-v2"
PACKS = REPO / "data" / "map" / "packs-v2"

LANES_OVERLAY_JS = """() => {
  const m = window.__map; const st = m.getStyle();
  const src = Object.keys(st.sources).find(k => st.sources[k].type === 'vector' && k !== 'base') || Object.keys(st.sources)[0];
  const turn = ['coalesce', ['get', 'turn:lanes'], ['get', 'turn:lanes:forward'], ['get', 'turn:lanes:backward'], ''];
  m.addLayer({id: 'dbg-lanes', type: 'line', source: src, 'source-layer': 'roads', filter: ['has', 'lanes'],
    layout: {'line-cap': 'round'},
    paint: {'line-color': ['match', ['get', 'lanes'], 1, '#1b9e77', 2, '#d95f02', 3, '#7570b3', 4, '#e7298a', 5, '#66a61e', '#a6761d'],
            'line-width': ['*', 2.5, ['get', 'lanes']], 'line-opacity': 0.55}});
  m.addLayer({id: 'dbg-turn', type: 'symbol', source: src, 'source-layer': 'roads',
    filter: ['any', ['has', 'turn:lanes'], ['has', 'turn:lanes:forward'], ['has', 'turn:lanes:backward']],
    layout: {'symbol-placement': 'line-center', 'text-field': ['concat', ['to-string', ['get', 'lanes']], ': ', turn],
             'text-font': ['Noto Sans Medium'], 'text-size': 12, 'text-allow-overlap': true, 'text-keep-upright': true},
    paint: {'text-color': '#111', 'text-halo-color': '#ffffff', 'text-halo-width': 2}});
  window.__viewer.idle = false;
  return src;
}"""

# "overlay": "lanesdir" (review fix check): the lanes overlay plus, on features with a direction-dependent lane
# value, red ">" marks along the line in the line's own direction (text-keep-upright false) and a label with the
# forward / backward / both_ways values. A reversed line shows its marks against the OSM way's direction.
LANES_DIR_OVERLAY_JS = """() => {
  const m = window.__map; const st = m.getStyle();
  const src = Object.keys(st.sources).find(k => st.sources[k].type === 'vector' && k !== 'base') || Object.keys(st.sources)[0];
  const dir = ['any', ['has', 'turn:lanes'], ['has', 'turn:lanes:forward'], ['has', 'turn:lanes:backward'],
               ['has', 'lanes:forward'], ['has', 'lanes:backward']];
  const s = k => ['to-string', ['coalesce', ['get', k], '-']];
  m.addLayer({id: 'dbg-lanes', type: 'line', source: src, 'source-layer': 'roads', filter: ['has', 'lanes'],
    layout: {'line-cap': 'round'},
    paint: {'line-color': ['match', ['get', 'lanes'], 1, '#1b9e77', 2, '#d95f02', 3, '#7570b3', 4, '#e7298a', 5, '#66a61e', '#a6761d'],
            'line-width': ['*', 2.5, ['get', 'lanes']], 'line-opacity': 0.45}});
  m.addLayer({id: 'dbg-dir', type: 'symbol', source: src, 'source-layer': 'roads', filter: dir,
    layout: {'symbol-placement': 'line', 'symbol-spacing': 70, 'text-field': '>', 'text-font': ['Noto Sans Medium'],
             'text-size': 24, 'text-keep-upright': false, 'text-allow-overlap': true, 'text-ignore-placement': true,
             'text-rotation-alignment': 'map'},
    paint: {'text-color': '#d00000', 'text-halo-color': '#ffffff', 'text-halo-width': 2}});
  m.addLayer({id: 'dbg-dirlabel', type: 'symbol', source: src, 'source-layer': 'roads',
    filter: ['any', dir, ['has', 'lanes:both_ways'], ['has', 'turn:lanes:both_ways']],
    layout: {'symbol-placement': 'line-center', 'text-allow-overlap': true, 'text-keep-upright': true,
             'text-field': ['concat', s('lanes'), ' = fwd ', s('lanes:forward'), ' ', ['coalesce', ['get', 'turn:lanes:forward'], ['get', 'turn:lanes'], ''],
                            ' / bwd ', s('lanes:backward'), ' ', ['coalesce', ['get', 'turn:lanes:backward'], ''],
                            ' / both ', s('lanes:both_ways'), ' ', ['coalesce', ['get', 'turn:lanes:both_ways'], '']],
             'text-font': ['Noto Sans Medium'], 'text-size': 12, 'text-offset': [0, 1.3]},
    paint: {'text-color': '#111', 'text-halo-color': '#ffffff', 'text-halo-width': 2}});
  window.__viewer.idle = false;
  return src;
}"""


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--views", default=str(Path(__file__).with_name("screenshot_views.json")))
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--browser", default="msedge")
    ap.add_argument("--timeout", type=float, default=90)
    ap.add_argument("--packs", default=str(PACKS), help="serve packs from this folder (default data/map/packs-v2)")
    ap.add_argument("--out", default=str(OUT), help="folder for the PNGs and screenshots.json (default data/map/screenshots-v2)")
    ap.add_argument("--prefix", default="", help="prefix for the PNG and summary names (e.g. 'before-')")
    a = ap.parse_args()
    views = json.loads(Path(a.views).read_text())
    if a.only:
        views = [v for v in views if v["name"] in a.only]
    out_dir = Path(a.out).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    port = free_port()
    server = subprocess.Popen([sys.executable, str(REPO / "viewer" / "serve.py"), "--port", str(port),
                               "--packs", a.packs])
    time.sleep(1.0)
    summary_path = out_dir / "screenshots.json"
    summary = json.loads(summary_path.read_text()) if summary_path.exists() else {}
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(
                channel=a.browser, headless=True,
                args=["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"])
            bad_views = []
            for v in views:
                page = browser.new_page(viewport={"width": v.get("width", 1200), "height": v.get("height", 800)},
                                        device_scale_factor=v.get("dpr", 1))
                logs, failed, external = [], [], []
                local = f"http://127.0.0.1:{port}/"
                page.on("console", lambda m: logs.append(f"{m.type}: {m.text}"))
                page.on("pageerror", lambda e: logs.append(f"pageerror: {e}"))
                page.on("response", lambda r: failed.append(f"HTTP {r.status} {r.url.replace(local, '/')}")
                        if r.status >= 400 else None)
                page.on("requestfailed", lambda r: failed.append(f"failed {r.url.replace(local, '/')}: {r.failure}"))
                page.on("request", lambda r: external.append(r.url)
                        if not r.url.startswith((local, "data:", "blob:")) else None)
                if v.get("encoding"):
                    enc = v["encoding"]

                    def add_encoding(route):  # Playwright passes (route) or (route, request) by arity
                        resp = route.fetch()
                        st = resp.json()
                        for src in st["sources"].values():
                            if src.get("type") == "vector":
                                src["encoding"] = enc
                        route.fulfill(response=resp, json=st)
                    page.route("**/style/*.json", add_encoding)
                q = {"packs": ",".join(v["packs"]), "variant": v.get("variant", "day"), "ui": "0",
                     "at": f"{v['zoom']}/{v['lat']}/{v['lon']}"}
                if "file" in v:
                    q.pop("at")
                    q["ui"] = "1"
                for k in ("order", "base", "basemax"):
                    if k in v:
                        q[k] = v[k]
                url = f"http://127.0.0.1:{port}/?{urlencode(q)}"
                t0 = time.time()
                page.goto(url)
                idle_js = "window.__viewer && window.__viewer.idle === true"
                ok = True
                try:
                    page.wait_for_function(idle_js, timeout=a.timeout * 1000)
                    if "file" in v:
                        page.evaluate("window.__viewer.idle = false")
                        page.set_input_files("#file", str(REPO / v["file"]))
                        page.wait_for_timeout(1000)
                        page.wait_for_function(idle_js, timeout=a.timeout * 1000)
                    if v.get("overlay") in ("lanes", "lanesdir"):
                        page.evaluate(LANES_OVERLAY_JS if v["overlay"] == "lanes" else LANES_DIR_OVERLAY_JS)
                        page.evaluate("window.__map.triggerRepaint()")
                        page.wait_for_function(idle_js, timeout=a.timeout * 1000)
                except Exception:
                    ok = False
                page.wait_for_timeout(500)
                name = a.prefix + v["name"]
                png = out_dir / f"{name}.png"
                page.screenshot(path=str(png))
                st = page.evaluate("JSON.stringify(window.__viewer || {})")
                state = json.loads(st)
                gl = page.evaluate("""() => { const c = document.createElement('canvas');
                    const g = c.getContext('webgl2'); if (!g) return 'no webgl2';
                    const d = g.getExtension('WEBGL_debug_renderer_info');
                    return d ? g.getParameter(d.UNMASKED_RENDERER_WEBGL) : 'webgl2'; }""")
                console = [l for l in logs if not l.startswith("debug")]
                view = page.evaluate("""() => { const m = window.__map; if (!m) return null;
                    const c = m.getCenter(); return [m.getZoom(), c.lat, c.lng]; }""")
                summary[name] = {
                    "url": url.replace(f"127.0.0.1:{port}", "127.0.0.1:PORT"), "idle": ok,
                    "seconds": round(time.time() - t0, 1), "errors": state.get("errors", []),
                    "console": console[:20], "failed_requests": failed[:20], "external_requests": external[:20],
                    "final_view_zoom_lat_lon": [round(x, 4) for x in view] if view else None,
                    "renderer": gl, "note": v.get("note", ""),
                }
                problems = len(state.get("errors", [])) + len(console) + len(failed) + len(external)
                if problems or not ok:
                    bad_views.append(name)
                print(f"{name}: idle={ok} {summary[name]['seconds']} s, "
                      f"{len(state.get('errors', []))} errors, {len(console)} console, {len(failed)} failed requests, "
                      f"{len(external)} external -> {png.relative_to(REPO).as_posix()}", flush=True)
                page.close()
            browser.close()
    finally:
        server.terminate()
    summary_path.write_text(json.dumps(summary, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"{len(views)} views, {len(bad_views)} with problems: {', '.join(bad_views) or 'none'}")
    return 1 if bad_views else 0


if __name__ == "__main__":
    sys.exit(main())
