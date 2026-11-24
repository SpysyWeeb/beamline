"""Build the style bundle's sprite sheets (1x and 2x, light and dark) from assets/.

Usage (from the repo root, after `python tools/fetch_art_tools.py`):
    .venv/art/Scripts/python packbuilder/map/style/build_sprites.py [--compare-v4]

Reads  assets/icons/icons.json + assets/icons/{upstream,beamline}/*.svg
       assets/shields/shields.json + assets/shields/{upstream,beamline}/*.svg
Writes data/art/sprites/{light,dark}{,@2x}.{png,json}   (copied into the style
       bundle by generate_style.mjs)
       data/art/sprites/build.json                      (what was built, sizes, time)

Steps, per flavor (light = day map, dark = night map):
  1. recolour every SVG template: each #rrggbb channel c becomes
     mix(pair[0], pair[1], c / 255) with the colour pair of the icon's
     category (the rule of Protomaps' sprites/src/main.rs, so the upstream icons
     come out as in the bundled v4 sheet);
  2. write them to data/art/build/<flavor>/ with file-safe names (":" -> "_");
  3. spreet 0.14.0 (tools/bin/spreet) rasterises and packs them: --unique, at
     ratio 1 and 2 (--retina), minified index;
  4. the index keys are renamed back to the sprite names ("US:I-2char").

--compare-v4 also crops every image that exists in the pinned Protomaps v4
sheets (data/art/upstream/v4, from tools/fetch_art_sources.py) out of both
sheets and reports size and pixel differences, to show that the rebuilt
upstream icons match the ones the style used before.
"""
import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SPREET = REPO / "tools" / "bin" / "spreet" / ("spreet.exe" if sys.platform == "win32" else "spreet")
OUT = REPO / "data" / "art" / "sprites"
WORK = REPO / "data" / "art" / "build"
HEX = re.compile(r"#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})\b")


def rgb(h: str):
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    return [int(h[i:i + 2], 16) for i in (0, 2, 4)]


def recolour(svg: str, pair) -> str:
    dark, light = rgb(pair[0]), rgb(pair[1])

    def rep(m):
        c = rgb(m.group(1))
        out = []
        for i in range(3):
            t = c[i] / 255
            out.append(min(255, max(0, round((1 - t) * dark[i] + t * light[i]))))
        return "#%02x%02x%02x" % tuple(out)
    return HEX.sub(rep, svg)


def safe(name: str) -> str:
    return name.replace(":", "_")


def load_items():
    icons = json.loads((REPO / "assets" / "icons" / "icons.json").read_text(encoding="utf-8"))
    shields = json.loads((REPO / "assets" / "shields" / "shields.json").read_text(encoding="utf-8"))
    flavors = {}
    for fl in ("light", "dark"):
        flavors[fl] = {**icons["flavors"][fl], **shields["flavors"][fl]}
    items = []
    for name, spec in icons["icons"].items():
        items.append((name, REPO / "assets" / "icons" / spec["source"] / (safe(name) + ".svg"), spec["color"]))
    for name, spec in shields["shields"].items():
        items.append((name, REPO / "assets" / "shields" / spec["source"] / (safe(name) + ".svg"), spec["color"]))
    return flavors, items


def build(flavor: str, pairs: dict, items) -> dict:
    src = WORK / flavor
    if src.exists():
        shutil.rmtree(src)
    src.mkdir(parents=True)
    names = {}
    for name, path, color in items:
        if not path.exists():
            raise SystemExit(f"missing source {path.relative_to(REPO).as_posix()}")
        if color not in pairs:
            raise SystemExit(f"{name}: no colour pair '{color}' in flavor {flavor}")
        if safe(name) in names:
            raise SystemExit(f"two sprites map to the file name {safe(name)}")
        names[safe(name)] = name
        (src / (safe(name) + ".svg")).write_text(recolour(path.read_text(encoding="utf-8"), pairs[color]), encoding="utf-8")
    OUT.mkdir(parents=True, exist_ok=True)
    result = {}
    for ratio, suffix in ((1, ""), (2, "@2x")):
        base = OUT / f"{flavor}{suffix}"
        cmd = [str(SPREET), "--unique", "--minify-index-file", "--ratio", str(ratio), str(src), str(base)]
        subprocess.run(cmd, check=True)
        idx_path = base.with_suffix(".json")
        idx = json.loads(idx_path.read_text(encoding="utf-8"))
        renamed = {names[k]: v for k, v in idx.items()}
        missing = set(names.values()) - set(renamed)
        if missing:
            raise SystemExit(f"spreet left out {sorted(missing)}")
        idx_path.write_text(json.dumps(dict(sorted(renamed.items())), separators=(",", ":")), encoding="utf-8")
        png = base.with_suffix(".png")
        result[f"{flavor}{suffix}"] = {"images": len(renamed), "png_bytes": png.stat().st_size,
                                       "json_bytes": idx_path.stat().st_size}
    return result


def compare_v4() -> dict:
    from PIL import Image, ImageChops
    report = {}
    for sheet in ("light", "dark", "light@2x", "dark@2x"):
        ours_idx = json.loads((OUT / f"{sheet}.json").read_text(encoding="utf-8"))
        v4_idx = json.loads((REPO / "data" / "art" / "upstream" / "v4" / f"{sheet}.json").read_text(encoding="utf-8"))
        ours = Image.open(OUT / f"{sheet}.png").convert("RGBA")
        v4 = Image.open(REPO / "data" / "art" / "upstream" / "v4" / f"{sheet}.png").convert("RGBA")
        same_size, exact, near, rows = 0, 0, {}, {}
        for name, e in v4_idx.items():
            if name not in ours_idx:
                rows[name] = "not in our sheet"
                continue
            o = ours_idx[name]
            if (o["width"], o["height"]) != (e["width"], e["height"]):
                rows[name] = f"size {o['width']}x{o['height']} vs v4 {e['width']}x{e['height']}"
                continue
            same_size += 1
            a = ours.crop((o["x"], o["y"], o["x"] + o["width"], o["y"] + o["height"]))
            b = v4.crop((e["x"], e["y"], e["x"] + e["width"], e["y"] + e["height"]))
            diff = ImageChops.difference(a, b)
            mx = max(band.getextrema()[1] for band in diff.split())
            over = None
            for band in diff.split():
                m = band.point(lambda v: 255 if v > 8 else 0)
                over = m if over is None else ImageChops.lighter(over, m)
            changed = over.histogram()[255]
            if mx == 0:
                exact += 1
            elif mx <= 8:
                near[name] = mx
            else:
                rows[name] = f"same size, max channel diff {mx}, {changed}/{a.width * a.height} px differ by more than 8"
        # "exact": every RGBA channel of every pixel equal; "near": max channel difference 1..8 of 255
        report[sheet] = {"v4_images": len(v4_idx), "same_size": same_size, "exact": exact,
                         "near_max_diff_le_8": near,
                         "differences": rows}
    return report


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--compare-v4", action="store_true")
    a = ap.parse_args()
    if not SPREET.exists():
        raise SystemExit("spreet missing: run python tools/fetch_art_tools.py")
    t0 = time.time()
    flavors, items = load_items()
    record = {"spreet": subprocess.run([str(SPREET), "--version"], capture_output=True, text=True).stdout.strip(),
              "sprites": len(items), "sheets": {}}
    for flavor, pairs in flavors.items():
        record["sheets"].update(build(flavor, pairs, items))
    record["seconds"] = round(time.time() - t0, 1)
    if a.compare_v4:
        record["compare_v4"] = compare_v4()
    (OUT / "build.json").write_text(json.dumps(record, indent=1), encoding="utf-8")
    for k, v in record["sheets"].items():
        print(f"{k}: {v['images']} images, {v['png_bytes']} B png, {v['json_bytes']} B json")
    if a.compare_v4:
        for sheet, r in record["compare_v4"].items():
            near = ", ".join(f"{n} max {d}/255" for n, d in r["near_max_diff_le_8"].items())
            print(f"compare {sheet}: {r['same_size']}/{r['v4_images']} same size, {r['exact']} pixel-identical, "
                  f"{len(r['near_max_diff_le_8'])} within 8/255{' (' + near + ')' if near else ''}, "
                  f"{sum(1 for v in r['differences'].values() if v.startswith('same size'))} differ by more")
            for n, why in r["differences"].items():
                print(f"   {n}: {why}")
    print(f"done in {record['seconds']} s -> {OUT.relative_to(REPO).as_posix()}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
