# State pack format (manifest version 3)

A state pack holds the offline data of one US state: the map tiles, the routing tiles and their
index, the search index and, optionally, the house numbers, all built from one OpenStreetMap
snapshot. Packs carry **data only**. The map style (style JSON, glyphs, sprites) ships inside the
app, and so does a small whole-US overview map (public domain: Natural Earth and US Census).

The format is meant to be read on any device: plain files, a JSON manifest, a standard tar for
transport. The Python reader at the end needs nothing but the standard library. The contract this
document follows is `docs/architecture.md` 2.11 (and `docs/architecture-downloads.md` 6.6 for
`pack_version` and the download folder).

Who writes and reads it:

| Side | Code |
|---|---|
| Writes packs | `packbuilder/pack/assemble.py` (standard library only): the map (`packbuilder/map`), routing and its index (`packbuilder/routing`), search (`packbuilder/search/build.py`) and addresses (`packbuilder/addresses`) into a pack folder and an archive |
| Reads, checks, installs | Android `:core:packs` (`PackManifest`, `Readers`, `PackScanner`, `TarReader`, `StyleComposer`) and `:core:installed` (`InstalledLibrary`: the one install and removal flow, the merged routing tile set through `:routing:packs`) |
| Publishes for downloads | `packbuilder/catalog/build_catalog.py` (track G) reads a pack folder or an archive |
| Test fixtures | `android/core/packs/src/test/resources/manifest-v3.json` (and `manifest-v2.json`, refused) |

`tools/fetch_android_map_pack.py` (format 2) is retired: it refuses to run and points here.

## 1. Layout

### The pack folder (what `assemble.py` and a download write)

```
<pack_id>/
  manifest.json          this document's subject; UTF-8 without BOM
  map.pmtiles            the map part
  routing.tar            the routing part (Valhalla tiles)
  routing.json           the routing index: the routing builder's manifest, every tile with its SHA-256
  search.sqlite          the search part
  addresses.sqlite       the addresses part (optional: a download may leave it out)
```

Part files sit directly in the folder (no subfolders).

### Installed on the phone

```
<files>/packs/<pack_id>/
  manifest.json, map.pmtiles, routing.json, search.sqlite,
  addresses.sqlite       unless the user skipped house numbers
  install-record.json    written by the installer only (section 5)
<files>/routing/
  routing.tar            the ONE merged routing tile set of every installed pack
  valhalla.json          the engine configuration (no tile_url: maps, routing and search are offline)
  device.json, packs/<state>.json   the routing manager's bookkeeping (the installed routing indexes)
```

**The per-state `routing.tar` is not kept**: at install its tiles are merged into
`routing/routing.tar` (tiles on a state line are stored once) and the staged copy is deleted
(decision 6.3: about 0.63 GB less per state). Invariant: the states in the merged tile set are
exactly the installed packs whose record says `"routing_merged": true`.

Folder names starting with `.` are never packs: the installer stages into `.staging-*`, moves an
older copy aside as `.old-*` and removes through `.removing-*`.

### Transport (one file)

A **pack archive** is the pack folder as one uncompressed POSIX tar (`ustar`), named
`<pack_id>-<osm date>.tar`, for example `us-pa-2026-11-14.tar`. This is what a user picks with "Use
a file instead".

- `manifest.json` is the **first** entry, so a reader can refuse a pack (wrong version, not enough
  space) before it copies gigabytes; then the parts **from the smallest to the largest**
  (`routing.json` first, `routing.tar` last).
- The part files are at the top level of the archive (no folder prefix). A leading `./` on an
  entry name is ignored (`./manifest.json` is `manifest.json`).
- Entries the manifest does not name are skipped, and so is a second entry with a name already
  read. `install-record.json` is never in an archive.
- Uncompressed on purpose: the parts are compressed already or compress poorly as one stream, the
  app hashes while it copies in one pass, and Python's `tarfile` reads it.

Downloads do not fetch the archive: each part is its own file on the server (gzip where it pays),
listed by the catalog (`docs/catalog-format.md`, track G); the downloader's job folder is a pack
folder, and the installer **moves** its files in (section 3).

## 2. Manifest

```json
{
  "manifest_version": 3,
  "pack_id": "us-pa",
  "pack_version": 202611160853,
  "name": "Pennsylvania",
  "country": "US",
  "state": "PA",
  "osm_snapshot": "2026-11-14",
  "created_utc": "2026-11-16T08:53:00Z",
  "bounds": [-80.519851, 39.719799, -74.689561, 42.516072],
  "attribution": "© OpenStreetMap contributors",
  "parts": {
    "map": {
      "file": "map.pmtiles", "format": "pmtiles", "format_version": 3,
      "schema": "protomaps-basemap", "schema_version": "4.15.2", "min_zoom": 0, "max_zoom": 14,
      "tile_type": "mvt", "schema_extensions": {"lanes_minzoom": 14, "top_zoom": 14},
      "clip": {"file": "tl_2026_us_state.zip", "sha256": "2e17ea40dff3…", "state": "PA"},
      "bytes": 244703514, "sha256": "13a23f6a6bb4…", "attribution": "© OpenStreetMap contributors"
    },
    "routing": {
      "file": "routing.tar", "format": "valhalla-tar", "format_version": 1,
      "valhalla_version": "3.9.1", "graph_build": "261114-pa-oh-v3.9.1-b53087",
      "bytes": 634429440, "sha256": "3347f2e97e10…", "attribution": "© OpenStreetMap contributors"
    },
    "routing_index": {
      "file": "routing.json", "format": "beamline-routing-pack", "format_version": 1,
      "bytes": 51259, "sha256": "6ab70bb67863…", "attribution": "© OpenStreetMap contributors"
    },
    "search": {
      "file": "search.sqlite", "format": "beamline-search-sqlite", "format_version": 1,
      "addr_key_table_version": 2, "addr_key_fingerprint": "97c8d281115e…",
      "ranking_sha256": "989553d1507d…",
      "bytes": 154705920, "sha256": "94ab7e813174…", "attribution": "© OpenStreetMap contributors"
    },
    "addresses": {
      "file": "addresses.sqlite", "format": "beamline-addresses-sqlite", "format_version": 1,
      "normalisation_table_version": 2, "key_fingerprint": "97c8d281115e…", "variant": "slim",
      "bytes": 288145408, "sha256": "e529b39fa7cd…",
      "attribution": "© OpenStreetMap contributors; U.S. Census Bureau TIGER/Line; USDOT National Address Database; City of Scranton GIS"
    }
  }
}
```

### Top-level fields

| Key | Type | Meaning |
|---|---|---|
| `manifest_version` | int, required | The layout of this file: **3**. A reader refuses a higher number ("update the app") and a lower one: version 2 packs are refused with "an old pack format; rebuild the pack". (Version 1 was the skeleton's, with a style inside; version 2 had no `pack_version`, no per-part credit and no routing index.) |
| `pack_id` | string, required | Stable id, `[a-z0-9][a-z0-9._-]{0,63}`, e.g. `us-pa`. It names the installed folder. Installing a pack with the same id replaces the old one. Ids that differ only in `.` and `_` are different packs. |
| `pack_version` | int, required in format 3 | The build time `YYYYMMDDhhmm` (UTC) of `created_utc`. **A higher number is a newer build of the same `pack_id`** (the app reads it into `PackInfo.packVersion`; the catalog offers an update by it). A published build is never rebuilt: a changed pack gets a new `pack_version`. |
| `name` | string, required | Shown to the user. |
| `country`, `state` | string, required | `US`, and the USPS state code. |
| `osm_snapshot` | `YYYY-MM-DD`, required | **The one OpenStreetMap date of the whole pack.** `assemble.py` refuses parts of different snapshots. |
| `created_utc` | ISO 8601, required in format 3 | When the pack was assembled. |
| `bounds` | `[west, south, east, north]`, required | Degrees, four finite numbers, -180 <= west < east <= 180, -90 <= south < north <= 90: the box of the state outline the map was clipped to. |
| `attribution` | string, required | The credit of the pack's **required** parts (what the catalog shows for the pack). The app credits the parts **installed** instead (below). |
| `parts` | object, required | The parts by name (below). |

Unknown keys are ignored at every level, so a newer builder can add information without breaking
older readers. A change older readers would misread raises `manifest_version` (layout) or a
part's `format_version` (content).

### Fields every part has

| Key | Meaning |
|---|---|
| `file` | Plain file name in the pack folder: no `/`, `\`, `:` or leading `.`, not `manifest.json` or `install-record.json`, at most 128 characters. Two parts may not name the same file. |
| `format`, `format_version` | What is in the file, and which version of that format. |
| `bytes` | Exact size of the file. |
| `sha256` | SHA-256 of the file, 64 hex digits. |
| `attribution` | The credit of this part's data, items separated by `"; "`. |

### The parts

| Part | Required | `file` | `format` / `format_version` | Extra fields |
|---|---|---|---|---|
| `map` | yes | `map.pmtiles` | `pmtiles` / 3 | `schema` (`protomaps-basemap`), `schema_version` (**the app's built-in style needs the same major version**, 4), `min_zoom`, `max_zoom`, `tile_type` (`mvt`), `schema_extensions` (`lanes_minzoom`: the zoom from which roads carry lane tags; `top_zoom`: the zoom holding what the stock schema keeps for z15), `clip` (source file name, its SHA-256, state) |
| `routing` | yes | `routing.tar` | `valhalla-tar` / 1: a Valhalla tile tar with `index.bin`, as `valhalla_build_extract` writes it | `valhalla_version`, `graph_build` (packs share routing tiles only within one graph build; the merge refuses a pack of another build unless every neighbour is updated with it) |
| `routing_index` | yes with `routing` | `routing.json` | `beamline-routing-pack` / 1: the routing builder's manifest (`packbuilder/routing/build.py`, `"format": "beamline-routing-pack/1"`), byte for byte | none: the file lists every tile with its level, size and SHA-256, and `tar_bytes` / `tar_sha256` of the routing part |
| `search` | yes | `search.sqlite` | `beamline-search-sqlite` / 1 (= the `schema_version` row of the file's own `meta` table) | `addr_key_table_version`, `addr_key_fingerprint`, `ranking_sha256` (copies of the file's meta) |

The search file may hold tables an older app does not know; it ignores them, and the format version
stays 1. Since 2026-11-22 (docs/next-round.md item 36): `signal(cell, lat, lon)`, every traffic light of the
map (`highway=traffic_signals` nodes; `packbuilder/search/signals.py`), keyed by a square of 0.01 degree
(`cell = (floor(lat*100)+9000)*40000 + floor(lon*100)+18000`); an app joins them to the junctions of a guided
route, and a pack without the table shows the lights the routing data has. Since 2026-11-22 (docs/next-round.md item 31): `street_line(entry_id, line)`, the line of a street
entry as a blob (`packbuilder/search/lines.py`: a varint count of lines; per line a varint count of points, then
per point a zigzag varint latitude step and longitude step of 1e-5 degree from the point before, the first from
0, 0); an app reads it for a street's distance, for where two streets meet and for the street at a point, and a
pack without the table answers as before. Since 2026-11-21 (docs/next-round.md item 28): `poi_door(entry_id, lat, lon, how)`, the point
where customers go into a large building (`how`: `main`, `entrance`, `parking`;
`packbuilder/search/doors.py`). An app from 0.1.14 on ends a route to such a place at the lane by
that point; with a file that lacks the table it routes as before.
| `addresses` | no; skippable at install | `addresses.sqlite` | `beamline-addresses-sqlite` / 1 (= its meta `schema_version`) | `normalisation_table_version`, `key_fingerprint`, `variant` (`slim`: the device file, without the diagnostic columns); `attribution` names every address source in the file (OpenStreetMap, TIGER/Line "U.S. Census Bureau", USDOT NAD, City of Scranton) |
| anything else | no | | any | copied, checked and kept; never judged |

What the app reads is written in one place, `Readers` in `:core:packs`. A pack is refused when its
manifest version is not 3, a required part is missing, or the map (format, version, schema major),
routing or routing index part has a format or version the app cannot read. The search and
addresses parts are judged on their own: one the app cannot read is unusable (`PackInfo.searchProblem`
says why), while map and routing stay usable.

**The credit.** `PackInfo.attribution` joins the attributions of the parts installed, each item
once, map first: a state added without house numbers does not credit the address sources. The map
screen's credit line joins the installed packs' credits and the overview's own credit
("Natural Earth; US Census Bureau").

### Fingerprints and hashes

`fingerprint = sha256_hex( for each spelling s in packbuilder/addresses/addrlib/fingerprint_spellings.json
"spellings", in order: utf8(s + "\t" + street_key(s) + "\n") )` (decision 6.11;
`packbuilder/addresses/key_fingerprint.py`). The search builder writes it to search
`meta.addr_key_fingerprint`, the address builder to addresses `meta.key_fingerprint`; `assemble.py`
copies both into the manifest and **refuses a pair that differs**. The app's port computes the same
over its copy of the list (given to the installer as `Readers.addrKeyFingerprint`); a pack whose
search part was built with another fingerprint has its search marked unusable ("built with another
address normaliser"). Every hash the builders record of a text file (normaliser sources, rule
files, `ranking_sha256`) is over **LF-normalised** text (CRLF read as LF).

## 3. Checks and the install

**At install, once.** The manifest is read first and checked (versions, names, bounds) and the free
space: the parts copied + 64 KiB for the manifest and the install record + the new merged routing
tile set (as large as the one installed plus the new routing part: the merge's peak) + 4 MiB for
the routing manager's files + `free_space_margin_mb` of the app's `assets/rules/packs.json`
(200 MB). This sum is `ArchiveInfo.neededBytes` of `inspect`, which never lists free space among
its problems (the caller compares it with `freeBytes`). From an archive, every part is copied into a
hidden staging folder and hashed with SHA-256 **while it is copied** (one pass); from a download
folder each part is **moved** (renamed, no copy: the folder must be on the same storage) and then
hashed there, and its free-space need leaves out the moved parts. A size or checksum that differs
stops the install; moved files go back into the download folder. Then: the cheap checks below, the
routing index checked against the routing part (its `tar_bytes` and `tar_sha256`, and its `state`
must be the manifest's `state`), the routing merge (per-tile SHA-256 against the routing index, no
second hash of the whole tar), the staged `routing.tar` deleted, the install record written, the
folder renamed into place, and one new generation of the installed data published. A failed or
cancelled install before the merge leaves nothing behind.

**One pack per state, one road build.** The merged routing set holds one routing state per USPS
state, so a pack with another `pack_id` for a state that is installed is refused ("Pennsylvania is
installed already (as us-pa). Remove it first."; `inspect` lists it among the problems). All states
in the set come from one routing graph build (`graph_build`): a pack of another build is refused
while a state of the old build is installed, in plain words ("... was built from another version of
the road data than Ohio on this phone ... Remove Ohio first, then install Pennsylvania and add Ohio
again."). The installer adds one state per change, so moving the states to a new build is: remove
the others, install one, add the others again from the same build.

**After a crash.** The next start finishes or undoes what was half done: a staging folder whose
routing merge had committed is completed and renamed in (the per-state routing tar is gone by then,
so the install rolls forward); if that roll forward fails (the record cannot be written, the rename
fails), the staging folder is kept, still owns its routing state, is listed among the issues and is
tried again at the next scan; any other staging folder is deleted (a download's moved parts with
it: the downloader fetches them again); an older copy moved aside for an update and not replaced
goes back; a removal cut short is finished; routing states no installed pack owns leave the merged
set, and so does a state whose manifest in `routing/packs/` cannot be read (damaged storage never
stops a scan: the pack of that state shows "its routing tiles are not in the routing set: install
the state again"). A repair that cannot be done now (too little free space to rewrite the routing
set) is listed in `InstalledSet.issues` (folder `routing/<state>`) and tried again at the next
scan. The JVM tests stop a change at four points (after staging, after the routing commit, before
the rename, during removal) and check the next start.

**Removal.** The folder is renamed aside, the routing states no installed pack owns leave the
merged set, a generation is published, then the folder is deleted. Rewriting the routing set needs
the remaining set's size free beside it; on a full phone that may fit only once the removed folder
is deleted, so the uninstall is tried once more then (a second generation when it worked). If it
still does not fit, `remove` says "Ohio is removed, but its roads stay in the route planner until
the phone has more free space. Beamline removes them then." and a later scan removes them.

**Changes while guiding.** While the navigation session holds `holdChanges("Not while guiding")`,
installs, house-number changes and removals are refused with that text; while an install, a
removal or a repair runs, `holdChanges` returns null. While held, a rescan (`refresh`) changes no
file and publishes no new generation (only the very first scan of a process is published); a
repair or a changed view it found waits, and runs with a rescan as soon as the last hold is
released. Every install, update, removal and house-number change publishes exactly one new
generation of `InstalledSet` (the one exception: the low-space removal above, two); a hold changes
only its `changesHeldBy`, not the generation.

**Messages.** `InstallStatus.Failed` and the answers of `remove` are plain sentences written by the
installer, never an exception's own text: a refusal of the routing merge is worded by its kind
(another road build, another route-planner version, not enough space, damaged tiles), and an
unexpected exception gives a fixed sentence with at most its class name.

**Afterwards, on every scan (each app resume): cheap checks only, no hashing.**

- `manifest.json` parses, versions as above, bounds valid, file names valid;
- every part file the install record says is there exists and has exactly `bytes` bytes (catches
  an interrupted copy); the skipped `addresses` and the merged `routing.tar` are not looked for;
- a header check by format: PMTiles v3 magic and the root directory, metadata, leaf directories
  and tile data inside the file; a tar's `ustar` magic; SQLite's `SQLite format 3\0` header; a
  JSON object for the routing index.

A pack that fails is not used; the app shows its folder and the reason.

## 4. Versions in one table

| What | Where | Today | Who refuses |
|---|---|---|---|
| Manifest layout | `manifest_version` | 3 | the app, for any other number |
| Build | `pack_version` | `YYYYMMDDhhmm` | nobody: higher = newer |
| Map container | `parts.map.format_version` | PMTiles 3 | the app |
| Map tile schema | `parts.map.schema`, `schema_version` | protomaps-basemap 4.15.2 | the app, if the schema or its major version differs from its built-in style |
| Routing tiles | `parts.routing.format_version`, `valhalla_version`, `graph_build` | valhalla-tar 1, Valhalla 3.9.1, `261114-pa-oh-v3.9.1-b53087` | the app (format), the merge (Valhalla version of every tile, graph build) |
| Routing index | `parts.routing_index.format_version` | 1 | the app |
| Search index | `parts.search.format_version`, `addr_key_fingerprint` | 1 | the app: search unusable, map and routing kept |
| House numbers | `parts.addresses.format_version` | 1 | the app: house numbers unusable |
| Style (in the app) | `android/app/src/main/assets/style/bundle-version.json` | content SHA-256 of the copied bundle | not part of a pack |

## 5. install-record.json (app-written)

```json
{"installed_utc": "2026-11-16T09:02:11Z", "verified": "sha256", "source": "archive",
 "skipped_parts": [], "routing_merged": true}
```

Written into the pack folder by the installer (replaced atomically: a temporary file, then a
rename) after every part's size and SHA-256 matched. `source` is `archive` (a `.tar` file),
`download` (a download folder whose files were moved in) or `folder`. `skipped_parts` lists the
optional parts left out (`addresses`); adding or removing house numbers later replaces the record.
`routing_merged` says the pack's routing tiles are in the merged tile set. A pack folder without a
record was copied some other way (for example `android/scripts/push_pack.py` over adb): the app
shows it, as not checked, and its routing is not merged.

## 6. Reading a pack from plain Python

Standard library only; works on the folder or the archive, on Windows, Linux or a comma device.

```python
# Copy of the snippet in docs/pack-format.md section 6 (kept identical; the report runs it).
"""Read a Beamline pack (folder or .tar, format 3), check its versions and, optionally, its checksums."""
import hashlib, json, sys, tarfile
from pathlib import Path

SUPPORTED_MANIFEST = 3
READS = {                                   # parts and the format versions this reader knows
    "map": ("pmtiles", 3),
    "routing": ("valhalla-tar", 1),
    "routing_index": ("beamline-routing-pack", 1),
    "search": ("beamline-search-sqlite", 1),
    "addresses": ("beamline-addresses-sqlite", 1),
}
REQUIRED = ("map", "routing", "routing_index", "search")   # "addresses" is optional (skippable)
STYLE_SCHEMA = ("protomaps-basemap", 4)     # tile schema and major version the style draws

def entry_name(e):                          # "./manifest.json" (as `tar -C dir .` writes) = "manifest.json"
    return e.name[2:] if e.name.startswith("./") else e.name

def read_manifest(path):
    p = Path(path)
    if p.is_dir():
        return json.loads((p / "manifest.json").read_text(encoding="utf-8"))
    with tarfile.open(p) as t:              # an archive: manifest.json is the first entry
        first = t.next()
        if first is None or not first.isfile() or entry_name(first) != "manifest.json":
            raise ValueError("first entry must be manifest.json")
        return json.loads(t.extractfile(first).read().decode("utf-8"))

def problems(m):
    v = m.get("manifest_version")
    if v != SUPPORTED_MANIFEST:
        why = "an old pack format; rebuild the pack" if isinstance(v, int) and v < SUPPORTED_MANIFEST else "update the reader"
        return [f"manifest_version {v}: {why}"]
    out = []
    if not isinstance(m.get("pack_version"), int):
        out.append("no pack_version")
    parts = m.get("parts", {})
    for name in REQUIRED:
        if name not in parts:
            out.append(f"no {name} part")
    for name, known in READS.items():
        part = parts.get(name)
        if part is not None and (part.get("format"), part.get("format_version")) != known:
            out.append(f"{name}: {part.get('format')} v{part.get('format_version')} unknown")
    mp = parts.get("map", {})
    try:                                    # "4.15.2" -> 4; anything else is not this style's schema
        major = int(str(mp.get("schema_version")).split(".")[0])
    except ValueError:
        major = None
    if (mp.get("schema"), major) != STYLE_SCHEMA:
        out.append(f"map tiles {mp.get('schema')} {mp.get('schema_version')} do not match the style")
    fps = {parts.get("search", {}).get("addr_key_fingerprint"), parts.get("addresses", {}).get("key_fingerprint", None)}
    if "addresses" in parts and len(fps) != 1:
        out.append("search and addresses were built with different street-key fingerprints")
    return out

def credit(m, skipped=()):
    """The credit for the parts installed: each part's attribution, split at "; ", each item once."""
    items = []
    for name, part in m["parts"].items():
        if name not in skipped:
            for item in (part.get("attribution") or m.get("attribution", "")).split(";"):
                if item.strip() and item.strip() not in items:
                    items.append(item.strip())
    return "; ".join(items)

def absent_parts(path, m):
    """Parts an installed folder does not hold, by its install-record.json (skipped, routing merged)."""
    rec = Path(path) / "install-record.json"
    if not rec.is_file():
        return set()
    r = json.loads(rec.read_text(encoding="utf-8"))
    out = {p for p in r.get("skipped_parts", []) if p == "addresses"}
    return out | ({"routing"} if r.get("routing_merged") else set())

def verify(path, m):
    """Sizes and SHA-256 of every part (slow: reads everything once)."""
    p = Path(path)
    absent = absent_parts(p, m) if p.is_dir() else set()
    want = {part["file"]: part for name, part in m["parts"].items() if name not in absent}
    def check(name, f):
        h, n = hashlib.sha256(), 0
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk); n += len(chunk)
        ok = n == want[name]["bytes"] and h.hexdigest() == want[name]["sha256"]
        print(f"  {name}: {n} bytes, sha256 {'ok' if ok else 'MISMATCH'}")
        return ok
    seen, ok = set(), True
    if p.is_dir():
        for name in want:
            if (p / name).is_file():
                with open(p / name, "rb") as f:
                    ok &= check(name, f); seen.add(name)
    else:
        with tarfile.open(p) as t:
            for e in t:
                if e.isfile() and entry_name(e) in want and entry_name(e) not in seen:
                    ok &= check(entry_name(e), t.extractfile(e)); seen.add(entry_name(e))
    for name in sorted(set(want) - seen):
        print(f"  {name}: MISSING")
    return ok and seen == set(want)

if __name__ == "__main__":
    try:
        m = read_manifest(sys.argv[1])
    except (ValueError, OSError, tarfile.TarError) as e:  # not a tar, no manifest, broken JSON
        sys.exit(f"not a readable pack: {e}")
    print(f"{m['pack_id']} {m.get('pack_version')}: {m['name']}, OSM {m['osm_snapshot']}, parts {', '.join(m['parts'])}")
    print("credit:", credit(m))
    found = problems(m)
    print("problems:", found or "none")
    if "--verify" in sys.argv:
        good = verify(sys.argv[1], m)
        print("checksums ok" if good else "checksums FAILED")
        sys.exit(0 if good and not found else 1)
    sys.exit(1 if found else 0)
```

Run as `python android/scripts/read_pack.py data/r3-packs/us-pa-2026-11-14.tar --verify` (the
snippet is `android/scripts/read_pack.py`; it exits 0 only when there is no problem and, with
`--verify`, every checksum matches). Build a pack with
`python packbuilder/pack/assemble.py pa` (section 1, and the module's header).
