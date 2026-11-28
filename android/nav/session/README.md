# :nav:session (track N)

The navigation session of Beamline: `BeamlineNavigationSession` implements `:core:api`'s
`NavigationSession` with every rule of `docs/architecture.md` section 3 (G, B, O, A, C, P, M, R),
the thresholds of `guidance.json`, the planner of `:routing:core` and the banner model of
`:routing:banner`. Plain Kotlin/JVM: the phone, a later Android Auto screen and the JVM tests use it
the same way. Report: `docs/experiments/13-navigation-session.md`.

| File (`src/main/kotlin/io/github/spysyweeb/beamline/nav/`) | What it is |
|---|---|
| `BeamlineNavigationSession.kt` | Contract (2.4): the session (delegates to `SessionCore`), `TripSnapshot`, `TripStore` (delegates to `TripFile`) |
| `Ports.kt`, `GuidanceRules.kt` | Contract (2.4): the follower port and the rules type; `GuidanceRules.parse` / `bundled` filled |
| `GuidanceRulesFile.kt` | Strict reader of `guidance.json` (every key, no unknown key, values that fit together) |
| `SessionCore.kt` | The state machine: intents, planning (P), fixes (G, M), banner (B), off route and re-route (O), arrival and stops (A, C), resume (R) |
| `EngineRouteService.kt` | Planning, the OSRM request, the traffic lights, the stop-order matrix and the detour matrices (`times`, C8) on `beamline-routing`; one engine per installed generation (2.2 rule 3) |
| `StopLeg.kt` | Item 51 (C1, C8), pure, also used by `:app`: where `AddStop` without an index puts a stop, the line of the leg it joins (`lineAhead`), the leg into a previewed stop, the stop it goes after, the detour's rejoin point |
| `RouteGeometry.kt` | A route's line as distances: projection, maneuver positions, point and bearing at a distance, time left |
| `GeometricFollower.kt` | The app's route follower (the only one since Ferrostar was dropped on 2026-11-17), also of the tests and the golden trace: `RouteFollower` with the 2.4 contract (entry within `entry_m`, then `exit_m` past, on plain geometry) |
| `RouteSimulator.kt`, `SimScripts.kt` | The built-in drive (`Simulation`, provider `simulated`) and its named scripts `default`, `wrongturn`, `gaps`, `jitter`, `drift`, `tunnel` |
| `TripFile.kt` | `trip.json`, format `beamline-trip/1` (stops with `PlaceSource` and `savedId`, reached flags, type, options, time; `"simulated": true` only for a simulated drive, P27) |
| `Views.kt` | `:routing:banner` banners as `BannerView`s (B1, B7), the lot turn, "At the light" and the stop's name on an arrival (`decorate`), routes as `RouteChoice`s with their steps in the same words |

`src/main/resources/io/github/spysyweeb/beamline/nav/guidance.json` is a copy of
`packbuilder/routing/guidance.json` (`python android/routing/scripts/sync_data.py --only guidance.json`;
a test fails when it differs).

## How the session works

- Every state change runs on the session's dispatcher (`beamline-nav`); engine calls run on the routing
  dispatcher with `withContext` and their answers come back to the session's thread, where an answer for
  an older revision, or a re-route the car no longer needs (O5), is dropped.
- The state changes once per fix, tick, intent, engine answer or timer (2.3: "at most once per accepted
  fix or tick, plus once per intent"). Inside such a unit (a *batch*) writes go to a pending copy and
  events wait; the unit's end publishes the state once, then its events, so a screen that reacts to
  `Arrived` already finds ARRIVED. A batch never spans a suspension.
- Start and Resume hold the pack-change hold while they wait for the engine. End, DiscardResume, PlanTo
  and `close()` cancel that wait and give the hold back; an answer that comes after `close()` is dropped.
- A Reroute (or Retry) before the first usable fix of a drive has nothing to start from: REROUTING asks
  with the first usable fix instead of waiting for an answer nobody asked for (O4). After an engine
  170/171 (O8), the next request waits for `reroute.min_interval_s`, so a car standing where no road is
  found asks once every 10 s, not once per fix.
- Positions come from the shared source only in PLANNING (waiting for a fix, P1), GUIDING, REROUTING and
  ARRIVED; a simulated drive replaces them with `RouteSimulator`'s fixes.
- A tick every `tick_s` drives the GPS state (G2), dead reckoning (G3), motion (M2), the re-route
  timers (O4, O7), the `trip.json` refresh (R1) and the hold of a change's problem (C3).
- The shown upcoming maneuver *U* is the follower's `stepIndex + 1`, frozen while a fix is off the route or
  in REROUTING (B2); the shown distance follows B3; lanes B5; announcements B8.
- Nothing in this module logs (rule PV2): no log call, no exception message built from a place or a
  point; `trip.json` names places and lives in the folder `:app` gives (`noBackupFilesDir`, PV4).
- During a trip the stops of each leg are found by place, not by index. A change during guidance
  (C3, C4) shows its stops at once, while the old route is still guided. A stop reached meanwhile
  makes the session ask the change again from there. A failed change goes back to the trip of the
  guided route, settings included.
- An arrival the follower has passed by itself (entry and exit on one fix, a gap) is reached on that
  fix, so a stop is never skipped and *U* never shows a leg's departure.
- Speed limits (batch A, rule D8): `SpeedLimits.from(route, osrm, index)` reads the OSRM answer's
  `legs[].annotation.maxspeed` once per route (one entry per segment of the flattened line; a leg
  whose count does not match has none); `Guidance.speedLimitKmh` is the entry at `traveled.index`.
  `SpeedLimitsTest`; the exit 24 drive test checks 89 -> none -> 72 -> none along the trip.
- The session never throws on its thread. Each intent, fix and tick runs in a guard that records
  only the exception's class name in `SessionCore.failures` (the tests require it empty) and goes on.
  A `trip.json` that cannot be written is skipped (`tripWriteFailures`). A follower that cannot be
  made from a route is an ENGINE problem, and the pack-change hold is given back. `close()` runs on
  the session's thread and keeps `trip.json` (R2).
- Lane warnings (batch C1, rule B9): `LaneWarnings.build(geo, osrm, index, rules, bannerRules, trace)` finds the
  intersections the route goes straight through where a lane that went on becomes turn-only (OSRM step
  intersections' lanes; the road's own lane count from one `trace_attributes` call per candidate over the 500 m
  before it). `EngineRouteService.forGuidance` returns `GuideAnswer(osrm, lanes)`; `SessionCore.laneWarningNow`
  sets `Guidance.laneWarning` from max(150 m, 18 s x speed) before until 5 m past (`guidance.json`
  `lane_warning`). `LaneWarningTest`: Peach St at Craig St, Erie warns; an added turn lane and the exit 24 trip
  do not. Recorded `trace_attributes` answers are in `session-recordings.json.gz` (`record_session.py`).
  The lane at a fork (item 37): a candidate where no lane ends still makes a spot when the road forks there
  (another plain road as important as the route's leaves within `lane_warning.fork_angle_deg` of straight ahead;
  `forkRequest`, `forkOf`: one more `trace_attributes` call): "Use the right lane for Mill Rd".
  `LaneWarningTest`: a fork where the engine gives no instruction, from recorded answers.
- Traffic lights (rule B10): `RouteLights.build(geo, signals, trace)`: the engine's lights on the route's line and
  (item 36) the installed packs' lights within 20 m of a junction of the route or 3 m of its line, on plain road,
  where the engine has none within 30 m. `signals` is the session's `signalsIn` (`SearchHub.signalsIn` in the app;
  null in the tests of the rules before, which then ask the engine for the lights alone).
- The steps preview in the banner's words (audit F40, play test P34, 0.1.40): every card names each arrival's
  stop ("Arrive on the left at Erie Zoo"; a dropped pin, and a point named by its coordinates or its street line
  also as a recent, keeps "Arrive") and the turn into a lot at once; the
  selected card's traffic lights are asked once the plan is final (CHOOSING) or a card is chosen (`askLights`,
  one engine walk per plan, never setting `replanning`), and Start reuses them (`forGuidance(known)`), waiting for
  a walk still on the routing thread rather than walking the route again. The guided
  banners say the same (`Views.decorate` in `prepare`).
- Adding a stop on the map (item 51, rule C8): `Detours(id, places)` answers `TripState.detours` (two matrices per
  10 places from J, where the stop joins, to the places and to R, the route's point 10 km past the farthest place
  or the destination; each call its own job on the routing thread); `PreviewStop(place)` answers
  `TripState.stopPreview` (one plan of the trip's type with the place inserted as C1). Neither changes the trip,
  counts as R4's next intent or sets `replanning`; a change of the stops, start, type, options or chosen route
  (also a change while guiding that fails and goes back, C3), arrival and the trip's end drop both (`dropStopWork`).
- Outside the installed maps while guided (O8, play test P5): the problem has no Retry (the session asks again
  on the first covered fix) and says so. A simulated drive is saved as one and offered again as one (P27,
  `TripState.resumeSimulated`); Resume simulates it again.

Wiring (I, `docs/architecture.md` 5.4): `BeamlineNavigationSession(positions, clock, System::currentTimeMillis,
installed.installed, installed::holdChanges, EngineOpener { ValhallaEngine.open(it) }, GeometricFollowerFactory(),
settings, TripStore(File(noBackupDir, "trip.json")), GuidanceRules.bundled(), PresetSet.bundled(),
BannerRules.bundled(), navDispatcher, routingDispatcher, doorOf, signalsIn)`.

## Tests

```
sh tools/round/gradle.sh n :nav:session:test                                   # 159 JVM tests, about 20 s
sh tools/round/gradle.sh n :nav:session:test --rerun                           # when Gradle says the tests are up to date
sh tools/round/gradle.sh n :nav:session:test -Pbeamline.writeGolden=true       # rewrites the golden trace
```

The tests run the session on a virtual clock (both dispatchers on the test's scheduler) with the
geometric follower and a **recorded-answer engine**: every route and matrix request is answered from
recordings made with pyvalhalla 3.9.1 on the PA+OH device tiles (`android/routing/testdata/reference/`
and `android/routing/testdata/nav/session-recordings.json.gz`). A request no recording holds fails the
test and is listed in `data/gradle/<key>/build/nav/session/missing-requests.json`; record it and run again
(a re-route answer can lead to new requests, so repeat until nothing is missing):

```
.venv/routing/Scripts/python android/routing/scripts/record_session.py \
    --missing data/gradle/n/build/nav/session/missing-requests.json --device data/routing/device
```

| Test class | Rules |
|---|---|
| `Exit24DriveTest` | the owner's exit 24 trip with the `default` script against the golden trace `android/routing/testdata/nav/exit24.json` and the table of section 8 |
| `FixesAndMotionTest` | G1-G6, M1-M3 |
| `BannerRulesSessionTest` | B1-B8 |
| `OffRouteTest` | O1-O9 (O4 also for a slow car: radius without heading, and a Reroute before the first fix; O8 also counts the engine calls of a car standing where no road is found) |
| `ArrivalAndStopsTest` | A1-A3, C1-C7 (with a stop reached while a change waits, a failed second change, process death and Resume keeping Shortest; C6 tells a changed order) |
| `StopDetoursTest`, `StopLegTest` | C8 (item 51): the detour matrices' points, formula, chunks, a newer or empty request, what drops them, R4, the previews before and while guiding; `StopLeg` |
| `PlanningAndResumeTest` | P1-P5, R1-R3 (R2 also with a wall clock that stepped back; a simulated drive offered and resumed as one, P27), `trip.json` with saved places |
| `SimulationScriptsTest` | the named scripts (`wrongturn`, `gaps`, `tunnel`, `jitter`, `drift`) and the OSRM step check (2 and 3 legs: one step per maneuver, same kind, length and place) |
| `RobustnessTest` | a follower that fails on one fix, a follower that cannot be made, an unknown simulation script, an unwritable `trip.json`, `close()` (also while Start or Resume waits for the engine: the hold is given back) |
| `RouteLightsTest` | B10 and item 22: lights, "At the light", the lot turn, the road driven; the steps preview in the banner's words (F40) and each arrival's stop name (P34) |
| `StopTiesTest` | how a place is tied to the road (2.11; a point with its "Near ..." street line stays a map point, P18) |
| `StateChangesTest` | 2.3: one state change for the fix that confirms off route, for the arrival fix (the `Arrived` event after it) and for the Reroute intent, seen by an unconfined collector |
| `GuidanceRulesTest` | `guidance.json` values, refusals, the copy equal to its source |
| `GeometricFollowerTest` | the test follower against the 2.4 contract (deciding fixes only, entry then exit, never back, `advance`, `complete`) |

The golden trace (format `beamline-golden-trace/1`) holds the inputs (route recording, simulation) and,
per fix, the phase, *U*, the shown distance, the chip, the lane row, GPS state, motion and the vehicle
point: a later Python port of the session checks against it (track A's Ferrostar follower did, until 2026-11-17).
