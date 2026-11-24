package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.routing.model.Leg
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Batch A, rule D8: the posted limit along the route from the OSRM answer's annotation.maxspeed. */
class SpeedLimitsTest {
    private fun arr(text: String): JsonArray = Json.parseToJsonElement(text).jsonArray

    @Test
    fun `exit 24 - 89 km h on I-79 to the exit, unknown on the ramp, 72 on Peach St, unknown after`() {
        val osrm = Data.json("reference/exit24-osrm.json")["response"]!!.toString()
        val route = Data.exit24
        val limits = SpeedLimits.from(route, osrm, 0)
        assertEquals(route.shape.size - 1, limits.size)
        assertEquals(48, limits.knownCount)
        // stretch boundaries (segment indexes of the one leg): 0-39 I-79, 40-49 the ramp, 50-57 Peach St, 58- the rest
        assertEquals(89.0, limits.at(0))
        assertEquals(89.0, limits.at(39))
        assertNull(limits.at(40))
        assertNull(limits.at(49))
        assertEquals(72.0, limits.at(50))
        assertEquals(72.0, limits.at(57))
        assertNull(limits.at(58))
        // outside the line: none
        assertNull(limits.at(-1))
        assertNull(limits.at(limits.size))
        // known for 85 % of the distance (docs/next-round.md item 11)
        val geo = RouteGeometry(route)
        val known = (0 until limits.size).filter { limits.at(it) != null }.sumOf { geo.along[it + 1] - geo.along[it] }
        assertEquals(0.85, known / geo.lengthM, 0.01)
    }

    @Test
    fun `two legs - mph read as km h, none and unknown give no limit, the joint has none`() {
        val limits = SpeedLimits.of(
            listOf(3, 3),
            listOf(
                arr("""[{"speed": 55, "unit": "mph"}, {"none": true}]"""),
                arr("""[{"unknown": true}, {"speed": 40}]"""),
            ),
        )
        assertEquals(5, limits.size)
        assertEquals(55 * 1.609344, limits.at(0)!!, 1e-9)
        assertNull(limits.at(1)) // none: no posted limit, no sign
        assertNull(limits.at(2)) // the step from leg 0's last point to leg 1's first
        assertNull(limits.at(3)) // unknown
        assertEquals(40.0, limits.at(4)) // no unit: km/h, as the engine writes it
    }

    @Test
    fun `a leg whose count does not match its line, or a broken answer, gives no limits`() {
        val limits = SpeedLimits.of(listOf(3, 3), listOf(arr("""[{"speed": 89, "unit": "km/h"}]"""), arr("""[{"speed": 72, "unit": "km/h"}, {"speed": 72, "unit": "km/h"}]""")))
        assertNull(limits.at(0))
        assertEquals(72.0, limits.at(3))
        assertEquals(3, (0 until limits.size).count { limits.at(it) == null }) // leg 0 (2) and the joint
        val route = Route(1.0, 1.0, listOf(Leg(1.0, 1.0, listOf(LatLon(42.0, -80.0), LatLon(42.001, -80.0)), emptyList())), emptyList(), false, false)
        assertSame(SpeedLimits.NONE, SpeedLimits.from(route, "not json", 0))
        assertSame(SpeedLimits.NONE, SpeedLimits.from(route, """{"routes": []}""", 0))
        assertNull(SpeedLimits.from(route, """{"routes": [{"legs": [{}]}]}""", 0).at(0))
        assertNull(SpeedLimits.from(route, """{"routes": [{"legs": [{"annotation": {"maxspeed": [{"speed": "x"}]}}]}]}""", 0).at(0))
        assertNull(SpeedLimits.from(route, """{"routes": [{"legs": [{"annotation": {"maxspeed": [{"speed": 30, "unit": "knots"}]}}]}]}""", 0).at(0))
    }
}
