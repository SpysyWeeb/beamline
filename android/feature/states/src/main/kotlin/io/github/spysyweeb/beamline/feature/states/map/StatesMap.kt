package io.github.spysyweeb.beamline.feature.states.map

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.feature.states.model.StateStatus
import io.github.spysyweeb.beamline.feature.states.model.Texts
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The map's zoom and centre, held by the caller (rememberStatesMapView), so "Whole country" can sit
 * outside the map: drawn over it, it covered the labels and outlines at the top right.
 */
@Stable
class StatesMapView internal constructor(private val width: Float, private val height: Float) {
    internal val zoom = Animatable(1f)
    internal var centerX by mutableFloatStateOf(width / 2)
    internal var centerY by mutableFloatStateOf(height / 2)

    /** Zoomed in past the fit: the screen offers "Whole country". */
    val zoomedIn: Boolean get() = zoom.value > 1.01f

    /** P25: the selection the map last showed (zoomed to, or chose not to); it acts once per selection, not again when its list entry comes back into view. */
    internal var shownSelection: String? = null

    /** Back to the whole country (1x, centred). */
    suspend fun wholeCountry() {
        centerX = width / 2
        centerY = height / 2
        zoom.animateTo(1f)
    }
}

@Composable
fun rememberStatesMapView(shapes: StateShapes): StatesMapView = remember(shapes) { StatesMapView(shapes.width, shapes.height) }

/**
 * The drawn US map of the picker (docs/architecture-downloads.md 6.8): Compose Canvas, no MapLibre.
 * Fills, outlines, inset boxes, the selection outline and postal-code labels by status; a tap goes
 * through StateHitTest; pinch and double-tap zoom about the finger from 1x (fit with 16 dp margins) to
 * 8x. The centre is kept so the card shows at most its margin past the country (P15); at 1x a
 * one-finger drag is left to the list around the map, zoomed in it pans.
 */
@Composable
fun StatesMap(
    shapes: StateShapes,
    status: Map<String, StateStatus>,
    selected: String?,
    onTap: (TapResult) -> Unit,
    modifier: Modifier = Modifier,
    view: StatesMapView = rememberStatesMapView(shapes),
    /** What a screen reader says for the map (StatesUi.mapDescription). */
    description: String = Texts.MAP_DESCRIPTION,
    /** False: the selection was made on the map; it is outlined, not zoomed (P25). */
    zoomToSelection: Boolean = true,
) {
    val density = LocalDensity.current
    val paths = remember(shapes) {
        shapes.states.associate { s ->
            s.code to Path().apply {
                fillType = PathFillType.EvenOdd
                for (r in s.rings) {
                    moveTo(r[0], r[1])
                    var i = 2
                    while (i < r.size) {
                        lineTo(r[i], r[i + 1])
                        i += 2
                    }
                    close()
                }
            }
        }
    }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.semantics { contentDescription = description }) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val margin = with(density) { 16.dp.toPx() }
        val fit = minOf((wPx - 2 * margin) / shapes.width, (hPx - 2 * margin) / shapes.height).coerceAtLeast(0.01f)
        val zoom = view.zoom
        var centerX by view::centerX
        var centerY by view::centerY
        val scope = rememberCoroutineScope()
        val scale = fit * zoom.value
        // the owner, 2026-11-26: zoomed in, a tap on a state away from the centre sometimes picked
        // another one. The tap handler below is set up once (its keys are the shapes and the fit), so
        // it must read the zoom as it is at the tap, never the scale of the composition that set it up.
        fun scaleNow() = fit * zoom.value
        // P15: the centre kept so the card shows at most its 16 dp margin past the country (at 1x: centred)
        fun clampX(c: Float, z: Float) = clampCentre(c, shapes.width, wPx / 2 / (fit * z), margin / (fit * z))
        fun clampY(c: Float, z: Float) = clampCentre(c, shapes.height, hPx / 2 / (fit * z), margin / (fit * z))
        // what is drawn and hit-tested: the stored centre kept in the card at this moment's zoom (also mid-animation)
        fun cx() = clampX(centerX, zoom.value)
        fun cy() = clampY(centerY, zoom.value)
        fun toMap(o: Offset): Offset {
            val s = scaleNow()
            return Offset((o.x - wPx / 2) / s + cx(), (o.y - hPx / 2) / s + cy())
        }
        val dpPx = with(density) { 1.dp.toPx() }

        // A selection from the list: zoom to the state's box at 60 % of the card.
        LaunchedEffect(selected) {
            val code = selected
            if (code == view.shownSelection) return@LaunchedEffect // shown already (the list entry came back into view)
            val before = view.shownSelection
            view.shownSelection = code
            if (code == null) { // P12: a search hid the selected state
                if (before != null) view.wholeCountry()
                return@LaunchedEffect
            }
            if (!zoomToSelection) return@LaunchedEffect // P25: picked on the map: outlined, not zoomed (it zoomed behind the sheet)
            val s = shapes.byCode(code) ?: return@LaunchedEffect
            val bw = (s.box[2] - s.box[0]).coerceAtLeast(1f)
            val bh = (s.box[3] - s.box[1]).coerceAtLeast(1f)
            val target = minOf(0.6f * wPx / (bw * fit), 0.6f * hPx / (bh * fit)).coerceIn(1f, 8f)
            centerX = clampX((s.box[0] + s.box[2]) / 2, target) // P15: no sea past the country's edge
            centerY = clampY((s.box[1] + s.box[3]) / 2, target)
            zoom.animateTo(target)
        }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(shapes, fit) {
                    // P15 (audit F66): pinch at any zoom, one finger pans only when zoomed in. At 1x a one-finger
                    // drag is left unconsumed, so in portrait the list the map sits in scrolls (detectTransformGestures
                    // consumed every drag past touch slop, whatever its callback did).
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false) // the tap detector below consumes the down
                        var mine = false // past touch slop as a pinch, or as a drag while zoomed in
                        var panSum = Offset.Zero
                        var zoomSum = 1f
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) break
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!mine) {
                                panSum += panChange
                                zoomSum *= zoomChange
                                val slop = viewConfiguration.touchSlop
                                val fingers = event.changes.count { it.pressed }
                                mine = abs(1 - zoomSum) * event.calculateCentroidSize(useCurrent = false) > slop ||
                                    (panSum.getDistance() > slop && (view.zoomedIn || fingers > 1))
                            }
                            if (mine) {
                                val before = zoom.value
                                val after = (before * zoomChange).coerceIn(1f, 8f)
                                val f = toMap(event.calculateCentroid(useCurrent = false)) // under the fingers, at the zoom before
                                centerX = clampX(zoomAbout(cx(), f.x, before, after) - panChange.x / (fit * after), after)
                                centerY = clampY(zoomAbout(cy(), f.y, before, after) - panChange.y / (fit * after), after)
                                // UNDISPATCHED: the next event reads the new zoom (a launched snapTo could still be pending)
                                scope.launch(start = CoroutineStart.UNDISPATCHED) { zoom.snapTo(after) }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) {
                                break // the list took the drag
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(shapes, fit) {
                    detectTapGestures(
                        onDoubleTap = { o ->
                            // P15: about the finger: the tapped point stays under it (before: it jumped to the middle)
                            val before = zoom.value
                            val after = (before * 2).coerceAtMost(8f)
                            val f = toMap(o)
                            centerX = clampX(zoomAbout(cx(), f.x, before, after), after)
                            centerY = clampY(zoomAbout(cy(), f.y, before, after), after)
                            scope.launch { zoom.animateTo(after) }
                        },
                        onTap = { o ->
                            val m = toMap(o)
                            onTap(StateHitTest.resolve(shapes, m.x, m.y, dpPx / scaleNow()))
                        },
                    )
                },
        ) {
            val ccx = cx()
            val ccy = cy()
            withTransform({
                translate(wPx / 2 - ccx * scale, hPx / 2 - ccy * scale)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                val thin = 1f * dpPx / scale
                for (s in shapes.states) {
                    val p = paths.getValue(s.code)
                    val fill = when (status[s.code]) {
                        StateStatus.INSTALLED, StateStatus.UPDATE_AVAILABLE -> colors.primary.copy(alpha = 0.55f)
                        StateStatus.IN_PROGRESS -> colors.primaryContainer
                        StateStatus.NOT_AVAILABLE -> colors.background
                        else -> colors.surfaceVariant
                    }
                    drawPath(p, fill)
                }
                for (s in shapes.states) {
                    val p = paths.getValue(s.code)
                    when (status[s.code]) {
                        StateStatus.INSTALLED, StateStatus.UPDATE_AVAILABLE -> drawPath(p, colors.primary, style = Stroke(1.5f * thin))
                        StateStatus.IN_PROGRESS -> drawPath(p, colors.primary, style = Stroke(2f * thin))
                        StateStatus.NOT_AVAILABLE -> drawPath(p, colors.outline, style = Stroke(thin, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * thin, 3 * thin))))
                        else -> drawPath(p, colors.outline, style = Stroke(thin))
                    }
                }
                for ((_, b) in shapes.insetBoxes) {
                    drawRect(colors.outlineVariant, topLeft = Offset(b[0], b[1]), size = androidx.compose.ui.geometry.Size(b[2] - b[0], b[3] - b[1]), style = Stroke(thin))
                }
                selected?.let { code -> paths[code]?.let { drawPath(it, colors.onSurface, style = Stroke(3f * thin)) } }
            }
            // Labels in screen pixels, so the text keeps its size at every zoom.
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = 11f * dpPx
                color = colors.onSurface.toArgb()
            }
            for (s in shapes.states) {
                val wideDp = (s.box[2] - s.box[0]) * scale / dpPx
                if (wideDp < 28f) continue
                val mark = when (status[s.code]) { // UNKNOWN and LOADING: as AVAILABLE, without a mark
                    StateStatus.INSTALLED, StateStatus.UPDATE_AVAILABLE -> " ✓"
                    StateStatus.IN_PROGRESS -> " ↓"
                    else -> ""
                }
                val x = wPx / 2 + (s.labelX - ccx) * scale
                val y = hPx / 2 + (s.labelY - ccy) * scale + paint.textSize / 3
                drawContext.canvas.nativeCanvas.drawText(s.code + mark, x, y, paint)
            }
        }
    }
}
