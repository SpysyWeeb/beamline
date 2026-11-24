"""search_code.py: the five files at the top level, byte-stable; query.py, lines.py and normalize.py the frozen
release pins.json pins (never packbuilder/search's), the same on a CRLF and an LF checkout; ranking.json tied to
the pack's; the code answering known queries in a separate Python. No network."""
import contextlib
import hashlib
import io
import json
import os
import shutil
import sqlite3
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fixtures  # noqa: E402
import common  # noqa: E402
import search_code  # noqa: E402

SEARCH = fixtures.REPO / "packbuilder" / "search"


def members(tar: Path) -> dict:
    with tarfile.open(tar) as t:
        return {m.name: t.extractfile(m).read() for m in t.getmembers()}


def copy_release(dest: Path, crlf: bool = False) -> Path:
    dest.mkdir(parents=True, exist_ok=True)
    for n in search_code.CODE_FILES:
        data = (search_code.RELEASE / n).read_bytes()
        (dest / n).write_bytes(data.replace(b"\n", b"\r\n") if crlf else data)
    return dest


class SearchCode(unittest.TestCase):
    def test_build_and_verify(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            man = fixtures.fake_pack(tmp / "pack")
            rep = search_code.build(tmp / "a.tar", 1763751540, tmp / "pack" / "manifest.json")
            with tarfile.open(tmp / "a.tar") as t:
                ms = t.getmembers()
                self.assertEqual(sorted(m.name for m in ms), sorted(common.SEARCH_CODE_FILES))
                self.assertTrue(all(m.isreg() and "/" not in m.name and m.mtime == 1763751540 for m in ms))
                for m in ms:
                    data = t.extractfile(m).read()
                    self.assertNotIn(b"\r\n", data)
                    src = search_code.RELEASE if m.name in search_code.CODE_FILES else SEARCH
                    self.assertEqual(data, common.text_bytes(src / m.name), m.name)
            self.assertEqual(rep["members"]["ranking.json"], man["parts"]["search"]["ranking_sha256"])
            self.assertEqual(rep["release"], common.load_pins()["search_code"]["release"])
            search_code.verify(tmp / "a.tar", tmp / "pack" / "manifest.json")
            search_code.build(tmp / "b.tar", 1763751540)
            self.assertEqual((tmp / "a.tar").read_bytes(), (tmp / "b.tar").read_bytes())

    def test_refusals(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            fixtures.fake_pack(tmp / "pack", ranking_sha256="0" * 64)
            with self.assertRaises(common.Refused):
                search_code.build(tmp / "x.tar", 0, tmp / "pack" / "manifest.json")
            self.assertFalse((tmp / "x.tar").exists())
            search_code.build(tmp / "ok.tar", 0)
            with self.assertRaises(common.Refused):
                search_code.verify(tmp / "ok.tar", tmp / "pack" / "manifest.json")
            five = members(tmp / "ok.tar")
            cases = {
                "a sixth file": [(n, b, "file") for n, b in five.items()] + [("cli.py", b"print(1)\n", "file")],
                "four files": [(n, b, "file") for n, b in five.items() if n != "lines.py"],
                "in a folder": [(f"search/{n}", b, "file") for n, b in five.items()],
                "a folder member": [("search", None, "dir")] + [(n, b, "file") for n, b in five.items()],
                "a link": [(n, b, "file") for n, b in five.items() if n != "lines.py"] + [("lines.py", b"query.py", "symlink")],
                "changed code": [(n, b + (b"#\n" if n == "query.py" else b""), "file") for n, b in five.items()],
                "changed data": [(n, b + (b"\n" if n == "abbreviations.json" else b""), "file") for n, b in five.items()],
            }
            for name, ms in cases.items():
                p = tmp / f"{name}.tar"
                fixtures.tar_with(p, ms)
                with self.assertRaises(common.Refused, msg=name):
                    search_code.verify(p)
            # comma.json's check (catalog.py) compares with no checkout, yet code off the pins is still refused
            fixtures.tar_with(tmp / "code.tar", cases["changed code"])
            with self.assertRaises(common.Refused) as cm:
                search_code.verify(tmp / "code.tar", None, None)
            self.assertIn("pinned release", str(cm.exception))
            fixtures.tar_with(tmp / "data.tar", cases["changed data"])
            search_code.verify(tmp / "data.tar", None, None)          # data is the pack's: checked against the pack


# ==================================================================== the frozen release
class FrozenRelease(unittest.TestCase):
    def test_the_release_has_its_pins(self):
        """Fails when a file of packbuilder/comma/search-release and pins.json "search_code" disagree."""
        pins = common.load_pins()["search_code"]
        self.assertEqual(pins["folder"], "packbuilder/comma/search-release")
        self.assertEqual(sorted(pins["files"]), sorted(search_code.CODE_FILES))
        for name, pin in pins["files"].items():
            data = common.text_bytes(search_code.RELEASE / name)
            self.assertEqual((len(data), hashlib.sha256(data).hexdigest()), (pin["bytes"], pin["sha256"]), name)
        search_code.release_files()                                     # and the builder agrees

    def test_release_1_is_the_published_code(self):
        """Release 1 is the code of the first published pack (us-pa-202611220901); StarPilot pins these."""
        pins = common.load_pins()["search_code"]
        if pins["release"] != 1:
            self.skipTest(f"release {pins['release']}: release 1's hashes are history")
        self.assertEqual({n: (p["bytes"], p["sha256"]) for n, p in pins["files"].items()}, {
            "query.py": (65649, "e576804ab39bb67ac87aff32f2eb74234e024afa61b3ec7fe75605bbf8fbd76a"),
            "lines.py": (7491, "cdb634b2ac4541de73b228b075f0dc0be7e0f0bed2f1e84b5d2eeface3050517"),
            "normalize.py": (4339, "fc9040c03500dab1a9265a32c7918488dcd6b3b2af813302fcb4d70c3e8f62e6")})

    def test_packbuilder_search_moved_ahead_is_reported(self):
        """A report, never a failure: packbuilder/search may move on; the comma keeps the release until a new
        one is made on purpose (copy, new pins, StarPilot's pin list)."""
        ahead = search_code.moved_ahead()
        if ahead:
            msg = (f"REPORT: packbuilder/search has moved ahead of the frozen search code release "
                   f"{common.load_pins()['search_code']['release']} ({', '.join(ahead)}); search-code.tar keeps the "
                   f"release. A new release, on purpose: StarPilot's pin list first, then the files copied into "
                   f"packbuilder/comma/search-release and new pins in pins.json (search_code.py --status).")
            print("\n" + msg, file=sys.stderr)
            self.skipTest(msg)

    def test_an_edit_in_packbuilder_search_never_reaches_the_tar(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            moved = tmp / "search"
            moved.mkdir()
            for n in common.SEARCH_CODE_FILES:
                shutil.copyfile(SEARCH / n, moved / n)
            with open(moved / "query.py", "ab") as f:
                f.write(b"\nimport socket  # an edit after the release\n")
            (moved / "lines.py").write_bytes(b"raise SystemExit('not the release')\n")
            self.assertEqual(search_code.moved_ahead(moved), ["query.py", "lines.py"])
            search_code.build(tmp / "a.tar", 0, None, moved)
            got = members(tmp / "a.tar")
            pins = common.load_pins()["search_code"]["files"]
            for n in search_code.CODE_FILES:
                self.assertEqual(hashlib.sha256(got[n]).hexdigest(), pins[n]["sha256"], n)
            self.assertEqual(got["ranking.json"], common.text_bytes(moved / "ranking.json"))
            search_code.verify(tmp / "a.tar", None, moved)

    def test_crlf_and_lf_checkouts_give_the_same_tar(self):
        """A Windows checkout with CRLF line ends and a Linux one with LF give the same bytes: the pinned ones."""
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            crlf, lf = copy_release(tmp / "crlf", crlf=True), copy_release(tmp / "lf")
            self.assertIn(b"\r\n", (crlf / "query.py").read_bytes())
            search_code.build(tmp / "crlf.tar", 1795338060, release=crlf)
            search_code.build(tmp / "lf.tar", 1795338060, release=lf)
            search_code.build(tmp / "here.tar", 1795338060)
            self.assertEqual((tmp / "crlf.tar").read_bytes(), (tmp / "lf.tar").read_bytes())
            self.assertEqual((tmp / "lf.tar").read_bytes(), (tmp / "here.tar").read_bytes())
            pins = common.load_pins()["search_code"]["files"]
            got = members(tmp / "crlf.tar")
            for n in search_code.CODE_FILES:
                self.assertEqual((len(got[n]), hashlib.sha256(got[n]).hexdigest()), (pins[n]["bytes"], pins[n]["sha256"]))

    def test_a_release_file_off_its_pin_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            rel = copy_release(tmp / "rel")
            with open(rel / "normalize.py", "ab") as f:
                f.write(b"# one more line\n")
            with self.assertRaises(common.Refused) as cm:
                search_code.build(tmp / "x.tar", 0, release=rel)
            self.assertIn("normalize.py", str(cm.exception))
            self.assertFalse((tmp / "x.tar").exists())
            (rel / "normalize.py").unlink()
            with self.assertRaises(common.Refused):
                search_code.build(tmp / "x.tar", 0, release=rel)
            self.assertFalse((tmp / "x.tar").exists())

    def test_pins_must_name_exactly_the_three_files(self):
        real = common.load_pins
        for change in (lambda p: p["search_code"]["files"].pop("lines.py"),
                       lambda p: p["search_code"]["files"].update({"cli.py": {"bytes": 1, "sha256": "0" * 64}}),
                       lambda p: p["search_code"].update(folder="packbuilder/search"),
                       lambda p: p.pop("search_code")):
            pins = json.loads(json.dumps(real()))
            change(pins)
            common.load_pins = lambda pins=pins: pins
            try:
                with self.assertRaises(common.Refused):
                    search_code.release_files()
            finally:
                common.load_pins = real

    def test_status(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(search_code.main(["--status"]), 0)
        s = json.loads(out.getvalue()[:out.getvalue().rindex("}") + 1])
        self.assertTrue(s["release_matches_pins"])
        self.assertEqual(s["packbuilder_search_moved_ahead"], search_code.moved_ahead())


# ==================================================================== the code answers, in a separate Python
STAND_IN = '''
import json, os, sys
MODE = {mode!r}
class SearchEngine:
    def __init__(self, db, addresses_path=None):
        if MODE == "crash":
            raise RuntimeError("the search file has no table entry")
        self.db, self.addresses = db, addresses_path
    def search(self, text, lat=None, lon=None, limit=None):
        if MODE == "hang":
            import time
            time.sleep(60)
        try:
            import common  # packbuilder/comma: must not be reachable from the separate Python
            reach = "packbuilder/comma reachable"
        except ImportError:
            reach = "isolated"
        if MODE == "empty":
            return {{"results": []}}
        return {{"results": [
            {{"title": "Peach Street Terrace", "subtitle": "elsewhere", "lat": 40.0, "lon": -75.0, "kind": "place"}},
            {{"title": text, "subtitle": "|".join([reach, str(sys.flags.isolated), str(sys.flags.no_site),
                                                 os.path.basename(self.db), str(os.path.isfile(self.db)),
                                                 str(self.addresses), str(lat), str(limit)]),
              "lat": 42.0548, "lon": -80.0875, "kind": "street"}}]}}
'''

PEACH = {"query": "Peach Street", "near": [42.028694, -80.128884], "title": "Peach Street",
         "at": [42.028694, -80.128884], "within_km": 10, "top": 3}


def stand_in(folder: Path, mode: str = "ok") -> Path:
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "query.py").write_text(STAND_IN.format(mode=mode), encoding="utf-8")
    for n in ("lines.py", "normalize.py"):
        (folder / n).write_text("", encoding="utf-8")
    return folder


class Answers(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self.tmp = Path(self.d.name)
        self.db = self.tmp / "search.sqlite"
        self.db.write_bytes(b"made up")

    def tearDown(self):
        self.d.cleanup()

    def test_the_code_runs_isolated_as_starpilot_loads_it(self):
        old = os.environ.get("PYTHONPATH")
        os.environ["PYTHONPATH"] = str(search_code.HERE)         # ignored by the separate Python (-I)
        try:
            # a path relative to this process's folder (the separate Python runs in the code's folder)
            got = search_code.ask(stand_in(self.tmp / "code"), Path(os.path.relpath(self.db)), None, [PEACH])
        finally:
            if old is None:
                os.environ.pop("PYTHONPATH", None)
            else:
                os.environ["PYTHONPATH"] = old
        (results,) = got
        self.assertEqual(results[1]["subtitle"], "isolated|1|1|search.sqlite|True|None|42.028694|8")
        hit, why = search_code.judge(PEACH, results)
        self.assertEqual((hit["title"], why), ("Peach Street", ""))
        self.assertLess(hit["km"], 10)

    def test_judge(self):
        far = [{"title": "Peach Street", "lat": 40.0, "lon": -75.0}]
        self.assertIsNone(search_code.judge(PEACH, far)[0])                            # the right name, too far
        near = [{"title": "Downs Drive", "lat": 42.05, "lon": -80.08}]
        self.assertIsNone(search_code.judge(PEACH, near)[0])                           # near, another name
        late = [{"title": "x", "lat": 42.05, "lon": -80.08}] * 3 + [{"title": "peach street", "lat": 42.05, "lon": -80.08}]
        self.assertIsNone(search_code.judge(PEACH, late)[0])                           # not among the first 3
        self.assertIsNotNone(search_code.judge(PEACH, late[2:])[0])                    # case ignored
        self.assertIn("no results", search_code.judge(PEACH, [])[1])

    def test_failures_are_refused(self):
        with self.assertRaises(common.Refused) as cm:
            search_code.ask(stand_in(self.tmp / "crash", "crash"), self.db, None, [PEACH])
        self.assertIn("no table entry", str(cm.exception))
        with self.assertRaises(common.Refused) as cm:
            search_code.ask(stand_in(self.tmp / "hang", "hang"), self.db, None, [PEACH], time_limit_s=3)
        self.assertIn("no answer within 3 s", str(cm.exception))
        results = search_code.ask(stand_in(self.tmp / "empty", "empty"), self.db, None, [PEACH])
        self.assertIsNone(search_code.judge(PEACH, results[0])[0])

    def test_only_pinned_code_is_ever_run(self):
        """answers() checks the tar first: a tar with other code is refused before anything of it runs."""
        marker = self.tmp / "ran"
        code = stand_in(self.tmp / "code")
        (code / "query.py").write_text(f"open({str(marker)!r}, 'w').close()\n", encoding="utf-8")
        ms = [(n, (code / n).read_bytes(), "file") for n in search_code.CODE_FILES]
        ms += [(n, common.text_bytes(SEARCH / n), "file") for n in search_code.DATA_FILES]
        fixtures.tar_with(self.tmp / "other.tar", ms)
        with self.assertRaises(common.Refused) as cm:
            search_code.answers(self.tmp / "other.tar", self.db, None, "PA")
        self.assertIn("pinned release", str(cm.exception))
        self.assertFalse(marker.exists())

    def test_a_state_without_known_queries_asks_its_own_place(self):
        db = self.tmp / "oh.sqlite"
        con = sqlite3.connect(db)
        con.executescript("CREATE TABLE entry(id INTEGER PRIMARY KEY, kind INTEGER, name TEXT, lat REAL, lon REAL, importance REAL);"
                          "INSERT INTO entry VALUES (1, 1, 'Columbus', 39.9612, -82.9988, 0.9),"
                          "(2, 1, 'Dayton', 39.7589, -84.1916, 0.7), (3, 2, 'High Street', 39.96, -83.0, 0.99);")
        con.commit()
        con.close()
        probes, kind = search_code.probes_for("OH", db)
        self.assertEqual((kind, [p["query"] for p in probes]), ("own place", ["Columbus"]))
        self.assertEqual(probes[0]["at"], [39.9612, -82.9988])
        probes, kind = search_code.probes_for("pa", db)
        self.assertEqual(kind, "known")
        self.assertIn("Peach Street", [p["query"] for p in probes])
        with self.assertRaises(common.Refused):
            search_code.probes_for("OH", self.db)                     # not a search file

    def test_the_known_queries_are_public_places(self):
        table = json.loads(search_code.PROBES.read_text(encoding="utf-8"))
        for st, probes in table.items():
            if st.startswith("_"):
                continue
            self.assertTrue(probes, st)
            for p in probes:
                self.assertEqual(sorted(p), ["at", "near", "query", "title", "top", "within_km"])
        self.assertEqual({p["query"] for p in table["PA"]} >= {"Peach Street", "Erie"}, True)


def real_search_file() -> Path | None:
    """A Pennsylvania search.sqlite on this desk (BEAMLINE_SEARCH_SQLITE, else data/search/pa-search.sqlite)."""
    for p in (os.environ.get("BEAMLINE_SEARCH_SQLITE"), fixtures.REPO / "data" / "search" / "pa-search.sqlite"):
        if p and Path(p).is_file():
            return Path(p)
    return None


@unittest.skipUnless(real_search_file(), "no Pennsylvania search.sqlite here (BEAMLINE_SEARCH_SQLITE)")
class RealSearchFile(unittest.TestCase):
    def test_the_release_answers_the_known_queries(self):
        with tempfile.TemporaryDirectory() as d:
            tar = Path(d) / "search-code.tar"
            search_code.build(tar, 0)
            rep = search_code.answers(tar, real_search_file(), None, "PA")
        self.assertEqual(rep["probes"], "known")
        self.assertEqual([a["query"] for a in rep["answered"]], [p["query"] for p in json.loads(
            search_code.PROBES.read_text(encoding="utf-8"))["PA"]])


def git_ok() -> bool:
    import subprocess
    try:
        return subprocess.run(["git", "-C", str(fixtures.REPO), "rev-parse", "HEAD"], capture_output=True).returncode == 0
    except OSError:
        return False


@unittest.skipUnless(git_ok(), "not a git checkout (or no git)")
class FromACommit(unittest.TestCase):
    def test_rev_takes_the_committed_data_files_and_the_release_code(self):
        import subprocess
        with tempfile.TemporaryDirectory() as d:
            tmp = Path(d)
            search_code.build(tmp / "head.tar", 0, None, rev="HEAD")
            got = members(tmp / "head.tar")
            for name in search_code.DATA_FILES:
                want = subprocess.run(["git", "-C", str(fixtures.REPO), "show", f"HEAD:packbuilder/search/{name}"],
                                      capture_output=True, check=True).stdout.replace(b"\r\n", b"\n")
                self.assertEqual(got[name], want, name)
            for name in search_code.CODE_FILES:
                self.assertEqual(got[name], common.text_bytes(search_code.RELEASE / name), name)
            search_code.verify(tmp / "head.tar", None, None, rev="HEAD")
            with self.assertRaises(common.Refused):
                search_code.build(tmp / "x.tar", 0, None, rev="no-such-commit-here")
            self.assertFalse((tmp / "x.tar").exists())


if __name__ == "__main__":
    unittest.main()
