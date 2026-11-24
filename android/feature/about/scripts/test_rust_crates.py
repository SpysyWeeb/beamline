"""Tests of rust_crates.py (which Rust crates a Cargo build links, without a Rust toolchain).
Standard library only, no network:

  python -m unittest discover -s android/feature/about/scripts -p "test_*.py"
"""
from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import rust_crates as rc  # noqa: E402


class CfgTest(unittest.TestCase):
    def test_android_targets(self):
        a64 = "aarch64-linux-android"
        self.assertTrue(rc.cfg_matches('cfg(target_os = "android")', a64))
        self.assertTrue(rc.cfg_matches("cfg(unix)", a64))
        self.assertFalse(rc.cfg_matches("cfg(windows)", a64))
        self.assertFalse(rc.cfg_matches('cfg(target_arch = "wasm32")', a64))
        self.assertTrue(rc.cfg_matches('cfg(any(target_arch = "x86", target_arch = "aarch64"))', a64))
        self.assertTrue(rc.cfg_matches('cfg(not(all(windows, target_env = "msvc")))', a64))
        self.assertTrue(rc.cfg_matches('cfg(any(target_os = "linux", target_os = "android"))', "i686-linux-android"))
        self.assertTrue(rc.cfg_matches('cfg(target_pointer_width = "32")', "armv7-linux-androideabi"))
        self.assertTrue(rc.cfg_matches('cfg(target_has_atomic = "64")', "armv7-linux-androideabi"))
        # Custom flags (docsrs, getrandom_backend) are not set by an ordinary build.
        self.assertFalse(rc.cfg_matches("cfg(docsrs)", a64))
        self.assertFalse(rc.cfg_matches('cfg(getrandom_backend = "custom")', a64))
        self.assertTrue(rc.cfg_matches("aarch64-linux-android", a64))
        self.assertFalse(rc.cfg_matches("x86_64-pc-windows-msvc", a64))


class VersionTest(unittest.TestCase):
    def test_requirements(self):
        self.assertTrue(rc.version_matches("0.4.44", "0.4.45"))
        self.assertFalse(rc.version_matches("0.4.44", "0.5.0"))
        self.assertTrue(rc.version_matches("1", "1.9.0"))
        self.assertFalse(rc.version_matches("^0.0.3", "0.0.4"))
        self.assertTrue(rc.version_matches(">= 0.4.0, < 0.6.0", "0.5.1"))
        self.assertFalse(rc.version_matches(">= 0.4.0, < 0.6.0", "0.6.0"))
        self.assertTrue(rc.version_matches("~1.2", "1.2.9"))
        self.assertFalse(rc.version_matches("~1.2", "1.3.0"))
        self.assertTrue(rc.version_matches("=2.0.18", "2.0.18"))
        self.assertTrue(rc.version_matches("*", "7.0.0"))


class LicenceTest(unittest.TestCase):
    def test_choice(self):
        self.assertEqual(rc.choose("MIT OR Apache-2.0"), "MIT")
        self.assertEqual(rc.choose("Unlicense OR MIT"), "MIT")
        self.assertEqual(rc.choose("Apache-2.0 WITH LLVM-exception OR Apache-2.0 OR MIT"), "MIT")
        self.assertEqual(rc.choose("LGPL-2.1-or-later OR Apache-2.0"), "Apache-2.0")
        self.assertEqual(rc.choose("(MIT OR Apache-2.0) AND Unicode-3.0"), "MIT AND Unicode-3.0")
        self.assertEqual(rc.choose("MIT AND Apache-2.0 WITH LLVM-exception AND (MIT OR Apache-2.0)"),
                         "MIT AND Apache-2.0 WITH LLVM-exception")
        self.assertEqual(rc.normalise_licence("MIT/Apache-2.0"), "MIT OR Apache-2.0")

    def test_copyright_lines(self):
        m = rc.Manifest("x", "1.0.0", "MIT OR Apache-2.0", None, False, [], {})
        m.files = {
            "LICENSE-APACHE": "Copyright [yyyy] [name of copyright owner]\n"
                              "copyright license to reproduce, prepare Derivative Works of,\n",
            "LICENSE-MIT": "Copyright (c) 2014 The X Developers\n\nPermission is hereby granted ...\n"
                           "THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM\n",
        }
        self.assertEqual(rc.copyright_lines(m, "MIT"), ["Copyright (c) 2014 The X Developers"])
        m.files = {"COPYRIGHT": "Copyrights in the X project are retained by their contributors. No\ncopyright assignment is required.\n"}
        self.assertEqual(rc.copyright_lines(m, "MIT"), ["Copyrights in the X project are retained by their contributors."])
        # A holder that begins with "The" is a holder (libc's LICENSE-MIT); licence sentences are not.
        m.files = {"LICENSE-MIT": "Copyright (c) The Rust Project Developers\n\nPermission is hereby granted ...\n"
                                  "The above copyright notice and this permission notice\n"}
        self.assertEqual(rc.copyright_lines(m, "MIT"), ["Copyright (c) The Rust Project Developers"])

    def test_a_notice_when_the_licence_file_names_no_holder(self):
        # Review of 2026-11-16: 23 crates shipped with no holder (bare MIT texts). The fallbacks, in order.
        bare = {"LICENSE-MIT": "Permission is hereby granted ...\nThe above copyright notice and this permission notice\n"}
        m = rc.Manifest("x", "1.0.0", "MIT", None, False, [], {}, files=dict(bare),
                        authors=["A. Writer <someone" + "@" + "example.org>", "B. Writer"], repository="https://github.com/o/x")
        self.assertEqual(rc.notice_of(m, "MIT"), (["Copyright (c) A. Writer, B. Writer and the x contributors"],
                                                  "its Cargo.toml authors (its licence file names no holder)"))
        m.authors = []
        self.assertEqual(rc.notice_of(m, "MIT")[0], ["Copyright (c) the contributors of github.com/o/x"])
        m.name = "geo"   # no licence file in the crate: the repository's line
        m.files = {}
        self.assertEqual(rc.notice_of(m, "MIT")[0], ["Copyright (c) 2017 The GeoRust Project Developers"])
        m.name, m.files = "x", {"LICENSE-MIT": "Copyright (c) 2014 The X Developers\n"}
        self.assertEqual(rc.notice_of(m, "MIT"), (["Copyright (c) 2014 The X Developers"], "the crate's licence file"))


LOCK = {
    ("app", "1.0.0"): rc.Locked("app", "1.0.0", None, [("lib", None), ("helper", None), ("macro", None), ("wasmonly", None), ("buildtool", None)]),
    ("lib", "2.1.0"): rc.Locked("lib", "2.1.0", None, [("opt", None), ("never", None)]),
    ("helper", "0.3.0"): rc.Locked("helper", "0.3.0", None, []),
    ("macro", "1.0.0"): rc.Locked("macro", "1.0.0", None, [("macrodep", None)]),
    ("macrodep", "1.0.0"): rc.Locked("macrodep", "1.0.0", None, []),
    ("wasmonly", "1.0.0"): rc.Locked("wasmonly", "1.0.0", None, []),
    ("buildtool", "1.0.0"): rc.Locked("buildtool", "1.0.0", None, []),
    ("opt", "1.0.0"): rc.Locked("opt", "1.0.0", None, []),
    ("never", "1.0.0"): rc.Locked("never", "1.0.0", None, []),
}
MANIFESTS = {
    "app": """
[package]
name = "app"
version = "1.0.0"
license = "BSD-3-Clause"
[features]
default = ["fast"]
fast = ["lib/speed"]
[dependencies]
lib = "2"
macro = "1"
helper = { version = "0.3", optional = true }
[target.'cfg(target_os = "android")'.dependencies]
helper = "0.3"
[target.'cfg(target_arch = "wasm32")'.dependencies]
wasmonly = "1"
[build-dependencies]
buildtool = "1"
""",
    "lib": """
[package]
name = "lib"
version = "2.1.0"
[features]
default = []
speed = ["dep:opt"]
rare = ["dep:never"]
[dependencies.opt]
version = "1"
optional = true
[dependencies.never]
version = "1"
optional = true
""",
    "helper": '[package]\nname = "helper"\nversion = "0.3.0"\n',
    "macro": '[package]\nname = "macro"\nversion = "1.0.0"\n[lib]\nproc-macro = true\n[dependencies]\nmacrodep = "1"\n',
    "macrodep": '[package]\nname = "macrodep"\nversion = "1.0.0"\n',
    "wasmonly": '[package]\nname = "wasmonly"\nversion = "1.0.0"\n',
    "buildtool": '[package]\nname = "buildtool"\nversion = "1.0.0"\n',
    "opt": '[package]\nname = "opt"\nversion = "1.0.0"\n',
    "never": '[package]\nname = "never"\nversion = "1.0.0"\n',
}


class ResolveTest(unittest.TestCase):
    def test_normal_dependencies_of_the_target_with_features(self):
        g = rc.resolve(LOCK, ("app", "1.0.0"), ["default"], "aarch64-linux-android",
                       lambda pkg: rc.parse_manifest(MANIFESTS[pkg[0]]))
        got = sorted(n for (n, _) in g.features)
        # lib (normal), opt (turned on by app's default feature through lib/speed), helper
        # (android only); not the proc-macro and its dependency, not the build tool, not the
        # wasm-only crate, not an optional dependency no feature asks for.
        self.assertEqual(got, ["app", "helper", "lib", "opt"])
        self.assertIn("speed", g.features[("lib", "2.1.0")])


if __name__ == "__main__":
    unittest.main()
