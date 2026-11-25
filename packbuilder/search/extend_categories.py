"""Add generated categories to categories.json for everyday places the hand-written list misses.

    python packbuilder/search/extend_categories.py --pack data/search/pa-search.sqlite          # report only
    python packbuilder/search/extend_categories.py --pack data/search/pa-search.sqlite --write  # write

docs/next-round.md item 43 (a tester, 2026-11-24: "antique stores" found two shops near
Philadelphia and none in Erie). For every iD preset of one simple tag (shop, amenity, craft,
leisure, tourism, office, healthcare = value) that no category matches yet and that at least
--min places in the given search pack carry, a category is added with the preset's English name,
its words in everyday forms (singular and plural, with and without "store"/"shop"), and the
preset itself in id_presets, so seed_categories.py copies the preset's aliases and terms (ISC).
Generated entries carry "generated": true and are replaced on the next run; hand-written ones are
never changed. Run seed_categories.py afterwards. Standard library only.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sqlite3

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
CATS = os.path.join(HERE, "categories.json")
REF_DIR = os.path.join(REPO, "data", "search", "ref", "idts")
KEYS = {"shop": "shop", "amenity": "services", "craft": "services", "office": "services",
        "healthcare": "health", "leisure": "fun", "tourism": "fun"}
# never a category of their own: too vague, private, or not a place people drive to
SKIP_VALUES = {"yes", "vacant", "no", "bench", "waste_basket", "parking_space", "bicycle_parking", "shelter", "toilets",
               "drinking_water", "vending_machine", "recycling", "post_box", "telephone", "clock", "loading_dock",
               "waste_disposal", "grit_bin", "hunting_stand", "picnic_table", "fire_pit", "garden", "pitch", "track",
               "playground", "swimming_pool", "information", "viewpoint", "artwork", "attraction", "company", "government",
               "parking_entrance", "letter_box", "atm", "charging_station", "fuel", "parking", "fountain", "bbq",
               "outdoor_seating", "slipway", "marina", "dog_park", "nature_reserve", "park", "common", "sports_centre",
               "stadium", "fitness_station", "trailhead", "camp_pitch", "lounger", "dressing_room", "water_point",
               "bleachers", "payment_terminal", "bicycle_repair_station", "public_bookcase", "post_depot", "parcel_locker",
               "studio", "social_facility", "prison", "car_sharing"}
STORE_WORDS = ("shop", "store", "stores", "shops")
# everyday words iD's presets lack (the 2026-11-24 test searches), added to the generated category
EXTRA_TERMS = {
    "shop_hairdresser": ["barber", "barbers", "barber shop", "barber shops", "barbershop", "barbershops", "hair salon", "hair salons"],
    "shop_beauty": ["nail salon", "nail salons", "nails", "beauty salon", "beauty salons"],
    "shop_bicycle": ["bike shop", "bike shops", "bike store", "bike stores"],
    "shop_craft": ["craft store", "craft stores", "craft shop", "crafts store", "hobby store"],
    "shop_musical_instrument": ["music store", "music stores", "music shop", "guitar store", "guitar shop"],
    "shop_books": ["book store", "book stores", "books", "book shop"],
    "shop_antiques": ["antique mall", "antique malls", "antiques mall"],
    "shop_second_hand": ["thrift", "consignment", "consignment shop", "secondhand store"],
    "shop_furniture": ["furniture"],
    "shop_jewelry": ["jeweler", "jewelers", "jewellery store"],
}
# iD's words that are too general for a category of their own ("old navy", "used cars", a keyboard)
GENERIC_ID_WORDS = {"old", "used", "preowned", "keyboard", "drum", "new"}
# words people do not put in the plural: no bare "furnitures" or "thrifts"
MASS = {"furniture", "thrift", "coffee", "beauty", "art", "jewelry", "music", "clothing", "seafood", "hardware", "software",
        "luggage", "equipment", "food", "wine", "tea", "spice", "fabric", "tobacco", "frozen food", "health food",
        "interior decoration", "lighting", "paint", "pottery", "erotic", "religion", "hunting", "fishing", "outdoor",
        "nutrition supplements", "electronics", "cosmetics", "photo", "printing", "copy", "rental", "storage"}


def plural(w: str) -> str:
    """The plural of one word; a word that already ends in s is left as it is (grounds, collectibles)."""
    if w.endswith("s"):
        return w
    if w.endswith(("x", "z", "ch", "sh")):
        return w + "es"
    if w.endswith("y") and len(w) > 1 and w[-2] not in "aeiou":
        return w[:-1] + "ies"
    return w + "s"


def plural_phrase(p: str) -> str:
    words = p.split(" ")
    return " ".join(words[:-1] + [plural(words[-1])])


def forms(name: str) -> list[str]:
    """Everyday forms of a preset name: "Antique Shop" -> antique shop(s), antique store(s), antiques;
    "Bag / Luggage Store" -> the forms of "bag store" and "luggage store". A bare singular word ("art",
    "boat") is never a form: it would turn ordinary searches into this category."""
    n = re.sub(r"\s+", " ", name.lower().replace("&", "and")).strip()
    parts = [x.strip() for x in n.split("/") if x.strip()]
    suffix = parts[-1].split(" ")[-1] if len(parts) > 1 and parts[-1].split(" ")[-1] in ("shop", "store", "office", "center") else None
    names = [x if (suffix is None or x.endswith(suffix)) else f"{x} {suffix}" for x in parts]
    out = []
    for nm in names:
        words = nm.split(" ")
        if words[-1] in ("shop", "store") and len(words) > 1:
            base = " ".join(words[:-1])
            if base not in MASS:
                out.append(plural_phrase(base) if (" " in base or not base.endswith("s")) else base)
            out.insert(len(out) - (0 if base in MASS else 1), nm)
            out += [f"{base} {w}" for w in STORE_WORDS]
        else:
            out += [nm, plural_phrase(nm)]
    seen, uniq = set(), []
    for f in out:
        if f and f not in seen and (" " in f or f.endswith("s") or f in names):
            seen.add(f)
            uniq.append(f)
    return uniq


def singular_key(term: str) -> str:
    """A term with its last word's plural ending off, to compare "coffee shops" with "coffee shop"."""
    words = term.split(" ")
    w = words[-1]
    for end, rep in (("ies", "y"), ("es", ""), ("s", "")):
        if w.endswith(end) and len(w) > len(end) + 2:
            w = w[: -len(end)] + rep
            break
    return " ".join(words[:-1] + [w])


def covered(cats: list, key: str, value: str) -> bool:
    """True when a hand-written category already matches key=value (by one of its match rules)."""
    for c in cats:
        if c.get("generated"):
            continue
        for rule in c.get("match", []):
            v = rule.get(key)
            if v == value or (isinstance(v, list) and value in v):
                return True
    return False


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pack", required=True, help="a built search pack (counts places per tag)")
    ap.add_argument("--min", type=int, default=15, help="at least this many places in the pack")
    ap.add_argument("--write", action="store_true")
    a = ap.parse_args()

    doc = json.load(open(CATS, encoding="utf-8"))
    cats = [c for c in doc["categories"] if not c.get("generated")]
    presets = json.load(open(os.path.join(REF_DIR, "presets.json"), encoding="utf-8"))
    en = json.load(open(os.path.join(REF_DIR, "en.json"), encoding="utf-8"))
    en_presets = en.get("en", en).get("presets", {}).get("presets", {})
    db = sqlite3.connect(a.pack)
    counts = dict(db.execute("select type, count(*) from entry where type like '%=%' group by type").fetchall())
    # a term is a word, or [word, weight]
    used_terms = {(t if isinstance(t, str) else t[0]).lower() for c in cats for t in c.get("terms", [])}
    added = []
    for pid, p in sorted(presets.items()):
        tags = p.get("tags") or {}
        if len(tags) != 1 or p.get("searchable") is False:
            continue
        (key, value), = tags.items()
        if key not in KEYS or value == "*" or value in SKIP_VALUES or "/" in value:
            continue
        n = counts.get(f"{key}={value}", 0)
        if n < a.min or covered(cats, key, value):
            continue
        name = (en_presets.get(pid) or {}).get("name") or value.replace("_", " ").title()
        cid = f"{key}_{value}"
        if any(c["id"] == cid for c in cats):
            continue
        used_keys = {singular_key(u) for u in used_terms}
        terms = [f for f in forms(name) + EXTRA_TERMS.get(f"{key}_{value}", []) if f not in used_terms and singular_key(f) not in used_keys]
        terms = list(dict.fromkeys(terms))
        if not terms:
            continue
        added.append({"id": cid, "generated": True, "label": name, "group": KEYS[key], "match": [{key: value}],
                      "terms": terms, "id_presets": [pid], "id_terms_exclude": sorted(GENERIC_ID_WORDS)})
        used_terms.update(terms)
    print(f"{len(cats)} hand-written categories; {len(added)} generated (at least {a.min} places each)")
    for c in added[:400]:
        print(f"  {c['id']:34s} {c['label']:28s} {', '.join(c['terms'][:5])}")
    if a.write:
        doc["categories"] = cats + added
        with open(CATS, "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, ensure_ascii=False, indent=1)
            f.write("\n")
        print("written:", os.path.relpath(CATS, REPO))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
