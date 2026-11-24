package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.routing.model.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The posted speed limit of each stretch of a route (batch A, docs/next-round.md item 11): from the
 * engine's OSRM-format answer, `legs[].annotation.maxspeed`, one entry per segment of the leg's
 * line (`{"speed": 89, "unit": "km/h"}`, `{"unknown": true}`, `{"none": true}`), laid over the
 * flattened Route.shape (the legs' shapes one after another, so the step from one leg's last point
 * to the next leg's first has no limit). A leg whose count does not match its shape has no limits
 * at all rather than misplaced ones. "none" (no posted limit) and "unknown" are both null: no sign.
 */
class SpeedLimits private constructor(private val kmh: Array<Double?>) {

    /** The limit in km/h of the segment after shape point [shapeIndex] (Guidance.traveled.index), or null. */
    fun at(shapeIndex: Int): Double? = kmh.getOrNull(shapeIndex)

    /** Segments with a known limit, of all segments (tests and the report). */
    val knownCount: Int get() = kmh.count { it != null }
    val size: Int get() = kmh.size

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val KMH_PER_MPH = 1.609344

        val NONE = SpeedLimits(emptyArray())

        /** Never throws: a broken or missing annotation gives [NONE] (no sign). */
        fun from(route: Route, osrmJson: String, routeIndex: Int): SpeedLimits = try {
            val r = json.parseToJsonElement(osrmJson).jsonObject["routes"]!!.jsonArray[routeIndex].jsonObject
            of(route.legs.map { it.shape.size }, r["legs"]!!.jsonArray.map { leg ->
                (leg.jsonObject["annotation"] as? JsonObject)?.get("maxspeed") as? JsonArray
            })
        } catch (e: Exception) {
            NONE
        }

        /** [legShapeSizes]: points of each leg's line; [maxspeed]: each leg's annotation (null when missing). */
        internal fun of(legShapeSizes: List<Int>, maxspeed: List<JsonArray?>): SpeedLimits {
            val total = legShapeSizes.sum()
            val out = arrayOfNulls<Double>(maxOf(0, total - 1))
            var offset = 0
            legShapeSizes.forEachIndexed { li, n ->
                val entries = maxspeed.getOrNull(li)
                if (entries != null && entries.size == n - 1) {
                    entries.forEachIndexed { j, e -> out[offset + j] = kmhOf(e.jsonObject) }
                }
                offset += n
            }
            return SpeedLimits(out)
        }

        private fun kmhOf(o: JsonObject): Double? {
            if (o["none"]?.jsonPrimitive?.booleanOrNull == true || o["unknown"]?.jsonPrimitive?.booleanOrNull == true) return null
            val speed = o["speed"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 } ?: return null
            return when (o["unit"]?.jsonPrimitive?.content) {
                "mph" -> speed * KMH_PER_MPH
                "km/h", null -> speed
                else -> null
            }
        }
    }
}
