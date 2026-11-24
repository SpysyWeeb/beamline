"""Kotlin (JVM) and Python search latency measured interleaved, several rounds, with the host load.

    python android/core/search/conformance/interleaved_bench.py --db <pa-search.sqlite> [--rounds 3]
        [--out data/search-conformance/interleaved]

The machine this runs on is shared, so one Kotlin run and one Python run taken minutes apart can
differ by 2x from load alone (review of 2026-11-15). Each round runs the Kotlin benchmark
(./gradlew :core:search-bench:bench, the SearchBench protocol) and Python's own measure.py
(--only latency, then --only prefix: the same protocol) back to back, alternating which goes first,
and reads the CPU load before and after each. The summary gives, per round and as a range over
the rounds, the warm and cold medians / p95 over the 444 test-set queries and the typing groups.
Windows only (CPU load from PowerShell, gradlew.bat). Standard library only.
"""
from __future__ import annotations

import argparse
import json
import os
import pathlib
import subprocess
import sys
import time

REPO = pathlib.Path(__file__).resolve().parents[4]
ANDROID = REPO / "android"


def cpu_load() -> float | None:
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-Command",
                            "(Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average"],
                           capture_output=True, text=True, timeout=60)
        return float(r.stdout.strip())
    except Exception:
        return None


def run_kotlin(out: pathlib.Path, log: pathlib.Path) -> float:
    env = dict(os.environ, BEAMLINE_BENCH_OUT=str(out))
    t0 = time.time()
    with open(log, "w", encoding="utf-8") as f:
        subprocess.run(["cmd", "/c", str(ANDROID / "gradlew.bat"), ":core:search-bench:bench", "--console=plain"], cwd=ANDROID, env=env,
                       stdout=f, stderr=subprocess.STDOUT, check=True)
    return time.time() - t0


def run_python(db: str, lat_out: pathlib.Path, pre_out: pathlib.Path, log: pathlib.Path) -> float:
    t0 = time.time()
    with open(log, "w", encoding="utf-8") as f:
        for only, out in (("latency", lat_out), ("prefix", pre_out)):
            subprocess.run([sys.executable, "-I", str(REPO / "packbuilder" / "search" / "measure.py"), "--only", only,
                            "--db", db, "--out", str(out)], cwd=REPO, stdout=f, stderr=subprocess.STDOUT, check=True)
    return time.time() - t0


def figures(kind: str, lat_file: pathlib.Path, pre_file: pathlib.Path | None = None) -> dict:
    """{name: (median, p95)} in one form for both engines."""
    out = {}
    if kind == "kotlin":
        j = json.loads(lat_file.read_text(encoding="utf-8"))
        a = j["query_types"]["ALL"]
        out["warm ALL"] = (a["warm"]["median_ms"], a["warm"]["p95_ms"])
        out["cold ALL"] = (a["cold"]["median_ms"], a["cold"]["p95_ms"])
        for g, v in j["typing"].items():
            out[g] = (v["median_ms"], v["p95_ms"])
    else:
        lat = json.loads(lat_file.read_text(encoding="utf-8"))["latency"]
        a = lat["dev_and_holdout_cases"]["ALL"] if "ALL" in lat["dev_and_holdout_cases"] else None
        if a is None:   # measure.py names the overall row differently in some versions
            a = lat.get("all") or lat.get("ALL")
        out["warm ALL"] = (a["warm_median_ms"], a["warm_p95_ms"])
        out["cold ALL"] = (a["cold_median_ms"], a["cold_p95_ms"])
        pre = json.loads(pre_file.read_text(encoding="utf-8"))["prefix_latency"]["groups"]
        for g, v in pre.items():
            p = v.get("all points")
            if p:
                out[g] = (p["median_ms"], p["p95_ms"])
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", required=True)
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--out", default=str(REPO / "data" / "search-conformance" / "interleaved"))
    args = ap.parse_args()
    out = pathlib.Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    rounds = []
    for i in range(1, args.rounds + 1):
        rec = {"round": i, "started": time.strftime("%H:%M:%S")}
        order = ["kotlin", "python"] if i % 2 else ["python", "kotlin"]
        rec["order"] = order
        for who in order:
            load0 = cpu_load()
            if who == "kotlin":
                f = out / f"kotlin-r{i}.json"
                wall = run_kotlin(f, out / f"kotlin-r{i}.log")
                fig = figures("kotlin", f)
            else:
                fl, fp = out / f"python-latency-r{i}.json", out / f"python-prefix-r{i}.json"
                wall = run_python(args.db, fl, fp, out / f"python-r{i}.log")
                fig = figures("python", fl, fp)
            rec[who] = {"cpu_load_before": load0, "cpu_load_after": cpu_load(), "wall_s": round(wall, 1), "figures": fig}
            print(f"round {i} {who}: load {load0} -> {rec[who]['cpu_load_after']} %, {wall:.0f} s, "
                  + "; ".join(f"{k} {v[0]}/{v[1]}" for k, v in fig.items()), flush=True)
        rounds.append(rec)
        (out / "summary.json").write_text(json.dumps({"rounds": rounds}, indent=1), encoding="utf-8")
    # ranges over the rounds and the Kotlin/Python ratio of medians per round
    names = list(rounds[0]["kotlin"]["figures"].keys())
    summary = {}
    for n in names:
        k = [r["kotlin"]["figures"][n] for r in rounds if n in r["kotlin"]["figures"]]
        p = [r["python"]["figures"][n] for r in rounds if n in r["python"]["figures"]]
        ratio = [round(a[0] / b[0], 2) for a, b in zip(k, p) if b[0]]
        summary[n] = {"kotlin_median_range": [min(x[0] for x in k), max(x[0] for x in k)],
                      "kotlin_p95_range": [min(x[1] for x in k), max(x[1] for x in k)],
                      "python_median_range": [min(x[0] for x in p), max(x[0] for x in p)] if p else None,
                      "python_p95_range": [min(x[1] for x in p), max(x[1] for x in p)] if p else None,
                      "kotlin_over_python_median_per_round": ratio}
    (out / "summary.json").write_text(json.dumps({"rounds": rounds, "ranges": summary}, indent=1), encoding="utf-8")
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main()
