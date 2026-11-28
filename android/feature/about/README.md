# :feature:about

About and identity (docs/architecture.md 2.8 and 5.1 "A"): the Night drive launcher icon, the
launch theme, the system splash and the reveal, the first-run notice, About, the licences and the
notices file. Android library, Compose. Owner during the feature round: track A. Report:
`docs/experiments/17-about-identity-follower.md`.

## What the app uses (for I)

| Piece | How |
|---|---|
| `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round` | `android:icon` / `android:roundIcon` in `:app`'s manifest (remove `:app`'s old icon files, 5.4 item 11) |
| `@style/Theme.Beamline.Starting` | the activity's theme. Its `postSplashScreenTheme` is `Theme.Beamline.AfterSplash` (this module; window background = the splash's land colour). To end on `:app`'s own theme, define `<style name="Theme.Beamline.AfterSplash" parent="Theme.Beamline"/>` in `:app` (an app style overrides a library style of the same name) |
| `Splash.install(activity) { keep }` | in `onCreate`, **before** `super.onCreate` (core-splashscreen's rule). `keep` holds the system splash; keep it short: the reveal does the waiting for the map |
| `SplashReveal(mapReady, onFinished, Modifier.fillMaxSize())` | over the map, at the top of the window, edge to edge (its centre must be the window's centre, where the system splash drew the icon). Show it once per process (rememberSaveable). While it shows, use dark system bars (`enableEdgeToEdge(SystemBarStyle.dark(TRANSPARENT), SystemBarStyle.dark(TRANSPARENT))`), or a light scrim covers the bottom of the scene |
| `FirstRunNotice(onAccept)` | after the reveal when `AppState.firstRunNoticeAccepted < FIRST_RUN_NOTICE_VERSION`; on accept store `FIRST_RUN_NOTICE_VERSION` |
| `DriverNoticeScreen(onBack)` | About's "Driver notice" row (`AboutEvent.OpenDriverNotice`): the notice under a top bar with Back and its title, nothing to accept (play test P59). It handles the system back key itself |
| `AboutScreen(ui, onEvent)`, `LicencesScreen(notices, onBack)` | `loadNotices(context)` reads `assets/notices/notices.json` (call it off the main thread). Both handle the system back key themselves |

The screens draw with the caller's `MaterialTheme` (`BeamlineTheme`, day or night as the map) and
keep clear of the system bars themselves (`safeDrawingPadding`).

## The reveal

`SplashRevealContent.kt`, timing in `RevealTimeline.kt` (JVM-tested), scene numbers in
`SplashScene.kt` (equal to `docs/design/app-icon/splash-scene.svg`, tested). The system splash
shows the icon (288 dp, the middle 192 dp visible, cut by the **device's icon mask**:
`config_icon_mask`, a circle on a Pixel). The reveal starts from that same picture, at the same
scale (1 icon unit = 192/72 dp) and the same mask (`AdaptiveIconDrawable.getIconMask()` on
Android 12+, a circle below), waits for the system splash to be handed over (`SplashHandoff`),
then opens in 700 ms (ease-out cubic, the design's overshoot), holds the full scene until
`mapReady` for at most 2 s, fades out in 250 ms and calls `onFinished`. With animations switched
off in the system settings it starts on the full scene. While it shows it takes every touch and
stands for the whole window for screen readers ("Beamline is starting"): the controls under it
are not offered until it has gone (play test P29).

## Resources and scripts

- `scripts/render_icon.py` renders `res/mipmap-*/ic_launcher_{background,foreground}.png` (108 dp)
  and `res/drawable-*/splash_icon_{background,foreground}.png` (288 dp) at mdpi..xxxhdpi from the
  approved SVGs with resvg 0.49.0 (`tools/bin/resvg`), and writes the monochrome vector
  `res/drawable/ic_launcher_monochrome.xml`. `--check` re-renders and compares.
- `scripts/notices.py` makes `src/main/assets/notices/` (`notices.json`, `NOTICES.txt`,
  `licences/*.txt`) from `notices/notices-source.json` (one entry per shipped component, SPDX
  licence, what it covers, where the licence was read) and `notices/licences/` (texts of the SPDX
  list v3.29.0, from `scripts/fetch_spdx.py`, plus the hand-made `LicenseRef-Public-Domain` and
  `LicenseRef-NVIDIA-2002`, the latter copied from glslang's LICENSE.txt). `notices.json` names one
  text file per licence expression (a long expression gets a short hashed file name: Windows
  paths, build folders included, stop at 260 characters); `NOTICES.txt` has the list and then each
  licence and exception text once.
- The licence gate: `notices.py resolve --key <key> [--gradle-arg=-P...]` saves the dependency
  reports of every module `:app` will depend on (release runtime classpaths and
  `coreLibraryDesugaring`, resolution only, through `tools/round/gradle.sh`) and lists the native
  libraries of their AARs; `notices.py --check --deps <that folder>` fails on an artifact or native
  library without an entry, a native library whose component list is not complete, a stale version
  or cover, any GPL/AGPL/LGPL licence (the allow-list is empty since 2026-11-17), an unchosen
  `OR` (an entry records the option taken; a choice inside an `AND` is checked too), an unknown SPDX
  id, a missing text or stale assets, an entry under a licence that asks for the copyright notice
  (MIT, BSD, ISC, Zlib, BSL-1.0, ... `NEEDS_HOLDER`) without its holders, an MPL-2.0 entry without
  its source address, and an e-mail address in an entry or an asset (the round's privacy rule: the
  app's copies of the licence texts leave them out, and their template copyright lines too, since
  each entry's own holder line stands above the text).
- The native libraries' component lists (`native_libraries` in the source file, each with its
  `basis`): `libmaplibre.so` from MapLibre Native android-v13.7.1's CMake link list and the licence
  files of its pinned submodules (MapLibre's own LICENSE.md misses ten of them), `libvalhalla-wrapper.so`
  from valhalla-mobile's vcpkg.json and Valhalla 3.9.1's `third_party`; until 2026-11-17 also
  `libferrostar.so` from `scripts/rust_crates.py`: Cargo's feature resolver 2 in Python, run on Ferrostar 0.59.0's
  Cargo.lock and each crate's own Cargo.toml (`.crate` files from crates.io, checked against the
  lock's sha256), for the four Android targets, plus the Rust standard library 1.99.1 and its
  crates.io dependencies. A crate's copyright line comes from its licence files, else from its
  repository's licence file (`REPOSITORY_NOTICES`, for crates that ship none), else its Cargo.toml
  authors, else its repository; `checked` says which. `rust_crates.py apply` writes the crate
  entries (marked `"source": "rust_crates.py"`); the MapLibre and Valhalla entries were written with track A's
  scratch script `data/r3-a/curate_natives.py` (marked `"source": "curate_natives.py"`), and the
  upstream files read are under `data/r3-a/notices/upstream/` (not in git).
- Some licences sit inside an artifact's data, where no classpath check sees them: OkHttp ships
  the Public Suffix List (MPL-2.0), which has its own entry. Look inside a new artifact's files.
- State (2026-11-17, Ferrostar dropped): 71 entries (from 170: Ferrostar core, its 94 Rust and native
  components, JNA, `desugar_jdk_libs` and its configuration, and kotlinx-datetime, which only
  Ferrostar brought, are gone), no exception (the allow-list is empty), every attribution entry with
  its holders. (2026-11-16, integration: 170 entries.) The app
  draws with MapLibre's **OpenGL** build (`org.maplibre.gl:android-sdk-opengl`,
  `beamline.mapRenderer=opengl`, the owner's choice of 2026-11-16, option 1 of
  `data/architecture/requests/a.md`): the Vulkan build linked glslang, whose licence has a GPL-3.0
  part with the Bison exception, so its three Vulkan-only entries (Vulkan-Headers,
  VulkanMemoryAllocator, glslang) are gone and the gate has no known failure. `LicenseRef-NVIDIA-2002`
  stays in `notices/licences/` but no entry uses it. Tests: `python -m unittest discover -s
  android/feature/about/scripts -p "test_*.py"` (32; the tree test allows no policy problem).

## Tests

```
sh tools/round/gradle.sh <key> :feature:about:testDebugUnitTest      # Notices.parse, timeline, scene, texts
sh tools/round/gradle.sh <key> :feature:about:connectedDebugAndroidTest   # emulator: driver notice page, reveal for screen readers
.venv/art/Scripts/python -m unittest discover -s android/feature/about/scripts -p "test_*.py"
.venv/art/Scripts/python android/feature/about/scripts/render_icon.py --check
.venv/art/Scripts/python android/feature/about/scripts/notices.py --check --policy-only
.venv/art/Scripts/python android/feature/about/scripts/notices.py resolve --key <key>
.venv/art/Scripts/python android/feature/about/scripts/notices.py --check --deps data/r3-<key>/notices
```

`rust_crates.py` (kept for a later Rust library; it was used for Ferrostar, dropped 2026-11-17; it fetches `.crate` files once):

```
python android/feature/about/scripts/rust_crates.py resolve --lock <ferrostar common/Cargo.lock> \
    --member <common/ferrostar/Cargo.toml> --workspace <common/Cargo.toml> --cache <folder> --out crates.json
python android/feature/about/scripts/rust_crates.py apply crates.json [std.json] --library libferrostar.so \
    --first "Ferrostar core" --first "Rust standard library" --first "LLVM libunwind"
```

Device checks run from `:demo:about` (its README).
