# 25. Next run, batch E: a route to a business ends in its own lot

Status: **done, 2026-11-19** (key n). Item 18 of docs/next-round.md with the expected result agreed
with the owner, and the owner's answers after batch D (no other routing change). Version
**0.1.6 (versionCode 9)**. Nothing is committed: the lead commits. Scratch, probes and logs are in
`data/r3-run2/e/`, which is not in git (the owner's own restaurant appears only there; this report
calls it "a restaurant on Peach Street").

## What changed and why

### 1. Which places count as a business

A stop is a **point of interest** (`Stop.pointOfInterest`; route_lib `_poi`) when `:nav:session`
`StopTies.isPointOfInterest` says so:

- a POI tapped on the map (`PlaceSource.MAP_POI`), or
- a place whose category is the category of a search POI. That is a category code of the pack's
  category table ("restaurant", "fuel") or a raw OSM tag ("shop=florist"). A recent, a resumed trip
  or a saved place made from a search POI keeps its category, so it counts too.

These are **not** points of interest:

- an address result (category "address");
- a street or a place of the search pack (road classes; city, town, ZIP and the other place types;
  the list is in `StopTies`);
- a place without a category (a saved pin, say);
- a map point.

None of these changes. When the app is unsure (no category), it keeps today's behaviour.

### 2. The lot rule (2.11 "Every request" 6; `every_request.lot` in presets.json)

A point of interest gets **two probes**. Both are `trace_attributes` calls, because valhalla-mobile
has no locate call:

- **The address probe, with `edge.use` added.** A filter does not change the match, so the address
  street's point is exactly what it was before this batch.
- **A small lot probe:** a 10 m grid within 30 m of the place, also with `edge.use`.

The probes are needed because the 50 m grid alone matched the pair at the owner's restaurant to a
driveway 20.5 m away that is reached from Mandy Lane. The driveway 20.0 m away, reached from
Commons Drive (the owner's reference), was missed.

**How the lot point is chosen.** The candidate is the nearest edge whose use is `driveway` or
`parking_aisle`. Alleys, drive-through lanes and other service roads are never used. The candidate
must also be:

- within **80 m** of the place, and
- at least **10 m nearer** to the place than every street of the classes the stop tie allows
  (residential to trunk).

The point sits 10 m inside that edge. The stop then has:

- that point as its location and the place as `display_lat`/`display_lon`;
- no `search_filter` (rule 2 would take the stop off the lot);
- `preferred_side` "either". This is Valhalla's default, so the route does not change. It marks
  the stop: the arrival side is not asked there, and the tie check knows the stop by it.

If no such edge exists, or a probe fails, the stop gets rule 4 (the address street) and then rule 2,
as before. The plan is then the same as in 0.1.5.

**Why the 10 m street margin.** With "nearer than every street" alone, 25 of the 38 downtown places
in the sample found some driveway 1 to 5 m nearer than their own street. One example is a shop on
State Street. With the margin, 13 of 38 still end in a lot, all of them clearly beside a driveway
(for example a driveway 9 m away and the street 30 m away) (`e/lotdist.py`, `e/downtown.py`).

**The tie check (rule 5).** A lot route is compared with **the route the stop gets without the lot
rule**. To get that route, the stop is put back on its street end (the address point, else the
place, tied as before), and the ordinary tie check is then run on that route (route_lib `unlot`,
`EveryRequest.unlot`). The lot route is kept when it is at most 90 s slower and has no more U-turns;
otherwise the plan is the route without the lot. I tried two other references first and dropped
both:

- **The place untied.** The check never fired, because the untied place snaps to the same lot.
- **The address point without the tie.** For a place with no address street this is the same as
  the place untied. One store's lot route was 187 s longer than ending on the street and was still
  kept.

`AddressTies` keeps both points per place, so a re-route does not probe again. Order of a plan:
rules 4 and 6, the type's requests, rule 5, rule 3.

### The owner's case (a restaurant on Peach Street, Millcreek)

The lead's check found a driveway 20 m away, Mandy Lane 45 m, Peach Street 48 m and a parking aisle
64 m. With the lot rule (`e/owner_case.py`):

| From | 0.1.5 | Batch E |
|---|---|---|
| the north | Interchange Road, Peach Street, ends **on Peach Street** (address point 64 m from the building) | **Peach Street, Douglas Parkway, Commons Drive, into the lot** (63 m, 24 m), ends 20 m from the building |
| the south | US 19 / Peach Street, ends on Peach Street | **US 19, Downs Drive, Commons Drive, into the lot**, ends 20 m from the building |

This is the agreed result. The tie check kept the lot route in both cases: +77 s and +37 s against
the street end. The street end does not count the driving inside the lot.

### The other cases of item 18 (public stand-ins; reference cases `lot_*` in plans.json)

| Case | Result |
|---|---|
| Eat'n Park, 7355 Peach Street (a restaurant beside the owner's, its lot behind it) | ends in its lot. Driveway 15 m away, Mandy Lane 41 m. From downtown Erie: Peach Street, Douglas Parkway, Mandy Lane, into the lot. On the emulator from Peach Street 1.5 km north: the same (screenshot below) |
| Family Dollar, 155 East 12th Street (the C1 alley case) as a business | East 12th Street, then its own lot by a driveway (26 m + 10 m). **No alley** (edge uses of the last 200 m from every origin: road, then driveway) |
| A house address (an address result) | unchanged: rule 4, its own street (code path: not a point of interest; the `address_street` reference case is unchanged) |
| Shoe Fly, 7520 Peach Street (a strip plaza) | Downs Drive, Scott Drive, into the plaza lot, 22 m from the store |
| Domino's, 1402 Turnpike Street (downtown, driveway 20 m, its street 12 m) | no lot: Turnpike Street as before; the side rule as before |
| Five Below, Millcreek Mall plaza (from downtown Erie) | the lot route is more than 90 s longer than the street end, so the tie check keeps Interchange Road (UNTIED) |

## Measured on the C1 review's 450 Erie plans

The sample is 150 POIs with an address × 3 origins (`data/review/batchC1-routing/p4.py`, seed 11;
the address street as in `d/measure_side.py`). Old = 0.1.5; new = the same places as points of
interest (`e/measure_lot.py` -> `e/measure_lot.txt`).

| | 0.1.5 | batch E |
|---|---|---|
| plans that find a lot | – | 234 (76 of 150 places) |
| routes ending in a lot | 0 | **227** (lot point median 18 m from the place) |
| lot routes the tie check sends back to the street end | – | 7 (identical to 0.1.5) |
| routes changed | – | **227** (every plan not ending in a lot is identical to 0.1.5) |
| more U-turns than 0.1.5 | – | **0** |
| an alley on the last 300 m | – | **0** |
| a drive-through lane on the last 300 m | – | **3** (one car wash, from 3 origins: the route passes the wash lane to reach the aisle behind it) |
| lot point nearer another POI of the pack (by 15 m) | – | 3 (Ross, whose plaza lot is shared with Max & Erma's) |
| more than 60 s longer than 0.1.5 | – | 13 (60-132 s; in-lot driving, and 0.1.5's side route was sometimes faster than its own plain route) |
| arrival-side requests | 192 | 97 |
| PC planning time, median / p90 | 66 / 107 ms | **83 / 126 ms** |
| engine calls per plan, route / trace_attributes | 2.43 / 1.0 | 2.74 / 2.0 |

Edge uses were read with `edge_walk` on the route's own shape. `map_snap` on the route's tail
wrongly reported a drive-through at one lot end. A lot that "belongs to another place" is only
measured by the nearest-POI test above; I found no case of a lot across a street.

## Emulator (beamline_n, 0.1.6)

- **Install.** `install -r data/android/apks/batchD/beamline-release.apk` (0.1.5) and then
  `install -r data/android/apks/batchE/beamline-release.apk`: both Success, versionCode 9. The app
  opens on the map; Pennsylvania is still installed.
- **The stand-in restaurant through the real UI.** Location set to Peach Street 1.5 km north. I
  searched "Eatn Park", tapped "Eat'n Park, 7355 Peach Street, Erie" and then Directions. The route
  sheet shows Fastest · Simpler, 2 min, 1.0 mi, via Peach St, Douglas Pkwy.
  `e/emu/04-route-sheet.png` and its crop `e/emu/04-route-sheet-end-crop.png` show the route
  leaving Peach Street at Douglas Parkway, turning right and ending in the lot just past the pin,
  not on Peach Street. The same place as an address result ("7355 Peach Street", not a point of
  interest) ends on Douglas Parkway beside the pin (`e/emu/timing/address-end-crop.png`). The PC
  route from that point: Peach Street 1316 m, Douglas Parkway 105 m, Mandy Lane 78 m, driveway
  41 m, 115 s.
- **Planning time** (PLANNING -> CHOOSING, both types, `e/measure_emu_lot.py`):

  | Round | Business (two probes) | Its address (one probe) |
  |---|---|---|
  | first (with probes) | 392 ms | 371 ms |
  | later rounds (`AddressTies` remembers the points) | 383 / 378 ms | 364 / 368 ms |

- **Section 8 clean drive** (`data/r3-run2/m/drive.py default`, test-extras build): banners at
  170.2 / 221.3 / 250.3 s and ARRIVED at 288.4 s (batch D: 170.3 / 221.3 / 250.4 / 288.4). The
  arrival screen shows "Arrived, Downs Drive". The drive's destination is a pin, so the lot rule
  does not apply to it.
- The emulator is stopped.

## Reference plans re-recorded

`reference_routes.py --device data/routing/device` changed:

- **plans.json: 0 of 252 plans** (no trip is a point of interest), plus **5 new cases, 6 runs**:
  `lot_restaurant` (Fastest, and Simpler with alternate 1), `lot_plaza`, `lot_alley_case`,
  `lot_none_downtown` and `lot_back_to_street`.
- recordings-plans.json.gz grows by their requests.

trips.json, exit24-osrm (the golden trace), banner-routes, reorder and errors are unchanged.
PlansReferenceTest compares Kotlin with Python on all 25 case runs. Its new test checks the lot,
the side street, no side request, Family Dollar's lot, Domino's street and Five Below's fallback.

## Proved (and how to re-run)

- `.venv/routing/Scripts/python packbuilder/routing/test_presets.py` (unittest): 30 OK. The 3 new
  tests cover `lot_tie_point` (driveway, aisle not alley, no drive-through, the street margin, 80
  m), the two probes and the tie check against the route without the lot, and the validator.
- `packbuilder/routing/test_routing.py`: 24/24.
- `sh tools/round/gradle.sh n :routing:core:test`: passes (50 tests).
- `:nav:session:test`: 103 tests, 0 failures. StopTiesTest has a new test for
  `isPointOfInterest`.
- The whole JVM suite: `BEAMLINE_TESTS_STRICT=1 sh tools/round/gradle.sh n test --continue
  -Pbeamline.routingPacksWork=<abs>/data/gradle/n/routing-packs-test` gives BUILD SUCCESSFUL,
  **761 tests, 0 failures, 1 skipped**.
- `android/routing/scripts/sync_data.py --check`: 0.
- `tools/check_contracts.py`: all the same; the 43 frozen files are unchanged.
- `android/scripts/check_private_logs.py scan`: 0 breaches.
- `check_network_rule.py scan`: 0; `privacy`: 0.
- Builds: `sh tools/round/gradle.sh n :app:assembleDebug :app:assembleRelease :app:lintRelease`
  pass. Lint: 0 errors, 54 warnings, 1 hint.
- Phone APK `data/android/apks/batchE/beamline-release.apk` (plain, TEST_EXTRAS false):
  85,250,093 bytes, SHA-256 `aed454fb2e552cd17a959cbce6e014f091a3b967866aa01aaf554eaa18aeeb3e`.
  The test-extras build is at `e/apk/beamline-release-tx.apk`.

## Contract changes

- **docs/architecture.md 2.11 "Every request":**
  - rule 2: a lot stop gets no stop options;
  - rule 3: a lot stop is not asked for the side;
  - rule 5: the lot's tie check, and the order of a plan;
  - **new rule 6** (the lot rule);
  - decision **6.83**.
- **presets.json:** `every_request.lot` {within_m 80, uses [driveway, parking_aisle],
  street_margin_m 10, probe_step_m 10, probe_within_m 30} and `lot_note`. Both copies are synced.
- **route_lib:** `_poi`, `LOT_SIDE`, `_unlot`, `address_probe_request(use)`, `lot_probe_request`,
  `lot_tie_point`, `unlot`, `tie_check(lots)`, and the arrival side skips stops that already have
  `preferred_side`.
- **Kotlin `:routing:core`:**
  - `Stop.pointOfInterest`;
  - `EveryRequest.lot` / `Lot` / `LOT_USES` / `LOT_SIDE` / `unlot`;
  - `AddressTie.request(use)` / `lotRequest` / `lotPoint`;
  - `AddressTies.Found`;
  - `RoutePlanner.resolve` / `tieCheck` / `arrivalSide`.
- **Kotlin `:nav:session`:** `StopTies.isPointOfInterest` and `SessionCore.placeStop`.
- **Other files:** android/routing/README.md; reference_routes.py cases.
- **Version:** 0.1.6, versionCode 9.

## Not verified

- **The owner's phone.** Nothing was installed on it. Batch E has not been driven on a real road.
- **The real lot owner.** Whether every lot found belongs to its place is not known from the map
  data. Only the "nearer another POI" test was run (3 shared plaza lots).
- **The drive-through pass.** The car wash's route crosses its drive-through lane to reach the
  aisle behind it. Valhalla 3.9.1 has no auto factor for drive-throughs, so nothing in this batch
  prevents it.
- **Multi-stop trips.** When several stops end in lots, the tie check compares the whole request
  with all of them put back on their street end. This is tested only with fakes (one stop).
- **A map POI tapped on the map.** It counts as a point of interest, but I did not try it on the
  emulator; only the search path was tried.
- **The emulator figure.** The difference with and without the lot rule (about 20 ms on the first
  plan) is within the noise of three rounds.

## Questions for the owner

- **Long lot routes.** Is a lot route up to 90 s longer than ending on the street acceptable? In
  the sample 13 lot routes are 60-132 s longer than 0.1.5, mostly mall and plaza lots with long
  aisles.
- **Downtown.** Downtown businesses with a driveway right beside them now end in it, for example a
  funeral home 11 m from a driveway with its street 23 m away. Is that wanted, or should downtown
  keep the street?
