package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.map.PinKind
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.TripState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** Rule S11 (batch B): the camera fits the results on the map, and the result pins. */
class ResultsCameraTest {
    private val rules = CameraRules.bundled()
    private val cam = CameraDirector(rules, VirtualClock())
    private val insets = EdgeInsetsDp(top = 120f, bottom = 300f)
    private val target get() = cam.target.value!!

    init {
        cam.onViewport(411f, 891f, insets)
    }

    /** Downtown Erie and points north-south of it, [km] apart (about 111 km per degree). */
    private fun line(n: Int, km: Double) = (0 until n).map { GeoPoint(42.12 + it * km / 111.0, -80.08) }

    private fun visible(points: List<GeoPoint>): Boolean {
        val t = target
        val p = t.padding
        val freeW = 411f - p.start - p.end
        val freeH = 891f - p.top - p.bottom
        return fitZoom(points, freeW, freeH) >= t.zoom!! - 1e-9 &&
            points.all { abs(it.lat - t.center!!.lat) < 2 && abs(it.lon - t.center!!.lon) < 2 }
    }

    @Test
    fun allNearResultsAreFittedAboveTheList() {
        val pts = line(5, 1.0)   // 4 km from first to last
        cam.onShowResults(pts, minZoom = 8.0)
        assertEquals(CameraMode.BROWSE, cam.state.value.mode)
        val t = target
        assertEquals(EdgeInsetsDp(top = 168f, bottom = 348f, start = 48f, end = 48f), t.padding)   // insets + preview.padding_dp
        assertNull(t.fit)
        assertTrue(visible(pts))
        assertTrue(t.zoom!! in 11.0..16.0, "zoom ${t.zoom}")
        assertEquals(42.12 + 2.0 / 111.0, t.center!!.lat, 1e-3)
        assertEquals(0.0, t.bearingDeg)
        assertEquals(0.0, t.pitchDeg)
    }

    @Test
    fun neverZoomedOutPastTheStatesNorCloserThanFollowZoom() {
        // 20 results 30 km apart: 570 km in all; at z8 about 2 degrees fit: only the nearest are fitted
        val far = line(20, 30.0)
        cam.onShowResults(far, minZoom = 8.0)
        assertTrue(target.zoom!! >= 8.0, "zoom ${target.zoom}")
        assertTrue(visible(far.take(2)))
        assertTrue(target.center!!.lat < far.last().lat - 1.0)    // the farthest are not in the box
        // the very first is always kept, even when two do not fit at minZoom
        cam.onShowResults(line(2, 2000.0), minZoom = 8.0)
        assertEquals(16.0, target.zoom)
        assertEquals(42.12, target.center!!.lat, 1e-9)
        // two results 50 m apart: not closer than follow_zoom
        cam.onShowResults(line(2, 0.05), minZoom = 8.0)
        assertEquals(rules.browseFollowZoom, target.zoom)
    }

    @Test
    fun theListGrowingReFitsAGestureOrAPickedResultEndsTheFit() {
        val pts = line(5, 3.0)
        cam.onShowResults(pts, minZoom = 8.0)
        val before = target.zoom!!
        cam.onViewport(411f, 891f, insets.copy(bottom = 520f))   // the list pulled up
        assertTrue(target.zoom!! < before)
        assertTrue(visible(pts))
        // a result's sheet: the camera goes to that place (K1), the fit is over
        cam.onShowPlace(pts[3])
        assertEquals(pts[3], target.center)
        assertEquals(16.0, target.zoom)
        cam.onPlaceLeft()
        assertNull(cam.target.value)
        // shown again (the sheet closed, the app fits the results again), then a pan
        cam.onShowResults(pts, minZoom = 8.0)
        assertTrue(visible(pts))
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertNull(cam.target.value)
        // following the puck: results leave the follow mode
        cam.onPuck(Fx.puck(Fx.ORIGIN))
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, cam.state.value.mode)
        cam.onShowResults(pts, minZoom = 8.0)
        assertEquals(CameraMode.BROWSE, cam.state.value.mode)
    }

    @Test
    fun notWhileATripExists() {
        cam.onTrip(Fx.choosing())
        val before = cam.target.value
        cam.onShowResults(line(5, 1.0), minZoom = 8.0)
        assertEquals(before, cam.target.value)
        assertEquals(CameraMode.PREVIEW, cam.state.value.mode)
    }

    @Test
    fun resultPinsUnderTheOthersAndTheSelectedOneIsThePickedPin() {
        val mapper = MapSceneMapper()
        val pts = line(3, 1.0)
        val none = mapper.scene(TripState(), null, MapTheme.DAY, null, results = pts)
        assertEquals(listOf("result:0", "result:1", "result:2"), none.pins.map { it.id })
        assertTrue(none.pins.all { it.kind == PinKind.RESULT })
        val selected = mapper.scene(TripState(), null, MapTheme.DAY, Fx.place("Sheetz", pts[1]), results = pts)
        assertEquals(listOf("result:0" to PinKind.RESULT, "result:2" to PinKind.RESULT, "picked" to PinKind.PICKED),
            selected.pins.map { it.id to it.kind })
        assertEquals(pts[1], selected.pins.last().point)
    }
}
