package io.github.spysyweeb.beamline.app.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** OP test 16 (0.1.41, with the plan's OP-7 texts and order): what My reports' rows and the bar say for each item. */
class OnlineTextsTest {
    private fun edit(state: OutboxState, changeset: Long? = 900, status: Int? = null, undoOf: String? = null, undone: Boolean = false,
                     kept: List<String> = emptyList(), confirm: String? = null) = OutboxItem(
        "i", 42.1, -80.1, "t", "Test Cafe", 1L, state, status = status, sentMs = if (state == OutboxState.SENT) 2L else null,
        edit = EditData("n42", 3, emptyMap(), mapOf("phone" to "1"), "c", changeset = changeset, undoOf = undoOf, undone = undone, kept = kept, confirm = confirm),
    )

    @Test
    fun theRowsSayWhatHappened() {
        // an edit
        assertEquals("Waiting to be sent", OnlineTexts.state(edit(OutboxState.WAITING), sending = false))
        assertEquals("Waiting: sign in to OpenStreetMap to send it", OnlineTexts.state(edit(OutboxState.WAITING), sending = false, signedIn = false))
        assertEquals("Uploaded · changeset 900", OnlineTexts.state(edit(OutboxState.SENT), sending = false))
        assertEquals("Uploaded · changeset 900 · undone", OnlineTexts.state(edit(OutboxState.SENT, undone = true), sending = false))
        assertEquals("Already on OpenStreetMap: nothing needed to change", OnlineTexts.state(edit(OutboxState.SENT, changeset = null), sending = false))
        assertEquals("Deleted from OpenStreetMap meanwhile: open it to delete your edit", OnlineTexts.state(edit(OutboxState.REVIEW, null, 410), sending = false))
        assertEquals("Deleted from OpenStreetMap meanwhile: open it to delete your edit", OnlineTexts.state(edit(OutboxState.REFUSED, null, 410), sending = false))
        assertEquals("Changed on OpenStreetMap meanwhile: open it to look again", OnlineTexts.state(edit(OutboxState.REVIEW, null, 409), sending = false))
        assertEquals("Not accepted by OpenStreetMap (400). Change it or delete it.", OnlineTexts.state(edit(OutboxState.REFUSED, null, 400), sending = false))
        assertEquals("Sending...", OnlineTexts.state(edit(OutboxState.WAITING), sending = true))
        // an undo
        val u = { s: OutboxState, cs: Long?, st: Int?, kept: List<String> -> edit(s, cs, st, undoOf = "e1", kept = kept) }
        assertEquals("Waiting to be sent", OnlineTexts.state(u(OutboxState.WAITING, null, null, emptyList()), sending = false))
        assertEquals("Undone · changeset 901", OnlineTexts.state(u(OutboxState.SENT, 901, null, emptyList()), sending = false))
        assertEquals("Undone · changeset 901 · left as changed since: Hours, Phone",
            OnlineTexts.state(u(OutboxState.SENT, 901, null, listOf("opening_hours", "phone", "contact:phone")), sending = false))
        assertEquals("Not undone: the place was deleted from OpenStreetMap", OnlineTexts.state(u(OutboxState.SENT, null, 410, emptyList()), sending = false))
        assertEquals("Not undone: someone changed it on OpenStreetMap since", OnlineTexts.state(u(OutboxState.SENT, null, null, listOf("phone")), sending = false))
        assertEquals("Nothing to undo: OpenStreetMap has the earlier details again", OnlineTexts.state(u(OutboxState.SENT, null, null, emptyList()), sending = false))
        assertEquals("Not accepted by OpenStreetMap (409). Delete it.", OnlineTexts.state(u(OutboxState.REFUSED, null, 409, emptyList()), sending = false))
        // the kind line
        assertEquals("Undo", OnlineTexts.kind(u(OutboxState.WAITING, null, null, emptyList())))
        assertEquals("Hours check", OnlineTexts.kind(edit(OutboxState.WAITING, confirm = "opening_hours")))
        assertEquals("Edit", OnlineTexts.kind(edit(OutboxState.WAITING)))
        // the bar after an undo, in OP-7's order
        assertEquals(OnlineTexts.UNDO_SIGN_IN, OnlineTexts.undoResult(u(OutboxState.WAITING, null, null, emptyList()), signedIn = false))
        assertEquals("The undo waits in My reports: sign in to OpenStreetMap to send it.", OnlineTexts.UNDO_SIGN_IN)
        assertEquals(OnlineTexts.UNDO_SIGN_IN, OnlineTexts.undoResult(null, signedIn = false))
        assertEquals(OnlineTexts.UNDO_WAITING, OnlineTexts.undoResult(u(OutboxState.WAITING, null, null, emptyList())))
        assertEquals(OnlineTexts.UNDO_WAITING, OnlineTexts.undoResult(null))
        assertEquals(OnlineTexts.UNDO_REFUSED, OnlineTexts.undoResult(u(OutboxState.REFUSED, null, 409, emptyList())))
        assertEquals(OnlineTexts.UNDO_GONE, OnlineTexts.undoResult(u(OutboxState.SENT, null, 410, emptyList())))
        assertEquals("Not undone: the place was deleted from OpenStreetMap.", OnlineTexts.UNDO_GONE)
        assertEquals(OnlineTexts.NOTHING_TO_UNDO, OnlineTexts.undoResult(u(OutboxState.SENT, null, null, emptyList())))
        assertEquals("Nothing to undo: OpenStreetMap has the earlier details again.", OnlineTexts.NOTHING_TO_UNDO)
        assertEquals(OnlineTexts.NOT_UNDONE, OnlineTexts.undoResult(u(OutboxState.SENT, null, null, listOf("phone"))))
        assertEquals(OnlineTexts.UNDONE_PART, OnlineTexts.undoResult(u(OutboxState.SENT, 901, null, listOf("phone"))))
        assertEquals(OnlineTexts.UNDONE, OnlineTexts.undoResult(u(OutboxState.SENT, 901, null, emptyList())))
        // the bar after a confirmation
        val c = { s: OutboxState, cs: Long? -> edit(s, cs, confirm = "opening_hours") }
        assertEquals(OnlineTexts.CONFIRM_WAITING, OnlineTexts.confirmResult(c(OutboxState.WAITING, null)))
        assertEquals(OnlineTexts.CONFIRM_REVIEW, OnlineTexts.confirmResult(c(OutboxState.REVIEW, null)))
        // the fixer's review: a confirmation also comes back for a move or a closing, with the hours unchanged: the bar names no hours
        assertEquals("This place changed on OpenStreetMap since your map was made: look again in My reports.", OnlineTexts.CONFIRM_REVIEW)
        assertEquals(OnlineTexts.CONFIRM_REFUSED, OnlineTexts.confirmResult(c(OutboxState.REFUSED, null)))
        assertEquals(OnlineTexts.CONFIRM_ALREADY, OnlineTexts.confirmResult(c(OutboxState.SENT, null)))
        assertEquals(OnlineTexts.CONFIRM_DONE, OnlineTexts.confirmResult(c(OutboxState.SENT, 900)))
        // the plan's words (4.o "Texts")
        assertEquals("No connection: the form shows the values of your map. Before your edit goes, Beamline reads the place again, " +
            "and if someone changed the same details asks you to look again.", OnlineTexts.EDIT_OFFLINE)
        assertEquals("This place was moved on OpenStreetMap. Check that it is still the place you mean, then upload again.", OnlineTexts.EDIT_MOVED)
        assertEquals("Edit hours", OnlineTexts.EDIT_HOURS)
        assertEquals("Confirmed the opening hours of a place", HoursCheck.comment(null))
        assertEquals("Hours checked", PlaceEdits.label("check_date:opening_hours"))
        assertEquals("Speed limit", PlaceEdits.label("maxspeed"))
        assertEquals("Undid my change to Test Cafe", OnlineTexts.undoComment("Test Cafe"))
        // the fixer's review: "made at the place" can also be a question at the place or an item made there (OP-6, AP-7),
        // so the survey notice does not say the phone was there in the last 10 minutes
        assertEquals("You made this edit at the place, so Beamline marks it as checked on the spot (source=survey). That tells " +
            "everyone who looks at the change that you were here at about that time. Your position itself is never sent.", OnlineTexts.SURVEY_TEXT)
        // an hours change taking its old check date is still "Updated hours" in public
        assertEquals("Updated hours", PlaceEdits.comment(mapOf("opening_hours" to "Mo-Fr 09:00-17:00", "check_date:opening_hours" to null)))
    }
}
