package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripProblem
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.feature.drive.Exit24.downsDrive
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame2
import io.github.spysyweeb.beamline.feature.drive.Exit24.guiding
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import io.github.spysyweeb.beamline.feature.drive.Exit24.sheetz
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rules D2 and D7, the arrival and stop cards, Recenter. */
class StatusPresentTest {

    private val noRoute = TripProblem(ProblemKind.NO_ROUTE, "No route found from here.", canRetry = true)
    private val stopCard = TripEvent.StopReached(0, "Sheetz")

    private fun status(trip: io.github.spysyweeb.beamline.api.nav.TripState, event: TripEvent? = null) =
        present(trip, shownEvent = event).guidance!!.status

    @Test
    fun `the arrival card says how far the place still is when the route ends more than 100 m from it`() {
        val arrived = LiveBanner(Exit24.arriveView, 10.0, false)
        val end = guiding().guidance!!.route.geometry.last()
        val mall = io.github.spysyweeb.beamline.api.model.Place("Mall store", io.github.spysyweeb.beamline.api.model.GeoPoint(end.lat + 150.0 / 111_320.0, end.lon),
            io.github.spysyweeb.beamline.api.model.PlaceSource.SEARCH)
        val far = status(guiding(banner = arrived, phase = Phase.ARRIVED, stops = listOf(TripStop(mall)))) as GuidanceStatusUi.Arrived
        // play test P4: the line says what the distance is (it read as a contradiction of "Arrived")
        assertEquals("Route ends 500 ft from the place", far.awayText, "150 m in the units shown (US: feet, rounded)")
        val near = io.github.spysyweeb.beamline.api.model.Place("Shop", io.github.spysyweeb.beamline.api.model.GeoPoint(end.lat + 90.0 / 111_320.0, end.lon),
            io.github.spysyweeb.beamline.api.model.PlaceSource.SEARCH)
        assertNull((status(guiding(banner = arrived, phase = Phase.ARRIVED, stops = listOf(TripStop(near)))) as GuidanceStatusUi.Arrived).awayText)
    }

    @Test
    fun `D2 - each status wins over every one after it`() {
        // Arrived > StopReached (card time) > RerouteFailed > Rerouting > GpsLost > WaitingForGps > GpsWeak > OnRoute.
        val twoStops = listOf(TripStop(sheetz, reached = true), TripStop(downsDrive))
        assertEquals(
            GuidanceStatusUi.Arrived("Downs Drive", "On the right"),
            status(guiding(banner = LiveBanner(Exit24.arriveView, 10.0, false), phase = Phase.ARRIVED, gps = GpsState.LOST, problem = noRoute, stops = twoStops), stopCard),
        )
        assertEquals(
            GuidanceStatusUi.StopReached("Sheetz"),
            status(guiding(phase = Phase.REROUTING, gps = GpsState.LOST, problem = noRoute, stops = twoStops), stopCard),
        )
        assertEquals(
            GuidanceStatusUi.RerouteFailed("No route found from here.", canRetry = true),
            status(guiding(phase = Phase.REROUTING, gps = GpsState.LOST, problem = noRoute)),
        )
        assertEquals(GuidanceStatusUi.Rerouting, status(guiding(phase = Phase.REROUTING, gps = GpsState.LOST)))
        assertEquals(GuidanceStatusUi.GpsLost, status(guiding(gps = GpsState.LOST)))
        assertEquals(GuidanceStatusUi.WaitingForGps, status(guiding(gps = GpsState.WAITING)))
        assertEquals(GuidanceStatusUi.GpsWeak, status(guiding(gps = GpsState.WEAK)))
        assertEquals(GuidanceStatusUi.OnRoute, status(guiding(gps = GpsState.GOOD)))
    }

    @Test
    fun `D7 - no banner in REROUTING, the status card takes its place`() {
        val rerouting = present(guiding(banner = frame2, phase = Phase.REROUTING))
        assertNull(rerouting.guidance!!.banner)
        assertEquals(GuidanceStatusUi.Rerouting, rerouting.guidance.status)
        val failed = present(guiding(banner = frame2, phase = Phase.REROUTING, problem = noRoute))
        assertNull(failed.guidance!!.banner)
        assertEquals(GuidanceStatusUi.RerouteFailed(noRoute.text, true), failed.guidance.status)
        val outside = TripProblem(ProblemKind.OUTSIDE_INSTALLED_MAPS, "You are outside the installed maps.", canRetry = false)
        assertEquals(
            GuidanceStatusUi.RerouteFailed("You are outside the installed maps.", false),
            present(guiding(phase = Phase.REROUTING, problem = outside)).guidance!!.status,
        )
        // the trip bar stays
        assertEquals("1.2 mi", rerouting.guidance.tripBar.remainingDistanceText)
    }

    @Test
    fun `D7 - after a new route the card stays until its first banner, also while the car is still off it`() {
        // O6 sets GUIDING at the answer; the session has no banner until a fix on the new route (B6)
        val replaced = present(guiding(banner = null)).guidance!!
        assertNull(replaced.banner)
        assertEquals(GuidanceStatusUi.Rerouting, replaced.status)
        val stillOff = present(guiding(banner = null, offRouteSuspected = true)).guidance!!
        assertEquals(GuidanceStatusUi.Rerouting, stillOff.status, "the wrongturn detour: never an empty top")
        assertEquals(GuidanceStatusUi.Rerouting, status(guiding(banner = null, gps = GpsState.WEAK)))
        // what outranks it stays first; at Start before the first fix it is waiting for GPS
        assertEquals(GuidanceStatusUi.WaitingForGps, status(guiding(banner = null, gps = GpsState.WAITING)))
        assertEquals(GuidanceStatusUi.GpsLost, status(guiding(banner = null, gps = GpsState.LOST)))
        assertEquals(GuidanceStatusUi.RerouteFailed(noRoute.text, true), status(guiding(banner = null, problem = noRoute)))
        // the first banner of the new route ends it
        assertEquals(GuidanceStatusUi.OnRoute, status(guiding(banner = frame2, offRouteSuspected = true)))
    }

    @Test
    fun `D7 - while only suspected off route the banner stays as it was`() {
        val onRoute = present(guiding(banner = frame2)).guidance!!
        val suspected = present(guiding(banner = frame2, offRouteSuspected = true)).guidance!!
        assertEquals(onRoute.banner, suspected.banner)
        assertEquals(GuidanceStatusUi.OnRoute, suspected.status)
    }

    @Test
    fun `a stop change that failed while guided shows its problem over the banner, which stays (C3)`() {
        val tooMany = TripProblem(ProblemKind.TOO_MANY_STOPS, "A trip can have up to 9 stops.", canRetry = false)
        val g = present(guiding(banner = frame2, problem = tooMany)).guidance!!
        assertNotNull(g.banner)
        assertEquals(GuidanceStatusUi.RerouteFailed("A trip can have up to 9 stops.", false), g.status)
    }

    @Test
    fun `the stop card shows for its time through EventCards, then the trip goes on`() {
        val cards = EventCards(8_000)
        val trip = guiding(stops = listOf(TripStop(sheetz, reached = true), TripStop(downsDrive)))
        cards.onEvent(stopCard, nowMs = 100_000)
        assertEquals(GuidanceStatusUi.StopReached("Sheetz"), status(trip, cards.shown(100_000)))
        assertEquals(GuidanceStatusUi.StopReached("Sheetz"), status(trip, cards.shown(107_999)))
        assertEquals(GuidanceStatusUi.OnRoute, status(trip, cards.shown(108_000)))
        // the banner stays under the card
        assertNotNull(present(trip, shownEvent = cards.shown(100_000)).guidance!!.banner)
    }

    @Test
    fun `arrived - the card names the destination and the side, no banner, no recenter, no stops editor`() {
        val trip = guiding(banner = LiveBanner(Exit24.arriveView, 12.0, false), phase = Phase.ARRIVED, remainingM = 12.0, remainingS = 2.0)
        val ui = present(trip, camera = CameraState(CameraMode.ARRIVED, showRecenter = true), stopsEditorOpen = true)
        val g = ui.guidance!!
        assertEquals(GuidanceStatusUi.Arrived("Downs Drive", "On the right"), g.status)
        assertNull(g.banner)
        assertFalse(g.showRecenter)
        assertNull(ui.stopsEditor)
        assertNull(ui.planning)
        // the event's name wins when the card is up; the left side; no side for a plain arrival
        assertEquals(
            GuidanceStatusUi.Arrived("Downs Drive", "On the left"),
            status(trip.copy(guidance = trip.guidance!!.copy(banner = LiveBanner(Exit24.arriveView.copy(kind = io.github.spysyweeb.beamline.api.nav.ManeuverKind.ARRIVE_LEFT), 5.0, false))), TripEvent.Arrived("Downs Drive")),
        )
        assertEquals(
            GuidanceStatusUi.Arrived("Downs Drive", null),
            status(trip.copy(guidance = trip.guidance!!.copy(banner = null))),
        )
    }

    @Test
    fun `Recenter shows after a pan and in the overview, not while following`() {
        val trip = guiding()
        assertFalse(present(trip, camera = CameraState(CameraMode.GUIDE_FOLLOW, false)).guidance!!.showRecenter)
        assertTrue(present(trip, camera = CameraState(CameraMode.GUIDE_FREE, true)).guidance!!.showRecenter)
        assertTrue(present(trip, camera = CameraState(CameraMode.GUIDE_OVERVIEW, false)).guidance!!.showRecenter)
        assertTrue(present(trip, camera = CameraState(CameraMode.GUIDE_FOLLOW, true)).guidance!!.showRecenter)
    }

    @Test
    fun `the stops editor opens over guidance while guided and re-routing`() {
        val ui = present(guiding(stops = listOf(TripStop(sheetz), TripStop(downsDrive))), stopsEditorOpen = true)
        assertNotNull(ui.guidance)
        assertEquals(listOf("Sheetz", "Downs Drive"), ui.stopsEditor!!.rows.map { it.title })
        assertNotNull(present(guiding(phase = Phase.REROUTING), stopsEditorOpen = true).stopsEditor)
        assertNull(present(guiding(), stopsEditorOpen = false).stopsEditor)
    }
}
