"""Step: hold-out accuracy test of TIGER interpolation and NAD points against OSM address points.

    python packbuilder/addresses/step_eval_accuracy.py [--force] [--n 5000] [--seed 20261115]

Draws a random sample of OSM address points (equal thirds from urban, suburban
and rural counties, see step_coverage), then resolves each one using only
(a) TIGER ADDRFEAT ranges and (b) only NAD points. Neither resolver sees the
OSM coordinates. The main run gets house number, street name, county, the OSM
postcode when there is one, and the municipality (county subdivision) the
point lies in. That municipality is derived from the true position, so this is
the optimistic case. When several candidates remain, the one whose ZIP, then
municipality, matches wins; otherwise the first. A second, 'blind' run
(columns *_blind_*) gets only house number, street and county.
Outputs: data/addresses/reports/accuracy.json, holdout_sample.csv
"""
from __future__ import annotations

import collections
import csv
import json
import random
import sqlite3

import numpy as np

from common import REPORTS, WORK, Step
from addrlib.geo import decode_line, haversine_m, interpolate
from addrlib.normalize import highway_alternatives, house_number, street_core, street_key, zip_parts
from geoassign import assign, load_counties, load_cousubs


def stats(errs, n_total):
    found = [e for e in errs if e is not None]
    out = {"n": n_total, "found": len(found), "not_found_share": round(1 - len(found) / n_total, 3) if n_total else None}
    if found:
        a = np.array(found)
        out.update({"median_m": round(float(np.median(a)), 1), "p90_m": round(float(np.percentile(a, 90)), 1),
                    "within_50m_of_all": round(float((a <= 50).sum()) / n_total, 3),
                    "within_200m_of_all": round(float((a <= 200).sum()) / n_total, 3),
                    "within_50m_of_found": round(float((a <= 50).mean()), 3),
                    "within_200m_of_found": round(float((a <= 200).mean()), 3)})
    return out


def pick(cands, z, cous):
    """cands: [(..., zip, cousub, payload)] -> best payload by ZIP then municipality agreement."""
    if not cands:
        return None, 0
    def score(c):
        return (2 if z and c[0] == z else 0) + (1 if cous and c[1] == cous else 0)
    best = max(cands, key=score)
    return best[2], len(cands)


def main():
    with Step("eval_accuracy", extra_args=[(("--n",), {"type": int, "default": 5000}),
                                           (("--seed",), {"type": int, "default": 20261115})]) as st:
        types = json.loads((REPORTS / "county_types.json").read_text(encoding="utf-8"))
        rng = random.Random(st.args.seed)
        con = sqlite3.connect(WORK / "osm_pa.sqlite")
        pool = collections.defaultdict(list)
        for row in con.execute("""SELECT osm_type, osm_id, hn_raw, street, postcode, lat, lon, county_fips, cousub_geoid
                                  FROM osm WHERE county_fips IS NOT NULL AND street IS NOT NULL
                                  AND hn_raw NOT LIKE '%;%' AND hn_raw NOT LIKE '%,%'"""):
            if house_number(row[2])[1] is not None:
                pool[types[row[7]]["type"]].append(row)
        con.close()
        per = st.args.n // 3
        sample = []
        for i, t in enumerate(("urban", "suburban", "rural")):
            k = per + (st.args.n - 3 * per if i == 0 else 0)
            sample += [(t,) + r for r in rng.sample(pool[t], k)]
        # extra open point sources (e.g. Scranton 911): their own sample in the counties they cover
        extra_db = WORK / "extra_pa.sqlite"
        extra_rows, extra_counties = [], set()
        counties = load_counties()
        cousubs = load_cousubs()
        if extra_db.exists():
            ec = sqlite3.connect(extra_db)
            extra_rows = ec.execute("SELECT source, hn, street, zip, lon, lat FROM extra").fetchall()
            ec.close()
            elon = np.array([r[4] if r[4] is not None else np.nan for r in extra_rows], dtype=float)
            elat = np.array([r[5] if r[5] is not None else np.nan for r in extra_rows], dtype=float)
            eok = np.isfinite(elon)
            elon[~eok] = 0
            elat[~eok] = 0
            eci = assign(counties, elon, elat)
            emi = assign(cousubs, elon, elat)
            ecount = collections.Counter(counties[i][0] for i in eci if i >= 0)
            extra_counties = {c for c, n in ecount.items() if n >= 10000}  # the counties a source really covers
            in_main = {(r[1], r[2]) for r in sample}
            cand = [r for t in pool for r in pool[t] if r[7] in extra_counties and (r[0], r[1]) not in in_main]
            sample += [("extra_sample",) + r for r in rng.sample(cand, min(600, len(cand)))]
        keys = {}
        for s in sample:
            keys.setdefault(s[4], street_key(s[4]))
        need_keys = set(keys.values())
        for k in list(need_keys):
            need_keys.update(highway_alternatives(k))
        need_cores = {street_core(k) for k in need_keys}

        # ---- TIGER ranges for the needed keys (and keys with the same core, e.g. 'w ritner st' for 'ritner st')
        tc = sqlite3.connect(WORK / "tiger_pa.sqlite")
        t_core = collections.defaultdict(set)
        for cf, k in tc.execute("SELECT DISTINCT e.county_fips, r.street_key FROM ranges r JOIN edges e USING(tlid)"):
            c = street_core(k)
            if c in need_cores:
                t_core[(cf, c)].add(k)
                need_keys.add(k)
        tr = collections.defaultdict(list)
        q = ("SELECT e.county_fips, r.street_key, r.tlid, r.side, r.hn_from, r.hn_to, r.parity, r.zip, e.cousub_geoid "
             "FROM ranges r JOIN edges e USING(tlid) WHERE r.street_key = ?")
        for k in need_keys:
            for cf, kk, tl, side, a, b, par, z, cous in tc.execute(q, (k,)):
                tr[(cf, kk)].append((tl, side, a, b, par, z, cous))
        geom_cache = {}

        def try_keys(cf, key, core_map):
            first = [key] + highway_alternatives(key)
            return first + sorted(core_map.get((cf, street_core(key)), set()) - set(first))

        def tiger_resolve(cf, key, hni, z, cous, offset):
            for k in try_keys(cf, key, t_core):
                cands = []
                for tl, side, a, b, par, zz, cs in tr.get((cf, k), ()):
                    if not (min(a, b) <= hni <= max(a, b)):
                        continue
                    if par == "O" and hni % 2 == 0 or par == "E" and hni % 2 == 1:
                        continue
                    cands.append((zz, cs, (tl, side, a, b)))
                best, n = pick(cands, z, cous)
                if best:
                    tl, side, a, b = best
                    if tl not in geom_cache:
                        geom_cache[tl] = decode_line(tc.execute("SELECT geom FROM edges WHERE tlid=?", (tl,)).fetchone()[0])
                    frac = 0.5 if a == b else (hni - a) / (b - a)
                    lon, lat = interpolate(geom_cache[tl], frac, side, offset)
                    return lon, lat, n
            return None

        # ---- NAD points
        nad = collections.defaultdict(list)
        n_core = collections.defaultdict(set)
        nad_counties = set()
        nad_db = WORK / "nad_pa.sqlite"
        if nad_db.exists():
            nc = sqlite3.connect(nad_db)
            rows = nc.execute("SELECT AddNo_Full, Add_Number, StNam_Full, Zip_Code, Longitude, Latitude FROM nad").fetchall()
            nc.close()
            lon = np.array([float(r[4] or "nan") for r in rows])
            lat = np.array([float(r[5] or "nan") for r in rows])
            ok = np.isfinite(lon)
            lon[~ok] = 0
            lat[~ok] = 0
            ci = assign(counties, lon, lat)
            mi = assign(cousubs, lon, lat)
            per_county = collections.Counter()
            kc = {}
            for j, r in enumerate(rows):
                if ci[j] < 0 or not r[2]:
                    continue
                cf = counties[ci[j]][0]
                per_county[cf] += 1
                k = kc.get(r[2]) or kc.setdefault(r[2], street_key(r[2]))
                c = street_core(k)
                if c not in need_cores:
                    continue
                n_core[(cf, c)].add(k)
                hn = house_number(r[0] or str(r[1] or "").split(".")[0])[0]
                if hn:
                    nad[(cf, hn, k)].append((zip_parts(r[3])[0] if r[3] else None,
                                             cousubs[mi[j]][0] if mi[j] >= 0 else None, (lon[j], lat[j])))
            nad_counties = {c for c, n in per_county.items() if n >= 1000}

        ext = collections.defaultdict(list)
        e_core = collections.defaultdict(set)
        kc2 = {}
        for j, r in enumerate(extra_rows):
            if eci[j] < 0 or not r[2]:
                continue
            cf = counties[eci[j]][0]
            k = kc2.get(r[2]) or kc2.setdefault(r[2], street_key(r[2]))
            c = street_core(k)
            if c not in need_cores:
                continue
            e_core[(cf, c)].add(k)
            hn = house_number(r[1] or "")[0]
            if hn:
                ext[(cf, hn, k)].append((zip_parts(r[3])[0] if r[3] else None,
                                         cousubs[emi[j]][0] if emi[j] >= 0 else None, (elon[j], elat[j])))

        def extra_resolve(cf, key, hn, z, cous):
            for k in try_keys(cf, key, e_core):
                best, n = pick(ext.get((cf, hn, k), []), z, cous)
                if best:
                    return float(best[0]), float(best[1]), n
            return None

        def nad_resolve(cf, key, hn, z, cous):
            for k in try_keys(cf, key, n_core):
                best, n = pick(nad.get((cf, hn, k), []), z, cous)
                if best:
                    return float(best[0]), float(best[1]), n
            return None

        # ---- run
        res = []
        for t, typ, oid, hn_raw, street, pc, lat, lon, cf, cous in sample:
            hn, hni = house_number(hn_raw)
            k = keys[street]
            z = zip_parts(pc)[0] if pc else None
            row = {"type": t, "county": cf, "county_name": types[cf]["county"], "osm": f"{typ}{oid}",
                   "hn": hn, "street": street, "key": k, "zip_given": z, "lat": lat, "lon": lon,
                   "in_nad_county": cf in nad_counties}
            for off in (0, 10):
                r = tiger_resolve(cf, k, hni, z, cous, off)
                row[f"tiger_off{off}_m"] = None if r is None else round(haversine_m(lon, lat, r[0], r[1]), 1)
                if off == 0:
                    row["tiger_candidates"] = r[2] if r else 0
            r = tiger_resolve(cf, k, hni, None, None, 10)  # blind: county only
            row["tiger_blind_off10_m"] = None if r is None else round(haversine_m(lon, lat, r[0], r[1]), 1)
            r = nad_resolve(cf, k, hn, None, None)
            row["nad_blind_m"] = None if r is None else round(haversine_m(lon, lat, r[0], r[1]), 1)
            r = nad_resolve(cf, k, hn, z, cous)
            row["nad_m"] = None if r is None else round(haversine_m(lon, lat, r[0], r[1]), 1)
            row["nad_then_tiger_m"] = row["nad_m"] if row["nad_m"] is not None else row["tiger_off0_m"]
            r = extra_resolve(cf, k, hn, z, cous)
            row["extra_m"] = None if r is None else round(haversine_m(lon, lat, r[0], r[1]), 1)
            row["extra_sample"] = t == "extra_sample"
            if t == "extra_sample":
                row["type"] = types[cf]["type"]
            res.append(row)

        with open(REPORTS / "holdout_sample.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(res[0]))
            w.writeheader()
            w.writerows(res)

        extra_res = [r for r in res if r["extra_sample"]]
        res = [r for r in res if not r["extra_sample"]]
        out = {"sample_size": len(res), "seed": st.args.seed, "by_type": {}, "nad_counties_only": {},
               "call_out": {}, "nad_counties": sorted(nad_counties)}
        cols = ["tiger_off0_m", "tiger_off10_m", "tiger_blind_off10_m", "nad_m", "nad_blind_m", "nad_then_tiger_m"]
        for t in ("all", "urban", "suburban", "rural"):
            sel = [r for r in res if t == "all" or r["type"] == t]
            out["by_type"][t] = {c: stats([r[c] for r in sel], len(sel)) for c in cols}
            seln = [r for r in sel if r["in_nad_county"]]
            if seln:
                out["nad_counties_only"][t] = {c: stats([r[c] for r in seln], len(seln)) for c in cols}
        for name in ("Erie", "Allegheny", "Philadelphia", "Centre"):
            sel = [r for r in res if r["county_name"] == name]
            if sel:
                out["call_out"][name] = {c: stats([r[c] for r in sel], len(sel)) for c in cols}
        if extra_res:
            out["extra_sources"] = {"counties": sorted(extra_counties), "n": len(extra_res),
                                    **{c: stats([r[c] for r in extra_res], len(extra_res))
                                       for c in ("tiger_off0_m", "extra_m", "nad_m")}}
            xd = [r["extra_m"] for r in extra_res if r["extra_m"] is not None]
            out["extra_sources"]["identical_position_share"] = round(sum(1 for d in xd if d < 1.0) / len(xd), 3) if xd else None
        nd = [r["nad_m"] for r in res if r["nad_m"] is not None]
        out["nad_identical_position_share"] = round(sum(1 for d in nd if d < 1.0) / len(nd), 3) if nd else None
        out["tiger_ambiguous_share"] = round(sum(1 for r in res if r["tiger_candidates"] > 1) / len(res), 3)
        (REPORTS / "accuracy.json").write_text(json.dumps(out, indent=2), encoding="utf-8")
        st.info.update({"sample": len(res), "tiger_median_all": out["by_type"]["all"]["tiger_off0_m"].get("median_m"),
                        "nad_used": bool(nad)})


if __name__ == "__main__":
    main()
