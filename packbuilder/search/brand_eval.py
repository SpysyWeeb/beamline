"""Brand evaluation for a built index.

    python packbuilder/search/brand_eval.py --state pa [--nsi data/search/ref/nsi/nsi.min.json]

1. Alias table from the data: for each chain in chain_reference.json, the
   spellings seen in OSM (brand / name tags) and the alias keys stored.
2. Name Suggestion Index (evaluated, NOT shipped): which extra alias keys NSI
   would add for the brands present in the state (needs the NSI file, fetched
   by this script into data/search/ref/nsi/ at a pinned version + SHA-256).
3. Chain coverage: OSM POIs per chain vs the public reference count.
   'stores' merges POIs of one brand within 250 m (fuel + shop of one store,
   node + building) so it is comparable to a store count.

Output: data/search/<state>-brand-eval.json (+ a printed summary).
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import sqlite3
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
import normalize  # noqa: E402
from query import haversine_km  # noqa: E402

NSI = {"package": "name-suggestion-index", "version": "8.0.20261101", "file": "dist/json/nsi.min.json",
       "sha256": "618c91e449b6871a65f2f4fcade65c64322505a0b77e14869c0d0a921c95ae3c", "licence": "BSD-3-Clause"}


def fetch_nsi() -> str:
    d = os.path.join(REPO, "data", "search", "ref", "nsi")
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, "nsi.min.json")
    if not os.path.exists(p):
        url = f"https://cdn.jsdelivr.net/npm/{NSI['package']}@{NSI['version']}/{NSI['file']}"
        print("downloading", url)
        with urllib.request.urlopen(url, timeout=300) as r, open(p + ".part", "wb") as f:
            f.write(r.read())
        os.replace(p + ".part", p)
    if hashlib.sha256(open(p, "rb").read()).hexdigest() != NSI["sha256"]:
        sys.exit("NSI file SHA-256 mismatch")
    return p


def clusters(points, km=0.25) -> int:
    """Number of groups of points closer than km (single linkage, small n)."""
    n = len(points)
    parent = list(range(n))

    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]
            a = parent[a]
        return a
    order = sorted(range(n), key=lambda i: points[i][0])
    for ai in range(n):
        a = order[ai]
        for bi in range(ai + 1, n):
            b = order[bi]
            if points[b][0] - points[a][0] > km / 111.0:
                break
            if haversine_km(points[a][0], points[a][1], points[b][0], points[b][1]) <= km:
                parent[find(a)] = find(b)
    return len({find(i) for i in range(n)})


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--state", default="pa")
    ap.add_argument("--no-nsi", action="store_true")
    args = ap.parse_args()
    db = sqlite3.connect(f"file:{os.path.join(REPO, 'data', 'search', args.state + '-search.sqlite')}?mode=ro", uri=True)
    ref = json.load(open(os.path.join(HERE, "chain_reference.json"), encoding="utf-8"))["chains"]
    out = {"chains": [], "nsi": None}

    # ---- 1 + 3: aliases and coverage per chain
    for c in ref:
        wds = c["wikidata"]
        q = ",".join("?" * len(wds))
        raw = db.execute(f"SELECT e.lat, e.lon, e.name, p.brand, p.cats FROM poi p JOIN entry e ON e.id = p.entry_id "
                         f"WHERE p.brand_wikidata IN ({q})", wds).fetchall()
        bids = [r[0] for r in db.execute(f"SELECT id FROM brand WHERE wikidata IN ({q})", wds)]
        joined = db.execute(f"SELECT e.lat, e.lon FROM entry e WHERE e.brand_id IN ({','.join('?' * len(bids))})",
                            bids).fetchall() if bids else []
        spellings = collections.Counter()
        for _, _, name, brand, _ in raw:
            if brand:
                spellings[f"brand={brand}"] += 1
            if name:
                spellings[f"name={name}"] += 1
        aliases = sorted({a for (a,) in db.execute(
            f"SELECT alias FROM brand_alias WHERE brand_id IN ({','.join('?' * len(bids))})", bids)}) if bids else []
        stores = clusters([(r[0], r[1]) for r in joined]) if joined else 0
        row = {"chain": c["chain"], "wikidata": wds, "osm_pois_with_brand_wikidata": len(raw),
               "osm_pois_incl_brand_tag_only": len(joined), "osm_store_sites_250m": stores,
               "reference": c["reference"], "reference_kind": c["kind"], "reference_as_of": c["as_of"],
               "reference_source": c["source"], "note": c.get("note"),
               "coverage": (round(stores / c["reference"], 2) if c["reference"] else None),
               "spellings_seen": spellings.most_common(12), "alias_keys": aliases}
        out["chains"].append(row)

    # ---- 2: NSI
    if not args.no_nsi:
        nsi = json.load(open(fetch_nsi(), encoding="utf-8"))
        by_wd = collections.defaultdict(list)
        for tree, v in nsi["nsi"].items():
            for item in v.get("items", []):
                wd = item.get("tags", {}).get("brand:wikidata")
                if wd:
                    by_wd[wd].append(item)
        brands = db.execute("SELECT id, wikidata, name, poi_count FROM brand WHERE wikidata IS NOT NULL").fetchall()
        our = collections.defaultdict(set)
        for alias, bid in db.execute("SELECT alias, brand_id FROM brand_alias"):
            our[bid].add(alias)
        total_new, brands_in_nsi, examples = 0, 0, {}
        per_chain = {}
        chain_wds = {wd for c in ref for wd in c["wikidata"]}
        for bid, wd, name, cnt in brands:
            items = by_wd.get(wd)
            if not items:
                continue
            brands_in_nsi += 1
            keys = set()
            for it in items:
                t = it.get("tags", {})
                for v in [it.get("displayName"), t.get("name"), t.get("brand"), t.get("name:en"), t.get("brand:en"),
                          t.get("short_name"), t.get("alt_name")] + list(it.get("matchNames", [])):
                    if v:
                        k = normalize.key(v)
                        if k:
                            keys.add(k)
                            keys.add(k.replace(" ", ""))
            new = sorted(keys - our[bid])
            total_new += len(new)
            if wd in chain_wds:
                per_chain[wd] = new
            elif new and len(examples) < 25 and cnt >= 10:
                examples[name] = new[:6]
        out["nsi"] = {"version": NSI["version"], "licence_stated": NSI["licence"],
                      "brands_with_wikidata_in_state": len(brands), "of_which_in_nsi": brands_in_nsi,
                      "alias_keys_nsi_would_add": total_new, "per_chain": per_chain,
                      "examples_other_brands": examples}
        for row in out["chains"]:
            row["nsi_would_add"] = sorted({k for wd in row["wikidata"] for k in per_chain.get(wd, [])})
    path = os.path.join(REPO, "data", "search", f"{args.state}-brand-eval.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
    print(f"{'chain':16s} {'wd POIs':>8s} {'+brand':>7s} {'sites':>6s} {'ref':>5s} {'cov':>5s}  NSI adds")
    for r in out["chains"]:
        print(f"{r['chain']:16s} {r['osm_pois_with_brand_wikidata']:8d} {r['osm_pois_incl_brand_tag_only']:7d} "
              f"{r['osm_store_sites_250m']:6d} {str(r['reference']):>5s} {str(r['coverage']):>5s}  "
              f"{', '.join(r.get('nsi_would_add', [])[:6])}")
    if out["nsi"]:
        n = out["nsi"]
        print(f"NSI {n['version']}: {n['of_which_in_nsi']} of {n['brands_with_wikidata_in_state']} brands found; "
              f"would add {n['alias_keys_nsi_would_add']} alias keys")
    print("wrote", os.path.relpath(path, REPO))


if __name__ == "__main__":
    main()
