"""Everyday searches find the places near you (docs/next-round.md item 43).

Runs tests/everyday_searches.json against a built search pack: BEAMLINE_SEARCH_DB, else
data/search/pa-search.sqlite. Skips when there is no pack (as the other pack tests do).
"""
import json
import os
import subprocess
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SEARCH = os.path.dirname(HERE)
REPO = os.path.abspath(os.path.join(SEARCH, "..", ".."))
DB = os.environ.get("BEAMLINE_SEARCH_DB") or os.path.join(REPO, "data", "search", "pa-search.sqlite")


@unittest.skipUnless(os.path.isfile(DB), "no built search pack")
class EverydayTest(unittest.TestCase):
    def test_everyday_searches(self):
        spec = json.load(open(os.path.join(HERE, "everyday_searches.json"), encoding="utf-8"))
        lat, lon = spec["from"]
        problems = []
        for s in spec["searches"]:
            out = subprocess.run([sys.executable, os.path.join(SEARCH, "cli.py"), s["q"], "--near", f"{lat},{lon}", "--db", DB,
                                  "--no-addresses", "--limit", "10", "--json"], capture_output=True, text=True, check=True)
            d = json.loads(out.stdout)
            cats = [p.get("match") for p in d.get("parse", []) or [] if p.get("as") == "category"]
            near = [r for r in d.get("results", []) if r["distance_km"] < spec["near_km"]]
            wanted = s["category"] if isinstance(s["category"], list) else [s["category"]]
            if not any(w in json.dumps(cats) for w in wanted):
                problems.append(f"{s['q']!r}: read as {cats or 'no category'}, expected {' or '.join(wanted)}")
            elif len(near) < s["min_near"]:
                problems.append(f"{s['q']!r}: {len(near)} of 10 within {spec['near_km']} km, expected {s['min_near']}")
        self.assertEqual([], problems, "\n".join(problems))


if __name__ == "__main__":
    unittest.main()
