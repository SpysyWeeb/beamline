package io.github.spysyweeb.beamline.feature.about

import android.graphics.Matrix
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Size of the icon's visible part on the system splash: a splash icon without an icon background is
 * 288 dp, of which the middle 192 dp shows (Android 12+ and core-splashscreen alike; measured on the
 * emulator: 504 px at 2.625 px/dp). The icon's 72-unit visible square is that part, so one icon
 * unit is 192 / 72 dp, and the reveal draws the scene at exactly that scale: nothing moves or
 * scales at the hand-over.
 */
internal const val SPLASH_ICON_CIRCLE_DP = 192f

/**
 * The shape the system splash cuts the icon with, as a path in a 100 x 100 box: on Android 12+ the
 * device's icon mask (config_icon_mask: a circle on a Pixel, a square on the AOSP emulator), which
 * AdaptiveIconDrawable reports; below Android 12 core-splashscreen draws a circle (null here). The
 * reveal opens this shape, so its first frame is the system splash's own picture.
 */
private fun splashMask(): android.graphics.Path? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        try {
            AdaptiveIconDrawable(null, null).iconMask.takeUnless { it.isEmpty }
        } catch (_: RuntimeException) {
            null
        }
    } else {
        null
    }

/** SplashReveal (AboutScreens.kt). Fills its bounds; give it the whole window (edge to edge). */
@Composable
internal fun SplashRevealContent(mapReady: Boolean, onFinished: () -> Unit, modifier: Modifier) {
    val ready by rememberUpdatedState(mapReady)
    val finish by rememberUpdatedState(onFinished)
    // Before the hand-over the frame is the reveal's start: the icon in the splash's shape.
    var frame by remember { mutableStateOf(RevealTimeline.frame(0L, null)) }
    val foreground = ImageBitmap.imageResource(R.drawable.splash_icon_foreground)
    val mask = remember { splashMask() }
    val unitPx = with(LocalDensity.current) { (SPLASH_ICON_CIRCLE_DP / (2f * SplashScene.ICON_RADIUS)).dp.toPx() }

    LaunchedEffect(Unit) {
        while (!SplashHandoff.mayStart(SystemClock.uptimeMillis())) withFrameMillis { }
        // With animations switched off in the system settings the reveal starts on the full scene.
        val skipOpening = (coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f
        var start = -1L
        var readyAt: Long? = null
        while (true) {
            val now = withFrameMillis { it }
            if (start < 0L) start = now
            val t = now - start + if (skipOpening) RevealTimeline.OPEN_MS else 0L
            if (ready && readyAt == null) readyAt = t
            frame = RevealTimeline.frame(t, readyAt)
            if (frame.finished) break
        }
        finish()
    }

    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = frame.sceneAlpha }
            .clearAndSetSemantics { }
            // The map under the reveal gets no touches until it has gone.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .drawWithCache {
                val s = SplashScene
                val cx = size.width / 2f
                val cy = size.height / 2f
                fun x(u: Float) = cx + (u - s.CENTRE) * unitPx
                fun y(u: Float) = cy + (u - s.CENTRE) * unitPx
                val far = (size.width + size.height) * 2f
                // Built once per size; each frame only clips and sets opacities.
                val river = Path().apply {
                    val p = s.river
                    moveTo(x(p[0].x), y(p[0].y))
                    cubicTo(x(p[1].x), y(p[1].y), x(p[2].x), y(p[2].y), x(p[3].x), y(p[3].y))
                }
                fun rounded(r: SplashScene.R, corner: Float) =
                    RoundRect(Rect(x(r.x), y(r.y), x(r.x + r.w), y(r.y + r.h)), CornerRadius(corner * unitPx))
                val park = Path().apply { addRoundRect(rounded(s.park, s.PARK_CORNER)) }
                val blocks = Path().apply { s.blocks.forEach { addRoundRect(rounded(it, s.BLOCK_CORNER)) } }
                val route = Path().apply {
                    s.route.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.x), y(p.y)) else lineTo(x(p.x), y(p.y)) }
                }
                val riverStroke = Stroke(width = s.RIVER_WIDTH * unitPx, cap = StrokeCap.Round)
                val casingStroke = Stroke(width = s.ROUTE_CASING_WIDTH * unitPx, join = StrokeJoin.Round)
                val routeStroke = Stroke(width = s.ROUTE_WIDTH * unitPx, join = StrokeJoin.Round)
                val iconSide = (s.ICON_CANVAS * unitPx).roundToInt()
                val iconAt = IntOffset(x(0f).roundToInt(), y(0f).roundToInt())
                val iconRadius = s.ICON_RADIUS * unitPx
                val matrix = Matrix()

                onDrawBehind {
                    drawRect(Color(s.LAND))
                    val radius = RevealTimeline.radius(frame.openFraction, iconRadius, size.width, size.height)
                    val opening = if (frame.openFraction >= 1f) {
                        null   // fully open: no clip at all
                    } else if (mask != null) {
                        val p = android.graphics.Path(mask)
                        matrix.setScale(2f * radius / 100f, 2f * radius / 100f)
                        matrix.postTranslate(cx - radius, cy - radius)
                        p.transform(matrix)
                        p.asComposePath()
                    } else {
                        Path().apply { addOval(Rect(Offset(cx, cy), radius)) }
                    }
                    val scene: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
                        val a = frame.detailAlpha
                        if (a > 0f) {
                            // Per shape, as the design's group opacity (only the river's edge
                            // touches a block; the difference there lasts the half-second fade).
                            drawPath(river, Color(s.WATER), alpha = a, style = riverStroke)
                            drawPath(park, Color(s.PARK), alpha = a)
                            drawPath(blocks, Color(s.BLOCK), alpha = a)
                        }
                        for (u in s.sideRoadsX) drawLine(Color(s.SIDE_ROAD), Offset(x(u), -far), Offset(x(u), far), s.SIDE_ROAD_WIDTH * unitPx)
                        for (u in s.sideRoadsY) drawLine(Color(s.SIDE_ROAD), Offset(-far, y(u)), Offset(far, y(u)), s.SIDE_ROAD_WIDTH * unitPx)
                        for (u in s.mainRoadsX) drawLine(Color(s.MAIN_ROAD), Offset(x(u), -far), Offset(x(u), far), s.MAIN_ROAD_WIDTH * unitPx)
                        for (u in s.mainRoadsY) drawLine(Color(s.MAIN_ROAD), Offset(-far, y(u)), Offset(far, y(u)), s.MAIN_ROAD_WIDTH * unitPx)
                        drawPath(route, Color(s.ROUTE_CASING), style = casingStroke)
                        drawPath(route, Color(s.ROUTE), style = routeStroke)
                        // The arrow, its beam and its glow: the icon's foreground layer, never moved or scaled.
                        drawImage(
                            foreground,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(foreground.width, foreground.height),
                            dstOffset = iconAt,
                            dstSize = IntSize(iconSide, iconSide),
                            filterQuality = FilterQuality.High,
                        )
                    }
                    if (opening == null) scene() else clipPath(opening, block = scene)
                }
            },
    )
}
