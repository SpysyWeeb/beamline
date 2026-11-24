// Generate the offline style bundle (day + night MapLibre styles, glyphs, sprites).
//
// Usage (from the repo root, after fetch_style_inputs.py and build_sprites.py):
//     node packbuilder/map/style/generate_style.mjs
//
// Reads  packbuilder/map/style/style_config.json and the pinned @protomaps/basemaps
//        package unpacked in data/map/style-src/ by fetch_style_inputs.py (glyphs too)
//        assets/icons/icons.json, assets/shields/shields.json, assets/poi_icons.json
//        data/art/sprites/ (sprite sheets built from assets/ by build_sprites.py)
// Writes packbuilder/map/style-bundle/{day,night}.json, glyphs/, sprites/, LICENSES.md
//
// The style JSON is a template. Before handing it to MapLibre the app (or the
// viewer) replaces two placeholders:
//   {{PACK_URL}}        e.g. file:///data/user/0/app/files/packs/pa.pmtiles  (Android)
//                       or   http://127.0.0.1:8765/packs/pa.pmtiles          (viewer)
//                       giving "pmtiles://file:///data/.../pa.pmtiles"
//   {{STYLE_BASE_URL}}  where this bundle lives, e.g. asset://style or file:///.../style-bundle
// Nothing in the generated style refers to the network.
//
// Checks (the script stops on any failure):
//   - WCAG contrast of every overridden label colour against its halo
//     (style_config.json label_overrides; night street labels at least 7:1),
//     of every driving-POI and stock-POI label colour against the POI halo
//     (label_overrides.stock_pois recolours the stock ones), and of every
//     shield's numerals against the shield fill;
//   - every icon name the styles can ask for exists in the sprite sheets.

import { readFileSync, writeFileSync, mkdirSync, copyFileSync, rmSync, existsSync, readdirSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO = resolve(HERE, "..", "..", "..");
const cfg = JSON.parse(readFileSync(join(HERE, "style_config.json"), "utf8"));
const versions = JSON.parse(readFileSync(join(HERE, "..", "versions.json"), "utf8"));
const iconsCfg = JSON.parse(readFileSync(join(REPO, "assets", "icons", "icons.json"), "utf8"));
const shieldsCfg = JSON.parse(readFileSync(join(REPO, "assets", "shields", "shields.json"), "utf8"));
const poiIcons = JSON.parse(readFileSync(join(REPO, "assets", "poi_icons.json"), "utf8"));
const npm = versions.protomaps_styles_npm;
const SRC = join(REPO, "data", "map", "style-src");
const SPRITES = join(REPO, "data", "art", "sprites");
const UPSTREAM = join(REPO, "data", "art", "upstream");
const OUT = join(HERE, "..", "style-bundle");
const pkgEntry = join(SRC, `protomaps-basemaps-${npm.version}`, "package", "dist", "esm", "index.js");
if (!existsSync(pkgEntry)) {
  console.error("missing style inputs: run packbuilder/map/style/fetch_style_inputs.py first");
  process.exit(1);
}
for (const s of cfg.sprites) {
  if (!existsSync(join(SPRITES, `${s}@2x.json`))) {
    console.error("missing sprite sheets: run packbuilder/map/style/build_sprites.py first");
    process.exit(1);
  }
}
const { layers, namedFlavor } = await import(pathToFileURL(pkgEntry).href);

// Deep-replace font stack names (the stock style switches to a Devanagari font
// for Devanagari names; a US-only bundle does not ship that font).
function replaceFonts(value) {
  if (Array.isArray(value)) return value.map(replaceFonts);
  if (value && typeof value === "object") {
    const o = {};
    for (const [k, v] of Object.entries(value)) o[k] = replaceFonts(v);
    return o;
  }
  if (typeof value === "string" && cfg.font_replacements[value]) return cfg.font_replacements[value];
  return value;
}

// WCAG 2 contrast ratio of two #rrggbb colours.
function contrast(a, b) {
  const lum = (hex) => {
    const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255)
      .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  };
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

function checkContrast(what, fg, bg, min) {
  const r = contrast(fg, bg);
  console.log(`${what}: ${fg} on ${bg}: contrast ${r.toFixed(2)} (min ${min})`);
  if (r < min) throw new Error(`${what}: contrast ${r.toFixed(2)} is below ${min}`);
}

// Below cfg.overview_legibility.below_zoom only the overview map is drawn: interstates and state
// lines get a colour that stands out from the earth (checked) and wider lines; from that zoom up
// the stock paint is kept (a step on the colour, the stock width stops from that zoom on).
function applyOverviewLegibility(variant, ls) {
  const ov = cfg.overview_legibility;
  if (!ov) return;
  const z = ov.below_zoom;
  const earth = ls.find((l) => l.id === "earth")?.paint?.["fill-color"];
  if (typeof earth !== "string") throw new Error("earth fill-color changed upstream; check overview_legibility");
  const patch = (id, spec, keepStockWidth) => {
    const l = ls.find((x) => x.id === id);
    if (!l) throw new Error(`overview_legibility: no layer ${id}`);
    checkContrast(`${variant}: ${id} below z${z}`, spec[variant], earth, ov.min_contrast);
    l.paint["line-color"] = ["step", ["zoom"], spec[variant], z, l.paint["line-color"]];
    const low = spec.width.flat();
    const w = l.paint["line-width"];
    if (keepStockWidth && Array.isArray(w) && w[0] === "interpolate") {
      // stock stops at or above z stay, the low ones are replaced
      const stops = [];
      for (let i = 3; i < w.length; i += 2) if (w[i] >= z) stops.push(w[i], w[i + 1]);
      l.paint["line-width"] = [w[0], w[1], w[2], ...low, ...stops];
    } else {
      l.paint["line-width"] = ["interpolate", ["linear"], ["zoom"], ...low, z, typeof w === "number" ? w : 0.4];
    }
  };
  patch("roads_highway", ov.highway, true);
  patch("boundaries", ov.state_lines, false);
}

// Driving points of interest, drawn with icons from assets/poi_icons.json.
// Placed above the stock POI layer so that they win label collisions.
function drivingLayer(variant, flavorName, source) {
  const t = cfg.driving_tweaks;
  const dark = flavorName === "dark";
  const kinds = Object.keys(poiIcons.map_kinds);
  const early = poiIcons.early_kinds;
  const iconMatch = ["match", ["get", "kind"]];
  const byIcon = {};
  for (const [kind, icon] of Object.entries(poiIcons.map_kinds)) (byIcon[icon] ||= []).push(kind);
  for (const [icon, ks] of Object.entries(byIcon)) iconMatch.push(ks.length === 1 ? ks[0] : ks, icon);
  iconMatch.push(t.missing_icon_fallback);
  // A fuel or services POI with a truck-stop name gets the truck_stop icon (the tiles have no
  // brand or hgv tag). Lower-cased name equal to a starts_with entry or beginning with it and a
  // space ("ta", "ta express ...", not "taco ..."), or containing a contains entry. The same rule
  // in Python: check_truck_stops.py.
  const tn = poiIcons.truck_stop_names;
  const n = ["var", "n"];
  const truck = ["let", "n", ["downcase", ["coalesce", ["get", "name"], ""]], ["any",
    ...tn.starts_with.map((s) => ["any", ["==", n, s], ["==", ["index-of", `${s} `, n], 0]]),
    ...tn.contains.map((s) => ["in", s, n])]];
  const icon = ["case", ["all", ["in", ["get", "kind"], ["literal", tn.kinds]], truck], "truck_stop", iconMatch];
  const labelColors = iconsCfg.label_colors[variant];
  const halo = dark ? t.poi_halo.night : t.poi_halo.day;
  const colorMatch = ["match", ["get", "kind"]];
  const byColor = {};
  for (const [kind, ic] of Object.entries(poiIcons.map_kinds)) {
    const group = iconsCfg.icons[ic].color;
    (byColor[group] ||= []).push(kind);
  }
  for (const [group, ks] of Object.entries(byColor)) {
    checkContrast(`${variant}: pois_driving label (${group})`, labelColors[group], halo, cfg.label_overrides.min_contrast);
    colorMatch.push(ks.length === 1 ? ks[0] : ks, labelColors[group]);
  }
  colorMatch.push(labelColors.base);
  return {
    id: "pois_driving", type: "symbol", source, "source-layer": "pois",
    filter: ["all",
      ["in", ["get", "kind"], ["literal", kinds]],
      [">=", ["zoom"], ["case", ["in", ["get", "kind"], ["literal", early]],
        ["-", ["get", "min_zoom"], t.early_kinds_zoom_advance], ["get", "min_zoom"]]]],
    layout: {
      "symbol-sort-key": ["case", ["in", ["get", "kind"], ["literal", early]], 0, 1],
      "icon-image": icon,
      "icon-size": 1,
      "text-field": ["coalesce", ["get", "name:en"], ["get", "name"], ""],
      "text-font": ["Noto Sans Regular"],
      "text-size": ["interpolate", ["linear"], ["zoom"], 15, 10, 19, 14],
      "text-max-width": 8,
      "text-offset": [1.1, 0],
      "text-variable-anchor": ["left", "right"],
      "text-justify": "auto",
      "text-optional": true,
    },
    paint: {
      "text-color": colorMatch,
      "text-halo-color": halo,
      "text-halo-width": 1.5,
    },
  };
}

// Route shields by network (assets/shields/shields.json): icon, numeral colour.
function patchShields(variant, layer) {
  const s = cfg.shield_style;
  const nameFor = (shield) => ["concat", `${shield}-`, ["length", ["get", "shield_text"]], "char"];
  const icon = ["match", ["get", "network"]];
  const color = ["match", ["get", "network"]];
  for (const rule of shieldsCfg.networks) {
    icon.push(rule.networks, nameFor(rule.shield));
    color.push(rule.networks, rule.text_color[variant]);
    checkContrast(`${variant}: shield ${rule.shield} numerals`, rule.text_color[variant], rule.halo_check[variant], cfg.label_overrides.min_contrast);
  }
  const fb = shieldsCfg.fallback;
  icon.push(nameFor(fb.shield));
  color.push(fb.text_color[variant]);
  checkContrast(`${variant}: shield ${fb.shield} numerals`, fb.text_color[variant], fb.halo_check[variant], cfg.label_overrides.min_contrast);
  layer.layout["icon-image"] = icon;
  layer.layout["icon-size"] = s.icon_size;
  layer.layout["text-size"] = s.text_size;
  layer.paint = { ...layer.paint, "text-color": color };
}

// Paint overrides from style_config.json "label_overrides", with a contrast check.
function applyLabelOverrides(variant, ls) {
  const lo = cfg.label_overrides;
  for (const [id, paint] of Object.entries(lo[variant] || {})) {
    const l = ls.find((x) => x.id === id);
    if (!l) throw new Error(`label_overrides.${variant}: no layer '${id}'`);
    const { _min_contrast, ...props } = paint;
    l.paint = { ...l.paint, ...props };
    const fg = l.paint["text-color"], halo = l.paint["text-halo-color"];
    if (typeof fg === "string" && typeof halo === "string") {
      checkContrast(`${variant}: ${id}`, fg, halo, _min_contrast ?? lo.min_contrast);
    }
  }
}

// The stock pois layer: shops and cafes not before stock_poi_min_zoom.zoom (driving POIs come
// first), and its label colours from label_overrides.stock_pois, each checked against the halo.
function patchStockPois(variant, layer) {
  const late = cfg.driving_tweaks.stock_poi_min_zoom;
  const f = layer.filter;
  const zoomTest = f?.[2];
  if (f?.[0] !== "all" || f.length !== 3 || JSON.stringify(zoomTest?.slice(0, 2)) !== '[">=",["zoom"]]') {
    throw new Error("stock pois filter changed upstream; check stock_poi_min_zoom");
  }
  const minZoom = zoomTest[2];
  f[2] = [">=", ["zoom"], ["case", ["in", ["get", "kind"], ["literal", late.kinds]], ["max", minZoom, late.zoom], minZoom]];
  const map = cfg.label_overrides.stock_pois[variant] || {};
  const tc = layer.paint["text-color"];
  if (tc?.[0] !== "case") throw new Error("stock pois text-color is no longer a case expression");
  const halo = layer.paint["text-halo-color"];
  const covered = new Set();
  for (let i = 1; i < tc.length - 1; i += 2) {
    const cond = tc[i];
    if (cond[0] !== "in" || cond[2]?.[0] !== "literal") throw new Error("unexpected stock pois colour condition");
    cond[2][1].forEach((k) => covered.add(k));
    tc[i + 1] = map[tc[i + 1]] ?? tc[i + 1];
    checkContrast(`${variant}: pois label (${cond[2][1].slice(0, 2).join(", ")}, ...)`, tc[i + 1], halo, cfg.label_overrides.min_contrast);
  }
  // the fallback colour is never used when every kind the filter lets through has a case of its own
  const kinds = f[1]?.[2]?.[1] || [];
  const uncovered = kinds.filter((k) => !covered.has(k));
  if (uncovered.length) throw new Error(`stock pois kinds without a label colour of their own: ${uncovered.join(", ")}`);
}

// Every icon name an icon-image expression can produce, given the layer's filter.
function iconNames(layer) {
  const out = new Set();
  const kindsFromFilter = () => {
    const f = JSON.stringify(layer.filter || []);
    const m = f.match(/\["in",\["get","kind"\],\["literal",(\[[^\]]*\])\]\]/);
    return m ? JSON.parse(m[1]) : [];
  };
  const walk = (e) => {
    if (typeof e === "string") return [e];
    if (!Array.isArray(e)) return [];
    const op = e[0];
    if (op === "match") {
      const r = [];
      const labels = new Set();
      for (let i = 2; i < e.length - 1; i += 2) [e[i]].flat().forEach((x) => labels.add(x));
      for (let i = 3; i < e.length - 1; i += 2) r.push(...walk(e[i]));
      const fb = e.at(-1);
      // match on kind with the kind itself as fallback: the matched labels never reach the fallback
      if (Array.isArray(fb) && fb[0] === "get" && JSON.stringify(e[1]) === JSON.stringify(fb)) {
        return r.concat(walk(fb).filter((k) => !labels.has(k)));
      }
      return r.concat(walk(fb));
    }
    if (op === "case") {
      const r = [];
      for (let i = 2; i < e.length - 1; i += 2) r.push(...walk(e[i]));
      return r.concat(walk(e.at(-1)));
    }
    if (op === "step") {
      const r = walk(e[2]);
      for (let i = 4; i < e.length; i += 2) r.push(...walk(e[i]));
      return r;
    }
    if (op === "concat") {
      let acc = [""];
      for (const part of e.slice(1)) {
        const opts = Array.isArray(part) && part[0] === "length" ? ["1", "2", "3", "4", "5"] : walk(part);
        acc = acc.flatMap((a) => opts.map((o) => a + o));
      }
      return acc;
    }
    if (op === "get" && e[1] === "kind") return kindsFromFilter();
    return [];
  };
  for (const n of walk(layer.layout["icon-image"])) if (n !== "") out.add(n);
  return out;
}

function buildStyle(variant, spec) {
  const source = "protomaps";
  let ls = layers(source, namedFlavor(spec.protomaps_flavor), { lang: cfg.lang });
  ls = replaceFonts(ls);
  const sprite = JSON.parse(readFileSync(join(SPRITES, `${spec.sprite}.json`), "utf8"));
  for (const l of ls) {
    if (l.id === "roads_oneway") l.minzoom = cfg.driving_tweaks.oneway_arrows_minzoom;
    if (l.id === "roads_shields") patchShields(variant, l);
    if (l.id === "pois") patchStockPois(variant, l);
    // The stock pois layer uses the kind as icon name. Every kind it draws now has an
    // icon in the sprite (town halls included), so the old fallback to "building" is
    // gone; the sprite check below stops the build if a kind ever lacks one.
  }
  applyLabelOverrides(variant, ls);
  applyOverviewLegibility(variant, ls);
  // One-way arrows for roads drawn against the direction of travel (oneway=-1).
  const owIndex = ls.findIndex((l) => l.id === "roads_oneway");
  const ow = ls[owIndex];
  if (ow.filter?.[0] !== "==" || ow.filter?.[2] !== "yes") throw new Error("roads_oneway filter changed upstream; check the reverse arrow layer");
  const rev = structuredClone(ow);
  rev.id = "roads_oneway_reverse";
  rev.filter = ["in", ["get", "oneway"], ["literal", cfg.driving_tweaks.oneway_reverse_values]];
  rev.layout["icon-rotate"] = (ow.layout["icon-rotate"] ?? 0) + 180;
  ls.splice(owIndex + 1, 0, rev);
  // Driving POIs just above the stock POI layer: placed first, so they win collisions.
  const poiIndex = ls.findIndex((l) => l.id === "pois");
  const stockKinds = new Set(JSON.parse(JSON.stringify(ls[poiIndex].filter)).flat(4));
  for (const k of Object.keys(poiIcons.map_kinds)) {
    if (stockKinds.has(k)) throw new Error(`kind '${k}' is drawn by both the stock pois layer and pois_driving`);
  }
  ls.splice(poiIndex + 1, 0, drivingLayer(variant, spec.protomaps_flavor, source));
  // Every icon the style can ask for must be in the sprite sheet.
  const missing = [];
  for (const l of ls) {
    if (!l.layout?.["icon-image"]) continue;
    for (const n of iconNames(l)) if (!sprite[n]) missing.push(`${l.id}: ${n}`);
  }
  if (missing.length) throw new Error(`${variant}: icons missing from the sprite sheet: ${missing.join(", ")}`);
  console.log(`${variant}: every icon the style uses is in sprites/${spec.sprite}`);
  const ph = cfg.placeholders;
  return {
    version: 8,
    name: `Beamline ${variant}`,
    metadata: {
      "beamline:variant": variant,
      "beamline:schema": "protomaps basemap v4 (tiles 4.x)",
      "beamline:generated_from": `@protomaps/basemaps ${npm.version} flavor '${spec.protomaps_flavor}'`,
      "beamline:placeholders": "replace {{PACK_URL}} and {{STYLE_BASE_URL}} before use",
    },
    sources: {
      [source]: {
        type: "vector",
        url: `pmtiles://${ph.pack_url}`,
        attribution: cfg.attribution,
      },
    },
    glyphs: `${ph.style_base_url}/glyphs/{fontstack}/{range}.pbf`,
    sprite: `${ph.style_base_url}/sprites/${spec.sprite}`,
    layers: ls,
  };
}

// A glyphs PBF that is valid but holds no glyphs: message glyphs { fontstack { name, range } }
function emptyGlyphPbf(name, range) {
  const enc = new TextEncoder();
  const field = (tag, bytes) => [tag, bytes.length, ...bytes];
  const inner = [...field(0x0a, enc.encode(name)), ...field(0x12, enc.encode(range))];
  if (inner.length > 127) throw new Error("name too long for 1-byte length");
  return new Uint8Array(field(0x0a, inner));
}

const styles = {};
for (const [variant, spec] of Object.entries(cfg.flavors)) styles[variant] = buildStyle(variant, spec);

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });
for (const [variant, style] of Object.entries(styles)) {
  writeFileSync(join(OUT, `${variant}.json`), JSON.stringify(style, null, 1) + "\n");
  console.log(`${variant}.json: ${style.layers.length} layers`);
}

let real = 0, stubs = 0;
for (const font of cfg.fonts) {
  const dir = join(OUT, "glyphs", font);
  mkdirSync(dir, { recursive: true });
  for (let start = 0; start < 65536; start += 256) {
    const range = `${start}-${start + 255}`;
    const src = join(SRC, "fonts", font, `${range}.pbf`);
    if (cfg.glyph_ranges_real.includes(range)) {
      copyFileSync(src, join(dir, `${range}.pbf`));
      real++;
    } else {
      writeFileSync(join(dir, `${range}.pbf`), emptyGlyphPbf(font, range));
      stubs++;
    }
  }
}
mkdirSync(join(OUT, "sprites"), { recursive: true });
for (const s of cfg.sprites) {
  for (const suffix of ["", "@2x"]) {
    for (const ext of ["json", "png"]) {
      copyFileSync(join(SPRITES, `${s}${suffix}.${ext}`), join(OUT, "sprites", `${s}${suffix}.${ext}`));
    }
  }
}
copyFileSync(join(SRC, "fonts", "OFL.txt"), join(OUT, "glyphs", "OFL.txt"));
copyFileSync(join(UPSTREAM, "LICENSE-tangrams-icons.md"), join(OUT, "sprites", "tangrams-icons-LICENSE.md"));
writeFileSync(join(OUT, "LICENSES.md"), `# Style bundle licences

Generated by packbuilder/map/style/generate_style.mjs. Do not edit by hand.

| Part | Source | Licence |
|---|---|---|
| day.json, night.json layer definitions | @protomaps/basemaps ${npm.version} (npm), modified (fonts, label colours, one-way arrows, route shields by network, driving POI layer with icons) | Code BSD-3-Clause; visual design CC0-1.0 (Protomaps LLC) |
| glyphs/ (Noto Sans Regular, Medium, Italic; ranges ${cfg.glyph_ranges_real.join(", ")}) | protomaps/basemaps-assets ${versions.basemaps_assets.commit.slice(0, 7)}, made with maplibre/font-maker | SIL Open Font License 1.1, see glyphs/OFL.txt |
| glyphs/ other ranges | generated empty placeholders | not copyrightable / CC0 |
| sprites/: icons marked "upstream" in assets/icons/icons.json, the Interstate and generic shields | protomaps/basemaps sprites/refill.svg (42ffaaa), derived from tangrams/icons | MIT, Copyright (c) 2017 Mapzen, see sprites/tangrams-icons-LICENSE.md |
| sprites/: icons marked "beamline", the US route and Pennsylvania shields | drawn for Beamline (assets/), built with spreet | MIT (the project licence); shield shapes follow public sign specifications, see assets/LICENSES.md |
| Map data shown with this style | OpenStreetMap | ODbL 1.0, credit "© OpenStreetMap contributors" |
`);

function du(p) {
  const st = statSync(p);
  if (!st.isDirectory()) return st.size;
  return readdirSync(p).reduce((s, n) => s + du(join(p, n)), 0);
}
function count(p) {
  const st = statSync(p);
  if (!st.isDirectory()) return 1;
  return readdirSync(p).reduce((s, n) => s + count(join(p, n)), 0);
}
console.log(`glyph files: ${real} real, ${stubs} empty placeholders`);
console.log(`bundle size: ${du(OUT)} bytes (${(du(OUT) / 1024).toFixed(0)} KiB) in ${count(OUT)} files (glyphs ${(du(join(OUT, "glyphs")) / 1024).toFixed(0)} KiB, sprites ${(du(join(OUT, "sprites")) / 1024).toFixed(0)} KiB)`);
