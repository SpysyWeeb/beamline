package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.feature.drive.Exit24.guiding
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 0.1.41 (wave 0, docs/online-design.md 4.8): the arrival card's one question; the app sets it, the presenter never does. */
class ArrivalQuestionContractTest {

    @Test
    fun `the presenter's arrival card asks nothing`() {
        val trip = guiding(banner = LiveBanner(Exit24.arriveView, 10.0, false), phase = Phase.ARRIVED)
        val arrived = present(trip).guidance!!.status as GuidanceStatusUi.Arrived
        assertNull(arrived.question)
        assertEquals(GuidanceStatusUi.Arrived(arrived.name, arrived.sideText, arrived.awayText, arrived.saved), arrived)
    }

    @Test
    fun `the question and its three answers`() {
        val q = ArrivalQuestionUi("Hours still right?", listOf("Mon–Fri 8 AM–6 PM"), yes = "Yes", edit = "Edit hours", notNow = "Not now")
        val card = GuidanceStatusUi.Arrived("Example Diner", null).copy(question = q)
        assertEquals(q, card.question)
        assertEquals(listOf(ArrivalAnswer.YES, ArrivalAnswer.EDIT, ArrivalAnswer.NOT_NOW), ArrivalAnswer.entries.toList())
        val e: DriveEvent = DriveEvent.AnswerArrival(ArrivalAnswer.NOT_NOW)
        assertEquals(ArrivalAnswer.NOT_NOW, (e as DriveEvent.AnswerArrival).answer)
    }
}
