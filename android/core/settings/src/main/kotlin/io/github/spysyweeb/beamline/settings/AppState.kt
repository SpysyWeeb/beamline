// FILE: core/settings/src/main/kotlin/io/github/spysyweeb/beamline/settings/AppState.kt
package io.github.spysyweeb.beamline.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/** Things the app remembers that are not choices in Settings. */
data class AppState(
    /** The version of the first-run notice the user accepted; 0 = never. */
    val firstRunNoticeAccepted: Int = 0,
)

/**
 * app-state.json (`{"first_run_notice_accepted": 1}`), written atomically like settings.json.
 * A missing, broken or foreign file reads as the defaults.
 */
class AppStateStore(private val file: File) {
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    @Synchronized
    fun update(change: (AppState) -> AppState) {
        val next = change(_state.value)
        if (next == _state.value) return
        save(next)
        _state.value = next
    }

    private fun load(): AppState = try {
        val obj = if (file.isFile) Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject else null
        AppState(firstRunNoticeAccepted = (obj?.get(FIRST_RUN_NOTICE_ACCEPTED) as? JsonPrimitive)?.intOrNull ?: 0)
    } catch (e: Exception) {
        AppState()
    }

    private fun save(s: AppState) {
        val text = JsonObject(mapOf(FIRST_RUN_NOTICE_ACCEPTED to JsonPrimitive(s.firstRunNoticeAccepted))).toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("cannot write ${file.name}")
        }
    }

    companion object {
        const val FIRST_RUN_NOTICE_ACCEPTED = "first_run_notice_accepted"
    }
}
