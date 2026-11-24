package io.github.spysyweeb.beamline.feature.states

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.File
import java.io.FileOutputStream

/** getpacks/ui.json (docs/architecture-downloads.md 6.5): the first-run choice, the notification question, a pending "show on map". */
data class UiChoices(
    val firstRunPickerDone: Boolean = false,
    val notificationsAsked: Boolean = false,
    /** The pack_id the map must show next ("us-pa"); null when there is none. */
    val showOnMap: String? = null,
    /**
     * Item 40: per pack_id, the newest catalog pack_version whose update the user has seen on the States
     * screen; the map's chip names an update only above it.
     */
    val updatesSeen: Map<String, Long> = emptyMap(),
)

/**
 * The store of ui.json. Every file access (the first read, each atomic write with fsync) runs on
 * [writer], a dispatcher that runs one task at a time (docs/architecture.md 1.2: JSON stores and
 * file work belong on Dispatchers.IO), never on the caller's thread: the read first, then each
 * change in the order it was asked for. [state] changes when a change has been applied; [loaded]
 * is false until the file was read, so nobody acts on the defaults of a file not read yet.
 */
internal class UiStore(private val file: File, private val scope: CoroutineScope, private val writer: CoroutineDispatcher) {
    private val _state = MutableStateFlow(UiChoices())
    val state: StateFlow<UiChoices> get() = _state
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> get() = _loaded

    init {
        scope.launch(writer) {
            _state.value = load()
            _loaded.value = true
        }
    }

    /** Applies [change] to the current choices on the writer, after every earlier change, and saves the result there. */
    fun update(change: (UiChoices) -> UiChoices) {
        scope.launch(writer) { applyNow(change) }
    }

    /**
     * For a task already running on the writer (GetPacksCore.actions is the same dispatcher): applies
     * [change] at once, so it is ordered with that task's other work (Cancel changes the choice
     * before the job ends). Never call it from another thread.
     */
    fun applyNow(change: (UiChoices) -> UiChoices) {
        val before = _state.value
        val next = change(before)
        if (next == before) return
        _state.value = next
        save(next)
    }

    private fun load(): UiChoices = try {
        val o = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
        fun bool(k: String) = (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false
        UiChoices(
            firstRunPickerDone = bool("first_run_picker_done"),
            notificationsAsked = bool("notifications_asked"),
            showOnMap = (o["show_on_map"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
            updatesSeen = (o["updates_seen"] as? JsonObject)?.mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()?.let { k to it }
            }?.toMap().orEmpty(),
        )
    } catch (_: Exception) {
        UiChoices() // missing or unreadable: as on a fresh install
    }

    private fun save(c: UiChoices) {
        val o = buildMap<String, kotlinx.serialization.json.JsonElement> {
            if (c.firstRunPickerDone) put("first_run_picker_done", JsonPrimitive(true))
            if (c.notificationsAsked) put("notifications_asked", JsonPrimitive(true))
            c.showOnMap?.let { put("show_on_map", JsonPrimitive(it)) }
            if (c.updatesSeen.isNotEmpty()) put("updates_seen", JsonObject(c.updatesSeen.mapValues { JsonPrimitive(it.value) }))
        }
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use {
                it.write(JsonObject(o).toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: java.io.IOException) {
            writeFailed()
        }
    }

    /** The JVM tests have no android.util.Log; a failed write is only logged, the choice stays in memory. */
    private fun writeFailed() {
        try {
            android.util.Log.w("Beamline.states", "ui.json not written")
        } catch (_: RuntimeException) {
        } catch (_: LinkageError) {
        }
    }
}
