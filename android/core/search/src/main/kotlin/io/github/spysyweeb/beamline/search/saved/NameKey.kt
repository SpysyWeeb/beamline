package io.github.spysyweeb.beamline.search.saved

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.search.Py
import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

/**
 * The name key of rule SP26 (docs/architecture-saved-places.md 2.7), the body of [savedNameKey]:
 * (1) NFKD; (2) combining marks (canonical combining class not 0, as the search fold drops them),
 * the variation selectors U+FE00-U+FE0F and format characters (general category Cf) dropped; (3)
 * lower case; (4) the apostrophes U+0027, U+2019, U+0060 removed, "&" -> " and "; (5) every run of
 * code points that are not letters, marks, numbers or symbols (L, M, N, S) -> one space; trimmed.
 * packbuilder/search/saved_places.py name_key() is the same function in Python.
 */
internal fun nameKeyOf(text: String): String {
    val nfkd = Normalizer.normalize(text, Normalizer.Form.NFKD)
    val kept = StringBuilder(nfkd.length)
    var i = 0
    while (i < nfkd.length) {
        val cp = nfkd.codePointAt(i)
        i += Character.charCount(cp)
        if (Py.isCombining(cp)) continue
        if (cp in 0xFE00..0xFE0F) continue
        if (Character.getType(cp) == Character.FORMAT.toInt()) continue
        kept.appendCodePoint(cp)
    }
    val t = kept.toString().lowercase()
        .replace("'", "").replace("’", "").replace("`", "")
        .replace("&", " and ")
    val out = StringBuilder(t.length)
    var gap = false
    var j = 0
    while (j < t.length) {
        val cp = t.codePointAt(j)
        j += Character.charCount(cp)
        if (isLmns(cp)) {
            if (gap && out.isNotEmpty()) out.append(' ')
            gap = false
            out.appendCodePoint(cp)
        } else {
            gap = true
        }
    }
    return out.toString()
}

/** General category L*, M*, N* or S*. */
private fun isLmns(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
    Character.OTHER_LETTER,
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER,
    Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
    else -> false
}

/**
 * Characters as the eye counts them (SP26): extended grapheme clusters by
 * java.text.BreakIterator.getCharacterInstance() (ICU on Android; JDK 20+ on the JVM), so a
 * family emoji of 7 code points counts 1.
 */
internal fun graphemeCount(text: String): Int {
    if (text.isEmpty()) return 0
    val it = BreakIterator.getCharacterInstance(Locale.ROOT)
    it.setText(text)
    var n = 0
    while (it.next() != BreakIterator.DONE) n++
    return n
}

/** A name as SP55 rule 1 stores it: control characters (general category Cc) removed, then trimmed. */
internal fun cleanName(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        if (Character.getType(cp) != Character.CONTROL.toInt()) sb.appendCodePoint(cp)
    }
    return sb.toString().trim()
}

/** Compares by Unicode code point (Python's str order), not by UTF-16 unit. */
internal fun compareCodePoints(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a.codePointAt(i)
        val cb = b.codePointAt(j)
        if (ca != cb) return ca.compareTo(cb)
        i += Character.charCount(ca)
        j += Character.charCount(cb)
    }
    return when {   // a prefix sorts first
        i < a.length -> 1
        j < b.length -> -1
        else -> 0
    }
}

/**
 * A point's text, "42.08418, -80.17321": latitude and longitude with five decimals (SP16; the
 * place name of a saved point, 3.1).
 */
fun pointText(p: GeoPoint): String = String.format(Locale.ROOT, "%.5f, %.5f", p.lat, p.lon)
