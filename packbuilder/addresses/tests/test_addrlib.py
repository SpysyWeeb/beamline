"""Unit tests for normalisation, parsing and lookup (no downloaded data needed).

    python -m unittest discover -s packbuilder/addresses/tests -v
"""
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from addrlib.geo import encode_line, decode_line, interpolate, haversine_m  # noqa: E402
from addrlib.normalize import (street_key, street_core, house_number, place_key, zip_parts,  # noqa: E402
                               highway_alternatives, unit_text, key_variants, fix_type_typo,
                               source_city, is_placeholder_housenumber)
from addrlib.parser import parse  # noqa: E402
from addrlib.lookup import AddressIndex, osa_distance  # noqa: E402


class StreetKeys(unittest.TestCase):
    same = [
        ["N Main St", "North Main Street", "n. main st.", "NORTH MAIN STREET"],
        ["St Clair Ave", "Saint Clair Avenue", "St. Clair Av"],
        ["21st St", "Twenty-First Street", "Twenty First St", "21 st Street"],
        ["1st Ave", "First Avenue"],
        ["State Route 8", "PA 8", "PA Route 8", "SR 8", "SR 0008", "Pennsylvania Route 8", "State Rte 8", "PA-8"],
        ["US 19", "US Route 19", "U.S. Highway 19", "US Hwy 19", "US-19"],
        ["I-79", "I 79", "Interstate 79", "I79", "I- 79"],
        ["Route 8", "Rte 8", "Rt 8", "Rt. 8"],
        ["Valley Drive Extension", "Valley Dr Ext"],
        ["Mt Lebanon Blvd", "Mount Lebanon Boulevard"],
        ["Fort Pitt Blvd", "Ft Pitt Blvd"],
        ["Township Road 312", "T-312", "Twp Rd 312"],
        ["Main Street Extended", "Main St Exd"],
        ["West Thirty-Eighth Street", "W 38th St"],
    ]

    def test_equivalent_forms(self):
        for group in self.same:
            keys = {street_key(s) for s in group}
            self.assertEqual(len(keys), 1, f"{group} -> {keys}")

    def test_saint_vs_street(self):
        self.assertEqual(street_key("St Marys St"), "saint marys st")
        self.assertEqual(street_key("Main St"), "main st")
        self.assertEqual(street_key("Street Rd"), "st rd")      # Street Road (Bucks County)
        self.assertEqual(street_key("Main St N"), "main st n")
        # Saint only at the start (after a directional) or after a saint prefix word
        self.assertEqual(street_key("N St Clair St"), "n saint clair st")
        self.assertEqual(street_key("Mount St Marys Rd"), "mount saint marys rd")
        self.assertEqual(street_key("Main St Exn"), street_key("Main Street Extension"))
        self.assertEqual(street_key("CHURCH ST REAR"), "church st rear")
        self.assertEqual(street_key("7th St Juniata"), "7th st juniata")

    def test_key_variants(self):
        self.assertEqual(fix_type_typo("sreet"), "st")
        self.assertEqual(fix_type_typo("aveune"), "ave")
        self.assertEqual(fix_type_typo("cicrle"), "cir")
        self.assertIsNone(fix_type_typo("main"))
        self.assertEqual(key_variants("state sreet")[0], "state st")  # not 'est sreet' ('state' ~ 'estate')
        self.assertIn("w 8th st", key_variants("w 8 st"))
        self.assertIn("br ave", key_variants("bracnh ave"))       # type word used in the name
        self.assertIn("vly vw dr", key_variants("valely vw dr"))
        self.assertEqual(key_variants("sr 8"), [])
        self.assertEqual(key_variants("main st"), [])

    def test_source_values(self):
        self.assertIsNone(source_city("19428", "PA"))
        self.assertIsNone(source_city("Not stated", "PA"))
        self.assertEqual(source_city("Franklin, PA", "PA"), "Franklin")
        self.assertEqual(source_city("Mount Penn", "PA"), "Mount Penn")
        self.assertTrue(is_placeholder_housenumber("700blk"))
        self.assertFalse(is_placeholder_housenumber("700b"))

    def test_highway_alternatives(self):
        self.assertIn("sr 8", highway_alternatives("rte 8"))
        self.assertIn("rte 8", highway_alternatives("sr 8"))
        self.assertEqual(highway_alternatives("main st"), [])

    def test_core(self):
        self.assertEqual(street_core("n main st"), "main")
        self.assertEqual(street_core("w ritner st"), "ritner")
        self.assertEqual(street_core("ritner st"), "ritner")

    def test_house_numbers(self):
        self.assertEqual(house_number("123"), ("123", 123))
        self.assertEqual(house_number("123 A"), ("123a", 123))
        self.assertEqual(house_number("123-125"), ("123-125", 123))
        self.assertEqual(house_number("123 1/2"), ("123 1/2", 123))
        self.assertEqual(house_number("0123"), ("123", 123))
        self.assertEqual(house_number("abc"), (None, None))

    def test_places_zip_units(self):
        self.assertEqual(place_key("Millcreek Township"), "millcreek")
        self.assertEqual(place_key("City of Erie"), "erie")
        self.assertEqual(place_key("Mt. Lebanon"), "mount lebanon")
        self.assertEqual(place_key("Spring City"), "spring city")
        self.assertEqual(zip_parts("16501-1234"), ("16501", "1234"))
        self.assertEqual(zip_parts("165011234"), ("16501", "1234"))
        self.assertEqual(zip_parts("1650"), (None, None))
        self.assertEqual(unit_text("Apt.", "4B"), "apt 4b")
        self.assertEqual(unit_text("Suite", "200"), "ste 200")


class Parser(unittest.TestCase):
    places = {"erie", "state college", "spring city", "pittsburgh", "gibsonia", "wexford", "lower burrell"}

    def test_units(self):
        self.check("500 Walnut St Apt #4B Erie PA", street_key="walnut st", unit="apt 4b", city_key="erie")
        self.check("500 Walnut Street Unit A-1, Erie", street_key="walnut st", unit="unit a-1", city_key="erie")
        self.check("500 Walnut St, 2nd Floor, Erie", street_key="walnut st", unit="fl 2", city_key="erie")
        self.check("500 Walnut St Rear Erie PA", street_key="walnut st", unit="rear", city_key="erie")
        self.check("626 State St Fl 3 Rm 4 Erie", street_key="state st", unit="fl 3 rm 4", city_key="erie")
        self.check("123 Main St Lower Burrell PA", street_key="main st", unit=None, city_key="lower burrell")
        self.check("100 Hill Side Rd", street_key="hl side rd", unit=None)
        self.check("5040 Trail Side Court 15644", street_key="trl side ct", unit=None, zip="15644")

    def test_odd_orders_and_noise(self):
        self.check("State St 626 Erie", house_number="626", street_key="state st", city_key="erie")
        self.check("Route 8 4500 Gibsonia", house_number="4500", street_key="rte 8", city_key="gibsonia")
        self.check("Erie PA 16501", street=None, city_key="erie", zip="16501")
        self.check("16501", zip="16501", house_number=None)
        self.check("626 State St Erie PA USA", street_key="state st", city_key="erie", state="pa")
        self.check("626 State St, Erie, PA 16501, United States", city_key="erie", zip="16501")
        self.check("123 main st erie pa 1650", street_key="main st", city_key="erie", zip=None, zip_invalid="1650")
        self.check("123 A Main St", house_number="123a", street_key="main st")
        self.check("123 A St Erie", house_number="123", street_key="a st", city_key="erie")
        self.check("123 N Main St", house_number="123", street_key="n main st")

    def check(self, text, **want):
        got = parse(text, self.places)
        for k, v in want.items():
            self.assertEqual(got[k], v, f"{text!r}: {k} = {got[k]!r}, want {v!r}")

    def test_examples(self):
        self.check("123 n main st apt 4, erie pa 16501", house_number="123", street_key="n main st",
                   unit="apt 4", city_key="erie", state="pa", zip="16501")
        self.check("123 N Main St Apt 4 Erie PA 16501", house_number="123", street_key="n main st",
                   unit="apt 4", city_key="erie", zip="16501")
        self.check("123 Main St", house_number="123", street_key="main st", city=None, zip=None)
        self.check("erie pa 16501, 123 main st", house_number="123", street_key="main st", city_key="erie")
        self.check("Erie, PA 16501 123 Main St", house_number="123", street_key="main st", city_key="erie")
        self.check("123 Main St Spring City PA", street_key="main st", city_key="spring city")
        self.check("4500 Route 8 Gibsonia PA 15044", house_number="4500", street_key="rte 8", city_key="gibsonia")
        self.check("10450 US-19 Wexford, PA", street_key="us 19", city_key="wexford")
        self.check("1600 W 26th St #4 Erie PA 16508-1234", unit="# 4", zip="16508", zip4="1234")
        self.check("123 Main St Rear, Erie", unit="rear", city_key="erie")
        self.check("123 1/2 Elm St", house_number="123 1/2", street_key="elm st")
        self.check("main st 123", house_number="123", street_key="main st")
        self.check("123 St Clair Ave Erie", street_key="saint clair ave", city_key="erie")
        self.check("12345 Main St", house_number="12345", zip=None)
        self.check("100 Park Ridge Rd State College PA", street_key="park rdg rd", city_key="state college")


class Lookup(unittest.TestCase):
    def setUp(self):
        from step_build import SCHEMA, INDEXES
        self.tmp = tempfile.TemporaryDirectory()
        db = Path(self.tmp.name) / "t.sqlite"
        con = sqlite3.connect(db)
        con.executescript(SCHEMA)
        con.execute("INSERT INTO localities VALUES (1,'16501','Erie','erie','source','Erie','erie','42049')")
        con.execute("INSERT INTO localities VALUES (2,'16801','State College','state college','source',"
                    "'State College','state college','42027')")
        con.execute("INSERT INTO streets VALUES (1,'North Main Street','n main st','main',1,1,0,42100000,-80000000)")
        con.execute("INSERT INTO streets VALUES (2,'N Main St','n main st','main',1,0,1,42100000,-80000000)")
        con.execute("INSERT INTO streets VALUES (3,'Main St','main st','main',2,1,0,40790000,-77860000)")
        con.execute("INSERT INTO addresses VALUES (1,1,'123',123,NULL,42100000,-80000000,'osm','n1','source')")
        con.execute("INSERT INTO addresses VALUES (2,3,'123',123,NULL,40790000,-77860000,'osm','n2','source')")
        con.execute("INSERT INTO edges VALUES (?,?)", (10, encode_line([(-80.001, 42.101), (-80.001, 42.111)])))
        con.execute("INSERT INTO ranges VALUES (1,2,10,'L',200,298,200,298,'E','tiger')")
        con.execute("INSERT INTO places VALUES ('erie','Erie','postal','16501','42049',1)")
        con.execute("INSERT INTO places VALUES ('state college','State College','postal','16801','42027',1)")
        con.execute("INSERT INTO meta VALUES ('state','PA')")
        con.executescript(INDEXES)
        con.commit()
        con.close()
        self.idx = AddressIndex(db)

    def tearDown(self):
        self.idx.con.close()
        self.tmp.cleanup()

    def test_point(self):
        r = self.idx.geocode("123 n main st, erie pa 16501")
        self.assertEqual(r[0]["accuracy"], "point")
        self.assertAlmostEqual(r[0]["lat"], 42.1, places=5)

    def test_locality_picks_right_town(self):
        r = self.idx.geocode("123 main st state college pa")
        self.assertAlmostEqual(r[0]["lat"], 40.79, places=5)

    def test_interpolation(self):
        r = self.idx.geocode("250 n main st erie pa")
        self.assertEqual(r[0]["accuracy"], "interpolated")
        self.assertTrue(42.101 < r[0]["lat"] < 42.111)

    def test_typo(self):
        r = self.idx.geocode("123 n mian st, erie pa 16501")
        self.assertEqual(r[0]["match"], "fuzzy_key")
        self.assertEqual(r[0]["accuracy"], "point")

    def test_type_typo(self):
        r = self.idx.geocode("123 n main sreet, erie pa 16501")
        self.assertEqual(r[0]["accuracy"], "point")
        self.assertEqual(r[0]["match"], "key_variant")

    def test_huge_number_and_state(self):
        r = self.idx.geocode("99999999999999999999 n main st erie pa")
        self.assertEqual(r[0]["accuracy"], "street")
        self.assertIn("house_number_rejected", r[0])
        r = self.idx.geocode("123 n main st erie oh")
        self.assertTrue(r[0]["state_mismatch"])
        self.assertFalse(r[0]["locality_match"])
        self.assertNotIn("state_mismatch", self.idx.geocode("123 n main st erie pa")[0])

    def test_place_only_input(self):
        self.assertEqual(self.idx.geocode("Erie PA 16501"), [])

    def test_variant_files(self):
        """Points-only and ranges-only packs open and answer from what they have."""
        import shutil
        src = Path(self.tmp.name) / "t.sqlite"
        for name, sql, q, acc in (("p.sqlite", "DROP TABLE ranges; DROP TABLE edges;", "123 n main st erie pa", "point"),
                                  ("r.sqlite", "DROP VIEW address_rows; DROP TABLE addresses;", "250 n main st erie pa",
                                   "interpolated")):
            dst = Path(self.tmp.name) / name
            shutil.copyfile(src, dst)
            con = sqlite3.connect(dst)
            con.executescript(sql)
            con.close()
            idx = AddressIndex(dst)
            try:
                self.assertEqual(idx.geocode(q)[0]["accuracy"], acc)
            finally:
                idx.con.close()

    def test_osa(self):
        self.assertEqual(osa_distance("main", "mian", 2), 1)
        self.assertEqual(osa_distance("main", "maine", 2), 1)
        self.assertEqual(osa_distance("main", "elm", 1), 2)


class BuildHelpers(unittest.TestCase):
    def test_street_values(self):
        from step_build import street_values
        self.assertEqual(street_values("West Lancaster Avenue;Lancaster Avenue"),
                         ["West Lancaster Avenue", "Lancaster Avenue"])
        self.assertEqual(street_values("Walnut St, Suite"), ["Walnut St"])
        self.assertEqual(street_values("261 Main St Blandon PA 19510"), ["Main St"])
        self.assertEqual(street_values("North Main Street"), ["North Main Street"])


class Geo(unittest.TestCase):
    def test_roundtrip_and_interpolate(self):
        line = [(-80.0, 42.0), (-80.0, 42.01)]
        self.assertEqual(decode_line(encode_line(line)), line)
        lon, lat = interpolate(line, 0.5)
        self.assertAlmostEqual(lat, 42.005, places=6)
        lon_l, _ = interpolate(line, 0.5, "L", 10)
        self.assertLess(lon_l, -80.0)  # heading north, left is west
        self.assertAlmostEqual(haversine_m(-80, 42, -80, 42.01), 1111.95, delta=1)


if __name__ == "__main__":
    unittest.main()
