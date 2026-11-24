"""Compare Planetiler's own per-stage wall and CPU times across build logs (e.g. with and without lane tags).

Usage (from the repo root):
    .venv/map/Scripts/python packbuilder/map/build_times.py data/map/logs/pa-261114-z14.log \
        data/map/logs/pa-261114-z14-nolanes-t1.log [more logs ...] [--json OUT.json]

Reads the stage summary Planetiler prints at the end of a run (lines such as
"osm_pass2 3m51s cpu:16m19s gc:2s avg:4.2") and prints one row per log with
the wall and CPU seconds of: overall, osm_pass2 (where the profile, and so the
lane patch, runs per OSM element) and archive (tile encoding, where the
line merge runs). CPU time is less sensitive to other jobs on the machine than
wall time, but not immune (shared cores, caches), so compare runs made back to
back and look at the spread between repeats.
"""
import argparse
import json
import re
import sys
from pathlib import Path

STAGES = ("overall", "osm_pass1", "osm_pass2", "archive")
ANSI = re.compile(r"\x1b\[[0-9;]*m")
LINE = re.compile(r"\]\s+-\s+\t(\w+)\s+([0-9hms.]+)\s+cpu:([0-9hms.]+)")


def seconds(text: str) -> float:
    total = 0.0
    for num, unit in re.findall(r"([0-9.]+)([hms])", text):
        total += float(num) * {"h": 3600, "m": 60, "s": 1}[unit]
    return total


def stages(log: Path) -> dict:
    out = {}
    for line in log.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LINE.search(ANSI.sub("", line))
        if m and m.group(1) in STAGES:
            out[m.group(1)] = {"wall_s": seconds(m.group(2)), "cpu_s": seconds(m.group(3))}
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--json")
    a = ap.parse_args()
    rows = {}
    print(f"{'log':40} " + " ".join(f"{s + ' wall':>15} {s + ' cpu':>14}" for s in STAGES))
    for p in a.logs:
        st = stages(Path(p))
        rows[Path(p).name] = st
        print(f"{Path(p).name:40} " + " ".join(
            f"{st.get(s, {}).get('wall_s', float('nan')):>15.1f} {st.get(s, {}).get('cpu_s', float('nan')):>14.1f}"
            for s in STAGES))
    if a.json:
        Path(a.json).parent.mkdir(parents=True, exist_ok=True)
        Path(a.json).write_text(json.dumps(rows, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
