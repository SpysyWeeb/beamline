package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.download.net.CatalogAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** docs/architecture-downloads.md 10.1 "Catalog parsing" (rules of 2.3). */
class CatalogParsingTest {
    private val address = CatalogAddress.parse("http://10.0.2.2:8765/catalog/catalog.json", allowCleartext = true)!!
    private val rules = DownloadRules()

    /** G's site catalog of the PA z14 archive (data/r3-g/site): PA's real sizes and hashes, manifest version 2. */
    private val fixture: String = javaClass.getResourceAsStream("catalog-v1.json")!!.readBytes().decodeToString()

    private fun parse(text: String, min: Int = 2, max: Int = 3) =
        CatalogFormat.parse(text.toByteArray(), address, min, max, rules.catalogMaxBytes, rules.maxFileBytes)

    private fun ok(text: String, min: Int = 2, max: Int = 3) = (parse(text, min, max) as CatalogParse.Ok).catalog

    private fun refused(text: String) = (parse(text) as CatalogParse.Refused).reason

    private fun tree(): JsonObject = Json.parseToJsonElement(fixture).jsonObject

    /** The fixture with one change applied to the first pack entry. */
    private fun withPack(change: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = tree().toMutableMap()
        val pack = root["packs"]!!.jsonArray[0].jsonObject.toMutableMap()
        change(pack)
        root["packs"] = JsonArray(listOf(JsonObject(pack)))
        return JsonObject(root).toString()
    }

    private fun twoPacks(second: (MutableMap<String, JsonElement>) -> Unit): String {
        val root = tree().toMutableMap()
        val first = root["packs"]!!.jsonArray[0].jsonObject
        val other = first.toMutableMap().also(second)
        root["packs"] = JsonArray(listOf(first, JsonObject(other)))
        return JsonObject(root).toString()
    }

    private fun ohio(p: MutableMap<String, JsonElement>) {
        p["id"] = JsonPrimitive("us-oh")
        p["state"] = JsonPrimitive("OH")
        p["name"] = JsonPrimitive("Ohio")
    }

    @Test
    fun theFixtureGivesTheFieldsOfSection23() {
        val c = ok(fixture)
        assertEquals(20261116085521, c.serial)
        assertEquals("ODbL-1.0", c.licence.id)
        assertEquals("Map data © OpenStreetMap contributors, available under the Open Database License.", c.licence.notice)
        val pa = c.packs.single()
        assertEquals("us-pa", pa.id)
        assertEquals("PA", pa.state)
        assertEquals("Pennsylvania", pa.name)
        assertEquals(202611160419, pa.packVersion)
        assertEquals(2, pa.manifestVersion)
        assertEquals("2026-11-14", pa.osmSnapshot)
        assertEquals(-80.51985, pa.bounds.west)
        assertEquals(42.51607, pa.bounds.north)
        assertEquals(listOf("search", "routing", "map"), pa.parts.map { it.name })
        val routing = pa.parts.first { it.name == "routing" }
        assertEquals(634_429_440, routing.bytes)
        assertEquals("3347f2e97e10d7bbd1ed4be4eb4437b4b09e62baf8a8f3a4c4eb3d2b42dbc342", routing.sha256)
        assertEquals("gzip", routing.transfer.encoding)
        assertEquals("../us-pa-202611160419/routing.tar.gz", routing.transfer.path)
        assertEquals(244_703_514, pa.parts.first { it.name == "map" }.transfer.bytes)
        // Appendix A: 548.5 MB to download (this build's gzip files carry no name), 1,033.8 MB unpacked
        assertEquals(548_482_461, pa.downloadBytes(includeOptional = true))
        assertEquals(1_033_840_111, pa.unpackedBytes(includeOptional = true))
        assertTrue(c.unusable.isEmpty())
    }

    @Test
    fun unknownKeysAreIgnoredAtEveryLevel() {
        val root = tree().toMutableMap()
        root["mirrors"] = JsonArray(emptyList())
        val pack = root["packs"]!!.jsonArray[0].jsonObject.toMutableMap()
        pack["future"] = JsonPrimitive(1)
        val parts = pack["parts"]!!.jsonArray.map { JsonObject(it.jsonObject + ("hint" to JsonPrimitive("x"))) }
        pack["parts"] = JsonArray(parts)
        root["packs"] = JsonArray(listOf(JsonObject(pack)))
        assertEquals(1, ok(JsonObject(root).toString()).packs.size)
    }

    @Test
    fun refusalsOfTheWholeList() {
        assertEquals(CatalogRefusal.NEWER_FORMAT, refused(fixture.replace("beamline-catalog/1", "beamline-catalog/2")))
        assertEquals(CatalogRefusal.NOT_A_CATALOG, refused(fixture.replace("beamline-catalog/1", "something-else/1")))
        assertEquals(CatalogRefusal.NOT_A_CATALOG, refused("[1, 2]"))
        assertEquals(CatalogRefusal.NOT_A_CATALOG, refused("not json"))
        val big = ByteArray(2 * 1024 * 1024 + 1) { ' '.code.toByte() }
        assertEquals(CatalogRefusal.TOO_LARGE, (CatalogFormat.parse(big, address, 2, 3, rules.catalogMaxBytes, rules.maxFileBytes) as CatalogParse.Refused).reason)
        val exactly = ByteArray(2 * 1024 * 1024) { ' '.code.toByte() }
        assertEquals(CatalogRefusal.NOT_A_CATALOG, (CatalogFormat.parse(exactly, address, 2, 3, rules.catalogMaxBytes, rules.maxFileBytes) as CatalogParse.Refused).reason)
    }

    @Test
    fun anyPathOutsideTheScopeRefusesEverything() {
        for (bad in listOf("../../x", "/abs", "http://h/x", "a/../../b", "%2e%2e/x", "a//b", "a?b")) {
            val text = withPack { p ->
                val m = p["manifest"]!!.jsonObject.toMutableMap()
                m["path"] = JsonPrimitive(bad)
                p["manifest"] = JsonObject(m)
            }
            assertEquals(CatalogRefusal.OUTSIDE_SCOPE, refused(text), bad)
        }
        // in a part's transfer too, even when the entry is otherwise unusable
        val text = withPack { p ->
            p["manifest_version"] = JsonPrimitive(9)
            val parts = p["parts"]!!.jsonArray.toMutableList()
            val t = parts[0].jsonObject["transfer"]!!.jsonObject.toMutableMap()
            t["path"] = JsonPrimitive("../../etc/x")
            parts[0] = JsonObject(parts[0].jsonObject + ("transfer" to JsonObject(t)))
            p["parts"] = JsonArray(parts)
        }
        assertEquals(CatalogRefusal.OUTSIDE_SCOPE, refused(text))
    }

    @Test
    fun versionsAndEncodingsMakeOneEntryUnusable() {
        val v4 = twoPacks { ohio(it); it["manifest_version"] = JsonPrimitive(4) }
        val c4 = ok(v4, 2, 3)
        assertEquals(Unusable.NEEDS_NEWER_APP, c4.unusable.single().reason)
        assertEquals("OH", c4.unusable.single().state)
        assertEquals("Ohio", c4.unusable.single().name)

        val zstd = twoPacks { p ->
            ohio(p)
            val parts = p["parts"]!!.jsonArray.toMutableList()
            val t = parts[0].jsonObject["transfer"]!!.jsonObject.toMutableMap()
            t["encoding"] = JsonPrimitive("zstd")
            parts[0] = JsonObject(parts[0].jsonObject + ("transfer" to JsonObject(t)))
            p["parts"] = JsonArray(parts)
        }
        val cz = ok(zstd)
        assertEquals(Unusable.NEEDS_NEWER_APP, cz.unusable.single().reason)
        assertEquals(listOf("PA"), cz.packs.map { it.state })

        // manifest version 2 with an app that reads only 3: not available yet, the others still usable
        val v2 = twoPacks { ohio(it); it["manifest_version"] = JsonPrimitive(3) }
        val c2 = ok(v2, 3, 3)
        assertEquals(Unusable.OLD_FORMAT, c2.unusable.single().reason)
        assertEquals("PA", c2.unusable.single().state)
        assertEquals(listOf("OH"), c2.packs.map { it.state })
    }

    @Test
    fun brokenEntries() {
        fun brokenBy(change: (MutableMap<String, JsonElement>) -> Unit) {
            val c = ok(twoPacks { ohio(it); change(it) })
            assertEquals(listOf("PA"), c.packs.map { it.state })
            assertEquals(Unusable.BROKEN, c.unusable.single().reason)
        }
        brokenBy { p ->
            val parts = p["parts"]!!.jsonArray.toMutableList()
            parts[0] = JsonObject(parts[0].jsonObject + ("sha256" to JsonPrimitive("ABC")))
            p["parts"] = JsonArray(parts)
        }
        brokenBy { p ->
            val parts = p["parts"]!!.jsonArray.toMutableList()
            parts[0] = JsonObject(parts[0].jsonObject + ("bytes" to JsonPrimitive(-1)))
            p["parts"] = JsonArray(parts)
        }
        brokenBy { p -> p["state"] = JsonPrimitive("PA"); p["id"] = JsonPrimitive("us-pa") } // duplicate state
        brokenBy { p -> p["state"] = JsonPrimitive("PA") } // id us-oh with state PA
        brokenBy { p -> p["name"] = JsonPrimitive("x".repeat(65)) }
        brokenBy { p -> p.remove("osm_snapshot") }
        brokenBy { p -> p["bounds"] = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))) }
        brokenBy { p ->
            val parts = p["parts"]!!.jsonArray.toMutableList()
            parts[0] = JsonObject(parts[0].jsonObject + ("file" to JsonPrimitive("job.json")))
            p["parts"] = JsonArray(parts)
        }
        brokenBy { p ->
            val parts = p["parts"]!!.jsonArray.toMutableList()
            parts[0] = JsonObject(parts[0].jsonObject + ("optional" to JsonPrimitive(true)))
            p["parts"] = JsonArray(parts)
        }
        // a 64-character name is fine
        val c = ok(twoPacks { ohio(it); it["name"] = JsonPrimitive("y".repeat(64)) })
        assertEquals(2, c.packs.size)
    }

    @Test
    fun aBadLicenceRefusesTheList() {
        val long = tree().toMutableMap().also { r ->
            r["licence"] = JsonObject(r["licence"]!!.jsonObject + ("url" to JsonPrimitive("https://example.org/" + "a".repeat(190))))
        }
        assertEquals(CatalogRefusal.NOT_A_CATALOG, refused(JsonObject(long).toString()))
        val http = tree().toMutableMap().also { r ->
            r["licence"] = JsonObject(r["licence"]!!.jsonObject + ("url" to JsonPrimitive("http://opendatacommons.org/licenses/odbl/1-0/")))
        }
        assertEquals(CatalogRefusal.NOT_A_CATALOG, refused(JsonObject(http).toString()))
        val exactly200 = tree().toMutableMap().also { r ->
            r["licence"] = JsonObject(r["licence"]!!.jsonObject + ("url" to JsonPrimitive("https://example.org/" + "a".repeat(180))))
        }
        assertTrue(parse(JsonObject(exactly200).toString()) is CatalogParse.Ok)
    }

    @Test
    fun aMissingCreditOnAPartIsNull() {
        val c = ok(fixture)
        assertNull(c.packs.single().parts.first().credit)
    }
}
