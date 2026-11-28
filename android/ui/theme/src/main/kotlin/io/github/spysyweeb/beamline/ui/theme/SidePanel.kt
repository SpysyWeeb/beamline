package io.github.spysyweeb.beamline.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp

/**
 * A window wider than tall and at least this wide (a phone on its side) lays its sheets out in one panel
 * on the start side and the map keeps the rest of the width: the drive overlay's banner and sheets (rule
 * D10) and, since 0.1.40 (play test P7), the place sheet and the results list. Otherwise the sheets are at
 * the bottom, across the whole width.
 */
val SIDE_LAYOUT_MIN_WIDTH: Dp = 560.dp

/** Whether a window [width] x [height] lays its sheets out in the side panel ([SIDE_LAYOUT_MIN_WIDTH]). */
fun isSideLayout(width: Dp, height: Dp): Boolean = width > height && width >= SIDE_LAYOUT_MIN_WIDTH

/** The side panel's width in a window [width] wide: half of it, at least 320 dp (the banner needs it) and at most 400 dp. */
fun sidePanelWidth(width: Dp): Dp = (width / 2).coerceIn(320.dp, 400.dp)
