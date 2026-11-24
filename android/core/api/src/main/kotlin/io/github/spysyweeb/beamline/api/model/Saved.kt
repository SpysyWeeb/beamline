// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/model/Saved.kt
package io.github.spysyweeb.beamline.api.model

/** Which slot a saved place fills: at most one HOME and one WORK. */
enum class SavedKind { HOME, WORK, OTHER }

/**
 * A place the user saved (saved-places.json, format beamline-saved-places/1, written by
 * :core:search SavedPlaces; docs/architecture-saved-places.md). Self-contained: [point],
 * [placeName], [subtitle] and [categoryId] are copies taken when it was saved or moved, so it
 * survives any pack being rebuilt, replaced or removed; it holds no search id and no pack id.
 * [toString] shows only [id] and [kind], never the name or the point (privacy rule PV2).
 */
data class SavedPlace(
    /** "s" and 16 lower-case hex digits; random; kept by rename and move. */
    val id: String,
    val kind: SavedKind,
    /** What lists, sheets, stops and cards show: "Home", "Work", or the user's own name ("Mom's"). */
    val name: String,
    val point: GeoPoint,
    /** The place's own name when saved or moved ("Erie Maritime Museum"); for a point, its coordinates ("42.08418, -80.17321"). */
    val placeName: String,
    /** The place's own subtitle then ("100 East Front Street, Erie"); null when it had none. */
    val subtitle: String? = null,
    /** The search category of the place then ("museum"); null for addresses, streets and points. */
    val categoryId: String? = null,
) {
    /** The second line: [placeName] unless it equals [name] (ignoring case), then [subtitle], joined with ", "; null when empty. */
    val description: String?
        get() = listOfNotNull(placeName.takeUnless { it.equals(name, ignoreCase = true) }, subtitle)
            .joinToString(", ")
            .ifEmpty { null }

    /** The place to plan to, add as a stop, start from or show in a sheet. */
    fun toPlace(): Place =
        Place(name = name, point = point, source = PlaceSource.SAVED, subtitle = description, categoryId = categoryId, savedId = id)

    override fun toString(): String = "SavedPlace(id=$id, kind=$kind)"
}
