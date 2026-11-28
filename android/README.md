# Beamline Android app

A Kotlin + Jetpack Compose navigation app that works offline: the map (MapLibre Native, OpenGL ES),
routing (Valhalla on the phone), turn-by-turn guidance (the navigation session with its own
route follower), search and saved places (SQLite), all from the state packs the user
downloads once or adds from a file. Version 0.1.3 (versionCode 6).

Status: the feature round is integrated (track I, `docs/experiments/19-integration.md`): first run,
"Choose your state" and downloads, the map, search, saved places, route choice, guidance with the
turn banner and lanes, re-routing, stops, arrival, the guidance notification, Settings, About and
licences. Proved on the emulator, reviewed three ways and fixed (report 19, "Review and fixes"); the
phone step (the owner's phone) comes next. The design is
`docs/architecture.md` with `docs/architecture-downloads.md` and `docs/architecture-saved-places.md`.
Pack format: `docs/pack-format.md` (format 3).

## Modules

| Module | What it is |
|---|---|
| `:app` | The activity and the flows between screens, `AppGraph` (wires everything once per process), the map host (`PackMap`), `GuidanceService` (foreground service, type `location`), `OfflineMapHttp` (MapLibre's HTTP client that refuses every request), the manifest |
| `:core:api` | The shared kernel (frozen): places, the installed-data view, the navigation session interface, the map scene and camera types |
| `:core:units`, `:core:settings`, `:core:location` | Texts for distances, durations and clocks; settings and app state; positions, the puck's rules and the one shared GPS listener |
| `:ui:theme` | Colours, the Material theme, the screen icons |
| `:core:packs`, `:core:installed`, `:routing:packs` | The pack format, the installer (`InstalledLibrary`: one install and remove flow for map, routing, search and house numbers, published as `InstalledSet` generations, held during a trip), the merged routing tile set |
| `:routing:core`, `:routing:banner`, `:routing:valhalla` | Route model and planner, the banner model, Valhalla |
| `:nav:session` | `BeamlineNavigationSession` (the rules of guidance, re-routing, stops, arrival, resume; the simulated drive and its scripts) and its route follower, `GeometricFollower` (Ferrostar was dropped on 2026-11-17) |
| `:core:search`, `:feature:search` | Search over the installed states, recents, saved places and their file; the search screens, place sheet, saved places screen and the map pick |
| `:feature:drive` | The drive screens: route sheet, stops editor, turn banner with lanes, trip bar, status cards, the notification text |
| `:map:scene`, `:map:layers` | The scene mapper and the camera director; the MapLibre layers (route, pins, markers, puck and arrow) |
| `:feature:about` | Launcher icon, splash and reveal, the first-run notice, About and licences |
| `:core:download`, `:feature:states` | The catalog and the only network code (`PackHttp`); the "Choose your state" and States screens, `DownloadService` (type `dataSync`) |
| `:map:demo`, `:demo:about`, `:demo:states`, `:demo:search`, `:routing:demo`, `:core:search-bench` | Demo and test hosts; not shipped |

Each module's README says how it is used. Logic lives in plain Kotlin/JVM modules with unit tests;
Compose code displays state.

Assets of `:app` (`app/src/main/assets/`): `style/` (the map style: day and night JSON, glyphs,
sprites, copied from `packbuilder/map/style-bundle/` by `scripts/sync_assets.py`, committed),
`rules/` (`map-compose.json`, `position.json`, `packs.json`: rules as data), and from
`app/assets-local/` the whole-US overview map `basemap/us-base.pmtiles` (z0-7, copied in by
`scripts/sync_assets.py`, **not committed**; the build stops without it unless
`-Pbeamline.allowNoOverview=true`).

## Build

Needs the Android SDK (`python tools/fetch_android_sdk.py --accept-licenses` from the repo root:
platforms 36 and 37.0, build-tools 36.0.0, emulator, the android-36 system image) and any JDK 17+
to start the Gradle wrapper; Gradle then runs on the JDK named in `gradle/gradle-daemon-jvm.properties`.

Once, from the repo root:

```
python android/scripts/sync_assets.py          # style bundle + overview map into the app
echo 'sdk.dir=C\:/Users/<you>/AppData/Local/Android/Sdk' > android/local.properties
```

The drive letter's colon is escaped (`C\:`), in `sdk.dir` and in any other path there
(`beamline.searchDb`, `beamline.addressesDb`): lint's PropertyEscape check fails `:app:lintRelease` otherwise.

`android/local.properties` (git-ignored) may also hold the catalog address for downloads in debug
builds (`docs/architecture-downloads.md` 8.1). For the emulator and the development server below:

```
beamline.catalogUrl=http://10.0.2.2:8765/catalog/catalog.json
```

Without an address a build has downloads off ("Downloads are off in this build. Use a file
instead: a state file (.tar) copied to this phone."). A release build takes only an `https` address, so today's release builds have downloads
off; plain `http` goes only to the development server, through the debug-only network security
config. `local.properties` wins over `-Pbeamline.catalogUrl=...`.

The round's scripts (`tools/round/`, `docs/architecture.md` 5.3) run Gradle, adb and the emulator
for one track key with its own build folder (`data/gradle/<key>/build`) and emulator; outside a
round, `./gradlew` in `android/` does the same with `android/build` folders:

```
sh tools/round/gradle.sh i :app:assembleDebug                  # debug: test extras on, StrictMode untagged-socket log
sh tools/round/gradle.sh i :app:assembleRelease                # R8, signed with the build PC's debug key
sh tools/round/gradle.sh i :app:assembleRelease -Pbeamline.testExtras=true   # a release that reads the test extras
sh tools/round/gradle.sh i test --continue -Pbeamline.routingPacksWork="$(pwd -W)/data/gradle/i/routing-packs-test"
                                                               # every module's JVM tests (the routing pack tests need
                                                               # that work folder, 1.8 GB; env.sh/gradle.sh set the two
                                                               # search databases the search tests read)
```

The app's own screen tests (`app/src/androidTest`: the map chrome on its side, the bars after a
rotation, the map style sheet's bars, Settings, the hours paste field; 0.1.40) run on the emulator
with `am instrument`, never `:app:connectedDebugAndroidTest` (it uninstalls the app, its states and
saved places). Build `:app:assembleDebug :app:assembleDebugAndroidTest`, `adb install -r` both APKs
(`data/gradle/<key>/build/app/outputs/apk/`), then
`adb shell "am instrument -w io.github.spysyweeb.beamline.test/androidx.test.runner.AndroidJUnitRunner"`
(add `-e class <fqcn>` for one class; with `tools/round/adb.sh` pass the shell command as one string).

Build properties (`gradle.properties`, `-P`): `beamline.mapRenderer=opengl` (MapLibre's OpenGL ES
build, the owner's choice of 2026-11-16; the Vulkan build links glslang, which the licence gate
refuses, so it is never shipped), `beamline.testExtras`, `beamline.applicationId` (a second install
beside the first), `beamline.catalogUrl`. All APKs hold every ABI (about 97 MB; per ABI about 26-32
MB) and use the same application id and key, so one installs over another with `adb install -r`.

## Run

Emulator (in a round: `sh tools/round/emulator.sh i start`, serial `emulator-5572`, AVD
`beamline_i` with a 10 GB data partition; `stop` when done). Install and start:

```
sh tools/round/adb.sh i install -r data/gradle/i/build/app/outputs/apk/debug/app-debug.apk
sh tools/round/adb.sh i shell am start -n io.github.spysyweeb.beamline/io.github.spysyweeb.beamline.app.MainActivity
```

The first start shows the splash, the driver notice ("I understand"), then "Choose your state".

**Add a state by download** (debug build with the catalog address above): serve the catalog site
on the PC, then pick the state on the map or the list and tap Download.

```
python packbuilder/catalog/serve_packs.py --root data/catalog/site --log-headers   # 127.0.0.1:8765 = the emulator's 10.0.2.2
```

`data/catalog/site` is built by `packbuilder/catalog/build_catalog.py` from P's format-3 packs
(`data/r3-board/g-catalog-site.md`). On a USB phone the debug build is made with
`http://127.0.0.1:8765/catalog/catalog.json` and the phone reaches the PC through
`adb reverse tcp:8765 tcp:8765` (the kit's `beamline-debug-loopback.apk`).

**Add a state from a file** (any build): copy the archive to the device, let the media scanner see
it (without this line the system picker does not list a pushed file), then States → "Use a file
instead" → the file → Install:

```
adb push data/r3-packs/us-pa-2026-11-14.tar /sdcard/Download/
adb shell content call --uri content://media --method scan_file --arg /sdcard/Download/us-pa-2026-11-14.tar
```

Packs live in `Android/data/<application id>/files/packs/` and the merged routing set in
`files/routing/`; downloads use `files/downloads/` until they are installed.

**Test extras** (`docs/architecture.md` 5.4 item 12): read by every debug build, and by a release
build only with `-Pbeamline.testExtras=true`; a plain release ignores them. Add them to `am start -n
io.github.spysyweeb.beamline/io.github.spysyweeb.beamline.app.MainActivity`:

| Extra | Does |
|---|---|
| `--es dest "lat,lon,name"` `--es origin "lat,lon,heading"` | plans that trip (route sheet) |
| `--es simulate default\|wrongturn\|gaps\|jitter\|drift\|tunnel` | with `dest`: starts the simulated drive with N's script |
| `--es addstop "lat,lon,name" --ef addstop_at_m 1000` | adds a stop when the simulated car passes that distance |
| `--es units metric` (any other value: miles), `--es style day\|night` (any other value: Automatic) | changes the setting (also in a running app) |
| `--ef lat <deg> --ef lon <deg> --ef zoom <z>` | one camera move (also in a running app) |
| `--es private_data test` | cold start only: saved places, recents and the trip in the `test/` folder of `no_backup` (rule PV7) |
| `--ez location_log true` | cold start only: the puck's position line in the log (rule PV7) |
| `--ez reset_notice true` | cold start only: the first-run notice is shown again |

The section 8 trip (`docs/architecture.md`): `--es origin "42.028694,-80.128884,57" --es dest
"42.053308,-80.079579,Downs Drive" --es simulate default`. The intents with the actions
`io.github.spysyweeb.beamline.action.OPEN_STATES` and `...OPEN_MAP` open the States screen or the
map in every build (the download notifications use them); OPEN_STATES does nothing until the driver
notice is accepted (the States screen fetches the list of states when it opens).

Location for guidance: precise location is needed to Start; with an approximate-only grant the route
sheet's "Allow precise location to start guidance" asks again (or, once Android stops asking, points to
the app's settings). A simulated drive needs no location; without a grant its notification comes from
a plain, not foreground, service.

Location on the emulator: `adb emu geo fix <lon> <lat>`; a GPX along a route with
`python android/scripts/geo_route.py <gpx> --interval 1` (guidance uses only `gps` fixes; the
emulator's fixes have speed 0). `cmd uimode night yes|no` switches a map style set to Automatic.

**The phone kit** (`data/android/apks/`, not in git, described with sizes, SHA-256 and the
commands above in `data/android/phone-kit.json`): `beamline-release.apk`,
`beamline-release-testextras.apk` and `beamline-debug-loopback.apk` (catalog address
`http://127.0.0.1:8765/catalog/catalog.json`, reached over USB with `adb reverse tcp:8765 tcp:8765`).

**The owner's phone** (`docs/architecture-saved-places.md` PV7): every session starts cold with
`--es private_data test`; never clear the app's data (one clearing, at the start of the phone step
after integration, was allowed once by the owner on 2026-11-16), uninstall, `run-as`, pull the app's
files or grep the log for the owner's places.

## Checks

From the repo root (`docs/architecture.md` 5.4 item 14): the JVM tests above;
`python android/scripts/sync_assets.py check`; `python android/routing/scripts/sync_data.py --check`;
`python android/scripts/copy_search_rules.py --check`; `.venv/art/Scripts/python
android/map/layers/scripts/sync_art.py --check`; `python packbuilder/catalog/build_state_shapes.py
--check`; the licence gate (`.venv/art/Scripts/python android/feature/about/scripts/notices.py resolve
--key i`, then `notices.py --check --deps data/r3-i/notices`); `python android/scripts/check_network_rule.py
manifest <merged manifest> --variant debug|release`, `scan`, `privacy`; `python
android/scripts/check_private_logs.py scan`, `manifest <merged manifest> <apk>`, `logcat <log files>`;
`python tools/check_contracts.py`. The merged manifest is
`<build root>/app/intermediates/merged_manifest/<variant>/process<Variant>MainManifest/AndroidManifest.xml`.

## Scripts (`scripts/`, run from the repo root)

| Script | Does |
|---|---|
| `sync_assets.py [style\|basemap\|check]` | copies the style bundle and the overview map into the app; `check` says whether the copies still match |
| `check_network_rule.py manifest\|scan\|privacy` | the network rule's merged-manifest check, source lint and private-address check |
| `check_private_logs.py scan\|manifest\|logcat\|selftest` | the logging and backup rules PV2-PV4 |
| `copy_search_rules.py [--check]` | the search rules copied from `packbuilder/` |
| `device_ui.py` | reads the screen, taps by text, screenshots (emulator checks) |
| `geo_route.py`, `make_route_gpx.py` | drives the emulator's GPS along a GPX; a timed GPX along one road |
| `read_pack.py` | the plain-Python pack reader of `docs/pack-format.md` (`--verify`) |
| `measure.py` | start-up, memory and frame measurements |
| `push_pack.py`, `pack_variants.py` | older developer shortcuts for format 2 (not adapted to format 3) |
