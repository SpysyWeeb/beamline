# 16. Map layers (track M, feature round)

Date: 2026-11-16 (night). Contract: `docs/architecture.md` 2.5, 3.6, 3.7, 5.1 "M: map layers";
`docs/architecture-saved-places.md` 2.8, 5.2, 10.1, 10.4; `assets/markers/SPEC.md`. Stage 1 (the
first engineer) built `:map:scene`, the marker export and `sync_art.py`, and went on through the
stage-2 list. Stage 2 (the second engineer, who wrote this report) re-ran every stage-1 proof,
closed stage 1's open points (a device test of the controller, the browsing puck's glide), made
the demo replay track N's golden trace, and took and looked at a full new set of screenshots on the
current tree. Two reviewers then reproduced the proofs and read the code against the contract; a
third engineer checked their nine findings against the code, fixed the five that were right and
could be fixed in M's folders, and proved the track again (section 7, "Review and fixes"; sections
1-6 are brought up to date with it). Module READMEs: `android/map/scene/README.md`,
`android/map/layers/README.md`, `android/map/demo/README.md`. Scratch, screenshots and logs:
`data/r3-m/` (not in git), with `data/r3-m/PROGRESS.md`.

## 1. What exists

| Module | What |
|---|---|
| `:map:scene` (JVM) | `MapSceneMapper` (route lines while choosing and guiding, the traveled cursor, pins, puck or arrow with lenses, stale and `glideMs`, saved markers with kinds, minimum zooms, scale, a stop's saved place left out, `savedBelowRoute`); `CameraDirector` (K1-K7); `CameraRules.parse`/`bundled`; `SavedMarkerRules.parse`/`bundled`; `CameraTarget.withZoomCap` (K1's z9.99 cap outside the states, which the app applies with `ComposedMap.zoomFor`; 7.4). |
| `:map:layers` (Android) | `MapLibreController`: seven GeoJSON sources and 19 layers, 30 in a style composed with the overview map and a state (route casing and fill for the chosen line ahead, the driven part and the alternatives in route candidate B, widths by zoom, under the style's `roads_oneway`, the driven part and the part ahead each in a far and a near piece so a cursor step sends only the short near piece (7.1); with the overview map a second set under the overview's labels below z8 (7.5); pins; saved markers in two layers on one source, under or over the route lines; puck, cone, beam, glow, arrow, lit lenses above every label); the vehicle marker's glide (arrow and puck); camera moves; tap (the pin on top, then the saved marker on top as `saved:<id>`, then the POI label nearest the finger), long press, user camera moves; everything re-added on a style load, drawn in the style's theme until the next scene. Marker PNGs (11 pieces x 5 densities, candidate B) and `route_colors.json` copied in by `scripts/sync_art.py`. A device test, `MapLibreControllerDeviceTest` (12 tests). |
| `:map:demo` (not shipped) | `DemoActivity`: the app's style composed by `StyleComposer` with the PA z14 map file (and with `--ez overview true` the overview map), the controller, the mapper and the director; hand-made states on the exit 24 route (`assets/demo/exit24.json`) or N's golden trace of that drive replayed row by row (`assets/demo/trace24.json`, scene `trace`), both made by `scripts/make_exit24.py`; a made-up long trip Erie - Pittsburgh - Carlisle of about 4,300 points (scenes `long`, `long_drive`); made-up saved places at public spots in Erie; scenes and camera moves from `am start` extras. |

The contract files `Scene.kt` and `MapLibreController.kt` changed only inside their `TODO` stubs.
A class with state needs a field the contract does not declare; each class's one private field
(`builder`, `director`, `layers`) is written inside the replacement of its last stub, so every
character outside the stubs is unchanged (`check_contracts.py` reports both files `filled`).

## 2. How it is proved

All commands from the repository root in Git Bash. Results are the last runs of the review round
(2026-11-16, 03:49-04:08 local) unless marked.

| Check | Command | Result |
|---|---|---|
| Mapper and camera tests (5.1 M; companion 10.1 `:map:scene`) | `sh tools/round/gradle.sh m :map:scene:test --rerun` | 58 tests, 0 failed, 0 skipped: `MapSceneMapperTest` 23, `CameraDirectorTest` 29 (K1's cap with `withZoomCap` added), `CameraRulesTest` 6 (forced to run: XML timestamps 11:07:36Z) |
| Layers' JVM parts | `sh tools/round/gradle.sh m :map:layers:testDebugUnitTest --rerun` | 18 tests, 0 failed: colours copy and values, route split, the far and near pieces joining into the split (every 7th cursor of a 300-point line), a cursor step on a 5,000-point line sending only the near pieces, tap rule (pins on top, Home over a star, saved over POI), the POI label nearest the finger, sort order, pin images, glide interpolation, two `Glide` tests, route placement and POI layers in composed styles (states only; overview and a state; overview only; the real `day.json` composed with the overview and PA by `StyleComposer`'s rules) and in the real `day.json`, the layer ids (XML timestamp 11:07:36Z) |
| Controller on a real map | `MapLibreControllerDeviceTest` with `am instrument` (2.1) | 12 tests, OK, three runs in a row on `beamline_m` (`data/r3-m/r-device-test-run{1,2,3}.txt`); the 4 tests added in the review failed on the code before the fixes (7.6) |
| camera.json copy | `python android/routing/scripts/sync_data.py --only camera.json --check` | `same` (and `CameraRulesTest` compares the copy with its source) |
| Marker PNGs in candidate B | `.venv/art/Scripts/python packbuilder/map/style/build_markers.py --route-candidate B` (stage 1) | 11 pieces x 5 densities, 625,488 B; `markers.json` route_candidate `B`; the puck's centre pixel #4A8CF7 (day), #3A80F0 (night), read with PIL |
| The copies in `:map:layers` | `.venv/art/Scripts/python android/map/layers/scripts/sync_art.py --check` | "55 marker PNGs and route_colors.json checked (route candidate B): all the same", exit 0. Shown to fail once (stage 1): one byte appended and one extra PNG gave exit 1 with `DIFFERS` and `EXTRA` (`data/r3-m/sync_art_negative.txt`) |
| Demo assets | `python android/map/demo/scripts/make_exit24.py --check` | `same` for `exit24.json` (78 points, 5,334.6 m, maneuvers at 4,167.7, 4,587.8, 4,943.1 m) and `trace24.json` (289 rows of `android/routing/testdata/nav/exit24.json`) |
| Logging rule PV2 | `.venv/search/Scripts/python android/scripts/check_private_logs.py scan --module map/scene --module map/layers --module map/demo` | "15 files in 3 modules; 11 marked lines; 0 breaches". `:map:scene` has no log call; `:map:layers` two plain-literal `Log.w`; the 11 marked lines are the demo's (stage 1's demo had 10 unmarked ones; the demo is not shipped, but it follows the rule, with the POI-name and marker-position lines behind `--ez location_log true`) |
| Network rule | `python android/scripts/check_network_rule.py scan` and `privacy` | "scan: 0 finding(s)" (the whole tree now); "privacy: 0 private IPv4 literal(s)" |
| Manifests | read the merged manifests of `:map:demo` (debug) and of `:map:layers`'s test APK (stage 2; the review round changed no manifest and no build file) | permissions: coarse and fine location (MapLibre's AAR), the app-own receiver permission, and in the test APK `REORDER_TASKS` (AndroidX Test's `ActivityScenario`); no `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, no cleartext or network security config |
| Contract and freeze | `python tools/check_contracts.py` | "39 contract files (26 with filled stubs) ... all the same"; "39 frozen files ... all unchanged" |
| Builds | `sh tools/round/gradle.sh m :map:layers:assembleDebug :map:layers:assembleDebugAndroidTest :map:demo:assembleDebug` | BUILD SUCCESSFUL |

### 2.1 Device test of the controller (`android/map/layers/src/androidTest/`)

`TestMapActivity` (a full-screen MapView) and `MapLibreControllerDeviceTest`: a small offline style
whose layers are named the way `StyleComposer` names them (`roads@us-pa`, `roads_oneway@us-pa`,
`pois@us-pa`, `pois_driving@us-pa`), a GeoJSON source, stand-in images for the three saved icons
(test code only), around downtown Erie at z15. Run:

```
sh tools/round/gradle.sh m :map:layers:assembleDebugAndroidTest
sh tools/round/adb.sh m install -r data/gradle/m/build/map/layers/outputs/apk/androidTest/debug/layers-debug-androidTest.apk
sh tools/round/adb.sh m shell am instrument -w io.github.spysyweeb.beamline.map.layers.test/androidx.test.runner.AndroidJUnitRunner
```

| Test | What it proves |
|---|---|
| `routeLayersGoUnderRoadsOnewayAndMarkersOnTop` | the style's layers in order: our 7 "under" layers directly under `roads_oneway@us-pa`, our 8 marker layers last |
| `onlyTheSavedLayerTheSceneNamesIsVisible` | `savedBelowRoute` false: `beamline-saved-over` visible and drawn, `-under` none; true: the other way round |
| `aStyleReloadBringsEverythingBackInTheNewTheme` | after a day to night `setStyle` + `onStyleLoaded`: the same order, the route line colour #3A80F0 (night B), pin, saved marker under the route, route ahead, driven part, arrow and beam drawn again; back to day: #4A8CF7 and no beam |
| `tapsReportPinsThenSavedMarkersThenPoiLabels` | injected taps: a pin's head gives `stop:0`; Home and a star at one point over a `pois_driving` label give `saved:<Home id>` and no POI; a `pois` label gives its name, kind and its own point; empty map gives nulls |
| `longPressAndUserPanAreReported` | an injected long press gives `LongPress` at the finger's point; an injected swipe gives `CameraMovedByUser`; an app `moveCamera` does not |
| `cameraMovesCentreWithBearingAndPitchOrFitABox` | a centre with zoom, bearing 30 and pitch 45 lands exactly; `zoom = null` keeps the zoom; a padded box fits top-down with both corners in view; an eased move ends where asked |
| `theArrowGlidesOverItsGlideMs` | an arrow with `glideMs` 2,000 is drawn between the two points after 1 s, still on its way after 1.5 s although lenses were lit on the way (the glide is not restarted), at the new point at the end, lit lenses drawn |
| `thePuckGlidesOverTheTimeBetweenItsPositions` | a puck moved 1 s after the last one glides (in between half-way; a cone turn on the way keeps the glide), ends at the new point; a puck after more than 3 s jumps (never drawn in between) |
| `aStyleLoadedInAnotherThemeIsDrawnInThatTheme` (review) | a day scene, then a night style with no new scene: night route colour #3A80F0 and the beam; a night scene, then a day style: #4A8CF7 and no beam |
| `aTapOnTwoPoiLabelsReportsTheNearest` (review) | two `pois` labels 7 dp apart, both inside the tap box of either: a tap on each reports that one |
| `withTheOverviewTheRouteStaysUnderEveryLabelAtEveryZoom` (review) | a style named as `StyleComposer` names the overview with a state (`roads@overview`, `places@overview` up to z8, the state's layers from z8): our 11 layers below the labels twice, the copies under `places@overview` with `maxzoom` 8, the set under `roads_oneway@us-pa` with `minzoom` 8; the saved "under" layer and its copy switch together; read from a screenshot (UiAutomation): at z7 the overview's label circle shows its own colour on the line and the line shows #4A8CF7 beside it; at z12 the state's label over the line |
| `aLongRouteFollowsItsCursor` (review) | a 5,000-point line (about 50 km), the cursor moved 150 points, one `show` every 30 ms with the arrow: the driven part drawn behind the cursor, the part ahead in front of it and not behind; logs the main-thread time of each `show` (section 3) |

`data/r3-m/demo.sh` `layers_test` installs and runs it (one test: `layers_test '#<name>'`).

The first run gave 5 of 8: two failures were my test's expected list, which did not allow for
MapLibre's own `org.maplibre.annotations.points` layer (it sits between the style's layers and ours;
the app does not use annotations; the test now leaves `org.maplibre.*` layers out), and one was a
single sample 300 ms after a jump, before the jump was drawn. Measured since then: a jump of the puck
is drawn 331 ms after `show` on this emulator (twice; logged by the test), never at a point in
between; the end checks now wait for the rendered point (at most 2 s). Then 8 of 8, three runs in a
row (`data/r3-m/s2-device-test-run{1,2,3}.txt`; run 3 after the tap change of 4.2). In the review
round, 12 of 12 three runs in a row (`data/r3-m/r-device-test-run{1,2,3}.txt`; the jump of the puck
drawn after 365 ms in run 1).

### 2.2 Emulator screenshots, looked at (`beamline_m`, 1080 x 2400, 420 dpi, API 36)

Stage 2's own set on the current tree (the style bundle P re-synced at 02:13 and P's current
`:core:packs`): `data/r3-m/shots2/s2-<day|night>-<name>.png`; contact sheets and crops
`data/r3-m/s2-sheet-*.png`, `data/r3-m/s2-crop-*.png`. Scripts: `data/r3-m/s2-shots-browse.sh`,
`s2-shots-guide.sh`, `s2-shots-camera.sh` (helpers `data/r3-m/demo.sh`). Stage 1's set
(`data/r3-m/shots/`, sheets `data/r3-m/sheet-*.png`) was looked at too and agrees.

| Item (5.1 M "Done when", companion 10.4) | Screenshot(s) | What I saw |
|---|---|---|
| Route B by day and night at z12 and z16 | `route-z12`, `route-z16` (day, night) | the blue B line with its darker casing along I-90; at z12 under the 79/90/99 shields; at z16 under the "AMVETS Memorial Highway" label; night: the night pair on the dark map |
| Alternatives | `choosing-preview` (day, night) | PREVIEW fit, top-down: the chosen line and the grey alternative where it leaves the route near Downs Drive; destination pin |
| Pins | `stops-preview`, `pins-z155`, `picked-z15` (day, night) | numbered stop "1" on Peach Street and the destination pin, both in the PREVIEW fit; the picked place white with a blue edge at the Erie Art Museum point; pins anchored at their tips |
| Puck with cone | `puck-z16` (day, night), `puck-stale-z16`, `follow-z16` | blue B dot with white ring and a cone towards the north-east; stale: grey dot, no cone; browse follow: the puck centred with its cone along I-90 |
| Arrow moving | `guide-moving-under-route`, `trace-exit` (day, night) | arrow in the lower third, heading up, foreshortened on the road, the driven part behind it in the traveled colour; lenses dark (`s2-crop-arrow-day.png`) |
| Stopped with lenses lit | `guide-stopped`, `trace-halt` (day, night) | bright red lenses and the glow behind (faint by day, stronger by night); `trace-halt` is N's real session (motion STOPPED 202-220 s of the trace) at the same spot as the hand-made stop |
| Night beam | every night guide shot | the beam ahead of the tip on moving, stopped, the exit, the halt and the arrival; never by day |
| Stale arrow | `guide-stale` (day, night) | grey arrow, lenses dark, no glow, no beam (also at night) |
| Tilted heading-up follow at the exit | `trace-exit` (day, night) | pitch 50, heading up along the ramp towards Peach Street, arrow in the lower third (log `camera idle z=15.74 pitch=50 bearing=58` on I-90) |
| Recenter after a pan | `recenter-0-follow` ... `recenter-3-back` | a real swipe (`input swipe`): log `gesture camera moved by user` and GUIDE_FREE with Recenter at 03:07:04.6; the arrow drives up the screen while free; back to GUIDE_FOLLOW by itself at 03:07:20.2 (15.5 s; `tick()` once a second), arrow back in the lower third |
| A style reload (day to night) with the layers back | `reload-0-day`, `reload-1-night` | log `style loaded (NIGHT): 89 layers, ours 15`; route, driven part, pin and arrow back in night colours with the beam; follow goes on |
| Overview, ARRIVED (K5, K6) | `overview`, `overview-back`, `trace-arrived` (day, night) | overview top-down north up with the remaining route and Recenter, back to follow after 10.9 s (log); ARRIVED (the trace's last row) top-down, north up, centred on the destination pin, closer in than the follow view (the director asks `arrived.zoom` 17; the zoom was not read back) |
| Three markers at z11 and z13, day and night | `saved-z13-home`, `-work`, `-star`, `saved-z11`, `saved-z12-star` (day, night) | house at the Erie Maritime Museum, briefcase at the airport, star at Millcreek Mall, each centred at z13; at z11 house and briefcase, nothing at the mall; the star at z12; drawn over the style's labels; night blue brighter |
| Marker over the route line in route choice, under it in the tilted guide view | `saved-over-route` (day, night), `guide-moving-under-route` (day, night) | over the line and its label with the white edge while choosing; while guiding under the route line (and under the beam), upright in the tilted view (`s2-crop-stack.png`, `s2-crop-arrow-night.png`) |
| Home and a star at one point | `saved-stack-z15` (day), `tap-stack-before` (night) | the house on top, the star hidden under it, both themes |
| A marker tap with its pin id | logcat (`data/r3-m/s2-taps-day.txt`, `s2-taps-night.txt`, `s2-taps-pins.txt`) | the stack: `pinId=saved:s0000000000000a01` (Home) by day and by night; the star alone: `saved:s0000000000000a03`; the UPMC Hamot POI: `poi=UPMC Hamot`; open water: `pinId=null poi=none`; long press reported; stop pin: `stop:0`; destination: `stop:1`; where the two pins overlap: `stop:1` (the destination, drawn on top) |

One shot was wrong on the first try and was taken again: `stops-preview` showed the z16 view instead
of the PREVIEW fit. The cause was the demo, not the director: my `--ef lat/lon/zoom` camera move
bypasses the director, so when the stops changed the director computed the same fit box as before
(the stop lies on the route) and rightly did not emit an equal target again. In the app a user pan
is reported (`CameraMovedByUser`), which makes the target null, so the next fit is a new target. The
script now goes through `idle` first.

**Review round (`data/r3-m/shots3/r-*.png`, scripts `data/r3-m/r-shots-route.sh day|night`,
`r-shots-overview.sh before|after`, `r-shots-reload-fps.sh`; sheets `data/r3-m/r-sheet-*.png`, crop
`data/r3-m/r-crop-joints.png`).** The route layers were rebuilt (7.1, 7.5), so every route item was
taken again on the new code and looked at:

| Item | Screenshot(s) | What I saw |
|---|---|---|
| Route B by day and night at z12 and z16, alternatives | `r-<day,night>-route-z12`, `-route-z16`, `-choosing-preview` | as in stage 2: the B pair under shields and road labels, the grey alternative and the destination pin in the PREVIEW fit |
| Arrow moving with the driven part, tilted follow at the exit | `r-<day,night>-guide-moving`, `-trace-exit` | the driven part behind the arrow in the traveled colour, the line ahead in B; night beam |
| Where a far and a near piece touch (point 64 of the exit 24 line, on Downs Drive, top-down z18) | `r-<day,night>-joint-ahead-z18` (cursor before it), `-joint-driven-z18` (cursor after it); crop `r-crop-joints.png` | one continuous line with an even casing, no seam and no cross-line at the joint, in both colours of both themes |
| A style reload (day to night) with the layers back | `r-reload-0-day`, `r-reload-1-night`; log `data/r3-m/r-reload-log.txt` | `style loaded (NIGHT, overview=false): 93 layers, ours 19`; route, driven part, the saved star under the line, arrow and beam back in night colours |
| The route with the overview map at z7 (review finding 5) | `r-before-*` (old code), `r-after-*` (new): `long-z7-pittsburgh-day`, `-night`, `long-z75-erie-day`, `long-preview-day`, `long-z9-day` | before: the line drawn across "Pittsburgh", "Meadville", "Grove City", "Franklin", "Edinboro" and the US 322 shield; after: the line under every one of them, day and night; at z9 (the states draw) both the same. Logs: before `route lines: under roads_oneway@us-pa`; after `under water_label_ocean@overview (z -Infinity-8.0); under roads_oneway@us-pa (z 8.0-Infinity)` (`data/r3-m/shots3/r-<before,after>-style-log.txt`) |

## 3. Measurements (indicative only: a shared emulator; all three emulator slots in use, by M, N and S)

Frames rendered per second, counted from MapView's `onDidFinishRenderingFrame` (frames MapLibre drew,
not display refreshes), one log line a second (`data/r3-m/s2-fps-*.txt`; stage 1's in
`data/r3-m/fps-follow-*.txt`):

| Mode | Where | Run | Seconds | Median | Min | Max | Mean |
|---|---|---|---|---|---|---|---|
| BROWSE_FOLLOW, top-down, z16 | puck moving along I-90 in Erie at the route's speed, 1 fix/s | stage 1 | 40 | 39 | 13 | 61 | 37.8 |
| | | stage 2, run 1 | 48 | 33.5 | 6 | 47 | 31.8 |
| | | stage 2, run 2 | 48 | 42 | 2 | 45 | 40.6 |
| GUIDE_FOLLOW, pitch 50, z ~15.75 | N's trace from 20 s on, on I-90 | stage 1 (hand-made drive) | 45 | 29 | 12 | 42 | 29.0 |
| | | stage 2, run 1 | 50 | 23.5 | 3 | 35 | 22.3 |
| | | stage 2, run 2 | 49 | 25 | 6 | 38 | 25.4 |

The spread between runs (33.5 and 42 for the same scene a few minutes apart) is larger than any
difference that could be put on the code; the minima are the first second of a scene (style tiles,
the transition). Stage 2's browse follow redraws the puck on every frame (its new glide); its second
run is the highest browse figure of the three, so no cost of the glide is visible at this precision.
The phone is I's (5.4 item 15).

Review round, after the route layers were rebuilt (`data/r3-m/r-fps-*.txt`, the same shared
emulator, other tracks' emulators running; the log line now also gives the longest main-thread time
of `show()` in each second):

| Mode | Where | Seconds | Median fps | Min | Max | Mean | Longest `show()` per second: median, max |
|---|---|---|---|---|---|---|---|
| BROWSE_FOLLOW, top-down, z16 | puck along I-90 in Erie | 51 | 44 | 15 | 57.9 | 43.0 | 0.15 ms, 0.41 ms |
| GUIDE_FOLLOW, pitch 50 | N's trace from 20 s on (78-point route) | 52 | 24 | 9 | 38.9 | 24.7 | 0.38 ms, 1.07 ms |
| GUIDE_FOLLOW, pitch 50 | `long_drive` from 20 km on (made-up 4,300-point line, 30 m/s) | 51 | 45 | 2 | 56.9 | 43.1 | 0.34 ms, 5.84 ms |

The guide follow on the trace (24) is where stage 2 measured it (23.5 and 25): the rebuild of the
route layers changed nothing visible at this precision. The long line in open country draws faster
than the exit 24 interchange; it is not a comparison of route lengths.

Main-thread time of one `show()` while the cursor steps along a 5,000-point line
(`aLongRouteFollowsItsCursor`, 150 steps, one every 30 ms, with the arrow; review finding 1). The
"before" build is the stage-2 controller with this test added (`data/r3-m/before/`):

| Build | Run | First `show` | Median | p90 | Max |
|---|---|---|---|---|---|
| before (whole line sent per step) | in the full suite | 14.48 ms | 1.738 ms | 3.634 ms | 10.143 ms |
| | alone, run 2 | 39.58 ms | 2.608 ms | 27.045 ms | 50.832 ms |
| | alone, run 3 | 36.94 ms | 2.700 ms | 26.601 ms | 48.201 ms |
| after (near pieces per step) | in the full suite, runs 1-3 | 15.39 / 19.09 / 25.34 ms | 0.423 / 0.662 / 0.589 ms | 0.754 / 1.123 / 0.942 ms | 8.160 / 18.639 / 16.448 ms |
| | alone, runs 1-2 | 68.23 / 33.65 ms | 0.681 / 0.722 ms | 1.517 / 1.281 ms | 14.310 / 20.016 ms |

The median step costs about a quarter of what it did and the p90 drops from up to 27 ms to under
1.6 ms. The maxima stay (8-20 ms): two of the 150 steps enter a new chunk and send the far pieces
(the whole line, as every step did before), and the emulator is shared. Only the main thread is
measured; MapLibre re-tiles a changed source on its own worker thread, which is not in these numbers
(on most steps it now re-tiles the near source, two pieces of 67 points together, instead of the
5,000-point line).

The puck stays still on the screen while following (stage 1's open point 4, fixed in 4.1): in 10
screenshots taken one after another during BROWSE_FOLLOW at z16, the centre of the puck's solid dot
moved within 7.9 px horizontally and 5.2 px vertically (`data/r3-m/s2-puck-positions.txt`,
`s2-puck-pos.py`). Without a glide the puck would lead the camera by up to one fix: 24.7 m at the
route's I-90 speed, about 14 dp (36 px) at z16 (computed, not measured).

## 4. Choices and reasons

### 4.1 Stage 2

- **The browsing puck glides too** (`Glide` in `SceneParts.kt`, used by `Layers`). Stage 1 glided
  only the arrow, while the director eased BROWSE_FOLLOW moves over the time between puck updates,
  so the puck jumped ahead and drifted back to the centre once a second. The puck now glides over
  the time since its last new point, at most 3 s (the director's own limit), measured in the
  controller; a longer gap jumps. No contract change: `VehicleMarker.Puck` has no glide field, and
  rule K3 asks only for the arrow; "glide animation between fixes" (5.1 M) covers both.
- **A glide keeps going for another look of the same point** (lenses lit, stale, night, a cone
  turned by the compass several times a second); a new point in the middle of a glide starts from
  where the marker is. Stage 1 restarted the glide on any change of the marker.
- **Frames by `Choreographer`, not `ValueAnimator`**: the developer option "animator duration
  scale" (0 on some phones) ends a ValueAnimator at once, which would freeze the arrow while
  MapLibre's camera ease (native, not scaled) goes on.
- **Overlapping pins: the one drawn on top wins** (picked over the destination over the stops, by
  the pins' sort key), whatever order MapLibre returns features in; the same rule as for the saved
  markers (SP42). Stage 1 took MapLibre's first feature.
- **The demo replays N's golden trace** (`--es scene trace`): the vehicle, motion, GPS state, phase,
  upcoming maneuver and shown distance of each row; the bearing is the route's at the snapped point
  (the trace marks every row snapped), the traveled cursor the projection on the route. The
  hand-made states stay for the static cases (moving, stopped, stale at a chosen point).
- **The demo's logs follow PV2** although the demo is not shipped: values marked, a tapped POI's
  name and the markers' screen positions only with `--ez location_log true`.

### 4.2 Stage 1 (kept)

- **glideMs** = the time between the last two distinct vehicle positions; the same position seen
  again keeps its glide; 0 for the first position and for a gap over 3 s (`VehicleGlide.MAX_GLIDE_MS`),
  so fixes coming back after a GPS loss make the arrow jump, as section 8's `gaps` variant asks. A
  code constant, because `camera.json`'s keys are fixed by the contract this round (question 1).
- **Camera animation**: follow moves over the glide (linear `easeCamera`, the same time and curve as
  the marker's glide); mode, viewport and inset changes over `transition_ms`; an identical move with
  only another duration is not re-emitted.
- **Vehicle at `vehicle_from_bottom`** through the camera padding: extra top padding = 0.44 x the free
  height for 0.28.
- **PREVIEW** re-fits when the choices, the stops or a chosen origin change (also after a pan), not
  when another card is chosen; with no route yet it fits the stops and the puck; a box of about one
  point is a centre at `follow_zoom`.
- **Ending guidance** (GUIDING to IDLE) emits one move to pitch 0, north up, keeping the zoom.
- **Before the first vehicle position** the puck stands in (scene) and the camera follows the puck or
  the route's start along its first bearing.
- **Stale puck has no cone** (decision 6.21, SPEC.md); an unknown course keeps the last bearing.
- **Pins** (decision 6.22, drawn in code, drafts; owner question 2, built as recommended): teardrop
  28 x 38 dp in route casing B (#1F5BC4, white numerals), reached stops grey, the picked place white
  with a blue edge. Ids `stop:<index into TripState.stops>` and `picked`.
- **Layer anchor in a composed style**: `StyleComposer` renames layers `<id>@<pack>`, so the route
  layers go under the first state copy of `roads_oneway` and taps query every copy of `pois` and
  `pois_driving` (device-tested in 2.1 with those names). With the overview map composed in too, a
  second set goes under the overview's first label below z8 (review round, 7.5).
- **Saved markers while guiding** sit under the route lines and therefore also under the style's
  labels (the route lines are under `roads_oneway`); they still show (overlap allowed). SP42's "above
  all labels" cannot hold together with "below the route line" while the route line is below the
  labels (question 4).
- **Theme** of the app's layers follows `MapScene.theme`; both day and night marker images are
  registered. Since the review round, right after a style load the style's own theme until the next
  scene (7.3).

## 5. Open questions (none blocks; the recommended or assumed answer is built)

1. Add `guide.glide_max_ms` (3000) to `camera.json`? Today it is a constant in `:map:scene`
   (`Motion.kt`) and, for the puck, in `:map:layers` (`Glide.MAX_PUCK_GLIDE_MS`, the same value).
   Not requested; it works as is.
2. The pin artwork and the picked pin's look are drafts (decision 6.22, owner question 2).
3. The saved-place icons and colours are P's drafts (owner question 12); markers on by default is
   the app's and S's matter (question 13): the mapper draws what it is given.
4. SP42 says saved markers are above all style labels, SP43 that they are below the route lines
   while guiding; with the route lines below the labels both cannot hold. Built: below the route
   lines (and so below labels) while guiding, above every label otherwise. The reproduce review
   (finding 1) agrees this needs a ruling by the orchestrator or the owner before I wires the app:
   accept it and amend SP42 ("while guiding, below the route lines"), or ask M for a route-line
   copy above the labels while guiding (a larger change). Not a track decision; left as built.
5. ~~Below z8 with the overview map~~: fixed in the review round (7.5), rendered with the real
   overview map before and after.
6. ~~I's checklist does not name rule K1's z9.99 cap~~ (only the app can apply it, `withZoomCap`,
   7.4): request `data/architecture/requests/m.md`, approved and applied by the lead the same night
   (`data/r3-board/lead-m-request-applied.md`): 5.4 item 4 now says it.
7. The driven part is cut at the cursor on each fix while the arrow glides, so its end runs up to
   one fix ahead of the arrow's centre: 24.7 m on I-90, about 12 dp at the guide zoom 15.75
   (computed), inside the arrow, whose tip is about 29 dp (half its 58 dp length, SPEC.md) ahead of its centre. Not visible in the
   screenshots; left as it is.

## 6. Not done or not verified

- Nothing ran on the phone (I's, 5.4); the frame rates are emulator figures with a wide spread.
- `CameraDirector` and `MapSceneMapper` were not driven by N's live session on the device; the demo
  replays N's golden trace (the session's output, recorded) and hand-made states. I's wiring and the
  E2E of section 8 drive them for real.
- The `gaps`, `tunnel`, `wrongturn` variants were not replayed in the demo (N's trace is the clean
  run only); the jump after a gap is proved on the JVM (`glideMs` 0 after more than 3 s) and on the
  device (the puck after more than 3 s).
- The overview-map case is now rendered in the demo with the real overview map and the PA pack
  (7.5), but only with a made-up straight line, not a real Valhalla route.
- The cost of a long route is measured on the main thread only (section 3); MapLibre's worker-thread
  re-tiling is not measured, and no real several-thousand-point Valhalla line was used (the
  made-up lines have 4,300 and 5,000 points).
- `withZoomCap` is tested on the JVM only; nothing applies it until I wires `PackMap` (7.4).
- Screenshots, logs and the helper scripts are under `data/r3-m/` (not in git).

## 7. Review and fixes

Two reviews (2026-11-16): "reproduce" (verdict sound, 4 minor findings) and "contract" (verdict
sound with fixes, 5 minor findings; no blocker, no major). Each finding was checked against the
code before acting on it. The device tests for findings C1, C2, C3 and C5 were written first and
run on the unchanged stage-2 code (7.6), then the code was fixed.

| # | Finding | Checked against the code | Done | Proof |
|---|---|---|---|---|
| C1 | The whole route re-sent on every cursor step | Right: `RouteLines` holds `traveled`, so `scene.route != drawnRoute` was true at each step and `drawRoute` rebuilt and re-sent the ahead, driven and alternatives sources | Fixed (7.1) | JVM: "the four pieces join ...", "a cursor step on a long route sends only the short near pieces"; device: `aLongRouteFollowsItsCursor`; main-thread time before/after in section 3; no joint seen at z18 (2.2) |
| C2 | A POI tap took MapLibre's first feature, not the nearest | Right (`firstNotNullOfOrNull` over `queryRenderedFeatures`) | Fixed (7.2) | JVM "a tap on several POI labels reports the one nearest the finger"; device `aTapOnTwoPoiLabelsReportsTheNearest` (failed before: "Test Bakery" for a tap on "Test Diner") |
| C3 | `onStyleLoaded`'s theme ignored when a scene exists | Right (`styleTheme` written, never read; colours from the last scene's theme) | Fixed (7.3) | device `aStyleLoadedInAnotherThemeIsDrawnInThatTheme` (failed before: #4A8CF7 on a night style) |
| C4 | K1's z9.99 cap not applied, not on I's checklist | Right (`Director` has no cap; its frozen constructor gets no installed maps) | Helper, and a request the lead applied to 5.4 item 4 (7.4); applying it is I's | JVM "K1 the app caps a follow outside the states ..."; `data/architecture/requests/m.md`; `data/r3-board/lead-m-request-applied.md`; scene README |
| C5 / R2 | With the overview map, route lines above the overview's labels below z8 | Right (`StyleComposer` puts all overview copies first; the anchor was the first state copy). One part of the suggested fix is not possible: `roads_oneway@overview` does not exist (roads_oneway starts at z15, the overview stops at z10) | Fixed with an anchor that exists (7.5) | JVM placement tests (the real `day.json` composed by the rules); device `withTheOverviewTheRouteStaysUnderEveryLabelAtEveryZoom` (failed before: saw #4A8CF7 at the label); demo with the real overview map, before/after shots (2.2) |
| R1 | Saved markers below the labels while guiding, against SP42 | Right as a description; the code does what the report says. It is a conflict between SP42 and SP43, not a code fault | Left as built; ruling asked (section 5, question 4) | `data/r3-m/shots2/s2-day-guide-moving-under-route.png` |
| R3 | Frame rates cannot separate the glide's cost from noise | Right | Nothing to change in code; measured once more (section 3: guide follow on the trace 24 fps, where stage 2 had 23.5 and 25); indicative only; the phone is I's | `data/r3-m/r-fps-*.txt` |
| R4 | Mapper and director never driven by a live session | Right | Outside M's folders (N's session, I's wiring); unchanged: the demo replays N's recorded trace; the jump after a gap is covered by the JVM and device tests | section 6 |

### 7.1 The route in far and near pieces (C1)

`RouteCut` (SceneParts.kt) cuts the chosen line into chunks of 64 points. Four pieces: the start to
the cursor's chunk and the end of that chunk to the end (far), the chunk's start to the cursor and
the cursor to the chunk's end (near). Sources: `beamline-route-traveled` (driven, far),
`beamline-route` (ahead, far; the whole line while choosing), `beamline-route-near` (both near
pieces, property `part`), `beamline-route-alternatives`. `RouteSources` remembers what each holds
and sends a source only when its own content changed: the near source on every cursor step, the far
ones when the cursor enters another chunk (78 times over a 5,000-point line), the alternatives when
they change (no longer at every step while guiding). Layers: each kind has a far and a near casing
and fill, every casing of the kind below every fill of it, so the pieces join without a seam (opaque
colours, round caps; looked at at z18). The JVM test checks that the pieces join into exactly the
lines of `split` for every 7th cursor of a 300-point line, and that 61 steps inside one chunk of a
5,000-point line send only near pieces of at most 68 points together and nothing else.

Not chosen: one source with a `line-gradient` over `line-progress`, or a trim of the line. A
gradient is drawn through a colour ramp along the whole line, so on a 400 km route the cut would
fall far from the arrow unless the ramp is very fine; I did not try it, nor confirm that MapLibre
Native 13.7.1 has a line trim. The chunked sources keep today's colours, widths and layers.

### 7.2 The POI label nearest the finger (C2)

Every named POI feature in the 24 dp tap box is collected with the screen distance from the finger
to its own point (`MapLibreMap.projection`); `SceneParts.nearestPoi` takes the nearest, the first of
equals; a feature without a point geometry counts as farthest. Pins and saved markers keep their
"drawn on top wins" rule.

### 7.3 The theme after a style load (C3)

`onStyleLoaded(style, theme)` draws the last scene at once in the style's `theme` (route colours of
that theme, the beam only on a night style), even when that scene was made for the other theme: a
style can finish loading before the app's scene of its theme arrives. The next `show(scene)` decides
again (last writer wins). The unused `styleTheme` field is gone. The other choice, letting the
scene's theme win, leaves day colours and no beam on a night map until the next scene, which is
what the reviewer found.

### 7.4 K1's zoom cap (C4)

The cap needs the installed maps (`ComposedMap.zoomFor` in `:core:packs`), which `:map:scene` may not
depend on (1.1), and `CameraDirector(rules, clock)` is frozen, so the director cannot apply it; a new
public input on `CameraDirector` would change the contract file outside its stubs. Instead
`:map:scene` gives `CameraTarget.withZoomCap(zoomFor)` (`ZoomCap.kt`), whose function type matches
`ComposedMap::zoomFor`: a centre with a zoom is capped at that centre; a target that keeps the zoom,
or fits a box, is returned as it is. The app writes
`controller.moveCamera(target.withZoomCap(composed::zoomFor))` and keeps
`setMaxZoomPreference(composed.maxZoom ...)` as today's `PackMap` does. The JVM test runs the
director: z16 at the Erie Maritime Museum, z9.99 at Conneaut (Ohio, outside the Pennsylvania box),
nothing else of the target changed. Only I can apply it, so the request
`data/architecture/requests/m.md` asked for one sentence in 5.4 item 4 (with the "re-apply the target
after a style load" step that the board note already gave); the lead approved and applied it at
04:03 (`data/r3-board/lead-m-request-applied.md`; `docs/architecture.md` 5.4 item 4 now names
`target.withZoomCap(composed::zoomFor)`, `setMaxZoomPreference` and `onStyleLoaded` with the
target re-applied). K1's cap is therefore not in M's director; it is a tested helper that I's
checklist tells I to call.

### 7.5 The route under the overview's labels (C5, R2)

`SceneParts.routePlacement` reads the style's layers in drawing order (id, label or not, minimum
zoom). With a state copy of `roads_oneway`, an overview layer that is a label (symbol or circle),
and state copies that start above zoom 0, the route layers (and the saved markers' "under" layer)
go in twice: under `roads_oneway@<state>` with `minzoom` = the lowest `minzoom` of the state copies
(8 today, read from the style, not hard-coded), and a copy of each (id + `-overview`) under the
overview's first label layer with `maxzoom` at the same zoom. With today's style that label is
`water_label_ocean@overview` (`roads_oneway@overview` does not exist, see the table). With the
overview only: one set under its first label; with states only: one set as before. The saved
"under" layer is copied too, so its order stays right if `camera.json` ever shows markers below z8.
Cost: 11 more layers in a style with the overview, each drawn only below z8, on the same sources.

### 7.6 The new tests on the old code

Before any fix, the device test with the four new tests ran on the stage-2 code
(`data/r3-m/before/device-test.txt`): 12 tests, 4 failures. `aTapOnTwoPoiLabelsReportsTheNearest`
and the old `tapsReportPinsThenSavedMarkersThenPoiLabels` (which now has a second label 7 dp from
"Test Diner") got "Test Bakery" for "Test Diner"; `aStyleLoadedInAnotherThemeIsDrawnInThatTheme`
read #4A8CF7 on the night style; `withTheOverviewTheRouteStaysUnderEveryLabelAtEveryZoom` saw the
route's #4A8CF7 where the overview's label should be. `aLongRouteFollowsItsCursor` passed: it checks
where the cut is drawn, and its cost is a measurement, not an assertion. The JVM tests of
`RouteCut`, `RouteSources`, `routePlacement` and `withZoomCap` test units that did not exist before
the fixes. After the fixes: 12 of 12, three runs.

### 7.7 Re-run

```
sh tools/round/gradle.sh m :map:scene:test --rerun :map:layers:testDebugUnitTest --rerun :map:layers:assembleDebug :map:layers:assembleDebugAndroidTest :map:demo:assembleDebug
sh tools/round/emulator.sh m start
. data/r3-m/demo.sh; layers_test                       # 12 tests
sh tools/round/adb.sh m install -r data/gradle/m/build/map/demo/outputs/apk/debug/demo-debug.apk
sh data/r3-m/r-shots-route.sh day; sh data/r3-m/r-shots-route.sh night
sh data/r3-m/r-shots-overview.sh after                  # needs files/overview.pmtiles (demo README)
sh data/r3-m/r-shots-reload-fps.sh
sh tools/round/emulator.sh m stop
```
