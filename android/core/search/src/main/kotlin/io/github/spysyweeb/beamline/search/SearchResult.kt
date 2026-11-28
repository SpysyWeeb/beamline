package io.github.spysyweeb.beamline.search

/** What a result row in a search list needs. Plain data: no Android, no UI. */
data class SearchResult(
    /** Stable id: the entry id in the pack's search database (same id for the same pack build).
     *  Results from the address lookup (no search entry) use a negative id, see [SearchService]. */
    val id: Long,
    val kind: Kind,
    val title: String,
    val subtitle: String,
    /** Category code for a POI in a category ("fuel", "rest_area"), the main OSM tag of a POI in
     *  no category ("shop=hairdresser"), the place type ("city", "zip") or the road class of a
     *  street ("primary"). Icons are mapped from this later. */
    val categoryId: String?,
    /** Human label for [categoryId] ("Gas station", "Hairdresser", "City", "Street"). */
    val categoryLabel: String?,
    val lat: Double,
    val lon: Double,
    /** Distance in km from the search position to (lat, lon), rounded to 0.01 km; null without a position. */
    val distanceKm: Double?,
    val brandId: Long?,
    /** For street results of a query with a house number: whether (lat, lon) is the house. */
    val address: AddressInfo?,
    /** Debugging: score and how the result was found (same values as the Python reference). */
    val score: Double,
    val how: String,
    val tier: String?,
) {
    enum class Kind(val code: String) { PLACE("place"), STREET("street"), POI("poi"), ADDRESS("address") }
}

/** House-number details of an address result. */
data class AddressInfo(
    val houseNumber: String,
    val streetName: String,
    /** POINT: an address point of the pack; INTERPOLATED: placed along a TIGER/OSM range;
     *  STREET: the number was not found, the position is the street (or its nearest numbered point). */
    val accuracy: Accuracy,
    /** False when the typed town or ZIP did not match (the result is a guess elsewhere in the
     *  state); null when no town or ZIP was typed. */
    val localityMatch: Boolean? = null,
    /** True when the typed state is not the pack's state. */
    val stateMismatch: Boolean = false,
) {
    enum class Accuracy(val code: String) { POINT("point"), INTERPOLATED("interpolated"), STREET("street") }
}

/** How the engine read the query (for a "searching for ..." line and for debugging). */
data class ParseLabel(val text: String, val role: String, val match: String? = null,
                      val matches: List<String>? = null, val fuzzy: Boolean? = null)

/**
 * 0.1.40 (play test P6): the place a query's words name ("coffee in corry": Corry), which the engine
 * ranked around (its anchor). [toString] prints none of it (PV2).
 */
data class NamedPlace(val name: String, val lat: Double, val lon: Double) {
    override fun toString(): String = "NamedPlace"
}

data class SearchResponse(
    val query: String,
    val parse: List<ParseLabel>,
    val results: List<SearchResult>,
    val ms: Double,
    /** True: the position is more than coverage_margin_km outside the pack; null: unknown. */
    val outOfCoverage: Boolean?,
    /** True when a position was given but not usable (not finite or out of range). */
    val positionIgnored: Boolean,
    /** The house number the engine read at the start of the query ("4319 peach st"), if any. */
    val houseNumber: String? = null,
    /** 0.1.40 (P6): the place the words name, when they name one ("coffee in corry"); null otherwise. */
    val namedPlace: NamedPlace? = null,
)

/**
 * What OpenStreetMap says of a POI beyond its name (the pack's `poi` table): the raw
 * `opening_hours` text (read with [io.github.spysyweeb.beamline.search.hours.OpeningHours]), the
 * phone numbers and the web addresses as OSM has them (several split by ";"), each null when OSM has
 * none; for the place sheet's details height (batch G, docs/next-round.md item 20) also the postal
 * [address] ("4662 West Ridge Road, Erie, PA 16506", from the `addr:*` tags and the pack's state;
 * null without a street) and the [category] in words ("Convenience store"; null when unknown).
 * [toString] shows none of them (rule PV2: phone numbers and web addresses are never logged).
 * Batch O2: [osm] is the OSM object it was made from, which "Edit on OpenStreetMap" edits.
 */
data class PoiDetails(
    val openingHours: String?,
    val phone: String?,
    val website: String?,
    val address: String? = null,
    val category: String? = null,
    /** Batch O2 (docs/online-design.md 4.2): the OSM object the POI was made from (`poi.osm`, "n25479855"), or null. */
    val osm: String? = null,
    /** 0.1.41: the POI's name in the pack (its OSM name), for a public changeset comment (docs/online-design.md 4.8, AP12); null when none. */
    val name: String? = null,
    /** 0.1.41: the pack's type of the entry, a category code ("fast_food") or a raw tag ("shop=florist"), for the kinds asked about (4.8, AP4); null when none. */
    val type: String? = null,
    /** 0.1.41: the entry's own point in the pack (a node POI: the node's point when the pack was built), for 53a's move check and 4.8's "at the place". */
    val lat: Double? = null,
    val lon: Double? = null,
) {
    /** Hours, phone or website: what the POIs of one name near a point must agree on (S10). */
    val hasContact: Boolean get() = openingHours != null || phone != null || website != null

    override fun toString(): String =
        "PoiDetails(hours=${openingHours != null}, phone=${phone != null}, website=${website != null}, address=${address != null}, category=${category != null})"
}

/**
 * Batch O3 (docs/online-design.md 4.3): a named POI of the pack near a point ([SearchEngine.poisNear]):
 * its entry [id], [name], category [code] and [label], OSM object ([osm], "n123"), point, distance and
 * its pack values. [toString] prints none of them (PV2).
 */
data class NearbyPoi(
    val id: Long,
    val name: String,
    val code: String?,
    val label: String?,
    val osm: String?,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    val openingHours: String? = null,
    val phone: String? = null,
    val website: String? = null,
    val address: String? = null,
) {
    override fun toString(): String = "NearbyPoi"
}

/**
 * The street at a point ([SearchEngine.nearStreet]; docs/next-round.md item 31): the street entry's [id],
 * its [name] and [subtitle] (municipality, county), how far its line is ([distanceM]) and the point of
 * the line. [toString] prints none of them (the point is where the user dropped a pin).
 */
data class NearStreet(
    val id: Long, val name: String, val subtitle: String, val distanceM: Double, val lat: Double, val lon: Double,
    /** The house number of the street's address point nearest to the asked point, when one is near enough (else null). */
    val house: String? = null,
) {
    override fun toString(): String = "NearStreet"
}

/** Batch O3 ("View all", docs/next-round.md item 27): one category of the pack and the [query] a tap types. */
data class CategoryInfo(val code: String, val label: String, val group: String?, val umbrella: Boolean, val query: String)
