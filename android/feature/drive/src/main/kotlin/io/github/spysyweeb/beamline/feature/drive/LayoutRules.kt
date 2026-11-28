package io.github.spysyweeb.beamline.feature.drive

import kotlin.math.abs
import kotlin.math.floor
import io.github.spysyweeb.beamline.api.nav.LaneMark

// Two layout rules that are plain arithmetic, kept apart from the composables so the JVM tests can
// call them (a file with an Android call in a top-level value cannot be loaded there).

/** The size of one lane box, the gap between two and the arrow inside, in dp. */
internal data class LaneMetrics(val widthDp: Float, val gapDp: Float, val iconDp: Float)

/** The sketch's lane: 40 x 44 dp boxes 6 dp apart with a 26 dp arrow (docs/design/turn-banner.html). */
internal val SKETCH_LANE = LaneMetrics(widthDp = 40f, gapDp = 6f, iconDp = 26f)

/**
 * The lane boxes for [count] lanes in [availableDp]: the sketch's size while they fit; otherwise every
 * box narrower by the same amount (the gap down to 2 dp once a box would be under 24 dp), the arrow
 * shrinking with its box. Every lane is always shown whole: the best one is often the right-most, and
 * a row of fixed boxes squeezed the last ones to nothing (7 lanes in the 320 dp side panel, 8 on a
 * 360 dp phone). With [pxPerDp] (the screen's density) it counts in whole pixels as Compose lays out:
 * each box and gap is rounded to whole px there, and 6 dp gaps of 15.75 px each rounded up to 16 px
 * pushed the eighth lane 3-4 px past the row on the emulator.
 */
internal fun laneMetrics(availableDp: Float, count: Int, pxPerDp: Float? = null): LaneMetrics {
    if (count <= 0) return SKETCH_LANE
    val n = count.toFloat()
    val d = pxPerDp ?: 1f
    fun units(dp: Float) = if (pxPerDp == null) dp else Math.round(dp * d).toFloat()
    val available = availableDp * d
    if (n * units(SKETCH_LANE.widthDp) + (n - 1) * units(SKETCH_LANE.gapDp) <= available + 0.01f) return SKETCH_LANE
    var gap = units(SKETCH_LANE.gapDp)
    var width = (available - (n - 1) * gap) / n
    if (width < 24f * d) {
        gap = units(2f)
        width = (available - (n - 1) * gap) / n
    }
    if (pxPerDp != null) width = floor(width + 0.001f)
    width = width.coerceAtLeast(0f)
    val widthDp = width / d
    return LaneMetrics(widthDp = widthDp, gapDp = gap / d, iconDp = minOf(SKETCH_LANE.iconDp, widthDp * SKETCH_LANE.iconDp / SKETCH_LANE.widthDp))
}

/**
 * A block of the route sheet's scrolling part in px from the part's top: [top] inclusive, [bottom]
 * exclusive; [padTop] and [padBottom] are its empty padding, which may be cut without cutting the block.
 */
internal data class Span(val top: Int, val bottom: Int, val padTop: Int = 0, val padBottom: Int = 0)

/**
 * Where the route sheet's scrolling part should come to rest so that no stop line is cut at its top
 * edge (under the pinned start row a cut line's second line read as the start's). [top] is the scroll
 * offset, [viewport] the part's visible height, [maxScroll] the furthest it scrolls; [lines] the stop
 * lines (the "To" label counts with the first); [card] the selected card, if any. A cut inside a line's
 * padding is no cut (the text is whole). The nearer edge of a cut line wins (the line whole, or wholly
 * scrolled away), unless that would cut the selected card while it is whole now: the card stays whole.
 * Null: nothing to move.
 */
internal fun sheetRestingScroll(top: Int, viewport: Int, maxScroll: Int, lines: List<Span>, card: Span?): Int? {
    val cut = lines.firstOrNull { top > it.top + it.padTop && top < it.bottom - it.padBottom } ?: return null
    val cardWhole = card != null && card.top >= top && card.bottom <= top + viewport
    fun keepsCard(t: Int) = !cardWhole || (card.top >= t && card.bottom <= t + viewport)
    return listOf(cut.top, cut.bottom)
        .filter { it in 0..maxScroll }
        .sortedBy { abs(it - top) }
        .firstOrNull(::keepsCard)
}

/**
 * P52 (review, 0.1.40): the selected card as [sheetRestingScroll] keeps it: with [roomBelow] (the fade over the part's
 * last lines) under it while that fits in the part at [top], so a card brought into view clear of the fade is not
 * moved back under it; otherwise the card alone (kept whole, as before).
 */
internal fun cardWithRoom(card: Span?, roomBelow: Int, top: Int, viewport: Int): Span? {
    if (card == null) return null
    val room = card.copy(bottom = card.bottom + roomBelow)
    return if (room.top >= top && room.bottom <= top + viewport) room else card
}

/**
 * Rule D10 (review, 0.1.40): the route sheet now shows a new trip, not a change of the one it showed: a trip planned
 * while a preview shows (PLANNING to PLANNING: a map place's Directions, a link handed over by another app) has none
 * of the stops shown [before] and no routes yet ([hasRoutes] false; a change of the stops, a swap of the ends or
 * Change start is replanned with the old routes still shown). Stops are compared by a key of each (its words).
 */
internal fun newTripShown(before: List<Int>, now: List<Int>, hasRoutes: Boolean): Boolean =
    before.isNotEmpty() && now.isNotEmpty() && !hasRoutes && now.none { it in before }

/** Item 45: a lane row becomes the strip when a lane is wrong for the route and another is right (a choice to make). */
internal fun laneChoice(lanes: List<LaneUi>): Boolean =
    lanes.any { it.mark == LaneMark.NOT_FOR_ROUTE } && lanes.any { it.mark != LaneMark.NOT_FOR_ROUTE }

/**
 * Item 54b (the play test of 0.1.41): the banner as the overlay draws it. On its side ([side]) while the stop results'
 * window or the Add stop search's panel is in the start panel under it ([stopWindow], `DriveOverlay(stopWindowDp)`),
 * the banner leaves out its lane row and the lane note under it (rules B5, D9, D9b): with them only the field and one
 * row fitted under the banner. Upright, and once the window or the panel goes, the banner as it is.
 */
internal fun bannerShown(banner: BannerUi, side: Boolean, stopWindow: Boolean): BannerUi =
    if (side && stopWindow) banner.copy(lanes = null, laneNote = null, laneDistanceM = null) else banner
