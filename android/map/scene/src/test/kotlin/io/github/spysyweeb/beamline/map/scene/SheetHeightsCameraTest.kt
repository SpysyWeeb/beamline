package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rule D10 (batch F, docs/next-round.md item 15): every bottom sheet has a lowest height, and the
 * insets the app hands the camera follow the sheet's height (the app measures the sheet), so the
 * results, the routes and a picked place are fitted to the space the sheet leaves, and back.
 * The heights below are those measured on the emulator at 411 x 891 dp (batch F screenshots).
 */
class SheetHeightsCameraTest {
    private val cam = CameraDirector(CameraRules.bundled(), VirtualClock())
    private val target get() = cam.target.value!!
    private val pad = 48f   // camera.json preview.padding_dp

    private fun insets(bottom: Float) = EdgeInsetsDp(top = 120f, bottom = bottom)

    @Test
    fun `the results are fitted above the list at each of its three heights, and back`() {
        cam.onViewport(411f, 891f, insets(380f))   // the middle height (about three rows)
        val pts = (0 until 6).map { GeoPoint(42.12 + it * 3.0 / 111.0, -80.08) }   // 15 km north-south
        cam.onShowResults(pts, minZoom = 8.0)
        val middle = target
        cam.onViewport(411f, 891f, insets(170f))   // lowered: the handle and the count
        val low = target
        assertEquals(CameraMode.BROWSE, cam.state.value.mode)
        assertEquals(170f + pad, low.padding.bottom)
        assertTrue(low.zoom!! > middle.zoom!!, "lowered list: closer (${low.zoom} > ${middle.zoom})")
        assertTrue(low.center!!.lat in pts.first().lat..pts.last().lat)
        cam.onViewport(411f, 891f, insets(650f))   // the highest height (about 60 %)
        assertTrue(target.zoom!! < middle.zoom!!)
        cam.onViewport(411f, 891f, insets(380f))   // back to the middle: the same fit as at first
        assertEquals(middle.copy(animateMs = target.animateMs), target)
    }

    @Test
    fun `the routes are fitted above the route sheet, low or full`() {
        cam.onViewport(411f, 891f, insets(600f))   // the full route sheet (From, To, cards, options, Start)
        cam.onTrip(Fx.choosing())
        val full = target
        assertEquals(CameraMode.PREVIEW, cam.state.value.mode)
        assertEquals(Bounds.around(Fx.ROUTE + Fx.ALT + Fx.ORIGIN), full.fit)
        assertEquals(600f + pad, full.padding.bottom)
        cam.onViewport(411f, 891f, insets(250f))   // lowered: the small cards and Start
        assertEquals(full.fit, target.fit)
        assertEquals(250f + pad, target.padding.bottom)
        // choosing another card at the low height does not re-fit (K2), the insets stay
        cam.onTrip(Fx.choosing(selected = 2))
        assertEquals(250f + pad, target.padding.bottom)
        cam.onViewport(411f, 891f, insets(600f))
        assertEquals(full.copy(animateMs = target.animateMs), target)
    }

    @Test
    fun `a picked place stays in the space above its sheet when the sheet is lowered`() {
        cam.onViewport(411f, 891f, insets(330f))
        cam.onShowPlace(Fx.MUSEUM)
        assertEquals(Fx.MUSEUM, target.center)
        assertEquals(330f, target.padding.bottom)
        cam.onViewport(411f, 891f, insets(150f))   // the place sheet's title line only
        assertEquals(Fx.MUSEUM, target.center)
        assertEquals(150f, target.padding.bottom)
    }
}
