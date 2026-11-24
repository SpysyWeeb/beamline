package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.GeoPoint
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What [RecentPlaces] does (docs/architecture.md 2.6, 2.12): newest first, at most [max] places,
 * recents.json written atomically on [writer] (newest state wins; RecentPlaces passes
 * Dispatchers.IO, docs/architecture.md 1.2). Same place: equal packId and
 * searchId, or equal non-empty [foldText] of the names within [samePlaceM] metres. Places are
 * listed with source RECENT. A saved place (source SAVED) is never added (rule SP31 of
 * docs/architecture-saved-places.md). Thread-safe; never logs, and the file's text never reaches
 * an exception (PV2).
 */
internal class RecentsStore(
    private val file: File,
    private val max: Int,
    private val samePlaceM: Double,
    writer: Executor,
) {
    private val lock = Any()
    private val state = MutableStateFlow(read(file, max))
    private val out = LatestTextWriter(file, writer)
    val places: StateFlow<List<Place>> = state.asStateFlow()

    fun add(place: Place) {
        if (place.source == PlaceSource.SAVED) return
        val recent = place.copy(source = PlaceSource.RECENT, savedId = null)
        change { list -> (listOf(recent) + list.filterNot { same(it, place) }).take(max) }
    }

    fun remove(place: Place) = change { list -> list.filterNot { same(it, place) } }

    fun clear() = change { emptyList() }

    fun awaitWrites() = out.awaitIdle()

    private fun change(f: (List<Place>) -> List<Place>) {
        synchronized(lock) {
            val before = state.value
            val after = f(before)
            if (after == before) return
            state.value = after
            out.submit(encode(after))
        }
    }

    /** Same packId+searchId, or the same folded name within [samePlaceM]. */
    fun same(a: Place, b: Place): Boolean {
        if (a.packId != null && a.searchId != null && a.packId == b.packId && a.searchId == b.searchId) return true
        val fa = foldText(a.name)
        return fa.isNotEmpty() && fa == foldText(b.name) && distanceM(a.point, b.point) <= samePlaceM
    }

    companion object {
        const val FORMAT = "beamline-recents/1"

        fun distanceM(a: GeoPoint, b: GeoPoint): Double = Geo.haversineKm(a.lat, a.lon, b.lat, b.lon) * 1000.0

        fun encode(places: List<Place>): String = JsonObject(
            linkedMapOf(
                "format" to JsonPrimitive(FORMAT),
                "places" to JsonArray(places.map { p ->
                    val m = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
                        "name" to JsonPrimitive(p.name),
                        "lat" to JsonPrimitive(p.point.lat),
                        "lon" to JsonPrimitive(p.point.lon),
                    )
                    p.subtitle?.let { m["subtitle"] = JsonPrimitive(it) }
                    p.categoryId?.let { m["category"] = JsonPrimitive(it) }
                    p.searchId?.let { m["search_id"] = JsonPrimitive(it) }
                    p.packId?.let { m["pack_id"] = JsonPrimitive(it) }
                    JsonObject(m)
                }),
            ),
        ).toString()

        /** The list in [file]; empty when it is missing or broken in any way. */
        fun read(file: File, max: Int): List<Place> {
            if (!file.isFile) return emptyList()
            return try {
                decode(file.readText(Charsets.UTF_8)).take(max)
            } catch (_: Exception) {
                emptyList()   // broken: reads as empty (the parser's message holds file text; it is dropped, PV2)
            }
        }

        fun decode(text: String): List<Place> {
            val o = Json.parseToJsonElement(text).jsonObject
            require(o["format"]?.jsonPrimitive?.content == FORMAT) { "not a recents file" }
            return o["places"]!!.jsonArray.map { e ->
                val p = e.jsonObject
                val lat = p["lat"]!!.jsonPrimitive.double
                val lon = p["lon"]!!.jsonPrimitive.double
                require(lat in -90.0..90.0 && lon in -180.0..180.0) { "bad point" }
                val name = p["name"]!!.jsonPrimitive.also { require(it.isString) { "bad name" } }.content
                Place(
                    name = name, point = GeoPoint(lat, lon), source = PlaceSource.RECENT,
                    subtitle = p["subtitle"]?.jsonPrimitive?.content,
                    categoryId = p["category"]?.jsonPrimitive?.content,
                    searchId = p["search_id"]?.jsonPrimitive?.longOrNull,
                    packId = p["pack_id"]?.jsonPrimitive?.content,
                )
            }
        }
    }
}
