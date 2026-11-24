// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/model/Model.kt
package io.github.spysyweeb.beamline.api.model

import kotlin.math.cos

/** A point in degrees, WGS84. */
data class GeoPoint(val lat: Double, val lon: Double)

/** A box in degrees, in the order pack manifests write it. */
data class Bounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    fun contains(p: GeoPoint): Boolean = p.lon >= west && p.lon <= east && p.lat >= south && p.lat <= north

    /** Grown by [km] on every side (111.32 km per degree of latitude; longitude scaled at the middle latitude). */
    fun grownByKm(km: Double): Bounds {
        val dLat = km / 111.32
        val dLon = km / (111.32 * cos(Math.toRadians((south + north) / 2)).coerceAtLeast(0.01))
        return Bounds(west - dLon, south - dLat, east + dLon, north + dLat)
    }

    companion object {
        /** From a manifest's `[west, south, east, north]`. */
        fun of(wsen: List<Double>): Bounds {
            require(wsen.size == 4) { "bounds need 4 numbers" }
            return Bounds(wsen[0], wsen[1], wsen[2], wsen[3])
        }

        /** The box around [points]; null for none. */
        fun around(points: List<GeoPoint>): Bounds? = if (points.isEmpty()) null else
            Bounds(points.minOf { it.lon }, points.minOf { it.lat }, points.maxOf { it.lon }, points.maxOf { it.lat })
    }
}

/** Where a place came from. */
enum class PlaceSource { SEARCH, RECENT, MAP_POI, MAP_PIN, RESUMED, SAVED }

/**
 * A place the user can go to: a search result, a recent, a POI tapped on the map, a dropped pin, a
 * saved place ([source] SAVED, [savedId] set). [searchId] and [packId] identify a search entry of
 * one pack build (they may not survive a pack rebuild); [name], [subtitle] and [point] are copies
 * and always enough to route and display. [toString] shows only [source] and [savedId], never the
 * name, the subtitle, the point or the search id (privacy rule PV2, docs/architecture-saved-places.md).
 */
data class Place(
    val name: String,
    val point: GeoPoint,
    val source: PlaceSource,
    val subtitle: String? = null,
    val categoryId: String? = null,
    val searchId: Long? = null,
    val packId: String? = null,
    /** The SavedPlace.id this place was made from (SavedPlace.toPlace), or the one it was just saved as
     *  from its own open sheet (SP19, a dropped pin included); null for every other place. */
    val savedId: String? = null,
) {
    override fun toString(): String = "Place(source=$source, savedId=$savedId)"
}
