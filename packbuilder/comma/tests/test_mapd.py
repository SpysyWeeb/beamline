"""mapd.py: the box and cell names, the group archives, the checks, Mapd's preparation, setup's pinned Go
(the download faked); and, on Linux
with MAPD_BIN naming a built `mapd` (mapd.py setup), the whole build of a made-up extract admitted by
Mapd itself. The preparation test needs pyosmium (.venv/map)."""
import contextlib
import gzip
import hashlib
import importlib.util
import io
import json
import os
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fixtures  # noqa: E402
import common  # noqa: E402
import mapd  # noqa: E402

HAVE_OSMIUM = importlib.util.find_spec("osmium") is not None
MAPD_BIN = os.environ.get("MAPD_BIN")

# Around Erie, PA: a residential street, a primary road, a footway, a service road, a building outline
OSM_XML = """<?xml version='1.0' encoding='UTF-8'?>
<osm version="0.6" generator="beamline-test">
  <node id="1" version="1" lat="42.1200" lon="-80.0900"/>
  <node id="2" version="1" lat="42.1210" lon="-80.0880"/>
  <node id="3" version="1" lat="42.1220" lon="-80.0860"/>
  <node id="4" version="1" lat="42.1100" lon="-80.0700"/>
  <node id="5" version="1" lat="42.1150" lon="-80.0650"/>
  <node id="6" version="1" lat="42.1300" lon="-80.1000"/>
  <node id="7" version="1" lat="42.1310" lon="-80.1010"/>
  <way id="10" version="1"><nd ref="1"/><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/><tag k="name" v="Test Street"/><tag k="maxspeed" v="25 mph"/></way>
  <way id="11" version="1"><nd ref="4"/><nd ref="5"/><tag k="highway" v="primary"/><tag k="maxspeed" v="45 mph"/></way>
  <way id="12" version="1"><nd ref="6"/><nd ref="7"/><tag k="highway" v="footway"/></way>
  <way id="13" version="1"><nd ref="1"/><nd ref="4"/><tag k="highway" v="service"/></way>
  <way id="14" version="1"><nd ref="5"/><tag k="highway" v="residential"/></way>
  <way id="15" version="1"><nd ref="6"/><nd ref="7"/><nd ref="1"/><nd ref="6"/><tag k="building" v="yes"/></way>
</osm>
"""


class BoxAndNames(unittest.TestCase):
    def test_pennsylvania_box(self):
        box = common.mapd_box(fixtures.PA_BOUNDS)
        self.assertEqual(box, {"min_lat": 38, "min_lon": -82, "max_lat": 44, "max_lon": -74})
        self.assertEqual(len(common.mapd_groups(box)), 12)
        self.assertEqual(common.mapd_groups(box)[:2], [(38, -82), (38, -80)])

    def test_edges(self):
        self.assertEqual(common.mapd_box([-80.5, 40.0, -78.0, 42.0]), {"min_lat": 40, "min_lon": -82, "max_lat": 42, "max_lon": -78})
        self.assertEqual(common.mapd_box([0.5, -1.5, 1.0, -0.5]), {"min_lat": -2, "min_lon": 0, "max_lat": 0, "max_lon": 2})
        with self.assertRaises(common.Refused):
            common.mapd_box([-74, 42, -80, 39])

    def test_cells_are_mapds_names(self):
        cells = common.mapd_cells(40, -76)
        self.assertEqual(len(cells), 64)
        # names seen in Mapd's own output for this group (GenerateBoundsFileName, "%f_%f_%f_%f")
        self.assertIn("40.000000_-74.250000_40.250000_-74.000000", cells)
        self.assertIn("41.750000_-76.000000_42.000000_-75.750000", cells)
        self.assertEqual(len(set(cells)), 64)


class Archives(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self.tmp = Path(self.d.name)
        self.box = common.mapd_box([-80.5, 41.5, -79.5, 42.5])   # 4 groups
        fixtures.fake_offline(self.tmp / "offline", self.box)

    def tearDown(self):
        self.d.cleanup()

    def test_pack_and_verify(self):
        mapd.check_generated(self.tmp / "offline", self.box)
        mapd.pack_groups(self.tmp / "offline", self.tmp / "archives", self.box, common.utc_epoch("2026-11-20"))
        mapd.write_outer(self.tmp / "archives", self.tmp / "mapd.tar", self.box, common.utc_epoch("2026-11-20"))
        rep = mapd.verify(self.tmp / "mapd.tar", self.box, None)
        self.assertEqual(rep["groups"], 4)
        with tarfile.open(self.tmp / "mapd.tar") as t:
            names = [m.name for m in t.getmembers()]
            self.assertEqual(names, ["40/-82.tar.gz", "40/-80.tar.gz", "42/-82.tar.gz", "42/-80.tar.gz"])
            group = t.extractfile("42/-82.tar.gz").read()
        self.assertEqual(group[4:8], b"\x00\x00\x00\x00")                # gzip time 0
        self.assertEqual(group[3] & 0x08, 0)                              # no file name
        with tarfile.open(fileobj=io.BytesIO(gzip.decompress(group))) as g:
            inner = g.getmembers()
        self.assertEqual(len(inner), 64)
        self.assertTrue(all(m.isreg() and m.name.startswith("offline/42/-82/") for m in inner))
        # the same input gives the same bytes
        mapd.pack_groups(self.tmp / "offline", self.tmp / "archives2", self.box, common.utc_epoch("2026-11-20"))
        mapd.write_outer(self.tmp / "archives2", self.tmp / "mapd2.tar", self.box, common.utc_epoch("2026-11-20"))
        self.assertEqual((self.tmp / "mapd.tar").read_bytes(), (self.tmp / "mapd2.tar").read_bytes())
        # another box is refused
        with self.assertRaises(common.Refused):
            mapd.verify(self.tmp / "mapd.tar", common.mapd_box([-80.5, 41.5, -77.5, 42.5]), None)

    def test_generated_tree_checks(self):
        cell = common.mapd_cells(42, -82)[5]
        (self.tmp / "offline" / "42" / "-82" / cell).unlink()
        with self.assertRaises(common.Refused):
            mapd.check_generated(self.tmp / "offline", self.box)
        fixtures.fake_offline(self.tmp / "offline", self.box, extra="42/-82/stray")
        with self.assertRaises(common.Refused):
            mapd.check_generated(self.tmp / "offline", self.box)

    def test_group_checks(self):
        cells = common.mapd_cells(42, -82)

        def group(members, extra=b"", twice=False):
            buf = io.BytesIO()
            with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as t:
                for name, kind in members:
                    ti = tarfile.TarInfo(name)
                    if kind == "dir":
                        ti.type = tarfile.DIRTYPE
                        t.addfile(ti)
                    else:
                        ti.size = 3
                        t.addfile(ti, io.BytesIO(b"abc"))
            data = gzip.compress(buf.getvalue() + extra, mtime=0)
            return data + (gzip.compress(b"\0" * 512, mtime=0) if twice else b"")
        good = [(f"offline/42/-82/{c}", "file") for c in cells]
        self.assertEqual(mapd.check_group(group(good), 42, -82), 64 * 3)
        bad = {
            "a cell missing": group(good[1:]),
            "another group's cell": group(good[1:] + [(f"offline/42/-80/{cells[0]}", "file")]),
            "a folder": group([("offline/42/-82", "dir")] + good),
            "a stray file": group(good + [("offline/42/-82/x/y", "file")]),
            "two gzip members": group(good, twice=True),
            "content after the end": group(good, extra=b"\0" * 1024 + b"junk"),
        }
        for name, data in bad.items():
            with self.assertRaises(common.Refused, msg=name):
                mapd.check_group(data, 42, -82)


class GoPin(unittest.TestCase):
    """setup's Go: installed only from an archive whose size and SHA-256 pins.json pins for the
    platform; go.dev's list is never read. No network: the download is faked and urlopen refuses."""

    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self.tmp = Path(self.d.name)
        self.saved = {k: getattr(mapd, k) for k in ("go_platform", "go_version_of", "fetch")}
        self.urlopen = mapd.urllib.request.urlopen
        self.fetched = []
        mapd.go_platform = lambda: "linux-amd64"
        mapd.go_version_of = lambda root: ("go version go1.25.1 linux/amd64"
                                           if (Path(root) / "bin" / "go").is_file() else "")

        def no_network(*_a, **_k):
            raise AssertionError("no request may be made: go.dev's list is not read, the pin is checked")
        mapd.urllib.request.urlopen = no_network
        buf = io.BytesIO()                              # a made-up Go archive
        with tarfile.open(fileobj=buf, mode="w:gz") as t:
            for name, data in (("go/bin/go", b"#!/bin/sh\necho made up\n"), ("go/VERSION", b"go1.25.1\n")):
                ti = tarfile.TarInfo(name)
                ti.size, ti.mode = len(data), 0o755
                t.addfile(ti, io.BytesIO(data))
        self.archive = buf.getvalue()

        def fake_fetch(url, dest, timeout=900):
            self.fetched.append(url)
            Path(dest).write_bytes(self.archive)
        mapd.fetch = fake_fetch

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(mapd, k, v)
        mapd.urllib.request.urlopen = self.urlopen
        self.d.cleanup()

    def pins(self, sha=None, size=None):
        p = json.loads(json.dumps(common.load_pins()))
        p["go"]["sha256"] = {} if sha is None else {"linux-amd64": sha}
        p["go"]["bytes"] = {} if size is None else {"linux-amd64": size}
        return p

    def get_go(self, pins, goroot):
        with contextlib.redirect_stdout(io.StringIO()):
            return mapd.get_go(self.tmp, pins, goroot)

    def test_pins_json_pins_go_for_both_linux_platforms(self):
        go = common.load_pins()["go"]
        self.assertEqual(go["version"], "1.25.1")                     # Mapd's go.mod
        for plat in ("linux-amd64", "linux-arm64"):
            self.assertRegex(go["sha256"].get(plat, ""), r"^[0-9a-f]{64}$", plat)
            self.assertGreater(go["bytes"].get(plat, 0), 10_000_000, plat)

    def test_no_pin_no_download(self):
        with self.assertRaises(common.Refused) as cm:
            self.get_go(self.pins(), None)
        self.assertIn("pins no SHA-256", str(cm.exception))
        self.assertEqual(self.fetched, [])

    def test_another_archive_is_refused(self):
        with self.assertRaises(common.Refused):
            self.get_go(self.pins("0" * 64, len(self.archive)), None)
        self.assertEqual(self.fetched, ["https://go.dev/dl/go1.25.1.linux-amd64.tar.gz"])
        self.assertFalse((self.tmp / "go1.25.1.linux-amd64.tar.gz").exists())   # removed, nothing unpacked
        self.assertFalse((self.tmp / "go").exists())
        sha = hashlib.sha256(self.archive).hexdigest()
        with self.assertRaises(common.Refused):                       # the pinned hash but another size
            self.get_go(self.pins(sha, len(self.archive) + 1), None)
        self.assertFalse((self.tmp / "go").exists())

    def test_the_pinned_archive_is_installed(self):
        sha = hashlib.sha256(self.archive).hexdigest()
        root = self.get_go(self.pins(sha, len(self.archive)), None)
        self.assertEqual(root, self.tmp / "go")
        self.assertTrue((root / "bin" / "go").is_file())
        self.assertFalse((self.tmp / "go1.25.1.linux-amd64.tar.gz").exists())
        self.get_go(self.pins(sha, len(self.archive)), None)  # installed already: not fetched again
        self.assertEqual(len(self.fetched), 1)


@unittest.skipUnless(HAVE_OSMIUM, "pyosmium is not here (.venv/map has it)")
class Prepare(unittest.TestCase):
    def test_only_mapds_roads_with_their_coordinates(self):
        import osmium
        with tempfile.TemporaryDirectory() as d:
            src = Path(d) / "extract.osm"
            src.write_text(OSM_XML, encoding="utf-8")
            rep = mapd.prepare(src, Path(d) / "roads.osm.pbf")
            self.assertEqual(rep["ways_kept"], 2)
            ways = {}
            for w in osmium.FileProcessor(str(Path(d) / "roads.osm.pbf"), osmium.osm.WAY):
                ways[w.id] = [(round(n.location.lat, 4), round(n.location.lon, 4)) for n in w.nodes]
            self.assertEqual(sorted(ways), [10, 11])
            self.assertEqual(ways[10], [(42.12, -80.09), (42.121, -80.088), (42.122, -80.086)])


@unittest.skipUnless(MAPD_BIN and sys.platform == "linux" and HAVE_OSMIUM,
                     "MAPD_BIN (a mapd built by mapd.py setup), Linux and pyosmium are needed")
class WholeBuildAdmittedByMapd(unittest.TestCase):
    def test_build_and_admission(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            (tmp / "extract.osm").write_text(OSM_XML, encoding="utf-8")
            man = {"bounds": [-80.2, 42.0, -80.0, 42.2], "osm_snapshot": "2026-11-20"}
            (tmp / "manifest.json").write_text(json.dumps(man), encoding="utf-8")
            rc = mapd.main(["build", "--extract", str(tmp / "extract.osm"), "--manifest", str(tmp / "manifest.json"),
                            "--mapd", MAPD_BIN, "--out", str(tmp / "out" / "mapd.tar"), "--work", str(tmp / "work")])
            self.assertEqual(rc, 0)
            info = json.loads((tmp / "out" / "mapd.json").read_text(encoding="utf-8"))
            self.assertTrue(info["admitted"])
            self.assertEqual((info["min_lat"], info["min_lon"], info["max_lat"], info["max_lon"]), (42, -82, 44, -80))
            self.assertEqual(info["cells"], 64)
            rep = mapd.verify(tmp / "out" / "mapd.tar", common.mapd_box(man["bounds"]), Path(MAPD_BIN), tmp / "v2",
                              "2026-11-20")
            self.assertTrue(rep["admitted"])
            self.assertEqual(rep["cells"], 64)
            self.assertEqual(rep["cells_with_roads"], 1)


if __name__ == "__main__":
    unittest.main()
