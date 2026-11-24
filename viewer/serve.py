"""Serve the offline map viewer on http://127.0.0.1:8765/ (standard library only).

Usage (from the repo root):
    python viewer/serve.py [--port 8765] [--packs data/map/packs]

URL layout:
    /          -> viewer/                         (index.html, app.js, vendor/)
    /style/    -> packbuilder/map/style-bundle/   (day.json, night.json, glyphs/, sprites/)
    /packs/    -> data/map/packs/ (or --packs)    (*.pmtiles; listing at /packs/index.json)

PMTiles are read with HTTP Range requests, which Python's built-in
http.server does not support, hence this small server. It binds to 127.0.0.1
only and never contacts the network.
"""
import argparse
import json
import os
import re
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parent.parent
MOUNTS = {}

TYPES = {
    ".mjs": "text/javascript", ".js": "text/javascript", ".json": "application/json",
    ".css": "text/css", ".html": "text/html; charset=utf-8", ".pbf": "application/x-protobuf",
    ".pmtiles": "application/octet-stream", ".png": "image/png",
}


class Handler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):
        if os.environ.get("VIEWER_LOG"):
            super().log_message(fmt, *args)

    def resolve(self):
        path = unquote(urlparse(self.path).path)
        for prefix in sorted(MOUNTS, key=len, reverse=True):
            if path == prefix.rstrip("/") or path.startswith(prefix):
                base = MOUNTS[prefix]
                rel = path[len(prefix):].lstrip("/")
                target = (base / rel).resolve()
                if base.resolve() not in target.parents and target != base.resolve():
                    return None
                if target.is_dir():
                    target = target / "index.html"
                return target
        return None

    def do_HEAD(self):
        self.do_GET(head=True)

    def do_GET(self, head=False):
        path = urlparse(self.path).path
        if path == "/packs/index.json":
            packs = sorted(p.name for p in MOUNTS["/packs/"].glob("*.pmtiles"))
            return self.send_bytes(json.dumps(packs).encode(), "application/json", head)
        target = self.resolve()
        if target is None or not target.is_file():
            self.send_error(404)
            return
        size = target.stat().st_size
        ctype = TYPES.get(target.suffix, "application/octet-stream")
        rng = self.headers.get("Range")
        m = re.match(r"bytes=(\d*)-(\d*)$", rng or "")
        start, end = 0, size - 1
        if m and (m.group(1) or m.group(2)):
            start = int(m.group(1)) if m.group(1) else max(0, size - int(m.group(2)))
            end = int(m.group(2)) if (m.group(2) and m.group(1)) else size - 1
            end = min(end, size - 1)
            if start > end:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.end_headers()
                return
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if head:
            return
        with open(target, "rb") as f:  # stream in 1 MiB chunks; never the whole file in memory
            f.seek(start)
            left = end - start + 1
            while left > 0:
                chunk = f.read(min(left, 1 << 20))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)

    def send_bytes(self, body, ctype, head=False):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not head:
            self.wfile.write(body)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--packs", default=str(ROOT / "data" / "map" / "packs"))
    a = ap.parse_args()
    MOUNTS["/style/"] = ROOT / "packbuilder" / "map" / "style-bundle"
    MOUNTS["/packs/"] = Path(a.packs)
    MOUNTS["/"] = ROOT / "viewer"
    srv = ThreadingHTTPServer(("127.0.0.1", a.port), Handler)
    print(f"viewer on http://127.0.0.1:{a.port}/  (packs from {a.packs})", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
