// Style binding and multi-pack composition. Pure functions: no DOM, no MapLibre,
// so the same logic can be ported to the Android app (Kotlin) or Python.
//
// A style in packbuilder/map/style-bundle/ is a template with two placeholders:
//   {{PACK_URL}}        the pack file, e.g. file:///abs/path/pa.pmtiles
//   {{STYLE_BASE_URL}}  where glyphs/ and sprites/ live
// and exactly one vector source named "protomaps".

const SOURCE = "protomaps";

function deepReplace(value, map) {
  if (Array.isArray(value)) return value.map((v) => deepReplace(v, map));
  if (value && typeof value === "object") {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = deepReplace(v, map);
    return out;
  }
  if (typeof value === "string") {
    let s = value;
    for (const [from, to] of Object.entries(map)) s = s.split(from).join(to);
    return s;
  }
  return value;
}

/** Single pack: fill in the placeholders. */
export function bindStyle(template, { packUrl, styleBaseUrl }) {
  return deepReplace(structuredClone(template), {
    "{{PACK_URL}}": packUrl,
    "{{STYLE_BASE_URL}}": styleBaseUrl,
  });
}

/**
 * Several packs at once with one source per pack and a copy of every layer per pack.
 *
 * packs: [{ id: "pa", url: "file:///.../pa.pmtiles", maxzoom?: number, minzoom?: number }]
 *   id       becomes the source id and the layer-id suffix ("roads_major@pa")
 *   minzoom / maxzoom (optional) limit where that pack's layers draw, e.g. a
 *            shared low-zoom base pack { id: "base", maxzoom: 8 } with state packs
 *            built from z8 upwards.
 * order:
 *   "interleaved" (default): for each stock layer, the copies of all packs are
 *       adjacent, so OH roads never cover PA labels; z-order is the same as with
 *       one merged source.
 *   "grouped": all layers of pack 1, then all layers of pack 2 ... (a later pack
 *       paints over an earlier one where they overlap).
 */
export function composeMultiPack(template, packs, { styleBaseUrl, order = "interleaved" } = {}) {
  const t = deepReplace(structuredClone(template), { "{{STYLE_BASE_URL}}": styleBaseUrl });
  const baseSource = t.sources[SOURCE];
  const sources = {};
  for (const p of packs) {
    sources[p.id] = { ...baseSource, url: baseSource.url.split("{{PACK_URL}}").join(p.url) };
  }
  const copy = (layer, p) => {
    const l = structuredClone(layer);
    l.id = `${layer.id}@${p.id}`;
    l.source = p.id;
    if (p.minzoom !== undefined) l.minzoom = Math.max(l.minzoom ?? 0, p.minzoom);
    if (p.maxzoom !== undefined) l.maxzoom = Math.min(l.maxzoom ?? 24, p.maxzoom);
    return l;
  };
  const layers = [];
  const shared = t.layers.filter((l) => l.source !== SOURCE); // background
  const perPack = t.layers.filter((l) => l.source === SOURCE);
  layers.push(...shared);
  if (order === "grouped") {
    for (const p of packs) for (const l of perPack) layers.push(copy(l, p));
  } else {
    for (const l of perPack) for (const p of packs) layers.push(copy(l, p));
  }
  return { ...t, sources, layers };
}
