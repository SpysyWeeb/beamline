"""Check that every search category and every mapped POI kind has an icon in the sprite.

Usage (from the repo root; standard library only):
    python packbuilder/map/style/check_poi_icons.py

Reads packbuilder/search/categories.json (search track, read-only),
assets/poi_icons.json and assets/icons/icons.json. Exits 1 and lists the
problems if a category has no icon, an icon name is not in icons.json, or
poi_icons.json maps a category id that categories.json does not have.
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]


def main() -> int:
    cats = json.loads((REPO / "packbuilder" / "search" / "categories.json").read_text(encoding="utf-8"))["categories"]
    mapping = json.loads((REPO / "assets" / "poi_icons.json").read_text(encoding="utf-8"))
    icons = json.loads((REPO / "assets" / "icons" / "icons.json").read_text(encoding="utf-8"))["icons"]
    problems = []
    ids = [c["id"] for c in cats]
    for c in cats:
        icon = mapping["categories"].get(c["id"])
        if not icon:
            problems.append(f"category '{c['id']}' ({c.get('label')}) has no icon")
        elif icon not in icons:
            problems.append(f"category '{c['id']}' -> '{icon}', which is not in assets/icons/icons.json")
    for cid in mapping["categories"]:
        if cid not in ids:
            problems.append(f"poi_icons.json maps unknown category '{cid}'")
    for kind, icon in mapping["map_kinds"].items():
        if icon not in icons:
            problems.append(f"map kind '{kind}' -> '{icon}', which is not in assets/icons/icons.json")
    groups = {}
    for c in cats:
        groups.setdefault(c.get("group", "?"), []).append(c["id"])
    used = sorted(set(mapping["categories"].values()) | set(mapping["map_kinds"].values()))
    print(f"{len(cats)} search categories in {len(groups)} groups, {len(mapping['map_kinds'])} map kinds, "
          f"{len(used)} distinct icons used")
    for p in problems:
        print("PROBLEM:", p)
    print("ok" if not problems else f"{len(problems)} problems")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
