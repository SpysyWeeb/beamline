# 21. Next run, batch A: shields over the route, real day and night, the speed-limit sign

Status: **done on the emulator, 2026-11-18.** The first small batch of the run's features, cheapest
first (docs/next-round.md, "The owner's answers after part 1"): the driven road's shields above the
route line while guiding (owner, 2026-11-18), the real day and night switch (item 4, decided
2026-11-16) and the speed-limit sign with the GPS speed beside it (item 11, first bullet, with the
owner's answers of 2026-11-17). Version **0.1.2 (versionCode 5)**. Nothing on the owner's phone yet.
The working record is `data/r3-run2/PROGRESS.md`, section "Batch A" (not in git); screenshots and
logs are under `data/r3-run2/a/` (not in git).

## What changed and why

### Shields above the route line while guiding

Part 1 drew the route ahead above the road labels while guiding, so road names do not sit on the
line in the tilted view; it also hid the driven road's route-number shields under the line. Now the
style's shield layers (`roads_shields@<state>`, one copy per state) are moved above the route
ahead's copy when guiding starts and back to their place when it ends (`SceneParts.guideShields`,
`Layers.moveShields` in `:map:layers`). Road name labels stay under the line as part 1 made them;
the browsing order (and the style's label priority between names and shields) is unchanged. These
are the only style layers that move at run time.

### Real day and night (map style)

Settings now offers **Automatic** ("Day or night with sunrise and sunset"), **Same as phone** ("Day
or night with the phone's dark mode"), Day and Night. Automatic computes the sun on the phone with no
network (`Sun.kt` in `:core:settings`: NOAA's solar calculator equations, public domain, no new
library): the night map from civil dusk to civil dawn (sun 6 degrees below the horizon), with a
0.5 degree margin each way once a theme is shown (about 2 to 3 minutes at Erie), so it never flickers
at the boundary. The place: the last known position, else the middle of the first installed state,
else none (then Automatic follows the phone). The rule runs while the app is on screen (at once when
it comes to the front), every minute or at the switch when that comes sooner, and when the states
change. Nothing of it is logged (PV2). **A stored "automatic" from before means the new Automatic**
(it now follows the sun); Same as phone is stored as `"phone"`. Section 8's "night switch" row now
uses Same as phone.

### Speed-limit sign and speed badge while guiding

Each route answer already carries the posted limit per stretch (`annotation.maxspeed`). The session
reads it once per route (`SpeedLimits` in `:nav:session`) and publishes the current stretch's limit
as `Guidance.speedLimitKmh` (one optional field added to the frozen `Trip.kt`). The drive screen
shows (rule D8):

- a US speed-limit sign (MUTCD R2-1: "SPEED LIMIT" over the number, black on white by day and night)
  while the limit is known and the car is on the route; "none" and unknown show no sign. 89 km/h
  from the engine reads 55 mph; km/h when Settings says kilometres;
- beside it a round badge with the speed from the GPS fixes during all of guidance (also where no
  limit is known), never while browsing or choosing, not after arriving. Its number turns **yellow
  at 5 mph over** and **red at 10 mph over** (8 and 16 km/h), compared on the whole numbers shown;
  the sign never changes. With a weak, lost or missing fix the number is a dash ("Speed unknown" to a
  screen reader);
- at the bottom start of the map (moved there on the owner's word, 2026-11-18; first built at the
  top start): level with the map buttons above the trip bar; while the credit's short line shows
  there in the first seconds of guiding it sits higher, and slides down when the line fades; it covers neither the banner, its lane row, the GPS chip or the stop card, nor the trip
  bar, Recenter or Overview.

**GPS speed usually reads a few mph below a car's speedometer**, which is built to read high; the
comma 3X link (Later) could give the car's own speed.

## How it is proved

| What | How | Result |
|---|---|---|
| Sun times | `SunTest` against the US Naval Observatory's published times: Erie 2026-06-21 (rise 05:45, set 21:00, civil 05:10 / 21:35 EDT), Erie 2026-12-21 (07:45, 16:52, civil 07:14 / 17:23 EST), Pittsburgh 2026-03-20 (07:23, 19:32), Utqiagvik polar night (no rise; civil 11:56-14:55 AKST) and midnight sun, the North Pole | all within 90 s; no crash in polar cases |
| Switch rule | `SunTest`: before/after dusk, the margin both ways, the next switch time and the timer's delay, the old `"automatic"` and the new `"phone"` read from a file | pass |
| Switch on the device | `data/r3-run2/a/speed_sun.py sun` (test extra `--es sun_at`): Automatic at noon = day map, 23:00 = night map; set to 21:36 (just after dusk, inside the margin): still day, then **night by the rule's own timer 110 s later** | shots `a/e2e/sun/` |
| Limit lookup | `SpeedLimitsTest`: exit 24's answer (89 km/h on I-79 to segment 39, none on the ramp, 72 on Peach St, none after; known for 85 % of the distance), two legs, mph, none/unknown, a leg whose count does not match, broken answers | pass |
| Limit along the drive | `Exit24DriveTest`: the published limit goes 89 -> none -> 72 -> none; the golden trace still matches | pass |
| Colours and dash | `SpeedPresentTest`: 59/60/64/65 mph against 55, 96/97/104/105 km/h against 89, no limit, off route, REROUTING, weak/lost/waiting GPS, no vehicle, ARRIVED, choosing | pass |
| Covers nothing | `DriveScreensTest.speedSignAndBadgeCoverNothing` (device, day and night): lanes + Recenter, red, no limit, weak GPS chip, stop card; bounds checked against banner, lanes, chip, card, trip bar, Recenter, Overview | 1/1 (all 13 of the class pass) |
| Shields order | `MapLibreControllerDeviceTest`: browsing order unchanged; guiding: names < route < shields, read from the screen too (a name circle shows the route's colour, a shield circle its own), back after guiding, also after a style reload | 13/13 |
| Exit 24 drive | `data/r3-run2/m/drive.py default` (release with test extras): banners 170.5 / 221.7 / 250.8 s, ARRIVED 289.0 (day); 170.5 / 221.7 / 250.9, 289.1 (night) | within 1 s of 171 / 222 / 251 |
| Screens of the drive | `a/e2e/day/default-start-10s.png`: "90" shields drawn on top of the blue line ahead, "AMVETS Memorial Highway" beside it, sign 55 and badge 55 mph; `default-u3.png`: lanes row, sign 45 under it; night the same in the night colours; `a/e2e/fast/fast-i79-day.png` (`--ef sim_speed 1.2`): **66 mph in red** beside the 55 sign | seen |
| Whole JVM suite | `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh d test --continue -Pbeamline.routingPacksWork=...` | 704 tests, 0 failures, 1 skipped |
| Builds and checks | `:app:assembleDebug :app:assembleRelease :app:lintRelease`; `check_contracts.py`; `check_private_logs.py scan` (0 breaches); `check_network_rule.py scan` and `privacy` | pass |
| Upgrade | emulator: 0.1.0 -> `install -r` 0.1.1 (part 1) -> `install -r` 0.1.2: Success each time, data kept (the map opens on the installed state) | pass |

Phone APK: `data/android/apks/batchA/beamline-release.apk`, 85,160,411 bytes, SHA-256
`effacd1602faff271fc5b1a90e5b26009de595248c6cf86c606c61637bf1d106`, versionCode 5 / 0.1.2, the
same application id; `install -r` over 0.1.1 succeeds, so the signing is the same.

## Contract changes

- `docs/architecture.md` K3 (layer order while guiding); 2.3 `Trip.kt` (frozen, 5.2 procedure:
  `Guidance.speedLimitKmh`); 2.7 `DriveUi.kt` (`SpeedOver`, `SpeedUi`, `GuidanceUi.speed`; the
  stubs kept); 2.10 the map style paragraph and two excerpts of `Settings.kt` (frozen: `MapStyle`,
  `nightMap`; new frozen files `Sun.kt`, `SunTest.kt`); 3.9 rule D8; 5.4 item 12 (test extras
  `style auto|phone`, `sun_at`, `sim_speed`); section 8 night-switch row; decisions 6.70-6.72.
  `python tools/check_contracts.py --freeze` re-recorded 41 frozen files.
- READMEs: `android/map/layers`, `android/feature/drive`, `android/nav/session`, `android/README.md`.

## Decisions taken here (and why)

- **The switch at civil dusk and dawn** (sun 6 degrees down), not at sunset: the standard edge of
  twilight and the owner's wording; at Erie it is about 30 minutes after sunset. Easy to move
  (`SunRule.thresholdDeg`) if the owner wants the night map earlier.
- **The sign at the bottom start** (the owner, 2026-11-18; the engineer had first put it at the top
  start because the credit shows at the bottom start in the first seconds of guiding): it rides
  above the credit's line while that shows and then drops level with the map buttons (`DriveOverlay`
  got a `creditShown` parameter for it).
- **The badge is dark (the banner's colours) by day and night**, so yellow and red read well; the
  sign is always black on white (MUTCD).
- **No sign while off route or in REROUTING**: the stretch's limit belongs to the route, not to
  where the car is then; the badge stays.
- **Colours compare the numbers shown**, so the driver never sees "60" white under a "55" sign.

## Not done, not verified

- The emulator drives used the test-extras build made just before the last fix (the cluster's
  offset under a GPS chip, `Overlay.kt` only); that fix is proved by the Compose device test and is
  in the phone APK, but the drives were not run again with it.
- A yellow badge was not caught on the emulator drive (the Peach St shot came while the car was
  still speeding up); yellow is proved by the JVM test and the Compose screenshots
  `speed-lanes-yellow-day/night`.
- The side (landscape) layout with the speed cluster was not run (`DriveLandscapeTest` not rerun).
- Nothing on the owner's phone: the sign's size and place at speed, the night map's switch time.

## Questions for the owner

1. Is civil dusk (about 30 minutes after sunset at Erie) the right moment for the night map, or
   should it come at sunset?
2. The sign and badge now sit at the bottom left above the trip bar (your answer): is the size right
   on the phone?

## Lead's follow-up: the speed pair moved to the bottom left (2026-11-18)

On the owner's word the sign and badge moved from the top start to the bottom start of the map.
While the credit's short line shows there (the first seconds of guiding) the pair rides above it
(`SPEED_CLUSTER_CREDIT_GAP`, 36 dp); when the line fades it slides down in 0.5 s to sit level with the
map buttons (`SPEED_CLUSTER_BOTTOM_GAP`, 16 dp). `DriveOverlay` got a `creditShown` parameter, which
`:app` sets from the credit rule (contract 2.7 FILE block and rule D8 updated). On its side the pair
sits at the bottom start of the map, right of the side panel.

Proved on emulator beamline_d (scratch under `data/r3-run2/a/bottomleft/clean/`, not in git):
- `DriveScreensTest` 13/13 at phone size, `DriveLandscapeTest` passed; `:feature:drive` and `:app`
  JVM tests pass; check_contracts, the private-log scan and `:app:lintRelease` pass.
- Upright exit 24 drive (release with test extras): banners at 171.3, 222.4 and 251.5 s, ARRIVED
  at 289.6 s. At 2 s the credit line shows under the raised pair; at 10 s the pair is down beside
  the route button.
- On its side: banners at 170.3, 221.3 and 250.4 s, ARRIVED at 288.4 s. The vehicle and route are
  centred in the map right of the side panel (the overlay reports the panel's width to the camera),
  the pair at the map's bottom start, the credit under it in the first seconds.
- Known limit: in a very short window that is not wide enough for the side layout (about 390 dp
  tall, for example split screen), the stop-reached card under the banner can touch the pair while
  it shows. Not seen at phone size; carried to batch B.
- Phone APK rebuilt: `data/android/apks/batchA/beamline-release.apk`, 0.1.2 (5), 85,160,411 B,
  SHA-256 1ab3c882e244b6914bf68c2bb033200235018296026b3fa1dff45aba4e6f51ae.
