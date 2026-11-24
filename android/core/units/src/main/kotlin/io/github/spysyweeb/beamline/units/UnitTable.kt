package io.github.spysyweeb.beamline.units

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/** The parsed units.json. Every rule (steps, words, clock patterns) comes from the file. */
internal class UnitTable(
    private val imperial: List<Step>,
    private val metric: List<Step>,
    val followingPrefix: String,
    private val underOneMinute: String,
    private val minutes: String,
    private val hoursMinutes: String,
    private val hours: String,
    private val clock12: DateTimeFormatter,
    private val clock24: DateTimeFormatter,
) {
    /** Below [belowM] metres (null: no limit) the distance is written in [unit]. */
    class Step(val belowM: Double?, val unit: String, val roundTo: Int?, val decimals: Int?)

    fun distance(system: UnitSystem, m: Double): String {
        val d = if (m.isNaN() || m < 0) 0.0 else m
        val steps = if (system == UnitSystem.IMPERIAL) imperial else metric
        val step = steps.first { it.belowM == null || d < it.belowM }
        val value = d / METRES_PER.getValue(step.unit)
        val number = if (step.roundTo != null) {
            // whole units in steps of round_to, never below one step ("10 ft", not "0 ft")
            val r = step.roundTo.toLong()
            maxOf((value / r).roundToLong() * r, r).toString()
        } else {
            // valueOf: the shortest decimal of the double, so 950 m is 0.95 km and rounds up to 1.0 km
            // (the step's first text, as units.json promises), not down from 0.94999...
            BigDecimal.valueOf(value).setScale(step.decimals ?: 0, RoundingMode.HALF_UP).toPlainString()
        }
        return "$number ${step.unit}"
    }

    fun duration(s: Double): String {
        val total = if (s.isNaN() || s < 0) 0L else floor(s / 60.0 + 0.5).toLong()
        return when {
            total == 0L -> underOneMinute
            total < 60 -> minutes.replace("{m}", total.toString())
            total % 60 == 0L -> hours.replace("{h}", (total / 60).toString())
            else -> hoursMinutes.replace("{h}", (total / 60).toString()).replace("{m}", (total % 60).toString())
        }
    }

    fun clock(h24: Boolean, epochMs: Long, zone: ZoneId): String =
        (if (h24) clock24 else clock12).format(Instant.ofEpochMilli(epochMs).atZone(zone))

    companion object {
        /** Metres per unit; the units a step may use. */
        private val METRES_PER = mapOf("ft" to 0.3048, "mi" to 1609.344, "m" to 1.0, "km" to 1000.0)

        fun parse(text: String): UnitTable {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root["format"]?.jsonPrimitive?.content == UnitRules.FORMAT) { "units.json: format is not ${UnitRules.FORMAT}" }
            val distance = root["distance"]!!.jsonObject
            fun steps(key: String): List<Step> = (distance[key] as JsonArray).map { e ->
                val o = e.jsonObject
                val unit = o["unit"]!!.jsonPrimitive.content
                require(unit in METRES_PER) { "units.json: unknown unit $unit" }
                Step(o["below_m"]?.jsonPrimitive?.double, unit, o["round_to"]?.jsonPrimitive?.int, o["decimals"]?.jsonPrimitive?.int)
            }.also { s ->
                require(s.isNotEmpty() && s.last().belowM == null) { "units.json: $key needs a last step without below_m" }
                require(s.dropLast(1).zipWithNext().all { (a, b) -> a.belowM!! < b.belowM!! }) { "units.json: $key steps must grow" }
            }
            val duration = root["duration"]!!.jsonObject
            val clock = root["clock"]!!.jsonObject
            val locale = Locale.forLanguageTag(clock["locale"]!!.jsonPrimitive.content)
            fun str(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content
            return UnitTable(
                imperial = steps("imperial"),
                metric = steps("metric"),
                followingPrefix = root["following_prefix"]!!.jsonPrimitive.content,
                underOneMinute = str(duration, "under_one_minute"),
                minutes = str(duration, "minutes"),
                hoursMinutes = str(duration, "hours_minutes"),
                hours = str(duration, "hours"),
                clock12 = DateTimeFormatter.ofPattern(str(clock, "h12"), locale),
                clock24 = DateTimeFormatter.ofPattern(str(clock, "h24"), locale),
            )
        }

        fun bundledText(): String =
            (UnitTable::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/units/units.json")
                ?: throw IllegalStateException("units.json missing (run android/routing/scripts/sync_data.py)"))
                .use { it.readBytes().toString(Charsets.UTF_8) }
    }
}
