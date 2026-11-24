"""Check that the contract code in docs/architecture.md and its companion contracts and the files in
the tree are the same, and that the frozen files of the round have not changed.

    python tools/check_contracts.py            # compare; exit 1 on any difference
    python tools/check_contracts.py --exact    # the same, but a filled TODO("track X") body counts as a difference
    python tools/check_contracts.py --write    # write every FILE block into the tree (orchestrator only)
    python tools/check_contracts.py --freeze   # record the frozen files' hashes (orchestrator only)

Documents it reads: docs/architecture.md first, then every companion contract docs/architecture-*.md
that exists (docs/architecture-downloads.md since Round G0, docs/architecture-saved-places.md since
Round SP0), in name order. A whole-file block (FILE or data) lives in exactly one document: a path
quoted as a whole file twice, in one document or in two, is reported. A companion that is deleted
leaves its contract files unquoted, which is reported too.

Blocks it knows (fenced code blocks of those documents):
  - a block whose first line is `// FILE: <path under android/>`: the file must equal the block,
    that first line included (the contract files carry it as their first line). A track replaces
    the stub bodies `TODO("track X")` of its own contract files and nothing else, so by default each
    stub matches any text and everything around the stubs must be the same;
  - a block whose first line is `// EXCERPT: <path under android/>`: the other lines of the block
    must appear in the file as one unbroken run, in the same order; a stub `TODO("track X")` in it
    matches any text, as in a FILE block, and --exact counts a filled one as a difference (since
    the review of Rounds G0 and SP0: three excerpts of docs/architecture-saved-places.md show
    stubs that tracks M and S fill);
  - a block right after a line `<!-- FILE: <path from the repository root> -->`: the file must equal
    the block (used for the JSON data files).
It also lists Kotlin files under android/ whose first line is `// FILE:` but that no document
quotes, so a contract file cannot drift out of the documents unnoticed.

Frozen files (docs/architecture.md 5.2): every file under FROZEN below (whole modules included, build
outputs left out) must have the SHA-256 recorded in tools/frozen-files.sha256. A file added to a
frozen module, removed from it or changed is reported, quoted in the document or not. Only the
orchestrator runs --freeze, after applying an approved change.

Line endings are compared and hashed as LF. Standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DOC = REPO / "docs" / "architecture.md"
COMPANIONS = "architecture-*.md"  # docs/architecture-downloads.md, docs/architecture-saved-places.md
ANDROID = REPO / "android"
FENCE = re.compile(r"^```")
MARK = re.compile(r"^<!--\s*FILE:\s*(\S+)\s*-->\s*$")
STUB = re.compile(r'TODO\("track [A-Z]+"\)')
FROZEN_LIST = REPO / "tools" / "frozen-files.sha256"
# docs/architecture.md 5.2: the shared Gradle files, the frozen modules, the unit table and this script.
FROZEN = [
    "android/settings.gradle.kts",
    "android/gradle/libs.versions.toml",
    "android/build.gradle.kts",
    "android/gradle.properties",
    "android/core/api",
    "android/core/units",
    "android/core/settings",
    "android/core/location",
    "android/ui/theme",
    "packbuilder/routing/units.json",
    "tools/check_contracts.py",
]
SKIP_DIRS = {"build", ".gradle", ".kotlin", ".idea", ".cxx", "__pycache__"}


def blocks(text: str):
    """Yields (line number, marker path or None, block lines) for every fenced block."""
    lines = text.split("\n")
    i = 0
    while i < len(lines):
        if FENCE.match(lines[i]):
            start = i
            marker = MARK.match(lines[i - 1]) if i > 0 else None
            j = i + 1
            while j < len(lines) and not FENCE.match(lines[j]):
                j += 1
            yield start + 1, (marker.group(1) if marker else None), lines[i + 1:j]
            i = j + 1
        else:
            i += 1


def read(path: Path) -> str | None:
    return path.read_bytes().decode("utf-8").replace("\r\n", "\n") if path.is_file() else None


def documents() -> list[Path]:
    """The main contract, then its companions in name order."""
    return [DOC] + sorted(DOC.parent.glob(COMPANIONS), key=lambda p: p.name)


def same_but_stubs(want: str, have: str) -> bool:
    """True when [have] equals [want] with each TODO("track X") stub replaced by any text."""
    parts = STUB.split(want)
    if len(parts) == 1:
        return False
    return re.fullmatch(r"[\s\S]*?".join(re.escape(p) for p in parts), have) is not None


def excerpt_found(want: str, have: str, exact: bool) -> tuple[bool, bool]:
    """(found, found only with filled stubs): [want]'s lines as one unbroken run of [have]'s lines;
    unless [exact], each TODO("track X") stub of [want] matches any text there."""
    if ("\n" + want + "\n") in ("\n" + have + "\n"):
        return True, False
    parts = STUB.split("\n" + want + "\n")
    if exact or len(parts) == 1:
        return False, False
    return re.search(r"[\s\S]*?".join(re.escape(p) for p in parts), "\n" + have + "\n") is not None, True


def frozen_files() -> list[Path]:
    out: list[Path] = []
    for rel in FROZEN:
        p = REPO / rel
        if p.is_file():
            out.append(p)
        elif p.is_dir():
            for f in p.rglob("*"):
                if f.is_file() and not SKIP_DIRS.intersection(f.relative_to(p).parts) and f.suffix != ".iml":
                    out.append(f)
    return sorted(out, key=lambda f: f.relative_to(REPO).as_posix())


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def check_frozen() -> int:
    if not FROZEN_LIST.is_file():
        print(f"MISSING  {FROZEN_LIST.relative_to(REPO).as_posix()}  (run --freeze once, orchestrator only)")
        return 1
    recorded: dict[str, str] = {}
    for line in FROZEN_LIST.read_text(encoding="utf-8").splitlines():
        if line.strip():
            h, rel = line.split("  ", 1)
            recorded[rel] = h
    bad = 0
    now = {f.relative_to(REPO).as_posix(): f for f in frozen_files()}
    for rel in sorted(set(recorded) | set(now)):
        if rel not in now:
            bad += 1
            print(f"REMOVED  {rel}  (a frozen file is gone)")
        elif rel not in recorded:
            bad += 1
            print(f"ADDED    {rel}  (a new file in a frozen module)")
        elif digest(now[rel]) != recorded[rel]:
            bad += 1
            print(f"CHANGED  {rel}  (a frozen file was edited)")
    print(f"{len(now)} frozen files checked against {FROZEN_LIST.relative_to(REPO).as_posix()}; "
          f"{'all unchanged' if bad == 0 else f'{bad} problem(s)'}")
    return bad


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--write", action="store_true", help="write FILE blocks into the tree instead of comparing")
    mode.add_argument("--freeze", action="store_true", help="record the hashes of the frozen files")
    ap.add_argument("--exact", action="store_true", help="a filled TODO stub body counts as a difference")
    a = ap.parse_args()
    if a.freeze:
        files = frozen_files()
        FROZEN_LIST.write_bytes("".join(f"{digest(f)}  {f.relative_to(REPO).as_posix()}\n" for f in files).encode("utf-8"))
        print(f"recorded {len(files)} frozen files in {FROZEN_LIST.relative_to(REPO).as_posix()}")
        return 0
    if not DOC.is_file():
        sys.exit(f"{DOC} not found")
    bad = 0
    filled = 0
    filled_excerpts = 0
    quoted: set[Path] = set()
    whole: dict[Path, str] = {}  # file -> where its one whole-file block is
    counts = {"file": 0, "excerpt": 0, "data": 0}
    docs = documents()
    for doc_path, line_no, marker, body in ((d, *b) for d in docs for b in blocks(read(d))):
        first = body[0] if body else ""
        if first.startswith("// FILE: "):
            path, kind = ANDROID / first[len("// FILE: "):].strip(), "file"
            want = "\n".join(body) + "\n"
        elif first.startswith("// EXCERPT: "):
            path, kind = ANDROID / first[len("// EXCERPT: "):].strip(), "excerpt"
            want = "\n".join(body[1:])
        elif marker:
            path, kind = REPO / marker, "data"
            want = "\n".join(body) + "\n"
        else:
            continue
        where = f"{doc_path.relative_to(REPO).as_posix()} line {line_no}"
        counts[kind] += 1
        quoted.add(path.resolve())
        rel = path.relative_to(REPO).as_posix()
        if kind != "excerpt":
            if path.resolve() in whole:
                bad += 1
                print(f"TWICE    {rel}  ({where}; already quoted as a whole file at {whole[path.resolve()]})")
                continue
            whole[path.resolve()] = where
        have = read(path)
        stubs_filled = kind == "file" and have is not None and have != want and same_but_stubs(want, have)
        if a.write and kind != "excerpt":
            if stubs_filled:
                print(f"skipped  {rel}  (its TODO bodies are filled; merge a contract change by hand)")
            elif have != want:
                if have is not None and len(STUB.findall(have)) < len(STUB.findall(want)):
                    print(f"skipped  {rel}  (a track has filled stubs in it; merge a contract change by hand)")
                    continue
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(want.encode("utf-8"))
                print(f"written  {rel}")
            continue
        if have is None:
            ok, why = False, "missing"
        elif kind == "excerpt":
            ok, stubs_filled = excerpt_found(want, have, a.exact)
            why = "excerpt not found as one run"
            filled_excerpts += ok and stubs_filled
        elif have == want:
            ok, why = True, ""
        elif stubs_filled and not a.exact:
            ok, why = True, ""
            filled += 1
        else:
            ok, why = False, "differs"
        if not ok:
            bad += 1
            print(f"{why.upper():8} {rel}  ({where})")
            if kind == "file" and have is not None and not a.exact and len(STUB.findall(have)) < len(STUB.findall(want)):
                # stubs filled: name the first contract line (outside the stubs) the file no longer has
                have_lines = have.split("\n")
                for n, x in enumerate(want.split("\n"), 1):
                    head = STUB.split(x)[0]  # a stub line keeps its text before the stub
                    if not any(y == x or (head != x and y.startswith(head)) for y in have_lines):
                        print(f"         contract line {n} not found in the file:\n           doc:  {x!r}")
                        break
            elif kind != "excerpt" and have is not None:
                for n, (x, y) in enumerate(zip(want.split("\n"), have.split("\n")), 1):
                    if x != y:
                        print(f"         first difference at line {n}:\n           doc:  {x!r}\n           tree: {y!r}")
                        break
        else:
            print(f"{'filled' if stubs_filled else 'same':8} {rel}")
    if a.write:
        return 1 if bad else 0
    for kt in sorted(ANDROID.rglob("*.kt")):
        if "build" in kt.relative_to(ANDROID).parts:
            continue
        head = kt.read_bytes()[:200].decode("utf-8", "replace")
        if head.startswith("// FILE: ") and kt.resolve() not in quoted:
            bad += 1
            print(f"UNQUOTED {kt.relative_to(REPO).as_posix()}  (a contract file no document shows)")
    print(f"{counts['file']} contract files ({filled} with filled stubs), {counts['excerpt']} excerpts"
          f"{f' ({filled_excerpts} with filled stubs)' if filled_excerpts else ''}, "
          f"{counts['data']} data files checked in {len(docs)} documents "
          f"({', '.join(d.relative_to(REPO).as_posix() for d in docs)}); "
          f"{'all the same' if bad == 0 else f'{bad} problem(s)'}")
    bad += check_frozen()
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
