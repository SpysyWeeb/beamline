"""Install the pinned Android SDK packages Beamline's Android build and emulator tests need.

Usage (from the repo root, any OS with Python 3.10+):

    python tools/fetch_android_sdk.py --accept-licenses   # you have read and accept Google's SDK terms
    python tools/fetch_android_sdk.py --accept-licenses --no-emulator   # build only
    python tools/fetch_android_sdk.py --accept-licenses --sdk-root D:/Android/Sdk

SDK location, first match wins: --sdk-root, ANDROID_HOME, ANDROID_SDK_ROOT, then the
standard per-user folder (Windows %LOCALAPPDATA%/Android/Sdk, macOS ~/Library/Android/sdk,
Linux ~/Android/Sdk). No PATH, registry or shell-profile changes.

What it does:
 1. Downloads the pinned Android SDK command-line tools zip from dl.google.com, checks its SHA-1
    (the checksum Google publishes in repository2-3.xml) and unpacks it to <sdk>/cmdline-tools/latest.
 2. Installs the packages below, each pinned to a revision, with the Android CLI (`android sdk install
    <path>@<revision>`). Since command-line tools 23.0, `sdkmanager` is only a wrapper around
    this CLI, and on Windows that wrapper splits package names at ';'. So this script calls the
    CLI directly, always with --no-metrics (the CLI otherwise sends usage statistics to Google).
 3. Lists what is installed and prints the disk use.

Side effects outside the SDK folder, made by Google's tools themselves (not by this script):
 - the CLI launcher downloads its full runtime into ~/.android/bin and ~/.android/cli (about 330 MB),
 - ~/.android/cli/tos_displayed records that the SDK terms were shown.

Then put `sdk.dir=<sdk path>` (forward slashes, the drive colon escaped as a backslash and a colon, as Android lint's
PropertyEscape check asks) into android/local.properties.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

# Android SDK command-line tools 23.0 (package "cmdline-tools;23.0", build 16111833),
# checksums copied from https://dl.google.com/android/repository/repository2-3.xml (2026-11-15).
CMDLINE_TOOLS_VERSION = "23.0"
CMDLINE_TOOLS = {
    "windows": ("commandlinetools-win-16111833_latest.zip", "57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d"),
    "linux": ("commandlinetools-linux-16111833_latest.zip", "e025545c62a8e64c7559119566a569fb1dec5f60"),
    "mac-x86_64": ("commandlinetools-mac_x86_64-16111833_latest.zip", "112cf9618794a997ff273537d55bee02c22abffe"),
    "mac-arm64": ("commandlinetools-mac_arm64-16111833_latest.zip", "ad03dc49bfacfd52c110b14104ea548b8a07e830"),
}
BASE_URL = "https://dl.google.com/android/repository/"

# SDK package path -> pinned revision (stable channel, 2026-11-15).
# Keep in step with android/gradle/libs.versions.toml (compileSdk, targetSdk, buildTools).
PACKAGES_BUILD = {
    "platform-tools": "37.0.1",
    "platforms/android-36": "2",          # targetSdk 36 (Google Play target since 2026-08-31)
    "platforms/android-37.0": "2",        # compileSdk 37: current AndroidX libraries require it
    "build-tools/36.0.0": "36.0.0",       # default build-tools of Android Gradle Plugin 9.5
}
PACKAGES_EMULATOR = {
    "emulator": "37.2.13",
    "system-images/android-36/default/x86_64": "2",  # AOSP image: no Google apps that phone home
}


def host_key() -> str:
    s = platform.system()
    if s == "Windows":
        return "windows"
    if s == "Linux":
        return "linux"
    if s == "Darwin":
        return "mac-arm64" if platform.machine().lower() in ("arm64", "aarch64") else "mac-x86_64"
    sys.exit(f"unsupported host OS: {s}")


def default_sdk_root() -> Path:
    for var in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(var):
            return Path(os.environ[var])
    s = platform.system()
    if s == "Windows":
        return Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"
    if s == "Darwin":
        return Path.home() / "Library" / "Android" / "sdk"
    return Path.home() / "Android" / "Sdk"


def sha1_of(path: Path) -> str:
    h = hashlib.sha1()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url: str, dest: Path, sha1: str, attempts: int = 3) -> None:
    """Download url to dest and check its SHA-1, retrying (a shared or slow link can cut
    a transfer short without an error; the first try on the reference machine did)."""
    for attempt in range(1, attempts + 1):
        print(f"download {url} (attempt {attempt})", flush=True)
        req = urllib.request.Request(url, headers={"User-Agent": "beamline-fetch-android-sdk"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r, open(dest, "wb") as f:
                expected = int(r.headers.get("Content-Length", "0") or 0)
                shutil.copyfileobj(r, f, 1 << 20)
        except OSError as e:
            print(f"  failed: {e}", flush=True)
            continue
        got = sha1_of(dest)
        if got == sha1:
            print(f"  ok, {dest.stat().st_size} bytes, sha1 {sha1}", flush=True)
            return
        print(f"  SHA-1 mismatch: got {got} ({dest.stat().st_size} of {expected} bytes), expected {sha1}",
              flush=True)
    dest.unlink(missing_ok=True)
    sys.exit(f"could not download {url} with the expected SHA-1 after {attempts} attempts")


def install_cmdline_tools(sdk: Path, local_zip: Path | None) -> Path:
    latest = sdk / "cmdline-tools" / "latest"
    props = latest / "source.properties"
    if props.exists() and f"Pkg.Revision={CMDLINE_TOOLS_VERSION}" in props.read_text():
        print(f"cmdline-tools {CMDLINE_TOOLS_VERSION} already in {latest}")
        return latest
    name, sha1 = CMDLINE_TOOLS[host_key()]
    with tempfile.TemporaryDirectory() as td:
        if local_zip:
            if sha1_of(local_zip) != sha1:
                sys.exit(f"{local_zip} does not have the pinned SHA-1 {sha1}")
            print(f"using {local_zip} (sha1 ok)")
            z = local_zip
        else:
            z = Path(td) / name
            download(BASE_URL + name, z, sha1)
        with zipfile.ZipFile(z) as zf:
            zf.extractall(td)
        if latest.exists():
            shutil.rmtree(latest)
        latest.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(Path(td) / "cmdline-tools"), str(latest))
    if os.name != "nt":
        for p in (latest / "bin").iterdir():
            p.chmod(0o755)
    print(f"cmdline-tools {CMDLINE_TOOLS_VERSION} installed in {latest}")
    return latest


def android_cli(latest: Path, sdk: Path) -> list[str]:
    exe = latest / "bin" / ("android.exe" if os.name == "nt" else "android")
    if not exe.exists():
        sys.exit(f"{exe} not found: command-line tools {CMDLINE_TOOLS_VERSION} should contain it")
    return [str(exe), "--no-metrics", f"--sdk={sdk.as_posix()}"]


ANSI = re.compile(r"\x1b\[[0-9;]*m")


def run(cmd: list[str]) -> str:
    print("+", " ".join(cmd[1:]), flush=True)
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    out = ANSI.sub("", (p.stdout or "") + (p.stderr or ""))
    print(out.rstrip(), flush=True)
    if p.returncode != 0 or "not found" in out:
        sys.exit(f"command failed ({p.returncode}): {' '.join(cmd[1:])}")
    return out


def folder_size(p: Path) -> int:
    total = 0
    for root, _dirs, files in os.walk(p):
        for f in files:
            try:
                total += (Path(root) / f).stat().st_size
            except OSError:
                pass
    return total


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sdk-root", type=Path, default=None)
    ap.add_argument("--accept-licenses", action="store_true",
                    help="required: confirms you accept the Android SDK terms "
                         "(https://developer.android.com/studio/terms) for this install")
    ap.add_argument("--no-emulator", action="store_true", help="skip the emulator and system image")
    ap.add_argument("--cmdline-tools-zip", type=Path, default=None,
                    help="use an already downloaded command-line tools zip (its SHA-1 is still checked)")
    a = ap.parse_args()
    if not a.accept_licenses:
        sys.exit("Read https://developer.android.com/studio/terms, then run again with --accept-licenses")

    sdk = (a.sdk_root or default_sdk_root()).resolve()
    sdk.mkdir(parents=True, exist_ok=True)
    print(f"SDK root: {sdk}")
    latest = install_cmdline_tools(sdk, a.cmdline_tools_zip)
    cli = android_cli(latest, sdk)

    wanted = dict(PACKAGES_BUILD)
    if not a.no_emulator:
        wanted.update(PACKAGES_EMULATOR)
    run(cli + ["sdk", "install", *[f"{p}@{v}" for p, v in wanted.items()]])
    run(cli + ["sdk", "list"])

    print("disk use:")
    for child in sorted(sdk.iterdir()):
        if child.is_dir():
            print(f"  {child.name:20s} {folder_size(child) / 1e6:9.1f} MB")
    print(f"  {'total':20s} {folder_size(sdk) / 1e6:9.1f} MB")
    print("next: put this line in android/local.properties:")
    # a properties file needs the drive letter's colon escaped (lint PropertyEscape)
    print("  sdk.dir=" + sdk.as_posix().replace(":", "\\:"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
