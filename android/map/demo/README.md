# :map:demo

Track M's device host (application id `io.github.spysyweeb.beamline.mapdemo`, not shipped, no
network permission): the app's style (`android/app/src/main/assets`, one copy in git) composed by
`:core:packs` `StyleComposer` with the PA z14 map file, `MapLibreController`, `MapSceneMapper` and
`CameraDirector`, driven by hand-made states on the exit 24 route (`DemoStates`, no navigation
session), or by track N's golden trace of that drive (`DemoTrace`: the session's vehicle and state
after each fix), and made-up saved places at public spots in Erie (`docs/architecture-saved-places.md`
10.3).

## Set-up (emulator `beamline_m`, from the repository root)

```
sh tools/round/emulator.sh m start
sh tools/round/gradle.sh m :map:demo:assembleDebug
sh tools/round/adb.sh m install -r data/gradle/m/build/map/demo/outputs/apk/debug/demo-debug.apk
sh tools/round/adb.sh m shell am start -n io.github.spysyweeb.beamline.mapdemo/io.github.spysyweeb.beamline.map.demo.DemoActivity   # creates its files folder
sh tools/round/adb.sh m push data/map/packs-v2/pa-261114-z14.pmtiles /sdcard/Android/data/io.github.spysyweeb.beamline.mapdemo/files/pa-z14.pmtiles
sh tools/round/adb.sh m push data/map/packs-v2/us-overview-ne512-tiger2026-z0-7.pmtiles /sdcard/Android/data/io.github.spysyweeb.beamline.mapdemo/files/overview.pmtiles   # only for --ez overview true
sh tools/round/adb.sh m shell am force-stop io.github.spysyweeb.beamline.mapdemo
```

`assets/demo/exit24.json` is made from track N's `android/routing/testdata/reference/exit24-osrm.json`
and `assets/demo/trace24.json` from N's golden trace `android/routing/testdata/nav/exit24.json` by
`python android/map/demo/scripts/make_exit24.py` (`--check` compares both; when N rewrites its trace,
`--check` says DIFFERS: run the script again).

## Scenes (am start extras; the activity is singleTop, so a second `am start` reaches the running demo)

| Extra | Values |
|---|---|
| `--es scene` | `idle` (puck with cone, downtown Erie), `idle_stale`, `follow` (puck moving along the route, BROWSE_FOLLOW at z16), `choosing` (route and a grey alternative), `stops` (a numbered stop too), `drive` (the exit 24 drive at the route's speeds, 1 position per second, a 20 s halt 15 m before the left turn, then ARRIVED), `trace` (N's golden trace of the same drive, row by row: vehicle, motion, GPS state, phase, shown distance; the halt and ARRIVED as the session gave them), `moving`, `stopped`, `stale` (at `at_m`), `arrived`, `long` (a made-up trip from downtown Erie to Pittsburgh to Carlisle: straight pieces with a point every 100 m, about 4,300 points, while choosing), `long_drive` (guiding along it at 30 m/s from `at_m`, one position per second) |
| `--es theme` | `day`, `night` (a change reloads the style) |
| `--ez overview` | `true`: compose the overview map too (`files/overview.pmtiles`), as the installed app does; `false` (default): the PA pack alone. A change reloads the style |
| `--es saved` | `none`, `three` (Home at the Erie Maritime Museum, Work at the airport, a star at Millcreek Mall), `route` (the three plus a star on I-90 by the exit), `stack` (a star at Home's very point too) |
| `--ez picked` | a PICKED pin at the Erie Art Museum |
| `--ef at_m`, `--ef at_s`, `--ef speed` | metres along the route; the trace's second to start at; speed factor |
| `--ez location_log true` | also log a tapped POI label's name and where each saved marker is (rule PV7 item 6: lines naming a place only behind this flag) |
| `--ef lat --ef lon --ef zoom [--ef pitch --ef bearing]` | one camera move |
| `--es action` | `overview`, `recenter`, `mylocation`, `where` (logs each saved marker's screen position, to tap it, and each of our layers' icon-image), `reload` (switches day/night with a style reload) |

Logcat tag `MapDemo`: gestures with their pin id, camera modes, `fps frames=<n> in <ms> ...
show_max_ms=<ms>` once a second while a scene moves (frames MapLibre drew, and the longest main-thread
time of `MapLibreController.show` in that second; indicative only on a shared emulator), the style's
layer count and where our route layers went (`route lines: under <layer> (z <from>-<to>)`). The demo's
log lines follow rule PV2 (`check_private_logs.py scan --module map/demo`: 0 breaches) although the
demo is not shipped.
