# :map:layers

`MapLibreController`: the app's layers on one `MapLibreMap` (Android library, MapLibre Native
13.7.1), implementing `:core:api`'s `MapController`. Contract: `docs/architecture.md` 2.5 and 5.1 M,
`assets/markers/SPEC.md`, `docs/architecture-saved-places.md` SP40-SP46. Owner during the round:
track M.

| File | What |
|---|---|
| `MapLibreController.kt` | The contract file (stubs filled): delegates to one `private val layers = Layers(context, map)`. |
| `Layers.kt` | Sources, layers, theme colours, the vehicle marker and its glide (one display frame at a time), camera moves, taps, long presses, camera gestures. |
| `RouteColors.kt` | Reads `route_colors.json` (the "selected" candidate, B). |
| `SceneParts.kt` | Android-free parts: the route split at the traveled cursor, `RouteCut` and `RouteSources` (the line cut into far and near pieces, and which source a new scene changes), `routePlacement` (where the route layers go in a style, twice with the overview map), the tap rule (pins, saved markers, the POI label nearest the finger), sort keys, pin image ids, the POI layers of a composed style, `Glide` (where a gliding marker is at a given time); `Ids` (every source, layer and image id). |
| `MarkerImages.kt` | The 11 marker pieces from `res/drawable-<density>/`, and the pins drawn on a Canvas. |
| `res/drawable-<density>/marker_*.png`, `res/raw/route_colors.json` | Copies made by `scripts/sync_art.py` (do not edit). |
| `scripts/sync_art.py` | Copies the marker PNGs (`data/art/markers/`, made by `build_markers.py --route-candidate B`) and `assets/route_colors.json`; `--check` exits 1 on any difference. |
| `src/test/.../LayersJvmTest.kt` | JVM tests of the Android-free parts. |
| `src/androidTest/.../MapLibreControllerDeviceTest.kt`, `TestMapActivity.kt` | The controller on a real MapLibre map (device test; the manifest declares the test activity and removes MapLibre's network permissions). |

## Use

On every style (re)load call `controller.onStyleLoaded(style, theme)` (it adds the images, the seven
GeoJSON sources and the 19 layers again, 30 in a style composed with the overview map and a state,
and redraws the last scene at once in the style's `theme`; the next `show` decides the theme again),
and apply the camera director's current `target` again: `MapLibreMap.setStyle` cancels a camera ease
that was running (seen on the emulator: a re-fit started just before a day/night reload never
happened); then `show(scene)` on every new scene (only the sources whose content changed are sent to
MapLibre; a cursor step sends only the two short near pieces of the route); `moveCamera(target)` with
the director's targets, each through `target.withZoomCap(composed::zoomFor)` (`:map:scene`, rule K1);
collect `gestures`; `release()` when the map goes away. Main thread only.

## Layers (bottom to top)

Inserted just under the style's `roads_oneway` (in a style made by `StyleComposer` its first state
copy, `roads_oneway@<pack>`), so above every road and bridge and below one-way arrows, shields and
labels (`route_colors.json` layer_order):

1. `beamline-saved-under`: the saved markers while guiding (`MapScene.savedBelowRoute`).
2. `beamline-route-alternatives-casing`, `beamline-route-alternatives` (grey).
3. `beamline-route-traveled-casing`, `beamline-route-traveled-near-casing`, `beamline-route-traveled`,
   `beamline-route-traveled-near` (the driven part, from the start to the traveled cursor).
4. `beamline-route-casing`, `beamline-route-near-casing`, `beamline-route`, `beamline-route-near`
   (the part ahead, from the cursor on).

The driven part and the part ahead each come in a far and a near piece (`RouteCut`, chunks of 64
points): `beamline-route-traveled` (source `beamline-route-traveled`) holds the start to the cursor's
chunk, `beamline-route` (source `beamline-route`) the end of that chunk to the end (the whole line
while choosing), and the source `beamline-route-near` the chunk's start to the cursor (`part:
driven`) and the cursor to the chunk's end (`part: ahead`). A far source changes only when the cursor
enters another chunk. Every casing of a kind is below every fill of it, so where two pieces touch no
joint is seen (opaque colours, round caps and joins; looked at at z18, day and night).

In a style composed with the overview map and a state (`StyleComposer`: all the overview's copies
first, its labels only below `state_min_zoom` 8), items 1-4 are there twice: as above with
`minzoom` 8, and a copy of each (id + `-overview`) under the overview's first label layer
(`water_label_ocean@overview` with today's style) with `maxzoom` 8, so the route is under the
overview's labels and shields at every zoom (`SceneParts.routePlacement`). With the overview only:
one set under its first label layer.

Under the state's first `pois` layer (above the road labels, shields and one-way arrows, below the
POI and place names; `SceneParts.guideRouteAnchor`), visible only while guiding (the arrow is the
marker, `SceneParts.routeOverLabels`; next run part 1): `beamline-route-guide-casing`,
`-guide-near-casing`, `beamline-route-guide`, `-guide-near`, the chosen route ahead again, so the
names of the road being driven (the banner names it) do not sit on the line in the tilted view; its
copies under the labels are hidden meanwhile (`Ids.GUIDE_ROUTE_ON_TOP`, `Ids.ROUTE_AHEAD`). The
style's route-number shields (`roads_shields` copies, `Ids.GUIDE_SHIELDS`) are moved above these
layers while guiding and back after it (`SceneParts.guideShields`, `Layers.moveShields`; batch A,
the owner 2026-11-18): the driven road's "US 19" stays readable, its name stays under the line.
These are the only style layers that move at run time.

Above every style layer:

4b. `beamline-puck-bubble`, `beamline-puck-bubble-edge` (batch C2, rule G7): the browsing puck's
   uncertainty bubble, a geographic disc of `VehicleMarker.Puck.bubbleM` metres (72-point ring,
   `SceneParts.bubbleRing`) in the puck's blue at 16 % by day, 22 % by night (grey when stale), with
   a 1.5 dp edge blurred 2 dp; moves with the puck's glide; never drawn for the arrow.
5. `beamline-saved-over`: the saved markers while browsing and choosing.
6. `beamline-pins`: search results (batch B, S11: the destination pin's art at 80 %, under the others), stops (numbered), destination, picked place (anchored at the bottom).
7. `beamline-puck-cone`, `beamline-puck`: browsing (cone turns with the compass and lies on the map;
   the dot stays round; stale: grey dot, no cone).
8. `beamline-arrow-beam` (night map only, never stale), `beamline-arrow-glow` (stopped),
   `beamline-arrow` (or `arrow_stale`), `beamline-arrow-lit` (stopped): all turn with the course and
   lie on the road (`icon-pitch-alignment: map`), as SPEC.md says.

Line widths: `widths_dp` of `route_colors.json`, exponential base 1.5 by zoom; casing and fill are
two lines; colours per `MapScene.theme` (candidate B day/night, its `traveled` pair, the grey
`alternative` pair; right after a style load the style's theme, until the next `show`). Saved markers: sprite `saved_home`, `saved_work`, `saved_place` (from the
style's sprite sheet), `icon-size` = the marker's scale, `symbol-sort-key` others 0, Work 1, Home 2,
filter `zoom >= min_zoom`, upright (viewport alignment), overlap allowed. Only one of the two saved
layers is visible, so no layer moves at run time; while guiding the markers are therefore below the
route lines and below the style's labels too.

## Behaviour

While guiding (the arrow is the vehicle marker; docs/next-round.md item 22): a traffic light is drawn
at each point of `MapScene.lights` (layer `beamline-route-lights`, above the lines and the saved
markers, below the pins and the vehicle, from zoom 13.5); the style's general place names (`pois`
and its copies per state) are hidden; of `pois_driving` only fuel, charging, parking, rest areas,
services and toll booths show; the road names (`roads_labels_minor`, `roads_labels_major`) are 1.3
times their size and upright. When guiding ends the style's own values are put back.

- **Glide.** A vehicle marker with a new point moves from where it is on the screen to the new
  point (linear; the arrow's bearing the shorter way), the vehicle source updated on every display
  frame (`Choreographer`, so the "animator duration scale" developer option cannot freeze it while
  MapLibre's camera ease runs). The arrow glides over its `glideMs` (rule K3); the browsing puck over
  the time since its last new point, at most 3 s (the time `CameraDirector` eases a BROWSE_FOLLOW
  move over), so in both cases the marker stays still on the screen while the camera follows
  (`easeCamera(..., linear)` over the same time). The same point with another look (lenses lit,
  stale, night, a turned cone) keeps the running glide; a new point in the middle of a glide starts
  from where the marker is. A change between puck and arrow, a style load, `glideMs = 0`, or a puck
  after more than 3 s: drawn at once.
- **Camera.** `moveCamera`: a box (`newLatLngBounds` with bearing, pitch and the padding in dp) or a
  centre (`zoom = null` keeps the zoom); `animateMs > 0` eases linearly, 0 jumps; a box MapLibre
  refuses leaves the camera where it is.
- **Gestures.** Tap: pins win (where pins overlap, the one drawn on top: picked over the destination over
  the stops), then saved markers (the one on top: Home over Work over others,
  reported as `"saved:<id>"`), then the style's POI labels (`pois`, `pois_driving` and their copies;
  the named one nearest the finger on the screen; name from `name:en` or `name`, kind, the feature's
  point); a box of 12 dp around the finger. Long press: `LongPress(point)`. A camera move started by
  a user gesture: `CameraMovedByUser`.

## Tests

`sh tools/round/gradle.sh m :map:layers:testDebugUnitTest` (JVM, 18 tests): the colours copy equal
to its source and its values, the split, the far and near pieces joining into the split, a cursor
step on a 5,000-point line sending only the near pieces (`RouteSources`), the tap rule and the POI
label nearest the finger, sort order, pin images, the glide (interpolation, a new point in the
middle of a glide, another look of the same point), the route placement and POI layers in composed
styles (with and without the overview map, the real `day.json` composed by `StyleComposer`'s rules)
and in the real `day.json`, the layer ids.

Device test (`MapLibreControllerDeviceTest`, 12 tests; a small offline style named the way
`StyleComposer` names layers, `roads_oneway@us-pa`, with a GeoJSON source and stand-in images for the
three saved icons): the layer order around `roads_oneway@us-pa`, the saved layers' switch, a day to
night reload with every layer back and the night colours (and the beam only at night), a style loaded
in the other theme than the last scene's, injected taps (a pin, Home over a star over a
`pois_driving` label, a `pois` label, two labels 7 dp apart, empty map), a long press, a user pan
versus an app move, camera moves (centre with bearing and pitch, zoom kept, a padded box, an eased
move), the glide of the arrow and of the puck read back from the rendered map, the route with the
overview map (layer order, zoom ranges, and from a screenshot: the overview's label over the line at
z7, the state's at z12), and a 5,000-point route whose cursor moves 150 steps (logs the main-thread
time of `show()`). Run it with `am instrument` (never `connectedAndroidTest`, docs/architecture.md 5.3):

```
sh tools/round/gradle.sh m :map:layers:assembleDebugAndroidTest
sh tools/round/adb.sh m install -r data/gradle/m/build/map/layers/outputs/apk/androidTest/debug/layers-debug-androidTest.apk
sh tools/round/adb.sh m shell am instrument -w io.github.spysyweeb.beamline.map.layers.test/androidx.test.runner.AndroidJUnitRunner
```

The rest is seen in `:map:demo` with the real style and map (`android/map/demo/README.md`).

`python android/map/layers/scripts/sync_art.py --check` (the copies equal their sources; needs
`data/art/markers/` from `build_markers.py --route-candidate B`, since `data/` is not in git).
