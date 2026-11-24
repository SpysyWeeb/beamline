# Experiment 07: map data (lane tags, US overview, state-line clipping, pack size)

Date: 2026-11-15 (first build), revised the same evening after two
independent reviews: the profile patch is now version 2 and the PA and OH
packs were rebuilt with it; section 10 lists every review finding and what
happened to it. Machine: Windows 11, 12 logical CPUs, 32 GB RAM. **Other
engineers' jobs ran during every measurement below** (an Android emulator,
Gradle builds, other pack builders), and some of my own measurement scripts
(2 processes) ran next to my builds, so wall times are noisy and pessimistic.
Every Planetiler run used 4 threads and a 4 GB heap; only one ran at a time
(`run_queue.py`). Units: file sizes in decimal MB (10^6 bytes); RAM in GiB
(2^30 bytes). Peak RAM of Planetiler is the summed working set of the Java
process tree, polled every 0.25 s with psutil (`measure.py`), so it is a lower
bound. The OSM input is the first round's Geofabrik snapshot
(`data/osm/pennsylvania-261114.osm.pbf`, `ohio-261114.osm.pbf`).

## Result in short

- **Lane data is in the tiles** (z14 and up, so also in the recommended z14
  packs): roads with highway=motorway, trunk, primary and their links carry
  `lanes`, `lanes:forward`, `lanes:backward`, `lanes:both_ways` as integers
  and `turn:lanes`, `turn:lanes:forward`, `turn:lanes:backward`,
  `turn:lanes:both_ways` as the OSM strings. It is a small extension of the
  Protomaps profile (`profile/beamline-profile.patch`, version 2: 63 added and
  8 removed lines in 4 files plus a 106-line options class), not a second
  layer. Lines whose lane values depend on direction are kept out of the
  profile's line merge, which had reversed 5 two-way lines in PA and 4 lines in
  OH with patch 1 (review finding). All 17 checked ways (I-90 exit 24, its
  eastbound ramp end at Peach St, Peach St at Downs Dr, Peach St with a centre
  turn lane, South Braddock Ave) match OSM in value, type and direction, every
  one of the 18,990 (PA) and 16,873 (OH) direction-dependent z14 lines runs in
  its OSM way's direction, and the share of road length with lane tags in the
  tiles equals OSM's for every class in PA and OH. A MapLibre style can use the
  numbers directly (`line-width: ["*", 2.5, ["get", "lanes"]]`, screenshots
  L1-L4, R1-R3). Cost: +0.78 MB (+0.32 %) on the PA z14 pack; build time below
  what this shared machine can resolve (two back-to-back pairs, section 1).
- **Our own US overview pack: 3.66 MB**, zoom 0-7, from public-domain data
  only (Natural Earth 5.1.2, Census TIGER/Line 2026 primary roads, Census
  Gazetteer places with 2025 population estimates). No OSM, no CC-BY layer.
  Built in about 35 s with Planetiler's YAML schema mode. Day and night look right at
  the whole-US view and up to the z8 takeover (O1-O9). Option (b), a build from
  the 11.4 GB US extract, was **not run**: (a) is good enough, and the hosted
  Protomaps cut (same schema, built from OSM) is a measured stand-in for what
  (b) would give: 17.8 MB, more forest and small lakes at z6-7.
- **State packs are clipped at the TIGER/Line 2026 state outline** (public
  domain, one pinned 10 MB file for all states, neighbouring states share
  edges, includes the Great Lakes water). The Census cartographic boundary
  files were rejected because they stop at the shoreline (PA's would lose Lake
  Erie). Along the PA/OH line (z0-14), tiles shared by both packs fell from
  655 to 236, byte-identical duplicate features from 1,703 to 0, POIs and
  places lying in the other state from 35 to 0, and same-name features in
  shared z14 tiles from 393 to 95 (roads that cross or follow the line). "Ohio Street" is
  labelled once instead of twice (B3 vs A3).
- **Pack size.** In the z15 pack, z15 holds 53 % of the bytes; minor roads
  (111 MB over all zooms), building polygons (74 MB), forest polygons (70 MB)
  and paths (28 MB) dominate. **Recommended and built: z14 maximum with the
  z15-only POIs, house numbers and private roads moved into z14 and 30 kinds
  of street furniture left out: PA 244.7 MB (-47 % against 463.9 MB), OH
  252.3 MB** (rebuilt with profile patch 2 in the review round). It keeps every street name, every house number and the driving
  POIs (screenshots S1-S6). MapLibre Tiles (MLT) from the same build (patch 1)
  are 221.1 MB (-9.6 %), render pixel-identical in the viewer's MapLibre GL JS 6.16.0 at
  z6.6, z12 and z17 (a few hundred anti-aliasing pixels differ at z16.5/z18),
  and MapLibre Native Android has decoded MLT since 12.1.0, but **MLT is not
  recommended yet**: nobody has run an MLT pack on Android. More options are
  measured in section 4; together the ones that only drop things the style
  does not draw or a car does not need reach 208.3 MB as MVT (-55 % against z15), measured; about 188 MB as MLT (estimate: -9.6 % applied).
- **Packs:** `data/map/packs-v2/pa-261114-z14.pmtiles`,
  `oh-261114-z14.pmtiles`, `us-overview-ne512-tiger2026-z0-7.pmtiles`, each with
  `.build.json` and (state packs) `.map.json`, the map part of the app's pack
  manifest. Proposed manifest changes are in section 5.

## 1. Lane data in the tiles

### How: a small profile extension, not a second layer

| Way | For | Against | Verdict |
|---|---|---|---|
| **Extend the profile** (chosen): set the lane attributes on the road features the profile already writes | One Planetiler pass, no extra build time. The lanes sit on the same line the style draws, with its `kind`, `oneway`, `name`, `ref`, bridges and links, so a lane-level style needs no join. Integers stay integers (`setAttr` with Integer). | A patch on a pinned upstream commit to maintain. Lines with different lane values no longer merge, and lines with direction-dependent lane values are kept out of the merge (patch 2), so a few more features. | Patch 2: 63 added and 8 removed lines in 4 files plus a 106-line options class, applied by `build_profile.py` with `git apply` to a fresh clone of the pinned commit. Every option is off unless passed, so the patched jar can still write the stock tiles. |
| Second thin layer (`lanes`) in a second Planetiler pass, merged in the finish step | Leaves the profile untouched. | A second full read of the OSM file (about 4 min for PA) on every build; the same road geometry twice in z14 tiles (bytes); the style must line up two layers; the merge step must re-encode most z14 tiles. | Rejected. |
| Keep only the OSM way id in the tiles, look lanes up on the device | Smallest tiles. | Needs a lane database on the device and a lookup per feature; MapLibre cannot style from it. | Rejected for drawing; the routing data (Valhalla) carries lanes for guidance anyway. |

What the patch writes (`--beamline_lanes_minzoom=14`, from
`build_config.json` "lanes.minzoom"): on features of the `roads` layer whose
OSM way has highway=motorway, motorway_link, trunk, trunk_link, primary or
primary_link, from zoom 14:

| Attribute | Type | From the OSM tag |
|---|---|---|
| `lanes`, `lanes:forward`, `lanes:backward`, `lanes:both_ways` | integer (MVT int value, `Number` in the TileJSON fields) | the tag if it is a whole number 1-30, otherwise left out (e.g. `2;3`) |
| `turn:lanes`, `turn:lanes:forward`, `turn:lanes:backward`, `turn:lanes:both_ways` | string, as in OSM (lanes left to right in the way's direction, `\|` between lanes, `;` between turns) | trimmed; empty left out |

The names are the OSM keys, so the OSM wiki documents them. Stretches with no
lane tag carry nothing (no guessing). The `:both_ways` forms (added in patch
version 2, review finding) describe a centre two-way turn lane, common on US
commercial strips: there `lanes` = forward + backward + both_ways, so without
them a lane-level style could not account for the middle lane. In PA 4.1 % of
trunk and 3.4 % of primary ways carry one of them.

**Line merging and direction (patch version 2).** The Protomaps profile merges
road lines with identical attributes in every tile
(`FeatureMerge.mergeLineStrings`, Planetiler's `LoopLineMerger`). Planetiler
0.10.2's source says the merger only "attempt[s] to preserve segment
directions": where two ways with the same attributes meet head to head or tail
to tail, or where it joins lines at a junction by angle, one of them comes out
reversed. For `lanes:forward`/`:backward` and `turn:lanes*` the direction is
part of the meaning (a reversed line swaps forward and backward and the
left-to-right order of the turn lanes). Patch version 1 let these lines merge,
and 5 two-way lines in the PA z14 pack came out reversed (reviewer's finding,
reproduced in section 10). Version 2 keeps every road feature that carries
`lanes:forward`, `lanes:backward`, `turn:lanes`, `turn:lanes:forward` or
`turn:lanes:backward` out of the merge: it stays one feature per OSM way and
tile, in the way's direction. Lines with only `lanes` and the `:both_ways`
values, which read the same in both directions, still merge.

z14 was chosen so the lanes are in the recommended z14 packs; the later lane-level map draws from z15/z16, which
MapLibre overzooms from z14. A style can read the turns per lane with
`["split", ["get", "turn:lanes"], "|"]`: `split` exists in the viewer's
MapLibre GL JS 6.16.0 (checked in the vendored code) and in MapLibre Native
Android since 13.5.0 (changelog: "implement split and join expressions").

### Proof at the requested places (`lanes_check.py`, `lanes_checkpoints.json`)

The tool reads the listed ways from the same `.osm.pbf` with pyosmium, finds
the road feature nearest to each way's midpoint in the z14 (and z15) tile, and
checks value, type (integer) and direction: the way's first and last node are
projected onto the tile line, because `turn:lanes` is ordered in the way's
direction and a reversed line would swap the lanes. Result for the patch-2
`pa-261114-z14.pmtiles` at z14: **0 mismatches at all 17 ways**, every feature
in the way's direction, nearest feature 0.04-0.54 m from the way. The packs
built with patch 1 (the first z14 pack, kept as
`data/map/work/patch-v1-packs/pa-261114-z14.pmtiles`, and the z15 comparison
pack at z15) pass the first 14 ways and fail the 3 review-round ways: they have
no `:both_ways` values, and South Braddock Ave runs reversed.

### Direction of every lane line (`lanes_check.py --direction`, review round)

Every z14 line (feature part) that carries a direction-dependent lane value
is matched, segment by segment, to the in-scope OSM ways with exactly the same
lane values within 3 m, and each segment votes "same" or "reversed" by
projecting its ends onto the way. Results:

| Pack | Lines checked | In the way's direction | Reversed, meaning changed | Reversed, symmetric (harmless) | Not matched |
|---|---|---|---|---|---|
| PA z14, patch 1 (first round) | 16,373 | 16,354 | **5** | 1 | 13 |
| PA z14, patch 2 (delivered) | 18,990 | 18,985 | **0** | 0 | 5 |
| OH z14, patch 1 (first round) | 14,939 | 14,930 | **4** (3 of them one-way turn lanes) | 2 | 3 |
| OH z14, patch 2 (delivered) | 16,873 | 16,873 | **0** | 0 | 0 |

"Not matched" lines are those whose every segment was ambiguous (two OSM ways
with the same values within 3 m running opposite ways). Patch 2 has more lines
because they are no longer merged. Screenshots `v1patch-R1` and `R1` (z18,
South Braddock Ave, red marks drawn in each line's own direction) show the fix:
with patch 1 the marks on the "fwd 2 left|through;right" stretch all point
south-east; with patch 2 the part that is OSM way 504762367 points north-west,
the way's direction. `R2` (Peach St at Downs Dr) shows the northbound and
southbound carriageways marked in their driving directions, `R3` (Peach St
south of Erie) the new "both 1 left" centre-lane values. All looked at.

| Place | OSM way | OSM tags | In the tile |
|---|---|---|---|
| I-90 near exit 24, both carriageways, 11 ways from -80.135 to -80.049 | 58662962, 58662963, 58662966, 145551802, 145551804, 1182123155, 145551800 | lanes=2 | `lanes` 2 |
| I-90 between the exit 24 ramps | 145551787, 145551791 | lanes=4 | 4 |
| I-90 exit 22-24 and 24-27 lane drops | 145551773, 145551803 | lanes=3 | 3 |
| Exit 24 eastbound off-ramp, end at Peach St / US 19 | 530545210 (motorway_link) | lanes=3, turn:lanes=left\|left;through\|right | 3, `left\|left;through\|right`, same direction |
| Peach St / US 19 northbound at Downs Dr | 530545218 (primary) | lanes=4, turn:lanes=left\|through\|through\|through;right | 4, `left\|through\|through\|through;right`, same direction |
| Peach St / US 19, two-way part north of I-90 | 530545035 (primary) | lanes=6, lanes:forward=2, lanes:backward=4, turn:lanes:backward=left\|through\|through\|through;right | all four, same direction |
| Peach St / US 19 south of Erie, centre turn lane (review round) | 121593871 (primary) | lanes=5, lanes:forward=2, lanes:backward=2, lanes:both_ways=1, turn:lanes:both_ways=left | all five, same direction |
| the same, with a turn-lane approach (review round) | 530832900 (primary) | lanes=6, lanes:forward=2, lanes:backward=3, lanes:both_ways=1, turn:lanes:both_ways=left, turn:lanes:backward=left;through\|through\|right | all six, same direction |
| South Braddock Ave near Pittsburgh (review round: reversed by patch 1) | 504762367 (primary) | lanes=3, lanes:forward=2, lanes:backward=1, turn:lanes:forward=left\|through;right | all four, same direction (patch 1: reversed) |

Screenshots (debug overlay added by `screenshots.py`: line width = 2.5 px x
`lanes`, label = `lanes: turn:lanes`), all looked at:
`L1` (z15 pack) and `L2` (z14 pack) at the exit 24 interchange show I-90 with
2 lanes each way, the eastbound off-ramp "3: left|left;through|right" ending at
Peach St, the westbound one "3: left|through;right|right" and Peach St's
4-lane approaches; `L3` shows "4: left|through|through|through;right" on Peach
St northbound ending at Downs Dr; `L4` is I-90 at z14; `L5` at z12 has no lane
attributes, as intended.

### Coverage against OSM (`lanes_check.py --coverage`)

OSM side: all ways of each class in the extract (which reaches 0.4-1 km past
the state line). Tile side: road length in the z14 tiles inside each tile
(buffers not counted). Lane share = share of length (and, for OSM, of ways)
with the tag. "Turn" = any `turn:lanes*` key; "both" = `lanes:both_ways` or
`turn:lanes:both_ways`. Patch-2 packs, review round:

| PA | OSM ways | with lanes | with turn lanes | with both_ways | OSM km | lanes, % of km | turn, % of km | both, % of km | tile km | lanes, % of km | turn, % of km | both, % of km |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| motorway | 13,419 | 99.9 % | 17.0 % | 0.0 % | 9,073 | 100.0 | 5.5 | 0.0 | 9,249 | 100.0 | 5.5 | 0.0 |
| motorway_link | 10,087 | 83.7 % | 9.8 % | 0.0 % | 2,320 | 84.4 | 5.5 | 0.0 | 2,341 | 84.5 | 5.5 | 0.0 |
| trunk | 14,502 | 96.9 % | 26.0 % | 4.1 % | 4,685 | 95.1 | 9.2 | 4.3 | 4,784 | 95.1 | 9.2 | 4.4 |
| trunk_link | 2,295 | 68.2 % | 4.8 % | 0.0 % | 238 | 70.9 | 2.8 | 0.0 | 239 | 71.2 | 2.8 | 0.0 |
| primary | 35,658 | 83.0 % | 23.0 % | 3.4 % | 10,522 | 77.7 | 6.1 | 2.7 | 10,602 | 78.2 | 6.2 | 2.8 |
| primary_link | 2,306 | 64.3 % | 5.0 % | 0.0 % | 186 | 67.9 | 4.0 | 0.0 | 187 | 67.9 | 4.1 | 0.0 |

| OH | OSM ways | with lanes | with turn lanes | with both_ways | OSM km | lanes, % of km | turn, % of km | both, % of km | tile km | lanes, % of km | turn, % of km | both, % of km |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| motorway | 13,746 | 99.5 % | 15.9 % | 0.0 % | 8,414 | 99.8 | 6.7 | 0.0 | 8,626 | 99.8 | 6.7 | 0.0 |
| motorway_link | 9,664 | 70.6 % | 11.9 % | 0.0 % | 2,556 | 69.3 | 5.8 | 0.0 | 2,585 | 69.3 | 5.8 | 0.0 |
| trunk | 7,024 | 94.4 % | 22.4 % | 1.9 % | 4,820 | 94.0 | 4.2 | 0.7 | 4,722 | 93.7 | 4.4 | 0.7 |
| trunk_link | 1,176 | 48.3 % | 9.3 % | 0.0 % | 222 | 47.7 | 3.6 | 0.0 | 222 | 47.8 | 3.6 | 0.0 |
| primary | 27,976 | 81.0 % | 33.4 % | 5.6 % | 10,217 | 68.5 | 6.8 | 3.0 | 10,356 | 68.1 | 6.8 | 3.0 |
| primary_link | 1,105 | 47.1 % | 11.9 % | 0.0 % | 96 | 49.7 | 10.7 | 0.0 | 96 | 49.7 | 10.7 | 0.0 |

My PA way counts give lanes on 99.9 / 96.9 / 83.0 % of motorway / trunk /
primary ways and turn lanes on 17.0 / 26.0 / 23.0 %, matching the brief's
100 / 97 / 83 % and 17 / 26 / 23 %. The brief's turn-lane shares count any
`turn:lanes*` key, including `turn:lanes:both_ways`; the first version of this
report counted only `turn:lanes` and its `:forward`/`:backward` forms (17.0 /
22.5 / 20.3 %) and did not know why the brief's numbers were higher (both
reviewers found the reason). By length, turn lanes cover only 4-10 %: they
are tagged on short approach segments. The tile-side km differ from the first
round's by up to 4 km per class (0.1 %). That is the length count, not lost
road: it counts whole segments whose midpoint lies in the tile, the merger
re-simplified merged lines into long segments reaching into the tile buffer
(counted whole), and unmerged lines keep a vertex near the tile edge (their
buffer part is not counted). The end points of the lines are the same
(checked on the largest differences, e.g. PA 350 in tile 14/4632/6152: 2
points with patch 1, 4 with patch 2, same ends).

### Cost

Size: two builds of the recommended z14 variant that differ only in
`--beamline_lanes_minzoom` (14 or -1):

| PA z0-14 | Pack |
|---|---|
| with lane tags, patch 2 (delivered) | 244,703,510 B |
| without (`z14-nolanes`; patch 1 and patch 2 write identical tiles when lanes are off) | 243,921,657 B |
| difference | **+781,853 B (+0.32 %)**; patch 1 (six keys, merged lines) was +663,268 B (+0.27 %) |

Build time (review round): the same two builds, run back to back twice
(with, without, OH, with, without) through `run_queue.py`, read with
`build_times.py` from Planetiler's own stage summary. Other engineers' jobs
(two Android emulators, Gradle, other pack builders) ran throughout; nothing
else of mine ran during these four builds.

| PA z0-14 | Planetiler wall (its log) | CPU, whole run | CPU, OSM pass 2 | CPU, tile encoding (archive) | Peak RAM (measure.py) |
|---|---|---|---|---|---|
| with lanes, run 1 | 541 s | 1,709 s | 969 s | 488 s | 5.26 GiB |
| without, run 1 | 649 s | 1,892 s | 1,150 s | 490 s | 5.25 GiB |
| with lanes, run 2 | 479 s | 1,651 s | 1,004 s | 433 s | 5.17 GiB |
| without, run 2 | 501 s | 1,648 s | 1,048 s | 398 s | 5.17 GiB |

Both repeats of each build wrote byte-identical packs (the build is
deterministic). The build with lane tags was faster in both pairs by wall time
and within 0.2 % (run 2) or 10 % faster (run 1) by CPU time; repeats of the
*same* build differ by 62 s and 148 s in wall time (11-23 % of the slower run)
and by 58 s and 244 s in CPU time (3-13 %) on this shared machine. **So the build-time cost of the lane tags is below what this
machine can resolve: measured, but not distinguishable from zero at a noise of
several percent.** By construction the patch adds eight tag reads per
motorway/trunk/primary way (about 78,000 of PA's ways) in OSM pass 2, and
keeps about 19,000 lines out of the line merge at z14. Not measured for a z15
pack (lanes are in its z14 and z15 tiles, so roughly twice the bytes).

## 2. Our own US overview pack

### (a) Natural Earth and Census data only (built)

`packbuilder/map/overview/`: `overview.yml` is a Planetiler 0.10.2
`generate-custom` schema (YAML rules, in the spirit of "rules in data files");
`build_overview.py` copies the 13 Natural Earth tables it needs into a small
GeoPackage, writes the Census towns (`census_places.py`), runs Planetiler and
then our finish step (header centre on the lower 48). Layers and attributes
follow the Protomaps 4.x schema, so the same day and night styles draw it.

| Layer | Content | Source (all public domain) | Zooms |
|---|---|---|---|
| earth | land | Natural Earth 1:50M (z0-3), 1:10M (z4-7) | 0-7 |
| water | ocean; lakes (with a label point per lake at Natural Earth's label zoom) | Natural Earth 1:50M/1:10M ocean, 1:10M lakes + North America lakes | 0-7 |
| boundaries | US borders (kind_detail 2), state lines (4) | Natural Earth 1:10M | 0-7, 2-7 |
| places | state names (with the USPS abbreviation the style shows below z6); 769 US cities with Natural Earth's zoom levels and capitals, plus the largest Canadian and Mexican ones; 4,269 more US towns of 5,000+ people; country names | Natural Earth; Census 2026 Gazetteer place points joined to Vintage 2025 population estimates | 2-7 |
| roads | interstates (US:I shields), US-route and other freeways (US:US shields) | Census TIGER/Line 2026 primary roads (all limited-access) | 3-7 |
| roads | other US and state highways | Natural Earth 1:10M roads (`expressway = 0`) | 6-7 |
| landuse | national parks | Natural Earth parks | 5-7 |

**Measured:** 3,661,759 bytes (3.66 MB), 2,112 tiles (bounds -180,15,-50,72:
all 50 states except the Aleutian islands west of 180 degrees); Planetiler
33.6 s and 0.51 GiB peak in the final run (28-41 s and 0.49-0.56 GiB over the
last three runs; 2 threads, other builds running); finish 0.9 s; `pmtiles verify`
passes; every label character has a glyph file. Per zoom: z0-3 0.15 MB, z4
0.30, z5 0.51, z6 1.00, z7 1.69 MB.

Screenshots (all looked at): `O1`/`O2` whole US at z3.6, day and night: state
lines and abbreviations, cities from Seattle to Miami, "UNITED STATES OF
AMERICA", the Great Lakes, Canada and Mexico as plain land; compare `B6`, the
first round's stand-in cut from Protomaps' hosted build, which looks almost
the same (it adds Gulf of Mexico and province labels). `O3` (z5.5, east),
`O8` (z4.5, west), `O9` (z3, Alaska and Hawaii; the grey strip west of 180
degrees is outside the bounds). `O4` (z7.5, western PA): towns down to
Conneaut, Corry, Titusville; interstates with shields, US routes; compare
`B8` (hosted cut): it also shows forest, small lakes and rivers. `O5` (z7.9,
overview) and `O6`/`O7` (z8.1, PA and OH packs) show the takeover: towns,
roads and shields continue; forest and small water appear at z8.

The first build (only Natural Earth cities, lake names on polygons, no US
routes, 3.19 MB) looked too empty at z7 and repeated lake names at tile
edges; the Census towns, the Natural Earth US routes and lake label points
fixed that (+0.47 MB).

Credit: Natural Earth asks for none ("Crediting the authors is unnecessary",
naturalearthdata.com terms of use); the Census Bureau's TIGER/Line technical
documentation (2026, section 1.2) says copyright "is not available for any work
of the United States Government" and only asks that the Census Bureau be cited.
The pack's metadata says "Natural Earth; US Census Bureau". No layer needs a
credit line on screen.

### (b) A low-zoom build from the Geofabrik US extract (not run)

Not downloaded or built: (a) shows everything the brief asks for at 3.66 MB.
What (b) would give is known without the download, because Protomaps' hosted
build is the same profile on the same OSM data: the first round's landcover-free
z0-7 cut is 17.8 MB (just under the 20 MB limit) and looks like `B6`/`B8`. Its
advantages are forest, small lakes and rivers at z6-7 (no public-domain forest
vector is at hand; the national land-cover rasters would need vectorising). Its
costs: an 11.4 GB download (about 50 min at the 4 MB/s link), a Planetiler run
over the whole US (estimated over an hour at 4 threads, RAM not measured for
that size), ODbL (an OSM credit, which the app shows anyway) and a rebuild for
each snapshot. If the owner wants the forest at z6-7, (b) is the way;
otherwise (a).

## 3. Clipping state packs at the true state line

### Boundary source

| Source | Licence | Water (Lake Erie) | Neighbours share edges | Pinnable | Verdict |
|---|---|---|---|---|---|
| **Census TIGER/Line 2026 state polygons** (`tl_2026_us_state.zip`, 9.96 MB, all states) | public domain | **included** (PA reaches 42.516 N, the Canadian line) | yes (one national file, one topology) | yes, SHA-256 in `fetch_sources.py`; yearly versions | **chosen** |
| Census cartographic boundary file `cb_2025_us_state_500k` | public domain | **cut at the shoreline** (PA stops at 42.270 N, OH at 41.977 N) | yes | yes | rejected: a PA pack would lose Lake Erie |
| OSM admin relation (boundary=administrative, admin_level=4) | ODbL (no new credit: the packs are ODbL anyway) | included | yes within one snapshot | no: changes with every snapshot; the relation must be assembled from each state's extract (Geofabrik extracts do not promise complete boundary relations); two packs from different snapshots can leave a gap or an overlap | rejected |
| Geofabrik `.poly` (first round) | ODbL-derived | included | no: buffered 0.4-1 km | no | replaced |

The OSM state line drawn in the packs and the TIGER line agree closely
(`boundary_compare.py`, OSM line in the first round's PA z14 tiles): PA/OH
line median 2.1 m, 95th percentile 90 m, maximum 157 m (236 samples); PA's
southern line (Mason-Dixon) median 10.0 m, 95th percentile 33 m, maximum 135
m (349 samples). So the clip and the drawn line coincide to well under a pixel
at z12 nearly everywhere. The Census Bureau notes that its boundaries are for
statistics, not legal land descriptions; for cutting map packs that does not
matter. TIGER coordinates are NAD83, used as WGS84 (about 1 m apart here).

`census_clip.py` writes the outline to `data/map/work/clip-v2/<state>.geojson`
and `build_pack.py` passes it to the profile's `--clip` with buffer 0, so every
feature is cut at the line and points outside it are dropped. The Geofabrik
extracts cover the TIGER outlines (area outside the `.poly`: 0.09 km2 for PA,
0.003 km2 for OH, slivers along the Delaware River); the `.poly` outlines
reached 685 km2 (PA) and 1,271 km2 (OH) beyond the state.

### Seam before and after (`seam_report.py`, PA + OH)

Before: first-round packs `pa/oh-261114-z0-15`; after: `pa/oh-261114-z14`.
Shared tiles are tiles both packs contain; "same name" counts (kind, name)
pairs present in both packs' copy of a shared tile; "outside" counts POIs and
places of a pack lying more than 50 m inside the other state.

| zoom | shared tiles before / after | identical features | same-name roads | same-name POIs | same-name places | points in the other state |
|---|---|---|---|---|---|---|
| 10 | 13 / 11 | 36 / 0 | 0 / 0 | 0 / 0 | 1 / 0 | 2 / 0 |
| 11 | 22 / 17 | 167 / 0 | 4 / 1 | 1 / 0 | 1 / 0 | 5 / 0 |
| 12 | 41 / 28 | 328 / 0 | 60 / 18 | 3 / 1 | 2 / 0 | 3 / 0 |
| 13 | 110 / 53 | 454 / 0 | 103 / 29 | 9 / 0 | 2 / 0 | 7 / 0 |
| 14 | 446 / 104 | 711 / 0 | 371 / 95 | 20 / 0 | 2 / 0 | 18 / 0 |
| z0-14 | 655 / 236 | 1,703 / 0 | | | | 35 / 0 |
| z15 (before only) | 1,360 / - | 366 / - | 565 / - | 52 / - | 2 / - | 81 / - |

Before, POIs such as "Morgan Oil", "Tic Toc Food Mart" and "Family Dollar"
were in both packs. After, what remains are roads that cross or follow the
line ("North State Line Road", "Kinsman Road"): each pack has its half, and
MapLibre may label both halves.

Screenshots with both packs loaded, interleaved (before `B1`-`B5`, `B7` from
`data/map/packs`; after `A1`-`A5`, `A7`), looked at: `B3`/`A3` (z15.5,
Sharpsville): "Ohio Street" twice before, once after. `B2`/`A2` (z12, Sharon /
Masury): before, Ohio's forest polygons reach a few hundred metres into PA;
after they stop at the line; roads continue across it. `A4` (z10, Conneaut /
Lake Erie): water and roads continue across the line, the state line is drawn
on into the lake, and the density step (Ohio maps far more landuse) is data,
not seam. `A6` (z5): the overview pack under PA and OH, whose own layers start
at z8. `A7` (z17, South State Line Road, which runs along the line) and `A5`
(z12, night) look normal: one road, labels repeated only at MapLibre's usual
spacing.

## 4. Pack size

### What makes the z15 pack big

`size_report.py breakdown`: each tile's stored (gzipped) bytes shared out over
its layers in proportion to each layer gzipped alone, and within a layer over
sub-groups in proportion to their feature bytes. PA z0-15 (v2: lanes, TIGER
clip), 463.9 MB, stored MB:

| layer | z0-10 | z11 | z12 | z13 | z14 | z15 | all |
|---|---|---|---|---|---|---|---|
| roads | 1.4 | 1.4 | 9.2 | 25.0 | 48.5 | 86.2 | 171.6 |
| landuse | 7.1 | 7.5 | 12.4 | 18.2 | 26.4 | 40.6 | 112.2 |
| buildings | 0.0 | 0.2 | 0.7 | 2.7 | 16.3 | 68.8 | 88.8 |
| water | 0.6 | 0.8 | 1.8 | 6.1 | 11.0 | 20.1 | 40.5 |
| pois | 0.0 | 0.3 | 0.6 | 1.5 | 4.1 | 15.2 | 21.7 |
| earth | 0.1 | 0.1 | 0.2 | 0.6 | 2.3 | 8.8 | 12.1 |
| boundaries | 0.2 | 0.3 | 0.6 | 1.3 | 2.6 | 5.0 | 9.9 |
| places | 0.2 | 0.2 | 1.2 | 1.5 | 2.1 | 2.4 | 7.5 |
| **all** | 9.6 | 10.8 | 26.7 | 56.8 | 113.2 | **247.1** | 463.9 |

Largest sub-groups over all zooms: minor roads 110.8 MB (1.17 M features),
building polygons 74.2 MB (2.60 M features at z15, 2.63 M over all zooms), landuse "wood" 70.1 MB, paths
28.0 MB, major roads 24.9 MB, water lines 22.9 MB, water polygons 16.9 MB, POIs
21.7 MB (of which the 30 street-furniture kinds 6.2 MB), house numbers 14.6 MB
(1.14 M points), earth 12.1 MB. Landuse kinds the current style does not draw
at all (farmland, residential, pitch, wetland, meadow, commercial ...) are
17.9 MB of the z15 pack and 11.8 MB of the z14 pack (by the breakdown; the
rewrite measured 10.9 MB).

### Options, measured on PA

Rows marked "build" are Planetiler builds; rows marked "rewrite" apply a rule
of `size_options.json` to a finished pack with `size_report.py option`, which
rewrites every tile with the same gzip level; the identity rewrite of the z14
pack is 244.6 MB like the original, so the savings are comparable. The rewrite
rows were measured on the patch-1 z14 pack (244.6 MB); the patch-2 pack is
0.12 MB larger, which does not change them.

| Option | PA size | Change | What is lost | How measured |
|---|---|---|---|---|
| z0-15 (v2: lanes, TIGER clip) | 463.9 MB | - | - | build |
| z0-15, street furniture left out | 460.4 MB | -0.8 % | trees, benches, crossings, street lamps, turning circles ... | rewrite |
| z0-14 stock profile (first round) | 220.3 MB | -52 % | 88 % of POIs, every house number | build (first round, Geofabrik clip) |
| **z0-14, z15-only POIs/house numbers/private roads moved into z14, street furniture left out (recommended)** | **244.7 MB** (patch 2; patch 1: 244.6) | **-47 %** | z14 buildings are merged blocks (row houses merged, houses under about 160 m2 dropped; D1-D6); ditches and drains; coordinates at z14 precision (about 0.5 m at 41 N) when overzoomed | build |
| same, buildings not merged at z14 | 274.5 MB | -41 % | (individual houses, like z15) | build |
| same, MapLibre Tiles (MLT), Planetiler defaults | 221.1 MB | -52 % (-9.6 % vs MVT) | nothing visible (pixel comparison below) | build |
| same, MLT with FSST + FastPFOR (`--mlt_fsst --mlt_fastpfor`) | not measured | | | build stopped: tile encoding ran at about 3 tiles/s at z14; after 25 min only 42 % of the features were written (default MLT: 2 min 57 s for the whole encoding stage, MVT: 1 min 25 s) |
| z14 + no building polygons at z14 | 227.3 MB | -51 % | every building footprint from z14 up (z11-13 keep theirs; house numbers stay) | rewrite |
| z14 + no building polygons at z11-13 | 241.2 MB | -48 % | buildings below z14 | rewrite |
| z14 + landuse kinds the style does not draw left out | 233.7 MB | -50 % | farmland, residential, commercial, pitch, wetland ... (invisible now; a later style could want them) | rewrite |
| z14 + footways left out | 236.4 MB | -49 % | footways, sidewalks, crossings, steps, cycleways, bridleways, paths (tracks stay) | rewrite |
| z14 + all paths left out | 231.6 MB | -50 % | the above and tracks | rewrite |
| z14 + landuse simplified at 0.5 px at z14 | 238.0 MB | -49 % | forest and park edges up to 3.6 m off at z14 | rewrite |
| z14 + landuse simplified at 0.5 px at z11-14 | 225.4 MB | -51 % | the same at every zoom | rewrite |
| z14 + small landuse (< 4 px2) or small forest (< 16 px2) left out | 243.7 / 244.4 MB | -0.4 / -0.1 % | almost nothing: the bytes are in large detailed polygons | rewrite |
| z14 + stream detail simplified (0.5 px) | 243.7 MB | -0.4 % | | rewrite |
| z14 + small buildings (< 1 px2) left out | 244.6 MB | 0 | none: the profile's z14 merge already drops them | rewrite |
| **z14 "lean": undrawn landuse + footways + landuse simplified at z11-14** | **208.3 MB** | **-55 %** | all three rows above | rewrite |

Ohio, recommended configuration: 252.3 MB (patch 2).

**MLT.** Planetiler 0.10.2 writes MLT (`--tile_format=mlt`; it bundles
`org.maplibre:mlt` 0.0.10) and PMTiles tile type 6. Readers:

- MapLibre GL JS 6.16.0 (viewer): the vendored code has the `encoding: "mlt"`
  source option (supported since 5.12.0 per the MLT implementation-status
  page). pmtiles 4.5.0's TileJSON does not pass the tile type on, so the style
  source needs `"encoding": "mlt"`; `screenshots.py` adds it (`"encoding"` view
  option). Result: `M1`/`M4`/`M5` are pixel-identical to the MVT views at z12,
  z6.6 and z17 (lanes overlay), `M2` differs in 258 of 960,000 pixels and `M3`
  in 86 (anti-aliasing, at most 36 of 255 per channel). 0 errors, 0 console
  messages.
- MapLibre Native Android 13.7.1 (app): its changelog adds MLT parsing in
  12.1.0 and FastPFOR in 13.4.0; its PMTiles source (`pmtiles_file_source.cpp`
  at tag `android-v13.7.1`) turns tile type 6 into `"encoding": "mlt"` itself,
  and the vendored MLT decoder (maplibre-tile-spec 751ebb1, July 2026) has
  FSST and FastPFOR code. **Not run on Android by me.**
- Build time: Planetiler's tile-encoding stage took 2 min 57 s for MLT
  against 1 min 25 s for MVT (whole build 681 s against 459 s, with more load
  on the machine). With FSST and FastPFOR switched on, encoding fell to about 3
  tiles per second at z14 and the build was stopped after 25 minutes, so those
  options are not measured.
- Our finish step cannot decode MLT, so for MLT packs it only fixes the header
  and records that the label clean-up (emoji removal) was skipped; that
  clean-up would have to move into the profile before MLT ships (PA has no
  such characters, the Ohio z14 pack has 20 such strings in 5 tiles).

### Recommendation

Ship **z14 with the clamp and the street-furniture list, MVT** now (built for
PA and OH). Then, in this order: (1) MLT, after one run of an MLT pack on the
Android emulator (-9.6 %); (2) with the style engineer, leave out the landuse
kinds the style does not draw, or decide to draw them (-4.5 %); (3) drop
footways (-3.3 %) and simplify landuse at the top zoom (-2.7 %) if the owner
agrees nothing visible is lost. Building detail (+12 %) only if the owner
prefers individual houses to merged blocks at street level (D4-D6).

## 5. Packs produced and the app's pack manifest

| File | Bytes | Planetiler | Peak RAM | Finish |
|---|---|---|---|---|
| `data/map/packs-v2/pa-261114-z14.pmtiles` (recommended; profile patch 2, review round) | 244,703,510 | 542.6 s | 5.26 GiB | 26.3 s, 0.31 GiB, 0 tiles changed |
| `data/map/packs-v2/oh-261114-z14.pmtiles` (recommended; profile patch 2, review round) | 252,304,209 | 552.5 s | 4.87 GiB | 21.4 s, 0.32 GiB, 5 tiles / 20 strings changed (emoji) |
| first-round builds of the same two packs (profile patch 1), kept in `data/map/work/patch-v1-packs/` | PA 244,584,926; OH 252,208,251 | 459.2 s; 808.5 s (contention) | 5.34; 5.13 GiB | 19.3 s; 51.5 s |
| `data/map/packs-v2/us-overview-ne512-tiger2026-z0-7.pmtiles` | 3,661,759 | 33.6 s (2 threads) | 0.51 GiB | 0.9 s |
| `pa-261114-z15.pmtiles` (lanes, TIGER clip, z15; patch 1) | 463,941,975 | 468.3 s | 5.41 GiB | 42.6 s, 0.58 GiB |
| `pa-261114-z14-bdetail.pmtiles` (patch 1) | 274,486,848 | 501.5 s | 5.31 GiB | 23.9 s |
| `pa-261114-z14-mlt.pmtiles` (patch 1) | 221,091,882 | 681.1 s | 5.20 GiB | 3.0 s (header only) |
| `pa-261114-z14-nolanes.pmtiles` (lane cost measurement; patch 1) | 243,921,658 | 1,005.0 s (machine at 100 % CPU) | 5.25 GiB | 26.2 s |
| `pa-261114-z14-nolanes-t1`/`-t2` and `pa-261114-z14-t2` (review round timing builds; patch 2 with lanes off writes all 47,514 tiles identical to the patch-1 `nolanes` pack; the files were deleted after the comparison, records in `data/map/work/timing-review/`) | 243,921,657 / 244,703,510 | 651.0, 501.9, 480.0 s | 5.17-5.25 GiB | 19.6-25.5 s |

The comparison packs marked "patch 1" were not rebuilt in the review round.
Patch 2 changes only z14 road lines that carry lane values (in PA: z0-13
byte-identical, 2,871 of 35,412 z14 tiles changed, +118,584 B = +0.05 %), so
their size rows in section 4 stand; their lane lines can still contain the
reversals of patch 1, so they are not for lane work. Rebuild the MLT variant
with patch 2 before the Android MLT test.

First round for comparison: PA z0-15 652.1 s and 5.36 GiB; the first v2 builds
were faster, probably because the machine was less busy then. All packs pass
`pmtiles verify`; PA z14, OH z14 and the overview pass the glyph check of
`tile_stats.py`.

### What the map part of a pack provides (`<pack>.map.json`)

`build_pack.py` writes, next to each pack, the fields of a pack manifest's map
part plus what the app should know about this round's changes, read back from
the finished file:

```json
{ "file": "pa-261114-z14.pmtiles", "format": "pmtiles", "format_version": 3,
  "tile_type": "mvt", "tile_compression": "gzip",
  "schema": "protomaps-basemap", "schema_version": "4.15.2",
  "schema_extensions": {"beamline_profile_patch": "2", "lanes_minzoom": 14, "top_zoom": 14,
                        "poi_drop_kinds": ["bbq", "bench", "..."]},
  "source_name": "protomaps", "min_zoom": 0, "max_zoom": 14,
  "bounds": [-80.519851, 39.719799, -74.689561, 42.516072],
  "clip": {"file": "data/map/work/data/census/tl_2026_us_state.zip", "sha256": "2e17ea40...", "state": "PA"},
  "osm_snapshot": "2026-11-14", "layers": ["boundaries", "buildings", "..."],
  "bytes": 244703510, "sha256": "...", "attribution": "© OpenStreetMap contributors" }
```

**Where the app is now.** While I worked, the Android track moved to
manifest version 2 (`docs/pack-format.md`, their file): packs carry data only,
the style and a whole-US overview ship inside the app, and
`assets/rules/map-compose.json` draws state packs from z8 over the overview,
the overview's labels up to z8 and its other layers up to z10. That is the
layering this report's screenshots use (`base`, `basemax=8`). A v2 `parts.map`
needs `file`, `format` `pmtiles`, `format_version` 3, `schema`
`protomaps-basemap`, `schema_version` (major 4), `min_zoom`, `max_zoom`,
`bytes` and `sha256`: everything is in `<pack>.map.json`, and the v2 packs
pass the app's rule (schema major 4). The overview map goes into the app with
their own script:
`python android/scripts/sync_assets.py basemap --basemap data/map/packs-v2/us-overview-ne512-tiger2026-z0-7.pmtiles`
(its default still looks for the first round's `data/map/packs/base-us-*-z0-7.pmtiles`).

**Proposed additions (for the Android track; I changed none of their files):**

1. `parts.map.tile_type` (`"mvt"` by default, `"mlt"`): a reader that cannot
   decode MLT must refuse the part, and the composer should put
   `"encoding": "mlt"` on that pack's source. MapLibre Native 13.7.1 derives the
   encoding from the PMTiles header itself, but MapLibre GL JS with pmtiles
   4.5.0 (viewer) and a Python reader (comma 3X) need the key.
2. `parts.map.schema_extensions` (`lanes_minzoom`, `top_zoom`,
   `poi_drop_kinds`): a lane-level style should check `lanes_minzoom` before
   drawing lanes from a pack; `top_zoom` 14 tells the composer that the pack's
   last zoom holds every POI and house number.
3. `parts.map.clip` (source file and SHA-256, state): two neighbouring packs
   cut with different outline vintages could overlap or leave a gap; the pack
   tool can refuse to pair them, as it already refuses mixed OSM snapshots.
4. The overview pack has no OSM snapshot and no state; if it is ever shipped
   as a pack rather than inside the app, `state` and `osm_snapshot` must be
   optional for that kind of pack.

## 6. How to rerun

From the repo root on Windows. Every step skips finished work; `--force`
redoes it.

```powershell
pwsh tools/fetch_osm_extracts.ps1 -States pennsylvania,ohio -Date 261114   # shared inputs (orchestrator's script)
py -3.12 -m venv .venv/map
.venv/map/Scripts/python -m pip install -r packbuilder/map/requirements.txt
python tools/fetch_map_tools.py                                  # Maven, go-pmtiles, planetiler.jar 0.10.2 (SHA-256 pinned)
.venv/map/Scripts/python packbuilder/map/build_profile.py         # patched profile jar -> data/map/bin/protomaps-basemap-4.15.2-beamline2.jar (patch version from versions.json)
.venv/map/Scripts/python packbuilder/map/fetch_sources.py         # Census files (pinned) + first round's auxiliary inputs
# recommended packs (about 8-14 min each)
.venv/map/Scripts/python packbuilder/map/build_pack.py pa         # -> data/map/packs-v2/pa-261114-z14.pmtiles (+ .build.json, .map.json)
.venv/map/Scripts/python packbuilder/map/build_pack.py oh
.venv/map/Scripts/python packbuilder/map/overview/build_overview.py   # -> data/map/packs-v2/us-overview-ne512-tiger2026-z0-7.pmtiles
# comparison builds (build_config.json "variants")
.venv/map/Scripts/python packbuilder/map/build_pack.py pa --variant z15
.venv/map/Scripts/python packbuilder/map/build_pack.py pa --variant z14-bdetail
.venv/map/Scripts/python packbuilder/map/build_pack.py pa --variant z14-mlt
.venv/map/Scripts/python packbuilder/map/build_pack.py pa --variant z14-mlt-fast   # stopped after 25 min, see section 4
.venv/map/Scripts/python packbuilder/map/build_pack.py pa --variant z14-nolanes
#   or one after another: .venv/map/Scripts/python packbuilder/map/run_queue.py QUEUE.txt
# checks
tools/bin/pmtiles/pmtiles.exe verify data/map/packs-v2/pa-261114-z14.pmtiles
# (--out-dir keeps the reports out of the delivered pack folder; without it they are written next to the pack)
.venv/map/Scripts/python packbuilder/map/lanes_check.py data/map/packs-v2/pa-261114-z14.pmtiles --state pa --coverage --direction --out-dir data/map/work/checks
.venv/map/Scripts/python packbuilder/map/lanes_check.py data/map/packs-v2/oh-261114-z14.pmtiles --state oh --coverage --direction --out-dir data/map/work/checks
.venv/map/Scripts/python packbuilder/map/lanes_check.py data/map/packs-v2/pa-261114-z15.pmtiles --state pa --zoom 15 --out-dir data/map/work/checks   # patch-1 pack: fails the 3 review-round ways
.venv/map/Scripts/python packbuilder/map/lanes_check.py data/map/work/patch-v1-packs/pa-261114-z14.pmtiles --state pa --direction --out-dir data/map/work/checks/v1patch   # shows the 5 reversals of patch 1
.venv/map/Scripts/python packbuilder/map/tile_stats.py data/map/packs-v2/pa-261114-z14.pmtiles --deep 14 --out-dir data/map/work/checks      # glyph check
.venv/map/Scripts/python packbuilder/map/size_report.py breakdown data/map/packs-v2/pa-261114-z15.pmtiles --out-dir data/map/work/checks
# lane-tag build time: run these one after another (a QUEUE.txt for run_queue.py), nothing else heavy of yours meanwhile:
#   build_pack.py pa --force | build_pack.py pa --variant z14-nolanes --force --tag t1 | build_pack.py oh --force
#   build_pack.py pa --force --tag t2 | build_pack.py pa --variant z14-nolanes --force --tag t2     then
.venv/map/Scripts/python packbuilder/map/build_times.py data/map/logs/pa-261114-z14.log data/map/logs/pa-261114-z14-nolanes-t1.log data/map/logs/pa-261114-z14-t2.log data/map/logs/pa-261114-z14-nolanes-t2.log
.venv/map/Scripts/python packbuilder/map/size_report.py option data/map/packs-v2/pa-261114-z14.pmtiles landuse_undrawn   # any key of size_options.json
.venv/map/Scripts/python packbuilder/map/census_clip.py pa --compare-poly data/osm/pennsylvania.poly
.venv/map/Scripts/python packbuilder/map/boundary_compare.py data/map/packs/pa-261114-z0-15.pmtiles --state pa --box=-80.53,39.70,-80.50,42.00
.venv/map/Scripts/python packbuilder/map/seam_report.py data/map/packs/pa-261114-z0-15.pmtiles data/map/packs/oh-261114-z0-15.pmtiles --states pa oh --out data/map/work/seam/seam-v1-pa-oh-z0-15.json
.venv/map/Scripts/python packbuilder/map/seam_report.py data/map/packs-v2/pa-261114-z14.pmtiles data/map/packs-v2/oh-261114-z14.pmtiles --states pa oh --out data/map/work/seam/seam-v2-pa-oh-z14.json
# screenshots (exit 1 on any MapLibre error, console message, failed or external request)
.venv/map/Scripts/python packbuilder/map/screenshots.py --views packbuilder/map/screenshot_views_v2.json
.venv/map/Scripts/python packbuilder/map/screenshots.py --views packbuilder/map/screenshot_views_review.json        # R1-R3, lane direction marks
.venv/map/Scripts/python packbuilder/map/screenshots.py --views packbuilder/map/screenshot_views_review.json --only R1-lanesdir-braddock-z18 --packs data/map/work/patch-v1-packs --prefix v1patch-
.venv/map/Scripts/python packbuilder/map/screenshots.py --views packbuilder/map/screenshot_views_seam_before.json --packs data/map/packs
python viewer/serve.py --packs data/map/packs-v2     # interactive; base pack: ?base=us-overview-ne512-tiger2026-z0-7.pmtiles&basemax=8
```

Logs: `data/map/logs/<pack>.log` (Planetiler), `data/map/logs/queue-*.out`,
`data/map/logs/size-options.out`. Size-option results:
`data/map/work/size-options/*.json`. Seam reports: `data/map/work/seam/`.

## 7. Versions and licences

| Item | Version | Licence (checked at) | Ships to device |
|---|---|---|---|
| Profile patch `beamline-profile.patch` (ours) | 2 (review round; 1 in the first build) | BSD-3-Clause like the profile it changes (repo LICENSE.md of protomaps/basemaps) | no (its output does) |
| Planetiler release jar (overview builds) | 0.10.2, SHA-256 f310bd04... (GitHub release digest) | Apache-2.0 (GitHub LICENSE) | no |
| MLT encoder inside Planetiler, `org.maplibre:mlt` | 0.0.10 | Apache-2.0 (its POM; GitHub licence of maplibre-tile-spec) | no |
| Census TIGER/Line 2026 states, primary roads | 2026 | public domain: "Copyright protection is not available for any work of the United States Government" (TIGER/Line 2026 technical documentation, 1.2); TIGER/Line is a Census trademark, not used in any product name | yes (clip line, overview roads) |
| Census 2026 Gazetteer places, Vintage 2025 population estimates | 2026 / 2025 | public domain (US government work) | yes (overview towns) |
| Census cartographic boundary file cb_2025_us_state_500k | 2025 | public domain | no (comparison only) |
| Natural Earth vector | 5.1.2 (VERSION file in the zip) | public domain, "No permission is needed", "Crediting the authors is unnecessary" (naturalearthdata.com terms of use) | yes (overview, and z0-5 of state packs as before) |
| OpenStreetMap (Geofabrik 2026-11-14) | 261114 | ODbL 1.0 | yes (state packs) |
| shapely | 2.2.0 | BSD-3-Clause (PyPI) | no |
| numpy (shapely dependency) | 2.5.5 | BSD-3-Clause and bundled 0BSD/MIT/Zlib/CC0 parts (PyPI license expression) | no |
| pyshp | 3.1.8 | MIT (PyPI) | no |
| osmium (pyosmium) | 4.3.2 | BSD-2-Clause (PyPI) | no |
| Pillow | 12.0.0 | MIT-CMU (PyPI) | no |
| first round's tools (Planetiler 0.10.2 inside the profile jar, Maven 3.9.16, go-pmtiles 1.31.2, pmtiles 3.8.1, psutil 7.2.2, Playwright 1.64.1) | unchanged | see `02-map.md` section 9 | no |

Nothing that ships is GPL, AGPL or LGPL. No Organic Maps or OsmAnd data.

## 8. Failures, dead ends, not verified

- **Not run on Android:** none of the v2 packs, the overview pack or MLT ran
  on MapLibre Native; the Android emulator belongs to another track and was
  in use. The MLT claims for Native rest on its changelog and source code.
- **Lane direction (corrected in the review round).** The first version of
  this report said Planetiler's line merging keeps segment directions. That
  was wrong: the merger only tries to, and 5 two-way lines of the patch-1 PA
  pack came out reversed (section 10). Patch 2 keeps direction-dependent lane
  lines out of the merge, and `lanes_check.py --direction` now checks the
  direction of every such line, not only the checkpoint ways.
- **Overview:** no forest, rivers or small lakes at z6-7 (the style draws
  rivers only from z9 anyway); the Aleutians west of 180 degrees are outside its
  bounds; Natural Earth's US routes are 1:10M lines, so at z7 they can be a
  pixel or two off the state packs' roads at z8.
- **Wall times** are noisy: the Ohio build took 808 s against 459-528 s for
  PA builds because two of my measurement scripts and the other tracks' jobs
  were running at the same time.
- **Size options** measured by rewriting tiles are exact for removals; the
  simplification rows use shapely's Douglas-Peucker, not Planetiler's, so a
  profile change would land near but not exactly on those numbers.
- **The finish step and MLT:** MLT tiles are copied unchanged, so the emoji
  clean-up does not run for MLT packs (section 4).
- **The style changed under me.** The style engineer regenerated
  `style-bundle/` during this round (last at 16:41 local time). The
  before/after seam pairs (`B*`, `A*`) were re-rendered together at the end
  with the same style; the other screenshots used the bundle as it was when
  they were taken. All screenshot runs, including a re-run of the first
  round's 21 views against `data/map/packs` (to
  `data/map/work/regress-v1-screenshots`), ended with 0 errors, 0 console
  messages and 0 failed or external requests.
- **MLT with FSST and FastPFOR** was stopped (section 4), so whether those
  encodings shrink the pack further is unknown.
- Bugs of mine found and fixed on the way: the build queue could not start a
  relative Windows program path; `build_pack.py`'s map-part writer stumbled on
  the PMTiles enum type once (re-run with `--map-json-only`); the screenshot
  route handler took Playwright's second argument as the encoding (fixed
  before any MLT screenshot was kept).

## 9. Outside this track

- **Android track:** the manifest proposals in section 5; to try the new
  packs: `python tools/fetch_android_map_pack.py --pmtiles data/map/packs-v2/pa-261114-z14.pmtiles`
  and `--state oh --pmtiles data/map/packs-v2/oh-261114-z14.pmtiles` (the
  script's default still looks in `data/map/packs/` for z0-15 files), and
  `python android/scripts/sync_assets.py basemap --basemap data/map/packs-v2/us-overview-ne512-tiger2026-z0-7.pmtiles`
  for the overview. One MLT pack run on the emulator would decide the MLT
  question.
- **Viewer** (not mine): to open MLT packs it should set `encoding: "mlt"`
  when the PMTiles header says tile type 6 (as Native does); for now
  `screenshots.py` adds it.
- **Style engineer:** lanes are in the tiles for the later lane-level layer
  (z14+, motorway/trunk/primary only); landuse kinds the style does not draw
  cost 4.5 % of a pack, so either draw them or let the builder drop them
  (`size_options.json` "landuse_undrawn" lists the kinds); `drinking_water`
  and `bench` are in both the style's POI list and the builder's drop list, so
  the style could drop them too. For the lane layer: `lanes:forward` and
  `turn:lanes:forward` are relative to the line's own direction (kept equal to
  the OSM way's since patch 2), and `lanes:both_ways` is the centre turn lane.
  From the review round: in the overview range (z3-5) the interstates are
  hairline white on light grey and almost vanish at phone width, and the state
  lines are faint (`O1`/`O2`); please widen them and raise their contrast there.
- **Attribution (style engineer and the app's style composer):** the style
  templates put "© OpenStreetMap contributors" on the vector source, so it also
  shows over the public-domain overview pack, whose metadata says "Natural
  Earth; US Census Bureau". Build the credit line from the loaded packs instead
  (each pack's metadata / `.map.json` "attribution"). In practice a state pack
  is always installed when the app is used, so the OSM credit is needed anyway;
  only an overview-only screen over-credits.
- **Routing and search tracks:** the TIGER/Line state outline
  (`census_clip.py`) could be the common state boundary for all three parts
  of a pack, so routing tiles, search index and map end at the same line.
- `README.md` (not mine): could mention the overview pack and `packs-v2`.
- Nothing was installed system-wide; WSL was not used. Outside the repo only
  pip's download cache was written.

## 10. Review and fixes

Two independent reviews of the first build ("reproduce" and "break") both
judged the track sound with fixes. In this round a second engineer took the
track over ("I" below): I checked every major finding myself before changing
anything, fixed the confirmed ones and the cheap minor ones, rebuilt the PA
and OH packs with profile patch 2 and re-ran the checks and the affected
screenshots. There were
no blockers. Other engineers' jobs (two Android emulators, Gradle builds, other
pack builders) ran throughout. No emulator was started for this track.

| # | Finding (lens) | Severity | Outcome |
|---|---|---|---|
| 1 | Lane direction is reversed on a few two-way roads: Planetiler's line merge does not keep segment direction, contrary to the report (break) | major | **Confirmed and fixed** (profile patch 2) |
| 2 | `lanes:both_ways` and `turn:lanes:both_ways` (centre turn lane) are not carried; this explains the brief's 26 % / 23 % turn-lane share (reproduce, break) | minor | **Fixed** (profile patch 2, checks, report) |
| 3 | The build-time cost of the lane patch was not measured (reproduce) | minor | **Measured**: below this machine's noise (section 1, "Cost") |
| 4 | Interstates very faint at the whole-US view; OSM attribution shown over the public-domain overview (reproduce) | minor | **Left open**: both live in the style and the style composer, which this track does not own; requests in section 9 |
| 5 | The documented check commands overwrite the sidecar files shipped in `packs-v2` (reproduce) | minor | **Fixed**: `--out-dir` on `lanes_check.py`, `tile_stats.py`, `size_report.py` |
| 6 | The report misstates the building-polygon count at z15 (break) | minor | **Fixed**: 2.60 M at z15, 2.63 M over all zooms (`pa-261114-z15.breakdown.json`: 2,601,497 and 2,630,276) |

**1. Lane direction (major, fixed).** The cause is in Planetiler 0.10.2's
source (`FeatureMerge.java` and `util/LoopLineMerger.java` at tag v0.10.2):
`mergeTwoEdges` only "attempt[s] to preserve segment directions", two edges
meeting head to head cannot both keep theirs, and `strokeMerge` pairs edges at
a junction by angle regardless of direction. I wrote an all-lines direction
check into `lanes_check.py --direction` (same method as the reviewer's
`direction_check.py`, my own code: per line part, segments matched to OSM ways
with identical lane values within 3 m, votes by projection). On the
first-round PA pack it finds exactly the reviewer's result: 16,373 lines, 5
reversed where it matters (tiles 14/4556/6178 South Braddock Ave, way
504762367; 14/4717/6198, way 541588175; 14/4733/6204, way 366164186;
14/4775/6192, way 424768985; 14/4747/6197, way 730221111) and 1 reversed with
symmetric values (14/4555/6176, forward = backward = 2). On the first-round OH
pack it finds 4 more that matter, 3 of them one-way `turn:lanes` lines, so
one-way roads were not safe either.

Fix: patch 2 keeps every road feature with `lanes:forward`, `lanes:backward`,
`turn:lanes`, `turn:lanes:forward` or `turn:lanes:backward` out of
`mergeLineStrings` (one feature per OSM way and tile, in the way's
direction). The reviewer's other suggestion, a unique per-way attribute, would
have added a key to every lane feature for the same effect. Result: PA 18,990
and OH 16,873 lines checked, **0 reversed**; the 17 checkpoint ways (3 new:
Peach St with a centre turn lane twice, South Braddock Ave) all pass. With
lanes off the patch-2 jar writes all 47,514 PA tiles byte-identical to patch
1; with lanes on, z0-13 are byte-identical and 2,871 of 35,412 z14 tiles
change. Size: PA +118,584 B (+0.05 %) over patch 1, OH +95,958 B (+0.04 %).

Renders: `v1patch-R1` against `R1` shows the reversed stretch of South
Braddock Ave and its fix (looked at). The 12 earlier views at z14 and above
were re-rendered with the patch-1 packs (`data/map/work/regress-v1patch-shots/`)
and the patch-2 packs (`data/map/screenshots-v2/`) with the same style: 7 are
pixel-identical; D2 and S4 differ only in anti-aliasing (31 and 0 pixels above
24/255; D2 looked at side by side); L2, L3 and L4 (lane overlay) differ because
separate per-way lines get their own round caps and labels. In L2 the patch-1
pack had a doubled, overlapping "4:left|left|through|through" label that is
now single (both looked at). The wrong statement in section 8 is corrected.

**2. Centre turn lane (minor, fixed).** Confirmed with my own pyosmium count:
1,594 in-scope PA ways carry both `lanes:both_ways` and
`turn:lanes:both_ways`, one of the two is on 4.1 % of trunk and 3.4 % of
primary ways, and with any `turn:lanes*` key counted the turn-lane share of
ways is 17.0 / 26.0 / 23.0 %, the brief's numbers. Patch 2 writes
`lanes:both_ways` (integer) and `turn:lanes:both_ways` (string);
`lanes_check.py` compares all eight keys, and its coverage table has a "both"
column (section 1). Checkpoints on Peach St / US 19 south of Erie: way
121593871 (lanes 5 = 2 + 2 + 1, centre "left") and way 530832900
(6 = 2 + 3 + 1, with `turn:lanes:backward`) both match; screenshot `R3` shows
"5 = fwd 2 / bwd 2 / both 1 left" on Peach St (looked at).

**3. Build-time cost (minor, measured).** Four PA z14 builds back to back
(with, without, OH, with, without), nothing else of mine running, Planetiler's
own per-stage CPU times read by the new `build_times.py`. The with-lanes build
was faster in wall time in both pairs and equal or faster in CPU time, while
repeats of the identical build differed by up to 23 % (wall) and 13 % (CPU).
So the cost is below what this shared machine resolves; the first report's
"no measurable build time" is now backed by a measurement and worded that way.
A quiet machine would be needed to put a number on it below a few percent.

**4. Overview look and attribution (minor, left open).** The overview pack is
unchanged since the reviews, so O1/O2 stand as the reviewer described them.
Road width and contrast at z3-5 are style settings (`style/`, `style-bundle/`,
another engineer's folders), and the attribution line comes from the style
templates and the composer (`viewer/`, the Android app). The data side is
right: the overview's metadata says "Natural Earth; US Census Bureau" and the
overview carries the interstates from z3. Requests are in section 9.

**5. Check commands write into `packs-v2` (minor, fixed).** `lanes_check.py`,
`tile_stats.py` and `size_report.py` (breakdown and option) take `--out-dir`;
tested on the overview pack, and the delivered folder stayed untouched.
Section 6 and `packbuilder/map/README.md` use `--out-dir data/map/work/checks`.
The sidecar reports in `packs-v2` were regenerated on purpose once for the new
PA and OH packs (`.lanes-z14.json`, `.stats.json`, PA `.breakdown.json`).

**6. Building count (minor, fixed)** in section 4.

**Re-run after the fixes (patch-2 packs):** `pmtiles verify` passes on PA and
OH; `lanes_check.py` gives 0 mismatches on PA and OH (checkpoints, coverage,
direction); the `tile_stats.py --deep 14` glyph check passes on PA and OH;
`seam_report.py` PA/OH gives exactly the same per-zoom numbers as before (236
shared z0-14 tiles, 0 identical features, 95 same-name roads at z14); the finish step
changed 0 tiles in PA and 5 tiles / 20 strings (emoji) in OH, as before; every
re-rendered view had 0 MapLibre errors, 0 console messages and 0 failed or
external requests.

**Still open after this round:**

- The comparison packs `pa-261114-z15`, `-z14-bdetail` and `-z14-mlt` are
  patch-1 builds (their lane lines can hold the reversals and lack
  `:both_ways`); their size rows stand. Rebuild `z14-mlt` with patch 2 before
  the Android MLT test.
- No pack has run on MapLibre Native Android (the emulator belongs to the
  Android track); MLT with FSST/FastPFOR is unmeasured; option (b) of the
  overview (US extract) was not built. Unchanged from section 8.
- `data/map/work/patch-v1-packs/` holds copies of the first-round PA and OH z14
  packs for the before/after checks; the current builder cannot recreate them
  without patch 1 (in git history).
