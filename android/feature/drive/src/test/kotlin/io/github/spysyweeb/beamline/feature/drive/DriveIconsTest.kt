package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The maneuver glyphs drawn here (DriveIcons, SketchPaths). */
class DriveIconsTest {

    private fun paths(v: ImageVector): List<VectorPath> = (0 until v.root.size).map { v.root[it] as VectorPath }

    /** The end point of every node of [p] on the 24-unit grid (the points the stroke reaches, arc ends included). */
    private fun points(p: VectorPath): List<Offset> {
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        val out = ArrayList<Offset>()
        for (n in p.pathData) {
            when (n) {
                is PathNode.MoveTo -> { x = n.x; y = n.y; startX = x; startY = y }
                is PathNode.RelativeMoveTo -> { x += n.dx; y += n.dy; startX = x; startY = y }
                is PathNode.LineTo -> { x = n.x; y = n.y }
                is PathNode.RelativeLineTo -> { x += n.dx; y += n.dy }
                is PathNode.HorizontalTo -> x = n.x
                is PathNode.RelativeHorizontalTo -> x += n.dx
                is PathNode.VerticalTo -> y = n.y
                is PathNode.RelativeVerticalTo -> y += n.dy
                is PathNode.ArcTo -> { x = n.arcStartX; y = n.arcStartY }
                is PathNode.RelativeArcTo -> { x += n.arcStartDx; y += n.arcStartDy }
                PathNode.Close -> { x = startX; y = startY }
                else -> error("node $n not expected in a glyph")
            }
            out += Offset(x, y)
        }
        return out
    }

    /**
     * Play test P34: the roundabout read as the male sign (a ring with an arrow leaving at the upper right, and a stem
     * under it). Now the route goes round the island and leaves straight up; the island's other half is drawn dim.
     */
    @Test
    fun `P34 the roundabout is a road round an island, not a male sign`() {
        val glyphs = mapOf(
            "banner ROUNDABOUT" to DriveIcons.maneuver(ManeuverKind.ROUNDABOUT),
            "banner ROUNDABOUT_EXIT" to DriveIcons.maneuver(ManeuverKind.ROUNDABOUT_EXIT),
            "chip ROUNDABOUT_EXIT" to DriveIcons.maneuverSmall(ManeuverKind.ROUNDABOUT_EXIT),
        )
        for ((name, v) in glyphs) {
            val all = paths(v)
            assertTrue(all.any { it.strokeAlpha < 1f }, "$name: the island is drawn dim")
            val route = all.filter { it.strokeAlpha >= 1f }.flatMap(::points)
            assertTrue(route.isNotEmpty(), "$name: a route drawn at full strength")
            val top = route.minBy { it.y }
            assertEquals(12f, top.x, 0.01f, "$name: the route leaves straight up (top point $top)")
            assertTrue(route.none { it.x > 16.5f && it.y < 8f }, "$name: no arrow leaving at the upper right: $route")
        }
        // the other maneuvers keep their one full-strength drawing
        assertTrue(paths(DriveIcons.maneuver(ManeuverKind.LEFT)).all { it.strokeAlpha == 1f })
    }
}
