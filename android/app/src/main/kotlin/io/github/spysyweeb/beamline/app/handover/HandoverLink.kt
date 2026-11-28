package io.github.spysyweeb.beamline.app.handover

import io.github.spysyweeb.beamline.api.model.GeoPoint

/** Item 52 (rule H2): what a link handed over by another app asks for. [toString] prints none of it (PV2). */
sealed interface Handover {
    /** A point; [label]: the link's own name for it ("Quokka Den"), null when it gives none. */
    data class Point(val point: GeoPoint, val label: String?) : Handover {
        override fun toString(): String = "Handover.Point"
    }

    /** Words to look up (at most [HandoverLink.MAX_TEXT_CHARS]), around [near] when the link gives a point to search near. */
    data class Text(val text: String, val near: GeoPoint?) : Handover {
        override fun toString(): String = "Handover.Text"
    }

    /** Nothing Beamline can use: another form, bad numbers, an empty query. */
    data object Unreadable : Handover
}

/**
 * Item 52 (docs/architecture.md 3.11, rule H2): reads a link another app hands over ("Get Directions", a map link):
 * `geo:` (RFC 5870 and Android's `geo:0,0?q=` forms), `google.navigation:`, Google Maps web links and OpenStreetMap
 * map links. Pure: no Android type, no I/O, no logging. Every number is checked (range, finite, 0,0 is no point),
 * every text decoded leniently, cleaned and cut at [MAX_TEXT_CHARS]; a link longer than [MAX_LINK_CHARS] is not read.
 */
object HandoverLink {
    const val MAX_LINK_CHARS = 8192
    const val MAX_TEXT_CHARS = 200

    private const val N = """[+-]?\d{1,3}(?:\.\d{1,20})?"""
    private val NUM = Regex("^$N$")
    private val COORDS = Regex("""^\s*(?:loc:\s*)?($N)\s*,\s*($N)\s*$""", RegexOption.IGNORE_CASE)

    /** K11: a coordinate pair at the start of a value (quoted or not), and the rest after it. */
    private val PREFIX = Regex("""^\s*['"]?(?:loc:\s*)?($N)\s*,\s*($N)['"]?(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** What looks like coordinates (also with too many digits): never words. */
    private val SHAPE = Regex("""^\s*(?:loc:\s*)?[+-]?\d+(?:\.\d+)?\s*,\s*[+-]?\d+(?:\.\d+)?\s*(?:\(.*\))?\s*$""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val ALT = Regex("""^[+-]?\d+(?:\.\d+)?$""")
    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*):""")
    private val PLACE_POINT = Regex("""!3d($N)!4d($N)""")
    private val OSM_MAP = Regex("""^map=\d{1,2}/($N)/($N)$""")
    private val QUESTION = Regex("%3F", RegexOption.IGNORE_CASE)

    fun parse(link: String): Handover {
        val s = link.trim()
        if (s.isEmpty() || s.length > MAX_LINK_CHARS) return Handover.Unreadable
        val scheme = SCHEME.find(s)?.groupValues?.get(1)?.lowercase() ?: return Handover.Unreadable
        val rest = s.substring(scheme.length + 1)
        return when (scheme) {
            "geo" -> geo(rest)
            "google.navigation" -> navigation(rest)
            "http", "https" -> web(rest)
            else -> Handover.Unreadable
        }
    }

    // ---------------------------------------------------------------- geo: (rule G)

    private fun geo(rest0: String): Handover {
        var rest = rest0.removePrefix("//")
        // K13: a link whose "?" was percent-encoded with everything else (Uri.fromParts): decoded once
        val noFragment = rest.substringBefore('#')
        if (!noFragment.contains('?') && QUESTION.containsMatchIn(noFragment)) rest = decode(noFragment, plusIsSpace = false)
        val main = rest.substringBefore('#')
        val params = query(main.substringAfter('?', ""))
        // K13: the head is always percent-decoded before it is split ("42.129%2C-80.085")
        val parts = decode(main.substringBefore('?'), plusIsSpace = false).split(';')
        for (p in parts.drop(1)) {
            val k = p.substringBefore('=').trim().lowercase()
            if (k == "crs" && p.substringAfter('=', "").trim().lowercase() != "wgs84") return Handover.Unreadable
        }
        val c = parts[0].trim()
        var head: GeoPoint? = null
        if (c.isNotEmpty()) {
            val n = c.split(',').map { it.trim() }
            if (n.size !in 2..3 || !NUM.matches(n[0]) || !NUM.matches(n[1]) || (n.size == 3 && !ALT.matches(n[2]))) return Handover.Unreadable
            val lat = n[0].toDouble()
            val lon = n[1].toDouble()
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return Handover.Unreadable
            head = pointOf(n[0], n[1])
        }
        qValue(params["q"], rest.substringAfter('?', ""))?.let { q ->
            when (val v = value(q)) {
                is Handover.Point -> return v
                is Handover.Text -> return Handover.Text(v.text, head)
                Handover.Unreadable -> Unit   // coordinates that are no point, or an empty q: left out
            }
        }
        return head?.let { Handover.Point(it, null) } ?: Handover.Unreadable
    }

    // ---------------------------------------------------------------- google.navigation: (rule N)

    private fun navigation(rest0: String): Handover {
        val raw = rest0.trimStart('/').removePrefix("?")
        val params = query(raw.substringBefore('#'))
        val q = qValue(params["q"], raw)?.let(::value)
        val ll = clean(params["ll"])?.let { COORDS.matchEntire(it) }?.let(::pointOf)
        if (ll != null) return Handover.Point(ll, (q as? Handover.Text)?.text)
        return q ?: Handover.Unreadable
    }

    /**
     * K11: the decoded q, or, when it is a pair followed by a "(" that is never closed (a label with an unencoded
     * "&" or "#"), the raw text from after "q=" to the end of [rawQuery] (its "&" and "#" pieces included), decoded.
     */
    private fun qValue(decoded: String?, rawQuery: String): String? {
        if (decoded == null) return null
        val m = PREFIX.matchEntire(decoded) ?: return decoded
        val after = m.groupValues[3].trimStart()
        if (!after.startsWith("(") || after.contains(')')) return decoded
        var i = 0
        while (i < rawQuery.length) {
            val end = rawQuery.indexOf('&', i).let { if (it < 0) rawQuery.length else it }
            val pair = rawQuery.substring(i, end)
            if (decode(pair.substringBefore('=')).trim().lowercase() == "q" && pair.contains('=')) {
                return decode(rawQuery.substring(i + pair.indexOf('=') + 1))
            }
            i = end + 1
        }
        return decoded
    }

    // ---------------------------------------------------------------- web links (rule W)

    private fun web(rest: String): Handover {
        if (!rest.startsWith("//")) return Handover.Unreadable
        val r = rest.substring(2)
        val end = r.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) r.length else it }
        val host = r.substring(0, end).substringAfterLast('@').substringBefore(':').lowercase().removeSuffix(".")
        val tail = r.substring(end)
        val fragment = tail.substringAfter('#', "")
        val noFragment = tail.substringBefore('#')
        val path = noFragment.substringBefore('?')
        val params = query(noFragment.substringAfter('?', ""))
        return when (host) {
            "maps.google.com", "www.google.com", "google.com" -> google(host, path, params)
            "www.openstreetmap.org", "openstreetmap.org" -> osm(path, params, fragment)
            else -> Handover.Unreadable
        }
    }

    private fun google(host: String, path0: String, params: Map<String, String>): Handover {
        val path = if (host == "maps.google.com" && (path0.isEmpty() || path0 == "/")) "/maps" else path0
        if (path != "/maps" && !path.startsWith("/maps/")) return Handover.Unreadable
        val seg = path.removePrefix("/maps").split('/').filter { it.isNotEmpty() }
        fun v(decoded: String?): Handover = decoded?.let(::value) ?: Handover.Unreadable
        return when {
            seg.isEmpty() -> when {
                params["daddr"] != null -> v(params["daddr"]!!.substringAfterLast(" to:"))
                params["q"] != null -> v(params["q"])
                else -> clean(params["ll"])?.let { COORDS.matchEntire(it) }?.let(::pointOf)?.let { Handover.Point(it, null) } ?: Handover.Unreadable
            }
            seg[0] == "dir" -> params["destination"]?.let(::v)
                ?: seg.drop(1).lastOrNull { !it.startsWith("@") && !it.startsWith("data=") }?.let { v(decode(it)) } ?: Handover.Unreadable
            seg[0] == "search" -> params["query"]?.let(::v) ?: seg.getOrNull(1)?.takeUnless { it.startsWith("@") }?.let { v(decode(it)) } ?: Handover.Unreadable
            seg[0] == "place" && seg.size > 1 -> {
                val name = clean(decode(seg[1]))
                val p = PLACE_POINT.findAll(path).lastOrNull()?.let(::pointOf) ?: seg.firstOrNull { it.startsWith("@") }?.let(::at)
                if (p != null) Handover.Point(p, name?.takeUnless { SHAPE.matches(it) }) else v(name)
            }
            seg[0].startsWith("@") -> at(seg[0])?.let { Handover.Point(it, null) } ?: Handover.Unreadable
            else -> Handover.Unreadable
        }
    }

    private fun osm(path: String, params: Map<String, String>, fragment: String): Handover {
        if (path.isNotEmpty() && path != "/") return Handover.Unreadable
        val mlat = params["mlat"]?.trim()
        val mlon = params["mlon"]?.trim()
        if (mlat != null && mlon != null) pointOf(mlat, mlon)?.let { return Handover.Point(it, null) }
        for (part in fragment.split('&')) OSM_MAP.matchEntire(part.trim())?.let(::pointOf)?.let { return Handover.Point(it, null) }
        return Handover.Unreadable
    }

    // ---------------------------------------------------------------- values (rules P4, P5; K11)

    /**
     * P5 with K11, on a decoded value before any cut: a coordinate pair at its start decides (with the rest empty, in
     * parentheses, or after a blank; plain words after it only when both numbers have a fraction): a valid pair is a
     * Point whose label is the rest (one leading "(" and everything from the last ")" on removed, cleaned); an invalid
     * one Unreadable. Otherwise the cleaned text: empty Unreadable, the shape of coordinates Unreadable, else words.
     */
    private fun value(v: String): Handover {
        PREFIX.matchEntire(v)?.let { m ->
            val rest = m.groupValues[3]
            val after = rest.trimStart()
            val words = after.isNotEmpty() && !after.startsWith("(")
            val decides = rest.isEmpty() || rest[0] == '(' || rest[0].isWhitespace()
            val fractions = m.groupValues[1].contains('.') && m.groupValues[2].contains('.')
            if (decides && (!words || fractions)) {
                val point = pointOf(m) ?: return Handover.Unreadable
                var label = after.trimEnd()
                if (label.startsWith("(")) label = label.substring(1)
                if (label.contains(')')) label = label.substringBeforeLast(')')
                return Handover.Point(point, clean(label))
            }
        }
        val c = clean(v) ?: return Handover.Unreadable
        if (SHAPE.matches(c)) return Handover.Unreadable
        return Handover.Text(c, null)
    }

    private fun at(seg: String): GeoPoint? = seg.removePrefix("@").split(',').takeIf { it.size >= 2 }?.let { pointOf(it[0].trim(), it[1].trim()) }
    private fun pointOf(m: MatchResult): GeoPoint? = pointOf(m.groupValues[1], m.groupValues[2])

    /** P4: a valid pair, or null (also for 0,0). */
    private fun pointOf(a: String, b: String): GeoPoint? {
        if (!NUM.matches(a) || !NUM.matches(b)) return null
        val lat = a.toDouble()
        val lon = b.toDouble()
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        return GeoPoint(lat, lon)
    }

    // ---------------------------------------------------------------- texts (rules P1-P3)

    /** P2: split at "&"; keys decoded, trimmed, lower-cased; values decoded; the first of a key wins. */
    internal fun query(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in s.split('&')) {
            if (pair.isEmpty()) continue
            val k = decode(pair.substringBefore('=')).trim().lowercase()
            if (k.isNotEmpty()) out.putIfAbsent(k, decode(pair.substringAfter('=', "")))
        }
        return out
    }

    /** P1: "%" and two hex digits is that byte, "+" a space (unless [plusIsSpace] is false), malformed UTF-8 U+FFFD. Never throws. */
    internal fun decode(s: String, plusIsSpace: Boolean = true): String {
        val out = java.io.ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && Character.digit(s[i + 1], 16) >= 0 && Character.digit(s[i + 2], 16) >= 0) {
                out.write(Character.digit(s[i + 1], 16) * 16 + Character.digit(s[i + 2], 16))
                i += 3
            } else if (c == '+' && plusIsSpace) {
                out.write(' '.code)
                i++
            } else {
                val cp = s.codePointAt(i)
                out.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                i += Character.charCount(cp)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * P3 with K17: control characters, U+2028, U+2029 and every whitespace or space character are blanks; format
     * characters (bidi overrides, zero-width spaces, U+FEFF) are dropped, except the joiners U+200C and U+200D that
     * emoji and some scripts need; leading blanks go, each run inside becomes one space; at most [MAX_TEXT_CHARS]
     * code points (never half a surrogate pair); trimmed; empty: null.
     */
    internal fun clean(s: String?): String? {
        if (s == null) return null
        val sb = StringBuilder()
        var n = 0
        var blank = false
        var i = 0
        while (i < s.length && n < MAX_TEXT_CHARS) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            if (type == Character.FORMAT.toInt() && cp != 0x200C && cp != 0x200D) continue
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp) || type == Character.CONTROL.toInt() || cp == 0x2028 || cp == 0x2029) {
                blank = sb.isNotEmpty()
                continue
            }
            if (blank) {
                sb.append(' ')
                blank = false
                if (++n >= MAX_TEXT_CHARS) break
            }
            sb.appendCodePoint(cp)
            n++
        }
        return sb.toString().trim().ifEmpty { null }
    }
}
