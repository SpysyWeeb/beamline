# `:feature:drive` — the drive screens

Android library (Compose). Owner during the feature round: track D. Contract: `docs/architecture.md`
2.7 (the files `DriveUi.kt` and `DriveOverlay.kt`, frozen except their function bodies), rules D1-D7
of 3.9, C1 and C6 of 3.4, R3 of 3.10, and `docs/architecture-saved-places.md` 5.7. Report:
`docs/experiments/15-drive-screens.md`.

Depends on `:core:api` (the trip, its state and intents), `:core:units` (every distance, duration and
clock text) and `:ui:theme` (colours, the Material theme, the Add icon), plus Compose: exactly the
edges of section 1.1 (the status bar's icons are set with platform calls, not androidx core). No
logging, no network, no Android below the composables: the presenter is plain Kotlin.

## What is in it

| File | What |
|---|---|
| `DriveUi.kt` | Contract: the UI model (`DriveUi`, `RouteSheetUi`, `BannerUi`, ...), `DriveEvent`, `EventCards`, `DrivePresenter`. Bodies delegate to the files below. |
| `Presentation.kt` | `DrivePresenter.present` and `notification`: a pure function from `TripState` (+ units, clock, zone, camera, stops editor open, card shown, precise location) to `DriveUi`. |
| `DriveTexts.kt` | Every fixed word of the drive screens and the notification, and the screen-reader texts. |
| `EventCardTimer.kt` | Which one-shot card (StopReached, Arrived, RerouteFailed) is on screen, for `cardMs`; else a StopsReordered (P49, the stops editor's "Stops reordered.") in its own slot, which never replaces a card. `EventCards` keeps one per instance. |
| `NotificationPacer.kt` | Rule D6's pace for the integrator's `GuidanceService`: `next(trip, units, nowMs)` gives the text to post now, or null; at once only when the maneuver the notification names changes (or the arrival), every other change at most every `min_interval_s`; `heldUntilMs()` says when a held text may go. |
| `LayoutRules.kt` | Plain-arithmetic layout rules with JVM tests: lane boxes for the row's width (`laneMetrics`), where the route sheet's scrolling part rests (`sheetRestingScroll`, with `cardWithRoom`: a selected card clear of the fade over the part's last lines stays clear), and whether the sheet now shows a new trip (`newTripShown`: planned during a preview, rule D10). |
| `DriveOverlay.kt` | Contract: `DriveOverlay(ui, onEvent, onInsets, modifier, creditShown, stopWindowDp, onTopStack)`; body in `Overlay.kt`. |
| `Overlay.kt` | Layout, upright and on its side (below); `onInsets`; the status bar's icon colour while the banner is under it; `DriveTags` = test tags. |
| `TurnBanner.kt` | The banner of `docs/design/turn-banner.html`: arrow, distance, instruction, "THEN" chip, lane row with three lane states, no hint text. The sketch's 40 dp lane boxes while they fit; more lanes than fit share the width in whole pixels, every lane whole. |
| `DriveIcons.kt` | Maneuver and lane arrows from the sketch's path data (six copied verbatim; the other kinds drawn in the same style, drafts), and the overlay's small icons. The roundabout (P34: it read as the male sign) is the route round the island, counter-clockwise, leaving straight up, with the island's other half drawn dim (`icon(dim)`, `DIM_ALPHA`). |
| `RouteSheet.kt` | Route choice (rule D10: three heights on `:ui:theme`'s sheet frame; the lowest: the arrival, "2 routes", Cancel (P20) and Start; the middle: "To X", the context line "From Butler · 1 stop" (P19, P20), one small card per route, Start; a turn between the two layouts moves the sheet to the new layout's opening height, P52; a tapped step shows at the middle height as the step view, "Step 3 of 7" with Previous / Next, P21, and is outlined in the list; a change of the stops scrolls the full height back to the top and holds the selected card still until the new routes come, P48; a fade over the scrolling part's last lines and a line above Start while it scrolls further, P52; "Guidance starts from where you are." above Start, P19). Full height: start row (with the overflow menu and Cancel, always in view), stops, cards (the selected one scrolls itself into view), Avoid highways / Avoid tolls, Start with its blocked reason (always in view), "Simulate drive" in the overflow menu. When the part between comes to rest, no stop line is left cut under the start row (shown from its top or scrolled wholly away; the selected card stays whole). |
| `StopsEditor.kt` | Up, down, remove (kept apart from the arrows, F30) per stop, Add stop, Best order. P49: a note line under the buttons ("Stops reordered.", the trip's problem, or the app's "Stop removed" with Undo, P10/F30, `DriveEvent.UndoStopRemoval`); guided, the trip's arrival under the title; at each height everything under the header keeps the tallest height it had since it reached that height, and the note line's room is kept at full height and, lowered, while Best order is offered, so Done never moves when a stop is removed or a note comes and goes. The 0.1.40 replay: when the window cuts the rows (on its side under the banner) the editor is as tall as it may be, so the empty note line gives its room to the rows (a note still gets its line, the rows scroll), and a fade over the last row says they scroll further (`DriveTags.STOPS_MORE_BELOW`). |
| `GuidanceParts.kt` | Trip bar ("No route from here" outside the installed maps, P5), status card and chip, stop and arrival cards (P31/F34: "Saved" with a check once `Arrived.saved`; the handle's raise is "Show place details"), map buttons, resume card ("Continue the simulated drive to ...?", P27). |
| `SpeedSign.kt` | Rule D8 (batch A): the US speed-limit sign (MUTCD R2-1) and the round speed badge (white, yellow from 5 mph / 8 km/h over, red from 10 / 16; a dash without a good fix), at the top start of the map, level with the map buttons above the trip bar; above the credit's line while that shows (`DriveOverlay(creditShown)`), then sliding down (the owner, 2026-11-18). `Presentation.speed` makes `SpeedUi`; `SpeedPresentTest` (JVM) and `DriveScreensTest.speedSignAndBadgeCoverNothing` (device, day and night) test it. In a window too short for it between the top stack and the bottom block (split screen, about 390 dp) the pair is left out until there is room (batch B; `DriveScreensTest.speedPairHidesInAShortWindowAndComesBack`). |
| `TurnBanner.kt` lane note | Rule D9 (batch C1): while `Guidance.laneWarning` is set the banner's lane row is that intersection's lanes and `BannerUi.laneNote` ("Right lane turns right: keep left for Peach St") is drawn under it (tag `lane-note`); `Presentation.withLaneWarning`, `LaneWarningPresentTest`. |

## Layout and what the integrator gets from it

- **Upright** (or narrower than 560 dp): the banner (or the re-route card, D7) across the top, its
  colour reaching up behind the status bar; the GPS chip or the stop card under it; the route sheet,
  stops editor, trip bar, arrival card or resume card at the bottom, above the navigation bar; Overview and
  Recenter above that at the end. The stops editor takes the route sheet's or the trip bar's place (P49;
  the route sheet's own state is kept meanwhile); while guided the bottom block stops under the banner
  (P10), but keeps `STOPS_EDITOR_MIN_HEIGHT` (260 dp times the font scale) in a window too short for it,
  and the map buttons are left out while they have no room above the editor.
  `onInsets(top = banner block, bottom = bottom block)`.
- **On its side** (wider than tall and at least 560 dp wide): the same blocks in one panel on the
  start side, half the width, between 320 and 400 dp; the banner starts under the status bar there;
  the map buttons at the bottom end. `onInsets(start = panel width)`, top and bottom 0. The side rule
  is `:ui:theme`'s `isSideLayout` / `sidePanelWidth` (play test P7: the app's browsing windows use it too).
- **Top stack** (P7): `onTopStack(dp)` gets where the banner block and the chip or stop card under it end,
  from the overlay's top, in both layouts (0 without one); the app keeps its windows under it.
- **Stop results** (item 51, `stopWindowDp`): only the top block, the chip or stop card and the speed pair
  (above the window, upright) are drawn; `onInsets(top = banner block)` upright, `start = panel` on its side.
- **Road label** (item 22): left out while Recenter shows (P32); on its side its end room counts from a
  navigation bar at the end, as the map buttons' does.
- Nothing shown (IDLE): `EdgeInsetsDp()`. The chips, cards and buttons are never in the insets, so the
  camera does not jump when they come and go.
- **Status bar icons:** upright, while the banner or the re-route card is shown (both dark, day and
  night), the overlay sets the status bar's icons light (again after every composition); when they
  go, it sets them back to follow the map (dark by day, light by night), as `MainActivity` sets them.
  It writes where androidx core 1.19's `WindowCompat` writes the same setting (API 35+: the window's
  `WindowInsetsController`; below: the decor view's `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`), so the app's
  own later changes keep working. It changes nothing otherwise.

```kotlin
// stopCardMs = guidance.json arrival.stop_card_s * 1000; session events -> cards.onEvent(e, elapsedRealtime)
val cards = EventCards(stopCardMs)
val ui = DrivePresenter.present(trip, units, System.currentTimeMillis(), ZoneId.systemDefault(),
    cameraDirector.state.value, stopsEditorOpen, cards.shown(elapsedRealtime), preciseLocationGranted)
DriveOverlay(ui,
    onEvent = { e -> /* Nav -> session.send(e.intent); Recenter / ShowOverview -> CameraDirector; the rest: app navigation */ },
    onInsets = { insets -> cameraDirector.onViewport(mapWidthDp, mapHeightDp, insets) })
// GuidanceService, with minIntervalMs = guidance.json notification.min_interval_s * 1000:
val pacer = NotificationPacer(minIntervalMs)   // on every trip state: pacer.next(trip, units, elapsedRealtime)?.let { post(it) }
// after a null, pacer.heldUntilMs()?.let { at -> handler.postDelayed({ pacer.next(latestTrip, units, elapsedRealtime)?.let(::post) }, at - elapsedRealtime) }
```

`DriveOverlay` reads the theme from the caller: wrap it in `BeamlineTheme(night)`; a dark surface
gives the banner its night colours. Give it the whole window (edge to edge): it pads its own content
for the system bars and a display cutout.

## Tests

- JVM (`src/test`, fixtures in `src/sharedTest/.../Exit24.kt`): `sh tools/round/gradle.sh d :feature:drive:testDebugUnitTest`
  (89 tests in 0.1.40; 65 at first: the three sketch frames, metric, D2 order, D7, cards, Eco, Start blocked, trip bar clock
  in America/New_York 12/24 h and across the end of daylight saving time, EventCards, notification texts and pace
  (GPS flapping paced, held texts), stop rows, best order, the saved-Home test of the saved-places
  companion 5.7, the two layout rules (`LayoutRulesTest`), and N's golden trace
  `android/routing/testdata/nav/exit24.json` fix by fix; the trace is a declared input, so the tests
  run again when N rewrites it). Add `--rerun` to run them when nothing changed. The golden-trace test
  takes distances, chip, lane flag, phase and GPS from the trace but roads, kinds and lane arrows from
  the fixtures (the trace has none): it does not prove N's real banners reach the right texts.
- Device (`src/androidTest`, Compose test rule, no data pushed): start the emulator
  (`sh tools/round/emulator.sh d start`), then `sh data/r3-d/run_device_tests.sh [OUT_DIR]` (builds,
  installs with `adb.sh`, runs `am instrument`, pulls the screenshots and 6 accessibility dumps to
  OUT_DIR, default `data/r3-d/screens/`, logs next to it; `BL_D_TESTS=<class or class#method,...>`
  runs only those), then `sh tools/round/emulator.sh d stop`. 44 tests in 0.1.40 (14 at first: every screen by day and night,
  three at font size 1.3, the texts of the three frames, the events of every button, the insets, the
  selected card for screen readers, the status bar over the banner (whole-device shots), what a screen
  reader is given on six screens (`a11y-*.txt`), eight lanes on a 360 dp phone and in both side
  panels (every lane whole), stop lines never cut under the sheet's start row (also after a drag),
  and the side layout at 800 x 360 and 640 x 360 dp (`DriveLandscapeTest`, whose host activity
  `LandscapeHostActivity` asks for landscape in `src/androidTest/AndroidManifest.xml`; the device's
  rotation settings are not touched); 0.1.40 adds the step view and its shots (`sheet-step-*`), the context line,
  Cancel at the lowest height, the note above Start, the stops shown from the top after a change (also on its
  side at font 1.3), the turn into the side panel, the road label beside a navigation bar and under Recenter,
  the reported top stack, and the stop results' overlay (`stop-results-top-day`); the stops editor in the sheet's and the
  trip bar's place with Done kept still (`stops-editor-in-place-day`, `stops-editor-undo-day`, `stops-editor-guided-*`,
  `land412-stops-editor-guided-day`), the arrival card's "Saved" (`arrived-saved-day`) and the trip bar outside the
  installed maps (`a11y-outside.txt`); after the review the selected card clear of the fade on its side
  (`land-sheet-card-clear-font130-day`), the lowered editor's Done kept still when Best order's note comes and goes
  (`stops-editor-lowered-note-day`) and a new trip planned during a preview opening at the middle height; after the replay
  the stops editor under the banner on its side with both rows whole, also opened upright and then turned
  (`land411-stops-editor-two-stops-day`, `land411-stops-editor-three-stops-day` with the rows' fade).
- `python data/r3-d/compare/make_compare.py`, then render `data/r3-d/compare/index.html` with a local
  browser (command in report 15): the sketch's three frames with the emulator's under them, day and night.
- `python android/scripts/check_private_logs.py scan --module feature/drive`: 0 breaches (no log calls).
- `python android/scripts/check_network_rule.py scan`: 0 findings.
