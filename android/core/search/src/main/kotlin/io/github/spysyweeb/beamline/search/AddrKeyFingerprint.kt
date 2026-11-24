package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.search.address.AddressNormalizer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The street-key fingerprint of docs/architecture.md 2.11 (decision 6.11):
 * `sha256_hex(for each spelling s of fingerprint_spellings.json, in order: utf8(s + "\t" + street_key(s) + "\n"))`.
 * The pack builders write it to search meta `addr_key_fingerprint` and addresses meta
 * `key_fingerprint` with addrlib's street_key (packbuilder/addresses/key_fingerprint.py); this
 * port computes it with its own [AddressNormalizer] over the copy of the list in the rule files,
 * so a search part keyed by another normaliser is caught at open ([SearchHub]).
 */
object AddrKeyFingerprint {
    /** The hashed lines, "spelling<TAB>key<LF>" each. */
    fun lines(spellings: List<String>, streetKey: (String) -> String): List<String> = spellings.map { "$it\t${streetKey(it)}\n" }

    fun compute(spellings: List<String>, streetKey: (String) -> String): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (l in lines(spellings, streetKey)) md.update(l.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** The "spellings" list of a fingerprint_spellings.json text. */
    fun spellings(json: String): List<String> {
        val arr = Json.parseToJsonElement(json).jsonObject["spellings"] as? JsonArray
            ?: throw IllegalArgumentException("fingerprint_spellings.json has no spellings list")
        return arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("a spelling is not a string") }
    }

    /** The bundled list (rules/fingerprint_spellings.json). */
    fun bundledSpellings(): List<String> = spellings(RuleFiles.text(RuleFiles.FINGERPRINT_SPELLINGS))

    /** This app's fingerprint: the bundled list through this port's street key. Computed once. */
    fun app(): String = appFingerprint

    private val appFingerprint: String by lazy {
        val n = AddressNormalizer()
        compute(bundledSpellings()) { n.streetKey(it) }
    }
}
