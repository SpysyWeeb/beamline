package io.github.spysyweeb.beamline.map.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** camera.json: CameraRules.parse and bundled, SavedMarkerRules.parse and bundled, the copy equal to its source. */
class CameraRulesTest {

    @Test
    fun `bundled camera rules are the values of camera json`() {
        val r = CameraRules.bundled()
        assertEquals(60.0, r.guidePitchDeg)
        assertEquals(30.0, r.bearingTurnDeg)
        assertEquals(0.28, r.guideVehicleFromBottom)
        assertEquals(listOf(0.0 to 17.0, 13.4 to 16.5, 24.6 to 15.75, 31.3 to 15.25), r.guideZoomBySpeed)
        assertEquals(250.0, r.approachWithinM)
        assertEquals(17.0, r.approachZoom)
        assertEquals(0.3, r.bearingSmoothing)
        assertEquals(15.0, r.recenterAfterS)
        assertEquals(10.0, r.overviewReturnAfterS)
        assertEquals(16.0, r.browseFollowZoom)
        assertEquals(48f, r.previewPaddingDp)
        assertEquals(17.0, r.arrivedZoom)
        assertEquals(800, r.transitionMs)
        assertEquals(r, CameraRules.parse(Fx.cameraText()))
    }

    @Test
    fun `bundled saved marker rules are the saved_places section`() {
        assertEquals(SavedMarkerRules(homeWorkMinZoom = 10.0, otherMinZoom = 12.0, iconScale = 1.25), SavedMarkerRules.bundled())
    }

    @Test
    fun `saved marker rules parse a test text`() {
        val text = """{"format": "beamline-camera/1", "saved_places": {"home_work_min_zoom": 9.5, "other_min_zoom": 13, "icon_scale": 1.5}}"""
        assertEquals(SavedMarkerRules(9.5, 13.0, 1.5), SavedMarkerRules.parse(text))
    }

    @Test
    fun `camera rules parse a test text`() {
        val text = """
            {"format": "beamline-camera/1",
             "guide": {"pitch_deg": 45, "vehicle_from_bottom": 0.3, "zoom_by_speed_mps": [[0, 18], [30, 15]],
                       "approach_within_m": 100, "approach_zoom": 16.5, "bearing_smoothing": 1, "recenter_after_s": 5},
             "overview": {"return_after_s": 7.5}, "browse": {"follow_zoom": 15}, "preview": {"padding_dp": 24.5},
             "arrived": {"zoom": 18}, "transition_ms": 0}
        """.trimIndent()
        val r = CameraRules.parse(text)
        assertEquals(CameraRules(45.0, 0.3, listOf(0.0 to 18.0, 30.0 to 15.0), 100.0, 16.5, 1.0, 5.0, 7.5, 15.0, 24.5f, 18.0, 0), r)
    }

    @Test
    fun `broken or foreign files are refused with fixed sentences`() {
        val good = Fx.cameraText()
        val bad = listOf(
            "not json at 42.068",
            "[1, 2]",
            good.replace("beamline-camera/1", "beamline-camera/2"),
            good.replace("\"pitch_deg\": 60", "\"pitch_deg\": 95"),
            good.replace("\"pitch_deg\": 60", "\"pitch_deg\": \"60\""),
            good.replace("\"vehicle_from_bottom\": 0.28", "\"vehicle_from_bottom\": 1.2"),
            good.replace("[[0, 17.0], [13.4, 16.5]", "[[13.4, 16.5], [0, 17.0]"),
            good.replace("[[0, 17.0], [13.4, 16.5], [24.6, 15.75], [31.3, 15.25]]", "[]"),
            good.replace("[[0, 17.0]", "[[0, 17.0, 3]"),
            good.replace("[[0, 17.0]", "[[0, 30.0]"),
            good.replace("\"bearing_smoothing\": 0.3", "\"bearing_smoothing\": 0"),
            good.replace("\"bearing_turn_deg\": 30", "\"bearing_turn_deg\": 200"),
            good.replace("\"bearing_turn_deg\": 30", "\"bearing_turn_deg\": \"30\""),
            good.replace("\"recenter_after_s\": 15", "\"recenter_after_s\": -1"),
            good.replace("\"transition_ms\": 800", "\"transition_ms\": 800.5"),
            good.replace("\"follow_zoom\": 16", "\"follow_zoom\": 40"),
            good.replace("\"overview\": {\"return_after_s\": 10},", ""),
        )
        for (text in bad) {
            val e = assertThrows<IllegalArgumentException>(text.take(60)) { CameraRules.parse(text) }
            assertFalse(e.message.orEmpty().contains("42.068"), e.message)
        }
        for (text in listOf(
            good.replace("\"icon_scale\": 1.25", "\"icon_scale\": 0"),
            good.replace("\"other_min_zoom\": 12", "\"other_min_zoom\": -1"),
            good.replace("\"saved_places\"", "\"saved\""),
            good.replace("beamline-camera/1", "beamline-guidance/1"),
        )) {
            assertThrows<IllegalArgumentException> { SavedMarkerRules.parse(text) }
        }
    }

    @Test
    fun `the bundled copy equals packbuilder's camera json`() {
        val source = File(Fx.repoRoot(), "packbuilder/routing/camera.json").readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
        val copy = File(Fx.repoRoot(), "android/map/scene/src/main/resources/io/github/spysyweeb/beamline/map/scene/camera.json")
            .readBytes().toString(Charsets.UTF_8)
        assertEquals(source, copy, "run python android/routing/scripts/sync_data.py --only camera.json")
    }
}
