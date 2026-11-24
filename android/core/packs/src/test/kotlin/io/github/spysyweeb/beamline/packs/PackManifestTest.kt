package io.github.spysyweeb.beamline.packs

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PackManifestTest {

    @Test
    fun `reads the documented format-3 manifest with every part`() {
        val m = PackManifest.parse(TestPacks.manifestText())
        assertEquals(3, m.manifestVersion)
        assertEquals(202611160400L, m.packVersion)
        assertEquals("us-pa", m.packId)
        assertEquals("2026-11-14", m.osmSnapshot)
        assertEquals(listOf(-80.52, 39.66, -74.69, 42.52), m.bounds)
        assertEquals("map.pmtiles", m.map.file)
        assertEquals("4.15.2", m.map.schemaVersion)
        assertEquals("mvt", m.map.tileType)
        assertEquals(14, m.map.schemaExtensions!!["lanes_minzoom"]!!.jsonPrimitive.int)
        assertEquals("PA", m.map.clip!!["state"]!!.jsonPrimitive.content)
        assertEquals("valhalla-tar", m.routing?.format)
        assertEquals("261114-pa-oh-v3.9.1-b53087", m.routing?.graphBuild)
        assertEquals("routing.json", m.routingIndex?.file)
        assertEquals("beamline-routing-pack", m.routingIndex?.format)
        assertEquals("search.sqlite", m.search?.file)
        assertEquals(2, m.search?.addrKeyTableVersion)
        assertEquals("97c8d281115ec4d4b186051b18efe3f15bb5b1c74d60902854665f83d228d73a", m.search?.addrKeyFingerprint)
        assertEquals("989553d1507d63aebc9c4ab0e52d22abd789f541130070693483a73ef7dfbbfb", m.search?.rankingSha256)
        assertEquals("addresses.sqlite", m.addresses?.file)
        assertEquals("slim", m.addresses?.variant)
        assertEquals(m.search?.addrKeyFingerprint, m.addresses?.keyFingerprint)
        assertEquals(2, m.addresses?.normalisationTableVersion)
        // A part this app does not know is still listed with its file, size and checksum.
        assertEquals(setOf("map", "routing", "routing_index", "search", "addresses", "future_part"), m.allParts.keys)
        assertEquals(7, m.allParts.getValue("future_part").formatVersion)
        assertEquals(6 * 1234L, m.totalBytes)
        assertEquals(5 * 1234L, m.bytesWithout(listOf("addresses")))
    }

    @Test
    fun `the credit joins the attributions of the parts installed`() {
        val m = PackManifest.parse(TestPacks.manifestText())
        assertEquals(
            "© OpenStreetMap contributors; U.S. Census Bureau TIGER/Line; USDOT National Address Database; City of Scranton GIS",
            m.creditFor(),
        )
        // Installed without house numbers: no address source is credited.
        assertEquals("© OpenStreetMap contributors", m.creditFor(skipped = listOf("addresses")))
    }

    @Test
    fun `version 2 packs are refused with rebuild the pack, newer ones with update the app`() {
        val v2 = assertThrows<IllegalArgumentException> { PackManifest.parse(TestPacks.manifestV2Text()) }
        assertEquals("manifest_version 2 is an old pack format; rebuild the pack", v2.message)
        val v4 = TestPacks.manifestText().replace("\"manifest_version\": 3", "\"manifest_version\": 4")
        assertTrue(assertThrows<IllegalArgumentException> { PackManifest.parse(v4) }.message!!.endsWith("update the app"))
        assertEquals(2, PackManifest.versionOf(TestPacks.manifestV2Text()))
    }

    @Test
    fun `addresses and pack_version are optional in the parser`() {
        val text = TestPacks.manifestText()
            .replace("\"pack_version\": 202611160400,", "")
            .replace("\"addresses\": {", "\"addresses_not\": {")
        val m = PackManifest.parse(text)
        assertNull(m.packVersion)
        assertNull(m.addresses)
        // The renamed part is a part this app does not know; it is still credited while installed.
        assertEquals("© OpenStreetMap contributors", m.creditFor(skipped = listOf("addresses_not")))
    }

    @Test
    fun `a pack without a map part or a part without a checksum is refused`() {
        val noMap = TestPacks.manifestText().replace("\"map\": {", "\"not_map\": {")
        assertTrue(assertThrows<Exception> { PackManifest.parse(noMap) }.message!!.contains("no \"map\" part"))
        val noSha = TestPacks.manifestFor(TestPacks.partFiles()).replace(Regex(""","sha256":"[0-9a-f]{64}"""), "")
        assertThrows<Exception> { PackManifest.parse(noSha) }
        assertThrows<Exception> { PackManifest.parse("""{"manifest_version":3,"pack_id":"x"}""") }
    }

    @Test
    fun `the readers require routing, its index and search, and judge search and addresses alone`() {
        val r = TestPacks.READERS
        val ok = PackManifest.parse(TestPacks.manifestText())
        assertNull(r.problem(ok))
        val noIndex = PackManifest.parse(TestPacks.manifestText().replace("\"routing_index\": {", "\"x_index\": {"))
        assertEquals("the pack has no \"routing_index\" part", r.problem(noIndex))
        val noSearch = PackManifest.parse(TestPacks.manifestText().replace("\"search\": {", "\"x_search\": {"))
        assertEquals("the pack has no \"search\" part", r.problem(noSearch))
        val search2 = PackManifest.parse(
            TestPacks.manifestText().replace("\"format\": \"beamline-search-sqlite\",\n      \"format_version\": 1", "\"format\": \"beamline-search-sqlite\",\n      \"format_version\": 2"),
        )
        assertNull(r.problem(search2), "a search part the app cannot read leaves map and routing usable")
        assertEquals("beamline-search-sqlite v2; this app reads beamline-search-sqlite v1", r.partProblem(search2, "search"))
        assertNull(r.partProblem(ok, "addresses"))
        assertNull(r.partProblem(ok, "future_part"))
    }
}
