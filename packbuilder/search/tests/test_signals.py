"""Unit tests of signals.py (the traffic lights of the search pack).

    .venv/search/Scripts/python packbuilder/search/tests/test_signals.py
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import signals as S  # noqa: E402


class SignalsTest(unittest.TestCase):
    def test_what_a_traffic_light_is(self):
        self.assertTrue(S.is_signal({"highway": "traffic_signals"}))
        # a crossing with its own pedestrian light is not a junction's light
        self.assertFalse(S.is_signal({"highway": "crossing", "crossing": "traffic_signals"}))
        self.assertFalse(S.is_signal({"name": "x"}))

    def test_cells(self):
        a, b = S.cell(40.44123, -79.99581), S.cell(40.44877, -79.99012)   # downtown Pittsburgh
        self.assertEqual(a, b, "the same 0.01 degree square")
        self.assertNotEqual(a, S.cell(40.45012, -79.99581))
        self.assertNotEqual(a, S.cell(40.44123, -80.00012))
        self.assertEqual((4044 + 9000) * 40000 + (-8000 + 18000), a)
        self.assertEqual(0, S.cell(-89.999, -179.999), "never below zero")
        self.assertLess(S.cell(89.999, 179.999), 2 ** 31)


if __name__ == "__main__":
    unittest.main()
