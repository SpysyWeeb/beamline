# Experiment 05: Android toolchain and skeleton app

Date: 2026-11-15. Machine: Windows 11, 12 logical CPUs, 32 GB RAM, NVIDIA RTX 2080 Super
(Max-Q) laptop GPU. **Four other engineers were running heavy map-data builds (Planetiler with
5 GB heaps, large downloads) on the same machine during every measurement below**, and the
network was shared and slow at times. All emulator numbers are indicative only; they say nothing
reliable about a real phone.

## Result in short

- **Second round (same day, after two independent reviews; see "Review and fixes").** The three
  major findings were reproduced on the emulator with the reviewed APK and fixed: a pack with
  impossible bounds or a style template of the wrong shape **crashed the app on every start**, and
  an empty, foreign or cut-off map file gave **a blank grey map with no message**. Packs are now
  checked (bounds, PMTiles header and size, sprites, glyphs, style shape), and every problem is
  named on screen. A new device test (`android/scripts/pack_variants.py`) installs 23 damaged
  packs one by one: the reviewed APK crashed on 5 and drew an empty or wrong screen without any
  explanation on 16 more; the fixed APK handles all 23. The cheap minor findings were fixed as
  well. Final screenshots use the map track's **14:30 Pennsylvania rebuild** and their current
  style bundle.
- The Android SDK is installed by a pinned script (`tools/fetch_android_sdk.py`). Since
  command-line tools 23.0, `sdkmanager` is only a wrapper around Google's new **Android CLI**,
  which installs itself into `~/.android/` and collects usage metrics unless told not to (details
  in "Toolchain").
- `android/` builds with Gradle 9.9.1, AGP 9.5.1, Kotlin 2.4.21 on the installed Temurin 25.
  There are two modules: `:app` (Compose UI) and `:core:packs`, plain Kotlin/JVM with no Android or UI.
  `:core:packs` finds packs, reads and checks their `manifest.json` and files, and builds the
  style. Its unit tests (20 tests, 32 cases counting the parameterised ones; 12 in the first round)
  pass with `./gradlew :core:packs:test`.
- The app shows a full-screen MapLibre map from a PMTiles file and an offline style (style JSON,
  glyphs, sprites) in the app's external files folder. It shows the OpenStreetMap credit, switches
  day and night with the system dark mode (live, without restarting), and shows a plain message
  when no pack is installed. It has **no INTERNET permission**, and no other permission except one
  that AndroidX declares for itself.
- **Final verification used the map track's real Pennsylvania build**
  (`data/map/packs/pa-261114-z0-15.pmtiles`, OSM 2026-11-14) **with the map track's style bundle**
  (`packbuilder/map/style-bundle/`, Protomaps light/dark). It was also run with **two packs at once**
  (their Ohio build too). Before those existed, a stand-in pack (Pennsylvania cut from a
  Protomaps daily planet build plus my hand-written style) was used; those results are kept below.
- Verified on the emulator: map drawn at state, city and street level (Erie, Pittsburgh,
  Philadelphia), readable labels, credit visible, day/night, airplane mode, no-pack case, and a
  broken-pack message. The kernel's per-app traffic counters show **zero bytes** for the app.
- MapLibre: `org.maplibre.gl:android-sdk` **13.7.1** (Vulkan). That is the artifact and major line
  Ferrostar 0.59's map UI pulls in (13.0.2, through maplibre-compose 0.13.0), so adding Ferrostar
  will raise nothing and lower nothing. OpenGL (`android-sdk-opengl`) can be chosen with one
  Gradle property and was measured too.

## 1. Toolchain

### What was installed and where

| Item | Version | Where | Disk |
|---|---|---|---|
| Android SDK command-line tools | 23.0 (build 16111833, SHA-1 checked) | `%LOCALAPPDATA%/Android/Sdk/cmdline-tools/latest` | 179 MB |
| Platform-tools | 37.0.1 | `Sdk/platform-tools` | 17.5 MB |
| SDK platform android-36 | r2 (targetSdk) | `Sdk/platforms` | 231 MB for both |
| SDK platform android-37.0 | r2 (compileSdk, see "Versions") | `Sdk/platforms` | (above) |
| Build-tools | 36.0.0 (AGP 9.5 default) | `Sdk/build-tools` | 143 MB |
| Emulator | 37.2.13 (stable channel) | `Sdk/emulator` | 1,122 MB |
| System image | `android-36;default;x86_64` r2 (AOSP, no Google apps) | `Sdk/system-images` | 2,050 MB |
| **SDK total** | | | **3,743 MB** (sum of file sizes, as the script prints it) |

A second run of the script installs nothing and lists the same revisions (checked).
Installing took 1 min 50 s for the build packages, 8.5 min for emulator plus system image and
55 s for platform 37.0, on a shared, busy connection.

Outside the SDK folder. None of these is a system-wide install; all are under the user profile:

| What | Made by | Size |
|---|---|---|
| `~/.android/bin` and `~/.android/cli` (the Android CLI runtime, version 1.1.16919734) | Google's `android.exe` launcher in cmdline-tools 23.0, downloaded on first use | 73 MB + 252 MB |
| `~/.android/cli/tos_displayed`, `~/.android/analytics.settings` (`hasOptedIn: false`) | Android CLI / Android tools | tiny |
| `~/.android/avd/beamline_api36.avd` + `.ini` (the emulator device, with three packs pushed) | `avdmanager` (standard location) | 5.7 GB |
| `~/.android/debug.keystore` | AGP (signs debug builds, and the release build for local tests) | 3 KB |
| `~/.android/emu-*` | emulator | tiny |
| `~/.gradle/wrapper/dists/gradle-9.9.1-bin` | Gradle wrapper (normal download cache) | 147 MB |
| `~/.gradle/caches/9.9.1` + new dependency groups in `caches/modules-2` | Gradle (normal cache) | about 800 MB + 370 MB |

Nothing was written to `~/.gradle/gradle.properties` or `~/.gradle/init.d`. No PATH, registry or
Windows feature changes. Gradle found the installed Temurin 25 itself, so it did not download a JDK.
In the repo (ignored by git): `.venv/android` (14 MB, `pmtiles` for the stand-in pack) and
`data/android/` (1.7 GB: packs, APK copies, logs, screenshots).

To undo: delete `%LOCALAPPDATA%/Android/Sdk`, `~/.android/avd/beamline_api36.*`,
`~/.android/bin`, `~/.android/cli` (the `adbkey` files in `~/.android` predate this work).

### Things that went wrong or were surprising

1. **sdkmanager is now a wrapper around the "Android CLI".** Command-line tools 23.0's
   `sdkmanager.bat` prints a deprecation warning and runs `android sdk ...`. The launcher then
   downloads the full CLI (about 325 MB) into `~/.android/bin` and `~/.android/cli`, which is
   outside the approved SDK folder. Google's tool did this on its own.
2. **The wrapper breaks package names on Windows.** `sdkmanager.bat --install "platforms;android-36"`
   ended with `Package platforms not found. Package android-36 not found.`: the batch file splits
   arguments at `;`. The script therefore calls `android.exe` directly with `/`-separated paths
   and an exact revision per package (`platforms/android-36@2`, `emulator@37.2.13`, ...). The new
   CLI can pin every package, including platform-tools and the emulator, which the old sdkmanager
   could not.
3. **Usage metrics.** The Android CLI sends usage statistics to Google (command names and flag
   names; Google says no paths or values) unless `--no-metrics` is given. The first two runs went
   through the `sdkmanager.bat` wrapper without that flag, so **those invocations probably sent
   metrics**. Every later call, and the committed script, passes `--no-metrics`. I did not create
   `~/.androidrc` (which could make that permanent) because it is a user-level config file; that
   is the owner's call.
4. **Licences.** The new CLI says "The --licenses option is no longer needed": it shows the SDK
   terms link and proceeds, and it wrote `Sdk/licenses/`. The script refuses to run without
   `--accept-licenses`, so accepting stays an explicit decision (the owner approved it for this
   machine).
5. **The first download was corrupt.** It returned the full length but the wrong SHA-1 (the link
   was saturated). The script now retries and checks every download. One `emulator` download
   inside the old wrapper also failed with "closed" after 25 minutes; the retry through the CLI
   took 8.5 minutes for emulator plus system image.

### Hardware acceleration

`emulator -accel-check` printed `WHPX(10.0.26300) is installed and usable.` The emulator ran with
WHPX and the host GPU: `Selecting Vulkan device: NVIDIA GeForce RTX 2080 Super with Max-Q Design`,
guest Vulkan 1.3, host GLES. Nothing had to be enabled. AVD: `medium_phone` (1080x2400, 420 dpi),
3 GB RAM (`hw.ramSize=3072`), started with `-no-window -gpu host -memory 3072`. It booted in about
2 minutes. The emulator was stopped at the end (`adb emu kill`).

## 2. Versions and why

All are pinned in `android/gradle/libs.versions.toml` (or the SDK script) and were looked up on
2026-11-15 at Google Maven, Maven Central, the Gradle plugin portal and services.gradle.org.

| Item | Version | Why |
|---|---|---|
| Gradle (wrapper, SHA-256 pinned) | 9.9.1 | Current release. AGP 9.5 needs at least 9.6.0. The wrapper jar was regenerated by 9.9.1 and matches Gradle's published checksum (`621008af...`). |
| Android Gradle Plugin | 9.5.1 | Latest stable. Supports up to API 37, needs JDK 17+. |
| JDK running Gradle | Temurin 25.0.3 (already installed) | AGP 9.5.1, Kotlin 2.4.21 and Gradle 9.9.1 all run on it; the one JDK 25 issue in AGP was fixed in 9.5.0-alpha08. Pinned with Gradle's own daemon-JVM criteria (`gradle/gradle-daemon-jvm.properties`: version 25, vendor Adoptium, plus download URLs from the foojay resolver), so another machine gets the same JDK automatically. No JDK path is committed. |
| Kotlin (KGP, Compose compiler plugin, serialization plugin) | 2.4.21 | Latest stable. AGP 9 ships built-in Kotlin with KGP 2.2.10; declaring the Kotlin plugins in the root build lifts it to 2.4.21. kotlinx-serialization 1.13.0 is built with Kotlin 2.4.10, so it needs 2.4.x anyway. |
| Compose BOM | 2026.09.00 (Compose 1.12.1, Material3 1.4.0) | Latest stable |
| activity-compose / core-ktx / lifecycle | 1.13.0 / 1.19.1 / 2.11.0 | Latest stable |
| kotlinx-serialization-json / coroutines | 1.13.0 / 1.11.0 | Latest stable |
| MapLibre Native Android | `org.maplibre.gl:android-sdk:13.7.1` (Vulkan) | See below |
| JUnit (tests only) | 6.1.3 (Jupiter) | Latest stable (6.2.0 is a milestone) |
| foojay-resolver-convention (settings plugin) | 1.0.0 | Lets Gradle download the daemon JDK; repeated in `settings.gradle.kts` because settings cannot read the catalog |
| compileSdk / targetSdk / minSdk | **37** / **36** / **26** | See below |

**compileSdk 37, not 36.** The first build failed because the current AndroidX releases (core
1.19.1, Compose 1.12.1, lifecycle 2.11.0) refuse to build against less than API 37:
`Dependency 'androidx.core:core-ktx:1.19.1' requires libraries and applications that depend on it to
compile against version 37 or later`. I kept current libraries and compile against
`android-37.0`. targetSdk stays 36, which is what Google Play requires for new apps and updates
since 2026-08-31 (checked on developer.android.com). The other choice is older AndroidX
(BOM 2026.06 or so) with compileSdk 36.

**minSdk 26.** Ferrostar core 0.59.0 declares minSdk 25 and valhalla-mobile 0.6.4 declares 24 (read
from their AAR manifests). 26 (Android 8.0) adds `java.nio.file`, `java.time` without desugaring
and adaptive icons without PNG fallbacks. Android 7.1 devices are a negligible share.

### MapLibre line and renderer

The release lines on Maven Central (GitHub release dates):

| Line | Latest | Notes |
|---|---|---|
| 13.5.x | 13.5.4 (2026-11-09) | maintenance branch `android-13.5.x`, bug fixes only |
| 13.7.x | **13.7.1** (2026-10-15) | newest feature release from `main` |
| date-versioned | 2026.11.2-globe | marked pre-release on GitHub, globe projection work; not considered |

All of them read `pmtiles://file://...` (added in 11.8). 13.7.1 was chosen over 13.5.4 because it
has fixes this app benefits from:

- invalid UTF-8 in a name no longer crashes `collator` expressions (OSM names are user data);
- OpenGL extension detection on some emulators;
- `global-state` expressions (a possible way to switch day/night without reloading the style);
- PMTiles from APK assets.

13.5.4 was released after 13.7.1 and carries three Vulkan fixes (#4788, #4817, #4818). Whether
13.7.1 already contains #4788 was not checked; #4817 and #4818 are newer than it. Going back to
13.5.4 is a one-line catalog change if 13.7.1 misbehaves.

Since 13.0, `org.maplibre.gl:android-sdk` **is the Vulkan build** (identical to
`android-sdk-vulkan`); `android-sdk-opengl` is OpenGL ES, and `android-sdk-vulkan-opengl` carries
both renderers (twice the native size). Ferrostar 0.59.0 `ui-maplibre` →
`org.maplibre.compose:maplibre-compose-android:0.13.0` → `org.maplibre.gl:android-sdk:13.0.2`
("requires", so Gradle upgrades it to our 13.7.1). The default is therefore `android-sdk`
(Vulkan): same artifact, no substitution rules needed. `-Pbeamline.mapRenderer=opengl` switches
to `android-sdk-opengl`. The MapLibre artifacts declare no Gradle capabilities, so mixing the
OpenGL artifact with Ferrostar's map UI would put two copies of the same classes on the classpath
("duplicate class" error) unless `android-sdk` is substituted. See "Next step".

**Conflict to decide.** The Vulkan AAR's manifest contains
`<uses-feature android:name="android.hardware.vulkan.version" android:version="0x400003" android:required="true"/>`.
Google Play will hide the app from devices without Vulkan 1.0.3. The OpenGL AAR does not do this.
On this emulator the Vulkan build was also clearly faster (below), but that says little about
phones.

## 3. The project

```
android/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew(.bat), gradle/wrapper/*
  gradle/libs.versions.toml            every version
  gradle/gradle-daemon-jvm.properties  JDK for the Gradle daemon (Temurin 25)
  app/                                 :app  - Compose UI, MapLibre MapView
  core/packs/                          :core:packs - plain Kotlin/JVM: PackScanner, PackManifest, StyleComposer (+ tests)
  sample-pack/style/{day,night}.json   hand-written stand-in styles (Protomaps schema 4.x)
  scripts/push_pack.py, measure.py     adb helpers
  README.md                            build, run, pack format
tools/fetch_android_sdk.py             SDK install (pinned)
tools/fetch_android_map_pack.py        app pack from the map track's outputs
tools/fetch_android_sample_pack.py     stand-in pack from a Protomaps daily build
tools/fetch_android_requirements.txt   pmtiles==3.8.1 for the stand-in pack script
```

- **Identity in one place.** `beamline.applicationId=io.github.spysyweeb.beamline` and
  `beamline.appName=Beamline` are in `android/gradle.properties`. The build, the manifest label
  and the adb scripts read them from there. The Kotlin namespace `io.github.spysyweeb.beamline.app`
  is separate from the application id.
- **Logic outside the UI.** `:core:packs` has no Android dependency at all. It is a `kotlin("jvm")`
  module, so it cannot reach UI code even by accident, and a future Android Auto screen or a test
  can use it as is. `:app` has a `MapViewModel` that calls it on the IO dispatcher, and Compose
  screens that only display the resulting state. The trip-in-progress layer (`:core:trip`) does
  not exist yet; the README names where it goes.
- **Caps.** `org.gradle.jvmargs=-Xmx2g`, `org.gradle.workers.max=4`, and
  `kotlin.compiler.execution.strategy=in-process`, so no separate Kotlin daemon with its own heap.
- **No INTERNET.** The MapLibre AAR declares INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE
  and both location permissions. The app manifest removes all five (`tools:node="remove"`). The
  merged manifest only asks for
  `io.github.spysyweeb.beamline.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (AndroidX's own signature
  permission). `MapLibre.setConnected(false)` is set at start-up, and MapLibre logs
  `connected - false`. Removing ACCESS_NETWORK_STATE did not crash MapLibre's connectivity
  receiver (checked on the emulator).
- `configChanges` includes `uiMode` and orientation. Switching day/night or rotating keeps the
  MapView and the camera; Compose recomposes and the screen calls `setStyle` with the other
  template.
- Test hooks: `--ef lat/lon/zoom` intent extras place the camera. The app logs
  `first map frame N ms` / `first fully rendered map frame N ms` (N measured from process fork)
  and one `frames:` line per second while drawing (tag `BeamlineMap`).

### Pack folder and manifest (version 1)

The format is described in `android/README.md`. Packs live in
`Android/data/<application id>/files/packs/<folder>/`, one folder per pack, each with a
`manifest.json`. That is the app's own external files folder: adb can write to it, and the app
needs no storage permission. The manifest holds: `manifest_version`, `pack_id`, `name`,
`country`, `state`, `osm_snapshot`, `bounds`, `attribution`, `map` (file, `format` pmtiles,
`format_version` 3, tile `schema` + `schema_version`, `source_name`, size, sha256) and an optional
`style` (day/night templates, glyph template, sprite names, style spec version 8). Unknown keys are
ignored and a higher `manifest_version` is refused. Paths must stay inside the pack. Folders whose
name starts with `.` (copies in progress) are skipped.

It is plain JSON on both sides: the Python tools write it, and the Kotlin tests parse the same
shape (`core/packs/src/test/resources/manifest-v1.json`).

`StyleComposer` takes the style template from the first pack (by id) that has one. It points the
template's vector source at `pmtiles://file:///.../map.pmtiles`, and the glyph and sprite URLs at
`file:///.../`. Glyph font names with spaces ("Noto Sans Regular") load fine through `file://`.

The map track's templates use `{{PACK_URL}}` / `{{STYLE_BASE_URL}}` placeholders. They work as they
are, because those placeholders occur only in exactly the URLs the composer replaces.

**Difference to settle with the map track:** their README suggests shipping the style bundle
inside the app (`asset://style`). My manifest lets a pack carry it. Both work with the code: a
pack without `style` would just need a built-in fallback, which is not written yet.

### Unit tests

`./gradlew :core:packs:test` runs 20 JUnit 6 tests (32 cases with the parameterised ones) on the JVM:

- manifest parsing: the Python shape, optional parts, missing fields;
- scanning: empty or missing folder, broken JSON, missing map file, newer manifest version, wrong
  format, path escape, duplicate ids, in-progress folders; since the second round also six kinds of
  impossible bounds, eight kinds of broken map file (empty, foreign, header only, PMTiles v2, tile
  data past the end, no tiles, size differing from the manifest, cut short without a size in the
  manifest), missing `@2x` sprite image, missing glyph ranges;
- style composing: one pack, night template, two packs with interleaved layer copies, no style;
  since the second round eight template shapes that must give a `StyleException` (not JSON, not an
  object, `sources` an array, `layers` an object, a layer that is a number or has no id, no
  source for `map.source_name`, no layer drawing it), the always-present OpenStreetMap credit, and
  packs whose tiles the style cannot draw reported as skipped.

All 32 pass (`BUILD SUCCESSFUL`; log `data/android/logs/fix/build-packs-test-rerun.log`). The first round had 12.

## 4–5. Running it

Maps used:

1. **Stand-in** (first round): `tools/fetch_android_sample_pack.py`. It cut box -80.53, 39.71,
   -74.68, 42.30, z0-15, out of the Protomaps daily build `20261115.pmtiles` (tiles 4.15.2, the
   same version the map track pins). It used HTTP range requests and only the `pmtiles` Python
   package, and needed 610 MB of downloads and 16.6 minutes. Result: 223,552 tiles, 581.8 MB.
   The pack also has my hand-written 28-layer day/night style, and Noto Sans glyphs and v4 sprites
   from `protomaps/basemaps-assets` at the commit the map track pins. 593 MB in all.
   Running the go-pmtiles binary for this was blocked by the session's safety classifier, so the
   extract is done in Python instead.
2. **Final**: `tools/fetch_android_map_pack.py` →
   `us-pa`: the map track's `pa-261114-z0-15.pmtiles` (464.0 MB, clipped to the state outline)
   plus `packbuilder/map/style-bundle` (Protomaps light/dark, 73 layers), 465.3 MB as a pack.
   `us-oh` was made the same way from their Ohio build for the two-pack test.
3. **Second round (final verification)**: the same tool, rerun at 14:3x for the map track's
   rebuilt `pa-261114-z0-15.pmtiles` (14:30, 463.8 MB, sha256 `f842948b...`) and their style bundle
   of 14:18 (74 layers): `us-pa` 465.3 MB, `us-oh` 468.2 MB. Plus `test-erie`, an 8.5 MB cut of
   `us-pa` (box -80.3, 41.95, -79.8, 42.25, z0-15) made by `android/scripts/pack_variants.py cut`,
   as the base for the broken-pack test.

Pushing a pack with `android/scripts/push_pack.py` (adb, into a temporary name, then renamed)
took 12-20 s for about 500 MB.

Screenshots, all opened and checked (`data/android/screenshots/`, not in git).

Second round, fixed APK, real `us-pa` from the 14:30 build (`final2/`):

| File | What it shows |
|---|---|
| `final2/01-state-day.png` | whole state on the pack's bounds: Erie, Pittsburgh, Scranton, Allentown, Harrisburg, Lancaster, Philadelphia, main roads, credit |
| `final2/02-erie-z13-day.png`, `final2/03-erie-z16-day.png` | Erie city and street level: street names along the roads, neighbourhoods, LECT and route 5/20 shields, POI icons and names |
| `final2/04-pittsburgh-z12-day.png`, `final2/05-pittsburgh-z16-day.png` | Pittsburgh city and street level (70 % scrim build) |
| `final2/06-philadelphia-z13-day.png`, `final2/07-philadelphia-z13-night-live-switch.png` | Philadelphia by day, then the same running app after `cmd uimode night yes` (no restart) |
| `final2/08-erie-z16-night.png`, `final2/09-state-night.png` | night style |
| `final2/10-pittsburgh-z14-airplane.png` | airplane icon in the status bar, map drawn |
| `final2/11-no-pack-day.png`, `final2/12-no-pack-night.png` | no-pack message, light and dark |
| `final2/13-pittsburgh-z16-day-final-scrim.png`, `final2/14-pittsburgh-z16-night-final-scrim.png`, `final2/15-erie-z16-day-final-scrim.png` | final APK (88 % scrim): the view where the reviewer saw "Convention Center Pittsburgh" printed over the status icons; the icons now sit on an almost plain bar |
| `final2/16-two-packs-pa-oh-border-z12.png` | us-pa + us-oh at Sharon/Farrell: roads continue across the state line, no skipped-pack note |
| `variants-before/`, `variants-after/` | one screenshot per broken-pack variant, reviewed APK and fixed APK (see "Review and fixes") |

The status-bar fix measured on the Pittsburgh z16 view: the darkest pixel of the label text
right of the battery icon is 117 (of 255) in the reviewer's screenshot, 214 with a 70 % scrim and
238 with the final 88 % scrim, so the label is nearly invisible under the icons (one-off
Pillow measurement, not committed).

First round:

| File | What it shows |
|---|---|
| `final/01-state-day.png` | Pennsylvania (clipped state pack), Erie, Pittsburgh, Harrisburg, Philadelphia labels, credit |
| `final/02-erie-z12-day.png` | Erie and Presque Isle, neighbourhoods, route shields 5/290/20/97 |
| `final/03-erie-street-z16-day.png`, `final/05-pittsburgh-street-z16-day.png` | street names, one-way arrows, POI icons and names, buildings |
| `final/04-pittsburgh-z12-day.png`, `final/06-philadelphia-z13-day.png` | city level |
| `final/07-philadelphia-z13-night-live-switch.png` | the same running app after `cmd uimode night yes` (no restart) |
| `final/08-erie-street-z16-night.png`, `final/09-state-night.png` | night style |
| `final/10-pittsburgh-z14-airplane.png` | airplane-mode icon in the status bar, map drawn |
| `final/13-two-packs-pa-oh-border-z12.png`, `final/14-two-packs-overview.png`, `final/15-two-packs-after-travel.png` | us-pa + us-oh installed together: roads (US 62, I-80) continue across the border at Sharon/Masury |
| `final/16-no-pack-with-broken-folder-day.png` | "No map installed", the folder path, and "Skipped broken-test: unreadable manifest.json: ..." |
| `01-no-pack-day.png`, `11-no-pack-night.png` | no-pack message, light and dark |
| `02-*` to `12-*` | the stand-in pack and hand-written style, day and night |
| `final/11-after-panning-opengl.png`, `final/12-after-travel-pan-*.png` | OpenGL build draws the same; position after the travel test |

Credit: "© OpenStreetMap contributors" is drawn by the app (bottom left, above the navigation
bar), taken from the manifests' `attribution`. Since the second round the app adds it itself when
no manifest names OpenStreetMap (an empty attribution left an empty box before). MapLibre's own
attribution button and logo are off. The map is drawn edge to edge; since the second round the
status and navigation bars get a translucent scrim, so map labels no longer print over the
system icons, and the credit box is almost opaque.

**No network, checked three ways** (first round; repeated in the second round with the fixed
APK and the new `us-pa`, with the same result: `data/android/logs/fix/final2-logcat-airplane*.txt`
has 46 lines from the app's process in airplane mode, the only "connect" matches being Android
loading its connectivity library and MapLibre's `connected - false`; `final2-netstats.txt` has
no `mAppUidStatsMap` entry for UID 10150):

1. The merged manifest has no INTERNET permission (above).
2. With airplane mode on, the app log was empty of network activity: 0 matches for
   `internet|socket|SecurityException|UnknownHost|okhttp|http(s):`
   (`data/android/logs/final-logcat-airplane.txt`, 47 lines).
3. With Wi-Fi up and the app started, panned and restarted many times, the kernel's per-UID
   traffic map (`dumpsys netstats detail`, `mAppUidStatsMap`) has **no entry for the app's UID
   10150**, so it sent and received no bytes. Other UIDs (system, 1000, ...) have entries.

MapLibre's ambient cache file `mbgl-offline.db` stayed at 64 KB, so tiles read from PMTiles are not
copied into it.

**Issue found and fixed on the way:** in my hand-written style, Interstate shield numbers were
white on the white `US:I` sprite. They now use dark text by day and light text by night.

**Seen, not fixed (map track's style):** the stock Protomaps dark flavour has low road contrast at
city and state zoom at night (`final/07`, `final/09`). For a driving app this is worth a look.

## 6. Measurements

Indicative only: emulator, other heavy jobs running, and run-to-run variation is large.

### Build (Gradle 9.9.1, AGP 9.5.1, JDK 25, 2 GB heap, 4 workers)

| Build | Time |
|---|---|
| First `:core:packs:test` ever (includes downloading Gradle plugins and dependencies) | 3 min 34 s |
| `clean assembleRelease`, new daemon, no build cache, no configuration cache | **139.4 s** |
| `clean assembleRelease`, warm daemon, no build cache | 62.8 s |
| `clean assembleDebug`, warm daemon, no build cache | 23.8 s |
| `:core:packs:test --rerun-tasks` | 15.4 s |

Timings: `data/android/logs/build-times.txt`. The emulator was running during these.

Second round (after the fixes, `data/android/logs/fix/build-times.txt`; emulator running, the
map track's builds and three other tracks on the machine):

| Build | Time |
|---|---|
| `clean assembleRelease --no-daemon` (fresh JVM), no build cache, no configuration cache | **77.8 s** |
| `clean assembleRelease`, warm daemon, no build cache (two runs) | 49.9 s, 59.6 s |
| `:core:packs:test --rerun-tasks` (32 cases) | 18.3 s |

I did not investigate why the fresh-JVM build was faster than the first round's 139.4 s; the
load from the other jobs varies a lot. Gradle's download and transform caches were already
filled, so it is not a cold-machine figure.

### APK size (universal APKs, all four ABIs)

| | release Vulkan | release OpenGL | debug Vulkan |
|---|---|---|---|
| Total | **50.9 MB** | **43.0 MB** | 62.9 MB |
| `lib/arm64-v8a` | 13.0 MB | 11.0 MB | 13.0 MB |
| `lib/armeabi-v7a` | 9.6 MB | 8.1 MB | 9.6 MB |
| `lib/x86` | 13.4 MB | 11.2 MB | 13.4 MB |
| `lib/x86_64` | 13.6 MB | 11.4 MB | 13.6 MB |
| dex (uncompressed / in APK) | 1.77 / 0.87 MB | 1.78 / 0.88 MB | 32.4 / 12.4 MB |
| everything else | 0.25 MB | 0.25 MB | 0.7 MB |

Second round: the fixed release APK (`data/android/apks/app-release-vulkan-fix.apk`) is the
same size, 50.87 MB, with the same per-ABI figures; dex 1.78 MB (0.88 MB in the APK). 64-bit
libraries still stored uncompressed at 16 KB offsets. `aapt2 dump`: minSdk 26, targetSdk 36,
compileSdk 37, the only permission is AndroidX's own `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

Native code is 97% of the release APK. With minSdk 26 the `.so` files are stored uncompressed
and page-aligned, so the APK is big but nothing is unpacked on install. Every 64-bit library is
**16 KB-aligned**, both the ELF LOAD segments (`0x4000`) and its offset inside the zip, as Google
Play requires for 15+ targets. The 32-bit libraries have 4 KB alignment, which is allowed.

A Play app bundle would deliver one ABI. The arm64 `libmaplibre.so` gzips to 4.55 MB (Vulkan) or
3.84 MB (OpenGL); this is an estimate of the download, not measured with bundletool.

### Cold start to first map frame (7 runs, release build, Erie z13)

Method:

- `adb shell am force-stop`, then `am start -W` with the camera extras (`android/scripts/measure.py startup`);
- "activity drawn" is am's `TotalTime`: from the intent to the activity's first frame, before
  the map has drawn anything;
- "first map frame" and "fully rendered" come from the app's own log, measured from the moment
  the process was forked (`SystemClock.uptimeMillis() - Process.getStartUptimeMillis()`), in
  MapLibre's `onDidFinishRenderingFrame` (first call, and first call with `fully == true`, i.e.
  all visible tiles drawn).

| Build, pack | am TotalTime (median, min-max) | First map frame | Fully rendered |
|---|---|---|---|
| Vulkan, us-pa (final) | 767 ms (602-1322) | **718 ms** (630-964) | **1948 ms** (1651-2390) |
| OpenGL, us-pa | 885 ms (561-946) | 882 ms (646-1083) | 2258 ms (2025-3007) |
| Vulkan, us-pa + us-oh, at the border (5 runs) | 712 ms | 667 ms | 1756 ms |
| Vulkan, stand-in pack with 28-layer style | 659 ms | 580 ms | 1356 ms |
| **Second round:** fixed APK, new `us-pa` (14:30 build, 74-layer style), 7 runs after 1 warm-up run | 781 ms (599-879) | **680 ms** (500-745) | **1574 ms** (1223-1736) |

Cold file cache: right after installing the APK or pushing a pack, the first start reads the
PMTiles directory and tiles from disk and is several times slower (the reproduce reviewer saw
5,061 ms to the first map frame on run 1, then 612-817 ms). `measure.py startup` now makes one
warm-up run first and prints it as "not counted" (`--warmup 1`). In the second-round measurement
the warm-up was not slow (507 ms), because the screenshot session had just read the same tiles.
Second-round log: `data/android/logs/fix/startup-final.txt`.

### Memory (`dumpsys meminfo`, TOTAL PSS)

The emulator reports no GPU memory, so the "Graphics" line is 0. Real devices will add GPU
buffers on top.

| Build | Start at Pittsburgh z14 | After 40 back-and-forth swipes | Fresh start → after 60 swipes travelling ~50 km east |
|---|---|---|---|
| Vulkan, us-pa | 107 MB (native heap 78 MB) | 122 MB (native 96 MB) | 103 → **98 MB** |
| OpenGL, us-pa | 80 MB (native 50 MB) | 80 MB (native 57 MB) | 90 → **67 MB** |
| Vulkan, us-pa + us-oh (crossing OH → PA) | | | 111 → 104 MB |
| **Second round:** fixed APK, new `us-pa` | 128 MB (native 99 MB) | 137 MB (native 110 MB) | continuing from there, after 60 swipes: **101 MB** (native 75 MB) |

The second round starts about 20 MB higher than the first. The style has one more layer and the
tiles were rebuilt, and the run-to-run spread on this loaded emulator is large; I did not look
further. Log: `data/android/logs/fix/pan-memory-final.txt`.

Memory stayed flat while new areas kept loading, so MapLibre's tile cache evicts as expected.

### Frames while panning

MapLibre reports CPU encoding and rendering time per frame in **seconds**; the first version of my
log called them milliseconds, which was fixed. The figures are map frames per one-second window
while drawing; Compose's `gfxinfo` does not see MapLibre's own surface.

| Build | Back-and-forth (40 swipes) | Travelling (60 swipes) |
|---|---|---|
| Vulkan, us-pa | median 13.8 fps, CPU 77 ms/frame | median 23.3 fps, 45 ms/frame |
| OpenGL, us-pa | median 3.9 fps, 321 ms/frame | median 8.9 fps, 118 ms/frame |
| Vulkan, two packs | | median 30.4 fps, 33 ms/frame |
| Vulkan, stand-in style (28 layers) | ~20 fps, ~50 ms/frame | |
| **Second round:** Vulkan, fixed APK, new `us-pa` | median 14.8 fps, CPU 71 ms/frame, worst 213 ms | median 28.8 fps, 35 ms/frame, worst 198 ms |

On this emulator the GPU work goes through gfxstream to the host, and the frame time includes
waiting for that. Vulkan was 2.5-3.5x faster than OpenGL here. These numbers must be redone on a
real phone before any renderer decision; they do not show that either renderer is slow on phones.

## 7. Next step: adding valhalla-mobile and Ferrostar

Coordinates and metadata as published on Maven Central (POM, Gradle module and AAR manifest read on
2026-11-15):

| Artifact | Licence | minSdk | Native code | Brings |
|---|---|---|---|---|
| `io.github.rallista:valhalla-mobile:0.6.4` (bundles Valhalla 3.9.1 per its 0.6.4 release notes) | MIT | 24 | `libvalhalla-wrapper.so` arm64 7.5 MB, v7a 6.0, x86 8.3, x86_64 7.9; 64-bit 16 KB-aligned | moshi 1.15.1, `valhalla-models` / `valhalla-models-config` 0.6.0, `com.stadiamaps:osrm-openapi:0.0.10` (BSD-3), kotlin-stdlib 2.2.10, core-ktx 1.13.1 |
| `com.stadiamaps.ferrostar:core:0.59.0` | BSD-3-Clause | 25 | `libferrostar.so` arm64 2.8 MB, 64-bit 16 KB-aligned | OkHttp BOM **5.3.2**, kotlinx-serialization-json 1.11.0, kotlinx-datetime 0.8.0, coroutines 1.11.0, lifecycle-viewmodel-ktx 2.10.0, appcompat 1.7.1, **JNA 5.18.1 (`@aar`)**; manifest adds ACCESS_COARSE/FINE_LOCATION |
| `com.stadiamaps.ferrostar:ui-formatters`, `ui-shared`, `ui-compose:0.59.0` | BSD-3-Clause | 25 | none | Compose BOM 2026.02.01, material3, activity-compose 1.13.0 |
| `com.stadiamaps.ferrostar:ui-maplibre:0.59.0` | BSD-3-Clause | 25 | none | **`org.maplibre.compose:maplibre-compose-android:0.13.0`** → `org.maplibre.gl:android-sdk:13.0.2`, scalebar plugin 3.0.2, JetBrains Compose foundation 1.10.3 |
| `com.stadiamaps.ferrostar:car-app:0.59.0` | BSD-3-Clause | 25 | none | `androidx.car.app:app:1.7.0` (Android Auto), material 1.13.0 |

How to add them:

1. Add the two versions to the catalog (they are already noted there as comments):
   `valhallaMobile = "0.6.4"`, `ferrostar = "0.59.0"`. Put routing behind a new pure module
   (`:core:routing`, depending on `valhalla-mobile`). Valhalla needs an Android library module,
   not a JVM module, because it is an AAR. The trip state goes into `:core:trip` (Ferrostar `core`).
   Screens only observe them.
2. Start with Ferrostar `core` (and `ui-formatters` / `car-app` when needed). Leave out
   `ui-maplibre` at first. It ties the map to maplibre-compose **0.13.0**, which is pre-1.0 and
   seven minor versions behind its current 0.20.0. The app already draws its map with MapLibre
   directly, and drawing the route line and the puck on it is a small amount of code. If
   `ui-maplibre` is wanted later: with the Vulkan artifact it resolves cleanly (13.0.2 → 13.7.1);
   with the OpenGL artifact add
   `configurations.all { resolutionStrategy.dependencySubstitution { substitute(module("org.maplibre.gl:android-sdk")).using(module("org.maplibre.gl:android-sdk-opengl:13.7.1")) } }`,
   or the build fails with duplicate classes.
3. Conflicts seen in the metadata, all resolved upwards by Gradle. Each needs one test after
   adding:
   - OkHttp: MapLibre 13.7.1 asks for 4.12.0 and Ferrostar's BOM for 5.3.2, so 5.3.2 wins.
     OkHttp 5 says it is binary compatible with 4.x; MapLibre never uses it here anyway (offline).
   - kotlin-stdlib 2.2.10 / 2.3.20 → 2.4.21; Compose BOM 2026.02.01 → 2026.09.00;
     core-ktx 1.13.1 / 1.17.0 → 1.19.1; lifecycle 2.10.0 → 2.11.0;
     kotlinx-serialization 1.11.0 → 1.13.0.
   - **JNA 5.18.1 is dual-licensed LGPL-2.1-or-later OR Apache-2.0** (its POM lists both). It
     can ship under Apache-2.0, but that choice must be written down, because nothing LGPL may ship.
   - Ferrostar core brings **ACCESS_FINE/COARSE_LOCATION** back into the merged manifest; this app
     currently removes them. Navigation needs them: remove the two `tools:node="remove"` lines then.
   - Ferrostar core and OkHttp are network-capable (Ferrostar's own HTTP route adapters).
     Use only a custom local route provider fed by valhalla-mobile, and keep INTERNET removed so
     any slip fails loudly. valhalla-mobile 0.6.x has an HTTP tile getter that is attached only
     when `tile_url` is set: never set it. Its native build has `ENABLE_HTTP OFF`.
   - valhalla-mobile's native dependencies (from its `src/vcpkg.json`): protobuf, Boost,
     abseil, lz4, rapidjson, robin-hood-hashing, unordered-dense. All are permissive, and the data
     tools (GEOS / spatialite) are off. I read the build files, not the binary; the licence list of
     the shipped `.so` is not verified.
4. Size: valhalla-mobile adds about 7.5 MB and Ferrostar about 2.8 MB of arm64 native code, next to
   MapLibre's 13 MB. Per-ABI app bundles become more important.
5. MapLibre's `MapLibre.setConnected(false)` should stay. If maplibre-compose is used, check that it
   does not reset it.

### MapLibre Android points that matter for several packs

- **Several sources, copied layers (implemented and tested on the device).** `StyleComposer` gives
  each pack its own vector source (`protomaps`, `protomaps-us-pa`, ...) and repeats every layer of
  that source once per pack. The copies sit next to each other, so the drawing order stays right:
  all water before all roads. With two packs and the 73-layer Protomaps style the map has about
  145 layers. Start-up, frame rate and memory stayed in the same range as with one pack on the
emulator. The views were not identical, so this is a rough comparison only.
  Each source keeps its own tile pyramid and cache, so memory grows with the number of packs
  visible at once, not with the number installed. The map track clips each state to its outline
  with no buffer, so polygons do not overlap. Things that span the border are cut there and their
  labels can appear on both sides.
  - Low zooms: every state pack also carries its own z0-z7 tiles of its own state only. The
    overview shows the installed states and nothing else (`final/14`). A shared low-zoom pack
    (the map track's `hosted-us-...-z0-7.pmtiles`, 19 MB) as one more source with the state
    sources starting at z8 would fill the gap. That needs a `minzoom` on the state sources,
    which the manifest does not have yet.
- **One merged file** (the map track's `merge_packs.py`): one source, no layer copies, no
  doubled labels, but a re-merge whenever any state updates. That is hard to do on a phone.
  The app can read either: a merged file is just one pack.
- Day/night is a full `setStyle`. It looked instant on the emulator. 13.7's `global-state`
  expressions could switch colours without reloading, if the style were written for it.
- `pmtiles://file://` plus `file://` glyphs and sprites work from the app's external files folder
  without any storage permission. Font stacks with spaces work.
- The Vulkan artifact's required `uses-feature` affects Play distribution (see "Versions").

## Review and fixes (second round, 2026-11-15 afternoon)

Two independent reviews ("reproduce" and "rules") read the first round. Both found it sound
with fixes. I checked every major finding myself before changing code: I ran the APK that was
reviewed (`data/android/apks/app-release-vulkan.apk`) on the emulator against a set of damaged
packs, then the fixed APK against the same set.

**How the findings were checked.** `android/scripts/pack_variants.py` (new, committed) cuts a small
Erie pack (8.5 MB, 2,472 tiles, z0-15) out of the real `us-pa` pack, makes 23 variants of it,
installs each one alone with adb, starts the app, waits 6 s, and records whether the app crashed
(`FATAL EXCEPTION` for its process), whether it is alive, the text on screen (`uiautomator dump`
of the Compose UI), and a screenshot. Results: `data/android/screenshots/variants-before/` and
`variants-after/` (`results.json` plus one PNG per variant), logs
`data/android/logs/fix/variants-before*.txt` and `variants-after.txt`.

| Variant | Reviewed APK | Fixed APK |
|---|---|---|
| `good` | map | map |
| `bounds-lat-95`, `bounds-south-north`, `bounds-wrap` | **crash** (`IllegalArgumentException: latitude must be between -90 and 90` / `latNorth cannot be less than latSouth`), again on every start | message naming the bounds |
| `bounds-zero` (`[0,0,0,0]`) | plain grey map with credit only | message |
| `style-sources-array`, `style-layer-number` | **crash** (`Element class ... is not a JsonObject`) | message naming the style file and the problem |
| `style-layers-object`, `style-empty`, `source-name-unknown` | plain grey map with credit only | message |
| `style-layer-no-id` | map drawn without its land layer (MapLibre dropped the layer silently) | message |
| `style-not-json` | message (already handled) | message |
| `zerolen`, `garbage`, `garbage-same-size`, `header-only` | plain grey map with credit only | message ("file has 0 bytes, the manifest says ... (copy not finished?)", "not a PMTiles file") |
| `truncated`, `truncated-no-size` (first third of the file) | **map looked complete** at the opening zoom: the low zooms sit at the start of the file; deeper zooms would have failed later | message |
| `no-sprite-png` | map without route shields and icons | message "missing file sprites/light.png" |
| `no-glyphs` (font folders removed) | map without any labels | message "no glyph files: expected e.g. glyphs/<font>/0-255.pbf" |
| `no-attribution` (empty `attribution`) | map with an empty credit box | map with "© OpenStreetMap contributors" |
| `pair-other-schema`, `pair-broken-file` (a good pack plus one the app cannot use) | good pack drawn, the other left out without a word | good pack drawn, plus a note at the top: "Skipped b-empty: map.pmtiles: file has 0 bytes ..." |

Reviewed APK: 2 of 23 as expected (5 crashes, 16 blank, incomplete or silent). Fixed APK: 23 of 23,
no crash. (Two variants of the first "before" run had to be redone: my screen reader missed text
quoted with `'`, and `adb push` drops empty folders, so `no-glyphs` now keeps a licence file in
`glyphs/`. The table uses the rerun, `variants-before-rerun.txt`.)

### Each finding

| # | Finding (review lens) | Severity | Outcome |
|---|---|---|---|
| 1 | App crashes on every launch when a pack's bounds are out of range (rules) | major | **Fixed.** Reproduced (3 crash variants above). `PackScanner` now rejects bounds that are not four finite numbers with -180 <= west < east <= 180 and -90 <= south < north <= 90, with the reason on screen; `[0,0,0,0]` is rejected too. `PackMap` also catches `IllegalArgumentException` around both camera moves (bounds and the test-extra camera) and keeps the current camera. 7 test cases. |
| 2 | Style template of the wrong JSON shape crashes the app instead of a `StyleException` (rules) | major | **Fixed.** Reproduced (2 crash variants). `StyleComposer` checks the shape with safe casts and names the problem (`"sources" must be an object`, `layer #73 is not an object`, `layer #1 has no id`, ...); anything unexpected left inside it is turned into a `StyleException`. `MapViewModel` now also catches any other exception (except cancellation) and shows "The map could not be shown" with the reason, so no pack can crash the app at start. 9 test cases. |
| 3 | Empty, foreign, truncated or header-only PMTiles file gives a blank grey map with no message (rules) | major | **Fixed.** Reproduced (6 variants; two cut-off files even looked fine at first). The scanner reads the 127-byte PMTiles header: magic and version 3, and the root directory, metadata, leaf directories and tile data it names must all lie inside the file; the root directory and tile data must not be empty. When the manifest gives `map.bytes`, the file size must match (an interrupted `adb push` is named as "copy not finished?"). `sha256` is still not checked on the device (hashing 465 MB on every resume is too slow; a deliberate verify step can do it later). Not done: listening to MapLibre's own "failed to load source" errors, so a file that is damaged inside its tile data would still draw blank tiles. One test with 8 broken files. |
| 4 | Empty attribution leaves an empty credit box (rules) | minor | **Fixed.** `StyleComposer.credits()` drops blank lines and puts "© OpenStreetMap contributors" first when no line names OpenStreetMap: every pack is built from OSM, so the credit is the project's rule, not the pack's. Device: `no-attribution` shows the credit. |
| 5 | Several broken-but-parseable packs give a blank map without a message; packs of another schema or source left out silently (rules) | minor | **Fixed.** A template with no source named `map.source_name`, no layers array, or no layer drawing that source is a `StyleException`. Packs whose `schema`/`source_name` differ from the style pack come back as `ComposedMap.skipped` and are shown in a note at the top of the map, as are scan problems while other packs are drawn (before, scan problems were only shown when no pack at all could be used). `packIds` now lists only the packs on the map. |
| 6 | Scanner checks sprite `.json` but not `.png` or `@2x`, and only the glyph folder (rules) | minor | **Fixed.** For each sprite name all four files are required (`.json`, `.png`, `@2x.json`, `@2x.png`; MapLibre loads `@2x` on any screen above 160 dpi), and at least one font folder must have `0-255.pbf` under the glyph template. Fonts the style names but the pack lacks are not checked (that needs the style's `text-font` values). |
| 7 | Unit tests do not cover these failure modes (rules) | minor | **Fixed.** 12 → 20 tests, 32 cases (see "Unit tests"). All pass. |
| 8 | Map drawn under the status and navigation bars; labels collide with system icons; labels show through the credit (reproduce) | minor | **Fixed.** Reproduced from the reviewer's `05-pgh-z16-day.png` ("Convention Center Pittsburgh" over the signal and battery icons). The map stays edge to edge (normal for a map app), but the status and navigation bars get a scrim in the theme's colour, 88 % opaque (a first try at 70 % still let the label show through), and the credit box is now 95 % opaque. Checked in `final2/13-pittsburgh-z16-day-final-scrim.png`, `final2/14-...-night-final-scrim.png` and `final2/15-erie-z16-day-final-scrim.png`: the label's darkest pixel under the icons went from 117 to 238 (of 255). Not done: MapLibre content padding, so a later "centre on my position" would still centre on the full screen, not the visible part. |
| 9 | README says `json.load` works, but on Windows the `©` is garbled without an encoding (reproduce) | minor | **Fixed** in `android/README.md`: the manifest is always UTF-8 without BOM; read it with `encoding="utf-8"`. The committed scripts already pass the encoding (checked: `push_pack.py`, `measure.py`, `fetch_android_map_pack.py`). |
| 10 | Shared test device: fixed application id and device-wide toggles collide with other testers (reproduce) | minor | **Fixed.** `measure.py screenshot` refuses to save when another app is in front before or after the capture. `measure.py`, `push_pack.py` and `pack_variants.py` take `BEAMLINE_APPLICATION_ID` for a second install built with `-Pbeamline.applicationId=...` (the build already accepted the `-P` override; now documented). The README says that night mode and airplane mode are device-wide, and to use `ANDROID_SERIAL` with several devices. |
| 11 | README `avdmanager` step incomplete (prompt, hand edit of `config.ini`) (reproduce) | minor | **Fixed.** The README now shows `echo no \| avdmanager.bat create avd ...` and a loop that sets `hw.ramSize=3072`, `hw.gpu.enabled=yes`, `hw.gpu.mode=host`. Tested in Git Bash on a throwaway AVD (`beamline_readme_check`, deleted afterwards): the defaults were `2G`/`no`/`auto` and the loop set all three. Git Bash needs the `.bat` ending, now written. |
| 12 | First cold-start run is a large outlier the report's range does not mention (reproduce) | minor | **Fixed.** `measure.py startup` now makes one warm-up run first (`--warmup 1`, printed as "not counted"): right after an install or a pack push the file cache is cold. Section 6 now explains this. The first round's 630-964 ms range has no such outlier (its runs evidently started with a warm cache), so its figures stand. |

Nothing was disputed: every finding held up when I checked it.

### Other changes in this round

- `fetch_android_map_pack.py` was rerun for the map track's new builds: `us-pa` from
  `pa-261114-z0-15.pmtiles` rebuilt at 14:30 (463.8 MB; bounds now -80.52 to -74.69 W-E, 39.66 to
  42.52 S-N, from the file's header) and `us-oh`, both with the style bundle of 14:18 (74 layers).
- `PackScanner` turns any exception while reading one folder into a problem for that folder,
  so one unreadable folder cannot stop the scan.

## Decisions for the owner

1. **Vulkan or OpenGL MapLibre build.** Vulkan matches Ferrostar's map UI and was faster on the
   emulator, but its manifest makes Vulkan 1.0.3 **required**, so Play hides the app on devices
   without it. OpenGL has no such filter and is 8 MB smaller. Needs a real-phone test (switch:
   `-Pbeamline.mapRenderer=opengl`).
2. **compileSdk 37** (current AndroidX) or stay at 36 with older AndroidX. targetSdk is 36 either way.
3. **Android CLI side effects**: keep the 325 MB CLI runtime in `~/.android/` (Google's
   cmdline-tools now need it), and decide whether to opt out of its metrics permanently
   (`~/.androidrc` with `--no-metrics`). The first two invocations probably sent usage metrics.
4. **Where the style lives**: inside each pack (my manifest, as tested) or shipped in the APK (map
   track's suggestion). Both can coexist.
5. **JNA** (comes with Ferrostar): record that it is used under Apache-2.0, not LGPL.
6. Release signing: the release build is signed with the debug key for local tests only.

## Not verified / open

- No real phone was used. All performance figures are from an emulator on a loaded machine.
- No Play app bundle or bundletool size check; per-ABI download sizes are gzip estimates.
- A third-party licence screen in the app is not built yet. MapLibre's BSD-2-Clause and FreeType
  licence (used inside `libmaplibre.so`) need their notices reproduced in a binary distribution.
- valhalla-mobile and Ferrostar were not added or run; their facts above come from published
  metadata and AARs only.
- The 13.5.x-only Vulkan fixes were not tested against 13.7.1.
- Second round, left open on purpose:
  - The pack check reads the PMTiles header and compares the size; it does not hash the file
    (`sha256` in the manifest) and does not walk the directories. A file that is damaged inside its
    tile data, at the right size, still draws blank or missing tiles without a message. The app
    does not listen to MapLibre's "failed to load source/sprite/glyphs" errors yet (they appear
    only in logcat as `Mbgl` errors).
  - Glyphs: only "at least one font with `0-255.pbf`" is checked, not every font and range the
    style names.
  - Bounds must have west < east, so a pack crossing the antimeridian (Alaska's Aleutians) must
    give -180..180 as its west and east.
  - The map is still drawn under the system bars (with a scrim); MapLibre content padding for
    the bars is not set, which a later "centre on my position" will need.
  - The broken-pack test (`pack_variants.py run`) takes about 40-60 s per variant on this
    emulator, mostly `uiautomator dump`; 23 variants take about 20 minutes.

## Licences of what was added

Ships in the app. Licences come from the published POMs, and for MapLibre also from `LICENSE.md` and
the third-party lists in its repository at tag `android-v13.7.1`. A search of those files found no
GPL, LGPL or AGPL component.

| Dependency | Licence | Ships |
|---|---|---|
| MapLibre Native Android `org.maplibre.gl:android-sdk` / `-opengl` 13.7.1 | BSD-2-Clause; the bundled native code includes components under ISC, BSL-1.0, MIT, BSD, Apache-2.0, FreeType License, HarfBuzz (MIT-style) | yes |
| `org.maplibre.gl:android-sdk-geojson` / `android-sdk-turf` 6.0.1 | POM: Apache-2.0; repository LICENSE: MIT (both permissive; the mismatch is upstream's) | yes |
| `org.maplibre.gl:maplibre-android-gestures` 0.0.4 | BSD-2-Clause | yes |
| OkHttp 4.12.0, Okio 3.6.0, Timber 5.0.1, Gson 2.10.1 (via MapLibre) | Apache-2.0 | yes |
| AndroidX (core, activity, lifecycle, Compose 1.12.1, Material3 1.4.0, ...) | Apache-2.0 | yes |
| Kotlin stdlib 2.4.21, kotlinx-coroutines 1.11.0, kotlinx-serialization 1.13.0 | Apache-2.0 | yes |
| JetBrains annotations, JSpecify, Guava listenablefuture | Apache-2.0 | yes |
| JUnit Jupiter 6.1.3 | EPL-2.0 | no (tests) |
| AGP 9.5.1, Kotlin Gradle plugin 2.4.21, Gradle 9.9.1, foojay-resolver-convention 1.0.0 | Apache-2.0 | no (build) |
| Temurin 25 (runs the build) | GPL-2.0 with Classpath Exception | no (build) |
| Android SDK, emulator, system image, Android CLI | Android SDK License Agreement | no |
| `pmtiles` (Python) 3.8.1 | BSD-3-Clause | no (tool) |

Pack contents (on the device, not in the APK): OpenStreetMap data (ODbL 1.0, credited on screen);
Noto Sans glyphs (SIL OFL 1.1); Protomaps v4 sprites (MIT, from tangrams/icons); the map track's
style (`@protomaps/basemaps`, BSD-3-Clause code, CC0 design); my sample styles (MIT, this repo).

## How to repeat

From the repo root on Windows (Git Bash or PowerShell; Python 3.12, a JDK 17+ on PATH):

```
python tools/fetch_android_sdk.py --accept-licenses
echo sdk.dir=<your SDK path with forward slashes> > android/local.properties
cd android && ./gradlew :core:packs:test assembleRelease && cd ..
# emulator: see android/README.md "Run" (avdmanager create avd ..., emulator -avd ...)
adb install -r android/app/build/outputs/apk/release/app-release.apk
adb shell am start -n io.github.spysyweeb.beamline/io.github.spysyweeb.beamline.app.MainActivity
python tools/fetch_android_map_pack.py                      # needs the map track's PA build
python android/scripts/push_pack.py data/android/packs/us-pa
python android/scripts/measure.py screenshot data/android/screenshots/erie.png --lat 42.1292 --lon -80.0851 --zoom 16
python android/scripts/measure.py startup --runs 7 --warmup 1 --lat 42.121 --lon -80.082 --zoom 13
python android/scripts/measure.py pan --swipes 60 --pattern travel
python android/scripts/measure.py memory
adb shell cmd uimode night yes                               # night style
adb shell cmd connectivity airplane-mode enable              # offline check
adb shell dumpsys netstats detail                            # look for the app's uid in mAppUidStatsMap
adb shell cmd uimode night no; adb shell cmd connectivity airplane-mode disable
# second round: broken-pack test (deletes the packs on the device; about 20 minutes)
.venv/android/Scripts/python android/scripts/pack_variants.py cut data/android/packs/us-pa data/android/packs/test-erie
python android/scripts/pack_variants.py make data/android/packs/test-erie data/android/variants
python android/scripts/pack_variants.py run data/android/variants --shots data/android/screenshots/variants-after
#   for the "before" column: adb install -r <the reviewed APK>, run again with --shots .../variants-before
python tools/fetch_android_map_pack.py --state oh && python android/scripts/push_pack.py data/android/packs/us-oh   # two packs
adb emu kill
```

The emulator commands, including creating the AVD with 3 GB and the host GPU, are in
`android/README.md` ("Run").

Without the map track's build, make the stand-in pack instead: see `android/README.md`.
