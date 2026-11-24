package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraTarget

/**
 * Rule K1's "today's z9.99 cap outside the states kept" (docs/architecture.md 3.7), which
 * [CameraDirector] cannot apply itself: its constructor (a frozen contract) gets no installed maps,
 * and the cap depends on them. The app applies it to every target of the director before
 * MapController.moveCamera, with the composed map's own rule, `target.withZoomCap(composed::zoomFor)`
 * (:core:packs ComposedMap.zoomFor: the wanted zoom inside a state pack's box, at most just below
 * the overview's last zoom outside every box), and keeps `MapLibreMap.setMaxZoomPreference(composed.maxZoom)`
 * for a map with the overview only, as today's PackMap does.
 *
 * A centre with a zoom is capped at that centre (a my-location follow outside the states asks
 * follow_zoom 16, where only the overview would draw, up to z10: a blank map). A target that keeps
 * the current zoom (zoom null) or fits a box comes back as it is: the box is a route's or the
 * remaining route's, inside the states, and MapLibre's own maximum zoom bounds any fit.
 */
fun CameraTarget.withZoomCap(zoomFor: (lat: Double, lon: Double, wanted: Double) -> Double): CameraTarget {
    val centre = center ?: return this
    val wanted = zoom ?: return this
    val capped = zoomFor(centre.lat, centre.lon, wanted)
    return if (capped < wanted) copy(zoom = capped) else this
}
