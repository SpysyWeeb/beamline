// FILE: feature/drive/src/main/kotlin/io/github/spysyweeb/beamline/feature/drive/DriveOverlay.kt
package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp

/**
 * Banner at the top, sheets and the trip bar at the bottom; reports the space it covers so the camera can pad.
 * [creditShown]: the map credit's short line is showing at the bottom start (rule D8 keeps the speed pair above it).
 * [stopWindowDp]: item 51 (rule S12), the stop results' window is this high at the bottom (0 on its side, where it
 * is in the start panel): only the top (banner, status chip, stop card) and the speed pair above the window are
 * drawn; no trip bar, sheets, map buttons, road label or stops editor. Null: everything, as always.
 * [onTopStack]: where the top stack ends (the banner block, and the chip or stop card under it), in dp from the
 * overlay's top, 0 without one; in both layouts (play test P7: on its side the app's windows share the start
 * panel with the banner and keep under it).
 */
@Composable
fun DriveOverlay(ui: DriveUi, onEvent: (DriveEvent) -> Unit, onInsets: (EdgeInsetsDp) -> Unit, modifier: Modifier = Modifier, creditShown: Boolean = false, stopWindowDp: Float? = null, onTopStack: (Float) -> Unit = {}): Unit =
    DriveOverlayContent(ui, onEvent, onInsets, modifier, creditShown = creditShown, stopWindowDp = stopWindowDp, onTopStack = onTopStack)
