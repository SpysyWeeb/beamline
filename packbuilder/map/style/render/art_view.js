// One map view for the art renders: the style bundle + a sample route in a candidate
// colour + the position marker, all from local files (render_art.py serves them).
//
// URL parameters:
//   pack=pa-261114-z0-15.pmtiles  variant=day|night  at=zoom/lat/lon  bearing=deg  pitch=deg
//   sample=<route sample in /data/route/routes.geojson>   cand=A|B|C (route colour candidate)
//   marker=arrow|arrow_stopped|puck|none   tag=<caption shown in the corner>
//   credit=<text>  (the credit on the map's vector source instead of the template's)
import * as maplibregl from "/viewer/vendor/maplibre-gl/maplibre-gl.mjs";
import { bindStyle } from "/viewer/compose_style.js";

const P = new URLSearchParams(location.search);
const state = (window.__art = { idle: false, errors: [], ready: false });
const protocol = new pmtiles.Protocol();
maplibregl.addProtocol("pmtiles", protocol.tile);

const variant = P.get("variant") || "day";
const theme = variant === "night" ? "night" : "day";
const STYLE_BASE = new URL("/style", location.href).href;
const at = (P.get("at") || "16/42.125/-80.087").split("/").map(Number);

function widths(spec, which) {
  const e = ["interpolate", ["exponential", spec.interpolation.base], ["zoom"]];
  for (const [z, w] of spec[which]) e.push(z, w);
  return e;
}

async function json(url) {
  const r = await fetch(url);
  if (!r.ok) throw new Error(`${url}: HTTP ${r.status}`);
  return r.json();
}

function loadImage(map, name, url) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      map.addImage(name, img, { pixelRatio: 3 });
      resolve();
    };
    img.onerror = () => reject(new Error(`image ${url}`));
    img.src = url;
  });
}

async function main() {
  const template = await json(`${STYLE_BASE}/${variant}.json`);
  const style = bindStyle(template, {
    packUrl: new URL(`/packs/${P.get("pack") || "pa-261114-z0-15.pmtiles"}`, location.href).href,
    styleBaseUrl: STYLE_BASE,
  });
  // credit=<text>: the pack's own credit on its source (render_art.py reads it from the pack's
  // metadata for views with "credit_from_pack"), as the app's credit line shows it; the template
  // names OpenStreetMap, which the public-domain overview does not need
  const credit = P.get("credit");
  if (credit) for (const s of Object.values(style.sources)) if (s.type === "vector") s.attribution = credit;
  const tag = document.getElementById("tag");
  tag.textContent = P.get("tag") || "";
  if (theme === "night") tag.className = "night";
  const map = new maplibregl.Map({
    container: "map", style, center: [at[2], at[1]], zoom: at[0],
    bearing: Number(P.get("bearing") || 0), pitch: Number(P.get("pitch") || 0),
    maxPitch: 70, attributionControl: { compact: true }, fadeDuration: 0,
  });
  window.__map = map;
  map.on("error", (e) => {
    const msg = String(e?.error?.message || e?.error || e);
    state.errors.push(msg);
    console.error(msg);
  });
  await new Promise((r) => map.once("load", r));

  const sample = P.get("sample");
  const cand = P.get("cand");
  const before = "roads_oneway";
  if (sample) {
    const rc = await json("/assets/route_colors.json");
    const routes = await json("/data/route/routes.geojson");
    const feats = routes.features.filter((f) => f.properties.sample === sample);
    const by = (role) => ({ type: "FeatureCollection", features: feats.filter((f) => f.properties.role === role) });
    const w = rc.widths_dp;
    const line = (id, src, color, width) => map.addLayer({
      id, type: "line", source: src,
      layout: { "line-cap": rc.line_cap, "line-join": rc.line_join },
      paint: { "line-color": color, "line-width": widths(w, width) },
    }, before);
    for (const role of ["alternative", "main", "traveled"]) map.addSource(`r-${role}`, { type: "geojson", data: by(role) });
    const alt = rc.alternative[theme];
    line("alt-casing", "r-alternative", alt.casing, "casing");
    line("alt-fill", "r-alternative", alt.fill, "fill");
    if (cand) {
      const c = rc.candidates[cand][theme];
      line("route-casing", "r-main", c.casing, "casing");
      line("route-fill", "r-main", c.fill, "fill");
      line("traveled-casing", "r-traveled", c.traveled.casing, "casing");
      line("traveled-fill", "r-traveled", c.traveled.fill, "fill");
    }
    const marker = P.get("marker") || "arrow";
    if (marker !== "none") {
      map.addSource("pos", { type: "geojson", data: by("arrow") });
      const stopped = marker === "arrow_stopped";
      let stack;
      if (marker === "puck") stack = [`puck_cone_${theme}`, `puck_${theme}`];
      else {
        stack = [];
        if (theme === "night") stack.push("arrow_beam");
        if (stopped) stack.push(`arrow_brake_glow_${theme}`);
        stack.push("arrow");
        if (stopped) stack.push("arrow_brakes_lit");
      }
      for (const n of stack) {
        await loadImage(map, `marker_${n}`, `/data/markers/res/drawable-xxhdpi/marker_${n}.png`);  // "arrow" alone is the one-way arrow of the sprite
        map.addLayer({
          id: `pos-${n}`, type: "symbol", source: "pos",
          layout: {
            "icon-image": `marker_${n}`,
            "icon-rotate": n.startsWith("puck_cone") ? Number(P.get("compass") || 30) : (n.startsWith("puck") ? 0 : ["get", "heading"]),
            "icon-rotation-alignment": "map",
            "icon-pitch-alignment": "map",
            "icon-allow-overlap": true,
            "icon-ignore-placement": true,
          },
        });
      }
    }
  }
  map.on("idle", () => { state.idle = true; });
  map.on("movestart", () => { state.idle = false; });
  state.ready = true;
  map.triggerRepaint();
}

main().catch((e) => {
  state.errors.push(String(e));
  state.failed = true;  // render_art.py stops waiting at once instead of timing out
  console.error(String(e));
});
