package io.github.spysyweeb.beamline.feature.about

import io.github.spysyweeb.beamline.feature.about.RevealTimeline.FADE_MS
import io.github.spysyweeb.beamline.feature.about.RevealTimeline.HOLD_MAX_MS
import io.github.spysyweeb.beamline.feature.about.RevealTimeline.OPEN_MS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class RevealTimelineTest {

    private fun f(t: Long, ready: Long?) = RevealTimeline.frame(t, ready)

    @Test
    fun startsOnTheIconAndOpensInAbout700Ms() {
        assertEquals(700L, OPEN_MS)
        val start = f(0, 0)
        assertEquals(0f, start.openFraction)
        assertEquals(0f, start.detailAlpha)
        assertEquals(1f, start.sceneAlpha)
        assertEquals(1f, f(OPEN_MS, 0).openFraction)
        assertEquals(1f, f(OPEN_MS, 0).detailAlpha)
        // Ease-out: more than half open at a third of the time; monotonic.
        assertTrue(f(OPEN_MS / 3, 0).openFraction > 0.5f)
        var last = -1f
        for (t in 0L..OPEN_MS step 10) {
            val o = f(t, 0).openFraction
            assertTrue(o >= last)
            last = o
        }
    }

    @Test
    fun theDetailFadesInAsInTheDesign() {
        assertEquals(0f, f((OPEN_MS * 0.12).toLong(), 0).detailAlpha, 1e-3f)
        assertEquals(0.5f, f((OPEN_MS * 0.47).toLong(), 0).detailAlpha, 1e-2f)
        assertEquals(1f, f((OPEN_MS * 0.82).toLong() + 1, 0).detailAlpha, 1e-3f)
    }

    @Test
    fun aMapReadyBeforeTheEndFadesRightAfterTheOpening() {
        assertEquals(OPEN_MS, RevealTimeline.fadeStartMs(0))
        assertEquals(OPEN_MS, RevealTimeline.fadeStartMs(300))
        assertEquals(1f, f(OPEN_MS, 0).sceneAlpha)
        assertEquals(0.5f, f(OPEN_MS + FADE_MS / 2, 0).sceneAlpha, 1e-3f)
        assertFalse(f(OPEN_MS + FADE_MS - 1, 0).finished)
        assertTrue(f(OPEN_MS + FADE_MS, 0).finished)
    }

    @Test
    fun theFullSceneIsHeldUntilTheMapIsReady() {
        for (t in listOf(OPEN_MS, 1_000L, 1_500L, OPEN_MS + HOLD_MAX_MS)) {
            val fr = f(t, null)
            assertEquals(1f, fr.sceneAlpha, "t=$t")
            assertEquals(1f, fr.openFraction)
            assertFalse(fr.finished)
        }
        // Ready at 1.2 s: the fade starts then.
        assertEquals(1_200L, RevealTimeline.fadeStartMs(1_200))
        assertEquals(1f, f(1_200, 1_200).sceneAlpha)
        assertTrue(f(1_200 + FADE_MS, 1_200).finished)
    }

    @Test
    fun theHoldLastsAtMostTwoSeconds() {
        assertEquals(2_000L, HOLD_MAX_MS)
        assertEquals(OPEN_MS + HOLD_MAX_MS, RevealTimeline.fadeStartMs(null))
        assertEquals(OPEN_MS + HOLD_MAX_MS, RevealTimeline.fadeStartMs(10_000))
        assertTrue(f(OPEN_MS + HOLD_MAX_MS + FADE_MS, null).finished)
    }

    @Test
    fun theCircleStartsAtTheIconAndEndsBeyondTheCorners() {
        val w = 1080f
        val h = 2400f
        val icon = 266f
        assertEquals(icon, RevealTimeline.radius(0f, icon, w, h))
        val end = RevealTimeline.radius(1f, icon, w, h)
        assertTrue(end > hypot(w / 2, h / 2))
        // The design: 205 units on its 144 x 312 screen.
        assertEquals(205f, RevealTimeline.radius(1f, 36f, 144f, 312f), 1e-3f)
    }
}
