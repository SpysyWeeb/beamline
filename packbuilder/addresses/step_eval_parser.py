"""Step: end-to-end test of parser + lookup on typed variants of real addresses; compare with usaddress.

    python packbuilder/addresses/step_eval_parser.py [--force] [--seed 7]

Draws real addresses from pa-addresses.sqlite (equal thirds from urban,
suburban and rural counties; only rows whose city is a source or postal city
and that have a ZIP) and writes one typed variant of each. The headline set is
300 variants, 75 of every kind:
  abbrev   - street words abbreviated or expanded, odd casing ('North Main Street' <-> 'n main st')
  no_zip   - '123 Main St Erie PA'
  no_city  - '123 Main St, PA 16501'
  typo     - one edit (swap, drop, double or replace a letter) in the longest street-NAME word
Reported separately: 75 'typo_type' variants (one edit in the street-TYPE word, which is spelt out
first when it is abbreviated: 'St' -> 'Srteet') and 60 clean variants as a baseline.

Success = the top result lies within 100 m of the true point. Two runs:
  in_pack  - against the pack as built: the target point itself is in the file, so this measures
             parsing and street matching (can the lookup find a point that exists?). Each variant is
             also parsed by usaddress (MIT, CRF model) feeding the same lookup.
  holdout  - against a copy of the pack from which every target address was removed (all points with
             the same house number on a street with the same street_core in the same county). This is
             what a user gets for an address the pack has no point for: interpolation or a neighbour.
Outputs: data/addresses/reports/e2e.json, e2e_cases.csv; work/e2e_holdout.sqlite (deleted at the end).
"""
from __future__ import annotations

import collections
import csv
import json
import random
import re
import shutil
import sqlite3
import statistics
import time

from common import OUT_DB, REPORTS, WORK, Step
from addrlib.geo import haversine_m
from addrlib.lookup import AddressIndex
from addrlib.normalize import clean, place_key, street_core, street_key, tables
from addrlib.parser import parse

KINDS = ["abbrev", "no_zip", "no_city", "typo"]
EXTRA_KINDS = ["typo_type"]
KEYBOARD = "qwertyuiopasdfghjklzxcvbnm"


def full_words():
    t = tables()
    types = {c: v[0] for c, v in t.raw["street_types"].items() if not c.startswith("_")}
    dirs = {c: v[1] for c, v in t.raw["directionals"].items() if not c.startswith("_")}
    return types, dirs


def abbreviate(street, rng):
    t = tables()
    types, dirs = full_words()
    out, changed = [], False
    toks = street.split()
    for i, w in enumerate(toks):
        lw = clean(w)
        if lw in t.dir and (i == 0 or i == len(toks) - 1):
            canon = t.dir[lw]
            new = canon.upper() if lw != canon else dirs[canon].title()
        elif lw in t.types and i == len(toks) - 1:
            canon = t.types[lw]
            new = canon.title() if lw != canon else types[canon].title()
            if rng.random() < 0.3:
                new += "."
        else:
            new = w
        changed |= new != w
        out.append(new)
    s = " ".join(out)
    if not changed or rng.random() < 0.5:
        s = s.lower()
    return s


def edit_word(w, rng):
    p = rng.randrange(1, len(w) - 1)
    op = rng.choice(["swap", "drop", "double", "replace"])
    if op == "swap":
        w2 = w[:p] + w[p + 1] + w[p] + w[p + 2:]
    elif op == "drop":
        w2 = w[:p] + w[p + 1:]
    elif op == "double":
        w2 = w[:p] + w[p] + w[p:]
    else:
        w2 = w[:p] + rng.choice([c for c in KEYBOARD if c != w[p].lower()]) + w[p + 1:]
    return None if w2.lower() == w.lower() else w2


def typo(street, rng):
    """One edit in the longest street-name word (not a type word, not a directional)."""
    t = tables()
    toks = street.split()
    idx = [i for i, w in enumerate(toks) if len(w) >= 4 and w.isalpha() and clean(w) not in t.types
           and clean(w) not in t.dir]
    if not idx:
        return None
    i = max(idx, key=lambda j: len(toks[j]))
    w2 = edit_word(toks[i], rng)
    if w2 is None:
        return None
    toks[i] = w2
    return " ".join(toks)


def typo_type(street, rng):
    """One edit in the street-type word; an abbreviated type is spelt out first ('St' -> 'Street')."""
    t = tables()
    types, _ = full_words()
    toks = street.split()
    j = len(toks) - 1
    while j > 0 and clean(toks[j]) in t.dir:
        j -= 1
    if j < 1 or clean(toks[j]) not in t.types:
        return None
    w = toks[j].rstrip(".")
    if len(w) < 4:
        w = types[t.types[clean(w)]].title()
    if len(w) < 4:
        return None
    w2 = edit_word(w, rng)
    if w2 is None:
        return None
    toks[j] = w2
    return " ".join(toks)


def make_text(kind, hn, street, city, zipc, rng):
    if kind == "clean":
        return f"{hn} {street}, {city}, PA {zipc}"
    if kind == "abbrev":
        return f"{hn} {abbreviate(street, rng)}, {city} {rng.choice(['PA', 'pa', 'Pa'])} {zipc}"
    if kind == "no_zip":
        return rng.choice([f"{hn} {street} {city} PA", f"{hn} {street}, {city}", f"{hn} {street.lower()} {city.lower()} pa"])
    if kind == "no_city":
        return rng.choice([f"{hn} {street}, PA {zipc}", f"{hn} {street} {zipc}"])
    if kind == "typo":
        s = typo(street, rng)
        return None if s is None else f"{hn} {s}, {city}, PA {zipc}"
    if kind == "typo_type":
        s = typo_type(street, rng)
        return None if s is None else rng.choice([f"{hn} {s}, {city}, PA {zipc}", f"{hn} {s} {city} pa"])


def usaddress_parse(text):
    import usaddress
    try:
        tags, _ = usaddress.tag(text)
    except usaddress.RepeatedLabelError as e:
        tags = collections.OrderedDict()
        for tok, lab in e.parsed_string:
            tags.setdefault(lab, tok)
    g = lambda *ks: " ".join(tags[k] for k in ks if k in tags).strip() or None
    street = g("StreetNamePreModifier", "StreetNamePreDirectional", "StreetNamePreType", "StreetName",
               "StreetNamePostType", "StreetNamePostDirectional")
    city = g("PlaceName")
    z = g("ZipCode")
    return {"house_number": (g("AddressNumber", "AddressNumberSuffix") or "").replace(" ", "").lower() or None,
            "street": street, "street_key": street_key(street) if street else None,
            "unit": g("OccupancyType", "OccupancyIdentifier"), "city": city,
            "city_key": place_key(city) if city else None, "state": g("StateName"),
            "zip": re.sub(r"\D", "", z)[:5] if z else None}


def dist_stats(ds, n):
    f = sorted(d for d in ds if d is not None)
    out = {"n": n, "none": round((n - len(f)) / n, 3) if n else None,
           "within_100m": round(sum(1 for d in f if d <= 100) / n, 3) if n else None,
           "within_200m": round(sum(1 for d in f if d <= 200) / n, 3) if n else None}
    if f:
        out.update({"median_m": round(statistics.median(f), 1), "p90_m": round(f[int(0.9 * (len(f) - 1))], 1)})
    return out


def latency(ms):
    s = sorted(ms)
    return {"mean": round(statistics.mean(s), 2), "median": round(statistics.median(s), 2),
            "p90": round(s[int(0.9 * (len(s) - 1))], 2), "max": round(s[-1], 2)} if s else {}


def make_holdout(cases, path):
    """Copy of the pack without the target addresses (same hn, same street_core, same county)."""
    if path.exists():
        path.unlink()
    shutil.copyfile(OUT_DB, path)
    con = sqlite3.connect(path)
    n = 0
    for c in cases:
        n += con.execute("""DELETE FROM addresses WHERE id IN (
                              SELECT a.id FROM addresses a JOIN streets s ON s.id = a.street_id
                              JOIN localities l ON l.id = s.locality_id
                              WHERE a.hn = ? AND s.street_core = ? AND l.county_fips = ?)""",
                         (c["true_hn"], street_core(c["true_key"]), c["county_fips"])).rowcount
    con.commit()
    con.close()
    return n


def main():
    with Step("eval_parser", extra_args=[(("--seed",), {"type": int, "default": 7})]) as st:
        rng = random.Random(st.args.seed)
        types = json.loads((REPORTS / "county_types.json").read_text(encoding="utf-8"))
        con = sqlite3.connect(OUT_DB)
        pool = collections.defaultdict(list)
        for row in con.execute("""SELECT a.id, a.hn, s.street, s.street_key, l.city, l.zip, l.county_fips,
                                         a.lat_e6, a.lon_e6, a.source
                                  FROM addresses a JOIN streets s ON s.id = a.street_id
                                  JOIN localities l ON l.id = s.locality_id
                                  WHERE l.city_src IN ('source', 'zip') AND l.zip IS NOT NULL AND a.unit IS NULL"""):
            if re.match(r"^\d+[a-z]?$", row[1]):
                pool[types[row[6]]["type"]].append(row)
        con.close()
        cases = []

        def case(kind, t, text, r):
            return {"kind": kind, "type": t, "text": text, "true_hn": r[1], "true_key": r[3],
                    "true_city_key": place_key(r[4]), "true_zip": r[5], "county_fips": r[6], "lat": r[7] / 1e6,
                    "lon": r[8] / 1e6, "address_id": r[0], "source": r[9]}

        rest = {}
        for t in ("urban", "suburban", "rural"):
            picks = rng.sample(pool[t], 400)
            j = 0
            for kind in KINDS * 25 + ["clean"] * 20:
                while True:
                    r = picks[j]
                    j += 1
                    text = make_text(kind, r[1], r[2], r[4], r[5], rng)
                    if text:
                        break
                cases.append(case(kind, t, text, r))
            rest[t] = picks[j:]
        rng2 = random.Random(st.args.seed + 1)  # extra kinds do not change the headline draws
        for t in ("urban", "suburban", "rural"):
            j = 0
            for kind in EXTRA_KINDS * 25:
                while True:
                    r = rest[t][j]
                    j += 1
                    text = make_text(kind, r[1], r[2], r[4], r[5], rng2)
                    if text:
                        break
                cases.append(case(kind, t, text, r))

        timing = collections.defaultdict(list)

        def run(idx, c, name, fn, prefix):
            t0 = time.perf_counter()
            p = fn(c["text"])
            t1 = time.perf_counter()
            res = idx.resolve(p)
            t2 = time.perf_counter()
            timing[f"{prefix}_parse"].append((t1 - t0) * 1000)
            timing[f"{prefix}_lookup"].append((t2 - t1) * 1000)
            top = res[0] if res else None
            d = haversine_m(c["lon"], c["lat"], top["lon"], top["lat"]) if top else None
            c[prefix + "_dist_m"] = None if d is None else round(d, 1)
            c[prefix + "_ok"] = d is not None and d <= 100
            c[prefix + "_top5_ok"] = any(haversine_m(c["lon"], c["lat"], x["lon"], x["lat"]) <= 100 for x in res)
            c[prefix + "_accuracy"] = top["accuracy"] if top else None
            c[prefix + "_match"] = top["match"] if top else None
            return p

        idx = AddressIndex(OUT_DB)
        for c in cases:
            for name, fn in (("rules", lambda s: parse(s, idx.known_places)), ("usaddress", usaddress_parse)):
                p = run(idx, c, name, fn, name)
                c[name + "_hn_ok"] = p.get("house_number") == c["true_hn"]
                c[name + "_key_ok"] = p.get("street_key") == c["true_key"]
                c[name + "_city_ok"] = (p.get("city_key") == c["true_city_key"]) if c["kind"] != "no_city" else (p.get("city_key") is None)
                # a ZIP is expected only when the variant text contains one (after the house number)
                want_zip = c["true_zip"] if re.search(r"\b%s\b" % c["true_zip"], c["text"].split(" ", 1)[1]) else None
                c[name + "_zip_ok"] = p.get("zip") == want_zip
        idx.con.close()

        hold_db = WORK / "e2e_holdout.sqlite"
        n_deleted = make_holdout(cases, hold_db)
        hidx = AddressIndex(hold_db)
        for c in cases:
            run(hidx, c, "rules", lambda s: parse(s, hidx.known_places), "holdout")
        hidx.con.close()
        hold_db.unlink()

        with open(REPORTS / "e2e_cases.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(cases[0]))
            w.writeheader()
            w.writerows(cases)
        out = {"n_test": sum(1 for c in cases if c["kind"] in KINDS),
               "n_extra": {k: sum(1 for c in cases if c["kind"] == k) for k in EXTRA_KINDS + ["clean"]},
               "success_definition": "top result within 100 m of the true address point",
               "in_pack": "target point is in the pack (parser + street matching test)",
               "holdout": f"target removed from a copy of the pack ({n_deleted} point rows deleted); "
                          "rules parser only", "by_kind": {}}
        groups = KINDS + EXTRA_KINDS + ["clean", "all_test"]
        for kind in groups:
            sel = [c for c in cases if (c["kind"] == kind) or (kind == "all_test" and c["kind"] in KINDS)]
            out["by_kind"][kind] = {"n": len(sel)}
            for name in ("rules", "usaddress"):
                out["by_kind"][kind][name] = {
                    m: round(sum(1 for c in sel if c[f"{name}_{m}"]) / len(sel), 3)
                    for m in ("ok", "top5_ok", "hn_ok", "key_ok", "city_ok", "zip_ok")}
            out["by_kind"][kind]["holdout"] = dict(
                dist_stats([c["holdout_dist_m"] for c in sel], len(sel)),
                ok=round(sum(1 for c in sel if c["holdout_ok"]) / len(sel), 3),
                top5_ok=round(sum(1 for c in sel if c["holdout_top5_ok"]) / len(sel), 3),
                top_accuracy=dict(collections.Counter(c["holdout_accuracy"] for c in sel)))
        for t in ("urban", "suburban", "rural"):
            sel = [c for c in cases if c["type"] == t and c["kind"] in KINDS]
            out.setdefault("by_type", {})[t] = {name: round(sum(1 for c in sel if c[f"{name}_ok"]) / len(sel), 3)
                                               for name in ("rules", "usaddress", "holdout")}
        out["latency_ms_per_query"] = {k: latency(v) for k, v in timing.items()}
        out["latency_note"] = "CPython on the build PC while other heavy builds were running"
        main_sel = [c for c in cases if c["kind"] in KINDS]
        out["rules_top_accuracy"] = dict(collections.Counter(c["rules_accuracy"] for c in main_sel))
        out["rules_top_match"] = dict(collections.Counter(c["rules_match"] for c in main_sel))
        out["holdout_top_match"] = dict(collections.Counter(c["holdout_match"] for c in main_sel))
        (REPORTS / "e2e.json").write_text(json.dumps(out, indent=2), encoding="utf-8")
        st.info.update({"rules_success": out["by_kind"]["all_test"]["rules"]["ok"],
                        "usaddress_success": out["by_kind"]["all_test"]["usaddress"]["ok"],
                        "holdout_success": out["by_kind"]["all_test"]["holdout"]["ok"],
                        "typo_type_success": out["by_kind"]["typo_type"]["rules"]["ok"]})


if __name__ == "__main__":
    main()
