# Routing on the device (routing track)

Route planning on the phone: valhalla-mobile (Valhalla 3.9.1) on the routing packs, a UI-free route
model and planner, the guidance banner model, the routing pack manager, and a demo app. Results,
measurements and the reasons behind each choice: `docs/experiments/09-routing-on-device.md`.

| Module | What it is | Kind |
|---|---|---|
| `:routing:core` | Route request and result model, presets format 2 (`presets.json`: route types plus options, `PresetSet.compose`), the planner (same rules as `packbuilder/routing/route_lib.py`), errors and warnings in plain words, alternates, stop order by brute force over one matrix call, the engine config, the OSRM request for Ferrostar. The engine is the `RoutingEngine` interface. | Kotlin/JVM |
| `:routing:banner` | The banner model: a pure function from a route to one banner per maneuver (main instruction with display-ready road names, following turn, lane row), rules from `banner.json`. Makes no distance text (`:core:units` does). | Kotlin/JVM |
| `:routing:packs` | Install, update and remove per-state routing packs in the one `routing.tar` the engine loads (rules of `packbuilder/routing/packs.py`, tar layout). | Kotlin/JVM |
| `:routing:valhalla` | `ValhallaEngine`: valhalla-mobile 0.6.4 behind `RoutingEngine`, opened from a config file. | Android library |
| `:routing:demo` | Demo app `io.github.spysyweeb.beamline.routingdemo`: routes one trip with the four route types and Fastest with each option, and draws them on the PA map pack. Its androidTest holds the on-device checks and measurements (`DeviceRoutingTest`: presets format 2 and the old 126 plans against pyvalhalla). | Android app |

Screens and the later Android Auto screen only display what these produce. `:routing:valhalla` is
the only module with native code.

## Prerequisites and where they live

None of these are in git; the commands below assume them under the repo root (`data/`, `.venv/`).
In a checkout or worktree without them, pass the paths of a checkout that has them (for example
`--device <other checkout>/data/routing/device`, `-Pbeamline.routingPacks=<other checkout>/data/routing/packs`,
`<other checkout>/.venv/routing/Scripts/python`); only read them, the scripts write under this
checkout's own `data/` and `android/routing/testdata/`.

| What | Path | Made by |
|---|---|---|
| Routing venv (Python 3.12, pyvalhalla 3.9.1) | `.venv/routing` | `packbuilder/routing/README.md` |
| PA and OH routing packs | `data/routing/packs/{pa,oh}` | report 01, `packbuilder/routing/build.py` |
| Device folder (PA+OH `routing.tar`, `valhalla.json`, `device.json`) | `data/routing/device` | report 01, `packbuilder/routing/packs.py` |
| PA map build (for the demo's map pack) | `data/map/packs/pa-261114-z0-15.pmtiles` | report 02 |
| Android SDK, JDK, Gradle set-up, `android/local.properties` | | `android/README.md`, report 05 |

## Data files

The rules live in data files whose single source is `packbuilder/routing/` (the Python builders).
The Android copies are made by a script and never edited:

```
python android/routing/scripts/sync_data.py --device data/routing/device    # copy
python android/routing/scripts/sync_data.py --device data/routing/device --check
```

| Source | Copy | Used for |
|---|---|---|
| `packbuilder/routing/presets.json` | `core/src/main/resources/io/github/spysyweeb/beamline/routing/presets.json` | format 2: four route types (Eco not offered), the options Avoid highways and Avoid tolls, the default, result checks |
| `packbuilder/routing/device_config.json` | `core/.../device_config.json` | engine settings of the device |
| `packbuilder/routing/banner.json` | `banner/src/main/resources/io/github/spysyweeb/beamline/routing/banner/banner.json` | banner thresholds and road-name abbreviations |
| `packbuilder/routing/guidance.json` | `../nav/session/src/main/resources/io/github/spysyweeb/beamline/nav/guidance.json` | the navigation session's thresholds (`--only guidance.json`) |

**Presets format 2** (`docs/architecture.md` 2.11): `compose(type, options)` gives the format-1 preset the
planner runs, the same in `route_lib.py` and `PresetSet.compose`: each request of the type (or each pool
candidate) gets the type's costing options with the options' on top; the fallback takes the options'
`fallback_costing_options` instead and runs when the request finds no route (every type; the route then
carries `RouteWarning.OptionFallback`, "No route to this place avoids toll roads. This route uses them.")
and past a `fallback_when` limit (not for Shortest, `option_fallback_when: false`). Avoid tolls is the hard
`exclude_tolls`. `PresetSet.resolve("fastest+avoid_tolls")` names a combination; the format-1 ids
`avoid_highways` and `avoid_tolls` alone mean Fastest with that option. The format-1 file is kept as
`testdata/reference/presets-format1.json` (`PresetSet.parseFormat1`). Python checks without an engine:
`.venv/routing/Scripts/python -m unittest packbuilder/routing/test_presets.py`.
| `packbuilder/routing/trips.json` | `testdata/trips.json` | test trips (tests only) |
| `data/routing/device/valhalla.json` (written by `packs.py`) | `core/.../valhalla_base.json` | base engine config (Valhalla 3.9.1 defaults, relative paths only) |

**Every request** (batch C1, `docs/architecture.md` 2.11, `every_request` in presets.json): `EveryRequest`
gives every route request `alley_factor` 5 and ties every stop after the first to a street
(`search_filter` residential..trunk without ramps, `search_cutoff` 300 m; on 171 the planner asks again
without them, `Calls.effective` keeps the request that answered); `RoutePlanner.arrivalSide` asks once
more with `preferred_side same` when a stop is reached on the left and keeps that route when it costs at
most 60 s more, has at most two more guidance instructions and arrives on the right
(`PlanResult.Ok.arrivalSide`; batch D: `street_side_cutoff tertiary`, so on residential streets and below
the engine ignores the side and the plain route stays). Batch D: Simpler keeps routes within 10 % and at
most 10 minutes of its quickest (`RoutePlanner.poolLimitS`), has a fourth candidate that keeps to major
roads, and the tie check and the arrival side also apply to an alternate it picked (`RoutePlanner.sameWay`). The C1 routing fix adds: `RoutePlanner.tieCheck` (the tied route stays only when at most 90 s slower
than the same request untied and no extra U-turn, `PlanResult.Ok.tieCheck`), `AddressTie` (a stop with
`Stop.street` is moved to that street when it is within 300 m: one `traceAttributes` probe, the place sent as
`display_lat`/`display_lon`; `AddressTies` keeps the points found) and `Stop.tied` false for a map point (no
stop options). `Stop.locationOptions` overrides the tie (test points on a
motorway on purpose). `RoutingEngine.traceAttributes` (valhalla-mobile `traceAttributesRaw`) serves the
session's lane warnings.
Batch E (2.11 "Every request" 6): a stop with `Stop.pointOfInterest` gets the address probe with `edge.use`
and a small probe near it (`AddressTie.lotRequest`); `AddressTie.lotPoint` (nearest driveway or parking aisle
within 80 m, at least 10 m nearer than every street) moves it into its lot (no stop options, `preferred_side`
"either": no side request); the tie check then compares with the route without the lot
(`EveryRequest.unlot`, then the ordinary check). Reference cases `lot_*` in `reference/plans.json`.
Batch E2 (docs/next-round.md item 19, architecture 2.11 "Every request" 5, 7, 8): `RoutePlanner.plan` first
decides each stop's end once with the plan's Fastest (`RoutePlanner.ends`: lot, address street, nearest
street, a pin as it is; `RoutePlanner.decide`: the first end at most 90 s slower than the next, no more
U-turns, no alley or drive-through lane in its last 300 m (`RoutePlanner.tailRequest`); the arrival side
once) and returns the decided stops in `PlanResult.Ok.ends` (`Stop.fixed`, `Stop.end`), which every other
route type and every re-route send as they are (`:nav:session` `SessionCore.decidedEnds`). The probes also
give the drive-through lanes and alleys near a point of interest (`AddressTie.leftOutRings`, `Stop.avoid`),
left out of every request as `exclude_polygons` (`ValhallaRequests.route`), asked again without them when no
route is found. The per-type `tieCheck` and `EveryRequest.unlot` are gone. Reference cases `end_same_*`,
`car_wash_street`, `drive_through_lot`; `plans.json` rows carry `end` and `end_point`.
Batch H (2.11 "Every request" 5, 7): every list of `RoutePlanner.ends` ends with the place itself, untied
(`StopEnd.PLACE`, `Stop.tied` false), so the nearest street is checked against it; the place never wins with
an alley or a drive-through lane in its last 300 m (`RoutePlanner.tailNever`, asked when it would win).
Reference case `address_out_of_reach` now ends at the place; `errors.json` also holds the requests the
planner asks for its two plans that succeed.

`:routing:core`'s tests fail when a copy differs from its source.

## Build and test

Gradle and SDK set-up as in `android/README.md`. From `android/`:

```
./gradlew :routing:core:test :routing:banner:test :routing:valhalla:testDebugUnitTest   # JVM, about 2 min
./gradlew :routing:packs:test -Pbeamline.routingPacks=<repo>/data/routing/packs         # real PA/OH packs, about 3.5 min
./gradlew :routing:demo:assembleDebug :routing:demo:assembleDebugAndroidTest
./gradlew :routing:demo:assembleRelease -Pbeamline.abiSplits=true                        # one APK per processor type
```

`:routing:packs:test` writes two tile sets under `<repo>/data/routing-packs-test/` (`pa-oh/` and
`pa-only/`) from the real packs; they are what the device tests run on.

Test data (`testdata/`, committed): `banner_cases.json` and `reference/`, recorded with pyvalhalla
3.9.1 on the PA+OH device tiles by `scripts/reference_routes.py` (needs the routing venv, see
`packbuilder/routing/README.md`):

```
.venv/routing/Scripts/python android/routing/scripts/reference_routes.py --device data/routing/device [--timings data/r3-n/reference-timings.json]
.venv/routing/Scripts/python android/routing/scripts/check_kotlin_tileset.py      # pyvalhalla on the Kotlin-written tars
```

| File | What |
|---|---|
| `reference/trips.json`, `reference/recordings.json.gz` | the old 126 plans: 21 trips x the six format-1 presets of `reference/presets-format1.json`, their alternates, four motorway-origin trips, the 1x40 matrix (the format-1 presets run by today's route_lib: since batch D the arrival side and the tie check also apply to an alternate a pool picked, so Simpler U4 and its alternates changed) |
| `reference/plans.json`, `reference/recordings-plans.json.gz` | presets format 2: 21 trips x {fastest, simpler, eco} x {none, highways, tolls, both} (Shortest left in batch D), and the cases of 2.11 (Carlisle to King of Prussia, the Turnpike and I-90 destinations, a start on the Turnpike, the C1 routing fix, batch D's Erie to Butler and Erie to Center Township, batch E's lot cases); `route_index` names a pool's alternate; the new file holds only requests the format-1 one lacks |
| `reference/banner-routes.json.gz`, `exit24-osrm.json`, `reorder.json`, `errors.json` | banner trips, the exit 24 trip in OSRM form, an 8-stop matrix, failing requests |
| `nav/exit24.json` | the golden trace of the exit 24 drive (`:nav:session`, `Exit24DriveTest`) |
| `nav/session-recordings.json.gz` | the session tests' other engine answers (`scripts/record_session.py`) |

`reference_routes.py` rewrites the committed fixtures; a rerun on the same tiles gives the same
files. Its Python timings are written only to the file `--timings` names (not committed; none without the
flag, so a run never overwrites another run's output under `data/`; in a parallel round, a file under
`data/r3-<key>/`).

## On a device

In a parallel round (`docs/architecture.md` 5.3) use the round's scripts, never `adb` or `emulator`
by hand; `device.py` runs only with `ANDROID_SERIAL` set and addresses only that device. Track N:

```
sh tools/round/gradle.sh n :routing:demo:assembleDebug :routing:demo:assembleDebugAndroidTest
sh tools/round/emulator.sh n start
. tools/round/env.sh n
D="python android/routing/scripts/device.py --build-root data/gradle/n/build --out data/r3-n/device"
$D install --only demo demo-test
$D push --tiles data/routing/device/routing.tar --pa-only data/routing-packs-test/pa-only/routing.tar
$D test --class io.github.spysyweeb.beamline.routing.demo.DeviceRoutingTest
sh tools/round/emulator.sh n stop
```

Outside a round, an emulator of its own (the AVD name is free; 3 GB RAM, 10 GB data partition for
1.8 GB of tiles and the 465 MB map pack), in Git Bash:

```
echo no | avdmanager.bat create avd -n beamline_routing -k "system-images;android-36;default;x86_64" -d medium_phone
cfg=~/.android/avd/beamline_routing.avd/config.ini
for kv in hw.ramSize=3072 hw.gpu.enabled=yes hw.gpu.mode=host disk.dataPartition.size=10G; do
  k=${kv%%=*}; grep -q "^$k=" "$cfg" && sed -i "s/^$k=.*/$kv/" "$cfg" || echo "$kv" >> "$cfg"
done
emulator -avd beamline_routing -port 5584 -no-window -gpu host -memory 3072 -no-snapshot -no-boot-anim -no-audio
export ANDROID_SERIAL=emulator-5584
```

From the repo root (`scripts/device.py` uses `am instrument` and never uninstalls, so the pushed
tiles stay):

```
python tools/fetch_android_map_pack.py --pmtiles data/map/packs/pa-261114-z0-15.pmtiles --pack-id us-pa
python android/routing/scripts/device.py install
python android/routing/scripts/device.py push --tiles data/routing-packs-test/pa-oh/routing.tar \
    --pa-only data/routing-packs-test/pa-only/routing.tar --map-pack data/android/packs/us-pa
python android/routing/scripts/device.py test          # DeviceRoutingTest: trips, alternates, config keys, errors, OSRM, banner trips
python android/routing/scripts/device.py measure --runs 3 [--drop-caches]
python android/routing/scripts/device.py screenshot data/routing-device/screens/demo.png [--from 40.4406,-80.0026 --to 40.4433,-79.9497]
python android/routing/scripts/apk_sizes.py android/routing/demo/build/outputs/apk/release/*.apk --gzip
adb emu kill
```

Results land in `data/routing-device/` (results, logs, screenshots; not committed).

## Using it from the app

```kotlin
val packs = RoutingPackManager(File(filesDir, "routing"), ValhallaEngine.VALHALLA_VERSION)
packs.install(listOf(PackSource.fromDir(downloadedPackDir)))          // off the main thread
val engine = ValhallaEngine.open(packs.engineConfigFile)                // reopen after every pack change
val presets = PresetSet.bundled()
val planner = RoutePlanner(engine, presets, packs.coverage())
val preset = presets.compose("simpler", setOf("avoid_tolls"))          // a route type with its options
when (val r = planner.plan(RouteRequest(listOf(Stop(here, headingDeg = 57, radiusM = 60), Stop(there))), preset)) {
    is PlanResult.Ok -> BannerModel.build(r.route)                      // what the banner shows per maneuver
    is PlanResult.Failed -> r.problem.userText                          // "Stop 2 is outside the installed maps. ..."
}
```

Licences of what this adds: see the report.
