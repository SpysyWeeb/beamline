// Check a style against the "sdk-support" tables of the MapLibre style specification:
// which MapLibre Native Android version is needed for every layer type, property and
// expression operator the style uses. This is a static check only; it does not
// replace running the style on a device.
//
// Usage (from the repo root):
//   npm install --prefix data/map/npm @maplibre/maplibre-gl-style-spec@26.5.1
//   node packbuilder/map/style/check_native_support.mjs packbuilder/map/style-bundle/day.json [...]
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { resolve } from "node:path";

const require = createRequire(resolve("data/map/npm/package.json"));
const ref = require("@maplibre/maplibre-gl-style-spec/dist/latest.json");
const ops = ref.expression_name.values;

const cmp = (a, b) => {
  const pa = String(a).split(".").map(Number), pb = String(b).split(".").map(Number);
  for (let i = 0; i < 3; i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) - (pb[i] || 0);
  return 0;
};

for (const file of process.argv.slice(2)) {
  const style = JSON.parse(readFileSync(file, "utf8"));
  const need = []; // [android version, what]
  const missing = [];
  const note = (support, what) => {
    const v = support?.android;
    if (!v || v === "wontfix" || v === "Not yet supported") missing.push(`${what} (${v ?? "no android entry"})`);
    else need.push([v === "supported" ? "0" : v, what]);
  };
  const walk = (v, where) => {
    if (!Array.isArray(v)) {
      if (v && typeof v === "object") for (const [k, x] of Object.entries(v)) walk(x, where);
      return;
    }
    if (typeof v[0] === "string" && ops[v[0]]) note(ops[v[0]]["sdk-support"]?.["basic functionality"], `expression ${v[0]}`);
    for (const x of v) walk(x, where);
  };
  for (const layer of style.layers) {
    for (const group of ["layout", "paint"]) {
      for (const [prop, value] of Object.entries(layer[group] || {})) {
        const spec = ref[`${group}_${layer.type}`]?.[prop];
        if (!spec) { missing.push(`${layer.id}: unknown ${group} property ${prop}`); continue; }
        note(spec["sdk-support"]?.["basic functionality"], `${layer.type} ${prop}`);
        if (Array.isArray(value)) {
          const dd = spec["sdk-support"]?.["data-driven styling"];
          if (dd && JSON.stringify(value).includes('"get"')) note(dd, `${layer.type} ${prop} (data-driven)`);
          walk(value, `${layer.id}.${prop}`);
        }
      }
    }
    if (layer.filter) walk(layer.filter, `${layer.id}.filter`);
  }
  const byWhat = new Map();
  for (const [v, w] of need) if (!byWhat.has(w) || cmp(v, byWhat.get(w)) > 0) byWhat.set(w, v);
  const sorted = [...byWhat.entries()].sort((a, b) => cmp(b[1], a[1]));
  console.log(`${file}: ${style.layers.length} layers, ${byWhat.size} distinct properties/operators`);
  console.log(`  highest MapLibre Native Android version required: ${sorted[0][1]} (${sorted[0][0]})`);
  console.log(`  next: ${sorted.slice(1, 5).map(([w, v]) => `${w} ${v}`).join("; ")}`);
  console.log(`  without Android support: ${missing.length ? [...new Set(missing)].join("; ") : "none"}`);
}
