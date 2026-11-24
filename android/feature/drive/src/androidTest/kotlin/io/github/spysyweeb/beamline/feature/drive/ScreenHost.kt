package io.github.spysyweeb.beamline.feature.drive

import android.graphics.Bitmap
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import java.io.File
import java.io.FileOutputStream

/** Where the device test writes its screenshots: the test APK's own external files folder (pulled with tools/round/adb.sh). */
internal fun screensDir(): File =
    File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }

internal const val SHOT_TAG = "shot"

/**
 * One phone screen, [widthDp] x [heightDp] dp: a stand-in map (land, two roads, the route in blue B,
 * day or night colours of docs/design and assets/route_colors.json) with the drive overlay on top, in
 * the app's theme. The system bars' insets are taken outside it, so the overlay lays out as on a
 * phone below the status bar. [fullWindow]: the whole window instead, the overlay drawing under the
 * real status and navigation bars as in the app (edge to edge); [onView] gets the hosting view.
 */
@Composable
internal fun PhoneScreen(
    ui: DriveUi,
    night: Boolean,
    heightDp: Int = 640,
    widthDp: Int = 360,
    onEvent: (DriveEvent) -> Unit = {},
    onInsets: (EdgeInsetsDp) -> Unit = {},
    fontScale: Float? = null,
    fullWindow: Boolean = false,
    onView: (View) -> Unit = {},
) {
    val density = LocalDensity.current
    val view = LocalView.current
    SideEffect { onView(view) }
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
        BeamlineTheme(night = night) {
            if (fullWindow) {
                Box(Modifier.fillMaxSize().clipToBounds().testTag(SHOT_TAG)) {
                    StandInMap(night)
                    DriveOverlay(ui, onEvent, onInsets)
                }
            } else {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(Modifier.size(widthDp.dp, heightDp.dp).clipToBounds().testTag(SHOT_TAG)) {
                        StandInMap(night)
                        DriveOverlay(ui, onEvent, onInsets)
                    }
                }
            }
        }
    }
}

@Composable
private fun StandInMap(night: Boolean) {
    val land = if (night) Color(0xFF1C242C) else Color(0xFFE7E5DF)
    val edge = if (night) Color(0xFF10161C) else Color(0xFFD2CFC7)
    val road = if (night) Color(0xFF34404C) else Color(0xFFFFFFFF)
    val casing = if (night) Color(0xFF1C3F86) else Color(0xFF1F5BC4)
    val fill = if (night) Color(0xFF3A80F0) else Color(0xFF4A8CF7)
    Canvas(Modifier.fillMaxSize()) {
        drawRect(land)
        val x = size.width / 2
        val y = size.height * 0.42f
        for ((c, w) in listOf(edge to 38.dp.toPx(), road to 32.dp.toPx())) {
            drawLine(c, Offset(x, -10f), Offset(x, size.height + 10f), w)
            drawLine(c, Offset(-10f, y), Offset(size.width + 10f, y), w)
        }
        val route = Path().apply { moveTo(x, size.height + 10f); lineTo(x, y); lineTo(-10f, y) }
        drawPath(route, casing, style = Stroke(16.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(route, fill, style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Captures the screen node and writes it as a PNG; returns the file. */
internal fun ComposeContentTestRule.saveShot(name: String): File {
    waitForIdle()
    val bitmap = onNodeWithTag(SHOT_TAG).captureToImage().asAndroidBitmap()
    val file = File(screensDir(), "$name.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}

/**
 * The node tagged [tag] is shown whole: not cut by the scrolling part or the screen edge (its bounds
 * clipped by every ancestor equal its own size). assertIsDisplayed passes for a sliver.
 */
internal fun ComposeContentTestRule.assertWhollyShown(tag: String) {
    waitForIdle()
    val coordinates = onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
    val shown = coordinates.boundsInWindow()
    val size = coordinates.size
    check(shown.height >= size.height - 1f && shown.width >= size.width - 1f) {
        "$tag: ${shown.width.toInt()} x ${shown.height.toInt()} px shown of ${size.width} x ${size.height}"
    }
}

/**
 * The node tagged [tag] is not cut at its top: shown from its own top edge, or not at all (scrolled
 * wholly away). The route sheet's stop lines must not be cut at the top of the scrolling part, where
 * a destination's second line read as the start's; a cut at the bottom edge (the name showing, the
 * second line under Start) is ordinary scrolling and allowed. The node's box here is its text (the
 * semantics node's layout, without the line's padding); a cut of the empty leading above the name's
 * letters (titleMedium: 16 sp letters in a 24 sp line, 4 sp above them, times [fontScale]) is no cut.
 */
internal fun ComposeContentTestRule.assertNotCutAtTop(tag: String, fontScale: Float = 1f) {
    waitForIdle()
    val coordinates = onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
    val shown = coordinates.boundsInWindow()
    val top = coordinates.positionInWindow().y
    val leading = 4f * fontScale * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    val hidden = shown.height <= 1f || shown.width <= 1f
    check(hidden || shown.top <= top + leading + 1f) {
        "$tag: cut at the top, ${shown.height.toInt()} px of ${coordinates.size.height} shown from ${(shown.top - top).toInt()} px below its top"
    }
}

/**
 * The banner's lane row shows all [count] lanes, each as wide as the first (none squeezed), inside
 * the row and not clipped. The lane boxes are the row's children in the unmerged tree (a screen
 * reader gets the row as one node with its description).
 */
internal fun ComposeContentTestRule.assertLanesWhole(count: Int) {
    waitForIdle()
    val row = onNodeWithTag(DriveTags.LANES, useUnmergedTree = true).fetchSemanticsNode()
    val lanes = row.children
    check(lanes.size == count) { "lanes: ${lanes.size} in the row, $count wanted" }
    val rowBounds = row.boundsInRoot
    val first = lanes.first().boundsInRoot.width
    check(first >= 24f) { "lane 0: $first px wide" }
    for ((i, lane) in lanes.withIndex()) {
        val b = lane.boundsInRoot
        check(b.width >= first - 1f) { "lane $i: ${b.width} px wide, lane 0 is $first px" }
        check(b.left >= rowBounds.left - 1f && b.right <= rowBounds.right + 1f) { "lane $i: $b outside the row $rowBounds" }
        val shown = lane.layoutInfo.coordinates.boundsInWindow()
        check(shown.width >= b.width - 1f && shown.height >= b.height - 1f) { "lane $i: $shown shown of $b" }
    }
}

/**
 * The whole device screen (system bars and popups included) through UiAutomation. Composition being
 * idle does not mean the frame and the status bar's new icons are on the display yet: the first try
 * caught the previous screen, so it waits for the main thread and then lets the display settle.
 */
internal fun saveDeviceScreen(name: String): File {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    Thread.sleep(700)
    try {
        instrumentation.uiAutomation.waitForIdle(200, 3_000)
    } catch (_: java.util.concurrent.TimeoutException) {
        // an animation keeps the screen busy; the sleep above has to do
    }
    val bitmap = instrumentation.uiAutomation.takeScreenshot()
    val file = File(screensDir(), "$name.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return file
}
