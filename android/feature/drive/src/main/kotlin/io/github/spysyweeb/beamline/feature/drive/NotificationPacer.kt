package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.units.UnitFormatter

/**
 * Rule D6's pace for the guidance notification (for the integrator's GuidanceService): "updated on
 * maneuver change and at most every notification.min_interval_s". The text of
 * [DrivePresenter.notification] goes out at once when the maneuver it names changes: another
 * maneuver or another route, a maneuver appearing (the first banner, the new route after a
 * re-route) or going (re-routing starts, D7), and the arrival. Every other change (the distance, the
 * GPS state, a problem, the phase while no maneuver is named) waits until [minIntervalMs] have passed
 * since the last post, so a GPS state flapping every second does not post every second. The same text
 * is never posted twice. Thread-safe.
 *
 * A held text is not lost: [heldUntilMs] says when it may go; call [next] again then with the latest
 * trip (the service's next trip update usually comes first, about once a second while guiding, but
 * with GPS lost none may come).
 */
class NotificationPacer(private val minIntervalMs: Long) {
    private var lastManeuver: Any? = null
    private var lastText: GuidanceNotificationText? = null
    private var lastAtMs = 0L
    private var holding = false

    /**
     * The text to post now, or null: nothing to post (the same text, or too soon after the last one:
     * see [heldUntilMs]), or no notification at all ([trip] outside GUIDING, REROUTING and ARRIVED;
     * then the pacer starts over). [nowMs] is monotonic.
     */
    @Synchronized
    fun next(trip: TripState, units: UnitFormatter, nowMs: Long): GuidanceNotificationText? {
        val text = DrivePresenter.notification(trip, units)
        if (text == null) {
            lastManeuver = null
            lastText = null
            holding = false
            return null
        }
        val maneuver = shownManeuver(trip)
        val first = lastText == null
        if (text == lastText) {
            lastManeuver = maneuver
            holding = false
            return null
        }
        if (!first && maneuver == lastManeuver && nowMs - lastAtMs < minIntervalMs) {
            holding = true
            return null
        }
        lastManeuver = maneuver
        lastText = text
        lastAtMs = nowMs
        holding = false
        return text
    }

    /**
     * When a text that [next] held back may be posted (monotonic ms, the same clock as nowMs), or null
     * when nothing is held. The caller then calls [next] again with the latest trip.
     */
    @Synchronized
    fun heldUntilMs(): Long? = if (holding) lastAtMs + minIntervalMs else null

    private companion object {
        /**
         * The maneuver the notification names (D6): the banner's maneuver of the route while guided,
         * the arrival, or nothing (no banner yet, or re-routing: D7, the title says "Finding a new
         * route...").
         */
        fun shownManeuver(trip: TripState): Any? = when (trip.phase) {
            Phase.GUIDING -> trip.guidance?.banner?.let { banner -> Pair(trip.guidance?.route?.id, banner.view.maneuverIndex) }
            Phase.ARRIVED -> Phase.ARRIVED
            else -> null
        }
    }
}
