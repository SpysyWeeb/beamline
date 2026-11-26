package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.nav.AnnounceStage
import io.github.spysyweeb.beamline.api.nav.END_UNDO_MS
import io.github.spysyweeb.beamline.api.nav.DriveEstimate
import io.github.spysyweeb.beamline.api.nav.BannerView
import io.github.spysyweeb.beamline.api.nav.LaneWarningView
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Guidance
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.ReplaceReason
import io.github.spysyweeb.beamline.api.nav.RouteChoice
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.RouteTypeInfo
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripProblem
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.api.nav.Vehicle
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.location.SharedPositionSource
import io.github.spysyweeb.beamline.routing.banner.BannerRules
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.model.StopEnd
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.RoutingProblem
import io.github.spysyweeb.beamline.routing.preset.Preset
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The navigation session's state machine: every rule of docs/architecture.md section 3 (rule ids in
 * the comments). Every method here runs on the session's dispatcher (one thread); engine calls go
 * to the routing dispatcher with withContext and their answers come back here, where an answer for
 * an older revision (or a dropped re-route) is ignored.
 *
 * Privacy (rule PV2): nothing here logs; no exception message is built from a place or a point.
 *
 * Robustness: an unexpected exception (a follower, the trip file, a bug) never leaves the session's
 * thread, where it would end the app in the middle of a drive. Each intent, fix and tick runs in a
 * guard that keeps the session going with the next one, and the scope's handler catches the rest;
 * both record only the exception's class name in [failures] (the tests require it empty). Expected
 * failures are handled where they happen: a trip.json that cannot be written is skipped (R1 is
 * best effort), a follower that cannot be made from a route is an ENGINE problem.
 */
internal class SessionCore(
    private val positions: SharedPositionSource,
    private val clock: Clock,
    private val wallClockMs: () -> Long,
    private val installed: kotlinx.coroutines.flow.StateFlow<InstalledSet>,
    private val holdPackChanges: (String) -> AutoCloseable?,
    engines: EngineOpener,
    private val followers: RouteFollowerFactory,
    private val settings: SettingsStore,
    private val store: TripStore,
    private val rules: GuidanceRules,
    private val presets: PresetSet,
    private val bannerRules: BannerRules,
    navDispatcher: CoroutineDispatcher,
    private val routingDispatcher: CoroutineDispatcher,
    private val doorOf: (Place, InstalledSet) -> GeoPoint? = { _, _ -> null },
    signalsIn: ((InstalledSet, Collection<Long>) -> List<GeoPoint>)? = null,
) {
    val state = MutableStateFlow(TripState(offeredTypes = presets.offered().map { RouteTypeInfo(it.id, it.label) }))
    val events = MutableSharedFlow<TripEvent>(extraBufferCapacity = 256)

    /** Class names of unexpected exceptions the session caught and survived (never a message: PV2). */
    val failures: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    private val scope = CoroutineScope(SupervisorJob() + navDispatcher + CoroutineExceptionHandler { _, e -> failures += e.javaClass.name })
    val service = EngineRouteService(engines, presets, rules, bannerRules,
        signalsIn?.let { read -> { cells: Collection<Long> -> read(installed.value, cells).map { LatLon(it.lat, it.lon) } } })

    /**
     * 2.3 "Changes at most once per accepted fix or tick, plus once per intent": inside a batch the
     * state is written to [pending] and the events wait in [pendingEvents]; the batch's end publishes
     * the state once, then the events (a screen that reacts to an event finds the state holding it).
     * Every intent, fix and tick is one batch ([guarded]), and so is each engine answer or timer:
     * the code between two suspension points. A batch never spans a suspension (that would hold
     * other units' writes back), which is why the coroutines below open one after each withContext.
     * A write outside any batch goes straight to [state].
     */
    private var batchDepth = 0
    private var pending: TripState? = null
    private val pendingEvents = ArrayList<TripEvent>()

    private inline fun <T> batch(block: () -> T): T {
        batchDepth++
        try {
            return block()
        } finally {
            if (--batchDepth == 0) flush()
        }
    }

    private fun flush() {
        pending?.let {
            pending = null
            state.value = it
        }
        if (pendingEvents.isNotEmpty()) {
            val out = pendingEvents.toList()
            pendingEvents.clear()
            out.forEach { events.tryEmit(it) }
        }
    }

    /** Runs one intent, fix or tick as one batch; an unexpected exception is recorded and the session goes on with the next one. */
    private inline fun guarded(block: () -> Unit) {
        try {
            batch(block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += e.javaClass.name
        }
    }

    @Volatile
    private var closed = false

    /** Test hook (the golden trace): called on the session thread after each fix the session handled while driving. */
    @Volatile
    var afterFix: ((Fix, TripState) -> Unit)? = null

    private var st: TripState
        get() = pending ?: state.value
        set(v) {
            if (batchDepth > 0) pending = v else state.value = v
        }

    // ------------------------------------------------------------------------------------ planning

    private class Planned(val choice: RouteChoice, val route: Route, val plan: PlanResult.Ok, val legStops: List<Int>)

    private var planned: List<Planned> = emptyList()
    private var choiceSeq = 0L
    private var planJob: Job? = null
    private var waitJob: Job? = null
    private var lastGeneration: Long? = null

    // ------------------------------------------------------------------------------------ guidance

    private class Guided(
        val route: Route,
        val geo: RouteGeometry,
        val banners: List<BannerView>,
        val follower: RouteFollower,
        val choice: RouteChoice,
        /**
         * The stop each leg ends at. Stops are found in TripState.stops by place, not by index: a
         * change during guidance (C3) shows its new stops before its route arrives, and a stop can be
         * reached meanwhile.
         */
        val legPlaces: List<Place>,
        /** The posted limit of each stretch (batch A, rule D8): from the OSRM answer's annotation.maxspeed. */
        val limits: SpeedLimits = SpeedLimits.NONE,
        /** Lanes that end in a turn (rule B9, batch C1). */
        val lanes: LaneWarnings = LaneWarnings.NONE,
        /** The traffic lights on the route (docs/next-round.md item 22). */
        val lights: RouteLights = RouteLights.NONE,
    ) {
        /** Index of the lane warning shown now (B9: once shown it stays until passed), or -1. */
        var laneShown = -1
        /** Flattened indexes of the arrival maneuvers (one per leg, in order). */
        val arrivals: List<Int> = route.maneuvers.indices.filter { route.maneuvers[it].type.isDestination }

        /** Legs whose end was reached on this route (A1, A2): a stop is reached once. */
        val reachedLegs = HashSet<Int>()
    }

    private var guided: Guided? = null
    private var hold: AutoCloseable? = null
    private var simulation: Simulation? = null
    private var collectJob: Job? = null
    private var tickJob: Job? = null
    private var orderJob: Job? = null
    /** Start or Resume waiting for the engine; it holds the pack-change hold until guidance begins (cancelled: released). */
    private var startJob: Job? = null

    /** R4: the trip that End took out of guidance, while UndoEnd may still bring it back. */
    private class EndedTrip(val stops: List<TripStop>, val routeType: String, val options: RouteOptions, val sim: Simulation?, val atMs: Long)
    private var ended: EndedTrip? = null

    // a change during guidance (C3, C4): what it asked, and the trip as the guided route had it (its undo)
    private var changeJob: Job? = null
    private var changeReason: ReplaceReason? = null
    private var changeOld: Triple<List<TripStop>, String, RouteOptions>? = null
    private var changeOldSettings: io.github.spysyweeb.beamline.settings.Settings? = null
    private var rerouteJob: Job? = null
    private var rerouteSeq = 0
    private var collectStartMs = 0L

    // fixes, speed and motion (G1, G2, M1, M2)
    private var lastUsable: Fix? = null
    private var prevUsable: Fix? = null
    private var usableCount = 0
    private var lastDeciding: Fix? = null
    private var speedMps: Double? = null
    private var motion = Motion.UNKNOWN
    private var slowSinceMs: Long? = null
    private var gps = GpsState.OFF

    // where the car is on the guided route
    private var hadProgress = false
    private var shownU = 1
    private var shownAlong = 0.0
    /** G8: how far the shown position is ahead of the car's own (0 without a lead). */
    private var leadNowM = 0.0
    private var shownPoint: LatLon? = null
    private var shownShape = 0
    private var shownStep = 0
    private var fixAlong = 0.0
    private var fixAlongMs = 0L
    private var lastFixOnRoute = false
    private var shownM: Double? = null
    private var lanesShown = false
    private val announced = HashSet<Double>()
    private var announcedNow = false
    private var vehicle: Vehicle? = null
    private var traveled: RouteCursor? = null

    // off route and re-route (O)
    private var offCount = 0
    private var offFirstMs = 0L
    private var onCount = 0
    private var startCheckPending = false
    private var lastRerouteAskMs: Long? = null
    private var rerouteAt: Long? = null
    private var outsideWaiting = false
    private var attempts = 0
    /** O5 applies to a re-route the session started (off route, O9), not to the driver's Reroute button. */
    private var rejoinAllowed = false
    /** A re-route was asked before any usable fix (Reroute right after Start): it is asked with the first one (O4). */
    private var fixWaiting = false

    // arrival, resume, problem hold
    private var destination: LatLon? = null
    private var lastSaveMs = 0L
    private var problemClearAt: Long? = null
    private var estimateJob: Job? = null
    /** Item 47: the destination a swap made of the current position (a second swap starts from the position again). */
    private var swappedPosition: Place? = null
    private var estimateAtMs = 0L

    init {
        scope.launch {
            guarded { startUp() }
            installed.collect { guarded { onInstalled(it) } }
        }
    }

    fun send(intent: NavIntent) {
        if (closed) return
        scope.launch { guarded { handle(intent) } }
    }

    /**
     * Ends the session on its own thread (the state is the session thread's only): position
     * collection, timers and engine work stop, the pack-change hold and the follower are released,
     * then the engine is closed on the routing thread (2.2 rule 3). trip.json is kept (R2).
     */
    fun close() {
        if (closed) return
        closed = true
        scope.launch {
            guarded {
                // Start or Resume waiting for the engine gives its hold back when cancelled (or, when its
                // answer is already on its way, when it sees [closed])
                startJob?.cancel()
                stopDriving()
                planJob?.cancel()
                waitJob?.cancel()
                orderJob?.cancel()
            }
            withContext(NonCancellable + routingDispatcher) {
                // on the routing thread: no batch here (batches belong to the session thread)
                try {
                    service.close()
                } catch (e: Exception) {
                    failures += e.javaClass.name
                }
            }
            scope.cancel()
        }
    }

    /** An event; inside a batch it follows the batch's state change. */
    private fun emit(e: TripEvent) {
        if (batchDepth > 0) pendingEvents += e else events.tryEmit(e)
    }

    // ================================================================================== start-up (R2)

    private fun startUp() {
        val snap = store.load() ?: return
        // R2: offered when wallClockMs() - savedEpochMs <= resume.max_age_s. A negative age (the wall
        // clock stepped back after the last write, say a network time correction) is within it.
        val age = wallClockMs() - snap.savedEpochMs
        if (age <= (rules.resumeMaxAgeS * 1000).roundToLong()) {
            // a type presets.json no longer has (Shortest left in batch D) is the default type (Fastest)
            val type = snap.routeType.takeIf { id -> presets.routeTypes.any { it.id == id } } ?: presets.default.routeType
            st = st.copy(phase = Phase.RESUME_OFFERED, stops = snap.stops, routeType = type, options = snap.options,
                revision = st.revision + 1)
        } else {
            clearTrip()
        }
    }

    // ================================================================================== intents

    private fun handle(intent: NavIntent) {
        // R4: End can be taken back only by the very next intent
        val endedBefore = ended
        ended = null
        when (intent) {
            is NavIntent.PlanTo -> planTo(intent)
            is NavIntent.Estimate -> estimate(intent.place)
            is NavIntent.SetOrigin -> if (st.phase == Phase.PLANNING || st.phase == Phase.CHOOSING) {
                st = st.copy(origin = intent.origin)
                replanBeforeGuidance()
            }
            is NavIntent.AddStop -> addStop(intent)
            is NavIntent.RemoveStop -> changeStops { stops ->
                val i = intent.index
                if (i !in stops.indices || stops[i].reached) null else stops.toMutableList().apply { removeAt(i) }
            }
            is NavIntent.MoveStop -> changeStops { stops ->
                val (from, to) = intent.from to intent.to
                val reached = stops.count { it.reached }
                if (from !in stops.indices || to !in stops.indices || from < reached || to < reached || from == to) null
                else stops.toMutableList().apply { add(to, removeAt(from)) }
            }
            NavIntent.OptimizeStopOrder -> optimizeStopOrder()
            NavIntent.SwapEnds -> swapEnds()
            is NavIntent.SetRouteType -> setRouteType(intent.typeId)
            is NavIntent.SetOptions -> setOptions(intent.options)
            is NavIntent.Choose -> choose(intent.choiceId)
            is NavIntent.Start -> start(intent.simulation)
            NavIntent.Reroute -> if (st.phase == Phase.GUIDING || st.phase == Phase.REROUTING) {
                if (st.phase == Phase.GUIDING) rejoinAllowed = false
                attempts = 0
                rerouteAt = null
                requestReroute(user = true)
            }
            NavIntent.Retry -> retry()
            NavIntent.End -> end()
            NavIntent.UndoEnd -> endedBefore?.let(::undoEnd)
            NavIntent.Done -> if (st.phase == Phase.ARRIVED) finishGuidance()
            NavIntent.Resume -> if (st.phase == Phase.RESUME_OFFERED) resume(Phase.RESUME_OFFERED, null)
            NavIntent.DiscardResume -> if (st.phase == Phase.RESUME_OFFERED) {
                clearTrip()
                toIdle()
            }
        }
    }

    /**
     * Item 47: a drive time for the place window's Directions. IDLE only (a trip's own times are on
     * its cards), from an acceptable fix, inside the installed routing, with the default route type
     * and the user's options; the same place asked again within [ESTIMATE_FRESH_MS] is not asked
     * again. Its answer is dropped when a trip has begun meanwhile.
     */
    private fun estimate(place: Place) {
        if (st.phase != Phase.IDLE) return
        val at = GeoPoint(place.point.lat, place.point.lon)
        val now = clock.elapsedMs()
        if (st.estimate?.point == at && now - estimateAtMs < ESTIMATE_FRESH_MS) return
        val fix = acceptableOrigin(positions.latest.value) ?: return
        val inst = installed.value
        val set = inst.routing ?: return
        if (!set.covers(at) || !set.covers(GeoPoint(fix.lat, fix.lon))) return
        val s = settings.settings.value
        val preset = presets.compose(presets.default.routeType, optionIds(RouteOptions(s.avoidHighways, s.avoidTolls)))
        val stops = listOf(stopFromFix(fix), placeStop(TripStop(place)))
        val rev = st.revision
        estimateJob?.cancel()
        estimateJob = scope.launch {
            val r = withContext(routingDispatcher) { service.plan(inst.generation, set, stops, preset) }
            batch {
                if (rev != st.revision || st.phase != Phase.IDLE) return@batch
                estimateAtMs = clock.elapsedMs()
                st = st.copy(estimate = (r as? PlanResult.Ok)?.let { DriveEstimate(at, it.route.timeS, it.route.lengthM) })
            }
        }
    }

    /**
     * Item 47: the trip the other way round before guidance ([NavIntent.SwapEnds]). A trip from the
     * current position ends at that position as it is now ([swappedPosition], so a second swap starts
     * from the current position again); the stops between are reversed; every type is planned again.
     */
    private fun swapEnds() {
        if (st.phase != Phase.PLANNING && st.phase != Phase.CHOOSING) return
        if (st.stops.isEmpty() || st.stops.any { it.reached }) return
        val last = st.stops.last().place
        val between = st.stops.dropLast(1).reversed()
        val newOrigin: Origin
        val newEnd: Place
        when (val o = st.origin) {
            Origin.CurrentPosition -> {
                val fix = acceptableOrigin(positions.latest.value)
                if (fix == null) {
                    setProblem(TripProblem(ProblemKind.NO_POSITION, NO_POSITION_TEXT, true), hold = true)
                    return
                }
                newOrigin = Origin.FromPlace(last)
                newEnd = Place(YOUR_POSITION, GeoPoint(fix.lat, fix.lon), PlaceSource.MAP_PIN)
            }
            is Origin.FromPlace -> {
                newOrigin = if (last == swappedPosition) Origin.CurrentPosition else Origin.FromPlace(last)
                newEnd = o.place
            }
        }
        swappedPosition = newEnd.takeIf { st.origin == Origin.CurrentPosition }
        st = st.copy(origin = newOrigin, stops = between + TripStop(newEnd))
        replanBeforeGuidance()
    }

    private fun planTo(intent: NavIntent.PlanTo) {
        when (st.phase) {
            Phase.GUIDING, Phase.REROUTING -> return // the screens send End first (rule S7)
            Phase.ARRIVED -> finishGuidance()
            Phase.RESUME_OFFERED -> {
                startJob?.cancel() // a Resume still waiting gives its hold back
                clearTrip()
            }
            else -> Unit
        }
        newTrip(listOf(TripStop(intent.place)))
    }

    /** A trip from the current position (P1) with the route type from settings (P4) and the options from settings. */
    private fun newTrip(stops: List<TripStop>) {
        planJob?.cancel()
        val s = settings.settings.value
        val type = s.routeType.takeIf { id -> presets.offered().any { it.id == id } } ?: presets.default.routeType
        // a stored type that is not offered (Eco, or Shortest, which left in batch D) is replaced in settings too
        if (type != s.routeType) settings.update { it.copy(routeType = type) }
        planned = emptyList()
        st = st.copy(phase = Phase.PLANNING, revision = st.revision + 1, origin = Origin.CurrentPosition, stops = stops,
            routeType = type, options = RouteOptions(s.avoidHighways, s.avoidTolls), choices = emptyList(),
            selectedChoiceId = null, guidance = null, problem = null, replanning = false)
        schedulePlan()
    }

    private fun addStop(intent: NavIntent.AddStop) {
        if (st.phase == Phase.IDLE) {
            newTrip(listOf(TripStop(intent.place)))
            return
        }
        changeStops { stops ->
            val reached = stops.count { it.reached }
            val at = (intent.index ?: if (stops.size > reached) stops.size - 1 else stops.size).coerceIn(reached, stops.size)
            stops.toMutableList().apply { add(at, TripStop(intent.place)) }
        }
    }

    /**
     * A change of the stops (C1): refused for reached stops and above planning.max_stops
     * (TOO_MANY_STOPS); before guidance a re-plan of every offered type (C2), during guidance a
     * re-plan from the car with the trip's type (C3).
     */
    private fun changeStops(change: (List<TripStop>) -> List<TripStop>?) {
        val phase = st.phase
        if (phase != Phase.PLANNING && phase != Phase.CHOOSING && phase != Phase.GUIDING && phase != Phase.REROUTING) return
        val next = change(st.stops) ?: return
        if (next.size > rules.maxStops) {
            setProblem(TripProblem(ProblemKind.TOO_MANY_STOPS, "A trip can have up to ${rules.maxStops} stops.", false), hold = true)
            return
        }
        if (next.none { !it.reached }) {
            // no destination left
            if (phase == Phase.PLANNING || phase == Phase.CHOOSING) end()
            return
        }
        if (phase == Phase.PLANNING || phase == Phase.CHOOSING) {
            st = st.copy(stops = next)
            replanBeforeGuidance()
        } else {
            changeDuringGuidance(next, st.routeType, st.options, ReplaceReason.STOPS_CHANGED)
        }
    }

    private fun setRouteType(typeId: String) {
        if (presets.offered().none { it.id == typeId }) return
        when (st.phase) {
            Phase.IDLE, Phase.RESUME_OFFERED -> settings.update { it.copy(routeType = typeId) }
            Phase.PLANNING, Phase.CHOOSING -> {
                // every offered type is planned already (P2): select the card holding it (P4, C7)
                settings.update { it.copy(routeType = typeId) }
                val holding = st.choices.firstOrNull { typeId in it.typeIds }
                st = when {
                    holding != null -> st.copy(routeType = typeId, selectedChoiceId = holding.id)
                    // its answer may still come; the last answer settles the type (publishChoices, C7)
                    st.phase == Phase.PLANNING -> st.copy(routeType = typeId)
                    // that type found no route: the trip's type stays the selected card's (P4, C7)
                    else -> st
                }
            }
            Phase.GUIDING, Phase.REROUTING -> if (typeId != st.routeType) {
                // the change first: it keeps the settings as they were, for its undo (C3)
                changeDuringGuidance(st.stops, typeId, st.options, ReplaceReason.OPTIONS_CHANGED)
                settings.update { it.copy(routeType = typeId) }
            }
            Phase.ARRIVED -> Unit
        }
    }

    private fun setOptions(options: RouteOptions) {
        when (st.phase) {
            Phase.IDLE, Phase.RESUME_OFFERED -> settings.update { it.copy(avoidHighways = options.avoidHighways, avoidTolls = options.avoidTolls) }
            Phase.PLANNING, Phase.CHOOSING -> if (options != st.options) {
                settings.update { it.copy(avoidHighways = options.avoidHighways, avoidTolls = options.avoidTolls) }
                st = st.copy(options = options)
                replanBeforeGuidance()
            }
            Phase.GUIDING, Phase.REROUTING -> if (options != st.options) {
                // the change first: it keeps the settings as they were, for its undo (C3)
                changeDuringGuidance(st.stops, st.routeType, options, ReplaceReason.OPTIONS_CHANGED)
                settings.update { it.copy(avoidHighways = options.avoidHighways, avoidTolls = options.avoidTolls) }
            }
            Phase.ARRIVED -> Unit
        }
    }

    /** C7: the card's type follows the choice (kept when it is one of the card's types), written to settings. */
    private fun choose(id: Long) {
        if (st.phase != Phase.CHOOSING && st.phase != Phase.PLANNING) return
        val c = st.choices.firstOrNull { it.id == id } ?: return
        val type = if (st.routeType in c.typeIds) st.routeType else c.typeIds.first()
        if (type != settings.settings.value.routeType) settings.update { it.copy(routeType = type) }
        st = st.copy(selectedChoiceId = id, routeType = type)
    }

    private fun retry() {
        when (st.phase) {
            Phase.PLANNING, Phase.CHOOSING -> replanBeforeGuidance()
            Phase.REROUTING -> {
                attempts = 0
                rerouteAt = null
                requestReroute(user = true)
            }
            Phase.RESUME_OFFERED -> resume(Phase.RESUME_OFFERED, null)
            else -> Unit
        }
    }

    private fun end() {
        when (st.phase) {
            Phase.PLANNING, Phase.CHOOSING -> {
                planJob?.cancel()
                toIdle()
            }
            Phase.GUIDING, Phase.REROUTING -> {
                val trip = EndedTrip(st.stops, st.routeType, st.options, simulation, clock.elapsedMs())
                finishGuidance()
                ended = trip
            }
            Phase.ARRIVED -> finishGuidance()
            else -> Unit
        }
    }

    /**
     * R4: UndoEnd within [END_UNDO_MS] of End (and before any other intent): the trip's stops not
     * reached, its route type and options re-planned from the current position, then guidance
     * (simulated again when it was). The session stays IDLE while it plans; when that plan fails the
     * trip is offered as after a restart (RESUME_OFFERED with the problem, so Retry and Discard work).
     */
    private fun undoEnd(e: EndedTrip) {
        if (st.phase != Phase.IDLE || clock.elapsedMs() - e.atMs > END_UNDO_MS) return
        st = st.copy(stops = e.stops, routeType = e.routeType, options = e.options)
        resume(Phase.IDLE, e.sim)
    }

    private fun toIdle() {
        planJob?.cancel()
        waitJob?.cancel()
        orderJob?.cancel()
        startJob?.cancel() // End or DiscardResume while Start or Resume waits: its hold is given back now
        planned = emptyList()
        st = TripState(revision = st.revision + 1, offeredTypes = st.offeredTypes, routeType = st.routeType, options = st.options)
    }

    /** End of a trip that was guided (End, Done, or the car leaving the destination, A3): IDLE, hold released, trip.json deleted (R1). */
    private fun finishGuidance() {
        stopDriving()
        clearTrip()
        toIdle()
        emit(TripEvent.GuidanceEnded)
    }

    // ================================================================================== planning (P, C2, C5)

    private fun optionIds(o: RouteOptions): Set<String> = buildSet {
        if (o.avoidHighways) add("avoid_highways")
        if (o.avoidTolls) add("avoid_tolls")
    }.filterTo(HashSet()) { id -> presets.options.any { it.id == id } }

    private fun replanBeforeGuidance() {
        st = st.copy(phase = Phase.PLANNING, revision = st.revision + 1, problem = null)
        schedulePlan()
    }

    /** C2: plans after planning.coalesce_ms without a further change; a newer revision cancels this one. */
    private fun schedulePlan() {
        planJob?.cancel()
        // a plan that waited for a fix ends its wait here, in this change (gps back to OFF)
        if (waitJob != null) {
            waitJob?.cancel()
            waitJob = null
            endOriginWait()
        }
        val rev = st.revision
        planJob = scope.launch {
            delay(rules.planCoalesceMs)
            if (rev == st.revision) runPlanning(rev)
        }
    }

    /** The origin of a plan now (P1): the chosen place, or an acceptable fix; null = wait for a fix ([waitForOriginFix]). */
    private fun originNow(): Stop? = when (val o = st.origin) {
        is Origin.FromPlace -> stopFrom(LatLon(o.place.point.lat, o.place.point.lon), o.headingDeg)
        Origin.CurrentPosition -> acceptableOrigin(positions.latest.value)?.let(::stopFromFix)
    }

    /**
     * P1: collects positions and waits up to planning.wait_for_fix_s for an acceptable origin fix
     * (gps WAITING meanwhile); null when none came. The caller ends the wait with [originAfterWait]
     * in its own batch; a newer revision ends it in [schedulePlan].
     */
    private suspend fun waitForOriginFix(): Fix? {
        waitJob?.cancel()
        val collecting = scope.launch { positions.fixes().collect { } }
        waitJob = collecting
        batch { if (st.phase == Phase.PLANNING || st.phase == Phase.RESUME_OFFERED) st = st.copy(gps = GpsState.WAITING) }
        try {
            return withTimeoutOrNull((rules.waitForFixS * 1000).roundToLong()) {
                positions.latest.first { acceptableOrigin(it) != null }
            }
        } finally {
            collecting.cancel()
            if (waitJob === collecting) waitJob = null
        }
    }

    /** The wait for an origin fix is over: gps back to OFF before guidance. */
    private fun endOriginWait() {
        if (st.phase == Phase.PLANNING || st.phase == Phase.CHOOSING || st.phase == Phase.RESUME_OFFERED) st = st.copy(gps = GpsState.OFF)
    }

    /** After [waitForOriginFix], inside the caller's batch: the fix as the origin, or NO_POSITION (P1); null also for an older revision. */
    private fun originAfterWait(rev: Long, fix: Fix?): Stop? {
        // an older revision's wait leaves gps alone: what made the revision newer has set it (schedulePlan, toIdle, Start)
        if (rev != st.revision) return null
        endOriginWait()
        if (fix == null) {
            // R4: an undone End (IDLE) without a position is offered as after a restart
            st = st.copy(phase = if (st.phase == Phase.RESUME_OFFERED || st.phase == Phase.IDLE) Phase.RESUME_OFFERED else Phase.CHOOSING,
                problem = TripProblem(ProblemKind.NO_POSITION, NO_POSITION_TEXT, true), replanning = false)
            return null
        }
        return stopFromFix(fix)
    }

    private fun acceptableOrigin(f: Fix?): Fix? {
        f ?: return null
        val age = clock.elapsedMs() - f.elapsedMs
        val acc = f.accuracyM ?: return null
        return f.takeIf { age <= rules.originMaxAgeS * 1000 && acc <= rules.originMaxAccuracyM }
    }

    /** P1: the current position as the origin, with its course when moving at least heading_min_speed_mps. */
    private fun stopFromFix(f: Fix): Stop {
        val moving = (f.speedMps ?: 0f) >= rules.rerouteHeadingMinSpeedMps
        return stopFrom(LatLon(f.lat, f.lon), if (moving) f.bearingDeg?.toDouble() else null)
    }

    /**
     * A planning origin (P1, Origin.FromPlace). One with a heading also gets reroute.origin_radius_m,
     * as the recorded exit 24 request has it: the heading picks the carriageway, the radius lets loki
     * find it (report 09). Coordinates are rounded to 6 decimals (0.11 m), as a phone's fix.
     */
    private fun stopFrom(p: LatLon, headingDeg: Double?): Stop {
        val h = headingDeg?.let(::heading360)
        return Stop(LatLon(RouteSimulator.round6(p.lat), RouteSimulator.round6(p.lon)), headingDeg = h, radiusM = h?.let { rules.rerouteOriginRadiusM })
    }

    /**
     * O4 (and C3, C4 "from the car"): the car as the origin of a route asked during a trip, always
     * with radiusM = reroute.origin_radius_m, and headingDeg = its course when its speed (the fix's,
     * else M1's) is at least reroute.heading_min_speed_mps.
     */
    private fun carStop(f: Fix): Stop {
        val v = f.speedMps?.toDouble() ?: speedMps ?: 0.0
        val h = if (v >= rules.rerouteHeadingMinSpeedMps) f.bearingDeg?.toDouble()?.let(::heading360) else null
        return Stop(LatLon(RouteSimulator.round6(f.lat), RouteSimulator.round6(f.lon)), headingDeg = h, radiusM = rules.rerouteOriginRadiusM)
    }

    private fun heading360(deg: Double): Int = ((deg.roundToInt() % 360) + 360) % 360

    /**
     * Batch E2 (docs/next-round.md item 19): where each place's stop ends, as the trip's plan decided it
     * ([PlanResult.Ok.ends]: its lot, its address street, the nearest street, a map point as it is). Every
     * route type of the plan, every re-route and every change during the trip send these same stops, so the
     * route always ends at the same point; a new plan (P2) decides again. In memory only: a trip resumed
     * after the process died decides again from where the car is.
     */
    private var decidedEnds: Map<Place, Stop> = emptyMap()

    /** A trip's place as the plan decided it ([decidedEnds]), else as [placeStop] (the planner decides it). */
    private fun planStop(s: TripStop) = decidedEnds[s.place] ?: placeStop(s)

    /** Keeps the ends [r] decided for [places] (the stops after the origin, in order). */
    private fun keepEnds(places: List<Place>, r: PlanResult) {
        if (r is PlanResult.Ok && r.ends.size == places.size + 1) decidedEnds = decidedEnds + places.zip(r.ends.drop(1))
    }

    /**
     * Where customers go into the trip's points of interest (docs/next-round.md item 28; [doorOf]): the
     * door found for a place and the installed data it was read from, null when it has none. A place is
     * asked once per installed generation ([learnDoors]); a trip rarely has more than a few places.
     */
    private val doors = HashMap<Place, Pair<Long, GeoPoint?>>()

    /**
     * Reads the doors of the trip's points of interest not asked yet ([doors]), on the routing thread
     * (a search-pack read of a few milliseconds each). Call before the stops of a plan are built
     * ([placeStop]); a failed read counts as no door.
     */
    private suspend fun learnDoors() {
        val inst = installed.value
        val todo = st.stops.map { it.place }.distinct().filter { StopTies.isPointOfInterest(it) && doors[it]?.first != inst.generation }
        if (todo.isEmpty()) return
        val found = withContext(routingDispatcher) { todo.map { p -> runCatching { doorOf(p, inst) }.getOrNull() } }
        if (doors.size > 64) doors.clear()
        todo.zip(found).forEach { (p, d) -> doors[p] = inst.generation to d }
    }

    /** A trip's place as a stop: tied to its address street when it names one, not tied when it is a map point, in its lot when it is a point of interest ([StopTies]), by its door when one was found ([learnDoors]). */
    private fun placeStop(s: TripStop) =
        Stop(LatLon(s.place.point.lat, s.place.point.lon), street = StopTies.street(s.place), tied = !StopTies.isMapPoint(s.place),
            pointOfInterest = StopTies.isPointOfInterest(s.place),
            door = doors[s.place]?.second?.takeIf { StopTies.isPointOfInterest(s.place) }?.let { LatLon(it.lat, it.lon) })

    private fun noRoutingProblem() = TripProblem(ProblemKind.OUTSIDE_INSTALLED_MAPS, NO_ROUTING_TEXT, true)

    /** P2-P4: every offered type with the current options, one after the other; the state after each answer. */
    private suspend fun runPlanning(rev: Long) {
        val now = originNow()
        val fix = if (now == null) waitForOriginFix() else null
        learnDoors()
        val inst = installed.value
        val set = inst.routing
        val (unreached, stops) = batch {
            val origin = now ?: originAfterWait(rev, fix) ?: return
            if (rev != st.revision) return
            if (set == null) {
                st = st.copy(phase = Phase.CHOOSING, problem = noRoutingProblem(), replanning = false)
                return
            }
            val unreached = st.stops.indices.filter { !st.stops[it].reached }
            st = st.copy(replanning = true)
            decidedEnds = emptyMap()
            unreached to (listOf(origin) + unreached.map { placeStop(st.stops[it]) })
        }
        var planStops = stops
        val opts = optionIds(st.options)
        val answers = ArrayList<Pair<String, PlanResult>>()
        var firstPublish = true
        val types = presets.offered()
        for ((i, type) in types.withIndex()) {
            val preset = presets.compose(type.id, opts)
            val r = withContext(routingDispatcher) { service.plan(inst.generation, set!!, planStops, preset) }
            // P2: the state after each answer, one change per answer (the last one also ends PLANNING)
            batch {
                if (rev != st.revision) return
                // batch E2: the first plan decides where each stop ends; the other types route to the same ends
                if (r is PlanResult.Ok && planStops === stops) {
                    keepEnds(unreached.map { st.stops[it].place }, r)
                    planStops = listOf(stops[0]) + r.ends.drop(1)
                }
                answers += type.id to r
                val final = i == types.lastIndex
                publishChoices(answers, unreached, replaceOld = firstPublish, final = final)
                firstPublish = false
                if (final) {
                    val anyOk = answers.any { it.second is PlanResult.Ok }
                    st = st.copy(phase = Phase.CHOOSING, replanning = false,
                        problem = if (anyOk) null else problemOf((answers.first().second as PlanResult.Failed).problem))
                }
            }
        }
    }

    /**
     * P3 (same route = one choice with every type id) and P4 (the selection holds the trip's type).
     * Until the [final] answer the trip's type is kept even when no choice holds it yet (its answer
     * may come later); then a type no choice holds gives way to the selected card's first (C7).
     */
    private fun publishChoices(answers: List<Pair<String, PlanResult>>, legStops: List<Int>, replaceOld: Boolean, final: Boolean) {
        val groups = ArrayList<Pair<MutableList<String>, PlanResult.Ok>>()
        for ((type, r) in answers) {
            if (r !is PlanResult.Ok) continue
            val same = groups.firstOrNull { sameRoute(it.second.route, r.route) }
            if (same != null) same.first += type else groups += mutableListOf(type) to r
        }
        val old = if (replaceOld) emptyList() else planned
        val next = groups.map { (types, r) ->
            val existing = old.firstOrNull { it.plan === r }
            if (existing != null && existing.choice.typeIds == types) existing
            else {
                val id = existing?.choice?.id ?: ++choiceSeq
                Planned(Views.choice(id, types.toList(), r.route, r.warnings.map { it.userText }, bannerRules), r.route, r, legStops)
            }
        }
        planned = next
        val choices = next.map { it.choice }
        var type = st.routeType
        val selected = choices.firstOrNull { type in it.typeIds } ?: choices.firstOrNull()
        if (final && selected != null && type !in selected.typeIds) {
            type = selected.typeIds.first()
            if (type != settings.settings.value.routeType) settings.update { it.copy(routeType = type) }
        }
        st = st.copy(choices = choices, selectedChoiceId = selected?.id, routeType = type, problem = null)
    }

    private fun sameRoute(a: Route, b: Route) = abs(a.lengthM - b.lengthM) < 1.0 && abs(a.timeS - b.timeS) < 0.5 && a.shape.size == b.shape.size

    private fun problemOf(p: RoutingProblem): TripProblem = TripProblem(
        kind = when (p) {
            is RoutingProblem.OutsideInstalledMaps -> ProblemKind.OUTSIDE_INSTALLED_MAPS
            is RoutingProblem.NoRoute -> ProblemKind.NO_ROUTE
            is RoutingProblem.NoRoadNearStop -> ProblemKind.NO_ROAD_NEAR_STOP
            is RoutingProblem.TooLong -> ProblemKind.TOO_LONG
            is RoutingProblem.TooManyStops -> ProblemKind.TOO_MANY_STOPS
            RoutingProblem.NeedTwoStops -> ProblemKind.NO_ROUTE
            is RoutingProblem.EngineFailure -> ProblemKind.ENGINE
        },
        text = p.userText,
        canRetry = true,
    )

    /** C5 and 2.2 rule 3: a new generation re-plans before guidance; in IDLE the engine is closed. */
    private fun onInstalled(set: InstalledSet) {
        val prev = lastGeneration
        lastGeneration = set.generation
        if (prev == null || prev == set.generation) return
        when (st.phase) {
            Phase.IDLE, Phase.RESUME_OFFERED -> scope.launch { withContext(routingDispatcher) { service.close() } }
            Phase.PLANNING, Phase.CHOOSING -> {
                planned = emptyList()
                st = st.copy(choices = emptyList(), selectedChoiceId = null)
                replanBeforeGuidance()
                st = st.copy(problem = TripProblem(ProblemKind.PACKS_CHANGED, PACKS_CHANGED_TEXT, false))
            }
            else -> Unit // changes are held during a trip (2.2 rule 4)
        }
        if (set.routing == null) scope.launch { withContext(routingDispatcher) { service.close() } }
    }

    // ================================================================================== stop order (C6)

    private fun optimizeStopOrder() {
        val phase = st.phase
        if (phase != Phase.PLANNING && phase != Phase.CHOOSING && phase != Phase.GUIDING && phase != Phase.REROUTING) return
        val unreached = st.stops.indices.filter { !st.stops[it].reached }
        if (unreached.size < 3) return
        val inst = installed.value
        val set = inst.routing ?: return
        val rev = st.revision
        orderJob?.cancel()
        val guiding = phase == Phase.GUIDING || phase == Phase.REROUTING
        val carPoint = if (guiding) (lastDeciding ?: lastUsable)?.let { LatLon(it.lat, it.lon) } ?: return else null
        orderJob = scope.launch {
            val now = if (guiding) null else originNow()
            val fix = if (!guiding && now == null) waitForOriginFix() else null
            val (preset, points) = batch {
                val originPoint: LatLon = carPoint ?: (now ?: originAfterWait(rev, fix))?.point ?: return@launch
                if (rev != st.revision) return@launch
                st = st.copy(replanning = true)
                presets.compose(st.routeType, optionIds(st.options)) to (listOf(originPoint) + unreached.map { placeStop(st.stops[it]).point })
            }
            val order = withContext(routingDispatcher) {
                try {
                    service.bestOrder(inst.generation, set, points, preset)
                } catch (e: Exception) {
                    null
                }
            }
            batch {
                orderJob = null
                if (rev != st.revision) {
                    // the trip changed meanwhile (its own work sets replanning); a trip being guided may have nothing else running
                    if (st.phase == Phase.GUIDING || st.phase == Phase.REROUTING) {
                        st = st.copy(replanning = changeJob != null || rerouteJob != null)
                        publish()
                    }
                    return@launch
                }
                st = st.copy(replanning = false)
                if (order == null) {
                    setProblem(TripProblem(ProblemKind.NO_ROUTE, NO_ORDER_TEXT, false), hold = true)
                    return@launch
                }
                // order[0] is the origin; the others are 1-based indexes into [unreached]
                val reordered = order.order.drop(1).map { st.stops[unreached[it - 1]] }
                val next = st.stops.filter { it.reached } + reordered
                if (next == st.stops) return@launch
                changeStops { next }
            }
        }
    }

    // ================================================================================== start (P5, R3)

    private fun start(sim: Simulation?) {
        if (st.phase != Phase.CHOOSING || st.replanning) return
        val p = planned.firstOrNull { it.choice.id == st.selectedChoiceId } ?: return
        // a simulated drive with a script name the session does not know (a test extra's typo) is refused here,
        // not half way into guidance
        if (sim?.scriptName != null && sim.scriptName !in SimScripts.NAMES) {
            st = st.copy(problem = TripProblem(ProblemKind.ENGINE, NO_SCRIPT_TEXT, false))
            return
        }
        val h = holdPackChanges(HOLD_REASON)
        if (h == null) {
            st = st.copy(problem = TripProblem(ProblemKind.PACKS_BUSY, PACKS_BUSY_TEXT, true))
            return
        }
        val inst = installed.value
        val set = inst.routing
        if (set == null) {
            h.close()
            st = st.copy(problem = noRoutingProblem())
            return
        }
        val rev = st.revision
        st = st.copy(replanning = true)
        startJob?.cancel()
        startJob = scope.launch {
            // the hold until guidance holds it ([hold]); given back on every other way out: an older
            // revision, close(), a cancellation while the engine works, a failure (2.2 rule 4, P5)
            var held: AutoCloseable? = h
            try {
                val osrm = withContext(routingDispatcher) {
                    try {
                        service.forGuidance(inst.generation, set, p.plan)
                    } catch (e: Exception) {
                        null
                    }
                }
                batch {
                    if (closed || rev != st.revision || st.phase != Phase.CHOOSING) return@launch
                    val g = osrm?.let { prepare(p.route, it, p.plan, p.legStops.map { i -> st.stops[i].place }) }
                    if (g == null) {
                        st = st.copy(replanning = false, problem = TripProblem(ProblemKind.ENGINE, ENGINE_TEXT, true))
                        return@launch
                    }
                    hold = h
                    held = null
                    beginGuidance(g, sim, resumed = false)
                }
            } finally {
                held?.close()
            }
        }
    }

    /**
     * R3 (from RESUME_OFFERED) and R4 (from IDLE, after End): re-plan the trip's type and options from
     * the current position to the stops not reached, then guide; [sim] drives it when not null.
     */
    private fun resume(from: Phase, sim: Simulation?) {
        val rev = st.revision + 1
        st = st.copy(revision = rev, problem = null, origin = Origin.CurrentPosition, replanning = true)
        startJob?.cancel()
        startJob = scope.launch {
            val now = originNow()
            val fix = if (now == null) waitForOriginFix() else null
            learnDoors()
            var held: AutoCloseable? = null
            try {
                val inst = installed.value
                val set = inst.routing
                val (stops, unreached, preset, type) = batch {
                    val origin = now ?: originAfterWait(rev, fix) ?: return@launch
                    if (closed || rev != st.revision || st.phase != from) return@launch
                    if (set == null) {
                        st = st.copy(phase = Phase.RESUME_OFFERED, replanning = false, problem = noRoutingProblem())
                        return@launch
                    }
                    held = holdPackChanges(HOLD_REASON)
                    if (held == null) {
                        st = st.copy(phase = Phase.RESUME_OFFERED, replanning = false, problem = TripProblem(ProblemKind.PACKS_BUSY, PACKS_BUSY_TEXT, true))
                        return@launch
                    }
                    val unreached = st.stops.indices.filter { !st.stops[it].reached }
                    val type = st.routeType.takeIf { id -> presets.routeTypes.any { it.id == id } } ?: presets.default.routeType
                    ResumePlan(listOf(origin) + unreached.map { placeStop(st.stops[it]) }, unreached, presets.compose(type, optionIds(st.options)), type)
                }
                val (r, osrm) = withContext(routingDispatcher) { service.planForGuidance(inst.generation, set!!, stops, preset) }
                batch {
                    if (closed || rev != st.revision || st.phase != from) return@launch
                    st = st.copy(routeType = type)
                    keepEnds(unreached.map { st.stops[it].place }, r)
                    val g = if (r is PlanResult.Ok && osrm != null) prepare(r.route, osrm, r, unreached.map { st.stops[it].place }) else null
                    if (g == null) {
                        st = st.copy(phase = Phase.RESUME_OFFERED, replanning = false, problem = (r as? PlanResult.Failed)?.problem?.let(::problemOf)
                            ?: TripProblem(ProblemKind.ENGINE, ENGINE_TEXT, true))
                        return@launch
                    }
                    hold = held
                    held = null
                    beginGuidance(g, sim, resumed = from == Phase.RESUME_OFFERED)
                }
            } finally {
                held?.close()
            }
        }
    }

    private data class ResumePlan(val stops: List<Stop>, val unreached: List<Int>, val preset: Preset, val type: String)

    private fun beginGuidance(g: Guided, sim: Simulation?, resumed: Boolean) {
        planJob?.cancel()
        waitJob?.cancel()
        orderJob?.cancel()
        simulation = sim
        resetDriving()
        installRoute(g)
        val now = clock.elapsedMs()
        lastSaveMs = now
        st = st.copy(phase = Phase.GUIDING, revision = st.revision + 1, choices = emptyList(), selectedChoiceId = null,
            problem = null, replanning = false, gps = GpsState.WAITING, guidance = guidanceNow())
        saveTrip()
        emit(TripEvent.GuidanceStarted(g.choice.id, sim != null))
        if (resumed) emit(TripEvent.RouteReplaced(g.choice.id, ReplaceReason.RESUMED))
        startCheckPending = sim == null
        startCollecting(sim)
    }

    /**
     * A route ready to guide: its line, its banners (B1, B6, built once per route), its follower
     * and the stop each leg ends at. Null when the follower cannot be made from it (a follower that
     * refuses the OSRM answer): the caller treats that as an engine failure, never a crash.
     */
    private fun prepare(route: Route, answer: GuideAnswer, plan: PlanResult.Ok, legPlaces: List<Place>): Guided? {
        val osrm = answer.osrm
        val geo: RouteGeometry
        val banners: List<BannerView>
        val follower: RouteFollower
        try {
            geo = RouteGeometry(route)
            // the legs whose stop ends in its lot (the plan's ends, the origin first)
            val lotLegs = plan.ends.drop(1).mapIndexedNotNull { leg, s -> leg.takeIf { s.end == StopEnd.LOT } }.toSet()
            banners = Views.atLights(Views.intoLots(Views.banners(route, bannerRules), geo, lotLegs), geo, answer.lights)
            follower = followers.create(FollowerRoute(route, osrm, plan.chosen.routeIndex), rules)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val choice = Views.choice(++choiceSeq, listOf(st.routeType), route, plan.warnings.map { it.userText }, bannerRules)
        return Guided(route, geo, banners, follower, choice, legPlaces, SpeedLimits.from(route, osrm, plan.chosen.routeIndex), answer.lanes, answer.lights)
    }

    /** [g] becomes the guided route (the old follower is closed); U comes from its first progress (B6). */
    private fun installRoute(g: Guided) {
        guided?.follower?.close()
        guided = g
        destination = g.route.shape.last()
        hadProgress = false
        shownU = 1
        shownAlong = 0.0
        leadNowM = 0.0
        shownPoint = null
        shownShape = 0
        shownStep = 0
        fixAlong = 0.0
        shownM = null
        lanesShown = false
        announced.clear()
        announcedNow = false
        traveled = null
        offCount = 0
        onCount = 0
    }

    private fun resetDriving() {
        lastUsable = null
        prevUsable = null
        usableCount = 0
        lastDeciding = null
        speedMps = null
        motion = Motion.UNKNOWN
        slowSinceMs = null
        gps = GpsState.WAITING
        vehicle = null
        attempts = 0
        lastRerouteAskMs = null
        rerouteAt = null
        outsideWaiting = false
        fixWaiting = false
        problemClearAt = null
    }

    private fun startCollecting(sim: Simulation?) {
        collectJob?.cancel()
        tickJob?.cancel()
        collectStartMs = clock.elapsedMs()
        val source = if (sim == null) positions else {
            val g = guided!!
            RouteSimulator({ guided?.route ?: g.route }, sim.copy(lateralNoiseM = SimScripts.lateralNoiseM(sim)),
                SimScripts.events(sim, g.route), clock)
        }
        collectJob = scope.launch { source.fixes().collect { guarded { onFix(it) } } }
        tickJob = scope.launch {
            val tick = (rules.tickS * 1000).roundToLong()
            while (true) {
                delay(tick)
                guarded { onTick() }
            }
        }
    }

    private fun stopDriving() {
        collectJob?.cancel()
        tickJob?.cancel()
        rerouteJob?.cancel()
        changeJob?.cancel()
        orderJob?.cancel()
        collectJob = null
        tickJob = null
        rerouteJob = null
        changeJob = null
        orderJob = null
        changeReason = null
        changeOld = null
        changeOldSettings = null
        fixWaiting = false
        hold?.close()
        hold = null
        guided?.follower?.close()
        guided = null
        simulation = null
        destination = null
    }

    // ================================================================================== fixes (G, O, B, A, M)

    /** G1: provider, age, order and accuracy. */
    private fun usable(f: Fix, now: Long): Boolean {
        if (f.provider !in rules.guidingProviders) return false
        val acc = f.accuracyM ?: return false
        if (acc > rules.progressMaxAccuracyM) return false
        if (now - f.elapsedMs > (rules.fixMaxAgeS * 1000).roundToLong()) return false
        val last = lastUsable
        if (last != null && f.elapsedMs <= last.elapsedMs) return false
        return true
    }

    private fun isDeciding(f: Fix) = f.accuracyM?.let { it <= rules.decideMaxAccuracyM } == true

    /** M1: the fix's speed, else distance over time since the previous usable fix. */
    private fun updateSpeed(f: Fix) {
        val prev = prevUsable
        speedMps = f.speedMps?.toDouble() ?: if (prev != null && f.elapsedMs > prev.elapsedMs) {
            Geo.distanceM(LatLon(prev.lat, prev.lon), LatLon(f.lat, f.lon)) / ((f.elapsedMs - prev.elapsedMs) / 1000.0)
        } else null
    }

    /** M2: STOPPED after stopped_after_s below stopped_below_mps, MOVING above moving_above_mps, else unchanged. */
    private fun updateMotion(now: Long) {
        if (gps == GpsState.LOST || usableCount < 2) {
            motion = Motion.UNKNOWN
            slowSinceMs = null
            return
        }
        val v = speedMps ?: return
        if (v < rules.stoppedBelowMps) {
            val since = slowSinceMs ?: (lastUsable?.elapsedMs ?: now).also { slowSinceMs = it }
            if (now - since >= (rules.stoppedAfterS * 1000).roundToLong()) motion = Motion.STOPPED
        } else {
            slowSinceMs = null
            if (v > rules.movingAboveMps) motion = Motion.MOVING
        }
    }

    private fun onFix(fix: Fix) {
        val g = guided ?: return
        val phase = st.phase
        if (phase != Phase.GUIDING && phase != Phase.REROUTING && phase != Phase.ARRIVED) return
        val now = clock.elapsedMs()
        if (!usable(fix, now)) {
            afterFix?.invoke(fix, st)
            return
        }
        prevUsable = lastUsable
        lastUsable = fix
        usableCount++
        gps = GpsState.GOOD // G2: one usable fix returns it to GOOD
        updateSpeed(fix)
        updateMotion(fix.elapsedMs)
        val deciding = isDeciding(fix)
        if (deciding) lastDeciding = fix
        val raw = LatLon(fix.lat, fix.lon)

        if (phase == Phase.ARRIVED) {
            // A3: ARRIVED ends when a deciding fix is more than leave_m from the destination
            val dest = destination
            if (deciding && dest != null && Geo.distanceM(raw, dest) > rules.arriveLeaveM) {
                finishGuidance()
                afterFix?.invoke(fix, st)
                return
            }
            vehicle = Vehicle(GeoPoint(fix.lat, fix.lon), fix.bearingDeg?.toDouble(), false, motion, speedMps, fix.elapsedMs)
            publish()
            afterFix?.invoke(fix, st)
            return
        }

        var p = g.follower.update(fix)
        if (!hadProgress) hadProgress = true

        // O9: the first deciding fix after Start far from the route re-routes at once from the car
        if (startCheckPending && deciding) {
            startCheckPending = false
            if (p.lateralM > rules.startFarFromRouteM) {
                enterRerouting()
                requestReroute(user = true)
            }
        }

        // O1-O3, O5: off / on by deciding fixes only
        val threshold = max(rules.offRouteBaseM, rules.offRouteAccuracyFactor * (fix.accuracyM ?: 0f))
        val v = speedMps ?: 0.0
        val course = fix.bearingDeg?.toDouble()
        val wrongWay = v >= rules.wrongWayMinSpeedMps && course != null && p.routeBearingDeg != null &&
            RouteGeometry.angleDiff(course, p.routeBearingDeg!!) > rules.wrongWayDeg
        if (deciding) {
            val off = p.lateralM > threshold || wrongWay
            if (off) {
                if (offCount == 0) offFirstMs = fix.elapsedMs
                offCount++
                onCount = 0
            } else {
                offCount = 0
                onCount++
            }
        }
        val suspected = offCount > 0
        if (st.phase == Phase.GUIDING && offCount >= rules.offRouteConfirmFixes &&
            fix.elapsedMs - offFirstMs >= (rules.offRouteConfirmS * 1000).roundToLong()) {
            enterRerouting()
            requestReroute(user = false)
        } else if (st.phase == Phase.REROUTING && rejoinAllowed && onCount >= rules.rejoinFixes && changeJob == null) {
            rejoin()
        }
        // O8: asked again as soon as a deciding fix is covered, within O4's min_interval_s: after an
        // engine 170/171 the car's point is covered, and every fix would otherwise ask the engine again
        if (st.phase == Phase.REROUTING && outsideWaiting && deciding && rerouteJob == null) {
            if (installed.value.routing?.covers(GeoPoint(fix.lat, fix.lon)) == true) {
                outsideWaiting = false
                requestReroute(user = false)
            }
        }
        // O4: a re-route asked before there was any usable fix (Reroute right after Start) is asked with this one
        if (st.phase == Phase.REROUTING && fixWaiting && rerouteJob == null && changeJob == null) {
            requestReroute(user = true)
        }

        val guiding = st.phase == Phase.GUIDING && !suspected
        if (guiding && deciding) {
            // A1, A2: an arrival the follower has passed by itself (a fix that entered and left a stop
            // at once, a few seconds without fixes, or a follower with its own waypoint advance) is
            // reached now, so a stop is never skipped and U never shows a leg's departure
            for (a in g.arrivals) {
                if (a > p.stepIndex) break
                if (g.geo.maneuverLeg[a] in g.reachedLegs) continue
                p = arriveAt(g, a, p) ?: run { afterFix?.invoke(fix, st); return }
            }
        }
        // B2: forced advance (and A1 arrivals passed in a gap), only on the route with a deciding fix
        if (guiding && deciding) {
            var guard = 0
            while (guard++ <= g.geo.maneuverCount) {
                val u = p.stepIndex + 1
                if (u >= g.geo.maneuverCount) break
                val beyond = -p.toManeuverM
                if (g.route.maneuvers[u].type.isDestination) {
                    if (beyond > rules.arrivePassedM) {
                        p = arriveAt(g, u, p) ?: run { afterFix?.invoke(fix, st); return }
                    } else break
                } else if (beyond > rules.advancePastM) {
                    p = g.follower.advance()
                } else break
            }
        }

        // where the car is shown (G8 lead, G6 backstep hold), U, the live distance (B3), lanes (B5), announcements (B8)
        val newAlong = g.geo.alongOf(p.shapeIndex, p.snapped)
        if (!suspected) {
            val ageS = ((now - fix.elapsedMs) / 1000.0).coerceIn(0.0, rules.fixMaxAgeS)
            val target = leadAlong(g, newAlong, speedMps, fix.accuracyM, ageS + glideS(fix.elapsedMs))
            // G6 judges the car's own position (the shown one without its lead); G8 waits, while
            // moving, for a car that the arrow is ahead of (a lead that shrank: braking, or one poor
            // fix), and lets the arrow go back to the car only when it is about to stop
            val realBack = carAlong() - newAlong
            val slow = (speedMps ?: 0.0) < rules.lead.minSpeedMps
            when {
                hadShown() && p.stepIndex == shownStep && realBack > 0.0 && realBack < rules.backstepHoldM -> Unit
                hadShown() && realBack <= 0.0 && target < shownAlong && !slow -> leadNowM = shownAlong - newAlong
                else -> {
                    shownAlong = target
                    leadNowM = target - newAlong
                    if (target > newAlong) {
                        shownPoint = g.geo.pointAt(target)
                        shownShape = g.geo.indexAt(target)
                    } else {
                        shownPoint = p.snapped
                        shownShape = p.shapeIndex
                    }
                }
            }
            shownStep = p.stepIndex
            fixAlong = newAlong
            fixAlongMs = fix.elapsedMs
            lastFixOnRoute = true
            traveled = RouteCursor(shownShape, GeoPoint(shownPoint!!.lat, shownPoint!!.lon))
        } else {
            lastFixOnRoute = false
        }
        if (guiding) {
            val u = (p.stepIndex + 1).coerceAtMost(g.geo.maneuverCount - 1)
            val d = if (u == p.stepIndex + 1) p.toManeuverM - (shownAlong - newAlong) else g.geo.maneuverAlong[u] - shownAlong
            updateBanner(g, u, max(0.0, d), deciding)
            // A1: arrived at the end of a leg when U is its arrival and s <= within_m, or the follower is complete on the last leg
            // (s from the fix itself: the lead of G8 is for the eye, an arrival waits for the car)
            if (deciding && g.route.maneuvers[shownU].type.isDestination) {
                val lastLeg = g.geo.maneuverLeg[shownU] == g.route.legs.lastIndex
                if ((shownM ?: Double.MAX_VALUE) + leadNowM <= rules.arriveWithinM || (p.complete && lastLeg)) {
                    p = arriveAt(g, shownU, p) ?: run { afterFix?.invoke(fix, st); return }
                    shownStep = p.stepIndex
                    if (st.phase == Phase.GUIDING) {
                        val nu = (p.stepIndex + 1).coerceAtMost(g.geo.maneuverCount - 1)
                        updateBanner(g, nu, max(0.0, p.toManeuverM), deciding)
                    }
                }
            }
        }
        vehicle = if (suspected) {
            Vehicle(GeoPoint(fix.lat, fix.lon), course, false, motion, speedMps, fix.elapsedMs) // O2: the raw fix and its course
        } else {
            val pt = shownPoint ?: p.snapped
            Vehicle(GeoPoint(pt.lat, pt.lon), g.geo.bearingAt(shownAlong) ?: p.routeBearingDeg, true, motion, speedMps, fix.elapsedMs)
        }
        publish()
        afterFix?.invoke(fix, st)
    }

    private fun hadShown() = shownPoint != null

    /** G8: where the car itself is along the route, as far as the session knows: the shown position without its lead. */
    private fun carAlong() = shownAlong - leadNowM

    /**
     * G8: how long the map will glide to a vehicle position of time [elapsedMs]: the time since the
     * last vehicle position (a fix's or a dead-reckoning tick's), as VehicleGlide measures it; 0 when
     * there is none or the gap is above lead.max_glide_s (the map jumps then).
     */
    private fun glideS(elapsedMs: Long): Double =
        vehicle?.let { (elapsedMs - it.elapsedMs) / 1000.0 }?.takeIf { it > 0.0 && it <= rules.lead.maxGlideS } ?: 0.0

    /**
     * G8: where the car is shown for the route position [along] (metres) that was true [aheadS]
     * seconds before the arrow, gliding, gets there (the fix's age plus its glide): ahead by the speed
     * [v] times aheadS plus lead.extra_s, at most lead.max_m, never past the next maneuver the car
     * slows for (lead.stop_at) nor the route's end, never behind [along]. No lead below
     * lead.min_speed_mps or from a fix poorer than lead.max_accuracy_m (or of unknown accuracy).
     */
    private fun leadAlong(g: Guided, along: Double, v: Double?, accuracyM: Float?, aheadS: Double): Double {
        val r = rules.lead
        if (v == null || v < r.minSpeedMps) return along
        if (accuracyM == null || accuracyM > r.maxAccuracyM) return along
        var cap = g.geo.lengthM
        val ms = g.geo.maneuverAlong
        for (i in ms.indices) {
            if (ms[i] > along && g.route.maneuvers[i].type in r.stopAt) {
                cap = ms[i]
                break
            }
        }
        return max(along, minOf(along + minOf(v * (aheadS + r.extraS), r.maxM), cap))
    }

    /** B3 (hold against jitter), B5 (lanes once shown stay), B8 (announce events) for upcoming maneuver [u] at live distance [d]. */
    private fun updateBanner(g: Guided, u: Int, d: Double, moving: Boolean) {
        if (u != shownU || shownM == null) {
            if (u != shownU) {
                lanesShown = false
                announced.clear()
                announcedNow = false
            }
            shownU = u
            shownM = d
        } else {
            val s = shownM!!
            if (d <= s || d > s + rules.distanceHoldM) shownM = d
        }
        val s = shownM!!
        val view = g.banners[u]
        if (!lanesShown && view.lanes != null) {
            lanesShown = s <= if (view.approachOnHighway) rules.lanesWithinHighwayM else rules.lanesWithinOtherM
        }
        // B8: PREPARE once per distance as s first drops below it (when U begins inside several, only the nearest)
        val list = if (view.approachOnHighway) rules.announceHighwayM else rules.announceOtherM
        val crossed = list.filter { s < it && it !in announced }
        if (crossed.isNotEmpty()) {
            announced += crossed
            val nearest = crossed.minOrNull()!!
            emit(TripEvent.Announce(u, AnnounceStage.PREPARE, nearest))
        }
        val v = speedMps
        if (!announcedNow && moving && v != null && v > rules.movingAboveMps && s / v < rules.announceNowS) {
            announcedNow = true
            emit(TripEvent.Announce(u, AnnounceStage.NOW, s))
        }
    }

    /**
     * A1-A3: the arrival maneuver [u] reached. An intermediate stop is marked reached, trip.json
     * saved, StopReached emitted and the follower moved past the arrival and the departure (A2);
     * the destination ends guidance in ARRIVED (A3: returns null).
     */
    private fun arriveAt(g: Guided, u: Int, p0: FollowerProgress): FollowerProgress? {
        val leg = g.geo.maneuverLeg[u]
        g.reachedLegs += leg
        // the leg's stop, by place (a pending change may have moved or removed it, C3)
        val place = g.legPlaces.getOrNull(leg)
        val stopIndex = st.stops.indexOfFirst { !it.reached && it.place == place }
        val stops = if (stopIndex < 0) st.stops else st.stops.mapIndexed { i, s -> if (i == stopIndex) s.copy(reached = true) else s }
        if (leg >= g.route.legs.lastIndex || stops.none { !it.reached }) {
            val name = (stops.getOrNull(stopIndex) ?: stops.lastOrNull())?.place?.name ?: ""
            clearTrip()
            rerouteJob?.cancel()
            rerouteJob = null
            changeJob?.cancel()
            changeJob = null
            changeReason = null
            changeOld = null
            changeOldSettings = null
            orderJob?.cancel()
            orderJob = null
            shownU = u
            shownM = 0.0
            st = st.copy(phase = Phase.ARRIVED, stops = stops, revision = st.revision + 1, replanning = false, problem = null)
            publish()
            emit(TripEvent.Arrived(name))
            return null
        }
        st = st.copy(stops = stops, revision = st.revision + 1)
        saveTrip()
        if (stopIndex >= 0) emit(TripEvent.StopReached(stopIndex, stops[stopIndex].place.name))
        var p = p0
        var guard = 0
        while (p.stepIndex < u + 1 && guard++ < 4) p = g.follower.advance()
        // a change asked before this stop was reached would route back to it: ask it again from here (C3)
        if (changeJob != null) changeDuringGuidance(st.stops, st.routeType, st.options, changeReason ?: ReplaceReason.STOPS_CHANGED)
        return p
    }

    // ================================================================================== ticks (G2-G4, M2, O7, R1)

    private fun onTick() {
        val g = guided ?: return
        val phase = st.phase
        if (phase != Phase.GUIDING && phase != Phase.REROUTING && phase != Phase.ARRIVED) return
        val now = clock.elapsedMs()
        val last = lastUsable
        gps = when {
            last == null -> GpsState.WAITING
            now - last.elapsedMs <= (rules.weakAfterS * 1000).roundToLong() -> GpsState.GOOD
            now - last.elapsedMs <= (rules.lostAfterS * 1000).roundToLong() -> GpsState.WEAK
            else -> GpsState.LOST
        }
        updateMotion(now)
        // G3: dead reckoning along the route in WEAK (never advances, never counts, never arrives)
        if (gps == GpsState.WEAK && phase == Phase.GUIDING && offCount == 0 && lastFixOnRoute && hadProgress) {
            val v = speedMps ?: 0.0
            if (v >= rules.deadReckonMinSpeedMps) {
                val limit = minOf(g.geo.maneuverAlong[shownU], g.geo.lengthM)
                // G8: the tick's position glides as a fix's would, so it leads the same way
                val car = fixAlong + v * (now - fixAlongMs) / 1000.0
                val along = minOf(leadAlong(g, car, v, last?.accuracyM, glideS(now)), limit)
                if (along > shownAlong) {
                    shownAlong = along
                    leadNowM = max(0.0, along - car)
                    val pt = g.geo.pointAt(along)
                    shownPoint = pt
                    shownShape = g.geo.indexAt(along)
                    traveled = RouteCursor(shownShape, GeoPoint(pt.lat, pt.lon))
                    vehicle = Vehicle(GeoPoint(pt.lat, pt.lon), g.geo.bearingAt(along), true, motion, speedMps, now)
                    val d = max(0.0, g.geo.maneuverAlong[shownU] - along)
                    if (shownM == null || d <= shownM!!) shownM = d
                }
            }
        }
        if (gps != GpsState.LOST) {
            // O4 (min_interval_s) and O7 (retry_s): a re-route that waits for its time; nothing re-routes in LOST (G4)
            val at = rerouteAt
            if (st.phase == Phase.REROUTING && at != null && now >= at && rerouteJob == null && changeJob == null) {
                rerouteAt = null
                requestReroute(user = false)
            }
        }
        // R1: trip.json every resume.refresh_s while a trip is guided
        if ((phase == Phase.GUIDING || phase == Phase.REROUTING) && now - lastSaveMs >= (rules.resumeRefreshS * 1000).roundToLong()) {
            saveTrip()
        }
        // C3: a change's problem shows for planning.problem_hold_s
        problemClearAt?.let { if (now >= it) {
            problemClearAt = null
            if (st.phase == Phase.GUIDING) st = st.copy(problem = null)
        } }
        publish()
    }

    // ================================================================================== re-route (O4-O8)

    /** Off route confirmed (O3) or a start far from the route (O9): REROUTING, which the car ends by rejoining the route (O5). */
    private fun enterRerouting() {
        if (st.phase != Phase.GUIDING) return
        rejoinAllowed = true
        onCount = 0
        st = st.copy(phase = Phase.REROUTING)
    }

    /**
     * O4: a re-route from the car; [user] (Reroute, Retry, O9, the first fix after a Reroute without
     * one) skips min_interval_s. With no usable fix yet there is nothing to ask from: REROUTING waits
     * for the first one ([fixWaiting], onFix), so it never waits for an answer nobody asked.
     */
    private fun requestReroute(user: Boolean) {
        if (st.phase != Phase.REROUTING) st = st.copy(phase = Phase.REROUTING)
        if (rerouteJob != null || changeJob != null) return
        val now = clock.elapsedMs()
        val lastAsk = lastRerouteAskMs
        val minMs = (rules.rerouteMinIntervalS * 1000).roundToLong()
        if (!user && lastAsk != null && now - lastAsk < minMs) {
            rerouteAt = maxOf(rerouteAt ?: 0L, lastAsk + minMs)
            publish()
            return
        }
        val originFix = lastDeciding ?: lastUsable
        if (originFix == null) {
            fixWaiting = true
            publish()
            return
        }
        fixWaiting = false
        val inst = installed.value
        val set = inst.routing
        // O8: outside the installed states no engine call, no attempt counted
        if (set == null || !set.covers(GeoPoint(originFix.lat, originFix.lon))) {
            outsideWaiting = true
            st = st.copy(problem = TripProblem(ProblemKind.OUTSIDE_INSTALLED_MAPS, OUTSIDE_TEXT, true), replanning = false)
            publish()
            return
        }
        lastRerouteAskMs = now
        rerouteAt = null
        val unreached = st.stops.indices.filter { !st.stops[it].reached }
        val legPlaces = unreached.map { st.stops[it].place }
        val stops = listOf(carStop(originFix)) + unreached.map { planStop(st.stops[it]) }
        val preset = presets.compose(st.routeType, optionIds(st.options))
        val seq = ++rerouteSeq
        st = st.copy(replanning = true)
        publish()
        rerouteJob = scope.launch {
            val (r, osrm) = withContext(routingDispatcher) { service.planForGuidance(inst.generation, set, stops, preset) }
            batch {
                rerouteJob = null
                if (seq != rerouteSeq || st.phase != Phase.REROUTING) return@launch
                onRerouteAnswer(r, osrm, legPlaces)
            }
        }
    }

    private fun onRerouteAnswer(r: PlanResult, osrm: GuideAnswer?, legPlaces: List<Place>) {
        val g = if (r is PlanResult.Ok && osrm != null) prepare(r.route, osrm, r, legPlaces) else null
        if (g != null) {
            keepEnds(legPlaces, r)
            // O6
            installRoute(g)
            attempts = 0
            outsideWaiting = false
            fixWaiting = false
            st = st.copy(phase = Phase.GUIDING, revision = st.revision + 1, replanning = false, problem = null)
            publish()
            emit(TripEvent.RouteReplaced(g.choice.id, ReplaceReason.REROUTE))
            return
        }
        val problem = (r as? PlanResult.Failed)?.problem
        // O8: 170/171 (no road reachable near the origin: the stops were reachable when the trip was planned)
        // is "outside the installed maps", no attempt counted
        if (problem != null && (problem.engineCode == 170 || problem.engineCode == 171) &&
            (problem !is RoutingProblem.OutsideInstalledMaps || problem.stopIndices.isEmpty() || 0 in problem.stopIndices)) {
            outsideWaiting = true
            st = st.copy(replanning = false, problem = TripProblem(ProblemKind.OUTSIDE_INSTALLED_MAPS, OUTSIDE_TEXT, true))
            publish()
            return
        }
        // O7
        attempts++
        val tp = problem?.let(::problemOf) ?: TripProblem(ProblemKind.ENGINE, ENGINE_TEXT, true)
        st = st.copy(replanning = false, problem = tp)
        rerouteAt = if (attempts < rules.rerouteMaxAttempts) clock.elapsedMs() + (rules.rerouteRetryS * 1000).roundToLong() else null
        publish()
        emit(TripEvent.RerouteFailed(tp))
    }

    /** O5: back on the old route before the answer: the answer is dropped, GUIDING again (no event). */
    private fun rejoin() {
        rerouteSeq++
        rerouteJob?.cancel()
        rerouteJob = null
        rerouteAt = null
        attempts = 0
        outsideWaiting = false
        fixWaiting = false
        st = st.copy(phase = Phase.GUIDING, replanning = false, problem = null)
    }

    // ================================================================================== changes during guidance (C3, C4)

    /**
     * C3, C4: [stops], [type] and [options] are shown at once and planned from the car (O4 origin);
     * guidance goes on with the old route until the answer. A newer change replaces a running one;
     * a stop reached meanwhile asks again (arriveAt). OK: the route is swapped and trip.json saved.
     * Failed: the trip goes back to what the guided route was planned for (the reached flags kept),
     * settings too, and the problem shows for planning.problem_hold_s.
     */
    private fun changeDuringGuidance(stops: List<TripStop>, type: String, options: RouteOptions, reason: ReplaceReason) {
        val g = guided ?: return
        if (changeOld == null) {
            changeOld = Triple(st.stops, st.routeType, st.options)
            changeOldSettings = settings.settings.value
        }
        changeReason = reason
        changeJob?.cancel()
        rerouteSeq++ // a running re-route's answer is dropped: this request replaces it
        rerouteJob?.cancel()
        rerouteJob = null
        val rev = st.revision + 1
        st = st.copy(stops = stops, routeType = type, options = options, revision = rev, replanning = true)
        publish()
        val originFix = lastDeciding ?: lastUsable
        val inst = installed.value
        val set = inst.routing
        val unreached = stops.indices.filter { !stops[it].reached }
        val legPlaces = unreached.map { stops[it].place }
        val origin = originFix?.let(::carStop) ?: Stop(g.geo.pointAt(carAlong()), radiusM = rules.rerouteOriginRadiusM)
        val preset = presets.compose(type, optionIds(options))
        changeJob = scope.launch {
            val (r, osrm) = if (set == null) PlanResult.Failed(RoutingProblem.OutsideInstalledMaps(emptyList(), 171), 0) to null
            else withContext(routingDispatcher) { service.planForGuidance(inst.generation, set, listOf(origin) + unreached.map { planStop(stops[it]) }, preset) }
            batch {
                changeJob = null
                if (rev != st.revision || guided == null) return@launch
                val next = if (r is PlanResult.Ok && osrm != null) prepare(r.route, osrm, r, legPlaces) else null
                val why = changeReason ?: reason
                changeReason = null
                if (next != null) {
                    keepEnds(legPlaces, r)
                    changeOld = null
                    changeOldSettings = null
                    installRoute(next)
                    fixWaiting = false
                    st = st.copy(phase = Phase.GUIDING, revision = st.revision + 1, replanning = false, problem = null)
                    saveTrip()
                    publish()
                    emit(TripEvent.RouteReplaced(next.choice.id, why))
                } else {
                    undoChange()
                    val tp = (r as? PlanResult.Failed)?.problem?.let(::problemOf) ?: TripProblem(ProblemKind.ENGINE, ENGINE_TEXT, true)
                    st = st.copy(replanning = false)
                    setProblem(tp, hold = true)
                    // a re-route that this change replaced asks again
                    if (st.phase == Phase.REROUTING) rerouteAt = clock.elapsedMs()
                    emit(TripEvent.RerouteFailed(tp))
                }
            }
        }
    }

    /** C3: back to the stops, type and options the guided route was planned for; stops reached meanwhile stay reached. */
    private fun undoChange() {
        val old = changeOld ?: return
        val oldSettings = changeOldSettings
        changeOld = null
        changeOldSettings = null
        val reachedNow = st.stops.filter { it.reached }.map { it.place }
        val stops = old.first.map { s -> if (!s.reached && s.place in reachedNow) s.copy(reached = true) else s }
        val typeOrOptionsChanged = st.routeType != old.second || st.options != old.third
        st = st.copy(stops = stops, routeType = old.second, options = old.third, revision = st.revision + 1)
        if (typeOrOptionsChanged && oldSettings != null) {
            settings.update { it.copy(routeType = oldSettings.routeType, avoidHighways = oldSettings.avoidHighways, avoidTolls = oldSettings.avoidTolls) }
        }
    }

    private fun setProblem(p: TripProblem, hold: Boolean) {
        st = st.copy(problem = p)
        problemClearAt = if (hold) clock.elapsedMs() + (rules.problemHoldS * 1000).roundToLong() else null
        if (hold && (st.phase == Phase.PLANNING || st.phase == Phase.CHOOSING)) {
            // before guidance there are no ticks: clear it with a timer
            val at = problemClearAt!!
            scope.launch {
                delay((rules.problemHoldS * 1000).roundToLong())
                batch {
                    if (problemClearAt == at && st.problem == p) {
                        problemClearAt = null
                        st = st.copy(problem = null)
                    }
                }
            }
        }
        publish()
    }

    // ================================================================================== state

    /** R1: trip.json now. A file that cannot be written (a full disk) is skipped: guidance matters more than the resume offer. */
    private fun saveTrip() {
        if (st.stops.isEmpty()) return
        lastSaveMs = clock.elapsedMs()
        try {
            store.save(TripSnapshot(wallClockMs(), st.stops, st.routeType, st.options))
        } catch (e: Exception) {
            tripWriteFailures++
        }
    }

    /** R1: trip.json deleted (End, Done, DiscardResume, arrival, a snapshot too old). */
    private fun clearTrip() {
        try {
            store.clear()
        } catch (e: Exception) {
            tripWriteFailures++
        }
    }

    /** trip.json writes or deletes that failed (tests). */
    var tripWriteFailures = 0
        private set

    private fun guidanceNow(): Guidance? {
        val g = guided ?: return null
        val u = shownU.coerceIn(0, g.banners.lastIndex)
        val legIndex = g.geo.maneuverLeg[shownStep.coerceIn(0, g.geo.maneuverCount - 1)]
        val legEnd = g.geo.legEndAlong(legIndex)
        val next = st.stops.indexOfFirst { !it.reached }.takeIf { it >= 0 }
        return Guidance(
            route = g.choice,
            banner = if (hadProgress && shownM != null) LiveBanner(g.banners[u], shownM!!, lanesShown) else null,
            vehicle = vehicle?.copy(motion = motion),
            traveled = traveled,
            legIndex = legIndex,
            nextStopIndex = next,
            toNextStopM = max(0.0, legEnd - shownAlong),
            toNextStopS = max(0.0, g.geo.timeAfter(shownAlong) - g.geo.timeAfter(legEnd)),
            remainingM = max(0.0, g.geo.lengthM - shownAlong),
            remainingS = g.geo.timeAfter(shownAlong),
            offRouteSuspected = offCount > 0,
            rerouteAttempts = attempts,
            simulated = simulation != null,
            speedLimitKmh = traveled?.let { g.limits.at(g.geo.indexAt(carAlong())) },  // the car's road, not the lead's (G8)
            laneWarning = laneWarningNow(g),
            lightsAhead = if (hadProgress) g.lights.ahead(shownAlong) else emptyList(),
            currentRoad = if (hadProgress) Views.currentRoad(g.route, u, bannerRules) else null,
        )
    }

    /**
     * Rule B9: the next lane warning, shown from max(min_m, lead_s x speed) (at most max_m) before
     * its intersection until after_m past it; once shown it stays until passed (a slower speed does
     * not hide it). None before the first progress, while off the route is suspected or rerouting.
     */
    private fun laneWarningNow(g: Guided): LaneWarningView? {
        if (!hadProgress || offCount > 0 || st.phase != Phase.GUIDING) return null
        val lw = rules.laneWarning
        val next = g.lanes.next(carAlong(), lw.afterM) ?: return null  // passed when the car is past it, not the arrow (G8)
        val toM = next.value.alongM - shownAlong
        val speed = vehicle?.speedMps?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val window = (speed * lw.leadS).coerceIn(lw.minM, lw.maxM)
        if (g.laneShown != next.index && toM > window) return null
        g.laneShown = next.index
        return LaneWarningView(next.value.lanes, next.value.text, max(0.0, toM))
    }

    private fun publish() {
        val phase = st.phase
        if (phase != Phase.GUIDING && phase != Phase.REROUTING && phase != Phase.ARRIVED) return
        st = st.copy(guidance = guidanceNow(), gps = gps)
    }

    companion object {
        /** Item 47: the same place asked again within this is not routed again. */
        const val ESTIMATE_FRESH_MS = 60_000L
        const val HOLD_REASON = "Not while guiding"
        /** Item 47: the destination a swap makes of the current position. */
        const val YOUR_POSITION = "Your position"
        const val NO_POSITION_TEXT = "No GPS position yet. Choose a start point or try again."
        const val PACKS_BUSY_TEXT = "A state is being installed. Start again when it has finished."
        const val OUTSIDE_TEXT = "You are outside the installed maps."
        const val NO_ROUTING_TEXT = "No state with routes is installed. Add a state."
        const val PACKS_CHANGED_TEXT = "The installed states changed. Planning again."
        const val NO_ORDER_TEXT = "No order of these stops connects them all."
        const val ENGINE_TEXT = "The route could not be planned."
        const val NO_SCRIPT_TEXT = "There is no simulated drive with that name."
    }
}
