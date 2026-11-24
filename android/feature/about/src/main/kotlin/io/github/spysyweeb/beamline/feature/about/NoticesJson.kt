package io.github.spysyweeb.beamline.feature.about

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * notices.json, format `beamline-notices/1`, written by scripts/notices.py into the module's
 * assets (`assets/notices/notices.json`):
 *
 * ```
 * {"format": "beamline-notices/1", "full_text": "notices/NOTICES.txt",
 *  "entries": [{"name": ..., "version": ... | null, "licence": "<SPDX expression>",
 *               "copyright": ... | null, "url": ... | null, "text": "notices/licences/<id>.txt"}]}
 * ```
 *
 * Asset paths are relative to the APK's assets folder (AssetManager.open). Other keys are ignored.
 * A file that breaks these rules is refused with IllegalArgumentException (whose message names the
 * rule, never a value of the file).
 */
internal object NoticesJson {
    const val FORMAT = "beamline-notices/1"
    const val ASSET = "notices/notices.json"
    private const val ASSET_DIR = "notices/"

    fun parse(json: String): Notices {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("notices.json: not JSON")
        }
        val obj = root as? JsonObject ?: throw IllegalArgumentException("notices.json: not an object")
        require(obj.text("format") == FORMAT) { "notices.json: unknown format" }
        val fullText = obj.text("full_text") ?: throw IllegalArgumentException("notices.json: no full_text")
        requireAsset(fullText, "full_text")
        val list = obj["entries"] as? JsonArray ?: throw IllegalArgumentException("notices.json: no entries")
        require(list.isNotEmpty()) { "notices.json: entries is empty" }
        val entries = list.mapIndexed { i, e ->
            val o = e as? JsonObject ?: throw IllegalArgumentException("notices.json: entry $i is not an object")
            val name = o.text("name")
            require(!name.isNullOrBlank()) { "notices.json: entry $i has no name" }
            val licence = o.text("licence")
            require(!licence.isNullOrBlank()) { "notices.json: entry $i has no licence" }
            val text = o.text("text") ?: throw IllegalArgumentException("notices.json: entry $i has no text")
            requireAsset(text, "entry $i text")
            NoticeEntry(
                name = name,
                version = o.optionalText("version", i),
                licence = licence,
                copyright = o.optionalText("copyright", i),
                url = o.optionalText("url", i),
                textAsset = text,
            )
        }
        val seen = HashSet<Pair<String, String?>>()
        entries.forEachIndexed { i, e -> require(seen.add(e.name to e.version)) { "notices.json: entry $i repeats an earlier one" } }
        return Notices(entries, fullText)
    }

    /** Reads and parses the module's notices.json from the app's assets (call off the main thread). */
    fun load(context: Context): Notices =
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })

    private fun requireAsset(path: String, what: String) {
        require(path.startsWith(ASSET_DIR) && !path.endsWith("/")) { "notices.json: $what is not under $ASSET_DIR" }
        require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "notices.json: $what is not a plain path" }
    }

    /** A string value, or null when the key is missing; another type is refused. */
    private fun JsonObject.text(key: String): String? = when (val v: JsonElement? = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (v.isString) v.content else throw IllegalArgumentException("notices.json: $key is not a string")
        else -> throw IllegalArgumentException("notices.json: $key is not a string")
    }

    private fun JsonObject.optionalText(key: String, i: Int): String? {
        val v = try {
            text(key)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("notices.json: entry $i $key is not a string")
        }
        return v?.takeIf { it.isNotBlank() }
    }
}

/** The app's notices (assets/notices/notices.json of :feature:about); call off the main thread. */
fun loadNotices(context: Context): Notices = NoticesJson.load(context)
