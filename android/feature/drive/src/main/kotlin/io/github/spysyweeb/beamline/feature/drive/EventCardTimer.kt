package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.TripEvent
import java.util.WeakHashMap

/**
 * The logic of [EventCards]: which one-shot card is on screen. The latest StopReached, Arrived or
 * RerouteFailed event is shown for [cardMs] after it came (`[at, at + cardMs)`, monotonic
 * milliseconds); a later one replaces it at once. A StopsReordered (play test P49: the stops editor's
 * "Stops reordered.") has its own slot of [cardMs]: shown when no card is, it never replaces one.
 * GuidanceStarted and GuidanceEnded drop both, so a card of a trip that ended never shows over the
 * next one. Thread-safe.
 */
class EventCardTimer(private val cardMs: Long) {
    private var event: TripEvent? = null
    private var atMs = 0L
    /** Play test P49: when Best order last changed the order (its own slot: it never replaces a card); null: none. */
    private var reorderedAtMs: Long? = null

    @Synchronized
    fun onEvent(event: TripEvent, nowMs: Long) {
        when (event) {
            is TripEvent.StopReached, is TripEvent.Arrived, is TripEvent.RerouteFailed -> {
                this.event = event
                atMs = nowMs
            }
            TripEvent.StopsReordered -> reorderedAtMs = nowMs
            is TripEvent.GuidanceStarted, TripEvent.GuidanceEnded -> {
                this.event = null
                reorderedAtMs = null
            }
            is TripEvent.RouteReplaced, is TripEvent.Announce -> Unit
        }
    }

    /** The card shown now, else StopsReordered while its own [cardMs] lasts, else null. */
    @Synchronized
    fun shown(nowMs: Long): TripEvent? {
        event?.let { e -> if (nowMs - atMs < cardMs) return e }
        val r = reorderedAtMs ?: return null
        return if (nowMs - r < cardMs) TripEvent.StopsReordered else null
    }
}

/**
 * One timer per EventCards instance. The contract class (DriveUi.kt, frozen except its function
 * bodies) has no field to hold the card, so it is kept here, by identity (EventCards does not
 * override equals); an EventCards that is no longer used is dropped with its timer.
 */
private val timers = WeakHashMap<EventCards, EventCardTimer>()

internal fun EventCards.timer(cardMs: Long): EventCardTimer =
    synchronized(timers) { timers.getOrPut(this) { EventCardTimer(cardMs) } }
