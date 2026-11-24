# :demo:states

Host for track G's device checks (`docs/architecture-downloads.md` 10.3), not shipped. Application id
`io.github.spysyweeb.beamline.statesdemo`. It runs `:feature:states` exactly as `:app` will, over a
fake installer, against the development server. Owner during the feature round: track G.

- `DemoApplication` builds `GetPacks` as I's `AppGraph` will (9.4 item 1) over `DemoInstalled`, and
  sets the debug StrictMode policies `detectUntaggedSockets().penaltyLog()` (4.2 item 5) and
  `detectDiskReads().detectDiskWrites().penaltyLog()` (file work on the main thread, logged with its
  stack under the tag StrictMode; docs/architecture.md 1.2).
- `DemoActivity`: the first-run screen while `showFirstRunPicker`, then a stand-in "map" page with the
  chip, the "<name> is ready." line of `showOnMap` and a States button; the intents
  `io.github.spysyweeb.beamline.action.OPEN_STATES` / `OPEN_MAP` open the States screen or the page.
  `onStart` calls `getPacks.onAppVisible()`. The system bars' icons follow day and night
  (`WindowCompat.getInsetsController`: dark icons by day, light by night; the activity handles
  `uiMode` itself, so a `SideEffect` sets them on every change).
- `DemoInstalled` (a fake `InstalledData`): reads manifests of format 2 or 3, checks each part's size
  and SHA-256, moves a download folder's parts into `files/demo-packs/<pack_id>/` (folder install;
  `installsFromFolder = true`) or copies them from an archive (the file route). It merges no routing
  and opens nothing. It accepts manifest versions 2 and 3, so the round-2 z14 PA archive works.
  `refresh` and `remove` do their file work on `Dispatchers.IO` (GetPacks calls them from the main
  thread). An installed pack is replaced by a newer build (Update).
- The catalog address comes from `beamline.catalogUrl` in `android/local.properties` (git-ignored),
  as for `:app` (8.1); the debug network security config allows plain http only to 10.0.2.2,
  127.0.0.1, localhost and that address.

## Run

```
sh tools/round/gradle.sh g :demo:states:assembleDebug
sh tools/round/emulator.sh g start
python packbuilder/catalog/serve_packs.py --root data/r3-g/site --port 8765 --log-headers     # in the background; stop it afterwards
sh tools/round/adb.sh g install -r data/gradle/g/build/demo/states/outputs/apk/debug/states-debug.apk
sh tools/round/adb.sh g shell am start -n io.github.spysyweeb.beamline.statesdemo/io.github.spysyweeb.beamline.states.demo.DemoActivity
sh tools/round/adb.sh g shell pm clear io.github.spysyweeb.beamline.statesdemo                 # a fresh first run (emulator only)
sh tools/round/emulator.sh g stop
```

`data/r3-g/ui.sh` has small helpers for driving the UI through `uiautomator dump` (`tap_text`,
`texts`, `shot`); source it with `. data/r3-g/ui.sh`. The site `data/r3-g/site` is built with
`python packbuilder/catalog/build_catalog.py --out data/r3-g/site --archive data/android/archives-z14/us-pa-2026-11-14.tar --manifest-versions 2,3 --check`.

## Later builds, failures and the time limit (the finishing engineer's device checks)

- `python data/r3-g/make_site2.py` builds `data/r3-g/site2` (PA 202611160519: the z14 parts with the
  manifest's `created_utc` one hour later); `python data/r3-g/make_site2.py c 2026-11-16T06:19:49Z`
  builds `data/r3-g/site3` (202611160619). `data/r3-g/site4` is a catalog with no packs.
- Switch on 404: download PA from `site`, then restart the server with `--root data/r3-g/site2`
  mid-download: "A newer Pennsylvania was published. Downloading that one."
- Update: PA installed from one site, server on the next, States, "Check again" in the list's footer:
  "Update available", Update sheet, Update.
- No longer offered: mid-download, restart the server with `--root data/r3-g/site4`.
- Android 15's dataSync time limit: `sh tools/round/adb.sh g shell device_config put activity_manager
  data_sync_fgs_timeout_duration 60000`, start a download, press HOME (the limit runs only while the
  app is not in the foreground); after 60 s the service ends with "Download paused" / "Open
  Beamline to continue."; afterwards `device_config delete activity_manager data_sync_fgs_timeout_duration`.
- A file that is not a pack: push any small file named `notes.tar` to `/sdcard/Download`, "Use a file
  instead"; delete it afterwards.
