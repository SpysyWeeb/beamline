package io.github.spysyweeb.beamline.download.net

import java.io.IOException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException

/** A response that breaks the server rules of docs/architecture-downloads.md 1.4 (BAD_RESPONSE); [rule] is a fixed sentence. */
internal class BadResponse(val rule: String) : IOException(rule)

/** Carries a [NetworkRuleRefused] out of OkHttp's DNS callback, which may only throw IOException. */
internal class RefusedByRule(val refusal: NetworkRuleRefused) : IOException(refusal.message)

internal object NetRules {

    /**
     * PackHttp.isPublic on the raw address: false for loopback, private (site-local), link-local,
     * any-local ("this network"), multicast and broadcast IPv4; for IPv6 loopback, unspecified,
     * link-local, site-local, multicast, unique-local (fc00::/7) and the IPv4-compatible block, and
     * an IPv4-mapped address by its IPv4 rules.
     */
    fun isPublicAddress(a: ByteArray): Boolean {
        fun u(i: Int) = a[i].toInt() and 0xff
        return when (a.size) {
            4 -> !(u(0) == 127 || u(0) == 10 || (u(0) == 172 && u(1) in 16..31) || (u(0) == 192 && u(1) == 168) ||
                (u(0) == 169 && u(1) == 254) || u(0) == 0 || u(0) >= 224)
            16 -> {
                val firstTwelveZero = (0 until 12).all { u(it) == 0 }
                val mapped = (0 until 10).all { u(it) == 0 } && u(10) == 0xff && u(11) == 0xff
                when {
                    mapped -> isPublicAddress(a.copyOfRange(12, 16))
                    firstTwelveZero -> false // ::, ::1 and the deprecated IPv4-compatible block
                    u(0) == 0xff -> false // multicast
                    (u(0) and 0xfe) == 0xfc -> false // unique-local fc00::/7
                    u(0) == 0xfe && (u(1) and 0xc0) == 0x80 -> false // link-local fe80::/10
                    u(0) == 0xfe && (u(1) and 0xc0) == 0xc0 -> false // site-local fec0::/10
                    else -> true
                }
            }
            else -> false
        }
    }

    /** True when [t]'s cause chain holds a certificate that is not yet valid or has expired (the phone's clock, 1.4). */
    fun isClockFailure(t: Throwable): Boolean {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth < 32) {
            if (c is CertificateNotYetValidException || c is CertificateExpiredException) return true
            c = c.cause
            depth++
        }
        return false
    }

    // First byte and full size from a Content-Range value ("bytes 0-99/1000", or a star for the
    // range on a 416); nulls when absent or odd.
    fun contentRange(value: String?): Pair<Long?, Long?> {
        if (value == null) return null to null
        val m = Regex("^\\s*bytes\\s+(?:(\\d+)-(\\d+)|\\*)/(\\d+|\\*)\\s*$", RegexOption.IGNORE_CASE).matchEntire(value) ?: return null to null
        val start = m.groupValues[1].takeIf { it.isNotEmpty() }?.toLongOrNull()
        val total = m.groupValues[3].toLongOrNull()
        return start to total
    }
}
