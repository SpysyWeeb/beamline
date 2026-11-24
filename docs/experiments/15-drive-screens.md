# Experiment 15: the drive screens (track D)

Date: 2026-11-16. Track D of the feature round (`docs/architecture.md` 5.1, "D: drive feature").
Code: `android/feature/drive/` (`:feature:drive`). Outputs (not in git): `data/r3-d/` (progress log,
screenshots, accessibility dumps, the comparison with the sketch, Gradle and instrumentation logs).
Built by two engineers one after the other: the first did stage 1 (the presenter, the cards, the
notification texts and all their JVM tests) and most of stage 2 (the composables, the device test
host, the screenshots); the second checked that work, closed what was open (landscape, the screen
reader side, the status bar over the banner), fixed what looking at the screens found, and wrote this
report. Two reviewers then reproduced and checked it; a third engineer checked their seven findings
against the code, fixed six and recorded the seventh as a hand-over (section "Review and fixes" at
the end), and brought the numbers below up to date.

## Result in one paragraph

`DrivePresenter` turns the navigation session's `TripState` into everything the drive screens show,
as a pure Kotlin function, with every distance, duration and clock text from `:core:units`.
`EventCards` keeps the one-shot stop, arrival and re-route cards for their time, and
`DrivePresenter.notification` (with `NotificationPacer` for its pace) gives the guidance
notification's title and text. `DriveOverlay` draws them in Compose: the turn banner of the approved
sketch (maneuver arrows and lane arrows from its path data, the "THEN" chip, three lane states, no
hint text), the route sheet (start row, cards, the two option toggles, Start with its blocked reason,
"Simulate drive" in the overflow menu), the stops editor, the trip bar, the status card and chips,
the stop, arrival and resume cards, and the Recenter and Overview buttons; upright it puts the banner
on top and the sheets at the bottom, on its side it puts both in a panel on the start side. 65 JVM
tests pass (every JVM item of the definition of done, N's golden trace run through the presenter
fix by fix, the notification's pace and two layout rules). 14 device tests pass on the emulator
`beamline_d` and wrote 70 screenshots and 5 accessibility dumps; the three frames sit under the
sketch's frames in one picture and match it, by day and by night. Not verified: the overlay over the
real map with the real session (I's wiring), N's real banners through the presenter (the golden-trace
test uses this track's fixtures for roads, kinds and lane arrows), real TalkBack (the emulator image
has none; what a screen reader is given was read on the device instead), and font sizes above 1.3.

## What was built

| Part | Where | Notes |
|---|---|---|
| `DrivePresenter.present` | `Presentation.kt` (called from the contract body in `DriveUi.kt`) | Rules D1-D7, C1, C6, R3. Phase → what is drawn: IDLE nothing; RESUME_OFFERED the resume card; PLANNING and CHOOSING the route sheet (and the stops editor when open); GUIDING and REROUTING guidance (and the stops editor when open); ARRIVED guidance with the arrival card, no stops editor. |
| `DrivePresenter.notification` | `Presentation.kt` | Rule D6 (texts below). |
| `EventCards` | `DriveUi.kt` → `EventCardTimer.kt` | Latest StopReached, Arrived or RerouteFailed event for `cardMs`; GuidanceStarted and GuidanceEnded drop the card. |
| `NotificationPacer` | `NotificationPacer.kt` | Not in the contract; for I's `GuidanceService`: at once when the maneuver the notification names changes (another maneuver or route, the first banner, re-routing starting, the arrival), every other change (distance, GPS, problem) at most every `min_interval_s`, never the same text twice; `heldUntilMs()` says when a held text may go. Changed after the review (below). |
| Layout rules | `LayoutRules.kt` | `laneMetrics` (lane boxes for the row's width, in whole pixels) and `sheetRestingScroll` (where the route sheet's scrolling part rests): plain arithmetic, JVM-tested. Added after the review. |
| Texts | `DriveTexts.kt` | Every fixed word in one place, with the screen-reader texts. |
| `DriveOverlay` | `DriveOverlay.kt` → `Overlay.kt` | Two layouts (below), `onInsets`, the status bar's icon colour over the banner, `DriveTags` (test tags). |
| Turn banner | `TurnBanner.kt`, `DriveIcons.kt` | Upright: flush at the top, its colour reaching up behind the status bar, as the sketch's phone frames show it. The sketch's 40 dp lane boxes while they fit; more lanes share the width, every lane whole. |
| Route sheet, stops editor | `RouteSheet.kt`, `StopsEditor.kt` | The sheet's start row (overflow menu, Cancel) and Start are pinned, the part between scrolls, the selected card scrolls itself into view, and the part never rests with a stop line cut under the start row; the editor's rows give up room first. |
| Trip bar, status, cards, map buttons, resume | `GuidanceParts.kt` | |
| Device test host | `src/androidTest/.../ScreenHost.kt`, `DriveScreensTest.kt`, `DriveLandscapeTest.kt`, `src/androidTest/AndroidManifest.xml` | A phone screen of a given size in dp with a stand-in map (land, two roads, the route in blue B) under the overlay, in `BeamlineTheme(night)`; or the whole window, edge to edge, for the status bar. `LandscapeHostActivity` (test APK only) asks for landscape. |
| Shared fixtures | `src/sharedTest/.../Exit24.kt` | The exit 24 trip as the session would hand it over; used by both test source sets (build file `sourceSets`). |

### The two layouts

- **Upright** (or narrower than 560 dp): banner (or the re-route card, D7) across the top; the GPS
  chip or the stop card under it; route sheet, trip bar, arrival card or resume card at the bottom;
  Overview and Recenter above it at the end; the stops editor over everything.
  `onInsets(top = top block, bottom = bottom block)`.
- **On its side** (wider than tall, at least 560 dp wide): the same blocks in one panel on the start
  side, half the width between 320 and 400 dp; the banner starts under the status bar (a rounded
  card, so the status bar is not half dark); map buttons at the bottom end.
  `onInsets(start = panel width)`, top and bottom 0. Section 8's "rotate" variant needs the banner
  usable on its side; upright layout on its side left about 150 dp of map between banner and trip bar
  and a 900 dp wide route sheet over the whole height.
- IDLE: insets 0. Chips, cards and buttons are never in the insets, so the camera does not jump.

### The exact texts

Given by the contract and used as given: "Your position", "Allow precise location to start guidance",
"Tolls", "Highways", "Finding a new route...", "GPS signal lost", "Fastest · Simpler" (joined with
" · "), "via I 90, US 19", "Arrive 3:42 PM", "Stop 2 · 4.1 mi", "onto ", "toward ", "Simulate drive",
"Continue to …?", "End trip" (the notification action's words, section 4), "Then" (the sketch's
"THEN", drawn upper-case).

This track's own (no text in the contract): "Waiting for GPS", "Weak GPS signal", "On the left" /
"On the right" (the arrival side), "Arrived" and "Arrived at Downs Drive", "To Downs Drive" (the
notification before the first maneuver and while re-routing), "Stop reached", "From", "To", "Start",
"Add stop", "Edit stops", "Stops", "Best order", "Done", "Retry", "Avoid highways", "Avoid tolls",
"Cancel", "More options", "Move up", "Move down", "Remove", "Reached", "Recenter", "Overview",
"Simulated", "Continue", "Discard", "2 stops left", "Continue your trip?" (no name), "Close stops"
(the screen-reader name of the dimmed map around the stops editor). Screen-reader texts follow the
sketch's labels: "Then left, In 0.3 mi" and "Lanes, left to right: left (allowed), left or straight on
(best), right".

Notification (D6): GUIDING → title "Exit 24 · 0.5 mi" (primary and the banner's distance), text the
roads as the banner writes them ("US 19 / Peach St", "onto Downs Dr"), or the status text when the
status is not on route ("GPS signal lost", "Weak GPS signal", "Waiting for GPS", a failed change's
problem); before the first maneuver title "To Downs Drive". REROUTING → title "Finding a new
route...", text the failure or "To Downs Drive". ARRIVED → "Arrived at Downs Drive", text the side.
Only the stops' names are used, never a subtitle or a point (PV6): a saved Home reads "Arrived at
Home".

## How it is proved

All commands from the repository root in Git Bash. Results are from the last runs of 2026-11-16
(after the review fixes: JVM 11:11 UTC, device 11:04 UTC) unless a row says otherwise.

| Command | Result |
|---|---|
| `sh tools/round/gradle.sh d :feature:drive:testDebugUnitTest --rerun` | BUILD SUCCESSFUL; 65 tests, 0 failures, 0 skipped: BannerPresentTest 11, EventCardsTest 7, GoldenTraceTest 4, LayoutRulesTest 6, NotificationTextTest 9, RouteSheetPresentTest 13, StatusPresentTest 8, TripBarPresentTest 7 (counts from the JUnit XML under `data/gradle/d/build/feature/drive/test-results/testDebugUnitTest/`, timestamps 11:11 UTC). Before the review fixes: 58 (the second engineer's 10:12 UTC run and the reviewer's run, same per-class counts). Without `--rerun` Gradle reports the task up to date when nothing changed. |
| Mutation by the first engineer: the road cap set to 3 instead of 2 | 3 tests failed (the frame 2 test, the "onto" rules test, the notification test); 0 after it was set back. Not repeated since. |
| `sh tools/round/emulator.sh d start`, `sh data/r3-d/run_device_tests.sh`, `sh tools/round/emulator.sh d stop` | `OK (14 tests)`, 0 skipped: theThreeSketchFrames, routeSheetScreens, routeSheetEvents, startBlockedWithoutPreciseLocationButSimulateStays, stopsEditorScreensAndEvents, statusAndCardScreens, guidanceEvents, largeFontScale, manyLanesFitTheBanner, selectedCardIsMarkedForScreenReaders, statusBarIconsOverTheBanner, screenReaderNodes (DriveScreensTest), widePhone, smallPhone (DriveLandscapeTest). 70 PNGs and 5 text dumps pulled to `data/r3-d/screens/` (11:04 UTC run). Emulator `beamline_d`: API 36, 1080 x 2400 px, density 420. The script now takes an output folder (`sh data/r3-d/run_device_tests.sh data/review/<name>/screens`) and `BL_D_TESTS=<class or class#method,...>`, so a re-run need not replace these files. |
| Mutations after the review (device): lane boxes fixed at the sketch's 40 dp; the route sheet's resting rule switched off | manyLanesFitTheBanner failed ("lane 7: 14.0 px wide, lane 0 is 105.0 px"), smallPhone failed ("stop-line-0: cut at the top, 41 px of 116 shown from 75 px below its top": exactly the reviewer's frame, the destination's second line alone under the start row); widePhone and largeFontScale passed (eight lanes fit the 400 dp panel at 40 dp; their sheets leave no line cut through its letters). All passed again with the code put back. |
| Mutation after the review (JVM): `NotificationPacer` as it was before (GPS, phase, problem posted at once) | both pacer tests of NotificationTextTest failed (the GPS-flapping test at its list of posts; the other at `heldUntilMs`); all JVM tests pass again with the new pacer. |
| Mutation: the selected card's bring-into-view removed | widePhone and smallPhone failed: "card-1: 966 x 45 px shown of 966 x 242" and "756 x 114 px shown of 756 x 242"; passed again after it was put back. |
| Mutation: the overlay never sets the status bar's icons light | statusBarIconsOverTheBanner failed ("light icons over the banner (night)"); its day leg passed, because the test activity starts with light icons. The test now first sets the window as `MainActivity` does for that map; against the same mutant it then failed on the day leg ("light icons over the banner (day)"); passed again with the code put back. |
| `python data/r3-d/compare/make_compare.py`, then Edge headless: `msedge --headless=new --disable-gpu --no-first-run --disable-background-networking --disable-component-update --disable-sync --disable-extensions --user-data-dir=<a scratch folder> --hide-scrollbars --window-size=1140,1700 --screenshot=<repo>/data/r3-d/compare/compare.png file:///<repo>/data/r3-d/compare/index.html` | `data/r3-d/compare/compare.png`: the sketch's page with its three frames, then the emulator's three frames by day and by night under them. The script drops the sketch's web-font link, so the page makes no request and the sketch shows in its fallback fonts. |
| `python android/scripts/check_private_logs.py scan --module feature/drive` | `scan: 13 files in 1 modules; 0 marked lines; 0 breaches` (the module has no log call). |
| `python android/scripts/check_network_rule.py scan` and `privacy` | `scan: 0 finding(s)` (S's earlier search-bench finding is gone); `privacy: 0 private IPv4 literal(s) in tracked files`. |
| `python tools/check_contracts.py` | exit 0; all the same (39 contract files, 26 with filled stubs; 20 excerpts; 5 data files); 39 frozen files unchanged. `DriveUi.kt` and `DriveOverlay.kt` "filled". |
| `aapt2 dump permissions` on the test APK | no network permission (only REORDER_TASKS and androidx's own DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION); `LandscapeHostActivity` is in the test APK only, not exported. Re-checked after the review fixes. |

What the JVM tests check, by the definition of done:

- Frame 1: "Exit 24" / "US 19 / Peach St" / "toward Waterford" / "0.5 mi" / chip LEFT "In 0.3 mi" /
  no lanes. Frame 2: "Turn left" / "onto US 19 / Peach St" / "400 ft" / chip RIGHT "In 0.2 mi" /
  allowed, best, not (with the arrows). Frame 3: "Turn right" / "onto Downs Dr" / "0.2 mi" / no chip /
  not, not, not, best. The left turn's fixture has the engine's three road names
  (`android/routing/banner` BannerModelTest), so the cap of two is tested on real data.
- Metric: "800 m", "In 400 m", "100 m", "In 350 m", "300 m", trip bar "2.0 km", the other texts unchanged.
- D2: one test steps down the whole order (Arrived over a stop card, a problem and LOST; StopReached
  over a problem and LOST; RerouteFailed over Rerouting and LOST; Rerouting over LOST; then GpsLost,
  WaitingForGps, GpsWeak, OnRoute).
- D7: no banner in REROUTING (plain and failed, with and without Retry); the banner unchanged while
  only `offRouteSuspected`; a failed stop change in GUIDING shows its problem while the banner stays.
- D4/D5: cards in choice order with merged labels, notes, via, arrival; Eco never named and a route
  only Eco gave never shown; Start needs a selected card, CHOOSING, no replanning and precise
  location; without precise location the reason is given and the card stays selected (Simulate stays).
- D3: "Arrive 3:42 PM" / "Arrive 15:42" for 12 min 10 s after 15:30 in New York; the zone given is
  used (Chicago gives 2:42 PM); across the end of daylight saving time (05:50 UTC on 2026-11-01 plus
  20 minutes is "Arrive 1:10 AM"); "Stop 1 · 1.0 mi", "Stop 2 · 4.1 mi" / "6.6 km", none when the
  destination is next; the Simulated flag.
- EventCards: shown in `[t, t + cardMs)`, the latest replaces at once, other events leave it, start and
  end drop it, two instances independent, any card time honoured.
- Notification: the texts above, "Arrived at Home" for a saved Home with no description or point,
  null outside the guided phases, and `NotificationPacer`'s timing: at once for another maneuver,
  another route with the same maneuver number, re-routing starting and the arrival; a GPS state
  flapping every second for 10 s posted twice (at 5 s and 10 s), a failed change and a re-route
  failure held to the 5 s, a held text posted at `heldUntilMs()`.
- Layout rules (`LayoutRulesTest`): lane boxes of the sketch while they fit (up to 7 on a 360 dp
  phone, 6 in the 320 dp panel), narrower and whole beyond (up to 20 lanes tried), in whole pixels at
  the emulator's density never past the row; the route sheet's resting place for a stop line cut at
  the top (nearer edge, the selected card kept whole, a cut through the line's empty space ignored).
- Saved places 5.7: a stop made from a saved Home reads "Home" with "Perry Square, Erie" (its
  description); `FromPlace(home)` gives "Home"; the stops editor and the resume card say "Home".
- N's golden trace (`android/routing/testdata/nav/exit24.json`, 289 fixes, board note
  `n-golden-trace.md`; read only, through the test property `beamline.repoRoot` and the catalog's
  JSON parser as a test dependency): at every fix the banner's primary is the trace's, the fixture's
  following maneuver is the one the trace's chip names (added after the review), the chip is
  "In 0.3 mi" / "In 0.2 mi" / none exactly as the trace has a chip (its distance from the trace),
  the lane row shows exactly when the trace says, the status is on route, ARRIVED shows the arrival
  card and no banner; the banner's text never goes up for one maneuver and the trip bar's remaining
  distance never goes up, in miles and in kilometres; the drive passes through the sketch's three
  frames; the first fix reads "2.6 mi", trip bar "3.3 mi", notification "Exit 24 · 2.6 mi" (section 8
  row 1). **What it does not prove:** the trace carries no maneuver kinds, road names, towards or lane
  arrows and marks, so those come from this track's fixtures, keyed by the trace's maneuver; the
  expected chip arrows are written in the test. It proves the presenter over N's distances and flags,
  not that N's real banners reach the right texts. The fixtures were compared by hand with what N's
  `android/routing/banner` BannerModelTest asserts for this route (lines 59-83: FORK_RIGHT "Exit 24",
  roads, "Waterford", chip LEFT "In 0.3 mi", no lanes; LEFT with three roads, chip RIGHT "In 0.2 mi",
  allowed-best-not; RIGHT "Downs Dr", no chip, not-not-not-best): they agree. The end-to-end test with
  the real session is the integrator's (see "Review and fixes"). The trace is a declared input of
  the test task, so the tests run again when N rewrites it (not tried: the file is N's).

What the device tests check: the frames' texts as drawn ("Exit 24 US 19 / Peach St, toward
Waterford", "Turn left onto US 19 / Peach St", the chip's and the lane row's screen-reader texts);
that every button sends its event (card → `Choose(2)`, the toggles → `SetOptions`, Start → `Start()`,
the start row → `ChooseStart`, Add stop → `SearchForStop`, overflow → `Start(Simulation())`, ✕ → `End`;
move up, remove, Best order, Done in the stops editor; Recenter, Overview, stops, End trip, Retry,
Done, Continue, Discard); that Start is off with the reason while Simulate stays on; that nothing can
start while planning; the insets (top over 80 dp with the banner, bottom over 40 dp, both 0 in IDLE;
on its side exactly start = 400 dp at 800 x 360 and 320 dp at 640 x 360, top and bottom 0); that
Cancel, the chosen card, Start and the blocked reason are shown whole (not cut by the scrolling part)
at font size 1.3 and on its side; the status bar's icons (light over the banner and the re-route card,
following the map afterwards, by day and by night); eight lanes with the best on the far right shown
whole and equally wide on a 360 dp phone and in the 320 and 400 dp side panels, the sketch's frames
keeping 40 dp boxes; the route sheet's stop lines never cut through their letters at the top of the
scrolling part, on its side, at font size 1.3, and after slow drags by hand; and what a screen
reader is given (below).

### Screenshots, looked at

All in `data/r3-d/screens/`, each `-day` and `-night` unless named otherwise. Looked at by the second
engineer on the last runs, tiled side by side with `data/r3-d/tile_screens.py` (tiles in
`data/r3-d/look/`), and at full resolution for the banners. After the review fixes the whole set was
taken again (11:04 UTC) and the frames, status-bar shots, sheets (upright, 1.3, both side widths),
lanes and compare.png looked at again (tiles `data/r3-d/look/review-*.png`): the frames and the
status bar are unchanged, the changes are the ones described.

- `frame1`, `frame2`, `frame3` and `data/r3-d/compare/compare.png`: the same arrows as the sketch (exit
  right, left, right), the bold instruction with the roads and the sign in the muted colour, the THEN
  chip with its arrow and "In 0.3 mi" / "In 0.2 mi", no chip in frame 3; no lane row in frame 1; in
  frame 2 an outlined left lane, a filled blue left-or-straight lane, a dim right lane; in frame 3
  three dim lanes and a filled blue straight-or-right lane; no hint line. Differences from the sketch:
  the distance's face (choice 13), where the second line wraps, and the map, which is a stand-in
  without the vehicle arrow and the same in all three frames (the map is M's). Night: the same,
  darker banner (draft).
- `sheet`, `sheet-start-blocked`, `sheet-planning`, `sheet-problem`, `sheet-two-stops`,
  `sheet-menu-day` (whole device, the overflow menu with "Simulate drive"): as described in the
  sections above; Start disabled with the reason under it; the problem with Retry.
- `stops-editor`, `stops-editor-guided`: rows with up, down, remove (the first row's up and the last
  row's down off; a reached stop greyed "Reached" with no buttons), Add stop, Best order with three
  stops not reached.
- `rerouting`, `reroute-failed` (the re-route card in place of the banner, D7), `gps-lost` (amber
  chip under the banner), `gps-weak`, `waiting-for-gps` (no banner yet, chip at the top),
  `stop-reached` (card under the banner), `next-stop` (trip bar "Stop 1 · 1.0 mi"), `recenter`,
  `arrived` (card at the bottom: Arrived, Downs Drive, On the right, Done; no banner, no buttons),
  `resume` ("Continue to Downs Drive?", 2 stops left, Discard, Continue). The spinners of the
  re-route card and the waiting chip show as a short arc: the frame caught at the animation's start.
- `font130-frame1`, `font130-frame2`, `font130-sheet` (font size 1.3): everything in view; a long
  sign line ends in "…" ("toward Waterf…" with two towards; the banner text keeps at most three lines,
  on purpose); in the sheet the second card is under the fold, the chosen one and Start whole.
- `statusbar-frame1`, `statusbar-sheet` (whole device, edge to edge): by day the dark banner under the
  status bar with light clock and icons; the route sheet with dark icons over the light map; by night
  light icons on both.
- `land-frame2`, `land-sheet`, `land-stops-editor`, `land-rerouting`, `land-arrived` (800 x 360 dp),
  `land640-frame1`, `land640-sheet`, `land640-lanes8`, `land640-sheet-dragged-day` (640 x 360 dp):
  the panel on the start side with banner, lanes and trip bar, the map buttons and Recenter at the
  bottom end; the route sheet with its start row, the chosen card and Start whole; above the card at
  800 dp the second stop whole ("2 Downs Drive / Erie, Pennsylvania"), at 640 dp no stop line (the
  destination scrolled wholly away, "Add stop" under the start row; before the review its second line
  stood there alone); the stops editor whole in the panel; the arrival card at the bottom of the panel.
- `lanes8` (360 dp, day and night) and `land640-lanes8`: eight lanes, six dim, an outlined
  straight-or-right lane and the filled blue right lane at the far right, all whole and equally wide
  (narrower than the sketch's boxes; the arrows shrink with them).

What looking found and what was fixed (first engineer): the trip bar's "Simulated" badge wrapped one
letter per line; the stops button, the selected toggle and the overflow menu came out lavender
(Material's default secondary colours, which `:ui:theme` does not set); the chip and lane-row test
tags sat inside cleared semantics; at font size 1.3 the trip bar cut "3 min · 1.2 mi" (End trip
became an outlined ✕ named "End trip") and Start scrolled out of the sheet (Start and its reason
pinned). Second engineer: on its side the chosen card was cut to its top edge behind Start
("card-1: 45 px shown of 242" in the mutation row) and Cancel scrolled away with the stops: the
start row is now pinned and the selected card brings itself into view; in the 640 dp sheet the
destination's subtitle then read as the start's second line: a divider shows under the start row
while the part below is scrolled (the reviewer found the divider was not enough; fixed after the
review, see "Review and fixes"). The first whole-device shots caught the previous screen (the frame
and the status bar's new icons were not on the display yet when composition was idle): the capture
now waits for the display to settle; the shots above are from after that. The stand-in map's route
line drew below its box (`sheet-menu-day` showed its end under the sheet): the host now clips it.

### What a screen reader is given

The emulator image (`system-images/android-36/default`) has no TalkBack, so real TalkBack was not
run. Instead `screenReaderNodes` reads the accessibility nodes the overlay exposes, as an
accessibility service does (UiAutomation), on five screens, writes them to
`data/r3-d/screens/a11y-{frame2,sheet,stops-editor,reroute-failed,arrived}.txt` and checks that every
control has a name (its own, or its non-control children's, as a screen reader speaks an icon button)
and that the expected texts are there. Frame 2 reads, in tree order: "400 ft", "Turn left onto US 19
/ Peach St", "Then right, In 0.2 mi", "Lanes, left to right: left (allowed), left or straight on
(best), right", "Arrive 3:31 PM", "1 min · 0.5 mi", "Simulated", then the Stops, End trip and Overview
buttons. The chosen route card is "checked, Selected" and the other "not checked, Not selected"
(Compose reports `selected` that way outside tabs). Found and fixed: the dimmed map around the stops
editor was a control with no name; it is "Close stops" now. Not checked: TalkBack's own focus order
(Compose orders by position, which the tree order shown here does not have to equal), its spoken
output, gestures. With the stops editor open, the dump holds only the editor (the route sheet under
the dim is not listed).

## Choices, and why

1. **At most two road names on the banner.** The engine gives the exit 24 left turn three ("US 19",
   "Peach St", "General Strong Vincent Memorial Hwy"); the sketch and section 8 row 4 read "onto US 19
   / Peach St". D1 reads "roads joined by ' / '" (every name), which would miss the definition of
   done's exact text; two keeps the line short at speed. Board note `data/r3-board/d-banner-roads.md`
   tells N and I; `data/architecture/requests/d.md` (request 2) asks the lead to write the cap and
   choice 2 into D1. All towards are shown (the line can wrap to three).
2. **"onto" also for merges and roundabouts.** D1 names turns and ramps (not exits and forks);
   "Merge onto I 90" and "Roundabout, 2nd exit onto Park Line Dr" read as turns do. Depart, arrive,
   ferry and other get the names alone.
3. **ARRIVED: no banner, no stops editor, no Recenter;** the arrival card (name, side, Done) takes the
   bottom in place of the trip bar, where Done is in reach. The contract says only that REROUTING has
   no banner; at the destination a counting "Arrive" banner would repeat the card.
4. **RerouteFailed comes from the trip's problem** while guided (a failed re-route, O7, O8; a stop or
   option change that failed, C3), so it ends when the session clears the problem; the RerouteFailed
   card event is kept by `EventCards` as the contract says but the status does not need it (a card kept
   for its 8 s could otherwise outlive a re-route that has since succeeded).
5. **Arrival clocks rounded to the nearest minute** (cards and trip bar), as the duration beside them
   is, so "13 min" and the clock agree.
6. **The trip bar's arrival reads "Arrive 3:42 PM"**, as the cards and section 8 row 1 write it.
   Its End button is an outlined ✕ with the screen-reader name "End trip" (room for the times at
   large font sizes).
7. **The only stop left to drive to cannot be removed** (End cancels the trip); moving checks the
   neighbour, so a stop cannot move above a reached one.
8. **GPS OFF while guided is shown as "Waiting for GPS"** (not expected to happen).
9. **`EventCards` drops its card on GuidanceStarted and GuidanceEnded**, so a card of an ended trip
   never shows over the next one. Its state is kept per instance in a module-private identity map,
   because the contract class has no field; its `cardMs` became readable when the lead applied this
   track's request (`data/architecture/requests/d.md`, board note `lead-d-request-applied.md`).
10. **`NotificationPacer`** added (not in the contract) so the pace of D6 lives with its texts. After
    the review it posts at once only when the maneuver the notification names changes (choice 23).
11. **Icons from the sketch's own path data** through Compose's SVG path parser: left, right, straight,
    exit, left-or-straight and straight-or-right verbatim. The other maneuver kinds and lane mixes
    are drawn here in the same 24-unit style (exit mirrored for left exits, bears, ramps and forks;
    sharp turns, U-turns, merges, roundabout, arrival with the side, ferry; mixed lanes as a stem with
    branches). **Drafts until the owner approves them.**
12. **Banner colours:** by day the sketch's (fixed, "they depict the app's own banner"); by night a
    shade darker with the night route blue for the best lane (**draft**). The rest of the overlay uses
    `BeamlineTheme` (day and night) without new colours, except amber for GPS LOST and failures.
13. **The distance's face** is the system's `sans-serif-condensed` bold: the app ships no Barlow, and
    adding a font file would add a licence entry (A's notices).
14. **Owner question 4 (6.38)**: "Simulate drive" is visible in the overflow menu in every build, as
    decision 6.29 says for the prototype; whether it stays in public builds is still the owner's.
15. **Fixtures shared** between the JVM and the device tests through `src/sharedTest/kotlin` (one
    `sourceSets` line each in this module's build file). The JVM tests also get `beamline.repoRoot`
    and `kotlinx-serialization-json` (catalog entry, test only, Apache-2.0) to read N's golden trace.
16. **A side layout for a phone on its side** (second engineer): wider than tall and at least 560 dp
    wide → one panel on the start side, half the width within 320-400 dp, reported as the `start`
    inset. The contract's `EdgeInsetsDp` has `start` and `end`, and section 8's "rotate" variant needs
    the banner usable on its side. 560 dp keeps a 640 x 360 dp phone on its side in this layout.
17. **The overlay sets the status bar's icons light while the dark banner or re-route card is under
    them, upright only** (second engineer). `MainActivity` gives the day map dark icons
    (`SystemBarStyle.light`), which would sit on the dark banner unreadable. It is set again after
    every composition (so a change by the app meanwhile lasts at most until the next fix) and set
    back to follow the map when the banner goes. On its side the banner starts under the status bar
    instead, so nothing needs changing there. The second engineer added `androidx-core-ktx` for
    `WindowCompat`; after the review it is gone again (choice 24). Board note
    `data/r3-board/d-overlay.md` tells I and M.
18. **The route sheet's start row (with the overflow menu and Cancel) is pinned above the scrolling
    part, and the selected card brings itself into view** (second engineer): on a short screen the
    stops otherwise pushed the chosen route out of sight behind Start. A divider under the start row
    shows while the part below is scrolled. After the review the part also never rests with a stop
    line cut at its top (choice 25).
19. **The stops editor's rows give up room first** (`weight(fill = false)`), so on a short screen the
    header and the buttons stay in view.
20. **The landscape test asks for landscape through its own host activity** (test APK manifest),
    not through the device's rotation settings: the launcher held the display upright when the
    rotation was set by `settings` (tried once and set back), and this way no device setting changes.
21. **No "Finding routes..." text while planning**: the busy line at the top of the sheet and the
    disabled Start are the only signs; the contract has no text for it (left as the first engineer
    built it).

After the review (third engineer):

22. **Lane boxes for the width.** The sketch's 40 x 44 dp boxes 6 dp apart while the lanes fit;
    otherwise every box narrower by the same amount (the gap down to 2 dp once a box would be under
    24 dp), the arrow shrinking with its box, height unchanged. Counted in whole pixels at the
    screen's density, because Compose rounds each box and gap: counted in dp, the 6 dp gaps (15.75 px
    each, laid out as 16) pushed the eighth lane 3-4 px past the row on the emulator; the device test
    found that. Every lane is shown rather than scrolling or dropping some: the best lane is often an
    outer one. Very many lanes (a toll plaza) get very narrow boxes (20 lanes in the 320 dp panel:
    12.5 dp); not drawn by any design, a draft.
23. **D6 read literally for the pace:** "updated on maneuver change and at most every 5 s". At once
    only when the maneuver the notification names changes; a maneuver appearing or going counts
    (the first banner, the new route after a re-route, re-routing starting, as D7 drops the banner
    then) and so does the arrival, as the reviewer suggested; GPS, problem and other phase changes
    are paced like the distance. Because a held change could otherwise wait for the next trip update,
    which with GPS lost may not come, the pacer says when a held text may go (`heldUntilMs()`); I's
    service calls `next` again then.
24. **No androidx core in `:feature:drive`.** Section 1.1 lists no library beyond Compose for it, and
    the dependency was only for `WindowCompat`. The overlay now writes the status bar's setting with
    platform calls in the same place androidx core 1.19.1's `WindowCompat` writes it (read from its
    bytecode in the Gradle cache with `javap`): from API 35 the window's
    `WindowInsetsController.setSystemBarsAppearance`, below that the decor view's
    `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` (for API 30-34 as well: once anything calls
    `setSystemBarsAppearance` there, the platform stops reading the flag, so `MainActivity`'s later
    switches through `WindowCompat` would stop working). Chosen over asking the lead for a new edge,
    which the reviewer offered as the other way: no contract change is needed this way.
25. **The route sheet never rests with a stop line cut at its top.** When the scrolling part comes to
    rest (after a drag or fling, after the selected card brought itself into view, after its content
    changed size), a stop line cut by the top edge moves to the nearer edge: shown from its top, or
    scrolled wholly away; but never so that the selected card, whole until then, is cut (then the
    line goes away). The "To" label counts with the first line. A cut through the line's empty space
    (its 4 dp padding and the 4 sp of leading above the name's letters) is no cut: counting only the
    padding, the rule hid both stops of the 800 x 360 dp sheet where the second stop's name was fully
    readable. On the 640 x 360 dp sheet the destination is now scrolled away entirely and "Add stop"
    sits under the start row; the divider and a drag show it. Snapping a scroll to item edges is a
    common list behaviour; the alternative (a pinned "To Downs Drive" line) would take room the
    shortest screens do not have and is a design question for the owner, since no design covers the
    sheet on its side.
26. **The golden-trace test stays with this track's fixtures for roads, kinds and lanes.** A test of
    N's real banners through the presenter would need `:nav:session` (or `:routing:banner`) on this
    module's test classpath, an edge section 1.1 does not give `:feature:drive`; `:app` has all of
    them, so it is the integrator's test (hand-over below). Meanwhile the test checks what the trace
    does carry, including now the chip's maneuver name.

## Definition of done (docs/architecture.md 5.1, D), item by item

| Item | Done | Proof |
|---|---|---|
| JVM test of `present`: frame 1 "Exit 24" / "US 19 / Peach St" / "toward Waterford" / "0.5 mi" / "In 0.3 mi" / no lanes | yes | BannerPresentTest frame 1; 65/65 JVM pass (11:11 UTC run, after the review fixes) |
| frame 2 "Turn left" / "onto US 19 / Peach St" / "400 ft" / "In 0.2 mi" / allowed-best-not | yes | BannerPresentTest frame 2 |
| frame 3 "Turn right" / "onto Downs Dr" / "0.2 mi" / no chip / not-not-not-best | yes | BannerPresentTest frame 3 |
| metric units | yes | BannerPresentTest metric (800 m, In 400 m, 100 m, In 350 m, 300 m, 2.0 km); GoldenTraceTest in km |
| each status (D2 order) | yes | StatusPresentTest "D2 - each status wins over every one after it" |
| no banner in REROUTING (D7) | yes | StatusPresentTest D7 tests; device: guidanceEvents asserts no banner node and the card's text |
| route cards with merged labels | yes | RouteSheetPresentTest ("Fastest · Simpler") |
| Eco never shown | yes | RouteSheetPresentTest (never named; an Eco-only route not shown) |
| Start blocked without precise location | yes | RouteSheetPresentTest; device: startBlockedWithoutPreciseLocationButSimulateStays |
| trip bar clock in America/New_York, 12- and 24-hour | yes | TripBarPresentTest ("Arrive 3:42 PM" / "Arrive 15:42", also across the end of DST) |
| `EventCards` timing | yes | EventCardsTest (7) |
| notification texts | yes | NotificationTextTest (9: the texts, and the pace of D6 since the review) |
| emulator screenshots of the three frames by day and night next to the sketch, looked at | yes | `data/r3-d/screens/frame{1,2,3}-{day,night}.png`, `data/r3-d/compare/compare.png`; what was seen is under "Screenshots, looked at" |
| saved places: `present` with a stop from a saved Home shows "Home" and its description; `FromPlace(home)` gives "Home" | yes | RouteSheetPresentTest saved-Home test |
| Delivers: banner with sketch icons and lane arrows, chip with THEN, three lane states, no hint text | yes | `TurnBanner.kt`, `DriveIcons.kt`; frames above; any number of lanes whole since the review (manyLanesFitTheBanner, `lanes8-*`) |
| route sheet (start row, cards, two toggles, Start, "Simulate drive" in the overflow menu, start-blocked reason) | yes | `RouteSheet.kt`; routeSheetEvents, `sheet-*`, `sheet-menu-day`; no stop line cut under the start row since the review (smallPhone, widePhone, largeFontScale) |
| stops editor (up, down, remove, best order, add from search) | yes | `StopsEditor.kt`; stopsEditorScreensAndEvents |
| trip bar, status chips, stop and arrival cards, resume card, Recenter and Overview | yes | `GuidanceParts.kt`; statusAndCardScreens, guidanceEvents |
| notification text | yes | `DrivePresenter.notification`, `NotificationPacer` |
| (rule 11) check_contracts passes, own tests pass, emulator stopped, report and README written, PROGRESS current | yes | rows of "How it is proved"; `emulator.sh d status` after the last device run: beamline_d not running, no slot of track d |

## What is not done or not verified

- The overlay over the real MapLibre map with the real session, `CameraDirector` and `EventCards` fed
  by real events: not tried (I wires `:app`). The presenter is tested on hand-made trip states and on
  N's golden trace (whose rows hold no maneuver kinds, road names or lane arrows: those are the
  fixtures', compared by hand with N's BannerModelTest). N's real banners through the presenter are
  not tested; that test is handed to I.
- Real TalkBack: not on the emulator image; what a screen reader is given was read and checked
  instead (above). TalkBack's own focus order and speech are not verified.
- Font sizes above 1.3, and very long real names: not checked (long names ellipsize by design).
- The landscape layout was checked in the test host (800 x 360 and 640 x 360 dp boxes, the activity
  turned to landscape), not with the real activity being rotated during guidance (section 8's
  "rotate" variant is I's run). The insets it reports on its side (`start`) have not been tried with
  M's `CameraDirector`.
- The status bar effect was checked with the test activity (edge to edge), not with `MainActivity`,
  and only on API 36 (the platform-call version of the review: the API 35+ path). The path for API
  26-34 (the decor view's flag) has not run on a device; it is the same write androidx core makes
  there (read from its bytecode). The second engineer's mutation of the status-bar effect was not
  repeated on the new code; `statusBarIconsOverTheBanner` passes on it and its whole-device shots show
  light icons over the banner by day and dark ones over the day map.
- The golden trace as a declared test input: declared, not tried (changing the file is N's).
- No performance measurement (frame time of the overlay while guiding).
- `NotificationPacer` is not used by anything yet (I's `GuidanceService`), and `heldUntilMs()` needs
  I's service to call `next` again; without that a held change waits for the next trip update.
- The route sheet's resting rule was checked in the sheet as the tests draw it (upright 360 x 640,
  at font size 1.3, 800 x 360 and 640 x 360 dp, two slow drags); not with a fling, not with nine
  stops, not with TalkBack scrolling.
- Lanes: eight lanes drawn and checked; up to 20 computed in the JVM tests, not drawn.
- The new arrow shapes and the night banner colours wait for the owner.

## Open questions for the owner

1. The arrow shapes drawn for the kinds the sketch does not show, and the banner's night colours
   (choices 11 and 12): approve or change.
2. The distance in the system's condensed face rather than Barlow Condensed (choice 13).
3. "Simulate drive" in public builds (6.38 question 4; built visible, as 6.29 says for the prototype).
4. The side layout for a phone on its side (choice 16, screenshots `land-*`): a draft of this round,
   as no design shows it. Since the review, on the shortest sheet (640 x 360 dp) the destination is
   scrolled away to keep the chosen route whole (choice 25); a pinned "To" line is the alternative.
5. Narrow lane boxes when a road has more lanes than fit (choice 22, `lanes8-*`): a draft.

## Review and fixes

Two reviews of the finished track ("reproduce" and "contract", both "sound with fixes", no blocker,
no major, seven minor findings). Each finding was checked against the code before acting on it.

| # | Finding | Checked | Outcome |
|---|---|---|---|
| R1 | GoldenTraceTest checks N's trace against hand-written banners and chips, not N's real ones | Right: `view(row)` builds the banners from `Exit24` fixtures; the trace has no kinds, roads or lane arrows; the expected chip is written in the test | **Partly fixed, rest handed over.** The test now also checks that the fixture's following maneuver is the one the trace's chip names; its doc comment, the README and this report say plainly what it does not prove; the fixtures were compared by hand with N's BannerModelTest (they agree). A test with the real session's banners needs `:nav:session` on this module's test classpath, an edge 1.1 does not give (choice 26): handed to I (board note `d-banner-roads.md`). |
| R2 | In the 640 x 360 dp sheet the scrolled destination's subtitle reads as the start's second line | Right: `land640-sheet-night.png` showed "Erie, Pennsylvania" alone under "From / Your position"; the selected card's bring-into-view stopped with the line's name scrolled away | **Fixed** (choice 25, `RouteSheet.kt`, `LayoutRules.kt` `sheetRestingScroll`). Tests: LayoutRulesTest (3 tests of the rule); device `smallPhone`, `widePhone` and `largeFontScale` assert that no stop line is cut through its letters at the top, `smallPhone` also after two slow drags. With the rule switched off `smallPhone` fails with the reviewer's frame (41 of 116 px shown, 75 px below its top). |
| R3 | D1's text says every road; the code shows two | Right: `MAX_ROADS = 2`; D1 reads "roads joined by ' / '" | **Request written** (the contract is not this track's to edit): `data/architecture/requests/d.md`, request 2, with the exact text for D1 (two names; "onto" also for merges and roundabouts). Code unchanged. |
| R4 | The device-test script overwrites the engineer's evidence on a re-run | Right: fixed `rm -rf data/r3-d/screens` and pull path | **Fixed**: `sh data/r3-d/run_device_tests.sh [OUT_DIR]` (default `data/r3-d/screens`, must be under `data/`, logs next to it), plus `BL_D_TESTS` for a subset. Used for every run of this round of fixes (`data/r3-d/run-dev/`, `run-mutant/`, `run-debug/`); the final run went to the default folder. Also found: `adb.sh` refuses a bare `-e`, so the subset is passed to the device's shell as one string. |
| C1 | The lane row cannot show 7+ lanes in the side panel or 8+ on a 360 dp phone | Right: fixed `size(40.dp, 44.dp)` boxes in a plain `Row`; the mutant run measured the eighth lane at 14 px against 105 px | **Fixed** (choice 22, `TurnBanner.kt`, `LayoutRules.kt` `laneMetrics`). Tests: LayoutRulesTest (3 tests, including whole pixels at density 2.625); device `manyLanesFitTheBanner` (360 dp), `smallPhone` (320 dp panel), `widePhone` (400 dp panel) with eight lanes, best at the far right; screenshots `lanes8-*`, `land640-lanes8-*`. The first version counted in dp and the device test caught the eighth lane 3-4 px short (rounding of the gaps). The lane row is now one merged node for screen readers (same description) instead of cleared semantics, so the tests can reach the boxes. |
| C2 | NotificationPacer lets GPS, phase and problem changes bypass D6's 5 s | Right: the key held `gps`, `phase` and `problem.kind`; a GPS state flapping each second posted each second | **Fixed** (choice 23, `NotificationPacer.kt`): at once only on a change of the maneuver named (and the arrival); the rest paced; new `heldUntilMs()`. Tests: NotificationTextTest's pace test rewritten and a new one (GPS flapping for 10 s posts twice). Both fail against the old pacer. |
| C3 | Direct dependency on androidx core-ktx is outside 1.1 | Right: `implementation(libs.androidx.core.ktx)` only for `WindowCompat` | **Fixed** by removing it (choice 24): platform calls in `Overlay.kt` written where `WindowCompat` 1.19.1 writes; the device test reads the platform's own `WindowInsetsController` (API 35+). `statusBarIconsOverTheBanner` passes; whole-device shots looked at. Noticed, not this track's: `:map:layers` also declares `androidx-core-ktx` while 1.1 lists only MapLibre for it (for the lead). |

Re-proved after the fixes: JVM 65/65 (`--rerun`, 11:11 UTC); device `OK (14 tests)`, 0 skipped,
70 PNGs and 5 dumps (11:04 UTC), looked at; compare.png re-rendered and looked at (frames unchanged);
check_contracts all the same, 39 frozen files unchanged; private-logs scan 0 breaches; network scan
0 findings, privacy 0; test APK without network permission; emulator stopped. Mutations: listed in
"How it is proved". A temporary debug test and hook used to find why the first version of the sheet
rule hid both stops at 800 dp were removed (`grep -rn debugSheet android/feature/drive` finds
nothing).
