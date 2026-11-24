package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.Vehicle
import io.github.spysyweeb.beamline.feature.drive.Exit24.guiding
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import io.github.spysyweeb.beamline.units.UnitSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Rule D8 (batch A; owner, 2026-11-17): the speed-limit sign while the stretch's limit is known,
 * the GPS speed beside it during all of guidance, yellow at 5 mph (8 km/h) over and red at 10 mph
 * (16 km/h), a dash with a weak or lost fix.
 */
class SpeedPresentTest {
    private fun mph(v: Double) = v / 2.2369362920544

    /** Guiding with [limitKmh] on the stretch and [mps] from the fixes. */
    private fun trip(limitKmh: Double?, mps: Double?, gps: GpsState = GpsState.GOOD, phase: Phase = Phase.GUIDING, offRoute: Boolean = false): TripState {
        val t = guiding(gps = gps, phase = phase, offRouteSuspected = offRoute)
        val vehicle = Vehicle(GeoPoint(42.03, -80.13), 0.0, true, Motion.MOVING, mps, 0L)
        return t.copy(guidance = t.guidance!!.copy(vehicle = vehicle, speedLimitKmh = limitKmh))
    }

    private fun speed(t: TripState, metric: Boolean = false) = present(t, units = if (metric) Exit24.km else Exit24.miles).guidance!!.speed

    @Test
    fun `the engine's 89 km h is the 55 mph sign, 72 the 45 one, metric shows km h`() {
        assertEquals(SpeedUi("55", "55", "mph", SpeedOver.NO), speed(trip(89.0, mph(55.0))))
        assertEquals("45", speed(trip(72.0, mph(40.0)))!!.limit)
        assertEquals(SpeedUi("89", "88", "km/h", SpeedOver.NO), speed(trip(89.0, 88 / 3.6), metric = true))
        assertEquals(65, Presentation.limitShown(104.6, UnitSystem.IMPERIAL))
        assertEquals(25, Presentation.limitShown(40.0, UnitSystem.IMPERIAL))
    }

    @Test
    fun `colours - yellow from 5 mph over, red from 10, 8 and 16 km h in metric, the sign stays`() {
        assertEquals(SpeedOver.NO, speed(trip(89.0, mph(59.0)))!!.over)
        assertEquals(SpeedOver.YELLOW, speed(trip(89.0, mph(60.0)))!!.over)
        assertEquals(SpeedOver.YELLOW, speed(trip(89.0, mph(64.0)))!!.over)
        assertEquals(SpeedOver.RED, speed(trip(89.0, mph(65.0)))!!.over)
        assertEquals("55", speed(trip(89.0, mph(80.0)))!!.limit)
        // metric: 89 km/h
        assertEquals(SpeedOver.NO, speed(trip(89.0, 96 / 3.6), metric = true)!!.over)
        assertEquals(SpeedOver.YELLOW, speed(trip(89.0, 97 / 3.6), metric = true)!!.over)
        assertEquals(SpeedOver.YELLOW, speed(trip(89.0, 104 / 3.6), metric = true)!!.over)
        assertEquals(SpeedOver.RED, speed(trip(89.0, 105 / 3.6), metric = true)!!.over)
        // below the limit, or no limit known: never coloured
        assertEquals(SpeedOver.NO, speed(trip(89.0, mph(20.0)))!!.over)
        assertEquals(SpeedOver.NO, speed(trip(null, mph(90.0)))!!.over)
        // the steps are compared on the numbers shown (64.6 mph reads 65: red)
        assertEquals(SpeedOver.RED, speed(trip(89.0, mph(64.6)))!!.over)
    }

    @Test
    fun `no limit known or none posted - the badge alone, off the route - no sign`() {
        assertEquals(SpeedUi(null, "30", "mph", SpeedOver.NO), speed(trip(null, mph(30.0))))
        assertNull(speed(trip(89.0, mph(55.0), offRoute = true))!!.limit)
        assertNull(speed(trip(89.0, mph(55.0), phase = Phase.REROUTING))!!.limit)
        assertEquals("55", speed(trip(89.0, mph(55.0), phase = Phase.REROUTING))!!.speed)
        assertNull(speed(trip(0.0, mph(10.0)))!!.limit)
    }

    @Test
    fun `dash - a weak, lost or missing fix, or no speed, the sign stays`() {
        for (gps in listOf(GpsState.WEAK, GpsState.LOST, GpsState.WAITING)) {
            assertEquals(SpeedUi("55", "–", "mph", SpeedOver.NO), speed(trip(89.0, mph(70.0), gps = gps)), "$gps")
        }
        assertEquals("–", speed(trip(89.0, null))!!.speed)
        val noVehicle = guiding().let { it.copy(guidance = it.guidance!!.copy(speedLimitKmh = 89.0)) }
        assertEquals(SpeedUi("55", "–", "mph", SpeedOver.NO), speed(noVehicle))
        assertEquals("Speed unknown", DriveTexts.speedLabel("–", "mph"))
        assertEquals("Speed limit 55 mph", DriveTexts.speedLimitLabel("55", "mph"))
    }

    @Test
    fun `only while guiding - not in ARRIVED, not while choosing or browsing`() {
        assertNull(speed(trip(89.0, mph(5.0), phase = Phase.ARRIVED)))
        assertNull(present(Exit24.choosing()).guidance)
        assertNull(present(TripState()).guidance)
    }

    /** Item 22 (docs/next-round.md): the road being driven, while the car is on the route. */
    @Test
    fun `the current road shows while guiding on the route`() {
        fun road(t: TripState, name: String? = "Peach St") =
            present(t.copy(guidance = t.guidance!!.copy(currentRoad = name))).guidance!!.currentRoad
        assertEquals("Peach\u00A0St", road(guiding()), "a short name stays on one line")
        assertEquals("General Strong Vincent Memorial Hwy", road(guiding(), "General Strong Vincent Memorial Hwy"))
        assertNull(road(guiding(), null), "a ramp or a lot: no label")
        assertNull(road(guiding(offRouteSuspected = true)), "off the route the name may be wrong")
        assertNull(road(guiding(phase = Phase.REROUTING)))
        assertNull(road(guiding(phase = Phase.ARRIVED)))
    }
}
