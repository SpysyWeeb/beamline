# packbuilder/map/style: style generator and artwork pipeline

The map engineer's generator (`fetch_style_inputs.py`, `generate_style.mjs`,
`style_config.json`, `check_native_support.mjs`) plus the artwork pipeline
that feeds it sprites built from `assets/`. Reports: `docs/experiments/02-map.md`
(style bundle) and `docs/experiments/06-map-art.md` (artwork).

| Script | Does |
|---|---|
| `import_upstream_icons.py` | cuts the upstream icons out of Protomaps' `refill.svg` into `assets/icons/upstream/`, `assets/shields/upstream/` |
| `draw_shields.py` | draws the US route and PA keystone shields into `assets/shields/beamline/` |
| `build_sprites.py` | recolours and packs all icons and shields with spreet into `data/art/sprites/` (1x, 2x, light, dark); `--compare-v4` checks against the bundled v4 sheets |
| `build_markers.py` | exports the marker pieces as Android PNGs (1x-4x) and composes the states |
| `generate_style.mjs` | writes `../style-bundle/` (styles, glyphs, sprites from `data/art/sprites/`); checks label contrast, shield numerals and that every icon exists |
| `check_poi_icons.py` | every search category (`packbuilder/search/categories.json`) has an icon |
| `check_truck_stops.py` | how often the map's truck-stop name rule agrees with search's `truck_stop` category (reads the search track's PA index) |
| `count_pois.py` | POI symbols MapLibre places per zoom (z11-z17) in town and on the highway |
| `tile_inventory.py` | what the packs carry: shield networks and lengths, POI kinds and `min_zoom` |
| `sample_routes.py` | sample routes for the renders (uses the routing track's `.venv/routing` and tiles, read-only) |
| `render_art.py` | headless Edge renders of map views (`art_views.json`, pages in `render/`) and of HTML pages |
| `build_sheet.py` | the contact sheet `assets/sheet/index.html` |
| `build_art.py` | runs all of the above in order, with wall time and peak memory per step |

Order: `fetch_style_inputs.py` (glyphs, npm package) and `tools/fetch_art_tools.py`,
`tools/fetch_art_sources.py` once; then `build_art.py`, or the steps one by one.

Round 3 (track P): below z7, where only the overview map is drawn, interstates and state lines get
a colour and width of their own (`style_config.json` "overview_legibility", applied and
contrast-checked by `generate_style.mjs`). `art_views.json` has the overview views
`overview-{day,night}-z{3.5,5}` (a view's `"pack": "v2/<file>"` reads `data/map/packs-v2`), and
`count_pois.py --pack v2/pa-261114-z14.pmtiles` counts the POIs of the z14 pack. When `build_art.py`
must not run (its `markers` step writes track M's folder), run the steps one by one:
`build_sprites.py`, `node generate_style.mjs`, `android/scripts/sync_assets.py`, `build_sheet.py`,
`render_art.py page assets/sheet/index.html data/art/sheet/sheet.png --width 1240 --dpr 2 --tiles --clip-height 1600`.
