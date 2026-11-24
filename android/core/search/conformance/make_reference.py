"""Reference answers of the Python engines, for the Kotlin conformance tests.

    python -I android/core/search/conformance/make_reference.py \
        --search-db <pa-search.sqlite> --addresses-db <pa-addresses-slim.sqlite> \
        --e2e-cases <data/addresses/reports/e2e_cases.csv> [--out data/search-conformance]

Runs the code in this repository's packbuilder/ (standard library only, so any Python 3.12 works;
the builders' venvs are fine too) and writes, into --out (default: <repo>/data/search-conformance,
not in git):
  reference-search.json     packbuilder/search/query.py on every query of search_queries.json
                            (top 10 with every field, parse labels, flags) plus the test-set verdicts
                            of packbuilder/search/tests/run_tests.py's Checker (top-1, top-5, data gap);
                            and "near_street": query.py near_street() at each point of points.json
                            and at fixed steps around it (the street at a dropped pin)
  reference-addresses.json  addrlib parse + lookup (top 5) for the addresses track's end-to-end
                            variants (e2e_cases.csv: 300 + 75 + 60 typed addresses) and extra strings,
                            against the pack file and against a copy with the targets removed
  e2e-holdout.sqlite        that copy (the pack file without the target addresses), as the
                            addresses track's step_eval_parser.py makes it
  norm-search.jsonl         [name, normalize.key(name)] for every distinct entry name of the pack
  norm-addresses.jsonl      [street, street_key, street_core, key_variants] for every distinct street
                            spelling of the address file, plus place_key and house_number samples
  norm-fingerprint.json     the fingerprint (below) of the two normaliser dumps
Both reference JSON files carry a "fingerprint": the SHA-256 of the input files and of every
reference source (each packbuilder module the engines import, their JSON rule files, points.json
and search_queries.json; text hashed with LF line endings). The Kotlin tests recompute it and fail
when the reference no longer matches the code or the configured packs.
The e2e cases hold real addresses and stay out of git; they are read from the addresses track's
report folder.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import pathlib
import shutil
import sqlite3
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.path.insert(0, str(REPO / "packbuilder" / "search"))
sys.path.insert(0, str(REPO / "packbuilder" / "search" / "tests"))
sys.path.insert(0, str(REPO / "packbuilder" / "addresses"))
import normalize  # noqa: E402  (packbuilder/search/normalize.py)
import query  # noqa: E402
import run_tests  # noqa: E402
from addrlib import normalize as anorm  # noqa: E402
from addrlib.lookup import AddressIndex  # noqa: E402
from addrlib.parser import parse as aparse  # noqa: E402

EXTRA_ADDRESSES = [
    # addrlib unit tests (packbuilder/addresses/tests/test_addrlib.py)
    "500 Walnut St Apt #4B Erie PA", "500 Walnut Street Unit A-1, Erie", "500 Walnut St, 2nd Floor, Erie",
    "500 Walnut St Rear Erie PA", "626 State St Fl 3 Rm 4 Erie", "123 Main St Lower Burrell PA", "100 Hill Side Rd",
    "5040 Trail Side Court 15644", "State St 626 Erie", "Route 8 4500 Gibsonia", "Erie PA 16501", "16501",
    "626 State St Erie PA USA", "626 State St, Erie, PA 16501, United States", "123 main st erie pa 1650",
    "123 A Main St", "123 A St Erie", "123 N Main St", "123 n main st apt 4, erie pa 16501",
    "123 N Main St Apt 4 Erie PA 16501", "123 Main St", "erie pa 16501, 123 main st", "Erie, PA 16501 123 Main St",
    "123 Main St Spring City PA", "4500 Route 8 Gibsonia PA 15044", "10450 US-19 Wexford, PA",
    "1600 W 26th St #4 Erie PA 16508-1234", "123 Main St Rear, Erie", "123 1/2 Elm St", "main st 123",
    "123 St Clair Ave Erie", "12345 Main St", "100 Park Ridge Rd State College PA",
    # the addresses track's spot checks (step_variants.py) and contract examples
    "626 State St Erie PA", "414 Grant St Pittsburgh PA", "501 N 3rd St Harrisburg PA", "1400 JFK Blvd Philadelphia PA",
    "243 S Allen St State College PA", "340 N Washington Ave Scranton PA", "1200 E Pittsburgh St Greensburg PA",
    "150 Main St Brookville PA", "1 Saint Marys St Erie", "626 State St Erie OH",
    "99999999999999999999 Main St Erie PA", "-5 State St Erie", "123 n mian st, erie pa 16501",
    "123 n main sreet, erie pa 16501", "West 8 St Erie", "10 w 8 st erie", "4319 Peach St", "4319 peach st erie",
    "4319 Peach Street, Erie, PA 16509", "1825 Downs Dr Erie PA", "2711 Elm St Erie", "Peach St Erie",
    "I-79 Erie", "US 19 Erie", "1 Interstate 79", "Twenty-First St Erie", "21 st Street Erie", "100 Hill St Exn",
    # hostile and odd input
    "", " ", ",", ",,,", "#", "# 4", "apt 4", "123", "pa", "erie", "usa", "123 usa", "½ Main St", "123½ Main St",
    "１２３ Main St", "123 Mäin Straße Erie", "123 main st\nerie pa", "123 main st;erie", "123 - 125 main st",
    "123-125 Main St", "123-A Main St", "0 main st", "000 main st erie", "1234567890123456789012345 main st",
    "a" * 300, "123 " + "main " * 100 + "st", "x'; DROP TABLE streets; --", "\x00", "Москва 1",
]


# The street at a point is asked at each named point and at these steps from it (metres north, metres east).
NEAR_STEPS = [(0, 0), (40, 0), (0, 40), (-25, -25), (-120, 90), (300, -200), (1000, 700), (-2500, 1500)]

# Reference sources that are not Python modules (the modules are found in sys.modules).
DATA_SOURCES = ["packbuilder/search/ranking.json", "packbuilder/search/abbreviations.json",
                "packbuilder/search/tests/points.json", "packbuilder/addresses/addrlib/us_street_norm.json",
                "packbuilder/addresses/addrlib/lookup_config.json", "android/core/search/conformance/search_queries.json"]


def lf_sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def file_sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def fingerprint(args) -> dict:
    """SHA-256 of the inputs and of every reference source, for the Kotlin tests' freshness check."""
    pb = (REPO / "packbuilder").resolve()
    mods = set()
    for m in list(sys.modules.values()):
        f = getattr(m, "__file__", None)
        if f and pathlib.Path(f).resolve().is_relative_to(pb):
            mods.add(pathlib.Path(f).resolve().relative_to(REPO.resolve()).as_posix())
    code = {p: lf_sha256(REPO / p) for p in sorted(mods | set(DATA_SOURCES))}
    inputs = {}
    for role, path in (("search_db", args.search_db), ("addresses_db", args.addresses_db), ("e2e_cases", args.e2e_cases)):
        p = pathlib.Path(path)
        inputs[role] = {"name": p.name, "bytes": p.stat().st_size, "sha256": file_sha256(p)}
    return {"code": code, "inputs": inputs}


def pos(v):
    if v is None:
        return None
    if isinstance(v, str):
        return {"nan": float("nan"), "inf": float("inf"), "-inf": float("-inf")}[v]
    return float(v)


def search_reference(args, out_dir, fp):
    qdoc = json.loads((HERE / "search_queries.json").read_text(encoding="utf-8"))
    points = json.loads((REPO / "packbuilder" / "search" / "tests" / "points.json").read_text(encoding="utf-8"))["points"]
    eng = query.SearchEngine(args.search_db, addresses_path=args.addresses_db)
    chk = run_tests.Checker(eng)
    res_all = []
    t_start = time.perf_counter()
    for q in qdoc["queries"]:
        if "at" in q:
            lat, lon = points[q["at"]]["lat"], points[q["at"]]["lon"]
        elif "near" in q:
            lat, lon = pos(q["near"][0]), pos(q["near"][1])
        else:
            lat = lon = None
        r = eng.search(q["q"], lat, lon, limit=10)
        rec = {"id": q["id"], "ms": r["ms"], "out_of_coverage": r["out_of_coverage"],
               "position_ignored": r["position_ignored"], "parse": r["parse"],
               "results": [{k: x.get(k) for k in ("id", "kind", "title", "subtitle", "category", "type", "lat", "lon",
                                                   "distance_km", "brand_id", "score", "how", "tier", "house_number",
                                                   "street_name", "house_located")} for x in r["results"]]}
        if "expect" in q:
            exp = q["expect"]
            anchor = (lat, lon) if lat is not None else None
            if "of" in exp:
                anchor = (points[exp["of"]]["lat"], points[exp["of"]]["lon"])
            elif "of_point" in exp:
                anchor = tuple(exp["of_point"])
            hits = [chk.match(x, exp, anchor) for x in r["results"][:5]]
            top1 = bool(hits[:1] and hits[0])
            top5 = any(hits)
            rec["verdict"] = {"top1": top1, "top5": top5, "gap": (not top5) and not chk.exists(exp, anchor)}
        res_all.append(rec)
    wall = time.perf_counter() - t_start
    # the street at a point: each named point and fixed steps around it (metres north, metres east)
    near = []
    for name in sorted(points):
        for north, east in NEAR_STEPS:
            lat = round(points[name]["lat"] + north / 111_320.0, 6)
            lon = round(points[name]["lon"] + east / (111_320.0 * math.cos(math.radians(points[name]["lat"]))), 6)
            near.append({"lat": lat, "lon": lon, "street": eng.near_street(lat, lon)})
    doc = {"engine": "packbuilder/search/query.py", "python": sys.version.split()[0],
           "sqlite": sqlite3.sqlite_version, "search_db": pathlib.Path(args.search_db).name,
           "addresses_db": pathlib.Path(args.addresses_db).name, "addr_ok": eng.addr_ok,
           "wall_s": round(wall, 2), "count": len(res_all), "fingerprint": fp, "results": res_all,
           "street_lines": eng.has_lines, "near_street": near}
    (out_dir / "reference-search.json").write_text(json.dumps(doc, indent=0, allow_nan=True), encoding="utf-8")
    sets = {}
    for q, rec in zip(qdoc["queries"], res_all):
        if "verdict" in rec:
            s = sets.setdefault(q["set"], {"n": 0, "top1": 0, "top5": 0, "gap": 0})
            s["n"] += 1
            for k in ("top1", "top5", "gap"):
                s[k] += rec["verdict"][k]
    print(f"search: {len(res_all)} queries in {wall:.1f} s; test sets (Python): {sets}")


def addr_result(x):
    keep = ("lat", "lon", "accuracy", "source", "house_number", "street", "street_key", "zip", "city", "municipality",
            "county_fips", "match", "score", "locality_match", "state_mismatch", "house_number_rejected", "unit",
            "address_id", "base_number_of", "edge_id", "side", "hn_gap")
    return {k: x.get(k) for k in keep if k in x}


def make_holdout(src, dst, cases):
    """Copy of the pack without the target addresses (same hn, same street_core, same county),
    as packbuilder/addresses/step_eval_parser.py make_holdout() does."""
    if dst.exists():
        dst.unlink()
    shutil.copyfile(src, dst)
    con = sqlite3.connect(dst)
    n = 0
    for c in cases:
        n += con.execute("""DELETE FROM addresses WHERE id IN (
                              SELECT a.id FROM addresses a JOIN streets s ON s.id = a.street_id
                              JOIN localities l ON l.id = s.locality_id
                              WHERE a.hn = ? AND s.street_core = ? AND l.county_fips = ?)""",
                         (c["true_hn"], anorm.street_core(c["true_key"]), c["county_fips"])).rowcount
    con.commit()
    con.close()
    return n


def address_reference(args, out_dir, fp):
    with open(args.e2e_cases, encoding="utf-8", newline="") as f:
        cases = list(csv.DictReader(f))
    texts = [("e2e:" + c["kind"], c["text"]) for c in cases] + [("extra", t) for t in EXTRA_ADDRESSES]
    hold = out_dir / "e2e-holdout.sqlite"
    n_del = make_holdout(pathlib.Path(args.addresses_db), hold, cases)
    doc = {"engine": "packbuilder/addresses/addrlib", "python": sys.version.split()[0], "sqlite": sqlite3.sqlite_version,
           "addresses_db": pathlib.Path(args.addresses_db).name, "holdout_rows_deleted": n_del, "fingerprint": fp,
           "runs": {}}
    for run_name, path in (("pack", args.addresses_db), ("holdout", str(hold))):
        idx = AddressIndex(path)
        recs = []
        t0 = time.perf_counter()
        for kind, text in texts:
            p = aparse(text, idx.known_places)
            r = idx.resolve(p)
            recs.append({"kind": kind, "text": text, "parse": {k: v for k, v in p.items() if k != "input"},
                         "results": [addr_result(x) for x in r]})
        doc["runs"][run_name] = {"wall_s": round(time.perf_counter() - t0, 2), "cases": recs}
        idx.con.close()
        ok = sum(1 for (kind, _), rec, c in zip(texts, recs, cases + [None] * len(EXTRA_ADDRESSES))
                 if c and rec["results"] and _dist_m(rec["results"][0], c) <= 100)
        print(f"addresses/{run_name}: {len(recs)} strings; e2e top-1 within 100 m (Python): {ok}/{len(cases)}")
    (out_dir / "reference-addresses.json").write_text(json.dumps(doc, indent=0), encoding="utf-8")


def _dist_m(x, c):
    from addrlib.geo import haversine_m
    return haversine_m(float(c["lon"]), float(c["lat"]), x["lon"], x["lat"])


def norm_reference(args, out_dir, fp):
    db = sqlite3.connect(pathlib.Path(args.search_db).resolve().as_uri() + "?mode=ro", uri=True)
    n = 0
    with open(out_dir / "norm-search.jsonl", "w", encoding="utf-8") as f:
        for (name,) in db.execute("SELECT DISTINCT name FROM entry"):
            f.write(json.dumps([name, normalize.key(name)], ensure_ascii=True) + "\n")
            n += 1
    db.close()
    adb = sqlite3.connect(pathlib.Path(args.addresses_db).resolve().as_uri() + "?mode=ro", uri=True)
    m = 0
    with open(out_dir / "norm-addresses.jsonl", "w", encoding="utf-8") as f:
        for (s,) in adb.execute("SELECT DISTINCT street FROM streets"):
            k = anorm.street_key(s)
            f.write(json.dumps(["street", s, k, anorm.street_core(k), anorm.key_variants(k)], ensure_ascii=True) + "\n")
            m += 1
        for (c,) in adb.execute("SELECT DISTINCT city FROM localities WHERE city IS NOT NULL UNION "
                                "SELECT DISTINCT municipality FROM localities WHERE municipality IS NOT NULL UNION "
                                "SELECT DISTINCT name FROM places"):
            f.write(json.dumps(["place", c, anorm.place_key(c)], ensure_ascii=True) + "\n")
            m += 1
        for (h,) in adb.execute("SELECT DISTINCT hn FROM addresses"):
            hn = anorm.house_number(h)
            f.write(json.dumps(["hn", h, hn[0], None if hn[1] is None else str(hn[1])], ensure_ascii=True) + "\n")
            m += 1
    adb.close()
    (out_dir / "norm-fingerprint.json").write_text(json.dumps(fp, indent=1), encoding="utf-8")
    print(f"normaliser dumps: {n} search names, {m} address strings")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--search-db", required=True)
    ap.add_argument("--addresses-db", required=True)
    ap.add_argument("--e2e-cases", required=True)
    ap.add_argument("--out", default=str(REPO / "data" / "search-conformance"))
    ap.add_argument("--only", choices=["search", "addresses", "norm"])
    args = ap.parse_args()
    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    t0 = time.perf_counter()
    fp = fingerprint(args)
    print(f"fingerprint: {len(fp['code'])} reference sources, 3 inputs hashed in {time.perf_counter() - t0:.1f} s")
    if args.only in (None, "search"):
        search_reference(args, out_dir, fp)
    if args.only in (None, "addresses"):
        address_reference(args, out_dir, fp)
    if args.only in (None, "norm"):
        norm_reference(args, out_dir, fp)


if __name__ == "__main__":
    main()
