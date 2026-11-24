"""Assert the routing-pack behaviours from steps 2-4 of experiment 01.

Needs the outputs of `build.py all` (combined graph + per-state packs).
usage (repo root, routing venv):
    python packbuilder/routing/test_routing.py            # all checks
    python packbuilder/routing/test_routing.py step3      # only checks whose name contains "step3"
Exit code 0 when every check passes. Findings (error texts, numbers) are written
to data/routing/results/test_findings.json.
"""
from __future__ import annotations

import gc
import hashlib
import io
import json
import re
import shutil
import sys
import tarfile
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import common as C  # noqa: E402
import packs  # noqa: E402
import route_eval as E  # noqa: E402
import route_lib as R  # noqa: E402

FINDINGS: dict = {}
P = R.load_trips()["places"]
TRIPS = {t["id"]: t for t in R.load_trips()["trips"]}
_combined = None


def combined():
    global _combined
    if _combined is None:
        _combined = R.actor(E.combined_config())
    return _combined


def trip_route(actor, tid, preset=None, **extra):
    t = TRIPS[tid]
    locs = [R.loc(P[t["from"]]), R.loc(P[t["to"]])]
    if preset is not None:
        return R.summarize(R.run_preset(actor, locs, preset, **extra))
    return R.summarize(R.route(actor, R.request(locs, None, **extra)))


# ---------------------------------------------------------------- step 2
def test_step2_border_crossings_connected():
    rows = E.run_crossings(combined())
    FINDINGS["step2_crossings"] = rows
    ids = {r["id"] for r in rows}
    assert len(ids) >= 5, f"expected at least 5 crossings in trips.json, got {sorted(ids)}"
    for r in rows:
        assert r["ok"], f"{r['id']} {r['direction']}: {r.get('error')}"
        assert r["on_expected_road"], f"{r['id']} {r['direction']} did not use {r['road']}: {r['street_names']}"
        assert r["crosses_state_line"] and {"PA", "OH"} <= set(r["admin_states"]), \
            f"{r['id']} {r['direction']} admins {r['admin_states']}"
        assert r["detour_ratio"] < 1.6, f"{r['id']} {r['direction']} detour ratio {r['detour_ratio']}"


def test_step2_cross_border_trips():
    out = {}
    for tid in ("B1", "B2", "B3", "B4", "B5"):
        s = trip_route(combined(), tid, admin_crossings=True)
        assert s["ok"], f"{tid}: {s.get('error')}"
        out[tid] = {"km": s["length_km"], "min": round(s["time_s"] / 60, 1),
                    "states": [a.get("state_code") for a in s.get("admins", [])]}
        assert {"PA", "OH"} <= set(out[tid]["states"]), f"{tid} admins {out[tid]['states']}"
    assert "I 90" in trip_route(combined(), "B1")["street_names"], "Erie -> Cleveland should use I 90"
    FINDINGS["step2_cross_border_trips"] = out


def test_step2_admin_and_timezone_baked_into_tiles():
    """Runtime config points admin/timezone at files that do not exist: data must come from the tiles."""
    cfg = E.combined_config(**{"mjolnir.admin": "missing.sqlite", "mjolnir.timezone": "missing.sqlite"})
    a = R.actor(cfg)
    t = TRIPS["B1"]
    resp = R.route(a, R.request([R.loc(P[t["from"]]), R.loc(P[t["to"]])], admin_crossings=True,
                                date_time={"type": 1, "value": "2026-11-16T08:00"}))
    assert "error" not in resp, resp
    locs = resp["trip"]["locations"]
    FINDINGS["step2_timezone_locations"] = [{k: l.get(k) for k in ("date_time", "time_zone_name", "time_zone_offset")}
                                            for l in locs]
    assert all(l.get("time_zone_name") == "America/New_York" for l in locs), locs
    admins = R.summarize(resp)["admins"]
    FINDINGS["step2_admins_from_tiles"] = admins
    assert {a.get("country_code") for a in admins} == {"US"}, admins
    assert {a.get("state_code") for a in admins} == {"PA", "OH"}, admins


# ---------------------------------------------------------------- step 3
TEST_DEVICE = C.DATA / "device_test"


def _fresh_device(layout):
    d = TEST_DEVICE / layout
    if d.exists():
        shutil.rmtree(d)
    d.mkdir(parents=True)
    return d


def _device_actor(d):
    return R.actor(packs.engine_config(d))


def _tileset_fingerprint(d, layout):
    if layout == "tar":
        if not (d / "routing.tar").exists():
            return {}
        with tarfile.open(d / "routing.tar") as tar:
            return {m.name: hashlib.sha256(tar.extractfile(m).read()).hexdigest()
                    for m in tar.getmembers() if m.name.endswith(".gph")}
    if not (d / "tiles").exists():
        return {}
    return {p.relative_to(d / "tiles").as_posix(): C.file_hash(p) for p in (d / "tiles").rglob("*.gph")}


def _manifest_fp(*mans):
    out = {}
    for m in mans:
        out.update({t["path"]: t["sha256"] for t in m["tiles"]})
    return out


FAKES = TEST_DEVICE / "fakes"


def _fake_pack(state, name, graph_build=None, version=None, tweak=(), drop=(), wrong_sha=None, header_version=None):
    """Copy of a real pack, changed on purpose: new graph_build / valhalla_version in the manifest,
    tiles whose bytes are changed (tweak) or left out (drop), a manifest that lists a wrong
    sha256 for one tile (wrong_sha), or one tile whose header claims another Valhalla version
    (header_version, with manifest and tar hashes made consistent so only the header check can catch it)."""
    from valhalla._valhalla import GraphTileHeader

    src = C.PACKS / state
    dst = FAKES / name
    if dst.exists():
        shutil.rmtree(dst)
    dst.mkdir(parents=True)
    man = C.load_json(src / "manifest.json")
    tar_path = dst / man["tar"]
    new_sha = {}
    with tarfile.open(src / man["tar"]) as tin, tarfile.open(tar_path, "w", format=tarfile.USTAR_FORMAT) as tout:
        for m in tin.getmembers():
            if m.name in drop:
                continue
            data = tin.extractfile(m).read()
            if m.name == packs.INDEX_FILE and drop:
                data = b"\0" * (len(data) - packs.INDEX_ENTRY * len(drop))  # refilled below
            if m.name in tweak:
                b = bytearray(data)
                b[-1] ^= 0xFF  # last byte of the tile (text/shape area), header untouched
                data = bytes(b)
            if header_version and m.name == header_version[0]:
                b = bytearray(data)
                hs = GraphTileHeader.byte_size()
                i = bytes(b[:hs]).find(man["valhalla_version"].encode())
                assert i >= 0, "version string not found in tile header"
                b[i:i + len(header_version[1])] = header_version[1].encode()
                data = bytes(b)
            if m.name.endswith(".gph"):
                new_sha[m.name] = hashlib.sha256(data).hexdigest()
            ti = tarfile.TarInfo(m.name)
            ti.size = len(data)
            ti.mtime = m.mtime
            tout.addfile(ti, io.BytesIO(data))
    if drop:
        from valhalla.baldr.utils import write_tar_index
        write_tar_index(tar_path)  # index.bin must match the remaining members
    man["tiles"] = [dict(t, sha256=new_sha[t["path"]]) for t in man["tiles"] if t["path"] not in drop]
    if wrong_sha:
        for t in man["tiles"]:
            if t["path"] == wrong_sha:
                t["sha256"] = "0" * 64
    if graph_build:
        man["graph_build"] = graph_build
    if version:
        man["valhalla_version"] = version
    man["tar_sha256"] = C.file_hash(tar_path)
    man["tar_bytes"] = tar_path.stat().st_size
    C.save_json(dst / "manifest.json", man)
    return dst


def _only_in(state, other, n=1):
    """n small level-2 tiles that only `state`'s pack carries."""
    a = C.load_json(C.PACKS / state / "manifest.json")["tiles"]
    b = {t["path"] for t in C.load_json(C.PACKS / other / "manifest.json")["tiles"]}
    return [t["path"] for t in sorted((t for t in a if t["path"] not in b and t["level"] == 2),
                                      key=lambda t: t["bytes"])[:n]]


def _expect_refused(fn, *args, must_contain="", **kw):
    try:
        fn(*args, **kw)
    except packs.PackError as e:
        assert must_contain in str(e), f"refused, but with an unexpected message: {e}"
        return str(e)
    raise AssertionError(f"{fn.__name__}{args} was not refused")


def _step3(layout):
    f = FINDINGS.setdefault(f"step3_{layout}", {})
    d = _fresh_device(layout)
    f["install_pa"] = packs.install("pa", d, layout)
    fp_pa_only = _tileset_fingerprint(d, layout)

    # only PA: a route inside PA works and matches the combined graph
    a = _device_actor(d)
    pa_only = {tid: trip_route(a, tid) for tid in ("U1", "T1", "I1", "X1")}
    for tid, s in pa_only.items():
        assert s["ok"], f"PA only, {tid}: {s.get('error')}"
        ref = trip_route(combined(), tid)
        assert abs(s["time_s"] - ref["time_s"]) <= 0.001 * ref["time_s"], f"{tid}: PA-only {s['time_s']} vs combined {ref['time_s']}"

    # only PA: routes from PA into OH. Record exactly what happens.
    probes = {}
    for tid in ("B1", "B2", "B3", "B5"):
        t = TRIPS[tid]
        s = trip_route(a, tid)
        if s["ok"]:
            gap = R.endpoint_gap_m(s, P[t["from"]], P[t["to"]])
            probes[tid] = {"result": "route", "km": s["length_km"], "min": round(s["time_s"] / 60, 1),
                           "start_gap_m": gap[0], "end_gap_m": gap[1]}
        else:
            probes[tid] = {"result": "error", "error": s["error"]}
    f["pa_only_into_oh"] = probes
    # how the failure looks further into Ohio: first 170 (the point snaps to a road in the
    # PA pack's tiles that is not connected to PA), then 171 (no road in any tile near it)
    edge = R.load_trips()["pa_only_edge_probes"]
    sweep = []
    for p in edge["points"]:
        s = R.summarize(R.route(a, R.request([R.loc(P[edge["from"]]), {"lat": p["lat"], "lon": p["lon"]}])))
        got = "route" if s["ok"] else str(s["error"]).split()[0]
        sweep.append({"lon": p["lon"], "expect": p["expect"], "got": got,
                      "detail": s.get("error") or f"{s['length_km']} km, end gap {s['endpoint_gap_m'][1]} m"})
        assert got == p["expect"], f"PA only, {edge['from']} -> {p}: got {s.get('error') or 'a route'}"
    f["pa_only_edge_sweep"] = sweep

    # install OH: cross-border routes work and equal the combined graph
    del a
    gc.collect()
    f["install_oh"] = packs.install("oh", d, layout)
    a = _device_actor(d)
    both = {}
    for tid in ("B1", "B2", "B3", "B4", "B5", "I3"):
        s = trip_route(a, tid)
        ref = trip_route(combined(), tid)
        assert s["ok"], f"PA+OH, {tid}: {s.get('error')}"
        assert abs(s["time_s"] - ref["time_s"]) <= 0.001 * ref["time_s"], f"{tid}: {s['time_s']} vs combined {ref['time_s']}"
        both[tid] = {"km": s["length_km"], "min": round(s["time_s"] / 60, 1)}
    f["both_installed"] = both

    # uninstall OH: PA still works and matches the PA-only result exactly
    del a
    gc.collect()
    f["uninstall_oh"] = packs.uninstall("oh", d, layout)
    assert _tileset_fingerprint(d, layout) == fp_pa_only, "tile set after uninstalling OH differs from PA-only install"
    a = _device_actor(d)
    for tid, before in pa_only.items():
        s = trip_route(a, tid)
        assert s["ok"], f"after uninstall, {tid}: {s.get('error')}"
        assert (s["time_s"], s["length_km"], s["shape"]) == (before["time_s"], before["length_km"], before["shape"]), \
            f"{tid} changed after uninstalling OH"
    after = {tid: (trip_route(a, tid)["ok"]) for tid in ("B1", "B3")}
    f["after_uninstall_into_oh_ok"] = after
    del a
    gc.collect()


def test_step3_install_uninstall_tar_layout():
    _step3("tar")


def test_step3_install_uninstall_dir_layout():
    _step3("dir")


def test_step3_refuses_mixed_graph_builds():
    d = _fresh_device("mixed")
    packs.install("pa", d, "tar")
    fake = TEST_DEVICE / "fake_oh_pack"
    if fake.exists():
        shutil.rmtree(fake)
    shutil.copytree(C.PACKS / "oh", fake)
    man = C.load_json(fake / "manifest.json")
    man["graph_build"] = "261201-pa-oh-v3.9.1-b1"  # pretend OH came from a later build
    C.save_json(fake / "manifest.json", man)
    FINDINGS["step3_mixed_build_error"] = _expect_refused(packs.install, "oh", d, pack_dir=fake,
                                                          must_contain="graph build")


def _pack_update(layout):
    """A release update: the device has PA+OH from build A; the new release (build B) changes
    one PA tile and one OH tile and drops one PA tile. Then one state alone goes back to build A."""
    f = FINDINGS.setdefault(f"step3_update_{layout}", {})
    d = _fresh_device(f"update_{layout}")
    real = {s: C.load_json(C.PACKS / s / "manifest.json") for s in ("pa", "oh")}
    f["install_pa_oh"] = packs.install(["pa", "oh"], d, layout)
    assert _tileset_fingerprint(d, layout) == _manifest_fp(real["pa"], real["oh"])
    t_pa, d_pa = _only_in("pa", "oh", 2)
    (t_oh,) = _only_in("oh", "pa", 1)
    build_b = real["pa"]["graph_build"].rsplit("-b", 1)[0] + "-b99999"
    pa_b = _fake_pack("pa", f"pa_b_{layout}", graph_build=build_b, tweak=[t_pa], drop=[d_pa])
    oh_b = _fake_pack("oh", f"oh_b_{layout}", graph_build=build_b, tweak=[t_oh])
    new = {"pa": C.load_json(pa_b / "manifest.json"), "oh": C.load_json(oh_b / "manifest.json")}

    # one state of a new build while the neighbour stays on the old build: refused, nothing changes
    f["update_pa_alone_refused"] = _expect_refused(packs.install, "pa", d, pack_dir=pa_b, must_contain="install pa oh")
    assert _tileset_fingerprint(d, layout) == _manifest_fp(real["pa"], real["oh"])

    # both states in one call: replaced, changed tiles carry the new bytes, the dropped tile is gone
    f["update_pa_oh"] = r = packs.install(["pa", "oh"], d, layout, pack_dirs={"pa": pa_b, "oh": oh_b})
    assert r["replaced"] == ["oh", "pa"] and r["tiles_changed"] == 2 and r["tiles_removed_stale"] == 1, r
    fp = _tileset_fingerprint(d, layout)
    assert fp == _manifest_fp(new["pa"], new["oh"]), "tile set after the update differs from the new manifests"
    assert d_pa not in fp and fp[t_pa] == {t["path"]: t["sha256"] for t in new["pa"]["tiles"]}[t_pa]
    assert {m["graph_build"] for m in packs.installed(d).values()} == {build_b}

    # single-state update with no neighbour installed: back to build A, the dropped tile returns
    packs.uninstall("oh", d)
    f["update_pa_back_to_a"] = r = packs.install("pa", d)
    assert r["replaced"] == ["pa"] and r["tiles_added"] == 1 and r["tiles_changed"] == 1, r
    assert _tileset_fingerprint(d, layout) == _manifest_fp(real["pa"])
    a = _device_actor(d)
    s = trip_route(a, "U3")
    assert s["ok"], s
    del a
    gc.collect()


def test_step3_pack_update_tar_layout():
    _pack_update("tar")


def test_step3_pack_update_dir_layout():
    _pack_update("dir")


def test_step3_refuses_other_engine_version():
    f = FINDINGS.setdefault("step3_engine_version", {})
    (tile,) = _only_in("pa", "oh", 1)
    # manifest says another Valhalla version: refused even on an empty device
    fake = _fake_pack("pa", "pa_v3100", graph_build="261114-pa-oh-v3.10.0-b53087", version="3.10.0")
    d = _fresh_device("version_manifest")
    f["manifest_version"] = _expect_refused(packs.install, "pa", d, pack_dir=fake, must_contain="Valhalla 3.10.0")
    # manifest says 3.9.1 but one tile header says 3.8.0 (hashes consistent): caught by the per-tile check
    fake = _fake_pack("pa", "pa_hdr380", header_version=(tile, "3.8.0"))
    for layout in ("tar", "dir"):
        d = _fresh_device(f"version_header_{layout}")
        f[f"tile_header_{layout}"] = _expect_refused(packs.install, "pa", d, layout, pack_dir=fake,
                                                     must_contain="built by Valhalla 3.8.0")
        assert not packs.installed(d) and not (d / "routing.tar").exists(), "a refused install left files behind"


def test_step3_tile_hashes_checked_in_both_layouts():
    (tile,) = _only_in("pa", "oh", 1)
    fake = _fake_pack("pa", "pa_wrongsha", wrong_sha=tile)
    for layout in ("tar", "dir"):
        d = _fresh_device(f"wrongsha_{layout}")
        FINDINGS[f"step3_wrong_tile_sha_{layout}"] = _expect_refused(
            packs.install, "pa", d, layout, pack_dir=fake, must_contain="does not match its manifest sha256")
        assert not packs.installed(d) and not (d / "routing.tar").exists()


def test_step3_layout_is_stored_on_device():
    f = FINDINGS.setdefault("step3_layout", {})
    d = _fresh_device("layout")
    packs.install("pa", d, "tar")
    assert C.load_json(d / packs.DEVICE_FILE)["layout"] == "tar"
    f["install_oh_as_dir"] = _expect_refused(packs.install, "oh", d, "dir", must_contain="tar layout")
    f["uninstall_as_dir"] = _expect_refused(packs.uninstall, "pa", d, "dir", must_contain="tar layout")
    packs.install("oh", d)  # no layout given: the stored one is used
    packs.uninstall("oh", d)
    packs.uninstall("pa", d)
    left = sorted(p.name for p in d.iterdir())
    f["left_after_last_uninstall"] = left
    assert left == ["packs"] and not any((d / "packs").iterdir()), left
    packs.install("pa", d, "dir")  # an empty device may pick another layout
    assert C.load_json(d / packs.DEVICE_FILE)["layout"] == "dir" and (d / "tiles").exists()
    packs.uninstall("pa", d)
    assert not (d / "tiles").exists(), "tile directory left behind after the last uninstall"


def test_step3_device_config_is_portable_and_complete():
    """device/valhalla.json: no builder or home paths, the preset and matrix settings are in it,
    and an engine opened from it routes and gives a matrix without nulls (step 5)."""
    d = _fresh_device("config")
    packs.install(["pa", "oh"], d, "tar")
    text = (d / "valhalla.json").read_text(encoding="utf-8")
    cfg = json.loads(text)
    for needle in (str(C.REPO), str(C.REPO).replace("\\", "\\\\"), C.REPO.as_posix(), str(Path.home())):
        assert needle not in text, f"device config contains a local path ({needle})"
    assert not re.search(r'"[A-Za-z]:[\\/]', text), "device config contains a Windows drive path"
    assert cfg["mjolnir"]["tile_extract"] == "routing.tar" and cfg["mjolnir"]["tile_dir"] == ""
    for k, v in packs.device_settings().items():
        assert packs._get_dotted(cfg, k) == v, f"{k} = {packs._get_dotted(cfg, k)}, want {v}"
    for k, v in C.load_json(packs.PRESETS_FILE)["requires_config"].items():
        assert packs._get_dotted(cfg, k) == v, k
    a = _device_actor(d)
    assert trip_route(a, "B1")["ok"]
    import route_eval as E2
    mt = R.load_trips()["matrix_trip"]
    t = TRIPS[mt["id"]]
    base = trip_route(a, mt["id"])
    targets = E2.corridor_targets(base["shape"], mt["targets"], mt["corridor_offset_m"])
    m1 = E2.time_matrix(a, [R.loc(P[t["from"]])], targets, reps=2)
    m2 = E2.time_matrix(a, targets, [R.loc(P[t["to"]])], reps=2)
    FINDINGS["step5_device_config_matrix"] = {"1x40": m1, "40x1": m2}
    assert m1["nulls"] == 0 and m2["nulls"] == 0, (m1, m2)
    del a
    gc.collect()


# ---------------------------------------------------------------- step 4
_preset_results = None


def preset_results():
    global _preset_results
    if _preset_results is None:
        _preset_results = E.run_presets(combined(), R.load_presets(), list(TRIPS.values()))
    return _preset_results


def test_step4_every_preset_routes_every_trip():
    bad = [(t, p, s["error"]) for t, r in preset_results().items() for p, s in r.items() if not s["ok"]]
    assert not bad, bad


def test_step4_fastest_is_close_to_quickest():
    """Valhalla's auto cost is not pure time (turn and density factors), so another preset can
    occasionally be a little quicker. Record those cases; fail only beyond 10%."""
    beaten = []
    for tid, r in preset_results().items():
        for pid, s in r.items():
            if s["time_s"] < r["fastest"]["time_s"] * 0.99:
                beaten.append({"trip": tid, "preset": pid, "fastest_s": r["fastest"]["time_s"], "other_s": s["time_s"]})
            assert r["fastest"]["time_s"] <= s["time_s"] * 1.10, f"{tid}: {pid} {s['time_s']}s vs fastest {r['fastest']['time_s']}s"
    FINDINGS["step4_presets_quicker_than_fastest"] = beaten


def test_step4_avoid_highways_cuts_highway_km():
    tot = {p: sum(r[p]["highway_km"] for r in preset_results().values()) for p in ("fastest", "avoid_highways")}
    FINDINGS["step4_highway_km"] = tot
    assert tot["avoid_highways"] < 0.25 * tot["fastest"], tot


def test_step4_avoid_highways_from_a_motorway_origin():
    """exclude_highways only forbids driving onto a motorway; a car already on I-90 must
    still be sent off at the next exit (presets.json fallback_when)."""
    presets = R.load_presets()
    bc = {c["id"]: c for c in R.load_trips()["border_crossings"]}
    out = {}
    for name, locs in {"I90_pa_to_erie": [R.loc(bc["I90"]["pa"]), R.loc(P["erie_perry_sq"])],
                       # on I-90 on purpose: the engine's default search_filter, not the stop tie of every_request (batch C1)
                       "I90_pa_to_I90_oh": [R.loc(bc["I90"]["pa"]), R.loc(bc["I90"]["oh"], search_filter={"min_road_class": "service_other", "max_road_class": "motorway"})],
                       "I80_pa_to_sharon": [R.loc(bc["I80"]["pa"]), R.loc(P["sharon"])]}.items():
        fast = R.summarize(R.run_preset(combined(), locs, presets["fastest"]))
        raw = R.run_preset(combined(), locs, presets["avoid_highways"])
        avoid = R.summarize(raw)
        out[name] = {"fastest_highway_km": fast["highway_km"], "avoid_highway_km": avoid["highway_km"],
                     "fastest_km": fast["length_km"], "avoid_km": avoid["length_km"],
                     "used_fallback": raw.get("used_fallback", False)}
        assert avoid["ok"] and raw.get("used_fallback"), out[name]
        assert avoid["highway_km"] <= 0.4 * fast["highway_km"] and avoid["highway_km"] < 6, out[name]
    FINDINGS["step4_avoid_highways_motorway_origin"] = out


def test_step4_presets_file_is_valid():
    errs = [e for p in R.load_presets().values() for e in R.validate_preset(p)]
    assert not errs, errs
    # the checker itself catches what Valhalla would silently ignore
    bad = {"id": "bad", "costing": "auto", "costing_options": {"use_distnace": 0.5, "use_tolls": "x",
                                                               "use_distance": 5, "exclude_highways": "yes"}}
    assert len(R.validate_preset(bad)) == 4, R.validate_preset(bad)


# minimum number of the 21 trips on which a preset must give a different path than Fastest
# (overlap below 97%); measured values are recorded in test_findings.json
# (Shortest left in batch D, docs/next-round.md item 13)
MIN_TRIPS_CHANGED = {"simpler": 3, "eco": 5, "avoid_highways": 10, "avoid_tolls": 4}


def test_step4_every_preset_changes_routes():
    changed = {pid: sum(1 for r in preset_results().values() if r[pid]["overlap_with_fastest"] < 0.97)
               for pid in MIN_TRIPS_CHANGED}
    FINDINGS["step4_trips_changed_vs_fastest"] = changed
    low = {p: n for p, n in changed.items() if n < MIN_TRIPS_CHANGED[p]}
    assert not low, f"presets that barely change routes (noise or broken options): {low}"


def test_step4_far_snap_is_flagged():
    gaps = {tid: r["fastest"]["endpoint_gap_m"] for tid, r in preset_results().items()}
    FINDINGS["step4_endpoint_gaps_m"] = gaps
    assert all(not R.far_snap_warnings(r["fastest"]) for r in preset_results().values()), gaps
    s = R.summarize(R.route(combined(), R.request([R.loc(P["erie_perry_sq"]), {"lat": 42.30, "lon": -80.10}])))
    FINDINGS["step4_lake_erie_destination"] = {"ok": s["ok"], "endpoint_gap_m": s.get("endpoint_gap_m"),
                                               "warnings": R.far_snap_warnings(s)}
    assert s["ok"] and R.far_snap_warnings(s), s.get("endpoint_gap_m")


def test_step4_avoid_tolls_on_turnpike_trips():
    r = preset_results()
    f = {}
    for tid in ("T1", "T2"):
        f[tid] = {"fastest_toll_km": r[tid]["fastest"]["toll_km"], "avoid_tolls_toll_km": r[tid]["avoid_tolls"]["toll_km"]}
        assert r[tid]["fastest"]["toll_km"] > 10, f"{tid}: fastest should use the Turnpike: {f[tid]}"
        assert r[tid]["avoid_tolls"]["toll_km"] < 1, f"{tid}: avoid_tolls still uses {f[tid]}"
    FINDINGS["step4_tolls"] = f


def test_step4_simpler_has_fewer_maneuvers():
    r = preset_results()
    fast = sum(x["fastest"]["maneuvers"] for x in r.values())
    simp = sum(x["simpler"]["maneuvers"] for x in r.values())
    FINDINGS["step4_maneuvers_total"] = {"fastest": fast, "simpler": simp}
    assert simp < fast, (simp, fast)


def test_step4_alternates():
    rows = E.run_alternates(combined(), R.load_trips()["alternates_trips"], 2)
    FINDINGS["step4_alternates"] = [{k: r.get(k) for k in ("trip", "requested", "returned")} for r in rows]
    assert all("error" not in r for r in rows), rows
    assert all(r["returned"] <= 2 for r in rows)
    assert any(r["returned"] >= 1 for r in rows), "no alternates at all"


def test_step4_multistop_four_stops():
    m = E.run_multistop(combined())
    FINDINGS["step4_multistop"] = {k: v for k, v in m.items() if k != "maneuvers"}
    assert "error" not in m, m
    assert len(m["legs"]) == 3, m


def test_step4_stop_types():
    st = E.run_stop_types(combined())
    FINDINGS["step4_stop_types"] = {k: {kk: vv for kk, vv in v.items() if kk != "maneuvers"} for k, v in st.items()}
    # legs and arrive/depart maneuvers: only break and break_through split the trip
    assert st["break"]["legs"] == 2 and st["break_through"]["legs"] == 2, st
    assert st["through"]["legs"] == 1 and st["via"]["legs"] == 1, st
    assert st["break"]["arrive_or_depart_mid_route"] >= 2 and st["break_through"]["arrive_or_depart_mid_route"] >= 2
    assert st["through"]["arrive_or_depart_mid_route"] == 0 and st["via"]["arrive_or_depart_mid_route"] == 0
    # u-turns: break and via may turn around at the stop (same path), through and break_through may not
    # (same path as each other; the test stop is on a dead-end service road, so they turn at its end instead)
    assert st["via"]["km"] == st["break"]["km"], st
    assert st["through"]["km"] == st["break_through"]["km"], st
    assert st["through"]["time_min"] >= st["via"]["time_min"], st
    assert st["via"]["uturns"] >= 1 and st["break"]["uturns"] == 0, st


def main() -> int:
    sel = sys.argv[1] if len(sys.argv) > 1 else ""
    tests = [(n, f) for n, f in globals().items() if n.startswith("test_") and sel in n]
    failed = 0
    for name, fn in tests:
        try:
            fn()
            print(f"PASS {name}", flush=True)
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {name}: {e}", flush=True)
            traceback.print_exc(limit=2)
    C.save_json(C.RESULTS / "test_findings.json", FINDINGS)
    shutil.rmtree(FAKES, ignore_errors=True)  # several GB of modified pack copies
    print(f"{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
