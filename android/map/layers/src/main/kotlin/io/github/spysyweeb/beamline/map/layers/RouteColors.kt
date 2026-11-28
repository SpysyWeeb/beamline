package io.github.spysyweeb.beamline.map.layers

import io.github.spysyweeb.beamline.api.map.MapTheme
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** One line look: the line and the wider line drawn under it, as `#RRGGBB`. */
data class LinePaint(val fill: String, val casing: String)

/**
 * The colours of one theme: the chosen route, its driven part, the alternatives (the other route
 * choices, P23), and the trip's own route drawn under a previewed stop's route (item 51, 0.1.40).
 */
data class ThemeLines(val route: LinePaint, val traveled: LinePaint, val alternative: LinePaint, val tripRoute: LinePaint)

/**
 * assets/route_colors.json (version 1), copied into res/raw by scripts/sync_art.py: the candidate
 * named by "selected" (the owner chose B on 2026-11-15), the alternatives' and the trip route's colours, and the widths in
 * dp by zoom ([zoom, width] stops, interpolated exponentially with [widthBase]).
 */
data class RouteColors(
    val candidate: String,
    val day: ThemeLines,
    val night: ThemeLines,
    val fillWidths: List<Pair<Double, Double>>,
    val casingWidths: List<Pair<Double, Double>>,
    val widthBase: Double,
) {
    fun lines(theme: MapTheme): ThemeLines = if (theme == MapTheme.NIGHT) night else day

    companion object {
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

        /** Fixed refusal sentences only (docs/architecture-saved-places.md PV2 item 2 binds :map:layers). */
        fun parse(text: String): RouteColors {
            val root = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: throw IllegalArgumentException("route_colors.json: not a JSON object")
            require((root["version"] as? JsonPrimitive)?.intOrNull == 1) { "route_colors.json: version is not 1" }
            val selected = (root["selected"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("route_colors.json: selected missing")
            val candidates = root["candidates"] as? JsonObject ?: throw IllegalArgumentException("route_colors.json: candidates missing")
            val chosen = candidates[selected] as? JsonObject ?: throw IllegalArgumentException("route_colors.json: the selected candidate is missing")
            val alternative = root["alternative"] as? JsonObject ?: throw IllegalArgumentException("route_colors.json: alternative missing")
            val tripRoute = root["trip_route"] as? JsonObject ?: throw IllegalArgumentException("route_colors.json: trip_route missing")
            val widths = root["widths_dp"] as? JsonObject ?: throw IllegalArgumentException("route_colors.json: widths_dp missing")
            val interpolation = widths["interpolation"] as? JsonObject
            require((interpolation?.get("type") as? JsonPrimitive)?.content == "exponential") {
                "route_colors.json: widths_dp.interpolation.type must be exponential"
            }
            val base = (interpolation["base"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0.0 }
                ?: throw IllegalArgumentException("route_colors.json: widths_dp.interpolation.base missing")

            fun theme(name: String): ThemeLines {
                val t = chosen[name] as? JsonObject ?: throw IllegalArgumentException("route_colors.json: a theme of the selected candidate is missing")
                val traveled = t["traveled"] as? JsonObject ?: throw IllegalArgumentException("route_colors.json: traveled colours missing")
                val alt = alternative[name] as? JsonObject ?: throw IllegalArgumentException("route_colors.json: alternative colours missing")
                val trip = tripRoute[name] as? JsonObject ?: throw IllegalArgumentException("route_colors.json: trip_route colours missing")
                return ThemeLines(route = paint(t), traveled = paint(traveled), alternative = paint(alt), tripRoute = paint(trip))
            }
            return RouteColors(
                candidate = selected,
                day = theme("day"),
                night = theme("night"),
                fillWidths = stops(widths["fill"]),
                casingWidths = stops(widths["casing"]),
                widthBase = base,
            )
        }

        private fun paint(o: JsonObject): LinePaint = LinePaint(colour(o["fill"]), colour(o["casing"]))

        private fun colour(e: Any?): String {
            val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
            require(s != null && HEX.matches(s)) { "route_colors.json: a colour is not #RRGGBB" }
            return s.uppercase()
        }

        private fun stops(e: Any?): List<Pair<Double, Double>> {
            val rows = e as? JsonArray ?: throw IllegalArgumentException("route_colors.json: a width list is missing")
            val out = rows.map { r ->
                val a = r as? JsonArray
                val z = (a?.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
                val w = (a?.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                require(a != null && a.size == 2 && z != null && w != null && w > 0.0) { "route_colors.json: each width stop must be [zoom, width]" }
                z to w
            }
            require(out.isNotEmpty() && out.zipWithNext().all { (a, b) -> a.first < b.first }) {
                "route_colors.json: width stops must have growing zooms"
            }
            return out
        }
    }
}
