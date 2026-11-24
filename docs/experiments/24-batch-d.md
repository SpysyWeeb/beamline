# 24. Next run, batch D: route types (Fastest and Simpler) and the arrival side without loops

Status: **done, 2026-11-19** (key n; two engineers, the second finishing from the tree and
`data/r3-run2/PROGRESS.md` "Batch D"). Item 13 of docs/next-round.md with the owner's answers under
it, and item 16 (the arrival side must not cost a loop) with the owner's answer there. Version
**0.1.5 (versionCode 8)**. Nothing is committed: the lead commits. Scratch, probes and logs are in
`data/r3-run2/d/`, which is not in git (the owner's own destinations appear only there).

## What changed and why

### 1. Shortest is no longer a route type

Only **Fastest** and **Simpler** are offered. Shortest is removed from `presets.json` (both copies;
not only `offered: false`), so the route sheet shows at most two cards and the planner never runs
it. Eco stays as it was (in the file, not offered).

- **A stored "shortest" becomes Fastest.** Settings: when a trip starts, a stored type that is not
  offered gives the presets' default (rule P4, as before for Eco), and the session now also writes
  the default back to settings. A `trip.json` saved with Shortest: the session shows and resumes it
  as Fastest from start-up (rule R3). Tests: PlanningAndResumeTest P4 (settings "shortest" ->
  trip and settings "fastest", no request with `shortest`) and the new "R3 a trip saved with
  Shortest resumes as Fastest".
- The format key `option_fallback_when` (used only by Shortest) stays supported; no type sets it.
- Tests that used Shortest as "the other type" (C7, R3, R4, a robustness test, the route sheet's
  two-card fixtures) now use Simpler. In C7's "no card holds the type" case every candidate of
  Simpler's pool is made to fail.

### 2. Simpler gets more room: 10 %, never more than 10 minutes

The owner's answer: a Simpler route may be up to **10 %** longer in time than the quickest route of
its pool, and **never more than 10 minutes** longer. `max_time_increase` 0.05 -> 0.10 and a new key
`max_time_extra_s` 600, in Python (`route_lib.pool_limit_s`) and Kotlin (`RoutePlanner.poolLimitS`,
`Preset.maxTimeExtraS`); both validators accept it. The quickest route of the pool is Fastest's own
route in practice, because the first candidate has Fastest's options. Turns are still counted as
every guidance instruction (every maneuver but the start and the arrival, `min_maneuvers`).

### 3. Better candidates for Simpler

A wider window alone was not enough. In the owner's example the engine's pool never held the
I-79 exit 99 / US 422 route, so the wider window made Simpler take I-79, I-80 and PA 8. I routed
every candidate kind once for the 21 reference trips and 60 Erie-region trips (4 Erie origins x 15
destinations, among them downtown Butler, Center School in Center Township and the owner's road;
`d/measure_pool.py run`), then scored candidate sets offline with the new window
(`d/measure_pool.py score` -> `d/measure_pool.txt`).

| Candidate set (engine calls) | Erie trips where Simpler differs (of 60) | +min where it differs (mean / max) | instructions fewer (total) | Center School |
|---|---|---|---|---|
| batch C: 3 candidates, +5 % (3) | 23 | 1.8 / 3.9 | 41 | PA 258 |
| 3 candidates, 10 % / 10 min (3) | 31 | 2.6 / 6.8 | 62 | PA 258 |
| **+ use_highways 1.0 with maneuver_penalty 60 (4)** | **35** | **2.6 / 6.8** | **66** | **US 422** |
| + maneuver_penalty 120 instead (4) | 35 | 2.5 / 6.8 | 69 | PA 258 |
| + use_highways 1.0 alone (4) | 31 | 2.6 / 6.8 | 62 | PA 258 |
| + major roads and no living streets, tracks or service roads (4) | 35 | 2.6 / 6.8 | 66 | US 422 |
| + the major-road candidate and maneuver_penalty 120 (5) | 35 | 2.7 / 6.8 | 69 | US 422 |
| + alternates on the second or the fourth candidate (4) | 35-36 | 2.7-2.9 / 6.8-8.1 | 68-69 | US 422 |
| 4 alternates on the first candidate (3) | 31 | 2.6 / 6.8 | 62 | PA 258 |
| every kind at once (14) | 41 | 2.6 / 8.1 | 81 | PA 258 |

I kept the fourth candidate **`{"use_highways": 1.0, "maneuver_penalty": 60}`**. It is the cheapest
set that brings the exit 99 / US 422 route into the pool near the owner's case. The candidate that
also avoids minor roads gave exactly the same routes, so the simpler one stays. `maneuver_penalty`
120 saves three more instructions over all 60 trips but loses the US 422 route at Center School.
More alternates and a fifth candidate add little for one more call each.

**The full planner** (tie, tie check, arrival side; `d/measure_final.py` -> `d/measure_final.txt`,
PC, pyvalhalla 3.9.1 on the phone's routing set):

| Set | Plans | Simpler differs from Fastest | +min where it differs (mean / max) | instructions fewer (total) | engine calls per plan (mean) | PC ms per plan (median) |
|---|---|---|---|---|---|---|
| reference trips, Fastest | 21 | - | - | - | 2.3 | 134 |
| reference trips, Simpler (batch C) | 21 | 7 | 1.1 / 5.0 | 15 | 4.3 | 145 |
| reference trips, **Simpler (batch D)** | 21 | **7** | **2.0 / 5.2** | **17** | **5.3** | **212** |
| Erie region, Fastest | 59 | - | - | - | 2.3 | 43 |
| Erie region, Simpler (batch C) | 59 | 22 | 1.9 / 3.9 | 40 | 4.4 | 91 |
| Erie region, **Simpler (batch D)** | 59 | **35** | **2.4 / 6.0** | **63** | **5.4** | **110** |

(59 Erie plans: the owner's own trip is left out of this table. Simpler never had more instructions
than Fastest. It picked an alternate in 6 Erie plans, and those now get the tie check and the
arrival side, item 4.) Simpler now costs one engine call more than before. On the PC that is a median of
19 ms more per plan on the Erie trips and 67 ms more on the reference trips. Shortest's own plan
(about two calls) is no longer made. The route sheet's total for both types should therefore be
close to 0.1.4's, but I did not compare the two on a device (see "Emulator").

### The owner's case: downtown Erie to a road north of Butler

| Route | Time | Instructions | Way |
|---|---|---|---|
| Fastest | 97.6 min | 10 | I-79, exit 113, PA 258, PA 8 |
| exit 99 / US 422 (now in Simpler's pool) | 100.4 min | 9 | I-79, exit 99, US 422, PA 8 |
| **Simpler (picked)** | **104.3 min** | **7** | I-79, I-80 (exit 29), PA 8 |

**Simpler still takes I-80, not US 422.** The US 422 route is now in the pool, but the rule the
owner confirmed picks the fewest guidance instructions within the limit (97.6 min + 10 % =
107.4 min; + 10 min = 107.6 min). The I-80 route has 7 instructions against 9 and is 6.7 min
(6.9 %) slower than Fastest, so it is inside the limit and wins. Only a different rule would pick
US 422: for example "the fewest instructions, but a route more than a few minutes slower must save
more than two instructions", or a smaller window (checked: with the four candidates and 5 %, Simpler takes US 422 here,
100.4 min, 9 instructions, and still does at Center School). This is question 1 below.

- **Downtown Erie to downtown Butler** stays on exit 99 / US 422 with both types: Fastest 96.7 min /
  7 instructions; Simpler 97.6 min / 6. The reference case `erie_butler` checks it in Python and
  Kotlin.
- **Center School, Center Township** (a public place about 1 km from the owner's road, the tracked
  stand-in): Fastest takes exit 113 and PA 258 (97.2 min, 10); Simpler takes exit 99 and US 422
  (100.1 min, 9). The reference case `erie_center_township` checks it. At the owner's road the I-80 / PA 8
  route has two instructions fewer than the US 422 route, so it wins there; at Center School it
  does not.

### 4. The tie check and the arrival side also for an alternate Simpler picked

Before, when Simpler picked an alternate of its request (`routeIndex` not 0: 8 of 84 reference
plans), neither check ran, because a new request's main route is a different route. Now the check
request keeps the same `alternates`, and the route compared is **the one that goes the same way**
as the picked one: the route whose shape starts with the longest run of the picked route's shape
points, covering at least half of its length (`route_lib.same_way`, `RoutePlanner.sameWay`,
`SAME_WAY_SHARE` 0.5). If no route is the same way, the check counts as asked and the picked route
stays. The chosen request is then the check request with that index (`ChosenRoute(request,
index)`), so the OSRM form for guidance follows the same route. Reference trip U4 (Simpler) used
to arrive on the left; now it takes its side route (731.1 s, `route_index` 1, side kept).

### 5. The arrival side without loops (item 16)

The owner's answer: on quiet streets (residential class and below) arrive on whichever side the
plain route gives; on busier streets (tertiary and above) keep the side route only when it costs
at most about a minute and at most two more turns, going around one block at most.

- The side request now carries **`street_side_cutoff tertiary`** (it was `service_other`, every
  street). Valhalla disables `preferred_side` on an edge of a lower class than the cutoff, so on an
  unclassified, residential or service street the side request gives the plain route and the plain
  route stays. This costs the same one call as before (the request is still sent only when a stop
  is reached on the left); I did not add a separate road-class lookup, because the app's engine has
  no locate call and a trace call would cost the same.
- A new limit **`max_extra_maneuvers` 2**: the side route may have at most two more guidance
  instructions than the plain route (counted as Simpler counts them), besides the 60 s and the
  no-extra-U-turn checks. Two more turns and 60 s are one block; a longer loop or a U-turn is
  refused.
- **Unclassified** counts as quiet: the owner named "residential and below" and "tertiary and
  above"; unclassified is between them and in OSM is mostly a minor road (question 3).

**The owner's cases** (scratch `d/probe_side.py`; in this report "a business on a residential
street in Erie" and "a shop on a busy secondary street in Erie"):

| Case | 0.1.4 (C1 rule) | Batch D |
|---|---|---|
| (a) the business on the residential street, approached along a main road | around the block: 217 s, 6 instructions, on the right | **turns straight onto its street**: 174 s, 4, on the left |
| (b) the shop on the secondary street, from (a) | 569 s, one block (+32 s, +2), on the right | the same: **on the right** |
| (b) from Perry Square | 173 s, on the right | the same: **on the right** |

**The C1 review's 450 Erie plans** (150 places with an address, seed 11, x 3 origins, Fastest,
with the address street; `d/measure_side.py` -> `d/measure_side.txt`):

| | C1 rule (0.1.4) | Batch D |
|---|---|---|
| Arrivals on the left | 68 | 133 |
| Side request asked | 192 | 192 |
| Side route kept | 124 | 59 |

Of the 124 side routes of 0.1.4, 59 stay (all within +56 s and +2 instructions of the plain route)
and **65 are gone**: 42 on quiet streets (the place is now reached on the left without a detour of
up to 57 s) and **23 loops of 3 to 5 extra instructions** on busier streets (State Street, Peach
Street, US 20 and others; +32 to +57 s). The 65 more left arrivals are all on quiet streets or
where only a loop would give the right side.

Three public places from that sample are new reference cases (`side_residential`,
`side_one_block`, `side_long_loop`), checked in Python and Kotlin: a community centre on West 24th
Street (residential: the plain route, on the left; the C1 rule went around the block, 613 s against
560 s), a shop on Parade Street (secondary: the side route, one block, +10 s, +1 instruction) and a
shop on State Street (primary: the side route was a loop of +4 instructions and +44 s, so the plain
route, on the left).

## Reference plans re-recorded

`android/routing/scripts/reference_routes.py --device data/routing/device` (54 s):

- `plans.json`: the Shortest plans are gone (21 trips x 4 option sets, and four runs of the 2.11 cases); of the
  252 plans left, **84 changed, all Simpler**: 11 routes (U4 takes its side route on its alternate;
  B4, B3, I2, I3 with the new window or candidate) and 73 flags only (one more candidate, the tie
  check now on alternates). Fastest and Eco plans are unchanged. Cases: `address_out_of_reach`
  (Fastest) keeps its plain route now (621.9 s instead of the side route's 627.2 s: the untied route
  ends on a mall service road, below tertiary); 7 new cases (`erie_butler` and
  `erie_center_township` x Fastest, Simpler; the three side cases).
- `trips.json` (the old 126 plans with format-1 presets, run by today's route_lib): Shortest U4
  (its side route had 15 more instructions; now the plain route) and I1, Simpler U4, and alternates.
- `exit24-osrm.json`, `banner-routes.json.gz`, `reorder.json`, `errors.json`: unchanged. The exit 24
  golden trace (`android/routing/testdata/nav/exit24.json`) is unchanged.
- `session-recordings.json.gz`: 23 recordings added (`record_session.py`, two rounds) for the
  Simpler requests and the new side requests of the session tests.

## Proved (and how to re-run)

**Python** (`.venv/routing`, from the repository root):

- `cd packbuilder/routing && ../../.venv/routing/Scripts/python -m unittest test_presets`: 27 OK
  (new: the window and its cap, the pick within the cap, the tie check and side on the alternate,
  `same_way`, the side on tertiary and above only, one block at most).
- `.venv/routing/Scripts/python packbuilder/routing/test_routing.py`: 24/24 (Shortest's check
  removed).
- `android/routing/scripts/sync_data.py --check`: exit 0.

**JVM** (`sh tools/round/gradle.sh n ...`):

- `:routing:core:test`: 47 tests pass. The plan reference tests compare Kotlin with Python on all
  252 plans and 19 cases (new: the Butler and Center School cases, the three side cases, the
  alternate's tie check and side). PlannerRulesTest: Simpler at 10 % and 10 min, an alternate pick
  with its tie check, the side rule's cutoff and two-instruction limit.
- `:nav:session:test`: 102 tests, 0 failures (the exit 24 golden trace file unchanged).
- `:feature:drive:testDebugUnitTest` and `:feature:drive:compileDebugAndroidTestKotlin`: pass.
- Whole suite `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh n test --continue
  -Pbeamline.routingPacksWork=<abs>/data/gradle/n/routing-packs-test`: **758 tests, 0 failures, 1
  skipped**.

**Builds and checks:**

- `sh tools/round/gradle.sh n :app:assembleDebug :app:assembleRelease :app:lintRelease`: pass;
  lint 0 errors, 54 warnings, 1 hint (as in batch C). The release's `BuildConfig.TEST_EXTRAS` is
  false; `VERSION_NAME` 0.1.5.
- **Phone APK** `data/android/apks/batchD/beamline-release.apk` (plain release, 0.1.5 / versionCode
  8): **85,249,381 bytes**, SHA-256
  `04d2738186245cd8298bb840ce5407cdb77ac3b4cfd89aec19f300251de137d9`.
- `python tools/check_contracts.py`: all the same, 43 frozen files unchanged.
  `android/scripts/check_private_logs.py scan`: 0 breaches. `android/scripts/check_network_rule.py
  scan`: 0 findings; `privacy`: 0. `sync_data.py --check`: 0. No dependency changed.

**Emulator beamline_n:**

- **`install -r` over 0.1.4:** beamline_n had the batch C phone APK (0.1.4 / 7, same SHA-256 as
  `data/android/apks/batchC/beamline-release.apk`). `install -r` of the batch D APK: Success,
  versionCode 8, versionName 0.1.5. The app opens on the driver notice (`d/emu/after-install-0.1.5.png`:
  the notice text and "I understand").
- **No state was installed** on beamline_n: the app showed "Choose your state", and a test trip said
  "No state with routes is installed". This is how the emulator was found; the app's data was not
  removed in this batch. I installed PA from `/sdcard/Download/us-pa-2026-11-14.tar` with "Use a file
  instead" (`d/setup_pa.py`; installed generation 2, 0 issues). Then I installed the 0.1.5 test-extras
  release over it (`:app:assembleRelease -Pbeamline.testExtras=true`, `d/apk/`) for the runs below.
  So beamline_n now has 0.1.5 with test extras and PA.
- **Route sheet, downtown Erie to Center School** (`d/emu/sheet-center-school.png`): two cards,
  **Fastest** "1 h 37 min, 98 mi, via West Bayfront Pkwy, PA 258" (selected) and **Simpler** "1 h 40
  min, 105 mi, via West Bayfront Pkwy, US 422". There is no third card. The map shows the route from
  Erie down I-79 to the pin north of Butler. The times match Python (97.2 and 100.1 min). Start is
  greyed with "Allow precise location to start guidance": location was not yet granted at that point.
- **Planning time for one long trip** (PLANNING to CHOOSING in the app's log, both offered types
  planned; `d/measure_emu_plan.py`, two runs of three each):

  | Destination from downtown Erie | ms |
  |---|---|
  | Center School (Simpler differs: US 422) | 826, 594, 551; 566, 545, 595 |
  | downtown Butler | 517, 549, 523; 549, 519, 553 |

  This is about 0.55 s for a 160 km trip, including Simpler's four candidates, its tie check and
  the session's own work. **There is no 0.1.4 figure on the emulator.** Going back to a 0.1.4 build
  would need a downgrade (`install -d`, which `tools/round/adb.sh` does not allow) or an uninstall,
  and an uninstall would remove PA again. My first two runs before PA was installed timed only a
  failed plan (about 300 ms), so I discarded them.
- **Section 8 clean drive** (`data/r3-run2/m/drive.py default data/r3-run2/d/e2e/default day`, after
  granting location and notifications): the banner advanced at **170.3, 221.3 and 250.4 s** (golden
  171 / 222 / 251); **ARRIVED at 288.4 s**. `default-arrived.png`: the "Arrived / Downs Drive" card
  with Done, and the arrow at the pin at the end of Downs Drive.
- The emulator was stopped at the end.

## Contract changes

- `docs/architecture.md`: the opening paragraph (Fastest / Simpler); P4 (a stored type that is not
  offered is written back as the default); C7's test (Simpler); R3 (a trip saved with a type that
  no longer exists resumes with the default); 2.11 the presets block (route types), a note on
  Shortest, a new "Simpler" paragraph (window, fourth candidate, the same way), "Every request" 3
  (cutoff tertiary, `max_extra_maneuvers` 2, alternates) and 5 (alternates), Composition 2; decision
  6.77 annotated; new decision **6.82**. No frozen file changed; `check_contracts`: all the same.
- `packbuilder/routing/presets.json` (and the Android copy): route types, `max_time_extra_s`, the
  fourth candidate, `every_request.arrival_side` (`street_side_cutoff tertiary`,
  `max_extra_maneuvers` 2) and the notes.
- `android/routing/README.md`: the batch D rules and the reference sets.

## Not verified

- **Nothing on the owner's phone.** The planning time on the phone is not measured; the emulator
  figure above is the only device figure.
- **Where the owner's road really is on the engine's map.** The owner's case was run from the
  owner's coordinates; whether the engine snaps them to the same road the owner means was not
  checked on the ground.
- **The road class Valhalla gives each street** decides the side rule. Peach Street, State Street
  and Parade Street were seen as busier streets, West 17th and West 24th as residential. Other
  streets were not checked one by one.
- **The same-way match** (half of the route's length) was checked on the reference trips and with
  fake routes only; a pool alternate that the check request does not return again keeps its route
  without a check (none seen in the reference plans).
- **Unused old recordings.** `session-recordings.json.gz` and the reference recordings keep entries
  for Shortest that no test asks for any more. They are harmless and were not pruned.
- The route sheet with two cards was not looked at on a screen in this batch: the presenter tests
  cover it, and the screenshot tests' fixtures were changed but not run (they need a device run of
  `:feature:drive:connectedAndroidTest`).

## Questions for the owner

1. **Your trip north of Butler: Simpler takes I-80 and PA 8 (104 min, 7 instructions), not US 422
   (100 min, 9).** That is what "fewest instructions within 10 % and 10 minutes" gives. If you want
   US 422 there, the rule must weigh time against turns: for example, a route more than about
   5 minutes slower must save at least three instructions. Should Simpler work that way?
2. **Arriving on the left on quiet streets.** In the Erie sample, 133 of 450 trips now end with the
   place on the left (68 before), all on residential streets or where only a loop would help. Is
   that what you want?
3. **Unclassified roads** (small roads that are neither residential nor tertiary, mostly outside
   towns) are treated as quiet: no side request. Is that right?
