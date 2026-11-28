package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.ManeuverKind

/**
 * Path data of the maneuver and lane arrows, on the sketch's 24 x 24 grid with round caps and joins
 * (docs/design/turn-banner.html: the main arrow at stroke 2.2, the chip and the lanes at 2.4).
 */
internal object SketchPaths {
    // ---- copied verbatim from docs/design/turn-banner.html (the approved sketch) ----
    val LEFT = listOf("M16 20v-7a4 4 0 0 0-4-4H6", "M9.5 5.5 6 9l3.5 3.5")
    val RIGHT = listOf("M8 20v-7a4 4 0 0 1 4-4h6", "M14.5 5.5 18 9l-3.5 3.5")
    val STRAIGHT = listOf("M12 20V5", "M7.5 9.5 12 5l4.5 4.5")
    val EXIT = listOf("M9 20v-6c0-2 1-3.3 2.4-4.5L17 5", "M11.5 5H17v5.5")
    val LEFT_STRAIGHT = listOf("M14 20V5", "M10.5 8.5 14 5l3.5 3.5", "M14 14H7", "M9.5 11.5 7 14l2.5 2.5")
    val STRAIGHT_RIGHT = listOf("M10 20V5", "M6.5 8.5 10 5l3.5 3.5", "M10 14h7", "M14.5 11.5 17 14l-2.5 2.5")

    // ---- drawn by track D in the sketch's style for the kinds the sketch does not show (drafts until the owner approves) ----
    /** The sketch's exit, mirrored: bear, ramp, exit or fork to the left. */
    val EXIT_LEFT = listOf("M15 20v-6c0-2-1-3.3-2.4-4.5L7 5", "M12.5 5H7v5.5")
    val SHARP_LEFT = listOf("M15 20V6l-8 8", "M7 9v5h5")
    val SHARP_RIGHT = listOf("M9 20V6l8 8", "M17 9v5h-5")
    val UTURN_LEFT = listOf("M16 20V10a4 4 0 0 0-8 0v7", "M4.5 13.5 8 17l3.5-3.5")
    val UTURN_RIGHT = listOf("M8 20V10a4 4 0 0 1 8 0v7", "M12.5 13.5 16 17l3.5-3.5")
    val MERGE = listOf("M12 12V5", "M8.5 8.5 12 5l3.5 3.5", "M6 20c0-4 6-4 6-8", "M18 20c0-4-6-4-6-8")
    val MERGE_LEFT = listOf("M9 20V5", "M5.5 8.5 9 5l3.5 3.5", "M17 20c0-5-8-5-8-9")
    val MERGE_RIGHT = listOf("M15 20V5", "M11.5 8.5 15 5l3.5 3.5", "M7 20c0-5 8-5 8-9")
    /**
     * Play test P34 (the old ring with a diagonal arrow read as the male sign): the route round the island, counter-
     * clockwise as U.S. roundabouts go, entering from below past its right side and leaving straight ahead.
     */
    val ROUNDABOUT = listOf("M12 21v-4.5a4 4 0 0 0 0-8V3", "M8.5 6.5 12 3l3.5 3.5")
    /** The island under [ROUNDABOUT], drawn at [DriveIcons.DIM_ALPHA]: its other half is the road not taken. */
    val ROUNDABOUT_ISLAND = listOf("M8 12.5a4 4 0 1 0 8 0a4 4 0 1 0-8 0")
    val ARRIVE = listOf("M12 20v-8.5", "M8.5 7.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0")
    val ARRIVE_LEFT = listOf("M15 20V5", "M4.5 11a3 3 0 1 0 6 0a3 3 0 1 0-6 0")
    val ARRIVE_RIGHT = listOf("M9 20V5", "M13.5 11a3 3 0 1 0 6 0a3 3 0 1 0-6 0")
    val FERRY = listOf("M4 14h16l-2.5 5h-11z", "M8 14v-4h8v4", "M12 10V6")
    /** A lane with no painted arrow: the lane line alone. */
    val LANE_PLAIN = listOf("M12 20V6")

    // ---- screen icons of the overlay (24-unit grid, drawn here like :ui:theme's BeamlineIcons) ----
    val CLOSE = listOf("M6 6l12 12", "M18 6 6 18")
    val UP = listOf("M6 15l6-6 6 6")
    val DOWN = listOf("M6 9l6 6 6-6")
    val RECENTER = listOf("M12 3 5 20l7-4 7 4z")
    val OVERVIEW = listOf("M6 19c0-6 12-4 12-11", "M4.5 19a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0", "M16.5 6a1.5 1.5 0 1 0 3 0a1.5 1.5 0 1 0-3 0")
    val STOPS = listOf("M9 6h11", "M9 12h11", "M9 18h11", "M4 6h.01", "M4 12h.01", "M4 18h.01")
    val WARNING = listOf("M12 3 2 20h20z", "M12 10v4", "M12 17h.01")
    /** P21: the step view's Previous and Next. */
    val PREVIOUS = listOf("M15 6l-6 6 6 6")
    val NEXT = listOf("M9 6l6 6-6 6")
}

/** Vector icons from [SketchPaths]; tinted by whoever shows them (Icon tint). */
internal object DriveIcons {
    /** The [dim] paths are drawn first, at [DIM_ALPHA] (an Icon's tint keeps the source alpha), then [paths] at full strength. */
    private fun icon(name: String, paths: List<String>, strokeWidth: Float, dim: List<String> = emptyList()): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for ((d, alpha) in dim.map { it to DIM_ALPHA } + paths.map { it to 1f }) {
                addPath(
                    pathData = addPathNodes(d),
                    stroke = SolidColor(Color.Black),
                    strokeAlpha = alpha,
                    strokeLineWidth = strokeWidth,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** Play test P34: the alpha of a glyph's dim part (the roundabout's island). */
    internal const val DIM_ALPHA = 0.4f

    /** Stroke of the banner's main arrow, of the chip's and the lanes' arrows, of the screen icons (sketch: 2.2 and 2.4). */
    const val MAIN_STROKE = 2.2f
    const val SMALL_STROKE = 2.4f
    private const val UI_STROKE = 2f

    private fun maneuverPaths(kind: ManeuverKind): List<String> = when (kind) {
        ManeuverKind.DEPART, ManeuverKind.STRAIGHT, ManeuverKind.RAMP_STRAIGHT, ManeuverKind.FORK_STRAIGHT, ManeuverKind.OTHER -> SketchPaths.STRAIGHT
        ManeuverKind.ARRIVE -> SketchPaths.ARRIVE
        ManeuverKind.ARRIVE_LEFT -> SketchPaths.ARRIVE_LEFT
        ManeuverKind.ARRIVE_RIGHT -> SketchPaths.ARRIVE_RIGHT
        ManeuverKind.LEFT -> SketchPaths.LEFT
        ManeuverKind.RIGHT -> SketchPaths.RIGHT
        ManeuverKind.SLIGHT_LEFT, ManeuverKind.RAMP_LEFT, ManeuverKind.EXIT_LEFT, ManeuverKind.FORK_LEFT -> SketchPaths.EXIT_LEFT
        ManeuverKind.SLIGHT_RIGHT, ManeuverKind.RAMP_RIGHT, ManeuverKind.EXIT_RIGHT, ManeuverKind.FORK_RIGHT -> SketchPaths.EXIT
        ManeuverKind.SHARP_LEFT -> SketchPaths.SHARP_LEFT
        ManeuverKind.SHARP_RIGHT -> SketchPaths.SHARP_RIGHT
        ManeuverKind.UTURN_LEFT -> SketchPaths.UTURN_LEFT
        ManeuverKind.UTURN_RIGHT -> SketchPaths.UTURN_RIGHT
        ManeuverKind.MERGE -> SketchPaths.MERGE
        ManeuverKind.MERGE_LEFT -> SketchPaths.MERGE_LEFT
        ManeuverKind.MERGE_RIGHT -> SketchPaths.MERGE_RIGHT
        ManeuverKind.ROUNDABOUT, ManeuverKind.ROUNDABOUT_EXIT -> SketchPaths.ROUNDABOUT
        ManeuverKind.FERRY -> SketchPaths.FERRY
    }

    /** What a maneuver's glyph draws dim under it (P34: the roundabout's island). */
    private fun dimPaths(kind: ManeuverKind): List<String> = when (kind) {
        ManeuverKind.ROUNDABOUT, ManeuverKind.ROUNDABOUT_EXIT -> SketchPaths.ROUNDABOUT_ISLAND
        else -> emptyList()
    }

    private val mainCache = HashMap<ManeuverKind, ImageVector>()
    private val smallCache = HashMap<ManeuverKind, ImageVector>()
    private val laneCache = HashMap<Set<LaneArrow>, ImageVector>()

    /** The banner's big arrow. */
    fun maneuver(kind: ManeuverKind): ImageVector =
        synchronized(mainCache) { mainCache.getOrPut(kind) { icon("maneuver_${kind.name.lowercase()}", maneuverPaths(kind), MAIN_STROKE, dimPaths(kind)) } }

    /** The chip's arrow (thicker, as the sketch draws it at 28 dp). */
    fun maneuverSmall(kind: ManeuverKind): ImageVector =
        synchronized(smallCache) { smallCache.getOrPut(kind) { icon("then_${kind.name.lowercase()}", maneuverPaths(kind), SMALL_STROKE, dimPaths(kind)) } }

    /** The arrows painted on one lane. */
    fun lane(arrows: Set<LaneArrow>): ImageVector =
        synchronized(laneCache) { laneCache.getOrPut(arrows) { icon("lane_" + arrows.sorted().joinToString("_") { it.name.lowercase() }, lanePaths(arrows), SMALL_STROKE) } }

    /**
     * One arrow alone takes its own shape; left with through and through with right are the sketch's
     * two drawings; any other mix is a through stem (headed only when THROUGH is painted) with a
     * branch per turn arrow at two thirds of its height.
     */
    fun lanePaths(arrows: Set<LaneArrow>): List<String> {
        val painted = arrows - LaneArrow.NONE
        if (painted.isEmpty()) return SketchPaths.LANE_PLAIN
        if (painted.size == 1) return when (painted.single()) {
            LaneArrow.THROUGH -> SketchPaths.STRAIGHT
            LaneArrow.LEFT -> SketchPaths.LEFT
            LaneArrow.RIGHT -> SketchPaths.RIGHT
            LaneArrow.SLIGHT_LEFT -> SketchPaths.EXIT_LEFT
            LaneArrow.SLIGHT_RIGHT -> SketchPaths.EXIT
            LaneArrow.SHARP_LEFT -> SketchPaths.SHARP_LEFT
            LaneArrow.SHARP_RIGHT -> SketchPaths.SHARP_RIGHT
            LaneArrow.REVERSE -> SketchPaths.UTURN_LEFT
            LaneArrow.MERGE_TO_LEFT -> SketchPaths.MERGE_LEFT
            LaneArrow.MERGE_TO_RIGHT -> SketchPaths.MERGE_RIGHT
            LaneArrow.NONE -> SketchPaths.LANE_PLAIN
        }
        if (painted == setOf(LaneArrow.LEFT, LaneArrow.THROUGH)) return SketchPaths.LEFT_STRAIGHT
        if (painted == setOf(LaneArrow.THROUGH, LaneArrow.RIGHT)) return SketchPaths.STRAIGHT_RIGHT
        return listOf(stem(painted)) + painted.sorted().flatMap(::branch)
    }

    /**
     * The stem of a lane of several arrows (the owner's screenshot, 2026-11-25: "the arrows are
     * meshing together"): the arrows that go up (straight, bear, merge, U-turn) leave it at its top
     * (y 12), the left and right turns lower down (y 17), so their heads stay apart as on the signs.
     */
    private fun stem(painted: Set<LaneArrow>): String =
        if (painted.any { it in UPWARD }) "M12 21V12" else "M12 21V17"

    private val UPWARD = setOf(
        LaneArrow.THROUGH, LaneArrow.SLIGHT_LEFT, LaneArrow.SLIGHT_RIGHT,
        LaneArrow.MERGE_TO_LEFT, LaneArrow.MERGE_TO_RIGHT, LaneArrow.REVERSE,
    )

    /** One painted arrow of a lane drawn on the stem's top ("M12 20v-7"): a branch, or the through arrow's upper half. */
    private fun branch(a: LaneArrow): List<String> = when (a) {
        LaneArrow.THROUGH -> listOf("M12 12V4", "M8.5 7.5 12 4l3.5 3.5")
        LaneArrow.LEFT -> listOf("M12 17H5", "M7.5 14.5 5 17l2.5 2.5")
        LaneArrow.RIGHT -> listOf("M12 17h7", "M16.5 14.5 19 17l-2.5 2.5")
        LaneArrow.SLIGHT_LEFT -> listOf("M12 12 6 6", "M6 10V6h4")
        LaneArrow.SLIGHT_RIGHT -> listOf("M12 12l6-6", "M18 10V6h-4")
        LaneArrow.SHARP_LEFT -> listOf("M12 15l-6 5", "M6 15.5V20h4.5")
        LaneArrow.SHARP_RIGHT -> listOf("M12 15l6 5", "M18 15.5V20h-4.5")
        LaneArrow.REVERSE -> listOf("M12 12V9a3 3 0 0 0-6 0v6", "M3.5 12.5 6 15l2.5-2.5")
        LaneArrow.MERGE_TO_LEFT -> listOf("M12 12 7 7")
        LaneArrow.MERGE_TO_RIGHT -> listOf("M12 12l5-5")
        LaneArrow.NONE -> emptyList()
    }

    private val splitCache = HashMap<Pair<Set<LaneArrow>, LaneArrow>, Pair<ImageVector, ImageVector>>()

    /**
     * The lane strip (item 45): a lane of several arrows as two drawings, the stem with the arrow the
     * route uses ([used]) and the lane's other arrows, so the strip draws the others dim (the owner's
     * signs, 2026-11-25: "left or bear left" with the route bearing left). One arrow, or none used:
     * the whole lane first and null.
     */
    fun laneSplit(arrows: Set<LaneArrow>, used: LaneArrow?): Pair<ImageVector, ImageVector?> {
        val painted = arrows - LaneArrow.NONE
        if (used == null || used !in painted || painted.size < 2) return lane(arrows) to null
        return synchronized(splitCache) {
            splitCache.getOrPut(painted to used) {
                val name = "lane_" + painted.sorted().joinToString("_") { it.name.lowercase() } + "_used_" + used.name.lowercase()
                icon(name, listOf(stem(painted)) + branch(used), SMALL_STROKE) to
                    icon(name + "_other", painted.sorted().filter { it != used }.flatMap(::branch), SMALL_STROKE)
            }
        }
    }

    val close = icon("close", SketchPaths.CLOSE, UI_STROKE)
    val up = icon("up", SketchPaths.UP, UI_STROKE)
    val down = icon("down", SketchPaths.DOWN, UI_STROKE)
    val recenter = icon("recenter", SketchPaths.RECENTER, UI_STROKE)
    val overview = icon("overview", SketchPaths.OVERVIEW, UI_STROKE)
    val stops = icon("stops", SketchPaths.STOPS, UI_STROKE)
    val warning = icon("warning", SketchPaths.WARNING, UI_STROKE)
    val previous = icon("previous", SketchPaths.PREVIOUS, UI_STROKE)
    val next = icon("next", SketchPaths.NEXT, UI_STROKE)
    /** Three dots, filled: the overflow menu. */
    val more: ImageVector = ImageVector.Builder("more", 24.dp, 24.dp, 24f, 24f).apply {
        for (y in listOf(5f, 12f, 19f)) {
            addPath(
                pathData = addPathNodes("M10.2 ${y}a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0z"),
                fill = SolidColor(Color.Black),
            )
        }
    }.build()
}
