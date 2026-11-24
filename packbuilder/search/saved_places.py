"""Reader of the app's saved places file (saved-places.json, format beamline-saved-places/1).

    python packbuilder/search/saved_places.py --check <saved-places.json>   # exit 0 when it reads

The Python side of docs/architecture-saved-places.md (sections 3 and 9), for a later client on
another device and for the emulator journey's check (10.3 step 13). Standard library only.

  load(path) / load_text(text) -> list[dict]   the places in store order (Home, Work, then the
                                               others by name key, then id), after the structure
                                               checks of rule SP52; raises SavedPlacesError
                                               ("broken" | "newer", rule SP53)
  name_key(text) -> str                        the name key of rule SP26 (Kotlin: savedNameKey)
  matching(places, typed, rules) -> list[dict] rule SP20 (Kotlin: SavedPlaces.matching)

Each place is a dict with the file's keys: id, kind ("home", "work", "other"), name ("Home" for
the home and "Work" for the work, whatever the file says), lat, lon, place_name, and subtitle and
category when present. Privacy (rule PV2): nothing here prints or raises a name, a place name or a
point; the command line prints counts only.
"""
from __future__ import annotations

import argparse
import json
import math
import re
import sys
import unicodedata
from pathlib import Path

FORMAT = "beamline-saved-places/1"
FORMAT_PREFIX = "beamline-saved-places/"
KINDS = ("home", "work", "other")
_ID = re.compile(r"s[0-9a-f]{16}")
_VERSION = re.compile(r"[1-9][0-9]{0,8}")


class SavedPlacesError(Exception):
    """The file cannot be used: kind "broken" (its structure is damaged, SP52) or "newer"
    (written by a newer app, SP53). The message is the kind only, never the file's text."""

    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


def name_key(text: str) -> str:
    """Rule SP26: NFKD; drop combining marks (combining class not 0), the variation selectors
    U+FE00-U+FE0F and format characters (Cf); lower case; remove the apostrophes ' ’ `,
    "&" -> " and "; every run of code points that are not letters, marks, numbers or symbols
    (general categories L, M, N, S) -> one space; trimmed."""
    kept = []
    for ch in unicodedata.normalize("NFKD", text):
        if unicodedata.combining(ch) != 0:
            continue
        if 0xFE00 <= ord(ch) <= 0xFE0F:
            continue
        if unicodedata.category(ch) == "Cf":
            continue
        kept.append(ch)
    t = "".join(kept).lower()
    t = t.replace("'", "").replace("’", "").replace("`", "")
    t = t.replace("&", " and ")
    out = []
    gap = False
    for ch in t:
        if unicodedata.category(ch)[0] in "LMNS":
            if gap and out:
                out.append(" ")
            gap = False
            out.append(ch)
        else:
            gap = True
    return "".join(out)


def _is_number(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)


def load_text(text: str) -> list[dict]:
    """The structure checks of SP52 (the same as the app's SavedPlacesFile.decode)."""
    try:
        root = json.loads(text)
    except ValueError:
        raise SavedPlacesError("broken") from None
    if not isinstance(root, dict):
        raise SavedPlacesError("broken")
    fmt = root.get("format")
    if not isinstance(fmt, str) or not fmt.startswith(FORMAT_PREFIX):
        raise SavedPlacesError("broken")
    version = fmt[len(FORMAT_PREFIX):]
    if not _VERSION.fullmatch(version):
        raise SavedPlacesError("broken")
    if version != "1":
        raise SavedPlacesError("newer")
    if "show_on_map" in root and not isinstance(root["show_on_map"], bool):
        raise SavedPlacesError("broken")
    if "notice" in root and not isinstance(root["notice"], str):
        raise SavedPlacesError("broken")
    arr = root.get("places")
    if not isinstance(arr, list):
        raise SavedPlacesError("broken")
    ids, homes, works, out = set(), 0, 0, []
    for e in arr:
        if not isinstance(e, dict):
            raise SavedPlacesError("broken")
        pid = e.get("id")
        if not isinstance(pid, str) or not _ID.fullmatch(pid) or pid in ids:
            raise SavedPlacesError("broken")
        ids.add(pid)
        kind = e.get("kind")
        if kind not in KINDS:
            raise SavedPlacesError("broken")
        homes += kind == "home"
        works += kind == "work"
        if homes > 1 or works > 1:
            raise SavedPlacesError("broken")
        lat, lon = e.get("lat"), e.get("lon")
        if not _is_number(lat) or not _is_number(lon) or not -90 <= lat <= 90 or not -180 <= lon <= 180:
            raise SavedPlacesError("broken")
        name, place_name = e.get("name"), e.get("place_name")
        if not isinstance(name, str) or not name or not isinstance(place_name, str) or not place_name:
            raise SavedPlacesError("broken")
        p = {"id": pid, "kind": kind, "name": {"home": "Home", "work": "Work"}.get(kind, name),
             "lat": float(lat), "lon": float(lon), "place_name": place_name}
        for k in ("subtitle", "category"):
            if k in e:
                if not isinstance(e[k], str):
                    raise SavedPlacesError("broken")
                p[k] = e[k]
        out.append(p)
    return store_order(out)


def load(path) -> list[dict]:
    try:
        raw = Path(path).read_bytes()
    except OSError:
        raise SavedPlacesError("broken") from None
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError:
        raise SavedPlacesError("broken") from None
    return load_text(text)


def _kind_rank(kind: str) -> int:
    return KINDS.index(kind)


def store_order(places: list[dict]) -> list[dict]:
    """Home, Work, then the others by name key (code point order), then id."""
    return sorted(places, key=lambda p: (_kind_rank(p["kind"]), name_key(p["name"]), p["id"]))


def _rank(q: str, n: str):
    if n == q:
        return 0
    if n.startswith(q):
        return 1
    have = [t for t in n.split(" ") if t]
    taken = [False] * len(have)
    for t in (t for t in q.split(" ") if t):
        for i, h in enumerate(have):
            if not taken[i] and h.startswith(t):
                taken[i] = True
                break
        else:
            return None
    return 2


def matching(places: list[dict], typed: str, rules: dict) -> list[dict]:
    """Rule SP20. [rules]: search_app.json's "saved" section (search_min_chars, search_max_rows)."""
    q = name_key(typed)
    if not q or len(q) < rules["search_min_chars"]:
        return []
    hits = []
    for p in places:
        n = name_key(p["name"])
        r = _rank(q, n)
        if r is not None:
            hits.append((r, _kind_rank(p["kind"]), n, p["id"], p))
    hits.sort(key=lambda h: h[:4])
    return [h[4] for h in hits[:rules["search_max_rows"]]]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Check a saved-places.json file (prints counts only).")
    ap.add_argument("--check", required=True, metavar="FILE")
    args = ap.parse_args(argv)
    try:
        places = load(args.check)
    except SavedPlacesError as e:
        print(f"saved places file: {e.kind}")
        return 1
    counts = {k: sum(1 for p in places if p["kind"] == k) for k in KINDS}
    print(f"saved places file: ok, {len(places)} places (home {counts['home']}, work {counts['work']}, "
          f"other {counts['other']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
