"""roads.py: the class table, the .rt bytes against StarPilot's own road_tiles.py, a whole build.

    .venv/map/Scripts/python -m unittest discover packbuilder/comma/tests

The PMTiles tests need the pmtiles package (.venv/map); the StarPilot tests need StarPilot's
openpilot/starpilot/navigation/road_tiles.py (STARPILOT_SRC, default data/starpilot/src or
data/starpilot/fork; loaded from that one file, unchanged) and numpy. Each is skipped when missing.
BEAMLINE_TEST_MAP names a real map.pmtiles for the last test (default: the weekly pack of
data/r3-packs/weekly if it is there).
"""
import importlib.util
import random
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fixtures  # noqa: E402
import common  # noqa: E402
import roads  # noqa: E402

HAVE_PMTILES = importlib.util.find_spec("pmtiles") is not None
RT = fixtures.starpilot_road_tiles()
NO_STARPILOT = "StarPilot's road_tiles.py (or numpy) is not here"


def real_map() -> Path | None:
    import os
    p = os.environ.get("BEAMLINE_TEST_MAP")
    if p:
        return Path(p)
    found = sorted((fixtures.REPO / "data" / "r3-packs" / "weekly").glob("us-pa-*/map.pmtiles"))
    return found[-1] if found else None


class ClassTable(unittest.TestCase):
    def test_contract_table(self):
        c = roads.road_class
        self.assertEqual(c("highway", "motorway", None), roads.MOTORWAY)
        self.assertEqual(c("major_road", "trunk", None), roads.MOTORWAY)
        self.assertEqual(c("major_road", "primary", None), roads.MAJOR)
        self.assertEqual(c("major_road", "secondary", None), roads.MAJOR)
        for d in ("tertiary", "residential", "unclassified"):
            self.assertEqual(c("minor_road", d, None), roads.STREET)
        self.assertEqual(c("other", "living_street", None), roads.STREET)    # protomaps-basemap 4 files it under "other"
        self.assertEqual(c("highway", "motorway_link", True), roads.LINK)
        self.assertEqual(c("major_road", "trunk_link", None), roads.LINK)       # by the name alone
        self.assertEqual(c("major_road", "jughandle", True), roads.LINK)        # by is_link alone
        self.assertEqual(c("minor_road", "service", None), roads.MINOR)
        self.assertEqual(c("path", "track", None), roads.MINOR)                 # protomaps-basemap 4 files it under "path"
        self.assertEqual(c("minor_road", "raceway", None), roads.MINOR)         # another minor_road kind
        self.assertEqual(c("major_road", "disused_tram", None), roads.MAJOR)    # a road kind's own class
        for kind, detail in (("path", "footway"), ("path", "cycleway"), ("path", "pier"), ("rail", "rail"),
                             ("ferry", None), ("aeroway", "runway"), ("other", "rest_area"), ("pier", None), (None, None)):
            self.assertIsNone(c(kind, detail, None), (kind, detail))


class Format(unittest.TestCase):
    def lines(self, rnd):
        out = {}
        for cls in rnd.sample(range(5), rnd.randint(1, 5)):
            out[cls] = [[(rnd.randint(-200, 4300), rnd.randint(-200, 4300)) for _ in range(rnd.randint(1, 30))]
                        for _ in range(rnd.randint(1, 12))]
        out.setdefault(0, []).append([(-40000, 5), (40000, -40000)])     # clipped to +-32767 by both
        return out

    def test_check_tile_refuses_what_the_decoder_refuses(self):
        good = roads.encode_tile({roads.STREET: [[(1, 2), (3, 4)]]})
        roads.check_tile(good)
        import struct
        import zlib
        body = zlib.decompress(good[6:])
        bad = {
            "short": good[:5],
            "magic": b"XPRT" + good[4:],
            "version": good[:4] + b"\x02" + good[5:],
            "trailing zlib": good + b"\x00",
            "cut zlib": good[:-3],
            "trailing body": good[:6] + zlib.compress(body + b"\x00", 6),
            "length one": good[:6] + zlib.compress(body[:9] + struct.pack("<H", 1) + body[11:], 6),
            "no class": struct.pack("<4sBB", b"SPRT", 1, 0) + zlib.compress(b"", 6),
            "too large": b"SPRT\x01\x01" + bytes(roads.MAX_STORED_BYTES),
        }
        for name, raw in bad.items():
            with self.assertRaises(ValueError, msg=name):
                roads.check_tile(raw)
        self.assertIsNone(roads.encode_tile({roads.MAJOR: [[(1, 1)]]}))  # no line of two points: no tile

    @unittest.skipIf(RT is None, NO_STARPILOT)
    def test_bytes_equal_starpilots_encoder_and_its_decoder_reads_them(self):
        import numpy as np
        rnd = random.Random(8)
        for _ in range(200):
            lines = self.lines(rnd)
            ours = roads.encode_tile(lines)
            theirs = RT.encode_road_tile({c: [np.asarray(l, dtype=np.float64) for l in v] for c, v in lines.items()})
            if ours is None:
                self.assertEqual(theirs[5], 0)
                continue
            self.assertEqual(ours, theirs)
            tile = RT.decode_road_tile(RT.TileKey(14, 1, 2), ours)
            for c, v in lines.items():
                v = [l for l in v if len(l) >= 2]
                if not v:
                    self.assertNotIn(c, tile.lines)
                    continue
                pts, offsets = tile.lines[c]
                want = [(max(-32767, min(32767, x)), max(-32767, min(32767, y))) for l in v for x, y in l]
                self.assertEqual([tuple(int(t) for t in p) for p in pts], want)
                self.assertEqual(list(np.diff(offsets)), [len(l) for l in v])


@unittest.skipUnless(HAVE_PMTILES, "the pmtiles package is not here (.venv/map has it)")
class Build(unittest.TestCase):
    def map_file(self, tmp: Path) -> tuple[Path, dict]:
        lat, lon = 42.1, -80.1
        tiles, keys = {}, {}
        for z in (8, 11, 12, 14):
            key = fixtures.tile_of(lat, lon, z)
            keys[z] = key
            tiles[key] = fixtures.mvt_tile({
                "roads": [({"kind": "highway", "kind_detail": "motorway", "min_zoom": 5}, 2, [[(0, 0), (100, 200), (4096, 4096)]]),
                          ({"kind": "major_road", "kind_detail": "primary_link", "is_link": True}, 2, [[(5, 5), (6, 9)]]),
                          ({"kind": "minor_road", "kind_detail": "residential"}, 2, [[(-64, 10), (4160, 10)], [(7, 7), (8, 8)]]),
                          ({"kind": "path", "kind_detail": "footway"}, 2, [[(1, 1), (2, 2)]]),
                          ({"kind": "rail", "kind_detail": "rail"}, 2, [[(1, 1), (2, 2)]]),
                          ({"kind": "major_road", "kind_detail": "primary"}, 1, [[(50, 50)]])],
                "water": [({"kind": "water"}, 2, [[(0, 0), (1, 1)]])]})
        # a z14 tile with only paths and rail: no file; a z14 tile with roads in a non-4096 extent
        x, y = keys[14][1], keys[14][2]
        tiles[(14, x + 1, y)] = fixtures.mvt_tile({"roads": [({"kind": "path", "kind_detail": "path"}, 2, [[(1, 1), (9, 9)]])]})
        tiles[(14, x + 2, y)] = fixtures.mvt_tile({"roads": [({"kind": "minor_road", "kind_detail": "service"}, 2,
                                                               [[(0, 0), (1, 1), (511, 511)]])]}, extent=512)
        path = tmp / "map.pmtiles"
        fixtures.write_pmtiles(path, tiles)
        return path, keys

    def test_build(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            path, keys = self.map_file(tmp)
            rep = roads.build(path, tmp / "roads.tar", mtime=1763751540)
            with tarfile.open(tmp / "roads.tar") as t:
                members = t.getmembers()
            names = [m.name for m in members]
            x, y = keys[14][1], keys[14][2]
            want = [f"{z}/{keys[z][1]}/{keys[z][2]}.rt" for z in (8, 11, 14)] + [f"14/{x + 2}/{y}.rt"]
            self.assertEqual(names, sorted(want, key=lambda n: tuple(int(v) for v in n[:-3].split("/"))))
            self.assertTrue(all(m.isreg() and m.mtime == 1763751540 and m.uid == 0 and m.mode == 0o644 for m in members))
            self.assertEqual(rep["zooms"]["14"]["map_tiles"], 3)
            self.assertEqual(rep["zooms"]["14"]["files"], 2)
            self.assertEqual(rep["zooms"]["8"]["lines"], {"motorway": 1, "major": 0, "street": 2, "link": 1, "minor": 0})
            roads.verify(tmp / "roads.tar", fixtures.ERIE_BOUNDS)
            # the same input gives the same bytes, one worker or two
            roads.build(path, tmp / "again.tar", mtime=1763751540, workers=2)
            self.assertEqual((tmp / "roads.tar").read_bytes(), (tmp / "again.tar").read_bytes())
            if RT is not None:
                with tarfile.open(tmp / "roads.tar") as t:
                    for m in t.getmembers():
                        z, tx, ty = (int(v) for v in m.name[:-3].split("/"))
                        tile = RT.decode_road_tile(RT.TileKey(z, tx, ty), t.extractfile(m).read())
                        self.assertFalse(tile.empty)
                        if (tx, ty) == (x + 2, y):   # extent 512 scaled to 4096
                            pts, _ = tile.lines[roads.MINOR]
                            self.assertEqual([tuple(int(v) for v in p) for p in pts], [(0, 0), (8, 8), (4088, 4088)])
                        if z == 8:
                            self.assertEqual(sorted(tile.lines), [roads.MOTORWAY, roads.STREET, roads.LINK])

    def test_directories_must_be_gzip(self):
        """A map whose directories are not gzip (the header's internal_compression) is refused with a
        plain message, not a gzip error from pmtiles' directory reader (which always gunzips)."""
        import contextlib
        import io
        from pmtiles.tile import Compression
        road = fixtures.mvt_tile({"roads": [({"kind": "highway", "kind_detail": "motorway"}, 2, [[(0, 0), (9, 9)]])]})
        tiles = {fixtures.tile_of(42.1, -80.1, z): road for z in (8, 11, 14)}
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            good = tmp / "gzip.pmtiles"                  # the hand-written file is read when its directories are gzip
            fixtures.write_pmtiles_raw(good, tiles, Compression.GZIP)
            entries, compression = roads.tile_entries(good, (8, 11, 14))
            self.assertEqual(([e[0] for e in entries], compression), ([8, 11, 14], 2))
            plain = tmp / "none.pmtiles"                 # directories stored as they are
            fixtures.write_pmtiles_raw(plain, tiles, Compression.NONE)
            zstd = tmp / "zstd.pmtiles"                  # a header naming zstd directories
            raw = bytearray(good.read_bytes())
            raw[97] = Compression.ZSTD.value
            zstd.write_bytes(bytes(raw))
            for path, label in ((plain, "none"), (zstd, "zstd")):
                with self.assertRaises(common.Refused, msg=label) as cm:
                    roads.tile_entries(path, (8, 11, 14))
                self.assertIn(f"directories are stored with compression '{label}'", str(cm.exception))
                err = io.StringIO()
                with contextlib.redirect_stderr(err):
                    self.assertEqual(roads.main(["--map", str(path), "--out", str(tmp / "roads.tar")]), 1)
                self.assertIn("refused:", err.getvalue())
                self.assertFalse((tmp / "roads.tar").exists())


class MemberRule(unittest.TestCase):
    """roads.verify: standard library only."""

    def test_member_rule(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            good = roads.encode_tile({0: [[(0, 0), (1, 1)]]})
            cases = {
                "dotdot": [("14/../1.rt", good, "file")],
                "absolute": [("/14/1/1.rt", good, "file")],
                "zoom 12": [("12/1/1.rt", good, "file")],
                "x out of range": [("8/256/1.rt", good, "file")],
                "folder": [("14/", None, "dir"), ("14/1/1.rt", good, "file")],
                "link": [("14/1/1.rt", b"14/1/2.rt", "symlink")],
                "other name": [("14/1/1.txt", good, "file")],
            }
            for name, members in cases.items():
                p = tmp / f"{name}.tar"
                fixtures.tar_with(p, members)
                with self.assertRaises(common.Refused, msg=name):
                    roads.verify(p)
            fixtures.tar_with(tmp / "broken.tar", [(f"{z}/1/1.rt", good if z != 11 else good[:-2], "file") for z in (8, 11, 14)])
            with self.assertRaises(common.Refused):
                roads.verify(tmp / "broken.tar")
            fixtures.tar_with(tmp / "nozoom.tar", [("14/1/1.rt", good, "file")])
            with self.assertRaises(common.Refused):
                roads.verify(tmp / "nozoom.tar")


@unittest.skipUnless(HAVE_PMTILES and RT is not None and real_map() is not None and real_map().is_file(),
                     "needs pmtiles, StarPilot's road_tiles.py and a real map.pmtiles")
class RealMap(unittest.TestCase):
    def test_erie_tiles_decode_with_starpilot(self):
        with tempfile.TemporaryDirectory() as d:
            out = Path(d) / "roads.tar"
            rep = roads.build(real_map(), out, bbox=[-80.2, 42.0, -80.0, 42.15], workers=1)
            self.assertGreater(rep["zooms"]["14"]["files"], 50)
            with tarfile.open(out) as t:
                n = 0
                for m in t.getmembers():
                    z, x, y = (int(v) for v in m.name[:-3].split("/"))
                    tile = RT.decode_road_tile(RT.TileKey(z, x, y), t.extractfile(m).read())
                    self.assertFalse(tile.empty)
                    n += 1
            self.assertEqual(n, sum(r["files"] for r in rep["zooms"].values()))


if __name__ == "__main__":
    unittest.main()
