package io.github.spysyweeb.beamline.search

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.execSQL
import io.github.spysyweeb.beamline.search.address.AddressIndex
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.math.roundToInt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Item 55 (docs/next-round.md): a typed house number with no town is looked up along the street the search shows, by
 * place, not by name. A small made-up place (made-up names, numbers and points; no real address): OSM's "Example Flinn
 * Road" runs east-west for 1.7 km; the census range that covers 120 calls it "Example Flynn Rd" and is filed under the
 * neighbouring town's ZIP. Around it: a crossing street and a parallel lane with ranges covering 120, a point 120 of the
 * crossing street, a same-named road 1 km off the line and another 45 km away (in the search pack too), all with ranges
 * covering 120; the road goes on east into the next township, where its census range covering 120 has OSM's spelling.
 *
 * The engine's own answer is made by hand ([street], [response]); [SearchService.withAddress] is the rule under test.
 */
class AlongStreetTest {
    private val dir: File = Files.createTempDirectory("Beamline along test ").toFile()

    @AfterEach
    fun cleanUp() {
        dir.deleteRecursively()
    }

    // the street shown: y = 41.5 N, from 77.62 W to 77.60 W
    private val lat0 = 41.5
    private val west = -77.62
    private val east = -77.60

    /** What the fixture's address file holds besides its fixed streets (see [addressDb]). */
    private class Extra(
        val flynnRange: Boolean = true, val flynnPoint: Boolean = false, val otherNameRange: Boolean = false,
        val nextRange: Boolean = true, val northRange: Boolean = true, val southRange: Boolean = false, val routeCrossing: Boolean = false,
        val crossPoint: Boolean = false,
    )

    private var made = 0

    private fun service(extra: Extra = Extra()): SearchService {
        val d = File(dir, "${made++}").also { it.mkdirs() }
        val search = searchDb(d)
        val addr = addressDb(d, extra)
        return SearchService(SearchEngine(search.path), AddressIndex(addr.path))
    }

    private fun exec(f: File, vararg sql: String) {
        val c = BundledSQLiteDriver().open(f.path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
        try {
            for (s in sql) c.execSQL(s)
        } finally {
            c.close()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** A street_line blob (packbuilder/search/lines.py encode) of lines given as (lat, lon) pairs in degrees. */
    private fun lineBlob(vararg lines: List<Pair<Double, Double>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun varint(v0: Long) {
            var v = v0
            while (v >= 0x80) {
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            out.write(v.toInt())
        }
        fun zigzag(n: Long) = if (n >= 0) n shl 1 else ((-n) shl 1) - 1
        varint(lines.size.toLong())
        var plat = 0L
        var plon = 0L
        for (l in lines) {
            varint(l.size.toLong())
            for ((la, lo) in l) {
                val a = (la * 1e5).roundToInt().toLong()
                val b = (lo * 1e5).roundToInt().toLong()
                varint(zigzag(a - plat))
                varint(zigzag(b - plon))
                plat = a
                plon = b
            }
        }
        return out.toByteArray()
    }

    /** An edges.geom blob: little-endian int32 (lon, lat) micro-degree pairs; points given as (lat, lon). */
    private fun edgeBlob(vararg pts: Pair<Double, Double>): ByteArray {
        val bb = ByteBuffer.allocate(pts.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        for ((la, lo) in pts) {
            bb.putInt((lo * 1e6).roundToInt())
            bb.putInt((la * 1e6).roundToInt())
        }
        return bb.array()
    }

    /**
     * What SearchEngine reads at open time, and the street entries with their boxes, lines and municipalities: 101 (the
     * street, also state route 999), 102 (the road of the name 45 km away, in Farville), 103 (the road's next piece east,
     * in Nextville), 104 ("Example Mill Lane", 5.5 km north), 105 (a road of the name in Nextville, 300 m south of
     * the street and parallel to it, its ends nowhere near the street's line), 106 ("South Main Street", 10 km north,
     * running south from the square; the search pack's derived key "~main street" as OSM's naming gives it) and 107
     * ("Main Street", 16.5 km north).
     */
    private fun searchDb(d: File): File {
        val f = File(d, "search.sqlite")
        exec(f,
            "CREATE TABLE category(id INTEGER PRIMARY KEY, code TEXT, label TEXT)",
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)",
            "INSERT INTO meta VALUES ('schema_version', '1'), ('addr_key', '{\"table_version\": \"2\"}'), ('state', 'PA')",
            "CREATE TABLE entry(id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, name TEXT NOT NULL, name_key TEXT NOT NULL, alt_keys TEXT, " +
                "type TEXT, lat REAL NOT NULL, lon REAL NOT NULL, importance REAL NOT NULL, muni_id INTEGER, county_id INTEGER, brand_id INTEGER, subtitle TEXT)",
            "CREATE TABLE street(entry_id INTEGER PRIMARY KEY, way_count INTEGER, length_m INTEGER, osm_way INTEGER, addr_key TEXT)",
            "CREATE TABLE street_line(entry_id INTEGER PRIMARY KEY, line BLOB NOT NULL)",
            "CREATE VIRTUAL TABLE entry_rtree USING rtree(id, minlat, maxlat, minlon, maxlon)",
            "INSERT INTO entry VALUES (1, 1, 'Exampleton Township', 'exampleton township', NULL, 'township', 41.51, -77.61, 0.5, NULL, NULL, NULL, 'Township, Example County, PA')",
            "INSERT INTO entry VALUES (101, 2, 'Example Flinn Road', 'example flinn road', 'pa 999|~route 999|~state route 999', 'secondary', $lat0, -77.61, 0.4, 1, NULL, NULL, 'Exampleton Township, Example County')",
            "INSERT INTO street VALUES (101, 2, 1700, 1, 'example flinn rd')",
            "INSERT INTO street_line VALUES (101, X'${hex(lineBlob(listOf(lat0 to west, lat0 to -77.61), listOf(lat0 to -77.61, lat0 to east)))}')",
            "INSERT INTO entry_rtree VALUES (101, $lat0, $lat0, $west, $east)",
            "INSERT INTO entry VALUES (2, 1, 'Farville Township', 'farville township', NULL, 'township', 41.81, -77.21, 0.5, NULL, NULL, NULL, 'Township, Far County, PA')",
            "INSERT INTO entry VALUES (102, 2, 'Example Flinn Road', 'example flinn road', NULL, 'secondary', 41.8, -77.21, 0.4, 2, NULL, NULL, 'Farville Township, Far County')",
            "INSERT INTO street VALUES (102, 1, 1700, 2, 'example flinn rd')",
            "INSERT INTO street_line VALUES (102, X'${hex(lineBlob(listOf(41.8 to -77.22, 41.8 to -77.20)))}')",
            "INSERT INTO entry_rtree VALUES (102, 41.8, 41.8, -77.22, -77.20)",
            "INSERT INTO entry VALUES (3, 1, 'Nextville Township', 'nextville township', NULL, 'township', 41.51, -77.59, 0.5, NULL, NULL, NULL, 'Township, Example County, PA')",
            "INSERT INTO entry VALUES (103, 2, 'Example Flinn Road', 'example flinn road', NULL, 'secondary', $lat0, -77.59, 0.4, 3, NULL, NULL, 'Nextville Township, Example County')",
            "INSERT INTO street VALUES (103, 1, 1700, 3, 'example flinn rd')",
            "INSERT INTO street_line VALUES (103, X'${hex(lineBlob(listOf(lat0 to east, lat0 to -77.58)))}')",
            "INSERT INTO entry_rtree VALUES (103, $lat0, $lat0, $east, -77.58)",
            "INSERT INTO entry VALUES (104, 2, 'Example Mill Lane', 'example mill lane', NULL, 'residential', 41.55, -77.61, 0.3, 1, NULL, NULL, 'Exampleton Township, Example County')",
            "INSERT INTO street VALUES (104, 1, 1700, 4, 'example ml ln')",
            "INSERT INTO street_line VALUES (104, X'${hex(lineBlob(listOf(41.55 to west, 41.55 to east)))}')",
            "INSERT INTO entry_rtree VALUES (104, 41.55, 41.55, $west, $east)",
            "INSERT INTO entry VALUES (105, 2, 'Example Flinn Road', 'example flinn road', NULL, 'secondary', 41.4973, -77.61, 0.4, 3, NULL, NULL, 'Nextville Township, Example County')",
            "INSERT INTO street VALUES (105, 1, 1700, 5, 'example flinn rd')",
            "INSERT INTO street_line VALUES (105, X'${hex(lineBlob(listOf(41.4973 to west, 41.4973 to east)))}')",
            "INSERT INTO entry_rtree VALUES (105, 41.4973, 41.4973, $west, $east)",
            "INSERT INTO entry VALUES (106, 2, 'South Main Street', 'south main street', '~main street', 'secondary', 41.595, -77.61, 0.4, 1, NULL, NULL, 'Exampleton Township, Example County')",
            "INSERT INTO street VALUES (106, 1, 1100, 6, 's main st')",
            "INSERT INTO street_line VALUES (106, X'${hex(lineBlob(listOf(41.6 to -77.61, 41.59 to -77.61)))}')",
            "INSERT INTO entry_rtree VALUES (106, 41.59, 41.6, -77.61, -77.61)",
            "INSERT INTO entry VALUES (107, 2, 'Main Street', 'main street', NULL, 'secondary', 41.65, -77.61, 0.4, 1, NULL, NULL, 'Exampleton Township, Example County')",
            "INSERT INTO street VALUES (107, 1, 1700, 7, 'main st')",
            "INSERT INTO street_line VALUES (107, X'${hex(lineBlob(listOf(41.65 to west, 41.65 to east)))}')",
            "INSERT INTO entry_rtree VALUES (107, 41.65, 41.65, $west, $east)",
            "INSERT INTO entry VALUES (8, 1, 'Boroughton', 'boroughton', NULL, 'borough', 41.7005, -77.618, 0.5, NULL, NULL, NULL, 'Borough, Example County, PA')",
            "INSERT INTO entry VALUES (9, 1, 'Outer Township', 'outer township', NULL, 'township', 41.71, -77.59, 0.5, NULL, NULL, NULL, 'Township, Example County, PA')",
            "INSERT INTO entry VALUES (108, 2, 'Example Pike', 'example pike', NULL, 'secondary', 41.7, -77.6175, 0.4, 8, NULL, NULL, 'Boroughton, Example County')",
            "INSERT INTO street VALUES (108, 1, 400, 8, 'example pike')",
            "INSERT INTO street_line VALUES (108, X'${hex(lineBlob(listOf(41.7 to west, 41.7 to -77.615)))}')",
            "INSERT INTO entry_rtree VALUES (108, 41.7, 41.7, $west, -77.615)",
            "INSERT INTO entry VALUES (109, 2, 'Example Pike', 'example pike', NULL, 'secondary', 41.7, -77.605, 0.4, 9, NULL, NULL, 'Outer Township, Example County')",
            "INSERT INTO street VALUES (109, 1, 2100, 9, 'example pike')",
            "INSERT INTO street_line VALUES (109, X'${hex(lineBlob(listOf(41.7 to -77.605, 41.7 to -77.58)))}')",
            "INSERT INTO entry_rtree VALUES (109, 41.7, 41.7, -77.605, -77.58)",
            "INSERT INTO entry VALUES (10, 1, 'Westham Township', 'westham township', NULL, 'township', 41.76, -77.61, 0.5, NULL, NULL, NULL, 'Township, Example County, PA')",
            "INSERT INTO entry VALUES (11, 1, 'Eastham Township', 'eastham township', NULL, 'township', 41.76, -77.44, 0.5, NULL, NULL, NULL, 'Township, Example County, PA')",
            "INSERT INTO entry VALUES (110, 2, 'Example Church Road', 'example church road', NULL, 'residential', 41.75, -77.61, 0.3, 10, NULL, NULL, 'Westham Township, Example County')",
            "INSERT INTO street VALUES (110, 1, 1700, 10, 'example church rd')",
            "INSERT INTO street_line VALUES (110, X'${hex(lineBlob(listOf(41.75 to west, 41.75 to east)))}')",
            "INSERT INTO entry_rtree VALUES (110, 41.75, 41.75, $west, $east)",
            "INSERT INTO entry VALUES (111, 2, 'Example Church Road', 'example church road', NULL, 'residential', 41.75, -77.44, 0.3, 11, NULL, NULL, 'Eastham Township, Example County')",
            "INSERT INTO street VALUES (111, 1, 1700, 11, 'example church rd')",
            "INSERT INTO street_line VALUES (111, X'${hex(lineBlob(listOf(41.75 to -77.45, 41.75 to -77.43)))}')",
            "INSERT INTO entry_rtree VALUES (111, 41.75, 41.75, -77.45, -77.43)",
        )
        return f
    }

    /**
     * The address file: localities 1 (the neighbouring town's ZIP 16999, Othertown), 2 (Exampleton's own ZIP 16998) and 3
     * (Farville, 45 km away), all made up. Streets: 10 "Example Flynn Rd" (the census's spelling, locality 1), 11 "Example
     * Flinn Rd" in Farville, 12 "Side Lane" (crosses the street), 13 "Mill Lane" (100 m north, parallel), 14 "Example Flinn
     * Rd" 1 km north (a same-named road near but not this one), 15 "Hilltop Rd" (another name for the street shown), 16
     * "Example Flinn Rd" in Nextville (locality 4: the road east of the street, under OSM's spelling), 17 "Example Flinn
     * Rd" in Nextville along entry 105, 300 m south; around "Example Mill Lane" (5.5 km north) 20 "Example Hill Ln" (120 m
     * north, parallel, with its own range), 21 "Example Mill Rd" and 22 "N Example Mill Ln", each with a house 120; 30
     * "Township Road 999" crossing the street (route 999's highway alternative "twp 999"), with a house 120 by the corner.
     */
    private fun addressDb(d: File, x: Extra): File {
        val f = File(d, "addresses.sqlite")
        fun e6(v: Double) = (v * 1e6).roundToInt()
        val sql = arrayListOf(
            "CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)",
            "INSERT INTO meta VALUES ('state', 'PA'), ('normalisation_table_version', '2')",
            "CREATE TABLE places(place_key TEXT NOT NULL, name TEXT NOT NULL, kind TEXT NOT NULL, zip TEXT, county_fips TEXT, n INTEGER)",
            "INSERT INTO places VALUES ('othertown', 'Othertown', 'postal', '16999', '42999', 5), ('exampleton', 'Exampleton', 'municipality', '16998', '42999', 5), " +
                "('farville', 'Farville', 'postal', '16900', '42998', 5), ('farville', 'Farville', 'municipality', '16900', '42998', 5), " +
                // the user's own postal town: none of the street's ranges or points is filed under it
                "('exampletown', 'Exampletown', 'postal', '16997', '42999', 5), " +
                // a town 25 km from every road of the name
                "('fartown', 'Fartown', 'postal', '16950', '42998', 5), " +
                // Boroughton: a borough and the postal town of Outer Township around it; Nearville: a postal town 3 km
                // from the street that serves none of its municipality
                "('boroughton', 'Boroughton', 'postal', '16980', '42999', 5), ('boroughton', 'Boroughton', 'municipality', '16980', '42999', 5), " +
                "('nearville', 'Nearville', 'postal', '16996', '42999', 5), " +
                // Bigtown: a postal town whose mail goes to Westham and Eastham townships, 14 km apart
                "('bigtown', 'Bigtown', 'postal', '16970', '42999', 5)",
            "CREATE TABLE localities(id INTEGER PRIMARY KEY, zip TEXT, city TEXT, city_key TEXT, city_src TEXT, municipality TEXT, muni_key TEXT, county_fips TEXT NOT NULL)",
            "INSERT INTO localities VALUES (1, '16999', 'Othertown', 'othertown', 'source', 'Exampleton', 'exampleton', '42999'), " +
                "(2, '16998', 'Exampleton', 'exampleton', 'source', 'Exampleton', 'exampleton', '42999'), " +
                "(3, '16900', 'Farville', 'farville', 'source', 'Farville', 'farville', '42998'), " +
                "(4, '16998', 'Exampleton', 'exampleton', 'source', 'Nextville', 'nextville', '42999'), " +
                // Exampletown's mail goes to Exampleton township; Boroughton's to Outer Township, whose other part has
                // the next town's ZIP 16981 (Sidetown); Nearville's only to Nearville
                "(5, '16997', 'Exampletown', 'exampletown', 'source', 'Exampleton', 'exampleton', '42999'), " +
                "(6, '16980', 'Boroughton', 'boroughton', 'source', 'Outer', 'outer', '42999'), " +
                "(7, '16981', 'Sidetown', 'sidetown', 'source', 'Outer', 'outer', '42999'), " +
                "(8, '16996', 'Nearville', 'nearville', 'source', 'Nearville', 'nearville', '42999'), " +
                "(9, '16970', 'Bigtown', 'bigtown', 'source', 'Westham', 'westham', '42999'), " +
                "(10, '16970', 'Bigtown', 'bigtown', 'source', 'Eastham', 'eastham', '42999'), " +
                "(11, '16971', 'Farham', 'farham', 'source', 'Eastham', 'eastham', '42999'), " +
                "(12, '16972', 'Nearham', 'nearham', 'source', 'Westham', 'westham', '42999')",
            "CREATE TABLE streets(id INTEGER PRIMARY KEY, street TEXT NOT NULL, street_key TEXT NOT NULL, street_core TEXT NOT NULL, locality_id INTEGER NOT NULL, " +
                "n_points INTEGER NOT NULL, n_ranges INTEGER NOT NULL, lat_e6 INTEGER, lon_e6 INTEGER)",
            "INSERT INTO streets VALUES (10, 'Example Flynn Rd', 'example flynn rd', 'example flynn', 1, 1, 1, ${e6(lat0)}, ${e6(-77.61)}), " +
                "(11, 'Example Flinn Rd', 'example flinn rd', 'example flinn', 3, 0, 1, ${e6(41.8)}, ${e6(-77.21)}), " +
                "(12, 'Side Lane', 'side ln', 'side', 2, 1, 1, ${e6(41.502)}, ${e6(-77.615)}), " +
                "(13, 'Mill Lane', 'ml ln', 'ml', 2, 0, 1, ${e6(41.5009)}, ${e6(-77.61)}), " +
                "(14, 'Example Flinn Rd', 'example flinn rd', 'example flinn', 2, 0, 1, ${e6(41.509)}, ${e6(-77.61)}), " +
                "(15, 'Hilltop Rd', 'hilltop rd', 'hilltop', 2, 0, 1, ${e6(lat0)}, ${e6(-77.61)}), " +
                "(16, 'Example Flinn Rd', 'example flinn rd', 'example flinn', 4, 0, 1, ${e6(lat0)}, ${e6(-77.59)}), " +
                "(17, 'Example Flinn Rd', 'example flinn rd', 'example flinn', 4, 0, 1, ${e6(41.4973)}, ${e6(-77.61)}), " +
                "(20, 'Example Hill Ln', 'example hl ln', 'example hl', 2, 1, 1, ${e6(41.5511)}, ${e6(-77.61)}), " +
                "(21, 'Example Mill Rd', 'example ml rd', 'example ml', 2, 1, 0, ${e6(41.5504)}, ${e6(-77.61)}), " +
                "(22, 'N Example Mill Ln', 'n example ml ln', 'example ml', 2, 1, 0, ${e6(41.5497)}, ${e6(-77.61)}), " +
                "(30, 'Township Road 999', 'twp 999', 'twp 999', 2, 1, 1, ${e6(41.5015)}, ${e6(-77.612)}), " +
                "(40, 'S Main St', 's main st', 'main', 2, 0, 1, ${e6(41.595)}, ${e6(-77.61)}), " +
                "(41, 'N Main St', 'n main st', 'main', 2, 1, 0, ${e6(41.605)}, ${e6(-77.61)}), " +
                "(50, 'W Main St', 'w main st', 'main', 2, 1, 0, ${e6(41.6503)}, ${e6(-77.615)}), " +
                "(51, 'E Main St', 'e main st', 'main', 2, 1, 0, ${e6(41.6503)}, ${e6(-77.605)}), " +
                "(60, 'Exampel Pike', 'exampel pike', 'exampel', 7, 0, 1, ${e6(41.7)}, ${e6(-77.595)}), " +
                "(70, 'Example Chruch Rd', 'example chruch rd', 'example chruch', 12, 0, 1, ${e6(41.75)}, ${e6(-77.61)}), " +
                "(71, 'Example Church Rd', 'example church rd', 'example church', 11, 1, 0, ${e6(41.75)}, ${e6(-77.44)})",
            "CREATE TABLE addresses(id INTEGER PRIMARY KEY, street_id INTEGER NOT NULL, hn TEXT NOT NULL, hn_int INTEGER, unit TEXT, lat_e6 INTEGER NOT NULL, lon_e6 INTEGER NOT NULL, source TEXT NOT NULL)",
            // the crossing street's own house 120, 33 m off the line and nearest the user: never "120 Example Flinn Road"
            "INSERT INTO addresses VALUES (1, 12, '120', 120, NULL, ${e6(41.5003)}, ${e6(-77.6152)}, 'nad')",
            // around Example Mill Lane: 120 Example Hill Ln 120 m north, 120 Example Mill Rd 44 m north (both nearer the
            // user than) 120 N Example Mill Ln, 33 m south: only the last is the lane's
            "INSERT INTO addresses VALUES (3, 20, '120', 120, NULL, ${e6(41.5511)}, ${e6(-77.6159)}, 'nad'), " +
                "(4, 21, '120', 120, NULL, ${e6(41.5504)}, ${e6(-77.615)}, 'nad'), (5, 22, '120', 120, NULL, ${e6(41.5497)}, ${e6(-77.617)}, 'nad')",
            // 12 N Main St, 44 m north of the square where South Main Street ends; 120 W Main St and 120 E Main St, each 33 m
            // off Main Street
            "INSERT INTO addresses VALUES (8, 41, '12', 12, NULL, ${e6(41.6004)}, ${e6(-77.6101)}, 'nad'), " +
                "(9, 50, '120', 120, NULL, ${e6(41.6503)}, ${e6(-77.615)}, 'nad'), (10, 51, '120', 120, NULL, ${e6(41.6503)}, ${e6(-77.605)}, 'nad')",
            "CREATE TABLE edges(id INTEGER PRIMARY KEY, geom BLOB NOT NULL)",
            "CREATE TABLE ranges(id INTEGER PRIMARY KEY, street_id INTEGER NOT NULL, edge_id INTEGER NOT NULL, side TEXT, hn_from INTEGER NOT NULL, hn_to INTEGER NOT NULL, " +
                "hn_lo INTEGER NOT NULL, hn_hi INTEGER NOT NULL, parity TEXT, source TEXT NOT NULL)",
            // Farville's Example Flinn Rd, 45 km away
            "INSERT INTO edges VALUES (1001, X'${hex(edgeBlob(41.8 to -77.22, 41.8 to -77.20))}')",
            "INSERT INTO ranges VALUES (2, 11, 1001, 'R', 100, 198, 100, 198, 'E', 'tiger')",
            // Side Lane from the street northwards: 120 is 11 m from the corner, on a crossing edge
            "INSERT INTO edges VALUES (1002, X'${hex(edgeBlob(lat0 to -77.615, 41.504 to -77.615))}')",
            "INSERT INTO ranges VALUES (3, 12, 1002, 'L', 118, 198, 118, 198, 'E', 'tiger')",
            // Mill Lane, parallel 100 m north
            "INSERT INTO edges VALUES (1003, X'${hex(edgeBlob(41.5009 to west, 41.5009 to east))}')",
            "INSERT INTO ranges VALUES (4, 13, 1003, 'L', 100, 198, 100, 198, 'E', 'tiger')",
            // Example Hill Ln's own range, on its own line 120 m north of Example Mill Lane
            "INSERT INTO edges VALUES (1020, X'${hex(edgeBlob(41.5511 to west, 41.5511 to east))}')",
            "INSERT INTO ranges VALUES (20, 20, 1020, 'R', 100, 198, 100, 198, 'E', 'tiger')",
            // South Main Street's own census range, 2-98 even from the square southwards, on its right (west)
            "INSERT INTO edges VALUES (1040, X'${hex(edgeBlob(41.6 to -77.61003, 41.59 to -77.61003))}')",
            "INSERT INTO ranges VALUES (40, 40, 1040, 'R', 2, 98, 2, 98, 'E', 'tiger')",
            // the census range of Example Pike's Outer Township piece: another spelling, under Sidetown's ZIP
            "INSERT INTO edges VALUES (1060, X'${hex(edgeBlob(41.70003 to -77.605, 41.70003 to -77.58))}')",
            "INSERT INTO ranges VALUES (60, 60, 1060, 'R', 100, 198, 100, 198, 'E', 'tiger')",
            // Westham's Example Church Road: a census range under another spelling, filed under Nearham's ZIP; Eastham's,
            // 14 km away: a house 120 under its own name, filed under Farham's
            "INSERT INTO edges VALUES (1070, X'${hex(edgeBlob(41.75003 to west, 41.75003 to east))}')",
            "INSERT INTO ranges VALUES (70, 70, 1070, 'R', 100, 198, 100, 198, 'E', 'tiger')",
            "INSERT INTO addresses VALUES (11, 71, '120', 120, NULL, ${e6(41.75027)}, ${e6(-77.44)}, 'nad')",
            "CREATE INDEX streets_key ON streets(street_key)",
            "CREATE INDEX streets_core ON streets(street_core)",
            "CREATE INDEX streets_locality ON streets(locality_id)",
            "CREATE INDEX addresses_street_hn ON addresses(street_id, hn)",
            "CREATE INDEX ranges_street ON ranges(street_id, hn_lo)",
            "CREATE INDEX places_key ON places(place_key)",
            "CREATE INDEX localities_zip ON localities(zip)",
            "CREATE INDEX localities_city ON localities(city_key)",
            "CREATE INDEX localities_muni ON localities(muni_key)",
            "CREATE VIEW address_rows AS SELECT a.id, a.hn, s.street, s.street_key, a.unit, l.city, l.municipality, l.zip, l.county_fips, " +
                "a.lat_e6 / 1e6 AS lat, a.lon_e6 / 1e6 AS lon, a.source, 'point' AS accuracy FROM addresses a JOIN streets s ON s.id = a.street_id JOIN localities l ON l.id = s.locality_id",
        )
        if (x.flynnRange) {
            // the census range of the street shown: 4 m north of OSM's line, 100-198 even on its right (south) side
            sql += "INSERT INTO edges VALUES (1000, X'${hex(edgeBlob(41.50004 to west, 41.50004 to east))}')"
            sql += "INSERT INTO ranges VALUES (1, 10, 1000, 'R', 100, 198, 100, 198, 'E', 'tiger')"
        }
        if (x.flynnPoint) sql += "INSERT INTO addresses VALUES (2, 10, '120', 120, NULL, ${e6(41.4997)}, ${e6(-77.6158)}, 'nad')"
        if (x.northRange) {
            // the same-named road 1 km north
            sql += "INSERT INTO edges VALUES (1004, X'${hex(edgeBlob(41.509 to west, 41.509 to east))}')"
            sql += "INSERT INTO ranges VALUES (5, 14, 1004, 'R', 100, 198, 100, 198, 'E', 'tiger')"
        }
        if (x.nextRange) {
            // the road east of the street, in Nextville
            sql += "INSERT INTO edges VALUES (1006, X'${hex(edgeBlob(41.50003 to east, 41.50003 to -77.58))}')"
            sql += "INSERT INTO ranges VALUES (7, 16, 1006, 'R', 100, 198, 100, 198, 'E', 'tiger')"
        }
        if (x.southRange) {
            // along entry 105, 300 m south
            sql += "INSERT INTO edges VALUES (1007, X'${hex(edgeBlob(41.4973 to west, 41.4973 to east))}')"
            sql += "INSERT INTO ranges VALUES (8, 17, 1007, 'R', 100, 198, 100, 198, 'E', 'tiger')"
        }
        if (x.crossPoint) {
            // house 120 of the road's next piece (Nextville), 125 m past the municipal line where the street ends
            sql += "INSERT INTO addresses VALUES (7, 16, '120', 120, NULL, ${e6(41.5003)}, ${e6(-77.5985)}, 'nad')"
        }
        if (x.routeCrossing) {
            // Township Road 999's house 120, 44 m off the street by the corner, and a range on an edge of no length at the corner
            sql += "INSERT INTO addresses VALUES (6, 30, '120', 120, NULL, ${e6(41.5004)}, ${e6(-77.6121)}, 'nad')"
            sql += "INSERT INTO edges VALUES (1030, X'${hex(edgeBlob(lat0 to -77.612, lat0 to -77.612))}')"
            sql += "INSERT INTO ranges VALUES (30, 30, 1030, 'L', 100, 198, 100, 198, 'E', 'tiger')"
        }
        if (x.otherNameRange) {
            sql += "INSERT INTO edges VALUES (1005, X'${hex(edgeBlob(41.49998 to west, 41.49998 to east))}')"
            sql += "INSERT INTO ranges VALUES (6, 15, 1005, 'L', 100, 198, 100, 198, 'E', 'tiger')"
        }
        exec(f, *sql.toTypedArray())
        return f
    }

    /** A street row as the engine gives it for a typed house number (located: [acc] POINT and "120 " in the title). */
    private fun street(id: Long = 101, name: String = "Example Flinn Road", lat: Double = lat0, lon: Double = -77.61, score: Double = 1.2345,
                       tier: String = "exact", acc: AddressInfo.Accuracy = AddressInfo.Accuracy.STREET) = SearchResult(
        id = id, kind = SearchResult.Kind.STREET, title = if (acc == AddressInfo.Accuracy.POINT) "120 $name" else name,
        subtitle = "Exampleton Township, Example County", categoryId = "secondary", categoryLabel = "Street", lat = lat, lon = lon,
        distanceKm = null, brandId = null, address = AddressInfo("120", name, acc), score = score, how = "name", tier = tier,
    )

    private fun poi(score: Double = 0.5) = SearchResult(
        id = 5000, kind = SearchResult.Kind.POI, title = "Example Flinn Diner", subtitle = "Exampleton", categoryId = "restaurant",
        categoryLabel = "Restaurant", lat = 41.51, lon = -77.61, distanceKm = null, brandId = null, address = null, score = score,
        how = "name", tier = "word_prefix",
    )

    private fun response(query: String, vararg rows: SearchResult, named: NamedPlace? = null, hn: String = "120") =
        SearchResponse(query, emptyList(), rows.toList(), 1.0, null, false, houseNumber = hn, namedPlace = named)

    /** Where the engine ranks a query naming Exampletown (its anchor): a made-up point 600 m north of the street. */
    private val exampletown = NamedPlace("Exampletown", 41.5055, -77.612)

    private fun metres(r: SearchResult, lat: Double, lon: Double) = Geo.haversineKm(r.lat, r.lon, lat, lon) * 1000.0

    // where the census range puts 120: 20/98 of the way east, 10 m to its right (south) of the edge 4 m north of the line
    private val estLat = 41.50004 - 10 / 111_195.0
    private val estLon = west + 20.0 / 98.0 * (east - west)

    // where Hilltop Rd's range (2 m south of the line) puts 120: 10 m to its left (north)
    private val hilltopLat = 41.49998 + 10 / 111_195.0

    /** (1) No town typed: OSM's name, the census's other spelling and ZIP: an estimated address goes first. */
    @Test
    fun anEstimatedAddressAlongTheStreetShownGoesFirst() {
        service().use { s ->
            val engine = response("120 example flinn road", street(), poi())
            // ranked near the crossing street and the parallel lane: their ranges are nearer, but not along the street
            for ((lat, lon) in listOf(41.501 to -77.615, null to null, 41.8 to -77.21)) {
                val r = s.withAddress(engine, lat, lon, 10)
                val a = r.results[0]
                assertEquals(SearchResult.Kind.ADDRESS, a.kind, "$lat")
                assertEquals("120 Example Flynn Rd", a.title)
                assertEquals(AddressInfo("120", "Example Flynn Rd", AddressInfo.Accuracy.INTERPOLATED, null, false), a.address)
                assertEquals("Othertown, PA 16999", a.subtitle)
                assertTrue(metres(a, estLat, estLon) < 2.0, "${metres(a, estLat, estLon)} m from the census position")
                assertTrue(a.id < 0)
                // on the engine's scale: just above its best row (rule S4), the engine's rows kept after it
                assertEquals(Py.round(1.2345 + 0.0001, 4), a.score)
                assertEquals(listOf(101L, 5000L), r.results.drop(1).map { it.id })
                assertEquals(if (lat == null) null else Py.round(Geo.haversineKm(lat, lon!!, a.lat, a.lon), 2), a.distanceKm)
            }
            // the limit holds: the address and the best engine row
            assertEquals(listOf(SearchResult.Kind.ADDRESS, SearchResult.Kind.STREET), s.withAddress(engine, null, null, 2).results.map { it.kind })
            // the same house as typed with the census's town: the same id
            val typed = s.withAddress(response("120 example flynn rd othertown", street()), null, null, 10).results[0]
            assertEquals(AddressInfo.Accuracy.INTERPOLATED, typed.address!!.accuracy)
            assertEquals(typed.id, s.withAddress(engine, null, null, 10).results[0].id)
        }
    }

    /** (1) "Any spelling": a range filed under another name altogether, lying along the street, counts too. */
    @Test
    fun aRangeOfAnotherNameAlongTheStreetCounts() {
        service(Extra(flynnRange = false, otherNameRange = true)).use { s ->
            val a = s.withAddress(response("120 example flinn road", street()), 41.501, -77.615, 10).results[0]
            assertEquals(SearchResult.Kind.ADDRESS, a.kind)
            assertEquals("120 Hilltop Rd", a.title)
            assertEquals(AddressInfo.Accuracy.INTERPOLATED, a.address!!.accuracy)
            assertTrue(metres(a, 41.49998 + 10 / 111_195.0, estLon) < 2.0)
        }
    }

    /** (2) A same-named road with a range covering the number, 1 km off the line or 45 km away, never wins. */
    @Test
    fun aSameNamedRoadElsewhereNeverWins() {
        // ranked from the road 1 km north, from Farville's or from nowhere: the street's own answer (here a range under
        // another name), never the same-named roads' that cover the number too
        service(Extra(flynnRange = false, otherNameRange = true)).use { s ->
            for ((lat, lon) in listOf(41.509 to -77.61, 41.8 to -77.21, null to null)) {
                val a = s.withAddress(response("120 example flinn road", street(), poi()), lat, lon, 10).results[0]
                assertEquals(SearchResult.Kind.ADDRESS, a.kind, "$lat")
                assertEquals("Exampleton, PA 16998", a.subtitle, "$lat")
                assertTrue(metres(a, hilltopLat, estLon) < 2.0, "$lat")
            }
        }
        // with nothing along the street: the engine's rows as they are
        service(Extra(flynnRange = false)).use { s ->
            val engine = response("120 example flinn road", street(), poi())
            assertSame(engine.results, s.withAddress(engine, 41.509, -77.61, 10).results)
        }
        // with the street's own range there, ranked from the far road: still the one along the street shown
        service().use { s ->
            val a = s.withAddress(response("120 example flinn road", street()), 41.8, -77.21, 10).results[0]
            assertTrue(metres(a, estLat, estLon) < 2.0)
        }
    }

    /**
     * (2) Where the search is ranked decides which street the number is looked along: a road of the name the list shows
     * 45 km away is not, unless the search is ranked there.
     */
    @Test
    fun aRoadOfTheNameThatTheListShowsElsewhereIsNotLookedAlong() {
        service(Extra(flynnRange = false, otherNameRange = true)).use { s ->
            val engine = response("120 example flinn road", street(), street(id = 102, lat = 41.8, lon = -77.21, score = 1.1), poi())
            for ((lat, lon) in listOf(41.501 to -77.615, null to null)) {
                val a = s.withAddress(engine, lat, lon, 10).results[0]
                assertEquals("Exampleton, PA 16998", a.subtitle, "$lat: the street's own answer, not Farville's")
                assertTrue(metres(a, hilltopLat, estLon) < 2.0, "$lat")
            }
            val a = s.withAddress(engine, 41.79, -77.21, 10).results[0]
            assertEquals(SearchResult.Kind.ADDRESS, a.kind)
            assertEquals("Farville, PA 16900", a.subtitle)
            assertTrue(Geo.haversineKm(a.lat, a.lon, 41.8, -77.22 + 0.02 * 20 / 98) < 0.02)
        }
    }

    /** (1) The road's next piece (the next township's row of the same name, its box touching) is looked along too. */
    @Test
    fun theNextPieceOfTheSameRoadCounts() {
        service(Extra(flynnRange = false)).use { s ->
            val engine = response("120 example flinn road", street(), street(id = 103, lat = lat0, lon = -77.59, score = 1.2), poi())
            val a = s.withAddress(engine, 41.501, -77.615, 10).results[0]
            assertEquals(SearchResult.Kind.ADDRESS, a.kind)
            assertEquals("120 Example Flinn Road", a.title)
            assertEquals("Exampleton, PA 16998", a.subtitle)
            assertTrue(metres(a, 41.50003 - 10 / 111_195.0, east + 0.02 * 20 / 98) < 2.0)
        }
    }

    /** (3) An exact point of the street (here under the census's spelling) wins over the range. */
    @Test
    fun anExactPointOnTheStreetWinsOverARange() {
        service(Extra(flynnPoint = true)).use { s ->
            val r = s.withAddress(response("120 example flinn road", street(), poi()), 41.501, -77.615, 10)
            val a = r.results[0]
            assertEquals(SearchResult.Kind.ADDRESS, a.kind)
            assertEquals("120 Example Flynn Rd", a.title)
            assertEquals(AddressInfo.Accuracy.POINT, a.address!!.accuracy)
            assertTrue(metres(a, 41.4997, -77.6158) < 0.5)
            assertEquals(Py.round(1.2345 + s.engine.rk.wHouseLocated, 4), a.score)
        }
    }

    /**
     * (a) A typed town or ZIP whose lookup locates no house there (the census range is filed under Othertown's ZIP, not
     * the user's Exampletown): the answer along the street shown goes first, with the range's own town and ZIP below, in
     * place of the lookup's guess elsewhere (Farville's house, today last).
     */
    @Test
    fun aTypedTownThatLocatesNothingThereGetsTheAnswerAlongTheStreet() {
        service().use { s ->
            for (q in listOf("120 Example Flinn Road, Exampletown", "120 example flinn road exampletown", "120 example flinn road 16997",
                "120 Example Flinn Road, Exampletown, PA")) {
                val r = s.withAddress(response(q, street(), poi(), named = exampletown), 41.501, -77.615, 10)
                val a = r.results[0]
                assertEquals(SearchResult.Kind.ADDRESS, a.kind, q)
                assertEquals("120 Example Flynn Rd", a.title, q)
                // in a municipality Exampletown's mail goes to: a match of the typed town
                assertEquals(AddressInfo("120", "Example Flynn Rd", AddressInfo.Accuracy.INTERPOLATED, true, false), a.address, q)
                assertEquals("Othertown, PA 16999", a.subtitle, q)
                assertTrue(metres(a, estLat, estLon) < 2.0, q)
                assertEquals(Py.round(1.2345 + 0.0001, 4), a.score, q)
                assertEquals(listOf(101L, 5000L), r.results.drop(1).map { it.id }, q)
            }
        }
    }

    /** (b) A typed town whose lookup locates the house there (a point of Othertown's "Example Flynn Rd"): as before. */
    @Test
    fun aTypedTownThatLocatesTheHouseIsUnchanged() {
        service(Extra(flynnPoint = true)).use { s ->
            val r = s.withAddress(response("120 example flinn road othertown", street(), poi()), 41.501, -77.615, 10)
            val a = r.results[0]
            assertEquals("120 Example Flynn Rd", a.title)
            assertEquals(AddressInfo("120", "Example Flynn Rd", AddressInfo.Accuracy.POINT, true, false), a.address)
            assertEquals(Py.round(1.2345 + s.engine.rk.wHouseLocated, 4), a.score)
            assertEquals(listOf(101L, 5000L), r.results.drop(1).map { it.id })
        }
    }

    /**
     * (c) A typed town whose lookup locates nothing there, and nothing along the street shown covers the number: as
     * before (the street row first, "Street only", the lookup's guess elsewhere last). And another state typed: as
     * before too, the street shown is not looked along.
     */
    @Test
    fun aTypedTownWithNothingAlongTheStreetIsUnchanged() {
        service(Extra(flynnRange = false)).use { s ->
            val r = s.withAddress(response("120 Example Flinn Road, Exampletown", street(), poi(), named = exampletown), 41.501, -77.615, 10)
            assertEquals(listOf(101L, 5000L), r.results.take(2).map { it.id })
            assertEquals(AddressInfo.Accuracy.STREET, r.results[0].address?.accuracy)
            val guess = r.results.last()
            assertEquals(SearchResult.Kind.ADDRESS, guess.kind)
            assertEquals(false, guess.address?.localityMatch)
            assertTrue(Geo.haversineKm(guess.lat, guess.lon, lat0, -77.61) > 0.3, "not along the street shown")
            assertEquals(3, r.results.size)
        }
        service().use { s ->
            val r = s.withAddress(response("120 Example Flinn Road, Exampletown, OH", street(), poi(), named = exampletown), 41.501, -77.615, 10)
            assertEquals(101L, r.results[0].id)
            assertTrue(r.results.none { it.kind == SearchResult.Kind.ADDRESS && it.address?.localityMatch != false })
        }
    }

    /**
     * Review 2 (3): a typed town far from every road of the name, named by the engine or not: the lookup's answer as
     * before (the street row first, the guess last), never the row nearest that town looked along; a typed municipality
     * the street is in, with the engine naming no place: the answer along it, a match of the typed town.
     */
    @Test
    fun aTypedTownFarFromTheStreetIsNotLookedAlong() {
        service().use { s ->
            for (named in listOf(NamedPlace("Fartown", 41.71, -77.45), null)) {
                val r = s.withAddress(response("120 Example Flinn Road, Fartown", street(), poi(), named = named), 41.501, -77.615, 10)
                assertEquals(101L, r.results[0].id, "$named")
                assertTrue(r.results.none { it.tier == AddressIndex.MATCH_ALONG }, "$named")
            }
        }
        service(Extra(flynnRange = false, otherNameRange = true, northRange = false, nextRange = false)).use { s ->
            val a = s.withAddress(response("120 Example Flinn Road, Exampleton", street(), poi()), 41.501, -77.615, 10).results[0]
            assertEquals(AddressIndex.MATCH_ALONG, a.tier)
            assertEquals(AddressInfo("120", "Hilltop Rd", AddressInfo.Accuracy.INTERPOLATED, true, false), a.address)
            assertTrue(metres(a, hilltopLat, estLon) < 2.0)
        }
    }

    /** Review 2 (7): another state typed with no town: nothing, as before item 55. */
    @Test
    fun anotherStateWithNoTownIsLeftAlone() {
        service().use { s ->
            val engine = response("120 example flinn road oh", street(), poi())
            assertSame(engine.results, s.withAddress(engine, 41.501, -77.615, 10).results)
            // the pack's own state: looked along
            assertEquals(SearchResult.Kind.ADDRESS, s.withAddress(response("120 example flinn road pa", street(), poi()), 41.501, -77.615, 10).results[0].kind)
        }
    }

    /**
     * Review 2 (2): a point counts only on the street's own name or the same core with no part contradicting it: around
     * "Example Mill Lane", 120 Example Hill Ln (one letter off, 120 m away, its range on its own line) and 120 Example
     * Mill Rd (another street type, 44 m away) are both nearer the user than 120 N Example Mill Ln (33 m off the lane),
     * which is the lane's house.
     */
    @Test
    fun aPointOfANearNameOnAnotherStreetIsNotTheHouse() {
        service().use { s ->
            val lane = street(id = 104, name = "Example Mill Lane", lat = 41.55, lon = -77.615)
            val a = s.withAddress(response("120 example mill lane", lane), 41.5506, -77.615, 10).results[0]
            assertEquals(SearchResult.Kind.ADDRESS, a.kind)
            assertEquals(AddressInfo("120", "N Example Mill Ln", AddressInfo.Accuracy.POINT, null, false), a.address)
            assertTrue(metres(a, 41.5497, -77.617) < 0.5, "${metres(a, 41.5497, -77.617)} m")
        }
    }

    /**
     * Review 2 (2): of the answers along one street, the closest name first, then the nearest to the line, before the
     * nearest to the user: the census range under the street's near spelling ("Example Flynn Rd") before Hilltop Rd's,
     * though Hilltop's puts 120 nearer the user.
     */
    @Test
    fun theClosestNameWinsOverTheNearestToTheUser() {
        service(Extra(otherNameRange = true)).use { s ->
            val a = s.withAddress(response("120 example flinn road", street(), poi()), 41.502, estLon, 10).results[0]
            assertEquals("Othertown, PA 16999", a.subtitle)
            assertTrue(metres(a, estLat, estLon) < 2.0)
        }
    }

    /**
     * Review 2 (10): state route 999's highway alternative "twp 999" names a crossing township road: its house by the
     * corner (nearest the user) is not the route's house, nor its range on an edge of no length at the corner; the
     * route's own census range is the answer, and with none, nothing.
     */
    @Test
    fun aCrossingRoadOfTheRoutesNumberIsNotTheRoute() {
        service(Extra(routeCrossing = true)).use { s ->
            val a = s.withAddress(response("120 example flinn road", street(), poi()), 41.5006, -77.6121, 10).results[0]
            assertEquals(AddressInfo.Accuracy.INTERPOLATED, a.address?.accuracy)
            assertTrue(metres(a, estLat, estLon) < 2.0, "${metres(a, estLat, estLon)} m from the route's own range")
        }
        service(Extra(routeCrossing = true, flynnRange = false)).use { s ->
            val engine = response("120 example flinn road", street(), poi())
            assertSame(engine.results, s.withAddress(engine, 41.5006, -77.6121, 10).results)
        }
    }

    /**
     * Review 2 (4): a long state route in pieces across municipalities (rows 101 and 103, joined end to end), the number
     * covered only by a census range under another spelling and another ZIP along the next piece; a road of the name
     * 45 km away where the engine located a house 120 does not stop it.
     */
    @Test
    fun aLocatedHouseOnARoadFarAwayDoesNotStopTheLookupOnTheRoadHere() {
        service(Extra(nextRange = false)).use { s ->
            val far = street(id = 102, lat = 41.7999, lon = -77.2159, score = 2.2, acc = AddressInfo.Accuracy.POINT)
            val engine = response("120 example flinn road", far, street(), street(id = 103, lat = lat0, lon = -77.59), poi())
            val r = s.withAddress(engine, 41.5004, -77.5985, 10)
            val a = r.results[0]
            assertEquals(AddressInfo("120", "Example Flynn Rd", AddressInfo.Accuracy.INTERPOLATED, null, false), a.address)
            assertEquals("Othertown, PA 16999", a.subtitle)
            assertTrue(metres(a, estLat, estLon) < 2.0)
            assertEquals(listOf(102L, 101L, 103L, 5000L), r.results.drop(1).map { it.id })
        }
        // the engine located the house on the road where the search is ranked: as it is
        service(Extra(nextRange = false)).use { s ->
            val here = street(acc = AddressInfo.Accuracy.POINT, lat = 41.4999, lon = -77.6158)
            val engine = response("120 example flinn road", here, street(id = 103, lat = lat0, lon = -77.59), poi())
            assertSame(engine, s.withAddress(engine, 41.5, -77.615, 10))
        }
    }

    /**
     * Review 2 (5): a road of the name 300 m south (entry 105, Nextville), parallel, its ends nowhere near the street's
     * line, is not the street's next piece; and the street's own answer wins over its joined piece's, even nearer the user.
     */
    @Test
    fun onlyAJoinedPieceCountsAndTheStreetsOwnAnswerFirst() {
        service(Extra(flynnRange = false, southRange = true)).use { s ->
            val engine = response("120 example flinn road", street(lon = -77.6159), street(id = 105, lat = 41.4973, lon = -77.6159), poi())
            assertSame(engine.results, s.withAddress(engine, 41.4995, -77.6159, 10).results)
        }
        service().use { s ->
            val engine = response("120 example flinn road", street(lon = -77.6005), street(id = 103, lat = lat0, lon = east), poi())
            val a = s.withAddress(engine, 41.5005, -77.6005, 10).results[0]
            assertEquals("Othertown, PA 16999", a.subtitle, "the street's own (Example Flynn Rd's), not the piece's")
            assertTrue(metres(a, estLat, estLon) < 2.0)
        }
    }

    /**
     * Review 3 (1): OSM's "South Main Street" (join key "s main st"; the search pack's derived key "main st" leaves the
     * direction out): 12 N Main St, 44 m past the square, is not its house; its own census range is the answer, titled with
     * its own name.
     */
    @Test
    fun aDirectionOfTheStreetIsKept() {
        service().use { s ->
            val south = street(id = 106, name = "South Main Street", lat = 41.6, lon = -77.61)
            val a = s.withAddress(response("12 south main street", south, hn = "12"), 41.6001, -77.61, 10).results[0]
            assertEquals(SearchResult.Kind.ADDRESS, a.kind)
            assertEquals("12 South Main Street", a.title)
            assertEquals(AddressInfo.Accuracy.INTERPOLATED, a.address?.accuracy)
            assertTrue(Geo.haversineKm(a.lat, a.lon, 41.6 - 0.01 * 10 / 96, -77.61003 - 10 / 83_390.0) < 0.002, "${metres(a, 41.59896, -77.61015)} m")
        }
    }

    /** Review 3 (5): "Main Street" with houses 120 on both W Main St and E Main St: the one shown says which. */
    @Test
    fun aDirectionTheStreetDoesNotHaveIsShown() {
        service().use { s ->
            val main = street(id = 107, name = "Main Street", lat = 41.65, lon = -77.606)
            val a = s.withAddress(response("120 main street", main), 41.6501, -77.606, 10).results[0]
            assertEquals("120 E Main St", a.title)
            assertEquals(AddressInfo("120", "E Main St", AddressInfo.Accuracy.POINT, null, false), a.address)
        }
    }

    /**
     * Review 3 (2+3): a house the engine located is not shown twice: the road crosses into Nextville where the street
     * ends, its house 120 is 125 m past the line, the engine located it on the next piece (row 103); the street looked
     * along (row 101, nearest the user) finds the same point.
     */
    @Test
    fun aHouseTheEngineLocatedIsNotShownTwice() {
        service(Extra(crossPoint = true)).use { s ->
            val located = street(id = 103, lat = 41.5003, lon = -77.5985, acc = AddressInfo.Accuracy.POINT, score = 2.2)
            val engine = response("120 example flinn road", street(lon = -77.6005), located, poi())
            assertSame(engine, s.withAddress(engine, 41.5005, -77.6005, 10))
        }
    }

    /**
     * Review 3 (2+3): with a town typed, a house the engine located on a road of the name 45 km away does not stop the
     * answer along the street in that town (the census range filed under the next town's ZIP).
     */
    @Test
    fun aHouseLocatedFarAwayDoesNotStopATypedTown() {
        service().use { s ->
            val far = street(id = 102, lat = 41.7999, lon = -77.2159, score = 2.2, acc = AddressInfo.Accuracy.POINT)
            val r = s.withAddress(response("120 Example Flinn Road, Exampletown", far, street(), poi(), named = exampletown), 41.501, -77.615, 10)
            val a = r.results[0]
            assertEquals(AddressIndex.MATCH_ALONG, a.tier)
            assertEquals("Othertown, PA 16999", a.subtitle)
            assertTrue(metres(a, estLat, estLon) < 2.0)
            assertEquals(listOf(102L, 101L, 5000L), r.results.drop(1).map { it.id })
        }
    }

    /**
     * Review 3 (4): a postal town's area is wider than a municipality's: the street 7 km from Exampletown's point (its
     * house filed under the next town's ZIP) is looked along when Exampletown is typed.
     */
    @Test
    fun aPostalTownReachesFarther() {
        service().use { s ->
            val named = NamedPlace("Exampletown", 41.563, -77.61)
            val a = s.withAddress(response("120 Example Flinn Road, Exampletown", street(), poi(), named = named), 41.501, -77.615, 10).results[0]
            assertEquals(AddressIndex.MATCH_ALONG, a.tier)
            assertTrue(metres(a, estLat, estLon) < 2.0)
        }
    }

    /**
     * Review 4: a borough typed that is also the postal town of the township around it: its own piece of Example Pike
     * (the row nearest it) has no answer and does not join the township's piece 850 m on; the township's piece, served by
     * the borough's postal town, has the census range (another spelling, filed under the next town's ZIP): it answers,
     * first, a match of the typed town.
     */
    @Test
    fun aBoroughThatIsThePostalTownReachesTheTownshipsStretch() {
        service().use { s ->
            val engine = response("120 example pike, boroughton", street(id = 108, name = "Example Pike", lat = 41.7, lon = -77.6175),
                street(id = 109, name = "Example Pike", lat = 41.7, lon = -77.605), named = NamedPlace("Boroughton", 41.7005, -77.618))
            val a = s.withAddress(engine, 41.7005, -77.618, 10).results[0]
            assertEquals(AddressIndex.MATCH_ALONG, a.tier)
            assertEquals("120 Exampel Pike", a.title)
            assertEquals("Sidetown, PA 16981", a.subtitle)
            assertEquals(AddressInfo("120", "Exampel Pike", AddressInfo.Accuracy.INTERPOLATED, true, false), a.address)
            assertTrue(Geo.haversineKm(a.lat, a.lon, 41.70003, -77.605 + 0.025 * 20 / 98) < 0.015)
        }
    }

    /**
     * Review 4 (1): the engine located 196 on the road's next piece, its point 300 m back from the road just past the
     * line between the two: the street's own estimate of 196, 35 m from that piece's line, is that house, not added.
     */
    @Test
    fun aHouseLocatedOnAJoinedPieceIsNotEstimatedAgain() {
        service().use { s ->
            val located = street(id = 103, lat = 41.4973, lon = -77.5995, acc = AddressInfo.Accuracy.POINT, score = 2.2)
            val engine = response("196 example flinn road", street(lon = -77.6005), located, poi(), hn = "196")
            assertSame(engine, s.withAddress(engine, 41.5005, -77.6005, 10))
        }
    }

    /**
     * Review 5 (2): the road crosses the line between two counties, each piece with its own 120: the engine located the
     * next piece's 120 (1.7 km away); the street's own census range still gives its own 120, first, the located row kept.
     */
    @Test
    fun theOtherPiecesLocatedHouseOfTheNumberDoesNotHideThisOne() {
        service().use { s ->
            val located = street(id = 103, lat = 41.5003, lon = -77.5959, acc = AddressInfo.Accuracy.POINT, score = 2.2)
            val engine = response("120 example flinn road", street(lon = -77.6159), located, poi())
            val r = s.withAddress(engine, 41.5005, -77.6159, 10)
            assertEquals(AddressIndex.MATCH_ALONG, r.results[0].tier)
            assertTrue(metres(r.results[0], estLat, estLon) < 2.0)
            assertEquals(listOf(101L, 103L, 5000L), r.results.drop(1).map { it.id })
        }
    }

    /**
     * Review 5 (1): a postal town serving two townships 14 km apart, each with an Example Church Road: the near one
     * (nearest the town) has only a census range under another spelling, the far one a house under its own name. The
     * near one's estimate is the answer: a nearer row with an answer is never passed over for a closer name elsewhere.
     */
    @Test
    fun theNearestRowWithAnAnswerWinsInATypedTown() {
        service().use { s ->
            val engine = response("120 example church road, bigtown", street(id = 110, name = "Example Church Road", lat = 41.75, lon = -77.61),
                street(id = 111, name = "Example Church Road", lat = 41.75, lon = -77.44), named = NamedPlace("Bigtown", 41.751, -77.61))
            val a = s.withAddress(engine, 41.751, -77.61, 10).results[0]
            assertEquals(AddressIndex.MATCH_ALONG, a.tier)
            assertEquals(AddressInfo("120", "Example Chruch Rd", AddressInfo.Accuracy.INTERPOLATED, true, false), a.address)
            assertEquals("Nearham, PA 16972", a.subtitle)
        }
    }

    /**
     * Review 4 (1, 2): a typed town where the engine located the house (its point 300 m back from the road): left as it
     * is, the town's lookup not run, no estimate above the exact address.
     */
    @Test
    fun aTypedTownWhereTheEngineLocatedTheHouseIsLeftAlone() {
        service().use { s ->
            val located = street(acc = AddressInfo.Accuracy.POINT, lat = 41.4973, lon = -77.6159, score = 2.2)
            val engine = response("120 example flynn rd othertown", located, poi())
            assertSame(engine, s.withAddress(engine, 41.501, -77.615, 10))
        }
    }

    /**
     * Review 4 (3): a postal town 3 km from the street that serves none of it, its answer not filed under it: the answer
     * along the street comes after the engine's rows (not a match of the typed town).
     */
    @Test
    fun anAnswerNearButNotInTheTypedTownGoesLast() {
        service().use { s ->
            val engine = response("120 example flinn road, nearville", street(), poi(), named = NamedPlace("Nearville", 41.527, -77.61))
            val r = s.withAddress(engine, 41.527, -77.61, 10)
            assertEquals(listOf(101L, 5000L), r.results.take(2).map { it.id })
            val a = r.results.last()
            assertEquals(AddressIndex.MATCH_ALONG, a.tier)
            assertEquals(false, a.address?.localityMatch)
            assertTrue(a.score < r.results.dropLast(1).minOf { it.score })
        }
    }

    /** (4) A typed town or ZIP: the lookup in that town as before (Farville's house, not the one along the street). */
    @Test
    fun aTypedTownOrZipBehavesAsBefore() {
        service().use { s ->
            for (q in listOf("120 example flinn road farville", "120 example flinn road 16900", "120 Example Flinn Rd, Farville, PA")) {
                val a = s.withAddress(response(q, street()), lat0, -77.61, 10).results[0]
                assertEquals(SearchResult.Kind.ADDRESS, a.kind, q)
                assertEquals("120 Example Flinn Rd", a.title, q)
                assertEquals(AddressInfo.Accuracy.INTERPOLATED, a.address?.accuracy, q)
                assertEquals(true, a.address!!.localityMatch, q)
                assertTrue(Geo.haversineKm(a.lat, a.lon, 41.8, -77.21) < 1.0, q)
            }
        }
    }

    /** (5) Nothing changes when the engine already located the house, or the street is only a word of the query. */
    @Test
    fun nothingChangesWhenTheEngineLocatedTheHouse() {
        service(Extra(flynnPoint = true)).use { s ->
            val located = response("120 example flinn road", street(acc = AddressInfo.Accuracy.POINT, lat = 41.4999), poi())
            assertSame(located, s.withAddress(located, 41.501, -77.615, 10))
            val anyWord = response("120 example flinn road", street(tier = "any_word"), poi())
            assertSame(anyWord.results, s.withAddress(anyWord, 41.501, -77.615, 10).results)
            // no house number: untouched
            val plain = SearchResponse("example flinn road", emptyList(), listOf(street().copy(address = null)), 1.0, null, false)
            assertSame(plain, s.withAddress(plain, 41.501, -77.615, 10))
            assertNull(s.withAddress(plain, 41.501, -77.615, 10).houseNumber)
        }
    }
}
