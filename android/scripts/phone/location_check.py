"""Location on a real phone for report 11: time to the first fix, the accuracy Android reports,
the update rate, and whether the puck appears. Prints numbers only.

    python android/scripts/phone/location_check.py --runs 3 --seconds 60     (BEAMLINE_PHONE_SERIAL set)
    python android/scripts/phone/location_check.py --recount data/phone/private/location/<run folder> ...

The phone's position is private. Everything that could show it (the app's log lines with the
puck and the camera, the screenshot after "Show my position") is written only under
data/phone/private/ and never printed. What is printed: seconds, metres, counts and which puck
was found in the middle of the screen: pixel counts of the live (route blue) and the grey fill and
of the white ring there and in two control squares 300 px to the left and right, not the picture.
puck_state is "live", "grey" or "none". --recount analyses saved runs again without the phone.

Per run: the app is force-stopped and started at a public test view (Pittsburgh z12, so the
camera does not show the position), the app's own log (logcat --pid) is streamed for --seconds,
then "Show my position" is tapped (only with the app in front), the camera centres on the puck and
a screenshot is taken. It needs a test-extras build and grants ACCESS_FINE/COARSE_LOCATION to the
app with `pm grant` (our app only). --revoke-after takes them back at the end.

Not measurable with this build: the satellite count (the app does not read GnssStatus, and
`dumpsys location` is outside what this work may read) and which provider gave each fix (the
puck log has no provider). "Fresh" below means a fix the app logged as LIVE.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import phone  # noqa: E402

PRIVATE = phone.PRIVATE
PUCK = re.compile(r"puck (-?[\d.]+) (-?[\d.]+) (LIVE|GREY) cone=(\S+) acc=(\S+)")
# The puck's fill: the route blue when the fix is live, grey when it is old (android/ui/theme
# Theme.kt). The kit's APKs of 2026-11-15 draw candidate A's blue; the source now has candidate B
# ("Clear"), so both are looked for.
LIVE_COLOURS = {"A day": (0x5B, 0xAA, 0xFF), "A night": (0x4B, 0x96, 0xEE),
                "B day": (0x4A, 0x8C, 0xF7), "B night": (0x3A, 0x80, 0xF0)}
GREY_COLOURS = {"day": (0x8C, 0x96, 0xA1), "night": (0x6E, 0x78, 0x83)}
TOL = 14  # per channel
BOX = 90  # half the side of the square looked at (the puck with its ring is about 66 px across)
CONTROL_DX = 300  # two control squares this far left and right of the middle
MIN_FILL, MIN_RING = 150, 100  # pixels needed to call a puck seen


def decode_png(png: Path) -> tuple[int, int, int, list[bytes]]:
    """(width, height, bytes per pixel, rows) of a screencap PNG (8-bit RGB(A), no interlace)."""
    import zlib, struct  # PNG decoding without Pillow
    data = png.read_bytes()
    pos, idat, w = 8, b"", 0
    while pos < len(data):
        n, typ = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        if typ == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            bpp = {6: 4, 2: 3}[ctype]
        elif typ == b"IDAT":
            idat += body
        pos += 12 + n
    raw = zlib.decompress(idat)
    stride = w * bpp
    rows, prev = [], bytearray(stride)
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]
            c = prev[i - bpp] if i >= bpp else 0
            if f == 1:
                line[i] = (line[i] + a) & 255
            elif f == 2:
                line[i] = (line[i] + b) & 255
            elif f == 3:
                line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line))
        prev = line
    return w, h, bpp, rows


def count_box(rows: list[bytes], bpp: int, cx: int, cy: int) -> dict:
    """Pixels of the live colours, the grey colours and white (the ring) in a square around cx, cy."""
    live = grey = white = 0
    for y in range(cy - BOX, cy + BOX + 1):
        r = rows[y]
        for x in range(cx - BOX, cx + BOX + 1):
            px = r[x * bpp:x * bpp + 3]
            if any(all(abs(px[k] - ref[k]) <= TOL for k in range(3)) for ref in LIVE_COLOURS.values()):
                live += 1
            elif any(all(abs(px[k] - ref[k]) <= TOL for k in range(3)) for ref in GREY_COLOURS.values()):
                grey += 1
            elif all(v >= 245 for v in px):
                white += 1
    return {"live": live, "grey": grey, "white": white}


def puck_check(png: Path) -> dict:
    """Which puck (live or grey) is in the middle of the screenshot, by pixel counts, against two
    control squares left and right. Numbers only: the picture is never printed or copied."""
    w, h, bpp, rows = decode_png(png)
    cx, cy = w // 2, h // 2
    mid = count_box(rows, bpp, cx, cy)
    ctl = [count_box(rows, bpp, cx + dx, cy) for dx in (-CONTROL_DX, CONTROL_DX)]
    if mid["live"] >= MIN_FILL and mid["white"] >= MIN_RING:
        state = "live"
    elif mid["grey"] >= MIN_FILL and mid["white"] >= MIN_RING:
        state = "grey"
    else:
        state = "none"
    return {"puck_state": state, "puck_seen": state != "none", "puck_live_px": mid["live"], "puck_grey_px": mid["grey"],
            "puck_white_px": mid["white"], "control_live_px": max(c["live"] for c in ctl),
            "control_grey_px": max(c["grey"] for c in ctl), "control_white_px": max(c["white"] for c in ctl)}


def analyse_log(lines: list[str]) -> dict:
    """Times (seconds after the app asked for updates) and accuracies from the app's puck lines.
    The puck line is written on every change of the puck, so a new fix that repeats the last one
    shows only when the puck was grey before (the app greys a fix older than 10 s): the times the
    puck turns LIVE again give the fix interval when it is longer than that."""
    t_req = next((phone.log_time(l) for l in lines if "location updates from" in l), None)
    fixes = []
    for l in lines:
        m = PUCK.search(l)
        if m and phone.log_time(l) is not None:
            acc = None if m.group(5) == "null" else float(m.group(5))
            fixes.append((phone.log_time(l), m.group(1), m.group(2), m.group(3), acc))
    res = {"updates_requested": t_req is not None}
    if t_req is None or not fixes:
        return res
    distinct, last = [], None
    for t, la, lo, look, acc in fixes:
        key = (la, lo, acc)
        if key != last:
            distinct.append((t, look, acc))
            last = key
    live = [d for d in distinct if d[1] == "LIVE"]
    res["first_puck_s"] = round(fixes[0][0] - t_req, 2)
    res["first_puck_look"] = fixes[0][3]
    res["first_live_s"] = round(next(f[0] for f in fixes if f[3] == "LIVE") - t_req, 2) if any(f[3] == "LIVE" for f in fixes) else None
    for lim in (20, 10, 5):
        hit = next((f for f in fixes if f[3] == "LIVE" and f[4] is not None and f[4] <= lim), None)
        res[f"first_acc_le_{lim}m_s"] = round(hit[0] - t_req, 2) if hit else None
    accs = [f[4] for f in fixes if f[3] == "LIVE" and f[4] is not None]
    if accs:
        res["acc_m_first"] = accs[0]
        res["acc_m_median"] = statistics.median(accs)
        res["acc_m_min"], res["acc_m_max"] = min(accs), max(accs)
    res["distinct_fixes"] = len(distinct)
    # the look over time: LIVE again after GREY = a new fix
    turned_live = [round(f[0] - t_req, 1) for p, f in zip(fixes, fixes[1:]) if p[3] == "GREY" and f[3] == "LIVE"]
    turned_grey = [round(f[0] - t_req, 1) for p, f in zip(fixes, fixes[1:]) if p[3] == "LIVE" and f[3] == "GREY"]
    res["turned_live_s"], res["turned_grey_s"] = turned_live[:30], turned_grey[:30]
    if len(turned_live) >= 2:
        res["fix_interval_s_median"] = round(statistics.median(b - a for a, b in zip(turned_live, turned_live[1:])), 1)
    res["log_span_s"] = round(fixes[-1][0] - t_req, 1)
    return res


def one_run(ph: phone.Phone, n: int, seconds: float, out: Path) -> dict:
    ph.sh(f"am force-stop {phone.APP}")
    time.sleep(1.5)
    t_start = time.time()
    ph.start(40.44, -80.0, 12, stop_first=False)
    pid = ph.pid()
    logf = out / f"location-run{n}.log"
    with open(logf, "w", encoding="utf-8") as f:
        p = subprocess.Popen([ph.adb, "-s", ph.serial, "logcat", "-v", "threadtime", f"--pid={pid}"], stdout=f,
                             stderr=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")
        time.sleep(seconds)
        tapped = ph.tap_label("Show my position", timeout=8)
        time.sleep(6)
        shot = out / f"location-run{n}-puck.png"
        if ph.front() == phone.APP:
            shot.write_bytes(ph.run("exec-out", "screencap", "-p", binary=True))
        p.terminate()
    lines = logf.read_text(encoding="utf-8", errors="replace").splitlines()
    res = {"run": n, **analyse_log(lines), "tapped_show_my_position": tapped}
    if shot.exists():
        res.update(puck_check(shot))
    res["run_seconds"] = round(time.time() - t_start, 1)
    return res


def recount(folders: list[str]) -> None:
    """Analyse saved runs again (logs and screenshots under data/phone/private/location/), with no
    phone: prints numbers only and appends them to data/phone/private/results/location-recount.jsonl."""
    out = PRIVATE / "results" / "location-recount.jsonl"
    out.parent.mkdir(parents=True, exist_ok=True)
    for folder in folders:
        d = Path(folder).resolve()
        if PRIVATE.resolve() not in d.parents:
            sys.exit(f"{folder}: only folders under {PRIVATE} are read")
        for logf in sorted(d.glob("location-run*.log")):
            n = int(re.search(r"run(\d+)", logf.name).group(1))
            res = {"folder": d.name, "run": n, **analyse_log(logf.read_text(encoding="utf-8", errors="replace").splitlines())}
            shot = d / f"location-run{n}-puck.png"
            if shot.exists():
                res.update(puck_check(shot))
            print(res, flush=True)
            with open(out, "a", encoding="utf-8") as f:
                f.write(json.dumps({"time": datetime.now().isoformat(timespec="seconds"), **res}) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--serial", default=os.environ.get("BEAMLINE_PHONE_SERIAL", ""))
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--seconds", type=float, default=60)
    ap.add_argument("--revoke-after", action="store_true")
    ap.add_argument("--recount", nargs="+", metavar="FOLDER",
                    help="no phone: analyse saved runs again (folders under data/phone/private/location/)")
    a = ap.parse_args()
    if a.recount:
        recount(a.recount)
        return 0
    ph = phone.Phone(a.serial)
    out = PRIVATE / "location" / datetime.now().strftime("%Y%m%d-%H%M%S")
    out.mkdir(parents=True, exist_ok=True)
    print(f"location setting on the phone: {ph.sh('settings get secure location_mode').strip()} (3 = on)")
    for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"):
        ph.sh(f"pm grant {phone.APP} android.permission.{perm}")
    print(f"granted to {phone.APP}: {ph.location_granted()}")
    try:
        for n in range(1, a.runs + 1):
            res = one_run(ph, n, a.seconds, out)
            print(res, flush=True)
            phone.save_jsonl(ph, "location", res, private=True)
    finally:
        ph.sh(f"am force-stop {phone.APP}")
        if a.revoke_after:
            for perm in ("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"):
                ph.sh(f"pm revoke {phone.APP} android.permission.{perm}")
            print(f"revoked again: granted now {ph.location_granted()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
