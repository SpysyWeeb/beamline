"""build_state_shapes.py (docs/architecture-downloads.md 6.8, 7.4): the committed asset equals a fresh build."""
import json
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import build_state_shapes as bss  # noqa: E402

STATES = set("AK AL AR AZ CA CO CT DC DE FL GA HI IA ID IL IN KS KY LA MA MD ME MI MN MO MS MT NC ND NE NH NJ NM NV NY "
             "OH OK OR PA RI SC SD TN TX UT VA VT WA WI WV WY".split())


class StateShapesTests(unittest.TestCase):
    def setUp(self):
        self.asset = json.loads(bss.ASSET.read_bytes())

    def test_format_size_and_states(self):
        a = self.asset
        self.assertEqual("beamline-state-shapes/1", a["format"])
        self.assertLessEqual(bss.ASSET.stat().st_size, bss.BUDGET)
        self.assertEqual(1000, a["width"])
        self.assertTrue(600 <= a["height"] <= 650, a["height"])
        codes = [s["code"] for s in a["states"]]
        self.assertEqual(51, len(codes))
        self.assertEqual(STATES, set(codes))
        for s in a["states"]:
            self.assertTrue(s["rings"], s["code"])
            x0, y0, x1, y1 = s["box"]
            for r in s["rings"]:
                x, y = r[0], r[1]
                for i in range(2, len(r), 2):
                    x += r[i]
                    y += r[i + 1]
                    self.assertTrue(x0 <= x <= x1 and y0 <= y <= y1, s["code"])
        by = {s["code"]: s for s in a["states"]}
        self.assertEqual(["DE", "MD", "NJ", "NY", "OH", "WV"], by["PA"]["neighbours"])
        self.assertEqual(["MD", "VA"], by["DC"]["neighbours"])
        self.assertEqual([], by["AK"]["neighbours"])
        self.assertEqual(("alaska", "hawaii", "lower48"), (by["AK"]["inset"], by["HI"]["inset"], by["PA"]["inset"]))
        self.assertEqual(42, by["PA"]["fips"])

    def test_insets_sit_in_their_boxes(self):
        a = self.asset
        by = {s["code"]: s for s in a["states"]}
        for code, inset in (("AK", "alaska"), ("HI", "hawaii")):
            bx0, by0, bx1, by1 = (v * a["scale"] for v in a["inset_boxes"][inset])
            x0, y0, x1, y1 = by[code]["box"]
            self.assertTrue(bx0 <= x0 and x1 <= bx1 and by0 <= y0 and y1 <= by1, code)

    def test_frames_project_known_points(self):
        a = self.asset
        f = a["frames"]["lower48"]
        project = bss.albers(f)
        x, y = project(-77.0365, 38.8977)          # Washington, DC
        mx, my = f["k"] * x + f["dx"], -f["k"] * y + f["dy"]
        dc = next(s for s in a["states"] if s["code"] == "DC")["box"]
        self.assertTrue(dc[0] - 5 <= mx * 10 <= dc[2] + 5 and dc[1] - 5 <= my * 10 <= dc[3] + 5)
        self.assertEqual("alaska", bss.frame_for(-134.42, 58.30))   # Juneau
        self.assertEqual("hawaii", bss.frame_for(-157.86, 21.31))   # Honolulu
        self.assertEqual("lower48", bss.frame_for(-80.09, 42.13))   # Erie

    @unittest.skipUnless(bss.GPKG.is_file(), "needs data/map/work/overview/ne_overview.gpkg")
    def test_the_committed_asset_equals_a_fresh_build(self):
        self.assertEqual(bss.ASSET.read_bytes(), bss.build())


if __name__ == "__main__":
    unittest.main()
