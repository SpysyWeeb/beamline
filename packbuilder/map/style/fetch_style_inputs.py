"""Fetch the pinned inputs of the offline style bundle into data/map/style-src/.

Usage (from the repo root):
    .venv/map/Scripts/python packbuilder/map/style/fetch_style_inputs.py

Inputs (all pinned):
  - @protomaps/basemaps (npm, BSD-3-Clause code, CC0 design): the style layer
    generator, version and tarball SHA-256 from versions.json.
  - protomaps/basemaps-assets at the commit in versions.json: Noto Sans glyph
    PBFs (SIL OFL 1.1) for the ranges in style_config.json, and the v4 sprite
    sheets (icons derived from tangrams/icons, MIT).
  - tangrams/icons licence text (MIT), pinned by commit.

Every downloaded file's SHA-256 is recorded in style_inputs.lock.json (kept in
git). When an entry already exists the download must match it, otherwise the
script stops. Re-running skips files that are present and match.
"""
import hashlib
import json
import sys
import tarfile
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
VERSIONS = json.loads((HERE.parent / "versions.json").read_text())
CFG = json.loads((HERE / "style_config.json").read_text())
LOCK = HERE / "style_inputs.lock.json"
OUT = REPO / "data" / "map" / "style-src"
TANGRAMS_COMMIT = "92510779634f4a006c61ea70e50cb8c52c765a81"


def sha256_bytes(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "beamline-packbuilder"})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.read()
        except OSError as e:
            print(f"  retry {url}: {e}")
    raise SystemExit(f"failed: {url}")


def main() -> int:
    lock = json.loads(LOCK.read_text()) if LOCK.exists() else {}
    wanted = {}
    npm = VERSIONS["protomaps_styles_npm"]
    wanted[f"protomaps-basemaps-{npm['version']}.tgz"] = npm["tarball"]
    assets = "https://raw.githubusercontent.com/protomaps/basemaps-assets/" + VERSIONS["basemaps_assets"]["commit"]
    for font in CFG["fonts"]:
        for rng in CFG["glyph_ranges_real"]:
            wanted[f"fonts/{font}/{rng}.pbf"] = f"{assets}/fonts/{urllib.parse.quote(font)}/{rng}.pbf"
    wanted["fonts/OFL.txt"] = f"{assets}/fonts/OFL.txt"
    for s in CFG["sprites"]:
        for suffix in ("", "@2x"):
            for ext in ("json", "png"):
                wanted[f"sprites/{s}{suffix}.{ext}"] = f"{assets}/sprites/v4/{s}{suffix}.{ext}"
    wanted["sprites/tangrams-icons-LICENSE.md"] = (
        f"https://raw.githubusercontent.com/tangrams/icons/{TANGRAMS_COMMIT}/LICENSE.md")

    changed = False
    for rel, url in wanted.items():
        dest = OUT / rel
        if dest.exists() and rel in lock and sha256_bytes(dest.read_bytes()) == lock[rel]["sha256"]:
            continue
        data = get(url)
        h = sha256_bytes(data)
        if rel in lock and lock[rel]["sha256"] != h:
            raise SystemExit(f"{rel}: SHA-256 {h} does not match the lock file ({lock[rel]['sha256']})")
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        lock[rel] = {"url": url, "sha256": h, "bytes": len(data)}
        changed = True
        print(f"got {rel} ({len(data)} bytes)")
    tgz = OUT / f"protomaps-basemaps-{npm['version']}.tgz"
    if lock[tgz.name]["sha256"] != npm["sha256"]:
        raise SystemExit("npm tarball hash differs from versions.json")
    pkg_dir = OUT / f"protomaps-basemaps-{npm['version']}"
    if not (pkg_dir / "package" / "dist" / "esm" / "index.js").exists():
        with tarfile.open(tgz) as t:
            t.extractall(pkg_dir, filter="data")
        print(f"extracted {pkg_dir.relative_to(REPO).as_posix()}")
    if changed:
        LOCK.write_text(json.dumps(dict(sorted(lock.items())), indent=1) + "\n")
        print(f"updated {LOCK.relative_to(REPO).as_posix()}")
    print("style inputs ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
