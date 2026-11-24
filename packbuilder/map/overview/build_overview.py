"""Build the whole-US overview pack (zoom 0-7) from public-domain inputs only.

Usage (from the repo root):
    .venv/map/Scripts/python packbuilder/map/overview/build_overview.py [--force]

Inputs (no OpenStreetMap data, nothing that needs a credit line):
  - Natural Earth 5.1.2 GeoPackage (public domain), the same file the state
    builds use: data/map/work/data/sources/natural_earth_vector.gpkg.zip
    (fetch_sources.py). The tables the schema reads are copied into a small
    GeoPackage, data/map/work/overview/ne_overview.gpkg, so Planetiler does not
    read all 183 tables.
  - US Census TIGER/Line 2026 primary roads (public domain), pinned by SHA-256
    in fetch_sources.py: data/map/work/data/census/tl_2026_us_primaryroads.zip.
  - US Census 2026 Gazetteer places and Vintage 2025 population estimates
    (public domain, pinned): towns of 5,000 people or more that Natural Earth
    lacks (census_places.py).

Steps:
  1. copy the Natural Earth tables (skipped when the subset exists), and write
     the Census towns (census_places.py);
  2. Planetiler 0.10.2 generate-custom with packbuilder/map/overview/overview.yml
     (tools/bin/planetiler/planetiler.jar from tools/fetch_map_tools.py), 4 threads,
     1 GB heap, bounds -180,15,-50,72 (all 50 states but the far end of the
     Aleutians beyond 180 degrees); measured by measure.py;
  3. finish_pack.py: header centre on the lower 48, label text clean-up.
Output: data/map/packs-v2/us-overview-ne512-tiger2026-z0-7.pmtiles (+ .build.json).
"""
import argparse
import hashlib
import json
import shutil
import sqlite3
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
from finish_pack import finish  # noqa: E402
from measure import run_measured  # noqa: E402

REPO = HERE.parents[2]
MAP = REPO / "data" / "map"
WORK = MAP / "work" / "overview"
NE_ZIP = MAP / "work" / "data" / "sources" / "natural_earth_vector.gpkg.zip"
ROADS = MAP / "work" / "data" / "census" / "tl_2026_us_primaryroads.zip"
JAR = REPO / "tools" / "bin" / "planetiler" / "planetiler.jar"
SCHEMA = HERE / "overview.yml"
OUT = REPO / "data" / "map" / "packs-v2" / "us-overview-ne512-tiger2026-z0-7.pmtiles"
BOUNDS = "-180,15,-50,72"
CENTER = (-96.5, 38.5, 3)
NE_TABLES = [
    "ne_50m_land", "ne_10m_land", "ne_50m_ocean", "ne_10m_ocean", "ne_10m_lakes", "ne_10m_lakes_north_america",
    "ne_10m_parks_and_protected_lands_area", "ne_10m_admin_0_boundary_lines_land",
    "ne_10m_admin_1_states_provinces", "ne_10m_admin_1_states_provinces_lines", "ne_10m_populated_places",
    "ne_10m_roads", "ne_10m_admin_0_countries",
]


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 22), b""):
            h.update(chunk)
    return h.hexdigest()


def ne_subset(force: bool) -> Path:
    """Copy the needed Natural Earth tables (schema, rows, GeoPackage metadata) into a small GeoPackage."""
    out = WORK / "ne_overview.gpkg"
    if out.exists() and not force:
        return out
    WORK.mkdir(parents=True, exist_ok=True)
    full = WORK / "natural_earth_vector.gpkg"
    if not full.exists():
        with zipfile.ZipFile(NE_ZIP) as z, z.open("packages/natural_earth_vector.gpkg") as s, open(full, "wb") as d:
            shutil.copyfileobj(s, d, 1 << 22)
    tmp = out.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    con = sqlite3.connect(tmp)
    con.execute(f"ATTACH DATABASE '{full.as_posix()}' AS src")
    con.execute("PRAGMA application_id = 1196444487")
    con.execute("PRAGMA user_version = 10200")
    for t in ("gpkg_spatial_ref_sys", "gpkg_contents", "gpkg_geometry_columns"):
        sql = con.execute("SELECT sql FROM src.sqlite_master WHERE name = ?", (t,)).fetchone()[0]
        con.execute(sql)
    con.execute("INSERT INTO gpkg_spatial_ref_sys SELECT * FROM src.gpkg_spatial_ref_sys")
    for t in NE_TABLES:
        sql = con.execute("SELECT sql FROM src.sqlite_master WHERE type = 'table' AND name = ?", (t,)).fetchone()[0]
        con.execute(sql)
        con.execute(f'INSERT INTO "{t}" SELECT * FROM src."{t}"')
        con.execute("INSERT INTO gpkg_contents SELECT * FROM src.gpkg_contents WHERE table_name = ?", (t,))
        con.execute("INSERT INTO gpkg_geometry_columns SELECT * FROM src.gpkg_geometry_columns WHERE table_name = ?",
                    (t,))
    version = zipfile.ZipFile(NE_ZIP).read("VERSION").decode().strip()
    con.commit()
    con.execute("DETACH DATABASE src")
    con.execute("VACUUM")
    con.close()
    tmp.replace(out)
    (WORK / "ne_overview.json").write_text(json.dumps({"natural_earth_version": version, "tables": NE_TABLES,
                                                        "source_sha256": sha256_of(NE_ZIP)}, indent=1))
    print(f"Natural Earth {version}: {len(NE_TABLES)} tables -> {out.relative_to(REPO).as_posix()} "
          f"({out.stat().st_size / 1e6:.1f} MB)")
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--threads", type=int, default=4)
    a = ap.parse_args()
    if OUT.exists() and not a.force:
        print(f"skip: {OUT.relative_to(REPO).as_posix()} exists (use --force)")
        return 0
    for need, how in ((NE_ZIP, "packbuilder/map/fetch_sources.py"), (ROADS, "packbuilder/map/fetch_sources.py --pinned-only"),
                      (JAR, "python tools/fetch_map_tools.py planetiler")):
        if not need.exists():
            raise SystemExit(f"{need.relative_to(REPO).as_posix()} missing: run {how}")
    gpkg = ne_subset(a.force)
    subprocess.run([sys.executable, str(HERE / "census_places.py")], check=True)
    raw = WORK / OUT.name
    cmd = ["java", "-Xmx1g", "-jar", JAR, "generate-custom", f"--schema={SCHEMA}",
           f"--ne_path={gpkg}", f"--tiger_roads_path={ROADS}",
           f"--census_places_path={WORK / 'census_places.geojson'}",
           f"--output={raw}", "--force", f"--threads={a.threads}", f"--bounds={BOUNDS}",
           f"--tmpdir={WORK / 'tmp'}"]
    res = run_measured(cmd, MAP / "logs" / "us-overview.log", cwd=REPO)
    if res["returncode"] != 0:
        print(json.dumps(res, indent=1))
        raise SystemExit("Planetiler failed, see data/map/logs/us-overview.log")
    fin = finish(raw, OUT, None, force=True, center=CENTER)
    rec = {
        "pack": OUT.relative_to(REPO).as_posix(),
        "output_bytes": OUT.stat().st_size,
        "planetiler": {k: v for k, v in res.items() if k != "command"},
        "command": [str(c).replace(str(REPO) + "\\", "").replace("\\", "/") for c in res["command"]],
        "inputs": {
            "natural_earth": {"file": NE_ZIP.relative_to(REPO).as_posix(), "sha256": sha256_of(NE_ZIP),
                              "tables": NE_TABLES, "licence": "public domain"},
            "tiger_primary_roads": {"file": ROADS.relative_to(REPO).as_posix(), "sha256": sha256_of(ROADS),
                                    "licence": "public domain"},
            "census_places": {"files": ["data/map/work/data/census/2026_Gaz_place_national.zip",
                                        "data/map/work/data/census/sub-est2025.csv"],
                              "sha256": [sha256_of(REPO / "data/map/work/data/census/2026_Gaz_place_national.zip"),
                                         sha256_of(REPO / "data/map/work/data/census/sub-est2025.csv")],
                              "licence": "public domain"},
            "schema": {"file": SCHEMA.relative_to(REPO).as_posix(), "sha256": sha256_of(SCHEMA)},
            "planetiler_jar": {"file": JAR.relative_to(REPO).as_posix(), "sha256": sha256_of(JAR)},
        },
        "bounds_arg": BOUNDS,
        "finish": {k: v for k, v in fin.items() if k != "samples_before"},
        "note": "other engineers' heavy jobs were running on the same machine",
    }
    OUT.with_suffix(".build.json").write_text(json.dumps(rec, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"ok {OUT.relative_to(REPO).as_posix()}: {rec['output_bytes'] / 1e6:.2f} MB, Planetiler {res['wall_seconds']} s, "
          f"peak {res['peak_rss_gib']} GiB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
