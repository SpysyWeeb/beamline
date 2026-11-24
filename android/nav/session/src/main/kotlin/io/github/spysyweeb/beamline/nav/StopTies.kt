package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource

/**
 * How a trip's place is tied to the road (docs/architecture.md 2.11 "Every request" 2; the owner's
 * answers after batch C1): a map point (a dropped pin, also once saved or in the recents: its
 * second line is its coordinates) is not tied; a place whose address is known is tied to the street
 * the address names when the planner finds it within reach ([street]); every other place to the
 * nearest street of residential class or above. A point of interest ([isPointOfInterest]; batch E,
 * docs/next-round.md item 18) ends in its own lot when the planner finds one near it. Only the place's
 * own fields are read; nothing is logged.
 */
internal object StopTies {
    /** "42.08418, -80.17321": the second line of a dropped pin, a saved pin and a recent pin (pointText, AppFlows.coordinates). */
    private val COORDINATES = Regex("""^-?\d{1,3}\.\d+, -?\d{1,3}\.\d+$""")

    /** "155 East 12th Street", "1200-1210 State Street", "12A Main Street": a house number, then a street with a letter in it. */
    private val ADDRESS = Regex("""^\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?\s+(\S.*\p{L}.*)$""")

    fun isMapPoint(p: Place): Boolean = p.source == PlaceSource.MAP_PIN || (p.subtitle?.trim()?.let(COORDINATES::matches) ?: false)

    /**
     * The types a search pack gives its streets (road classes) and its places (city, town, ZIP, ...):
     * not points of interest. Every other search type is a POI's: a category code of the pack's
     * category table ("restaurant", "fuel") or a raw OSM tag ("shop=florist").
     */
    private val STREET_AND_PLACE_TYPES = setOf(
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link", "secondary", "secondary_link",
        "tertiary", "tertiary_link", "unclassified", "residential", "living_street", "service", "pedestrian", "track",
        "busway", "road",
        "city", "town", "village", "hamlet", "borough", "township", "county", "locality", "neighbourhood", "quarter",
        "suburb", "island", "zip", "state", "country", "place",
    )

    /**
     * A point of interest (a business, shop, amenity): a POI tapped on the map ([PlaceSource.MAP_POI]),
     * or a place whose category is a search POI's (a search result, a recent, a resumed trip or a
     * saved place made from one keep that category). Not a map point, not an address result
     * ("address"), not a street or a city, not a place without a category (a saved pin): those keep
     * the street rules.
     */
    fun isPointOfInterest(p: Place): Boolean {
        if (isMapPoint(p)) return false
        if (p.source == PlaceSource.MAP_POI) return true
        val c = p.categoryId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return c != "address" && c !in STREET_AND_PLACE_TYPES
    }

    /**
     * The street the place's address names: an address result's own name ("155 East 12th Street",
     * category "address"), else the first part of its second line that is a house number and a
     * street ("155 East 12th Street, Erie"; a saved place's "Erie Maritime Museum, 150 East Front
     * Street, Erie"). Null when there is none, and for a map point.
     */
    fun street(p: Place): String? {
        if (isMapPoint(p)) return null
        if (p.categoryId == "address") ADDRESS.matchEntire(p.name.trim())?.let { return it.groupValues[1].trim() }
        for (part in p.subtitle.orEmpty().split(",")) {
            ADDRESS.matchEntire(part.trim())?.let { return it.groupValues[1].trim() }
        }
        return null
    }
}
