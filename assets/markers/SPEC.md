# Position markers: pieces and how the app uses them

Vector masters in this folder, built from the approved sketch
`docs/design/position-markers.html` (path data and colours of its script).
PNG exports for Android (mdpi to xxxhdpi, that is 1x to 4x) are made by
`packbuilder/map/style/build_markers.py` into
`data/art/markers/res/drawable-<density>/marker_<piece>.png`.

## Units and anchor

- One sketch unit = **0.8 dp** (the sketch's own "at phone size" scale). Each
  SVG declares its canvas in dp (`width`/`height`), with the sketch units as
  `viewBox`.
- Every piece sits on a **square canvas centred on the anchor**: the image
  centre is the vehicle position (the fix, or the fix snapped to the route).
  Pieces of different canvas sizes are stacked by centring them, and every
  rotation is about the image centre. All canvases are whole, even dp, so the
  centres line up at every density (also 1.5x).
- Pieces point **up** (north, heading 0).
- MapLibre symbol layer: `icon-anchor: center` (the default), no offset.

| Piece | Canvas | What | Shown |
|---|---|---|---|
| `puck_day`, `puck_night` | 64 dp | dot r = 11.2 dp in the route blue, white ring 4 dp (outer diameter 26.4 dp), soft shadow, faint halo r = 24 dp | browsing |
| `puck_cone_day`, `puck_cone_night` | 176 dp | 70-degree cone, 84 dp long, route blue fading from 66 % to 0 | browsing, when the compass heading is known |
| `puck_stale` | 64 dp | the puck in grey #8D96A0 | browsing, position stale |
| `arrow` | 136 dp | arrow 43 dp wide, 58 dp long: two facets (#7CBDFF left, #3D8CF0 right), centre ridge, thin rear face #1B54A6, unlit brake lenses #7A2620, white outline 3.2 dp, soft shadow | driving |
| `arrow_brakes_lit` | 136 dp | lit lenses #FF3B2F with their soft glow, and the white outline again on top (the sketch paints the outline last) | driving, stopped |
| `arrow_brake_glow_day`, `arrow_brake_glow_night` | 136 dp | red glow behind the rear (14 % day, 34 % night) | driving, stopped |
| `arrow_beam` | 360 dp | headlight beam #FFF3CF from the tip, about 140 dp long, no lamp | driving, night map only |
| `arrow_stale` | 136 dp | the arrow in greys, lenses dark | driving, position stale |

## Layer order (bottom to top)

All marker layers sit above the route line and above every map label.

- Browsing: `puck_cone_*`, then `puck_*`.
- Driving: `arrow_beam` (night map only), `arrow_brake_glow_*` (stopped only),
  `arrow`, `arrow_brakes_lit` (stopped only).
- Stale: `puck_stale` or `arrow_stale` alone.

## What rotates

- The **cone** turns with the compass heading (device azimuth from north):
  `icon-rotate` = azimuth, `icon-rotation-alignment: map`, so it stays true
  when the map turns.
- The **puck** does not rotate (it is round).
- The **arrow, beam, glow and lit lenses** turn together with the course (the
  GPS bearing, or the route's bearing at the snapped point): the same
  `icon-rotate` on all four layers, `icon-rotation-alignment: map`.

## Tilted view (guidance)

The map tilts and turns heading-up when guidance starts. All arrow pieces use
`icon-pitch-alignment: map`: they lie flat on the road and foreshorten with the
tilt, the beam lies on the road ahead of the tip, and the glow on the road
behind. Checked in MapLibre GL JS at pitch 50 (`data/art/views/route-*-i90-exit24.png`,
`check-stopped-exit24-night.png`). The baked shadow then reads as a soft
shadow on the road under the arrow, as in the sketch's tilted stage.
Browsing stays top-down; if the puck is ever shown on a tilted map, keep the
dot `icon-pitch-alignment: viewport` (round) and the cone `map` (suggestion,
not rendered).

`icon-pitch-alignment` and `icon-rotation-alignment` are supported by MapLibre
Native Android since 5.2.0 and 2.0.1 (style-spec `sdk-support` table); the app
runs 13.7.1. Native rendering of these layers was **not** tested here.

## States and switching (to settle with the Android track)

- **Brake lenses** lit only while the car is stopped, off as soon as it moves.
  Suggestion: stopped = speed under 0.5 m/s for 1 s; moving = over 1 m/s
  (the gap avoids flicker in slow traffic).
- **Beam** only with the night map style (Automatic follows the map, not the
  clock), never with the day map.
- **Stale**: no fix for a while (suggestion: 10 s, or accuracy worse than 100 m):
  grey piece, no cone, no beam, no lenses.
- **Route blue**: the puck and cone use the selected route fill
  (`assets/route_colors.json`). The masters carry the sketch's blue, which is
  candidate A; `build_markers.py --route-candidate B` exports the puck in B.
  The arrow keeps the sketch's colours (approved "same blue family").

## Android export sizes (px, square)

| Piece | mdpi 1x | hdpi 1.5x | xhdpi 2x | xxhdpi 3x | xxxhdpi 4x |
|---|---|---|---|---|---|
| puck_* | 64 | 96 | 128 | 192 | 256 |
| puck_cone_* | 176 | 264 | 352 | 528 | 704 |
| arrow, arrow_brakes_lit, arrow_brake_glow_*, arrow_stale | 136 | 204 | 272 | 408 | 544 |
| arrow_beam | 360 | 540 | 720 | 1080 | 1440 |

All 11 pieces at 5 densities: 0.62 MB of PNG (measured; most of it the beam).
In MapLibre, add each bitmap with its density (`pixelRatio`) so it is drawn at
its dp size.
