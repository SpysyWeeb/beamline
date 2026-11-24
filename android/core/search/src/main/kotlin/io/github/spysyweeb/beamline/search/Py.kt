package io.github.spysyweeb.beamline.search

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

/**
 * Small helpers that reproduce Python behaviour the reference engine relies on, so the port can
 * follow packbuilder/search/query.py and packbuilder/addresses/addrlib line by line.
 */
internal object Py {
    /** Python's round(x, n) for floats: exact decimal value, ties to even. */
    fun round(x: Double, n: Int): Double {
        if (x.isNaN() || x.isInfinite()) return x
        return BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toDouble()
    }

    /**
     * Python 3.12's built-in sum() over floats, started from the int 0: the first value is
     * taken as is, the rest are added with Neumaier compensation, and the compensation is added
     * at the end when it is non-zero and finite (CPython 3.12.10 Python/bltinmodule.c).
     */
    fun sum(xs: DoubleArray): Double {
        if (xs.isEmpty()) return 0.0
        var f = 0.0 + xs[0]
        var c = 0.0
        for (i in 1 until xs.size) {
            val x = xs[i]
            val t = f + x
            c += if (kotlin.math.abs(f) >= kotlin.math.abs(x)) (f - t) + x else (x - t) + f
            f = t
        }
        if (c != 0.0 && c.isFinite()) f += c
        return f
    }

    /** str.split() without arguments: split on runs of whitespace, no empty strings. */
    fun split(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && isSpace(s[i])) i++
            if (i >= n) break
            val start = i
            while (i < n && !isSpace(s[i])) i++
            out.add(s.substring(start, i))
        }
        return out
    }

    /** Python str.isspace() for one character (ASCII part plus the Unicode spaces Java knows). */
    fun isSpace(c: Char): Boolean =
        c == ' ' || c in '\t'..'\r' || c in '\u001c'..'\u001f' || c == '\u0085' || c == ' ' ||
            Character.isSpaceChar(c) || Character.isWhitespace(c)

    /** str.isdigit() for ASCII strings (the only strings this code calls it on). */
    fun isDigits(s: String): Boolean = s.isNotEmpty() && s.all { it in '0'..'9' }

    /** str.isalpha() for ASCII strings. */
    fun isAlpha(s: String): Boolean = s.isNotEmpty() && s.all { it in 'a'..'z' || it in 'A'..'Z' }

    /** str(int(digits)): the decimal digits without leading zeros ("0" stays "0"). */
    fun intStr(digits: String): String {
        val t = digits.trimStart('0')
        return t.ifEmpty { "0" }
    }

    /** str.capitalize(): first character title case, the rest lower case. */
    fun capitalize(s: String): String {
        if (s.isEmpty()) return s
        val low = s.lowercase()
        val first = low.codePointAt(0)
        val rest = low.substring(Character.charCount(first))
        return String(Character.toChars(Character.toTitleCase(first))) + rest
    }

    private val combiningCache = HashMap<Int, Boolean>()

    /**
     * unicodedata.combining(c) != 0 (the canonical combining class is not zero), worked out
     * with java.text.Normalizer: canonical reordering swaps two adjacent marks when the first
     * has the higher non-zero class, so a character with class 0 never moves.
     *   U+0301 (class 230) + c moves when 0 < class(c) < 230;
     *   c + U+0334 (class 1) moves when class(c) > 1.
     */
    fun isCombining(cp: Int): Boolean {
        if (cp < 0x300) return false
        synchronized(combiningCache) {
            combiningCache[cp]?.let { return it }
            val c = String(Character.toChars(cp))
            val a = "́" + c
            val b = c + "̴"
            val r = Normalizer.normalize(a, Normalizer.Form.NFD) != Normalizer.normalize("́", Normalizer.Form.NFD) + Normalizer.normalize(c, Normalizer.Form.NFD) ||
                Normalizer.normalize(b, Normalizer.Form.NFD) != Normalizer.normalize(c, Normalizer.Form.NFD) + "̴"
            combiningCache[cp] = r
            return r
        }
    }
}

/** Great-circle distances exactly as the Python reference writes them (StrictMath = fdlibm,
 *  which gives the same bits on the JVM and on Android). */
object Geo {
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val s1 = StrictMath.sin((p2 - p1) / 2)
        val s2 = StrictMath.sin(Math.toRadians(lon2 - lon1) / 2)
        // Python: sin(...) ** 2 + cos(p1) * cos(p2) * sin(...) ** 2  (x ** 2 written as x * x)
        val a = s1 * s1 + StrictMath.cos(p1) * StrictMath.cos(p2) * (s2 * s2)
        return 2 * 6371.0088 * StrictMath.asin(StrictMath.sqrt(minOf(1.0, a)))
    }
}

/** A position (degrees). */
data class LatLon(val lat: Double, val lon: Double)
