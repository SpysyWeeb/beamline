package io.github.spysyweeb.beamline.about.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.feature.about.AboutPackUi
import io.github.spysyweeb.beamline.feature.about.AboutUi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import io.github.spysyweeb.beamline.feature.about.R as AboutR

/** Made-up but realistic About content (public places only; no device data). */
internal fun sampleAbout() = AboutUi(
    appName = "Beamline",
    versionText = "0.1.0 (3)",
    buildText = "Demo build (not the app)",
    styleText = "Map style 007a6f98c1f9",
    packs = listOf(
        AboutPackUi("Pennsylvania", "2026-11-14", "1.03 GB"),
        AboutPackUi("Ohio", "2026-11-14", "0.98 GB"),
    ),
    dataCredits = listOf(
        "Map data © OpenStreetMap contributors, ODbL 1.0",
        "US overview: Natural Earth; US Census Bureau",
        "Pennsylvania: © OpenStreetMap contributors (ODbL 1.0); house numbers: US Census Bureau TIGER/Line, USDOT National Address Database, City of Scranton (PDDL 1.0)",
        "Ohio: © OpenStreetMap contributors (ODbL 1.0)",
    ),
)

/** Something map-like under the reveal, so the hand-over to "the map" can be seen. */
@Composable
internal fun StandInMap(night: Boolean) {
    val land = if (night) Color(0xFF1B2128) else Color(0xFFF2EFE9)
    val road = if (night) Color(0xFF39434F) else Color(0xFFFFFFFF)
    val water = if (night) Color(0xFF1A3346) else Color(0xFFAAD3DF)
    Canvas(Modifier.fillMaxSize().background(land)) {
        val w = size.width
        val h = size.height
        drawLine(water, Offset(-50f, h * 0.12f), Offset(w + 50f, h * 0.08f), strokeWidth = w * 0.12f)
        for (i in 1..5) drawLine(road, Offset(w * i / 6f, 0f), Offset(w * i / 6f, h), strokeWidth = 10f)
        for (i in 1..9) drawLine(road, Offset(0f, h * i / 10f), Offset(w, h * i / 10f), strokeWidth = 10f)
        drawLine(Color(0xFF4A8CF7), Offset(w * 3 / 6f, h), Offset(w * 3 / 6f, h * 0.3f), strokeWidth = 18f, cap = StrokeCap.Round)
    }
}

@Composable
internal fun Home(onOpen: (String) -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("About demo", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                "Host for :feature:about's device checks. Not the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for ((label, screen) in listOf("About" to "about", "Licenses" to "licences", "Driver notice" to "notice", "Icon shapes" to "icons")) {
                Button(onClick = { onOpen(screen) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
}

/** A superellipse (|x|^4 + |y|^4 = 1), the "squircle" launcher mask. */
private val Squircle: Shape = GenericShape { size, _ ->
    val n = 4.0
    val steps = 180
    for (i in 0..steps) {
        val t = 2 * Math.PI * i / steps
        val c = cos(t)
        val s = sin(t)
        val x = (abs(c).pow(2.0 / n) * sign(c) + 1) / 2 * size.width
        val y = (abs(s).pow(2.0 / n) * sign(s) + 1) / 2 * size.height
        if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
    }
    close()
}

/** The adaptive icon's two layers, as the launcher composes them, under three masks; and the themed layer. */
@Composable
internal fun IconShapes(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val bg = ImageBitmap.imageResource(AboutR.mipmap.ic_launcher_background)
    val fg = ImageBitmap.imageResource(AboutR.mipmap.ic_launcher_foreground)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Icon shapes (rendered from the shipped layers)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            for (dark in listOf(false, true)) {
                val wall = if (dark) Brush.linearGradient(listOf(Color(0xFF1B2430), Color(0xFF0B0F14)))
                else Brush.linearGradient(listOf(Color(0xFFE9EEF5), Color(0xFFC6D4E6)))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(wall).padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Layers(bg, fg, CircleShape, 64.dp)
                    Layers(bg, fg, Squircle, 64.dp)
                    Layers(bg, fg, RoundedCornerShape(22), 64.dp)
                    Layers(bg, fg, CircleShape, 40.dp)
                    Layers(bg, fg, CircleShape, 28.dp)
                }
            }
            Text("Themed (monochrome layer, tinted as Android 13+ does)", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((tile, ink) in listOf(Color(0xFFD7E3FF) to Color(0xFF0B3A7A), Color(0xFF263040) to Color(0xFFB8CCF0))) {
                    Box(Modifier.size(64.dp).clip(CircleShape).background(tile), contentAlignment = Alignment.Center) {
                        Image(
                            painterResource(AboutR.drawable.ic_launcher_monochrome),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(ink),
                            modifier = Modifier.size(96.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}

@Composable
private fun Layers(bg: ImageBitmap, fg: ImageBitmap, shape: Shape, size: Dp) {
    Canvas(Modifier.size(size).clip(shape)) {
        val side = (this.size.width * 108f / 72f).roundToInt()
        val off = IntOffset(-(side - this.size.width.roundToInt()) / 2, -(side - this.size.height.roundToInt()) / 2)
        for (layer in listOf(bg, fg)) {
            drawImage(layer, IntOffset.Zero, IntSize(layer.width, layer.height), off, IntSize(side, side), filterQuality = FilterQuality.High)
        }
    }
}
