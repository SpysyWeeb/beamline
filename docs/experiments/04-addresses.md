# Experiment 04: US street addresses for Pennsylvania

Date: 2026-11-15. Track: addresses (coverage, gap-filling data, normalisation, lookup).
Code: `packbuilder/addresses/`. Output: `data/addresses/pa-addresses.sqlite` (full) and
`data/addresses/pa-addresses-slim.sqlite` (the device file), not in git.
All timings were taken while other engineers' heavy builds were running on the same
machine (at one check, two Java processes held 4.3 GB and 2.3 GB; in round 2 a QEMU VM held
3.6 GB and a Java process 2.2 GB). Wall times are therefore pessimistic and noisy.

**Round 2 (same day):** a second engineer took the track over after two independent reviews,
fixed the findings and re-ran tiger, coverage, build and every evaluation. All numbers below
are from round 2 unless marked otherwise. What changed and why is in "Review and fixes" at the end.

## Summary

- **OSM has house numbers for about 18% of Pennsylvania.** 1,108,519 OSM objects with
  `addr:housenumber` inside PA (1,059,812 distinct number + street pairs) against
  5,882,221 housing units (Census PEP Vintage 2025). 57 of 67 counties are below 10%.
  Coverage is high only where someone imported county data: Centre 78%, Lehigh 77%,
  Philadelphia 64%. Allegheny 16%, Erie 30%.
- **TIGER/Line 2026 address ranges fill almost everything, but coarsely.** On 5,000
  held-out OSM points, TIGER interpolation finds 96.5% of addresses; median error is 72 m,
  90th percentile 228 m, 32% are within 50 m and 85% within 200 m. A 10 m offset
  to the correct side of the street helps a little (median 70 m, 34% within 50 m). That
  offset is now the default. These figures let the resolver see the target's ZIP and the
  municipality it lies in. Told only the county, TIGER does worse: median 74 m, p90 294 m,
  82% within 200 m.
- **The National Address Database (release 24, public domain per USDOT's labels) covers 10 PA counties**
  (1,074,485 rows, about 0.95 M distinct addresses). Where it exists it is much better
  than TIGER: median 30 m, 90th percentile 95 m, 64% within 50 m, 8% not found.
  Pennsylvania has no open statewide address-point file. Of the 66 OpenAddresses PA source
  files, only one has a clearly open licence: the City of Scranton's 911 address layer
  (PDDL). It covers Lackawanna County (median 22 m; 26% not found because the layer is
  incomplete).
- **Built pack:** 2,038,629 address points (OSM 1.08 M, NAD 0.86 M not already in OSM,
  Scranton 0.09 M) plus 1.43 M TIGER range rows on 797 k street edges. The full file is
  353 MB as SQLite (158 MB gzip, 103 MB xz). The device file `pa-addresses-slim.sqlite`
  drops two diagnostic columns: **288 MB, 132 MB gzip, 83 MB xz** (all measured). Points
  with house numbers cover about 34% of housing units, and TIGER is the fallback for the rest.
- **Normalisation and a rule-based parser** (data in `addrlib/us_street_norm.json`, logic
  in standard-library Python, meant to be ported to Kotlin). On 300 typed variants of real
  addresses (abbreviated, no ZIP, no city, one typo), two numbers matter:
  - **The address has a point in the pack:** 100% resolve to within 100 m (usaddress feeding the
    same lookup: 99.0%). This tests parsing and street matching only, since the answer is in the file.
  - **The address has no point** (target removed from a copy of the pack): 67.7% within 100 m and
    89.3% within 200 m, median 63 m. This is TIGER interpolation's accuracy, and it is what most PA
    addresses get, because points cover only about a third of homes.
  - Typos in the street-type word ('Srteet'), tested separately: rules 100%, usaddress 94.7%.
  - Rules were chosen over usaddress (MIT, CRF model) because they port to Kotlin as data and code.
- **Whole-US estimate:** 8.9 to 16.5 GB uncompressed for all states. This is an estimate,
  and its basis is given below.

## How to rerun

From the repo root on Windows (Python 3.12.10). Linux works the same with `bin/python`.

```
py -3.12 -m venv .venv/addresses
.venv/addresses/Scripts/python.exe -m pip install -r packbuilder/addresses/requirements.txt
.venv/addresses/Scripts/python.exe packbuilder/addresses/run.py --list
.venv/addresses/Scripts/python.exe packbuilder/addresses/run.py
.venv/addresses/Scripts/python.exe -m unittest discover -s packbuilder/addresses/tests -v
.venv/addresses/Scripts/python.exe packbuilder/addresses/report_tables.py > data/addresses/reports/tables.md
```

`run.py` runs one script per step in its own process and skips finished steps.
`--force <step ...>` re-runs the named steps. The steps are: download, nad, extra, osm,
tiger, coverage, build, eval_accuracy, eval_parser, estimate and variants. Round 2 ran
`run.py --force tiger coverage build eval_accuracy eval_parser estimate variants` (790 s),
then `run.py --only eval_parser --force eval_parser` after the last parser fix. Each step
writes `data/addresses/metrics/<step>.json` with its wall time, CPU time and peak
working set. Reports go to `data/addresses/reports/`, and the README in
`packbuilder/addresses/` has the step table and the output schema.

Inputs: the shared `data/osm/pennsylvania-261114.osm.pbf`. The osm step waits until its
MD5 matches the `.md5` file, and the check passed (`a08fd76d...`). The other inputs
are pinned in `packbuilder/addresses/sources.json`. Their SHA-256 hashes are in
`sources.lock.json`, and a later run that downloads a changed file stops with an error.
The NAD and Scranton layers come from live ArcGIS services, which have no hash. The
NAD step checks that each county's row count matches the service's own statistics.

## 1. The gap: OSM house numbers per county

Method: every node, way and relation in the PA extract that has `addr:housenumber`
(pyosmium). A way's position is its polygon centroid, and a relation's is the mean of
its outer-way vertices. Counties come from the Census 2025 cartographic boundary file.
"Distinct" counts unique (house number, normalised street key) pairs, so that a building
and a POI node with the same address count once. Counts are compared with **Census
Population Estimates Program, Vintage 2025, table CO-EST2025-HU-42: Annual Estimates of
Housing Units for Counties in Pennsylvania, July 1, 2025** (released May 2026; public
domain).

Caveat: housing units are not addresses. An apartment building is many units and one
address, while shops, churches and barns are addresses but not units. In the NAD counties,
the authoritative points come to 86% to 108% of housing units, so the ratio is a
yardstick and not a true coverage percentage.

Statewide: 1,108,519 OSM objects (180,563 nodes, 926,906 ways, 1,050 relations).
1,101,546 of them have `addr:street`, 457 of those through an associatedStreet relation,
and 6,953 have no street at all. 558,925 (50%) have `addr:postcode`. There are 1,774
`addr:interpolation` ways, and 1,551 of them are usable as ranges. Distinct addresses:
1,059,812, which is **18.0% of 5,882,221 housing units**. Adding NAD gives 32.5%, and
adding Scranton 911 too gives 34.0%.

| County type (by housing-unit density) | Counties | Housing units | OSM / HU | OSM+NAD / HU | all points / HU |
|---|---:|---:|---:|---:|---:|
| urban (>= 300 HU/km2: Philadelphia, Delaware, Allegheny) | 3 | 1,609,473 | 37.0% | 37.0% | 37.0% |
| suburban (40 to 300) | 21 | 3,000,411 | 12.5% | 36.3% | 39.0% |
| rural (< 40) | 43 | 1,272,337 | 7.0% | 17.8% | 18.1% |

Counties by OSM / HU: 57 below 10%, 4 from 10% to 25%, 3 from 25% to 50%, 1 from 50% to 75%,
and 2 at or above 75%.

Called out:

| County | Type | HU 2025 | OSM distinct | OSM / HU | OSM with postcode | NAD distinct | all points / HU |
|---|---|---:|---:|---:|---:|---:|---:|
| Erie | suburban (58 HU/km2) | 120,762 | 35,676 | 29.5% | 99.6% | 0 | 29.5% |
| Allegheny | urban (325 HU/km2) | 614,484 | 100,853 | 16.4% | 90.9% | 15 | 16.4% |
| Philadelphia | urban | 764,829 | 489,061 | 63.9% | **1.7%** | 3 | 63.9% |
| Centre | rural (24 HU/km2; State College is urban, the county is not) | 67,780 | 52,918 | 78.1% | 99.9% | 60,054 | 91.2% |

Philadelphia's OSM addresses almost never carry a postcode, so the build fills in ZIPs
from TIGER (see section 3). In Centre, 85% of NAD addresses are already in OSM, which
looks like an earlier county import. Erie and Allegheny have no open point source; see
section 2.

Per county (NAD found in OSM = share of NAD's distinct addresses that OSM also has, by number and street key):

| County | Type | Housing units 2025 | OSM addresses (distinct) | OSM / HU | OSM objects (n/w/r) | with postcode | NAD (distinct) | NAD found in OSM | Scranton 911 (distinct) | all points / HU |
|---|---|---:|---:|---:|---|---:|---:|---:|---:|---:|
| Adams | rural | 44,750 | 1,525 | 3.4% | 117/1520/9 | 97.6% | 4 |  | 0 | 3.4% |
| Allegheny | urban | 614,484 | 100,853 | 16.4% | 24935/78256/193 | 90.9% | 15 |  | 0 | 16.4% |
| Armstrong | rural | 32,314 | 190 | 0.6% | 58/173/1 | 69.8% | 6 |  | 0 | 0.6% |
| Beaver | suburban | 79,845 | 572 | 0.7% | 180/448/4 | 75.3% | 1 |  | 0 | 0.7% |
| Bedford | rural | 23,836 | 275 | 1.2% | 112/193/0 | 88.2% | 1 |  | 0 | 1.2% |
| Berks | suburban | 172,559 | 4,368 | 2.5% | 905/3711/22 | 86.0% | 2 |  | 0 | 2.5% |
| Blair | suburban | 56,086 | 9,555 | 17.0% | 1125/8573/6 | 98.5% | 56,439 | 15.9% | 0 | 101.6% |
| Bradford | rural | 29,712 | 168 | 0.6% | 48/127/0 | 92.6% | 0 |  | 0 | 0.6% |
| Bucks | suburban | 259,320 | 6,096 | 2.4% | 1632/5115/43 | 89.3% | 719 |  | 0 | 2.6% |
| Butler | suburban | 90,342 | 2,718 | 3.0% | 168/2656/12 | 74.2% | 0 |  | 0 | 3.0% |
| Cambria | rural | 64,528 | 531 | 0.8% | 239/329/1 | 98.4% | 6 |  | 0 | 0.8% |
| Cameron | rural | 3,924 | 14 | 0.4% | 3/11/0 | 100.0% | 0 |  | 0 | 0.4% |
| Carbon | rural | 34,816 | 8,454 | 24.3% | 944/7835/3 | 99.4% | 0 |  | 0 | 24.3% |
| Centre | rural | 67,780 | 52,918 | 78.1% | 1253/53378/85 | 99.9% | 60,054 | 85.2% | 0 | 91.2% |
| Chester | suburban | 217,440 | 76,308 | 35.1% | 72573/7040/47 | 98.0% | 2 |  | 0 | 35.1% |
| Clarion | rural | 18,922 | 170 | 0.9% | 31/150/2 | 86.9% | 0 |  | 0 | 0.9% |
| Clearfield | rural | 38,429 | 1,784 | 4.6% | 165/2468/3 | 51.4% | 0 |  | 0 | 4.6% |
| Clinton | rural | 18,724 | 447 | 2.4% | 26/429/3 | 88.4% | 43 |  | 0 | 2.5% |
| Columbia | rural | 30,065 | 1,186 | 3.9% | 144/1099/2 | 74.5% | 0 |  | 0 | 3.9% |
| Crawford | rural | 42,385 | 1,849 | 4.4% | 290/1760/3 | 85.9% | 0 |  | 0 | 4.4% |
| Cumberland | suburban | 114,465 | 3,786 | 3.3% | 559/3499/9 | 90.1% | 99,156 | 3.5% | 0 | 86.9% |
| Dauphin | suburban | 129,660 | 3,772 | 2.9% | 706/3290/22 | 93.6% | 0 |  | 0 | 2.9% |
| Delaware | urban | 230,160 | 6,171 | 2.7% | 3042/3698/21 | 85.7% | 10 |  | 0 | 2.7% |
| Elk | rural | 16,968 | 137 | 0.8% | 28/120/0 | 91.2% | 0 |  | 0 | 0.8% |
| Erie | suburban | 120,762 | 35,676 | 29.5% | 3844/36188/18 | 99.6% | 0 |  | 0 | 29.5% |
| Fayette | rural | 62,792 | 389 | 0.6% | 179/271/6 | 88.6% | 64,530 | 0.5% | 0 | 102.8% |
| Forest | rural | 6,965 | 35 | 0.5% | 5/31/0 | 91.7% | 0 |  | 0 | 0.5% |
| Franklin | rural | 68,778 | 1,254 | 1.8% | 205/1241/6 | 84.6% | 5 |  | 0 | 1.8% |
| Fulton | rural | 7,237 | 43 | 0.6% | 12/37/0 | 83.7% | 0 |  | 0 | 0.6% |
| Greene | rural | 16,304 | 68 | 0.4% | 12/61/0 | 89.0% | 5 |  | 0 | 0.4% |
| Huntingdon | rural | 21,148 | 446 | 2.1% | 75/386/2 | 93.1% | 4 |  | 0 | 2.1% |
| Indiana | rural | 37,753 | 597 | 1.6% | 193/413/1 | 65.2% | 40,549 | 1.4% | 0 | 107.5% |
| Jefferson | rural | 22,163 | 231 | 1.0% | 93/224/2 | 56.7% | 0 |  | 0 | 1.0% |
| Juniata | rural | 10,608 | 30 | 0.3% | 14/17/1 | 93.8% | 0 |  | 0 | 0.3% |
| Lackawanna | suburban | 100,686 | 1,535 | 1.5% | 285/1331/18 | 87.8% | 0 |  | 83,004 | 82.9% |
| Lancaster | suburban | 223,178 | 5,034 | 2.3% | 2442/3425/35 | 70.9% | 0 |  | 0 | 2.3% |
| Lawrence | suburban | 39,704 | 344 | 0.9% | 122/305/2 | 68.8% | 40,101 | 0.7% | 0 | 101.1% |
| Lebanon | suburban | 60,891 | 1,729 | 2.8% | 1050/750/7 | 97.0% | 0 |  | 0 | 2.8% |
| Lehigh | suburban | 153,510 | 117,577 | 76.6% | 2730/118574/46 | 99.9% | 0 |  | 0 | 76.6% |
| Luzerne | suburban | 151,965 | 4,460 | 2.9% | 4828/3812/14 | 25.7% | 0 |  | 1 | 2.9% |
| Lycoming | rural | 52,857 | 3,088 | 5.8% | 226/3024/3 | 98.8% | 4 |  | 0 | 5.8% |
| McKean | rural | 19,534 | 945 | 4.8% | 216/821/2 | 92.8% | 0 |  | 0 | 4.8% |
| Mercer | rural | 51,145 | 886 | 1.7% | 102/854/9 | 84.0% | 3 |  | 0 | 1.7% |
| Mifflin | rural | 21,671 | 344 | 1.6% | 112/249/1 | 90.1% | 0 |  | 0 | 1.6% |
| Monroe | suburban | 80,353 | 2,575 | 3.2% | 1184/1790/12 | 98.0% | 0 |  | 2 | 3.2% |
| Montgomery | suburban | 355,529 | 32,514 | 9.1% | 12486/22100/64 | 91.9% | 302,990 | 9.8% | 0 | 86.1% |
| Montour | rural | 8,221 | 297 | 3.6% | 151/168/1 | 86.9% | 0 |  | 0 | 3.6% |
| Northampton | suburban | 131,421 | 6,880 | 5.2% | 2128/5865/20 | 90.9% | 0 |  | 0 | 5.2% |
| Northumberland | rural | 43,498 | 641 | 1.5% | 472/211/0 | 89.8% | 0 |  | 0 | 1.5% |
| Perry | rural | 20,342 | 373 | 1.8% | 63/395/1 | 62.3% | 2 |  | 0 | 1.8% |
| Philadelphia | urban | 764,829 | 489,061 | 63.9% | 4388/500958/204 | 1.7% | 3 |  | 0 | 63.9% |
| Pike | rural | 40,861 | 1,246 | 3.0% | 631/843/1 | 97.7% | 0 |  | 0 | 3.0% |
| Potter | rural | 12,451 | 98 | 0.8% | 16/83/0 | 94.9% | 0 |  | 0 | 0.8% |
| Schuylkill | rural | 67,679 | 1,901 | 2.8% | 1425/646/1 | 90.3% | 0 |  | 0 | 2.8% |
| Snyder | rural | 16,212 | 204 | 1.3% | 66/153/1 | 71.4% | 0 |  | 0 | 1.3% |
| Somerset | rural | 38,027 | 445 | 1.2% | 61/414/5 | 66.9% | 2 |  | 0 | 1.2% |
| Sullivan | rural | 5,630 | 35 | 0.6% | 11/25/0 | 86.1% | 0 |  | 0 | 0.6% |
| Susquehanna | rural | 21,562 | 172 | 0.8% | 49/141/6 | 95.4% | 0 |  | 2,285 | 11.4% |
| Tioga | rural | 21,698 | 230 | 1.1% | 158/95/1 | 53.9% | 23,391 | 0.8% | 0 | 108.0% |
| Union | rural | 16,372 | 99 | 0.6% | 41/61/3 | 82.9% | 0 |  | 0 | 0.6% |
| Venango | rural | 26,293 | 919 | 3.5% | 84/892/2 | 83.1% | 0 |  | 0 | 3.5% |
| Warren | rural | 21,757 | 4,578 | 21.0% | 287/4337/1 | 17.0% | 0 |  | 0 | 21.0% |
| Washington | suburban | 99,340 | 6,477 | 6.5% | 504/6158/19 | 63.1% | 94,668 | 6.2% | 0 | 95.9% |
| Wayne | rural | 32,637 | 166 | 0.5% | 38/135/1 | 94.8% | 0 |  | 2,309 | 7.5% |
| Westmoreland | suburban | 169,961 | 2,031 | 1.2% | 392/1735/12 | 91.9% | 171,135 | 1.0% | 0 | 100.9% |
| Wyoming | rural | 12,989 | 155 | 1.2% | 25/136/1 | 93.8% | 0 |  | 6 | 1.2% |
| York | suburban | 193,394 | 50,157 | 25.9% | 29875/21673/30 | 72.5% | 16 |  | 0 | 25.9% |

## 2. Gap-filling sources

| Source | What it gives for PA | Licence (checked at) | Verdict |
|---|---|---|---|
| **TIGER/Line 2026 ADDRFEAT** (vintage 2026, files dated 2026-10-06) | 67 county files, 123.6 MB zipped; 894,412 records, 796,084 edges, 1,428,442 range sides; ZIP per side | The ISO metadata inside every file: "The TIGER/Line Shapefile products are not copyrighted ... free to use in a product or publication, however acknowledgement must be given to the U.S. Census Bureau as the source." | used (fallback) |
| **USDOT National Address Database r24** (compiled 2026-09-30) | 1,074,485 PA rows. 10 counties substantial: Montgomery 357,112, Westmoreland 175,571, Cumberland 111,997, Washington 100,293, Centre 79,227, Blair 67,763, Fayette 67,750, Indiana 45,334, Lawrence 43,633, Tioga 24,136; 15 other counties have 1 to 1,080 stray rows. Newest record date per county ranges from 2022-07 (Fayette, Cumberland, Westmoreland) to 2026-07 (Centre). Placement: 56% "Unknown", 32% parcel, 11% rooftop. 26% have no ZIP | USDOT's DCAT record (harvested by data.gov): `"license": "http://www.usa.gov/publicdomain/label/1.0/"`; the data.gov parent record lists CC0 1.0. USDOT's disclaimer page returned HTTP 403 to automated fetches and was **not read** | used (point source) |
| **City of Scranton 911 address layer** (via OpenAddresses `us/pa/lackawanna.json`) | 97,499 points covering Lackawanna County (about 4,600 more fall in neighbouring Susquehanna and Wayne); 90,469 distinct base addresses, of which 89,410 were not already in OSM | ArcGIS item `licenseInfo`: "PDDL - This item is meant for public access and use ... GIS is a public domain and may be copied without permission; citation of the source is appreciated." | used (point source); the layer also has owner-name and phone fields, which are never requested |
| OpenAddresses, all 66 PA source files (GitHub API listing of `sources/us/pa`, read 2026-11-15; the folder last changed 2026-11-05), one address layer each | county 911 or parcel layers | Grouped by the `license` field in each source file: 1 explicit open licence (Lackawanna, above). 19 have only an attribution flag or a link and no licence (Butler, Clarion, Clearfield, Columbia, Delaware, Erie, Fayette, Indiana, McKean, Mifflin, Montour, Perry, Philadelphia, Potter, Snyder, Union, Venango, Washington, Westmoreland). 2 have disclaimer or "use constraints: none" text only (Dauphin, Somerset). 1 is non-commercial (Bucks). 43 have no licence information at all | not used: not clearly PD, CC0 or attribution-only. The brief allowed attribution-only sources; the 19 were left out by judgement, because their flag is OpenAddresses' own note and not the publisher's licence. Erie, Washington and Westmoreland, for example, carry `"attribution": true, "presumed": true` and a link to the county GIS page |
| Erie County 911 site/structure points (ArcGIS, 120,014 points) | would raise Erie from 30% to about 100% | the service's item has no `licenseInfo`; OpenAddresses marks attribution as "presumed" | not used; worth asking Erie County GIS for an explicit open licence |
| Allegheny County address points (WPRDC; PASDA dataset 1219, "Allegheny County - Address Points (2026)", file of 2026-11-09) | Pittsburgh area | WPRDC CKAN: "License not specified". PASDA full metadata, read 2026-11-15: no licence; use constraints "for informational purposes only ... shipped as is", and a distribution-liability clause under which "the USER shall indemnify, save harmless, and, if requested, defend" the distributors | not used: no licence grant, and an indemnity condition is more than attribution |
| Montgomery County address points | already in NAD | item says "free and open resource", but there is no licence name | covered by NAD |
| **Pennsylvania statewide open address points** | none found. PASDA hosts county layers (Allegheny, Blair, Clearfield, Crawford, Cumberland, Berks and others), each with its own metadata. PEMA says the counties provision their NG911 layers, including site/structure address points, to a statewide system, but I found no public download of that merged set | n/a | does not exist as open data as far as I could find. Only Allegheny's PASDA metadata was read in full (above); the other county layers' licences were not checked |
| Overture Maps addresses | alpha theme, "over 175 sources", each row with its own source licence, GeoParquet | mixed per source | set aside as instructed |

The national NAD text file is 8,477,543,836 bytes (TXT.zip, 99,226,272 rows, 60
columns, CSV). Its download ran at about 0.55 MB/s here, roughly 4.3 hours, so I stopped
it after 949 MB. Its part files are in `data/addresses/download/nad_r24_TXT.zip.parts`,
and `step_download.py --only nad` resumes them. The PA rows came instead from USDOT's
hosted view of the same r24 release: 78.6 MB of gzip JSON lines in 36 minutes. That is
cheap for one state; for a whole-US build the national zip is the better path.

## 3. The address table (`data/addresses/pa-addresses.sqlite`)

Merge order (`step_build.py`):

1. **OSM points.** A value like `12;14` gives two rows. A street value that holds several
   names (`West Gay Street;Gay Street`) gives one row per name. One that holds a whole address or
   extra text (`261 Main St Blandon PA 19510`, `Walnut St, Suite`) is cut to its street by the
   parser (49 OSM values). Points with the same county, number, street key and unit within 50 m
   collapse into one (19,151 dropped). Total: 1,084,827.
2. **NAD**, then **Scranton 911.** Block placeholders such as `700blk` are dropped (875 NAD
   rows; the suffix list is in the data file). Unit-level rows of one building collapse to the base
   address. A row is a duplicate when the same county, number and street key lies within
   300 m of an existing point. Duplicates are dropped, but they fill a missing ZIP or city
   on the OSM point (4,306 ZIPs from NAD, 117 from Scranton). Added: NAD 864,392 and
   Scranton 89,410.
3. **ZIP for points without one:** the nearest TIGER edge of the same street (same county)
   within 300 m, and the side of the street by cross product (766,021 points). Otherwise
   the nearest edge of any street within 150 m (68,011). 7,272 points end up without a
   ZIP.
4. **City:** the source's city (OSM `addr:city`, NAD `Post_City`), unless it is a placeholder
   (`Not stated`) or only digits (`19428`); a trailing state (`Franklin, PA`) is dropped. Otherwise
   the most common source city among points with the same ZIP (a postal-city guess). Otherwise
   the municipality (TIGER COUSUB). Every row also gets its municipality.
5. **TIGER ranges** (1,428,442) and usable **OSM `addr:interpolation`** ways (1,551) go into
   `ranges`, and their geometry into `edges`. Interpolation happens at lookup time;
   ranges are not expanded into rows.
6. **Slim copy:** `pa-addresses-slim.sqlite` is the same file without `addresses.source_id`
   and `addresses.zip_src`, and with `meta.variant` saying so. This is the file a device ships.

Rows by table: addresses 2,038,629, streets 402,200, localities 12,843, ranges 1,429,993,
edges 797,448, places 13,722. One street spelling with a comma is left (`Spring Run Dr, E`,
from the range data, which the cleanup does not touch).

Schema (also in `packbuilder/addresses/README.md`; `meta` holds sources, attribution,
input hashes and build statistics):

```
localities(id, zip, city, city_key, city_src, municipality, muni_key, county_fips)
streets(id, street, street_key, street_core, locality_id, n_points, n_ranges, lat_e6, lon_e6)
addresses(id, street_id, hn, hn_int, unit, lat_e6, lon_e6, source, source_id, zip_src)  -- accuracy 'point'
ranges(id, street_id, edge_id, side, hn_from, hn_to, hn_lo, hn_hi, parity, source)      -- accuracy 'interpolated'
edges(id, geom)   -- little-endian int32 (lon, lat) micro-degree pairs
places(place_key, name, kind, zip, county_fips, n)
VIEW address_rows(id, hn, street, street_key, unit, city, municipality, zip, county_fips, lat, lon, source, accuracy)
```

Each address row carries the fields the task asked for: house number (canonical),
street as written, normalised street key, unit, city or postal city, ZIP, lat/lon,
source and accuracy class. The `address_rows` view shows them flat. The strings are
normalised into `streets` and `localities` because a flat table repeating them was
estimated at about 175 bytes per row (an estimate, not built). The built `addresses`
table plus its index is 82.8 bytes per row.

**For the search track:** index `streets` (street, street_key, street_core) joined to
`localities` (city, municipality, ZIP), and `places` for place names. Then call
`addrlib.lookup.AddressIndex.resolve(parsed)` to turn a house number into a position.
No R*Tree is built here; reverse geocoding would need one on `addresses`, and that index
is the search track's to build. The NAD points can be dropped from a pack on their own if
their licence is ever in doubt: `DELETE FROM addresses WHERE source = 'nad'`.

**Lookup contract** (also in the README). Every result carries `accuracy` (`point`,
`interpolated`, `street`), `match` and `locality_match`. When the typed street does not exist
in the typed town, the lookup still returns the best match elsewhere in the state with
`locality_match: false` (`1 Saint Marys St Erie` gives St Marys St in Phoenixville; Erie has
only Saint Mary Dr). The caller must show such a result as a guess. A typed state other than the
pack's (`626 State St Erie OH`) sets `state_mismatch: true` and `locality_match: false`. A house
number above `max_house_number` (lookup_config.json) is not used. Input without a street
(`Erie PA 16501`) returns nothing: place search belongs to the search track.

## 4. Normalisation and parsing

`addrlib/us_street_norm.json` holds every word list. I wrote the tables myself, from
general knowledge of US address spelling. USPS Publication 28 was not opened or copied.
I checked the tables against the data by listing the most frequent last words of street
names in PA's OSM and TIGER data that the tables did not recognise. That check added
"Extended/Exd" and "Commons/Cmns" in round 1, and "Exn" (Extension) and "Knolls" in round 2.
The tables contain:

- directionals (N to NW), and the weak ones (`no`, `so`, `nth`, `sth`) used only as a leading word;
- about 150 street types with their spellings, and a short list of "strong" types the parser uses as a boundary;
- name words (Mt/Mount, Ft/Fort, Jr, MLK, JFK), and the words after which `St` still means Saint (Mount, Fort, Old, Port);
- ordinals as words ("Twenty-First" becomes `21st`), including "21 st Street";
- highway forms: `I-79`, `I 79`, `I79` and `Interstate 79` become `i 79`. `US 19`, `US Hwy 19`
  and `U.S. Highway 19` become `us 19`. `PA 8`, `SR 8`, `SR 0008`, `State Route 8` and
  `Pennsylvania Route 8` become `sr 8`. `Route 8` stays the generic `rte 8`, and the lookup
  tries `sr`/`us`/`i`/`cr` for it. `T-312` and `Township Road 312` become `twp 312`. Business,
  bypass, alternate and truck modifiers are kept;
- unit designators (Apt, Ste, Unit, #, Fl, Rm, Bldg, Lot, Trlr, and no-number ones like Rear and Upper),
  plus `No`/`Num`/`Number`, which mean `#` only at the start of a source's unit field;
- house-number fractions (1/2, 1/4, 3/4) and block-placeholder suffixes (`blk`, `block`);
- the 50 states plus DC and PR, with ambiguous state tokens (`in`, `oh`, `pa`, ...) marked;
- country words dropped from the end of typed input (`USA`, `United States`), and city placeholders (`Not stated`);
- place words ("Township" to `twp`, "City of" dropped).

What stays in code is the order of the rules and the shapes of numbers (house number, ZIP, unit
identifier regular expressions).

Saint versus Street: an `st` followed by a name word means Saint when it starts the name,
follows a leading directional, or follows a Saint prefix word (`St Clair St`, `N St Clair St`
and `Mount St Marys Rd` give `saint ...`). Anywhere else it is the street type (`Main St Exn`
gives `main st ext`, `Church St Rear` gives `church st rear`). Before round 2 every `st` before a
name word became Saint, which mis-keyed about 60 street spellings in the pack. An `st` followed by
a street type, a directional or nothing stays Street (`Street Rd` and `St Rd` both become
`st rd`). Type words and directionals are
canonicalised wherever they appear, so `Valley Drive Extension` and `Valley Dr Ext` give
the same key. The key is a matching key and not a display form. ZIP+4 is split into
`zip` and `zip4`. The round-2 rule changed the keys of 84 of 170,448 distinct street
spellings: 37 Saint back to Street, 36 `exn` to `ext`, 11 `knolls`. One regression: `Ridgway St Marys
Rd` (a road to the town of St. Marys) is now keyed `ridgway st marys rd`. It still matches a query
typed with `St`, but no longer one typed with `Saint`.

Street keys agree well across sources: 94.6% of OSM addresses' street keys exist as a
TIGER key in the same county, counting exact matches only. The common misses are
Philadelphia streets that TIGER writes with a directional and OSM does not (`Ritner
Street` against `W Ritner St`), and upper-case NAD-style names without a type (`MAIN`).
The lookup handles both through `street_core`, the key without its directionals and type.

**Parser** (`addrlib/parser.py`, rules only). In order:
1. A trailing country (`USA`) is dropped.
2. ZIP: a ZIP or ZIP+4 is taken only where it ends a part, ends the input or follows a state,
   and never as the first token, so `12345 Main St` keeps 12345 as the house number. A lone
   `16501` is a ZIP. A 3-4 or 6-8 digit number after a final state is kept as `zip_invalid`
   and not used.
3. State, accepted in the same positions.
4. House number, including `123A`, `123-125`, `123 1/2` and `123 A Main St`. `123 A St` keeps
   the street `A St`.
5. Units, with or without a number: `apt 4`, `apt #4b`, `unit a-1`, `# 4`, `2nd floor`, and
   several in a row (`fl 3 rm 4`). A designator without a number (`rear`, `upper`) counts only
   after a strong street type, after another unit or alone between commas. It never counts when
   it starts a known place (`Lower Burrell`) or when a strong type follows (`Trail Side Court`).
6. A house number that does not lead: after a street type or a route number
   (`State St 626 Erie`, `Route 8 4500 Gibsonia`), else a final number (`main st 123`).
7. Input that is only a known place (`Erie PA 16501`) is a locality, not a street.
8. Street and city are split by a comma when there is one. Otherwise the split falls at the end
   of a unit or trailing house number, else before the longest trailing run that is a known place
   (a gazetteer the lookup passes in from `places`). Failing that, the street ends after a highway
   number or after the last "strong" street type.

The locality can also come first (`erie pa 16501, 123 main st`).

**usaddress** (version 0.5.16, MIT, checked in the package's LICENSE; it uses
python-crfsuite 0.9.12, MIT) was run on the same strings and fed into the same lookup.
On the round-2 test set it scored 99.0% top-1 against 100% for the rules. On type-word typos
it scored 94.7% against 100%, where it parsed the city right in only 74.7%. In round 1 it scored 97.3%
against 99.3%, losing mostly on cities without commas and on unusual street names
(`IRISHTOWN RD Extension`, `Avenue B`, `2034 FURNACE`). Parsing takes 0.21 ms per
address with the rules and 0.32 ms with usaddress (mean).
usaddress depends on a trained CRF model file, and porting it to Kotlin would need a CRF
runtime plus that model. The rules are about 350 lines and port directly. **Choice:
rules plus data.**

**Lookup** (`addrlib/lookup.py`, weights in `addrlib/lookup_config.json`). It works in
tiers: the exact street key plus highway alternatives, then `street_core`, then key
variants, then edit distance of 2 or less within the typed locality (for typos).
Key variants are second-chance keys from `normalize.key_variants`, tried smallest change first:
- a misspelt street-type word, wherever it is (`Srteet`, `Aveune`, `Bracnh Ave`, `Valely View Dr`);
  it is compared with the full type words only;
- a bare number read as an ordinal (`West 8 St` to `w 8th st`).

The variant tier can misfire, because 'line' and 'rock' are one edit from 'lane' and 'lock'.
It is tried only after the exact and core keys found nothing in the typed town, so the effect
is limited. Each tier is first
restricted to the typed ZIP or city or municipality. A core-key candidate may fill in a
directional or type the user left out but may not contradict one they typed (`E
Pittsburgh St` will not match `W Pittsburgh St`). Then: an address point with the same
number (`123a` falls back to the point of `123`), else a range containing the number,
interpolated with a 10 m side offset, else
the nearest numbered point on the street (accuracy `street`). Results are ranked by
locality match, match tier, accuracy and source. A street found in the typed town wins
over an exact number in another town. Only when the street is not found in the typed
town at all does the lookup fall back to the whole state, and then the result says
`locality_match: false` so the UI can show it as a guess (see the contract in section 3).
The lookup opens every variant file: it answers from points alone or from ranges alone when
the other tables are missing.

## 5. Accuracy

### Hold-out test of the fill-in sources (`step_eval_accuracy.py`, seed 20261115)

The sample is 5,000 OSM address points, one third each from urban, suburban and rural
counties, with a street name and a single house number. Each is resolved without its
coordinates, using house number, street, county, the OSM postcode if present and the
municipality (county subdivision) the point lies in. That municipality is derived from the
true position, so these rows are the optimistic case, not what a user types. (a) uses only
TIGER ranges; (b) uses only NAD points. When several candidates remain, the one in the same
ZIP and then the same municipality wins. A second, **blind** run gives the resolver only house
number, street and county. Errors are great-circle distances to the OSM point, which is
usually a building centroid. "Within" shares are taken over all sampled points; median and
p90 are over the points found. The round-2 re-run reproduced every round-1 figure exactly.

| Source | County type | n | not found | median m | p90 m | within 50 m | within 200 m |
|---|---|---:|---:|---:|---:|---:|---:|
| TIGER interpolation | all | 5000 | 3.5% | 72.5 | 228.2 | 31.6% | 84.8% |
| TIGER interpolation | urban | 1668 | 1.0% | 76.8 | 198.2 | 30.9% | 89.4% |
| TIGER interpolation | suburban | 1666 | 4.3% | 68.3 | 249.4 | 33.9% | 83.0% |
| TIGER interpolation | rural | 1666 | 5.3% | 73.0 | 254.5 | 30.1% | 82.0% |
| TIGER, 10 m side offset | all | 5000 | 3.5% | 69.9 | 224.6 | 34.1% | 85.1% |
| TIGER, 10 m offset, **blind** (county only) | all | 5000 | 3.5% | 74.4 | 293.8 | 32.3% | 81.8% |
| TIGER, 10 m offset, blind | urban | 1668 | 1.0% | 76.3 | 201.4 | 31.8% | 88.9% |
| TIGER, 10 m offset, blind | suburban | 1666 | 4.3% | 70.8 | 786.3 | 33.9% | 78.3% |
| TIGER, 10 m offset, blind | rural | 1666 | 5.3% | 74.6 | 375.5 | 31.1% | 78.3% |
| NAD points only | all counties | 5000 | 75.8% | 30.5 | 94.7 | 17.0% | 23.8% |
| NAD points only | NAD counties only (10) | 1317 | 8.1% | 30.4 | 94.6 | 64.5% | 90.1% |
| NAD points only, **blind** | NAD counties only (10) | 1317 | 8.1% | 30.9 | 98.4 | 63.9% | 89.2% |
| NAD points only | NAD counties, suburban | 318 | 15.1% | 24.5 | 88.1 | 62.3% | 81.8% |
| NAD points only | NAD counties, rural | 999 | 5.9% | 32.5 | 94.8 | 65.3% | 92.8% |
| TIGER only | NAD counties only | 1317 | 5.7% | 78.0 | 268.8 | 26.2% | 80.6% |
| NAD, else TIGER | NAD counties only | 1317 | 1.4% | 33.4 | 113.4 | 65.6% | 94.8% |
| Scranton 911 only | Lackawanna (separate sample) | 600 | 26.3% | 22.4 | 172.7 | 54.0% | 67.0% |
| TIGER only | Lackawanna (separate sample) | 600 | 7.0% | 80.5 | 314.2 | 28.3% | 75.8% |

Called-out counties, TIGER interpolation: Erie (n=151) 3.3% not found, median 65 m, p90
148 m. Allegheny (n=290) 2.4%, 61 m, 157 m. Philadelphia (n=1361) 0.5%, 80 m, 201 m.
Centre (n=974) 5.7%, 80 m, 258 m; NAD in Centre has 5.7% not found and a median of 33 m.

Without ZIP and municipality the median hardly moves, but the tail grows. Suburban p90 is 786 m
blind against 248 m, because a county often has several streets of the same name (a Main Street
in each borough) and the first match can be the wrong one. In the full lookup the typed city or
ZIP plays that role, so typing the town matters.

Why TIGER misses 177 of 5,000: in 85 the street is not in that county's TIGER file under
any matching key, in 68 the number lies outside every range, and in 24 the parity is
wrong. 173 of the 4,823 found are more than 500 m off. When several TIGER ranges match
(8.4% of the sample), the ZIP or municipality picks among them. 3.6% of NAD matches lie
within 1 m of the OSM point, so OSM rarely copied NAD positions. Scranton 911's misses
are mostly house numbers absent from the layer (139 of 158), such as shopping-centre
addresses, and not normalisation failures.

**Sample bias:** the sample follows where OSM has addresses. "Urban" is 82% Philadelphia,
and "rural" is 58% Centre County, the only rural county with many OSM addresses. Rural
TIGER quality in counties with no OSM addresses could not be measured, because there is
nothing there to measure it against.

### End-to-end: parser plus lookup on typed variants (`step_eval_parser.py`, seed 7)

The test set is 300 real addresses drawn from the built pack: 100 per county type, with a
source or postal city and a ZIP. Each gets one typed variant, 75 of each kind. Two more kinds
are reported separately: 75 variants with a typo in the street-**type** word (`Srteet`,
`Aveune`; an abbreviated type is spelt out first), drawn with their own random stream so the
headline draws do not change, and 60 clean variants as a baseline. Success means the top
result lies within 100 m of the true point.

Each variant is resolved twice:
- **In the pack.** The target point is in the file, so success means the lookup found a point
  that exists. This is a test of parsing and street matching, not of geocoding accuracy.
  usaddress was run on the same strings and fed into the same lookup.
- **Target removed.** A copy of the pack has every target deleted: all points with the same house
  number on a street with the same `street_core` in the same county (492 rows). This is what a
  user gets for an address the pack has no point for, as is the case for about two thirds of PA
  homes.

**Target in the pack:**

| Variant | n | rules: top-1 | rules: top-5 | usaddress: top-1 | usaddress: top-5 |
|---|---:|---:|---:|---:|---:|
| abbreviated / expanded words, odd case | 75 | 100.0% | 100.0% | 100.0% | 100.0% |
| missing ZIP | 75 | 100.0% | 100.0% | 100.0% | 100.0% |
| missing city | 75 | 100.0% | 100.0% | 96.0% | 96.0% |
| one typo in the street name | 75 | 100.0% | 100.0% | 100.0% | 100.0% |
| **all 300** | 300 | **100.0%** | 100.0% | 99.0% | 99.0% |
| one typo in the street-type word (separate) | 75 | 100.0% | 100.0% | 94.7% | 96.0% |
| clean baseline (separate) | 60 | 100.0% | 100.0% | 98.3% | 98.3% |

The rules parser got the house number and ZIP right in 100% of the 300 cases and the city
in 99.7%. "Street key parsed right" is 74.7%, which is expected: in the typo kinds the typed
key contains the typo, and the lookup corrects it. In round 1, before the fixes, the same
test scored 99.3% (rules) and 97.3% (usaddress) on a slightly different draw. The pack has
changed since, so the draws differ.

**Target removed (rules parser):**

| Variant | n | top-1 within 100 m | within 200 m | median m | p90 m | no answer | top result is |
|---|---:|---:|---:|---:|---:|---:|---|
| abbreviated | 75 | 57.3% | 92.0% | 87.2 | 185.4 | 0% | 68 interpolated, 7 street |
| missing ZIP | 75 | 68.0% | 90.7% | 59.8 | 198.1 | 0% | 70 interpolated, 5 street |
| missing city | 75 | 72.0% | 85.3% | 64.5 | 306.5 | 0% | 73 interpolated, 2 street |
| typo in the name | 75 | 73.3% | 89.3% | 58.0 | 198.8 | 0% | 71 interpolated, 4 street |
| **all 300** | 300 | **67.7%** | **89.3%** | 62.9 | 205.9 | 0% | 282 interpolated, 18 street |
| typo in the type word (separate) | 75 | 66.7% | 86.7% | 65.1 | 231.6 | 0% | 71 interpolated, 4 street |
| clean baseline (separate) | 60 | 68.3% | 88.3% | 74.8 | 204.1 | 0% | 56 interpolated, 4 street |

By county type, target removed: urban 66%, suburban 78%, rural 59% within 100 m. These
figures agree with the TIGER hold-out above (median about 70 m). The break reviewer measured 65.0%
on round-1 code with a similar deletion. "street" means no range held the number, and the
nearest numbered point on the street was returned instead.

**Independent cross-check.** The reproduce reviewer's generator was re-run on the final code,
with one fix: their script passed latitude and longitude to the distance function in swapped
order. It draws uniformly over all pack rows, puts its typo in the longest word (often the
type word) and uses six input styles. Results with seed 99: 300/300 in the pack, and with the
targets removed 73.0% within 100 m, 91.0% within 200 m, median 58 m. Its first run on round-2
code failed 5 of 300, all typos in a type word used inside a name (`Bracnh Ave`, `Valely View Dr`,
`Cneter Bridge`). That led to the variant rule covering every word.

**Latency** (CPython on the build PC, other heavy jobs running): parse 0.21 ms mean
(p90 0.33 ms). Lookup, target in pack: mean 43 ms, **median 2.4 ms, p90 175 ms**, max 371 ms.
Target removed: mean 46 ms, median 2.8 ms, p90 193 ms. The tail is the typo tier, which scans
the street keys of the typed town in Python; 75 of the 300 resolved through it.

**Caveat:** the variants are generated from the pack's own street names, and the
abbreviations and type-word typos are built from the same tables the normaliser uses. The
in-pack numbers measure whether parsing and lookup are self-consistent, not how well they
handle real user queries. A test set of queries people actually typed is still needed.

Spot checks in the test towns, run by `step_variants.py` on the round-2 full and slim files:
626 State St Erie, 414 Grant St Pittsburgh, 501 N 3rd St Harrisburg, 1400 JFK Blvd
Philadelphia (after a `jfk` alias was added), 243 S Allen St State College and 340 N
Washington Ave Scranton each resolve to an OSM point. `150 Main St Brookville` (Jefferson
County, 1% OSM coverage) is interpolated from TIGER. `1200 E Pittsburgh St Greensburg` has no
such number on that street; it returns the nearest number on East Pittsburgh Street in
Greensburg (accuracy `street`) rather than an exact 1200 in another town.

## 6. Sizes, time and memory

Downloads (measured):

| Item | Size | Time |
|---|---:|---:|
| TIGER 2026 ADDRFEAT, 67 PA county zips | 123.6 MB | ~6 min together with the files below (it shared the line with a stopped NAD bulk download) |
| TIGER 2026 COUSUB PA, Census CB 2025 counties, PEP housing tables | 11.3 + 11.8 + 0.03 MB | (same run) |
| NAD r24 PA rows via the hosted view | 78.6 MB gzip JSONL | 36 min (service-bound, 2 connections) |
| Scranton 911 layer | 22.5 MB JSONL | 75 s |
| OSM PA extract (shared input, not downloaded by this track) | 350.4 MB | n/a |
| NAD r24 national TXT.zip (not completed) | 8.48 GB | about 4.3 h at the 0.55 MB/s seen (estimate) |

Build steps. Peak memory is psutil's peak working set of each step process (Windows
PeakWorkingSetSize). Other heavy jobs were running at the same time. osm, nad and extra
were not re-run in round 2 (their inputs and code did not change); the rest are round-2
figures, with round 1 in brackets.

| Step | Wall s | CPU s | Peak MB | Output |
|---|---:|---:|---:|---|
| osm (3 pyosmium passes, 4 reader threads) | 162 | 175 | 2,278 | 1,111,997 objects, `work/osm_pa.sqlite` 130 MB |
| tiger | 84 (172) | 82 | 687 | 1,428,442 ranges, 796,084 edges, 229 MB |
| nad | 2,168 | 590 | 686 | 1,074,485 rows, 331 MB |
| extra (Scranton) | 75 | 3 | 40 | 97,499 rows |
| coverage | 28 (94) | 28 | 919 | reports |
| **build** (includes the slim copy) | **166** (243) | 163 | **5,729** | **pa-addresses.sqlite 353.3 MB, pa-addresses-slim.sqlite 287.7 MB** |
| eval_accuracy | 52 (74) | 52 | 1,630 | |
| eval_parser (now two runs plus a 353 MB pack copy) | 69 (55) | | 1,279 | |
| estimate (gzip and xz of both files) | 317 (225) | 314 | 156 | |
| variants | 63 (97) | 62 | 32 | |

Round-2 wall times are shorter because the machine was less loaded, not because the
code got faster. The build's peak, 5.7 GB, is unchanged and close to the 6 GB budget. Most of
it is 2 million Python dicts plus 800 k shapely line strings used for the ZIP fill. If more
states push it over, the next step is to process one county at a time.

Output by table (each table copied with its indexes into its own file, then VACUUMed):
addresses 168.8 MB, ranges 75.9 MB, edges 65.3 MB, streets 41.3 MB, localities 1.0 MB,
places 1.0 MB.

| Pack variant (`step_variants.py`) | SQLite MB | gzip -6 MB | Test addresses answered from the file |
|---|---:|---:|---|
| full (`pa-addresses.sqlite`) | 353.3 | 158.0 (xz -6: 103.3) | 6 test-town addresses as OSM points, Brookville interpolated, Greensburg 1200 as `street` |
| **slim (`pa-addresses-slim.sqlite`, the device file)**: full without `addresses.source_id` (NAD UUIDs) and `zip_src` | **287.7** | **132.2 (xz -6: 83.1)** | same as full |
| points only (slim without ranges/edges) | 117.0 | 52.2 | the 6 points; Brookville only as `street` (nearest numbered point); nothing better in most rural counties |
| ranges only (slim without points) | 172.3 | 80.7 | all 7 interpolated from TIGER (about 70 m median, see section 5) |

All four files were opened with the shipped `AddressIndex` and asked the same eight addresses
(`reports/variants.json`). In round 1 the lookup could not open the points-only or ranges-only
file.

**Whole-US estimate.** These are estimates, not measurements.
- TIGER 2026 ADDRFEAT, all 3,227 county files: **about 2.79 GB** zipped, from the sizes in the Census
  directory listing (rounded by the server; the PA files there sum to 123.4 MB against
  123.6 MB measured). That is 22.6 times PA.
- Address database for all states: **about 8.9 GB (low) to 16.5 GB (high) uncompressed**,
  for the full variant. Slim would be about 18% less.
  - Low: PA's 353 MB times US/PA housing units (148,260,882 / 5,882,221 = 25.2). This
    assumes the PA mix of points and ranges everywhere.
  - High: PA's TIGER and street tables times 22.6, plus one point per US housing unit
    at PA's 82.8 bytes per point. NAD is much denser in other states (all of Ohio, for
    example, is in it), so many states will hold more points than PA does.
- A device would download only its states: on PA's numbers, about 100 to 160 MB
  compressed per state of PA's size.

## Recommendation for the PA pack

Ship by default the **slim** file, `data/addresses/pa-addresses-slim.sqlite`, which the build
now writes: 288 MB on disk, 132 MB gzip, 83 MB xz (all measured). That is
OSM points, NAD points and Scranton 911 points, with TIGER ranges and OSM interpolation
as the fallback. Reasons:
- Without TIGER ranges, 47 of 67 counties have points for fewer than 1 in 10 homes,
  even with NAD and Scranton added. A points-only pack (117 MB) would fail most rural
  and suburban searches, and "address not found" is the worst outcome for a driving app.
- Without points, every address is about 70 m off in the median and 225 m at p90.
  That is enough to stop on the wrong side of a block or at the wrong driveway. The
  points cost about 115 MB and make 2 million addresses accurate to roughly a
  building. End to end, an address with a point resolves to within 100 m of the true point
  practically always, and one without a point about two times in three (section 5).
- The NAD rows (0.86 M) can be dropped from the file on their own (`source = 'nad'`) if the
  licence question below is answered the wrong way.
- Cheap next savings, not built here. Edges average 8.6 vertices, and almost all
  coordinate steps between vertices fit in 2 bytes as zigzag varints. Delta-encoding them
  should shrink `edges` from 65 MB to about 36 MB (estimate). Dropping TIGER ranges on
  street segments whose numbers are already covered by points would save more; that
  saving was not measured.

To add later, in order of value:
1. Ask Erie County (120 k points) and Allegheny County (WPRDC/PASDA: no licence, and an
   indemnity clause) for an explicit open licence. Each would add about 100 k or more
   accurate points in a test area.
2. Ask USDOT (NAD@dot.gov) to confirm in writing that the NAD rows for the PA counties may be
   redistributed in a pack published under ODbL.
3. Use the national NAD zip in a whole-US build, since one download serves all states.
4. Build a real-query test set.

## Did not work, dead ends, not verified

- The national NAD download was too slow here (0.55 MB/s, about 4.3 h estimated) and was stopped.
  The PA rows came from USDOT's hosted view instead. That view times out on `State='PA'`
  queries over the whole state, so the step asks county by county within each county's
  bounding box. Each county's row count matches the service's own statistics exactly.
- USDOT's NAD disclaimer page returned HTTP 403 to automated fetches, in round 1 and again in
  round 2. The licence above comes from USDOT's catalog records, which disagree with each other
  (see Licences), not from that page.
- The Census Data API returned empty responses (keyless), so housing units come from the
  published PEP spreadsheet instead.
- I did not check every PASDA county layer's metadata. Round 2 read Allegheny's in full: no
  licence. PEMA's NG911 pages say the counties provision their layers to a statewide system,
  but no public download of it was found. So "no open statewide file" still rests on catalog
  and web searches.
- County types are by density, which is crude. Erie is "suburban" and Centre "rural".
- The parser has no multi-line or PO Box handling. PO boxes are not routable, and "box"
  is parsed only as a unit designator. A leading minus (`-5 State St`) is dropped and the
  number used as 5.
- The lookup's typo search scans the typed town's street keys in Python: median lookup
  2.4 ms, but p90 175 ms and up to 0.4 s for big towns. It needs an index (trigram or FTS5
  spellfix) once it runs on a phone; the search track may already provide one.
- The cross-check and the type-word typos still come from the pack's own names and tables; no
  real user queries were available.
- Ohio was not processed. NAD covers all of Ohio, so the Ohio pack should reach far
  higher point coverage than PA.
- Overture addresses were not evaluated, as instructed.
- Other projects' code: none read. I read only OpenAddresses source definition JSON files
  (data) and pyosmium's installed source, to check how its thread pool and filters behave.

## Licences

Data in the pack (the pack itself would be published under ODbL 1.0 with this attribution
in `meta.attribution`):

| Data | Licence | Checked at |
|---|---|---|
| OpenStreetMap (Geofabrik PA extract 2026-11-14) | ODbL 1.0 | openstreetmap.org/copyright (project-wide) |
| TIGER/Line 2026 ADDRFEAT, COUSUB; Census CB 2025 counties | not copyrighted; credit "U.S. Census Bureau" | ISO metadata `useConstraints` inside the ADDRFEAT zips |
| Census PEP Vintage 2025 housing units | US Government work (17 U.S.C. 105) | table footer, census.gov |
| USDOT NAD r24 | **public domain per USDOT's catalog labels; USDOT's terms page not read.** The records disagree: the harvested DCAT record has `license: http://www.usa.gov/publicdomain/label/1.0/`; an older NAD record on data.gov names CC0 1.0; the current data.gov "NAD Text File" page (metadata modified 2026-09-30) has no licence field, only access level and rights "public"; the hosted view's metadata has no licence field. The USDOT disclaimer page returns HTTP 403 to automated fetches (tried again 2026-11-15). A web search engine's summary of that page says seven areas may hold data that is not public domain (HI, MI, MN, NH, OR, SC, SD); Pennsylvania is not among them, but that is a secondary source. NAD is compiled from county data (here Montgomery, Westmoreland, Cumberland, Washington, Centre, Blair, Fayette, Indiana, Lawrence, Tioga), whose own terms were not checked | data.gov records, search summary of the USDOT page |
| City of Scranton 911 address points | PDDL 1.0 / "GIS is a public domain" | ArcGIS item licenseInfo of the service |

Software. Nothing GPL, AGPL or LGPL ships to a device. What ships is `addrlib/` (project
code, Python standard library only), its two JSON data files and the SQLite file. The
build-only items:

| Package | Version | Licence | Ships? | Checked at |
|---|---|---|---|---|
| osmium (pyosmium) | 4.3.2 | BSD-2-Clause (bundles libosmium, BSL-1.0 per upstream, not checked in the wheel) | no | wheel dist-info LICENSE.TXT |
| shapely | 2.2.0 | BSD-3-Clause; **the Windows wheel bundles GEOS, LGPL-2.1** | no | dist-info LICENSE.txt, LICENSE_GEOS |
| numpy | 2.5.4 | BSD-3-Clause (plus 0BSD/MIT/Zlib/CC0 parts) | no | dist-info |
| pyshp | 3.1.8 | MIT | no | dist-info |
| openpyxl / et_xmlfile | 3.1.5 / 2.0.0 | MIT | no | dist-info |
| psutil | 7.2.2 | BSD-3-Clause | no | dist-info |
| usaddress / python-crfsuite / probableparsing | 0.5.16 / 0.9.12 / 0.0.1 | MIT | no (evaluation only) | dist-info LICENSE |
| requests, urllib3, idna, charset-normalizer, certifi (pulled in by osmium) | 2.34.2, 2.8.0, 3.20, 3.5.2, 2026.10.23 | Apache-2.0, MIT, BSD-3, MIT, MPL-2.0 | no | dist-info |
| SQLite (via Python's sqlite3) | bundled with Python 3.12.10 | public domain | yes (on the device it is the platform's SQLite) | sqlite.org |

## Notes for other tracks and the orchestrator

- **Search:** join `streets` and `localities` from `pa-addresses.sqlite`, and use
  `addrlib.parser.parse` with `addrlib.lookup.AddressIndex.resolve` for house numbers.
  `places` maps city names and municipalities to ZIPs. Street keys come from
  `addrlib.normalize.street_key` and must be computed the same way on the device.
  The file was rebuilt in round 2 (15:00 on 2026-11-15): 84 street spellings have new keys
  (the Saint rule, `Exn`, `Knolls`), and the normalisation table version is now 2. The schema
  and the `address_rows` view are unchanged. Read the lookup contract in section 3, above all
  `locality_match`.
- **Packaging:** the device file is `pa-addresses-slim.sqlite`, 288 MB (83 MB xz) per
  PA-sized state, the largest of the three pack parts so far. Decide whether it is a
  separate optional download.
- Files outside git that this track left: `data/addresses/download/nad_r24_TXT.zip.parts`
  (949 MB, the stopped national NAD download; safe to delete),
  `data/addresses/work/variant_{points,ranges}_only.sqlite` (289 MB of size-test copies) and
  `data/addresses/work/pa-addresses-nonad.sqlite` (257 MB, a round-1 build without NAD made
  with the old normalisation; stale, safe to delete). Round-1 reports are kept for comparison
  in `data/addresses/logs/*_round1.json`.
- No changes are needed outside `packbuilder/addresses/` and this report.

## Review and fixes

Two independent reviews of round 1: "reproduce", which re-ran the work, and "break", which
attacked it. Both returned "sound with fixes", with 2 major and 15 minor findings and no blockers.
I re-ran each failing case myself on round-1 code before changing anything (all reproduced), then
fixed every finding, re-ran the pipeline and tests, and re-ran the reviewers' own probe and fuzz
scripts on the result. Tests: 13 unit tests in round 1, 22 now (`python -m unittest discover
-s packbuilder/addresses/tests`), all passing. The break reviewer's 3,000-string fuzz run gives 0
exceptions now, against 25 OverflowErrors on round 1, with no query over 1.5 s.

| # | Finding (lens) | Severity | Outcome |
|---|---|---|---|
| B1 | The headline 99.3% only measures finding a point that is already in the pack; with the target removed it is about 65% (break) | major | **Fixed.** Confirmed: in round 1, 299 of 300 top results were the target point itself. `step_eval_parser.py` now also runs every variant against a copy of the pack with the targets removed (492 rows deleted): 67.7% within 100 m, 89.3% within 200 m, median 63 m. The summary and section 5 now give both numbers and say what each measures. |
| B2 | `Apt #4B` left `apt` in the street and found nothing; `Unit A-1`, `2nd Floor`, `Rear` mis-split (break) | major | **Fixed.** Confirmed on round-1 code (`500 Walnut St Apt #4B Erie PA` returned nothing). The parser now reads `<designator> #<id>`, keeps hyphenated ids (`a-1`), reads `2nd floor` as `fl 2`, accepts several designators in a row, and accepts no-id designators after a strong type or alone between commas. All five forms now resolve to 500 Walnut St, Erie. A new guard I added keeps `Trail Side Court` and `Lower Burrell` from being read as units; the first version of the fix broke `Trail Side Court`, and the e2e run caught it. |
| R1, B6 | A typo in the street-type word (`Srteet`, `Aveune`, `Cicrle`) returns nothing (reproduce, break) | minor | **Fixed.** `normalize.key_variants` corrects a type word one edit from a full type word, tried smallest change first; the lookup uses them as a tier after the exact and core keys. A new `typo_type` kind (75 variants) is in the e2e test: rules 100%, usaddress 94.7%. The report now says the name-typo kind excludes type words. The cross-check showed the same problem for type words used inside names (`Bracnh Ave`); the variants cover every word now. The variant rule misfires on 'line' and 'rock', which are one edit from 'lane' and 'lock'; this is noted in section 4. |
| R2 | A trailing house number followed by a city (`State St 626 Erie`) went into the street (reproduce) | minor | **Fixed.** A number right after a street type or route number is the house number when none leads; the rest is the city. Unit test added. |
| R3 | Several units in one input; a city-only input becomes a street; another town as top-1 (reproduce) | minor | **Fixed / documented.** `Fl 3 Rm 4` gives unit `fl 3 rm 4`. `Erie PA 16501` and `16501` now parse as a locality and return no address. The lookup's fallback to another town is intended and stays: the result is flagged `locality_match: false`, and the README and section 3 now state that callers must check that flag (`1 Saint Marys St Erie`: Erie has only Saint Mary Dr). |
| R4 | Timing keys named `_s` held milliseconds; mean only (reproduce) | minor | **Fixed.** `e2e.json` now has `latency_ms_per_query` with mean, median, p90 and max per parser and run. |
| R5 | The recommended slim pack was not a build output; the lookup could not open the points-only or ranges-only variants (reproduce) | minor | **Fixed.** `step_build.py` writes `pa-addresses-slim.sqlite` (`--no-slim` skips it), and `meta.variant` names each file. `AddressIndex` checks which tables exist. `step_variants.py` opens all four files with the lookup and records its answers to eight test addresses. |
| R6 | OpenAddresses count was 64 in the report, 66 in the repository (reproduce) | minor | **Fixed.** 66, checked again through the GitHub API on 2026-11-15; the licence grouping was already over 66. The report now explains the exclusion of the 19 attribution-flag sources: the flag is OpenAddresses' own `presumed` note, not a publisher licence. |
| R7, B9 | The NAD licence rests on USDOT's metadata labels, which disagree, and not on the upstream county terms (reproduce, break) | minor | **Documented, left open.** I re-checked: the data.gov "NAD Text File" record (modified 2026-09-30) has no licence field; the older records say public domain and CC0; the disclaimer page still returns 403. A search engine's summary of that page lists seven non-public-domain areas, and Pennsylvania is not among them (secondary source). The licence table now says all this. NAD rows stay separable by `source`. Asking NAD@dot.gov is in the decisions list. |
| B3 | The Saint-vs-Street rule corrupted keys when `St` was followed by an unknown word (`Main St Exn` gave `main saint exn`) (break) | minor | **Fixed.** Saint now applies only at the start (after an optional directional) or after a prefix word in the data file. `exn` and `knolls` were added after re-listing the unrecognised last words. 84 spellings changed key (37 Saint back to St). The 25 remaining non-initial `St` that become Saint all follow a leading directional (`N St Clair St`). One regression is listed in section 4 (`Ridgway St Marys Rd`). |
| B4 | OverflowError on house numbers above 2^63 (break) | minor | **Fixed.** Numbers above `max_house_number` (99,999,999, in lookup_config.json) are not used; the street is returned with `house_number_rejected`. Fuzz: 0 exceptions. |
| B5 | Typed state ignored; `626 State St Erie OH` returned the PA point as a locality match (break) | minor | **Fixed.** The lookup reads `meta.state`; a different typed state sets `state_mismatch: true` and `locality_match: false`. `-5 State St` (leading minus dropped) is left as is: harmless tolerance. |
| B6 | Trailing `USA`, `2nd Floor`, `West 8 St`, `123 A Main St` mishandled (break) | minor | **Fixed.** Country words come from the data file. `2nd Floor` is handled under B2. `w 8 st` gets an ordinal variant and finds W 8th St in Erie. `123 A Main St` gives house number `123a`, falling back to the point of 123. Unit tests added for each. |
| B7 | The accuracy hold-out gave the resolver the true ZIP and the municipality derived from the true position (break) | minor | **Fixed.** A blind run (county only) is now reported next to it: TIGER median 74.4 m and p90 293.8 m blind, against 69.9 m and 224.6 m. This reproduces the reviewer's figures exactly. The method text now says the municipality comes from the true position. |
| B8 | Dirty street names (`A;B`, whole addresses) and NAD block placeholders (`700blk`) in the pack; digit-only cities (break) | minor | **Fixed.** The build splits `;` lists into one row per name and cuts whole-address values to their street with the parser. It drops 875 `blk` rows and cleans cities through `normalize.source_city`. Streets with `;` or `,` went from 43 to 1 (from range data, not cleaned); `blk` points from 875 to 0; digit-only cities from 3 to 0. |
| B10 | Normalisation rules hard-coded in Python (break) | minor | **Fixed.** Weak directionals, Saint prefix words, fraction tokens, unit-number words, block suffixes, city placeholders and country words now live in `us_street_norm.json` (version 2). What remains in code is the rule order and the number-shape regular expressions, which are grammar, not word lists. |

Disputed: none. Every finding reproduced on round-1 code.

New measurements after the fixes are in sections 3 to 6. Unchanged:
- coverage (the summary JSON is identical to round 1);
- the TIGER, NAD and Scranton hold-out figures (identical);
- the whole-US estimate.

Changed:
- address rows: 2,039,443 to 2,038,629;
- full file 353.4 to 353.3 MB; slim 287.8 to 287.7 MB, with its xz size now measured at 83.1 MB
  (estimated 87 MB in round 1);
- end-to-end: 99.3% in pack to 100% in pack, and now also 67.7% with the target removed.

Still open from the original assignment:
- a test set of real typed queries;
- the licence of every PASDA county layer (only Allegheny read);
- written confirmation of NAD's licence;
- Ohio, which was not part of this track's brief beyond the border tests the other tracks run.
