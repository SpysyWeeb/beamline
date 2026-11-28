package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.TripStop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * trip.json, format beamline-trip/1 (docs/architecture.md 2.12, rules R1-R3): the stops with
 * their reached flags, the trip's route type and options, whether it is a simulated drive (play test
 * P27: the key only for one), and the wall-clock time of the write.
 *
 * ```
 * {"format": "beamline-trip/1", "saved_epoch_ms": 1763280000000, "route_type": "fastest",
 *  "options": {"avoid_highways": false, "avoid_tolls": false},
 *  "stops": [{"reached": false, "place": {"name": "...", "lat": 42.05, "lon": -80.08, "source": "SAVED",
 *             "subtitle": null, "category_id": null, "search_id": null, "pack_id": null, "saved_id": "s0123..."}}],
 *  "simulated": true}                                   (only for a simulated drive)
 * ```
 *
 * Written atomically (a temporary file beside it, fsync, rename). The file names places, so it
 * lives in the no-backup folder the app gives (rule PV4) and nothing here logs or puts a value in an
 * exception message (rule PV2): a file that cannot be read is simply no trip.
 */
internal class TripFile(private val file: File) {

    @Synchronized
    fun save(s: TripSnapshot) {
        val text = encode(s)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // renameTo does not replace on every platform (Windows JVM tests): delete, then rename
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("trip.json could not be written")
        }
    }

    @Synchronized
    fun load(): TripSnapshot? = try {
        if (!file.isFile) null else decode(file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    companion object {
        const val FORMAT = "beamline-trip/1"

        fun encode(s: TripSnapshot): String = JsonObject(linkedMapOf<String, JsonElement>(
            "format" to JsonPrimitive(FORMAT),
            "saved_epoch_ms" to JsonPrimitive(s.savedEpochMs),
            "route_type" to JsonPrimitive(s.routeType),
            "options" to JsonObject(linkedMapOf(
                "avoid_highways" to JsonPrimitive(s.options.avoidHighways),
                "avoid_tolls" to JsonPrimitive(s.options.avoidTolls),
            )),
            "stops" to JsonArray(s.stops.map { st ->
                JsonObject(linkedMapOf("reached" to JsonPrimitive(st.reached), "place" to place(st.place)))
            }),
        ).apply {
            // play test P27: an optional key, so a real trip's file stays as it was (and an older file reads as not simulated)
            if (s.simulated) put("simulated", JsonPrimitive(true))
        }).toString()

        private fun place(p: Place): JsonObject = JsonObject(linkedMapOf(
            "name" to JsonPrimitive(p.name),
            "lat" to JsonPrimitive(p.point.lat),
            "lon" to JsonPrimitive(p.point.lon),
            "source" to JsonPrimitive(p.source.name),
            "subtitle" to (p.subtitle?.let(::JsonPrimitive) ?: JsonNull),
            "category_id" to (p.categoryId?.let(::JsonPrimitive) ?: JsonNull),
            "search_id" to (p.searchId?.let(::JsonPrimitive) ?: JsonNull),
            "pack_id" to (p.packId?.let(::JsonPrimitive) ?: JsonNull),
            "saved_id" to (p.savedId?.let(::JsonPrimitive) ?: JsonNull),
        ))

        /** Null for anything that is not a complete beamline-trip/1 file (no message: it could hold a place). */
        fun decode(text: String): TripSnapshot? {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
            if ((root["format"] as? JsonPrimitive)?.content != FORMAT) return null
            val saved = (root["saved_epoch_ms"] as? JsonPrimitive)?.longOrNull ?: return null
            val type = (root["route_type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val opts = root["options"] as? JsonObject ?: return null
            val options = RouteOptions(
                avoidHighways = (opts["avoid_highways"] as? JsonPrimitive)?.booleanOrNull ?: return null,
                avoidTolls = (opts["avoid_tolls"] as? JsonPrimitive)?.booleanOrNull ?: return null,
            )
            val stops = (root["stops"] as? JsonArray ?: return null).map { e ->
                val o = e as? JsonObject ?: return null
                TripStop(placeOf(o["place"]) ?: return null, (o["reached"] as? JsonPrimitive)?.booleanOrNull ?: return null)
            }
            if (stops.isEmpty()) return null
            val simulated = (root["simulated"] as? JsonPrimitive)?.booleanOrNull ?: false
            return TripSnapshot(saved, stops, type, options, simulated)
        }

        private fun placeOf(e: JsonElement?): Place? {
            val o = e as? JsonObject ?: return null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
            val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
            val source = PlaceSource.entries.firstOrNull { it.name == str("source") } ?: return null
            return Place(
                name = str("name") ?: return null,
                point = GeoPoint(lat, lon),
                source = source,
                subtitle = str("subtitle"),
                categoryId = str("category_id"),
                searchId = (o["search_id"] as? JsonPrimitive)?.longOrNull,
                packId = str("pack_id"),
                savedId = str("saved_id"),
            )
        }
    }
}
