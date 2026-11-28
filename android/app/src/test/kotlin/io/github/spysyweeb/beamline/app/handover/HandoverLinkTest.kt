package io.github.spysyweeb.beamline.app.handover

import io.github.spysyweeb.beamline.api.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.Random

/**
 * Item 52 (rule H2, design/handover.md 2.5 and K11, K13, K17): the links other apps hand over, read into a point,
 * words to look up, or nothing usable. Made-up points near Erie, PA (the PV3 canary points among them), made-up
 * names ("Quokka Den", "Example Street") and public places (Erie Zoo, Presque Isle State Park).
 */
class HandoverLinkTest {

    private fun P(lat: Double, lon: Double, label: String? = null): Handover = Handover.Point(GeoPoint(lat, lon), label)
    private fun T(text: String, near: GeoPoint? = null): Handover = Handover.Text(text, near)
    private val U: Handover = Handover.Unreadable

    /** The test's own words for an answer (Handover's toString prints nothing, PV2). */
    private fun said(h: Handover): String = when (h) {
        is Handover.Point -> "P(${h.point.lat}, ${h.point.lon}, ${h.label})"
        is Handover.Text -> "T(${h.text}, ${h.near?.let { "${it.lat}, ${it.lon}" }})"
        Handover.Unreadable -> "U"
    }

    private val rows: List<Pair<String, Handover>> = listOf(
        // 2.5, rows 1-63
        "geo:42.129,-80.085" to P(42.129, -80.085),
        "geo:42.129,-80.085?z=17" to P(42.129, -80.085),
        "geo:42.129,-80.085,190" to P(42.129, -80.085),
        "geo:42.129,-80.085;u=35;crs=WGS84" to P(42.129, -80.085),
        "geo:42.129,-80.085;crs=moon-2011" to U,
        "geo:0,0?q=42.13646,-80.08718(Quokka+Den)" to P(42.13646, -80.08718, "Quokka Den"),
        "geo:0,0?q=42.13646%2C-80.08718%20(Quokka%20Den%20%26%20Grill)" to P(42.13646, -80.08718, "Quokka Den & Grill"),
        "geo:0,0?q=42.13646,-80.08718" to P(42.13646, -80.08718),
        "geo:41.5,-81.0?q=42.13646,-80.08718(Quokka+Den)" to P(42.13646, -80.08718, "Quokka Den"),
        "geo:0,0?q=Erie+Zoo" to T("Erie Zoo"),
        "geo:0,0?q=1600+Example+Street%2C+Erie%2C+PA+16501" to T("1600 Example Street, Erie, PA 16501"),
        "geo:42.129,-80.085?q=coffee" to T("coffee", GeoPoint(42.129, -80.085)),
        "geo:42.129,-80.085?q=" to P(42.129, -80.085),
        "geo:0,0" to U,
        "geo:0,0?q=" to U,
        "geo:0,0?q=+++" to U,
        "geo:91,-80.085" to U,
        "geo:42.129,-181" to U,
        "geo:NaN,NaN" to U,
        "geo:1e2,5" to U,
        "geo:Infinity,0" to U,
        "geo:42.129" to U,
        "geo:42.129,-80.085,abc" to U,
        "geo:0,0?q=999,999(Bad)" to U,
        "geo:41.5,-81.0?q=0,0" to P(41.5, -81.0),
        "GEO:42.129,-80.085" to P(42.129, -80.085),
        "geo:?q=Erie+Zoo" to T("Erie Zoo"),
        "geo://42.129,-80.085" to P(42.129, -80.085),
        "geo:0,0?q=Erie%ZZZoo%" to T("Erie%ZZZoo%"),
        "geo:0,0?q=" + "a".repeat(500) to T("a".repeat(200)),
        "geo:0,0?q=Line%0AOne%09%09Two%E2%80%A8Three" to T("Line One Two Three"),
        "geo:0,0?q=42.129,-80.085(%20%20)" to P(42.129, -80.085),
        "geo:0,0?q=loc:42.129,-80.085" to P(42.129, -80.085),
        "geo:0,0?q=%F0%9F%8D%95+Quokka" to T("🍕 Quokka"),
        "geo:0,0?q=%C3%28" to T("�("),
        "geo:0,0?q=NaN,NaN" to T("NaN,NaN"),
        "google.navigation:q=42.129,-80.085" to P(42.129, -80.085),
        "google.navigation:q=Erie+Zoo&mode=w" to T("Erie Zoo"),
        "google.navigation:ll=42.129,-80.085" to P(42.129, -80.085),
        "google.navigation:ll=42.129,-80.085&q=Quokka+Den" to P(42.129, -80.085, "Quokka Den"),
        "google.navigation:ll=42.129,-80.085&q=42.1,-80.1" to P(42.129, -80.085),
        "google.navigation:///?q=Erie+Zoo" to T("Erie Zoo"),
        "google.navigation://?q=Erie+Zoo" to T("Erie Zoo"),
        "google.navigation:mode=d" to U,
        "google.navigation:" to U,
        "google.navigation:q=42.13646,-80.08718(Quokka+Den)" to P(42.13646, -80.08718, "Quokka Den"),
        "google.navigation:ll=0,0&q=Erie+Zoo" to T("Erie Zoo"),
        "https://www.google.com/maps/dir/?api=1&destination=42.129%2C-80.085" to P(42.129, -80.085),
        "https://www.google.com/maps/dir/?api=1&origin=Erie&destination=Presque+Isle+State+Park&travelmode=driving&waypoints=Erie+Zoo" to
            T("Presque Isle State Park"),
        "https://www.google.com/maps/search/?api=1&query=Erie+Zoo" to T("Erie Zoo"),
        "https://maps.google.com/maps?q=42.129,-80.085" to P(42.129, -80.085),
        "https://maps.google.com/?q=Erie+Zoo" to T("Erie Zoo"),
        "https://maps.google.com?q=Erie+Zoo" to T("Erie Zoo"),
        "http://maps.google.com/maps?saddr=My+Location&daddr=State+Street,+Erie,+PA" to T("State Street, Erie, PA"),
        "https://maps.google.com/maps?daddr=Erie+Zoo+to:Presque+Isle+State+Park" to T("Presque Isle State Park"),
        "https://www.google.com/maps/place/Erie+Maritime+Museum/@42.1360,-80.0870,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d42.13646!4d-80.08718!16s" to
            P(42.13646, -80.08718, "Erie Maritime Museum"),
        "https://www.google.com/maps/place/Erie+Maritime+Museum/@42.13646,-80.08718,17z" to P(42.13646, -80.08718, "Erie Maritime Museum"),
        "https://www.google.com/maps/place/Erie+Zoo" to T("Erie Zoo"),
        "https://google.com/maps/@42.129,-80.085,15z" to P(42.129, -80.085),
        "https://www.google.com/maps/dir/Erie/Presque+Isle+State+Park/@42.1,-80.1,12z/data=!4m2" to T("Presque Isle State Park"),
        "https://www.google.com/maps?ll=42.129,-80.085" to P(42.129, -80.085),
        "https://www.google.com/search?q=pizza" to U,
        "https://www.google.com/mapsx?q=1" to U,
        "https://www.google.com/maps/embed?pb=1" to U,
        "https://www.google.com/maps/d/viewer?mid=1" to U,
        "https://maps.app.goo.gl/AbCdE" to U,
        "https://goo.gl/maps/AbCdE" to U,
        "https://maps.example.com/maps?q=42.1,-80.1" to U,
        "https://user@MAPS.GOOGLE.COM.:443/?q=Erie+Zoo" to T("Erie Zoo"),
        "https://www.google.com/maps/@91,0,15z" to U,
        "https://www.google.com/maps/place/0,0/@0,0,3z" to U,
        "https:maps.google.com/?q=Erie+Zoo" to U,
        "https://www.openstreetmap.org/?mlat=42.129&mlon=-80.085#map=17/42.129/-80.085" to P(42.129, -80.085),
        "https://www.openstreetmap.org/#map=17/42.13646/-80.08718" to P(42.13646, -80.08718),
        "https://openstreetmap.org/?mlat=42.129&mlon=-80.085" to P(42.129, -80.085),
        "https://www.openstreetmap.org/node/123" to U,
        "https://www.openstreetmap.org/#map=17/abc/def" to U,
        "https://www.openstreetmap.org/?mlat=91&mlon=0" to U,
        "mailto:someone@example.com" to U,
        "io.github.spysyweeb.beamline://oauth?code=1&state=2" to U,
        "intent://x#Intent;end" to U,
        "" to U,
        "   " to U,
        "no-scheme" to U,
        "geo:0,0?q=" + "a".repeat(8990) to U,
        // K11: a coordinate prefix decides (rows 64-68)
        "geo:0,0?q=42.13646,-80.08718(Quokka Den & Grill)" to P(42.13646, -80.08718, "Quokka Den & Grill"),
        "geo:0,0?q=42.13646,-80.08718(Store #12)" to P(42.13646, -80.08718, "Store #12"),
        "geo:0,0?q=42.13646,-80.08718(" + "a".repeat(300) + ")" to P(42.13646, -80.08718, "a".repeat(200)),
        "geo:0,0?q=42.13646,-80.08718+Quokka+Den" to P(42.13646, -80.08718, "Quokka Den"),
        "https://www.google.com/maps/dir//'42.129,-80.085'/" to P(42.129, -80.085),
        // K11, the edges of the prefix: words after whole numbers stay words, a closed label with an & and more parameters after it
        "geo:0,0?q=12,5+Quokka" to T("12,5 Quokka"),
        "geo:0,0?q=42.13646,-80.08718(Quokka & Grill)&z=17" to P(42.13646, -80.08718, "Quokka & Grill"),
        "google.navigation:q=42.13646,-80.08718(Quokka Den & Grill)&mode=d" to P(42.13646, -80.08718, "Quokka Den & Grill"),
        "geo:0,0?q=42.13646,-80.08718(Quokka) Den" to P(42.13646, -80.08718, "Quokka"),
        "geo:0,0?q=999.5,-80.08718(Quokka & Grill)" to U,
        // K13: a percent-encoded comma, a fully encoded link (rows 69-70)
        "geo:42.129%2C-80.085" to P(42.129, -80.085),
        "geo:42.129%2C-80.085%3Fq%3DErie%20Zoo" to T("Erie Zoo", GeoPoint(42.129, -80.085)),
        // K17: format characters are dropped (rows 71-72)
        "geo:0,0?q=42.13646,-80.08718(%E2%80%AEQuokka+Den)" to P(42.13646, -80.08718, "Quokka Den"),
        "geo:0,0?q=Erie%E2%80%8B+Zoo" to T("Erie Zoo"),
        "geo:0,0?q=%EF%BB%BFErie%E2%81%A0+Zoo%E2%80%8D" to T("Erie Zoo‍"),
    )

    @TestFactory
    fun everyFormOfLink(): List<DynamicTest> = rows.mapIndexed { i, (link, want) ->
        DynamicTest.dynamicTest("row ${i + 1}") {
            assertEquals(said(want), said(HandoverLink.parse(link)), "row ${i + 1}: ${link.take(120)}")
        }
    }

    /** PV2: an answer prints none of the link. */
    @Test
    fun nothingOfALinkIsPrinted() {
        assertEquals("Handover.Point", Handover.Point(GeoPoint(42.13646, -80.08718), "Quokka Den").toString())
        assertEquals("Handover.Text", Handover.Text("Quokka Den", null).toString())
        assertEquals("Handover.Text", Handover.Text("Quokka Den", GeoPoint(42.13646, -80.08718)).toString())
        assertEquals("Unreadable", Handover.Unreadable.toString())
    }

    /** 2,000 links of the characters the forms use, lengths 0 to 300: every one is read without an exception. */
    @Test
    fun garbageNeverThrows() {
        val chars = "geo:google.navigation:https://maps.google.com/?&=;,.()%+-0123456789aZ!@#"
        val r = Random(52)
        repeat(2000) {
            val n = r.nextInt(301)
            val s = buildString { repeat(n) { append(chars[r.nextInt(chars.length)]) } }
            HandoverLink.parse(s)
            HandoverLink.parse("geo:$s")
            HandoverLink.parse("https://www.google.com/maps/$s")
        }
    }

    @Test
    fun theLimitsAreTheDesignsOwn() {
        assertEquals(8192, HandoverLink.MAX_LINK_CHARS)
        assertEquals(200, HandoverLink.MAX_TEXT_CHARS)
    }
}
