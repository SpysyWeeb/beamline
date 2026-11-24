# 26. Next run, batch E2: one end point for every route type

Status: **done, 2026-11-19** (key n). Item 19 of docs/next-round.md: the plan agreed with the owner, the
lead's checks and its cases, and the owner's answers after batch E (no car-wash exception: one general
rule). Version **0.1.7 (versionCode 10)**. Nothing is committed: the lead commits. Scratch, probes and
logs are in `data/r3-run2/e2/`, which is not in git (the owner's own restaurant appears only there; this
report calls it "a restaurant on Peach Street").

## What changed and why

On 0.1.6 the end checks (the lot, the stop tie's check, the arrival side) ran **per route type, on that
type's own route**. To a restaurant on Peach Street, Simpler's own way into the lot was 94-95 s slower
than its own street end, just over the 90 s limit, so Simpler fell back to Peach Street while Fastest
ended in the lot. The owner: "The destination point is non-negotiable. The route should end at the same
point no matter what route choice."

### 1. The possible ends of a stop (2.11 "Every request" 7)

Each stop after the first gets a list, best first (route_lib `stop_ends`, `RoutePlanner.ends`):

| Stop | Ends, best first |
|---|---|
| a point of interest (batch E's `Stop.pointOfInterest`) | its own lot (rule 6), its address street (rule 4), the nearest street (rule 2) |
| a place that names its street (a house address) | that street, the nearest street |
| any other tied place (a street, a city) | the nearest street |
| a map point, a saved or recent pin | exactly where it is |

### 2. One decision per plan (2.11 "Every request" 5 and 7)

The ends are tried in that order with the **plan's Fastest request with the driver's options**
(`decision_preset`: "simpler+avoid_tolls" decides with "fastest+avoid_tolls"), stop by stop. An end
passes when:

- its route finds a way;
- it takes at most 90 s longer than the route to the **next end on the list**, with no more U-turns;
- the last 300 m of its way use no alley and no drive-through lane. This is one `trace_attributes` call
  on the route's shape (`edge_walk`, else `map_snap`), asked only for the end about to be kept.

The first end that passes is kept; when none passes, the last one is kept. The arrival side (batch D's
rule) is then asked **once**, on the route to the chosen ends.

The decided locations (point, stop tie or none, side options, the boxes of section 3) are marked
decided (`Stop.fixed`, `Stop.end`) and returned in `PlanResult.Ok.ends`. A decided stop is sent as it
is: nothing is probed or checked again.

- **Every route type** routes to those ends. Simpler's pool picks among routes to the same point and
  asks no tie check or side request of its own.
- **`:nav:session`** plans the first route type, then plans every other type with the decided stops.
  It keeps them by place for the trip (`SessionCore.decidedEnds`, in memory), so **every re-route and
  stop change** sends the same ends.
- A new plan decides again, and so does a trip resumed after the process died (from where the car is).

0.1.6's "untied" fallback (the place without the street tie) is on no list any more. A tied place with a
single end has nothing to check.

### 3. Drive-through lanes and alleys left out of every route (2.11 "Every request" 8)

The probes a point of interest already gets (address and lot) also show the edges whose Valhalla use is
`drive_through` or `alley` within 80 m of the place (`left_out_rings`, `AddressTie.leftOutRings`).

- **The boxes.** Each such edge gets a 4 m square at its middle. A box is made only where that middle
  is more than 3 m from both ends of the edge and from every other probed edge, so it never touches
  another edge. Every request of the plan carries the boxes as `exclude_polygons`.
- **The limits.** At most 20 rings of 5 vertices: the app's engine allows 100 vertices and 10 km of
  perimeter (`service_limits` in valhalla_base.json). valhalla-mobile hands the request JSON to the
  engine unchanged (`ValhallaEngine.route` calls `routeRaw` with the request text). The session test's
  OSRM guidance request carried the boxes through pyvalhalla. On the emulator the plan to the stand-in
  succeeded with the same route as the PC, but that route does not depend on its boxes.
- **When no route is found.** A request that finds no route with the boxes is asked once more without
  them (route_lib `route`, `Calls.route`). The tail check then rejects a way in through a lane or an
  alley, so a lot reached only through its drive-through lane fails and the next end is used.

**Up front or only after a route touched a lane?** Up front: the edges come from probes the stop
already asks, so there is no extra call. `measure_rings.py` (450 plans x 2 types, with and without the
boxes):

- Fastest is the same route in 450 of 450. No box changed a route that did not need it.
- Simpler changes in 4 routes, all of which reached a lot through an alley in their last 300 m without
  the boxes (+0 to +20 s).
- The cost is +5 ms per two-type plan (median).

"After a touch" would cost a tail call per route type and a re-ask, for the same result.

**Why alleys are in the list too:** the end is judged on Fastest only. Without them Simpler's pool
reached 4 lot ends through an alley (`measure_e2_dt_only.txt`), and the owner's C1 rule is "no alleys".

## The cases (reference cases in plans.json; stand-ins only)

| Case | 0.1.6 | 0.1.7 |
|---|---|---|
| A doughnut shop on Peach Street (Krispy Kreme), from the west (`end_same_west`) | Fastest in the lot (583 s, 10 instructions); Simpler on Peach Street (519 s, 5) | both in the lot, the same point; Simpler 613 s, 9 instructions, via Greengarden Blvd from the north |
| the same from the north / south (`end_same_north`, `end_same_south`) | both in the lot | both in the lot, the same point (707/750 s; 624/624 s) |
| the same with Avoid tolls (`end_same_avoid`) | - | decided with Fastest + Avoid tolls; the same point |
| a car wash on West 12th Street (Top Notch Auto Wash; `car_wash_street`) | up the wash lane to the aisle beside the building (287 s from Perry Square) | lot fails on the tail check (its way in is the wash lane), ends on West 12th Street at the entrance, 260 s; both types; 666 and 497 s from the two other origins (0.1.6 696, 524) |
| a drive-through restaurant with an ordinary entrance (McDonald's on Peach Street; `drive_through_lot`) | in its lot | in its lot, same route (372 s from the south-east origin), its 5 lane boxes on every request, no lane used |
| Family Dollar, 155 East 12th Street (`lot_alley_case`) | its own driveway, no alley | the same, Simpler too |
| a house address (`address_street`, Family Dollar as an address) and a downtown business with no lot (`lot_none_downtown`, Domino's on Turnpike St) | the address street | unchanged, both types |
| a re-route (Kotlin) | - | `PlansReferenceTest`: a plan from the west with the north plan's ends asks 1 engine call and ends at the same point; `StopTiesTest`: in a session, the re-route from the car sends the same destination and no probe or tail call |

The owner's restaurant itself (scratch `e2/owner_e2.py`, the lead's 5 origins): both types end at the
same lot point from all 5.

| Origin | Fastest | Simpler |
|---|---|---|
| north | 715 s, 10 instructions | 776 s, 5 |
| south | 644 s, 7 | 644 s, 7 |
| west 26th | 590 s, 9 | 608 s, 8 |
| west 38th | 494 s, 10 | 512 s, 9 |
| Perry Square | 756 s, 11 | 765 s, 6 |

This agrees with the lead's check: Simpler 18-61 s slower, with fewer instructions.

## Measured on the C1 review's 450 Erie plans (both route types; `e2/measure_e2.py` -> `measure_e2.txt`)

| | 0.1.6 | 0.1.7 |
|---|---|---|
| plans where Fastest and Simpler end at different points | 4 | **0** |
| drive-through lane in the last 300 m (Fastest / Simpler) | 3 / 3 (the car wash) | 0 / 0 |
| alley in the last 300 m (Fastest / Simpler) | 0 / 4 | 0 / 0 |
| U-turns added | - | 0 |
| ends: lot / address street / nearest street | 227 + 7 back to street | 224 / 218 / 8 |
| two-type plan on the PC, median / p90 | 201 / 284 ms | 123 / 170 ms |
| engine calls per two-type plan (route / trace_attributes) | 8.47 / 4.0 | 6.24 / 2.99 |

**Routes that changed:** Fastest 7 of 450, Simpler 26 of 450.

- **Car wash, lot to street (Fastest 3):** 27-30 s quicker each.
- **Address street to nearest street (Fastest 4):**
  - the address street end had a U-turn (Mercyhurst University, +38 s);
  - or it was more than 90 s slower than the nearest street (Erie Zoo x2, Kuhar Law: 6-17 s quicker).
  - 0.1.6 compared the street end with the same point untied instead.
- **Simpler, its own end replaced by Fastest's (11):**
  - lot to street: Five Below, Primanti Brothers, Erie Fire Department, the car wash;
  - lot to nearest street: Baycrete;
  - street to nearest street: the 4 above.
- **Simpler, same end, another way (15):**
  - 4 because the alley near the lot is now left out (Palermo Laundromat, Lavery Brewing);
  - the rest because the side of the street is now decided once on Fastest's route. Simpler no longer
    asks its own side request, so it either keeps Fastest's side options or no longer takes its own
    side route.
  - Times: median -11 s, range -173 to +69 s. The largest increases are Second Hand Rose +70 s and
    Fine Wine & Good Spirits +48 s (3 -> 6 instructions: Fastest's decision kept a side route that
    Simpler must follow too).

**Planning is cheaper**: the checks run once per plan instead of once per type, and the decision's
Fastest route is the Fastest type's own route (asked once).

## Emulator (beamline_n)

- **Planning, 0.1.6 against 0.1.7.** From a point in the west of Erie (emu geo fix -80.1080 42.1050),
  search "Krispy Kreme" then Directions; PLANNING -> CHOOSING for the whole two-type plan
  (`e2/measure_emu_e2.py`):
  - 0.1.6: 1286 (cold), 566, 566 ms;
  - 0.1.7 test-extras: 882 (cold), 484, 495 ms.
- **0.1.6's route choice sheet.** Fastest 10 min 6.4 mi via West Bayfront Pkwy and Peach St; Simpler
  9 min 6.2 mi. Zoomed on the end (`e2/emu/v016/pair-016.png`): Fastest's line turns off Peach Street
  into the lot and ends beside the pin. With Simpler selected, its blue line stops on Peach Street,
  while the grey (Fastest) line goes on into the lot.
- **0.1.7 plain release** (`install -r` over the batch E plain 0.1.6: Success, versionCode 10).
  - The sheet: Fastest 10 min 6.4 mi via West Bayfront Pkwy, Peach St; Simpler 10 min 6.4 mi via West
    Bayfront Pkwy, Greengarden Blvd (`e2/emu/v017/2a-simpler-sheet.png`).
  - Zoomed on the end (`e2/emu/v017/pair-017.png`). Fastest selected: up Peach Street and into the lot
    to the pin. Simpler selected: its blue line comes down from the north (Greengarden Blvd side) into
    the same lot and ends at the same small hook beside the pin, where the grey Fastest line also ends.
- **Section 8 clean drive** (test-extras 0.1.7, `data/r3-run2/m/drive.py default` -> `e2/e2e/default`):
  banners at 170.5 / 221.7 / 250.8 s (target 171 / 222 / 251), ARRIVED at 288.9 s.
- The emulator was stopped; it now has 0.1.7 test-extras and the Pennsylvania pack.

## Reference plans re-recorded (`android/routing/scripts/reference_routes.py`; diff `e2/diff_ref.py`)

- **New fields.** `plans.json` rows now carry `end` (the decided end of the last stop) and `end_point`
  (the route's last point); `PlansReferenceTest` compares both.
- **New and extended cases.** 7 new case runs pairs: `end_same_west/north/south/avoid`,
  `car_wash_street`, `drive_through_lot`. Simpler was added to `lot_alley_case` and
  `lot_none_downtown`. 39 case runs in all.
- **The 277 old rows.** 254 only lose the `tie_check: kept` label (one end, nothing to choose). 11
  routes changed:
  - Simpler and Eco U4 (none, tolls): they no longer take their own side route. Fastest arrived on the
    right, so no side was decided, and Simpler arrives on the left, 687.3 s instead of 731.1 s.
  - Simpler U4 with highways / both: Fastest + Avoid highways decided the side, so Simpler now takes
    the side route (731.1 s instead of 735.4).
  - Simpler and Eco I1 (none, tolls): Fastest's side request was asked and not kept.
  - `address_out_of_reach` (Ulta Beauty given as a plain place whose street is out of reach): one end
    (the nearest street) and no check. It now ends on Millcreek Mall Boulevard 191 m from the place
    with a U-turn (586.6 s); 0.1.6 took the untied route (621.9 s, 32 m). As a point of interest, which
    is how a search result reaches the planner, Ulta Beauty ends in its lot with no U-turn (620 s).
- **The other recordings.**
  - `trips.json` (the old format-1 plans): 4 rows, the same side effect.
  - Unchanged: `banner-routes.json.gz`, `exit24-osrm.json`, `exit24-osrm-device.json`, `reorder.json`,
    `errors.json`.
  - The exit 24 golden trace test passes unchanged.
  - Session recordings: +67 requests (`record_session.py`): the second route type now asks with the
    decided stops, plus the new session test.

## Proved (and how to re-run)

- **Python.** `cd packbuilder/routing && ../../.venv/routing/Scripts/python -m unittest test_presets`:
  31 OK (the decided ends, the next-end check with a slower end, a U-turn and an alley tail, the lot
  then street with a drive-through tail, Simpler to the same end with no probe, the boxes,
  the no-route fallback). `test_routing.py` 24/24.
- **Kotlin.** `sh tools/round/gradle.sh n :routing:core:test` and `:nav:session:test` pass. The whole
  JVM suite (`BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh n test --continue
  -Pbeamline.routingPacksWork=<abs>/data/gradle/n/routing-packs-test`): BUILD SUCCESSFUL, 764 tests, 0
  failures, 1 skipped (JUnit XML).
- **Python and Kotlin in step:** `sync_data.py --check` 0; `PlansReferenceTest` compares every
  reference plan's route, `end` and `end_point`.
- **Checks:** `check_contracts.py` all the same, 43 frozen files unchanged; `check_private_logs.py scan`
  0 breaches; `check_network_rule.py scan` 0, `privacy` 0.
- **Builds:** `sh tools/round/gradle.sh n :app:assembleDebug :app:assembleRelease :app:lintRelease`
  pass (lint 0 errors).
- **Phone APK:** `data/android/apks/batchE2/beamline-release.apk`, 85,250,893 bytes, SHA-256
  `569ddbb6459e08bfd2506d5643ea999f478268ef165d5d185e9e3287a1be42a8` (0.1.7 / 10).

## Contract changes

- **presets.json `every_request`:**
  - `tie_check` gains `tail_m` 300 and `never_uses` [alley, drive_through];
  - new `left_out` {uses [drive_through, alley], within_m 80, box_half_m 2, clear_m 3, max_rings 20};
  - notes `tie_check_note`, `left_out_note` and `lot_note` rewritten;
  - the Kotlin copy is synced.
- **docs/architecture.md 2.11:**
  - the "Simpler" paragraph (no per-type checks);
  - "Every request" 3 (the side once per plan), 5 (the ends' check replaces the tie check) and 6 (the
    lot is the first end);
  - new rules 7 (one end point per stop) and 8 (lanes and alleys left out);
  - decision row 6.84.
- **Code:**
  - `PlanResult.Ok.ends`; `Stop.end`/`fixed`/`avoid`; `StopEnd`;
  - `EveryRequest.LeftOut`, `TieCheck.tailM`/`neverUses`, `PresetSet.decisionPreset`;
  - `EveryRequest.unlot` and the per-type `tieCheck` removed;
  - route_lib `unlot`/`tie_check`/`resolve_stops` replaced by `stop_ends`/`decide_ends`/`left_out_rings`/`tail_uses`.
- **Other files:** android/routing/README.md; reference_routes.py (cases, `end`, `end_point`,
  alternates with the decided ends).

## Not verified

- Nothing on the owner's phone; no real drive.
- A multi-stop plan with lots is covered only by the code path, which decides stop by stop; no multi-stop
  reference case has a point of interest.
- The session keeps decided ends in memory only: a trip resumed after the process died decides again
  from the car's position.
- That valhalla-mobile on the device honours `exclude_polygons` is shown only by its code (`routeRaw`
  passes the request text, the engine config allows 100 vertices). No emulator case was found where the
  boxes change a route in a way the sheet shows: the Simpler alley cases differ by under half a minute.
  A rejected request would still plan, through the fallback without the boxes.
- The engine call count of a plan on the emulator is not logged by the app; the PC counts (Python and
  Kotlin make the same requests for the reference plans) stand in for it.

## Questions for the owner

1. **A plain place with no street to choose.** A place that is not a business and whose address street
   is out of reach (Ulta Beauty sent as an address) now ends on the nearest street even with a U-turn
   near the end. 0.1.6 took the place untied there. Should the place itself (untied) come back as a
   last end for such stops?
2. **The side of the street is decided once.** The side is now decided on Fastest's route, so Simpler
   either follows Fastest's side route or arrives on the left where 0.1.6's Simpler took its own side
   route (15 Simpler routes in the 450 changed their way; the largest +70 s). Is that acceptable?
