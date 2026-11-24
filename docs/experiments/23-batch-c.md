# 23. Next run, batch C: routing and guidance (C1), the uncertainty bubble and batch B follow-ups (C2)

## C1: no alleys, arrival on the driver's side, a lane that ends in a turn

Status: **done on the emulator, 2026-11-18** (key n). Items 9 and 12 of docs/next-round.md, with
the owner's answer after batch B on the arrival side. The version is unchanged (C2 sets it and
writes the phone APK). Nothing is committed: the lead commits. The working record is
`data/r3-run2/PROGRESS.md`, section "Batch C1". Probes, logs and screenshots are under
`data/r3-run2/c1/`, which is not in git.

### What changed and why

#### Item 9: no alleys, and a destination tied to a street

The owner reported a drive between two businesses that ended in the alley behind the destination.
There are two causes. First, the engine ties a place's point to the nearest edge, and behind a
building that edge is often a service way. Second, the route request said nothing about service ways.

I measured how often this happens. I took the 1322 Erie places of the PA search pack that have an
address and asked Valhalla's `locate` for the edge each one ties to (`c1/probe_alleys.py`):

| Edge type | Places tied to it |
|---|---|
| Driveway | 641 |
| Parking aisle | 221 |
| Alley | 63 |
| Service road | 36 |
| Drive-through | 44 |
| Street | 317 |

So only about a quarter of the places were tied to a street.

I checked the Valhalla 3.9.1 options in its sources at tag 3.9.1 (`src/sif/autocost.cc`,
`src/sif/dynamiccost.cc`, `valhalla/sif/dynamiccost.h`) and in its API reference:

- `alley_factor` is an auto costing option. Its default is 1 and its range is 0.1 to 100000. It
  multiplies the cost of alley edges.
- `alley_penalty` defaults to 5 s, charged on entering an alley.
- `service_penalty` is 75 s for auto, charged on entering a generic service road.
- `service_factor` defaults to 1.
- Driveways, parking aisles and drive-throughs have no auto factor.
- The location options are:
  - `search_filter`, with `min_road_class`, `max_road_class` and `exclude_ramp`;
  - `search_cutoff`;
  - `preferred_side`;
  - `street_side_tolerance` (default 5 m);
  - `street_side_max_distance` (default 1000 m);
  - `street_side_cutoff`.
- The location field `street` (a street-name hint) is documented as **not implemented** in 3.9.1.

The new section `every_request` in `packbuilder/routing/presets.json` holds the rules. It is
applied in the same way by `route_lib.request`, `route_lib.route` and `route_lib.arrival_side`, and
by `:routing:core` `EveryRequest`, `ValhallaRequests.route` and `RoutePlanner`. `sync_data --check`
passes. It does three things:

1. **`alley_factor` 5 on every route.** I routed 120 random pairs of those Erie places with both
   ends tied to a street (`c1/probe_through.py`). One route used 115 m of alley as a short cut.
   With `alley_factor` 5 none did, at +5 s in all. `alley_factor` 10 and `service_factor` 2 changed
   nothing more there, so they were not added.
2. **The tie.** Every location after the first gets `search_filter {min_road_class residential,
   max_road_class trunk, exclude_ramp true}` and `search_cutoff` 300 m. That covers the stops and
   the destination, never the car's origin. The effect is that the nearest street of residential
   class or above is used, and never a motorway or a ramp.
   - When no such street is within 300 m, the engine answers 171 ("No suitable edges near
     location"). The same request is then asked once more without those two keys, so a place that
     only a service way reaches can still be reached.
   - The plan keeps the request that answered, so the follower's OSRM form follows the same path.
3. **The arrival side** (item 2 below).

`Stop.locationOptions` is new. Its keys win over the tie. It exists for test points that are on a
motorway on purpose, such as the 2.11 cases on I-90 and the Turnpike, and the banner cases.

**The shop with the alley behind it: Family Dollar, 155 East 12th Street, Erie**
(42.124352, -80.077705; shop=dollar_store with an address). Its point tied to an unnamed alley
(service=alley) 14 m away. From Perry Square:

| | Route end | Time |
|---|---|---|
| Before | 12th St, right onto Holland St, right into the **alley** (36 m), arrive | 117 s |
| After | 12th St, right onto Holland St, arrive on Holland St | 107 s |

`alley_factor` alone does not change the end: the alley is the destination edge. The tie does.

The street the place's address names (12th St) is not used yet. Valhalla 3.9.1 cannot do it (the
`street` hint is not implemented), so the app would have to choose the point itself. That is a later
step, as item 9 says.

**A place on a divided road and a one-way street.** On a divided road, both the old and the new
request tie the place to the nearer carriageway, which is the one on the place's side. In the
side-of-street probe below, 89 of the 128 arrivals on the left stayed on the left when the side was
asked. Most of those were on minor streets, where the side is deliberately not asked, or on one-way
streets, where Valhalla has only one direction to offer. In both cases the first route stays.

**Recorded reference routes made again** (`android/routing/scripts/reference_routes.py --device
data/routing/device`; `record_session.py` for the session tests):

- `plans.json`: 136 of 336 plans changed, in 9 of 21 trips: U1-U5, R1, R2, I1 and T2. These are the
  trips whose ends are in towns, now tied to a street. Examples:
  - U1, Market Square to the Carnegie Museum, Fastest: 476.6 s became 374.9 s. The old route
    drove into the museum's service road.
  - R1, Capitol to Hersheypark: 1491.6 s became 1214.8 s. The old route ended inside the park.
  - Some plans are slightly longer. U4 with Avoid highways keeps the side route (687.3 s became
    731.1 s, together with the tie). R2 is +48 s from the tie alone: the side was asked there, but
    the first route stayed.
- Carlisle to King of Prussia, Shortest with Avoid tolls: 170.3 km became 170.2 km (T2: 168.6 km
  became 168.5 km). The quoted numbers in the tests and in 2.11 were updated.
- These keep the engine's default `search_filter`, so their routes test what they were made for:
  - every banner case except exit24;
  - the motorway stops of 2.11: on I-90, on the Turnpike, and the I-90 into Ohio trip.
- The exit 24 trip did not change. Downs Drive arrives on the right, so there is no side call. The
  golden trace `nav/exit24.json` stays as it was, and `Exit24DriveTest` passes.

#### Item 9 and the owner's answer: arrive with the destination on the driver's side

When the chosen route reaches a stop with the destination on the left (maneuver type 6), the planner
asks the same request once more. That request has `preferred_side same` and `street_side_cutoff
tertiary` on those stops, so residential and smaller streets count as minor and the side is not
asked there. The side route is kept only when all of these hold:

- it succeeds;
- it reaches none of those stops on the left;
- it has no more U-turns than the first route;
- it takes at most 60 s longer (`max_extra_s`).

Otherwise the first route stays. `PlanResult.Ok.arrivalSide` says what happened: NOT_ASKED,
ASKED_KEPT or SIDE.

- The arrival text comes from the engine's maneuver (B7), so it matches the route that was kept.
  The tests check this: the exit 24 arrival is "Arrive, on the right", and the reference plans
  carry the kept route's maneuvers.
- Alternates now leave out the route that was kept. Before, they left out the first route, so the
  first route can now be offered as an alternate.

Erie probe (`c1/probe_side.py`): 300 trips to Erie places with an address, from Perry Square and
from the exit 24 area.

| Outcome | Count |
|---|---|
| Arrived on the right | 160 |
| Arrived on the left | 128 |
| Side unknown | 12 |

Of the 128 arrivals on the left:

| When the side was asked | Count |
|---|---|
| Side route arrives on the right within 60 s: kept | 26 (two of them even quicker) |
| Side route arrives on the right but costs more than 60 s: first route stays | 13 (Jared at Millcreek: +148 s) |
| Still on the left (minor street or one-way) | 89 |

No side route had a U-turn. Example: from Perry Square to Velocity Network (VNET, 42.124459,
-80.084535), the side route is kept: 113 s, arriving on the right after Sassafras St and W 10th St.

**Cost of the second call**: a few tens of ms per plan on the emulator, within the noise; see "Proved".

#### Item 12: a lane that ends in a turn (rules B9 and D9)

**The data.** For the route's OSRM answer (already fetched for the follower), I take each step's
intersections after the first, so there is no maneuver at them. An intersection is kept only when:

- it carries lanes, some of which are not for the route;
- it is a real intersection (3 or more bearings);
- the route passes it straight (out within 30 degrees of in), with a valid lane marked straight.

Two more filters:

- Of a run of nodes with the same lanes, only the last is kept. A turn-lane tag covers every piece
  of its way, and the engine repeats the lanes at each node.
- Nothing within 60 m before the route's next maneuver is kept. Those lanes belong to that maneuver,
  and its banner already shows them (B5).

For each remaining candidate, one `trace_attributes` call (edge_walk on the route's own points)
reads `edge.lane_count` over the 500 m before it, never before the step's maneuver. That gives the
road's own lane count. There are at most 40 calls per route, nearest first.

**The rule.** A spot needs fewer valid lanes than the road had before. The road's count is trusted
only in two cases:

- turn lanes were seen being added (fewer lanes before than at the intersection);
- the road had that many lanes for at least 300 m.

The second case was needed. Without it, the exit 24 trip warned twice, and both warnings were wrong:

- on Peach St just after the left turn: lanes L, L, S, S on a 4-lane piece that is the turn pocket
  itself, entered 100 m before;
- on Downs Dr.

So an added turn lane alone never warns.

**The words** name the side whose lanes end:

- "Right lane turns right: keep left for Peach St";
- "Left 2 lanes turn left: keep right";
- "Right lane exits" on a motorway;
- "ends" when the arrows are not all a turn to that side.

When both sides have lanes that are not for the route, the lanes the road gained are taken to be
left-turn pockets on the left, which is the usual US layout. When that still leaves both sides,
the line is "Keep to the marked lane for …".

**Shown** from max(150 m, 18 s × current speed) before the intersection, at most 600 m, until 5 m
past it. Once shown, it stays until the car has passed. It is never shown before the first
progress, while off-route is suspected, or outside GUIDING. The thresholds are in `guidance.json`
`lane_warning`.

**On screen** (D9), the banner keeps its maneuver, distance and chip. Its lane row becomes the
intersection's lanes, with the ones that go on highlighted, and the line appears under the row.

**Peach St northbound at Craig St.** The engine's lanes at the intersection are left (not valid),
straight (valid), right (not valid). The lane counts are 2, then 3 for the last stretch, then 1.

- Two nodes 47 m apart carry these lanes: 42.103241, -80.078403 and 42.103638, -80.078192. The
  second is where the lane count drops to 1, and the rule keeps that one.
- 1 lane goes on of 3, and the road had 2, so the warning is "Right lane turns right: keep left for
  Peach St".
- North of Craig, the lanes left (not valid) and straight+right (valid) follow 1-lane road. That is
  an added left-turn lane, and it does not warn.
- On W 26th St, the same lanes repeat at four nodes just before a left turn onto State St. Neither
  filter used alone was enough there: keeping the last node of the run and dropping lanes near the
  next maneuver both had to apply before that warning went away.

**Contract changes** (docs/architecture.md):

- 2.11 "Every request";
- B9, D9, and decisions 6.76 to 6.78;
- the blocks of the frozen `Trip.kt` (`Guidance.laneWarning`) and `Banner.kt` (`LaneWarningView`),
  with the 5.2 procedure: block updated, `--freeze`, check again;
- `GuidanceRules.kt` (`laneWarning`, `LaneWarningRules`), `DriveUi.kt` (`BannerUi.laneNote`) and
  `guidance.json` (`lane_warning`).

Other changes:

- `RoutingEngine.traceAttributes` is new. Its default throws. `ValhallaEngine` implements it with
  valhalla-mobile's `traceAttributesRaw`.
- `record_session.py` records `trace_attributes` requests.

### Proved (and how to re-run)

**Python, on the PC's offline tiles** (`data/routing/device`, pyvalhalla 3.9.1):

- `.venv/routing/Scripts/python data/r3-run2/c1/probe_cases.py` gives the named cases, old request
  against new (`c1/probe_cases.txt`):

  | Case | Before | After |
  |---|---|---|
  | Shop with an alley behind it: Family Dollar, 155 E 12th St | ended in the alley | ends on Holland St, 117 s became 107 s |
  | Divided road: Erie Greyhound Station, 208 E Bayfront Pkwy | ended in an 84 m service way, on the left | arrives on Holland St on the right, from Perry Square and from the east |
  | Divided road: Erie Fitness Now, 2147 W 12th St, from the west | ended on a service way (type 4) | arrives on the eastbound carriageway on the right, 186 s became 135 s |
  | Same place, from Perry Square | ended on a service way | arrives on the right, 428 s became 377 s, after an engine U-turn at a median opening 215 m before (see "Not verified") |
  | One-way street: McDonald's, 1115 Sassafras St | 145 s, ended in unnamed service ways | 101 s on Sassafras St, still on the left (side asked, nothing on the right) |
  | One-way street: Wendy's, 105 E 12th St, tied to one-way French St | 257 s | 210 s, on the left as the one-way allows |
  | VNET | arrived on the right after a service way | the side route is kept, 113 s |

- `c1/probe_alleys.py`, `probe_through.py`, `probe_side.py`, `probe_lanes.py` and
  `probe_lane_spots.py` give the numbers quoted above.
- `.venv/routing/Scripts/python -m unittest packbuilder/routing/test_presets.py`: 14 OK.
- `.venv/routing/Scripts/python packbuilder/routing/test_routing.py`: 25/25. The I-90-into-Ohio
  end was given the engine's default search_filter, because it is a point on I-90 on purpose.
- `android/routing/scripts/reference_routes.py --device data/routing/device` and
  `record_session.py`: fixtures made again, as listed above.

**JVM** (`sh tools/round/gradle.sh n ...`):

- `:routing:core:test :routing:banner:test :routing:valhalla:testDebugUnitTest` pass.
- `:nav:session:test`: 98/98. This includes:
  - `Exit24DriveTest`, with the golden trace unchanged;
  - the new `LaneWarningTest` (4): Peach and Craig through the session; the added turn lane
    north of Craig, which is a candidate but makes no spot; the pure rule cases; the exit 24 drive,
    which shows no warning.
- `:feature:drive:testDebugUnitTest` passes, including `LaneWarningPresentTest` (2).
- Whole suite, `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh n test --continue
  -Pbeamline.routingPacksWork=<abs>/data/gradle/n/routing-packs-test`: **734 tests, 0 failures,
  1 skipped** (JUnit XML; batch B had 728).

**Builds and checks:**

- `:app:assembleDebug :app:assembleRelease`: pass, after the last change (a log-line comment).
- `:app:assembleRelease -Pbeamline.testExtras=true` was built for the emulator
  (`c1/apk/beamline-release-tx.apk`).
- `python tools/check_contracts.py`: all the same. 41 frozen files, re-recorded with `--freeze`
  after the `Trip.kt` and `Banner.kt` change.
- `check_private_logs.py scan`: 0 breaches. `check_network_rule.py scan|privacy`: 0.
- `sync_data.py --check`: exit 0.

**Emulator beamline_n** (API 36, release with test extras; us-pa installed through "Use a file
instead"; `. tools/round/env.sh n`):

- Section 8 clean drive, `data/r3-run2/m/drive.py default data/r3-run2/c1/e2e/default day`:
  - the banner advanced at **170.3, 221.4 and 250.7 s** (golden 171 / 222 / 251);
  - **ARRIVED at 288.9 s**;
  - the log has no "lane warning shown".
- Peach St drive, `data/r3-run2/c1/drive_lanes.py default data/r3-run2/c1/e2e/lanes-day day`
  (origin on Peach St north of Craig St, heading 27; destination farther on Peach St; the default simulation at
  34 mph):
  - the warning was shown 20 s after GUIDING and gone 38 s after it, so it was on screen for about
    18 s;
  - ARRIVED came at 62 s.
  - `default-shown.png`: the banner reads "0.4 mi / Arrive". Under it is the lane row with the
    left and right arrows grey and the middle (straight) one highlighted blue, then the line
    "Right lane turns right: keep left for Peach St". The map shows Craig St ahead and a 35 sign
    with 34 mph.
  - `default-gone.png`: the banner reads "0.2 mi / Arrive" with no lane row. The car is at Metz St,
    just past Craig St.
- The same drive by night (`lanes-night`): shown at 20 s, gone at 38 s.
  - `default-shown-4s.png`: the same banner on the night map, the line in white on the dark banner.
  - `default-gone.png`: no lane row, the car at Metz St.
- **Cost of the arrival side's second call**, `c1/measure_side.py c1/measure 3`: PLANNING to
  CHOOSING, with all three offered types planned, from Perry Square.

  | Destination | Times (ms) |
  |---|---|
  | VNET (side kept) | 370, 378, 376 |
  | Grace Fellowship (side kept) | 399, 384, 389 |
  | Voodoo (side over 60 s) | 401, 365, 362 |
  | ERIEBANK (still on the left) | 376, 378, 385 |
  | Family Dollar (right, no call) | 357, 345, 380 |

  The second call costs at most a few tens of milliseconds per plan on the emulator, which is
  within the run-to-run spread. The phone is slower and was not measured. The lane warnings' trace
  calls happen at Start: CHOOSING to GUIDING took 23 ms on the Peach trip, which had one candidate.
- The emulator was stopped at the end.

### Not verified

- **Nothing on the owner's phone.** The cost of the extra calls on the phone is not measured.
- **Where the Peach warning ends.** The engine repeats the lanes on two nodes 47 m apart, and the
  rule keeps the last. So the warning ends after Metz St, which is about 47 m past Craig St and is
  where the lane count drops to 1. It does not end at Craig St itself. Which of the two corners
  really has the right-turn-only lane is not checked on the ground.
- **Lane warnings depend on OpenStreetMap's tagging.** I checked them on the Erie routes above
  only: Peach St, W 26th St, State St and the exit 24 trip.
- **The U-turn at the median on W 12th St.** It comes from the tie (the place is tied to its own
  carriageway), not from the side rule. Whether that median opening allows a legal U-turn is not
  checked on the ground. The side rule itself never adds a U-turn: it refuses routes that have more
  U-turns than the first.
- **The stop-order matrix (C6)** does not use the tie or `alley_factor`. Its times can differ
  slightly from the routes that are then planned.
- **Not covered by a device test:** the lane note's layout in landscape or in a short window. Only
  the emulator screenshots in portrait were checked.
- **Unused old recordings.** `session-recordings.json.gz` keeps the old recordings that no test
  asks for any more (213 entries). They are harmless. They were not pruned.

### Questions for the owner

1. **The street the address names.** Places are now tied to the nearest street of residential
   class or above. For Family Dollar on E 12th St that is Holland St, the side street, and not 12th
   St. Should the next step tie an address to the street it names? Valhalla 3.9.1 cannot do this
   itself, so the app would choose the point on that street.
2. **Dropped pins are tied too.** A pin dropped on I-90 now goes to the nearest street within
   300 m. Before, it stayed on the interstate. Should a pin you drop yourself keep its own road
   instead?
3. **Which streets get the side rule.** The side is asked only on tertiary streets and above, and
   on residential streets the first route is kept. Is that the right line?
4. **The lane warning's words when both sides have turn lanes.** The app assumes the added lanes
   are left-turn pockets. That gives "Right lane turns right" at Peach and Craig. Where the added
   lane is a right-turn pocket instead, the words would name the wrong side, though the
   highlighted lanes would still be right. Is that acceptable, or should those spots read only
   "Keep to the marked lane for …"?
5. **A lane that becomes turn-only with no lane added before it.** The app warns there only after
   300 m of road with that many lanes. A shorter stretch could be the turn pocket itself, and that
   would give a false warning like the ones the exit 24 trip showed before this rule. Is it better
   to miss some of these warnings than to show some wrong ones?

## C1 routing fix: the second routing review and the owner's answers after C1

A second review of C1's routing (`data/review/batchC1-routing/`, p1-p10 and `lib.py`) found the
routing sound but left eight points. The owner then answered questions 1-5 above (docs/next-round.md,
"The owner's answers after batch C1"). Python (`packbuilder/routing`) and Kotlin (`android/routing`,
`:nav:session`) were changed alike. Scratch and measurements are in `data/r3-run2/c1/fix/`.

### What changed and why

1. **The side is asked on every street** (review, major). presets.json `every_request.arrival_side`
   now has `street_side_cutoff service_other` instead of `tertiary`, because the owner's answer
   covers all streets. The 60 s cap and the no-extra-U-turn check stay. Two further changes:
   - A side route counts only when it **arrives on the right** (maneuver type 5) at every stop
     that was on the left. Before, any end that was not type 6 counted, so type 4 (no side)
     counted too. A test covers type 4.
   - **When the chosen route is an alternate** (Simpler picked `routeIndex` 1 or 2), neither the
     side rule nor the tie check runs. The main route of a new request would be a different route
     from the one Simpler picked. This happens in 8 of the 84 Simpler reference plans, and one of
     them (U4) arrives on the left. This was decided and recorded, not changed. Doing it properly
     would need the side request to ask for alternates too.
2. **Tie check** (review, minor; new `every_request.tie_check {max_extra_s 90}`). When the chosen
   route was asked with the stops tied to a street, the same request is asked once more without
   the tie. The tied route stays only when it is at most 90 s slower than the untied one and has no
   more U-turns. Otherwise the untied route is the plan. This adds one engine call per plan
   (`PlanResult.Ok.tieCheck`, route_lib `tie_check`). Plan order: address street, the type's
   requests, tie check, arrival side.
3. **Text matches code** (review, minor). 2.11 "Every request" 3 and decision 6.77 now say that the
   side is asked on every street.
4. **Far places** (review, minor). A destination more than 100 m from any street (for example
   inside a large mall lot) ends at the street, up to 300 m away. This is now in 2.11 rule 2 and
   D2. It is also on screen: the arrival card shows **"Destination 500 ft away"** when the end of
   the route is more than 100 m in a straight line from the place
   (`GuidanceStatusUi.Arrived.awayText`, `DrivePresenter.DESTINATION_AWAY_OVER_M`, tag
   `destination-away`; the DriveUi.kt contract block was updated).
5. **Banner cases** (review, minor). The note in `banner_cases.json` is kept. One sentence was
   added: exit24 is the case that keeps the app's own request (its end tied as the planner ties
   it). That already held, so nothing else was needed.
6. **An address is tied to the street it names** (owner). valhalla-mobile 0.6.4 has no `locate`
   call: its JNI offers only route, matrix, height, trace_route and trace_attributes. The planner
   therefore asks **one `trace_attributes`** (map_snap) over a grid of point pairs every 50 m
   within 300 m of the place. Each pair is farther than `breakage_distance` from the next, so it is
   matched alone to its nearest edge within 50 m. Among the answer's edges, the nearest one whose
   name equals the address street is used. Names are compared in lower case on letters and digits,
   and the `words` table expands "E", "St" and similar. The edge must be of a road class that the
   tie allows. That edge gives the point, kept 10 m inside the edge so that it is not a crossing.
   The location becomes that point with the place as `display_lat`/`display_lon`, which Valhalla
   3.9.1 honours for the side of the street (checked: right, left and the side rule all work on
   it). The stop tie still applies.
   - If no such street is found, or the call fails, the place is used as before.
   - The probe request is computed in whole micro-degrees, so Python
     (`address_probe_request`/`address_tie_point`) and Kotlin (`AddressTie`) send exactly the same
     request, and the reference recordings answer it.
   - **Where the street comes from:** `:nav:session` `StopTies` reads it from the place's own
     texts. For an address result it is the name ("155 East 12th Street"). Otherwise it is the
     first "number street" part of the second line ("155 East 12th Street, Erie", or a saved
     place's "Museum, 150 East Front Street, Erie").
   - `EngineRouteService` keeps the points it finds per routing set (`AddressTies`, up to 32), so
     a re-route does not probe again.
   - Python: the reference trips' places carry no address, so none of them changed. The new plan
     cases carry `_street`.
7. **A dropped pin is not tied** (owner). This covers a `MAP_PIN` place and any place whose second
   line is coordinates (a saved pin, a recent pin). Such a stop gets no `search_filter` or
   `search_cutoff` (`Stop.tied` false, route_lib `_tie: "none"`), and the route goes to the road at
   the pin. `alley_factor` 5 still applies, and a test checks it.
8. **Lane warning**: unchanged, as the owner answered. The words stay, and the lane row with the
   continuing lanes highlighted was seen in C1's lane screenshots. No case without the row is
   known.

### Measured, before -> after

Unless noted, these are pyvalhalla 3.9.1 on the device tiles, with the review's sample: 150 Erie
POIs with an address (seed 11).

| What | Before (C1 as committed) | After |
|---|---|---|
| p4: 450 plans (150 places x 3 origins), arrivals on the left | 140 | 71 |
| p4: side route kept | 54 | 129 |
| p4: plans with more U-turns than without the C1 rules | 7 | 1 |
| p4: plans more than 60 s slower than without the C1 rules | 2 | 1 |
| p9: left arrivals of the tied route (from Perry Square) fixed by the side rule | 19 of 66 (tertiary) | 44 of 66 (every street; within 60 s, no extra U-turn) |
| p10: side routes on residential streets | 57 found | asked now |
| Plans ending on the address street (measure_street.py, 450 plans) | 236 | 411 (the street found for 146 of 150 places) |
| Address probe time (PC, pyvalhalla) | - | 43 ms median, 82 ms p90, 124 ms max |

The review's named cases, from `c1/fix/after-cases-ut.txt`. "Old" means without the C1 rules.
"Untied" is the same request without the stop tie; `alley_factor` still applies to it.

| Case | Old | Tied | Untied | Plan after |
|---|---|---|---|---|
| Ulta Beauty from Perry Square | 663 s, 0 U-turns | 628 s, 1 U-turn | 663 s, 0 | untied, then the side: 668 s, on the right, 0 U-turns |
| Ulta Beauty from exit 24 | 483 s, 0 | 447 s, 1 | 483 s, 0 | untied + side: 488 s, right |
| Carter's (Perry Square / exit 24) | 660 / 480 s, 0 | 629 / 449 s, 1 | 660 / 480 s, 0 | untied + side: 665 / 485 s, right |
| Five Below (Perry Square / exit 24) | 655 / 475 s, 0 | 632 / 452 s, 1 | 655 / 475 s, 0 | untied + side: 658 / 478 s; with its address street, Interchange Road: 535 / 404 s |
| American Legion Hall, 260 E 3rd St, from exit 24 | 771 s | 912 s (15.9 km) | 771 s | untied 771 s; with its address street: tied to East 3rd Street, 760 s |
| Dr. Mitchell S. Humphrey, State St, from exit 24 | 519 s, 0 U-turns | 515 s, 1 | 524 s, 1 | tied kept: the untied route has the same U-turn, which comes from `alley_factor`, not the tie |
| Octapharma Plasma from the east | 323 s | 387 s | 323 s | tied kept: +64 s is within 90 s; on the left (the side route did not qualify) |

The owner's cases (`c1/fix/after-cases-addr.txt`, from Perry Square):

- Family Dollar, 155 East 12th Street: the point found is on East 12th Street, 49 m from the place.
  Holland Street is nearer at 37 m. The route is 94 s and ends with "East 12th Street",
  arriving on the right. Without the address street it ended on Holland Street (107 s).
- The same point as a dropped pin: not tied. The route ends on the service road at the pin
  (117 s).
- Address street out of reach (falls back): Ulta Beauty and Carter's (Interchange Road is more
  than 300 m from inside the mall), Octapharma Plasma ("Erie Central Mall" is not a street), and
  Baycrete (Greengarden Boulevard).

**Reference plans re-recorded.** `reference_routes.py --device data/routing/device` changed none
of the 336 format-2 plans and none of the 355 numbers in the old-format file
(`c1/fix/diff_plans.py`). The reference trips' ends are city landmarks. Their tie check keeps the
tied route in all 328 plans where it runs, and the side rule gives the same answers on every
street as it did on tertiary streets and above. The 8 plans without a tie check are Simpler
alternates. Four plan cases are new, with five runs: `address_street` (Fastest and Simpler, Family
Dollar, East 12th Street), `address_out_of_reach` (Ulta Beauty: the nearest street, then the
untied route), and `dropped_pin`. The plan summaries now also carry `tie_check` and
`arrival_side`, and the Kotlin test compares both. Session recordings grew from 213 to 304 through
the `record_session.py` loop: the tie check's requests, and the exit 24 pin's untied requests.

### Proved (and how to re-run)

- Review cases: from `data/review/batchC1-routing/`, run `<venv>/python p4.py` (before:
  `c1/fix/before-p4.txt`; after: `c1/fix/after-p4-nostreet.txt`). p9 and p10 measure the cutoffs
  directly (`c1/fix/before-p9.txt`, `before-p10.txt`). Also run
  `.venv/routing/Scripts/python data/r3-run2/c1/fix/cases_ut.py`, `cases_addr.py` and
  `measure_street.py`.
- `python -m unittest packbuilder/routing/test_presets.py`: 21 OK (7 new: the tie check both
  ways, side only on the right, every street, a pin not tied, street_key, the address point).
- `python packbuilder/routing/test_routing.py`: 25/25.
- `sh tools/round/gradle.sh n :routing:core:test`: 44 tests, 0 failures. PlansReferenceTest
  compares every case including the new four, and a new test checks Family Dollar on East 12th
  Street with the display point, Ulta UNTIED with 0 U-turns, and a pin without `search_filter`
  and with `alley_factor`.
- `:nav:session:test`: 101 tests pass. Exit24DriveTest passes and `testdata/nav/exit24.json`, the
  golden trace, is unchanged. The new `StopTiesTest` has 3 tests. In O8 a plan is now counted as
  one request: the exit 24 destination there is a pin, so there is no untie request after a 171.
- `:feature:drive:testDebugUnitTest` passes, with the new arrival distance test ("Destination 500
  ft away" at 150 m, none at 90 m).
- Whole JVM suite: `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh n test --continue
  -Pbeamline.routingPacksWork=<abs>/data/gradle/n/routing-packs-test`. Result: 753 tests, 0
  failures, 1 skipped (`c1/fix/all-tests.log`).
- `sync_data.py --check`: exit 0. `tools/check_contracts.py`: all the same, frozen files
  unchanged.
- Emulator beamline_n, section 8's clean exit 24 drive. It was run because the request changed:
  the drive's destination is a pin from a test extra, which is now not tied, and every plan has the
  tie check. Command: `data/r3-run2/m/drive.py default data/r3-run2/c1/fix/e2e/default day`, with
  the test-extras build `c1/fix/apk/beamline-release-tx.apk` installed over C1's. Banners came at
  171.5, 222.8 and 251.9 s and ARRIVED at 290.1 s (C1: 170.3/221.4/250.7/288.9). In the
  `default-arrived.png` screenshot the route ends at the pin on Downs Drive, and the card reads
  "Arrived / Downs Drive / Done" with no distance line, because the end is under 100 m from the
  place.
- Phone APK: `sh tools/round/gradle.sh n :app:assembleRelease :app:lintRelease` passes. Lint has
  0 errors and 54 warnings, and the release `BuildConfig.TEST_EXTRAS` is false. The APK is
  `data/android/apks/batchC/beamline-release.apk`, 0.1.4 / versionCode 7, 85,248,785 bytes,
  SHA-256 1ee13ba526157ce584f5561ed7fe516389d957a67f89c98dbcb1d5500ea51163.
- On the emulator, the test build was uninstalled and batchB 0.1.3 (versionCode 6) installed.
  `install -r` of batchC then gave Success and versionCode 7. In the screenshot
  `c1/fix/e2e/after-install-r.png` the app opens on the driver notice. The emulator is stopped, and
  its app data was cleared by the uninstall.

### Contract changes (C1 routing fix)

- docs/architecture.md 2.11 "Every request":
  - Rule 2 now covers far places and map points.
  - Rule 3: every street, type 5, alternates.
  - Rule 4 is new: the address street.
  - Rule 5 is new: the tie check.
- D2: the arrival card's distance line.
- The DriveUi.kt block (`Arrived.awayText`).
- Decision 6.77 reworded; decision 6.81 is new.
- presets.json `every_request`: `arrival_side.street_side_cutoff`, `tie_check`, `address_street`,
  and the notes.
- No frozen file changed. `Place` (core/api) was not changed: the street is read from the place's
  texts.

### Not verified (C1 routing fix)

- **The address probe on the device.** It uses `traceAttributesRaw`, the call the lane warning
  already makes on the device. The probe's own request (map_snap with breakage) ran only on
  pyvalhalla 3.9.1, which is the same engine version. The emulator drive goes to a pin, so it does
  not probe.
- **Probe timing on the phone.** It was measured only on the PC: 43 ms median.
- **A place whose second line does not start with its house number.** Some search subtitles may
  differ, and such a place falls back to the nearest street.
- **An address street name that differs beyond the `words` table** (for example "Route 5" against
  "PA 5"). Such a place falls back.
- **Simpler alternates** stay without the side rule and the tie check.
- **Nothing on the phone.**

## C2: the uncertainty bubble, batch B follow-ups, and the end of the batch

Status: **done on the emulator, 2026-11-18** (key m). Item 3 of docs/next-round.md with the owner's
answer (browsing only; guidance keeps the route-snapped position), the owner's answers after batch B
and the batch B review. Version 0.1.4 (versionCode 7). Nothing is committed: the lead commits. The
working record is `data/r3-run2/PROGRESS.md`, section "Batch C2"; scratch under `data/r3-run2/c2/`
(not in git). An earlier C2 engineer had been stopped part-way with most of the bubble and search
code written and no record; that work was read through, kept, finished and tested.

### What changed and why

#### Item 3: the uncertainty bubble and a steadier position while browsing (rule G7, decision 6.79)

- **The filter.** `:core:location` `PositionEstimator` (Estimate.kt), plain Kotlin over the fixes
  the app already gets from Android's `LocationManager` (GPS and network providers). Two filters,
  one at a time:
  - *Standing still* (the accelerometer's |a| varies by less than 0.15 m/s² over 2 s, and no fix of
    30 m or better reports 1 m/s or more): the mean of the fixes of the last 120 s (at most 12),
    each weighted by 1 / accuracy². A phone that does not move sees fixes scattered around one
    point; the weighted mean is the best estimate of that point, and an accurate fix counts far
    more than a poor one. The bubble is the fixes' weighted RMS spread around the mean, at least the
    mean's own error: about as wide as the fixes scatter, as the owner asked.
  - *Moving* (or no accelerometer): a constant-velocity Kalman filter per axis (measurement noise =
    accuracy², process noise 1.5 m/s²). With good fixes it follows them with no visible lag; poor
    fixes are smoothed as far as the time between them allows.
  - A fix more than 3 combined radii from where the estimate expects the phone starts it again at
    that fix (walking out of a building, or the accelerometer said still while the phone moved).
- **Why not more.** No Google Play services. Android's own fused provider is not added yet: it
  would hand the app the same fixes again (on a phone with Play services it is Google's fusion
  underneath) and needs a rule of its own in `FixFilter`; left for the phone test.
- **The puck.** When the last fix or the estimate is poorer than 25 m the puck stands on the
  estimate; while the estimate's radius is above 25 m a soft disc of that radius (whole metres, at
  most 2 km) is drawn around it: the puck's blue at 16 % by day and 22 % by night, a 1.5 dp edge
  blurred 2 dp, grey when the puck is grey, under every marker. A good fix: the puck on the fix, no
  bubble, as before. `VehicleMarker.Puck.bubbleM` (frozen `MapScene.kt`, block updated); the arrow
  has none, so it never shows while guiding (K7).
- **Thresholds** in `android/app/src/main/assets/rules/position.json` `bubble` (first guesses for
  the phone test). The accelerometer (`AccelerometerMotionSource` in `:app`) is read only while the
  puck is tracked. Logs: "puck bubble shown" / "gone" (no position, no size).
- **Emulator.** `geo fix` has no accuracy, so a test extra `--ef fix_accuracy_m <m>` (test-extras
  builds only) makes every fix report that accuracy.

#### Batch B follow-ups (decision 6.80)

- **a. The 20 truly nearest.** Enter asks the engine for 100 candidates (`on_map.candidates`), sorts
  them by distance and keeps up to 20 within 25 miles (`within_km` 40.23); when none is that close,
  the nearest 5 (`nearest_when_none_within`). Before, the engine's top 20 by rank were re-sorted, so
  a nearer match ranked 21st was missed.
- **b.** The results end (pins and list) when a state one of them came from is removed while they
  show (`SearchFeature.closeResultsGoneFrom`, called by AppFlows on every installed-set change).
- **c.** The results list is opaque (the panel colour is 95 % opaque elsewhere).
- **d. Hours, phone and website without a search id** (a POI tapped on the map, a saved place, a
  recent): looked up in the installed pack covering the point by name and position. The first
  version (one POI of that name within 100 m, else nothing) found nothing for a real Sheetz on West
  Ridge Road in Erie: it is a convenience store and two fuel canopies within 40 m. Now
  `SearchEngine.detailsNamedNear` takes the same-named POIs within 100 m and shows their details
  when every one that has any has the same ones (there only the store has them); different details
  or none: nothing, never a guess. A dropped pin is never looked up.
- **e.** Call and Website are offered again once the trip has ARRIVED (`placeSheet(..., guiding)`),
  never while guiding or rerouting.
- **f.** `OpeningHoursTest` has five good-then-bad texts among the unreadable ones
  (`Mo-Fr 08:00-17:00; garbage`, `...; Sa 10:00-`, `..., Dec 25 off`, `...; Sa sunrise-sunset`,
  `24/7; Su 18:00+`): all give nothing. The `OpeningHours` comment says the status works on
  wall-clock time, so a closing time inside a daylight-saving change hour can be off by an hour.
  "Closing soon" stays the last hour.

### Proved (and how to re-run)

- Modules touched: `sh tools/round/gradle.sh m :core:location:test
  :feature:search:testDebugUnitTest :map:scene:test :map:layers:testDebugUnitTest`: 158 tests, 0
  failures. New: `BubbleTest` 7 (indoors 100 m every 20 s: the estimate moves less than 0.35 of the
  fixes' jumps, its error under 0.6 of theirs, bubble 45-110 m; one poor fix gives its own radius; a
  good fix no bubble and no lag; walking with 8 m fixes and driving with 5 m fixes at 25 m/s, also
  with the accelerometer reading still: no bubble, worst error under 10 m and 12 m; leaving a
  building starts again at the first good fix; the motion detector; the tracker with a still
  accelerometer draws the bubble and puts the puck on the estimate); `MapSceneMapperTest` (bubble by
  day and night, grey when stale, none for a good fix, the arrow while guiding); `LayersJvmTest` (the
  ring, its colour, the layer order); `SearchResultsTest` +4 (a, b, d, e).
  `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh m :core:search:test --tests '*OpeningHoursTest*'
  --tests '*PoiDetailsPackTest*'`: 10/10 (the real PA pack: the Sheetz above found from a point 40 m
  away; too far, another name, an empty name, and all Sheetz within 30 km with different details:
  nothing).
- The whole JVM suite once, for the batch: `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh m test
  --continue -Pbeamline.routingPacksWork=<abs>/data/gradle/m/routing-packs-test`
  (`c2/all-tests.log`): 748 tests, 0 failures, 1 skipped (JUnit XML; routing modules from the build
  cache of C1's identical inputs). Search conformance (`.venv/search/Scripts/python -I
  android/core/search/conformance/compare.py`, the Kotlin answers written by this run): 1,079/1,079
  equal.
- Checks: `check_contracts.py` all the same, frozen files unchanged after `--freeze`;
  `check_private_logs.py scan` 0 breaches; `check_network_rule.py scan` 0, `privacy` 0;
  `android/routing/scripts/sync_data.py --check` exit 0. No dependency changed in batch C, so the
  notices gate was not run.
- Emulator beamline_m, release test-extras build (`c2/apk/beamline-release-tx.apk`), Pennsylvania
  installed from the file: `. tools/round/env.sh m; .venv/android/Scripts/python
  data/r3-run2/c2/bubble.py shots data/r3-run2/c2/shots 60` (and `... 120`). Fixes scattered about
  60 m around Perry Square in downtown Erie once a second, the camera at zoom 16. Seen on the shots:
  - `bubble-60m-day.png`: a blue puck on South Park Row with a faint blue disc about 140 m across
    and a soft edge, streets and labels readable under it; `bubble-60m-night.png`: the same disc,
    darker blue, on the night map;
  - `bubble-120m-day.png` / `-night.png`: a grey puck and a grey disc (120 m is above the grey
    limit of 100 m, so the puck is grey as before, and the bubble follows it);
  - `good-fix-after-60m-day.png`: accuracy back to the emulator's own, fixes on the square's centre:
    the bubble is gone and the puck sits on the fix (with its compass cone);
  - logs (`c2/shots/log-60m.txt`): "fix accuracy set", "puck bubble shown", then "fix accuracy
    back", "puck bubble gone" within a second.
- Builds: `sh tools/round/gradle.sh m :app:assembleDebug :app:assembleRelease :app:lintRelease`
  pass, lint 0 errors. Phone APK `data/android/apks/batchC/beamline-release.apk` (plain release,
  no test extras): 85,247,505 bytes, SHA-256
  `fc66c92951fc58584ea96962919b68d0c3ab689ab2045af82f2a71d96306d4a7`, 0.1.4 / versionCode 7. On the
  emulator it installs with `install -r` over batch B's 0.1.3 (versionCode 6) and opens.

### Contract changes (C2)

- docs/architecture.md: rule G7 (3.1); S10 (details by name and position for places without a
  search id; Call and Website again once ARRIVED); S11 (100 candidates, 20 within 25 miles or the
  nearest 5, opaque list, results end when their state is removed); decisions 6.79 and 6.80; the
  test extra `--ef fix_accuracy_m` in the 5.4 list; the quoted `MapScene.kt` and `search_app.json`
  blocks. docs/architecture-saved-places.md: the quoted `SearchFeature` (`placeSheet(..., guiding)`,
  `closeResultsGoneFrom`). Frozen hashes re-recorded (`MapScene.kt`, the `:core:location` files).
- Rules files: `position.json` `bubble`; `packbuilder/search/search_app.json` (`on_map.within_km`,
  `nearest_when_none_within`, `candidates`; `details.match_within_m`) and its copy.

### Not verified (C2)

- Nothing on the phone. The bubble's thresholds are first guesses; a phone held in the hand indoors
  may read as moving, and then the moving filter follows each poor fix more closely.
- Follow-ups a-e are proved by JVM tests only, not on the emulator; the opaque list was not looked
  at on a screen. When a state is removed while one result's sheet is open, the results and pins
  end but the sheet stays.
