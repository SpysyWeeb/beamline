package io.github.spysyweeb.beamline.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The few screen icons the app needs, drawn here (24 x 24 grid, 2-unit strokes) rather than taken
 * from an icon library. Tinted by the composable that shows them.
 */
object BeamlineIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(pathBuilder: PathBuilder.() -> Unit) =
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = pathBuilder)

    private fun ImageVector.Builder.fill(type: PathFillType = PathFillType.NonZero, pathBuilder: PathBuilder.() -> Unit) =
        path(fill = SolidColor(Color.Black), pathFillType = type, pathBuilder = pathBuilder)

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }

    /** Crosshair with a dot: centre the map on my position. */
    val MyLocation = icon("my_location") {
        stroke {
            circle(12f, 12f, 7f)
            moveTo(12f, 2f); lineTo(12f, 5f)
            moveTo(12f, 19f); lineTo(12f, 22f)
            moveTo(2f, 12f); lineTo(5f, 12f)
            moveTo(19f, 12f); lineTo(22f, 12f)
        }
        fill { circle(12f, 12f, 3f) }
    }

    /** The same, with the centre ring filled: the map follows my position. */
    val MyLocationFollowing = icon("my_location_following") {
        stroke {
            moveTo(12f, 2f); lineTo(12f, 5f)
            moveTo(12f, 19f); lineTo(12f, 22f)
            moveTo(2f, 12f); lineTo(5f, 12f)
            moveTo(19f, 12f); lineTo(22f, 12f)
        }
        fill { circle(12f, 12f, 8f) }
    }

    /** Two stacked map sheets: the installed state packs. */
    val Packs = icon("packs") {
        stroke {
            moveTo(12f, 4f); lineTo(21f, 9f); lineTo(12f, 14f); lineTo(3f, 9f); close()
            moveTo(3f, 13.5f); lineTo(12f, 18.5f); lineTo(21f, 13.5f)
        }
    }

    /** Gear with eight teeth, drawn from its outline. */
    val Settings = icon("settings") {
        fill(PathFillType.EvenOdd) {
            val teeth = 8
            val outer = 10.5f
            val inner = 7.8f
            for (i in 0 until teeth) {
                val a = Math.PI * 2 * i / teeth
                val half = Math.PI / teeth
                val pts = listOf(
                    a - half * 0.95 to inner, a - half * 0.45 to outer, a + half * 0.45 to outer, a + half * 0.95 to inner,
                )
                pts.forEachIndexed { k, (ang, r) ->
                    val x = 12f + r * cos(ang).toFloat()
                    val y = 12f + r * sin(ang).toFloat()
                    if (i == 0 && k == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            close()
            circle(12f, 12f, 3.4f)
        }
    }

    val Back = icon("back") {
        stroke {
            moveTo(20f, 12f); lineTo(5f, 12f)
            moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
        }
    }

    val Add = icon("add") {
        stroke {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(19f, 12f)
        }
    }
}
