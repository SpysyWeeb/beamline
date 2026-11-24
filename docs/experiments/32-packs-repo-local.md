# 32. The packs repository, prepared and run locally (step P1a)

Date: 2026-11-21. Step P1a of `docs/packs-design.md` (sections 3, 4 and 7, with the owner's answers of
section 8 and of 2026-11-20: both repositories private for now, Pennsylvania only while private).
Nothing was pushed or created on GitHub; no git state was changed in either repository.

## Result in short

- **The packs repository is ready to push**: the sibling folder `beamline-packs`, 88 files, 988,323
  bytes, the largest 64 KB (`packbuilder/routing/route_lib.py`); no data. It is an export of this
  repository made by `tools/export_packs_repo.py`; its own files are written here under `packs-repo/`.
- **A full local run, exactly as the workflow runs it** (`python3 build/weekly.py run`, WSL Ubuntu
  24.04, inside a 6.5 GiB, no-swap, 2-CPU cgroup) built Pennsylvania from Geofabrik's extract of
  2026-11-20, passed every check and wrote the catalog site: **37.6 min wall, of which 10 min was
  this PC's slow download of the 351 MB extract; 28 min of building and checking.**
- **It fits GitHub's private-repository runner, with a thin memory margin**: the largest step (house
  numbers, `step_build.py`) peaked at **5.8 GiB resident** (5,959 MiB sampled for its process tree)
  in the first run, about 0.7 GiB under the 6.5 GiB limit, and less than that on a real 7 GB runner
  once its OS and agent take their share. After the review, `step_build.py` frees its working lists
  as it goes, which brings its peak to **4.75 GiB** (4,756 MiB; 4,867 MB `ru_maxrss`) with an identical
  part (section 4a). The workflow adds 4 GB of swap as insurance and keeps a
  one-setting switch to a self-hosted runner. **Correction (review):** the first run's log ends with
  `oom 1 oom_kill 1`, which the first version of this report left out. That kill was a separate
  limit test, ten minutes before the run, in the same reused cgroup. No build step was killed (section 4a).
- **Minutes:** about 55 min a run on GitHub (estimate below), **about 240 of the 2,000 free minutes a
  month** at one run a week; the first run, which fetches every slow input from its publisher, about
  100 min.
- **Pennsylvania's routing is built from its own extract** (graph build `261120-pa-v3.9.1-b58624`).
  Measured on the same 2026-11-14 extract against today's part: same 286 tiles, 7.9 % smaller
  (584.5 vs 634.4 MB), every difference at the Ohio line; routes inside Pennsylvania unchanged.

## 1. What was made

### In this repository

| File | What |
|---|---|
| `tools/export_packs_repo.py` | the export: copies exactly the files the weekly build needs (list `FILES`, paths kept; `android/scripts/read_pack.py` becomes `tools/read_pack.py`) plus `packs-repo/**`, writes `SOURCE.md` (this repository's commit, "do not edit here"), deletes anything else in the destination (never `.git`), refuses the whole export on one privacy finding; `--check` compares; `--extra-terms FILE` checks a local word list (kept outside both repositories) |
| `packs-repo/README.md`, `ATTRIBUTION.md`, `.gitignore` | the packs repository's front page (what, how often, where, licences, how the build works, how to run it), the ODbL attribution, the ignore list (`data/`, `.venv/`, pack files) |
| `packs-repo/.github/workflows/weekly.yml` | the workflow (section 3) |
| `packs-repo/build/weekly.py` | the one entry point: `setup`, `run`, `publish --github / --local`, `cleanup-drafts` (standard library only) |
| `packs-repo/build/config.json` | `"states": ["pa"]` (Ohio = add `"oh"`), threads 2, Java heap 3g, keep 2 builds, the checks' thresholds, the kept inputs |
| `packs-repo/build/check_routes.py`, `route_checks.json` | the test routes: 19 Pennsylvania trips (public landmarks of `trips.json`, the PA side of the Ohio and New York lines), lengths measured on the round-3 pack |
| `packs-repo/build/privacy_scan.py` | the generic privacy scan (also used by the export) |
| `packs-repo/build/release_to_archive.py` | a downloaded release to the `.tar` of "Use a file instead" (every part checked) |
| `packs-repo/build/setup_ubuntu.sh` | `python3.12-venv`, `openjdk-25-jdk-headless`, `git` |

**Builder changes for Linux and the weekly build** (environment overrides only; unset, every
builder behaves as before, so the Windows path is unchanged):

- `BEAMLINE_SNAPSHOT` (YYMMDD of the extracts) in `routing/common.py`, `map/build_pack.py`,
  `search/build.py`, `addresses/common.py` (they had `261114` written in).
- `BEAMLINE_ROUTING_STATES` (default `pa,oh`) in `routing/common.py` / `build.py`: the graph's states;
  one state uses its extract as it is (hard link, no merge); `graph_build` names the states
  (`<snap>-pa-v3.9.1-b…`, still `…-pa-oh-…` by default); the shared-tile statistics only with PA and OH.
- `BEAMLINE_THREADS`, `BEAMLINE_JAVA_HEAP` in `map/build_pack.py`.
- `map/build_profile.py` runs `sh <maven>/bin/mvn` off Windows (it called `mvn.cmd` only).
- `tools/fetch_osm_extracts.ps1`: **Geofabrik moved the US states into one folder each**
  (`north-america/us/pennsylvania/pennsylvania-latest.osm.pbf`); the old flat links now redirect to a
  404. Fixed here and in `weekly.py` (new layout first, the flat one second).
- `routing/README.md`: the two variables documented.

### The packs repository (as exported)

`README.md`, `ATTRIBUTION.md`, `SOURCE.md`, `.gitignore`, `.github/workflows/weekly.yml`, `build/` (7
files above), `tools/` (`fetch_map_tools.py`, `fetch_routing_env.py`, `read_pack.py`), and
`packbuilder/`: map (13 files: the build, profile patch, finish step, sources fetcher, pins), routing
(12: build, packs, route_lib, presets, device config, trips), search (19: the builder, query engine,
rule files, the three case files and their runner, the input checks), addresses (22: the six pack
steps, `addrlib`, sources and lock), pack (2), catalog (5). The evaluation-only scripts (screenshots,
size reports, address evaluations, search measurements, style tools) stay in the app repository.
**One file is changed on the way:** `packbuilder/routing/presets.json` loses its `note` / `*_note`
keys, which describe the owner's own test places (next-round items 18 and 19); no code reads them.

## 2. The weekly build (`build/weekly.py run`)

1. **Inputs restored** from the store (`--store-release inputs` on GitHub, `--store DIR` locally).
2. **Extract:** `<state>-latest.osm.pbf` + `.md5` + `.poly` from Geofabrik; the MD5 is checked (three
   tries); the replication timestamp in the file's header gives the snapshot (2026-11-20T21:21:21Z ->
   `261120`), which is the date every part's `osm_snapshot` must share (assemble refuses otherwise);
   all states of a build must be of one day.
3. **Refresh:** a map source is fetched again only when its publisher's `Last-Modified` differs and
   the kept copy is at least 28 days old; the Scranton points are fetched again every 28 days (the
   kept copy is used if that fails); the Census, TIGER, NAD and time-zone files are pinned by the
   builders and fetched once.
4. **Build:** map (`build_pack.py pa`), routing (`build.py all` with the build's states), search
   (`build.py --state pa`), house numbers (`run.py --only download nad extra osm tiger build`); work
   files deleted as soon as they are no longer needed.
5. **Assemble** (format 3, folder only; `pack_version` = the run's start, UTC minute).
6. **Checks** (all must pass): `tools/read_pack.py --verify`; the three search case files on the
   pack's own search and address files (thresholds in `config.json`: top-5 at least 94 / 93 / 95 %
   and, without data gaps, 96 / 95 / 96 %); `check_inputs.py`; the routing part installed as the app
   installs it (`packs.install`: every tile's SHA-256 and Valhalla version) and the 19 test routes
   (a route, ends within 500 m of a road, length within 25 % of the reference); not much smaller
   than the published pack (total at least 95 %, each part at least 90 %; skipped without a
   published catalog; `--allow-smaller` for a deliberate change).
7. **Catalog site** (`build_catalog.py --check`), `data/out/run.json` and a summary (the workflow
   run's summary page).

**Where the slow inputs live between runs: a release tagged `inputs`**, not the Actions cache. The
cache drops an entry not used for 7 days (a weekly run is right at that edge; one failed or late
week loses everything) and holds 10 GB a repository; release files stay until replaced, are
copied from GitHub's own storage in about a minute, and need no other secret than `GITHUB_TOKEN`.
Eleven assets, 2.9 GB: Natural Earth (446 MB), water and land polygons (933 and 954 MB), QRank,
pgf-encoding, the map sources' manifest, the Census files (tar), the time zones, and per state the
address downloads (tar), the NAD rows (gzip tar of the work database, so the weekly run never calls
the ArcGIS service) and the Scranton points. `inputs.json` indexes them (content identity, size,
upstream URL and Last-Modified). The Actions cache is used only for things that may vanish safely
(pip wheels, the Maven zip and wheel downloads, the compiled profile jar).

## 3. The workflow (`.github/workflows/weekly.yml`)

- **When:** `cron: "17 9 * * 1"`: Mondays 09:17 UTC = 4:17 AM US Eastern Standard Time, 5:17 AM in
  summer time (cron is UTC only; a minute past the hour avoids GitHub's top-of-the-hour queue).
  Geofabrik's extracts of Sunday evening are then ready. Plus `workflow_dispatch` (inputs `publish`,
  `allow_smaller`).
- **One job** (build, check, publish). Reason: a free private repository has 500 MB of Actions
  artifact storage, and one Pennsylvania release is 664 MB, so separate jobs could not hand the pack
  over; a single job also needs no second machine's setup. Measured disk: at most about 7.1 GB in
  `data/` plus 0.85 GB of virtual environments and 0.4 GB of tools, inside the runner's ~14 GB free
  even before the "free disk" step (which removes .NET, Android, GHC, the tool cache: ~25 GB more).
- **Steps:** checkout; **privacy scan (required, first)**; free disk; swap as insurance (4 GB);
  `setup_ubuntu.sh`; cache; `weekly.py setup`; `weekly.py run --store-release inputs`; `weekly.py
  publish --github --store-release inputs` (scheduled runs, or `publish` checked); on failure
  `cleanup-drafts` and the logs kept 7 days.
- **Publishing, only after every check passed** (`run.json` `checks_passed`): per pack build a
  release `<pack_id>-<pack_version>` created **as a draft**, its files uploaded and their sizes
  compared, then published; then the `catalog` release's `catalog.json` replaced (`--clobber`; the
  file is missing for the second or so of its upload, which a phone sees as "try again"); then pack
  releases beyond the newest two of each state deleted (never one the catalog names; `catalog` and
  `inputs` are never touched); then changed inputs saved. A failed run publishes nothing; a draft
  left by a failed upload is deleted. `permissions: contents: write`, `GITHUB_TOKEN` only.
- **Runner by one setting:** repository variable `PACKS_RUNNER` = `self-hosted` makes the job run on
  a self-hosted runner labelled `self-hosted, linux, x64, beamline-packs` (e.g. in this PC's WSL);
  unset, `ubuntu-24.04`. No runner was set up.

## 4. The full local run

`wsl -d Ubuntu-24.04 -u root -- bash data/p1a/wsl/full_run.sh` (the same entry point; the cgroup of
`data/p1a/wsl/limited.sh`: `memory.max` 6.5 GiB, `memory.swap.max` 0, `cpuset` 0-1), after
`python3 build/weekly.py setup` and with the store seeded from this PC's round-3 copies of the slow
inputs (`data/p1a/wsl/seed_store.sh`, through `weekly.py`'s own `save_inputs`), so the run is the
steady weekly one. Previous catalog: the round-3 site's (`data/catalog/site`).

| Step | Wall (s) | Peak resident (MiB) | Extra disk at its peak (MiB) | Fits 2 cores / 7 GB |
|---|---:|---:|---:|---|
| inputs restored (11 items, 2.9 GB, local copy) | – | – | – | yes |
| extract (351 MB, this PC's line) | 600 | – | – | yes (seconds on GitHub) |
| map (Planetiler, 2 threads, 3 GB heap) | 699 | 4,597 | 1,536 | yes |
| routing (PA alone, 2 threads; incl. 146 s Overpass boundaries) | 353 | 2,619 | 2,788 | yes |
| search | 225 | 2,826 | 527 | yes |
| house numbers (download, nad, extra restored; osm, tiger, build) | 294 | **5,959** | 2,004 | **yes, ~0.7 GiB under the cgroup limit; thin on a 7 GB runner (see 4a)** |
| assemble | 4 | 28 | 262 | yes |
| checks (read_pack, 3 search sets, inputs, 19 routes) | 12 | 151 | 558 | yes |
| catalog (`--check`) | 53 | 23 | 399 | yes |
| **total** | **2,256** (1,656 without the download) | | data/ at most ~7.1 GB | |

A clean re-run of the address steps in a fresh cgroup: osm 67 s / 2.26 GB, tiger 73 s / 0.72 GB,
build 132 s / **6.09 GB `ru_maxrss`**; `memory.events`: oom 0, oom_kill 0, `max` 7,955 (the page cache
was reclaimed at the limit). No step of the full run was killed, but the full run's own log does
show an OOM kill. Section 4a explains it.

**On GitHub** (estimate, not measured): Azure's 2 vCPUs taken as 1.5x slower than these two WSL
cores: ~41 min building and checking, + ~6 min setup (cached; ~10 uncached), ~2 min restoring the
inputs, ~1 min for the extract, ~3 min publishing (664 MB) and hashing the inputs: **about 55 min**,
4.35 runs a month: **about 240 min of the 2,000 free minutes**. The first run also downloads the slow
inputs from their publishers (2.5 GB of map sources at ~1 MB/s per connection, the NAD service's
~36 min measured in round 3): **about 100 min once.** All far inside the 6-hour job limit.

**Checks of the run** (`data/p1a/run-1.json`, `data/p1a/checks-1/`): read_pack `--verify` ok; search
cases 355 top-5 98.9 % (99.4 % without its 2 gaps), held-out 99 at 97.0 % (98.0 %), review 49 at
100 %; `check_inputs` 0 failed; 19 of 19 routes; size 0.963 of the published round-3 pack (routing
0.922 because Pennsylvania's routing is now built alone, see 5; map 1.002, search 1.005, addresses
1.000). Catalog serial 20261121071459.

**Published locally and fetched:** `weekly.py publish --local ~/p1a/published` wrote
`us-pa-202611210637/` (manifest, `routing.json`, `map.pmtiles`, `search.sqlite.gz`,
`addresses.sqlite.gz`, `routing.tar.gz`, release notes; 663.9 MB) and `catalog/catalog.json`; a second
publish of the same build was refused ("a published build is never rebuilt"); inputs unchanged, so
nothing saved. `serve_packs.py --root ~/p1a/published` + `catalog_client.py list` / `get … PA`:
663.9 MB fetched in 7.5 s (8 requests), `read_pack.py --verify`: checksums ok;
`release_to_archive.py` made `us-pa-2026-11-20.tar` (1,273,497,600 bytes), `read_pack --verify` ok.
`prune_list` on a made-up list keeps the two newest PA builds and never touches `catalog`, `inputs`
or another state.

### 4a. Review fix: the OOM kill in the first run's log, and the house-number memory

**What the log showed.** The first full run's log (`~/p1a/full_run.log` in WSL) ends with
`cgroup memory.peak: 7516192768 bytes; events: … max 20576 oom 1 oom_kill 1`. The first version of
this report said nothing was killed. That was incomplete.

**What was killed** (the kernel journal of that WSL boot, `journalctl -k -b -2`; the extract is in
`data/p1a/oom-evidence.txt`): the boot has **exactly one** OOM report. journald stored it three
times, but all three copies carry the same kernel time, 197.52 s = **01:27:40 EST**. The victim was a
lone `bash` + `python3` at 7.3 GB of anonymous memory. The memory cgroup was **`/p1a` with a limit
of 7 GiB** (`limit 7340032kB`). The full run started at 01:37:25 EST (`run-1.json` `started_utc`
06:37:25Z), **ten minutes after the kill**. Two facts tie that kill to the full run's log:

- The log's `memory.peak` is exactly 7 GiB. That is the old cgroup's limit, and it is above the
  full run's own 6.5 GiB limit, so the full run cannot have reached it.
- `limited.sh` at the time used the fixed cgroup `/p1a`, so the full run's `memory.events` still
  held the earlier kill.

The killed process was a separate limit test, not a build step; no step returned non-zero.
`limited.sh` was then changed to make a fresh cgroup per run, which is the version in `data/p1a/wsl/`.

**Re-run to confirm** (`data/p1a/wsl/oom_probe.sh`: the same full build in a fresh 6.5 GiB, no-swap,
2-CPU cgroup, with `memory.events` logged on every change and the kernel log captured):

| Run | Code | data/ | Wall (s) | House numbers: sampled peak / `ru_maxrss` | `memory.events` | Checks |
|---|---|---|---:|---|---|---|
| A | as first run | empty (like a runner; setup, then 11 inputs restored) | 1,509 (no download) | 5,959 MiB / 6,088.9 MB | **oom 0, oom_kill 0** | all pass |
| B | after the fix below | reused (A's folder) | 1,369 (no download) | **4,756 MiB / 4,867.0 MB** | **oom 0, oom_kill 0** | all pass |

In both runs `memory.peak` equals the limit (6,979,321,856 bytes), because the page cache fills the
cgroup and is reclaimed there (`max` 26,248 and 11,794 times). No kernel OOM line was logged
(`~/p1a/probe-{a,b}/`).

**The margin, corrected.** The first run's 5,959 MiB was about **0.7 GiB under the 6.5 GiB cgroup**,
not "~1 GB". A real 7 GB runner also gives part of its memory to its OS and the runner agent, so it
was thinner still.

**Fix at the cause** (`packbuilder/addresses/step_build.py`): free memory that is no longer needed.
- The raw OSM rows are freed once they are points.
- The OSM match index is freed after the extra sources are merged.
- Each point dict is freed as soon as it becomes an address row.

The step's peak drops by **1.2 GB** (6,088.9 to 4,867.0 MB `ru_maxrss`; 5,959 to 4,756 MiB
sampled), at the same speed (128 s). That leaves about 1.9 GiB under the 6.5 GiB cgroup. The
largest step is now the map at about 4.6 GiB. The **house-number part is identical**: every table
of A's and B's `addresses.sqlite` has the same rows (`data/p1a/cmp_sqlite.py`: addresses 2,039,397,
streets 402,240, ranges 1,429,993, edges, localities, places, schema). Only `meta.built_utc`
differs. The address tests pass (22 OK). The 4 GB swap step stays as insurance.

**Also found and fixed** (`build/weekly.py`): the address steps skip a step whose metrics file
exists. In a reused folder (a local run, or a self-hosted runner if its checkout keeps ignored files)
the weekly build would therefore have taken an earlier run's `osm`/`tiger`/`build` as done. That
happened in the first probe run here: the house numbers took 0.7 s. `build_parts` now deletes those
three metrics files before the address step. The kept inputs (`download`, `nad`, `extra`) bring
their own metrics. Run B shows the three steps rebuilt in a reused folder.

### Against the current pack (`data/r3-packs/us-pa-2026-11-14.tar`)

| Part | Round 3 (2026-11-14, joint graph) | Weekly (2026-11-20, PA alone) |
|---|---:|---:|
| map | 244,703,514 | 245,148,874 |
| routing | 634,429,440 (`261114-pa-oh-v3.9.1-b53087`) | 584,673,280 (`261120-pa-v3.9.1-b58624`) |
| routing_index | 51,259 | 49,513 |
| search | 154,705,920 | 155,422,720 |
| addresses | 288,145,408 | 288,186,368 |
| total | 1,322,035,541 | 1,273,480,755 |

Both pass `read_pack --verify`. Searches (`data/p1a/compare-search.txt`, near Erie): "Beach 10" now
finds "Beach 10 - Budny" (the round-3 pack predates batch F); "presque isle beach" gives the park's
beaches; "mcdonalds on peach st", "sheetz on peach street", "gas", "walmart erie" (from Pittsburgh),
"10 w 8th st" give the same first three as before; "waterfall" near Ricketts Glen finds its falls.
Routes: the 19 trips give the same lengths to the metre (T1 1 m less) except E1 (below).

## 5. Pennsylvania's routing from its own extract

Built with `BEAMLINE_ROUTING_STATES=pa` from the **same** 2026-11-14 extract the round-3 joint graph
used (`data/p1a/wsl/cmp_build.sh`, compared by `data/p1a/compare_routing.py`, result
`data/p1a/compare-261114.json`), so only the graph differs:

- **Size:** 584,478,720 vs 634,429,440 bytes (−7.9 %); tile build 131 s, 4.66 GB peak with 4 threads.
- **Tiles:** the same 286 (6 / 26 / 254 on levels 0 / 1 / 2); 273 of the same size; the 13 that differ
  all lie on the Ohio line (16 tiles there: 64.9 MB joint, 14.9 MB alone; the largest, level-0 tile
  `0/002/904`, 24 MB less). The 50 tiles on the New York line are identical (the joint graph had no
  New York data either).
- **Routes with only Pennsylvania installed:** to the PA side of the lines (I-90 and US-20 at Ohio,
  two roads at New York, I-80 near Sharon) the same, except **E1** (Erie to I-90 at the Ohio line):
  47.1 km alone, 62.2 km joint, which turned at an Ohio interchange; the weekly check keeps E1
  without a length reference. **Beyond the line:** Erie to Conneaut (3 km into Ohio) and Sharon to a
  point 15 km into Ohio routed on the joint part's Ohio roads; with PA alone the end snaps to the
  nearest Pennsylvania road (1.2 and 13 km away; the app's far-end warning applies). Points 20-23 km
  into Ohio failed with "unconnected regions" on the joint part and now end on a PA road 18-21 km
  away. New York (4 and 15 km in) fails on both.
- **Decision: Pennsylvania alone** while only Pennsylvania is published: smaller, nothing changes
  inside the state, and a published PA part no longer carries a slice of Ohio the user did not
  install. When Ohio is added (`"states": ["pa", "oh"]`), both are cut from one graph again, as the
  phone needs (it refuses two graph builds).

**On the phone:** a pack of graph build `261120-pa-…` is refused while the round-3 Ohio pack
(`261114-pa-oh-…`) is installed. To install a weekly PA pack, Ohio must be removed first.

## 6. Privacy

- `build/privacy_scan.py` (generic: home paths, private IPv4/IPv6, adb addresses, e-mail addresses
  other than GitHub noreply and example domains, token prefixes and assignments, odd-length hex and
  mixed-case runs, MAC addresses and serial assignments, binary or > 2 MB files): **88 files, 0
  findings**; a scratch file with one sample of each kind gave the 15 expected findings. It runs
  first in the workflow and inside the export.
- The app's scans against the packs folder: `check_network_rule.py privacy` (its `REPO` pointed at
  the folder): **0 hits**. `check_private_logs.py logcat` over every file: only its generic canaries,
  as expected for test data (the Erie landmark Perry Square's longitude in `trips.json`,
  `points.json`, `route_checks.json`, search cases; the words "airport", "art museum", "millcreek" in
  category lists and cases). None is a private place.
- A local list of specific terms (names, the owner's places of next-round items 9 and 16-18 and of
  `owner_case.py`, the home network prefix, device names), kept in `data/p1a/private-terms.txt` and
  never exported: `export_packs_repo.py --extra-terms` **clean**, after `presets.json`'s notes were
  left out (they named the owner's restaurant and car-wash cases). "4319 Peach Street", in the search
  cases and README, is a McDonald's (a public business), not a private place.
- No secret: the workflow uses only `GITHUB_TOKEN`; nothing from `data/secrets/` was read.

## 7. Not verified

- **Nothing ran on GitHub.** The workflow file parses (PyYAML) but its `gh` calls (draft, upload,
  undraft, `--clobber`, delete), `runner.environment`, the cache, the swap step and the disk freeing
  were not executed; `publish --github` and `cleanup-drafts` are untested. GitHub's runner speed is an
  estimate (1.5x).
- The first-ever run (empty `inputs` release: every slow input from its publisher, the NAD service)
  was not run in this form; the local store was seeded from this PC's round-3 copies.
- The refresh paths (an upstream file newer and 28 days old; the Scranton points after 28 days)
  were not exercised (the seeded copies are 6 days old).
- The cgroup imitates 7 GB / 2 cores but not GitHub's disk speed or its OS overhead exactly.
- After the review fix (4a), the local publish, serve, fetch and `.tar` proofs were not run again.
  The fix does not touch that code, and build B's catalog was written with `build_catalog --check`.
  The routing comparison and the comparison with round 3 were not run again either: their parts
  are the same size as in the first run.
- After the review (lead, 2026-11-21, not run on GitHub yet): the actions are pinned to commits; a
  failure to read the published catalog now stops the run unless the catalog release does not exist
  yet; a failed catalog upload turns the run's releases back into drafts, which the cleanup removes.
  The code's licence is MIT, the app's (owner, 2026-11-21): `LICENSE` is exported too.

## 8. Questions for the owner

1. **The code's licence**: answered 2026-11-21, MIT like the app. The data are ODbL / public domain
   as written.
2. **Ohio on the phone:** while only Pennsylvania is published, a weekly PA pack cannot sit next to
   the round-3 Ohio pack (different graph builds). Remove Ohio when installing a weekly PA pack, or
   keep using round-3 packs until Ohio is published too?
3. **Ohio's house numbers** (later): the NAD service needs about 9 h for Ohio, more than one job; keep
   Ohio without NAD points (as round 3) or fetch the national NAD file once into `inputs`?
4. **The `inputs` release** republishes the build's inputs (ODbL water polygons, public-domain Census
   and NAD files) once the repository is public; fine, or keep it out of sight (a private store)?
