package io.github.spysyweeb.beamline.search

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import io.github.spysyweeb.beamline.search.address.AddressIndex
import io.github.spysyweeb.beamline.search.address.StreetTables
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RulesAndVersionsTest {
    private fun lfSha(b: ByteArray): String {
        val s = String(b, Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(s).joinToString("") { "%02x".format(it) }
    }

    /** The copies in the resources equal their sources in packbuilder/ (run copy_search_rules.py after a change). */
    @Test
    fun ruleCopiesMatchPackbuilder() {
        val manifest = Json.parseToJsonElement(RuleFiles.text("SOURCES.json")).jsonObject["files"]!!.jsonObject
        // search_app.json: the search screen's and the saved places' rules (Round SP0, docs/architecture-saved-places.md 7.1);
        // fingerprint_spellings.json: the street-key fingerprint's list (round 3, docs/architecture.md 2.11);
        // poi_icons.json: category id -> sprite icon (round 3, docs/architecture.md 5.1 S, decision 6.20)
        assertEquals(setOf(RuleFiles.ABBREVIATIONS, RuleFiles.RANKING, RuleFiles.STREET_NORM, RuleFiles.LOOKUP_CONFIG, "search_app.json",
            RuleFiles.FINGERPRINT_SPELLINGS, RuleFiles.POI_ICONS), manifest.keys)
        for ((name, info) in manifest) {
            val copy = RuleFiles.text(name).toByteArray(Charsets.UTF_8)
            val recorded = info.jsonObject["sha256_lf"]!!.jsonPrimitive.content
            assertEquals(recorded, lfSha(copy), "$name differs from SOURCES.json: the copy was edited")
            val src = File(TestEnv.repoRoot, info.jsonObject["source"]!!.jsonPrimitive.content)
            assertTrue(src.isFile, "$name: source $src missing (packbuilder/ is part of the repository)")
            assertEquals(lfSha(src.readBytes()), recorded, "$name: packbuilder changed, run android/scripts/copy_search_rules.py")
        }
    }

    @Test
    fun jsonKeyOrderIsKept() {
        // the port relies on file order (connectors, first spelling wins in the street tables)
        val t = SearchTables(RuleFiles.json(RuleFiles.ABBREVIATIONS))
        assertEquals(listOf("near", "in", "on"), t.connectors.keys.toList())
        val st = StreetTables.load()
        assertEquals("aly" to "alley", st.typeFirstSpelling.first())
        assertEquals("2", st.version)
    }

    /** A tiny address file; [version] null = no normalisation_table_version row. */
    private fun tinyAddressDb(dir: File, version: String?): File {
        val f = File(dir, "addr-v${version ?: "none"}.sqlite")
        val c = BundledSQLiteDriver().open(f.path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
        c.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        c.execSQL("INSERT INTO meta VALUES ('state', 'PA')")
        if (version != null) c.execSQL("INSERT INTO meta VALUES ('normalisation_table_version', '$version')")
        c.execSQL("CREATE TABLE places(place_key TEXT, name TEXT, kind TEXT, zip TEXT, county_fips TEXT, n INTEGER)")
        c.execSQL("CREATE TABLE localities(id INTEGER PRIMARY KEY, zip TEXT, city TEXT, city_key TEXT, city_src TEXT, municipality TEXT, muni_key TEXT, county_fips TEXT)")
        c.execSQL("CREATE TABLE streets(id INTEGER PRIMARY KEY, street TEXT, street_key TEXT, street_core TEXT, locality_id INTEGER, n_points INTEGER, n_ranges INTEGER, lat_e6 INTEGER, lon_e6 INTEGER)")
        c.execSQL("CREATE TABLE addresses(id INTEGER PRIMARY KEY, street_id INTEGER, hn TEXT, hn_int INTEGER, unit TEXT, lat_e6 INTEGER, lon_e6 INTEGER, source TEXT)")
        c.execSQL("CREATE VIEW address_rows AS SELECT a.id, a.hn, s.street, s.street_key, a.unit, NULL AS city, NULL AS municipality, NULL AS zip, NULL AS county_fips, a.lat_e6 / 1e6 AS lat, a.lon_e6 / 1e6 AS lon, a.source, 'point' AS accuracy FROM addresses a JOIN streets s ON s.id = a.street_id")
        c.close()
        return f
    }

    /** An address file keyed with another normaliser version is refused, by the lookup and by the search join. */
    @Test
    fun otherNormaliserVersionsAreRefused() {
        val dir = Files.createTempDirectory("Beamline version test #% ").toFile()
        try {
            val v1 = tinyAddressDb(dir, "1")
            val v2 = tinyAddressDb(dir, "2")
            val e = assertThrows<IllegalStateException> { AddressIndex(v1.path) }
            assertTrue("version 1" in e.message!!, e.message)
            AddressIndex(v2.path).use { assertEquals("2", it.tableVersion); assertEquals("pa", it.state) }
            val searchDb = TestEnv.requireSearchDb()
            val e2 = assertThrows<IllegalStateException> { SearchEngine(searchDb, v1.path) }
            assertTrue("table version 1" in e2.message!!, e2.message)
            SearchEngine(searchDb, v1.path, allowAddrVersionMismatch = true).use { assertTrue(it.addrWarning != null) }
            SearchEngine(searchDb, v2.path).use {
                assertTrue(it.addrOk)
                assertEquals("2", it.addrKeyTableVersion)
                assertEquals(StreetTables.load().version, it.addressesTableVersion)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A minimal search database: what SearchEngine reads at open time, keyed with [addrKeyVersion]. */
    private fun tinySearchDb(dir: File, schema: String, addrKeyVersion: String): File {
        val f = File(dir, "search-s$schema-k$addrKeyVersion.sqlite")
        val c = BundledSQLiteDriver().open(f.path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
        c.execSQL("CREATE TABLE category(id INTEGER PRIMARY KEY, code TEXT, label TEXT)")
        c.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        c.execSQL("INSERT INTO meta VALUES ('schema_version', '$schema'), " +
            "('addr_key', '{\"table_version\": \"$addrKeyVersion\"}')")
        c.execSQL("CREATE TABLE street(entry_id INTEGER PRIMARY KEY, addr_key TEXT)")
        c.close()
        return f
    }

    /**
     * Stricter than query.py (review of 2026-11-15): an address file without a version row, a
     * pair of packs keyed with a version this port does not implement, and a search database of
     * another schema are all refused. Needs no pack file: it runs (and passes) without the search
     * database configured; the same refusal with the real pack is [missingVersionIsRefusedWithThePack].
     */
    @Test
    fun missingOrForeignVersionsAreRefused() {
        val dir = Files.createTempDirectory("Beamline version test 2 ").toFile()
        try {
            val none = tinyAddressDb(dir, null)
            val s2 = tinySearchDb(dir, "1", "2")
            assertThrows<IllegalStateException> { AddressIndex(none.path) }
            val e1 = assertThrows<IllegalStateException> { SearchEngine(s2.path, none.path) }
            assertTrue("records no normalisation table version" in e1.message!!, e1.message)
            // both packs keyed with version 3: they agree with each other, but not with this port (2)
            val s3 = tinySearchDb(dir, "1", "3")
            val a3 = tinyAddressDb(dir, "3")
            val e2 = assertThrows<IllegalStateException> { SearchEngine(s3.path, a3.path) }
            assertTrue("this app implements version 2" in e2.message!!, e2.message)
            SearchEngine(s3.path, a3.path, allowAddrVersionMismatch = true).use { assertTrue(it.addrWarning != null) }
            // the same tiny database keyed with version 2 attaches
            SearchEngine(s2.path, tinyAddressDb(dir, "2").path).use { assertTrue(it.addrOk) }
            // another schema
            val e3 = assertThrows<IllegalStateException> { SearchEngine(tinySearchDb(dir, "2", "2").path) }
            assertTrue("schema version 2" in e3.message!!, e3.message)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * An address file without a version row is refused next to the real search pack too. The
     * database is looked up outside assertThrows, so without it the test is skipped (or fails in
     * strict mode) instead of failing on the skip (the Round 0 bug of the test above).
     */
    @Test
    fun missingVersionIsRefusedWithThePack() {
        val searchDb = TestEnv.requireSearchDb()
        val dir = Files.createTempDirectory("Beamline version test 3 ").toFile()
        try {
            val none = tinyAddressDb(dir, null)
            val e = assertThrows<IllegalStateException> { SearchEngine(searchDb, none.path) }
            assertTrue("records no normalisation table version" in e.message!!, e.message)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** The real pack files carry the version this port implements. */
    @Test
    fun packFilesCarryThisVersion() {
        val a = TestEnv.requireAddressesDb()
        AddressIndex(a).use { assertEquals(StreetTables.load().version, it.tableVersion) }
        SearchEngine(TestEnv.requireSearchDb(), a).use {
            assertEquals(SearchEngine.SCHEMA_VERSION, it.meta["schema_version"])
            assertEquals(StreetTables.load().version, it.addrKeyTableVersion)
            assertEquals(StreetTables.load().version, it.addressesTableVersion)
        }
    }
}
