"""Export the packs repository (beamline-packs) from this one: the files its weekly build needs, nothing else.

    python tools/export_packs_repo.py [--dest ../beamline-packs] [--check] [--extra-terms FILE]

The packs repository is a copy, never edited by hand. Its own files (README, the workflow, the
weekly entry point, the privacy scan, its settings) are written here under packs-repo/; the builder
files it runs are listed in FILES below and keep their paths (android/scripts/read_pack.py goes to
tools/read_pack.py). SOURCE.md records this repository's commit. One file is changed on the way
(TRANSFORMS): packbuilder/routing/presets.json loses its "note" keys, which describe the owner's own
test places; no code reads them.

Before anything is written, every file is scanned with packs-repo/build/privacy_scan.py (generic
patterns: home paths, private addresses, e-mail addresses, token-like strings, device identifiers,
binary or large files). One finding refuses the whole export: nothing is written. --extra-terms FILE
adds a local list of words (one per line, case-insensitive; the file stays outside both
repositories) that no exported file may contain; it is how specific private terms are checked
without writing them into the packs repository.

The destination ends up holding exactly the exported files (anything else in it is deleted; its .git
folder is never touched), so running the export again gives the same files. --check writes nothing
and exits 1 when the destination differs. Standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
OWN = REPO / "packs-repo"

# (path in this repository, path in the packs repository); the same path unless given.
FILES = [
    # map part (Planetiler + the Protomaps profile with our patch)
    *[f"packbuilder/map/{f}" for f in (
        "README.md", "build_pack.py", "build_profile.py", "census_clip.py", "finish_pack.py", "measure.py",
        "fetch_sources.py", "mvt.py", "tile_stats.py", "build_config.json", "versions.json", "requirements.txt",
        "profile/beamline-profile.patch")],
    # routing part (Valhalla 3.9.1 from the pyvalhalla wheel)
    *[f"packbuilder/routing/{f}" for f in (
        "README.md", "build.py", "common.py", "merge_osm.py", "driveways.py", "tzdb.py", "fetch_boundaries.py", "packs.py",
        "route_lib.py", "presets.json", "device_config.json", "trips.json", "requirements.txt")],
    # search part, its query engine and the tests the weekly check runs
    *[f"packbuilder/search/{f}" for f in (
        "README.md", "build.py", "osm_extract.py", "doors.py", "lines.py", "signals.py", "geo.py", "rules.py", "normalize.py", "query.py",
        "categories.json", "categories_idterms.json", "abbreviations.json", "ranking.json",
        "THIRD_PARTY_NOTICES.md", "requirements.txt", "tests/run_tests.py", "tests/check_inputs.py",
        "tests/cases.json", "tests/cases_holdout.json", "tests/cases_review.json", "tests/points.json")],
    # house numbers (the build steps of the pack; the evaluation steps stay in the app repository)
    *[f"packbuilder/addresses/{f}" for f in (
        "README.md", "common.py", "run.py", "step_download.py", "step_nad.py", "step_extra.py", "step_osm.py",
        "step_tiger.py", "step_build.py", "geoassign.py", "key_fingerprint.py", "sources.json",
        "sources.lock.json", "requirements.txt", "addrlib/__init__.py", "addrlib/fingerprint_spellings.json",
        "addrlib/geo.py", "addrlib/lookup_config.json", "addrlib/lookup.py", "addrlib/normalize.py",
        "addrlib/parser.py", "addrlib/us_street_norm.json")],
    # the pack and the catalog
    *[f"packbuilder/pack/{f}" for f in ("README.md", "assemble.py")],
    *[f"packbuilder/catalog/{f}" for f in (
        "README.md", "build_catalog.py", "catalog_format.py", "catalog_client.py", "serve_packs.py")],
    # the comma files of each pack and comma.json (docs/comma-pack-format.md); search-release/ is the frozen
    # release of the search code a comma runs (pinned in pins.json), search_probes.json the check's queries
    *[f"packbuilder/comma/{f}" for f in (
        "README.md", "common.py", "roads.py", "mapd.py", "search_code.py", "catalog.py", "check.py", "pins.json",
        "search_probes.json", "search-release/query.py", "search-release/lines.py", "search-release/normalize.py")],
    "docs/comma-pack-format.md",
    # tools
    "tools/fetch_map_tools.py", "tools/fetch_routing_env.py",
    ("android/scripts/read_pack.py", "tools/read_pack.py"),
]


def drop_notes(data: bytes) -> bytes:
    """presets.json without its notes ("note" and "*_note" keys): they tell how the rules were found,
    with the owner's own test cases (places), which stay in the app repository. No code reads them."""
    import json

    def walk(o):
        if isinstance(o, dict):
            return {k: walk(v) for k, v in o.items() if not (k == "note" or k.endswith("_note"))}
        if isinstance(o, list):
            return [walk(v) for v in o]
        return o
    return (json.dumps(walk(json.loads(data.decode("utf-8"))), indent=2, ensure_ascii=False) + "\n").encode("utf-8")


# Exported files that are changed on the way (path in the packs repository -> transform).
TRANSFORMS = {"packbuilder/routing/presets.json": drop_notes}


def load_scan():
    spec = importlib.util.spec_from_file_location("privacy_scan", OWN / "build" / "privacy_scan.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def git_head() -> tuple[str, bool]:
    try:
        sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO, capture_output=True, text=True,
                             check=True).stdout.strip()
        dirty = bool(subprocess.run(["git", "status", "--porcelain", "--untracked-files=no"], cwd=REPO,
                                    capture_output=True, text=True, check=True).stdout.strip())
        return sha, dirty
    except (OSError, subprocess.CalledProcessError):
        return "unknown", True


def plan() -> dict[str, bytes]:
    """Packs-repository path -> bytes, for every exported file (text normalised to LF line ends)."""
    out: dict[str, bytes] = {}
    for p in sorted(OWN.rglob("*")):
        if p.is_file() and "__pycache__" not in p.parts:
            out[p.relative_to(OWN).as_posix()] = p.read_bytes()
    for f in FILES:
        src, dst = (f, f) if isinstance(f, str) else f
        if dst in out:
            raise SystemExit(f"{dst} is both a packs-repo/ file and a builder file")
        out[dst] = (REPO / src).read_bytes()
        if dst in TRANSFORMS:
            out[dst] = TRANSFORMS[dst](out[dst])
    for k, v in out.items():
        if b"\0" not in v[:8192]:
            out[k] = v.replace(b"\r\n", b"\n")
    sha, dirty = git_head()
    out["SOURCE.md"] = (
        "# Source\n\n"
        "This repository is exported from the Beamline app repository by its `tools/export_packs_repo.py`.\n"
        "**Do not edit files here: edit them in the app repository and export again.**\n\n"
        f"- App repository commit: `{sha}`" + (" (with changes not yet committed there)" if dirty else "") + "\n"
        f"- Files: {len(out) + 1} (this one included)\n"
    ).encode("utf-8")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--dest", type=Path, default=REPO.parent / "beamline-packs")
    ap.add_argument("--check", action="store_true", help="write nothing; exit 1 when the destination differs")
    ap.add_argument("--extra-terms", type=Path, help="a local file of words no exported file may contain")
    a = ap.parse_args()
    files = plan()
    scan = load_scan()
    terms = []
    if a.extra_terms:
        terms = [t.strip().lower() for t in a.extra_terms.read_text(encoding="utf-8").splitlines()
                 if t.strip() and not t.startswith("#")]
    problems = []
    for name, data in files.items():
        if len(data) > scan.MAX_BYTES:
            problems.append(f"{name}: over 2 MB")
        if b"\0" in data[:8192]:
            problems.append(f"{name}: a binary file")
            continue
        text = data.decode("utf-8", "replace")
        problems += [f"{name}:{n}: {kind}" for n, kind in scan.scan_text(text)]
        low = text.lower()
        problems += [f"{name}: contains extra term #{i + 1}" for i, t in enumerate(terms) if t in low]
    if problems:
        print("\n".join(problems))
        print(f"export refused: {len(problems)} privacy finding(s); nothing was written")
        return 1
    dest = a.dest.resolve()
    present = {p.relative_to(dest).as_posix() for p in dest.rglob("*")
               if p.is_file() and ".git" not in p.relative_to(dest).parts} if dest.exists() else set()
    changed = sorted(n for n, d in files.items() if not (dest / n).is_file() or (dest / n).read_bytes() != d)
    # SOURCE.md names the commit; it alone differing does not make an export stale
    stale = sorted(present - set(files))
    if a.check:
        real = [c for c in changed if c != "SOURCE.md"]
        for n in real:
            print(f"differs: {n}")
        for n in stale:
            print(f"not exported: {n}")
        print(f"check: {len(real)} differing, {len(stale)} extra")
        return 1 if real or stale else 0
    for n in stale:
        (dest / n).unlink()
    for d in sorted((p for p in dest.rglob("*") if p.is_dir() and ".git" not in p.relative_to(dest).parts),
                    key=lambda p: len(p.parts), reverse=True):
        if not any(d.iterdir()):
            d.rmdir()
    for n in changed:
        out = dest / n
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(files[n])
    total = sum(len(d) for d in files.values())
    digest = hashlib.sha256(b"".join(n.encode() + b"\0" + hashlib.sha256(d).digest()
                                     for n, d in sorted(files.items()) if n != "SOURCE.md")).hexdigest()
    print(f"exported {len(files)} files, {total} bytes, to {dest.name}: {len(changed)} written, {len(stale)} removed; "
          f"content digest {digest[:16]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
