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
| `ResultsViews.kt` (batch B) | rule S11's results list at the bottom of the map (the query, the count with where it is ranked from, "· Near Corry" (P6), "Other matches" over the name-only rows (P2), "Close results"; item 51's stop results: "Working out detours..." and no rows until they are sorted with "+N min", the fallback note, the card with the time it adds, the arrival and "Add stop"; a place handed over by another app during a trip (item 52) opens as one card without a list, with "Go here instead" under "Add stop"; three heights on `:ui:theme`'s sheet frame, rule D10: the header alone, about three rows, about 60 %; dragged by the handle or by the rows at their top, tapped up; the height kept while a result's sheet is open), and S10's status line (a green, yellow or red dot with the words and the next change) and the week behind a tap |
| `HoursTexts.kt` (batch B) | rule S10's words: the status, the next change in US 12-hour time, the week, the first phone number and the first http(s) web address of OSM's values; for the place sheet's details height (batch G) every number in US form (`phones`) and every web address written out (`websites`, long ones shortened in the middle); parsed texts cached (256) |
| `SearchController.kt` | rules S1-S7, S11, S12 and SP4, SP10-SP15, SP18-SP23, SP30, SP31 (0.1.40: a new stop looked for along the trip's route and shown as stop results that wait for the detours, item 51; a typed row keeps its list, P44; the best match then the nearest, P6; a category's own places before name matches, P2; "Near Corry", P6; Undo after clearing recents, P45; a spelling hint, P47; "Choose start", P22; the field focused in every mode, P26): debounce (`search_app.json` typing.debounce_ms), stale answers dropped, shortcuts, NO_SEARCH_DATA (its line says "add a state" only when nothing is installed; with states installed but none searchable it says the maps cannot be searched, and "Add a state" still leads to the States screen), the modes, the place sheet's actions; searches through `SearchHub` (made lazily on the search thread, then opened, checked and warmed up when the screen opens) or, in tests, a `PackSearcher` |
| `SavedController.kt` | 2.2-2.6, SP16-SP19, SP23-SP25, SP27, SP28, SP30, SP45: list rows and texts, menus, dialogs, the map pick with its zoom and pack-box rules (0.1.40: a point's street from the app, `streetFound`, shown on the pick bar and in the Save dialog and saved with the point, P18; Save on for a point's empty name, a tap answers "Type a name.", P57; Remove's confirm red and `SavedOutput.Removed` for the app's Undo, `SavedEvent.UndoRemove`, P58; the start chosen on the map, P22) |
| `PlaceKeys.kt` (0.1.40) | `placeSheetKey`: what keeps the place window's height (the place's source, point, name and pack entry; not its line or saved id, so a street found later or a Save keep it, F42) |
| `SearchTexts.kt` | every text (word for word from the contracts; the few they leave open are marked "S's words") and the icon names |
| `SpriteIcons.kt` | icons cut from the style's sprite sheet (`style/sprites/{light,dark}@2x`) in the APK's assets by name (`poi_icons.json` for categories, `saved_home` / `saved_work` / `saved_place`); a name the sheet lacks draws a plain square, never a crash; a missing sheet or icon is remembered as missing (`SpriteCache`), so it is looked for once per process |
| `SearchViews.kt` | the composables; the keyboard flag of PV8 (`IME_FLAG_NO_PERSONALIZED_LEARNING`, no suggestions, no autocorrect) on the three text fields; rows as one focus stop each, "Edit {name}" a separate one, the "Show on the map" row as one switch, radio chips, polite live problem lines, 48 dp targets, rows that stack above font scale 1.3 (SP5, SP6); colours of the map's panels by day and night; the place sheet's three heights (batch G: the details height, `PlaceDetails`, a tap opens, a long press copies). 0.1.40: opaque panels (P9); the place window says where the place is at every height ("2.3 mi · where", the note too; P9, P51, P53), a dropped pin draws a pin (P17), the peek buttons wrap at large text (F41); the window's and the results list's content keeps off the navigation bar and a cutout at the sides (`sheetInsets()`, P1); Save and Rename open with the name selected and an empty Name shows "Name this place" (P56, P57); the dialogs scroll with their buttons kept in view (F47); Remove's confirm is red (P58); a titled search has Back and its title on one row, its rows have icons (P60) |

What `:app` (track I) does with the outputs is docs/architecture-saved-places.md 5.5. In short:
make one `SearchFeature` and one `SavedPlacesFeature` in `AppGraph` (the search dispatcher is the
`beamline-search` thread), open search with `driving` and `destination` and a `SearchContext`
whose `accuracyM` comes from the fix, and show `SavedDialog` over everything while
`SavedPlacesFeature.ui.dialog` is set.

Tests:

- JVM (`sh tools/round/gradle.sh s :feature:search:testDebugUnitTest`; add `--rerun` after it to
  force a run when nothing changed): `StopResultsTest` (27: item 51, and item 52's handed-over card and `lookUp`; 0.1.40 review: stop results carry no P2 flag, a look's figure survives the detours' end, a saved row of a typed list keeps its words for Back), `SearchFeatureTest` (27), `SearchResultsTest` (28; batch B: S11's Enter, the
  20-result cap, nearest first, one match picked, none, not outside GO_TO or while driving, select, deselect, close, a new
  search; S10's hours on rows and the sheet, Call and Website, the clock, a recent's details read later; batch C2: the 20 truly
  nearest of 100 candidates within 25 miles or the nearest 5, results ended by a removed state, details by name and
  position, Call and Website again once ARRIVED; 0.1.40: P6, P44, P2, P46), `SavedPlacesFeatureTest` (18; 0.1.40: P18, P22, P57, P58),
  `SavedLeakTest` (1), `PlaceWindowTest` (1, F42), `SpriteCacheTest` (2): docs/architecture-saved-places.md 10.1 items 1-8 and
  the controller items of docs/architecture.md 5.1 S, on a virtual clock with a fake of the packs. An engine test through
  the controller cannot run here (an Android library's unit tests get the Android variant of
  sqlite-bundled); the device test does it.
- Device (`sh tools/round/gradle.sh s :feature:search:connectedDebugAndroidTest` on the track's own emulator, optionally
  `-Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`; `StopResultsScreensTest` (item 51; item 52's card with "Go here instead"; 0.1.40 review: "Other matches" headings never share a key and stop results have none, a one-place card has no arrow back to a list, the card's status is one live line),
  `PlaceWindowScreensTest` (0.1.40: P9, P17, P51, P53/F41, P56, P57/F47, P58, P60; the replay: the raised window's status line right under the distance) and `SheetHeightsScreensTest` (P1, P2, P6) besides the one below):
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
