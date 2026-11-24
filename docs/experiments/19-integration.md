# 19. Integration (track I)

Status: **done on the emulator, 2026-11-16**: stage 1 (wire), stage 2 (the drive test of section 8)
and stage 3 (the twelve download journeys with the network rule's proofs on the device, the
saved-places steps, checklist item 14 from the final tree, the READMEs, the phone kit), then three
reviews and their fixes ("Review and fixes" at the end: 5 major and 20 minor findings; the majors all
fixed and re-proved on the emulator; the phone kit rebuilt from the final tree). Not done here
by design: TalkBack and Gboard (no such apps on this emulator image) and everything on the owner's
phone (the next step). The working record is `data/r3-i/PROGRESS.md` (not in git); screenshots and
logs are under `data/r3-i/` (not in git): stage 2's under `data/r3-i/e2e/`, stage 3's under
`data/r3-i/journeys/`.

## Fixes outside :app

Each fix is the smallest one, with a test that fails without it, and the module's own tests passing.

| Module | Defect | Fix | Test |
|---|---|---|---|
| `:core:download` (G) | `RulesFile.bundled()` loaded `download_rules.json` by a path relative to its class's package; R8 moves the class in the app's release build, so the release APK stopped at start ("download_rules.json is missing from the module's resources") | `RulesFile.RESOURCE` is the absolute path `/io/github/spysyweeb/beamline/download/download_rules.json`, as every other module's rule file | `RulesAndSpaceTest.theBundledRulesAreFoundWithoutTheClassPackage` (fails with the old path: shown by a scratch mutation, restored and compared with `cmp`); `:core:download` 63 tests pass; the release APK starts and guides |
| `android/scripts/check_private_logs.py` (S) | `manifest` could not find the extraction rules in a shrunk release APK: the resource optimizer shortens resource paths (`res/xml/data_extraction_rules.xml` becomes `res/4j.xml`) | when the source path is missing, the rules file is found by its resource name through `aapt2 dump resources` (read as UTF-8) | `selftest` case "a shrunk APK's rules file is found by its resource name"; `manifest` on the release APK: 0 problems (1 problem before) |
| `android/scripts/check_private_logs.py` (S) | its PV4 rules wanted device transfer to exclude only the three place files; the lead's decision L1 excludes every domain from device transfer too | `WANT_TRANSFER` = the five domains (path "."), docstring and selftest rules updated | `selftest`: "rules fail with only the three files excluded from transfer", "rules fail when transfer leaves out a domain (external)"; `manifest` on both APKs: 0 problems |
| `android/feature/about/scripts/notices.py` (A) | the resolver read the desugaring configuration of `:nav:ferrostar` only; `:app` has it since integration wired desugaring (the comment there anticipated this) | `DESUGARING_MODULES` gains `":app"` | `notices.py resolve --key i` then `--check --deps data/r3-i/notices`: "licence gate: passed (170 entries, coverage checked)" |
| `:feature:about` (A) | `AboutTextsTest` looked for the label "Draft first-run notice (A)", which decision L9 replaced (the notice text is approved) | the test reads the approved label; `AboutTexts` KDoc says approved | `AboutTextsTest.theFirstRunNoticeIsTheContractsText` (run with `--rerun`: its input, the document, is not a declared task input, so a cached result would not prove it) |
| `:nav:ferrostar` (A) | where the route comes back along the same road (out to a stop and back on the same street: the exit 24 trip with the Sheetz stop on Perry Highway), the follower's nearest-point search broke the tie between the way out and the way back about 1 km ahead by rounding (centimetres): the snapped point jumped to the way back on about half the fixes, its bearing was opposite to the car's course, the session counted wrong-way fixes and re-routed, and after the re-route the forced advance marked the stop reached without the car going there | `FollowerGeometry.project` keeps the earlier stretch unless a stretch more than 100 m further along is nearer by more than 0.5 m | `FollowerGeometryTest.theWayOutIsKeptWhereTheRouteComesBackAlongTheSameRoad` (fails on the old code, "expected 0 but was 4": shown by a scratch mutation, restored and compared with `cmp`); `:nav:ferrostar:testReleaseUnitTest` 5 tests pass; the `stop` variant re-run: no off-route, the stop reached at the Sheetz |
| `:feature:drive` (D) | review: after a re-route (O6 sets GUIDING at the answer) the session has no banner until a fix on the new route (B6); a car still off the new route (the `wrongturn` detour) got neither a banner nor "Finding a new route..." for about 30 s over four re-routes | `Presentation.status`: in GUIDING with no banner (and GPS neither waiting nor lost) the status is Rerouting, so D7's card stays until the new route's first banner; the notification says "Finding a new route..." too | `StatusPresentTest` "D7 - after a new route the card stays until its first banner, also while the car is still off it" (status was OnRoute without the fix); `NotificationTextTest`'s expectation for a GUIDING state without banner changed from "" to "Finding a new route..."; `:feature:drive` 66 tests pass; `wrongturn` re-run: the card at +0.4, +2, +6 s after the first RouteReplaced and +3 s after the second |
| `:feature:states` (G) | review: the state sheet opened half expanded, so on mobile data its two decision buttons, and on Wi-Fi its credit and privacy lines (1.1 step 4: always shown), started under the navigation bar | the sheet opens fully expanded (`skipPartiallyExpanded`), its content scrolls and keeps clear of the navigation bar | device: `rv/dl/d1-a-pa-sheet-wifi.png`, `d1-b-pa-sheet-mobile.png` (looked at: everything above the navigation bar) |
| `:feature:states` (G) | review: "Notifications are off, so Beamline can't tell you when it's done." showed before the notification question was answered (1.1 step 5: after a refusal) | `StatesPresenter.notificationsOffShown`: not while the question can still come or is on screen; GetPacksCore keeps the question's state (asked, answered) | `NotificationsLineTest` (3 tests); device: no line after ALLOW (`rv/dl/d7-c`); the frame before the question: the kit check below |
| `:feature:states` (G) | review: on a start with an unfinished first-run download the map with the download chip showed for a moment before the picker (the job is recovered when the app becomes visible) | `GetPacks.firstRunDecided` (ui.json read, first scan, job recovered); `:app` holds the splash reveal until it is true (2 s at most) | device: force-stop mid-download, launcher start, 22 screencaps (`rv/dl/d8-relaunch-sheet.png`): no pack-map frame between the reveal and the picker |
| `:core:installed` (P) | review: one installed state had three sizes: the sheet "On this phone: 1.3 GB", the States row "688 MB", About "0.69 GB" (the folder without its routing part, which lives in the merged set); after "Remove house numbers" the row kept the old size until the next scan | `PackInfo.bytesOnDisk` = the folder plus the routing part kept in the merged set, minus a house-number part being removed (published before its file is deleted) | `InstalledLibraryTest` "the size on the phone counts the routing part in the merged set and no house numbers being removed" (fails without the fix: the folder alone); `:core:installed` 36 tests pass; device: row and About "1.3 GB" (`rv/dl/d2-*`) |
| `android/scripts/check_private_logs.py` (S) | review: the extraction rules left out the four device-protected domains, so "every domain" (L1, PV4) was not true | `DOMAINS` has all nine domains; the app's rules and the quoted block in PV4 list them | `selftest` "rules fail when a device-protected domain is left out" (all behave); `manifest` on the kit's APKs: 0 problems |
| `android/scripts/check_private_logs.py` (S) | `logcat` counted logcat's own time as a canary point: with `-v time` a line stamped `10:13:42.068` hit the cut canary latitude 42.068 (seen on stage 2's stop log; any log with thousands of lines meets one sooner or later) | a leading logcat time (`MM-DD HH:MM:SS.mmm`) is left out of the number match; words are still matched on the whole line | `selftest` cases "logcat passes on logcat's own time" (FAIL on the old matcher, shown) and "logcat fails on a point after logcat's time"; selftest all behave; `logcat` over the 20 stage 2 logs: 0 canary hits |

## Contract changes (by the procedure of docs/architecture.md 5.2)

- `android/gradle.properties` (frozen): `beamline.mapRenderer=opengl` and its comment (decision L2,
  option 1 of `data/architecture/requests/a.md`); with A's notices source (MapLibre covers
  `android-sdk-opengl`, the three Vulkan-only entries removed), the regenerated notices assets (170
  entries) and `test_notices.py` without its glslang allowance.
- `:core:settings` (frozen): `FrameRate.FPS_30` removed, a stored "30" reads as 60, tests adapted and
  one added (decision L10).
- `tools/frozen-files.sha256` recorded again (`python tools/check_contracts.py --freeze`), then
  `python tools/check_contracts.py`: all the same.
- `docs/architecture.md`: 6.38 answers of 2026-11-16 and the draft/assumption wordings they settle
  (L9); 5.4 item 12 `--ez reset_notice true` (L5); section 4 PV4 in short and 5.2/section 8's one
  allowed clearing of the phone's data (L1, L11); N5's order: OfflineMapHttp is set right after
  `MapLibre.getInstance` (MapLibre 13.7.1's `HttpRequestImpl` needs the instance in its static
  initialiser; before it the app crashed at start).
- `docs/architecture-saved-places.md`: PV4's rules and text (every domain out of device transfer,
  L1), PV7's dated note (L11), SP45 and 3.6 and section 12 answers (L9).
- `docs/architecture-downloads.md`: N5's order (as above) in 4.1 and 9.4 item 2; 12.1 answered (L9).
- Stage 3: `docs/architecture.md` section 8, row 4 of the table: the banner distance at the gore is
  "0.2 mi", with the reason (the first fix past the gore comes about 23 m after it at 1 fix/s, 399 m
  before the turn in N's golden trace; "0.3 mi" only when that fix falls less than about 20 m past
  the gore) and "Where" now reads "the first fix past maneuver 1". Stage 2's open point 1: the app
  and the golden trace agree, the table's text assumed a fix exactly 10 m past. `python
  tools/check_contracts.py`: all the same, 39 frozen files unchanged. No frozen file changed in
  stage 3.

- Review and fixes (2026-11-16, after the three reviews), `docs/architecture.md`: D7 (the card also in
  GUIDING while there is no banner yet after a new route); section 4 (a simulated drive without a
  location grant starts the guidance service without foreground priority; the route sheet's reason is a
  button that asks for precise location again, else a message points to the app's settings); the
  quoted `DriveUi.kt` block gains `DriveEvent.AskPreciseLocation`; section 8 row 4's lane cell "about
  400 m < window" (it said 422 m, the old wording's distance). `docs/architecture-saved-places.md` PV4:
  the quoted `data_extraction_rules.xml` with the four device-protected domains. No frozen file
  changed; `python tools/check_contracts.py`: all the same, 39 frozen files unchanged.

## Stage 2: the end-to-end test of section 8 on the emulator

All runs on `beamline_i` (API 36, 1080 x 2400), the release APK built with
`-Pbeamline.testExtras=true` (same key and id as the debug APK, installed over it), us-pa (and for
`remove while guiding` us-oh) from P's v3 archives. Each run's screenshots, the app's own log
(`logcat -s Beamline BeamlineMap BeamlineGuidance BeamlinePacks AndroidRuntime`) and a summary of
its check lines are under `data/r3-i/e2e/<run>/`; every screenshot cited was looked at. The scripts
are in `data/r3-i/scripts/` (`e2e_rows.py` rows 0-10 in one run, `e2e_variant.py` the scripted
variants, `e2e_options.py`, `e2e_remove_guiding.py`, `gps_drive.py`, `check_drive.py` the
"Throughout" checks from the log). For the checks the app logs, on change only and as enum names,
indexes and numbers (PV2): `trip phase/gps/motion`, `trip banner maneuver <U> suspected=<bool>`,
`camera mode`, and in builds made for testing `trip shown <U> <m> m lanes=<bool>`.

Before the table: both builds made again from the tree, the stage 1 smoke run repeated once on a
fresh install of the debug build with the development server (notice before any request, one
catalog request with the allowed headers, PA downloaded and installed, search, Directions, route
sheet, "Simulate drive" to ARRIVED, Done, service stopped, no "Untagged socket" line) and the test
extras drive on the release build (GUIDING to ARRIVED 291.6 s): both passed.

### The table

| Row | Day (`e2e/day`) | Night (`e2e/night`) |
|---|---|---|
| 0 Route sheet | PREVIEW top-down north up, route, destination pin, puck; one card "Fastest · Shortest · Simpler" 5 min 3.3 mi, note Highways, no Eco, toggles off | the same in night colours |
| 1 Start | "2.6 mi" Exit 24 / US 19 / Peach St, toward Waterford, chip left "In 0.3 mi", no lanes, GUIDE_FOLLOW pitch 50, arrow in the lower third, trip bar Arrive / 5 min / 3.3 mi / Simulated, no beam | the same with the beam ahead of the arrow |
| 2 1.0 mi | "1.0 mi", chip unchanged; a pan: GUIDE_FREE with Recenter at 106.6 s, back to GUIDE_FOLLOW at 122.1 s; driven part in the traveled colour | "1.0 mi", beam; screen off 106-136 s: notification "Exit 24 · 0.7 mi" / "US 19 / Peach St" |
| 3 0.5 mi | "0.5 mi", chip "In 0.3 mi" | the same, banner current after screen-on |
| 4 gore | at 170.6 s "Turn left onto US 19 / Peach St", chip right "In 0.2 mi", lanes at once (left allowed, left+through best, right not for route), zoom 16.5 then 17 (approach) at 183.7 s, arrow on the ramp; **distance "0.2 mi"** (398 m at the first fix after the advance; N's golden trace 399 m) where the table says "0.3 mi" | the same |
| 5 400 ft | "400 ft", chip "In 0.2 mi", lanes | the same |
| 6 halt | "50 ft" (15 m), nothing advances, STOPPED 1 s after the halt began (201.7 s), lenses lit with glow, MOVING again at 220.8 s | night lenses and glow |
| 7 after the left turn | 221.8 s "Turn right onto Downs Dr" "0.2 mi", no chip, lanes at once (left, through, through not for route; through+right best), heading turning with the road | the same, beam |
| 8 0.2 mi | "0.2 mi" | the same |
| 9 after the right turn | 250.9 s "Arrive" (the route gives no side), "0.2 mi" counting down | the same |
| 10 the end | ARRIVED 289.0 s, card "Arrived / Downs Drive", top-down north up z17 centred on the destination, lenses lit once stopped, notification "Arrived at Downs Drive"; Done: IDLE, the puck back, "guidance service stopped" | the same, beam in ARRIVED |

Throughout (both runs, `check_drive.py --clean`: PASS): the banner advanced at 170.6 / 221.8 /
250.9 s only (golden trace 171 / 222 / 251), gps GOOD only, no REROUTING, never off-route
suspected, the shown distance never went up.

### The variants

| Variant | Run | Result |
|---|---|---|
| wrongturn | `e2e/wrongturn` | suspected at the first off fix (202.8 s), REROUTING on the fifth (206.8 s), the banner stayed U2 while suspected, the arrow off the line only while suspected, RouteReplaced(REROUTE), GUIDING, banners of the new route, ARRIVED. The script's car keeps to its 400 m detour, so four re-routes 10 s apart; the first REROUTING lasted 0.1 s (card not caught), the later ones 5 s with "Finding a new route...". Open point: between each RouteReplaced and the new route's first progress the top shows neither banner nor card (5 s each here) |
| gaps | `e2e/gaps` | 8 s: WEAK, "Weak GPS signal", arrow moving, distance falling by dead reckoning, no advance, no re-route; 35 s: WEAK then LOST 30 s after the last fix (grey arrow, "GPS signal lost", distance frozen), fixes back: GOOD with the real distance, no re-route |
| tunnel | `e2e/tunnel` | LOST in the 60 s gap; the first fix after it: "Turn right onto Downs Dr" with lanes at once (catch-up over two maneuvers), no REROUTING, no wrong banner later |
| jitter | `e2e/jitter` | no REROUTING, suspected for the one 70 m fix only (the arrow drawn off the line during it), the stale fix ignored, advances 0.1 s from the no-halt clean timing |
| drift | `e2e/drift` | never suspected; shots inside the drift window show the arrow on the line |
| stop | `e2e/stop` (v3), `e2e/stop-v4` | first run: an off-route and re-route right after turning onto Perry Highway, and the stop marked reached 0.3 mi before the Sheetz (follower defect, fixed in `:nav:ferrostar`, see "Fixes outside :app"); re-run: AddStop at 1,000 m, STOPS_CHANGED 0.2 s later while guiding, "Stop 1 · 5.1 mi", no off-route, the car into the Sheetz lot, "Stop reached / Sheetz" card (seen at 1.0 and 5.4 s, gone at 12.8 s), on to Downs Dr, ARRIVED |
| options | `e2e/options` | Avoid highways: the same card, note Highways; Avoid tolls: no change; both set back off |
| metric | `e2e/metric` | "4.1 km" (4.2 km for the first second), "800 m", chip "In 400 m", "350 m", trip bar in km, clock unchanged (12 h) |
| units mid-trip | `e2e/units-mid-trip` | at row 3 (through the test extra; Settings is not reachable while guiding): "0.5 mi" / "In 0.3 mi" / "1.2 mi" became "800 m" / "In 400 m" / "1.9 km" at once, nothing else changed |
| rotate | `e2e/rotate` | at rows 2 and 7: D's side layout, the camera following in the free part, the credit beside the panel; back upright as before; one service start, no phase change |
| night switch | `e2e/night-switch` | Automatic style, `cmd uimode night yes` at row 3: style NIGHT 3 s later, route, arrow and beam in night colours, guidance on; back to DAY at 240 s |
| remove while guiding | `e2e/remove-guiding` | States over guidance (OPEN_STATES intent) at row 2: "You can't remove a state during a trip." on every row, Remove greyed, a tap did nothing, no generation; Back: guidance on |
| remove while choosing | `e2e/remove-choosing` | Remove Pennsylvania on the route sheet: generation 2, re-planned, "Stop 1, 2 is outside the installed maps. Install the map of the state it is in." + Retry; reinstalled from the file: the card is back |
| GPS path | `e2e/gps-path` | real `gps` fixes through AndroidPositionSource: banner 1-2-3-4 at 171.2 / 222.9 / 251.3 s, GOOD only, no re-route, ARRIVED 288.9 s (the emulator's fixes carry speed 0, so the lenses stay lit: an emulator artefact) |
| process death | `e2e/process-death` | force-stop 120 s into the GPS drive, cold start: "Continue to Downs Drive?" Discard / Continue; Continue: GuidanceStarted, RouteReplaced(RESUMED), service again, ARRIVED |

### Stage 1's open items, closed on the device

Fresh install of the release test-extras build (`e2e/fresh-first-start`): POI tap, long press
(dropped pin), tap elsewhere, a saved place's marker and its sheet (item 5); the notification
permission asked at the first Start when G never asked (item 9); both SP18 dialogs ("End this trip
and go to ...?", "Drop your trip to ... and go to ...?").

### Changes in :app in stage 2

- Log lines for the checks above (`trip motion`, `camera mode`; `trip shown` in test builds only).
- POST_NOTIFICATIONS at the first Start is not asked again when it was refused once (G's ask).
- The drive overlay is not composed while a screen covers the map: its light status-bar icons for
  the dark banner were left white on the light States screen opened during a trip.
- After the notification permission is granted during guidance, the guidance notification is
  posted again (it was not shown until its text changed).

### Item 15 measurements (indicative, emulator)

- APK, release with test extras: 96,599,981 bytes with every ABI; per ABI (shared entries 8.7 MB
  plus that ABI's libraries): arm64-v8a ~31.6 MB, armeabi-v7a ~26.2 MB, x86_64 ~32.0 MB, x86
  ~32.2 MB; JNA adds armeabi, mips, mips64 (0.1-0.2 MB each).
- Memory at ARRIVED in the process that drove the whole trip (`e2e/metric/meminfo-arrived.txt`):
  TOTAL PSS 183.6 MB, native heap 126.8 MB, Java heap 13.3 MB, TOTAL RSS 322.0 MB.
- Frame rate while following (the app's one-second frame windows, ~270 windows per drive): median
  24.3 fps by day and 25.1 by night, 10th percentile 16.8 / 16.9, max 45.6 / 44.3; the map frames
  are drawn only when something moves. *Corrected after the review:* not reproduced. Counted again
  with one method (`fps_windows.py`, windows between GUIDING and ARRIVED), stage 2's own final day log
  gives a median of 21.6 fps; see "Review and fixes", Measurements.
- Cold start on the final build (`am start -W` after force-stop, PA installed, 3 runs): TotalTime
  643 / 547 / 592 ms; the first fully rendered map frame 1,186 / 1,238 / 1,170 ms after the process
  started (`e2e/measure/cold-start.txt`).

### The 8 s stop card

`e2e/stopcard` (a stop on the route itself, Peach St after the left turn, added at 1,000 m):
StopReached at 223.9 s, the card "Stop reached / Peach Stop" on screen 0.5 s and 7.2 s after it
and gone 8.9 s after it (screencap times on the device), guidance on to Downs Dr, ARRIVED.

### The final build

The last :app change of stage 2 (the notification refresh) was proved on a fresh install
(`e2e/fresh-v6`: after ALLOW at the first Start the notification "Exit 24 · 2.6 mi" / "US 19 /
Peach St" was there within 2 s with the car standing still), and the day table was run again on it
(`e2e/day-final`: the same texts, lanes, pan and return, halt and lenses, ARRIVED 289.1 s, Done;
`check_drive.py --clean` PASS). From the final tree: all JVM tests (`gradle.sh i test --continue`,
673 tests, 0 failures, 1 skipped), `check_network_rule.py manifest` debug and release 0 problems,
`check_private_logs.py manifest` debug and release 0 problems, `scan` 0 breaches, `selftest` all
behave, `logcat` over every stage 2 log 0 canary hits, `check_network_rule.py scan` and `privacy`
0, `tools/check_contracts.py` all the same (39 frozen files unchanged). The variants ran on the
builds of their day (v3 to v5, `data/r3-i/e2e/apks/`); the later changes touch only a route that
comes back along itself (the follower), a screen over a trip (the overlay) and the notification
permission's answer, none of which those runs met except where they were run again.

### Open points for the lead (stage 2)

1. Row 4 says "0.3 mi"; the app shows "0.2 mi" (398 m at the first fix after the advance, which at
   1 fix/s comes 23 m past maneuver 1; N's golden trace has 399 m). The table's text assumes the
   advance exactly 10 m past. Suggest "0.2 mi" in the table. (Done in stage 3: see "Contract changes".)
2. `wrongturn`: after each RouteReplaced the top shows neither the banner nor "Finding a new
   route..." until the new route's first progress (O6 sets GUIDING at the answer, D7 ties the card
   to REROUTING). One fix when the car follows the new route; 5 s per re-route in the script. Should
   D's presenter keep the card until the first progress?
3. N's problem text "Stop 1, 2 is outside the installed maps..." (both ends outside; the origin
   counted as stop 1) reads oddly.
4. N's `RouteGeometry.project` (GeometricFollower in N's JVM tests, and RouteSimulator when it
   switches to a new route) keeps the tie rule the follower had (a later stretch wins by 1e-9 m);
   the app's follower is fixed. For N to align, with the golden trace rerun.
5. Settings cannot be reached while guiding (the pill and its Settings button are hidden, S8), so
   "units mid-trip" can only be switched through the test extra; intended?
6. After a state is removed during route choice the map under the sheet stays blank at the preview
   zoom (no new camera target); acceptable?
7. "Simulate drive" in the route sheet uses `Simulation()` without the `default` script (no halt);
   section 8 is run with the test extras, which name the script. Kept as D built it.
8. The route sheet's "Allow precise location to start guidance" is a text, not a way to ask; after
   a refusal the way back is the my-location button or the app's settings.
9. A dropped pin saved from its sheet keeps "Dropped pin" with Save on the open sheet (S's
   `same()` never matches a MAP_PIN place, 3.1); its marker's sheet shows the saved place with Edit.

### Not verified in stage 2

- "replanning" shown while the stop change runs: the re-plan took 0.1-0.2 s, no screenshot caught it.
- The first REROUTING of `wrongturn` (0.1 s) on screen; the later ones (5 s) were caught.
- TalkBack and Gboard: not on this emulator image (the phone step).
- A's device test of `:nav:ferrostar` (FerrostarScriptedDrivesDeviceTest) was not run again after
  the follower fix (JVM tests only); `notices.py --check` was not run again (no dependency changed).
  Both done in stage 3: OK (8 and 2 tests); the gate passed.
- The phone: not part of this stage.

## Stage 3: journeys and proofs (emulator, 2026-11-16)

Stage 2's open items closed first: A's device tests of `:nav:ferrostar` after stage 2's follower fix
(R8-minified release test APK on `beamline_i`): `FerrostarFollowerDeviceTest` OK (8 tests),
`FerrostarScriptedDrivesDeviceTest` OK (2 tests). The licence gate from the tree: `notices.py resolve
--key i` (153 artifacts, 6 native libraries) and `notices.py --check --deps data/r3-i/notices`:
"licence gate: passed (170 entries, coverage checked)". Row 4's "0.3 mi": decided (see "Contract
changes").

Builds: the debug build of the tree as stage 2 left it (the same SHA-256 as stage 2's v6) for the
first journeys; the two `:app` fixes below went into later builds, and every journey they touch was
run again on them. The journeys' screenshots, server logs (`serve_packs.py --log-headers`, never the
client's address) and logs are under `data/r3-i/journeys/<name>/`; every screenshot cited was looked
at. Helpers: `data/r3-i/scripts/serve.sh` (dev server start/stop), `jr.py` (screen, taps, logs),
`watch.py` (a screenshot at each new download phase), `netstats_uid.py`, `pcap_dests.py`,
`item14.sh`, `write_phone_kit.py`.

### The twelve download journeys (`docs/architecture-downloads.md` 10.4)

| # | Result | Evidence (`data/r3-i/journeys/`) |
|---|---|---|
| 1 | PASS. Fresh install: the notice first; no request in the server log before "I understand"; then exactly one `GET /catalog/catalog.json` with `User-Agent: Beamline-Packs/1`, `Accept-Encoding: identity`, `Host`, `Connection` | `j01-first-run` |
| 2 | PASS. A tap on Pennsylvania on the drawn map: the sheet with 1.1 step 4's texts and the catalog's sizes (681 MB, 1.3 GB, house numbers 132 MB, the Sources line, credit and privacy lines); Download: the progress view at once, the notification dialog 3 s later over it; after Allow the notification "Downloading Pennsylvania" / "182 MB of 681 MB" with a bar | `j02-download` |
| 3 | PASS. The picker closed by itself, the map fitted to PA, "Pennsylvania is ready."; States: PA "On this phone", "House numbers: included". Background variant: FAILED first (the fit and the snackbar were spent while the app was in the background; fixed in `:app`, below), PASS on the fixed build: reopened from the launcher, the map fitted to PA and the snackbar for about 4 s | `j03-installed` (`j03-bg2-reopen-sheet.png`) |
| 4 | PASS. Wi-Fi and mobile data off: "sheetz" near Erie: ten Sheetz with distances; "downs dr" -> Directions: two cards (row 0); Simulate drive: guidance with the banner and trip bar (row 1); no failure in the app's log | `j04-offline` |
| 5 | PASS, four ways: `--cut-after 100000000`: `Range: bytes=100000000-` 206 for the two parts over 100 MB, "Connection lost / Trying again in a moment."; Wi-Fi off 60 s (mobile data left on, as on a phone): "Waiting for Wi-Fi", on by itself after, `Range` at the bytes on the device; the server stopped 10 min: "Couldn't reach the download server" / "Your progress is kept." after 5 min (notification "Download paused"), Continue after the server came back: `Range` at the bytes on the device; the emulator restarted (`adb shell reboot`) mid first-run download: nothing requested while closed, reopened from the launcher: the first-run screen's progress view with "Continuing the download..." (caught by screenshots 0.4 s apart), `Range: bytes=55442176-` 206 = the rest. No part was ever transferred twice in full | `j05-interrupted`, `j05-restart` |
| 6 | PASS. Mobile data only: the sheet "You're on mobile data. This download uses 681 MB of your data plan." with Download on Wi-Fi only / Use mobile data; Download on Wi-Fi only: "Waiting for Wi-Fi" (and its notification), no request; Wi-Fi on: went on by itself and installed | `j06-mobile-data` |
| 7 | PASS. A filler file in `/data/local/tmp`: Ohio's sheet "Needs about 2.7 GB free. This phone has 1.3 GB free." with Free up space; 2.6's formula gives 2,674,470,818 B with PA's merged set (634 MB) and 2,040,041,378 B without it, so the estimate includes PA's set; the space taken away right after Download: "Not enough space" / "Ohio needs 2.7 GB free. This phone has 1.2 GB free." [Free up space] [Continue] [Cancel], notification "Download paused / Not enough space...", no request at all in the server log; filler removed, Continue: installed | `j07-space` |
| 8 | PASS. A later PA build (`data/r3-i/s3/site-update`: the same part files, a manifest with pack_version 202611161200 and OSM data of Nov 15): Check again (one catalog GET) -> "Update available · data from Nov 15, 2026 · 681 MB download" -> the Update sheet's texts -> Update; the map and search worked during the download (chip "Downloading Pennsylvania · 42 %"); About: "OpenStreetMap data of 2026-11-15" | `j08-update` |
| 9 | PASS. Remove Ohio: "Remove Ohio?" with 1.2's texts, removed; during a simulated trip: "You can't remove a state during a trip.", Remove and Remove house numbers greyed, a tap does nothing | `j09-remove` |
| 10 | PASS (below) | `j10-network`, `s1`-`s3` |
| 11 | PASS. A debug build made with no address (the `local.properties` line commented out for that build, restored): "Downloads are off in this build. Use a file instead."; the running server got no request during the whole journey; the file route installed PA and the map opened fitted to it with the snackbar | `j11-no-address` |
| 12 | PASS. Ohio without house numbers: "House numbers: not included" + "Add house numbers · 81 MB"; "247 main st conneaut" (an address of OH's address part with no POI on it) found only as "Main Street, Conneaut ... Street only"; Add: the server log shows only `manifest.json` and `addresses.sqlite.gz`; "included"; the search finds "247 Main Street, Conneaut ... Exact address"; Remove house numbers: "not included", the search no longer finds it | `j12-house-numbers` |

### The network rule on the device (10.4 row 10, 4.2 item 5)

- Per-app accounting (`dumpsys netstats detail` read after installing and before the first start, and
  after the journeys; `data/r3-i/scripts/netstats_uid.py <uid> before after`), untagged = tag 0x0
  minus tag 0xb1a, limit 16 KB each way: session 1 (journeys 1-5, 7-9, 12; 4.69 GB received) 120 B
  received / 120 B sent; session 2 (the captured run and journey 6; 1.34 GB) 0 / 0; session 3 (the
  restart and background runs, counted from the reboot) 0 / 0. PASS.
- Packet capture: `emulator.sh` cannot pass `-tcpdump`, so the console's `network capture start
  <file>` was used (`adb emu`, through `adb.sh`). Finding: it records only the emulator's cellular
  link (eth0); a ping over Wi-Fi was missing from it, the same ping with Wi-Fi off was there. So the
  captured journey ran with Wi-Fi off: fresh install, notice, picker, PA downloaded over mobile data,
  search, directions, a simulated drive (199 s); the baseline with the app uninstalled, Wi-Fi off and
  the emulator idle for 287 s. `pcap_dests.py app.pcap baseline.pcap`: 78,840 packets from the
  device, destinations only the dev server (78,832) and mDNS multicast (also in the baseline); no DNS
  query in either. PASS.
- StrictMode: no "Untagged socket" line in the debug builds' logs of sessions 1-3 and journey 11.
- `check_private_logs.py logcat` over every log of this stage (12 files, the emulator's whole log
  included): 0 canary hits.

### The saved-places steps (`docs/architecture-saved-places.md` 10.3)

On the release build with the test extras (the final `:app` from step 5 on), PA installed, every
cold start with `--es private_data test`, GPS at the Erie Zoo (`data/r3-i/journeys/sp-saved-places/`).

| Step | Result |
|---|---|
| 1 | PASS: the pill at the top with Settings beside it; START "Set home", "Set work", "Saved places / None yet", the shortcuts |
| 2 | PASS: header "Set home" while searching; START "Home", "Erie Maritime Museum, 100 East Front Street, Erie", 2.8 mi |
| 3 | PASS after a fix: "Choose on the map", the prompt, "Point on the map" / "42.08418, -80.17321", the PICKED pin, then START "Work, 42.08418, -80.17321". The bar covered the credit's last line and half of the my-location button: fixed in `:app` (below) and seen right (`sp03-x-fix-sheet.png`) |
| 4 | PASS: "Save place", Other selected, "Millcreek Mall" in the field; the sheet "Saved as Quokka Shop" with Edit |
| 5 | PASS: after the restart "Home", "Work", "Saved places, 3 places", no recent row for the mall; "quo" -> Quokka Shop first; "home" -> Home first |
| 6 | PASS: a tap on Home: the route sheet to Home with a card, no place sheet |
| 7 | PASS: Add stop -> search with Home and Work, no Set rows -> Saved places -> Quokka Shop: stops "Quokka Shop", "Home", new cards |
| 8 | PASS: house, briefcase, star in the middle of their z13 views; z11 house and briefcase, no star; z12 the star |
| 9 | PARTLY: no star under the destination pin; search for a stop during guidance shows Home and Work at the top with the keyboard down (`mInputShown=false`); no saved marker came into the tilted view on this trip (two extra drives meant to pass one did not reach it in 40-60 s), so "upright, never over the route line" is not shown in `:app` (M's device test shows it) |
| 10 | PASS: the house marker -> the sheet "Home" with Directions and Edit |
| 11 | PASS: no markers with "Show on the map" off, back when on |
| 12 | PASS: rename, change of location, the make-home dialog's texts as written, Remove work; the final rows as written |
| 13 | PASS with `su 0` instead of `run-as` (the test-extras build is a release build, not debuggable; `su` exists on this userdebug emulator image only): only `no_backup/test/saved-places.json` and `recents.json` (no trip.json: the trips had ended); no other file of the app holds a canary; `saved_places.py --check`: ok, 1 place, Home at 42.13108, -80.085566 |
| 14 | PASS: the house readable in night colours |
| 15 | PASS: `check_private_logs.py logcat` over both captures and the emulator's whole log: 0 canary hits; `manifest`: 0 problems |
| 16 | PASS: the SP52 notice above "Set home" after the first and the second start, `saved-places.broken-1.json` holds `{`, no notice after OK and a restart |
| 17 | PARTLY: the dumps show the pill's "Search places or addresses", "Edit Home", the switch "Show on the map" with its state; TalkBack is not on this image: phone step |
| 18 | Not done here: phone only (Gboard) |

### Changes in :app in stage 3

- A finished download's "show on map" (fit, snackbar, `showOnMapDone()`) waits until the activity
  is visible (`AppRoot`: `mapShown` needs the lifecycle at least STARTED). Before, an install that
  ended while Beamline was in the background spent the fit and the 4 s snackbar unseen (journey 3).
- During a map pick the credit line and the my-location button get room for S's whole pick bar
  (`pickBarRoom`: the bar's title lines, coordinates, note and problem lines at the font scale, the
  navigation bar and a 12 dp gap) instead of a fixed 168 dp, which left the credit's last line and
  half of the button under the bar (10.3 step 3).

No module outside `:app` was changed in stage 3.

### Checklist item 14 from the final tree

`sh tools/round/gradle.sh i test --continue -Pbeamline.routingPacksWork=...`: BUILD SUCCESSFUL,
673 tests, 0 failures, 1 skipped (S's timing probe). `sh data/r3-i/scripts/item14.sh` over the kit's
APKs: `sync_assets.py check`, `sync_data.py --check`, `copy_search_rules.py --check`, `sync_art.py
--check`, `build_state_shapes.py --check`, `notices.py --check` (passed, 170 entries),
`check_network_rule.py manifest` debug and release, `scan`, `privacy`, `check_private_logs.py scan`
(0 breaches, 29 marked lines), `selftest`, `manifest` debug and release, A's Python tests,
`render_icon.py --check`, `tools/check_contracts.py`: all passed.

### The phone kit (decision L7)

`data/android/apks/` with `data/android/phone-kit.json` (sizes, SHA-256, what each is for, the
commands, the media-scan line), built from the final tree: `beamline-release.apk` (96,599,981 B),
`beamline-release-testextras.apk` (96,599,981 B, the same bytes as the build the saved-places steps
ran on), `beamline-debug-loopback.apk` (111,741,628 B, catalog `http://127.0.0.1:8765/catalog/catalog.json`).
Checked: one application id, versionCode 3 / 0.1.0, the same signing certificate, the nine
permissions of section 4 plus the app's own, no Vulkan, SPIR-V or glslang marker in `libmaplibre.so`,
the loopback address only in the debug APK. On the emulator: the loopback APK with `adb reverse
tcp:8765 tcp:8765` fetched the catalog (Host 127.0.0.1:8765) and downloaded and installed PA; the
test-extras APK installed over it guided the exit 24 trip; the plain release installed over that
ignored the extras and reads "Release build, map drawn with MapLibre (OpenGL ES)" in About. The
kit of 2026-11-15 is unchanged in `data/android/apks/old-kit-2026-11-15/`.

### Measurements added in stage 3 (indicative, emulator)

- Cold start of the kit's release test-extras APK (`am start -W` after force-stop, PA installed):
  TotalTime 712 / 656 / 648 ms.
- Downloads from the dev server (debug): PA over Wi-Fi 1 min 44 s from the tap to "Pennsylvania is
  ready." (11:44:59 to 11:46:43); over mobile data and over `adb reverse` under a minute each to the
  installed state (the server log's first and last request 43 s apart over mobile data); PA installed
  from the file within about 10 s of Install ("Installing" seen at 12:50:02, the map at 12:50:08).

### Seen in stage 3, for the tracks or the lead (not changed)

1. G: before the notification question is answered, the progress view already says "Notifications
   are off, so Beamline can't tell you when it's done." with Turn on (1.1 step 5 says after a refusal).
2. G/I: on every start while a first-run download is unfinished, the map with the pill and the chip
   shows for a fraction of a second before the first-run screen (`showFirstRunPicker` turns true after
   `ui.json` is read).
3. G: the state sheet opens half expanded; on mobile data its two buttons start under the navigation
   bar until it is dragged up.
4. P/G: the States row's size leaves out the routing part ("688 MB" where the sheet said "On this
   phone: 1.3 GB"), and after Remove house numbers it keeps the old size until the next scan (P
   publishes the generation before deleting the file, by design).
5. G: once, right after a removal with the States screen open, the "On this phone" section was
   missing although Ohio was installed; reopening the screen showed it. Tried once more, not seen again.
6. S: "247 main st conneaut" ranks "247 Main St, Conneautville, PA, Estimated" above "247 Main Street,
   Conneaut, Exact address" (with the Ohio house numbers installed).
7. I/lead: a search result opens its sheet but the camera stays where it was, so the place can be off
   screen; no rule asks for a move (5.5 ShowPlace). Suggest: centre on the place at the sheet's zoom.
8. I/M: in the route preview the route's last metres can sit under the credit line (Erie to
   Philadelphia, Quokka Shop).
9. A: About writes "OpenStreetMap data of 2026-11-14" (ISO) where States writes "Nov 14, 2026".
10. 10.3 steps 13 and 16 name `run-as`, which works only for debuggable builds; the step's own
    set-up is the test-extras (release) build. `su 0` on the emulator did the same.
11. M (asked for in the hand-over): a route preview below z8 in the installed app, Erie to
    Philadelphia: the route and the grey alternatives run under the overview's labels ("State
    College", "Youngstown"); the pins stay on top (`data/r3-i/journeys/m-low-zoom-preview/`).
12. My own slip, recorded: in journey 5 a Remove tap meant for Ohio landed on Pennsylvania's button
    (the layout moved as a banner was dismissed) and I confirmed the dialog without reading it; PA was
    downloaded again. Dialog texts were checked before every confirm after that.

### Not done or not verified

- TalkBack (10.3 step 17's second half, 10.4's TalkBack) and Gboard's incognito mark (step 18): not on
  this emulator image; the phone step.
- 10.3 step 9: a saved marker in the tilted guide view was not brought into view in `:app`.
- The brief "replanning" state of the stop variant and the first 0.1 s "Finding a new route..." of
  `wrongturn` (stage 2): still not caught on screen.
- The phone: nothing in this round touched it.
- Settings' "Saved places" row reading "Not while guiding" (item 8): Settings cannot be opened while
  guiding (the pill is hidden, S8), so the row's guided state is not reachable on screen.
- Stage 2's variants ran on builds before stage 3's two `:app` changes; those changes touch only the
  show-on-map timing and the pick bar's room, which the variants do not meet.

### Checklist 5.4 at the end of track I

| Item | State | Proof |
|---|---|---|
| 1 dependencies, desugaring, id | done | stage 1 (build file); every build of this stage; the licence gate resolves `:app`'s classpath |
| 2 AppGraph | done | stages 1-2; journeys 1-12, the saved-places steps (test folder), N5 (no request but the catalog's, packet capture) |
| 3 start-up order | done | journey 1; "Continuing the download..." on start (journey 5); observation 2 above |
| 4 map screen | done | stages 1-2; journeys 3 and 11 (fit, snackbar; the visibility fix), the low-zoom preview, the pick-bar fix |
| 5 gestures | done | stage 2 (POI, long press, marker); 10.3 steps 3 and 10 |
| 6 flows | done | stages 1-2; 10.3 steps 2-12 |
| 7 States screen | done | journeys 2-12 |
| 8 Settings | done (one part not reachable) | 10.3 step 11, About in journey 8; "Not while guiding" not reachable (above) |
| 9 GuidanceService, notification, permissions | done | stages 1-2; the notification question at the first Start (10.3 step 9) |
| 10 manifest | done | `aapt2 dump badging` of the kit, `check_network_rule.py manifest` and `check_private_logs.py manifest` debug and release: 0 problems |
| 11 launcher icon, theme | done | stage 1; A's icon and splash on every start |
| 12 test extras | done | stages 1-2, the kit check, the saved-places steps |
| 13 R8 | done | the release builds run; the exit 24 drive on the release APKs (stage 2, the kit check) |
| 14 JVM tests and checks | done | 673 / 0 / 1; every check passed (above) |
| 15 E2E and measurements | done on the emulator | section 8 (stage 2), the downloads and saved places (stage 3), `check_private_logs.py logcat` 0 hits, the measurements of stages 2 and 3; reviewers and the phone come next |

(The table above is stage 3's. The state after the reviews is the table at the end of "Review and fixes".)

## Review and fixes (2026-11-16, after the three reviews)

Three reviews read stages 1-3: "reproduce" (the track run again on the emulator), "wiring" (the
sources of `:app` and the kit's manifests) and "driver" (about 60 of the screenshots against the
contract and the designs). Each finding was checked against the tree and the contract before
acting; the evidence of this step is under `data/r3-i/rv/` (not in git), the working record is
`data/r3-i/PROGRESS.md` ("Review and fixes", RV-M1 to RV-M4). Every screenshot named here was looked at.

### Each finding and what was done

| Review, # | Finding | Verdict | What was done, and the proof |
|---|---|---|---|
| reproduce 1, driver 5 (major) | `wrongturn`: no banner and no card for about 30 s after the first re-route | right; the gap lies between O6 (GUIDING at the answer) and B6 (no banner until a fix on the new route), and the script's car stays off every new route | `:feature:drive` `Presentation.status`: in GUIDING with no banner the Rerouting card ("Finding a new route...") shows until the new route's first banner (D7's wording extended). Test `StatusPresentTest`. Device `rv/wrongturn/sheet.png`: the card at +0.4, +2 and +6 s after the first RouteReplaced and +3 s after the second; ARRIVED |
| wiring 1 (major) | the exported OPEN_STATES intent opened States, and so fetched the catalog, under the driver notice | right | `AppFlows.openStates` refuses before the notice is accepted (`AppRules.noticeAccepted`, test `AppRulesTest`); no screen is composed under the notice. Device, fresh install of the debug build, server running: OPEN_STATES under the notice: the notice stays (`rv/dl/w1-a`), the server log stays empty, the app logs "States not opened: the driver notice is not accepted yet"; after "I understand" one catalog GET |
| wiring 2, driver 3 (major) | approximate-only location: Start off with a plain text, no way to ask again (section 4: the app offers to ask) | right | `DriveEvent.AskPreciseLocation` (D's `DriveUi.kt`, contract block updated); the route sheet's "Allow precise location to start guidance" is a text button; `MainActivity.requestPreciseLocation` asks again, and when Android no longer shows the question it says "Precise location is off for this app. You can allow it in the app's settings." with App settings. Device (`rv/sp/w2-*`): coarse only; the button asks ("Change ... to precise location?"); refused twice, the third tap shows the message (looked at); granted: Start enabled. The my-location button still follows with an approximate fix (section 4: the browsing puck works with it) |
| driver 1 (major) | the state sheet opened half expanded: buttons on mobile data, credit and privacy lines under the navigation bar | right | G's sheet opens fully expanded and scrolls. Device `rv/dl/d1-a-pa-sheet-wifi.png`, `d1-b-pa-sheet-mobile.png`: every line and button above the navigation bar |
| driver 2 (major) | one state, three sizes (sheet 1.3 GB, row 688 MB, About 0.69 GB); About's ISO date | right | P's `onPhoneBytes` (the folder plus the routing part in the merged set, minus house numbers being removed; test in `InstalledLibraryTest`); About writes the size and date as States does. Device: the row "On this phone · data from Nov 14, 2026 · 1.3 GB", About "OpenStreetMap data of Nov 14, 2026 · 1.3 GB" |
| reproduce 2 (minor) | a simulated drive without a location grant: no service, no notification | right (Android 14+ refuses a location-type service without a grant) | `GuidanceService.start`: with no location grant a plain service that only posts the notification (section 4 says so now). Device: both grants revoked, simulated drive: "guidance service started without foreground: no location grant", notification "Exit 24 · 2.3 mi" / "US 19 / Peach St" |
| reproduce 3 (minor) | a search result opens its sheet but the camera stays | as the contract says (K1: the browsing camera moves only for my-location); the owner put exactly this on the next round (`docs/next-round.md` item 2, with a K1 change) | not changed here; the sheet's slight see-through is S's panel colour (95 % opaque, by design), not changed |
| reproduce 4 (minor) | route preview: the destination pin's head under the status bar | right (D's top block is empty in CHOOSING, so the status bar's band was not padded, nor the credit row above the sheet) | `AppRules.cameraInsets`: the camera director gets the status bar's height at the top and, while not driving, the credit row at the bottom (test `AppRulesTest`). Device `rv/sp/r4-route-home.png`: the pin whole, the puck and the route above the credit; the guide camera is unchanged (the drives below) |
| reproduce 5 (minor) | the report's frame rate (24.3 / 25.1 fps) not reproduced | right | measured again with one method (`data/r3-i/scripts/fps_windows.py`: the one-second windows between GUIDING and ARRIVED); see "Measurements" below |
| reproduce 6 (minor) | the arrow off the line after turns (rows 7, 9) | not a defect of the arrow: crops of row 9 (`rv/day/day-row9-arrive*.png`) show the flat arrow on the line, pointing along Downs Dr; the camera's heading follows by K3's low-pass (`bearing_smoothing` 0.3), so for about 5 s the road runs across the screen | not changed: `camera.json` calls its numbers first guesses for the phone test; the catch-up speed is for the phone step to judge |
| wiring 3 (minor) | `private_data test` lost when Android recreates the activity in a new process | right | the data folder is chosen whenever the graph is not built, whatever `savedInstanceState`. Device: test set, HOME, `am kill`, restored from Recents: "private data: test set" and the test set's places. If `DownloadService` restarts the process first, the extra cannot reach the graph and the activity logs "extra ignored" (PV7's sessions start cold; this needs a download running at a process death) |
| wiring 4 (minor) | the extraction rules missed the four device-protected domains | right | added to the rules, to PV4's quoted block and to `check_private_logs.py` (selftest case) |
| wiring 5 (minor) | settings and app-state writes fsync on the main thread | right | `AppGraph.updateSettings` / `updateAppState` on one "beamline-store" thread (Settings, the notice, the test extras' units and style); `reset_notice` stays synchronous (a test extra on a cold start, before the first frame). The two small stores are still read on the main thread when the graph is built, not changed |
| wiring 6 (minor) | the map jumped to the state after a recreation (font size, language) | right | `configChanges` gains fontScale, locale, layoutDirection, colorMode, fontWeightAdjustment; a map made again over the same states puts the camera back; a camera request waits for the style. Device: font scale 1.3 while browsing at Erie z15: no recreation, no camera move (`rv/sp/w6-font-1.3.png`) |
| wiring 7 (minor) | a dark phone with Automatic could compose the day style first | plausible (a race); fixed at its cause | `AppGraph.systemDark` starts from the configuration. Device: night mode on, cold start: one "style loaded (NIGHT" line, no DAY before it |
| wiring 8 (minor) | row 4's lane cell still said 422 m | right | "about 400 m < window, whichever fix it is" |
| driver 4 (minor) | Recenter over the credit while panned | right | `AppRules.creditEndPaddingDp` keeps the credit clear of the pill, and the credit may take 6 lines (with 4 its last source was cut to "City of ..."). Device `rv/night/night-pan-free.png`: the whole credit beside Recenter |
| driver 6 (minor) | "End trip" is one tap, no confirmation | the contract and the approved design have a one-tap ✕ | not changed: for the owner (open decisions) |
| driver 7 (minor) | "Notifications are off..." before the question is answered | right | G's rule `notificationsOffShown` (test `NotificationsLineTest`); device: no line after ALLOW, and the frames of the kit check below |
| driver 8 (minor) | the map flashed before the picker on a start with an unfinished first-run download | right | G's `firstRunDecided`; the reveal waits for it. Device (`rv/dl/d8-relaunch-sheet.png`, 22 frames): the reveal, then the picker, no pack-map frame. Still seen: the picker's body fills about 4 frames after its title on that cold start (G's first state), not changed |
| driver 9 (minor) | with three cards the option chips sit half under the pinned Start | the sheet's middle part scrolls by design (the start row and Start stay in view at any font size) | not changed |
| driver 10 (minor) | road labels on the route line, one upside down | MapLibre places line labels again while the camera turns (keep-upright is on in the style); hiding the route's own road names is a design change | not changed: for M in the next round |
| driver 11 (minor) | "Downloads are off in this build. Use a file instead." gives no hint what file | the wording is the contract's (G's texts are still drafts, L9) | not changed: for the owner (open decisions) |
| driver 12 (minor) | an estimated address ranked above the exact one; British spellings; "Peach St" wrapping alone | ranking: S's next round; spellings: the contract's words; the wrap: D's banner text | not changed: open decisions |

### Proved again from the final tree

- JVM: `sh tools/round/gradle.sh i test --continue -Pbeamline.routingPacksWork=...` BUILD SUCCESSFUL;
  `junit_summary.py`: 681 tests, 0 failures, 1 skipped (673 before; 8 new).
- Section 8 by day and by night on the release test-extras build (`rv/day`, `rv/night`, `rv/day2`;
  `check_drive.py --clean` PASS on all three): rows 0-10 as the table (sheets `rv/day/sheet-a/b/c.png`,
  `rv/night/sheet-a/b/c.png`, `rv/day2/sheet*.png` looked at); the banner advancing at 171.0 / 222.2 /
  251.4 s (golden 171 / 222 / 251; in `rv/day2` 0.9 to 1.8 s later, so its fixed-time frames of rows 4,
  7 and 9 caught the moment before the advance, and its later frames of rows 4 and 7 show it); GPS
  GOOD only, no re-route, the shown distance never up; the pan, Recenter, and back after 15 s; by night
  the screen off 30 s with the notification "Exit 24 · 0.7 mi" / "US 19 / Peach St"; ARRIVED with
  "Arrived at Downs Drive", Done, IDLE. `rv/day` ran on the build before the credit's line limit changed
  (6 instead of 4); `rv/night` and `rv/day2` on the final build (the kit's test-extras APK).
- One download journey on the debug build with the development server: journeys 1-3 from a fresh install
  (the notice, one catalog request, the PA sheet, Download, a force-stop mid-download and a launcher start
  that resumed with `Range: bytes=75449632-` 206, "Pennsylvania is ready.", the map fitted:
  `rv/dl/j03-end.png`).
- Saved-places steps 1-4 on the debug build in the test set: all pass (`rv/sp/sp01-*` to `sp04-*`).
- 10.3 step 9's missing part: a made-up saved place beside Peach St ("Peach Marker", 42.05050, -80.08060)
  in the tilted guide view (`rv/day/sheet-c.png`, `rv/night/night-marker-224.png`): the star upright,
  beside the route line, never over it. Step 9 now passes in `:app`.
- Every static check of item 14 (`sh data/r3-i/scripts/item14.sh data/r3-i/rv/item14
  data/r3-i/rv/apks/debug-rv2.apk data/android/apks/beamline-release.apk`): all 17 exit 0, "ALL PASSED"
  (licence gate "passed (170 entries, coverage checked)" on stage 3's resolution: no dependency changed
  in this step; `check_private_logs.py scan` 171 files, 29 marked lines, 0 breaches; both `manifest`
  checks 0 problems with the nine domains; `check_contracts.py` all the same, 39 frozen unchanged).
- `check_private_logs.py logcat` over the app logs captured in this step (11 files: the drives, the
  variant, the W1-W7 checks, the kit): 0 canary hits. No "Untagged socket" line in the captured debug log.

### The phone kit, rebuilt (decision L7)

Built from the final tree by `data/r3-i/rv/build_kit.sh` (the three builds of L7; `local.properties` set
to the loopback address for the debug build only and put back: it holds the emulator's 10.0.2.2 address
again), described by `python data/r3-i/scripts/write_phone_kit.py` in `data/android/phone-kit.json`:

| APK | Bytes | SHA-256 |
|---|---|---|
| `beamline-release.apk` | 96,600,097 | `53181cf01c1a820992064b36446bb4d721947eb2393d45a0f4110540fe0ce554` |
| `beamline-release-testextras.apk` | 96,600,097 | `332a02b9b6f11de629ef7bc676c0f753d83dda92f9a5d25b41da21534014db8a` (the same bytes as the build of `rv/night` and `rv/day2`) |
| `beamline-debug-loopback.apk` | 112,021,260 | `151c5a12bbbaad59201e33799d3bb5a532af34d564adfbe73c4450d8aae8d067` |

Checked: one application id, versionCode 3 / 0.1.0, ten permissions each (the nine of section 4 and the
app's own), one signing certificate (`apksigner`), no glslang, SPIR-V or vkCreateInstance marker in
`libmaplibre.so` (EGL present), the loopback address only in the debug APK's dex, no 10.0.2.2 in any. On
the emulator: the loopback APK on a fresh install with `adb reverse tcp:8765 tcp:8765`: the catalog and
PA's parts fetched with Host 127.0.0.1:8765 (7 requests); the progress view without "Notifications are
off" before and under the question (`rv/kit/d7-download-frames.png`); the line after "Don't allow"
(`rv/kit/d7-after-refusal`); PA installed. The test-extras APK over it guided the exit 24 trip; the plain
release over that ignored the extras and reads "Release build, map drawn with MapLibre (OpenGL ES)" and
"1.3 GB" in About. `adb reverse` removed afterwards. The kit of 2026-11-15 stays in
`data/android/apks/old-kit-2026-11-15/`; `early-look/` (the lead's) was not touched.

### Measurements (indicative, emulator)

| What | Value | How |
|---|---|---|
| Frame rate while guiding, by day | median 18.0 fps, 10th percentile 12.7, max 34.0 (265 windows); on the final build 18.9 / 12.8 / 34.6 (268) | `fps_windows.py` on `rv/day`, `rv/day2` (no build running on the PC) |
| Frame rate while guiding, by night | median 18.2 fps, 10th percentile 12.9, max 31.3 (238 windows) | `rv/night` |
| The same method on earlier logs | stage 2's final day log: median 21.6 (268 windows); the reviewer's day log: 18.7 (238) | `fps_windows.py`. Stage 2's 24.3 / 25.1 are not reproduced by it and are withdrawn. The emulator draws on the PC's graphics, so the figure depends on the PC; the phone step has the real one |
| Memory at ARRIVED after a whole drive (night) | TOTAL PSS 184,090 kB, native heap PSS 124,065 kB, TOTAL RSS 323,284 kB | `dumpsys meminfo` (`rv/night/meminfo-arrived.txt`) |
| Cold start, kit test-extras APK | TotalTime 548 / 562 / 555 ms | `am start -W` after force-stop, PA installed (`rv/kit/cold-start-kit-te.txt`) |
| APK sizes | release 96,600,097 B; test-extras 96,600,097 B; debug loopback 112,021,260 B | the kit |

### Not done or not verified after the reviews

- TalkBack (10.3 step 17's second half, 10.4) and Gboard (10.3 step 18): not on this emulator image;
  the phone step.
- The log of the saved-places steps 1-4 on the debug build was not captured (the emulator's log was
  cleared before the next check), so `check_private_logs.py logcat` did not run over it; stage 3's
  full run of the steps had 0 canary hits, and no saved-places code changed in this step.
- The full twelve download journeys and the network proofs (traffic accounting, packet capture) were
  not run again: one journey (1-3, with a resume) and the kit's download over `adb reverse` were. The
  network code (`PackHttp`, the socket factory) did not change.
- The variants other than `wrongturn` were not run again on the final build. This step's changes touch
  the camera padding outside guiding, the credit's padding and lines, the start-up, the States sheet,
  the size line and the notification line, which the variants do not exercise (the drives above cover
  the guide camera).
- Item 8's "Not while guiding" row of Settings: Settings is not reachable while guiding (S8), as before.
- The brief "replanning" of a stop change (0.1-0.2 s) is still not caught on screen.
- The scratch mutation runs ("fails without the fix") were not repeated for this step's tests; each new
  test asserts behaviour the old code did not have (status OnRoute, the folder-only size, no rule for
  the line, no gate).
- `notices.py resolve` was not run again (no dependency changed); `--check` passed on stage 3's
  resolution.
- Nothing ran on the owner's phone.

### Open decisions after the reviews

1. Owner: "End trip" is one tap on the trip bar, 8 dp from the Stops button (driver 6). Keep it, add an
   "End trip?" question, or move it?
2. Owner or lead: "Downloads are off in this build. Use a file instead." gives no hint what file
   (driver 11): add "A state file (.tar) copied to this phone." to G's draft text?
3. Owner: US spellings ("Kilometers and meters", "Near the map center", "Neighborhood") in place of the
   contract's British ones (driver 12)?
4. D: keep "Peach St" together in the banner with a no-break space inside road names (driver 12)?
5. S, next round: "247 Main Street, Conneaut" (exact) ranks below "247 Main St, Conneautville, PA"
   (estimated).
6. M, next round: road names along the route line in the tilted view (driver 10); the guide camera's
   heading catch-up after turns (`bearing_smoothing`, reproduce 6) at the phone test.
7. Still open from stage 3: the blank map after removing the state during route choice; a saved dropped
   pin's open sheet still offering Save; abiFilters to drop JNA's armeabi, mips and mips64 libraries; the
   "On this phone" section that vanished once (not seen again); 10.3 steps 13 and 16 naming `run-as` for
   a build that is not debuggable; 4.2 item 5's capture and Wi-Fi.

### Checklist 5.4 after the reviews

| Item | State | Proof |
|---|---|---|
| 1 dependencies, desugaring, id | done | every build of this step; the kit's badging |
| 2 AppGraph | done | stages 1-3; the store thread (W5) and the dark start (W7) |
| 3 start-up order | done | OPEN_STATES no longer opens States under the notice (W1, device); the reveal waits for the picker's decision (D8, device) |
| 4 map screen | done | the preview padding (R4, device), the credit beside Recenter (D4, device), the camera kept through a font change (W6, device) |
| 5 gestures | done | stage 2; 10.3 step 3 again (pick bar, credit and button above it) |
| 6 flows | done | 10.3 steps 1-4 again; the re-route card (R1) |
| 7 States screen | done | the sheet fully expanded (D1), one size (D2), the notification line (D7), journeys 1-3 again |
| 8 Settings | done (one part not reachable) | Settings writes off the main thread; "Not while guiding" not reachable (S8) |
| 9 GuidanceService, notification, permissions | done | precise location asked again from the route sheet (W2, device); the simulated drive's notification without a grant (R2, device) |
| 10 manifest | done | the nine domains in the extraction rules; both `manifest` checks 0 problems; the kit's badging |
| 11 launcher icon, theme | done | stage 1; unchanged |
| 12 test extras | done | the test set kept after a process death (W3, device); the kit check |
| 13 R8 | done | the release builds of this step drove the exit 24 trip |
| 14 JVM tests and checks | done | 681 tests / 0 failures / 1 skipped; item 14's 17 checks all passed |
| 15 E2E and measurements | done on the emulator | section 8 by day and night again, `wrongturn`, one download journey, saved-places steps 1-4 and 9; the measurements above; TalkBack, Gboard and the phone come next |
