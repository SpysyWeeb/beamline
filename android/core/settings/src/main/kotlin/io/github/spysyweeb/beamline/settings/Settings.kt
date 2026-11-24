package io.github.spysyweeb.beamline.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.io.File

/** A choice with a stable key: the key is what is stored, so labels can change freely. */
interface Choice {
    val key: String
}

enum class MapStyle(override val key: String) : Choice {
    /**
     * Follows sunrise and sunset ([SunRule]; batch A, 2026-11-18). A file written before, where
     * "automatic" meant the phone's dark mode, reads as this new Automatic.
     */
    AUTOMATIC("automatic"),
    /** Follows the system's dark mode (what Automatic did up to version 0.1.1). */
    SAME_AS_PHONE("phone"),
    DAY("day"),
    NIGHT("night"),
}

/**
 * The map's frame rate. The owner removed the 30 frames a second choice (2026-11-16): a file that
 * stored "30" reads as [FPS_60] (see [SettingsStore]).
 */
enum class FrameRate(override val key: String, val fps: Int?) : Choice {
    /** The display's highest refresh rate. */
    SCREEN("screen", null),
    FPS_60("60", 60),
}

/** Stored only: guidance and audio do not exist yet. */
enum class GuidanceSound(override val key: String) : Choice {
    SILENT("silent"),
    CHIME("chime"),
    VOICE("voice"),
}

enum class Units(override val key: String) : Choice {
    MILES("miles"),
    KILOMETRES("kilometres"),
}

/** Everything the user can set. The defaults are the owner's: Automatic, Screen rate, Silent, miles. */
data class Settings(
    val mapStyle: MapStyle = MapStyle.AUTOMATIC,
    val frameRate: FrameRate = FrameRate.SCREEN,
    val guidanceSound: GuidanceSound = GuidanceSound.SILENT,
    val units: Units = Units.MILES,
    /**
     * The route type last chosen (a presets.json route type id). Stored as given: the navigation
     * session checks it against the offered types and falls back to the presets' default.
     */
    val routeType: String = "fastest",
    val avoidHighways: Boolean = false,
    val avoidTolls: Boolean = false,
    /** Whether the "House numbers" box is ticked when a state is added. */
    val includeAddresses: Boolean = true,
) {
    /**
     * Whether the night map is shown, given the system's dark mode and [sunDark], the sun rule's
     * answer ([SunRule]; null when it has no place to use: Automatic then follows the phone).
     */
    fun nightMap(systemDark: Boolean, sunDark: Boolean? = null): Boolean = when (mapStyle) {
        MapStyle.AUTOMATIC -> sunDark ?: systemDark
        MapStyle.SAME_AS_PHONE -> systemDark
        MapStyle.DAY -> false
        MapStyle.NIGHT -> true
    }
}

/**
 * Settings kept in one small JSON file (`{"map_style":"automatic", ...}`), written atomically
 * (temporary file, then rename). Unknown or missing values fall back to the default, so an older
 * or newer file never stops the app. UI-free: screens observe [settings] and call [update].
 */
class SettingsStore(private val file: File) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @Synchronized
    fun update(change: (Settings) -> Settings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        save(next)
        _settings.value = next
    }

    private fun load(): Settings {
        val obj = try {
            if (!file.isFile) return Settings()
            Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject ?: return Settings()
        } catch (e: Exception) {
            return Settings()
        }
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
        fun bool(k: String, default: Boolean) = (obj[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default
        val d = Settings()
        return Settings(
            mapStyle = pick(str(MAP_STYLE), d.mapStyle),
            frameRate = pick(str(FRAME_RATE)?.let { if (it == RETIRED_FPS_30) FrameRate.FPS_60.key else it }, d.frameRate),
            guidanceSound = pick(str(GUIDANCE_SOUND), d.guidanceSound),
            units = pick(str(UNITS), d.units),
            routeType = (obj[ROUTE_TYPE] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: d.routeType,
            avoidHighways = bool(AVOID_HIGHWAYS, d.avoidHighways),
            avoidTolls = bool(AVOID_TOLLS, d.avoidTolls),
            includeAddresses = bool(INCLUDE_ADDRESSES, d.includeAddresses),
        )
    }

    private fun save(s: Settings) {
        val text = JsonObject(
            mapOf(
                MAP_STYLE to JsonPrimitive(s.mapStyle.key),
                FRAME_RATE to JsonPrimitive(s.frameRate.key),
                GUIDANCE_SOUND to JsonPrimitive(s.guidanceSound.key),
                UNITS to JsonPrimitive(s.units.key),
                ROUTE_TYPE to JsonPrimitive(s.routeType),
                AVOID_HIGHWAYS to JsonPrimitive(s.avoidHighways),
                AVOID_TOLLS to JsonPrimitive(s.avoidTolls),
                INCLUDE_ADDRESSES to JsonPrimitive(s.includeAddresses),
            ),
        ).toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // renameTo does not replace on every platform; fall back to delete + rename.
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("cannot write ${file.name}")
        }
    }

    companion object {
        const val MAP_STYLE = "map_style"
        const val FRAME_RATE = "frame_rate"
        const val GUIDANCE_SOUND = "guidance_sound"
        const val UNITS = "units"
        const val ROUTE_TYPE = "route_type"
        const val AVOID_HIGHWAYS = "avoid_highways"
        const val AVOID_TOLLS = "avoid_tolls"
        const val INCLUDE_ADDRESSES = "include_addresses"

        /** The stored key of the removed 30 frames a second choice; read as 60. */
        private const val RETIRED_FPS_30 = "30"

        private inline fun <reified T> pick(key: String?, default: T): T where T : Enum<T>, T : Choice =
            enumValues<T>().firstOrNull { it.key == key } ?: default
    }
}
