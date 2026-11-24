# 33. Front doors: a route to a large store ends at its front

Date: 2026-11-21. Item 28 of `docs/next-round.md`. Built and measured by the lead; data: the
Pennsylvania extract of 2026-11-14 and the PA+OH routing tiles of round 3.

## Summary

- **The fault.** A route to a Walmart Supercenter in Erie ended on the service road behind the
  building, 121 m from its entrance. A search entry that is an area uses its centroid; for a large
  store that is the middle of the roof, 60 m or more from every lane, and the lane nearest to it
  is as often the one at the back as the one along the front.
- **The fix.** The search pack now carries a *front door* for a large building (table `poi_door`:
  a mapped main entrance, else another mapped customer entrance, else for a shop the side its
  customer parking is on). The trip planner looks for the lot around that door, counts a plain
  service road there (the lane along a store's front is often mapped as one), and lets the route
  to it be slower by the walk it saves.
- **Result, Erie County.** All 16 large buildings with a door, from two directions each (32
  plans): the route ends within 30 m of the door in 32 of 32 (8 of 32 before). The six Walmart
  Supercenters: 7 to 15 m from the door (before: 22 to 224 m).
- **Result, Pennsylvania.** 300 buildings x 2 origins. A shop whose front is known from its
  parking: within 30 m of the door in 92 % of the plans (38 % before), median 11 m (39 m). A mapped
  main entrance: 94 % (32 %). Another mapped entrance: 67 % (28 %).
- **Cost.** Median 0 s; one plan in ten is 38 s or more longer, because it now drives through the
  lot to the front instead of stopping at the street.
- **Unchanged.** A small building, a place that is not a building, and a pack built before
  2026-11-21 route exactly as before. Every other table of the search part has the same rows.

## 1. The door (pack side, `packbuilder/search/doors.py`)

Only for a point of interest that is a building whose centroid lies 15 m or more inside its
outline (about 30 m across or more), or outside it. In this order:

1. a node of the outline tagged `entrance=main`; of several, the one nearest to the customer
   parking;
2. else a node tagged `entrance=yes`, `shop`, `entrance` or `secondary`: the only one, or of
   several the one nearest to the customer parking (several and no parking mapped: none);
3. else, for a shop only (`shop=*`, `building=retail` or `supermarket`): the point of the outline
   nearest to the middle of the customer parking within 60 m (parking areas by area, parking
   aisles by length x 16 m; at least 300 m2);
4. else none.

Never a service, emergency, exit, garage, staircase or home entrance; never parking or an entrance
tagged `access=private` or `no`; never underground or multi-storey parking.

### How good is rule 3?

The answer key: the 209 large POI buildings of Pennsylvania with exactly one mapped main
entrance. For each, the point rule 3 alone would give, against the entrance.

| Buildings | Rule | Answered | Median to the entrance | Within 30 m | Opposite side |
|---|---|---|---|---|---|
| Shops (85) | parking areas, 60 m | 62 | 18 m | 71 % | 3 % |
| Shops (85) | parking aisles, 60 m | 67 | 19 m | 70 % | 7 % |
| Shops (85) | **areas and aisles, 60 m (built)** | 74 | 19 m | 72 % | 4 % |
| Shops (85) | areas and aisles, 120 m | 79 | 21 m | 70 % | 5 % |
| Others (124: schools, churches, libraries, offices, ...) | areas and aisles, 60 m | 110 | 33 m | 45 % | 29 % |

"Opposite side": the direction from the centroid differs by 120 degrees or more. So the parking
tells where a shop's front is, and says little about a school or a church, whose parking is as
often behind or beside the building: rule 3 is for shops only. Two of the four Walmart
Supercenters around Erie have no parking area mapped, only aisles; with the aisles all four get a
door.

### What the packs hold

| State | Large POI buildings | Main entrance | Other entrance | Parking | No door | Rows in `poi_door` |
|---|---|---|---|---|---|---|
| Pennsylvania | 5,186 | 262 | 161 | 1,677 | 3,086 | 2,098 |
| Ohio | 5,110 | 327 | 254 | 1,485 | 3,044 | 2,062 |

(The table has a few rows fewer than doors found: the same place mapped twice is one entry.)
Pennsylvania: 2,098 doors among 137,967 POIs; the search part grows by 78 KB (155,357,184 to
155,435,008 bytes), Ohio's by 74 KB. Every other table has the same rows as the packs of 2026-11-20
(compared table by table for both states; only the build time differs). Pennsylvania's extract
step takes 160 s (164 s before), its index step 88 s.

## 2. The route (app side)

- `Stop.door` (route_lib `_door`): the session reads it once per place from the search packs
  (`SearchHub.doorAt`: the POI of the place's name within 100 m, so a recent, a saved place and a
  POI tapped on the map find it too).
- The lot rule (item 18) runs around the door: the small probe and every distance are the door's.
- `lot.door_uses`: around a door a plain `service_road` counts next to `driveway` and
  `parking_aisle`. Without it 42 of 300 buildings had "no lot edge within 80 m of the door" while
  a service road was 3 to 20 m away, and the share within 30 m was 67 %.
- `tie_check.door_walk_m_per_s` (1.4): the lot by the door may be slower than the next end by 90 s
  plus the walk it saves. Without it 16 of 600 plans kept a street end 109 to 258 m from the door
  because the way through a large lot was 92 to 179 s longer.

### Erie: every large building with a door, two origins each

Distance from the route's last point to the door, before and after, and the change in time.

| Place | Door from | From the north-west | From the south-east |
|---|---|---|---|
| Walmart Supercenter, Elm Street, Erie | main entrance | 121 m to 7 m (-15 s) | 121 m to 7 m (-12 s) |
| Walmart Supercenter, West Ridge Road | parking | 22 m to 15 m (+3 s) | 22 m to 15 m (+2 s) |
| Walmart Supercenter, Buffalo Road, Harborcreek | parking | 117 m to 13 m (-11 s) | 224 m to 13 m (+97 s) |
| Walmart Supercenter, Downs Drive | parking | 112 m to 13 m (-4 s) | 112 m to 13 m (-3 s) |
| Walmart Supercenter, Edinboro | parking | 83 m to 11 m (+39 s) | 83 m to 11 m (+40 s) |
| Walmart Supercenter, Corry | parking | 90 m to 7 m (-7 s) | 90 m to 7 m (-6 s) |
| Wegmans, West Ridge Road | parking | 6 m to 6 m (0 s) | 6 m to 6 m (0 s) |
| Wegmans, Peach Street | parking | 30 m to 8 m (-4 s) | 30 m to 8 m (+12 s) |
| Millcreek Mall | parking | 222 m to 24 m (+38 s) | 222 m to 24 m (+38 s) |
| Sportsman's Warehouse | parking | 74 m to 6 m (-22 s) | 74 m to 6 m (-21 s) |
| Tractor Supply Company, Edinboro | parking | 43 m to 14 m (-1 s) | 43 m to 14 m (-1 s) |
| Tractor Supply Company, Buffalo Road | parking | 39 m to 13 m (+2 s) | 39 m to 13 m (+2 s) |
| Lakeside Commons, Edinboro | parking | 26 m to 12 m (-8 s) | 26 m to 12 m (-7 s) |
| Bates Collision | parking | 43 m to 11 m (-24 s) | 43 m to 11 m (-15 s) |
| Gary Miller of Erie | parking | 43 m to 24 m (-1 s) | 43 m to 24 m (-1 s) |
| Eastland Bowl | main entrance | 35 m to 12 m (-3 s) | 35 m to 12 m (-16 s) |

### Pennsylvania: 300 buildings x 2 origins

An even sample of the 2,098 doors; origins about 3 km to the north-west and to the south-east;
Fastest. "Lot": the plan's end is a lot end.

| Door from | Plans | Within 30 m before | after | Median before | after | Lot ends before | after |
|---|---|---|---|---|---|---|---|
| Parking (shops) | 492 | 38 % | 92 % | 39 m | 11 m | 279 | 443 |
| Main entrance | 62 | 32 % | 94 % | 41 m | 12 m | 30 | 48 |
| Other entrance | 46 | 28 % | 67 % | 49 m | 18 m | 12 | 22 |

Time after minus before, parking: median 0 s, 90th percentile +38 s. The plans that still end
more than 60 m from the door (4 % of the shops) have no driveway, aisle or service road within
80 m of the door, or a street as near as the lot edge (a door on the street side: the street end
is then right).

## 3. Tests

- `packbuilder/search/tests/test_doors.py`: 9 tests of the door rules.
- `packbuilder/routing/test_presets.py`: 36 tests (4 new: the service road by a door, the probe
  around the door, the walk saved, the validation).
- Reference plans (`android/routing/scripts/reference_routes.py --only plans`): four new cases,
  `door_parking`, `door_entrance`, `door_walk`, `door_none`; the 252 plans and the older cases
  are unchanged. The Kotlin planner gives the same routes (`PlansReferenceTest`).
- `StopTiesTest` (the session reads the door once and plans to the lane by it),
  `SearchServiceTest.frontDoors` (the lookup, and a pack without the table).

## 4. Not covered

- A business mapped as a point inside a large building (a pharmacy inside a supermarket, a shop
  in a mall) keeps its own point.
- A campus (a school, a hospital, a park, an airport) is an area that is not a building; it keeps
  its centroid. Its main parking would be the better end; that is a different rule.
- A door mapped on a side no car can reach falls back to the street end, as before.
- Rule 3 puts the point about the middle of the side the parking is on, not at the real doorway:
  median 19 m off, which is the same lane.

## 5. How to run it again

Scratch scripts of the measurement are in `data/r3-run2/b1/` (not in git): `door_variants.py`
(the answer key), `eval_doors.py --region erie|pa` (routes before and after), `diag_doors.py` and
`tie_misses.py` (why a plan does not end by the door). The pack side:

    .venv/search/Scripts/python -I packbuilder/search/build.py --state pa --force extract index --out-dir data/r3-packs --work-dir data/r3-p/search-work
    .venv/search/Scripts/python packbuilder/search/tests/test_doors.py
    .venv/routing/Scripts/python packbuilder/routing/test_presets.py
