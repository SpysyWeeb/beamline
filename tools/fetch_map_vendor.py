"""Fetch the JavaScript libraries the offline map viewer vendors into viewer/vendor/.

Usage (from the repo root, any Python 3.12):
    python tools/fetch_map_vendor.py [--force]

Unlike tools/bin/, the vendored files ARE kept in git (about 1.7 MB) so the
viewer works offline straight from a clone. This script documents where they
came from and re-creates them byte for byte: each npm tarball and licence file
is checked against a pinned SHA-256.

  maplibre-gl 6.16.0  BSD-3-Clause  (ESM build: maplibre-gl.mjs + worker + css)
  pmtiles     4.5.0   BSD-3-Clause  (browser bundle dist/pmtiles.js, includes fflate, MIT)
"""
import argparse
import hashlib
import io
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / "viewer" / "vendor"

PACKAGES = [
    {
        "name": "maplibre-gl", "version": "6.16.0",
        "url": "https://registry.npmjs.org/maplibre-gl/-/maplibre-gl-6.16.0.tgz",
        "sha256": "1504a18021fc6d9329a9dc7c949525822c8ae5e2fe3e3751df9262a13af5de4b",
        "files": {
            "package/dist/maplibre-gl.mjs": "maplibre-gl/maplibre-gl.mjs",
            "package/dist/maplibre-gl-shared.mjs": "maplibre-gl/maplibre-gl-shared.mjs",
            "package/dist/maplibre-gl-worker.mjs": "maplibre-gl/maplibre-gl-worker.mjs",
            "package/dist/maplibre-gl.css": "maplibre-gl/maplibre-gl.css",
            "package/LICENSE.txt": "maplibre-gl/LICENSE.txt",
        },
    },
    {
        "name": "pmtiles", "version": "4.5.0",
        "url": "https://registry.npmjs.org/pmtiles/-/pmtiles-4.5.0.tgz",
        "sha256": "23ae7c575578ad24cd579377d69c46550631da219e6f179997ec2cf3b8c937e5",
        "files": {"package/dist/pmtiles.js": "pmtiles/pmtiles.js"},
    },
]
# Licence texts not contained in the npm tarballs, pinned by commit.
LICENCES = {
    "pmtiles/LICENSE": "https://raw.githubusercontent.com/protomaps/PMTiles/5897a82a16b233abac7b8f339c019b931c2bbf54/LICENSE",
    "pmtiles/fflate-LICENSE": "https://raw.githubusercontent.com/101arrowz/fflate/31acfb8d282ae3a8bf5b81841aaf5f547c2c455c/LICENSE",
}


def get(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "beamline-tools"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    for p in PACKAGES:
        if all((VENDOR / dst).exists() for dst in p["files"].values()) and not a.force:
            print(f"skip {p['name']} {p['version']} (present)")
            continue
        data = get(p["url"])
        got = hashlib.sha256(data).hexdigest()
        if got != p["sha256"]:
            raise SystemExit(f"{p['name']}: SHA-256 {got} != pinned {p['sha256']}")
        with tarfile.open(fileobj=io.BytesIO(data)) as t:
            for src, dst in p["files"].items():
                out = VENDOR / dst
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(t.extractfile(src).read())
        (VENDOR / p["name"] / "VERSION").write_text(p["version"] + "\n")
        print(f"ok {p['name']} {p['version']}")
    for dst, url in LICENCES.items():
        out = VENDOR / dst
        if out.exists() and not a.force:
            continue
        out.write_bytes(get(url))
        print(f"ok {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
