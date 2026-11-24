"""Record the engine answers the navigation session's JVM tests need (android/nav/session).

The session tests answer every routing request from recordings (a recorded-answer engine). When a
test asks something no recording holds (a re-route from a simulated fix, a stop change, a stop-order
matrix), the test fails and lists the request in the module's build folder; this script asks
pyvalhalla 3.9.1 on the PA+OH device tiles and adds the answers to
android/routing/testdata/nav/session-recordings.json.gz. The simulated drives are deterministic, so
the next run asks the same requests and finds them.

    sh tools/round/gradle.sh n :nav:session:test          # fails: N requests not recorded
    .venv/routing/Scripts/python android/routing/scripts/record_session.py \
        --missing data/gradle/n/build/nav/session/missing-requests.json --device data/routing/device
    sh tools/round/gradle.sh n :nav:session:test          # again (a re-route answer can lead to new requests)

Requests are stored as the tests sent them (the app's request, turn_lanes included). Route, matrix
(sources_to_targets) and trace_attributes (batch C1: lane counts for the lane warnings) requests are recorded. Standard library plus pyvalhalla.
"""
from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "packbuilder" / "routing"))
import valhalla  # noqa: E402

OUT = REPO / "android" / "routing" / "testdata" / "nav" / "session-recordings.json.gz"


def engine(device: Path) -> valhalla.Actor:
    cfg = json.loads((device / "valhalla.json").read_text(encoding="utf-8"))
    cfg["mjolnir"]["tile_extract"] = str((device / cfg["mjolnir"]["tile_extract"]).resolve())
    return valhalla.Actor(cfg)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--missing", type=Path, required=True, help="missing-requests.json written by the session tests")
    ap.add_argument("--device", type=Path, default=REPO / "data" / "routing" / "device",
                    help="device folder written by packbuilder/routing/packs.py (PA+OH)")
    a = ap.parse_args()
    if valhalla.__version__ != "3.9.1":
        sys.exit(f"pyvalhalla {valhalla.__version__}; the device engine is 3.9.1")
    if not a.missing.is_file():
        print("no missing requests")
        return 0
    missing = json.loads(a.missing.read_text(encoding="utf-8"))["requests"]
    have: list[dict] = []
    if OUT.is_file():
        have = json.loads(gzip.decompress(OUT.read_bytes()))["recordings"]
    keys = {json.dumps(r["request"], sort_keys=True) for r in have}
    actor = engine(a.device)
    added = 0
    for m in missing:
        req = json.loads(m["text"])
        key = json.dumps(req, sort_keys=True)
        if key in keys:
            continue
        try:
            if m["kind"] == "matrix":
                body = actor.matrix(m["text"])
            elif m["kind"] == "trace_attributes":
                body = actor.trace_attributes(m["text"])
            else:
                body = actor.route(m["text"])
            have.append({"request": req, "response": json.loads(body)})
        except valhalla.ValhallaError as e:
            have.append({"request": req, "error": {"code": e.code, "message": e.message}})
        except RuntimeError as e:
            msg = str(e)
            try:
                err = json.loads(msg)
                have.append({"request": req, "error": {"code": err.get("error_code"), "message": err.get("error")}})
            except ValueError:
                have.append({"request": req, "error": {"code": None, "message": msg}})
        keys.add(key)
        added += 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    data = json.dumps({"format": "beamline-recordings/1",
                       "meta": {"engine": f"pyvalhalla {valhalla.__version__}", "device": "data/routing/device (PA+OH)",
                                "made_by": "android/routing/scripts/record_session.py"},
                       "recordings": have}, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    with open(OUT, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as f:
        f.write(data)
    print(f"added {added} recording(s); {len(have)} in {OUT.relative_to(REPO).as_posix()} ({OUT.stat().st_size:,} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
