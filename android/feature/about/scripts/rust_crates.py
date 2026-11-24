"""Which Rust crates are compiled into a native library built by Cargo, with their licences
(track A, docs/architecture.md 5.1 "A": the licence gate lists Ferrostar's Rust crates at 0.59.0).

Without a Rust toolchain this does what `cargo tree -e normal --target <triple>` would answer,
from the files Cargo itself reads:

- the workspace's Cargo.lock (the exact versions and their sha256);
- the member's own Cargo.toml (and the workspace Cargo.toml for `workspace = true` keys);
- each crate's normalised Cargo.toml inside its .crate file from crates.io (fetched once into
  --cache and checked against the lock's sha256).

Resolution follows Cargo's feature resolver 2 for one target at a time: a crate is compiled into
the library when a normal dependency path (no build- or dev-dependencies, no proc-macro crate)
reaches it from the member with the features the build turns on, through `[target.'cfg(..)']`
tables that hold for that target. Features are unified across the whole graph; `dep:x`, `x/f`
and weak `x?/f` are followed. The union over the given targets is reported (an AAR ships them all).

  python android/feature/about/scripts/rust_crates.py resolve --lock L --member M --workspace W \
      --cache DIR --out FILE.json [--features default] [--target T ...]
  python android/feature/about/scripts/rust_crates.py apply FILE.json --library libferrostar.so \
      --first "Ferrostar core" [--first "Rust standard library" ...]
        writes the crates as entries of android/feature/about/notices/notices-source.json
        (entries marked "source": "rust_crates.py" are replaced) and sets the library's components

Standard library only (Python 3.11+ for tomllib). The only network use is a plain GET of
https://static.crates.io/crates/<name>/<name>-<version>.crate for a crate not yet in --cache.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import socket
import sys
import tarfile
import tomllib
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
SOURCE = REPO / "android" / "feature" / "about" / "notices" / "notices-source.json"
CRATE_URL = "https://static.crates.io/crates/{n}/{n}-{v}.crate"
USER_AGENT = "notices-tool/1"

# ---------------------------------------------------------------------------------------------
# Targets and cfg() expressions

_COMMON = {"target_os": "android", "target_family": "unix", "target_env": "", "target_vendor": "unknown",
           "target_endian": "little", "panic": "unwind",
           "target_has_atomic": {"8", "16", "32", "64", "ptr"}}
TARGETS: dict[str, dict] = {
    "aarch64-linux-android": {**_COMMON, "target_arch": "aarch64", "target_pointer_width": "64", "target_abi": "",
                              "target_feature": {"neon", "fp-armv8"}},
    "armv7-linux-androideabi": {**_COMMON, "target_arch": "arm", "target_pointer_width": "32", "target_abi": "eabi",
                                "target_feature": {"v7", "vfp3", "thumb2"}},
    "i686-linux-android": {**_COMMON, "target_arch": "x86", "target_pointer_width": "32", "target_abi": "",
                           "target_feature": {"sse", "sse2", "fxsr"}},
    "x86_64-linux-android": {**_COMMON, "target_arch": "x86_64", "target_pointer_width": "64", "target_abi": "",
                             "target_feature": {"sse", "sse2", "fxsr"}},
}
_FLAGS = {"unix"}   # bare cfg names that hold on every Android target


def cfg_matches(spec: str, triple: str) -> bool:
    """A `[target.<spec>]` key: a triple, or cfg(...). Unknown cfg names and keys are false
    (custom flags such as docsrs or getrandom_backend are not set by an ordinary build)."""
    spec = spec.strip()
    if not spec.startswith("cfg("):
        return spec == triple
    values = TARGETS[triple]
    tokens = re.findall(r'\(|\)|,|=|"[^"]*"|[A-Za-z_][A-Za-z0-9_]*', spec[3:])
    pos = 0

    def expr() -> bool:
        nonlocal pos
        name = tokens[pos]
        pos += 1
        if name in ("all", "any", "not") and pos < len(tokens) and tokens[pos] == "(":
            pos += 1
            args = []
            while tokens[pos] != ")":
                args.append(expr())
                if tokens[pos] == ",":
                    pos += 1
            pos += 1
            if name == "all":
                return all(args)
            if name == "any":
                return any(args)
            if len(args) != 1:
                raise ValueError(f"not() takes one argument: {spec}")
            return not args[0]
        if pos < len(tokens) and tokens[pos] == "=":
            pos += 1
            value = tokens[pos].strip('"')
            pos += 1
            have = values.get(name)
            if isinstance(have, set):
                return value in have
            return have == value
        return name in _FLAGS

    if not tokens or tokens[0] != "(":
        raise ValueError(f"bad cfg: {spec}")
    pos = 1
    result = expr()
    if tokens[pos] != ")":
        raise ValueError(f"bad cfg: {spec}")
    return result


# ---------------------------------------------------------------------------------------------
# Versions


def _ver(v: str) -> tuple[int, int, int, str]:
    m = re.match(r"(\d+)(?:\.(\d+))?(?:\.(\d+))?(.*)", v.strip())
    if not m:
        raise ValueError(f"not a version: {v}")
    return int(m.group(1)), int(m.group(2) or 0), int(m.group(3) or 0), m.group(4)


def _caret_upper(parts: tuple[int, ...], given: int) -> tuple[int, int, int]:
    major, minor, patch = parts[:3]
    if major > 0 or given == 1:
        return major + 1, 0, 0
    if minor > 0 or given == 2:
        return 0, minor + 1, 0
    return 0, 0, patch + 1


def version_matches(req: str, version: str) -> bool:
    """Cargo's version requirements (caret by default, ~, =, <, <=, >, >=, *, comma = and)."""
    v = _ver(version)[:3]
    for part in [p.strip() for p in (req or "*").split(",") if p.strip()]:
        m = re.match(r"(\^|~|=|<=|>=|<|>)?\s*(.*)", part)
        op, rest = m.group(1) or "^", m.group(2)
        if rest in ("*", ""):
            continue
        rest = rest.replace(".*", "").replace(".x", "")
        given = len(rest.split("-")[0].split("."))
        b = _ver(rest)[:3]
        ok = {
            "^": lambda: b <= v < _caret_upper(b, given),
            "~": lambda: b <= v < ((b[0] + 1, 0, 0) if given == 1 else (b[0], b[1] + 1, 0)),
            "=": lambda: v[:given] == b[:given],
            "<": lambda: v < b,
            "<=": lambda: v <= b,
            ">": lambda: v > b,
            ">=": lambda: v >= b,
        }[op]()
        if not ok:
            return False
    return True


# ---------------------------------------------------------------------------------------------
# Manifests


@dataclass
class Dep:
    key: str            # the name used in this manifest (features refer to it)
    package: str        # the crate's real name
    req: str
    optional: bool
    default_features: bool
    features: list[str]
    kind: str           # normal, build, dev
    target: str | None  # [target.<spec>] key, or None


@dataclass
class Manifest:
    name: str
    version: str
    licence: str | None
    licence_file: str | None
    proc_macro: bool
    deps: list[Dep]
    features: dict[str, list[str]]
    files: dict[str, str] = field(default_factory=dict)   # licence-like files: name -> text
    authors: list[str] = field(default_factory=list)      # Cargo.toml [package] authors
    repository: str | None = None


def _deps_of(table: dict, kind: str, target: str | None, workspace: dict) -> list[Dep]:
    out = []
    for key, spec in (table or {}).items():
        if isinstance(spec, str):
            spec = {"version": spec}
        spec = dict(spec)
        if spec.get("workspace"):
            ws = workspace.get(key, {})
            ws = {"version": ws} if isinstance(ws, str) else dict(ws)
            feats = list(ws.get("features", [])) + list(spec.get("features", []))
            ws.update({k: v for k, v in spec.items() if k not in ("workspace", "features")})
            ws["features"] = feats
            spec = ws
        if "path" in spec and "version" not in spec:
            raise ValueError(f"path dependency {key} without a version is not supported")
        out.append(Dep(key, spec.get("package", key), spec.get("version", "*"), bool(spec.get("optional")),
                       spec.get("default-features", spec.get("default_features", True)),
                       list(spec.get("features", [])), kind, target))
    return out


def parse_manifest(text: str, workspace_text: str | None = None) -> Manifest:
    t = tomllib.loads(text)
    ws = tomllib.loads(workspace_text) if workspace_text else {}
    ws_deps = ws.get("workspace", {}).get("dependencies", {})
    ws_pkg = ws.get("workspace", {}).get("package", {})
    pkg = t["package"]

    def pval(k):
        v = pkg.get(k)
        if isinstance(v, dict) and v.get("workspace"):
            return ws_pkg.get(k)
        return v

    deps: list[Dep] = []
    for kind, name in (("normal", "dependencies"), ("build", "build-dependencies"), ("dev", "dev-dependencies")):
        deps += _deps_of(t.get(name) or t.get(name.replace("-", "_")), kind, None, ws_deps)
        for spec, tables in (t.get("target") or {}).items():
            deps += _deps_of(tables.get(name) or tables.get(name.replace("-", "_")), kind, spec, ws_deps)
    lib = t.get("lib") or {}
    return Manifest(pkg["name"], str(pval("version")), pval("license"), pval("license-file"),
                    bool(lib.get("proc-macro") or lib.get("proc_macro")), deps,
                    {k: list(v) for k, v in (t.get("features") or {}).items()},
                    authors=[str(x) for x in (pval("authors") or [])], repository=pval("repository"))


# ---------------------------------------------------------------------------------------------
# Lock file and crate files


@dataclass
class Locked:
    name: str
    version: str
    checksum: str | None
    deps: list[tuple[str, str | None]]   # (name, version when the lock names one)


def load_lock(path: Path) -> dict[tuple[str, str], Locked]:
    lock = tomllib.loads(path.read_text(encoding="utf-8"))
    out = {}
    for p in lock["package"]:
        deps = []
        for d in p.get("dependencies", []):
            parts = d.split(" ")
            deps.append((parts[0], parts[1] if len(parts) > 1 else None))
        out[(p["name"], p["version"])] = Locked(p["name"], p["version"], p.get("checksum"), deps)
    return out


def _get(url: str) -> bytes:
    """A plain GET with a generic User-Agent. IPv4 only: where IPv6 has no route, each connection
    would first wait for the IPv6 attempt to time out (about 21 s on the PC this was written on)."""
    real = socket.getaddrinfo

    def v4(host, port, family=0, *args, **kw):
        return real(host, port, socket.AF_INET, *args, **kw)

    socket.getaddrinfo = v4
    try:
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.read()
    finally:
        socket.getaddrinfo = real


LICENCE_FILE = re.compile(r"^(licen[cs]e|copying|copyright|notice|unlicense)([-_.].*)?$", re.I)


def crate_manifest(name: str, version: str, checksum: str | None, cache: Path, fetch: bool) -> Manifest:
    f = cache / f"{name}-{version}.crate"
    if not f.exists():
        if not fetch:
            raise FileNotFoundError(f"{f} is not in the cache (run without --offline)")
        data = _get(CRATE_URL.format(n=name, v=version))
        if checksum and hashlib.sha256(data).hexdigest() != checksum:
            raise ValueError(f"{name}-{version}.crate: sha256 differs from Cargo.lock")
        cache.mkdir(parents=True, exist_ok=True)
        f.write_bytes(data)
    data = f.read_bytes()
    if checksum and hashlib.sha256(data).hexdigest() != checksum:
        raise ValueError(f"{f.name}: sha256 differs from Cargo.lock")
    files: dict[str, str] = {}
    toml_text = None
    with tarfile.open(fileobj=io.BytesIO(gzip.decompress(data)), mode="r:") as tar:
        for m in tar.getmembers():   # read in memory only, nothing is extracted
            if not m.isfile():
                continue
            parts = m.name.split("/")
            if len(parts) != 2:
                continue
            if parts[1] == "Cargo.toml":
                toml_text = tar.extractfile(m).read().decode("utf-8")
            elif LICENCE_FILE.match(parts[1]) and m.size < 400_000:
                raw = tar.extractfile(m).read()
                try:
                    files[parts[1]] = raw.decode("utf-8")
                except UnicodeDecodeError:
                    files[parts[1]] = raw.decode("latin-1")
    if toml_text is None:
        raise ValueError(f"{f.name}: no Cargo.toml")
    man = parse_manifest(toml_text)
    man.files = files
    return man


# ---------------------------------------------------------------------------------------------
# Feature resolution (resolver 2, normal dependencies of one target)


class Graph:
    def __init__(self, lock: dict[tuple[str, str], Locked], load):
        self.lock = lock
        self.load = load                 # (name, version) -> Manifest
        self.manifests: dict[tuple[str, str], Manifest] = {}
        self.features: dict[tuple[str, str], set[str]] = {}
        self.on_deps: dict[tuple[str, str], set[str]] = {}   # optional deps turned on, by key
        self.parent: dict[tuple[str, str], tuple[str, str] | None] = {}

    def manifest(self, pkg):
        if pkg not in self.manifests:
            self.manifests[pkg] = self.load(pkg)
        return self.manifests[pkg]

    def locked_dep(self, pkg, dep: Dep) -> tuple[str, str]:
        cands = [v for n, v in self.lock[pkg].deps if n == dep.package]
        versions = [v or next(lv for (ln, lv) in self.lock if ln == dep.package) for v in cands]
        if not versions:
            raise KeyError(f"{pkg[0]} {pkg[1]}: {dep.package} is not among its dependencies in Cargo.lock")
        ok = [v for v in versions if version_matches(dep.req, v)]
        if len(ok) != 1:
            raise KeyError(f"{pkg[0]} {pkg[1]}: {dep.package} {dep.req} matches {ok} of {versions}")
        return dep.package, ok[0]


def resolve(lock: dict, root: tuple[str, str], root_features: list[str], triple: str, load) -> Graph:
    g = Graph(lock, load)
    g.features[root] = set()
    g.on_deps[root] = set()
    g.parent[root] = None
    changed = True

    def deps_for(pkg) -> list[Dep]:
        m = g.manifest(pkg)
        return [d for d in m.deps if d.kind == "normal" and (d.target is None or cfg_matches(d.target, triple))]

    def activate(pkg, dep: Dep, extra: list[str]) -> None:
        nonlocal changed
        child = g.locked_dep(pkg, dep)
        if g.manifest(child).proc_macro:
            return   # compiled for the build machine; nothing of it is linked
        if child not in g.features:
            g.features[child] = set()
            g.on_deps[child] = set()
            g.parent[child] = pkg
            changed = True
        want = list(dep.features) + list(extra) + (["default"] if dep.default_features else [])
        for f in want:
            enable(child, f)

    def enable(pkg, feat: str) -> None:
        nonlocal changed
        m = g.manifest(pkg)
        if feat in g.features[pkg]:
            return
        if feat not in m.features:
            if feat == "default":
                return
            if any(d.key == feat and d.optional for d in m.deps):   # implicit feature of an optional dep
                g.features[pkg].add(feat)
                g.on_deps[pkg].add(feat)
                changed = True
                return
            raise KeyError(f"{m.name} {m.version} has no feature {feat!r}")
        g.features[pkg].add(feat)
        changed = True
        for item in m.features[feat]:
            if item.startswith("dep:"):
                g.on_deps[pkg].add(item[4:])
            elif "/" in item:
                key, sub = item.split("/", 1)
                if not key.endswith("?"):
                    if any(d.key == key and d.optional for d in m.deps):
                        g.on_deps[pkg].add(key)
                    # the feature itself is applied below, once the dependency is active
            elif item in m.features or not any(d.key == item and d.optional for d in m.deps):
                enable(pkg, item)
            else:
                g.on_deps[pkg].add(item)

    for f in root_features:
        enable(root, f)
    while changed:
        changed = False
        for pkg in list(g.features):
            m = g.manifest(pkg)
            for d in deps_for(pkg):
                if d.optional and d.key not in g.on_deps[pkg]:
                    continue
                extra = []
                for feat in g.features[pkg]:
                    for item in m.features.get(feat, []):
                        if "/" in item and not item.startswith("dep:"):
                            key, sub = item.split("/", 1)
                            if key.rstrip("?") == d.key:
                                extra.append(sub)
                before = (len(g.features), sum(len(v) for v in g.features.values()))
                activate(pkg, d, extra)
                after = (len(g.features), sum(len(v) for v in g.features.values()))
                if before != after:
                    changed = True
    return g


# ---------------------------------------------------------------------------------------------
# Licences

PREFERENCE = ["MIT", "Apache-2.0", "BSD-3-Clause", "BSD-2-Clause", "ISC", "Zlib", "BSL-1.0", "Unicode-3.0",
              "Unicode-DFS-2016", "CC0-1.0", "Unlicense", "MPL-2.0"]


def normalise_licence(s: str) -> str:
    s = s.strip().replace(" / ", " OR ").replace("/", " OR ")
    return re.sub(r"\s+", " ", s)


def options(expr: str) -> list[list[str]]:
    """The ways to satisfy an SPDX expression: each a list of terms (ids, or 'id WITH exc') that
    all apply. Parentheses, AND and OR; AND binds tighter."""
    tokens = re.findall(r"\(|\)|[A-Za-z0-9.+-]+(?:\s+WITH\s+[A-Za-z0-9.+-]+)?", expr)
    pos = 0

    def primary():
        nonlocal pos
        t = tokens[pos]
        pos += 1
        if t == "(":
            r = disj()
            pos += 1   # ")"
            return r
        return [[re.sub(r"\s+", " ", t)]]

    def conj():
        nonlocal pos
        r = primary()
        while pos < len(tokens) and tokens[pos] == "AND":
            pos += 1
            nxt = primary()
            r = [a + b for a in r for b in nxt]
        return r

    def disj():
        nonlocal pos
        r = conj()
        while pos < len(tokens) and tokens[pos] == "OR":
            pos += 1
            r = r + conj()
        return r

    out = disj()
    return [sorted(set(o), key=o.index) for o in out]


def choose(expr: str) -> str:
    """The option Beamline takes: never a GPL-family one; otherwise the most permissive by PREFERENCE."""
    def rank(term: str) -> int:
        base = term.split(" WITH ")[0]
        return PREFERENCE.index(base) if base in PREFERENCE else len(PREFERENCE) + 1

    opts = [o for o in options(expr) if not any("GPL" in t.upper() for t in o)] or options(expr)
    best = min(opts, key=lambda o: (len(o), sorted(rank(t) for t in o)))
    return " AND ".join(best)


COPYRIGHT = re.compile(r"^\s*Copyright(?:s\b)?\s*(?:\(c\)|©)?\s*(?P<rest>.*)$")
# Sentences of licence texts that begin with the word, not notices. "The Rust Project Developers"
# is a holder ("Copyright (c) The Rust Project Developers", libc), so "the" alone is not enough.
NOT_A_NOTICE = re.compile(r"^(license|licen[cs]e|notice|holders?|owners?|and|law|statement|\[yyyy\]|\{yyyy\}|<year>|"
                          r"\[name of copyright owner\]|the (above|copyright|software|work|licen[cs]e|owner)\b|"
                          r"this |by |of |to |is |or )", re.I)

# Crates whose .crate file holds no licence file at all: the copyright line of the licence file in
# their repository, read on 2026-11-16 (track A's scratch data/r3-a/notices/upstream/copyright/).
REPOSITORY_NOTICES = {
    "geo": "Copyright (c) 2017 The GeoRust Project Developers",          # georust/geo LICENSE-MIT
    "geo-types": "Copyright (c) 2017 The GeoRust Project Developers",    # same repository
    "rstar": "Copyright (c) 2018 The rstar project developers",          # georust/rstar LICENSE-MIT
    "i_overlay": "Copyright (c) 2023 iShape-Rust",                       # iShape-Rust/iOverlay LICENSE-MIT
    "i_key_sort": "Copyright (c) 2024 iShape-Rust",                      # iShape-Rust/iKeySort LICENSE
}


def strip_emails(text: str) -> str:
    """The text without e-mail addresses (round privacy rule: none in the app's notices); an
    address in <...> or (...) goes with its brackets, the names stay."""
    text = re.sub(r"\s*<[^<>\s]+@[^<>\s]+>", "", text)
    return re.sub(r"\s*\(?\b[\w.+-]+@[\w-]+(\.[\w-]+)+\b\)?", "", text).strip()


def notice_of(m: Manifest, chosen: str) -> tuple[list[str], str]:
    """The copyright notice of a crate and where it was found. Many crates' licence files are the
    bare licence text with no holder (MIT asks that "the above copyright notice" travels with
    every copy), so when the crate's own files name none: the repository's licence file
    (REPOSITORY_NOTICES), else the authors of its Cargo.toml, else its repository's contributors."""
    lines = copyright_lines(m, chosen)
    if lines:
        return lines, "the crate's licence file"
    if m.name in REPOSITORY_NOTICES:
        return [REPOSITORY_NOTICES[m.name]], "the licence file of its repository (the crate has none)"
    names = [n for n in (strip_emails(a) for a in m.authors) if n]
    if names:
        return [f"Copyright (c) {', '.join(names)} and the {m.name} contributors"], \
            "its Cargo.toml authors (its licence file names no holder)"
    if m.repository:
        repo = re.sub(r"^https?://|\.git$|/$", "", m.repository)
        return [f"Copyright (c) the contributors of {repo}"], \
            "its repository (neither its licence file nor its Cargo.toml names a holder)"
    return [], "nowhere"


def copyright_lines(m: Manifest, chosen: str) -> list[str]:
    """The copyright notices of the crate's licence files (lines that begin with "Copyright",
    not the licence texts' own sentences), the file of the chosen licence first."""
    ids = [t.split(" WITH ")[0] for t in chosen.split(" AND ")]

    def order(fname: str) -> int:
        up = fname.upper()
        if up.startswith("COPYRIGHT"):
            return 0
        return 1 if any(i.upper().split("-")[0] in up for i in ids) else 2
    lines: list[str] = []
    for fname in sorted(m.files, key=lambda n: (order(n), n)):
        flines = m.files[fname].splitlines()
        for i, line in enumerate(flines):
            mm = COPYRIGHT.match(line)
            if not mm or not mm.group("rest").strip() or NOT_A_NOTICE.match(mm.group("rest").strip()):
                continue
            text = re.sub(r"\s+", " ", line.strip())
            if text.startswith("Copyrights"):   # a sentence, not a notice line: keep it whole
                for more in flines[i + 1:i + 4]:
                    if "." in text:
                        break
                    text += " " + more.strip()
                text = text[:text.index(".") + 1] if "." in text else text
            # A © that the file's own encoding lost (U+FFFD), as in libm's copy of musl's COPYRIGHT
            text = re.sub(r"^(Copyright) � ", r"\1 (c) ", text)
            # No e-mail addresses in the app's notices (the names stay)
            text = strip_emails(text)
            if re.search(r"HOLDERS BE LIABLE|copyright notice|above copyright", text, re.I):
                continue
            if text not in lines:
                lines.append(text)
        if lines:
            break
    return lines[:3]


# ---------------------------------------------------------------------------------------------
# Commands


def cmd_resolve(a) -> int:
    lock = load_lock(a.lock)
    member_text = a.member.read_text(encoding="utf-8")
    ws_text = a.workspace.read_text(encoding="utf-8") if a.workspace else None
    root_man = parse_manifest(member_text, ws_text)
    root = (root_man.name, root_man.version)
    if root not in lock:
        raise SystemExit(f"{root} is not in {a.lock}")

    def load(pkg):
        if pkg == root:
            return root_man
        return crate_manifest(pkg[0], pkg[1], lock[pkg].checksum, a.cache, not a.offline)

    union: dict[tuple[str, str], dict] = {}
    per_target = {}
    graphs = {}
    for triple in a.target or list(TARGETS):
        g = resolve(lock, root, a.features, triple, load)
        graphs[triple] = g
        per_target[triple] = sorted(f"{n} {v}" for (n, v) in g.features if (n, v) != root)
        for pkg in g.features:
            if pkg == root:
                continue
            union.setdefault(pkg, {"targets": []})["targets"].append(triple)
            union[pkg]["features"] = sorted(set(union[pkg].get("features", [])) | g.features[pkg])
    crates = []
    for (n, v), info in sorted(union.items()):
        g = next(graphs[t] for t in info["targets"])
        m = g.manifest((n, v))
        path, p = [], (n, v)
        while p is not None:
            path.append(p[0])
            p = g.parent.get(p)
        upstream = normalise_licence(m.licence) if m.licence else None
        chosen = choose(upstream) if upstream else None
        notice, notice_from = notice_of(m, chosen or "")
        crates.append({
            "name": n, "version": v, "licence": chosen, "upstream_licence": upstream,
            "licence_file": m.licence_file, "copyright": notice, "copyright_from": notice_from,
            "targets": info["targets"] if len(info["targets"]) < len(a.target or TARGETS) else "all",
            "features": info["features"], "via": " <- ".join(path),
            "licence_files": sorted(m.files),
        })
    out = {"root": f"{root[0]} {root[1]}", "features": a.features, "lock": a.lock.name,
           "targets": a.target or list(TARGETS), "crates": crates}
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    print(f"{len(crates)} crates compiled into {root[0]} {root[1]} for {', '.join(out['targets'])} -> {a.out}")
    for c in crates:
        flag = "" if c["licence"] else "  (NO LICENCE FIELD)"
        print(f"  {c['name']} {c['version']}: {c['upstream_licence']} -> {c['licence']}{flag}")
    return 0


def cmd_listed(a) -> int:
    """Crates named one by one (no resolution), e.g. the Rust standard library's own dependencies
    from rust-lang/rust's library/Cargo.lock: their licences and copyright lines, same format."""
    lock = load_lock(a.lock)
    crates = []
    for spec in a.crate:
        n, v = spec.split("@")
        m = crate_manifest(n, v, lock[(n, v)].checksum, a.cache, not a.offline)
        upstream = normalise_licence(m.licence) if m.licence else None
        chosen = choose(upstream) if upstream else None
        notice, notice_from = notice_of(m, chosen or "")
        crates.append({"name": n, "version": v, "licence": chosen, "upstream_licence": upstream,
                       "licence_file": m.licence_file, "copyright": notice, "copyright_from": notice_from,
                       "targets": "all", "features": [], "via": a.via, "licence_files": sorted(m.files)})
    out = {"root": a.via, "features": [], "lock": a.lock.name, "targets": list(TARGETS), "crates": crates}
    a.out.write_text(json.dumps(out, indent=1) + "\n", encoding="utf-8")
    for c in crates:
        print(f"  {c['name']} {c['version']}: {c['upstream_licence']} -> {c['licence']}  {c['copyright'][:1]}")
    return 0


def cmd_apply(a) -> int:
    src = json.loads(SOURCE.read_text(encoding="utf-8"))
    kept = [e for e in src["entries"] if e.get("source") != "rust_crates.py"]
    by_key: dict[tuple[str, str], dict] = {}
    for path in a.json:
        data = json.loads(path.read_text(encoding="utf-8"))
        for c in data["crates"]:
            if not c["licence"]:
                raise SystemExit(f"{c['name']} {c['version']}: no licence field; curate it by hand first")
            key = (c["name"], c["version"])
            via = f"{data['root']} (features {', '.join(data['features'])}) via {c['via']}" if data["features"] \
                else f"{data['root']}"
            if key in by_key:
                by_key[key]["checked"] += f"; also {via}"
                continue
            e = {
                "kind": "native", "name": f"{c['name']} (Rust crate)", "version": c["version"],
                "licence": c["licence"], "copyright": "; ".join(c["copyright"]) or None,
                "url": f"https://crates.io/crates/{c['name']}",
                "checked": f"Cargo.toml and licence files of {c['name']}-{c['version']}.crate from crates.io "
                           f"(sha256 as in {data['lock']}); compiled into {a.library}: {via}"
                           + (f"; copyright from {c['copyright_from']}" if c.get("copyright_from") else ""),
                "source": "rust_crates.py",
            }
            if c["upstream_licence"] != c["licence"]:
                e["upstream_licence"] = c["upstream_licence"]
            by_key[key] = e
    new = [by_key[k] for k in sorted(by_key, key=lambda k: (k[0].lower(), k[1]))]
    src["entries"] = kept + new
    lib = src["native_libraries"][a.library]
    lib["components"] = list(a.first) + sorted({e["name"] for e in new}, key=str.lower)
    SOURCE.write_text(json.dumps(src, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{len(new)} crate entries written for {a.library}; {len(lib['components'])} components")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("resolve")
    r.add_argument("--lock", type=Path, required=True)
    r.add_argument("--member", type=Path, required=True)
    r.add_argument("--workspace", type=Path)
    r.add_argument("--cache", type=Path, required=True)
    r.add_argument("--out", type=Path, required=True)
    r.add_argument("--features", nargs="*", default=["default"])
    r.add_argument("--target", action="append", choices=sorted(TARGETS))
    r.add_argument("--offline", action="store_true", help="use only crates already in --cache")
    s = sub.add_parser("listed")
    s.add_argument("--lock", type=Path, required=True)
    s.add_argument("--crate", action="append", required=True, help="name@version, as in the lock")
    s.add_argument("--via", required=True, help="what brings them in, e.g. 'Rust standard library 1.99.1'")
    s.add_argument("--cache", type=Path, required=True)
    s.add_argument("--out", type=Path, required=True)
    s.add_argument("--offline", action="store_true")
    p = sub.add_parser("apply")
    p.add_argument("json", type=Path, nargs="+")
    p.add_argument("--library", required=True)
    p.add_argument("--first", action="append", default=[])
    a = ap.parse_args(argv)
    return {"resolve": cmd_resolve, "listed": cmd_listed, "apply": cmd_apply}[a.cmd](a)


if __name__ == "__main__":
    sys.exit(main())
