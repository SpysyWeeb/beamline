package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.TripProblem
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.feature.drive.Exit24.choosing
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame1
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame2
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame3
import io.github.spysyweeb.beamline.feature.drive.Exit24.guiding
import io.github.spysyweeb.beamline.feature.drive.Exit24.miles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Rule D6: the guidance notification's title and text; NotificationPacer's pace. */
class NotificationTextTest {

    private fun n(trip: TripState, units: io.github.spysyweeb.beamline.units.UnitFormatter = miles) = DrivePresenter.notification(trip, units)

    @Test
    fun `guiding - primary and distance, then the roads as the banner writes them`() {
        assertEquals(GuidanceNotificationText("Exit 24 · 0.5 mi", "US 19 / Peach St"), n(guiding(banner = frame1)))
        assertEquals(GuidanceNotificationText("Exit 24 · 0.3 mi", "US 19 / Peach St"), n(guiding(banner = frame1.copy(distanceM = 482.8))))
        assertEquals(GuidanceNotificationText("Turn left · 400 ft", "onto US 19 / Peach St"), n(guiding(banner = frame2)))
        assertEquals(GuidanceNotificationText("Turn right · 0.2 mi", "onto Downs Dr"), n(guiding(banner = frame3)))
        assertEquals(GuidanceNotificationText("Exit 24 · 800 m", "US 19 / Peach St"), n(guiding(banner = frame1), Exit24.km))
        // no roads: the sign's towards; neither: empty
        val signOnly = frame1.copy(view = Exit24.exitView.copy(roads = emptyList()))
        assertEquals("toward Waterford", n(guiding(banner = signOnly))!!.text)
        assertEquals("", n(guiding(banner = LiveBanner(Exit24.arriveView, 100.0, false)))!!.text)
        assertEquals("Arrive, on the right · 350 ft", n(guiding(banner = LiveBanner(Exit24.arriveView, 100.0, false)))!!.title)
    }

    @Test
    fun `not on route - the status text in place of the roads`() {
        assertEquals(GuidanceNotificationText("Exit 24 · 0.5 mi", "GPS signal lost"), n(guiding(gps = GpsState.LOST)))
        assertEquals("Weak GPS signal", n(guiding(gps = GpsState.WEAK))!!.text)
        assertEquals("Waiting for GPS", n(guiding(gps = GpsState.WAITING))!!.text)
        val failedChange = TripProblem(ProblemKind.TOO_MANY_STOPS, "A trip can have up to 9 stops.", false)
        assertEquals("A trip can have up to 9 stops.", n(guiding(problem = failedChange))!!.text)
    }

    @Test
    fun `re-routing - no maneuver in the title (D7), the failure or the destination as text`() {
        assertEquals(GuidanceNotificationText("Finding a new route...", "To Downs Drive"), n(guiding(banner = frame2, phase = Phase.REROUTING)))
        val outside = TripProblem(ProblemKind.OUTSIDE_INSTALLED_MAPS, "You are outside the installed maps.", false)
        assertEquals(
            GuidanceNotificationText("Finding a new route...", "You are outside the installed maps."),
            n(guiding(banner = frame2, phase = Phase.REROUTING, problem = outside)),
        )
    }

    @Test
    fun `before the first maneuver is known - the destination`() {
        assertEquals(GuidanceNotificationText("To Downs Drive", "Waiting for GPS"), n(guiding(banner = null, gps = GpsState.WAITING)))
        // D7: a new route with no banner yet (the car not yet on it) says it is still finding the way
        assertEquals(GuidanceNotificationText("To Downs Drive", "Finding a new route..."), n(guiding(banner = null)))
    }

    @Test
    fun `arrived - Arrived at the destination, and the side`() {
        val arrived = guiding(banner = LiveBanner(Exit24.arriveView, 10.0, false), phase = Phase.ARRIVED)
        assertEquals(GuidanceNotificationText("Arrived at Downs Drive", "On the right"), n(arrived))
        assertEquals(GuidanceNotificationText("Arrived at Downs Drive", ""), n(arrived.copy(guidance = arrived.guidance!!.copy(banner = null))))
    }

    @Test
    fun `a saved Home reads Home, never its description or point (PV6)`() {
        val home = SavedPlace("s00000000000000aa", SavedKind.HOME, "Home", GeoPoint(42.1292, -80.0851), "Perry Square", "Erie")
        val arrived = guiding(banner = LiveBanner(Exit24.arriveView, 10.0, false), phase = Phase.ARRIVED, stops = listOf(TripStop(home.toPlace())))
        val text = n(arrived)!!
        assertEquals("Arrived at Home", text.title)
        val rerouting = n(guiding(phase = Phase.REROUTING, stops = listOf(TripStop(home.toPlace()))))!!
        assertEquals("To Home", rerouting.text)
        for (t in listOf(text, rerouting)) {
            val all = t.title + " " + t.text
            assertFalse(all.contains("Perry") || all.contains("Erie") || all.contains("42.") || all.contains("80."), all)
        }
    }

    @Test
    fun `no notification outside GUIDING, REROUTING and ARRIVED`() {
        assertNull(n(TripState()))
        assertNull(n(choosing(phase = Phase.PLANNING)))
        assertNull(n(choosing()))
        assertNull(n(TripState(phase = Phase.RESUME_OFFERED, stops = listOf(TripStop(Exit24.downsDrive)))))
    }

    @Test
    fun `the pacer - at once on a new maneuver, else at most every 5 s, never the same text twice`() {
        val pacer = NotificationPacer(minIntervalMs = 5_000)
        val first = pacer.next(guiding(banner = frame1), miles, 0)
        assertEquals("Exit 24 · 0.5 mi", first!!.title)
        assertNull(pacer.next(guiding(banner = frame1), miles, 1_000)) // same text
        assertNull(pacer.heldUntilMs())
        assertNull(pacer.next(guiding(banner = frame1.copy(distanceM = 640.0)), miles, 2_000)) // 0.4 mi, too soon
        assertEquals(5_000L, pacer.heldUntilMs())
        assertEquals("Exit 24 · 0.4 mi", pacer.next(guiding(banner = frame1.copy(distanceM = 640.0)), miles, 5_000)!!.title)
        assertNull(pacer.heldUntilMs())
        assertNull(pacer.next(guiding(banner = frame1.copy(distanceM = 480.0)), miles, 6_000)) // 0.3 mi, too soon
        // the maneuver changes: at once
        assertEquals("Turn left · 400 ft", pacer.next(guiding(banner = frame2), miles, 6_500)!!.title)
        // re-routing starts, so the maneuver goes (D7): at once
        assertEquals("Finding a new route...", pacer.next(guiding(banner = frame2, phase = Phase.REROUTING), miles, 7_000)!!.title)
        // the new route's first maneuver: at once
        val newRoute = Exit24.choice(9, listOf("fastest"))
        assertEquals("Turn left · 400 ft", pacer.next(guiding(banner = frame2, route = newRoute), miles, 7_200)!!.title)
        // the same maneuver number on another route is another maneuver: at once
        assertEquals("Turn left · 0.3 mi", pacer.next(guiding(banner = frame2.copy(distanceM = 480.0)), miles, 7_400)!!.title)
        // the arrival: at once
        val arrived = guiding(banner = LiveBanner(Exit24.arriveView, 10.0, false), phase = Phase.ARRIVED)
        assertEquals("Arrived at Downs Drive", pacer.next(arrived, miles, 7_500)!!.title)
        // the trip ends: nothing, and the next trip's first text goes out at once
        assertNull(pacer.next(TripState(), miles, 7_600))
        assertEquals("Exit 24 · 0.5 mi", pacer.next(guiding(banner = frame1), miles, 7_700)!!.title)
    }

    @Test
    fun `the pacer - GPS, problem and phase changes wait for the 5 s like the distance (D6)`() {
        val pacer = NotificationPacer(minIntervalMs = 5_000)
        assertEquals(GuidanceNotificationText("Turn left · 400 ft", "onto US 19 / Peach St"), pacer.next(guiding(banner = frame2), miles, 0))
        // GPS flapping every second on the same maneuver: one post per 5 s, not one per second
        val states = listOf(GpsState.WEAK, GpsState.GOOD, GpsState.WEAK, GpsState.GOOD, GpsState.WEAK, GpsState.GOOD, GpsState.WEAK, GpsState.GOOD, GpsState.WEAK, GpsState.LOST)
        val posted = states.mapIndexedNotNull { i, gps -> pacer.next(guiding(banner = frame2, gps = gps), miles, 1_000L * (i + 1))?.let { (i + 1) * 1_000L to it.text } }
        assertEquals(listOf(5_000L to "Weak GPS signal", 10_000L to "GPS signal lost"), posted)
        // a held change goes out when its time comes, through heldUntilMs and another call with the latest trip
        assertNull(pacer.next(guiding(banner = frame2), miles, 11_000))
        assertEquals(15_000L, pacer.heldUntilMs())
        assertEquals("onto US 19 / Peach St", pacer.next(guiding(banner = frame2), miles, pacer.heldUntilMs()!!)!!.text)
        // a failed stop change while the banner stays: waits too
        val failedChange = TripProblem(ProblemKind.TOO_MANY_STOPS, "A trip can have up to 9 stops.", false)
        assertNull(pacer.next(guiding(banner = frame2, problem = failedChange), miles, 16_000))
        assertEquals("A trip can have up to 9 stops.", pacer.next(guiding(banner = frame2, problem = failedChange), miles, 20_000)!!.text)
        // while re-routing (no maneuver named) the failure waits too
        assertEquals("Finding a new route...", pacer.next(guiding(banner = frame2, phase = Phase.REROUTING), miles, 21_000)!!.title)
        val outside = TripProblem(ProblemKind.OUTSIDE_INSTALLED_MAPS, "You are outside the installed maps.", false)
        assertNull(pacer.next(guiding(banner = frame2, phase = Phase.REROUTING, problem = outside), miles, 22_000))
        assertEquals("You are outside the installed maps.", pacer.next(guiding(banner = frame2, phase = Phase.REROUTING, problem = outside), miles, 26_000)!!.text)
        // back to the posted text before the time is up: nothing held, nothing to post
        assertNull(pacer.next(guiding(banner = frame2, phase = Phase.REROUTING, problem = outside), miles, 27_000))
        assertNull(pacer.heldUntilMs())
    }
}
