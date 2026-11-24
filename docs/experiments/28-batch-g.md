# 28. Next run, batch G: the place sheet drags up to all its details; the waterfall and cave icons

Status: **done, 2026-11-20** (key m). Item 20 of docs/next-round.md, with the owner's answer that it also
covers a business tapped on the map and saved or recent places, and the two icons the owner approved on
2026-11-20. Version **0.1.9 (versionCode 12)**. No new packs. Nothing is committed: the lead commits.
Scratch, shots and logs are in `data/r3-run2/g/` (not in git); the working record is
`data/r3-run2/PROGRESS.md`, section "Batch G".

## Item 20: a third height of the place sheet

The owner asked to "drag up on location pages" to see the hours of every day and the phone number and
website spelled out. The place sheet now has three heights on the shared frame of rule D10
(`SheetSteps.THREE`, the same steps, tap, screen-reader actions and camera insets as the results list):

| Height | What it shows |
|---|---|
| Lowest | icon, title on one line, close (as batch F) |
| Middle (a new place opens here) | the sheet of batch B: title, subtitle, distance, the status line, the buttons |
| Details (new) | the middle part, then what the place is, the whole week, every phone number, every website, the postal address |

- **Getting there.** A drag up (one height per 40 dp), a tap on the handle or the title line, the
  screen reader's "Raise the sheet", or a tap on the status line ("Open now · until 6 PM"; its screen-reader
  hint is "show all details"). Before, that tap opened the week inline; the week now lives at the details
  height only. From the details height a tap on the handle goes back to the lowest (D10).
- **Height.** At most 70 % of the window; the part under the header scrolls inside it (a drag down
  scrolls to the top first, then lowers the sheet). A first try at 80 % put the place under the search
  pill (`03-place-details-80pc`), so 70 % it is. The camera insets follow the measured sheet as before, so
  the place is fitted into the map above it. The height survives a turn of the phone (`rememberSaveable`).
- **The week.** Monday to Sunday, today in bold, the same texts as rule S10 ("8 AM–6 PM", "Closed", "Open
  24 hours"), "Holiday hours may differ" where holiday rules were left out. Nothing when the place has no
  readable hours.
- **Phone numbers.** Every number of OSM's `phone` (split by ";"), each on its own line. A US-shaped number
  (10 digits, or 11 starting with 1, with spaces, dashes, dots, brackets or "+1") is written "(814)
  555-0123" and dialled as "+18145550123". Any other (another country, an extension, a short code) is
  shown as the pack has it, and dialled with its digits when it has at least 7.
- **Websites.** Every address of OSM's `website` that a browser can open (the batch B rule: http or https,
  a bare domain gets https://), written without "http://" or "https://" and a trailing "/"
  ("example.com/menu"). Longer than 40 characters, it is shortened in the middle with "…".
- **Taps.** A tap on a number or a website sends `PlaceSheetEvent.OpenOutside`, and the app opens the
  dialer (`ACTION_DIAL`) or the browser (`ACTION_VIEW`) exactly as the Call and Website buttons do. The
  app opens no connection itself (N5). A long press copies the line (the whole web address, the address
  too) and shows a short "Copied" note for 2 s.
- **While driving.** During GUIDING and REROUTING the lines are written out but are plain text: a tap
  and a long press do nothing, and the Call and Website buttons are not there (batch B). Once the trip is
  ARRIVED they work again, as the buttons do. That is the owner's ruling after batch B (S10, decision
  6.80). The task text for this batch listed ARRIVED with the driving phases; the recorded ruling was
  kept, and S7's sentence that still said ARRIVED was corrected to match S10.
- **What else is on the details height (from the packs only).**
  - What the place is: the category's label ("Post office", "Car wash", "Waterfall"), or for an entry
    type the categories do not name, the OSM value in words ("Hairdresser"). The icon alone is often
    ambiguous (a map POI gets a generic building icon), and the words cost one line.
  - The postal address from the pack's `addr:*` tags and its state ("2108 East 38th Street, Erie, PA
    16510"). A place tapped on the map has no subtitle at all, and a search result's subtitle has no
    state or ZIP code. This is the line to read out or copy. There is no address line without a street.
  - Nothing else: the pack has brand and operator too, but they mostly repeat the name.

## The same details wherever the place came from

Batch C2 had already added the lookup by name and position (`SearchEngine.detailsNamedNear`, rule S10).
Report 22 was written before it, so its "Not done" was out of date. This batch measured the lookup and
fixed the three places where it could still miss or mislead:

1. **A saved place is looked up by its own name.** The sheet of a saved place carries the owner's name
   for it ("Home", "Mail drop"). The lookup used that name and found nothing. It now uses
   `SavedPlace.placeName`, the place's own name when it was saved.
2. **A search id is read only while it is still the place.** Search ids move when a pack is rebuilt:
   the Erie Zoo was entry 433934 in the 11-16 Pennsylvania build and is 435720 in the 11-20 build. A
   recent kept from before the new pack would have read the details of whatever entry now holds its old
   id. `SearchEngine.isNamedNear` now checks that the entry is a POI with the same name key within
   `details.match_within_m` of the place. If it is not, the place is looked up by name and position
   (`SearchHub.detailsOf`).
3. **Address and category come along.** `PoiDetails` gained `address` and `category`. The same-name
   check compares only hours, phone and website. A store's fuel canopies have the store's name but a
   different category, and must not make the answer unsure.

The matching rule is unchanged: the same name key (the search normaliser's key, so case, punctuation and
abbreviations do not matter, but a different name never matches), within 100 m
(`search_app.json details.match_within_m`). It is shown only when every same-named POI there that has
hours, phone or website has the same ones; otherwise nothing is shown. A dropped pin is never looked up.
The distances below show why 100 m is enough and why nothing wider is needed.

### Measured: map POIs in Erie

`data/r3-run2/g/lookup_sample.py` reads every named point of the `pois` layer of the PA map tiles (zoom 14,
the pack's highest) in a box around Erie (42.06 to 42.16 N, 80.20 to 79.98 W). It takes each name the way
the app does (`name:en`, else `name`) and runs the same lookup on the PA search file
(`g/lookup-erie.txt`):

| | Count |
|---|---|
| Named map POIs (points) | 3,442 |
| Found (a same-named search POI within 100 m) | **2,622 (76.2 %)** |
| without bus stops, landuse labels ("residential", "retail" plazas), amusement rides and the like | 2,642, **2,608 found (98.7 %)** |
| Found, with hours, phone or website shown | 373 (159 with hours) |
| Found, pack has none of them | 2,249 |
| Same-named POIs disagree (nothing shown) | 0 |
| Distance tile point to the search entry | median 0.4 m, 90 % 3.0 m, max 97.5 m |

The 820 misses are mostly not businesses: 365 bus stops, 180 residential and 77 retail landuse labels
(shopping plazas), 63 playgrounds, and the rides of the amusement park. The airport label lies 120 m from
its search entry. Every map POI whose search entry has hours, phone or website gets them.

On the emulator (beamline_m), the same 3,442 points through the Kotlin engine
(`DetailsLookupTimingTest`, a device test that runs only when its files are copied in;
`g/lookup-device.txt`): the same 2,622 found and 373 with details. One `detailsNamedNear` call takes
**median 0.29 ms, 90 % 0.45 ms, 99 % 0.89 ms, max 6.3 ms** (first pass), 0.26 ms median on a second pass.
Opening the file takes 86 ms (the app's hub has it open already). The lookup runs once per sheet on the
search thread.

## The two approved icons

- `assets/icons/beamline/waterfall.svg` and `cave.svg` are copied byte for byte from
  `data/r3-run2/icons-approved/` (19 x 19 frame, grey template colours, the approval line kept).
- `assets/icons/icons.json`: two entries, source beamline, colour group green.
- `assets/poi_icons.json` and its `:core:search` copy (`copy_search_rules.py`, SOURCES.json): the
  waterfall category now points at `waterfall` (was viewpoint), cave at `cave` (was attraction).
- Built: `build_art.py --only sprites style check` (sprite sheets light/dark, 1x/2x, the style bundle,
  `check_poi_icons` ok; `g/build_art.log`), then `sync_assets.py style` (the app's assets/style and
  bundle-version.json). Skipped: shields, import and markers (their inputs did not change), and views /
  sheet / sheetpng (renders for approval, used neither by the app nor by the map).
- **Not on the map itself.** The PA map tiles have no waterfall or cave kind (the Protomaps profile
  does not export `natural=waterfall` or `natural=cave_entrance`; none in the Ricketts Glen box), so no
  `map_kinds` entry was added. The icons show on the search rows and the place sheet. The result pins
  keep the destination art (rule S11).

## On the emulator (beamline_m, phone size, `g/shots/`, each looked at)

The case is the Erie Post Office on East 38th Street (a public place from the search pack). It has
weekday and Saturday hours, one phone number (no business in the Erie box has two) and a website.

1. **Middle** (`01-place-middle`): the sheet as before: post office icon, title, "2108 East 38th Street,
   Erie", 3.3 mi, "Open now · until 6 PM", Directions, Save, Call, Website.
2. **Lowest** (`02-place-low`): icon, "Erie Post Office", close; the map re-fitted.
3. **Details** (`03-place-details`, after a tap on the status line):
   - the middle part, then "Post office";
   - Hours Mon–Fri 8 AM–6 PM with Fri in bold (2026-11-20 is a Friday), Sat 8 AM–1 PM, Sun Closed;
   - Phone "(800) 275-8777" and Website "tools.usps.com/locations/details/1436442" in the link colour;
   - the pin is fitted in the map above the sheet, under the pill.
4. **Scrolled, long press** (`04-details-scrolled-copied`): the address "2108 East 38th Street, Erie, PA
   16510" at the bottom. Android's own clipboard preview shows the copied https address. The
   app's "Copied" note was already gone when the shot was taken; the device test checks it.
5. **A tap on the number** opened the phone's dialer with "+1 800-275-8777" (the shot script will not
   capture another app; its screen texts were read).
6. **The same business tapped on the map** (`07-map-poi-details`, MAP_POI): the same week, number and
   website. The map POI has no subtitle and a generic building icon (as before this batch).
7. **Saved** as Other, renamed "Mail drop" (`08a-save-dialog`). After the app was restarted, the saved
   place's sheet (`08-saved-place-details`: "Mail drop", "Erie Post Office", Edit) shows the post office's
   details. Before this batch it showed none.
8. **Without hours** (`09-place-without-hours`, Niagara Car Wash tapped on the map): no status line and
   no Hours part. "Car wash", the phone, "www.niagaracarwash.net" and the address are shown.
9. **A dropped pin** (`10-dropped-pin-details`): coordinates only, no details.
10. **While guiding** (`11-details-while-guiding`, a trip to the car wash, the post office tapped on the
    map): Add stop and Directions only. The number is in the plain text colour. A tap and a long press on
    it did nothing: the app stayed in front and no "Copied" note appeared.
11. **Icons, day** (`12-waterfall-day`: "waterfall" at Ricketts Glen, 20 falls; `14-cave-day`: "cave"
    near the Aitken caves, 17 caves): the new icons on every row, green on pale green.
12. **Icons, night** (`16-waterfall-night`, `15-cave-night`, map style Night): bright green on the dark
    frame. `17-waterfall-sheet-night-details`: Mohawk Falls' sheet with the waterfall icon; its details
    height shows "Waterfall" only (no hours, phone or address in OSM). The map style was set back to
    Automatic afterwards.

**Section 8 clean drive** (the test-extras build of 0.1.9): banners at 221.3 and 250.4 s, ARRIVED
at 288.4 s (`g/drive.log`, `g/e2e/default/`).

## The batch's end

- **Version:** 0.1.9, versionCode 12 (`android/app/build.gradle.kts`).
- **Phone APK:** `data/android/apks/batchG/beamline-release.apk`, **85,269,569 bytes**, SHA-256
  `a3e0e6ceab4b144c37389372df0d9003f63ae6dbc7769c3402fc42c599bb7a3a`. Installed with `install -r` over 0.1.8 (`data/android/apks/batchF/beamline-release.apk`) on
  beamline_m (0.1.8 put back first with `pm install -r -d`): Success, 0.1.9 / 12; the PA pack and the three
  saved places kept.
- **Emulator:** beamline_m has the plain 0.1.9 release, the PA pack of batch F and a third saved place
  ("Mail drop", the post office). It is stopped.

## Proved (and how to re-run)

- **JVM tests of the touched modules.**
  - `sh tools/round/gradle.sh m :feature:search:testDebugUnitTest :core:search:test --tests
    '*PoiDetailsPackTest*'`: SearchResultsTest 17 (+3: the details lines while guiding and arrived, a
    saved place by its own name and a dropped pin never, the phone and website texts);
  - PoiDetailsPackTest 4 (+2: address, category and `isNamedNear` on the PA file;
    `postalAddress` / `categoryWords`).
- **Whole JVM suite.** `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh m test --continue
  -Pbeamline.routingPacksWork=<abs>/data/gradle/m/routing-packs-test`: BUILD SUCCESSFUL, **780 tests, 0 failures, 1 skipped** (JUnit XML; 775 before + 5 new) (`g/all-tests.log`).
- **Device tests (beamline_m).**
  - `SheetHeightsScreensTest`, 5/5. The place sheet now opens "Half open". New: `placeSheetDetailsHeight`
    (the status tap raises to the details height; the week, both numbers and the website are there; a tap
    sends OpenOutside CALL "+18145550100" and WEBSITE; a long press shows "Copied"; the actions and the
    handle tap). New: `placeSheetDetailsWhileGuiding` (no OpenOutside, no "Copied", no Call).
  - `DetailsLookupTimingTest`: the numbers above.
  - How to run them: `:feature:search:assembleDebugAndroidTest`, then `adb.sh m install -r` the test
    APK, then `am instrument` (PROGRESS.md has the `run-as` copy of the lookup files).
- **Checks.**
  - `python tools/check_contracts.py`: all the same, 44 frozen files unchanged, after `--freeze` for
    Sheets.kt (comments only);
  - `android/scripts/check_private_logs.py scan`: 0 breaches;
  - `check_network_rule.py scan`: 0, `privacy`: 0;
  - `android/routing/scripts/sync_data.py --check`, `copy_search_rules.py --check`, `sync_assets.py
    check`, `check_poi_icons.py`: all pass.
- **Builds.** `sh tools/round/gradle.sh m :app:assembleDebug :app:assembleRelease :app:lintRelease`:
  pass; lint 0 errors, 54 warnings, 1 hint (`g/final-build.log`).

## Contract changes

- **docs/architecture.md:**
  - S7: Call and Website not while guiding, again once ARRIVED (the sentence matches S10 now);
  - S10: an id is read only while it is still the place (`isNamedNear`); a saved place by `placeName`;
    the agreement on hours, phone and website only; the details height (what it shows, the phone and
    website forms, tap, long press, driving);
  - D10: the place sheet has three heights, a new place opens at the middle one;
  - decision 6.87;
  - the `SearchUi.kt` FILE block.
- **docs/architecture-saved-places.md:** the `PlaceSheetUi` excerpt (new fields).
- **tools/frozen-files.sha256:** `ui/theme/.../Sheets.kt` (comments only: MIDDLE and THREE also serve the
  place sheet).
- **Code API:**
  - `PoiDetails` + `address`, `category`, `hasContact`;
  - `SearchEngine.isNamedNear`, `SearchService.isNamedNear`, `SearchHub.detailsOf`;
  - `PlaceSheetUi` + `phones`, `websites`, `address`, `category`, `canOpenOutside`;
  - `ContactUi`; `PlaceSheetEvent.OpenOutside`;
  - `HoursTexts.phones`, `websites`, `shortened`.
- **Rule files and art:**
  - `assets/icons/beamline/waterfall.svg`, `cave.svg`; `assets/icons/icons.json`;
  - `assets/poi_icons.json` and its copy, with SOURCES.json;
  - the style bundle's sprites and the app's assets/style copy with `bundle-version.json`.
- **New UI texts (US spelling):** "Hours", "Phone", "Website" (caption), "Address", "Copied", "Copy",
  "Open the website", "Show all details".
- **New test tags:** place-status, place-details, detail-phone-&lt;i&gt;, detail-website-&lt;i&gt;,
  detail-address, hours-week.
- **README:** android/feature/search (HoursTexts, SearchViews rows).

## Not verified

- **Nothing on the owner's phone.** The stepped drag and the handle tap at the highest height are still
  the owner's to try (the answer after batch F).
- **Two phone numbers on a real place.** No business in the Erie box has two numbers in OSM, so two
  numbers were shown only in the device test with made-up numbers.
- **Not looked at:** the details height in landscape, with a large font, or under TalkBack itself
  (the semantics are checked in the Compose test); the app's own "Copied" note on the emulator
  screen (it is in the device test; Android's clipboard preview showed on the screen).
- **A recent from the old pack.** A recent made before the 11-20 pack was not tried on the emulator.
  The `isNamedNear` check is proved on the PA file in PoiDetailsPackTest.
- **Seen, not changed:**
  - a POI tapped on the map gets a generic building icon and no subtitle (as before);
  - while the camera follows the position, opening a sheet does not move the camera to the place, so
    the place can sit under the search pill (`07`, `08`);
  - the place sheet is 95 % opaque, so the trip bar shows faintly through it while guiding (`11`).

## Questions for the owner

1. **Is 70 % of the screen right for the details height?** The rest scrolls inside the sheet.
2. **Copying while driving.** While guiding, a long press on a number does not copy it either, so
   nothing on the sheet invites a touch while driving. Should a passenger be able to copy?
3. **What else goes on the details height?** It now shows what the place is and its postal address.
   Brand and operator are in the packs too, but they mostly repeat the name, so they were left out.
