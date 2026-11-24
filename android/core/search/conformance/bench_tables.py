"""Side-by-side latency tables (Markdown) from the benchmark outputs.

    python android/core/search/conformance/bench_tables.py [--dir data/search-conformance] [--device data/search-demo/bench-device.json]

Reads bench-jvm.json (./gradlew :core:search-bench:bench), python-measure-{latency,prefix,house}.json
(packbuilder/search/measure.py --only latency|prefix|house --out ...) and, when given,
bench-device.json (android/scripts/search_demo.py bench). Standard library only.
"""
from __future__ import annotations

import argparse
import json
import pathlib

REPO = pathlib.Path(__file__).resolve().parents[4]


def load(p):
    p = pathlib.Path(p)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=str(REPO / "data" / "search-conformance"))
    ap.add_argument("--jvm", default=None)
    ap.add_argument("--device", default=None)
    args = ap.parse_args()
    d = pathlib.Path(args.dir)
    jvm = load(args.jvm or d / "bench-jvm.json")
    dev = load(args.device) if args.device else None
    py_lat = (load(d / "python-measure-latency.json") or {}).get("latency", {})
    py_pre = (load(d / "python-measure-prefix.json") or {}).get("prefix_latency", {}).get("groups", {})
    py_house = (load(d / "python-measure-house.json") or {}).get("house_latency", {}).get("queries", {})
    pre_load = (load(d / "python-measure-prefix.json") or {}).get("prefix_latency", {}).get("cpu_load_percent_at_start")
    print(f"Python CPU load at start: latency {py_lat.get('cpu_load_percent_at_start')} %, prefix {pre_load} %")
    print()
    cols = [("Python", None), ("Kotlin JVM", jvm), ("Kotlin emulator", dev)]
    cols = [c for c in cols if c[0] == "Python" or c[1]]

    print("Query types (test sets: tuned + held-out), cold median / p95 and warm median / p95, ms\n")
    print("| type | n | " + " | ".join(f"{n} cold | {n} warm" for n, _ in cols) + " |")
    print("|---|---:|" + "---:|---:|" * len(cols))
    pyc = py_lat.get("dev_and_holdout_cases", {})
    for t in list(jvm["query_types"].keys()):
        if t == "review set":
            continue
        row = [t, str(jvm["query_types"][t]["queries"])]
        for n, src in cols:
            if src is None:
                p = pyc.get(t)
                row += [f"{p['cold_median_ms']:.1f} / {p['cold_p95_ms']:.1f}", f"{p['warm_median_ms']:.1f} / {p['warm_p95_ms']:.1f}"] if p else ["", ""]
            else:
                q = src["query_types"][t]
                row += [f"{q['cold']['median_ms']:.1f} / {q['cold']['p95_ms']:.1f}", f"{q['warm']['median_ms']:.1f} / {q['warm']['p95_ms']:.1f}"]
        print("| " + " | ".join(row) + " |")

    print("\nTyping (second run of each prefix, Erie + Philadelphia + Altoona), median / p95 / max, ms\n")
    print("| group | n | " + " | ".join(n for n, _ in cols) + " |")
    print("|---|---:|" + "---:|" * len(cols))
    for g, v in jvm["typing"].items():
        row = [g, str(v["n"])]
        for n, src in cols:
            if src is None:
                p = py_pre.get(g, {}).get("all points")
                row.append(f"{p['median_ms']:.1f} / {p['p95_ms']:.1f} / {p['max_ms']:.0f}" if p else "")
            else:
                q = src["typing"][g]
                row.append(f"{q['median_ms']:.1f} / {q['p95_ms']:.1f} / {q['max_ms']:.0f}")
        print("| " + " | ".join(row) + " |")

    print("\nHouse numbers with the addresses database attached, warm median of 5, ms\n")
    print("| query | " + " | ".join(n for n, _ in cols) + " | top result |")
    print("|---|" + "---:|" * len(cols) + "---|")
    for q, v in jvm["house_numbers"]["with addresses"].items():
        row = [q]
        for n, src in cols:
            if src is None:
                p = py_house.get("with addresses")
                row.append(f"{p[q]['median_ms']:.1f}" if isinstance(p, dict) and q in p else "n/a")
            else:
                row.append(f"{src['house_numbers']['with addresses'][q]['median_ms']:.1f}")
        print("| " + " | ".join(row) + f" | {v['top']} |")

    for n, src in cols:
        if src is None:
            continue
        print(f"\n{n}: SQLite {src['sqlite_version']}; open {json.dumps(src['open'])}; "
              f"address parse {src['address_lookup']['parse']}; lookup {src['address_lookup']['lookup']}; "
              f"memory {json.dumps(src.get('memory'))}; math checksum {src.get('math_checksum')}; wall {src.get('bench_wall_s')} s")


if __name__ == "__main__":
    main()
