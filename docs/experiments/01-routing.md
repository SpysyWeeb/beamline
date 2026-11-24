# Experiment 01: Routing packs with Valhalla (PA + OH)

Date: 2026-11-15. Machine: Windows 11, 12 logical CPUs, 32 GB RAM. Other
engineers' heavy builds were running at the same time, so every time below
is from a shared machine. Heavy jobs were capped at 4 threads. A reproducing
reviewer, on the same loaded machine, got timings 15-45% slower and some
faster, so read every time as a range, not a fixed number.

Revised the same day after two independent reviews (reproduce and break):
four major findings were confirmed and fixed (pack updates, engine-version
gate, Avoid highways from a motorway, the 170/171 error behaviour), and the
cheap minor ones too. See "Review and fixes" at the end.

## Result in short

- **Engine:** valhalla-mobile 0.6.4 (newest on Maven Central, 2026-10-06)
  bundles **Valhalla 3.9.1**. Its `src/valhalla` submodule points at commit
  `bafb699`, which is the `3.9.1` tag. `pyvalhalla==3.9.1` is on PyPI with
  wheels for win_amd64, manylinux x86_64 and **manylinux aarch64**, so the
  builder, the phone and a later comma 3X can all run the same engine.
- **Windows-native works.** `valhalla_build_tiles`, `valhalla_build_admins`,
  `valhalla_build_extract` and `valhalla_build_config` all ran from the Windows
  wheel. WSL was not needed. `valhalla_build_timezones` is in **no**
  pyvalhalla wheel (Windows or Linux). It is a shell script that needs the
  `spatialite` CLI, so I rebuilt it in Python (`tzdb.py`) on top of the
  SQLite/SpatiaLite libraries the wheel already vendors.
- **One graph for both states.** The two state extracts must be **merged
  first**. Passing both files to `valhalla_build_tiles` builds, and routes
  across the border still work, but it creates duplicate edges: the validator
  reports 44,792 possible duplicates against 68 for the merged build. Valhalla
  itself logs a warning saying not to do it. With the merged input, all 6
  crossings tested (I-90, I-80, I-76 Turnpike, US-20, US-6, a county road)
  route in both directions with a detour ratio of 1.02 to 1.08. The admin
  data shows the route going from OH to PA.
- **Admin data needed a fix.** If the admin DB is built from the state
  extracts, only Ohio survives: the PA and US boundary relations are cut at
  the extract edge. I fetched the complete relations from the OSM API (Overpass
  was down) and checked that none had changed since the snapshot. The tiles
  now carry US/PA/OH.
- **Packs:** PA pack 634 MB, OH pack 644 MB (uncompressed tar, the format the
  engine memory-maps). They share 15 border tiles (65 MB), byte for byte the
  same. gzip brings PA to 240 MB and xz to 171 MB for download. The
  driving-only graph is 22% smaller than Valhalla's default with walking and
  cycling. Install takes 3.5 to 10 s, uninstall 0.1 to 3 s. With PA only, routes
  into Ohio work up to about 19 km past the line. Beyond that the engine
  returns first `170 Locations are in unconnected regions` (on the probe
  line west of Sharon, from about 20 to 50 km past the line) and then
  `171 No suitable edges near location`. Two builds
  of the same input are not byte-identical (64 of 522 tiles differ), so packs
  from different builds must never be mixed; `packs.py` refuses that,
  updates installed states in place (several packs in one call for a
  release), refuses packs built for another Valhalla version, and checks
  every tile's SHA-256.
- **Presets:**
  - Shortest, Avoid highways and Avoid tolls clearly change routes.
  - Eco is a weak proxy: it changes 9 of 21 trips, with 3.7% less distance.
  - Simpler as a plain `maneuver_penalty` **is noise**: more time, and no
    fewer maneuvers. Replaced by a small candidate pool: pick the fewest
    maneuvers within +5% time. It changes 6 of 21 trips and never adds
    maneuvers.
  - Avoid highways used to do nothing when the trip starts on a motorway
    (Valhalla's exclusion only forbids driving onto one). It now falls back
    to `use_highways: 0` in that case and leaves at the next exit. Avoid
    tolls has the same blind spot on the Turnpike, with no fix in 3.9.1.
- **Matrix:** with the default config, CostMatrix returns **nulls** on a
  479 km interstate trip: 19 of 40 cells for 1x40, 22 of 40 for 40x1, and all
  40 for one many-to-one case. Cause, found in the code: each search tree
  stops at `max_matrix_distance / 2` of path distance. With
  `service_limits.auto.max_matrix_distance = 1000000` and
  `thor.costmatrix.allow_second_pass = true` there are 0 nulls, at 0.4 to 2 s
  on this PC. These settings are now in the device config
  (`device_config.json`), and the test checks 0 nulls with it.
- **Device needs only the tiles.** The admin and timezone databases are
  builder-only: a route with both paths pointing at missing files still
  returns `America/New_York` and US/PA/OH. The device `valhalla.json` holds
  no absolute paths.
- **Silent far snapping:** a destination outside the installed tiles or in a
  lake is snapped to a road up to 35 km away without a warning. The route
  summary now reports the gap, and the app should warn above 1 km.

## How to rerun

From the repo root (Windows shown; on Linux use `.venv/routing/bin/python`):

```
python tools/fetch_routing_env.py                                    # venv + pinned, hash-checked pyvalhalla 3.9.1
.venv/routing/Scripts/python packbuilder/routing/build.py all        # merge, tz, config, boundaries, admins, tiles, packs
.venv/routing/Scripts/python packbuilder/routing/test_routing.py     # 25 checks for steps 2-5, about 3-4 min
.venv/routing/Scripts/python packbuilder/routing/route_eval.py crossings|presets|tune|extras|matrix|latency
.venv/routing/Scripts/python packbuilder/routing/build.py tiles-unmerged   # experiment: two PBFs, no merge
.venv/routing/Scripts/python packbuilder/routing/compare_unmerged.py
.venv/routing/Scripts/python packbuilder/routing/build.py tiles-allmodes   # experiment: keep walk/bike edges
.venv/routing/Scripts/python packbuilder/routing/build.py tiles-disk       # experiment: build disk footprint
.venv/routing/Scripts/python packbuilder/routing/measure_compression.py pa
.venv/routing/Scripts/python packbuilder/routing/packs.py install pa oh / uninstall oh / list
```

Prerequisites: Python 3.12, and the inputs in `data/osm/`
(`pennsylvania-261114.osm.pbf`, `ohio-261114.osm.pbf`, their `.md5`,
`pennsylvania.poly`, `ohio.poly`; the repo's downloader is
`tools/fetch_osm_extracts.ps1`, and the date is `SNAPSHOT` in `common.py`).
`build.py` waits up to an hour for the PBFs to match their `.md5`. Each step
writes a stamp under `data/routing/build/stamps/` and is skipped on rerun
unless `--force` is given or (new) the step's recorded input files changed
size or mtime. Raw results go to `data/routing/results/*.json|md` and logs to
`data/routing/logs/`. See `packbuilder/routing/README.md` for the file list.

## 1. Engine version

| check | finding | source |
|---|---|---|
| newest valhalla-mobile on Maven Central | `io.github.rallista:valhalla-mobile:0.6.4`, published 2026-10-06 | repo1.maven.org maven-metadata.xml |
| Valhalla inside it | release note "bumps valhalla to 3.9.1"; at tag 0.6.4 the submodule `src/valhalla` = `bafb699`, which is tag `3.9.1` | GitHub API, Rallista/valhalla-mobile and valhalla/valhalla tags |
| matching Python package | `pyvalhalla==3.9.1` (MIT), `__commit_id__ = gbafb69902`, wheels `cp312-abi3` for win_amd64 / manylinux_2_28 x86_64 / manylinux_2_28 aarch64 / macOS arm64 | PyPI JSON, installed package |
| newer Valhalla exists | 3.9.2 is tagged but not in valhalla-mobile yet. Stay on 3.9.1 until valhalla-mobile moves | GitHub tags |

Tools in the Windows wheel:

| tool | usable on Windows? | note |
|---|---|---|
| `valhalla_build_tiles` | yes | C++ exe in `valhalla/bin/`. The wheel's console-script wrapper (`Scripts/valhalla_build_tiles.exe`) **fails** in a venv that is not activated: `FileNotFoundError: Can't find executable at valhalla_build_tiles`, because it looks the tool up on PATH. `common.py` calls `bin/*.exe` directly and puts `pyvalhalla.libs` on PATH for that process only |
| `valhalla_build_admins` | yes | same wrapper issue, same fix |
| `valhalla_build_extract` | yes | pure Python. `-g DIR` needs shapely |
| `valhalla_build_config` | yes | pure Python (`python -m valhalla.valhalla_build_config`) |
| `valhalla_build_timezones` | **not shipped** (Windows or Linux wheel) | upstream it is a `/bin/sh` script needing `spatialite`, `spatialite_tool`, `unzip`. Replaced by `tzdb.py`: it loads the wheel's vendored `sqlite3-*.dll` + `spatialite-*.dll` (SpatiaLite 5.1.0) through ctypes and runs the same SQL (`ImportSHP`, `CreateSpatialIndex`, `VACUUM`, `ANALYZE`) on timezone-boundary-builder **2026d**, the release the 3.9.1 script pins (SHA-256 checked). 304 zones, 4 s. Valhalla reads it fine: routes carry `America/New_York` |

No WSL was used and no apt packages were installed.

## 2. One graph for PA + OH

### Feeding two overlapping extracts

The Geofabrik extracts overlap along the border: 165,028 nodes, 4,317 ways and
344 relations are in both files. In 3.9.1, `PBFGraphParser::ParseWays` loops
over the input files and calls `graph_parser::way()` for every way, with no
de-duplication. The only order check is `id < last_id`, which is reset for
each file. `build_tile_set` itself logs: *"Tile building using more than one
osm.pbf extract is discouraged. Consider merging the extracts into one file.
See https://github.com/valhalla/valhalla/issues/3925"*. I tested both ways.

Which build each number comes from (corrected after review): the unmerged
build used the first, Ohio-only admin DB. The merged column mixes two merged
builds of the same input: the **first** merged build (same Ohio-only admin DB
as the unmerged build) for the tile size, and the **final** merged build (full
US/PA/OH admins) for everything read from `logs/build_tiles.log` and for the
locate counts. The admin DB changes only the admin codes stored in the tiles,
and the duplicate count (68) is the same in both merged builds, so the
comparison holds; only the size row is strictly like for like.

| | merged first (`merge_osm.py`) | two PBFs given directly |
|---|---|---|
| routable ways (final merged build) | 3,862,708 | 3,863,601 (+893) |
| graph edges / nodes (final merged build) | 8,735,242 / 6,844,366 | 8,757,179 / 6,853,607 |
| validator "possible duplicates" L0 / L1 / L2 (both merged builds) | 0 / 0 / 68 | 4,372 / 11,628 / 28,792 |
| edges at the crossing points (locate, final merged build) | US-20: 4, US-6: 8 (2 of them repeat the same way and direction) | US-20: 6 (2 repeats), US-6: 12 (6 repeats) |
| tile size (first merged build, same admin DB as unmerged) | 1,176.4 MB | 1,178.8 MB |
| crossing routes | all 12 ok | all 12 ok (no state codes: Ohio-only admin DB) |

So the merged graph is not entirely free of repeated edges either (2 at
US-6, and the validator's 68). The merged input holds one copy of each OSM
object, so these come from the data itself, not from feeding two files; I did
not look into what causes them.

Decision: **merge first.** `merge_osm.py` is a streaming k-way merge on
(type, id) with pyosmium. Both extracts come from the same snapshot, so
duplicates are identical (0 version mismatches). It took 662 s and 222 MiB
peak RSS for 100 M objects. It is slow because every object passes through
Python, but memory stays flat. For a US build this step goes away: use the
single US extract.

### Admin and timezone data

- First attempt: admins built from the merged extract. `valhalla_build_admins`
  logged `Pennsylvania (162109) is missing way member 1001586551 ... degenerate
  and will be skipped`, and the same happened for the United States and every
  neighbour state. Only **Ohio** was inserted. Tiles got no country code and
  no PA state code.
- Fix: `fetch_boundaries.py` fetches the 11 relations it needs (US, PA, OH,
  NY, NJ, DE, MD, WV, KY, IN, MI) with all members. Plan A was an Overpass
  "attic" query at the extract's own timestamp (`2026-11-14T21:20:41Z`), but
  overpass-api.de returned 504/502 on all three tries. Plan B was the OSM API
  `relation/{id}/full` call, which returns current data. The script checks the
  newest element timestamp of every relation, and none was newer than the
  snapshot, so the current versions are the snapshot versions. Result:
  11 admin areas, `admins.sqlite` 10.9 MB, built in 38 s.
- Timezones: `tz_world.sqlite` 122.2 MB (whole world), built in 4 s.
- **Run time:** neither DB is needed on the device. The test sets
  `mjolnir.admin` and `mjolnir.timezone` to missing files and still gets
  `time_zone_name: America/New_York`, offset -05:00, and admins
  `US/Pennsylvania` + `US/Ohio` from the tiles. Both are builder-only.

### Border proof (merged graph)

Test points about 2.5 km either side of the line on the same road. They were
found from the OSM data by `find_crossings.py` (122 crossing segments in
total) and stored in `trips.json`. Each request uses `admin_crossings: true`.

| crossing | OH→PA km (straight) | PA→OH km | detour ratio | admins on route |
|---|---|---|---|---|
| I-90 | 5.16 (4.99) | 5.17 | 1.03 / 1.04 | OH, PA |
| I-80 | 4.95 (4.63) | 4.96 | 1.07 | OH, PA |
| I-76 Ohio / PA Turnpike | 5.03 (4.96) | 5.04 | 1.02 | OH, PA |
| US-20 | 5.08 (4.97) | 5.08 | 1.02 | OH, PA |
| US-6 | 4.13 (3.81) | 4.13 | 1.08 | OH, PA |
| Columbiana Road (county road) | 5.03 (4.92) | 5.03 | 1.02 | OH, PA |

All 12 routes use the named road (`I 76 East` and so on) and record a state
crossing. Whole trips across the line also work: Erie→Cleveland 166 km on
I-90, Youngstown→Pittsburgh 110 km, Cleveland→Pittsburgh 217 km,
Akron→Erie 204 km, Sharon→Hubbard 12 km.

Pitfall found on the way: on divided highways a point snaps to the nearest
carriageway. The first PA→OH runs therefore drove to the next exit and back
(14.6 km on I-90 and 47.9 km on the Turnpike, against 5 km). `heading` alone
did not fix it. `heading` **plus `radius: 60`** did, because loki then also
considers the other carriageway. The app should send the GPS heading and a
radius when routing from the car's position.

## 3. Per-state packs, install and uninstall

### Pack design

- **Cut:** `valhalla_build_extract -g` with each state's `.poly` (converted to
  GeoJSON) takes every tile, at every level, whose bounding box touches the
  polygon. Each pack is a normal Valhalla tile tar (with `index.bin`, loadable
  on its own) plus `manifest.json`. The manifest holds the tile paths, sizes,
  SHA-256 and `graph_build` (`261114-pa-oh-v3.9.1-b53087`). The `b…` part is
  the 16-bit tileset build id that Valhalla 3.9.1 stamps into every tile
  header (`set_tileset_build_id`).
- **Border tiles:** both packs carry them, so a lone pack routes everywhere in
  its own state, right up to the line. Cutting from one combined build makes
  the shared tiles byte-identical (15 tiles, 65 MB, all SHA-256 equal). On
  install the device keeps one copy.
- **When both carry a tile:** the device stores it once. Each installed
  manifest lists it, which works as a reference count. Uninstall deletes only
  tiles no other installed manifest lists.
- **Mixing builds is refused:** edges in a tile refer to node indexes in the
  neighbour tiles, which are only valid within one build, and the GraphReader
  does not check this. `packs.py install` refuses a pack whose `graph_build`
  differs from the packs that stay installed. Tested: *"pack oh is from graph
  build 261201-… but installed pack pa is from 261114-…; update all packs to
  one build in one call (packs.py install oh pa) or uninstall pa first"*. All
  packs of one release must therefore be cut from one national build and
  updated together, or at least together with their neighbours.
- **Updates** (fixed after review): installing a state that is already
  installed replaces it. Tiles whose SHA-256 changed get the new pack's bytes,
  tiles only the old copy listed are removed, new ones are added. A release
  update installs several packs in one call (`packs.py install pa oh`), which
  is checked against the packs that stay installed. Before the fix, an update
  kept the old tiles and recorded the new manifest.
- **Engine version gate** (added after review): Valhalla does not reject tiles
  from another version (the break review patched a tile header from 3.9.1 to
  3.8.0 and it still routed). `packs.py` refuses a pack whose manifest
  `valhalla_version` differs from the engine's version, and checks the version
  in every tile header while copying it.
- **Integrity:** every tile written to the device, in both layouts, is checked
  against the SHA-256 its manifest lists (before the fix only the dir layout
  checked). A refused install leaves the device unchanged.
- **The one tile set** (`packs.py`):
  - layout `tar` (default) rewrites a single `routing.tar` with `index.bin`
    (the extract layout, memory-mapped by the engine) from the union of
    tiles, then swaps it in atomically.
  - layout `dir` adds or deletes `.gph` files in one tile directory. This is
    not atomic: an engine opened in the middle of an update could see a mix.
  - The layout is chosen at the first install and stored in `device.json`;
    a call with the other layout is refused (before the fix, mixing layouts
    silently broke the device).

  Either way the engine has to be reopened afterwards. Correction: the first
  version of this report said Windows cannot replace a tar that an Actor
  still maps. That was wrong. With a live Actor on `routing.tar`, installing
  OH replaced the file without error; the old Actor kept routing on the old
  tiles (Erie→Cleveland still `171`) and a new Actor routed it (165.6 km).
- **Device config** (fixed after review): `device/valhalla.json` now holds
  only paths relative to the device folder (`"tile_extract": "routing.tar"`),
  no admin/timezone/traffic/transit paths, and the settings the device needs
  from `device_config.json` (matrix limits, see section 5) and from the
  presets' `requires_config` (hard exclusions, `max_alternates`).
  `packs.engine_config(device)` resolves the tile path; the app has to do the
  same with its own files folder.

### Tests (`test_routing.py`, both layouts, all passed)

| case | result |
|---|---|
| only PA, routes inside PA (Pittsburgh U1, Turnpike T1, Erie→Harrisburg I1, Wellsboro→Coudersport X1) | all routed, times equal to the combined graph |
| only PA, Erie→Cleveland (B1), Cleveland→Pittsburgh (B3) | **error** `171 No suitable edges near location (HTTP 400)`. Erie→Akron and Erie→Columbus give the same |
| only PA, Youngstown→Pittsburgh (B2), Sharon→Hubbard OH (B5) | **routes fine**, same as with both packs: 109.6 km / 12.3 km, ends within 32 m of the request |
| both installed | B1-B5 and Columbus→Cleveland all route, times equal to the combined graph |
| OH uninstalled | 224 tiles removed, 15 shared kept; the tile set is identical (per-tile SHA-256) to PA-only, and the four PA routes match the earlier PA-only results exactly (time, length, shape) |
| pack from another build | refused (above) |
| release update (added after review) | PA+OH of build A installed; PA of build B alone refused (*"... update all packs to one build in one call (packs.py install pa oh) or uninstall oh first"*); PA+OH of build B in one call: 2 changed tiles replaced, 1 dropped tile removed, tile set equals the new manifests exactly; then OH uninstalled and PA back to build A: the dropped tile returns, tile set equals the PA manifest, Erie routes |
| pack for another Valhalla version (added) | manifest 3.10.0: refused on an empty device (*"pack pa was built for Valhalla 3.10.0 but this engine is Valhalla 3.9.1"*); one tile header patched to 3.8.0 with consistent hashes: refused in both layouts (*"... was built by Valhalla 3.8.0, expected 3.9.1"*), nothing left on the device |
| manifest lists a wrong tile SHA-256 (added) | refused in both layouts, device unchanged |
| layout (added) | `install oh --layout dir` on a tar device refused, uninstall with the wrong layout refused, no layout given uses the stored one; after the last uninstall only an empty `packs/` remains and the next install may pick `dir` |
| device config (added) | no absolute or home paths; every `device_config.json` and `requires_config` key present; B1 routes; matrix 1x40 and 40x1 on I1 with 0 nulls |

Why only some Ohio routes work, and what the error is (corrected after
review): the PA pack's local-level (0.25°) tiles reach to 80.75° W, about
19 km into Ohio (Youngstown, Hubbard). Routes to points inside them work.
Further west there are **two** errors, not one. Sharon PA to points due west
along latitude 41.20 (`trips.json` `pa_only_edge_probes`, asserted by the
test):

| destination longitude | PA only | combined graph |
|---|---|---|
| -80.70, -80.74 | route (end 352 m / 104 m from the point) | same |
| -80.76 to -81.10 | `170 Locations are in unconnected regions. Go check/edit the map at osm.org (HTTP 400)` | route |
| -81.20 and further | `171 No suitable edges near location (HTTP 400)` | route |

`locate` on the PA-only device shows why: at -80.80 loki snaps to a
residential road 4.2 km away that is cut off at the edge of the PA pack's
local tiles; at -80.95 and -81.10 it snaps to a level-0 primary road 17 and
29 km away; neither is reachable from PA in the installed tiles, hence 170.
From -81.2 nothing in the installed tiles lies within loki's 35 km
`search_cutoff`, hence 171. Erie→Cleveland and Cleveland→Pittsburgh give 171.
App logic must treat both codes as "destination outside the installed packs"
and offer the neighbour pack. The OH pack reaches only to 80.5° W, about
1.5 km into PA.

Also corrected: the first report said the engine "does not snap to a
far-away motorway". It does snap silently, up to `search_cutoff` (35 km), to
whatever road the installed tiles have. With the combined PA+OH graph, from
Erie: Jamestown NY ends 5.6 km from the requested point, Binghamton NY
8.2 km, Wilmington DE 10.1 km, Hagerstown MD 5.0 km, a point in Lake Erie
14.2 km, all without a warning (Buffalo NY gives 171). The route summary now
reports `endpoint_gap_m` (requested point to route end; in 3.9.1
`trip.locations` hold the requested coordinates, so the old `snap_m`, which
looked for `projected_lat`, was always empty), and
`route_lib.far_snap_warnings` warns above `checks.warn_if_endpoint_gap_m_over`
in `presets.json` (1,000 m). On the 21 trips the largest gap is 108 m.

Install timings on this PC, re-measured after the fixes (two test runs,
loaded machine; install now also hashes every tile and reads its header):

| operation | tar layout | dir layout |
|---|---|---|
| install PA on an empty device | 4.5-4.7 s | 3.7-3.9 s |
| install OH on top of PA | 7.1-7.3 s | 3.5-3.9 s |
| uninstall OH | 2.9-3.0 s | 0.14-0.16 s |
| install PA+OH in one call | 8.8-10.0 s | 8.3 s |
| release update PA+OH (build A → B) | 8.5-9.2 s | 3.5 s (only changed tiles are written) |

Before the fixes the first engineer measured 3.6-3.8 / 5.1-5.7 / 1.6 s (tar)
and the reproducing reviewer 5.2-5.4 / 7.3-8.2 / 2.6 s with the same old code
on the loaded machine, so the extra checks cost little next to the load
noise. The tar layout rewrites the whole tile set (about 1.2 GB for PA+OH)
on every change; on a phone that is a real cost to measure.

## 4. Route presets

`presets.json` maps each preset to Valhalla `auto` costing options. Names,
ranges and defaults were checked against the 3.9.1 docs
(`docs/docs/api/route/api-reference.md`) and source (`src/sif/autocost.cc`,
`dynamiccost.cc`). Two places where the docs and the code differ:

- the docs give `service_penalty` default 15, but `autocost.cc` sets 75 for
  auto;
- hard exclusions (`exclude_highways`, `exclude_tolls`) are rejected unless
  `service_limits.allow_hard_exclusions` is true (default false). We own the
  device config, so it is enabled there.

Car costing has no hills or left-turn option, so none is used.

Valhalla silently ignores unknown option names and out-of-range or
wrongly typed values (the break review: `use_distnace`, `use_distance: 5`,
`use_tolls: "x"`, `exclude_highways: "yes"` all returned the default route).
So `route_lib.validate_preset` now checks every option in `presets.json`
(main, fallback and candidates) against the 3.9.1 auto option list with
types and ranges, and the test also requires each preset to change at least
a minimum number of the 21 trips (Shortest 15, Simpler 3, Eco 5, Avoid
highways 10, Avoid tolls 4), so a typo cannot pass silently.

"Trips changed" below means the preset's path overlaps the Fastest path by
less than 97% (share of ~40 m grid cells, `route_lib.overlap`, taken both
ways). A trip whose path stays the same but whose maneuver count changes is
not counted: for Simpler, B2 goes from 21 to 20 maneuvers on the same path,
so 7 trips differ in time, distance or maneuvers but 6 count as changed.

Tuning sweeps (`route_eval.py tune`, 21 trips, numbers relative to Fastest):

| candidate | trips changed | time | distance | maneuvers |
|---|---|---|---|---|
| `maneuver_penalty` 30 / 60 / 120 / 300 | 5 / 7 / 10 / 14 | +1.3 / +1.5 / +5.1 / +7.1% | +0.8 / +1.6 / +6.1 / +7.2% | -0.5 / -0.5 / 0.0 / -0.2 |
| `use_distance` 0.3 / 0.5 | 3 / 9 | -0.4 / +0.4% | -2.0 / -3.7% | -0.4 / +0.4 |
| `top_speed` 105 (65 mph) | 8 | +6.9% | +0.9% | +2.8 (leaves 70 mph interstates: toll km 839→57) |
| `shortest:true` / `use_distance:1` | 20 / 20 | +22.2 / +19.3% | -8.3 / -8.2% | +7.6 / +7.2 |
| `use_highways` 0 / 0.25, `exclude_highways` | 16 / 16 / 14 | +45.4 / +32.0 / +25.5% | +12.2 / +3.4 / -0.4% | motorway km left: 24 / 48 / 0 |
| `use_tolls` 0, + `toll_booth_penalty` 600, `exclude_tolls` | 4 each | +3.6% | +1.0% | toll km 0 in all three |
| pool: fewest maneuvers within +5% time | 6 | -0.1% | -1.1% | -0.9, never worse |
| pool: shortest within +5% time | 10 | +0.2% | -3.9% | +0.5 |

Chosen presets and verdicts (`route_eval.py presets`):

| preset | options | trips changed vs Fastest | mean time | mean distance | verdict |
|---|---|---|---|---|---|
| Fastest | defaults | — | — | — | baseline. Valhalla's cost is not pure time: on 2 trips another preset was 1-7% quicker (U4 Cleveland 11.5 vs 12.3 min) |
| Shortest | `shortest: true` | 20/21 | +22.2% | -8.3% | clearly different. Uses local roads (+7.6 maneuvers) |
| Simpler | pool: Fastest + 2 alternates + `maneuver_penalty` 60 and 300, pick the fewest maneuvers within +5% time | 6/21 | -0.1% | -1.1% | modest but real on long trips (Erie→Harrisburg 18→13 maneuvers, Erie→Cleveland 15→11, Akron→Erie 19→16). Costs about 3 route calls. **`maneuver_penalty` alone is noise** and was dropped |
| Eco | `use_distance: 0.5` | 9/21 | +0.4% | -3.7% | weak proxy (no fuel model). Changes a third of trips with 2-10% less distance at about the same time |
| Avoid highways | `exclude_highways: true`, fallback `use_highways: 0` when no route is found or the result keeps > 0.5 motorway km (added after review, see below) | 14/21 | +25.5% | -0.4% | clearly different, 0 motorway km. Chosen over `use_highways: 0`, which also avoids trunk roads (+45% time) |
| Avoid tolls | `use_tolls: 0` | 4/21 (exactly the 4 trips with tolls) | +3.6% | +1.0% | works: Philadelphia→Pittsburgh 277→350 min, Carlisle→King of Prussia 98→122 min, 0 toll km |

**Avoid highways when the car is already on a motorway** (fixed after
review). In 3.9.1 `exclude_highways` only forbids driving *onto* a motorway
(`valhalla/sif/dynamiccost.h`, `CheckExclusions`: "isDriveOnto"), which is
what the docs mean by "allowed to start and end with highways". A trip that
starts on a motorway may therefore stay on motorways all the way, and the
first version of the preset did:

| trip (start on the motorway, no heading) | Fastest | Avoid highways, before | Avoid highways, now |
|---|---|---|---|
| I-90 2.5 km inside PA → Erie | 48.3 km, 46.7 motorway km | identical to Fastest | 42.4 km, 1.9 motorway km (next exit) |
| I-90 in PA → I-90 in OH (5 km) | 14.6 km, 13.1 motorway km | 116.5 km, 115.3 motorway km | 17.1 km, 4.2 motorway km |
| I-80 in PA → Sharon | 14.8 km, 13.5 motorway km | identical to Fastest | 16.4 km, 4.6 motorway km |

The preset now has `"fallback_when": {"highway_km_over": 0.5}`: when the
excluded route still has more than 0.5 motorway km, `run_preset` also routes
with the fallback (`use_highways: 0`) and keeps the one with fewer motorway
km. On the 21 trips (none starts on a motorway) this adds no request.

**Avoid tolls has the same blind spot and no fix in 3.9.1:** from a point on
the PA Turnpike near the Ohio line to Pittsburgh, Fastest, `use_tolls: 0`,
`exclude_tolls: true` and `use_tolls: 0` with `toll_booth_penalty: 3000`
all return the same 76.0 km with 42.9 toll km. `exclude_tolls` has the same
drive-onto rule. Left open (see "Review and fixes").

Per-trip results (`route_eval.py presets`, regenerated after the fixes).
Cell: time / distance / maneuvers (turns etc., excluding depart and
arrive); `=` means the same path as Fastest (overlap ≥ 97%), otherwise the
overlap share. Trips: 5 urban, 2 regional, 4 interstate, 2 PA Turnpike, 3
rural, 5 cross-border (`trips.json`).

| trip | kind | fastest | shortest | simpler | eco | avoid_highways | avoid_tolls |
|---|---|---|---|---|---|---|---|
| U1 | urban | 8 min / 5.8 km / 7 m | 10 min / 5.3 km / 7 m (45%) | 8 min / 5.8 km / 7 m = | 8 min / 5.8 km / 7 m = | 9 min / 5.6 km / 7 m (72%) | 8 min / 5.8 km / 7 m = |
| U2 | urban | 3 min / 2.3 km / 3 m | 3 min / 2.3 km / 3 m = | 3 min / 2.3 km / 3 m = | 3 min / 2.3 km / 3 m = | 3 min / 2.3 km / 3 m = | 3 min / 2.3 km / 3 m = |
| U3 | urban | 9 min / 8.0 km / 4 m | 11 min / 7.2 km / 8 m (11%) | 9 min / 8.0 km / 4 m = | 9 min / 8.0 km / 4 m = | 9 min / 8.0 km / 4 m = | 9 min / 8.0 km / 4 m = |
| U4 | urban | 12 min / 12.2 km / 9 m | 13 min / 7.5 km / 1 m (3%) | 11 min / 8.0 km / 7 m (8%) | 11 min / 8.0 km / 7 m (8%) | 11 min / 8.0 km / 7 m (8%) | 12 min / 12.2 km / 9 m = |
| U5 | urban | 5 min / 3.8 km / 6 m | 7 min / 3.0 km / 9 m (18%) | 5 min / 3.8 km / 6 m = | 5 min / 3.8 km / 6 m = | 5 min / 3.8 km / 6 m = | 5 min / 3.8 km / 6 m = |
| R1 | regional | 25 min / 24.6 km / 8 m | 31 min / 22.6 km / 8 m (10%) | 25 min / 24.6 km / 8 m = | 25 min / 24.6 km / 8 m = | 30 min / 23.2 km / 14 m (8%) | 25 min / 24.6 km / 8 m = |
| R2 | regional | 75 min / 88.4 km / 10 m | 86 min / 85.9 km / 16 m (54%) | 75 min / 88.4 km / 10 m = | 76 min / 87.4 km / 10 m (92%) | 86 min / 85.9 km / 12 m (54%) | 75 min / 88.4 km / 10 m = |
| I1 | interstate | 284 min / 478.7 km / 18 m | 323 min / 433.5 km / 34 m (50%) | 288 min / 479.8 km / 13 m (93%) | 300 min / 438.0 km / 24 m (60%) | 409 min / 514.1 km / 26 m (3%) | 284 min / 478.7 km / 18 m = |
| I2 | interstate | 279 min / 455.0 km / 16 m | 338 min / 441.1 km / 46 m (56%) | 279 min / 455.0 km / 16 m = | 283 min / 449.1 km / 18 m (89%) | 434 min / 515.8 km / 46 m (1%) | 279 min / 455.0 km / 16 m = |
| I3 | interstate | 133 min / 232.4 km / 14 m | 179 min / 222.1 km / 21 m (46%) | 134 min / 229.1 km / 13 m (79%) | 133 min / 232.4 km / 14 m = | 205 min / 232.3 km / 25 m (1%) | 133 min / 232.4 km / 14 m = |
| I4 | interstate | 128 min / 186.3 km / 13 m | 152 min / 180.5 km / 34 m (46%) | 127 min / 210.8 km / 11 m (49%) | 128 min / 186.3 km / 13 m = | 162 min / 195.6 km / 20 m (9%) | 128 min / 186.3 km / 13 m = |
| T1 | turnpike | 277 min / 491.2 km / 16 m | 410 min / 467.8 km / 48 m (2%) | 277 min / 491.2 km / 16 m = | 277 min / 491.2 km / 16 m = | 429 min / 492.6 km / 43 m (1%) | 350 min / 531.5 km / 31 m (10%) |
| T2 | turnpike | 98 min / 171.9 km / 14 m | 108 min / 167.5 km / 19 m (81%) | 98 min / 171.9 km / 14 m = | 98 min / 171.9 km / 14 m = | 175 min / 181.8 km / 29 m (1%) | 122 min / 178.0 km / 18 m (2%) |
| X1 | rural | 54 min / 72.5 km / 2 m | 54 min / 68.7 km / 2 m (78%) | 54 min / 72.5 km / 2 m = | 54 min / 68.7 km / 2 m (78%) | 54 min / 72.5 km / 2 m = | 54 min / 72.5 km / 2 m = |
| X2 | rural | 44 min / 52.0 km / 5 m | 64 min / 49.4 km / 16 m (46%) | 44 min / 52.0 km / 5 m = | 44 min / 52.0 km / 5 m = | 44 min / 52.0 km / 5 m = | 44 min / 52.0 km / 5 m = |
| X3 | rural | 14 min / 15.2 km / 4 m | 17 min / 11.6 km / 13 m (5%) | 14 min / 15.2 km / 4 m = | 15 min / 11.6 km / 10 m (5%) | 14 min / 15.2 km / 4 m = | 14 min / 15.2 km / 4 m = |
| B1 | cross_border | 98 min / 165.6 km / 15 m | 139 min / 157.0 km / 18 m (2%) | 100 min / 167.1 km / 11 m (95%) | 98 min / 164.5 km / 13 m (97%) | 162 min / 183.6 km / 14 m (0%) | 98 min / 165.6 km / 15 m = |
| B2 | cross_border | 69 min / 109.6 km / 21 m | 86 min / 102.9 km / 26 m (2%) | 70 min / 109.7 km / 20 m = | 70 min / 107.9 km / 24 m (95%) | 90 min / 104.2 km / 25 m (1%) | 77 min / 115.7 km / 24 m (16%) |
| B3 | cross_border | 128 min / 216.8 km / 20 m | 168 min / 200.5 km / 26 m (2%) | 128 min / 216.8 km / 20 m = | 128 min / 216.8 km / 20 m = | 183 min / 208.7 km / 29 m (1%) | 147 min / 226.8 km / 30 m (11%) |
| B4 | cross_border | 123 min / 203.9 km / 19 m | 159 min / 193.5 km / 25 m (43%) | 122 min / 202.2 km / 16 m (96%) | 122 min / 201.0 km / 15 m (95%) | 178 min / 215.4 km / 26 m (1%) | 123 min / 203.9 km / 19 m = |
| B5 | cross_border | 10 min / 12.3 km / 5 m | 11 min / 12.2 km / 8 m (91%) | 10 min / 12.3 km / 5 m = | 10 min / 12.3 km / 5 m = | 10 min / 12.3 km / 5 m = | 10 min / 12.3 km / 5 m = |

### Alternates, multi-stop, stop types

- **Alternates** (`alternates: 2`, the default `service_limits.max_alternates`
  is 2): 5 of 6 two-location trips returned 2 alternates; Erie→Cleveland
  returned 1. They were 3-23% slower, with 3-67% overlap with the main route.
  Asking for 3 returns 2 without an error or warning (clamped). With 3
  locations and `alternates: 2` the route comes back with 0 alternates and no
  warning (the docs say multipoint alternates are not supported, and neither
  are time-dependent ones).
- **Four stops** Erie → Meadville → Franklin → Pittsburgh: 3 legs
  (68.1 / 43.6 / 137.7 km), 160 min, 67 ms. `optimized_route` kept the order.
- **Stop types**, Harrisburg → Hersheypark (middle) → Lancaster. The middle
  point snaps to a dead-end service road:

  | type | legs | arrive/depart at middle | u-turn | result |
  |---|---|---|---|---|
  | `break` | 2 | yes ("destination on the left", then "drive northwest") | at the stop, inside the leg change | 64.4 min |
  | `via` | 1 | no | "Make a left U-turn" at the stop | 64.4 min, same path as break |
  | `through` | 1 | no | not allowed at the stop; drives on and turns at the end of the road | 68.4 min |
  | `break_through` | 2 | yes | not at the stop (as through) | 68.4 min, same path as through |

  Guidance for the app: a user stop is `break`. A "drive past this point" is
  `through` (or `via` if turning around there is fine). A
  `break_through` is a stop that must be approached and left in the same
  direction (for example a fuel stop on a divided road).

## 5. Matrix for search-along-route

Trip I1 Erie→Harrisburg (479 km). 40 synthetic targets 400-1500 m beside the
route, evenly spaced over the whole route or over the first 100 km. Times are
warm medians on this PC; there is no POI index yet (other track). Re-run
after the fixes, with the first run in brackets; the null counts were the
same in both runs, the times moved by up to about 60% with the load.
The new last row is the device as `packs.py` writes it (PA+OH tar, settings
from `device_config.json`).

| config | 1x40 whole | 40x1 whole | 1x40 next 100 km | 40x1 next 100 km |
|---|---|---|---|---|
| default (select_optimal → costmatrix) | 516 (672) ms, **19 nulls** | 579 (706) ms, **22 nulls** | 289 (283) ms, 1 null | 639 (724) ms, **40 nulls** |
| costmatrix, `max_matrix_distance` 1,000 km + `allow_second_pass` | 952 (1,266) ms, 0 nulls | 930 (1,216) ms, 0 nulls | 331 (435) ms, 0 nulls | 1,486 (2,028) ms, 0 nulls |
| timedistancematrix | 10,040 (14,966) ms, 0 nulls | 7,369 (19,457) ms, 0 nulls | 2,547 (2,560) ms, 0 nulls | 12,148 (12,799) ms, 0 nulls |
| device config (the tuned costmatrix settings, on the device's PA+OH tar) | 1,555 ms (first call 2,575), 0 nulls | 1,241 ms, 0 nulls | 318 ms, 0 nulls | 1,434 ms, 0 nulls |

Cause of the nulls: `src/thor/costmatrix.cc` sets
`current_pathdist_threshold_ = max_matrix_distance / 2` and prunes each tree
past that **path** distance. Raising `max_matrix_distance` alone left 1-2
nulls. The second pass (destination-only roads opened, hierarchy relaxed)
removed those and adds warning 400 naming the connections it fixed. Config
keys that matter in 3.9.1:

- `service_limits.auto.max_matrix_distance` (default 400,000 m). This is also
  the straight-line request limit.
- `service_limits.auto.max_matrix_location_pairs` (default 2,500).
- `thor.source_to_target_algorithm` (`select_optimal` | `costmatrix` |
  `timedistancematrix`).
- `thor.costmatrix.allow_second_pass` (default false),
  `thor.costmatrix.check_reverse_connection`, `max_iterations` (2800),
  `min_iterations` (100), `dijkstra_distance` (0), `max_reserved_locations`
  (25).
- `thor.costmatrix.hierarchy_limits.max_up_transitions.{1,2}` (400, 100) and
  `.expand_within_distance.{0,1,2}` (1e8, 20,000, 5,000).
- `service_limits.hierarchy_limits.costmatrix.max_allowed_up_transitions` and
  `.max_expand_within_distance` (caps on request overrides; only applied if
  `allow_modification` is true).

Recommendation: costmatrix with the two changes above, and targets limited to
the next stretch of road (smaller searches). Both changes are now in
`device_config.json`; the test opens the device engine and asserts 0 nulls
for 1x40 and 40x1 over the whole 479 km trip.

## 6. Measurements

All on this PC with other heavy jobs running.

Memory method: psutil sampled every 0.25 s over the process tree. "Peak
working set" is Windows' own per-process peak (resident pages, including
memory-mapped files). "Peak private" is Windows' peak commit charge (memory
that has to be backed by RAM or page file). Valhalla's build memory-maps its
temporary files, so working set is much larger than private.

| step | wall time | peak working set | peak private | output |
|---|---|---|---|---|
| merge PA+OH (pyosmium, Python loop) | 662 s (940 s in the reproducing review, identical output) | 221 MiB | 220 MiB | 679.4 MB PBF |
| timezone DB | 4.1 s | 122 MiB | 125 MiB | 122.2 MB |
| boundary fetch (OSM API) + merge | 52 s fetch, 4.6 s merge | 55 MiB | 51 MiB | 1.8 MB PBF |
| `valhalla_build_admins` | 37.9 s (13.4-13.6 s in two review re-runs) | 545-546 MiB | 1,160 MiB | 10.9 MB |
| `valhalla_build_tiles -j 4` (final) | **507 s** | **5,814 MiB** | **2,204 MiB** | 1,212.3 MB, 522 tiles |
| same, first build (Ohio-only admins) | 613 s | 5,761 MiB | 2,500 MiB | 1,176.4 MB |
| same, two PBFs unmerged | 689 s | 5,706 MiB | 2,356 MiB | 1,178.8 MB |
| `valhalla_build_extract` per state | 2.0-2.8 s | 49 MiB | 383 MiB | see below |

The build working set is just under the 6 GB budget. Valhalla has no memory
cap; `max_cache_size` was set to 512 MiB per thread. Stage times of the final
build: parse ways 123 s, parse nodes 50 s, build 44 s, enhance 55 s, filter
(dropping walk/bike-only edges) 123 s, hierarchy 36 s, shortcuts 15 s,
restrictions 23 s, validate 20 s.

Tile sizes (bytes / 10^6):

| | level 0 (4°) | level 1 (1°) | level 2 (0.25°) | total |
|---|---|---|---|---|
| combined PA+OH | 7 tiles, 63.9 MB | 42 tiles, 160.4 MB | 473 tiles, 988.0 MB | 522 tiles, 1,212.3 MB |
| PA pack | 6, 58.0 MB | 26, 76.9 MB | 254, 499.1 MB | 286 tiles, 634.4 MB tar |
| OH pack | 3, 34.9 MB | 20, 99.1 MB | 216, 509.2 MB | 239 tiles, 643.7 MB tar |
| shared | | | | 15 tiles, 64.8 MB |

Download size of the PA pack: gzip -6 240.4 MB (37.9%, 46 s single thread);
xz -6 171.3 MB (27.0%, 409 s single thread). The engine needs the tar
uncompressed, so install would decompress.

Driving-only graph (`include_pedestrian=false`, `include_bicycle=false`):
the same input with Valhalla's default (walking and cycling edges kept,
`build.py tiles-allmodes`) gives 1,556.8 MB (L0 67.3, L1 167.2, L2
1,322.3 MB). Driving-only is therefore **22% smaller**. The all-modes build
took 397 s, 5,775 MiB working set, 2,540 MiB private.

Build disk use (`build.py tiles-disk`, stops before the cleanup stage and
measures): **5.49 GB** = 4.27 GB of temporary `*.bin` files (way_nodes 1.35,
ways 1.24, nodes 0.71, edges 0.28, edge shapes 0.27, node maps 0.37 GB, ...)
plus 1.21 GB of tiles. That is the footprint at the end of the build; the true
peak may be a little higher. Add the 0.68 GB merged input and the 1.28 GB of
pack tars.

Build reproducibility: that second build from the same input produced the
same 522 tiles with the same total size, but **all 522 differ in the header
build id, and 64 differ in content** (multi-threaded build order). A rebuild
of the same snapshot is therefore *not* compatible with packs from the first
build, which is one more reason `packs.py` refuses mixed `graph_build` ids.

Route latency and memory, from `route_eval.py latency`. Each run is a fresh
process with pyvalhalla `Actor`, 3 processes per layout. "Process-cold" is
the first route in the process: tiles not yet in Valhalla's cache, OS file
cache warm. "Warm" is the median of 5 repeats. Re-measured after the fixes:
the tar column now uses the device as `packs.py` writes it (PA+OH, the new
`device_config.json` settings, opened through `packs.engine_config`); the
tile-dir column is the combined build directory. First-run values (before
the fixes) in brackets.

| route | km | tar (device layout): process-cold / warm | tile dir: process-cold / warm |
|---|---|---|---|
| U1 Pittsburgh downtown→Oakland (first in process) | 5.8 | 143-159 / 19-23 ms (174-221 / 22-29) | 225-238 / 18-21 ms (251-313 / 21-36) |
| U3 Erie→Presque Isle | 8.0 | 13-14 / 11 ms (19-21 / 12-13) | 20-21 / 10-11 ms (22-43 / 11-15) |
| R1 Harrisburg→Hershey | 24.6 | 26-27 / 21-24 ms (30-38 / 25-40) | 52-57 / 20-23 ms (54-76 / 22-27) |
| B3 Cleveland→Pittsburgh | 217 | 67-106 / 64-73 ms (90-113 / 89-95) | 107-109 / 66-70 ms (117-134 / 68-84) |
| I1 Erie→Harrisburg | 479 | 53-61 / 46-56 ms (68-115 / 54-73) | 84-94 / 45-47 ms (92-134 / 52-58) |
| T1 Philadelphia→Pittsburgh | 491 | 94-107 / 80-90 ms (111-135 / 96-146) | 150-157 / 80-81 ms (184-206 / 92-124) |

The second run was a little faster across the board, most likely lighter
load from the other jobs; the device settings do not touch route search.
Actor creation: 5.5-6.2 ms (tar), 8.9-9.8 ms (dir); first run 7-14 ms.
Process memory after those routes (unchanged): tar layout RSS 126 MiB,
private 173 MiB; tile-dir layout RSS 332 MiB, private 427 MiB (tiles are
copied into the heap cache). Python alone is about 34 MiB. The tar layout is
clearly better for a phone.

## 7. Whole-US estimate (estimate, not measured)

Basis: Geofabrik `us-latest.osm.pbf` is listed at 11.4 GiB (download page,
2026-11-15), against 0.63 GiB for the merged PA+OH input, a factor of about
18. I assume tile size and build time scale with input size, which is crude:
the share of roads in an extract varies by state. Treat the figures as
±30-50%.

| item | PA+OH measured | US estimate | basis |
|---|---|---|---|
| tiles (driving only, uncompressed) | 1.21 GB | **about 22 GB** (15-30) | x18 |
| sum of state packs (border tiles duplicated) | 1.28 GB (+5.6% over combined) | about 24-26 GB | x18, plus about 10-15% duplication because 4° level-0 tiles touch many states |
| download, xz | 27% of tar | about 6-7 GB for all states | ratio measured on PA |
| build time, 4 threads | 507 s | **about 2.5 h** (1.5-4 h) | x18, linear |
| build RAM | 2.2 GiB private / 5.8 GiB working set | private about 8-20 GiB; working set uses whatever RAM there is (file-backed) | the 4 x 512 MiB tile caches are fixed; the rest scales with data. A 32 GB machine is plausible, not proven |
| build disk | 5.49 GB at end of build (temp + tiles), plus input | **about 100 GB** (+12 GB input, +25 GB packs) | x18 |
| merge step | 662 s | not needed | use the single US extract |
| admin DB | 10.9 MB | about 20-30 MB, US + 50 states + DC via OSM API | builder-only |
| timezone DB | 122 MB | same file (world) | builder-only |

## Failures, dead ends, unverified

- **Overpass API was down** (504/502 on three tries) for the snapshot-time
  boundary query. The OSM API fallback worked and the boundaries are proven
  unchanged since the snapshot (no element newer than the snapshot
  timestamp).
- **Two PBFs to `valhalla_build_tiles`:** works but duplicates edges (section 2).
- **Admins from state extracts:** only Ohio, see section 2. Fixed with fetched
  boundaries.
- **`maneuver_penalty` for Simpler:** noise; replaced by a candidate pool.
- **`top_speed` for Eco:** below 113 km/h it pushes routes off 70 mph
  interstates. Rejected.
- **Wheel console scripts** (`Scripts/valhalla_build_*.exe`) do not work
  unless the venv is activated (PATH lookup). Worked around; worth an
  upstream issue.
- **`tiles-allmodes` first run** stopped with a non-zero exit and no error
  message during the enhance stage. At the time my measuring code was walking
  the tile directory every 2 s to sample disk use. The rerun without that
  poller passed. The cause is probably the poller touching files while
  Valhalla rewrote them on Windows, but that is not proven. The poller was
  removed; disk use is now measured by stopping the build before cleanup
  (`tiles-disk`).
- **Overpass attic path** (`fetch_boundaries.py`, first choice): still
  failing at the end of the session (`RuntimeError: Overpass query failed`),
  so only the OSM API fallback has been run end to end.
- **Avoid tolls from a point on a toll road** keeps the Fastest route
  (section 4). No 3.9.1 option changes that; open.
- **Dir-layout updates are not atomic** (tiles are replaced one by one);
  an engine opened during an update could see a mix of builds. The tar layout
  swaps the whole file at once. Open; another reason to use the tar layout.
- **Rebuilds are not byte-identical, also for the admin DB:** the review's
  re-run of `build.py admins` (to test the new input signatures) gave the
  same 11 areas and the same size, 10,854,400 bytes, but different bytes.
  The tiles were not rebuilt.
- **Not verified:**
  - Android itself (no Android SDK here): valhalla-mobile 0.6.4 loading these
    exact tars, and on-phone latency and memory.
  - Reading the packs on a comma 3X (aarch64 wheel exists; not run).
  - Time-dependent routing (no traffic data).
  - The whole-US numbers (estimates).
  - Truly cold-disk latency: Windows file cache could not be flushed without
    admin rights.
  - The tile build (507 s, 5.8 GiB working set) and the merge were not re-run
    after the review; nothing in the fixes touches them. Their numbers come
    from the first engineer's logs and stamps (checked by the reproducing
    reviewer).
  - Updating packs on a phone: the update rules are implemented and tested in
    `packs.py` (Python), not in the Android app.

## Ideas and code read

Only Valhalla's own docs and source (MIT) were read: `pbfgraphparser.cc`,
`util.cc`, `admin.cc`, `autocost.cc`, `dynamiccost.{h,cc}`, `costmatrix.cc`,
`matrix_action.cc`, `loki/worker.cc`, the route API
reference (for the option list in `route_lib.AUTO_OPTIONS_3_9_1`), and the scripts
`valhalla_build_timezones` and `valhalla_build_extract`. No code from other
projects was copied. No OsmAnd or Organic Maps code, data or formats were
used.

## Licences (checked at the primary source)

| item | licence | ships to device | checked at |
|---|---|---|---|
| pyvalhalla 3.9.1 (Valhalla build tools + bindings) | MIT | no | PyPI metadata, valhalla/valhalla COPYING |
| libraries vendored in the pyvalhalla wheel (SpatiaLite MPL/GPL/LGPL tri-licence, GEOS LGPL-2.1, PROJ, libxml2, zlib, lz4, protobuf, Boost, ZeroMQ, curl, sqlite, ...) | various open source | no (builder only) | wheel contents |
| Valhalla 3.9.1 inside valhalla-mobile | MIT | yes (other track) | COPYING at tag 3.9.1 |
| valhalla-mobile 0.6.4 | MIT; native deps per its vcpkg.json: protobuf, Boost, rapidjson, lz4, abseil, robin-hood-hashing, unordered-dense, none GPL/LGPL | yes (other track) | Maven POM, LICENSE.md, src/vcpkg.json at tag 0.6.4 |
| osmium (pyosmium) 4.3.2 | BSD-2-Clause | no | PyPI |
| shapely 2.2.0 | BSD-3-Clause | no | PyPI |
| psutil 7.2.2 | BSD-3-Clause | no | PyPI |
| numpy 2.5.4 | BSD-3-Clause AND 0BSD AND MIT AND Zlib AND CC0-1.0 | no | PyPI |
| requests 2.34.2 / urllib3 2.8.0 / idna 3.20 / certifi 2026.10.23 / charset-normalizer 3.5.2 | Apache-2.0 / MIT / BSD-3-Clause / MPL-2.0 / MIT | no | PyPI |
| OpenStreetMap data (extracts, boundaries) | ODbL 1.0 | yes (routing tiles are a derived database) | openstreetmap.org/copyright |
| timezone-boundary-builder 2026d | data ODbL 1.0 (code MIT) | no (builder only; zone ids end up in tiles) | project README |

## Decisions for the project owner

1. Ship packs as tar extracts, with every border tile in both neighbouring
   packs, one copy per tile on the device, and refuse mixed builds.
   *Recommended.* It costs about 5% extra download for PA/OH; more at the
   4° level for small states.
2. One national graph build per release, cut into state packs, with state
   packs updated together. *Recommended.* The alternative is per-state builds
   with neighbour buffers, but those cannot share border tiles safely.
3. The Simpler preset adds about 3 route calls per request for a modest gain.
   *Recommended:* keep it, but show it only for long trips, or drop it.
4. Eco is a distance/time blend, not a fuel model. *Recommended:* label it
   "Eco (shorter, similar time)" or drop it until a real consumption model
   exists.
5. Device config: allow hard exclusions, raise
   `service_limits.auto.max_matrix_distance` to 1,000 km and enable
   `thor.costmatrix.allow_second_pass`. *Recommended;* now written by
   `packs.py` from `device_config.json`, so the phone track can copy it.
6. For a US build, build admins from fetched complete relations (US + 50
   states + DC), not from the extract. *Recommended.* Also file upstream
   issues for the missing `valhalla_build_timezones` and the wrapper PATH bug.
7. Pack updates: one release = all installed packs updated in one call
   (`install pa oh`), with the tar layout on the phone because it swaps
   atomically. *Recommended.* The phone app has to implement the same
   rules as `packs.py` (graph build, engine version, per-tile SHA-256).
8. Destination outside the installed packs: treat errors 170 and 171 the
   same ("not in your installed maps") and warn when a route end is more than
   1 km from the requested point. *Recommended.*

No changes outside my folders were needed.

## Review and fixes

Two independent reviews ran after the first build: "reproduce" (re-ran the
work) and "break" (attacked it). Both said "sound with fixes". I checked
each blocker and major finding myself before changing code (probe scripts in
the session scratchpad, results quoted below); there were no blockers. No
finding turned out to be wrong, so none is disputed.

| # | review | severity | finding | what happened |
|---|---|---|---|---|
| 1 | both | major | Installing a state that is already installed (a pack update) kept the old tiles but recorded the new manifest | **Confirmed**: installed PA, then a fake PA with a new `graph_build` and one changed tile: `tiles_added 0`, the changed tile on the device was still the old one, `packs/pa.json` showed the new build. **Fixed**: an installed state is replaced (new bytes win, tiles only the old copy listed are removed); several packs install in one call for a release update, checked against the packs that stay. Tests `test_step3_pack_update_{tar,dir}_layout`: PA+OH build A → refused for PA alone → PA+OH build B in one call (2 changed tiles, 1 dropped, tile set equals the new manifests) → OH uninstalled → PA back to build A (dropped tile returns). |
| 2 | break | major | No engine-version gate: a pack for another Valhalla version installs, and Valhalla does not reject such tiles | **Confirmed**: a PA pack labelled 3.10.0 installed on an empty device. **Fixed**: install compares the manifest's `valhalla_version` and every tile header's version with the engine (`valhalla.__version__` here; valhalla-mobile's Valhalla on the phone). Test `test_step3_refuses_other_engine_version` (manifest 3.10.0, and a tile whose header says 3.8.0 with consistent hashes, both layouts; nothing left on the device). |
| 3 | break | major | Avoid highways does nothing, or gives an absurd route, when the trip starts on a motorway | **Confirmed**: I-90 in PA → Erie identical to Fastest (46.7 motorway km); I-90 PA → I-90 OH 116.5 km against 14.6 km. Cause found in `dynamiccost.h` (exclusion only on driving onto a motorway). **Fixed** with a data rule `fallback_when: {highway_km_over: 0.5}` in `presets.json`: now 1.9 / 4.2 / 4.6 motorway km (section 4). Test `test_step4_avoid_highways_from_a_motorway_origin`. |
| 4 | break | major | PA-only into Ohio: error 170 comes before 171; the report named only 171 | **Confirmed** with my own sweep: 170 from -80.76 to -81.10, 171 from -81.20 along lat 41.20; `locate` showed why (section 3). **Fixed** in the report; the sweep is in `trips.json` and asserted in the step-3 tests. |
| 5 | reproduce | minor | README/report did not say where the inputs come from or that Python 3.12 is required | **Fixed**: prerequisites in the README and "How to rerun". |
| 6 | reproduce | minor | Wall times not reproducible within 15% on the shared machine | **Accepted**: the report now says times are ranges from a loaded machine; install and latency were re-measured (section 6). The tile build was not re-run. |
| 7 | reproduce | minor | The unmerged-vs-merged table mixes two merged builds; 2 repeated edges in the merged graph not mentioned | **Fixed** in section 2 (source of each row, the 2 repeats). Conclusion unchanged. |
| 8 | reproduce | minor | Simpler's "6 of 21 changed" depends on an undocumented 97% overlap rule | **Fixed**: definition in section 4 (7 trips differ in numbers, 6 in path). |
| 9 | reproduce | minor | Generated configs carry absolute home paths; skip logic is existence-only | **Fixed** for the device: `device/valhalla.json` has only relative paths (asserted by test). The builder's own `build/valhalla.json` keeps absolute paths: it is machine-local, gitignored, and written by `valhalla_build_config`. Stamps now record input file size/mtime and pinned values; a changed input rebuilds the step. Verified on `admins` (unchanged inputs: skipped; build config touched: "inputs changed ... rebuilding"). Stamps made before this change (tiles, packs) are trusted as they are. |
| 10 | break | minor | Far-away locations snap silently; `snap_m` was dead code; "does not snap far away" was false | **Confirmed** (Lake Erie 14.2 km, Binghamton 8.2 km, ...). **Fixed**: `endpoint_gap_m` from `trip.locations`, `far_snap_warnings` with the threshold in `presets.json`, report corrected. Test `test_step4_far_snap_is_flagged`. |
| 11 | break | minor | Layout (tar/dir) not stored; mixing layouts broke the device | **Fixed**: `device.json`, other layout refused, uninstall uses the stored one, an emptied device may choose again. Test `test_step3_layout_is_stored_on_device`. |
| 12 | break | minor | Tar-layout install did not check tile SHA-256 | **Fixed**: both layouts check every tile. Test `test_step3_tile_hashes_checked_in_both_layouts`. |
| 13 | break | minor | "Windows cannot replace a mapped tar" was wrong | **Confirmed** with my own probe (install OH under a live Actor worked). Report corrected. |
| 14 | break | minor | Device config lacked the recommended matrix settings; `requires_config` in `presets.json` was never read | **Fixed**: `device_config.json` + `requires_config` merged into the device config; test asserts every key and 0 matrix nulls with the device config. |
| 15 | break | minor | Preset options not validated; nothing guards Eco/Simpler from being noise or typos | **Fixed**: `validate_preset` (names, types, ranges for 3.9.1) and a minimum-changed-trips test per preset. |
| 16 | break | minor | Per-trip preset table only in a gitignored file | **Fixed**: the table is in section 4. |

Found while checking, left open: Avoid tolls from a point on the Turnpike
(no 3.9.1 option helps, section 4); dir-layout updates are not atomic.

Not done (outside this track or this machine): Android loading of the tars,
comma 3X run, cold-disk latency, a whole-US build.
