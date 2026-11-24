"""How small does a routing pack get for download? (gzip -6 and xz -6 of the pack tar)

The engine needs the tar uncompressed (it memory-maps it), so compression only
matters for the download; the install step would decompress.

usage: python measure_compression.py [state ...]     (default: pa oh)
"""
from __future__ import annotations

import gzip
import json
import lzma
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common as C  # noqa: E402


def measure(path: Path) -> dict:
    out = {"raw_bytes": path.stat().st_size}
    for name, opener in (("gzip6", lambda f: gzip.GzipFile(fileobj=f, mode="wb", compresslevel=6, mtime=0)),
                         ("xz6", lambda f: lzma.LZMAFile(f, mode="wb", preset=6))):
        tmp = path.with_suffix(path.suffix + "." + name)
        t0 = time.perf_counter()
        with open(path, "rb") as src, open(tmp, "wb") as raw, opener(raw) as dst:
            while b := src.read(1 << 22):
                dst.write(b)
        out[name] = {"bytes": tmp.stat().st_size, "ratio": round(tmp.stat().st_size / out["raw_bytes"], 3),
                     "seconds_single_thread": round(time.perf_counter() - t0, 1)}
        tmp.unlink()
    return out


if __name__ == "__main__":
    states = sys.argv[1:] or list(C.STATES)
    res = {}
    for st in states:
        man = C.load_json(C.PACKS / st / "manifest.json")
        res[st] = measure(C.PACKS / st / man["tar"])
        print(st, json.dumps(res[st]), flush=True)
    C.save_json(C.RESULTS / "compression.json", res)
