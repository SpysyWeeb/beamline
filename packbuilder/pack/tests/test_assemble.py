"""Tests of packbuilder/pack/assemble.py and android/scripts/read_pack.py on tiny made-up inputs.

    python -m unittest discover packbuilder/pack/tests
"""
import hashlib
import io
import json
import sqlite3
import sys
import tarfile
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "packbuilder" / "pack"))
sys.path.insert(0, str(REPO / "android" / "scripts"))
import assemble  # noqa: E402
import read_pack  # noqa: E402

FP = "a" * 64
OSM = "© OpenStreetMap contributors"


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


class Inputs:
    """One made-up set of builder outputs for PA in a temporary folder."""

    def __init__(self, root: Path, map_snapshot="2026-11-14", search_fp=FP, addr_fp=FP, map_sha=None):
        self.root = root
        pm = b"PMTiles\x03" + bytes(200)
        self.map = root / "pa-261114-z14.pmtiles"
        self.map.write_bytes(pm)
        info = {"schema": "protomaps-basemap", "schema_version": "4.15.2", "min_zoom": 0, "max_zoom": 14,
                "tile_type": "mvt", "schema_extensions": {"lanes_minzoom": 14, "top_zoom": 14},
                "clip": {"file": "data/map/work/data/census/tl_2026_us_state.zip", "sha256": "b" * 64, "state": "PA"},
                "osm_snapshot": map_snapshot, "bounds": [-80.5, 39.7, -74.7, 42.5],
                "bytes": len(pm), "sha256": map_sha or sha(pm)}
        self.map.with_suffix(".map.json").write_text(json.dumps(info), encoding="utf-8")
        self.routing = root / "routing"
        self.routing.mkdir()
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as t:
            data = bytes(range(64)) * 20
            ti = tarfile.TarInfo("index.bin")
            ti.size = len(data)
            t.addfile(ti, io.BytesIO(data))
        tar = buf.getvalue()
        (self.routing / "routing-pa-261114.tar").write_bytes(tar)
        (self.routing / "manifest.json").write_text(json.dumps({
            "format": "beamline-routing-pack/1", "state": "pa", "snapshot": "261114",
            "graph_build": "261114-pa-oh-v3.9.1-b53087", "valhalla_version": "3.9.1",
            "tar": "routing-pa-261114.tar", "tar_bytes": len(tar), "tar_sha256": sha(tar), "tiles": []}), encoding="utf-8")
        self.search = root / "pa-search.sqlite"
        self.db(self.search, {"schema_version": "1", "state": "PA", "addr_key_table_version": "2",
                              "addr_key_fingerprint": search_fp, "ranking_sha256": "c" * 64,
                              "osm_source": "data/osm/pennsylvania-261114.osm.pbf"}, rows=50)
        self.addresses = root / "pa-addresses-slim.sqlite"
        self.db(self.addresses, {"schema_version": "1", "state": "PA", "normalisation_table_version": "2",
                                 "key_fingerprint": addr_fp, "variant": "slim (device file)",
                                 "osm_extract": "pennsylvania-261114.osm.pbf",
                                 "credit": f"{OSM}; U.S. Census Bureau TIGER/Line; USDOT National Address Database"}, rows=500)

    @staticmethod
    def db(p: Path, meta: dict, rows: int):
        con = sqlite3.connect(p)
        con.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        con.executemany("INSERT INTO meta VALUES (?,?)", list(meta.items()))
        con.execute("CREATE TABLE filler(x TEXT)")
        con.executemany("INSERT INTO filler VALUES (?)", [("x" * 100,)] * rows)
        con.commit()
        con.close()

    def src(self):
        return {"map": str(self.map), "routing": str(self.routing), "search": str(self.search),
                "addresses": str(self.addresses)}


CREATED = datetime(2026, 11, 16, 4, 5, tzinfo=timezone.utc)


class AssembleTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.out = self.root / "out"

    def tearDown(self):
        self.tmp.cleanup()

    def run_ok(self, inputs, with_addresses=True):
        return assemble.assemble("pa", inputs.src(), self.out, with_addresses, True, True, CREATED)

    def test_archive_and_folder_are_format_3_and_verify(self):
        r = self.run_ok(Inputs(self.root))
        tar = self.out / "us-pa-2026-11-14.tar"
        self.assertTrue(tar.is_file())
        m = read_pack.read_manifest(tar)
        self.assertEqual(3, m["manifest_version"])
        self.assertEqual(202611160405, m["pack_version"])
        self.assertEqual(202611160405, r["pack_version"])
        self.assertEqual("2026-11-16T04:05:00Z", m["created_utc"])
        self.assertEqual([], read_pack.problems(m))
        self.assertTrue(read_pack.verify(tar, m))
        self.assertTrue(read_pack.verify(self.out / "us-pa", m))
        self.assertEqual(["map", "routing", "routing_index", "search", "addresses"], list(m["parts"]))
        self.assertEqual("tl_2026_us_state.zip", m["parts"]["map"]["clip"]["file"], "a file name, no local path")
        self.assertEqual({"lanes_minzoom": 14, "top_zoom": 14}, m["parts"]["map"]["schema_extensions"])
        self.assertEqual(FP, m["parts"]["search"]["addr_key_fingerprint"])
        self.assertEqual(FP, m["parts"]["addresses"]["key_fingerprint"])
        self.assertEqual("slim", m["parts"]["addresses"]["variant"])
        self.assertEqual(OSM, m["attribution"])
        self.assertEqual(f"{OSM}; U.S. Census Bureau TIGER/Line; USDOT National Address Database", read_pack.credit(m))
        self.assertEqual(OSM, read_pack.credit(m, skipped=("addresses",)))
        # manifest first, then the parts from the smallest to the largest
        with tarfile.open(tar) as t:
            members = t.getmembers()
        self.assertEqual("manifest.json", members[0].name)
        sizes = [e.size for e in members[1:]]
        self.assertEqual(sorted(sizes), sizes)
        self.assertTrue(all(e.isfile() and "/" not in e.name for e in members))
        # the routing index is the routing builder's manifest, byte for byte
        self.assertEqual((self.root / "routing" / "manifest.json").read_bytes(), (self.out / "us-pa" / "routing.json").read_bytes())

    def test_without_house_numbers(self):
        self.run_ok(Inputs(self.root), with_addresses=False)
        m = read_pack.read_manifest(self.out / "us-pa")
        self.assertNotIn("addresses", m["parts"])
        self.assertEqual([], read_pack.problems(m))
        self.assertEqual(OSM, read_pack.credit(m))

    def test_different_fingerprints_are_refused(self):
        with self.assertRaisesRegex(assemble.Refused, "different street keys"):
            self.run_ok(Inputs(self.root, addr_fp="d" * 64))
        self.assertFalse((self.out / "us-pa").exists())

    def test_different_snapshots_are_refused(self):
        with self.assertRaisesRegex(assemble.Refused, "not from one OpenStreetMap snapshot"):
            self.run_ok(Inputs(self.root, map_snapshot="2026-11-21"))

    def test_a_file_that_differs_from_its_record_is_refused(self):
        with self.assertRaisesRegex(assemble.Refused, "its record says"):
            self.run_ok(Inputs(self.root, map_sha="e" * 64))

    def test_read_pack_refuses_version_2(self):
        m = {"manifest_version": 2, "parts": {}}
        self.assertEqual(["manifest_version 2: an old pack format; rebuild the pack"], read_pack.problems(m))


if __name__ == "__main__":
    unittest.main()
