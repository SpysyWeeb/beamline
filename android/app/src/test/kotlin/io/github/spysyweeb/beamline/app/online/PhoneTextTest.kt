package io.github.spysyweeb.beamline.app.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A phone number is written the way OpenStreetMap asks for it (docs/next-round.md item 38). */
class PhoneTextTest {
    @Test
    fun aNumberTypedTheAmericanWayGetsItsCountryCode() {
        for (typed in listOf("(814) 555-0123", "814-555-0123", "814.555.0123", "8145550123", "814 555 0123", "1 814 555 0123", "1-814-555-0123",
            "+1 814 555 0123", "+1 (814) 555-0123", "+18145550123", "  814-555-0123 ")) {
            assertEquals("+1-814-555-0123", Checks.phoneText(typed), typed)
            assertNull(Checks.phone(typed), "the form's check takes it: $typed")
        }
        assertEquals("+1-814-555-0123", Checks.phoneText("+1-814-555-0123"), "already in that form")
    }

    @Test
    fun whatIsNotANorthAmericanNumberStaysAsTyped() {
        assertEquals("+44 20 7946 0958", Checks.phoneText(" +44 20 7946 0958 "), "another country's number")
        assertEquals("+49 30 1234567", Checks.phoneText("+49 30 1234567"), "ten digits after a +: the + says the country code is there")
        assertEquals("555-0123", Checks.phoneText("555-0123"), "no area code: not guessed")
        assertEquals("014 555 0123", Checks.phoneText("014 555 0123"), "an area code never begins with 0")
        assertEquals("814 155 0123", Checks.phoneText("814 155 0123"), "an exchange never begins with 1")
        assertEquals("2 814 555 0123", Checks.phoneText("2 814 555 0123"), "eleven digits that do not begin with 1")
        assertEquals("", Checks.phoneText("  "))
        assertEquals("\u0668\u0661\u0664 555 0123", Checks.phoneText("\u0668\u0661\u0664 555 0123"), "digits of another script are not read as 0 to 9")
    }

    @Test
    fun severalNumbersStaySeveral() {
        assertEquals("+1-814-555-0123;+1-814-555-0199", Checks.phoneText("(814) 555-0123; 814-555-0199"))
        assertEquals("+1-814-555-0123;+44 20 7946 0958", Checks.phoneText("8145550123;+44 20 7946 0958"))
        assertEquals("+1-814-555-0123", Checks.phoneText("814-555-0123;"))
    }

    @Test
    fun theFormWritesWhatWasTypedInThatFormAndLeavesAnUntouchedNumberAlone() {
        // the place has a number in the national form: opening the form changes nothing
        val base = mapOf("name" to "Cafe", "amenity" to "cafe", "phone" to "(814) 555-0100")
        val s = EditFields.of(base)
        val f = FormFields(FormValues(s.name, s.hours, s.phone, s.website))
        fun changes() = PlaceEdits.changes(base, EditFields(f.name, f.hoursText(), f.phoneText(), f.websiteText(), null, null), closed = false)
        assertFalse(f.anyChanged)
        assertEquals("(814) 555-0100", f.phoneText(), "not asked, not changed")
        assertNull(f.phoneNote)
        // a number the place has with a space at its end: still nothing to say
        assertNull(FormFields(FormValues("Cafe", null, "(814) 555-0100 ", null)).phoneNote)
        f.name = "Cafe Two"
        assertEquals(setOf("name"), changes().keys, "another field's change does not rewrite the phone")
        // a new number, typed plainly
        f.phone = "8145550199"
        assertTrue(f.phoneChanged)
        assertEquals("+1-814-555-0199", f.phoneText())
        assertEquals("Saved as +1-814-555-0199", f.phoneNote)
        assertEquals("+1-814-555-0199", changes()["phone"])
        // typed in OpenStreetMap's form already: nothing to say; a number with a problem: the problem, not the note
        f.phone = "+1-814-555-0199"
        assertNull(f.phoneNote)
        f.phone = "call us"
        assertNull(f.phoneNote)
        // undone: as it was
        f.undoPhone()
        assertEquals("(814) 555-0100", f.phoneText())
        // a place that is being added: the number typed is formatted
        val fresh = FormFields()
        fresh.startFresh(true)
        fresh.phone = "(814) 555-0123"
        assertEquals("+1-814-555-0123", fresh.phoneText())
        assertEquals("Saved as +1-814-555-0123", fresh.phoneNote)
    }
}
