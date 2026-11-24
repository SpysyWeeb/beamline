"""Avoid highways alternates on trips that start on a motorway: the rule before the review fix
(alternates asked with the preset's own options, exclude_highways, whatever produced the route)
against RoutePlanner's rule now (reference_routes.alternates_for). pyvalhalla 3.9.1 on the PA+OH
device folder; prints one line per trip. Evidence for docs/experiments/09-routing-on-device.md,
"Review and fixes".

    .venv/routing/Scripts/python android/routing/scripts/old_vs_new_alternates.py --device data/routing/device
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import reference_routes as RR  # noqa: E402

R = RR.R


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device", type=Path, default=RR.REPO / "data" / "routing" / "device")
    a = ap.parse_args()
    actor = RR.engine(a.device)
    preset = R.load_presets()["avoid_highways"]
    n_alt = 2
    for name, locs in RR.motorway_origin_trips().items():
        chosen = R.run_preset(actor, locs, preset)
        # before: preset.costingOptions, chosen route dropped, nothing else
        resp = R.route(actor, R.request(locs, preset, alternates=n_alt))
        routes = [{"trip": resp["trip"]}] + [{"trip": x["trip"]} for x in resp.get("alternates", [])]
        old = [r for r in routes if not RR.same_route(r, chosen)][:n_alt]
        new = RR.alternates_for(actor, locs, preset, chosen, n_alt)

        def fmt(rs):
            return [f"{r['trip']['summary']['length']:.1f} km / {R.flagged_km(r, 'highway'):.1f} motorway km" for r in rs]

        print(json.dumps({"trip": name, "used_fallback": bool(chosen.get("used_fallback")),
                          "chosen": f"{chosen['trip']['summary']['length']:.1f} km / {R.flagged_km(chosen, 'highway'):.1f} motorway km",
                          "alternates_before_fix": fmt(old), "alternates_now": fmt(new)}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
