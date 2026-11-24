"""Tests of driveways.py on a small made-up map (no data files needed).

    .venv/routing/Scripts/python -m unittest packbuilder/routing/test_driveways.py
"""
import sys
import tempfile
import unittest
from pathlib import Path

import osmium

sys.path.insert(0, str(Path(__file__).resolve().parent))
import driveways as D  # noqa: E402

NODES = "".join(f"n{i} v1 x-77.{i:03d} y40.{i:03d}\n" for i in range(1, 13))
WAYS = """\
w100 v1 Thighway=residential,name=First%20%Street Nn1,n2,n3
w101 v1 Thighway=service Nn4,n5
w102 v1 Thighway=service,service=parking_aisle Nn5,n6
w103 v1 Thighway=residential,name=Second%20%Street Nn8,n9
w104 v1 Thighway=service,service=alley Nn10,n11
w105 v1 Thighway=footway Nn12,n7
w110 v3 Thighway=service,service=driveway,oneway=yes Nn2,n4
w111 v1 Thighway=service,service=driveway Nn3,n7
w112 v1 Thighway=service,service=driveway Nn1,n3
w113 v1 Thighway=service,service=driveway Nn1,n8
w114 v1 Thighway=service,service=driveway,access=private Nn6,n9
w115 v2 Thighway=service,service=driveway Nn4,n9
w116 v1 Thighway=service,service=driveway Nn10,n1
w117 v1 Thighway=service,service=driveway,motor_vehicle=customers Nn5,n8
"""


def read(path):
    ways, nodes = {}, 0
    for o in osmium.FileProcessor(str(path)):
        if o.is_way():
            assert o.id not in ways, f"way {o.id} is in the file twice"
            ways[o.id] = (o.version, [n.ref for n in o.nodes], dict(o.tags))
        elif o.is_node():
            nodes += 1
    return ways, nodes


class DrivewaysTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.src = Path(self.dir.name) / "in.opl"
        self.src.write_text(NODES + WAYS, encoding="utf-8")

    def tearDown(self):
        self.dir.cleanup()

    def test_only_the_entrances_of_lots_are_chosen(self):
        found = D.find(self.src)
        # 110 street to a lot's lane, 115 a lot's lane to a street
        self.assertEqual([110, 115], sorted(found))
        # not: 111 a dead end (its far end is only on a footway), 112 both ends on one street, 113 street to
        # street, 114 private, 116 alley to street (an alley is a street here), 117 customers only

    def test_the_file_comes_back_with_only_those_ways_changed(self):
        out = Path(self.dir.name) / "out.osm.pbf"
        res = D.open_entrances(self.src, out)
        self.assertEqual(2, res["driveways_opened"])
        before, n_before = read(self.src)
        after, n_after = read(out)
        self.assertEqual(n_before, n_after)
        self.assertEqual(sorted(before), sorted(after))
        self.assertEqual((4, [2, 4], {"highway": "service", "oneway": "yes"}), after[110])
        self.assertEqual((3, [4, 9], {"highway": "service"}), after[115])
        for i in before:
            if i not in (110, 115):
                self.assertEqual(before[i], after[i], i)

    def test_the_rule(self):
        self.assertTrue(D.chosen({1: "road"}, {2: "lot"}))
        self.assertTrue(D.chosen({1: "lot"}, {1: "lot", 2: "lot"}))
        self.assertFalse(D.chosen({1: "road"}, {2: "road"}))
        self.assertFalse(D.chosen({1: "lot"}, {1: "lot"}), "both ends on one and the same way")
        self.assertFalse(D.chosen({}, {2: "lot"}))
        self.assertEqual("lot", D.other_kind({"highway": "service"}))
        self.assertEqual("lot", D.other_kind({"highway": "service", "service": "parking_aisle"}))
        self.assertEqual("road", D.other_kind({"highway": "service", "service": "alley"}))
        self.assertEqual("road", D.other_kind({"highway": "service", "service": "drive-through"}))
        self.assertIsNone(D.other_kind({"highway": "service", "access": "private"}))
        self.assertIsNone(D.other_kind({"highway": "service", "service": "driveway"}))
        self.assertIsNone(D.other_kind({"highway": "footway"}))
        self.assertFalse(D.is_open_driveway({"highway": "service", "service": "driveway", "access": "destination"}))


if __name__ == "__main__":
    unittest.main()
