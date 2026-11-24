"""Publish an app release on GitHub (docs/app-updates-design.md 5, item 41).

    python tools/release.py --notes "What changed"              # dry run: build, check, write data/releases/v<name>/
    python tools/release.py --notes "What changed" --publish    # the same, then create the GitHub release
    python tools/release.py --notes "..." --skip-build          # reuse the APKs of an earlier dry run

The owner decided (2026-11-24) that a release follows every batch they approve; this script is the
only way one is made. It builds two APKs signed with the owner's key (the universal one, and an
arm64-v8a one with -Pbeamline.abi), checks that both are signed by the owner's certificate, that the
arm64 one holds only arm64 native code, and that the version is above the latest published release;
it writes update.json (format beamline-update/1, read by the app's AppUpdate.parse) and, with
--publish, runs `gh release create v<name>` at the pushed HEAD of main. It refuses a tree with
uncommitted changes, a HEAD that is not origin/main, a tag that exists, and a version not above the
last release. The key and its password stay in android/local.properties; this script never reads them.
Standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
GH_REPO = "SpysyWeeb/beamline"
LATEST_UPDATE = f"https://github.com/{GH_REPO}/releases/latest/download/update.json"
# The owner's release certificate (CN=Beamline): its SHA-256 is public, printed by any APK signed with it.
OWNER_CERT_SHA256 = "d11c6247e6d28c8293fdb61dc10ef62198667327fa83c2e3bb969d6fee818d5f"
BUILT_APK = REPO / "data/gradle/i/build/app/outputs/apk/release/app-release.apk"
ABI = "arm64-v8a"


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, cwd=REPO, check=True, text=True, encoding="utf-8", errors="replace", **kw)


def version() -> tuple[int, str, int]:
    g = (REPO / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    code = int(re.search(r"^\s*versionCode = (\d+)\s*$", g, re.M).group(1))
    name = re.search(r'^\s*versionName = "([^"]+)"\s*$', g, re.M).group(1)
    toml = (REPO / "android/gradle/libs.versions.toml").read_text(encoding="utf-8")
    min_sdk = int(re.search(r'^android-minSdk = "(\d+)"', toml, re.M).group(1))
    return code, name, min_sdk


def apksigner() -> Path:
    lp = (REPO / "android/local.properties").read_text(encoding="utf-8")
    sdk = re.search(r"^sdk\.dir=(.+)$", lp, re.M).group(1).strip().replace("\\:", ":").replace("\\\\", "/")
    tools = sorted((Path(sdk) / "build-tools").iterdir(), key=lambda p: [int(x) if x.isdigit() else 0 for x in re.split(r"[.-]", p.name)])
    for name in ("apksigner.bat", "apksigner"):
        if (tools[-1] / name).exists():
            return tools[-1] / name
    raise SystemExit("apksigner not found in the SDK's newest build-tools")


def build(out: Path, name: str) -> dict[str, Path]:
    files = {}
    for abi, file in ((None, f"beamline-{name}.apk"), (ABI, f"beamline-{name}-arm64.apk")):
        cmd = ["sh", "tools/round/gradle.sh", "i", ":app:assembleRelease", "-q"] + ([f"-Pbeamline.abi={abi}"] if abi else [])
        print("building", file, "...", flush=True)
        run(cmd)
        shutil.copyfile(BUILT_APK, out / file)
        files[abi or "universal"] = out / file
    return files


def check_apk(path: Path, abi: str, signer: Path) -> None:
    res = subprocess.run([str(signer), "verify", "--print-certs", str(path)], capture_output=True, text=True)
    if res.returncode != 0 or f"certificate SHA-256 digest: {OWNER_CERT_SHA256}" not in res.stdout:
        raise SystemExit(f"{path.name}: not signed by the owner's certificate (is android/local.properties' key set?)")
    abis = {n.split("/")[1] for n in zipfile.ZipFile(path).namelist() if n.startswith("lib/") and n.count("/") >= 2}
    if abi != "universal" and abis != {abi}:
        raise SystemExit(f"{path.name}: native code for {sorted(abis)}, expected only {abi}")


def latest_published() -> int | None:
    try:
        with urllib.request.urlopen(urllib.request.Request(LATEST_UPDATE, headers={"User-Agent": "beamline-release"}), timeout=30) as r:
            return int(json.loads(r.read().decode("utf-8"))["version_code"])
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--notes", required=True, help="what changed, for the release page and the app's note")
    ap.add_argument("--publish", action="store_true", help="create the GitHub release (otherwise a dry run)")
    ap.add_argument("--skip-build", action="store_true", help="use the APKs already in data/releases/v<name>/")
    a = ap.parse_args()

    code, name, min_sdk = version()
    out = REPO / "data" / "releases" / f"v{name}"
    out.mkdir(parents=True, exist_ok=True)
    last = latest_published()
    if last is not None and code <= last:
        raise SystemExit(f"version code {code} is not above the latest release's {last}: raise it in android/app/build.gradle.kts")
    head = None
    if a.publish:
        if run(["git", "status", "--porcelain", "--untracked-files=no"], capture_output=True).stdout.strip():
            raise SystemExit("uncommitted changes: commit and push first, so the release is the public code")
        run(["git", "fetch", "-q", "origin"])
        head = run(["git", "rev-parse", "HEAD"], capture_output=True).stdout.strip()
        if head != run(["git", "rev-parse", "origin/main"], capture_output=True).stdout.strip():
            raise SystemExit("HEAD is not origin/main: push main first")
        if subprocess.run(["gh", "release", "view", f"v{name}", "--repo", GH_REPO], capture_output=True).returncode == 0:
            raise SystemExit(f"release v{name} exists already")

    files = {"universal": out / f"beamline-{name}.apk", ABI: out / f"beamline-{name}-arm64.apk"}
    if not a.skip_build:
        files = build(out, name)
    signer = apksigner()
    for abi, p in files.items():
        if not p.is_file():
            raise SystemExit(f"{p} missing: build first (leave out --skip-build)")
        check_apk(p, abi, signer)
    update = {
        "format": "beamline-update/1",
        "version_code": code,
        "version_name": name,
        "min_sdk": min_sdk,
        "published_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "notes": a.notes,
        "apks": {abi: {"file": p.name, "size": p.stat().st_size, "sha256": sha256(p)} for abi, p in sorted(files.items())},
    }
    (out / "update.json").write_text(json.dumps(update, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    for abi, p in sorted(files.items()):
        print(f"  {abi:10s} {p.name}  {p.stat().st_size / 1048576:.1f} MB")
    print(f"  update.json  version {name} ({code}), latest published: {last}")
    if not a.publish:
        print(f"dry run: files in {out.relative_to(REPO).as_posix()}; publish with --publish")
        return 0
    run(["gh", "release", "create", f"v{name}", "--repo", GH_REPO, "--target", head, "--title", f"Beamline {name}",
         "--notes", a.notes, str(out / "update.json"), *(str(p) for p in files.values())])
    print(f"published https://github.com/{GH_REPO}/releases/tag/v{name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
