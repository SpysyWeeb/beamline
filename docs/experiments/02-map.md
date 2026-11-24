# Experiment 02: map pack (PMTiles basemap, offline style, viewer)

Date: 2026-11-15. Machine: Windows 11, 12 logical CPUs, 32 GB RAM. **Other
engineers' heavy builds (Gradle, an Android emulator, search and address
builds, downloads) ran on the same machine during every measurement below**,
so wall times are pessimistic and noisy. Every heavy job used 4 threads.

This report was written by the first engineer and then revised after two
reviews; section 12 lists every review finding and what happened to it.
Units: file sizes in decimal MB (10^6 bytes); RAM in GiB (2^30 bytes) with the
decimal GB in brackets where it is compared with the "about 6 GB" budget.

## Result in short

- A working, re-runnable pipeline: Planetiler 0.10.2 + the Protomaps basemap
  profile (tiles 4.15.2, compiled from a pinned commit) turns
  `data/osm/<state>-261114.osm.pbf` into one PMTiles file per state, clipped to
  the state's `.poly` outline. A finish step of our own (`finish_pack.py`)
  then writes correct header bounds and removes characters the font bundle
  cannot draw (emoji in Ohio trail names).
- **PA z0-15: 463.8 MB, 12.1 min (10.9 Planetiler + 1.3 finish), 5.36 GiB
  (5.76 GB) peak. OH z0-15: 466.7 MB, 12.5 min, 5.42 GiB (5.82 GB) peak.
  PA z0-14: 220.3 MB, 10.0 min, 5.15 GiB (5.53 GB) peak.** All with a 4 GB
  Java heap.
- **z15, not z14.** The Protomaps profile clamps late POIs and building
  details into z15 tiles with a fixed `Math.min(minZoom, 15)`. A z14 build
  therefore does not just lose precision: it drops about 88 % of the POIs
  (fuel stations, shops, restaurants) and all house numbers. See screenshots
  21 and 23. Fixing that would need a patch to the profile.
- An offline style bundle in git (1.44 MiB, 781 files): `day.json`,
  `night.json`, Noto Sans glyphs (OFL), the Protomaps sprites (MIT-derived).
  It needs no network. The source URL is `pmtiles://{{PACK_URL}}`, so on
  Android it becomes `pmtiles://file:///abs/path.pmtiles`. Street and place
  labels were recoloured for contrast (checked by the generator), and one-way
  arrows now also show for `oneway=-1`. The style-spec validator reports no
  errors; a static check finds nothing MapLibre Native Android lacks; and the
  Android track ran this bundle with these packs on MapLibre Native Android
  13.7.1 in an emulator (their report, `05-android-skeleton.md`).
- `viewer/`: static page, MapLibre GL JS 6.16.0 and pmtiles 4.5.0 vendored,
  with a small Range-capable local server. 41 headless Edge (SwiftShader
  WebGL 2) screenshots, all with 0 MapLibre errors, 0 console messages, 0
  failed requests and 0 requests leaving 127.0.0.1; the screenshot tool now
  fails when any of these appear. I opened and looked at the ones listed in
  section 5.
- Several states: `pmtiles merge` refuses the job ("2015 overlapping tiles ...
  Inputs must be disjoint"). A feature-level merge script works: 9.3 s, 1.09
  GiB RAM for PA+OH. **The recommendation is still one source per installed
  state pack, with interleaved layer copies, plus one shared low-zoom base pack
  (z0-7, whole US, 17.8 MB measured on a landcover-free cut).** The reasons are
  in section 6.
- **Whole-US estimate: about 17 GB at z15, about 8 GB at z14; about 7.5 to 8
  hours of building per-state at 4 threads.** These are estimates, see section 7.

## 1. Tool choice

| Candidate | Verdict |
|---|---|
| **Planetiler + Protomaps basemap profile** (chosen) | Planetiler is Apache-2.0 and runs on the installed Java 25 on Windows, with no WSL. The profile is BSD-3-Clause, and its schema is adapted from Tilezen (MIT). The official styles in `@protomaps/basemaps` are BSD-3 code with a CC0 visual design. The fonts and sprites have their own licences: OFL-1.1 for the fonts and MIT-derived icons for the sprites. The roads layer carries `name`, `ref`, `oneway`, `shield_text`, `network` and link/bridge/tunnel flags. The schema is the same one Protomaps builds daily for the planet, so their build is a direct size reference. |
| Planetiler or tilemaker + Shortbread (CC0 schema) | Viable on paper: Shortbread profiles and MapLibre styles exist (VersaTiles). Their licences and driving detail (shields from route relations, one-way) were not checked in depth. Not built. |
| tilemaker | BSD-licensed and has a Windows build. It has a reputation (not measured here) for being slower and using more memory than Planetiler on large extracts, and we would still need a schema and a style. Not built. |
| OpenMapTiles (schema/profile) | Excluded by the brief: CC-BY schema licence with a required credit. |

The profile is not on Maven Central, so `build_profile.py` clones
`protomaps/basemaps` at commit `42ffaaa4` (CHANGELOG "Tiles 4.15.2", which
depends on Planetiler 0.10.2) and builds the fat jar with a portable Maven
3.9.16. That takes about 1 minute. The Maven local repository goes in
`data/map/m2`, not `~/.m2`.

Inputs besides OSM, fetched by `fetch_sources.py` and hashed into
`data/map/work/data/sources/manifest.json`:

| File | Size | Licence | Used |
|---|---|---|---|
| natural_earth_vector.gpkg.zip | 446 MB | public domain | z0-5 earth, water, boundaries, places |
| water-polygons-split-3857.zip | 933 MB | ODbL (OSM-derived, osmdata.openstreetmap.de) | oceans; ships in the tiles |
| land-polygons-split-3857.zip | 954 MB | ODbL | earth layer; ships in the tiles |
| qrank.csv.gz | 126 MB | CC0 (Wikidata QRank) | ranking only, build time |
| pgf-encoding.zip | 0.8 MB | MIT (wipfli/pgf-encoding), Noto font data OFL | Devanagari encoding, build time |
| daylight-landcover.gpkg | 36 MB | CC-BY 4.0 (ESA WorldCover) | **not used and no longer downloaded** (only fetched if `"landcover": true`) |

**These inputs are not pinned.** Their publishers serve only the latest file
(the water and land polygons change daily, QRank weekly), so a hash in git
would break the next fetch. What a build used is recorded instead: SHA-256,
size and Last-Modified of each file, copied into every pack's `.build.json`.
To reproduce a pack byte for byte later, archive these files with the release.

**Landcover is switched off.** The profile's `landcover` layer (z0-7 only)
comes from ESA WorldCover under CC-BY 4.0, which would add a credit line, the
same problem that excluded OpenMapTiles. `build_pack.py` feeds the profile a
valid but empty GeoPackage (`build_config.json: "landcover": false`). The
build log shows `[landcover] - Finished in 0.1s`, and the packs have no
`landcover` layer. Credit needed for our packs: "© OpenStreetMap contributors"
only.

**Hosted alternative (noted, not depended on).** Protomaps publishes a daily
planet build of the same schema. Today's is `build.protomaps.com/20261114.pmtiles`:
139.5 GB, version 4.15.2, Planetiler 0.10.2. A region can be cut out with
`pmtiles extract https://build.protomaps.com/20261114.pmtiles out.pmtiles --region=pa.geojson`,
which fetches only the needed byte ranges. The docs
(docs.protomaps.com/basemaps/downloads) say: "hotlinking to these downloads
are discouraged", copy the file to your own storage instead, "URLs may change",
the output is an ODbL Produced Work with "OpenStreetMap attribution required",
and BLAKE3 hashes are provided. No rate limits are stated. **The hosted build
includes the CC-BY landcover layer** (531 of the 1,047 tiles of a US z0-7 cut
carry it), so a cut is only clean after dropping that layer (section 6). I
used it only for reference sizes (dry runs) and for a demo low-zoom base pack.

## 2. Measurements: builds

Command: `build_pack.py <state> [--maxzoom N]`. It runs Planetiler into
`data/map/work/raw/`, then `finish_pack.py` into `data/map/packs/`. Peak RAM of
Planetiler is the RSS (working set) of the Java process tree, polled every
0.25 s with psutil (`measure.py`), so it is a lower bound. It includes heap,
off-heap buffers and resident pages of Planetiler's memory-mapped temporary
files (`storage=mmap`, node map `sparsearray` on mmap, per the build log).
The finish step's peak is the peak working set of its Python process. Other
heavy jobs were running.

| Pack | Java heap | Planetiler wall | Planetiler peak RAM | Finish step | Output |
|---|---|---|---|---|---|
| PA z0-15 | -Xmx4g | 652.1 s | 5.36 GiB (5.76 GB) | 75.8 s, 0.55 GiB, 0 tiles changed | 463.8 MB (463,787,526 B) |
| PA z0-14 | -Xmx4g | 581.0 s | 5.15 GiB (5.53 GB) | 19.5 s, 0.25 GiB, 0 tiles changed | 220.3 MB (220,283,766 B) |
| OH z0-15 | -Xmx4g | 697.4 s | 5.42 GiB (5.82 GB) | 51.7 s, 0.54 GiB, 16 tiles / 46 strings changed | 466.7 MB (466,665,335 B) |
| PA z0-15, first build (kept for reference) | -Xmx5g | 631.6 s | 6.72 GiB (7.22 GB) | - | 464.0 MB |
| PA+OH merge (`merge_packs.py`) | Python | 9.3 s | 1.09 GiB | - | 930.0 MB |

The first PA build ran with a 5 GB heap and went over the ~6 GB budget. PA
z0-15 was rebuilt with the 4 GB default: 5.76 GB. The Planetiler output of the
rebuild is byte-identical in size (464,011,330 B) to the 5 GB build; the
reproduce review had already shown the build to be deterministic.

The OH and PA z0-14 Planetiler outputs were built before the finish step
existed and were finished without rebuilding (their raw files and records were
moved into `data/map/work/raw/`). The finished packs are 0.05 to 0.8 MB
smaller than Planetiler's output because the pmtiles Python writer
deduplicates every identical tile content, where Planetiler deduplicates only
some.

The packs hold **8.39 M features (PA z0-15, all zooms; 6.27 M at z15)** and
8.87 M (OH). The "17.8 M features" in the first version of this report was
Planetiler's counter of features rendered before the profile merges them.

Where the time goes in the PA z0-15 build (4 GB heap), from Planetiler's log;
the first build's times in brackets:

| Stage | Time | Notes |
|---|---|---|
| startup (QRank load) | 0:22 (0:35) | |
| ne | 1:52 (2:47) | Unzips and reads the 446 MB Natural Earth GeoPackage, single-threaded. A fixed cost of every state build. |
| osm_pass1 | 0:08 (0:08) | 45.9 M nodes, 4.95 M ways, 56.8 k relations |
| osm_pass2 | 5:25 (5:19) | |
| water + land polygons | 1:12 (0:29) | |
| sort + archive | 1:51 (1:11) | |
| finish step | 1:16 | gunzips every tile once, rewrites only those that need it |

Largest tiles in PA, from Planetiler's tile stats:

- largest gzipped: 229 KB (8/72/96, landuse);
- largest raw: 453 KB (z15, Philadelphia buildings, 199 KB gzipped).

### Size per zoom (tiles / MB stored)

From `tile_stats.py` on the finished packs, the bytes of unique tile contents
per zoom (a content shared by several zoom levels is counted at the first):

| zoom | PA z0-15 | PA z0-14 | OH z0-15 | US z0-7 base (landcover-free cut) |
|---|---|---|---|---|
| 0-7 | 21 / 0.37 | 21 / 0.37 | 20 / 0.20 | 1,047 / 17.77 |
| 8 | 17 / 0.93 | 17 / 0.93 | 16 / 0.73 | - |
| 9 | 47 / 2.69 | 47 / 2.69 | 48 / 3.12 | - |
| 10 | 165 / 5.59 | 165 / 5.59 | 160 / 7.66 | - |
| 11 | 613 / 10.82 | 613 / 10.82 | 586 / 14.84 | - |
| 12 | 2,310 / 26.84 | 2,310 / 26.84 | 2,283 / 30.51 | - |
| 13 | 9,055 / 57.05 | 9,055 / 57.05 | 8,918 / 60.74 | - |
| 14 | 35,817 / 112.87 | 35,817 / 115.89 | 36,197 / 114.85 | - |
| 15 | 142,954 / 246.27 | - | 140,959 / 233.69 | - |

z15 is 53 % of the PA pack. Uncompressed bytes per layer at z15 in PA:
buildings 38 % (3.74 M features), roads 31 %, landuse 14 %, POIs 7 %,
water 6 %.

**Why the z14 tiles of the z0-14 pack are 2.7 % bigger** than the same tiles in
the z0-15 pack: Planetiler treats the maximum zoom of a build differently. Its
log shows `min_feature_size_at_max_zoom=0.0625` and
`simplify_tolerance_at_max_zoom=0.0625` pixels, against `min_feature_size=1.0`
and `simplify_tolerance=0.1` below the maximum zoom, so that the last zoom can
be overzoomed. In a sample of 3,205 z14 tiles, the z0-14 pack has 7 % more
bytes in buildings and landuse, 6 % more in water and 8.6 % more landuse
features; POIs and places are identical and roads differ by under 1 %.

Cross-check: Protomaps' hosted build, cut to the same PA `.poly` region
(`pmtiles extract ... --dry-run`), would be 482 MB. That cut uses whole tiles
along the border and includes landcover; ours is 463.8 MB.

## 3. Driving detail in the tiles

Measured in the PA z15 tiles (`tile_stats.py --deep 14 15`; OH is similar):

- **Street names:** 698 k of 981 k road features carry `name`. Minor road
  labels start at z15 in the style.
- **Reference numbers and shields:** 86 k roads carry `ref`. 59 k carry
  `shield_text` + `network`, derived from route relations (US:PA 38.8 k,
  US:US 10.6 k, US:I 6.0 k, US:PA:Turnpike, US:PA:Allegheny:Belt, ...). The
  style draws an Interstate-shaped shield for `US:I` and a generic shield for
  the rest. The sprite shields are monochrome; red, white and blue US shields
  would need our own artwork.
- **One-way:** a `oneway` attribute from z14, a string copied from the OSM tag
  (`Roads.java` line 400): at z15 in PA 63 k `yes`, 73 k `no`, 269 `-1`, 45
  `alternating`, 38 `reversible`. The style draws arrows from z15 (stock: z16)
  for `yes`, and now also for `-1`, rotated by 180 degrees (screenshots 62, 63).
  `alternating` and `reversible` have no fixed direction and get no arrow.
  Arrow direction depends on Planetiler keeping the way's direction when it
  merges lines; its `LoopLineMerger` says it "attempt[s] to preserve segment
  directions". I did not check individual arrows against the OSM ways.
- **POI layer:** `pois`, with `kind`, `kind_detail` and `min_zoom`, from
  amenity/shop/tourism/leisure/highway/railway etc. At z15 PA has 645 k POIs:
  fuel 3,989, parking 6,689, charging_station 533, car_repair 3,423, hotel
  1,395, motel 380, hospital 297, toilets 1,557, services 75, rest_area 31,
  motorway_junction 3,478 (exit points, a possible exit-number label later)
  and fast_food 5,667. The rest-area count looks low for PA and is worth a
  check by the search track. The other layers are `places` (locality,
  neighbourhood, macrohood), `boundaries`, `buildings` (with house numbers at
  z15), `landuse`, `water` and `earth`.

### z14 versus z15

| | PA z0-14 | PA z0-15 |
|---|---|---|
| file | 220.3 MB | 463.8 MB |
| build (Planetiler + finish) | 600 s | 728 s |
| POIs at max zoom | 76,165 | 644,667 |
| fuel stations at max zoom | 1,257 | 3,989 |

The profile emits POIs with `setZoomRange(Math.min(minZoom, 15), 15)`
(`Pois.java` lines 547 and 630). Features whose min zoom is 16 to 18, which
covers most shops, unnamed fuel and parking, and bus stops, are placed into
z15 tiles. With `--maxzoom=14` they are dropped, not clamped to 14.
Geometry overzooms fine: at z16 and z18 the z14 pack's streets, buildings and
one-way arrows look like the z15 pack's. The POIs, café and restaurant labels
and house numbers are missing (screenshots 20 to 23). **Use z15.** A z14 pack
is only worth it with a one-line patch to the profile (clamp to the configured
maxzoom). That would be our own fork; not done.

## 4. Offline style bundle (`packbuilder/map/style-bundle/`, in git)

Generated by `style/generate_style.mjs` from `@protomaps/basemaps` 5.7.2
(flavors `light` → day, `dark` → night, language `en`). The inputs are pinned
by SHA-256 in `style/style_inputs.lock.json`.

| Part | Size | Licence |
|---|---|---|
| day.json, night.json (74 layers each) | 175 KB each | BSD-3 code, CC0 design (Protomaps) |
| glyphs: Noto Sans Regular, Medium, Italic. Real ranges 0-255, 256-511, 768-1023, 8192-8447 | 345 KB per font | SIL OFL 1.1 (`glyphs/OFL.txt`) |
| glyphs: the other 252 ranges of U+0000-FFFF per font | valid empty PBFs of about 34 bytes each, 8.5 KB per font | generated |
| sprites: light, dark, @1x and @2x | 102 KiB | derived from tangrams/icons, MIT |
| **total** | **1,509,280 bytes (1.44 MiB), 781 files** | |

**Placeholders.** The app replaces `{{PACK_URL}}` (source
`pmtiles://{{PACK_URL}}` → `pmtiles://file:///data/.../pa.pmtiles`) and
`{{STYLE_BASE_URL}}` (glyphs `.../glyphs/{fontstack}/{range}.pbf`, sprite
`.../sprites/light`, e.g. `asset://style` or a `file://` folder on Android).
`viewer/compose_style.js` has the reference implementation; it has no UI code.

**Changes from the stock style** (all driven by `style/style_config.json`):

- The Devanagari font switch is replaced with Noto Sans Regular.
- One-way arrows start at z15 instead of z16, and a second arrow layer
  (`roads_oneway_reverse`) draws `oneway=-1` roads with the arrow turned round.
- **Label colours** (`label_overrides`): the stock dark flavour's street labels
  were #666666 / #525252 on a #1f1f1f halo (2.1 to 2.9:1). Now night street
  labels are #c8c8c8 / #b4b4b4 with a 1.5 px halo, addresses and
  neighbourhoods #9a9a9a, towns #d0d0d0, states #7a7a7a; day street labels
  #6e6668 (were #938a8d, 3.35:1 on white). `generate_style.mjs` computes the
  WCAG contrast of every overridden text colour against its halo and stops if
  it is under 4.5:1 (3:1 for the large state names). Result: night 5.9 to
  10.4:1 (state names 3.8:1), day 5.1 to 5.6:1. Before/after: `before-08-...` vs `08-...`.
- Two driving POI layers draw a coloured dot and a name. They cover the kinds
  the stock layer skips: fuel, charging_station, parking, rest_area, services,
  car_repair, car_wash, hotel, motel and hospital.
- POI kinds that have no icon in the sprite (`townhall`) use `building`.
- The source credit is a link: `<a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors</a>`.

**Font coverage, measured** on every `name`, `name:en*` and `pgf:*` string in
the z15 tiles of the finished packs:

- PA: 14,480,073 characters, 99.9990 % covered by the real ranges.
- OH: 11,712,517 characters, 99.9926 % covered.

The uncovered characters are mostly Private Use Area code points (125 in PA,
787 in OH), plus a few Cyrillic letters and some Hangul/CJK (MapLibre GL JS
draws these with a local system font). Every one of them falls in a range for
which the bundle has a file (real or empty placeholder); `tile_stats.py` now
checks that and warns otherwise. General Punctuation is needed: curly
apostrophes and dashes, as in "BJ’s Gas", "Rutter’s" and "Tacony–Palmyra
Bridge", appear about 1,100 times in PA. 768-1023 (combining accents and
Greek) was added after OH showed 88 such characters.

**Characters above U+FFFF.** The first version of this report said MapLibre
never requests glyph ranges above the Basic Multilingual Plane. That was
wrong: OSM in Ohio has trail names such as "🟢Silver Creek Loop 2.7mi"
(U+1F7E2, U+1F7E6; 10 distinct strings in 16 tiles at z14-15), and MapLibre GL
JS requested `128768-129023.pbf`, got a 404, logged a warning and drew the
emoji with a browser font (`before-60`, `before-61`). The hosted US base cut
also has Osage-script place names (U+104B0-104FF) in Oklahoma. Shipping
placeholders for all astral ranges is not practical, and MapLibre Native has
no browser font to fall back on, so the **finish step removes every character
above U+FFFF, plus variation selectors U+FE00-FE0F and the zero-width joiner,
from all strings in the tiles** (`build_config.json` "finish"). After it,
the same views make no glyph request outside the bundle (`60`, `61`), and
`tile_stats.py` reports no name characters outside the bundle's ranges in
PA, OH or the base pack.

Why placeholders and not just leave ranges out: in MapLibre a failed glyph
range request fails the symbol layout of that tile (GL JS 6 now falls back to
local rendering with a warning; Native was not tested). With empty PBFs those
characters are simply not drawn.

**MapLibre Native Android compatibility.** The styles validate with
`gl-style-validate` from `@maplibre/maplibre-gl-style-spec` 26.5.1 (exit 0).
`style/check_native_support.mjs` maps every property and expression operator
to the spec's `sdk-support` table. The highest Android version needed is 9.1.0
(`in` expression), nothing lacks Android support, and PMTiles sources need
11.7+. **Run on Native by the Android track, not by me:** their report
(`docs/experiments/05-android-skeleton.md`) shows this style bundle (73-layer
version) with `pa-261114-z0-15.pmtiles` and the OH pack on MapLibre Native
Android 13.7.1 in an emulator, at state, city and street level, day and night,
with `pmtiles://file://` packs and `file://` glyphs and sprites, and font
stacks with spaces. `asset://` URLs and a real phone are still untested.

## 5. Viewer (`viewer/`)

`python viewer/serve.py`, then open http://127.0.0.1:8765/. `serve.py` uses
only the standard library, answers GET and HEAD with HTTP Range support,
streams files in 1 MiB chunks (a full 467 MB GET peaked at 24 MiB in the
server process) and binds to 127.0.0.1 only. Tested: HEAD 200 with the right
Content-Length, Range 206, suffix range 206, out-of-range 416, and `../`,
`%2f`, `%2e%2e` and `%5c` traversal attempts all 404. The page lets you pick
packs from `data/map/packs/`, day or night, how several packs are combined,
open any `.pmtiles` from disk (pmtiles `FileSource`; the map then frames the
pack's header bounds, or uses the header centre when the bounds span more than
half the world), show tile boundaries, and click-to-inspect feature
attributes. Vendored: MapLibre GL JS 6.16.0 (BSD-3, ESM-only, needs WebGL 2)
and pmtiles 4.5.0 (BSD-3, bundles fflate, MIT): 1.73 MB, produced byte for
byte by `tools/fetch_map_vendor.py`.

**Rendering check.** `packbuilder/map/screenshots.py` starts the server and
drives the installed Edge headless through Playwright. WebGL 2 runs on
SwiftShader ("ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero))").
It waits for MapLibre's `idle` event and saves PNGs. It records MapLibre
errors, every console message, every request that failed or got HTTP 400 or
more, and every request not to 127.0.0.1, and exits with 1 if any view has
one. The first version only counted MapLibre errors, which is why the emoji
404 (a console warning) went unnoticed. Final run: 41 views (21 single-pack,
14 multi-pack, 6 checks), all idle, all with 0 of each
(`data/map/screenshots/screenshots.json`). The same tool run against the
unfinished OH pack (`--packs data/map/work/raw --prefix before-`) reports
the 404 and exits with 1.

Screenshots in `data/map/screenshots/` (not in git). I opened the ones marked
"looked at" after the fixes:

| File | What it shows / what I checked |
|---|---|
| 01-pa-state-day, 02-pa-state-night | z6.6. State clipped to its outline, Lake Erie, rivers, major roads, city labels |
| 03-erie-city-day | z11.5. Presque Isle and the lake shore, shields 79, 290, 5, street names |
| 04-pittsburgh-city-day, 06-pittsburgh-city-night | z11.5. Rivers, interstate shields (79, 279, 376, 579), place labels |
| 05-philadelphia-city-day | z11.5. Delaware River. NJ side empty (outside the PA pack), as expected |
| 07-erie-street-day, 08-erie-street-night (looked at; compare `before-07`, `before-08`) | z16.5. Street names now clearly readable at night, POI icons, parking dots, "Purrista Cat Café", "Stevo’s Pizza" |
| 09-philadelphia-street-day | z16.5. City Hall with the fallback icon, hotels, "Fogo de Chão" |
| 10-pittsburgh-street-night (looked at; compare `before-10`) | z16.5. One-way arrows on Fifth/Liberty, "McCormick & Schmick’s", "Fogo de Chão", lighter street names |
| 11-erie-interstates-shields-day | z11.8. I-79 Interstate-shaped shield vs generic shields |
| 12-harrisburg-city-day, 16-state-college-city-day, 17-scranton-city-night | other test cities |
| 13/14-pittsburgh-oakland-cafe-z17-day/night | "The Café Carnegie", "Fossil Fuels Café", no boxes |
| 15-philadelphia-oneway-z17-day | Chinatown one-way arrows, "Greenland Café" |
| 20/21-z15 vs z14 pack at z18 (Philadelphia), 22/23 at z16 (Erie) | z14 overzoom: geometry fine, POIs and house numbers missing |
| 30-43 | multi-pack seam views, see section 6; 39 looked at (base pack under PA+OH, no landcover tint) |
| 50-viewer-open-local-file-ohio (looked at) | the "open a .pmtiles file" path: the map frames Ohio from the fixed header bounds |
| 51-viewer-open-local-file-base (looked at) | the same with the US base pack: header centre (-96.5, 38.5) at zoom 3 shows the whole country |
| before-60/61 vs 60/61-emoji-trail-oh (looked at) | West Branch State Park, OH. Before: glyph 404 and the emoji drawn by the browser font as a box-like mark before "East Davis Loop". After: plain names, no request outside the bundle |
| 62/63-oneway-reverse-sharpsburg-z17 (62 looked at) | Sharpsburg, PA: arrows on Middle Street and 14th Street (`oneway=-1`), which had none before |

The panel is hidden (`ui=0`) in all views except 50 and 51.

## 6. Several states at once

Both approaches were prototyped with PA + OH.

**A. One source per pack, layers copied per pack** (`composeMultiPack` in
`viewer/compose_style.js`, used by the viewer when several packs are selected).
"Interleaved" puts the copies of each stock layer next to each other, so the
z-order is the same as with one source. "Grouped" draws all of PA's layers,
then all of OH's. The Android track implemented the interleaved form in
Kotlin and ran it with these two packs on the emulator.

**B. One merged file.** go-pmtiles 1.31.2 `merge` refuses:
`Failed to merge ... 2015 overlapping tiles, starting with 0 0 0. Inputs must
be disjoint` (re-checked on the finished packs). `merge_packs.py` copies the
376,156 tiles only one pack has and unions the layers of the 2,015 shared
tiles at the protobuf level (tag re-indexing, no geometry change; own small
MVT codec `mvt.py`). It drops 2,069 byte-identical duplicate features and
writes a clustered PMTiles v3 with the pmtiles Python writer: 9.3 s, 1.09 GiB,
930.0 MB (inputs 930.5 MB). Self-test on real tiles: a tile merged with itself
comes back the same size with the same feature counts. Merging two
neighbouring tiles keeps every road feature's attributes.

**What the seam looks like** (`seam_report.py`, screenshots 30 to 43):

- **Shared tiles:** 1 per zoom at z0-2, 2 at z3-7, then 4, 6, 13, 22, 41, 110,
  446 and 1,360 at z8-15.
- **The overlap band:** Geofabrik's `.poly` outlines are buffered, and the PA
  and OH outlines overlap by 0.4 to 1.0 km along the state line (measured at
  five latitudes). Both packs contain that band.
- **Duplicates in the band:** in the z15 shared tiles, 565 road names and 52
  POI names (e.g. "Morgan Oil", "Tic Toc Food Mart") occur in both packs with
  different geometry, so neither approach removes them.
- **Approach A:** "Ohio Street" was labelled twice at z15 (screenshot 35); the
  merged file showed it once (36). "Grouped" order is worse: OH's opaque fills
  cut PA-side labels ("West H|ill", screenshot 33).
- **Approach B and interleaved A** looked otherwise identical at z8, z10, z12
  and z15 (30 vs 31, 32 vs 34, 42 vs 43). Lines drawn twice in the band are
  invisible.
- **Data, not seam:** Ohio simply has much more OSM landuse mapped than
  Pennsylvania. That shows as a density step at the border at z10-12.
- **Low zooms:** each clipped pack only holds its own state, so at z5 two
  packs show two state-shaped islands (38). With a z0-7 base pack under them,
  the whole region shows (39). With the base pack's layers capped at z8 and
  the state packs' layers starting at z8, the state packs take over cleanly
  (40).
- **Pack bounds:** the PMTiles header bounds were the OSM extract's box (PA
  started at -81.38, 38.92 instead of -80.52, 39.66). The finish step now
  writes the clip polygon's box: PA -80.523, 39.664, -74.688, 42.516; OH
  -84.822, 38.401, -80.505, 42.336. These boxes are what an app can use to
  pick the packs that touch the viewport.

**The demo base pack.** `base-us-20261114-z0-7.pmtiles` (17.8 MB, 1,047
tiles) is Protomaps' hosted 2026-11-14 build cut to Geofabrik's `us.poly` at
z0-7, then run through `finish_pack.py --drop-layers landcover` (removing the
CC-BY layer from 531 tiles and the Osage characters from 21 strings) with
bounds from the US outline and a centre set to the lower 48. The raw cut was
19.4 MB, so landcover was about 8 % of it. It is a demo input, not a pipeline
product: it was built by Protomaps, not by us.

**Recommendation: A (one source per pack, interleaved) + a shared base pack.**

- Each state pack stays a separate file. It can be downloaded, verified,
  updated and deleted on its own, and it stays versioned together with that
  state's routing tiles and search index, as the project requires. A merged
  file has to be rewritten (all GBs of it) whenever any state changes. It
  would also mix OSM snapshots from several states in one file.
- The cost of A is 74 layers per pack in view. The app should only add the
  packs whose header bounds touch the viewport, usually one to three.
- Also on the cost side: duplicate labels in the overlap band. The fix is
  upstream: clip each pack at the true state line instead of Geofabrik's
  buffered `.poly`. Candidate sources are the OSM admin boundary relation
  (ODbL) or Census cartographic boundaries (public domain). Then the band
  disappears. Not done here.
- **Low zooms:** build one US base pack at z0-7 with this same pipeline
  (`build_pack.py` on `us-latest.osm.pbf` with `--maxzoom 7`; not run here, it
  is a 12.3 GB input). Ship it with every install: at most about 18 MB, measured
  on the landcover-free hosted cut of the same schema version. Draw it below
  z8, and draw state packs from z8. State packs can keep their own z0-7 tiles
  (0.2 to 0.4 MB) or be built with `--minzoom 8`.
- `merge_packs.py` stays useful where one file is simpler: the Windows browser,
  a comma device covering a few states, or server-side region bundles.

## 7. Whole-US estimates (estimates, not measured builds)

| Basis | z0-15 | z0-14 |
|---|---|---|
| Our finished PA+OH packs per MB of OSM PBF: (463.8 + 466.7) / (350.4 + 327.7) = 1.372 MB per MB, times the Geofabrik US extract (12,268 MB, HEAD request) | **16.8 GB** | 16.8 × 220.3/463.8 = **8.0 GB** |
| Protomaps' hosted 20261114 build, `pmtiles extract --dry-run --region=us.geojson` (Geofabrik `us.poly`): "archive size of 17 GB", 14.4 M tile entries. Whole tiles, includes landcover. Our PA is 0.96 × the hosted PA cut | **17 GB** (about 16.4 GB scaled by 0.96) | - |
| US base pack z0-7, hosted cut without landcover | 17.8 MB | |

**Build time, per-state runs at 4 threads (estimate).** Split PA's run into a
part that scales with the PBF (osm_pass1, osm_pass2, sort, archive: 398 s in
the first build, 444 s in the rebuild, for 350.4 MB) and a fixed part paid by
every run (startup, Natural Earth, water and land polygons: about 3.5 to 4
min). US: 12,268 / 350.4 × 400 to 445 s = 3.9 to 4.3 h, plus 51 runs (50
states and DC) × about 220 s = 3.1 h, plus the finish step at about 0.11 to
0.16 s per MB of output × 16.8 GB = 0.5 to 0.75 h. **About 7.5 to 8 hours.**
The first version double-counted the Natural Earth time; this one counts it
once per run.

**RAM (not measured beyond these two states).** PA and OH (328 to 350 MB
PBFs) peaked at 5.2 to 5.4 GiB working set with a 4 GB heap. Heap in use
after garbage collection peaked at 2.2G (PA) and 3.1G (OH) as Planetiler's
log prints it, so OH already used three quarters of the 4 GB heap. Planetiler's README
recommends a heap of about 0.5 × the PBF size and leaves the rest of RAM for
its memory-mapped files. The largest states are California (1,336 MB PBF)
and Texas (728 MB; Geofabrik HEAD requests today), so by that rule a 4 GB heap
is enough, but the memory-mapped node map and feature storage grow with the
input and will raise the working set. Whether CA stays under 6 GB is unknown
until one large state is built.

## 8. Failures, dead ends, things not verified

- **Downloads:** the first single-stream download of the water polygons ended
  silently at 72 of 933 MB, and the script then accepted it. `fetch_sources.py`
  now checks the size against Content-Length and resumes. The
  osmdata.openstreetmap.de server gave about 0.3 to 1 MB/s per connection, so
  files over 64 MB now use 4 parallel range requests.
- **`pmtiles merge`** cannot merge state packs (overlapping tiles). That is a
  design limit, not a bug.
- **Emoji glyph requests** (found by the break review, fixed): see section 4.
  The first screenshot check missed it because it ignored console warnings
  and HTTP errors.
- **Not verified on a real device:**
  - MapLibre Native Android on a phone; `asset://` glyph and sprite URLs
    (the Android track's emulator run used `file://`).
  - Native's behaviour with the empty glyph PBFs is only covered by that
    emulator run, which drew the PA and OH packs without visible problems.
  - Whether Native Android draws Hangul/CJK with a local font by default.
- **Not run on Linux aarch64 (comma):** reading the packs from plain Python.
  The same code (pmtiles 3.8.1 pure Python + `mvt.py`, standard library) reads
  them on Windows Python 3.12.
- **Not built:**
  - the US base pack from our own pipeline (12.3 GB input);
  - a z14 pack with the clamp patched;
  - Shortbread or tilemaker alternatives;
  - MapLibre Tiles (MLT), which Planetiler 0.10 can apparently write (it has
    `tile_format` and `mlt_*` options) and which might be smaller. Untested.
  - clipping at the true state line.
- **Shields** are the Protomaps monochrome designs. **No POI icons** exist for
  fuel, parking or hotels (dots for now).
- One-way arrow directions were not checked way by way against OSM.
- RAM of a large state (CA, TX) is unmeasured (section 7).

## 9. Versions and licences

| Item | Version | Licence (checked at) | Ships to device |
|---|---|---|---|
| OpenStreetMap data (Geofabrik 2026-11-14) | 261114 | ODbL 1.0 | yes (tiles) |
| OSM water/land polygons (osmdata.openstreetmap.de, 2026-11-14) | - (not pinned; SHA-256 in the manifest) | ODbL 1.0 (README of protomaps/basemaps LICENSE_DATA.md, osmdata site) | yes (tiles) |
| Natural Earth vector GeoPackage | file dated 2022-05-14 (SHA-256 in the sources manifest) | public domain (naturalearthdata.com terms, quoted in protomaps LICENSE_DATA.md) | yes (tiles) |
| Wikidata QRank | 2026-11-10 | CC0 | no (ranking input) |
| pgf-encoding | 2025-04-14 | MIT (GitHub LICENSE) | no |
| Protomaps hosted build 20261114 (demo base pack only, landcover removed) | tiles 4.15.2 | ODbL Produced Work (OSM); landcover layer would be CC-BY 4.0, removed | demo only |
| Planetiler | 0.10.2 | Apache-2.0 (GitHub LICENSE) | no |
| Protomaps basemap profile | tiles 4.15.2, commit 42ffaaa4 | BSD-3-Clause (repo LICENSE.md); schema from Tilezen, MIT | no (its output does) |
| @protomaps/basemaps (style generator) | 5.7.2 | BSD-3-Clause code, CC0 design (repo LICENSE.md, npm) | yes (style JSON) |
| Noto Sans glyph PBFs (basemaps-assets 028c18f) | - | SIL OFL 1.1 (fonts/OFL.txt) | yes |
| Protomaps v4 sprites (basemaps-assets 028c18f) | - | derived from tangrams/icons, MIT (repo README, tangrams LICENSE.md) | yes |
| MapLibre GL JS | 6.16.0 | BSD-3-Clause (LICENSE.txt in package) | yes (viewer) |
| pmtiles (JS) | 4.5.0 | BSD-3-Clause (PMTiles LICENSE); bundles fflate MIT | yes (viewer) |
| go-pmtiles | 1.31.2 | BSD-3-Clause (GitHub LICENSE) | no |
| Apache Maven | 3.9.16 | Apache-2.0 | no |
| Java (preinstalled Temurin) | 25.0.3 | GPL-2.0 with Classpath Exception | no |
| pmtiles (Python) | 3.8.1 | BSD-3-Clause (PyPI) | no (comma could use it) |
| psutil | 7.2.2 | BSD-3-Clause | no |
| playwright (Python) + greenlet, pyee, typing_extensions | 1.64.1 / 3.5.6 / 14.0.0 / 4.16.0 | Apache-2.0 / MIT / MIT / PSF-2.0 | no |
| @maplibre/maplibre-gl-style-spec (validation) | 26.5.1 | ISC (npm) | no |
| Microsoft Edge (preinstalled, headless) | 157 | proprietary, test only | no |

No dependency was added in the review round. Nothing that ships is GPL, AGPL
or LGPL. Ideas were taken from reading Planetiler's and Protomaps'
permissively licensed sources: `Clip.java`, `Pois.java`, `Roads.java`,
`Basemap.java`, Planetiler's `FeatureMerge.java` and `LoopLineMerger.java`
(for one-way direction), and the pmtiles Python reader and writer. `mvt.py`
was written from the MVT specification.

## 10. How to rerun

From the repo root on Windows. Every step skips finished work; `--force`
redoes it.

```powershell
# shared inputs (orchestrator's script, not this track's): data/osm/*.osm.pbf, *.md5, *.poly
pwsh tools/fetch_osm_extracts.ps1 -States pennsylvania,ohio -Date 261114
py -3.12 -m venv .venv/map
.venv/map/Scripts/python -m pip install -r packbuilder/map/requirements.txt
python tools/fetch_map_tools.py                     # Maven + go-pmtiles into tools/bin (SHA-256 pinned)
python tools/fetch_map_vendor.py                    # viewer/vendor (already in git)
.venv/map/Scripts/python packbuilder/map/build_profile.py     # profile jar -> data/map/bin
.venv/map/Scripts/python packbuilder/map/fetch_sources.py     # ~2.5 GB -> data/map/work/data/sources
.venv/map/Scripts/python packbuilder/map/build_pack.py pa     # -> data/map/work/raw/..., then data/map/packs/pa-261114-z0-15.pmtiles (+ .build.json)
.venv/map/Scripts/python packbuilder/map/build_pack.py pa --maxzoom 14
.venv/map/Scripts/python packbuilder/map/build_pack.py oh
tools/bin/pmtiles/pmtiles.exe verify data/map/packs/pa-261114-z0-15.pmtiles
.venv/map/Scripts/python packbuilder/map/tile_stats.py data/map/packs/pa-261114-z0-15.pmtiles --deep 14 15   # also the glyph-range check
.venv/map/Scripts/python packbuilder/map/tile_stats.py data/map/packs/oh-261114-z0-15.pmtiles --deep 14 15
.venv/map/Scripts/python packbuilder/map/tile_stats.py data/map/packs/pa-261114-z0-14.pmtiles --deep 14
.venv/map/Scripts/python packbuilder/map/merge_packs.py data/map/packs/pa-oh-261114-z0-15-merged.pmtiles data/map/packs/pa-261114-z0-15.pmtiles data/map/packs/oh-261114-z0-15.pmtiles
.venv/map/Scripts/python packbuilder/map/seam_report.py data/map/packs/pa-261114-z0-15.pmtiles data/map/packs/oh-261114-z0-15.pmtiles
# style bundle (already in git)
.venv/map/Scripts/python packbuilder/map/style/fetch_style_inputs.py
node packbuilder/map/style/generate_style.mjs       # prints the label contrast ratios; stops if one is too low
npm install --prefix data/map/npm @maplibre/maplibre-gl-style-spec@26.5.1
node data/map/npm/node_modules/@maplibre/maplibre-gl-style-spec/dist/gl-style-validate.mjs packbuilder/map/style-bundle/day.json packbuilder/map/style-bundle/night.json
node packbuilder/map/style/check_native_support.mjs packbuilder/map/style-bundle/day.json
# demo base pack for the low zooms (from Protomaps' hosted build, then landcover removed)
tools/bin/pmtiles/pmtiles.exe extract https://build.protomaps.com/20261114.pmtiles data/map/packs/hosted-us-20261114-z0-7.pmtiles --region=data/map/work/clip/us.geojson --maxzoom=7
#   (us.geojson: packbuilder/map/poly2geojson.py on https://download.geofabrik.de/north-america/us.poly)
.venv/map/Scripts/python packbuilder/map/finish_pack.py data/map/packs/hosted-us-20261114-z0-7.pmtiles data/map/packs/base-us-20261114-z0-7.pmtiles --clip data/map/work/clip/us.geojson --drop-layers landcover --center=-96.5,38.5,3
# rendering checks (each exits 1 on any error, console message, failed or external request)
.venv/map/Scripts/python packbuilder/map/screenshots.py
.venv/map/Scripts/python packbuilder/map/screenshots.py --views packbuilder/map/screenshot_views_multi.json
.venv/map/Scripts/python packbuilder/map/screenshots.py --views packbuilder/map/screenshot_views_checks.json
#   the emoji problem before the finish step: --packs data/map/work/raw --prefix before- --only 60-emoji-trail-oh-z16-day 61-emoji-trail-oh-z17-night
python viewer/serve.py   # interactive: http://127.0.0.1:8765/
```

The old build record of the 5 GB PA run, its log and the pre-review stats
files are kept in `data/map/work/old-records/`. The rerun log of the review
round is `data/map/logs/rerun_after_review.log`.

## 11. Outside this track

- `README.md` (not mine): the layout table could list the new top-level
  `viewer/` folder.
- **Android track:** the packs it copied (`tools/fetch_android_map_pack.py`)
  are from before the finish step: their header bounds are the extract's box
  and the OH pack still has the 10 emoji trail names. Re-copying the packs and
  the regenerated style bundle (74 layers, new label colours, reverse one-way
  arrows, linked credit) picks up the fixes. Their decision on where the style
  lives (in each pack or in the APK) is still open.
- `tools/fetch_osm_extracts.ps1` is the orchestrator's script; the map README
  and this report now name it as the first step.
- Nothing was installed system-wide and WSL was not used. Outside the repo,
  only the user-level download caches were written: pip's and npm's in
  `%LOCALAPPDATA%`. The pmtiles Python writer used the system temp folder for
  its temporary tile file (up to the pack size, deleted when done).

## 12. Review and fixes

Two reviews looked at the first version: "reproduce" re-ran the work, "break"
attacked it. Both rated it sound with fixes. I checked each blocker and major
finding myself before changing anything. There were no blockers.

| # | Finding (review) | Severity | What happened |
|---|---|---|---|
| B1 | Emoji (above U+FFFF) in names make MapLibre request glyph ranges the bundle lacks; the report said this never happens (break) | major | **Confirmed and fixed.** Reproduced: the unfinished OH pack makes Edge request `Noto Sans Regular/128768-129023.pbf` (404) at West Branch State Park (`before-60/61`). A scan found 10 such strings in 16 OH tiles, none in PA, and Osage letters in the hosted US base cut. New `finish_pack.py` removes characters above U+FFFF, variation selectors and the zero-width joiner from all tile strings (rule list in `build_config.json`); OH: 46 strings in 16 tiles changed. After: 0 failed requests in the same views. New checks: `screenshots.py` fails on any 4xx/failed request or console message; `tile_stats.py` warns when a name character has no glyph file (it warns on the raw OH pack, is clean on all finished packs). Report text corrected. |
| B2 | PMTiles header bounds are the OSM extract's box, not the clipped state (break) | major | **Confirmed and fixed.** PA header said -81.378, 38.921 against a clip box starting at -80.523, 39.664. The finish step now writes the clip polygon's box and centre and checks them; `pmtiles verify` passes on all packs. The viewer's open-file path frames the header bounds (screenshot 50) or, for a world-wide box, uses the header centre (51; the US base pack's centre is set to the lower 48). |
| R1 / B5 | README said a 5 GB heap; config says 4 GB; PA z0-15 numbers came from the 5 GB run (both) | minor | **Fixed.** README says 4 GB. PA z0-15 rebuilt with 4 GB: 652.1 s, 5.36 GiB (5.76 GB), inside the budget. The 5 GB run stays in the table, labelled. |
| R2 | Rerun instructions never fetch the OSM extracts; `build_pack.py` waits up to 120 min for missing files (reproduce) | minor | **Fixed.** README and section 10 start with `tools/fetch_osm_extracts.ps1`. `build_pack.py` now stops at once with that command in the message; `--wait-minutes N` keeps the old waiting behaviour. Tested on a missing file (0.5 s). |
| R3 | "17.8 M features" is Planetiler's pre-merge counter; the pack holds 8.39 M (reproduce) | minor | **Fixed.** Report gives 8.39 M (6.27 M at z15) for PA and 8.87 M for OH, and explains the other number. |
| R4 / B5 | Peak RAM labelled GB but is GiB (both) | minor | **Fixed.** `measure.py` and `merge_packs.py` now write `peak_rss_gib` / `peak_memory_gib` plus bytes; the report uses GiB with decimal GB next to the budget comparisons. |
| R5 / B6 | "All versions are pinned", but five data inputs are floating downloads (both) | minor | **Fixed in the docs, left open in substance.** README and section 1 now say these inputs are not pinned and that the manifest is the record. They cannot be pinned by hash because the publishers serve only the latest file. Archiving them with each release is listed as a decision. The unused landcover file is no longer downloaded. |
| R6 | Whole-US build time double-counts the Natural Earth cost (reproduce) | minor | **Fixed.** Section 7 now scales only the OSM stages and adds the fixed cost once per run, plus the finish step: about 7.5 to 8 h. |
| R7 | z14 tiles of the z0-14 pack are 2.7 % bigger than in the z0-15 pack, unexplained (reproduce) | minor | **Explained.** Planetiler keeps smaller features and simplifies less at a build's max zoom (`min_feature_size_at_max_zoom=0.0625`, `simplify_tolerance_at_max_zoom=0.0625` vs 1.0 / 0.1 below it, from the build log). Measured on 3,205 tiles: polygon layers +6 to 7 % bytes, landuse +8.6 % features, POIs and places identical. |
| R8 | MapLibre Native Android 13.x only checked statically (reproduce) | minor | **Partly resolved, by the Android track, not by me.** Their emulator run drew this bundle with the PA and OH packs on MapLibre Native Android 13.7.1 (`pmtiles://file://`, `file://` glyphs). Open: a real phone, `asset://` URLs, and their run predates the finish step and the new style. |
| B3 | Night road and address labels have low contrast (break) | minor | **Fixed.** Label colours are overridden in `style_config.json` and `generate_style.mjs` enforces a WCAG contrast of at least 4.5:1 against the halo (3:1 for state names): night 5.9 to 10.4:1 (state names 3.8:1), day 5.1 to 5.6:1. Looked at `08` against `before-08` and at `10`. |
| B4 | The recommended base pack (hosted cut) contains CC-BY landcover; the credit is plain text, not a link (break) | minor | **Confirmed and fixed.** The landcover layer was in 531 of 1,047 tiles. `finish_pack.py --drop-layers landcover` made `base-us-20261114-z0-7.pmtiles` (17.8 MB, was 19.4 MB), now used in the multi-pack views. The report calls it a demo input and gives 17.8 MB as the size bound. The style credit is now a link to openstreetmap.org/copyright. |
| B7 | One-way arrows only for `oneway=yes` (break) | minor | **Fixed.** New layer `roads_oneway_reverse` for the values in `oneway_reverse_values` (`-1`), arrow turned by 180 degrees (screenshots 62/63). `alternating`/`reversible` deliberately get no arrow. |
| B8 | `serve.py` answers HEAD with 404 and reads whole files into memory (break) | minor | **Fixed.** HEAD uses the same resolver; bodies stream in 1 MiB chunks. Tested: HEAD 200, Range 206, suffix 206, 416, traversal 404, full 467 MB GET at 24 MiB peak. |
| B9 | No RAM estimate for the whole US / large states (break) | minor | **Addressed as an estimate, not measured.** Section 7 gives the basis (Planetiler's 0.5 × PBF heap rule, CA 1,336 MB and TX 728 MB PBFs, mmap storage) and says it stays unknown until one large state is built. |

Other changes in this round: `fetch_sources.py` skips the landcover file
unless landcover is enabled; `screenshots.py` gained `--packs`, `--prefix`
and an open-file view type; the viewer README describes the server.

Re-measured after the fixes: PA z0-15 build (rebuilt), finish step on all
three packs, per-zoom sizes and feature counts (all packs), glyph coverage,
merge (time, RAM, size), go-pmtiles merge refusal, seam report (unchanged:
same shared tiles and duplicate counts), style bundle size, style validation
and the Native support check, and all 41 screenshot views. Unchanged by the
fixes and not re-measured: POI and attribute counts (tile contents are
identical except the 16 OH tiles), the hosted-build dry-run sizes, and the US
PBF size.
