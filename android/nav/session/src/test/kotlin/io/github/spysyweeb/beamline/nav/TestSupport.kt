package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.RoutingSet
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.location.PositionSource
import io.github.spysyweeb.beamline.location.SharedPositionSource
import io.github.spysyweeb.beamline.routing.banner.BannerRules
import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses
import io.github.spysyweeb.beamline.routing.valhalla.canonicalJson
import io.github.spysyweeb.beamline.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream
import kotlin.math.floor

/** Test data from android/routing/testdata (recorded with pyvalhalla 3.9.1 on the PA+OH device tiles). */
object Data {
    fun text(name: String): String {
        val stream = Data::class.java.getResourceAsStream("/$name") ?: error("test resource $name missing")
        val raw = if (name.endsWith(".gz")) GZIPInputStream(stream) else stream
        return raw.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject

    fun exists(name: String) = Data::class.java.getResource("/$name") != null

    /** The owner's exit 24 trip (docs/architecture.md section 8): origin with heading 57, Downs Dr. */
    val EXIT24_ORIGIN = Place("Exit 24 start", GeoPoint(42.028694, -80.128884), PlaceSource.MAP_PIN)
    val DOWNS_DRIVE = Place("Downs Drive", GeoPoint(42.053308, -80.079579), PlaceSource.MAP_PIN)

    /** The exit 24 route as the session plans it (Fastest; banner-routes.json.gz, case exit24). */
    val exit24: Route by lazy {
        val rec = json("reference/banner-routes.json.gz")["recordings"]!!.jsonArray.first { it.jsonObject["case"]!!.jsonPrimitive.content == "exit24" }
        ValhallaResponses.parseRoute(rec.jsonObject["response"].toString()).main
    }
}

/**
 * The recorded-answer engine of the session tests: answers route and matrix requests from the
 * recordings (a request is its canonical JSON). A request that is not recorded is kept in
 * [MissingRequests] (written to the build folder for android/routing/scripts/record_session.py)
 * and answered with an EngineException, so the test fails at its end with a clear message.
 */
class RecordedEngine(recordings: List<JsonObject>) : RoutingEngine, AutoCloseable {
    private val answers = recordings.associateBy { canonicalJson(it["request"]!!) }
    val asked = mutableListOf<JsonObject>()
    var closed = 0
    /** Fails a request on purpose (O7, C3 failures): return the exception to throw. */
    var failWhen: ((JsonObject) -> EngineException?)? = null

    private fun answer(kind: String, requestJson: String): String {
        val req = Json.parseToJsonElement(requestJson).jsonObject
        asked += req
        failWhen?.invoke(req)?.let { throw it }
        val r = answers[canonicalJson(req)] ?: run {
            MissingRequests.add(kind, requestJson)
            throw EngineException(null, "request not recorded")
        }
        r["error"]?.jsonObject?.let { throw EngineException(it["code"]!!.jsonPrimitive.int, it["message"]!!.jsonPrimitive.content) }
        return r["response"].toString()
    }

    override fun route(requestJson: String): String = answer("route", requestJson)
    override fun matrix(requestJson: String): String = answer("matrix", requestJson)
    override fun traceAttributes(requestJson: String): String = answer("trace_attributes", requestJson)
    override fun close() {
        closed++
    }

    val routeRequests get() = asked.filter { "sources" !in it && "shape" !in it }

    companion object {
        val FILES = listOf(
            "reference/banner-routes.json.gz", "reference/recordings.json.gz", "reference/recordings-plans.json.gz",
            "nav/session-recordings.json.gz",
        )

        private val all: List<JsonObject> by lazy {
            val out = ArrayList<JsonObject>()
            for (f in FILES) if (Data.exists(f)) out += Data.json(f)["recordings"]!!.jsonArray.map { it.jsonObject }
            // the exit 24 trip in OSRM form (Ferrostar's request), as reference_routes.py recorded it
            val osrm = Data.json("reference/exit24-osrm.json")
            out += JsonObject(mapOf("request" to osrm["request"]!!, "response" to osrm["response"]!!))
            out
        }

        fun standard() = RecordedEngine(all)
    }
}

/** Requests the recordings lack, for android/routing/scripts/record_session.py (one JSON file per test JVM). */
object MissingRequests {
    private val seen = LinkedHashMap<String, Pair<String, String>>()

    @Synchronized
    fun add(kind: String, text: String) {
        val key = canonicalJson(Json.parseToJsonElement(text))
        if (key in seen) return
        seen[key] = kind to text
        val out = System.getProperty("beamline.missingRequests") ?: return
        val arr = JsonArray(seen.values.map { (k, t) -> JsonObject(mapOf("kind" to JsonPrimitive(k), "text" to JsonPrimitive(t))) })
        File(out).apply { parentFile.mkdirs() }.writeText(JsonObject(mapOf("format" to JsonPrimitive("beamline-missing-requests/1"), "requests" to arr)).toString())
    }

    @Synchronized
    fun count() = seen.size
}

/** A dispatcher that holds its work while [gated], to make an engine answer arrive later (O5, C2). */
class GatedDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
    var gated = false
    private val held = ArrayList<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        if (gated) held += context to block else inner.dispatch(context, block)
    }

    fun release() {
        gated = false
        val now = held.toList()
        held.clear()
        now.forEach { (c, b) -> inner.dispatch(c, b) }
    }

    val waiting get() = held.size
}

/** Positions pushed by the test (a hot flow: a fix nobody collects is lost, as on the phone). */
class PushedPositions : PositionSource {
    val flow = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    override fun fixes(): Flow<Fix> = flow
}

/**
 * A session on a virtual clock: both dispatchers on the test's scheduler, the recorded engine, the
 * geometric follower, settings and trip.json in a temporary folder, PA+OH installed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Harness(
    val test: TestScope,
    val engine: RecordedEngine = RecordedEngine.standard(),
    val dir: File = Files.createTempDirectory("nav-session").toFile(),
    val wallStartMs: Long = 1_763_280_000_000L,
    rules: GuidanceRules = GuidanceRules.bundled().withoutLead(),
    puck: Boolean = true,
    followers: RouteFollowerFactory = GeometricFollowerFactory(),
    val routingGate: GatedDispatcher = GatedDispatcher(StandardTestDispatcher(test.testScheduler, "routing")),
    tripFile: File = File(dir, "trip.json"),
    /** Item 28: the front door of a place, as the app reads it from the search packs (none by default). */
    doorOf: (Place, InstalledSet) -> GeoPoint? = { _, _ -> null },
) {
    val scheduler = test.testScheduler
    val clock = Clock { scheduler.currentTime }
    var wallOffsetMs = 0L
    val pushed = PushedPositions()
    val positions = SharedPositionSource(pushed, test.backgroundScope)
    val installed = MutableStateFlow(InstalledSet(generation = 1, scanned = true, routing = paOh()))
    var holdBusy = false
    var holds = 0
    var released = 0
    val settings = SettingsStore(File(dir, "settings.json"))
    val store = TripStore(tripFile)
    var opens = 0
    val session = BeamlineNavigationSession(
        positions = positions,
        clock = clock,
        wallClockMs = { wallStartMs + scheduler.currentTime + wallOffsetMs },
        installed = installed,
        holdPackChanges = { if (holdBusy) null else { holds++; AutoCloseable { released++ } } },
        engines = EngineOpener { opens++; engine },
        followers = followers,
        settings = settings,
        store = store,
        rules = rules,
        presets = PresetSet.bundled(),
        bannerRules = BannerRules.bundled(),
        navDispatcher = StandardTestDispatcher(scheduler, "nav"),
        routingDispatcher = routingGate,
        doorOf = doorOf,
    )
    val events = mutableListOf<TripEvent>()
    val rows = mutableListOf<Pair<Fix, TripState>>()

    init {
        test.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { session.events.collect { events += it } }
        // the browsing puck: keeps the shared source collecting, so `latest` is current (as in the app)
        if (puck) test.backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) { positions.fixes().collect { } }
        session.core.afterFix = { f, s -> rows += f to s }
        run()
    }

    val state: TripState get() = session.state.value

    fun send(i: NavIntent) {
        session.send(i)
        run()
    }

    fun run() = scheduler.runCurrent()

    fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    /** Pushes one fix now and lets the session handle it. */
    fun fix(f: Fix) {
        pushed.flow.tryEmit(f)
        run()
    }

    fun gps(lat: Double, lon: Double, accuracy: Float = 5f, speed: Float? = 20f, bearing: Float? = null, provider: String = "gps",
            ageMs: Long = 0) = fix(Fix(lat, lon, accuracy, scheduler.currentTime - ageMs, speed, bearing, provider))

    /** Plans the exit 24 trip from its origin (heading 57) to Downs Dr and waits for the choices. */
    fun planExit24(origin: Origin = Origin.FromPlace(Data.EXIT24_ORIGIN, 57.0)) {
        send(NavIntent.PlanTo(Data.DOWNS_DRIVE))
        send(NavIntent.SetOrigin(origin))
        advance(1_000)
    }

    fun eventsOf(type: Class<out TripEvent>) = events.filter { type.isInstance(it) }

    fun phase(): Phase = state.phase

    private val missingAtStart = MissingRequests.count()

    /**
     * Fails the test when a request was not recorded (see android/routing/scripts/record_session.py),
     * or when the session caught an unexpected exception (SessionCore.failures: it survives them on
     * the phone, a test must not have any).
     */
    fun assertAllRecorded() {
        check(MissingRequests.count() == missingAtStart) {
            "${MissingRequests.count() - missingAtStart} engine request(s) not recorded: run " +
                ".venv/routing/Scripts/python android/routing/scripts/record_session.py (see its docstring)"
        }
        check(session.core.failures.isEmpty()) { "the session caught ${session.core.failures}" }
    }

    fun close() {
        session.close()
        run()
    }

    companion object {
        /** Every level-2 tile of Pennsylvania's and Ohio's boxes (an approximation of the PA+OH tile set, for covers()). */
        fun paOh(): RoutingSet {
            val tiles = HashSet<Int>()
            var lat = 38.375
            while (lat <= 42.375) {
                var lon = -84.875
                while (lon <= -74.625) {
                    tiles += RoutingSet.level2TileId(GeoPoint(lat, lon))
                    lon += 0.25
                }
                lat += 0.25
            }
            return RoutingSet("valhalla.json", "261114-pa-oh", "3.9.1", listOf("PA", "OH"), tiles)
        }
    }
}

/**
 * The bundled rules without G8's lead (no speed is fast enough for it): the tests of the other rules
 * check where a fix itself is drawn; LeadTest checks the lead with the bundled rules.
 */
fun GuidanceRules.withoutLead(): GuidanceRules = copy(lead = lead.copy(minSpeedMps = Double.POSITIVE_INFINITY))
