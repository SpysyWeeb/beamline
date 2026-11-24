"""Compare three typo-tolerance methods on the vocabulary of a built index:

  symspell  deletion table (spell_del) in plain SQLite, as used by query.py
  trigram   FTS5 'trigram' tokenizer over the vocabulary (built into SQLite >= 3.34)
  spellfix1 SQLite's spellfix1 virtual table (a loadable extension, not built in)

Stdlib only, so the same script runs under Windows Python and under Linux
python3 (WSL), which is where spellfix1 can be compiled and loaded.

    # 1. export the vocabulary and generate the typo set (Windows, from repo root)
    python packbuilder/search/fuzzy_eval.py export --db data/search/pa-search.sqlite --out data/search/fuzzy
    # 2. run the comparison (any machine); --spellfix needs a compiled spellfix1 library
    python packbuilder/search/fuzzy_eval.py run --dir data/search/fuzzy --work <scratch dir> [--spellfix ./spellfix.so]

Measures per method: index size on disk, build seconds, recall@1 / recall@5
on (a) hand-collected real typos and (b) synthetic single-edit typos of
vocabulary words, and lookup latency (median / p95).
"""
from __future__ import annotations

import argparse
import json
import os
import random
import sqlite3
import statistics
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))

REAL_TYPOS = [  # (typed, intended) - collected from the test set and common US misspellings
    ("wallmart", "walmart"), ("walmrt", "walmart"), ("mcdonlads", "mcdonalds"), ("mcdonals", "mcdonalds"),
    ("pitsburgh", "pittsburgh"), ("pittsbrgh", "pittsburgh"), ("philadelpia", "philadelphia"),
    ("harrisburgh", "harrisburg"), ("harisburg", "harrisburg"), ("scranten", "scranton"),
    ("starbuks", "starbucks"), ("starbcks", "starbucks"), ("resturant", "restaurant"),
    ("restraunt", "restaurant"), ("pharmacey", "pharmacy"), ("hospitl", "hospital"),
    ("allentwon", "allentown"), ("alentown", "allentown"), ("lancastor", "lancaster"),
    ("eagel", "eagle"), ("colege", "college"), ("gettysburgh", "gettysburg"), ("peech", "peach"),
    ("staton", "station"), ("barell", "barrel"), ("eire", "erie"), ("chipotel", "chipotle"),
    ("gass", "gas"), ("hotle", "hotel"), ("sheets", "sheetz"), ("walgreen", "walgreens"),
    ("dunkn", "dunkin"), ("targett", "target"), ("wawwa", "wawa"), ("cemetary", "cemetery"),
    ("libary", "library"), ("goverment", "government"), ("pensylvania", "pennsylvania"),
    ("wilksbarre", "wilkesbarre"), ("altona", "altoona"), ("williamsprt", "williamsport"),
    ("hersey", "hershey"), ("lebannon", "lebanon"), ("johnstwon", "johnstown"), ("readng", "reading"),
    ("bethleham", "bethlehem"), ("pottsvile", "pottsville"), ("meadvile", "meadville"),
    ("monroevile", "monroeville"), ("kenywood", "kennywood"),
]


def osa(a: str, b: str) -> int:
    la, lb = len(a), len(b)
    if abs(la - lb) > 3:
        return 9
    prev2, prev = None, list(range(lb + 1))
    for i in range(1, la + 1):
        cur = [i] + [0] * lb
        for j in range(1, lb + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            if i > 1 and j > 1 and a[i - 1] == b[j - 2] and a[i - 2] == b[j - 1]:
                cur[j] = min(cur[j], prev2[j - 2] + 1)
        prev2, prev = prev, cur
    return prev[lb]


def deletes(w: str, maxd: int) -> set[str]:
    out, frontier = {w}, {w}
    for _ in range(maxd):
        nxt = set()
        for x in frontier:
            for i in range(len(x)):
                nxt.add(x[:i] + x[i + 1:])
        out |= nxt
        frontier = nxt
    return out


def make_typo(w: str, rng: random.Random) -> str:
    letters = "abcdefghijklmnopqrstuvwxyz"
    op = rng.choice(["del", "ins", "sub", "swap"])
    i = rng.randrange(len(w))
    if op == "del":
        return w[:i] + w[i + 1:]
    if op == "ins":
        return w[:i] + rng.choice(letters) + w[i:]
    if op == "sub":
        return w[:i] + rng.choice([c for c in letters if c != w[i]]) + w[i + 1:]
    if i == len(w) - 1:
        i -= 1
    return w[:i] + w[i + 1] + w[i] + w[i + 2:]


def cmd_export(args) -> None:
    os.makedirs(args.out, exist_ok=True)
    db = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    vocab = db.execute("SELECT word, freq FROM vocab").fetchall()
    with open(os.path.join(args.out, "vocab.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for w, c in vocab:
            f.write(f"{w}\t{c}\n")
    words = {w for w, _ in vocab}
    rng = random.Random(20261115)
    # synthetic: frequency-weighted sample of alphabetic words with >= 5 letters
    pool = [(w, c) for w, c in vocab if len(w) >= 5 and w.isalpha() and c >= 3]
    population = [w for w, _ in pool]
    weights = [c for _, c in pool]
    synth = []
    seen = set()
    while len(synth) < args.n:
        w = rng.choices(population, weights=weights)[0]
        t = make_typo(w, rng)
        if t in words or (t, w) in seen or len(t) < 4:
            continue           # skip real-word errors; they are a separate problem
        seen.add((t, w))
        synth.append((t, w))
    # double edits on long words (>= 7 letters): shows what distance 2 buys
    pool2 = [(w, c) for w, c in pool if len(w) >= 7]
    synth2 = []
    while len(synth2) < args.n // 4:
        w = rng.choices([w for w, _ in pool2], weights=[c for _, c in pool2])[0]
        t = make_typo(make_typo(w, rng), rng)
        if t in words or (t, w) in seen or osa(t, w) != 2:
            continue
        seen.add((t, w))
        synth2.append((t, w))
    real = [(t, w) for t, w in REAL_TYPOS if w in words]
    missing = [w for _, w in REAL_TYPOS if w not in words]
    with open(os.path.join(args.out, "typos.json"), "w", encoding="utf-8") as f:
        json.dump({"real": real, "real_missing_target": missing, "synthetic": synth, "synthetic_double": synth2,
                   "seed": 20261115}, f, indent=0)
    print(f"vocab {len(vocab)} words; real typos {len(real)} (targets missing from vocab: {missing}); "
          f"synthetic {len(synth)}, double-edit {len(synth2)}")


def build_symspell(db, vocab, long_from=7):
    db.execute("CREATE TABLE vocab(id INTEGER PRIMARY KEY, word TEXT NOT NULL, freq INTEGER NOT NULL)")
    db.execute("CREATE TABLE spell_del(del TEXT, word_id INTEGER, PRIMARY KEY(del, word_id)) WITHOUT ROWID")
    rows, drows = [], []
    for i, (w, c) in enumerate(vocab, 1):
        rows.append((i, w, c))
        if len(w) >= 4 and not any(ch.isdigit() for ch in w):
            for d in deletes(w, 2 if len(w) >= long_from else 1):
                drows.append((d, i))
    db.executemany("INSERT INTO vocab VALUES (?,?,?)", rows)
    drows.sort()
    db.executemany("INSERT OR IGNORE INTO spell_del VALUES (?,?)", drows)


def lookup_symspell(db, tok, long_from=7):
    maxd = 2 if len(tok) >= long_from else 1
    dels = list(deletes(tok, maxd))
    q = ("SELECT DISTINCT v.word, v.freq FROM spell_del d JOIN vocab v ON v.id = d.word_id "
         f"WHERE d.del IN ({','.join('?' * len(dels))})")
    out = [(w, osa(tok, w), f) for w, f in db.execute(q, dels)]
    out = [x for x in out if x[1] <= maxd]
    out.sort(key=lambda x: (x[1], -x[2]))
    return [w for w, _, _ in out[:5]]


def fnv1a32(s: str) -> int:
    """FNV-1a 32-bit over UTF-8, as a signed 32-bit int (easy to port to Kotlin)."""
    h = 0x811C9DC5
    for b in s.encode("utf-8"):
        h ^= b
        h = (h * 0x01000193) & 0xFFFFFFFF
    return h - (1 << 32) if h >= (1 << 31) else h


def build_symspell_hash(db, vocab, long_from=7, min_freq=1):
    """Same as build_symspell, but the deletion strings are stored as 32-bit
    FNV-1a hashes (collisions only add candidates; OSA distance filters them)."""
    db.execute("CREATE TABLE vocab(id INTEGER PRIMARY KEY, word TEXT NOT NULL, freq INTEGER NOT NULL)")
    db.execute("CREATE TABLE spell_del(h INTEGER, word_id INTEGER, PRIMARY KEY(h, word_id)) WITHOUT ROWID")
    rows, drows = [], []
    for i, (w, c) in enumerate(vocab, 1):
        rows.append((i, w, c))
        if len(w) >= 4 and c >= min_freq and not any(ch.isdigit() for ch in w):
            for d in deletes(w, 2 if len(w) >= long_from else 1):
                drows.append((fnv1a32(d), i))
    db.executemany("INSERT INTO vocab VALUES (?,?,?)", rows)
    drows = sorted(set(drows))
    db.executemany("INSERT INTO spell_del VALUES (?,?)", drows)


def lookup_symspell_hash(db, tok, long_from=7):
    maxd = 2 if len(tok) >= long_from else 1
    hs = list({fnv1a32(d) for d in deletes(tok, maxd)})
    q = ("SELECT DISTINCT v.word, v.freq FROM spell_del d JOIN vocab v ON v.id = d.word_id "
         f"WHERE d.h IN ({','.join('?' * len(hs))})")
    out = [(w, osa(tok, w), f) for w, f in db.execute(q, hs)]
    out = [x for x in out if x[1] <= maxd]
    out.sort(key=lambda x: (x[1], -x[2]))
    return [w for w, _, _ in out[:5]]


def build_trigram(db, vocab, detail):
    db.execute("CREATE TABLE vocab(id INTEGER PRIMARY KEY, word TEXT NOT NULL, freq INTEGER NOT NULL)")
    db.execute(f"CREATE VIRTUAL TABLE vocab_tri USING fts5(word, tokenize='trigram', detail={detail}, content='')")
    db.executemany("INSERT INTO vocab VALUES (?,?,?)", ((i, w, c) for i, (w, c) in enumerate(vocab, 1)))
    db.executemany("INSERT INTO vocab_tri(rowid, word) VALUES (?,?)", ((i, w) for i, (w, c) in enumerate(vocab, 1)))
    db.execute("INSERT INTO vocab_tri(vocab_tri) VALUES('optimize')")


def lookup_trigram(db, tok, limit=60):
    grams = sorted({tok[i:i + 3] for i in range(len(tok) - 2)})
    if not grams:
        return []
    match = " OR ".join('"' + g.replace('"', '""') + '"' for g in grams)
    # candidates sharing the most trigrams (bm25 ranks rarer shared trigrams higher)
    rows = db.execute("SELECT v.word, v.freq FROM vocab_tri t JOIN vocab v ON v.id = t.rowid "
                      "WHERE vocab_tri MATCH ? ORDER BY rank LIMIT ?", (match, limit)).fetchall()
    out = [(w, osa(tok, w), f) for w, f in rows]
    out = [x for x in out if x[1] <= 2]
    out.sort(key=lambda x: (x[1], -x[2]))
    return [w for w, _, _ in out[:5]]


def build_hybrid(db, vocab):
    """SymSpell (hashed, distance 1) + FTS5 trigram fallback for long words."""
    build_symspell_hash(db, vocab, 99)
    db.execute("CREATE VIRTUAL TABLE vocab_tri USING fts5(word, tokenize='trigram', detail=full, content='')")
    db.executemany("INSERT INTO vocab_tri(rowid, word) VALUES (?,?)", ((i, w) for i, (w, c) in enumerate(vocab, 1)))
    db.execute("INSERT INTO vocab_tri(vocab_tri) VALUES('optimize')")


def lookup_hybrid(db, tok):
    got = lookup_symspell_hash(db, tok, 99)
    if not got and len(tok) >= 7:
        got = lookup_trigram(db, tok, 200)
    return got


def build_spellfix(db, vocab):
    db.execute("CREATE VIRTUAL TABLE spell USING spellfix1")
    db.executemany("INSERT INTO spell(word, rank) VALUES (?,?)", vocab)


def lookup_spellfix(db, tok):
    rows = db.execute("SELECT word, distance, rank FROM spell WHERE word MATCH ? AND top=5", (tok,)).fetchall()
    return [w for w, _, _ in rows]


def evaluate(name, db, lookup, real, synth, synth2):
    res = {}
    for label, pairs in (("real", real), ("synthetic", synth), ("synthetic_double", synth2)):
        r1 = r5 = 0
        lat = []
        for typed, want in pairs:
            t0 = time.perf_counter()
            got = lookup(db, typed)
            lat.append((time.perf_counter() - t0) * 1000)
            r1 += bool(got[:1] and got[0] == want)
            r5 += want in got
        lat.sort()
        res[label] = {"n": len(pairs), "recall_at_1": round(r1 / len(pairs), 4), "recall_at_5": round(r5 / len(pairs), 4),
                      "latency_ms_median": round(statistics.median(lat), 3),
                      "latency_ms_p95": round(lat[int(0.95 * (len(lat) - 1))], 3)}
    return res


def cmd_run(args) -> None:
    vocab = []
    with open(os.path.join(args.dir, "vocab.tsv"), encoding="utf-8") as f:
        for line in f:
            w, c = line.rstrip("\n").split("\t")
            vocab.append((w, int(c)))
    with open(os.path.join(args.dir, "typos.json"), encoding="utf-8") as f:
        t = json.load(f)
    real = [tuple(x) for x in t["real"]]
    synth = [tuple(x) for x in t["synthetic"]]
    synth2 = [tuple(x) for x in t.get("synthetic_double", [])]
    os.makedirs(args.work, exist_ok=True)
    out = {"sqlite_version": sqlite3.sqlite_version, "python": sys.version.split()[0], "platform": sys.platform,
           "vocab_words": len(vocab), "methods": {}}
    methods = [
        ("symspell_d1_short_d2_long7", lambda db: build_symspell(db, vocab, 7), lambda db, x: lookup_symspell(db, x, 7)),
        ("symspell_d2_from5", lambda db: build_symspell(db, vocab, 5), lambda db, x: lookup_symspell(db, x, 5)),
        ("symspell_d1_only", lambda db: build_symspell(db, vocab, 99), lambda db, x: lookup_symspell(db, x, 99)),
        ("symspell_hash_d1_short_d2_long7", lambda db: build_symspell_hash(db, vocab, 7),
         lambda db, x: lookup_symspell_hash(db, x, 7)),
        ("symspell_hash_d1_only", lambda db: build_symspell_hash(db, vocab, 99),
         lambda db, x: lookup_symspell_hash(db, x, 99)),
        ("symspell_hash_d1_only_freq2", lambda db: build_symspell_hash(db, vocab, 99, 2),
         lambda db, x: lookup_symspell_hash(db, x, 99)),
        ("hybrid_symspell_hash_d1_plus_trigram", lambda db: build_hybrid(db, vocab), lookup_hybrid),
        ("trigram_detail_full", lambda db: build_trigram(db, vocab, "full"), lookup_trigram),
        ("trigram_detail_full_top200", lambda db: build_trigram(db, vocab, "full"),
         lambda db, x: lookup_trigram(db, x, 200)),
        ("trigram_detail_column", lambda db: build_trigram(db, vocab, "column"), lookup_trigram),
        ("trigram_detail_none", lambda db: build_trigram(db, vocab, "none"), lookup_trigram),
    ]
    if args.spellfix:
        methods.append(("spellfix1", lambda db: build_spellfix(db, vocab), lookup_spellfix))
    for name, build, lookup in methods:
        path = os.path.join(args.work, f"fuzzy-{name}.sqlite")
        if os.path.exists(path):
            os.remove(path)
        db = sqlite3.connect(path)
        if name == "spellfix1":
            db.enable_load_extension(True)
            db.load_extension(args.spellfix)
        t0 = time.time()
        try:
            with db:
                build(db)
            db.execute("VACUUM")
        except sqlite3.OperationalError as e:
            out["methods"][name] = {"error": str(e)}
            print(name, "FAILED", e)
            db.close()
            continue
        build_s = time.time() - t0
        db.close()
        size = os.path.getsize(path)
        db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        if name == "spellfix1":
            db.enable_load_extension(True)
            db.load_extension(args.spellfix)
        # vocab table alone (shared by every method) to report the method's own cost
        vocab_only = db.execute("SELECT SUM(length(word)) + 9 * COUNT(*) FROM vocab").fetchone()[0] \
            if name != "spellfix1" else None
        lookup(db, "warmup")
        res = evaluate(name, db, lookup, real, synth, synth2)
        db.close()
        out["methods"][name] = {"db_bytes": size, "build_seconds": round(build_s, 2), **res}
        print(f"{name:28s} {size / 2**20:7.1f} MiB  build {build_s:5.1f}s  "
              f"real r@1 {res['real']['recall_at_1']:.2f} r@5 {res['real']['recall_at_5']:.2f}  "
              f"synth r@1 {res['synthetic']['recall_at_1']:.2f} r@5 {res['synthetic']['recall_at_5']:.2f}  "
              f"double r@5 {res['synthetic_double']['recall_at_5']:.2f}  "
              f"median {res['synthetic']['latency_ms_median']:.2f} ms p95 {res['synthetic']['latency_ms_p95']:.2f} ms")
    # size of an empty-method baseline (vocab table only)
    base = os.path.join(args.work, "fuzzy-vocab-only.sqlite")
    if os.path.exists(base):
        os.remove(base)
    db = sqlite3.connect(base)
    with db:
        db.execute("CREATE TABLE vocab(id INTEGER PRIMARY KEY, word TEXT NOT NULL, freq INTEGER NOT NULL)")
        db.executemany("INSERT INTO vocab VALUES (?,?,?)", ((i, w, c) for i, (w, c) in enumerate(vocab, 1)))
    db.execute("VACUUM")
    db.close()
    out["vocab_only_db_bytes"] = os.path.getsize(base)
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1)
    print("wrote", args.out)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    e = sub.add_parser("export")
    e.add_argument("--db", required=True)
    e.add_argument("--out", required=True)
    e.add_argument("--n", type=int, default=2000)
    r = sub.add_parser("run")
    r.add_argument("--dir", required=True, help="folder with vocab.tsv and typos.json")
    r.add_argument("--work", required=True, help="scratch folder for the test databases")
    r.add_argument("--spellfix", help="path to a compiled spellfix1 extension")
    r.add_argument("--out", required=True)
    args = ap.parse_args()
    cmd_export(args) if args.cmd == "export" else cmd_run(args)


if __name__ == "__main__":
    main()
