package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RulesAndSpaceTest {

    @Test
    fun theBundledRulesAreTheFileAndItsCopyEqualsTheSource() {
        assertEquals(DownloadRules(), DownloadRules.bundled())
        val root = File(System.getProperty("beamline.repoRoot") ?: error("beamline.repoRoot not set"))
        val source = File(root, "packbuilder/catalog/download_rules.json").readBytes()
        val copy = javaClass.getResourceAsStream("download_rules.json")!!.readBytes()
        assertArrayEquals(source, copy, "run python android/routing/scripts/sync_data.py --only download_rules.json")
        assertEquals(DownloadRules(), DownloadRules.parse(source.decodeToString()))
    }

    /**
     * R8 repackages classes in the app's release build, so the bundled rules must be found by an
     * absolute path that does not depend on the loading class's package (found by integration: the
     * release APK stopped at start). The class loader's root lookup is what a moved class still has.
     */
    @Test
    fun theBundledRulesAreFoundWithoutTheClassPackage() {
        assertTrue(RulesFile.RESOURCE.startsWith("/"), "a path relative to the class's package breaks under R8")
        val fromRoot = ClassLoader.getSystemClassLoader().getResource(RulesFile.RESOURCE.removePrefix("/"))
        assertTrue(fromRoot != null, "the rules are not at ${RulesFile.RESOURCE}")
    }

    @Test
    fun parseReadsEveryKeyAndRefusesOtherFormats() {
        val r = DownloadRules.parse("""{"format":"beamline-download-rules/1","retry_backoff_s":[1,2],"buffer_bytes":65536,"note":"x","later":true}""")
        assertEquals(listOf(1, 2), r.retryBackoffS)
        assertEquals(65536, r.bufferBytes)
        assertEquals(DownloadRules().maxRedirects, r.maxRedirects)
        assertThrows(IllegalArgumentException::class.java) { DownloadRules.parse("""{"format":"beamline-download-rules/2"}""") }
        assertThrows(IllegalArgumentException::class.java) { DownloadRules.parse("""{"format":"beamline-download-rules/1","max_redirects":"5"}""") }
        assertThrows(IllegalArgumentException::class.java) { DownloadRules.parse("""{"format":"beamline-download-rules/1","retry_backoff_s":[]}""") }
        assertThrows(IllegalArgumentException::class.java) { DownloadRules.parse("[]") }
    }

    /** PA's three parts with no manifest bytes (10.1 "Space formulas", 6.3's worked example). */
    private val pa = CatalogPack(
        id = "us-pa", state = "PA", name = "Pennsylvania", packVersion = 202611152241, manifestVersion = 3, osmSnapshot = "2026-11-14",
        credit = "© OpenStreetMap contributors", bounds = Bounds(-80.51985, 39.7198, -74.68956, 42.51607),
        manifest = TransferFile("../us-pa-202611152241/manifest.json", "identity", 0, "0".repeat(64)),
        parts = listOf(
            CatalogPart("search", "search.sqlite", false, 154_705_920, "a".repeat(64), TransferFile("../x/search.sqlite.gz", "gzip", 63_346_936, "b".repeat(64)), null),
            CatalogPart("routing", "routing.tar", false, 634_429_440, "c".repeat(64), TransferFile("../x/routing.tar.gz", "gzip", 240_430_789, "d".repeat(64)), null),
            CatalogPart("map", "map.pmtiles", false, 244_703_514, "e".repeat(64), TransferFile("../x/map.pmtiles", "identity", 244_703_514, "e".repeat(64)), null),
        ),
    )

    @Test
    fun spaceFormulas() {
        val m = 200_000_000L
        assertEquals(1_868_268_314, Space.estimate(pa, JobKind.INSTALL, true, moves = true, routingSetBytes = 0, onDiskBytes = 0, marginBytes = m))
        assertEquals(2_902_107_188, Space.estimate(pa, JobKind.INSTALL, true, moves = false, routingSetBytes = 0, onDiskBytes = 0, marginBytes = m))
        assertEquals(2_502_697_754, Space.estimate(pa, JobKind.INSTALL, true, moves = true, routingSetBytes = 634_429_440, onDiskBytes = 0, marginBytes = m))
        // at CHECKING with the installer's own figure: moves count only what it needs beyond the parts
        val info = ArchiveInfo("us-pa", "Pennsylvania", "PA", "2026-11-14", neededBytes = 2_000_000_000, addressesBytes = null,
            replacesInstalled = false, freeBytes = 0, problems = emptyList())
        val r = 1_033_838_874L
        assertEquals(r + maxOf(240_430_789 + m, 2_000_000_000 - r), Space.exact(pa, JobKind.INSTALL, true, true, 0, m, info))
        assertEquals(r + 2_000_000_000, Space.exact(pa, JobKind.INSTALL, true, false, 0, m, info))
        // what is already verified in the folder is not needed again
        assertTrue(Space.exact(pa, JobKind.INSTALL, true, true, 500_000_000, m, info) < Space.exact(pa, JobKind.INSTALL, true, true, 0, m, info))
    }
}
