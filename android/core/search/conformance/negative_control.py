"""Negative control for the conformance tests: a reference with known, deliberate changes.

    python android/core/search/conformance/negative_control.py [--src data/search-conformance]
    BEAMLINE_CONFORMANCE_DIR=<repo>/data/search-conformance-negative ./gradlew :core:search:test \
        --tests '*SearchConformanceTest*' --tests '*AddressConformanceTest.sameAnswersAsAddrlib*'

Copies the reference into data/search-conformance-negative/ and changes it in exactly these places,
so the Kotlin tests must report each of them (and fail):
  search:    query 1 two results swapped; query 2 a title changed; query 3 a score + 0.0001;
             query 4 a distance + 0.01; query 5 a parse label changed; one test-set verdict flipped
  addresses: case 1 a latitude + 2e-9 deg; case 2 the parsed ZIP changed; case 3 one result dropped
The holdout database is hard-linked (no copy). Standard library only.

--only title,score,distance,label plants just those kinds (field-level changes with the same ids and
verdicts): the search test must still fail. Kinds: swap title score distance label verdict lat zip drop,
and (not in the default set, as it stops the test before the answers are compared) stale: the
search reference's fingerprint of packbuilder/search/query.py no longer matches the file.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import shutil

REPO = pathlib.Path(__file__).resolve().parents[4]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(REPO / "data" / "search-conformance"))
    ap.add_argument("--dst", default=str(REPO / "data" / "search-conformance-negative"))
    ap.add_argument("--only", default="swap,title,score,distance,label,verdict,lat,zip,drop",
                    help="comma-separated kinds of change to plant")
    args = ap.parse_args()
    want = set(args.only.split(","))
    src = pathlib.Path(args.src)
    dst = pathlib.Path(args.dst)
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    s = json.loads((src / "reference-search.json").read_text(encoding="utf-8"))
    rich = [r for r in s["results"] if len(r["results"]) >= 3 and r["results"][0]["distance_km"] is not None]
    changed = []
    if "swap" in want:
        r = rich[0]; r["results"][0], r["results"][1] = r["results"][1], r["results"][0]; changed.append((r["id"], "swap"))
    if "title" in want:
        r = rich[1]; r["results"][2]["title"] += " X"; changed.append((r["id"], "title"))
    if "score" in want:
        r = rich[2]; r["results"][1]["score"] = round(r["results"][1]["score"] + 0.0001, 4); changed.append((r["id"], "score"))
    if "distance" in want:
        r = rich[3]; r["results"][0]["distance_km"] = round(r["results"][0]["distance_km"] + 0.01, 2); changed.append((r["id"], "distance"))
    if "label" in want:
        r = [x for x in rich[4:] if x["parse"]][0]; r["parse"][0]["text"] += "!"; changed.append((r["id"], "label"))
    if "verdict" in want:
        v = [x for x in s["results"] if "verdict" in x][7]; v["verdict"]["top1"] = not v["verdict"]["top1"]; changed.append((v["id"], "verdict"))
    if "stale" in want:
        s["fingerprint"]["code"]["packbuilder/search/query.py"] = "0" * 64; changed.append(("fingerprint", "stale query.py"))
    (dst / "reference-search.json").write_text(json.dumps(s), encoding="utf-8")
    a = json.loads((src / "reference-addresses.json").read_text(encoding="utf-8"))
    cases = [c for c in a["runs"]["pack"]["cases"] if len(c["results"]) >= 2]
    if "lat" in want:
        cases[0]["results"][0]["lat"] += 2e-9; changed.append(("addr:" + cases[0]["text"], "lat"))
    if "zip" in want:
        cases[1]["parse"]["zip"] = "00000"; changed.append(("addr:" + cases[1]["text"], "zip"))
    if "drop" in want:
        cases[2]["results"].pop(); changed.append(("addr:" + cases[2]["text"], "drop result"))
    (dst / "reference-addresses.json").write_text(json.dumps(a), encoding="utf-8")
    try:
        os.link(src / "e2e-holdout.sqlite", dst / "e2e-holdout.sqlite")
    except OSError:
        shutil.copyfile(src / "e2e-holdout.sqlite", dst / "e2e-holdout.sqlite")
    for x in changed:
        print("changed", *x)


if __name__ == "__main__":
    main()
