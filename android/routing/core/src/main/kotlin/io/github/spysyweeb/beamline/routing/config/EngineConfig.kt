package io.github.spysyweeb.beamline.routing.config

import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.routing.preset.resourceText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Valhalla config the device engine is opened with.
 *
 * Base: the device valhalla.json that packbuilder/routing/packs.py writes (Valhalla 3.9.1 defaults
 * plus our build settings, tile path relative, no admin/timezone databases), bundled as
 * valhalla_base.json. On top: device_config.json "set" and presets.json "requires_config" (hard
 * exclusions, max_alternates, the CostMatrix settings), exactly as packs.device_settings() merges
 * them, and the absolute path of the one tile tar. The file is handed to valhalla-mobile as a
 * path (its `Valhalla(configPath)` constructor), so no key passes through the wrapper's config
 * model, which is where keys were dropped in the past.
 */
object EngineConfig {
    const val DEVICE_CONFIG_FORMAT = "beamline-routing-device-config/1"

    fun bundledBase(): JsonObject = Json.parseToJsonElement(resourceText("valhalla_base.json")).jsonObject

    fun bundledDeviceSettings(presets: PresetSet = PresetSet.bundled()): Map<String, JsonElement> =
        deviceSettings(resourceText("device_config.json"), presets)

    /** packs.device_settings(): device_config.json "set" plus the presets' requires_config; they must agree. */
    fun deviceSettings(deviceConfigJson: String, presets: PresetSet): Map<String, JsonElement> {
        val root = Json.parseToJsonElement(deviceConfigJson).jsonObject
        require(root["format"]?.jsonPrimitive?.content == DEVICE_CONFIG_FORMAT) { "device_config.json: wrong format" }
        val out = LinkedHashMap<String, JsonElement>(root["set"]!!.jsonObject)
        for ((k, v) in presets.requiresConfig) {
            val prev = out[k]
            require(prev == null || prev == v) { "device_config.json and presets.json disagree on $k" }
            out[k] = v
        }
        return out
    }

    /** The config for an engine on [tileExtractPath] (absolute path of the device's routing.tar). */
    fun build(
        tileExtractPath: String,
        base: JsonObject = bundledBase(),
        settings: Map<String, JsonElement> = bundledDeviceSettings(),
    ): JsonObject {
        var cfg = base
        for ((k, v) in settings) cfg = set(cfg, k, v)
        cfg = set(cfg, "mjolnir.tile_extract", JsonPrimitive(tileExtractPath))
        cfg = set(cfg, "mjolnir.tile_dir", JsonPrimitive(""))
        // Never set tile_url: valhalla-mobile attaches its HTTP tile getter only when it is set.
        cfg = set(cfg, "mjolnir.tile_url", JsonPrimitive(""))
        return cfg
    }

    fun get(cfg: JsonObject, dotted: String): JsonElement? {
        var node: JsonElement = cfg
        for (p in dotted.split('.')) node = (node as? JsonObject)?.get(p) ?: return null
        return node
    }

    fun set(cfg: JsonObject, dotted: String, value: JsonElement): JsonObject {
        val parts = dotted.split('.')
        fun go(obj: JsonObject, i: Int): JsonObject {
            val key = parts[i]
            val child = if (i == parts.lastIndex) value else go(obj[key] as? JsonObject ?: JsonObject(emptyMap()), i + 1)
            return JsonObject(LinkedHashMap(obj).apply { put(key, child) })
        }
        return go(cfg, 0)
    }

    /** Settings whose value in [cfg] is missing or different (numbers compared by value). */
    fun mismatches(cfg: JsonObject, settings: Map<String, JsonElement>): List<String> = settings.mapNotNull { (k, v) ->
        val have = get(cfg, k)
        val same = have == v || (have is JsonPrimitive && v is JsonPrimitive && !have.isString && !v.isString &&
            have.content.toDoubleOrNull() != null && have.content.toDoubleOrNull() == v.content.toDoubleOrNull())
        if (same) null else "$k: expected $v, found $have"
    }

    /** String values that look like absolute paths (a portable base config has none). */
    fun absolutePaths(cfg: JsonElement, path: String = ""): List<String> = when (cfg) {
        is JsonObject -> cfg.flatMap { (k, v) -> absolutePaths(v, if (path.isEmpty()) k else "$path.$k") }
        is JsonPrimitive -> if (cfg.isString && (cfg.content.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(cfg.content)))
            listOf("$path=${cfg.content}") else emptyList()
        else -> emptyList()
    }
}
