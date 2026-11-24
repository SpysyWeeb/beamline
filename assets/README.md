# assets: source artwork

The map icons, route shields and position markers, and the small data files the
app and the style read. The SVGs here are the source of truth; the sprite
sheets in `packbuilder/map/style-bundle/sprites/` and the marker PNGs are built
from them. Licences and the basis for the shields: `LICENSES.md`. Results and
measurements: `docs/experiments/06-map-art.md`.

| Path | What |
|---|---|
| `icons/icons.json` | every map icon: source (upstream or beamline), colour group, and the light/dark colour pairs per group |
| `icons/upstream/*.svg` | icons taken from the vector source of the bundled Protomaps set (`refill.svg`), one file each, same frame |
| `icons/beamline/*.svg` | icons drawn for this project in that frame: parking, hospital, pharmacy, bank, ATM, truck stop, welcome center, home, work, saved place |
| `shields/shields.json` | shields and which road network gets which (US:I, US:US, US:PA, fallback), numeral colours |
| `shields/beamline/*.svg` | US route and Pennsylvania keystone, 1 to 5 characters (drawn by `packbuilder/map/style/draw_shields.py`; edit the script) |
| `shields/upstream/*.svg` | the bundled Interstate and generic shields |
| `markers/*.svg`, `markers/SPEC.md` | position-marker pieces and how the app stacks, rotates and tilts them |
| `poi_icons.json` | search category id and map POI kind to icon name, so search results and the map show the same icon |
| `route_colors.json` | route line colours (three candidates for the owner), alternative and driven-part colours, widths by zoom; read by the app |
| `sheet/index.html` | contact sheet for the owner's approval, self-contained (built by `packbuilder/map/style/build_sheet.py`; not in git) |

## Template colours

Icons and shields are drawn in grey "template" colours. The sprite build turns
each colour channel `c` into `mix(pair[0], pair[1], c / 255)` with the colour
pair of the icon's group (the rule of Protomaps' own sprite generator), so one
SVG makes the day and the night sprite. New icons use the upstream values:
outline and symbol `#3f3f3f` (stroke 1 px), frame fill `#f4f4f4`, symbol
fills `#d3d3d3` or `#fff`, frame `rect x=0.5 y=0.5 width=18 height=18 rx=4`
in a `0 0 19 19` viewBox.
The three saved-place icons (`saved_home`, `saved_work`, `saved_place`) are filled instead: frame
`#000`, outline and glyph `#fff`, colour group `saved` (`docs/architecture-saved-places.md` 8).

## Rebuild

Needs, once (details in `docs/experiments/06-map-art.md`, section 11):

- Python 3.12 with `.venv/art` (`packbuilder/map/style/requirements-art.txt`, every package pinned), Node.js
  (tested with 24.18), and Microsoft Edge installed (the renders drive it headless through Playwright; no
  browser is downloaded);
- `python tools/fetch_art_tools.py` (spreet, resvg) and `python tools/fetch_art_sources.py` (upstream icons);
- from the map track: `data/map/style-src/` and `data/map/npm/` (`packbuilder/map/style/fetch_style_inputs.py`),
  the PA pack `data/map/packs/pa-261114-z0-15.pmtiles` (and `oh-261114-z0-15.pmtiles` for one Ohio view),
  `viewer/vendor/` (`tools/fetch_map_vendor.py`);
- from the routing track, for the sample routes only: `.venv/routing` and `data/routing/build/`.

Then, from the repo root:

```powershell
.venv/art/Scripts/python packbuilder/map/style/build_art.py --with-routes   # shields, import, sprites, markers, style, checks, sample routes, renders, sheet
```

Without `--with-routes` the sample routes are reused, and computed only if
`data/art/route/routes.geojson` does not exist yet. The script checks the inputs
of every step first and stops with a list of what is missing.
