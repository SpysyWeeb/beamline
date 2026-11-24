package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.search.address.AddressIndex
import io.github.spysyweeb.beamline.search.address.AddressNormalizer
import io.github.spysyweeb.beamline.search.address.AddressParser
import io.github.spysyweeb.beamline.search.address.ParsedAddress
import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The cases of packbuilder/addresses/tests/test_addrlib.py (normaliser and parser), in Kotlin. */
class AddressUnitTest {
    private val n = AddressNormalizer()
    private val parser = AddressParser(n)
    private val places = setOf("erie", "state college", "spring city", "pittsburgh", "gibsonia", "wexford", "lower burrell")

    @Test
    fun equivalentForms() {
        val same = listOf(
            listOf("N Main St", "North Main Street", "n. main st.", "NORTH MAIN STREET"),
            listOf("St Clair Ave", "Saint Clair Avenue", "St. Clair Av"),
            listOf("21st St", "Twenty-First Street", "Twenty First St", "21 st Street"),
            listOf("1st Ave", "First Avenue"),
            listOf("State Route 8", "PA 8", "PA Route 8", "SR 8", "SR 0008", "Pennsylvania Route 8", "State Rte 8", "PA-8"),
            listOf("US 19", "US Route 19", "U.S. Highway 19", "US Hwy 19", "US-19"),
            listOf("I-79", "I 79", "Interstate 79", "I79", "I- 79"),
            listOf("Route 8", "Rte 8", "Rt 8", "Rt. 8"),
            listOf("Valley Drive Extension", "Valley Dr Ext"),
            listOf("Mt Lebanon Blvd", "Mount Lebanon Boulevard"),
            listOf("Fort Pitt Blvd", "Ft Pitt Blvd"),
            listOf("Township Road 312", "T-312", "Twp Rd 312"),
            listOf("Main Street Extended", "Main St Exd"),
            listOf("West Thirty-Eighth Street", "W 38th St"),
        )
        for (g in same) assertEquals(1, g.map { n.streetKey(it) }.toSet().size, "$g -> ${g.map { n.streetKey(it) }}")
    }

    @Test
    fun saintVersusStreetAndVariants() {
        assertEquals("saint marys st", n.streetKey("St Marys St"))
        assertEquals("main st", n.streetKey("Main St"))
        assertEquals("st rd", n.streetKey("Street Rd"))
        assertEquals("main st n", n.streetKey("Main St N"))
        assertEquals("n saint clair st", n.streetKey("N St Clair St"))
        assertEquals("mount saint marys rd", n.streetKey("Mount St Marys Rd"))
        assertEquals(n.streetKey("Main Street Extension"), n.streetKey("Main St Exn"))
        assertEquals("church st rear", n.streetKey("CHURCH ST REAR"))
        assertEquals("7th st juniata", n.streetKey("7th St Juniata"))
        assertEquals("st", n.fixTypeTypo("sreet"))
        assertEquals("ave", n.fixTypeTypo("aveune"))
        assertEquals("cir", n.fixTypeTypo("cicrle"))
        assertNull(n.fixTypeTypo("main"))
        assertEquals("state st", n.keyVariants("state sreet")[0])
        assertTrue("w 8th st" in n.keyVariants("w 8 st"))
        assertTrue("br ave" in n.keyVariants("bracnh ave"))
        assertTrue("vly vw dr" in n.keyVariants("valely vw dr"))
        assertEquals(emptyList<String>(), n.keyVariants("sr 8"))
        assertEquals(emptyList<String>(), n.keyVariants("main st"))
        assertTrue("sr 8" in n.highwayAlternatives("rte 8"))
        assertEquals("main", n.streetCore("n main st"))
        assertEquals("ritner", n.streetCore("w ritner st"))
    }

    @Test
    fun houseNumbersAndPlaces() {
        assertEquals("123" to BigInteger.valueOf(123), n.houseNumber("123")!!.let { it.text to it.int })
        assertEquals("123a", n.houseNumber("123 A")!!.text)
        assertEquals("123-125", n.houseNumber("123-125")!!.text)
        assertEquals("123 1/2", n.houseNumber("123 1/2")!!.text)
        assertEquals("123", n.houseNumber("0123")!!.text)
        assertNull(n.houseNumber("abc"))
        assertEquals(BigInteger("99999999999999999999"), n.houseNumber("99999999999999999999")!!.int)
        assertEquals("millcreek", n.placeKey("Millcreek Township"))
        assertEquals("erie", n.placeKey("City of Erie"))
        assertEquals("mount lebanon", n.placeKey("Mt. Lebanon"))
        assertEquals("spring city", n.placeKey("Spring City"))
        assertEquals(1, AddressNormalizer.osaDistance("main", "mian", 2))
        assertEquals(1, AddressNormalizer.osaDistance("main", "maine", 2))
        assertEquals(2, AddressNormalizer.osaDistance("main", "elm", 1))
    }

    private fun check(text: String, vararg want: Pair<String, String?>) {
        val got: ParsedAddress = parser.parse(text, places)
        val m = mapOf("house_number" to got.houseNumber, "street" to got.street, "street_key" to got.streetKey,
            "unit" to got.unit, "city" to got.city, "city_key" to got.cityKey, "state" to got.state, "zip" to got.zip,
            "zip4" to got.zip4, "zip_invalid" to got.zipInvalid)
        for ((k, v) in want) assertEquals(v, m[k], "'$text': $k")
    }

    @Test
    fun parserCases() {
        check("500 Walnut St Apt #4B Erie PA", "street_key" to "walnut st", "unit" to "apt 4b", "city_key" to "erie")
        check("500 Walnut Street Unit A-1, Erie", "street_key" to "walnut st", "unit" to "unit a-1", "city_key" to "erie")
        check("500 Walnut St, 2nd Floor, Erie", "street_key" to "walnut st", "unit" to "fl 2", "city_key" to "erie")
        check("500 Walnut St Rear Erie PA", "street_key" to "walnut st", "unit" to "rear", "city_key" to "erie")
        check("626 State St Fl 3 Rm 4 Erie", "street_key" to "state st", "unit" to "fl 3 rm 4", "city_key" to "erie")
        check("123 Main St Lower Burrell PA", "street_key" to "main st", "unit" to null, "city_key" to "lower burrell")
        check("100 Hill Side Rd", "street_key" to "hl side rd", "unit" to null)
        check("5040 Trail Side Court 15644", "street_key" to "trl side ct", "unit" to null, "zip" to "15644")
        check("State St 626 Erie", "house_number" to "626", "street_key" to "state st", "city_key" to "erie")
        check("Route 8 4500 Gibsonia", "house_number" to "4500", "street_key" to "rte 8", "city_key" to "gibsonia")
        check("Erie PA 16501", "street" to null, "city_key" to "erie", "zip" to "16501")
        check("16501", "zip" to "16501", "house_number" to null)
        check("626 State St Erie PA USA", "street_key" to "state st", "city_key" to "erie", "state" to "pa")
        check("626 State St, Erie, PA 16501, United States", "city_key" to "erie", "zip" to "16501")
        check("123 main st erie pa 1650", "street_key" to "main st", "city_key" to "erie", "zip" to null, "zip_invalid" to "1650")
        check("123 A Main St", "house_number" to "123a", "street_key" to "main st")
        check("123 A St Erie", "house_number" to "123", "street_key" to "a st", "city_key" to "erie")
        check("123 N Main St", "house_number" to "123", "street_key" to "n main st")
        check("123 n main st apt 4, erie pa 16501", "house_number" to "123", "street_key" to "n main st",
            "unit" to "apt 4", "city_key" to "erie", "state" to "pa", "zip" to "16501")
        check("erie pa 16501, 123 main st", "house_number" to "123", "street_key" to "main st", "city_key" to "erie")
        check("Erie, PA 16501 123 Main St", "house_number" to "123", "street_key" to "main st", "city_key" to "erie")
        check("123 Main St Spring City PA", "street_key" to "main st", "city_key" to "spring city")
        check("4500 Route 8 Gibsonia PA 15044", "house_number" to "4500", "street_key" to "rte 8", "city_key" to "gibsonia")
        check("10450 US-19 Wexford, PA", "street_key" to "us 19", "city_key" to "wexford")
        check("1600 W 26th St #4 Erie PA 16508-1234", "unit" to "# 4", "zip" to "16508", "zip4" to "1234")
        check("123 Main St Rear, Erie", "unit" to "rear", "city_key" to "erie")
        check("123 1/2 Elm St", "house_number" to "123 1/2", "street_key" to "elm st")
        check("main st 123", "house_number" to "123", "street_key" to "main st")
        check("123 St Clair Ave Erie", "street_key" to "saint clair ave", "city_key" to "erie")
        check("12345 Main St", "house_number" to "12345", "zip" to null)
        check("100 Park Ridge Rd State College PA", "street_key" to "park rdg rd", "city_key" to "state college")
    }

    @Test
    fun geometry() {
        val line = listOf(doubleArrayOf(-80.0, 42.0), doubleArrayOf(-80.0, 42.01))
        val (_, lat) = AddressIndex.interpolate(line, 0.5, null, 0.0)
        assertEquals(42.005, lat, 1e-6)
        val (lonL, _) = AddressIndex.interpolate(line, 0.5, "L", 10.0)
        assertTrue(lonL < -80.0)   // heading north, left is west
        assertEquals(0.0, Py.sum(doubleArrayOf()))
        assertEquals(1.0, Py.sum(doubleArrayOf(1e100, 1.0, -1e100)))   // Python 3.12: sum([1e100, 1.0, -1e100]) == 1.0
    }
}
