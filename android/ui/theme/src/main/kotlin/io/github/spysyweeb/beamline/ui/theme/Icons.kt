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

    // ---- item 47: the place window's outline icons (one set: 24 grid, 2-unit strokes)

    /** A clock: the opening hours. */
    val Clock = icon("clock") {
        stroke {
            circle(12f, 12f, 9f)
            moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15.5f, 14f)
        }
    }

    /** A handset: a phone number, Call. */
    val Phone = icon("phone") {
        stroke {
            moveTo(5f, 4f); lineTo(8.5f, 4f); lineTo(10f, 8.5f); lineTo(7.8f, 9.9f)
            arcTo(11f, 11f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 14.1f, y1 = 16.2f)
            lineTo(15.5f, 14f); lineTo(20f, 15.5f); lineTo(20f, 19f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18f, y1 = 21f)
            arcTo(16f, 16f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 6f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 4f)
            close()
        }
    }

    /** A globe: a web address, Website. */
    val Globe = icon("globe") {
        stroke {
            circle(12f, 12f, 9f)
            moveTo(12f, 3f)
            arcTo(4f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 21f)
            arcTo(4f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f, y1 = 3f)
            moveTo(3f, 12f); lineTo(21f, 12f)
        }
    }

    /** A map pin: the address. */
    val Pin = icon("pin") {
        stroke {
            moveTo(12f, 21f)
            curveTo(12f, 21f, 5f, 14.5f, 5f, 9.5f)
            arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 9.5f)
            curveTo(19f, 14.5f, 12f, 21f, 12f, 21f)
            close()
            circle(12f, 9.5f, 2.5f)
        }
    }

    /** A five-pointed star: Save. */
    val Star = icon("star") {
        stroke {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 9.5f else 4f
                val a = Math.toRadians(-90.0 + i * 36.0)
                val x = 12f + r * cos(a).toFloat()
                val y = 12.8f + r * sin(a).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    /** A flag: Report a problem. */
    val Flag = icon("flag") {
        stroke {
            moveTo(5f, 21f); lineTo(5f, 4f)
            lineTo(17f, 4f); lineTo(14.5f, 8f); lineTo(17f, 12f); lineTo(5f, 12f)
        }
    }

    /** A pencil: Edit. */
    val Pencil = icon("pencil") {
        stroke {
            moveTo(4f, 20f); lineTo(5f, 16f); lineTo(16f, 5f); lineTo(19f, 8f); lineTo(8f, 19f); close()
            moveTo(14f, 7f); lineTo(17f, 10f)
        }
    }

    /** A check: an option that is on (item 47). */
    val Check = icon("check") {
        stroke {
            moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f)
        }
    }
}
