"""Build the drawn US map of the state picker: android/feature/states/src/main/assets/states/us_states.json.

    python packbuilder/catalog/build_state_shapes.py [--check]

docs/architecture-downloads.md 6.8 and 7.4. Reads data/map/work/overview/ne_overview.gpkg read-only
(written by packbuilder/map/overview/build_overview.py from Natural Earth 5.1.2, public domain):
the 51 rows of ne_10m_admin_1_states_provinces with adm0_a3 = 'USA' (50 states and DC). Projects
them with an Albers equal-area conic per frame (the lower 48; Alaska and Hawaii as insets at the
lower left, as US wall maps draw them), simplifies (Douglas-Peucker per ring, tolerance 0.25 map
units; each state keeps its largest part, other parts under 0.5 square map units are dropped),
finds the neighbours on the unsimplified outlines (touching within 0.5 map units) and writes the
asset in format beamline-state-shapes/1 (LF, compact): integers in tenths of a map unit, each
ring's first pair absolute and the rest differences.

--check rebuilds in memory and fails when the committed file differs or is larger than 300 KB.
Standard library only.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import sqlite3
import struct
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
GPKG = REPO / "data/map/work/overview/ne_overview.gpkg"
SOURCE_ZIP = REPO / "data/map/work/data/sources/natural_earth_vector.gpkg.zip"
ASSET = REPO / "android/feature/states/src/main/assets/states/us_states.json"
FORMAT = "beamline-state-shapes/1"
SOURCE = "Natural Earth 5.1.2 ne_10m_admin_1_states_provinces (public domain)"
# The pinned Natural Earth download the overview was built from (packbuilder/map/fetch_sources.py,
# recorded in data/map/packs-v2/us-overview-ne512-tiger2026-z0-7.build.json).
SOURCE_SHA256 = "b9e2e7b3d5c2c59593f6eafc19be2ba87fb04788a90c57ccd6cf32a587b6ecc3"
BUDGET = 300 * 1024

WIDTH = 1000.0            # the lower 48, in map units
SCALE = 10                # integers in the asset are tenths of a map unit
TOLERANCE = 0.25          # Douglas-Peucker, map units (about 1.5 km in the lower 48)
MIN_PART = 0.5            # square map units
TOUCH = 0.5               # map units, on the unsimplified outlines
ALASKA_SCALE = 0.35       # of the lower 48's scale (reduced only if Alaska would not fit its box)
INSET_MARGIN = 20.0
ALASKA_BOX = (230.0, 170.0)
HAWAII_BOX = (140.0, 80.0)

FRAMES = {
    "lower48": dict(lat1=29.5, lat2=45.5, lat0=23.0, lon0=-96.0),
    "alaska": dict(lat1=55.0, lat2=65.0, lat0=50.0, lon0=-154.0),
    "hawaii": dict(lat1=8.0, lat2=18.0, lat0=3.0, lon0=-157.0),
}


def frame_for(lon: float, lat: float) -> str | None:
    """Which frame a position belongs to: the rule StateShapes.project follows on the phone."""
    if lat >= 50.0 and (lon <= -129.0 or lon >= 170.0):
        return "alaska"
    if 15.0 <= lat <= 25.0 and -165.0 <= lon <= -150.0:
        return "hawaii"
    if 10.0 <= lat <= 60.0 and -140.0 <= lon <= -50.0:
        return "lower48"
    return None


def frame_of_state(code: str) -> str:
    return {"AK": "alaska", "HI": "hawaii"}.get(code, "lower48")


def albers(f: dict):
    """Spherical Albers equal-area conic (Snyder, Map Projections: A Working Manual, eq. 14-1 to 14-6), R = 1."""
    p1, p2, p0 = (math.radians(f[k]) for k in ("lat1", "lat2", "lat0"))
    n = (math.sin(p1) + math.sin(p2)) / 2
    c = math.cos(p1) ** 2 + 2 * n * math.sin(p1)
    rho0 = math.sqrt(c - 2 * n * math.sin(p0)) / n
    lon0 = f["lon0"]

    def project(lon: float, lat: float):
        if lon > 0:                       # Alaska's Aleutians east of 180 degrees
            lon -= 360.0
        rho = math.sqrt(c - 2 * n * math.sin(math.radians(lat))) / n
        theta = n * math.radians(lon - lon0)
        return rho * math.sin(theta), rho0 - rho * math.cos(theta)
    return project


# ---------------------------------------------------------------------------------- GeoPackage

def wkb_polygons(blob: bytes) -> list:
    """GeoPackage geometry (header + WKB) -> [polygon], each a list of rings of (lon, lat)."""
    if blob[:2] != b"GP":
        raise ValueError("not a GeoPackage geometry")
    flags = blob[3]
    env = (flags >> 1) & 7
    size = 8 + {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}[env]
    data, pos = blob[size:], 0

    def read_geom():
        nonlocal pos
        order = "<" if data[pos] == 1 else ">"
        gtype = struct.unpack_from(order + "I", data, pos + 1)[0]
        pos += 5
        dims = 2
        if gtype & 0x80000000 or gtype & 0x40000000:      # EWKB Z/M flags
            dims += bool(gtype & 0x80000000) + bool(gtype & 0x40000000)
            gtype &= 0xFFFF
        elif gtype >= 1000:
            dims += {1: 1, 2: 1, 3: 2}[gtype // 1000]
            gtype %= 1000
        if gtype == 3:
            nrings = struct.unpack_from(order + "I", data, pos)[0]
            pos += 4
            rings = []
            for _ in range(nrings):
                npts = struct.unpack_from(order + "I", data, pos)[0]
                pos += 4
                pts = []
                for _ in range(npts):
                    xy = struct.unpack_from(order + "dd", data, pos)
                    pts.append(xy)
                    pos += 8 * dims
                rings.append(pts)
            return [rings]
        if gtype == 6:
            n = struct.unpack_from(order + "I", data, pos)[0]
            pos += 4
            out = []
            for _ in range(n):
                out += read_geom()
            return out
        raise ValueError(f"geometry type {gtype} is not a polygon")
    return read_geom()


def read_states(gpkg: Path) -> list:
    if not gpkg.is_file():
        raise SystemExit("run packbuilder/map/overview/build_overview.py first")
    con = sqlite3.connect(f"file:{gpkg.as_posix()}?mode=ro", uri=True)
    try:
        rows = con.execute(
            "SELECT postal, name, fips, latitude, longitude, geom FROM ne_10m_admin_1_states_provinces "
            "WHERE adm0_a3 = 'USA' ORDER BY postal").fetchall()
    finally:
        con.close()
    return [dict(code=r[0], name=r[1], fips=int(str(r[2]).removeprefix("US")), lat=r[3], lon=r[4], polygons=wkb_polygons(r[5]))
            for r in rows]


# ---------------------------------------------------------------------------------- geometry

def ring_area(r) -> float:
    a = 0.0
    for i in range(len(r)):
        x1, y1 = r[i - 1]
        x2, y2 = r[i]
        a += x1 * y2 - x2 * y1
    return abs(a) / 2


def seg_dist(p, a, b) -> float:
    (px, py), (ax, ay), (bx, by) = p, a, b
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - ax - t * dx, py - ay - t * dy)


def douglas_peucker(pts, tol):
    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        best, idx = 0.0, -1
        for k in range(i + 1, j):
            d = seg_dist(pts[k], pts[i], pts[j])
            if d > best:
                best, idx = d, k
        if best > tol and idx >= 0:
            keep[idx] = True
            stack += [(i, idx), (idx, j)]
    return [p for p, k in zip(pts, keep) if k]


def simplify_ring(ring, tol):
    """A closed ring (no repeated last point) simplified with two anchors: its first point and the point farthest from it."""
    if len(ring) < 4:
        return ring
    far = max(range(len(ring)), key=lambda i: math.hypot(ring[i][0] - ring[0][0], ring[i][1] - ring[0][1]))
    a = douglas_peucker(ring[:far + 1], tol)
    b = douglas_peucker(ring[far:] + [ring[0]], tol)
    return a[:-1] + b[:-1]


def open_ring(r):
    return r[:-1] if len(r) > 1 and r[0] == r[-1] else list(r)


# ---------------------------------------------------------------------------------- build

def build() -> bytes:
    states = read_states(GPKG)
    proj = {k: albers(f) for k, f in FRAMES.items()}
    # projected (R = 1) outlines per state
    for s in states:
        p = proj[frame_of_state(s["code"])]
        s["proj"] = [[open_ring([p(lon, lat) for lon, lat in ring]) for ring in poly] for poly in s["polygons"]]

    def extent(sts):
        xs = [x for s in sts for poly in s["proj"] for ring in poly for x, _ in ring]
        ys = [y for s in sts for poly in s["proj"] for ring in poly for _, y in ring]
        return min(xs), min(ys), max(xs), max(ys)

    frames = {}
    l48 = [s for s in states if frame_of_state(s["code"]) == "lower48"]
    x0, y0, x1, y1 = extent(l48)
    k48 = WIDTH / (x1 - x0)
    height = math.ceil((y1 - y0) * k48)
    # map = (k * x + dx, -k * y + dy): y grows downwards
    frames["lower48"] = dict(FRAMES["lower48"], k=k48, dx=-x0 * k48, dy=y1 * k48)

    boxes = {}
    ak_box = (INSET_MARGIN, height - INSET_MARGIN - ALASKA_BOX[1], INSET_MARGIN + ALASKA_BOX[0], height - INSET_MARGIN)
    hi_box = (ak_box[2] + INSET_MARGIN, height - INSET_MARGIN - HAWAII_BOX[1], ak_box[2] + INSET_MARGIN + HAWAII_BOX[0], height - INSET_MARGIN)

    def fit(name, sts, box, scale):
        # Small parts that will be dropped do not decide the frame: measure on the kept parts only.
        kept = []
        for s in sts:
            areas = [ring_area(poly[0]) * (scale * k48) ** 2 for poly in s["proj"]]
            big = max(range(len(areas)), key=areas.__getitem__)
            kept += [poly for i, poly in enumerate(s["proj"]) if i == big or areas[i] >= MIN_PART]
        xs = [x for poly in kept for x, _ in poly[0]]
        ys = [y for poly in kept for _, y in poly[0]]
        bw, bh = box[2] - box[0] - 4, box[3] - box[1] - 4   # 2 units of air inside the inset's outline
        k = min(scale * k48, bw / (max(xs) - min(xs)), bh / (max(ys) - min(ys)))
        cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
        mx, my = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
        frames[name] = dict(FRAMES[name], k=k, dx=cx - k * mx, dy=cy + k * my)
        boxes[name] = box

    fit("alaska", [s for s in states if s["code"] == "AK"], ak_box, ALASKA_SCALE)
    fit("hawaii", [s for s in states if s["code"] == "HI"], hi_box, 1.0)

    def to_map(frame, xy):
        f = frames[frame]
        return f["k"] * xy[0] + f["dx"], -f["k"] * xy[1] + f["dy"]

    # map-unit outlines, unsimplified (for the neighbours) and simplified (for the asset)
    for s in states:
        fr = frame_of_state(s["code"])
        s["frame"] = fr
        s["map"] = [[[to_map(fr, p) for p in ring] for ring in poly] for poly in s["proj"]]

    neighbours = find_neighbours(states)

    out_states = []
    for s in states:
        parts = s["map"]
        areas = [ring_area(poly[0]) for poly in parts]
        big = max(range(len(parts)), key=areas.__getitem__)
        rings = []
        for i, poly in enumerate(parts):
            if i != big and areas[i] < MIN_PART:
                continue
            for j, ring in enumerate(poly):
                if j > 0 and ring_area(ring) < MIN_PART:
                    continue                       # a tiny hole
                simple = simplify_ring(ring, TOLERANCE)
                ints = []
                for x, y in simple:
                    p = (round(x * SCALE), round(y * SCALE))
                    if not ints or ints[-1] != p:
                        ints.append(p)
                if len(ints) > 1 and ints[0] == ints[-1]:
                    ints.pop()
                if len(ints) >= 3:
                    rings.append(ints)
        xs = [x for r in rings for x, _ in r]
        ys = [y for r in rings for _, y in r]
        lx, ly = to_map(s["frame"], albers(FRAMES[s["frame"]])(s["lon"], s["lat"]))
        enc = []
        for r in rings:
            flat = [r[0][0], r[0][1]]
            for (ax, ay), (bx, by) in zip(r, r[1:]):
                flat += [bx - ax, by - ay]
            enc.append(flat)
        out_states.append({
            "code": s["code"], "fips": s["fips"], "name": s["name"], "inset": s["frame"],
            "label": [round(lx * SCALE), round(ly * SCALE)], "box": [min(xs), min(ys), max(xs), max(ys)],
            "neighbours": sorted(neighbours[s["code"]]), "rings": enc,
        })

    def rnd(v):
        return round(v, 6)

    asset = {
        "format": FORMAT, "source": SOURCE, "source_sha256": SOURCE_SHA256,
        "width": int(WIDTH), "height": height, "scale": SCALE,
        "frames": {k: {kk: rnd(vv) for kk, vv in f.items()} for k, f in frames.items()},
        "inset_boxes": {k: [rnd(v) for v in b] for k, b in boxes.items()},
        "states": out_states,
    }
    return (json.dumps(asset, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def find_neighbours(states) -> dict:
    """States whose unsimplified outlines come within TOUCH map units of each other (vertex to vertex, on a grid)."""
    grid: dict = {}
    for s in states:
        for poly in s["map"]:
            for ring in poly:
                for x, y in ring:
                    grid.setdefault((s["frame"], int(x // TOUCH), int(y // TOUCH)), {}).setdefault(s["code"], []).append((x, y))
    near = {s["code"]: set() for s in states}
    for (frame, gx, gy), by_state in grid.items():
        for code, pts in by_state.items():
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    other = grid.get((frame, gx + dx, gy + dy))
                    if not other:
                        continue
                    for code2, pts2 in other.items():
                        if code2 == code or code2 in near[code]:
                            continue
                        if any(math.hypot(x - x2, y - y2) <= TOUCH for x, y in pts for x2, y2 in pts2):
                            near[code].add(code2)
                            near[code2].add(code)
    return near


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true", help="fail when the committed asset differs or exceeds 300 KB")
    a = ap.parse_args(argv)
    if SOURCE_ZIP.is_file():
        h = hashlib.sha256()
        with open(SOURCE_ZIP, "rb") as f:
            for b in iter(lambda: f.read(1 << 20), b""):
                h.update(b)
        if h.hexdigest() != SOURCE_SHA256:
            print(f"warning: {SOURCE_ZIP.name} is not the pinned Natural Earth copy", file=sys.stderr)
    data = build()
    if len(data) > BUDGET:
        print(f"error: the asset would be {len(data):,} bytes, over the {BUDGET:,}-byte budget", file=sys.stderr)
        return 1
    if a.check:
        have = ASSET.read_bytes() if ASSET.is_file() else b""
        if have != data:
            print(f"error: {ASSET.relative_to(REPO).as_posix()} differs from a fresh build; run build_state_shapes.py", file=sys.stderr)
            return 1
        print(f"check ok: {ASSET.relative_to(REPO).as_posix()} ({len(data):,} bytes) equals a fresh build")
        return 0
    ASSET.parent.mkdir(parents=True, exist_ok=True)
    ASSET.write_bytes(data)
    obj = json.loads(data)
    pts = sum(len(r) // 2 for s in obj["states"] for r in s["rings"])
    print(f"wrote {ASSET.relative_to(REPO).as_posix()}: {len(obj['states'])} states, {pts:,} points, {len(data):,} bytes, "
          f"{obj['width']} x {obj['height']} map units")
    return 0


if __name__ == "__main__":
    sys.exit(main())
