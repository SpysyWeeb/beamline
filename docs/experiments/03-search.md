# Experiment 03: offline search index and query engine (Pennsylvania)

Date: 2026-11-15. Track: search. Code: `packbuilder/search/` (its README has the
schema and every command). Outputs: `data/search/` (not in git). First build by
one engineer, then two independent reviews ("reproduce" and "break"), then a
second engineer checked and fixed the findings; see "Review and fixes" at the
end. All numbers below are from the fixed build unless marked "before".

## Result in one paragraph

A one-command builder turns the Geofabrik Pennsylvania extract (2026-11-14) into
a 147.5 MiB read-only SQLite file with 456,470 searchable entries (14,803 places
including 67 counties, 2,556 municipalities and 1,237 ZIP codes; 305,507 street
entries; 136,160 POIs) in about 4.4 minutes on a lightly loaded PC (6-10 minutes
under heavy load), using only FTS5 and R*Tree: no loadable extension, no custom
tokenizer. A stdlib-only Python query engine handles categories, brands,
places, streets, house numbers (joined to the addresses track's database),
search-as-you-type from one character, typos, "near X" / "in X" / "on
<street>", trailing places and trailing state names ("erie pa"). Tests: the
345-case set the first engineer tuned on, top-1 97 % / top-5 99 % (2 data
gaps); 99 held-out cases, 95 % / 97 % (1 gap); 49 cases written from the review
findings before any fix, 53 % / 63 % before and 98 % / 100 % after. Warm median
query time is about 11 ms and the 95th percentile about 42 ms over the 444
dev and held-out queries; every 1- and 2-letter prefix stays under 36 ms and
the 100 most common 3-letter prefixes under 50 ms (before the fixes: up to 800
and 217 ms). These were measured on a PC shared with other heavy builds (CPU
45-50 % busy at the start of the runs). Linux python3 3.12.3 with SQLite 3.45.1
gives identical per-case results, and the engine's SQL runs on the SQLite
3.50.1 CLI (the version androidx sqlite-bundled 2.7.x compiles, inferred).

## How to rerun everything

From the repo root on Windows (paths are relative to the repo):

```
python -m venv .venv/search
.venv/search/Scripts/python -m pip install -r packbuilder/search/requirements.txt
python tools/fetch_sqlite_tools.py                                   # SQLite 3.50.1 CLI + analyzer (SHA-256 pinned)
.venv/search/Scripts/python packbuilder/search/seed_categories.py    # optional: regenerates categories_idterms.json (offline after the first run)
.venv/search/Scripts/python -I packbuilder/search/build.py --state pa            # check, extract, index (skips finished steps; --force to redo all, --force index for one)
python packbuilder/search/tests/run_tests.py --json data/search/test-results.json
python packbuilder/search/tests/run_tests.py --cases cases_holdout.json
python packbuilder/search/tests/run_tests.py --cases cases_review.json
python packbuilder/search/tests/check_inputs.py                       # bad positions, odd paths, hostile strings
.venv/search/Scripts/python packbuilder/search/measure.py --state pa             # sizes, FTS variants, SQLite 3.50.1 check, prefix + house + test-set latency
.venv/search/Scripts/python packbuilder/search/fuzzy_eval.py export --db data/search/pa-search.sqlite --out data/search/fuzzy
.venv/search/Scripts/python packbuilder/search/fuzzy_eval.py run --dir data/search/fuzzy --work data/search/fuzzy/work-win --out data/search/fuzzy/results-windows.json
wsl -d Ubuntu-24.04 -u <wsl-user> -- bash /mnt/c/<repo>/packbuilder/search/wsl_spellfix.sh /mnt/c/<repo>      # spellfix1 + same benchmark on Linux
wsl -d Ubuntu-24.04 -u <wsl-user> -- bash /mnt/c/<repo>/packbuilder/search/wsl_linux_check.sh /mnt/c/<repo>   # all test sets + input checks with Linux python3
.venv/search/Scripts/python packbuilder/search/brand_eval.py --state pa          # aliases, NSI comparison, chain coverage
python packbuilder/search/cli.py "walmart erie pa"                               # try a query (--at erie / --near lat,lon)
```

The input must match its `.md5` (`build.py` checks it). The index step imports
the addresses track's `street_key()` to fill the house-number join key, so
`packbuilder/addresses/addrlib/` must be present (`--no-addr-key` skips it).
The engine (and the test runner) refuse to attach `data/addresses/pa-addresses.sqlite`
when its normaliser table version differs from the one the search index was
keyed with; then rebuild the older side, or pass `--allow-addr-version-mismatch`
(the runner prints a warning) or `--no-addresses`. To compare against the
first engineer's build, `measure.py --db <file> --out <file>` measures any
database file. WSL work stays in `~/beamline/search-fuzzy` and
`~/beamline/search-linux`; no apt packages were installed.

## Inputs and tool versions

| Item | Version / identity |
|---|---|
| OSM data | `data/osm/pennsylvania-261114.osm.pbf`, 334.2 MB, MD5 a08fd76d6f9a7e25a887ad070e540d4b, replication timestamp 2026-11-14T21:20:41Z |
| Python (build + engine) | 3.12.10 Windows, venv `.venv/search` |
| SQLite used to build and test | 3.49.1 (Python's bundled DLL; FTS5, RTREE, MATH_FUNCTIONS; no DBSTAT, no load_extension used) |
| Cross-checks | SQLite 3.50.1 official Windows CLI; Ubuntu 24.04 python3 3.12.3 with system SQLite 3.45.1 (ENABLE_FTS5, ENABLE_RTREE, ENABLE_LOAD_EXTENSION, ENABLE_MATH_FUNCTIONS) |
| OSM reader | pyosmium (`osmium`) 4.3.2, libosmium 2.23.1 |
| Geometry | shapely 2.2.0 (GEOS in the wheel), numpy 2.5.4 |
| iD tagging schema | @openstreetmap/id-tagging-schema 7.3.0, `dist/translations/en.json` SHA-256 f5a5dad8...f583c47 |
| Name Suggestion Index (evaluation only) | name-suggestion-index 8.0.20261101, `dist/json/nsi.min.json` SHA-256 618c91e4...95ae3c |
| Addresses join key | `packbuilder/addresses/addrlib/normalize.py` `street_key()`, table version 2; file hashes stored in the database (`meta.addr_key`) |

## What the builder does

1. `check`: MD5 of the PBF against the `.md5` file.
2. `extract` (pyosmium, 4 reader threads, node locations in memory): one pass
   over relations (administrative relations and their member ways), then one
   pass over everything with area assembly. Keeps admin boundaries, place
   nodes/areas, named or ref'd drivable ways (motorway ... residential, service,
   track, pedestrian), POIs (anything matching a category rule, carrying a
   brand, or a named amenity/shop/tourism/leisure/office/craft/healthcare/
   historic/aeroway/station), and 1.13 million address points as compact arrays
   (only for ZIP places and postal-city context; house numbers are the
   addresses track's job). Category rules are applied here, so a change to
   `categories.json` needs the extract step again.
3. `index`:
   * Boundaries: libosmium could not assemble the Pennsylvania relation nor
     Delaware County (a few member ways lie outside the extract). The builder
     rebuilds such relations from their member ways and closes gaps of up to
     0.15 degrees with straight segments when at most 5 % of the ways are
     missing; this recovered Pennsylvania (3/447 ways missing) and Delaware
     County (3/73), so all 67 counties are present. Approximate, and flagged in
     `meta.boundaries_rebuilt`.
   * Points are located in municipalities and counties with a grid-binned,
     prepared-polygon point-in-polygon pass (7.1 million road vertices, 1.1
     million address points).
   * Places: municipalities (admin_level 8) merged with a same-named place
     node inside them (1,042 merges), counties, remaining place nodes, and ZIP
     codes made from address points (median point, 2nd-98th percentile box,
     ZIPs with at least 3 addresses).
   * Streets: ways cut at municipal boundaries, grouped by (name key,
     municipality), then split into clusters whose boxes are more than ~600 m
     apart. So "Main Street" is one entry per town only when its pieces lie
     together: 3,030 (name, municipality) groups have more than one entry
     (6,308 entries), e.g. a township with two separate Main Streets. Each
     entry has a bounding box, a representative point on the road, and
     `street.addr_key`, the addresses track's key of its name (the house-number
     join key). Streets with a `US n` / `PA n` ref also get the derived keys
     `route n` (and `state route n` for PA routes).
   * POIs: categories from `categories.json`, brand from brand:wikidata (or the
     brand tag joined to the wikidata seen most with it), node/area duplicates
     merged (same name and category within ~150 m, keeping the union of
     categories), centroid for areas. A POI in no category stores the
     `key=value` of its main OSM tag as its type (shown as e.g. "Hairdresser",
     "Tax advisor office"). Subtitles use the first value of OSM `;` lists.
   * Context: every entry keeps its municipality and county, plus candidate
     places (populated places within 6 km, postal cities from addr:city,
     ZIPs from addr:postcode) with weights. Nothing hard-assigns a street to
     one city; ranking uses the candidates.
   * Search structures: FTS5 name index, R*Tree, category words, brand aliases
     (including the short form of possessive brand names: "Dick's Sporting
     Goods" -> "dicks"), typo tables, `poi_prefix` (named POIs by 0.1-degree
     grid cell and the first 3 characters of their key, for search as you
     type), a partial index on place keys. `ANALYZE` and `VACUUM` at the end.
   * `meta` records the state bounding box, the licence line, the full ISC
     notice for the iD words, and the addresses normaliser version and hashes.

**Access-point problem (not solved):** a POI mapped as an area gets its
centroid as "routable point". For large areas (state parks, game lands,
malls, airports, hospitals) the centroid can be far from any road or entrance,
so routing to it will end at an arbitrary nearest road. A later step should
snap to an `entrance=*` node, a named access road, or the nearest drivable
road edge reachable from outside, and store that as a second point.

## Database contents

Row counts (final database): entry 456,470 (places 14,803, streets 305,507,
POIs 136,160, of which 38,289 named POIs fall in no category and are found by
name only; 36,073 of those carry an OSM type label, 2,216 none); poi_cat
129,733; context 1,027,747; brand 1,633; brand_alias 3,021; category 100;
category_term 982; vocab 74,144; spell_del 585,191; vocab_tri 42,416 words;
poi_prefix 131,015; streets with an addresses join key 305,507 (all). Place
types: borough 951, city 57, town 41, township 1,546, village 614, hamlet
7,415, neighbourhood 2,348, locality 421, suburb 44, quarter 17, island 45,
county 67, ZIP 1,237 (place counts unchanged from the first build). Every
place except counties and ZIPs has a county.

Size per table including its indexes (DBSTAT via the official sqlite3.exe
3.50.1), file total 154,705,920 bytes (147.5 MiB; the first build was 141.0
MiB):

| Object | MiB | Note |
|---|---:|---|
| entry | 53.2 | names, keys, alt keys, precomputed subtitle text |
| entry_rtree | 23.3 | one box per entry |
| context | 20.4 | 1.03 M candidate rows |
| poi | 11.2 | tags for display |
| entry_name_key (index) | 10.4 | exact / full-prefix lookups |
| street | 8.3 | was 5.0; +3.3 for `addr_key` |
| spell_del | 7.1 | hashed distance-1 deletions |
| entry_fts | 5.1 | FTS5, contentless, detail=none, prefix 2 and 3 |
| poi_prefix | 2.0 | new: search as you type |
| vocab_tri | 1.6 | trigram fallback for typos |
| vocab + its unique index | 2.5 | |
| poi_cat | 1.3 | |
| entry_place_key (partial index) | 0.3 | new: place prefixes by importance |
| others (place, brand, aliases, categories, meta, entry_brand) | 0.9 | |

The text in `entry` (subtitles repeat municipality and county names) and the
context table are the obvious places to shrink: subtitles could be built at
query time from `muni_id`/`county_id`, and context could keep only postal-city
and ZIP rows.

FTS5 option comparison, same text (`measure.py --only fts`, re-measured on the
final build; count-query times in ms for `st*`, `main AND street*`,
`walmart*`, `pit*`, `peach AND (street OR st*)`, single runs, noisy):

| FTS5 options | MiB | query ms |
|---|---:|---|
| content='', detail=none, prefix='2 3' (shipped) | 5.1 | 4.3 / 8.5 / 0.2 / 0.2 / 1.2 |
| content='', detail=none, no prefix index | 2.3 | 16.4 / 8.6 / 0.3 / 0.4 / 13.7 |
| content='', detail=column, prefix='2 3' | 11.7 | 4.3 / 4.1 / 0.2 / 0.2 / 0.9 |
| content='', detail=full, prefix='2 3' | 12.0 | 6.8 / 6.4 / 0.3 / 0.2 / 1.6 |
| normal content table, detail=full, no prefix | 20.4 | 10.7 / 3.6 / 0.2 / 0.2 / 8.1 |

The engine never needs phrase queries, so `detail=none` is enough; the prefix
indexes cost 2.8 MiB and make short prefixes several times faster.

## Categories

`categories.json` has 100 categories with OSM tag rules (equality, value lists,
"any value", regex, negation) and everyday US words; 23 driver-critical ones
(gas, diesel, EV charging, truck stop, rest area, service plaza, welcome
center, restroom, ATM, RV dump, viewpoint, parking for trucks / park and ride,
hospital / ER, police, fire, post office, trailhead, boat launch, picnic area,
propane, car wash) also admit unnamed POIs. Pennsylvania-specific: state store
(Fine Wine & Good Spirits), beer distributor, PennDOT driver license center,
vehicle inspection, state game lands, welcome center, municipal building
("borough building", "township building"), plus words such as hoagie,
cheesesteak, "fire company". 463 extra words come from the iD tagging schema
presets (ISC; e.g. "rest stop" on `highway/rest_area`), generated into
`categories_idterms.json` with the attribution and the full ISC notice (also in
`THIRD_PARTY_NOTICES.md` and in the database `meta`); a noise list keeps
generic iD words ("station", "store", "dine") out. A word can point to several
categories with weights (own words 1.0, iD words 0.6, or an explicit weight
such as "rest stop" 0.8 for service plazas). Commercial truck stops mapped as
`highway=services` (Love's, Pilot, Flying J, TA, Petro, ONE9, or `hgv=yes`)
are now truck stops only, not service plazas (service plazas: 83 -> 29 POIs),
so "rest stop" finds rest areas and turnpike plazas. Organic Maps'
categories.txt was not opened (the structure here is simply categories with
tag rules and word lists), so nothing from it was used.

## Brands

* **Alias table from the data:** for each brand:wikidata, every normalised
  brand tag, plus name spellings that contain the brand name or are used by at
  least 30 % of its POIs, plus a no-space form ("homedepot"), plus the first
  word of a possessive multi-word brand name ("dicks", "bjs", "ollies",
  "ritas", "sams"). POIs with only a brand tag join the wikidata most often
  seen with that brand. Spellings seen for the test chains include "Walmart
  Supercenter" / "Walmart Garden Center", "Dunkin' Donuts" (67 of 517), "WetGo"
  (5 GetGo POIs), "Sunoco A-Plus", "Love's Travel Stop", "Flying J Travel
  Center".
* **Name Suggestion Index (evaluated, not shipped):** NSI 8.0.20261101 knows
  1,338 of the 1,410 brands with a wikidata ID in the PA data and would add
  1,565 alias keys (1,581 before the possessive aliases). Useful ones: "cvs"
  for CVS Pharmacy (OSM uses only "CVS Pharmacy"), "walgreen", "dunkins",
  "supertarget", "lowes home improvement", "whole foods", "fnb", "td",
  "citizens"; noise: foreign variants ("mcdonalds france", "dominos
  australia"), single letters ("m", "c"). Its repository and npm package state
  BSD-3-Clause and nothing separate for the data, so per the project rule it is
  not shipped. A hand-reviewed alias list of a few dozen chains would capture
  most of the value ("cvs", "bk", "7-11").
* **Chain coverage in PA** (`brand_eval.py`, re-run on the final build with the
  same result; "sites" merges POIs of one brand within 250 m, e.g. Sheetz fuel
  + shop):

| Chain | OSM POIs (brand:wikidata) | sites | reference | kind, date | sites / reference |
|---|---:|---:|---:|---|---:|
| Walmart | 176 | 135 | 135 | company, 2026-01-19 | 1.00 |
| Target | 80 | 79 | 79 | company page, read 2026-11-15 | 1.00 |
| The Home Depot | 71 | 70 | 71 | company store map, 2026-02-01 | 0.99 |
| Lowe's | 83 | 83 | 83 | 10-K FY2025, 2026-01-30 | 1.00 |
| McDonald's | 477 | 474 | 474 | ScrapeHero, 2026-10-08 | 1.00 |
| Starbucks | 327 | 317 | not found | | |
| Dunkin' | 517 | 511 | 691 | ScrapeHero, 2026-10-16 | 0.74 |
| Sheetz | 559 | 299 | 320 | ScrapeHero, 2026-10-19 | 0.93 |
| Wawa | 405 | 255 | 274 | ScrapeHero, 2026-09-22 | 0.93 |
| GetGo | 123 | 89 | 117 | ScrapeHero, 2026-10-27 | 0.76 |
| Giant Eagle | 100 | 97 | 92 | ScrapeHero, 2026-10-18 | 1.05 |
| Turkey Hill | 295 | 185 | 235 | ScrapeHero, 2026-08-25 | 0.79 |
| Rite Aid | 259 | 254 | 0 | news: all stores closed in 2025 | stale |
| CVS Pharmacy | 377 | 364 | not found | | |
| Walgreens | 95 | 92 | not found | | |
| Sunoco | 631 | 561 | not found | | |
| Love's | 19 | 7 | not found | | |
| Pilot Flying J | 34 | 20 | 18 | ScrapeHero, 2026-10-27 | 1.11 |
| Cracker Barrel | 24 | 24 | not found | | |

Sources are in `packbuilder/search/chain_reference.json` with URLs. "Company"
counts come from the chains (Walmart location facts page, Target regional page,
Home Depot investor store map, Lowe's 10-K); ScrapeHero is a third-party data
vendor's public count of store-locator entries. Neither reviewer could
re-confirm the Walmart number on the web (the page did not return counts to
WebFetch); the Sheetz count was re-confirmed by the reproduce reviewer.
Findings: the big-box chains match the reference exactly, which suggests their
OSM data was itself mapped from store locators recently (not verified, but it
means the comparison is not independent). Dunkin', GetGo and Turkey Hill are
20-26 % short. **Rite Aid is the warning:** 254 sites still in OSM, zero stores
open since 2025; the search will show closed stores until OSM is cleaned, so
the app needs a way to suppress or flag brands known to be defunct.

## Typo tolerance: three methods measured, one chosen

Benchmark (`fuzzy_eval.py`, not re-run in the review; the vocabulary size is
unchanged at 74,144 words): the 74,145-word vocabulary of the index (one build
before the first engineer's final one), 50 real typos, 2,000 synthetic
single-edit typos and 500 double-edit typos of frequency-weighted words (seed
20261115; real-word errors excluded). **The 50 "real" typos were partly
collected from the tuned test set, so that column is not independent.**
Recall@1 / recall@5; latency per lookup (single-edit set); database size
includes the 1.3 MiB vocabulary table. Linux run (python3, SQLite 3.45.1,
WSL); the Windows run gives the same recall and 1.5-2x the latency (the
reproduce reviewer re-ran it and got the same sizes and recalls).

| Method | MiB | real | single edit | double edit | median / p95 ms |
|---|---:|---|---|---|---|
| SymSpell, text keys, distance 2 from 5 letters | 76.0 | 0.70 / 0.94 | 0.95 / 1.00 | 0.83 / 0.98 | 0.80 / 4.91 |
| SymSpell, text keys, distance 1 (<7 letters) / 2 | 73.1 | 0.70 / 0.94 | 0.95 / 1.00 | 0.78 / 0.88 | 0.23 / 0.84 |
| SymSpell, text keys, distance 1 only | 14.4 | 0.70 / 0.94 | 0.95 / 1.00 | 0 / 0 | 0.12 / 0.36 |
| SymSpell, hashed keys, distance 1 / 2 | 35.4 | 0.70 / 0.94 | 0.95 / 1.00 | 0.78 / 0.88 | 0.40 / 1.49 |
| SymSpell, hashed keys, distance 1 | 8.6 | 0.70 / 0.94 | 0.95 / 1.00 | 0 / 0 | 0.14 / 0.38 |
| same, words seen at least twice | 4.3 | 0.76 / 0.92 | 0.95 / 1.00 | 0 / 0 | 0.11 / 0.24 |
| **hashed SymSpell distance 1 + trigram fallback (chosen)** | 10.8 | 0.70 / 0.94 | 0.95 / 1.00 | 0.68 / 0.70 | 0.12 / 0.33 |
| FTS5 trigram, detail=full, top 60 | 3.5 | 0.70 / 0.94 | 0.82 / 0.86 | 0.63 / 0.72 | 2.91 / 8.19 |
| FTS5 trigram, detail=full, top 200 | 3.5 | 0.70 / 0.92 | 0.89 / 0.93 | 0.72 / 0.82 | 5.60 / 13.33 |
| FTS5 trigram, detail=column or none (invalid, see below) | 2.7-3.5 | 0.02 / 0.02 | 0.10 / 0.11 | 0.09 / 0.10 | ~2 / ~6 |
| spellfix1 (loadable extension) | 3.0 | 0.54 / 0.86 | 0.41 / 0.54 | 0.23 / 0.36 | 1.34 / 7.19 |

Choice: SymSpell-style deletions of distance 1, stored as 32-bit FNV-1a hashes
(a hash collision only adds a candidate; the edit distance is always checked),
plus an FTS5 trigram index of words of 7+ letters that is consulted only when
the distance-1 lookup finds nothing. In the shipped database these cost 7.1 +
1.6 MiB. The highest recall on double typos (0.98) costs 76 MiB, about 65 MiB
more than the chosen design (0.70). spellfix1 lost on recall in this setup (its
candidate search keys on a phonetic prefix, so early-letter errors are missed)
and needs a loadable extension: possible on Android only by shipping a native
library through sqlite-bundled's `addExtension` (2.6.0+) and on the device only
if its Python was built with extension loading; not worth it.

Why the `detail=column` / `detail=none` trigram rows are near zero: the lookup
asks for the 60 best matches by `ORDER BY rank` (bm25). With those detail
levels FTS5 does not store per-row term counts, so bm25 gives every match the
same score; an experiment on 210 words with SQLite 3.49.1 found 5 distinct rank
values with `detail=full` and 1 with `column` or `none`. The query therefore
returns an arbitrary 60 of thousands of matches. The rows measure that query
shape, not the trigram idea; a lookup that counts shared trigrams itself would
be needed to use those smaller indexes.

Real-word errors ("sheets" for "Sheetz", "resturant", which appears in OSM
names) are handled in the engine: a word that is one edit away from a word at
least 20 times more frequent is also tried corrected. Connector words ("near",
"in", "on", "at", ...) are never corrected.

## Query engine

`query.py`, about 900 lines, standard library only. Steps: validate the
position (missing, non-numeric, non-finite or out of range -> ignored and
`position_ignored`; more than 50 km outside the state's box ->
`out_of_coverage`); normalise (`normalize.py` + `abbreviations.json`:
positional street-type, directional, saint/mount/fort/pennsylvania and
route-number rules); split off "near me", a trailing state word ("erie pa",
"pittsburgh, pa", "philadelphia pennsylvania"; kept when the whole phrase is a
known name such as "university of pennsylvania"), a connector and a leading
house number (unless no street-type word follows and the phrase starts a
brand, category or name: "7 eleven", "84 lumber", "7 brew"); classify the
phrase as category word / brand alias / known name, otherwise try the last 1-3
words as a place (only real municipalities, villages, counties and ZIPs unless
the rest is a category or brand); find typo candidates; fetch candidates per
interpretation with SQL (category: R*Tree rings of 3-500 km around the anchor,
walking the box first for rings up to 30 km; brand: ordered by squared
distance; names: FTS5 ordered by distance and by importance; 1-3 characters:
places by key prefix through a partial index and POIs from `poi_prefix` in the
grid cells around the anchor; "on <street>": POIs inside the matched street
boxes, preferring addr:street equal to the street); score with the weights in
`ranking.json` (a POI of the category the query names, or one whose whole name
is a word of that category such as "Rest Area", ranks only as a category hit);
look up house numbers; merge, drop near-duplicates (same title within 150 m),
return the top 10 with title, subtitle, category label, distance (from the
returned point), coordinates, kind and type. A pure category query that finds
nothing out of coverage returns nothing instead of junk name matches.

Examples (Erie bias unless noted):

```
'walmart erie pa'        -> Walmart Supercenter 2711 Elm Street, Erie; 1825 Downs Drive; 5350 West Ridge Road
'gas near pittsburgh'    -> Sunoco 310 Cedar Avenue, Pittsburgh; Shell, Pittsburgh; ... (parse: pittsburgh=place, gas=category fuel)
'mcdonalds on peach st'  -> McDonald's 4319 Peach Street; McDonald's 7200 Peach Street
'gas on peach street'    -> Kwik Fill 3620 Peach Street; Citgo 3826 Peach Street; ... (24 ms; was 295 ms)
'pitsburgh'              -> Pittsburgh (city)            (corrected)
'sheet' @ Altoona        -> Sheetz nearest first         (brand prefix)
'rest stop'              -> Rest area 29 km, 32 km, ...  (before: Pilot Travel Center, a truck stop, first)
"dick's"                 -> Dick's Sporting Goods 7 km   (before: Dickson City 378 km first)
'7-eleven' @ Philadelphia -> 7-Eleven stores nearest first (before: 'Eleven Mile', '7' read as a house number)
'route 30' @ Lancaster   -> US 30 street entries          (before: 'State Route 3017', 'Route 30 Diner')
'pa turnpike' @ Harrisburg -> Pennsylvania Turnpike 8.5 km (before: Amos K. Hutchinson Bypass 227 km)
'4319 peach st'          -> 4319 Peach Street, 4.43 km   (house point; before: the street's distance 1.75 km and two duplicates)
```

House numbers: the engine joins the addresses track through its view
`address_rows(hn, street_key, lat, lon)` using `street.addr_key`, which the
builder computed with that track's own `street_key()` (it abbreviates: "n
main st"; this index expands: "north main street"). One lookup per distinct
street key and house number; each address point goes to the street result
whose box holds it best (inside the box, else within 0.004 degrees). The
engine raises an error, instead of silently falling back, when the search
index has no join key or when the addresses database's
`meta.normalisation_table_version` differs from the one the index was keyed
with. Tested against `data/addresses/pa-addresses.sqlite` as rebuilt by the
addresses track at 15:00 on 2026-11-15 (normaliser version 2): all 15 house
cases (6 hand-picked, 9 sampled real addresses in 9 cities) are located within
0.1 km of the address point. Without the file the engine returns the street
with `house_located: false`.

## Regression tests

Three case files, each with mechanical expectations (place named X; street
named X in municipality Y; street carrying ref key R; POI with brand:wikidata Q
within N km; POI of category C within N km; title contains T; ZIP; house
number located within 0.1 km of a given point). Before a miss is counted as an
engine failure the runner asks the database whether anything satisfies the
expectation at all; if not, it is a data gap. The runner also checks on every
result of every case that `distance_km` equals the distance to the result's own
coordinates.

* `tests/cases.json`: 345 cases. **The first engineer tuned the engine while
  looking at these cases** (about six rounds), so its numbers are optimistic.
* `tests/cases_holdout.json`: 99 cases written after that tuning and run once.
* `tests/cases_review.json`: 49 cases written from the review findings before
  any fix, run once against the unfixed engine (results kept in
  `data/search/test-results-review-before.json`), then used to check the fixes.

| type | cases | top-1 | top-5 | data gaps |
|---|---:|---:|---:|---:|
| abbreviation | 18 | 100 % | 100 % | 0 |
| address | 8 | 100 % | 100 % | 0 |
| border | 8 | 100 % | 100 % | 0 |
| brand | 43 | 98 % | 98 % | 1 |
| category | 71 | 100 % | 100 % | 0 |
| mixed | 27 | 96 % | 96 % | 1 |
| pa_specific | 32 | 97 % | 100 % | 0 |
| partial | 24 | 79 % (was 83 %) | 92 % | 0 |
| place | 55 | 98 % | 100 % | 0 |
| street | 30 | 93 % | 100 % | 0 |
| typo | 29 | 100 % | 100 % | 0 |
| **all (tuned set)** | **345** | **97 %** | **99 %** | **2** |
| **held-out** | **99** | **95 %** | **97 %** | **1** |

Review set, before and after the fixes (the house cases passed before too, but
all of them returned a distance that did not match the returned point):

| type | cases | top-1 before | top-5 before | top-1 after | top-5 after |
|---|---:|---:|---:|---:|---:|
| state_suffix ("erie pa") | 11 | 9 % | 9 % | 100 % | 100 % |
| leading_number ("7-eleven") | 7 | 57 % | 57 % | 100 % | 100 % |
| route ("route 30", "pa turnpike") | 8 | 50 % | 50 % | 100 % | 100 % |
| apostrophe_brand ("dick's") | 5 | 40 % | 100 % | 100 % | 100 % |
| rest_stop | 3 | 0 % | 67 % | 67 % | 100 % |
| house (with coordinates) | 15 | 100 % | 100 % | 100 % | 100 % |
| **all** | **49** | **53 %** | **63 %** | **98 %** | **100 %** |

Results whose `distance_km` disagreed with their own coordinates: 29 before (17
in the review set, 11 in the tuned set, 1 held-out, all house-number results),
0 after.

Failures and misses, all of them:

* Data gaps (tuned set): no Speedway within 60 km of Erie in OSM (nearest is
  103 km); no Sheetz whose addr:street is Peach Street in Erie. Held-out: the
  nearest Pizza Hut to Altoona is 11.5 km, outside the 10 km I asked for (an
  over-tight expectation rather than missing data, as the reproduce reviewer
  noted).
* Engine failures (tuned set): "er" typed from Pittsburgh gives emergency rooms
  ("er" is a category word) instead of Erie, an honest ambiguity; "wa" from
  Philadelphia gives towns (Wayne, Warminster) instead of Wawa.
* Engine failures (held-out): "gass station" (both words are real words in
  OSM names, so no correction is tried, and the category is missed); "philly"
  (nickname; now matched to hoagie shops instead of the city; needs a
  nickname/alias list for places).
* Top-5 but not top-1 (tuned): "cranberry township", "front street",
  "interstate 79" (the top result is Raymond P Shafer Highway, I-79's name in
  Erie, so arguably right), "peach s", "liberty a", "pnc park" from Erie, and
  newly "rest ar" from Breezewood (see "Review and fixes", B9). Held-out:
  "lincoln highway", "mckn". Review set: "service plaza" from Harrisburg gives
  Onvo Travel Plaza (a real service plaza, 22 km) before the turnpike plazas;
  my expectation (title contains "Service Plaza") was stricter than needed,
  and I left it as written.
* A limitation the pass rates do not show: border queries use only the PA
  pack (Ohio was not built), so "walmart" from Youngstown finds Pennsylvania
  stores and passes, while a real user there wants the Ohio ones. Such
  positions are now flagged `out_of_coverage` when more than 50 km outside
  the state's box (Cleveland is; Youngstown is not).
* `tests/check_inputs.py`: 19 checks pass (8 bad positions, 6 far-away
  category queries that must return nothing, brand and border behaviour, a
  database path containing `#`, `%` and spaces, 27 hostile query strings).

The same runner under Linux python3 3.12.3 / SQLite 3.45.1 gives identical
per-case results on all three sets (493 cases, pass flags and top-5 result
lines compared), and the input checks pass there too (`wsl_linux_check.sh`).
The tuned set gives the same pass rates with the addresses database detached.

## Latency

All times in milliseconds, Windows, Python 3.12.10, SQLite 3.49.1, Intel
i7-10750H. **Other engineers' builds were running**; the CPU load at the start
of each run is given. "Cold" means a new connection (SQLite page cache empty;
the OS file cache was NOT flushed), "warm" five repeats on that connection.

Test-set queries (`measure.py --only latency`, 444 dev and held-out queries, no
addresses database attached). "Before" is the first engineer's engine on the
first engineer's database, measured by me in the same session (CPU 45 % busy at
start); "after" is the final build (CPU 49 % busy at start):

| type | n | after cold median / p95 | after warm median / p95 | before warm median / p95 |
|---|---:|---:|---:|---:|
| place | 74 | 7.1 / 21.8 | 5.1 / 20.6 | 4.1 / 16.0 |
| street | 38 | 26.1 / 53.9 | 24.2 / 53.7 | 22.3 / 40.1 |
| category | 87 | 18.6 / 65.9 | 18.4 / 50.5 | 25.7 / 102.5 |
| brand | 55 | 7.4 / 17.7 | 5.6 / 18.8 | 6.0 / 17.7 |
| mixed | 36 | 17.5 / 58.0 | 16.5 / 65.9 | 19.6 / 80.8 |
| typo | 39 | 10.3 / 25.6 | 7.5 / 25.4 | 7.1 / 58.1 |
| abbreviation | 18 | 12.3 / 28.3 | 10.7 / 101.6 | 10.7 / 71.8 |
| partial | 31 | 18.6 / 33.8 | 17.3 / 37.7 | 18.0 / 79.6 |
| pa_specific | 46 | 14.6 / 34.6 | 12.1 / 36.2 | 9.6 / 34.2 |
| address | 10 | 18.3 / 40.6 | 16.6 / 40.5 | 15.1 / 22.4 |
| border | 10 | 8.1 / 12.0 | 5.8 / 8.1 | 6.2 / 70.4 |
| **all** | **444** | **13.9 / 43.4** | **11.0 / 42.4** (max 111) | **10.7 / 60.1** (max 324) |
| review set | 49 | 11.7 / 31.5 | 9.2 / 22.6 (max 61) | (not comparable: the old engine answers most of them wrongly) |

The first engineer's own earlier figures (warm 14.8 / 77.4, max 414, at 59 %
load) and the reproduce reviewer's (warm 20.1 / 111.4, max 538, at 76 % load)
show how much load moves these numbers: treat any single figure as +-50 %.

Search as you type (`measure.py --only prefix`): every 1-letter (26) and
2-letter (676) prefix, the 100 most common 3-letter word starts of the
vocabulary, and 8 common whole words, at Erie, Philadelphia and Altoona; each
query run twice on one connection, the second run reported. Before = first
engineer's engine and database (CPU 12 % busy at start); after = final engine
(CPU 46 % busy at start):

| group | n | before median / p95 / max | after median / p95 / max |
|---|---:|---:|---:|
| 1 letter | 78 | 228 / 517 / 797 | 3.0 / 11.4 / 14.4 |
| 2 letters | 2,028 | 1.2 / 73 / 217 | 1.5 / 3.2 / 35 |
| 3 letters (100 most common) | 300 | 44 / 117 / 214 | 5.3 / 30 / 50 |
| common whole words ("street", "road", "lane", "church", "avenue", "main", "park", "school") | 24 | 107 / 172 / 181 | 79 / 172 / 185 |

Most 2-letter combinations ("qx") match nothing, so the 2-letter median is low
in both runs; the p95 and max are the useful columns. The remaining 2-letter
maxima are prefixes that are category words ("hs" = high school, "rx", "er",
"ev"). Common whole words were not improved: they go through FTS5 with all
matches sorted by distance (the reviewer measured 315-396 ms for "street" and
"road" under heavier load). On a phone all of this will be slower.

House numbers (`measure.py --only house`, warm median of 5, CPU 32 % busy at
start; with the addresses database attached vs not attached): "1 main st" 70.8
vs 44.9; "100 main st" 66.5 vs 45.0; "500 church st" 53.4 vs 35.0; "123 n main
st" 57.2 vs 29.8; "4319 peach st" 27.1 vs 24.5; "1600 market st philadelphia"
28.0 vs 19.2; "600 grant street pittsburgh" 20.3 vs 11.2; "72 s main st wilkes
barre" 35.1 vs 13.2. The join adds 3-28 ms; the break reviewer measured 261-308
vs 79-105 ms for the first two queries with the first engineer's engine (I did
not re-measure that engine's join, because in my snapshot its run-time import
of the other track's code no longer resolved).

## Build time and memory

Full forced run of the fixed builder (`build.py --state pa --force`, started
14:49:19, finished 14:53:41 on 2026-11-15; one CPU sample during it read 18 %),
plus a final index-only rebuild at 15:04 to key the house-number join with the
addresses track's latest normaliser code:

| Step | Wall time | Peak memory (process peak working set) | Output |
|---|---:|---:|---|
| check (MD5 of the 334 MB PBF) | 0.7 s | small (not recorded) | `data/search/work/pa-input.ok` |
| extract (pyosmium, 4 threads) | 164.1 s | 1,674 MB | `data/search/work/pa-extract.pkl`, 373 MB |
| index (single-threaded Python) | 94.9 s (final rebuild: 105.7 s) | 2,789 MB (2,788 MB) | `data/search/pa-search.sqlite`, 154.7 MB |
| total | 4.4 min | | |

All runs today, on the same input under different machine load: extract 136.5
s (before the boundary member-way capture), 159.2, 164.1, 249.6, 383.6
(reviewer), 466.9 s; index 82-195 s across about eight runs (reviewer: 180.5
s). Peak memory was the same in every run (1,674-1,675 MB and 2,767-2,813 MB).
So build time is 4-10 minutes on this PC depending on what else runs; memory
is stable. The first engineer's report gave the start of its clean run as
13:56, but its log shows the input check at 14:03 and the extract output at
14:07; the times it quoted (249.6 s, 116.0 s) are from that run's log.

Peak memory is the process's peak working set (`psutil` `peak_wset` on
Windows), recorded by each step in `data/search/pa-build-stats.json`, which now
keeps the latest result per step plus a `history` list of every run, and
counts rows from the finished tables (the first build's file said 5,238 brand
aliases because it counted duplicates the `INSERT OR IGNORE` dropped; the table
has 3,021 rows now, 2,963 before the possessive aliases). Each step runs in its
own process. The extract uses 4 reader threads; the index step is
single-threaded Python. Neither step can be capped at a memory limit; both
stayed under 3 GB.

## Portability (Android and comma 3X)

* Needs: FTS5 with the built-in `unicode61` and `trigram` tokenizers (trigram:
  SQLite 3.34 or newer), R*Tree, `WITHOUT ROWID` tables, partial indexes
  (3.8.0+). No loadable extension, no custom tokenizer, no SQL math function,
  no JSON function. `CROSS JOIN` is used only to fix a join order (plain
  SQLite syntax). Distances, edit distance, FNV-1a hashing and the grid-cell
  formula are done in application code (all trivially portable).
* androidx sqlite-bundled: the build file in the AndroidX source compiles
  SQLite 3.50.1 from June 2025 until an update to 3.53.4 on 2026-10-02, after
  2.7.1 (2026-09-09) was released, so 2.7.x should carry 3.50.1 with
  `SQLITE_ENABLE_FTS5`, `SQLITE_ENABLE_RTREE`, `SQLITE_ENABLE_MATH_FUNCTIONS`,
  `SQLITE_ENABLE_JSON1`, `SQLITE_THREADSAFE=2`, `SQLITE_TEMP_STORE=3`
  (read by the first engineer from `sqlite/sqlite-bundled/build.gradle` in the
  androidx GitHub mirror, commits 8f2b190 and fc956c2; the release branch
  itself was not checked, and neither reviewer could confirm it). The
  engine's SQL shapes, including the new ones (partial-index prefix query,
  `poi_prefix` lookup, `CROSS JOIN` ring query), ran without error on the
  official 3.50.1 CLI, which also chose the partial index (`measure.py --only
  compat`).
* Linux / comma: verified with Ubuntu 24.04's python3 (SQLite 3.45.1).
  Not verified on the device's own Python build (python-build-standalone or
  AGNOS); it needs SQLite >= 3.34 with FTS5 and R*Tree enabled, which should be
  checked with `PRAGMA compile_options` on the device.
* The database opens read-only (`mode=ro` URI built with percent-encoding,
  `query_only`).
* The house-number join needs nothing from the addresses track at run time
  except its database: the join key is a column of this database.

## Licences

| Item | Licence (checked at) | Ships to device |
|---|---|---|
| OpenStreetMap data (in the index) | ODbL 1.0 (openstreetmap.org/copyright); attribution string stored in `meta` | yes |
| iD tagging schema words in `categories_idterms.json` and `category_term` | ISC (LICENSE.md in the npm package and GitHub repo); the full notice is now carried in `categories_idterms.json`, `THIRD_PARTY_NOTICES.md` and `meta.third_party_notices` | yes (words, with notice) |
| SQLite | public domain (sqlite.org/copyright.html) | yes (part of the platform / sqlite-bundled) |
| Query engine, normalisation, data files in `packbuilder/search` | this project's licence | yes (as the model for the Kotlin port) |
| pyosmium 4.3.2 | BSD-2-Clause (wheel METADATA, source header) | no |
| libosmium 2.23.1 (inside pyosmium) | BSL-1.0 (LICENSE in osmcode/libosmium) | no |
| shapely 2.2.0 / GEOS (in the wheel) | BSD-3-Clause / LGPL-2.1 (wheel licence files) | no |
| numpy 2.5.4 | BSD-3-Clause and others (wheel METADATA) | no |
| psutil 7.2.2 | BSD-3-Clause | no |
| requests 2.34.2, urllib3 2.8.0, idna 3.20, certifi 2026.10.23, charset-normalizer 3.5.2 (osmium dependencies) | Apache-2.0, MIT, BSD-3-Clause, MPL-2.0, MIT (wheel METADATA) | no |
| SQLite 3.50.1 Windows tools (`tools/fetch_sqlite_tools.py`) | public domain | no |
| spellfix.c from SQLite 3.45.1 (benchmark only, compiled in WSL) | public domain | no |
| Name Suggestion Index 8.0.20261101 (benchmark only) | BSD-3-Clause stated for the whole repository (README, LICENSE.md) | no, by project rule |
| pypdf 6.1.1 (to read two PDFs while researching store counts, scratch venv outside the repo) | BSD-3-Clause | no |
| Store-count references | facts quoted with sources (company pages, 10-K, ScrapeHero, news) | no |
| Addresses track's `street_key()` (imported at build time only) | this project's licence (that track's own code) | no (only its output, the key column) |

The review added no dependency.

## Dead ends and things not done

* libosmium silently drops multipolygon relations that are incomplete in the
  extract: the Pennsylvania relation and Delaware County were missing in the
  first builds (255 places without a county). Fixed by the member-way rebuild
  described above; the rebuilt shapes are approximate where gaps were closed.
* Capturing member ways via pyosmium's `handler_for_filtered` coincided with
  a slower extract in the first engineer's runs (136 s before; 467 s and 250 s
  after), but my run of the same code took 164 s on a quieter machine, so the
  slowdown was most likely load, not code.
* The first typo table (text keys, distance 2 for long words) made the
  database 210 MB; the measured comparison led to the hashed distance-1 table
  plus trigram fallback.
* The trailing-place heuristic first split "state college" into "state" + the
  township "College", and "giant eagle" into "giant" near the village Eagle;
  it now only fires when the whole phrase is not already a known name, brand
  or category, and only for real municipalities unless the rest is a category
  or brand.
* Not built: Ohio (the builder supports `--state oh`; multi-pack search across
  a state border is not implemented: the engine opens one database).
* Not done: a dedicated route entity (routes are found through street
  ref keys, now including "route n"); "turnpike" alone is not an alias of the
  Pennsylvania Turnpike; opening hours are stored raw and not interpreted; no
  handling of stale or closed POIs; place nicknames ("philly", "pgh"); brand
  abbreviations not in the data ("bk", "7-11"); access points for large areas;
  fast common whole words ("road", "street": 80-185 ms).
* The `entry` table and `context` table dominate the size and were not
  optimised.

## Files outside `packbuilder/search`, `data/search` and this report

* `tools/fetch_sqlite_tools.py` (added by the first engineer; unchanged in the
  review): fetches the official SQLite 3.50.1 Windows tools into
  `tools/bin/sqlite/`, SHA-256 pinned.
* Read only: `data/osm/pennsylvania-261114.osm.pbf` (+ `.md5`, `.poly`),
  `data/addresses/pa-addresses.sqlite`, and `packbuilder/addresses/addrlib/`
  (`normalize.py` + `us_street_norm.json`), which the index step imports at
  build time for the join key. Nothing outside my folders was edited.
* Outside the repo: WSL `~/beamline/search-fuzzy` (SQLite 3.45.1 amalgamation
  zip, `spellfix.c`, compiled `spellfix.so`, benchmark databases) and
  `~/beamline/search-linux` (a copy of the index and the Linux test results);
  a throw-away venv with pypdf 6.1.1 and, from the review, a copy of the first
  engineer's `packbuilder/search` in the session scratch folder (used to
  measure the "before" numbers). The first engineer's database is kept as
  `data/search/work/pa-search-before.sqlite` for the same purpose. No apt or
  system-wide installs.

## Decisions for the project owner

1. **One shared street normaliser for search and addresses.** Today the search
   index stores the addresses track's key per street, computed at build time
   with that track's code. During this review that code changed twice within
   an hour (table version 1 -> 2, then a code change without a version bump),
   and its database was rebuilt mid-review. The engine now refuses a version
   mismatch, but it cannot see code changes that keep the version.
   Recommendation: move `street_key()` and its table into a shared module that
   both builders import, record its hash in both databases, and rebuild both
   packs together (they are built from one snapshot anyway).
2. **Stale chain data.** Rite Aid shows 254 sites in OSM and zero real stores.
   Recommendation: a small data file of defunct brand:wikidata IDs that the
   builder drops or flags, reviewed per release.
3. **Typo table size versus recall.** Shipped: hashed distance 1 + trigram
   fallback (8.7 MiB). Alternatives: hashed distance 2 for long words (+27
   MiB, recall@5 on double typos 0.88 instead of 0.70) or text keys with
   distance 2 from 5 letters (+65 MiB, 0.98). Recommendation: keep the shipped
   one for phones.
4. **NSI aliases.** Not shipped (data licence not separate from code).
   Recommendation: a hand-made alias list for the ~30 most-searched chains
   ("cvs", "walgreen", "dunkin donuts", "bk", "7-11"), written by us.
5. **Ranking while a category word is being typed.** "rest ar" from Breezewood
   puts a park named "Jacquet Rest Area" 230 km away first; the nearby rest
   areas and the service plaza 15 km away follow. Name prefix matches outweigh
   nearby category hits until the category word is complete. Recommendation:
   decide whether a multi-word query that is a prefix of a category term should
   rank like the category (this would hurt "state st" -> State Street, which is
   also a prefix of "state store"), then tune on a fresh test set.
6. **Size work before the Kotlin port**: subtitles at query time and a smaller
   context table could save an estimated 30-40 MiB (estimate from the table
   sizes above, not measured).
7. **Ohio and border search**: build the Ohio pack and merge results from
   neighbouring packs; until then, positions outside the pack are only
   flagged.

## Review and fixes

Two reviews of the first build: "reproduce" (re-ran everything) and "break"
(attacked it). Both said "sound with fixes", with no blockers. I checked each
finding myself before changing code. None is disputed; for two I add a
correction of detail. The pre-registered review cases (`cases_review.json`)
were written before any fix.

| # | Finding | Severity | Outcome |
|---|---|---|---|
| R1 | House-number results report `distance_km` to the street's point, not the returned house point | major | **Fixed.** Confirmed: "4319 peach st" at Erie returned 1.75 km for a point 4.43 km away, and three copies of the same house. The house is now looked up before distance and de-duplication; the point goes to the street that holds it best. The runner checks distance against coordinates on every result: 29 violations before, 0 after. 15 house cases with expected coordinates added. |
| R2 | Search-as-you-type at 2 characters is ~10x the headline latency | major | **Fixed.** Confirmed: 100-300 ms for common 2-letter prefixes, about 1 s for "s". Cause: places by prefix scanned every entry with that key prefix, and nearby POIs joined the whole R*Tree box to `entry`. Now a partial index on place keys and a `poi_prefix` grid table (2.0 MiB). 1 letter p95 517 -> 11 ms, 2 letters p95 73 -> 3 ms (max 217 -> 35), 3 letters p95 117 -> 30 ms. A dedicated prefix set is in `measure.py`. The 3-letter fix also needed a faster category browse (R*Tree walked first for rings up to 30 km: "pla" 3 km ring 173 -> 1.7 ms), which also cut category p95 from 103 to 51 ms. |
| R3 | Build-time and latency headline numbers do not reproduce within 15 % | minor | **Addressed.** Re-measured, numbers now given with CPU load and as ranges (build 4-10 min; latency +-50 %). Peak memory, outputs and row counts were already reproducible. |
| R4 | `pa-build-stats.json` overwritten; brand alias count wrong; start time wrong | minor | **Fixed.** Stats keep a per-run history; counts come from the finished tables (brand_alias 2,963 rows in the first build, not 5,238); the report now states the first engineer's run times from its log. |
| R5 | Typo table omits the best measured variant; real typos overlap the tuned set; trigram `detail=column/none` rows unexplained | minor | **Fixed in the report.** Row added (76.0 MiB, 0.98 on double typos); overlap stated; the near-zero rows explained and checked with an experiment (bm25 gives all matches one score at those detail levels). |
| R6 | House-number join not mechanically tested; runs another track's code at run time | minor | **Fixed.** The join key is now a column computed at build time (`street.addr_key`); the engine runs no code of the other track. The 15 house cases check coordinates within 0.1 km. |
| B1 | Trailing state name ("erie pa", "pittsburgh, pa") breaks the parse; "near" typo-corrected to "bear" | major | **Fixed.** Confirmed exactly as described. Trailing "pa" / "penna" / "pennsylvania" (also before a ZIP) is dropped unless the whole phrase is a known name ("university of pennsylvania"), list in `abbreviations.json`; connector words are never typo-corrected. Review cases 9 % -> 100 %. |
| B2 | A leading number is always a house number: 7-Eleven, 84 Lumber, 7 Brew fail | major | **Fixed.** Confirmed. A leading number stays a house number when a street-type word follows; otherwise it is part of the name if the phrase (or its first two words) is a brand alias, category word, or the start of a place or POI name. Review cases 57 % -> 100 %; the 8 address cases still pass. "7-11" and "seven eleven" still find nothing (no such alias in the data; see decision 4). |
| B3 | Short-prefix and common-word latency far above the reported p95 | minor | **Partly fixed.** 1-3 letters fixed as in R2. Common whole words ("road", "street") are unchanged at 80-185 ms here (FTS5 matches sorted by distance); left open. "s" from London or 0,0 (reviewer: 2-3 s) now takes 4.4-4.5 ms. |
| B4 | Route-number and highway queries rank wrong or find nothing | minor | **Mostly fixed.** Streets with `US n` / `PA n` refs get derived keys `route n` / `state route n`; "pa" before a word expands to "pennsylvania" ("pa turnpike"). "route 30", "route 22", "route 8", "pa 8", "us 30", "i 80", "pa turnpike" pass. Not done: "turnpike" alone, and I did not re-check "us 30" from the reviewer's central point. |
| B5 | Bad or far-away positions not validated | minor | **Fixed.** inf / nan / out-of-range / non-numeric positions are ignored (`position_ignored`); positions more than 50 km outside the state box are flagged `out_of_coverage`, and a category query that finds nothing there returns nothing. 14 checks in `check_inputs.py`. |
| B6 | "dick's" puts towns 378 km away first; "bk" finds nothing | minor | **Fixed for possessive brands**: the builder adds the first word of possessive multi-word brand names as an alias ("dicks", "bjs", "ollies"); 5 review cases pass. "bk" not done (needs a hand alias list). |
| B7 | House-number join slower than reported; fails silently | minor | **Fixed.** One lookup per distinct key instead of one per street result: the join adds 3-28 ms (reviewer: about 150-200 ms). The engine raises instead of falling back when the join key is missing or the addresses database uses another normaliser version. Correction of detail: the version check proved necessary within the session, because the addresses track changed its normaliser and rebuilt its database while I worked. |
| B8 | Database path not escaped in the SQLite URI | minor | **Fixed.** Confirmed with the old engine (a path with `#` and `%` gives "no such table: category"); URIs are now built with percent-encoding, for ATTACH too. Checked in `check_inputs.py`. |
| B9 | "rest stop" / "rest area" rank commercial truck stops above rest areas | minor | **Fixed.** Truck stops mapped as `highway=services` are no longer service plazas (data rule in `categories.json`). Side effect: dev case "rest ar" from Breezewood, which had passed because a truck stop was 0.5 km away, dropped to top-5. After seeing that, I added one general rule (POIs of the named category, or with a generic name equal to a category word, rank only as category hits); it did not restore top-1 (a park named "Jacquet Rest Area" 230 km away is first), and I stopped there rather than tune to the case (decision 5). |
| B10 | ISC permission notice not carried with the copied iD words | minor | **Fixed.** Full notice in `categories.json` / `categories_idterms.json` (generated), `THIRD_PARTY_NOTICES.md` and the database `meta`. |
| B11 | Semicolons in subtitles; "once per town" wording; uncategorised POIs without a label; tied scores | minor | **Fixed / stated.** Subtitles use the first value (32 -> 0 with `;`); the cluster rule is stated next to the claim (3,030 groups have more than one entry by my count of `(name, muni_id)`; the reviewer counted 3,930 groups, probably including streets outside municipalities); uncategorised POIs show their OSM type (no label: 38,288 -> 2,216). Tied scores: left as they are (the order is stable). |

Changes beyond the findings, all measured: the category browse walks the
R*Tree box first for rings up to 30 km, and "<category> on <street>" probes
`poi_cat` per box entry ("gas on peach street" 295 -> 24 ms). The test-set
95th percentile fell from 60 to 42 ms warm at a similar CPU load.

Still open after the review: "er" and "wa" (2-letter ambiguity), "gass
station" (real-word typo), "philly" (nicknames), "rest ar" (decision 5),
common whole words latency, Ohio and multi-pack search, access points, stale
chains, NSI or a hand alias list, the shared normaliser, size work.
