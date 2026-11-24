"""Print the markdown tables used in docs/experiments/04-addresses.md from data/addresses/reports/*.

    python packbuilder/addresses/report_tables.py > data/addresses/reports/tables.md
"""
from __future__ import annotations

import csv
import json

from common import METRICS, REPORTS


def load(name):
    return json.loads((REPORTS / name).read_text(encoding="utf-8"))


def pct(x):
    return "" if x is None else f"{100 * x:.1f}%"


def coverage():
    rows = list(csv.DictReader(open(REPORTS / "coverage_by_county.csv", encoding="utf-8")))
    print("| County | Type | Housing units 2025 | OSM addresses (distinct) | OSM / HU | OSM objects (n/w/r) | "
          "with postcode | NAD (distinct) | NAD found in OSM | Scranton 911 (distinct) | all points / HU |")
    print("|---|---|---:|---:|---:|---|---:|---:|---:|---:|---:|")
    for r in sorted(rows, key=lambda r: r["county"]):
        nad = int(r["nad_distinct_addresses"])
        print(f"| {r['county']} | {r['type']} | {int(r['housing_units_2025']):,} | {int(r['osm_distinct_addresses']):,} | "
              f"{pct(float(r['osm_per_hu']))} | {r['osm_nodes']}/{r['osm_ways']}/{r['osm_relations']} | "
              f"{pct(float(r['osm_postcode_share'])) if r['osm_postcode_share'] else ''} | "
              f"{nad:,} | {pct(float(r['nad_found_in_osm'])) if r['nad_found_in_osm'] else ''} | "
              f"{int(r['extra_distinct_addresses']):,} | {pct(float(r['all_points_per_hu']))} |")
    print()


def accuracy():
    a = load("accuracy.json")
    names = {"tiger_off0_m": "TIGER interpolation", "tiger_off10_m": "TIGER, 10 m side offset",
             "tiger_blind_off10_m": "TIGER, 10 m offset, blind (county only)",
             "nad_m": "NAD points only", "nad_blind_m": "NAD points only, blind (county only)",
             "nad_then_tiger_m": "NAD, else TIGER"}
    for title, block in (("All sampled counties", a["by_type"]), ("Only the NAD counties", a["nad_counties_only"])):
        print(f"**{title}**\n")
        print("| Source | County type | n | not found | median m | p90 m | within 50 m (of all) | within 200 m (of all) |")
        print("|---|---|---:|---:|---:|---:|---:|---:|")
        for col, label in names.items():
            for t, v in block.items():
                s = v[col]
                print(f"| {label} | {t} | {s['n']} | {pct(s['not_found_share'])} | {s.get('median_m', '')} | "
                      f"{s.get('p90_m', '')} | {pct(s.get('within_50m_of_all'))} | {pct(s.get('within_200m_of_all'))} |")
        print()
    print("**Called-out counties (TIGER interpolation / NAD)**\n")
    print("| County | n | TIGER not found | TIGER median m | TIGER p90 m | NAD not found | NAD median m |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for c, v in a["call_out"].items():
        t, n = v["tiger_off0_m"], v["nad_m"]
        print(f"| {c} | {t['n']} | {pct(t['not_found_share'])} | {t.get('median_m', '')} | {t.get('p90_m', '')} | "
              f"{pct(n['not_found_share'])} | {n.get('median_m', '')} |")
    print()


def e2e():
    e = load("e2e.json")
    print("**Target point in the pack** (parser and street matching)\n")
    print("| Variant | n | rules: top-1 within 100 m | rules: top-5 | usaddress: top-1 | usaddress: top-5 | "
          "rules hn/street key/city/ZIP parsed right | usaddress hn/street key/city/ZIP |")
    print("|---|---:|---:|---:|---:|---:|---|---|")
    for k, v in e["by_kind"].items():
        r, u = v["rules"], v["usaddress"]
        print(f"| {k} | {v['n']} | {pct(r['ok'])} | {pct(r['top5_ok'])} | {pct(u['ok'])} | {pct(u['top5_ok'])} | "
              f"{pct(r['hn_ok'])} / {pct(r['key_ok'])} / {pct(r['city_ok'])} / {pct(r['zip_ok'])} | "
              f"{pct(u['hn_ok'])} / {pct(u['key_ok'])} / {pct(u['city_ok'])} / {pct(u['zip_ok'])} |")
    print()
    print("**Target removed from the pack** (rules parser)\n")
    print("| Variant | n | top-1 within 100 m | within 200 m | top-5 within 100 m | median m | p90 m | none | top result accuracy |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---|")
    for k, v in e["by_kind"].items():
        h = v["holdout"]
        print(f"| {k} | {v['n']} | {pct(h['ok'])} | {pct(h['within_200m'])} | {pct(h['top5_ok'])} | "
              f"{h.get('median_m', '')} | {h.get('p90_m', '')} | {pct(h['none'])} | {h['top_accuracy']} |")
    print()
    print("latency ms/query:", json.dumps(e["latency_ms_per_query"]))
    print("by type:", e["by_type"])
    print("rules top accuracy:", e["rules_top_accuracy"], "match:", e["rules_top_match"],
          "holdout match:", e.get("holdout_top_match"))
    print()


def metrics():
    print("| Step | wall s | CPU s | peak working set MB | notes |")
    print("|---|---:|---:|---:|---|")
    for p in sorted(METRICS.glob("*.json")):
        m = json.loads(p.read_text(encoding="utf-8"))
        extra = {k: v for k, v in m.items() if k not in ("step", "finished_utc", "wall_s", "cpu_s", "peak_rss_mb",
                                                          "peak_rss_how", "note", "stats", "table_mb", "other_mb")}
        print(f"| {m['step']} | {m['wall_s']} | {m['cpu_s']} | {m['peak_rss_mb']} | {json.dumps(extra)[:300]} |")
    print()


if __name__ == "__main__":
    for f in (coverage, accuracy, e2e, metrics):
        try:
            f()
        except FileNotFoundError as ex:
            print(f"({f.__name__}: {ex})\n")
