package io.github.spysyweeb.beamline.feature.about

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The timing of SplashReveal as plain functions of the time since the reveal began (after the
 * system splash was handed over), so it is tested on the JVM (RevealTimelineTest).
 *
 * 1. Opening, [OPEN_MS]: a circle centred on the icon opens from the icon's own circle until the
 *    scene fills the screen (ease-out cubic, as the design page `night-drive-reveal.html`); the
 *    buildings, park and river fade in over the same time. The icon never moves or scales.
 * 2. Hold: the full scene stays until the map is ready, for at most [HOLD_MAX_MS] after the opening.
 * 3. Fade, [FADE_MS]: the scene fades out over the map; then the reveal is finished.
 */
internal object RevealTimeline {
    const val OPEN_MS = 700L
    const val HOLD_MAX_MS = 2_000L
    const val FADE_MS = 250L

    /**
     * The design opens the circle to 205 units on a screen of 144 x 312 units, whose farthest
     * corner is hypot(72, 156) units from the centre: the same overshoot is kept on every screen,
     * so the corners fill at the same moment of the ease as in the design.
     */
    val END_RADIUS_PER_CORNER: Float = (205.0 / hypot(72.0, 156.0)).toFloat()

    data class Frame(
        /** 0 at the icon's circle, 1 at the end radius. */
        val openFraction: Float,
        /** Opacity of the buildings, park and river. */
        val detailAlpha: Float,
        /** Opacity of the whole reveal over the map. */
        val sceneAlpha: Float,
        val finished: Boolean,
    )

    fun easeOutCubic(p: Float): Float {
        val q = 1f - p.coerceIn(0f, 1f)
        return 1f - q * q * q
    }

    /** The design's detail fade: from 12 % of the opening, over 70 % of it. */
    fun detailAt(p: Float): Float = ((p - 0.12f) / 0.7f).coerceIn(0f, 1f)

    /**
     * When the fade begins. [readyAtMs] is when the map was first ready (0 or less when it was
     * ready before the reveal began), or null while it is not ready.
     */
    fun fadeStartMs(readyAtMs: Long?): Long {
        val latest = OPEN_MS + HOLD_MAX_MS
        return if (readyAtMs == null) latest else min(max(readyAtMs, OPEN_MS), latest)
    }

    fun frame(elapsedMs: Long, readyAtMs: Long?): Frame {
        val t = max(elapsedMs, 0L)
        val p = (t.toFloat() / OPEN_MS).coerceIn(0f, 1f)
        val fadeStart = fadeStartMs(readyAtMs)
        val fade = if (t <= fadeStart) 0f else ((t - fadeStart).toFloat() / FADE_MS).coerceIn(0f, 1f)
        return Frame(
            openFraction = easeOutCubic(p),
            detailAlpha = detailAt(p),
            sceneAlpha = 1f - fade,
            finished = t >= fadeStart + FADE_MS,
        )
    }

    /** The circle's radius, in the same unit as the arguments. */
    fun radius(openFraction: Float, iconRadius: Float, width: Float, height: Float): Float {
        val end = max(hypot(width / 2f, height / 2f) * END_RADIUS_PER_CORNER, iconRadius)
        return iconRadius + (end - iconRadius) * openFraction
    }
}
