"""Step: sizes for PA and a labelled whole-US estimate.

    python packbuilder/addresses/step_estimate.py [--force]

Measured: PA download sizes (sources.lock.json), PA output size and per-table
sizes (SQLite dbstat), PA row counts, gzip -6 / xz -6 sizes of the full and the
slim (device) file. Estimated (labelled as such):
  - national TIGER ADDRFEAT download: sum of the file sizes printed in the
    Census directory listing (rounded to 0.1 MB/KB by the server);
  - whole-US address database size, two ways:
      low  = PA bytes per housing unit x US housing units (assumes the PA mix
             of OSM + NAD points and TIGER ranges everywhere);
      high = TIGER part scaled by national/PA ADDRFEAT download size, plus one
             address point per US housing unit at the PA bytes-per-point.
Output: data/addresses/reports/estimate.json
"""
from __future__ import annotations

import json
import re
import sqlite3
import urllib.request

import openpyxl

from common import DL, HERE, METRICS, OUT_DB, REPORTS, SOURCES, Step, file_mb

NAD_TXT_ZIP_BYTES = 8_477_543_836   # Content-Range of TXT.zip, 2026-11-15
NAD_ROWS = 99_226_272               # NAD r24 sample profile file published with TXT.zip


def listing_sizes(url):
    html = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "beamline-packbuilder/0.1"}),
                                  timeout=120).read().decode("utf-8", "replace")
    out = {}
    for name, size in re.findall(r'href="(tl_\d+_\d{5}_addrfeat\.zip)".*?<td align="right">\s*([\d.]+[KMG]?)\s*</td>',
                                 html, flags=re.S):
        mult = {"K": 1024, "M": 1024 ** 2, "G": 1024 ** 3}.get(size[-1], 1)  # Apache listing uses KiB/MiB
        out[name] = float(size.rstrip("KMG")) * mult
    return out


def compressed_mb(path):
    """gzip -6 and xz -6 sizes of the pack file (what a download would carry)."""
    import gzip
    import lzma
    out = {}
    for name, opener in (("gzip6", lambda f: gzip.GzipFile(fileobj=f, mode="wb", compresslevel=6)),
                         ("xz6", lambda f: lzma.LZMAFile(f, mode="wb", preset=6))):
        class Counter:
            n = 0
            def write(self, b):
                self.n += len(b)
                return len(b)
            def flush(self):
                pass
        c = Counter()
        with open(path, "rb") as src, opener(c) as z:
            while True:
                b = src.read(1 << 22)
                if not b:
                    break
                z.write(b)
        out[name] = round(c.n / 1e6, 1)
    return out


def us_housing_units():
    wb = openpyxl.load_workbook(DL / "NST-EST2025-HU.xlsx", read_only=True)
    for row in wb.active.iter_rows(values_only=True):
        if row[0] == "United States":
            return int(row[7])


def main():
    with Step("estimate") as st:
        lock = json.loads((HERE / "sources.lock.json").read_text(encoding="utf-8"))
        build = json.loads((METRICS / "build.json").read_text(encoding="utf-8"))
        cov = json.loads((REPORTS / "coverage_summary.json").read_text(encoding="utf-8"))
        sizes = listing_sizes(SOURCES["tiger_addrfeat_index"])
        pa_list = sum(v for k, v in sizes.items() if k.split("_")[2].startswith(SOURCES["state"]["fips"]))
        us_list = sum(sizes.values())
        pa_dl = sum(v["bytes"] for k, v in lock.items() if k.startswith("addrfeat_"))
        hu_pa = cov["statewide"]["housing_units_2025"]
        hu_us = us_housing_units()
        tables = build["table_mb"]
        out_mb = build["output_mb"]
        tiger_part = sum(v for k, v in tables.items() if k in ("ranges", "edges"))  # incl. their indexes
        point_part = tables["addresses"]
        street_part = out_mb - tiger_part - point_part
        n_pts = build["rows"]["addresses"]
        bpp = point_part * 1e6 / n_pts
        ratio_tiger = us_list / pa_list
        low = out_mb * hu_us / hu_pa
        high = tiger_part * ratio_tiger + street_part * ratio_tiger + bpp * hu_us / 1e6
        comp = compressed_mb(OUT_DB)
        slim = OUT_DB.with_name(OUT_DB.stem + "-slim.sqlite")
        slim_info = {"pa_slim_output_mb": file_mb(slim), "pa_slim_output_compressed_mb": compressed_mb(slim)} \
            if slim.exists() else {}
        res = {
            "measured": {
                "pa_output_mb": out_mb, "pa_output_compressed_mb": comp, **slim_info,
                "pa_rows": build["rows"], "pa_table_mb": tables,
                "pa_tiger_addrfeat_download_mb": round(pa_dl / 1e6, 1),
                "pa_tiger_addrfeat_files": sum(1 for k in lock if k.startswith("addrfeat_")),
                "pa_bytes_per_address_point_incl_index": round(bpp, 1),
                "nad_r24_txt_zip_gb": round(NAD_TXT_ZIP_BYTES / 1e9, 2), "nad_r24_rows": NAD_ROWS,
                "pa_housing_units_2025": hu_pa, "us_housing_units_2025": hu_us,
            },
            "estimate": {
                "us_tiger_addrfeat_download_gb_from_listing": round(us_list / 1e9, 2),
                "us_tiger_addrfeat_files": len(sizes),
                "pa_tiger_addrfeat_download_mb_from_listing": round(pa_list / 1e6, 1),
                "us_over_pa_addrfeat_ratio": round(ratio_tiger, 1),
                "us_over_pa_housing_ratio": round(hu_us / hu_pa, 1),
                "us_address_db_gb_low": round(low / 1e3, 2),
                "us_address_db_gb_high": round(high / 1e3, 2),
                "basis": {
                    "low": "PA output MB x (US housing units / PA housing units)",
                    "high": "(PA TIGER+street tables x national/PA ADDRFEAT size) + one point per US housing unit "
                            "x PA bytes per point",
                },
            },
        }
        (REPORTS / "estimate.json").write_text(json.dumps(res, indent=2), encoding="utf-8")
        st.info.update(res["estimate"])


if __name__ == "__main__":
    main()
