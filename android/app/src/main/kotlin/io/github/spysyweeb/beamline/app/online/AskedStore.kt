package io.github.spysyweeb.beamline.app.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * 0.1.41 (docs/online-design.md 4.8, AP6, AP12; plan AP-1, AP-6): the places where the user answered the arrival
 * question (Yes, the edit button or Not now), each with its day: `no_backup/arrival-asked.json`,
 * `{"format":"beamline-asked/1","asked":{"n7570266250":"2026-11-28"}}`. Only OSM object ids and days, none older than
 * [PlaceQuestions.ASKED_DAYS]; written atomically (as online.json) on the caller's thread (the store thread); an
 * unreadable or foreign file reads as empty. A question shown and not answered is remembered in memory only, for the
 * process ([noteShown]): nothing derived from the position memory reaches the disk without the user's tap. [clear]
 * (Contribute or online features off) deletes the file and the memory. Never logged, never in a backup, never sent
 * (PV1-PV8). [toString] prints nothing of it. The fixer's review (AP12, "at most 90 days old"): what is past leaves
 * the disk when the store is opened ([today], the app's start) and whenever it is read, not only at the next answer.
 */
class AskedStore(private val file: File, today: LocalDate = LocalDate.now()) {
    private var answered: Map<String, LocalDate>? = null
    private val shown = HashSet<String>()

    init {
        fresh(today)
    }

    private fun loaded(): Map<String, LocalDate> = answered ?: (try {
        if (file.isFile) AskedRecords.parse(file.readText(Charsets.UTF_8)) else emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }).also { answered = it }

    /** The record on [today]: what is past is dropped from the memory and the disk (rewritten, or deleted when nothing is left). */
    private fun fresh(today: LocalDate): Map<String, LocalDate> {
        val m = loaded()
        val next = AskedRecords.prune(m, today)
        if (next != m) {
            answered = next
            try {
                if (next.isEmpty()) delete() else write(AskedRecords.format(next))
            } catch (_: Exception) {
                // the next answer writes it again; nothing past is used meanwhile
            }
        }
        return next
    }

    /** AP6: not asked again on arrival: answered in the last 90 days, or shown in this run. */
    @Synchronized
    fun recent(osm: String, today: LocalDate): Boolean = osm in shown || AskedRecords.recent(fresh(today), osm, today)

    /** AP-6: the question was shown at [osm] (on screen, AP-1): remembered until the app is closed, never written. */
    @Synchronized
    fun noteShown(osm: String) {
        shown += osm
    }

    /** AP-6: the user answered the question at [osm] [today]: written (and what is past dropped) on the caller's thread. */
    @Synchronized
    fun markAnswered(osm: String, today: LocalDate) {
        val next = AskedRecords.prune(loaded() + (osm to today), today)
        answered = next
        write(AskedRecords.format(next))
    }

    /** AP-6: Contribute or online features went off: the file and the memory are gone. */
    @Synchronized
    fun clear() {
        shown.clear()
        answered = emptyMap()
        delete()
    }

    private fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot write the arrival record")
        }
    }

    override fun toString(): String = "AskedStore"

    companion object {
        const val FILE_NAME = "arrival-asked.json"
    }
}

/** The record's rules (AP6), plain Kotlin. */
object AskedRecords {
    const val FORMAT = "beamline-asked/1"
    const val MAX = 1_000

    /** Answered on day D: not asked again before D + 90 days. */
    fun recent(m: Map<String, LocalDate>, osm: String, today: LocalDate): Boolean =
        m[osm]?.let { today.isBefore(it.plusDays(PlaceQuestions.ASKED_DAYS)) } == true

    /** What is still in force on [today]: nothing 90 days old, nothing more than a day ahead (a clock set back), at most [MAX], newest kept. */
    fun prune(m: Map<String, LocalDate>, today: LocalDate): Map<String, LocalDate> =
        m.filterValues { today.isBefore(it.plusDays(PlaceQuestions.ASKED_DAYS)) && !it.isAfter(today.plusDays(1)) }
            .entries.sortedByDescending { it.value }.take(MAX).associate { it.key to it.value }

    /** Only keys that are OSM objects ("n123", "w45") and values that are days; anything else is left out. */
    fun parse(text: String): Map<String, LocalDate> = try {
        val o = Json.parseToJsonElement(text) as? JsonObject
        val asked = o?.takeIf { (it["format"] as? JsonPrimitive)?.contentOrNull == FORMAT }?.get("asked") as? JsonObject
        asked?.mapNotNull { (k, v) ->
            val day = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (OsmRef.parse(k)?.toString() == k && day != null) k to day else null
        }?.toMap() ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun format(m: Map<String, LocalDate>): String = JsonObject(
        mapOf(
            "format" to JsonPrimitive(FORMAT),
            "asked" to JsonObject(m.toSortedMap().mapValues { JsonPrimitive(it.value.toString()) }),
        ),
    ).toString()
}
