"""packs-repo/build/weekly.py with its comma side, without Linux, network or GitHub:

- the run: every comma step (Mapd's setup, the three parts, their check, comma.json) is kept apart, so
  a failed or missing comma part fails only the comma side; the phone's packs and catalog.json are
  built, checked and published as if there were no comma side (step() is faked: the comma scripts run
  in-process, Mapd's admission is left out, the Linux-only builders are stood in for by fixtures);
- publish (a folder, and GitHub through a faked gh): the comma files and comma.json only when run.json
  records the comma side as passed and every file is the one comma.json gives; otherwise the published
  comma.json stays, nothing it names is deleted, and publish ends as failed after publishing;
- the extract fetch: the dated fallback when the latest Geofabrik extract cannot be used (a faked
  fetch; no request leaves the machine);
- build/setup_ubuntu.sh and the workflow.
Made-up pack (Erie, PA); standard library only."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import types
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fixtures  # noqa: E402
import catalog  # noqa: E402  (puts packbuilder/catalog on the path)
import build_catalog  # noqa: E402
import check as comma_check  # noqa: E402
import common  # noqa: E402
import search_code  # noqa: E402

spec = importlib.util.spec_from_file_location("weekly", fixtures.REPO / "packs-repo" / "build" / "weekly.py")
weekly = importlib.util.module_from_spec(spec)
spec.loader.exec_module(weekly)

BUILD = "us-pa-202611211859"
OLD, OLDER = "us-pa-202611080000", "us-pa-202611010000"
COMMA_FILES = {"search-code.tar", "roads.tar", "roads.tar.gz", "mapd.tar", "mapd.tar.gz"}


def quiet(fn, *a):
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        return fn(*a)


REAL_STEP = weekly.step
SLEEPER = [sys.executable, "-c", "import time; time.sleep(120)"]


class FakeSteps:
    """weekly.step without Linux: the comma scripts run in-process (catalog.py; check.py without
    Mapd's admission, which needs Linux; search_code.py), roads.py and mapd.py are stood in for by the
    fixtures (a step in `broken` writes a broken part and still exits 0), the phone's checks write
    passing results, and a step in `fail` exits 1 having done nothing. A step in `hang` is the real
    weekly.step running a command that sleeps, under the time limit it was given. Every call is
    recorded, with its time limit (self.limits)."""

    def __init__(self, fail=(), broken=(), missing=(), catalog_check_fails=False, hang=()):
        self.calls, self.fail, self.broken, self.missing = [], set(fail), set(broken), set(missing)
        self.catalog_check_fails, self.hang, self.limits = catalog_check_fails, set(hang), {}

    def __call__(self, run, name, cmd, env=None, check=True, time_limit_s=None):
        self.calls.append(name)
        self.limits[name] = time_limit_s
        if name in self.hang:
            return REAL_STEP(run, name, SLEEPER, env, check, time_limit_s)
        weekly.LOGS.mkdir(parents=True, exist_ok=True)
        (weekly.LOGS / f"{name}.log").write_text(f"made-up log of {name}\n", encoding="utf-8")
        cmd = [str(c) for c in cmd]
        script = next((c for c in cmd if c.endswith(".py")), "")
        args = cmd[cmd.index(script) + 1:] if script else []
        if name in self.fail:
            rc = 1
        elif name in self.missing:                    # exits 0 having written nothing
            rc = 0
        else:
            rc = self.run(name, script.replace("\\", "/"), args)
        run.setdefault("steps", {})[name] = {"returncode": rc}
        if rc and check:
            raise SystemExit(f"step {name} failed (exit {rc})")
        return rc

    def run(self, name, script, args):
        def arg(flag):
            return args[args.index(flag) + 1]
        if script.endswith("packbuilder/catalog/build_catalog.py"):
            return quiet(build_catalog.main, args)
        if script.endswith("packbuilder/comma/catalog.py"):
            if self.catalog_check_fails:          # comma.json written, then its check fails
                quiet(catalog.main, [a for a in args if a != "--check"])
                return 1
            return quiet(catalog.main, args)
        if script.endswith("packbuilder/comma/check.py"):
            i = args.index("--mapd")
            return quiet(comma_check.main, args[:i] + args[i + 2:])
        if script.endswith("packbuilder/comma/search_code.py"):
            return quiet(search_code.main, args)
        if script.endswith("packbuilder/comma/roads.py"):
            if name in self.broken:
                fixtures.tar_with(Path(arg("--out")), [("../14/1/1.rt", b"x", "file")])
            else:
                fixtures.fake_roads_tar(Path(arg("--out")))
            return 0
        if script.endswith("packbuilder/comma/mapd.py"):
            if args[0] == "setup":                    # Go and Mapd's build: Linux; the test makes the program
                return 0
            out = Path(arg("--out"))
            fixtures.fake_mapd_tar(out, work=out.parent.parent / f".{out.parent.name}-mapd-work")
            return 0
        if script.endswith("packbuilder/search/tests/run_tests.py"):
            Path(arg("--json")).write_text(json.dumps({"summary": {"all": {"n": 100, "top5": 100, "gap": 0}}}))
            return 0
        if script.endswith("build/check_routes.py"):
            Path(arg("--out")).parent.mkdir(parents=True, exist_ok=True)
            Path(arg("--out")).write_text(json.dumps({"summary": {"ok": True}}))
            return 0
        return 0


class FakeGitHub:
    """The gh commands weekly.py runs, on releases held in memory: {tag: {"draft", "assets": {name: bytes}}}."""

    def __init__(self, releases, fail_upload=()):
        self.releases, self.fail_upload, self.log = releases, set(fail_upload), []

    def run(self, cmd, capture_output=False, text=False, check=False, **_kw):
        if not cmd or cmd[0] != "gh":
            raise AssertionError(f"only gh is faked: {cmd}")
        args = [str(c) for c in cmd[1:]]
        self.log.append(args)
        out, err, rc = self.handle(args)
        if check and rc:
            raise subprocess.CalledProcessError(rc, cmd)
        return subprocess.CompletedProcess(cmd, rc, out if text else out.encode(), err if text else err.encode())

    def handle(self, a):
        verb, tag = a[1], a[2] if len(a) > 2 else None
        if verb == "list":
            return json.dumps([{"tagName": t, "isDraft": r["draft"]} for t, r in self.releases.items()]), "", 0
        if verb != "create" and tag not in self.releases:
            return "", "release not found", 1
        if verb == "view":
            return json.dumps({"assets": [{"name": n, "size": len(b)}
                                          for n, b in self.releases[tag]["assets"].items()]}), "", 0
        if verb == "create":
            self.releases[tag] = {"draft": "--draft" in a, "assets": {}}
            return "", "", 0
        if verb == "upload":
            for f in (x for x in a[3:] if not x.startswith("--")):
                if Path(f).name in self.fail_upload:
                    return "", "HTTP 502", 1
                self.releases[tag]["assets"][Path(f).name] = Path(f).read_bytes()
            return "", "", 0
        if verb == "edit":
            self.releases[tag]["draft"] = "--draft=true" in a
            return "", "", 0
        if verb == "delete":
            del self.releases[tag]
            return "", "", 0
        if verb == "download":
            name, folder = a[a.index("--pattern") + 1], Path(a[a.index("--dir") + 1])
            folder.mkdir(parents=True, exist_ok=True)
            (folder / name).write_bytes(self.releases[tag]["assets"][name])
            return "", "", 0
        raise AssertionError(f"gh {a} is not faked")


def old_comma_json(*tags) -> bytes:
    """A comma.json published before, naming these builds."""
    packs = [{"id": t[:5], "pack_version": int(t[6:]), "parts": [
        {"name": "roads", "file": "roads.tar", "transfer": {"path": f"../{t}/roads.tar"}}]} for t in tags]
    return json.dumps({"format": "beamline-comma-catalog/1", "serial": 20261101000000, "packs": packs}).encode()


class WeeklyTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.TemporaryDirectory()
        self.tmp = Path(self.d.name)
        self.saved = []
        out, data = self.tmp / "out", self.tmp / "data"
        for k, v in {"OUT": out, "RUN_JSON": out / "run.json", "LOGS": out / "logs", "COMMA": out / "comma",
                     "DATA": data, "COMMA_DATA": data / "comma", "MAPD_TOOL": data / "comma" / "mapd-tool",
                     "COMMA_SETUP": data / "comma" / "setup.json"}.items():
            self.patch(weekly, k, v)
        self.old_epoch = os.environ.get("SOURCE_DATE_EPOCH")
        os.environ["SOURCE_DATE_EPOCH"] = str(common.utc_epoch(fixtures.CREATED))
        # The made-up pack's search.sqlite is not a search file: check.py's question to the search code
        # (search_code.answers, run for real in test_search_code) is stood in for; a test sets self.answer.
        self.answer = None
        self.patch(search_code, "answers", self.fake_answers)

    def fake_answers(self, tar, db, addresses, state, *a, **k):
        search_code.verify(tar, None, None)                  # what answers() checks before running anything
        if self.answer:
            raise common.Refused(self.answer)
        return {"probes": "known", "answered": [{"query": "Erie", "title": "Erie", "state": state}]}

    def tearDown(self):
        for obj, name, value in reversed(self.saved):
            setattr(obj, name, value)
        if self.old_epoch is None:
            os.environ.pop("SOURCE_DATE_EPOCH", None)
        else:
            os.environ["SOURCE_DATE_EPOCH"] = self.old_epoch
        self.d.cleanup()

    def patch(self, obj, name, value):
        self.saved.append((obj, name, getattr(obj, name)))
        setattr(obj, name, value)

    # ---------------------------------------------------------------- a site as the run leaves it
    def site(self, comma_ok=True) -> dict:
        """The phone's pack and site, the comma parts and, through the run's own build_comma_catalog,
        comma.json with the comma result recorded in run.json (or a run.json whose comma side failed)."""
        out = weekly.OUT
        fixtures.fake_pack(out / "packs" / "us-pa")
        fixtures.fake_parts(out / "comma" / "us-pa", manifest_path=out / "packs" / "us-pa" / "manifest.json")
        steps = FakeSteps()
        self.patch(weekly, "step", steps)
        run = {"checks_passed": True, "comma": {"ok": None}}
        weekly.build_site(run)
        self.assertIsNone(quiet(weekly.build_comma_catalog, run))
        self.assertIs(run["comma"]["ok"], True)
        if not comma_ok:  # every comma file and comma.json's own record are right: only "ok" says no
            run["comma"].update(ok=False, failed={"stage": "check", "why": "made up"})
        weekly.save_run(run)
        self.cat = json.loads((out / "site" / "catalog" / "catalog.json").read_text(encoding="utf-8"))
        self.comma_bytes = (out / "site" / "catalog" / "comma.json").read_bytes()
        self.comma = json.loads(self.comma_bytes)
        return run

    def last_week(self, dest: Path) -> bytes:
        """A folder as last week's publish left it: two builds, catalog.json naming the newer, comma.json
        naming the older (its comma side had failed the week after)."""
        for t in (OLD, OLDER):
            (dest / t).mkdir(parents=True)
            (dest / t / "manifest.json").write_text("{}")
        (dest / "catalog").mkdir()
        (dest / "catalog" / "catalog.json").write_text(json.dumps({"packs": [{"id": "us-pa", "pack_version": 202611080000}]}))
        old = old_comma_json(OLDER)
        (dest / "catalog" / "comma.json").write_bytes(old)
        return old

    def publish_local(self, dest: Path):
        a = argparse.Namespace(github=False, local=str(dest), store=str(self.tmp / "store"), store_release=None)
        return quiet(weekly.cmd_publish, a)

    def run_json(self) -> dict:
        return json.loads(weekly.RUN_JSON.read_text(encoding="utf-8"))


# ==================================================================== the run: the comma side kept apart
class RunKeepsTheCommaSideApart(WeeklyTest):
    def setUp(self):
        super().setUp()
        w = weekly
        self.patch(w, "restore_inputs", lambda run, store: {"format": "beamline-inputs/1", "assets": {}})

        def fetch(run, store=None):
            run["snapshot"], run["osm_snapshot"] = "261120", "2026-11-20"
            return "261120"
        self.patch(w, "fetch_extracts", fetch)
        self.patch(w, "refresh_inputs", lambda run, idx: None)
        self.patch(w, "build_parts", lambda run, keep_work: {"pa": {}})

        def assemble(run, parts, keep_work):
            man = fixtures.fake_pack(w.OUT / "packs" / "us-pa")
            run.setdefault("packs", {})["pa"] = {
                "pack_id": man["pack_id"], "pack_version": man["pack_version"], "osm_snapshot": man["osm_snapshot"],
                "graph_build": "test", "parts": {k: v["bytes"] for k, v in man["parts"].items()},
                "total_bytes": sum(v["bytes"] for v in man["parts"].values())}
        self.patch(w, "assemble", assemble)
        self.setup_record(True)

    def setup_record(self, ok: bool, error: str = ""):
        weekly.MAPD_TOOL.mkdir(parents=True, exist_ok=True)
        (weekly.MAPD_TOOL / "mapd").write_bytes(b"made up")
        rec = {"ok": ok} if ok else {"ok": False, "error": error, "log": "data/comma/setup-mapd.log"}
        weekly.COMMA_SETUP.write_text(json.dumps(rec))
        if not ok:
            (weekly.COMMA_DATA / "setup-mapd.log").write_text("go: cannot reach the module proxy\n")

    def run_build(self, steps: FakeSteps) -> str:
        self.patch(weekly, "step", steps)
        a = argparse.Namespace(store=str(self.tmp / "store"), store_release=None, previous_catalog=None,
                               allow_smaller=False, keep_work=False)
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(io.StringIO()):
            weekly.cmd_run(a)                                   # never ends as failed for the comma side
        return buf.getvalue()

    def assert_phone_only_publish(self, run_text: str, stage: str):
        run = self.run_json()
        self.assertTrue(run["checks_passed"])                   # the phone's checks: the comma result is not among them
        self.assertNotIn("comma", run["checks"]["pa"])
        self.assertIs(run["comma"]["ok"], False)
        self.assertEqual(run["comma"]["failed"]["stage"], stage)
        self.assertIn("THE COMMA SIDE FAILED", run_text)
        site = weekly.OUT / "site"
        self.assertTrue((site / "catalog" / "catalog.json").is_file())
        self.assertFalse((site / "catalog" / "comma.json").exists())
        self.assertIn("FAILED", (weekly.OUT / "summary.md").read_text(encoding="utf-8"))
        dest = self.tmp / "published"
        old = self.last_week(dest)
        with self.assertRaises(SystemExit) as cm:              # ends as failed ...
            self.publish_local(dest)
        self.assertIn("COMMA SIDE FAILED", str(cm.exception))
        # ... after publishing the phone's side exactly as without a comma side
        names = {p.name for p in (dest / BUILD).iterdir()}
        phone = {q["transfer"]["path"].split("/")[-1] for q in
                 json.loads((site / "catalog" / "catalog.json").read_text(encoding="utf-8"))["packs"][0]["parts"]}
        self.assertEqual(names, phone | {"manifest.json", "RELEASE-NOTES.md"})
        self.assertFalse(names & COMMA_FILES)
        self.assertIn("no comma parts", (dest / BUILD / "RELEASE-NOTES.md").read_text(encoding="utf-8"))
        self.assertEqual((dest / "catalog" / "catalog.json").read_bytes(), (site / "catalog" / "catalog.json").read_bytes())
        self.assertEqual((dest / "catalog" / "comma.json").read_bytes(), old)     # last week's, untouched
        # keep_builds 2 would delete the oldest, but the published comma.json names it
        self.assertEqual(sorted(p.name for p in dest.iterdir()), sorted(["catalog", BUILD, OLD, OLDER]))
        pub = self.run_json()["published"]
        self.assertEqual((pub["releases"], pub["comma_published"], pub["comma_serial"]), ([BUILD], False, None))
        with self.assertRaises(SystemExit):
            quiet(weekly.cmd_comma_status, None)

    def test_all_comma_steps_pass(self):
        steps = FakeSteps()
        text = self.run_build(steps)
        self.assertNotIn("COMMA SIDE FAILED", text)
        run = self.run_json()
        self.assertTrue(run["checks_passed"])
        self.assertIs(run["comma"]["ok"], True)
        # every comma step has the comma time limit; the phone's steps run as before, without one
        comma_steps = {"comma-search-code-pa", "comma-roads-pa", "comma-mapd-pa", "check-comma-pa", "comma-catalog"}
        self.assertLessEqual(comma_steps, set(steps.limits))
        self.assertEqual({n: steps.limits[n] for n in comma_steps}, {n: 1800 for n in comma_steps})
        self.assertEqual({n: v for n, v in steps.limits.items() if n not in comma_steps}, {n: None for n in steps.limits
                                                                                         if n not in comma_steps})
        check = json.loads((weekly.OUT / "checks" / "comma-pa.json").read_text())
        self.assertTrue(check["search_answers"]["ok"])
        self.assertEqual(check["search_code"]["release"], 1)
        comma_json = weekly.OUT / "site" / "catalog" / "comma.json"
        self.assertEqual(run["comma"]["comma_json"]["sha256"], hashlib.sha256(comma_json.read_bytes()).hexdigest())
        self.assertEqual(steps.calls[-1], "comma-catalog")
        dest = self.tmp / "published"
        self.last_week(dest)
        self.publish_local(dest)                                # no SystemExit
        self.assertEqual((dest / "catalog" / "comma.json").read_bytes(), comma_json.read_bytes())
        names = {p.name for p in (dest / BUILD).iterdir()}
        for q in json.loads(comma_json.read_bytes())["packs"][0]["parts"]:
            self.assertIn(q["transfer"]["path"].split("/")[-1], names)
        self.assertEqual(sorted(p.name for p in dest.iterdir()), sorted(["catalog", BUILD, OLD]))   # as before
        self.assertTrue(self.run_json()["published"]["comma_published"])
        quiet(weekly.cmd_comma_status, None)

    def test_an_invalid_part_fails_the_comma_check_and_stops_comma_json(self):
        steps = FakeSteps(broken={"comma-roads-pa"})
        text = self.run_build(steps)
        self.assertIn("check-comma-pa", steps.calls)
        self.assertNotIn("comma-catalog", steps.calls)          # no comma.json is written
        self.assertIn("catalog", steps.calls)                   # the phone's catalog is
        self.assertFalse(json.loads((weekly.OUT / "checks" / "comma-pa.json").read_text())["roads"]["ok"])
        self.assert_phone_only_publish(text, "check")

    def test_a_part_missing_after_its_builder_makes_the_check_fail(self):
        steps = FakeSteps(missing={"comma-mapd-pa"})            # mapd.py "passes" but writes no mapd.tar
        text = self.run_build(steps)
        self.assertIn("check-comma-pa", steps.calls)
        self.assertNotIn("comma-catalog", steps.calls)
        result = json.loads((weekly.OUT / "checks" / "comma-pa.json").read_text())
        self.assertEqual((result["search_code"]["ok"], result["roads"]["ok"], result["mapd"]["ok"]), (True, True, False))
        self.assert_phone_only_publish(text, "check")

    def test_a_missing_part_stops_the_rest_of_the_comma_side(self):
        steps = FakeSteps(fail={"comma-roads-pa"})              # roads.py exits 1: no roads.tar
        text = self.run_build(steps)
        self.assertNotIn("comma-mapd-pa", steps.calls)
        self.assertNotIn("check-comma-pa", steps.calls)
        self.assertNotIn("comma-catalog", steps.calls)
        self.assertEqual(self.run_json()["comma"]["skipped"], ["check", "catalog"])
        self.assert_phone_only_publish(text, "parts")

    def test_a_failed_mapd_setup_skips_every_comma_step(self):
        self.setup_record(False, "packbuilder/comma/mapd.py setup failed (exit 1)")
        steps = FakeSteps()
        text = self.run_build(steps)
        self.assertFalse([c for c in steps.calls if "comma" in c])
        self.assertTrue((weekly.LOGS / "setup-mapd.log").is_file())      # kept with this run's logs
        self.assertIn("setup failed", self.run_json()["comma"]["failed"]["why"])
        self.assert_phone_only_publish(text, "setup")

    def test_search_code_that_does_not_answer_fails_the_comma_check(self):
        self.answer = "the search code does not answer from this search file: 'Peach Street': no results"
        steps = FakeSteps()
        text = self.run_build(steps)
        self.assertIn("check-comma-pa", steps.calls)
        self.assertNotIn("comma-catalog", steps.calls)
        result = json.loads((weekly.OUT / "checks" / "comma-pa.json").read_text())
        self.assertEqual((result["search_code"]["ok"], result["search_answers"]["ok"], result["roads"]["ok"]),
                         (True, False, True))
        self.assertIn("Peach Street", result["search_answers"]["error"])
        self.assert_phone_only_publish(text, "check")

    def test_a_comma_step_past_its_time_limit_is_killed_and_fails_the_comma_side(self):
        """A stalled step (here: the Mapd build, a command that sleeps) is killed at the comma time limit and
        counts as that step's failure; the phone's packs go on."""
        self.patch(weekly, "CONFIG", json.loads(json.dumps(weekly.CONFIG)))
        weekly.CONFIG["comma"]["step_time_limit_s"] = 1
        steps = FakeSteps(hang={"comma-mapd-pa"})
        t0 = time.time()
        text = self.run_build(steps)
        self.assertLess(time.time() - t0, 60)
        rec = self.run_json()["steps"]["comma-mapd-pa"]
        self.assertEqual((rec["returncode"], rec["timed_out"], rec["time_limit_s"]), (weekly.TIMED_OUT, True, 1))
        self.assertIn("time limit of 1 s", self.run_json()["comma"]["failed"]["why"])
        self.assertIn("ran past its time limit", (weekly.LOGS / "comma-mapd-pa.log").read_text(encoding="utf-8"))
        self.assertNotIn("check-comma-pa", steps.calls)
        self.assert_phone_only_publish(text, "parts")

    def test_a_comma_check_past_its_time_limit_fails_the_comma_side(self):
        self.patch(weekly, "CONFIG", json.loads(json.dumps(weekly.CONFIG)))
        weekly.CONFIG["comma"]["step_time_limit_s"] = 1
        steps = FakeSteps(hang={"check-comma-pa"})
        text = self.run_build(steps)
        self.assertIn("time limit", self.run_json()["comma"]["failed"]["why"])
        self.assertNotIn("comma-catalog", steps.calls)
        self.assert_phone_only_publish(text, "check")

    def test_a_comma_json_whose_check_failed_is_not_left_to_publish(self):
        steps = FakeSteps(catalog_check_fails=True)
        text = self.run_build(steps)
        self.assertEqual(steps.calls[-1], "comma-catalog")
        self.assert_phone_only_publish(text, "catalog")

    def test_cmd_setup_goes_on_after_a_failed_mapd_setup(self):
        steps = FakeSteps(fail={"setup-mapd"})
        self.patch(weekly, "step", steps)
        self.patch(weekly, "java_major", lambda: 25)
        self.patch(weekly, "VENV", self.tmp / "venv")
        quiet(weekly.cmd_setup, None)                           # no SystemExit
        self.assertEqual(steps.calls[-1], "setup-mapd")
        self.assertFalse(json.loads(weekly.COMMA_SETUP.read_text())["ok"])

    def test_setup_survives_a_failed_mapd_setup(self):
        """cmd_setup's last step: a failure is recorded, never raised (go.dev, the Go proxy or
        StarPilot's repository unreachable, a missing pin)."""
        self.patch(weekly, "step", FakeSteps(fail={"setup-mapd"}))
        self.assertFalse(quiet(weekly.setup_comma, {}))
        rec = json.loads(weekly.COMMA_SETUP.read_text())
        self.assertFalse(rec["ok"])
        self.assertTrue((weekly.COMMA_DATA / "setup-mapd.log").is_file())

        def crash(*_a, **_k):
            raise OSError("no such file: .venv/map/bin/python")
        self.patch(weekly, "step", crash)
        self.assertFalse(quiet(weekly.setup_comma, {}))
        self.assertIn("OSError", json.loads(weekly.COMMA_SETUP.read_text())["error"])
        self.patch(weekly, "step", FakeSteps())
        (weekly.MAPD_TOOL / "mapd").write_bytes(b"made up")
        self.assertTrue(quiet(weekly.setup_comma, {}))
        # a stalled fetch or module download: killed at the comma time limit, recorded, never raised
        self.patch(weekly, "CONFIG", json.loads(json.dumps(weekly.CONFIG)))
        weekly.CONFIG["comma"]["step_time_limit_s"] = 1
        steps = FakeSteps(hang={"setup-mapd"})
        self.patch(weekly, "step", steps)
        self.assertFalse(quiet(weekly.setup_comma, {}))
        self.assertEqual(steps.limits["setup-mapd"], 1)
        rec = json.loads(weekly.COMMA_SETUP.read_text())
        self.assertFalse(rec["ok"])
        self.assertIn("time limit of 1 s", rec["error"])
        self.assertIn("ran past its time limit", (weekly.COMMA_DATA / "setup-mapd.log").read_text(encoding="utf-8"))


# ==================================================================== publish: the comma gate
class PublishGate(WeeklyTest):
    def test_release_files(self):
        self.site()
        files = weekly.release_files(self.cat["packs"][0], self.comma)
        names = [f.name for f in files]
        self.assertEqual(names[0], "manifest.json")
        cp = {p["name"]: p["transfer"]["path"].split("/")[-1] for p in self.comma["packs"][0]["parts"]}
        for n in ("search_code", "roads", "mapd", "routing", "search", "addresses"):
            self.assertIn(cp[n], names)
        self.assertEqual(len(names), len(set(names)))                  # a shared file once
        self.assertTrue(all(f.parent.name == BUILD for f in files))
        phone_only = [f.name for f in weekly.release_files(self.cat["packs"][0], None)]
        self.assertFalse(set(phone_only) & COMMA_FILES)
        self.assertEqual(set(names) - set(phone_only), {cp["search_code"], cp["roads"], cp["mapd"]})

    def test_comma_problem(self):
        self.site()
        self.assertIsNone(weekly.comma_problem(self.cat, self.comma_bytes))

        def changed(fn):
            c = json.loads(self.comma_bytes)
            fn(c)
            return weekly.comma_problem(self.cat, json.dumps(c).encode())
        self.assertIn("names the builds", changed(lambda c: c.update(packs=[])))
        self.assertIn("outside its release",
                      changed(lambda c: c["packs"][0]["parts"][3]["transfer"].update(path=f"../{OLD}/search-code.tar")))
        self.assertIn("outside its release",
                      changed(lambda c: c["packs"][0]["parts"][3]["transfer"].update(path=f"../{BUILD}/../x")))
        self.assertIn("SHA-256", changed(lambda c: c["packs"][0]["parts"][4]["transfer"].update(bytes=1)))
        self.assertIn("SHA-256", changed(lambda c: c["packs"][0]["parts"][0]["transfer"].update(sha256="0" * 64)))
        self.assertIn("not beamline-comma-catalog/1", changed(lambda c: c.update(format="beamline-comma-catalog/2")))
        # the serial: a real UTC time, at most two days ahead of this clock (the comma refuses others)
        self.assertIn("not a real UTC time", changed(lambda c: c.update(serial=20261121185960)))
        self.assertIn("not a real UTC time", changed(lambda c: c.update(serial=20261131000000)))
        self.assertIn("not 14 digits", changed(lambda c: c.update(serial=2026112118590)))
        ahead = int((datetime.now(timezone.utc) + timedelta(days=2, hours=1)).strftime("%Y%m%d%H%M%S"))
        self.assertIn("two days ahead", changed(lambda c: c.update(serial=ahead)))
        soon = int((datetime.now(timezone.utc) + timedelta(days=1)).strftime("%Y%m%d%H%M%S"))
        self.assertIsNone(changed(lambda c: c.update(serial=soon)))

    def test_a_comma_json_with_a_serial_ahead_of_the_clock_is_not_published(self):
        self.site()
        c = json.loads(self.comma_bytes)
        c["serial"] = int((datetime.now(timezone.utc) + timedelta(days=3)).strftime("%Y%m%d%H%M%S"))
        data = json.dumps(c).encode()
        (weekly.OUT / "site" / "catalog" / "comma.json").write_bytes(data)
        run = self.run_json()
        run["comma"]["comma_json"].update(bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        weekly.save_run(run)                                  # as if the run had recorded this very file
        dest = self.tmp / "published"
        with self.assertRaises(SystemExit) as cm:
            self.publish_local(dest)
        self.assertIn("two days ahead", str(cm.exception))
        self.assertFalse((dest / "catalog" / "comma.json").exists())
        self.assertTrue((dest / "catalog" / "catalog.json").is_file())  # the phone's side went on

    # ---------------------------------------------------------------- the phone's side: checked before anything
    def assert_nothing_published(self, dest: Path, words: str):
        with self.assertRaises(SystemExit) as cm:
            self.publish_local(dest)
        self.assertIn(words, str(cm.exception))
        self.assertIn("nothing is published", str(cm.exception))
        self.assertFalse(dest.exists() and any(dest.iterdir()))

    def test_a_changed_phone_file_is_refused_before_anything_is_published(self):
        self.site()
        f = weekly.OUT / "site" / BUILD / "map.pmtiles"
        raw = bytearray(f.read_bytes())
        raw[-1] ^= 0xFF                                       # same size, other bytes
        f.write_bytes(bytes(raw))
        self.assert_nothing_published(self.tmp / "published", "map.pmtiles")

    def test_a_missing_phone_file_is_refused(self):
        self.site()
        (weekly.OUT / "site" / BUILD / "manifest.json").unlink()
        self.assert_nothing_published(self.tmp / "published", "manifest.json is missing")

    def test_another_catalog_json_than_the_checked_one_is_refused(self):
        self.site()
        path = weekly.OUT / "site" / "catalog" / "catalog.json"
        cat = json.loads(path.read_bytes())
        cat["serial"] += 1                                    # valid, its files untouched: not what the run checked
        path.write_text(json.dumps(cat, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        self.assert_nothing_published(self.tmp / "published", "not the catalog.json the run checked")

    def test_a_run_json_without_a_checked_catalog_publishes_nothing(self):
        run = self.site()
        run.pop("catalog")
        weekly.save_run(run)
        self.assert_nothing_published(self.tmp / "published", "records no checked catalog.json")

    def test_the_phone_files_are_published_as_they_are(self):
        self.site()
        dest = self.tmp / "published"
        self.publish_local(dest)
        site = weekly.OUT / "site"
        self.assertEqual((dest / "catalog" / "catalog.json").read_bytes(), (site / "catalog" / "catalog.json").read_bytes())
        for q in [self.cat["packs"][0]["manifest"]] + [p["transfer"] for p in self.cat["packs"][0]["parts"]]:
            name = q["path"].split("/")[-1]
            self.assertEqual((dest / BUILD / name).read_bytes(), (site / BUILD / name).read_bytes(), name)
            self.assertEqual(hashlib.sha256((dest / BUILD / name).read_bytes()).hexdigest(), q["sha256"], name)

    def test_a_failed_comma_result_is_never_published(self):
        """Every comma file is fine, but run.json says the comma side failed: the decision is run.json's."""
        self.site(comma_ok=False)
        dest = self.tmp / "published"
        old = self.last_week(dest)
        with self.assertRaises(SystemExit):
            self.publish_local(dest)
        self.assertEqual((dest / "catalog" / "comma.json").read_bytes(), old)
        self.assertFalse({p.name for p in (dest / BUILD).iterdir()} & COMMA_FILES)
        self.assertTrue((dest / OLDER).is_dir())

    def test_a_run_json_without_a_comma_result_publishes_no_comma_files(self):
        run = self.site()
        weekly.save_run({"checks_passed": True, "catalog": run["catalog"]})   # e.g. a run without a comma side
        dest = self.tmp / "published"
        with self.assertRaises(SystemExit):
            self.publish_local(dest)
        self.assertFalse((dest / "catalog" / "comma.json").exists())
        self.assertTrue((dest / "catalog" / "catalog.json").is_file())

    def test_a_changed_comma_file_is_refused(self):
        self.site()
        roads = weekly.OUT / "site" / BUILD / "roads.tar"
        raw = bytearray(roads.read_bytes())
        raw[-1] ^= 0xFF                                                  # same size, other bytes
        roads.write_bytes(bytes(raw))
        dest = self.tmp / "published"
        old = self.last_week(dest)
        with self.assertRaises(SystemExit) as cm:
            self.publish_local(dest)
        self.assertIn("roads.tar", str(cm.exception))
        self.assertEqual((dest / "catalog" / "comma.json").read_bytes(), old)
        self.assertTrue((dest / BUILD / "manifest.json").is_file())     # the phone's side is published

    def test_another_comma_json_than_the_checked_one_is_refused(self):
        self.site()
        c = json.loads(self.comma_bytes)
        c["serial"] += 1                                                 # valid, but not what the run checked
        (weekly.OUT / "site" / "catalog" / "comma.json").write_text(json.dumps(c), encoding="utf-8")
        dest = self.tmp / "published"
        with self.assertRaises(SystemExit) as cm:
            self.publish_local(dest)
        self.assertIn("not the comma.json the run checked", str(cm.exception))
        self.assertFalse((dest / "catalog" / "comma.json").exists())

    def test_a_published_build_is_never_rebuilt(self):
        self.site()
        dest = self.tmp / "published"
        self.publish_local(dest)
        self.assertTrue(quiet(catalog.check_site, dest))                 # every file comma.json names, re-read
        self.assertTrue(quiet(build_catalog.check_site, dest, {3}))      # and catalog.json's
        with self.assertRaises(SystemExit):
            self.publish_local(dest)

    def test_prune_keeps_what_either_catalog_names(self):
        tags = [OLDER, OLD, BUILD, "us-pa-202610250000"]
        both = {"packs": [{"id": "us-pa", "pack_version": 202611211859}, {"id": "us-pa", "pack_version": 202611010000}]}
        self.assertEqual(weekly.prune_list(tags, 1, both), ["us-pa-202610250000", OLD])
        # a file named in another build's release folder keeps that build too
        files = {"packs": [{"id": "us-pa", "pack_version": 202611211859,
                            "parts": [{"transfer": {"path": f"../{OLD}/routing.tar.gz"}}]}]}
        self.assertEqual(weekly.prune_list(tags, 1, files), ["us-pa-202610250000", OLDER])
        self.assertEqual(quiet(weekly.prune, tags, 1, {"packs": []}, None), [])  # the published comma.json not known


class PublishOnGitHub(WeeklyTest):
    """cmd_publish --github through a faked gh (no network, no GitHub)."""

    def github(self, comma_ok: bool, fail_upload=()) -> FakeGitHub:
        self.site(comma_ok)
        self.old = old_comma_json(OLDER)
        gh = FakeGitHub({"catalog": {"draft": False, "assets": {"catalog.json": b"{}", "comma.json": self.old}},
                         OLD: {"draft": False, "assets": {"manifest.json": b"{}"}},
                         OLDER: {"draft": False, "assets": {"manifest.json": b"{}"}}}, fail_upload)
        self.patch(weekly, "subprocess", types.SimpleNamespace(run=gh.run, CompletedProcess=subprocess.CompletedProcess,
                                                               CalledProcessError=subprocess.CalledProcessError,
                                                               Popen=subprocess.Popen))
        return gh

    def publish(self):
        a = argparse.Namespace(github=True, local=None, store=str(self.tmp / "store"), store_release=None)
        return quiet(weekly.cmd_publish, a)

    def uploads_to_catalog(self, gh):
        return [Path(x).name for a in gh.log if a[:3] == ["release", "upload", "catalog"] for x in a[3:]
                if not x.startswith("--")]

    def test_comma_side_passed(self):
        gh = self.github(True)
        self.publish()
        self.assertEqual(self.uploads_to_catalog(gh), ["catalog.json", "comma.json"])      # comma.json after
        self.assertEqual(gh.releases["catalog"]["assets"]["comma.json"], self.comma_bytes)
        self.assertFalse(gh.releases[BUILD]["draft"])
        self.assertTrue(set(gh.releases[BUILD]["assets"]) & COMMA_FILES)
        self.assertNotIn(OLDER, gh.releases)                                               # pruned, as before
        self.assertIn(OLD, gh.releases)

    def test_comma_side_failed(self):
        gh = self.github(False)
        with self.assertRaises(SystemExit) as cm:
            self.publish()
        self.assertIn("COMMA SIDE FAILED", str(cm.exception))
        self.assertEqual(self.uploads_to_catalog(gh), ["catalog.json"])
        self.assertEqual(gh.releases["catalog"]["assets"]["comma.json"], self.old)        # untouched
        self.assertFalse(gh.releases[BUILD]["draft"])                                      # published ...
        self.assertFalse(set(gh.releases[BUILD]["assets"]) & COMMA_FILES)                  # ... without comma files
        self.assertIn(OLDER, gh.releases)                                                  # named by the published comma.json
        self.assertFalse(self.run_json()["published"]["comma_published"])

    def test_a_changed_phone_file_stops_publish_before_any_upload(self):
        gh = self.github(True)
        f = weekly.OUT / "site" / BUILD / "routing.json"
        raw = bytearray(f.read_bytes())
        raw[0] ^= 0x01
        f.write_bytes(bytes(raw))
        before = json.loads(json.dumps({t: {"draft": r["draft"], "assets": sorted(r["assets"])}
                                        for t, r in gh.releases.items()}))
        with self.assertRaises(SystemExit) as cm:
            self.publish()
        self.assertIn("routing.json", str(cm.exception))
        self.assertEqual(gh.log, [])                                                        # not one gh call
        self.assertEqual({t: {"draft": r["draft"], "assets": sorted(r["assets"])} for t, r in gh.releases.items()}, before)

    def test_comma_json_upload_failed(self):
        gh = self.github(True, fail_upload={"comma.json"})
        with self.assertRaises(SystemExit) as cm:
            self.publish()
        self.assertIn("upload of comma.json failed", str(cm.exception))
        self.assertEqual(gh.releases["catalog"]["assets"]["catalog.json"],
                         (weekly.OUT / "site" / "catalog" / "catalog.json").read_bytes())
        self.assertFalse([a for a in gh.log if a[:2] == ["release", "delete"]])            # nothing deleted
        self.assertEqual(self.run_json()["published"]["inputs_saved"], [])                 # the rest went on


# ==================================================================== a step's time limit (the real weekly.step)
class StepTimeLimit(WeeklyTest):
    def test_a_step_that_sleeps_is_killed_at_its_limit(self):
        run = {}
        t0 = time.time()
        rc = quiet(weekly.step, run, "comma-sleeps", SLEEPER, None, False, 2)
        self.assertLess(time.time() - t0, 60)
        self.assertEqual(rc, weekly.TIMED_OUT)
        rec = run["steps"]["comma-sleeps"]
        self.assertEqual((rec["returncode"], rec["timed_out"], rec["time_limit_s"]), (weekly.TIMED_OUT, True, 2))
        self.assertIn("ran past its time limit of 2 s", (weekly.LOGS / "comma-sleeps.log").read_text(encoding="utf-8"))
        with self.assertRaises(SystemExit):                                     # check=True: a failed step
            quiet(weekly.step, {}, "comma-sleeps", SLEEPER, None, True, 1)

    def test_a_step_within_its_limit_is_untouched(self):
        run = {}
        rc = quiet(weekly.step, run, "comma-quick", [sys.executable, "-c", "print('done')"], None, False, 60)
        self.assertEqual(rc, 0)
        self.assertNotIn("timed_out", run["steps"]["comma-quick"])
        self.assertIn("done", (weekly.LOGS / "comma-quick.log").read_text(encoding="utf-8"))
        rc = quiet(weekly.step, run, "phone-step", [sys.executable, "-c", "raise SystemExit(3)"], None, False)
        self.assertEqual(rc, 3)                                                  # no limit: as before

    def test_comma_step_says_why(self):
        self.patch(weekly, "CONFIG", json.loads(json.dumps(weekly.CONFIG)))
        weekly.CONFIG["comma"]["step_time_limit_s"] = 1
        run = {}
        why = quiet(weekly.comma_step, run, "comma-sleeps", SLEEPER)
        self.assertIn("comma-sleeps ran past its time limit of 1 s", why)
        why = quiet(weekly.comma_step, run, "comma-fails", [sys.executable, "-c", "raise SystemExit(5)"])
        self.assertIn("comma-fails failed (exit 5)", why)
        self.assertIsNone(quiet(weekly.comma_step, run, "comma-passes", [sys.executable, "-c", "pass"]))
        self.assertEqual(weekly.CONFIG["comma"]["step_time_limit_s"], 1)

    def test_the_limit_is_thirty_minutes(self):
        self.assertEqual(weekly.comma_time_limit(), 1800)

    @unittest.skipUnless(hasattr(os, "killpg"), "process groups: Linux (the weekly build's system)")
    def test_the_whole_process_group_is_killed(self):
        """What the step started (here a grandchild that sleeps, as git or go under mapd.py) is killed too."""
        pidfile = self.tmp / "grandchild.pid"
        code = ("import subprocess, sys, time; "
                "p = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(120)']); "
                "open(sys.argv[1], 'w').write(str(p.pid)); time.sleep(120)")
        run = {}
        rc = quiet(weekly.step, run, "comma-group", [sys.executable, "-c", code, str(pidfile)], None, False, 2)
        self.assertEqual(rc, weekly.TIMED_OUT)
        pid = int(pidfile.read_text())
        for _ in range(50):                       # reaped by init once its parent is gone
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                break
            try:
                with open(f"/proc/{pid}/stat") as f:  # a zombie waiting to be reaped is dead too
                    if f.read().rsplit(")", 1)[1].split()[0] == "Z":
                        break
            except FileNotFoundError:
                break
            time.sleep(0.1)
        else:
            os.kill(pid, 9)
            self.fail("the step's grandchild was left running")


# ==================================================================== the extract fetch and its fallback
FOLDER = "https://download.geofabrik.de/north-america/us/pennsylvania"
NOW = datetime(2026, 11, 22, 9, 17, tzinfo=timezone.utc)


class FakeGeofabrik:
    """Geofabrik's folder of Pennsylvania as files in memory, a listing page shaped like the real one
    (absolute links to each file), and pyosmium's header date of each made-up extract."""

    def __init__(self):
        self.files, self.ts, self.requests, self.listing = {}, {}, [], []
        self.files[f"{FOLDER}/pennsylvania.poly"] = b"pennsylvania\n1\n-80.5 42.3\nEND\nEND\n"

    def add(self, name, ts, md5_ok=True, md5=True, data=True):
        blob = f"made-up extract {name} {ts}".encode()
        if data:
            self.files[f"{FOLDER}/{name}"] = blob
        if md5:
            digest = hashlib.md5(blob).hexdigest() if md5_ok else "0" * 32
            self.files[f"{FOLDER}/{name}.md5"] = f"{digest}  {name}\n".encode()
        self.ts[blob] = ts
        if "latest" not in name:
            self.listing.append(name)

    def download(self, url, dest, tries=5):
        self.requests.append(url)
        if url not in self.files:
            raise SystemExit(f"download failed: {url}")
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(self.files[url])

    def fetch_text(self, url):
        self.requests.append(url)
        if url != FOLDER + "/":
            raise OSError("HTTP Error 404: Not Found")
        rows = "".join(f'<tr><td class="filename"><a href="{FOLDER}/{n}">{n}</a></td></tr>'
                       f'<tr><td><a href="{FOLDER}/{n}.md5">{n}.md5</a></td></tr>'
                       f'<tr><td><a href="{FOLDER}/{n[:-8]}-free.shp.zip">shp</a></td></tr>' for n in self.listing)
        return f'<html><a href="{FOLDER}/pennsylvania-latest.osm.pbf">latest</a><table>{rows}</table></html>'

    def url_exists(self, url):
        self.requests.append("HEAD " + url)
        return url in self.files

    def osm_timestamp(self, path):
        return self.ts[Path(path).read_bytes()]


class ExtractFallback(WeeklyTest):
    def setUp(self):
        super().setUp()
        self.g = FakeGeofabrik()
        for k in ("download", "fetch_text", "url_exists", "osm_timestamp"):
            self.patch(weekly, k, getattr(self.g, k))
        self.patch(weekly, "now_utc", lambda: NOW)
        self.patch(weekly, "CONFIG", json.loads(json.dumps(weekly.CONFIG)))
        weekly.CONFIG["states"] = ["pa"]
        self.patch(weekly, "GEOFABRIK_PAUSES_S", ())

    def fetch(self) -> tuple[dict, str]:
        run = {}
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            weekly.fetch_extracts(run)
        return run, buf.getvalue()

    def osm(self, name) -> Path:
        return weekly.DATA / "osm" / name

    def test_the_latest_extract_when_it_works(self):
        self.g.add("pennsylvania-latest.osm.pbf", "2026-11-21T20:21:46Z")
        self.g.add("pennsylvania-261121.osm.pbf", "2026-11-21T20:21:46Z")
        run, text = self.fetch()
        e = run["extracts"]["pa"]
        self.assertEqual((run["snapshot"], e["file"], e["source"]), ("261121", "pennsylvania-261121.osm.pbf", "latest"))
        self.assertNotIn(FOLDER + "/", self.g.requests)                 # the listing is not read
        self.assertNotIn("USING", text)

    def test_a_missing_latest_extract_uses_the_newest_dated_one(self):
        self.g.add("pennsylvania-latest.osm.pbf", "2026-11-21T20:21:46Z", data=False)   # its .md5 is there, the file 404s
        for day in ("261119", "261120", "261121"):
            self.g.add(f"pennsylvania-{day}.osm.pbf", f"2026-11-{day[4:]}T20:21:46Z")
        run, text = self.fetch()
        e = run["extracts"]["pa"]
        self.assertEqual((e["source"], e["source_file"]), ("dated", "pennsylvania-261121.osm.pbf"))
        self.assertIn("download failed", e["fallback_reason"])
        self.assertEqual((run["snapshot"], run["osm_snapshot"]), ("261121", "2026-11-21"))   # from its header
        self.assertIn("USING pennsylvania-261121.osm.pbf", text)
        # the file kept its own name (the snapshot's) and was not deleted on the way
        self.assertEqual(self.osm("pennsylvania-261121.osm.pbf").read_bytes(),
                         self.g.files[f"{FOLDER}/pennsylvania-261121.osm.pbf"])
        self.assertTrue(self.osm("pennsylvania-261121.osm.pbf.md5").read_text().startswith(e["md5"]))
        self.assertFalse(self.osm("pennsylvania-latest.osm.pbf").exists())
        self.assertNotIn(f"{FOLDER}/pennsylvania-261120.osm.pbf", self.g.requests)   # the newest one was enough
        for url in self.g.requests:                                     # only the state's own folder is asked
            self.assertTrue(url.removeprefix("HEAD ").startswith("https://download.geofabrik.de/north-america/us/"))

    def test_a_latest_extract_without_a_map_date_uses_the_newest_dated_one(self):
        self.g.add("pennsylvania-latest.osm.pbf", "")                   # matches its .md5; its header names no date
        self.g.add("pennsylvania-261121.osm.pbf", "2026-11-21T20:21:46Z")
        dates = self.g.osm_timestamp

        def osm_timestamp(path):
            ts = dates(path)
            if not ts:
                raise SystemExit(f"{Path(path).name}: no replication timestamp in its header ('')")
            return ts
        self.patch(weekly, "osm_timestamp", osm_timestamp)
        run, text = self.fetch()
        e = run["extracts"]["pa"]
        self.assertEqual((e["source"], e["source_file"], run["snapshot"]), ("dated", "pennsylvania-261121.osm.pbf", "261121"))
        self.assertIn("no replication timestamp", e["fallback_reason"])
        self.assertIn("USING pennsylvania-261121.osm.pbf", text)

    def test_a_server_that_does_not_answer_for_a_moment_is_asked_again(self):
        self.g.add("pennsylvania-latest.osm.pbf", "2026-11-21T20:21:46Z")
        self.g.add("pennsylvania-261121.osm.pbf", "2026-11-21T20:21:46Z")
        down, slept = [True, True], []
        exists, text = self.g.url_exists, self.g.fetch_text

        def url_exists(url):
            return False if down else exists(url)

        def fetch_text(url):
            if down:
                raise OSError("HTTP Error 503: Service Unavailable")
            return text(url)
        self.patch(weekly, "url_exists", url_exists)
        self.patch(weekly, "fetch_text", fetch_text)
        self.patch(weekly, "GEOFABRIK_PAUSES_S", (30, 90, 240))
        self.patch(weekly.time, "sleep", lambda s: (slept.append(s), down and down.pop()))
        run, out = self.fetch()
        self.assertEqual((slept, run["extracts"]["pa"]["source"]), ([30, 90], "latest"))
        self.assertIn("asking again in 30 s", out)

    def test_a_server_that_never_answers_ends_the_run_after_the_pauses(self):
        slept = []
        self.patch(weekly, "url_exists", lambda url: False)
        self.patch(weekly, "GEOFABRIK_PAUSES_S", (30, 90, 240))
        self.patch(weekly.time, "sleep", slept.append)
        with self.assertRaises(SystemExit) as e:
            self.fetch()
        self.assertEqual(slept, [30, 90, 240])
        self.assertIn("no dated extract", str(e.exception))

    def kept(self, day, ts, md5_ok=True):
        """A store folder keeping one extract of Pennsylvania as save_extracts leaves it."""
        folder = weekly.DATA / "the-store"
        folder.mkdir(parents=True, exist_ok=True)
        blob = f"made-up extract kept {day} {ts}".encode()
        self.g.ts[blob] = ts
        (folder / f"extract-pennsylvania-{day}.osm.pbf").write_bytes(blob)
        digest = hashlib.md5(blob).hexdigest() if md5_ok else "0" * 32
        (folder / f"extract-pennsylvania-{day}.osm.pbf.md5").write_text(f"{digest}  pennsylvania-{day}.osm.pbf\n")
        (folder / "extract-pennsylvania.poly").write_bytes(b"kept poly")
        return weekly.Store(str(folder), None), blob

    def fetch_with(self, store):
        run = {}
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            weekly.fetch_extracts(run, store)
        return run, buf.getvalue()

    def test_an_unreachable_geofabrik_uses_the_extract_the_store_keeps(self):
        self.patch(weekly, "url_exists", lambda url: False)              # nothing answers, the listing is empty
        store, blob = self.kept("261120", "2026-11-20T21:21:00Z")
        run, text = self.fetch_with(store)
        e = run["extracts"]["pa"]
        self.assertEqual((e["source"], e["source_file"], e["file"], run["snapshot"]),
                         ("store", "extract-pennsylvania-261120.osm.pbf", "pennsylvania-261120.osm.pbf", "261120"))
        self.assertIn("no dated extract", e["fallback_reason"])
        self.assertIn("USING the extract kept in the store", text)
        self.assertEqual(self.osm("pennsylvania-261120.osm.pbf").read_bytes(), blob)
        self.assertEqual(self.osm("pennsylvania.poly").read_bytes(), b"kept poly")

    def test_a_kept_extract_that_is_too_old_or_damaged_is_not_used(self):
        self.patch(weekly, "url_exists", lambda url: False)
        for day, ts, ok, why in (("261101", "2026-11-01T21:21:00Z", True, "days old"),
                                 ("261120", "2026-11-20T21:21:00Z", False, "MD5 differs")):
            shutil.rmtree(weekly.DATA / "the-store", ignore_errors=True)
            store, _ = self.kept(day, ts, md5_ok=ok)
            with self.assertRaises(SystemExit) as e:
                self.fetch_with(store)
            self.assertIn(why, str(e.exception))
            self.assertFalse(self.osm(f"pennsylvania-{day}.osm.pbf").exists())

    def test_geofabrik_is_preferred_to_the_store(self):
        self.g.add("pennsylvania-latest.osm.pbf", "2026-11-21T20:21:46Z")
        store, _ = self.kept("261120", "2026-11-20T21:21:00Z")
        run, _ = self.fetch_with(store)
        self.assertEqual((run["extracts"]["pa"]["source"], run["snapshot"]), ("latest", "261121"))

    def test_a_published_run_keeps_its_extract_and_drops_the_older_one(self):
        store, _ = self.kept("261114", "2026-11-14T21:21:00Z")
        self.g.add("pennsylvania-latest.osm.pbf", "2026-11-21T20:21:46Z")
        run, _ = self.fetch_with(store)
        with contextlib.redirect_stdout(io.StringIO()):
            saved = weekly.save_extracts(store, run)
            again = weekly.save_extracts(store, run)
        self.assertEqual((saved, again), (["extract-pennsylvania-261121.osm.pbf"], []))
        self.assertEqual(sorted(store.names()), ["extract-pennsylvania-261121.osm.pbf",
                                                 "extract-pennsylvania-261121.osm.pbf.md5", "extract-pennsylvania.poly"])
        self.patch(weekly, "url_exists", lambda url: False)              # and next week it is what an outage falls back to
        shutil.rmtree(weekly.DATA / "osm")
        run2, _ = self.fetch_with(store)
        self.assertEqual((run2["extracts"]["pa"]["source"], run2["snapshot"]), ("store", "261121"))

    def test_md5_mismatches_are_skipped(self):
        self.g.add("pennsylvania-latest.osm.pbf", "2026-11-21T20:21:46Z", md5_ok=False)
        self.g.add("pennsylvania-261121.osm.pbf", "2026-11-21T20:21:46Z", md5_ok=False)
        self.g.add("pennsylvania-261120.osm.pbf", "2026-11-20T20:21:46Z")
        run, _ = self.fetch()
        e = run["extracts"]["pa"]
        self.assertIn("MD5", e["fallback_reason"])
        self.assertEqual((e["source_file"], run["snapshot"]), ("pennsylvania-261120.osm.pbf", "261120"))
        self.assertIn("pennsylvania-261121.osm.pbf", e["fallback_skipped"][0])
        self.assertFalse(self.osm("pennsylvania-261121.osm.pbf").exists())

    def test_the_map_date_in_the_header_decides_the_age(self):
        self.g.add("pennsylvania-latest.osm.pbf", "x", md5=False, data=False)  # no latest at all
        self.g.add("pennsylvania-261121.osm.pbf", "2026-11-05T20:21:46Z")       # named recent, 16.5 days old inside
        self.g.add("pennsylvania-261120.osm.pbf", "2026-11-20T20:21:46Z")
        run, _ = self.fetch()
        e = run["extracts"]["pa"]
        self.assertEqual(e["source_file"], "pennsylvania-261120.osm.pbf")
        self.assertIn("2026-11-05", e["fallback_skipped"][0])

    def test_too_old_is_refused_and_the_limit_is_the_config(self):
        self.g.add("pennsylvania-latest.osm.pbf", "x", md5=False, data=False)
        self.g.add("pennsylvania-261112.osm.pbf", "2026-11-11T23:00:00Z")       # 10.4 days old by its header
        self.g.add("pennsylvania-261101.osm.pbf", "2026-11-01T20:21:46Z")
        with self.assertRaises(SystemExit) as cm:
            quiet(weekly.fetch_extracts, {})
        msg = str(cm.exception)
        self.assertIn("latest extract cannot be used", msg)
        self.assertIn("over the limit of 10", msg)
        self.assertIn("pennsylvania-261101.osm.pbf and older", msg)
        self.assertNotIn(f"{FOLDER}/pennsylvania-261101.osm.pbf", self.g.requests)   # older by its name: not fetched
        self.assertFalse(self.osm("pennsylvania-261112.osm.pbf").exists())
        weekly.CONFIG["extract_fallback"] = {"max_age_days": 14}
        run, _ = self.fetch()
        self.assertEqual(run["extracts"]["pa"]["source_file"], "pennsylvania-261112.osm.pbf")
        self.assertEqual(run["snapshot"], "261111")                              # the header's date, not the name's

    def test_the_default_limit_is_ten_days(self):
        weekly.CONFIG.pop("extract_fallback", None)
        self.g.add("pennsylvania-latest.osm.pbf", "x", md5=False, data=False)
        self.g.add("pennsylvania-261112.osm.pbf", "2026-11-12T10:00:00Z")      # 9.97 days old
        run, _ = self.fetch()
        self.assertEqual(run["extracts"]["pa"]["source_file"], "pennsylvania-261112.osm.pbf")

    def test_nothing_listed(self):
        self.g.add("pennsylvania-latest.osm.pbf", "2026-11-21T20:21:46Z", data=False)
        with self.assertRaises(SystemExit) as cm:
            quiet(weekly.fetch_extracts, {})
        self.assertIn("none listed", str(cm.exception))


# ==================================================================== the system packages
class SystemPackages(unittest.TestCase):
    def test_setup_script_installs_mapds_build_needs(self):
        text = (fixtures.REPO / "packs-repo" / "build" / "setup_ubuntu.sh").read_text(encoding="utf-8")
        m = re.search(r"apt-get install((?:[^\n]*\\\n)*[^\n]*)", text)
        pkgs = set(m.group(1).replace("\\\n", " ").split())
        self.assertLessEqual({"gcc", "libc6-dev", "pkg-config", "zlib1g-dev", "git", "python3.12-venv",
                              "openjdk-25-jdk-headless"}, pkgs)
        wf = (fixtures.REPO / "packs-repo" / ".github" / "workflows" / "weekly.yml").read_text(encoding="utf-8")
        self.assertIn("sudo sh build/setup_ubuntu.sh", wf)
        self.assertNotIn("apt-get install", wf)                 # one list of system packages: the script's
        self.assertIn("python3 build/weekly.py comma-status", wf)


if __name__ == "__main__":
    unittest.main()
