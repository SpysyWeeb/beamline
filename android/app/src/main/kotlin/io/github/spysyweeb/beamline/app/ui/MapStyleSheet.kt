package io.github.spysyweeb.beamline.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spysyweeb.beamline.settings.MapStyle

/**
 * The map style button and sheet (0.1.34; the owner, 2026-11-25: "the map style section in settings
 * could have it's own button and UI on the map screen"; the concept on the design canvas): a round
 * button under Settings (not while guiding) opens a sheet of four picture tiles. A tap writes the
 * setting at once, so the map changes behind the sheet; Settings keeps the same choice.
 */
internal val MapStyleIcon: ImageVector = ImageVector.Builder("map_style", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(12f, 3f); lineTo(21f, 8f); lineTo(12f, 13f); lineTo(3f, 8f); close()
        moveTo(3f, 12f); lineTo(12f, 17f); lineTo(21f, 12f)
        moveTo(3f, 16f); lineTo(12f, 21f); lineTo(21f, 16f)
    }
}.build()

private val DAY_GROUND = Color(0xFFE8EBEE)
private val DAY_ROAD = Color(0xFFFFFFFF)
private val DAY_ROUTE = Color(0xFF4A8CF7)
private val NIGHT_GROUND = Color(0xFF1A1F28)
private val NIGHT_ROAD = Color(0xFF2B3340)
private val NIGHT_ROUTE = Color(0xFF3A80F0)

/** The line under the tiles for the chosen style. */
internal fun mapStyleLine(style: MapStyle): String = when (style) {
    MapStyle.AUTOMATIC -> "Automatic: day or night with sunrise and sunset"
    MapStyle.SAME_AS_PHONE -> "Same as phone: day or night with the phone's dark mode"
    MapStyle.DAY -> "Day: always the light map"
    MapStyle.NIGHT -> "Night: always the dark map"
}

private fun label(style: MapStyle) = when (style) {
    MapStyle.AUTOMATIC -> "Automatic"
    MapStyle.SAME_AS_PHONE -> "Same as phone"
    MapStyle.DAY -> "Day"
    MapStyle.NIGHT -> "Night"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapStyleSheet(current: MapStyle, onPick: (MapStyle) -> Unit, onClose: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // P41: the app's sheet colour (Material's baseline lavender is not in BeamlineTheme), also under the nav bar now
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        SheetWindowBars()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp).navigationBarsPadding().testTag("map-style-sheet"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Map style", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            val order = listOf(MapStyle.AUTOMATIC, MapStyle.SAME_AS_PHONE, MapStyle.DAY, MapStyle.NIGHT)
            for (pair in order.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (style in pair) StyleTile(style, style == current, Modifier.weight(1f)) { onPick(style) }
                }
            }
            Text(mapStyleLine(current), fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StyleTile(style: MapStyle, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick).testTag("map-style-${style.key}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(92.dp).clip(shape)
                .border(if (selected) 3.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant, shape),
        ) {
            Canvas(Modifier.fillMaxSize()) { preview(style) }
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(14.dp)) {
                        val w = size.width
                        val tick = Stroke(width = w * 0.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        val p = androidx.compose.ui.graphics.Path().apply {
                            moveTo(w * 0.2f, w * 0.52f); lineTo(w * 0.42f, w * 0.74f); lineTo(w * 0.8f, w * 0.3f)
                        }
                        drawPath(p, Color.White, style = tick)
                    }
                }
            }
        }
        Text(
            label(style), fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** A small map of the style: roads and the route in day or night colours; Automatic split with a sun and a moon; Same as phone a phone. */
private fun DrawScope.preview(style: MapStyle) {
    val w = size.width
    val h = size.height
    fun map(left: Float, right: Float, night: Boolean) {
        drawRect(if (night) NIGHT_GROUND else DAY_GROUND, Offset(left, 0f), Size(right - left, h))
        val road = if (night) NIGHT_ROAD else DAY_ROAD
        drawRect(road, Offset(left, h * 0.42f), Size(right - left, h * 0.12f))
        if (w * 0.42f in left..right) drawRect(road, Offset(w * 0.42f, 0f), Size(w * 0.08f, h))
    }
    when (style) {
        MapStyle.DAY, MapStyle.NIGHT -> {
            val night = style == MapStyle.NIGHT
            map(0f, w, night)
            drawLine(if (night) NIGHT_ROUTE else DAY_ROUTE, Offset(w * 0.46f, h), Offset(w * 0.46f, 0f), strokeWidth = h * 0.05f)
        }
        MapStyle.AUTOMATIC -> {
            map(0f, w / 2, false)
            map(w / 2, w, true)
            drawCircle(Color(0xFFF5B400), radius = h * 0.11f, center = Offset(w * 0.24f, h * 0.28f))
            drawCircle(Color(0xFFE4E8EE), radius = h * 0.11f, center = Offset(w * 0.76f, h * 0.28f))
            drawCircle(NIGHT_GROUND, radius = h * 0.1f, center = Offset(w * 0.79f, h * 0.23f))
        }
        MapStyle.SAME_AS_PHONE -> {
            drawRect(Color(0xFFF1F3F5))
            val pw = h * 0.42f
            val ph = h * 0.74f
            val tl = Offset((w - pw) / 2, (h - ph) / 2)
            drawRect(DAY_GROUND, tl, Size(pw / 2, ph))
            drawRect(NIGHT_GROUND, Offset(tl.x + pw / 2, tl.y), Size(pw / 2, ph))
            drawRoundRect(
                Color(0xFF374151), tl, Size(pw, ph),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(pw * 0.18f), style = Stroke(width = h * 0.035f),
            )
        }
    }
}
