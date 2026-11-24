"""Unit tests of lines.py (the lines of the streets in the search pack).

    .venv/search/Scripts/python packbuilder/search/tests/test_lines.py

A small grid at 42 N: State Street runs north-south, 12th Street east-west through it.
"""
import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import lines  # noqa: E402

LAT0, LON0 = 42.0, -80.0
KY = 111320.0
KX = KY * math.cos(math.radians(LAT0))


def q(x: float, y: float) -> tuple[int, int]:
    """(lat, lon) in whole 1e-5 degrees of a point x metres east and y metres north of the origin."""
    return (round((LAT0 + y / KY) * lines.SCALE), round((LON0 + x / KX) * lines.SCALE))


def metres(lat: float, lon: float) -> tuple[float, float]:
    return ((lon - LON0) * KX, (lat - LAT0) * KY)


STATE = [[q(0, -500), q(0, 0), q(0, 500)]]
TWELFTH = [[q(-400, 0), q(0, 0), q(400, 0)]]


class LinesTest(unittest.TestCase):
    def test_a_blob_gives_back_its_points(self):
        for ls in ([[(4200000, -8000000), (4200010, -8000020)]],
                   STATE + TWELFTH,
                   [[(0, 0), (1, -1)], [(-9000000, 18000000), (9000000, -18000000), (5, 5)]]):
            self.assertEqual(ls, lines.decode(lines.encode(ls)))
        self.assertEqual([], lines.decode(lines.encode([])))
        # small steps are one byte each: count, count, the first point, then 2 bytes a point
        blob = lines.encode([[(4200000, -8000000), (4200010, -8000020), (4200030, -8000010)]])
        self.assertEqual(1 + 1 + (4 + 4) + 2 + 2, len(blob))

    def test_a_broken_blob_is_refused(self):
        good = lines.encode(STATE)
        far = lines.encode([[(4200000, -8000000), (4200010, -8000020)]])
        far = far[:2] + bytes([0xFF, 0xFF, 0xFF, 0xFF, 0x0F]) + far[6:]   # a first latitude far off the globe
        for bad in (good[:-1], good + b"\x00", b"\x01\x01\x02\x02", b"\x01", b"\xff" * 12, far):
            with self.assertRaises(ValueError):
                lines.decode(bad)

    def test_the_nearest_point_of_a_street(self):
        d, lat, lon = lines.nearest(STATE, *reversed(q_deg(120, 80)))
        self.assertAlmostEqual(120.0, d, delta=1.5)
        x, y = metres(lat, lon)
        self.assertAlmostEqual(0.0, x, delta=1.5)
        self.assertAlmostEqual(80.0, y, delta=1.5)
        # beyond the end of the line: its end point
        d, lat, lon = lines.nearest(STATE, *reversed(q_deg(30, 900)))
        self.assertAlmostEqual(math.hypot(30, 400), d, delta=1.5)
        # on the street: nothing to walk
        self.assertLess(lines.nearest(STATE, *reversed(q_deg(0, 250)))[0], 1.5)
        self.assertIsNone(lines.nearest([], 42.0, -80.0))

    def test_where_two_streets_cross(self):
        pts = lines.crossings(STATE, TWELFTH, within_m=15, apart_m=120)
        self.assertEqual(1, len(pts))
        x, y = metres(*pts[0])
        self.assertLess(math.hypot(x, y), 2.0)
        # the other way round: the same place
        back = lines.crossings(TWELFTH, STATE, within_m=15, apart_m=120)
        self.assertEqual(1, len(back))
        self.assertLess(math.hypot(*metres(*back[0])), 2.0)

    def test_a_street_that_ends_on_another_meets_it(self):
        # a side street that stops 6 m short of State Street's line (the simplified line moved a little)
        side = [[q(300, 200), q(6, 200)]]
        pts = lines.crossings(STATE, side, within_m=15, apart_m=120)
        self.assertEqual(1, len(pts))
        x, y = metres(*pts[0])
        self.assertAlmostEqual(3.0, x, delta=2.0)
        self.assertAlmostEqual(200.0, y, delta=2.0)
        # 40 m short: not a meeting
        self.assertEqual([], lines.crossings(STATE, [[q(300, 200), q(40, 200)]], within_m=15, apart_m=120))

    def test_a_divided_road_is_one_crossing_and_far_crossings_are_two(self):
        divided = [[q(-400, 0), q(400, 0)], [q(400, 25), q(-400, 25)]]   # two carriageways 25 m apart
        self.assertEqual(1, len(lines.crossings(STATE, divided, within_m=15, apart_m=120)))
        twice = [[q(-400, -300), q(400, -300)], [q(-400, 300), q(400, 300)]]   # the same name 600 m apart
        self.assertEqual(2, len(lines.crossings(STATE, twice, within_m=15, apart_m=120)))

    def test_parallel_streets_do_not_cross(self):
        self.assertEqual([], lines.crossings(STATE, [[q(60, -500), q(60, 500)]], within_m=15, apart_m=120))
        self.assertEqual([], lines.crossings(STATE, [], within_m=15, apart_m=120))


def q_deg(x: float, y: float) -> tuple[float, float]:
    """(lon, lat) in degrees of a point x metres east and y metres north of the origin."""
    return (LON0 + x / KX, LAT0 + y / KY)


if __name__ == "__main__":
    unittest.main()
