"""Fetch the official SQLite command-line tools for Windows (sqlite3.exe,
sqlite3_analyzer.exe) into tools/bin/sqlite/.

    python tools/fetch_sqlite_tools.py

Version 3.50.1 is the SQLite version that androidx.sqlite:sqlite-bundled 2.7.x
compiles in, so the CLI doubles as a cross-check of the search index on that
SQLite version. Used by packbuilder/search/measure.py for per-table sizes
(sqlite3_analyzer / the DBSTAT table, which Python's own sqlite3 lacks).
Licence: SQLite is in the public domain (https://sqlite.org/copyright.html).
The SHA-256 below was computed on first download (2026-11-15); sqlite.org
publishes SHA3-256, recorded here as well.
"""
import hashlib
import os
import sys
import urllib.request
import zipfile

VERSION = "3.50.1"
URL = "https://www.sqlite.org/2025/sqlite-tools-win-x64-3500100.zip"
SHA256 = "5e9c73d344660479c8dca6f8dbbef41d4d3bf25cff56554cb85823ae447a22ec"
SHA3_256 = "a9b26ca6e415f61ada511a14fb2166c9278de3b471702281dd02f3ce97288cfa"

HERE = os.path.dirname(os.path.abspath(__file__))
DEST = os.path.join(HERE, "bin", "sqlite")
CACHE = os.path.join(HERE, "cache")


def main() -> None:
    exe = os.path.join(DEST, "sqlite3_analyzer.exe")
    if os.path.exists(exe) and open(os.path.join(DEST, "VERSION")).read().strip() == VERSION:
        print("sqlite tools", VERSION, "already in", DEST)
        return
    os.makedirs(CACHE, exist_ok=True)
    zpath = os.path.join(CACHE, os.path.basename(URL))
    if not os.path.exists(zpath):
        print("downloading", URL)
        with urllib.request.urlopen(URL, timeout=300) as r, open(zpath + ".part", "wb") as f:
            f.write(r.read())
        os.replace(zpath + ".part", zpath)
    data = open(zpath, "rb").read()
    if hashlib.sha256(data).hexdigest() != SHA256:
        sys.exit(f"SHA-256 mismatch for {zpath}")
    os.makedirs(DEST, exist_ok=True)
    with zipfile.ZipFile(zpath) as z:
        for name in z.namelist():
            if name.endswith(".exe") and "/" not in name:
                with z.open(name) as src, open(os.path.join(DEST, name), "wb") as dst:
                    dst.write(src.read())
    with open(os.path.join(DEST, "VERSION"), "w") as f:
        f.write(VERSION + "\n")
    print("installed sqlite tools", VERSION, "into", DEST)


if __name__ == "__main__":
    main()
