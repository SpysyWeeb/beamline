# Download catalog format (`beamline-catalog/1`)

How Beamline's state packs are offered for download: the list of states (`catalog.json`) and the
files it names. A pack itself is described in `docs/pack-format.md`; this document says how its
files travel. The design behind it, with its reasons and measurements, is
`docs/architecture-downloads.md` sections 2 and 3.

The format is Beamline's own. Everything here can be read with Python's standard library, so a
client on any device (a later comma 3X client included) can fetch a pack without the Android app.

| Side | Code |
|---|---|
| Writes the site | `packbuilder/catalog/build_catalog.py` |
| Serves it for development | `packbuilder/catalog/serve_packs.py` |
| Reads it (reference client, normative) | `packbuilder/catalog/catalog_client.py`, with the rules in `packbuilder/catalog/catalog_format.py` |
| Reads it (the app) | Android `:core:download` (`CatalogFormat.parse`, `CatalogAddress`, `PackHttp`, `DownloadEngine`) |

**The comma catalog** is a separate file, `comma.json`, published beside `catalog.json` in the same
`catalog` release: the same packs for a comma device running StarPilot, plus three comma-only parts.
Its format (`beamline-comma-catalog/1`) is `docs/comma-pack-format.md` section 2; its writer is
`packbuilder/comma/catalog.py`, which uses the transfer rule of section 2 below and never changes
`catalog.json` or anything else in this document.

## 1. Layout on the server

One folder per pack build, and one folder for the list. The layout copies GitHub Releases (each
release is one flat folder of assets), so the same `catalog.json` bytes work on the build PC and on
GitHub:

```
<scope>/catalog/catalog.json                      the list (the address a build is configured with)
<scope>/us-pa-202611152241/manifest.json          one folder (one release) per pack build
<scope>/us-pa-202611152241/routing.json.gz
<scope>/us-pa-202611152241/map.pmtiles
<scope>/us-pa-202611152241/routing.tar.gz
<scope>/us-pa-202611152241/search.sqlite.gz
<scope>/us-pa-202611152241/addresses.sqlite.gz
```

`<scope>` is the folder above the catalog's folder: `https://github.com/<owner>/<repo>/releases/download/`
on GitHub (release tags `catalog` and `us-pa-202611152241`), `http://<host>:8765/` from the build
PC. The catalog names every file by a path relative to its own address
(`../us-pa-202611152241/map.pmtiles`), never by an absolute URL.

A release folder is named `<pack_id>-<pack_version>`. Folder and file names match
`[a-z0-9][a-z0-9._-]*`. A published release is never rebuilt: a changed pack is a new build with a
higher `pack_version`, in a new folder.

## 2. One file per part, and gzip

Each part of a pack (`map`, `routing`, `routing_index`, `search`, `addresses`, later parts) is its
own transfer file, so a download resumes per file with a plain HTTP `Range`, a broken part costs only
itself, the optional house-number part can be left out by not fetching it, and every file stays
under GitHub's 2 GiB limit for one release asset. The `.tar` pack archive of `docs/pack-format.md`
stays the format of "Use a file instead" and is not published.

**Compression rule** (the builder's): a part is sent as `<file>.gz` when it is at least 65,536 bytes
and gzip level 6 makes it 90 % or less of its size; otherwise it is sent as `<file>` itself. A gzip
file is a single member with no file name and no time in its header
(`gzip.GzipFile(filename="", mode="wb", compresslevel=6, mtime=0)`). Measured on Pennsylvania's z14
pack: routing shrinks to 37.9 %, search to 40.9 %; the map (already compressed tiles) is sent as is.
The whole pack goes from 1,033.8 MB to 548.5 MB.

Different zlib builds may compress the same input to different bytes, so equal hashes across
machines are not promised. The builder therefore keeps each release folder's `transfer.json`
(`{"<file>": {"path", "encoding", "bytes", "sha256", "part_sha256"}}`) and reuses a finished transfer
file instead of compressing again, and a release folder that `catalog.json` already names is never
written again.

## 3. `catalog.json`

UTF-8 without a byte order mark, LF line ends, one JSON object. Example (hashes shortened here
only):

```json
{
  "format": "beamline-catalog/1",
  "serial": 20261115224107,
  "generated_utc": "2026-11-15T22:41:07Z",
  "licence": {
    "id": "ODbL-1.0",
    "name": "Open Database License 1.0",
    "url": "https://opendatacommons.org/licenses/odbl/1-0/",
    "notice": "Map data © OpenStreetMap contributors, available under the Open Database License."
  },
  "packs": [
    {
      "id": "us-pa",
      "state": "PA",
      "name": "Pennsylvania",
      "pack_version": 202611152241,
      "manifest_version": 3,
      "osm_snapshot": "2026-11-14",
      "credit": "© OpenStreetMap contributors",
      "bounds": [-80.51985, 39.7198, -74.68956, 42.51607],
      "manifest": {"path": "../us-pa-202611152241/manifest.json", "encoding": "identity", "bytes": 2210, "sha256": "…"},
      "parts": [
        {"name": "search", "file": "search.sqlite", "optional": false, "bytes": 154705920, "sha256": "92f09d9a15bf…",
         "credit": "© OpenStreetMap contributors",
         "transfer": {"path": "../us-pa-202611152241/search.sqlite.gz", "encoding": "gzip", "bytes": 63346936, "sha256": "…"}},
        {"name": "map", "file": "map.pmtiles", "optional": false, "bytes": 244703514, "sha256": "13a23f6a6bb4…",
         "credit": "© OpenStreetMap contributors",
         "transfer": {"path": "../us-pa-202611152241/map.pmtiles", "encoding": "identity", "bytes": 244703514, "sha256": "13a23f6a6bb4…"}}
      ]
    }
  ]
}
```

### Top level (all required)

| Key | Type | Meaning |
|---|---|---|
| `format` | string | `beamline-catalog/<major>`. A reader refuses a major it does not know and reads everything else; unknown keys are ignored at every level, so fields can be added without a new major. |
| `serial` | integer | The builder's UTC time as `YYYYMMDDhhmmss`, always larger than the previous serial it wrote (previous + 1 if the clock is behind). A format-1 reader records it and uses the list it fetched whatever its serial. |
| `generated_utc` | string | ISO 8601 UTC with `Z`, at most 32 characters. |
| `licence` | object | `id` (SPDX), `name` (at most 100 characters), `url` (https, at most 200; Beamline shows the notice and never opens the URL; a client that opens it must check that it is https), `notice` (one line, at most 300 characters, shown with every download). A licence that breaks these refuses the whole list. |
| `packs` | array | One entry per pack; at most one per `state` in format 1. |

`catalog.json.sig` beside the catalog is reserved for a detached signature over the exact bytes. A
format-1 reader never fetches it. Signing becomes required before any second host (a mirror, a CDN)
is added.

### Per pack

| Key | Type | Meaning |
|---|---|---|
| `id` | string | The manifest's `pack_id` (`[a-z0-9][a-z0-9._-]{0,63}`); in format 1 exactly `us-` followed by `state` in lower case. |
| `state` | string | USPS code, two capital letters. |
| `name` | string | Shown to the user; at most 64 characters. |
| `pack_version` | integer | The manifest's `pack_version` (the build time `YYYYMMDDhhmm`, UTC); for a manifest without one (version 2), the builder derives it from `created_utc` the same way. Higher is a newer build of the same `id`. |
| `manifest_version` | integer | The manifest's layout version. A reader reads the versions from its minimum to its maximum; above its maximum the entry "needs a newer version", below its minimum it is "not available yet". |
| `osm_snapshot` | string | `YYYY-MM-DD`, the date of the OpenStreetMap data. |
| `credit` | string | The manifest's `attribution`; at most 300 characters. |
| `bounds` | array | `[west, south, east, north]`, as the manifest. |
| `manifest` | object | A transfer object for `manifest.json`; encoding always `identity`. |
| `parts` | array | One object per manifest part, in download order (ascending transfer size). |

**Per part:** `name` (the manifest's part name, `[a-z][a-z0-9_]{0,31}`), `file` (the manifest's
`file`, a plain file name as `docs/pack-format.md` defines it), `optional` (true only for parts the
pack format calls skippable: `addresses`), `bytes` and `sha256` (of the file as installed; equal to
the manifest's), `credit` (optional: the part's `attribution`, at most 300 characters), `transfer`.

**Transfer object:** `path` (relative to the catalog's address), `encoding` (`identity` or `gzip`;
`identity` means the transfer file is the part file, and its `bytes` and `sha256` equal the
part's), `bytes`, `sha256` (of the transfer file as served).

### Rules a reader enforces

1. Size at most 2 MiB; a JSON object; `format` as above.
2. Every `path` matches `^(\.\./)?[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*$` and,
   resolved against the catalog's URL (RFC 3986), stays inside the scope (the folder above the
   catalog's folder). One bad path refuses **the whole list**: it means a broken or hostile server.
3. Per entry (a failure makes only that entry unusable): required fields present and typed;
   strings within their lengths; `id` equal to `us-` + `state` in lower case; `bytes` from 0 to
   8 GiB − 1; `sha256` 64 lowercase hex digits; `encoding` known (an unknown one means "needs a newer
   version"); `manifest_version` within the reader's range; `id`, `state` and part `file` names
   unique; part names unique; no part `file` named `manifest.json`, `install-record.json` or
   `job.json`.
4. After downloading `manifest.json` (its own size and hash checked), its `pack_id`, `pack_version`
   (when present), `state`, `osm_snapshot` and every chosen part's `file`, `bytes` and `sha256` must
   equal the catalog's; otherwise the download fails (the list and the files disagree; nothing but
   the manifest was fetched).

## 4. How a client fetches one pack

For one pack, with the chosen parts (all, or all but the optional ones):

1. GET the manifest (small, no resume) and check it (rule 4).
2. For each chosen part, in catalog order: the local name of the transfer file is
   `.transfer-<part name>`; if the final file `<file>` already exists with the right size and was
   verified, skip it. Else:
   - If a `.transfer-*` file exists with N bytes (0 < N < transfer `bytes`), hash those N bytes again,
     then GET with `Range: bytes=N-` and `If-Match: <saved ETag>` when an ETag was saved. A 206 whose
     `Content-Range` starts at N: append. A 200: the server ignored the range; truncate and take the
     whole body. A 412, or a strong ETag that differs from the saved one: truncate and start again at
     0. A 416 with `Content-Range: bytes */<size>` and N = size: complete. Redirects are followed only
     from https to https with every resolved address public, at most 5; a redirect target is never
     stored, and every attempt starts again at the catalog-relative URL.
   - Read at most `bytes - N` more bytes; more is an error. Hash every byte written. A transfer that
     ends early keeps its bytes for the next attempt.
   - At the end the size and SHA-256 must equal the transfer object's; on a mismatch delete the file
     and fetch it again once, then stop with that part missing (the parts already verified stay).
   - `gzip`: inflate the transfer file into `<file>` (at most the part's `bytes`; more is an error),
     hashing the output; its size and SHA-256 must equal the part's; then delete the transfer file.
     `identity`: rename the transfer file to `<file>`.
3. The folder now holds `manifest.json` and the chosen part files: a pack folder in the layout of
   `docs/pack-format.md`, ready for an installer or for direct use.

Requests carry only `User-Agent: Beamline-Packs/1`, `Accept-Encoding: identity` and, when needed,
`Range`, `If-Match` and (for the catalog) `If-None-Match`: no cookies, no query string, nothing about
the user or the device.

### The core of one resumable file, in Python

Standard library only. This text was run against a local Range server: a cut transfer keeps its
bytes and resumes, a complete file ends with 416, an ignored range and a 412 start again from 0,
and redirects to `http://127.0.0.1`, `https://127.0.0.1` and `https://10.200.0.5` are refused. The
full reference client, `packbuilder/catalog/catalog_client.py`, also checks the catalog host's own
addresses, the development server's same-host rule and the rules of section 3.

```python
import hashlib, ipaddress, os, socket, urllib.error, urllib.parse, urllib.request

class Redirects(urllib.request.HTTPRedirectHandler):
    max_redirections = 5                                   # 3.1: at most 5 hops
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        u = urllib.parse.urlsplit(newurl)                  # 3.1: https only, every address public
        if u.scheme != "https" or not all(ipaddress.ip_address(a[4][0]).is_global
                                          for a in socket.getaddrinfo(u.hostname, u.port or 443)):
            raise urllib.error.HTTPError(newurl, code, "redirect refused", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)

def fetch(url, dest, size, sha256, etag=None):
    """One transfer file (2.4). Returns the ETag to keep for the next resume."""
    have = os.path.getsize(dest) if os.path.exists(dest) else 0
    h = hashlib.sha256()
    if have:
        with open(dest, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    req = urllib.request.Request(url, headers={"User-Agent": "Beamline-Packs/1", "Accept-Encoding": "identity"})
    if have:
        req.add_header("Range", f"bytes={have}-")
        if etag: req.add_header("If-Match", etag)
    try:
        r = urllib.request.build_opener(Redirects).open(req, timeout=30)
    except urllib.error.HTTPError as e:
        if e.code == 416 and have == size and e.headers.get("Content-Range") == f"bytes */{size}":
            r = None                                       # already complete
        elif e.code == 412:                                # the file changed: start again at 0
            os.remove(dest); return fetch(url, dest, size, sha256)
        else:
            raise
    if r is not None:
        with r:
            new_etag = r.headers.get("ETag")
            if r.status == 200 or (etag and new_etag and not new_etag.startswith("W/") and new_etag != etag):
                have, h = 0, hashlib.sha256()              # range ignored, or another file: start over
            elif not r.headers.get("Content-Range", "").startswith(f"bytes {have}-"):
                raise ValueError("206 does not start where asked")
            etag = new_etag
            with open(dest, "r+b" if have else "wb") as out:
                out.seek(have); out.truncate()
                for b in iter(lambda: r.read(1 << 20), b""):
                    have += len(b)
                    if have > size: raise ValueError("longer than the catalog says")
                    out.write(b); h.update(b)
    if have < size: raise ConnectionError("cut short; call again to resume")    # bytes kept
    if h.hexdigest() != sha256: os.remove(dest); raise ValueError("damaged")
    return etag
```

## 5. The tools

Run from the repository root with Python 3.12; standard library only; nothing is uploaded.

```
python packbuilder/catalog/build_catalog.py --out data/catalog/site \
    --pack data/r3-packs/us-pa [--pack data/r3-packs/us-oh] \
    [--archive <pack>.tar] [--manifest-versions 3] [--prune] [--check]
python packbuilder/catalog/serve_packs.py --root data/catalog/site [--bind 127.0.0.1] [--port 8765]
    [--log-headers] [--log-clients] [--throttle-kbps N] [--cut-after BYTES] [--ignore-range] [--fail-every N]
python packbuilder/catalog/catalog_client.py list <catalog URL>
python packbuilder/catalog/catalog_client.py get <catalog URL> <state or id> --out <folder> [--skip-optional]
python -m unittest discover packbuilder/catalog/tests
```

- `build_catalog.py` reads pack folders or archives (manifest first), accepts only the manifest
  versions given (default 3), verifies every part's size and SHA-256 in the same read pass that
  writes its transfer file, refuses a transfer file of 2 GiB or more, two packs of one state, an id
  that is not `us-` + the state, a string over its limit and any path that breaks rule 2, and writes
  `catalog/catalog.json` through a temporary file. Entries for the packs given replace those with the
  same `id`; other entries stay; a run that changes no entry leaves the file as it is.
  `SOURCE_DATE_EPOCH` (seconds), when set, fixes `serial` and `generated_utc`, so two builds of the
  same inputs give the same bytes. `--prune` deletes release folders no entry names; `--check`
  re-reads every file the catalog names and compares size and SHA-256 (run it before publishing).
- `serve_packs.py` answers GET and HEAD only (else 405); a URL path maps to a file only when every
  segment matches `[A-Za-z0-9][A-Za-z0-9._-]*`. It sends `Accept-Ranges: bytes`, a strong
  `ETag` `"<size hex>-<mtime_ns hex>"`, `Last-Modified` and `Cache-Control: no-cache`; one range gives
  206, a start at or past the end 416 with `bytes */<size>`, several ranges the whole file; it honours
  `If-Match` (412), `If-None-Match` (304) and `If-Range`. It logs one line per request (time, method,
  path, `Range`, status, bytes sent, `User-Agent`) and the client's address only with
  `--log-clients`. It binds 127.0.0.1 by default: an emulator reaches it as `10.0.2.2`, a phone on
  USB through `adb reverse tcp:8765 tcp:8765` as `127.0.0.1`.
- `catalog_client.py` is the reference client of section 4: public addresses only, except a loopback
  or private catalog host, which only then allows plain http to that one host; a run cut short keeps
  its bytes and its ETags (`.client-state.json` in the output folder, removed at the end) and resumes
  when started again. The result passes `python android/scripts/read_pack.py <folder> --verify`.
