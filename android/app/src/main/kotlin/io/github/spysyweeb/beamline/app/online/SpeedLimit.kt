package io.github.spysyweeb.beamline.app.online

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The speed limit of a road (docs/next-round.md item 32, docs/online-design.md 4.7): the one fix of
 * road data the app makes itself. A driving app shows the limit on every trip, and a missing or
 * wrong one is the commonest thing a driver can put right from what the sign says. Everything else
 * about a road stays a note (4.1): a wrong one-way or a closed road is not a thing to change from
 * a phone.
 *
 * The edit is a tag on a way that exists: `maxspeed=45 mph` (OSM's form in the United States, wiki
 * Key:maxspeed). The way is the road of [ROADS] nearest to the point in OSM's own map of the spot
 * ([roadAt]); it goes out as every edit does ([EditData], one changeset, the conflict check).
 * Plain Kotlin; nothing here logs.
 */
object SpeedLimits {
    /** The limits the picker offers, in miles an hour (the posted limits of the United States). */
    val CHOICES = listOf(15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70)

    /** The roads a posted limit belongs to: not service roads, tracks, paths or roads being built. */
    val ROADS = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street",
        "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
    )

    /** A road farther than this from the point is not the road meant. */
    const val WITHIN_M = 30.0

    /** OSM's map is asked for a box this far each way: a road is found when one of its points lies in it. */
    const val BOX_M = 250.0

    const val KEY = "maxspeed"
    const val NOT_SET = "not set"

    fun tag(mph: Int): String = "$mph mph"

    private val MPH = Regex("^(\\d{1,3}) ?mph$")
    private val BARE = Regex("^\\d{1,3}$")

    /** The limit of a `maxspeed` value in miles an hour, when it is written so ("35 mph"). */
    fun mphOf(tag: String?): Int? = tag?.trim()?.let { MPH.matchEntire(it) }?.groupValues?.get(1)?.toIntOrNull()

    /** A `maxspeed` value in words: "35 mph"; a bare number is kilometres an hour in OSM; anything else as written. */
    fun shown(tag: String?): String {
        val t = tag?.trim().orEmpty()
        return when {
            t.isEmpty() -> NOT_SET
            MPH.matches(t) -> "${mphOf(t)} mph"
            BARE.matches(t) -> "$t km/h"
            else -> t
        }
    }

    /** The changeset's comment. */
    fun comment(road: String?, mph: Int): String =
        if (road.isNullOrBlank()) "Set the speed limit of a road to $mph mph" else "Set the speed limit of $road to $mph mph"

    /**
     * A road as OSM has it now: the way, its [version] and [tags], how far its line is from the point
     * asked for and how long the way is (one way is one stretch of a road, often a few blocks).
     * [toString] prints none of it.
     */
    data class Road(val ref: OsmRef, val version: Int, val tags: Map<String, String>, val distanceM: Double, val lengthM: Double) {
        val name: String? get() = tags["name"] ?: tags["ref"]
        override fun toString(): String = "Road"
    }

    /**
     * The road at ([lat], [lon]) in an OSM map answer (`GET /api/0.6/map.json`): of the ways of [ROADS]
     * whose line passes within [WITHIN_M], the nearest; when [name] is given (the road the driver was
     * on) a way of that name comes before a nearer one of another name (a side street at a corner).
     * Null: no such road, or an answer that cannot be read.
     */
    fun roadAt(json: String, lat: Double, lon: Double, name: String?): Road? = try {
        val els = (Json.parseToJsonElement(json) as JsonObject)["elements"] as JsonArray
        val coords = HashMap<Long, DoubleArray>()
        val ways = ArrayList<JsonObject>()
        for (e in els) {
            val o = e as? JsonObject ?: continue
            when ((o["type"] as? JsonPrimitive)?.content) {
                "node" -> {
                    val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: continue
                    val la = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: continue
                    val lo = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: continue
                    coords[id] = doubleArrayOf(la, lo)
                }
                "way" -> if (o["tags"] is JsonObject) ways += o
            }
        }
        val ky = 111_195.0
        val kx = ky * cos(Math.toRadians(lat))
        var best: Road? = null
        var bestNamed = false
        val want = name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        for (w in ways) {
            val tags = (w["tags"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
            if (tags["highway"] !in ROADS || tags["area"] == "yes") continue
            val id = (w["id"] as? JsonPrimitive)?.longOrNull ?: continue
            val version = (w["version"] as? JsonPrimitive)?.intOrNull ?: continue
            val refs = w["nodes"] as? JsonArray ?: continue
            val pts = refs.mapNotNull { n -> (n as? JsonPrimitive)?.longOrNull?.let(coords::get) }
            if (pts.size < 2 || pts.size != refs.size) continue   // a way whose points did not all come is not measured
            var nearest = Double.MAX_VALUE
            var length = 0.0
            for (i in 1 until pts.size) {
                val ax = (pts[i - 1][1] - lon) * kx
                val ay = (pts[i - 1][0] - lat) * ky
                val bx = (pts[i][1] - lon) * kx
                val by = (pts[i][0] - lat) * ky
                val dx = bx - ax
                val dy = by - ay
                val den = dx * dx + dy * dy
                val t = if (den == 0.0) 0.0 else (-(ax * dx + ay * dy) / den).coerceIn(0.0, 1.0)
                nearest = minOf(nearest, hypot(ax + t * dx, ay + t * dy))
                length += hypot(dx, dy)
            }
            if (nearest > WITHIN_M) continue
            val named = want != null && (tags["name"]?.lowercase() == want || tags["ref"]?.lowercase() == want)
            val b = best
            if (b == null || (named && !bestNamed) || (named == bestNamed && nearest < b.distanceM)) {
                best = Road(OsmRef(OsmType.WAY, id), version, tags, nearest, length)
                bestNamed = named
            }
        }
        best
    } catch (_: Exception) {
        null
    }
}

/** Where the speed limit screen is. */
enum class SpeedPhase { LOADING, NO_ROAD, OFFLINE, PICK, SUMMARY, UPLOADING, DONE, WAITING, REFUSED }

/**
 * One speed limit fix, from the point to the upload (docs/online-design.md 4.7); Compose state, read on
 * the main thread. [load] reads OSM's map of the spot and finds the road ([SpeedLimits.roadAt]); the
 * user picks the posted limit ([pick]); [summary] says what changes; [upload] puts it in the outbox as
 * an edit of that way and sends it at once. [picked]: a limit chosen earlier (the tap on the speed
 * sign while driving, kept in My reports until the driver has stopped), shown chosen; nothing goes
 * out before the summary's Upload. Nothing here logs.
 */
class SpeedSession(
    val lat: Double,
    val lon: Double,
    /** The road's name as the app knew it when the fix was started (the road being driven), or null. */
    val roadName: String?,
    picked: Int?,
    /** An outbox item being looked at again, or null for a new fix. */
    private val itemId: String?,
    private val http: OsmHttp,
    private val outbox: OutboxStore,
    private val sender: OutboxSender,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var phase by mutableStateOf(SpeedPhase.LOADING)
        private set
    var road by mutableStateOf<SpeedLimits.Road?>(null)
        private set
    var mph by mutableStateOf(picked)
        private set
    /** Why Next did not go on, or a line after a conflict; null: nothing to say. */
    var notice by mutableStateOf<String?>(null)
        private set
    var lines by mutableStateOf<List<String>>(emptyList())
        private set
    var link by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<Int?>(null)
        private set
    /** 0.1.41 (OP-4): the fix went and OpenStreetMap already had the limit: nothing was uploaded. */
    var already by mutableStateOf(false)
        private set

    private val id: String = itemId ?: java.util.UUID.randomUUID().toString()

    /** 0.1.41 (53e, OP-6): the outbox item looked at again (a limit picked while driving), as it is now; null for a new fix. */
    val stored: OutboxItem? get() = itemId?.let { outbox.byId(it) }

    /** 0.1.41 (53e, OP-6): the change the summary's Upload would put in the outbox. */
    val pendingChanges: Map<String, String?> get() = mph?.takeIf { road != null }?.let { mapOf(SpeedLimits.KEY to SpeedLimits.tag(it)) } ?: emptyMap()

    /** Reads the road at the point from OSM ([conflict]: the road changed on OSM while the fix waited). */
    fun load(conflict: Boolean = false) {
        phase = SpeedPhase.LOADING
        scope.launch {
            val json = try {
                withContext(io) { http.map(SpotCheck.box(lat, lon, SpeedLimits.BOX_M)) }
            } catch (_: OnlineRefused) {
                null
            } catch (_: IOException) {
                null
            }
            if (json == null) {
                phase = SpeedPhase.OFFLINE
                return@launch
            }
            val r = SpeedLimits.roadAt(json, lat, lon, roadName)
            road = r
            notice = if (conflict && r != null) OnlineTexts.SPEED_CONFLICT else null
            phase = if (r == null) SpeedPhase.NO_ROAD else SpeedPhase.PICK
        }
    }

    fun pick(value: Int) {
        if (value in SpeedLimits.CHOICES) {
            mph = value
            notice = null
        }
    }

    /** The road's limit on OSM now, in words. */
    val current: String get() = SpeedLimits.shown(road?.tags?.get(SpeedLimits.KEY))

    /** "Next": the change as a summary, or the picker stays with a [notice]. */
    fun summary() {
        val r = road ?: return
        val m = mph
        notice = when {
            m == null -> OnlineTexts.SPEED_PICK_FIRST
            SpeedLimits.mphOf(r.tags[SpeedLimits.KEY]) == m -> OnlineTexts.SPEED_SAME
            else -> null
        }
        if (notice != null || m == null) return
        lines = OnlineTexts.speedLines(r, m)
        phase = SpeedPhase.SUMMARY
    }

    fun backToPick() {
        phase = SpeedPhase.PICK
    }

    /** Something was chosen (the discard question on leaving). */
    val dirty: Boolean get() = (phase == SpeedPhase.PICK && mph != null && itemId == null) || phase == SpeedPhase.SUMMARY

    /** "Upload": into the outbox (replacing the item looked at again) and sent at once when it can be. 0.1.41: [atPlace] (53e) is kept with it. */
    fun upload(atPlace: Boolean = false) {
        if (phase != SpeedPhase.SUMMARY) return   // only the summary's Upload, once
        val r = road ?: return
        val m = mph ?: return
        val old = r.tags[SpeedLimits.KEY]
        outbox.add(
            OutboxItem(
                id = id, lat = lat, lon = lon, text = lines.joinToString("\n"), placeName = r.name ?: roadName, createdMs = now(),
                edit = EditData(
                    r.ref.toString(), r.version, if (old == null) emptyMap() else mapOf(SpeedLimits.KEY to old),
                    mapOf(SpeedLimits.KEY to SpeedLimits.tag(m)), SpeedLimits.comment(r.name, m), road = true,
                ),
                speedMph = m,
                atPlace = atPlace,
            ),
        )
        already = false
        phase = SpeedPhase.UPLOADING
        scope.launch {
            sender.sendWaiting(id, null)
            val item = outbox.byId(id)
            when (item?.state) {
                OutboxState.SENT -> {
                    link = item.link
                    already = item.edit?.changeset == null
                    phase = SpeedPhase.DONE
                }
                OutboxState.REVIEW -> load(conflict = true)
                OutboxState.REFUSED -> {
                    status = item.status
                    phase = SpeedPhase.REFUSED
                }
                else -> phase = SpeedPhase.WAITING
            }
        }
    }

    /** Deletes the outbox item being looked at again. */
    fun delete() {
        itemId?.let { outbox.delete(it) }
    }

    val isItem: Boolean get() = itemId != null
}
