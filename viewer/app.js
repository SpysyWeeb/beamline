// Beamline offline viewer. Everything is loaded from the local server (viewer/serve.py):
// MapLibre GL JS and pmtiles from vendor/, the style bundle from /style/, packs from /packs/.
//
// URL parameters (all optional):
//   packs=a.pmtiles,b.pmtiles   packs to show (default: the first one listed in /packs/)
//   variant=day|night           style variant (default day)
//   order=interleaved|grouped   layer order when several packs are shown
//   base=c.pmtiles&basemax=8    a low-zoom base pack drawn only below zoom `basemax`
//   at=zoom/lat/lon             initial view (the #map= hash also works)
//   ui=0                        hide the panel (used for screenshots)
import * as maplibregl from "./vendor/maplibre-gl/maplibre-gl.mjs";
import { bindStyle, composeMultiPack } from "./compose_style.js";

const params = new URLSearchParams(location.search);
const state = (window.__viewer = { idle: false, errors: [], loads: 0 });
const protocol = new pmtiles.Protocol();
maplibregl.addProtocol("pmtiles", protocol.tile);

const STYLE_BASE = new URL("style", location.href).href;
const packUrl = (name) => new URL(`packs/${encodeURIComponent(name)}`, location.href).href;
const $ = (id) => document.getElementById(id);
const templates = {};

async function template(variant) {
  if (!templates[variant]) {
    const r = await fetch(`${STYLE_BASE}/${variant}.json`);
    if (!r.ok) throw new Error(`style ${variant}: HTTP ${r.status}`);
    templates[variant] = await r.json();
  }
  return templates[variant];
}

async function buildStyle({ packs, variant, order, base, basemax, fileName }) {
  const t = await template(variant);
  if (fileName) return bindStyle(t, { packUrl: fileName, styleBaseUrl: STYLE_BASE });
  const list = packs.map((p) => ({ id: p.replace(/\.pmtiles$/, ""), url: packUrl(p) }));
  if (base) {
    list.unshift({ id: "base", url: packUrl(base), maxzoom: basemax });
    for (const p of list.slice(1)) p.minzoom = basemax;
  }
  if (list.length === 1) return bindStyle(t, { packUrl: list[0].url, styleBaseUrl: STYLE_BASE });
  const style = composeMultiPack(t, list, { styleBaseUrl: STYLE_BASE, order });
  style.layers = style.layers.filter((l) => (l.minzoom ?? 0) < (l.maxzoom ?? 24));
  return style;
}

function status(text) {
  $("status").textContent = text;
}

async function main() {
  let available = [];
  try {
    available = await (await fetch("packs/index.json")).json();
  } catch (e) {
    state.errors.push(`pack list: ${e}`);
  }
  for (const p of available) $("packs").add(new Option(p, p));
  const wanted = (params.get("packs") || available[0] || "").split(",").filter(Boolean);
  for (const o of $("packs").options) o.selected = wanted.includes(o.value);
  $("variant").value = params.get("variant") || "day";
  $("order").value = params.get("order") || "interleaved";
  if (params.get("ui") === "0") $("panel").style.display = "none";

  const opts = () => ({
    packs: [...$("packs").selectedOptions].map((o) => o.value),
    variant: $("variant").value,
    order: $("order").value,
    base: params.get("base"),
    basemax: Number(params.get("basemax") || 8),
    fileName: state.fileName,
  });
  document.documentElement.dataset.theme = $("variant").value === "night" ? "dark" : "light";

  const at = (params.get("at") || "").split("/").map(Number);
  const map = new maplibregl.Map({
    container: "map",
    style: await buildStyle(opts()),
    hash: params.has("at") ? false : "map",
    center: at.length === 3 ? [at[2], at[1]] : [-77.6, 40.9],
    zoom: at.length === 3 ? at[0] : 6.5,
    maxZoom: 19,
    attributionControl: { compact: false },
  });
  window.__map = map;
  map.addControl(new maplibregl.NavigationControl(), "top-right");
  map.addControl(new maplibregl.ScaleControl({ unit: "imperial" }), "bottom-left");
  map.on("error", (e) => {
    const msg = String(e?.error?.message || e?.error || e);
    state.errors.push(msg);
    console.error(msg);
  });
  map.on("idle", () => {
    state.idle = true;
    state.loads++;
    status(`zoom ${map.getZoom().toFixed(2)}  layers ${map.getStyle().layers.length}` +
      (state.errors.length ? `\n${state.errors.length} error(s): ${state.errors.at(-1)}` : ""));
  });
  map.on("movestart", () => (state.idle = false));

  const restyle = async () => {
    state.idle = false;
    document.documentElement.dataset.theme = $("variant").value === "night" ? "dark" : "light";
    map.setStyle(await buildStyle(opts()));
  };
  for (const id of ["packs", "variant", "order"]) $(id).addEventListener("change", () => {
    state.fileName = null;
    restyle();
  });
  $("file").addEventListener("change", async (e) => {
    const f = e.target.files[0];
    if (!f) return;
    const archive = new pmtiles.PMTiles(new pmtiles.FileSource(f));
    protocol.add(archive);
    state.fileName = f.name;
    const h = await archive.getHeader();
    // Packs made by build_pack.py carry their clip polygon's box as header bounds.
    // A box wider than half the world (a US pack with the Aleutians) is no use for
    // framing, so use the header centre and centre zoom then.
    if (h.maxLon - h.minLon < 180) {
      map.fitBounds([[h.minLon, h.minLat], [h.maxLon, h.maxLat]], { animate: false, padding: 20 });
    } else {
      map.jumpTo({ center: [h.centerLon, h.centerLat], zoom: h.centerZoom });
    }
    restyle();
  });
  $("tiles").addEventListener("change", (e) => (map.showTileBoundaries = e.target.checked));
  $("toggle").addEventListener("click", () => $("panel").classList.toggle("hidden"));
  map.on("click", (e) => {
    if (!$("inspectOn").checked) return;
    const feats = map.queryRenderedFeatures(e.point).slice(0, 12);
    $("inspect").style.display = "block";
    $("inspect").textContent = feats.map((f) =>
      `${f.layer.id}  [${f.sourceLayer}]\n` +
      Object.entries(f.properties).map(([k, v]) => `  ${k}: ${v}`).join("\n")).join("\n\n") || "nothing here";
  });
}

main().catch((e) => {
  state.errors.push(String(e));
  status(String(e));
});
