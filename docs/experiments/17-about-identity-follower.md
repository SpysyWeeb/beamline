# 17. About, identity and the follower (track A, feature round)

Date: 2026-11-16 (night). Contract: `docs/architecture.md` 2.4 (`Ports.kt`, `GuidanceRules.kt`,
`FerrostarFollower.kt`), 2.8, 4 (start-up order), 5.1 "A: about, identity and the follower" with
the draft notice text, 6.14, 6.34, 6.35, 6.38 questions 1 and 6; `docs/architecture-downloads.md`
9 (what G adds to the classpath). Stage 1 (the first engineer) built `:feature:about` and
`:demo:about` with their device checks, the follower in `:nav:ferrostar` with its device test, and
the licence gate with every artifact covered. Stage 2 (the second engineer, who wrote this report)
re-ran stage 1's proofs, looked at its screenshots, finished the three open native component
lists (MapLibre, Valhalla, Ferrostar's Rust crates), found that the gate then fails on exactly one
component (glslang) and proved both ways out, added a device test of the follower on every named
script, re-ran the device checks, and wrote this report. Two reviewers then reproduced and read
the track; the engineer who finished it after the review fixed what they found (section 10,
"Review and fixes") and brought every section up to date: where a number below changed in that
pass, the new one is given and the run is dated "review". Module READMEs:
`android/feature/about/README.md`, `android/demo/about/README.md`, `android/nav/ferrostar/README.md`.
Scratch, recordings, screenshots and logs: `data/r3-a/` (not in git), with `data/r3-a/PROGRESS.md`;
the review pass's own files are under `data/r3-a/review/`.

## 1. What exists

| Module | What |
|---|---|
| `:feature:about` (Android, Compose) | The Night drive launcher icon (`@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`: adaptive, background and foreground bitmaps at mdpi..xxxhdpi rendered from the approved SVGs, monochrome vector for themed icons); `Theme.Beamline.Starting` (+ `values-v33`) and `Theme.Beamline.AfterSplash`; `Splash.install` (core-splashscreen, hand-over to the reveal); `SplashReveal` (the icon stays, the device's icon mask opens over `splash-scene.svg`, 0.7 s, held until `mapReady` for at most 2 s, 250 ms fade); `FirstRunNotice` (the draft text, no added words); `AboutScreen`; `LicencesScreen` (list, one licence, all notices in one text); `Notices.parse` and `loadNotices`; the notices assets; `scripts/render_icon.py`, `scripts/notices.py` (the notices file and the licence gate), `scripts/fetch_spdx.py`, `scripts/rust_crates.py` (new in stage 2). |
| `:demo:about` (not shipped) | Host for the device checks: the start-up order over a stand-in map (system splash, reveal until "map ready", first-run notice until accepted), About, licences, the driver notice and the icon under three masks; extras `screen`, `night`, `reset_notice`, `map_ready_ms`, `hold_ms`. |
| `:nav:ferrostar` (Android) | `FerrostarFollowerFactory` (the 2.4 port) over Ferrostar 0.59's core: `FerrostarRouteFollower` (Ferrostar does the snapping and the trip progress inside the step being driven; the follower applies the advance rule and its own geometry where Ferrostar's model does not fit the port; a Ferrostar failure leaves the geometry following) and `FollowerGeometry`; `consumer-rules.pro` (JNA, uniffi); core library desugaring; release test APK minified by R8; two device tests (`FerrostarFollowerDeviceTest`, 8 tests; `FerrostarScriptedDrivesDeviceTest`, 2 tests) and a JVM test (`FollowerGeometryTest`). |

The contract files `AboutUi.kt`, `AboutScreens.kt` and `FerrostarFollower.kt` changed only inside
their `TODO` stubs (each delegates to the module's own code); `check_contracts.py` reports them
`filled`.

## 2. How it is proved

All commands from the repository root in Git Bash. Results are the review pass's runs
(2026-11-16, 04:25 to 04:50 local) unless marked "stage 1" or "stage 2".

| What | Command | Result |
|---|---|---|
| JVM tests of `:feature:about` and `:nav:ferrostar` | `sh tools/round/gradle.sh a :feature:about:testDebugUnitTest --rerun :nav:ferrostar:testReleaseUnitTest --rerun` | 25 of 25 pass (counted in the JUnit XML): `NoticesParseTest` 8 (with the shipped 173-entry `notices.json` and every text file it names), `RevealTimelineTest` 6, `SplashSceneTest` 4, `AboutTextsTest` 3, `FollowerGeometryTest` 4 |
| Python tests of the scripts | `.venv/art/Scripts/python -m unittest discover -s android/feature/about/scripts -p "test_*.py"` | 32 OK (26 of stage 2 + 6 of the review: a holder for attribution licences, no e-mail in an entry, e-mails left out of texts, template copyright lines left out, the crate notice fallbacks, the shipped list's holders and e-mails) |
| Icon resources equal the approved SVGs | `.venv/art/Scripts/python android/feature/about/scripts/render_icon.py --check` | "21 files checked; all the same" |
| What ships | `.venv/art/Scripts/python android/feature/about/scripts/notices.py resolve --key a` | 22 module reports, 151 runtime artifacts + 2 desugaring artifacts, 6 native libraries, none missing from the Gradle cache |
| What ships, again (review, 04:33) | the same | the three reports byte for byte the same as at 04:00 (other tracks changed no dependency since) |
| The licence gate | `.venv/art/Scripts/python android/feature/about/scripts/notices.py --check --deps data/r3-a/notices` | **fails with 1 problem**: glslang's GPL-3.0-with-Bison-exception part (section 4). Everything else passes: 153 artifacts covered, 6 native libraries complete, 173 entries, every attribution entry with its holders, no e-mail address, assets current (`data/r3-a/notices/gate-final.txt`) |
| Both ways out, with today's tooling | `notices.py resolve --key a --gradle-arg=-Pbeamline.mapRenderer=opengl --out data/r3-a/notices-opengl`, then `.venv/art/Scripts/python data/r3-a/review/prove_options.py` (fresh scratch copies under `data/r3-a/p2/`) | OpenGL renderer, the three Vulkan-only entries removed: "licence gate: passed (170 entries, coverage checked)"; glslang allowed as a second exception: "licence gate: passed (173 entries, coverage checked)" (`data/r3-a/review/options-results.txt`; stage 2 had 169 and 172 before the review's added entry) |
| Contracts | `python tools/check_contracts.py` | "39 contract files ... all the same", "39 frozen files ... all unchanged" |
| Logging rule | `python android/scripts/check_private_logs.py scan --module feature/about --module nav/ferrostar --module demo/about` | "15 files in 3 modules; 0 marked lines; 0 breaches" |
| Network rule | `python android/scripts/check_network_rule.py scan`; `... privacy` | "scan: 0 finding(s)"; "privacy: 0 private IPv4 literal(s)" |
| Builds | `sh tools/round/gradle.sh a :demo:about:assembleDebug :nav:ferrostar:assembleReleaseAndroidTest :nav:ferrostar:assembleRelease` | BUILD SUCCESSFUL; `minifyReleaseAndroidTestWithR8` ran |
| Follower on the device (release test APK, R8, rebuilt after the review's changes) | install `ferrostar-release-androidTest.apk`, `am instrument ... FerrostarFollowerDeviceTest` | "OK (8 tests)" (`data/r3-a/review/ferrostar-device-test.txt`): the three advances 0.0 m from the golden trace, Ferrostar's snapping and progress on 287 of 289 fixes, the two new tests of section 10 |
| Follower on every named script and on a route with two stops | `am instrument ... FerrostarScriptedDrivesDeviceTest` | "OK (2 tests)": six scripts on exit 24 and two scripts on a three-leg route, the same step as N's `GeometricFollower` on every fix (`data/r3-a/review/ferrostar-scripts-test.txt`, section 5) |
| Licences with the 172 entries (stage 2) | `:demo:about`, `--es screen licences`, scrolled, glslang opened, "All notices" opened and scrolled | screenshots looked at (`data/r3-a/device2/licences-172-sheet.png`, section 7) |
| Licence pages after the review's notices fixes (172 entries: the Public Suffix List entry came after this check) | the same demo rebuilt, scrolled to and opened serde, unique_resource and parsedate (curl) | looked at: each shows its holder line above the text, the MIT template line is gone, curl's text has no address (`data/r3-a/review/device/licences-holders-sheet.png`, `licence-serde-2.png`) |
| First-run notice once | `--ez reset_notice true` cold start, tap "I understand", force-stop, cold start from the launcher | notice shown, then the UI dump has no "I understand" (`data/r3-a/device2/notice-once-sheet.png`) |
| Launcher icon, splash recording, About and licences day and night (stage 1) | `:demo:about` on `beamline_a`; files under `data/r3-a/device/` | looked at again in stage 2 (sections 3 and 7) |

Device checks run on the emulator `beamline_a` (API 36, 1080 x 2400, 420 dpi), no data pushed.

## 3. Measurements

**Splash and reveal (stage 1, emulator, debug build; looked at frame by frame).** Recordings
`data/r3-a/device/splash-cold-start*.mp4` and `splash-hold-*.mp4`, frames cut with imageio-ffmpeg,
contact sheets `splash2-sheet-a.png`, `splash2-sheet-b.png`, `splash-circle-handover.png`, phases by
`data/r3-a/reveal_phases.py`.

| Measured | Value | Designed |
|---|---|---|
| System splash icon, centred | 504 px wide at 420 dpi = 192 dp | 192 dp visible of 288 dp |
| Frames before and after the hand-over to the reveal | identical (square mask, and circle mask with the overlay) | identical |
| Map ready late (6 s): fade starts after the opening starts | about 2.68 s | 0.7 + 2.0 = 2.7 s |
| Map ready at 1.8 s | the fade waits for it (about 1.7 s after the opening starts) | held until ready |
| Fade | about 240 ms | 250 ms |
| Frame times during a cold start (`dumpsys gfxinfo`, shared emulator, debug) | 41 frames, 50th percentile 34 ms, 90th 97 ms | indicative only |

What I see in the contact sheets (stage 2 looked at them again): the system splash shows the icon
on the dark land colour; the reveal's first frames are the same picture; the mask (a rounded square
on this AOSP image) opens to the full scene within about 0.17 s (ease-out cubic: most of the radius
is reached early), the buildings, park and river fade in over the rest of the 0.7 s, the arrow and
beam never move, then the scene holds and fades to the stand-in map (light), then the demo menu.
The opening has few recorded frames (in `splash2-sheet-a.png`: icon size at 3.742 s, a mask
almost as wide as the screen at 3.750 s, the full screen at 3.907 s, nothing recorded between
3.792 s and 3.907 s): the shared emulator drops frames, so the smoothness of the opening is not
proved here.

**The follower on the exit 24 drive (N's golden trace, 289 fixes at 1 fix/s, release test APK
minified by R8).** Run again in the review pass with the reworked follower
(`data/r3-a/review/ferrostar-device-test.txt`); the advances are the same as in stages 1 and 2:

| Maneuver | Ferrostar follower advances at | N's test follower (golden trace) | Apart | Fix past the maneuver point (exit_m 10) |
|---|---|---|---|---|
| 1 "Exit 24" | t = 171 s | t = 171 s | 0.0 m | 21.3 m |
| 2 "Turn left onto US 19 / Peach St" | t = 222 s | t = 222 s | 0.0 m | 10.6 m |
| 3 "Turn right onto Downs Dr" | t = 251 s | t = 251 s | 0.0 m | 11.7 m |

Steps reached 1, 2, 3, never back; Ferrostar's own step equal to the follower's on every fix;
Ferrostar's snapping and trip progress used on 287 of the 289 fixes (the other two lie between a
maneuver's point and the advance); largest lateral offset on the clean drive 0.0 m (0.1 m with the
follower's own geometry in stage 2); remaining 29.8 m at the last fix, Ferrostar's figure (29.7 m
with the follower's own; section 5 says why they differ) (the trace ends in ARRIVED, which the
session decides). Every fix at 40 m accuracy (worse than
decide_max_accuracy_m 30): never advances. A fix moved 20 m north of the I-90 line: lateral
16.81 m (the road runs at about 57 degrees), equal to the fix-to-snapped distance. With
Ferrostar's own `DistanceEntryAndSnappedExit(20, 10, 30)` on the same fixes: step 0 of 4 reached.
The nearest fixes to the ends of Ferrostar's steps 0-2: 8.4, 2.2 and 2.5 m.

**The notices file (review).** 173 entries: 6 data, 2 artwork, 1 font, 24 libraries (23 with
their Maven coordinates, and the Public Suffix List inside OkHttp), 140 native components
(libmaplibre.so 37, libvalhalla-wrapper.so 16, libferrostar.so 89 including Ferrostar itself, the
Rust standard library, LLVM libunwind and 86 crate names over 91 crate versions; components shared
by several libraries count once). 153 entries name their copyright holders; the 20 that do not are
under Apache-2.0 (its NOTICE-file rule, not a holder line), Apache-2.0 WITH LLVM-exception, GPL-2.0
WITH the Classpath Exception (desugar_jdk_libs, the accepted exception; its entry carries the text
and the source address; no holder line, not changed here) or public domain.
`notices.json` 47.6 KB, `NOTICES.txt` 186.2 KB, 22 licence text files 198.2 KB. Licences, counting
each part of an `AND`: MIT 106, Apache-2.0 22, ISC 13, BSD-3-Clause 9, BSD-2-Clause 6, public
domain 5, MPL-2.0 4 (uniffi's three crates and the Public Suffix List), BSL-1.0 4, and one each of
the rest. (Stage 2 gave 172 entries, 45.7 KB, 184.4 KB and 198.6 KB, counted its own way.)

## 4. The licence gate

**Built.** `notices.py --check` fails when an artifact on the union of release runtime classpaths
(and the `coreLibraryDesugaring` configuration) of every module `:app` will depend on has no entry,
when a native library in the `jni/` folders of their AARs is not described or its component list is
not marked complete, when an entry names a version that does not resolve or covers something that
does not ship, when a licence is GPL, AGPL or LGPL in any form outside the one allowed pair
(`desugar_jdk_libs`, GPL-2.0-only WITH Classpath-exception-2.0), when an upstream `OR` has no
recorded choice (also inside an `AND`, e.g. compiler_builtins' "MIT AND Apache-2.0 WITH
LLVM-exception AND (MIT OR Apache-2.0)"), when an id is not in the pinned SPDX list 3.29.0, when a
text is missing, or when the assets differ from what the list makes; since the review also when an
entry under a licence that asks for the copyright notice (MIT, BSD, ISC, Zlib, BSL-1.0, curl, ICU,
Unicode, FTL, OFL, ...) names no holder, when an MPL-2.0 entry does not say where its source is, and
when an entry or an asset holds an e-mail address. Tests show it failing on a removed entry, an
entry marked GPL, a stale version, a stale cover, an undescribed or incomplete native library, a
BOM not declared as shipping nothing, an AAR missing from the cache, and (review) an attribution
entry without a holder and an entry with an e-mail address.

**What ships (resolved again 2026-11-16 04:00, with the other tracks' build files of that time).** 153
artifacts (G's OkHttp 5.3.2 and Okio 3.16.4 among them, both Apache-2.0, as
`docs/architecture-downloads.md` 9 says) and 6 native libraries: `libandroidx.graphics.path.so`,
`libsqliteJni.so`, `libjnidispatch.so` (stage 1), and the three stage 2 finished:

- **`libmaplibre.so`** (MapLibre Native android-v13.7.1, Vulkan build): 37 components from the
  CMake link list of `mbgl-core`, `platform/android/android.cmake` and `vendor/vulkan.cmake`, each
  licence read in MapLibre's `platform/android/LICENSE.md` or, where that file is silent, in the
  licence file of the pinned submodule. Marker strings in the arm64 library confirm SQLite, ICU 61,
  FreeType, HarfBuzz, PMTiles, FSST, FastPFOR, VulkanMemoryAllocator, glslang/SPIR-V, protozero,
  supercluster, Boost and libc++abi. **MapLibre's own LICENSE.md omits ten things it links:** ICU,
  SQLite, HarfBuzz, PMTiles, unordered_dense, jni.hpp, the MapLibre Tile code, Vulkan-Headers,
  VulkanMemoryAllocator and glslang (and lists metal-cpp, which only Apple builds use). FreeType is
  taken under FTL (its other option is GPL-2.0-or-later), with its BDF and PCF drivers' MIT-style
  notices; HarfBuzz under its "Old MIT" licence plus the MIT of its Microsoft USE data.
- **`libvalhalla-wrapper.so`** (valhalla-mobile 0.6.4, Valhalla 3.9.1): 16 components; stage 1's
  list from the vcpkg.json plus Valhalla's own `third_party` code on the library's include path:
  Howard Hinnant's date library (its message "not found in timezone database" is in the library),
  the IANA time zone data (embedded, public domain), cpp-statsd-client (the `statsd.*` keys are
  in the library), vtzero with protozero (the tile action's `mvt_*` keys are in the library) and
  flatbush. The library is stripped, so vtzero and flatbush are listed so that nothing is missed,
  not because a symbol proves them. OSM-binary (LGPL), libosmium, microtar, cxxopts and just_gtfs
  leave none of their strings: not shipped.
- **`libferrostar.so`** (Ferrostar 0.59.0): no Rust toolchain on this PC, so
  `scripts/rust_crates.py` does what `cargo tree -e normal --target <android triple>` would: it reads
  Ferrostar's `common/Cargo.lock` and Cargo.toml files at the tag, fetches each crate's `.crate`
  from crates.io once (sha256 checked against the lock), and runs Cargo's feature resolver 2 for
  the four Android targets with the build's features (`cargo ndk ... build -p ferrostar`: default
  = std, uniffi), following only normal dependencies (no build- or dev-dependencies, no proc-macro
  crates) through `cfg(...)` tables evaluated per target. Result: 81 crates (all 15 crate paths the
  arm64 library names are among them; an upper bound of what the linker keeps after LTO, e.g.
  uniffi's default `cargo_metadata` and its `camino`, `semver`, `cargo-platform` probably leave
  nothing in the binary). Plus the Rust standard library 1.99.1 (the `rustc version` string in the
  library) with its 11 crates.io dependencies from rust-lang/rust's `library/Cargo.lock` at that
  tag (gimli, addr2line and miniz_oxide paths are in the library) and the NDK's LLVM libunwind
  (statically linked). Each `MIT OR Apache-2.0` (or wider) choice records MIT, the others their
  most permissive option; copyright lines are read from each crate's own licence files (e-mail
  addresses left out); since the review, where a crate's licence file names no holder (a bare MIT
  text, as in serde, anyhow, itoa and most of their siblings), the line comes from the licence file of
  its repository (geo, geo-types, rstar, i_overlay, i_key_sort, which ship no licence file), else
  from its Cargo.toml authors ("Copyright (c) <the authors it names> and the serde
  contributors"), else from its repository ("the contributors of github.com/mozilla/uniffi-rs");
  each entry's `checked` says which. uniffi, uniffi_core and uniffi_pipeline are MPL-2.0
  (file-level copyleft; allowed by the policy; their entries carry the source URL, which MPL 3.2
  asks the notices to tell).

**The one failure: glslang.** MapLibre's Vulkan renderer (`org.maplibre.gl:android-sdk`, the frozen
`beamline.mapRenderer=vulkan`) statically links glslang to compile its shaders on the phone.
glslang's `LICENSE.txt` at MapLibre's pinned commit `a92c61f8` puts "glslang proper" under
BSD-3-Clause, BSD-2-Clause, MIT, Apache-2.0 and "GPL 3 with special bison exception" (its
Bison-generated grammar parser), and the preprocessor also under an NVIDIA 2002 notice and a
Khronos MIT-style notice. Recorded honestly, the entry's licence is `BSD-3-Clause AND BSD-2-Clause
AND MIT AND Apache-2.0 AND GPL-3.0-or-later WITH Bison-exception-2.2 AND LicenseRef-NVIDIA-2002
AND MIT-Khronos-old`, and the gate refuses it: the Bison exception lets the parser ship inside a
larger work under any terms (much as the Classpath Exception does for `desugar_jdk_libs`), but
6.34 allows exactly one GPL-with-exception entry, and this would be a second. Two ways out, both
run and passing (section 2): **(1)** the OpenGL renderer (`beamline.mapRenderer=opengl`, a frozen
line; `android-sdk-opengl` 13.7.1 is the only artifact that changes, and its arm64 library holds no
glslang, SPIR-V, VMA or Vulkan strings and needs no libvulkan.so), or **(2)** glslang as a second
allowed exception. The request with the exact edits is `data/architecture/requests/a.md`. Not
measured: how the OpenGL renderer compares with Vulkan on the owner's phone. Both ways were run
again in the review pass with today's tooling and list (section 2), and the marker strings were
counted again in the two arm64 builds: Vulkan `libmaplibre.so` glslang 7, "SPIR-V" 24,
VmaBlockMetadata 3, libvulkan.so 2; OpenGL build 0 of each.

**Found in the review pass: the Public Suffix List.** OkHttp 5.3.2 (G's and Ferrostar's HTTP client)
ships Mozilla's Public Suffix List as data (`assets/PublicSuffixDatabase.list` in its Android AAR),
which is under MPL-2.0, not OkHttp's Apache-2.0. No classpath check can see a licence inside an
artifact's data; it now has its own entry ("Public Suffix List (inside OkHttp)", MPL-2.0, with its
address). Other artifacts were not searched the same way.

## 5. The follower

Ferrostar 0.59's Kotlin bindings, read from `core-0.59.0.aar` with javap (stage 1):
`NavigationController(route, config)` exists but has no methods in 0.59; navigation runs on
`Navigator` (`createNavigator(route, config, shouldRecord)`, `getInitialState`,
`updateUserLocation`, `advanceToNextStep`); the step conditions are top-level factories
(`stepAdvanceDistanceEntryAndSnappedExit(distanceToEndOfStep, distanceAfterEndOfStep,
minimumHorizontalAccuracy)` is public, `stepAdvanceManual()`), `WaypointAdvanceMode.WaypointWithinRange`,
`RouteDeviationTracking.None`, `CourseFiltering.SNAP_TO_ROUTE`.

**Finding (stage 1, on the device, and re-run in stage 2 and in the review):** with the contract's
own condition, `DistanceEntryAndSnappedExit(20, 10, 30)`, Ferrostar never advanced on the exit 24
drive. Its geometry is right (each step ends on our maneuver point), but when the exit check is not
yet met the condition returns `new_instance()`, which forgets the entry. The review read it in the
0.59.0 crate's own source (`src/navigation_controller/step_advance/conditions.rs`, crates.io
`ferrostar-0.59.0.crate`): `should_advance_step` runs the exit check while
`has_reached_end_of_current_step` is set, and a failed exit check ends in
`result.unwrap_or(StepAdvanceResult::continue_with_state(self.new_instance()))`, where
`new_instance()` sets `has_reached_end_of_current_step: false // Always reset this to the initial
state`.
At 1 fix/s on I-90 the fix 16.3 m before the exit entered, the next one was 8.4 m past (exit_m is
10: no advance, entry forgotten) and the next 21.3 m past (outside entry_m: cannot enter again).
Ferrostar also snaps only to the current step, clamped at its end, while the session needs the
distance to the route line (O1-O3) and a negative distance past a maneuver (B2). So the follower
uses the contract's fallback, `ManualStepCondition`: Ferrostar is advanced in lock-step and the
advance rule is the follower's (the 2.4 port's rule, as N's `GeometricFollower` documents it).
`FerrostarFollowerDeviceTest.ferrostarsOwnConditionLosesTheEntryAtTheExit` keeps the finding: it
fails when a newer Ferrostar keeps the entry, and then the rule can go back to it.

**Who computes what (since the review).** Stage 2's follower used none of Ferrostar's answers
(a reviewer's finding, section 10). Now, for every fix whose route point lies in the step being
driven (between its maneuver and the upcoming one), the progress is Ferrostar's: its snapped point
and the segment it reports (Ferrostar's step geometry index plus the step's first shape index), the
lateral offset from that point, its distance to the next maneuver, its remaining distance and
duration. The follower's own geometry (`FollowerGeometry`, the search window of N's
`GeometricFollower`) answers where Ferrostar's model cannot: after the upcoming maneuver's point and
before the advance (Ferrostar clamps at the step's end, the port needs the negative distance of
B2), when the fix's route point lies in another step, when Ferrostar's point is more than 3 m from
the follower's (another stretch of the same step), and at the last step. The follower's own point
is still computed on every fix: it decides the advance (exit_m past the maneuver) and guards
Ferrostar's answer. A Ferrostar failure (the OSRM answer unreadable, no such route, not one step
per maneuver, or any exception from a Ferrostar call, a JNA `LinkageError` included) turns
Ferrostar off for that route and the follower goes on with its own geometry; `create()` no longer
throws for any of these. Ferrostar's remaining distance adds up the engine's own step lengths,
which are 0.13 % longer than the haversine length of their geometry that `GeometricFollower`
measures (exit 24: 5,341.6 m against 5,334.6 m; per step +0.24 to +5.09 m); the session reads
neither figure (it computes its own from the snapped point and segment).

**Every named script, and a route with two stops.** `FerrostarScriptedDrivesDeviceTest` makes the
six scripts of section 8 with N's own `RouteSimulator` and `SimScripts` on a virtual clock and gives
each fix to this follower and to N's `GeometricFollower` (which `:nav:session`'s JVM tests use for
these scripts), with the forced advances rule B2 asks for (a deciding fix on the line snapped more
than past_m beyond the upcoming maneuver). Since the review the follower's values on most fixes are
Ferrostar's, so this compares Ferrostar's snapping and progress (Rust, through JNA and R8) with N's
independent Kotlin, not a copy with itself. The review added a route with two stops: N's
pyvalhalla recordings 39 (native) and 71 (OSRM form) of the same request
(`nav/session-recordings.json.gz`: the exit 24 start, stops at 42.03832,-80.108429 and
42.050136,-80.081933, Downs Drive; three legs of 2, 4 and 3 maneuvers), driven with the default and
jitter scripts. Result from the R8-minified release test APK on `beamline_a`, review pass,
2026-11-16 about 04:36 (`data/r3-a/review/ferrostar-scripts-test.txt`):

| Route, script | Fixes | Steps advanced at (s) | Forced | Same step on every fix | Ferrostar's progress | Largest difference: snapped / lateral / toManeuver / remaining / remainingS |
|---|---|---|---|---|---|---|
| exit 24, default | 453 | 170, 221, 250 | 0 | yes | 99.6 % | 0.06 m / 0.06 m / 0.02 m / 1.89 m / 0.20 s |
| exit 24, wrongturn | 453 | 170, 202, 267 | 0 | yes | 99.6 % | 0.07 / 0.07 / 0.02 / 1.89 / 0.20 |
| exit 24, gaps | 410 | 170, 202, 231 | 0 | yes | 99.5 % | 0.06 / 0.06 / 0.02 / 1.89 / 0.20 |
| exit 24, jitter | 453 | 170, 202, 231 | 0 | yes | 99.6 % | 0.44 / 0.11 / 0.44 / 2.01 / 0.20 |
| exit 24, drift | 453 | 170, 202, 231 | 0 | yes | 99.6 % | 0.06 / 0.06 / 0.03 / 1.91 / 0.20 |
| exit 24, tunnel | 393 | 217, 217, 231 | 2 | yes | 99.2 % | 0.06 / 0.06 / 0.02 / 1.89 / 0.20 |
| two stops, default | 453 | 102, 103, 189, 222, 240, 241, 250 | 2 | yes | 98.5 % | 0.06 / 0.06 / 0.02 / 4.30 / 0.11 |
| two stops, jitter | 453 | 82, 83, 170, 202, 220, 222, 230 | 2 | yes | 98.0 % | 0.37 / 0.11 / 0.37 / 4.42 / 0.11 |

Tolerances asserted: the same step, leg and `complete` on every fix, Ferrostar's own step equal to
the follower's, never back, Ferrostar on, snapped point, lateral offset and distance to the
maneuver within 1 m, remaining distance within 1 m + 0.2 % of the route (the step lengths above),
Ferrostar's share at least 90 % on exit 24 default and 80 % on the two-stop route. The remaining
time is only reported. Stage 2's table (its own geometry on every fix) had 0.00-0.45 m differences;
the snapped and lateral differences of 0.06 m now are Ferrostar's great-circle point against
`GeometricFollower`'s planar one.

The default script gives 170, 221 and 250 s here against 171, 222 and 251 s in the golden
trace, whose first row is stamped 1 s; the offset is the clocks' start, not traced further. In the
jitter runs the two snapped points differ by up to 0.44 m along the line (lateral within 0.11 m):
probably the two projections pick different points where two segments are about equally near (not
investigated further). At each stop two steps advance on consecutive fixes (the leg's arrival and
the next leg's departure share a point), and B2 forces two advances, the same in both followers. So
the session, which N proves on these scripts with `GeometricFollower`, sees the same steps with this
follower; the session itself was not run with it on the device (wiring the app is I's).

## 6. Choices and their reasons

- **Reveal starts from the device's icon mask** (`AdaptiveIconDrawable.getIconMask()` on API 31+,
  a circle below), not always a circle (stage 1): the system splash cuts the icon with the
  device's mask (a square on this AOSP image); a circle start visibly jumped at the hand-over. On a
  Pixel the mask is the design's circle.
- **"Held until mapReady (at most 2 s)"** read as at most 2 s of hold after the 0.7 s opening (the
  fade starts at most 2.7 s after the reveal starts); one constant (`HOLD_MAX_MS`) if the owner
  wants 2 s in all. A reviewer asked whether 2 s was meant in all; kept, because 5.1 says "held on
  the full scene until `mapReady`, at most 2 s", and the scene is full only after the opening
  (section 10).
- **The reveal's drawing**: roads, buildings, park and river as vectors from `splash-scene.svg`'s
  numbers (tested), the icon's foreground bitmap (288 dp, resvg) for the arrow, beam and glow,
  because of their blurs. Its clock starts at the system splash's hand-over, with a 300 ms
  fallback when no splash is shown. `Theme.Beamline.AfterSplash` (land colour as window
  background) avoids a flash; `:app` can override it by name.
- **No heading on the first-run notice**: only the icon, the draft text and the button, so no
  unapproved words are added.
- **Notices format** `beamline-notices/1` with a `full_text` key; a text file per licence
  expression for the one-licence screen, and in `NOTICES.txt` each licence text once (stage 2;
  stage 1 repeated texts per expression). Long expressions get a short hashed file name (stage 2):
  glslang's expression of about 140 characters made a file name that broke a copy of the tooling
  under a deeper folder (Windows' 260-character paths), and Gradle's build folders are deeper
  still.
- **Public-domain data** (US Government, Natural Earth, the IANA tz data) as
  `LicenseRef-Public-Domain` with a short text of our own; **MapLibre GeoJSON/Turf 6.0.1** as MIT
  (repository licence) although its POMs say Apache-2.0: keeping the MIT notice is the safe side.
- **JNA under Apache-2.0** with the `OR` offer kept as `upstream_licence` (owner question 6, as
  recommended). **Each `MIT OR Apache-2.0` crate under MIT**, other choices the most permissive
  offered, never a GPL-family option.
- **One allow-list entry, in code** (`ALLOWED` in notices.py), so a second exception is a code
  change the owner can see, not a data edit. glslang is therefore left failing, not allowed.
- **The Rust crate list is what Cargo resolves, not what the linker keeps**: crediting a crate
  whose code LTO dropped is harmless; missing one that ships is not. Without a Rust toolchain the
  resolution is done in Python from Cargo's own inputs; the alternative was installing rustup on the
  owner's PC overnight, which no one approved.
- **E-mail addresses** are left out of everything track A ships or keeps in git (names kept; the
  round's privacy rule says no e-mail address in any tracked file). Since the review that includes
  the licence texts: curl's copyright line loses its address (the name and years stay), FreeType's two
  mailing lists read "[e-mail address left out]"; the terms do not depend on them, and the gate now
  fails on any address in an entry or an asset.
- **Template copyright lines** ("Copyright (c) <year> <copyright holders>" in MIT, BSD-2, BSD-3 and
  ISC) are left out of the app's copy of those texts (review): the entry's own holder line stands
  above the text, and the text's "above copyright notice" is then that line.
- **Copyright holders where a licence file names none** (review): the crate's repository licence
  file, else its Cargo.toml authors, else "the contributors of" its repository; each entry's
  `checked` says which. For Boost, whose licence file names no holder and whose files name many:
  "Copyright the authors of the Boost C++ Libraries (each source file names its holders)".
- **Follower on `ManualStepCondition`**, with the rule computed by the follower (not by the
  session calling `advance()` for every step): the 2.4 port says the step advances by guidance.json
  `advance` or by `advance()`, and the session calls `advance()` only for B2. The follower does not
  call N's classes; its device tests compare with them.
- **Ferrostar's answer guarded by the follower's own point** (review): used only within 3 m of it
  and inside the step, so the session sees what it was tested with (`GeometricFollower`) wherever
  the two could disagree, and Ferrostar's answer everywhere else (98-99.6 % of the fixes measured).
- **A Ferrostar failure degrades, it does not throw** (review): the follower is complete without
  Ferrostar, so a bad OSRM answer or a native failure costs Ferrostar, not guidance. Not reported to
  the session (the port has no channel for it); the tests read `ferrostarOff`.
- **R8-minified test APK**: `testBuildType = "release"`, `androidTest { enableMinification = true }`
  (AGP 9.5 DSL), `testProguardFiles(consumer-rules.pro, test-rules.pro)`, the test APK signed with
  the debug key; the library itself stays unminified (the app's R8 run does that).
- **New venv package** (stage 1): imageio-ffmpeg 0.6.0 in `.venv/art` (BSD-2-Clause wrapper; its
  ffmpeg binary is a PC-side tool, never shipped), to cut frames from the screen recordings.
- **Root on the own emulator** (stage 1) for Launcher3's themed-icon preference and icon cache, and
  fabricated overlays for the circle and squircle masks (this launcher's own shape setting is behind
  a flag that is off); all restored afterwards.

## 7. Definition of done (5.1 "A"), item by item

| Item of "Done when" | State | Proof |
|---|---|---|
| `notices.py --check` passes against what will ship (the union of release runtime classpaths, `coreLibraryDesugaring`, the native libraries of their AARs, G's additions included) | **not done: fails on one entry, glslang, pending the owner** | `notices.py resolve --key a` again in the review (04:33, the same reports as at 04:00), then `--check --deps data/r3-a/notices` (`data/r3-a/notices/gate-final.txt`): 153 artifacts covered, 6 native libraries complete, 173 entries with their holders, 1 problem (glslang's GPL-3.0-with-Bison-exception part, section 4). Both ways out run again with today's tooling and pass (`data/r3-a/review/options-results.txt`); request `data/architecture/requests/a.md` |
| The check fails on a removed entry and on an entry marked GPL | done | `test_notices.py`: `CoverageTest.test_a_removed_entry_fails`, `PolicyTest.test_an_entry_marked_gpl_fails` (and the gate itself fails on glslang); 32 OK |
| JVM test of `Notices.parse` | done | `NoticesParseTest` 8 of 8, including the shipped 173-entry file and every text it names |
| Launcher screenshot from `:demo:about`: circle and squircle masks, themed icon on | done (stage 1), looked at | `data/r3-a/device/launcher-shapes-sheet.png`: the Night drive icon under a circle and a squircle (home screen and drawer), the monochrome arrow on a pale circle and squircle with themed icons on, and the AOSP default rounded square (masks switched with a runtime overlay, section 8) |
| A screen recording of the splash looked at frame by frame | done (stage 1), looked at again | `splash-cold-start*.mp4`, frame folders and sheets; what the frames show is in section 3 |
| About and licences screenshots | done, looked at | stage 1 `data/r3-a/device/screens-sheet.png` (About, licences, one licence, all notices, the notice; day and night); stage 2 `data/r3-a/device2/licences-172-sheet.png`: the list starts with the data credits, the native components and Rust crates follow sorted by name, glslang's page shows its licence expression, its copyright lines and the texts, and "All notices in one text" shows the list and then each licence text once; review `data/r3-a/review/device/licences-holders-sheet.png` and `licence-serde-2.png`: the licence pages of serde, unique_resource and curl's parsedate with their holder lines, no template line, no address |
| First-run notice shown once and not again after accepting | done (stage 1, and again in stage 2) | stage 2 `data/r3-a/device2/notice-once-sheet.png`: the draft text with "I understand" after a reset; after the tap, a force-stop and a cold start from the launcher, the menu, and the UI dump has no "I understand" |
| `:nav:ferrostar`'s device test: exit 24 at 1 fix/s, the three advances within 15 m of N's test follower (golden trace), never back, from a release-minified test APK | done | `FerrostarFollowerDeviceTest` OK (8 tests, review pass, the APK rebuilt with `minifyReleaseAndroidTestWithR8`): 0.0 m apart at all three, never back, Ferrostar's progress on 287 of 289 fixes |
| Delivers: icon (adaptive, every density, foreground bitmaps, monochrome vector) | done | `render_icon.py --check`: 21 files the same; launcher sheet |
| Delivers: `Theme.Beamline.Starting`, `Splash.install`, `SplashReveal` (about 0.7 s, held until mapReady, at most 2 s) | done | `RevealTimelineTest`, `SplashSceneTest`; stage 1 measurements (section 3) |
| Delivers: `FirstRunNotice` (draft text), `AboutScreen`, `LicencesScreen`, `Notices.parse` | done | `AboutTextsTest` (the text equals the draft of 5.1), the screenshots above |
| Delivers: `notices.py` producing `assets/notices/NOTICES.txt` and `notices.json` from a curated list with an SPDX licence per entry; the data credits of 5.1; `desugar_jdk_libs` with the Classpath Exception text; JNA under Apache-2.0 | done | `notices.py` writes the assets; the tree test checks that OSM, Natural Earth, TIGER/Line, USDOT NAD, Scranton, Noto Sans, tangrams, Protomaps, desugar_jdk_libs and JNA (Apache-2.0) are in it and the Classpath text is in `NOTICES.txt`; the US Census boundaries entry is there too |
| Delivers: `FerrostarFollowerFactory` on Ferrostar 0.59 with the named configuration, binding names confirmed, `lateralM` from the snapped point | done, with the contract's fallback for the step advance | section 5: `ManualStepCondition`, because 0.59's `DistanceEntryAndSnappedExit` forgets its entry (measured, and read in its source); Ferrostar does the snapping and the trip progress (98-99.6 % of the fixes); `lateralM` from Ferrostar's snapped point there, from the follower's elsewhere; lateral test 16.81 m = fix to snapped point |
| Delivers: consumer R8 rules (JNA, uniffi), core library desugaring | done | `consumer-rules.pro` is used by the test APK's R8 run (`testProguardFiles`), which runs Ferrostar through JNA on the device; `isCoreLibraryDesugaringEnabled = true` |
| Round rule 11: contracts, own tests, emulator stopped, report and READMEs, PROGRESS | done | section 2; `emulator.sh a status`: "emulator-5570 (beamline_a): not running", no slot of key a |

## 8. Not done, not verified, failed

- **The licence gate fails** on glslang until the owner decides (section 4). Nothing else fails.
- **The Rust crate list is Cargo's resolution re-done in Python, not `cargo tree` itself**: no Rust
  toolchain was installed; `rust_crates.py` re-implements resolver 2 for normal dependencies and
  was tested on a small graph and checked against the 15 crate paths in the binary, not against a
  real `cargo tree` run. Its `cfg` evaluation treats unknown flags as unset and gives each Android
  target a short list of target features (enough for these crates; a crate keyed on another target
  feature would be resolved wrongly).
- **Valhalla's vtzero and flatbush** are listed because they are on the library's include path;
  the stripped library cannot show whether they are compiled in. **SIMD Everywhere** is listed
  because MapLibre lists it; its use on Android was not checked. The libffi version that JNA
  5.18.1 bundles was not checked (stage 1).
- **MapLibre GeoJSON/Turf**: the POMs say Apache-2.0, the repository says MIT; MIT is recorded;
  not resolved with the publisher (stage 1).
- **Reveal frame rate** measured only on the shared emulator with a debug build; not on a release
  build or the phone; the scene drawing was optimised afterwards and not re-measured (stage 1). The
  recordings show few frames during the first 0.17 s, when the mask reaches the screen's edges.
- **Squircle and circle launcher shapes** were shown by switching the system icon mask with a
  runtime overlay; this launcher's own shape setting is behind a flag (stage 1). The system splash
  on API 26-30 (core-splashscreen's circle) was not seen on a device; only API 36 was.
- **The follower** is proved on the clean drive against the golden trace, on all six scripts and
  on a three-leg route (two scripts) against N's follower, fix by fix, but not inside
  `BeamlineNavigationSession` on the device (that is I's wiring). The two-stop route was driven
  with the default and jitter scripts only.
- **The follower's step advance is still its own**, not Ferrostar's: Ferrostar 0.59's condition
  cannot be used (section 5). Whether to keep Ferrostar at all is open question 4.
- **The graphics-path native sources** were read on androidx-main, not at the 1.0.1 tag (stage 1).
- **Valhalla `statsd.*`**: the keys are in the library; whether Valhalla creates its UDP statsd
  client when no host is configured was not tested (for N and I: the configuration must not set
  `statsd.host`).
- **Licence texts**: stage 2 wrote here that each entry's own copyright lines stand above the
  template text and called it cosmetic. That was wrong: 26 MIT, BSD and BSL entries had no holder
  at all, so the app showed only the template line. Fixed in the review (section 10).
- **Data inside artifacts**: the Public Suffix List inside OkHttp was found by looking (section 4);
  no other artifact's data was searched for licences of its own.
- Nothing was run on the phone (only I uses the phone).

## 9. Open questions

1. **glslang (new, owner).** MapLibre's Vulkan build links glslang, whose licence has a GPL-3.0
   part with the Bison exception. Choose: (a) the OpenGL renderer (one frozen line,
   `beamline.mapRenderer=opengl`; "one exception" stays true; how it draws on the phone is not
   measured), or (b) accept glslang as a second exception. Request: `data/architecture/requests/a.md`.
   Track A's recommendation (review): (b), unless "one exception" must stay literally true. The
   Bison exception lets the generated parser ship under any terms, the same kind of exception the
   owner already accepted for `desugar_jdk_libs`, and (b) keeps the renderer the phone was measured
   with (report 11); (a) needs a look at the map on the phone first.
2. **Owner question 1 (6.38)**: the first-run notice ships the draft text, unchanged; to approve.
3. **Owner question 6 (6.38)**: JNA is taken under Apache-2.0, as recommended; to confirm.
4. **Keep Ferrostar on the phone? (lead and owner; it changes 6.14 and 6.35)** Since the review
   Ferrostar does the snapping and the trip progress on 98-99.6 % of the fixes, but its 0.59 advance
   condition does not work at 1 fix/s at highway speed, so the step advance (6.14's other half) is
   the follower's, and the follower keeps its own geometry where Ferrostar clamps at a step's end.
   Using N's `GeometricFollower` on the phone instead would drop JNA, `libferrostar.so` (2.0-2.8 MB
   per ABI), the 89 native notice components of that library (Rust crates, std, uniffi's MPL-2.0
   files) and the `desugar_jdk_libs` exception, and the two followers give the same steps on every
   script and on the two-stop route (section 5). Kept as built for this round; the 5.1 wording
   ("uses `ManualStepCondition` if a condition has no public constructor") does not cover the case
   met here (a public constructor whose condition misbehaves) and needs the lead's amendment either
   way.
5. **uniffi under MPL-2.0** (three crates in `libferrostar.so`): MPL 3.2 asks that recipients of the
   binary be told where the source of those files is; their entries carry the crates.io URL, where
   the source is published. Enough for a prototype; a published app may want a sentence in About.
6. **The comment in `android/gradle.properties`** says Vulkan is "the artifact Ferrostar's map UI
   uses"; Beamline does not use Ferrostar's map UI (6.14), so that reason does not hold here (for
   the lead, with question 1).

## 10. Review and fixes

Two reviews (one reproduced the proofs, one read the code against the contract) found three majors
and four minors. Each was checked against the code before acting. All runs below are of the review
pass, 2026-11-16 04:25-04:50.

| Finding | Checked | Outcome |
|---|---|---|
| Major (both reviews): the licence gate fails on glslang | true; the gate re-run after a fresh `resolve` gives the same single problem | **Not fixable inside track A**: the way out is a frozen line (option a) or a second exception against 6.34 (option b). Both re-proved with today's tooling (`data/r3-a/review/prove_options.py`: 170 and 173 entries, passed); marker strings counted again in both arm64 builds; the request now carries a recommendation (option b, section 9). The TreeTest allowance stays until the decision |
| Major: about 25 MIT, BSD and BSL entries ship with no copyright holder; "cosmetic" in section 8 was wrong | true: the policy check run on stage 2's list reports 26 entries without a holder (`data/r3-a/review/holder-check-before-after.txt`) | **Fixed.** Holders filled: 23 Rust crates (`rust_crates.py` now falls back to the repository's licence file, the Cargo.toml authors, or the repository, and no longer drops "Copyright (c) The Rust Project Developers" as a sentence: libc), LZ4, Protocol Buffers, robin-hood-hashing, Boost, expected-lite, unique_resource (from their upstream licence files and headers, fetched into `data/r3-a/notices/upstream/copyright/`). The gate fails on any attribution entry without a holder (`NEEDS_HOLDER`), with tests; template copyright lines are left out of the app's texts; the wrong sentence in section 8 is corrected. After: 0 entries without a holder; the device shows the holder above the text (`data/r3-a/review/device/licences-holders-sheet.png`, `licence-serde-2.png`, looked at) |
| Major: Ferrostar runs but contributes nothing; the device tests compare a copy of `GeometricFollower` with itself | true: every field came from `FollowerGeometry`; Ferrostar's state fed only a test hook | **Fixed as far as Ferrostar 0.59 allows.** The snapping and trip progress are now Ferrostar's on every fix inside the step being driven (287 of 289 on exit 24; 98-99.6 % on the scripts); the advance rule stays the follower's, because Ferrostar's own condition forgets its entry (read in the 0.59.0 source, section 5). The device tests now compare Ferrostar's answers with N's independent `GeometricFollower`, report the share, and fail if Ferrostar is off or answers on fewer than 90 % (exit 24) or 80 % (two stops) of the fixes. Whether to keep Ferrostar remains the lead's and owner's question 4 |
| Minor: a decorative Ferrostar call can kill guidance (unguarded native calls, a strict step-count `require` in `create()`, `advance()` before the first fix leaves Ferrostar behind) | true for all three | **Fixed.** Every Ferrostar call is guarded; a failure turns Ferrostar off for that route and the geometry goes on; `create()` no longer throws on an unreadable answer, a missing route or a step-count mismatch; advances before the first fix are applied to Ferrostar's first state. New device tests: `anOsrmAnswerFerrostarCannotUseLeavesTheGeometryFollowing` (not JSON, no route 3, one step less: Ferrostar off, the same advances 171/222/251 s) and `anAdvanceBeforeTheFirstFixKeepsFerrostarInStep`; a three-leg route on the device (section 5) |
| Minor: Ferrostar used only as a mirror departs from the 5.1 wording | true | Partly answered by the fix above; the wording of 5.1 ("if a condition has no public constructor") still does not describe the case, so it stays an item for the lead (section 9, question 4) |
| Minor: e-mail addresses in verbatim licence texts (curl, FTL) against rule 5 | true: three addresses in tracked files | **Fixed** by the literal reading of rule 5: `notices.py` and `fetch_spdx.py` leave addresses out of texts, the two tracked source texts were cleaned, and the gate and the Python tests fail on any address in an entry or an asset |
| Minor: the hold limit could mean 2 s in all, the code allows 0.7 + 2 s | read 5.1 and 2.8 again | **Not changed (refuted):** 5.1 says "held on the full scene until `mapReady`, at most 2 s"; the scene is full only once the 0.7 s opening is over, so the 2 s are the hold. One constant changes it if the lead reads it otherwise (section 6) |

Also found in this pass: the Public Suffix List inside OkHttp (MPL-2.0) had no entry (section 4);
it has one now.

Changed in this pass: `android/feature/about/scripts/notices.py`, `rust_crates.py`, `fetch_spdx.py`,
`test_notices.py`, `test_rust_crates.py`; `android/feature/about/notices/notices-source.json` and
`licences/curl.txt`, `licences/FTL.txt`; the generated assets under
`android/feature/about/src/main/assets/notices/`; `android/nav/ferrostar/.../FerrostarRouteFollower.kt`
and both device tests; this report, the three READMEs, `data/architecture/requests/a.md` and the
board notes `a-notices.md`, `a-follower.md`. Scratch: `data/r3-a/curate_natives.py` (holders of
the C and C++ entries) and `data/r3-a/review/`.

## 11. How to re-run

From the repository root, in Git Bash.

```
# JVM and Python
sh tools/round/gradle.sh a :feature:about:testDebugUnitTest --rerun :nav:ferrostar:testReleaseUnitTest --rerun
.venv/art/Scripts/python -m unittest discover -s android/feature/about/scripts -p "test_*.py"
.venv/art/Scripts/python android/feature/about/scripts/render_icon.py --check
# the notices file and the licence gate
.venv/art/Scripts/python android/feature/about/scripts/notices.py
.venv/art/Scripts/python android/feature/about/scripts/notices.py resolve --key a
.venv/art/Scripts/python android/feature/about/scripts/notices.py --check --deps data/r3-a/notices
# the other renderer's classpath (for the glslang decision)
.venv/art/Scripts/python android/feature/about/scripts/notices.py resolve --key a --gradle-arg=-Pbeamline.mapRenderer=opengl --out data/r3-a/notices-opengl
.venv/art/Scripts/python data/r3-a/review/prove_options.py      # both ways out, in scratch copies
# repository checks
python tools/check_contracts.py
python android/scripts/check_private_logs.py scan --module feature/about --module nav/ferrostar --module demo/about
python android/scripts/check_network_rule.py scan
# device
sh tools/round/emulator.sh a start
sh tools/round/gradle.sh a :demo:about:assembleDebug :nav:ferrostar:assembleReleaseAndroidTest
sh tools/round/adb.sh a install -r data/gradle/a/build/demo/about/outputs/apk/debug/about-debug.apk
sh tools/round/adb.sh a shell am start -n io.github.spysyweeb.beamline.aboutdemo/io.github.spysyweeb.beamline.about.demo.DemoActivity --es screen licences
sh tools/round/adb.sh a install -r data/gradle/a/build/nav/ferrostar/outputs/apk/androidTest/release/ferrostar-release-androidTest.apk
sh tools/round/adb.sh a shell "am instrument -w -r -e class io.github.spysyweeb.beamline.nav.ferrostar.FerrostarFollowerDeviceTest io.github.spysyweeb.beamline.nav.ferrostar.test/androidx.test.runner.AndroidJUnitRunner"
sh tools/round/adb.sh a shell "am instrument -w -r -e class io.github.spysyweeb.beamline.nav.ferrostar.FerrostarScriptedDrivesDeviceTest io.github.spysyweeb.beamline.nav.ferrostar.test/androidx.test.runner.AndroidJUnitRunner"
sh tools/round/emulator.sh a stop
```

Frames from a screen recording: imageio-ffmpeg in `.venv/art` (see `data/r3-a/PROGRESS.md`);
`data/r3-a/reveal_phases.py` and `data/r3-a/contact_sheet.py` analyse them.
