"""Independent check of the Kotlin search answers against the Python reference (no Kotlin code).

    python android/core/search/conformance/compare.py [--dir data/search-conformance]

Reads reference-search.json (Python) and kotlin/kotlin-search.json (written by the Kotlin
SearchConformanceTest) and compares, for every query, the top-10 ids in order and every field of
every result. Prints the counts and the first differences. Standard library only.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[4]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=str(REPO / "data" / "search-conformance"))
    ap.add_argument("--kotlin", help="Kotlin answers (default: <dir>/kotlin/kotlin-search.json; a device dump from "
                                     "android/scripts/search_demo.py dump also works)")
    args = ap.parse_args()
    d = pathlib.Path(args.dir)
    ref = {r["id"]: r for r in json.loads((d / "reference-search.json").read_text(encoding="utf-8"))["results"]}
    kfile = pathlib.Path(args.kotlin) if args.kotlin else d / "kotlin" / "kotlin-search.json"
    kt = {r["id"]: r for r in json.loads(kfile.read_text(encoding="utf-8"))}
    missing = sorted(set(ref) ^ set(kt))
    ids_same = fields_same = 0
    diffs = []
    for qid, p in ref.items():
        k = kt.get(qid)
        if k is None:
            continue
        pi = [x["id"] for x in p["results"]]
        ki = [x["id"] for x in k["results"]]
        if pi != ki:
            diffs.append(f"{qid}: ids py={pi} kt={ki}")
            continue
        ids_same += 1
        bad = [f"#{i + 1}.{f}: py={a[f]!r} kt={b.get(f)!r}" for i, (a, b) in enumerate(zip(p["results"], k["results"]))
               for f in a if a[f] != b.get(f)]
        if bad:
            diffs.append(f"{qid}: " + "; ".join(bad))
        else:
            fields_same += 1
    print(f"queries: {len(ref)}; missing on one side: {len(missing)}; top-10 ids equal: {ids_same}; "
          f"every field equal: {fields_same}")
    for x in diffs[:20]:
        print("  " + x)
    return 0 if not diffs and not missing else 1


if __name__ == "__main__":
    sys.exit(main())
