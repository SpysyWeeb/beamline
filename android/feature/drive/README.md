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
| `EventCardTimer.kt` | Which one-shot card (StopReached, Arrived, RerouteFailed) is on screen, for `cardMs`. `EventCards` keeps one per instance. |
| `NotificationPacer.kt` | Rule D6's pace for the integrator's `GuidanceService`: `next(trip, units, nowMs)` gives the text to post now, or null; at once only when the maneuver the notification names changes (or the arrival), every other change at most every `min_interval_s`; `heldUntilMs()` says when a held text may go. |
| `LayoutRules.kt` | Two plain-arithmetic layout rules with JVM tests: lane boxes for the row's width (`laneMetrics`), and where the route sheet's scrolling part rests (`sheetRestingScroll`). |
| `DriveOverlay.kt` | Contract: `DriveOverlay(ui, onEvent, onInsets, modifier)`; body in `Overlay.kt`. |
| `Overlay.kt` | Layout, upright and on its side (below); `onInsets`; the status bar's icon colour while the banner is under it; `DriveTags` = test tags. |
| `TurnBanner.kt` | The banner of `docs/design/turn-banner.html`: arrow, distance, instruction, "THEN" chip, lane row with three lane states, no hint text. The sketch's 40 dp lane boxes while they fit; more lanes than fit share the width in whole pixels, every lane whole. |
| `DriveIcons.kt` | Maneuver and lane arrows from the sketch's path data (six copied verbatim; the other kinds drawn in the same style, drafts), and the overlay's small icons. |
| `RouteSheet.kt` | Route choice (rule D10: two heights on `:ui:theme`'s sheet frame; the lowest shows one small card per route and Start): start row (with the overflow menu and Cancel, always in view), stops, cards (the selected one scrolls itself into view), Avoid highways / Avoid tolls, Start with its blocked reason (always in view), "Simulate drive" in the overflow menu. When the part between comes to rest, no stop line is left cut under the start row (shown from its top or scrolled wholly away; the selected card stays whole). |
| `StopsEditor.kt` | Up, down, remove per stop, Add stop, Best order. |
| `GuidanceParts.kt` | Trip bar, status card and chip, stop and arrival cards, map buttons, resume card. |
| `SpeedSign.kt` | Rule D8 (batch A): the US speed-limit sign (MUTCD R2-1) and the round speed badge (white, yellow from 5 mph / 8 km/h over, red from 10 / 16; a dash without a good fix), at the top start of the map, level with the map buttons above the trip bar; above the credit's line while that shows (`DriveOverlay(creditShown)`), then sliding down (the owner, 2026-11-18). `Presentation.speed` makes `SpeedUi`; `SpeedPresentTest` (JVM) and `DriveScreensTest.speedSignAndBadgeCoverNothing` (device, day and night) test it. In a window too short for it between the top stack and the bottom block (split screen, about 390 dp) the pair is left out until there is room (batch B; `DriveScreensTest.speedPairHidesInAShortWindowAndComesBack`). |
| `TurnBanner.kt` lane note | Rule D9 (batch C1): while `Guidance.laneWarning` is set the banner's lane row is that intersection's lanes and `BannerUi.laneNote` ("Right lane turns right: keep left for Peach St") is drawn under it (tag `lane-note`); `Presentation.withLaneWarning`, `LaneWarningPresentTest`. |

## Layout and what the integrator gets from it

- **Upright** (or narrower than 560 dp): the banner (or the re-route card, D7) across the top, its
  colour reaching up behind the status bar; the GPS chip or the stop card under it; the route sheet,
  trip bar, arrival card or resume card at the bottom, above the navigation bar; Overview and
  Recenter above that at the end; the stops editor over everything (a tap on the dimmed map, named
  "Close stops" for screen readers, closes it). `onInsets(top = banner block, bottom = bottom block)`.
- **On its side** (wider than tall and at least 560 dp wide): the same blocks in one panel on the
  start side, half the width, between 320 and 400 dp; the banner starts under the status bar there;
  the map buttons at the bottom end. `onInsets(start = panel width)`, top and bottom 0.
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
  (65 tests: the three sketch frames, metric, D2 order, D7, cards, Eco, Start blocked, trip bar clock
  in America/New_York 12/24 h and across the end of daylight saving time, EventCards, notification texts and pace
  (GPS flapping paced, held texts), stop rows, best order, the saved-Home test of the saved-places
  companion 5.7, the two layout rules (`LayoutRulesTest`), and N's golden trace
  `android/routing/testdata/nav/exit24.json` fix by fix; the trace is a declared input, so the tests
  run again when N rewrites it). Add `--rerun` to run them when nothing changed. The golden-trace test
  takes distances, chip, lane flag, phase and GPS from the trace but roads, kinds and lane arrows from
  the fixtures (the trace has none): it does not prove N's real banners reach the right texts.
- Device (`src/androidTest`, Compose test rule, no data pushed): start the emulator
  (`sh tools/round/emulator.sh d start`), then `sh data/r3-d/run_device_tests.sh [OUT_DIR]` (builds,
  installs with `adb.sh`, runs `am instrument`, pulls 70 screenshots and 5 accessibility dumps to
  OUT_DIR, default `data/r3-d/screens/`, logs next to it; `BL_D_TESTS=<class or class#method,...>`
  runs only those), then `sh tools/round/emulator.sh d stop`. 14 tests: every screen by day and night,
  three at font size 1.3, the texts of the three frames, the events of every button, the insets, the
  selected card for screen readers, the status bar over the banner (whole-device shots), what a screen
  reader is given on five screens (`a11y-*.txt`), eight lanes on a 360 dp phone and in both side
  panels (every lane whole), stop lines never cut under the sheet's start row (also after a drag),
  and the side layout at 800 x 360 and 640 x 360 dp (`DriveLandscapeTest`, whose host activity
  `LandscapeHostActivity` asks for landscape in `src/androidTest/AndroidManifest.xml`; the device's
  rotation settings are not touched).
- `python data/r3-d/compare/make_compare.py`, then render `data/r3-d/compare/index.html` with a local
  browser (command in report 15): the sketch's three frames with the emulator's under them, day and night.
- `python android/scripts/check_private_logs.py scan --module feature/drive`: 0 breaches (no log calls).
- `python android/scripts/check_network_rule.py scan`: 0 findings.
