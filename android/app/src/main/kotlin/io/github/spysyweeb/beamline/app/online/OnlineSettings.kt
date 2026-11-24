package io.github.spysyweeb.beamline.app.online

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File

/**
 * The online switches (docs/online-design.md section 2). [online] is the main switch ("Allow online
 * features" on the first-run choice, "Online features" in Settings); each feature has its own switch
 * under it, which counts only while the main switch is on. Batch O1 has one feature, [contributeOsm]
 * (OSM notes); "Find places online" comes with batch O4. The defaults are "Offline only": nothing is
 * on until the user chooses, and [choiceMade] false shows the first-run choice (also once to someone
 * updating from a version without it).
 */
data class OnlineSettings(
    val choiceMade: Boolean = false,
    val online: Boolean = false,
    val contributeOsm: Boolean = false,
    /** The public-note notice (section 3) was shown and the user chose Send. */
    val noteNoticeSeen: Boolean = false,
    /** Batch O2: the public-edit notice (section 3) was shown and the user chose Upload. */
    val editNoticeSeen: Boolean = false,
    /**
     * Item 40 (the owner, 2026-11-24): once a day, when the app opens, ask the packs' catalog whether an
     * installed state has newer map data (GetPacks.checkForUpdates); a file without the key takes [online].
     */
    val checkMapUpdates: Boolean = false,
    /** Item 41: once a day, ask the app's GitHub releases for a newer Beamline (AppUpdates); a file without the key takes [online]. */
    val checkAppUpdates: Boolean = false,
) {
    /** OsmHttp may send (rule N4): the main switch and the feature's own. */
    val osmAllowed: Boolean get() = online && contributeOsm

    /** The daily map-update check may run (item 40): the main switch and its own. */
    val mapUpdatesAllowed: Boolean get() = online && checkMapUpdates

    /** The daily app-update check may run (item 41): the main switch and its own. */
    val appUpdatesAllowed: Boolean get() = online && checkAppUpdates

    /** The first-run choice or the main switch turned on: every online feature on (the owner, 2026-11-20). */
    fun allowOnline(): OnlineSettings = copy(choiceMade = true, online = true, contributeOsm = true, checkMapUpdates = true, checkAppUpdates = true)

    /** "Offline only", or the main switch turned off: no online feature sends anything. */
    fun offlineOnly(): OnlineSettings = copy(choiceMade = true, online = false)

    /** A feature's own switch; only while the main switch is on. */
    fun withContributeOsm(on: Boolean): OnlineSettings = if (online) copy(contributeOsm = on) else this

    /** Item 40's own switch; only while the main switch is on. */
    fun withCheckMapUpdates(on: Boolean): OnlineSettings = if (online) copy(checkMapUpdates = on) else this

    /** Item 41's own switch; only while the main switch is on. */
    fun withCheckAppUpdates(on: Boolean): OnlineSettings = if (online) copy(checkAppUpdates = on) else this
}

/**
 * online.json in the app's files folder (`{"choice_made":true,"online":false,...}`), written atomically
 * like settings.json. A missing, broken or foreign file reads as the defaults (Offline only, choice not made).
 */
class OnlineSettingsStore(private val file: File) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<OnlineSettings> = _state.asStateFlow()

    @Synchronized
    fun update(change: (OnlineSettings) -> OnlineSettings) {
        val next = change(_state.value)
        if (next == _state.value) return
        save(next)
        _state.value = next
    }

    private fun load(): OnlineSettings = try {
        val obj = if (file.isFile) Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject else null
        fun bool(k: String) = (obj?.get(k) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
        OnlineSettings(bool(CHOICE_MADE), bool(ONLINE), bool(CONTRIBUTE_OSM), bool(NOTE_NOTICE_SEEN), bool(EDIT_NOTICE_SEEN),
            // item 40: a file from before the switch takes the main switch's value (on for who chose online)
            if (obj?.containsKey(CHECK_MAP_UPDATES) == true) bool(CHECK_MAP_UPDATES) else bool(ONLINE),
            if (obj?.containsKey(CHECK_APP_UPDATES) == true) bool(CHECK_APP_UPDATES) else bool(ONLINE))
    } catch (_: Exception) {
        OnlineSettings()
    }

    private fun save(s: OnlineSettings) {
        val text = JsonObject(
            mapOf(
                CHOICE_MADE to JsonPrimitive(s.choiceMade), ONLINE to JsonPrimitive(s.online),
                CONTRIBUTE_OSM to JsonPrimitive(s.contributeOsm), NOTE_NOTICE_SEEN to JsonPrimitive(s.noteNoticeSeen),
                EDIT_NOTICE_SEEN to JsonPrimitive(s.editNoticeSeen), CHECK_MAP_UPDATES to JsonPrimitive(s.checkMapUpdates),
                CHECK_APP_UPDATES to JsonPrimitive(s.checkAppUpdates),
            ),
        ).toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("cannot write online.json")
        }
    }

    companion object {
        const val FILE_NAME = "online.json"
        const val CHOICE_MADE = "choice_made"
        const val ONLINE = "online"
        const val CONTRIBUTE_OSM = "contribute_osm"
        const val NOTE_NOTICE_SEEN = "note_notice_seen"
        const val EDIT_NOTICE_SEEN = "edit_notice_seen"
        const val CHECK_MAP_UPDATES = "check_map_updates"
        const val CHECK_APP_UPDATES = "check_app_updates"
    }
}
