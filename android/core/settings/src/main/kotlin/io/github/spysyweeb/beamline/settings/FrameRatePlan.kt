package io.github.spysyweeb.beamline.settings

/** One display mode as Android reports it (`Display.Mode`). */
data class DisplayMode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * What to ask for, for a frame-rate setting:
 * - [mapMaxFps]: MapLibre's `MapView.setMaximumFps` (it draws no more frames than this);
 * - [surfaceFrameRate]: `Surface.setFrameRate` on the map's own surface (Android 11+), the platform's
 *   current way to tell the display what rate the content wants;
 * - [preferredModeId]: `WindowManager.LayoutParams.preferredDisplayModeId`, used only before
 *   Android 11, and only to pick the fastest mode of the current resolution (0 = no preference).
 */
data class FrameRatePlan(val mapMaxFps: Int, val surfaceFrameRate: Float, val preferredModeId: Int)

object FrameRates {

    /**
     * [modes] are the display's supported modes, [current] the active one. Screen rate asks for the
     * highest refresh rate available at the current resolution; 60 caps the map and tells the
     * display that 60 is enough, so a 120 Hz phone can slow down and save power.
     */
    fun plan(setting: FrameRate, modes: List<DisplayMode>, current: DisplayMode?): FrameRatePlan {
        val sameSize = modes.filter { current == null || (it.width == current.width && it.height == current.height) }
        val fastest = sameSize.maxByOrNull { it.refreshRate } ?: current
        return when (setting) {
            FrameRate.SCREEN -> {
                val rate = fastest?.refreshRate ?: 60f
                FrameRatePlan(
                    mapMaxFps = kotlin.math.round(rate).toInt().coerceAtLeast(1),
                    surfaceFrameRate = rate,
                    preferredModeId = fastest?.id ?: 0,
                )
            }
            FrameRate.FPS_60 -> FrameRatePlan(setting.fps!!, setting.fps.toFloat(), 0)
        }
    }
}
