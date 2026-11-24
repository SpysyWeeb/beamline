"""Broken-pack tests on a device: make damaged copies of a good pack, install each, check the app.

OUT OF DATE: written for the skeleton's pack manifest version 1 (style inside the pack). The app
now reads manifest version 2 (docs/pack-format.md) and refuses version 1, so `make` and `run`
would only test that refusal. The same failure cases for version 2 are unit tests in
android/core/packs (PackScannerTest, PackInstallerTest). `cut` still works and is how
data/android/inputs/erie-cut-z0-15.pmtiles was made. Pass --allow-v1 to run the old test anyway.

Usage (from the repo root, app installed, one device or emulator on adb):

    # 1. a small good pack (Erie) cut from the full Pennsylvania pack; needs the `pmtiles`
    #    package (tools/fetch_android_requirements.txt), e.g. the .venv/android of the README
    .venv/android/Scripts/python android/scripts/pack_variants.py cut data/android/packs/us-pa data/android/packs/test-erie
    # 2. the variants (standard library only)
    python android/scripts/pack_variants.py make data/android/packs/test-erie data/android/variants
    # 3. install each variant alone, start the app, read the screen, take a screenshot
    python android/scripts/pack_variants.py run data/android/variants --shots data/android/screenshots/variants

`run` removes every installed pack first (it leaves the device with the last variant installed).
For each variant it records: did the app crash (logcat FATAL EXCEPTION for its process), is the
process alive, the text on screen (uiautomator dump of the Compose UI), and checks it against
what the variant expects: either the map with the OpenStreetMap credit, or a message naming
the problem. Results go to <shots>/results.json and a table on stdout.

Files of the base pack are hard-linked into the variants where possible (no extra disk space);
every file a variant changes is replaced, never written in place, so the base stays intact.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from html import unescape
from pathlib import Path
from xml.etree import ElementTree

ANDROID_DIR = Path(__file__).resolve().parents[1]
OSM = "© OpenStreetMap contributors"
NO_MAP = "No map installed"
FAILED = "The map could not be shown"


def app_id() -> str:
    if os.environ.get("BEAMLINE_APPLICATION_ID"):
        return os.environ["BEAMLINE_APPLICATION_ID"]
    for line in (ANDROID_DIR / "gradle.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("beamline.applicationId="):
            return line.split("=", 1)[1].strip()
    sys.exit("beamline.applicationId missing from android/gradle.properties")


# ---------------------------------------------------------------------------------------- cut

def cut(a) -> None:
    import math

    from pmtiles.reader import MmapSource, Reader
    from pmtiles.tile import tileid_to_zxy, zxy_to_tileid
    from pmtiles.writer import Writer

    w_, s_, e_, n_ = (float(v) for v in a.bbox.split(","))
    src = Path(a.src)
    m = json.loads((src / "manifest.json").read_text(encoding="utf-8"))
    out = Path(a.out)
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    for d in ("style", "glyphs", "sprites"):
        if (src / d).is_dir():
            shutil.copytree(src / d, out / d)

    def tile(lon: float, lat: float, z: int) -> tuple[int, int]:
        n = 1 << z
        x = int((lon + 180.0) / 360.0 * n)
        y = int((1.0 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2.0 * n)
        return min(max(x, 0), n - 1), min(max(y, 0), n - 1)

    with open(src / m["map"]["file"], "rb") as f:
        r = Reader(MmapSource(f))
        header, meta = r.header(), r.metadata()
        ids = []
        for z in range(0, a.maxzoom + 1):
            x0, y0 = tile(w_, n_, z)
            x1, y1 = tile(e_, s_, z)
            ids += [zxy_to_tileid(z, x, y) for x in range(x0, x1 + 1) for y in range(y0, y1 + 1)]
        with open(out / "map.pmtiles", "wb") as o:
            wr = Writer(o)
            count = 0
            for tid in sorted(ids):
                data = r.get(*tileid_to_zxy(tid))
                if data:
                    wr.write_tile(tid, data)
                    count += 1
            h = dict(header)
            h.update(min_lon_e7=int(w_ * 1e7), min_lat_e7=int(s_ * 1e7), max_lon_e7=int(e_ * 1e7),
                     max_lat_e7=int(n_ * 1e7), max_zoom=a.maxzoom, center_zoom=min(12, a.maxzoom),
                     center_lon_e7=int((w_ + e_) / 2 * 1e7), center_lat_e7=int((s_ + n_) / 2 * 1e7))
            wr.finalize(h, meta)
    m["pack_id"] = a.pack_id or out.name
    m["name"] = f"{m['name']} test cut {a.bbox}"
    m["bounds"] = [w_, s_, e_, n_]
    m["map"]["bytes"] = (out / "map.pmtiles").stat().st_size
    m["map"].pop("sha256", None)
    m["map"]["max_zoom"] = a.maxzoom
    (out / "manifest.json").write_text(json.dumps(m, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{out}: {count} tiles, map.pmtiles {m['map']['bytes'] / 1e6:.1f} MB")


# --------------------------------------------------------------------------------------- make

def link_or_copy(src: str, dst: str) -> None:
    try:
        os.link(src, dst)
    except OSError:
        shutil.copy2(src, dst)


def replace(path: Path, data: bytes | str) -> None:
    """Write a file that may be a hard link to the base pack without touching the base."""
    if path.exists():
        path.unlink()
    if isinstance(data, str):
        path.write_text(data, encoding="utf-8")
    else:
        path.write_bytes(data)


def make(a) -> None:
    base = Path(a.base)
    out = Path(a.out)
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)
    expect: dict[str, dict] = {}

    def pack(variant: str, folder: str, manifest=None, files=None) -> Path:
        d = out / variant / folder
        shutil.copytree(base, d, copy_function=link_or_copy)
        m = json.loads((d / "manifest.json").read_text(encoding="utf-8"))
        m["pack_id"] = folder
        if manifest:
            manifest(m)
        replace(d / "manifest.json", json.dumps(m, indent=2, ensure_ascii=False) + "\n")
        if files:
            files(d)
        return d

    def one(name: str, want: str, text: str, manifest=None, files=None) -> None:
        pack(name, name, manifest, files)
        expect[name] = {"expect": want, "text": text}

    def style(edit):
        def files(d: Path) -> None:
            for n in ("day", "night"):
                p = d / "style" / f"{n}.json"
                s = json.loads(p.read_text(encoding="utf-8"))
                edit(s)
                replace(p, json.dumps(s))
        return files

    mapfile = lambda d: d / "map.pmtiles"  # noqa: E731
    size = mapfile(base).stat().st_size

    one("good", "map", OSM)
    # the map file
    one("zerolen", "message", "copy not finished", files=lambda d: replace(mapfile(d), b""))
    one("garbage", "message", "copy not finished", files=lambda d: replace(mapfile(d), os.urandom(1 << 20)))
    one("garbage-same-size", "message", "not a PMTiles file",
        manifest=lambda m: m["map"].__setitem__("bytes", 1 << 20),
        files=lambda d: replace(mapfile(d), os.urandom(1 << 20)))
    one("truncated", "message", "copy not finished",
        files=lambda d: replace(mapfile(d), mapfile(base).read_bytes()[: size // 3]))
    one("truncated-no-size", "message", "cut short", manifest=lambda m: m["map"].pop("bytes"),
        files=lambda d: replace(mapfile(d), mapfile(base).read_bytes()[: size // 3]))
    one("header-only", "message", "copy not finished",
        files=lambda d: replace(mapfile(d), mapfile(base).read_bytes()[:127]))
    # bounds
    one("bounds-lat-95", "message", "bounds", manifest=lambda m: m.__setitem__("bounds", [-80.3, 95, -79.8, 96]))
    one("bounds-south-north", "message", "bounds",
        manifest=lambda m: m.__setitem__("bounds", [-80.3, 42.25, -79.8, 41.95]))
    one("bounds-zero", "message", "bounds", manifest=lambda m: m.__setitem__("bounds", [0, 0, 0, 0]))
    one("bounds-wrap", "message", "bounds", manifest=lambda m: m.__setitem__("bounds", [-200, -100, 200, 100]))
    # credit
    one("no-attribution", "map", OSM, manifest=lambda m: m.__setitem__("attribution", ""))
    # style shape
    one("source-name-unknown", "message", "no vector source",
        manifest=lambda m: m["map"].__setitem__("source_name", "nosuchsource"))
    one("style-sources-array", "message", '"sources" must be an object', files=style(lambda s: s.__setitem__("sources", [1])))
    one("style-layers-object", "message", '"layers" must be an array', files=style(lambda s: s.__setitem__("layers", {"a": 1})))
    one("style-layer-number", "message", "is not an object", files=style(lambda s: s["layers"].append(5)))
    one("style-layer-no-id", "message", "has no id",
        files=style(lambda s: next(l for l in s["layers"] if l.get("source") == "protomaps").pop("id")))
    one("style-empty", "message", "no vector source",
        files=lambda d: [replace(d / "style" / n, "{}") for n in ("day.json", "night.json")])
    one("style-not-json", "message", "cannot read style",
        files=lambda d: [replace(d / "style" / n, "{not json") for n in ("day.json", "night.json")])
    # sprites and glyphs
    one("no-sprite-png", "message", "missing file sprites/light.png",
        files=lambda d: [p.unlink() for p in (d / "sprites").glob("*.png")])
    # The font folders go, a licence file stays (adb push skips empty folders, so glyphs/ must not be empty).
    one("no-glyphs", "message", "no glyph files",
        files=lambda d: [shutil.rmtree(f) for f in (d / "glyphs").iterdir() if f.is_dir()]
        + [replace(d / "glyphs" / "README.txt", "fonts removed for the test\n")])
    # two packs: the good one is drawn, the other is named on screen
    pack("pair-other-schema", "a-good")
    pack("pair-other-schema", "b-other", manifest=lambda m: m["map"].__setitem__("schema", "other-schema"))
    expect["pair-other-schema"] = {"expect": "map", "text": "Skipped b-other"}
    pack("pair-broken-file", "a-good")
    pack("pair-broken-file", "b-empty", files=lambda d: replace(mapfile(d), b""))
    expect["pair-broken-file"] = {"expect": "map", "text": "Skipped b-empty"}

    (out / "variants.json").write_text(json.dumps(expect, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{len(expect)} variants in {out}")


# ---------------------------------------------------------------------------------------- run

ADB = shutil.which("adb") or "adb"


def adb(*args: str, binary: bool = False, check: bool = False):
    # Only the device ANDROID_SERIAL names (`. tools/round/env.sh <key>`): never a guessed one.
    serial = os.environ.get("ANDROID_SERIAL", "").strip()
    if not serial:
        sys.exit("ANDROID_SERIAL is not set: run `. tools/round/env.sh <key>` first (this script never picks a device)")
    p = subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=not binary,
                       encoding=None if binary else "utf-8", errors=None if binary else "replace")
    if check and p.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {p.stderr}")
    return p.stdout


def screen_text() -> list[str]:
    adb("shell", "uiautomator", "dump", "/sdcard/beamline-ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/beamline-ui.xml")
    try:
        # attributes are quoted with ' or " depending on the text, so parse it as XML
        return [n.get("text") for n in ElementTree.fromstring(xml).iter("node") if n.get("text")]
    except ElementTree.ParseError:
        return [unescape(t) for t in re.findall(r' text="([^"]+)"', xml)]


def top_activity() -> str:
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", adb("shell", "dumpsys", "activity", "activities"))
    return m.group(1) if m else "?"


def run(a) -> None:
    app = app_id()
    activity = f"{app}/io.github.spysyweeb.beamline.app.MainActivity"
    remote = f"/sdcard/Android/data/{app}/files/packs"
    root = Path(a.dir)
    expect = json.loads((root / "variants.json").read_text(encoding="utf-8"))
    names = a.only or list(expect)
    shots = Path(a.shots)
    shots.mkdir(parents=True, exist_ok=True)
    results = {}
    for name in names:
        adb("shell", "am", "force-stop", app)
        adb("shell", f"rm -rf {remote}/* {remote}/.incoming-*; mkdir -p {remote}", check=True)
        for p in sorted((root / name).iterdir()):
            adb("push", str(p), f"{remote}/.incoming-{p.name}", check=True)
            adb("shell", f"mv {remote}/.incoming-{p.name} {remote}/{p.name}", check=True)
        adb("logcat", "-c")
        adb("shell", "am", "start", "-W", "-n", activity)
        time.sleep(a.wait)
        log = adb("logcat", "-d")
        crashed = bool(re.search(rf"FATAL EXCEPTION[\s\S]{{0,400}}Process: {re.escape(app)}", log))
        alive = bool(adb("shell", "pidof", app).strip())
        top = top_activity()
        texts = screen_text() if alive else []
        png = shots / f"v-{name}.png"
        png.write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        joined = "\n".join(texts)
        want = expect[name]
        if want["expect"] == "map":
            shown = OSM in texts and NO_MAP not in joined and FAILED not in joined
        else:
            shown = (NO_MAP in joined or FAILED in joined)
        ok = alive and not crashed and top.startswith(app) and shown and want["text"] in joined
        results[name] = {"ok": ok, "crashed": crashed, "alive": alive, "top": top, "expect": want, "screen": texts}
        reason = next((t for t in texts if t.startswith("Skipped") or "style" in t or "no packs" in t), "")
        print(f"{'PASS' if ok else 'FAIL'}  {name:22} crashed={crashed!s:5} alive={alive!s:5} "
              f"expect={want['expect']:7} | {reason[:110] or ' / '.join(texts)[:110]}", flush=True)
    (shots / "results.json").write_text(json.dumps(results, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    bad = [n for n, r in results.items() if not r["ok"]]
    print(f"{len(results) - len(bad)} of {len(results)} as expected" + (f"; not as expected: {bad}" if bad else ""))


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")  # the credit has a (c) sign; Windows consoles default to cp1252
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    c = sub.add_parser("cut", help="small pack cut from a bigger one (needs the pmtiles package)")
    c.add_argument("src")
    c.add_argument("out")
    c.add_argument("--bbox", default="-80.3,41.95,-79.8,42.25", help="west,south,east,north (default: Erie)")
    c.add_argument("--maxzoom", type=int, default=15)
    c.add_argument("--pack-id")
    m = sub.add_parser("make", help="damaged copies of a good pack")
    m.add_argument("base")
    m.add_argument("out")
    r = sub.add_parser("run", help="install each variant on the device and check the app")
    r.add_argument("dir")
    r.add_argument("--shots", default="data/android/screenshots/variants")
    r.add_argument("--wait", type=float, default=6)
    r.add_argument("--only", nargs="*")
    ap.add_argument("--allow-v1", action="store_true", help="run make/run although they write manifest version 1")
    a = ap.parse_args()
    if a.cmd in ("make", "run") and not a.allow_v1:
        sys.exit("pack_variants.py make/run write manifest version 1, which the app refuses; see the docstring (--allow-v1)")
    {"cut": cut, "make": make, "run": run}[a.cmd](a)
    return 0


if __name__ == "__main__":
    sys.exit(main())
