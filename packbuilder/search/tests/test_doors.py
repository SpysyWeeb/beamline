"""Unit tests of doors.py (the front door of a large building).

    .venv/search/Scripts/python packbuilder/search/tests/test_doors.py

A 60 m x 40 m store at 42 N, its long sides facing north and south; shapely only.
"""
import math
import sys
import unittest
from pathlib import Path

import shapely

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import doors  # noqa: E402

LAT0, LON0 = 42.0, -80.0
KY = 111320.0
KX = KY * math.cos(math.radians(LAT0))


def ll(x: float, y: float) -> tuple[float, float]:
    """(lon, lat) of a point x metres east and y metres north of the store's middle."""
    return (LON0 + x / KX, LAT0 + y / KY)


def box(x0, y0, x1, y1):
    return shapely.Polygon([ll(x0, y0), ll(x1, y0), ll(x1, y1), ll(x0, y1)])


def metres(lon: float, lat: float) -> tuple[float, float]:
    return ((lon - LON0) * KX, (lat - LAT0) * KY)


STORE = box(-30, -20, 30, 20)
LOT_SOUTH = box(-40, -90, 40, -30)       # the customer lot in front (south)
LOT_NORTH_SMALL = box(-10, 25, 10, 35)   # a few staff spaces behind


class DoorsTest(unittest.TestCase):
    def test_kinds_of_entrance(self):
        self.assertEqual("main", doors.entrance_kind({"entrance": "main"}))
        for v in ("yes", "shop", "entrance", "secondary"):
            self.assertEqual("customer", doors.entrance_kind({"entrance": v}), v)
        for v in ("service", "emergency", "exit", "garage", "staircase", "home"):
            self.assertIsNone(doors.entrance_kind({"entrance": v}), v)
        self.assertIsNone(doors.entrance_kind({"entrance": "main", "access": "private"}))
        self.assertIsNone(doors.entrance_kind({"door": "yes"}))

    def test_what_counts_as_customer_parking(self):
        self.assertTrue(doors.parking_counts({"amenity": "parking"}))
        self.assertTrue(doors.parking_counts({"amenity": "parking", "access": "customers"}))
        for extra in ({"access": "private"}, {"access": "no"}, {"parking": "underground"}, {"parking": "multi-storey"}):
            self.assertFalse(doors.parking_counts({"amenity": "parking", **extra}), extra)
        self.assertTrue(doors.aisle_counts({"highway": "service", "service": "parking_aisle"}))
        self.assertFalse(doors.aisle_counts({"highway": "service", "service": "driveway"}))
        self.assertFalse(doors.aisle_counts({"highway": "service", "service": "parking_aisle", "access": "private"}))

    def test_a_shop_is_retail(self):
        self.assertTrue(doors.is_retail({"shop": "supermarket"}))
        self.assertTrue(doors.is_retail({"building": "retail", "amenity": "pharmacy"}))
        self.assertFalse(doors.is_retail({"shop": "vacant"}))
        self.assertFalse(doors.is_retail({"amenity": "school", "building": "school"}))

    def test_only_a_large_building_gets_a_door(self):
        self.assertTrue(doors.is_large(STORE))
        small = box(-10, -8, 10, 8)
        self.assertFalse(doors.is_large(small))
        self.assertIsNone(doors.door(small, [(1, *ll(0, -8), "main")], [LOT_SOUTH], True))

    def test_the_main_entrance_wins(self):
        ents = [(7, *ll(0, 20), "main"), (8, *ll(0, -20), "customer")]
        lon, lat, how = doors.door(STORE, ents, [LOT_SOUTH], True)
        self.assertEqual("main", how)
        self.assertAlmostEqual(20.0, metres(lon, lat)[1], delta=0.5)
        # two main entrances: the one nearest to the customer parking
        ents = [(7, *ll(0, 20), "main"), (9, *ll(10, -20), "main")]
        lon, lat, how = doors.door(STORE, ents, [LOT_SOUTH], True)
        self.assertEqual(("main", -20), (how, round(metres(lon, lat)[1])))
        # two and no parking mapped: the lower node id, so the build gives the same answer every time
        lon, lat, how = doors.door(STORE, ents, [], True)
        self.assertEqual(20, round(metres(lon, lat)[1]))

    def test_a_customer_entrance_when_no_main_one(self):
        one = [(5, *ll(-30, 0), "customer")]
        lon, lat, how = doors.door(STORE, one, [], False)
        self.assertEqual(("entrance", -30), (how, round(metres(lon, lat)[0])))
        two = [(5, *ll(-30, 0), "customer"), (6, *ll(5, -20), "customer")]
        lon, lat, how = doors.door(STORE, two, [LOT_SOUTH], False)
        self.assertEqual(("entrance", -20), (how, round(metres(lon, lat)[1])), "the one by the parking")
        self.assertIsNone(doors.door(STORE, two, [], False), "several and no parking mapped: nothing says which")

    def test_a_shop_without_entrances_faces_its_parking(self):
        lon, lat, how = doors.door(STORE, [], [LOT_SOUTH, LOT_NORTH_SMALL], True)
        x, y = metres(lon, lat)
        self.assertEqual("parking", how)
        self.assertAlmostEqual(-20.0, y, delta=0.5, msg="on the south wall, the side of the large lot")
        self.assertLess(abs(x), 10.0, "about the middle of that wall")
        self.assertIsNone(doors.door(STORE, [], [LOT_SOUTH], False), "not for a school, a church or an office")
        self.assertIsNone(doors.door(STORE, [], [], True), "no parking mapped")
        self.assertIsNone(doors.door(STORE, [], [box(-5, -40, 5, -30)], True), "100 m2 says nothing")
        far = box(-40, -200, 40, -100)
        self.assertIsNone(doors.door(STORE, [], [far], True), "a lot more than 60 m away is another place's")

    def test_parking_aisles_count_where_no_lot_area_is_mapped(self):
        aisles = [shapely.LineString([ll(-35, y), ll(35, y)]) for y in (-40, -58, -76)]
        lon, lat, how = doors.door(STORE, [], [], True, aisles)
        self.assertEqual(("parking", -20), (how, round(metres(lon, lat)[1])))
        self.assertIsNone(doors.door(STORE, [], [], True, []), "no aisle either")
        short = [shapely.LineString([ll(0, -40), ll(10, -40)])]
        self.assertIsNone(doors.door(STORE, [], [], True, short), "10 m of aisle (160 m2) says nothing")

    def test_a_building_with_a_courtyard_or_in_parts(self):
        ring = shapely.Polygon(STORE.exterior.coords, [box(-10, -5, 10, 5).exterior.coords])
        lon, lat, how = doors.door(ring, [], [LOT_SOUTH], True)
        self.assertEqual(("parking", -20), (how, round(metres(lon, lat)[1])), "the outline, never the courtyard")
        parts = shapely.MultiPolygon([box(-30, -20, -2, 20), box(2, -20, 30, 20)])
        lon, lat, how = doors.door(parts, [], [LOT_SOUTH], True)
        self.assertEqual(-20, round(metres(lon, lat)[1]))


if __name__ == "__main__":
    unittest.main()
