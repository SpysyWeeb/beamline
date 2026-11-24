"""Render the art check images: map views with the route candidates and markers, and HTML pages.

Usage (from the repo root):
    .venv/art/Scripts/python packbuilder/map/style/render_art.py views [--only NAME ...]
    .venv/art/Scripts/python packbuilder/map/style/render_art.py page assets/sheet/index.html data/art/sheet/sheet.png
        [--width 1280] [--dark] [--clip-height N --tiles]

views: every view in packbuilder/map/style/art_views.json is opened in
  packbuilder/map/style/render/art_view.html (or render/board.html for "page": "board",
  a grid of every icon or shield drawn by MapLibre from the bundle's sprite and glyphs) (MapLibre GL JS from viewer/vendor,
  the regenerated style bundle, a PA pack from data/map/packs, the sample routes
  from data/art/route/routes.geojson, marker PNGs from data/art/markers) and saved
  as data/art/views/<name>.png.
page: a local HTML file is opened with every non-local request blocked and
  saved as one full-page PNG (or, with --tiles, as numbered PNGs of at most
  --clip-height CSS pixels each, cut in the gaps between headings, paragraphs,
  panels, cards, figures and table rows, so long pages stay readable and no
  tile starts or ends inside an image).

Like packbuilder/map/screenshots.py (read-only, not used), it drives the
installed Microsoft Edge headless through Playwright with WebGL on SwiftShader,
and exits with 1 if a view logs a MapLibre error or console message, has a
failed or 4xx/5xx request, a request that leaves 127.0.0.1, or never gets idle.
viewer/serve.py is imported read-only for its Range-capable handler; the
mounts are set here: /style/ /packs/ /viewer/ /art/ /assets/ /data/.
"""
import argparse
import gzip
import json
import re
import socket
import struct
import sys
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlencode

from playwright.sync_api import sync_playwright

REPO = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(REPO / "viewer"))
import serve  # noqa: E402  (viewer/serve.py, used read-only)

ARGS = ["--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"]


class Server(ThreadingHTTPServer):
    # the default listen backlog of 5 overflowed once under load (ERR_CONNECTION_REFUSED
    # on a glyph request while the browser opened many connections at once)
    request_queue_size = 128
    daemon_threads = True


def start_server():
    serve.MOUNTS.clear()
    serve.MOUNTS.update({
        "/style/": REPO / "packbuilder" / "map" / "style-bundle",
        "/packs/": REPO / "data" / "map" / "packs",
        # a view's "pack": "v2/<file>" is read from data/map/packs-v2 (the round-2 builds: the z14
        # state packs and the public-domain overview)
        "/packs/v2/": REPO / "data" / "map" / "packs-v2",
        "/viewer/": REPO / "viewer",
        "/art/": HERE / "render",
        "/assets/": REPO / "assets",
        "/data/": REPO / "data" / "art",
    })
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    srv = Server(("127.0.0.1", port), serve.Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, port


DEFAULT_PACK = "pa-261114-z0-15.pmtiles"  # the default of render/art_view.js and board.js


def pack_path(pack: str) -> Path:
    return REPO / "data" / "map" / ("packs-v2/" + pack[3:] if pack.startswith("v2/") else f"packs/{pack}")


def pack_credit(path: Path) -> str | None:
    """The credit in a PMTiles file's own metadata ("attribution", HTML removed), or None. The
    public-domain overview says "Natural Earth; US Census Bureau", as the app's credit line does
    (android/scripts/sync_assets.py records the same text in us-base.json)."""
    with open(path, "rb") as f:
        h = f.read(127)
        if len(h) < 127 or h[:7] != b"PMTiles" or h[7] != 3:
            return None
        meta_off, meta_len = struct.unpack_from("<QQ", h, 24)
        f.seek(meta_off)
        raw = f.read(meta_len)
    meta = json.loads(gzip.decompress(raw) if h[97] == 2 else raw)
    return re.sub(r"<[^>]+>", "", str(meta.get("attribution") or "")).strip() or None


def missing_inputs(views) -> list:
    """Inputs the views need, checked before the browser starts (a missing one used to
    make every affected view wait out the whole --timeout and come out blank)."""
    need = {
        "packbuilder/map/style-bundle/day.json": "node packbuilder/map/style/generate_style.mjs",
        "packbuilder/map/style-bundle/night.json": "node packbuilder/map/style/generate_style.mjs",
        "viewer/vendor/maplibre-gl/maplibre-gl.mjs": "the viewer's vendored MapLibre (tools/fetch_map_vendor.py)",
    }
    for v in views:
        pack = v.get("pack", DEFAULT_PACK)
        need[pack_path(pack).relative_to(REPO).as_posix()] = (
            "the map track's pack (packbuilder/map/build_pack.py, overview/build_overview.py)")
        if v.get("sample"):
            need["data/art/route/routes.geojson"] = "packbuilder/map/style/sample_routes.py (build_art.py --with-routes)"
        if v.get("marker", "arrow") != "none" and v.get("sample"):
            need["data/art/markers/res/drawable-xxhdpi/marker_arrow.png"] = "packbuilder/map/style/build_markers.py"
    return [f"{p}  <- {how}" for p, how in need.items() if not (REPO / p).exists()]


def run_views(a) -> int:
    views = json.loads((HERE / "art_views.json").read_text(encoding="utf-8"))["views"]
    if a.only:
        views = [v for v in views if v["name"] in a.only]
    gone = missing_inputs(views)
    if gone:
        print("missing inputs, nothing rendered:\n  " + "\n  ".join(gone))
        return 2
    out = REPO / "data" / "art" / "views"
    out.mkdir(parents=True, exist_ok=True)
    srv, port = start_server()
    local = f"http://127.0.0.1:{port}/"
    summary, bad = {}, []
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(channel=a.channel, headless=True, args=ARGS)
            for v in views:
                page = browser.new_page(viewport={"width": v.get("width", 520), "height": v.get("height", 640)},
                                        device_scale_factor=v.get("dpr", 2))
                logs, failed, external = [], [], []
                page.on("console", lambda m: logs.append(f"{m.type}: {m.text}"))
                page.on("pageerror", lambda e: logs.append(f"pageerror: {e}"))
                page.on("response", lambda r: failed.append(f"HTTP {r.status} {r.url.replace(local, '/')}")
                        if r.status >= 400 else None)
                page.on("requestfailed", lambda r: failed.append(f"failed {r.url.replace(local, '/')}"))
                page.on("request", lambda r: external.append(r.url)
                        if not r.url.startswith((local, "data:", "blob:")) else None)
                q = {k: v[k] for k in ("variant", "sample", "cand", "marker", "bearing", "pitch", "tag", "pack", "compass",
                                       "kind", "cols") if k in v}
                if "zoom" in v:
                    q["at"] = f"{v['zoom']}/{v['lat']}/{v['lon']}"
                if v.get("credit_from_pack"):
                    # the map shows the pack's own credit, as the app's credit line does
                    q["credit"] = pack_credit(pack_path(v.get("pack", DEFAULT_PACK))) or ""
                t0 = time.time()
                page.goto(f"{local}art/{v.get('page', 'art_view')}.html?{urlencode(q)}")
                ok = True
                try:
                    # ends early when the page reports a failure (window.__art.failed)
                    page.wait_for_function("window.__art && ((window.__art.ready && window.__art.idle) || window.__art.failed)",
                                           timeout=a.timeout * 1000)
                    ok = not page.evaluate("!!window.__art.failed")
                except Exception:
                    ok = False
                page.wait_for_timeout(400)
                png = out / f"{v['name']}.png"
                page.screenshot(path=str(png))
                st = json.loads(page.evaluate("JSON.stringify(window.__art || {})"))
                console = [l for l in logs if not l.startswith("debug")]
                summary[v["name"]] = {"idle": ok, "seconds": round(time.time() - t0, 1), "errors": st.get("errors", []),
                                      "console": console[:10], "failed_requests": failed[:10], "external_requests": external[:10],
                                      "query": q}
                problems = len(st.get("errors", [])) + len(console) + len(failed) + len(external)
                if problems or not ok:
                    bad.append(v["name"])
                print(f"{v['name']}: idle={ok} {summary[v['name']]['seconds']} s, {problems} problems -> "
                      f"{png.relative_to(REPO).as_posix()}", flush=True)
                page.close()
            browser.close()
    finally:
        srv.shutdown()
    (out / "views.json").write_text(json.dumps(summary, indent=1), encoding="utf-8")
    print(f"{len(views)} views, {len(bad)} with problems: {', '.join(bad) or 'none'}")
    return 1 if bad else 0


ATOMIC = "h1, h2, h3, p, li, .panel, .icon, .state, figure, tr"  # blocks a tile must not cut through


def tile_cuts(page, height: int, clip: int):
    """Tile boundaries (CSS px) of at most `clip` px each, placed in the gaps between blocks:
    the largest y in (top + 0.4 clip, top + clip] that lies inside no ATOMIC element. Returns
    the boundaries and the y of any cut that had to go through an element (none found)."""
    boxes = page.evaluate("""(sel) => [...document.querySelectorAll(sel)].map(e => {
        const r = e.getBoundingClientRect(); return [r.top + scrollY, r.bottom + scrollY]; })""", ATOMIC)
    inside = lambda y: any(t < y < b for t, b in boxes)
    gaps = sorted({round(b) + 1 for _, b in boxes} | {round(t) - 1 for t, _ in boxes})
    cuts, forced = [0], []
    while height - cuts[-1] > clip:
        top = cuts[-1]
        ok = [y for y in gaps if top + 0.4 * clip < y <= top + clip and not inside(y)]
        if ok:
            cuts.append(max(ok))
        else:
            cuts.append(top + clip)
            forced.append(top + clip)
    cuts.append(height)
    return cuts, forced


def run_page(a) -> int:
    src = (REPO / a.html).resolve()
    dest = REPO / a.png
    dest.parent.mkdir(parents=True, exist_ok=True)
    logs, external = [], []
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel=a.channel, headless=True, args=ARGS)
        ctx = browser.new_context(viewport={"width": a.width, "height": 900}, device_scale_factor=a.dpr,
                                  color_scheme="dark" if a.dark else "light")
        page = ctx.new_page()

        def route(r):
            if r.request.url.startswith(("file:", "data:", "blob:")):
                return r.continue_()
            external.append(r.request.url)
            return r.abort()
        page.route("**/*", route)
        page.on("console", lambda m: logs.append(f"{m.type}: {m.text}"))
        page.on("pageerror", lambda e: logs.append(f"pageerror: {e}"))
        page.goto(src.as_uri())
        page.wait_for_load_state("load")
        page.wait_for_timeout(500)
        height = page.evaluate("document.documentElement.scrollHeight")
        if a.tiles:
            cuts, forced = tile_cuts(page, height, a.clip_height)
            for old in dest.parent.glob(f"{dest.stem}-[0-9][0-9].png"):
                old.unlink()  # a run with fewer tiles must not leave stale ones behind
            for n, (top, bottom) in enumerate(zip(cuts, cuts[1:]), 1):
                page.screenshot(path=str(dest.with_name(f"{dest.stem}-{n:02d}.png")), full_page=True,
                                clip={"x": 0, "y": top, "width": a.width, "height": bottom - top})
            print(f"{len(cuts) - 1} tiles of at most {a.clip_height} px from a {height} px page, cut at {cuts[1:-1]}; "
                  f"{'cuts through an element at ' + str(forced) if forced else 'no cut goes through a heading, text block, card, figure or table row'}")
            if forced:
                logs.append(f"tile cut through an element at y={forced}")
        else:
            page.screenshot(path=str(dest), full_page=True)
            print(f"page {height} px -> {dest.relative_to(REPO).as_posix()}")
        browser.close()
    for l in logs:
        print("console:", l)
    for u in external:
        print("blocked external request:", u)
    return 1 if (logs or external) else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--channel", default="msedge",
                        help="Playwright browser channel: msedge (default, the installed Edge on Windows) or "
                             "chromium (Playwright's own build, after `playwright install chromium`; not tried here)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    v = sub.add_parser("views", parents=[common])
    v.add_argument("--only", nargs="*")
    v.add_argument("--timeout", type=float, default=120)
    p = sub.add_parser("page", parents=[common])
    p.add_argument("html")
    p.add_argument("png")
    p.add_argument("--width", type=int, default=1280)
    p.add_argument("--dpr", type=float, default=1)
    p.add_argument("--dark", action="store_true")
    p.add_argument("--tiles", action="store_true")
    p.add_argument("--clip-height", type=int, default=1400)
    a = ap.parse_args()
    return run_views(a) if a.cmd == "views" else run_page(a)


if __name__ == "__main__":
    sys.exit(main())
