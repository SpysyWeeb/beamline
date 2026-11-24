"""Tests of tools/osm_fix/osm_fix.py against a pretend server (nothing here touches the network).

    python -m unittest tools/osm_fix/test_osm_fix.py
"""
import json
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import osm_fix as F  # noqa: E402


class Server:
    """A pretend API: the elements it holds, and every call made to it."""

    def __init__(self):
        self.elements = {
            ("way", 10): {"type": "way", "id": 10, "version": 3, "nodes": [1, 2, 3], "tags": {"highway": "service", "service": "driveway", "oneway": "yes"}},
            ("node", 7): {"type": "node", "id": 7, "version": 1, "lat": 40.1234567, "lon": -77.54321, "tags": {"amenity": "cafe"}},
        }
        self.calls = []
        self.fail_put = None

    def call(self, method, url, body=None, token=None, content=None):
        path = url.split("/api/0.6/", 1)[1]
        self.calls.append((method, path, body, token))
        if method == "GET":
            typ, ident = path[:-5].split("/")
            return json.dumps({"elements": [self.elements[(typ, int(ident))]]}).encode()
        if path == "changeset/create":
            return b"555"
        if path.endswith("/close"):
            return b""
        typ, ident = path.split("/")
        if self.fail_put == (typ, int(ident)):
            raise F.Refused("HTTP 409 version mismatch")
        e = self.elements[(typ, int(ident))]
        e["version"] += 1
        return str(e["version"]).encode()


class OsmFixTest(unittest.TestCase):
    def setUp(self):
        self.server = Server()
        self.dir = tempfile.TemporaryDirectory()
        self.saved = (F._call, F.PROPOSALS, F.SECRETS, F.REPO)
        F._call = self.server.call
        F.REPO = Path(self.dir.name)
        F.PROPOSALS = Path(self.dir.name) / "proposals"
        F.SECRETS = Path(self.dir.name) / "secrets.json"
        F.SECRETS.write_text(json.dumps({"dev": {"access_token": "TOKEN"}}), encoding="utf-8")

    def tearDown(self):
        F._call, F.PROPOSALS, F.SECRETS, F.REPO = self.saved
        self.dir.cleanup()

    def run_main(self, *argv):
        return F.main(list(argv))

    def proposal(self):
        return next(F.PROPOSALS.glob("*.json"))

    def propose(self):
        self.assertEqual(0, self.run_main("propose", "--server", "dev", "--comment", "An entrance lane, not a driveway", "way/10", "--remove", "service"))
        return self.proposal()

    def test_a_proposal_changes_only_the_tags_asked_for(self):
        p = json.loads(self.propose().read_text(encoding="utf-8"))
        c = p["changes"][0]
        self.assertEqual({"highway": "service", "oneway": "yes"}, c["after"])
        self.assertEqual(3, c["version"])
        self.assertEqual(["      - service = driveway"], F._diff(c["before"], c["after"]))
        self.assertTrue(all(m == "GET" for m, *_ in self.server.calls), "proposing uploads nothing")

    def test_nothing_goes_up_without_the_approval(self):
        path = self.propose()
        self.server.calls.clear()
        self.assertEqual(0, self.run_main("upload", str(path)))
        self.assertEqual([], self.server.calls)

    def test_an_approved_upload_sends_the_way_as_it_was_with_the_new_tags(self):
        path = self.propose()
        self.server.calls.clear()
        self.assertEqual(0, self.run_main("upload", str(path), "--approved"))
        puts = [(p, b) for m, p, b, t in self.server.calls if m == "PUT"]
        self.assertEqual(["changeset/create", "way/10", "changeset/555/close"], [p for p, _ in puts])
        tags = {t.get("k"): t.get("v") for t in ET.fromstring(puts[0][1]).iter("tag")}
        self.assertEqual({"comment": "An entrance lane, not a driveway", "created_by": F.CREATED_BY, "source": "local knowledge"}, tags)
        way = ET.fromstring(puts[1][1]).find("way")
        self.assertEqual(("10", "3", "555"), (way.get("id"), way.get("version"), way.get("changeset")))
        self.assertEqual(["1", "2", "3"], [n.get("ref") for n in way.findall("nd")], "the way's nodes go back unchanged")
        self.assertEqual({"highway": "service", "oneway": "yes"}, {t.get("k"): t.get("v") for t in way.findall("tag")})
        self.assertTrue(all(t == "TOKEN" for m, p, b, t in self.server.calls if m == "PUT"))
        self.assertEqual(555, json.loads(path.read_text(encoding="utf-8"))["uploaded"])
        # the same file is not uploaded a second time
        self.server.calls.clear()
        self.assertEqual(1, self.run_main("upload", str(path), "--approved"))
        self.assertEqual([], self.server.calls)

    def test_an_element_changed_on_the_server_since_is_refused(self):
        path = self.propose()
        self.server.elements[("way", 10)]["version"] = 4
        self.server.calls.clear()
        self.assertEqual(1, self.run_main("upload", str(path), "--approved"))
        self.assertFalse(any(m == "PUT" for m, *_ in self.server.calls), "no changeset is opened")

    def test_a_node_keeps_its_position(self):
        self.assertEqual(0, self.run_main("propose", "--server", "dev", "--comment", "Name added", "node/7", "--set", "name=Corner Cafe"))
        self.assertEqual(0, self.run_main("upload", str(self.proposal()), "--approved"))
        node = ET.fromstring(next(b for m, p, b, t in self.server.calls if m == "PUT" and p == "node/7")).find("node")
        self.assertEqual(("40.1234567", "-77.54321"), (node.get("lat"), node.get("lon")))
        self.assertEqual({"amenity": "cafe", "name": "Corner Cafe"}, {t.get("k"): t.get("v") for t in node.findall("tag")})

    def test_what_the_tool_refuses(self):
        long = "x" * 256
        for argv in (
            ("propose", "--server", "dev", "--comment", "c", "relation/5", "--set", "a=b"),          # not a node or a way
            ("propose", "--server", "dev", "--comment", "c", "way/10", "--remove", "name"),           # no such tag
            ("propose", "--server", "dev", "--comment", "c", "way/10", "--set", "highway=service"),   # nothing changes
            ("propose", "--server", "dev", "--comment", long, "way/10", "--remove", "service"),       # comment too long
            ("propose", "--server", "dev", "way/10", "--remove", "service"),                          # no comment
            ("propose", "--server", "dev", "--comment", "c", "node/7", "--remove", "amenity"),        # would leave no tag
        ):
            self.assertEqual(1, self.run_main(*argv), argv)
        self.assertFalse(F.PROPOSALS.exists() and list(F.PROPOSALS.glob("*.json")))

    def test_a_failure_part_way_closes_the_changeset_and_says_so(self):
        path = self.propose()
        self.assertEqual(0, self.run_main("propose", "--server", "dev", "--into", str(path), "node/7", "--set", "name=Corner Cafe"))
        self.server.fail_put = ("node", 7)
        self.server.calls.clear()
        self.assertEqual(1, self.run_main("upload", str(path), "--approved"))
        self.assertEqual("changeset/555/close", [p for m, p, b, t in self.server.calls if m == "PUT"][-1])
        p = json.loads(path.read_text(encoding="utf-8"))
        self.assertNotIn("uploaded", p)
        self.assertEqual(555, p["stopped_in_changeset"])
        self.assertEqual(1, len(p["uploaded_elements"]))
        self.assertEqual(1, self.run_main("upload", str(path), "--approved"), "not sent again blindly")

    def test_at_most_five_elements(self):
        path = self.propose()
        for i in range(20, 24):
            self.server.elements[("node", i)] = {"type": "node", "id": i, "version": 1, "lat": 1.0, "lon": 2.0, "tags": {"a": "b"}}
            self.assertEqual(0, self.run_main("propose", "--server", "dev", "--into", str(path), f"node/{i}", "--set", "c=d"))
        self.server.elements[("node", 30)] = {"type": "node", "id": 30, "version": 1, "lat": 1.0, "lon": 2.0, "tags": {"a": "b"}}
        self.assertEqual(1, self.run_main("propose", "--server", "dev", "--into", str(path), "node/30", "--set", "c=d"))


if __name__ == "__main__":
    unittest.main()
