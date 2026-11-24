# Design references

Approved sketches. They are the reference for shapes, colours and behaviour;
the real assets and screens are built from them.

| File | What it shows |
|---|---|
| `position-markers.html` | The browsing puck and the driving arrow, in every state |
| `turn-banner.html` | The guidance banner for back-to-back turns with lane arrows |
| `app-icon/` | The app icon ("Night drive") as layered SVG, the splash scene, and the pages that show them |

Each file is a page fragment (styles, markup and a script, with no `<html>`
wrapper). Open it in a browser to look at it; the drawing code in its script
holds the exact path data and colour values.

## Decisions these sketches carry

Position markers
- Browsing: a puck in the route blue with a white ring, and a cone toward the
  compass direction that fades with distance.
- Driving: a slightly raised arrow (two facets, a centre ridge, a thin rear
  face). No visible headlight lamp; at night a soft beam starts at the tip.
  Two small brake lenses sit in the rear face at the outer corners.
- Brake lenses light only while the car is stopped.
- The headlight beam shows only with the night map.
- The arrow stays in the route's blue family.
- The map is top-down while browsing and tilts, heading up, when guidance
  starts.

App icon and splash
- "Night drive": the app's arrow on a night map, on its route, with the
  headlight beam ahead and the brake lights lit, in the lighter, bluer night
  palette (chosen over the near-black first version, which vanished on dark
  wallpapers). No lettering.
- `icon-background.svg` (map, roads, route) and `icon-foreground.svg` (beam,
  glow, arrow) are the two layers of an Android adaptive icon;
  `icon-monochrome.svg` is the single-colour version for themed icons;
  `icon-full.svg` is the two layers together. The foreground uses blur, which
  Android vector drawables cannot do, so export it as bitmaps per density.
- No rim: each launcher cuts the icon to its own shape, so a rim drawn into
  the artwork would be clipped.
- The splash is a reveal, not a zoom. It starts as the icon in its circle on
  the plain map colour (`splash-start.svg`). A circle centred on the icon opens
  outward until the scene fills the screen (`splash-scene.svg`). Nothing is
  scaled or moved: the arrow, beam and brake lights stay put. Side streets and
  the route's turns are uncovered, and buildings, the park and the river fade
  in across the whole scene. About 0.7 seconds in all, then the real map takes
  over. `night-drive-reveal.html` plays it.
- Rejected: "Blue field" (a white arrow on blue), because it is nearly
  identical to the Spark Mail icon; "Lanes", because it shows a feature that
  does not exist yet. `candidates.html` shows all three.

Map artwork (approved 2026-11-15 from the contact sheet built by
`packbuilder/map/style/build_sheet.py`)
- Route line: candidate B, "Clear" (day fill #4A8CF7 on casing #1F5BC4, night
  fill #3A80F0 on casing #1C3F86). `assets/route_colors.json` holds it.
- The seven icons drawn for this project (parking, hospital, pharmacy, bank,
  ATM, truck stop, welcome center), the US route shield and the Pennsylvania
  keystone shield are approved as drawn.

Name
- The app is called Beamline (one word, lower-case l). Its Android
  application id is `io.github.spysyweeb.beamline`.

Other decisions
- Eco is not offered for now. The route types are Fastest, Shortest and
  Simpler, plus Avoid highways and Avoid tolls. Eco returns when it can use
  hills and speed properly.
- House-number data is downloaded with a state by default and can be skipped
  to save space.

Turn banner
- The following turn sits beside the main instruction, with the distance from
  the first turn to the second under its arrow ("In 0.2 mi").
- It appears when the following turn is within 0.5 mile and within 1 minute of
  the next one, using the route's own time estimate.
- Lane arrows have three states: best lane, also allowed, not for this route.
  The best lane takes the following turn into account.
- No lane row where the map has no lane data. Nothing is guessed.
- No explanatory text under the lane row.
- Guidance is silent by default. Voice and a chime are later options; a spoken
  "then" would chain only within 0.25 mile, and a lane hint would be spoken
  only when more than one lane is allowed and one is better.
