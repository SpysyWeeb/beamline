# 27. Next run, batch F: every bottom sheet gets a lowest height; named beaches are searchable

Status: **done, 2026-11-20** (item 15 key n, item 14 and the batch's end key m). Items 15 and 14 of
docs/next-round.md. Version **0.1.8 (versionCode 11)**. New search parts for Pennsylvania and Ohio
(pack_version 202611200917). Nothing is committed: the lead commits. Scratch, shots and logs are in
`data/r3-run2/f15/` and `data/r3-run2/f14/` (not in git); the working record is
`data/r3-run2/PROGRESS.md`, sections "Batch F, item 15" and "Batch F, item 14".

## Item 15: a lowest height for the results list, the route sheet and the place sheet

The owner could not lower the results list to see the pins. The route sheet covered about 60 % of the
screen, so the routes could not be compared on the map. Now all three sheets share one frame with one set
of height rules: `:ui:theme` `Sheets.kt` (`SheetFrame`, `SheetState`, `SheetSteps`, `SheetNestedScroll`,
`SheetTexts`), used by ResultsViews.kt, SearchViews.kt and RouteSheet.kt. This is the new rule D10.

| Sheet | Heights |
|---|---|
| Results list | three: lowest = handle, query, count, close; middle = rows up to 30 % of the screen (at least 150 dp); highest = 60 % |
| Route choice | two: lowest = one small card per route (its time over its types; a tap chooses it) plus Start and its reason; or the full sheet |
| Place sheet | two: lowest = icon, title on one line, close; or the full sheet |

- **Drag.** A drag anywhere on a sheet steps one height for every 40 dp it travels. On the rows, a drag up
  raises the sheet before the rows scroll. A drag down first scrolls the rows to their top, then lowers
  the sheet. A fling after a drag that moved the sheet is used up.
- **Tap.** A tap on the handle (or the header joined to it) raises one height. From the highest height
  it goes back to the lowest.
- **Screen reader.** The handle has a label ("Results list", "Route choice", "Place details"), a state
  ("Lowered", "Half open", "Full height") and the actions "Raise the sheet" / "Lower the sheet".
- **Camera.** No new camera code. AppRoot and DriveOverlay already measure each sheet into the camera
  insets, and `CameraDirector.onViewport` re-fits the results, the routes or a picked place to the space
  left.
- **Remembered heights.** The results list keeps its height while a result's place sheet is open
  (`ResultsHeightMemory`). New results open at the middle height. A new trip's route sheet and a new
  place's sheet open at full height. Heights survive a turn of the phone.
- **Landscape.** The browsing sheets stay full width at the bottom with the same heights. The route sheet
  in the side panel can be lowered too, and the routes stay fitted to the right of the panel.

Shots on beamline_n (`f15/shots/`), each looked at:

- **Results list.** `results-2-low`: only the handle, "sheetz / 7 places" and close; all 7 pins
  re-fitted into the bigger map. `results-5-rows-dragged-low`: a drag down on the first row lowered the
  list. `results-6-dragged-up-full`: a 300 px drag on the handle moved two heights.
- **Route and place sheets.** `route-2-low`: the small card "7 min / Fastest · Simpler" and Start; the
  route re-fitted. `place-2-low`: the icon, "Sheetz" and close; the place re-centred lower.
- **Turning the phone.** `turn-1-landscape-route-low` / `turn-2-portrait-route-low`: the sheet stays low
  in the side panel and back in portrait.

## Item 14: named beaches and other natural places a driver goes to

### Why "Beach 10" found nothing

- **Presque Isle's beaches were dropped.** They are all `natural=beach` ways: "Beach no.1", "Beach
  no.6", "Beach no.7 Waterworks", "Beach no.8 Pettinato", "Barracks Beach", "Mill Road Beaches", "Beach 9 -
  Pine Tree", "Beach 10 - Budny" and "Beach 11". No beaches 2 to 5 are mapped there. The search extract
  kept named objects only under amenity, shop, tourism, leisure and similar keys, and no category named
  `natural`, so these ways were dropped.
- **The `leisure=park` "Beach 1" to "Beach 4" are somewhere else.** They are **four small parks near Lake
  Harmony** (Carbon County, 41.15 N 75.57 W, about 400 km from Erie). They were in the index all along,
  with the type "park" (checked in the old PA search file: ways 1564276563, 1564276576, 1564271367 and
  1564276577). The query itself was fine: only a leading number is read as a house number.

### The fix (search build only; no app code)

- **Four new categories** in `packbuilder/search/categories.json` (group outdoors, flag `in_park`), each
  with its words:

  | Category | Tags | Words | Icon |
  |---|---|---|---|
  | beach | `natural=beach`, `leisure=beach_resort` | beach, beaches, public / swimming / lake / sand beach | beach |
  | waterfall | `natural=waterfall`, `waterway=waterfall` | waterfall(s), falls (0.8), cascade(s) | viewpoint |
  | cave | `natural=cave_entrance` | cave(s), cavern(s), cave entrance | attraction |
  | peak | `natural=peak` | peak(s), summit, mountain peak, mountain top, highest point | peak |

  The beach category comes before the parks, so a beach also tagged `leisure=park` shows as a beach.
  The icons are sprite icons that already exist (`assets/poi_icons.json`, copied to `:core:search`;
  `check_poi_icons.py` ok).
- **Only these natural objects are kept** (`osm_extract.py`). `natural=*` and `waterway=*` objects are
  kept only for the values a category rule names (`RuleBook.tag_values`). Springs, cliffs, rocks, woods,
  water, wetlands, ridges and saddles stay out: a driver does not head to them, or there are too many
  and they are vague. Viewpoints were already searchable (`tourism=viewpoint`).
- **Two derived keys** (`build.py`, tier `exact_alt`). No change to the query engine or the ranking:
  - "No. N" is also keyed as "N": "Beach no.1" is also found as "beach 1".
  - A natural place inside named parks, nature reserves or protected areas (the three smallest that hold
    its point) is also keyed "&lt;park&gt; &lt;label&gt;" and "&lt;park&gt; &lt;name&gt;". The park's name is used
    as written and without an ending such as "State Park" or "Park". So "presque isle beach" finds the
    beaches of Presque Isle State Park, and "ricketts glen waterfall" finds that park's falls. The extract
    now keeps park outlines for this (`parks`).
- **Ten new `natural` regression cases** in `packbuilder/search/tests/cases.json`: Beach 10, Beach 1,
  beach 11, presque isle beach and beach near Erie; waterfall near Ricketts Glen; ganoga falls; ricketts
  glen waterfall; mount davis; crystal cave. All pass, top-1, on the new PA file. The old sets are
  unchanged: tuned, held-out and review give the same top-1 / top-5 / gap counts as before
  (`f14/rt-before-*` against `f14/rt-new-*`).

### Measured

| | Pennsylvania | Ohio |
|---|---|---|
| Points of interest added | **1,807**: 1,589 peaks, 134 waterfalls, 59 beaches, 25 caves (606 inside a named park) | **561**: 403 peaks, 65 beaches, 63 waterfalls, 30 caves (189 inside a named park) |
| Points of interest lost | 0 | 0 |
| Search file | 154,705,920 -> **155,357,184** bytes (+651 KB, +0.4 %) | 118,837,248 -> **119,025,664** bytes (+188 KB) |
| Build time | extract 162.8 s, index 91.2 s (index before: 107.1 s) | extract 160.7 s (before 158.5 s), index 68.1 s (before 78.1 s) |
| Pack archive | `us-pa-2026-11-14.tar` 1,322,700,800 bytes | `us-oh-2026-11-14.tar` 1,196,554,240 bytes |

The index times fall within the usual spread between runs: the new places cost no measurable time. The
counts come from `f14/compare.txt` (`f14/compare_db.py`, old against new).

Kotlin search time on the emulator, from the search demo app with the PA search file (`f14/demo_times.sh`;
three rounds near Erie, the third round shown, ms):

| Query | Old file | New file | New answer |
|---|---|---|---|
| Beach 10 | 14.9 | **2.4** | 1 result: Beach 10 - Budny |
| Beach 1 | 10.1 | 10.7 | 6: Beach no.1 first |
| presque isle beach | 25.8 | 12.0 | 8 beaches |
| beach | 15.5 | 33.8 | 10, now a category hit |
| waterfall | 10.5 | 21.6 | 10, now a category hit |
| sheetz (control) | 25.3 | 30.8 | 10 |

Opening the file took 228 ms before and 209 ms after. "beach" and "waterfall" are now category searches
with more candidates, so they take about twice as long, still well under 50 ms.

### On the emulator, installed the owner's way (beamline_m, `f14/shots/`, each looked at)

1. **Before.** 0.1.7 with the old PA pack: "Beach 10" listed only "Beach Front Drive", "Ferncliff
   Beach", "Beach Zero" and other places, no Presque Isle beach (`02-old-pack-beach10`). Two places saved
   first: Sheetz on Liberty Street (Other) and the Erie Zoo ticket booth (Home). The search screen shows
   "Saved places, 2 places" (`03-saved-before-017`).
2. **Upgrade.** `install -r data/android/apks/batchF/beamline-release.apk` over 0.1.7: Success, 0.1.8 / 11.
3. **New pack.** The new PA file was copied over `/sdcard/Download/us-pa-2026-11-14.tar`. Then States >
   "Use a file instead" > the file in Downloads > Install. The sheet read "Pennsylvania, From
   us-pa-2026-11-14.tar ... Needs 2.8 GB free", then "Pennsylvania is ready." (`05-picker`,
   `06-after-pick`, `07-pa-ready`).
4. **Saved places kept.** Home (the ticket booth) and Sheetz are both still saved (`08`, `09-saved-list-after`).
5. **"Beach 10".** It opens the place sheet straight away: beach icon, "Beach 10 - Budny", Millcreek
   Township, 3.0 mi, Directions, Save (`10-new-beach10`).
6. **Directions.** A route plans: Fastest · Simpler, 25 min, 11 mi, "via Peninsula Dr, West 12th St",
   along the peninsula to the beach (`11-route-beach10`).
7. **"Beach 1".**
   - Enter: a list of 3, all with the beach icon: Beach 11 (2.3 mi), Beach 10 - Budny (3.0 mi), Beach
     no.1 (3.6 mi). Enter's list is ordered nearest first (rule S11) (`12-new-beach1`).
   - Typing the same text: Beach no.1 is the first suggestion, then Beach 11 and Beach 10. Lake Harmony's
     "Beach 1" park comes 4th, at 242 mi (`14-typing-beach1`).
   - Beach no.1's place sheet (`13`).
8. **"presque isle beach".** 8 places, all Presque Isle beaches, pins along the peninsula
   (`15-presque-isle-beach`). "Mill Road Beaches" has no park key: its point lies outside the park's
   outline.
9. **"waterfall" at Ricketts Glen** (position set there). 20 falls: Tuscarora Falls 70 ft, Erie Falls 250
   ft, Conestoga, Wyandot, Mohican ... with the viewpoint (camera) icon, pins along both glens
   (`16-waterfall-ricketts`).

### For the lead: the new Pennsylvania pack and how the owner installs it

- **File:** `data/r3-packs/batchF/us-pa-2026-11-14.tar`, 1,322,700,800 bytes, SHA-256
  `51b9d6d0204ecba83e7793ad754ad5182639591dfc1a4518f8b40f53a41e74b5` (check with `sha256sum`). `pack_version` 202611200917; the phone's pack is
  202611160853. Map, routing, house numbers: the same files as before; only `search.sqlite` is new.
  Ohio: `data/r3-packs/batchF/us-oh-2026-11-14.tar`, 1,196,554,240 bytes, SHA-256
  `6515d8672f51e41915ece1f52018328e087b00f652c76728e86e56dc453fa19c` (the owner has no Ohio pack; nothing to do).
- **On the phone** (0.1.6 works; better install 0.1.8 first: `data/android/apks/batchF/beamline-release.apk`
  with `adb install -r`, or open the APK on the phone):
  1. Copy the tar to the phone's **Download** folder over USB (file transfer mode). It has the same name as
     the old one: replace it (or delete the old one first) so only one `us-pa-2026-11-14.tar` is there.
  2. Open Beamline > **States** (the button on the map, under Settings) > **Use a file instead** (bottom
     of the screen) > pick
     `us-pa-2026-11-14.tar` in Downloads > **Install** (house numbers stay on). It needs 2.8 GB free while
     it installs.
  3. Wait for "Pennsylvania is ready." Saved places, Home and Work stay. The old copy in Download can then
     be deleted to free 1.3 GB.

## The batch's end

- **Version:** 0.1.8, versionCode 11 (`android/app/build.gradle.kts`).
- **Phone APK:** `data/android/apks/batchF/beamline-release.apk`, **85,267,301 bytes**, SHA-256
  `7b87f2d3bc5a1440344726fdcc742ff6c63ab5246a039da78b2d3f2414c927ca`. Installed with `install -r` over
  0.1.7 (batchE2) on beamline_m: Success, the saved places and the pack kept.
- **Emulator:** beamline_m has 0.1.8 (plain release) and the new PA pack. It is stopped.

## Proved (and how to re-run)

- **Search build.** `.venv/search/Scripts/python -I packbuilder/search/build.py --state pa|oh --out-dir
  data/r3-packs --work-dir data/r3-p/search-work` (the index alone: `--step index --force index`). Then
  `python packbuilder/pack/assemble.py pa|oh --out data/r3-packs/batchF` and
  `python android/scripts/read_pack.py data/r3-packs/batchF/us-pa-2026-11-14.tar --verify`: checksums ok.
- **Python regression.** `python packbuilder/search/tests/run_tests.py` (and `--cases
  cases_holdout.json`, `cases_review.json`) on the new PA file (`f14/rt-new-*`, run by the first item-14
  engineer on the same data): all 10 `natural` cases top-1; the other sets unchanged.
- **Conformance.**
  - `make_queries.py --db data/r3-packs/pa-search.sqlite`: 1,089 queries, the 10 new cases included; 79
    sampled `x-word` vocabulary queries replace 69, because the vocabulary changed.
  - `make_reference.py --search-db <abs>/data/search/pa-search.sqlite`. This is the file
    `tools/round/env.sh` gives the JVM tests; a reference made from the new PA file fails them with "not
    the file the reference was made from".
  - `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh m :core:search:test`: 80 tests, 0 failures, 1
    skipped. `compare.py`: 1,089/1,089 top-10 ids and every field equal.
  - Negative sets regenerated and run:
    - `...-negative`: 9 findings (1 id, 3 field, 1 label, 1 verdict, 3 address);
    - `-fields`: 3 field and 1 label difference, ids and verdicts equal;
    - `-stale`: "query.py changed since the reference was made".
- **Whole JVM suite.** `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh m test --continue
  -Pbeamline.routingPacksWork=<abs>/data/gradle/m/routing-packs-test`: BUILD SUCCESSFUL, **775 tests, 0
  failures, 1 skipped** (JUnit XML). 18 tasks ran; 334 were up to date or from the cache.
- **Item 15 tests.**
  - JVM: SheetHeightsTest 8 and SheetHeightsCameraTest 3.
  - On beamline_n: SheetHeightsScreensTest 3 and RouteSheetHeightsTest 1; DriveScreensTest 14,
    DriveLandscapeTest 2 and SearchScreensTest 6 (2 skipped by their own assumptions, as before).
  - Section 8 clean drive: ARRIVED at 290.8 s.
- **Checks.**
  - `python tools/check_contracts.py`: all the same, 44 frozen files unchanged;
  - `check_private_logs.py scan`: 0 breaches;
  - `check_network_rule.py scan`: 0, `privacy`: 0;
  - `android/routing/scripts/sync_data.py --check`, `copy_search_rules.py --check`, `sync_assets.py check`,
    `packbuilder/map/style/check_poi_icons.py`: all pass.
- **Builds.** `sh tools/round/gradle.sh m :app:assembleDebug :app:assembleRelease :app:lintRelease`:
  pass; lint 0 errors, 54 warnings, 1 hint (`:app:lintAnalyzeRelease --rerun` first: the cached analysis
  still held an unescaped drive letter in the untracked android/local.properties, now escaped).

## Contract changes

- **docs/architecture.md:**
  - (item 15) new rule D10 after D8 (3.9); the S11 text; the `:ui:theme` module row (Sheets.kt);
    decision 6.85;
  - (item 14) decision 6.86.
- **tools/frozen-files.sha256:** `ui/theme/.../Sheets.kt` added (`check_contracts.py --freeze`, 5.2).
- **Search build** (`packbuilder/search`):
  - categories.json: 4 new categories (104 now), with the `in_park` flag and its note;
  - osm_extract.py: `natural` / `waterway` kept for the ruled values; a new `parks` list;
  - rules.py: `tag_values`, `in_park`;
  - build.py: the "No. N" and park keys;
  - tests/cases.json: 10 `natural` cases;
  - README.
- **Rule files:** `assets/poi_icons.json` (beach, waterfall, cave, peak) and its `:core:search` copy;
  `SOURCES.json` hash.
- **Conformance:** `search_queries.json` (1,089 queries) and the conformance README count.
- **New UI texts (item 15, US spelling):** "Results list", "Place details", "Route choice", "Raise the
  sheet", "Lower the sheet", "Lower to the smallest height", "Lowered", "Half open", "Full height".
- **New test tags (item 15):** sheet-handle, results-sheet, results-rows, place-sheet, card-low-&lt;id&gt;.
- **The packs.** PA and OH search parts rebuilt; packs re-assembled with pack_version 202611200917.
  pack-format.md and catalog-format.md are unchanged: a higher `pack_version` of the same `pack_id` is
  already the rule for a newer build.

## Not verified

- **Nothing on the owner's phone.** The pack install over 0.1.6 is shown only on 0.1.8 on the emulator;
  the install path is the same code.
- **The catalog was not rebuilt.** The emulator catalog served by `serve_packs.py` was not touched, so a
  download build would not offer the new pack until a release folder `us-pa-202611200917` is made.
- **The conformance reference uses the round-2 PA file** (`data/search/pa-search.sqlite`), as the round's
  test setting requires. The Kotlin engine on the new file is checked only by the device searches above
  and the demo timings, not query by query.
- **Some places were not searched in the app.** Peaks and caves were searched only in Python (mount
  davis, crystal cave). Ohio's new places were not searched at all; only their counts are known.
- **Item 15:**
  - no emulator case with two different routes at the lowest height;
  - not looked at: the night theme of the low sheets, TalkBack itself, a large font scale, and the
    results or place sheet lowered in landscape;
  - the whole-suite run of item 15 re-ran only the changed modules.

## Questions for the owner

1. **New icons (no artwork drawn).**
   - Waterfalls show the viewpoint (camera) icon and caves the attraction icon.
   - Should a waterfall and a cave icon be drawn?
   - The beach and peak icons already existed.
2. **Peaks.** Peaks are many: 1,589 in Pennsylvania, most of them hills nobody drives to. "peak" or
   "summit" near you lists the nearest ones. Keep them, or keep only peaks with a Wikidata link or an
   elevation?
3. **"Beach 1" after Enter.** Enter shows the three nearest matches nearest first, so Beach 11 and Beach 10
   come above Beach no.1. While typing, Beach no.1 is first. Is that order fine, or should an exact
   number match go first in the Enter list too?
4. **Item 15:**
   - Is the stepped drag right (one height per 40 dp), or should the sheet follow the finger?
   - Should a tap on the handle at the highest height go back to the lowest (as built), or lower one
     height?
