package io.github.spysyweeb.beamline.packs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class PackScannerTest {

    @TempDir
    lateinit var root: File

    private fun scan() = PackScanner(root, TestPacks.READERS).scan()

    @Test
    fun `missing or empty packs folder means no packs`() {
        assertTrue(PackScanner(File(root, "does-not-exist"), TestPacks.READERS).scan().isEmpty)
        assertTrue(scan().isEmpty)
    }

    @Test
    fun `finds valid packs and reports the others`() {
        TestPacks.write(root, "us-pa")
        TestPacks.write(root, "us-oh")
        File(root, "not-a-pack").mkdirs()
        TestPacks.write(root, "broken-json", edit = { "{ nope" })
        TestPacks.write(root, "no-map").also { File(it, "map.pmtiles").delete() }
        File(root, "loose-file.txt").writeText("ignored: not a folder")
        File(root, ".staging-us-nj-1").mkdirs() // install in progress: ignored, not a problem

        val r = scan()

        assertEquals(listOf("us-oh", "us-pa"), r.packs.map { it.id })
        val problems = r.problems.associate { it.folder to it.reason }
        assertEquals(setOf("not-a-pack", "broken-json", "no-map"), problems.keys)
        assertEquals("no manifest.json", problems["not-a-pack"])
        assertEquals("map part map.pmtiles: missing", problems["no-map"])
        // Copied without the installer: no install record, so shown as not verified.
        assertNull(r.packs.first().install)
        assertTrue(r.packs.first().bytesOnDisk > 0)
    }

    @Test
    fun `versions the app cannot read are refused with a reason`() {
        TestPacks.write(root, "newer-manifest", edit = { it.replace("\"manifest_version\":3", "\"manifest_version\":4") })
        TestPacks.write(root, "old-manifest", edit = { it.replace("\"manifest_version\":3", "\"manifest_version\":2") })
        // A real version-1 manifest (the skeleton's): no "parts", a "map" and a "style" at the top.
        TestPacks.write(root, "skeleton-v1", edit = { """{"manifest_version":1,"pack_id":"us-pa","map":{"file":"map.pmtiles"},"style":{}}""" })
        TestPacks.write(root, "mbtiles", edit = { it.replace("\"format\":\"pmtiles\"", "\"format\":\"mbtiles\"") })
        TestPacks.write(root, "schema-5", edit = { it.replace("\"schema_version\":\"4.15.2\"", "\"schema_version\":\"5.0.0\"") })
        TestPacks.write(root, "other-schema", edit = { it.replace("\"schema\":\"protomaps-basemap\"", "\"schema\":\"shortbread\"") })
        // Format 3: the app routes with the pack, so a routing format it cannot read refuses the pack;
        // a search format it cannot read does not (the search part alone is unusable).
        TestPacks.write(root, "routing-v9", edit = { it.replace("\"format_version\":1,\"valhalla_version\"", "\"format_version\":9,\"valhalla_version\"") })
        TestPacks.write(root, "search-v9", edit = { it.replace("\"format_version\":1,\"addr_key_table_version\"", "\"format_version\":9,\"addr_key_table_version\"") })
        val r = scan()
        assertEquals(listOf("search-v9"), r.packs.map { it.dir.name })
        val reasons = r.problems.associate { it.folder to it.reason }
        assertTrue(reasons["newer-manifest"]!!.contains("newer than this app"), reasons["newer-manifest"])
        assertTrue(reasons["old-manifest"]!!.contains("old pack format"), reasons["old-manifest"])
        assertEquals("manifest_version 1 is an old pack format; rebuild the pack", reasons["skeleton-v1"])
        assertEquals("manifest_version 2 is an old pack format; rebuild the pack", reasons["old-manifest"])
        assertEquals("routing part is valhalla-tar v9; this app reads valhalla-tar v1", reasons["routing-v9"])
        assertTrue(reasons["mbtiles"]!!.contains("this app reads pmtiles v3"), reasons["mbtiles"])
        assertTrue(reasons["schema-5"]!!.contains("draws protomaps-basemap 4.x"), reasons["schema-5"])
        assertTrue(reasons["other-schema"]!!.contains("shortbread"), reasons["other-schema"])
    }

    @Test
    fun `part files must be plain names in the pack folder`() {
        TestPacks.write(root, "escape", edit = { it.replace("\"file\":\"map.pmtiles\"", "\"file\":\"../map.pmtiles\"") })
        TestPacks.write(root, "subfolder", edit = { it.replace("\"file\":\"map.pmtiles\"", "\"file\":\"tiles/map.pmtiles\"") })
        TestPacks.write(root, "same-file", edit = { it.replace("\"file\":\"search.sqlite\"", "\"file\":\"map.pmtiles\"") })
        TestPacks.write(root, "Bad_ID", id = "Bad ID")
        val r = scan()
        assertTrue(r.isEmpty)
        val reasons = r.problems.associate { it.folder to it.reason }
        assertTrue(reasons["escape"]!!.contains("plain file name"), reasons["escape"])
        assertTrue(reasons["subfolder"]!!.contains("plain file name"), reasons["subfolder"])
        assertTrue(reasons["same-file"]!!.contains("same file"), reasons["same-file"])
        assertTrue(reasons["Bad_ID"]!!.contains("pack_id"), reasons["Bad_ID"])
        assertFalse(PackScanner.isPlainFileName("manifest.json"))
        assertFalse(PackScanner.isPlainFileName(".hidden"))
        assertTrue(PackScanner.isPlainFileName("routing-pa-261114.tar"))
    }

    @Test
    fun `duplicate pack ids keep the first folder`() {
        TestPacks.write(root, "a-pa", id = "us-pa")
        TestPacks.write(root, "b-pa", id = "us-pa")
        val r = scan()
        assertEquals(listOf("a-pa"), r.packs.map { it.dir.name })
        assertEquals("duplicate pack_id us-pa", r.problems.single().reason)
    }

    /** Skeleton review finding, kept: these bounds crashed the app on every start. */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "[-80.3,95,-79.8,96]", // latitude beyond 90
            "[-200,-100,200,100]", // both out of range
            "[-80.3,42.25,-79.8,41.95]", // south above north
            "[-79.8,41.95,-80.3,42.25]", // west east of east
            "[0,0,0,0]", // empty box: blank grey map
            "[-80.3,41.95,-79.8]", // three numbers
        ],
    )
    fun `impossible bounds are rejected with a reason`(bounds: String) {
        TestPacks.write(root, "bad", edit = { it.replace("[-80.52,39.66,-74.69,42.52]", bounds) })
        val r = scan()
        assertTrue(r.isEmpty, "bounds $bounds accepted")
        assertTrue(r.problems.single().reason.startsWith("bounds"), r.problems.single().reason)
    }

    @Test
    fun `ordinary bounds pass`() {
        assertNull(PackScanner.boundsProblem(listOf(-80.53, 39.71, -74.68, 42.3)))
        assertNull(PackScanner.boundsProblem(listOf(-180.0, 51.0, 180.0, 72.0)))
        assertNotNull(PackScanner.boundsProblem(listOf(Double.NaN, 39.71, -74.68, 42.3)))
    }

    /** The cheap check: size against the manifest and a header check per part format. */
    @Test
    fun `broken part files are reported`() {
        val good = TestPacks.pmtiles()
        val maps = mapOf(
            "zero-length" to ByteArray(0),
            "garbage" to ByteArray(4096) { (it * 31 + 7).toByte() },
            "header-only" to good.copyOf(PartHeaders.PMTILES_HEADER_BYTES),
            "version-2" to TestPacks.pmtiles(version = 2),
            "tiles-past-end" to TestPacks.pmtiles(tileLength = 5000),
            "no-tiles" to TestPacks.pmtiles(rootLength = 0),
        )
        for ((name, bytes) in maps) {
            // The manifest has the broken file's size and checksum, so only the header check can catch it.
            val files = TestPacks.partFiles(bytes)
            File(root, name).mkdirs()
            TestPacks.sourceFolder(File(root, name), files)
        }
        // A copy cut short: the size differs from the manifest.
        TestPacks.write(root, "short-copy")
        File(root, "short-copy/map.pmtiles").writeBytes(good.copyOf(100))
        // The routing tar and the search file get their own header checks.
        TestPacks.write(root, "routing-not-tar").also { File(it, "routing.tar").writeBytes(ByteArray(TestPacks.routingTar().size)) }
        TestPacks.write(root, "search-not-sqlite").also { File(it, "search.sqlite").writeBytes(ByteArray(4096) { 1 }) }

        val r = scan()
        assertTrue(r.isEmpty, "accepted: ${r.packs.map { it.dir.name }}")
        val reasons = r.problems.associate { it.folder to it.reason }
        assertEquals(maps.keys + setOf("short-copy", "routing-not-tar", "search-not-sqlite"), reasons.keys)
        maps.keys.forEach { assertTrue(reasons[it]!!.startsWith("map part map.pmtiles: "), reasons[it]) }
        assertTrue(reasons["short-copy"]!!.contains("copy not finished"), reasons["short-copy"])
        assertTrue(reasons["garbage"]!!.contains("not a PMTiles file"), reasons["garbage"])
        assertTrue(reasons["tiles-past-end"]!!.contains("cut short"), reasons["tiles-past-end"])
        assertEquals("routing part routing.tar: not a tar file", reasons["routing-not-tar"])
        assertEquals("search part search.sqlite: not a SQLite file", reasons["search-not-sqlite"])
    }
}
