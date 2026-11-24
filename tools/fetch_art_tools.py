"""Fetch the portable build tools of the map artwork track (sprites, shields, markers).

Usage (from the repo root, any Python 3.12):
    python tools/fetch_art_tools.py [--force] [--platform win-x86_64|linux-x86_64] [spreet] [resvg]

Downloads each archive into data/art/cache/, checks its pinned SHA-256, and
unpacks it into tools/bin/<TOOL>/. Nothing is installed system-wide and PATH is
not changed: the art scripts call the binaries by their path
(tools/bin/<TOOL>/<TOOL>.exe on Windows, tools/bin/<TOOL>/<TOOL> on Linux).
The platform is detected; --platform only overrides it (for example to
download and check the Linux archives from Windows).

Pinned tools (both build time only, nothing of them ships to a device):
  spreet  0.14.0 (MIT) - rasterises a folder of SVGs and packs them into a
          MapLibre sprite sheet + JSON index (1x and 2x). It is the packer
          Protomaps' own sprite generator is built on. Bundles resvg 0.49.
  resvg   0.49.0 (Apache-2.0 OR MIT) - renders single SVGs to PNG, used for
          the position-marker exports at 1x-4x and the contact sheet.

The SHA-256 values are the digests GitHub publishes for these release assets
(api.github.com/repos/<owner>/<repo>/releases/tags/<tag>, field "digest"),
re-checked here after download. The Windows builds are the ones the pipeline
was run with; the Linux builds (static musl for spreet) are pinned the same
way but the pipeline has not been run on Linux.
"""
import argparse
import hashlib
import os
import platform
import shutil
import stat
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / "data" / "art" / "cache"
BIN = ROOT / "tools" / "bin"

TOOLS = {
    "spreet": {
        "version": "0.14.0",
        "licence": "MIT",
        "builds": {
            "win-x86_64": ("https://github.com/flother/spreet/releases/download/v0.14.0/spreet-x86_64-pc-windows-msvc.zip",
                           "ee74c25d9877c5f6ec7ff6e4f496b14e720da080bbe7e3ab9bee2b7201702820"),
            "linux-x86_64": ("https://github.com/flother/spreet/releases/download/v0.14.0/spreet-x86_64-unknown-linux-musl.tar.gz",
                             "187e93895c0032736994ff79bf1268284cdcb96b0e3a911d9305e1208d3c9df9"),
        },
    },
    "resvg": {
        "version": "0.49.0",
        "licence": "Apache-2.0 OR MIT",
        "builds": {
            "win-x86_64": ("https://github.com/linebender/resvg/releases/download/v0.49.0/resvg-win-x86_64.zip",
                           "38af9631b37e61118a32a5ef31e6be6af083eda63b4c17ffc4fdc63e449277df"),
            "linux-x86_64": ("https://github.com/linebender/resvg/releases/download/v0.49.0/resvg-linux-x86_64.tar.gz",
                             "216637e75e909e53e3098b0875cd5f7da2fdc8b4359771b4a29b539afff9d3f5"),
        },
    },
}


def this_platform() -> str:
    machine = platform.machine().lower()
    if machine not in ("amd64", "x86_64"):
        raise SystemExit(f"no pinned build for CPU '{machine}' (only x86_64)")
    if sys.platform == "win32":
        return "win-x86_64"
    if sys.platform.startswith("linux"):
        return "linux-x86_64"
    raise SystemExit(f"no pinned build for '{sys.platform}' (only Windows and Linux)")


def binary(name: str, plat: str) -> str:
    return f"{name}.exe" if plat.startswith("win") else name


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def unpack(archive: Path, dest: Path) -> None:
    root = dest.resolve()

    def safe(member: str) -> None:
        target = (dest / member).resolve()
        if root not in target.parents and target != root:
            raise SystemExit(f"unsafe path in archive: {member}")
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as z:
            for member in z.namelist():
                safe(member)
            z.extractall(dest)
    else:
        with tarfile.open(archive, "r:gz") as t:
            for m in t.getmembers():
                safe(m.name)
                if not (m.isfile() or m.isdir()):
                    raise SystemExit(f"unexpected member type in archive: {m.name}")
            t.extractall(dest, filter="data")


def fetch(name: str, spec: dict, plat: str, force: bool) -> None:
    url, digest = spec["builds"][plat]
    exe = binary(name, plat)
    dest = BIN / name if plat == this_platform_or_none() else BIN / f"{name}-{plat}"
    if (dest / exe).exists() and (dest / "VERSION").exists() and not force:
        if (dest / "VERSION").read_text().strip() == spec["version"]:
            print(f"skip {name} {spec['version']} {plat} (present)")
            return
    CACHE.mkdir(parents=True, exist_ok=True)
    archive = CACHE / f"{name}-{spec['version']}-{Path(url).name}"
    if not archive.exists() or sha256(archive) != digest:
        print(f"download {url}")
        req = urllib.request.Request(url, headers={"User-Agent": "beamline-tools"})
        with urllib.request.urlopen(req, timeout=120) as r, open(archive, "wb") as f:
            shutil.copyfileobj(r, f)
    got = sha256(archive)
    if got != digest:
        archive.unlink(missing_ok=True)
        raise SystemExit(f"SHA-256 mismatch for {name} {plat}: got {got}, want {digest}")
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True)
    unpack(archive, dest)
    # some archives put the binary in a sub-folder; move it up so the path is fixed
    if not (dest / exe).exists():
        found = list(dest.rglob(exe))
        if len(found) != 1:
            raise SystemExit(f"{name}: {exe} not found in the archive")
        shutil.move(str(found[0]), dest / exe)
    if not plat.startswith("win"):
        os.chmod(dest / exe, os.stat(dest / exe).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    (dest / "VERSION").write_text(spec["version"] + "\n")
    print(f"ok {name} {spec['version']} {plat} (sha256 {digest[:12]}...) -> "
          f"{(dest / exe).relative_to(ROOT).as_posix()}")


def this_platform_or_none():
    try:
        return this_platform()
    except SystemExit:
        return None


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--platform", choices=["win-x86_64", "linux-x86_64"],
                    help="download another platform's build into tools/bin/<TOOL>-<PLATFORM>/ (for checking the pins)")
    ap.add_argument("tools", nargs="*", default=list(TOOLS), help="subset of: " + ", ".join(TOOLS))
    a = ap.parse_args()
    plat = a.platform or this_platform()
    for name in a.tools:
        fetch(name, TOOLS[name], plat, a.force)
    return 0


if __name__ == "__main__":
    sys.exit(main())
