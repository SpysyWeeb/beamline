package io.github.spysyweeb.beamline.app.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/** 0.1.41, 53c (docs/online-design.md 4.5 "Still right?"): the confirmation item and the year rule. Made-up places only. */
class HoursCheckTest {
    private val day = 86_400_000L
    private val now = 1_795_878_000_000L   // 2026-11-28 15:00 UTC
    private val zone = ZoneId.of("America/New_York")
    private val t = HoursTarget("n77", "Test Cafe", 42.1, -80.1, "Mo-Fr 08:00-18:00", packOsm = "n7700", icon = "cafe", nodeLat = 42.1, nodeLon = -80.1)

    /** OP test 23. */
    @Test
    fun theDateIsThePhonesDay() {
        assertEquals("2026-11-28", HoursCheck.date(Instant.parse("2026-11-29T03:30:00Z").toEpochMilli(), zone))
        assertEquals("2026-11-29", HoursCheck.date(Instant.parse("2026-11-29T03:30:00Z").toEpochMilli(), ZoneId.of("UTC")))
        val i = HoursCheck.item(t, "c1", now, zone, atPlace = true)
        val e = i.edit!!
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-18:00"), e.base)
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-18:00", "check_date:opening_hours" to "2026-11-28"), e.changes)
        assertEquals("opening_hours", e.confirm)
        assertNull(e.baseVersion)
        assertEquals(42.1, e.nodeLat)
        assertEquals("n7700", e.packOsm)
        assertEquals("cafe", e.icon)
        assertTrue(i.atPlace)
        assertEquals(OutboxState.WAITING, i.state)
        assertEquals(listOf("Hours checked: Mon–Fri 8 AM–6 PM; Sat–Sun closed"), HoursCheck.lines("Mo-Fr 08:00-18:00"))
        assertEquals("Hours checked: Mon–Fri 8 AM–6 PM; Sat–Sun closed", i.text)
        assertEquals("Test Cafe", i.placeName)
        // the fixer's review: the window's title names it locally only when the map has no name; its comment stays "a place"
        assertEquals("Test Cafe", HoursCheck.item(t.copy(label = "Work"), "c2", now, zone, atPlace = false).placeName)
        val unnamed = HoursCheck.item(t.copy(name = null, label = "ATM"), "c3", now, zone, atPlace = false)
        assertEquals("ATM", unnamed.placeName)
        assertEquals("Confirmed the opening hours of a place", unnamed.edit!!.comment)
        assertNull(HoursCheck.item(t.copy(name = null), "c4", now, zone, atPlace = false).placeName, "neither known")
        assertEquals("Confirmed the opening hours of Test Cafe", HoursCheck.comment("Test Cafe"))
        assertEquals("Confirmed the opening hours of a place", HoursCheck.comment(null))
        assertEquals("HoursTarget", t.toString(), "prints none of it (PV2)")
        assertEquals(listOf("Hours checked: Mo-Fr 08:00-18:00; PH off"), HoursCheck.lines("Mo-Fr 08:00-18:00; PH off"), "a text the form cannot express")
    }

    private fun confirmation(ageDays: Long, state: OutboxState = OutboxState.SENT, undone: Boolean = false, osm: String = "n77", packOsm: String? = null) =
        HoursCheck.item(t.copy(osm = osm, packOsm = packOsm), "c$ageDays$state", now - ageDays * day, zone, atPlace = false).let { i ->
            i.copy(state = state, edit = i.edit!!.copy(undone = undone))
        }

    /** OP test 24 (+ AP-8: a place the user added counts by its hours). */
    @Test
    fun hoursAreAskedAgainAfterAYear() {
        val h = "Mo-Fr 08:00-18:00"
        assertTrue(HoursCheck.due(h, emptyList(), "n77", null, now), "never checked")
        assertFalse(HoursCheck.due(h, listOf(confirmation(364)), "n77", null, now), "364 days")
        assertTrue(HoursCheck.due(h, listOf(confirmation(365)), "n77", null, now), "365 days")
        assertFalse(HoursCheck.due(null, emptyList(), "n77", null, now), "no hours: nothing to confirm")
        assertFalse(HoursCheck.due(" ", emptyList(), "n77", null, now))
        // the user's own hours edit waiting, 10 days old
        val edit = OutboxItem("e1", 42.1, -80.1, "t", "Test Cafe", now - 10 * day,
            edit = EditData("n77", 3, mapOf("opening_hours" to "Mo-Fr 08:00-17:00"), mapOf("opening_hours" to h), "Updated hours"))
        assertFalse(HoursCheck.due(h, listOf(edit), "n77", null, now))
        assertTrue(HoursCheck.due(h, listOf(edit.copy(edit = edit.edit!!.copy(changes = mapOf("opening_hours" to null)))), "n77", null, now), "a removal is no check")
        assertTrue(HoursCheck.due(h, listOf(edit.copy(edit = edit.edit!!.copy(changes = mapOf("phone" to "1")))), "n77", null, now), "another detail")
        // in review, refused, undone or an undo: not the user's check
        assertTrue(HoursCheck.due(h, listOf(confirmation(30, OutboxState.REVIEW)), "n77", null, now))
        assertTrue(HoursCheck.due(h, listOf(confirmation(30, OutboxState.REFUSED)), "n77", null, now))
        assertTrue(HoursCheck.due(h, listOf(confirmation(30, undone = true)), "n77", null, now))
        val undo = confirmation(30).let { it.copy(edit = it.edit!!.copy(undoOf = "x")) }
        assertTrue(HoursCheck.due(h, listOf(undo), "n77", null, now))
        // matched by the pack's object when the object differs (a test object of a test build)
        assertFalse(HoursCheck.due(h, listOf(confirmation(30, osm = "n999", packOsm = "n7700")), "n77", "n7700", now))
        assertTrue(HoursCheck.due(h, listOf(confirmation(30, osm = "n999", packOsm = "n7700")), "n77", null, now))
        assertEquals(now - 30 * day, HoursCheck.lastCheckedMs(listOf(confirmation(30), confirmation(200)), "n77"))
        // AP-8: the user added this place with hours (sent: its node is the object)
        val added = OutboxItem("a1", 42.1, -80.1, "t", "Test Cafe", now - 20 * day, OutboxState.SENT, sentMs = now - 20 * day,
            add = AddData("cafe", mapOf("amenity" to "cafe", "opening_hours" to h), "c", node = 77, changeset = 5))
        assertFalse(HoursCheck.due(h, listOf(added), "n77", null, now))
        assertTrue(HoursCheck.due(h, listOf(added.copy(state = OutboxState.WAITING, add = added.add!!.copy(node = null))), "n77", null, now), "waiting: no object yet")
        assertTrue(HoursCheck.due(h, listOf(added.copy(add = added.add!!.copy(tags = mapOf("amenity" to "cafe")))), "n77", null, now), "added without hours")
    }
}
