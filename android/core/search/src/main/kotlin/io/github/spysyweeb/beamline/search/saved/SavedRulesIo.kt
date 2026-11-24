package io.github.spysyweeb.beamline.search.saved

import io.github.spysyweeb.beamline.search.SearchAppRules
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [SavedRules.parse]: the "saved" section of search_app.json. Fixed messages only (PV2). */
internal fun parseSavedRules(text: String): SavedRules {
    val root = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        throw IllegalArgumentException("search_app.json is not a JSON object")
    }
    val o: JsonObject = root["saved"] as? JsonObject ?: throw IllegalArgumentException("search_app.json has no saved section")
    try {
        return SavedRules(
            maxOther = o["max_other"]!!.jsonPrimitive.int,
            nameMaxChars = o["name_max_chars"]!!.jsonPrimitive.int,
            samePlaceM = o["same_place_m"]!!.jsonPrimitive.double,
            searchMinChars = o["search_min_chars"]!!.jsonPrimitive.int,
            searchMaxRows = o["search_max_rows"]!!.jsonPrimitive.int,
            pickMinZoom = o["pick_min_zoom"]!!.jsonPrimitive.double,
            positionMaxAccuracyM = o["position_max_accuracy_m"]!!.jsonPrimitive.double,
        )
    } catch (_: RuntimeException) {
        throw IllegalArgumentException("search_app.json saved section is incomplete")
    }
}

internal val bundledSavedRules: SavedRules by lazy { parseSavedRules(SearchAppRules.bundledText()) }

private val idRandom = SecureRandom()

/** "s" and 16 lower-case hex digits (64 random bits). */
internal fun newRandomSavedId(): String {
    val b = ByteArray(8)
    synchronized(idRandom) { idRandom.nextBytes(b) }
    return "s" + b.joinToString("") { "%02x".format(it) }
}
