"""Small, reviewed tag fixes to OpenStreetMap under the mapper's own account.

The mapper points at something wrong on the ground ("this entrance lane is tagged as a driveway"); a
proposal file is written and shown (the element, its tags before and after, the changeset comment); the
mapper says yes; only then is it uploaded. Every edit is reviewed by the account holder one by one, so
this is ordinary mapping, not an automated edit (OpenStreetMap's Automated Edits code of conduct covers
changes made without individual review).

What this tool can do, and nothing else:
  - change the tags of nodes and ways that already exist (a way's nodes and a node's position are sent
    back exactly as the server has them);
  - at most MAX_ELEMENTS elements in one changeset;
  - refuse the upload when an element changed on the server after the proposal was written.
It cannot create, move or delete anything, and it does not touch relations.

Commands (run from the repository root; --server dev is the test server, live the real map):

  python tools/osm_fix/osm_fix.py auth --server live --client-id <id of your OAuth 2 application>
  python tools/osm_fix/osm_fix.py check --server live
  python tools/osm_fix/osm_fix.py show --server live way/123456
  python tools/osm_fix/osm_fix.py around --server live 40.0000 -77.0000 --radius 60
  python tools/osm_fix/osm_fix.py propose --server live --comment "..." --source "local knowledge" \
        way/123456 --set highway=service --remove service [--into data/osm-fix/<file>.json]
  python tools/osm_fix/osm_fix.py upload data/osm-fix/<file>.json --approved

The sign-in (auth) opens the OpenStreetMap approval page in the browser and waits for its answer on
http://127.0.0.1:8756/callback (register exactly that redirect URI, permission "Modify the map" only,
"Confidential application" unticked). The token is written to data/secrets/osm-fix.json, which git
ignores; it is never printed.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import http.server
import json
import math
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SECRETS = REPO / "data" / "secrets" / "osm-fix.json"
PROPOSALS = REPO / "data" / "osm-fix"
SERVERS = {
    "live": {"web": "https://www.openstreetmap.org", "api": "https://api.openstreetmap.org"},
    "dev": {"web": "https://master.apis.dev.openstreetmap.org", "api": "https://master.apis.dev.openstreetmap.org"},
}
PORT = 8756
REDIRECT = f"http://127.0.0.1:{PORT}/callback"
SCOPE = "write_api"
AGENT = "osm-fix/0.1"
CREATED_BY = "osm-fix 0.1"
MAX_ELEMENTS = 5
MAX_COMMENT = 255
MAX_AROUND_M = 250.0
TYPES = ("node", "way")


class Refused(Exception):
    """Something this tool will not do, or the server would not take. The text is for the terminal."""


# ---- stored sign-ins -------------------------------------------------------------------------------

def _load() -> dict:
    return json.loads(SECRETS.read_text(encoding="utf-8")) if SECRETS.exists() else {}


def _save(d: dict) -> None:
    SECRETS.parent.mkdir(parents=True, exist_ok=True)
    SECRETS.write_text(json.dumps(d, indent=1), encoding="utf-8")


def _token(server: str) -> str:
    t = _load().get(server, {}).get("access_token")
    if not t:
        raise Refused(f"not signed in on the {server} server: run the auth command first")
    return t


# ---- http ------------------------------------------------------------------------------------------

def _call(method: str, url: str, body: bytes | None = None, token: str | None = None, content: str | None = None) -> bytes:
    req = urllib.request.Request(url, data=body, method=method, headers={"User-Agent": AGENT})
    if token:
        req.add_header("Authorization", "Bearer " + token)
    if content:
        req.add_header("Content-Type", content)
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            return r.read()
    except urllib.error.HTTPError as e:
        # the server's own words say why (a version conflict, a closed changeset); they hold no secret
        raise Refused(f"{method} {urllib.parse.urlsplit(url).path}: HTTP {e.code} {e.read()[:300].decode('utf-8', 'replace')}") from None
    except urllib.error.URLError as e:
        raise Refused(f"{method} {urllib.parse.urlsplit(url).path}: no answer ({e.reason})") from None


def fetch(server: str, typ: str, ident: int) -> dict:
    """The element as the server has it now: {"type", "id", "version", "tags", and "nodes" or "lat"/"lon"}."""
    d = json.loads(_call("GET", f"{SERVERS[server]['api']}/api/0.6/{typ}/{ident}.json"))
    e = d["elements"][0]
    if e.get("visible") is False:
        raise Refused(f"{typ}/{ident} is deleted")
    e.setdefault("tags", {})
    return e


# ---- sign-in ---------------------------------------------------------------------------------------

def cmd_auth(args) -> None:
    web = SERVERS[args.server]["web"]
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(24)
    got: dict = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802
            q = urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query)
            if urllib.parse.urlsplit(self.path).path != "/callback":
                self.send_response(404)
                self.end_headers()
                return
            got["state"] = q.get("state", [""])[0]
            got["code"] = q.get("code", [""])[0]
            got["error"] = q.get("error", [""])[0]
            ok = bool(got["code"]) and got["state"] == state
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(("<h2>Signed in. You can close this tab.</h2>" if ok else "<h2>Sign-in did not work. Close this tab and look at the terminal.</h2>").encode())

        def log_message(self, *a):  # the request line holds the one-time code: never logged
            pass

    srv = http.server.HTTPServer(("127.0.0.1", PORT), Handler)
    srv.timeout = 5
    url = web + "/oauth2/authorize?" + urllib.parse.urlencode({
        "response_type": "code", "client_id": args.client_id, "redirect_uri": REDIRECT, "scope": SCOPE,
        "state": state, "code_challenge": challenge, "code_challenge_method": "S256"})
    print(f"Opening the {args.server} server's approval page in the browser. Waiting up to {args.wait} s for the answer...", flush=True)
    if not args.no_browser:
        webbrowser.open(url)
    else:
        print("Open this address in the browser:\n  " + url, flush=True)
    end = time.time() + args.wait
    while "code" not in got and time.time() < end:
        srv.handle_request()
    srv.server_close()
    if not got.get("code"):
        raise Refused("no approval arrived" + (f" (the server said: {got['error']})" if got.get("error") else ""))
    if got["state"] != state:
        raise Refused("the answer did not belong to this sign-in; nothing was saved")
    body = urllib.parse.urlencode({"grant_type": "authorization_code", "code": got["code"], "redirect_uri": REDIRECT,
                                   "client_id": args.client_id, "code_verifier": verifier}).encode()
    tok = json.loads(_call("POST", web + "/oauth2/token", body, content="application/x-www-form-urlencoded"))
    if not tok.get("access_token"):
        raise Refused("the server gave no token")
    d = _load()
    d[args.server] = {"client_id": args.client_id, "access_token": tok["access_token"], "scope": tok.get("scope", ""), "created": int(time.time())}
    _save(d)
    print(f"Signed in on the {args.server} server (scope: {tok.get('scope', '?')}). The token is in data/secrets/osm-fix.json.")


def cmd_check(args) -> None:
    """What the stored token may do, as the server says (no account details are asked for)."""
    d = json.loads(_call("GET", f"{SERVERS[args.server]['api']}/api/0.6/permissions.json", token=_token(args.server)))
    perms = d.get("permissions", [])
    print(f"{args.server}: the token allows {', '.join(perms) if perms else 'nothing'}")
    if "allow_write_api" not in perms:
        raise Refused("the token cannot modify the map: register the application with \"Modify the map\" and sign in again")


# ---- looking ---------------------------------------------------------------------------------------

def _ref(text: str) -> tuple[str, int]:
    typ, _, ident = text.partition("/")
    if typ not in TYPES or not ident.isdigit():
        raise Refused(f"'{text}' is not node/<id> or way/<id>")
    return typ, int(ident)


def _tags_text(tags: dict) -> str:
    return "\n".join(f"      {k} = {v}" for k, v in sorted(tags.items())) or "      (no tags)"


def cmd_show(args) -> None:
    typ, ident = _ref(args.element)
    e = fetch(args.server, typ, ident)
    extra = f"{len(e['nodes'])} nodes" if typ == "way" else f"at {e['lat']:.6f}, {e['lon']:.6f}"
    print(f"{typ}/{ident} version {e['version']} ({extra}), last changed {e.get('timestamp', '?')}\n{_tags_text(e['tags'])}")


def cmd_around(args) -> None:
    """The roads (and tagged nodes with --nodes) within a small box, for finding the element meant."""
    r = min(args.radius, MAX_AROUND_M)
    dlat = r / 111_320.0
    dlon = r / (111_320.0 * math.cos(math.radians(args.lat)))
    bbox = f"{args.lon - dlon:.6f},{args.lat - dlat:.6f},{args.lon + dlon:.6f},{args.lat + dlat:.6f}"
    d = json.loads(_call("GET", f"{SERVERS[args.server]['api']}/api/0.6/map.json?bbox={bbox}"))
    nodes = {e["id"]: e for e in d["elements"] if e["type"] == "node"}

    def metres(lat, lon):
        return math.hypot((lat - args.lat) * 111_320.0, (lon - args.lon) * 111_320.0 * math.cos(math.radians(args.lat)))

    rows = []
    for e in d["elements"]:
        tags = e.get("tags", {})
        if e["type"] == "way" and ("highway" in tags or args.all):
            pts = [nodes[n] for n in e["nodes"] if n in nodes]
            if pts:
                rows.append((min(metres(p["lat"], p["lon"]) for p in pts), f"way/{e['id']} v{e['version']} ({len(e['nodes'])} nodes)", tags))
        elif e["type"] == "node" and tags and args.nodes:
            rows.append((metres(e["lat"], e["lon"]), f"node/{e['id']} v{e['version']}", tags))
    for dist, head, tags in sorted(rows, key=lambda x: x[0])[: args.limit]:
        print(f"{dist:5.0f} m  {head}  " + "; ".join(f"{k}={v}" for k, v in sorted(tags.items()))[:230])
    print(f"{len(rows)} found within about {r:.0f} m")


# ---- proposing -------------------------------------------------------------------------------------

def _apply(tags: dict, sets: list[str], removes: list[str]) -> dict:
    out = dict(tags)
    for k in removes:
        if k not in out:
            raise Refused(f"cannot remove '{k}': the element has no such tag")
        del out[k]
    for kv in sets:
        k, sep, v = kv.partition("=")
        k, v = k.strip(), v.strip()
        if not sep or not k or not v:
            raise Refused(f"'{kv}' is not key=value")
        if len(k) > 255 or len(v) > 255:
            raise Refused(f"'{k}': keys and values are at most 255 characters")
        out[k] = v
    return out


def _diff(before: dict, after: dict) -> list[str]:
    lines = []
    for k in sorted(set(before) | set(after)):
        if k not in after:
            lines.append(f"      - {k} = {before[k]}")
        elif k not in before:
            lines.append(f"      + {k} = {after[k]}")
        elif before[k] != after[k]:
            lines.append(f"      ~ {k} = {before[k]}  ->  {after[k]}")
    return lines


def show_proposal(p: dict) -> None:
    print(f"Proposal for the {p['server']} server ({'the real map' if p['server'] == 'live' else 'the test server'})")
    print(f"  Changeset comment: {p['comment']}")
    print(f"  Source: {p['source']}")
    for c in p["changes"]:
        print(f"  {c['type']}/{c['id']} (version {c['version']}): {SERVERS[p['server']]['web']}/{c['type']}/{c['id']}")
        for line in _diff(c["before"], c["after"]):
            print(line)
        same = sorted(k for k in c["after"] if c["before"].get(k) == c["after"][k])
        print("      unchanged: " + (", ".join(f"{k}={c['after'][k]}" for k in same)[:300] or "(none)"))


def cmd_propose(args) -> None:
    typ, ident = _ref(args.element)
    if args.into and Path(args.into).exists():
        p = json.loads(Path(args.into).read_text(encoding="utf-8"))
        if p["server"] != args.server:
            raise Refused("the proposal file is for the other server")
        if p.get("uploaded"):
            raise Refused("that proposal was uploaded already")
        if args.comment:
            p["comment"] = args.comment
    else:
        if not args.comment:
            raise Refused("a changeset comment is needed (--comment)")
        p = {"server": args.server, "comment": args.comment, "source": args.source, "changes": []}
    if not 1 <= len(p["comment"]) <= MAX_COMMENT:
        raise Refused(f"the comment has {len(p['comment'])} characters; the limit is {MAX_COMMENT}")
    if any(c["type"] == typ and c["id"] == ident for c in p["changes"]):
        raise Refused(f"{typ}/{ident} is in this proposal already")
    if len(p["changes"]) >= MAX_ELEMENTS:
        raise Refused(f"at most {MAX_ELEMENTS} elements in one changeset")
    e = fetch(args.server, typ, ident)
    after = _apply(e["tags"], args.set or [], args.remove or [])
    if after == e["tags"]:
        raise Refused("nothing would change")
    if not after:
        raise Refused("this would leave the element without any tag")
    p["changes"].append({"type": typ, "id": ident, "version": e["version"], "before": e["tags"], "after": after})
    PROPOSALS.mkdir(parents=True, exist_ok=True)
    path = Path(args.into) if args.into else PROPOSALS / (time.strftime("%Y%m%d-%H%M%S") + f"-{typ}{ident}.json")
    path.write_text(json.dumps(p, indent=1, ensure_ascii=False), encoding="utf-8")
    show_proposal(p)
    shown = path.resolve()
    print(f"Written to {shown.relative_to(REPO).as_posix() if shown.is_relative_to(REPO) else shown}. Not uploaded.")


# ---- uploading -------------------------------------------------------------------------------------

def _element_xml(e: dict, tags: dict, changeset: int) -> bytes:
    """The element exactly as the server has it (a way's nodes, a node's position), with the new tags."""
    root = ET.Element("osm")
    attrs = {"id": str(e["id"]), "version": str(e["version"]), "changeset": str(changeset)}
    if e["type"] == "node":
        attrs["lat"], attrs["lon"] = repr(e["lat"]), repr(e["lon"])
    el = ET.SubElement(root, e["type"], attrs)
    for n in e.get("nodes", []):
        ET.SubElement(el, "nd", {"ref": str(n)})
    for k, v in tags.items():
        ET.SubElement(el, "tag", {"k": k, "v": v})
    return ET.tostring(root, encoding="utf-8")


def cmd_upload(args) -> None:
    path = Path(args.proposal)
    p = json.loads(path.read_text(encoding="utf-8"))
    if p.get("uploaded"):
        raise Refused(f"uploaded already as changeset {p['uploaded']}")
    if p.get("stopped_in_changeset"):
        raise Refused(f"an upload of this file stopped part-way in changeset {p['stopped_in_changeset']}: look at it, then propose what is left again")
    if not p["changes"] or len(p["changes"]) > MAX_ELEMENTS:
        raise Refused(f"a proposal holds 1 to {MAX_ELEMENTS} elements")
    if not 1 <= len(p["comment"]) <= MAX_COMMENT:
        raise Refused("the comment is missing or too long")
    show_proposal(p)
    if not args.approved:
        print("Not uploaded: the account holder has to see this and say yes first (then run again with --approved).")
        return
    server, api = p["server"], SERVERS[p["server"]]["api"]
    token = _token(server)
    # nothing may have changed on the server since the proposal was shown
    now = []
    for c in p["changes"]:
        if c["type"] not in TYPES:
            raise Refused("only nodes and ways")
        e = fetch(server, c["type"], c["id"])
        if e["version"] != c["version"] or e["tags"] != c["before"]:
            raise Refused(f"{c['type']}/{c['id']} changed on the server after the proposal was written (now version {e['version']}): propose again")
        now.append(e)
    cs = ET.Element("osm")
    el = ET.SubElement(cs, "changeset")
    for k, v in (("comment", p["comment"]), ("created_by", CREATED_BY), ("source", p["source"])):
        if v:
            ET.SubElement(el, "tag", {"k": k, "v": v})
    changeset = int(_call("PUT", f"{api}/api/0.6/changeset/create", ET.tostring(cs, encoding="utf-8"), token, "text/xml").decode().strip())
    done, problem = [], None
    try:
        for c, e in zip(p["changes"], now):
            new = _call("PUT", f"{api}/api/0.6/{c['type']}/{c['id']}", _element_xml(e, c["after"], changeset), token, "text/xml").decode().strip()
            done.append(f"{c['type']}/{c['id']} is now version {new}")
    except Refused as ex:
        problem = ex
    # the changeset is closed whatever happened, and the file says what went up
    try:
        _call("PUT", f"{api}/api/0.6/changeset/{changeset}/close", b"", token)
    except Refused as ex:
        problem = problem or ex
    p["uploaded_elements"] = done
    p["uploaded" if problem is None and len(done) == len(p["changes"]) else "stopped_in_changeset"] = changeset
    path.write_text(json.dumps(p, indent=1, ensure_ascii=False), encoding="utf-8")
    for line in done:
        print("  " + line)
    if problem is not None:
        raise Refused(f"changeset {changeset} stopped after {len(done)} of {len(p['changes'])} elements ({SERVERS[server]['web']}/changeset/{changeset}): {problem}")
    print(f"Uploaded as changeset {changeset}: {SERVERS[server]['web']}/changeset/{changeset}")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)

    def server(sp):
        sp.add_argument("--server", choices=sorted(SERVERS), required=True)

    a = sub.add_parser("auth")
    server(a)
    a.add_argument("--client-id", required=True)
    a.add_argument("--wait", type=int, default=300)
    a.add_argument("--no-browser", action="store_true")
    a.set_defaults(fn=cmd_auth)
    a = sub.add_parser("check")
    server(a)
    a.set_defaults(fn=cmd_check)
    a = sub.add_parser("show")
    server(a)
    a.add_argument("element")
    a.set_defaults(fn=cmd_show)
    a = sub.add_parser("around")
    server(a)
    a.add_argument("lat", type=float)
    a.add_argument("lon", type=float)
    a.add_argument("--radius", type=float, default=60.0)
    a.add_argument("--nodes", action="store_true")
    a.add_argument("--all", action="store_true")
    a.add_argument("--limit", type=int, default=30)
    a.set_defaults(fn=cmd_around)
    a = sub.add_parser("propose")
    server(a)
    a.add_argument("element")
    a.add_argument("--set", action="append")
    a.add_argument("--remove", action="append")
    a.add_argument("--comment")
    a.add_argument("--source", default="local knowledge")
    a.add_argument("--into")
    a.set_defaults(fn=cmd_propose)
    a = sub.add_parser("upload")
    a.add_argument("proposal")
    a.add_argument("--approved", action="store_true")
    a.set_defaults(fn=cmd_upload)
    args = ap.parse_args(argv)
    try:
        args.fn(args)
    except Refused as e:
        print("Refused: " + str(e), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
