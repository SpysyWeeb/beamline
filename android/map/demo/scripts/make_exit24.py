"""Write the exit 24 route as a small asset for :map:demo (track M's device checks).

    python android/map/demo/scripts/make_exit24.py          # write
    python android/map/demo/scripts/make_exit24.py --check  # compare only; exit 1 when it differs

Reads  android/routing/testdata/reference/exit24-osrm.json (track N's recording of the owner's
       exit 24 trip, docs/architecture.md 8: I-90 at 42.028694,-80.128884 heading 57 to Downs Drive
       42.053308,-80.079579; read only)
Writes android/map/demo/src/main/assets/demo/exit24.json:
       {"points": [[lat, lon], ...],                       the route line (polyline6 decoded)
        "maneuvers": [{"index", "along_m", "type", "modifier", "name", "speed_mps"}, ...]}
       index = the line point nearest the maneuver; along_m = metres from the start to that point;
       speed_mps = the step's own distance / duration (the speed of the stretch after it).
Reads  android/routing/testdata/nav/exit24.json (track N's golden trace of that drive: the session's
       state after each fix of the `default` simulation; read only)
Writes android/map/demo/src/main/assets/demo/trace24.json:
       {"columns": [...], "rows": [[t_ms, lat, lon, snapped, fix_bearing_deg, speed_mps, phase, motion,
        gps, u, shown_m, remaining_m], ...]}   (the vehicle's point; the demo's `trace` scene replays it)
       When N rewrites its trace, --check says DIFFERS: run this script again.
Standard library only.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
SRC = REPO / "android" / "routing" / "testdata" / "reference" / "exit24-osrm.json"
OUT = REPO / "android" / "map" / "demo" / "src" / "main" / "assets" / "demo" / "exit24.json"
TRACE_SRC = REPO / "android" / "routing" / "testdata" / "nav" / "exit24.json"
TRACE_OUT = REPO / "android" / "map" / "demo" / "src" / "main" / "assets" / "demo" / "trace24.json"
TRACE_COLUMNS = ["t_ms", "lat", "lon", "snapped", "fix_bearing_deg", "speed_mps", "phase", "motion",
                 "gps", "u", "shown_m", "remaining_m"]


def decode(poly: str, precision: int = 6) -> list[tuple[float, float]]:
    """Google's polyline algorithm at 1e-6 (OSRM's polyline6)."""
    pts, i, lat, lon, f = [], 0, 0, 0, 10 ** precision
    while i < len(poly):
        out = []
        for _ in range(2):
            shift = res = 0
            while True:
                b = ord(poly[i]) - 63
                i += 1
                res |= (b & 0x1F) << shift
                shift += 5
                if b < 0x20:
                    break
            out.append(~(res >> 1) if res & 1 else res >> 1)
        lat += out[0]
        lon += out[1]
        pts.append((lat / f, lon / f))
    return pts


def metres(a: tuple[float, float], b: tuple[float, float]) -> float:
    la1, la2 = math.radians(a[0]), math.radians(b[0])
    dla, dlo = la2 - la1, math.radians(b[1] - a[1])
    h = math.sin(dla / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin(dlo / 2) ** 2
    return 2 * 6371008.8 * math.asin(math.sqrt(h))


def build() -> bytes:
    rec = json.loads(SRC.read_text(encoding="utf-8"))
    route = rec["response"]["routes"][0]
    pts = decode(route["geometry"])
    along = [0.0]
    for a, b in zip(pts, pts[1:]):
        along.append(along[-1] + metres(a, b))
    maneuvers = []
    for step in route["legs"][0]["steps"]:
        lon, lat = step["maneuver"]["location"]
        idx = min(range(len(pts)), key=lambda k: metres(pts[k], (lat, lon)))
        dur = step["duration"]
        maneuvers.append({
            "index": idx,
            "along_m": round(along[idx], 1),
            "type": step["maneuver"]["type"],
            "modifier": step["maneuver"].get("modifier"),
            "name": step.get("name", ""),
            "speed_mps": round(step["distance"] / dur, 3) if dur > 0 else 0.0,
        })
    doc = {
        "_about": "The exit 24 route of docs/architecture.md 8, from android/routing/testdata/reference/exit24-osrm.json "
                  "(made by android/map/demo/scripts/make_exit24.py; do not edit).",
        "length_m": round(along[-1], 1),
        "points": [[round(p[0], 6), round(p[1], 6)] for p in pts],
        "maneuvers": maneuvers,
    }
    return (json.dumps(doc, indent=1) + "\n").encode("utf-8")


def build_trace() -> bytes:
    rec = json.loads(TRACE_SRC.read_text(encoding="utf-8"))
    if rec.get("format") != "beamline-golden-trace/1":
        raise SystemExit("make_exit24.py: the golden trace is not format beamline-golden-trace/1")
    rows = []
    for r in rec["rows"]:
        v, fix = r["vehicle"], r["fix"]
        if v is None:
            continue
        rows.append([
            r["t_ms"], round(v["lat"], 6), round(v["lon"], 6), 1 if v["snapped"] else 0,
            fix.get("bearing_deg"), fix.get("speed_mps"), r["phase"], r["motion"], r["gps"], r["u"],
            r["shown_m"], r["remaining_m"],
        ])
    about = ("Track N's golden trace of the exit 24 drive (android/routing/testdata/nav/exit24.json), the "
             "vehicle and state per fix, for :map:demo's trace scene (made by "
             "android/map/demo/scripts/make_exit24.py; do not edit).")
    nl = chr(10)
    text = (
        "{" + nl
        + ' "_about": ' + json.dumps(about) + "," + nl
        + ' "columns": ' + json.dumps(TRACE_COLUMNS) + "," + nl
        + ' "rows": [' + nl
        + ("," + nl).join("  " + json.dumps(r) for r in rows) + nl
        + " ]" + nl
        + "}" + nl
    )
    return text.encode("utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    bad = 0
    for out, data in ((OUT, build()), (TRACE_OUT, build_trace())):
        same = out.is_file() and out.read_bytes() == data
        if a.check:
            print(("same    " if same else "DIFFERS ") + out.relative_to(REPO).as_posix())
            bad += 0 if same else 1
            continue
        if not same:
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_bytes(data)
        print(("wrote   " if not same else "same    ") + out.relative_to(REPO).as_posix())
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
