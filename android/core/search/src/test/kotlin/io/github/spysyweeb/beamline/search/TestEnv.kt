package io.github.spysyweeb.beamline.search

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Paths handed to the tests by build.gradle.kts (from android/local.properties). */
object TestEnv {
    val repoRoot: File get() = File(System.getProperty("beamline.repoRoot") ?: "../../..")
    val searchDb: String get() = System.getProperty("beamline.searchDb") ?: ""
    val addressesDb: String get() = System.getProperty("beamline.addressesDb") ?: ""
    val conformanceDir: File get() = File(System.getProperty("beamline.conformanceDir") ?: File(repoRoot, "data/search-conformance").path)
    val outDir: File get() = File(System.getProperty("beamline.outDir") ?: File(conformanceDir, "kotlin").path).also { it.mkdirs() }

    /**
     * Strict mode (BEAMLINE_TESTS_STRICT=1 or -Pbeamline.strict=true): a missing input fails the
     * test instead of skipping it, so a run without the packs or the reference cannot pass green.
     */
    val strict: Boolean get() = System.getProperty("beamline.strict") == "true"

    /** Skips the test when [ok] is false, or fails it in strict mode. */
    fun need(ok: Boolean, why: String) {
        if (!ok && strict) fail<Unit>("strict mode: $why")
        assumeTrue(ok, why)
    }

    fun requireSearchDb(): String {
        need(searchDb.isNotEmpty() && File(searchDb).isFile, "beamline.searchDb not set in android/local.properties")
        return searchDb
    }

    fun requireAddressesDb(): String {
        need(addressesDb.isNotEmpty() && File(addressesDb).isFile, "beamline.addressesDb not set in android/local.properties")
        return addressesDb
    }

    /** Named bias points of packbuilder/search/tests/points.json. */
    val points: Map<String, LatLon> by lazy {
        val f = File(repoRoot, "packbuilder/search/tests/points.json")
        val o = kotlinx.serialization.json.Json.parseToJsonElement(f.readText()).let { (it as kotlinx.serialization.json.JsonObject)["points"] as kotlinx.serialization.json.JsonObject }
        o.mapValues { (_, v) ->
            val p = v as kotlinx.serialization.json.JsonObject
            LatLon((p["lat"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble(), (p["lon"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble())
        }
    }

    private fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }

    /** SHA-256 of a text file with CRLF read as LF (as make_reference.py's fingerprint). */
    fun lfSha256(b: ByteArray): String {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            if (b[i] == '\r'.code.toByte() && i + 1 < b.size && b[i + 1] == '\n'.code.toByte()) i++
            out.write(b[i].toInt())
            i++
        }
        return hex(MessageDigest.getInstance("SHA-256").digest(out.toByteArray()))
    }

    private val fileShaCache = HashMap<String, String>()

    /** SHA-256 of a (large) file's bytes, once per test JVM. */
    @Synchronized
    fun fileSha256(path: String): String = fileShaCache.getOrPut(File(path).absolutePath) {
        val md = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().buffered(1 shl 20).use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        hex(md.digest())
    }

    /**
     * Why a reference document of make_reference.py no longer describes what is being tested:
     * a reference source (query.py, addrlib, the rule files, the checker, the query list) changed
     * since it was made, or the configured pack files are not the ones it was made from.
     * Empty when it is current.
     */
    fun staleReference(ref: JsonObject): List<String> {
        val fp = ref["fingerprint"]?.jsonObject
            ?: return listOf("the reference has no fingerprint (made by an older make_reference.py): run it again")
        val out = ArrayList<String>()
        for ((path, sha) in fp["code"]!!.jsonObject) {
            val f = File(repoRoot, path)
            val now = if (f.isFile) lfSha256(f.readBytes()) else "missing"
            if (now != sha.jsonPrimitive.content) out.add("$path changed since the reference was made")
        }
        for ((role, info) in fp["inputs"]!!.jsonObject) {
            val path = when (role) {
                "search_db" -> searchDb
                "addresses_db" -> addressesDb
                else -> continue   // e2e_cases: read by make_reference.py only
            }
            val want = info.jsonObject["sha256"]!!.jsonPrimitive.content
            if (path.isEmpty() || !File(path).isFile || fileSha256(path) != want) {
                out.add("$role ($path) is not the file the reference was made from (${info.jsonObject["name"]?.jsonPrimitive?.content})")
            }
        }
        return out
    }
}
