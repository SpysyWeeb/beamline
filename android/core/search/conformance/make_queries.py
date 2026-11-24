"""Build the search conformance query list (search_queries.json, committed next to this file).

    python android/core/search/conformance/make_queries.py --db <pa-search.sqlite>

Every query of the three test sets of packbuilder/search/tests (tuned, held-out, review) plus a
seeded sample of extra queries that the test sets do not cover well:
  prefix1..prefix4  the first 1-4 letters of common place, brand and street names (as typed:
                    no trailing space), at five bias points
  typing            a whole word typed letter by letter, then with a trailing space
  brand_punct       brand names as written in the data (apostrophes, '&', '-', '.') and variants
  number            numbers alone and in short phrases (ZIPs, routes, house numbers)
  address           house number + street (+ town)
  word              single words drawn from the vocabulary by frequency, and word pairs
  hostile           FTS5 syntax, quotes, SQL, control characters, non-Latin scripts, long input
  position          ordinary queries with missing, non-finite, out-of-range or far-away positions
  cap               texts longer than ranking.json max_query_chars (rule S5), cut by both engines
Standard library only; reads the database read-only. Deterministic (seed 20261115).
"""
from __future__ import annotations

import argparse
import json
import pathlib
import random
import sqlite3

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[3]
TESTS = REPO / "packbuilder" / "search" / "tests"
OUT = HERE / "search_queries.json"
BIAS = ["erie", "pittsburgh", "philadelphia", "harrisburg", "state_college", "altoona", "scranton"]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--seed", type=int, default=20261115)
    args = ap.parse_args()
    rng = random.Random(args.seed)
    db = sqlite3.connect(pathlib.Path(args.db).resolve().as_uri() + "?mode=ro", uri=True)
    out = []

    # ---- the three test sets, as they are
    for set_name, fname in (("tuned", "cases.json"), ("holdout", "cases_holdout.json"), ("review", "cases_review.json")):
        for c in json.loads((TESTS / fname).read_text(encoding="utf-8"))["cases"]:
            q = {"id": c["id"], "set": set_name, "type": c["type"], "q": c["q"]}
            if "at" in c:
                q["at"] = c["at"]
            elif "near" in c:
                q["near"] = c["near"]
            q["expect"] = c["expect"]
            out.append(q)

    extra = []

    def add(typ, text, at=None, near=None):
        q = {"id": f"x-{typ}-{len(extra):04d}", "set": "extra", "type": typ, "q": text}
        if near is not None:
            q["near"] = near
        elif at is not None:
            q["at"] = at
        extra.append(q)

    # ---- common names: places by importance, brands by POI count, streets by number of entries
    places = [r[0] for r in db.execute(
        "SELECT name FROM entry WHERE kind = 1 AND type IN ('city', 'borough', 'town', 'township') "
        "ORDER BY importance DESC LIMIT 60")]
    brands = [r[0] for r in db.execute("SELECT name FROM brand ORDER BY poi_count DESC LIMIT 80")]
    streets = [r[0] for r in db.execute(
        "SELECT name FROM entry WHERE kind = 2 GROUP BY name_key ORDER BY COUNT(*) DESC LIMIT 60")]
    names = rng.sample(places, 12) + rng.sample(brands, 14) + rng.sample(streets, 10)
    for nm in names:
        letters = "".join(ch for ch in nm.lower() if ch.isalnum())
        at = rng.choice(BIAS)
        for k in (1, 2, 3, 4):
            if len(letters) >= k:
                add(f"prefix{k}", nm[:k] if nm[:k].isalnum() else letters[:k], at=at)

    # ---- typing a word letter by letter
    for word in ("sheetz", "walmart", "pittsburgh", "rest stop", "4319 peach", "erie city hall"):
        at = rng.choice(BIAS)
        for k in range(1, len(word) + 1):
            add("typing", word[:k], at=at)
        add("typing", word + " ", at=at)

    # ---- brand names with punctuation, as the data writes them
    punct = [b for b in brands if any(ch in b for ch in "'’&-.!/+")]
    for b in punct[:30]:
        at = rng.choice(BIAS)
        add("brand_punct", b, at=at)
        add("brand_punct", b.lower().replace("'", "").replace("’", ""), at=at)
    for b in ("McDonald's", "Dunkin'", "Dunkin' Donuts", "7-Eleven", "7 eleven", "Love's", "Lowe's", "Kohl's",
              "Arby's", "Wendy's", "Dick's Sporting Goods", "Sam's Club", "BJ's", "Ollie's", "Rita's",
              "Trader Joe's", "AT&T", "Barnes & Noble", "Bed Bath & Beyond", "CVS/pharmacy", "Rite-Aid",
              "Chick-fil-A", "Wal-Mart", "Wal*Mart", "Home Depot.", "Sheetz!", "Giant-Eagle", "T.J. Maxx",
              "Kwik-Fill", "Sunoco A-Plus"):
        add("brand_punct", b, at=rng.choice(BIAS))

    # ---- numbers
    for text in ("1", "7", "12", "84", "99", "123", "2026", "4319", "16509", "16501", "15213", "19103", "17101",
                 "165", "1650", "165091", "0", "007", "1st", "3rd", "21st street", "i 80", "i-79", "route 6",
                 "us 19", "pa 8", "sr 3017", "exit 24", "1600 market", "100", "8 mile", "84 lumber",
                 "99 cents", "7 brew", "24 hour", "2 guys"):
        add("number", text, at=rng.choice(BIAS))

    # ---- addresses
    for text, at in (("4319 peach st", "erie"), ("4319 peach street erie pa", "pittsburgh"),
                     ("100 state st erie", "erie"), ("123 main st", "harrisburg"), ("1 main st", "state_college"),
                     ("600 grant st pittsburgh", "pittsburgh"), ("1600 market st philadelphia", "philadelphia"),
                     ("200 e college ave", "state_college"), ("72 s main st wilkes barre", "scranton"),
                     ("1825 downs dr", "erie"), ("2711 elm st erie", "erie"), ("5 n 2nd st", "harrisburg"),
                     ("10 w 8th st", "erie"), ("1400 jfk blvd", "philadelphia"), ("99999 main st", "erie"),
                     ("4319 peach", "erie"), ("4319", "erie"), ("626 state st erie oh", "erie"),
                     ("150 main st brookville", "pittsburgh"), ("1200 e pittsburgh st greensburg", "pittsburgh"),
                     # next run, part 1: exact addresses first (an exact address once ranked below a
                     # place or an estimated address with a similar name)
                     ("4319 peach st", "erie_peach_upper"), ("247 main st conneaut", "erie"),
                     ("247 Main Street, Conneaut", "erie")):
        add("address", text, at=at)

    # ---- single words from the vocabulary (by frequency) and pairs
    vocab = db.execute("SELECT word, freq FROM vocab WHERE freq >= 2").fetchall()
    words, weights = [w for w, _ in vocab], [f for _, f in vocab]
    picked = rng.choices(words, weights=weights, k=50) + rng.sample(words, 30)
    for w in picked:
        add("word", w, at=rng.choice(BIAS))
    for _ in range(25):
        a, b = rng.choices(words, weights=weights, k=2)
        add("word", f"{a} {b}", at=rng.choice(BIAS))

    # ---- hostile strings (packbuilder/search/tests/check_inputs.py plus more)
    hostile = ['"', "'", '"walmart', "wal*", "*", "(", ")", "NEAR(a b)", "a AND OR b", "names: walmart",
               "x'; DROP TABLE entry; --", "\x00", "walmart\x00erie", "\U0001F642 gas", "東京", "Москва", "a" * 500,
               "gas " * 300, "16801", "-", "&", "7-11", "%", "_", "\\", "^walmart", "{names}: pizza",
               "", " ", "   ", "\t", "\n", "AND", "OR", "NOT", "NEAR", "pizza OR", "OR pizza", "\"\"\"\"",
               "''", "café", "Ñandú", "ﬁsh", "Ｗａｌｍａｒｔ", "① main st", "İstanbul", "straße",
               "érie", "erïe", "wal​mart", "wal­mart", "﻿gas", "sheetz️",
               "near", "near me", "in", "on", "at", "pa", "erie pa pa", "near near", "in in erie",
               "gas near", "gas in", "gas on", "on peach st", "the", "the the the", "and", "of the"]
    for text in hostile:
        add("hostile", text, at=rng.choice(BIAS))

    # ---- positions
    special = [("nan", 0.0), (0.0, "-inf"), ("inf", "inf"), (95.0, 10.0), (40.0, 200.0), (None, -80.0),
               (40.0, None), (51.5074, -0.1278), (0.0, 0.0), (41.4993, -81.6944), (41.0998, -80.6495),
               (39.9612, -82.9988), (-90.0, 180.0), (90.0, -180.0)]
    for text in ("gas", "rest stop", "walmart", "erie", "pizza"):
        for lat, lon in special:
            add("position", text, near=[lat, lon])
        add("position", text)            # no position at all

    # ---- the input cap (rule S5: ranking.json search.max_query_chars, 256 code points, cut by
    # both engines): the cut inside a word, right after and right inside an emoji written as a
    # surrogate pair, after a base letter whose combining mark is cut off, and a house number with
    # a long tail. Added after every random draw, so the queries above are unchanged.
    cap = ["sheetz " + "z" * 300, "z" * 255 + "🙂" + "gas", "z" * 254 + "🙂" + "gas",
           "gas " * 63 + "pizza", "4319 peach st " + "x" * 300, "é" * 200, "🙂" * 300]
    for text in cap:
        add("cap", text, at="erie")

    out += extra
    doc = {"_doc": "Search conformance queries (generated by make_queries.py; seed %d). 'at' names a point in "
                   "packbuilder/search/tests/points.json; 'near' is [lat, lon] where a coordinate may be null or "
                   "the strings 'nan', 'inf', '-inf'. Test-set cases keep their 'expect'." % args.seed,
           "count": len(out), "queries": out}
    OUT.write_text(json.dumps(doc, indent=1, ensure_ascii=True) + "\n", encoding="utf-8")
    by = {}
    for q in out:
        k = q["set"] if q["set"] != "extra" else "extra:" + q["type"]
        by[k] = by.get(k, 0) + 1
    print(f"wrote {OUT} with {len(out)} queries: {by}")


if __name__ == "__main__":
    main()
