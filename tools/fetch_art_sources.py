"""Fetch the upstream vector sources of the bundled map icons, pinned by SHA-256.

Usage (from the repo root, any Python 3.12):
    python tools/fetch_art_sources.py

Writes data/art/upstream/. Re-running skips files that are present and match.

What and why:
  refill.svg        protomaps/basemaps sprites/refill.svg at commit 42ffaaa4 (the
                    same commit as the tile profile in packbuilder/map/versions.json).
                    One SVG with ~580 icon groups. The Protomaps v4 sprite sheets
                    (protomaps/basemaps-assets sprites/v4) are generated from it by
                    sprites/src/main.rs with the colours in sprites/flavors/*.json.
                    Icons derived from tangrams/icons (MIT, Mapzen); the repository
                    is BSD-3-Clause code + CC0 visual design (LICENSE.md).
  flavors-*.json    the colour pairs Protomaps uses per icon category.
  spritegen-main.rs Protomaps' sprite generator, kept to document the recolouring
                    rule our build follows (read, not run).
  LICENSE-basemaps.md, LICENSE-tangrams-icons.md  licence texts.
  v4/*.png|json     the bundled v4 sheets (basemaps-assets 028c18f), only to check
                    that our rebuild reproduces them.
"""
import hashlib
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "data" / "art" / "upstream"
BM = "https://raw.githubusercontent.com/protomaps/basemaps/42ffaaa4a85a41bfcb23e43cc0f5b492a5eca123"
BMA = "https://raw.githubusercontent.com/protomaps/basemaps-assets/028c18f713baecad011301ff7a69acc39bcc2ae7"
TANGRAMS = "https://raw.githubusercontent.com/tangrams/icons/92510779634f4a006c61ea70e50cb8c52c765a81"

FILES = {
    "refill.svg": (f"{BM}/sprites/refill.svg", "f5abcc79ac60a4486d077b27db0f945a261d781392c68d5fd8d8e54bbc417a65"),
    "flavors-light.json": (f"{BM}/sprites/flavors/light.json", "ad535ff37255be896d608b78bca1444c9cdd5e725e8523b346364c4112be2ceb"),
    "flavors-dark.json": (f"{BM}/sprites/flavors/dark.json", "3c9f1d6ae1f8e5bd35362d55d65a08622846a282faf3a41596b3328d65b6397e"),
    "spritegen-main.rs": (f"{BM}/sprites/src/main.rs", "b3dd0cc11d81972a7f17388314b9304ad7a6c64970b5108a1163d5feee2229c0"),
    "LICENSE-basemaps.md": (f"{BM}/LICENSE.md", "74f975cfedd168098c43b5cfd6e587e40604684c4ffcec3e90d24b3b09c061b0"),
    "LICENSE-tangrams-icons.md": (f"{TANGRAMS}/LICENSE.md", "46d0ca73c10d7366ef7bf3932d8508267096393ccc9ef3a41d1b1d1fe37023f1"),
    "v4/light.png": (f"{BMA}/sprites/v4/light.png", "b6a34640917bdc57d0bd080836db33376371a3312ebe7b849045268015de3481"),
    "v4/light.json": (f"{BMA}/sprites/v4/light.json", "bfac76cf7ed5c2aa2992695904056a1c6b07785b7fd20e6c640cb44fd6244a2e"),
    "v4/light@2x.png": (f"{BMA}/sprites/v4/light@2x.png", "23f6e9df27c2e9a14385763980a24e3608966bfc8192a106058bc2ca959ab563"),
    "v4/light@2x.json": (f"{BMA}/sprites/v4/light@2x.json", "1fb5b123fbe35d2e1f6ac171513ce01df89e671273a417ee89c44886a4c132f0"),
    "v4/dark.png": (f"{BMA}/sprites/v4/dark.png", "8920f68e8f9f7a4188da43e62a81bbc426532579fff7b5f27aad09e130f4246e"),
    "v4/dark.json": (f"{BMA}/sprites/v4/dark.json", "bfac76cf7ed5c2aa2992695904056a1c6b07785b7fd20e6c640cb44fd6244a2e"),
    "v4/dark@2x.png": (f"{BMA}/sprites/v4/dark@2x.png", "6e01b4e366d304f27bbd55390511a3d4e39aa1d03eb3e82f873f7ea32e74cbe4"),
    "v4/dark@2x.json": (f"{BMA}/sprites/v4/dark@2x.json", "1fb5b123fbe35d2e1f6ac171513ce01df89e671273a417ee89c44886a4c132f0"),
}


def main() -> int:
    for rel, (url, want) in FILES.items():
        dest = OUT / rel
        if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == want:
            continue
        req = urllib.request.Request(url, headers={"User-Agent": "beamline-art"})
        with urllib.request.urlopen(req, timeout=120) as r:
            data = r.read()
        got = hashlib.sha256(data).hexdigest()
        if got != want:
            raise SystemExit(f"{rel}: SHA-256 {got} does not match the pinned {want}")
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        print(f"got {rel} ({len(data)} bytes)")
    print("art sources ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
