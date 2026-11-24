# 20. Next run, part 1: the fixes

Status: **done on the emulator, 2026-11-18.** Part 1 of the run agreed with the owner on 2026-11-16
(docs/next-round.md, "The next run" and items 1 to 11): the fixes only, no new features or designs.
Three groups built it (key d: drop Ferrostar and the drive fixes; key m: map and camera; key s:
search, places and texts), each was reviewed, and the review findings were then checked, fixed or
refuted, and the whole app proved again from the final tree (key i). Version **0.1.1 (versionCode 4)**.
Nothing on the owner's phone yet: that is the next step. The working record is
`data/r3-run2/PROGRESS.md` (not in git); screenshots and logs are under `data/r3-run2/` (not in git).

## What changed and why

### Group 1: drop Ferrostar, and the drive fixes

- **Ferrostar dropped** (the owner's decision). `:nav:ferrostar` and `:routing:ferrostarcheck` are
  deleted with their includes, catalog entries, JNA, core library desugaring and the AppGraph wiring.
  Beamline's own geometric follower in `:nav:session` (which already decided the step advance) is the
  only follower (`GeometricFollowerFactory`). Notices: 170 entries before, 71 after, no exception
  (the GPL allow-list is empty).
- **End with Undo (rule R4).** End stays one tap and ends the trip at once; a "Trip ended" bar with
  Undo shows for 10 s (`END_UNDO_MS`). Undo sends the new `NavIntent.UndoEnd` (frozen `:core:api`
  `Intents.kt`, changed by the 5.2 procedure): the same stops, route type and options, re-planned from
  the current position. After the bar, End is final.
- **Short road names on one line (D1).** Names of up to 16 characters get no-break spaces
  (`Presentation.keepTogether`), so "Peach St" never breaks across two lines.
- **"Simulate drive" ruling (D4, section 8).** It plays the route without the scripted halt.

### Group 2: map and camera

- **Tilt of the driving view (item 10).** MapLibre was applying the commanded pitch: 287 of 290 samples
  read pitch 60.0 in this run's drive (Group 2 measured the same at 50). The cause was geometry:
  MapLibre's narrow field of view, the vehicle low on the screen and the banner covering the top of the
  map. The fix is `guide.pitch_deg` 50 → 60, the limit that applies (`MapLibreConstants.MAXIMUM_PITCH`
  = 60 in MapLibre Android 13.7.1; K3 now says so).
- **A picked place moves the map (item 2, K1).** A search result, saved place, recent or place from a
  sheet: BROWSE centered on it above the sheet at `follow_zoom`, capped outside the states.
- **Open where the driver is (item 5, K1, 5.4 item 3).** A cold start with location opens on the puck
  in BROWSE_FOLLOW at z16. Without a position, the map opens where it was left (`no_backup/last-camera.txt`).
- **The credit (item 8, 5.4 item 4).** One short "© OpenStreetMap" line that fades after 5 s or at the
  first touch; an info mark stays while browsing; a tap opens the "Map data" list (ODbL,
  openstreetmap.org/copyright), the same list as About's new "Map data". While guiding: the route
  preview and the first 5 s only.
- **Integration findings.** The heading follows a turn at the next fix (`guide.bearing_turn_deg` 30).
  The route ahead is drawn above the road labels while guiding (under POI and place names). The map
  no longer goes blank after a state is removed while choosing a route.

### Group 3: search, places and texts

- **Exact addresses first (S4).** A located house number adds `weights.house_located` (1.0) × the
  street name's tier value × the distance decay (both engines). `SearchService` scores the address
  lookup's answer on the engine's scale, so with Pennsylvania and Ohio installed "247 Main Street,
  Conneaut" gives Ohio's exact house first.
- **SP19.** A saved dropped pin's open sheet shows "Saved as ..." and Edit.
- **Downloads off.** "Downloads are off in this build. Use a file instead: a state file (.tar) copied
  to this phone."
- **US spellings** in every user-visible text (Kilometers and meters, licenses, Neighborhood, center,
  Theater and others; the full list is in `data/r3-run2/PROGRESS.md`, Group 3, item 4). Installed
  packs' "Neighbourhood" labels are rewritten by `ranking.json` `us_spellings`.

## The review findings and what was done (key i)

Every finding was checked against the tree first. There was no blocker and no major finding.

| # | Finding | Checked | Outcome |
|---|---|---|---|
| G1-1 | `DriveScreensTest` (device) compares banner texts with plain spaces | True: `assertTextEquals` compares exactly | **Fixed**: `assertPlainText` (no-break spaces read as spaces) for the banner texts; the a11y node labels are read the same way. Ran on the emulator: **12/12 pass** |
| G1-2 | Desugaring proof incomplete: lintRelease failed, library modules not linted | True: lint flagged `PropertyEscape` in the machine-local `android/local.properties` (the drive colon in `sdk.dir`, `beamline.searchDb`, `beamline.addressesDb`) | **Fixed**: colons escaped there (every reader is `java.util.Properties`, or `env.sh` and `check_private_logs.py`, which already unescape); `tools/fetch_android_sdk.py` now prints `sdk.dir` escaped. `:app:lintRelease` **passes** (0 errors, no NewApi). For the library modules, a bytecode scan (`data/r3-run2/i/api_scan.py`) read 1,310 release classes of 23 first-party modules: **0** of 12,475 java.*/javax.* references needs an API above 26 (with `--min-sdk 25` it finds the 18 API-26 references, so the scan works) |
| G1-3 | Nothing on screen between Undo and guidance (up to 15 s without a fix) | True | **Fixed**: after Undo the bar reads "Continuing the trip..." until the phase leaves IDLE (20 s cap); `AppRules.endBar` with a JVM test; R4 amended. Emulator without location: the bar was seen from 2 to 14 s after Undo, then "Continue to Downs Drive?" |
| G2-1 | A picked place stays the camera target, so the camera goes back to it when the sheet closes or another place's sheet opens | True (`place` was only cleared by a gesture, my-location or a phase change) | **Fixed**: `CameraDirector.onPlaceLeft()` (contract `Scene.kt` in 2.5 and its quote), called from `AppFlows.closeSheet` and from `showSheet` when another place replaces the sheet. New director test; K1 amended. Emulator: closing the Presque Isle sheet logged no camera move |
| G2-2 | The heading filter drops from weight 1 to 0.3 just above 90° | True | **Fixed**: weight 1 up to 4 × `bearing_turn_deg` (120°), falling linearly back to 0.3 at 5.5 × (165°), so a reversal (180°) is still smoothed. Test: 100/110/120° followed at once, no step over 0..180°. K3 and the module README amended; the K4 test's expected value follows (100° is now followed at once) |
| G2-3 | The info mark is nearly invisible at night | True | **Fixed**: a light ring around the night mark. Emulator `i/checks/credit-night-8s-mark.png`: clearly visible |
| G2-4 | The remembered camera is read on the main thread | True, but the same holds for `settings.json` and `app-state.json`, read at start-up | **Documented** as intended: one small read before the first frame. Section 1.2 and the `LastCameraStore` KDoc say so; writes stay on IO |
| G2-5 | Three contract sentences say the credit line stays above the pick bar | True | **Fixed**: "the credit (line or mark)" in architecture.md and the saved-places companion (two places) |
| G2-6 | The tilt fix is one number, not seen on the phone; which limit is real | True | K3 now names the real limit (`MAXIMUM_PITCH` 60; the native ~65 inset limit is never reached). The look on the phone stays an owner check |
| G3-1 | A log-only text ("Cancelled") was changed and listed as visible | True | **Reverted** to "Cancelled" (log texts stay); the PROGRESS line corrected. `android/scripts/phone/phone.py` looked for `install: Done` while the app logs `install status Done`: it now reads both forms (`_install_end`) |
| G3-2 | The bonus still lifts houses on streets that only share a word ("247 Main Street, Conneaut", PA only) | True | **Fixed** in both engines: no bonus for an `any_word` match. Reference regenerated; strict `:core:search:test` 70 tests, 0 failures; `compare.py` 1,079/1,079 equal; test sets unchanged (334/345, 94/99, 48/49). Against Group 3's reference, 2 of 1,065 answers changed: "247 Main Street, Conneaut" @erie (Peach Street first again, as before part 1) and "626 state st erie oh" @erie ("Erie" first again, as before part 1) |
| G3-3 | Far house with the typed number outranks nearer streets | A ruling, not a defect | **Kept** (decision below) |
| G3-4 | Two claims not backed by screenshots | True | Shown now: `i/checks/license-detail.png` ("License: PDDL-1.0"). The saved pin's sheet icon is left generic (SP19 asks for "Saved as" and Edit) |
| G3-5 | Recents and saved places stored earlier keep "Neighbourhood" | True | **Accepted**: an old recent shows the old spelling until it is searched again |

Also fixed: `MapLibreControllerDeviceTest.withTheOverviewTheRouteStaysUnderEveryLabelAtEveryZoom`
still expected the layer order without Group 2's `GUIDE_ROUTE_ON_TOP` layers (Group 2 had not run the
device test). It failed on the emulator and was corrected. Now **12/12 pass**.

## How it is proved (from the final tree, key i)

| Check | Result |
|---|---|
| `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh i test --continue -Pbeamline.routingPacksWork=<abs>/data/gradle/i/routing-packs-test` | BUILD SUCCESSFUL; **690 tests, 0 failures, 1 skipped** (S's timing probe), 20 modules, counted from the JUnit XML (stale results of the deleted `:nav:ferrostar` left out). The 1.8 GB work folder was deleted afterwards |
| `python tools/check_contracts.py` | 38 contract files, 20 excerpts, 5 data files: all the same; 39 frozen files unchanged |
| `android/scripts/check_private_logs.py scan` | 169 files, 30 marked lines, **0 breaches** |
| `check_network_rule.py scan` / `privacy` | 0 findings / 0 private IPv4 literals |
| `notices.py resolve --key i`, then `--check` | **passed, 71 entries**, 141 artifacts, 4 native libraries, no desugaring; the about scripts' Python tests pass |
| `sync_data.py --check`, `copy_search_rules.py --check`, `sync_assets.py check` (and `sync_art.py --check`) | all in sync |
| `:app:assembleDebug`, `:app:assembleRelease` (plain and `-Pbeamline.testExtras=true`), `:app:lintRelease` | all build; lint 0 errors, no NewApi |
| Device tests on beamline_i: `DriveScreensTest`, `MapLibreControllerDeviceTest` | 12/12 and 12/12 |

On the emulator (beamline_i, API 36, the release build with test extras, us-pa installed, installed
with `install -r` over the 0.1.0 (3) build already there):

| Run | Result |
|---|---|
| Section 8 clean drive, day (`data/r3-run2/m/drive.py default data/r3-run2/i/e2e/day day`) | banner advances at **170.2 / 221.3 / 250.3 s** (golden trace 171 / 222 / 251), ARRIVED **288.4 s** |
| The same by night | **170.3 / 221.3 / 250.4 s**, ARRIVED **288.4 s** |
| wrongturn (`data/r3-run2/d/drive.py wrongturn`) | suspected 202.3 s, REROUTING 206.3 s, four RouteReplaced REROUTE, the new route's banner 237.4 s, ARRIVED 317.5 s; "Finding a new route..." on screen (`wrongturn-replaced-2s.png`) |
| Undo (`data/r3-run2/i/undo_i.py <out> fix\|nolocation`) | "Trip ended / Undo" bar; with a position, GUIDING again 7.5 s after the End (Undo was tapped about 5 s after it, so roughly 2 s of planning); without location, "Continuing the trip..." from 2 to 14 s, then "Continue to Downs Drive?" |

What the screenshots show (opened and looked at):

- `i/e2e/day/default-u2.png` (row 4, after the gore of exit 24): the tilted guide view. The roads and
  labels narrow towards the banner. The banner reads "Turn left onto US 19 /" with "Peach St" whole
  on the next line, plus lanes and the "Then" chip.
- `i/e2e/night/default-start-2s.png`: the night guide view, tilted, with "© OpenStreetMap" at the
  bottom left in the first seconds.
- `i/checks/open-1-cold-follows-puck.png`: a cold start in downtown Erie on the puck at z16, the
  my-location icon filled, the short credit line at the bottom left.
- `i/checks/pick-3-place.png`: "presque isle" searched and picked; the map moved to the park, with the
  pin centered above the place sheet at z16 and the credit faded to the "i" mark.
- `i/checks/credit-night-8s-mark.png`: the night mark with its new light ring, clearly visible.
- `i/checks/about-map-data.png`: About shows Version 0.1.1 (4) and "Map data": the ODbL line with
  openstreetmap.org/copyright, the US overview sources and Pennsylvania's sources, then
  "Open-source licenses".
- `i/checks/license-detail.png`: "City of Scranton 911 address points / License: PDDL-1.0".
- `i/e2e/undo/fix-1-trip-ended-bar.png`: the dark "Trip ended" bar with a blue Undo.
- `i/e2e/undo/nolocation-2-continuing.png`: the "Continuing the trip..." bar without an action.
- `i/e2e/wrongturn/wrongturn-replaced-2s.png`: "Finding a new route..." in the banner.

## Measurements

| | Value |
|---|---|
| Plain release APK (`data/android/apks/part1/beamline-release.apk`) | **85,144,027 B**, SHA-256 `b62a85010f4b369deec87606b3098ae6bfda5ea1dbf6667fed2f3058f7956888` |
| Its application id and signing certificate | `io.github.spysyweeb.beamline`; certificate SHA-256 `df239631...e393bc`, the same as the current kit's `data/android/apks/beamline-release.apk` (0.1.0, versionCode 3): `install -r` works |
| Release with test extras | 85,160,411 B (Group 1: 96,600,097 B before dropping Ferrostar) |
| Debug APK | 99,009,192 B (report 19: 111,632,664 B) |
| Per ABI (plain release) | arm64-v8a ~28.3 MB, armeabi-v7a ~23.8 MB, x86 ~29.1 MB, x86_64 ~29.0 MB |
| Guide pitch while following (day drive, 290 samples, 1 Hz) | 60.0 in 287; the rest are the first second and ARRIVED |
| Search test sets (Python top-1) | tuned 334/345, held-out 94/99, review 48/49 (unchanged) |

## Contract changes

Groups 1 to 3 (details in their sections of `data/r3-run2/PROGRESS.md`):

- docs/architecture.md: sections 1 and 1.1 (module map without Ferrostar), 2.1 (`Place.savedId`
  KDoc), 2.3 (`Ports.kt`, `Intents.kt` with `UndoEnd` and `END_UNDO_MS`), 2.4 (the FerrostarFollower
  block becomes a record), 2.5 `Scene.kt`, 2.12 (`last-camera.txt`), 3.7 (camera.json pitch 60 and
  `bearing_turn_deg`; K1, K3), 3.8 S1, S4, S5, 3.9 D1 and D4, 3.10 R4, section 4 (manifest, N5, PV4),
  5.1, 5.2, 5.4 items 1 to 4 and 13, decisions 6.14, 6.15, 6.34, 6.35 and 6.38, sections 7 and 8.
- docs/architecture-downloads.md (N5, the downloads-off text) and docs/architecture-saved-places.md
  (SP19, the `Place.savedId` quote).
- Frozen and refrozen by the 5.2 procedure: `core/api` `Intents.kt` and `Model.kt`,
  `android/settings.gradle.kts`, `android/gradle/libs.versions.toml`.
- Contract data: `packbuilder/search/ranking.json` (`weights.house_located`, `us_spellings`; the
  ranking hash changes), `camera.json` (source and module copy).

Added by the review fixes (key i), none to a frozen file:

- 2.5 `Scene.kt` block: `CameraDirector.onPlaceLeft()` (code and quote).
- 3.7 K1: the picked place stops being a target when its sheet closes or a map tap replaces it.
- 3.7 K3: the bearing weight stays 1 up to 4 × `bearing_turn_deg` and falls back to the plain weight at
  5.5 ×. Which pitch limit applies (`MAXIMUM_PITCH` 60).
- 3.8 S4: no exact-address bonus for an `any_word` match; `ranking.json` `_doc` says the same (rules
  copied with `copy_search_rules.py`).
- 3.10 R4: the "Continuing the trip..." bar after Undo.
- 1.2: the start-up reads on the main thread by design.
- docs/architecture-saved-places.md (two sentences) and the map-pick paragraph of docs/architecture.md:
  "the credit (line or mark)".

Texts changed by the review fixes: new "Continuing the trip..." (R4). The log text "Canceled" went
back to "Cancelled" (not visible).

## Decisions taken here (and why)

- **A far house with the typed number stays first** (for example "1 main st" near State College →
  1 Main Street, Bellwood, 45.65 km). The number was typed, and the distance decay already halves the
  bonus at 25 km. If the owner finds it wrong on the phone, the cheap limit is no bonus beyond a few
  `scale_km` (both engines, reference regenerated).
- **Old recents keep "Neighbourhood"** until searched again: relabeling stored subtitles would touch
  the saved and recent stores for a cosmetic gain.
- **The remembered camera stays a main-thread read** at start-up (as the settings stores'); documented.
- **`local.properties` is escaped on this PC, and the SDK tool prints the escaped form**, rather than
  disabling lint's PropertyEscape.
- **The device tests ran with `connectedDebugAndroidTest`** through `gradle.sh` on this track's own
  emulator (ANDROID_SERIAL), after the app checks were done. Section 5.3 forbids it only where it would
  uninstall pushed data; these library test packages have none.

## Not done, not verified

- Nothing on the owner's phone: the pitch 60 look, the credit and the open-on-the-puck start all
  remain to be seen there.
- The two-state Conneaut case is proved on the JVM (real us-pa and us-oh packs), not on the emulator.
- No API 26 to 28 emulator run. The lack of desugaring is proved by lint (app), the bytecode scan
  (libraries) and the release build, not by a run on an old Android version.
- Undo during REROUTING and Undo of a trip with several stops were not tried on the emulator.
- Open for the lead or owner: the unused OSRM request (6.15); whether shields should go back above the
  route line while guiding; the Harrisburg/State College far-house ruling above; rebuild all state packs
  together when new packs are made (`ranking_sha256` changed).
