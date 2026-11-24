"""starpilot/make_device_pack.py: the device folder of comma-pack-format.md section 1 from the part files, and
pack.json as section 3. Standard library only; made-up pack, parts and wheel. Skipped, with the reason,
in a checkout without starpilot/make_device_pack.py (it is not committed yet)."""
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fixtures  # noqa: E402
import common  # noqa: E402

MAKE_DEVICE_PACK = fixtures.REPO / "starpilot" / "make_device_pack.py"
mdp = None
if MAKE_DEVICE_PACK.is_file():
    spec = importlib.util.spec_from_file_location("make_device_pack", MAKE_DEVICE_PACK)
    mdp = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mdp)
ABSENT = "starpilot/make_device_pack.py is not in this checkout (not committed yet): nothing to test"


def run(*args) -> tuple[int, str]:
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
        try:
            rc = mdp.main([str(a) for a in args])
        except SystemExit as e:
            rc = e.code if isinstance(e.code, int) else 1
            out.write(str(e.code))
    return rc, out.getvalue()


@unittest.skipIf(mdp is None, ABSENT)
class DevicePack(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self.tmp = Path(self.d.name)
        self.pack = self.tmp / "pack"
        self.manifest = fixtures.fake_pack(self.pack)
        self.parts = self.tmp / "parts"
        fixtures.fake_parts(self.parts, manifest_path=self.pack / "manifest.json")
        self.wheel = self.tmp / fixtures.WHEEL_NAME
        fixtures.fake_wheel(self.wheel)

    def tearDown(self):
        self.d.cleanup()

    def test_section_1_and_3(self):
        out = self.tmp / "beamline"
        rc, text = run("--pack", self.pack, "--wheel", self.wheel, "--out", out, "--parts", self.parts)
        self.assertEqual(rc, 0, text)
        top = sorted(p.name for p in out.iterdir())
        self.assertEqual(top, ["addresses.sqlite", "mapd", "pack.json", "pylib", "roads", "routing.tar", "search", "search.sqlite"])
        self.assertEqual(sorted(p.name for p in (out / "search").iterdir()), sorted(common.SEARCH_CODE_FILES))
        rts = sorted(p.relative_to(out / "roads").as_posix() for p in (out / "roads").rglob("*.rt"))
        self.assertEqual(len(rts), 3)
        self.assertEqual(sorted({r.split("/")[0] for r in rts}), ["11", "14", "8"])
        self.assertEqual([p.relative_to(out / "mapd").as_posix() for p in (out / "mapd").rglob("*.tar.gz")], ["42/-82.tar.gz"])
        self.assertTrue((out / "pylib" / "valhalla" / "__init__.py").is_file())
        pj = json.loads((out / "pack.json").read_text(encoding="utf-8"))
        self.assertEqual(list(pj), ["format", "id", "name", "pack_version", "osm_snapshot", "valhalla_version", "credit",
                                    "parts", "engine", "mapd"])
        self.assertEqual(pj["format"], "beamline-comma-pack/1")
        self.assertEqual((pj["id"], pj["name"], pj["pack_version"], pj["osm_snapshot"], pj["valhalla_version"]),
                         ("us-pa", "Pennsylvania", 202611211859, "2026-11-20", "3.9.1"))
        self.assertEqual(pj["credit"], fixtures.ADDR_CREDIT)
        self.assertEqual(pj["engine"], fixtures.WHEEL_NAME)
        self.assertEqual(pj["parts"], {
            "routing": self.manifest["parts"]["routing"]["sha256"], "search": self.manifest["parts"]["search"]["sha256"],
            "addresses": self.manifest["parts"]["addresses"]["sha256"],
            "search_code": common.sha256_file(self.parts / "search-code.tar"),
            "roads": common.sha256_file(self.parts / "roads.tar"), "mapd": common.sha256_file(self.parts / "mapd.tar")})
        self.assertEqual(pj["mapd"], {"min_lat": 42, "min_lon": -82, "max_lat": 44, "max_lon": -80,
                                      "source_date": "2026-11-20", "admitted": False})

    def test_only_what_is_installed(self):
        out = self.tmp / "beamline"
        self.assertEqual(run("--pack", self.pack, "--wheel", self.wheel, "--out", out, "--parts", self.parts)[0], 0)
        rc, text = run("--pack", self.pack, "--wheel", self.wheel, "--out", out, "--no-addresses")
        self.assertEqual(rc, 0, text)
        pj = json.loads((out / "pack.json").read_text(encoding="utf-8"))
        self.assertEqual(sorted(pj["parts"]), ["routing", "search", "search_code"])
        self.assertNotIn("mapd", pj)
        for gone in ("roads", "mapd", "addresses.sqlite"):
            self.assertFalse((out / gone).exists(), gone)
        self.assertEqual(sorted(p.name for p in (out / "search").iterdir()), sorted(common.SEARCH_CODE_FILES))

    def test_present_checks_kept(self):
        out = self.tmp / "beamline"
        (self.pack / "search.sqlite").write_bytes(b"SQLite format 3\x00changed")
        rc, text = run("--pack", self.pack, "--wheel", self.wheel, "--out", out)
        self.assertNotEqual(rc, 0)
        self.assertIn("not the file the manifest names", text)
        fixtures.fake_pack(self.pack)
        other = self.tmp / "pyvalhalla-3.8.0-cp312-abi3-manylinux_2_28_aarch64.whl"
        fixtures.fake_wheel(other)
        rc, text = run("--pack", self.pack, "--wheel", other, "--out", out)
        self.assertNotEqual(rc, 0)
        self.assertIn("Valhalla 3.9.1", text)
        rc, text = run("--pack", self.pack, "--wheel", self.wheel, "--out", out, "--mapd", self.parts / "mapd.tar",
                       "--mapd-date", "2026-11-19")
        self.assertNotEqual(rc, 0)
        self.assertIn("one map date", text)

    def test_unsafe_tars_are_refused(self):
        out = self.tmp / "beamline"
        bad = self.tmp / "bad-roads.tar"
        fixtures.tar_with(bad, [("14/1/1.rt", b"14/1/2.rt", "symlink")])
        rc, text = run("--pack", self.pack, "--wheel", self.wheel, "--out", out, "--roads", bad)
        self.assertNotEqual(rc, 0)
        self.assertFalse((out / "roads").exists())
        fixtures.tar_with(bad, [("../escape.rt", b"x", "file")])
        rc, _ = run("--pack", self.pack, "--wheel", self.wheel, "--out", out, "--roads", bad)
        self.assertNotEqual(rc, 0)
        self.assertFalse((self.tmp / "escape.rt").exists())
        code = self.tmp / "bad-code.tar"
        fixtures.tar_with(code, [("query.py", b"x = 1\n", "file")])
        rc, _ = run("--pack", self.pack, "--wheel", self.wheel, "--out", out, "--search-code", code)
        self.assertNotEqual(rc, 0)

    def test_archives_folder_still_works(self):
        out = self.tmp / "beamline"
        common.unpack(self.parts / "mapd.tar", self.tmp / "archives", "mapd")
        rc, text = run("--pack", self.pack, "--wheel", self.wheel, "--out", out, "--mapd", self.tmp / "archives",
                       "--mapd-date", "2026-11-20")
        self.assertEqual(rc, 0, text)
        pj = json.loads((out / "pack.json").read_text(encoding="utf-8"))
        self.assertEqual(pj["mapd"]["min_lat"], 42)
        self.assertNotIn("mapd", pj["parts"])


if __name__ == "__main__":
    unittest.main()
