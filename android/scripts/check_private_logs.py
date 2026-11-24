"""Prove that the app never logs a saved place (rules PV2, PV3 and PV4 of docs/architecture-saved-places.md).

    python android/scripts/check_private_logs.py scan [--module core/search ...] [--root DIR]
    python android/scripts/check_private_logs.py logcat FILE [FILE ...]
    python android/scripts/check_private_logs.py manifest MERGED_MANIFEST [APK | --rules-xml FILE] [--aapt2 PATH]
    python android/scripts/check_private_logs.py selftest

scan      PV2 items 2 and 3 over the main Kotlin sources (src/main/**/*.kt; build folders, demos and
          test sources excluded) of the shipped modules: by default every module :app depends on
          after integration (docs/architecture.md 1.1, "everything above except the demos") plus
          any further module :app's build file names; --module limits it to the named module folders
          (paths under android/, the way each track runs it on its own modules). Exit 1 on a breach;
          every marked line (// log-ok:, // private-ok:) is printed for the reviewer.
          - :core:search, :feature:search and :map:scene, and :app's online/ package (the OSM outbox,
            batch O1): no logging call at all (android.util.Log, Log., println, print(, System.out,
            System.err, printStackTrace, Timber).
          - The code that holds saved places (:core:search's search/saved/ package, :feature:search,
            :map:scene, :map:layers, and :app's online/ package with the outbox): no require, check, error, requireNotNull, checkNotNull or throw
            whose message has a string template ($).
          - Everywhere else a log call (Log.v/d/i/w/e/wtf, println, print(, System.out, System.err)
            is a tag and one plain string literal with no $, or is marked on its line(s)
            "// log-ok: <what the values are>"; no exception is passed to it (no third argument)
            and none of .message, .localizedMessage, stackTraceToString(), printStackTrace() appears
            in it, marked or not; printStackTrace() is refused anywhere. A log call holding one of
            the words lat, lon, latitude, longitude, point, GeoPoint, Place, place, stops, query,
            saved needs "// private-ok: <reason>" and must sit inside the location-log guard of PV7
            item 6: an enclosing `if` (or the line itself) whose condition names locationLog.
logcat    Fails when a canary of the emulator journey (10.3) appears, case-insensitively, in a
          captured log: the words quokka, maritime, millcreek, art museum, airport, and each canary
          point's latitude and longitude with 3, 4 and 5 decimals (rounded and cut), a number counting
          only when no digit or dot comes before it ("142.136 ms" is not a hit, "at 42.1365" is).
          Prints the file, line number and canary of each hit, never the line.
manifest  PV4: the merged manifest's <application> has android:allowBackup="false" and
          android:dataExtractionRules="@xml/data_extraction_rules"; the rules in the APK (read with
          aapt2 dump xmltree) or in --rules-xml are exactly those of PV4: cloud backup and device
          transfer each exclude the domains root, file, database, sharedpref and external (path
          "."). (Until integration device transfer excluded only saved-places.json, recents.json and
          trip.json; the lead decided on 2026-11-16 that nothing of the app leaves the phone.)
selftest  Shows every check failing once on a scratch tree and passing on clean input
          (docs/architecture-saved-places.md 10.2).

Standard library only. Prints no source text beyond the flagged line's file and number and the
marked lines' own text (code the reviewer must read).
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ANDROID = "android"

# docs/architecture.md 1.1: what :app depends on once integration is done ("everything above except the demos")
SHIPPED = [
    "core/api", "core/units", "core/location", "core/settings", "ui/theme", "routing/core", "routing/banner",
    "routing/packs", "routing/valhalla", "core/packs", "core/installed", "core/search", "nav/session",
    "map/scene", "map/layers", "feature/search", "feature/drive", "feature/about", "core/download", "feature/states", "app",
]
NO_LOG_MODULES = {"core/search", "feature/search", "map/scene"}
# Batch O1: the online package holds the outbox (a note's text and point, PV1-PV8): it never logs.
ONLINE = "app/src/main/kotlin/io/github/spysyweeb/beamline/app/online"
NO_LOG_PATHS = [ONLINE]
SAVED_CODE = ["core/search/src/main/kotlin/io/github/spysyweeb/beamline/search/saved", "feature/search", "map/scene", "map/layers", ONLINE]

LOG_CALL = re.compile(r"(?<![\w.])(Log\.(?:v|d|i|w|e|wtf)|println|print|System\.out\.\w+|System\.err\.\w+)\s*\(")
ANY_LOG = re.compile(r"android\.util\.Log\b|(?<![\w.])Log\.\w+|(?<![\w.])println\s*\(|(?<![\w.])print\s*\(|System\.out\b|"
                     r"System\.err\b|printStackTrace\s*\(|\bTimber\b")
EXC_TEXT = re.compile(r"\.message\b|\.localizedMessage\b|stackTraceToString\s*\(|printStackTrace\s*\(")
PRIVATE_WORD = re.compile(r"\b(lat|lon|latitude|longitude|point|GeoPoint|Place|place|stops|query|saved)\b")
MSG_CALL = re.compile(r"(?<![\w.])(require|check|error|requireNotNull|checkNotNull)\s*\(|(?<![\w.])throw\s")
LOG_OK = "// log-ok:"
PRIVATE_OK = "// private-ok:"
GUARD = re.compile(r"\bif\s*\(.*location_?[Ll]og")


# ------------------------------------------------------------------ Kotlin text without comments
def code_only(text: str) -> str:
    """[text] with every comment replaced by spaces (newlines kept), strings kept as they are."""
    out = []
    i, n = 0, len(text)
    depth = 0
    while i < n:
        c = text[i]
        if depth:
            if text.startswith("/*", i):
                depth += 1
                out.append("  ")
                i += 2
            elif text.startswith("*/", i):
                depth -= 1
                out.append("  ")
                i += 2
            else:
                out.append("\n" if c == "\n" else " ")
                i += 1
        elif text.startswith("/*", i):
            depth = 1
            out.append("  ")
            i += 2
        elif text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(text[i:j])
            i = j
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(text[i:j])
            i = j
        elif c == "'" and i + 2 < n:
            m = re.match(r"'(\\.|[^'\\])'", text[i:i + 4]) or re.match(r"'\\u[0-9a-fA-F]{4}'", text[i:i + 8])
            if m:
                out.append(m.group(0))
                i += len(m.group(0))
            else:
                out.append(c)
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def call_span(code: str, start: int) -> int:
    """End index (exclusive) of the call whose '(' is the first one at or after [start]; a trailing
    { ... } lambda (require(x) { "..." }) is included."""
    i = code.find("(", start)
    if i < 0:
        return len(code)
    j = match_close(code, i, "(", ")")
    k = j
    while k < len(code) and code[k] in " \t":
        k += 1
    if k < len(code) and code[k] == "{":
        j = match_close(code, k, "{", "}")
    return j


def match_close(code: str, i: int, op: str, cl: str) -> int:
    depth = 0
    n = len(code)
    while i < n:
        c = code[i]
        if code.startswith('"""', i):
            e = code.find('"""', i + 3)
            i = n if e < 0 else e + 3
            continue
        if c == '"':
            i += 1
            while i < n and code[i] != '"':
                i += 2 if code[i] == "\\" else 1
            i += 1
            continue
        if c == op:
            depth += 1
        elif c == cl:
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    return n


STRING = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\\n])*"')


def strings_in(s: str) -> list[str]:
    return STRING.findall(s)


def blank_strings(code: str) -> str:
    """[code] with the inside of every string literal replaced by spaces (newlines kept, same
    length), so words in strings are not taken for calls."""
    def blank(m):
        t = m.group(0)
        q = 3 if t.startswith('"""') else 1
        return t[:q] + re.sub(r"[^\n]", " ", t[q:-q]) + t[-q:]
    return STRING.sub(blank, code)


def has_template(s: str) -> bool:
    return any(re.search(r"(?<!\\)\$[\w{]", lit) for lit in strings_in(s))


def split_args(inner: str) -> list[str]:
    """Top-level comma-separated arguments of a call's inside."""
    args, depth, cur, i = [], 0, [], 0
    while i < len(inner):
        c = inner[i]
        m = STRING.match(inner, i)
        if m:
            cur.append(m.group(0))
            i = m.end()
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
        i += 1
    if "".join(cur).strip():
        args.append("".join(cur).strip())
    return args


def plain_literal(a: str) -> bool:
    return bool(re.fullmatch(r'"(?:\\.|[^"\\$])*"', a.strip()))


# ------------------------------------------------------------------ scan
class Finding:
    def __init__(self, path: str, line: int, why: str):
        self.path, self.line, self.why = path, line, why

    def __str__(self):
        return f"{self.path}:{self.line}: {self.why}"


def module_dirs(root: Path, modules: list[str] | None) -> list[str]:
    if modules:
        return [m.strip("/").removeprefix("android/") for m in modules]
    mods = list(SHIPPED)
    # anything else :app's build file names (transitively), demos never
    seen, todo = set(mods), ["app"]
    while todo:
        m = todo.pop()
        bf = root / ANDROID / m / "build.gradle.kts"
        if not bf.is_file():
            continue
        for dep in re.findall(r'project\("(:[\w:-]+)"\)', bf.read_text(encoding="utf-8")):
            d = dep.strip(":").replace(":", "/")
            if "demo" in d or d in seen:
                continue
            seen.add(d)
            mods.append(d)
            todo.append(d)
    return mods


def kotlin_files(root: Path, module: str):
    base = root / ANDROID / module / "src" / "main"
    if not base.is_dir():
        return
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in ("build", ".gradle", ".kotlin")]
        for f in filenames:
            if f.endswith(".kt"):
                yield Path(dirpath) / f


def line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def in_guard(lines: list[str], idx: int) -> bool:
    """The line itself names locationLog in an if, or an enclosing block opened by such an if."""
    if GUARD.search(lines[idx]):
        return True
    depth = 0
    for k in range(idx - 1, max(-1, idx - 60), -1):
        ln = lines[k]
        depth += ln.count("}") - ln.count("{")
        if depth < 0:
            if GUARD.search(ln):
                return True
            depth = 0   # an enclosing block that is not the guard: keep looking outwards
    return False


def scan_file(root: Path, module: str, path: Path, findings: list[Finding], marked: list[str]):
    raw = path.read_text(encoding="utf-8", errors="replace")
    code = code_only(raw)
    bare = blank_strings(code)   # for finding calls; [code] (same positions) for their texts
    raw_lines = raw.split("\n")
    code_lines = code.split("\n")
    rel = path.relative_to(root).as_posix()
    norm = rel.removeprefix(ANDROID + "/")
    no_log = module in NO_LOG_MODULES or any(norm.startswith(p + "/") for p in NO_LOG_PATHS)
    saved_code = any(norm.startswith(s + "/") or norm.startswith(s) for s in SAVED_CODE)

    if no_log:
        for m in ANY_LOG.finditer(bare):
            findings.append(Finding(rel, line_of(code, m.start()), f"logging in a module that must not log ({module}): {m.group(0).strip()}"))
    if saved_code:
        for m in MSG_CALL.finditer(bare):
            end = call_span(code, m.end() - 1) if "(" in m.group(0) else statement_end(code, m.end())
            if has_template(code[m.start():end]):
                findings.append(Finding(rel, line_of(code, m.start()), "an exception message built from a value in code that holds saved places"))
    for m in re.finditer(r"printStackTrace\s*\(", bare):
        if not no_log:
            findings.append(Finding(rel, line_of(code, m.start()), "printStackTrace()"))
    if no_log:
        return
    for m in LOG_CALL.finditer(bare):
        open_paren = code.find("(", m.start())
        end = match_close(code, open_paren, "(", ")")
        call = code[m.start():end]
        first, last = line_of(code, m.start()), line_of(code, end - 1)
        span_raw = "\n".join(raw_lines[first - 1:last])
        log_ok = LOG_OK in span_raw
        private_ok = PRIVATE_OK in span_raw
        args = split_args(code[open_paren + 1:end - 1])
        name = m.group(1)
        where = f"{rel}:{first}"
        if name.startswith("Log."):
            if len(args) > 2:
                findings.append(Finding(rel, first, "an exception (or a third argument) passed to a log call"))
                continue
        if EXC_TEXT.search(call):
            findings.append(Finding(rel, first, "an exception's message or stack trace in a log call"))
            continue
        if PRIVATE_WORD.search(call):
            if not private_ok:
                findings.append(Finding(rel, first, "a log call naming a position, place, stops or query without // private-ok:"))
            elif not in_guard(code_lines, first - 1):
                findings.append(Finding(rel, first, "a // private-ok: log call outside the location-log guard (PV7 item 6)"))
            else:
                marked.append(f"{where}: {span_raw.strip()}")
            continue
        plain = (name.startswith("Log.") and len(args) == 2 and plain_literal(args[1])) or \
                (not name.startswith("Log.") and len(args) <= 1 and (not args or plain_literal(args[0])))
        if plain:
            continue
        if log_ok or private_ok:
            marked.append(f"{where}: {span_raw.strip()}")
        else:
            findings.append(Finding(rel, first, "a log call that prints a value without // log-ok:"))


def statement_end(code: str, i: int) -> int:
    """End of a `throw ...` statement: through the first call's closing parenthesis, or the line."""
    nl = code.find("\n", i)
    nl = len(code) if nl < 0 else nl
    p = code.find("(", i, nl)
    return match_close(code, p, "(", ")") if p >= 0 else nl


def cmd_scan(args) -> int:
    root = Path(args.root).resolve() if args.root else REPO
    findings: list[Finding] = []
    marked: list[str] = []
    mods = module_dirs(root, args.module)
    files = 0
    for mod in mods:
        for f in kotlin_files(root, mod):
            files += 1
            scan_file(root, mod, f, findings, marked)
    for line in marked:
        print("marked  " + line)
    for f in findings:
        print("BREACH  " + str(f))
    print(f"scan: {files} files in {len(mods)} modules; {len(marked)} marked lines; {len(findings)} breaches")
    return 1 if findings else 0


# ------------------------------------------------------------------ logcat
CANARY_WORDS = ["quokka", "maritime", "millcreek", "art museum", "airport"]
# 10.3: Home = Erie Maritime Museum, Work = Erie International Airport, "Quokka Shop" = Millcreek Mall,
# the new location of step 12 = Erie Art Museum
CANARY_POINTS = [(42.13646, -80.08718), (42.08418, -80.17321), (42.06875, -80.09813), (42.13108, -80.08557)]


def canary_numbers() -> list[str]:
    out = set()
    for lat, lon in CANARY_POINTS:
        for v in (abs(lat), abs(lon)):
            for d in (3, 4, 5):
                out.add(f"{v:.{d}f}")
                s = f"{v:.6f}"
                out.add(s[: s.index(".") + 1 + d])   # cut, not rounded
    return sorted(out)


# logcat's own time at the start of a line (`-v time`, threadtime: "11-16 10:13:42.068 ..."): its
# seconds are the clock, not a place (a false hit on "42.068" seen at integration, 2026-11-16).
LOGCAT_TIME = re.compile(r"^\s*\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}")


def logcat_hits(text: str) -> list[tuple[int, str]]:
    hits = []
    nums = [(n, re.compile(r"(?<![0-9.])" + re.escape(n))) for n in canary_numbers()]
    for i, line in enumerate(text.split("\n"), 1):
        low = line.lower()
        for w in CANARY_WORDS:
            if w in low:
                hits.append((i, w))
        body = LOGCAT_TIME.sub("", line, count=1)
        for n, rx in nums:
            if rx.search(body):
                hits.append((i, n))
    return hits


def cmd_logcat(args) -> int:
    bad = 0
    for f in args.files:
        text = Path(f).read_text(encoding="utf-8", errors="replace")
        hits = logcat_hits(text)
        for line, canary in hits:
            print(f"CANARY  {Path(f).name}:{line}: '{canary}'")
        bad += len(hits)
        print(f"logcat: {Path(f).name}: {len(text.splitlines())} lines, {len(hits)} canary hits")
    return 1 if bad else 0


# ------------------------------------------------------------------ manifest
ANDROID_NS = "http://schemas.android.com/apk/res/android"
DOMAINS = ("root", "file", "database", "sharedpref", "external",
           "device_root", "device_file", "device_database", "device_sharedpref")
# Every domain, the device-protected ones included (integration review, 2026-11-16).
WANT_CLOUD = {(d, ".") for d in DOMAINS}
# The lead's decision for integration (2026-11-16): every domain is excluded from device transfer too.
WANT_TRANSFER = set(WANT_CLOUD)


def manifest_problems(manifest_text: str) -> list[str]:
    out = []
    try:
        root = ET.fromstring(manifest_text)
    except ET.ParseError:
        return ["the manifest is not XML"]
    app = root.find("application")
    if app is None:
        return ["no <application>"]
    if app.get(f"{{{ANDROID_NS}}}allowBackup") != "false":
        out.append('<application> lacks android:allowBackup="false"')
    if app.get(f"{{{ANDROID_NS}}}dataExtractionRules") != "@xml/data_extraction_rules":
        out.append('<application> lacks android:dataExtractionRules="@xml/data_extraction_rules"')
    return out


def rules_from_xml(text: str) -> tuple[set, set, set]:
    root = ET.fromstring(text)
    def sect(tag):
        s = root.find(tag)
        return set() if s is None else {(e.get("domain"), e.get("path")) for e in s.findall("exclude")}
    includes = {(e.get("domain"), e.get("path")) for e in root.iter("include")}
    return sect("cloud-backup"), sect("device-transfer"), includes


def rules_from_xmltree(dump: str) -> tuple[set, set, set]:
    """Parses `aapt2 dump xmltree` output (E: elements, A: attributes, indented by depth)."""
    cloud, transfer, includes = set(), set(), set()
    section, cur = None, None
    for line in dump.splitlines():
        m = re.match(r"(\s*)E: ([\w-]+)", line)
        if m:
            tag = m.group(2)
            if tag in ("cloud-backup", "device-transfer"):
                section = tag
            if cur is not None:
                (cloud if cur[0] == "cloud-backup" else transfer if cur[0] == "device-transfer" else includes).add((cur[1], cur[2])) \
                    if cur[3] == "exclude" else includes.add((cur[1], cur[2]))
                cur = None
            if tag in ("exclude", "include"):
                cur = [section, None, None, tag]
            continue
        a = re.match(r'\s*A: (?:http://schemas.android.com/apk/res/android:)?(domain|path)(?:\([^)]*\))?="([^"]*)"', line)
        if a and cur is not None:
            cur[1 if a.group(1) == "domain" else 2] = a.group(2)
    if cur is not None:
        (cloud if cur[0] == "cloud-backup" else transfer).add((cur[1], cur[2])) if cur[3] == "exclude" else includes.add((cur[1], cur[2]))
    return cloud, transfer, includes


def rules_problems(cloud: set, transfer: set, includes: set) -> list[str]:
    out = []
    if cloud != WANT_CLOUD:
        out.append(f"cloud-backup excludes {sorted(cloud)}, want {sorted(WANT_CLOUD)}")
    if transfer != WANT_TRANSFER:
        out.append(f"device-transfer excludes {sorted(transfer)}, want {sorted(WANT_TRANSFER)}")
    if includes:
        out.append(f"unexpected <include> rules {sorted(includes)}")
    return out


def find_aapt2(explicit: str | None) -> str | None:
    if explicit:
        return explicit
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    lp = REPO / ANDROID / "local.properties"
    if not sdk and lp.is_file():
        for line in lp.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
    if not sdk:
        return shutil.which("aapt2")
    bt = Path(sdk) / "build-tools"
    cands = sorted(bt.glob("*/aapt2*")) if bt.is_dir() else []
    return str(cands[-1]) if cands else shutil.which("aapt2")


def rules_path_from_resources(dump: str) -> str | None:
    """The file of xml/data_extraction_rules in `aapt2 dump resources` output. A release build's
    resource optimizer shortens the paths (res/xml/data_extraction_rules.xml becomes res/4j.xml)."""
    lines = dump.splitlines()
    for i, line in enumerate(lines):
        if re.search(r"\bxml/data_extraction_rules\b", line):
            for nxt in lines[i + 1:i + 4]:
                m = re.search(r"\(file\)\s+(\S+)", nxt)
                if m:
                    return m.group(1)
    return None


def cmd_manifest(args) -> int:
    problems = manifest_problems(Path(args.manifest).read_text(encoding="utf-8"))
    if args.rules_xml:
        problems += rules_problems(*rules_from_xml(Path(args.rules_xml).read_text(encoding="utf-8")))
    elif args.apk:
        aapt2 = find_aapt2(args.aapt2)
        if not aapt2:
            print("manifest: aapt2 not found (give --aapt2)")
            return 2
        r = subprocess.run([aapt2, "dump", "xmltree", "--file", "res/xml/data_extraction_rules.xml", args.apk],
                           capture_output=True, text=True)
        if r.returncode != 0:
            # A shrunk release APK keeps the resource under a shortened path: look it up by name.
            res = subprocess.run([aapt2, "dump", "resources", args.apk], capture_output=True, text=True,
                                 encoding="utf-8", errors="replace")
            path = rules_path_from_resources(res.stdout) if res.returncode == 0 else None
            if path:
                r = subprocess.run([aapt2, "dump", "xmltree", "--file", path, args.apk], capture_output=True, text=True)
        if r.returncode != 0:
            problems.append("the APK has no res/xml/data_extraction_rules.xml")
        else:
            problems += rules_problems(*rules_from_xmltree(r.stdout))
    else:
        problems.append("give the APK or --rules-xml to check the rules themselves")
    for p in problems:
        print("BREACH  " + p)
    print(f"manifest: {len(problems)} problems")
    return 1 if problems else 0


# ------------------------------------------------------------------ selftest (10.2)
GOOD_RULES = """<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
        <exclude domain="device_root" path="." />
        <exclude domain="device_file" path="." />
        <exclude domain="device_database" path="." />
        <exclude domain="device_sharedpref" path="." />
    </device-transfer>
</data-extraction-rules>
"""
GOOD_MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x">
<application android:allowBackup="false" android:dataExtractionRules="@xml/data_extraction_rules"/></manifest>"""
SAVED_PKG = "core/search/src/main/kotlin/io/github/spysyweeb/beamline/search/saved"
APP_PKG = "app/src/main/kotlin/io/github/spysyweeb/beamline/app"


def _tree(files: dict[str, str]) -> Path:
    root = Path(tempfile.mkdtemp(prefix="private-logs-"))
    for rel, text in files.items():
        p = root / ANDROID / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text, encoding="utf-8")
    return root


def _scan(files: dict[str, str], modules: list[str]) -> int:
    root = _tree(files)
    try:
        ns = argparse.Namespace(root=str(root), module=modules)
        with open(os.devnull, "w") as null:
            old = sys.stdout
            sys.stdout = null
            try:
                return cmd_scan(ns)
            finally:
                sys.stdout = old
    finally:
        shutil.rmtree(root, ignore_errors=True)


def cmd_selftest(_args) -> int:
    hdr = "package x\n\nimport android.util.Log\n\n"
    clean_app = hdr + """class A(val locationLog: Boolean) {
    fun f(n: Int, e: Exception, p: P) {
        Log.i("Beamline", "plain words")
        Log.i("Beamline", "packs: $n") // log-ok: a count
        Log.w("Beamline", "failed: ${e.javaClass.simpleName}") // log-ok: the exception's class name
        if (locationLog) {
            Log.i("Beamline", "puck ${p.lat} ${p.lon}") // private-ok: the phone kit's position line, PV7 item 6
        }
        val url = "http://example.invalid/x" // not a log call
    }
}
"""
    cases = [
        ("a Log.i in search/saved/", {f"{SAVED_PKG}/X.kt": hdr + 'fun f() { Log.i("x", "plain") }\n'}, ["core/search"]),
        ("a println in :feature:search", {"feature/search/src/main/kotlin/x/Y.kt": 'package x\nfun f() { println("hi") }\n'}, ["feature/search"]),
        ("require(ok) { \"bad $name\" } in search/saved/", {f"{SAVED_PKG}/X.kt": 'package x\nfun f(ok: Boolean, name: String) {\n    require(ok) { "bad $name" }\n}\n'}, ["core/search"]),
        ("throw with a template in :map:layers", {"map/layers/src/main/kotlin/x/Z.kt": 'package x\nfun f(n: String) { throw IllegalStateException("no $n") }\n'}, ["map/layers"]),
        ("an unmarked Log.i(\"x\", \"install: $s\") in :app", {f"{APP_PKG}/A.kt": hdr + 'fun f(s: String) { Log.i("x", "install: $s") }\n'}, ["app"]),
        ("Log.e(\"x\", \"failed\", e) in :app, marked", {f"{APP_PKG}/A.kt": hdr + 'fun f(e: Exception) { Log.e("x", "failed", e) } // log-ok: none\n'}, ["app"]),
        ("Log.w(\"x\", \"${e.message}\") in :app, marked", {f"{APP_PKG}/A.kt": hdr + 'fun f(e: Exception) { Log.w("x", "${e.message}") } // log-ok: none\n'}, ["app"]),
        ("a marked Log.i(\"x\", \"at ${p.lat}\") outside the guard", {f"{APP_PKG}/A.kt": hdr + 'fun f(p: P) {\n    Log.i("x", "at ${p.lat}") // private-ok: none\n}\n'}, ["app"]),
        ("a log-ok line naming a place", {f"{APP_PKG}/A.kt": hdr + 'fun f(place: String) { Log.i("x", "to $place") } // log-ok: none\n'}, ["app"]),
        ("printStackTrace() in :app", {f"{APP_PKG}/A.kt": 'package x\nfun f(e: Exception) { e.printStackTrace() }\n'}, ["app"]),
    ]
    ok = True
    for why, files, mods in cases:
        rc = _scan(files, mods)
        print(f"{'ok  ' if rc == 1 else 'FAIL'}  scan fails on {why}")
        ok &= rc == 1
    passing = [
        ("clean :app code (plain, marked, guarded private line, a URL in a string)", {f"{APP_PKG}/A.kt": clean_app}, ["app"]),
        ("search/saved/ code with fixed messages and words in comments", {f"{SAVED_PKG}/X.kt":
            'package x\n/** Never logs: no println, no Log.i("x", "$y"). */\nfun f(ok: Boolean) {\n    require(ok) { "a fixed sentence" } // not "$x"\n    val s = "Log.i is only text here"\n}\n'}, ["core/search"]),
    ]
    for why, files, mods in passing:
        rc = _scan(files, mods)
        print(f"{'ok  ' if rc == 0 else 'FAIL'}  scan passes {why}")
        ok &= rc == 0
    # manifest
    bad_manifest = GOOD_MANIFEST.replace(' android:dataExtractionRules="@xml/data_extraction_rules"', "")
    ok &= _report("manifest fails without the rules attribute", bool(manifest_problems(bad_manifest)))
    ok &= _report("manifest passes with both attributes", not manifest_problems(GOOD_MANIFEST))
    ok &= _report("rules pass when exactly those of PV4", not rules_problems(*rules_from_xml(GOOD_RULES)))
    three_files = GOOD_RULES.split("<device-transfer>")[0] + "<device-transfer>\n" + "".join(
        f'        <exclude domain="file" path="{f}" />\n' for f in ("saved-places.json", "recents.json", "trip.json")
    ) + "    </device-transfer>\n</data-extraction-rules>\n"
    ok &= _report("rules fail with only the three files excluded from transfer", bool(rules_problems(*rules_from_xml(three_files))))
    head, tail = GOOD_RULES.split("<device-transfer>")
    no_external = head + "<device-transfer>" + tail.replace('        <exclude domain="external" path="." />\n', "")
    ok &= _report("rules fail when transfer leaves out a domain (external)", bool(rules_problems(*rules_from_xml(no_external))))
    no_device = GOOD_RULES.replace('        <exclude domain="device_root" path="." />' + "\n", "")
    ok &= _report("rules fail when a device-protected domain is left out", bool(rules_problems(*rules_from_xml(no_device))))
    dump = ("N: android=http://schemas.android.com/apk/res/android (line=1)\n  E: data-extraction-rules (line=6)\n"
            "    E: cloud-backup (line=7)\n" + "".join(
                f'      E: exclude (line=8)\n        A: domain="{d}" (Raw: "{d}")\n        A: path="." (Raw: ".")\n'
                for d in DOMAINS) +
            "    E: device-transfer (line=14)\n" + "".join(
                f'      E: exclude (line=15)\n        A: domain="{d}" (Raw: "{d}")\n        A: path="." (Raw: ".")\n'
                for d in DOMAINS))
    ok &= _report("rules read from an aapt2 xmltree dump pass", not rules_problems(*rules_from_xmltree(dump)))
    shrunk = ("    resource 0x7f110000 xml/data_extraction_rules\n      () (file) res/4j.xml type=XML\n"
              "    resource 0x7f110001 xml/other\n      () (file) res/5k.xml type=XML\n")
    ok &= _report("a shrunk APK's rules file is found by its resource name",
                  rules_path_from_resources(shrunk) == "res/4j.xml" and rules_path_from_resources("nothing") is None)
    # logcat
    ok &= _report('logcat passes on "142.136 ms"', not logcat_hits("I/Beamline: frame 142.136 ms"))
    ok &= _report('logcat fails on "at 42.1365"', bool(logcat_hits("I/Beamline: camera at 42.1365 -80.0872")))
    ok &= _report('logcat fails on "Quokka"', bool(logcat_hits("I/x: name QUOKKA shop")))
    ok &= _report('logcat fails on "-80.17321"', bool(logcat_hits("lon=-80.17321")))
    ok &= _report('logcat fails on a cut "42.068"', bool(logcat_hits("p 42.068 x")))
    ok &= _report('logcat passes on logcat\'s own time "10:13:42.068"',
                  not logcat_hits("11-16 10:13:42.068 I/Beamline(30328): trip shown 3 224 m lanes=true"))
    ok &= _report('logcat fails on a point after logcat\'s time',
                  bool(logcat_hits("11-16 10:13:00.000 I/x(1): at 42.068 -80.098")))
    print("selftest: " + ("all checks behave" if ok else "SOME CHECKS DO NOT BEHAVE"))
    return 0 if ok else 1


def _report(why: str, good: bool) -> bool:
    print(f"{'ok  ' if good else 'FAIL'}  {why}")
    return good


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("scan")
    s.add_argument("--module", action="append", help="a module folder under android/ (repeatable); default: every shipped module")
    s.add_argument("--root", help="the repository root to scan (default: this repository; a scratch copy for trials)")
    lc = sub.add_parser("logcat")
    lc.add_argument("files", nargs="+")
    mf = sub.add_parser("manifest")
    mf.add_argument("manifest")
    mf.add_argument("apk", nargs="?")
    mf.add_argument("--rules-xml")
    mf.add_argument("--aapt2")
    sub.add_parser("selftest")
    args = ap.parse_args(argv)
    return {"scan": cmd_scan, "logcat": cmd_logcat, "manifest": cmd_manifest, "selftest": cmd_selftest}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
