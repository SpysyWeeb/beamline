package io.github.spysyweeb.beamline.feature.states.map

/**
 * P15 (audit F66): the centre on one axis (map units) that keeps the country in the card. [size]: the
 * map's width or height in units; [viewHalf]: half the card in units at this zoom; [margin]: the fit's
 * 16 dp in units at this zoom. When the card holds the whole map on this axis (1x), the map is centred
 * and does not move; zoomed in, the card's edge stops [margin] past the map's edge. On the axis the fit
 * is limited by, lo and hi are equal at 1x in exact maths but can cross by an ulp in Float either way:
 * within 0.001 units of each other they count as equal.
 */
internal fun clampCentre(c: Float, size: Float, viewHalf: Float, margin: Float): Float {
    val lo = viewHalf - margin
    val hi = size - viewHalf + margin
    return if (lo >= hi - 1e-3f) size / 2 else c.coerceIn(lo, hi)
}

/** P15: the centre on one axis after zooming from [zoomBefore] to [zoomAfter] about [focus] (map units): the point under the finger stays under it. */
internal fun zoomAbout(centre: Float, focus: Float, zoomBefore: Float, zoomAfter: Float): Float = focus + (centre - focus) * zoomBefore / zoomAfter
