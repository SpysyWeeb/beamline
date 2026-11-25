# Beamline: architecture of the feature round

Date: 2026-11-15. Status: the contract for the feature round. Round 0 (the scaffold), Round G0
(the set-up of in-app downloads) and Round SP0 (the set-up of saved places) are done: every module,
contract file and data file this document and its companion contracts name exists in the tree,
compiles, and is the same as the code shown (checked with `python tools/check_contracts.py`,
appendix B). Companion contracts: `docs/architecture-downloads.md` (getting state packs in the app,
track G) and `docs/architecture-saved-places.md` (the search bar's place, saved places, Home and
Work: tracks S and M, with work for P, N, D and I). Package root `P` =
`io.github.spysyweeb.beamline`. Paths of Kotlin files are under `android/`.

Goal of the round: a working offline prototype on a real phone. Choose your state on the first run
(from a list or a map); the app downloads and installs it. Search a place or address from the bar at
the top of the map (or pick a point), see it, ask for directions, choose Fastest / Simpler
(Shortest until batch D) with Avoid highways / Avoid tolls, add stops, start, be guided with the designed banner, the
driving arrow and the tilted heading-up camera, re-route when off the route, behave sensibly with
poor or lost GPS, arrive. Save Home, Work and named places, go there with one tap, see them on the
map; they stay on the phone and never reach a log. Plus the Night drive icon and splash reveal,
About and licences with a notices file, the first-run notice, Eco hidden, and one pack per state
with map, routing, search and optional addresses, downloaded in the app or added from a file. Maps,
routing and search work with no network at all; the network is used only for the list of states and
the packs (section 4).

How to use this document: section 1 says what exists and who may depend on what; section 2 is
the code every track builds on (it is in the tree as shown); section 3 is the behaviour the
navigation session and the screens must pass; section 5 says who owns which folder and when a
track is done. A companion contract holds one design's journeys, exact texts, formats, rules and
Kotlin contracts in full; this document holds what crosses tracks and points to it. Words like
"must" are binding; numbers marked *(estimate)* are not measurements. Appendix A lists the
findings of the two reviews of the draft, of the review of Round 0 and of the two reviews of Rounds
G0 and SP0, and what became of each.

## 0. How the round runs

1. **Round 0, scaffold: done.** The new modules with their build files, all `settings.gradle.kts`
   includes, all version-catalog entries, the Gradle isolation hook (5.3), the frozen contract
   module `:core:api`, the finished `:core:units`, the additions to `:core:settings` and
   `:core:location`, `:ui:theme` (moved out of `:app`, route blue B), the data files with their
   thresholds (`units.json`, `guidance.json`, `camera.json`, `search_app.json`) and their copies,
   and the public types and stub entry points of every track's module (bodies
   `TODO("track X")`). `./gradlew assembleDebug test` passes from an empty build folder when the
   two search databases are configured (5.3, appendix B).
2. **Round G0, set-up of in-app downloads: done.** The design of `docs/architecture-downloads.md`
   adopted as a companion contract; the modules `:core:download`, `:feature:states` and
   `:demo:states` with their build files, includes and catalog entry (`okhttp`); the contract
   files of that document with `TODO("track G")` bodies; the installer hand-off added to the
   frozen `:core:api` (2.2) with its test; `download_rules.json` with its copy; `:app`'s manifest
   network lines, catalog address and debug-only network security config (4); the contract check
   reading this document and its companions (appendix B).
3. **Round SP0, set-up of saved places: done.** The design of `docs/architecture-saved-places.md`
   adopted as a companion contract; the frozen `:core:api` additions (`PlaceSource.SAVED`,
   `Place.savedId` and a `toString()` that prints no name or point, `SavedPlace`, `SavedKind`,
   `MapScene.saved`, `SavedMarker`) with their test; the contract files of S (`SavedPlaces.kt`,
   `SavedUi.kt`, the new parts of `SearchUi.kt` and `SearchScreens.kt`) and M (`Scene.kt`) with
   `TODO` bodies; the `saved` and `saved_places` sections of `search_app.json` and `camera.json`
   with their copies; the drafts of the three saved-place icons in `assets/` (appendix B).
4. **Seven parallel tracks** P, N, S, D, M, A, G (section 5), one engineer each. Each owns whole
   folders, builds and tests alone, and compiles only against frozen modules plus its own (the
   few listed exceptions in 1.1).
5. **Integration (I, one engineer, alone)** wires everything in `:app`, then the end-to-end test
   of section 8 on the emulator, then reviewers, then the real phone.

## 1. Module map

"JVM" = plain Kotlin/JVM, no Android dependency (unit tests on the JVM). "Frozen" = written in
Round 0 (and, for the installer hand-off of 2.2, Round G0; for saved places in 2.1 and 2.5, Round
SP0) and not edited during the round (changes
only through the orchestrator, additive, between waves; see 5.2).

| Module | Folder (`android/...`) | Package | Kind | Owner this round | What it is |
|---|---|---|---|---|---|
| `:core:api` **new** | `core/api` | `P.api.model`, `P.api.data`, `P.api.nav`, `P.api.map` | JVM | Round 0, G0 and SP0, **frozen** | The shared kernel: places and saved places, installed-data view (with the installer hand-off for downloads), the navigation session interface and its state, the map scene (with saved-place markers) and camera types. No project dependencies. |
| `:core:units` **new** | `core/units` | `P.units` | JVM | Round 0 (complete), **frozen** | The one place where distance, duration and clock texts are made, from `units.json`. |
| `:core:settings` | `core/settings` | `P.settings` | JVM | Round 0 additions, **frozen** | Settings (with route type, avoid flags, include-addresses) and `AppStateStore` (first-run flag). |
| `:core:location` | `core/location` | `P.location` | JVM | Round 0 addition, **frozen** | `Fix`, `PositionSource`, `Clock`, `PuckTracker`; `SharedPositionSource` (one GPS listener for the app). |
| `:ui:theme` **new** | `ui/theme` | `P.ui.theme` | Android lib, Compose | Round 0 (moved from `:app`), **frozen** | Colours (route blue B), the Material theme, the hand-drawn screen icons; the bottom sheets' one frame and height rules (`Sheets.kt`, rule D10; batch F). |
| `:core:packs` | `core/packs` | `P.packs` | JVM | P | Manifest v3, readers, scanner, the archive installer, `StyleComposer`. |
| `:core:installed` **new** | `core/installed` | `P.installed` | JVM | P | `InstalledLibrary`: implements `api.data.InstalledData`; one install/remove flow for all four parts; publishes `InstalledSet` generations; holds changes during guidance. |
| `:routing:packs` | `routing/packs` | `P.routing.packs` | JVM | P | The merged routing tile set (`RoutingPackManager`), used by `:core:installed`. |
| `:routing:core` | `routing/core` | `P.routing.*` | JVM | N | Route model, presets (format 2: route types + options), planner, errors, stop order, OSRM request. |
| `:routing:banner` | `routing/banner` | `P.routing.banner` | JVM | N | Banner model (pure function of a route and `banner.json`). |
| `:routing:valhalla` | `routing/valhalla` | `P.routing.mobile` | Android lib | N (no change expected) | `ValhallaEngine` (valhalla-mobile 0.6.4). |
| `:nav:session` **new** | `nav/session` | `P.nav` | JVM | N | `BeamlineNavigationSession`, its rules (`guidance.json`), route service, follower port and the app's follower (`GeometricFollower`, the only one since Ferrostar was dropped on 2026-11-17), simulated drive and its named scripts, trip store. |
| `:core:search` | `core/search` | `P.search` | JVM | S | Engine, address lookup, `SearchService`; adds `MultiPackSearch`, `SearchHub`, `RecentPlaces`, `SavedPlaces` (the user's saved places and their file, `docs/architecture-saved-places.md`), the fingerprint check, the input cap from data. |
| `:feature:search` **new** | `feature/search` | `P.feature.search` | Android lib, Compose | S | `SearchFeature` (UI-free controller), search bar and screen, place sheet, category icons; `SavedPlacesFeature`: the saved places screen, its dialogs and the map pick. |
| `:feature:drive` **new** | `feature/drive` | `P.feature.drive` | Android lib, Compose | D | `DrivePresenter` (pure), `EventCards`, route-choice sheet, stops editor, turn banner with lanes, trip bar, re-route/GPS/arrival/resume states, notification text. |
| `:map:scene` **new** | `map/scene` | `P.map.scene` | JVM | M | `MapSceneMapper` (trip, puck and saved places to scene) and `CameraDirector` (camera modes), rules in `camera.json`. |
| `:map:layers` **new** | `map/layers` | `P.map.layers` | Android lib, MapLibre | M | `MapLibreController`: route lines, pins, saved-place markers, puck and arrow layers, camera moves, tap and long-press. Marker PNGs and route colours copied in. |
| `:map:demo` **new** | `map/demo` | `P.map.demo` | Android app, not shipped | M | Host for M's device checks (application id `P.mapdemo`). |
| `:feature:about` **new** | `feature/about` | `P.feature.about` | Android lib, Compose | A | Launcher icon (all densities, themed), splash and reveal, About, licences, notices file, first-run notice. |
| `:demo:about` **new** | `demo/about` | `P.about.demo` | Android app, not shipped | A | Host for A's launcher-icon, splash and screen checks (application id `P.aboutdemo`). |
| `:core:download` **new** | `core/download` | `P.download`, `P.download.net` | JVM | G | Catalog format and parser, address rules, `PackHttp` (with `:app`'s `OsmHttp`, the only code that opens network connections, rule N1), catalog cache, `DownloadEngine` (state machine, resume, hashing, unpacking, space), the hand-off to the installer, `download_rules.json` (`docs/architecture-downloads.md` 6.2-6.6). |
| `:feature:states` **new** | `feature/states` | `P.feature.states` (+ `.map`, `.model`) | Android lib, Compose | G | `GetPacks` (in-process controller), `StatesScreen` (the first-run "Choose your state" and the States screen), the drawn US map and its tap rules, `DownloadService` (foreground service, type `dataSync`), the download notifications, the network watcher, the tagging socket factory (`docs/architecture-downloads.md` 6.1, 6.7-6.9). |
| `:demo:states` **new** | `demo/states` | `P.states.demo` | Android app, not shipped | G | Host for G's device checks with a fake `InstalledData` (application id `P.statesdemo`). |
| `:app` | `app` | `P.app` | Android app | Round 0 (theme move), Round G0 (manifest network lines, catalog address, debug network config), then I | Activity, screens and navigation between them (the States screen is G's `StatesScreen`), `AppGraph`, `PackMap` host, `GuidanceService`, `OfflineMapHttp`, manifest. |
| `:routing:demo` | `routing/demo` | | not shipped | N | Kept: `DeviceRoutingTest` is N's on-device regression suite. (`:nav:ferrostar` and `:routing:ferrostarcheck` were deleted on 2026-11-17: the owner dropped Ferrostar.) |
| `:demo:search`, `:core:search-bench` | | | not shipped | S | As today. |

### 1.1 Allowed dependency directions

Arrows point at what a module may depend on; the build files of Round 0 already declare exactly
these. Nothing else is allowed; a new edge needs the orchestrator.

```
:core:api        -> (nothing; kotlinx-coroutines-core only)
:core:units      -> (nothing; kotlinx-serialization-json)
:core:location   -> (as before)
:core:settings   -> (as before)
:ui:theme        -> (Compose only)
:routing:core    -> (as before)
:routing:banner  -> :routing:core
:routing:packs   -> :routing:core
:routing:valhalla-> :routing:core
:core:packs      -> :core:api
:core:installed  -> :core:api, :core:packs, :routing:packs
:core:search     -> :core:api
:nav:session     -> :core:api, :core:location, :core:settings, :routing:core, :routing:banner
:map:scene       -> :core:api, :core:location
:map:layers      -> :core:api                              (+ MapLibre)
:map:demo        -> :map:layers, :map:scene, :core:packs
:feature:search  -> :core:api, :core:search, :core:units, :ui:theme
:feature:drive   -> :core:api, :core:units, :ui:theme
:feature:about   -> :ui:theme                              (+ core-splashscreen, activity-compose)
:demo:about      -> :feature:about, :ui:theme
:core:download   -> :core:api                              (+ OkHttp, kotlinx-serialization-json)
:feature:states  -> :core:api, :core:download, :core:settings, :core:units, :ui:theme
                                                           (+ androidx core, activity-compose, lifecycle-service)
:demo:states     -> :feature:states, :ui:theme
:app             -> everything above except the demos      (wired by I; today :core:packs, :core:settings, :core:location, :ui:theme)
```

Rules: no feature module depends on another feature module; no JVM module depends on an Android
module; `:core:api` stays dependency-free so it can be frozen (it exposes kotlinx-coroutines-core
with `api(...)`; its tests alone also read `:routing:banner`, to check the "same names" claims of
2.3); a module whose public signatures use another module's types declares it with `api(...)`
(`:nav:session` exposes `:core:api`, `:core:location`, `:core:settings`, `:routing:core`,
`:routing:banner`; `:feature:states` exposes `:core:download`, `:core:settings`, `:core:units` and
lifecycle-service, `:core:download` exposes `:core:api`). Only `:core:download` uses OkHttp
directly (rule N1, section 4); `:app` adds it when I writes `OfflineMapHttp`, at the version the app
already resolves.

Cross-track compile edges that remain (the upstream owner must keep these modules compiling after
every edit batch and may not make a source-incompatible change to their public API without the
orchestrator): `:routing:core` (N) is compiled by P through `:routing:packs` and
`:core:installed`; `:core:packs` (P) is compiled by N's `:routing:demo` and M's `:map:demo`;
`:routing:packs` (P) is compiled by N's `:routing:demo` (its androidTest, where
`DeviceRoutingTest` reads the tile lists of the tars). (`:nav:session` was also compiled by A's
`:nav:ferrostar` until that module was deleted on 2026-11-17.)

### 1.2 What runs on which thread

| Thread (one each, named) | Created by | Runs |
|---|---|---|
| main | Android | Compose, MapLibre, camera moves |
| `beamline-nav` | `AppGraph` (single-thread executor as a dispatcher) | every state change of the navigation session; the follower; timers |
| `beamline-routing` | `AppGraph` | every Valhalla call (planner, OSRM request, stop-order matrix); the engine is never used from two threads |
| `beamline-search` | `AppGraph` | every SQLite search call; stale answers are dropped, never cancelled (the bundled SQLite cannot interrupt a query) |
| `Dispatchers.IO` | coroutines | pack install, copy, merge, scans, JSON stores (`saved-places.json` on its store's writer, `docs/architecture-saved-places.md` SP51); downloads (inside `DownloadService`). The one read of a small start-up file before the first frame (`settings.json`, `app-state.json`, the one-line `last-camera.txt`) stays on the main thread by design; their writes are on IO |

## 2. Contracts

Blocks whose first line is `// FILE: <path under android/>` are whole files of the tree, that
first line included. Blocks whose first line is `// EXCERPT: <path>` are an unbroken part of a
longer file. Blocks after a line `<!-- FILE: <path> -->` are whole data files. A stub body
`TODO("track X")` is replaced by that track; nothing else in a contract file changes during the
round (5.2), and `tools/check_contracts.py` checks exactly that: a stub matches any body, in a
`FILE` block and in an `EXCERPT` block alike, every other character must be the same (`--exact`
also counts a filled body as a difference).
Everything else in a track's modules is the track's own. The companion contracts
(`docs/architecture-*.md`) quote their contract files the same way and are checked with this
document; a whole file is quoted in one document only (2.13, 2.14).

### 2.1 Shared model (`:core:api`, frozen)

```kotlin
// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/model/Model.kt
package io.github.spysyweeb.beamline.api.model

import kotlin.math.cos

/** A point in degrees, WGS84. */
data class GeoPoint(val lat: Double, val lon: Double)

/** A box in degrees, in the order pack manifests write it. */
data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(p: GeoPoint): Boolean = p.lon >= west && p.lon <= east && p.lat >= south && p.lat <= north

    /** Grown by [km] on every side (111.32 km per degree of latitude; longitude scaled at the middle latitude). */
    fun grownByKm(km: Double): Bounds {
        val dLat = km / 111.32
        val dLon = km / (111.32 * cos(Math.toRadians((south + north) / 2)).coerceAtLeast(0.01))
        return Bounds(west - dLon, south - dLat, east + dLon, north + dLat)
    }

    companion object {
        /** From a manifest's `[west, south, east, north]`. */
        fun of(wsen: List<Double>): Bounds {
            require(wsen.size == 4) { "bounds need 4 numbers" }
            return Bounds(wsen[0], wsen[1], wsen[2], wsen[3])
        }

        /** The box around [points]; null for none. */
        fun around(points: List<GeoPoint>): Bounds? = if (points.isEmpty()) null else
            Bounds(points.minOf { it.lon }, points.minOf { it.lat }, points.maxOf { it.lon }, points.maxOf { it.lat })
    }
}

/** Where a place came from. */
enum class PlaceSource { SEARCH, RECENT, MAP_POI, MAP_PIN, RESUMED, SAVED }

/**
 * A place the user can go to: a search result, a recent, a POI tapped on the map, a dropped pin, a
 * saved place ([source] SAVED, [savedId] set). [searchId] and [packId] identify a search entry of
 * one pack build (they may not survive a pack rebuild); [name], [subtitle] and [point] are copies
 * and always enough to route and display. [toString] shows only [source] and [savedId], never the
 * name, the subtitle, the point or the search id (privacy rule PV2, docs/architecture-saved-places.md).
 */
data class Place(
    val name: String,
    val point: GeoPoint,
    val source: PlaceSource,
    val subtitle: String? = null,
    val categoryId: String? = null,
    val searchId: Long? = null,
    val packId: String? = null,
    /** The SavedPlace.id this place was made from (SavedPlace.toPlace), or the one it was just saved as
     *  from its own open sheet (SP19, a dropped pin included); null for every other place. */
    val savedId: String? = null,
) {
    override fun toString(): String = "Place(source=$source, savedId=$savedId)"
}
```

Saved places (Round SP0, `docs/architecture-saved-places.md` 5.1): a place the user saved is stored
as a `SavedPlace` and used everywhere as an ordinary `Place` with source SAVED and its `savedId`.
`Place`, `SavedPlace` and `SavedMarker` (2.5) print no name and no point (rule PV2, section 4);
`:core:api`'s `SavedPlaceTest` checks it.

```kotlin
// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/model/Saved.kt
package io.github.spysyweeb.beamline.api.model

/** Which slot a saved place fills: at most one HOME and one WORK. */
enum class SavedKind { HOME, WORK, OTHER }

/**
 * A place the user saved (saved-places.json, format beamline-saved-places/1, written by
 * :core:search SavedPlaces; docs/architecture-saved-places.md). Self-contained: [point],
 * [placeName], [subtitle] and [categoryId] are copies taken when it was saved or moved, so it
 * survives any pack being rebuilt, replaced or removed; it holds no search id and no pack id.
 * [toString] shows only [id] and [kind], never the name or the point (privacy rule PV2).
 */
data class SavedPlace(
    /** "s" and 16 lower-case hex digits; random; kept by rename and move. */
    val id: String,
    val kind: SavedKind,
    /** What lists, sheets, stops and cards show: "Home", "Work", or the user's own name ("Mom's"). */
    val name: String,
    val point: GeoPoint,
    /** The place's own name when saved or moved ("Erie Maritime Museum"); for a point, its coordinates ("42.08418, -80.17321"). */
    val placeName: String,
    /** The place's own subtitle then ("100 East Front Street, Erie"); null when it had none. */
    val subtitle: String? = null,
    /** The search category of the place then ("museum"); null for addresses, streets and points. */
    val categoryId: String? = null,
) {
    /** The second line: [placeName] unless it equals [name] (ignoring case), then [subtitle], joined with ", "; null when empty. */
    val description: String?
        get() = listOfNotNull(placeName.takeUnless { it.equals(name, ignoreCase = true) }, subtitle)
            .joinToString(", ")
            .ifEmpty { null }

    /** The place to plan to, add as a stop, start from or show in a sheet. */
    fun toPlace(): Place =
        Place(name = name, point = point, source = PlaceSource.SAVED, subtitle = description, categoryId = categoryId, savedId = id)

    override fun toString(): String = "SavedPlace(id=$id, kind=$kind)"
}
```

### 2.2 Installed data (`:core:api`, frozen; implemented by P in `:core:installed`)

```kotlin
// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/data/InstalledData.kt
package io.github.spysyweeb.beamline.api.data

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import kotlin.math.floor

/** One installed state pack, as every consumer sees it. Paths are absolute. */
data class PackInfo(
    val packId: String,
    val name: String,
    /** USPS code, "PA". */
    val state: String,
    val osmSnapshot: String,
    val bounds: Bounds,
    /**
     * The credit for the parts installed, from the manifest's per-part attribution: OpenStreetMap
     * for map, routing and search; the address sources (TIGER/Line, NAD, city layers) only when the
     * addresses part is installed. Shown on the map's credit line and in About.
     */
    val attribution: String,
    val folder: String,
    val mapFile: String,
    val mapMinZoom: Int,
    val mapMaxZoom: Int,
    /** Null when the pack has no usable search part ([searchProblem] says why when it has one). */
    val searchFile: String?,
    /** Null when the pack has no addresses part or the user skipped it ([addressesSkipped]). */
    val addressesFile: String?,
    val addressesSkipped: Boolean,
    /** Why the search part is not used ("built with another address normaliser"); null when fine. */
    val searchProblem: String?,
    /** SHA-256 of the ranking rules the search part was built with (search meta ranking_sha256). */
    val searchRankingSha256: String?,
    /** The pack's routing tiles are in the merged tile set ([InstalledSet.routing]). */
    val inRoutingSet: Boolean,
    /** Checksums were verified at install (false: copied in some other way, e.g. adb). */
    val verified: Boolean,
    val bytesOnDisk: Long,
    /**
     * The manifest's pack_version (pack format 3: the build time as YYYYMMDDhhmm, UTC); null for a
     * pack without one. A higher number is a newer build of the same pack_id.
     */
    val packVersion: Long? = null,
)

/** The merged routing tile set of all installed packs (one Valhalla tar, one graph build). */
data class RoutingSet(
    /** valhalla.json to open the engine with (ValhallaEngine.open). */
    val engineConfigFile: String,
    val graphBuild: String,
    val valhallaVersion: String,
    val states: List<String>,
    /** Valhalla level-2 tile ids present (0.25 degree tiles). */
    val level2Tiles: Set<Int>,
) {
    /** Whether a point lies on an installed routing tile. */
    fun covers(p: GeoPoint): Boolean = level2TileId(p) in level2Tiles

    companion object {
        /** Same as :routing:core Geo.tileId(p, 2): row-major from (-90, -180), 0.25 degree tiles. */
        fun level2TileId(p: GeoPoint): Int {
            val cols = (360.0 / 0.25).toInt()
            val row = floor((p.lat + 90.0) / 0.25).toInt()
            val col = floor((p.lon + 180.0) / 0.25).toInt()
            return row * cols + col
        }
    }
}

data class PackIssue(val folder: String, val reason: String)

/**
 * One immutable view of what is installed. A new one, with a higher [generation], is published
 * after every install, update, removal or rescan that changed anything.
 */
data class InstalledSet(
    val generation: Long = 0,
    val scanned: Boolean = false,
    val packs: List<PackInfo> = emptyList(),
    /** Null when no installed pack has routing tiles. */
    val routing: RoutingSet? = null,
    val issues: List<PackIssue> = emptyList(),
    /** Non-null while installs and removals are refused; the text says why ("Not while guiding"). */
    val changesHeldBy: String? = null,
)

/** A pack archive the user picked (system file picker). [open] may be called more than once. */
class PackArchive(val label: String, val open: () -> InputStream)

/**
 * A pack already on this device as a folder (the downloader's): manifest.json and the part files
 * side by side, on the same volume as the packs folder. [dir] is an absolute path.
 */
class PackFolder(val label: String, val dir: String)

/** What an archive holds, read from its manifest only (nothing copied). */
data class ArchiveInfo(
    val packId: String,
    val name: String,
    val state: String,
    val osmSnapshot: String,
    /** Bytes the install needs with every part, the routing merge's peak included. */
    val neededBytes: Long,
    /** Size of the addresses part; null when the archive has none. */
    val addressesBytes: Long?,
    val replacesInstalled: Boolean,
    val freeBytes: Long,
    /** Empty when it can be installed. Free space is never listed here: compare [neededBytes] with [freeBytes]. */
    val problems: List<String>,
)

enum class InstallStep { CHECKING, COPYING, MERGING_ROUTING, FINISHING }

sealed interface InstallStatus {
    data object Idle : InstallStatus
    data class Running(val label: String, val step: InstallStep, val doneBytes: Long, val totalBytes: Long, val file: String?) : InstallStatus
    data class Done(val name: String, val osmSnapshot: String, val skippedParts: List<String>) : InstallStatus
    data class Failed(val label: String, val message: String) : InstallStatus
    data class Cancelled(val label: String) : InstallStatus
}

/** Installed packs for every screen and for the session. Implemented by :core:installed (track P). */
interface InstalledData {
    val installed: StateFlow<InstalledSet>
    val install: StateFlow<InstallStatus>

    /** Rescans (cheap checks only) and finishes or undoes what a killed process left half done. */
    suspend fun refresh()

    /** Reads an archive's manifest; nothing is copied. */
    suspend fun inspect(archive: PackArchive): ArchiveInfo

    /** Starts an install; false when one is running or changes are held. */
    fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean

    /** True when [startInstallFromFolder] is implemented (track P). */
    val installsFromFolder: Boolean get() = false

    /**
     * Starts an install from [folder] that moves its part files into the pack (rename, no copy)
     * after checking each one's size and SHA-256 against the folder's manifest.json, then goes on as
     * [startInstall] does (merge, checks, rename into place, a new generation). The free-space check
     * counts only bytes that are not already in [folder]. On failure or cancel every moved file is
     * moved back into [folder]. Progress and result in [install]; install-record.json says
     * "source": "download". False when an install is running, changes are held, or
     * [installsFromFolder] is false.
     */
    fun startInstallFromFolder(folder: PackFolder, includeAddresses: Boolean): Boolean = false

    /** True when [startAddOptionalParts] and [removeOptionalParts] are implemented (track P). */
    val changesOptionalParts: Boolean get() = false

    /**
     * Adds the optional parts found in [folder] (today only "addresses") to the installed pack whose
     * pack_id and pack_version equal those of [folder]'s manifest.json. Each part file is checked
     * (size and SHA-256 against that manifest) and moved into the pack folder; then the install
     * record (skipped_parts) is replaced atomically and a new generation is published with the
     * pack's addressesFile and attribution updated. No other file of the pack is touched. Progress
     * and result in [install]. False when an install is running, changes are held, no such pack is
     * installed, or [changesOptionalParts] is false.
     */
    fun startAddOptionalParts(folder: PackFolder): Boolean = false

    /**
     * Removes the optional parts of the installed pack [packId]: the install record first, a new
     * generation (search closes the old address handle, rule 3 of section 2.2), then the files.
     * Returns null when removed, else why not (plain words); refused while changes are held.
     */
    suspend fun removeOptionalParts(packId: String): String? = "This version of Beamline can't remove house numbers."

    fun cancelInstall()
    fun dismissInstallResult()

    /** Removes a pack with all its parts. Returns null when removed, else why not (plain words). */
    suspend fun remove(packId: String): String?

    /**
     * Refuses installs and removals until the returned handle is closed (the session holds it
     * while guiding). Returns null, holding nothing, while an install or removal is running: the
     * caller does not start guidance then (rule P5, ProblemKind.PACKS_BUSY).
     */
    fun holdChanges(reason: String): AutoCloseable?
}
```

The members after `startInstall` with default bodies (`installsFromFolder`,
`startInstallFromFolder`, `changesOptionalParts`, `startAddOptionalParts`, `removeOptionalParts`),
`PackFolder` and `PackInfo.packVersion` are the installer hand-off for downloads, added in Round G0
(`docs/architecture-downloads.md` 6.6): P implements them during the round (5.1); until then the
defaults say "not supported", the downloader feeds the archive install a tar made on the fly, and
`:core:api`'s `InstallerHandoffTest` checks the defaults.

The implementation's constructor (fixed; the integrator codes against it):

```kotlin
// FILE: core/installed/src/main/kotlin/io/github/spysyweeb/beamline/installed/InstalledLibrary.kt
package io.github.spysyweeb.beamline.installed

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.packs.InstallRules
import io.github.spysyweeb.beamline.packs.Readers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The installed state packs of this device: one install and removal flow for map, routing (merged
 * into one tile set), search and addresses (section 2.2, pack format 3 in docs/pack-format.md).
 * [packsDir] holds one folder per pack, [routingDir] the merged routing tile set and its
 * valhalla.json. [readers] says what the app can read (ComposeRules.readers()); [rules] are the
 * install rules (assets/rules/packs.json). File work runs on [io]; [freeBytes] is the usable space
 * of a folder.
 */
class InstalledLibrary(
    packsDir: File,
    routingDir: File,
    readers: Readers,
    rules: InstallRules,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    freeBytes: (File) -> Long = { it.usableSpace },
) : InstalledData {
    override val installed: StateFlow<InstalledSet> get() = TODO("track P")
    override val install: StateFlow<InstallStatus> get() = TODO("track P")
    override suspend fun refresh(): Unit = TODO("track P")
    override suspend fun inspect(archive: PackArchive): ArchiveInfo = TODO("track P")
    override fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean = TODO("track P")
    override fun cancelInstall(): Unit = TODO("track P")
    override fun dismissInstallResult(): Unit = TODO("track P")
    override suspend fun remove(packId: String): String? = TODO("track P")
    override fun holdChanges(reason: String): AutoCloseable? = TODO("track P")
}
```

**How each consumer obtains its data** (no one opens a pack file by guessing a path):

| Data | Who opens it | From | Lifetime |
|---|---|---|---|
| Map tiles | `:app` (I) via P's `StyleComposer.compose(style, theme, overview, packs: List<PackInfo>)` (new overload) | `PackInfo.mapFile` (`pmtiles://file://...`) | Recomposed when `generation` or theme changes; MapLibre reloads the style |
| Routing engine | N (`:nav:session`, `EngineHolder`) through `EngineOpener` | `RoutingSet.engineConfigFile` | Opened on first use of a generation on `beamline-routing`; closed by rule 3 |
| Search and addresses | S (`:core:search`, `SearchHub`) via `SearchService.open` | `PackInfo.searchFile`, `addressesFile` | Opened lazily per generation on `beamline-search`; warm-up read at open (decision 6.17) |
| Front doors (item 28) | `:app` (`AppGraph.doorHub`, a second `SearchHub` without warm-up) for N's `doorOf` | `PackInfo.searchFile` (its `poi_door` table) | Opened lazily per generation on `beamline-routing`, on the first plan to a point of interest; closed by rule 3 |
| Credits | `:app` (the map's "© OpenStreetMap" and its "Map data" list, 5.4 item 4) and A (About's "Map data") | `PackInfo.attribution` of every installed pack | Per generation |

**When a pack is added or removed while files are open.** Rules P, N and S implement:

1. P never changes a file in place. An install writes a new folder and a new merged
   `routing.tar` (`routing.tar.new`, then rename); a removal renames the folder aside, then
   deletes it. On Android (Linux) an open file stays readable after it is unlinked or replaced,
   so every handle opened on generation *g* keeps working until it is closed.
2. P publishes generation *g+1* only after the change is complete on disk.
3. N and S hold one handle per generation. On *g+1*, the next call opens the new handle; the old
   handle is closed after the call running on it returns (never during a call). Both are
   confined to their single thread, so "after the running call" is simply "next on the thread".
   In addition the session closes its engine as soon as a new generation arrives while it is
   IDLE (it reopens lazily), and closes it without replacement when `InstalledSet.routing`
   becomes null, so a replaced tile set (about 1.3 GB for PA+OH) is not held open while the app
   idles.
4. While the session is GUIDING, REROUTING or ARRIVED it holds `holdChanges("Not while guiding")`:
   installs and removals are refused with that text, so the routing set cannot change under a
   trip. `holdChanges` returns null while an install or removal is already running; the session
   then refuses Start (rule P5). In PLANNING or CHOOSING a new generation makes the session
   re-plan (rule C5).
5. MapLibre: `:app` recomposes the style; a removed pack's source disappears from the new style
   before its folder is deleted (P deletes the aside folder after publishing *g+1*; `:app`
   applies *g+1* on the main thread; an extra second of an unlinked file is harmless).
6. On JVM tests under Windows, an open file cannot be deleted: tests close handles first.

### 2.3 Navigation session (`:core:api`, frozen; implemented by N)

```kotlin
// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/nav/Trip.kt
package io.github.spysyweeb.beamline.api.nav

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The one navigation session of the process; the phone screens and later Android Auto render it. */
interface NavigationSession {
    /** Current trip; read from any thread. Changes at most once per accepted fix or tick, plus once per intent. */
    val state: StateFlow<TripState>
    /** One-shot happenings (cards, the notification, later voice and chime). Not replayed. */
    val events: SharedFlow<TripEvent>
    /** Thread-safe; handled in order on the session's thread. */
    fun send(intent: NavIntent)
    /** Ends position collection and timers; the session cannot be used afterwards. */
    fun close()
}

enum class Phase {
    /** No trip. The session does not collect positions. */
    IDLE,
    /** Stops known; routes being computed (also after a change of type, options or stops before guidance). */
    PLANNING,
    /** Route choices ready; nothing guided yet. */
    CHOOSING,
    GUIDING,
    /** Off the route (confirmed): a new route is being asked for, or the last attempt failed ([TripState.problem]). */
    REROUTING,
    /** At the destination; ends on Done or when the car leaves (guidance.json arrival.leave_m). */
    ARRIVED,
    /** A trip saved before the process died can be resumed or discarded. */
    RESUME_OFFERED,
}

enum class GpsState {
    /** The session is not collecting positions. */
    OFF,
    /** Collecting, no usable fix yet. */
    WAITING,
    GOOD,
    /** No usable fix for a few seconds; the vehicle is dead-reckoned along the route. */
    WEAK,
    /** No usable fix for longer; the vehicle is shown stale and nothing advances. */
    LOST,
}

enum class Motion { MOVING, STOPPED, UNKNOWN }

sealed interface Origin {
    data object CurrentPosition : Origin
    /** A chosen start (preview, simulation). [headingDeg] picks the carriageway on a divided road. */
    data class FromPlace(val place: Place, val headingDeg: Double? = null) : Origin
}

data class RouteOptions(val avoidHighways: Boolean = false, val avoidTolls: Boolean = false)

/** A route type offered to the user (presets.json route_types with offered = true, in file order). */
data class RouteTypeInfo(val id: String, val label: String)

data class TripStop(val place: Place, val reached: Boolean = false)

/** One distinct route of the choice sheet; types that gave the same route share one choice. */
data class RouteChoice(
    val id: Long,
    /** Route type ids that produced this route, in presets order ("fastest", "simpler"). */
    val typeIds: List<String>,
    val lengthM: Double,
    val timeS: Double,
    val turnCount: Int,
    val hasTolls: Boolean,
    val hasHighways: Boolean,
    /** Names of the two longest stretches, for "via I 90, US 19". */
    val via: List<String>,
    /** The whole route as one line (all legs). */
    val geometry: List<GeoPoint>,
    /** Index into [geometry] where each leg ends (one per stop). */
    val legEnds: List<Int>,
    val bounds: Bounds,
    /** Plain-word warnings ("The route reaches stop 2 0.9 mi from the point you chose."). */
    val warnings: List<String>,
)

/** Where the driven part of the route line ends: after geometry[index], at [point]. */
data class RouteCursor(val index: Int, val point: GeoPoint)

/** The vehicle as the map draws it. */
data class Vehicle(
    val point: GeoPoint,
    /** Route bearing at the snapped point when snapped, else the GPS course; null when unknown. */
    val bearingDeg: Double?,
    val snapped: Boolean,
    val motion: Motion,
    val speedMps: Double?,
    /** Monotonic time of the position (fix or dead-reckoning tick). */
    val elapsedMs: Long,
)

data class Guidance(
    val route: RouteChoice,
    /** Null only until the first progress after a start or a route change. */
    val banner: LiveBanner?,
    val vehicle: Vehicle?,
    val traveled: RouteCursor?,
    val legIndex: Int,
    /** Index into [TripState.stops] of the next stop not reached. */
    val nextStopIndex: Int?,
    val toNextStopM: Double,
    val toNextStopS: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** Off-route fixes are being counted but not yet confirmed (the arrow is off the line). */
    val offRouteSuspected: Boolean,
    /** Failed re-route attempts in a row (0 normally). */
    val rerouteAttempts: Int,
    val simulated: Boolean,
    /**
     * The posted speed limit of the stretch being driven, km/h (the engine's `annotation.maxspeed`
     * at [traveled]; batch A, rule D8); null where it is unknown or there is none.
     */
    val speedLimitKmh: Double? = null,
    /** A lane ending in a turn ahead, shown now (rule B9, batch C1); null when there is none. */
    val laneWarning: LaneWarningView? = null,
    /**
     * The traffic lights on the route ahead of the vehicle, nearest first, one per junction, for the
     * rest of the route (docs/next-round.md items 22 and 39); empty where the routing data knows none.
     */
    val lightsAhead: List<GeoPoint> = emptyList(),
    /** The road being driven, display-ready ("Peach St", "I 90 East"); null where it has no name (item 22). */
    val currentRoad: String? = null,
)

enum class ProblemKind {
    NO_POSITION, OUTSIDE_INSTALLED_MAPS, NO_ROUTE, NO_ROAD_NEAR_STOP, TOO_LONG, TOO_MANY_STOPS,
    /** The installed packs changed under a plan (rule C5), or a stop's state was removed. */
    PACKS_CHANGED,
    /** An install or removal is running; guidance cannot start until it has finished (rule P5). */
    PACKS_BUSY,
    ENGINE,
}

/** A problem in plain words (texts from :routing:core RoutingProblem where one exists). */
data class TripProblem(val kind: ProblemKind, val text: String, val canRetry: Boolean)

data class TripState(
    val phase: Phase = Phase.IDLE,
    /** +1 on every change of stops, type, options or route; answers for an older revision are dropped. */
    val revision: Long = 0,
    val origin: Origin = Origin.CurrentPosition,
    /** The stops in driving order; the last is the destination. */
    val stops: List<TripStop> = emptyList(),
    /** The trip's route type: re-routes, stop changes and trip.json use it (rule C7). */
    val routeType: String = "fastest",
    val options: RouteOptions = RouteOptions(),
    val offeredTypes: List<RouteTypeInfo> = emptyList(),
    /** Filled in CHOOSING (and kept while PLANNING a change); empty while guiding. */
    val choices: List<RouteChoice> = emptyList(),
    val selectedChoiceId: Long? = null,
    /** Non-null in GUIDING, REROUTING and ARRIVED. */
    val guidance: Guidance? = null,
    val gps: GpsState = GpsState.OFF,
    val problem: TripProblem? = null,
    /**
     * An engine request for the current revision is running: planning, a stop, type or option
     * change during guidance (rules C3, C4), or a re-route. Screens show "busy" from it.
     */
    val replanning: Boolean = false,
)
```

```kotlin
// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/nav/Banner.kt
package io.github.spysyweeb.beamline.api.nav

/** Which arrow a maneuver gets (same names as :routing:banner ManeuverIcon; mapped by name). */
enum class ManeuverKind {
    DEPART, ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT,
    SHARP_RIGHT, UTURN_LEFT, UTURN_RIGHT, RAMP_LEFT, RAMP_RIGHT, RAMP_STRAIGHT, EXIT_LEFT, EXIT_RIGHT, FORK_LEFT,
    FORK_RIGHT, FORK_STRAIGHT, MERGE, MERGE_LEFT, MERGE_RIGHT, ROUNDABOUT, ROUNDABOUT_EXIT, FERRY, OTHER,
}

/** One arrow painted on a lane (Valhalla lane bits, :routing:core LaneBits). */
enum class LaneArrow { REVERSE, SHARP_LEFT, LEFT, SLIGHT_LEFT, THROUGH, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, MERGE_TO_LEFT, MERGE_TO_RIGHT, NONE }

/** Same names as :routing:banner LaneMark. */
enum class LaneMark { BEST, ALLOWED, NOT_FOR_ROUTE }

/** One lane, left to right. [usedArrow]: the arrow this route uses, in a best or allowed lane. */
data class LaneView(val arrows: Set<LaneArrow>, val mark: LaneMark, val usedArrow: LaneArrow?)

/** The following turn beside the main one; distance and time of the stretch between the two (route's own estimate). */
data class FollowingView(val kind: ManeuverKind, val primary: String, val distanceM: Double, val timeS: Double)

/**
 * What the banner says about one maneuver; built once per route from :routing:banner. Road names
 * are display-ready (banner.json road_abbreviations applied: "Peach St").
 */
data class BannerView(
    /** Index into the route's maneuvers, all legs flattened (Route.maneuvers). */
    val maneuverIndex: Int,
    val kind: ManeuverKind,
    /** "Exit 24", "Turn left", "Roundabout, 2nd exit", "Arrive, on the right". */
    val primary: String,
    val roads: List<String>,
    val towards: List<String>,
    val exitNumber: String?,
    val roundaboutExit: Int?,
    /** The stretch before the maneuver is flagged highway (motorway) by the engine. */
    val approachOnHighway: Boolean,
    val following: FollowingView?,
    /** Null: no lane row (no lane data, or none marked). */
    val lanes: List<LaneView>?,
)

/** The banner on screen now. */
data class LiveBanner(
    val view: BannerView,
    /** Live distance to the maneuver in metres (held against jitter, rule B3). */
    val distanceM: Double,
    /** Whether the lane row is shown now (rule B5). */
    val showLanes: Boolean,
)

/**
 * A lane that ends in a turn ahead (rule B9, batch C1): the route goes straight through the next
 * intersection, where fewer lanes go on than the road had before its turn lanes began.
 */
data class LaneWarningView(
    /** The intersection's lanes, left to right: BEST for the lanes that go on with the route, NOT_FOR_ROUTE for the others. */
    val lanes: List<LaneView>,
    /** One line: "Right lane turns right: keep left for Peach St". */
    val text: String,
    /** Live distance to the intersection in metres. */
    val distanceM: Double,
)
```

```kotlin
// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/nav/Intents.kt
package io.github.spysyweeb.beamline.api.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place

sealed interface NavIntent {
    /** A new trip to one place from the current position (replaces any trip that is not being guided). */
    data class PlanTo(val place: Place) : NavIntent
    data class SetOrigin(val origin: Origin) : NavIntent
    /** Inserts at [index] of the stops; null = just before the destination (or as the destination when there is none). */
    data class AddStop(val place: Place, val index: Int? = null) : NavIntent
    data class RemoveStop(val index: Int) : NavIntent
    /** Moves a stop not reached; whatever stop ends up last is the destination (rule C1). */
    data class MoveStop(val from: Int, val to: Int) : NavIntent
    /** Reorders the stops not yet reached for the least driving time; the destination stays last. */
    data object OptimizeStopOrder : NavIntent
    data class SetRouteType(val typeId: String) : NavIntent
    data class SetOptions(val options: RouteOptions) : NavIntent
    /** Selects a route card; the trip's route type follows it (rule C7). */
    data class Choose(val choiceId: Long) : NavIntent
    /** Starts guidance on the selected choice; with [simulation] the session drives the route itself. */
    data class Start(val simulation: Simulation? = null) : NavIntent
    /** Asks for a new route from here now (user button). */
    data object Reroute : NavIntent
    /** Retries after a failed plan or re-route. */
    data object Retry : NavIntent
    /** Cancels planning or ends guidance; back to IDLE. */
    data object End : NavIntent
    /**
     * Takes back the End of a guided trip (rule R4): within [END_UNDO_MS] of it, and before any
     * other intent, the stops not reached are re-planned from the current position with the same
     * route type and options, and guidance goes on (a simulated drive stays simulated). Ignored
     * otherwise.
     */
    data object UndoEnd : NavIntent
    /** Leaves ARRIVED. */
    data object Done : NavIntent
    data object Resume : NavIntent
    data object DiscardResume : NavIntent
}

/** How long after End during guidance the app offers "Undo" and the session accepts [NavIntent.UndoEnd] (rule R4). */
const val END_UNDO_MS: Long = 10_000

enum class ReplaceReason { REROUTE, STOPS_CHANGED, OPTIONS_CHANGED, RESUMED }

enum class AnnounceStage { PREPARE, NOW }

sealed interface TripEvent {
    data class GuidanceStarted(val routeId: Long, val simulated: Boolean) : TripEvent
    data class RouteReplaced(val routeId: Long, val reason: ReplaceReason) : TripEvent
    data class StopReached(val stopIndex: Int, val name: String) : TripEvent
    data class Arrived(val name: String) : TripEvent
    data class RerouteFailed(val problem: TripProblem) : TripEvent
    /** For later voice and chime: emitted at guidance.json announce distances; nothing listens yet. */
    data class Announce(val maneuverIndex: Int, val stage: AnnounceStage, val distanceM: Double) : TripEvent
    data object GuidanceEnded : TripEvent
}

/** The built-in drive along the route being guided: for tests, the emulator and the phone. */
data class Simulation(
    /** 1.0 = the route's own speed per maneuver (lengthM / timeS); 4.0 = four times as fast. */
    val speedFactor: Double = 1.0,
    /** Fixes per simulated second. */
    val fixHz: Double = 1.0,
    val accuracyM: Double = 5.0,
    /** Sideways noise in metres (normal distribution, seeded). */
    val lateralNoiseM: Double = 0.0,
    val seed: Long = 1,
    /**
     * A named script that the session builds from the route at Start (section 8: "default",
     * "wrongturn", "gaps", "jitter", "drift", "tunnel"); [script] is added to it. Null: [script] only.
     */
    val scriptName: String? = null,
    val script: List<SimEvent> = emptyList(),
)

/** Something that happens when the simulated car reaches [atM] metres along the route it is driving. */
sealed interface SimEvent {
    val atM: Double
    /** Stands still (speed 0, fixes continue). */
    data class Halt(override val atM: Double, val seconds: Double) : SimEvent
    /** No fixes; the car keeps moving. */
    data class Gap(override val atM: Double, val seconds: Double) : SimEvent
    /** One fix moved sideways. */
    data class Jump(override val atM: Double, val sidewaysM: Double) : SimEvent
    /** Every fix for [seconds] moved [sidewaysM] sideways (positive = right of the route), then back. */
    data class Drift(override val atM: Double, val seconds: Double, val sidewaysM: Double) : SimEvent
    data class PoorAccuracy(override val atM: Double, val seconds: Double, val accuracyM: Double) : SimEvent
    /** One fix delivered with an old timestamp. */
    data class StaleFix(override val atM: Double, val ageS: Double) : SimEvent
    /** Leaves the route along [points]; afterwards the car follows whatever route the session guides then. */
    data class Detour(override val atM: Double, val points: List<GeoPoint>, val speedMps: Double) : SimEvent
}
```

`ManeuverKind` and `LaneMark` have exactly the names of `:routing:banner`'s `ManeuverIcon` and
`LaneMark`, and `RoutingSet.level2TileId` equals `:routing:core`'s `Geo.tileId(p, 2)`; the
`:core:api` tests check all three against the real modules.

### 2.4 Session implementation entry points (`:nav:session`, owned by N)

Constructor and ports are fixed (the integrator and A code against them); everything else in the
module is N's.

```kotlin
// FILE: nav/session/src/main/kotlin/io/github/spysyweeb/beamline/nav/Ports.kt
package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route
import java.io.File

/** Opens the routing engine on a tile set (ValhallaEngine.open on the phone, a recording player in tests). Closed when it is AutoCloseable. */
fun interface EngineOpener {
    fun open(engineConfigFile: File): RoutingEngine
}

/** A planned route as the follower needs it. */
class FollowerRoute(
    val route: Route,
    /** The engine's OSRM-format answer for the same path (ValhallaRequests.osrm of the chosen request). */
    val osrmJson: String,
    /** Which route of [osrmJson] is [route] (0 = main). */
    val osrmRouteIndex: Int,
)

/** Where one fix lies on the route. Indexes are over Route.maneuvers (all legs flattened). */
data class FollowerProgress(
    /** The maneuver whose stretch is being driven (0 = departure); the upcoming maneuver is stepIndex + 1. */
    val stepIndex: Int,
    val legIndex: Int,
    val snapped: LatLon,
    /** Index into Route.shape (flattened) of the segment holding [snapped]. */
    val shapeIndex: Int,
    val routeBearingDeg: Double?,
    /** Fix to [snapped], metres. */
    val lateralM: Double,
    val toManeuverM: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** The follower has passed the last maneuver. */
    val complete: Boolean,
)

/**
 * Follows one route geometrically. Called on the session thread only. Contract (tested in
 * :nav:session with GeometricFollower, the app's follower, and test doubles): the step index advances
 * only by guidance.json `advance` (entry within entry_m of the maneuver, then exit_m past it, with
 * fixes no worse than fixes.decide_max_accuracy_m) or by [advance]; it never goes back.
 */
interface RouteFollower : AutoCloseable {
    fun update(fix: Fix): FollowerProgress
    /** Moves to the next step now (rule B2's forced advance; called once per step to catch up). */
    fun advance(): FollowerProgress
}

fun interface RouteFollowerFactory {
    fun create(route: FollowerRoute, rules: GuidanceRules): RouteFollower
}
```

```kotlin
// FILE: nav/session/src/main/kotlin/io/github/spysyweeb/beamline/nav/GuidanceRules.kt
package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.routing.model.ManeuverType

/**
 * guidance.json (source: packbuilder/routing/guidance.json, copied by android/routing/scripts/sync_data.py).
 * Each field names its JSON key. Parsing and validation: track N.
 */
data class GuidanceRules(
    val fixMaxAgeS: Double,                 // fixes.max_age_s
    val progressMaxAccuracyM: Double,       // fixes.progress_max_accuracy_m
    val decideMaxAccuracyM: Double,         // fixes.decide_max_accuracy_m
    val guidingProviders: List<String>,     // fixes.guiding_providers
    val weakAfterS: Double,                 // gps.weak_after_s
    val lostAfterS: Double,                 // gps.lost_after_s
    val deadReckonMinSpeedMps: Double,      // dead_reckoning.min_speed_mps
    val backstepHoldM: Double,              // snap.backstep_hold_m
    val advanceEntryM: Double,              // advance.entry_m
    val advanceExitM: Double,               // advance.exit_m
    val advancePastM: Double,               // advance.past_m
    val offRouteBaseM: Double,              // off_route.base_m
    val offRouteAccuracyFactor: Double,     // off_route.accuracy_factor
    val offRouteConfirmFixes: Int,          // off_route.confirm_fixes
    val offRouteConfirmS: Double,           // off_route.confirm_s
    val rejoinFixes: Int,                   // off_route.rejoin_fixes
    val wrongWayDeg: Double,                // off_route.wrong_way_deg
    val wrongWayMinSpeedMps: Double,        // off_route.wrong_way_min_speed_mps
    val rerouteMinIntervalS: Double,        // reroute.min_interval_s
    val rerouteRetryS: Double,              // reroute.retry_s
    val rerouteMaxAttempts: Int,            // reroute.max_attempts
    val rerouteHeadingMinSpeedMps: Double,  // reroute.heading_min_speed_mps
    val rerouteOriginRadiusM: Int,          // reroute.origin_radius_m
    val startFarFromRouteM: Double,         // reroute.start_far_from_route_m
    val arriveWithinM: Double,              // arrival.within_m
    val arrivePassedM: Double,              // arrival.passed_m
    val arriveLeaveM: Double,               // arrival.leave_m
    val stopCardS: Double,                  // arrival.stop_card_s
    val distanceHoldM: Double,              // banner.distance_hold_m
    val lanesWithinHighwayM: Double,        // banner.lanes_within_m.highway
    val lanesWithinOtherM: Double,          // banner.lanes_within_m.other
    val stoppedBelowMps: Double,            // motion.stopped_below_mps
    val stoppedAfterS: Double,              // motion.stopped_after_s
    val movingAboveMps: Double,             // motion.moving_above_mps
    val originMaxAgeS: Double,              // planning.origin_max_age_s
    val originMaxAccuracyM: Double,         // planning.origin_max_accuracy_m
    val waitForFixS: Double,                // planning.wait_for_fix_s
    val planCoalesceMs: Long,               // planning.coalesce_ms
    val maxStops: Int,                      // planning.max_stops
    val problemHoldS: Double,               // planning.problem_hold_s
    val announceHighwayM: List<Double>,     // announce.highway_m
    val announceOtherM: List<Double>,       // announce.other_m
    val announceNowS: Double,               // announce.now_s
    val resumeMaxAgeS: Double,              // resume.max_age_s
    val resumeRefreshS: Double,             // resume.refresh_s
    val notificationMinIntervalS: Double,   // notification.min_interval_s
    val tickS: Double,                      // tick_s
    /** lane_warning: rule B9 (batch C1). */
    val laneWarning: LaneWarningRules = LaneWarningRules(),
    /** lead: rule G8 (2026-11-24). */
    val lead: LeadRules = LeadRules(),
) {
    companion object {
        fun parse(text: String): GuidanceRules = GuidanceRulesFile.parse(text)
        fun bundled(): GuidanceRules = GuidanceRulesFile.parse(GuidanceRulesFile.bundledText())
    }
}

/**
 * guidance.json `lane_warning` (rule B9, batch C1): the notice of a lane that ends in a turn shows
 * from max(min_m, lead_s x speed), at most max_m, before the intersection until after_m past it;
 * lookback_m: how far back the road's own lane count is read; max_calls: trace_attributes calls per
 * route at most (nearest first); same_count_evidence_m: road with as many lanes as the intersection
 * that counts as the road's own lane count (else the stretch may be the turn pocket itself);
 * fork_angle_deg (item 37): another road that leaves the junction within this of straight ahead makes
 * it a fork, where the lanes to use are shown though no lane ends.
 */
/**
 * guidance.json `lead` (rule G8; the owner, 2026-11-24: "the location on screen is a little behind
 * where I'm at"). A fix tells where the car was when the fix was taken, and the arrow then glides to
 * it over the time to the next fix (K3), so without this the arrow trails the car by the fix's age
 * plus about a second. The shown position is moved ahead along the route by the speed times (the
 * fix's age + the glide + [extraS]), so the glide ends where the car is when it ends.
 * [minSpeedMps]: no lead slower than this; [maxAccuracyM]: none from a poorer fix; [maxGlideS]: a
 * gap longer than this between two fixes counts as no glide (the map jumps there, K3); [extraS]:
 * the receiver's own delay that the fix's time does not show (to tune on a drive); [maxM]: never
 * more than this ahead of the fix; [stopAt]: never past the next maneuver of these types (the car
 * slows for them, and the arrow must not turn early); never behind the fix.
 */
data class LeadRules(
    val minSpeedMps: Double = 2.2,
    val maxAccuracyM: Double = 30.0,
    val maxGlideS: Double = 3.0,
    val extraS: Double = 0.5,
    val maxM: Double = 60.0,
    val stopAt: Set<ManeuverType> = setOf(
        ManeuverType.LEFT, ManeuverType.RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT,
        ManeuverType.UTURN_LEFT, ManeuverType.UTURN_RIGHT, ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT,
        ManeuverType.FERRY_ENTER, ManeuverType.DESTINATION, ManeuverType.DESTINATION_LEFT, ManeuverType.DESTINATION_RIGHT,
    ),
)

data class LaneWarningRules(
    val leadS: Double = 18.0,
    val minM: Double = 150.0,
    val maxM: Double = 600.0,
    val afterM: Double = 5.0,
    val lookbackM: Double = 500.0,
    val maxCalls: Int = 40,
    val sameCountEvidenceM: Double = 300.0,
    val forkAngleDeg: Double = 50.0,
)
```

```kotlin
// FILE: nav/session/src/main/kotlin/io/github/spysyweeb/beamline/nav/BeamlineNavigationSession.kt
package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.NavigationSession
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.SharedPositionSource
import io.github.spysyweeb.beamline.routing.banner.BannerRules
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The navigation session (rules: section 3 of docs/architecture.md). Created once per process by
 * AppGraph. All state changes run on [navDispatcher] (one thread, "beamline-nav"); engine calls on
 * [routingDispatcher] (one thread, "beamline-routing"). Time comes from [clock] (monotonic; a
 * test's virtual clock in tests, which must agree with the dispatcher's virtual time) and, for
 * trip.json ages only, from [wallClockMs] (System::currentTimeMillis on the phone).
 * Positions are collected only in PLANNING (from the current position), GUIDING, REROUTING and ARRIVED.
 * Route type and options are read from and written to [settings].
 * [holdPackChanges] is InstalledData.holdChanges: null while an install or removal runs.
 * [doorOf] (docs/next-round.md item 28): where customers go into a place, from the search packs
 * (SearchHub.doorAt), or null; called on [routingDispatcher], once per place of a trip.
 * [signalsIn] (item 36): the traffic lights the installed search packs have in the given cells
 * (SearchHub.signalsIn; RouteLights.cell); called on [routingDispatcher], once per guided route.
 * Null (the tests of the rules before): the lights are the engine's alone.
 */
class BeamlineNavigationSession(
    positions: SharedPositionSource,
    clock: Clock,
    wallClockMs: () -> Long,
    installed: StateFlow<InstalledSet>,
    holdPackChanges: (reason: String) -> AutoCloseable?,
    engines: EngineOpener,
    followers: RouteFollowerFactory,
    settings: SettingsStore,
    store: TripStore,
    rules: GuidanceRules,
    presets: PresetSet,
    bannerRules: BannerRules,
    navDispatcher: CoroutineDispatcher,
    routingDispatcher: CoroutineDispatcher,
    doorOf: (Place, InstalledSet) -> GeoPoint? = { _, _ -> null },
    signalsIn: ((InstalledSet, Collection<Long>) -> List<GeoPoint>)? = null,
) : NavigationSession {
    override val state: StateFlow<TripState> get() = TODO("track N")
    override val events: SharedFlow<TripEvent> get() = TODO("track N")
    override fun send(intent: NavIntent): Unit = TODO("track N")
    override fun close(): Unit = TODO("track N")
}

/**
 * What survives process death during a trip (trip.json; format beamline-trip/1). [savedEpochMs]
 * is the wall-clock time of the last write: the session rewrites the file every resume.refresh_s
 * while a trip is guided, so its age says how long ago the trip was last alive (rule R1).
 */
data class TripSnapshot(
    val savedEpochMs: Long,
    val stops: List<TripStop>,
    val routeType: String,
    val options: RouteOptions,
)

/** trip.json in the app's no-backup folder (getNoBackupFilesDir), written atomically (temporary file, fsync, rename). */
class TripStore(file: File) {
    fun save(snapshot: TripSnapshot): Unit = TODO("track N")
    fun load(): TripSnapshot? = TODO("track N")
    fun clear(): Unit = TODO("track N")
}
```

The follower on the phone: `GeometricFollowerFactory()` of `:nav:session` (`GeometricFollower`,
the follower of the JVM tests and the golden trace). Until 2026-11-17 the phone used
`FerrostarFollowerFactory` of `:nav:ferrostar` (Ferrostar 0.59's `NavigationController` behind
the same port, track A, report 17); the owner dropped Ferrostar (`docs/next-round.md`, "The
owner's answers"), and that module and its contract file were deleted. Its block, kept as the
record (no longer a contract file):

```kotlin
// (deleted 2026-11-17) nav/ferrostar/src/main/kotlin/io/github/spysyweeb/beamline/nav/ferrostar/FerrostarFollower.kt
package io.github.spysyweeb.beamline.nav.ferrostar

import io.github.spysyweeb.beamline.nav.FollowerRoute
import io.github.spysyweeb.beamline.nav.GuidanceRules
import io.github.spysyweeb.beamline.nav.RouteFollower
import io.github.spysyweeb.beamline.nav.RouteFollowerFactory

/**
 * RouteFollower on Ferrostar 0.59's NavigationController: snapping and step advance only, no
 * route provider, no deviation tracking (decision 6.14). One follower per guided route.
 */
class FerrostarFollowerFactory : RouteFollowerFactory {
    override fun create(route: FollowerRoute, rules: GuidanceRules): RouteFollower = TODO("track A")
}
```

N's other public pieces (names fixed here, signatures and bodies N's):

- `:routing:core` presets format 2 (data in 2.11): `PresetSet.routeTypes`, `PresetSet.options`,
  `PresetSet.offered(): List<RouteType>`, `PresetSet.compose(typeId: String, optionIds: Set<String>): Preset`
  (returns the format-1 shape the planner runs; `compose(id, emptySet())` equals format 1's preset
  of the same id, and `compose("fastest", setOf("avoid_highways"))` equals format 1's
  `avoid_highways`), `RoutePlanner.plan(request: RouteRequest, preset: Preset): PlanResult`.
  `PresetSet.FORMAT` becomes `beamline-route-presets/2`.
- `:nav:session`: `RouteSimulator` (a `PositionSource` driven by a `Simulation` along the guided
  route, provider `"simulated"`), the named scripts of `Simulation.scriptName` (`default`,
  `wrongturn`, `gaps`, `jitter`, `drift`, `tunnel`; section 8 says what each does), built from the
  route being guided at Start, and `EngineRouteService` (planning and the OSRM request on
  `beamline-routing`).

### 2.5 Map scene and camera (`:core:api` frozen; `:map:scene`, `:map:layers` owned by M)

```kotlin
// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/map/MapScene.kt
package io.github.spysyweeb.beamline.api.map

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import kotlinx.coroutines.flow.SharedFlow

enum class MapTheme { DAY, NIGHT }

data class EdgeInsetsDp(val top: Float = 0f, val bottom: Float = 0f, val start: Float = 0f, val end: Float = 0f)

/** Everything the app draws on top of the map style. Built by :map:scene MapSceneMapper. */
data class MapScene(
    val theme: MapTheme,
    val route: RouteLines? = null,
    val pins: List<MapPin> = emptyList(),
    val marker: VehicleMarker? = null,
    /** The user's saved places (none when hidden): above the labels, below the pins and the vehicle; above the route lines unless [savedBelowRoute]. */
    val saved: List<SavedMarker> = emptyList(),
    /** True while guiding (GUIDING, REROUTING): [saved] is drawn below the route lines, so they never hide the line or the turn. */
    val savedBelowRoute: Boolean = false,
    /**
     * The traffic lights on the guided route ahead of the vehicle (Guidance.lightsAhead; docs/next-round.md
     * item 22): a small traffic-light image on the route line at each, above the line, below the pins
     * and the vehicle. Only for the route being driven, so the map stays calm.
     */
    val lights: List<GeoPoint> = emptyList(),
)

/**
 * One saved place on the map: the style sprite image saved_home, saved_work or saved_place by
 * [kind], [scale] times its sprite size, upright, shown from map zoom [minZoom] on. A tap on it is
 * reported as MapGesture.Tap with pinId = "saved:" + [savedId]. [toString] shows only [savedId] and
 * [kind], never the point (privacy rule PV2).
 */
data class SavedMarker(val savedId: String, val point: GeoPoint, val kind: SavedKind, val minZoom: Double, val scale: Double) {
    override fun toString(): String = "SavedMarker(savedId=$savedId, kind=$kind)"
}

data class RouteLines(
    val chosenId: Long,
    val chosen: List<GeoPoint>,
    /** Driven part of [chosen]; null before guidance. */
    val traveled: RouteCursor?,
    /** The other choices (grey), only while choosing. */
    val alternatives: List<AlternativeLine>,
)

data class AlternativeLine(val id: Long, val points: List<GeoPoint>)

/** RESULT: one of the search results on the map (rule S11), under every other pin; the selected one is the PICKED pin. */
enum class PinKind { DESTINATION, STOP, PICKED, RESULT }

data class MapPin(val id: String, val point: GeoPoint, val kind: PinKind, val number: Int? = null, val reached: Boolean = false)

sealed interface VehicleMarker {
    /**
     * Browsing: puck, compass cone (null = hidden), grey when stale; [bubbleM]: the radius in metres
     * of the soft uncertainty circle around it (rule G7), null = none. Never while guiding (K7).
     */
    data class Puck(val point: GeoPoint, val coneDeg: Double?, val stale: Boolean, val bubbleM: Double? = null) : VehicleMarker
    /**
     * Driving: arrow along [bearingDeg]; lenses lit only when [brakesLit]; the beam is drawn with
     * the night theme only. [glideMs]: how long the move from the previous position animates (the
     * time between the two positions, rule K3).
     */
    data class Arrow(val point: GeoPoint, val bearingDeg: Double, val brakesLit: Boolean, val stale: Boolean, val glideMs: Int) : VehicleMarker
}

enum class CameraMode { BROWSE, BROWSE_FOLLOW, PREVIEW, GUIDE_FOLLOW, GUIDE_FREE, GUIDE_OVERVIEW, ARRIVED }

/** One camera move: [center] (with [zoom]) or [fit]. */
data class CameraTarget(
    val center: GeoPoint? = null,
    val fit: Bounds? = null,
    val zoom: Double? = null,
    val bearingDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val padding: EdgeInsetsDp = EdgeInsetsDp(),
    val animateMs: Int = 0,
)

data class CameraState(val mode: CameraMode = CameraMode.BROWSE, val showRecenter: Boolean = false)

/** A POI label tapped on the map (from the style's POI layers). */
data class MapPoi(val name: String, val kind: String, val point: GeoPoint)

sealed interface MapGesture {
    /** [pinId]: the MapPin.id, or "saved:" + SavedMarker.savedId, under the tap; [poi]: a POI label under it. */
    data class Tap(val point: GeoPoint, val pinId: String?, val poi: MapPoi?) : MapGesture
    data class LongPress(val point: GeoPoint) : MapGesture
    /** Pan, zoom, rotate or tilt by the user. */
    data object CameraMovedByUser : MapGesture
}

/** The map as the app commands it. Implemented on MapLibre by :map:layers (track M); Android-free. */
interface MapController {
    val gestures: SharedFlow<MapGesture>
    /** Replaces what is drawn; cheap when little changed (GeoJSON sources updated, layers kept). */
    fun show(scene: MapScene)
    fun moveCamera(target: CameraTarget)
}
```

```kotlin
// FILE: map/scene/src/main/kotlin/io/github/spysyweeb/beamline/map/scene/Scene.kt
package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.PuckState
import kotlinx.coroutines.flow.StateFlow

/**
 * Trip and puck to what the map draws. One per map surface: it remembers the previous vehicle
 * position, so the arrow's glideMs is the time between the last two positions (rule K3).
 * [savedRules]: camera.json "saved_places" (docs/architecture-saved-places.md, rules SP40-SP43).
 */
class MapSceneMapper(savedRules: SavedMarkerRules = SavedMarkerRules.bundled()) {
    /**
     * [picked]: the place whose sheet is open, or the point being chosen on the map (a PICKED pin).
     * [saved]: the saved places to draw (the app passes none when they are hidden); one that is a
     * stop of [trip] (a stop's place.savedId) is left out; MapScene.savedBelowRoute is true in
     * GUIDING and REROUTING (SP43). The arrow replaces the puck while a trip is guided.
     * [results]: the search results on the map (rule S11), RESULT pins "result:<index>"; the one at
     * the point of [picked] (the selected result, whose sheet is open) is left out: the PICKED pin
     * stands there instead.
     */
    fun scene(trip: TripState, puck: PuckState?, theme: MapTheme, picked: Place?, saved: List<SavedPlace> = emptyList(), results: List<GeoPoint> = emptyList()): MapScene =
        TODO("track M")
}

/** camera.json "saved_places" (the same file as CameraRules, copied by sync_data.py). Field = JSON key. */
data class SavedMarkerRules(
    val homeWorkMinZoom: Double,            // saved_places.home_work_min_zoom
    val otherMinZoom: Double,               // saved_places.other_min_zoom
    val iconScale: Double,                  // saved_places.icon_scale
) {
    companion object {
        fun parse(text: String): SavedMarkerRules = TODO("track M")
        fun bundled(): SavedMarkerRules = TODO("track M")
    }
}

/** camera.json (source: packbuilder/routing/camera.json, copied by android/routing/scripts/sync_data.py). Field = JSON key. */
data class CameraRules(
    val guidePitchDeg: Double,              // guide.pitch_deg
    val guideVehicleFromBottom: Double,     // guide.vehicle_from_bottom (fraction of the free map height)
    val guideZoomBySpeed: List<Pair<Double, Double>>, // guide.zoom_by_speed_mps [[mps, zoom], ...]
    val approachWithinM: Double,            // guide.approach_within_m
    val approachZoom: Double,               // guide.approach_zoom
    val bearingSmoothing: Double,           // guide.bearing_smoothing
    val recenterAfterS: Double,             // guide.recenter_after_s
    val overviewReturnAfterS: Double,       // overview.return_after_s
    val browseFollowZoom: Double,           // browse.follow_zoom
    val previewPaddingDp: Float,            // preview.padding_dp
    val arrivedZoom: Double,                // arrived.zoom
    val transitionMs: Int,                  // transition_ms
    val bearingTurnDeg: Double = 0.0,       // guide.bearing_turn_deg (optional; 0 = plain smoothing)
    val lean: LeanRules? = null,            // guide.lean (optional; absent = the camera never leans into a turn)
) {
    companion object {
        fun parse(text: String): CameraRules = TODO("track M")
        fun bundled(): CameraRules = TODO("track M")
    }
}

/**
 * camera.json guide.lean (docs/next-round.md item 22): approaching a turn the driving camera turns
 * part of the way toward the road the route turns onto, zooms in and tilts up a little, then eases
 * back after the turn. Field = JSON key.
 */
data class LeanRules(
    val withinM: Double,                    // within_m: the lean starts this far before the turn,
    val withinS: Double,                    // within_s: or this many seconds before it at the current speed, whichever is farther
    val bearingShare: Double,               // bearing_share: the share of the turn's angle the map turns before the vehicle does
    val maxBearingDeg: Double,              // max_bearing_deg: and never more than this
    val zoomAdd: Double,                    // zoom_add: zoom levels added at the turn
    val pitchDeg: Double,                   // pitch_deg: the pitch at the turn (guide.pitch_deg elsewhere)
    val minTurnDeg: Double,                 // min_turn_deg: a smaller change of direction is no turn
    val lookM: Double,                      // look_m: the route's direction is read this far before and after the turn
    val holdMs: Long,                       // hold_ms: after the turn the map waits at most this long for the vehicle to turn as far
)

/** Camera modes and moves (rules K1-K7). UI-free: the phone map and later the car map use it the same way. */
class CameraDirector(rules: CameraRules, clock: Clock) {
    /** The move to make now; null = leave the camera where the user put it. */
    val target: StateFlow<CameraTarget?> get() = TODO("track M")
    val state: StateFlow<CameraState> get() = TODO("track M")
    fun onViewport(widthDp: Float, heightDp: Float, insets: EdgeInsetsDp): Unit = TODO("track M")
    fun onTrip(trip: TripState): Unit = TODO("track M")
    fun onPuck(puck: PuckState?): Unit = TODO("track M")
    fun onGesture(gesture: MapGesture): Unit = TODO("track M")
    /** K1/K1a: my-location: not following -> following north up -> following turned by the phone's compass -> following north up. */
    fun onMyLocation(): Unit = TODO("track M")
    fun onRecenter(): Unit = TODO("track M")
    fun onOverview(): Unit = TODO("track M")
    /**
     * The search results on the map (rule S11), [points] nearest first: browsing, the camera fits
     * the nearest of them, as many as fit at map zoom [minZoom] or closer (the zoom from which the
     * installed states draw), never closer than browse.follow_zoom (K1). Cleared like a picked place.
     */
    fun onShowResults(points: List<GeoPoint>, minZoom: Double): Unit = TODO("track M")
    /** A place picked from a search, a list or a sheet: the camera goes to it (K1). */
    fun onShowPlace(point: GeoPoint): Unit = TODO("track M")
    /** The picked place's sheet closed or another place replaced it, or the results on the map closed: the camera stays where it is (K1, S11). */
    fun onPlaceLeft(): Unit = TODO("track M")
    fun tick(): Unit = TODO("track M")
    /** K1a: the map's bearing when the camera stopped (browsing keeps a user's own turn). */
    fun onMapBearing(deg: Double): Unit = TODO("track M")
    /** K1a: a compass reading from the sensor while [compassFollow]: the map turns with it at once. */
    fun onHeading(deg: Float): Unit = TODO("track M")
    /** K1a: the user began turning the map with two fingers: the compass no longer leads. */
    fun onUserRotate(): Unit = TODO("track M")
    /** K1a: true while browsing follows the phone's compass. */
    val compassFollow: StateFlow<Boolean> get() = TODO("track M")
}
```

```kotlin
// FILE: map/layers/src/main/kotlin/io/github/spysyweeb/beamline/map/layers/MapLibreController.kt
package io.github.spysyweeb.beamline.map.layers

import android.content.Context
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.MapController
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import kotlinx.coroutines.flow.SharedFlow
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * MapController on one MapLibreMap (main thread only). Route colours from route_colors.json
 * ("selected": "B"), marker PNGs per assets/markers/SPEC.md, both copied in by sync_art.py.
 */
class MapLibreController(context: Context, map: MapLibreMap) : MapController {
    override val gestures: SharedFlow<MapGesture> get() = TODO("track M")
    override fun show(scene: MapScene): Unit = TODO("track M")
    override fun moveCamera(target: CameraTarget): Unit = TODO("track M")
    /** Re-adds sources and layers after every style (re)load; layers go before the style layer "roads_oneway". */
    fun onStyleLoaded(style: Style, theme: MapTheme): Unit = TODO("track M")
    fun release(): Unit = TODO("track M")
}
```

### 2.6 Search (`:core:search` and `:feature:search`, owned by S)

`MapScene.saved`, `savedBelowRoute` and `SavedMarker` (2.5) and the new parts of `SearchUi.kt` and
`SearchScreens.kt` below are Round SP0's; the contract files of the saved places themselves
(`SavedPlaces.kt`, `SavedUi.kt`) are quoted in `docs/architecture-saved-places.md` (2.14). Every
`iconName` is a sprite name of the app's style bundle (`android/app/src/main/assets/style/sprites/`,
synced by P); `:feature:search` cuts the icon from those sprites in the APK's assets, and 5.1 S says
how its own test APK gets them.

```kotlin
// FILE: core/search/src/main/kotlin/io/github/spysyweeb/beamline/search/RecentPlaces.kt
package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.model.Place
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Recent places, newest first, in recents.json (format beamline-recents/1, written atomically).
 * Same place = same packId+searchId, or same name within 50 m. A broken file reads as empty.
 */
class RecentPlaces(file: File, max: Int = 30) {
    val places: StateFlow<List<Place>> get() = TODO("track S")
    fun add(place: Place): Unit = TODO("track S")
    fun remove(place: Place): Unit = TODO("track S")
    fun clear(): Unit = TODO("track S")
}
```

```kotlin
// FILE: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchUi.kt
package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.search.NearbyPoi
import io.github.spysyweeb.beamline.search.PoiDetails
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.hours.OpenState
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

/** What a pick is for: a new trip, a stop of the current trip, the trip's start point, or a saved place (SaveTarget). */
enum class SearchMode { GO_TO, ADD_STOP, SET_START, SAVE }

/** What a pick in SAVE mode is for (docs/architecture-saved-places.md, rule SP15). */
sealed interface SaveTarget {
    data object Home : SaveTarget
    data object Work : SaveTarget
    /** A new place with a name: the pick returns SearchOutput.SaveNew and the app opens the save dialog. */
    data object NewPlace : SaveTarget
    /** A new point for the saved place [savedId]; its id, kind and name stay. */
    data class Move(val savedId: String) : SaveTarget
}

enum class NearSource { GPS, MAP_CENTER, NONE }

/**
 * Where results are ranked from: a fresh fix (60 s) when there is one, else the map centre.
 * [accuracyM]: the fix's horizontal accuracy (GPS only; null when unknown): "Use my position" needs
 * it at most saved.position_max_accuracy_m (SP23).
 */
data class SearchContext(val near: GeoPoint?, val source: NearSource, val accuracyM: Double? = null)

data class ShortcutUi(val id: String, val label: String, val iconName: String)

/**
 * Batch O3 (docs/next-round.md item 27): one place type of the "View all" grid and of the type picker
 * of "Add a missing place" (one component, [PlaceTypeGrid]): the category [code], its [label], its
 * sprite [iconName] (the same as the map and the result rows) and the [query] a tap in the search types.
 */
data class PlaceTypeUi(val code: String, val label: String, val iconName: String, val query: String)

/** A plain heading of the grid ("Food and drink") and its types. */
data class PlaceTypeGroupUi(val heading: String, val types: List<PlaceTypeUi>)

/**
 * Batch O3 (docs/next-round.md item 26): one of the user's own sent changes (an added place, an edit, a
 * replacement or a closing), shown in search and on the place sheet as if the map had it, until an
 * installed pack does (the app makes these from its outbox and passes them to [SearchFeature.ownPlaces]).
 * [name]: the name now; [oldName]: the pack's name at that spot it replaces (its pack row is not shown);
 * [categoryCode] / [categoryLabel]: its type; [terms]: words besides its name that find it ("food");
 * [overrides]: "opening_hours", "phone", "website" set by the user (a null value: removed; a key not
 * there comes from the pack); [closed]: closed permanently; [osm]: its OSM object. [toString] prints
 * none of it (PV2).
 */
data class OwnPlaceUi(
    val key: String,
    val name: String,
    val point: GeoPoint,
    val categoryCode: String?,
    val categoryLabel: String?,
    val terms: List<String> = emptyList(),
    val oldName: String? = null,
    val overrides: Map<String, String?> = emptyMap(),
    val closed: Boolean = false,
    val osm: String? = null,
) {
    override fun toString(): String = "OwnPlaceUi"
}

/** [iconName]: a sprite name of the style bundle (assets/poi_icons.json, or saved_home / saved_work / saved_place). [note]: "Exact address", "Street only", "Saved", ... */
data class ResultRowUi(
    val key: String, val iconName: String, val title: String, val subtitle: String?, val distanceText: String?, val note: String?,
    /** Open, closing soon or closed now (rule S10); null when the place has no hours or they cannot be read. */
    val hours: HoursUi? = null,
)

/**
 * A place's opening hours now (rule S10, batch B): [state] gives the colour (green, yellow, red),
 * [label] the words always beside it ("Open now", "Closing soon", "Closed now"), [next] the next
 * change ("until 9 PM", "closes at 9 PM", "opens at 7 AM", "opens Mon at 7 AM", "open 24 hours";
 * null when there is none in the week ahead). [week]: Monday to Sunday (the place sheet's tap);
 * [holidayNote]: "Holiday hours may differ" when holiday rules were left out.
 */
data class HoursUi(
    val state: OpenState,
    val label: String,
    val next: String?,
    val week: List<HoursDayUi> = emptyList(),
    val holidayNote: String? = null,
)

/** One day of the week view: "Mon", "8 AM–5 PM" or "Closed" or "Open 24 hours"; [today] is drawn bold. */
data class HoursDayUi(val day: String, val text: String, val today: Boolean)

/**
 * The results of a search shown on the map (rule S11): [rows] and [places] in the same order,
 * nearest first, at most search_app.json on_map.max_results (20); [selected]: the index whose
 * sheet is open (its pin stands out), null while the list shows.
 */
data class ResultsUi(val query: String, val rows: List<ResultRowUi>, val places: List<Place>, val selected: Int? = null)

/** The results view (rule S11). */
sealed interface ResultsEvent {
    /** A row or a result pin: that place's sheet opens (the app moves the map to it, K1). */
    data class Select(val index: Int) : ResultsEvent
    /** The sheet of a result closed: the list shows again. */
    data object Deselect : ResultsEvent
    /** The list's close button or Back: the results and their pins are gone. */
    data object Close : ResultsEvent
}

/**
 * A Home, Work or other saved-place row of the search screen and the saved places screen.
 * [savedId] null: the "Set home" / "Set work" row. [distanceText] only with a GPS position (SP10).
 */
data class SavedRowUi(
    val savedId: String?,
    val kind: SavedKind,
    val iconName: String,
    val title: String,
    val subtitle: String?,
    val distanceText: String?,
    /** "Outside the installed maps", or "Already your destination" (SET_START, SP14; a tap does nothing). */
    val note: String?,
    /** The row's "Edit" and its accessibility label "Edit Mom's" (SP5); null: no Edit (search rows, a pick-only list). */
    val editLabel: String? = null,
)

enum class SearchPhase { START, SEARCHING, RESULTS, NO_RESULTS, NO_SEARCH_DATA }

data class SearchUi(
    val mode: SearchMode = SearchMode.GO_TO,
    val query: String = "",
    val phase: SearchPhase = SearchPhase.START,
    val shortcuts: List<ShortcutUi> = emptyList(),
    val recents: List<ResultRowUi> = emptyList(),
    val results: List<ResultRowUi> = emptyList(),
    /** "Near your position", "Near the map center". */
    val nearText: String? = null,
    val message: String? = null,
    /** SAVE mode: the line above the field ("Set home", "Set work", "Save place", "New location for Mom's"); null otherwise. */
    val title: String? = null,
    /** START: the Home and Work rows (table of 2.1). */
    val slots: List<SavedRowUi> = emptyList(),
    /** START: the subtitle of the "Saved places" row ("None yet", "1 place", "4 places"); null: no row. */
    val savedPlacesText: String? = null,
    /** START in SAVE mode: the "Choose on the map" row. */
    val chooseOnMap: Boolean = false,
    /** Saved places whose name matches the typed text (SP20, SP21), shown above [results]; keys "saved:<id>"; always empty in SAVE mode. */
    val savedResults: List<ResultRowUi> = emptyList(),
    /** GO_TO START: the notice of SP52 or SP53, shown first with "OK"; null otherwise. */
    val savedNotice: String? = null,
    /** START in SAVE mode: the "Use my position" row (a fresh fix within saved.position_max_accuracy_m, SP23). */
    val useMyPosition: Boolean = false,
    /** Raise the keyboard when the screen opens; false in ADD_STOP while driving (SP4). */
    val focusField: Boolean = true,
    /** Batch O3 ("View all", item 27): the grid of every place type shows over the start rows; null: not shown. */
    val allTypes: List<PlaceTypeGroupUi>? = null,
)

sealed interface SearchEvent {
    data class QueryChanged(val text: String) : SearchEvent
    data class ShortcutTapped(val id: String) : SearchEvent
    /** A result, a recent or a saved result ("saved:<id>"). */
    data class RowTapped(val key: String) : SearchEvent
    data class RemoveRecent(val key: String) : SearchEvent
    data object ClearRecents : SearchEvent
    /** "Add a state" on the NO_SEARCH_DATA screen. */
    data object OpenStates : SearchEvent
    /** The Home or Work row: set, a pick (SP12-SP14); empty, SAVE mode for that slot (SP15). */
    data class SlotTapped(val kind: SavedKind) : SearchEvent
    /** The "Saved places" row. */
    data object OpenSavedPlaces : SearchEvent
    /** The "Choose on the map" row (SAVE mode). */
    data object ChooseOnMap : SearchEvent
    /** The "Use my position" row (SAVE mode, SP23). */
    data object UseMyPosition : SearchEvent
    /** "OK" on the notice of SP52 or SP53. */
    data object DismissSavedNotice : SearchEvent
    data object Close : SearchEvent
    /** The keyboard's search action (Enter): in GO_TO while not driving, the matches go on the map (rule S11). */
    data object Submit : SearchEvent
    /** Batch O3: the "View all" chip at the end of the shortcuts: the grid of every place type opens. */
    data object ViewAll : SearchEvent
    /** Batch O3: a type of the grid: its search runs as a chip's does, and the grid closes. */
    data class TypeTapped(val type: PlaceTypeUi) : SearchEvent
    /** Batch O3: the grid's Back. */
    data object CloseViewAll : SearchEvent
}

sealed interface SearchOutput {
    /** Never with mode SAVE: SAVE picks are saved here (Home, Work, Move) or returned as SaveNew. */
    data class Picked(val place: Place, val mode: SearchMode) : SearchOutput
    /** The app opens the States screen. */
    data object OpenStates : SearchOutput
    data object Closed : SearchOutput
    /** GO_TO: a saved place inside the installed maps was tapped; plan the trip now, no sheet (SP12, SP18). */
    data class PlanTrip(val place: Place) : SearchOutput
    /** Show this place's sheet on the map (a saved place outside the installed maps, SP13). */
    data class ShowPlace(val place: Place) : SearchOutput
    /** Open the saved places screen; a row tap there picks for [mode] (SP27). */
    data class OpenSavedPlaces(val mode: SearchMode) : SearchOutput
    /** SAVE mode for SaveTarget.NewPlace: [place] was picked; the app opens the save dialog. */
    data class SaveNew(val place: Place) : SearchOutput
    /** SAVE mode: "Choose on the map" for [target] (SP16); SAVE mode has ended (back at GO_TO START, or done when opened by openToSave, SP25). */
    data class PickOnMap(val target: SaveTarget) : SearchOutput
    /** Enter found several matches (rule S11): the search screen is done; SearchFeature.results holds them and the app fits [places]. */
    data class ShowResults(val places: List<Place>) : SearchOutput
}

/**
 * CALL and WEBSITE (rule S10) last, never while driving: the app opens the dialer or the browser.
 * REPORT is never in [PlaceSheetUi.actions]: the sheet offers it by [PlaceSheetUi.canReport]
 * (docs/online-design.md 4.1, batch O1); EDIT_OSM neither, by [PlaceSheetUi.canEditOsm] (4.2, batch O2);
 * ADD_PLACE neither, by [PlaceSheetUi.canAddPlace] (4.3, batch O3).
 */
enum class PlaceAction { DIRECTIONS, ADD_STOP, OPEN_STATES, SAVE, EDIT, CALL, WEBSITE, REPORT, EDIT_OSM, ADD_PLACE }

data class PlaceSheetUi(
    val place: Place,
    val iconName: String,
    val title: String,
    val subtitle: String?,
    val distanceText: String?,
    /** "Outside the installed maps" when no routing tile covers it. */
    val note: String?,
    /** In display order (SP19); OPEN_STATES then SAVE or EDIT when the place cannot be routed to. */
    val actions: List<PlaceAction>,
    /** "Saved as Home", "Saved as Mom's"; null when not saved or when the title already is the saved name. */
    val savedText: String? = null,
    /** The saved place this is (EDIT acts on it); null when not saved. */
    val savedId: String? = null,
    /** Opening hours now and the week (rule S10); null when OSM has none or the text cannot be read. */
    val hours: HoursUi? = null,
    /** The number the CALL action dials (the first of OSM's `phone`); null: no CALL. */
    val phone: String? = null,
    /** The address the WEBSITE action opens (http or https, the first of OSM's `website`); null: no WEBSITE. */
    val website: String? = null,
    /** The details height (batch G, docs/next-round.md item 20): every number of OSM's `phone`, each on its own line. */
    val phones: List<ContactUi> = emptyList(),
    /** Every web address of OSM's `website` that a browser can open, each on its own line. */
    val websites: List<ContactUi> = emptyList(),
    /** The postal address from the pack ("4662 West Ridge Road, Erie, PA 16506"); null when it has none. */
    val address: String? = null,
    /** What the place is, in words ("Convenience store"); null when the pack does not say. */
    val category: String? = null,
    /** False while guiding (GUIDING, REROUTING): the numbers and web addresses show, but a tap or a long press does nothing (S10). */
    val canOpenOutside: Boolean = true,
    /**
     * "Report a problem" (docs/online-design.md 4.1): the app sets it while "Contribute to
     * OpenStreetMap" is on and no trip is being driven. A dropped pin shows "Report a problem here"
     * beside its buttons; any other place at the details height. Either sends Act(REPORT).
     */
    val canReport: Boolean = false,
    /** Batch O2 (docs/online-design.md 4.2): the OSM object of the place from its pack ("n25479855"), or null. */
    val osm: String? = null,
    /** The pack's own values of the place (hours, phone, website as OSM had them), or null. */
    val poi: PoiDetails? = null,
    /**
     * "Edit on OpenStreetMap" at the details height: the app sets it for a place with [osm] while
     * "Contribute to OpenStreetMap" is on and no trip is being driven. It sends Act(EDIT_OSM).
     */
    val canEditOsm: Boolean = false,
    /**
     * Batch O3 (docs/next-round.md item 26): the place is one of the user's own sent changes not yet in an
     * installed pack; its values above are the user's, and the sheet shows this one small tag ("Your
     * edit · waiting for the map update"); null otherwise.
     */
    val ownTag: String? = null,
    /**
     * Batch O3 (docs/online-design.md 4.3): "Add a missing place" beside "Report a problem here" on a
     * dropped pin; the app sets it with [canReport]. It sends Act(ADD_PLACE).
     */
    val canAddPlace: Boolean = false,
)

/**
 * A phone number or a web address on the place sheet's details height (batch G): [text] as shown
 * ("(814) 555-0123", "example.com/menu", long ones shortened in the middle), [value] what a tap
 * opens (the number dialled, the http(s) address; null: the text is shown but opens nothing) and
 * [copy] what a long press copies (the number as written, the whole address).
 */
data class ContactUi(val text: String, val value: String?, val copy: String)

sealed interface PlaceSheetEvent {
    data class Act(val action: PlaceAction) : PlaceSheetEvent
    /** A tap on a number (CALL) or a web address (WEBSITE) of the details height: the app opens the dialer or the browser with [value], as the buttons do (S10). */
    data class OpenOutside(val action: PlaceAction, val value: String) : PlaceSheetEvent
    data object Close : PlaceSheetEvent
}

/**
 * UI-free search controller (rules S1-S7; saved places SP10-SP31). Queries run on
 * [searchDispatcher]; [ui] is read on the main thread. [saved] is read for the rows and written for
 * SAVE-mode picks of Home, Work and Move.
 */
class SearchFeature(
    installed: StateFlow<InstalledSet>,
    recents: RecentPlaces,
    saved: SavedPlaces,
    units: StateFlow<UnitFormatter>,
    scope: CoroutineScope,
    searchDispatcher: CoroutineDispatcher,
) {
    val ui: StateFlow<SearchUi> get() = impl.ui
    /**
     * GO_TO, ADD_STOP or SET_START; SAVE goes through [openToSave]. [driving]: phase GUIDING,
     * REROUTING or ARRIVED (no keyboard at once, SP4). [destination]: in SET_START, the trip's last
     * stop, whose saved row reads "Already your destination" (SP14).
     */
    fun open(mode: SearchMode, context: SearchContext, driving: Boolean = false, destination: Place? = null): Unit = impl.open(mode, context, driving, destination)
    /** SAVE mode for [target] (from the saved places screen or a sheet's Edit); after the save, on Close or on "Choose on the map", onEvent returns Closed or PickOnMap and this search is done. */
    fun openToSave(target: SaveTarget, context: SearchContext): Unit = impl.openToSave(target, context)
    /** Picked places are added to the recents here, except saved places and SAVE-mode picks (SP31). */
    fun onEvent(event: SearchEvent): SearchOutput? = impl.onEvent(event)
    /**
     * For a search pick, a recent, a tapped POI, a dropped pin or a saved place; [tripActive] (phase
     * PLANNING, CHOOSING, GUIDING or REROUTING) offers ADD_STOP first; [driving] (GUIDING, REROUTING
     * or ARRIVED) leaves out SAVE and EDIT (SP4, SP19); [guiding] (GUIDING or REROUTING) leaves out
     * CALL and WEBSITE (S10: offered again once the trip has arrived).
     */
    fun placeSheet(place: Place, context: SearchContext, tripActive: Boolean, driving: Boolean = false, guiding: Boolean = driving): PlaceSheetUi = impl.placeSheet(place, context, tripActive, driving, guiding)
    fun close(): Unit = impl.close()

    /** The results on the map after Enter (rule S11); null when none are shown. */
    val results: StateFlow<ResultsUi?> get() = impl.results
    /** Outputs that come with an answer after [onEvent] returned: ShowResults, or Picked for a single match (S11). */
    val later: SharedFlow<SearchOutput> get() = impl.later
    /** A row or pin selected (ShowPlace of that result), the sheet closed or the results closed (S11). */
    fun onResultsEvent(event: ResultsEvent): SearchOutput? = impl.onResultsEvent(event)
    /** S11: ends the results when a state one of them came from is no longer in [set] (removed while they show); true when they ended. */
    fun closeResultsGoneFrom(set: InstalledSet): Boolean = impl.closeResultsGoneFrom(set)
    /** S11b: "Search this area": the results' words again around [center], only places inside [area]; they replace the results, the camera stays. The number found (0: the results stay as they were); null when they ended or changed meanwhile, or no pack could be read. */
    suspend fun searchArea(center: GeoPoint, area: Bounds): Int? = impl.searchArea(center, area)
    /** Counts up when hours, phone or website of a place arrived from its pack: a place sheet made before is made again (S10). */
    val detailsLoaded: StateFlow<Int> get() = impl.detailsLoaded
    /** Recomputes the statuses of the results rows at the clock (the app calls it each minute, S10). */
    fun refreshHours(): Unit = impl.refreshHours()
    /**
     * Batch O3 (docs/next-round.md item 26): the user's own sent changes shown as if the map had them
     * (search rows, the place sheet) until an installed pack does; the app sets them from its outbox.
     */
    var ownPlaces: List<OwnPlaceUi>
        get() = impl.ownPlaces
        set(value) = run { impl.ownPlaces = value }
    /** Batch O3: the named POIs of the installed packs within [withinM] of [point], nearest first (on the search thread). */
    suspend fun poisNear(point: GeoPoint, withinM: Double): List<NearbyPoi> = impl.poisNear(point, withinM)
    /** Batch O3: the place types of the installed packs, grouped as the "View all" grid shows them. */
    suspend fun placeTypes(): List<PlaceTypeGroupUi> = impl.placeTypes()
    /** The phone's local date and time the hours are evaluated at (S10); the test extras may set another. */
    var hoursClock: () -> LocalDateTime
        get() = impl.hoursClock
        set(value) = run { impl.hoursClock = value }

    // Track S: the controller behind the contract above (SearchController.kt), searching through a SearchHub.
    private val impl = SearchController(installed, recents, saved, units, scope, searchDispatcher, HubSearcher())

    /** Tests: the same controller with another searcher (a fake, or a hub on chosen files). */
    internal constructor(
        installed: StateFlow<InstalledSet>, recents: RecentPlaces, saved: SavedPlaces, units: StateFlow<UnitFormatter>,
        scope: CoroutineScope, searchDispatcher: CoroutineDispatcher, searcher: PackSearcher,
    ) : this(installed, recents, saved, units, scope, searchDispatcher) {
        impl.searcher = searcher
    }
}
```

```kotlin
// FILE: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchScreens.kt
package io.github.spysyweeb.beamline.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The search pill on the map. While the results of a search show on the map (rule S11) it holds that
 * search's words ([query]) and a button that ends the results ([onClear]); a tap on the words opens
 * the search with them typed (the owner, 2026-11-21: the bar forgot what was searched for).
 */
@Composable
fun SearchBar(onClick: () -> Unit, modifier: Modifier = Modifier, query: String? = null, onClear: () -> Unit = {}): Unit =
    TODO("track S")

@Composable
fun SearchScreen(ui: SearchUi, onEvent: (SearchEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")

@Composable
fun PlaceSheet(ui: PlaceSheetUi, onEvent: (PlaceSheetEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")

/** The results on the map after Enter (rule S11): the list at the bottom, pulled up for more. */
@Composable
fun ResultsSheet(ui: ResultsUi, onEvent: (ResultsEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")

/** The saved places screen (2.6). */
@Composable
fun SavedPlacesScreen(ui: SavedListUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")

/** The Edit menu or a dialog of 2.2-2.6, over whatever is on screen. */
@Composable
fun SavedDialog(ui: SavedDialogUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")

/** The pick prompt at the top (in the search pill's place) and the bar at the bottom, over the map; the app keeps its my-location button above the bar. */
@Composable
fun MapPickBar(ui: MapPickUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track S")
```

The search screen's and the saved places' rules (S's file; copied by S's
`android/scripts/copy_search_rules.py` into `:core:search`'s rules folder, where `:feature:search`
reads it too; the `saved` section is `docs/architecture-saved-places.md` 7.1):

<!-- FILE: packbuilder/search/search_app.json -->
```json
{
  "format": "beamline-search-app/1",
  "note": "How the app's search screen uses the search engine (docs/architecture.md 3.8, rules S1-S7): where results are ranked from, typing, merging the answers of neighbouring state packs, how many results Enter shows on the map and how far (rule S11: up to max_results of the candidates nearest first, none farther than within_km, 25 miles; when none is that close, the nearest nearest_when_none_within anyway; candidates is how many matches the engine is asked for before they are sorted by distance), how near a place without a search id must be to a pack POI of the same name for its hours, phone and website (rule S10, details.match_within_m), recents and the shortcut buttons; and the user's saved places (docs/architecture-saved-places.md, rules SP10-SP31, SP50-SP56): how many, how long a name, when two places are the same, how saved names match typed text, from which map zoom a tapped point may be saved and how precise a position must be for 'Use my position'. Read by android/core/search and android/feature/search (copied into android/core/search by android/scripts/copy_search_rules.py; edit this file, not the copy). Each shortcut's query is a term of packbuilder/search/categories.json; its icon is a sprite name from assets/poi_icons.json. Every number is a first guess for the phone test.",
  "near": {"fix_max_age_s": 60},
  "typing": {"debounce_ms": 120},
  "merge": {"pack_margin_km": 50, "dedupe_m": 100, "limit": 10},
  "on_map": {"max_results": 20, "within_km": 40.23, "nearest_when_none_within": 5, "candidates": 100},
  "details": {"match_within_m": 100},
  "recents": {"max": 30, "same_place_m": 50},
  "saved": {"max_other": 100, "name_max_chars": 40, "same_place_m": 50, "search_min_chars": 1, "search_max_rows": 3,
            "pick_min_zoom": 16, "position_max_accuracy_m": 30},
  "shortcuts": [
    {"id": "gas", "label": "Gas", "query": "gas", "icon": "fuel"},
    {"id": "rest_stops", "label": "Rest stops", "query": "rest stops", "icon": "rest_area"},
    {"id": "food", "label": "Food", "query": "food", "icon": "restaurant"},
    {"id": "coffee", "label": "Coffee", "query": "coffee", "icon": "cafe"},
    {"id": "parking", "label": "Parking", "query": "parking", "icon": "parking"},
    {"id": "ev_charging", "label": "EV charging", "query": "ev charging", "icon": "charging_station"},
    {"id": "hotels", "label": "Hotels", "query": "lodging", "icon": "hotel"}
  ]
}
```

### 2.7 Drive (`:feature:drive`, owned by D)

```kotlin
// FILE: feature/drive/src/main/kotlin/io/github/spysyweeb/beamline/feature/drive/DriveUi.kt
package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.units.UnitFormatter
import java.time.ZoneId

data class StopRowUi(
    val index: Int,
    val title: String,
    val subtitle: String?,
    val reached: Boolean,
    val canRemove: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class RouteCardUi(
    val choiceId: Long,
    /** "Fastest · Simpler" */
    val labels: String,
    val durationText: String,
    val distanceText: String,
    /** "Arrive 3:42 PM" */
    val arriveText: String,
    /** "via I 90, US 19" */
    val viaText: String?,
    /** "Tolls", "Highways" */
    val notes: List<String>,
    val selected: Boolean,
)

data class RouteSheetUi(
    /** "Your position" or the origin's name; tapping it chooses another start (DriveEvent.ChooseStart). */
    val fromText: String,
    val stops: List<StopRowUi>,
    val cards: List<RouteCardUi>,
    val avoidHighways: Boolean,
    val avoidTolls: Boolean,
    val busy: Boolean,
    val problem: String?,
    val canRetry: Boolean,
    val warnings: List<String>,
    val canStart: Boolean,
    /** Why Start is off although a route is chosen ("Allow precise location to start guidance"); null when it is not off for that. */
    val startBlockedText: String?,
)

data class StopsEditorUi(val rows: List<StopRowUi>, val canOptimize: Boolean, val busy: Boolean)

/** The chip beside the main instruction: arrow and "In 0.2 mi" (does not count down). */
data class ThenUi(val kind: ManeuverKind, val distanceText: String)

data class LaneUi(val arrows: Set<LaneArrow>, val mark: LaneMark, val usedArrow: LaneArrow?)

data class BannerUi(
    val kind: ManeuverKind,
    val roundaboutExit: Int?,
    /** "0.3 mi", "400 ft" */
    val distanceText: String,
    /** "Exit 24", "Turn left" */
    val primary: String,
    /** "US 19 / Peach St" (exits), "onto US 19 / Peach St" (turns) */
    val roadsText: String?,
    /** "toward Waterford" */
    val towardsText: String?,
    val then: ThenUi?,
    /** Null: no lane row. */
    val lanes: List<LaneUi>?,
    /**
     * Rule B9 (batch C1): a lane ends in a turn ahead; then [lanes] are that intersection's lanes and
     * this line is shown under them ("Right lane turns right: keep left for Peach St").
     */
    val laneNote: String? = null,
)

sealed interface GuidanceStatusUi {
    data object OnRoute : GuidanceStatusUi
    data object WaitingForGps : GuidanceStatusUi
    /** "Finding a new route..." */
    data object Rerouting : GuidanceStatusUi
    data class RerouteFailed(val text: String, val canRetry: Boolean) : GuidanceStatusUi
    data object GpsWeak : GuidanceStatusUi
    /** "GPS signal lost" */
    data object GpsLost : GuidanceStatusUi
    data class StopReached(val name: String) : GuidanceStatusUi
    /** [awayText]: "Destination 500 ft away" when the route ends more than 100 m from the place (a place tied to a street up to 300 m away). */
    data class Arrived(val name: String, val sideText: String?, val awayText: String? = null) : GuidanceStatusUi
}

data class TripBarUi(
    val arriveText: String,
    val remainingTimeText: String,
    val remainingDistanceText: String,
    /** "Stop 2 · 4.1 mi" */
    val nextStopText: String?,
    val simulated: Boolean,
)

/** [banner] is null in REROUTING: the status card takes its place (rule D7). */
/** How far over the limit the current speed is (rule D8): yellow at 5 mph (8 km/h) over, red at 10 mph (16 km/h). */
enum class SpeedOver { NO, YELLOW, RED }

/** The speed-limit sign and the current speed beside it while guiding (rule D8). */
data class SpeedUi(
    /** The sign's number ("55"); null: no sign (no limit known, none posted, or off the route). */
    val limit: String?,
    /** The current speed from the GPS fixes ("57"), or "–" with a weak or lost fix. */
    val speed: String,
    /** "mph" or "km/h", as Settings' units. */
    val unit: String,
    /** The colour of [speed]'s number; the sign never changes. */
    val over: SpeedOver,
    /**
     * Item 32 (docs/next-round.md): a tap on the pair reports the posted limit ([DriveEvent.SpeedLimitTapped]).
     * The app sets it while "Contribute to OpenStreetMap" is on; off, the pair is not a button.
     */
    val canReport: Boolean = false,
)

data class GuidanceUi(
    val banner: BannerUi?,
    val status: GuidanceStatusUi,
    val tripBar: TripBarUi,
    val showRecenter: Boolean,
    /** Rule D8: in GUIDING and REROUTING; null in ARRIVED. */
    val speed: SpeedUi? = null,
    /**
     * The road being driven ("Peach St", "I 90 East"), in a small label at the bottom of the map
     * (docs/next-round.md item 22: the route line covers that road's own name on the map); null where
     * the road has no name, off the route and in ARRIVED.
     */
    val currentRoad: String? = null,
)

data class ResumeUi(val destinationName: String, val stopsLeft: Int)

/** Everything the drive feature draws. */
data class DriveUi(
    val planning: RouteSheetUi? = null,
    val stopsEditor: StopsEditorUi? = null,
    val guidance: GuidanceUi? = null,
    val resume: ResumeUi? = null,
)

sealed interface DriveEvent {
    /** Sent to the session unchanged (route choice, options, start, simulate, end, stops, retry, done, resume). */
    data class Nav(val intent: NavIntent) : DriveEvent
    data object Recenter : DriveEvent
    data object ShowOverview : DriveEvent
    data object OpenStopsEditor : DriveEvent
    data object CloseStopsEditor : DriveEvent
    /** Opens search in ADD_STOP mode; the place comes back as NavIntent.AddStop. */
    data object SearchForStop : DriveEvent
    /** Opens search in SET_START mode; the place comes back as NavIntent.SetOrigin. */
    data object ChooseStart : DriveEvent
    /**
     * The route sheet's "Allow precise location to start guidance" was tapped (section 4: Start is
     * off without precise location and the app offers to ask for it again): the app asks for precise
     * location, or points to the app's settings when Android no longer shows the question.
     */
    data object AskPreciseLocation : DriveEvent
    /**
     * Item 32: the speed sign or the speed beside it was tapped ([SpeedUi.canReport]): the app offers the
     * posted limits to pick from; the pick is kept for later, nothing is sent while driving.
     */
    data object SpeedLimitTapped : DriveEvent
}

data class GuidanceNotificationText(val title: String, val text: String)

/**
 * Which one-shot card is on screen: the latest StopReached, Arrived or RerouteFailed event, for
 * [cardMs] (guidance.json arrival.stop_card_s) after it came. Times are monotonic milliseconds.
 */
class EventCards(private val cardMs: Long) {
    fun onEvent(event: TripEvent, nowMs: Long): Unit = TODO("track D")
    fun shown(nowMs: Long): TripEvent? = TODO("track D")
}

/** Pure: TripState (+ units, clock, camera) to what is drawn. Rules D1-D7. */
object DrivePresenter {
    /**
     * [shownEvent]: EventCards.shown(now). [locationAllowed]: precise location is granted (an
     * approximate-only grant counts as not allowed); without it Start is off with a reason.
     */
    fun present(
        trip: TripState,
        units: UnitFormatter,
        nowEpochMs: Long,
        zone: ZoneId,
        camera: CameraState,
        stopsEditorOpen: Boolean,
        shownEvent: TripEvent?,
        locationAllowed: Boolean,
    ): DriveUi = TODO("track D")

    /** Title and text of the guidance notification; null outside GUIDING, REROUTING and ARRIVED. */
    fun notification(trip: TripState, units: UnitFormatter): GuidanceNotificationText? = TODO("track D")
}
```

```kotlin
// FILE: feature/drive/src/main/kotlin/io/github/spysyweeb/beamline/feature/drive/DriveOverlay.kt
package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp

/**
 * Banner at the top, sheets and the trip bar at the bottom; reports the space it covers so the camera can pad.
 * [creditShown]: the map credit's short line is showing at the bottom start (rule D8 keeps the speed pair above it).
 */
@Composable
fun DriveOverlay(ui: DriveUi, onEvent: (DriveEvent) -> Unit, onInsets: (EdgeInsetsDp) -> Unit, modifier: Modifier = Modifier, creditShown: Boolean = false): Unit =
    TODO("track D")
```

### 2.8 About and identity (`:feature:about`, owned by A)

```kotlin
// FILE: feature/about/src/main/kotlin/io/github/spysyweeb/beamline/feature/about/AboutUi.kt
package io.github.spysyweeb.beamline.feature.about

/** Raise when the first-run text changes: the notice is shown again. */
const val FIRST_RUN_NOTICE_VERSION = 1

data class AboutPackUi(val name: String, val osmSnapshot: String, val sizeText: String)

data class AboutUi(
    val appName: String,
    /** "0.1.0 (3)" */
    val versionText: String,
    val buildText: String?,
    /** "Map style 007a6f98c1f9" */
    val styleText: String,
    val packs: List<AboutPackUi>,
    /**
     * "Map data © OpenStreetMap contributors, ODbL 1.0", "US overview: Natural Earth; US Census
     * Bureau", then each installed pack's PackInfo.attribution (address sources included only where
     * the house numbers were installed).
     */
    val dataCredits: List<String>,
)

sealed interface AboutEvent {
    data object OpenLicences : AboutEvent
    data object OpenDriverNotice : AboutEvent
    data object Back : AboutEvent
}

/** One shipped component; [licence] is an SPDX expression; [textAsset] is its licence text under assets/notices/. */
data class NoticeEntry(
    val name: String,
    val version: String?,
    val licence: String,
    val copyright: String?,
    val url: String?,
    val textAsset: String,
)

/** assets/notices/notices.json; [fullTextAsset] = assets/notices/NOTICES.txt (everything in one file). */
data class Notices(val entries: List<NoticeEntry>, val fullTextAsset: String) {
    companion object {
        fun parse(json: String): Notices = TODO("track A")
    }
}
```

```kotlin
// FILE: feature/about/src/main/kotlin/io/github/spysyweeb/beamline/feature/about/AboutScreens.kt
package io.github.spysyweeb.beamline.feature.about

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** The system splash (core-splashscreen) with the Night drive icon; held while [keepOnScreen] is true. */
object Splash {
    fun install(activity: ComponentActivity, keepOnScreen: () -> Boolean): Unit = TODO("track A")
}

/** The reveal over the map: the icon stays put while a circle opens to the scene, about 0.7 s, held until [mapReady] (at most 2 s). */
@Composable
fun SplashReveal(mapReady: Boolean, onFinished: () -> Unit, modifier: Modifier = Modifier): Unit = TODO("track A")

@Composable
fun FirstRunNotice(onAccept: () -> Unit, modifier: Modifier = Modifier): Unit = TODO("track A")

@Composable
fun AboutScreen(ui: AboutUi, onEvent: (AboutEvent) -> Unit, modifier: Modifier = Modifier): Unit = TODO("track A")

@Composable
fun LicencesScreen(notices: Notices, onBack: () -> Unit, modifier: Modifier = Modifier): Unit = TODO("track A")
```

Resources A adds to `:feature:about`: `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`
(adaptive, with the monochrome layer), `@style/Theme.Beamline.Starting`.

### 2.9 Formatting exists once (`:core:units`, Round 0, frozen)

Every distance, duration and clock text on any screen (banner, chip, trip bar, route cards, search
results, notification; About sizes excepted) comes from `UnitFormatter`. No other module formats a
distance. `banner.json`'s `distance_text` moved to `units.json` (N removes it from `banner.json`
and `FollowingTurn.distanceText` from `:routing:banner` during the round; until then nothing reads
them on screen). The clock follows the phone's 12/24-hour setting (`clock24h`), not the units
setting (decision 6.32). `Settings.units` MILES maps to IMPERIAL, KILOMETRES to METRIC (one line
in `:app`).

```kotlin
// FILE: core/units/src/main/kotlin/io/github/spysyweeb/beamline/units/Units.kt
package io.github.spysyweeb.beamline.units

import java.time.ZoneId

enum class UnitSystem { IMPERIAL, METRIC }

/** units.json (source: packbuilder/routing/units.json, copied by android/routing/scripts/sync_data.py). */
class UnitRules private constructor(internal val table: UnitTable) {
    companion object {
        const val FORMAT = "beamline-units/1"
        fun parse(text: String): UnitRules = UnitRules(UnitTable.parse(text))
        fun bundled(): UnitRules = parse(UnitTable.bundledText())
    }
}

/**
 * Every distance, duration and clock text on screen comes from here (section 2.9). [system] follows
 * Settings.units (MILES = IMPERIAL); [clock24h] follows the phone's 12/24-hour setting, not the units.
 */
class UnitFormatter(val rules: UnitRules, val system: UnitSystem, val clock24h: Boolean = false) {
    /** "250 ft", "0.2 mi", "12 mi" / "350 m", "1.4 km"; never goes up as the distance shrinks. */
    fun distance(m: Double): String = rules.table.distance(system, m)
    /** "In 0.2 mi" (units.json following_prefix). */
    fun distanceIn(m: Double): String = rules.table.followingPrefix + distance(m)
    /** "< 1 min", "12 min", "1 h", "1 h 5 min" (rounded to the nearest minute). */
    fun duration(s: Double): String = rules.table.duration(s)
    /** "3:42 PM" (12-hour clock) or "15:42" (24-hour clock), in [zone]. */
    fun clockTime(epochMs: Long, zone: ZoneId): String = rules.table.clock(clock24h, epochMs, zone)
}
```

The rules (source `packbuilder/routing/units.json`, copied by `android/routing/scripts/sync_data.py`;
the imperial table is the former `banner.json` `distance_text`, unchanged):

<!-- FILE: packbuilder/routing/units.json -->
```json
{
  "format": "beamline-units/1",
  "note": "How the app writes distances, durations and clock times (docs/architecture.md 2.9). Read by android/core/units (copied there by android/routing/scripts/sync_data.py; edit this file, not the copy). The imperial table is the one banner.json distance_text used: under 100 ft in steps of 10 ft, up to 500 ft in steps of 50 ft, then tenths of a mile up to 9.9 mi, then whole miles. Each step ends where the next one's first text begins, so a text never goes back up as the distance shrinks (500 ft is followed by 0.1 mi; 9.9 mi by 10 mi). Metric: under 100 m in steps of 10 m, up to 950 m in steps of 50 m, then tenths of a kilometre up to 9.9 km, then whole kilometres. Durations are rounded to the nearest minute. The clock follows the phone's 12/24-hour setting, not the unit system.",
  "distance": {
    "imperial": [
      {"below_m": 30.48, "unit": "ft", "round_to": 10},
      {"below_m": 152.4, "unit": "ft", "round_to": 50},
      {"below_m": 16012.9728, "unit": "mi", "decimals": 1},
      {"unit": "mi", "decimals": 0}
    ],
    "metric": [
      {"below_m": 100, "unit": "m", "round_to": 10},
      {"below_m": 950, "unit": "m", "round_to": 50},
      {"below_m": 9950, "unit": "km", "decimals": 1},
      {"unit": "km", "decimals": 0}
    ]
  },
  "following_prefix": "In ",
  "duration": {"under_one_minute": "< 1 min", "minutes": "{m} min", "hours_minutes": "{h} h {m} min", "hours": "{h} h"},
  "clock": {"h12": "h:mm a", "h24": "HH:mm", "locale": "en-US"}
}
```

The implementation (`UnitTable.kt`, internal) and its tests are in the tree: the exit 24 texts
("2.6 mi", "In 0.3 mi", "In 0.2 mi", "400 ft", "50 ft", "3.3 mi"), metric texts, a 0-40 km sweep in
10 cm steps for both tables (a shrinking distance never shows a larger text), durations at 29 s,
30 s, 59 min 31 s and 3,900 s, clock texts in `America/New_York` for both clock settings, refusal
of a broken file, and the copy equal to its source.

### 2.10 Settings, app state and location (Round 0, frozen)

`Settings` gained four fields (JSON keys `route_type`, `avoid_highways`, `avoid_tolls`,
`include_addresses`). Unknown or broken values fall back to the default, as before. The store
keeps `routeType` as written: it does not know the presets, so the session checks the id against
the offered types and falls back to the presets' default (rule P4). The session writes the first
three; the States screen (G) reads and writes the last: it is the default of its "House numbers"
box, and changing the box writes it.

**Map style (batch A, 2026-11-18; the owner's decision of 2026-11-16, docs/next-round.md item 4).**
Settings offers Automatic, Same as phone, Day and Night ("Automatic: Day or night with sunrise and
sunset", "Same as phone: Day or night with the phone's dark mode"). Automatic (stored
`"automatic"`) follows the sun: `SunRule` in `:core:settings` (`Sun.kt`, NOAA's solar calculator
equations, public domain, no network) shows the night map from civil dusk to civil dawn (the sun 6
degrees below the horizon) with a margin of 0.5 degrees on each side once a theme is shown (about 2
to 3 minutes at Erie), so it does not switch back and forth at the boundary; it looks from the last
known position (`AndroidPositionSource.lastKnownPoint`), else the middle of the first installed
state, else it has no answer and Automatic follows the phone. `:app` runs it while the activity is
started (so also when the app comes to the front), every minute or at the switch when that comes
sooner, and when the installed states change; nothing of it is logged (PV2). Same as phone
(stored `"phone"`) is what Automatic was up to version 0.1.1. **A settings file written before keeps
working:** its `"automatic"` reads as the new Automatic (it now follows the sun).

```kotlin
// EXCERPT: core/settings/src/main/kotlin/io/github/spysyweeb/beamline/settings/Settings.kt
enum class MapStyle(override val key: String) : Choice {
    /**
     * Follows sunrise and sunset ([SunRule]; batch A, 2026-11-18). A file written before, where
     * "automatic" meant the phone's dark mode, reads as this new Automatic.
     */
    AUTOMATIC("automatic"),
    /** Follows the system's dark mode (what Automatic did up to version 0.1.1). */
    SAME_AS_PHONE("phone"),
    DAY("day"),
    NIGHT("night"),
}
```

```kotlin
// EXCERPT: core/settings/src/main/kotlin/io/github/spysyweeb/beamline/settings/Settings.kt
    fun nightMap(systemDark: Boolean, sunDark: Boolean? = null): Boolean = when (mapStyle) {
        MapStyle.AUTOMATIC -> sunDark ?: systemDark
        MapStyle.SAME_AS_PHONE -> systemDark
        MapStyle.DAY -> false
        MapStyle.NIGHT -> true
    }
```

```kotlin
// EXCERPT: core/settings/src/main/kotlin/io/github/spysyweeb/beamline/settings/Settings.kt
data class Settings(
    val mapStyle: MapStyle = MapStyle.AUTOMATIC,
    val frameRate: FrameRate = FrameRate.SCREEN,
    val guidanceSound: GuidanceSound = GuidanceSound.SILENT,
    val units: Units = Units.MILES,
    /**
     * The route type last chosen (a presets.json route type id). Stored as given: the navigation
     * session checks it against the offered types and falls back to the presets' default.
     */
    val routeType: String = "fastest",
    val avoidHighways: Boolean = false,
    val avoidTolls: Boolean = false,
    /** Whether the "House numbers" box is ticked when a state is added. */
    val includeAddresses: Boolean = true,
) {
```

```kotlin
// FILE: core/settings/src/main/kotlin/io/github/spysyweeb/beamline/settings/AppState.kt
package io.github.spysyweeb.beamline.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/** Things the app remembers that are not choices in Settings. */
data class AppState(
    /** The version of the first-run notice the user accepted; 0 = never. */
    val firstRunNoticeAccepted: Int = 0,
)

/**
 * app-state.json (`{"first_run_notice_accepted": 1}`), written atomically like settings.json.
 * A missing, broken or foreign file reads as the defaults.
 */
class AppStateStore(private val file: File) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    @Synchronized
    fun update(change: (AppState) -> AppState) {
        val next = change(_state.value)
        if (next == _state.value) return
        save(next)
        _state.value = next
    }

    private fun load(): AppState = try {
        val obj = if (file.isFile) Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject else null
        AppState(firstRunNoticeAccepted = (obj?.get(FIRST_RUN_NOTICE_ACCEPTED) as? JsonPrimitive)?.intOrNull ?: 0)
    } catch (e: Exception) {
        AppState()
    }

    private fun save(s: AppState) {
        val text = JsonObject(mapOf(FIRST_RUN_NOTICE_ACCEPTED to JsonPrimitive(s.firstRunNoticeAccepted))).toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("cannot write ${file.name}")
        }
    }

    companion object {
        const val FIRST_RUN_NOTICE_ACCEPTED = "first_run_notice_accepted"
    }
}
```

```kotlin
// FILE: core/location/src/main/kotlin/io/github/spysyweeb/beamline/location/SharedPositionSource.kt
package io.github.spysyweeb.beamline.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn

/**
 * One position listener for the whole app: every collector (the puck, the session) shares one
 * upstream collection, started by the first collector and stopped [stopAfterMs] after the last
 * one leaves. [latest] is the last fix any collector saw (raw, unfiltered).
 *
 * When the upstream ends or fails while collectors stay (no location permission yet, or the
 * permission was withdrawn), it is collected again after [restartAfterMs], so a permission
 * granted later reaches the collectors that are already waiting.
 */
class SharedPositionSource(
    upstream: PositionSource,
    scope: CoroutineScope,
    stopAfterMs: Long = 5_000,
    restartAfterMs: Long = 2_000,
) : PositionSource {
    private val _latest = MutableStateFlow<Fix?>(null)
    val latest: StateFlow<Fix?> = _latest.asStateFlow()
    private val shared = flow {
        while (true) {
            emitAll(upstream.fixes().catch { })
            delay(restartAfterMs)
        }
    }
        .onEach { _latest.value = it }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopAfterMs), replay = 0)

    override fun fixes(): Flow<Fix> = shared
}
```

`:ui:theme` holds `BeamlineColors`, `BeamlineTheme(night)` and `BeamlineIcons`, moved from `:app`
unchanged except the route blue, now candidate B (the owner's choice in
`assets/route_colors.json`):

```kotlin
// EXCERPT: ui/theme/src/main/kotlin/io/github/spysyweeb/beamline/ui/theme/Theme.kt
object BeamlineColors {
    val routeDay = Color(0xFF4A8CF7)
    val routeNight = Color(0xFF3A80F0)
```

### 2.11 Data contracts (files)

**Pack format version 3** (P updates `docs/pack-format.md` and its Python reader to exactly this):

- Archive: one uncompressed ustar `.tar` per state, `manifest.json` first, then the small parts
  (`routing.json`), then the large ones. File name `us-pa-<osm date>.tar`. The archive stays the
  file format ("Use a file instead"); downloads fetch each part as its own file (gzip where it
  pays) by the catalog of `docs/architecture-downloads.md` section 2 (G writes the reference,
  `docs/catalog-format.md`).
- `manifest_version: 3`. Version 2 packs are refused with "an old pack format; rebuild the pack".
  Top-level `pack_version` (integer, the build time `YYYYMMDDhhmm` UTC of `created_utc`): a higher
  number is a newer build of the same `pack_id` (read into `PackInfo.packVersion`).
- Parts (each with `file`, `format`, `format_version`, `bytes`, `sha256` as in v2, and its own
  `attribution`):

| Part | Required | File | format / version | Extra fields |
|---|---|---|---|---|
| `map` | yes | `map.pmtiles` | `pmtiles` / 3 | v2's, plus `tile_type` (`mvt`), `schema_extensions` (`lanes_minzoom`, `top_zoom`), `clip` (source file name, SHA-256, state) |
| `routing` | yes | `routing.tar` | `valhalla-tar` / 1 | `valhalla_version`, `graph_build` |
| `routing_index` | yes with `routing` | `routing.json` | the routing builder's manifest format / 1 | none (the file lists every tile with its SHA-256) |
| `search` | yes | `search.sqlite` | the search format / 1 | `addr_key_table_version`, `addr_key_fingerprint`, `ranking_sha256` |
| `addresses` | no; skippable at install | `addresses.sqlite` | the addresses format / 1 | `normalisation_table_version`, `key_fingerprint`, `variant` (`slim`); `attribution` names every address source in the file (OpenStreetMap, TIGER/Line "U.S. Census Bureau", USDOT NAD, City of Scranton) |

- `PackInfo.attribution` joins the attributions of the parts installed: a state added without
  house numbers does not credit the address sources.
- Installed layout: `packs/<pack_id>/` holds `manifest.json`, `map.pmtiles`, `search.sqlite`,
  `addresses.sqlite` (unless skipped), `routing.json`, `install-record.json`
  (`{"installed_utc","verified":"sha256","source":"archive","skipped_parts":[],"routing_merged":true}`;
  `source` is `download` for a pack installed from a download folder).
  **The per-state `routing.tar` is not kept**: its tiles live in the one merged tile set
  `routing/routing.tar` beside `packs/` (decision 6.3). Invariant P keeps: the states in the merged
  tile set are exactly the installed packs whose record says `routing_merged`.
- Format identifiers: the existing ones keep what the rename committed (`beamline-...`); formats
  new in this round: `beamline-units/1`, `beamline-guidance/1`, `beamline-camera/1`,
  `beamline-search-app/1`, `beamline-trip/1`, `beamline-recents/1`, `beamline-route-presets/2`, and
  for downloads (`docs/architecture-downloads.md`) `beamline-catalog/1`, `beamline-download-rules/1`,
  `beamline-download-job/1`, `beamline-state-shapes/1`, and for saved places
  (`docs/architecture-saved-places.md` section 3) `beamline-saved-places/1`.

**Fingerprints** (decision 6.11). `fingerprint = sha256_hex( for each spelling s in
packbuilder/addresses/addrlib/fingerprint_spellings.json, in order: utf8(s + "\t" + street_key(s) + "\n") )`.
The search builder writes it to search `meta.addr_key_fingerprint`, the address builder to
addresses `meta.key_fingerprint`, the assembler copies both into the manifest and refuses a pair
that differs. The app's port computes the same over its copy of the list at open and marks the
search part unusable on a mismatch (`PackInfo.searchProblem`); map and routing stay usable. Every
hash the builders record (normaliser sources, rule files, `ranking_sha256`) is over LF-normalised text.

**Presets format 2** (`packbuilder/routing/presets.json`, N; the route_lib reference and the
Kotlin planner change together):

```json
{
  "format": "beamline-route-presets/2",
  "engine": "valhalla", "valhalla_version": "3.9.1",
  "requires_config": {"service_limits.allow_hard_exclusions": true, "service_limits.max_alternates": 2},
  "checks": {"warn_if_endpoint_gap_m_over": 1000},
  "default": {"route_type": "fastest", "options": []},
  "route_types": [
    {"id": "fastest",  "label": "Fastest",  "offered": true,  "costing": "auto", "costing_options": {}},
    {"id": "simpler",  "label": "Simpler",  "offered": true,  "costing": "auto", "select": "min_maneuvers",
     "max_time_increase": 0.10, "max_time_extra_s": 600, "alternates": 2,
     "candidates": [{}, {"maneuver_penalty": 60}, {"maneuver_penalty": 300}, {"use_highways": 1.0, "maneuver_penalty": 60}]},
    {"id": "eco",      "label": "Eco",      "offered": false, "costing": "auto", "costing_options": {"use_distance": 0.5}}
  ],
  "options": [
    {"id": "avoid_highways", "label": "Avoid highways", "costing_options": {"exclude_highways": true},
     "fallback_costing_options": {"use_highways": 0.0}, "fallback_when": {"highway_km_over": 0.5}},
    {"id": "avoid_tolls", "label": "Avoid tolls", "costing_options": {"exclude_tolls": true},
     "fallback_costing_options": {"use_tolls": 0.0}}
  ]
}
```

(The tuning notes of format 1 move along unchanged. Batch D, docs/next-round.md item 13: Shortest
left the route types, `{"id": "shortest", ..., "costing_options": {"shortest": true},
"option_fallback_when": false}` until then; a stored "shortest" is Fastest, rules P4 and R3; the
format key `option_fallback_when` stays supported with no type using it.) **Avoid tolls is a hard exclusion**
(`exclude_tolls`) for every type: Valhalla ignores the soft `use_tolls` factor when `shortest` is
set, so the soft form left Shortest with tolls (review finding F2; the reviewer's pyvalhalla
3.9.1 run on the PA+OH tiles: Carlisle to King of Prussia, Shortest with `use_tolls: 0` 167.5 km
with tolls, with `exclude_tolls` 168.6 km without; with the points of the next paragraph on
`data/routing/device`, 169.6 km with tolls and 170.3 km without; 170.2 km since batch C1 ties King of
Prussia Mall to a street).

**Simpler** (`"select": "min_maneuvers"`, a candidate pool; batch D, the owner's answers to
docs/next-round.md item 13): one request per candidate, the first also asking for `alternates`
(2); the pool keeps the routes at most `max_time_increase` (10 %) and never more than
`max_time_extra_s` (600 s) slower than its quickest route (`route_lib.pool_limit_s`,
`RoutePlanner.poolLimitS`; a preset without the key has no such cap), and picks the fewest guidance
instructions (every maneuver but the start and the arrival, `Route.turnCount`; ties: the quicker,
then the first). The fourth candidate (`use_highways` 1.0 with `maneuver_penalty` 60) keeps to major
roads: from downtown Erie to Center School, Center Township (north of Butler) it brings I-79 exit 99
and US 422 into the pool, which no other candidate gave. Four engine calls per plan. Since batch E2
the pool routes to the ends the plan decided once with Fastest ("Every request" 7) and asks no tie
check or side request of its own (batch D asked both for the picked route; when the picked route
is an alternate of its request, `ChosenRoute.routeIndex` not 0, a check request keeps the same
`alternates` and takes the route *the same way* as the picked one: `route_lib.same_way`,
`RoutePlanner.sameWay`, the route whose shape starts with the longest run of the picked route's
shape points, at least half of its length; none such: the check counts as asked and the picked
route stays).

**Every request** (batch C1, docs/next-round.md item 9; `every_request` in presets.json, the same in
`route_lib.request`/`route`/`arrival_side` and in `:routing:core` `EveryRequest`, `ValhallaRequests.route`,
`RoutePlanner`; the matrix of C6 is not changed). Option names checked against Valhalla 3.9.1's sources
(`src/sif/autocost.cc`, `src/sif/dynamiccost.cc`, `valhalla/sif/dynamiccost.h`) and its API reference:

1. Costing: `alley_factor` 5 under the preset's own options (an alley costs five times its time; the
   default `alley_penalty` 5 s and auto's `service_penalty` 75 s stay). Driveways, parking aisles and
   drive-throughs have no auto factor in 3.9.1.
2. Where a stop is tied to the road: every location after the first (stops and the destination,
   never the origin) gets `search_filter {min_road_class residential, max_road_class trunk,
   exclude_ramp true}` and `search_cutoff` 300 m: the nearest street of residential class or above,
   never a motorway or a ramp. No such street within 300 m: the engine answers 171 and the same
   request is asked once more without those two keys (the place is then reached by its service
   way); the plan keeps the request that answered (`ChosenRoute`), so the OSRM form follows the same
   path. A `Stop.locationOptions` key wins over these (test points on a motorway on purpose). A
   place more than 100 m from any street (inside a large car park) therefore ends at the street, up
   to 300 m away; the arrival card then says how far the place still is (D2). The location key
   `street` is documented in 3.9.1 as not implemented, so the app ties an address itself (rule 4).
   A map point is not tied (the owner, after batch C1): a dropped pin, also once saved or in the
   recents (`Stop.tied` false, `StopTies.isMapPoint`; route_lib `_tie` "none") gets no stop options
   and the route goes to the road at the pin; `alley_factor` still applies. A point of interest that
   ends in its own lot (rule 6) gets no stop options either.
3. Arrival side (the owner, 2026-11-18: preferred unless it adds more than about one minute; batch
   D, docs/next-round.md item 16: never a loop around the block on a quiet street): when the chosen
   route arrives at a stop with the destination on the left (maneuver type 6; drive on the right),
   the same request is asked once more with `preferred_side same` and `street_side_cutoff tertiary`
   on those stops (not on a stop that ends in a lot, rule 6: its location already says
   `preferred_side` "either"). On a street of residential class and below (unclassified, residential, service)
   the engine ignores the side (Valhalla disables it on an edge of a lower class than the cutoff),
   so the plain route stays, the place possibly on the left. That route is kept when it succeeds,
   arrives at each of them on the right (maneuver type 5; type 4, no side, does not count), has no
   more U-turns, takes at most `max_extra_s` (60 s) longer and has at most `max_extra_maneuvers` (2)
   more guidance instructions (one block at most, never a longer loop); else the first route stays (`PlanResult.Ok.arrivalSide`: NOT_ASKED,
   ASKED_KEPT, SIDE). One more engine call per plan only when a stop is reached on the left. The
   arrival text is the engine's own (B7: "Arrive, on the right" from maneuver type 5), so it matches
   the route kept. Batch E2 (rule 7): asked once per plan, on the decision's Fastest route to the
   chosen ends and only for the stops decided there; a side route kept puts its `preferred_side` and
   `street_side_cutoff` on those stops, so every route type and every re-route carries them. Alternates leave out the route kept, not the first one. When a pool (Simpler)
   picked an alternate (`ChosenRoute.routeIndex` not 0) this rule and rule 5 compare the route the
   same way (the "Simpler" paragraph above; until batch D they were not asked: 8 of the 84 Simpler
   reference plans, U4 arriving on the left, which now takes its side route).
4. Address street (the owner, after batch C1; `address_street`): a stop that names the street of its
   address (`Stop.street`, from the place's texts by `:nav:session` `StopTies`: an address result's
   name, else the first "number street" part of its second line; route_lib `_street`) is tied to
   that street when it passes within `within_m` (300 m). valhalla-mobile 0.6.4 has no locate call, so
   the planner asks one `trace_attributes` (map_snap) over a grid of point pairs every
   `probe_step_m` (50 m) within 300 m, each pair matched alone to its nearest edge within
   `probe_radius_m` (50 m) (`AddressTie.request`, route_lib `address_probe_request`, equal number for
   number); the nearest edge whose name equals the address street (`words` expand "E", "St" ...; a
   road class rule 2 allows) gives the point, `end_margin_m` (10 m) inside the edge so it is not a
   crossing. The location is that point with the place as `display_lat`/`display_lon` (the side of
   the street is the place's); rule 2 still applies. No such street, or the call fails: the place as
   it is. One call per place; `:nav:session` keeps the points found per routing set
   (`AddressTies`), so a re-route does not ask again. Family Dollar, 155 East 12th Street, Erie, ends
   on East 12th Street, not Holland Street.
5. The ends' check (`tie_check`; batch E2, docs/next-round.md item 19, replacing the per-type tie
   check of the second routing review and batch E): a stop's possible ends (rule 7) are tried best
   first with the plan's Fastest request; an end is kept when its route finds a way, takes at most
   `max_extra_s` (90 s) longer than the route to the next end on the list with no more U-turns (when
   that one finds a way), and the last `tail_m` (300) metres of its way use none of `never_uses`
   (`alley`, `drive_through`: one `trace_attributes` call, `edge_walk` on the route's shape, else
   `map_snap`, asked only for the end about to be kept; a failed call does not count against it).
   The last end is kept when none before it is (`PlanResult.Ok.tieCheck`: NOT_ASKED when no stop had
   a choice, KEPT when every stop kept its first end, UNTIED when one took a later end). Until 0.1.6
   each route type checked its own route (the tied route against the same request untied; a lot route
   against its street end), so Simpler could end elsewhere than Fastest. Batch H (docs/next-round.md,
   the owner's answers after batch G): the last end of every list is the place itself, untied (rule 7),
   so the nearest street has to pass this check against it. The place never wins with an alley or a
   drive-through lane in its last `tail_m` metres (C1: no alleys unless there is no other way): when it
   would win (the nearest street is slower or has a U-turn more, or found no way, or uses one itself)
   its own tail is asked (one call); a place route that uses one does not count against the nearest
   street, and the nearest street is kept instead when its route finds a way (route_lib `_tail_never`,
   `RoutePlanner.tailNever`). Engine calls: one route request per end tried and its next end, one tail
   call per stop with a choice, one more when the place would win.
6. A point of interest ends in its own lot (batch E, docs/next-round.md item 18; `lot`): a stop that
   is a business, shop or amenity (`Stop.pointOfInterest`, from `:nav:session`
   `StopTies.isPointOfInterest`: a POI tapped on the map, or a place whose category is a search POI's
   (a category code or a raw OSM tag; a recent, a resumed trip or a saved place made from one keep
   it); not an address result, a street, a city or ZIP, a place without a category such as a saved
   pin, or a map point; route_lib `_poi`) is probed twice: rule 4's probe with `edge.use` (the
   filter does not change the match, so rule 4's point stays the same) and a small one near it
   (`probe_step_m` 10 within `probe_within_m` 30; `AddressTie.lotRequest`, route_lib
   `lot_probe_request`). The nearest edge whose use is `driveway` or `parking_aisle` (never `alley`,
   never `drive_through`, never another service road) within `within_m` (80 m) and at least
   `street_margin_m` (10 m) nearer to the place than every street of the classes rule 2 allows gives
   the point, `end_margin_m` inside the edge (`AddressTie.lotPoint`, route_lib `lot_tie_point`). The
   location is that point with the place as `display_lat`/`display_lon`, no stop options and
   `preferred_side` "either" (Valhalla's default, no route change; it marks the stop for rules 3 and
   5). No such edge, or the probes fail: rule 4, then rule 2, as before (the plan is then the same as
   before batch E). `AddressTies` keeps both points per place. A restaurant on Peach Street,
   Millcreek (the owner's case) now ends in its lot 20 m from the building, entered from Commons Drive
   from the north and from the south; Family Dollar, 155 East 12th Street, from East 12th Street into
   its own lot, no alley; a downtown business whose street is nearer than the driveway behind it ends
   on its street. 450 Erie plans (docs/experiments/25-batch-e.md): 227 end in a lot, 7 more go back to
   the street end by rule 5, the other 216 are unchanged. Batch E2: the lot is the first end of rule 7
   and rule 5 compares it with the address street end (else the nearest street).
   Front doors (docs/next-round.md item 28, 2026-11-21): the centroid of a large store is the middle
   of its roof, and the lane nearest to it is as often the service road behind the building. For a
   place that is a large building the search pack gives the point where customers go in (its
   `poi_door` table, docs/pack-format.md; `Stop.door`, route_lib `_door`), read by the session once
   per place on `beamline-routing` (`BeamlineNavigationSession` `doorOf`, in the app
   `SearchHub.doorAt`: the POI of the place's name within `details.match_within_m`, so a recent, a
   saved place and a POI tapped on the map find it too). The small probe and every distance of this
   rule are then the door's, and the uses are `door_uses` (`driveway`, `parking_aisle`,
   `service_road`: the lane along a store's front is often mapped as a plain service road, and by the
   door it is not the delivery road behind the building). The address street (rule 4) and the nearest
   street (rule 2) are still the place's own, and the place stays the display point. In rule 7's
   check such a lot may be slower than the next end by `max_extra_s` plus the walk it saves: how much
   nearer to the door its route ends than the next end's, at `tie_check.door_walk_m_per_s` (1.4 m/s;
   `RoutePlanner.walkSavedS`, route_lib `_walk_saved_s`): the lot of a large store is large, and its
   street end is often 200 m from the door. No door (a small
   building, a place that is not a building, a pack built before 2026-11-21): this rule as it was.
   A Walmart Supercenter in Erie: 121 m from its entrance before, 7 m now; 300 large buildings of
   Pennsylvania x 2 origins (docs/experiments/33-front-doors.md): within 30 m of the door in 92 % of
   the plans to a shop (38 % before).
   The way into the lot (docs/next-round.md item 35, 2026-11-21; `every_request.entrance`,
   `RoutePlanner.decideEntrances`, route_lib `decide_entrances`): the engine picks a way in by its
   own cost, which counts every small junction of an entrance, so two ways into one lot can be near
   ties there while one is clearly faster by the clock. For a stop that ends in a lot the plan's
   Fastest route is asked, the place where its last `tail_m` (800 m) leave the public roads is closed
   with one exclude_polygons square (2 x `box_half_m`, `into_m` along the way inside the lot;
   `RoutePlanner.entranceRing`: one trace_attributes call, the route's own edges) and the route is
   asked again. The other way in is taken (`Stop.entry`, route_lib `_entry`: every route of the plan
   leaves the first one out, before the rings of rule E2) when it is at least `min_gain_s` (10 s)
   faster, costs at most `within_cost` (1.1) times as much and has no more U-turns. It counts only when
   it was found with the way in really closed (a request that finds no route is asked once more
   without any exclusion: that answer, free to use the lanes rule E2 leaves out, is not another way
   in) and ends within `same_end_m` (5 m) of where the first route ends. Every plan decides
   it again, also for stops whose end is fixed, and drops the ring of the plan before: a driver who
   missed the turn is not sent back to it. Two engine calls more a plan to a lot (three when the other
   way in is taken). 300 plans to large buildings on the weekly pack's graph: another way in exists for
   209, is faster for 40, and 16 plans change (22 s saved in the middle, 34 s at most).
7. One end point per stop, decided once per plan (batch E2, docs/next-round.md item 19; the owner:
   "The destination point is non-negotiable. The route should end at the same point no matter what
   route choice."; route_lib `stop_ends`/`decide_ends`, `RoutePlanner.ends`/`decide`). Each stop after
   the first that is not yet decided gets its possible ends, best first: a point of interest its own
   lot (rule 6), its address street (rule 4), the nearest street (rule 2); a place that names its
   street that street, then the nearest street; any other tied place the nearest street; and last on
   each of these lists the place itself, untied (its own point, no stop tie; batch H; `StopEnd.PLACE`,
   route_lib `_end` "place"; a point of interest's boxes of rule 8 kept on it); a map point
   (and a saved or recent pin) exactly where it is, nothing to choose. Rule 5 picks one end per stop,
   stop by stop (the others at their end so far), with the plan's Fastest and the driver's options
   (`route_lib.decision_preset`, `PresetSet.decisionPreset`: "simpler+avoid_tolls" decides with
   "fastest+avoid_tolls"; a format-1 preset of the old reference plans that is not a route type decides
   with itself); then rule 3 once on the route to those ends. The decided locations (point, stop tie or
   none, side options, rule 8's boxes) are marked decided (`Stop.fixed`, `Stop.end`: LOT, STREET, NEAR,
   PLACE, PIN; route_lib `_fixed`, `_end`) and returned (`PlanResult.Ok.ends`, route_lib `_ends`); a decided
   stop is sent as it is, nothing is probed or checked again. The type's own requests (Simpler's pool
   included) go to those ends. `:nav:session` plans the first route type, then every other type and
   every re-route and stop change of the trip with those decided stops (`SessionCore.decidedEnds`, by
   place, in memory; a new plan decides again, and so does a trip resumed after the process died).
   Order of a plan: rules 4 and 6 (the probes), rule 5, rule 3, the type's requests. Python and Kotlin
   are compared on the reference plans (both types end at the same point; a re-route from elsewhere
   asks one route request). 450 Erie plans (docs/experiments/26-batch-e2.md): Fastest and Simpler ended
   at different points in 4 plans with 0.1.6, now in 0. Batch H (docs/experiments/29-batch-h.md):
   as points of interest no end changes; as plain addresses 4 of 450 change from the nearest street to
   the place (Ulta Beauty and Carter's at Millcreek Mall, from two origins: 191 m to 32 m, Fastest
   U-turns 7 to 3 in all 450), no alley and no drive-through lane in any last 300 m before or after.
8. Drive-through lanes and alleys left out of every route (batch E2; `left_out`): the probes of a
   point of interest (rules 4 and 6) also give the edges whose use is in `uses` (`drive_through`,
   `alley`) within `within_m` (80 m) of the place; each gets a square of 2 x `box_half_m` (2 m) at the
   middle of its edge, only where that middle is more than `clear_m` (3 m) from both ends of the edge
   and from every other edge the probes see (never touching another edge), in whole micro-degrees,
   nearest first (`AddressTie.leftOutRings`, route_lib `left_out_rings`; `Stop.avoid`, route_lib
   `_avoid`). Every request of the plan carries them as `exclude_polygons` (at most `max_rings` 20 rings
   of 5 vertices: the app's engine allows 100 vertices and 10 km, `service_limits` in
   valhalla_base.json; valhalla-mobile passes the request JSON to the engine as it is). A request that
   finds no route with them is asked once more without them (route_lib `route`, `RoutePlanner`
   `Calls.route`), and rule 5's tail check then rejects a way in through a lane or an alley, so a lot
   reached only through its drive-through lane ends on the street: the car wash on West 12th Street,
   Erie, ends on West 12th Street at the lane's entrance (4.3 min; 0.1.6 drove up the wash lane, 4.8
   min). Left out up front, from probes the stop already asks (no extra call), not after a route
   touched one: on the 450 Erie plans Fastest is the same route with and without them, and Simpler
   changes only where its pool reached a lot through an alley.

**The ends of a trip** (pyvalhalla 3.9.1 on `data/routing/device`, Round 0 review finding R1):
Valhalla's hard exclusions forbid driving *onto* an excluded road, not being on one.

- A trip that **starts** on a toll road (or a motorway) gets a route that may stay on it: from a
  point on the Turnpike (40.229690, -76.350721) to King of Prussia (40.09, -75.38), 93.9 km with
  tolls, the same with and without `exclude_tolls`. The owner accepted this on 2026-11-15 (there
  is nowhere else to go).
- A **stop or destination** on a toll road (a Turnpike service plaza, a dropped pin on it) cannot
  be reached under the exclusion: Carlisle (40.2, -77.19) to the Turnpike point
  (40.149533, -75.844894) gives "No path could be found for input" with `exclude_tolls`, for
  Fastest and for Shortest (Fastest without the option: 126.3 km). Erie (42.1292, -80.0851) to
  the I-90 point (42.032716, -80.125726) fails the same way with `exclude_highways`, Shortest
  included. So both options have a fallback for that case (rule 2) and the route says so (rule
  6): the Turnpike point is then reached with `use_tolls: 0` in 141.5 km, joining the Turnpike as
  late as it can; with Shortest, which ignores the soft factor, in 123.7 km. The owner confirms
  this behaviour (6.38).

**Composition** (`compose(type, options)`, the same in `route_lib.py` and Kotlin):

1. Every request the type makes (its one request, or each pool candidate) gets the type's costing
   options with each selected option's `costing_options` merged on top. Two selected options that
   set the same key are refused at load.
2. Selected options' `fallback_when` maps are united; the fallback options of a request are its
   merged options with each option's `fallback_costing_options` applied instead of its
   `costing_options`. The fallback runs **when the request finds no route**, for every type, and
   when the route exceeds a `fallback_when` limit, except for a type with
   `"option_fallback_when": false` (Shortest until batch D: with `shortest` set Valhalla ignores
   `use_highways`, so that second request would only repeat the plain Shortest route; no type sets
   it since).
3. A plain type with a fallback runs exactly as format 1's `avoid_highways` does today.
4. A pool type with a fallback (new): run the pool; if every candidate failed, or the picked route
   exceeds a `fallback_when` limit, run the whole pool again with the fallback options and keep the
   picked route with less of the flagged road (ties: the first). Alternates are not offered in this
   round (decision 6.6), so the alternates rule is untouched.
5. With no options, `compose` returns format 1's preset of the same id. Eco keeps `offered: false`:
   the route sheet never shows it; the planner and the tests still run it.
6. A route that the fallback produced because the request found no route carries a plain-word
   warning in `RouteChoice.warnings` (N's text, for example "No route to this place avoids toll
   roads. This route uses them."); its card notes show "Tolls" / "Highways" from the route as
   usual. A route from a `fallback_when` re-run carries no warning, as in format 1.

### 2.12 Persistence

| What | File (app's files folder unless noted) | Owner | Survives |
|---|---|---|---|
| Settings (map style, frame rate, sound, units, route type, avoid flags, include addresses) | `settings.json` | `:core:settings` | everything |
| First-run notice accepted (version) | `app-state.json` | `:core:settings` | everything |
| Recent places (30, newest first) | `recents.json` in the **no-backup folder** (`getNoBackupFilesDir`) | `:core:search` | everything but uninstall and clear data; never backed up or transferred (PV4); a broken file reads as empty |
| Saved places: Home, Work, the others and the "Show on the map" switch (format `beamline-saved-places/1`) | `saved-places.json` in the no-backup folder | `:core:search` `SavedPlaces` (S) | everything but uninstall and clear data; never backed up or transferred (PV4); a damaged file is kept aside as `saved-places.broken-<n>.json` and the user is told (SP52); a newer format leaves the store read-only (SP53) |
| Trip in progress: stops with reached flags, route type, options, time of the last write | `trip.json` in the no-backup folder | `:nav:session` `TripStore` | process death (rules R1-R3); never backed up or transferred (PV4) |
| Where the map was left (centre and zoom, written when the map screen stops; rule K1) | `last-camera.txt` in the no-backup folder (`beamline-last-camera/1 <lat> <lon> <zoom>`) | `:app` `LastCameraStore` | everything but uninstall and clear data; never backed up or transferred, never logged; a broken file reads as none |
| Packs, install records | `Android/data/<app id>/files/packs/` | P | everything |
| Merged routing tiles, engine config | `Android/data/<app id>/files/routing/` | P | everything |
| List of states (catalog cache): the bytes as received, and its URL, ETag, serial and time checked | `getpacks/catalog.json`, `getpacks/catalog-meta.json` | `:core:download` `CatalogSource` (G) | everything; a cache made for another URL is ignored |
| First-run choice made, notification permission asked, a pending "show on map" | `getpacks/ui.json` | `:feature:states` (G) | everything |
| Download job: `job.json` (format `beamline-download-job/1`) and the bytes downloaded so far | `Android/data/<app id>/files/downloads/<pack_id>-<pack_version>/` | `:core:download` (G) | process death and restarts (the bytes on disk are the progress); deleted when the job ends (`docs/architecture-downloads.md` 6.5) |
| Not persisted | search text, route choices, banners, camera mode, a map pick in progress, a running install from a file (restarts from nothing) | | |

The no-backup folder holds every file that can name a place of the user (`docs/architecture-saved-places.md`
PV4): Android never puts it in a cloud backup or a device-to-device transfer. In a test-extras build
started cold with `--es private_data test`, these files (and `last-camera.txt`) are in its `test/` subfolder for the life
of that process (PV7).

### 2.13 Getting packs (`:core:download`, `:feature:states`, owned by G)

The contract files of track G are quoted in full in `docs/architecture-downloads.md`, not here
(a whole file lives in one document; `tools/check_contracts.py` reads every companion):

| File (under `android/`) | Section there | What it fixes |
|---|---|---|
| `core/download/.../download/Catalog.kt` | 6.2 | The catalog model (`beamline-catalog/1`), `CatalogFormat.parse` and its refusals |
| `core/download/.../download/net/CatalogAddress.kt` | 6.2 | The configured address, its scope, the path and redirect rules (N2) |
| `core/download/.../download/net/PackHttp.kt` | 6.2 | With `:app`'s `OsmHttp` (batch O1), the only code that opens connections (N1, N3): `PackRequest`, `PackResponse`, `NetworkRuleRefused`, `ClockProblem` |
| `core/download/.../download/DownloadRules.kt` | 6.3 | `download_rules.json` as a type; `NetworkState` |
| `core/download/.../download/CatalogSource.kt` | 6.3 | The catalog cache and `CatalogState` |
| `core/download/.../download/Job.kt` | 6.3 | `DownloadJob`, phases and every wait, pause and fail reason |
| `core/download/.../download/DownloadEngine.kt` | 6.3 | The engine the service runs (`recover`, `start`, `run`, `pause`, `resume`, `cancel`, ...) |
| `core/download/.../download/PackHandoff.kt` | 6.3 | The hand-off to `InstalledData` (folder install, or the archive stand-in), `VirtualTar` |
| `core/download/.../download/Space.kt` | 6.3 | The free-space formulas |
| `feature/states/.../feature/states/map/StateShapes.kt` | 6.8 | The drawn US map's data and `StateHitTest` |
| `feature/states/.../feature/states/GetPacks.kt` | 6.9 | What I wires: `GetPacks`, `GetPacksConfig`, `MapChip`, `GetPacksHost` |
| `feature/states/.../feature/states/StatesScreen.kt` | 6.9 | `StatesScreen(getPacks, mode, onClose)` |
| `feature/states/.../feature/states/DownloadService.kt` | 6.9 | The `dataSync` foreground service and its notification actions |

(`...` = `src/main/kotlin/io/github/spysyweeb/beamline`.) The data file
`packbuilder/catalog/download_rules.json` (G's; copied into `:core:download` by
`android/routing/scripts/sync_data.py --only download_rules.json`) is quoted there in 6.3. The
`:core:api` additions those files use are in 2.2 above.

### 2.14 Saved places (`:core:search`, `:feature:search`, owned by S)

The new contract files of saved places are quoted in full in `docs/architecture-saved-places.md`,
not here (a whole file lives in one document; `tools/check_contracts.py` reads all three):

| File (under `android/`) | Section there | What it fixes |
|---|---|---|
| `core/search/.../search/saved/SavedPlaces.kt` | 5.3 | The store `SavedPlaces` (the file, structure-only reading, atomic writes off the main thread, names, the same-place rule, matching), `SavedRules`, `LoadProblem`, `SaveProblem`, `SaveResult`, `savedNameKey` |
| `feature/search/.../feature/search/SavedUi.kt` | 5.4 | `SavedPlacesFeature` (the saved places screen, the Edit menu, the save, rename and confirm dialogs, the map pick) with its UI states, events and outputs |

(`...` = `src/main/kotlin/io/github/spysyweeb/beamline`.) The other files that design changed are
quoted whole in this document: `Model.kt` and `Saved.kt` (2.1), `BeamlineNavigationSession.kt`
(2.4), `MapScene.kt` and `Scene.kt` (2.5), `SearchUi.kt`, `SearchScreens.kt` and `search_app.json`
(2.6), `camera.json` (3.7); the companion shows the changed parts of them as checked excerpts
(`BeamlineNavigationSession.kt` changed only in `TripStore`'s comment, its 6.3 item 5).

## 3. Behaviour rules

All thresholds of the session are in **`packbuilder/routing/guidance.json`** (owned by N, copied
into `:nav:session` resources by `android/routing/scripts/sync_data.py`; a test fails when the copy
differs). Camera rules are in **`packbuilder/routing/camera.json`** (owned by M, copied into
`:map:scene` the same way with `sync_data.py --only camera.json`). Every value is a first guess for
the phone test, not a measurement.

<!-- FILE: packbuilder/routing/guidance.json -->
```json
{
  "format": "beamline-guidance/1",
  "note": "Thresholds of the navigation session (docs/architecture.md section 3; rule ids in brackets). Read by android/nav/session (copied there by android/routing/scripts/sync_data.py; edit this file, not the copy). Distances in metres, times in seconds, speeds in metres per second. Every value is a first guess for the phone test, not a measurement.",
  "fixes": {"max_age_s": 3, "progress_max_accuracy_m": 50, "decide_max_accuracy_m": 30, "guiding_providers": ["gps", "simulated"]},
  "gps": {"weak_after_s": 2.5, "lost_after_s": 30},
  "dead_reckoning": {"min_speed_mps": 2.0},
  "snap": {"backstep_hold_m": 15},
  "advance": {"entry_m": 20, "exit_m": 10, "past_m": 40},
  "off_route": {"base_m": 35, "accuracy_factor": 1.5, "confirm_fixes": 3, "confirm_s": 4,
                "rejoin_fixes": 3, "wrong_way_deg": 120, "wrong_way_min_speed_mps": 3},
  "reroute": {"min_interval_s": 10, "retry_s": 15, "max_attempts": 5, "heading_min_speed_mps": 2,
              "origin_radius_m": 60, "start_far_from_route_m": 200},
  "arrival": {"within_m": 30, "passed_m": 40, "leave_m": 300, "stop_card_s": 8},
  "banner": {"distance_hold_m": 30, "lanes_within_m": {"highway": 1609.344, "other": 482.8032}},
  "motion": {"stopped_below_mps": 0.5, "stopped_after_s": 1.0, "moving_above_mps": 1.0},
  "planning": {"origin_max_age_s": 30, "origin_max_accuracy_m": 100, "wait_for_fix_s": 15,
               "coalesce_ms": 300, "max_stops": 9, "problem_hold_s": 8},
  "announce": {"highway_m": [3218.688, 804.672], "other_m": [804.672, 152.4], "now_s": 6},
  "resume": {"max_age_s": 1800, "refresh_s": 30},
  "notification": {"min_interval_s": 5},
  "lane_warning": {"lead_s": 18, "min_m": 150, "max_m": 600, "after_m": 5, "lookback_m": 500, "max_calls": 40,
                   "same_count_evidence_m": 300, "fork_angle_deg": 50},
  "lead": {"min_speed_mps": 2.2, "max_accuracy_m": 30, "max_glide_s": 3, "extra_s": 0.5, "max_m": 60,
           "stop_at": ["left", "right", "sharp_left", "sharp_right", "uturn_left", "uturn_right", "roundabout_enter",
                       "roundabout_exit", "ferry_enter", "destination", "destination_left", "destination_right"]},
  "tick_s": 1
}
```

Definitions. A **usable** fix: provider in `guiding_providers`, age at arrival
≤ `max_age_s` (age = `clock` − `Fix.elapsedMs`), `elapsedMs` later than the last usable fix,
accuracy ≤ `progress_max_accuracy_m`. A **deciding** fix: usable and accuracy ≤
`decide_max_accuracy_m` (unknown accuracy counts as neither). *U* = the upcoming maneuver index =
`FollowerProgress.stepIndex + 1` (flattened over legs). *Lateral* = `FollowerProgress.lateralM`.
Every rule id below needs at least one JVM test in `:nav:session` (N's definition of done).

### 3.1 Fixes and GPS state (G)

- **G1** Fixes that are not usable are dropped before the follower sees them: wrong provider (the
  network provider is never used while guiding), stale (age > 3 s; Android hands out cached fixes),
  out of order, or accuracy > 50 m.
- **G2** `gps` = WAITING until the first usable fix after collection starts; GOOD while the last
  usable fix is ≤ `weak_after_s` old; WEAK from 2.5 s to `lost_after_s`; LOST after 30 s. One
  usable fix returns it to GOOD.
- **G3** WEAK: every `tick_s` the vehicle is dead-reckoned along the route at the last speed when
  that speed ≥ `dead_reckoning.min_speed_mps` and the last fix was on the route; otherwise it
  holds still. Dead reckoning moves the vehicle and lowers the banner distance (floor 0) but never
  advances *U*, never counts for off-route, never arrives.
- **G4** LOST: the vehicle stays where it was, `stale` on the arrow, the banner keeps its last
  distance, status "GPS signal lost". Nothing advances, nothing re-routes. (Whether dead reckoning
  should go on past 30 s on a long straight tunnel is for the phone test; it is one number.)
- **G5** A lone sideways jump: one fix with lateral above the threshold counts toward off-route
  (O2) but cannot confirm it alone (O3). Lateral jitter below the threshold is invisible: the
  vehicle is drawn at the snapped point. A steady drift below the threshold (5 m for 10 s, the
  `drift` script) never sets `offRouteSuspected`.
- **G6** Backwards jitter: while on the route, a snapped position that is behind the previous one
  by less than `backstep_hold_m` (15 m) on the same step is replaced by the previous one; a larger
  step back (reversing, U-turn) is taken.
- **G7** The browsing puck's uncertainty bubble and steadier position (batch C2; the owner,
  2026-11-16, docs/next-round.md item 3: browsing only). Guidance never uses it: the arrow keeps the
  route-snapped position of G1-G6, and `VehicleMarker.Arrow` has no bubble (K7). `:core:location`
  `PositionEstimator` keeps an estimate from the accepted fixes (rules in `position.json` `bubble`):
  standing still (`MotionDetector`: the standard deviation of the accelerometer's |a| over
  `accel_window_s` below `still_below_mps2`, and no fix at most `speed_max_accuracy_m` accurate
  reporting `moving_speed_mps` or more) it is the mean of the fixes of the last `still_window_s` (at
  most `still_max_fixes`) weighted by 1 / accuracy², its radius their weighted RMS spread around the
  mean, at least the mean's own error 1 / sqrt(sum of weights); otherwise (moving, or no
  accelerometer reading) a constant-velocity Kalman filter per axis (measurement noise = accuracy²,
  process noise `moving_accel_mps2`), radius sqrt of the two position variances. A fix farther than
  `reset_sigmas` combined radii from where the estimate expects the phone starts it again at that
  fix; a fix without accuracy counts as `unknown_accuracy_m`. When the last fix or the estimate is
  poorer than `show_above_m` (25 m) the puck sits on the estimate (`PuckState.lat/lon`), and while
  the estimate's radius is above it a soft disc of that radius (whole metres, at most `max_m`) is
  drawn around the puck: `VehicleMarker.Puck.bubbleM`, the puck's own blue at 16 % by day and 22 %
  by night (grey when the puck is grey) with a blurred edge, under every marker (layers
  `beamline-puck-bubble`, `-edge`). A good fix: the puck on the fix, no bubble, as before. The
  accelerometer (`AccelerometerMotionSource` in `:app`, normal rate) is read only while the puck is
  tracked. Logs say "puck bubble shown" / "puck bubble gone" (no position, no size; PV2).


- **G8** Lead (0.1.22; the owner, 2026-11-24, docs/next-round.md item 39: "the location on screen
  is a little behind where I'm at"). A fix says where the car was when it was taken, and the arrow
  then glides to it over the time since the previous position (K3), so the arrow trailed the car
  by the fix's age plus about a second (20-40 m at 25-45 mph). While guiding on the route the
  shown position is the fix's snapped position moved ahead along the route by speed x (age +
  glide + `lead.extra_s`): age = `clock` - `Fix.elapsedMs` (at most `max_age_s`), glide = the time
  since the last vehicle position the map was given (a fix's or a G3 tick's, as `VehicleGlide`
  measures it; 0 above `lead.max_glide_s`, at most 3, where the map jumps), so the glide ends where
  the car is when it ends. No lead below `lead.min_speed_mps` (2.2, about 5 mph) or from a fix
  poorer than `lead.max_accuracy_m` (30) or of unknown accuracy; never more than `lead.max_m` (60);
  never past the next maneuver whose type is in `lead.stop_at` (plain and sharp turns, U-turns,
  roundabouts, a ferry, arrivals: the car slows there and the arrow must not turn early; exits,
  ramps, forks, merges and "continue" are passed, so the banner may say 0 for a second there);
  never behind the fix. When a new lead position is behind the arrow while the car itself moved on
  (braking, or one fix too poor for a lead) and the car is not slower than `min_speed_mps`, the
  arrow holds until the car catches up; slower than that it goes back to the car (it never stands
  past a stop line). G6 judges the car's own position (the shown one without its lead). The
  banner's distance, the lights and the travelled line follow the arrow; U's advance (B1, B2),
  off-route (O1-O5), arrival (A1: the banner's distance plus the lead), the lane notice's end (B9),
  the speed limit and a re-route's fallback origin go by the car. In WEAK (G3) the dead-reckoned
  position leads the same way. `extra_s` (0.5 since 0.1.25: the owner, 2026-11-24, after a drive with the lead: "It still feels a
  little behind"; at most 2) is the receiver's own delay that the fix's time does not show: tune it on a drive ("the arrow reaches the stop line before or after the
  car"). The simulated drive's fixes have no age, so there the arrow leads its fixes by one second,
  which is where the simulated car is.
### 3.2 Banner over a drive (B)

- **B1** Shown banner = `BannerView` of maneuver *U* (built once per route with `banner.json`).
  Before the first progress, *U* = 1.
- **B2** *U* advances only when the follower advances: the snapped position came within
  `advance.entry_m` (20 m) of maneuver *U* and then moved `advance.exit_m` (10 m) past it, both
  with deciding fixes. Forced advance: when a deciding fix snaps more than `advance.past_m`
  (40 m) beyond maneuver *U* along the route (the entry was missed in a gap), the session calls
  `advance()`, **repeatedly within the same update, until the snapped point is no more than
  `past_m` beyond the new *U*** (after a tunnel that hid two turns, the first fix shows the right
  banner). Arrivals passed in a gap are handled the same way (A1). Dead reckoning never advances.
  While `offRouteSuspected` or in REROUTING, *U* does not advance at all (no forced advance, no
  arrival): the car is not on the route the banner describes.
- **B3** Live distance *d* = `toManeuverM` (minus the dead-reckoned metres in WEAK). Shown
  distance *s*: on each update, *s* = *d* if *d* ≤ *s*, or if *d* > *s* + `distance_hold_m` (30 m);
  otherwise *s* stays. *s* is reset to *d* when *U* changes. Text = `UnitFormatter.distance(s)`, so
  it never goes back up by jitter.
- **B4** "Then" chip: shown for the whole approach to *U* iff `BannerView.following` is non-null
  (the stretch from *U* to *U*+1 is ≤ 804.672 m AND ≤ 60 s by the route's own estimate, and *U*+1
  is not an arrival). Its text is `distanceIn(following.distanceM)`, fixed: it does not count down.
  It goes away when *U* advances (the following turn becomes the main instruction).
- **B5** Lane row: shown when `BannerView.lanes` is non-null and *s* ≤ `lanes_within_m.highway`
  (1 mi) if `approachOnHighway`, else ≤ `lanes_within_m.other` (0.3 mi). Once shown it stays until
  *U* changes. These two windows are first guesses of this document (the sketch's frames, 0.2 mi
  and 400 ft, fit inside them), not the owner's numbers. Best lane narrowing toward the following
  turn is already in the lanes (`banner.json`, up to 0.5 mi).
- **B6** A new route (re-route, stop change) rebuilds the banners; *U* comes from its first progress.
- **B7** The last leg's arrival maneuver is a normal banner ("Arrive, on the right") with a
  counting distance, no chip, no lanes.
- **B9** A lane that ends in a turn (batch C1, docs/next-round.md item 12). Built once per route
  (`LaneWarnings` in `:nav:session`, from the OSRM answer and the engine's `trace_attributes`): a
  spot is an intersection of an OSRM step after its first (no maneuver there), a real one (3 or more
  bearings), passed straight (out within 30 degrees of in) with a valid lane marked straight, whose
  lanes say fewer lanes go on (valid or active) than the road had before (`lanes_before`: the fewest
  `edge.lane_count` of the route's line in the `lookback_m` (500 m) before it, never before the
  step's own maneuver; one trace_attributes call per candidate, at most `max_calls` (40) per route,
  nearest first; a failed call drops only that spot). Of a run of nodes with the same lanes in a
  step only the last counts (a turn-lane tag covers every piece of its way), and none within 60 m
  before the route's next maneuver (those are that maneuver's lanes, B5). The road's own count is
  trusted when turn lanes were seen being added (fewer lanes before than at the intersection) or
  when it had as many lanes for `same_count_evidence_m` (300 m); an added turn lane alone never warns
  (the lanes that go on are as many as the road had). Shown (`Guidance.laneWarning`, a
  `LaneWarningView`: the intersection's lanes, BEST for those that go on, the line, the live
  distance) from max(`min_m` 150, `lead_s` 18 x current speed), at most `max_m` 600, before the
  intersection until `after_m` (5 m) past it; once shown it stays until passed; never before the
  first progress, while `offRouteSuspected`, or outside GUIDING. The line names the side whose lanes
  end: "Right lane turns right: keep left for Peach St" ("Left 2 lanes turn left: keep right", "Right
  lane exits" on a motorway, "ends" when the arrows are not all a turn to that side); when both sides
  have lanes not for the route the lanes the road gained are taken to be left-turn lanes on the left;
  when that still leaves both sides, "Keep to the marked lane for ...". GPS cannot tell the car's
  lane, so every driver approaching sees it; only where OpenStreetMap has turn lanes.
  The lane at a fork (docs/next-round.md item 37, 2026-11-21): a candidate that makes no spot (the
  lanes not for the route were only added) still makes one where the road forks: another road leaves
  the junction within `fork_angle_deg` (50) of straight ahead by the OSRM bearings
  (`Candidate.forkLike`), and the engine, asked about that junction (`forkRequest`: one more
  trace_attributes call over the route's points from about 60 m before it to 30 m after; `forkOf`
  finds the junction by its place), says such a road is a plain road (use `road`) the driver may
  enter, as important as the road the route goes on by, or more. The line names the lanes to use and
  that road: "Use the right lane for Mill Rd" ("Use the left 2 lanes ...", "Use the marked
  lane ..." when they are not together at one side). The engine gives no instruction at such a fork
  when it counts the route's branch as straight ahead. 13 junctions on 300 routes in Pennsylvania.
  Two roads of one class that cross at less than that angle are taken for a fork as well.
- **B10** Traffic lights (docs/next-round.md item 22, 2026-11-21). Built once per route (`RouteLights`
  in `:nav:session`): one `trace_attributes` call per stretch of the route (each leg alone, at most
  150 km and 12,000 points a call; the route's own points, `edge_walk`), asked for each edge's last
  shape index and whether a light stands at its end (`edge.traffic_signal`, `node.traffic_signal`);
  a stretch whose call fails has none. 2 ms for 6 km in town, 132 ms for 664 km (PC).
  `Guidance.lightsAhead` holds the lights from the shown position to the route's end (until
  0.1.22 only the next 3 km; the owner, 2026-11-24, item 39: panning ahead must show them), one per
  junction (two within 30 m are one), nearest first; the map draws a small traffic light at each
  (`MapScene.lights`, in GUIDING and REROUTING). A plain turn (left, right, slight, sharp, U-turn;
  never a ramp, an exit, a fork or an arrival) with a light at most 5 m before its maneuver point or
  15 m after it (a light the car passes 10 m or more before it turns is another junction's) says
  so: `BannerView.primary` "At the light, turn left" (`Views.atLights`); the chip keeps its short
  words. The icons and the words are an extra: a missing one never says there is no light.
  The packs' lights (docs/next-round.md item 36, 2026-11-21): the engine knows a light only on the
  route's very line; one the map has on the cross street's side of a junction, or just past the point
  where the route turns off, it does not see. The search pack carries every light (`signal` table,
  docs/pack-format.md; `SearchHub.signalsIn`, read on the routing thread through the session's
  `signalsIn`), and `RouteLights.build` adds those within 20 m of a junction of the route (the end
  of an edge where another road can be driven; the light is drawn at the junction) or within 3 m of
  its line, on plain road only (no bridge, tunnel or motorway) and only where the engine has no
  light within 30 m along the route (the end of a walk's last edge, a stop or a cut of a long leg,
  is never a junction). The same trace_attributes call then also asks for
  `edge.bridge`, `edge.tunnel`, `edge.road_class` and `node.intersecting_edge.driveability`.
  300 routes in Pennsylvania: 3,262 junctions with a light from the engine, 141 more from the pack.
- **B11** Words that say where (item 22). A ramp or an exit the data gives no sign and no name for
  leads `toward` the first road name of the next maneuver ("Take the ramp on the left toward I 79
  South"; `BannerModel.main`). On a leg whose stop ends in its lot (`StopEnd.LOT`), the turn that
  leaves the last named road, when it is a plain turn at most 400 m before the arrival, says "Turn
  left into the parking lot" (`Views.intoLots`).
- **B12** The road being driven (item 22): `Guidance.currentRoad`, the names of the stretch after the
  maneuver behind the vehicle (at most two, the last word short as on the banner, without a memorial
  name when the road has another: "I 90 East", "US 19 / Peach St"); null on a ramp or in a lot, and
  before the first progress (`Views.currentRoad`).
- **B8** Announce events (for later voice/chime): PREPARE once when *s* first drops below each
  `announce.highway_m` / `other_m` distance (by `approachOnHighway`); NOW once when the time to
  the maneuver at the current speed drops below `now_s`. Emitted, not consumed this round.

### 3.3 Off route and re-route (O)

- **O1** Threshold *T* = max(`base_m`, `accuracy_factor` × accuracy) = max(35 m, 1.5 × accuracy).
- **O2** A deciding fix is **off** when lateral > *T*, or when the speed ≥ `wrong_way_min_speed_mps`
  and the course differs from the route bearing at the snapped point by more than `wrong_way_deg`
  (120°). It is **on** when lateral ≤ *T* and not wrong-way. Usable fixes that are not deciding
  neither count nor reset. While at least one off fix is counted, `offRouteSuspected` = true and the
  vehicle is drawn at the raw fix with the GPS course.
- **O3** Off-route is confirmed by `confirm_fixes` (3) consecutive off fixes spanning at least
  `confirm_s` (4 s); at 1 fix per second that is the fifth off fix. An on fix resets the count.
  Confirmation sets phase REROUTING and asks for a route (O4).
- **O4** Re-route request: origin = the last deciding fix with `radiusM` = `origin_radius_m` (60)
  and `headingDeg` = course when speed ≥ `heading_min_speed_mps` (2 m/s); stops = the stops not
  reached; the trip's `routeType` and options (rule C7). Not asked within `min_interval_s` (10 s)
  of the previous re-route request. `replanning` = true while it runs.
- **O5** While the request runs, the old route stays drawn and guided geometrically (without
  advancing *U*, B2). If `rejoin_fixes` (3) consecutive on fixes arrive before the answer, the
  answer is dropped and the phase returns to GUIDING (no event).
- **O6** Answer OK: the new route replaces the old (banners rebuilt, B6), phase GUIDING, event
  `RouteReplaced(REROUTE)`.
- **O7** Answer failed: `rerouteAttempts` +1, event `RerouteFailed`, phase stays REROUTING with
  `problem` set; retried every `retry_s` (15 s) up to `max_attempts` (5); after that only on
  Retry or Reroute, or when the car rejoins the old route (O5 rule, back to GUIDING).
- **O8** Leaving the installed states: before asking, the session checks
  `RoutingSet.covers(origin)`. Outside: no engine call; `problem` = OUTSIDE_INSTALLED_MAPS ("You are
  outside the installed maps."), no attempts counted; asked again as soon as a deciding fix is
  covered. An engine answer 170/171 for the origin is treated the same way.
- **O9** Start from elsewhere: at Start, if the first deciding fix is more than
  `start_far_from_route_m` (200 m) from the route, the session re-routes at once from the car (O4).
  A simulated start begins on the route, so this never fires in simulation.

### 3.4 Arrival and stops (A, C)

- **A1** Arrived at the end of leg *L* when *U* is that leg's arrival maneuver and either a
  deciding fix has *s* ≤ `arrival.within_m` (30 m), or a deciding fix snaps more than
  `passed_m` (40 m) past it (also when that happens inside B2's catch-up loop), or the follower
  reports `complete` on the last leg.
- **A2** Intermediate stop: the stop is marked reached, `trip.json` is saved, event
  `StopReached`, guidance continues on the next leg (*U* moves past the arrival and the departure
  maneuvers); D shows the card for `stop_card_s` (8 s, through `EventCards`).
- **A3** Destination: phase ARRIVED, event `Arrived`, `trip.json` deleted.
  ARRIVED ends on Done or when a deciding fix is more than `leave_m` (300 m) from the destination;
  then IDLE: the pack-change hold is released and position collection stops.
- **C1** Stops can be added, removed and moved in PLANNING, CHOOSING, GUIDING and REROUTING;
  reached stops cannot be changed. Whatever stop ends up last is the destination: moving the
  destination up makes the stop before it the destination (`canMoveDown` is false on the last
  row, `canMoveUp` true). A trip has at most `planning.max_stops` (9) stops: one more is refused
  with `problem` TOO_MANY_STOPS ("A trip can have up to 9 stops."), so the origin and the stops
  always fit `StopOrderer`'s 10 points.
- **C2** Before guidance a change re-plans every offered type (PLANNING, then CHOOSING), after
  `planning.coalesce_ms` (300 ms) without a further change; queued work for an older revision is
  skipped before it starts (an engine call already running finishes and its answer is dropped).
- **C3** During guidance a change re-plans the trip's route type from the car (O4 origin) with the
  new stops not reached; `replanning` = true until the answer. Until then guidance continues on the
  old route. OK: swap (`RouteReplaced(STOPS_CHANGED)`), save `trip.json`. Failed: the change is
  undone (old stops back), `problem` set for `planning.problem_hold_s` (8 s), event `RerouteFailed`.
- **C4** Route type and options can be changed before guidance (re-plan, C2) and during guidance
  (re-plan from the car as C3, `RouteReplaced(OPTIONS_CHANGED)`); the choice is written to settings.
- **C5** A new `InstalledSet` generation in PLANNING or CHOOSING re-plans (choices from an older
  routing generation are discarded). During a trip packs cannot change (2.2 rule 4).
- **C6** OptimizeStopOrder (offered when at least 3 stops are not reached): one matrix with the
  trip's route type and options over origin + stops not reached, brute force over the order with
  the destination kept last (`StopOrderer`), minimising time; when the options' exclusions leave a
  stop unconnected (a stop on an excluded road, 2.11), the matrix is asked again with the fallback
  options; then as C2/C3. No connected order: unchanged, problem NO_ROUTE shown.
- **C7** `Choose(id)` selects that card and sets the trip's `routeType`: kept when it is one of the
  card's `typeIds`, else the card's first type id; it is written to settings. Re-routes (O4),
  changes during guidance (C3, C4), `trip.json` and Resume use the trip's `routeType`. Test: choose
  the Simpler card (the Shortest card until batch D), Start, the `wrongturn` script gives a Simpler
  re-route; Resume after process death keeps Simpler.

### 3.5 Planning (P)

- **P1** PlanTo / stops change from IDLE: origin = current position. The origin fix is the latest
  fix (any provider) no older than `origin_max_age_s` (30 s) and no worse than
  `origin_max_accuracy_m` (100 m), with heading when moving ≥ 2 m/s; otherwise the session collects
  positions and waits up to `wait_for_fix_s` (15 s), then `problem` NO_POSITION ("No GPS position
  yet. Choose a start point or try again."); the route sheet's start row opens search in SET_START
  mode (`DriveEvent.ChooseStart`, then `NavIntent.SetOrigin`).
- **P2** Every offered type is planned with the current options, in presets order, one after the
  other on `beamline-routing`; the state is published after each answer, so Fastest shows first.
- **P3** Same route (length within 1 m, time within 0.5 s, same shape size: the planner's
  `sameRoute`) from several types = one `RouteChoice` with all their type ids.
- **P4** The trip starts with the route type from settings when it is an offered type, else the
  presets' default, which the session then writes to settings (a stored "shortest" becomes
  "fastest": Shortest left in batch D, docs/next-round.md item 13). Selected choice = the one holding the trip's `routeType`, else the first, and
  selecting a choice this way sets the trip's `routeType` as C7 does. All types failing: `problem` = the first type's problem; CHOOSING with no choices, Retry offered.
- **P5** Start takes `holdPackChanges("Not while guiding")` first: null (an install or removal is
  running) refuses Start with `problem` PACKS_BUSY ("A state is being installed. Start again when
  it has finished."). Then it asks the engine once more for the OSRM form of the chosen request
  (on `beamline-routing`), creates the follower, saves `trip.json`, phase GUIDING, event
  `GuidanceStarted`. With a `Simulation` the session drives from the route's start and ignores
  real fixes until End.

### 3.6 Motion and brake lenses (M)

- **M1** Speed = `Fix.speedMps` when present, else distance / time between the last two usable fixes.
- **M2** STOPPED after speed < `stopped_below_mps` (0.5 m/s) for `stopped_after_s` (1 s); MOVING as
  soon as speed > `moving_above_mps` (1 m/s); in between the previous value holds (no flicker in
  slow traffic). UNKNOWN before two usable fixes and in LOST.
- **M3** `brakesLit` = motion STOPPED and not stale. The beam is drawn only with the night map
  (renderer's rule, from `MapScene.theme`), never in LOST (stale arrow: no beam, no lenses).

### 3.7 Camera (K; `camera.json`, M's `CameraDirector`)

<!-- FILE: packbuilder/routing/camera.json -->
```json
{
  "format": "beamline-camera/1",
  "note": "Camera rules of the map while browsing, choosing a route and guiding (docs/architecture.md 3.7, rules K1-K7), and from which zoom the user's saved places are drawn (docs/architecture-saved-places.md, rules SP40-SP43). Read by android/map/scene (copied there by android/routing/scripts/sync_data.py; edit this file, not the copy). Zoom levels are MapLibre's; pitch and bearing in degrees; times in seconds unless named _ms; icon_scale multiplies the sprite icon's size. Every value is a first guess for the phone test, not a measurement.",
  "guide": {"pitch_deg": 60, "vehicle_from_bottom": 0.28,
            "zoom_by_speed_mps": [[0, 17.0], [13.4, 16.5], [24.6, 15.75], [31.3, 15.25]],
            "approach_within_m": 250, "approach_zoom": 17.0, "bearing_smoothing": 0.3, "bearing_turn_deg": 30,
            "lean": {"within_m": 120, "within_s": 7, "bearing_share": 0.5, "max_bearing_deg": 55, "zoom_add": 0.5,
                     "pitch_deg": 50, "min_turn_deg": 30, "look_m": 35, "hold_ms": 4000},
            "recenter_after_s": 15},
  "overview": {"return_after_s": 10},
  "browse": {"follow_zoom": 16},
  "preview": {"padding_dp": 48},
  "arrived": {"zoom": 17},
  "saved_places": {"home_work_min_zoom": 10, "other_min_zoom": 12, "icon_scale": 1.25},
  "transition_ms": 800
}
```

- **K1** BROWSE: top-down (pitch 0), north up, never moved by the app except: my-location →
  BROWSE_FOLLOW (centre on the puck, zoom `follow_zoom`, today's z9.99 cap outside the states kept),
  until a user gesture → BROWSE; a cold start with location allowed (and turned on) begins in
  BROWSE_FOLLOW too, from the last known position, and moves when the first fix comes; a place
  picked from a search result, a saved place, a recent or a sheet (`CameraDirector.onShowPlace`;
  not a place tapped on the map, which is on screen already) → BROWSE centred on it at
  `follow_zoom` in the area the insets leave free (above the place sheet: `AppRules.cameraInsets`
  adds the sheet), capped outside the states, until a gesture, the sheet closing or a place tapped
  on the map replacing it (`CameraDirector.onPlaceLeft`: the camera then stays where it is, so the
  sheet going does not carry it back to the place); while choosing a route or at
  ARRIVED the camera looks at it as my-location does there, while guiding it does not move; the
  search results on the map after Enter (S11, `CameraDirector.onShowResults`, browsing only) →
  BROWSE fitting the nearest of them, as many as fit at the zoom from which the installed states
  draw (`state_min_zoom`, z8) or closer and never closer than `follow_zoom`, in the area above the
  results list, until a gesture, my-location, a result picked (then as a picked place) or the
  results closed (`onPlaceLeft`); a result's sheet closing fits the results again. Without
  a position (location not allowed, off or not known yet) a cold start shows the map where it was
  left last time (`last-camera.txt`, 2.12, capped outside the states), the whole installed set only
  on the very first open; the one-off "<state> is ready." fit after an install is kept.
- **K1a** Turning while browsing (0.1.27; the owner, 2026-11-24: "allow users to rotate the map, with a
  compass ... when they click on it, it will point north, if they click it again, it points in the
  direction the phone compass is pointing"; one button since 0.1.28: "the screen rotating with the
  phone's compass without being centered on the user is a bit odd"). Browsing may be turned with two
  fingers (still no tilt). My-location holds the compass: not following -> following, north up;
  following -> following turned by the phone's compass (the button shows a needle pointing to north
  on the screen, ringed in the route's blue); turned by the compass -> following, north up. Since
  0.1.30 (the owner: "if the user rotates the map, taping the compass button should set the map to
  face north first (leaving the map where it's at), and pressing the button again should bring the
  map to the users location") a map turned by hand and not following turns north up where it is
  first (within 0.5 degree of north counts as north up); the next tap follows. Since
  0.1.29 (the owner: "The goal is to keep the map up to speed with the user") the map takes the
  rotation-vector sensor's reading without animation, only while it turns with the compass. Since
  0.1.30 (the owner: "a little bit of snapping when the compass is moving 1 degree at a time ... a
  super small amount of smoothing without adding any kind of delay") the sensor reads every 8 ms
  (faster than the screen redraws) and `CompassFilter`, a One Euro filter (minimum cut-off 1 Hz,
  beta 2, derivative cut-off 3 Hz), calms it in place of 0.1.29's half-degree steps. Simulated:
  turning at a steady speed the map is under 0.1 degree behind; when a turn starts from still at
  most about 0.4 degree (2 ms at a quick turn); held still it wobbles about half as much as the
  sensor. Moves under 0.02 degree are skipped. A drag ends the following
  and the compass turn (the map stays as it is); a two-finger turn ends only the compass turn.
  Otherwise a small red mark on the button's rim always points to north (since 0.1.29). The button's look is
  option A of the owner's choice (2026-11-24): a bold crosshair, blue with a dot while following, a
  lighter night panel with a thin outline. The map
  fills the whole screen, behind the status bar and the navigation bar (0.1.29). Browsing's own moves (a picked place) keep the
  bearing browsing has. A new phase group (a route choice, guidance, arrival, back to browsing) starts
  north up with the compass not leading. Without a compass reading the second tap keeps following
  north up. The constants are `Director`'s COMPASS_STEP_DEG and `CompassFilter`'s.
- **K2** PLANNING/CHOOSING → PREVIEW: top-down, north up, fit all choices' bounds plus the origin and
  stops, padded by `padding_dp` plus the overlay insets.
- **K3** GUIDING or REROUTING → GUIDE_FOLLOW: pitch `pitch_deg` (60), bearing = vehicle bearing
  (low-pass `bearing_smoothing` on the circle, its weight growing with the change up to 1 at
  `bearing_turn_deg`, so a turn is followed at the next position while the wobble of straight
  driving stays smoothed; the weight stays 1 up to four times `bearing_turn_deg` (a sharp corner
  between two fixes) and falls back smoothly to the plain weight at 5.5 times, so a reversal or a
  glitch, about 180 degrees in one position, keeps the plain weight), vehicle at `vehicle_from_bottom` of the free map height, zoom
  from speed by linear interpolation of `zoom_by_speed_mps`, `approach_zoom` when *s* ≤
  `approach_within_m`. Each move animates over the time between the last two vehicle positions
  (`VehicleMarker.Arrow.glideMs`, which `MapSceneMapper` remembers per map) so the arrow glides.
  The pitch was 50 until 2026-11-17: MapLibre applied it (the camera read 50 during the follow
  eases), but its narrow field of view (the camera 1.5 view heights above the centre) with the
  vehicle low on the screen and the banner covering the top quarter left little visible
  foreshortening, so the view read as nearly top-down on the phone; 60 (the limit that applies:
  MapLibre Android's default maximum pitch, `MapLibreConstants.MAXIMUM_PITCH` = 60 in 13.7.1, which
  camera moves are clamped to while Beamline sets no `setMaxPitchPreference`; the native transform's
  inset-dependent limit, about 65 here, is never reached; to be looked at on the owner's phone) gives
  the far part of the visible map about the depth of M's full-screen demo at 50
  (`docs/design/position-markers.html` uses a stronger perspective still). While guiding the route
  ahead is drawn above the road name labels, below the route-number shields and the POI and place
  names (`:map:layers` `Ids.GUIDE_ROUTE_ON_TOP`; the style's `roads_shields` copies are moved above
  it while guiding and back after it, `Ids.GUIDE_SHIELDS`, `SceneParts.guideShields`: the owner,
  2026-11-18, batch A, so the driven road's "US 19" or "I 90" is not hidden under the line); the
  banner names the road.
- **K3a** The lean into a turn (docs/next-round.md item 22, 2026-11-21; `guide.lean`, `Lean` in
  `:map:scene`). In GUIDE_FOLLOW, when the banner's maneuver is nearer than the larger of `within_m`
  (120) and `within_s` (7) at the current speed, and the route line turns by `min_turn_deg` (30) or
  more there (read from the line itself, `look_m` (35) before and after the maneuver point, once per
  maneuver; so a "Continue" and an arrival never lean), the camera moves smoothly toward the turn:
  the bearing by `bearing_share` (0.5) of what is left of the turn (from the vehicle's own bearing to
  the road the route turns onto, so the map never turns past that road), at most `max_bearing_deg`
  (55); the zoom by `zoom_add` (0.5); the pitch to `pitch_deg` (50), all in
  full at the turn. When the banner moves on to the next maneuver the zoom and the pitch ease back
  at once, and the bearing stays where it leaned until the vehicle's own bearing has turned that
  far, at most `hold_ms` (4 s): a position a second old still points down the old road, and the map
  must not turn back. A new route (a re-route after a missed turn, another trip) and a vehicle off
  the route keep nothing of the old turn. A camera.json without `guide.lean` never leans.
- **K4** Any user gesture in GUIDE_FOLLOW → GUIDE_FREE; `showRecenter` = true. Recenter, or
  `recenter_after_s` (15 s) without a gesture → GUIDE_FOLLOW.
- **K5** Overview → GUIDE_OVERVIEW: top-down, north up, fit the remaining route; back to
  GUIDE_FOLLOW after `return_after_s` (10 s) or on Recenter.
- **K6** ARRIVED: animate to top-down, north up, centred on the destination at `arrived.zoom`;
  after Done or leaving → BROWSE.
- **K7** The arrow replaces the puck from Start to the end of ARRIVED; the browsing puck otherwise.
- **Saved-place markers** (rules SP40-SP46 of `docs/architecture-saved-places.md` 2.8;
  `camera.json` `saved_places`, read by `SavedMarkerRules`): Home and Work from zoom 10, the others
  from zoom 12, at 1.25 times the sprite size, upright also in the tilted view; above the labels and
  below the pins and the vehicle; above the route lines while browsing and choosing a route, below
  them while guiding (`MapScene.savedBelowRoute`); a saved place that is a stop of the trip is left
  out; none while the user has switched them off. The camera does not move when a map pick starts
  (K1).

### 3.8 Search (S; `search_app.json` in 2.6)

- **S1** Ranking position: a fix ≤ `near.fix_max_age_s` (60 s) old when there is one ("Near your
  position"), else the map centre ("Near the map center").
- **S2** Typing: each change is queued on `beamline-search` after `typing.debounce_ms` (120); only
  the newest pending query runs; answers for an older query are dropped. Empty text shows
  shortcuts and recents.
- **S3** Packs asked: every pack with a usable search part whose bounds grown by
  `merge.pack_margin_km` (50 km) contain the ranking position; with none, the pack nearest to it.
- **S4** Merge: if all asked packs have the same `searchRankingSha256`, results are merged by
  score; otherwise the pack containing the position first, then the others. Duplicates (same kind,
  same folded title, within `merge.dedupe_m` = 100 m) keep the higher score. Limit `merge.limit` (10).
  Exact addresses first (next run, part 1): the engine adds `ranking.json` `weights.house_located`
  (1.0) times the tier value of the street's name match and the distance decay of the house from the
  anchor to a street whose typed house number it located (nothing outside a place the query names,
  and nothing for an `any_word` match, a street that only shares a word such as "Street" with the
  query: on a phone without Ohio, "247 Main Street, Conneaut" does not put 247 East 6th Street in
  Erie first), in Kotlin and in `query.py` alike; the address lookup's answer (`SearchService`) is scored on the
  engine's scale (exact and first: the best engine score + `house_located`; estimated and first: the
  best + 0.0001; a guess, last: the lowest - 0.0001), so the merge by score keeps an exact address
  of one pack above an estimated one of another ("247 Main Street, Conneaut" near Erie: Ohio's
  exact house before Pennsylvania's estimated "247 Main St, Conneautville").
- **S5** The input is cut to `ranking.json` `max_query_chars` (256) by the engine itself, in Kotlin
  and in `query.py` alike. Type labels made from OSM tag values are spelled the US way
  (`ranking.json` `us_spellings`: "Neighborhood", "Sports center", "Theater"), and so is the leading
  type label of a place's subtitle in a pack built before ("Neighbourhood in Erie" is shown
  "Neighborhood in Erie"); names are never changed.
- **S6** Shortcuts come from `search_app.json` (Gas, Rest stops, Food, Coffee, Parking, EV
  charging, Hotels): each a term the engine already understands and a sprite icon name.
- **S7** The place sheet offers DIRECTIONS and, while a trip exists (`tripActive`: phase PLANNING,
  CHOOSING, GUIDING or REROUTING), ADD_STOP first; then SAVE ("Save") or, for a saved place, EDIT
  ("Edit") last (SP19). A place no routing tile covers gets the note "Outside the installed maps" and
  only OPEN_STATES (then SAVE or EDIT); the NO_SEARCH_DATA screen has "Add a state"
  (`SearchEvent.OpenStates` → `SearchOutput.OpenStates`): first start with nothing installed always
  has a way to the States screen. In SET_START mode a pick returns `Picked(place, SET_START)`. While
  driving (GUIDING, REROUTING, ARRIVED) the sheet has no SAVE or EDIT, and in ARRIVED no ADD_STOP
  (SP4); a read-only store gives neither (SP53). After them, when the place's pack has them and
  not while guiding (GUIDING, REROUTING; again once ARRIVED, S10): CALL ("Call", S10) and WEBSITE
  ("Website", S10).
  "Directions", and a saved place's one-tap
  `PlanTrip`, while a trip exists, by phase (SP18): IDLE → `PlanTo`; RESUME_OFFERED → the dialog
  "Drop your trip to {destination} and go to {name}?" (Cancel / Go: `DiscardResume`, then
  `PlanTo`); PLANNING, CHOOSING → `PlanTo`; GUIDING, REROUTING → "End this trip and go to {name}?"
  (Cancel / Go: `End`, then `PlanTo`); ARRIVED → `Done`, then `PlanTo`.
- **S8** The search bar (SP1-SP3): a pill at the top of the map, under the status bar, with the
  Settings button beside it (the States button under it was removed on 2026-11-21: States is in Settings), hint and accessibility label
  "Search places or addresses", shown while the phase is IDLE or RESUME_OFFERED and no search
  screen, saved places screen or map pick is open, also with nothing installed; on the first run it
  comes with the map, after the driver notice and "Choose your state" (5.4 item 3). Hidden during
  route choice (search is reached there from the route sheet's start row and "Add stop") and while
  guiding (the banner has the top; the stops editor's "Add stop" opens search without the keyboard,
  SP4). G's map chip and the pack-problem notice sit under it; the chip shows only while the pill
  does (the download's notification carries the progress meanwhile).
- **S9** Saved places in search (SP4-SP6, SP10-SP31; every text in
  `docs/architecture-saved-places.md` section 2): Home and Work rows (or "Set home" / "Set work") and
  the "Saved places" row at the top of START; one tap on a saved place in GO_TO goes straight to
  route choice when it is inside the installed maps (else its sheet opens); saved names that match
  the typed text are listed above the engine's results (at most 3, never in SAVE mode); a saved
  place never becomes a recent, and a recent that is a saved place is not listed; nothing about
  saved places is created or changed while driving, and using one needs no typing.
- **S10** Opening hours, phone and website (batch B; the owner, 2026-11-17, docs/next-round.md item
  11). The search pack's `poi` table keeps OSM's `opening_hours`, `phone` (or `contact:phone`) and
  `website` (or `contact:website`); `SearchEngine.poiDetails` reads them by entry id, apart from the
  search itself (its answers and the conformance reference are unchanged), for the result rows of an
  answer and once for a sheet's place (by `packId` and `searchId`: recents of a search have them,
  read by id only while that entry is still the place, `SearchEngine.isNamedNear`: a POI of the same
  name key within `details.match_within_m`, since ids move when a pack is rebuilt (batch G); a
  place without them, a POI tapped on the map, a saved place or such a recent, is looked up in the
  installed pack that covers its point by name and position (batch C2, the owner after batch B):
  `SearchEngine.detailsNamedNear`: the POIs whose name has the same key within `search_app.json`
  `details.match_within_m` (100 m); shown when every one of them that has hours, phone or website
  has the same ones (one shop is often several POIs: a store and its fuel canopies), else nothing,
  never a guess; a saved place by the name it had when saved (`SavedPlace.placeName`, not "Home"),
  batch G; a dropped pin is never looked up). The text is read with
  `ch.poole:OpeningHoursParser` (MIT) and evaluated by `OpeningHours` in `:core:search` at the
  phone's clock and time zone, over the subset that needs no guess: rules with `;` (a later rule
  naming a weekday replaces earlier ones for it, except an `off` with times, which closes only those
  times) and `,` (adds), `24/7`, weekday ranges, times (also past midnight: `Fr-Sa 18:00-02:00`),
  `off` / `closed`; rules naming only public or school holidays are left out (no holiday calendar)
  and the week says "Holiday hours may differ"; dates, months, years, weeks, nth weekdays, sunrise
  and other variable times, open ends, `unknown`, fallback rules and anything the parser refuses
  make the whole text unreadable: nothing is shown (never a guess; 97.1 % of the Pennsylvania pack's
  places with hours are readable). The status, its colour always with its words: green "Open now",
  yellow "Closing soon" (from exactly one hour before closing; the lead's choice), red "Closed now"
  (from the closing minute on); the next change after " · " in US 12-hour time: "until 9 PM",
  "closes at 9 PM", "opens at 7 AM", with the weekday when a day or more away ("opens Mon at 7 AM"),
  "open 24 hours". On the place sheet (the status; the week, Monday to Sunday, at the details
  height, to which a tap on the status raises the sheet) and on every result row of the search
  screen and of the results on the map; recomputed each minute. The place sheet's details height
  (batch G; the owner, 2026-11-20, docs/next-round.md item 20) writes out what the pack has: what
  the place is (the category's label, or the OSM value in words: "Hairdresser"), the whole week
  (today in bold, "Holiday hours may differ" where it applies; nothing without readable hours),
  every number of `phone` on its own line (US-shaped ones as "(814) 555-0123", others as the pack
  has them), every web address of `website` a browser can open (without "http://", "https://" and
  a trailing "/", over 40 characters shortened in the middle with "…") and the postal address from
  `addr:*` and the pack's state ("4662 West Ridge Road, Erie, PA 16506"). A tap on a number or a web
  address opens the dialer or the browser exactly as Call and Website do; a long press copies it
  (the address too) with a short "Copied" note; while guiding (GUIDING, REROUTING) the lines show
  but neither does anything.
  "Call" opens the phone's dialer with the first number (`ACTION_DIAL`, no permission); "Website"
  opens the first address (http or https; a bare domain gets `https://`) in the phone's browser,
  only on that tap; neither while guiding (GUIDING, REROUTING), both again once the trip has
  ARRIVED (the owner after batch B). The app itself makes no request for either (N5). The
  test extra `--es hours_at <local date-time>|off` sets the clock the statuses see.
- **S11** Search results on the map (batch B; the owner, 2026-11-16, docs/next-round.md item 1).
  Typing keeps the list of S2. The keyboard's search action (Enter, `SearchEvent.Submit`) in GO_TO
  while not driving asks the packs for `search_app.json` `on_map.candidates` (100) matches near
  the ranking position (S1), orders them by distance and keeps the nearest `on_map.max_results`
  (20, the lead's cap) within `on_map.within_km` (40.23 km, 25 miles); when none is that close, the
  nearest `on_map.nearest_when_none_within` (5) anyway (the owner after batch B; batch C2): none,
  the screen says "No results"; one, it is picked (as a tap on it: a recent, its sheet, the map moves to it, K1);
  several, the search screen closes and `SearchFeature.results` holds them: RESULT pins on the map
  (`MapScene.pins`, under every other pin; the destination pin's art, smaller), the camera fits them
  (K1), and a short list at the bottom (about three rows, pulled up for more, pulled down to the
  handle and the count, rule D10; the query, "12 places", a close button; opaque, so pins never
  show through the rows). A pin or a row opens that place's sheet (`ResultsEvent.Select`, the pick path of
  K1) and its pin is the PICKED pin; closing the sheet goes back to the list; closing the list (its
  button or Back), a new search, or the removal of a state one of them came from
  (`SearchFeature.closeResultsGoneFrom`, batch C2) ends the results and their pins (the camera stays). Not offered
  while a trip exists: the pill is only shown while browsing (S8), and planning a trip ends the
  results.
  **Since 0.1.15 (the owner, 2026-11-21, after the walkthrough of 0.1.13):** the list and the map
  show the same places in the same order. Every search asks for `on_map.candidates` rows; an answer
  led by a category or a brand match (`SearchResult.tier`) is shown by the rule above (the nearest
  first, within 25 miles, at most 20; so a category's list is in distance order), any other answer
  as the engine's best `merge.limit` rows in its order (`SearchController.shaped`). Enter asks nothing
  again: it puts the list's rows on the map. A shortcut chip and a type of the "View all" grid are a
  whole question: their answer goes straight to the map as Enter's does (in GO_TO while not
  driving; elsewhere a list as before); Back from a grid type's results opens the grid again. While
  the results show, the map's search bar holds their words and an X that ends them; a tap on the
  words opens the search with them typed (`SearchBar(query, onClear)`).
- **S11b** Search this area (0.1.30; the owner, 2026-11-24: "when they drag the map to a different
  area, a button should pop up below the search bar that says "Search this area" that updates the
  pins and list with searches in that window"). While results show, a drag or zoom of the map by
  the user offers "Search this area" first below the search bar once the camera rests. A tap asks
  the results' words again around the map's centre (on_map.candidates rows) and keeps the places in
  the part of the map the user sees (the window less the camera's padding: the search bar, the
  list; a turned map: the box around it), nearest the centre first, at most on_map.max_results. They
  replace the pins and the list (none selected, an open place sheet closes; the rows' distances stay
  from the user); the camera stays where the user put it. None there: "Nothing found in this area"
  and the results stay, until the map moves again. Other results, or none, end the offer
  (`SearchFeature.searchArea`, `AppFlows.areaSearch`).

### 3.9 Drive presentation (D)

- **D1** Banner texts: `primary` as given; a name of at most 16 characters (`SHORT_NAME_CHARS`:
  "Peach St", "US 19", "Waterford"; the primary, each road, each towards name) keeps its words
  together with no-break spaces, so it never breaks across two lines (the lead, 2026-11-16; a
  longer name may wrap between its words); `roadsText` = the first two roads joined by " / " (the
  engine can name more: the exit 24 left turn has three), prefixed "onto " for turns, ramps, merges
  and roundabouts (not for exits, forks, depart, arrive and ferries); `towardsText` = "toward " + towards joined by ", ";
  `distanceText` = `distance(s)`; `then` = `distanceIn(following.distanceM)` (the chip also shows
  the sketch's small "THEN" label, drawn by D); lanes only when `showLanes`.
- **D2** Status priority: Arrived > StopReached (card time) > RerouteFailed > Rerouting > GpsLost >
  WaitingForGps > GpsWeak > OnRoute. The arrival card shows the name, the side, and "Destination
  *distance* away" when the end of the route is more than 100 m in a straight line from the
  destination's place (2.11 "Every request" 2; `DrivePresenter.DESTINATION_AWAY_OVER_M`).
- **D3** Trip bar: arrival clock = now + `remainingS`; remaining time and distance; next stop
  "Stop *n* · distance" when a stop is not the destination; "Simulated" badge when simulated.
- **D4** Route cards in choice order, labels joined with " · ", notes "Tolls" / "Highways" from the
  choice flags; `canStart` when a choice is selected, the phase is CHOOSING, nothing is
  `replanning`, and precise location is allowed. Without precise location `startBlockedText` =
  "Allow precise location to start guidance" (an approximate-only grant counts as not allowed);
  "Simulate drive" stays available. "Simulate drive" plays the route with `Simulation()`, without
  a named script, so without the `default` script's 20 s halt (the lead's ruling, 2026-11-17: as
  built); the scripted drives of section 8 are started with the test extra `--es simulate`.
- **D5** The route sheet never offers a type the presets mark `offered: false` (Eco).
- **D6** Notification: title = primary + distance ("Exit 24 · 0.3 mi"), text = roads, or the
  status text when not on route; "Arrived at …" in ARRIVED. Updated on maneuver change and at most
  every `notification.min_interval_s` (5 s).
- **D7** In REROUTING the banner is not shown (`GuidanceUi.banner` = null): the status card
  "Finding a new route..." (or the failure text with Retry) takes its place until the new route's
  first progress. O6 sets GUIDING at the answer, and the session has no banner until a fix on the new
  route (B6), so the card also shows in GUIDING while `Guidance.banner` is null and the GPS is neither
  waiting nor lost: a car still off the new route never has an empty top (integration review,
  2026-11-16; before, the `wrongturn` detour showed nothing for about 30 s). While only `offRouteSuspected`, the banner stays as it was (it does not advance,
  B2). `StopsEditorUi.busy` and `RouteSheetUi.busy` come from `TripState.replanning` (and
  PLANNING).
- **D11** The road being driven (item 22, B12): `GuidanceUi.currentRoad` in GUIDING while the car is
  on the route (not while `offRouteSuspected`, not in REROUTING or ARRIVED): a small label in the
  banner's colours at the bottom of the map, level with the speed pair and the map buttons
  and between them (test tag `current-road`; a screen reader says "On Peach St"); one line, a long
  name ends in an ellipsis. The route line covers that road's own name on the map, so the label is
  where the driver reads it.
- **D9** Lane warning (batch C1, B9): while `Guidance.laneWarning` is set, `BannerUi.lanes` is its
  lanes and `BannerUi.laneNote` its line, drawn under the lane row in the banner's ink (semibold, at
  most two lines; test tag `lane-note`); the banner's maneuver, distance and chip stay. Gone with the
  warning.
- **D8** Speed-limit sign and speed (batch A; the owner, 2026-11-17, docs/next-round.md item 11).
  `Guidance.speedLimitKmh` is the posted limit of the stretch being driven: the session reads
  `legs[].annotation.maxspeed` of the engine's OSRM-format answer (`SpeedLimits` in `:nav:session`,
  one entry per segment of the flattened line, looked up at `traveled.index`; "none", "unknown", a
  missing annotation or a leg whose count does not match its line give null). `GuidanceUi.speed`
  (`SpeedUi`) in GUIDING and REROUTING only (never while browsing or choosing, not in ARRIVED): the
  US sign (MUTCD R2-1, "SPEED LIMIT" over the number, black on white by day and by night) with the
  limit in whole mph (89 km/h reads 55) or km/h as Settings' units, only while the limit is known
  and the car is on the route (not in REROUTING, not while `offRouteSuspected`); beside it a round
  badge with the speed from the GPS fixes (`Vehicle.speedMps`) during all of guidance, also where no
  limit is known, its number white, **yellow from 5 mph over** the limit shown and **red from 10 mph
  over** (8 and 16 km/h in metric), compared on the whole numbers shown; the sign never changes.
  With the GPS not GOOD (weak, lost, waiting) or no speed in the fixes the number is a dash ("–",
  read "Speed unknown"). The pair sits at the bottom start of the map (the owner, 2026-11-18):
  level with the map buttons (`SPEED_CLUSTER_BOTTOM_GAP`) above the trip bar upright, beside the side
  panel on its side; while the map credit's short line shows in that corner (the first seconds of
  guiding, `DriveOverlay(creditShown)`) it sits higher (`SPEED_CLUSTER_CREDIT_GAP`), and it slides
  down when the line fades (the owner, 2026-11-18); it never
  covers the banner (its lane row included), the GPS chip or the stop card, the trip bar or the map
  buttons: in a window too short for it between the top stack (banner, chip, stop card) and the
  bottom block (split screen, about 390 dp tall) the pair is left out while there is no room and
  comes back when there is (batch B). GPS speed often reads a few mph below a car's speedometer, which is built to read
  high.
- **D10** Every bottom sheet's heights (batch F; the owner, 2026-11-18, docs/next-round.md item 15).
  The results list (S11), the route sheet and the place sheet (S7) share one frame (`:ui:theme`
  `Sheets.kt`: `SheetFrame`, `SheetState`, `SheetSteps`) and one set of rules. Each has a lowest height
  that leaves the map free: the results list only its handle and header (the query, "12 places", the
  close button), the route sheet one small card per route (its time over its types, the chosen one
  outlined; a tap chooses it as the full cards do), the problem when there is no route, and Start
  with its reason, the place sheet its icon, its title on one line and the close button. The results
  list has three heights (that, about three rows: 30 % of the screen and at least 150 dp of rows,
  about 60 %); the route sheet two (that, all of it); the place sheet three (that, the sheet of S7
  and S10, and its details height of S10, at most 70 % of the window and scrolling inside; batch G).
  A drag anywhere on a sheet moves it
  one height for every 40 dp (`SHEET_STEP`) it travels in one direction; on the rows of a list a drag
  up raises the sheet before the rows scroll, a drag down scrolls the rows to their top first and
  then lowers it; the sheet rests at its heights (no height in between, no animation, so the camera
  gets one change per step). A tap on the handle (or on the header joined to it) raises one height,
  from the highest back to the lowest. A screen reader finds the handle by "Results list", "Route
  choice" or "Place details", with its height ("Lowered", "Half open", "Full height") and the actions
  "Raise the sheet" and "Lower the sheet". A new results list and a new place open at the middle
  height, a new trip's sheet at full height; the height is kept across a turn of the phone, and the
  results list's while a result's place sheet is open. The camera's insets follow the height the
  sheet rests at (the app measures each sheet, `AppRules.cameraInsets`, `DriveOverlay.onInsets`), so
  the results (K1), the routes (K2) and a picked place (K1) are fitted to the space left, and fitted
  again when the sheet goes back up. On its side the browsing sheets stay at the bottom across the
  width with the same heights; the route sheet is in the side panel, can be lowered there too, and
  the camera keeps fitting to the side of the panel (`start` = the panel's width).

### 3.10 Resume after process death (R)

- **R1** `trip.json` is written at Start, at every stop change or stop reached, at every type or
  option change during a trip, and every `resume.refresh_s` (30 s) while GUIDING or REROUTING;
  `savedEpochMs` is the wall-clock time of that write. It is deleted on End, Done and
  DiscardResume, and on arrival (A3).
- **R2** On start-up, a snapshot with `wallClockMs() − savedEpochMs` ≤ `resume.max_age_s` (1800 s)
  puts the session in RESUME_OFFERED; older snapshots are deleted. So a 45-minute trip killed at
  minute 40 is offered for 30 minutes after it died. Test: a 45-minute trip on a virtual clock,
  killed at minute 40, restarted 10 minutes later: offered; restarted 31 minutes later: deleted.
- **R3** Resume re-plans the trip's `routeType` and options from the current position to the stops
  not reached and starts guidance (`RouteReplaced(RESUMED)` after `GuidanceStarted`). A `trip.json`
  whose type presets.json no longer has (a trip saved with Shortest before batch D) resumes with the
  presets' default (Fastest), shown as such from start-up. The offer is
  only ever shown in the visible app: when the process died with the app in the background, the
  user sees "Continue to …?" the next time the app is opened (a background start of the
  foreground service is not allowed, so there is no notification action for it).
- **R4** End with Undo (the owner, 2026-11-16): the trip bar's End stays one tap and ends the trip
  at once (IDLE, hold released, `trip.json` deleted, `GuidanceEnded`). The app then shows a bar
  "Trip ended" with "Undo" for `END_UNDO_MS` (10 s; it goes at once when a new trip begins).
  `UndoEnd` within that time, and before any other intent, re-plans the stops not reached with the
  same route type and options from the current position (as R3, waiting for a fix as P1 does),
  and guides again (`GuidanceStarted`, no `RouteReplaced`; a simulated drive is simulated again,
  from the new route's start); the session stays IDLE while it plans. When that plan fails the
  trip is offered as after a restart (RESUME_OFFERED with the problem: Resume retries, Discard
  drops it). After the bar is gone, or after any other intent, the End is final. End from ARRIVED,
  and End from the notification's action, offer no Undo. While the session plans after Undo, the
  bar reads "Continuing the trip..." (no action) until the phase leaves IDLE (part 1's review:
  without it nothing showed for up to 15 s without a fix). Tests: `UndoEndTest` (`:nav:session`),
  `AppRulesTest` (`AppRules.endBar`).

## 4. Background work, permissions and the network

Decision: **a foreground service of type `location`, started when guidance starts.** The prototype
includes it. Downloads have their own foreground service (type `dataSync`, G's).

- `GuidanceService` (in `:app`, integrator) holds no logic: it keeps the process at foreground
  priority while the session is in GUIDING, REROUTING or ARRIVED, so guidance continues with the
  screen off or another app on top. The session keeps collecting positions through the shared
  source the whole time (it lives in `AppGraph`, not in a screen).
- Started from the activity at Start (the app is visible: allowed on Android 12+);
  `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_LOCATION)`; stops itself when the
  phase leaves those three. A simulated drive with no location grant (Android 14+ refuses a
  location-type service without one) starts it as a plain service that only posts the
  notification, without foreground priority (integration review, 2026-11-16). Started again on Resume after a process restart (the user taps Resume
  in the visible app, rule R3).
- Downloads run in `DownloadService` (`:feature:states`, G; `docs/architecture-downloads.md`
  section 5), type `dataSync`, started only from a tap or from the visible app, separate from
  `GuidanceService`. Both may run at once (a download during a trip); the install of a download
  waits until the trip has ended (2.2 rule 4).
- Manifest (the merged manifest of `:app`, checked with `check_network_rule.py manifest`, 5.4 item
  14): the permissions are exactly `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_DATA_SYNC`,
  `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, plus permissions defined
  under the app's own id (androidx core's `<app id>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).
  `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE_DATA_SYNC` and `WAKE_LOCK` are for the
  downloader only and come from `:feature:states` (its manifest, and OkHttp's Android AAR for
  `INTERNET`; MapLibre's AAR declares the first two as well). Never `ACCESS_WIFI_STATE` (MapLibre
  declares it; removed with `tools:node="remove"`), `RECEIVE_BOOT_COMPLETED`,
  **`ACCESS_BACKGROUND_LOCATION`** ("while in use" permission covers a location service started
  while the app is visible) or `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; no
  `android:usesCleartextTraffic`; a `networkSecurityConfig` only in the debug variant (plain http
  only to the development server, `docs/architecture-downloads.md` 8.2); exactly two services with
  a foreground type, both not exported: `GuidanceService` (`location`) and `DownloadService`
  (`dataSync`). Since Round G0 `:app`'s manifest no longer removes `INTERNET` and
  `ACCESS_NETWORK_STATE`, so they must not be stripped from `:feature:states` once I wires it; the
  other entries of the list arrive with I's wiring. On `<application>`, `android:allowBackup="false"`
  stays and `android:dataExtractionRules="@xml/data_extraction_rules"` is added with I's
  `res/xml/data_extraction_rules.xml` (rule PV4 below).
- Demo apps and test APKs declare no network permission: MapLibre's are removed in `:map:demo`'s
  manifest and in the androidTest manifest of `:map:layers` (since Round 0's
  review), as `:routing:demo` already did (and `:nav:ferrostar` and `:routing:ferrostarcheck` until
  they were deleted on 2026-11-17). The exceptions are the
  APKs that hold the downloader: `:demo:states` (G's device checks) and `:feature:states`'s own
  test APK. They hold `INTERNET` already (OkHttp's Android AAR, which `:core:download` brings for
  `PackHttp`, declares it) and get `ACCESS_NETWORK_STATE` and the service's permissions from
  `:feature:states`'s manifest once G has written it (it is empty after Round G0). A track that adds
  a library to a demo or test APK checks its merged manifest.
- **The network rule** (replaces Round 0's "no `INTERNET`"; in full, with how each part is proved,
  in `docs/architecture-downloads.md` section 4):
  - **N1 one class per service** (since batch O1, docs/online-design.md section 6). `PackHttp`
    (`:core:download`) for packs and `OsmHttp` (`:app`, `app/online/`) for OpenStreetMap (notes; since batch O2 the sign-in and edits; since batch O3 a new place) are
    the only code that opens network connections; later `LookupHttp` for online search (batch O4).
    On Android their sockets are tagged by G's socket factory (`TrafficStats` tag `0x0B1A` for
    packs, `0x0B1B` for OSM). The one other file that touches OkHttp is I's `OfflineMapHttp` (N5).
  - **N2 where.** Each class has fixed hosts. `OsmHttp`: OSM's test server
    (`master.apis.dev.openstreetmap.org`, API and sign-in) in debug builds and release builds made
    with test extras, `api.openstreetmap.org` (API) and `www.openstreetmap.org` (sign-in's token and
    revoke) only in the plain release; every address a name resolves to must be public. `PackHttp`: only the configured catalog address (`beamline.catalogUrl`; none = downloads off
    in that build), URLs inside its scope and the redirect targets its rules allow; every address
    a host resolves to must be public, except the debug build's own local server.
  - **N3 what.** `OsmHttp`: only the requests the OSM API defines for the features built (batch O1:
    `POST /api/0.6/notes.json`, a note's text and point in the body; batch O2: the sign-in's token
    and revoke requests, the user's name, and an edit's read of the place, changeset create, element
    upload and changeset close; batch O3: OSM's map of a small box around a pressed point, about 120 m
    across, for the duplicate check, and a new node at the pressed point; the table of
    `docs/architecture-downloads.md` 4.1),
    `User-Agent: Beamline/<version>`, with an account `Authorization: Bearer <token>` (the token
    never logged), no cookies, no cache, no identifiers, no position but the point the user chose. `PackHttp`: `GET` only, with fixed headers (`User-Agent: Beamline-Packs/1`,
    `Accept-Encoding: identity`, and when needed `Range`, `If-Match`, `If-None-Match`); no cookies,
    no body, no HTTP cache, no position, no identifiers of any kind, no query string the app builds.
    A log line names a request by its catalog-relative path, never the host, a query or a redirect
    target, and never holds an exception's message, a server's answer or the installer's words (the
    logging rule PV2 below, which every module follows).
  - **N4 when.** Only after a user action. `PackHttp`: the first-run picker or the States screen opening, a
    download, update or continue the user started (with its automatic continuation when the
    network returns or the app is reopened). `OsmHttp`: the user's Send, Upload, Sign in or Sign
    out (and the edit form reading the place it opens on, the add form reading OSM's map of the spot), or a note, edit or new place the user confirmed
    that waits in the outbox, while the app is open; it refuses every request while its switch
    ("Online features" and "Contribute to OpenStreetMap") is off, so with "Offline only" nothing
    leaves but the pack downloads. Nothing at app start but that outbox, nothing in the background, no
    analytics, no crash reports.
  - **N5 everything else offline.** MapLibre gets an HTTP client that refuses every request
    (`HttpRequestImpl.setOkHttpClient(OfflineMapHttp.client)` in `Application.onCreate`, right after
    `MapLibre.getInstance` and before any map view exists; `MapLibre.setConnected(false)` stays); the composed styles point only
    at local files; Valhalla's configuration never sets `tile_url`; the route follower is Beamline's own
    geometry (Ferrostar, used only through `NavigationController`, was dropped on 2026-11-17).
    Maps, routing and search never need the network. A place sheet's "Website" (S10) hands the
    address to the phone's browser only when tapped (an `ACTION_VIEW` intent; "Call" an `ACTION_DIAL`
    one): the app itself opens no connection for them.
  Proved by the merged-manifest check, a source lint (`check_network_rule.py scan`), JVM tests
  (the rule itself, the composed styles, `OfflineMapHttp`, the engine configuration, `OsmHttpTest`,
  `OsmEditTest` and `OnlineSettingsTest` for the switches) and, on the device, per-app traffic accounting (with
  "Offline only": nothing outside tag `0x0B1A`) and a packet capture (E2E, section 8).
- **Privacy of the user's places** (rules PV1-PV8, in full with how each is proved in
  `docs/architecture-saved-places.md` section 4; driving safety is its rule SP4):
  - **PV1 only on the phone.** Saved places are written only to `no_backup/saved-places.json` (and
    its `.tmp` and `.broken-<n>` files); nothing about a place goes to `files/`, the cache, external
    storage or the network (the network code is `PackHttp` and `OsmHttp`, N1-N3; a note the user
    sends carries only its text and the point the user chose). The OSM outbox (batch O1,
    `no_backup/osm-outbox.json`; since batch O2 also the user's edits and, once sent, the local
    copy shown on the place sheet) and the OSM account (batch O2, `no_backup/osm-account.bin`, the
    token and the user's name sealed with an Android Keystore key) live beside the saved places
    under the same rules PV1-PV8.
  - **PV2 never in a log.** No log line, exception message, `toString()` or crash text holds a saved
    place's name, place name, subtitle or coordinates. `Place`, `SavedPlace` and `SavedMarker` print
    no name or point (2.1, 2.5). `:core:search`, `:feature:search`, `:map:scene` and `:app`'s `online/` package (the OSM outbox) have no log call
    in their main sources, and the code that holds saved places builds no exception message from a
    value. Everywhere else a log call is a tag and one plain literal, or is marked on its line
    `// log-ok: <what the values are>` and prints only numbers, durations, counts, sizes, enum
    names, pack ids, file names and catalog-relative paths (N3); an exception is never passed to a
    log call and its message is never logged (its class name is, marked); a line holding a word
    such as `lat`, `lon`, `point`, `place`, `stops` or `query` needs `// private-ok: <reason>` and
    the location-log guard of PV7. This binds every module (5.2).
  - **PV3 proved** by `android/scripts/check_private_logs.py` (S): `scan` over the main sources of
    every module `:app` depends on, `logcat` over the emulator journey's logs (canary words and
    points), `manifest` (PV4); and by leak tests on the JVM in `:core:search` and `:feature:search`.
  - **PV4 backup and transfer: excluded.** `saved-places.json`, `recents.json`, `trip.json` and `last-camera.txt` live
    in the no-backup folder (2.12), which Android leaves out of cloud backup and device-to-device
    transfer whatever the manifest says; `android:allowBackup="false"` stays; the data extraction
    rules exclude every domain from cloud backup and from device-to-device transfer, so nothing of
    the app leaves the phone (the lead's decision for integration, 2026-11-16).
  - **PV5, PV6.** Nothing about saved places leaves the app until an export exists (later; owner
    question 11); names show only on the app's screens and in the guidance notification ("Arrived at
    Home"), never a description or coordinates there.
  - **PV7 the owner's phone** (5.2): every session started cold with the test extra
    `--es private_data test`, so only made-up places are shown, logged or captured; never clear data,
    uninstall, back up, `run-as` or pull the app's files; the puck's position line only with
    `--ez location_log true`.
  - **PV8 the keyboard.** The search, save and rename fields ask the keyboard not to learn what is
    typed (`IME_FLAG_NO_PERSONALIZED_LEARNING`, no autocorrect or suggestions).
- Permissions in context: location is asked when the user taps my-location, Directions or a saved
  place's one-tap route (SP12) without a start point; `POST_NOTIFICATIONS` (Android 13+) once, right
  after the first Download has started or the first time Start is tapped, whichever comes first.
  Refused notifications: guidance and downloads still run (their notifications are just not visible;
  the download's progress view says so). Location refused, **or granted as approximate only**: Start
  is off with the reason (D4) and the app offers to ask for precise location again (the reason on the
route sheet is a button that asks; when Android no longer shows the question, a message points to
the app's settings); the browsing
  puck still works with an approximate fix, and a simulated drive still works. (Guidance uses only
  `gps` fixes, G1, which an approximate-only grant never delivers.)
- Notification: channel "Guidance", low importance, silent, ongoing; text from
  `DrivePresenter.notification` (updated on maneuver change and at most every
  `notification.min_interval_s`); action "End trip" (sends `NavIntent.End`); tap opens the app.
  Downloads use their own channel "Downloads" (`docs/architecture-downloads.md` 1.1 step 7).
- Screen: while the drive overlay is visible and guiding, the window keeps the screen on. While a
  state is being installed **from a file**, the States screen keeps the screen on and says "Keep
  Beamline open until the install has finished" (downloads and their installs run in
  `DownloadService`; running file installs there is a later option).
- Later (not this round): Android Auto (same session), voice and chime on the Announce events,
  heads-up turn notifications, Android 16 live-update notifications, a "stop guidance after N minutes
  stationary" rule.

## 5. Ownership, collisions and definitions of done

### 5.1 Ownership table

Paths are under the repository root. "Owns" = may create, edit and delete; everything else is
read-only for that track. Each track also owns its report `docs/experiments/<n>-*.md` (P 12, N 13,
S 14, D 15, M 16, A 17, G 18, I 19; 11 is the real-phone baseline, `11-phone-baseline.md`, measured
outside the tracks), its module READMEs, and its scratch outputs under `data/r3-<key>/`.
(The table and the paragraphs below are the feature round's record. Since 2026-11-17 the next run
has no tracks; `:nav:ferrostar` and `:routing:ferrostarcheck` are deleted, and with Ferrostar
went JNA, core library desugaring and `desugar_jdk_libs`, the licence gate's one exception:
see 6.14, 6.34 and 6.35.)

| Track | Owns | Needs from a device | Definition of done (proved by) |
|---|---|---|---|
| **0** scaffold | done (appendix B) | none | `./gradlew assembleDebug test` from an empty build folder, with the two search databases set (5.3); `tools/check_contracts.py` all the same |
| **G0** set-up of downloads | done (appendix B) | none | the same, with track G's modules and `docs/architecture-downloads.md` |
| **SP0** set-up of saved places | done (appendix B) | none | the same, with the saved-places contract files and `docs/architecture-saved-places.md` |
| **P** packs and map | `android/core/packs/`, `android/core/installed/`, `android/routing/packs/`, `packbuilder/map/`, `packbuilder/pack/` (new assembler), `packbuilder/search/build.py`, `packbuilder/addresses/` (`addrlib/` change-by-request, below), `tools/fetch_android_map_pack.py` (retire), `docs/pack-format.md`, `android/scripts/{sync_assets,read_pack,push_pack,pack_variants}.py`; writes `android/app/src/main/assets/style/` and `android/app/assets-local/` only through `sync_assets.py` | none required (JVM tests on the real archives); the E2E covers the install on the emulator and the phone | see P below |
| **N** navigation | `android/routing/{core,banner,valhalla,demo,ferrostarcheck,scripts,testdata}/`, `android/routing/README.md`, `android/nav/session/`, `packbuilder/routing/` except `units.json` and `camera.json` | emulator (`:routing:demo` regression needs the PA+OH tile set pushed, 1.8 GB) | see N below |
| **S** search | `android/core/search/`, `android/core/search-bench/`, `android/demo/search/`, `android/feature/search/`, `android/scripts/{copy_search_rules,search_demo,check_private_logs}.py`, `packbuilder/search/` except `build.py` (with the new `saved_places.py` and its test; `ranking.json` change-by-request, below) | emulator (search + address files pushed, about 0.45 GB) | see S below |
| **D** drive feature | `android/feature/drive/` | emulator (no data) | see D below |
| **M** map layers | `android/map/`, `packbuilder/routing/camera.json`, `data/art/markers/` (rebuilt with P's `build_markers.py`, which M runs but does not edit) | emulator with the PA z14 map file pushed to `:map:demo` (245 MB) | see M below |
| **A** about, identity and the follower | `android/feature/about/` (with its `scripts/notices.py`), `android/demo/about/`, `android/nav/ferrostar/` | emulator (no data) | see A below |
| **G** getting packs | `android/core/download/`, `android/feature/states/`, `android/demo/states/`, `packbuilder/catalog/` (with `download_rules.json`), `docs/catalog-format.md`, `android/scripts/check_network_rule.py`, `data/catalog/` | emulator AVD `beamline_g`, port 5574, no pushed data; the development server (`serve_packs.py`) on port 8765 | see G below |
| **I** integration (after the round, alone) | `android/app/` (the States screen is G's `StatesScreen`; I writes `OfflineMapHttp` with `OfflineMapHttpTest` and `ComposedStyleOfflineTest`, and owns the Round G0 changes to the manifest and build file; I writes `res/xml/data_extraction_rules.xml`, PV4, and rewrites `:app`'s log lines as PV2 says, 5.4 item 14), `android/README.md`, `README.md` | emulator, then the phone (only I touches the phone, only as PV7 allows) | section 5.4 and the E2E test of section 8 pass on the emulator and the phone |

**Change-by-request files** (the owner edits them only when the other side asks, in the same
wave, so the Python and Kotlin sides move together): `packbuilder/addresses/addrlib/` (P owns; S's
`AddressNormalizer` is its port and the strict conformance run compares them) and
`packbuilder/search/ranking.json` (S owns; P's builder hashes it into `ranking_sha256`).

**P: packs and map.** Delivers: manifest v3 (2.11) in `:core:packs` and the Python reader;
`InstalledLibrary` implementing `InstalledData` with one flow for all parts: inspect → free space
(parts + the new merged routing tar + the staged routing tar + `free_space_margin_mb`) → copy and
hash every part into `.staging-*` (skipping `addresses` when asked) → cheap checks →
`RoutingPackManager.install` from the staged tar with `verifyTarHash = false` → delete the staged
tar → rename into place → publish a generation. Removal: refused while held → rename aside →
routing uninstall → publish → delete. `holdChanges` returns null while an install or removal runs.
`cleanUp` restores the invariant of 2.11 (orphan routing states uninstalled, half-removed folders
finished). `StyleComposer.compose(..., packs: List<PackInfo>)` and the credit line from the loaded
packs' `attribution`. `packbuilder/pack/assemble.py` (standard library only) building `us-pa` and
`us-oh` v3 archives from one OSM snapshot: map z14 (profile patch 2), routing (graph build
261114-pa-oh), search PA and OH, addresses PA and OH (slim), each part with its attribution.
Builders: fingerprints, `ranking_sha256`, LF-normalised hashes, locality indexes in the address
file. The public-domain overview synced into the app; style follow-ups (interstates and state lines
legible at z3-5, the credit), `count_pois.py` re-run on the z14 packs, `sync_assets.py check` in
sync. **First wave hand-over:** `data/r3-packs/oh-search.sqlite` and `data/r3-packs/pa-search.sqlite`
with the new search meta (fingerprint, `ranking_sha256`), announced to S when written.
**For downloads** (`docs/architecture-downloads.md` 6.6 and 9.2): manifest v3 top-level
`pack_version` written by `assemble.py` and read into `PackInfo.packVersion`; `docs/pack-format.md`
gains `pack_version` and `"source": "download"`; `installsFromFolder = true` and
`startInstallFromFolder` (the archive flow with "copy and hash into `.staging-*`" replaced by "move
each part file from the folder into `.staging-*`, then hash it there"; `addresses` left in the
folder when skipped; on any failure or cancel before the final rename the moved files go back;
`install-record.json` `"source": "download"`); `inspect` never lists free space among `problems`;
`changesOptionalParts = true`, `startAddOptionalParts` and `removeOptionalParts` (house numbers
added to or removed from an installed pack: the file moved in or deleted, the install record
replaced by rename, a generation published).
Done when: JVM tests pass for install from the real PA and OH archives (all parts; routing merged
and the staged tar gone; `routing.json` kept), install without addresses (credit without the
address sources), update of an installed state, removal (routing uninstalled, the other state still
routes), refusal while held, `holdChanges` null while an install runs, refusal of a v2 pack
("rebuild the pack"), too little space (counting the merge peak), a fingerprint mismatch (search
unusable, map and routing usable), crash recovery at four points (after staging, after the routing
commit, before the rename, during removal), one generation per change; for downloads: the folder
install of the real PA folder (files moved, none copied: the folder holds no part file afterwards
and the installed files keep their last-modified times), a failure in the middle moves the files
back, `packVersion` read, space counted without the moved parts; PA installed without house
numbers, then added from a folder holding only the manifest and `addresses.sqlite` (the credit
gains the address sources, nothing else in the pack folder changes), then removed (credit back,
file gone), each refused while held, and a folder whose manifest has another `pack_version`
refused; the merged set's `valhalla.json` has `mjolnir.tile_url` empty or absent (rule N5);
`read_pack.py --verify` passes on both archives; overview screenshots at z3.5 and z5, day and
night, looked at. **For saved places** (`docs/architecture-saved-places.md` 6.1, 8; first wave,
announced to S and M): the sprite sheets rebuilt with the three icons `saved_home`, `saved_work`,
`saved_place` (Round SP0 put their drafts and the `saved` colour pairs in `assets/icons/`; no code
change expected), the contact sheet refreshed for the owner, the style bundle synced into the app.
P runs the steps one by one, not `build_art.py` (whose `markers` step writes M's folder):
`build_sprites.py` (writes `data/art/sprites/` and `data/art/build/light|dark/`), `node
generate_style.mjs` (`packbuilder/map/style-bundle/`), `sync_assets.py`, `build_sheet.py`
(`assets/sheet/index.html`, git-ignored, and `data/art/route/metrics.json`; it reads M's
`data/art/markers/states/`, so P runs it when M is not rebuilding the markers) and, for the PNG,
`render_art.py page` (`data/art/sheet/`); the data rule of 5.2 gives P those folders.
Done when also: the three are in the `light` and `dark` sprites, 1x and 2x, of the synced bundle;
`sync_assets.py check` passes; the contact sheet shows them.

**N: navigation.** Delivers: presets format 2 with `compose` in `route_lib.py` and `:routing:core`
(2.11, Avoid tolls as `exclude_tolls` with its no-route fallback, `option_fallback_when: false` on
Shortest, the fallback warning of rule 6); `reference_routes.py` recordings for the 21 trips ×
{fastest, shortest, simpler, eco} × {none, highways, tolls, both}, plus Carlisle to King of Prussia
× Shortest × tolls and the two end cases of 2.11 (Carlisle to the Turnpike point × {fastest,
shortest} × tolls; Erie to the I-90 point × {fastest, shortest} × highways); the Kotlin planner
equal to Python on all of them and on the old 126 plans; `banner.json` `road_abbreviations`
(Street→St, Drive→Dr, Avenue→Ave, Road→Rd, Boulevard→Blvd, Highway→Hwy, Parkway→Pkwy, Lane→Ln,
Court→Ct, Place→Pl, Circle→Cir, Terrace→Ter; last word only) and `distance_text` removed;
`GuidanceRules.parse` and `bundled`; `:nav:session` complete; `RouteSimulator` and the named
scripts; `TripStore`; a test follower implementing `RouteFollower` with the contract of 2.4. A
golden trace `android/routing/testdata/nav/exit24.json` (inputs: the route recording and the
simulation script; outputs per fix: phase, *U*, shown distance, chip, lanes shown, gps, motion,
vehicle point) usable by a later Python port and by A.
Done when: JVM tests pass for the exit 24 drive against the golden trace and for each rule group
of section 3 (at least one test per rule id: G1-G6, B1-B8, O1-O9, A1-A3, C1-C7, P1-P5, M1-M3,
R1-R3) with a recorded-answer engine and the test follower, on a virtual clock; the planner tests
include Shortest × Avoid tolls with `has_toll` false, and the two end cases giving a route with the
fallback warning, not NO_ROUTE (a destination on the Turnpike with Avoid tolls; on I-90 with Avoid
highways and Shortest); a 3-stop route shows OSRM step *k* =
Valhalla maneuver *k* (flattened) or N's mapping handles the difference; `:routing:demo`
`DeviceRoutingTest` passes with format 2. **For saved places** (`docs/architecture-saved-places.md`
6.1): `TripStore` writes and reads `PlaceSource.SAVED` and `Place.savedId`, in the folder I gives it
(the no-backup folder); one JVM round-trip test of a stop with a saved id; any log call in
`:nav:session`'s main sources follows PV2 (a plain literal, or marked and free of stops and points).

**S: search.** Delivers: `max_query_chars` in `ranking.json`, applied in `query.py` and the Kotlin
engine (the service's cap removed), conformance reference regenerated and the strict test run
equal; `SearchHub` (per-generation services, warm-up at open); fingerprint check at open;
`MultiPackSearch` (S3-S4); `RecentPlaces`; `search_app.json` copied in; `SearchFeature`;
`SearchBar`, `SearchScreen`, `PlaceSheet` with category icons cut from the style's sprite sheet by
`assets/poi_icons.json` (copied in by `copy_search_rules.py`). The sprites are in `:app`'s assets
(`style/sprites/`); for its screenshot tests S adds
`getByName("androidTest").assets.directories.add("../../app/src/main/assets")` to the `sourceSets`
of `:feature:search`'s build file, as `:map:demo` and `:routing:demo` do for their main source set
(one copy in git). Until P announces the sprites with `saved_home`, `saved_work` and `saved_place`
(P's first wave), those three names are missing there: S's tests register a stand-in (a plain
square of the icon's size in the `saved` colour, test code only), and the screenshots that show a
saved icon are taken after the announcement. Until P's first-wave files exist, the
merge tests run on fixtures: two copies of the PA search file with different `ranking_sha256`, and
a hand-made second pack (a few rows near Conneaut, Ohio) with a synthetic fingerprint.
Done when: strict `:core:search:test` passes (conformance equal after the cap moved); JVM tests for
the merge (fixtures; and, once P's files exist, the PA and OH search files: a query near Erie
returns Pennsylvania and Conneaut, Ohio results; duplicates across the line merged; different
ranking hashes fall back to pack-first), fingerprint mismatch refused, recents (order, de-dup, max,
broken file), the controller (debounce, stale answers dropped, shortcuts, NO_SEARCH_DATA with
OpenStates, ADD_STOP and SET_START modes, OPEN_STATES for an outside place); emulator screenshots,
day and night, looked at: empty screen with shortcuts and recents, "sheetz", "rest stop", "4319
peach st" near Erie, a place sheet routable and one outside the maps. S also fixes
`RulesAndVersionsTest.missingOrForeignVersionsAreRefused`, which fails instead of skipping when no
search database is configured (it calls `TestEnv.requireSearchDb()` inside `assertThrows`).
**For saved places** (`docs/architecture-saved-places.md`, which has every text, rule and test in
full): `SavedPlaces` (SP50-SP56: structure-only reading, the persisted notice, atomic writes on its
writer), `SavedRules`, the `SavedPlacesFile` codec, `savedNameKey` and the grapheme count (SP26),
`foldText`, the matcher (SP20); the `SearchFeature` additions (SP4, SP10-SP15, SP18-SP23, SP30,
SP31, the sheet of SP19); `SavedPlacesFeature` (its 2.2-2.6, SP16, SP23-SP25, SP27, SP45); the
composables (slot rows, notice, "Saved places", "Use my position" and "Choose on the map" rows,
saved result rows, `SavedPlacesScreen` full and pick-only, `SavedDialog`, `MapPickBar`, the pill's
hint of S8, the sheet's "Save", "Edit" and "Saved as"); accessibility and layout (SP5, SP6); the
keyboard flag (PV8); `packbuilder/search/saved_places.py` and its test; `check_private_logs.py`
(PV3). Done when also: the JVM tests of its 10.1 (the leak tests included) and the checks of its
10.2 pass; `copy_search_rules.py --check` covers `search_app.json`; `python -m pytest
packbuilder/search/tests/test_saved_places.py` passes; `check_private_logs.py scan` passes on S's
modules; emulator screenshots of its 10.4, day and night, looked at; the keyboard check of PV8 done (the
editor flags on the emulator; Gboard's mark is I's, `docs/architecture-saved-places.md` 10.3 step 18).

**D: drive feature.** Delivers: `DrivePresenter`, `EventCards` and `DriveOverlay`: banner
(maneuver icons and lane arrows drawn from the path data in `docs/design/turn-banner.html`, the
chip with its "THEN" label, the three lane states, no hint text), route sheet (start row, cards, the
two option toggles, Start, "Simulate drive" in the overflow menu, the start-blocked reason), stops
editor (up, down, remove, best order, add from search), trip bar, status chips, stop and arrival
cards, resume card, Recenter and Overview buttons, notification text.
Done when: JVM tests of `present` for the three sketch frames (exact texts: "Exit 24" / "US 19 /
Peach St" / "toward Waterford" / "0.5 mi" / then "In 0.3 mi" / no lanes; "Turn left" / "onto US 19
/ Peach St" / "400 ft" / "In 0.2 mi" / allowed-best-not; "Turn right" / "onto Downs Dr" / "0.2 mi"
/ no chip / not-not-not-best), metric units, each status (D2 order), no banner in REROUTING (D7),
route cards with merged labels, Eco never shown, Start blocked without precise location, trip bar
clock in `America/New_York` (12- and 24-hour), `EventCards` timing, notification texts; emulator
screenshots of the three frames by day and night next to the sketch, looked at. **For saved
places**: one JVM test: `present` with a stop made from a saved Home shows the row title "Home" and
its description, and `FromPlace(home)` gives `fromText` "Home" (`docs/architecture-saved-places.md`
5.7; no new contract).

**M: map layers.** Delivers: `MapSceneMapper`, `CameraDirector`, `CameraRules.parse`;
`MapLibreController` (GeoJSON sources for chosen line, driven part, alternatives, pins, vehicle;
line layers per `route_colors.json` candidate B with casing and widths by zoom, before
`roads_oneway`; markers above all labels, pieces and order per `assets/markers/SPEC.md`, beam only
with the night theme, glide animation between fixes; tap and long-press with POI hits from the
`pois` and `pois_driving` layers); marker PNGs rebuilt in candidate B with
`.venv/art/Scripts/python packbuilder/map/style/build_markers.py --route-candidate B` (writes
`data/art/markers/`; today's export there is candidate A) and copied with `route_colors.json` into
`android/map/layers/src/main/` by `android/map/layers/scripts/sync_art.py` (with `--check`);
`:map:demo` replaying the exit 24 geometry with hand-made or traced states.
Done when: JVM tests of the mapper (choosing, guiding, traveled cursor, lenses, stale, pins,
`glideMs` from the previous position) and of the camera rules K1-K7 on a virtual clock; the
camera.json copy equal to its source; emulator screenshots, looked at: route B by day and night at
z12 and z16, alternatives, pins, puck with cone, arrow moving, stopped with lenses lit, night beam,
stale arrow, the tilted heading-up follow at the exit, recenter after a pan, a style reload (day to
night) with the layers back; frame rate while following at Erie z16 measured (indicative only:
shared emulator). **For saved places** (`docs/architecture-saved-places.md` 2.8, 5.2):
`SavedMarkerRules.parse` and `bundled`; the saved markers of `MapSceneMapper` (kinds, min zooms,
scale, a stop's saved place left out) and `savedBelowRoute`; in `MapLibreController` the saved
source with its layers above and below the route lines (SP41-SP43, SP46), Home on top, the hit test
reporting `"saved:<id>"` (SP44), re-added on every style load; `:map:demo` with made-up saved
markers. Until P announces the sprites with the three saved icons (P's first wave), `:map:demo`
adds a stand-in image under each of the three names (`style.addImage`, demo code only), and the
screenshots of the markers are taken after the announcement. Done when also: the mapper and rules
tests of its 10.1 pass; the screenshots of its 10.4 looked at.

**A: about, identity and the follower.** Delivers: launcher icon (adaptive: background and
foreground at every density, foreground as bitmaps because of its blur, monochrome vector for
themed icons), `Theme.Beamline.Starting`, `Splash.install`, `SplashReveal` (the icon stays put, a
circle opens over `splash-scene.svg`, about 0.7 s, then the map; held on the full scene until
`mapReady`, at most 2 s), `FirstRunNotice` (text below, approved by the owner as written on 2026-11-16), `AboutScreen`,
`LicencesScreen`, `Notices.parse`, and `notices.py` producing the module's Android assets
`android/feature/about/src/main/assets/notices/NOTICES.txt` and `notices.json` (read at run time as
`assets/notices/...`, 2.8; not the frozen `assets/` folder at the repository root) from a curated
list with an SPDX licence per entry. Data credits: OpenStreetMap
ODbL, Natural Earth, US Census, TIGER/Line, USDOT NAD, City of Scranton (PDDL), Noto Sans OFL,
tangrams icons MIT, Protomaps basemaps style BSD-3/CC0 (until 2026-11-17 also
`desugar_jdk_libs` with the Classpath Exception text, and JNA, offered under LGPL-2.1-or-later
**or** Apache-2.0 and taken under Apache-2.0, 6.38 question 6; both went with Ferrostar).
**Licence gate:** `notices.py --check` fails when an artifact that ships
(the release runtime classpath and the `coreLibraryDesugaring` configuration) or a native library
of the release build (MapLibre's vendored libraries, Valhalla and its linked libraries,
SQLite) has no entry, and when any entry's licence is GPL, AGPL or LGPL in any version or "or
later" form. The allow-list (`ALLOWED` in `notices.py`) is empty since 2026-11-17: its one entry,
`desugar_jdk_libs` (GPL-2.0 WITH Classpath-exception-2.0), the one exception the owner had
accepted, served Ferrostar alone.
An entry whose upstream licence is an SPDX `OR` expression records the option Beamline takes and
is checked as that option.
`FerrostarFollowerFactory`: Ferrostar 0.59's `NavigationController`
(`waypointAdvance = WaypointWithinRange(arrival.within_m)`, step and arrival advance =
`DistanceEntryAndSnappedExit(advance.entry_m, advance.exit_m, fixes.decide_max_accuracy_m)`,
`routeDeviationTracking = None`, `snappedLocationCourseFiltering = SnapToRoute`; A confirms the
0.59 Kotlin binding names and, if a condition has no public constructor there, uses
`ManualStepCondition` and lets the session call `advance()`); `lateralM` computed from the snapped
point; its consumer R8 rules (JNA, uniffi).
Done when: `notices.py --check` passes against what will ship. `:app` is frozen until I wires it
and today depends only on `:core:packs`, `:core:settings`, `:core:location`, `:ui:theme` and
MapLibre, so Ferrostar, JNA, valhalla-mobile, sqlite-bundled and `desugar_jdk_libs` are not on its
classpath yet; A therefore checks the union, over every module of 1.1 that `:app` will depend on,
of `./gradlew <module>:dependencies --configuration releaseRuntimeClasspath` and (where it
exists) `--configuration coreLibraryDesugaring` (resolution only, in A's own build folder), and
the native libraries in the `jni/` folders of the AARs on them (that union includes
`:feature:states` and `:core:download`, so OkHttp 5.3.2 and Okio 3.16.4, both Apache-2.0 and
already resolved through Ferrostar, keep their entries). The same check against `:app`'s
release classpath and release APK (`lib/` folders) is I's final gate (5.4 items 1 and 14). The
check fails on a removed entry and on an entry marked GPL; JVM test of `Notices.parse`;
emulator: launcher screenshot (circle and squircle masks, themed icon on) from `:demo:about`, a
screen recording of the splash looked at frame by frame, About and licences screenshots, first-run
notice shown once and not again after accepting; `:nav:ferrostar`'s device test: the exit 24 route
(`android/routing/testdata/reference/exit24-osrm.json`) driven at 1 fix/s advances at the three
maneuvers within ±15 m of where the test follower of N advances (N's golden trace once it exists;
until then the maneuver points of the recording plus `exit_m`), never goes back, and runs from a
release-minified test APK.

**G: getting packs** (`docs/architecture-downloads.md`, which has every text, rule and test in
full). Delivers: `:core:download` complete (`CatalogFormat.parse` and the catalog rules of its
2.3, `CatalogAddress`, `PackHttp` under the network rule N1-N5 (section 4), `CatalogSource`,
`DownloadEngine` with resume, hashing, unpacking, space and its state machine (its 6.4),
`PackHandoff` with the archive stand-in until P's folder install lands, `DownloadRules.parse` and
`bundled`); `:feature:states` (`GetPacks`, `StatesScreen` for the first run and the States screen
with every text of its section 1, `StatesPresenter`, the drawn US map and `StateHitTest`,
`DownloadService` with its notifications, `AndroidNetworkWatcher`, `TaggingSocketFactory`, the
file route "Use a file instead", the library manifest of its 5.3, the asset
`states/us_states.json`); `:demo:states` with a fake `InstalledData`; `packbuilder/catalog/`
(`build_catalog.py`, `serve_packs.py`, `catalog_client.py`, `build_state_shapes.py`, their tests);
`docs/catalog-format.md`; `android/scripts/check_network_rule.py` (`manifest`, `scan`, `privacy`).
G codes against the `InstalledData` interface only; its tests and demo use fakes.
Done when: the JVM tests of its 10.1 (`:core:download` against a local Range server: catalog
parsing, cache, address and network rules, resume, ignored range, changed file, 416, hash failure,
unreachable server, bad response, clock, list mismatch, gzip part, space, process death, already
installed, network states, hold and busy, hand-off, house numbers, switch on 404, unknown format,
the space formulas) and 10.2 (`:feature:states`: the shapes, the tap rules, every text of the
presenter) pass; the device checks of its 10.3 on `beamline_g` with `:demo:states` and the
development server, observed and with screenshots by day and night looked at;
`check_network_rule.py scan` and `privacy` pass on the tree; `build_state_shapes.py --check`
passes; `python -m unittest discover packbuilder/catalog/tests` passes; `docs/catalog-format.md`
written; report 18 names every artifact G adds to the runtime classpath with its SPDX licence (so
A's `notices.py --check` passes when I puts `:feature:states` on `:app`'s classpath).

First-run notice (A; approved by the owner as written on 2026-11-16, no longer a draft): "Beamline gives directions from map data on this phone. The data can
be out of date or wrong. You are responsible for driving safely and following road rules, signs
and signals. Set your route before you drive, and do not handle the phone while driving."
[I understand]

### 5.2 Shared files and how collisions are avoided

- **Frozen during the round:** `android/settings.gradle.kts`, `android/gradle/libs.versions.toml`,
  `android/build.gradle.kts`, `android/gradle.properties`, `:core:api`, `:core:units`,
  `:core:settings`, `:core:location`, `:ui:theme`, `packbuilder/routing/units.json`, `assets/`,
  `docs/design/`, `docs/architecture.md` and its companion contracts (`docs/architecture-*.md`),
  `tools/check_contracts.py`, and in every track's modules the contract files of section 2 and of
  the companions except their `TODO` bodies. Round 0 and Round G0 added every include and catalog
  entry the round needs: the new modules (Round G0: `:core:download`, `:feature:states`,
  `:demo:states`), `ferrostar-core` and `desugar-jdk-libs` (for `:nav:ferrostar` and `:app`; both
  removed on 2026-11-17 with Ferrostar), `androidx-core-splashscreen`,
  `androidx-lifecycle-service`, the Compose `ui-tooling`, `ui-tooling-preview`, `ui-test-junit4`
  and `ui-test-manifest` (versions from the Compose BOM), `androidx-test-rules`,
  `kotlinx-coroutines-test`, `okhttp` (Round G0: `:core:download`, and `:app` for `OfflineMapHttp`
  when I wires it), `opening-hours-parser` (batch B, 2026-11-18: `ch.poole:OpeningHoursParser`
  0.29.0, MIT, for `:core:search`, rule S10), and the module dependencies of 1.1. The new modules' build files already
  declare JUnit 5 for JVM tests and the androidx test runner for device tests where a track will
  need them; a track may edit its own modules' build files, using catalog entries only.
- **How the freeze is checked:** `python tools/check_contracts.py` compares every block of this
  document and of each companion contract `docs/architecture-*.md` with the tree (a whole file may
  be quoted in one document only), and every file of the frozen code (the four Gradle files above,
  the five frozen modules as whole folders without build outputs, `units.json`, and the script
  itself) with the SHA-256 list `tools/frozen-files.sha256`. A file added to a frozen module,
  changed or removed fails the check, quoted in a document or not. `assets/`, `docs/design/`, this
  document and its companions are frozen by rule only.
- **Changes to a frozen file** go through the orchestrator: a track writes
  `data/architecture/requests/<key>.md`; the orchestrator applies approved changes between waves
  (additive where possible), updates this document or the companion that holds the block, records
  the frozen list again (`python tools/check_contracts.py --freeze`, after every approved change to
  a frozen file: Round G0, and publishing day's `android/gradle.properties` line of
  `docs/architecture-downloads.md` 8.3), runs `python tools/check_contracts.py` and a full build,
  and tells every track. `--write` (the documents' blocks into the tree) never overwrites a
  contract file whose stubs a track has filled; such a change is merged by hand.
- **No track compiles another track's changing module**, except the edges of 1.1, whose owners keep
  them compiling and source-compatible.
- **Rule data files** are copied, never edited in place: `sync_data.py --only <name>` copies one
  file (M: `camera.json`; N: its own; G: `download_rules.json`, whose source is
  `packbuilder/catalog/download_rules.json`); S's `copy_search_rules.py` copies `search_app.json`
  (into `:core:search` since Round SP0).
- **Logging (every track).** Rule PV2 of `docs/architecture-saved-places.md` (section 4 here in
  short) binds the main sources of every shipped module: a log call is a tag and one plain string
  literal, or is marked on its line `// log-ok: <what the values are>` and prints only numbers,
  durations, counts, sizes, enum names, pack ids, file names and catalog-relative paths; no
  exception is passed to a log call and no exception message is logged; a line that names a
  position, a place, stops or a query needs `// private-ok:` and the location-log guard;
  `:core:search`, `:feature:search` and `:map:scene` log nothing. S's `check_private_logs.py scan`
  checks it: every track runs it on its own modules once S has written it, and I over the whole app
  (5.4 item 14).
- **Reports and READMEs:** each track writes its own report and its module `README.md`; only I
  edits `README.md` and `android/README.md`.
- **Data:** each track writes only under `data/r3-<key>/` and `data/gradle/<key>/` (and M under
  `data/art/markers/` and `data/art/build/markers/`, which `build_markers.py` writes; P under the
  rest of `data/art/` and the git-ignored `assets/sheet/`, which its sprite, style and contact-sheet
  steps write (5.1 P); G under `data/catalog/`, the development server's site); existing builder
  outputs under `data/` are otherwise read-only (P's new packs go to `data/r3-packs/`). Every track
  may create and remove its own lock folders under `data/locks/` (5.3).
- **Emulators:** AVD `beamline_<key>`, fixed port (P 5560, N 5562, S 5564, D 5566, M 5568, A 5570,
  G 5574, I 5572), `ANDROID_SERIAL` always set to the track's own emulator (`emulator-<port>`) in
  every shell that runs adb, Gradle or a script that calls adb; at most three running at once, each
  holding one of the three emulator slots of 5.3 (taken atomically before the start, given back
  after the stop); stopped when done; system night mode and location switches set back. The scripts
  of `tools/round/` (5.3) make the AVD, start and stop it, and are the only way a track runs adb. The phone is used by I only, and only as rule PV7 of
  `docs/architecture-saved-places.md` allows: every session (the phone kit's included) started cold
  with `--es private_data test`, so only made-up places are on screen, in a capture or in a log;
  never `pm clear`, `adb uninstall`, `adb install` without `-r`, `adb backup`, `bmgr`, `run-as` or
  `adb pull` of the app's files; on an install error the engineer stops and asks the owner; logs
  only of the app's process, kept under `data/phone/private/` and never printed. The phone's data is
  never cleared and the app never uninstalled, whatever the owner has saved (one exception, section
  8: one clearing allowed once by the owner on 2026-11-16, before any saved place existed; never again): a journey that needs a
  fresh install or cleared data (the first-run journeys of the downloads) runs on the emulator only.

### 5.3 Gradle isolation (in `android/build.gradle.kts`)

Seven engineers building in one checkout would share `android/.gradle/` locks and every module's
`build/` folder, and could read each other's half-written outputs. Every track runs Gradle from
`android/` as

```
./gradlew --project-cache-dir ../data/gradle/<key>/cache -Pbeamline.buildRoot=../data/gradle/<key>/build -Pkotlin.project.persistent.dir=../data/gradle/<key>/kotlin --configure-on-demand -Dorg.gradle.daemon.idletimeout=600000 <tasks of its own modules, each named with its project path>
```

The hook in `android/build.gradle.kts` moves every project's build directory to
`<buildRoot>/<project path with / for :>` (the root project to `<buildRoot>/_root`) when
`beamline.buildRoot` is set; without it the build is as before. Round 0 used it for every build of
appendix B (key `0`), Round G0, Round SP0 and the review after them key `0b`. The two other flags:

- **`--configure-on-demand`, so another track's build script cannot stop yours.** Gradle evaluates
  the build script of every project for any task, so a build file that one track is half-way
  through editing, or has broken, fails the builds of all seven. With the flag and tasks named by
  project path (`:feature:search:test`, never a bare `test`), Gradle evaluates only the root, the
  named projects and what they depend on (measured in the review of Rounds G0 and SP0:
  `:core:units:compileKotlin` evaluates 36 projects without the flag, 3 with it; a bare task name
  still configures every project). It changes nothing else (it is a command-line flag; Gradle
  calls it incubating). A track still writes a build file in one go (the whole file at once), not
  in several partial saves.
- **`-Dorg.gradle.daemon.idletimeout=600000`, so idle daemons go away.** Gradle's default idle
  time is 3 hours, and `./gradlew --stop` stops every daemon of the same Gradle version and JVM,
  which all tracks share: never use it. With the flag, a daemon this command starts exits after
  10 idle minutes (tried in the review: a daemon started with the value 60000 recorded it and
  stopped itself after one idle minute). A build reuses any idle daemon started from this folder,
  whichever track started it; a daemon started without the flag keeps the 3 hours.

**Memory, slots and the round's scripts.** Caps stay: 2 GB heap, 4 workers, Kotlin in-process.
Measured in the review of Rounds G0 and SP0 (Windows private bytes, this PC: 31.8 GB, 12 hardware
threads, about 8 GB in use with no build running): a Gradle daemon holds 3.3 to 3.9 GB (the 2 GB
heap plus metaspace and native memory), and stays at about 3.3 GB while idle; each JVM test worker
0.6 to 0.85 GB, up to four at once; the clean build of every module peaked at 6.5 GB of Java
processes. An emulator started as below takes about 3.5 GB (*estimate*, `-memory 3072`). Seven
tracks cannot all build and run emulators at once on that, so every Gradle run, every emulator and
every memory-heavy data build goes through the scripts in `tools/round/` (written and tried by the
orchestrator before the tracks started; they replace the hand-written lock snippets this section
had). No track runs `./gradlew`, `adb` or `emulator` directly.

| Script | Use | What it guarantees |
|---|---|---|
| `. tools/round/env.sh <key>` | source it in a shell that runs a script calling adb (`device_ui.py`, `geo_route.py`, a track's own) | `ANDROID_SERIAL=emulator-<port of the key>`, the two search databases, the SDK's `emulator` and `cmdline-tools` on PATH |
| `sh tools/round/gradle.sh <key> <tasks by project path>` | every Gradle run, resolution-only ones included | one of **three build slots**, taken only when 7 GB of memory is free; the command of this section with the track's own build root, project cache and Kotlin folder; `ANDROID_SERIAL` set |
| `sh tools/round/emulator.sh <key> start\|stop\|status\|keep` | the track's emulator | makes AVD `beamline_<key>` once (10 GB data partition), takes one of **three emulator slots** only when 5 GB is free, starts it detached on the track's port, waits for the boot; `stop` flushes, powers off and gives the slot back |
| `sh tools/round/adb.sh <key> <adb arguments>` | every adb command | always `adb -s emulator-<port>`; refuses another serial and the commands that act on the adb server (`kill-server`, `start-server`, `connect`, ...), because the owner's phone is attached to the same PC; device paths are not rewritten by Git Bash (give local files as relative or `C:/...` paths) |
| `BL_NEED_GB=<n> sh tools/round/heavy.sh <key> <command>` | a data build expected to use more than 3 GB (Planetiler, a routing graph build) | one at a time, and only when that much memory is free |

A script that finds no slot or too little memory waits (`BL_SLOT_WAIT` seconds; by default 360 for
Gradle, 420 for an emulator, 480 for a heavy command) and then exits with code 75: the track does
other work and runs it again. Slots are folders under `data/locks/`, taken under a mutex folder, so
two tracks never hold the same one. A slot left behind is taken over by the next track that needs
one: a build or heavy slot when the shell that took it no longer exists, an emulator slot when its
emulator is not running, or when nothing has used it for two hours (that emulator is then powered
off; every `adb.sh` call and `emulator.sh keep` count as use). A track stops its emulator whenever
it is not using it: six tracks share three slots. Memory already promised to slots taken in the
last two minutes is counted as used. `gradle.sh` refuses `--stop`.

No AVD named `beamline_<key>` existed when the round started except `beamline_i`, which the
orchestrator made when trying the scripts (the earlier rounds' `beamline_api36`, `beamline_routing`
and `beamline_search` are not used).

The shared Gradle build cache in the user's home is safe for concurrent builds and stays on.
`connectedAndroidTest` stays forbidden where it would uninstall pushed data (use `am instrument`);
every device task runs with `ANDROID_SERIAL` set.

**A `test` of every module needs the two search databases.** `:core:search`'s tests read
`BEAMLINE_SEARCH_DB` and `BEAMLINE_ADDRESSES_DB` (or `beamline.searchDb` and
`beamline.addressesDb` in `android/local.properties`), set to `data/search/pa-search.sqlite` and
`data/addresses/pa-addresses-slim.sqlite` given as absolute paths. Without them most of those
tests skip, but `RulesAndVersionsTest.missingOrForeignVersionsAreRefused` fails until S fixes it
(5.1), so `./gradlew test` at the root is red for a reason that is not the caller's. A track that
runs only its own modules' tasks, as above, is not affected.

### 5.4 Integration checklist (I)

1. `:app` depends on every module of 1.1 except the demos; no core library desugaring (it was on
   for Ferrostar alone, removed 2026-11-17; minSdk 26 has `java.time` and `java.nio.file`); namespace `P.app`, application id from `beamline.applicationId`.
2. `AppGraph`: the three dispatchers (1.2), `SharedPositionSource` over `AndroidPositionSource`,
   `InstalledLibrary` (packs in `getExternalFilesDir/packs`, routing in `getExternalFilesDir/routing`),
   `RecentPlaces`, `SearchFeature`, `BeamlineNavigationSession` (with `EngineOpener {
   ValhallaEngine.open(it) }`, `GeometricFollowerFactory()` (until 2026-11-17
   `FerrostarFollowerFactory()`), `System::currentTimeMillis` as the
   wall clock, `installed::holdChanges`), `CameraDirector`, `MapSceneMapper`, `EventCards`
   (`stopCardS` from `GuidanceRules`), a `StateFlow<UnitFormatter>` from `Settings.units` and
   `DateFormat.is24HourFormat`, `AppStateStore`. `PuckTracker` now uses the shared source.
   Saved places (`docs/architecture-saved-places.md` 6.1, PV4, PV7): `SavedPlaces(File(dir,
   "saved-places.json"), SavedRules.bundled(), Dispatchers.IO.asExecutor())`, `RecentPlaces(File(dir,
   "recents.json"))` and `TripStore(File(dir, "trip.json"))` with `dir` = `noBackupFilesDir`, or its
   `test/` folder in a process started with the test extra `private_data`; `AppGraph` takes `dir` as
   a constructor argument, which `BeamlineApplication` holds and `MainActivity.onCreate` sets from the
   extra before its first access to `graph` (a later setting is refused, and that refusal logs
   "private data: extra ignored, app already running"); `SavedPlacesFeature(saved, recents,
   installed, units, scope)`; the `saved` argument of `SearchFeature`; `MapSceneMapper()`.
   `GetPacks(context, installed, settings, units, GetPacksConfig(BuildConfig.CATALOG_URL,
   BuildConfig.DEBUG, PackManifest.SUPPORTED_VERSION, PackManifest.SUPPORTED_VERSION,
   File(getExternalFilesDir(null) ?: filesDir, "downloads"), File(filesDir, "getpacks"), freeBytes,
   routingSetBytes, openStates, openMap), scope)`, with the same `freeBytes` given to
   `InstalledLibrary` (`File.usableSpace`, `docs/architecture-downloads.md` 2.6) and
   `routingSetBytes` = the total size of the files in the routing folder `InstalledLibrary` gets.
   `BeamlineApplication` implements `GetPacksHost`; in `onCreate`, right after `MapLibre.getInstance`
   (right after it, not before: MapLibre 13.7.1's `HttpRequestImpl` reads the MapLibre instance in its static initialiser and throws `MapLibreConfigurationException` without it, seen at integration on 2026-11-16; no map view and no request exists yet then),
   it calls `HttpRequestImpl.setOkHttpClient(OfflineMapHttp.client)` (rule N5; `OfflineMapHttp.kt`
   is I's, with `OfflineMapHttpTest` and `ComposedStyleOfflineTest`, `docs/architecture-downloads.md`
   4.2 item 3; `:app` gains `implementation(libs.okhttp)`), keeps `MapLibre.setConnected(false)`, and
   in debug builds sets `StrictMode.VmPolicy.Builder().detectUntaggedSockets().penaltyLog()`.
3. Start-up order: `Splash.install` → `SplashReveal` over the map until `mapReady` → `FirstRunNotice`
   when `firstRunNoticeAccepted < FIRST_RUN_NOTICE_VERSION` → G's `StatesScreen(FIRST_RUN)` ("Choose
   your state") while `getPacks.showFirstRunPicker` → the map, with the search pill at the top (S8).
   The map opens on the driver (K1): with location allowed and on, a cold start calls
   `director.onMyLocation()` (BROWSE_FOLLOW from the last known fix; location is never asked for at
   start); without a position it shows where the map was left (`LastCameraStore`), and fits the
   installed set only on the very first open.
   Nothing touches the network before the notice is accepted. `MainActivity.onStart` calls
   `getPacks.onAppVisible()`.
4. Map screen (a rewrite of today's `MapScreen`, `MapViewModel` and `PackMap` on the new APIs):
   `PackMap` composes the style from `InstalledSet` (P's overload), attaches `MapLibreController`
   on every style load, removes its old puck layers; feeds `CameraDirector` (viewport, insets from
   `DriveOverlay.onInsets`, puck, trip, gestures), calls `tick()` every second and applies its
   targets; every target goes through `target.withZoomCap(composed::zoomFor)` (`:map:scene`
   `ZoomCap.kt`, rule K1's z9.99 cap outside the states) before `MapLibreController.moveCamera`, and
   `PackMap` keeps `map.setMaxZoomPreference(composed.maxZoom ?: MapLibreConstants.MAXIMUM_ZOOM)`;
   after every style load `MapLibreController.onStyleLoaded(style, theme)` and then the director's
   current target again (`setStyle` cancels a running camera ease); shows `MapSceneMapper.scene(...)` on every trip, puck, theme or picked-place change;
   shows the credit as OpenStreetMap's attribution guidelines allow (docs/next-round.md item 8): one
   short line "© OpenStreetMap" (only while OpenStreetMap data is drawn; the overview's sources are
   public domain), small, in the bottom start corner above the trip bar or the sheet; it fades after
   five seconds or at the first touch of the map and is shown again when the map appears, when the
   app comes back and at each change between browsing, a route preview, guiding and ARRIVED; during
   the whole route preview it stays; while guiding it shows only those first seconds, then nothing.
   While browsing, after it fades, a small info mark stays in the corner. A tap on the line or the
   mark opens "Map data", the full list of sources: the OpenStreetMap licence line with
   openstreetmap.org/copyright, the overview's sources and each installed state's own credit
   (`PackInfo.attribution`); the same list is About's "Map data" (`AppRules`, `mapDataCredits`). The top of the
   map as rule S8 says (`docs/architecture-saved-places.md` SP1-SP3): the search pill with the
   Settings button beside it while no trip exists (a States button stood under it until 0.1.14); today's
   pack-problem notice and `getPacks.mapChip` (while it is not null; a tap opens the States screen)
   under the pill; the banner there while guiding. The chip shows only while the pill shows (SP1):
   in PLANNING, CHOOSING, GUIDING, REROUTING and ARRIVED, and while a search screen, the saved
   places screen or a map pick is open, it is not shown, so it never covers the route preview or the
   banner; the download's notification and the States screen keep showing the job, and the chip
   comes back with the pill (`docs/architecture-downloads.md` 1.5, `docs/architecture-saved-places.md`
   SP2). The saved places go to the scene
   (`MapSceneMapper.scene(..., saved = if (showOnMap) places else emptyList())`, a new scene when
   they change). During a map pick (SP16) the pill, the place sheet and the drive overlay's sheets are
   hidden (not closed), the my-location button and the credit (line or mark) stay above the pick bar, and the
   chosen point is the PICKED pin.
   Whenever the map is shown and `getPacks.showOnMap` is not null (a cold start from the launcher,
   the `OPEN_MAP` intent, the first-run screen closing, "Show on map"): close the States screen,
   `moveCamera(CameraTarget(fit = bounds, padding = 32 dp))`, snackbar "<name> is ready.", then
   `getPacks.showOnMapDone()`.
5. Gestures: Tap on a POI → `Place(MAP_POI)` → `PlaceSheet`; LongPress → `Place("Dropped pin",
   subtitle = coordinates, MAP_PIN)` → `PlaceSheet`; Tap elsewhere closes the sheet. A tap on a saved
   marker (`pinId` `"saved:<id>"`) → the sheet of that saved place (SP44). While a map pick runs,
   every Tap and LongPress goes to `SavedPlacesFeature.onMapTap(point, poi, cameraZoom)` instead, with
   no sheet and no dropped pin (SP16, SP24).
6. Flows: search bar → `SearchScreen` (GO_TO; ADD_STOP from `DriveEvent.SearchForStop`; SET_START
   from `DriveEvent.ChooseStart`) → `Picked` → `PlaceSheet` (GO_TO), `NavIntent.AddStop`
   (ADD_STOP) or `NavIntent.SetOrigin(FromPlace)` (SET_START); `OpenStates` → States screen
   (`StatesScreen(MANAGE)`, from Settings, the map chip (also "No map for this area. Add a state." where the
   map rests outside every installed state at zoom 9 or closer, 0.1.15) and the download
   notifications); Directions → `PlanTo`; `DriveEvent.Nav` → `session.send`; Recenter/Overview → `CameraDirector`;
   my-location → permission → `CameraDirector.onMyLocation`; session events → `EventCards.onEvent`
   and `present(..., shownEvent = cards.shown(now), locationAllowed = precise location granted)`.
   Saved places (`docs/architecture-saved-places.md` 5.5): search is opened with `driving` (phase
   GUIDING, REROUTING or ARRIVED), in SET_START with `destination` = the last stop, and with
   `SearchContext.accuracyM` from the fix; `PlanTrip`, `ShowPlace`, `OpenSavedPlaces`, `SaveNew`,
   `PickOnMap`, `SearchToSave`, `PickEnded` and `Saved` as its table 5.5 says, a pick hiding and then
   showing again what it came from (SP25); "Directions" and `PlanTrip` by phase as S7 says (SP18),
   with its two dialogs.
7. States screen: I places G's `StatesScreen(getPacks, MANAGE, onClose)` instead of writing one
   (it replaces today's `PackManagerScreen` and `PickedSources`; downloads, updates, house numbers,
   removal and "Use a file instead" with the "House numbers" box, the step texts, the hold message,
   inspect before copying, and keep-screen-on with "Keep Beamline open" during a file install are
   all G's). The notification intents `openStates` / `openMap` are explicit intents to
   `MainActivity` with the actions `io.github.spysyweeb.beamline.action.OPEN_STATES` /
   `…OPEN_MAP`, handled in every build (not a test extra).
8. Settings screen as today plus an About entry (since 0.1.15 without "Frame rate" and "Guidance sound":
   the map always asks for the screen's fastest rate and guidance is silent); Guidance sound text was "Silent (Chime and Voice
   come later)"; a new group "Places" above About with the row "Saved places" (the saved places
   screen, `openList(pickFor = null)`), which reads "Not while guiding" and opens nothing in GUIDING,
   REROUTING and ARRIVED (SP4).
9. `GuidanceService` and the notification (section 4); keep-screen-on while guiding on screen;
   `POST_NOTIFICATIONS` asked at the first Start only if G has not asked it already (G asks right
   after the first Download has started); precise location asked again when only approximate was
   granted.
10. Manifest: exactly the permissions of section 4, the two services, `ACCESS_WIFI_STATE` removed
    from every library, `INTERNET` and `ACCESS_NETWORK_STATE` kept (they are `:feature:states`'s),
    the debug-only network security config of Round G0 (`docs/architecture-downloads.md` 8.2),
    `android:allowBackup="false"` and `android:dataExtractionRules` with
    `res/xml/data_extraction_rules.xml` (`docs/architecture-saved-places.md` PV4); keep the activity's `android:configChanges` (orientation, screen size, uiMode and the rest of
    today's list) so the map survives rotation and day/night switches; `aapt2 dump badging` and
    the merged manifests (debug and release) checked.
11. Remove `:app`'s old launcher icon files (A's win); activity theme `Theme.Beamline.Starting`.
12. Test extras (debug, or release with `-Pbeamline.testExtras=true`): `--es dest "lat,lon,name"`,
    `--es origin "lat,lon,heading"`, `--es simulate default|wrongturn|gaps|jitter|drift|tunnel`
    (`Simulation(scriptName = ...)`), `--es addstop "lat,lon,name" --ef addstop_at_m 1000` (sends
    `AddStop` when the simulated car passes that distance), `--es units metric`,
    `--es style day|night|phone|auto` (`auto`: Automatic, `phone`: Same as phone), `--es sun_at
    <ISO time with offset>` (batch A: the clock the sun rule sees, moved there and running on;
    `off` puts it back), `--ef sim_speed <factor>` (batch A: `Simulation(speedFactor = ...)` for a
    simulated drive started with `--es simulate`), `--es hours_at <local date-time>` (batch B: the
    clock the opening hours are read at, S10, moved there and running on; `off` puts it back), `--ef fix_accuracy_m <m>` (batch C2: every fix reports that accuracy, so the emulator can play a poor fix for G7's bubble; 0 puts it back); for saved places (`docs/architecture-saved-places.md` PV7, 10.3)
    `--es private_data test` and `--ez location_log true` (read once, on a cold start only), and
    `--ef lat <deg> --ef lon <deg> --ef zoom <z>` (one camera move, also to a running app); for the
    phone step `--ez reset_notice true` (read on a cold start only: the first-run notice is set back
    to "not accepted", so the owner meets it in person after the tests accepted it; the lead, 2026-11-16).
13. R8: release build runs (Valhalla Moshi rules; until 2026-11-17 also `:nav:ferrostar`'s consumer
    rules); the simulated exit 24 drive works in the release APK.
14. All JVM tests of all modules (with `ComposedStyleOfflineTest` and `OfflineMapHttpTest` in
    `:app`); `sync_assets.py check`, `sync_data.py --check`, `copy_search_rules.py --check`,
    `sync_art.py --check`, `notices.py --check` (with `:feature:states` on the classpath),
    `build_state_shapes.py --check`, `check_network_rule.py manifest` (debug and release),
    `check_network_rule.py scan` and `privacy`, `check_private_logs.py scan` and `manifest` (debug and
    release, `docs/architecture-saved-places.md` PV3, PV4), `tools/check_contracts.py`. Before the
    scan, I rewrites `:app`'s log lines as PV2 item 3 of `docs/architecture-saved-places.md` says
    (the lines it names: `PackMap.kt` 158, 244 and 329, `AndroidPositionSource.kt` 66,
    `MapViewModel.kt` 85 and 105): an exception logged by its class name only (marked
    `// log-ok:`), never its message or the exception itself; the camera-idle line with the zoom
    only; the puck line (`BeamlineLocation`, the phone kit's location measurement) marked
    `// private-ok:` and logged only in a process started with `--ez location_log true` (PV7 item
    6, item 12 here); every other line a plain literal or marked.
15. The E2E test (section 8) on the emulator (with `check_private_logs.py logcat` over its logs),
    then reviewers, then the phone (as PV7 allows); report APK size per
    ABI, cold start, memory after the drive (Valhalla native heap; try `thor.clear_reserved_memory`
    only if memory is a problem), frame rate while following: all indicative.

## 6. Engineering decisions settled here

| # | Question (source) | Decision | Reason |
|---|---|---|---|
| 6.1 | Which overview map ships (08 #1, 07) | The public-domain 3.66 MB build (Natural Earth + Census) | 14 MB smaller APK; no OSM credit needed for it; the forest it lacks at z6-7 is not driving information |
| 6.2 | z0-14 packs with merged buildings (07) | Yes: z14, profile patch 2, street furniture dropped, MVT; PA 244.7 MB, OH 252.3 MB | -47 % with every street name, house number and driving POI kept; MLT waits for one Android run; "lean" options wait for the owner's look |
| 6.3 | Routing manifest inside the state pack (09 #4) | Yes, as part `routing_index` (`routing.json`); the per-state tar is merged into one tile set and not kept | The update rules need the tile list and hashes; not keeping the tar saves about 0.63 GB per state on the phone |
| 6.4 | When checksums are verified (08, 09 #5) | Once, while copying from the archive (all parts, one pass); per-tile hashes while merging; no whole-tar hash at merge; cheap checks on every scan | Every byte is hashed once on its way in; the tar hash at merge repeated a check made seconds before and cost 1.5-2.3× the install |
| 6.5 | Avoid highways / tolls with other types | Toggles that apply to every type (composition 2.11); Avoid tolls as the hard `exclude_tolls`; when an exclusion finds no route (a stop or the destination on that kind of road) the soft form is asked instead, for every type, and the route carries a warning; Shortest skips only the `fallback_when` re-run; Eco hidden by `offered: false` | Matches "route types plus the options"; rules stay in presets.json; the soft toll factor does nothing with Shortest (review F2); the owner accepted that a trip starting on a toll road may stay on it; a place on a toll road can only be reached on it, so "No route" would be the wrong answer (Round 0 review R1; confirmed by the owner on 2026-11-16, 6.38 question 5) |
| 6.6 | Alternates | Not shown this round: one route per type, duplicates merged | Three types already give choices; alternates double engine calls and do not exist for several stops |
| 6.7 | Stop ordering (09 #9) | By driving time for every type; the user's order unless "Best order" is tapped | Same as the Python reference; no `order_by` field needed yet |
| 6.8 | Arriving as a following turn (09 #2) | No | The design's rule is about turns; the owner's trip relies on it |
| 6.9 | Lane narrowing distance (09 #3) | Up to 0.5 mi (`banner.json`), Valhalla's own below 0.25 mi | Consistent with the following-turn window |
| 6.10 | Input length cap (10 #6) | `ranking.json` `max_query_chars` 256, applied by `query.py` and the Kotlin engine | One rule in data, both engines equal on every input |
| 6.11 | Normaliser identity (10 #2, #8) | Key fingerprint over a fixed spelling list (2.11); LF-normalised hashes; a mismatch disables that pack's search only | A version number missed a real code change; a fingerprint catches any key change |
| 6.12 | Search across neighbouring packs (10 gap 2) | S3-S4; packs built together with the same `ranking.json` | Erie is 10 km from Ohio; scores are comparable only with equal ranking rules |
| 6.13 | The app's copy of the style (08 §2) | Made only by `sync_assets.py` from the committed bundle, by P; `check` in sync before integration; route line and markers are app layers, not style | The APK must match a commit; one writer |
| 6.14 | Role of Ferrostar | **Dropped (the owner, 2026-11-16; done 2026-11-17).** Beamline's own `GeometricFollower` in `:nav:session` does the snapping and step advance on the phone too. Was: follower only (`NavigationController`, not `FerrostarCore`); planning, re-route policy and all deciding rules were ours anyway | The follower already decided the step advance itself (report 17: Ferrostar's own condition never advanced on the exit 24 drive), and the two followers agreed within 1 m on every scripted drive; dropping it removes about 99 licence entries, JNA, core library desugaring and the one allowed GPL exception, and about 3 MB of native code per ABI |
| 6.15 | Two engine calls per guided route (09 #8) | Accepted (OSRM form asked only at Start and re-route, not for every choice). Since 2026-11-17 no follower reads the OSRM answer (`FollowerRoute.osrmJson`); the call is kept for now and can go with a change of the 2.4 port | 20-130 ms on the emulator; no serializer to maintain |
| 6.16 | Background guidance | Foreground service of type location (section 4); no background-location permission | Guidance must survive screen-off; "while in use" suffices |
| 6.17 | First-query latency (10 #4) | `SearchHub` reads the hot tables once at open on the search thread | Cheap; measured on the phone at integration |
| 6.18 | Addresses without a town (10 #3), brand ranking (10 #1) | Unchanged | The owner tunes them on the road |
| 6.19 | Address typo speed (10 #5) | Indexes on `localities(city_key)`, `localities(muni_key)` in the address builder | Data-only change, likely 0.3-0.7 s off the tail (*estimate*) |
| 6.20 | Search result icons | Cut from the style's sprite sheet by `poi_icons.json` | One icon set for map and list, no second export |
| 6.21 | Marker exports (06 #5) | PNGs in candidate B committed in `:map:layers`, puck and cone in blue B, arrow in the sketch's blue family; stale puck without cone (SPEC) | Approved art; no build-time tool chain in the app build |
| 6.22 | Stop and destination pins | Plain pins in blue B with white numerals, drawn by M | No approved art; the owner judges them on the phone |
| 6.23 | Pack changes during a trip | Refused while guiding (hold); Start refused while an install runs | The routing set cannot change under a trip |
| 6.24 | Process death during guidance | `trip.json` refreshed every 30 s while guiding; RESUME_OFFERED for 30 min after the last write; Resume re-plans from the car | The service makes death rare; asking is safer than silently resuming; a long trip must not lose its offer (review J2) |
| 6.25 | Formatting | One `UnitFormatter` from `units.json`; banner texts made at display time | Exists once; metric works everywhere |
| 6.26 | Banner road names | `banner.json` `road_abbreviations` on the last word ("Peach St") | The sketch shows the short forms |
| 6.27 | valhalla-mobile's kotlin-reflect (09 #6), native heap (09 #7) | Accepted; memory measured on the phone, `device_config.json` changed only on a measurement | Not worth a fork now |
| 6.28 | Pack transport (08 #4) | One uncompressed `.tar` per state for files; per-part files (gzip where it pays) for downloads (6.39, 6.40) | Simplest for users; hashing in one pass; downloads resume per part and skip house numbers without fetching them |
| 6.29 | Simulated drive | In every prototype build (route sheet overflow menu and test extras); "Simulate drive" stays visible in the route sheet's menu in every build (the owner, 2026-11-16, 6.38 question 4) | The owner tests on the phone; one session code path for real and simulated fixes |
| 6.30 | Parallel work | Frozen `:core:api`, API-only edges, Gradle isolation (5.3), `check_contracts.py` | Seven engineers in one checkout must not compile each other's half-edited code |
| 6.31 | Which route type a re-route uses | The trip's `routeType`, set by the chosen card (C7) and saved in settings and `trip.json` | Choosing Shortest and then missing a turn must not give a Fastest route (review J1) |
| 6.32 | The 12/24-hour clock | Follows the phone's setting, not the units setting | The owner decided units only; a metric user may still want 3:42 PM (review J9) |
| 6.33 | Where `camera.json` lives | `packbuilder/routing/camera.json`, copied like the other rule files | One source of truth for rule data, also for a later comma 3X port (reviews J11, F14) |
| 6.34 | Licence policy | Enforced by `notices.py --check` (SPDX per entry, no GPL/AGPL/LGPL, native libraries and the desugaring configuration included; the allow-list is empty since 2026-11-17, when `desugar_jdk_libs`, its one entry, went with Ferrostar, and JNA, taken under its Apache-2.0 option, went too); independence stated in 7 | "Nothing GPL ships" must fail a build, not rely on reading (review J4); the owner accepted one exception, not two (Round 0 review R5) |
| 6.35 | Who builds `:nav:ferrostar` | Track A, against the frozen port of 2.4 (the module was deleted on 2026-11-17, 6.14) | Takes the riskiest device work off N, the critical path, without a second engineer for N (review F15) |
| 6.36 | Most stops per trip | 9 (`planning.max_stops`; confirmed by the owner on 2026-11-16) | Origin + 9 is the largest set "Best order" can solve exactly (10 points); "several stops" is met |
| 6.37 | How the start point is chosen | Search in SET_START mode from the route sheet's start row (`SetOrigin(FromPlace)`); the sheet's "Use my position" sends `SetOrigin(CurrentPosition)` | "Choose a start point" needed a home (review J3) |
| 6.39 | One archive per state, or the parts? (downloads) | **Each part is its own file** on the server, downloaded straight into a job folder on the packs volume, then handed to the installer, which **moves** them in (no copy) | Resume per part; house numbers skippable without fetching them; no extraction pass and no second copy (the archive way needs the tar plus the unpacked pack); every file stays under the 2 GiB GitHub asset limit (downloads 2.2) |
| 6.40 | Compress for transfer? (downloads) | **gzip level 6** for every part of at least 64 KB that shrinks to 90 % or less (measured: routing and search; expected by the same rule: the routing index and house numbers); **map stays as it is**. zstd later. | Measured on the PA z14 pack: 1,033.8 MB raw, **548.5 MB** with gzip on routing + search (-46.9 %); zstd-19 saves only 49 MB more and needs a native library on the phone; unpacking costs 5.6 s on this PC (downloads appendix A) |
| 6.41 | The existing `.tar` archive (downloads) | Stays the **file-sideload format**, unchanged, behind "Use a file instead" | Users without network or with their own builds; P's archive install is untouched |
| 6.42 | Android mechanism (downloads) | **The app's own foreground service of type `dataSync`** (`DownloadService`), started only from a tap while the app is visible, running a UI-free engine; partial wake lock while bytes move; **no WorkManager, no DownloadManager** (downloads 5) | WorkManager's long-running workers count against job quota on Android 16 and Google's own page names "launch the foreground service directly" as the way out; one code path for API 26-36; streaming SHA-256 and our own resume rules |
| 6.43 | Resume (downloads) | `Range: bytes=N-` plus `If-Match: <ETag>` on the stable catalog-relative URL, re-resolved on every attempt; the SHA-256 of the whole file is the final judge | The release host ignores `If-Range` but honours `If-Match` (research, measured); signed redirect targets expire |
| 6.44 | Catalog signing (downloads) | **Not signed in format 1**; HTTPS plus per-file SHA-256; the format reserves a detached `catalog.json.sig`; **signing is required before any second host (mirror) is added** (downloads 3.3) | A Python client must work with the standard library, which has no public-key verification; with one HTTPS host a signature protects only against a takeover of the owner's GitHub account |
| 6.45 | Network rule (downloads) | One class (`PackHttp`) opens connections, only to the configured catalog address and inside its scope (every resolved address public, except the debug build's own local server), only plain GETs with fixed headers, only after a user action; MapLibre is given an HTTP client that refuses every request. Checked by a manifest check, a source lint, JVM tests (the rule itself, the composed styles, the routing engine's configuration) and, on the device, per-app traffic accounting and a packet capture (downloads 4) | Testable replacement of Round 0's "no INTERNET" guard, which made any stray request fail by itself |
| 6.46 | First-run picker (downloads) | After the driver notice: "Choose your state" with a **drawn US map (Compose Canvas, Alaska and Hawaii as insets) and a searchable list**, one selection for both; a tap never starts a download, it opens a sheet with sizes and choices (downloads 1.1, 6.8) | No second MapLibre view and no dependency on P's style composer; works with no overview map and no network; the list is the accessible path |
| 6.47 | Skipping (downloads) | **Allowed** ("Not now"): the overview map with no state, a chip on the map ("Choose your state", or the download's progress while one exists) | A user with no network and no file must not be trapped; the overview map and the States screen remain reachable (downloads 1.5) |
| 6.48 | Updates (downloads) | Shown on the States screen when the catalog (fetched only when that screen opens) has a higher `pack_version` for an installed pack; installed by the same flow, atomically replacing the old pack | No background network use (rule N4) |
| 6.49 | Mobile data (downloads) | Never used without a tap: on a metered network the sheet offers "Download on Wi-Fi only" (default) and "Use mobile data"; no setting (the owner, 2026-11-16, 6.38 question 7) | Polite default for 0.5-1 GB |
| 6.50 | One job at a time (downloads) | Yes; a second Download waits until the first is done ("One state at a time") | Simple state machine; queueing is later (downloads 11) |
| 6.51 | What P must add (downloads) | `PackInfo.packVersion`, `PackFolder`, `InstalledData.installsFromFolder` / `startInstallFromFolder` (moves parts), `changesOptionalParts` / `startAddOptionalParts` / `removeOptionalParts` (house numbers later), manifest v3 `pack_version`; until P delivers, G uses a stand-in that feeds the frozen archive install a tar made on the fly, and hides the house-number actions (downloads 6.6) | Track G builds and tests without waiting for P |
| 6.52 | When downloaded bytes are deleted (downloads) | **Only when they can never be installed** (the build is gone from the server, the installer refused it, the app cannot read it) **or the user cancels** (with a confirmation). Every other stop (no server, a damaged part, a server that breaks the rules, a crash, the system) pauses with the files kept and offers Continue (downloads 1.4, 6.4) | A server that is asleep or a bad part must not cost the user gigabytes already verified |
| 6.53 | House numbers after install (downloads) | The States row says whether a state has them; **Add house numbers** (fetches only that part) and **Remove house numbers** once P's optional-part install exists (downloads 1.2, 6.6) | The owner's rule "house numbers can be skipped" needs a way back that does not cost a 550 MB re-download |
| 6.54 | Where is the search bar? (saved places) | A pill at the top of the map, under the status bar, with the Settings button beside it, while no trip exists (IDLE, RESUME_OFFERED). Hidden during route choice and guidance; search is reached there through the route sheet's start row (SET_START) and "Add stop" (ADD_STOP) (rule S8; saved places 1) | Round 0 had `SearchBar` ("the search pill on the map") but no place for it; the lead's statement to the owner |
| 6.55 | Model (saved places) | A new `SavedPlace` with `SavedKind` (HOME, WORK, OTHER) in `:core:api`; when used it becomes an ordinary `Place` with the new `PlaceSource.SAVED` and the new `Place.savedId` (saved places 5.1) | The map scene (M) and a later car screen need the type and cannot see `:core:search`; the session, sheets, stops and `trip.json` keep using one type, `Place`. Both changes are additive |
| 6.56 | Store (saved places) | `SavedPlaces` in `:core:search` (S), JVM, beside `RecentPlaces`; validation in memory at once, file written on `Dispatchers.IO` (saved places 3.3) | S owns search; JVM tests; Android Auto reuses it; no fsync on the main thread while a pack install fills the disk |
| 6.57 | File (saved places) | `saved-places.json`, format `beamline-saved-places/1`, in the app's **no-backup folder**; atomic write; reading checks the structure only, never today's name rules, so a later rules change cannot discard the list; a damaged file is kept aside, never overwritten, and the user is told until they confirm; a newer format makes the store read-only (saved places 3) | Saved places are precious and private; Android never backs up or transfers `no_backup/` |
| 6.58 | Identity (saved places) | A random id; every saved place carries its own name, point and description; no search id, no pack id (SP54) | It must survive a pack being rebuilt or removed |
| 6.59 | One tap (saved places) | Tapping Home, Work or any saved place in GO_TO search goes straight to route choice when the place is inside the installed maps; otherwise its place sheet opens with "Add a state" (SP12, SP13) | The lead's promise for Home and Work; one rule for every saved place is easier to learn |
| 6.60 | Replacing Home (saved places) | The old Home is removed, after a confirmation that names it (SP28) | One rule, no surprise entries; moving house is the usual case. Owner question 10 (6.38), answered on 2026-11-16: as built |
| 6.61 | Names (saved places) | Other places have unique names (case, accents and punctuation ignored; any script and emoji kept), up to 40 characters as the eye counts them; "Home" and "Work" are reserved (SP26, SP55) | Typing a name in search finds exactly one place, in any language |
| 6.62 | Search (saved places) | Saved places whose name matches the typed text are listed above every map result, at most 3, except while choosing a place to save (SP20-SP22) | They are the user's own words; the engine finds everything else |
| 6.63 | Recents (saved places) | Picking a saved place never adds a recent; a recent that is a saved place is not listed (SP30, SP31) | Shown once |
| 6.64 | Markers (saved places) | Three new sprite icons (blue rounded squares: house, briefcase, star), Home and Work from zoom 10, others from zoom 12, above the route line while browsing and choosing a route, below it while guiding, below pins and the vehicle; on by default (owner question 13, 6.38, answered on 2026-11-16: on by default); a "Show on the map" switch stored in the same file (SP40-SP46) | Small and distinct from POI icons; the route stays readable at speed; the switch needs no change to frozen `:core:settings` |
| 6.65 | Backup and transfer (saved places) | Excluded from cloud backup and from device-to-device transfer, together with `recents.json` and `trip.json`, which can hold the same places (PV4) | "Stored only on the phone"; nothing leaves the phone unless the user exports it |
| 6.66 | Logging (saved places) | No saved place's name, description or coordinates in any log line: `Place` and `SavedPlace` print no name or point, every log call that prints a value is marked and reviewed, exceptions are logged by class name only; a source scan, JVM tests of the failure paths and a canary check of the emulator journey's log prove it (PV2, PV3) | The owner's rule; it must fail a check, not rely on reading |
| 6.67 | Export and import (saved places) | Not in this round; the export file is the store's own file format, already readable by a standard-library Python reader (saved places 3.6, 11) | Format ready, no extra screens now. Owner question 11 (6.38), answered on 2026-11-16: saved places are lost on a new phone for now, export comes in a later round |
| 6.68 | Tests on the owner's phone (saved places) | Only in a separate "test" data set of made-up places, chosen by a test extra on a cold start; never clear data, never read the app's files, never uninstall (PV7) | Real saved places must never reach a screenshot, a UI dump or a log file, and must not be lost |
| 6.70 | Shields while guiding (owner, 2026-11-18; batch A) | The style's `roads_shields` layers are moved above the route ahead's copy while guiding and back after it; road names stay under the line (K3) | The driven road's route number ("US 19", "I 90") was hidden under the line; moving the one shield layer keeps the browsing order and its label priority as the style has them |
| 6.71 | Map style Automatic (owner, 2026-11-16; batch A) | Sunrise and sunset on the phone (NOAA equations in `:core:settings`), night from civil dusk to civil dawn with a 0.5 degree margin each way, from the last known position, else the middle of the installed state, else the phone; checked every minute and at the switch while the app is open; "Same as phone" its own choice; a stored "automatic" means the new Automatic (2.10) | No network and no new library; civil twilight is the standard edge of "dark"; the margin stops a flicker; the owner asked for the real switch |
| 6.72 | Speed-limit sign and speed (owner, 2026-11-17; batch A) | D8: the stretch's limit from `annotation.maxspeed` in `Guidance.speedLimitKmh` (one field added to the frozen `Trip.kt`), the GPS speed in a badge beside it, yellow at 5 mph / 8 km/h over and red at 10 / 16, a dash without a good fix; top start under the banner | The data is already in every route answer; one optional field keeps every other user of `Guidance` unchanged; the corner under the banner is the only one free of the trip bar, the map buttons and the credit |
| 6.73 | Results on the map (owner, 2026-11-16; batch B) | S11: Enter in GO_TO shows up to 20 matches, nearest first, as RESULT pins (the destination pin's art at 80 %, the selected one the white PICKED pin) with a list at the bottom; the camera fits as many of the nearest as fit at z8 or closer; the results end with a new search, Back, the close button or a trip | Reuses the pick path and the pin art (no new artwork); fitting only what the states draw keeps the map from showing the empty overview; a single match needs no list |
| 6.74 | Opening hours (owner, 2026-11-17; batch B) | S10: Simon Poole's OpeningHoursParser (MIT, Maven Central, no runtime dependency) parses; Beamline's own `OpeningHours` evaluates a subset and refuses the rest; holiday-only rules left out with a note; read apart from the search by entry id | The ready evaluators (io.leonard's opening-hours-evaluator, MIT) treat public holidays as never matching and let later rules add to earlier ones, which can show a wrong status; a subset that refuses what it cannot evaluate never guesses; the search answers and the conformance reference stay as they were |
| 6.75 | Call and Website (owner, 2026-11-17; batch B) | S10: `ACTION_DIAL` and `ACTION_VIEW` on a tap, not while driving | No permission, no network code in the app (N1, N5); the phone's own apps do the rest |
| 6.76 | No alleys (owner, 2026-11-16; batch C1) | 2.11 "Every request": stops tied to the nearest residential-or-higher street within 300 m (not a motorway or ramp), else as before; `alley_factor` 5 on every route | 1322 Erie POIs with an address: 641 were tied to a driveway, 221 to a parking aisle, 63 to an alley; the hard filter with a 171 fallback keeps a place reachable that only a service way reaches; Valhalla 3.9.1 does not implement the `street` hint |
| 6.77 | Arrival side (owner, 2026-11-18; batch C1; C1 routing fix) | 2.11 "Every request" 3: asked when a stop is reached on the left, on every street (`street_side_cutoff service_other`); kept when it arrives on the right, at most 60 s longer, no extra U-turn (batch D, 6.82: tertiary and above only, at most two more instructions) | Erie probe: 128 of 300 arrivals on the left; the second review: with the side asked only on tertiary streets and above, 19 of 66 left arrivals were fixed among 150 Erie places, on every street 44 (data/review/batchC1-routing/p9.py); 450 plans: 140 left arrivals before the fix, 71 after; one more call only for those plans |
| 6.79 | Uncertainty bubble (owner, 2026-11-16; batch C2) | G7: Beamline's own estimator over the fixes it already gets (GPS and network providers of `LocationManager`): a 1/accuracy²-weighted mean while the accelerometer says still, a constant-velocity Kalman filter while moving; browsing only; no Google Play services and no fused provider (yet) | Standing still the fixes scatter around one point and the weighted mean is the best estimate of it, its spread the honest size of the bubble; a Kalman filter follows walking and driving with no lag on good fixes; the fused provider would hand the same fixes again (or, on a phone with Play services, Google's own fusion) and needs a rule of its own in `FixFilter`: left for the phone test. Synthetic tests (`BubbleTest`): indoors (100 m every 20 s, 50 m scatter per axis) the estimate moves less than 0.35 of the fixes' jumps, is nearer than 0.6 of their error, and the bubble is 45-110 m; walking (8 m fixes) and driving (5 m fixes at 25 m/s, even with the accelerometer reading still) no bubble, worst error under 10 m and 12 m |
| 6.80 | Batch B follow-ups (owner, 2026-11-18; batch C2) | S11: 100 candidates, nearest first, 20 within 25 miles or the nearest 5; results end when their state is removed; the list opaque. S10: details by name and position for places without a search id (the same-named POIs within 100 m, when their details agree, else nothing); Call and Website again once ARRIVED | The engine's top 20 by rank were re-sorted, so a nearer match ranked 21st was missed |
| 6.81 | Where a stop meets the road (owner, 2026-11-18, after batch C1; the second routing review) | 2.11 "Every request" 2, 4, 5: an address tied to its own street when within 300 m (one trace_attributes probe; valhalla-mobile has no locate); a dropped pin not tied; the tied route kept only when at most 90 s slower than the untied one and no extra U-turn; the arrival card says how far the place is past 100 m | The nearest residential street is often a side street (Family Dollar on East 12th Street was reached on Holland Street); in a mall the nearest street can need a U-turn (Ulta Beauty, Carter's, Five Below) or a long way round (American Legion Hall 771 s untied, 912 s tied); 450 Erie plans: the route ends on the address street in 411 instead of 236 |
| 6.82 | Route types and the arrival side (owner, 2026-11-18 evening; batch D) | Only Fastest and Simpler are offered; a stored Shortest is Fastest (P4, R3). Simpler: up to 10 % and never more than 10 minutes slower than the quickest of its pool, the fewest guidance instructions, a fourth candidate that keeps to major roads (2.11 "Simpler"); the tie check and the arrival side also for an alternate the pool picked. Arrival side: none on residential streets and below, on busier streets at most 60 s and two more instructions (one block) (2.11 "Every request" 3) | docs/next-round.md items 13 and 16, the owner's answers; measured in docs/experiments/24-batch-d.md |
| 6.83 | A route to a business ends in its own lot (owner, 2026-11-19; batch E) | 2.11 "Every request" 6: a point of interest ends on the nearest driveway or parking aisle within 80 m that is at least 10 m nearer than every street (never an alley or a drive-through lane); no side request there; the tie check compares it with the route without the lot (at most 90 s slower, no more U-turns); else the address street and the nearest street as before | docs/next-round.md item 18: the address-street tie of 0.1.4/0.1.5 ended the owner's restaurant on Peach Street; the agreed result enters the lot from the side street (Commons Drive) and ends beside the building; the street margin keeps downtown businesses with a driveway behind them on their street (25 of 38 downtown sample places found some driveway nearer than their street, 13 with the margin) |
| 6.84 | One end point for every route type (owner, 2026-11-19; batch E2) | 2.11 "Every request" 5, 7, 8: the end of each stop is decided once per plan with Fastest (lot, address street, nearest street; a pin as it is; at most 90 s slower than the next end, no more U-turns, no alley or drive-through lane in its last 300 m), the arrival side once; every route type and every re-route go to the decided ends; drive-through lanes and alleys within 80 m of a point of interest are left out of every request (exclude_polygons) | docs/next-round.md item 19: on 0.1.6 Simpler ended a restaurant on Peach Street on Peach Street (its own way into the lot 94-95 s slower than its own street end) while Fastest ended in the lot; the owner wants one general rule, no list of places, and no car-wash exception; measured in docs/experiments/26-batch-e2.md |
| 6.85 | Every bottom sheet gets a lowest height (owner, 2026-11-18; batch F) | D10: one frame in `:ui:theme` for the results list (three heights), the route sheet and the place sheet (two); a drag steps one height per 40 dp, rows drag the sheet at their top, a tap on the handle raises (from the highest, back to the lowest), screen-reader actions; the camera insets follow the measured height | The owner could not lower the list nor compare routes on the map; stepped heights keep one camera change per step and need no new camera code |
| 6.86 | Named beaches and other natural places a driver heads to are searchable (owner, 2026-11-18; batch F) | Search build only (`packbuilder/search`, no app code): new categories beach (`natural=beach`, `leisure=beach_resort`), waterfall (`natural=waterfall`, `waterway=waterfall`), cave (`natural=cave_entrance`) and peak (`natural=peak`), each with its words; `natural=*` / `waterway=*` objects are kept only for these values (no woods, water, springs, cliffs); two derived keys: "No. N" also keyed as "N" ("Beach no.1" -> "beach 1"), and a natural place inside named parks also keyed "<park> <label>" / "<park> <name>" ("presque isle beach"); icons from the existing sprite (waterfall uses viewpoint, cave uses attraction). PA and OH search parts rebuilt; packs `pack_version` 202611200917 | "Beach 10" found nothing: `natural=beach` was dropped by the extract. The `leisure=park` "Beach 1" to "Beach 4" are four parks near Lake Harmony, in the index all along; Presque Isle's beaches are all `natural=beach`. Derived keys rank as `exact_alt`, so no engine change and no new ranking rule |
| 6.87 | The place sheet's details height (owner, 2026-11-20; batch G) | S10, D10: a third height of the place sheet with the week, every number and web address written out (tap opens as Call / Website, long press copies), the address and the category; a saved place looked up by its own name; a search id read only while its entry is still the place | The owner wants the hours of every day and the number and website spelled out; ids move when a pack is rebuilt (Erie Zoo 433934 -> 435720 between the 11-16 and 11-20 Pennsylvania builds), so an old recent could have shown another place's hours |
| 6.88 | The place itself as the last end (owner, 2026-11-20, after batch G; batch H) | 2.11 "Every request" 5, 7: every list of ends of a tied place ends with the place itself, untied (its own point, no stop tie), so the nearest street has to pass the check against it (at most 90 s slower, no more U-turns); the place never wins with an alley or a drive-through lane in its last 300 m (its tail asked when it would win; then the nearest street, unless that finds no way) | docs/next-round.md, the owner's answers after batch G: batch E2 kept the nearest street with no check, so a plain place whose address street is out of reach could end 191 m away with a U-turn (Ulta Beauty as an address); the alley guard keeps C1's "no alleys unless there is no other way"; measured in docs/experiments/29-batch-h.md |
| 6.91 | Online batch O3 (docs/online-design.md 4.3, 4.5; docs/next-round.md items 24-27; the O2 review) | "Add a missing place" on a dropped pin (hidden while a trip is driven or the switch is off; signed out: sign in or a note "Missing place: ..."): a type from the search's categories (`PlaceTypes.ADDABLE`, tags checked against categories.json), name, hours, phone, website. The spot check: the pack's POIs within 60 m at once and OSM's map of a 120 m box (`GET /api/0.6/map.json`); duplicates (same name within 50 m, same type and a similar name within 50 m, an unnamed type within 25 m) offer "Edit that one instead"; a business within 30 m or in the same building outline gives "Already on the map here" with "My place replaced it" / "Both are here" / "It's the same place"; an address alone gets the tags. One changeset ("Added <type> <name>", `created_by`, `source=survey`) with one node at the pressed point; the outbox runs the duplicate check again at send time. "This is now a different business" in the edit form: the old business's own tags go, address and building stay (`BusinessTags`), made again at send time. The friendlier form: times picked with Material 3's time picker in the phone's 12/24-hour setting, day groups, split days, Open 24 hours, "(next day)", the public holidays line, the sheet's own week as the preview, "Changed" with Undo, the discard question, phone and website checked, the OSM text in a fold-out; the writer still reads its text back with the hours parser. Your changes on your map at once: sent additions, edits, replacements and closings shown in search, on the place sheet ("Your edit · waiting for the map update") and on the map (an own layer copying the style's POI layer; the old label hidden by the tiles' feature id, (type << 44) \| OSM id) until an installed pack's OSM data is of a later day; a closed place is hidden on the map and "Closed permanently" in search and on its sheet. "View all" place types (the pack's `category` table, grouped), the same grid as the type picker. | docs/experiments/31-batch-o3.md |
| 6.90 | Online batch O2 (docs/online-design.md 4.1, 4.2, 4.4, 4.5; the owner's answers 2, 4, 6) | OSM sign-in by OAuth 2 with PKCE (S256) in the browser; the redirect `io.github.spysyweeb.beamline://oauth` is accepted only for the sign-in this app started (its state, once, within 15 min); the token and the user's name sealed with AES-GCM under an Android Keystore key in `no_backup/osm-account.bin`; sign-out deletes it and asks OSM to revoke it when online. Client ids in the build (the live one only in the plain release). Notes: "Post with my OSM name", checked to start with when signed in; signed out at send time the note goes anonymously. "Edit on OpenStreetMap" for a node or way of the pack's `poi.osm` (a relation opens on openstreetmap.org): name, a week editor checked with the app's hours parser (anything it cannot express read-only), phone, website, "Closed permanently" (main keys amenity, shop, craft, office, leisure, tourism, healthcare, club to `disused:`; opening_hours, phone, contact:phone, website, contact:website removed; name kept; OSM wiki Key:disused:). The place is read before the form; the summary; one changeset (comment, `created_by=Beamline <version>`, `source=survey`), the element at the version read, close. Edits go through the outbox: right after the summary the element read is uploaded (OSM's 409 brings the form back with the new values); an edit that waited is read again and, if the place changed, waits for the user (REVIEW). Sent edits stay in the outbox and show on the place sheet ("Your edit, waiting for the next map update") until an installed pack has the values. Test objects only by a test extra of test builds. | docs/experiments/30-batch-o2.md |
| 6.89 | Online as a choice, batch O1 (owner, 2026-11-16 and 2026-11-20; docs/online-design.md) | The first-run step after the notice ("Offline only" / "Allow online features", equal, neither selected; shown once after an update too); Settings "Online features" (main switch turns every feature on, each turns off alone; "Contribute to OpenStreetMap"; "My reports"); network rule N1-N5 per service (`OsmHttp`, OSM test server in test builds, openstreetmap.org only in the plain release, refused while the switch is off); the outbox (`no_backup/osm-outbox.json`, PV1-PV8; sent when the app is open, no background service; keeps the note's number and link); "Report a problem" (a dropped pin's "Report a problem here", a place's details height; at the pressed point or the place's point, never the phone's position; anonymous; the first-send public notice; hidden while a trip is driven and while the switch is off); every note ends with "via Beamline" | "Offline only" stays a promise proved by per-app accounting; online features never leak saved places or the position (docs/online-design.md section 3); report docs/experiments/29-batch-h.md |
| 6.78 | Lane warning (owner, 2026-11-18; batch C1) | B9, D9: OSRM intersection lanes plus trace_attributes lane counts; a spot needs evidence of the road's own lane count | Peach St at Craig St warns; an added turn lane, the exit 24 trip and a short stretch after a turn (the pocket itself) do not |
| 6.69 | While driving (saved places) | In GUIDING, REROUTING and ARRIVED nothing about saved places is created or changed, and using one needs no typing: sheets offer no Save or Edit, the list opened for a stop is pick-only, Settings' "Saved places" reads "Not while guiding", stop search opens without the keyboard (SP4) | Saving and naming are tasks for a parked car; Home, Work and the list stay one tap away for a stop |

### 6.38 Questions only the owner can answer

The owner answered questions 1, 4 ("Simulate drive"), 5, 6, 7, 10, 11 and 13 on 2026-11-16 (each marked
below); the decisions and the text that called them drafts or assumptions say so. Still open, and
still drafts where built: 2 (pins), 3 (the Interstate shield), 4's PA keystone shield, 8 (the
public host), 9 (update checks), 12 (the saved-place icons), the overview colours below z7 (report
12), and the texts tracks G and S added (reports 14 and 18). Whether Ferrostar stays (report 17)
was **answered on 2026-11-16: dropped** (`docs/next-round.md`, "The owner's answers"; done
2026-11-17, 6.14).

1. **Answered 2026-11-16: approved as written.** The first-run notice text (5.1, A; no longer a draft).
2. Stop and destination pins: accept plain blue numbered pins until art is drawn?
3. Interstate shield: keep the bundled monochrome shape, recolour it red/white/blue, or replace it
   (report 06, decision 3)?
4. Before publishing (not needed for the prototype): have the PA keystone shield's use checked, and
   decide whether "Simulate drive" stays visible in public builds. **Answered 2026-11-16 for "Simulate
   drive": it stays visible in the route sheet's menu in every build.** The keystone shield is still
   open.
5. **Answered 2026-11-16: as built (routed with the warning).** A stop or destination on a toll road (a
   Turnpike service plaza) with Avoid tolls, or on a
   motorway with Avoid highways: this document routes there anyway, keeping off that road as much
   as the route type allows, with the warning "No route to this place avoids toll roads. This route
   uses them." (2.11 rule 6), instead of showing "No route". Confirm, or choose "No route" with a
   hint.
6. **Answered 2026-11-16: confirmed.** JNA, which Ferrostar's core needs, is offered under
   LGPL-2.1-or-later or Apache-2.0. Beamline
   takes it under Apache-2.0, so it is not an exception to "nothing LGPL ships". Confirm.
   (Moot since 2026-11-17: JNA went with Ferrostar.)
7. **Mobile data** (downloads, `docs/architecture-downloads.md` 12.1). **Answered 2026-11-16: as built
   (mobile data only after a tap, no setting).** The app never uses mobile
   data for a download without a tap and offers "Download on Wi-Fi only" first. Recommendation:
   keep it (no setting needed now).
8. **The public host** (12.2 there). Recommendation: a separate public repository only for packs
   (for example `beamline-packs`), GitHub Releases as in its 8.3, created only when you say so; a
   paid mirror only if GitHub limits downloads.
9. **Update checks** (12.3 there). The list of states is fetched only when you open the first-run
   screen or States. Recommendation: keep it so for now; a daily check at app start would be the
   first background network use and should be your explicit choice.
10. **Replacing Home or Work** (saved places, `docs/architecture-saved-places.md` 12.1). **Answered
    2026-11-16: as built (removed after the confirmation that names it).** The old
    one is removed, after a confirmation that names it (recommended), or kept as an ordinary saved
    place under its own place name?
11. **New phone, or a reinstall** (saved places, 12.2 there). **Answered 2026-11-16: saved places are
    lost on a new phone for now; export comes in a later round.** Saved places stay on this phone and do
    not move with Android's phone-to-phone transfer. Until export exists they are lost on a new
    phone and on any uninstall, and an uninstall is forced when an update cannot install over the
    old app: today's builds are signed with the build PC's own debug key, so a build from another PC
    cannot update the installed app. Choose: (a) accept for now, with export and import in the next
    round, and the orchestrator signs every build with one kept key so updates never need an
    uninstall; or (b) also add a plain "Save a copy" row this round (3.6 there, about a day of
    work). Recommended: (a), because the kept key removes the forced uninstall and re-entering Home
    and Work takes a minute; (b) if you already have many named places.
12. **The look of the markers** (saved places, 12.3 there): the three icons and the blue colours of
    its section 8, on the contact sheet and the phone screenshots. They are filled squares where the
    POI icons are pale, so they read as "yours", and their blue is the route's blue (casing by day,
    fill by night): while guiding they sit under the route line, in route choice over it with a
    white edge. Approve, or change (recommended: approve; the other choice is an amber square, which
    no POI uses either)?
13. **Markers on the map by default** (saved places, 12.4 there). **Answered 2026-11-16: on by
    default.** Home, Work and your places show as
    small markers unless you turn "Show on the map" off (Settings → Saved places). With them on, any
    screenshot or shared view of the map near home shows where Home is. Keep them on by default
    (recommended: yes, they make the places easy to tap), start with them off, or show Work and your
    places but never mark Home?

## 7. What must not be blocked for later

- **Android Auto on the same session.** The session, its state and intents live in JVM modules
  with no phone UI; `BannerView` carries maneuver kind, roads, exit number and lanes, enough for
  `NavigationTemplate` (`Step`, `Lane`, `TravelEstimate`); `CameraDirector`, `MapSceneMapper` and
  `MapController` are Android-free so a car-surface renderer can implement `MapController`; the
  foreground service already holds the process for a car screen. Nothing may reference a `View`,
  `Activity` or Compose type below the feature modules. Saved places come along unchanged
  (`SavedPlaces`, `SavedPlace`, `toPlace()`, `MapScene.saved` and `SavedMarkerRules`: a car
  destination list is Home, Work, then the others); the car never edits them
  (`docs/architecture-saved-places.md` 9).
- **Lane-level map stages.** P's rebuilt packs keep the lane attributes at z14
  (`schema_extensions.lanes_minzoom`); M inserts its route layers by layer id, so a lane layer can
  go between roads and the route line; the owner's distances for the later "move over" advice
  (1 mile on highways, 0.3 mile on main roads) go into `guidance.json` when that stage is built
  (today's `lanes_within_m` values are only the lane row's display window, B5); the session exposes
  the vehicle's snapped position, the route geometry and the upcoming maneuver for that advice and
  the cruising-lane rule (second from the right).
- **Voice and chime.** `TripEvent.Announce` is emitted at data-driven distances (B8);
  `Settings.guidanceSound` exists; the design's spoken "then" (0.25 mi) and lane hint rules go into
  `banner.json` or `guidance.json` when built.
- **Search along the route.** The guided route's geometry and the traveled cursor are in
  `TripState`; `SearchContext` can gain an along-route variant; the engine's R*Tree boxes along the
  remaining polyline are the planned query (report 10).
- **comma 3X port.** Pack format v3 has a standard-library Python reader; every rule (presets,
  banner, guidance, units, camera, search rules) is a data file under `packbuilder/`; the golden
  trace `exit24.json` lets a Python session be checked fix by fix, the follower included (plain
  geometry in `GeometricFollower`; Ferrostar was dropped on 2026-11-17). The catalog of downloads is device-neutral, and
  `packbuilder/catalog/catalog_client.py` is a standard-library client that yields a pack folder.
  The saved places' file is plain versioned JSON with a standard-library reader
  (`packbuilder/search/saved_places.py`, the same name key and matching); places reach a 3X only
  through the user's own export, never by sync.
- **Export and import of saved places.** The store's own file format is the exchange format; the
  screens come later (`docs/architecture-saved-places.md` 3.6 and 11; owner question 11).
- **More from downloads.** Mirrors, a signed catalog, background update checks, several downloads
  at once, zstd, a user-initiated data transfer job on API 34+: `docs/architecture-downloads.md`
  section 11 says what each needs (signing comes before any second host).
- **Independence.** Beamline uses no OsmAnd code, artwork, file formats or servers, and no Organic
  Maps data files; the catalog format of downloads is its own. Reviewers check the provenance of new art (pins, icons, markers) and of every
  data file a track adds; the About screen's licences list is the record of what ships.

## 8. End-to-end test: the owner's exit 24 trip

Set-up (I, emulator first, then the phone): release APK with test extras, `us-pa` and `us-oh` v3
archives added through States → "Use a file instead" (house numbers included); or a debug APK built
with `beamline.catalogUrl` pointing at the development server on the build PC, the two states
downloaded through the first-run picker and States (`docs/architecture-downloads.md` 8.1, 10.4; a
release APK has downloads off until publishing day). Settings: Day map, miles, Silent. The
route's own figures (report 09, rounded): total 5,341.6 m; maneuver 1 "Exit 24" at about
4,173 m from the start, maneuver 2 "Turn left onto US 19 / Peach St" about 422 m after it,
maneuver 3 "Turn right onto Downs Dr" about 356 m after that, the destination about 391 m further.
Distances below are along the route.

Start the trip: `--es origin "42.028694,-80.128884,57" --es dest "42.053308,-80.079579,Downs Drive"`,
then the simulated drive with the `default` script (`--es simulate default`; the route sheet's
"Simulate drive" plays the route without the halt, D4): speed factor 1, 1 fix/s, accuracy 5 m, a 20 s
halt about 15 m before maneuver 2. (Searching "Downs Dr" and tapping Directions is a smoke test
only: a street result's point is not this destination, and another end point can add a car-park
turn that changes rows 7-10.)

| # | Where | Banner | Lanes | Camera | Markers and line |
|---|---|---|---|---|---|
| 0 | Route sheet | none | none | PREVIEW: top-down, north up, the whole route and both pins in view | puck (blue B, white ring); route line blue B with casing; grey lines for other choices when they differ; destination pin. Cards: one card per distinct route, labels merged ("Fastest · …"); no Eco; toggles off |
| 1 | Start (0 m) | "Exit 24", "US 19 / Peach St", "toward Waterford", distance "2.6 mi"; chip "Then" left arrow "In 0.3 mi" | none (no lane data at the exit) | GUIDE_FOLLOW: pitch 60 (K3), heading up along I-90 east, arrow in the lower third, zoom from speed | puck replaced by the arrow on the line; lenses dark; no beam (day); trip bar "Arrive h:mm", remaining time, "3.3 mi"; "Simulated" badge |
| 2 | 1.0 mi before the exit | "1.0 mi", chip unchanged "In 0.3 mi" | none | follow; user pans: GUIDE_FREE, Recenter shown; 15 s later back to follow | driven part behind the arrow in the traveled colour |
| 3 | 0.5 mi before the exit (sketch frame 1) | "0.5 mi", chip "In 0.3 mi" | none | follow | as before |
| 4 | Gore of exit 24, the first fix past maneuver 1 | advances to "Turn left", "onto US 19 / Peach St", "0.2 mi" (the first fix past the gore comes about 23 m after it at 1 fix/s, 399 m before the turn in N's golden trace; "0.3 mi" only when that fix falls less than about 20 m past the gore, more than 0.25 mi before the turn; integration, 2026-11-16); chip right arrow "In 0.2 mi" | shown at once (about 400 m < window, whichever fix it is): left = allowed, left+through = best, right = not for route | follow, zoom in within 250 m of the left turn | arrow on the ramp |
| 5 | 400 ft before the left turn (sketch frame 2) | "400 ft", chip "In 0.2 mi" | as 4 | follow, approach zoom | — |
| 6 | Halt 15 m before the turn, 20 s | "50 ft"; nothing advances | as 4 | stays, bearing steady | after 1 s: brake lenses lit (and glow); off again within one fix of moving > 1 m/s |
| 7 | After the left turn (10 m past maneuver 2) | "Turn right", "onto Downs Dr", "0.2 mi"; no chip (arriving is not a turn) | shown at once: left, through, through = not for route, through+right = best | follow, heading turns with the road | arrow on Peach St |
| 8 | 0.2 mi before Downs Dr (sketch frame 3) | "0.2 mi" | as 7 | follow | — |
| 9 | After the right turn | "Arrive" with the side the route gives, distance counting down from "0.2 mi" | none | follow | — |
| 10 | 30 m before the end | ARRIVED card "Downs Drive" | — | animates to top-down, north up, centred on the destination | arrow, lenses lit once stopped; notification "Arrived at Downs Drive"; Done → IDLE, puck back, service stopped |

Throughout: the shown distance never goes up; the step advances at the three maneuvers only;
`gps` GOOD; no REROUTING; the notification (screen off for 30 s between rows 2 and 3) shows
"Exit 24 · …" and guidance continues; after screen-on the banner is current.

Repeat with the **Night** map: the beam shows ahead of the arrow on every row from 1 to 10, never
when stale; night route colours; brake glow at the night strength.

Variants, each a separate run (script names for `--es simulate`, built by N from the route):

| Variant | Script | Must show |
|---|---|---|
| `wrongturn` | at the end of the ramp, a 400 m detour the other way along Peach St | on the fifth off fix (1 fix/s: 3 off fixes spanning at least 4 s) after leaving the line: REROUTING, the banner replaced by "Finding a new route..." (D7); a new route to Downs Dr (event `RouteReplaced(REROUTE)`), GUIDING, banners of the new route; *U* never advanced while off the line; the arrow left the line only while suspected |
| `gaps` | no fixes for 8 s at 2,000 m; later 35 s at 3,000 m | 8 s: WEAK, the arrow keeps moving along the line, distance keeps falling, no advance, no reroute; 35 s: WEAK, then LOST after 30 s (grey arrow, no beam or lenses, "GPS signal lost", distance frozen); fixes back: GOOD, the arrow jumps ahead to the real position, no reroute |
| `tunnel` | no fixes for 60 s from 300 m before exit 24 to past the left turn | LOST during the gap; on the first fix after it the banner is "Turn right onto Downs Dr" at once (B2's catch-up loop), no REROUTING, no wrong banner on any later fix |
| `jitter` | 8 m sideways noise over the whole trip, one fix 70 m sideways on Peach St, one fix 15 s stale at 1,500 m | no REROUTING; `offRouteSuspected` for the one jumped fix only; the stale fix ignored (no jump back); advances within ±15 m of the clean run |
| `drift` | every fix 5 m to the right for 10 s on I-90 | `offRouteSuspected` never set; the arrow stays on the line |
| `stop` (test extra `addstop`) | at 1,000 m, `AddStop` of the first "sheetz" result near exit 24 | `replanning` while it runs; route replaced (`STOPS_CHANGED`) while guidance continues; trip bar "Stop 1 · …"; stop card for 8 s at the stop; guidance resumes to Downs Dr; ARRIVED at the end |
| options | Route sheet with Avoid highways, then Avoid tolls | Avoid highways: the same route (exit 24 is the first exit), card note "Highways"; Avoid tolls: no change (no tolls) |
| metric | `--es units metric` | "4.2 km", "400 m"-style texts, chip "In 400 m"-style, trip bar in km; the arrival clock unchanged (12/24 h follows the phone) |
| units mid-trip | switch Settings to kilometres at row 3 | the banner, chip and trip bar change units at once; nothing else changes |
| rotate | rotate the device at rows 2 and 7 | banner, camera follow and arrow survive; no restart of guidance |
| night switch | `cmd uimode night yes` at row 3 with map style Same as phone (Automatic until 0.1.1; set back afterwards) | the style reloads, route line, pins and arrow come back with the night colours and the beam; guidance continues |
| remove while guiding | States: remove Ohio at row 2 | Remove is off with "You can't remove a state during a trip." (G's States screen, `docs/architecture-downloads.md` 1.2; the installer itself refuses with "Not while guiding", 2.2 rule 4); nothing changes |
| remove while choosing | States: remove Pennsylvania on the route sheet (row 0) | the choices are discarded and re-planned; problem "outside the installed maps"; reinstall restores the cards |
| GPS path | no simulation: the route as a GPX through `adb emu geo fix` at 1 Hz (`geo_route.py`) | the same banner sequence as the clean run, through `AndroidPositionSource` |
| process death | during GUIDING (GPS path run): `adb shell am force-stop`, reopen | "Continue to Downs Drive?"; Continue re-plans from the current fix and guides on, same route type |

**Downloads** (a debug APK and `serve_packs.py` over P's v3 PA and OH packs; app data cleared first
on the emulator, never on the phone, PV7): the twelve journeys of
`docs/architecture-downloads.md` 10.4, each with its pass condition there: the driver notice before
any request and then one catalog request with only the headers of rule N3; a map tap on
Pennsylvania, the sheet and Download; the picker closing with the map fitted to the state; search,
directions and a simulated drive with the network off; interrupted downloads (a cut connection,
Wi-Fi off, the server stopped, a restart) that resume with `Range`; mobile data only; too little
space; an update; removal, refused during a trip; the network rule on the device (per-app traffic
accounting: the app's untagged traffic at most 16 KB each way; on the emulator a packet capture with
no destination beyond the development server, DNS and the idle baseline; no "Untagged socket"
StrictMode line); a build with no address; house numbers added and removed later.

**Saved places** (the test-extras build, the `us-pa` pack installed, made-up places at public spots
in Erie; emulator `beamline_i`): the seventeen steps of `docs/architecture-saved-places.md` 10.3,
each with its pass condition there: the search pill at the top with the Settings button beside it;
Home set from search, Work from a point on the map, a named place from a place sheet; after a cold
start the rows, the recents without the saved place, and the matches; one tap on Home to the route
sheet; a saved place as a stop; the markers at their zooms, none under the destination pin, upright
and never over the route line while guiding; a marker's sheet; the "Show on the map" switch;
rename, change of location, make home, remove; the three files only in `no_backup/` (`run-as`,
emulator only); the night colours; no canary word or point in the captured logs
(`check_private_logs.py logcat`) and the manifest's rules (`manifest`); a damaged file kept aside
with its notice; content descriptions and TalkBack.

On the phone (USB, `ANDROID_SERIAL` set to it; every session as rule PV7 of
`docs/architecture-saved-places.md` says: started cold with `--es private_data test`, no clearing of
data, uninstall, `run-as`, pull of the app's files or log grep of the owner's places; one clearing
of the app's data at the start of the phone step that follows integration was allowed once by the
owner on 2026-11-16, before any saved place existed; never again): the clean run
by day and by night, `wrongturn`,
and one stationary check (indoors: WAITING or WEAK, then LOST after 30 s without fixes; outdoors
back to GOOD); the download journeys 2-6, 10 and 12 over the home Wi-Fi (the build PC's address only
in `android/local.properties`), run from the States screen after removing the state there (journey
1, a fresh install, and the first-run screen are proved on the emulator only: the phone's data is
never cleared), journey 5 with the phone locked for 5 minutes, and once with the router's internet
link unplugged (`docs/architecture-downloads.md` 10.4); the saved-places steps 1-8, 10, 11 and 18 (and 17 when the
emulator had no TalkBack) in the test set, screenshots under `data/phone/private/`. The owner's real drive of
the trip is the last step and is not part of this round's done criteria.

## Appendix A. Review findings and what was done

Two reviews of the draft (lens "journeys", findings J1-J15; lens "fit", findings F1-F16), both
"sound with fixes". Each finding was checked against the code of the tree before the contract was
changed.

| # | Finding | Checked | Outcome |
|---|---|---|---|
| J1 | Choosing a card does not set the route type; a re-route or Resume drops Shortest | No rule for `Choose` in the draft | **Fixed**: rule C7, `TripState.routeType` doc, decision 6.31, N's tests |
| J2 | The 30-minute resume window counts from the last event, so a long trip killed mid-way is lost | `TripSnapshot` had only the write time; writes only on events | **Fixed**: rules R1-R3 (`resume.refresh_s` 30 s), `wallClockMs` in the constructor, decision 6.24 |
| J3 | Frozen types lack hooks: busy flag, "choose a start point", sideways drift, start-blocked reason | All four absent in the draft | **Fixed**: `TripState.replanning`; `SearchMode.SET_START` + `DriveEvent.ChooseStart`; `SimEvent.Drift`; `present(..., locationAllowed)` + `RouteSheetUi.startBlockedText` |
| J4 | Licence policy not enforced; OsmAnd / Organic Maps independence not stated | Only a missing-entry check existed | **Fixed**: A's licence gate (5.1), decision 6.34, "Independence" in 7 |
| J5 | Tunnel: catch-up after a gap with two maneuvers inside | B2 called `advance()` once per fix | **Fixed**: B2 loops within one update, A1 too; `tunnel` E2E variant. Dead reckoning past 30 s stays a phone-test number, not an owner question |
| J6 | Banner during REROUTING and while off the line | Undefined | **Fixed**: B2 (no advance while suspected or rerouting), D7 (no banner in REROUTING) |
| J7 | Stop editing: ADD_STOP only while guiding, destination moves, re-plan storms | As stated | **Fixed**: S7 (`tripActive`), C1 (destination moves), C2 (300 ms coalescing, older revisions skipped) |
| J8 | No way to the States screen with nothing installed; installs unprotected; holdChanges vs a running install | As stated | **Fixed**: `SearchOutput.OpenStates`, `PlaceAction.OPEN_STATES` (S7); keep-screen-on during installs (4, 5.4); `holdChanges` returns null while an install runs, `ProblemKind.PACKS_BUSY` (P5) |
| J9 | Units also switch the clock to 24 hours | `units.json` clock was per unit system | **Fixed**: clock follows the phone (`clock24h`), decision 6.32 |
| J10 | About credits omit TIGER, NAD, Scranton | Report 04 lists them in the address file's attribution | **Fixed**: per-part attribution in pack format 3, `PackInfo.attribution` doc, A's credit list |
| J11 | `camera.json` authored in Android code | As stated | **Fixed**: `packbuilder/routing/camera.json`, copied by `sync_data.py` (decision 6.33) |
| J12 | Rotation, night switch, units change and pack removal mid-trip untested | `configChanges` exists in today's manifest; nothing said to keep it | **Fixed**: 5.4 item 10; E2E variants rotate, night switch, units mid-trip, remove while guiding/choosing |
| J13 | `wrongturn` timing and the "search Downs Dr" alternative | O3 at 1 Hz confirms on the fifth fix | **Fixed**: section 8 wording; the search start is a smoke test only |
| J14 | Approximate-only location grant | G1 drops non-gps fixes, so guidance would wait silently | **Fixed**: treated as refused for Start (D4, section 4) |
| J15 | S's done criteria need P's OH search file | `data/search` holds only `pa-search.sqlite` | **Fixed** with F3 |
| F1 | Round 0 gate cannot pass: `:routing:demo` does not compile | True at the snapshot (commit 0eef649 calls a two-argument `compose`) | **Rejected, no longer holds**: the rename's working tree already calls the four-argument `compose` with `AppStyle` and `Readers`; `:routing:demo:assembleDebug` passed in Round 0's baseline build and in the final build |
| F2 | Avoid tolls (and the highway fallback) do nothing with Shortest | Reviewer's pyvalhalla output read (`use_tolls: 0` ignored with `shortest`) | **Fixed**: Avoid tolls = `exclude_tolls` for every type, Carlisle-King of Prussia case in N's tests; the owner accepted the toll-road-start limit. What a destination on a toll road does was stated wrongly; corrected after the Round 0 review (R1: no-route fallback, `option_fallback_when: false` on Shortest) |
| F3 | S needs data only P makes; shared Python/Kotlin files | As stated | **Fixed**: P's first-wave hand-over, S's fixtures, change-by-request files (5.1) |
| F4 | `:ui:theme` carries route blue A | `Theme.kt` had #5BAAFF / #4B96EE | **Fixed**: moved with B (#4A8CF7 / #3A80F0) |
| F5 | A has no host app for the icon and splash | As stated | **Fixed**: `:demo:about` |
| F6 | M's marker export writes into read-only `data/art` | `build_markers.py` writes `data/art/markers` (today candidate A) | **Fixed**: M owns `data/art/markers/` this round and rebuilds it in B |
| F7 | A stateless mapper cannot produce `glideMs` | As stated | **Fixed**: `MapSceneMapper` is a class, one per map |
| F8 | No wall clock for `trip.json` ages | As stated | **Fixed**: `wallClockMs` |
| F9 | `SharedPositionSource` never restarts after its upstream ends | Reproduced in a JVM test | **Fixed**: the upstream is collected again after `restartAfterMs`, also after a failure; three tests |
| F10 | The settings store cannot fall back to the presets' default | `:core:settings` has no presets | **Fixed**: the store keeps the string; the session checks it (P4, 2.10) |
| F11 | Stop cap, TOO_MANY_STOPS, hold and notification intervals not in data | As stated | **Fixed**: `ProblemKind.TOO_MANY_STOPS`, `planning.max_stops`, `problem_hold_s`, `notification.min_interval_s` |
| F12 | The integrator would write rules: simulation scripts, card timing, screen rewrites | As stated | **Fixed**: named scripts by N (`Simulation.scriptName`), `EventCards` by D, explicit rewrite items in 5.4 |
| F13 | The lane-row window is called the owner's decision | It is this document's guess | **Fixed**: B5 and section 7 wording |
| F14 | `camera.json` outside `packbuilder/` | Same as J11 | **Fixed** with J11 |
| F15 | Track N is the critical path; split it into N1 and N2 | N's list is the longest | **Rejected as proposed** (a seventh engineer); instead `:nav:ferrostar` moved to A (decision 6.35) and the follower's device test no longer waits for N's golden trace |
| F16 | A replaced tile set stays open while the session idles | As stated | **Fixed**: 2.2 rule 3 |

**Review of Round 0** (lens "parallel-ready", "sound with fixes", findings R1-R7). Each finding was
checked again before anything was changed.

| # | Finding | Checked | Outcome |
|---|---|---|---|
| R1 (major) | Avoid tolls: a destination on a toll road gives "No path", not "may use it near that end" | Reproduced with pyvalhalla 3.9.1 on `data/routing/device`: start on the Turnpike with `exclude_tolls` 93.9 km with tolls; Carlisle to a Turnpike point "No path could be found for input" with Fastest and Shortest. Also found: Erie to an I-90 point fails the same way with `exclude_highways`, and Shortest had no fallback for it | **Fixed** in the contract: 2.11 "The ends of a trip", presets format 2 (`avoid_tolls` gets `fallback_costing_options`, Shortest's `option_fallbacks` became `option_fallback_when`), composition rules 2 and 6, C6, decision 6.5, N's recordings and tests; owner question 6.38 #5 |
| R2 | A fresh checkout's `test` is red: one search test fails without the search databases | Pre-existing test (`TestEnv.requireSearchDb()` inside `assertThrows`). `:core:search:test` without the variables: 23 run, 1 failed (this one), 15 skipped; with them: 23 run, 0 failed, 1 skipped | **Fixed in the document**: 5.3 says plainly that `test` of every module needs the two variables until S's fix; section 0 and track 0's row say the same. The test itself stays S's (5.1), since Round 0 changes existing modules only where a contract type moves |
| R3 | `:map:demo` and `:nav:ferrostar`'s androidTest APK keep INTERNET | Seen in the merged manifests of the reviewer's build, and also in `:map:layers`'s androidTest APK (MapLibre) | **Fixed**: `tools:node="remove"` for INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE in `map/demo/src/main/AndroidManifest.xml` and new `src/androidTest/AndroidManifest.xml` files in `:map:layers` and `:nav:ferrostar`; section 4; every merged manifest of the final build checked (appendix B) |
| R4 | A's licence gate names `:app`'s classpath, which has no Ferrostar, Valhalla or SQLite until I wires it | True: `:app` depends on `:core:packs`, `:core:settings`, `:core:location`, `:ui:theme` and MapLibre. Also found: `desugar_jdk_libs` is on the `coreLibraryDesugaring` configuration, not on `releaseRuntimeClasspath` | **Fixed**: A checks the union of the modules `:app` will depend on, both configurations, and the AARs' `jni/` libraries; the `:app` check stays I's final gate |
| R5 | The licence allow-list has two entries; the owner accepted one | True. JNA's POM (5.18.1) declares LGPL-2.1-or-later or Apache-2.0 | **Fixed**: one-entry allow-list (`desugar_jdk_libs`); JNA recorded under Apache-2.0, not an exception; decision 6.34; owner question 6.38 #6 |
| R6 | `check_contracts.py` does not freeze `:core:api` against a new public type in a new or unmarked file | Reproduced on a scratch copy. Also found: it reported every contract file whose stubs a track had filled as different, and `--write` would have overwritten that work | **Fixed**: the frozen code is checked against `tools/frozen-files.sha256` (5.2); a filled stub matches, `--exact` for byte equality; `--write` skips filled files. Tried on a scratch copy: a new file in `:core:api`, an edited unquoted frozen file and a frozen build-file edit are reported; filled stubs pass; a changed signature next to filled stubs is reported |
| R7 | `git status` shows more than Round 0's files | True: the uncommitted rename and another engineer's `android/scripts/phone/` are in the working tree | **Not a code change**: the orchestrator commits the rename first, then Round 0, without `android/scripts/phone/` |

**Review of Rounds G0 and SP0** (lens "parallel-ready", findings PR1-PR8, and lens "faithful",
findings FA1-FA4; both "sound with fixes"). Each finding was checked against the documents and the
tree before anything was changed (2026-11-16).

| # | Finding | Checked | Outcome |
|---|---|---|---|
| PR1 (major) | M and S cannot keep `check_contracts.py` green: three companion excerpts contain `TODO` stubs and excerpts were matched exactly | True: the excerpts of `Scene.kt`, `SearchUi.kt` (`SearchFeature`) and `SearchScreens.kt` hold stubs; the script matched an excerpt as one exact run, although section 2 says a stub matches any body | **Fixed**: a stub in an excerpt matches any text, as in a `FILE` block; `--exact` still reports it; trials on a scratch copy (appendix B); `--freeze` |
| PR2 (major) | The memory budget of 5.3 does not add up; idle daemons live 3 hours; 32 workers on 12 threads | True: a daemon holds 3.3-3.9 GB, not 2.5; the clean build peaked at 6.5 GB of Java processes; no idle timeout was set anywhere | **Fixed** in 5.3: `-Dorg.gradle.daemon.idletimeout=600000` (tried: a daemon started with 60000 stopped after one idle minute), at most two Gradle builds at once beside at most three emulators (the lead's number, kept), atomic build slots under `data/locks/`, the measured figures. Workers stay 4 (the project's cap); two builds use 8 of 12 threads |
| PR3 (major) | One broken build script blocks every track's Gradle run | Gradle evaluates every project's script by default: without the flag `:core:units:compileKotlin` evaluated 36 projects, with `--configure-on-demand` 3 (root, `:core`, `:core:units`); the reviewer's scratch trial with a broken `:feature:drive` script failed without the flag and passed with it | **Fixed**: `--configure-on-demand` in the 5.3 command, tasks named by project path, a build file written in one go; the full build and a track-sized build pass with the flag |
| PR4 | P's sprite and contact-sheet rebuild writes where the data rule forbids | True: `build_sprites.py` writes `data/art/sprites/` and `data/art/build/<flavour>/`, `build_sheet.py` `assets/sheet/` and `data/art/route/`, `build_art.py` also runs M's markers step (and `build_markers.py` writes `data/art/build/markers/` too) | **Fixed**: 5.2 Data gives P `data/art/` except M's `markers/` and `build/markers/`, plus the git-ignored `assets/sheet/`; 5.1 P and saved places 6.1 name the steps P runs one by one |
| PR5 | `:routing:demo` → `:routing:packs` (N compiles P's module) is missing from the cross-track edges | True: `androidTestImplementation(project(":routing:packs"))` | **Fixed** in 1.1 |
| PR6 | Emulator lock racy, no `data/locks`, no per-track AVD | True: "count before starting" can let two start; only `beamline_api36`, `beamline_routing`, `beamline_search` exist | **Fixed**: three emulator slots taken with `mkdir`, the AVD recipe with `-n beamline_<key>` and `-port` in 5.3; tracks make their own AVD (none made here: no emulator is started in this step) |
| PR7 | No defined way for `:feature:search` to read the sprites, no stand-in before P's first wave | True: `:feature:search` has no assets entry; the three saved icons are not in the app's sprites yet | **Fixed**: 2.6 and 5.1 S (S adds the app's assets to its androidTest source set, as `:map:demo` does for main), stand-ins in S's tests and M's demo until P's announcement (5.1 M, saved places 6.1) |
| PR8 | (a) A's notices path reads as the frozen `assets/`; (b) downloads 10.3 serves `data/r3-g/site`, 7.1 builds `data/catalog/site` | (a) True. (b) Not an error: 10.3's site holds version-2 packs (`--manifest-versions 2,3`) for G's demo, kept apart from the version-3 site I uses; the original design had it so | (a) **Fixed** in 5.1 A (`android/feature/about/src/main/assets/notices/`). (b) **Kept, made explicit** in downloads 10.3 |
| FA1 (major) | PV7 was relaxed ("clear data only while the owner has saved no place") and the documents contradicted each other | True: the download design cleared app data on the phone, the saved-places design never does; the merge added an exception nobody can check | **Fixed** by the lead's rule (the stricter privacy rule wins): never clear data or uninstall on the phone, in PV7 item 7, downloads 10.4, 5.2 and section 8, as section 4 and 6.68 already said; journey 1 and the first-run screen are proved on the emulator only; on the phone the download journeys run from the States screen after removing a state |
| FA2 | Stale "question 2" in the saved-places companion | True (section 11) | **Fixed**: "question 12.2" |
| FA3 | Nowhere says where the map chip goes while a trip phase hides the pill | True | **Fixed**: the chip shows only while the pill shows (downloads 1.5 and 9.4, SP2, S8, 5.4 item 4); the download's notification carries the job meanwhile |
| FA4 | Main 5.4 and the I row omit the rewrite of `:app`'s log lines | True; the companion's PV2 item 3 and 6.1 assign it to I | **Fixed**: 5.4 item 14 names the lines and the guard; the I row of 5.1 names the rewrite |

## Appendix B. Round 0, Round G0, Round SP0 and review record

What Round 0 created or changed (all compiled with the build below):

- New modules with build files and contract files: `:core:api`, `:core:units`, `:ui:theme`,
  `:core:installed`, `:nav:session`, `:nav:ferrostar`, `:map:scene`, `:map:layers`, `:map:demo`,
  `:feature:search`, `:feature:drive`, `:feature:about`, `:demo:about`; all included in
  `android/settings.gradle.kts`.
- Existing modules: `:core:settings` (four settings, `AppStateStore`), `:core:location`
  (`SharedPositionSource`), `:core:search` (`RecentPlaces` stub; depends on `:core:api`),
  `:core:packs` (depends on `:core:api`), `:app` (theme moved out to `:ui:theme`).
- Data files: `packbuilder/routing/units.json`, `guidance.json`, `camera.json`,
  `packbuilder/search/search_app.json`; copies made by `sync_data.py` (now with `--only`).
- `android/gradle/libs.versions.toml`: core-splashscreen 1.2.0, lifecycle-service (the lifecycle
  version), androidx.test rules 1.7.0, Compose ui-tooling, ui-tooling-preview, ui-test-junit4 and
  ui-test-manifest (Compose BOM 2026.09.00, which pins them at 1.12.1); versions checked at Google
  Maven on 2026-11-15.
- `android/build.gradle.kts`: the isolation hook (5.3). `tools/check_contracts.py`.

How it was checked (2026-11-15, Round 0's own run):

- **Baseline first.** With only the isolation hook added, `./gradlew assembleDebug test` of the
  renamed tree in an empty build folder: every module assembled (`:routing:demo` included, finding F1); the only
  failure was `RulesAndVersionsTest.missingOrForeignVersionsAreRefused`, which fails when no search
  database is configured (S fixes it, 5.1). Every later run sets `BEAMLINE_SEARCH_DB` and
  `BEAMLINE_ADDRESSES_DB` to the PA search and slim address files under `data/`.
- **Final build.** From `android/`, with the isolation hook (key `0`), a new build root and a new
  project cache, without the build cache, `ANDROID_SERIAL` pointing at no device:
  `./gradlew assembleDebug assembleDebugAndroidTest test --no-build-cache`: BUILD SUCCESSFUL,
  906 tasks, all executed. It produced the debug APKs of `:app`, `:map:demo`, `:demo:about`,
  `:demo:search` and `:routing:demo`, the AARs of every Android library, the jars of every JVM
  module, and the androidTest APKs. JVM tests: 157 run, 0 failed, 1 skipped (`SqlTimingProbe`,
  opt-in): `:core:api` 3, `:core:units` 7, `:core:settings` 9, `:core:location` 10,
  `:core:packs` 52, `:core:search` 23, `:routing:core` 24, `:routing:banner` 15, `:routing:packs`
  12, `:routing:valhalla` 2. The compiler warnings (14) are all in files Round 0 did not touch.
- **Document equals code.** `python tools/check_contracts.py`: 23 contract files, 2 excerpts and 4
  data files the same as this document, byte for byte (LF), and no contract file in the tree that
  the document does not quote. The code blocks of sections 2 and 3 were pasted from the tree after
  the build, and the check was run again on the final text. A changed line and an unquoted
  contract file were both reported when tried.
- **Copies in sync.** `python android/routing/scripts/sync_data.py --check`: every copy the same.
- No device was used.

After the review of Round 0 (findings R1-R7 in appendix A), the same day:

- **Changed:** `map/demo/src/main/AndroidManifest.xml` and new
  `map/layers/src/androidTest/AndroidManifest.xml`, `nav/ferrostar/src/androidTest/AndroidManifest.xml`
  (network permissions removed); `tools/check_contracts.py` (frozen list, filled stubs, `--exact`,
  `--freeze`, a safe `--write`) and the new `tools/frozen-files.sha256` (36 files); this document
  (2.11, C6, section 4, 5.1 N and A, 5.2, 5.3, 6.5, 6.34, 6.38, appendices); the `tools/` row of
  `README.md`. No Kotlin source and no data file changed.
- **Valhalla.** pyvalhalla 3.9.1 on `data/routing/device`, the points of 2.11: start on the
  Turnpike with `exclude_tolls` 93.9 km with tolls; Carlisle to the Turnpike point and Erie to the
  I-90 point "No path could be found for input" under the exclusions, Fastest and Shortest; the
  soft forms route there (Turnpike point: Fastest 141.5 km, Shortest 123.7 km; I-90 point:
  Shortest 13.0 km).
- **Final build.** As above with key `0/fix` (new build root and project cache, no build cache,
  the two search databases set, `ANDROID_SERIAL` pointing at no device): BUILD SUCCESSFUL in
  2 min 45 s, 906 tasks, all executed. JVM tests: 157 run, 0 failed, 1 skipped (`SqlTimingProbe`),
  the same per module as above. All 49 merged manifests of the build (every variant of every
  module, test APKs included) declare none of INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE.
- **Licence classpaths** (resolution only): `:app` `releaseRuntimeClasspath` has MapLibre 13.7.1
  and no Ferrostar, JNA, valhalla-mobile or sqlite-bundled; `:nav:ferrostar`
  `releaseRuntimeClasspath` has Ferrostar core 0.59.0 and JNA 5.18.1 (an AAR); its
  `coreLibraryDesugaring` has `desugar_jdk_libs` 2.1.5.
- **Document equals code.** `python tools/check_contracts.py` and `--exact`: 23 contract files,
  2 excerpts, 4 data files the same; 36 frozen files unchanged. On a scratch copy of the tree a
  new file in `:core:api`, an edited unquoted frozen file and an edited frozen build file were each
  reported; filled stubs passed (and failed with `--exact`); a changed signature next to filled
  stubs was reported; `--write` left a filled file alone; CRLF line endings passed.
- No device was used.

**Round G0, set-up of in-app downloads** (the night of 2026-11-15 to 16; the orchestrator's step,
with the orchestrator's authority over frozen files):

- **Adopted:** the reviewed download design as the companion contract
  `docs/architecture-downloads.md` (its decisions G1-G15 are 6.39-6.53 here, its owner questions
  7-9 of 6.38); this document changed in its header and sections 0, 1, 1.1, 1.2, 2 (block rules),
  2.2, 2.10-2.12, the new 2.13, 4, 5.1-5.4, 6, 6.38, 7 and 8. Reports renumbered (P 12 to I 19, G
  18); track G's emulator `beamline_g` on port 5574.
- **Created:** `:core:download`, `:feature:states`, `:demo:states` (build files, the 13 contract
  files of the companion with `TODO("track G")` bodies, an empty library manifest, the demo's
  placeholder manifest and its debug manifest); `packbuilder/catalog/download_rules.json` and its
  copy in `:core:download` (`sync_data.py` gained the pair); `android/app/src/debug/AndroidManifest.xml`;
  `core/api/.../api/InstallerHandoffTest.kt`.
- **Changed:** `android/settings.gradle.kts` (three includes), `android/gradle/libs.versions.toml`
  (`okhttp` 5.3.2), `InstalledData.kt` (the installer hand-off of 2.2, additive, with defaults),
  `android/app/build.gradle.kts` (catalog address, `CATALOG_URL`, the debug-only network security
  config), `android/app/src/main/AndroidManifest.xml` (no longer removes `INTERNET` and
  `ACCESS_NETWORK_STATE`; still removes `ACCESS_WIFI_STATE`), `android/routing/scripts/sync_data.py`,
  `tools/check_contracts.py` (reads this document and every `docs/architecture-*.md`, reports a
  whole file quoted twice), `tools/frozen-files.sha256` (37 files). `android/local.properties`
  (not committed) got the emulator address `http://10.0.2.2:8765/catalog/catalog.json`.
- **Where the design was changed** (each recorded in the companion where its text changed): the
  build-script snippet imports `java.io.StringReader`, `java.net.URI` and `java.util.Properties`
  because AGP 9.5.1's `java` extension accessor shadows the `java` package inside the script;
  `:feature:states` declares `:core:settings`, `:core:units` and lifecycle-service with `api`
  (they are in its public signatures, rule of 1.1); `download_rules.json` has its source in
  `packbuilder/catalog/` and a copy, as every rule file; the stricter logging rule of the
  saved-places design (no exception message, server answer or installer's words in a log line)
  replaced "the installer's own words ... also logged" in the companion's texts and in the KDoc of
  `DownloadJob.detail`. Found in the build: OkHttp's Android AAR declares `INTERNET`, so
  `:demo:states` and `:feature:states`'s test APK hold it already (section 4 names them as the
  downloader's hosts).
- **Final build.** From `android/`, key `0b` (new build root and project cache, no build cache,
  the two search databases set, `ANDROID_SERIAL` pointing at no device):
  `./gradlew assembleDebug assembleDebugAndroidTest test --no-build-cache`: BUILD SUCCESSFUL in
  2 min 23 s, 1,044 tasks, all executed. JVM tests: 160 run, 0 failed, 1 skipped
  (`SqlTimingProbe`): `:core:api` 6 (3 new), the other modules as in Round 0; `:core:download` and
  `:feature:states` have no tests yet (G writes them). Compiler warnings: 14, as in Round 0, all in files neither round wrote, none in a file
  Round G0 wrote.
- **Manifests.** All 30 merged manifests of the build read, and the release variants of `:app` and `:demo:states` merged afterwards (`processReleaseMainManifest`, no device task): `:app` debug declares
  `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `INTERNET`, `ACCESS_NETWORK_STATE` (MapLibre's,
  no longer removed) and androidx core's own permission, no `ACCESS_WIFI_STATE`, the debug network
  security config (cleartext to `10.0.2.2`, `127.0.0.1`, `localhost` only), no
  `usesCleartextTraffic`; `:app` release the same permissions with no network security config and an empty `CATALOG_URL` (an http address never reaches a release build); `:demo:states` (debug) and `:feature:states`'s test APK hold
  `INTERNET` from OkHttp's AAR; every other demo and test APK declares none of `INTERNET`,
  `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`. The rest of section 4's list arrives with I's wiring.
- **Document equals code.** `python tools/check_contracts.py` and `--exact`: 36 contract files, 6
  excerpts, 5 data files in 2 documents the same; 37 frozen files unchanged. On scratch copies of
  the tree 19 trials passed: Round 0's (a new file in `:core:api`, an edited unquoted frozen file,
  an edited frozen build file, filled stubs passing and failing with `--exact`, a changed signature
  next to filled stubs, `--write` leaving a filled file alone, CRLF) on the companion's files, and
  new ones: an edited frozen test, a changed companion block and data block reported with the
  companion's name and line, the same file quoted in two documents, a deleted companion leaving its
  files unquoted, a third companion read and written.
- **Copies in sync.** `sync_data.py --check`, `sync_assets.py check`, `copy_search_rules.py --check`:
  all the same. The other checks of 5.4 item 14 (`sync_art.py`, `notices.py`,
  `build_state_shapes.py`, `check_network_rule.py`) do not exist yet (M, A and G write them).
- No device was used; nothing was committed.

**Round SP0, set-up of saved places** (the night of 2026-11-15 to 16, after Round G0; the
orchestrator's step, with the orchestrator's authority over frozen files):

- **Adopted:** the reviewed saved-places design as the companion contract
  `docs/architecture-saved-places.md` (its decisions SPD1-SPD16 are 6.54-6.69 here, its owner
  questions 10-13 of 6.38). This document changed in its header and sections 0, 1, 1.2, 2 (block
  rules), 2.1, 2.4-2.6, 2.11, 2.12, the new 2.14, 3.7, 3.8 (S7 amended, S8, S9), 4 (backup, privacy,
  logging), 5.1-5.4, 6, 6.38, 7 and 8; `docs/architecture-downloads.md` where the two designs meet
  (its header, 1.5, N3, 9.4 items 3 and 4, 10.4; the companion's 6.3 lists the points).
- **Created:** `core/api/.../api/model/Saved.kt` and the test `SavedPlaceTest.kt` in the frozen
  `:core:api`; `core/search/.../search/saved/SavedPlaces.kt` and `feature/search/.../feature/search/SavedUi.kt`
  with `TODO("track S")` bodies; the copy of `search_app.json` in `:core:search`'s rules folder;
  `assets/icons/beamline/saved_home.svg`, `saved_work.svg` and `saved_place.svg` (drafts for the
  owner's approval).
- **Changed:** `Model.kt` (`PlaceSource.SAVED`, `Place.savedId`, the redacted `toString()`) and
  `MapScene.kt` (`MapScene.saved`, `savedBelowRoute`, `SavedMarker`, the doc of `Tap`), both
  additive; `Scene.kt` (M: `MapSceneMapper(savedRules)`, `scene(..., saved)`, `SavedMarkerRules`);
  `SearchUi.kt` and `SearchScreens.kt` (S); `BeamlineNavigationSession.kt` (`TripStore`'s comment
  only); `packbuilder/search/search_app.json` (`saved`) and `packbuilder/routing/camera.json`
  (`saved_places`), each with its note, and the copy of `camera.json`; `android/scripts/copy_search_rules.py`
  (the `search_app.json` row) and its `SOURCES.json`; one line of S's `RulesAndVersionsTest` (its
  list of expected copies gained `search_app.json`); `assets/icons/icons.json` (the three icons, the
  `saved` colour pairs, a `_doc` line) and `assets/README.md`; the docstring of
  `tools/check_contracts.py`; `tools/frozen-files.sha256` (39 files: `Model.kt`, `MapScene.kt` and
  the script changed, `Saved.kt` and `SavedPlaceTest.kt` added).
- **What already compiled still does.** The tree has no `when` over `PlaceSource`, `SearchMode` or
  `PlaceAction` and no use of the model `Place`'s `toString()`; `SearchFeature` and `MapSceneMapper`
  have no caller yet (I's); every new parameter but `SearchFeature`'s `saved` has a default. The first
  trial build failed in `:core:search:test` only: `RulesAndVersionsTest.ruleCopiesMatchPackbuilder`
  pins the set of rule copies, which did not hold the new one; that one line was added.
- **Final build.** From `android/`, key `0b` (Round G0's build root, project cache and Kotlin folder
  deleted first; no build cache; the two search databases set; `ANDROID_SERIAL` pointing at no
  device): `./gradlew assembleDebug assembleDebugAndroidTest test --no-build-cache`: BUILD SUCCESSFUL
  in 2 min 24 s, 1,044 tasks, all executed. JVM tests: 164 run, 0 failed, 1 skipped
  (`SqlTimingProbe`): `:core:api` 10 (4 new in `SavedPlaceTest`), the other modules as in Round G0
  (`:core:search` 23, its `RulesAndVersionsTest` now also checking the new copy). Compiler warnings:
  14, the same as in Rounds 0 and G0, none in a file Round SP0 wrote. No manifest changed in Round
  SP0 (`:app`'s backup rules are I's, 5.4 item 10).
- **Document equals code.** `python tools/check_contracts.py` and `--exact`: 39 contract files, 20
  excerpts, 5 data files in 3 documents the same; 39 frozen files unchanged. On a scratch copy of
  the tree 14 trials passed: the unchanged copy read in 3 documents; a changed signature in
  `SavedPlaces.kt` reported with the companion's name and line; a filled stub there passing, and
  failing with `--exact`; a companion excerpt that is no longer one run of its file; a changed line of
  `Model.kt` reported by this document's block, the companion's excerpt and the frozen list;
  `SavedUi.kt` quoted whole in two documents; the companion deleted, leaving `SavedPlaces.kt` and
  `SavedUi.kt` unquoted; a new file in `:core:api`; an edit of the frozen `Saved.kt`; an edited
  `search_app.json`, and an edited rule copy breaking the companion's excerpt of it; CRLF line
  endings; `--write` skipping a filled file and writing a missing one back.
  Found while checking: text added after the last `TODO` stub of a contract file (here the three
  new composables of `SearchScreens.kt`, compared with the block before it was replaced) matches
  that stub in the default mode, and only `--exact` reports it. Left as it is (the stub rule is the
  script's design); a reviewer of a track's contract files should look past the last stub.
- **Copies and assets in sync.** `sync_data.py --check`, `copy_search_rules.py --check`,
  `sync_assets.py check` and `packbuilder/map/style/check_poi_icons.py`: all pass. The sprite sheets
  are not rebuilt yet (P's first wave), so the app's style does not have the three icons yet.
- No device was used; nothing was committed.

**Review of Rounds G0 and SP0** (2026-11-16; findings PR1-PR8 and FA1-FA4 of appendix A; the
orchestrator's step, with the orchestrator's authority over frozen files):

- **Changed:** `tools/check_contracts.py` (a stub in an `EXCERPT` block matches any text unless
  `--exact`; the summary line counts filled excerpts when there are any) and
  `tools/frozen-files.sha256` (39 files; only the script changed); this document (the reading note
  before section 0, 1.1, 2 (block rules), 2.6, 3.8 S8, 5.1 P, S, M, A and the I row, 5.2 Data and
  Emulators, 5.3, 5.4 items 4 and 14, 8, appendices A and B); `docs/architecture-downloads.md` (1.5,
  the 4.3 row of 5.2 and 5.3, 9.4 item 4, 10.3, 10.4); `docs/architecture-saved-places.md` (SP2, PV7 item 7, 6.1 rows P, S and
  M, section 11). No Kotlin source, build file, manifest or data file changed.
- **Final build.** From `android/`, key `0b` (build root, project cache and Kotlin folder deleted
  first; no build cache; the two search databases set; `ANDROID_SERIAL` pointing at no device), with
  the two flags 5.3 now adds: `./gradlew ... --configure-on-demand
  -Dorg.gradle.daemon.idletimeout=600000 assembleDebug assembleDebugAndroidTest test
  --no-build-cache`: BUILD SUCCESSFUL in 2 min 41 s, 1,044 tasks, all executed. JVM tests: 164 run,
  0 failed, 1 skipped (`SqlTimingProbe`): `:core:api` 10, `:core:units` 7, `:core:settings` 9,
  `:core:location` 10, `:core:packs` 52, `:core:search` 23, `:routing:core` 24, `:routing:banner`
  15, `:routing:packs` 12, `:routing:valhalla` 2. Compiler warnings: 14, in the same files as
  before, none in a file of Round G0 or SP0.
- **Memory, measured during that build** (every 2 s, Windows private bytes): the Gradle daemon 3.3
  to 3.9 GB, each JVM test worker 0.6 to 0.85 GB with up to four at once, the Java processes 6.5 GB
  at the peak; free memory never below 18.1 GB of 31.8 (about 8 GB in use with no build). These are
  the figures of 5.3.
- **The two flags.** Without `--configure-on-demand`, `:core:units:compileKotlin` evaluated the
  build scripts of 36 projects, with it 3 (the root, `:core`, `:core:units`; configuration cache off
  for the comparison); a track-sized run (`:feature:search:assembleDebugAndroidTest :map:scene:test`
  and `:app`'s release manifest) passed with both flags. A daemon started with `--priority low
  -Dorg.gradle.daemon.idletimeout=60000` (low priority so that it could not reuse a running daemon)
  logged `idleTimeout=60000` and stopped itself after one idle minute.
- **Manifests.** All 32 merged manifests read: `:app` debug and release declare exactly
  `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_NETWORK_STATE`, `INTERNET` and androidx
  core's own permission (the state of `docs/architecture-downloads.md` 9.3 item 5; the rest of
  section 4's list arrives with I's wiring), `allowBackup="false"`, the network security config in
  debug only, no `usesCleartextTraffic`; `:demo:states` and `:feature:states`'s test APK hold
  `INTERNET` from OkHttp's AAR; every other demo and test APK declares no network permission.
- **Document equals code.** `python tools/check_contracts.py` and `--exact`: 39 contract files, 20
  excerpts, 5 data files in 3 documents the same; 39 frozen files unchanged. On a scratch copy of
  the tree 19 trials passed: Round SP0's 14, and five new ones: a filled stub in the `Scene.kt`
  excerpt passes (the file and the excerpt counted as filled) and fails with `--exact`; filled stubs
  in the `SearchUi.kt` and `SearchScreens.kt` excerpts pass and fail with `--exact`; a changed line
  next to a filled stub in an excerpt is still reported.
- **Copies and assets in sync.** `sync_data.py --check`, `copy_search_rules.py --check`,
  `sync_assets.py check` and `check_poi_icons.py`: all pass.
- No device or emulator was used, no adb command was run, and no AVD was made; nothing was
  committed.
