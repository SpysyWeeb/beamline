"""Measurements of a built search index.

    python packbuilder/search/measure.py --state pa                 # all measurements
    python packbuilder/search/measure.py --state pa --only sizes

sizes    bytes per table and index (DBSTAT through the official sqlite3.exe from
         tools/bin/sqlite, since Python's bundled SQLite lacks DBSTAT), row counts
latency  query latency per test-case type, cold (new connection, SQLite page
         cache empty; the OS file cache is NOT flushed) and warm (same
         connection, 5 repeats), median and 95th percentile
fts      size/build time of the name index under other FTS5 options
compat   runs the engine's SQL shapes on the official SQLite 3.50.1 CLI (the
         version androidx sqlite-bundled 2.7.x compiles in) and prints SQLite
         version / compile options of this Python
prefix   search-as-you-type latency: every 1- and 2-letter prefix (26 + 676),
         the 100 most common 3-letter word starts of the vocabulary and 8 common
         whole words, at 3 bias points (Erie, Philadelphia, Altoona); each query
         run twice on one connection, the second (warm) run is reported
house    house-number queries with and without the addresses database attached
         (warm, 5 repeats)

--db PATH measures another database file (e.g. a saved copy of an older build).

Output: data/search/<state>-measurements.json
"""
from __future__ import annotations

import argparse
import collections
import json
import os
import pathlib
import platform
import sqlite3
import statistics
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import query  # noqa: E402

SQLITE_CLI = os.path.join(REPO, "tools", "bin", "sqlite", "sqlite3.exe")


def ro_uri(path: str) -> str:
    return pathlib.Path(path).resolve().as_uri() + "?mode=ro"


def pct(xs, p):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(round(p * (len(xs) - 1))))]


def sizes(db_path: str) -> dict:
    out = {"file_bytes": os.path.getsize(db_path)}
    if os.path.exists(SQLITE_CLI):
        sql = ("SELECT name, SUM(pgsize), COUNT(*) FROM dbstat GROUP BY name ORDER BY 2 DESC;")
        r = subprocess.run([SQLITE_CLI, "-readonly", "-separator", "\t", db_path, sql],
                           capture_output=True, text=True, check=True)
        objs = {}
        for line in r.stdout.strip().splitlines():
            name, b, pages = line.split("\t")
            objs[name] = {"bytes": int(b), "pages": int(pages)}
        out["objects"] = objs
        # group shadow tables under their virtual table
        groups = collections.Counter()
        for name, v in objs.items():
            g = name
            for vt in ("entry_fts", "entry_rtree", "vocab_tri"):
                if name.startswith(vt + "_") or name == vt:
                    g = vt
            if name.startswith("sqlite_autoindex_"):
                g = name.replace("sqlite_autoindex_", "").rsplit("_", 1)[0] + " (autoindex)"
            groups[g] += v["bytes"]
        out["grouped_bytes"] = dict(groups.most_common())
    else:
        out["objects"] = "tools/bin/sqlite/sqlite3.exe missing: run python tools/fetch_sqlite_tools.py"
    db = sqlite3.connect(ro_uri(db_path), uri=True)
    tables = [r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type IN ('table') "
                                        "AND name NOT LIKE '%_data' AND name NOT LIKE '%_idx' AND name NOT LIKE '%_config' "
                                        "AND name NOT LIKE '%_node' AND name NOT LIKE '%_rowid' AND name NOT LIKE '%_parent' "
                                        "AND name NOT LIKE 'sqlite_%'")]
    out["rows"] = {t: db.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0] for t in tables
                   if t not in ("entry_fts", "vocab_tri")}
    out["rows"]["entry by kind"] = {str(k): n for k, n in db.execute("SELECT kind, COUNT(*) FROM entry GROUP BY kind")}
    out["meta"] = dict(db.execute("SELECT key, value FROM meta"))
    return out


def latency(db_path: str, cases_files: list[str]) -> dict:
    points = json.load(open(os.path.join(HERE, "tests", "points.json")))["points"]
    cases = []
    for cf in cases_files:
        cases += json.load(open(os.path.join(HERE, "tests", cf), encoding="utf-8"))["cases"]
    cold = collections.defaultdict(list)
    warm = collections.defaultdict(list)
    allc, allw = [], []
    for c in cases:
        bias = None
        if "at" in c:
            bias = (points[c["at"]]["lat"], points[c["at"]]["lon"])
        elif "near" in c:
            bias = tuple(c["near"])
        lat, lon = (bias if bias else (None, None))
        eng = query.SearchEngine(db_path)          # new connection: empty page cache
        t0 = time.perf_counter()
        eng.search(c["q"], lat, lon)
        ms = (time.perf_counter() - t0) * 1000
        cold[c["type"]].append(ms)
        allc.append(ms)
        for _ in range(5):
            t0 = time.perf_counter()
            eng.search(c["q"], lat, lon)
            ms = (time.perf_counter() - t0) * 1000
            warm[c["type"]].append(ms)
            allw.append(ms)
        eng.db.close()
    res = {}
    for t in sorted(cold):
        res[t] = {"queries": len(cold[t]),
                  "cold_median_ms": round(statistics.median(cold[t]), 2), "cold_p95_ms": round(pct(cold[t], .95), 2),
                  "warm_median_ms": round(statistics.median(warm[t]), 2), "warm_p95_ms": round(pct(warm[t], .95), 2)}
    res["ALL"] = {"queries": len(allc),
                  "cold_median_ms": round(statistics.median(allc), 2), "cold_p95_ms": round(pct(allc, .95), 2),
                  "warm_median_ms": round(statistics.median(allw), 2), "warm_p95_ms": round(pct(allw, .95), 2),
                  "warm_max_ms": round(max(allw), 2)}
    return res


def _stats(xs):
    return {"n": len(xs), "median_ms": round(statistics.median(xs), 2), "p95_ms": round(pct(xs, .95), 2),
            "max_ms": round(max(xs), 2)}


def prefix_latency(db_path: str) -> dict:
    points = json.load(open(os.path.join(HERE, "tests", "points.json")))["points"]
    letters = "abcdefghijklmnopqrstuvwxyz"
    one = list(letters)
    two = [a + b for a in letters for b in letters]
    db = sqlite3.connect(ro_uri(db_path), uri=True)
    starts = {}
    for w, f in db.execute("SELECT word, freq FROM vocab WHERE length(word) >= 3"):
        if w[:3].isalpha():
            starts[w[:3]] = starts.get(w[:3], 0) + f
    db.close()
    three = [w for w, _ in sorted(starts.items(), key=lambda x: -x[1])[:100]]
    words = ["street", "road", "lane", "church", "avenue", "main", "park", "school"]
    groups = {"1 letter": one, "2 letters": two, "3 letters (100 most common)": three, "common words": words}
    res, combined = {}, {}
    for pt in ("erie", "philadelphia", "altoona"):
        lat, lon = points[pt]["lat"], points[pt]["lon"]
        eng = query.SearchEngine(db_path)
        for g, qs in groups.items():
            warm, worst = [], []
            for q in qs:
                eng.search(q, lat, lon)
                t0 = time.perf_counter()
                eng.search(q, lat, lon)
                ms = (time.perf_counter() - t0) * 1000
                warm.append(ms)
                worst.append((round(ms, 1), q))
            combined.setdefault(g, []).extend(warm)
            res.setdefault(g, {})[pt] = dict(_stats(warm), slowest=sorted(worst, reverse=True)[:5])
        eng.db.close()
        print(pt, json.dumps({g: {k: v for k, v in d[pt].items() if k != "slowest"} for g, d in res.items()}))
    for g, xs in combined.items():
        res[g]["all points"] = _stats(xs)
    print("all points", json.dumps({g: res[g]["all points"] for g in groups}))
    return res


HOUSE_QUERIES = [("1 main st", "erie"), ("100 main st", "erie"), ("500 church st", "erie"), ("123 n main st", "erie"),
                 ("4319 peach st", "erie"), ("1600 market st philadelphia", "pittsburgh"),
                 ("600 grant street pittsburgh", "erie"), ("72 s main st wilkes barre", "wilkes_barre")]


def house_latency(db_path: str, addresses: str) -> dict:
    points = json.load(open(os.path.join(HERE, "tests", "points.json")))["points"]
    res = {}
    for label, apath in (("without addresses", None), ("with addresses", addresses)):
        if apath and not os.path.exists(apath):
            res[label] = "addresses database missing"
            continue
        try:
            # the addresses track may be mid-rebuild with another normaliser version: measure anyway, say so
            eng = query.SearchEngine(db_path, addresses_path=apath, allow_addr_version_mismatch=True)
        except TypeError:       # engine versions before the review fixes
            eng = query.SearchEngine(db_path, addresses_path=apath)
        per = {"_warning": getattr(eng, "addr_warning", None)}
        for q, pt in HOUSE_QUERIES:
            lat, lon = points[pt]["lat"], points[pt]["lon"]
            eng.search(q, lat, lon)
            xs = []
            for _ in range(5):
                t0 = time.perf_counter()
                r = eng.search(q, lat, lon)
                xs.append((time.perf_counter() - t0) * 1000)
            top = r["results"][0] if r["results"] else {}
            per[q] = {"median_ms": round(statistics.median(xs), 1), "max_ms": round(max(xs), 1),
                      "top": top.get("title"), "house_located": top.get("house_located")}
        eng.db.close()
        res[label] = per
        print(label, json.dumps(per))
    return res


COMPAT_SQL = """
SELECT sqlite_version();
SELECT count(*) FROM entry_fts WHERE entry_fts MATCH '"peach" AND ("street" OR "st"*)';
SELECT count(*) FROM entry_fts WHERE entry_fts MATCH '("beech" OR "peach") AND "street"*';
SELECT count(*) FROM entry_fts f JOIN entry e ON e.id = f.rowid WHERE entry_fts MATCH '"walmart"*' ORDER BY e.importance DESC LIMIT 5;
SELECT count(*) FROM entry_rtree WHERE maxlat >= 42.0 AND minlat <= 42.2 AND maxlon >= -80.2 AND minlon <= -80.0;
SELECT count(*) FROM vocab_tri WHERE vocab_tri MATCH '"pit" OR "its" OR "tsb"';
SELECT count(*) FROM spell_del WHERE h IN (1, 2, 3);
SELECT count(*) FROM category_term WHERE term >= 'gas st' AND term < 'gas su';
SELECT count(*) FROM (SELECT e.id FROM entry e WHERE e.kind = 1 AND e.name_key >= 'st' AND e.name_key < 'su' ORDER BY e.importance DESC LIMIT 30);
SELECT count(*) FROM poi_prefix WHERE cell IN (5285000, 5285001) AND p3 >= 'st' AND p3 < 'su';
SELECT count(*) FROM entry_rtree r CROSS JOIN poi_cat c CROSS JOIN entry e WHERE c.entry_id = r.id AND e.id = r.id AND c.cat_id IN (1, 2, 3, 4, 5, 6, 7) AND r.maxlat >= 42.0 AND r.minlat <= 42.2 AND r.maxlon >= -80.2 AND r.minlon <= -80.0;
EXPLAIN QUERY PLAN SELECT e.id FROM entry e WHERE e.kind = 1 AND e.name_key >= 'st' AND e.name_key < 'su' ORDER BY e.importance DESC LIMIT 30;
"""


def compat(db_path: str) -> dict:
    out = {"python": sys.version.split()[0], "platform": platform.platform(),
           "python_sqlite_version": sqlite3.sqlite_version,
           "python_sqlite_compile_options": [r[0] for r in sqlite3.connect(":memory:").execute("PRAGMA compile_options")]}
    if os.path.exists(SQLITE_CLI):
        r = subprocess.run([SQLITE_CLI, "-readonly", db_path], input=COMPAT_SQL, capture_output=True, text=True)
        out["cli_3_50_1_stdout"] = r.stdout.strip().splitlines()
        out["cli_3_50_1_stderr"] = r.stderr.strip()
        out["cli_3_50_1_ok"] = r.returncode == 0 and not r.stderr.strip()
    return out


def fts_variants(db_path: str) -> dict:
    """Size and build time of the name index under different FTS5 options,
    built from the same text (entry name_key + alt_keys)."""
    db = sqlite3.connect(ro_uri(db_path), uri=True)
    rows = [(i, " ".join([k] + [a.lstrip("~") for a in (alt or "").split("|") if a]))
            for i, k, alt in db.execute("SELECT id, name_key, alt_keys FROM entry WHERE name_key != ''")]
    work = os.path.join(REPO, "data", "search", "work", "fts-variants")
    os.makedirs(work, exist_ok=True)
    res = {}
    for name, opts in [("detail_none_prefix23 (shipped)", "content='', columnsize=0, detail=none, prefix='2 3'"),
                       ("detail_none_noprefix", "content='', columnsize=0, detail=none"),
                       ("detail_column_prefix23", "content='', columnsize=0, detail=column, prefix='2 3'"),
                       ("detail_full_prefix23", "content='', columnsize=0, detail=full, prefix='2 3'"),
                       ("detail_full_noprefix_with_content", "detail=full")]:
        p = os.path.join(work, name.split()[0] + ".sqlite")
        if os.path.exists(p):
            os.remove(p)
        t0 = time.time()
        d = sqlite3.connect(p)
        d.execute(f"CREATE VIRTUAL TABLE f USING fts5(names, {opts})")
        with d:
            d.executemany("INSERT INTO f(rowid, names) VALUES (?,?)", rows)
        d.execute("INSERT INTO f(f) VALUES('optimize')")
        d.commit()
        d.execute("VACUUM")
        d.close()
        sec = time.time() - t0
        d = sqlite3.connect(p)
        lat = []
        for q in ['"st"*', '"main" AND "street"*', '"walmart"*', '"pit"*', '"peach" AND ("street" OR "st"*)']:
            t0 = time.perf_counter()
            d.execute("SELECT count(*) FROM f WHERE f MATCH ?", (q,)).fetchone()
            lat.append(round((time.perf_counter() - t0) * 1000, 2))
        d.close()
        res[name] = {"bytes": os.path.getsize(p), "build_seconds": round(sec, 1), "count_query_ms": lat}
        print(name, res[name])
    return res


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--state", default="pa")
    ap.add_argument("--only", choices=["sizes", "latency", "compat", "fts", "prefix", "house"])
    ap.add_argument("--db", help="database file (default data/search/<state>-search.sqlite)")
    ap.add_argument("--out", help="output JSON (default data/search/<state>-measurements.json)")
    args = ap.parse_args()
    db_path = args.db or os.path.join(REPO, "data", "search", f"{args.state}-search.sqlite")
    out_path = args.out or os.path.join(REPO, "data", "search", f"{args.state}-measurements.json")
    out = json.load(open(out_path)) if os.path.exists(out_path) else {}

    def cpu_load():
        try:
            import psutil
            return psutil.cpu_percent(interval=1.0)
        except Exception:
            return None
    if args.only in (None, "prefix"):
        load = cpu_load()
        out["prefix_latency"] = {"cpu_load_percent_at_start": load, "groups": prefix_latency(db_path)}
    if args.only in (None, "house"):
        out["house_latency"] = {"cpu_load_percent_at_start": cpu_load(),
                                "queries": house_latency(db_path, os.path.join(REPO, "data", "addresses",
                                                                               f"{args.state}-addresses.sqlite"))}
    if args.only in (None, "sizes"):
        out["sizes"] = sizes(db_path)
        print(json.dumps(out["sizes"].get("grouped_bytes"), indent=1))
    if args.only in (None, "compat"):
        out["compat"] = compat(db_path)
        print("compat:", out["compat"].get("cli_3_50_1_ok"), out["compat"].get("cli_3_50_1_stdout"))
    if args.only in (None, "fts"):
        out["fts_variants"] = fts_variants(db_path)
    if args.only in (None, "latency"):
        try:
            import psutil
            load = psutil.cpu_percent(interval=1.0)
        except Exception:
            load = None
        out["latency"] = {"cpu_load_percent_at_start": load,
                          "dev_and_holdout_cases": latency(db_path, ["cases.json", "cases_holdout.json"]),
                          "review_cases": latency(db_path, ["cases_review.json"])}
        print(json.dumps(out["latency"], indent=1))
    out["measured_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
    print("wrote", os.path.relpath(out_path, REPO))


if __name__ == "__main__":
    main()
