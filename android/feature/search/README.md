# :feature:search (search screen, place sheet, saved places screens)

Android library, Compose. Track S of the feature round (docs/architecture.md 2.6, 3.8;
docs/architecture-saved-places.md sections 1, 2, 4 and 5.4). Depends on `:core:api`,
`:core:search`, `:core:units` and `:ui:theme` only. Nothing here logs (rule PV2;
`python android/scripts/check_private_logs.py scan --module feature/search`). Report:
`docs/experiments/14-search-and-saved-places.md`.

| File | What it is |
|---|---|
| `SearchUi.kt` (contract) | `SearchFeature`, the UI-free search controller, and its UI state, events and outputs; the body is `SearchController.kt` |
| `SavedUi.kt` (contract) | `SavedPlacesFeature`, the controller of the saved places screen, the Edit menu, the save / rename / confirm dialogs and the map pick; the body is `SavedController.kt` |
| `SearchScreens.kt` (contract) | the seven composables: `SearchBar` (the pill), `SearchScreen`, `PlaceSheet`, `ResultsSheet` (batch B), `SavedPlacesScreen`, `SavedDialog`, `MapPickBar`; drawn in `SearchViews.kt` and `ResultsViews.kt` |
| `ResultsViews.kt` (batch B) | rule S11's results list at the bottom of the map (the query, the count, "Close results"; three heights on `:ui:theme`'s sheet frame, rule D10: the header alone, about three rows, about 60 %; dragged by the handle or by the rows at their top, tapped up; the height kept while a result's sheet is open), and S10's status line (a green, yellow or red dot with the words and the next change) and the week behind a tap |
| `HoursTexts.kt` (batch B) | rule S10's words: the status, the next change in US 12-hour time, the week, the first phone number and the first http(s) web address of OSM's values; for the place sheet's details height (batch G) every number in US form (`phones`) and every web address written out (`websites`, long ones shortened in the middle); parsed texts cached (256) |
| `SearchController.kt` | rules S1-S7 and SP4, SP10-SP15, SP18-SP23, SP30, SP31: debounce (`search_app.json` typing.debounce_ms), stale answers dropped, shortcuts, NO_SEARCH_DATA (its line says "add a state" only when nothing is installed; with states installed but none searchable it says the maps cannot be searched, and "Add a state" still leads to the States screen), the modes, the place sheet's actions; searches through `SearchHub` (made lazily on the search thread, then opened, checked and warmed up when the screen opens) or, in tests, a `PackSearcher` |
| `SavedController.kt` | 2.2-2.6, SP16-SP19, SP23-SP25, SP27, SP28, SP30, SP45: list rows and texts, menus, dialogs, the map pick with its zoom and pack-box rules |
| `SearchTexts.kt` | every text (word for word from the contracts; the few they leave open are marked "S's words") and the icon names |
| `SpriteIcons.kt` | icons cut from the style's sprite sheet (`style/sprites/{light,dark}@2x`) in the APK's assets by name (`poi_icons.json` for categories, `saved_home` / `saved_work` / `saved_place`); a name the sheet lacks draws a plain square, never a crash; a missing sheet or icon is remembered as missing (`SpriteCache`), so it is looked for once per process |
| `SearchViews.kt` | the composables; the keyboard flag of PV8 (`IME_FLAG_NO_PERSONALIZED_LEARNING`, no suggestions, no autocorrect) on the three text fields; rows as one focus stop each, "Edit {name}" a separate one, the "Show on the map" row as one switch, radio chips, polite live problem lines, 48 dp targets, rows that stack above font scale 1.3 (SP5, SP6); colours of the map's panels by day and night; the place sheet's three heights (batch G: the details height, `PlaceDetails`, a tap opens, a long press copies) |

What `:app` (track I) does with the outputs is docs/architecture-saved-places.md 5.5. In short:
make one `SearchFeature` and one `SavedPlacesFeature` in `AppGraph` (the search dispatcher is the
`beamline-search` thread), open search with `driving` and `destination` and a `SearchContext`
whose `accuracyM` comes from the fix, and show `SavedDialog` over everything while
`SavedPlacesFeature.ui.dialog` is set.

Tests:

- JVM (`sh tools/round/gradle.sh s :feature:search:testDebugUnitTest`; add `--rerun` after it to
  force a run when nothing changed): `SearchFeatureTest` (21), `SearchResultsTest` (10, batch B: S11's Enter, the
  20-result cap, nearest first, one match picked, none, not outside GO_TO or while driving, select, deselect, close, a new
  search; S10's hours on rows and the sheet, Call and Website, the clock, a recent's details read later; batch C2: the 20 truly
  nearest of 100 candidates within 25 miles or the nearest 5, results ended by a removed state, details by name and
  position, Call and Website again once ARRIVED), `SavedPlacesFeatureTest` (13),
  `SavedLeakTest` (1), `SpriteCacheTest` (2): docs/architecture-saved-places.md 10.1 items 1-8 and
  the controller items of docs/architecture.md 5.1 S, on a virtual clock with a fake of the packs. An engine test through
  the controller cannot run here (an Android library's unit tests get the Android variant of
  sqlite-bundled); the device test does it.
- Device (`sh tools/round/gradle.sh s :feature:search:assembleDebugAndroidTest`, installed with
  `tools/round/adb.sh <key> install -r` and run with `am instrument`, never `connectedAndroidTest`):
  `SearchScreensTest` writes the screenshots of docs/architecture.md 5.1 S and
  docs/architecture-saved-places.md 10.4 by day and night into the test APK's external files
  folder (`screens/`), checks the accessibility tree and the tap targets of SP5
  (`a11y-*.txt` there), asserts the keyboard flags of PV8 from `dumpsys input_method`, and writes
  `timing.txt` (6 rounds of a hub with and without the warm-up: open and check, the first two
  queries; min, median and max). `coldOpenTiming` runs only with `-e warm true|false`, one hub per
  process after the page cache was dropped (track S's scratch script
  `data/r3-s/device-cold-timing.sh`; emulator only, it needs root). The engine screenshots need P's
  PA search and address files pushed there as `pa-search.sqlite` and `pa-addresses.sqlite`. The
  test APK gets the app's style sprites through the `androidTest` assets line of
  `build.gradle.kts` and declares no network permission.
- Not checkable on the round's emulator image (AOSP, no Gboard, no TalkBack): Gboard's incognito
  mark of PV8 and a TalkBack pass. Both need a phone; track S asked for a step in
  docs/architecture-saved-places.md 10.3 (request `data/architecture/requests/s.md`).
