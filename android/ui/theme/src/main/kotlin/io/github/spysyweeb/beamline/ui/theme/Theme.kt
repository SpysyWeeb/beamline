package io.github.spysyweeb.beamline.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colours from the approved sketches (docs/design/position-markers.html): the route blue family.
 * The route blue is candidate B, "Clear" (assets/route_colors.json, chosen 2026-11-15): the fill
 * colours of the route line, also used for the puck and for route-coloured screen elements.
 * The screens follow the map: night map, dark screens; day map, light screens.
 */
object BeamlineColors {
    val routeDay = Color(0xFF4A8CF7)
    val routeNight = Color(0xFF3A80F0)
    val accentDay = Color(0xFF2A74D8)
    val accentNight = Color(0xFF5AA5FF)
    val puckRing = Color(0xFFFFFFFF)
    val puckGreyDay = Color(0xFF8C96A1)
    val puckGreyNight = Color(0xFF6E7883)
}

@Composable
fun BeamlineTheme(night: Boolean, content: @Composable () -> Unit) {
    val scheme = if (night) {
        darkColorScheme(
            primary = BeamlineColors.accentNight,
            onPrimary = Color(0xFF06121F),
            background = Color(0xFF0D1216),
            surface = Color(0xFF151C23),
            onSurface = Color(0xFFE7EDF3),
            onBackground = Color(0xFFE7EDF3),
            surfaceVariant = Color(0xFF1E2731),
            onSurfaceVariant = Color(0xFF97A5B4),
            surfaceContainerHigh = Color(0xFF1B232C),
            surfaceContainerHighest = Color(0xFF222B35),
        )
    } else {
        lightColorScheme(
            primary = BeamlineColors.accentDay,
            onPrimary = Color.White,
            background = Color(0xFFEEF1F4),
            surface = Color.White,
            onSurface = Color(0xFF17212B),
            onBackground = Color(0xFF17212B),
            surfaceVariant = Color(0xFFE3E8ED),
            onSurfaceVariant = Color(0xFF566575),
            surfaceContainerHigh = Color(0xFFF7F9FB),
            surfaceContainerHighest = Color(0xFFEFF2F5),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
