# Experiment 08: Android app shell (style in the app, US overview, pack manager, settings, puck)

Date: 2026-11-15. Machine: Windows 11, 12 logical CPUs, 32 GB RAM. **Other engineers' jobs ran
during every measurement below**: a Planetiler/Java job of 4.5-4.7 GB working set, Gradle builds,
and, by the end, two more Android emulators (ports 5584 and 5610, not mine). The CPU load
read 62-100 % (`Win32_Processor.LoadPercentage`) around the device measurements. Every emulator
number is indicative only and says nothing reliable about a phone. Gradle ran with a 2 GB heap
and 4 workers (`android/gradle.properties`).

A phone was attached over USB during this work. It was never used: every adb call was pinned to
the emulator (`ANDROID_SERIAL=emulator-5554`).

**After two reviews, a second engineer checked and fixed the findings the same evening (section
16).** Sections 1-15 are the first round's report, corrected where the reviews showed a number or
a command to be wrong; the corrections point to section 16.

## Result in short

- **The style ships in the app.** `android/scripts/sync_assets.py` copies
  `packbuilder/map/style-bundle/` into `android/app/src/main/assets/style/` and records what it
  copied (`bundle-version.json`: content SHA-256, source commit, uncommitted-changes flag). Copied
  twice, as asked: at the start (`c1b3c3844e9e`, commit 33f5f2d) and before the final checks
  (`876da2e3b0bd`, commit 33f5f2d **plus the map track's uncommitted changes**: new sprites with
  US-route and Pennsylvania keystone shields, parking and bank icons). The new bundle drew
  correctly on the emulator. The fix round copied it a third time (`007a6f98c1f9`, POI layers
  changed by the map track at 16:41); that copy is in the APK now.
- **Packs carry data only: pack format version 2**, written down in `docs/pack-format.md`:
  folder layout, a `.tar` transport file with `manifest.json` first, one `osm_snapshot` per pack,
  a `format`/`format_version` per part, sizes and SHA-256 for every part, bounds; `routing`
  (Valhalla tile tar) and `search` (SQLite) are defined and carried but unused. The document's
  Python reader (standard library only) read and verified the real PA (1.25 GB), OH (1.11 GB)
  and Erie test packs. `tools/fetch_android_map_pack.py` now builds these packs from the three
  pack builders' outputs and refuses mixed OSM snapshots.
- **Whole-US overview from the APK: `pmtiles://asset://` works in MapLibre Native 13.7.1** on the
  emulator (17.8 MB file stored uncompressed). No copy to app storage is needed. It shows when no
  state is installed, and under and around installed states (rules in
  `assets/rules/map-compose.json`). **Release APK: 50.87 → 69.94 MB (+19.07 MB:** overview
  17.77, style 0.86, code 0.27, other 0.02). The arm64 native share is unchanged at 13.03 MB.
- **A performance trap was found and fixed.** MapLibre Native is slow with overzoomed z0-7 tiles.
  With the overview drawn at every zoom, panning at Pittsburgh z14 fell to 1.8-2.5 frames/s
  (602-764 ms CPU per frame); the overview alone at z14 cost 247 ms per frame. The overview now
  stops at z10 (`overview_max_zoom`): Pittsburgh z14 is back to 10.3 frames/s (103 ms); an area
  with no state pack at z14 (Rochester NY) pans at 56-58 frames/s (2.4-3.6 ms; fix round,
  section 16). With no state installed the camera now stops just below z10, so the map is never
  blank.
- **Pack manager** (States screen): installed packs with name, OSM date, size, parts and
  whether they were checked; remove with a confirmation; add from a `.tar` (system file picker)
  or a folder (tree picker). An install copies with progress, hashes while copying, checks, then
  renames into place. On the emulator: the 1.25 GB PA pack installed in about 14.6 s and OH
  (1.11 GB) in about 11.2 s. A damaged archive was refused with "map.pmtiles is damaged: its
  SHA-256 does not match the manifest" and the installed copy stayed. A cancel at 333 MB and a
  failure left nothing behind. No INTERNET permission.
- **Settings** (persisted JSON, `:core:settings`): Map style Automatic/Day/Night; Frame rate
  Screen/60/30 through `MapView.setMaximumFps` plus `Surface.setFrameRate` on the map's surface
  (Android 11+) or `preferredDisplayModeId` (Android 8-10); Guidance sound (stored only); Units.
  **Above-60 rendering cannot be confirmed on the emulator:** its only display mode is 60 Hz.
- **Location and puck**: permission asked only when the my-location button is tapped,
  foreground only, through Android's LocationManager. The puck follows
  `docs/design/position-markers.html` (route-blue dot, white ring, halo, a 70-degree cone that
  fades with distance) and turns grey when the fix is older than 10 s or worse than 100 m (rules
  in `assets/rules/position.json`). On the emulator a GPX drive down Peach St, Erie (10.91 km,
  245 fixes) moved the puck along the street: 217 logged positions, all within 0.7 m of the
  track, never moving backwards, ending at I-90 exit 24. The compass cone turned with the
  emulated magnetometer (magnetic east read as 80 degrees true, south as 171 degrees: Erie's
  declination of about 9-10 degrees west).
- **57 unit-test cases** in three UI-free modules pass (`:core:packs`, `:core:settings`,
  `:core:location`; the skeleton's 32 cases were rewritten or replaced for format 2). **64**
  after the fix round.
- Merged manifest: `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION` and AndroidX's own
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. **No INTERNET, no background location, no
  foreground service.** The emulator was stopped at the end.

## 1. What was built

```
android/
  app/                              Android only: screens and adapters
    src/main/assets/style/          the map style, copied by scripts/sync_assets.py (committed, 781 files, 1.65 MB)
    src/main/assets/rules/          map-compose.json, position.json: rules as data (committed)
    assets-local/basemap/           us-base.pmtiles + us-base.json: the overview (NOT committed, .gitignore)
    src/main/kotlin/io/github/spysyweeb/beamline/app/
      AppGraph.kt                   one per process: wires the core objects to Android (files, assets, sensors)
      MainActivity.kt               screens (map / states / settings), permission request, frame-rate request
      map/   MapScreen, MapViewModel, PackMap (MapLibre view, puck layers, follow), PuckImages
      packs/ PackManagerScreen, PickedSources (system file picker -> PackSource)
      settings/ SettingsScreen
      location/ AndroidPositionSource (LocationManager), RotationVectorHeadingSource
      ui/    Theme (colours from the sketches), Icons (drawn here: crosshair, gear, packs, back, add)
  core/packs/     format 2: PackManifest, Readers, PackScanner (+PartHeaders), PackInstaller, TarReader, PackLibrary, StyleComposer
  core/settings/  Settings, SettingsStore, FrameRates
  core/location/  Fix/Heading/PositionSource/HeadingSource, PositionRules, PuckTracker (+FixFilter), Compass, HeadingSmoother, SimulatedPositionSource, Gpx
  scripts/        sync_assets.py, device_ui.py, make_route_gpx.py, geo_route.py, read_pack.py (+ the skeleton's push_pack.py, measure.py, pack_variants.py)
docs/pack-format.md
tools/fetch_android_map_pack.py   rewritten for format 2 (map + routing + search, folder + .tar)
```

Removed: `android/sample-pack/` (hand-written stand-in styles) and
`tools/fetch_android_sample_pack.py`. They made format-1 packs from a Protomaps download for the
time before the map track existed; the style now lives in the app.

Rule kept from the skeleton: everything that decides something lives in a plain Kotlin/JVM
module with unit tests; Compose code only displays state. `:core:*` modules have no Android
dependency at all (they cannot reach UI code by accident). They also avoid Java APIs newer than
Android 8 (e.g. `InputStream.readNBytes` is Android 13+, so it is not used).

## 2. The style in the app (task 1)

`python android/scripts/sync_assets.py style` copies `day.json`, `night.json`, `glyphs/`,
`sprites/` and `LICENSES.md`, checks the copy against the source (same SHA-256 over every
path and its bytes), and writes:

```json
{"source": "packbuilder/map/style-bundle", "content_sha256": "876da2e3b0bd…", "files": 781, "bytes": 1646377,
 "git_commit": "33f5f2d7…", "uncommitted_changes": true, "copied_utc": "2026-11-15T22:50:09Z"}
```

`sync_assets.py check` reports whether the copy still matches the bundle. Settings > About shows
"Map style 007a6f98c1f9, from commit 33f5f2d7" (the fix round's copy).

| Copy | When (UTC) | content SHA-256 | Source state |
|---|---|---|---|
| first | 22:06 | `c1b3c3844e9e…` (1.51 MB) | commit 33f5f2d, clean |
| second (first round's final) | 22:50 | `876da2e3b0bd…` (1.65 MB) | 33f5f2d + the map track's uncommitted edits of 15:41 local (new sprites and shields, 73 layers) |
| third (fix round, now in the APK) | 00:09 (16 Nov) | `007a6f98c1f9…` (1.66 MB) | 33f5f2d + the map track's uncommitted edits of 16:41 local: only `day.json`/`night.json` changed (the `pois` and `pois_driving` layers: a darker park-label green, filter and icon choices); same 73 layers, same sprites |

The app reads the templates from `asset://style/...`, and the composed style points glyphs and
sprites at `asset://style/glyphs/{fontstack}/{range}.pbf` and `asset://style/sprites/light|dark`.
Font names with spaces ("Noto Sans Regular") load fine through `asset://`, which the skeleton
had only tested with `file://`. Labels, shields and POI icons draw (screenshots 02, 08, 37).

**For the orchestrator:** the APK's style is a snapshot of uncommitted map-track work. When that
work is committed, `sync_assets.py check` should report "in sync". If the commit differs, run
`sync_assets.py style` again and commit the result with it.

## 3. Pack format version 2 (task 1, `docs/pack-format.md`)

The full description is in the document. In short:

- **Folder** `packs/<pack_id>/` with `manifest.json` and part files at the top (`map.pmtiles`,
  `routing.tar`, `search.sqlite`); **archive** = the same as one uncompressed ustar `.tar`,
  `manifest.json` first, so a reader can refuse before copying gigabytes.
- `manifest_version` 2; `osm_snapshot` once per pack; `bounds`; `parts.{map,routing,search,…}`,
  each with `file`, `format`, `format_version`, `bytes`, `sha256`, plus part fields (map:
  `schema`, `schema_version`, zooms; routing: `valhalla_version`, `graph_build`).
- What the app reads is in one place (`Readers`): manifest 2; map `pmtiles` v3 with
  `protomaps-basemap` 4.x (the schema the built-in style draws, from `map-compose.json`). Parts the
  app does not use yet are copied and checksummed but not judged. An unknown part
  (`future_part` in the test fixture) is kept.
- **Checksums once, at install** (hashed while copying); afterwards only sizes and header magic
  (PMTiles header and section bounds, tar `ustar`, `SQLite format 3\0`) on every resume. The
  installer writes `install-record.json`; packs pushed with adb lack it and are shown as
  "copied outside the app, not checked".

`tools/fetch_android_map_pack.py` assembles packs (standard library only):

| Pack | Parts | Size | Wall time | Peak working set |
|---|---|---|---|---|
| `us-pa` | map 463.8 MB + routing 634.4 MB + search 154.7 MB | 1,252.9 MB (folder and `.tar`) | 6.6 s | 21 MiB |
| `us-oh` | map 466.7 MB + routing 643.7 MB (no OH search index exists yet) | 1,110.4 MB | 6.4 s | 20 MiB |
| `test-erie` | map 8.5 MB (the skeleton's Erie cut) | 8.5 MB | 0.4 s | - |

Times are with the inputs in the file cache (just built by the other tracks). Peak working set
was read with `GetProcessMemoryInfo` after the process exited (a small wrapper, not committed).
The tool reads each part's OSM date (Planetiler's replication time, the routing manifest's
`snapshot`, the search file's `meta.osm_timestamp`): all three said 2026-11-14. It also checks
the routing tar's SHA-256 against the routing builder's own manifest.

**The document's Python reader, run on the real packs** (`android/scripts/read_pack.py`, an
exact copy of the snippet, run with `python -I`):

```
us-pa: Pennsylvania, OSM 2026-11-14, parts map, routing, search
problems: none
  map.pmtiles: 463787526 bytes, sha256 ok
  routing.tar: 634429440 bytes, sha256 ok
  search.sqlite: 154705920 bytes, sha256 ok
checksums ok                                    (archive, 5.2 s, 19 MiB)
us-oh: Ohio, OSM 2026-11-14, parts map, routing ... checksums ok   (folder, 4.1 s)
test-erie: Erie test cut, OSM 2026-11-14, parts map ... checksums ok
```

Kotlin reads what Python writes: `TarReader` is tested on archives made by Python's `tarfile`
in both ustar and PAX form (long names, non-ASCII names, folders, empty files;
`core/packs/src/test/resources/python-*.tar`).

## 4. The US overview map (task 2)

- Input: `data/map/packs/base-us-20261114-z0-7.pmtiles` (17.77 MB, the map track's
  landcover-free cut of Protomaps' hosted build; OSM 2026-11-14, schema 4.15.2). Copied by
  `sync_assets.py basemap` into `app/assets-local/basemap/` with a small JSON record. That
  folder is git-ignored and added to the build as an assets folder. The build stops without it
  unless `-Pbeamline.allowNoOverview=true`; the app then works without the overview.
- `androidResources.noCompress += "pmtiles"`. The APK lists the file as `Stored`, 17,772,824
  bytes. MapLibre 13.7.1 reads asset byte ranges with `AAsset_seek64` (changelog entry and PR
  #4602, read on GitHub). A deflated asset would have to be inflated from its start on every seek.
- **Verified on the emulator:** the overview drew at first start with no pack (screenshots 02/03),
  around and under state packs (08, 15-19), by day and night. No copy-to-storage fallback was
  needed, so none was written.

**How the overview and state packs are put together** (`StyleComposer`, rules in
`assets/rules/map-compose.json`):

1. All overview layers are drawn first, then the state packs, interleaved layer by layer as in
   the skeleton.
2. With a state installed, state layers start at **z8**, and the overview's label layers
   (symbol, circle) stop at **z8**. Below z8 there is one map, the overview, with no doubled
   labels. From z8 the states' opaque land and water cover the overview inside a state.
3. The overview is **never drawn above z10** (`overview_max_zoom`). Around the states it shows
   land, water and main roads without labels up to z10 (screenshot 18: New York beside
   Pennsylvania at z10). Above z10 an area without a pack shows the plain background.
4. Camera: the lower 48 when no state is installed. After the first state is installed, the
   camera moves to it; after the last one is removed, back to the lower 48 (fix round).
5. (Fix round.) With no state installed the camera cannot zoom past z9.99: MapLibre hides a layer
   at every zoom equal to or above its `maxzoom`, so z10.00 itself was blank. The my-location
   button and following the puck also stop at z9.99 where the position lies outside every
   installed state's box (`ComposedMap.maxZoom`, `zoomFor`).

Why rule 3 (`measure.py pan`, release APK, Frame rate setting "Screen rate" = max 60 fps, PA+OH
installed, puck on, other jobs running). The views are `--lat 40.44 --lon -80.0 --zoom 14`
(Pittsburgh), `--lat 43.15 --lon -77.6 --zoom 14` (Rochester NY) and `--lat 42.0 --lon -77.0
--zoom 9.5` (PA/NY border). In the first round `measure.py pan` ignored `--lat/--lon/--zoom` and
panned whatever view was on screen (it was fixed in the fix round), so the first-round rows depend
on the view set just before, which was not recorded for Rochester:

| View | Overview drawn up to | Frames/s (median) | MapLibre CPU per frame (median) | Host CPU |
|---|---|---|---|---|
| Pittsburgh z14, PA + OH + overview | every zoom | 2.5 (40 swipes), 1.8 (20 swipes) | 602 ms, 764 ms | 62-100 % |
| Rochester NY z14, overview only | every zoom | 4.8 | 247 ms | 62-100 % |
| Pittsburgh z14 | z10 | **10.3** | **103 ms** | 62-100 % |
| ~~Rochester NY z14 (now plain background)~~ | z10 | ~~33.5~~ | ~~8.4 ms~~ (view not recorded; replaced by the next row) | |
| Rochester NY z14 (plain background), fix round, 2 runs of 20 swipes | z10 | **58.5, 56.3** | **2.4, 3.6 ms** | 34 %, 91 % |
| Pittsburgh z14, fix round, 40 swipes | z10 | 7.8 | 145 ms | 63 % |
| PA/NY border z9.5, overview under and around PA | z10 | 6.0 | 175 ms | 62-100 % |
| PA/NY border z9.5, fix round, 20 swipes | z10 | 4.6 | 261 ms | 62 % |
| skeleton, Pittsburgh z14, PA only (05 report) | - | 14.8 | 71 ms | not recorded |

A blank area is cheap to draw (it is only the background layer); the number shows the cost of the
overzoomed overview, not that a blank map is good. The spread between runs (Pittsburgh 7.8 to
10.3 frames/s) comes from the host: other engineers' emulators share the GPU.

The probable cause is that an overzoomed tile draws its whole parent tile's geometry, so a z14
view redraws large z7 tiles many times. I did not confirm this in MapLibre's code. The z8-z10
band still costs (6 frames/s at the border). A cheaper overview (the map track's own 3.66 MB
build) or a lower cap would help; both are one data change.

## 5. Pack manager (task 3)

`PackLibrary` (`:core:packs`) holds the installed packs and the one running install as
StateFlows. It is UI-free, and the map, the States screen and a later Android Auto screen all
use it. `PackInstaller`:

1. reads `manifest.json` first, checks versions, names, bounds and free space (pack size +
   200 MB, `free_space_margin_mb` in `assets/rules/packs.json` since the fix round; 0 bytes free
   now counts as full);
2. copies each part into `packs/.staging-<id>-<n>/`, hashing while copying, and checks size
   and SHA-256 against the manifest;
3. runs the normal scan on the staged folder;
4. renames it into place. An older copy of the same pack (also one adb put under another folder
   name) is renamed aside first and deleted after the switch.

Any failure or a cancel deletes the staging folder. On the next scan, `cleanUp()` removes
`.staging-*`/`.removing-*` folders a crash left behind. It also puts back an `.old-*` copy when a
crash came between moving the old copy aside and moving the new one in. Removal renames the
folder aside first, so a half-deleted pack is never scanned.

Sources: `PackSource.Archive` (a `.tar` from `ACTION_OPEN_DOCUMENT`) and `PackSource.Folder`
(`ACTION_OPEN_DOCUMENT_TREE`). The tree picker cannot pick the Downloads root itself on Android
11+, but a folder inside it works.

**On the emulator** (release and debug builds, packs pushed to `/sdcard/Download/`):

| Case | Result | Screenshot |
|---|---|---|
| no pack | overview + "No state map installed / Add a state"; States screen explains | 02, 03, 04, 05 |
| add `test-erie-2026-11-14.tar` (8.5 MB) via the picker | "Erie test cut added. Checksums checked."; `install-record.json` written (`source: archive`); map moved to the pack | 06, 07, 08 |
| damaged archive (one byte flipped in the map) | "Could not add … map.pmtiles is damaged: its SHA-256 does not match the manifest. Nothing was installed."; the installed copy untouched | 09, 39 |
| remove | confirmation dialog, folder gone | 10 |
| add the same pack as a folder (tree picker, "Allow access") | installed, `source: folder` | 11 |
| add `us-pa-2026-11-14.tar` (1.25 GB, map + routing + search) | progress per file; **about 14.6 s** (logcat: 24 MB copied at 15:33:43.68, done 15:33:57.97); Map · Routing · Search · checked at install | 12, 13 |
| add Ohio (1.11 GB), cancel at 333 MB | "was cancelled. Nothing was installed."; packs folder holds only `us-pa` and `test-erie`, no `.staging-*` | 13, 40, 41 |
| add Ohio fully | **about 11.2 s** (20 MB at 15:35:21.34, done 15:35:32.34) | 14 |

That is about 85-100 MB/s with SHA-256 on the emulator's virtual disk; a phone's flash and CPU
will differ. The archives were pushed with `adb push` at 45-98 MB/s (12-23 s). No foreground
service exists, so if Android kills the app during a long copy the install simply did not happen
(staging cleaned on the next start).

## 6. Settings (task 4)

`SettingsStore` keeps `{"map_style":"automatic","frame_rate":"screen","guidance_sound":"silent","units":"miles"}`
in `files/settings.json`, written atomically. Unknown or broken values fall back to the default
(unit tests). On the emulator: all four changed, the file read back with `run-as`, the app
force-stopped and restarted, the night map and 30 fps still applied (screenshots 20-22).

- **Map style:** Automatic follows the system dark mode; Day and Night override it. The screens
  and the system bar icons follow the map.
- **Frame rate:** `FrameRates.plan()` (pure, unit-tested) turns the setting and the display's
  modes into three requests:
  - `MapView.setMaximumFps(n)`;
  - `Surface.setFrameRate(rate, FRAME_RATE_COMPATIBILITY_DEFAULT, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)`
    on MapLibre's own `SurfaceView` (`MapView.getRenderView()`, Android 11+);
  - before Android 11 only, `WindowManager.LayoutParams.preferredDisplayModeId` set to the
    fastest mode at the current resolution.

  Screen rate = the highest `Display.Mode.refreshRate` at the current resolution; 60 and 30 ask
  for 60/30, so a 120 Hz phone can slow down. **All three are requests.** With
  `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS` a phone may keep its current rate, and battery saver can
  cap it. Since the fix round the Settings text says so ("Asks for this screen's fastest rate
  (N Hz). The phone may keep a lower one."), and the log line reads `frame rate requested: …`.
  Whether the display switched shows only in `dumpsys display` / SurfaceFlinger on a phone. Looked up on developer.android.com today: the
  frame-rate guide recommends `setFrameRate()` over `preferredDisplayModeId`, and the Android
  15 adaptive-refresh-rate guide says rates set explicitly on a SurfaceView are respected.
  Logged on the emulator: `frame rate: max 60 fps, surface 60.000004 Hz` (Screen rate) and
  `max 30 fps, surface 30.0 Hz` (30).
  **The emulator has one display mode, 60 Hz** (`dumpsys display`: `supportedModes [{id=1, … fps=60.000004}]`),
  **so rendering above 60 Hz was not and cannot be confirmed here.** It needs a 90/120 Hz phone.
- **Guidance sound:** stored only; the screen says guidance is not built yet.
- **Units:** stored; nothing shows distances yet.

## 7. Location and the browsing puck (task 5)

- **Permission in context:** nothing is asked at launch. The my-location button asks for FINE
  and COARSE together (Android 12+ can then offer "Approximate"). The system dialog offered only
  "While using the app / Only this time / Don't allow" (screenshot 23). Refusal shows a note (24).
  Refusal for good shows a note with an "App settings" button (35; made with `pm revoke` +
  `user-fixed`). Phone location off shows its own note (34). Tracking runs only between
  `onStart` and `onStop`.
- **Sources behind interfaces** (`:core:location`): `PositionSource` / `HeadingSource` return
  Flows. On Android, `AndroidPositionSource` uses `LocationManager` (GPS + network providers,
  1 s, last known fix first) and `RotationVectorHeadingSource` uses `TYPE_ROTATION_VECTOR`.
  For tests, `SimulatedPositionSource` replays a GPX track and `SimulatedHeadingSource` a list
  of headings, both on a virtual clock. No Google Play services.
- **Compass math** (`Compass.azimuth`, unit-tested): the direction of the screen's top edge
  while the phone lies flat, or of the back of the phone once it stands upright (car mount),
  whichever has the longer horizontal shadow. It handles all four display rotations. True north
  uses `GeomagneticField` declination at the last fix. A low-pass on the circle smooths it
  (359 and 1 average to 0).
- **Rules as data** (`assets/rules/position.json`): grey when the fix is older than 10 s or its
  accuracy is worse than 100 m; cone hidden after 3 s without a compass reading; smoothing 0.25.
  A network fix does not replace a fresh satellite fix (`FixFilter`; which providers count as
  satellite, `["gps"]`, is in the rules file since the fix round).
- **Drawing:** a GeoJSON point and two MapLibre symbol layers on top of the style (cone, then
  dot). The bitmaps are drawn at run time from the sketch's numbers in its units, 1 unit =
  0.75 dp: core radius 14 with a white ring 5 wide; halo radius 30 at 14 %; shadow; cone radius
  105, 70 degrees, gradient 66 % → 26 % at 55 % → 0. Colours are the sketch's route blues
  (#5BAAFF day, #4B96EE night); grey is #8C96A1 / #6E7883 (my choice: the sketch has no grey
  puck). The cone rotates with `icon-rotation-alignment: map`; the map stays north-up and
  top-down (rotate and tilt gestures off).
- **My-location button:** centres (zooms in to z16 if further out) and follows the puck until
  the user pans; the icon is filled while following.

**On the emulator** (screenshots 25-36; the scripts are in `android/scripts/`):

| Check | How | Result |
|---|---|---|
| puck appears after the grant | `geo_route.py --fix`, tap my-location, allow | blue puck, ring, cone (26 day, 27 night after switching style). The first fix arrived about 4.5 s after the grant. |
| moves along Peach St | `make_route_gpx.py` took Peach St from the PA pack's z14 tiles (735 points, 10.91 km, from 42.1334,-80.0894 at the bay down to I-90 exit 24); `geo_route.py --every 3 --interval 1` sent 245 fixes | camera followed (28 day, 29 night, 31 at exit 24). The puck log had 217 distinct positions, **largest distance to the track 0.7 m** (log rounding), **0 steps backwards** of 216, last 42.04669,-80.07818 |
| compass cone | `adb emu sensor set magnetic-field` to face magnetic east, then south | cone 80 then 171 degrees true (30) |
| grey, old fix | `cmd location set-location-enabled false` (the emulator repeats its last fix every second otherwise) | GREY "old" after the 10 s rule (32 night, 33 day); the cone stays, grey, as the compass still works |
| grey, poor fix | a test provider with `--accuracy 300` | GREY "poor" (36); test provider removed and the shell's mock-location permission reset afterwards |

## 8. Proposal: the navigation session (task 6)

**What it is.** One `NavigationSession` object in a new plain-Kotlin module `:core:trip`. It
holds everything about the current trip and nothing about how it is drawn. The phone screens
and the later Android Auto screen are two renderers of the same object. The session never
references a View, a Composable or a `CarContext`.

**State** (one immutable `TripState`, published as `StateFlow<TripState>`, at most about 1 Hz
plus on events):

- `phase`: Idle → Planning (destination chosen, routes being computed) → Choosing (alternatives
  ready) → Guiding → Rerouting → Arrived; plus Failed(reason, e.g. "destination is outside the
  installed states", "no route").
- `destination` (name, point, search entry id) and `origin`.
- `routes`: the alternatives, each with geometry, length, time, legs and maneuvers (with lane
  arrays and the street names and shields to show), the routing pack set's `graph_build`, and
  the OSM date. The chosen route id.
- `progress` (while guiding): the matched position on the route and the raw fix, heading,
  distance and time to the next maneuver, the next and the following maneuver. The following
  maneuver is filled only when the design rule holds: within 0.5 mile and 1 minute, using the
  route's own time estimate. Lane guidance for the next maneuver (best / allowed / not for this
  route, the best lane taking the following turn into account; empty when the map has none:
  nothing guessed). Remaining distance and ETA. Off-route flag. Stopped/moving, which drives the
  arrow's brake lenses.
- `display hints` derived from the phase: browsing (top-down puck) or guiding (tilted
  heading-up arrow). The design ties the tilted view to guidance starting, so this belongs to
  the session, not to a screen.

Settings (units, guidance sound) are not copied into the state. Renderers and the announcer
read `SettingsStore` themselves.

**One-shot events** (`SharedFlow<TripEvent>`): announce maneuver (for the later chime or voice;
silent by default, so nothing listens yet), rerouted, arrived, pack missing for destination.

**Who owns it.** The process, not a screen: `AppGraph` creates it once, like `PackLibrary` and
`PuckTracker` now. Starting guidance also starts a small foreground service (type `location`)
that keeps the process alive while the phone screen is off or Android Auto is showing. It only
holds the session alive; it contains no logic. The current permission set covers this:
"while in use" location may run in a foreground service started while the app is visible. The
manifest would then add `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_LOCATION`, and still no
background location. If the process dies mid-trip, the destination and route id are saved to a
small file, and the session offers to resume.

**Inputs.** Commands from either screen: `planTo(destination)`, `choose(routeId)`, `start()`,
`stop()`, `reroute()`. Positions from the same `PositionSource` the puck uses (one GPS listener
for the whole app). Routing through a `RouteEngine` interface (valhalla-mobile over the
installed packs' routing tars), and progress through Ferrostar's core navigation controller
behind a small adapter, so the state shape is ours and not Ferrostar's. `PackLibrary` tells the
session which states are installed, so it can say "add Ohio" instead of failing silently.

**How screens observe it.**

- Phone: a ViewModel maps `TripState` to UI state (banner, lane row, route line, arrow, camera
  mode) with `collectAsStateWithLifecycle`. Rotation, theme changes or leaving the screen lose
  nothing, because nothing lives in the screen.
- Android Auto (`androidx.car.app`): a `NavigationScreen` maps the same `TripState` to
  `NavigationTemplate` (`RoutingInfo` with `Step`, `Lane`, `TravelEstimate`) and calls
  `NavigationManager.updateTrip()` with each state. Its buttons send the same commands.
- Both draw the same route line and arrow from the session's geometry. The map style and the
  `StyleComposer` stay shared.

**Threading and tests.** The session runs confined to one coroutine dispatcher (no locks), on
an injected clock. It is tested like `PuckTracker`: `SimulatedPositionSource` driving the Peach
St GPX, plus a fake `RouteEngine` returning a fixed route, then assertions on the sequence of
`TripState`s (e.g. the following-turn rule at exit 24, off-route at a wrong turn, arrival).

## 9. Measurements (task 7)

All on the emulator `beamline_api36` (AOSP API 36 x86_64, 3 GB, WHPX, host GPU through
gfxstream, MapLibre Vulkan), release build. **Other heavy jobs ran throughout** (see the top),
so these are indicative only.

### APK size (universal APK, all four ABIs; Python `zipfile` over the APK)

| Part (in the APK) | Skeleton (`app-release-vulkan-fix.apk`) | Shell (`app-release-shell.apk`) | Change | Fix round (`app-release-shell-fix.apk`) |
|---|---|---|---|---|
| **total** | **50.87 MB** | **69.94 MB** | **+19.07 MB** | **69.94 MB** (69,939,780 bytes, +90 bytes) |
| `assets/basemap` (overview, stored) | - | 17.77 MB | +17.77 | same |
| `assets/style` (1.65 MB, deflated) | - | 0.86 MB | +0.86 | 0.86 MB (1.66 MB, third copy) |
| `assets/rules` | - | 1 KB | | 1 KB (+ `packs.json`) |
| dex | 0.88 MB | 1.15 MB | +0.27 | 1.15 MB |
| `lib/arm64-v8a` | 13.03 MB | 13.03 MB | 0 | same |
| `lib/armeabi-v7a` / `x86` / `x86_64` | 9.61 / 13.40 / 13.62 MB | same | 0 | same |
| other | 0.24 MB | 0.26 MB | +0.02 | 0.26 MB (`DebugProbesKt.bin` gone) |

An arm64-only split would be about 33.3 MB (total minus the other three ABIs). That is an
estimate, not measured with bundletool. The map track's own public-domain overview is 3.66 MB
(their report 07). Swapping it in (`sync_assets.py basemap --basemap <file>`) would make the APK
about 14 MB smaller; not tried here. Debug APK: 81.92 MB.

### Cold start to first map frame (`measure.py startup`, 1 warm-up run not counted)

Method as in the skeleton report: `am start -W` TotalTime, and the app's own log of the first
map frame and the first fully rendered frame, counted from process fork. Both state packs were
installed and location was allowed (the puck starts at launch).

| Build, view | CPU load around it | am TotalTime median (min-max) | First map frame | Fully rendered |
|---|---|---|---|---|
| shell, before the two start-up changes below, Erie z13 (7 runs) | 77 % read just after | 644 ms (421-681) | **1004 ms** (741-1494) | 2087 ms (1492-3510) |
| shell, after them, Erie z13 (7 runs, first try) | very high (activity frame alone doubled) | 1140 ms (691-2576) | 1747 ms (978-5163) | 4141 ms (2741-7002) |
| **shell, final APK**, Erie z13 (7 runs) | 91-97 % | 850 ms (637-1156) | **1234 ms** (1151-1822) | 2524 ms (2263-3088) |
| shell, final APK, lower 48 z3.3, overview only in view (5 runs) | 91-97 % | 1178 ms (1015-1446) | 2107 ms (1821-2311) | 3336 ms (2779-3380) |
| reproduce review, final APK, Erie z13 (7 runs) | 100 %, 3 other emulators | 784 ms | 1375 ms | 3314 ms |
| **fix round**, Erie z13, first set (7 runs) | 19-77 % (per run) | 624 ms (542-1057) | **993 ms (858-1488)** | 2040 ms (1689-2576) |
| **fix round**, Erie z13, second set (7 runs) | 53-94 % | 678 ms (455-2659) | **1022 ms (781-3134)** | 2200 ms (1762-6141) |
| skeleton second round, Erie z13, PA only (05 report) | heavy, not recorded | 781 ms | 680 ms | 1574 ms |

The fix-round rows used a release built with `-Pbeamline.testExtras=true` (the camera extras;
the build differs from the shipped one only in that flag) and print the host load per run. Over
five sets by two engineers and a reviewer, the shell's first map frame lies between **about 0.8
and 1.5 s** on this emulator (medians 0.99-1.38 s, outliers to 3 s at 94 % load), against 0.68 s
for the skeleton. The direction is clear, the size is not.

One traced start (release, before the changes): process forked at 0; the activity's first
frame at +537 ms (still "Loading", no map). The MapView appeared at +741 ms: the pack scan
waited for `onResume`, and the view model composed both the day and the night style first. The
style was loaded at +876 ms and the first map frame came at +995 ms. The two changes made: scan
when the view model is created, and compose only the theme on screen (the other one on a
switch). **No run shows that they helped** (the medians after them are not lower than the 1004 ms
before them); they are kept because they remove work, not because of a measurement. The rest of the gap to the
skeleton is the bigger style (214 layers for overview + two states, against 74) and the
overview's tiles. **This needs a real phone and a quiet machine before any conclusion.**

### Memory (`dumpsys meminfo`, TOTAL PSS; the emulator reports no GPU memory)

| Build | Start at Pittsburgh z14 | After 40 back-and-forth swipes | After 60 swipes travelling east | Lower 48 z3.3 |
|---|---|---|---|---|
| shell, overview drawn at all zooms | 120 MB (native 93) | 140 MB (native 111) | 124 MB (native 94) | |
| **shell, final** | **111 MB** (native 85) | **147 MB** (native 118) | **112 MB** (native 82) | 118 MB (native 90) |
| fix round: 8 s after start / 20 s after start | **150, 141 MB / 97 MB** (native 118, 108 / 70) | 143 MB (native 113) | 106 MB (native 79), 60 travelling swipes from a fresh start | |
| skeleton second round, PA only | 128 MB | 137 MB | 101 MB | |

**The "start" reading depends on when it is taken:** 8 s after a cold start at Pittsburgh z14
the app held 141-150 MB (tiles still being parsed), 20 s after it 97 MB. The first round's
111 MB and the reproduce review's 99/123 MB lie inside that range; the first round did not record
the delay. The commands are now exact: `measure.py memory --lat 40.44 --lon -80.0 --zoom 14
[--wait 8]` restarts the app at that view and waits `--wait` seconds.

Panning in the final memory run (load above 90 %): back-and-forth 4.5 frames/s (264 ms CPU per
frame), travelling 9.2 frames/s (122 ms). Fix round (load 47-63 %): back-and-forth 7.8 frames/s
(145 ms), travelling 21.5 frames/s (47 ms). Logs: `data/android/logs/shell/memory-final2.txt`,
`data/android/logs/shell-fix/memory-pan-pittsburgh.txt`.

### Build and tests (Gradle 9.9.1, AGP 9.5.1, JDK 25, 2 GB heap, 4 workers; emulator and other jobs running)

| Step | Time |
|---|---|
| `:core:packs:test :core:settings:test :core:location:test --rerun-tasks` (57 cases) | 49.8 s (fix round, 64 cases: 33.3 s) |
| `clean assembleRelease`, warm daemon | 112.7 s |
| `assembleRelease` after small changes | 62-143 s |
| `assembleDebug` | 37.8 s |

Gradle's peak memory was not measured; its heap is capped at 2 GB.

### Manifest

`aapt2 dump badging` of the final release APK (`data/android/logs/shell/release-badging.txt`):

```
uses-permission: name='android.permission.ACCESS_COARSE_LOCATION'
uses-permission: name='android.permission.ACCESS_FINE_LOCATION'
uses-permission: name='io.github.spysyweeb.beamline.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
  uses-feature-not-required: name='android.hardware.location'
  uses-feature-not-required: name='android.hardware.location.gps'
  uses-feature: name='android.hardware.vulkan.version' version='4194307'
```

The merged manifest
(`app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`)
has no `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `ACCESS_BACKGROUND_LOCATION` or
`FOREGROUND_SERVICE*`. The three network permissions MapLibre declares are still removed. The
location permissions MapLibre also declares are now our own declaration. `android.hardware.location`
was implied as *required* by the location permissions; it is now declared not required, because
the map works without it.

### Screenshots (all opened and looked at; `data/android/screenshots/shell/`, not in git)

A contact sheet of the main ones: `data/android/screenshots/shell-contact-sheet.png`.

| File | Shows |
|---|---|
| 01 | first start over the skeleton's version-1 packs: an old message text, since fixed to "manifest_version 1 is an old pack format; rebuild the pack" |
| 02, 03 | overview only, no pack: day, night (live switch); "No state map installed / Add a state" |
| 04, 05 | States screen empty: night, day |
| 06, 11 | system picker: Downloads with the `.tar`; tree picker in the pack folder |
| 07, 08 | Erie test cut installed; map moved to it, the state over the overview, day |
| 09, 39 | damaged archive refused: day, night |
| 10 | remove confirmation |
| 12, 40 | installing PA (1.25 GB) with progress: day; Ohio: night |
| 13, 41 | cancelled: day, night |
| 14 | Ohio and Pennsylvania installed, night |
| 15, 19 | PA + OH at z6.3 (overview only below z8): night, day |
| 16 | PA/OH line at z9.2, night: both states, no seam artefacts |
| 17 | Erie z13, night |
| 18 | PA/NY line at z10, day: New York from the overview (no labels), PA from its pack |
| 20, 21, 22 | Settings defaults (day); all changed (night map while the system is day); after restart the night map and 30 fps kept. **These three predate the second style copy and show the old hash `c1b3c3844e9e`; they were re-shot in the fix round** (`shell-fix/20-22`, hash `007a6f98c1f9`, new Screen-rate text) |
| 23, 24, 35, 34 | permission dialog; refused; refused for good with "App settings"; phone location off |
| 25 | 4 s after the grant, before the first fix: no puck yet (timing, not a bug) |
| 26, 27 | puck with cone, day; after switching to the night style |
| 28, 29, 30, 31 | following the drive down Peach St: day, night, cone turned east, at I-90 exit 24 |
| 32, 33, 36 | grey puck: old fix night and day; poor accuracy night |
| 37, 38 | final APK with the re-copied style (keystone and US-route shields, parking and bank icons): day, then live switch to night |

## 10. Unit tests (57 cases in the first round; 64 after the fix round, all passing, section 16)

| Class | Cases | Covers |
|---|---|---|
| `PackManifestTest` | 3 | the documented manifest with all parts and an unknown part; optional parts; missing map part or checksum |
| `PackScannerTest` | 13 | valid and bad folders; newer, older (incl. a real skeleton v1 manifest), other map format, schema 5, other schema; a newer *routing* format does not stop the map; file names (escape, subfolder, shared file, bad id); duplicate ids; 6 impossible bounds; broken map files (empty, foreign, header only, v2, cut short, no tiles, short copy), routing not a tar, search not SQLite |
| `PackInstallerTest` | 11 + 4 | archive and folder install with progress and install record; damaged part, wrong size, missing part, manifest not first, too new (refused before any part is opened), cancel, read error in the middle, all leave nothing; replacing an older copy under another folder name; crash leftovers cleaned and an `.old-*` copy restored; remove; tars written by Python (ustar, PAX). Fix round: 0 bytes free is refused before copying, and the margin comes from the rules; a non-tar file is "not a pack file" and a full disk is named as such; `./` entry names; the shipped `packs.json` |
| `PackLibraryTest` | 3 + 1 | install → state → remove; a failed install; cancel from another thread. Fix round: a cancel that lands after the pack is in place is reported as added (fails without the fix: checked) |
| `StyleComposerTest` | 15 + 2 | overview only (asset URLs, z10 cap); one pack without the overview; overview + two states (order, z8 gating, z10 cap, bounds); zoom narrowing; nothing installed; 8 broken template shapes; OSM credit; the shipped rules file. Fix round: ids `col.a` and `col_a` get separate sources and layers; camera limit z9.99 with only the overview and `zoomFor` outside the states |
| `SettingsTest` | 5 | defaults; stored and read back (atomic); unknown, missing and broken values; day/night decision; frame-rate plan incl. the emulator's 60.000004 Hz mode |
| `LocationTest` | 7 | GPX reading; grey rules (old, poor, unknown accuracy, cone timeout); a simulated drive down Peach St on a virtual clock until the puck greys; network vs satellite fixes (fix round: the satellite providers come from the rules); smoothing across north; azimuth flat, landscape, upright in a mount; the shipped rules file |

`android/scripts/pack_variants.py make|run` (the skeleton's device test of 23 broken packs)
writes version-1 packs, which the app now refuses, so it would only test that refusal. It now
stops with that message unless given `--allow-v1`. Its cases are covered for version 2 by the
unit tests above, but not re-run on a device.

## 11. Licences of what was added or now ships

| Item | Licence (checked at) | Ships to device |
|---|---|---|
| kotlinx-coroutines-core 1.11.0 (now also used by the core modules) | Apache-2.0 (POM on Maven Central) | yes (was already shipping through coroutines-android) |
| kotlinx-coroutines-test 1.11.0 | Apache-2.0 (POM on Maven Central) | no (tests) |
| Style bundle in the APK: style JSON from @protomaps/basemaps 5.7.2, modified | BSD-3-Clause code, CC0-1.0 design (bundle's `LICENSES.md`, map track's report 02) | yes, now in the APK |
| Noto Sans glyph PBFs | SIL OFL 1.1 (`glyphs/OFL.txt` in the bundle) | yes |
| Sprites: icons derived from tangrams/icons | MIT, (c) 2017 Mapzen (`sprites/tangrams-icons-LICENSE.md`) | yes |
| Sprites: US-route and Pennsylvania shields, driving icons drawn for Beamline | MIT (project licence), per the bundle's `LICENSES.md`; **art not yet approved by the owner** (contact-sheet rule) | yes |
| Overview `base-us-20261114-z0-7.pmtiles` (Protomaps hosted build cut, landcover removed) | OpenStreetMap ODbL 1.0 (credited on screen) + Natural Earth public domain (map track's report 02) | yes, in the APK |
| Puck, icons and theme colours | drawn in this project from `docs/design/position-markers.html`; MIT | yes |
| pmtiles (Python) 3.8.1, for `make_route_gpx.py` | BSD-3-Clause (already in `.venv/android`) | no (test tool) |

Nothing new is GPL, AGPL or LGPL. No Google Play services; no Material icon library (the five
screen icons are drawn in `ui/Icons.kt`). Pillow (the map venv's copy, already installed) made the
contact sheets from a scratch script that is not committed.

## 12. Not verified, failed or left open

- **No real phone.** Every timing is from a loaded emulator. Above-60 Hz rendering is not
  verified (60 Hz display only). Vulkan on phones is not tested.
- **Cold start is slower than the skeleton** (first map frame about 0.8-1.5 s over five sets,
  medians 0.99-1.38 s, against 0.68 s), with heavy load on top. No run shows that the two
  start-up changes helped.
- The overview between z8 and z10, where it lies under the states, still costs (4.6-6 frames/s at
  the PA/NY border, z9.5). The cause of the overzoom cost is inferred, not confirmed in
  MapLibre's code.
- Above z10, a place outside every installed state is blank (by design, decision 2). The
  my-location button and following now stop at z9.99 there, but a user who zooms in by hand
  still sees the plain background with no note. A "no state map here" note is a small later
  feature (it needs the state outlines, not just their boxes, to be right near borders).
- The puck was not checked against the sketch pixel by pixel; it was compared by eye (screenshots
  26/27 against the sketch's day/night puck).
- The grey colour of the puck is my choice (the sketch has none).
- In-app download, hosted packs and a foreground service for long installs: not built (as asked).
  A copy interrupted by the system killing the app leaves nothing installed, which is safe but
  must be restarted.
- `routing` and `search` parts are carried and checksummed, never opened by the app.
- The map track's own 3.66 MB overview was not tried in the app. Its credit would not be OSM;
  the app always shows the OSM credit anyway, as every state pack needs it.
- The APK's style is a snapshot of the map track's **uncommitted** work (section 2).
- `pack_variants.py` device test not ported to format 2 (section 10). The break review's 17
  hostile format-2 archives (its own script, outside this track's folders) cover the same ground
  on a device, and the fix round re-ran four of them (section 16).
- The location of a my-location tap before the first fix: the camera centres once the fix
  arrives (about 4.5 s on the emulator); there is no "searching" indicator.

## 13. Decisions for the owner

1. **Overview map**: keep the 17.8 MB OSM-based cut (more detail at z6-7) or switch to the map
   track's 3.66 MB public-domain build (14 MB smaller APK, no OSM data). It is one command,
   then a look at both.
2. **Overview above z8 around installed states**: shown up to z10 (now) costs frame rate near
   state lines. Stopping it at z8 is faster but leaves neighbouring states blank from z8.
3. **Grey puck colour** (#8C96A1 day / #6E7883 night) and whether the cone should stay when the
   fix is old (now it stays, grey, because the compass still works).
4. **Pack transport**: an uncompressed `.tar` per state (now). One file is simplest for users. A
   compressed download (the routing report measured xz at 27 % of the routing tar) can be
   added for hosting later without changing the installed layout.
5. **Shield art in the APK**: the map track's new US-route and keystone shields ship with this
   build; the owner's contact-sheet approval is still due.

## 14. Outside my folders

- `.gitattributes` does not mark `*.tar` as binary. The two test archives
  (`core/packs/src/test/resources/python-*.tar`) contain NUL bytes, so `text=auto` treats them
  as binary; adding `*.tar binary` would make that explicit.
- `packbuilder/map/style-bundle/` had uncommitted changes when copied (section 2). The
  orchestrator should commit them together with this work, or re-copy afterwards.
- `README.md` (root) could mention that the style now ships in the app and packs carry data only.
- The search track: the app reads `meta.schema_version` as the search part's `format_version`
  and `meta.osm_timestamp` as its OSM date. Keep those two keys stable, or tell the Android track.
- (Fix round) The style bundle changed again at 16:41 local (POI layers only) and was copied a
  third time (`007a6f98c1f9`); it is still uncommitted map-track work. The same advice applies.
- (Fix round) Erie at z13 shows white route shields reading "LECT" near the bayfront (screenshot
  `shell-fix/51`). That is the map data or the style's shield text, not the app; the map track
  may want to check it.

## 15. How to repeat

From the repo root (Git Bash; `export MSYS_NO_PATHCONV=1 ANDROID_SERIAL=emulator-5554` when a
phone is attached too):

```
python android/scripts/sync_assets.py                       # style bundle + overview into the app
python android/scripts/sync_assets.py check                 # re-hashes both copies
cd android && ./gradlew :core:packs:test :core:settings:test :core:location:test assembleRelease && cd ..
#   for the measurements and the --ef/--es extras below: ./gradlew assembleRelease -Pbeamline.testExtras=true
python tools/fetch_android_map_pack.py                      # us-pa (map, routing, search): folder + data/android/archives/us-pa-2026-11-14.tar
python tools/fetch_android_map_pack.py --state oh
python tools/fetch_android_map_pack.py --pmtiles data/android/inputs/erie-cut-z0-15.pmtiles --pack-id test-erie --name "Erie test cut" --parts map
#   (erie-cut-z0-15.pmtiles: the skeleton's Erie cut, `pack_variants.py cut`, copied from data/android/packs/test-erie/map.pmtiles)
python -I android/scripts/read_pack.py data/android/archives/us-pa-2026-11-14.tar --verify
emulator -avd beamline_api36 -no-window -gpu host -memory 3072 -no-snapshot -no-boot-anim -no-audio
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb push data/android/archives/us-pa-2026-11-14.tar /sdcard/Download/
python android/scripts/device_ui.py tap "States"
python android/scripts/device_ui.py tap "Add a state from a pack file (.tar)"
python android/scripts/device_ui.py tap "us-pa-2026-11-14.tar"     # (Show roots > Downloads first if the picker opens elsewhere)
adb logcat -s BeamlinePacks                                 # install progress per 10 %, then Done(...)
python android/scripts/device_ui.py shot data/android/screenshots/x.png
# location
python android/scripts/geo_route.py --fix 42.1334 -80.0894
python android/scripts/device_ui.py tap "Show my position"      # then WHILE USING THE APP
.venv/android/Scripts/python android/scripts/make_route_gpx.py data/android/packs/us-pa/map.pmtiles --name "Peach Street" --bbox=-80.11,42.045,-80.06,42.135 --out data/android/routes/peach-st.gpx
python android/scripts/geo_route.py data/android/routes/peach-st.gpx --every 3 --interval 1
adb logcat -s BeamlineLocation                              # "puck <lat> <lon> LIVE|GREY cone=... acc=..."
adb emu sensor set magnetic-field -47.7452:9.88766:0         # compass: magnetic east (upright phone)
adb shell cmd location set-location-enabled false            # grey after 10 s; set back to true
# measurements (a release built with -Pbeamline.testExtras=true; each prints the frame-rate request and host load)
python android/scripts/measure.py startup --runs 7 --warmup 1 --lat 42.121 --lon -80.082 --zoom 13   # Erie
python android/scripts/measure.py memory --lat 40.44 --lon -80.0 --zoom 14 [--wait 8|20]             # Pittsburgh, fresh start
python android/scripts/measure.py pan --swipes 40 --lat 40.44 --lon -80.0 --zoom 14
python android/scripts/measure.py memory                                                            # right after the pan
python android/scripts/measure.py pan --swipes 60 --pattern travel --lat 40.44 --lon -80.0 --zoom 14
python android/scripts/measure.py pan --swipes 20 --lat 43.15 --lon -77.6 --zoom 14                  # Rochester NY, no pack
python android/scripts/measure.py pan --swipes 20 --lat 42.0 --lon -77.0 --zoom 9.5                  # PA/NY border
"$ANDROID_HOME/build-tools/36.0.0/aapt2" dump badging android/app/build/outputs/apk/release/app-release.apk | grep uses-
adb shell cmd uimode night no; adb shell cmd uimode night        # must print "Night mode: no"
adb emu kill
```

Logs of the first round: `data/android/logs/shell/` (build, tests, startup, memory, pan A/B,
pack build, Python reader, the drive's GPS and puck logs). Fix round: `data/android/logs/shell-fix/`.

## 16. Review and fixes

Two independent reviews ("reproduce" and "break") both judged the work sound with fixes. They
raised 15 findings, all **minor**: no blocker and no major. A second engineer checked each one
on the same code and emulator, then fixed it, disputed it with evidence, or left part of it open
with a reason. Nothing new was built beyond the fixes. **Other engineers' jobs ran throughout:**
a 4.9 GB Java job, another engineer's emulator, Gradle builds; the host CPU read 19-94 %.

### 16.1 Findings and what happened

| # | Finding (review) | Severity | What happened |
|---|---|---|---|
| R1 | "Blank background, 33.5 frames/s, 8.4 ms" not reproducible (reproduce) | minor | **Partly disputed, partly fixed.** The reviewer's runs did not pan Rochester. The committed `measure.py pan` ignored `--lat/--lon/--zoom` and never started the activity (`git show HEAD:android/scripts/measure.py`, function `pan`), so it panned whatever view was on screen. With `pan` now restarting the app at the view: **58.5 and 56.3 frames/s, 2.4 and 3.6 ms** per frame (host 34 % and 91 %); screenshot `shell-fix/58` shows the blank background. The report's row did lack the view, settings and load: section 4 now gives them, strikes the old row, and says a blank map being cheap is no argument for anything. The "8 ms" quote in `map-compose.json` was replaced. |
| R2 | Cold-start "fully rendered" reproduces only to 31 % (reproduce) | minor | **Fixed (text).** Section 9 quotes ranges, adds the reviewer's row and two new sets (first map frame medians 993 and 1022 ms, range 781-3134 ms), and no longer suggests the two start-up changes helped: no run shows it. |
| R3 | `measure.py memory` command does not give the reported view (reproduce) | minor | **Fixed.** `memory` and `pan` now restart the app at `--lat/--lon/--zoom` and wait `--wait` s; both print the frame-rate request and the host load. Exact commands are in section 15 and in the script's docstring. The re-run found why the numbers differed: 8 s after a start the app holds 141-150 MB, 20 s after it 97 MB (tiles still being parsed at 8 s). |
| R4 | Emulator left in system night mode (reproduce) | minor | **Fixed for this round.** `cmd uimode night` printed `Night mode: no` when the emulator started (the reviewer had reset it) and again before `adb emu kill`. Section 15 now ends with that check. Who left it on in the first round cannot be told now. |
| R5 | Settings screenshots 20-22 show the older style hash (reproduce) | minor | **Fixed.** Re-shot with the final APK (`shell-fix/20`, `21`, `22`: defaults by day; Night + 30 fps; the same after a force-stop and restart, with `frame rate requested: max 30 fps, surface 30.0 Hz` logged). They show `007a6f98c1f9`, the third style copy. The first-round table notes the old hash. |
| R6 | Faint labels show through the translucent system bars (reproduce) | minor | **Fixed.** The bands behind the status and navigation bars were 88 % opaque (`0xE0`); now they are opaque, in the panel colour. In `shell-fix/26-puck-bars-day/night` no label shows through. Labels under the floating buttons and the credit are normal map behaviour and were left. |
| R7 | Frame-rate logs prove only the request (reproduce) | minor | **Fixed (wording); phone check still open.** Settings now says "Asks for this screen's fastest rate (60 Hz). The phone may keep a lower one." The log line is `frame rate requested: …`, and section 6 says all three calls are requests (`CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`). Checking it on a 90/120 Hz phone with `dumpsys display` remains open. |
| B1 | Pack ids `col.a` and `col_a` collide in the composed style (break) | minor | **Fixed.** `StyleComposer.suffix` is now one-to-one: letters, digits and `-` stay, every other character, `_` included, becomes `_` plus 4 hex digits (`col.a` → `col_002ea`, `col_a` → `col_005fa`, `us-pa` unchanged). New unit test. On the emulator the reviewer's `collide-a.tar` and `collide-b.tar` both installed and the style loaded with 343 layers and **no "duplicate" line in logcat** (screenshots `shell-fix/50`, `51`). |
| B2 | Removing the last pack, or a fix outside all packs, leaves a blank map under "This is the overview" (break) | minor | **Fixed; one part left open.** Three causes, three fixes. (a) Camera requests from intents were re-applied on every pack change, so the reviewer's Erie z13 came back after each removal. They are now applied once, and when the last state goes the camera returns to the lower 48. (b) With no state installed the camera stops below the overview's last zoom. A first try at exactly z10.00 was **still blank**, because MapLibre hides a layer at zooms ≥ its `maxzoom` (seen on the emulator). The limit is now z9.99 (`ComposedMap.maxZoom`). (c) My-location and following stop at z9.99 where the position lies outside every state's box (`zoomFor`). Checked on the emulator: removing all four packs at Erie z13 → camera z2.23 at 37.95,-95.95 (`53`); a z14 request with no state → z9.99 with the overview drawn (`54`); my-location from z3 → Erie at z9.99; following a fix to Los Angeles with PA+OH installed → z9.99, overview shown (`57`). **Open:** zooming in by hand past z10 outside the states is still blank with no note (by design, decision 2; a note is a small later feature). Pinch and double-tap gestures against the limit were not tried (adb cannot pinch); MapLibre applies `setMaxZoomPreference` to gestures too. |
| B3 | Doc, Python snippet and Kotlin disagree on `./manifest.json` and the pack_id first character (break) | minor | **Fixed.** One rule: both readers ignore a leading `./`, and the doc says so. The pack_id rule now gives the first-character rule and the regular expression. `problems()` reports a non-numeric `schema_version` instead of raising, and the script reports a non-tar file as "not a readable pack". `verify()` also lists missing parts and closes its files. The doc's snippet is regenerated from `read_pack.py` (compared: identical). Run on the reviewer's `dotslash.tar` (checksums ok), `schema5.tar` (problem listed), `not-a-tar.tar` (clean message), and on the real PA archive (16.3). Kotlin test for `./` names added. |
| B4 | Install error text for a full disk or a non-tar file misleads; 0 bytes free skips the check (break) | minor | **Fixed.** Write failures get their own message. For ENOSPC: "The phone ran out of storage space while copying map.pmtiles. Free some space and try again." A non-tar file: "not-a-tar.tar is not a pack file: it is not a tar archive". 0 bytes free now counts as full. On the emulator, a `dd` fill during an Ohio re-install showed the new message; nothing was installed, the installed Ohio stayed untouched and no staging folder was left (`59`). Not-a-tar: `52`. Unit tests for all three. |
| B5 | Narrow cancel race: an install that finished the rename can be reported as cancelled (break) | minor | **Fixed.** `PackLibrary` notes the installed pack on the IO thread; a `CancellationException` after that reports Done. The new test cancels from inside the installer's clock call, after the copy and before the rename. It **fails without the fix** (checked by taking the fix out once) and passes with it. Not triggered on a device. |
| B6 | `sync_assets.py basemap` does not validate; `check` does not re-hash the overview (break) | minor | **Fixed.** `basemap` refuses a file whose tile schema or major version differs from the style's (read from `map-compose.json`), and warns when tiles go above `overview_max_zoom` or the file is over the owner's 20 MB budget. `check` re-hashes `us-base.pmtiles` against its record. A fake schema-5.0.0 file was refused with nothing copied. The current overview passes; the state packs, if given as an overview, get both warnings. |
| B7 | Test extras live in the exported release activity; stale references; `DebugProbesKt.bin` ships (break) | minor | **Fixed.** The extras are read only when `BuildConfig.TEST_EXTRAS`: always in debug builds, in a release only with `-Pbeamline.testExtras=true` (used for the measurements). The plain release ignored `--ef lat 40.44 --ef lon -80.0 --ef zoom 14 --es screen settings`: the camera stayed on the PA+OH box at z4.75 on the map screen. `device_ui.py` and `tools/fetch_android_requirements.txt` no longer name removed scripts. `DebugProbesKt.bin` is excluded and is gone from the APK. |
| B8 | Install limits and the provider policy are constants, not data (break) | minor | **Partly fixed, partly kept with a reason.** `free_space_margin_mb` moved to the new `assets/rules/packs.json` (`InstallRules`; a test reads the shipped file), and the satellite providers (`["gps"]`) to `position.json`. **Kept in code:** the puck's drawing numbers (radii 14/5/30/105, 70°, alpha stops). They are the approved sketch's geometry, copied from `docs/design/position-markers.html`, which stays their one source; a rules file would be a second copy. `MAX_MANIFEST` (1 MiB) is a safety limit on reading a JSON file, not a behaviour rule. |

Also found and fixed in this round: the zoom limit's off-by-`maxzoom` (B2 above); `measure.py
pan`/`memory` ignoring their view arguments (the cause of R1 and R3); a camera request re-applied
on every pack change (the cause of B2's case). The first round said the emulator's Downloads
folder was cleaned, but `test-erie-2026-11-14.tar` and `erie-pack-folder/` from 15:26-15:31 were
still there. They are deleted now.

### 16.2 Changed files

- `android/core/packs`: `StyleComposer.kt` (one-to-one id suffix; `ComposedMap.maxZoom`,
  `zoomFor`, `packBounds`), `PackInstaller.kt` (`InstallRules`, free-space check, write and
  not-a-tar messages), `TarReader.kt` (`TarFormatException`), `PackLibrary.kt` (cancel race,
  rules, clock), `PackScanner.kt` (pack_id message); tests in `StyleComposerTest`,
  `PackInstallerTest`, `PackLibraryTest`.
- `android/core/location`: `Position.kt` (`satellite_providers`), `PuckTracker.kt`; `LocationTest`.
- `android/app`: `build.gradle.kts` (`TEST_EXTRAS`, `DebugProbesKt.bin` excluded),
  `MainActivity.kt` (extras gated, one-shot camera requests), `AppGraph.kt` (`packs.json`),
  `map/PackMap.kt` (camera reset, zoom limit, centring zoom, a camera log line, "requested"),
  `map/MapScreen.kt` (opaque bands), `map/MapViewModel.kt` (passes `ComposedMap`),
  `settings/SettingsScreen.kt` (Screen-rate text); assets `rules/packs.json` (new),
  `rules/position.json`, `rules/map-compose.json` (comment), `style/` (third copy).
- `android/scripts`: `measure.py`, `sync_assets.py`, `read_pack.py`, `device_ui.py`;
  `android/README.md`; `docs/pack-format.md`; `tools/fetch_android_requirements.txt`.

### 16.3 Re-measured (emulator `beamline_api36`, other jobs running)

| What | Result | How |
|---|---|---|
| Unit tests | **64 cases pass** (packs 52, settings 5, location 7), 33.3 s with `--rerun-tasks` | Gradle, 2 GB heap, 4 workers |
| Release APK (plain, the shipped kind) | **69,939,780 bytes = 69.94 MB** (+90 bytes); arm64 13.03 MB unchanged; no `DebugProbesKt.bin` | Python `zipfile` over `data/android/apks/app-release-shell-fix.apk` |
| Builds | `assembleRelease` after changes: 1 min 40 s to 2 min 53 s | `time`, warm daemon |
| Cold start, Erie z13 | first map frame medians 993 / 1022 ms (781-3134), activity 624 / 678 ms, fully rendered 2040 / 2200 ms | `measure.py startup`, 2 × 7 runs, host 19-94 % |
| Memory, Pittsburgh z14 | 141-150 MB 8 s after start, 97 MB at 20 s; 143 MB after 40 swipes; 106 MB after 60 travelling swipes | `dumpsys meminfo` TOTAL PSS via `measure.py memory` |
| Panning | Pittsburgh z14 7.8 frames/s (145 ms); travelling 21.5 (47 ms); Rochester z14 58.5 / 56.3 (2.4 / 3.6 ms); PA/NY border z9.5 4.6 (261 ms) | `measure.py pan`, Screen-rate setting (max 60 fps) |
| Pack install through the picker | PA 1.25 GB in 10.4 s; OH 1.11 GB in 8.8 s | logcat, first MB to Done |
| Python reader `--verify`, PA archive | all three parts `sha256 ok`, 7.2 s, peak working set 19 MiB | PowerShell `Start-Process`, `PeakWorkingSet64` polled every 0.1 s |
| Merged manifest / badging | `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, AndroidX's `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` only; no INTERNET, background location or foreground service | `aapt2 dump badging` (`data/android/logs/shell-fix/release-badging.txt`), merged-manifest grep |

### 16.4 Screenshots of the fix round (all opened and looked at)

`data/android/screenshots/shell-fix/`, and a contact sheet `shell-fix-contact-sheet.png`:
20-22 Settings (day, night, after restart); 26 puck with opaque bands (day, night); 50-51 two
colliding pack ids listed and drawn; 52 not-a-pack file; 53 last state removed → whole US;
54 no state, z14 asked → z9.99 with the overview; 55 installing PA; 56 PA and OH back; 57
following a fix to Los Angeles → z9.99 overview; 58 Rochester z14 (blank, by design); 59 disk
filled during a copy. The new States and map states were shot by day only; the same cards and
the overview by night are in the first round (03, 39, 41), and their drawing did not change.

### 16.5 State left behind

The emulator `beamline_api36` was stopped with `adb emu kill` (serial pinned; another engineer's
emulator was not touched). On it: the plain release APK, PA and OH re-installed through the
picker ("checked at install"), settings at their defaults, system night mode off, location on,
GPS fix at Erie, Downloads empty. Logs: `data/android/logs/shell-fix/`. APKs:
`data/android/apks/app-release-shell-fix.apk` (plain) and `app-release-fix-testextras.apk`.
