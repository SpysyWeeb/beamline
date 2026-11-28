package io.github.spysyweeb.beamline.app.ui

import android.os.Build
import android.view.Window
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * P39 (0.1.40): a rotation lets the platform redraw the system bars with light icons, so whenever [rotation]
 * (the configuration's orientation) or [upright] changes, both are set again from the map
 * ([AppRules.barIconsAfterRotation]). The status bar not while the drive overlay holds it: [upright]
 * and [driveDarkTop] (its dark banner reaches under the bar then).
 */
@Composable
internal fun BarIconsAfterRotation(window: Window, night: Boolean, revealDone: Boolean, upright: Boolean, rotation: Int, driveDarkTop: () -> Boolean) {
    val view = LocalView.current
    LaunchedEffect(night, revealDone, upright, rotation) {
        val icons = AppRules.barIconsAfterRotation(night, revealDone, driveHoldsStatusBar = upright && driveDarkTop())
        val bars = WindowCompat.getInsetsController(window, view)
        bars.isAppearanceLightNavigationBars = icons.navForLightBackground
        icons.statusForLightBackground?.let { bars.isAppearanceLightStatusBars = it }
    }
}

/**
 * P41 (0.1.40): inside a Material modal sheet (its own dialog window). That window kept Android's contrast
 * scrim behind the 3-button navigation bar, coloured by the bar appearance the sheet opened with (a
 * grey band once the theme turned light under it, a white one once it turned dark); the sheet reaches
 * under the bar itself (Material pads only its content), so the bar is made see-through over it, as
 * MainActivity's enableEdgeToEdge makes the main window's. The bars' icons follow the sheet's text
 * colour: on every theme change (Material sets them once, when the dialog is made) and after a
 * rotation (P39: the platform may redraw them light). Call it first inside the sheet's content.
 */
@Composable
internal fun SheetWindowBars() {
    val view = LocalView.current
    val lightBars = LocalContentColor.current.luminance() <= 0.5f // dark text on a light sheet: dark icons (Material's own test)
    val orientation = LocalConfiguration.current.orientation
    DisposableEffect(view, lightBars, orientation) {
        val window = generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
            ?: (view as? DialogWindowProvider)?.window
        if (window != null) {
            if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
            WindowCompat.getInsetsController(window, window.decorView).run {
                isAppearanceLightNavigationBars = lightBars
                isAppearanceLightStatusBars = lightBars
            }
        }
        onDispose { }
    }
}
