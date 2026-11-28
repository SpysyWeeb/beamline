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
    override val state: StateFlow<TripState> get() = core.state
    internal val core = SessionCore(positions, clock, wallClockMs, installed, holdPackChanges, engines, followers, settings,
        store, rules, presets, bannerRules, navDispatcher, routingDispatcher, doorOf, signalsIn)
    override val events: SharedFlow<TripEvent> get() = core.events
    override fun send(intent: NavIntent): Unit = core.send(intent)
    override fun close(): Unit = core.close()
}

/**
 * What survives process death during a trip (trip.json; format beamline-trip/1). [savedEpochMs]
 * is the wall-clock time of the last write: the session rewrites the file every resume.refresh_s
 * while a trip is guided, so its age says how long ago the trip was last alive (rule R1).
 * [simulated] (play test P27): the trip is a simulated drive; offered again it says so, and Resume
 * simulates it again (TripState.resumeSimulated).
 */
data class TripSnapshot(
    val savedEpochMs: Long,
    val stops: List<TripStop>,
    val routeType: String,
    val options: RouteOptions,
    val simulated: Boolean = false,
)

/** trip.json in the app's no-backup folder (getNoBackupFilesDir), written atomically (temporary file, fsync, rename). */
class TripStore(file: File) {
    fun save(snapshot: TripSnapshot): Unit = trip.save(snapshot)
    private val trip = TripFile(file)
    fun load(): TripSnapshot? = trip.load()
    fun clear(): Unit = trip.clear()
}
