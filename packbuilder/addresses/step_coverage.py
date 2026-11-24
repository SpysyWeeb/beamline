"""Step: measure OSM address coverage per county against Census housing units (and NAD where present).

    python packbuilder/addresses/step_coverage.py [--force]

Housing units: Census Bureau PEP Vintage 2025, table CO-EST2025-HU-42
(July 1, 2025 estimates, released May 2026). County type by housing-unit
density (units per km2 of land, land area from the 2025 cartographic boundary
file): urban >= 300, suburban 40-300, rural < 40 (thresholds in
coverage_config below).
Outputs: data/addresses/reports/coverage_by_county.csv, coverage_summary.json,
county_types.json
"""
from __future__ import annotations

import collections
import csv
import json
import sqlite3

import numpy as np
import openpyxl

from common import DL, REPORTS, WORK, Step
from addrlib.normalize import house_number, split_housenumbers, street_key
from geoassign import assign, load_counties

COVERAGE_CONFIG = {"urban_min_hu_per_km2": 300, "suburban_min_hu_per_km2": 40}
CALL_OUT = ["Erie", "Allegheny", "Philadelphia", "Centre"]


def housing_units():
    wb = openpyxl.load_workbook(DL / "CO-EST2025-HU-42.xlsx", read_only=True)
    out = {}
    for row in wb.active.iter_rows(values_only=True):
        name = row[0]
        if isinstance(name, str) and name.startswith(".") and " County," in name:
            out[name[1:].split(" County,")[0]] = int(row[7])  # July 1, 2025
    return out


def county_type(density):
    if density >= COVERAGE_CONFIG["urban_min_hu_per_km2"]:
        return "urban"
    if density >= COVERAGE_CONFIG["suburban_min_hu_per_km2"]:
        return "suburban"
    return "rural"


def main():
    with Step("coverage") as st:
        hu = housing_units()
        counties = load_counties()
        info = {}
        for fips, name, aland, _ in counties:
            d = hu[name] / (aland / 1e6)
            info[fips] = {"fips": fips, "county": name, "housing_units_2025": hu[name],
                          "land_km2": round(aland / 1e6, 1), "hu_per_km2": round(d, 1), "type": county_type(d)}
        (REPORTS / "county_types.json").write_text(json.dumps(
            {f: {"county": v["county"], "type": v["type"]} for f, v in info.items()}, indent=1), encoding="utf-8")

        con = sqlite3.connect(WORK / "osm_pa.sqlite")
        by = collections.defaultdict(collections.Counter)
        distinct = collections.defaultdict(set)
        keyc = {}
        for typ, hn_raw, street, place, pc, cf in con.execute(
                "SELECT osm_type, hn_raw, street, place, postcode, county_fips FROM osm WHERE county_fips IS NOT NULL"):
            c = by[cf]
            c["osm_" + {"n": "nodes", "w": "ways", "r": "relations"}[typ]] += 1
            c["osm_objects"] += 1
            if street:
                c["osm_with_street"] += 1
            elif place:
                c["osm_with_place_only"] += 1
            if pc:
                c["osm_with_postcode"] += 1
            s = street or place
            if s:
                k = keyc.get(s) or keyc.setdefault(s, street_key(s))
                for h in split_housenumbers(hn_raw):
                    hn = house_number(h)[0]
                    if hn:
                        distinct[cf].add((hn, k))
        # interpolation ways by county of their middle vertex
        iw = con.execute("SELECT coords FROM osm_interp").fetchall()
        con.close()
        if iw:
            mids = [c[0].split(";")[len(c[0].split(";")) // 2].split(",") for c in iw]
            ci = assign(counties, np.array([float(m[0]) for m in mids]), np.array([float(m[1]) for m in mids]))
            for i in ci:
                if i >= 0:
                    by[counties[i][0]]["osm_interpolation_ways"] += 1

        nad_db = WORK / "nad_pa.sqlite"
        nad_by = collections.defaultdict(set)
        if nad_db.exists():
            nc = sqlite3.connect(nad_db)
            rows = nc.execute("SELECT AddNo_Full, Add_Number, StNam_Full, Longitude, Latitude FROM nad").fetchall()
            nc.close()
            lon = np.array([float(r[3] or "nan") for r in rows])
            lat = np.array([float(r[4] or "nan") for r in rows])
            ok = np.isfinite(lon)
            lon[~ok] = 0
            lat[~ok] = 0
            ci = assign(counties, lon, lat)
            for r, i in zip(rows, ci):
                if i < 0 or not r[2]:
                    continue
                hn = house_number(r[0] or str(r[1] or "").split(".")[0])[0]
                if hn:
                    s = r[2]
                    k = keyc.get(s) or keyc.setdefault(s, street_key(s))
                    nad_by[counties[i][0]].add((hn, k))

        extra_by = collections.defaultdict(set)
        extra_db = WORK / "extra_pa.sqlite"
        if extra_db.exists():
            ec = sqlite3.connect(extra_db)
            rows = ec.execute("SELECT hn, street, lon, lat FROM extra").fetchall()
            ec.close()
            lon = np.array([r[2] if r[2] is not None else np.nan for r in rows], dtype=float)
            lat = np.array([r[3] if r[3] is not None else np.nan for r in rows], dtype=float)
            ok = np.isfinite(lon)
            lon[~ok] = 0
            lat[~ok] = 0
            ci = assign(counties, lon, lat)
            for r, i in zip(rows, ci):
                if i < 0 or not r[1]:
                    continue
                hn = house_number(r[0] or "")[0]
                if hn:
                    k = keyc.get(r[1]) or keyc.setdefault(r[1], street_key(r[1]))
                    extra_by[counties[i][0]].add((hn, k))

        rows = []
        tot = collections.Counter()
        for fips in sorted(info):
            v = dict(info[fips])
            c = by[fips]
            v.update({k: c[k] for k in ("osm_objects", "osm_nodes", "osm_ways", "osm_relations",
                                         "osm_with_street", "osm_with_place_only", "osm_with_postcode",
                                         "osm_interpolation_ways")})
            v["osm_distinct_addresses"] = len(distinct[fips])
            v["osm_per_hu"] = round(v["osm_distinct_addresses"] / v["housing_units_2025"], 3)
            v["osm_postcode_share"] = round(c["osm_with_postcode"] / c["osm_objects"], 3) if c["osm_objects"] else None
            n = nad_by.get(fips)
            v["nad_distinct_addresses"] = len(n) if n else 0
            v["nad_per_hu"] = round(len(n) / v["housing_units_2025"], 3) if n else None
            v["nad_found_in_osm"] = round(len(n & distinct[fips]) / len(n), 3) if n and len(n) > 1000 else None
            v["osm_plus_nad_distinct"] = len(distinct[fips] | (n or set()))
            v["osm_plus_nad_per_hu"] = round(v["osm_plus_nad_distinct"] / v["housing_units_2025"], 3)
            ex = extra_by.get(fips, set())
            v["extra_distinct_addresses"] = len(ex)
            v["extra_found_in_osm"] = round(len(ex & distinct[fips]) / len(ex), 3) if len(ex) > 1000 else None
            v["all_points_distinct"] = len(distinct[fips] | (n or set()) | ex)
            v["all_points_per_hu"] = round(v["all_points_distinct"] / v["housing_units_2025"], 3)
            rows.append(v)
            for k in ("housing_units_2025", "osm_objects", "osm_nodes", "osm_ways", "osm_relations",
                      "osm_with_street", "osm_with_postcode", "osm_interpolation_ways",
                      "osm_distinct_addresses", "nad_distinct_addresses", "osm_plus_nad_distinct",
                      "extra_distinct_addresses", "all_points_distinct"):
                tot[k] += v[k]
            tot["counties_" + v["type"]] += 1
        with open(REPORTS / "coverage_by_county.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0]))
            w.writeheader()
            w.writerows(rows)
        bands = collections.Counter()
        for v in rows:
            r = v["osm_per_hu"]
            bands["<10%" if r < 0.1 else "10-25%" if r < 0.25 else "25-50%" if r < 0.5 else
                  "50-75%" if r < 0.75 else ">=75%"] += 1
        by_type = {}
        for t in ("urban", "suburban", "rural"):
            sel = [v for v in rows if v["type"] == t]
            hu_t = sum(v["housing_units_2025"] for v in sel)
            by_type[t] = {"counties": len(sel), "housing_units": hu_t,
                          "osm_distinct": sum(v["osm_distinct_addresses"] for v in sel),
                          "osm_per_hu": round(sum(v["osm_distinct_addresses"] for v in sel) / hu_t, 3),
                          "osm_plus_nad_per_hu": round(sum(v["osm_plus_nad_distinct"] for v in sel) / hu_t, 3),
                          "all_points_per_hu": round(sum(v["all_points_distinct"] for v in sel) / hu_t, 3)}
        summary = {
            "housing_units_table": "Census PEP Vintage 2025 CO-EST2025-HU-42, July 1 2025",
            "statewide": dict(tot),
            "statewide_osm_per_hu": round(tot["osm_distinct_addresses"] / tot["housing_units_2025"], 3),
            "statewide_osm_plus_nad_per_hu": round(tot["osm_plus_nad_distinct"] / tot["housing_units_2025"], 3),
            "statewide_all_points_per_hu": round(tot["all_points_distinct"] / tot["housing_units_2025"], 3),
            "county_bands_osm_per_hu": dict(bands),
            "by_type": by_type,
            "call_out": {v["county"]: v for v in rows if v["county"] in CALL_OUT},
            "county_type_thresholds": COVERAGE_CONFIG,
        }
        (REPORTS / "coverage_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        st.info.update({"statewide_osm_per_hu": summary["statewide_osm_per_hu"],
                        "statewide_osm_plus_nad_per_hu": summary["statewide_osm_plus_nad_per_hu"],
                        "nad_used": nad_db.exists()})


if __name__ == "__main__":
    main()
