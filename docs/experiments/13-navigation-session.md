# 13. Navigation: presets format 2, the banner's road names and the navigation session (track N)

Date: 2026-11-16 (night, the owner asleep; built unattended). Contract: `docs/architecture.md` 2.3,
2.4, 2.11, section 3, 5.1 "N: navigation"; `docs/architecture-saved-places.md` 6.1 (N) and PV2.
Built in two stages by the track's two engineers. The first finished stage 1 and built a first
version of all of stage 2. The second re-ran stage 1's checks, reviewed the session against
section 3 rule by rule, fixed what the review found (section 3.2), added the tests for it, and
finished the track. Two reviewers then reproduced and read the track; a third engineer checked
their findings against the code and fixed the ones that were right (section 8, "Review and
fixes"). Scratch and the running log: `data/r3-n/PROGRESS.md`.

## 1. What exists now

| Part | Where | State |
|---|---|---|
| Presets format 2 (route types + options, `compose`) | `packbuilder/routing/presets.json`, `route_lib.py`; `:routing:core` `Presets.kt` | done, tested both sides |
| Avoid tolls as `exclude_tolls`, the no-route fallback for every type and its warning, `option_fallback_when: false` on Shortest, the pool fallback | `route_lib.run_preset`, `RoutePlanner`, `RouteWarning.OptionFallback` | done |
| Reference recordings of format 2 (336 plans + 11 runs of the 2.11 cases) and the old 126 plans kept | `android/routing/testdata/reference/` | done; the Kotlin planner equals Python on all of them |
| `banner.json` `road_abbreviations`, `distance_text` removed; `:routing:banner` following | `packbuilder/routing/banner.json`, `BannerRules.kt`, `Banner.kt` | done |
| `GuidanceRules.parse` / `bundled` | `:nav:session` `GuidanceRulesFile.kt` | done |
| `BeamlineNavigationSession` with every rule group of section 3 | `:nav:session` `SessionCore.kt` and helpers | done on the JVM (88 tests) |
| Test follower (2.4 contract), `RouteSimulator` and the six named scripts, `TripStore`, `EngineRouteService` | `:nav:session` | done |
| Golden trace of the exit 24 drive | `android/routing/testdata/nav/exit24.json`; board note `data/r3-board/n-golden-trace.md` | done (unchanged by stage 2) |
| OSRM step *k* = Valhalla maneuver *k* | `SimulationScriptsTest` | done: 2 legs and 3 legs, kind, length and place of every step |
| `:routing:demo` `DeviceRoutingTest` with format 2 on the emulator | `android/routing/demo/src/androidTest/` | passes (section 3.5) |
| `check_private_logs.py scan` on N's modules | S's script | 0 breaches (section 5) |

## 2. Presets format 2 (stage 1)

`presets.json` is now `beamline-route-presets/2` exactly as 2.11 quotes it, with the format-1 tuning
notes carried over unchanged (a `format_2` note added where format 2 changed the meaning: Shortest,
Eco, Avoid tolls). The format-1 file is kept as `android/routing/testdata/reference/presets-format1.json`:
the old 126 plans were recorded with it.

`compose(type, options)` (the same in `route_lib.py` and `PresetSet.compose`) returns the format-1
shape the planner runs: each request of the type (each pool candidate) gets the type's costing
options, then the candidate's, then every selected option's on top, options in file order whatever
order is asked; the fallback takes each option's `fallback_costing_options` instead;
`fallback_when` maps are united (the smaller limit wins) except for a type with
`option_fallback_when: false`. A pool type with options gets `fallback_candidates` (the pool with the
fallback options). The composed preset also names its `optionIds` and the roads they avoid
(`avoids`, from the options' hard exclusions), which only the warning uses. Load refuses: an unknown
or misspelt Valhalla option, two options that set the same key, a default type that is not offered,
`request_options`, any composition Valhalla 3.9.1 would silently ignore.

The planner (`RoutePlanner.plan(request, preset)`; `plan(request)` resolves `presetId` with
`PresetSet.resolve`, which also takes the format-1 ids `avoid_highways`, `avoid_tolls` as Fastest with
that option) runs format 1's rules plus: the fallback **when the request finds no route, for every
type**, with `RouteWarning.OptionFallback` ("No route to this place avoids toll roads. This route uses
them."; "highways"; both: "highways and toll roads"; the roads named are those the route really uses);
the fallback_when re-run skipped for Shortest; and for a pool, the whole pool again with the fallback
options when every candidate failed or the picked route exceeds a limit (the route with less of the
flagged road wins, ties the first). `StopOrderer.bestOrder(points, keepLast, preset)` asks the fallback
options' matrix when the options' matrix connects no order (rule C6).

### 2.1 Reference and equality

`reference_routes.py --only plans` (pyvalhalla 3.9.1, `data/routing/device`, graph build
261114-pa-oh-v3.9.1-b53087) wrote `reference/plans.json` (21 trips x 4 types x 4 option sets, and
the 2.11 cases) and `reference/recordings-plans.json.gz` (the 351 requests the format-1 recordings did
not already hold; 4,066,826 bytes; 519 requests in all). `--only trips matrix` run with the format-1
file reproduced `reference/trips.json` and `recordings.json.gz` **byte for byte** (`cmp`), so the old
126 plans stay the old reference.

Kotlin against Python (`:routing:core`, replay engine that fails on any request the reference did not
make): all 336 format-2 plans and the 11 case runs agree on time (0.05 s), length (0.6 m), maneuvers,
legs, highway and toll km, `has_toll`, `has_highway`, end gaps, fallback used, no-route fallback, pool
size and the warning text; the old 126 plans, their alternates and the four motorway-origin trips agree
with the format-1 presets as before; `compose(id, {})` equals format 1's preset of the same id and
`compose("fastest", {"avoid_highways"})` equals format 1's `avoid_highways` (`Preset.sameRun`;
Python `same_run`).

Measured on the recordings (pyvalhalla; the numbers 2.11 quotes):

| Case | Result |
|---|---|
| Carlisle (40.2, -77.19) to King of Prussia (40.09, -75.38), Shortest | 169.629 km with tolls; with Avoid tolls 170.258 km, `has_toll` false, no fallback |
| Same, trips.json points (trip T2), Shortest | 167.512 km with tolls; with Avoid tolls 168.560 km, `has_toll` false |
| Carlisle to the Turnpike point (40.149533, -75.844894), Avoid tolls | Fastest 141.478 km, Shortest 123.696 km, both by the no-route fallback with the warning (Fastest without the option 126.334 km) |
| Erie (42.1292, -80.0851) to the I-90 point (42.032716, -80.125726), Avoid highways | Fastest and Shortest 12.970 km by the fallback with "No route to this place avoids highways. This route uses them." (Fastest without the option 14.252 km) |
| Turnpike start (40.229690, -76.350721) to King of Prussia, Avoid tolls | 93.944 km with tolls, the same as without the option, no fallback, no warning |
| The 21 trips | no fallback of either kind in any of the 336 plans |

## 3. The navigation session

### 3.1 Shape

`BeamlineNavigationSession`'s stubs delegate to `SessionCore`. The contract fixes the constructor,
whose parameters are not properties, so the delegate is created by one line written where the first
stub was; `check_contracts.py` accepts it as a filled stub, and the public API is unchanged.
`TripStore` delegates to `TripFile` the same way. Every state change runs on the session's dispatcher;
engine calls go to the routing dispatcher and come back; answers for an older revision are dropped.

- **Follower.** `GeometricFollower` is the test follower with the 2.4 contract, on plain geometry:
  Ferrostar's DistanceEntryAndSnappedExit. Entry is a deciding fix within `advance.entry_m` of the
  maneuver point in a straight line; exit is a deciding fix snapped `advance.exit_m` past it; the same
  fix may do both. The step never goes back. Its search window follows only fixes near the line.
- **Simulation.** `RouteSimulator` drives the guided route. It reads the route again every step, so
  after a re-route the car follows the new one. Each stretch is driven at its own speed, with fixes at
  `fixHz`, seeded normal sideways noise and the `SimEvent`s by odometer; positions have 6 decimals.
  `SimScripts` builds `default`, `wrongturn`, `gaps`, `jitter`, `drift` and `tunnel` from the route by
  maneuver index (section 8).
- **trip.json** (`beamline-trip/1`): stops with every `Place` field (`source` SAVED and `savedId`
  included), reached flags, type, options, `saved_epoch_ms`; atomic write; a broken or foreign file is
  no trip; nothing logged. The file is where `:app` says (`TripStore(File(noBackupDir, "trip.json"))`).
- **Engine holder** (`EngineRouteService`): one engine per installed generation, opened on first use
  on the routing thread, the old one closed before the new opens; closed at once in IDLE on a new
  generation and when the routing set disappears. `plan` never throws: anything the planner did not
  turn into a failed plan becomes an ENGINE failure.
- **One state change per event** (review fixes). 2.3 says the state "changes at most once per
  accepted fix or tick, plus once per intent". Each intent, fix, tick, engine answer and timer is one
  batch: inside it, writes go to a pending copy and events wait; the batch's end publishes the state
  once, then the events. A batch never spans a suspension.
- **Robustness** (stage 2). An exception on the session's thread would end the app in the middle of
  a drive. Each intent, fix and tick therefore runs in a guard that records only the exception's class
  name (`SessionCore.failures`) and carries on with the next one; the scope's handler catches the
  rest. The tests require `failures` to be empty. Expected failures are handled where they happen:
  - A `trip.json` that cannot be written (a full disk) is skipped (`tripWriteFailures`).
  - A follower that cannot be made from a route is an ENGINE problem, and Start gives the pack-change
    hold back.
  - `close()` runs on the session's thread: collection, timers, engine work, the hold and the
    follower, then the engine on the routing thread. `trip.json` stays, for R2.
- **Logging (PV2).** No log call in `:nav:session`, `:routing:core`, `:routing:banner` or
  `:routing:valhalla` main sources; no exception message built from a place or a point.

### 3.2 Stage 2 review: what was wrong and what changed

Stage 2 read `SessionCore.kt` against every rule of section 3 and 2.2 rule 3/4. The first engineer's
69 tests all passed (run once with `--rerun`: without it Gradle reported every task up to date and ran
nothing). The review still found the following; each is fixed and has a test.

| # | Found | Rule | Fix | Test |
|---|---|---|---|---|
| 1 | Any exception in a fix, a tick or an intent (a follower's, `trip.json` on a full disk) would leave the session's thread and end the app; `close()` changed the session's state from the caller's thread | 1.2, robustness | guard plus scope handler, `failures`, `tripWriteFailures`, follower failure = ENGINE, `close()` on the session thread | `RobustnessTest` (5 tests) |
| 2 | The re-route origin had `radius` 60 only when it had a heading | O4 ("radiusM = origin_radius_m and headingDeg = course when speed ≥ 2 m/s") | `carStop`: radius always, heading only at 2 m/s or more (fix speed, else M1), for re-routes and changes from the car (C3, C4) | `O4 a slow car's origin keeps the radius, without a heading` |
| 3 | During a change that waits for its route, leg stops were found by index into the new stop list: a reorder marked the wrong stop; a stop reached meanwhile was reached again when the answer (still routing to it) came; a second change's failure "undid" to the first, unconfirmed change; the settings undo captured the settings after they had been written, so it restored nothing | C3, C4, A2 | stops found by place (`Guided.legPlaces`); a stop reached while a change waits asks the change again from there; the undo goes back to the trip the guided route was planned for, keeps reached flags, restores settings | `C3 a stop reached while a change waits ...`, `C3 a failed change goes back to the trip of the guided route, also after a second change, settings too` |
| 4 | A follower that passed an intermediate stop by itself (entry and exit on one fix, after 2 s without fixes) left the stop never reached and *U* on the next leg's departure | A1, A2, B1 | arrivals the follower has passed are reached on that fix (once per leg) | `A2 a stop the follower passed on one fix ...` |
| 5 | `revision` did not change when a re-route replaced the route or a stop was reached; a best-order answer that arrived after such a change left `replanning` true | 2.3 `TripState.revision`, C6 | +1 on O6, A2, arrival; a stale best-order answer sets `replanning` from what still runs | asserts in O6 and A2 tests |
| 6 | `SetRouteType` for a type no card holds set the trip's type anyway (P4/C7: the trip's type is the selected card's); Resume of a trip.json with an unknown type kept that type | P4, C7, R3 | the trip's type stays the selected card's (settings still written); Resume sets the type it planned with | `C7 SetRouteType selects the card holding the type ...` |
| 7 | A simulated Start with an unknown script name failed after guidance had begun (no fixes, no ticks) | P5, 2.3 `Simulation.scriptName` | refused before the hold is taken (choice 24) | `a simulated drive with an unknown script name is refused ...` |

Also strengthened: the C7 test now ends the session without End (process death), starts a second
session on the same folder a minute later, resumes, and checks the resumed request is Shortest. The
OSRM check now runs on a 3-leg trip as well as a 2-leg one, and also compares each step's maneuver
location with the Valhalla maneuver's point (within 1 m).

### 3.3 Tests (JVM, virtual clock, recorded-answer engine)

Both dispatchers run on the test's scheduler. The engine answers from recordings: the format-1 and
format-2 references, the banner trips, the exit 24 OSRM answer and
`testdata/nav/session-recordings.json.gz` (74 answers, 76,760 bytes, recorded by `record_session.py`).
A request no recording holds fails the test and is listed for `record_session.py`. The loop is: run,
record, run, until nothing is missing; the simulated drives are deterministic. The follower is
`GeometricFollower`, except where a test wraps it.

| Group | Tests (rule id in each name) |
|---|---|
| exit 24 | the clean run with `default` against the golden trace (289 rows, row by row) and section 8's table |
| G | G1 (network, stale, out of order, poor and unknown accuracy dropped), G2, G3 (x2), G4, G5, G6 |
| B | B1, B2 (x2: entry/exit, catch-up over two turns after a gap; no advance while suspected), B3, B4, B5, B6, B7, B8 |
| O | O1, O2, O3, O4 (x3: origin, radius, heading, stops, the 10 s interval; a slow car; a Reroute before the first fix), O5 (a gated answer dropped), O6, O7 (retries to 5, then Retry), O8 (x3: outside tiles; an engine 171; the engine calls of a car standing where no road is found), O9 |
| A, C | A1 (x3: within 30 m; a stop passed in a gap; a follower that reports complete), A2 (x2: within 30 m; passed by the follower on one fix), A3 (x2), C1, C2, C3 (x4: swap; failure undone; a stop reached while waiting; a second change failing), C4, C5, C6, C7 (x2: card type, re-route, trip.json, process death and Resume; SetRouteType) |
| P, R | P1 (x2), P2, P3, P4, P5 (x2), R1 (x2), R2 (x2: the 45-minute trip killed at minute 40: offered 10 min later, deleted 31 min later; a wall clock that stepped back 2 s), R3 |
| M | M1, M2, M3 (the brake lenses' inputs: STOPPED with a live fix, UNKNOWN in LOST) |
| scripts | the scripts' events; `wrongturn`, `gaps`, `tunnel`, `jitter`, `drift` each with what section 8 says it must show |
| OSRM steps | 2 legs and 3 legs: one step per maneuver, same kind, length and place within 1 m: no mapping needed |
| robustness | a follower failing on one fix; a follower that cannot be made; an unknown simulation script; an unwritable `trip.json`; `close()`; `close()` while Start (P5) or Resume (R3) waits for the engine |
| state changes (2.3) | `StateChangesTest`: the fix that confirms off route (O3), the arrival fix (A3, with the `Arrived` event after the state) and the Reroute intent (O4) each change the state once, seen by an unconfined collector |
| follower, rules | `GeometricFollowerTest` (3: the 2.4 contract), `GuidanceRulesTest` (3) |
| saved places | `trip.json` round trip with a `SAVED` stop and its `savedId`; the snapshot's text holds no name or point |

Run: `sh tools/round/gradle.sh n :nav:session:test --rerun`: **88 tests, 0 failures, 0 skipped**
(80 before the review fixes). All 44 rule ids of section 3 appear in a test name, checked with a
script over the JUnit XML (count per id: O4 and C3 four; O8, A1, A3 and P5 three; G3, B2, O3, A2,
C7, P1, R1, R2 and R3 two; the others one).

### 3.4 Measurements (virtual time, from the tests; not phone numbers)

- Exit 24 clean run (`default`), from the golden trace: 289 fixes. *U* goes from 1 to 2 at the fix of
  t = 171 s (shown 398.76 m), to 3 at 222 s and to 4 at 251 s. Between those, the 20 s halt stops
  15.02 m before the left turn, STOPPED from its second fix (t = 202 s). ARRIVED at 289 s, 29.75 m
  before the end. The first banner is "Exit 24" at 4167.74 m (the route's own maneuver length is
  4172.8 m: geometry against Valhalla's length), with the chip "Turn left" at 421 m. Lanes show at
  once on maneuvers 2 and 3. GPS is GOOD and nothing is suspected throughout.
- `jitter` (8 m noise, seed 1): *U* advanced at 4189.0, 4599.4 and 4955.7 m along the route, against
  4189.0, 4598.4 and 4954.8 m in the clean run (within 1 m; section 8 asks for 15 m).
- `wrongturn`: REROUTING on the fifth off fix; 4 re-routes before the car is back on a route (the
  script's detour is a straight 400 m line opposite the route's bearing after the left turn, and the
  simulated driver ignores the new routes until it ends); ARRIVED after 317 s.
- O8 after an engine 171, the car standing 80 m off I-90 for 30 s with a fix every second: 30 engine
  requests before the review fix (one per fix), 3 after it (one per `reroute.min_interval_s`).

### 3.5 On the emulator

`:routing:demo` `DeviceRoutingTest` on `beamline_n` (emulator-5562, the round's Android 36 x86_64 AVD;
valhalla-mobile 0.6.4), with `data/routing/device/routing.tar` (the PA+OH tiles the references were
recorded on) and the PA-only tar pushed:

```
sh tools/round/gradle.sh n :routing:demo:assembleDebug :routing:demo:assembleDebugAndroidTest
sh tools/round/emulator.sh n start
. tools/round/env.sh n
python android/routing/scripts/device.py --build-root data/gradle/n/build --out data/r3-n/device install --only demo demo-test
python android/routing/scripts/device.py --build-root data/gradle/n/build --out data/r3-n/device push \
    --tiles data/routing/device/routing.tar --pa-only data/routing-packs-test/pa-only/routing.tar
python android/routing/scripts/device.py --build-root data/gradle/n/build --out data/r3-n/device test \
    --class io.github.spysyweeb.beamline.routing.demo.DeviceRoutingTest
sh tools/round/emulator.sh n stop
```

Stage 1's run: `OK (8 tests)` in 104.6 s (`data/r3-n/device/logs/`). The new `format2PlansMatchPyvalhalla`
planned all 347 format-2 runs (336 + the 11 case runs) on the device with 0 differences from
pyvalhalla (`results/plans-device.json`: compared 347, mismatches 0). It compared time, length,
maneuvers, legs, highway and toll km, `has_toll`, fallback used and the warning text; the four
warnings came out word for word. Plan time on the shared emulator: median 95.9 ms, slowest 1,029 ms
(indicative only: a shared emulator, not the phone). The old 126 plans (format-1 presets), their
alternates, the motorway-origin trips, the config keys, the errors (with the PA-only probes), the
exit 24 OSRM answer and the banner trips passed as before. Results are in `data/r3-n/device/results/`
(not committed).

Stage 2's rerun (03:14, APKs built at 03:11 by the final Gradle run; the tile tars already on the
emulator had the same SHA-256 as `data/routing/device/routing.tar` and the PA-only tar, so they were not
pushed again): `OK (8 tests)`, `Time: 97.216` s (`data/r3-n/device/logs/instrument-1794823989.txt`).
`results/plans-device.json`: compared 347, mismatches 0; plan time on the shared emulator median
87.3 ms, slowest 1,351.6 ms (indicative only). Stage 2 changed nothing that `:routing:demo` builds:
`:routing:core`, `:routing:banner`, `:routing:valhalla` and `:routing:demo` are as stage 1 left them.
The emulator was stopped afterwards and its slot given back.

## 4. Choices made where the contract left a gap

Stage 1:

1. **Format-1 file kept for the old 126 plans** (`reference/presets-format1.json`,
   `PresetSet.parseFormat1`, `route_lib.load_presets_format1`); `route_lib.load_presets()` now returns
   the six format-1 combinations composed from format 2, so `route_eval.py` and `test_routing.py` keep
   their ids (Avoid tolls there is now `exclude_tolls`).
2. **Warning text** names the avoided roads the fallback route uses; when it uses none of them (not
   seen on these tiles) only the first sentence. One text per route, not per option.
3. **A pool's alternates after its fallback** come from the fallback pool's first request (the format-1
   rule had no pool fallback; alternates are not offered this round, decision 6.6).
4. **`banner.json` keeps `beamline-guidance-banner/1`**: 2.11's list of new formats has no banner/2 and
   the only reader changes with the file; the parser refuses a file that still has `distance_text`.
   Abbreviations apply to the last word of each road name of the main instruction only when another word
   comes before it, exact words (case included); `towards` are not abbreviated. `:routing:banner`'s
   tests check the chip texts with a test-only copy of `units.json`'s imperial table (the module may not
   depend on `:core:units`).
5. **`GuidanceRules` is strict**: every key, no unknown key, whole numbers where the type is `Int`, and
   rules that tie values together (weak before lost, deciding no worse than usable, `max_stops` 1..9 so
   the origin and the stops fit `StopOrderer`'s 10 points, the network provider never guiding).
6. **A planning origin with a heading also gets `reroute.origin_radius_m`** (60 m), for P1 and for
   `Origin.FromPlace`: it is what the recorded exit 24 request has, and heading alone does not keep loki
   on the carriageway (09). Request coordinates are rounded to 6 decimals (0.11 m).
7. **Intents outside their phases**: PlanTo is ignored in GUIDING and REROUTING (the screens End first,
   S7). It acts as Done then PlanTo in ARRIVED, and as DiscardResume then PlanTo in RESUME_OFFERED.
   SetOrigin works only in PLANNING and CHOOSING. SetRouteType before guidance selects the card holding
   that type (every offered type is already planned) and writes settings. Removing the last stop not
   reached is ignored during guidance and ends the trip before it.
8. **O5 rejoin** applies to re-routes the session started (O3, O9), not to the driver's Reroute button.
   The button, Retry and O9 skip `min_interval_s`. An engine 170/171 on a re-route is O8 (outside, no
   attempt counted). (Review fixes: O8's covered fix no longer skips `min_interval_s`; choice 26.)
9. **Banner while off the route**: *U* and the shown distance freeze while a fix is suspected and in
   REROUTING (B2, D7). Dead reckoning never passes *U* and floors the distance at 0.
10. **Announce events**: PREPARE carries the announce distance it crossed. When *U* begins already
    inside several distances, only the nearest is announced. NOW needs a speed above
    `motion.moving_above_mps`.
11. **C5** shows `PACKS_CHANGED` ("The installed states changed. Planning again.") while re-planning;
    no routing set at all: `OUTSIDE_INSTALLED_MAPS` "No state with routes is installed. Add a state.".
12. **C6**: an engine failure of the matrix is treated as no connected order (NO_ROUTE, "No order of
    these stops connects them all.").
13. **Remaining distance and times** come from the route's geometry and maneuver times, not from the
    follower, so a dead-reckoned or held position gives consistent numbers with either follower.
14. **Scripts**: placed by maneuver index and odometer so they apply to any route; `jitter`'s 8 m is the
    standard deviation of the noise; `wrongturn` is a straight 400 m line at 12 m/s.
15. **`device.py`** now refuses to run without `ANDROID_SERIAL` and addresses only that device. It takes
    `--build-root` (APKs of a `tools/round/gradle.sh` build) and `--out`.
16. **Owner question 5 (6.38)**: built the recommended answer: a place on a toll road or motorway is
    reached with the warning, not "No route".

Stage 2:

17. **The O4 origin** (re-routes and changes from the car) always carries `radius` 60, and a heading
    only at 2 m/s or more, as O4 says; the P1 and `FromPlace` origins keep choice 6 (radius only with a
    heading), because P1 names no radius and the recorded exit 24 request has one with its heading.
18. **A stop reached while a change waits for its route** makes the session ask the change again from
    where the car is, with the stops not reached. The old answer would route back to the stop just
    reached. A pending best-order answer is dropped in that case: the driver can ask again.
19. **The undo of a failed change** restores what the guided route was planned for, not an earlier
    change that also never got a route. Settings go back too when the type or options changed. Stops
    reached meanwhile stay reached.
20. **`TripState.revision`** also grows when a re-route's route arrives (O6) and when a stop is reached
    or the destination arrived ("+1 on every change of stops ... or route"). A best-order answer that
    comes after such a change is dropped.
21. **Unexpected exceptions are survived, not raised**: the session keeps guiding, and the class name
    goes to `failures`, which the tests require empty. Nothing is logged (PV2: `:nav:session` has no
    log call). I wires `failures` to nothing; it exists for tests and a later debug screen.
22. **A follower that cannot be made from a route** (for example Ferrostar refusing an OSRM answer) is
    an ENGINE problem with Retry at Start, a failed attempt on a re-route (O7), and an undone change on
    C3/C4.
23. **SetRouteType with a type no card holds** writes settings but leaves the trip's type and the
    selection on the selected card (P4, C7). While planning is still running, the type is kept until
    the last answer, which settles it as before.
24. **`Start(Simulation(scriptName = ...))` with a name the session does not know** (a typo in I's
    test extra `--es simulate`) is refused before the hold is taken, with "There is no simulated
    drive with that name." (kind ENGINE, no Retry). Before, it failed after guidance had begun and
    left a trip with no fixes.

Review fixes (section 8):

25. **A Reroute or Retry before the first usable fix** of a drive puts the session in REROUTING and
    asks the engine with the first usable fix (as a driver's request: no `min_interval_s`), instead of
    leaving the phase alone. The driver asked for a new route, and dropping the press without a word
    would be worse; until the fix comes nothing runs, `replanning` stays false and `gps` shows WAITING.
    No new problem text: 2.3 has none that fits ("No GPS position yet. Choose a start point ..." is a
    planning text).
26. **O8 re-asks keep O4's `min_interval_s`.** After the engine answered 170/171 the car's point is
    covered, so "asked again as soon as a deciding fix is covered" would mean one engine call per fix,
    with no attempt counted and no end. O4 ("not asked within `min_interval_s` of the previous re-route
    request") now binds the re-ask too: at most one request per 10 s while the car stays. Leaving the
    installed states and coming back is still asked at the first covered fix unless an engine request
    ran in the last 10 s. No distance gate: guidance.json has no number for it, and 2.4 fixes the
    fields of `GuidanceRules`.
27. **R2 offers a snapshot whose age is negative** (the wall clock stepped back after the last write,
    for example a network time correction), as the rule's inequality says; only an age above
    `resume.max_age_s` deletes the file.
28. **2.3 "at most once per fix or tick, plus once per intent"** is kept by batching (section 3.1):
    events are emitted after the batch's state, so a screen that reacts to `Arrived` or `StopReached`
    finds the state that holds it. Engine answers and timers (the coalesced plan, the problem hold) are
    units of their own; 2.3 does not count them, and each changes the state once. The cleanup of a
    plan's wait for a fix happens in the change that ended the wait (`schedulePlan`), not in a later
    task.
29. **Start and Resume keep their hold in a job** (`startJob`) that End, DiscardResume, PlanTo and
    `close()` cancel; the hold is given back in a `finally`, and an answer that arrives after `close()`
    (already on its way to the session's thread) sees `closed` and gives it back too.
30. **`reference_routes.py` writes timings only to the file `--timings` names.** Its default used to be
    `data/routing-reference/timings.json`, an output of an earlier run that rule 7 makes read-only to a
    track; a run without the flag now writes none and says so.
31. **`:routing:demo` is outside PV2**: `check_private_logs.py scan --module routing/demo` reports 4
    breaches (timings and counts of the demo trip printed without `// log-ok:`, and an exception's
    `toString` in the measure path). PV2 binds shipped modules; 1.1 ships "everything above except the
    demos" and the scan's own default leaves demos out. Left as it is, so that the device evidence of
    section 3.5 stays the build it was taken with; a lead may want them marked.

## 5. How it is proved (commands and results)

Every line below was run again after the review fixes (2026-11-16, 03:30 to 03:46) unless it says
otherwise.

| Command | Result |
|---|---|
| `.venv/routing/Scripts/python -m unittest packbuilder/routing/test_presets.py` | 14 tests OK |
| `.venv/routing/Scripts/python data/r3-n/run_step4.py` (calls `packbuilder/routing/test_routing.py`'s `test_step4_*` functions; its `main()` would also write `data/routing/results/test_findings.json` and delete `data/routing/device_test/fakes`, outputs of other runs) | 13 of 13 PASS, including `presets_file_is_valid`, `avoid_tolls_on_turnpike_trips`, `shortest_is_never_longer`, `avoid_highways_from_a_motorway_origin` (they cover `route_lib.load_presets()`); `test_findings.json` untouched (still dated 2026-11-15) |
| `sh tools/round/gradle.sh n :routing:core:test --rerun :routing:banner:test --rerun :nav:session:test --rerun :routing:valhalla:testDebugUnitTest --rerun :routing:packs:compileKotlin :routing:demo:assembleDebug :routing:demo:assembleDebugAndroidTest` | BUILD SUCCESSFUL (final run 03:44, after the last change; log `data/r3-n/review-fix-final.log`); `:routing:packs` (P's module, which compiles `:routing:core`) compiles; the demo and test APKs are up to date (nothing they build changed) |
| `python data/r3-n/junit_summary.py` (scratch helper: sums the JUnit XML) | `:routing:core` 43 tests, `:routing:banner` 15, `:nav:session` 88, `:routing:valhalla` 2; 0 failures, 0 errors, 0 skipped; no `missing-requests.json` written |
| The same new tests run on the code before the fixes (`--tests` on the four classes; `data/r3-n/fix-prefix-newtests.log`) | 8 of the 8 new tests fail, each on its finding: "asked with the first usable fix" false; "asked again 30 times in 30 s"; R2 IDLE instead of RESUME_OFFERED; released 0 instead of 1 (Start and Resume); states seen for one fix `[(REROUTING, false), (REROUTING, true), (REROUTING, true)]`, `[ARRIVED, ARRIVED]`, for the intent `[(REROUTING, false), (REROUTING, true)]` |
| `python tools/check_contracts.py` | all the same (39 contract files, 20 excerpts, 5 data files); 39 frozen files unchanged; `Ports.kt` same, `GuidanceRules.kt` and `BeamlineNavigationSession.kt` filled |
| `python android/routing/scripts/sync_data.py --check` | no copy differs |
| `python android/scripts/check_private_logs.py scan --module nav/session --module routing/core --module routing/banner --module routing/valhalla` | 0 breaches (`--module routing/demo` added: 4 breaches in the demo, which PV2 does not bind; choice 31) |
| `python android/scripts/check_network_rule.py scan` and `privacy` (G's, `data/r3-board/g-network-rule.md`) | scan: 0 findings (the `:core:search-bench` finding stage 2 saw is gone); privacy: 0 private IPv4 literals in tracked files |
| `.venv/routing/Scripts/python android/routing/scripts/record_session.py --missing data/gradle/n/build/nav/session/missing-requests.json --device data/routing/device` | run twice for the review fixes' tests: 2, then 2 answers added (78 in all, 85,433 bytes); afterwards no request is missing |
| `.venv/routing/Scripts/python android/routing/scripts/reference_routes.py --help` (after the `--timings` change) | the script loads; `--timings` has no default |
| Stage 1 only: `reference_routes.py --device data/routing/device --only trips matrix` and `--only plans` (`--timings data/r3-n/reference-timings.json`) | old reference rewritten byte for byte; `plans.json` and `recordings-plans.json.gz` written (28 s) |
| Stage 2 only: `DeviceRoutingTest` on `beamline_n` (section 3.5): `device.py ... install --only demo demo-test`, then `test --class io.github.spysyweeb.beamline.routing.demo.DeviceRoutingTest` | `OK (8 tests)` in 97.2 s; format 2: 347 runs compared, 0 mismatches. Not rerun after the review fixes: they changed nothing `:routing:demo` builds (the reproduce review reran it: OK, 8 tests, 347 compared, 0 mismatches) |
| `sh tools/round/emulator.sh n status` | `emulator-5562 (beamline_n): not running`, no slot of track n |
| `sha256sum android/routing/testdata/nav/exit24.json` | `99e1de41...` and file time 02:07: the golden trace was not rewritten; `Exit24DriveTest` compares equal |

## 5a. What is not done or not verified

- `:nav:ferrostar` (A) compiles `:nav:session`. No N engineer compiled it: it is another track's
  module, and the round's rules allow N to build only its own (the reproduce review did, and it
  built). Neither stage 2 nor the review fixes changed a public signature of `:nav:session`:
  `Ports.kt` and `GuidanceRules.kt` are as stage 1 left them, and `SessionCore` is internal.
- The session has run only on the JVM with the geometric follower. It has not run with Ferrostar, on a
  device, or inside `:app` (I wires it). A's device test compares Ferrostar's advances with the golden
  trace.
- `:routing:ferrostarcheck`, `MeasureTest` and `PackInstallOnDeviceTest` were not run this round.
- No screenshots: nothing of this track draws, and N's definition of done names none.
- Stage 1's first `reference_routes.py --only trips` run wrote its timings to the script's default
  `data/routing-reference/timings.json`, an earlier run's output outside `data/r3-n/` (rule 7). The
  script merges into an existing file, so that run replaced the file's `python_ms` values (6 format-1
  presets x 21 trips) and `wall_s` (now 23.1); the file holds nothing else now. The values it replaced
  cannot be restored (git-ignored, no copy) and are timing noise by design (report 09, R5). The script
  now writes timings only where `--timings` says (choice 30).
- The session's 2.3 batching is proved for three units (off-route confirmation, arrival, Reroute) and
  holds by construction for the others (every intent, fix and tick runs in `guarded`, every engine
  answer and timer in `batch`); no test counts the changes of every unit of a whole drive.
- The Reroute-before-the-first-fix wait has no time limit: with no fix ever, REROUTING shows
  "Finding a new route..." (D7) until a fix comes, End, or Retry. A NO_POSITION-like text for it
  would be a new contract text (choice 25).
- The races of section 3.2 item 3 were tested with a held routing dispatcher, not with real engine
  timing. The OSRM call took 20 to 130 ms on the emulator (decision 6.15), and a plan's median was
  87 ms on the shared emulator (section 3.5), so these windows are short and the cases will be rare.

## 6. Open questions

- Owner question 5 (6.38), as built: confirm routing to a toll-road or motorway destination with the
  warning.
- For the lead: should `banner.json` become `beamline-guidance-banner/2`? A reader of format 1 would
  refuse the new file, because `distance_text` is gone. Kept at 1 (choice 4).
- The `wrongturn` script's straight-line detour makes several re-routes in simulation; a detour along
  real roads would need a recorded path (the E2E on the emulator will show what the phone does).
- For I: `SessionCore.failures` and `tripWriteFailures` are internal counters. If a debug screen ever
  wants them, they need a public view; nothing logs them (PV2).
- For the lead: should a Reroute pressed with no fix at all show a problem after
  `planning.wait_for_fix_s` (choice 25)? 2.3 and D2 have no text for it today.
- For the lead: `:routing:demo`'s four log lines (choice 31) are outside PV2 as written; mark them
  if demos should follow it too.

## 7. Hand-over

The track is complete as far as the JVM and the emulator's routing regression can prove it. For the
next engineer (review, then I):

1. Section 3.2 lists every behaviour stage 2 changed, section 8 what the review fixes changed;
   section 4 lists every judgement call.
2. If the session's behaviour changes, rerun with `-Pbeamline.writeGolden=true`, look at the diff of
   `android/routing/testdata/nav/exit24.json`, and update `data/r3-board/n-golden-trace.md` (A compares
   Ferrostar against it). Neither stage 2's fixes nor the review fixes changed the clean run (the
   comparison passes unchanged).
3. New tests that ask the engine something new: the `record_session.py` loop (`android/nav/session/README.md`).
4. I's wiring is in `android/nav/session/README.md` (the constructor call of 5.4 item 2).

Scratch: `data/r3-n/` (`PROGRESS.md`, logs, device results, reference timings, `junit_summary.py`,
`run_step4.py`).

## 8. Review and fixes

Two reviews read the finished track: one reproduced every check (verdict sound, two minor points),
one read it against the contract without running anything (verdict sound with fixes: one major,
five minor). The engineer who finished the track after them checked each finding against the code
first, wrote a test for it where a test could catch it, ran the new tests on the unfixed code (all
8 failed, each on its finding: section 5), then fixed the code and ran everything again.

| # | Review | Finding | Checked against the code | Outcome | Test |
|---|---|---|---|---|---|
| 1 | contract, **major** | A driver's Reroute before the first usable fix leaves the session in REROUTING for ever | Right. `requestReroute` set REROUTING, then returned at `lastDeciding ?: lastUsable ?: return`; no later fix or tick asked again (O5 needs `rejoinAllowed`, the tick needs `rerouteAt`, O8 needs `outsideWaiting`). Reproduced: the request never came | **Fixed**: with no fix, REROUTING waits for the first usable fix (`fixWaiting`) and asks with it (choice 25) | `O4 a Reroute before the first fix is asked with the first usable fix - REROUTING never waits for nothing` |
| 2 | contract, minor | O8 after an engine 170/171: one engine call per fix while the car stays | Right. The O8 branch re-asked with `user = true` on every covered deciding fix; measured 30 requests in 30 s | **Fixed**: the re-ask keeps O4's `min_interval_s`: 3 requests in 30 s (choice 26) | `O8 after an engine 171 the car standing still is asked for again once per min_interval_s, not on every fix` |
| 3 | contract, minor | `close()` while Start or Resume waits for the engine leaks the pack-change hold | Right. The hold lived in a local of an untracked coroutine; whichever order the answer and `close()` ran in, nobody closed it (and guidance even began after `close()` when the answer came first) | **Fixed**: `startJob`, cancelled by `close()`, End, DiscardResume and PlanTo; the hold is given back in a `finally`; an answer after `close()` sees `closed` (choice 29) | `P5 close while Start waits ...`, `R3 close while Resume waits ...` (`RobustnessTest`) |
| 4 | contract, minor | R2 deletes a trip whose age is negative | Right: `age in 0..max` against the rule's `age <= max` | **Fixed** (choice 27) | `R2 a snapshot written at a later wall-clock reading (the clock stepped back since) is offered, not deleted` |
| 5 | contract, minor | The state is written several times per fix, against 2.3 "at most once per accepted fix or tick, plus once per intent" | Right. An unconfined collector saw three states for the fix that confirms off route (REROUTING without `replanning` first), two for the arrival fix and two for the Reroute intent. 2.3 is frozen, so documenting the intermediate values there was not open to the track | **Fixed** by batching (section 3.1, choice 28); events follow their state | `StateChangesTest` (3 tests) |
| 6 | contract, minor | Stage 1 wrote scratch outside `data/r3-n/`, onto `data/routing-reference/timings.json` | Right (rule 7: existing builder outputs under `data/` are read-only to a track). The run merged into the file; what it replaced is lost (section 5a) | **Fixed for the future**: `reference_routes.py` writes timings only to the file `--timings` names (choice 30); `android/routing/README.md` says so; PROGRESS and 5a say what was replaced. Not run again against the shared path | `reference_routes.py --help` loads; no test (a script flag) |
| 7 | reproduce, minor | The report said `test_routing.py` could not run (no pytest), but it has its own runner | Right: its `main()` runs every `test_` function. `main()` also writes `data/routing/results/test_findings.json` and deletes `data/routing/device_test/fakes`, so it was not used | **Done**: `data/r3-n/run_step4.py` calls the 13 `test_step4_*` functions: 13 of 13 PASS (section 5) | the 13 checks themselves |
| 8 | reproduce, minor | Board note `n-golden-trace.md` said 79 `:nav:session` tests, the report 80 | Right | **Fixed**: the line corrected, and a new update line (88 tests, trace unchanged) | - |

No finding was wrong, so none was left alone. The golden trace is unchanged (same SHA-256, file not
rewritten, `Exit24DriveTest` equal row by row): none of the fixes touches the clean run. No public
signature changed, and `:routing:core`, `:routing:banner`, `:routing:valhalla` and `:routing:demo`
were not edited, so the device regression of section 3.5 still describes the code it ran on.

Files changed by the review fixes: `android/nav/session/src/main/kotlin/.../nav/SessionCore.kt`; the
tests `OffRouteTest.kt`, `RobustnessTest.kt`, `PlanningAndResumeTest.kt`, the new `StateChangesTest.kt`;
`android/routing/testdata/nav/session-recordings.json.gz` (+4 answers: a re-route from 600 m along the
exit 24 route and from a car standing 80 m off I-90, each with its OSRM form);
`android/routing/scripts/reference_routes.py`; `android/routing/README.md`;
`android/nav/session/README.md`; this report; `data/r3-board/n-golden-trace.md`.
