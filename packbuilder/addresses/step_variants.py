"""Step: size of pack variants, for choosing what the PA pack ships by default.

    python packbuilder/addresses/step_variants.py [--force]

Variants:
  full         - pa-addresses.sqlite as built (with diagnostic columns)
  slim         - pa-addresses-slim.sqlite as built by step_build: the device file (no source_id, zip_src)
  points_only  - slim without ranges and edges (no interpolation fallback)  -> work/variant_points_only.sqlite
  ranges_only  - slim without address points (TIGER/OSM ranges + streets)   -> work/variant_ranges_only.sqlite
Each variant is opened with addrlib.lookup.AddressIndex and asked the same test addresses, so the
'what it can answer' column of the report comes from the files themselves.
Output: data/addresses/reports/variants.json
"""
from __future__ import annotations

import gzip
import json
import shutil
import sqlite3

from common import OUT_DB, REPORTS, WORK, Step, file_mb
from addrlib.lookup import AddressIndex

SLIM_DB = OUT_DB.with_name(OUT_DB.stem + "-slim.sqlite")
PROBES = ["626 State St Erie PA", "414 Grant St Pittsburgh PA", "501 N 3rd St Harrisburg PA",
          "1400 JFK Blvd Philadelphia PA", "243 S Allen St State College PA", "340 N Washington Ave Scranton PA",
          "1200 E Pittsburgh St Greensburg PA", "150 Main St Brookville PA"]


def gzip_mb(p):
    class C:
        n = 0
        def write(self, b):
            self.n += len(b)
            return len(b)
        def flush(self):
            pass
    c = C()
    with open(p, "rb") as f, gzip.GzipFile(fileobj=c, mode="wb", compresslevel=6) as z:
        shutil.copyfileobj(f, z, 1 << 22)
    return round(c.n / 1e6, 1)


def make(name, sql):
    out = WORK / f"variant_{name}.sqlite"
    if out.exists():
        out.unlink()
    shutil.copyfile(SLIM_DB, out)
    con = sqlite3.connect(out)
    con.executescript(sql)
    con.commit()
    con.execute("VACUUM")
    con.close()
    return out


def probe(path):
    idx = AddressIndex(path)
    out = {}
    for q in PROBES:
        r = idx.geocode(q)
        out[q] = (f"{r[0]['accuracy']} ({r[0]['source']}, {r[0]['city']})" if r else "none")
    idx.con.close()
    return out


def main():
    with Step("variants") as st:
        variants = {
            "points_only": """
                DROP TABLE ranges; DROP TABLE edges;
                DELETE FROM streets WHERE n_points = 0;
                DELETE FROM localities WHERE id NOT IN (SELECT locality_id FROM streets);""",
            "ranges_only": """
                DROP VIEW address_rows; DROP TABLE addresses;
                DELETE FROM streets WHERE n_ranges = 0;
                DELETE FROM localities WHERE id NOT IN (SELECT locality_id FROM streets);""",
        }
        res = {"full": {"file": "pa-addresses.sqlite", "mb": file_mb(OUT_DB), "gzip6_mb": gzip_mb(OUT_DB),
                        "answers": probe(OUT_DB)},
               "slim": {"file": SLIM_DB.name, "mb": file_mb(SLIM_DB), "gzip6_mb": gzip_mb(SLIM_DB),
                        "answers": probe(SLIM_DB)}}
        for name, sql in variants.items():
            p = make(name, sql)
            res[name] = {"file": f"work/{p.name}", "mb": file_mb(p), "gzip6_mb": gzip_mb(p), "answers": probe(p)}
            print(name, res[name], flush=True)
        (REPORTS / "variants.json").write_text(json.dumps(res, indent=2), encoding="utf-8")
        st.info.update({k: {"mb": v["mb"], "gzip6_mb": v["gzip6_mb"]} for k, v in res.items()})


if __name__ == "__main__":
    main()
