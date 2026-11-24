"""Fetch the pinned SPDX licence list (ids) and licence texts for notices.py.

Writes android/feature/about/notices/spdx-ids.json (every licence and exception id of the SPDX
list at TAG) and android/feature/about/notices/licences/<id>.txt for each id named with --ids, or,
without --ids, for every id the curated list uses that has no text yet. LicenseRef-* texts are
this project's own and are written by hand. Plain GETs to the public SPDX repository, nothing sent.

  python android/feature/about/scripts/fetch_spdx.py [--ids Apache-2.0 MIT ...]
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import notices  # noqa: E402

TAG = "v3.29.0"
BASE = f"https://raw.githubusercontent.com/spdx/license-list-data/{TAG}"


def get(url: str) -> bytes:
    # curl when there is one: on the build PC urllib waited about 80 s per request (a slow first
    # address family), curl 0.4 s.
    if shutil.which("curl"):
        return subprocess.run(["curl", "-sSfL", "-m", "60", url], check=True, capture_output=True).stdout
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids", nargs="*")
    a = ap.parse_args()
    lic = json.loads(get(f"{BASE}/json/licenses.json"))
    exc = json.loads(get(f"{BASE}/json/exceptions.json"))
    ids = {
        "about": f"Licence and exception ids of the SPDX License List {TAG} "
                 "(github.com/spdx/license-list-data), fetched by scripts/fetch_spdx.py.",
        "version": lic.get("licenseListVersion", TAG),
        "licenses": sorted(x["licenseId"] for x in lic["licenses"]),
        "exceptions": sorted(x["licenseExceptionId"] for x in exc["exceptions"]),
    }
    notices.SPDX_IDS.write_text(json.dumps(ids, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {notices.SPDX_IDS.relative_to(notices.REPO).as_posix()}: {len(ids['licenses'])} licences, {len(ids['exceptions'])} exceptions")
    want = set(a.ids or [])
    if not a.ids:
        for e in notices.entries_of(notices.load_source()):
            expr = notices.parse_spdx(e.licence)
            want.update(i.rstrip("+") for i in expr.ids() + expr.exceptions())
    notices.TEXTS.mkdir(parents=True, exist_ok=True)
    for ident in sorted(want):
        if ident.startswith("LicenseRef-"):
            continue
        out = notices.TEXTS / f"{ident}.txt"
        if out.exists() and a.ids is None:
            continue
        # No e-mail address in a tracked file (round privacy rule): curl's and FTL's texts name some.
        text = notices.without_emails(get(f"{BASE}/text/{ident}.txt").decode("utf-8").replace("\r\n", "\n"))
        out.write_bytes(text.encode("utf-8"))
        print(f"wrote {out.relative_to(notices.REPO).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
