package io.github.spysyweeb.beamline.feature.about

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class NoticesParseTest {

    private fun doc(entries: String, format: String = "\"beamline-notices/1\"", full: String = "\"notices/NOTICES.txt\"") =
        """{"format": $format, "full_text": $full, "entries": [$entries]}"""

    private val okhttp =
        """{"name": "OkHttp", "version": "5.3.2", "licence": "Apache-2.0", "copyright": "Copyright 2019 Square, Inc.",
           "url": "https://square.github.io/okhttp/", "text": "notices/licences/Apache-2.0.txt", "covers": ["com.squareup.okhttp3:okhttp"]}"""
    private val osm =
        """{"name": "OpenStreetMap data", "version": null, "licence": "ODbL-1.0", "copyright": "© OpenStreetMap contributors",
           "url": null, "text": "notices/licences/ODbL-1.0.txt"}"""

    @Test
    fun readsEntriesInOrderWithOptionalFields() {
        val n = Notices.parse(doc("$okhttp, $osm"))
        assertEquals("notices/NOTICES.txt", n.fullTextAsset)
        assertEquals(2, n.entries.size)
        val a = n.entries[0]
        assertEquals(
            NoticeEntry("OkHttp", "5.3.2", "Apache-2.0", "Copyright 2019 Square, Inc.", "https://square.github.io/okhttp/", "notices/licences/Apache-2.0.txt"),
            a,
        )
        val b = n.entries[1]
        assertEquals("OpenStreetMap data", b.name)
        assertNull(b.version)
        assertNull(b.url)
        assertEquals("ODbL-1.0", b.licence)
    }

    @Test
    fun missingOptionalKeysAndBlankValuesAreNull() {
        val e = """{"name": "Noto Sans", "licence": "OFL-1.1", "version": "", "text": "notices/licences/OFL-1.1.txt"}"""
        val n = Notices.parse(doc(e))
        assertNull(n.entries[0].version)
        assertNull(n.entries[0].copyright)
        assertNull(n.entries[0].url)
    }

    @Test
    fun refusesWhatIsNotTheFormat() {
        assertThrows<IllegalArgumentException> { Notices.parse("not json") }
        assertThrows<IllegalArgumentException> { Notices.parse("[]") }
        assertThrows<IllegalArgumentException> { Notices.parse(doc(okhttp, format = "\"beamline-notices/2\"")) }
        assertThrows<IllegalArgumentException> { Notices.parse(doc(okhttp, format = "1")) }
        assertThrows<IllegalArgumentException> { Notices.parse("""{"format": "beamline-notices/1", "full_text": "notices/NOTICES.txt"}""") }
        assertThrows<IllegalArgumentException> { Notices.parse(doc("")) }
        assertThrows<IllegalArgumentException> { Notices.parse(doc(okhttp, full = "null")) }
    }

    @Test
    fun refusesEntriesWithoutNameLicenceOrText() {
        assertThrows<IllegalArgumentException> { Notices.parse(doc("""{"licence": "MIT", "text": "notices/licences/MIT.txt"}""")) }
        assertThrows<IllegalArgumentException> { Notices.parse(doc("""{"name": "x", "licence": " ", "text": "notices/licences/MIT.txt"}""")) }
        assertThrows<IllegalArgumentException> { Notices.parse(doc("""{"name": "x", "licence": "MIT"}""")) }
        assertThrows<IllegalArgumentException> { Notices.parse(doc("""{"name": "x", "licence": "MIT", "text": "notices/licences/MIT.txt", "url": 3}""")) }
        assertThrows<IllegalArgumentException> { Notices.parse(doc("\"just a string\"")) }
    }

    @Test
    fun refusesAssetPathsOutsideTheNoticesFolder() {
        for (path in listOf("licences/MIT.txt", "notices/../secrets.txt", "notices//MIT.txt", "/notices/MIT.txt", "notices/", "notices/./MIT.txt")) {
            val e = """{"name": "x", "licence": "MIT", "text": "$path"}"""
            assertThrows<IllegalArgumentException>(path) { Notices.parse(doc(e)) }
        }
        assertThrows<IllegalArgumentException> { Notices.parse(doc(okhttp, full = "\"NOTICES.txt\"")) }
    }

    @Test
    fun refusesTheSameComponentTwice() {
        assertThrows<IllegalArgumentException> { Notices.parse(doc("$okhttp, $okhttp")) }
    }

    @Test
    fun messagesNeverQuoteTheFile() {
        val e = assertThrows<IllegalArgumentException> {
            Notices.parse(doc("""{"name": "SECRET-NAME", "licence": "MIT", "text": "elsewhere/SECRET-PATH"}"""))
        }
        assertTrue("SECRET" !in e.message.orEmpty(), e.message)
    }

    /** The file notices.py wrote into the module's assets parses, and every text it names is there. */
    @Test
    fun theShippedNoticesFileParsesAndItsTextsExist() {
        val assets = File("src/main/assets")
        val file = File(assets, NoticesJson.ASSET)
        assertTrue(file.isFile, "run scripts/notices.py: ${file.path} is missing")
        val n = Notices.parse(file.readText())
        assertTrue(File(assets, n.fullTextAsset).isFile)
        for (e in n.entries) assertTrue(File(assets, e.textAsset).isFile, e.textAsset)
        assertTrue(n.entries.any { it.name.startsWith("OpenStreetMap") && it.licence == "ODbL-1.0" })
    }
}
