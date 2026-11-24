package io.github.spysyweeb.beamline.app

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.RoutingSet
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.feature.drive.BannerUi
import io.github.spysyweeb.beamline.feature.drive.DrivePresenter
import io.github.spysyweeb.beamline.feature.drive.LaneUi
import io.github.spysyweeb.beamline.feature.drive.ThenUi
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.location.PositionSource
import io.github.spysyweeb.beamline.location.SharedPositionSource
import io.github.spysyweeb.beamline.nav.BeamlineNavigationSession
import io.github.spysyweeb.beamline.nav.EngineOpener
import io.github.spysyweeb.beamline.nav.GeometricFollowerFactory
import io.github.spysyweeb.beamline.nav.GuidanceRules
import io.github.spysyweeb.beamline.nav.TripStore
import io.github.spysyweeb.beamline.routing.banner.BannerRules
import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.routing.valhalla.canonicalJson
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.zip.GZIPInputStream

/**
 * Track D's request (data/r3-board/d-banner-roads.md, report 15): the REAL navigation session's
 * banners through DrivePresenter on the exit 24 trip, against the sketch's three frames
 * (docs/design/turn-banner.html; section 8 rows 3, 5 and 8). D's GoldenTraceTest could only use
 * hand-written roads, kinds and lanes, because :feature:drive may not see :nav:session; :app sees
 * both. The session runs on a virtual clock with N's recorded Valhalla answers (pyvalhalla 3.9.1 on
 * the PA+OH tiles, android/routing/testdata), N's geometric follower and the `default` script.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealSessionBannersTest {

    private val testdata = File(System.getProperty("beamline.appDir") ?: ".", "../routing/testdata")

    private fun json(name: String): JsonObject {
        val f = File(testdata, name)
        val text = (if (name.endsWith(".gz")) GZIPInputStream(f.inputStream()) else f.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        return Json.parseToJsonElement(text).jsonObject
    }

    /** Answers route requests from the recordings (a request is its canonical JSON), as N's tests do. */
    private inner class Recorded : RoutingEngine {
        private val answers: Map<String, JsonObject> = buildMap {
            for (f in listOf("reference/banner-routes.json.gz", "reference/recordings.json.gz", "reference/recordings-plans.json.gz", "nav/session-recordings.json.gz")) {
                if (File(testdata, f).isFile) json(f)["recordings"]!!.jsonArray.forEach { r -> put(canonicalJson(r.jsonObject["request"]!!), r.jsonObject) }
            }
            val osrm = json("reference/exit24-osrm.json")
            put(canonicalJson(osrm["request"]!!), JsonObject(mapOf("request" to osrm["request"]!!, "response" to osrm["response"]!!)))
        }
        var missing = 0

        private fun answer(requestJson: String): String {
            val r = answers[canonicalJson(Json.parseToJsonElement(requestJson))] ?: run {
                missing++
                throw EngineException(null, "request not recorded")
            }
            r["error"]?.jsonObject?.let { throw EngineException(it["code"]!!.jsonPrimitive.int, it["message"]!!.jsonPrimitive.content) }
            return r["response"].toString()
        }

        override fun route(requestJson: String): String = answer(requestJson)
        override fun matrix(requestJson: String): String = answer(requestJson)
    }

    /** Every level-2 tile of Pennsylvania's and Ohio's boxes (as N's harness). */
    private fun paOh(): RoutingSet {
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

    @Test
    fun theRealSessionsBannersGiveTheSketchFrames() = runTest {
        val dir = Files.createTempDirectory("real-banners").toFile()
        val engine = Recorded()
        val noFixes = object : PositionSource {
            override fun fixes(): Flow<Fix> = emptyFlow()
        }
        val session = BeamlineNavigationSession(
            positions = SharedPositionSource(noFixes, backgroundScope),
            clock = Clock { testScheduler.currentTime },
            wallClockMs = { 1_763_280_000_000L + testScheduler.currentTime },
            installed = MutableStateFlow(InstalledSet(generation = 1, scanned = true, routing = paOh())),
            holdPackChanges = { AutoCloseable { } },
            engines = EngineOpener { engine },
            followers = GeometricFollowerFactory(),
            settings = SettingsStore(File(dir, "settings.json")),
            store = TripStore(File(dir, "trip.json")),
            rules = GuidanceRules.bundled(),
            presets = PresetSet.bundled(),
            bannerRules = BannerRules.bundled(),
            navDispatcher = StandardTestDispatcher(testScheduler, "nav"),
            routingDispatcher = StandardTestDispatcher(testScheduler, "routing"),
        )
        val units = UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL)
        fun banner(s: TripState): BannerUi? = DrivePresenter.present(
            s, units, 1_763_280_000_000L, ZoneId.of("America/New_York"), CameraState(), false, null, true,
        ).guidance?.banner

        // The session ticks once a second on the test's scheduler: close it whatever happens, or a
        // failed assertion would leave runTest advancing the virtual clock for ever.
        try {
            drive(session, engine, ::banner)
        } finally {
            session.close()
            testScheduler.runCurrent()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.drive(session: BeamlineNavigationSession, engine: Recorded, banner: (TripState) -> BannerUi?) {
        session.send(NavIntent.PlanTo(Place("Downs Drive", GeoPoint(42.053308, -80.079579), PlaceSource.MAP_PIN)))
        session.send(NavIntent.SetOrigin(Origin.FromPlace(Place("Exit 24 start", GeoPoint(42.028694, -80.128884), PlaceSource.MAP_PIN), 57.0)))
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        assertEquals(Phase.CHOOSING, session.state.value.phase, "the plan from the recorded answers")
        session.send(NavIntent.Start(Simulation(scriptName = "default")))
        testScheduler.runCurrent()

        // Every distinct banner of the drive, second by second (the default script: 1 fix/s, a 20 s halt).
        val seen = mutableListOf<BannerUi>()
        var t = 0
        while (session.state.value.phase != Phase.ARRIVED && t < 600) {
            testScheduler.advanceTimeBy(1_000)
            testScheduler.runCurrent()
            banner(session.state.value)?.let { if (seen.lastOrNull() != it) seen += it }
            t++
        }
        // Rule D1: the real banners keep short names on one line (no-break spaces); compared as plain text below.
        assertTrue(seen.any { it.roadsText == "onto US 19 / Peach St" }, "no-break spaces inside the short names")
        seen.replaceAll { it.copy(primary = it.primary.replace(' ', ' '), roadsText = it.roadsText?.replace(' ', ' '),
            towardsText = it.towardsText?.replace(' ', ' ')) }
        assertEquals(Phase.ARRIVED, session.state.value.phase, "the simulated drive arrives")
        assertEquals(0, engine.missing, "every engine request was recorded")

        // Frame 1 (section 8 row 3): on I-90, 0.5 mi before the exit.
        val f1 = seen.first { it.primary == "Exit 24" && it.distanceText == "0.5 mi" }
        assertEquals("US 19 / Peach St", f1.roadsText)
        assertEquals("toward Waterford", f1.towardsText)
        assertEquals(ThenUi(ManeuverKind.LEFT, "In 0.3 mi"), f1.then)
        assertNull(f1.lanes)

        // Frame 2 (row 5): on the ramp, 400 ft before the left turn.
        val f2 = seen.first { it.primary == "Turn left" && it.distanceText == "400 ft" }
        assertEquals(ManeuverKind.LEFT, f2.kind)
        assertEquals("onto US 19 / Peach St", f2.roadsText)
        assertEquals(ThenUi(ManeuverKind.RIGHT, "In 0.2 mi"), f2.then)
        assertEquals(
            listOf(
                LaneUi(setOf(LaneArrow.LEFT), LaneMark.ALLOWED, LaneArrow.LEFT),
                LaneUi(setOf(LaneArrow.LEFT, LaneArrow.THROUGH), LaneMark.BEST, LaneArrow.LEFT),
                LaneUi(setOf(LaneArrow.RIGHT), LaneMark.NOT_FOR_ROUTE, null),
            ),
            f2.lanes,
        )

        // Frame 3 (row 8): on Peach St, 0.2 mi before Downs Dr; no chip (arriving is not a turn).
        val f3 = seen.first { it.primary == "Turn right" && it.distanceText == "0.2 mi" }
        assertEquals(ManeuverKind.RIGHT, f3.kind)
        assertEquals("onto Downs Dr", f3.roadsText)
        assertNull(f3.then)
        val lanes = f3.lanes
        assertNotNull(lanes)
        assertEquals(listOf(LaneMark.NOT_FOR_ROUTE, LaneMark.NOT_FOR_ROUTE, LaneMark.NOT_FOR_ROUTE, LaneMark.BEST), lanes!!.map { it.mark })
        assertEquals(LaneUi(setOf(LaneArrow.THROUGH, LaneArrow.RIGHT), LaneMark.BEST, LaneArrow.RIGHT), lanes.last())

        // Section 8 "throughout": the shown distance never goes up within one maneuver.
        seen.zipWithNext().filter { (a, b) -> a.primary == b.primary && a.roadsText == b.roadsText }.forEach { (a, b) ->
            val m = { s: String -> s.split(" ").let { (n, u) -> n.toDouble() * if (u == "mi") 1609.344 else 0.3048 } }
            assert(m(b.distanceText) <= m(a.distanceText) + 1e-6) { "${a.primary}: ${a.distanceText} then ${b.distanceText}" }
        }
    }
}
