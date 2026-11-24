# 22. Next run, batch B: search results on the map, opening hours, phone and website

Status: **done on the emulator, 2026-11-18.** The second small batch of the run's features
(docs/next-round.md, "The owner's answers after part 1"): search results on the map after Enter
(item 1, with the owner's answer of 2026-11-16 and the lead's 20-result cap), opening hours, phone
and website on the place sheet and on the result rows (item 11, second bullet, with the owner's
answers of 2026-11-17), and three small carry-overs from batch A. Version **0.1.3 (versionCode 6)**.
Nothing on the owner's phone yet. The working record is `data/r3-run2/PROGRESS.md`, section
"Batch B" (not in git); screenshots and logs are under `data/r3-run2/b/` (not in git).

## What changed and why

### Search results on the map (rule S11)

- **Enter shows the matches on the map.** The search field's keyboard action (`SearchEvent.Submit`)
  in GO_TO while not driving asks the packs for up to `search_app.json` `on_map.max_results` (20,
  the lead's cap) matches near the ranking position (rule S1) and orders them by distance. None:
  the screen says "No results" as today. One: it is picked, exactly as a tap on it (a recent, its
  sheet, the map moves to it). Several: the search screen closes, `SearchFeature.results` holds
  them, the map draws a pin for each and a short list at the bottom shows the same rows (the query,
  "20 places", a close button; about three rows, dragged or tapped up to about 60 % of the
  screen). Typing without Enter keeps today's suggestion list.
- **Pins.** `MapScene.pins` gains `PinKind.RESULT` (one value added to the frozen `MapScene.kt`, 5.2
  procedure). They reuse the destination pin's art (blue, white dot) at 80 % and lie under every
  other pin; the selected one is the existing white "picked" pin at full size, so it stands out by
  colour and size (no new artwork). A tap on a result pin selects it.
- **Camera.** `CameraDirector.onShowResults(points, minZoom)`: browsing only, it fits the nearest
  results that still fit at the zoom from which the installed states draw (`state_min_zoom`, z8) or
  closer, never closer than `follow_zoom` (two matches 50 m apart), in the area above the list and
  below the search pill (the camera's top inset includes the pill's block while the results show).
  Farther matches keep their pins but are not in the box. A gesture or my-location ends the fit.
- **Pick, back, close.** A row or a pin opens that place's sheet through the existing pick path
  (part 1: the map moves to it above the sheet). Closing the sheet (its ×, Back, a tap on the map)
  goes back to the list and fits the results again. Closing the list (its ×, or Back) removes the
  pins; the camera stays. Opening a new search, Directions, Add stop or any trip phase ends the
  results. The pill stays on the map while the results show (a new search replaces them).
- **Not while guiding.** The view is only reached from the pill (shown only while browsing, S8);
  search during guidance (Add stop) is unchanged.

### Opening hours, phone and website (rule S10)

- **Data.** The search pack's `poi` table already holds OSM's `opening_hours`, `phone` and
  `website`. `SearchEngine.poiDetails(ids)` reads them by entry id, apart from the search itself:
  the engine's answers and the conformance reference do not change. The controller reads them on
  the search thread for every answer's POIs, and once for a sheet whose place has a pack and search
  id (a recent: they are read when the sheet opens, and the sheet is made again when they arrive).
  `Place` did not need new fields (the frozen model is unchanged); recents and saved places work as
  before. A saved place, a dropped pin and a POI tapped on the map have no search id, so they show
  no hours (see "Not done").
- **Parser.** Simon Poole's **OpeningHoursParser** (`ch.poole:OpeningHoursParser` 0.29.0, MIT,
  Maven Central, no runtime dependency; licence checked in its POM and LICENCE.txt, added to the
  notices: 72 entries) parses the text; Beamline's own `OpeningHours` (`:core:search`) evaluates
  it. Why not a ready evaluator: `io.leonard:opening-hours-evaluator` (MIT, built on the same parser)
  was looked at; its public-holiday check always answers "not a holiday" and its rules are
  combined by "any open rule minus closed rules", so `Mo-Fr 08:00-17:00; We 10:00-12:00` would be
  open all Wednesday: a wrong status, which the owner ruled out ("never a guess"). The evaluator
  here takes the subset that can be evaluated without guessing (`;` replaces earlier rules for the
  weekdays it names, except an `off` with times, which closes only those times; `,` adds; `24/7`;
  weekday ranges; several times a day; times past midnight, `18:00-02:00` and `18:00-26:00`;
  `off` / `closed`), leaves out rules that name only public or school holidays (no holiday calendar
  on the phone) and then says "Holiday hours may differ" under the week, and refuses everything
  else (dates, months, years, week numbers, the nth weekday, sunrise, open ends, `unknown`,
  fallback rules, a comment alone, anything the parser refuses): nothing is shown. On the
  Pennsylvania pack **97.1 %** of the places with hours are readable (25,013 of 25,747; 227 of them
  with holiday parts left out).
- **Status.** At the phone's clock and time zone: green "Open now", yellow "Closing soon" (from
  exactly one hour before closing), red "Closed now" (from the closing minute), always a coloured
  dot with the words; then the next change in US 12-hour time: "until 9 PM", "closes at 11 PM",
  "opens at 6 AM", "opens Mon at 8 AM" (a day or more away), "open 24 hours". On the sheet the week
  (Monday to Sunday, today in bold, "5 AM–3:30 AM", "Closed", "Open 24 hours") opens with a tap on
  the status line. The same status on every result row, in the search list and in the results on
  the map; recomputed each minute.
- **Call and Website.** Two more buttons on the sheet after Save/Edit, only when the pack has the
  value and never while driving (GUIDING, REROUTING, ARRIVED). "Call" opens the dialer with the
  first number (`ACTION_DIAL`, no permission). "Website" opens the first address (http or https; a
  bare domain gets `https://`; other schemes are dropped) in the phone's browser (`ACTION_VIEW`).
  The app itself opens no connection; rule N5 says so. Without an app for it a line says "No app on
  this phone can make calls" / "No browser on this phone can open it".
- **Test extra.** `--es hours_at 2026-11-18T22:15` sets the clock the statuses see (running on from
  there), `--es hours_at off` puts the phone's clock back. Read only in test-extras builds.

### Carry-overs from batch A

- a. `AndroidPositionSource.lastLocation` was already `@Volatile` (batch A, commit 25eff96); its
  comment now says who writes it (the listener on the main looper, and the flow's start) and who
  reads it.
- b. **Short windows (D8).** The speed pair is left out while there is no room for it between the
  top stack (banner, GPS chip, stop card) and the bottom block, and comes back when there is. Device
  test `DriveScreensTest.speedPairHidesInAShortWindowAndComesBack` at 360 x 390 dp: with the
  stop-reached card the pair is gone and nothing overlaps; without it the pair is back and covers
  nothing. Upright only: on its side the pair sits beside the panel, clear of the stack.
- c. `Sun.kt`: one comment that `crossings()` samples every 10 minutes and why that is enough (a
  crossing pair is hours apart; SunRule re-evaluates every minute). `:core:settings` is frozen:
  5.2 procedure.

## How it is proved

- **JVM:** `OpeningHoursTest` (8: 24/7, `Mo-Fr 08:00-17:00; Sa 09:00-12:00`, `Fr-Sa 18:00-02:00`,
  `off` and overrides, holiday parts, 14 unreadable texts, the boundaries one hour before closing
  and at closing, the time zone) and `PoiDetailsPackTest` (real PA pack: 20 Sheetz near downtown
  Erie, 9 with details, 6 with hours, all 6 readable; the pack-wide share) in `:core:search`;
  `SearchResultsTest` (10: Enter, the cap and the order, one match, none, not outside GO_TO or while
  driving, typing keeps the list, select / deselect / close, a new search, hours on rows and the
  sheet with Call and Website, the clock moving across closing and the weekend, a recent's details
  read later, the time and phone and web texts) in `:feature:search`; `ResultsCameraTest` (5: the fit
  above the list, never below z8 nor above `follow_zoom`, re-fit when the list grows, picked and
  gesture end it, not while a trip exists, result pins under the others and the selected one as the
  picked pin) in `:map:scene`.
- **Whole JVM suite** once at the end, guidance unchanged (`:nav:session` with the exit 24 golden
  trace): see "Numbers" below. Nothing that guidance uses changed, so no emulator drive was run.
- **Emulator** (beamline_s, API 36, release with test extras, us-pa installed, position downtown
  Erie), `data/r3-run2/b/shots/`:
  - `sheetz-day-results.png`: "sheetz" + Enter: 7 blue pins around Erie above the list "sheetz / 20
    places", rows "3814 Liberty Street 2.3 mi", "750 Peninsula Drive 3.7 mi", "8180 Perry Highway
    5.1 mi", "4662 West Ridge Road 6.3 mi" with a green "Open now · open 24 hours".
  - `sheetz-day-row4-sheet.png`: that row's sheet: white picked pin above the sheet at street zoom,
    "Open now · open 24 hours", buttons Directions, Save, Call, Website.
  - `sheetz-day-back-to-list.png`, `sheetz-day-back-to-browse.png`: Back gives the list again, then
    plain browsing with no pins, the camera where it was.
  - `sheetz-day-pin-sheet.png`: a tap on a pin opens "Sheetz, 8180 Perry Highway" (no details in OSM:
    no hours, no Call or Website); `sheetz-day-selected-among-pins.png` (zoomed out with the sheet
    open): the selected pin white and larger among the blue ones.
  - `mcdonalds-day-sheet.png`, `mcdonalds-day-week.png` (clock 12:30 Wednesday): "Open now · until
    3:30 AM", the week "Mon 5 AM–3 AM ... Wed 5 AM–3:30 AM (bold) ... Sun 5 AM–3 AM".
  - `mcdonalds-day-2215-closing-soon.png` (22:15): rows green "Open now · until 3:30 AM" and yellow
    "Closing soon · closes at 11 PM"; `mcdonalds-night-results.png`, `mcdonalds-night-sheet.png`
    (night map, 23:30): green "Open now", red "Closed now · opens at 6 AM".
  - Call: the dialer came to the front with a `tel:` DIAL intent; Website: the emulator's WebView
    browser came to the front with a VIEW/BROWSABLE `https:` intent (no screenshot kept of either:
    they show a business's number and page).
  - Device test `DriveScreensTest` 14/14 on beamline_s (`data/r3-run2/b/drive-device/`), the new
    short-window case included (`speed-short-card-day.png`: banner and card, no pair;
    `speed-short-plain-day.png`: sign 55 and badge 50 between the banner and the trip bar).

## Numbers

- Whole JVM suite: `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh s test --continue
  -Pbeamline.routingPacksWork=<abs>/data/gradle/s/routing-packs-test`: **728 tests, 0 failures, 1
  skipped** (SqlTimingProbe, as always; JUnit XML, `:nav:ferrostar`'s stale folder left out; 704 in
  batch A + 24 new). `Exit24DriveTest` (the golden trace) passes. Work folder deleted afterwards.
- Search conformance: `.venv/search/Scripts/python -I android/core/search/conformance/compare.py`:
  1,079 queries, top-10 ids and every field equal (the rows' fields did not change; reference not
  regenerated).
- `sh tools/round/gradle.sh s :app:assembleDebug :app:assembleRelease :app:lintRelease`: pass (lint:
  0 errors, 51 warnings, every module's lintAnalyzeRelease run).
- `python tools/check_contracts.py`: all the same, 41 frozen files unchanged after `--freeze`;
  `android/scripts/check_private_logs.py scan`: 0 breaches; `check_network_rule.py scan` and
  `privacy`: 0 findings; notices `resolve --key s` + `--check --deps data/r3-run2/b/notices`: passed,
  72 entries; the notices scripts' tests 32 OK; `copy_search_rules.py --check`: all copies match.
- Phone APK `data/android/apks/batchB/beamline-release.apk` (plain release, no test extras in its
  dex): **85,229,189 B**, SHA-256
  **04c7562da4490bc137d6e253b406925c1a6531628175cea04b899c5da1b000f6**, versionCode 6 / 0.1.3
  (aapt2 badging). On beamline_s: 0.1.2 (`data/android/apks/batchA/beamline-release.apk`) put back
  with `pm install -r -d`, then `install -r` of this APK: Success, versionCode 6, the app opens on its
  map (`b/shots/upgrade-0.1.2-to-0.1.3-open.png`). Emulator stopped.

## Contract changes

- `docs/architecture.md`: 2.5 `MapScene.kt` (`PinKind.RESULT`, frozen `:core:api`, refrozen) and
  `Scene.kt` (`MapSceneMapper.scene(..., results)`, `CameraDirector.onShowResults`, the
  `onPlaceLeft` KDoc); 2.6 `SearchUi.kt` (`ResultRowUi.hours`, `HoursUi`, `HoursDayUi`, `ResultsUi`,
  `ResultsEvent`, `SearchEvent.Submit`, `SearchOutput.ShowResults`, `PlaceAction.CALL` / `WEBSITE`,
  `PlaceSheetUi.hours` / `phone` / `website`, `SearchFeature.results`, `later`, `onResultsEvent`,
  `detailsLoaded`, `refreshHours`, `hoursClock`), `SearchScreens.kt` (`ResultsSheet`) and the
  `search_app.json` block (`on_map.max_results` 20; the file in `packbuilder/search/`, copied by
  `copy_search_rules.py`); rules K1 (the results' fit), S7 (Call, Website), new S10 and S11, D8 (the
  short window), N5 (the browser and the dialer, no request by the app); 5.2 (the new catalog entry);
  5.4 item 12 (`--es hours_at`); decisions 6.73-6.75.
- `docs/architecture-saved-places.md`: the same `SearchUi.kt`, `Scene.kt` and `SearchScreens.kt`
  excerpts.
- Frozen files changed by the 5.2 procedure (applied, blocks updated, `check_contracts.py
  --freeze`, checked): `android/gradle/libs.versions.toml` (`openinghoursparser` 0.29.0,
  `opening-hours-parser`), `core/api` `MapScene.kt`, `core/settings` `Sun.kt` (comment only).
- READMEs: core/search, feature/search, map/scene, map/layers, feature/drive; android/README.md
  version.

## Decisions taken here (and why)

- The results ordered by distance, nearest first, of the engine's 20 best (the lead's cap): the
  list is "near me"; the engine already ranks by score with distance decay.
- The camera fits only as many of the nearest as fit at z8 (the states' first zoom); the farther
  pins stay drawn. A fit of all 20 Sheetz around Erie would reach Franklin and Oil City at about
  z8.5 with the Erie ones crowded at the top.
- A result's sheet closing fits the results again (back to the results means seeing them); a tap
  on another map POI ends the fit (the driver is looking elsewhere).
- Opening a new search ends the results; the pill stays visible so a new search is one tap.
- `Place` unchanged: details are looked up by `packId` + `searchId` (no frozen model change, no
  recents format change; old recents get hours too).
- Call and Website are left out in ARRIVED as well (the sheet's existing "driving" flag, SP4).
- 12-hour US times always (the app's texts are US; the phone's 24-hour setting is not read).

## Not done, not verified

- Hours are not shown for a POI tapped on the map, a saved place or a dropped pin (no search id):
  looking a tapped POI up in the pack by name and position would be a small next step.
- The results view was not tried with a hardware or Gboard keyboard (the emulator's Enter key was
  sent with `input keyevent 66`); nothing on the owner's phone; no TalkBack pass.
- The result list's drag (pull up) was not exercised on the emulator; the tap on its header and the
  JVM state are.
- No emulator drive (guidance is untouched; the JVM golden trace passes).
- A night shot of the sheet's week was not taken (the day one was); the night list and sheet were.
- MapLibre device tests of map/layers (`MapLibreControllerDeviceTest`) were not run for the new pin
  image; the emulator shots show it.

## Questions for the owner

1. Results far away: around Erie the 20 nearest Sheetz reach Meadville and beyond, and the camera
   fits the nearest that fit at z8 (about 50 km across). Would a distance limit (for example 25 mi)
   be better than a count of 20?
2. Should a POI tapped on the map (not found by search) also show hours, phone and website (a
   lookup in the pack by name and place)?
3. "Closing soon" is the last hour (the lead's choice). Keep it, or 30 minutes?
4. Call and Website are also hidden at ARRIVED (as Save and Edit are). Should they be offered once
   arrived?
