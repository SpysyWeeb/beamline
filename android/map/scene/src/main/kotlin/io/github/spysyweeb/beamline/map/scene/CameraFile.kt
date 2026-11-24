package io.github.spysyweeb.beamline.map.scene

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull

/**
 * Reads camera.json (format beamline-camera/1; source packbuilder/routing/camera.json, copied into
 * this module's resources by android/routing/scripts/sync_data.py --only camera.json).
 *
 * Every refusal is an IllegalArgumentException with a fixed sentence: no value from the file is put
 * into a message (docs/architecture-saved-places.md PV2 item 2 binds :map:scene).
 */
internal object CameraFile {
    const val FORMAT = "beamline-camera/1"
    private const val RESOURCE = "/io/github/spysyweeb/beamline/map/scene/camera.json"

    /** Highest zoom a rule may name (MapLibre renders up to 25.5). */
    private const val MAX_ZOOM = 25.0

    fun bundledText(): String =
        (CameraFile::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("camera.json missing from :map:scene (run android/routing/scripts/sync_data.py --only camera.json)"))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    /** The root object, after the format check. */
    fun root(text: String): JsonObject {
        val element: JsonElement = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            // The parser's message quotes the input; it is not kept.
            throw IllegalArgumentException("camera.json: not JSON")
        }
        val root = element as? JsonObject ?: throw IllegalArgumentException("camera.json: not a JSON object")
        val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(format == FORMAT) { "camera.json: format is not beamline-camera/1" }
        return root
    }

    fun cameraRules(text: String): CameraRules {
        val root = root(text)
        val guide = obj(root, "guide", "camera.json: guide missing")
        val overview = obj(root, "overview", "camera.json: overview missing")
        val browse = obj(root, "browse", "camera.json: browse missing")
        val preview = obj(root, "preview", "camera.json: preview missing")
        val arrived = obj(root, "arrived", "camera.json: arrived missing")

        val pitch = num(guide, "pitch_deg", "camera.json: guide.pitch_deg missing")
        require(pitch in 0.0..85.0) { "camera.json: guide.pitch_deg must be 0 to 85" }
        val fromBottom = num(guide, "vehicle_from_bottom", "camera.json: guide.vehicle_from_bottom missing")
        require(fromBottom > 0.0 && fromBottom < 1.0) { "camera.json: guide.vehicle_from_bottom must be between 0 and 1" }
        val zoomBySpeed = zoomBySpeed(guide)
        val approachWithin = num(guide, "approach_within_m", "camera.json: guide.approach_within_m missing")
        require(approachWithin >= 0.0) { "camera.json: guide.approach_within_m must not be negative" }
        val approachZoom = zoom(guide, "approach_zoom", "camera.json: guide.approach_zoom missing or out of range")
        val smoothing = num(guide, "bearing_smoothing", "camera.json: guide.bearing_smoothing missing")
        require(smoothing > 0.0 && smoothing <= 1.0) { "camera.json: guide.bearing_smoothing must be above 0 and at most 1" }
        // Optional: a course change of at least this many degrees is followed at once (a turn), smaller
        // ones are smoothed less the larger they are; absent or 0 = plain bearing_smoothing.
        val turnDeg = guide["bearing_turn_deg"]?.let { num(guide, "bearing_turn_deg", "camera.json: guide.bearing_turn_deg must be a number") } ?: 0.0
        require(turnDeg in 0.0..180.0) { "camera.json: guide.bearing_turn_deg must be 0 to 180" }
        // Optional: the lean into a turn (docs/next-round.md item 22); absent = none.
        val lean = (guide["lean"] as? JsonObject)?.let { l ->
            // fixed texts (no key in them): this module's messages never carry a value (privacy rule PV2's scan)
            fun n(key: String, ok: (Double) -> Boolean): Double =
                num(l, key, "camera.json: guide.lean has a value missing").also { require(ok(it)) { "camera.json: guide.lean has a value out of range" } }
            LeanRules(
                withinM = n("within_m") { it >= 0.0 },
                withinS = n("within_s") { it >= 0.0 },
                bearingShare = n("bearing_share") { it in 0.0..1.0 },
                maxBearingDeg = n("max_bearing_deg") { it in 0.0..90.0 },
                zoomAdd = n("zoom_add") { it in 0.0..3.0 },
                pitchDeg = n("pitch_deg") { it in 0.0..85.0 },
                minTurnDeg = n("min_turn_deg") { it in 0.0..180.0 },
                lookM = n("look_m") { it > 0.0 },
                holdMs = n("hold_ms") { it >= 0.0 && it <= 60_000.0 }.toLong(),
            )
        }
        val recenter = num(guide, "recenter_after_s", "camera.json: guide.recenter_after_s missing")
        require(recenter > 0.0) { "camera.json: guide.recenter_after_s must be above 0" }
        val overviewReturn = num(overview, "return_after_s", "camera.json: overview.return_after_s missing")
        require(overviewReturn > 0.0) { "camera.json: overview.return_after_s must be above 0" }
        val followZoom = zoom(browse, "follow_zoom", "camera.json: browse.follow_zoom missing or out of range")
        val padding = num(preview, "padding_dp", "camera.json: preview.padding_dp missing")
        require(padding >= 0.0) { "camera.json: preview.padding_dp must not be negative" }
        val arrivedZoom = zoom(arrived, "zoom", "camera.json: arrived.zoom missing or out of range")
        val transition = num(root, "transition_ms", "camera.json: transition_ms missing")
        require(transition >= 0.0 && transition <= 60_000.0 && transition == Math.floor(transition)) {
            "camera.json: transition_ms must be a whole number from 0 to 60000"
        }
        return CameraRules(
            guidePitchDeg = pitch,
            guideVehicleFromBottom = fromBottom,
            guideZoomBySpeed = zoomBySpeed,
            approachWithinM = approachWithin,
            approachZoom = approachZoom,
            bearingSmoothing = smoothing,
            recenterAfterS = recenter,
            overviewReturnAfterS = overviewReturn,
            browseFollowZoom = followZoom,
            previewPaddingDp = padding.toFloat(),
            arrivedZoom = arrivedZoom,
            transitionMs = transition.toInt(),
            bearingTurnDeg = turnDeg,
            lean = lean,
        )
    }

    fun savedMarkerRules(text: String): SavedMarkerRules {
        val saved = obj(root(text), "saved_places", "camera.json: saved_places missing")
        val homeWork = zoom(saved, "home_work_min_zoom", "camera.json: saved_places.home_work_min_zoom missing or out of range")
        val other = zoom(saved, "other_min_zoom", "camera.json: saved_places.other_min_zoom missing or out of range")
        val scale = num(saved, "icon_scale", "camera.json: saved_places.icon_scale missing")
        require(scale > 0.0 && scale <= 8.0) { "camera.json: saved_places.icon_scale must be above 0 and at most 8" }
        return SavedMarkerRules(homeWorkMinZoom = homeWork, otherMinZoom = other, iconScale = scale)
    }

    private fun zoomBySpeed(guide: JsonObject): List<Pair<Double, Double>> {
        val rows = guide["zoom_by_speed_mps"] as? JsonArray
            ?: throw IllegalArgumentException("camera.json: guide.zoom_by_speed_mps missing")
        require(rows.isNotEmpty()) { "camera.json: guide.zoom_by_speed_mps is empty" }
        val pairs = rows.map { row ->
            val pair = row as? JsonArray
            val speed = (pair?.getOrNull(0) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            val zoom = (pair?.getOrNull(1) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
            require(pair != null && pair.size == 2 && speed != null && zoom != null) {
                "camera.json: each guide.zoom_by_speed_mps row must be [speed, zoom]"
            }
            require(speed >= 0.0 && zoom in 0.0..MAX_ZOOM) { "camera.json: guide.zoom_by_speed_mps has a speed below 0 or a zoom out of range" }
            speed to zoom
        }
        require(pairs.zipWithNext().all { (a, b) -> a.first < b.first }) {
            "camera.json: guide.zoom_by_speed_mps speeds must grow"
        }
        return pairs
    }

    private fun obj(o: JsonObject, key: String, missing: String): JsonObject =
        o[key] as? JsonObject ?: throw IllegalArgumentException(missing)

    private fun num(o: JsonObject, key: String, missing: String): Double =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
            ?: throw IllegalArgumentException(missing)

    private fun zoom(o: JsonObject, key: String, bad: String): Double =
        (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it in 0.0..MAX_ZOOM }
            ?: throw IllegalArgumentException(bad)
}
