# viewer: offline map browser

A static page that shows Beamline packs with MapLibre GL JS. It needs no
network: MapLibre GL JS and the pmtiles library are vendored in `vendor/`, the
style bundle comes from `packbuilder/map/style-bundle/`, and packs are local
files. It is the test bench for the map track and the start of a Windows map
browser.

```powershell
python viewer/serve.py                 # http://127.0.0.1:8765/
python viewer/serve.py --packs D:/packs --port 9000
```

`serve.py` (Python standard library only) is needed because PMTiles are read
with HTTP Range requests, which `python -m http.server` does not answer. It
binds to 127.0.0.1 only, answers GET and HEAD, and streams files in 1 MiB
chunks (a full 467 MB GET peaked at 24 MiB in the server process).

In the page: pick one or more packs from `data/map/packs/` (Ctrl+click for
several), day or night style, how several packs are combined, or open any
`.pmtiles` file from disk ("Open a .pmtiles file" reads it in place with the
browser's File API, nothing is uploaded; the map then frames the pack's header
bounds, or uses its header centre if the bounds span more than half the
world). "Click to inspect" lists the features and attributes under the
pointer.

URL parameters: `packs=a.pmtiles,b.pmtiles`, `variant=day|night`,
`order=interleaved|grouped`, `base=c.pmtiles&basemax=8`, `at=zoom/lat/lon`, `ui=0`.

## Files

| File | What |
|---|---|
| `index.html`, `app.js` | the page (UI only) |
| `compose_style.js` | pure functions: fill the style template, compose one style for several packs |
| `serve.py` | local server with Range support |
| `vendor/maplibre-gl/` | MapLibre GL JS 6.16.0, BSD-3-Clause (`LICENSE.txt`) |
| `vendor/pmtiles/` | pmtiles 4.5.0 browser bundle, BSD-3-Clause (`LICENSE`); bundles fflate, MIT (`fflate-LICENSE`) |

The vendored files are produced by `python tools/fetch_map_vendor.py`, which
pins each npm tarball by SHA-256. MapLibre GL JS 6 is ESM-only and needs WebGL 2.
