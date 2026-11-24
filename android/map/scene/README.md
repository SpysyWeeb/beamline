# :map:scene

Plain Kotlin/JVM (no Android): what the map draws and where the camera goes, from the trip, the
puck and the saved places. Contract: `docs/architecture.md` 2.5, 3.6, 3.7 and
`docs/architecture-saved-places.md` 2.8, 5.2. Owner during the feature round: track M.

| File | What |
|---|---|
| `Scene.kt` | The contract file (stubs filled): `MapSceneMapper`, `SavedMarkerRules`, `CameraRules`, `CameraDirector`. Each class's private state is one `private val` after its last function. |
| `SceneBuilder.kt` | The mapper: route lines, pins, puck or arrow, saved markers. |
| `Director.kt` | The camera rules K1-K7. |
| `CameraFile.kt` | Reads `camera.json` (format `beamline-camera/1`); every refusal is a fixed sentence (rule PV2). |
| `Motion.kt` | The glide (time between the last two vehicle positions), the bearing low-pass on the circle, small geometry. |
| `ZoomCap.kt` | `CameraTarget.withZoomCap(zoomFor)`: rule K1's z9.99 cap outside the states, applied by the app with `ComposedMap.zoomFor` (the director cannot know the installed maps). |
| `src/main/resources/.../camera.json` | Copy of `packbuilder/routing/camera.json`: `python android/routing/scripts/sync_data.py --only camera.json` (`--check` compares). |

## How the app uses it

One `MapSceneMapper()` and one `CameraDirector(CameraRules.bundled(), clock)` per map surface.

- `mapper.scene(trip, puck, theme, picked, saved)` on every trip, puck, theme, picked-place or saved
  change; give the result to `MapController.show`.
- Feed the director: `onViewport(w, h, insets)` (insets from the drive overlay), `onTrip`, `onPuck`,
  `onGesture` (from `MapController.gestures`), `onMyLocation`, `onRecenter`, `onOverview`, and
  `tick()` once a second. Apply every non-null `target` with `MapController.moveCamera`; `state`
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
- **Guide pitch and heading (next run part 1).** `pitch_deg` 60 (was 50: MapLibre applied 50, but
  with its narrow field of view and the banner over the top quarter the view read as nearly
  top-down; docs/architecture.md 3.7 K3). The bearing filter's weight grows with the change, from
  `bearing_smoothing` to 1 at `bearing_turn_deg` (30): a turn is followed at the next position, the
  wobble of straight driving stays smoothed; the weight stays 1 up to 120 degrees in one position
  (a sharp corner) and falls smoothly back to 0.3 at 165, so a reversal (about 180) keeps 0.3.

## Tests

`sh tools/round/gradle.sh m :map:scene:test` (JUnit 5, virtual clock): `MapSceneMapperTest`
(choosing, guiding, traveled cursor, lenses, stale, pins, glideMs, saved markers of
`docs/architecture-saved-places.md` 10.1; 23 tests), `CameraDirectorTest` (K1-K6, and K1's cap with
`withZoomCap`, the picked place and its sheet closing, the turn and a sharp corner; K7 is the mapper's), `CameraRulesTest` (parse, refusals, bundled values, the
copy equal to its source; 6 tests).

No log call and no `$` in a message anywhere in `src/main` (rule PV2 item 2).
