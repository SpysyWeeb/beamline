"""Copy upstream icons out of Protomaps' refill.svg into one SVG file per icon.

Usage (from the repo root, after `python tools/fetch_art_sources.py` and
`python tools/fetch_art_tools.py`):
    .venv/art/Scripts/python packbuilder/map/style/import_upstream_icons.py [--check]

Reads  data/art/upstream/refill.svg (protomaps/basemaps 42ffaaa4, pinned by SHA-256)
       assets/icons/icons.json and assets/shields/shields.json (which icons come
       from upstream, under which upstream group id)
Writes assets/icons/upstream/<name>.svg and assets/shields/upstream/<name>.svg

Each icon is the group `_<id>` of refill.svg, extracted the way Protomaps'
sprite generator (sprites/src/main.rs) does it, so that the frame and stroke
widths stay exactly the same:
  - helper labels (<text>) are dropped;
  - the viewBox is the union of the leaf shapes' bounding boxes (fill geometry,
    no stroke; measured with `resvg --query-all`), grown by 0.5 on every side;
  - colours stay the grey "template" values; the sprite build maps them to
    each category's colour pair (see build_sprites.py).
The Illustrator CSS classes are resolved into plain presentation attributes so
each file stands alone and can be edited in any SVG editor. Coordinates are
left as they are in refill.svg (the viewBox does the offset).

--check only reports what would change (exit 1 if a file differs).
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SRC = REPO / "data" / "art" / "upstream" / "refill.svg"
RESVG = REPO / "tools" / "bin" / "resvg" / ("resvg.exe" if sys.platform == "win32" else "resvg")
SVG_NS = "http://www.w3.org/2000/svg"
XLINK_NS = "http://www.w3.org/1999/xlink"
ET.register_namespace("", SVG_NS)
ET.register_namespace("xlink", XLINK_NS)
LEAVES = {"path", "rect", "line", "circle", "ellipse", "polygon", "polyline"}
HEADER = ("Extracted from protomaps/basemaps sprites/refill.svg (commit 42ffaaa4) group '_{gid}'. "
          "Derived from tangrams/icons, Copyright (c) 2017 Mapzen, MIT License; "
          "Protomaps visual design CC0-1.0. See assets/LICENSES.md.")


def local(tag: str) -> str:
    return tag.split("}", 1)[-1]


def parse_css(svg_root):
    style = svg_root.find(f"{{{SVG_NS}}}defs/{{{SVG_NS}}}style")
    rules = []
    for sel, body in re.findall(r"([^{}]+)\{([^{}]*)\}", style.text or ""):
        decls = []
        for d in body.split(";"):
            if ":" in d:
                k, v = d.split(":", 1)
                decls.append((k.strip(), v.strip()))
        for s in sel.split(","):
            rules.append((s.strip().lstrip("."), decls))
    by_class = {}
    for order, (cls, decls) in enumerate(rules):
        by_class.setdefault(cls, []).append((order, decls))
    return by_class


def inline_styles(el, by_class):
    """Replace class="cls-N" by the presentation attributes it stands for (source order wins)."""
    for e in el.iter():
        cls = e.attrib.pop("class", None)
        if not cls:
            continue
        found = []
        for c in cls.split():
            found.extend(by_class.get(c, []))
        props = {}
        for _order, decls in sorted(found, key=lambda x: x[0]):
            for k, v in decls:
                props[k] = v
        for k, v in props.items():
            if k.startswith("font") or k == "letter-spacing":
                continue
            if k == "stroke-width" and v.endswith("px"):
                v = v[:-2]
            e.set(k, v)


def drop_text(el):
    for parent in list(el.iter()):
        for child in list(parent):
            if local(child.tag) == "text":
                parent.remove(child)


def drop_empty_groups(el):
    changed = True
    while changed:
        changed = False
        for parent in list(el.iter()):
            for child in list(parent):
                if local(child.tag) == "g" and len(child) == 0:
                    parent.remove(child)
                    changed = True


def referenced_ids(el):
    ids = set()
    for e in el.iter():
        for k, v in e.attrib.items():
            ids.update(re.findall(r"url\(#([^)]+)\)", v))
            if local(k) == "href" and v.startswith("#"):
                ids.add(v[1:])
    return ids


def strip_ids(el):
    for e in el.iter():
        e.attrib.pop("id", None)


def bbox(svg_text: str):
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "q.svg"
        p.write_text(svg_text, encoding="utf-8")
        out = subprocess.run([str(RESVG), "--query-all", str(p)], capture_output=True, text=True, check=True).stdout
    xs, ys, xe, ye = [], [], [], []
    for line in out.splitlines():
        name, x, y, w, h = line.split(",")
        if not name.startswith("leaf"):
            continue
        x, y, w, h = map(float, (x, y, w, h))
        xs.append(x); ys.append(y); xe.append(x + w); ye.append(y + h)
    if not xs:
        raise ValueError("no shapes")
    return min(xs), min(ys), max(xe), max(ye)


def extract(root, by_class, defs_by_id, gid):
    group = None
    for g in root.iter(f"{{{SVG_NS}}}g"):
        if g.get("id") == f"_{gid}":
            group = g
            break
    if group is None:
        raise SystemExit(f"refill.svg has no group '_{gid}'")
    g = ET.fromstring(ET.tostring(group))
    drop_text(g)
    inline_styles(g, by_class)
    # referenced clip paths / gradients, transitively
    need, todo = {}, list(referenced_ids(g))
    while todo:
        i = todo.pop()
        if i in need or i not in defs_by_id:
            continue
        d = ET.fromstring(ET.tostring(defs_by_id[i]))
        inline_styles(d, by_class)
        need[i] = d
        todo.extend(referenced_ids(d))
    strip_ids(g)
    # number the leaves so resvg reports their boxes
    leaves = [e for e in g.iter() if local(e.tag) in LEAVES]
    for n, e in enumerate(leaves):
        e.set("id", f"leaf{n}")
    defs_xml = "".join(ET.tostring(d, encoding="unicode") for d in need.values())
    probe = (f'<svg xmlns="{SVG_NS}" xmlns:xlink="{XLINK_NS}" width="3000" height="3000">'
             f'<defs>{defs_xml}</defs>{ET.tostring(g, encoding="unicode")}</svg>')
    x0, y0, x1, y1 = bbox(probe)
    for e in leaves:
        e.attrib.pop("id", None)
    x0, y0, x1, y1 = x0 - 0.5, y0 - 0.5, x1 + 0.5, y1 + 0.5
    vb = f"{x0:g} {y0:g} {x1 - x0:g} {y1 - y0:g}"
    g.attrib.pop("id", None)
    drop_empty_groups(g)
    body = ET.tostring(g, encoding="unicode")
    # the namespace is declared once on <svg>
    body = body.replace(f' xmlns="{SVG_NS}"', "").replace(f' xmlns:xlink="{XLINK_NS}"', "")
    defs_xml = defs_xml.replace(f' xmlns="{SVG_NS}"', "").replace(f' xmlns:xlink="{XLINK_NS}"', "")
    defs_part = f"\n  <defs>{defs_xml}</defs>" if need else ""
    xl = f' xmlns:xlink="{XLINK_NS}"' if "xlink:" in body + defs_xml else ""
    return (f'<svg xmlns="{SVG_NS}"{xl} viewBox="{vb}">\n'
            f"  <!-- {HEADER.format(gid=gid)} -->{defs_part}\n  {body}\n</svg>\n")


def file_name(sprite_name: str) -> str:
    return sprite_name.replace(":", "_") + ".svg"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--only", nargs="*")
    a = ap.parse_args()
    root = ET.parse(SRC).getroot()
    by_class = parse_css(root)
    defs = root.find(f"{{{SVG_NS}}}defs")
    defs_by_id = {e.get("id"): e for e in defs.iter() if e.get("id")}
    jobs = []
    icons = json.loads((REPO / "assets" / "icons" / "icons.json").read_text(encoding="utf-8"))["icons"]
    for name, spec in icons.items():
        if spec.get("source") == "upstream":
            jobs.append((name, spec.get("upstream_id", name), REPO / "assets" / "icons" / "upstream"))
    shields = json.loads((REPO / "assets" / "shields" / "shields.json").read_text(encoding="utf-8"))["shields"]
    for name, spec in shields.items():
        if spec.get("source") == "upstream":
            jobs.append((name, spec.get("upstream_id", name), REPO / "assets" / "shields" / "upstream"))
    if a.only:
        jobs = [j for j in jobs if j[0] in a.only]
    changed = 0
    for name, gid, outdir in jobs:
        svg = extract(root, by_class, defs_by_id, gid)
        out = outdir / file_name(name)
        old = out.read_text(encoding="utf-8") if out.exists() else None
        if old == svg:
            continue
        changed += 1
        if a.check:
            print(f"differs: {out.relative_to(REPO).as_posix()}")
            continue
        outdir.mkdir(parents=True, exist_ok=True)
        out.write_text(svg, encoding="utf-8", newline="\n")
    print(f"{len(jobs)} upstream icons, {changed} {'differ' if a.check else 'written'}")
    return 1 if (a.check and changed) else 0


if __name__ == "__main__":
    sys.exit(main())
