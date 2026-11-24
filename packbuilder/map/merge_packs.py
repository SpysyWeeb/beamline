"""Merge several overlapping PMTiles vector packs into one file.

Usage (from the repo root):
    .venv/map/Scripts/python packbuilder/map/merge_packs.py OUT.pmtiles IN1.pmtiles IN2.pmtiles [...]
        [--no-dedupe] [--force]

Why not `pmtiles merge`: go-pmtiles merge combines *disjoint* archives only.
Two state packs share every tile that the border runs through (and, at low
zooms, the same few big tiles), so those tiles have to be merged feature by
feature. This script:
  - copies a tile unchanged when only one input has it (no decode);
  - when several inputs have it, unions the MVT layers (packbuilder/map/mvt.py):
    features are appended with their tag indices remapped, and a feature that
    is identical in id, type, geometry and tags to one already present is
    dropped (the same OSM object in the overlap band of both extracts);
  - writes a clustered PMTiles v3 archive (pmtiles Python writer).
A record with wall time, peak memory and counts goes to OUT.merge.json.
"""
import argparse
import gzip
import json
import sys
import time
from pathlib import Path

import psutil

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mvt  # noqa: E402
from pmtiles.reader import Reader, MmapSource  # noqa: E402
from pmtiles.tile import Compression, TileType, tileid_to_zxy  # noqa: E402
from pmtiles.writer import Writer  # noqa: E402
from tile_stats import entries  # noqa: E402


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out")
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--no-dedupe", action="store_true")
    ap.add_argument("--force", action="store_true")
    a = ap.parse_args()
    out = Path(a.out)
    if out.exists() and not a.force:
        print(f"skip: {out} exists")
        return 0
    t0 = time.time()
    files = [open(p, "rb") for p in a.inputs]
    srcs = [MmapSource(f) for f in files]
    readers = [Reader(s) for s in srcs]
    headers = [r.header() for r in readers]
    metas = [r.metadata() for r in readers]
    for h in headers:
        if h["tile_type"] != TileType.MVT or h["tile_compression"] != Compression.GZIP:
            raise SystemExit("only gzip-compressed MVT packs are supported")
    index = {}  # tile_id -> list of (input no, offset, length)
    for i, (s, h) in enumerate(zip(srcs, headers)):
        for tid, off, ln, run in entries(s, h):
            for k in range(run):
                index.setdefault(tid + k, []).append((i, off, ln))
    overlap = {z: 0 for z in range(0, 16)}
    copied = merged = dropped = 0
    out.parent.mkdir(parents=True, exist_ok=True)
    with open(out, "wb") as f:
        w = Writer(f)
        for tid in sorted(index):
            parts = index[tid]
            if len(parts) == 1:
                i, off, ln = parts[0]
                base = headers[i]["tile_data_offset"]
                w.write_tile(tid, srcs[i](base + off, ln))
                copied += 1
                continue
            z = tileid_to_zxy(tid)[0]
            overlap[z] = overlap.get(z, 0) + 1
            datas = [gzip.decompress(srcs[i](headers[i]["tile_data_offset"] + off, ln)) for i, off, ln in parts]
            tile = datas[0]
            for other in datas[1:]:
                if a.no_dedupe:
                    tile = tile + other  # plain concatenation: duplicate layer names (for comparison only)
                else:
                    tile, d = mvt.merge_tiles(tile, other)
                    dropped += d
            data = gzip.compress(tile, compresslevel=6, mtime=0)
            w.write_tile(tid, data)
            merged += 1
        hdr = dict(headers[0])
        hdr["tile_type"] = TileType.MVT
        hdr["tile_compression"] = Compression.GZIP
        for k, fn in (("min_lon_e7", min), ("min_lat_e7", min), ("max_lon_e7", max), ("max_lat_e7", max)):
            hdr[k] = fn(h[k] for h in headers)
        hdr["center_lon_e7"] = (hdr["min_lon_e7"] + hdr["max_lon_e7"]) // 2
        hdr["center_lat_e7"] = (hdr["min_lat_e7"] + hdr["max_lat_e7"]) // 2
        hdr["center_zoom"] = max(0, min(h["center_zoom"] for h in headers) - 1)
        meta = dict(metas[0])
        layers = {}
        for m in metas:
            for vl in m.get("vector_layers", []):
                layers.setdefault(vl["id"], vl)
        meta["vector_layers"] = list(layers.values())
        meta["description"] = "merged from " + ", ".join(Path(p).name for p in a.inputs)
        w.finalize(hdr, meta)
    for f in files:
        f.close()
    proc = psutil.Process()
    peak = getattr(proc.memory_info(), "peak_wset", None) or proc.memory_info().rss
    rec = {
        "out": out.name, "inputs": [Path(p).name for p in a.inputs],
        "input_bytes": [Path(p).stat().st_size for p in a.inputs],
        "output_bytes": out.stat().st_size,
        "tiles_copied": copied, "tiles_merged": merged,
        "merged_tiles_per_zoom": {z: n for z, n in overlap.items() if n},
        "duplicate_features_dropped": dropped,
        "wall_seconds": round(time.time() - t0, 1),
        "peak_memory_bytes": peak,
        "peak_memory_gib": round(peak / 1024**3, 2),
        "peak_method": "psutil peak working set of this Python process (Windows)",
    }
    out.with_suffix(".merge.json").write_text(json.dumps(rec, indent=1))
    print(json.dumps(rec, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
