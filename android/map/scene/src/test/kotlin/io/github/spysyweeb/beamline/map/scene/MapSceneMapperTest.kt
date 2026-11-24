package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.map.PinKind
import io.github.spysyweeb.beamline.api.map.VehicleMarker
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.location.PuckLook
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** docs/architecture.md 5.1 M "Done when": the mapper (choosing, guiding, traveled cursor, lenses, stale, pins, glideMs). */
class MapSceneMapperTest {
    private val rules = SavedMarkerRules(homeWorkMinZoom = 10.0, otherMinZoom = 12.0, iconScale = 1.25)

    private fun arrow(s: io.github.spysyweeb.beamline.api.map.MapScene) = s.marker as VehicleMarker.Arrow

    @Test
    fun `idle draws the puck only`() {
        val s = MapSceneMapper(rules).scene(TripState(), Fx.puck(Fx.ORIGIN), MapTheme.DAY, picked = null)
        assertNull(s.route)
        assertTrue(s.pins.isEmpty())
        assertEquals(VehicleMarker.Puck(Fx.ORIGIN, 30.0, stale = false), s.marker)
        assertEquals(MapTheme.DAY, s.theme)
        assertFalse(s.savedBelowRoute)
    }

    @Test
    fun `no puck and no trip draws nothing`() {
        val s = MapSceneMapper(rules).scene(TripState(), null, MapTheme.NIGHT, null)
        assertNull(s.marker)
        assertEquals(MapTheme.NIGHT, s.theme)
    }

    @Test
    fun `a grey puck is stale and has no cone`() {
        val s = MapSceneMapper(rules).scene(TripState(), Fx.puck(Fx.ORIGIN, cone = 120f, look = PuckLook.GREY), MapTheme.DAY, null)
        assertEquals(VehicleMarker.Puck(Fx.ORIGIN, coneDeg = null, stale = true), s.marker)
        val noCone = MapSceneMapper(rules).scene(TripState(), Fx.puck(Fx.ORIGIN, cone = null), MapTheme.DAY, null)
        assertEquals(VehicleMarker.Puck(Fx.ORIGIN, coneDeg = null, stale = false), noCone.marker)
    }

    @Test
    fun `choosing draws the selected choice and the others as alternatives`() {
        val s = MapSceneMapper(rules).scene(Fx.choosing(selected = 2), Fx.puck(Fx.ORIGIN), MapTheme.DAY, null)
        val r = s.route!!
        assertEquals(2L, r.chosenId)
        assertEquals(Fx.ALT, r.chosen)
        assertNull(r.traveled)
        assertEquals(listOf(1L), r.alternatives.map { it.id })
        assertEquals(Fx.ROUTE, r.alternatives.single().points)
        // the puck, not the arrow, while choosing (K7)
        assertTrue(s.marker is VehicleMarker.Puck)
        assertFalse(s.savedBelowRoute)
    }

    @Test
    fun `choosing without a selection draws the first choice`() {
        val s = MapSceneMapper(rules).scene(Fx.choosing(selected = null), null, MapTheme.DAY, null)
        assertEquals(1L, s.route!!.chosenId)
        assertEquals(listOf(2L), s.route!!.alternatives.map { it.id })
    }

    @Test
    fun `planning keeps the last choices and draws none before the first`() {
        val kept = MapSceneMapper(rules).scene(Fx.choosing(phase = Phase.PLANNING), null, MapTheme.DAY, null)
        assertEquals(1L, kept.route!!.chosenId)
        val first = MapSceneMapper(rules).scene(Fx.choosing(phase = Phase.PLANNING).copy(choices = emptyList(), selectedChoiceId = null), null, MapTheme.DAY, null)
        assertNull(first.route)
        assertEquals(listOf(PinKind.DESTINATION), first.pins.map { it.kind })
    }

    @Test
    fun `the puck carries its uncertainty bubble, by day and night, and none while guiding (G7)`() {
        val poor = Fx.puck(Fx.ORIGIN).copy(accuracyM = 100f, bubbleM = 72f)
        for (theme in MapTheme.entries) {
            val s = MapSceneMapper(rules).scene(TripState(), poor, theme, null)
            assertEquals(VehicleMarker.Puck(Fx.ORIGIN, 30.0, stale = false, bubbleM = 72.0), s.marker)
        }
        val grey = MapSceneMapper(rules).scene(TripState(), poor.copy(look = PuckLook.GREY), MapTheme.DAY, null)
        assertEquals(VehicleMarker.Puck(Fx.ORIGIN, null, stale = true, bubbleM = 72.0), grey.marker)
        // A good fix: no bubble.
        assertNull((MapSceneMapper(rules).scene(TripState(), Fx.puck(Fx.ORIGIN), MapTheme.DAY, null).marker as VehicleMarker.Puck).bubbleM)
        // Guiding: the arrow on the route, no bubble anywhere.
        val v = Fx.vehicle(GeoPoint(42.0350, -80.1120), ms = 10_000, bearing = 62.5)
        assertTrue(MapSceneMapper(rules).scene(Fx.guiding(v), poor, MapTheme.NIGHT, null).marker is VehicleMarker.Arrow)
    }

    @Test
    fun `guiding draws the route with its traveled cursor and the arrow`() {
        val v = Fx.vehicle(GeoPoint(42.0350, -80.1120), ms = 10_000, bearing = 62.5)
        val cursor = RouteCursor(1, GeoPoint(42.0350, -80.1120))
        val s = MapSceneMapper(rules).scene(Fx.guiding(v, traveled = cursor), Fx.puck(Fx.ORIGIN), MapTheme.DAY, null)
        val r = s.route!!
        assertEquals(1L, r.chosenId)
        assertEquals(Fx.ROUTE, r.chosen)
        assertEquals(cursor, r.traveled)
        assertTrue(r.alternatives.isEmpty())
        assertEquals(VehicleMarker.Arrow(v.point, 62.5, brakesLit = false, stale = false, glideMs = 0), s.marker)
        assertTrue(s.savedBelowRoute)
    }

    @Test
    fun `the traveled cursor follows the guidance`() {
        val m = MapSceneMapper(rules)
        val a = m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 1_000), traveled = RouteCursor(0, Fx.ROUTE[1])), null, MapTheme.DAY, null)
        val b = m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[3], 2_000), traveled = RouteCursor(2, Fx.ROUTE[3])), null, MapTheme.DAY, null)
        assertEquals(RouteCursor(0, Fx.ROUTE[1]), a.route!!.traveled)
        assertEquals(RouteCursor(2, Fx.ROUTE[3]), b.route!!.traveled)
        val none = m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[3], 3_000), traveled = null), null, MapTheme.DAY, null)
        assertNull(none.route!!.traveled)
    }

    @Test
    fun `rerouting keeps the old route drawn and arrived draws it too`() {
        val v = Fx.vehicle(Fx.ROUTE[2], 5_000)
        val rr = MapSceneMapper(rules).scene(Fx.guiding(v, phase = Phase.REROUTING), null, MapTheme.DAY, null)
        assertEquals(Fx.ROUTE, rr.route!!.chosen)
        assertTrue(rr.marker is VehicleMarker.Arrow)
        assertTrue(rr.savedBelowRoute)
        val arrived = MapSceneMapper(rules).scene(Fx.guiding(Fx.vehicle(Fx.DEST, 9_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED), null, MapTheme.DAY, null)
        assertEquals(Fx.ROUTE, arrived.route!!.chosen)
        assertTrue(arrived.marker is VehicleMarker.Arrow)
        assertFalse(arrived.savedBelowRoute)
    }

    @Test
    fun `the brake lenses are lit only when stopped and not stale (M3)`() {
        val m = MapSceneMapper(rules)
        val p = Fx.ROUTE[2]
        assertFalse(arrow(m.scene(Fx.guiding(Fx.vehicle(p, 1_000, motion = Motion.MOVING)), null, MapTheme.NIGHT, null)).brakesLit)
        assertTrue(arrow(m.scene(Fx.guiding(Fx.vehicle(p, 2_000, motion = Motion.STOPPED, speed = 0.0)), null, MapTheme.NIGHT, null)).brakesLit)
        assertFalse(arrow(m.scene(Fx.guiding(Fx.vehicle(p, 3_000, motion = Motion.UNKNOWN)), null, MapTheme.NIGHT, null)).brakesLit)
        // stopped but LOST: stale arrow, no lenses
        val lost = arrow(m.scene(Fx.guiding(Fx.vehicle(p, 4_000, motion = Motion.STOPPED, speed = 0.0), gps = GpsState.LOST), null, MapTheme.NIGHT, null))
        assertTrue(lost.stale)
        assertFalse(lost.brakesLit)
    }

    @Test
    fun `the arrow is stale only in LOST`() {
        val m = MapSceneMapper(rules)
        for (gps in GpsState.entries) {
            val a = arrow(m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[2], 1_000 + gps.ordinal * 1_000L), gps = gps), null, MapTheme.DAY, null))
            assertEquals(gps == GpsState.LOST, a.stale, gps.name)
        }
    }

    @Test
    fun `glideMs is the time between the last two positions`() {
        val m = MapSceneMapper(rules)
        fun at(i: Int, ms: Long) = arrow(m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[i], ms)), null, MapTheme.DAY, null))
        assertEquals(0, at(1, 10_000).glideMs)      // nothing to glide from
        assertEquals(1_000, at(2, 11_000).glideMs)
        assertEquals(500, at(3, 11_500).glideMs)    // 2 Hz
        // the same position seen again (a scene for another reason) keeps its glide
        assertEquals(500, at(3, 11_500).glideMs)
        val again = arrow(m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[3], 11_500)), null, MapTheme.NIGHT, Fx.downs))
        assertEquals(500, again.glideMs)
        // stopped: same point, later time
        assertEquals(1_000, at(3, 12_500).glideMs)
        // back after a gap longer than a glide (fixes back after LOST): a jump
        assertEquals(0, at(4, 47_500).glideMs)
        assertEquals(1_000, at(5, 48_500).glideMs)
    }

    @Test
    fun `glide starts again after the arrow went away`() {
        val m = MapSceneMapper(rules)
        m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 10_000)), null, MapTheme.DAY, null)
        m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[2], 11_000)), null, MapTheme.DAY, null)
        val idle = m.scene(TripState(), Fx.puck(Fx.ROUTE[2]), MapTheme.DAY, null)
        assertTrue(idle.marker is VehicleMarker.Puck)
        val next = arrow(m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 12_000)), null, MapTheme.DAY, null))
        assertEquals(0, next.glideMs)
    }

    @Test
    fun `an unknown course keeps the last bearing, or the route's first one`() {
        val m = MapSceneMapper(rules)
        val first = arrow(m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[0], 1_000, bearing = null)), null, MapTheme.DAY, null))
        assertEquals(startBearing(Fx.ROUTE)!!, first.bearingDeg, 1e-9)
        assertEquals(80.0, arrow(m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 2_000, bearing = 80.0)), null, MapTheme.DAY, null)).bearingDeg)
        assertEquals(80.0, arrow(m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 3_000, bearing = null, motion = Motion.STOPPED)), null, MapTheme.DAY, null)).bearingDeg)
        assertEquals(10.0, arrow(m.scene(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 4_000, bearing = 370.0)), null, MapTheme.DAY, null)).bearingDeg, 1e-9)
    }

    @Test
    fun `a guided trip before its first position shows the puck (K7)`() {
        val s = MapSceneMapper(rules).scene(Fx.guiding(null), Fx.puck(Fx.ORIGIN), MapTheme.DAY, null)
        assertTrue(s.marker is VehicleMarker.Puck)
        assertEquals(Fx.ROUTE, s.route!!.chosen)
    }

    @Test
    fun `pins - numbered stops, the destination, reached stops and the picked place`() {
        val a = Fx.place("Stop A", Fx.ROUTE[2])
        val b = Fx.place("Stop B", Fx.ROUTE[4])
        val trip = Fx.guiding(
            Fx.vehicle(Fx.ROUTE[3], 1_000),
            stops = listOf(TripStop(a, reached = true), TripStop(b), TripStop(Fx.downs)),
        )
        val picked = Fx.place("Sheetz", GeoPoint(42.0310, -80.1200))
        val pins = MapSceneMapper(rules).scene(trip, null, MapTheme.DAY, picked).pins
        assertEquals(listOf("stop:0", "stop:1", "stop:2", "picked"), pins.map { it.id })
        assertEquals(listOf(PinKind.STOP, PinKind.STOP, PinKind.DESTINATION, PinKind.PICKED), pins.map { it.kind })
        assertEquals(listOf(1, 2, null, null), pins.map { it.number })
        assertEquals(listOf(true, false, false, false), pins.map { it.reached })
        assertEquals(listOf(a.point, b.point, Fx.DEST, picked.point), pins.map { it.point })
    }

    @Test
    fun `a picked place alone is a PICKED pin`() {
        val pins = MapSceneMapper(rules).scene(TripState(), null, MapTheme.DAY, Fx.place("Dropped pin", Fx.MALL)).pins
        assertEquals(1, pins.size)
        assertEquals(PinKind.PICKED, pins[0].kind)
        assertEquals(Fx.MALL, pins[0].point)
        assertNull(pins[0].number)
    }

    // ---- saved places (docs/architecture-saved-places.md 10.1, :map:scene) ----------------

    @Test
    fun `no saved places gives no markers`() {
        assertTrue(MapSceneMapper(rules).scene(TripState(), null, MapTheme.DAY, null).saved.isEmpty())
    }

    @Test
    fun `three saved places give three markers with their kinds, zooms and scale from the bundled rules`() {
        val s = MapSceneMapper().scene(TripState(), null, MapTheme.DAY, null, listOf(Fx.home, Fx.work, Fx.star))
        assertEquals(listOf(SavedKind.HOME, SavedKind.WORK, SavedKind.OTHER), s.saved.map { it.kind })
        assertEquals(listOf(10.0, 10.0, 12.0), s.saved.map { it.minZoom })
        assertEquals(listOf(1.25, 1.25, 1.25), s.saved.map { it.scale })
        assertEquals(listOf(Fx.home.id, Fx.work.id, Fx.star.id), s.saved.map { it.savedId })
        assertEquals(listOf(Fx.MUSEUM, Fx.AIRPORT, Fx.MALL), s.saved.map { it.point })
    }

    @Test
    fun `a saved place that is a stop of the trip is left out`() {
        val trip = Fx.choosing(stops = listOf(TripStop(Fx.home.toPlace())))
        val s = MapSceneMapper(rules).scene(trip, null, MapTheme.DAY, null, listOf(Fx.home, Fx.work))
        assertEquals(listOf(Fx.work.id), s.saved.map { it.savedId })
        // its destination pin shows it
        assertEquals(PinKind.DESTINATION, s.pins.single().kind)
        // an intermediate stop, reached or not, is left out too
        val guided = Fx.guiding(
            Fx.vehicle(Fx.ROUTE[1], 1_000),
            stops = listOf(TripStop(Fx.star.toPlace(), reached = true), TripStop(Fx.home.toPlace())),
        )
        val g = MapSceneMapper(rules).scene(guided, null, MapTheme.DAY, null, listOf(Fx.home, Fx.work, Fx.star))
        assertEquals(listOf(Fx.work.id), g.saved.map { it.savedId })
    }

    @Test
    fun `a picked saved place keeps its marker`() {
        val s = MapSceneMapper(rules).scene(TripState(), null, MapTheme.DAY, Fx.home.toPlace(), listOf(Fx.home))
        assertEquals(listOf(Fx.home.id), s.saved.map { it.savedId })
        assertEquals(PinKind.PICKED, s.pins.single().kind)
    }

    @Test
    fun `savedBelowRoute is true in GUIDING and REROUTING only`() {
        val m = MapSceneMapper(rules)
        val v = Fx.vehicle(Fx.ROUTE[1], 1_000)
        val below = mapOf(
            Phase.IDLE to TripState(),
            Phase.PLANNING to Fx.choosing(phase = Phase.PLANNING),
            Phase.CHOOSING to Fx.choosing(),
            Phase.GUIDING to Fx.guiding(v),
            Phase.REROUTING to Fx.guiding(v, phase = Phase.REROUTING),
            Phase.ARRIVED to Fx.guiding(v, phase = Phase.ARRIVED),
            Phase.RESUME_OFFERED to TripState(phase = Phase.RESUME_OFFERED, stops = listOf(TripStop(Fx.downs))),
        ).mapValues { (_, t) -> m.scene(t, null, MapTheme.DAY, null, listOf(Fx.work)).savedBelowRoute }
        assertEquals(
            mapOf(
                Phase.IDLE to false, Phase.PLANNING to false, Phase.CHOOSING to false, Phase.GUIDING to true,
                Phase.REROUTING to true, Phase.ARRIVED to false, Phase.RESUME_OFFERED to false,
            ),
            below,
        )
    }

    @Test
    fun `a saved marker prints no point`() {
        val s = MapSceneMapper(rules).scene(TripState(), null, MapTheme.DAY, null, listOf(Fx.star))
        val text = s.saved.toString()
        assertFalse(text.contains("42.068") || text.contains("80.098") || text.contains("Quokka"), text)
    }

    /** Item 22 (docs/next-round.md): the lights ahead are drawn while the route is driven, not on the arrival map. */
    @Test
    fun `the traffic lights ahead are drawn while guiding only`() {
        val v = Fx.vehicle(GeoPoint(42.0350, -80.1120), ms = 10_000, bearing = 62.5)
        val lights = listOf(GeoPoint(42.036, -80.110), GeoPoint(42.04, -80.10))
        val guiding = Fx.guiding(v).let { it.copy(guidance = it.guidance!!.copy(lightsAhead = lights)) }
        val mapper = MapSceneMapper(rules)
        assertEquals(lights, mapper.scene(guiding, null, MapTheme.DAY, null).lights)
        assertEquals(lights, mapper.scene(guiding.copy(phase = Phase.REROUTING), null, MapTheme.NIGHT, null).lights)
        assertTrue(mapper.scene(guiding.copy(phase = Phase.ARRIVED), null, MapTheme.DAY, null).lights.isEmpty())
        assertTrue(mapper.scene(Fx.choosing(), null, MapTheme.DAY, null).lights.isEmpty())
    }
}
