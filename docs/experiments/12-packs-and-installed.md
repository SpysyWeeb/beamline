# Experiment 12: packs and map (track P, round 3)

Date: 2026-11-16. Track P of the feature round (`docs/architecture.md` 5.1 "P: packs and map";
`docs/architecture-downloads.md` 6.6 and 9.2; `docs/architecture-saved-places.md` 6.1 and 8).
Built in two stages: the first engineer did stage 1 and most of stage 2; the second engineer
re-ran every check, found and fixed one failing test (section 6), added the installer's rule tests
on the real archives, fixed the credit of the overview renders, ran `count_pois.py` on the Ohio
z14 pack, and finished the documents. Two reviewers then checked the track; a third engineer
fixed the seven findings of the contract review, each with a test (section 9), and proved the
track again. Outputs not in git: `data/r3-packs/` (the packs),
`data/r3-p/` (scratch, logs, `PROGRESS.md`), `data/art/` (sprites, views, contact sheet).

**Machine load:** the PC was shared with six other tracks (builds, emulators) the whole time. Every
time below is one run, wall clock, on a warm disk cache; treat it as +-50 %.

## Result in one paragraph

Pack format 3 exists end to end. `packbuilder/pack/assemble.py` (standard library only) builds the
Pennsylvania and Ohio archives and pack folders from one OSM snapshot (2026-11-14): map z14 with
profile patch 2, the existing routing graph build with its routing index, search and slim house
numbers for both states, each part with its own credit; `read_pack.py --verify` passes on both
archives and both folders. `:core:packs` reads format 3 only (a version-2 pack: "manifest_version 2
is an old pack format; rebuild the pack") and composes the map style from the installed packs'
`PackInfo` with their credit line. `:core:installed`'s `InstalledLibrary` implements every member
of `InstalledData`, the downloads hand-off included (folder install by moving, optional parts),
and its JVM tests pass on small synthetic packs and on the real PA and OH archives: 96 JVM tests in
`:core:packs` (61) and `:core:installed` (35), 0 failures, and every rule of the definition of done
(hold, busy, space, version 2, fingerprint, crash recovery at four points, generations) is tested
on the real archives as well as on the synthetic packs. The first-wave hand-overs were made early:
the sprites with the three saved-place icons, and the PA and OH search files with the street-key
fingerprint and `ranking_sha256`. The overview map in the app is the public-domain build with its
own credit, and interstates and state lines are visible at z3-5 (a draft for the owner).

## 1. What was built

| Area | Files | What |
|---|---|---|
| Sprites (first wave) | `data/art/sprites/`, `packbuilder/map/style-bundle/sprites/`, `android/app/src/main/assets/style/sprites/` | `saved_home`, `saved_work`, `saved_place` in light and dark, 1x (19 px) and 2x (38 px); contact sheet `assets/sheet/index.html` and `data/art/sheet/sheet-*.png` |
| Fingerprint | `packbuilder/addresses/addrlib/fingerprint_spellings.json` (new, 1,482 spellings), `packbuilder/addresses/key_fingerprint.py` (new) | `fingerprint = sha256(for each spelling: s TAB street_key(s) LF)`; `lf_sha256` |
| Search builder | `packbuilder/search/build.py` | `--out-dir`, `--work-dir`; meta `addr_key_table_version`, `addr_key_fingerprint`, `ranking_sha256`, `rules_sha256`; LF-normalised hashes |
| Address builder | `packbuilder/addresses/` (`common.py`, `sources.json`, `step_*.py`, `geoassign.py`) | any state of `sources.json` "states" (`BEAMLINE_ADDR_STATE`, `BEAMLINE_ADDR_DATA`, `BEAMLINE_ADDR_INPUTS`), a generic User-Agent, meta `key_fingerprint`, `normaliser_sha256`, `credit`; indexes `localities(city_key)`, `localities(muni_key)` (decision 6.19). `addrlib/` itself is unchanged (no request needed: the new list is a new file) |
| Assembler | `packbuilder/pack/assemble.py`, `tests/test_assemble.py`, `README.md` | format-3 folder and archive; refuses mixed snapshots, differing fingerprints, a file that differs from its builder's record, a wrong kind or state |
| Reader | `android/scripts/read_pack.py` = `docs/pack-format.md` section 6 | format 3, credit, absent parts of an installed folder, exit code |
| Format doc | `docs/pack-format.md` | rewritten for format 3 (`pack_version`, `"source": "download"`, parts, credit, fingerprints, install and its free-space sum, crash recovery, changes while guiding) |
| `:core:packs` | `PackManifest.kt`, `Readers.kt`, `PackScanner.kt`, `StyleComposer.kt` (+ tests) | manifest v3, required parts, part judgement, `InstallRecord` v3, `compose(..., List<PackInfo>)` (JVM name `composeInstalled`), `ComposedMap.creditLine` |
| `:core:installed` | `InstalledLibrary.kt` (stubs filled only), `InstalledCore.kt` (+ tests) | the one install and removal flow, folder install, optional parts, hold, generations, crash recovery |
| Map | `style_config.json` "overview_legibility", `generate_style.mjs`, `android/scripts/sync_assets.py`, `render_art.py`, `render/art_view.js`, `art_views.json`, `count_pois.py`, `build_sheet.py` | z3-7 interstates and state lines; the overview's own credit (in the app and in the renders); overview views; `--pack` and the Ohio places for the POI count |
| Retired | `tools/fetch_android_map_pack.py` | now only says to use `assemble.py` |

## 2. How it is proved (commands and what they showed)

All from the repository root. The results are the second engineer's runs (2026-11-16, night) unless
said otherwise; the runs after the review fixes are in section 9 (they supersede the counts here). After
every change of section 6.

| Command | Result |
|---|---|
| `python tools/check_contracts.py` | 39 contract files (26 with filled stubs, across all tracks), 20 excerpts, 5 data files "all the same"; 39 frozen files unchanged. `InstalledLibrary.kt`: "filled". |
| `sh tools/round/gradle.sh p :core:packs:test --rerun` | 61 tests, 0 skipped, 0 failures (`RealPacksManifestTest` read both real archives) |
| `sh tools/round/gradle.sh p :core:installed:test --rerun` | 27 tests, 0 skipped, 0 failures: `InstalledLibraryTest` 16 (2.3 s), `InstalledFolderTest` 5 (0.3 s), `RealArchivesInstallTest` 3 (49.5 s), `RealArchivesRulesTest` 3 (43.3 s). Before the fixes of section 6 the same command failed 1 of 23. After the review fixes: 35 tests, 0 failures (section 9). |
| `sh tools/round/gradle.sh p :routing:packs:test --rerun -Pbeamline.routingPacksWork=<repo>/data/r3-p/routing-packs-test` | 12 tests, 0 failures (its real-pack test writes 1.8 GB of test devices, so the work folder is given inside P's scratch and was deleted afterwards). After the review fixes (the module changed: section 9): 14 tests, 0 failures |
| `sh tools/round/gradle.sh p :routing:demo:compileDebugKotlin :map:demo:compileDebugKotlin` | BUILD SUCCESSFUL: the two modules of other tracks that compile `:core:packs` still compile |
| `python -m unittest discover packbuilder/pack/tests` | 6 tests OK (archive and folder verify, order, no house numbers, fingerprint and snapshot refusals, a changed file, version 2) |
| `.venv/addresses/Scripts/python.exe -m unittest discover -s packbuilder/addresses/tests` | 22 tests OK |
| `python android/scripts/read_pack.py data/r3-packs/us-pa-2026-11-14.tar --verify` (and `us-oh-2026-11-14.tar`, `data/r3-packs/us-pa`, `data/r3-packs/us-oh`) | "problems: none", "checksums ok", exit 0, four times |
| `python android/scripts/sync_assets.py check` | "in sync" (style cf828c778bfc, overview 02273872e323), exit 0 |
| `python android/scripts/check_private_logs.py scan --module core/packs --module core/installed --module routing/packs` | 11 files in 3 modules, 0 marked lines, 0 breaches (no log calls) |
| `.venv/addresses/Scripts/python.exe packbuilder/addresses/key_fingerprint.py` | 1,482 spellings, `97c8d281115ec4d4b186051b18efe3f15bb5b1c74d60902854665f83d228d73a` (also in both search files, both address files and both manifests) |
| JSON check of `android/app/src/main/assets/style/sprites/{light,light@2x,dark,dark@2x}.json` | each holds 119 images, `saved_home`, `saved_work`, `saved_place` among them: 19x19 at pixel ratio 1, 38x38 at 2 |

**The definition of done, item by item** (tests under `android/core/installed/src/test/...`;
"real" = on the archives in `data/r3-packs`):

| Done-when item | Proof |
|---|---|
| install from the real PA and OH archives, all parts; routing merged, staged tar gone, `routing.json` kept | `RealArchivesInstallTest` "PA and OH install with every part, ...": both folders hold exactly manifest, map, routing.json, search, addresses, record; no routing.tar; record `routing_merged` true; the routing manager lists `pa` and `oh` |
| install without addresses, credit without the address sources | real: "PA without house numbers ..." (credit "© OpenStreetMap contributors"); synthetic: "without house numbers the credit names no address source" |
| update of an installed state | real: PA again with OH installed, one generation; synthetic: "an update replaces the installed state in one generation" |
| removal: routing uninstalled, the other state still routes | real: after removing OH, the set's states are `[PA]`, Erie and Pittsburgh covered, Columbus not, the routing manager lists only `pa` |
| refusal while held | real: `RealArchivesRulesTest` "held, busy and too little space" (install, folder install, removal and house-number removal refused with "Not while guiding"; `inspect` lists it); synthetic: "installs and removals are refused while held" |
| `holdChanges` null while an install runs | real: the same test, during the PA archive's copy; synthetic: "holdChanges is null while an install runs"; and while a removal runs: real and synthetic "holdChanges is null while a removal runs" (new) |
| refusal of a v2 pack ("rebuild the pack") | real: `RealArchivesRulesTest` on round 2's `data/android/archives/us-pa-2026-11-14.tar` (only its manifest is read): `inspect` and install say "manifest_version 2 is an old pack format; rebuild the pack", nothing copied; synthetic, `PackManifestTest`, `PackScannerTest` |
| too little space, counting the merge peak | real: PA's need = every part + 64 KiB + PA's routing part + 4 MiB + 200 MB, refused with one byte less, nothing copied; with PA installed, OH's need also counts PA's merged tile set (634 MB) and is refused with one byte less, the generation unchanged; synthetic: "too little space is refused before copying, counting the routing merge" |
| a fingerprint mismatch: search unusable, map and routing usable | real: the PA pack read with another fingerprint has `searchProblem` "built with another address normaliser", `searchFile` null, the map file there, routing covering Erie; with the real fingerprint no problem; synthetic: "a fingerprint mismatch ..." |
| crash recovery at four points | real: `RealArchivesRulesTest` "crash recovery at the four points ..." (after staging: undone, no folder left, no tile set; after the routing commit: rolled forward; before the rename of an update: finished with the new record; during removal: finished, OH's routing uninstalled, PA still routes); synthetic: the four "a crash ..." tests. A fault hook throws where a killed process would stop; the crashed library is ended, then a new library on the same folders refreshes |
| one generation per change | asserted after every install, update, removal and house-number change (real and synthetic); a hold changes `changesHeldBy` only; after the review, a refresh while held publishes none, and the one exception is a removal on a phone too full to rewrite the routing set (two, section 9) |
| folder install of the real PA folder: moved, none copied, times kept | real: "the real PA folder ..." (download folders made of hard links to `data/r3-packs/us-pa`; the folder holds only `manifest.json` afterwards; every installed file kept its last-modified time; record `"source": "download"`) |
| a failure in the middle moves the files back | real (a wrong house-number file: every file back with its size and time) and `InstalledFolderTest` |
| `packVersion` read; space counted without the moved parts | real (202611160853); synthetic "free space is counted without the moved parts" |
| house numbers added from a folder holding only the manifest and `addresses.sqlite` (credit gains the sources, nothing else changes), removed (credit back, file gone), each refused while held, another `pack_version` refused | real: "PA without house numbers, then added from a folder and removed again"; synthetic: "house numbers are added from a folder and removed again" (held and other version included) |
| merged `valhalla.json` has `mjolnir.tile_url` empty or absent (N5) | real install test and the first `InstalledLibraryTest` (`EngineConfig.build` writes `""`) |
| `read_pack.py --verify` passes on both archives | section 2 table |
| overview screenshots z3.5 and z5, day and night, looked at | `data/art/views/overview-{day,night}-z{3.5,5}.png`, re-rendered by the second engineer after the credit fix and looked at one by one (section 5); composite `data/r3-p/e2-overview-after.png`; before the legibility change: `data/r3-p/overview-before.png` |
| saved-place icons in the light/dark sprites at 1x and 2x of the synced bundle; `check` passes; the contact sheet shows them | JSON check above; `sync_assets.py check`; `data/art/sheet/sheet-04.png` (looked at: the last row holds `saved_home`, `saved_work`, `saved_place`, each marked "new", by day and by night); icons cut again from the synced 2x sheets: `data/r3-p/e2-saved-icons-check.png` |
| `count_pois.py` re-run on the z14 packs | PA (first engineer) and OH (second engineer), section 3 |
| `sync_assets.py check` in sync | section 2 table |

## 3. Measurements

| What | Measured |
|---|---|
| `build_sprites.py --compare-v4` | 2.5 s; 119 images per sheet |
| PA search index (existing extract) | 107 s, peak 2,789 MB; 456,470 entries; 154,705,920 bytes (the same size as round 2's) |
| OH search extract + index | 159 s (peak 1,431 MB) + 78 s (peak 2,108 MB); 353,706 entries; 118,837,248 bytes |
| PA address rebuild (TIGER keys recomputed, then build) | 91 s (687 MB) + 164 s (5,729 MB); slim file 288,145,408 bytes, 2,038,629 points (the round-2 count) |
| OH addresses: TIGER download (88 counties + county subdivisions) | 1,956 s (the census server answered in batches about every 90 s) |
| OH addresses: OSM, TIGER, build | 61 s (1,934 MB), 110 s (610 MB), 81 s (2,878 MB); slim file 181,481,472 bytes, 598,938 points, 1,383,357 ranges |
| NAD for Ohio on the hosted service | 5,544,101 rows in 88 counties; 50,000 rows in 301 s (166 rows/s with 2 threads): about 9 h, so not used (section 4) |
| `assemble.py pa` | 5.1 s (warm cache) |
| Archives | `us-pa-2026-11-14.tar` 1,322,045,440 bytes (pack_version 202611160853); `us-oh-2026-11-14.tar` 1,196,359,680 bytes (202611160920) |
| Installs on this PC (JVM, `RealArchivesInstallTest`), three runs: engineer 1; engineer 2 before and after the fixes | PA archive (first state) 9.6 / 7.8 / 8.1 s; OH archive beside PA 12.4 / 9.2 / 9.6 s; PA update with OH installed 13.3 / 9.5 / 10.8 s; OH removal 3.3 / 3.2 / 2.7 s; PA from a download folder (moves, then hashes) 9.3 / 5.9 / 6.1 s; PA without house numbers 9.5 / 7.0 / 7.6 s; adding house numbers 1.1 / 0.8 / 0.9 s |
| POIs placed, PA z14 pack against the z15 pack (`count_pois.py --pack v2/pa-261114-z14.pmtiles`, 6 places x z11-17) | identical in 41 of 42 views; downtown Erie z14: 14 parking icons against 13 (`data/r3-p/poi_counts_z14.json`, the z15 run `data/art/views/poi_counts.json`) |
| POIs placed, OH z14 pack (`count_pois.py --pack v2/oh-261114-z14.pmtiles --places conneaut-downtown columbus-downtown`) | downtown Columbus z14 22, z15 23, z16 40, z17 24 symbols (z16: 6 restaurants, 4 fast food, 3 cafes, 5 banks, 7 parking entrances); Conneaut 1-5 per view (rest area at z11-13, fuel at z15-16: a small town). No z15 Ohio pack exists to compare with; downtown Pittsburgh at z16 places 45 on the PA z14 pack, so the Ohio pack draws POIs as the PA one does (`data/r3-p/poi_counts_oh_z14.json`) |
| Overview legibility (WCAG non-text contrast against the earth colour) | interstates below z7: day #a8693a 3.33:1 (was white, 1.33:1), night #a97c4f 4.46:1 (was #474747, 1.77:1); state lines: day #7d7d7d 3.10:1 (was #adadad), night #808a9c 4.74:1 (was #5b6374, 2.73:1) |

## 4. Choices and their reasons

1. **The fingerprint list is a JSON object** with the list under `"spellings"` (and an `"_about"`
   comment), 1,482 spellings: every spelling of every table of `us_street_norm.json` version 2 in a
   street name, every positional rule (Saint, ordinals, glued highways, modifiers) and some accented
   names. Generated once by `data/r3-p/make_spellings.py`; the file, not the generator, is the
   definition (never regenerate it). The fingerprint code lives beside `addrlib`, not in it, so S's
   conformance references (which hash the loaded `addrlib` sources) did not go stale.
2. **The app's fingerprint reaches the installer through `Readers.addrKeyFingerprint`.**
   `:core:installed` may not depend on `:core:search`, and its constructor is fixed; `Readers` is
   P's and already a constructor argument. The integrator sets it from S's port
   (`rules.readers().copy(addrKeyFingerprint = ...)`); null skips the check. A mismatch sets
   `searchProblem` "built with another address normaliser" and `searchFile` null.
3. **`neededBytes`** = every part copied + 64 KiB + (the merged routing set now + the routing part)
   + 4 MiB (what `RoutingPackManager` keeps beside the new set) + `free_space_margin_mb`. It is the
   figure G's `Space.exact` expects (it subtracts `addressesBytes` itself). For a folder install
   the moved parts are not counted.
4. **Crash recovery rolls forward** after the routing commit: a staged folder whose `routing.json`
   equals the routing set's manifest of its state is completed, because the per-state routing tar
   is not kept and the old tiles cannot be put back. Before the commit everything is undone.
5. **The library scans once by itself when it is made** (and recovers a change a killed process
   left half done), so `installed.scanned` turns true without a first `refresh()` call; `refresh()`
   waits for that first scan, so a refresh always ends with the newest view published (second
   engineer).
6. **A change always publishes a new generation**, even when the view looks the same (an update
   to the same build replaced files that N and S may hold open); a rescan publishes only when
   something differs; a hold changes `changesHeldBy` without a new generation (the session would
   otherwise close its engine at Start).
7. **`RoutingSet.states`** holds the USPS codes of the packs whose tiles are in the set (`["OH",
   "PA"]`), like `PackInfo.state`.
8. **Archive order**: manifest, then the parts from the smallest to the largest (routing.json,
   search, map, addresses, routing for PA); `bounds` from the map's clip outline.
9. **Ohio's house numbers are OSM + TIGER only** in this round; the credit says so. Kept by the
   second engineer: the OH pack is published to the other tracks (G builds its catalog and its dev
   server from `data/r3-packs/us-oh`, board `p-archives.md`), so a rebuild during the round would
   change its `pack_version` under them; the national NAD file is 8.5 GB and the hosted service
   about 9 h; the owner's trip and the E2E are in Pennsylvania, whose pack has NAD. A rebuild with
   NAD is a lead's or owner's decision for after the round (section 8).
10. **The overview's credit** comes from the file's own metadata ("Natural Earth; US Census
   Bureau"); `StyleComposer`'s new overload no longer adds an OpenStreetMap credit that no shown
   data needs (the old overload keeps round 2's rule). The art renders now show the same credit
   (section 6).
11. **Interstates warm brown below z7**: grey interstates were indistinguishable from grey state
    lines at z3.5; a warm tint keeps the two apart and passes 3:1 by day and night. From z7 up the
    stock white highways with casing are unchanged. Owner's draft (owner question 3 is about the
    shield, not this colour).
12. **One library per folder set** (second engineer). The app makes one `InstalledLibrary`; the
    tests now do the same: each test ends a library (cancel and join its scope) before it makes the
    next one on the same folders and before JUnit deletes the temporary folder.
13. **Removal keeps the 2.11 invariant by itself** (second engineer): after the folder is moved
    aside, every routing state no installed pack owns is uninstalled (the removed one, and one left
    by a pack folder that fails its checks), the same rule as `cleanUp`; `remove` and
    `removeOptionalParts` return a failure in plain words and never throw at the caller. After the
    review the uninstall is also tried again once the removed folder is deleted (a full phone,
    section 9, finding 2).
14. **The contract file `InstalledLibrary.kt`** has only its stub bodies replaced; the first stub's
    replacement also declares `internal val core`, and the last one's the overrides of the download
    hand-off (`installsFromFolder`, `startInstallFromFolder`, `changesOptionalParts`,
    `startAddOptionalParts`, `removeOptionalParts`), which can only live in this class.
    `check_contracts.py` reports it "filled". A reviewer may prefer a request to show those members
    in the quoted block of 2.2.

## 5. The screenshots, looked at

- `data/art/views/overview-day-z3.5.png`: the eastern half of the US; interstates as thin warm-brown
  lines, clearly separate from the solid grey state lines; state codes and city names readable; the
  credit box says "Natural Earth; US Census Bureau".
- `overview-night-z3.5.png`: the same view by night: tan interstates, blue-grey state lines, both
  visible on the dark earth; the same credit.
- `overview-day-z5.png`: Lake Erie to Virginia: interstates brown with junctions readable (Erie,
  Pittsburgh, Cleveland, Columbus), state lines dashed grey (PA-OH, PA-NY, WV-VA); same credit.
- `overview-night-z5.png`: the same by night; dashed light state lines, tan interstates; same credit.
- `data/art/sheet/sheet-04.png`: the contact-sheet tile whose last row shows `saved_home` (house),
  `saved_work` (briefcase), `saved_place` (star), each in a filled blue rounded square with a white
  outline and glyph, darker blue by day and lighter by night, marked "new".
- `data/r3-p/e2-saved-icons-check.png`: the three icons cut from the app's synced `light@2x` and
  `dark@2x` sprites at 4x zoom: the same drawings, nothing clipped.

## 6. What the second engineer found and changed

- **A failing test.** `sh tools/round/gradle.sh p :core:installed:test --rerun` failed 1 of 23:
  "a crash before the rename of an update finishes the update" passed its assertions, then JUnit
  could not delete its temporary folder (`DirectoryNotEmptyException` on `routing`). The tests let
  several `InstalledLibrary` instances work on the same folders at once (the crashed library's
  tail, and the first scan each constructor starts), so an old instance still had a file open in
  `routing/` when the folder was deleted; such an instance could also take another one's staging
  folder for a crash leftover and delete it. It is a test isolation fault (the app makes one
  library), so it passed in the first engineer's run and failed in mine. Fixed in the tests (one
  library at a time, ended with cancel-and-join, also in `@AfterEach`) and in `InstalledCore`
  (`refresh()` waits for the first scan). After the fixes: 27 of 27.
- **The installer rules on the real archives** (`RealArchivesRulesTest`, new): hold, busy during an
  install and during a removal, too little space with and without PA's merged set, the round-2
  version-2 archive, crash recovery at the four points, a fingerprint mismatch. Also new:
  "holdChanges is null while a removal runs" on synthetic packs, and the OH folder's file list in
  the real install test.
- **Removal** uninstalls every orphan routing state and returns failures as words (choice 13); a
  dead field (`changeThread`) and two needless `!!` removed.
- **The credit of the overview renders.** The four renders the first engineer looked at showed
  "© OpenStreetMap contributors" in the credit box: the style template's source credit, not what
  the app shows over the public-domain overview. `render_art.py` now reads a pack's own metadata
  credit for views marked `"credit_from_pack": true` (the four overview views), and
  `render/art_view.js` puts it on the map's source. Re-rendered: 4 views, 0 problems, each showing
  "Natural Earth; US Census Bureau".
- **`count_pois.py`** has two Ohio places (downtown Conneaut, downtown Columbus) for the OH z14
  pack, and `--places` now defaults to the six Pennsylvania places.
- `docs/pack-format.md`: the free-space sum as `inspect` gives it, the crash recovery in full, the
  changes while guiding.

## 7. Notes for other tracks

- **I (integration):** `AppGraph` gives `InstalledLibrary` `ComposeRules.readers().copy(addrKeyFingerprint = <S's port>)`,
  one instance for the app, its packs and routing folders on the same volume as G's download
  folders; `PackMap` uses `StyleComposer.compose(style, theme, overview, installed.packs)` and
  `ComposedMap.creditLine`, with the overview's credit from `assets-local/basemap/us-base.json`
  (`attribution`); run `sync_assets.py basemap --basemap data/map/packs-v2/us-overview-ne512-tiger2026-z0-7.pmtiles`
  (assets-local is git-ignored); `PackLibrary`/`PackInstaller` are the old path and can go once
  `:app` uses `InstalledLibrary`.
- **N:** the round-2 packs under `data/android/` are format 2 and are now refused by
  `PackScanner` (`:routing:demo`'s map); use `data/r3-packs/us-pa` (board `p-manifest-v3.md`).
  `:core:packs` was not changed in stage 2.
- **G:** the installer's behaviour is on the board (`p-installer.md`, updated in stage 2 and after
  the review). New after the review: `inspect` lists "Pennsylvania is installed already (as us-pa).
  Remove it first." for a second pack_id of an installed state; a pack of another road build is
  refused with a plain sentence (section 9, finding 5); `remove` may answer "Ohio is removed, but
  its roads stay in the route planner ..." on a full phone (the pack is gone; the sentence is
  information, not a refusal); while changes are held a refresh publishes no new generation.
- **I:** `android/README.md` line 14 still says "pack manifest (format 2)"; it is format 3 now
  (`docs/pack-format.md`). `InstalledSet.issues` may now hold `routing` or `routing/<state>` as the
  folder of an issue of the merged routing set, and a `.staging-*` folder whose install could not be
  finished yet.
- **Read-only files that mention the retired tool:** `android/README.md` (I), `android/routing/README.md`
  (N), and the untracked phone-kit script `android/scripts/phone/make_z14_archive.py`, which imports
  `tools/fetch_android_map_pack.py` and now stops with the retirement message.
- Compile check of the edges (`sh tools/round/gradle.sh p :routing:demo:compileDebugKotlin
  :map:demo:compileDebugKotlin`): BUILD SUCCESSFUL at the end of stage 2 (the modules belong to N
  and M and may be mid-edit when it runs again).

## 8. Not done, or not verified

- **NAD addresses for Ohio**: not in the OH pack (choice 9). The hosted service would take about
  9 h (measured rate); the national TXT file (8.5 GB) was not fetched. The OH pack has 598,938 OSM
  points and 1,383,357 TIGER ranges; PA has 2,038,629 points with NAD.
- No device check (none required for P): the install on the emulator and the phone is part of I's
  E2E. The install times are from the JVM on this PC with warm caches; the phone will be slower.
- `android/scripts/push_pack.py` and `pack_variants.py` refuse to run without `ANDROID_SERIAL` and
  pass `-s` to adb (rule 4 of the round), but were not otherwise updated for format 3: an
  adb-pushed folder has no install record, so its routing is not merged (it shows as not checked);
  `pack_variants.py` stays marked out of date. Neither was run in this round.
- The art views summary `data/art/views/views.json` lists only the four overview views (the
  renderer rewrites it per run); the route views' PNGs are unchanged.
- The colours of interstates and state lines below z7 and the three saved-place icons are drafts
  until the owner approves them.
- The crash tests stop a change by throwing where a killed process would stop; no process was
  actually killed, and a power cut (unsynced directory entries on Android) was not simulated.
- **Moving the installed states to a new routing graph build in one step** is not supported: the
  installer adds one state per change, and a state of another build is refused while one of the
  old build is installed (section 9, finding 5). The user's way is remove, install, add again; the
  downloads flow "a newer pack for an installed state" (downloads 1.3) will hit this refusal when
  a new graph build is published. A lead's decision for after the round (an installer call that
  takes several packs, or the catalog publishing a new build only with every state).
- Behaviour while held (section 9, finding 1) is proved on the synthetic packs only, not on the
  real archives.

## 9. Review and fixes

Two reviews followed stage 2. The reproduce review ("sound") re-ran the proof and found nothing
that contradicts this report; it also ran `check_network_rule.py`, which had appeared on the board
meanwhile (0 findings in P's modules). The contract review ("sound with fixes") found no blocker
and no major defect, and seven minor ones. The finishing engineer checked each against the code
before acting: all seven were right, and all seven are fixed. Each fix has a test that fails
without it: a script (`data/r3-p/mutation/mutate.py`) undid the six installer fixes in
`InstalledCore.kt`, and the six new tests that cover them failed (`data/r3-p/f-mutation.log`);
with `RoutingPacks.kt` parsing state manifests unguarded again, the damaged-manifest test failed
too (`f-mutation-6.log`). Both files were restored from their saved copies and compared byte for
byte with `cmp` before anything else ran.

| # | Finding (contract review) | Checked against the code | Fix | Test |
|---|---|---|---|---|
| 1 | `refresh()`/`cleanUp` change the routing set and publish generations while changes are held | right: `scanAndRecover` never looked at the holds; an orphan found mid-trip was uninstalled (`routing.tar` and `valhalla.json` rewritten) and a generation published | a repair takes the busy flag like any change and is not done while held; a rescan while held publishes nothing (only a process's first scan is published, so the app can start); what it found runs, with a rescan, as soon as the last hold is released | "while held a refresh changes no file and publishes nothing, the repair runs at the release": same generation, same routing set, `routing.tar` length and time unchanged, both routing states kept; after `close()` exactly one new generation with Ohio's routing gone |
| 2 | removal swallows a failed routing uninstall | right: on a full phone `RoutingPackManager.uninstall` (it writes the remaining set beside the old one) threw, the error was swallowed, and the published set still covered Ohio | `uninstallOrphans` never throws and returns the states it could not remove (listed in `issues` as `routing/<state>`); after the folder is deleted (its space is free then) the uninstall is tried again, with a second generation when it worked; if it still does not fit, `remove` says "Ohio is removed, but its roads stay in the route planner until the phone has more free space. Beamline removes them then." and a later scan finishes it | "a removal on a full phone uninstalls the routing tiles once the folder is gone" (null, two generations, Ohio's tile gone); "a removal that cannot free the routing tiles says so, and a later refresh removes them" (the message, the issue, a refresh on the still full phone changes nothing, then one with space removes the tiles) |
| 3 | a roll forward that fails after the routing commit deletes the staged pack | right: `runCatching { rollForward }.onFailure { deleteRecursively }`, so a folder install's moved parts were lost and the merged state became an orphan | the staging folder is kept, still owns its routing state (orphan detection now counts committed staging folders), is listed in `issues` and is rolled forward by the next scan | "a roll forward that fails keeps the staged install, which owns its routing, and finishes later" (an IOException at the new hook point "roll-forward" in a new process; a normal restart then finishes the install) |
| 4 | the installer does not check that `routing.json` names the pack's own state | right: only `tar_bytes` and `tar_sha256` were compared | refused before the merge: "Ohio: its routing tiles are for another state (PA), not OH. Get the pack again."; also refused, and listed by `inspect`: another `pack_id` for an installed state, which would take over that state's routing ("Pennsylvania is installed already (as us-pa). Remove it first.") | "routing tiles of another state, and another pack for an installed state, are refused" (generation, folders and PA's routing unchanged) |
| 5 | raw exception text and engineer wording in `InstallStatus.Failed`; a graph-build change untested and without a way forward | right: `e.message` of any exception and `PackException`'s text ("update all packs to one build in one step (pa oh) or remove oh first") reached the screen | `PackException` has a `kind`; the installer words each kind ("Pennsylvania was built from another version of the road data than Ohio on this phone, and the two cannot be mixed. Remove Ohio first, then install Pennsylvania and add Ohio again."); an unexpected exception gives a fixed sentence (an IOException: "Reading gone.tar or writing into the app's storage failed. Try again."; others: the class name only); the scanner's "cannot read the pack" carries the class name, not the message; a failed move in `startAddOptionalParts` has the folder install's sentence | "a pack from another road build is refused in plain words, and remove, install, add again works"; "an unexpected exception gives fixed words, never its own text"; `PackRulesTest` "refusals carry their kind" and the kind of the graph-build refusal |
| 6 | a corrupt `routing/packs/<state>.json` blocks every scan, install and removal | right: `readInstalled` parsed every file unguarded; the first scan failed inside `runCatching`, `scanned` stayed false, and every later call threw | `RoutingPackManager.installed()` leaves an unreadable state out, `unreadable()` names it, `uninstall` removes it (the set is rewritten from the readable states), any install rewrites the set without it; the installer uninstalls such states as orphans, so the pack of that state shows "its routing tiles are not in the routing set: install the state again"; `view()` also survives a routing folder it cannot read at all (an issue, no routing set) | "a damaged routing state manifest stops no scan, and the state can be installed again"; `PackRulesTest` "an unreadable state manifest stops nothing and is uninstalled" |
| 7 | `ComposedMap.attributions` KDoc contradicts the new overload | right | KDoc reworded (the old overload adds the OSM credit; the `PackInfo` overload credits only what the overview and the parts name). `android/README.md:14` ("format 2") is I's file: told to I in section 7 and on the board | none needed (a comment) |

**Files changed after the review:** `android/core/installed/src/main/.../InstalledCore.kt`;
`android/core/installed/src/test/.../InstalledLibraryTest.kt` (8 new tests) and `MiniStates.kt`
(`build`, `routingState`, `packId`); `android/routing/packs/src/main/.../RoutingPacks.kt`
(additive: `PackException.kind` with a default, `unreadable()`, `hasPendingChange()`; the module is
compiled by N's `:routing:demo`, which still compiles); `android/routing/packs/src/test/.../PackRulesTest.kt`
(2 new tests); `android/core/packs/src/main/.../PackScanner.kt` (one message) and
`StyleComposer.kt` (KDoc); `docs/pack-format.md` (section 3: one pack per state and one road build,
the kept roll forward, removal on a full phone, the hold and refresh, the messages); the READMEs of
`:core:installed` and `:routing:packs`; this report; the board note `p-installer.md`.

**Choices made with the fixes.**

- While held, a rescan publishes nothing rather than a read-only view: N's session assumes that
  nothing changes during a trip (`SessionCore.onInstalled`: "changes are held during a trip"), and
  a frozen view is the simplest promise. The one exception is the first scan of a process (a trip
  resumed after process death may hold changes before it), which must be published so the app can
  start; the routing manager's own recovery of a half-written routing change can run inside that
  first scan, before any engine is open.
- A repair takes the busy flag, so `holdChanges` returns null while it runs (as for an install).
  It is taken only when a rescan finds something to repair, so an ordinary refresh never makes
  Start fail. A staged install whose roll forward keeps failing is retried at every scan, so each
  scan then holds the flag for a moment.
- The low-space removal publishes two generations: the folder must leave the published view before
  it is deleted (2.2 rule 5), and the routing set changes only after that. This is the one
  exception to "one generation per change", and only on a phone too full to rewrite the set.
- `RoutingSet.level2Tiles` stays what the engine can route (every state in the tile set), so a
  routing state that could not be removed yet is still covered, and the issue says so.
- A graph-build upgrade of one state while a state of the old build is installed stays refused
  (routing across the border would mix node indexes of two builds). This installer adds one state
  per change, so the way forward is remove, install, add again, as the message says (section 8).

**Re-proved after the fixes** (2026-11-16, night; commands as in section 2):

| Command | Result |
|---|---|
| `sh tools/round/gradle.sh p :core:packs:test --rerun :core:installed:test --rerun :routing:packs:test --rerun -Pbeamline.routingPacksWork=<abs repo>/data/r3-p/routing-packs-test :routing:demo:compileDebugKotlin :map:demo:compileDebugKotlin` | BUILD SUCCESSFUL in 2m 44s (`data/r3-p/f-tests-final.log`); from the JUnit XML (`data/r3-p/count_tests.py`): `:core:packs` 61 tests, `:core:installed` 35 (InstalledLibraryTest 24, InstalledFolderTest 5, RealArchivesInstallTest 3, RealArchivesRulesTest 3), `:routing:packs` 14 (PackRulesTest 13, RealPacksTest 1); 0 failures, 0 errors, 0 skipped; the 1.8 GB work folder deleted afterwards |
| `python tools/check_contracts.py` | all the same; 39 frozen files unchanged; exit 0 |
| `python android/scripts/check_private_logs.py scan --module core/packs --module core/installed --module routing/packs` | 11 files in 3 modules; 0 marked lines; 0 breaches |
| `python android/scripts/check_network_rule.py scan --root android/<module>` for the three modules | 0 findings each; the whole-tree `scan` has 1 finding, S's `core/search-bench/.../BenchMain.kt:37: ProcessBuilder`; `privacy`: 0 private IPv4 literals |
| `python android/scripts/read_pack.py <both archives, both folders> --verify` | exit 0, "problems: none", "checksums ok", four times (`data/r3-p/f-read-pack.log`) |
| `python -m unittest discover packbuilder/pack/tests`; `.venv/addresses/Scripts/python.exe -m unittest discover -s packbuilder/addresses/tests` | 6 OK; 22 OK |
| `python android/scripts/sync_assets.py check` | "in sync" (style cf828c778bfc, overview 02273872e323), exit 0 |
| JSON check of the four synced sprite sheets | 119 images each; `saved_home`, `saved_work`, `saved_place` 19x19 at ratio 1 and 38x38 at ratio 2 |
| `data/r3-p/e2-overview-after.png` | looked at again: interstates brown or tan and state lines grey (dashed at z5) by day and by night, credit "Natural Earth; US Census Bureau" |

The install times this run printed (`RealArchivesInstallTest`, single runs, other tracks building
at the same time): PA archive 13.0 s, OH beside PA 17.5 s, PA update 16.3 s, OH removal 3.6 s, PA
from a download folder 10.4 s. They are slower than stage 2's (section 3), which reflects the load
on the shared PC; the fixes touch no step of the copy or the merge.
