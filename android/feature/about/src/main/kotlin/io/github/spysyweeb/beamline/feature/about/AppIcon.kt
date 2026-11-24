package io.github.spysyweeb.beamline.feature.about

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The launcher icon as the screens show it: the two adaptive layers (the same bitmaps the launcher
 * uses), their visible middle (72 of 108 units) filling [size], clipped to [shape]. Decorative.
 */
@Composable
internal fun AppIcon(size: Dp = 72.dp, shape: Shape = CircleShape, modifier: Modifier = Modifier) {
    val background = ImageBitmap.imageResource(R.mipmap.ic_launcher_background)
    val foreground = ImageBitmap.imageResource(R.mipmap.ic_launcher_foreground)
    Canvas(modifier.size(size).clip(shape).clearAndSetSemantics { }) {
        // The 108-unit canvas drawn 1.5 times the visible size, centred.
        val side = (this.size.width * 108f / 72f).roundToInt()
        val offset = IntOffset(-(side - this.size.width.roundToInt()) / 2, -(side - this.size.height.roundToInt()) / 2)
        for (layer in listOf(background, foreground)) {
            drawImage(
                layer,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(layer.width, layer.height),
                dstOffset = offset,
                dstSize = IntSize(side, side),
                filterQuality = FilterQuality.High,
            )
        }
    }
}
