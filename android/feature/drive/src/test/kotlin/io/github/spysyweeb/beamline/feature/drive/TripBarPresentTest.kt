package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.TripProblem
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.feature.drive.Exit24.downsDrive
import io.github.spysyweeb.beamline.feature.drive.Exit24.guiding
import io.github.spysyweeb.beamline.feature.drive.Exit24.newYork
import io.github.spysyweeb.beamline.feature.drive.Exit24.now
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import io.github.spysyweeb.beamline.feature.drive.Exit24.sheetz
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/** Rule D3: the trip bar, its clock in America/New_York in 12- and 24-hour form. */
class TripBarPresentTest {

    private val start = guiding(remainingM = 5341.6, remainingS = 12 * 60.0 + 10)

    @Test
    fun `12-hour clock - arrival is now plus the remaining time, in America New_York`() {
        val bar = present(start).guidance!!.tripBar
        assertEquals("Arrive 3:42 PM", bar.arriveText)
        assertEquals("12 min", bar.remainingTimeText)
        assertEquals("3.3 mi", bar.remainingDistanceText)
        assertNull(bar.nextStopText)
        assertTrue(bar.simulated)
    }

    @Test
    fun `24-hour clock follows the phone, not the units`() {
        assertEquals("Arrive 15:42", present(start, units = Exit24.miles24).guidance!!.tripBar.arriveText)
        // metric with the 12-hour clock keeps 3:42 PM (decision 6.32)
        val metric = present(start, units = Exit24.km).guidance!!.tripBar
        assertEquals("Arrive 3:42 PM", metric.arriveText)
        assertEquals("5.3 km", metric.remainingDistanceText)
    }

    @Test
    fun `the zone given is used, not the machine's`() {
        assertEquals("Arrive 2:42 PM", present(start, zone = ZoneId.of("America/Chicago")).guidance!!.tripBar.arriveText)
        assertEquals("Arrive 3:42 PM", present(start, zone = newYork).guidance!!.tripBar.arriveText)
    }

    @Test
    fun `across the end of daylight saving time in New York`() {
        // 2026-11-01 01:50 EDT (05:50 UTC); 20 minutes later it is 01:10 EST
        val beforeChange = Instant.parse("2026-11-01T05:50:00Z").toEpochMilli()
        val trip = guiding(remainingS = 20 * 60.0)
        assertEquals("Arrive 1:10 AM", present(trip, nowEpochMs = beforeChange).guidance!!.tripBar.arriveText)
        assertEquals("Arrive 01:10", present(trip, units = Exit24.miles24, nowEpochMs = beforeChange).guidance!!.tripBar.arriveText)
    }

    @Test
    fun `the arrival clock is rounded to the nearest minute, as the duration is`() {
        val bar = present(guiding(remainingS = 12 * 60.0 + 40)).guidance!!.tripBar
        assertEquals("Arrive 3:43 PM", bar.arriveText)
        assertEquals("13 min", bar.remainingTimeText)
        assertEquals("Arrive 3:30 PM", present(guiding(remainingS = 0.0)).guidance!!.tripBar.arriveText)
        assertEquals("< 1 min", present(guiding(remainingS = 0.0)).guidance!!.tripBar.remainingTimeText)
        assertEquals("Arrive 4:35 PM", present(guiding(remainingS = 3900.0), nowEpochMs = now).guidance!!.tripBar.arriveText)
        assertEquals("1 h 5 min", present(guiding(remainingS = 3900.0)).guidance!!.tripBar.remainingTimeText)
    }

    @Test
    fun `the next stop when it is not the destination`() {
        val stops = listOf(TripStop(sheetz), TripStop(downsDrive))
        val bar = present(guiding(stops = stops, nextStopIndex = 0, toNextStopM = 1609.344)).guidance!!.tripBar
        assertEquals("Stop 1 · 1.0 mi", bar.nextStopText)
        // after the stop: the destination is next, no stop text
        val after = present(guiding(stops = listOf(TripStop(sheetz, reached = true), TripStop(downsDrive)), nextStopIndex = 1)).guidance!!.tripBar
        assertNull(after.nextStopText)
        // numbered as the stops are, reached ones included
        val three = listOf(TripStop(sheetz, reached = true), TripStop(sheetz.copy(name = "Erie Zoo")), TripStop(downsDrive))
        assertEquals("Stop 2 · 4.1 mi", present(guiding(stops = three, nextStopIndex = 1, toNextStopM = 6600.0)).guidance!!.tripBar.nextStopText)
        assertEquals("Stop 2 · 6.6 km", present(guiding(stops = three, nextStopIndex = 1, toNextStopM = 6600.0), units = Exit24.km).guidance!!.tripBar.nextStopText)
    }

    /**
     * Play test P5: far from the installed maps (rule O8: OUTSIDE_INSTALLED_MAPS without Retry, while re-routing) the
     * old route's arrival, times and next stop are not the trip's: the bar says "No route from here" instead.
     */
    @Test
    fun `P5 outside the installed maps the bar says there is no route from here`() {
        val stops = listOf(TripStop(sheetz), TripStop(downsDrive))
        val outside = TripProblem(ProblemKind.OUTSIDE_INSTALLED_MAPS, "You are outside the installed maps. Guidance goes on when you are back on them.", false)
        val bar = present(guiding(phase = Phase.REROUTING, problem = outside, stops = stops, nextStopIndex = 0)).guidance!!.tripBar
        assertEquals("No route from here", bar.noRouteText)
        assertNull(bar.nextStopText)
        // a failed stop change outside the maps keeps Retry (C3), and the bar keeps its times and next stop
        val change = present(guiding(phase = Phase.REROUTING, problem = outside.copy(canRetry = true), stops = stops, nextStopIndex = 0)).guidance!!.tripBar
        assertNull(change.noRouteText)
        assertEquals("Stop 1 · 1.2 mi", change.nextStopText)
        // an ordinary failed re-route, and plain guidance: the bar as it was
        val noRoute = TripProblem(ProblemKind.NO_ROUTE, "No route found from here.", canRetry = true)
        assertNull(present(guiding(phase = Phase.REROUTING, problem = noRoute)).guidance!!.tripBar.noRouteText)
        assertNull(present(guiding()).guidance!!.tripBar.noRouteText)
        assertNull(present(guiding(problem = outside)).guidance!!.tripBar.noRouteText, "only while re-routing")
    }

    @Test
    fun `the Simulated badge only on a simulated drive`() {
        assertTrue(present(guiding(simulated = true)).guidance!!.tripBar.simulated)
        assertFalse(present(guiding(simulated = false)).guidance!!.tripBar.simulated)
    }
}
