"""Run the whole artwork pipeline in order and record wall time and peak memory of each step.

Usage (from the repo root, after the one-time setup in docs/experiments/06-map-art.md):
    .venv/art/Scripts/python packbuilder/map/style/build_art.py [--from STEP] [--only STEP ...] [--with-routes]

Steps (each is also a command of its own):
  shields   python packbuilder/map/style/draw_shields.py
  import    import_upstream_icons.py           (refill.svg -> assets/icons/upstream, assets/shields/upstream)
  sprites   build_sprites.py --compare-v4       (assets -> data/art/sprites)
  markers   build_markers.py                    (assets/markers -> data/art/markers)
  style     node generate_style.mjs             (-> packbuilder/map/style-bundle)
  check     check_poi_icons.py                  (every search category has an icon)
  routes    sample_routes.py with .venv/routing (data/art/route/routes.geojson; runs with --with-routes,
            and on its own when the views step runs and routes.geojson does not exist yet)
  views     render_art.py views                 (data/art/views)
  sheet     build_sheet.py                      (assets/sheet/index.html)
  sheetpng  render_art.py page ... --tiles      (data/art/sheet/*.png)

Before anything runs, the inputs of the chosen steps are checked (tools, upstream
sources, the map track's style inputs and PA pack, the routing environment) and
the script stops with a list of what is missing and which command makes it.

Peak memory is the largest sum of the working sets (RSS) of the step's process
tree, polled every 0.1 s with psutil, so it is a lower bound (short peaks
between polls are missed). The record goes to data/art/build_art.json.
"""
import argparse
import json
import subprocess
import sys
import time
from pathlib import Path

import psutil

REPO = Path(__file__).resolve().parents[3]
PY = sys.executable
S = "packbuilder/map/style"
WIN = sys.platform == "win32"
EXE = ".exe" if WIN else ""
ROUTING_PY = REPO / ".venv" / "routing" / ("Scripts" if WIN else "bin") / f"python{EXE}"
ROUTES = REPO / "data" / "art" / "route" / "routes.geojson"
STEPS = [
    ("shields", [PY, f"{S}/draw_shields.py"]),
    ("import", [PY, f"{S}/import_upstream_icons.py"]),
    ("sprites", [PY, f"{S}/build_sprites.py", "--compare-v4"]),
    ("markers", [PY, f"{S}/build_markers.py"]),
    ("style", ["node", f"{S}/generate_style.mjs"]),
    ("check", [PY, f"{S}/check_poi_icons.py"]),
    ("routes", [str(ROUTING_PY), f"{S}/sample_routes.py"]),
    ("views", [PY, f"{S}/render_art.py", "views"]),
    ("sheet", [PY, f"{S}/build_sheet.py"]),
    ("sheetpng", [PY, f"{S}/render_art.py", "page", "assets/sheet/index.html", "data/art/sheet/sheet.png",
                  "--width", "1240", "--dpr", "2", "--tiles", "--clip-height", "1600"]),
]


# what each step needs before it can run: path -> the command that makes it
NEEDS = {
    "import": {f"tools/bin/resvg/resvg{EXE}": "python tools/fetch_art_tools.py",
               "data/art/upstream/refill.svg": "python tools/fetch_art_sources.py"},
    "sprites": {f"tools/bin/spreet/spreet{EXE}": "python tools/fetch_art_tools.py",
                "data/art/upstream/v4/light.json": "python tools/fetch_art_sources.py"},
    "markers": {f"tools/bin/resvg/resvg{EXE}": "python tools/fetch_art_tools.py"},
    "style": {"data/map/style-src": "packbuilder/map/style/fetch_style_inputs.py (map track)"},
    "check": {"packbuilder/search/categories.json": "the search track's categories (in git)"},
    "routes": {str(ROUTING_PY.relative_to(REPO).as_posix()): "python tools/fetch_routing_env.py (routing track)",
               "data/routing/build/valhalla.json": "the routing track's Valhalla tiles (packbuilder/routing)"},
    "views": {"data/map/packs/pa-261114-z0-15.pmtiles": "the map track's PA pack (packbuilder/map/build_pack.py)",
              "viewer/vendor/maplibre-gl/maplibre-gl.mjs": "python tools/fetch_map_vendor.py"},
}


def run(name, cmd):
    t0 = time.time()
    p = psutil.Popen(cmd, cwd=REPO)
    peak = 0
    while p.poll() is None:
        try:
            procs = [p] + p.children(recursive=True)
            rss = 0
            for q in procs:
                try:
                    rss += q.memory_info().rss
                except psutil.Error:
                    pass
            peak = max(peak, rss)
        except psutil.Error:
            pass
        time.sleep(0.1)
    return {"step": name, "exit": p.returncode, "seconds": round(time.time() - t0, 1),
            "peak_rss_mib": round(peak / 2 ** 20, 1)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--from", dest="start")
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--with-routes", action="store_true")
    a = ap.parse_args()
    names = [n for n, _ in STEPS]
    todo = list(STEPS)
    if a.start:
        todo = STEPS[names.index(a.start):]
    if a.only:
        todo = [s for s in STEPS if s[0] in a.only]
    if not a.with_routes and not (a.only and "routes" in a.only):
        todo = [s for s in todo if s[0] != "routes"]
    chosen = [n for n, _ in todo]
    if "views" in chosen and "routes" not in chosen and not ROUTES.exists():
        # without the sample routes every route view would wait out its timeout and come out blank
        print("data/art/route/routes.geojson is missing: running the routes step before the views", flush=True)
        todo.insert(chosen.index("views"), STEPS[names.index("routes")])
    missing = [f"{name}: {p}  <- {how}" for name, _ in todo for p, how in NEEDS.get(name, {}).items()
               if not (REPO / p).exists()]
    if missing:
        print("missing inputs, nothing was run:", *missing, sep="\n  ")
        return 2
    records = []
    t0 = time.time()
    for name, cmd in todo:
        print(f"== {name}: {' '.join(Path(c).name if i == 0 else c for i, c in enumerate(cmd))}", flush=True)
        r = run(name, cmd)
        records.append(r)
        print(f"== {name}: exit {r['exit']}, {r['seconds']} s, peak {r['peak_rss_mib']} MiB", flush=True)
        if r["exit"] != 0:
            break
    out = {"total_seconds": round(time.time() - t0, 1), "steps": records,
           "note": "other engineers' jobs were running on the same machine"}
    (REPO / "data" / "art").mkdir(parents=True, exist_ok=True)
    (REPO / "data" / "art" / "build_art.json").write_text(json.dumps(out, indent=1), encoding="utf-8")
    failed = [r for r in records if r["exit"] != 0]
    print(f"total {out['total_seconds']} s, {'FAILED: ' + failed[0]['step'] if failed else 'all steps ok'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
