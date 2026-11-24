# Experiment 11: Beamline on a real phone (Pixel 9)

Date: 2026-11-15/16 (about 21:00 to 01:05, this machine's clock; after a review, checks and
repeats on 2026-11-16 from 01:25 to 03:00, section 16). Phone: **Google Pixel 9,
Android 17 (API 37, build CP3A.261005.005), Tensor G4, Mali-G715 GPU**, the owner's own phone,
over USB. Machine: the shared Windows PC; its load does not affect phone numbers (only the adb
round trips). No Gradle and no emulator were run: every APK and archive came from the phone kit
(`data/android/phone-kit.json`, hashes checked), except the second Pennsylvania archive built here
(section 7).

Until now every number of this project came from an emulator on a busy PC (report 08). This
report puts the phone's numbers beside them, with the same methods (table in section 10).

## Result in short

- **The phone is many times faster than the emulator (6-8 x for the first map frame, 5-25 x for
  panning), and steady.** Cold start at Erie z13:
  first map frame **0.17 s** (emulator 0.99-1.38 s), fully rendered **0.32-0.50 s** (emulator
  2.0-3.3 s). Panning downtown Pittsburgh at z14, the emulator's worst case at 7.8-10.3 frames/s,
  runs at **111-115 frames/s** with 2-6 % late frames. Repeated pans agree closely: over 3-5
  runs a view's frame rate varies by at most about 3 % and its late share by at most about 3
  points (section 16.2). Zoom gestures vary more, most of all at the border view with Vulkan.
- **Above 60 Hz is confirmed.** With "Screen rate" the display's refresh period was 8.33 ms
  (120 Hz) in 97 % of SurfaceFlinger's samples while the map moved, and the map's own surface
  presented frames 8.32 ms apart (median). "60" and "30" are honoured too: 30 even puts the
  whole screen at 30 Hz while the map moves. The view system's frame statistics counted 0
  frames in all 318 gesture runs: the map draws on its own surface, which only SurfaceFlinger
  sees.
- **One view is slow, and only with the Vulkan build:** the PA/NY border at z9.5 (the US overview
  under and around the state) runs at **80 frames/s with 26 % late frames** (45 frames/s at the
  60 setting); its slowest frame while panning took 48-72 ms. **The OpenGL build draws it at
  116 frames/s, 3 % late.** Five runs per renderer, day and night, confirm this (section 16.2).
  At most other views Vulkan has fewer late frames (mostly under 2 %, against OpenGL's steady
  2-8 %; Pittsburgh's back-and-forth pan is the exception, 5 % against 2 %), and OpenGL reaches
  "fully rendered" sooner (0.32 against 0.50 s): while a view loads after a start, Vulkan's
  slowest frame takes 0.12-0.23 s, OpenGL's under 0.05 s. The renderer choice is a decision
  (section 13).
- **No stalls are hidden by the method**: of 1,532 present intervals over 150 ms (which the frame
  rates leave out as pauses), 1,531 began after the camera had stopped; one of 166 ms is unclear
  (section 16.1).
- **Installing Pennsylvania through the picker works and checks**: 1.25 GB in **11.8-15.1 s**
  (4 runs), SHA-256 of every part checked, "checked at install". An archive pushed with adb is
  hidden by the picker until the media index is told about it (section 3).
- **Memory**: 254-298 MB TOTAL PSS, of which about 170 MB is graphics memory (which the emulator
  did not report); without it 84-125 MB, the emulator's order. Three idle minutes gave nothing
  back; nothing grew without bound. Readings soon after a start move by about 10 % between
  sessions (20 s after start, without graphics: 92-110 MB over 28 readings; section 16.4).
- **The z0-14 map**: archive 1,033.9 MB instead of 1,252.9 MB (the map part 245 instead of
  464 MB), installs in 8.7-12.8 s, pans and zooms the same, uses about 5 MB more memory just
  after a start (checked z0-15, z0-14, z0-15 in one session, section 16.4). At
  z15-17 downtown Erie and Pittsburgh look nearly the same: buildings, small streets, footpaths
  and one-way arrows are all there. Lost: a few small features only the z15 data had and some
  roundness of curves at z17; the bench icons are gone because the z0-14 build also drops some
  point kinds on purpose (section 7).
- **Location** (numbers only): the puck appeared at once from Android's last known position, and
  centring on it showed it. Indoors, new fixes came **every 20 s at a reported 100 m**; in 3 minutes,
  one fix of 20 m and none better; no satellite fix. So the puck **blinks between blue and grey
  every 10 s** (the app greys a fix older than 10 s). The emulator's one fix per second hid this.
- **Left on the phone** (checked again after the review work): the plain release of Beamline with
  Pennsylvania (z0-15, checked at install), settings at their defaults, location not granted, no
  test build, `/sdcard/Download/beamline-test/` deleted, "stay awake" back to off (section 11).
- **Review**: five minor findings, all checked; the scripts were corrected, the doubtful
  numbers repeated, and three statements of the first version were wrong or too strong (the
  0.2 s frames, the run-to-run spread, the location pixel counts). Section 16.

## 1. How it was measured

Everything ran from this machine with adb, against the phone only (`adb -s <serial>` in every
call; the serial is not written here). The scripts are new, in `android/scripts/phone/`; the
emulator scripts in `android/scripts/` are unchanged.

| Script | What it does |
|---|---|
| `phone.py` | the phone counterpart of `measure.py` + `device_ui.py`: `device`, `settings` (taps on the app's own Settings screen), `install` (States > Add a state > the system file picker), `startup`, `pan` (pan, travel and zoom gestures), `memory`, `shot` |
| `run_sets.py` | the sets of this report: `memory`, `matrix` (frame rates x day/night x views), `shots` (z15-17 at Erie and Pittsburgh) |
| `make_z14_archive.py` | runs `tools/fetch_android_map_pack.py` unchanged with the z0-14 map, its output folders pointed away from the kit's own archive |
| `compare_sheet.py` | side-by-side sheets of the z0-15 and z0-14 screenshots (Pillow from `.venv/map`) |
| `location_check.py` | time to fix, reported accuracy, update rate, puck seen; writes only under `data/phone/private/`, prints numbers only |
| `summarize.py` | the tables of this report, from `data/phone/results/*.jsonl` |

**The same methods as on the emulator**, so the numbers compare:

- **Cold start:** `am force-stop`, then `am start -W` with the camera extras: TotalTime, and the
  app's own log lines "first map frame N ms" and "first fully rendered map frame N ms" (counted
  from the process fork). One warm-up run is not counted.
- **Panning:** the swipes of `measure.py pan` (600 ms each, 0.3 s apart; back and forth, or always
  towards the east for the "travelling" pan), after a restart at the view and 8 s of loading.
- **Memory:** `dumpsys meminfo` of the app (TOTAL PSS, Graphics, native heap).

**Added for the phone:**

- **Zooming:** a one-finger zoom (a tap followed at once by a 500 px, 800 ms swipe: double tap and
  drag), alternately down (in by about one zoom level) and up (out again), 8 gestures per run.
  adb cannot pinch.
- **The map's own surface.** The map draws on its own `SurfaceView`, which the view system's frame
  statistics do not see: `dumpsys gfxinfo` counted **0 frames in every one of the 318
  gesture runs**, while the map's surface presented 444,483 frames in them. So `pan` reads
  SurfaceFlinger instead: `dumpsys SurfaceFlinger --latency "<the BLAST layer of our SurfaceView>"` returns the last 128 frames
  of that one surface (desired, actual present and ready times, in ns); it is polled every
  0.35 s and merged by present time (no frame was missed between polls in any run). From the
  present-to-present intervals:
  - **frames per second while drawing**: intervals over 150 ms are **left out** of this rate and
    of the late share, as pauses between gestures. That this hides no stalls was checked after
    the review (section 16.1): each of the 1,109 intervals over 150 ms in the report's runs began
    after the camera had stopped (the app logs "camera idle"), none while the map moved; since
    the review `phone.py` reports them on their own (`long_intervals`, `while_moving`);
  - **late frames**: the share of intervals longer than 1.5 x the frame time the setting asks for
    (8.33 ms at Screen rate, which is 120 Hz here; 16.7 ms at 60; 33.3 ms at 30).
- **The display's refresh rate during the run:** the first line of every `--latency` answer is
  SurfaceFlinger's vsync period at that moment (8.33 ms = 120 Hz, 16.67 ms = 60 Hz, 33.33 ms =
  30 Hz). This, not the display "mode", tells the rate the screen actually ran at (section 4.1).
- The app's own one-second windows ("frames: n=… cpu avg=…", the numbers report 08 gives) are kept:
  "ML fps" and MapLibre's CPU time per frame. ML fps includes the pauses between gestures, so it
  reads a little lower than the surface rate. The first window of a run also holds the view's
  load frames (it is logged at the first gesture frame); the slowest frames of the report, 0.12 to
  0.23 s, were in that window, at load, not during gestures (section 16.1). Since the review,
  `phone.py pan` closes that window before the gestures (a camera move of a few metres with the
  test extras, then 1.5 s) and reports the load window's slowest frame apart.

**Rules kept on the owner's phone.** Only packages starting with `io.github.spysyweeb.beamline`
were installed or replaced; files were pushed only to `/sdcard/Download/beamline-test/` (and
the UI dumps of `uiautomator` went there too, not to `/sdcard/`); logcat was read only for our
own process (`--pid`) and never cleared; every tap and swipe was sent in the same shell command
as a check that our app (or the file picker it opened) had the focus. Two slips, both logged in
`data/phone/logs/worklog.txt`: while trying out the zoom gesture I sent one tap pair without
that check (the app was in front, showing the Pittsburgh map); and during the final privacy check
one command printed a position line from `data/phone/private/` to this session's console. The
position is in no file outside `data/phone/private/` and not in this report. The only system
setting changed was "stay awake while charging" (`stay_on_while_plugged_in` 0 → 7, put back to 0
at the end). The system dark mode was not touched; Day and Night were set in the app. Every map
measurement and screenshot was made with the location permission **not** granted (section 9,
item 8) and the camera placed at a public test location by the test extras.

## 2. The phone (task 1)

| What | Value | How |
|---|---|---|
| Model, Android | Pixel 9, Android 17 (API 37), build CP3A.261005.005, security patch 2026-10-05 | `getprop` |
| Chip, GPU drivers | Tensor G4; EGL and Vulkan drivers `mali` (Mali-G715, as MapLibre's OpenGL backend logs it) | `getprop ro.soc.model`, `ro.hardware.egl/vulkan` |
| ABIs | arm64-v8a only | `ro.product.cpu.abilist` |
| Page size | **4 KB** (4096 bytes) | `getconf PAGE_SIZE` |
| Screen | 1080 x 2424, 420 dpi (2.625 px per dp) | `wm size`, `wm density` |
| Display modes | **two: 60 Hz (mode 1) and 120 Hz (mode 2)**, same resolution; render rates offered: 120, 60, 40, 30, 24, 20 Hz | `dumpsys display` (`supportedModes`, `supportedRefreshRates`) |
| Refresh settings | Smooth Display on (`peak_refresh_rate` = Infinity), no minimum; battery saver off (`low_power` 0) | `settings get` (read only) |
| Idle refresh | the display sits in the 120 Hz **mode** but renders at **60 Hz** when nothing moves (`renderFrameRate 60.0`, SurfaceFlinger period 16.67 ms) | `dumpsys display`, `dumpsys SurfaceFlinger --latency` |
| Free storage | 11 GB of 109 GB at the start (91 % used); 7.7 GB at the lowest (two archives in Downloads and one pack installed) | `df -h /data` |
| System theme | dark (the app's "Automatic" map style therefore opened with the night map) | first start's log: "style loaded … (night)" |

**Which renderer each build really uses.** The app logs its build flag ("MapLibre renderer:
vulkan|opengl"), which only says how it was built. The phone shows what runs:

| Build | Seen at run time | Threads of the process (`ps -T`) |
|---|---|---|
| `beamline-release-testextras-vulkan.apk` (the plain `beamline-release.apk` is built with the same renderer flag and logs "vulkan") | no EGL at all from MapLibre: no `EGLConfigChooser` line, no OpenGL "GPU Identifier" line | one `RenderThread 18…` (MapLibre's), `mali-compiler`, 2 x `mali-event-hand`, 2 x `mali-mem-purge` |
| `beamline-release-testextras-opengl.apk` | `Mbgl-EGLConfigChooser: In emulator: false` and `Mbgl: {RenderThread 18}[General]: GPU Identifier: Mali-G715`: MapLibre's OpenGL ES backend choosing an EGL config and reading `GL_RENDERER` | two `RenderThread 18…`, `mali-cpu-comman` (the Mali GLES driver's command thread), 2 x `mali-event-hand`, 2 x `mali-mem-purge` |

The "vulkan: searching for layers" lines appear in **both** builds and come from thread
`RenderThread` (HWUI, Android's own view renderer, which uses Vulkan on this phone), so they
prove nothing about the map. `/proc/<pid>/maps` is not readable from adb on this phone. So the
proof is indirect but consistent: only the OpenGL build makes MapLibre's EGL calls.

## 3. Adding Pennsylvania through the app's own flow (task 2)

The kit's archive `us-pa-2026-11-14.tar` (1,252,935,680 bytes, SHA-256 `da0ed2e5…` as in the
kit) was pushed to `/sdcard/Download/beamline-test/`: **46.9 MB/s, 25.5 s**; its SHA-256 on the
phone was the same. Then: States > "Add a state from a pack file (.tar)" > the system file
picker (`com.google.android.documentsui`) > Show roots > Downloads > `beamline-test` > the file,
driven by `phone.py install` (taps found with `uiautomator`, each one guarded).

**First problem the emulator did not show:** in the picker the folder read **"No items"**. The
file pushed with adb was in Android's media index with `is_pending=1` and no size (`content query`
on our file only), and the picker hides pending files. `content call --uri content://media
--method scan_file --arg <our file>` set it to `is_pending=0`, size 1,252,935,680, and the file
appeared. On the emulator (API 36) the same push showed at once. A user who downloads a pack with
a browser does not meet this; the kit's instructions for adb do (section 9).

| Run | Archive | Map | First MB copied → Done | Picked → Done | Checks |
|---|---|---|---|---|---|
| 1 | `us-pa-2026-11-14.tar`, 1,252.9 MB | z0-15 | **14.84 s** | 16.0 s | Done; SHA-256 of all three parts checked while copying; `install-record.json`: `"verified": "sha256", "source": "archive"`; States: "Map · Routing · Search · checked at install" (screenshot `01-states-pa-installed.png`) |
| 2 | the same, replacing the z0-14 pack | z0-15 | **15.06 s** | 15.9 s | Done, checked; installed manifest `max_zoom 15` |
| 3 | the same, replacing itself | z0-15 | **13.72 s** | 13.8 s | Done, checked |
| 1 | `us-pa-z14-2026-11-14.tar`, 1,033.9 MB (section 7) | z0-14 | **11.42 s** | 11.8 s | Done, checked; installed manifest `max_zoom 14` |
| 2 | the same, replacing itself | z0-14 | **12.79 s** | 13.5 s | Done, checked |
| 3 (review) | `us-pa-z14-2026-11-14.tar`, replacing z0-15 | z0-14 | **8.68 s** | 9.7 s | Done |
| 4 (review) | `us-pa-2026-11-14.tar`, replacing z0-14 | z0-15 | **11.79 s** | 12.8 s | Done; `install-record.json` `"verified": "sha256"`, manifest `max_zoom 15`, map part 463,787,527 bytes (this is the pack left on the phone) |

That is about **81-119 MB/s copied and hashed** (1.25 GB in 11.8-15.1 s; 1.03 GB in 8.7-12.8 s;
the review's session was the fastest for both),
the same order as the emulator's 85-100 MB/s on its virtual disk (report 08: 14.6 s, then 10.4 s).
Replacing an installed copy cost no more than a first install. With the time to open the picker
and navigate it (by script) the whole flow took 19-36 s. Free storage needed: the archive in
Downloads plus the installed copy (2.5 GB for Pennsylvania), plus the staging copy while
replacing.

## 4. Cold start (task 3)

`phone.py startup`, Erie z13 (`--lat 42.121 --lon -80.082 --zoom 13`, report 08's view), PA
installed, location not allowed (the puck does not start). Milliseconds, median (min-max).

| Build, settings | Runs | Activity drawn (am TotalTime) | First map frame | Fully rendered |
|---|---|---|---|---|
| Vulkan, Day, Screen rate | 7 | **115** (108-128) | **168** (156-185) | **499** (478-510) |
| Vulkan, Day, Screen rate, lower 48 z3.3 | 5 | 111 (102-122) | 161 (158-181) | 384 (377-409) |
| Vulkan, Day, Screen rate, second set 3.5 h later | 7 | 108 (107-122) | 169 (154-179) | 498 (481-528) |
| **OpenGL**, Day, Screen rate | 7 | 114 (112-126) | 163 (145-183) | **321** (299-353) |
| OpenGL, Day, Screen rate, lower 48 z3.3 | 5 | 112 (110-119) | 151 (137-154) | 226 (215-227) |
| Vulkan, Night, 30 fps (the settings the matrix left) | 7 | 115 (109-123) | 185 (167-205) | 515 (498-542) |
| OpenGL, Night, 30 fps (the same) | 7 | 112 (104-124) | 160 (151-175) | 356 (341-401) |
| Vulkan, Day, Screen rate, **z0-14 map** (section 7) | 7 | 111 (103-119) | 156 (145-167) | 480 (464-498) |

The phone starts the app **6-8 times faster** than the emulator did: first map frame 0.15-0.19 s
against medians of 0.99-1.38 s, fully rendered 0.32-0.52 s against 2.0-3.3 s (report 08,
section 9). Two sets 3.5 hours apart gave the same numbers (168/499 and 169/498 ms). **OpenGL
reaches "fully rendered" about 0.18 s sooner** than Vulkan (321 against 498 ms at Erie, 226
against 384 ms for the lower 48); the first frame is the same. The first-ever start after the
install, before any warm-up and with no pack, took 288 ms to the activity and 429 ms to the
first map frame.

## 5. Panning and zooming (task 4)

Pennsylvania only (the emulator runs of report 08 had Ohio too: 214 style layers there, 127
here). Views: downtown Pittsburgh z14 (back-and-forth pan and one-finger zoom), a travelling pan
east across Pittsburgh from there (30 swipes, about 0.4 degrees of longitude), downtown Erie z16,
the PA/NY border z9.5 (`42.0, -77.0`, the overview under and around PA), Rochester NY z14 (no
state installed: plain background, by design), the whole lower 48 at z2.3 (`39.0, -96.5`). Each
view with Frame rate at Screen rate, 60 and 30, Day and Night, with the Vulkan and the OpenGL
build: **2 runs each** (one pass in each order: Vulkan, OpenGL, OpenGL, Vulkan), 20 swipes (30 for
the travelling pan) or 8 zoom gestures per run: 264 runs in this matrix, 318 gesture runs in
the whole report. One run stopped itself after 9 of 20 swipes because another window took the focus (OpenGL, 30, Night, border;
excluded below; whose window it was is outside what this work may read). **Two runs per cell is
thin**: after the review, the cells that drive the renderer decision (Pittsburgh z14 pan, border
z9.5 pan and zoom, Rochester z14 pan; Screen rate and 60; day and night; both renderers) were
run 3 more times each (96 runs); they agree with the tables below (section 16.2).

### 5.1 Does the phone really draw above 60 Hz with "Screen rate"? Yes.

- The app asks for `max 120 fps, surface 120.00001 Hz` (its log) and the Settings screen says
  "Asks for this screen's fastest rate (120 Hz)" (screenshot `02-settings-day.png`).
- **SurfaceFlinger's vsync period during the gestures was 8.33 ms (120 Hz) in 97 % of the
  samples** at Screen rate, with both renderers (the other 3 % were taken just before or after
  the gestures, when the screen falls back to 60 Hz). With the 60 setting: 16.67 ms in 98-99 %;
  with 30: **33.33 ms in 95-96 %: the whole screen drops to 30 Hz while the map draws**.
- **The map's own surface presented frames 8.32 ms apart** (median interval) at Screen rate,
  111-119 frames per second while drawing. At 60: 16.65 ms; at 30: 33.3 ms.
- `dumpsys gfxinfo` of the app counted **0 frames** during every gesture run: the view system's
  statistics do not see the map at all, which is why they cannot answer this question.
- When the map stops, the display returns to rendering at 60 Hz within about a second (the
  samples before and after each run).

So all three settings work as asked on this phone: the request (`Surface.setFrameRate` on
MapLibre's `SurfaceView` plus `setMaximumFps`) is honoured, and `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS`
did not keep the screen at 60 (the switch between 60 and 120 is seamless on this panel).

### 5.2 Results

**Screen rate (120 Hz)** (surface frames per second while drawing / late frames, median of the runs; runs in brackets)

| View | Vulkan day | Vulkan night | OpenGL day | OpenGL night |
|---|---|---|---|---|
| Pittsburgh z14, pan | 113 / 4.8 % (2) | 112 / 5.2 % (2) | 114 / 2.1 % (2) | 115 / 1.9 % (2) |
| Pittsburgh z14, zoom | 116 / 2.1 % (2) | 115 / 2.9 % (2) | 116 / 2.2 % (2) | 116 / 2.6 % (2) |
| Pittsburgh, travelling pan | 118 / 0.6 % (2) | 118 / 0.6 % (2) | 117 / 1.8 % (2) | 116 / 2.0 % (2) |
| Erie z16, pan | 112 / 0.8 % (2) | 114 / 0.6 % (2) | 115 / 1.5 % (2) | 116 / 1.9 % (2) |
| Erie z16, zoom | 113 / 1.4 % (2) | 113 / 1.5 % (2) | 106 / 3.2 % (2) | 111 / 2.8 % (2) |
| PA/NY border z9.5, pan | 80 / 25.8 % (2) | 80 / 27.0 % (2) | 116 / 3.0 % (2) | 116 / 3.0 % (2) |
| PA/NY border z9.5, zoom | 105 / 7.8 % (2) | 94 / 15.1 % (2) | 114 / 4.0 % (2) | 112 / 4.3 % (2) |
| Rochester NY z14, pan | 119 / 0.2 % (2) | 118 / 0.2 % (2) | 110 / 8.3 % (2) | 110 / 8.3 % (2) |
| Rochester NY z14, zoom | 118 / 0.3 % (2) | 118 / 0.2 % (2) | 111 / 8.3 % (2) | 110 / 8.0 % (2) |
| whole US z2.3, pan | 117 / 0.9 % (2) | 117 / 1.2 % (2) | 114 / 4.2 % (2) | 113 / 4.3 % (2) |
| whole US z2.3, zoom | 115 / 3.7 % (2) | 114 / 4.7 % (2) | 115 / 3.5 % (2) | 115 / 3.4 % (2) |

**60** (surface frames per second while drawing / late frames, median of the runs; runs in brackets)

| View | Vulkan day | Vulkan night | OpenGL day | OpenGL night |
|---|---|---|---|---|
| Pittsburgh z14, pan | 59 / 0.7 % (2) | 58 / 0.8 % (2) | 57 / 2.9 % (2) | 57 / 2.9 % (2) |
| Pittsburgh z14, zoom | 60 / 0.6 % (2) | 60 / 0.1 % (2) | 59 / 2.2 % (2) | 58 / 2.2 % (2) |
| Pittsburgh, travelling pan | 59 / 0.3 % (2) | 59 / 0.3 % (2) | 58 / 2.7 % (2) | 58 / 2.5 % (2) |
| Erie z16, pan | 57 / 1.1 % (2) | 57 / 1.0 % (2) | 57 / 3.1 % (2) | 57 / 2.9 % (2) |
| Erie z16, zoom | 58 / 1.8 % (2) | 57 / 2.1 % (2) | 55 / 4.3 % (2) | 56 / 4.1 % (2) |
| PA/NY border z9.5, pan | 45 / 29.4 % (2) | 45 / 29.6 % (2) | 57 / 4.4 % (2) | 57 / 3.9 % (2) |
| PA/NY border z9.5, zoom | 51 / 14.2 % (2) | 50 / 16.7 % (2) | 56 / 4.9 % (2) | 56 / 4.5 % (2) |
| Rochester NY z14, pan | 60 / 0.2 % (2) | 59 / 0.3 % (2) | 57 / 5.2 % (2) | 56 / 5.3 % (2) |
| Rochester NY z14, zoom | 59 / 0.8 % (2) | 59 / 0.7 % (2) | 56 / 5.3 % (2) | 57 / 5.4 % (2) |
| whole US z2.3, pan | 59 / 0.5 % (2) | 59 / 0.6 % (2) | 57 / 4.4 % (2) | 57 / 4.6 % (2) |
| whole US z2.3, zoom | 59 / 0.8 % (2) | 60 / 1.0 % (2) | 58 / 3.5 % (2) | 58 / 3.9 % (2) |

**30** (surface frames per second while drawing / late frames, median of the runs; runs in brackets)

| View | Vulkan day | Vulkan night | OpenGL day | OpenGL night |
|---|---|---|---|---|
| Pittsburgh z14, pan | 30 / 0.9 % (2) | 29 / 1.5 % (2) | 29 / 2.9 % (2) | 29 / 3.5 % (2) |
| Pittsburgh z14, zoom | 30 / 0.7 % (2) | 30 / 0.4 % (2) | 29 / 2.2 % (2) | 29 / 2.5 % (2) |
| Pittsburgh, travelling pan | 30 / 0.8 % (2) | 30 / 0.5 % (2) | 29 / 3.0 % (2) | 29 / 3.1 % (2) |
| Erie z16, pan | 29 / 2.1 % (2) | 29 / 2.2 % (2) | 29 / 3.4 % (2) | 28 / 3.7 % (2) |
| Erie z16, zoom | 29 / 2.0 % (2) | 29 / 2.4 % (2) | 28 / 5.9 % (2) | 28 / 6.1 % (2) |
| PA/NY border z9.5, pan | 28 / 5.5 % (2) | 28 / 6.5 % (2) | 29 / 3.5 % (2) | 29 / 3.3 % (1) |
| PA/NY border z9.5, zoom | 29 / 3.8 % (2) | 29 / 3.6 % (2) | 29 / 4.8 % (2) | 29 / 3.8 % (2) |
| Rochester NY z14, pan | 30 / 0.1 % (2) | 30 / 0.6 % (2) | 29 / 2.5 % (2) | 29 / 3.0 % (2) |
| Rochester NY z14, zoom | 30 / 0.6 % (2) | 30 / 0.9 % (2) | 29 / 3.6 % (2) | 29 / 3.0 % (2) |
| whole US z2.3, pan | 30 / 1.1 % (2) | 30 / 1.0 % (2) | 29 / 3.6 % (2) | 28 / 4.0 % (2) |
| whole US z2.3, zoom | 29 / 1.5 % (2) | 29 / 1.6 % (2) | 29 / 4.2 % (2) | 29 / 3.9 % (2) |

MapLibre's CPU time per frame (its own encoding + rendering numbers, median of the runs) explains
most of it:

| View | Vulkan 120 / 60 / 30 | OpenGL 120 / 60 / 30 |
|---|---|---|
| Pittsburgh z14 pan | 7.3 / 11.4 / 15.0 ms | 6.4 / 10.3 / 13.2 ms |
| PA/NY border z9.5 pan | **10.1 / 16.1 / 19.6 ms** | 4.7 / 6.9 / 8.3 ms |
| Rochester z14 pan | 4.1 / 3.1 / 3.3 ms | 2.1 / 2.5 / 2.9 ms |

What the tables say:

1. **Everywhere except one view, the map keeps up with 120 Hz on this phone**: 106-119 frames a
   second while panning or zooming, with 0.2-8 % late frames. At 60 and 30 it holds 57-60 and
   28-30. The emulator's worst case, Pittsburgh z14 (7.8-10.3 frames/s there), runs at 111-115
   here.
2. **The worst case is the PA/NY border at z9.5 with the Vulkan build: 80 frames/s and 26-27 %
   late at Screen rate, 45 frames/s and 29-30 % late at 60** (worst single frame while panning
   48-72 ms of MapLibre CPU; the first version said 155-204 ms, but those were frames of the
   view's load, before the gestures: section 16.1). It is the band where the US overview is drawn under and around the state
   (z8-z10, report 08 section 4). The OpenGL build draws the same view at 116 / 57 frames/s with
   3-4 % late and half the CPU time. This is the one view where the renderer matters a lot.
3. **OpenGL has a steady 2-8 % of late frames at every view and every rate; Vulkan mostly under
   2 %** (the exception: Pittsburgh's back-and-forth pan at 120 Hz, Vulkan 5 % against OpenGL
   2 %). OpenGL's late frames show even on the empty Rochester view (8 % at 120 Hz with only 2.1 ms of CPU per
   frame). Those late frames are single missed vsyncs (16.7 ms intervals at 120 Hz), not long
   stalls: a pacing pattern of the OpenGL path, not load. Vulkan's late share there is 0.2 %.
4. **Lower frame rates make each frame slower to draw** (Pittsburgh: 7.3 ms of CPU at 120 Hz,
   11.4 at 60, 15.0 at 30, same build). The phone lowers its CPU clocks when less work is asked
   for. 30 fps is therefore not "four times cheaper" than 120; the battery effect was not measured.
5. **Day and night cost the same** (differences within the run-to-run spread).
6. **Zooming is as smooth as panning** (one-finger zoom, 1 zoom level per gesture): 106-118 frames
   a second at Screen rate, except again the Vulkan border view (94-105; over 5 runs per cell
   after the review 83-116 frames/s, section 16.2).
7. **The run-to-run spread** (these tables have 2 runs per cell): of the 153 pairs, half differ
   by less than 0.8 % in frame rate and 0.4 points in late share, 90 % by less than 2.7 % and
   1.3 points. Zoom runs at 120 Hz spread most: the Vulkan border zoom pair differs by 19 % and
   13 points (115.5 / 1.5 % against 95.0 / 14.2 %), so the day/night difference of that row is
   noise, not a difference. Pans repeated 5 times stay within about 3 % and 3 points (section
   16.2). The emulator's numbers moved by 30 % with the host's load.

## 6. Memory (task 5)

`phone.py memory` (= `measure.py memory`), Vulkan, Day, Screen rate, Pittsburgh z14, 3 runs of
the sequence: fresh start → 8 s → 20 s → 40 back-and-forth swipes → 180 s idle (app in front,
screen on) → fresh start, 60 travelling swipes. MB, median (min-max).

| When | TOTAL PSS | of it Graphics | PSS without Graphics | Native heap | Emulator (report 08, TOTAL PSS) |
|---|---|---|---|---|---|
| 8 s after start | 281 (277-287) | 173 (171-173) | **108** (106-114) | 74 (73-83) | 141-150 |
| 20 s after start | 268 (263-274) | 173 (171-173) | **95** (92-101) | 63 (61-70) | 97 |
| after 40 swipes | 293 (287-298) | 173 | **120** (114-125) | 90 (83-94) | 143-147 |
| after 40 swipes + 180 s idle | 283 (278-298) | 163 (163-173) | **120** (115-125) | 90 (83-94) | not measured |
| after 60 travelling swipes | 254 (253-254) | 170 | **84** (83-84) | 55 | 106-112 |

The same sequence with the z0-14 map (2 runs): 8 s 290 / 118 MB (TOTAL PSS / without
graphics), 20 s 278 / 106, after 40 swipes 297 / 124, after 180 s idle 298 / 125, after 60
travelling swipes 264 / 92. That looked like 4-10 MB more than with the z0-15 map, but the
two sets were 3 h apart (corrected below).

**These start readings move between sessions** (checked after the review, section 16.4): the
reviewer read 287 / 110 MB at 20 s, above the range above, and 24 more readings in a later
session with the same pack gave 265-276 / 94-105 MB at 20 s and 276-289 / 105-118 MB at 8 s.
So the 20 s reading without graphics lies anywhere in **92-110 MB** (28 readings, three
sessions); one reading can be 10-15 % above another session's median. The z0-14 difference was
checked again in one session (z0-15, z0-14, z0-15, 6 starts each): **about +5 MB** at 8 s and at
20 s, smaller than the 4-10 MB that the two sessions above suggested.

- **Graphics memory, about 165-175 MB, is the largest part** (EGL mtrack 115.5 MB: the
  window's and the map surface's buffers; GL mtrack 61.7 MB: the GPU's allocations). The emulator
  reported none, so its TOTAL PSS compares with the phone's "without Graphics" column: the same
  order (84-120 MB here, 97-150 MB there).
- **Three minutes of idle gave nothing back** (120 MB without graphics before and after): tiles
  stay cached while the app is in front. The heap does not grow without bound: a travelling pan
  over new tiles ends lower (84 MB) than a back-and-forth pan over the same ones (120 MB).
- No run came near a limit (the app's Java heap limit is 256 MB; Java heap was 3-7 MB).

## 7. The smaller map data: z0-14 instead of z0-15 (task 6)

**Built** with the assembly script named in `docs/pack-format.md`,
`tools/fetch_android_map_pack.py`, run unchanged by `android/scripts/phone/make_z14_archive.py`
with `--pmtiles data/map/packs-v2/pa-261114-z14.pmtiles`. The wrapper only points the tool's two
output folders to `data/android/packs-z14/` and `data/android/archives-z14/`, because the tool
would otherwise overwrite the kit's own `us-pa` folder and archive. Same pack id `us-pa`, so the
app replaces the installed one. Routing and search are the same files as in the kit's archive
(SHA-256 `3347f2e9…` and `92f09d9a…`, equal to its manifest). The documented Python reader
(`read_pack.py --verify`) checked all three parts: `checksums ok`.

**Not only the zoom differs.** The z0-14 file (`pa-261114-z14`, built 17:35) uses the map track's
profile patch 2 with `poi_drop: true` (it drops point kinds such as `bench`, `picnic_table`,
`waste_basket`, `vending_machine`; the list is in its `.map.json`). The kit's z0-15 file
(`data/map/packs/pa-261114-z0-15`) uses the plain profile. So some differences below come from that
choice, not from the zoom limit; they are marked.

| | z0-15 (kit) | z0-14 (new) | Change |
|---|---|---|---|
| Archive | 1,252,935,680 bytes | **1,033,850,880 bytes** (SHA-256 `bdae4e15…`) | −219 MB (−17.5 %) |
| Map part | 463.8 MB | **244.7 MB** | −47 % |
| adb push | 46.9 MB/s, 25.5 s (review: 50.9 MB/s) | 58.8 MB/s, 16.8 s (review: 56.3 MB/s) | |
| Install through the picker, first MB → Done | 14.84, 15.06, 13.72 s; review 11.79 s | **11.42, 12.79 s; review 8.68 s** | −2.5 to −3.1 s (same session) |
| Cold start, Erie z13, Vulkan, Day (first map frame / fully rendered) | 168 / 499 ms (and 169 / 498) | 156 / 480 ms | slightly lower; the run ranges overlap |
| Memory without graphics, Pittsburgh z14 | 95-120 MB | 106-125 MB | first sessions +4 to +10 MB; **one session, 6 starts each: 8 s 112 and 111 against 117 MB, 20 s 100 and 99 against 104 MB (z0-15, z0-14, z0-15): about +5 MB** (section 16.4) |

**Panning and zooming** (Vulkan, Screen rate, 2 runs each; surface frames per second while
drawing / late frames):

| View | z0-15 day | z0-14 day | z0-15 night | z0-14 night |
|---|---|---|---|---|
| Pittsburgh z14, pan | 113 / 4.8 % | 112 / 6.0 % | 112 / 5.2 % | 113 / 5.0 % |
| Pittsburgh z14, zoom | 116 / 2.1 % | 112 / 4.8 % | 115 / 2.9 % | 112 / 5.5 % |
| Pittsburgh, travelling pan | 118 / 0.6 % | 118 / 0.6 % | 118 / 0.6 % | 118 / 0.6 % |
| Erie z16, pan | 112 / 0.8 % | 111 / 0.7 % | 114 / 0.6 % | 111 / 0.9 % |
| Erie z16, zoom | 113 / 1.4 % | 113 / 1.6 % | 113 / 1.5 % | 113 / 1.5 % |
| PA/NY border z9.5, pan | 80 / 25.8 % | 80 / 26.0 % | 80 / 27.0 % | 81 / 26.1 % |
| PA/NY border z9.5, zoom | 105 / 7.8 % | 99 / 11.2 % | 94 / 15.1 % | 96 / 12.9 % |
| Rochester NY z14, pan | 119 / 0.2 % | 119 / 0.2 % | 118 / 0.2 % | 119 / 0.2 % |
| Rochester NY z14, zoom | 118 / 0.3 % | 116 / 0.2 % | 118 / 0.2 % | 119 / 0.2 % |
| whole US z2.3, pan | 117 / 0.9 % | 117 / 1.3 % | 117 / 1.2 % | 118 / 1.1 % |
| whole US z2.3, zoom | 115 / 3.7 % | 108 / 10.4 % | 114 / 4.7 % | 116 / 2.7 % |

No difference beyond the spread. The first version singled out the zooms at Pittsburgh z14 (2-3
points more late frames with z0-14) and one whole-US zoom pair (108 / 10 %, while the night pair
is 116 / 3 %); after the review's check of the spread (section 5.2, item 7) those are within
what two zoom runs of the same pack differ by (for example the z0-14 Pittsburgh zoom pair at
night: 2.9 and 8.1 % late), so they are not a difference either. At z14 both packs draw the
same zoom level of tiles; the z0-14 tiles hold all of the detail at z14, so a z14 view loads
fuller tiles, a likely reason for the slightly higher memory (not checked further).

**What the map looks like at z15, z16 and z17** (downtown Erie at 42.129, -80.085 and
downtown Pittsburgh at 40.4406, -79.9959, by day and night; 24 screenshots, all looked at; side by
side in `data/phone/screenshots/sheets/`, whole screens `*-full.png` and the middle 640 px at full
size `*-detail.png`):

- **Building outlines: nothing lost that the eye can find.** In the side-by-side sheets every
  building of the z0-15 screenshots is in the z0-14 ones with the same shape, at all three
  zooms, in both cities.
- **Small streets: nothing lost.** Alleys, service roads, footpaths in the parks (Perry Square in
  Erie, Mellon Green in Pittsburgh), railway lines and the one-way arrows are all
  drawn at z15-17 from the z14 data.
- **Small areas only the z15 data had are lost.** In Perry Square (Erie) a small rectangle
  inside the west half of the park (drawn at z16 and z17 from the z0-15 data) is missing with
  z0-14.
- **Curves get corners at z17.** The round path in the middle of Mellon Green (Pittsburgh) is a
  smooth circle with z0-15 and a visible octagon with z0-14 at z17; at z15-16 one cannot tell.
- **Place icons: almost the same.** Hotels, garages, banks, restaurants, the library, the city
  hall, the police and the parks have their icons with both. The **bench icons** in Perry Square
  and Mellon Green (z17) are gone with z0-14, **but because that build drops benches on purpose
  (`poi_drop`), not because of the zoom.** A few named places differ at z16 in Pittsburgh
  ("Au Bon Pain" and "Joe & Pie Cafe & Pizzeria" only with z0-15, "Bae Bae's Kitchen" only with
  z0-14); from screenshots alone one cannot tell whether that is data or label collision.
- **Labels and shields move.** The label placement differs: the z0-14 map shows more street
  names at z15 (Peach, Sassafras, State Street in Erie; Smithfield Street, Liberty Avenue, 7th
  Avenue, Boulevard of the Allies in Pittsburgh), the z0-15 map shows others (French, Holland
  Street) and the I-579 shield at Pittsburgh z15. Route 5 keystone shields appear in other places
  (more of them at Erie z17 with z0-14).
- Day and night show the same differences; no rendering faults in either.

In plain words: **up to z16 a driver would not see a difference; at z17 the z0-14 map is a
little coarser** (a missing small feature, a polygonal circle), at a 219 MB smaller download
and 2.5 s shorter install.

## 8. Location (task 7; numbers only)

`location_check.py`: the Vulkan test-extras build, the location permission granted with
`pm grant` (our app only) for this test and revoked after it, the phone's location on. Each run:
the app starts at a public view (Pittsburgh z12, so the camera does not show the position),
its log is recorded for 60 s (one run 180 s), then "Show my position" is tapped and a screenshot
taken. Logs and screenshots are in `data/phone/private/` only; the screenshots were checked by
pixel colours in the middle of the screen against two areas 300 px away, not looked at. The
phone lay still, indoors. The script's first version counted only the blue, so its saved result
for run 4 said "puck not seen"; since the review it counts the grey puck too and stores
`puck_state` (live, grey or none), and `--recount` produced the numbers below from the saved
logs and screenshots (`data/phone/private/results/location-recount.jsonl`).

| What | Result (4 runs) |
|---|---|
| Puck appears | **at once** (0.00 s after the app asked for updates) from Android's last known position: accuracy 100 m (one run 20 m), drawn **grey** because that fix was old; 0.03 s later a second stored fix made it **live** (100 m) |
| New fixes | **one every 20.0 s** in all runs (live at 0, 20, 40, 60 … s), each reported at **100 m**; the position did not change between them. Read from the puck turning live again (median interval 20.0 s in all 4 runs): the app logs the puck only when it changes, so a repeated fix shows only after the puck has gone grey; fixes closer than 10 s apart would not show this way |
| Better fixes | in the 180 s run one fix of **20 m at 116 s**, then 48 m, 76 m and 100 m on the next fixes (20 s apart); **no fix better than 20 m** in any run |
| Satellite fix | **none seen** within 70-190 s (inferred: the app does not log the provider, but a satellite fix reports better than 20 m and comes every second) |
| Satellite count, time to a satellite fix | **not measured**: the app does not read `GnssStatus`, and `dumpsys location` is outside what this work may read |
| Puck on screen after "Show my position" | **yes, in all 4 runs** (counts corrected after the review, from `location_check.py --recount`): runs 1-3 the **live** puck, 1,561 pixels of the puck blue #5BAAFF (within 14 per channel) in the 181 x 181 px square at the screen's centre; run 4, taken at 186 s, just after the puck had turned grey (last fix at 176 s), the **grey** puck, 1,581 pixels of the puck greys (1,575 of the day grey #8C96A1) and no blue. In both control squares 300 px left and right: 0 pixels of either fill colour. White (the ring) is in the centre square (2,692-2,697 px) but also in the control squares (3,518 px: white streets), so it only confirms, it does not prove |

**Found on the phone, hidden by the emulator:** the emulator gave a fix every second. Here, with
a fix every 20 s and the rule "grey when the fix is older than 10 s"
(`assets/rules/position.json`), **the puck alternates blue and grey every 10 s** while the phone
lies indoors. Whether that is right is a decision (section 13). The emulator's first fix came
4.5 s after the grant; here a stored fix is shown at once, but the first fresh, accurate fix can
take minutes indoors.

## 9. What the phone showed that the emulator hid (task 8)

1. **The Vulkan build is slow where the overview lies under and around a state** (PA/NY border
   z9.5): 80 frames/s and a quarter of the frames late at Screen rate, 45 frames/s at 60. The
   slowest single frame while panning took 48-72 ms of MapLibre CPU (corrected after the
   review: the 155-204 ms frames first quoted here were the view's load frames, section 16.1).
   Those load frames are a phone finding of their own: while a view first draws after a start,
   Vulkan's slowest frame takes 0.12-0.23 s, OpenGL's under 0.05 s.
   The OpenGL build draws the same view at 116 / 57 frames/s. On the emulator both were equally
   slow (4.6-6 frames/s), so the difference could not show. At most other views Vulkan has
   fewer late frames.
2. **The OpenGL build misses a vsync now and then at every view** (2-8 % late at 120 Hz, 3-5 %
   at 60), even with almost nothing to draw. The emulator ran at 60 Hz with everything else much
   slower, so it could not show this.
3. **"30 frames a second" puts the whole screen at 30 Hz while the map moves** (SurfaceFlinger's
   period 33.33 ms), not only the map. Smooth system animations (the status bar, a notification
   sliding in) would then also run at 30 while the map moves. And a frame costs more CPU time at
   30 than at 120 (lower clocks), so the battery saving is smaller than the frame count suggests.
4. **A pack pushed with adb stays "pending" in Android 17's media index** and the system file
   picker hides it ("No items"). `content call --uri content://media --method scan_file --arg
   <file>` fixes it. The phone kit's "adding_a_state" line should say so (`data/android/phone-kit.json`
   is not in git; `android/README.md` may carry the same advice).
5. **A dark system theme opens the night map** at first start ("Automatic"); this is the setting
   working, mentioned because a first impression on a phone in dark mode is the night map.
6. **Our app's log over about four hours, 318 gesture runs, 60 timed cold starts and 5 installs**: no
   error line and no crash (`logcat --pid`, every line of our process at level W/E/F kept). The
   only warning is "puck not drawn: style not set", once per start, before the style has loaded
   (harmless: the puck is drawn when the style is ready). MapLibre logged no error with either
   renderer.
7. **Gestures that adb can drive** (the Vulkan test-extras build, Erie z13): a **double tap**
   zoomed in by exactly one level (camera z13.00 → z14.00); a **long press** (1.2 s) did nothing
   (no camera move, the app stayed in front); the **system Back key** on Settings returned to the
   map; one-finger **double-tap-and-drag** zoomed smoothly in and out (section 5); fling after a
   swipe works (the pans). The focus check stopped one run when another window came to the front
   (section 5).
   Pinch, two-finger zoom-out and rotation cannot be sent with `input`; rotate and tilt are off
   in the app anyway (browsing is north-up and top-down).
8. **Location permission found granted.** At 23:06 phone time (my first start of the app) the app
   had no location permission. At 23:12, when my install script started the app again, Android
   showed `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` granted with the flags
   `USER_SET` and `SELECTED_LOCATION_ACCURACY`, which the system's permission dialog sets when
   someone taps "Precise" and "While using the app". None of my taps were on the map screen
   (where the location button is), and no other process on this machine was talking to the phone
   (only the adb server). Most likely someone tapped the phone's screen. I revoked both
   permissions with `pm revoke` (our app only) before any map measurement, so that no map log
   or screenshot could carry the real position, granted them again only for the location test,
   and revoked them afterwards (section 11).

## 10. The phone beside the emulator (report 08)

Emulator: `beamline_api36` (AOSP API 36 x86_64, 3 GB, host GPU) on the shared PC under 19-100 %
CPU load, release build with the Vulkan renderer, PA + OH installed, the "Screen rate" setting
there meaning 60 fps. Phone: Pixel 9, the Vulkan test-extras release unless stated, PA only.
Phone values are medians (min-max) over the runs given in sections 3-8.

| Measure | Emulator (report 08) | Pixel 9 | Phone method |
|---|---|---|---|
| Cold start Erie z13: activity drawn | 624-850 ms (medians of 5 sets) | **108-115 ms** | `phone.py startup`, 7 runs x 3 sets |
| Cold start Erie z13: first map frame | 993-1375 ms (781-3134) | **168, 169 ms** (154-185); OpenGL 163 | same |
| Cold start Erie z13: fully rendered | 2040-3314 ms | **499, 498 ms** (478-528); OpenGL **321** | same |
| Cold start lower 48 z3.3 (activity / first frame / full) | 1178 / 2107 / 3336 ms | 111 / 161 / 384 ms; OpenGL 112 / 151 / 226 | 5 runs |
| Pan Pittsburgh z14, 40 swipes (the app's frames/s; MapLibre CPU per frame) | **7.8-10.3 frames/s; 103-145 ms** | **110.0 (109.7-114.0); 7.5 ms**; surface 111.3 frames/s, 5.7 % late | 3 runs, Screen rate (120 Hz) |
| the same at 60 (the emulator's cap) | (as above) | 58.8 frames/s, 0.7 % late; 11.4 ms | 2 runs of 20 swipes |
| Travelling pan Pittsburgh, 60 swipes | 9.2 and 21.5 frames/s; 122 and 47 ms | **120.9 (120.7-121.0); 6.8 ms**; surface 117.7, 0.5 % late | 3 runs |
| Rochester NY z14 (no state, blank) | 58.5, 56.3 frames/s; 2.4, 3.6 ms | 121.0 frames/s; 4.1 ms; surface 118.7, 0.2 % late | 2 runs |
| PA/NY border z9.5 | 4.6-6.0 frames/s; 175-261 ms | Vulkan **79.0; 10.1 ms**, surface 80.4, 25.8 % late; OpenGL **113.4; 4.7 ms**, surface 116.2, 3.0 % late. Over 5 runs each (with the review's repeats), day: Vulkan 80 (80-81) frames/s, 26.3 % (25.1-27.9) late; OpenGL 116 (115-117), 3.0 % (2.8-3.1) | 2 runs each; 5 after the review |
| Memory Pittsburgh z14, 8 s after start | 141-150 MB TOTAL PSS (no GPU memory reported) | 281 MB TOTAL PSS, of it 173 MB graphics; **108 MB without** (all sessions: 105-118) | 3 runs; 27 after the review |
| … 20 s after start | 97 MB | 268 / **95** MB (all sessions: 263-287 / 92-110) | 3 runs; 28 after the review |
| … after 40 swipes | 143-147 MB | 293 / **120** MB | |
| … after 60 travelling swipes | 106-112 MB | 254 / **84** MB | |
| Add PA (1.25 GB) through the picker | 14.6 s, 10.4 s | **14.84, 15.06, 13.72 s; review 11.79 s** | logcat, first MB → Done |
| adb push of the PA archive | 45-98 MB/s | 46.9 MB/s; review 50.9 MB/s | |
| Display rates | one mode, 60 Hz | 60 and 120 Hz; **120 used at Screen rate** | SurfaceFlinger |
| First position | 4.5 s after the grant (emulated fix every second) | stored fix at once; fresh fixes every 20 s at 100 m indoors | section 8 |
| Run-to-run spread | up to 30 % (host load) | pans: within about 3 % in frame rate and 3 points in late share (5 runs); pairs: 90 % within 2.7 % and 1.3 points; zooms at 120 Hz more (Vulkan border zoom 84-116 frames/s) | sections 5.2 and 16.2 |

## 11. State left on the phone

Checked at the end of the first work (01:04) and again at the end of the review work (about
02:58, this machine's clock); the list below is the final state.

- **Installed:** only `io.github.spysyweeb.beamline` (`pm list packages` with our prefix), the
  **plain release**: the installed `base.apk` has SHA-256 `201f249a…5a5`, the kit's
  `beamline-release.apk`. It **ignores the test extras**: started with `--ef lat 40.44 --ef lon
  -80.0 --ef zoom 14 --es screen settings`, it opened the map on the Pennsylvania box (camera
  z5.55), not Pittsburgh z14 and not Settings (checked both times; at the second check it logged
  "renderer: vulkan", 127 style layers, first map frame 180 ms). **No test-extras build remains.**
- **Pennsylvania installed**: the kit's z0-15 archive, added through the picker (install run 4,
  during the review): manifest `max_zoom 15`, `install-record.json` `"verified": "sha256",
  "source": "archive"`, map part 463,787,527 bytes, 1.25 GB.
- **Settings at their defaults**: Map style Automatic (the phone's dark theme gives the night
  map), Frame rate Screen rate, Guidance sound Silent (read back from the Settings screen's
  checked buttons at the end of the review work), Units Miles (never changed).
- **Location not granted** to the app. It was found granted at 23:12 (section 9, item 8); I left
  it as a fresh install would be. The app asks when the location button is tapped. The review
  work did not grant it.
- **`/sdcard/Download/beamline-test/` deleted** (both archives and the UI dumps), the deleted
  paths scanned again, and a media-index query for that path finds no row. Nothing else was
  written outside the app's own storage.
- **"Stay awake while charging" restored** to its earlier value, off (`0`), after both works.
- The app was force-stopped. The first time that brought an old system file picker window to
  the front (left from one of the app's "Add a state" flows); it was closed with Back, sent only
  while the picker had the focus. Behind it **a Google Play services screen**
  (`com.google.android.gms`, OctarineActivity) was in front. This work did not open it and did
  not touch it. It may be the window that took the focus at 22:52 (section 5). The review work
  found that screen in front at its start and left it in front at its end (no picker window
  this time).
- Free storage 10 GB (11 GB at the start; the difference is the installed pack).

## 12. Not verified, failed or left open

- **No satellite fix, no satellite count.** Indoors no satellite fix came in up to 190 s; the
  count needs `GnssStatus` in the app or `dumpsys location`, which this work may not read. An
  outdoor test and a drive are needed.
- **Battery, temperature and thermal throttling** were not measured (no allowed reading). The
  phone ran about four hours of rendering while charging. Two passes almost two hours apart gave
  the same numbers (Vulkan, Pittsburgh z14, day: 113.1 and 112.1 frames/s), so throttling did not show.
- **Why the Vulkan build is slow at the border view** was not looked into in MapLibre's code; it
  coincides with the overzoomed overview tiles (report 08, section 4). **Why OpenGL misses single
  vsyncs** everywhere is inferred from the interval pattern only.
- Pinch zoom, two-finger zoom-out, rotation and tilt cannot be sent with `input`.
- Only Pennsylvania was installed (the emulator numbers had Ohio too: 214 against 127 style layers).
- The "30" setting's battery effect: frames cost more CPU each at 30 (section 5), so the saving
  is unknown.
- The picker navigation in `phone.py install` looks for English labels ("Show roots",
  "Downloads"); another system language would need other labels.
- The z15-17 comparison is by eye on 24 screenshots of two downtowns, not a feature count.
- Who granted the location permission at 23:12 is unknown.
- The kit's instructions (`data/android/phone-kit.json`, "adding_a_state") and `android/README.md`
  do not mention the media-index step that adb pushes need on this phone; I did not change them
  (the kit is another engineer's file, outside git, and the README is being edited for the rename).
  Still so after the review; the line to add is in section 16.5.
- One present interval of 166 ms (of 1,532 over 150 ms) began 0.95 s after a camera stop, later
  than the 0.8 s the check allows, so it is unclear whether the map was moving (section 16.1).
- The memory readings soon after a start move by about 10 % between sessions; why was not found
  (not the fresh install, section 16.4).
- The reviewer's single 20 s memory reading (287 / 110 MB) is above all 27 other readings; it
  was not reproduced.

## 13. Decisions for the owner

1. **Renderer: Vulkan (now) or OpenGL ES.** OpenGL draws the overview band near state lines
   (z8-z10) at 116 instead of 80 frames/s (57 instead of 45 at the 60 setting), reaches "fully
   rendered" 0.18 s sooner (Vulkan's slowest frame while a view loads takes 0.12-0.23 s), and its
   APK is 7.9 MB smaller (62.0 against 69.9 MB). Vulkan has fewer
   late frames at most other views (mostly under 2 % against 2-8 %; Pittsburgh's back-and-forth
   pan excepted, 5 % against 2 %). These numbers held over 5 runs per cell after the review
   (section 16.2). A third way: keep Vulkan and make the
   overview cheaper (report 08, decisions 1 and 2: the 3.66 MB overview, or stop it at z8), then
   re-measure the border view with `run_sets.py matrix --views border-z9.5`.
2. **Map data z0-14 or z0-15.** z0-14: 219 MB smaller per state (the map part halves), 2.5 s faster
   to add, the same smoothness, about 5 MB more memory after a start, at z17 a little coarser (section 7). If
   z0-14 is chosen, also decide whether its POI drop list (benches etc.) is wanted, since the
   comparison here mixes both.
3. **The grey puck with slow fixes.** With fixes every 20 s indoors the puck blinks blue/grey
   every 10 s. Options: lengthen `old_after` in `assets/rules/position.json` (for example 25 s),
   or grey only after two missed fix intervals.
4. **"30 frames a second"** slows the whole screen to 30 Hz while the map moves and saves less CPU
   per frame than the number suggests. Keep it, describe it differently, or drop it.
5. **Location permission**: found granted (unknown who); left not granted.

## 14. How to repeat

From the repo root in Git Bash, a phone attached with USB debugging allowed
(`export MSYS_NO_PATHCONV=1 BEAMLINE_PHONE_SERIAL=<serial> PYTHONIOENCODING=utf-8`):

```
adb -s $BEAMLINE_PHONE_SERIAL shell settings get global stay_on_while_plugged_in     # note it; set 7; restore at the end
adb -s $BEAMLINE_PHONE_SERIAL install data/android/apks/beamline-release-testextras-vulkan.apk
adb -s $BEAMLINE_PHONE_SERIAL shell mkdir -p /sdcard/Download/beamline-test
adb -s $BEAMLINE_PHONE_SERIAL push data/android/archives/us-pa-2026-11-14.tar /sdcard/Download/beamline-test/
adb -s $BEAMLINE_PHONE_SERIAL shell content call --uri content://media --method scan_file \
    --arg /storage/emulated/0/Download/beamline-test/us-pa-2026-11-14.tar   # else the picker shows "No items"
python android/scripts/phone/phone.py device
python android/scripts/phone/phone.py install us-pa-2026-11-14.tar
python android/scripts/phone/phone.py settings --style day --fps screen
python android/scripts/phone/phone.py startup --runs 7 --warmup 1 --lat 42.121 --lon -80.082 --zoom 13 --view erie-z13 --tag v1
python android/scripts/phone/run_sets.py memory --reps 3 --tag v1
python android/scripts/phone/run_sets.py shots --tag v1
python android/scripts/phone/run_sets.py matrix --tag v1          # once per renderer and pass; adb install -r the other test-extras APK between
python android/scripts/phone/phone.py gestures
python android/scripts/phone/make_z14_archive.py                  # data/android/archives-z14/us-pa-2026-11-14.tar
python -I android/scripts/read_pack.py data/android/archives-z14/us-pa-2026-11-14.tar --verify
#   push it as us-pa-z14-2026-11-14.tar (+ scan_file), phone.py install us-pa-z14-2026-11-14.tar, then:
python android/scripts/phone/run_sets.py shots --tag v2
python android/scripts/phone/run_sets.py matrix --tag v2 --fps screen
python android/scripts/phone/run_sets.py memory --reps 2 --tag v2
.venv/map/Scripts/python android/scripts/phone/compare_sheet.py
python android/scripts/phone/location_check.py --runs 3 --seconds 60 --revoke-after   # private output only
python android/scripts/phone/summarize.py compact --tag v1        # also: pan, memory, startup, install
# the review's checks and repeats (section 16):
python android/scripts/phone/reanalyse_gaps.py --jsonl            # no phone: intervals over 150 ms in the saved runs
python android/scripts/phone/location_check.py --recount data/phone/private/location/<run folder>   # no phone, numbers only
python android/scripts/phone/run_sets.py matrix --tag r1 --fps screen 60 --views pgh-z14 border-z9.5 roc-z14 --zoom-views border-z9.5
#   six passes, renderer order Vulkan, OpenGL, OpenGL, Vulkan, Vulkan, OpenGL (adb install -r between)
python android/scripts/phone/run_sets.py memstart --reps 6 --tag r1-z15a   # after settings --style day --fps screen
python android/scripts/phone/summarize.py compact --tags r1 --views pgh-z14 border-z9.5 border-z9.5-zoom roc-z14 --spread
```

Each `matrix` pass takes about 33 minutes (the review's smaller pass about 11); the whole report
about four hours of phone time, the review about two more.

## 15. Files

Committed (new): `android/scripts/phone/` (`phone.py`, `run_sets.py`, `make_z14_archive.py`,
`compare_sheet.py`, `location_check.py`, `summarize.py`, and since the review
`reanalyse_gaps.py`) and this report. Nothing else in the repository was changed.

Not in git (`data/`):

- `data/phone/results/*.jsonl`: every run's numbers (`pan`, `memory`, `startup`, `install`;
  the review's runs have the tags `r1`, `r-fresh`, `r1-warm`, `r1-z15a`, `r1-z14`, `r1-z15b`;
  `gaps-reanalysis.jsonl` from `reanalyse_gaps.py`);
  `data/phone/raw/pan-*.json`: the merged SurfaceFlinger present times and our app's log lines
  of each gesture run; `data/phone/logs/`: work log (with every step on the phone and the two
  slips), device facts, the start-up, install, matrix and chain outputs.
- `data/phone/screenshots/`: `01-states-pa-installed.png`, `02-settings-day.png`,
  `zoom-compare/` (24 screenshots, public test locations), `sheets/` (8 side-by-side sheets).
- `data/phone/private/`: the location logs, results and screenshots that show the phone's
  position. Never copied elsewhere.
- `data/android/packs-z14/us-pa/` and `data/android/archives-z14/us-pa-2026-11-14.tar`: the
  z0-14 pack (1,033.9 MB).

## 16. Review and fixes

A reviewer reproduced the main numbers on the phone (cold start, the Pittsburgh pan, the 60
setting, above-60 Hz, the renderer of each build, the matrix cells, the screenshots, the privacy
of the files, the end state) and raised five minor findings. Each was checked again on
2026-11-16 (01:25-03:00, the same phone and kit; about 1.5 h of phone time), the scripts were
corrected, and what the findings put in doubt was measured again.

| # | Finding | Checked | Result | Fixed |
|---|---|---|---|---|
| 1 | Intervals over 150 ms are left out as pauses and could hide stalls at the Vulkan border view | Every saved run re-read; new runs check it themselves | **No hidden stalls**: 1,531 of 1,532 such intervals began after the camera stopped, one of 166 ms is unclear. But the report's "single frames up to 0.2 s" was **wrong**: those were load frames | `phone.py` (long intervals, camera-stop check, load window closed before the run), `reanalyse_gaps.py` (new), `summarize.py`; sections 1, 5.2, 9 |
| 2 | Spread claims rest on 2 runs per cell | 96 more runs of the decision cells (3 per cell) | The renderer comparison holds (5 runs per cell). "1-2 %" was too broad a claim: pans stay within about 3 % and 3 points, zooms at 120 Hz vary far more | `run_sets.py` (`--zoom-views`), `summarize.py` (`--tags`, `--spread`); sections 5, 10 |
| 3 | Location pixel counts differ from the saved results; the grey puck was saved as "not seen" | Recount of the saved screenshots | Confirmed: 1,561 (not 1,556) blue pixels; run 4 has 1,581 grey pixels (not 1,549). "None of either in the control areas" was wrong for white | `location_check.py` (grey puck, `puck_state`, control squares, `--recount`); section 8 |
| 4 | The reviewer's 20 s memory reading is above the report's range | 24 more readings, before and after many starts, and z0-15/z0-14/z0-15 | Readings move by about 10 % between sessions; the range is now 92-110 MB. The z0-14 difference shrinks to about 5 MB | `run_sets.py memstart` (new); sections 6, 7, 10 |
| 5 | The kit and `android/README.md` lack the media-scan step | Still lacking | Left unchanged (another engineer's files); the line to add is below | none (orchestrator) |

### 16.1 Intervals over 150 ms, and the slowest frames (finding 1)

The app logs "camera idle" each time the camera stops. Lining these lines up with
SurfaceFlinger's present times tells a pause (the camera has stopped, the map has nothing to
draw) from a stall (the camera moves, a frame is late). For the 318 saved runs of sections 5-7
the clocks were lined up by the app's first "frames:" window, which is logged at the first frame
of the first gesture (316 runs could be lined up; check: every later "frames:" line fell within
-10 to +12 ms of a present for 90 % of 5,562 lines, median 0.2 ms). The review's 96 new runs read
the log with `logcat -v monotonic`, the clock of the present times, so they need no such step.
A long interval counts as a pause when the camera stopped at most 0.8 s before it began.

| Runs | Intervals over 150 ms | Began within 0.8 s after a camera stop | While the camera moved | Start after the last stop: median / 99 % / max |
|---|---|---|---|---|
| Report (tags v1, v2), 316 runs | 1,109 | 1,109 | **0** | 317 / 405 / 760 ms |
| Review repeats (r1), 96 runs | 423 | 422 | **1** (166 ms, began 0.95 s after a stop) | 323 / 449 / 950 ms |

So the long intervals are the pauses between swipes: they begin about 0.3 s after the camera
stops, when MapLibre's label fades end. Nothing was hidden at the border view either (Vulkan,
56 runs: 0 of 166 began while the camera moved). The one unclear interval was in the first run after re-installing the OpenGL
build (Pittsburgh z14, day, 120 Hz, 14 long intervals against 2-6 usually); MapLibre's slowest
frame in that run took 22 ms, so it was not a slow frame; most likely the next swipe simply
began late, but that cannot be told apart. A stall in the very first frames of a swipe would
likewise merge with the pause before it; MapLibre's slowest gesture frames (below) bound that.
The late shares do not come from the label fades either: leaving out the intervals that start
within 0.45 s after a camera stop gives about the same shares while the map moves (Vulkan
border, 120 Hz: 23.5-24.4 % against 25.8-27.0 %; OpenGL Rochester: 8.6-8.8 % against
8.0-8.3 %).

**The slowest frames, corrected.** The report quoted "single frames up to 155-204 ms" at the
Vulkan border view. Those frames were in the app's first one-second window, which also holds the
view's load. To separate the two, `phone.py pan` now moves the camera by a few metres with the
test extras 1.5 s before the gestures; that closes the load window before the run. Results
(the old runs re-read without their first window, and the 96 new runs):

| | Vulkan | OpenGL |
|---|---|---|
| Slowest frame while a view loads (after a start) | **121-234 ms** at the border, Pittsburgh, Erie and US views (with the new settle step: border 127-193, Pittsburgh 187-234 ms); 4-10 ms at the empty Rochester view | 6-42 ms |
| Slowest frame during gestures, border view, pan / zoom | **48-72 ms** / 31-53 ms | 12-25 ms |
| Slowest frame during gestures, the other views | 9-42 ms | 6-52 ms |

So Vulkan's expensive frames come once, while a view first draws after a start (most likely the
building of its drawing pipelines; not looked into in MapLibre). That is where its later "fully
rendered" comes from (0.50 against 0.32 s, section 4).

`phone.py pan` now records per run: `long_intervals` (count, total, longest, each length,
`while_moving`, the time after the last camera stop), `late_share_moving`, `fps_moving`,
`maplibre_cpu_ms_worst_before_run` and `settle`; `reanalyse_gaps.py` gives the same for the
saved runs (`data/phone/results/gaps-reanalysis.jsonl`), and `summarize.py pan` shows the long
intervals and the late share while moving.

### 16.2 Repeats of the cells that drive the renderer decision (finding 2)

Pittsburgh z14 pan, border z9.5 pan and zoom, Rochester z14 pan; Screen rate and 60; day and
night; 3 more runs per cell with each renderer, in six passes in the order Vulkan, OpenGL,
OpenGL, Vulkan, Vulkan, OpenGL (tag `r1`, 96 runs). Surface frames per second while drawing /
late frames, median (min-max) of the 3 new runs:

**Screen rate (120 Hz)**

| View | Vulkan day | Vulkan night | OpenGL day | OpenGL night |
|---|---|---|---|---|
| Pittsburgh z14, pan | 112 (112-113) / 4.8 % (4.6-5.2) | 114 (113-114) / 3.8 % (3.1-4.8) | 116 (113-116) / 2.2 % (1.9-2.4) | 115 (115-116) / 2.1 % (2.1-2.3) |
| PA/NY border z9.5, pan | 80 (80-81) / 26.7 % (25.1-27.9) | 80 (80-81) / 26.1 % (26.0-27.2) | 116 (115-117) / 3.0 % (2.8-3.1) | 115 (115-116) / 3.2 % (3.1-3.2) |
| PA/NY border z9.5, zoom | 92 (84-100) / 15.9 % (10.7-23.2) | 99 (83-100) / 11.3 % (11.1-22.5) | 113 (112-113) / 3.9 % (3.7-4.6) | 114 (113-114) / 4.3 % (4.2-4.3) |
| Rochester NY z14, pan | 119 (119-119) / 0.2 % (0.2-0.2) | 118 (118-118) / 0.2 % (0.2-0.2) | 110 (110-110) / 8.2 % (8.1-8.5) | 110 (110-110) / 8.3 % (8.2-8.5) |

**60**

| View | Vulkan day | Vulkan night | OpenGL day | OpenGL night |
|---|---|---|---|---|
| Pittsburgh z14, pan | 59 (58-59) / 0.8 % (0.6-1.1) | 58 (58-59) / 0.7 % (0.6-0.9) | 58 (57-58) / 2.8 % (2.7-2.8) | 58 (57-58) / 2.6 % (2.6-3.2) |
| PA/NY border z9.5, pan | 45 (44-46) / 30.6 % (29.1-31.2) | 45 (45-46) / 28.8 % (28.3-29.4) | 57 (56-57) / 4.3 % (4.2-4.4) | 57 (56-58) / 4.4 % (3.8-4.5) |
| PA/NY border z9.5, zoom | 50 (47-51) / 13.7 % (13.7-20.6) | 51 (49-52) / 14.9 % (13.6-19.8) | 56 (56-56) / 4.7 % (4.5-4.8) | 57 (56-57) / 4.7 % (4.7-5.0) |
| Rochester NY z14, pan | 59 (59-60) / 0.3 % (0.1-0.5) | 59 (59-60) / 0.3 % (0.2-0.5) | 56 (56-56) / 5.8 % (5.4-5.9) | 57 (57-57) / 5.2 % (5.2-5.4) |

- **The renderer comparison holds.** For every pan cell the new medians are within 1.2 frames/s
  and 1.4 points of the report's two runs. Over all 5 runs: border pan at Screen rate, Vulkan
  80 (79-81) frames/s with 25.1-27.9 % late, OpenGL 116 (115-117) with 2.7-3.3 % late; Rochester,
  Vulkan 0.1-0.3 % late, OpenGL 8.0-8.6 %; Pittsburgh, Vulkan 3.1-5.5 %, OpenGL 1.7-2.4 %.
- **Spread over 5 runs**: every pan cell stays within 3.2 % in frame rate and 2.8 points in late
  share. The Vulkan border zoom does not: at 120 Hz its 5 runs range from 84 to 116 frames/s and
  from 1.5 to 23.2 % late (the report's day pair, 115.5 / 1.5 % and 95.0 / 14.2 %, is part of
  this). So that one cell is noisy, and its day/night difference in section 5.2 means nothing;
  OpenGL's border zoom stays within 3 % and 1 point.
- The report's claim "runs differ by 1-2 %" is replaced in the summary, in section 5.2 (item 7)
  and in section 10.
- The other findings of section 5.1 held in these 96 runs: `dumpsys gfxinfo` counted 0 frames in
  every run; SurfaceFlinger's vsync period was 8.33 ms in all 2,252 samples at Screen rate and
  16.67 ms in 98 % of 2,237 samples at 60.

### 16.3 Location pixel counts (finding 3)

Confirmed. The script saved only a count of the blue, so its result for the 180 s run said
`puck_seen: False` while the screenshot showed the grey puck, and the report's counts did not
come from the script. `location_check.py` now counts the live colours (the kit's blue and the
chosen "Clear" blue, by day and night), the grey colours and white in the centre square and in
two control squares, stores `puck_state` (live / grey / none), and `--recount` re-reads saved runs
without the phone, printing numbers only. Recount of the 4 saved runs (in
`data/phone/private/results/location-recount.jsonl`):

| Run | Puck state | Live px | Grey px | White px | Control squares (live / grey / white) | Puck turned live again (s) |
|---|---|---|---|---|---|---|
| 1-3 (60 s) | live | 1,561 | 26 | 2,697 | 0 / 0 / 3,518 | every 20.0 s |
| 4 (180 s) | grey | 0 | 1,581 | 2,692 | 0 / 0 / 3,518 | every 20.0 s, once 16.2 s (the 20 m fix) |

The reviewer's counts agree: 1,561 blue, and 1,575 pixels of the day grey (the script's 1,581
adds 6 pixels near the night grey, anti-aliased edges). Two smaller
corrections from the recount: the old script's "first live" was empty for runs 2 and 3 because it
only looked at fixes with a new position or accuracy (the puck turned live 0.03 s after the
request in all 4 runs, as the report said); and white is no evidence on its own, since the map's
white streets fill the control squares too. Section 8 is corrected.

Also seen while recounting (each colour counted on its own): the live puck in the kit's build is
candidate A's day blue (#5BAAFF: 1,561 pixels; 0 of the chosen "Clear" #4A8CF7). The kit's APKs
were built on 2026-11-15 before the theme took "Clear"; the source (`android/ui/theme`,
`Theme.kt`) has "Clear" now, so the next build should draw it (not checked on the phone).

### 16.4 Memory soon after a start (finding 4)

`run_sets.py memstart`: fresh start at Pittsburgh z14, readings at 8 s and about 20 s (the first
two steps of `run_sets.py memory`), 6 starts per set, Vulkan build. MB, median (min-max):

| Set | Pack, settings | 8 s: TOTAL PSS / without graphics | 20 s: TOTAL PSS / without graphics / native heap |
|---|---|---|---|
| Report (3 runs, the first session) | z0-15, Day, Screen rate | 281 (277-287) / 108 (106-114) | 268 (263-274) / 95 (92-101) / 63 (61-70) |
| Reviewer (1 reading) | z0-15, Day, Screen rate | - | 287 / 110 / 74 |
| `r-fresh`: right after `install -r` | z0-15, Day, Screen rate | 287 (284-288) / 116 (113-117) | 274 (266-276) / 103 (101-105) / 66 (64-67) |
| `r1-warm`: after about 100 starts (left at Night, 60 by the last pass) | z0-15, Night, 60 | 284 (282-289) / 113 (112-118) | 272 (271-276) / 101 (98-105) / 64 (61-68) |
| `r1-z15a` | z0-15, Day, Screen rate | 283 (281-288) / 112 (110-117) | 271 (267-275) / 100 (96-104) / 65 (61-69) |
| `r1-z14` (the z0-14 archive added through the picker) | z0-14, Day, Screen rate | 289 (284-292) / 117 (111-120) | 276 (273-278) / 104 (100-107) / 68 (64-72) |
| `r1-z15b` (the kit's archive added again) | z0-15, Day, Screen rate | 282 (276-285) / 111 (105-112) | 271 (265-272) / 99 (94-100) / 66 (61-67) |

- The 20 s reading without graphics is **92-110 MB** over 28 readings with the z0-15 pack in
  three sessions; this session's readings lie 4-8 MB above the first session's. The reviewer's
  287 / 110 is above all 27 others, by 4 % in TOTAL PSS and 5 % without graphics against this
  session's highest (276 / 105). A fresh install is not the cause (`r-fresh` is within 4 MB of
  the later sets); the cause was not found. Section 6 now gives the wider range.
- **z0-14 against z0-15 in one session** (A-B-A): +5 to +6 MB without graphics at 8 s, +4 to
  +5 MB at 20 s. The first sessions' "+4 to +10 MB" came from two sessions 3 h apart and is
  replaced by "about 5 MB" (sections 6, 7, 13). The later steps of the memory sequence (after
  panning, after idle) were not repeated.

### 16.5 The media-scan step (finding 5)

Confirmed and left as it is: `data/android/phone-kit.json` ("adding_a_state") and
`android/README.md` (the `adb push … /sdcard/Download/` line) still do not mention it, and both
belong to other work. The line to add after the push, for whoever edits them next:

```
adb shell content call --uri content://media --method scan_file --arg /storage/emulated/0/Download/us-pa-2026-11-14.tar   # a phone's file picker hides an adb-pushed file until it is scanned
```

The review scanned both of its pushes this way before opening the picker, and both files were
listed and installed (whether they would have been hidden without it was not tried again).

### 16.6 What the review work did on the phone

Only the rules of section 1: `stay_on_while_plugged_in` read (0), set to 7 and put back to 0;
only our package installed and replaced (Vulkan and OpenGL test-extras builds, then the plain
release); files pushed only into `/sdcard/Download/beamline-test/`, which was deleted at the end
together with its media-index rows; every tap, swipe and key sent in the same shell command as
the focus check; logcat read only with `--pid` of our app. `logcat -v monotonic` tries to read
the kernel log to correct for sleep; the phone refused it ("klogctl: Permission denied") and
nothing was read. The location permission was not granted; no map, log or screenshot of this
work shows the real position (the location numbers above come from the saved private files,
printed as numbers only). No step failed or stopped. Work log: `data/phone/logs/worklog.txt`
(lines marked REVIEW-FIX), outputs `data/phone/logs/review-chain1.out` and `review-chain2.out`.
One trial run (tag `r-test`, border view, 10 swipes) is in `pan.jsonl` and in no table.
