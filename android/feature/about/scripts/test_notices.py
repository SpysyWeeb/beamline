"""Tests of notices.py (the licence gate). Standard library only:

  python -m unittest discover -s android/feature/about/scripts -p "test_*.py"
"""
from __future__ import annotations

import copy
import json
import os
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import notices  # noqa: E402

REPORT = """
------------------------------------------------------------
Project ':x'
------------------------------------------------------------

releaseRuntimeClasspath - Runtime classpath of '/release'.
+--- project ':y'
|    +--- org.example:lib:1.0 -> 1.2
|    \\--- org.example:lib:1.2 (*)
+--- org.example:bom:3.0
+--- org.example:native:2.0
+--- org.jetbrains.kotlin:kotlin-stdlib:{strictly 2.4.21} -> 2.4.21
+--- org.example:constraint:9.9 (c)
\\--- org.example:gone -> 4.0 (n)
"""


# A documentation-domain address, put together at run time so that no tracked file holds one.
ADDRESS = "someone" + "@" + "example.org"


def entry(name, licence, covers=(), version=None, kind="library", **kw):
    e = {"kind": kind, "name": name, "licence": licence, "covers": list(covers),
         "copyright": "Copyright (c) 2020 Example Ltd", "url": "https://example.org/src"}
    if version:
        e["version"] = version
    e.update(kw)
    return e


def source(entries, natives=None, not_shipped=()):
    return {"format": notices.SOURCE_FORMAT, "entries": entries,
            "native_libraries": natives or {}, "not_shipped": [{"covers": c} for c in not_shipped]}


class SpdxTest(unittest.TestCase):
    def test_parses_and_prints(self):
        for s in ["MIT", "Apache-2.0 OR MIT", "GPL-2.0-only WITH Classpath-exception-2.0",
                  "BSD-3-Clause AND CC0-1.0", "(MIT OR Apache-2.0) AND Unicode-3.0", "GPL-2.0+"]:
            self.assertEqual(notices.parse_spdx(s).text(), s)
        e = notices.parse_spdx("LGPL-2.1-or-later OR Apache-2.0")
        self.assertEqual([o.text() for o in e.options()], ["LGPL-2.1-or-later", "Apache-2.0"])
        self.assertEqual(notices.parse_spdx("GPL-2.0-only WITH Classpath-exception-2.0").exceptions(), ["Classpath-exception-2.0"])

    def test_refuses_broken_expressions(self):
        for s in ["", "MIT OR", "(MIT", "MIT)", "WITH X", "MIT WITH", "MIT, Apache-2.0", "MIT Apache-2.0"]:
            with self.assertRaises(notices.Fail, msg=s):
                notices.parse_spdx(s)

    def test_copyleft_detection(self):
        for i in ["GPL-2.0-only", "GPL-3.0-or-later", "LGPL-2.1-or-later", "AGPL-3.0-only", "GPL-2.0+", "LGPL-2.0", "gpl-2.0"]:
            self.assertTrue(notices.is_copyleft_gpl(i), i)
        for i in ["MIT", "Apache-2.0", "MPL-2.0", "EPL-2.0", "BSD-3-Clause"]:
            self.assertFalse(notices.is_copyleft_gpl(i), i)


class PolicyTest(unittest.TestCase):
    def problems(self, *entries, natives=None):
        return notices.check_policy(source(list(entries), natives))

    def test_permissive_entries_pass(self):
        self.assertEqual(self.problems(entry("A", "MIT"), entry("B", "Apache-2.0"), entry("C", "BSD-3-Clause AND CC0-1.0")), [])

    def test_an_entry_marked_gpl_fails(self):
        for lic in ["GPL-2.0-only", "GPL-3.0-or-later", "LGPL-2.1-or-later", "AGPL-3.0-only", "GPL-2.0+", "MIT AND LGPL-2.1-only"]:
            p = self.problems(entry("X", lic, ["org.example:x"]))
            self.assertTrue(any("GPL" in m for m in p), (lic, p))

    def test_no_exception_is_allowed(self):
        # Since 2026-11-17 (Ferrostar and with it core library desugaring dropped) the allow-list is
        # empty: even the exception the owner had accepted, desugar_jdk_libs, fails now.
        self.assertEqual(notices.ALLOWED, set())
        self.assertTrue(self.problems(entry("desugar", "GPL-2.0-only WITH Classpath-exception-2.0", ["com.android.tools:desugar_jdk_libs"])))
        self.assertTrue(self.problems(entry("other", "GPL-2.0-only WITH Classpath-exception-2.0", ["org.example:other"])))

    def test_a_choice_must_be_made(self):
        self.assertTrue(self.problems(entry("JNA", "LGPL-2.1-or-later OR Apache-2.0", ["net.java.dev.jna:jna"])))
        self.assertEqual(self.problems(entry("JNA", "Apache-2.0", ["net.java.dev.jna:jna"], upstream_licence="LGPL-2.1-or-later OR Apache-2.0")), [])
        # Taking the LGPL option is refused; an option that is not offered is refused.
        self.assertTrue(self.problems(entry("JNA", "LGPL-2.1-or-later", ["net.java.dev.jna:jna"], upstream_licence="LGPL-2.1-or-later OR Apache-2.0")))
        self.assertTrue(self.problems(entry("JNA", "MIT", ["net.java.dev.jna:jna"], upstream_licence="LGPL-2.1-or-later OR Apache-2.0")))

    def test_a_choice_inside_an_and(self):
        # compiler_builtins: "MIT AND Apache-2.0 WITH LLVM-exception AND (MIT OR Apache-2.0)"
        up = "MIT AND Apache-2.0 WITH LLVM-exception AND (MIT OR Apache-2.0)"
        self.assertEqual(self.problems(entry("cb", "MIT AND Apache-2.0 WITH LLVM-exception", upstream_licence=up)), [])
        self.assertEqual(self.problems(entry("cb", "Apache-2.0 WITH LLVM-exception AND MIT AND Apache-2.0", upstream_licence=up)), [])
        self.assertTrue(self.problems(entry("cb", "MIT", upstream_licence=up)))
        self.assertTrue(self.problems(entry("u", "Unicode-3.0", upstream_licence="(MIT OR Apache-2.0) AND Unicode-3.0")))
        self.assertEqual(self.problems(entry("u", "MIT AND Unicode-3.0", upstream_licence="(MIT OR Apache-2.0) AND Unicode-3.0")), [])

    def test_unknown_ids_and_missing_texts_fail(self):
        self.assertTrue(self.problems(entry("A", "Apache-2")))
        self.assertTrue(self.problems(entry("A", "MIT WITH Not-An-Exception")))
        self.assertTrue(self.problems(entry("A", "LicenseRef-No-Such-Text")))

    def test_an_attribution_licence_needs_a_holder(self):
        # MIT, BSD, ISC, ... ask for "the above copyright notice"; the shipped texts are SPDX
        # templates, so an entry without its own line would show no holder at all (review finding).
        for lic in ["MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC", "Zlib", "BSL-1.0", "MIT AND Apache-2.0",
                    "Unicode-DFS-2016 AND ICU", "FTL AND MIT", "MIT-Modern-Variant"]:
            p = self.problems(entry("X", lic, copyright=None))
            self.assertTrue(any("names no holder" in m for m in p), (lic, p))
            self.assertTrue(self.problems(entry("X", lic, copyright="  ")), lic)
            self.assertEqual(self.problems(entry("X", lic)), [], lic)
        # Apache-2.0 (NOTICE files), public domain and CC0 need no holder line.
        for lic in ["Apache-2.0", "Apache-2.0 WITH LLVM-exception", "LicenseRef-Public-Domain", "CC0-1.0"]:
            self.assertEqual(self.problems(entry("X", lic, copyright=None)), [], lic)
        # MPL-2.0 asks to say where the source is.
        self.assertTrue(any("url" in m for m in self.problems(entry("U", "MPL-2.0", url=None))))
        self.assertEqual(self.problems(entry("U", "MPL-2.0", copyright=None)), [])

    def test_no_email_address_in_an_entry(self):
        p = self.problems(entry("X", "MIT", copyright=f"Copyright (c) 2020 A. Person <{ADDRESS}>"))
        self.assertTrue(any("e-mail" in m for m in p), p)

    def test_licence_texts_lose_their_email_addresses(self):
        self.assertEqual(notices.without_emails(f"Copyright (c) 1996 - 2015, A. Person, <{ADDRESS}>.\n"),
                         "Copyright (c) 1996 - 2015, A. Person.\n")
        self.assertEqual(notices.without_emails("     o list" + "@" + "lists.example.org\n"), "     o [e-mail address left out]\n")
        self.assertEqual(notices.without_emails("no address here, see https://example.org/@x"), "no address here, see https://example.org/@x")

    def test_template_copyright_lines_are_left_out_of_the_texts(self):
        for ident in ["MIT", "BSD-2-Clause", "BSD-3-Clause", "ISC"]:
            text = notices.licence_text(notices.parse_spdx(ident))
            self.assertNotIn("<year>", text, ident)
            self.assertIn("copyright notice", text.lower(), ident)   # the terms themselves stay
        # Instructions that only show how to apply a licence stay as they are.
        self.assertIn("Copyright [yyyy] [name of copyright owner]", notices.licence_text(notices.parse_spdx("Apache-2.0")))

    def test_native_components_need_entries(self):
        p = self.problems(entry("A", "MIT"), natives={"liba.so": {"components": ["A", "B"], "complete": True}})
        self.assertEqual(len(p), 1)
        self.assertIn("'B'", p[0])


class ReportTest(unittest.TestCase):
    def test_reads_resolved_versions(self):
        got = notices.parse_report(REPORT)
        self.assertEqual(got[("org.example", "lib")], {"1.2"})
        self.assertEqual(got[("org.jetbrains.kotlin", "kotlin-stdlib")], {"2.4.21"})
        self.assertNotIn(("org.example", "constraint"), got)
        self.assertNotIn(("org.example", "gone"), got)
        self.assertEqual(notices.report_sections(REPORT), [(":x", "releaseRuntimeClasspath")])

    def test_unreadable_lines_fail(self):
        with self.assertRaises(notices.Fail):
            notices.parse_report("+--- what is this")


class CoverageTest(unittest.TestCase):
    """--check against a dependency report and a stand-in Gradle cache."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.deps = root / "deps"
        self.deps.mkdir()
        (self.deps / "deps-runtime.txt").write_text(REPORT, encoding="utf-8")
        home = root / "gradle"
        cache = home / "caches" / "modules-2" / "files-2.1"
        for g, n, v in [("org.example", "lib", "1.2"), ("org.example", "bom", "3.0"),
                        ("org.jetbrains.kotlin", "kotlin-stdlib", "2.4.21")]:
            d = cache / g / n / v / "h1"
            d.mkdir(parents=True)
            (d / f"{n}-{v}.pom").write_text("<project><packaging>jar</packaging></project>", encoding="utf-8")
        d = cache / "org.example" / "native" / "2.0" / "h2"
        d.mkdir(parents=True)
        with zipfile.ZipFile(d / "native.aar", "w") as z:
            z.writestr("classes.jar", b"")
            z.writestr("jni/x86_64/libnat.so", b"\x7fELF")
            z.writestr("jni/arm64-v8a/libnat.so", b"\x7fELF")
        self.old_home = os.environ.get("GRADLE_USER_HOME")
        os.environ["GRADLE_USER_HOME"] = str(home)
        self.good = source(
            [entry("Lib", "MIT", ["org.example:lib"], version="1.2"),
             entry("Native", "Apache-2.0", ["org.example:native"]),
             entry("Kotlin", "Apache-2.0", ["org.jetbrains.kotlin:*"])],
            natives={"libnat.so": {"components": ["Native"], "complete": True}},
            not_shipped=["org.example:bom"],
        )

    def tearDown(self):
        if self.old_home is None:
            os.environ.pop("GRADLE_USER_HOME", None)
        else:
            os.environ["GRADLE_USER_HOME"] = self.old_home
        self.tmp.cleanup()

    def coverage(self, src):
        return notices.check_coverage(src, self.deps)[0]

    def test_everything_covered_passes(self):
        self.assertEqual(self.coverage(self.good), [])

    def test_a_removed_entry_fails(self):
        src = copy.deepcopy(self.good)
        src["entries"] = [e for e in src["entries"] if e["name"] != "Lib"]
        p = self.coverage(src)
        self.assertTrue(any("no entry for org.example:lib" in m for m in p), p)

    def test_a_stale_version_fails(self):
        src = copy.deepcopy(self.good)
        src["entries"][0]["version"] = "1.0"
        self.assertTrue(any("resolves to 1.2" in m for m in self.coverage(src)))

    def test_a_native_library_without_description_fails(self):
        src = copy.deepcopy(self.good)
        src["native_libraries"] = {}
        self.assertTrue(any("libnat.so" in m for m in self.coverage(src)))
        src["native_libraries"] = {"libnat.so": {"components": ["Native"], "complete": False, "todo": "t"}}
        self.assertTrue(any("not complete" in m for m in self.coverage(src)))

    def test_a_bom_must_be_declared_as_shipping_nothing(self):
        src = copy.deepcopy(self.good)
        src["not_shipped"] = []
        self.assertTrue(any("org.example:bom" in m for m in self.coverage(src)))

    def test_an_aar_missing_from_the_cache_fails(self):
        d = Path(os.environ["GRADLE_USER_HOME"]) / "caches/modules-2/files-2.1/org.example/native/2.0/h2/native.aar"
        d.unlink()
        (d.parent / "native-2.0.pom").write_text("<project><packaging>aar</packaging></project>", encoding="utf-8")
        self.assertTrue(any("not in the Gradle cache" in m for m in self.coverage(self.good)))

    def test_a_stale_cover_fails(self):
        src = copy.deepcopy(self.good)
        src["entries"][0]["covers"].append("org.example:not-there")
        self.assertTrue(any("not-there" in m for m in self.coverage(src)))


class TreeTest(unittest.TestCase):
    """The shipped list itself."""

    def test_the_curated_list_passes_the_policy_and_the_assets_are_current(self):
        src = notices.load_source()
        problems = notices.check_policy(src)
        # No known problem since the owner chose the OpenGL renderer (2026-11-16, decision L2 of
        # the integration): glslang, MapLibre's Vulkan-only GPL part, no longer ships.
        self.assertEqual(problems, [])
        self.assertEqual(notices.check_assets(src), [])

    def test_the_outputs_are_what_the_app_reads(self):
        data = json.loads((notices.ASSETS / "notices.json").read_text(encoding="utf-8"))
        self.assertEqual(data["format"], "beamline-notices/1")
        self.assertEqual(data["full_text"], "notices/NOTICES.txt")
        for e in data["entries"]:
            self.assertTrue((notices.ASSETS.parent / e["text"]).is_file(), e["text"])
            self.assertNotIn(" OR ", e["licence"])
        names = [e["name"] for e in data["entries"]]
        for must in ["OpenStreetMap data", "Natural Earth", "US Census Bureau TIGER/Line", "USDOT National Address Database",
                     "City of Scranton 911 address points", "Noto Sans", "Map icons (tangrams/icons)",
                     "Protomaps basemaps style", "MapLibre Android SDK", "Valhalla"]:
            self.assertIn(must, names)
        for gone in ["Ferrostar core", "JNA", "desugar_jdk_libs"]:   # dropped 2026-11-17
            self.assertNotIn(gone, names)
        text = (notices.ASSETS / "NOTICES.txt").read_text(encoding="utf-8")
        self.assertNotIn("Classpath", text)
        self.assertIn("Open Data Commons Open Database License", text)

    def test_every_attribution_entry_shows_a_holder_and_no_asset_an_email(self):
        data = json.loads((notices.ASSETS / "notices.json").read_text(encoding="utf-8"))
        for e in data["entries"]:
            ids = notices.parse_spdx(e["licence"]).ids()
            if any(notices.needs_holder(i) for i in ids):
                self.assertTrue((e["copyright"] or "").strip(), e["name"])
                self.assertNotIn("<copyright holders>", e["copyright"], e["name"])
        for p in notices.ASSETS.rglob("*.txt"):
            self.assertIsNone(notices.EMAIL.search(p.read_text(encoding="utf-8")), p.name)
        for p in notices.TEXTS.glob("*.txt"):
            self.assertIsNone(notices.EMAIL.search(p.read_text(encoding="utf-8")), p.name)


if __name__ == "__main__":
    unittest.main()
