"""Count the POI symbols MapLibre actually places, by layer and kind, at a range of zooms.

Usage (from the repo root):
    .venv/art/Scripts/python packbuilder/map/style/count_pois.py [--out data/art/views/poi_counts.json]
        [--zooms 11 12 13 14 15 16 17] [--places NAME ...] [--pack FILE] [--channel msedge]
    .venv/art/Scripts/python packbuilder/map/style/count_pois.py --pack v2/oh-261114-z14.pmtiles
        --places conneaut-downtown columbus-downtown --out data/r3-p/poi_counts_oh_z14.json

Opens render/art_view.html (no route) for each place and zoom below, waits for
idle, and counts the features of the 'pois_driving' and stock 'pois' layers
returned by queryRenderedFeatures (symbols placed on screen after collisions,
412 x 800 CSS px, a phone in portrait, day style). This is how "fuel, parking,
rest areas, service plazas and EV charging appear earlier than shops and cafes"
is measured: two town views, two highway views (an I-79 rest area, a
Pennsylvania Turnpike service plaza) and the owner's test junction.
"""
import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from urllib.parse import urlencode

from playwright.sync_api import sync_playwright

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import render_art  # noqa: E402

PLACES = {
    "erie-peach-st-upper": (42.0560, -80.0880),     # Peach St / US 19 north of I-90 exit 24: stores, fuel
    "erie-downtown": (42.1245, -80.0860),
    "pittsburgh-downtown": (40.4420, -79.9980),
    "i79-rest-area-mckean": (41.8445, -80.1760),    # I-79 rest areas both directions, south of Erie
    "turnpike-sideling-hill": (40.0591, -78.0815),  # Sideling Hill Service Plaza, I-70/76
    "i90-exit24": (42.0478, -80.0854),
    # Ohio (the OH pack: --pack v2/oh-261114-z14.pmtiles --places conneaut-downtown columbus-downtown)
    "conneaut-downtown": (41.9476, -80.5542),
    "columbus-downtown": (39.9612, -82.9988),
}
# the places of the Pennsylvania pack (the default list; the Ohio ones are given with --places)
PA_PLACES = ["erie-peach-st-upper", "erie-downtown", "pittsburgh-downtown", "i79-rest-area-mckean",
             "turnpike-sideling-hill", "i90-exit24"]
ZOOMS = [11, 12, 13, 14, 15, 16, 17]
GROUPS = {
    "fuel and charging": {"fuel", "charging_station"},
    "parking": {"parking"},
    "rest areas and services": {"rest_area", "services"},
    "cafes": {"cafe"},
    "restaurants and fast food": {"restaurant", "fast_food"},
    "shops": {"supermarket", "convenience", "clothes", "books", "beauty", "electronics"},
}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="data/art/views/poi_counts.json")
    ap.add_argument("--zooms", nargs="*", type=float, default=ZOOMS)
    ap.add_argument("--places", nargs="*", default=PA_PLACES, choices=list(PLACES))
    ap.add_argument("--channel", default="msedge")
    ap.add_argument("--pack", default=render_art.DEFAULT_PACK,
                    help="the map pack: a file of data/map/packs, or v2/<file> of data/map/packs-v2 (the z14 packs: "
                         "v2/pa-261114-z14.pmtiles, v2/oh-261114-z14.pmtiles with the Ohio --places)")
    a = ap.parse_args()
    srv, port = render_art.start_server()
    result = {}
    try:
        with sync_playwright() as pw:
            browser = pw.chromium.launch(channel=a.channel, headless=True, args=render_art.ARGS)
            for place in a.places:
                lat, lon = PLACES[place]
                for z in a.zooms:
                    z = int(z) if float(z).is_integer() else z
                    page = browser.new_page(viewport={"width": 412, "height": 800}, device_scale_factor=1)
                    q = {"variant": "day", "at": f"{z}/{lat}/{lon}", "pack": a.pack}
                    page.goto(f"http://127.0.0.1:{port}/art/art_view.html?{urlencode(q)}")
                    page.wait_for_function("window.__art && ((window.__art.ready && window.__art.idle) || window.__art.failed)",
                                           timeout=120000)
                    if page.evaluate("!!window.__art.failed"):
                        raise SystemExit(f"{place} z{z}: page failed: {page.evaluate('window.__art.errors')}")
                    page.wait_for_timeout(300)
                    feats = page.evaluate("""() => window.__map.queryRenderedFeatures({layers: ['pois_driving', 'pois']})
                        .map(f => [f.layer.id, f.properties.kind])""")
                    c = Counter(k for _, k in feats)
                    by_group = {g: sum(c[k] for k in ks) for g, ks in GROUPS.items()}
                    result[f"{place} z{z}"] = {"by_group": by_group, "by_kind": dict(c.most_common())}
                    print(f"{place} z{z}: " + ", ".join(f"{g} {n}" for g, n in by_group.items()), flush=True)
                    page.close()
            browser.close()
    finally:
        srv.shutdown()
    Path(a.out).parent.mkdir(parents=True, exist_ok=True)
    result = {"_pack": a.pack, **result}
    Path(a.out).write_text(json.dumps(result, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
