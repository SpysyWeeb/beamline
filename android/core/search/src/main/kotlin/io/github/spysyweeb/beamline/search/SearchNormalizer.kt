package io.github.spysyweeb.beamline.search

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The search key tables (abbreviations.json, copied from packbuilder/search). Key order of the
 * JSON objects is kept (the reference relies on it for the connector list).
 */
class SearchTables(json: JsonObject) {
    val suffix: Map<String, String> = strMap(json["suffix"]!!.jsonObject)
    val prefix: Map<String, String> = strMap(json["prefix"]!!.jsonObject)
    val directional: Map<String, String> = strMap(json["directional"]!!.jsonObject)
    val anywhere: Map<String, String> = strMap(json["anywhere"]!!.jsonObject)
    val routePrefix: Map<String, String> = json["route_prefix"]?.jsonObject?.let { strMap(it) } ?: emptyMap()
    val stop: Set<String> = json["stopwords"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    val stateNames: Set<String> = json["state_names"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
    val streetTypes: Set<String> = json["street_types"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()
    /** kind -> phrases, in file order ("near", "in", "on"). */
    val connectors: LinkedHashMap<String, List<String>> = LinkedHashMap<String, List<String>>().apply {
        for ((k, v) in json["connectors"]!!.jsonObject) put(k, v.jsonArray.map { it.jsonPrimitive.content })
    }
    val nearMe: List<String> = json["near_me"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun strMap(o: JsonObject): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        for ((k, v) in o) if (!k.startsWith("_")) m[k] = v.jsonPrimitive.content
        return m
    }
}

/** Port of packbuilder/search/normalize.py: canonical search keys. */
class SearchNormalizer(val tb: SearchTables) {

    /** Steps 1-4: NFKD, drop combining marks, lower case, apostrophes, '&', non-alphanumerics ([foldText]). */
    fun fold(text: String?): String = foldText(text)

    /** Step 6: positional abbreviation expansion; multi-word expansions become several tokens. */
    fun expandTokens(tokens: List<String>): List<String> {
        val n = tokens.size
        if (n == 0) return tokens
        val out = ArrayList<String>(n + 2)
        var lastCore = n - 1
        while (lastCore > 0 && tokens[lastCore] in tb.directional) lastCore--
        for ((i, tok) in tokens.withIndex()) {
            var rep: String? = null
            if (i + 1 < n && tok in tb.routePrefix && Py.isDigits(tokens[i + 1])) {
                out.addAll(Py.split(tb.routePrefix.getValue(tok)))
                continue
            }
            if (n > 1) {
                if (i == 0 && tok in tb.prefix) rep = tb.prefix[tok]
                else if (i == lastCore && i > 0 && tok in tb.suffix) rep = tb.suffix[tok]
                else if ((i == 0 || i == n - 1) && tok in tb.directional) rep = tb.directional[tok]
            }
            if (rep == null && tok in tb.anywhere) rep = tb.anywhere[tok]
            if (rep == null) out.add(tok) else out.addAll(Py.split(rep))
        }
        return out
    }

    fun tokens(text: String?, expand: Boolean = true, dropStop: Boolean = true): List<String> {
        var toks = Py.split(fold(text))
        if (expand) toks = expandTokens(toks)
        if (dropStop) {
            val kept = toks.filter { it !in tb.stop }
            if (kept.isNotEmpty()) toks = kept
        }
        return toks
    }

    fun key(text: String?): String = tokens(text).joinToString(" ")

    fun streetKey(text: String?): String = key(text)

    companion object {
        /** FNV-1a 32-bit of the UTF-8 bytes as a signed 32-bit integer (spell_del keys). */
        fun fnv1a32(s: String): Int {
            var h = 0x811C9DC5.toInt()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toInt() and 0xFF)
                h *= 0x01000193
            }
            return h
        }

        /** The word and every string made by deleting one character (insertion order kept). */
        fun deletes1(word: String): LinkedHashSet<String> {
            val out = LinkedHashSet<String>()
            out.add(word)
            for (i in word.indices) out.add(word.substring(0, i) + word.substring(i + 1))
            return out
        }
    }
}
