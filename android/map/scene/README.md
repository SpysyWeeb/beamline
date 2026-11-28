# :map:scene

Plain Kotlin/JVM (no Android): what the map draws and where the camera goes, from the trip, the
puck and the saved places. Contract: `docs/architecture.md` 2.5, 3.6, 3.7 and
`docs/architecture-saved-places.md` 2.8, 5.2. Owner during the feature round: track M.

| File | What |
|---|---|
| `Scene.kt` | The contract file (stubs filled): `MapSceneMapper`, `SavedMarkerRules`, `CameraRules`, `CameraDirector`. Each class's private state is one `private val` after its last function. |
| `SceneBuilder.kt` | The mapper: route lines, pins, puck or arrow, saved markers. |
| `Director.kt` | The camera rules K1-K8 (K1b, K2a and K8 since 0.1.40); `pinInView` (K1b). |
| `CameraFile.kt` | Reads `camera.json` (format `beamline-camera/1`); every refusal is a fixed sentence (rule PV2). |
| `Motion.kt` | The glide (time between the last two vehicle positions), the bearing low-pass on the circle, small geometry. |
| `ZoomCap.kt` | `CameraTarget.withZoomCap(zoomFor)`: rule K1's z9.99 cap outside the states, applied by the app with `ComposedMap.zoomFor` (the director cannot know the installed maps). |
| `src/main/resources/.../camera.json` | Copy of `packbuilder/routing/camera.json`: `python android/routing/scripts/sync_data.py --only camera.json` (`--check` compares). |

## How the app uses it

One `MapSceneMapper()` and one `CameraDirector(CameraRules.bundled(), clock)` per map surface.

- `mapper.scene(trip, puck, theme, picked, saved, results, step)` on every trip, puck, theme,
  picked-place, saved, results or shown-step change; give the result to `MapController.show`.
- Feed the director: `onViewport(w, h, insets)` (insets from the drive overlay), `onTrip`, `onPuck`,
  `onGesture` (from `MapController.gestures`), `onMyLocation`, `onRecenter`, `onOverview`, and
  `tick()` once a second. 0.1.40: `onPlaceTapped(point)` for a place tapped on the map whose sheet
  opened, `onCameraAt(middle, zoom, bearing)` from the map's idle callback and at each tap or long
  press (K1b); `onShowPlaceAfterRoute(point)` before End when Back from directions reopens a window
  (K2a); `onStopView(points)` while a stop is being added, empty when that ends (K8). Apply every non-null `target` with `MapController.moveCamera`; `state`
  gives the mode and `showRecenter` for the drive overlay.
- The director asks `browse.follow_zoom` (16) on my-location; the z9.99 cap outside the installed
  states (K1) is not in the director: its frozen constructor gets no installed maps. The app applies
  it to every target: `controller.moveCamera(target.withZoomCap(composed::zoomFor))` (`ZoomCap.kt`;
  a centre with a zoom is capped at that centre, a target that keeps the zoom or fits a box is
  returned as it is), and keeps `setMaxZoomPreference(composed.maxZoom ...)` as today's `PackMap`.
  `docs/architecture.md` 5.4 item 4 tells I so (track M's request, applied by the lead).

## What it decides (beyond the letter of the contract)

- **Scene.** The arrow in GUIDING, REROUTING and ARRIVED (K7); before the session's first vehicle
  position the puck stands in. Arrow: stale = GPS `LOST`; lenses = `STOPPED` and not stale (M3); an
  unknown course keeps the last bearing (else the route's first bearing). A GREY puck is stale and
  has no cone. Route: guided = the guided route with its traveled cursor, no alternatives; CHOOSING
  (and PLANNING while the last choices are kept) = the selected choice (else the first) and the others
  as alternatives. Pins: every stop (`"stop:<index into TripState.stops>"`), the last one the
  DESTINATION, the others STOP numbered from 1 (reached ones keep their number and are marked), then
  the PICKED pin (`"picked"`). Saved markers: kinds, `min_zoom` 10/10/12, scale 1.25 from
  `camera.json`; a saved place that is any stop of the trip (by `place.savedId`) is left out;
  `savedBelowRoute` only in GUIDING and REROUTING.
  0.1.40: a chosen start (`Origin.FromPlace`) is the first pin, START `"start"`, in PLANNING and
  CHOOSING only (P22; its saved marker is left out then); `step` is `MapScene.step` in PLANNING and
  CHOOSING only (P21); the alternatives are `AlternativeLine`s with their choice ids (P23: a tap on
  one chooses it). Item 51: with `TripState.stopPreview.route` (PLANNING to REROUTING) that route is
  the chosen line and the trip's own route ahead (guided: from the start of the chunk of 64 points the
  traveled cursor is in, so the line changes only when the car enters the next chunk, not on every fix;
  before: the selected choice) its one alternative, `tripRoute = true` (grey, never tapped); not on the
  arrival map.
- **glideMs.** The time between the last two distinct vehicle positions; the same position seen
  again keeps its glide; the first position after the arrow appeared, and any gap over 3 s
  (`VehicleGlide.MAX_GLIDE_MS`: fixes back after a GPS loss) give 0, a jump. The cap is a code
  constant because `camera.json`'s keys are fixed by the contract this round.
- **Camera.** `target` null = leave the camera alone (BROWSE, GUIDE_FREE, PREVIEW/ARRIVED after a
  user pan). A follow move animates over the glide (BROWSE_FOLLOW: the time between the last two
  puck updates, up to 3 s, so the camera keeps moving between 1 Hz fixes; `:map:layers` glides the puck
  over the same time, so it stays still on the screen); a mode change, a viewport or inset change over
  `transition_ms`; the same move with only another duration is not re-emitted. GUIDE_FOLLOW puts the
  vehicle at `vehicle_from_bottom` of the free height through the padding (`followPadding`). PREVIEW
  fits all choices' boxes plus the stops and a chosen origin (the puck while no route exists yet),
  re-fits when the choices, stops or chosen origin change (also after a pan), not when another card
  is chosen; a box of about one point (< 0.0005 degrees) is a centre at `follow_zoom`. GUIDE_OVERVIEW
  fits the route from the traveled cursor on, once (again when the route is replaced), and shows
  Recenter. Ending guidance (GUIDING to IDLE) emits one move back to pitch 0, north up, at the last
  vehicle position, keeping the zoom (`zoom = null`). My-location: BROWSE to BROWSE_FOLLOW; in a
  guide mode it is Recenter; in PREVIEW or ARRIVED one look at the puck until the content changes.
  `onShowResults(points, minZoom)` (batch B, rule S11): BROWSE fitting the nearest results that fit at
  `minZoom` (the states' z8) or closer, never closer than `follow_zoom`, above the results list (Web
  Mercator, MapLibre's 512 dp world at z0); cleared by a gesture, my-location, a picked place or
  `onPlaceLeft`. The mapper's `scene(..., results)` draws them as RESULT pins "result:<i>" under the
  others, the selected one (at the picked place) as the PICKED pin. `ResultsCameraTest` (5).
  `onShowPlace(point)` (a place picked from a search, a list or a sheet, K1, next run part 1): BROWSE
  centred on it at `follow_zoom` with the insets (the app adds the place sheet) until a gesture,
  leaving BROWSE_FOLLOW; in PREVIEW or ARRIVED a look at it; nothing while guiding.
  `onPlaceLeft()` (part 1's review): the picked place's sheet closed or a map tap replaced it; the
  place is no longer a target, so the camera stays where it is when the insets change.
- **0.1.40 camera.** K1b (P8): a place tapped on the map on a browsing map that is not following
  stays put while its pin (28 x 38 dp, `PIN_W_DP`/`PIN_H_DP`, 8 dp margin) is in the free area
  (`pinInView`, Web Mercator from the last `onCameraAt`); once it is not, it is centred there at the
  map's zoom (top padding + the pin's height) and kept there at every inset until a gesture,
  my-location, another place, the results or `onPlaceLeft`; no room for the pin: the camera stays.
  A trip: nothing. Since 0.1.41 (item 54a) a tap while following ends it where the map is
  (`followBeforeTap`: its bearing and zoom kept, the compass no longer leading) and the next
  `onMyLocation` gives it back as it was (north up, or turned by the compass), in one press; a
  gesture, a two-finger turn, a picked place, the results or a new phase group end that return,
  `onPlaceLeft` does not. K2a (P50): a route closed before it started (PREVIEW to BROWSE) follows
  the puck (BROWSE_FOLLOW, north up, `follow_zoom`) when there is one; with `onShowPlaceAfterRoute`
  sent before (P46) it shows that place as a picked one instead, once. K6 (P4): ARRIVED frames the car
  and the destination together (the overview's padding, room for the arrow at the frame's edge; at most
  `arrived.zoom`); within about 50 m, no car
  or no viewport: centred on the destination as before; framed again only when the car, not STOPPED,
  has moved more than 20 m (`ARRIVED_REFRAME_M`), which also ends a pan. K5 (P32): the overview is
  padded by `overview.padding_dp` (72, optional in the file; absent = `preview.padding_dp`). K8 (item
  51): `onStopView(points)` in PREVIEW or a guide mode fits the points top-down, north up (guided:
  GUIDE_OVERVIEW with the vehicle in the box, re-fitted when the vehicle leaves the middle 80 %, at most
  every 5 s, `STOP_REFIT_MS`); a gesture leaves the camera until new points, Recenter or
  `recenter_after_s`; no K4/K5 return meanwhile; empty points: back to K2 or GUIDE_FOLLOW; a change of
  phase group ends it. Item 54b (0.1.41): the app also sends the route ahead while the Add stop search
  is typed in over the map, as `onStopView(points, paddingDp)` with its own padding (the app's 16 dp
  typing view; null: `preview.padding_dp`, as the stop results), kept with the view; no room for the
  fit (the free area less that padding on each side): the camera stays (`stopRoom`) until the insets
  give room again.
- **Guide pitch and heading (next run part 1).** `pitch_deg` 60 (was 50: MapLibre applied 50, but
  with its narrow field of view and the banner over the top quarter the view read as nearly
  top-down; docs/architecture.md 3.7 K3). The bearing filter's weight grows with the change, from
  `bearing_smoothing` to 1 at `bearing_turn_deg` (30): a turn is followed at the next position, the
  wobble of straight driving stays smoothed; the weight stays 1 up to 120 degrees in one position
  (a sharp corner) and falls smoothly back to 0.3 at 165, so a reversal (about 180) keeps 0.3.

## Tests

`sh tools/round/gradle.sh m :map:scene:test` (JUnit 5, virtual clock): `MapSceneMapperTest`
(choosing, guiding, traveled cursor, lenses, stale, pins, glideMs, saved markers of
`docs/architecture-saved-places.md` 10.1; 0.1.40: the step, the START pin, a stop's preview; 28 tests),
`CameraDirectorTest` (K1-K6, and K1's cap with
`withZoomCap`, the picked place and its sheet closing, the turn and a sharp corner; K7 is the mapper's;
K5's overview padding), `CameraRulesTest` (parse, refusals, bundled values, the
copy equal to its source; 6 tests), `CameraViewsTest` (0.1.40: K6 arrival frame, K2a after a closed
route, K1b tapped place, `pinInView`; 0.1.41: a tap while following and the one-press return; 12
tests), `StopViewCameraTest` (0.1.40: K8; 0.1.41: the room guard and the typing view's padding; 6
tests), `StopViewPaddingContractTest` (0.1.41's wave 0: the padding's default),
`ResultsCameraTest`, `SheetHeightsCameraTest`, `CompassTest`, `LeanTest`.

No log call and no `$` in a message anywhere in `src/main` (rule PV2 item 2).
