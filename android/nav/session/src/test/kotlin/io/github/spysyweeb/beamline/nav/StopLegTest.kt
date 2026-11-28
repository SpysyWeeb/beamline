package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.Guidance
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.RouteChoice
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Item 51 (rules C1, C8): where a new stop goes, the leg it joins and the point its detour is measured back to ([StopLeg]). */
class StopLegTest {
    private val s = Place("Wegmans", GeoPoint(42.07, -80.10), PlaceSource.SEARCH)
    private val d = Place("Erie Zoo", GeoPoint(42.11, -80.07), PlaceSource.SEARCH)
    private val start = Place("Exit 24 start", GeoPoint(42.03, -80.13), PlaceSource.MAP_PIN)

    /** A line of [n] points going north from 42.0, -80.0, [stepM] apart. */
    private fun north(n: Int, stepM: Double = 100.0): List<GeoPoint> = List(n) { i -> GeoPoint(42.0 + i * stepM / 111_195.0, -80.0) }

    private fun choice(id: Long, geometry: List<GeoPoint>, legEnds: List<Int>) = RouteChoice(
        id = id, typeIds = listOf("fastest"), lengthM = 1.0, timeS = 1.0, turnCount = 0, hasTolls = false, hasHighways = false,
        via = emptyList(), geometry = geometry, legEnds = legEnds, bounds = Bounds(0.0, 0.0, 0.0, 0.0), warnings = emptyList(),
    )

    private fun guided(route: RouteChoice, cursor: RouteCursor?, stops: List<TripStop>) = TripState(
        phase = Phase.GUIDING, stops = stops,
        guidance = Guidance(route, null, null, cursor, 0, 0, 0.0, 0.0, 0.0, 0.0, false, 0, false),
    )

    @Test
    fun `(a) guiding with two legs and the car on the first - the line is the last leg`() {
        val g = north(20)
        val trip = guided(choice(1, g, listOf(9, 19)), RouteCursor(4, g[4]), listOf(TripStop(s), TripStop(d)))
        assertEquals(g.subList(9, 20), StopLeg.lineAhead(trip))
    }

    @Test
    fun `(b) guiding on the last leg - the line starts at the car`() {
        val g = north(20)
        val car = GeoPoint(g[12].lat + 0.0001, g[12].lon)
        val trip = guided(choice(1, g, listOf(9, 19)), RouteCursor(12, car), listOf(TripStop(s, reached = true), TripStop(d)))
        assertEquals(listOf(car) + g.subList(13, 20), StopLeg.lineAhead(trip))
        // one leg, no progress yet: the whole route
        assertEquals(g, StopLeg.lineAhead(guided(choice(1, g, listOf(19)), null, listOf(TripStop(d)))))
        // re-routing: the same line
        assertEquals(listOf(car) + g.subList(13, 20),
            StopLeg.lineAhead(guided(choice(1, g, listOf(9, 19)), RouteCursor(12, car), listOf(TripStop(d))).copy(phase = Phase.REROUTING)))
    }

    @Test
    fun `(c) choosing - the selected card's last leg, else the first card's`() {
        val a = north(10)
        val b = north(30)
        val trip = TripState(phase = Phase.CHOOSING, stops = listOf(TripStop(s), TripStop(d)),
            choices = listOf(choice(1, a, listOf(4, 9)), choice(2, b, listOf(14, 29))), selectedChoiceId = 2)
        assertEquals(b.subList(14, 30), StopLeg.lineAhead(trip))
        assertEquals(a.subList(4, 10), StopLeg.lineAhead(trip.copy(selectedChoiceId = null)))
    }

    @Test
    fun `(d) planning without a card - the chosen start and the destination`() {
        val trip = TripState(phase = Phase.PLANNING, origin = Origin.FromPlace(start), stops = listOf(TripStop(d)))
        assertEquals(listOf(start.point, d.point), StopLeg.lineAhead(trip))
        // with a stop before the destination the line starts there; from the position (not known here): nothing
        assertEquals(listOf(s.point, d.point), StopLeg.lineAhead(trip.copy(stops = listOf(TripStop(s), TripStop(d)))))
        assertEquals(emptyList<GeoPoint>(), StopLeg.lineAhead(trip.copy(origin = Origin.CurrentPosition)))
    }

    @Test
    fun `(e) no trip, arrived, offered or no stop - no line`() {
        val g = north(20)
        val stops = listOf(TripStop(d))
        assertEquals(emptyList<GeoPoint>(), StopLeg.lineAhead(TripState()))
        assertEquals(emptyList<GeoPoint>(), StopLeg.lineAhead(guided(choice(1, g, listOf(19)), null, stops).copy(phase = Phase.ARRIVED)))
        assertEquals(emptyList<GeoPoint>(), StopLeg.lineAhead(TripState(phase = Phase.RESUME_OFFERED, origin = Origin.FromPlace(start), stops = stops)))
        assertEquals(emptyList<GeoPoint>(), StopLeg.lineAhead(TripState(phase = Phase.CHOOSING, origin = Origin.FromPlace(start))))
    }

    @Test
    fun `(f) insert index - before the destination, after the stops reached (SessionCore addStop)`() {
        val sr = TripStop(s, reached = true)
        assertEquals(0, StopLeg.insertIndex(emptyList()))
        assertEquals(0, StopLeg.insertIndex(listOf(TripStop(d))))
        assertEquals(1, StopLeg.insertIndex(listOf(TripStop(s), TripStop(d))))
        assertEquals(1, StopLeg.insertIndex(listOf(sr, TripStop(d))))
        assertEquals(2, StopLeg.insertIndex(listOf(sr, TripStop(d, reached = true))))
    }

    @Test
    fun `(g) the stop a new stop goes after`() {
        val trip = TripState(phase = Phase.CHOOSING, stops = listOf(TripStop(s), TripStop(d)))
        assertEquals(s, StopLeg.joinsAfter(trip))
        assertNull(StopLeg.joinsAfter(trip.copy(stops = listOf(TripStop(s, reached = true), TripStop(d)))))
        assertNull(StopLeg.joinsAfter(trip.copy(stops = listOf(TripStop(d)))))
    }

    @Test
    fun `(h) the rejoin point is 10 km past the farthest place, the destination when nearer`() {
        val line = north(1001)                                              // 100 km going north
        fun at(km: Double, eastM: Double = 0.0) = GeoPoint(42.0 + km * 1000.0 / 111_195.0, -80.0 + eastM / (111_195.0 * kotlin.math.cos(Math.toRadians(42.0))))
        val (p, bearing) = StopLeg.rejoin(line, listOf(at(5.0, 800.0), at(20.0, -1500.0)), 10.0)!!
        val want = at(30.0)
        assertTrue(Geo.distanceM(LatLon(p.lat, p.lon), LatLon(want.lat, want.lon)) <= 50.0, "$p")
        assertEquals(0.0, bearing, 0.5)
        // a 25 km line: 30 km is past its end, the destination is the rejoin point
        assertNull(StopLeg.rejoin(north(251), listOf(at(5.0), at(20.0)), 10.0))
        assertNull(StopLeg.rejoin(listOf(at(1.0)), listOf(at(1.0)), 10.0), "a one-point line")
        // a line going east: its bearing there
        val east = List(300) { i -> GeoPoint(42.0, -80.0 + i * 100.0 / (111_195.0 * kotlin.math.cos(Math.toRadians(42.0)))) }
        assertEquals(90.0, StopLeg.rejoin(east, listOf(east[10]), 10.0)!!.second, 0.5)
    }

    @Test
    fun `the leg into a previewed stop`() {
        val g = north(30)
        assertEquals(g.subList(9, 20), StopLeg.lineToStop(choice(1, g, listOf(9, 19, 29))))
        assertEquals(g.subList(0, 15), StopLeg.lineToStop(choice(1, g, listOf(14, 29))))
        assertEquals(g, StopLeg.lineToStop(choice(1, g, listOf(29))))
    }
}
