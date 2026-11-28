package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 0.1.41 (wave 0, item 54b): onStopView takes a padding; null keeps preview.padding_dp, as before. */
class StopViewPaddingContractTest {
    private val clock = VirtualClock()
    private val cam = CameraDirector(CameraRules.bundled(), clock)
    private val pts = listOf(Fx.ROUTE[2], GeoPoint(42.0450, -80.0950), GeoPoint(42.0380, -80.0900))

    @Test
    fun `a null padding is the stop view of before`() {
        cam.onViewport(411f, 891f, EdgeInsetsDp(top = 24f, bottom = 300f))
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 10_000)))
        cam.onStopView(pts, paddingDp = null)
        assertEquals(CameraMode.GUIDE_OVERVIEW, cam.state.value.mode)
        val padded = EdgeInsetsDp(top = 72f, bottom = 348f, start = 48f, end = 48f)
        assertEquals(CameraTarget(fit = Bounds.around(pts + Fx.ROUTE[1]), bearingDeg = 0.0, pitchDeg = 0.0, padding = padded, animateMs = 800), cam.target.value)
    }
}
