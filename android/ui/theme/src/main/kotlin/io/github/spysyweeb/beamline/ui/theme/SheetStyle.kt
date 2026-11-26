package io.github.spysyweeb.beamline.ui.theme

import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Item 47's style rules: the top corners of every bottom sheet. */
val SHEET_CORNER: Dp = 24.dp

/** Item 47's style rules: the height of a sheet's primary button (and the pills beside it). */
val PRIMARY_BUTTON_HEIGHT: Dp = 52.dp

/**
 * How far (px) the sheet inside still has to slide to the height it is going to: its target height
 * minus its height now (positive while it grows). Whoever measures a sheet for the map's camera
 * provides one and adds it, so the camera gets the height the sheet is going to the moment it starts
 * moving, not after the slide (the owner, 2026-11-26: the map's fit to the window "is delayed").
 */
val LocalSheetLag = staticCompositionLocalOf<MutableIntState?> { null }
