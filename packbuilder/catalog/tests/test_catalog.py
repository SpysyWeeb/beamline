"""python -m unittest discover packbuilder/catalog/tests (docs/architecture-downloads.md 7.5)."""
import hashlib
import io
import json
import os
import random
import shutil
import sys
import tempfile
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parents[2] / "android" / "scripts"))

import build_catalog  # noqa: E402
import catalog_client  # noqa: E402
import catalog_format as cf  # noqa: E402
import read_pack  # noqa: E402
import serve_packs  # noqa: E402


def sha(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def make_pack(folder: Path, state="PA", pack_id=None, version=202611152241, manifest_version=3, seed=1) -> Path:
    """A tiny pack: three parts, one compressible."""
    rnd = random.Random(seed)
    folder.mkdir(parents=True, exist_ok=True)
    parts = {
        "map": ("map.pmtiles", bytes(rnd.getrandbits(8) for _ in range(200_000))),
        "routing": ("routing.tar", b"".join(f"tile {i % 997} of the routing set\n".encode() for i in range(12_000))),
        "search": ("search.sqlite", bytes(rnd.getrandbits(8) for _ in range(70_000))),
    }
    m = {"manifest_version": manifest_version, "pack_id": pack_id or f"us-{state.lower()}", "name": "Pennsylvania",
         "country": "US", "state": state, "osm_snapshot": "2026-11-14", "created_utc": "2026-11-15T22:41:07Z",
         "bounds": [-80.51985, 39.7198, -74.68956, 42.51607], "attribution": "© OpenStreetMap contributors", "parts": {}}
    if manifest_version >= 3:
        m["pack_version"] = version
    for name, (file, data) in parts.items():
        (folder / file).write_bytes(data)
        m["parts"][name] = {"file": file, "format": "test", "format_version": 1, "bytes": len(data), "sha256": sha(data),
                            "attribution": "© OpenStreetMap contributors"}
    (folder / "manifest.json").write_text(json.dumps(m, indent=1), encoding="utf-8")
    return folder


def build(*args) -> int:
    out, err = io.StringIO(), io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        rc = build_catalog.main([str(a) for a in args])
    build.last_out, build.last_err = out.getvalue(), err.getvalue()
    return rc


class Server:
    def __init__(self, root: Path, **kw):
        self.opts = serve_packs.Options(root, out=io.StringIO(), **kw)
        self.httpd = serve_packs.make_server(self.opts, "127.0.0.1", 0)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def url(self, path="/catalog/catalog.json"):
        return f"http://127.0.0.1:{self.port}{path}"

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class CatalogTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="g-catalog-"))
        self.pack = make_pack(self.tmp / "us-pa")
        self.site = self.tmp / "site"
        self.assertEqual(0, build("--out", self.site, "--pack", self.pack), build.last_err)
        self.release = self.site / "us-pa-202611152241"

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def serve(self, **kw) -> Server:
        s = Server(self.site, **kw)
        self.addCleanup(s.close)
        return s

    def get(self, server: Server, out: Path):
        return catalog_client.get_pack(server.url(), "PA", out, False, 3, 3, quiet=True)

    def get_until_done(self, server: Server, out: Path, tries=6) -> int:
        """Runs get again after each cut (--cut-after cuts every path once); returns the number of cuts."""
        for cuts in range(tries):
            try:
                self.get(server, out)
                return cuts
            except ConnectionError:
                continue
        self.fail("never finished")

    def test_site_layout_and_compression_rule(self):
        cat = json.loads((self.site / "catalog" / "catalog.json").read_text(encoding="utf-8"))
        e = cat["packs"][0]
        enc = {p["name"]: p["transfer"]["encoding"] for p in e["parts"]}
        self.assertEqual({"map": "identity", "routing": "gzip", "search": "identity"}, enc)
        sizes = [p["transfer"]["bytes"] for p in e["parts"]]
        self.assertEqual(sorted(sizes), sizes)
        self.assertTrue((self.release / "routing.tar.gz").is_file())
        self.assertTrue((self.release / "transfer.json").is_file())
        with open(self.release / "routing.tar.gz", "rb") as f:
            head = f.read(10)
        self.assertEqual(0, head[3] & 0x08, "no file name in the gzip header")
        self.assertEqual(b"\0\0\0\0", head[4:8], "no time in the gzip header")
        _, usable, unusable = cf.parse((self.site / "catalog" / "catalog.json").read_bytes(), "http://127.0.0.1:8765/catalog/catalog.json")
        self.assertEqual(1, len(usable))
        self.assertEqual([], unusable)
        self.assertEqual(0, build("--out", self.site, "--check"), build.last_out)

    def test_get_gives_a_pack_folder_that_read_pack_verifies(self):
        s = self.serve()
        out = self.get(s, self.tmp / "got")
        m = read_pack.read_manifest(out)
        with redirect_stdout(io.StringIO()):
            self.assertTrue(read_pack.verify(out, m))
        self.assertEqual({"manifest.json", "map.pmtiles", "routing.tar", "search.sqlite"}, {p.name for p in out.iterdir()})
        for r in s.opts.log:
            want = {"Host", "User-Agent", "Accept-Encoding", "Connection"} | ({"Range", "If-Match"} if r["range"] else set())
            self.assertEqual(want, set(r["headers"]))
            self.assertEqual("Beamline-Packs/1", r["headers"]["User-Agent"])
            self.assertEqual("GET", r["method"])

    def test_a_cut_transfer_resumes_with_range(self):
        s = self.serve(cut_after=50_000)
        out = self.tmp / "got"
        with self.assertRaises(ConnectionError):
            self.get(s, out)
        self.assertEqual(50_000, (out / ".transfer-search").stat().st_size)  # the smallest part comes first
        self.assertEqual(1, self.get_until_done(s, out))  # map.pmtiles is cut once too (routing.tar.gz is under 50 KB)
        ranged = [r for r in s.opts.log if r["range"]]
        self.assertTrue(any(r["range"] == "bytes=50000-" and r["status"] == 206 and r["if_match"] for r in ranged), s.opts.log)
        with redirect_stdout(io.StringIO()):
            self.assertTrue(read_pack.verify(out, read_pack.read_manifest(out)))

    def test_an_ignored_range_starts_over_and_is_still_correct(self):
        s = self.serve(cut_after=50_000, ignore_range=True)
        out = self.tmp / "got"
        self.assertEqual(2, self.get_until_done(s, out))  # search.sqlite and map.pmtiles, once each
        self.assertTrue(any(r["range"] and r["status"] == 200 for r in s.opts.log))
        with redirect_stdout(io.StringIO()):
            self.assertTrue(read_pack.verify(out, read_pack.read_manifest(out)))

    def test_a_corrupted_file_fails_as_damaged(self):
        f = self.release / "map.pmtiles"
        data = bytearray(f.read_bytes())
        data[1000] ^= 0xFF
        f.unlink()  # a hard link to the input pack: replace it, never write through it
        f.write_bytes(bytes(data))
        s = self.serve()
        with self.assertRaises(catalog_client.Damaged):
            self.get(s, self.tmp / "got")
        self.assertEqual(2, len([r for r in s.opts.log if r["path"].endswith("/map.pmtiles")]))

    def test_redirects_off_the_local_server_are_refused(self):
        s = self.serve()
        target = "/us-pa-202611152241/map.pmtiles"
        s.opts.redirects[target] = f"http://127.0.0.1:{s.port + 1}{target}"
        with self.assertRaises(catalog_client.Refused):
            self.get(s, self.tmp / "a")
        s.opts.redirects[target] = f"https://127.0.0.1:{s.port}{target}"
        with self.assertRaises(catalog_client.Refused):
            self.get(s, self.tmp / "b")
        rules = catalog_client.Rules("https://github.com/O/R/releases/download/catalog/catalog.json")
        self.assertFalse(rules.redirect_allowed("https://github.com/x", "http://example.org/x"))
        self.assertFalse(rules.redirect_allowed("https://github.com/x", "https://127.0.0.1/x"))
        self.assertFalse(rules.redirect_allowed("https://github.com/x", "https://10.200.0.5/x"))
        self.assertTrue(rules.redirect_allowed("https://github.com/x", "https://release-assets.githubusercontent.com/x?sig=1"))
        with self.assertRaises(catalog_client.Refused):
            catalog_client.Rules("http://example.org/catalog/catalog.json")

    def test_the_catalog_is_the_same_bytes_when_built_twice(self):
        os.environ["SOURCE_DATE_EPOCH"] = "1763246467"
        try:
            a, b = self.tmp / "a", self.tmp / "b"
            self.assertEqual(0, build("--out", a, "--pack", self.pack))
            self.assertEqual(0, build("--out", b, "--pack", self.pack))
        finally:
            del os.environ["SOURCE_DATE_EPOCH"]
        self.assertEqual((a / "catalog/catalog.json").read_bytes(), (b / "catalog/catalog.json").read_bytes())
        self.assertEqual((a / "us-pa-202611152241/routing.tar.gz").read_bytes(), (b / "us-pa-202611152241/routing.tar.gz").read_bytes())
        # a second run over the same site changes nothing
        before = (self.site / "catalog/catalog.json").read_bytes()
        self.assertEqual(0, build("--out", self.site, "--pack", self.pack))
        self.assertEqual(before, (self.site / "catalog/catalog.json").read_bytes())

    def test_a_rerun_reuses_transfer_json_without_compressing(self):
        (self.site / "catalog" / "catalog.json").unlink()  # an interrupted publish: release folder there, list not
        gz = self.release / "routing.tar.gz"
        mtime = gz.stat().st_mtime_ns
        real = build_catalog.gzip.GzipFile
        build_catalog.gzip.GzipFile = None  # compressing would fail now
        try:
            self.assertEqual(0, build("--out", self.site, "--pack", self.pack), build.last_err)
        finally:
            build_catalog.gzip.GzipFile = real
        self.assertIn("(reused)", build.last_out)
        self.assertEqual(mtime, gz.stat().st_mtime_ns)

    def test_a_published_release_folder_is_left_untouched(self):
        before = {p.name: p.read_bytes() for p in self.release.iterdir()}
        changed = make_pack(self.tmp / "us-pa-changed", seed=2)  # same pack_version, other bytes
        self.assertEqual(0, build("--out", self.site, "--pack", changed), build.last_err)
        self.assertIn("already published", build.last_out)
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.release.iterdir()})

    def test_paths_outside_the_scope_are_refused(self):
        cat = json.loads((self.site / "catalog" / "catalog.json").read_text(encoding="utf-8"))
        for bad in ["../../x", "/abs", "http://h/x", "a/../../b", "%2e%2e/x", "a//b", "a?b"]:
            cat["packs"][0]["parts"][0]["transfer"]["path"] = bad
            with self.assertRaises(cf.CatalogRefused) as c:
                cf.parse(json.dumps(cat).encode(), "http://127.0.0.1:8765/catalog/catalog.json")
            self.assertEqual("OUTSIDE_SCOPE", c.exception.reason, bad)

    def test_two_packs_of_one_state_and_a_wrong_id_are_refused(self):
        other = make_pack(self.tmp / "us-pa-2", version=202611160000, seed=3)
        self.assertEqual(1, build("--out", self.tmp / "s2", "--pack", self.pack, "--pack", other))
        self.assertIn("same state", build.last_err)
        wrong = make_pack(self.tmp / "wrong", pack_id="us-oh")
        self.assertEqual(1, build("--out", self.tmp / "s3", "--pack", wrong))
        self.assertIn("is not us- and its state", build.last_err)

    def test_a_version_2_manifest_needs_the_flag(self):
        v2 = make_pack(self.tmp / "v2", manifest_version=2)
        self.assertEqual(1, build("--out", self.tmp / "s4", "--pack", v2))
        self.assertIn("manifest_version 2", build.last_err)
        self.assertEqual(0, build("--out", self.tmp / "s5", "--pack", v2, "--manifest-versions", "2,3"))
        cat = json.loads((self.tmp / "s5/catalog/catalog.json").read_text(encoding="utf-8"))
        self.assertEqual(202611152241, cat["packs"][0]["pack_version"])  # from created_utc

    def test_an_archive_input_gives_the_same_entry(self):
        import tarfile
        tar = self.tmp / "us-pa.tar"
        with tarfile.open(tar, "w", format=tarfile.USTAR_FORMAT) as t:
            for name in ["manifest.json", "map.pmtiles", "routing.tar", "search.sqlite"]:
                t.add(self.pack / name, arcname=name)
        self.assertEqual(0, build("--out", self.tmp / "s6", "--archive", tar), build.last_err)
        a = json.loads((self.site / "catalog/catalog.json").read_text(encoding="utf-8"))["packs"]
        b = json.loads((self.tmp / "s6/catalog/catalog.json").read_text(encoding="utf-8"))["packs"]
        self.assertEqual(a, b)

    def test_prune_removes_release_folders_no_entry_names(self):
        stray = self.site / "us-pa-1"
        stray.mkdir()
        (stray / "x").write_text("x")
        self.assertEqual(0, build("--out", self.site, "--prune"))
        self.assertFalse(stray.exists())
        self.assertTrue(self.release.is_dir())


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="g-serve-"))
        (self.tmp / "d").mkdir()
        (self.tmp / "d" / "f.bin").write_bytes(bytes(range(256)) * 10)
        self.s = Server(self.tmp)

    def tearDown(self):
        self.s.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def req(self, method="GET", path="/d/f.bin", headers=None):
        import http.client
        c = http.client.HTTPConnection("127.0.0.1", self.s.port, timeout=10)
        c.request(method, path, headers=headers or {})
        r = c.getresponse()
        body = r.read()
        c.close()
        return r, body

    def test_ranges_etags_and_methods(self):
        r, body = self.req()
        self.assertEqual(200, r.status)
        etag = r.getheader("ETag")
        self.assertFalse(etag.startswith("W/"))
        self.assertEqual("bytes", r.getheader("Accept-Ranges"))
        r, body = self.req(headers={"Range": "bytes=2500-"})
        self.assertEqual((206, "bytes 2500-2559/2560", 60), (r.status, r.getheader("Content-Range"), len(body)))
        r, _ = self.req(headers={"Range": "bytes=10-19"})
        self.assertEqual("bytes 10-19/2560", r.getheader("Content-Range"))
        r, _ = self.req(headers={"Range": "bytes=2560-"})
        self.assertEqual((416, "bytes */2560"), (r.status, r.getheader("Content-Range")))
        r, body = self.req(headers={"Range": "bytes=0-1,5-6"})
        self.assertEqual((200, 2560), (r.status, len(body)))
        r, _ = self.req(headers={"Range": "bytes=5-", "If-Match": '"other"'})
        self.assertEqual(412, r.status)
        r, _ = self.req(headers={"If-None-Match": etag})
        self.assertEqual(304, r.status)
        r, body = self.req(headers={"Range": "bytes=5-", "If-Range": '"other"'})
        self.assertEqual((200, 2560), (r.status, len(body)))
        r, body = self.req(headers={"Range": "bytes=5-", "If-Range": etag})
        self.assertEqual((206, 2555), (r.status, len(body)))
        r, _ = self.req("POST")
        self.assertEqual(405, r.status)
        r, body = self.req("HEAD")
        self.assertEqual((200, b""), (r.status, body))
        for bad in ["/d/../d/f.bin", "/d/.f", "/%2e%2e/x", "/d//f.bin", "/nothing"]:
            self.assertEqual(404, self.req(path=bad)[0].status, bad)
        self.assertNotIn("127.0.0.1", self.s.opts.out.getvalue(), "no client address without --log-clients")
        self.assertIn("Range=bytes=2500- 206 60 UA=", self.s.opts.out.getvalue())

    def test_fail_every(self):
        self.s.opts.fail_every = 2
        self.assertEqual([200, 503, 200, 503], [self.req()[0].status for _ in range(4)])


if __name__ == "__main__":
    unittest.main()
