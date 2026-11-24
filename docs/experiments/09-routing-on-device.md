# Experiment 09: Routing on the device (valhalla-mobile, planner, banner model, routing packs)

Date: 2026-11-15. Machine: Windows 11, 12 logical CPUs, 32 GB RAM, **shared with five other
engineers running builds and emulators during every measurement below**. Device: an Android
emulator (AOSP API 36 x86_64, 3 GB RAM, WHPX, host GPU) on that loaded machine, not a phone. Run
to run spread was large (the same debug build was 2-5x slower in one hour than in the next), so
every time below is a range from a loaded emulator, not a phone figure.

Revised the same day after two independent reviews (a "reproduce" and a "break" review): one
major and fourteen minor findings, all confirmed, thirteen fixed in code or data and two answered
in this report. What changed and how it was checked is in "Review and fixes" at the end; the
sections below describe the code as it now stands.

## Result in short

- **valhalla-mobile 0.6.4 routes on our PA+OH tiles on Android, with the same answers as
  pyvalhalla 3.9.1.** On the emulator, all 21 trips x 6 presets (126 plans, through the Kotlin
  planner) gave the same time, length, maneuver count, legs and motorway km as the Python
  reference on the same tiles: **126 of 126 equal**; the alternates of the 21 two-stop trips with
  every preset (126 more comparisons) and four trips that start on a motorway (Avoid highways'
  fallback and its alternates) are equal too. The OSRM-format answer for the owner's trip is identical to pyvalhalla's (same geometry,
  same banner and voice instructions). valhalla-mobile 0.6.4 is still the newest release (Maven
  Central, 2026-10-06) and bundles Valhalla 3.9.1 (report 01).
- **No config key or costing option can be dropped**: the engine is opened from a config *file*
  (`Valhalla(configPath)`), and requests go through `routeRaw` / `matrixRaw` as JSON, so nothing
  passes through the wrapper's generated models. Proven on the device by switching each key off in
  a variant config: CostMatrix settings (1x40 on a 479 km trip: 0 null cells with the device
  config, 19 with Valhalla's defaults), `max_alternates` (2 vs 1 for a request of 3), and
  `allow_hard_exclusions` (warning 208 appears without it). **Finding:** in 3.9.1 hard exclusions
  are applied even when not allowed; loki only adds warning 208, because it clears the flags on a
  copy (`auto opts = pair.second.options();` in `src/loki/worker.cc`). Report 01's "rejected unless
  allowed" is not what 3.9.1 does. We keep the key set.
- **New modules** (all under `android/routing/`, see its README): `:routing:core` (model, presets,
  planner, errors, alternates, stop order, engine config; plain JVM), `:routing:banner` (banner
  model; plain JVM), `:routing:packs` (pack install/update/remove; plain JVM), `:routing:valhalla`
  (the engine; Android library), `:routing:demo` (demo app `io.github.spysyweeb.beamline.routingdemo` + the
  on-device tests), `:routing:ferrostarcheck` (the Ferrostar parsing test only). 53 JVM tests and
  11 instrumented tests (9 in the demo, 2 Ferrostar), all passing at the end.
- **The planner behaves like the Python reference**: replaying the recorded pyvalhalla answers, it
  makes exactly the requests `route_lib.run_preset` made (any other request fails the test) and
  picks the same route for 126 of 126 plans (Simpler's pool of 3 candidates, Avoid highways'
  fallback rule included).
- **Banner model**: a pure function from a route to one banner per maneuver. The owner's exit 24
  trip gives exactly what was asked (below), and 13 more real Pennsylvania trips cover roundabouts,
  forks, a left exit (two), U-turns (left and right), a ramp onto a motorway, keep-straight, a
  numbered exit with three route numbers, and arriving. Valhalla 3.9.1 emits **no standalone merge
  maneuver** on this graph (0 in 630 urban routes): the merge is part of the ramp maneuver.
- **Ferrostar 0.59's core parses our route**: in an instrumentation test its own OSRM parser
  (`createOsrmResponseParser(6)`) reads the owner's trip, as valhalla-mobile produced it on the
  emulator, into 5 steps with banner instructions, the exit number "24" and both lane rows.
  **Finding for the owner:** Ferrostar core's AAR demands core library desugaring, which puts
  `desugar_jdk_libs` (**GPL-2.0 with the Classpath Exception**) into any app that uses it.
- **Routing packs on the device**: the rules of `packs.py` (tar layout) in Kotlin. On the real PA
  and OH packs it writes a tile set with **the same index.bin and the same length (1,212,712,960
  bytes) as packs.py**, pyvalhalla routes the 21 trips on it identically, and the PA-only tile set
  gives route / 170 / 171 exactly where report 01 found them. A change is committed with a marker
  file, so a process killed in the middle is completed or undone on the next call (tested by
  simulated kills at three points), and a change without the free space for the new tile set is
  refused before writing. Installing PA on the emulator took 3.6-6.8 s, and about 1.5-2.3 times
  that with the whole-tar hash (load-dependent; three sessions on the shared PC).
- **Measured on the emulator (release build, fresh process each run)**: opening the engine
  160-530 ms (8-13 ms for a second engine in the same process); first route of the process
  125-240 ms (Pittsburgh 5.8 km) and 180-250 ms (Erie-Harrisburg 479 km), warm 18-40 ms and
  73-128 ms; three presets for the 479 km trip 0.3-0.46 s; 1x40 matrix 1.0-1.9 s; process PSS
  55 MiB after opening the engine, 283-316 MiB after a session of long routes and matrices
  (native heap 126-134 MiB).
  valhalla-mobile adds per APK: arm64 7.5 MB, armeabi-v7a 6.0 MB, x86_64 7.9 MB, x86 8.3 MB of
  native code (stored uncompressed; **64-bit libraries 16 KB aligned**, ELF and zip offset), plus
  about 0.95 MB of dex for all processor types (0.6 MB of it is kotlin-reflect, pulled in by its
  Moshi).

## How to rerun

Prerequisites: the routing venv and the built packs of report 01 (`.venv/routing`,
`data/routing/packs/{pa,oh}`, `data/routing/device/`), the PA map build of report 02
(`data/map/packs/pa-261114-z0-15.pmtiles`), the Android SDK and Gradle set-up of report 05. None
of them is in git; `android/routing/README.md` ("Prerequisites and where they live") lists them.
In a checkout or worktree without `data/` and `.venv/` (as this one), pass the paths of the
checkout that has them, read-only: `--device <that checkout>/data/routing/device`,
`-Pbeamline.routingPacks=<that checkout>/data/routing/packs`, `<that checkout>/.venv/routing/Scripts/python`.
The scripts write only under this checkout's `data/` and `android/routing/testdata/`. From the
repo root unless a line says `cd android`:

```
python android/routing/scripts/sync_data.py --device data/routing/device --check   # copies equal packbuilder/
.venv/routing/Scripts/python android/routing/scripts/reference_routes.py --device data/routing/device   # 25 s; same files on a rerun
.venv/routing/Scripts/python android/routing/scripts/old_vs_new_alternates.py --device data/routing/device  # review evidence
cd android
./gradlew :routing:core:test :routing:banner:test :routing:valhalla:testDebugUnitTest
./gradlew :routing:packs:test -Pbeamline.routingPacks=<repo>/data/routing/packs     # writes data/routing-packs-test/{pa-oh,pa-only}
./gradlew :routing:demo:assembleDebug :routing:demo:assembleDebugAndroidTest :routing:ferrostarcheck:assembleDebugAndroidTest
./gradlew :routing:demo:assembleRelease -Pbeamline.abiSplits=true [-Pbeamline.r8Measure=true]
cd ..
.venv/routing/Scripts/python android/routing/scripts/check_kotlin_tileset.py        # pyvalhalla on the Kotlin-written tars
python tools/fetch_android_map_pack.py --pmtiles data/map/packs/pa-261114-z0-15.pmtiles --pack-id us-pa
# emulator: android/routing/README.md "On a device" (own AVD, 3 GB, 10 GB data partition)
python android/routing/scripts/device.py install
python android/routing/scripts/device.py push --tiles data/routing-packs-test/pa-oh/routing.tar \
    --pa-only data/routing-packs-test/pa-only/routing.tar --map-pack data/android/packs/us-pa \
    --routing-pack data/routing/packs/pa
python android/routing/scripts/device.py test          # 9 device tests, about 1 min
python android/routing/scripts/device.py ferrostar     # 2 tests
python android/routing/scripts/device.py measure --runs 3 [--drop-caches]            # debug build, instrumented
adb install -r android/routing/demo/build/outputs/apk/release/demo-x86_64-release.apk
python android/routing/scripts/device.py measure --runs 3 --release [--drop-caches]  # release build
python android/routing/scripts/device.py screenshot data/routing-device/screens/demo.png
python android/routing/scripts/apk_sizes.py android/routing/demo/build/outputs/apk/release/demo-*-release.apk --gzip
JAVA_HOME=<a JDK> apkanalyzer dex packages --defined-only <apk built with -Pbeamline.r8Measure=true>
adb emu kill
```

`device.py` runs tests with `am instrument` and never uninstalls: Gradle's `connectedAndroidTest`
uninstalls the app and with it the 1.8 GB of pushed tiles. Results go to `data/routing-device/`.
`--drop-caches` needs `adb root` (works on the AOSP emulator image). Gradle was run with the
project's caps (2 GB heap, 4 workers).

## 1. The engine on Android

### What was read

valhalla-mobile 0.6.4 source (GitHub tag 0.6.4): `Valhalla.kt`, `ValhallaActor.kt`,
`ValhallaKotlin.kt`, `ValhallaConfigManager.kt`, `src/wrapper/valhalla_actor.cpp`, `main.cpp`,
`build.gradle.kts`, `vcpkg.json`. Relevant facts:

- `Valhalla(configPath: String)` builds the native actor from a JSON file read with
  `rapidjson::read_json` into a ptree: every key in the file reaches Valhalla. The other
  constructor writes a `ValhallaConfig` model (valhalla-models-config 0.6.0) with Moshi; that model
  has typed fields only (it has the keys we need today, several typed `Integer` where Valhalla uses
  floats), so a key it lacks would be dropped. We do not use it.
- `routeRaw(json)` / `matrixRaw(json)` pass the request string unchanged; `route(RouteRequest)`
  goes through the generated request model. We only use the raw methods.
- Errors come back as `{"code": n, "message": "..."}` and are thrown as
  `ValhallaException.Internal`, whose only trace of the code is its message
  `ValhallaError(code=171, No suitable edges near location)`. `ValhallaEngine.errorCode` parses it
  (unit-tested). Recognising the envelope uses Moshi's Kotlin reflection; `ValhallaEngine` also
  recognises it itself, so an R8 or Moshi problem can never turn an error into an "answer".
- The HTTP tile getter is attached only when `mjolnir.tile_url` is set; `EngineConfig` always sets
  it to `""`. The native build has `ENABLE_HTTP OFF`. The demo app has no INTERNET permission.
- Each `Valhalla` holds one native actor; calls are serialised with a lock; `close()` frees it.

### The device config

`EngineConfig.build(tarPath)` = the device `valhalla.json` that `packs.py` writes (bundled as
`valhalla_base.json`, copied by `sync_data.py`, relative paths only), plus `device_config.json`
"set" and `presets.json` "requires_config" applied the way `packs.device_settings()` does
(disagreement is an error), plus the absolute path of the device's `routing.tar`. The routing pack
manager writes it as `valhalla.json` in the device folder; `ValhallaEngine.open(file)` opens it.
A relative `tile_extract` would be resolved against the process's working directory (`/` on
Android), hence the absolute path at install time.

### Proof that it routes on our tiles, and matches pyvalhalla

`DeviceRoutingTest` on the emulator, tiles = the PA+OH tile set written by `:routing:packs` from
the real packs:

| check | result |
|---|---|
| 21 trips x 6 presets through `RoutePlanner` vs `reference/trips.json` (pyvalhalla 3.9.1, same tiles) | **126/126 equal** (time to 0.1 s, length to 1 m, maneuvers, legs, motorway km) |
| alternates of the 21 two-stop trips, every preset | same count (0-2), time, length and motorway km for all 126 |
| 4 trips that start on a motorway (exit 24 trip, I-90 PA -> Erie, I-90 PA -> I-90 OH, I-80 PA -> Sharon), Fastest and Avoid highways with alternates | equal, including which plans used the fallback (3 of 4) |
| the 14 banner trips | same time, length, maneuver types, instructions and lane states as the pyvalhalla recordings |
| exit 24 trip in OSRM format | same distance, duration and geometry string; `bannerInstructions`, `voiceInstructions`, `intersections`, `maneuver` of all 5 steps equal as JSON |
| Buffalo NY from Erie | Valhalla 171 -> "A stop is outside the installed maps" |
| Lake Erie point | routes, with the warning "The route reaches stop 2 8.8 mi from the point you chose" (14.1 km) |
| PA-only tile set, Sharon -> points due west (trips.json probes) | route / 170 / 170 / 171 / 171 as expected; all failures name stop 2 as outside |

Valhalla's version: valhalla-mobile does not report it at run time and its library contains no
version string. The evidence that it is 3.9.1 is the release note and submodule tag (report 01)
plus the identical answers above.

### Config keys and costing options reach the engine

Each key was switched off in a variant config file opened next to the device one (`configKeysReachTheEngine`):

| key | device config | variant | |
|---|---|---|---|
| `service_limits.auto.max_matrix_distance` 1,000 km + `thor.costmatrix.allow_second_pass` | 1x40 along I1: **0** null cells | 400 km / false (Valhalla defaults): **19** null cells | as report 01 on the PC |
| `service_limits.max_alternates` | request 3 alternates: **2** | set to 1: **1** | |
| `service_limits.allow_hard_exclusions` | `exclude_highways`, Harrisburg-Hershey: 0 motorway km, no warning | false: 0 motorway km **and warning 208** "Hard exclusions are not allowed on this server, ignoring hard excludes" | 3.9.1 applies the exclusion anyway (copy bug, above); confirmed with pyvalhalla too |

Costing options: the 126 equal plans include Shortest (`shortest: true`), Eco (`use_distance`),
Avoid highways (`exclude_highways` + fallback `use_highways: 0`), Avoid tolls (`use_tolls`) and
Simpler's `maneuver_penalty` candidates, so each option changes the device route exactly as it
changes pyvalhalla's.

## 2. Route model and planner (`:routing:core`)

- **Request**: `RouteRequest(stops, presetId, withAlternates)`; `Stop(point, type, headingDeg,
  radiusM, name)` with `StopType` break / through / via / break_through (report 01's meanings).
  The Valhalla request is route_lib's plus `turn_lanes: true` (lanes do not change the route;
  checked: 126 equal plans). `break` is left out as Valhalla's default, as route_lib does.
- **Result**: `Route` (length, time, legs, requested points, toll/highway flags) / `Leg` (shape,
  maneuvers) / `Maneuver` (Valhalla type code and enum, instruction, verbal pre-transition text,
  length, time, street names and begin street names, shape indexes, toll, highway, exit sign:
  numbers / branches / towards / names, roundabout exit count, lanes, `verbal_multi_cue`) /
  `Lane` (direction bits, state NONE / VALID / ACTIVE, the direction it is used for). Lane bits and
  maneuver types are Valhalla 3.9.1's (`turnlanes.h`, `directions.proto`).
- **Presets**: loaded from the bundled copy of `presets.json`, validated like
  `route_lib.validate_preset` (unknown option names, wrong types and ranges are refused; Valhalla
  would silently ignore them). A preset with `request_options` is refused at load: route_lib
  merges it into the request and this planner would not, so the two would diverge silently (no
  preset uses it today). The planner implements `run_preset`: plain preset; fallback options
  when the first answer fails or a `fallback_when` condition holds (Avoid highways: more than
  0.5 motorway km -> ask again with `use_highways: 0`, keep the route with less); candidate pool
  with the select rule (Simpler: fewest maneuvers within +5% time, ties by time, first minimum
  wins as Python's `min`). Identical requests within one plan are asked once.
- **Alternates** (two stops only; Valhalla has no multipoint alternates): one request with
  `alternates: max_alternates` and **the options that produced the chosen route**: the pool's
  first request for Simpler (no extra call; test: 3 calls in all), the fallback options when
  Avoid highways' fallback produced the route, else the preset's own options. The chosen route is
  dropped from the answer, and so is any route that keeps more of a `fallback_when` road than both
  the limit and the chosen route (Avoid highways: an alternate never has more motorway than the
  route the rule accepted). route_lib has no alternates rule; the reference is
  `reference_routes.py alternates_for`, recorded for all six presets.
- **Errors in plain words** (`RoutingProblem`): 170 and 171 -> `OutsideInstalledMaps`, naming the
  stops whose 0.25 degree tile is not installed (`TileCoverage` from the installed manifests; tile
  ids checked against pyvalhalla's `get_tile_id_from_lon_lat`); with every stop on installed
  tiles, 170 -> "No road connects these stops" and 171 -> `NoRoadNearStop` ("No road was found
  near one of the stops. Move the stop closer to a road."); 442 -> no route; 154/166 -> too long;
  150 -> too many stops; an engine answer that cannot be read (not JSON, fields missing, unknown
  units) -> `EngineFailure` ("The route could not be planned."), never an exception out of
  `plan()`. Silent far snapping -> `RouteWarning.StopSnappedFar` above
  `checks.warn_if_endpoint_gap_m_over` (1,000 m), for every stop that starts or ends a leg.
- **Stop order**: `StopOrderer` asks one all-to-all matrix (preset costing) and tries every order
  (lexicographic, first minimum, as `itertools.permutations`): 8 stops = 720 orders with the last
  stop kept, 5,040 with a free end. It is capped at 8! = 40,320 orders: 10 stops with the last
  stop kept, 9 with a free end (10 with a free end would be 362,880 orders). When no order
  connects every stop it returns null instead of an order. It minimises matrix *time* for every
  preset, as the Python reference does (see decision 9). On the recorded 8-stop Erie / Ohio /
  Pittsburgh matrix it finds Python's brute-force order (21,293 s), which here equals Valhalla's
  `optimized_route` order.
- **Ferrostar hand-off**: `PlanResult.Ok.chosen` = the request that produced the route and its
  index in the answer; `ValhallaRequests.osrm(chosen.request)` asks the same path in OSRM format.

JVM tests (`./gradlew :routing:core:test`, 24 tests): the 126-plan comparison, the alternates of
every preset (126) and the four motorway-origin trips against recorded real answers
(`reference/recordings.json.gz`, 272 distinct recorded requests, 3.7 MB gzipped), the planner
rules against a scripted fake engine (fallback triggered / not triggered / after a failure, the
alternates after the fallback and their motorway limit, unreadable answers, pool selection, no
alternates with three stops, stop types and heading in the request, error mapping including 171
on installed tiles), recorded errors (Buffalo 171, Lake Erie 14 km, Jamestown 5.6 km), the exit
24 parse, presets and validation (including `request_options`), polyline and tile ids, stop order
(including no connected order and the caps), engine config, OSRM request, and that the data copies
equal their packbuilder source.

## 3. Output for Ferrostar 0.59

### What Ferrostar needs (read in its source at tag 0.59.0)

`common/ferrostar/src/routing_adapters/valhalla.rs` (request), `osrm/mod.rs` and `osrm/models.rs`
(parser), `lib.rs` (exports), `android/core/.../RouteProvider.kt` and `FerrostarCore.kt`:

- Ferrostar's own Valhalla adapter POSTs `format: "osrm"`, `banner_instructions: true`,
  `voice_instructions: true`, `filters: {action: include, attributes: [shape_attributes.speed,
  .speed_limit, .time, .length]}` (for annotations), `costing`, `locations` (start with
  `street_side_tolerance` and `heading` from the course), plus any extra options, and parses with
  the OSRM parser at polyline precision 6.
- Required by the parser: `code` ("Ok"), `routes[]` with `geometry` (polyline string), `distance`,
  `duration`, `legs[].steps[]`; each step `distance`, `duration`, `geometry`, `maneuver` with
  `location`, `bearing_before`, `bearing_after`, `type` (optional `modifier`, `exit`,
  `instruction`), and `intersections[]` with `location`, `bearings`, `entry`. Optional and used:
  `name`, `ref`, `exits`, `driving_side`, `bannerInstructions[]` (`distanceAlongGeometry`,
  `primary` / `secondary` / `sub` with `text`, `type`, `modifier`, `degrees`, `components`),
  `voiceInstructions[]`, leg `annotation`, `via_waypoints`, `waypoints[]`.
- What reaches Ferrostar's route model: banner texts and maneuver type/modifier, exit numbers
  (from `exit-number` components), **lanes only from the banner `sub` components** (`active`,
  `directions`, `active_direction`); the intersections' `lanes` (with `valid`) are parsed but not
  kept. A lane that is valid but not preferred shows as `active=false` with an
  `active_direction`. Valhalla puts the lane `sub` into a second banner from 400 m before the turn
  when the step is longer than 400 m.
- Lost compared with our model: Valhalla's maneuver type codes, `verbal_multi_cue`, per-maneuver
  toll/highway flags, `begin_street_names`.

### Proof (`:routing:ferrostarcheck`, on the emulator)

`FerrostarParsesOurRouteTest` runs Ferrostar 0.59.0's core (native `libferrostar.so` through JNA)
on `reference/exit24-osrm.json` (pyvalhalla) and `reference/exit24-osrm-device.json`
(valhalla-mobile on the emulator, recorded by `DeviceRoutingTest`): both give 1 route, 5,341.6 m,
78 geometry points, 5 steps; on I-90 a `fork / slight right` banner "US 19: Peach Street,
Waterford" with exit number 24 and no lanes, and the spoken "In 1 mile, Keep right to take
exit 24..."; on the ramp two banners (turn left), the second from 400 m with lanes [left]
(valid), [left, straight] (active), [right] (no); on Peach St a `turn / right` banner "Downs Drive"
with four lanes, the right-most [straight, right] active. Both tests pass. Ferrostar's AAR
declares INTERNET and the location permissions; the test APK removes them
(`src/androidTest/AndroidManifest.xml`, `tools:node="remove"`): `aapt2 dump permissions` on it now
lists only androidx.test's `REORDER_TASKS` and its own receiver permission, and the parse still
passes.

### What a custom route provider for Ferrostar has to do

1. Implement `CustomRouteProvider` (`suspend fun getRoutes(userLocation, waypoints):
   List<uniffi.ferrostar.Route>`) and give it to `FerrostarCore(customRouteProvider, httpClient,
   locationProvider, navigationControllerConfig)`. The constructor still wants an
   `HttpClientProvider` (OkHttp); it is never called with a custom provider, and INTERNET stays
   out of the manifest so a slip fails loudly.
2. Build the stops: the user location as the first stop with `headingDeg` = course over ground
   and `radiusM` 60 (report 01: without them a start on a divided road snaps to the other
   carriageway), then the remaining waypoints (`Break` -> break, `Via` -> via; their Valhalla
   properties are JSON in `waypoint.properties`).
3. Run `RoutePlanner.plan` with the trip's preset on the shared engine, on `Dispatchers.IO`.
4. Ask the engine again with `ValhallaRequests.osrm(plan.chosen.request)` (same locations,
   costing options and alternates count; units miles, language en-US), parse the bytes with
   `createOsrmResponseParser(6u).parseResponse(...)`, return `routes[chosen.routeIndex]` first and
   the others as alternates. One extra engine call per plan: 20-130 ms warm on the emulator.
5. Keep our own `Route` (Valhalla JSON) next to Ferrostar's for the banner model: OSRM step k is
   Valhalla maneuver k of the same leg (5 = 5 on the exit 24 trip), so Ferrostar's current step
   index selects our banner.
6. On reroute Ferrostar calls `getRoutes` again with the current location and the remaining
   waypoints: same path, so the preset and the heading rule apply to reroutes too.
7. Map `PlanResult.Failed` to an exception for Ferrostar and show `problem.userText`.

Adding Ferrostar core to the app brings (from its POM / AAR, read here): core library desugaring
(below), JNA 5.18.1 (LGPL-2.1-or-later OR Apache-2.0: record the Apache choice), OkHttp 5,
location permissions in the merged manifest, `libferrostar.so` 2.6-2.8 MB per 64-bit ABI.

## 4. Banner model (`:routing:banner`)

`BannerModel.build(route, rules)` returns one `Banner` per maneuver: `MainInstruction` (icon,
type code, short text "Exit 24" / "Turn left" / "Keep right" / "Roundabout, 2nd exit", roads,
towards, exit number, roundabout exit, approach length), `FollowingTurn` or null (icon, text,
distance and time between the two maneuvers, "In 0.2 mi"), and the lane row or null (cells with
direction bits and BEST / ALLOWED / NOT_FOR_ROUTE). Rules in `packbuilder/routing/banner.json`:

- following turn when the stretch between the two maneuvers is **<= 804.672 m (0.5 mi) and
  <= 60 s** by the route's own estimate (`max_distance_m`, `max_time_s`);
- arriving (types 4-6) is not a following turn (`not_a_turn_types`);
- a lane row only when the engine marked at least one lane valid or active (lanes tagged in OSM
  but none marked for the maneuver get no row; nothing is guessed);
- best lane = the engine's active lanes. Valhalla itself narrows them to the one lane on the side
  of the next maneuver when the maneuver is shorter than 0.402 km (`ActivateTurnLanes`,
  `src/odin/enhancedtrippath.cc`). When the banner shows a following turn and the engine left
  several best lanes, the banner applies the same rule up to the 0.5 mi limit: the active lane
  nearest the side of the following turn stays best, the others become "also allowed"; straight
  maneuvers (becomes, continue, ramp straight, stay straight) have no side
  (`refine_best_lane_for_following_turn`, `no_side_types`);
- distance texts: under 100 ft in 10 ft steps, up to 500 ft (152.4 m) in 50 ft steps
  ("In 250 ft"), then tenths of a mile ("In 0.2 mi") up to 9.95 mi, then whole miles. Each step
  ends where the next one's first text begins, so the text never goes back up as the car closes
  in (the first version ended the feet at 0.1 mi = 528 ft and showed "550 ft" just before
  "0.1 mi", and "10.0 mi" just before "10 mi"); a test sweeps 0-40 km in 0.1 m steps.

### The owner's trip

Start on I-90 eastbound 2.6 mi west of exit 24 (42.028694, -80.128884, heading 57, radius 60), end
on Downs Drive (42.053308, -80.079579). The end point was chosen on Downs Drive itself: the trip
description ends there and gives no address. With the Walmart at 1825 Downs Dr as the end, Valhalla
adds "Turn right" into the car park 0.32 mi / 55 s after the right turn, which by the rule would be
shown as a following turn.

| maneuver (Valhalla) | banner | following | lanes |
|---|---|---|---|
| 23 Keep right to take exit 24 onto US 19/Peach Street toward Waterford (0.262 mi, 32.8 s) | Exit 24 / US 19 / Peach Street, toward Waterford | Turn left, **In 0.3 mi** | **none** (no lane data at the exit) |
| 15 Turn left onto US 19/Peach Street/... (0.221 mi, 28.7 s) | Turn left / US 19 / Peach Street / General Strong Vincent Memorial Highway | Turn right, **In 0.2 mi** | **allowed, best, not for route** (left / left+through / right) |
| 10 Turn right onto Downs Drive (0.243 mi, 42.4 s) | Turn right / Downs Drive | **none** (arriving) | **not, not, not, best** (left / through / through / through+right) |

Valhalla's `verbal_multi_cue` is false on the left turn (it does not chain the left and the right),
as the design notes said. The engine's own lane choice already takes the right turn into account
(active = left+through); with the destination on the west part of Downs Drive (next turn left) it
marks the left lane active instead, which shows the rule works both ways.

### The other real cases (`banner_cases.json`)

Found by routing about 1,900 trips between random points around PA cities with pyvalhalla and
harvesting maneuvers by type, then cutting each trip to about 1.5 km either side of the maneuver
(start with the road's heading and radius 60). All 14 are routed by `reference_routes.py`, which
fails if the expected maneuver is missing, and again on the emulator.

| case | what it shows (asserted in `BannerModelTest`) |
|---|---|
| roundabout_park_line | "Roundabout, 2nd exit" onto Park Line Drive (road names taken from the exit maneuver), chained 0.3 mi / 200 ft / 150 ft, no lane rows |
| roundabout_us222 | approach lanes tagged but none marked -> no lane row |
| fork_left_i476 | "Exit 7" with two best lanes; next fork 838 m away -> not chained; keep left with two best lanes |
| fork_right_us1 | "Exit 340B" onto US 1 North, lanes not / not / best / best |
| left_exit_i676 | "Exit 22 on the left", keep-left 0.48 mi later chained "In 0.5 mi"; engine marks two lanes active, banner narrows to the left one: best, allowed, not, not |
| left_exit_red_lion | "Take the exit on the left", no lane data, depart chained "In 150 ft" |
| uturn_left_grant | left turn -> "Make a U-turn" in 0.3 mi -> right turn in 350 ft, lanes best/not/not |
| uturn_right_i376 | right U-turn chained "In 0.1 mi"; arrival whose lanes have none marked -> no row |
| ramp_onto_i76 | left exit (lanes "merge to right" + two without arrows: no row), right turn in 0.2 mi, U-turn in 400 ft (engine already picks the left of the two right-turn lanes: not, best, allowed), U-turn -> ramp in 200 ft, "Take the ramp on the right" I 76 East / US 1 North |
| keep_straight_us22 | "Exit 50" -> "Keep straight" in 0.2 mi; two best lanes kept (a straight has no side) |
| exit_147a_i79 | "Exit 147A" / US 6 East / US 19 North / US 322 East, toward Meadville |
| keep_right_aramingo | "Exit 26" -> keep right in 0.3 mi -> keep right in 0.1 mi with lanes not / allowed / best |
| arrive_left | right turn -> left turn in 250 ft; the left turn does not chain to the destination; "Arrive, on the left" |

Motorway merges: 0 merge maneuvers (types 25, 37, 38) in 630 random urban routes (7,515
maneuvers) around Philadelphia, Pittsburgh, Harrisburg, Allentown, Scranton, Lancaster and Erie,
and none in about 670 other routes. Valhalla 3.9.1 has the merge types
(`ManeuversBuilder::IsMergeManeuverType`), but in every route looked at the ramp maneuver simply
continues onto the motorway ("Turn right to take the I 376 East ramp toward Monroeville", 9.8 mi).
The icon mapping for merges exists but is not exercised by a real case.

A table of every banner of every case is written by the test to `android/routing/banner/build/banner-cases.md`.

## 5. Routing packs on the device (`:routing:packs`)

`RoutingPackManager(deviceDir, engineVersion)` with `install(listOf(PackSource...))`,
`uninstall(state)`, `installed()`, `coverage()`. Same rules as `packs.py` (tar layout): one
`routing.tar` with `index.bin` rewritten from the union of the installed packs' tiles (sorted, the
same layout `valhalla_build_extract` and `packs.py` write) into `routing.tar.new` and renamed over
the old one (atomic rename); shared border tiles stored once, each manifest a reference count;
all packs from one graph build (refused otherwise, with packs.py's message); installing an
installed state is an update (changed tiles replaced, tiles only the old copy listed removed);
manifest `valhalla_version` and every tile header's version must equal the engine's (3.9.1, read
at byte 16 of the GraphTileHeader); every tile is checked against its manifest SHA-256 while it is
copied, the pack tar against `tar_sha256`; a refused or failed install leaves `routing.tar`
byte-identical and no `.new` file. It writes `device.json` and the engine's `valhalla.json`.

Beyond packs.py (which writes the manifests after the swap and has the same window on the PC):

- **Commit marker.** A change writes `routing.tar.new`, stages the new manifests, `device.json`
  and `valhalla.json` in `pending/` (each written and fsynced), then writes `pending/COMMIT`
  (written to a temporary name and renamed: the commit point), then renames the tile set into
  place and moves the staged files in. Every public method first calls `recover()`: with `COMMIT`
  present it completes the change (each step can run again), without it it deletes
  `routing.tar.new` and `pending/`. So `routing.tar` and `packs/` never disagree past the next
  call. Folder fsync after renames on Android/Linux; Windows has none (skipped silently).
- **Free space.** Before writing, the exact length of the new tile set (`TileTar.sizeOf`, equal to
  the written length in the tests) plus 4 MiB must be free in the device folder; otherwise
  "not enough free space: the new routing tile set needs N MB, M MB are free", nothing written.
- **Plain names.** The manifest has no hash of its own (`tar_sha256` is inside it) and its names
  become file and member names: `state` must be `[a-z0-9_-]{1,32}`, `tar` a plain file name
  without `..`, every tile path `[0-2](/ddd){2,3}.gph`.

Tests (JVM): 11 rule tests on small synthetic packs (run anywhere: add/remove neighbour, other
build refused, release update refused for one state and accepted for all with 2 changed and 1
stale tile, version in manifest and in a tile header, wrong tile hash, tampered tar, differing
shared tile, engine config, a change killed after the commit point at two places and completed by
the next call, a change killed before it and undone, too little free space, manifest names) and
1 test on the real packs:

| step on the real packs | result | time (JVM on the loaded PC): first two runs / two runs after the review fixes |
|---|---|---|
| install PA on an empty device | 286 tiles, every tile's SHA-256 = manifest | 5.8-14.2 / 5.4-7.0 s |
| install OH on top | 510 tiles (286 + 239 - 15 shared); **index.bin byte-identical to packs.py's device tar, same length 1,212,712,960 B** | 15.5-20.1 / 8.2-10.5 s |
| uninstall OH | tile set and index back to PA-only | 3.6-5.8 / 2.8-3.5 s |
| install PA+OH in one call (release update) | 510 tiles | 14.1-17.2 / 8.7-11.1 s |
| install PA without the whole-tar hash | | 3.1-4.3 / 2.5-3.2 s |

The review's reproduction on the same PC measured 24.6 s for the PA+OH step and 20.9 s for OH on
top, with an emulator booting beside Gradle: these steps move by 3x with the load, so read them as
"seconds to tens of seconds" on this PC, not as figures. packs.py measured 4.5-4.7 / 7.1-7.3 /
2.9-3.0 / 8.8-10.0 s for the same steps (report 01). pyvalhalla on the Kotlin-written tars
(`check_kotlin_tileset.py`, rerun after the fixes): the 21 trips equal the reference; PA-only
probes give route / 170 / 170 / 171 / 171.

On the emulator (`PackInstallOnDeviceTest`, debug build, the real PA pack pushed to the app's
files): install with the whole-tar hash / without it / remove took 15.7 / 6.8 / 0.2 s in the
first session, 8.7 / 4.0 s in the review's, and 5.3 / 3.6 / 0.09 s after the fixes (the commit
marker included); the engine routes Erie-Harrisburg on the result each time. The whole-tar hash
costs 1.5-2.3 times the install.

## 6. Demo app (`:routing:demo`)

`io.github.spysyweeb.beamline.routingdemo`, own application id, not `:app`. It opens the engine on the pushed
PA+OH tiles, plans Lancaster -> Gettysburg (trip R2) with all six presets (or `--ed
from_lat/from_lon/to_lat/to_lon`), and draws each route as an offset line on a MapLibre map from
the Pennsylvania map pack (`:core:packs` composes the style), with a legend (time, miles, turns,
plan ms), a green start and a red destination marker, and the camera fitted to the routes and
stops in the part of the screen below the legend (48 dp at the sides). After the review fixes
(debug build), `data/routing-device/screens/r2-lancaster-gettysburg.png`, `r2-pittsburgh-u1.png`
and `r2-buffalo.png` were taken and looked at: both ends and both markers are inside the frame
(the first version clipped "Getty|" and "|ter"), the legend numbers are those below, and the
Buffalo marker sits in the grey outside the PA pack, which says why every preset failed. A wide
east-west trip on a tall screen still leaves map above and below the routes; under the
Lancaster-Gettysburg routes it is Maryland, outside the pack, so grey. The first screenshots,
taken with `device.py screenshot` and looked at:

- `data/routing-device/screens/release-lancaster-gettysburg.png`: six lines between Lancaster and
  Gettysburg through York; Fastest, Simpler and Avoid tolls 74 min / 54.9 mi / 10 turns; Shortest
  86 min / 53.4 mi / 16 turns; Eco 75 min / 54.3 mi; Avoid highways 86 min / 53.3 mi / 12 turns
  (the same numbers as the Python reference). The bottom quarter of the map is grey: that is
  Maryland, outside the PA pack.
- `demo-pittsburgh-u1.png`: Market Square -> Oakland; Shortest (orange) clearly takes another
  street through the Bluff; the others run along the river. With parallel offset lines, a
  different street is hard to tell from the same street at this zoom; the legend numbers carry it.
- `release-buffalo.png` (release build): Erie -> Buffalo, every preset "A stop is outside the
  installed maps...", the map showing the installed state.

Found by looking: the first release screenshot of the Buffalo case showed the launcher, because
the demo crashed when every preset failed (empty bounds); fixed. The release build first failed
outright: Android's ICU regex refuses an unescaped `}` that the JVM accepts (`ValhallaEngine`'s
envelope pattern; the JVM unit test had passed). Both were caught only by running on the device.

## 7. Measurements (emulator under load)

These are the first session's figures; the review reproduced the release-build ones within the
ranges below. The review fixes change no code on these paths (Fastest, the matrix, the stop order
loop and the engine are as measured; the alternates change only touches presets with a fallback
rule), so they were not measured again, except the pack install (section 5). After the fixes, the
on-device test's own plan times for the 126 plans (debug build) had a median of 86 ms (15-638 ms),
and the release demo planned all six presets for Lancaster-Gettysburg in 1.4 s after a 576 ms
engine open, inside the ranges below.

Each run is a fresh process (`device.py measure`), on the PA+OH tiles in the app's external files
folder. Release = R8-minified, not debuggable (`DemoActivity --es measure`); debug = the
instrumented `MeasureTest`. "first" = the first route of the process (tiles not yet touched by
the engine; OS page cache warm unless "dropped"); "warm" = median of 5 repeats. Memory: Android
`Debug.MemoryInfo` (PSS) and `/proc/self/status` (VmRSS, VmHWM, RssFile, RssAnon), read in the
process.

| | release, page cache warm (6 runs) | release, page cache dropped (6 runs) | debug, last 4 runs |
|---|---|---|---|
| open the engine (first in process) | 246-423 ms | 162-303 ms | 1,025-1,844 ms (2.2-7.0 s in an earlier, busier hour) |
| open a second engine in the same process | 8-10 ms | 8-10 ms | 11-13 ms |
| U1 Pittsburgh 5.8 km, first / warm | 125-237 / 20-40 ms | 185-461 / 18-34 ms | 125-206 / 20-35 ms |
| I1 Erie-Harrisburg 479 km, first / warm | 181-248 / 88-128 ms | 428-559 / 73-95 ms | 198-260 / 73-134 ms |
| 3 presets (Fastest, Shortest, Avoid highways), I1 | 308-458 ms | 412-751 ms | 307-768 ms |
| same, R2 88 km | 158-257 ms | 244-353 ms | 150-248 ms |
| Simpler (3 calls), I1 / R2 | 328-475 / 188-282 ms | 305-503 / 188-242 ms | 286-702 / 178-303 ms |
| Fastest with alternates (2 calls), I1 | 235-372 ms | 197-238 ms | 217-324 ms |
| matrix 1x40 along I1, first / warm (0 nulls) | 1,068-1,892 / 1,023-1,831 ms | 1,104-1,383 / 925-1,291 ms | 1,231-1,668 / 963-2,245 ms |
| stop order, 8 stops (8x8 matrix + 720 orders) | 510-899 ms | 596-987 ms | 565-1,197 ms |
| PSS before the engine / after opening / after all of the above | 11-16 / 55 / 283-316 MiB | 11-16 / 53-55 / 306-314 MiB | 94 / 131 / 359-405 MiB |
| native heap PSS at the end | 126-134 MiB | 126-134 MiB | 121-129 MiB |
| VmRSS / VmHWM at the end | 405-438 / 529-537 MiB | 428-436 / 528-530 MiB | 463-495 / 588-590 MiB |
| RssFile / RssAnon at the end | 220-222 / 183-217 MiB | 218-219 / 209-217 MiB | 204 / 251-284 MiB |

For comparison, pyvalhalla on the PC (report 01): U1 warm 19-23 ms, I1 warm 46-56 ms, Actor
creation 5.5-6.2 ms, 1x40 1.2-1.6 s. Notes:

- Opening costs 160-530 ms once per process in the release build (loading the 7.9 MB library,
  Valhalla's static set-up, Moshi's reflection in valhalla-mobile), 8-13 ms afterwards. In the
  debuggable build the same step took 1-7 s, so debug numbers say little about opening. The app
  should open the engine once, off the main thread, and keep it.
- The demo's own runs (map on screen, R2 with six presets): engine open 307-534 ms (release),
  then all six presets in 1.4-1.7 s together: Fastest 486-609 ms (the first route of the
  process), Simpler 427-626 ms, the others 67-216 ms.
- After a session with two 479 km routes, presets, a matrix and the stop order, the native heap
  is 126-134 MiB and stays: Valhalla keeps its search label arrays (`thor.clear_reserved_memory`
  is false in the build config). With the map on screen and only the short Pittsburgh trip
  routed, `dumpsys meminfo` showed native heap 86 MiB, total PSS 140 MiB, RSS 250 MiB. RssFile counts
  the touched pages of the memory-mapped tile file (and libraries), which the OS can drop.
- On-device plan times inside `DeviceRoutingTest` (debug, the busier hour): median 139-161 ms per
  preset, Simpler 498 ms.

### APK size per processor type (release, R8, `-Pbeamline.abiSplits=true`)

| ABI | demo APK | libvalhalla-wrapper.so (stored uncompressed) | gzip -9 of it (estimate of the download) | PT_LOAD alignment | zip data offset 16 KB-aligned |
|---|---|---|---|---|---|
| arm64-v8a | 22,764,312 B | 7,521,264 B | 2,436,940 B | 0x4000 (16 KB) | yes |
| armeabi-v7a | 17,852,358 B | 6,017,176 B | 2,281,423 B | 0x1000 | yes |
| x86_64 | 23,739,951 B | 7,907,088 B | 2,565,864 B | 0x4000 (16 KB) | yes |
| x86 | 23,910,646 B | 8,290,784 B | 2,690,564 B | 0x1000 | yes |

Both 64-bit libraries are 16 KB page-aligned (the requirement covers 64-bit ABIs; 32-bit ones
keep 4 KB). Dex added by valhalla-mobile, the same for every ABI (apkanalyzer on a release build
with `-dontobfuscate`, `-Pbeamline.r8Measure`): `com.valhalla` 273 KB, `kotlin.reflect` 612 KB,
`com.squareup.moshi` 48 KB, `com.osrm` 19 KB, about **0.95 MB**; `kotlin-reflect` and Moshi come
only from valhalla-mobile (`moshi-kotlin` 1.15.1; checked in the dependency tree); okio (25 KB) is
shared with MapLibre's OkHttp. apkanalyzer's download-size estimate for the whole arm64 demo APK
(MapLibre included) is 8.77 MB. For comparison, MapLibre's `libmaplibre.so` is 13.0 MB (arm64).
R8: valhalla-mobile ships its own keep rule (the HTTP client called from C++); the demo adds
`ErrorResponse` and `kotlin.Metadata` for Moshi's reflection. The release build was run on the
emulator, routes and reports 171 correctly.

## Failures, dead ends, unverified

- **No real phone.** Every Android figure is from an emulator (x86_64) on a loaded PC. ARM phones
  will differ; the 2-5x spread between hours of the same build shows how much the load moves them.
- **Gradle's `connectedAndroidTest` is unusable here** (it uninstalls the app and the 1.8 GB of
  tiles); tests run through `am instrument` (`device.py`).
- **The `com.android.library` plugin cannot be requested with a version** in a subproject (AGP is
  already on the root classpath through `android-application`; Gradle refuses: "already on the
  classpath with an unknown version"). The library modules apply it by id; the root build file
  was not touched.
- **Ferrostar check had to be its own module**: Ferrostar core's AAR metadata requires core library
  desugaring; enabling it in `:routing:valhalla` would have forced it on every module using it.
- **Two device-only bugs** (ICU regex, demo crash when every preset fails): fixed, see section 6.
  A third came with the review fixes: the APK packager stores `.gz` assets decompressed and
  without the `.gz`, so the on-device test reads `reference/banner-routes.json`, not the name in
  `testdata/`.
- **Gradle's build cache restores test tasks.** `cleanTest` followed by `test` can come back
  `FROM-CACHE` without running a test (`org.gradle.caching=true` in `gradle.properties`); the final
  test run used `--no-build-cache`.
- **A first `configKeysReachTheEngine` assumption was wrong**: I expected the exclusion to be
  ignored without `allow_hard_exclusions`; 3.9.1 applies it and only warns (section 1).
- **Not verified**: Ferrostar navigation itself (not built, as asked); the merge icon on a real
  route (none exists in 3.9.1 output here); Avoid tolls from a point on a toll road (report 01's
  open item, unchanged); `thor.clear_reserved_memory` / smaller label reservations to lower the
  130 MiB native heap (not tried); the licence list of what is linked into `libvalhalla-wrapper.so`
  (from its build files: protobuf, Boost, abseil, lz4, rapidjson, robin-hood-hashing,
  unordered-dense; a string scan of the arm64 library found no GPL/LGPL text, which proves little);
  the whole-tar SHA-256 on a phone CPU with hardware SHA (emulator only).
- **Measurement method limits**: "first route" figures with a warm page cache are process-cold
  only; the "dropped" runs used `echo 3 > /proc/sys/vm/drop_caches` as root on the emulator, whose
  own disk sits in the Windows file cache, so they are not phone flash reads either.

## Licences (checked at the primary source)

| item | licence | ships in the app | checked at |
|---|---|---|---|
| valhalla-mobile 0.6.4 | MIT | yes (`:routing:valhalla`) | Maven POM |
| Valhalla 3.9.1 inside it | MIT | yes | report 01 (COPYING at the tag) |
| its native deps (protobuf, Boost, abseil, lz4, rapidjson, robin-hood-hashing, unordered-dense) | BSD-3 / BSL-1.0 / Apache-2.0 / BSD-2 / MIT / MIT / MIT | yes, inside the .so | report 05 and `src/vcpkg.json` at 0.6.4 (build files, not the binary) |
| valhalla-models 0.6.0, valhalla-models-config 0.6.0 | MIT | yes (transitive) | Maven POMs |
| com.stadiamaps:osrm-openapi 0.0.10 | BSD-3-Clause | yes (transitive) | Maven POM |
| Moshi, moshi-kotlin, moshi-adapters 1.15.1 | Apache-2.0 | yes (transitive) | Maven POMs |
| kotlin-reflect 1.8.21 | Apache-2.0 | yes (transitive) | Maven POM |
| okio 3.7.0 | Apache-2.0 | yes (also via MapLibre) | Maven POM |
| Ferrostar core 0.59.0 | BSD-3-Clause | **no** (test APK of `:routing:ferrostarcheck`) | Maven POM |
| JNA 5.18.1 (with Ferrostar) | LGPL-2.1-or-later OR Apache-2.0 | no (test APK) | Maven POM |
| desugar_jdk_libs 2.1.5 (required by Ferrostar core) | GPL-2.0 with the Classpath Exception | no (test APK) - **would ship with Ferrostar** | Google Maven POM |
| androidx.test runner 1.7.0, core 1.7.0, ext-junit 1.3.0 | Apache-2.0 | no | Google Maven POMs |
| JUnit 4.13.2 | EPL-1.0 | no | Maven POM |
| JUnit 6.1.3, kotlinx-serialization-json, coroutines, Compose, MapLibre | as recorded in report 05 | as there | report 05 |

No code was copied from any project; Valhalla, valhalla-mobile and Ferrostar sources were read
for behaviour only. No OsmAnd or Organic Maps code or data.

## Decisions for the owner

1. **Ferrostar core needs `desugar_jdk_libs` (GPL-2.0 with the Classpath Exception) in the app.**
   Its AAR metadata says `coreLibraryDesugaringEnabled=true`, and AGP refuses to build an APK with
   Ferrostar core otherwise. The Classpath Exception allows linking without the GPL applying to the
   app, but the rule says nothing GPL may ship. Options: accept it as a linking exception; ask
   Ferrostar to drop the flag (our minSdk 26 has java.time natively); or do guidance without
   Ferrostar core. *Needs a decision before the guidance step.*
2. **Arriving is not a "following turn"** (`banner.json` `not_a_turn_types`). This is what makes the
   owner's right turn onto Downs Dr show nothing beside it. The alternative chains "then arrive"
   when the destination is within 0.5 mi and 1 min. *Recommended: keep as is.*
3. **Best-lane narrowing up to the 0.5 mi limit** (Valhalla does it itself below 0.25 mi). It
   changes one of the 14 cases (left exit 22: best, allowed instead of best, best). *Recommended:
   keep; it is one flag in `banner.json`.*
4. **Pack format between tracks.** The app pack being built in the main checkout (manifest v2,
   `parts.routing`) records the tar and its SHA-256 but not the tile list, graph build per tile or
   per-tile hashes that the update rules need. *Recommended:* ship packbuilder's routing manifest
   (`beamline-routing-pack/1`) inside the app pack (e.g. `routing.json`) and let `PackSource` read it.
5. **Whole-tar hash on the phone**: it makes the install 1.5-2.3 times as long on the emulator
   (three sessions; the absolute times moved 3x with the PC's load) and adds nothing the per-tile
   hashes do not check (every tile byte written is hashed; the tar headers are rewritten).
   *Recommended:* verify the download's hash once after download, not again at install
   (`verifyTarHash = false`).
6. **valhalla-mobile brings kotlin-reflect** (0.6 MB dex) for its Moshi use. *Recommended:
   accept for now;* an upstream raw-JSON entry point without Moshi would remove it.
7. **Native heap of about 130 MiB after long routes and matrices.** *Recommended:* test
   `thor.clear_reserved_memory: true` and smaller `max_reserved_labels_count_*` in
   `device_config.json` on a real phone before release.
8. **Two engine calls per route for Ferrostar** (Valhalla JSON for our model and banner, OSRM for
   Ferrostar), 20-130 ms extra. *Recommended:* accept; deriving one from the other would lose
   Valhalla's maneuver types and multi-cue, or mean writing an OSRM serializer.
9. **Stop order always minimises driving time**, also for Shortest and Eco (the matrix is asked
   with the preset's options, but the brute force sums the matrix times, as the Python reference
   does). presets.json has no field saying what a preset optimises, and putting that rule in code
   would break "rules live in data". *Recommended:* keep time for now; if Shortest should order by
   distance, add an `order_by` field to presets.json (packbuilder first) and have `StopOrderer`
   read it.
10. **The alternates' motorway limit** (new with the review fix): an Avoid highways alternate is
   dropped when it keeps more motorway than both 0.5 km and the chosen route. On the three trips
   that start on a motorway it keeps two alternates each, with the same motorway km as the chosen
   route. *Recommended: keep;* it reads the preset's own `fallback_when`, no new data.

## Files

New: `android/routing/` (modules `core`, `banner`, `packs`, `valhalla`, `demo`, `ferrostarcheck`;
`scripts/` `sync_data.py`, `reference_routes.py`, `check_kotlin_tileset.py`, `device.py`,
`apk_sizes.py`, `old_vs_new_alternates.py`; `testdata/` with `banner_cases.json`, `trips.json`
(copy) and `reference/`; `README.md`), `packbuilder/routing/banner.json`, this report. Changed
(appended lines only): `android/settings.gradle.kts` (6 includes), `android/gradle/libs.versions.toml`
(versions and libraries of this track), `packbuilder/routing/README.md` (banner.json and the copy
script). Not committed (`data/`): `data/routing-packs-test/` (2 tile sets, 1.85 GB),
`data/android/packs/us-pa` (465 MB), `data/routing-device/` (results, logs, screenshots, APKs),
`data/routing-reference/timings.json`, `data/logs-r2/` (logs of the review-fix round), and the
reviewers' own copies `data/review-reproduce/` (3.0 GB) and `data/review-break/`, left as they
were.
The emulator `beamline_routing` (`~/.android/avd/`) was stopped at the end.

## Review and fixes

Two independent reviews read the code and reran the work: "reproduce" (verdict: sound with fixes;
it reproduced every result within the stated ranges except some load-dependent timings) and
"break" (sound with fixes). Their 15 findings were checked one by one before changing anything;
none could be shown wrong, so none is disputed. The two alternates findings are the same defect
seen from two sides.

| # | lens | severity | finding | checked how | what happened |
|---|---|---|---|---|---|
| B1 | break | **major** | Avoid highways alternates after the fallback are asked with `exclude_highways`, the options the fallback rule had just rejected | Read `RoutePlanner.alternatesFor`; reran the old rule on real tiles (`old_vs_new_alternates.py`): from I-90 in PA, the alternates to Erie had 46.7 and 26.5 motorway km (chosen route 1.9), into Ohio 115.3 (chosen 4.2), to Sharon 13.5 and 5.6 (chosen 4.6) | **Fixed.** Alternates use the options that produced the chosen route, and an alternate may not keep more of a `fallback_when` road than both the limit and the chosen route. Now 1.9/1.9, 4.1/4.1 and 4.6/4.6 motorway km. Fake-engine tests (requests and filter), reference for all six presets x 21 trips plus 4 motorway-origin trips, equal on the JVM and on the emulator |
| R1 | reproduce | minor | Alternates validated only for Fastest; Avoid highways alternates ignore the fallback | Same as B1; `reference_routes.py` recorded only `presets["fastest"]` | **Fixed** with B1. Alternates of every preset are now in `reference/trips.json` (126) and compared on the JVM and the emulator; the motorway-origin trips cover the fallback (3 of 4 use it, the exit 24 trip keeps the exclusion because exit 24 is the first exit) |
| R2 | reproduce | minor | Pack install atomic for `routing.tar` only; manifests written after; no free-space check | Read `RoutingPacks.install`; the order was packs.py's | **Fixed**: commit marker in `pending/` with roll forward / roll back on the next call, free-space check from the exact new tar length (section 5). Tests kill the change at three points (before the commit, after it, after the tile swap) and check the next call; too little space is refused with nothing written. packs.py (PC side) is unchanged |
| B4 | break | minor | Same window; manifest `state` / `tar` used as file names unchecked (`../`) | Read `PackSource.fromDir` and `install` | **Fixed** with R2, plus plain-name checks for `state`, `tar` and tile paths (test with `../pa`, `../../x.tar`, `sub/x.tar`, `../0/002/904.gph`). The manifest itself is still not covered by a hash: that belongs to the download (decision 4) |
| R3 | reproduce | minor | `StopOrderer` returns the identity order when no order is connected | Read `bestOrder`: `best ?: (0 until n)` with time infinity | **Fixed**: `bestOrder` returns null when no order connects every stop; test with a cut matrix |
| R4 | reproduce | minor | A preset with `request_options` would be ignored by the Kotlin planner | Read `route_lib.request` (merges it) and `ValhallaRequests.route` (does not); no preset uses it | **Fixed** by refusing the key at load with a clear message (applying it would mean copying route_lib's subtle rules: pools drop it, the fallback keeps it); test |
| R5 | reproduce | minor | Rerunning `reference_routes.py` rewrites committed fixtures with timing noise; README commands assume the main-checkout layout | Ran the script twice | **Fixed**: timings go to `data/routing-reference/timings.json` (not committed); recordings are written once per distinct request; a second run on the same tiles gave byte-identical files (all 7). README has "Prerequisites and where they live", the report says how to point the commands at another checkout, and the script stops with a plain message when the device folder is missing |
| R6 | reproduce | minor | The on-device banner test's name overstates what it checks | Read the test | **Fixed**: renamed `bannerTripsEqualTheRecordings`; it now compares time, length, maneuver types and instructions and lane states with `banner-routes.json.gz` (passes on the emulator) |
| R7 | reproduce | minor | Some figures do not reproduce within 20 %: they depend on load | The review's own numbers; mine after the fixes were lower again (section 5) | **Answered in the report**: decision 5 now gives the ratio, not a single number, and section 5 lists the ranges of all sessions. Nothing to fix in code |
| R8 | reproduce | minor | Demo map: route ends cut off, no start/end markers, half the screen unused | Looked at the screenshots | **Fixed**: camera fitted below the measured legend with 48 dp sides, start and destination markers; new screenshots looked at (section 6). A wide trip on a tall screen still leaves map above and below the routes |
| B2 | break | minor | Distance text not monotonic just under 0.1 mi ("550 ft" before "0.1 mi") | Old steps: 160.3 m -> 550 ft, 161 m -> 0.1 mi; the same kind of jump at 10 mi ("10.0 mi" before "10 mi") | **Fixed in `packbuilder/routing/banner.json`** (feet up to 500 ft, tenths up to 9.95 mi), copied by `sync_data.py`; tests at 150 / 152 / 152.5 / 155 / 160.5 / 161 m and 16,013 m, and a sweep 0-40 km in 0.1 m steps. The 14 real banner cases did not change |
| B3 | break | minor | Engine answers that cannot be parsed escape `RoutePlanner.plan` as exceptions | Read `Calls.route` | **Fixed**: unreadable answers become `EngineFailure`; also for the matrix in `StopOrderer`. Test with "not json", `{}` and units "furlongs"; for Avoid highways an unreadable first answer leads to the fallback, as a 442 does |
| B5 | break | minor | The Ferrostar check's test APK declares INTERNET and location permissions | `aapt2 dump permissions` | **Fixed**: `src/androidTest/AndroidManifest.xml` removes them; the test APK now lists only `REORDER_TASKS` (androidx.test) and its own receiver permission; the Ferrostar test still passes |
| B6 | break | minor | Report understates stop reordering at the cap: 10 stops with a free end is 362,880 orders; the companion function lacks the n<2 guard | Read `StopOrderer` | **Fixed**: the cap is 8! = 40,320 orders, i.e. 10 stops with the last kept and 9 with a free end (`maxStops`); 0, 1 and 2 stops are handled; tests of both caps |
| B7 | break | minor | Error 171 with every stop on installed tiles is reported as "outside the installed maps"; stop order always minimises time | Read `RoutingProblem.from` | **First half fixed**: `NoRoadNearStop` ("No road was found near one of the stops. Move the stop closer to a road."); test. **Second half left as decision 9**: no data field says what a preset optimises, and the Python reference orders by time too |

What was rerun after the fixes (same loaded PC, five other engineers' jobs running; the emulator is
my own `beamline_routing`, 3 GB, started for this round and stopped at the end):

- `sync_data.py --check`: all five copies equal their source.
- `reference_routes.py` (pyvalhalla 3.9.1, main checkout's device folder, read-only): 25 s, 314
  requests (272 distinct); the 126 plans and the Fastest alternates equal the previous fixture,
  banner, OSRM, reorder and errors files byte-identical; a second run byte-identical in all 7
  files. Recordings grew from 2.0 to 3.7 MB (gzip) for the alternates of all presets.
- JVM, with `--no-build-cache` so no test is restored from the cache: 53 tests pass (core 24,
  banner 15, packs 12 including the real PA/OH packs, valhalla 2), 1 min 17 s. The Kotlin-written
  PA+OH tile set again has packs.py's index.bin; `check_kotlin_tileset.py` again "all as expected".
- Emulator (debug build; tile sets written by the fixed pack manager, pushed again): `device.py
  test` 9 of 9 in 61 s (126 plans, 126 alternates, 4 motorway-origin trips, config keys, errors,
  OSRM, banner trips against the recordings, measurement, pack install), `device.py ferrostar` 2
  of 2. Release (R8) build installed and run: routes all six presets, markers drawn.
- Re-measured because a fix touched them: the pack install (JVM and emulator, section 5). Not
  re-measured: route, matrix and stop-order latency, memory and APK sizes (no code on those paths
  changed; the valhalla-mobile libraries are the same files).
