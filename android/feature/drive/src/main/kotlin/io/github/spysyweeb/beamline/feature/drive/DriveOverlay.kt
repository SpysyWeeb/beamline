// FILE: feature/drive/src/main/kotlin/io/github/spysyweeb/beamline/feature/drive/DriveOverlay.kt
package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp

/**
 * Banner at the top, sheets and the trip bar at the bottom; reports the space it covers so the camera can pad.
 * [creditShown]: the map credit's short line is showing at the bottom start (rule D8 keeps the speed pair above it).
 */
@Composable
fun DriveOverlay(ui: DriveUi, onEvent: (DriveEvent) -> Unit, onInsets: (EdgeInsetsDp) -> Unit, modifier: Modifier = Modifier, creditShown: Boolean = false): Unit =
    DriveOverlayContent(ui, onEvent, onInsets, modifier, creditShown)
