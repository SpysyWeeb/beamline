package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.routing.model.LatLon
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals

/** The exit 24 route's line (the route the session guides in these tests). */
val EXIT24 = RouteGeometry(Data.exit24)

/** Along-distance of maneuver [k] of the exit 24 route (1 exit 24, 2 left onto Peach St, 3 right onto Downs Dr, 4 arrive). */
fun m(k: Int) = EXIT24.maneuverAlong[k]

/** A stop on Peach St, 150 m after the left turn (on the route: a 3-stop trip with it has two legs). */
val PEACH_STOP: Place by lazy {
    val p = EXIT24.pointAt(m(2) + 150.0)
    Place("Peach St stop", GeoPoint(RouteSimulator.round6(p.lat), RouteSimulator.round6(p.lon)), PlaceSource.MAP_PIN)
}

/** One session test: a harness on the test's virtual clock, closed at the end; every engine request must be recorded. */
fun sessionTest(rules: GuidanceRules = GuidanceRules.bundled().withoutLead(), puck: Boolean = true, followers: RouteFollowerFactory = GeometricFollowerFactory(),
                body: suspend TestScope.(Harness) -> Unit) = runTest {
    val h = Harness(this, rules = rules, puck = puck, followers = followers)
    try {
        body(h)
        h.assertAllRecorded()
    } finally {
        h.close()
    }
}

/** Plans the exit 24 trip and starts guidance (no simulation unless given). */
fun Harness.guideExit24(sim: Simulation? = null) {
    planExit24()
    assertAllRecorded()
    assertEquals(Phase.CHOOSING, phase(), "${state.problem}")
    send(NavIntent.Start(sim))
    assertEquals(Phase.GUIDING, phase(), "${state.problem}")
}

/**
 * A fix [alongM] metres along the exit 24 route, [sideM] metres to its right, now (after
 * [afterMs] of virtual time), with the route's bearing as course unless [bearing] is given.
 */
fun Harness.at(alongM: Double, sideM: Double = 0.0, accuracy: Float = 5f, speed: Float? = 25f, bearing: Double? = null,
               provider: String = "gps", afterMs: Long = 1_000, ageMs: Long = 0, geo: RouteGeometry = EXIT24) {
    if (afterMs > 0) advance(afterMs)
    val p = if (sideM == 0.0) geo.pointAt(alongM) else geo.offset(alongM, sideM)
    gps(p.lat, p.lon, accuracy, speed, (bearing ?: geo.bearingAt(alongM))?.toFloat(), provider, ageMs)
}

/** Fixes every [stepM] metres from [fromM] to [toM], one per second. */
fun Harness.drive(fromM: Double, toM: Double, stepM: Double = 25.0, speed: Float = 25f) {
    var a = fromM
    while (a <= toM + 1e-9) {
        at(a, speed = speed)
        a += stepM
    }
}

/** The vehicle's distance along the exit 24 route. */
fun TripState.vehicleAlong(geo: RouteGeometry = EXIT24): Double {
    val v = guidance!!.vehicle!!.point
    return geo.project(LatLon(v.lat, v.lon)).alongM
}

val TripState.u: Int? get() = guidance?.banner?.view?.maneuverIndex
val TripState.shown: Double? get() = guidance?.banner?.distanceM
