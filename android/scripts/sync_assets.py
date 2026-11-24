"""Copy the map style bundle and the US overview map into the app's assets.

Usage (from the repo root, standard library only):

    python android/scripts/sync_assets.py            # both
    python android/scripts/sync_assets.py style      # style bundle only
    python android/scripts/sync_assets.py basemap [--basemap data/map/packs/base-us-20261114-z0-7.pmtiles]
    python android/scripts/sync_assets.py check      # say whether the copies still match their sources

style:   packbuilder/map/style-bundle/{day.json, night.json, glyphs/, sprites/, LICENSES.md}
         -> android/app/src/main/assets/style/   (committed: the style ships in the app)
         and writes assets/style/bundle-version.json: the git commit that last touched the bundle,
         whether the bundle had uncommitted changes, a SHA-256 over every copied file (path + bytes),
         file count and size. The app shows the first 12 hex digits of that hash in Settings.
basemap: data/map/packs/base-us-*-z0-7.pmtiles (newest by name, or --basemap)
         -> android/app/assets-local/basemap/us-base.pmtiles  (NOT committed: a swappable input)
         plus us-base.json (source name, size, SHA-256, zooms, bounds from the PMTiles header).
         The build adds android/app/assets-local/ as an extra assets folder and stores *.pmtiles
         uncompressed in the APK, so MapLibre can read byte ranges with pmtiles://asset://.
         Refused (nothing copied) unless the file is PMTiles v3 with the tile schema and major
         version the app's style draws (assets/rules/map-compose.json: schema, schema_major).
         Warns when tiles lie above overview_max_zoom (never drawn: wasted APK size) or the file
         is over the owner's budget of about 20 MB.
check:   re-hashes the copied style and the copied overview and compares them with the records
         and with the style bundle; exit code 1 if anything differs.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import re
import shutil
import struct
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BUNDLE = REPO / "packbuilder" / "map" / "style-bundle"
APP = REPO / "android" / "app"
STYLE_OUT = APP / "src" / "main" / "assets" / "style"
BASE_OUT = APP / "assets-local" / "basemap"
STYLE_PARTS = ("day.json", "night.json", "glyphs", "sprites", "LICENSES.md")
COMPOSE_RULES = APP / "src" / "main" / "assets" / "rules" / "map-compose.json"
OVERVIEW_BUDGET_MB = 20  # the owner's limit for the bundled overview: "about 20 MB at most"


def rel(p: Path) -> str:
    return p.resolve().relative_to(REPO).as_posix()


def tree_files(root: Path, parts=STYLE_PARTS) -> list[Path]:
    out = []
    for name in parts:
        p = root / name
        if p.is_file():
            out.append(p)
        elif p.is_dir():
            out.extend(sorted(f for f in p.rglob("*") if f.is_file()))
    return sorted(out, key=lambda f: f.relative_to(root).as_posix())


def tree_hash(root: Path) -> tuple[str, int, int]:
    """SHA-256 over (relative path, NUL, size, NUL, bytes) of every bundle file, in path order."""
    h = hashlib.sha256()
    n = size = 0
    for f in tree_files(root):
        data = f.read_bytes()
        h.update(f.relative_to(root).as_posix().encode("utf-8") + b"\0" + str(len(data)).encode() + b"\0")
        h.update(data)
        n += 1
        size += len(data)
    return h.hexdigest(), n, size


def git(*args: str) -> str:
    p = subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, text=True)
    return p.stdout.strip() if p.returncode == 0 else ""


def sync_style() -> dict:
    if not (BUNDLE / "day.json").is_file():
        sys.exit(f"{rel(BUNDLE)}/day.json not found")
    digest, n, size = tree_hash(BUNDLE)
    commit = git("log", "-1", "--format=%H %cI", "--", rel(BUNDLE))
    dirty = bool(git("status", "--porcelain", "--", rel(BUNDLE)))
    if STYLE_OUT.exists():
        shutil.rmtree(STYLE_OUT)
    STYLE_OUT.mkdir(parents=True)
    for name in STYLE_PARTS:
        src = BUNDLE / name
        if src.is_dir():
            shutil.copytree(src, STYLE_OUT / name)
        elif src.is_file():
            shutil.copyfile(src, STYLE_OUT / name)
    copied, n2, size2 = tree_hash(STYLE_OUT)
    if copied != digest:
        sys.exit("copy differs from the bundle (bundle changed while copying?); run again")
    record = {
        "source": rel(BUNDLE),
        "content_sha256": digest,
        "files": n,
        "bytes": size,
        "git_commit": commit.split(" ")[0] if commit else None,
        "git_commit_date": commit.split(" ")[1] if " " in commit else None,
        "uncommitted_changes": dirty,
        "copied_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    (STYLE_OUT / "bundle-version.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(f"style: {n} files, {size / 1e6:.2f} MB, sha256 {digest[:12]}, commit "
          f"{(record['git_commit'] or '?')[:8]}{' + uncommitted changes' if dirty else ''} -> {rel(STYLE_OUT)}")
    return record


def pmtiles_header(path: Path) -> dict:
    with open(path, "rb") as f:
        h = f.read(127)
        if len(h) < 127 or h[:7] != b"PMTiles" or h[7] != 3:
            sys.exit(f"{path} is not a PMTiles v3 file")
        meta_off, meta_len = struct.unpack_from("<QQ", h, 24)
        f.seek(meta_off)
        raw = f.read(meta_len)
    meta = json.loads(gzip.decompress(raw) if h[97] == 2 else raw)
    # Protomaps basemap builds name the schema only by its version ("4.15.2"); our own builds may
    # add "schema" to the metadata.
    version = str(meta.get("version", ""))
    return {
        "min_zoom": h[100],
        "max_zoom": h[101],
        "bounds": [round(v / 1e7, 5) for v in struct.unpack_from("<iiii", h, 102)],
        "center": [round(v / 1e7, 5) for v in struct.unpack_from("<ii", h, 119)] + [h[118]],
        "schema": meta.get("schema") or ("protomaps-basemap" if version[:1].isdigit() else None),
        "schema_version": version,
        "osm_snapshot": str(meta.get("planetiler:osm:osmosisreplicationtime", ""))[:10] or None,
        # the file's own credit (the public-domain overview: "Natural Earth; US Census Bureau"),
        # without HTML; an OpenStreetMap build that names none gets the OpenStreetMap credit
        "attribution": re.sub(r"<[^>]+>", "", str(meta.get("attribution") or "")).strip() or None,
    }


def overview_problems(header: dict, size: int) -> tuple[list[str], list[str]]:
    """(reasons to refuse, warnings) for an overview map, from the app's compose rules."""
    rules = json.loads(COMPOSE_RULES.read_text(encoding="utf-8"))
    refuse, warn = [], []
    major = header["schema_version"].split(".")[0]
    if header["schema"] != rules["schema"] or major != str(rules["schema_major"]):
        refuse.append(f"tile schema {header['schema']} {header['schema_version'] or '?'}: the app's style draws "
                      f"{rules['schema']} {rules['schema_major']}.x ({rel(COMPOSE_RULES)})")
    if header["max_zoom"] > rules["overview_max_zoom"]:
        warn.append(f"tiles up to z{header['max_zoom']}, but the app draws the overview only up to "
                    f"z{rules['overview_max_zoom']:g}: the zooms above are wasted APK size")
    if size > OVERVIEW_BUDGET_MB * 1_000_000:
        warn.append(f"{size / 1e6:.1f} MB is over the owner's budget of about {OVERVIEW_BUDGET_MB} MB for the overview")
    return refuse, warn


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def sync_basemap(src: Path | None) -> dict:
    if src is None:
        cands = sorted((REPO / "data" / "map" / "packs").glob("base-us-*-z0-7.pmtiles"))
        if not cands:
            sys.exit("no data/map/packs/base-us-*-z0-7.pmtiles; give --basemap")
        src = cands[-1]
    header = pmtiles_header(src)
    refuse, warn = overview_problems(header, src.stat().st_size)
    for w in warn:
        print(f"basemap: warning: {w}")
    if refuse:
        sys.exit(f"basemap: {src.name} refused, nothing copied: " + "; ".join(refuse))
    BASE_OUT.mkdir(parents=True, exist_ok=True)
    dst = BASE_OUT / "us-base.pmtiles"
    shutil.copyfile(src, dst)
    record = {
        "source": src.name,
        "bytes": dst.stat().st_size,
        "sha256": sha256_of(dst),
        **header,
        "attribution": header["attribution"] or "© OpenStreetMap contributors",
        "copied_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    (BASE_OUT / "us-base.json").write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"basemap: {src.name} {record['bytes'] / 1e6:.1f} MB, z{header['min_zoom']}-{header['max_zoom']}, "
          f"schema {header['schema_version']}, OSM {header['osm_snapshot']}, sha256 {record['sha256'][:12]} -> {rel(dst)}")
    return record


def check() -> int:
    ok = True
    vf = STYLE_OUT / "bundle-version.json"
    if not vf.is_file():
        print("style: not copied yet")
        ok = False
    else:
        rec = json.loads(vf.read_text(encoding="utf-8"))
        now, _, _ = tree_hash(BUNDLE)
        copy, _, _ = tree_hash(STYLE_OUT)
        print(f"style: copied {rec['content_sha256'][:12]} at {rec['copied_utc']}; bundle now {now[:12]}; "
              f"copy on disk {copy[:12]}")
        ok &= now == rec["content_sha256"] == copy
    bf = BASE_OUT / "us-base.json"
    pm = BASE_OUT / "us-base.pmtiles"
    if not bf.is_file() or not pm.is_file():
        print("basemap: not copied (the app then shows no overview map)")
        ok = False
    else:
        rec = json.loads(bf.read_text(encoding="utf-8"))
        on_disk = sha256_of(pm)
        print(f"basemap: copied {rec['source']} {rec['bytes'] / 1e6:.1f} MB sha256 {rec['sha256'][:12]}; "
              f"file on disk {on_disk[:12]}")
        ok &= on_disk == rec["sha256"] and pm.stat().st_size == rec["bytes"]
        refuse, warn = overview_problems(pmtiles_header(pm), pm.stat().st_size)
        for p in refuse + warn:
            print(f"basemap: {'REFUSED' if p in refuse else 'warning'}: {p}")
        ok &= not refuse
    print("in sync" if ok else "NOT in sync")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("what", nargs="?", default="all", choices=("all", "style", "basemap", "check"))
    ap.add_argument("--basemap", type=Path, help="PMTiles file for the overview map")
    a = ap.parse_args()
    if a.what == "check":
        return check()
    if a.what in ("all", "style"):
        sync_style()
    if a.what in ("all", "basemap"):
        sync_basemap(a.basemap)
    return 0


if __name__ == "__main__":
    sys.exit(main())
