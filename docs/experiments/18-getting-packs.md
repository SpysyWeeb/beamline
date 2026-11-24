# Experiment 18: getting state packs in the app (track G, round 3)

Date: 2026-11-16. Track G of the feature round (`docs/architecture.md` 5.1 "G: getting packs"; the
companion contract `docs/architecture-downloads.md`, which has every text, rule, format and test).
Built in two stages, reviewed twice, then finished by a third engineer. The first engineer wrote
`:core:download`, `packbuilder/catalog/`, `docs/catalog-format.md` and most of `:feature:states`,
`:demo:states` and `android/scripts/check_network_rule.py`, and ran the first device checks. The
second engineer re-ran every check, closed what stage 1 left open, fixed three faults found on the
device, ran the remaining device checks of 10.3 and wrote this report and the four READMEs. The
finishing engineer checked each review finding against the code, fixed the ones that were right,
found and fixed four more faults on the device, and added a device test of the screen-reader order
(section 12, "Review and fixes"). Scratch outputs (not in git): `data/r3-g/` (logs, screenshots,
the demo sites, `PROGRESS.md`), `data/catalog/site` (the site for I), `data/gradle/g/` (builds).

**Machine load:** six other tracks were building and running emulators on this PC the whole time.
Every time below is one run, wall clock; treat it as indicative only.

## Result in one paragraph

The phone side and the PC side of in-app downloads exist and work end to end on the emulator against
the development server. `:core:download` (JVM) parses and checks `beamline-catalog/1`, enforces the
network rule N1-N5 in the one class that opens connections (`PackHttp`), caches the list, and runs
the download state machine of 6.4 with resume by `Range` + `If-Match`, two-step hashing, gzip
unpacking, the free-space formulas, process-death recovery with its breaker, the switch to a newer
build on a 404, and the hand-off to the installer: 62 JVM tests, one or more per row of 10.1,
against a local Range server, 0 failed. `:feature:states` has the first-run "Choose your state"
screen and the States screen with the texts of section 1, the drawn US map with its tap rules, the
foreground `dataSync` service with its notifications, the network watcher, the tagging socket
factory and the file route: 37 JVM tests (10.2 and the review's fixes), 0 failed, and a 4-test
device test of the order a screen reader is given, 0 failed. On `beamline_g` the demo app downloaded
and installed Pennsylvania from the first-run screen and the States screen, switched to a newer
build when the old one disappeared from the server, updated an installed build, ended cleanly when
Android 15's `dataSync` time limit stopped the service, and resumed after a cut, `kill -9`, `am
force-stop`, a Pause, a lost network and a stopped server, each time with a `Range` request for
exactly the bytes on disk. No byte left outside the downloader's socket tag beyond 1,520 received
and 120 sent in a 1.38 GB session, and no "Untagged socket" line. StrictMode on the demo found no
disk access on the main thread from `:feature:states` or `:core:download`. The PC tools pass their 20
tests; `data/catalog/site` offers P's format-3 PA and OH for I; `check_network_rule.py` `scan`,
`privacy` and the demo's `manifest` pass on the tree. Not done: TalkBack itself (not on the
emulator image; what it is given is tested instead), the house-number flows on a device, and seven
of the failure views on a device (their texts are JVM-tested).

## 1. What was built

| Area | Files | What |
|---|---|---|
| Catalog format | `android/core/download/.../Catalog.kt` (`CatalogParser.kt`), `docs/catalog-format.md` | `beamline-catalog/1` and the reader rules of 2.3: size, path rule and scope (one bad path refuses the list), per-entry checks (BROKEN, OLD_FORMAT, NEEDS_NEWER_APP), licence limits |
| Address and network rule | `net/CatalogAddress.kt` (`net/AddressRules.kt`), `net/PackHttp.kt`, `net/NetRules.kt` | scope, `redirectAllowed` (3.1); `PackHttp` is the only file with OkHttp: own resolver with the public-address rule (local catalog host excepted), redirects by hand (at most 5), the exact N3 headers, no cookies, no cache, `Content-Encoding` refused, `ClockProblem` from the cause chain, `cancelAll` |
| Catalog cache | `CatalogSource.kt` (`CatalogStore.kt`) | `catalog.json` + `catalog-meta.json`, `If-None-Match`, the 6-hour rule, a cache from the future refreshed, another URL's cache ignored, a lower serial used and logged |
| Engine | `DownloadEngine.kt` (`EngineCore.kt`, `JobRecord.kt`), `Job.kt`, `Space.kt` | the state machine of 6.4; `job.json` (`beamline-download-job/1`) written atomically on every change of phase or reason; per-part transfer files, verified and unpacked; back-off 2, 5, 10, 30, 60 s; 8 attempts without progress; network waits of at most 6 h; recovery and the two-deaths breaker; switch to a newer build on 404 |
| Hand-off | `PackHandoff.kt` (`TarStream.kt`) | `FolderHandoff` (P's move install) and the archive stand-in fed a ustar made on the fly (`VirtualTar`, byte-equal to Python's `tarfile` for the fixture) |
| Rules | `DownloadRules.kt` (`RulesFile.kt`), `packbuilder/catalog/download_rules.json` | parse and bundled; the module's copy is compared with the source by a test |
| Controller | `android/feature/states/.../GetPacks.kt` (`GetPacksCore.kt`, `UiStore.kt`, `model/FirstRun.kt`, `model/JobSwitch.kt`, `model/Continuing.kt`) | the catalog source, the engine, `getpacks/ui.json`, the first-run rule, the map chip, show-on-map, the screens' actions (one at a time on `Dispatchers.IO`), the in-process watcher for installs held by a trip or a busy installer, the switch and "Continuing…" lines |
| Texts | `model/StatesPresenter.kt`, `model/Texts.kt` | pure: every text of section 1, statuses, the chip, the notifications |
| Screens | `StatesScreen.kt` (`StatesScreenImpl.kt`: `StatesBody` and the sheets and dialogs), `map/StatesMap.kt` | first run and States: map card ("Whole country" under it), search field, list, sheets, dialogs, chooser, progress view, file sheet, fixed bottom bar (a traversal group read right after the title) |
| Map | `map/StateShapes.kt` (`map/ShapesCode.kt`), `assets/states/us_states.json`, `packbuilder/catalog/build_state_shapes.py` | 51 states, 9,437 points, 65,206 bytes, from Natural Earth 5.1.2 (public domain); Albers frames; the hit test |
| Service | `DownloadService.kt` (`DownloadServiceBody.kt`), `res/drawable/beamline_states_download.xml` | foreground at once (id 4210, `dataSync`), `engine.run()` on IO, notification at most once per second, partial wake lock while bytes move, end notification, `onTimeout` posts its own end |
| Platform | `AndroidNetworkWatcher.kt`, `TaggingSocketFactory.kt`, `src/main/AndroidManifest.xml` | 5.2 by kind of catalog host; tag `0x0B1A` on every socket, bound to the Wi-Fi network for a local host; the manifest of 5.3 |
| Device test | `src/androidTest/` (`ReadingOrderTest.kt`, a do-nothing accessibility service, its XML) | the order a screen reader is given, read from the platform's traversal links |
| Demo | `android/demo/states/` | `DemoApplication` (GetPacks as I will build it, StrictMode untagged-socket and disk policies), `DemoActivity` (bar icons by day and night), `DemoInstalled` (a fake `InstalledData` that verifies and moves files) |
| PC tools | `packbuilder/catalog/` | `catalog_format.py`, `build_catalog.py`, `serve_packs.py`, `catalog_client.py`, `build_state_shapes.py`, `tests/` |
| Checks | `android/scripts/check_network_rule.py` | `manifest`, `scan`, `privacy` (4.2 items 1 and 2) |
| READMEs | `android/core/download/README.md`, `android/feature/states/README.md`, `android/demo/states/README.md`, `packbuilder/catalog/README.md` | what each piece is, how I wires it, how to run the tests |

## 2. How it is proved (commands and results, 2026-11-16, final run after the review fixes)

All from the repository root in Git Bash.

| Check | Command | Result |
|---|---|---|
| JVM tests of 10.1 | `sh tools/round/gradle.sh g :core:download:test --rerun` | 62 tests, 0 failed, 0 skipped: CatalogParsingTest 8, CatalogSourceTest 5, AddressRulesTest 7, NetworkRuleTest 8, EngineTransferTest 14, EngineStateTest 13, VirtualTarTest 4, RulesAndSpaceTest 3 |
| JVM tests of 10.2 | `sh tools/round/gradle.sh g :feature:states:testDebugUnitTest --rerun` | 37 tests, 0 failed, 0 skipped: StateShapesTest 5, StatesPresenterTest 17, FirstRunTest 7, JobSwitchTest 4, UiStoreTest 2, ContinuingTest 2 |
| Device test (reading order) | `sh tools/round/gradle.sh g :feature:states:connectedDebugAndroidTest` (emulator up) | ReadingOrderTest 4 tests, 0 failed (run twice, the second on the final code); the orders: `data/r3-g/reading-order-s3-final.txt` |
| Demo build | `sh tools/round/gradle.sh g :demo:states:assembleDebug :feature:states:assembleDebugAndroidTest` | BUILD SUCCESSFUL |
| Counting the results | `python data/r3-g/count_tests.py` | reads `data/gradle/g/build/*/test-results/` |
| PC tools | `python -m unittest discover packbuilder/catalog/tests` | Ran 20 tests, OK |
| Shapes asset | `python packbuilder/catalog/build_state_shapes.py --check` | `check ok: ... (65,206 bytes) equals a fresh build` |
| Demo site | `python packbuilder/catalog/build_catalog.py --out data/r3-g/site --archive data/android/archives-z14/us-pa-2026-11-14.tar --manifest-versions 2,3 --check` | stage 1: 66 s; PA 202611160419, 548.5 MB to download, 1,033.8 MB unpacked, check ok |
| Later builds for the device checks | `python data/r3-g/make_site2.py` and `python data/r3-g/make_site2.py c 2026-11-16T06:19:49Z` | site2: PA 202611160519 (61 s); site3: PA 202611160619 (58 s); both `check ok`. `data/r3-g/site4`: the same catalog with no packs |
| Site for I | `python packbuilder/catalog/build_catalog.py --out data/catalog/site --pack data/r3-packs/us-pa --pack data/r3-packs/us-oh --check` | stage 2: 3 min 38 s; PA 202611160853: 680.9 MB / 1,322.0 MB; OH 202611160920: 614.3 MB / 1,196.4 MB; `check ok` |
| Reference client on that site | `catalog_client.py get http://127.0.0.1:<port>/catalog/catalog.json OH --out <folder>`, then `python android/scripts/read_pack.py <folder> --verify` | stage 2: 614.3 MB in 15.0 s over loopback; "problems: none", "checksums ok" on all five parts |
| Contracts | `python tools/check_contracts.py` | 39 contract files, 20 excerpts, 5 data files "all the same"; 39 frozen files unchanged |
| Private logs | `python android/scripts/check_private_logs.py scan --module core/download --module feature/states --module demo/states` | 36 files, 1 marked line, 0 breaches |
| Network rule, privacy | `python android/scripts/check_network_rule.py privacy` | 0 private IPv4 literals |
| Network rule, scan | `python android/scripts/check_network_rule.py scan` | `scan: 0 finding(s)` |
| Network rule, demo manifest | `python android/scripts/check_network_rule.py manifest data/gradle/g/build/demo/states/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml --variant debug --host demo` | `manifest (demo, debug): 0 problem(s)` |

## 3. Device checks of 10.3 (`:demo:states` on `beamline_g`, API 36, development server)

Set-up: `sh tools/round/emulator.sh g start`; `python packbuilder/catalog/serve_packs.py --root <site> --port 8765 --log-headers [--throttle-kbps 16000]` in the background; the debug APK installed with `adb.sh g install -r`; `pm clear` for a fresh first run (emulator only). Server logs: `data/r3-g/serve-*.log`; logcat: `data/r3-g/logcat-s2-a.txt` (stage 2), `data/r3-g/logcat-s3-{a,b,c}.txt` (finishing engineer, 04:55-05:31); screenshots: `data/r3-g/shots/` (stage 1: `01`-`12`; stage 2: `s2-*`; finishing engineer: `s3-*`). The throttle (16,000 kbit/s, about 2 MB/s per connection) was used so long checks had a download to work on; it is the server's rate, not a measurement of the app.

| 10.3 item | What was done | What was seen | State |
|---|---|---|---|
| First-run screen | fresh `pm clear`, start | title, intro, the drawn map, "Search states", "Available: Pennsylvania 548 MB download · data from Nov 14, 2026", "Not available yet" rows, the fixed bar "Use a file instead" / "Not now"; one `GET /catalog/catalog.json` with exactly User-Agent `Beamline-Packs/1`, Accept-Encoding `identity`, Host, Connection. Shots `s2-01`, `s3-01` (day, dark bar icons), `s3-02` (night) | done |
| Map tap on PA | tap at PA's label point | the map zooms to PA, the row is highlighted, the sheet of 1.1 step 4 (`s2-04`, `s2-18`, `s3-03` night); after Cancel "Whole country" sits under the map with every label free (`s3-04` night, `s3-05` day) and brings the whole map back (`s3-06`) | done |
| Chooser near DC | tap at DC's label point at the fit zoom | "Which one?" with DC, VA, MD, WV, nearest first (`s2-02`, `s2-37`); DC's sheet (`s2-03`); Maryland's nearby line (`s2-38`) | done (stage 2) |
| Sheet on Wi-Fi | States screen, PA row | `s2-18` | done (stage 2) |
| Sheet on mobile data (emulator cellular only) | `svc wifi disable` | "You're on mobile data. This download uses 548 MB of your data plan." [Download on Wi-Fi only] [Use mobile data] (`s2-04`) | done (stage 2) |
| A PA download with the notification steps of 1.1 | first run and States screen | stage 2: every notification step (`s2-09`, `s2-35`, `s2-10`, `s2-23`, `s2-14`); installed by the fake installer, `downloads/` empty afterwards; picker closed by itself; "Pennsylvania is ready." The finishing engineer ran four more to the end (the switched build, the Update, one from "Add a state", and one resumed after force-stop, the last on the final code) | done |
| `--cut-after` | stage 1 only, `--cut-after 100000000` (request 3 in `data/architecture/requests/g.md`) | routing and map each cut at 100,000,000 bytes and continued with `Range: bytes=100000000-` (206 with exactly the rest) | done in stage 1, not re-run |
| Process death (`kill -9`) | stage 1 only | reopening continued with `Range: bytes=74009536-`, 206 with 166,421,245 bytes (= 240,430,781 − 74,009,536) | done in stage 1, not re-run |
| `am force-stop`, then reopen, "Continuing the download…" | finishing engineer, final code, throttled | force-stop at 05:25:19 with `.transfer-routing` 3,014,656 bytes: `Range: bytes=3014656-` 206. "Continuing…" was not visible on that cold start (fault 7 of section 12, fixed). After the fix: install + force-stop with `.transfer-routing` 203,489,280 bytes, reopen, six screenshots 0.7 s apart: "Continuing the download…" in frames 2-4 (`s3-39-reopen-strip`), then "275 MB of 548 MB · about 3 min left"; `Range: bytes=203489280-` 206 with 36,941,501 bytes (= 240,430,781 − 203,489,280); installed. The reproduce review also saw the line with the stage-2 code | done |
| `dumpsys deviceidle force-idle` during a download | stage 2, deep idle enabled first | IDLE for 150 s, the transfer file grew about 61 MB every 30 s | done (stage 2) |
| Server stopped 10 minutes | stage 2 | "Couldn't reach the download server" after 8 attempts, every byte kept (`s2-21`, `s2-22`); reopening: "Continuing the download…" (`s2-24-…-3`), `Range: bytes=89718784-` | done (stage 2) |
| Pause from the notification | stage 2 | "Download paused · 79 %" / "Open Beamline to continue." stays (`s2-10`) | done (stage 2) |
| No "Untagged socket" line | logcat, StrictMode `detectUntaggedSockets()` | 0 lines in stage 2's session and in the finishing engineer's three windows (6,293, 8,040 and 39,935 lines); no FATAL EXCEPTION | done |
| `dumpsys netstats detail` under tag `0xb1a` | stage 2 | untagged 1,520 bytes received and 120 sent of 1.38 GB, within 4.2 item 5's 16 KB (not traced to a socket) | done (stage 2) |
| TalkBack reaches the bottom bar right after the title and reads the rows and the progress | `ReadingOrderTest` (device test; TalkBack is not on the image) | first run: "Choose your state", "Use a file instead", "Not now", intro, map, "Search states", "Available", "Pennsylvania, 548 MB download · data from Nov 14, 2026", … ; during a download the progress ("Downloading Pennsylvania", the bar, "238 MB of 548 MB · about 3 min left", the hint, Pause, Cancel) comes after the bar and before the map, and the row reads "Pennsylvania, Downloading · 43 %"; States: "States", "Use a file instead", map, …, "On this phone", "Pennsylvania, On this phone · data from Nov 14, 2026 · 1.0 GB, Remove". Control: a bar Row without `isTraversalGroup` (the code before the review) is read after the list | what TalkBack is given: done; TalkBack itself: not run |
| Screenshots of every view, day and night, looked at | lists in 3.1 | each one listed was opened and looked at | partly: the views of section 7 were not shown |
| File route ("Use a file instead") | stage 2: the z14 archive; finishing engineer: a file that is not a pack | stage 2: picker, file sheet, "Installing Pennsylvania", ready (`s2-27`-`s2-32`). Not a pack: "notes.tar can't be installed" / "Nothing was changed on this phone." [Details] [OK] (`s3-22` day, `s3-24` night), Details "This file is not a Beamline state pack." (`s3-23`); log line names only the exception class | done |
| Cancel on the first run | stage 2 | "Stop downloading Pennsylvania?" / "The 81 MB downloaded so far are deleted."; Delete brings the picker back | done (stage 2) |

Beyond the list of 10.3 (views and flows the reviews asked to see on a device):

| Check | What was done | What was seen |
|---|---|---|
| Switch on 404 (1.4) | PA downloading from `site` (PA 202611160419); at 225 MB the server restarted on `site2` (202611160519) | `GET /us-pa-202611160419/routing.tar.gz Range=bytes=210600736- 404`, then `GET /catalog/catalog.json`, then the new build's manifest and parts; "A newer Pennsylvania was published. Downloading that one." in the progress view (`s3-07` day, `s3-08` night); installed the 05:19 build; `downloads/` empty |
| Update (1.3) | PA 202611160519 installed; server on `site3`; States, "Check again" | "Update available · data from Nov 14, 2026 · 548 MB download" [Update] [Remove] (`s3-10`; the Update button was off at first: fault 5 of section 12, `s3-11-update-disabled-fault-day`); sheet "Update Pennsylvania" / "New OpenStreetMap data of Nov 14, 2026. Yours is from Nov 14, 2026." / "Download: 548 MB" / "Your Pennsylvania keeps working until the update is installed." [Update] [Not now] (`s3-12` day, `s3-13` night); installed the 06:19 build; the row back to "On this phone · … · 1.0 GB" (`s3-16`) |
| Android 15's 6-hour limit (1.4, `onTimeout`) | `device_config put activity_manager data_sync_fgs_timeout_duration 60000`, the Update download (throttled), then HOME | nothing for 165 s while the demo was in the foreground (the limit counts only outside it); 60 s after HOME: `FGS (dataSync) timed out` (ActivityManager), the service gone, notification id 4211 "Download paused" / "Open Beamline to continue." (`s3-14`), no crash. Reopening: "Continuing the download…" (`s3-15`), `Range: bytes=173023680-` 206 with 71,679,834 bytes (= 244,703,514 − 173,023,680), installed. The setting was deleted afterwards |
| No longer offered (1.4) | PA downloading from `site3`, server restarted on `site4` (no packs) | after a "Connection lost" retry (`s3-25`): `404`, `GET /catalog/catalog.json`, "Pennsylvania is no longer offered" / "The list of states changed. Check the list again." [Check again] [OK]; the job folder held only `job.json`; OK removed it. Final code: no bar, row "Download failed" (`s3-36` night, `s3-37` day; before the fix `s3-26`, `s3-27`: fault 6) |
| The list cannot be fetched (1.4) | `pm clear`, server stopped | "Can't load the list of states right now. Try again later, or use a file." [Try again] (`s3-28` day, `s3-29` night); Arizona's sheet "Can't load the list of states right now." [Try again] [Use a file instead] (`s3-30`) |
| No network on the first run (1.4) | `pm clear` with `svc wifi disable` and `svc data disable` | "You're offline. Connect to the internet to download your state, or use a file." with the debug line "no Wi-Fi or Ethernet network" (`s3-31`); Alabama's sheet "Connect to the internet to see the download for Alabama." [Try again] [Use a file instead] (`s3-32` day, `s3-33` night) |
| Network lost half way (1.4) | both networks off mid-download | "Waiting for a connection" / "143 MB of 548 MB. The download goes on when you're online." (`s3-18` day, `s3-19` night); went on by itself when they came back |
| Paused and Cancel | Pause, Continue, Pause, Cancel, Delete on the States screen | "Download paused" / "65 MB of 548 MB. Your progress is kept." (`s3-17` day, `s3-20` night); "Stop downloading Pennsylvania?" / "The 254 MB downloaded so far are deleted." (`s3-21`); `downloads/` empty after Delete |
| Main-thread file work (contract review 4) | demo's StrictMode `detectDiskReads().detectDiskWrites().penaltyLog()`; logcat captured in three windows: first run to the switch (`logcat-s3-a`); Remove, Download, Pause, Continue, network loss, Cancel (`logcat-s3-b`); 05:14-05:31: a download, install and Remove, no longer offered twice, can't load, offline, force-stop and the last download (`logcat-s3-c`). The Update and time-limit part and the file route were not in a captured window | 0 violations with a frame of `:feature:states` or `:core:download`. All others come from the demo host building GetPacks's inputs at each process start (`getExternalFilesDir`, `filesDir`, the frozen `SettingsStore.load` and `UnitRules.bundled`: I's `AppGraph` will do the same), and, before its fix, from the demo's own `DemoInstalled.remove` (fault 8; after the fix a whole download, install and Remove gave 0) |

### 3.1 Screenshots (`data/r3-g/shots/`)

Stage 2, day: `s2-01` first run, `s2-02` chooser near DC, `s2-03` DC sheet, `s2-04` PA sheet on mobile data, `s2-05` progress view behind the permission dialog, `s2-06` waiting for Wi-Fi, `s2-08` downloading, `s2-09` ongoing notification, `s2-10` notification after Pause, `s2-11` paused view, `s2-12-after-force-stop-3` first frame after a cold start, `s2-13` after install (first run), `s2-14` "is ready" notification, `s2-15` States with PA, `s2-22` couldn't reach the server, `s2-23` its notification, `s2-24-reopen-after-server-3` "Continuing the download…", `s2-25` ready banner, `s2-27`/`s2-28` the system file picker, `s2-29` file sheet, `s2-31` installing from a file, `s2-32` after the file install, `s2-33` notification right after Allow, `s2-34` cancel dialog, `s2-35` waiting-for-Wi-Fi notification after Allow. Night: `s2-16`, `s2-17`, `s2-18`, `s2-19`, `s2-20`, `s2-21`, `s2-26`, `s2-30`, `s2-36`, `s2-37`, `s2-38`. Stage 1 night: `10`, `11`, `12`.

Finishing engineer, day: `s3-01` first run (dark bar icons), `s3-05` zoomed map with "Whole country" under it, `s3-06` after it, `s3-07` switched to a newer build, `s3-09` after that install, `s3-10` "Update available", `s3-11-update-disabled-fault-day` (the fault), `s3-12` Update sheet, `s3-14` the time-limit notification, `s3-15` "Continuing…" after it, `s3-16` after the update, `s3-17` paused, `s3-18` waiting for a connection, `s3-22` file not a pack, `s3-25` connection lost, `s3-26-…-before-fix-day`, `s3-28` can't load the list, `s3-30` its sheet, `s3-31` offline, `s3-32` offline sheet, `s3-37` no longer offered, `s3-38-force-stop-first-frame-day`, `s3-39-reopen-1`…`6` and `s3-39-reopen-strip`. Night: `s3-02` first run, `s3-03` PA sheet, `s3-04` zoomed map, `s3-08` switched, `s3-13` Update sheet, `s3-19` waiting for a connection, `s3-20` paused, `s3-21` cancel dialog, `s3-23` Details of a file that is not a pack, `s3-24` its banner, `s3-27-…-before-fix-night`, `s3-29` can't load the list, `s3-33` offline sheet, `s3-35` a sheet opened at night, `s3-36` no longer offered.

Not taken on a device (texts JVM-tested only): list mismatch, rejected by the installer, not enough space, clock, damaged, keeps stopping, bad response, the house-number sheets and flows, the mobile-data sheet and the waiting-for-Wi-Fi view by night.

What the screenshots show that is not ideal (demo only, or minor): by night the 3-button navigation bar keeps a light background with dark buttons (readable; `isAppearanceLightNavigationBars` did not change it on this image); a sheet that was open while the system switched to night keeps the day icons in its status bar (`s3-13`, `s3-33`), while a sheet opened at night has light icons (`s3-35`); during a file install on the first run the bar still offers "Not now"; a failed list fetch that is followed by losing the network keeps saying "Can't load the list…" until the screen is opened again (both are 1.4 texts; the offline banner shows on a fresh open).

## 4. Measurements

| What | Value | How |
|---|---|---|
| Catalog of `data/r3-g/site` | 2,095 bytes | server log |
| PA v2 (demo site) | 548,481,239 bytes to download (548 MB on screen), 1,033.8 MB unpacked | `build_catalog.py`, the sheet |
| PA v3 / OH v3 (`data/catalog/site`) | 680.9 MB / 614.3 MB to download; 1,322.0 MB / 1,196.4 MB unpacked; house numbers 132.4 MB / 80.5 MB; largest transfer file OH's map, 252,304,213 bytes | `build_catalog.py`, `catalog.json` |
| Building `data/catalog/site` | 3 min 38 s | `time` |
| Building a later z14 PA build (`make_site2.py`) | 61 s and 58 s | `time` |
| Reference client, OH v3 over loopback | 15.0 s for 614.3 MB | `time` |
| Retries with the server gone | first failure to PAUSED(SERVER_UNREACHABLE): 5 min 4 s (8 attempts) | logcat (stage 2) |
| `dataSync` time limit set to 60 s | timed out 60 s after the demo left the foreground (HOME 05:06:22, timeout 05:07:22); none in 165 s in the foreground | logcat |
| Cold start | `Displayed … DemoActivity +1s657ms` (stage 2); a screenshot taken 1.5 s after `am start` (plus the capture's own time) after a force-stop shows only the title and intro (`s3-38`) | logcat, screenshot |
| Shapes asset | 65,206 bytes, 51 states, 9,437 points | `build_state_shapes.py --check` |
| Download rate of the app, unthrottled | about 4-5 MB/s (stage 1, shared emulator; indicative only) | stage 1 |
| Untagged bytes of the demo's uid | 1,520 received, 120 sent (of 1.38 GB received) | netstats (stage 2) |

## 5. What G adds to the runtime classpath (with SPDX licences)

G's own additions, through `:feature:states` (and `:core:download` under it):

| Artifact | Version | SPDX | Why |
|---|---|---|---|
| `com.squareup.okhttp3:okhttp` | 5.3.2 | Apache-2.0 | `PackHttp` (`:core:download`) |
| `com.squareup.okhttp3:okhttp-android` | 5.3.2 | Apache-2.0 | OkHttp's Android artifact |
| `com.squareup.okio:okio`, `com.squareup.okio:okio-jvm` | 3.16.4 | Apache-2.0 | OkHttp's I/O |
| `androidx.lifecycle:lifecycle-service` | 2.11.0 | Apache-2.0 | `DownloadService : LifecycleService`; only `:feature:states` uses it |

Correction after the reproduce review: this section used to list lifecycle-service among entries other
modules already use; `grep` of the build files shows only `android/feature/states/build.gradle.kts`.
Its own dependencies (`lifecycle-runtime` and below) are already on the classpath through
`lifecycle-runtime-compose` and `activity-compose`, which other modules use. Everything else on
`:feature:states`'s release runtime classpath (105 artifact versions,
`data/r3-g/feature-states-release-classpath.txt`, from `sh tools/round/gradle.sh g
:feature:states:dependencies --configuration releaseRuntimeClasspath`) comes from catalog entries
other modules already use: `kotlinx-serialization-json:1.13.0`, `kotlinx-coroutines-android:1.11.0`,
Compose (BOM 2026.09.00), `androidx.core:core-ktx:1.19.1`, `androidx.activity:activity-compose:1.13.0`,
`androidx.lifecycle:lifecycle-runtime-compose:2.11.0` and their transitive androidx, Kotlin and
JetBrains libraries, plus `com.google.guava:listenablefuture:1.0` and `org.jspecify:jspecify:1.0.0`;
all Apache-2.0 as published (not re-read POM by POM). A's gate covers all of it: its
`data/r3-a/notices/deps-runtime.txt` resolves `:feature:states` (lifecycle-service 2.11.0 included)
and `notices.json` groups AndroidX under one Apache-2.0 entry; OkHttp and Okio have their own entries.
Board note `data/r3-board/g-classpath.md`. The device test's artifacts (`androidTestImplementation`)
do not ship. G added no native library and no package to a project venv.

## 6. Faults found and fixed in stage 2

1. **The download notification stayed hidden after the permission was granted.** The service now
   re-posts the current notification when notifications become allowed (`s2-35`).
2. **"Continuing the download…" was never visible** (it showed only during CHECKING). It then stayed
   until bytes moved, at least 3 s from `onAppVisible` (refined again in section 12, fault 7).
3. **The file route closed the first-run picker as soon as a file was picked.** The choice is now
   written when the install starts; the picker stays with the installer's progress; a refusal shows
   "<name> can't be installed" with Details.
4. **Blank first frame of the screen.** The screen's state is made on `Dispatchers.IO`; the title
   and intro show at once.
5. Stage 1's "java.util.logging lines do not reach logcat" was not a fault (tag `Beamline.download`).
6. `showFirstRunPicker` is a pure function (`model/FirstRun.kt`) with the 10.2 test.

## 7. Not done, not verified, failed

- **TalkBack itself** (10.3, 6.7): not run; the round's emulator image has none. What TalkBack is
  given (the platform's traversal links) is checked by `ReadingOrderTest` on the device; TalkBack's
  own speech and gestures are not. Request 6 in `data/architecture/requests/g.md` asks to write
  that into 10.3; I's phone run (10.4) can run TalkBack.
- **The house-number flows on a device** (Add/Remove house numbers, a sheet with the box): not run.
  The demo's fake installer adds no optional parts and the z14 demo pack has no house-number part.
  JVM-tested (presenter; engine row "Add house numbers" of 10.1). I's 10.4 row 12 covers them on P's
  installer.
- **Seven failure views on a device**: list mismatch, rejected by the installer, not enough space,
  clock, damaged, keeps stopping, bad response. JVM-tested (engine and presenter). The other failure
  views were seen (section 3).
- **`--cut-after` and `kill -9`** were not re-run after stage 1 (stage 1's evidence stands; resume
  after force-stop, the time limit, Pause and the stopped server was seen on the final code).
- **Whether the stage-2 `onTimeout` really lost its notification** was not tried on the device: the
  fix was made from the reading of the code and then seen working.
- **`GetPacksCore`'s constructor** still reads the bundled rules resource on the caller's thread (a
  classpath resource; StrictMode did not flag it). I's `AppGraph` decides the thread.
- No Compose UI test of the screens' behaviour (taps, sheets) exists; their logic is in the pure
  presenter, which is tested, and the reading order is tested on the device.
- `data/catalog/site`'s PA was not fetched by the reference client (OH was; both passed `--check`).
- The 1,520 + 120 untagged bytes of the stage-2 netstats check were not traced to their sockets.
- **The phone**: G does not touch it (I's 10.4).

## 8. Choices and their reasons

1. **Contract files delegate to internal files** (`internal val core` and the like); the service's
   overrides are closed by the contract's own two braces; OkHttp with fully qualified names inside
   `PackHttp.kt`, whose import list is fixed. `check_contracts.py` passes. (Stage 1.)
2. **Attempt counting.** An attempt that gained bytes resets the count and the back-off and does not
   count itself (287 s of waits, 1.4). (Stage 1.)
3. **CHECKING fetches the manifest only when it is not already in the job folder**, and network
   waits happen only right before a request. (Stage 1.)
4. **ADD_OPTIONAL is refused (NOT_SUPPORTED) with the archive stand-in.**
5. **A held change from `inspect` is not a refusal**: the download waits at READY. (Stage 1.)
6. **`job.json` keeps the entry re-encoded from the parsed entry** and `recover` checks it again.
7. **The tap rule.** Inside a state the chooser opens only when the tapped state itself is small and
   another outline lies within `nearDp / 8` (2 dp); it then lists every state within 16 dp, small or
   not. 6.8's wording fails three of 10.2's cases on the real asset: central PA (MD and WV are small
   and within 11 dp), Lake Michigan's middle (Natural Earth's Wisconsin holds its half of the lake,
   is small at about 43 dp, and its water edge is within 2 dp), and DC's label (10.2 wants VA, which
   is not small, in the list). The finishing engineer tried "the tapped or the near state small" to
   follow 6.8 more closely; it failed the Lake Michigan case and was dropped. Cost: 1 dp inside
   Virginia next to DC gives Virginia (DC's chooser opens from DC and from Maryland's side).
   `StateShapesTest.theInsideRuleAndWhyItDiffersFrom68` pins each reason. **Inset scales** 0.285
   (Alaska) and 0.947 (Hawaii) so each fits its 230 x 170 and 140 x 80 box; 6.8 says 0.35 and full
   scale. Both in request 1-2 of `data/architecture/requests/g.md`.
8. **Free space and needs in GB** as the sheet examples write them. (Stage 1.)
9. **The network watcher, private host without Wi-Fi or Ethernet:** "unmetered" is the default
   network's NOT_METERED instead of always true, so 10.3's mobile-data sheet can appear on an emulator
   on cellular only; debug builds only. Departs from the frozen KDoc of `NetworkState.unmetered`;
   request 4 asks to align the KDoc and 5.2. (Stage 2.)
10. **Draft texts** where section 1 has none (owner to approve): INSTALLER_BUSY "It will be installed
    when the other install has finished."; an UPDATE job's title "Downloading <state>"; a refused
    file install reuses 1.4's "<name> can't be installed" / "Nothing was changed on this phone.";
    **"This file is not a Beamline state pack."** under Details when the installer cannot read the
    picked file at all (`Texts.FILE_NOT_A_PACK`, shown under the file's name); a failed job's row line
    reuses 1.4's "Download failed".
11. **The first-run file install** is not persisted (2.12); its "keep the picker" flag lives in memory.
12. **`--cut-after 100000000`** instead of 10.3's 300000000, which never cuts (request 3).
13. **`build_catalog.py` honours `SOURCE_DATE_EPOCH`**; `transfer.json` records the part's SHA-256;
    the Python client keeps ETags in `.client-state.json` and deletes it at the end. (Stage 1.)
14. **`check_network_rule.py privacy`** checks RFC 1918 addresses. (Stage 1.)
15. **The demo accepts manifest versions 2..3** and keeps its packs on the volume of `downloads/`.
16. **The report's name** is `docs/experiments/18-getting-packs.md`, as the round's assignment says;
    the companion (7.2, 9.1) writes `18-downloads.md` (request 5).
17. **Threading of the screens' actions** (finishing engineer): one ordered single-thread view of
    `Dispatchers.IO` (`GetPacksCore.actions`, `limitedParallelism(1)`) for the engine's actions and
    every read and write of `ui.json`, rather than an IO launch per call, so a user's taps keep their
    order (Pause then Cancel) and Cancel changes the remembered choice before the job ends, in one
    task (else the first-run picker could see "no job, choice made" for a moment and close).
18. **The reading-order test** reads the platform's traversal links through UiAutomation with a
    do-nothing accessibility service of the test APK enabled, instead of Compose's internal test
    hooks: it checks what a screen reader is actually handed, and needs no internal API.
19. **"Continuing the download…"** shows until bytes move again and 3 s more, counted from the first
    moving value (not from `onAppVisible`), and a late PAUSED value of the job being continued does
    not end it.
20. **"Whole country"** moved from over the map to a row under it (with the "Tap a state" hint); the
    row appears only while zoomed in or while the hint shows, so the list moves down by that row then.

## 9. Open questions and decisions for the orchestrator

All six text changes are written out, with the exact wanted text, in
`data/architecture/requests/g.md`:

1. 6.8's tap rule: the rule 10.2 needs (choice 7).
2. 6.8's inset scales: the fitted 0.285 and 0.947.
3. 10.3's and 10.4's `--cut-after 300000000`: write 100000000.
4. `NetworkState.unmetered`'s KDoc and 5.2 for a private host with no Wi-Fi or Ethernet (choice 9);
   or reject, and G reverts `AndroidNetworkWatcher`'s fallback to "always true".
5. The report's file name in 7.2 and 9.1.
6. 10.3's TalkBack item on an image without TalkBack: `ReadingOrderTest`, TalkBack itself in 10.4.

Also:

7. Owner questions 7-9 of 6.38 (mobile data, the public host, update checks) are built as
   recommended: mobile data only after a tap with "Download on Wi-Fi only" first; no public host (the
   development server only); the list fetched only when the first-run or States screen opens.
8. The draft texts of choice 10 need the owner's words or approval.

## 10. How to re-run

```
sh tools/round/gradle.sh g :core:download:test :feature:states:testDebugUnitTest :demo:states:assembleDebug :feature:states:assembleDebugAndroidTest
python data/r3-g/count_tests.py
python -m unittest discover packbuilder/catalog/tests
python packbuilder/catalog/build_state_shapes.py --check
python tools/check_contracts.py
python android/scripts/check_network_rule.py scan
python android/scripts/check_network_rule.py privacy
python android/scripts/check_network_rule.py manifest data/gradle/g/build/demo/states/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml --variant debug --host demo
python android/scripts/check_private_logs.py scan --module core/download --module feature/states --module demo/states
sh tools/round/emulator.sh g start
sh tools/round/gradle.sh g :feature:states:connectedDebugAndroidTest
sh tools/round/emulator.sh g stop
```

Device: `android/demo/states/README.md` (emulator, server, install, start, `. data/r3-g/ui.sh` for
`tap_text`, `texts`, `shot`; the later builds, the empty site and the time-limit check in its last
section); `python data/r3-g/netstats_uid.py <after> <uid> <before>` for the per-app accounting;
afterwards `sh tools/round/adb.sh g shell cmd uimode night no` and `sh tools/round/emulator.sh g stop`.

## 11. Definition of done (`docs/architecture.md` 5.1 "G"), item by item

| Item | State | Proof |
|---|---|---|
| JVM tests of 10.1 pass | done | 62 tests, 0 failed (section 2) |
| JVM tests of 10.2 pass | done | 37 tests, 0 failed (section 2) |
| Device checks of 10.3 on `beamline_g`, observed, screenshots day and night looked at | partly | section 3: every item observed, the TalkBack item through what TalkBack is given (`ReadingOrderTest`, 4 tests, 0 failed), not through TalkBack itself; `--cut-after` and `kill -9` from stage 1; seven failure views and the house-number views not shown on a device (section 7) |
| `check_network_rule.py scan` passes on the tree | done | `scan: 0 finding(s)` |
| `check_network_rule.py privacy` passes on the tree | done | `privacy: 0 private IPv4 literal(s)` |
| `build_state_shapes.py --check` passes | done | `check ok` |
| `python -m unittest discover packbuilder/catalog/tests` passes | done | 20 tests OK |
| `docs/catalog-format.md` written | done | stage 1 |
| Report 18 names every artifact G adds to the runtime classpath, with SPDX | done | section 5 (corrected: lifecycle-service is G's) |

## 12. Review and fixes

Two reviews (reproduce, verdict sound_with_fixes; contract, verdict sound_with_fixes) reported 12
findings. Each was checked against the code before anything was changed. Commands and results for
every fix are in sections 2 and 3.

| # | Review, severity | Finding | Checked | What was done | Proof |
|---|---|---|---|---|---|
| R1 | reproduce, major | TalkBack order unverified, and the code may not produce it | **Right.** Compose orders by `traversalIndex` only among the members of one traversal group; the bar was a plain `Row` with `traversalIndex = -1`, which Compose dissolves into its buttons (index 0), so they were read after the list (read from Compose UI 1.12.1's code, then seen on the device with the old modifier: control case) | The bar is `semantics { isTraversalGroup = true; traversalIndex = -1f }`; `StatesBody` extracted so a device test can show the screen without GetPacks; `ReadingOrderTest` (4) | `connectedDebugAndroidTest`: 4 tests, 0 failed; orders in `data/r3-g/reading-order-s3-final.txt` (section 3). TalkBack itself: not run |
| R2 | reproduce, minor | Day status-bar icons white on light | **Right.** The demo set no bar appearance (the stage-2 report had only the night case) | `DemoActivity` sets `isAppearanceLightStatusBars`/`NavigationBars` from the theme on every change (it handles `uiMode` itself); the README tells I to do the same | `s3-01`, `s3-05` (day, dark icons), `s3-02`, `s3-04` (night, light icons) |
| R3 | reproduce, minor | Section 5 wrongly says lifecycle-service comes from other modules | **Right** | Section 5 rewritten; board note `g-classpath.md`; A's gate already resolves it (`data/r3-a/notices/deps-runtime.txt`) | `grep` of `android/**/*.gradle.kts` |
| R4 | reproduce, minor | Several 10.3 items not re-run or device-unverified | **Right** | Re-observed on the final code: force-stop with "Continuing…" and `Range`, the Update flow, the switch on 404, the time limit, no longer offered, can't load the list, offline, waiting for a connection, paused by night, cancel, a file that is not a pack. Still JVM-only: section 7 | section 3 |
| R5 | reproduce, minor | "Whole country" chip covers labels and outlines | **Right** (it is in `:feature:states`, so `:app` would inherit it) | `StatesMapView` holds zoom and centre; the button is in a row under the map with the "Tap a state" hint | `s3-04` night, `s3-05` day: NJ, CT, MA free |
| C1 | contract, major | "A newer Pennsylvania was published. Downloading that one." can never appear | **Right**: `RunFlags.switched` was never assigned | Pure rule `model/JobSwitch.kt` (same pack, higher `pack_version`, same kind, the previous job not DONE or FAILED), wired in GetPacksCore's job collector; cleared on DONE, FAILED, cancel/dismiss or another pack's job. `EngineStateTest.aGoneFileSwitchesToTheNewerBuild` now also asserts that the engine publishes the switch the way the rule recognises it | `JobSwitchTest` 4 + engine test pass; seen on the device (`s3-07`, `s3-08`, server log in section 3) |
| C2 | contract, major | Definition of done only partly met (10.3) | **Right** | As R1 and R4; the TalkBack item is met through what TalkBack is given; request 6 asks to say so in 10.3 | section 11 keeps the item "partly" |
| C3 | contract, minor | Tap rule differs from 6.8 in a way not fully disclosed (`isSmall(inside)`) | **Right** about the disclosure | Tried "the tapped or the near state small": it breaks 10.2's Lake Michigan case (`StateShapesTest` failed at that line), so the rule stays; the KDoc, choice 7 and the README now state all three differences and why; a test pins each; request 1 | `StateShapesTest.theInsideRuleAndWhyItDiffersFrom68` passes |
| C4 | contract, minor | File writes with fsync and recursive deletes on the main thread | **Right** (and `ui.json` was read on the caller's thread too) | `GetPacksCore.actions` (`Dispatchers.IO.limitedParallelism(1)`) for the engine's actions, `onAppVisible` and every `ui.json` access; `UiStore` reads and writes only there; `showFirstRunPicker` waits for the read; the service's notification actions go through it and the run waits for them; `download()` returns its result by callback on the main thread | `UiStoreTest` (2); StrictMode on the device: 0 violations from G's modules (section 3) |
| C5 | contract, minor | Invented text "This file is not a Beamline state pack." | **Right** | In `Texts.kt` as a draft (choice 10), shown only under Details of 1.4's "<file> can't be installed" / "Nothing was changed on this phone." | presenter test; `s3-22`, `s3-23`, `s3-24` |
| C6 | contract, minor | After `onTimeout` the "Download paused" notification is probably never posted | **Plausible** from the code (stopSelf cancels lifecycleScope before the run's `end` can run); not reproduced with the old code | `onTimeout` stops the job on `actions` and calls `end()` itself; `end()` runs once per start | device: time limit set to 60 s, notification "Download paused" / "Open Beamline to continue." (`s3-14`), reopening continued with `Range` |
| C7 | contract, minor | Contract text not amended for G's choices | **Right** (G may not edit it) | `data/architecture/requests/g.md`, six items with the exact text wanted | the file |

Faults the finishing engineer found on the device (not in the reviews), all fixed:

| # | Fault | Cause | Fix | Proof |
|---|---|---|---|---|
| 5 | After a download finished, "Update" (and "Add house numbers") stayed off for the rest of the session | the row actions were enabled only when `input.job == null`, but the engine keeps a DONE job (and a FAILED one until dismissed) as its current job | enabled while no job is unfinished, as the sheet already did | `s3-11-update-disabled-fault-day`; `StatesPresenterTest.theStatesScreen` (DONE and FAILED jobs of another state leave Update and Add house numbers on, a running one turns them off) |
| 6 | A failed job's view drew a moving progress bar, and its row said "Paused · 0 %" | a null progress meant "indeterminate"; the row used the paused line for FAILED | `JobUi.bar = false` for every failure; row line "Download failed" (1.4's title; choice 10) | `s3-26`/`s3-27` before, `s3-36`/`s3-37` after; presenter test |
| 7 | "Continuing the download…" not visible after `am force-stop` on a cold start | the 3 s counted from `onAppVisible`, which ran out before the first full frame | `model/Continuing.kt`: until bytes move, then 3 s from the first moving value; a late PAUSED value of the continued job keeps it | `ContinuingTest` (2); `s3-39-reopen-strip` |
| 8 | (demo only) `DemoInstalled.remove` and `refresh` did their file work on the main thread | suspend functions without a dispatcher | both on `Dispatchers.IO` | StrictMode: 6 violations before, 0 after over a download, install and Remove |

Files changed by the finishing engineer: `GetPacksCore.kt`, `UiStore.kt`, `DownloadServiceBody.kt`,
`StatesScreenImpl.kt`, `map/StatesMap.kt`, `map/ShapesCode.kt` (KDoc), `model/StatesPresenter.kt`,
`model/Texts.kt`, new `model/JobSwitch.kt` and `model/Continuing.kt`; tests `JobSwitchTest`,
`ContinuingTest`, `UiStoreTest`, `StateShapesTest`, `StatesPresenterTest`, `EngineStateTest`; new
`src/androidTest/` of `:feature:states`; the demo's `DemoApp.kt` and `DemoInstalled.kt`; the four
READMEs (three changed); this report. No contract signature changed (`check_contracts.py`: all the
same).
