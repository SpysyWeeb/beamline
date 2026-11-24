package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.AnnounceStage
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.ReplaceReason
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripProblem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** EventCards: the latest StopReached, Arrived or RerouteFailed event for the card time (rule A2, guidance.json arrival.stop_card_s). */
class EventCardsTest {

    private val stop = TripEvent.StopReached(0, "Sheetz")
    private val arrived = TripEvent.Arrived("Downs Drive")
    private val failed = TripEvent.RerouteFailed(TripProblem(ProblemKind.NO_ROUTE, "No route found from here.", canRetry = true))

    @Test
    fun `a card shows for 8 s after its event, then goes`() {
        val cards = EventCards(8_000)
        assertNull(cards.shown(0))
        cards.onEvent(stop, nowMs = 50_000)
        assertEquals(stop, cards.shown(50_000))
        assertEquals(stop, cards.shown(57_999))
        assertNull(cards.shown(58_000))
        assertNull(cards.shown(120_000))
    }

    @Test
    fun `each of the three card events shows, the latest replaces the one before at once`() {
        val cards = EventCards(8_000)
        cards.onEvent(failed, 1_000)
        assertEquals(failed, cards.shown(1_000))
        cards.onEvent(stop, 3_000)
        assertEquals(stop, cards.shown(3_001))
        cards.onEvent(arrived, 5_000)
        assertEquals(arrived, cards.shown(12_999))
        assertNull(cards.shown(13_000))
    }

    @Test
    fun `other events make no card and leave the current one`() {
        val cards = EventCards(8_000)
        cards.onEvent(TripEvent.Announce(2, AnnounceStage.PREPARE, 804.672), 0)
        cards.onEvent(TripEvent.RouteReplaced(9, ReplaceReason.STOPS_CHANGED), 0)
        assertNull(cards.shown(1))
        cards.onEvent(stop, 10_000)
        cards.onEvent(TripEvent.RouteReplaced(10, ReplaceReason.REROUTE), 11_000)
        cards.onEvent(TripEvent.Announce(3, AnnounceStage.NOW, 40.0), 12_000)
        assertEquals(stop, cards.shown(12_000))
    }

    @Test
    fun `a trip that starts or ends drops the card`() {
        val cards = EventCards(8_000)
        cards.onEvent(stop, 0)
        cards.onEvent(TripEvent.GuidanceEnded, 1_000)
        assertNull(cards.shown(1_000))
        cards.onEvent(arrived, 2_000)
        cards.onEvent(TripEvent.GuidanceStarted(4, simulated = true), 3_000)
        assertNull(cards.shown(3_000))
    }

    @Test
    fun `two EventCards do not share their card`() {
        val a = EventCards(8_000)
        val b = EventCards(8_000)
        a.onEvent(stop, 0)
        assertEquals(stop, a.shown(1))
        assertNull(b.shown(1))
    }

    @Test
    fun `EventCards honours the card time it is given`() {
        val cards = EventCards(3_000)
        cards.onEvent(stop, 1_000)
        assertEquals(stop, cards.shown(3_999))
        assertNull(cards.shown(4_000))
        val long = EventCards(20_000)
        long.onEvent(arrived, 0)
        assertEquals(arrived, long.shown(19_999))
        assertNull(long.shown(20_000))
    }

    @Test
    fun `the timer honours any card time`() {
        val t = EventCardTimer(cardMs = 3_000)
        t.onEvent(arrived, 100)
        assertEquals(arrived, t.shown(3_099))
        assertNull(t.shown(3_100))
        val never = EventCardTimer(cardMs = 0)
        never.onEvent(stop, 0)
        assertNull(never.shown(0))
    }
}
