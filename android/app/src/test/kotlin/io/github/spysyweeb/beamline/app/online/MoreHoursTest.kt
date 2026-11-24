package io.github.spysyweeb.beamline.app.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A second set of hours of one place (docs/next-round.md item 33): the office, the drive-through, the kitchen. */
class MoreHoursTest {
    private val storage = mapOf("shop" to "storage_rental", "name" to "Corner Storage", "opening_hours" to "Mo-Su 06:00-22:00")
    private val office = "Mo-Fr 09:30-18:00; Sa 09:00-17:30"

    private fun form(tags: Map<String, String>): FormFields {
        val s = EditFields.of(tags)
        return FormFields(FormValues(s.name, s.hours, s.phone, s.website, moreKey = s.moreKey, moreHours = s.moreHours))
    }

    private fun changes(base: Map<String, String>, f: FormFields) =
        PlaceEdits.changes(base, EditFields(f.name, f.hoursText(), f.phone, f.websiteText(), f.moreKey ?: f.start.moreKey, f.moreHoursText()), closed = false)

    @Test
    fun officeHoursAreAddedBesideThePlacesOwn() {
        val f = form(storage)
        assertNull(f.more)
        assertFalse(f.anyChanged)
        f.addMore(MoreHours.OFFICE)
        f.hours2 = HoursText.parse("Mon - Fri 9:30 am - 6:00 pm\nSat 9:00 am - 5:30 pm\nSun Closed")!!.draft
        assertTrue(f.moreChanged)
        val c = changes(storage, f)
        assertEquals(mapOf("opening_hours:office" to office), c, "the place's own hours are not touched")
        assertEquals(listOf("Office hours: (none) → Mon–Fri 9:30 AM–6 PM; Sat 9 AM–5:30 PM; Sun closed"), PlaceEdits.summary(storage, c))
        assertEquals("Updated office hours", PlaceEdits.comment(c))
        // added and taken back: nothing to upload
        f.removeMore()
        assertNull(f.more)
        assertEquals(emptyMap<String, String?>(), changes(storage, f))
    }

    @Test
    fun aSecondSetOpenStreetMapHasIsShownChangedAndRemoved() {
        val base = storage + ("opening_hours:office" to office)
        val f = form(base)
        assertEquals(MoreHours.OFFICE, f.more)
        assertEquals(office, f.hours2.text())
        assertFalse(f.anyChanged)
        assertEquals(emptyMap<String, String?>(), changes(base, f))
        // its kind stays what OSM says: another kind cannot be put in its place
        f.addMore(MoreHours.KITCHEN)
        assertEquals(MoreHours.OFFICE, f.more)
        // changed
        f.hours2 = HoursDraft.of("Mo-Fr 09:00-17:00")!!
        assertEquals(mapOf<String, String?>("opening_hours:office" to "Mo-Fr 09:00-17:00"), changes(base, f))
        f.undoMore()
        assertFalse(f.moreChanged)
        // removed: the tag goes
        f.removeMore()
        assertEquals(MoreHours.OFFICE, f.more)
        assertEquals(mapOf<String, String?>("opening_hours:office" to null), changes(base, f))
        assertEquals(listOf("Office hours: Mon–Fri 9:30 AM–6 PM; Sat 9 AM–5:30 PM; Sun closed → (none)"), PlaceEdits.summary(base, changes(base, f)))
    }

    @Test
    fun aSecondSetTheEditorCannotShowCanStillBeRemoved() {
        // seasonal office hours: shown as text, kept as they are, and "Remove these hours" removes the tag (the review of 2026-11-21)
        val seasonal = "May-Sep: Mo-Fr 09:00-17:00"
        val base = storage + ("opening_hours:office" to seasonal)
        val f = form(base)
        assertEquals(MoreHours.OFFICE, f.more)
        assertEquals(seasonal, f.hours2ReadOnly)
        assertFalse(f.anyChanged)
        assertEquals(emptyMap<String, String?>(), changes(base, f), "left alone, it stays as it is")
        f.removeMore()
        assertNull(f.hours2ReadOnly)
        assertTrue(f.moreChanged)
        assertEquals(mapOf<String, String?>("opening_hours:office" to null), changes(base, f))
        f.undoMore()
        assertEquals(seasonal, f.hours2ReadOnly)
        assertFalse(f.moreChanged)
        // an outbox item that removed it, opened again: still removed
        val again = form(base)
        again.keepMore = false
        again.setValues(FormValues("Corner Storage", "Mo-Su 06:00-22:00", null, null))
        again.keepMore = true
        assertEquals(mapOf<String, String?>("opening_hours:office" to null), changes(base, again))
    }

    @Test
    fun aClosedPlaceKeepsNoSecondSetOfHours() {
        val base = storage + ("opening_hours:office" to office)
        val c = PlaceEdits.closedChanges(base)
        assertTrue("opening_hours:office" in c && c["opening_hours:office"] == null)
        assertEquals("storage_rental", c["disused:shop"])
    }

    @Test
    fun theKindsAndTheirKeys() {
        assertEquals(MoreHours.DRIVE_THROUGH, MoreHours.of(mapOf("amenity" to "pharmacy", "opening_hours:drive_through" to "Mo-Fr 09:00-18:00")))
        assertEquals(MoreHours.KITCHEN, MoreHours.ofKey("opening_hours:kitchen"))
        assertNull(MoreHours.ofKey("opening_hours:signed"))
        assertNull(MoreHours.of(storage))
        assertEquals("Drive-through hours", PlaceEdits.label("opening_hours:drive_through"))
        // a pack knows no second set: the "changed since the map was made" notice compares without it
        assertEquals(EditFields.of(storage).core(), EditFields.of(storage + ("opening_hours:office" to office)).core())
        // a new place takes its second set with it
        val f = FormFields()
        f.name = "Corner Storage"
        f.addMore(MoreHours.OFFICE)
        f.hours2 = HoursDraft.of(office)!!
        assertEquals(office, EditSession.newTags(f)["opening_hours:office"])
        // an outbox item of a new place, opened again
        val again = FormFields()
        again.setValues(FormValues("Corner Storage", null, null, null, null, "opening_hours:office", office))
        assertEquals(MoreHours.OFFICE, again.more)
        assertEquals(office, again.hours2.text())
    }

    @Test
    fun aDifferentBusinessStartsWithoutTheOldOnesSecondSet() {
        val f = form(storage + ("opening_hours:office" to office))
        f.startFresh(true)
        assertNull(f.more)
        f.addMore(MoreHours.KITCHEN)
        assertEquals(MoreHours.KITCHEN, f.more)
        f.startFresh(false)
        assertEquals(MoreHours.OFFICE, f.more)
        assertEquals(office, f.hours2.text())
    }
}
