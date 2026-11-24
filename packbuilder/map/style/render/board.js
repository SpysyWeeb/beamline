// A board of sprite images drawn by MapLibre itself with the style bundle's sprite
// sheet and glyphs, on the map's land colour: every icon (kind=icons) or every
// shield width with sample numbers (kind=shields), at real size (1 CSS px = 1 dp).
//
// URL parameters: kind=icons|shields  variant=day|night  cols=N
import * as maplibregl from "/viewer/vendor/maplibre-gl/maplibre-gl.mjs";
import { bindStyle } from "/viewer/compose_style.js";

const P = new URLSearchParams(location.search);
const state = (window.__art = { idle: false, errors: [], ready: false });
const variant = P.get("variant") || "day";
const kind = P.get("kind") || "icons";
const STYLE_BASE = new URL("/style", location.href).href;

async function json(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
  return r.json();
}

// lay points out on a grid in pixel space at zoom 0, converted to lon/lat
function grid(items, cols, cellW, cellH, map) {
  return items.map((it, i) => {
    const x = (kind === "shields" ? 120 : 40) + (i % cols) * cellW + cellW / 2, y = 30 + Math.floor(i / cols) * cellH + cellH / 2;
    const ll = map.unproject([x, y]);
    return { type: "Feature", properties: it, geometry: { type: "Point", coordinates: [ll.lng, ll.lat] } };
  });
}

async function main() {
  const template = bindStyle(await json(`${STYLE_BASE}/${variant}.json`), { packUrl: "unused", styleBaseUrl: STYLE_BASE });
  const land = template.layers.find((l) => l.id === "earth").paint["fill-color"];
  const shieldLayer = template.layers.find((l) => l.id === "roads_shields");
  const style = { version: 8, glyphs: template.glyphs, sprite: template.sprite, sources: {},
                  layers: [{ id: "bg", type: "background", paint: { "background-color": land } }] };
  const map = new maplibregl.Map({ container: "map", style, center: [0, 0], zoom: 0, interactive: false,
                                   attributionControl: false, fadeDuration: 0, renderWorldCopies: false });
  map.on("error", (e) => {
    const msg = String(e?.error?.message || e?.error || e);
    state.errors.push(msg);
    console.error(msg);
  });
  await new Promise((r) => map.once("load", r));
  const textColor = variant === "night" ? "#d0d0d0" : "#333333";
  const halo = land;
  let feats, layers;
  if (kind === "icons") {
    const icons = (await json("/assets/icons/icons.json")).icons;
    const names = Object.keys(icons).filter((n) => !["arrow", "townspot", "capital"].includes(n));
    const cols = Number(P.get("cols") || 10);
    feats = grid(names.map((n) => ({ icon: n, label: n.replace(/_/g, " "), isnew: icons[n].source === "beamline" ? "new" : "" })), cols, 100, 64, map);
    layers = [{
      id: "icons", type: "symbol", source: "pts",
      layout: { "icon-image": ["get", "icon"], "icon-allow-overlap": true, "text-allow-overlap": true,
                "text-field": ["format", ["get", "label"], {}, "\n", {}, ["get", "isnew"], { "font-scale": 0.9 }],
                "text-font": ["Noto Sans Regular"], "text-size": 10, "text-anchor": "top", "text-offset": [0, 1.2],
                "text-max-width": 9 },
      paint: { "text-color": textColor, "text-halo-color": halo, "text-halo-width": 1 },
    }];
  } else {
    const samples = { 1: "5", 2: "19", 3: "832", 4: "322A", 5: "LECT1" };
    const nets = [["US:I", "Interstate"], ["US:US", "US route"], ["US:PA", "PA route"], ["US:OH", "fallback (OH)"]];
    const items = [];
    for (const [net, label] of nets) {
      for (const n of [1, 2, 3, 4, 5]) items.push({ network: net, shield_text: samples[n], label: n === 1 ? label : "" });
    }
    feats = grid(items, 5, 70, 56, map);
    const lay = { ...shieldLayer.layout };
    delete lay["symbol-placement"];
    layers = [
      { id: "shields", type: "symbol", source: "pts",
        layout: { ...lay, "icon-allow-overlap": true, "text-allow-overlap": true }, paint: shieldLayer.paint },
      { id: "names", type: "symbol", source: "pts",
        layout: { "text-field": ["get", "label"], "text-font": ["Noto Sans Regular"], "text-size": 10,
                  "text-anchor": "right", "text-offset": [-2.4, 0], "text-allow-overlap": true },
        paint: { "text-color": textColor, "text-halo-color": halo, "text-halo-width": 1 } },
    ];
  }
  map.addSource("pts", { type: "geojson", data: { type: "FeatureCollection", features: feats } });
  for (const l of layers) map.addLayer(l);
  map.on("idle", () => { state.idle = true; });
  state.ready = true;
  map.triggerRepaint();
}

main().catch((e) => {
  state.errors.push(String(e));
  state.failed = true;  // render_art.py stops waiting at once instead of timing out
  console.error(String(e));
});
