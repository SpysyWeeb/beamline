package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.TripEvent
import java.util.WeakHashMap

/**
 * The logic of [EventCards]: which one-shot card is on screen. The latest StopReached, Arrived or
 * RerouteFailed event is shown for [cardMs] after it came (`[at, at + cardMs)`, monotonic
 * milliseconds); a later one replaces it at once. GuidanceStarted and GuidanceEnded drop the card,
 * so a card of a trip that ended never shows over the next one. Thread-safe.
 */
class EventCardTimer(private val cardMs: Long) {
    private var event: TripEvent? = null
    private var atMs = 0L

    @Synchronized
    fun onEvent(event: TripEvent, nowMs: Long) {
        when (event) {
            is TripEvent.StopReached, is TripEvent.Arrived, is TripEvent.RerouteFailed -> {
                this.event = event
                atMs = nowMs
            }
            is TripEvent.GuidanceStarted, TripEvent.GuidanceEnded -> this.event = null
            is TripEvent.RouteReplaced, is TripEvent.Announce -> Unit
        }
    }

    @Synchronized
    fun shown(nowMs: Long): TripEvent? {
        val e = event ?: return null
        return if (nowMs - atMs < cardMs) e else null
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
