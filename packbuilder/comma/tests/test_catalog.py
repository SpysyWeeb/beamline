"""catalog.py: comma.json as comma-pack-format.md section 2, written beside the phone's catalog.json from a
made-up pack; the copyright sign as UTF-8 in both catalogs; the pinned engine. Standard library only."""
import contextlib
import datetime
import hashlib
import io
import json
import os
import shutil
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fixtures  # noqa: E402
import catalog  # noqa: E402  (puts packbuilder/catalog on the path: the phone catalog's writer)
import build_catalog  # noqa: E402
import common  # noqa: E402

EPOCH = str(common.utc_epoch(fixtures.CREATED))     # 2026-11-21T18:59:00Z = 1795287540


def quiet(fn, *a):
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return fn(*a)


class CommaCatalog(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self.tmp = Path(self.d.name)
        self.old_epoch = os.environ.get("SOURCE_DATE_EPOCH")
        os.environ["SOURCE_DATE_EPOCH"] = EPOCH
        self.manifest = fixtures.fake_pack(self.tmp / "packs" / "us-pa")
        fixtures.fake_parts(self.tmp / "comma" / "us-pa", manifest_path=self.tmp / "packs" / "us-pa" / "manifest.json")
        self.site = self.tmp / "site"
        self.assertEqual(quiet(build_catalog.main, ["--out", str(self.site), "--pack", str(self.tmp / "packs" / "us-pa"),
                                                    "--check"]), 0)

    def tearDown(self):
        if self.old_epoch is None:
            os.environ.pop("SOURCE_DATE_EPOCH", None)
        else:
            os.environ["SOURCE_DATE_EPOCH"] = self.old_epoch
        self.d.cleanup()

    def write(self, *extra) -> int:
        return quiet(catalog.main, ["--site", str(self.site), "--parts", str(self.tmp / "comma"), "--check", *extra])

    def test_the_contracts_shape(self):
        self.assertEqual(self.write(), 0)
        raw = (self.site / "catalog" / "comma.json").read_bytes()
        cat = catalog.parse(raw, "https://github.com/o/r/releases/download/catalog/comma.json")
        phone = json.loads((self.site / "catalog" / "catalog.json").read_text(encoding="utf-8"))
        self.assertEqual(list(cat), ["format", "serial", "generated_utc", "licence", "engines", "packs"])
        self.assertEqual(cat["format"], "beamline-comma-catalog/1")
        self.assertEqual(cat["serial"], 20261121185900)
        self.assertEqual(cat["generated_utc"], "2026-11-21T18:59:00Z")
        self.assertEqual(cat["licence"], phone["licence"])
        self.assertEqual(len(cat["engines"]), 1)
        e = cat["engines"][0]
        self.assertEqual(list(e), ["valhalla_version", "machine", "python", "file", "url", "bytes", "sha256"])
        self.assertEqual((e["valhalla_version"], e["machine"], e["python"]), ("3.9.1", "aarch64", "cp312"))
        self.assertEqual(e["file"], fixtures.WHEEL_NAME)
        self.assertEqual(e["bytes"], 34736379)
        pin = common.load_pins()["engines"][0]["blake2b_256"]
        self.assertEqual(e["url"], "https://files.pythonhosted.org/packages/" + "/".join((pin[:2], pin[2:4], pin[4:], e["file"])))
        (p,) = cat["packs"]
        self.assertEqual(list(p), ["id", "state", "name", "pack_version", "osm_snapshot", "bounds", "valhalla_version",
                                   "credit", "parts"])
        self.assertEqual((p["id"], p["state"], p["name"], p["pack_version"], p["osm_snapshot"], p["valhalla_version"]),
                         ("us-pa", "PA", "Pennsylvania", 202611211859, "2026-11-20", "3.9.1"))
        self.assertEqual(p["credit"], fixtures.ADDR_CREDIT)       # the contract's example credit, joined from the parts
        self.assertEqual([q["name"] for q in p["parts"]], ["routing", "search", "addresses", "search_code", "roads", "mapd"])
        self.assertEqual({q["name"]: q["optional"] for q in p["parts"]},
                         {"routing": False, "search": False, "addresses": True, "search_code": False, "roads": False, "mapd": True})
        shared = {q["name"]: q for q in phone["packs"][0]["parts"]}
        for q in p["parts"]:
            keys = ["name", "file", "optional", "bytes", "sha256", "transfer"] + (["mapd"] if q["name"] == "mapd" else [])
            self.assertEqual(list(q), keys)
            if q["name"] in ("routing", "search", "addresses"):       # the very same release files as the phone's
                self.assertEqual(q["transfer"], shared[q["name"]]["transfer"])
                self.assertEqual((q["bytes"], q["sha256"]), (shared[q["name"]]["bytes"], shared[q["name"]]["sha256"]))
            else:
                src = self.tmp / "comma" / "us-pa" / q["file"]
                self.assertEqual((q["bytes"], q["sha256"]), (src.stat().st_size, common.sha256_file(src)))
                self.assertTrue(q["transfer"]["path"].startswith("../us-pa-202611211859/"))
        mapd = next(q for q in p["parts"] if q["name"] == "mapd")
        self.assertEqual(mapd["mapd"], {"min_lat": 42, "min_lon": -82, "max_lat": 44, "max_lon": -80, "source_date": "2026-11-20"})
        roads = next(q for q in p["parts"] if q["name"] == "roads")
        self.assertEqual(roads["transfer"]["encoding"], "identity")         # under 64 KiB: sent as it is
        # the files a release upload takes: all in the pack's release folder
        rel = self.site / "us-pa-202611211859"
        for q in p["parts"]:
            f = (self.site / "catalog" / q["transfer"]["path"]).resolve()
            self.assertEqual(f.parent, rel.resolve())
            self.assertEqual(hashlib.sha256(f.read_bytes()).hexdigest(), q["transfer"]["sha256"])

    def test_copyright_sign_is_utf8_in_both_catalogs(self):
        self.assertEqual(self.write(), 0)
        for name in ("catalog.json", "comma.json"):
            raw = (self.site / "catalog" / name).read_bytes()
            self.assertIn("Map data © OpenStreetMap".encode("utf-8"), raw, name)
            self.assertNotIn(b"\xc3\x82\xc2\xa9", raw, name)                 # "©" read as Latin-1 and encoded again
            self.assertNotIn(b"\\u00a9", raw, name)
            self.assertFalse(raw.startswith(b"\xef\xbb\xbf"), name)
            self.assertNotIn(b"\r\n", raw, name)
        man = (self.site / "us-pa-202611211859" / "manifest.json").read_bytes()
        self.assertNotIn(b"\xc3\x82\xc2\xa9", man)

    def test_same_inputs_same_bytes_and_reuse(self):
        self.assertEqual(self.write(), 0)
        first = (self.site / "catalog" / "comma.json").read_bytes()
        table = json.loads((self.site / "us-pa-202611211859" / "comma-transfer.json").read_text(encoding="utf-8"))
        self.assertEqual(sorted(table), ["mapd.tar", "roads.tar", "search-code.tar"])
        os.environ["SOURCE_DATE_EPOCH"] = str(int(EPOCH) + 3600)
        self.assertEqual(self.write(), 0)                                   # nothing changed: left as it is
        self.assertEqual((self.site / "catalog" / "comma.json").read_bytes(), first)
        other = self.tmp / "site2"
        shutil.copytree(self.site, other)
        (other / "catalog" / "comma.json").unlink()
        os.environ["SOURCE_DATE_EPOCH"] = EPOCH
        self.assertEqual(quiet(catalog.main, ["--site", str(other), "--parts", str(self.tmp / "comma")]), 0)
        self.assertEqual((other / "catalog" / "comma.json").read_bytes(), first)

    def test_serial_only_grows_and_stays_a_time(self):
        self.assertEqual(self.write(), 0)
        path = self.site / "catalog" / "comma.json"
        cat = json.loads(path.read_text(encoding="utf-8"))
        cat["serial"] = 20261121235959                   # after the build time (18:59:00), not ahead of the clock
        cat["packs"] = []
        path.write_text(json.dumps(cat), encoding="utf-8")
        self.assertEqual(self.write(), 0)
        # the second after it, across midnight: 20261121235960 would be no time at all
        self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["serial"], 20261122000000)

    def test_a_serial_ahead_of_the_clock_is_never_written(self):
        self.assertEqual(self.write(), 0)
        path = self.site / "catalog" / "comma.json"
        cat = json.loads(path.read_text(encoding="utf-8"))
        cat["serial"] = int((datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=3))
                            .strftime("%Y%m%d%H%M%S"))
        cat["packs"] = []
        before = (json.dumps(cat) + "\n").encode()
        path.write_bytes(before)
        self.assertEqual(self.write(), 1)                # the next serial would be ahead too: refused
        self.assertEqual(path.read_bytes(), before)      # and nothing written
        self.assertEqual(quiet(catalog.main, ["--site", str(self.site), "--check-only"]), 1)
        for bad in (20261121185960, 20260230000000, "20261121185900"):
            cat["serial"] = bad
            path.write_text(json.dumps(cat), encoding="utf-8")
            self.assertEqual(self.write(), 1, bad)

    def test_serial_rule(self):
        now = datetime.datetime(2026, 11, 22, 9, 42, tzinfo=datetime.timezone.utc)
        catalog.check_serial(20261122094200, now)
        catalog.check_serial(20261124094200, now)        # two days ahead: still taken
        catalog.check_serial(19991231235959, now)        # any time in the past
        for bad in (20261124094201, 20261122094260, 20261322000000, 20261100000000, 2026112209420, 202611220942000,
                    -20261122094200, True, "20261122094200", 20261122246000):
            with self.assertRaises(ValueError, msg=repr(bad)):
                catalog.check_serial(bad, now)
        self.assertEqual(catalog.next_serial(now, None), 20261122094200)
        self.assertEqual(catalog.next_serial(now, 20261122094200), 20261122094201)
        self.assertEqual(catalog.next_serial(now, 20261231235959), 20270101000000)
        self.assertEqual(catalog.next_serial(now, 20261101000000), 20261122094200)
        with self.assertRaises(ValueError):
            catalog.next_serial(now, 20261122094299)

    def test_a_missing_or_broken_part_refuses_the_run(self):
        (self.tmp / "comma" / "us-pa" / "roads.tar").unlink()
        self.assertEqual(self.write(), 1)
        self.assertFalse((self.site / "catalog" / "comma.json").exists())
        fixtures.tar_with(self.tmp / "comma" / "us-pa" / "roads.tar", [("../14/1/1.rt", b"x", "file")])
        self.assertEqual(self.write(), 1)
        fixtures.fake_roads_tar(self.tmp / "comma" / "us-pa" / "roads.tar")
        fixtures.fake_mapd_tar(self.tmp / "comma" / "us-pa" / "mapd.tar", [-80.2, 42.0, -78.0, 42.2], self.tmp / "w2")
        self.assertEqual(self.write(), 1)                                   # mapd.tar of another box

    def test_parse_refusals(self):
        self.assertEqual(self.write(), 0)
        url = "https://github.com/o/r/releases/download/catalog/comma.json"
        good = json.loads((self.site / "catalog" / "comma.json").read_text(encoding="utf-8"))

        def broken(change):
            c = json.loads(json.dumps(good))
            change(c)
            with self.assertRaises(ValueError):
                catalog.parse(json.dumps(c, ensure_ascii=False).encode("utf-8"), url)
        broken(lambda c: c.update(format="beamline-comma-catalog/2"))
        broken(lambda c: c["packs"][0]["parts"][0]["transfer"].update(path="../../elsewhere/routing.tar.gz"))
        broken(lambda c: c["packs"][0]["parts"].pop(4))                              # no roads
        broken(lambda c: c["packs"][0]["parts"][5]["mapd"].update(min_lat=39))
        broken(lambda c: c["engines"][0].update(sha256="x" * 64))
        broken(lambda c: c["engines"][0].update(url="http://files.pythonhosted.org/" + c["engines"][0]["file"]))
        broken(lambda c: c["packs"][0].update(valhalla_version="3.10.0"))           # no engine for it
        broken(lambda c: c["licence"].update(notice="Map data Â© OpenStreetMap contributors"))
        broken(lambda c: c.update(serial=20261121185960))                          # not a time
        broken(lambda c: c.update(serial=int((datetime.datetime.now(datetime.timezone.utc)
                                              + datetime.timedelta(days=2, minutes=5)).strftime("%Y%m%d%H%M%S"))))

    def test_a_search_code_tar_with_unpinned_code_is_refused(self):
        """comma.json never names a search-code.tar whose code is not the pinned release."""
        part = self.tmp / "comma" / "us-pa" / "search-code.tar"
        with tarfile.open(part) as t:
            ms = [(m.name, t.extractfile(m).read() + (b"# edited\n" if m.name == "query.py" else b""), "file")
                  for m in t.getmembers()]
        fixtures.tar_with(part, ms)
        self.assertEqual(self.write(), 1)
        self.assertFalse((self.site / "catalog" / "comma.json").exists())

    @unittest.skipUnless((fixtures.REPO / "data" / "starpilot" / "wheels" / fixtures.WHEEL_NAME).is_file(),
                         "the aarch64 wheel is not downloaded here")
    def test_pinned_engine_is_the_wheel_here(self):
        """The wheel downloaded on this desk has the pinned size and SHA-256, and its BLAKE2b-256 is the
        folder path of the pinned PyPI url (PyPI files a wheel under its BLAKE2b-256)."""
        wheel = fixtures.REPO / "data" / "starpilot" / "wheels" / fixtures.WHEEL_NAME
        e = catalog.engines()[0]
        self.assertEqual((wheel.stat().st_size, common.sha256_file(wheel)), (e["bytes"], e["sha256"]))
        b2 = hashlib.blake2b(wheel.read_bytes(), digest_size=32).hexdigest()
        self.assertTrue(e["url"].endswith(f"/{b2[:2]}/{b2[2:4]}/{b2[4:]}/{fixtures.WHEEL_NAME}"))

    def test_a_pin_without_a_digest_is_refused(self):
        pins = common.load_pins()
        pins["engines"][0]["blake2b_256"] = pins["engines"][0]["blake2b_256"][:60]
        real = common.load_pins
        common.load_pins = lambda: pins
        try:
            with self.assertRaises(common.Refused):
                catalog.engines()
        finally:
            common.load_pins = real


if __name__ == "__main__":
    unittest.main()
