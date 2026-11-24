"""Small made-up inputs for the comma part tests: vector tiles, a PMTiles map, a pack folder, Mapd cells,
a wheel. No network, no real data. The place in the made-up pack is Erie, PA (a public town)."""
from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
import sys
import tarfile
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
COMMA = HERE.parent
REPO = COMMA.parents[1]
sys.path.insert(0, str(COMMA))
sys.path.insert(0, str(REPO / "packbuilder" / "map"))

import common  # noqa: E402

# Erie, PA and its neighbourhood: one two-degree group of Mapd (42, -82)
ERIE_BOUNDS = [-80.2, 42.0, -80.0, 42.2]
PA_BOUNDS = [-80.519851, 39.719799, -74.689561, 42.516072]
CREATED = "2026-11-21T18:59:00Z"
SNAPSHOT = "2026-11-20"
ADDR_CREDIT = ("© OpenStreetMap contributors; U.S. Census Bureau TIGER/Line; USDOT National Address Database; "
               "City of Scranton GIS")
WHEEL_NAME = "pyvalhalla-3.9.1-cp312-abi3-manylinux_2_28_aarch64.whl"


# ------------------------------------------------------------------ StarPilot's road_tiles.py (read only)
def road_tiles_path() -> Path | None:
    for base in (os.environ.get("STARPILOT_SRC"), REPO / "data" / "starpilot" / "src", REPO / "data" / "starpilot" / "fork"):
        if base:
            p = Path(base) / "openpilot" / "starpilot" / "navigation" / "road_tiles.py"
            if p.is_file():
                return p
    return None


def starpilot_road_tiles():
    """StarPilot's own road_tiles module, loaded from that one file (needs numpy); None when absent."""
    p = road_tiles_path()
    if p is None or importlib.util.find_spec("numpy") is None:
        return None
    name = "starpilot_road_tiles"
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, p)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


# ------------------------------------------------------------------ vector tiles and a PMTiles map
def _zigzag(n: int) -> int:
    return (n << 1) ^ (n >> 31)


def geometry(lines: list[list[tuple[int, int]]], point: bool = False) -> bytes:
    import mvt
    vals, cx, cy = [], 0, 0
    for line in lines:
        x, y = line[0]
        vals += [(1 & 7) | (1 << 3), _zigzag(x - cx), _zigzag(y - cy)]
        cx, cy = x, y
        if point:
            continue
        vals.append((2 & 7) | ((len(line) - 1) << 3))
        for x, y in line[1:]:
            vals += [_zigzag(x - cx), _zigzag(y - cy)]
            cx, cy = x, y
    return b"".join(mvt.enc_varint(v) for v in vals)


def mvt_value(v) -> bytes:
    import mvt
    if isinstance(v, bool):
        return mvt.enc_varint(7 << 3) + mvt.enc_varint(int(v))
    return mvt.enc_field(1, str(v).encode("utf-8"))


def mvt_tile(layers: dict, extent: int = 4096) -> bytes:
    """layers: {name: [(props, ftype (1 point, 2 line), [lines])]} -> one uncompressed MVT tile."""
    import mvt
    out = b""
    for name, feats in layers.items():
        keys, values, fbytes = [], [], []
        for i, (props, ftype, lines) in enumerate(feats):
            tags = []
            for k, v in props.items():
                if k not in keys:
                    keys.append(k)
                enc = mvt_value(v)
                if enc not in values:
                    values.append(enc)
                tags += [keys.index(k), values.index(enc)]
            fbytes.append(mvt.enc_feature(i + 1, ftype, tags, geometry(lines, point=ftype == 1)))
        body = mvt.enc_varint(15 << 3) + mvt.enc_varint(2) + mvt.enc_field(1, name.encode())
        body += b"".join(mvt.enc_field(2, f) for f in fbytes)
        body += b"".join(mvt.enc_field(3, k.encode()) for k in keys)
        body += b"".join(mvt.enc_field(4, v) for v in values)
        body += mvt.enc_varint(5 << 3) + mvt.enc_varint(extent)
        out += mvt.enc_field(3, body)
    return out


def write_pmtiles(path: Path, tiles: dict, compress: bool = True) -> None:
    """tiles: {(z, x, y): uncompressed MVT bytes} -> a PMTiles v3 file (needs the pmtiles package)."""
    import gzip
    from pmtiles.tile import Compression, TileType, zxy_to_tileid
    from pmtiles.writer import Writer
    with open(path, "wb") as f:
        w = Writer(f)
        for (z, x, y) in sorted(tiles, key=lambda k: zxy_to_tileid(*k)):
            data = tiles[(z, x, y)]
            w.write_tile(zxy_to_tileid(z, x, y), gzip.compress(data, mtime=0) if compress else data)
        w.finalize({"tile_type": TileType.MVT, "tile_compression": Compression.GZIP if compress else Compression.NONE,
                    "min_lon_e7": int(ERIE_BOUNDS[0] * 1e7), "min_lat_e7": int(ERIE_BOUNDS[1] * 1e7),
                    "max_lon_e7": int(ERIE_BOUNDS[2] * 1e7), "max_lat_e7": int(ERIE_BOUNDS[3] * 1e7),
                    "center_zoom": 11, "center_lon_e7": int(-80.1 * 1e7), "center_lat_e7": int(42.1 * 1e7)},
                   {"name": "test"})


def write_pmtiles_raw(path: Path, tiles: dict, internal) -> None:
    """A PMTiles v3 file written by hand (gzip tiles, one root directory, no leaf directories), its
    directory and metadata stored with the given internal compression (pmtiles' Compression.GZIP or
    NONE): for the header checks of roads.py, which pmtiles' own Writer (always gzip) cannot make."""
    import gzip
    from pmtiles.tile import Compression, Entry, TileType, serialize_directory, serialize_header, zxy_to_tileid
    entries, data = [], b""
    for key in sorted(tiles, key=lambda k: zxy_to_tileid(*k)):
        blob = gzip.compress(tiles[key], mtime=0)
        entries.append(Entry(zxy_to_tileid(*key), len(data), len(blob), 1))
        data += blob
    root = serialize_directory(entries)           # gzip, as pmtiles writes it
    meta = b'{"name": "test"}'
    if internal == Compression.GZIP:
        meta = gzip.compress(meta, mtime=0)
    else:
        root = gzip.decompress(root)
    zooms = [k[0] for k in tiles]
    after = 127 + len(root) + len(meta)
    header = {"root_offset": 127, "root_length": len(root), "metadata_offset": 127 + len(root),
              "metadata_length": len(meta), "leaf_directory_offset": after, "leaf_directory_length": 0,
              "tile_data_offset": after, "tile_data_length": len(data), "addressed_tiles_count": len(entries),
              "tile_entries_count": len(entries), "tile_contents_count": len(entries), "clustered": True,
              "internal_compression": internal, "tile_compression": Compression.GZIP, "tile_type": TileType.MVT,
              "min_zoom": min(zooms), "max_zoom": max(zooms),
              "min_lon_e7": int(ERIE_BOUNDS[0] * 1e7), "min_lat_e7": int(ERIE_BOUNDS[1] * 1e7),
              "max_lon_e7": int(ERIE_BOUNDS[2] * 1e7), "max_lat_e7": int(ERIE_BOUNDS[3] * 1e7)}
    Path(path).write_bytes(serialize_header(header) + root + meta + data)


def tile_of(lat: float, lon: float, z: int) -> tuple[int, int, int]:
    import math
    n = 1 << z
    x = int((lon + 180.0) / 360.0 * n)
    s = math.sin(math.radians(lat))
    y = int((0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n)
    return z, x, y


# ------------------------------------------------------------------ a pack folder
def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fake_pack(folder: Path, bounds=ERIE_BOUNDS, with_addresses: bool = True, ranking_sha256: str | None = None,
              pack_version: int = 202611211859) -> dict:
    """A format-3 pack folder whose part files are small made-up bytes with the right sizes and hashes."""
    folder.mkdir(parents=True, exist_ok=True)
    rank = ranking_sha256 or sha(common.text_bytes(REPO / "packbuilder" / "search" / "ranking.json"))
    blobs = {"map": ("map.pmtiles", b"PMTiles\x03" + bytes(range(200)) * 50),
             "routing": ("routing.tar", bytes((i * 7) % 256 for i in range(150000))),
             "routing_index": ("routing.json", b'{"format": "beamline-routing-pack/1"}\n'),
             "search": ("search.sqlite", b"SQLite format 3\x00" + bytes(70000)),
             "addresses": ("addresses.sqlite", b"SQLite format 3\x00" + bytes((i * 13) % 256 for i in range(90000)))}
    if not with_addresses:
        blobs.pop("addresses")
    parts = {}
    for name, (file, data) in blobs.items():
        (folder / file).write_bytes(data)
        parts[name] = {"file": file, "attribution": "© OpenStreetMap contributors", "bytes": len(data), "sha256": sha(data)}
    parts["routing"].update(format="valhalla-tar", format_version=1, valhalla_version="3.9.1", graph_build="test")
    parts["search"].update(format="beamline-search-sqlite", format_version=1, ranking_sha256=rank)
    if with_addresses:
        parts["addresses"]["attribution"] = ADDR_CREDIT
    manifest = {"manifest_version": 3, "pack_id": "us-pa", "pack_version": pack_version, "name": "Pennsylvania",
                "country": "US", "state": "PA", "osm_snapshot": SNAPSHOT, "created_utc": CREATED,
                "bounds": list(bounds), "attribution": "© OpenStreetMap contributors", "parts": parts}
    (folder / "manifest.json").write_bytes((json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))
    return manifest


# ------------------------------------------------------------------ comma parts without the real builders' inputs
def fake_roads_tar(path: Path, bounds=ERIE_BOUNDS) -> list[str]:
    """roads.tar with one made-up tile per zoom at the middle of the bounds (encoded with roads.encode_tile)."""
    import roads
    lat, lon = (bounds[1] + bounds[3]) / 2, (bounds[0] + bounds[2]) / 2
    names = []
    with common.TarOut(path, mtime=common.utc_epoch(CREATED)) as t:
        for z in common.ROAD_ZOOMS:
            _, x, y = tile_of(lat, lon, z)
            rt = roads.encode_tile({roads.MOTORWAY: [[(0, 0), (4096, 4096)]], roads.STREET: [[(10, 10), (20, 30), (40, 50)]]})
            t.add_bytes(f"{z}/{x}/{y}.rt", rt)
            names.append(f"{z}/{x}/{y}.rt")
    return names


def fake_offline(offline: Path, box: dict, extra: str | None = None, drop: str | None = None) -> None:
    """Mapd's output tree for a box with made-up cell bytes (structure only: Mapd would not admit them)."""
    for lat, lon in common.mapd_groups(box):
        for cell in common.mapd_cells(lat, lon):
            rel = f"{lat}/{lon}/{cell}"
            if rel == drop:
                continue
            p = offline / str(lat) / str(lon) / cell
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(cell.encode() + b"\n")
    if extra:
        (offline / extra).parent.mkdir(parents=True, exist_ok=True)
        (offline / extra).write_bytes(b"x")


def fake_mapd_tar(path: Path, bounds=ERIE_BOUNDS, work: Path | None = None) -> dict:
    import mapd
    box = common.mapd_box(bounds)
    work = work or path.parent / "mapd-fixture-work"
    fake_offline(work / "offline", box)
    mtime = common.utc_epoch(SNAPSHOT)
    mapd.pack_groups(work / "offline", work / "archives", box, mtime)
    mapd.write_outer(work / "archives", path, box, mtime)
    info = {**box, "source_date": SNAPSHOT, "bytes": path.stat().st_size, "sha256": common.sha256_file(path)}
    path.with_suffix(".json").write_text(json.dumps(info, indent=1) + "\n", encoding="utf-8")
    return info


def fake_parts(folder: Path, bounds=ERIE_BOUNDS, manifest_path: Path | None = None) -> None:
    import search_code
    folder.mkdir(parents=True, exist_ok=True)
    search_code.build(folder / "search-code.tar", common.utc_epoch(CREATED), manifest_path)
    fake_roads_tar(folder / "roads.tar", bounds)
    fake_mapd_tar(folder / "mapd.tar", bounds, folder.parent / f".{folder.name}-mapd-work")


def fake_wheel(path: Path) -> None:
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("valhalla/__init__.py", "# made up for a test\n")
        z.writestr("pyvalhalla-3.9.1.dist-info/METADATA", "Name: pyvalhalla\nVersion: 3.9.1\n")


def tar_with(path: Path, members: list[tuple[str, bytes | None, str]]) -> None:
    """A tar with chosen members: (name, data, kind) where kind is 'file', 'dir' or 'symlink'."""
    with tarfile.open(path, "w", format=tarfile.USTAR_FORMAT) as t:
        for name, data, kind in members:
            ti = tarfile.TarInfo(name)
            if kind == "dir":
                ti.type = tarfile.DIRTYPE
                t.addfile(ti)
            elif kind == "symlink":
                ti.type, ti.linkname = tarfile.SYMTYPE, data.decode() if data else "/etc/passwd"
                t.addfile(ti)
            else:
                ti.size = len(data)
                t.addfile(ti, io.BytesIO(data))
