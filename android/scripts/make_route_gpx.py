"""Make a timed GPX drive along one named road, from a pack's own map tiles.

Usage (from the repo root; needs the pmtiles package, as in .venv/android):

    .venv/android/Scripts/python android/scripts/make_route_gpx.py data/android/packs/us-pa/map.pmtiles \
        --name "Peach Street" --bbox -80.11,42.045,-80.06,42.135 --out data/android/routes/peach-st.gpx

It collects the vertices of every road line called --name in the z14 tiles inside --bbox, orders
them from north to south (good for a road that runs roughly north-south, like Peach St in Erie),
resamples every --step metres and stamps times for --speed (m/s). Only for driving the emulator's
GPS in tests (android/scripts/geo_route.py); it is not a route planner.
"""
from __future__ import annotations

import argparse
import gzip
import math
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

from pmtiles.reader import MmapSource, Reader


def varints(b: bytes, i: int = 0, end: int | None = None):
    end = len(b) if end is None else end
    while i < end:
        v = s = 0
        while True:
            c = b[i]
            i += 1
            v |= (c & 0x7F) << s
            s += 7
            if c < 0x80:
                break
        yield v, i


def fields(b: bytes):
    """(field number, wire type, value or bytes) of one protobuf message."""
    i = 0
    while i < len(b):
        key, i = next(varints(b, i))
        fn, wt = key >> 3, key & 7
        if wt == 0:
            v, i = next(varints(b, i))
            yield fn, wt, v
        elif wt == 2:
            n, i = next(varints(b, i))
            yield fn, wt, b[i:i + n]
            i += n
        elif wt == 5:
            yield fn, wt, b[i:i + 4]
            i += 4
        elif wt == 1:
            yield fn, wt, b[i:i + 8]
            i += 8
        else:
            raise ValueError(f"wire type {wt}")


def packed(b: bytes) -> list[int]:
    return [v for v, _ in varints(b)]


def lines_named(tile: bytes, name: str) -> list[list[tuple[int, int]]]:
    out = []
    for fn, _, layer in fields(tile):
        if fn != 3:
            continue
        lname, keys, values, feats = "", [], [], []
        for f, _, v in fields(layer):
            if f == 1:
                lname = v.decode()
            elif f == 3:
                keys.append(v.decode())
            elif f == 4:
                values.append(next((x.decode() for g, _, x in fields(v) if g == 1), None))
            elif f == 2:
                feats.append(v)
        if lname != "roads":
            continue
        for feat in feats:
            tags, geom, ftype = [], [], 0
            for f, _, v in fields(feat):
                if f == 2:
                    tags = packed(v)
                elif f == 4:
                    geom = packed(v)
                elif f == 3:
                    ftype = v
            props = {keys[tags[k]]: values[tags[k + 1]] for k in range(0, len(tags), 2)}
            if ftype != 2 or props.get("name") != name:
                continue
            x = y = 0
            i = 0
            part: list[tuple[int, int]] = []
            while i < len(geom):
                cmd, count = geom[i] & 7, geom[i] >> 3
                i += 1
                for _ in range(count if cmd in (1, 2) else 0):
                    x += (geom[i] >> 1) ^ -(geom[i] & 1)
                    y += (geom[i + 1] >> 1) ^ -(geom[i + 1] & 1)
                    i += 2
                    if cmd == 1 and part:
                        out.append(part)
                        part = []
                    part.append((x, y))
            if part:
                out.append(part)
    return out


def tile_xy(lon: float, lat: float, z: int) -> tuple[float, float]:
    n = 2 ** z
    return (lon + 180) / 360 * n, (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n


def lonlat(tx: float, ty: float, z: int) -> tuple[float, float]:
    n = 2 ** z
    return tx / n * 360 - 180, math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * ty / n))))


def metres(a, b) -> float:
    dy = (b[1] - a[1]) * 111_320
    dx = (b[0] - a[0]) * 111_320 * math.cos(math.radians(a[1]))
    return math.hypot(dx, dy)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("pmtiles", type=Path)
    ap.add_argument("--name", required=True)
    ap.add_argument("--bbox", required=True, help="west,south,east,north")
    ap.add_argument("--zoom", type=int, default=14)
    ap.add_argument("--step", type=float, default=15.0, help="metres between points")
    ap.add_argument("--speed", type=float, default=13.4, help="m/s (13.4 = 30 mph)")
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args()
    w, s, e, n = (float(v) for v in a.bbox.split(","))
    z = a.zoom
    x0, y0 = (int(v) for v in tile_xy(w, n, z))
    x1, y1 = (int(v) for v in tile_xy(e, s, z))
    pts = []
    with open(a.pmtiles, "rb") as f:
        r = Reader(MmapSource(f))
        for tx in range(x0, x1 + 1):
            for ty in range(y0, y1 + 1):
                data = r.get(z, tx, ty)
                if not data:
                    continue
                if data[:2] == b"\x1f\x8b":
                    data = gzip.decompress(data)
                for part in lines_named(data, a.name):
                    for px, py in part:
                        lon, lat = lonlat(tx + px / 4096, ty + py / 4096, z)
                        if w <= lon <= e and s <= lat <= n:
                            pts.append((lon, lat))
    if len(pts) < 2:
        sys.exit(f"no '{a.name}' found in z{z} tiles inside {a.bbox}")
    pts = sorted(set(pts), key=lambda p: -p[1])  # north to south
    # resample every --step metres along the ordered vertices
    out = [pts[0]]
    carry = 0.0
    for p, q in zip(pts, pts[1:]):
        d = metres(p, q)
        t = a.step - carry
        while t <= d:
            out.append((p[0] + (q[0] - p[0]) * t / d, p[1] + (q[1] - p[1]) * t / d))
            t += a.step
        carry = d - (t - a.step)
    start = datetime(2026, 11, 15, 12, 0, 0, tzinfo=timezone.utc)
    dt = a.step / a.speed
    rows = [f'    <trkpt lat="{lat:.6f}" lon="{lon:.6f}"><time>{(start + timedelta(seconds=i * dt)).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3]}Z</time></trkpt>'
            for i, (lon, lat) in enumerate(out)]
    a.out.parent.mkdir(parents=True, exist_ok=True)
    a.out.write_text('<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="Beamline make_route_gpx.py" '
                     'xmlns="http://www.topografix.com/GPX/1/1">\n  <trk><name>' + a.name + '</name><trkseg>\n'
                     + "\n".join(rows) + "\n  </trkseg></trk>\n</gpx>\n", encoding="utf-8")
    length = sum(metres(p, q) for p, q in zip(out, out[1:]))
    print(f"{a.out.as_posix()}: {len(out)} points, {length / 1000:.2f} km, {len(out) * dt:.0f} s at {a.speed} m/s, "
          f"from {out[0][1]:.5f},{out[0][0]:.5f} to {out[-1][1]:.5f},{out[-1][0]:.5f}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
