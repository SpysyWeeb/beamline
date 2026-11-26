package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.ReplaceReason
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.routing.engine.EngineException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/** Rules P1-P5 (planning and Start) and R1-R3 (resume), docs/architecture.md 3.5 and 3.10, and trip.json. */
class PlanningAndResumeTest {

    /** A fix at the exit 24 origin, moving 10 m/s on bearing 57 (the trip the recordings hold). */
    private fun Harness.fixAtOrigin(ageMs: Long = 0, accuracy: Float = 8f) =
        gps(Data.EXIT24_ORIGIN.point.lat, Data.EXIT24_ORIGIN.point.lon, accuracy, 10f, 57f, ageMs = ageMs)

    @Test
    fun `P1 the origin is a recent fix with heading when moving, otherwise wait 15 s then NO_POSITION`() = sessionTest(puck = false) { h ->
        h.send(NavIntent.PlanTo(Data.DOWNS_DRIVE))
        h.advance(1_000)
        assertEquals(Phase.PLANNING, h.phase())
        assertEquals(GpsState.WAITING, h.state.gps, "collecting positions while it waits")
        h.advance(15_000)
        assertEquals(Phase.CHOOSING, h.phase())
        assertEquals(ProblemKind.NO_POSITION, h.state.problem!!.kind)
        assertEquals("No GPS position yet. Choose a start point or try again.", h.state.problem!!.text)
        assertEquals(GpsState.OFF, h.state.gps)
        // Retry, and a fix arrives while it waits: planned from it, with heading 57 and radius 60
        h.send(NavIntent.Retry)
        h.advance(2_000)
        h.fixAtOrigin()
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        val origin = h.engine.routeRequests.last { "format" !in it }["locations"]!!.jsonArray[0].jsonObject
        assertEquals(57, origin["heading"]!!.jsonPrimitive.int)
        assertEquals(60, origin["radius"]!!.jsonPrimitive.int)
    }

    /** Item 47: the trip the other way round, and back; the position as it was is the swapped destination. */
    @Test
    fun `a swap plans the trip the other way round, and a second swap plans it from the position again`() = sessionTest { h ->
        h.fixAtOrigin()
        h.send(NavIntent.PlanTo(Data.DOWNS_DRIVE))
        h.advance(2_000)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        h.send(NavIntent.SwapEnds)
        h.advance(2_000)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        assertEquals(Origin.FromPlace(Data.DOWNS_DRIVE), h.state.origin)
        val back = h.state.stops.single().place
        assertEquals(SessionCore.YOUR_POSITION, back.name)
        assertEquals(PlaceSource.MAP_PIN, back.source)
        assertEquals(Data.EXIT24_ORIGIN.point.lat, back.point.lat, 1e-9)
        assertEquals(Data.EXIT24_ORIGIN.point.lon, back.point.lon, 1e-9)
        assertTrue(h.state.choices.isNotEmpty(), "routes back")
        h.fixAtOrigin()
        h.send(NavIntent.SwapEnds)
        h.advance(2_000)
        assertEquals(Origin.CurrentPosition, h.state.origin)
        assertEquals(Data.DOWNS_DRIVE, h.state.stops.single().place)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        h.assertAllRecorded()
    }

    /** Item 47: no position, no swap (a trip from the position has nothing to end at). */
    @Test
    fun `a swap without a position says so and changes nothing`() = sessionTest(puck = false) { h ->
        h.send(NavIntent.PlanTo(Data.DOWNS_DRIVE))
        h.advance(16_000)
        assertEquals(ProblemKind.NO_POSITION, h.state.problem?.kind)
        h.send(NavIntent.SwapEnds)
        h.advance(500)
        assertEquals(Origin.CurrentPosition, h.state.origin)
        assertEquals(Data.DOWNS_DRIVE, h.state.stops.single().place)
        assertEquals(ProblemKind.NO_POSITION, h.state.problem?.kind)
    }

    /** Item 47: the place window's drive time: IDLE only, from a fix, no trip made, not asked twice, dropped with a trip. */
    @Test
    fun `an estimate gives the drive time to a place without making a trip`() = sessionTest { h ->
        // no fix yet: no answer, no waiting for one
        h.send(NavIntent.Estimate(Data.DOWNS_DRIVE))
        h.advance(1_000)
        assertNull(h.state.estimate)
        assertEquals(Phase.IDLE, h.phase())
        assertEquals(GpsState.OFF, h.state.gps)
        h.fixAtOrigin()
        h.send(NavIntent.Estimate(Data.DOWNS_DRIVE))
        h.advance(1_000)
        val e = h.state.estimate!!
        assertEquals(Data.DOWNS_DRIVE.point, e.point)
        assertTrue(e.timeS > 60 && e.lengthM > 500, "$e")
        assertEquals(Phase.IDLE, h.phase(), "no trip")
        assertTrue(h.state.stops.isEmpty())
        // asked again within a minute: not routed again
        val asked = h.engine.routeRequests.size
        h.fixAtOrigin()
        h.send(NavIntent.Estimate(Data.DOWNS_DRIVE))
        h.advance(1_000)
        assertEquals(asked, h.engine.routeRequests.size)
        // a trip: not answered while one exists, and the estimate goes when it ends
        h.send(NavIntent.PlanTo(Data.DOWNS_DRIVE))
        h.advance(2_000)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        val planned = h.engine.routeRequests.size
        h.send(NavIntent.Estimate(Data.EXIT24_ORIGIN))
        h.advance(1_000)
        assertEquals(planned, h.engine.routeRequests.size)
        h.send(NavIntent.End)
        h.advance(1_000)
        assertEquals(Phase.IDLE, h.phase())
        assertNull(h.state.estimate)
        h.assertAllRecorded()
    }

    @Test
    fun `P1 a fix older than 30 s or worse than 100 m is not an origin`() = sessionTest { h ->
        h.fixAtOrigin(ageMs = 31_000)
        h.send(NavIntent.PlanTo(Data.DOWNS_DRIVE))
        h.advance(1_000)
        assertEquals(Phase.PLANNING, h.phase(), "waiting: the latest fix is too old")
        h.fixAtOrigin(accuracy = 150f)
        h.advance(1_000)
        assertEquals(Phase.PLANNING, h.phase(), "waiting: too poor")
        h.fixAtOrigin()
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase())
        assertTrue(h.state.choices.isNotEmpty())
    }

    @Test
    fun `P2 every offered type in presets order, the state published after each answer`() = sessionTest { h ->
        h.routingGate.gated = true
        h.send(NavIntent.PlanTo(Data.DOWNS_DRIVE))
        h.send(NavIntent.SetOrigin(io.github.spysyweeb.beamline.api.nav.Origin.FromPlace(Data.EXIT24_ORIGIN, 57.0)))
        h.advance(400)
        assertTrue(h.state.replanning)
        h.routingGate.release()
        h.routingGate.gated = true
        h.run()
        assertEquals(Phase.PLANNING, h.phase(), "one type answered")
        assertEquals(listOf(listOf("fastest")), h.state.choices.map { it.typeIds })
        h.routingGate.release()
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase())
        // every_request costing options (presets.json, batch C1) go on every request; the types differ in the rest
        val every = io.github.spysyweeb.beamline.routing.preset.PresetSet.bundled().everyRequest.costingOptions.keys
        val costings = h.engine.routeRequests.filter { "format" !in it }.map { it["costing_options"]!!.jsonObject["auto"]!!.jsonObject.keys - every }
        assertEquals(setOf<String>(), costings.first(), "Fastest first")
        assertTrue(costings.drop(1).any { it == setOf("maneuver_penalty") }, "then Simpler's pool")
        assertFalse(h.state.offeredTypes.any { it.id == "eco" }, "Eco is never offered")
        assertEquals(listOf("fastest", "simpler"), h.state.offeredTypes.map { it.id }, "Shortest left in batch D")
    }

    @Test
    fun `P3 the same route from several types is one choice with every type id`() = sessionTest { h ->
        h.planExit24()
        val ids = h.state.choices.flatMap { it.typeIds }
        assertEquals(listOf("fastest", "simpler").sorted(), ids.sorted(), "each type once")
        assertTrue(h.state.choices.size < 2 || h.state.choices.map { it.lengthM }.distinct().size == 2)
        h.state.choices.forEach { c -> assertEquals(c.typeIds, listOf("fastest", "simpler").filter { it in c.typeIds }, "presets order") }
        // the exit 24 trip: Simpler gives Fastest's route
        assertTrue(h.state.choices.any { it.typeIds.containsAll(listOf("fastest", "simpler")) }, "${h.state.choices.map { it.typeIds }}")
    }

    @Test
    fun `P4 the trip's type comes from settings when offered, else the default, all failing is the first type's problem`() = sessionTest { h ->
        h.settings.update { it.copy(routeType = "simpler") }
        h.planExit24()
        assertEquals("simpler", h.state.routeType)
        assertTrue("simpler" in h.state.choices.first { it.id == h.state.selectedChoiceId }.typeIds)
        h.send(NavIntent.End)
        // a stored Shortest (batch D: no longer a type) is Fastest, in the trip and in settings
        h.settings.update { it.copy(routeType = "shortest") }
        h.planExit24()
        assertEquals("fastest", h.state.routeType, "a stored Shortest becomes Fastest")
        assertEquals("fastest", h.settings.settings.value.routeType)
        assertTrue(h.engine.routeRequests.none { (it["costing_options"]?.jsonObject?.get("auto") as? kotlinx.serialization.json.JsonObject)?.get("shortest") != null })
        h.send(NavIntent.End)
        h.settings.update { it.copy(routeType = "eco") }
        h.planExit24()
        assertEquals("fastest", h.state.routeType, "Eco is not offered: the presets' default")
        h.send(NavIntent.End)
        h.engine.failWhen = { EngineException(171, "No suitable edges near location") }
        h.planExit24()
        assertEquals(Phase.CHOOSING, h.phase())
        assertTrue(h.state.choices.isEmpty())
        assertEquals(ProblemKind.NO_ROAD_NEAR_STOP, h.state.problem!!.kind)
        assertTrue(h.state.problem!!.canRetry)
    }

    @Test
    fun `P5 Start takes the hold first, asks the OSRM form, saves trip json, GUIDING and GuidanceStarted`() = sessionTest { h ->
        h.planExit24()
        h.holdBusy = true
        h.send(NavIntent.Start())
        assertEquals(Phase.CHOOSING, h.phase())
        assertEquals(ProblemKind.PACKS_BUSY, h.state.problem!!.kind)
        assertEquals("A state is being installed. Start again when it has finished.", h.state.problem!!.text)
        h.holdBusy = false
        h.send(NavIntent.Start())
        assertEquals(Phase.GUIDING, h.phase())
        assertEquals(1, h.holds)
        assertEquals("osrm", h.engine.routeRequests.last()["format"]!!.jsonPrimitive.content)
        assertNotNull(h.store.load())
        val started = h.events.filterIsInstance<TripEvent.GuidanceStarted>().single()
        assertEquals(h.state.guidance!!.route.id, started.routeId)
        assertFalse(started.simulated)
        assertTrue(h.state.choices.isEmpty())
        h.send(NavIntent.End)
        assertEquals(Phase.IDLE, h.phase())
        assertEquals(1, h.released)
        assertNull(h.store.load())
    }

    @Test
    fun `P5 a simulated drive starts on the route and ignores real fixes`() = sessionTest { h ->
        h.planExit24()
        h.send(NavIntent.Start(Simulation()))
        assertTrue(h.events.filterIsInstance<TripEvent.GuidanceStarted>().single().simulated)
        h.advance(3_000)
        val along = h.state.vehicleAlong()
        val far = EXIT24.offset(3000.0, 400.0)
        h.gps(far.lat, far.lon)                                // a real fix far away
        assertEquals(along, h.state.vehicleAlong(), 1e-6)
        assertFalse(h.state.guidance!!.offRouteSuspected)
        assertTrue(h.state.guidance!!.simulated)
        assertTrue(h.rows.all { it.first.provider == "simulated" })
    }

    // ---------------------------------------------------------------------------------- R

    @Test
    fun `R1 trip json at Start, every 30 s while guiding, deleted on End`() = sessionTest { h ->
        h.guideExit24()
        val first = h.store.load()!!
        assertEquals((h.wallStartMs + h.scheduler.currentTime).toDouble(), first.savedEpochMs.toDouble(), 1_000.0)
        assertEquals(listOf("Downs Drive"), first.stops.map { it.place.name })
        h.drive(500.0, 500.0 + 25.0 * 31)
        val later = h.store.load()!!
        assertTrue(later.savedEpochMs >= first.savedEpochMs + 30_000, "rewritten every resume.refresh_s")
        h.send(NavIntent.End)
        assertNull(h.store.load())
    }

    private fun snapshotAt(wallMs: Long, type: String = "fastest") =
        TripSnapshot(wallMs, listOf(TripStop(Data.DOWNS_DRIVE)), type, RouteOptions())

    @Test
    fun `R2 a 45-minute trip killed at minute 40 - offered 10 minutes later, deleted 31 minutes later`() = runTest {
        val start = 1_763_280_000_000L
        val killedAt = start + 40 * 60_000L                   // the last write of the 45-minute trip
        val dir1 = Files.createTempDirectory("r2a").toFile()
        TripStore(File(dir1, "trip.json")).save(snapshotAt(killedAt))
        val offered = Harness(this, dir = dir1, wallStartMs = killedAt + 10 * 60_000L)
        try {
            assertEquals(Phase.RESUME_OFFERED, offered.phase())
            assertEquals(listOf("Downs Drive"), offered.state.stops.map { it.place.name })
        } finally {
            offered.close()
        }
        val dir2 = Files.createTempDirectory("r2b").toFile()
        TripStore(File(dir2, "trip.json")).save(snapshotAt(killedAt))
        val late = Harness(this, dir = dir2, wallStartMs = killedAt + 31 * 60_000L)
        try {
            assertEquals(Phase.IDLE, late.phase())
            assertNull(late.store.load(), "older than resume.max_age_s: deleted")
        } finally {
            late.close()
        }
    }

    @Test
    fun `R2 a snapshot written at a later wall-clock reading (the clock stepped back since) is offered, not deleted`() = runTest {
        val wall = 1_763_280_000_000L
        val dir = Files.createTempDirectory("r2c").toFile()
        TripStore(File(dir, "trip.json")).save(snapshotAt(wall + 2_000L))   // a network time correction of 2 s after the process died
        val h = Harness(this, dir = dir, wallStartMs = wall)
        try {
            assertEquals(Phase.RESUME_OFFERED, h.phase(), "age -2 s is within resume.max_age_s")
            assertNotNull(h.store.load())
        } finally {
            h.close()
        }
    }

    @Test
    fun `R3 Resume re-plans the trip's type from the current position and guides - GuidanceStarted then RouteReplaced(RESUMED)`() = runTest {
        val dir = Files.createTempDirectory("r3").toFile()
        val wall = 1_763_280_000_000L
        TripStore(File(dir, "trip.json")).save(snapshotAt(wall - 60_000L, type = "simpler"))
        val h = Harness(this, dir = dir, wallStartMs = wall)
        try {
            assertEquals(Phase.RESUME_OFFERED, h.phase())
            assertEquals("simpler", h.state.routeType)
            h.fixAtOrigin()
            h.send(NavIntent.Resume)
            h.advance(500)
            assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
            val kinds = h.events.map { it::class.simpleName }
            assertEquals(listOf("GuidanceStarted", "RouteReplaced"), kinds)
            assertEquals(ReplaceReason.RESUMED, (h.events[1] as TripEvent.RouteReplaced).reason)
            assertTrue(h.engine.routeRequests.filter { "format" !in it }.any { "maneuver_penalty" in it["costing_options"]!!.jsonObject["auto"]!!.jsonObject },
                "the trip's Simpler (C7)")
            assertEquals("simpler", h.store.load()!!.routeType)
            assertEquals(1, h.holds)
            h.assertAllRecorded()
        } finally {
            h.close()
        }
    }

    @Test
    fun `R3 a trip saved with Shortest resumes as Fastest - Shortest left in batch D`() = runTest {
        val dir = Files.createTempDirectory("r3-shortest").toFile()
        val wall = 1_763_280_000_000L
        TripStore(File(dir, "trip.json")).save(snapshotAt(wall - 60_000L, type = "shortest"))
        val h = Harness(this, dir = dir, wallStartMs = wall)
        try {
            assertEquals(Phase.RESUME_OFFERED, h.phase())
            assertEquals("fastest", h.state.routeType)
            h.fixAtOrigin()
            h.send(NavIntent.Resume)
            h.advance(500)
            assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
            val every = io.github.spysyweeb.beamline.routing.preset.PresetSet.bundled().everyRequest.costingOptions.keys
            val req = h.engine.routeRequests.first { "format" !in it }
            assertEquals(setOf<String>(), req["costing_options"]!!.jsonObject["auto"]!!.jsonObject.keys - every, "Fastest's request")
            assertEquals("fastest", h.store.load()!!.routeType)
            h.assertAllRecorded()
        } finally {
            h.close()
        }
    }

    @Test
    fun `R1 DiscardResume deletes trip json`() = runTest {
        val dir = Files.createTempDirectory("r1d").toFile()
        val wall = 1_763_280_000_000L
        TripStore(File(dir, "trip.json")).save(snapshotAt(wall - 60_000L))
        val h = Harness(this, dir = dir, wallStartMs = wall)
        try {
            h.send(NavIntent.DiscardResume)
            assertEquals(Phase.IDLE, h.phase())
            assertNull(h.store.load())
        } finally {
            h.close()
        }
    }

    @Test
    fun `trip json round trip keeps saved places (PlaceSource SAVED, savedId) and the reached flags`() {
        val dir = Files.createTempDirectory("trip").toFile()
        val store = TripStore(File(dir, "trip.json"))
        assertNull(store.load())
        val home = Place("Home", GeoPoint(42.1292, -80.0851), PlaceSource.SAVED, subtitle = "Perry Square, Erie", savedId = "s0123456789abcdef")
        val search = Place("Erie Maritime Museum", GeoPoint(42.1365, -80.0870), PlaceSource.SEARCH, "150 East Front Street", "museum", 42L, "us-pa")
        val snap = TripSnapshot(1_763_280_000_000L, listOf(TripStop(search, reached = true), TripStop(home)), "simpler", RouteOptions(true, false))
        store.save(snap)
        assertEquals(snap, store.load())
        val text = File(dir, "trip.json").readText()
        assertTrue(text.contains("\"format\":\"beamline-trip/1\""))
        assertTrue(text.contains("\"saved_id\":\"s0123456789abcdef\""))
        assertFalse(File(dir, "trip.json.tmp").exists())
        // a broken or foreign file is no trip
        File(dir, "trip.json").writeText("{\"format\":\"beamline-trip/2\"}")
        assertNull(store.load())
        File(dir, "trip.json").writeText("not json")
        assertNull(store.load())
        store.save(snap)
        store.clear()
        assertNull(store.load())
        assertFalse(File(dir, "trip.json").exists())
        // the redacted toString of :core:api: no name or point of the saved place (PV2)
        assertFalse(snap.toString().contains("Home") || snap.toString().contains("42.1292"))
    }
}
