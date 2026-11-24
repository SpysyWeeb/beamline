"""Python twin of SqlTimingProbe.kt: the same SQL and engine queries timed with Python's sqlite3.

    python -I android/core/search/conformance/sql_probe.py --db <pa-search.sqlite> [--addresses <pa-addresses-slim.sqlite>]
"""
from __future__ import annotations

import argparse
import math
import pathlib
import sqlite3
import statistics
import sys
import time

REPO = pathlib.Path(__file__).resolve().parents[4]
sys.path.insert(0, str(REPO / "packbuilder" / "search"))
import query  # noqa: E402

COLS = ("e.id, e.kind, e.name, e.name_key, e.alt_keys, e.type, e.lat, e.lon, e.importance, e.muni_id, e.county_id, "
        "e.brand_id, e.subtitle")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--addresses", help="address file to attach for the house-number timings")
    args = ap.parse_args()
    lat, lon = 39.9526, -75.1652
    k = math.cos(math.radians(lat)) ** 2
    db = sqlite3.connect(query.ro_uri(args.db), uri=True)
    sql_dist = (f"SELECT {COLS} FROM entry_fts f JOIN entry e ON e.id = f.rowid WHERE entry_fts MATCH ? "
                "ORDER BY (e.lat - ?) * (e.lat - ?) + (e.lon - ?) * (e.lon - ?) * ? LIMIT ?")
    sql_count = "SELECT count(*) FROM entry_fts f JOIN entry e ON e.id = f.rowid WHERE entry_fts MATCH ?"
    print("sqlite", sqlite3.sqlite_version)
    for word in ('"road"*', '"street"*', '"walmart"*'):
        for _ in range(2):
            db.execute(sql_dist, (word, lat, lat, lon, lon, k, 400)).fetchall()
        t = []
        for _ in range(5):
            t0 = time.perf_counter()
            db.execute(sql_dist, (word, lat, lat, lon, lon, k, 400)).fetchall()
            t.append((time.perf_counter() - t0) * 1000)
        tc = []
        for _ in range(5):
            t0 = time.perf_counter()
            db.execute(sql_count, (word,)).fetchall()
            tc.append((time.perf_counter() - t0) * 1000)
        n = db.execute(sql_count, (word,)).fetchone()[0]
        print(f"PROBE {word} matches={n}  dist-sorted top 400: {statistics.median(t):.2f} ms   count only: {statistics.median(tc):.2f} ms")
    if args.addresses:
        eng = query.SearchEngine(args.db, addresses_path=args.addresses)
        points = {"erie": (42.1292, -80.0851), "pittsburgh": (40.4406, -79.9959), "wilkes_barre": (41.2459, -75.8813)}
        for q, pt in (("1 main st", "erie"), ("100 main st", "erie"), ("500 church st", "erie"), ("123 n main st", "erie"),
                      ("4319 peach st", "erie"), ("1600 market st philadelphia", "pittsburgh"),
                      ("600 grant street pittsburgh", "erie"), ("72 s main st wilkes barre", "wilkes_barre")):
            la, lo = points[pt]
            eng.search(q, la, lo)
            t = []
            for _ in range(5):
                t0 = time.perf_counter()
                eng.search(q, la, lo)
                t.append((time.perf_counter() - t0) * 1000)
            print(f"PROBE house '{q}' (slim attached) median {statistics.median(t):.2f} ms")
    eng = query.SearchEngine(args.db)
    for q in ("road", "street", "walmart", "gas", "sheetz"):
        for _ in range(2):
            eng.search(q, lat, lon)
        t = []
        for _ in range(5):
            t0 = time.perf_counter()
            eng.search(q, lat, lon)
            t.append((time.perf_counter() - t0) * 1000)
        print(f"PROBE engine '{q}' @philadelphia median {statistics.median(t):.2f} ms")


if __name__ == "__main__":
    main()
