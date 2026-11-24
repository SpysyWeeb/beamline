"""Towns for the US overview pack from Census data: Gazetteer place points + population estimates.

Usage (from the repo root; build_overview.py runs it):
    .venv/map/Scripts/python packbuilder/map/overview/census_places.py [--min-population 10000]

Inputs (public domain, pinned by SHA-256 in fetch_sources.py):
  data/map/work/data/census/2026_Gaz_place_national.zip   place names and internal points (INTPTLAT/INTPTLONG)
  data/map/work/data/census/sub-est2025.csv               Vintage 2025 population estimates (SUMLEV 162:
                                                          incorporated places)
Natural Earth already gives the large cities with curated zoom levels and
capitals; this adds the incorporated places it lacks (joined on state + name),
so zoom 6-7 shows towns the way the state packs do from zoom 8.
Output: data/map/work/overview/census_places.geojson (name, population,
population_rank, min_zoom, state).
"""
import argparse
import csv
import io
import json
import re
import sqlite3
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
CENSUS = REPO / "data" / "map" / "work" / "data" / "census"
WORK = REPO / "data" / "map" / "work" / "overview"
GAZ = CENSUS / "2026_Gaz_place_national.zip"
POP = CENSUS / "sub-est2025.csv"
SUFFIX = re.compile(r"\s+(city and borough|city|town|village|borough|municipality|CDP|plantation|"
                    r"metropolitan government|metro government|unified government|consolidated government|"
                    r"urban county|corporation)(\s*\(balance\))?$")


def clean(name: str) -> str:
    name = re.sub(r"\s*\(balance\)$", "", name.strip())
    name = SUFFIX.sub("", name)
    return name.split("-")[0].strip() if name.count("-") and " government" in name else name


def rank(pop: int) -> int:
    """Population rank on Natural Earth's RANK_MAX scale (14 = over 10 million ... 4 = 1,000 to 5,000)."""
    for r, low in ((14, 10_000_000), (13, 5_000_000), (12, 1_000_000), (11, 500_000), (10, 200_000),
                   (9, 100_000), (8, 50_000), (7, 20_000), (6, 10_000), (5, 5_000), (4, 1_000)):
        if pop >= low:
            return r
    return 3


def min_zoom(pop: int) -> int:
    return 5 if pop >= 200_000 else 6 if pop >= 50_000 else 7


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--min-population", type=int, default=5_000)
    a = ap.parse_args()
    pop = {}
    with open(POP, encoding="latin-1", newline="") as f:
        for row in csv.DictReader(f):
            if row["SUMLEV"] == "162":
                pop[row["STATE"] + row["PLACE"]] = (int(row["POPESTIMATE2025"]), row["STNAME"])
    z = zipfile.ZipFile(GAZ)
    text = z.read(z.namelist()[0]).decode("utf-8", "replace")
    rows = list(csv.DictReader(io.StringIO(text), delimiter="|"))
    # Natural Earth US cities, to leave out the ones it already has
    con = sqlite3.connect(WORK / "ne_overview.gpkg")
    ne = {(r[0], r[1].lower()) for r in con.execute(
        "SELECT ADM1NAME, NAME FROM ne_10m_populated_places WHERE ADM0_A3 = 'USA'")}
    feats, skipped_ne = [], 0
    for r in rows:
        geoid = r["GEOID"].strip()
        if geoid not in pop:
            continue
        p, state = pop[geoid]
        if p < a.min_population:
            continue
        name = clean(r["NAME"])
        if (state, name.lower()) in ne:
            skipped_ne += 1
            continue
        lon, lat = float(r["INTPTLONG"].strip()), float(r["INTPTLAT"].strip())
        feats.append({"type": "Feature", "geometry": {"type": "Point", "coordinates": [lon, lat]},
                      "properties": {"name": name, "population": p, "population_rank": rank(p),
                                     "min_zoom": min_zoom(p), "state": r["USPS"], "geoid": geoid}})
    out = WORK / "census_places.geojson"
    out.write_text(json.dumps({"type": "FeatureCollection", "features": feats}), encoding="utf-8")
    print(f"{len(feats)} places with population >= {a.min_population} (and {skipped_ne} already in Natural Earth) "
          f"-> {out.relative_to(REPO).as_posix()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
