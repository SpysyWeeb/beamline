// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/net/CatalogAddress.kt
package io.github.spysyweeb.beamline.download.net

/**
 * The configured catalog address and every URL it may lead to (network rule N2).
 *
 * [url] is the catalog's own URL as configured. [scope] is the folder above the catalog's folder
 * (`https://github.com/O/R/releases/download/catalog/catalog.json` has the scope
 * `https://github.com/O/R/releases/download/`; `http://10.0.2.2:8765/catalog/catalog.json` has
 * `http://10.0.2.2:8765/`). Every file a catalog names must resolve inside [scope]. [local]: the
 * host is a loopback or private IPv4 literal or "localhost" (debug builds only): PackHttp then allows
 * that one host's address and AndroidNetworkWatcher uses the rules for a local server (section 5.2).
 */
class CatalogAddress private constructor(val url: String, val scope: String, val cleartext: Boolean, val local: Boolean) {

    /**
     * The absolute URL of a catalog-relative [path]. Throws IllegalArgumentException when [path] does
     * not match [PATH_RULE] or the result is not inside [scope].
     */
    fun resolve(path: String): String = AddressRules.resolve(this, path)

    /** Whether [absoluteUrl] is the catalog itself or inside [scope]. */
    fun contains(absoluteUrl: String): Boolean = AddressRules.contains(this, absoluteUrl)

    companion object {
        /**
         * One relative path: an optional single leading "../", then segments of letters, digits, '.',
         * '_' and '-', each starting with a letter or digit. No query, fragment, scheme or "//".
         */
        val PATH_RULE = Regex("^(\\.\\./)?[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*$")

        /**
         * Null for a blank [raw]: downloads are off in this build. Throws IllegalArgumentException
         * for anything else the rules refuse: a scheme other than https, or http when
         * [allowCleartext] is false or the host is not a private or loopback IPv4 literal; a private
         * or loopback host when [allowCleartext] is false; a user name or password, a query or a
         * fragment in the URL; a path that does not end in ".json".
         */
        fun parse(raw: String, allowCleartext: Boolean): CatalogAddress? = AddressRules.parse(raw, allowCleartext)?.let { CatalogAddress(it.url, it.scope, it.cleartext, it.local) }

        /** "localhost", or an IPv4 literal in 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 or 127.0.0.0/8. */
        fun isPrivateOrLoopback(host: String): Boolean = AddressRules.isPrivateOrLoopback(host)

        /**
         * Whether a redirect from [from] to [to] may be followed, judged on the URLs: from https only
         * to https on a host that is not a private, loopback or link-local (169.254.0.0/16) IPv4
         * literal or an IPv6 literal; from http (a debug build's own server) only to the same scheme,
         * host and port. PackHttp checks the resolved addresses as well (PackHttp.isPublic). The
         * hop count is PackHttp's.
         */
        fun redirectAllowed(from: String, to: String): Boolean = AddressRules.redirectAllowed(from, to)
    }
}
