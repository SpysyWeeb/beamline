package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.search.AddressInfo
import io.github.spysyweeb.beamline.search.Geo
import io.github.spysyweeb.beamline.search.RuleFiles
import io.github.spysyweeb.beamline.search.SearchResult
import io.github.spysyweeb.beamline.search.saved.LoadProblem
import io.github.spysyweeb.beamline.search.saved.SaveProblem
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Every text the search screen, the place sheet and the saved-places screens show
 * (docs/architecture.md 3.8, docs/architecture-saved-places.md section 2, word for word where the
 * contract gives them; the few the contract leaves open are marked "S's words").
 */
internal object Texts {
    const val NEAR_GPS = "Near your position"
    const val NEAR_MAP = "Near the map center"
    const val OUTSIDE = "Outside the installed maps"
    const val ALREADY_DESTINATION = "Already your destination"
    const val HOME = "Home"
    const val WORK = "Work"
    const val SET_HOME = "Set home"
    const val SET_WORK = "Set work"
    const val SAVE_PLACE = "Save place"
    const val SAVED = "Saved"
    const val YOUR_POSITION = "Your position"
    const val POINT_ON_MAP = "Point on the map"
    const val NONE_YET = "None yet"
    const val EXACT_ADDRESS = "Exact address"
    const val STREET_ONLY = "Street only"
    /** S's words (the contract names "Exact address" and "Street only" only). */
    const val ESTIMATED_ADDRESS = "Estimated address"
    /** S's words for NO_RESULTS and NO_SEARCH_DATA (the contract gives the phases, not the lines). */
    const val NO_RESULTS = "No places found"
    const val NO_SEARCH_DATA = "Search needs a state on this phone. Add a state to search its places and addresses."
    /**
     * NO_SEARCH_DATA when states are installed but none can be searched (a search part P marked
     * unusable, or one the search refused at open: another address normaliser, no fingerprint, a
     * file that cannot be opened). The button stays "Add a state" (3.8 S7): the States screen is
     * where a state is updated or added. S's words.
     */
    const val SEARCH_UNUSABLE = "Search can't use the installed maps. Update them, or add a state, on the States screen."
    const val BROKEN_NOTICE = "Your saved places could not be read, so the list starts empty. The old file is kept on this phone."
    const val NEWER_NOTICE = "These saved places were made by a newer Beamline. Update the app to see and change them."
    const val WRITE_FAILED = "Could not save. The phone may be out of space."

    // the saved places screen, its menu and dialogs, the map pick (2.2-2.6, SP16, SP24)
    const val SAVED_PLACES = "Saved places"
    const val SHOW_ON_MAP = "Show on the map"
    const val ADD_A_PLACE = "Add a place"
    const val LIST_EMPTY_HINT = "Places you save from search or the map show here."
    const val CHANGE_LOCATION = "Change location"
    const val RENAME = "Rename"
    const val MAKE_HOME = "Make home"
    const val MAKE_WORK = "Make work"
    const val REMOVE = "Remove"
    const val REMOVE_HOME = "Remove home"
    const val REMOVE_WORK = "Remove work"
    const val SAVE = "Save"
    const val REPLACE = "Replace"
    const val OTHER = "Other"
    const val NO_POINT_YET = "No point chosen yet"
    const val USE_THIS_POINT = "Use this point"
    const val ZOOM_IN = "Zoom in to choose the exact spot."
    const val ADD_THIS_STATE = "Add this state to choose a point here."
    const val USE_MY_POSITION = "Use my position"
    const val CHOOSE_ON_MAP = "Choose on the map"
    const val ADD_STATE_BUTTON = "Add a state"
    const val SEARCH_HINT = "Search places or addresses"
    /** The map's bar while a search's results show: what a screen reader says for its words and for its X. */
    fun searchAgain(query: String) = "Search: $query"
    const val CLEAR_SEARCH = "Clear search"
    const val CANCEL = "Cancel"
    const val OK = "OK"
    const val NAME = "Name"
    /** P57 (0.1.40): the Name field's drawn hint in the Save and Rename dialogs (its label stays NAME). */
    const val NAME_HINT = "Name this place"
    const val BACK = "Back"
    const val DIRECTIONS = "Directions"
    /** Rule S10 (batch B): the place sheet's buttons; the dialer and the browser open only on a tap. */
    const val CALL = "Call"
    const val WEBSITE = "Website"
    /** Batch O1 (docs/online-design.md 4.1): a dropped pin's button, and the line at a place's details height. */
    const val REPORT_HERE = "Report a problem here"
    const val REPORT = "Report a problem"
    // Batch O2 (docs/online-design.md 4.2, 4.5)
    const val EDIT_OSM = "Edit on OpenStreetMap"
    // Batch O3 (docs/online-design.md 4.3; docs/next-round.md items 26 and 27)
    const val OWN_TAG = "Your edit · waiting for the map update"
    const val CLOSED_PERMANENTLY = "Closed permanently"
    const val ADD_PLACE = "Add a missing place"
    const val VIEW_ALL = "View all"
    const val ALL_TYPES = "All place types"
    // 0.1.41 (docs/online-design.md 4.8, item 53c): at the place, the window's details height under the week
    const val HOURS_STILL_RIGHT = "Hours still right?"
    const val HOURS_YES = "Yes"
    const val HOURS_EDIT = "Edit hours"
    const val HOURS_CONFIRMED = "Hours confirmed"

    /** The plain heading of a category group of packbuilder/search/categories.json ("food": "Food and drink"). */
    fun typeGroup(group: String?): String = when (group) {
        "road" -> "On the road"
        "car" -> "For the car"
        "food" -> "Food and drink"
        "shop" -> "Shops"
        "money" -> "Money"
        "health" -> "Health"
        "lodging" -> "Places to stay"
        "services" -> "Public services"
        "outdoors" -> "Outdoors"
        "fun" -> "Things to do"
        "community" -> "Community"
        "transport" -> "Getting around"
        else -> "More"
    }
    /** Rule S11: the results list's close button, and its count line ("12 places"). */
    const val CLOSE_RESULTS = "Close results"
    fun resultsCount(n: Int) = if (n == 1) "1 place" else "$n places"
    /** Rule D10: what a screen reader calls the handles of the results list and of the place sheet. */
    const val RESULTS_LIST = "Results list"
    const val PLACE_DETAILS = "Place details"
    /** Batch G (item 20): the captions of the place sheet's details height, the long press's note and the screen reader's action names. */
    const val HOURS = "Hours"
    const val PHONE = "Phone"
    const val WEBSITE_CAPTION = "Website"
    const val ADDRESS = "Address"
    const val COPIED = "Copied"
    const val COPY = "Copy"
    const val OPEN_WEBSITE = "Open the website"
    const val SHOW_DETAILS = "Show all details"
    const val ADD_STOP = "Add stop"
    const val EDIT = "Edit"
    const val RECENT = "Recent"
    const val CLEAR = "Clear"

    // 0.1.40, play test P45: clearing the recents can be undone; the buttons name what they act on (audit F49)
    const val RECENTS_CLEARED = "Recent places cleared"
    const val UNDO = "Undo"
    const val CLEAR_RECENTS = "Clear recent places"
    fun removeRecent(name: String) = "Remove $name from recent places"

    // 0.1.40, play test P47 and P44 (audit F50): a next step after no results; the field's own clear button
    const val NO_RESULTS_HINT = "Check the spelling, or try fewer words."
    const val CLEAR_TEXT = "Clear text"

    /** 0.1.40, play test P2: the heading over the rows that only matched by name ("Erie Animal Hospital" for "hospital"). */
    const val OTHER_MATCHES = "Other matches"

    // 0.1.40, play test P22: SET_START is a titled picker with "Your position" (YOUR_POSITION) and "Choose on the map"
    const val CHOOSE_START = "Choose start"
    const val START_PROMPT = "Tap the map where the trip starts"
    const val START_HERE = "Use as start"

    // Item 51 (rule S12): a new stop along the trip's route, the time it adds, its card
    const val ALONG_ROUTE = "Along your route"
    fun alongAfter(stop: String) = "Along your route after $stop"
    const val ALONG_NONE_CLOSE = "None close to your route. Nearest shown."
    const val WORKING_DETOURS = "Working out detours..."
    const val DETOURS_SLOW = "Detours took too long. Nearest first."
    const val DETOURS_NONE = "Detours could not be worked out. Nearest first."
    const val ON_THE_WAY = "On the way"
    const val WORKING_ROUTE = "Working out the route..."
    const val PREVIEW_FAILED = "Could not work out the route with this stop."
    const val ARRIVE = "Arrive "
    fun goesAfter(stop: String) = "Goes after $stop"
    /** The card's button as a screen reader says it. */
    fun addStopLabel(title: String) = "Add $title as a stop"
    /** Item 52 (rule H7): the second button of a handed-over place's stop card. */
    const val GO_INSTEAD = "Go here instead"
    /** Item 52: what a screen reader says for it. */
    fun goInsteadLabel(title: String) = "Go to $title instead of your destination"
    /** The card's time line as a screen reader says it (the dot is not read): "Adds 4 min, arrive 4:33 PM"; "On the way, arrive 4:33 PM". */
    fun addedWords(added: String?, arrive: String?): String = listOfNotNull(
        added?.let { if (it.startsWith("+")) "Adds " + it.drop(1) else it },
        arrive?.let { if (added != null) it.replaceFirstChar(Char::lowercaseChar) else it },
    ).joinToString(", ")
    const val BACK_TO_LIST = "Back to the list"

    fun listLimit(max: Int) = "You can save $max places besides Home and Work. Remove one to add another."
    fun editLabel(name: String) = "Edit $name"
    fun kindLabel(k: SavedKind) = when (k) {
        SavedKind.HOME -> HOME
        SavedKind.WORK -> WORK
        SavedKind.OTHER -> OTHER
    }
    fun replaces(k: SavedKind, description: String) =
        if (k == SavedKind.HOME) "This replaces your home: $description." else "This replaces your work: $description."

    fun savedPlacesCount(n: Int) = when (n) {
        0 -> NONE_YET
        1 -> "1 place"
        else -> "$n places"
    }

    fun newLocationFor(name: String) = "New location for $name"
    fun savedAs(name: String) = "Saved as $name"
    fun alreadySavedAs(name: String) = "This place is already saved as $name."

    fun notice(p: LoadProblem?): String? = when (p) {
        LoadProblem.BROKEN -> BROKEN_NOTICE
        LoadProblem.NEWER -> NEWER_NOTICE
        null -> null
    }

    /** P6 (0.1.40): "Near Corry". */
    fun near(name: String) = "Near $name"
    /** P6 (0.1.40): after the map centre's line when there is no fresh fix (S1's fallback): why the search is not around the user. */
    const val NO_POSITION = "no position yet"

    /**
     * S1, P6: GPS "Near your position"; the map's centre "Near <town>" when [area] is known, else "Near the
     * map center"; without the user's position ([SearchContext.distanceFrom] null: no fresh fix, not S1b)
     * followed by " · no position yet".
     */
    fun nearText(c: SearchContext, area: String? = null): String? = when (c.source) {
        NearSource.GPS -> NEAR_GPS
        NearSource.MAP_CENTER -> (area?.let(::near) ?: NEAR_MAP).let { if (c.distanceFrom == null) "$it · $NO_POSITION" else it }
        NearSource.NONE -> null
    }

    /** A refusal of a save, rename or change of location as the dialogs show it (2.3). */
    fun problem(p: SaveProblem, name: String = "", renaming: Boolean = false, already: String? = null, maxChars: Int = 40, maxOther: Int = 100): String = when (p) {
        SaveProblem.NAME_EMPTY -> "Type a name."
        SaveProblem.NAME_NO_LETTERS -> "Use at least one letter or number."
        SaveProblem.NAME_TOO_LONG -> "Up to $maxChars characters."
        SaveProblem.NAME_TAKEN -> "You already have a place called $name."
        SaveProblem.NAME_RESERVED -> reserved(name, renaming)
        SaveProblem.ALREADY_SAVED -> alreadySavedAs(already ?: name)
        SaveProblem.LIMIT -> "You can save $maxOther places besides Home and Work. Remove one to save another."
        SaveProblem.WRITE_FAILED -> WRITE_FAILED
        SaveProblem.NOT_FOUND, SaveProblem.READ_ONLY -> WRITE_FAILED_OR_GONE
    }

    /** S's words for a place that disappeared under an open dialog, or a read-only store. */
    const val WRITE_FAILED_OR_GONE = "This place can't be changed now."

    private fun reserved(name: String, renaming: Boolean): String {
        val home = io.github.spysyweeb.beamline.search.saved.savedNameKey(name) == "home"
        return when {
            renaming && home -> "Use Make home to make this your home."
            renaming -> "Use Make work to make this your work."
            home -> "Choose Home above to save your home."
            else -> "Choose Work above to save your work."
        }
    }
}

/** Icons (sprite names of the style bundle): categories by assets/poi_icons.json (copied into :core:search's rules). */
internal object Icons {
    const val SAVED_HOME = "saved_home"
    const val SAVED_WORK = "saved_work"
    const val SAVED_PLACE = "saved_place"
    /** S's choices for rows without a POI category: a town, a street, an address, a POI of no category. */
    const val PLACE = "townspot"
    const val STREET = "arrow"
    const val ADDRESS = "building"
    const val OTHER = "building"

    private val categories: Map<String, String> by lazy {
        val o = Json.parseToJsonElement(RuleFiles.text(RuleFiles.POI_ICONS)).jsonObject["categories"]!!.jsonObject
        o.filterKeys { !it.startsWith("_") }.mapValues { (it.value as JsonPrimitive).content }
    }

    fun forKind(k: SavedKind) = when (k) {
        SavedKind.HOME -> SAVED_HOME
        SavedKind.WORK -> SAVED_WORK
        SavedKind.OTHER -> SAVED_PLACE
    }

    fun forCategory(categoryId: String?): String? = categoryId?.let { categories[it] }

    /** The place where two streets meet ([SearchEngine.CROSSING_TYPE]): a point on the map, not a building or a road. */
    private fun isCrossing(categoryId: String?) = categoryId == io.github.spysyweeb.beamline.search.SearchEngine.CROSSING_TYPE

    fun forResult(r: SearchResult): String = forCategory(r.categoryId) ?: when (r.kind) {
        SearchResult.Kind.PLACE -> PLACE
        SearchResult.Kind.STREET if isCrossing(r.categoryId) -> PLACE
        SearchResult.Kind.STREET -> if (r.address != null) ADDRESS else STREET
        SearchResult.Kind.ADDRESS -> ADDRESS
        SearchResult.Kind.POI -> OTHER
    }

    fun forPlace(p: Place, saved: SavedPlace?): String = when {
        p.source == PlaceSource.SAVED && saved != null -> forKind(saved.kind)
        // a point dropped on the map is a point, not a building (the owner, 2026-11-21): the style's spot image (a pin
        // of its own would be new artwork; the saved-place star would say "saved")
        p.source == PlaceSource.MAP_PIN -> PLACE
        isCrossing(p.categoryId) -> PLACE
        else -> forCategory(p.categoryId) ?: OTHER
    }
}

internal fun covered(set: InstalledSet, p: GeoPoint): Boolean = set.routing?.covers(p) == true

/** The distance text: only with a GPS position (SP10). */
internal fun distanceText(c: SearchContext, p: GeoPoint, units: UnitFormatter): String? {
    // S1b: searching the map's area, distances are still from the user
    val n = c.distanceFrom ?: (if (c.source == NearSource.GPS) c.near else null) ?: return null
    return units.distance(Geo.haversineKm(n.lat, n.lon, p.lat, p.lon) * 1000.0)
}

/** The engine's note of an address row: how precise the typed house number is. */
internal fun addressNote(a: AddressInfo?): String? = when (a?.accuracy) {
    AddressInfo.Accuracy.POINT -> Texts.EXACT_ADDRESS
    AddressInfo.Accuracy.INTERPOLATED -> Texts.ESTIMATED_ADDRESS
    AddressInfo.Accuracy.STREET -> Texts.STREET_ONLY
    null -> null
}
