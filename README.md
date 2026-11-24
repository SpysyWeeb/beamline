# Beamline

A driving-first, offline-first navigation app. Search for a place, pick a route,
follow turn-by-turn guidance, all from a downloaded state pack with no signal.

Status: early and in daily use by its author (version 0.1.24, Android). Pennsylvania state packs are
built every week and published at [beamline-packs](https://github.com/SpysyWeeb/beamline-packs); the
app downloads them from there, or installs one from a file. No app store release; APK releases and
in-app updates are being set up.

## Scope

- Android phone first. A comma 3X (Python on Linux) is a possible later target,
  so pack formats stay device-neutral.
- United States only for now. Pennsylvania is the test state, Ohio the
  neighbour for border tests.
- Independent of OsmAnd: none of its code, artwork, file formats or servers.

## How it is put together

| Part | Built on |
|---|---|
| Map drawing | MapLibre Native (OpenGL ES), reading PMTiles files |
| Routing | Valhalla, on the device |
| Guidance | Beamline's navigation session and its own route follower (Ferrostar was dropped on 2026-11-17) |
| Search and saved places | SQLite (FTS5 + R*Tree) index built per state; saved places in one file on the phone |
| Map data | OpenStreetMap extracts from Geofabrik; US Census TIGER/Line and the National Address Database for house numbers |
| Getting states | A catalog of state packs over HTTP (`GET` only, after a tap), or a `.tar` file |

A state pack (`docs/pack-format.md`, format 3) has a map file, routing data, a
search index and, optional, house numbers, built and versioned together.
Nothing else in the app uses the network, and nothing about the user leaves the
phone (`docs/architecture.md` section 4).

## How to build and run

`android/README.md`: building the app, running it on the emulator, adding a
state by download (the development server `packbuilder/catalog/serve_packs.py`)
or from a file, the test extras, and the checks.

## Layout

| Folder | Contents |
|---|---|
| `packbuilder/` | Builds state packs and the download catalog on a PC (Python) |
| `android/` | The Android app |
| `viewer/` | Opens a pack in a browser, for checking packs |
| `assets/` | Source artwork: icons, route shields, position markers |
| `docs/architecture.md` | The app's architecture: modules, contracts, behaviour rules, who owns what; with `docs/architecture-downloads.md` and `docs/architecture-saved-places.md` |
| `docs/design/` | Approved design sketches |
| `docs/experiments/` | Measured results of each experiment and each track's report |
| `data/` | Downloads and build outputs (not in git) |
| `tools/` | Scripts that fetch portable build tools (binaries not in git); `check_contracts.py` checks `docs/architecture.md` against the code and the frozen files against `frozen-files.sha256`; `round/` runs Gradle, adb and the emulator for one track of a parallel round |

## Map data credit

Map data © OpenStreetMap contributors, available under the Open Database
License (https://www.openstreetmap.org/copyright).
