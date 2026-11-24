"""The app's notices file and the licence gate (docs/architecture.md 5.1 "A", decision 6.34).

From the curated list android/feature/about/notices/notices-source.json (one entry per shipped
component, each with an SPDX licence) and the licence texts in android/feature/about/notices/licences/,
this writes the module's Android assets, read at run time as assets/notices/...:

  android/feature/about/src/main/assets/notices/notices.json    (format beamline-notices/1, Notices.parse)
  android/feature/about/src/main/assets/notices/NOTICES.txt     (everything in one text)
  android/feature/about/src/main/assets/notices/licences/*.txt  (one text per licence expression)

Commands, from the repository root (standard library only):

  python android/feature/about/scripts/notices.py                 write the assets
  python android/feature/about/scripts/notices.py resolve --key a [--gradle-arg -P...] save what ships: the dependency
        reports of the modules :app will depend on (releaseRuntimeClasspath, runtimeClasspath for
        JVM modules, coreLibraryDesugaring where it exists), resolution only, run through
        tools/round/gradle.sh <key> (or android/gradlew with --gradlew), into --out (default
        data/r3-<key>/notices/)
  python android/feature/about/scripts/notices.py --check --deps data/r3-a/notices
        the licence gate; exit 1 when it fails:
        - every artifact on those classpaths has an entry (or is listed as shipping nothing), and an
          entry naming one version names the resolved one;
        - every native library in the jni/ folders of their AARs (found in the Gradle cache) is
          described under native_libraries, and each component listed there has an entry;
        - every entry's licence is a valid SPDX expression of the pinned SPDX list without OR (an
          entry whose upstream licence is an OR expression records the option Beamline takes, which
          must be one of its options) and is not GPL, AGPL or LGPL in any version or form, except
          a pair listed in ALLOWED below (empty since 2026-11-17: desugar_jdk_libs under GPL-2.0
          with the Classpath Exception, the one exception the owner had accepted, went with Ferrostar);
        - every entry under a licence that asks for the copyright notice (MIT, BSD, ISC, ...,
          NEEDS_HOLDER) names its holders, every MPL-2.0 entry says where its source is, and no
          entry or asset holds an e-mail address (the licence texts' own are left out);
        - every licence has its text, and the assets equal what the list makes now.
  python android/feature/about/scripts/notices.py --check --policy-only
        the last two points only (no dependency reports needed)
  python android/feature/about/scripts/notices.py list --deps DIR
        print the resolved artifacts and native libraries with their POM licences (for curation)
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
MODULE = REPO / "android" / "feature" / "about"
SOURCE_DIR = MODULE / "notices"
SOURCE = SOURCE_DIR / "notices-source.json"
TEXTS = SOURCE_DIR / "licences"
SPDX_IDS = SOURCE_DIR / "spdx-ids.json"
ASSETS = MODULE / "src" / "main" / "assets" / "notices"
ASSET_PREFIX = "notices/"

SOURCE_FORMAT = "beamline-notices-source/1"
OUTPUT_FORMAT = "beamline-notices/1"

# Exceptions to "nothing GPL, AGPL or LGPL ships" (decision 6.34). Empty since 2026-11-17: the one
# the owner had accepted (Round 0 review R5), desugar_jdk_libs under GPL-2.0 with the Classpath
# Exception, served Ferrostar alone and went with it. An exception is a change to this line, made
# only with the owner's consent, not to the data file.
ALLOWED: set[tuple[str, str]] = set()

# The modules :app will depend on (docs/architecture.md 1.1: everything except the demos), with
# the configuration that holds what each ships.
ANDROID_MODULES = [":app", ":ui:theme", ":routing:valhalla", ":map:layers",
                   ":feature:search", ":feature:drive", ":feature:about", ":feature:states"]
JVM_MODULES = [":core:api", ":core:units", ":core:location", ":core:settings", ":routing:core",
               ":routing:banner", ":routing:packs", ":core:packs", ":core:installed", ":core:search",
               ":nav:session", ":map:scene", ":core:download"]
# Core library desugaring is off since Ferrostar went (2026-11-17); :app's configuration is still read,
# so a desugaring library that comes back is checked (an empty report writes no deps-desugar.txt).
DESUGARING_MODULES = [":app"]

KIND_ORDER = ["data", "art", "font", "library", "native"]


class Fail(Exception):
    pass


# ---------------------------------------------------------------------------------------------
# SPDX expressions


@dataclass
class Expr:
    op: str                     # "id", "AND", "OR", "WITH"
    value: str = ""             # the id for "id"; the exception for "WITH"
    args: list["Expr"] = field(default_factory=list)

    def ids(self) -> list[str]:
        if self.op == "id":
            return [self.value]
        if self.op == "WITH":
            return self.args[0].ids()
        return [i for a in self.args for i in a.ids()]

    def exceptions(self) -> list[str]:
        if self.op == "WITH":
            return [self.value] + self.args[0].exceptions()
        return [e for a in self.args for e in a.exceptions()]

    def options(self) -> list["Expr"]:
        """The alternatives of a top-level OR (itself when there is none)."""
        return [o for a in self.args for o in a.options()] if self.op == "OR" else [self]

    def resolutions(self) -> list[frozenset[str]]:
        """Every way to satisfy the expression: the set of terms (an id, or "id WITH exception")
        that then all apply. "MIT AND (MIT OR Apache-2.0)" -> {MIT}, {MIT, Apache-2.0}."""
        if self.op in ("id", "WITH"):
            return [frozenset([self.text()])]
        if self.op == "OR":
            return [r for a in self.args for r in a.resolutions()]
        out = [frozenset()]
        for a in self.args:
            out = [x | y for x in out for y in a.resolutions()]
        return out

    def text(self) -> str:
        if self.op == "id":
            return self.value
        if self.op == "WITH":
            return f"{self.args[0].text()} WITH {self.value}"
        inner = []
        for a in self.args:
            t = a.text()
            inner.append(f"({t})" if a.op in ("AND", "OR") and a.op != self.op else t)
        return f" {self.op} ".join(inner)


TOKEN = re.compile(r"\s*(\(|\)|[A-Za-z0-9.+-]+)")


def parse_spdx(s: str) -> Expr:
    tokens = []
    pos = 0
    s = s.strip()
    while pos < len(s):
        m = TOKEN.match(s, pos)
        if not m:
            raise Fail(f"not an SPDX expression: {s!r}")
        tokens.append(m.group(1))
        pos = m.end()
    tokens = [t for t in tokens if t]
    i = 0

    def peek():
        return tokens[i] if i < len(tokens) else None

    def take():
        nonlocal i
        t = peek()
        i += 1
        return t

    def primary() -> Expr:
        t = take()
        if t is None:
            raise Fail(f"SPDX expression ends early: {s!r}")
        if t == "(":
            e = disjunction()
            if take() != ")":
                raise Fail(f"unbalanced parenthesis: {s!r}")
            return e
        if t in ("AND", "OR", "WITH", ")"):
            raise Fail(f"unexpected {t!r} in {s!r}")
        e = Expr("id", t)
        if peek() == "WITH":
            take()
            exc = take()
            if exc is None or exc in ("AND", "OR", "WITH", "(", ")"):
                raise Fail(f"WITH needs an exception: {s!r}")
            e = Expr("WITH", exc, [e])
        return e

    def conjunction() -> Expr:
        parts = [primary()]
        while peek() == "AND":
            take()
            parts.append(primary())
        return parts[0] if len(parts) == 1 else Expr("AND", args=parts)

    def disjunction() -> Expr:
        parts = [conjunction()]
        while peek() == "OR":
            take()
            parts.append(conjunction())
        return parts[0] if len(parts) == 1 else Expr("OR", args=parts)

    e = disjunction()
    if i != len(tokens):
        raise Fail(f"trailing text in SPDX expression: {s!r}")
    return e


def load_spdx_ids() -> tuple[set[str], set[str], str]:
    data = json.loads(SPDX_IDS.read_text(encoding="utf-8"))
    return set(data["licenses"]), set(data["exceptions"]), data["version"]


def is_copyleft_gpl(licence_id: str) -> bool:
    """GPL, AGPL, LGPL in any version or form (-only, -or-later, +, deprecated ids, LGPLLR)."""
    return "GPL" in licence_id.upper()


# Licences that ask for the copyright notice to travel with every copy, binary copies included
# (MIT: "the above copyright notice ... shall be included"; BSD: "must reproduce the above copyright
# notice"), or that this list treats so (Zlib, BSL-1.0): an entry under any of them must name its
# holders. The licence texts are SPDX templates ("Copyright (c) <year> <copyright holders>"), so
# the entry's own copyright line is the only notice the app shows (review of 2026-11-16).
NEEDS_HOLDER = re.compile(r"^(?!MIT-0$)(MIT(-[A-Za-z0-9.-]+)?|BSD-[A-Za-z0-9.-]+|ISC|Zlib|BSL-1\.0|curl|ICU|"
                          r"Unicode-[A-Za-z0-9.-]+|FTL|X11|NCSA|OFL-1\.[01](-[A-Za-z-]+)?|LicenseRef-NVIDIA-2002)$")


def needs_holder(licence_id: str) -> bool:
    return bool(NEEDS_HOLDER.match(licence_id.rstrip("+")))


# Round privacy rule: no e-mail address in any tracked file, the app's notices included. Upstream
# texts name some (curl's copyright line, FreeType's mailing lists); the licence terms do not
# depend on them.
EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")


# The SPDX texts of MIT, BSD-2-Clause, BSD-3-Clause and ISC begin with a template line
# ("Copyright (c) <year> <copyright holders>"). The app shows each entry's own copyright line above
# the text, so the template line is left out of the app's copy (its "above copyright notice" is then
# the entry's). Lines that only explain how to apply a licence (Apache-2.0's appendix, GPL's end,
# FTL's credit sentence) stay.
PLACEHOLDER = re.compile(r"^Copyright (?:\(c\) )?<year> <(?:owner|copyright holders)>\.?[ \t]*\n(?:[ \t]*\n)?", re.M)


def without_emails(text: str) -> str:
    """[text] with each e-mail address left out: one in <...> goes with its brackets and a comma
    before it ("Daniel Stenberg, <...>." -> "Daniel Stenberg."), a bare one is replaced by a note."""
    text = re.sub(r",?[ \t]*<[^<>\s]+@[^<>\s]+>", "", text)
    return EMAIL.sub("[e-mail address left out]", text)


# ---------------------------------------------------------------------------------------------
# The curated list


@dataclass
class Entry:
    name: str
    version: str | None
    licence: str
    upstream: str | None
    copyright: str | None
    url: str | None
    kind: str
    covers: list[str]
    checked: str | None


def load_source(path: Path = SOURCE) -> dict:
    src = json.loads(path.read_text(encoding="utf-8"))
    if src.get("format") != SOURCE_FORMAT:
        raise Fail(f"{path.name}: format is not {SOURCE_FORMAT}")
    return src


def entries_of(src: dict) -> list[Entry]:
    out = []
    for i, e in enumerate(src["entries"]):
        if not e.get("name") or not e.get("licence"):
            raise Fail(f"entry {i}: name and licence are required")
        kind = e.get("kind", "library")
        if kind not in KIND_ORDER:
            raise Fail(f"entry {e['name']}: kind {kind!r} is not one of {KIND_ORDER}")
        out.append(Entry(e["name"], e.get("version"), e["licence"], e.get("upstream_licence"),
                         e.get("copyright"), e.get("url"), kind, list(e.get("covers", [])), e.get("checked")))
    names = [(x.name, x.version) for x in out]
    dup = {n for n in names if names.count(n) > 1}
    if dup:
        raise Fail(f"entries repeated: {sorted(dup)}")
    return out


def text_file_name(expr: str) -> str:
    """A file name for the text of [expr]. A long expression gets a shortened name with a hash,
    because Windows paths (build folders included) are limited to 260 characters."""
    base = re.sub(r"[^A-Za-z0-9.+-]+", "_", expr.replace("(", "").replace(")", "")).strip("_")
    if len(base) > 48:
        base = base[:36].rstrip("_.-+") + "_" + hashlib.sha256(expr.encode("utf-8")).hexdigest()[:10]
    return base + ".txt"


def licence_text(expr: Expr) -> str:
    """The texts of every licence and exception of [expr], in order, each once."""
    parts = []
    seen = set()
    for ident in expr.ids() + expr.exceptions():
        if ident in seen:
            continue
        seen.add(ident)
        f = TEXTS / f"{ident.rstrip('+')}.txt"
        if not f.exists():
            raise Fail(f"no licence text {f.relative_to(REPO).as_posix()}")
        text = without_emails(f.read_text(encoding="utf-8").replace("\r\n", "\n"))
        parts.append(PLACEHOLDER.sub("", text).strip("\n"))
    if len(parts) == 1:
        return parts[0] + "\n"
    rule = "\n\n" + "-" * 72 + "\n\n"
    return rule.join(parts) + "\n"


def sorted_entries(entries: list[Entry]) -> list[Entry]:
    return sorted(entries, key=lambda e: (KIND_ORDER.index(e.kind), e.name.lower(), e.version or ""))


def make_outputs(src: dict) -> dict[str, str]:
    """Relative path under assets/notices/ -> text."""
    entries = sorted_entries(entries_of(src))
    files: dict[str, str] = {}
    out_entries = []
    for e in entries:
        expr = parse_spdx(e.licence)
        fname = "licences/" + text_file_name(expr.text())
        if fname not in files:
            files[fname] = licence_text(expr)
        out_entries.append({
            "name": e.name, "version": e.version, "licence": e.licence, "copyright": e.copyright,
            "url": e.url, "text": ASSET_PREFIX + fname, "kind": e.kind,
        })
    files["notices.json"] = json.dumps({
        "format": OUTPUT_FORMAT,
        "full_text": ASSET_PREFIX + "NOTICES.txt",
        "entries": out_entries,
    }, ensure_ascii=False, indent=1) + "\n"
    files["NOTICES.txt"] = full_text(entries)
    return files


def full_text(entries: list[Entry]) -> str:
    lines = [
        "Beamline: notices",
        "",
        "Beamline ships the software and data below. Each item names its license (an SPDX",
        "identifier); the text of each license and exception follows the list, once.",
        "",
    ]
    heading = {"data": "Map data", "art": "Artwork", "font": "Fonts", "library": "Software libraries",
               "native": "Native code inside the libraries"}
    kind = None
    for e in entries:
        if e.kind != kind:
            kind = e.kind
            lines += ["", "=" * 72, heading[kind], "=" * 72, ""]
        lines.append(e.name + (f" {e.version}" if e.version else ""))
        lines.append(f"  License: {e.licence}")
        if e.copyright:
            lines.append(f"  {e.copyright}")
        if e.url:
            lines.append(f"  {e.url}")
        lines.append("")
    lines += ["", "=" * 72, "License texts", "=" * 72, ""]
    # Each licence and exception once, by its id (each entry's licence names the ones that apply).
    ids = sorted({i for e in entries for x in [parse_spdx(e.licence)] for i in x.ids() + x.exceptions()}, key=str.lower)
    for ident in ids:
        lines += ["-" * 72, ident, "-" * 72, ""]
        lines.append(licence_text(Expr("id", ident)).rstrip("\n"))
        lines.append("")
    return "\n".join(lines).rstrip("\n") + "\n"


def write_outputs(files: dict[str, str]) -> None:
    lic = ASSETS / "licences"
    lic.mkdir(parents=True, exist_ok=True)
    for old in lic.glob("*.txt"):
        if f"licences/{old.name}" not in files:
            old.unlink()
    for rel, text in files.items():
        p = ASSETS / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(text.encode("utf-8"))


# ---------------------------------------------------------------------------------------------
# Policy


def check_policy(src: dict) -> list[str]:
    problems = []
    licences, exceptions, version = load_spdx_ids()
    try:
        entries = entries_of(src)
    except Fail as e:
        return [str(e)]
    for e in entries:
        try:
            expr = parse_spdx(e.licence)
        except Fail as x:
            problems.append(f"{e.name}: {x}")
            continue
        for ident in expr.ids():
            base = ident[:-1] if ident.endswith("+") else ident
            if not (base in licences or re.fullmatch(r"LicenseRef-[A-Za-z0-9.-]+", base)):
                problems.append(f"{e.name}: {ident!r} is not an SPDX licence id (SPDX list {version})")
        for exc in expr.exceptions():
            if exc not in exceptions:
                problems.append(f"{e.name}: {exc!r} is not an SPDX exception id (SPDX list {version})")
        if expr.op == "OR" or any(a.op == "OR" for a in _walk(expr)):
            problems.append(f"{e.name}: licence {e.licence!r} offers a choice; record the option Beamline "
                            "takes in licence and the offer in upstream_licence")
        if e.upstream:
            try:
                up = parse_spdx(e.upstream)
                taken = expr.resolutions()
                if e.upstream != e.licence and (len(taken) != 1 or taken[0] not in up.resolutions()):
                    problems.append(f"{e.name}: {e.licence!r} is not one of the options of {e.upstream!r}")
            except Fail as x:
                problems.append(f"{e.name}: upstream_licence: {x}")
        if any(is_copyleft_gpl(i) for i in expr.ids()):
            allowed = any((c, e.licence) in ALLOWED for c in e.covers)
            if not allowed:
                problems.append(f"{e.name}: {e.licence} is GPL, AGPL or LGPL, which may not ship "
                                "(no exception is allowed since 2026-11-17)")
        holder_ids = [i for i in expr.ids() if needs_holder(i)]
        if holder_ids and not (e.copyright or "").strip():
            problems.append(f"{e.name}: {', '.join(holder_ids)} asks for the copyright notice; the entry names no "
                            "holder (copy the line from its licence file, or its authors)")
        if "MPL-2.0" in expr.ids() and not e.url:
            problems.append(f"{e.name}: MPL-2.0 asks to tell where the source is; the entry has no url")
        for what, value in (("name", e.name), ("copyright", e.copyright), ("url", e.url)):
            if value and EMAIL.search(value):
                problems.append(f"{e.name}: its {what} holds an e-mail address (leave it out; the names stay)")
        try:
            licence_text(expr)
        except Fail as x:
            problems.append(f"{e.name}: {x}")
    natives = src.get("native_libraries", {})
    names = {e.name for e in entries}
    for lib, desc in natives.items():
        for comp in desc.get("components", []):
            if comp not in names:
                problems.append(f"native {lib}: component {comp!r} has no entry")
    return problems


def _walk(e: Expr):
    yield e
    for a in e.args:
        yield from _walk(a)


def check_assets(src: dict) -> list[str]:
    try:
        want = make_outputs(src)
    except Fail as e:
        return [str(e)]
    problems = []
    for rel, text in want.items():
        if EMAIL.search(text):
            problems.append(f"e-mail   assets/notices/{rel} would hold an e-mail address ({EMAIL.search(text).group(0)[:3]}...)")
        p = ASSETS / rel
        if not p.exists():
            problems.append(f"missing  assets/notices/{rel} (run notices.py)")
        elif p.read_bytes() != text.encode("utf-8"):
            problems.append(f"differs  assets/notices/{rel} (run notices.py)")
    if (ASSETS / "licences").exists():
        for p in (ASSETS / "licences").glob("*"):
            if f"licences/{p.name}" not in want:
                problems.append(f"extra    assets/notices/licences/{p.name} (run notices.py)")
    return problems


# ---------------------------------------------------------------------------------------------
# What ships: dependency reports and native libraries

DEP_LINE = re.compile(
    r"^[|\s]*[+\\]--- (?P<g>[^\s:]+):(?P<n>[^\s:]+)(?::(?P<v>\{[^}]*\}|[^\s]+))?(?: -> (?P<v2>[^\s]+))?(?: \((?P<flag>[*cn])\))?\s*$")


def parse_report(text: str) -> dict[tuple[str, str], set[str]]:
    """{(group, name): {resolved versions}} of every external module in a `dependencies` report."""
    out: dict[tuple[str, str], set[str]] = {}
    for line in text.splitlines():
        if "--- " not in line or "--- project " in line:
            continue
        m = DEP_LINE.match(line)
        if not m:
            raise Fail(f"cannot read the dependency line {line.strip()!r}")
        if m.group("flag") in ("c", "n"):
            continue   # a constraint, or not resolved: not on the classpath through this line
        v = m.group("v2") or m.group("v")
        if not v:
            raise Fail(f"no version in {line.strip()!r}")
        v = re.sub(r"^\{(strictly|require|prefer) |\}$|[{}]", "", v).strip()
        out.setdefault((m.group("g"), m.group("n")), set()).add(v)
    return out


def report_sections(text: str) -> list[tuple[str, str]]:
    """(project, configuration) of each section of a report, to prove what was resolved."""
    found = []
    project = None
    for line in text.splitlines():
        m = re.match(r"^Project '(:[^']*)'$", line)
        if m:
            project = m.group(1)
        m = re.match(r"^(\w+) - ", line)
        if m and project:
            found.append((project, m.group(1)))
    return found


def gradle_home() -> Path:
    return Path(os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")


def module_files(g: str, n: str, v: str) -> list[Path] | None:
    """Every file Gradle cached for g:n:v (POM, module metadata, jar, aar), or None when none."""
    base = gradle_home() / "caches" / "modules-2" / "files-2.1" / g / n / v
    return sorted(p for p in base.glob("*/*") if p.is_file()) if base.exists() else None


def is_android_library(files: list[Path]) -> bool:
    """True when the metadata says the module itself holds an AAR (a Kotlin multiplatform root
    module only points at its -android module with available-at; that one is checked itself)."""
    modules = [p for p in files if p.suffix == ".module"]
    for p in modules:
        try:
            meta = json.loads(p.read_text(encoding="utf-8", errors="replace"))
        except ValueError:
            return True   # unreadable metadata: do not assume it is native-free
        for variant in meta.get("variants", []):
            if any(f.get("name", "").endswith(".aar") for f in variant.get("files", [])):
                return True
    if modules:
        return False
    return any(p.suffix == ".pom" and re.search(r"<packaging>\s*aar\s*</packaging>", p.read_text(encoding="utf-8", errors="replace"))
               for p in files)


def native_libraries(artifacts: dict[tuple[str, str], set[str]]) -> tuple[dict[str, dict], list[str]]:
    """{lib file name: {"from": [g:n:v], "abis": [...]}}, and the modules whose AAR is not in the
    Gradle cache (a resolution-only run fetches metadata, not always the files)."""
    libs: dict[str, dict] = {}
    missing = []
    for (g, n), versions in sorted(artifacts.items()):
        for v in sorted(versions):
            files = module_files(g, n, v)
            if files is None:
                missing.append(f"{g}:{n}:{v}")
                continue
            aars = [p for p in files if p.suffix == ".aar"]
            if not aars:
                if is_android_library(files):
                    missing.append(f"{g}:{n}:{v}")
                continue   # a jar, or a POM-only module (a BOM): nothing native for the APK
            for aar in aars:
                with zipfile.ZipFile(aar) as z:
                    for name in z.namelist():
                        m = re.fullmatch(r"jni/([^/]+)/([^/]+\.so)", name)
                        if m:
                            d = libs.setdefault(m.group(2), {"from": set(), "abis": set()})
                            d["from"].add(f"{g}:{n}:{v}")
                            d["abis"].add(m.group(1))
    return {k: {"from": sorted(v["from"]), "abis": sorted(v["abis"])} for k, v in sorted(libs.items())}, missing


def load_deps(deps: Path) -> dict[tuple[str, str], set[str]]:
    reports = sorted(deps.glob("deps-*.txt"))
    if not reports:
        raise Fail(f"no deps-*.txt in {deps}; run notices.py resolve first")
    union: dict[tuple[str, str], set[str]] = {}
    for r in reports:
        text = r.read_text(encoding="utf-8")
        if not report_sections(text):
            raise Fail(f"{r.name} holds no dependency report")
        for k, v in parse_report(text).items():
            union.setdefault(k, set()).update(v)
    return union


def matches(pattern: str, g: str, n: str) -> bool:
    pg, _, pn = pattern.partition(":")
    if pg != g:
        return False
    if pn.endswith("*"):
        return n.startswith(pn[:-1])
    return pn == n


def check_coverage(src: dict, deps: Path) -> tuple[list[str], list[str]]:
    """Problems, and notes (what was checked)."""
    problems: list[str] = []
    entries = entries_of(src)
    union = load_deps(deps)
    not_shipped = [x["covers"] for x in src.get("not_shipped", [])]
    covered_by: dict[str, list[Entry]] = {}
    for (g, n), versions in sorted(union.items()):
        coord = f"{g}:{n}"
        if any(matches(p, g, n) for p in not_shipped):
            continue
        hits = [e for e in entries if any(matches(p, g, n) for p in e.covers)]
        if not hits:
            problems.append(f"no entry for {coord}:{','.join(sorted(versions))}")
            continue
        covered_by[coord] = hits
        for e in hits:
            exact = coord in e.covers
            if e.version and exact and e.version not in versions:
                problems.append(f"{e.name}: entry says {e.version}, {coord} resolves to {','.join(sorted(versions))}")
    libs, missing = native_libraries(union)
    for m in missing:
        problems.append(f"{m}: not in the Gradle cache, its native libraries cannot be listed (build once, then check again)")
    described = src.get("native_libraries", {})
    for lib, d in libs.items():
        if lib not in described:
            problems.append(f"native library {lib} (from {', '.join(d['from'])}) is not described under native_libraries")
            continue
        comps = described[lib].get("components", [])
        if not comps:
            problems.append(f"native library {lib}: no components listed")
        if described[lib].get("complete") is not True:
            problems.append(f"native library {lib}: its component list is not complete yet: "
                            + described[lib].get("todo", "say what is in it and set complete to true"))
    for lib in described:
        if lib not in libs:
            problems.append(f"native_libraries lists {lib}, which nothing on the classpath ships (remove it)")
    for e in entries:
        for p in e.covers:
            if not any(matches(p, g, n) for (g, n) in union):
                problems.append(f"{e.name}: covers {p}, which is not on the classpath (remove it, or the entry)")
    notes = [f"{len(union)} artifacts on the union of classpaths, {len(libs)} native libraries "
             f"({', '.join(libs)})"]
    return problems, notes


def resolve(key: str | None, gradlew: bool, out: Path, extra: list[str] | None = None) -> int:
    out.mkdir(parents=True, exist_ok=True)
    if gradlew:
        base = [str(REPO / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew"))]
        cwd = REPO / "android"
    else:
        base = ["sh", "tools/round/gradle.sh", key]
        cwd = REPO
    common = ["-q", "--console=plain", "--no-parallel", "--no-configuration-cache"] + list(extra or [])
    runs = {
        "deps-runtime.txt": [t for m in ANDROID_MODULES for t in (f"{m}:dependencies", "--configuration", "releaseRuntimeClasspath")]
        + [t for m in JVM_MODULES for t in (f"{m}:dependencies", "--configuration", "runtimeClasspath")],
        "deps-desugar.txt": [t for m in DESUGARING_MODULES for t in (f"{m}:dependencies", "--configuration", "coreLibraryDesugaring")],
    }
    for name, tasks in runs.items():
        r = subprocess.run(base + common + tasks, cwd=cwd, capture_output=True, text=True, encoding="utf-8")
        if r.returncode != 0:
            print(r.stderr[-3000:], file=sys.stderr)
            print(f"notices.py resolve: Gradle exit {r.returncode} for {name}"
                  + (" (75: no free build slot yet; run it again later)" if r.returncode == 75 else ""), file=sys.stderr)
            return r.returncode
        if name == "deps-desugar.txt" and not parse_report(r.stdout):
            (out / name).unlink(missing_ok=True)
            print(f"{name}: coreLibraryDesugaring is empty (no desugaring), not written")
            continue
        (out / name).write_text(r.stdout, encoding="utf-8")
        sections = report_sections(r.stdout)
        print(f"wrote {name}: {len(sections)} sections, {len(parse_report(r.stdout))} artifacts")
    union = load_deps(out)
    libs, missing = native_libraries(union)
    (out / "natives.json").write_text(json.dumps({"libraries": libs, "not_in_cache": missing}, indent=1) + "\n", encoding="utf-8")
    print(f"wrote natives.json: {len(libs)} native libraries" + (f", {len(missing)} artifacts not in the Gradle cache" if missing else ""))
    return 0


def pom_licences(g: str, n: str, v: str) -> list[str]:
    base = gradle_home() / "caches" / "modules-2" / "files-2.1" / g / n / v
    poms = sorted(base.glob(f"*/{n}-{v}.pom")) if base.exists() else []
    if not poms:
        return ["(no POM in cache)"]
    text = poms[0].read_text(encoding="utf-8", errors="replace")
    names = re.findall(r"<license>\s*<name>([^<]*)</name>", text)
    return names or ["(POM names no licence; see its parent)"]


def list_union(deps: Path) -> int:
    union = load_deps(deps)
    for (g, n), versions in sorted(union.items()):
        for v in sorted(versions):
            print(f"{g}:{n}:{v}\t{'; '.join(pom_licences(g, n, v))}")
    libs, missing = native_libraries(union)
    for lib, d in libs.items():
        print(f"native {lib}\t{', '.join(d['from'])}\t{' '.join(d['abis'])}")
    for m in missing:
        print(f"not in cache {m}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="The app's notices file and the licence gate.")
    ap.add_argument("command", nargs="?", default="build", choices=["build", "resolve", "list"])
    ap.add_argument("--check", action="store_true", help="the licence gate (needs --deps or --policy-only)")
    ap.add_argument("--deps", type=Path, help="folder with deps-*.txt from `notices.py resolve`")
    ap.add_argument("--policy-only", action="store_true", help="with --check: skip the classpath coverage")
    ap.add_argument("--key", help="resolve: the round key for tools/round/gradle.sh")
    ap.add_argument("--gradlew", action="store_true", help="resolve: run android/gradlew directly (outside a round)")
    ap.add_argument("--out", type=Path, help="resolve: where to write (default data/r3-<key>/notices)")
    ap.add_argument("--gradle-arg", action="append", default=[], help="resolve: one more Gradle argument, e.g. "
                    "-Pbeamline.mapRenderer=opengl to see what the other renderer would ship")
    a = ap.parse_args(argv)
    try:
        if a.command == "resolve":
            if not a.key and not a.gradlew:
                ap.error("resolve needs --key <round key> or --gradlew")
            out = a.out or (REPO / "data" / f"r3-{a.key or 'local'}" / "notices")
            return resolve(a.key, a.gradlew, out, a.gradle_arg)
        if a.command == "list":
            if not a.deps:
                ap.error("list needs --deps")
            return list_union(a.deps)
        src = load_source()
        if a.check:
            if not a.deps and not a.policy_only:
                ap.error("--check needs --deps <folder from notices.py resolve>, or --policy-only")
            problems = check_policy(src) + check_assets(src)
            notes = []
            if a.deps:
                p, notes = check_coverage(src, a.deps)
                problems += p
            for n in notes:
                print(n)
            for p in problems:
                print(f"FAIL  {p}")
            n_entries = len(src["entries"])
            if problems:
                print(f"licence gate: {len(problems)} problems ({n_entries} entries)")
                return 1
            print(f"licence gate: passed ({n_entries} entries" + (", coverage checked)" if a.deps else ", policy only)"))
            return 0
        files = make_outputs(src)
        write_outputs(files)
        print(f"wrote assets/notices/: notices.json ({len(src['entries'])} entries), NOTICES.txt, "
              f"{sum(1 for k in files if k.startswith('licences/'))} licence texts")
        return 0
    except Fail as e:
        print(f"notices.py: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
