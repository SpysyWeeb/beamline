package io.github.spysyweeb.beamline.packs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * The real format-3 archives of packbuilder/pack/assemble.py (data/r3-packs, system property
 * beamline.r3Packs): the first tar entry is manifest.json, this app reads it, and its folder copy is
 * accepted by the scanner. Skipped when the archives are not there. Reads only the manifest and
 * the file headers (cheap); the install tests of :core:installed read the whole archives.
 */
class RealPacksManifestTest {

    private val dir = File(System.getProperty("beamline.r3Packs") ?: "data/r3-packs")

    private fun archive(id: String): File? = dir.listFiles { f -> f.name.startsWith("$id-") && f.name.endsWith(".tar") }?.firstOrNull()

    @ParameterizedTest
    @ValueSource(strings = ["us-pa", "us-oh"])
    fun `the archive's first entry is a format-3 manifest this app reads`(id: String) {
        val tar = archive(id)
        assumeTrue(tar != null, "no $id archive in $dir")
        val text = tar!!.inputStream().buffered().use { input ->
            val r = TarReader(input)
            val first = r.next()!!
            assertEquals(PackManifest.FILE_NAME, first.name)
            r.entryStream.readBytes().toString(Charsets.UTF_8)
        }
        val m = PackManifest.parse(text)
        assertEquals(3, m.manifestVersion)
        assertEquals(id, m.packId)
        assertEquals("2026-11-14", m.osmSnapshot)
        assertTrue(m.packVersion!! >= 202611160000L, "pack_version ${m.packVersion}")
        assertNull(TestPacks.READERS.problem(m))
        assertNull(TestPacks.READERS.partProblem(m, PackManifest.SEARCH))
        assertNull(TestPacks.READERS.partProblem(m, PackManifest.ADDRESSES))
        assertEquals(setOf("map", "routing", "routing_index", "search", "addresses"), m.allParts.keys)
        assertEquals(14, m.map.maxZoom)
        assertEquals("261114-pa-oh-v3.9.1-b53087", m.routing!!.graphBuild)
        assertEquals(m.search!!.addrKeyFingerprint, m.addresses!!.keyFingerprint)
        assertEquals("slim", m.addresses!!.variant)
        val osm = "© OpenStreetMap contributors"
        assertEquals(osm, m.creditFor(skipped = PackManifest.OPTIONAL_PARTS))
        val full = m.creditFor()
        assertTrue(full.startsWith("$osm; U.S. Census Bureau TIGER/Line"), full)
        // The scanner accepts the pack folder assemble.py wrote next to the archive (sizes and headers).
        val folder = File(dir, id)
        if (folder.isDirectory) {
            val read = PackScanner(dir, TestPacks.READERS).read(folder)
            assertTrue(read is PackScanner.Read.Ok, read.toString())
        }
    }
}
