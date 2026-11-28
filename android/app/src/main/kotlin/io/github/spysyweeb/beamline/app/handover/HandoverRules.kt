package io.github.spysyweeb.beamline.app.handover

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.app.ui.Layer
import io.github.spysyweeb.beamline.feature.search.NearSource
import io.github.spysyweeb.beamline.feature.search.SearchContext
import io.github.spysyweeb.beamline.feature.search.SearchMode
import io.github.spysyweeb.beamline.search.AddressInfo
import io.github.spysyweeb.beamline.search.Geo
import io.github.spysyweeb.beamline.search.MultiSearchResponse
import io.github.spysyweeb.beamline.search.NearbyPoi
import io.github.spysyweeb.beamline.search.PackResult
import io.github.spysyweeb.beamline.search.SearchResult
import io.github.spysyweeb.beamline.search.address.AddressIndex
import io.github.spysyweeb.beamline.search.foldText
import java.util.Locale

/**
 * Item 52 (docs/architecture.md 3.11, rules H3-H7): the decisions about a link handed over by another app, pure and
 * JVM-tested (HandoverRulesTest); AppFlows and MainActivity wire them. Nothing here logs, and nothing a decision
 * holds is printed (PV2).
 */
object HandoverRules {
    /** H5: a pack's place of the label's name this close to the link's point is that place. */
    const val LABEL_POI_M = 50.0
    /** H5: an unlabelled point is named by the only named place this close. */
    const val POI_NAME_M = 30.0
    /** H6/H7: a namesake or another address farther than this is another place; a place this close to the destination is the destination. */
    const val SAME_PLACE_M = 150.0
    /** H4 (K5): how long a link may wait for the map (a form of the user's open). */
    const val LINK_WAIT_MS = 120_000L
    /** H4 (K5): how long a link that came during the first-run steps (driver notice, online choice, picker) may wait. */
    const val FIRST_RUN_WAIT_MS = 600_000L

    /** H7: what a handed-over place opens. */
    enum class Act { PREVIEW, STOP_CARD, ALREADY, WINDOW, NOTE }

    private fun guiding(phase: Phase) = phase == Phase.GUIDING || phase == Phase.REROUTING

    /**
     * H7 (with the critiques' K2): what a handed-over place opens in [phase]. [inside]: on the installed routing tiles;
     * [atDestination]: within [SAME_PLACE_M] of the trip's destination. During a trip (planned, chosen or guided) the
     * place is offered as a stop or the new destination (item 51's card); without one the route preview.
     */
    fun act(phase: Phase, inside: Boolean, atDestination: Boolean = false): Act = when {
        !inside -> if (guiding(phase)) Act.NOTE else Act.WINDOW
        pickToCard(phase) && atDestination -> Act.ALREADY
        pickToCard(phase) -> Act.STOP_CARD
        else -> Act.PREVIEW
    }

    /** H7: a row picked in the search a link's words opened goes to the stop card in these phases (a trip planned, chosen or guided). */
    fun pickToCard(phase: Phase): Boolean = phase == Phase.PLANNING || phase == Phase.CHOOSING || guiding(phase)

    /** H6, P30: "Add a state" is offered only while no trip runs (States is not reachable during a trip). */
    fun statesOffered(phase: Phase): Boolean = phase == Phase.IDLE || phase == Phase.RESUME_OFFERED

    /** H7: the words of ALREADY: guiding there, or the route looked at ends there. */
    fun alreadyText(phase: Phase): String = if (guiding(phase)) HandoverTexts.ALREADY_GOING else HandoverTexts.ALREADY_SHOWN

    /** H3: a VIEW intent with a link, not a start from Recents (which hands the task's first intent back). */
    fun takes(action: String?, data: String?, fromHistory: Boolean): Boolean =
        action == "android.intent.action.VIEW" && !data.isNullOrBlank() && !fromHistory

    /** H4: the map can take a link: the reveal done, the notice accepted, the online choice made, no first-run picker, the states scanned, nothing of the user's open. */
    fun ready(revealDone: Boolean, noticeAccepted: Boolean, onlineChoiceMade: Boolean, pickerShown: Boolean, scanned: Boolean, busy: Boolean): Boolean =
        revealDone && noticeAccepted && onlineChoiceMade && !pickerShown && scanned && !busy

    /**
     * H4: something of the user's is open that a link must not close: a report form, an OSM edit, "Add a missing
     * place", the speed limit screen, the save dialog, a map pick, the public note or edit notice, or (K8) a search to
     * save a place ([saveSearch]: the search controller in SAVE mode; a layer opened to save).
     */
    fun busy(stack: List<Layer>, saveDialog: Boolean, mapPick: Boolean, publicNotice: Boolean, saveSearch: Boolean = false): Boolean =
        saveDialog || mapPick || publicNotice || saveSearch ||
            stack.any { it is Layer.Report || it == Layer.EditPlace || it == Layer.AddPlace || it == Layer.SpeedLimit || (it is Layer.Search && (it.forSave || it.mode == SearchMode.SAVE)) }

    /** H4 (K5): a link that arrived at [receivedMs] (monotonic) is too old at [nowMs] to open by itself; [firstRun]: it came during the first-run steps. */
    fun expired(receivedMs: Long, nowMs: Long, firstRun: Boolean): Boolean =
        nowMs - receivedMs > if (firstRun) FIRST_RUN_WAIT_MS else LINK_WAIT_MS

    /** H5: "42.12900, -80.08500", the text a dropped pin shows (AppFlows.coordinates). */
    fun pointText(p: GeoPoint): String = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)

    /** H5: the folded names are equal, or one is the other followed by more words; both at least 3 characters folded (else only equal). */
    fun sameName(a: String, b: String): Boolean {
        val x = foldText(a)
        val y = foldText(b)
        if (x.length < 3 || y.length < 3) return x.isNotEmpty() && x == y
        return x == y || x.startsWith("$y ") || y.startsWith("$x ")
    }

    /**
     * H5: the place a point is: a label and a pack place of that name within [LABEL_POI_M] (that place, at its point;
     * K7: a label that differs from the pack's name gets "<pack name>, <address>" as its second line); a label alone
     * (at the link's point); the only named place within [POI_NAME_M] (its name, the link's point); the street line
     * ([line], a dropped pin's); its coordinates.
     */
    fun pointPlace(point: GeoPoint, label: String?, pois: List<NearbyPoi>, line: String?): Place {
        val coordinates = pointText(point)
        if (label != null) {
            val poi = pois.filter { it.distanceM <= LABEL_POI_M && sameName(label, it.name) }.minByOrNull { it.distanceM }
            if (poi != null) {
                val second = if (foldText(label) == foldText(poi.name)) poi.address ?: line else listOfNotNull(poi.name, poi.address).joinToString(", ")
                return Place(label, GeoPoint(poi.lat, poi.lon), PlaceSource.MAP_POI, subtitle = second, categoryId = poi.code)
            }
            return Place(label, point, PlaceSource.SEARCH, subtitle = line ?: coordinates)
        }
        val near = pois.filter { it.distanceM <= POI_NAME_M }
        if (near.size == 1) return Place(near[0].name, point, PlaceSource.SEARCH, subtitle = line ?: coordinates)
        if (line != null) return Place(line, point, PlaceSource.MAP_PIN, subtitle = coordinates)
        return Place(coordinates, point, PlaceSource.MAP_PIN)
    }

    // ---------------------------------------------------------------- words (H6)

    /** H6 (K1): what words handed over lead to. [toString] prints none of them (PV2). */
    sealed interface TextAct {
        /** The words name a US state ([code], "oh") that is not installed. */
        data class OutsideState(val code: String) : TextAct {
            override fun toString(): String = "TextAct.OutsideState"
        }

        /** One clear match: opened as the same place picked in search. */
        data class Clear(val row: PackResult) : TextAct {
            override fun toString(): String = "TextAct.Clear"
        }

        /** "Name, 12 Some Street, ...": the address is looked up alone, named by the name. */
        data class TryAddress(val name: String, val address: String) : TextAct {
            override fun toString(): String = "TextAct.TryAddress"
        }

        /** The search opens with the words typed. */
        data object Search : TextAct
    }

    /**
     * H6 in its order (K1): a US state named in the words that is not among [installedStates] (codes, lower case) gives
     * [TextAct.OutsideState] whatever the rows are (asked first with [r] null, before any lookup); then one clear match
     * of [r] (from that state's pack when an installed state is named, [packStates]: packId -> code; in the town when the
     * words give one); then "Name, 12 Some Street, ..."; else the search.
     */
    fun decideText(
        text: String, r: MultiSearchResponse?, spellings: Map<String, String>, ambiguous: Set<String>,
        installedStates: Set<String>, packStates: Map<String, String>, streetKey: (String) -> String = ::foldText,
    ): TextAct {
        val state = namedState(text, spellings, ambiguous)
        if (state != null && state !in installedStates) return TextAct.OutsideState(state)
        clearMatch(text, r, state, packStates, townOf(text, spellings, ambiguous), streetKey)?.let { return TextAct.Clear(it) }
        nameAndAddress(text)?.let { (name, address) -> return TextAct.TryAddress(name, address) }
        return TextAct.Search
    }

    /**
     * H6: one clear match among [r]'s rows (best score first), or null: never a category's or a brand's answer; (a) a
     * located address (a house point or a position along its street, in the typed town and state) on the street the words
     * name before their first comma ([clearAddress]), with no other located address on that street farther than
     * [SAME_PLACE_M]; (b) an exact name equal to the words before the first comma, with no namesake farther than
     * [SAME_PLACE_M], and (K1) in [town] when the words give one. With [state], only a row of that state's pack
     * ([packStates]). [streetKey]: how street names compare (the address parser's AddressNormalizer.streetKey in the app).
     */
    fun clearMatch(
        text: String, r: MultiSearchResponse?, state: String? = null, packStates: Map<String, String> = emptyMap(), town: String? = null,
        streetKey: (String) -> String = ::foldText,
    ): PackResult? {
        val rows = r?.results ?: return null
        val first = rows.firstOrNull() ?: return null
        val res = first.result
        if (res.tier == "category" || res.tier == "brand") return null
        if (HOUSE_STREET.matches(text.substringBefore(',').trim())) clearAddress(text, r, state, packStates, streetKey)?.let { return it }
        if (state != null && packStates[first.packId] != state) return null
        if (located(res)) return null   // an address the words do not name (rule (a) above): never a name's match
        if (res.tier != "exact" && res.tier != "exact_alt") return null
        val title = foldText(res.title)
        if (title.isEmpty() || title != foldText(text.substringBefore(','))) return null
        if (town != null && !" ${foldText(res.subtitle)} ".contains(" $town ")) return null
        return first.takeIf { rows.drop(1).none { o -> foldText(o.result.title) == title && apartM(o.result, res) > SAME_PLACE_M } }
    }

    /**
     * H6 rule (a) alone, also for the address of "Name, 12 Some Street, ..." (K1: with [state], only that state's pack): the
     * first row that is no town or ZIP (the places the words name may rank above their address: "150 East Front Street,
     * Erie, PA 16507" gave "Erie" first on the emulator) a located address whose street is the one [text] names after its
     * house number (all the words before the first comma, or their start when the words have no comma: "1600 Example
     * Street Erie PA"), and no other located row on that street farther than [SAME_PLACE_M] (the same address in another
     * town). The same number on other streets, which the engine also returns, does not count (H-check, emulator: "423 West
     * 38th Street, Erie" went to the list for them).
     */
    fun clearAddress(
        text: String, r: MultiSearchResponse?, state: String? = null, packStates: Map<String, String> = emptyMap(),
        streetKey: (String) -> String = ::foldText,
    ): PackResult? {
        val rows = r?.results ?: return null
        val first = rows.firstOrNull { it.result.kind != SearchResult.Kind.PLACE } ?: return null
        if (!located(first.result)) return null
        if (state != null && packStates[first.packId] != state) return null
        val key = first.result.address?.streetName?.let(streetKey).orEmpty()
        val typed = HOUSE_STREET.matchEntire(text.substringBefore(',').trim())?.groupValues?.get(1)?.let(streetKey) ?: return null
        if (key.isEmpty() || !(typed == key || (',' !in text && typed.startsWith("$key ")))) return null
        return first.takeIf {
            rows.none { o -> o !== first && located(o.result) && o.result.address?.streetName?.let(streetKey) == key && apartM(o.result, first.result) > SAME_PLACE_M }
        }
    }

    /** H6 (a): words that start with a house number; group 1 is the rest (the street, and with no comma what follows it). */
    private val HOUSE_STREET = Regex("""^\d+[A-Za-z]?(?:-\d+[A-Za-z]?)?\s+(\S.*)$""")

    /**
     * A located address (a house point or a position along its street, in the typed town and state). Item 55: an answer
     * along the street the search shows where it is ranked ([AddressIndex.MATCH_ALONG]) is that place's estimate, not the
     * address the words give: located only when a typed town matched it (with no town, "451 Some Road" could be any of
     * the state's roads of the name, and the link's words open the search list instead).
     */
    private fun located(res: SearchResult): Boolean {
        val a = res.address ?: return false
        if (res.tier == AddressIndex.MATCH_ALONG && a.localityMatch != true) return false
        return (a.accuracy == AddressInfo.Accuracy.POINT || a.accuracy == AddressInfo.Accuracy.INTERPOLATED) && a.localityMatch != false && !a.stateMismatch
    }

    private fun apartM(a: SearchResult, b: SearchResult): Double = Geo.haversineKm(a.lat, a.lon, b.lat, b.lon) * 1000.0

    /** H6: the place the search screen makes of the same row (SearchController.rowsOf), so the route, the door, the lot and Recents treat it alike. */
    fun placeOf(pr: PackResult): Place {
        val res = pr.result
        return Place(res.title, GeoPoint(res.lat, res.lon), PlaceSource.SEARCH, res.subtitle.ifEmpty { null }, res.categoryId, res.id, pr.packId)
    }

    /** H6: an address row's whole line, "1600 Example Street, Erie, PA 16501". */
    fun addressLine(pr: PackResult): String = listOf(pr.result.title, pr.result.subtitle).filter { it.isNotBlank() }.joinToString(", ")

    private val HOUSE = Regex("""^\d+[A-Za-z]?(-\d+[A-Za-z]?)?\s+\S.*$""")

    /** H6: "Name, 12 Some Street, Town, ST 12345" as (the name, the address); null for any other shape. */
    fun nameAndAddress(text: String): Pair<String, String>? {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[0].any { it.isDigit() } || !HOUSE.matches(parts[1])) return null
        return parts[0] to parts.drop(1).joinToString(", ")
    }

    private val COUNTRY = setOf("us", "usa", "u s", "u s a", "united states", "united states of america")
    private val ZIP5 = Regex("""^\d{5}$""")
    private val ZIP4 = Regex("""^\d{4}$""")

    /** The words split at "," and ";", folded, empty ones dropped, a trailing country dropped while more than one part is left. */
    private fun partsOf(text: String): List<String> {
        val parts = text.split(',', ';').map(::foldText).filter { it.isNotEmpty() }.toMutableList()
        while (parts.size > 1 && parts.last() in COUNTRY) parts.removeAt(parts.lastIndex)
        return parts
    }

    /** The state the last part names ([code]) and the words of that part before its spelling ([before], a ZIP left out). */
    private class StateRead(val code: String, val before: List<String>)

    private fun readState(parts: List<String>, spellings: Map<String, String>, ambiguous: Set<String>): StateRead? {
        val words = parts.lastOrNull()?.split(' ')?.filter { it.isNotEmpty() }?.toMutableList() ?: return null
        if (words.size >= 2 && ZIP4.matches(words.last()) && ZIP5.matches(words[words.size - 2])) words.removeAt(words.lastIndex)
        val zip = words.isNotEmpty() && ZIP5.matches(words.last())
        if (zip) words.removeAt(words.lastIndex)
        for (n in minOf(4, words.size) downTo 1) {
            val tail = words.takeLast(n).joinToString(" ")
            val code = spellings[tail] ?: continue
            val wholePart = n == words.size && parts.size >= 2
            val fullName = n < words.size && tail.length >= 4 && tail !in ambiguous
            return if (wholePart || zip || fullName) StateRead(code, words.dropLast(n)) else null
        }
        return null
    }

    /**
     * H6: the US state the words' last part names ("oh"), or null: a spelling of [spellings] (us_street_norm.json) that is
     * the whole last part of two or more, or before a ZIP ("Columbus OH 43215"), or a name of 4 letters or more that is not
     * in [ambiguous] after other words ("Columbus Ohio").
     */
    fun namedState(text: String, spellings: Map<String, String>, ambiguous: Set<String>): String? =
        readState(partsOf(text), spellings, ambiguous)?.code

    /**
     * H6 (K1): the town the words give after the name, folded: the last part once the state part (and its ZIP) is left
     * out, or the words before the state in that part ("Erie PA 16508": "erie"); never a part that starts with a house
     * number; null when the words give none.
     */
    fun townOf(text: String, spellings: Map<String, String>, ambiguous: Set<String>): String? {
        val parts = partsOf(text)
        if (parts.size < 2) return null
        val st = readState(parts, spellings, ambiguous)
        val rest = parts.drop(1)
        val towns = when {
            st == null -> rest
            st.before.isEmpty() -> rest.dropLast(1)
            else -> rest.dropLast(1) + st.before.joinToString(" ")
        }
        return towns.lastOrNull { !HOUSE.matches(it) }
    }

    /** H6: a state's name from its code: its longest spelling, each word capitalised except "of" ("Ohio", "District of Columbia"). */
    fun stateName(code: String, spellings: Map<String, String>): String {
        val longest = spellings.filter { it.value == code }.keys.maxByOrNull { it.length } ?: return code.uppercase(Locale.US)
        return longest.split(' ').joinToString(" ") { w -> if (w == "of") w else w.replaceFirstChar { it.titlecase(Locale.US) } }
    }

    /** K16: the trip now guided goes to [place] (its point is the destination's): a handed-over place becomes a recent then. */
    fun guidedTo(place: Place, phase: Phase, destination: GeoPoint?): Boolean =
        phase == Phase.GUIDING && destination != null &&
            Geo.haversineKm(destination.lat, destination.lon, place.point.lat, place.point.lon) * 1000.0 <= 1.0

    /**
     * H6: the search a link's words open is ranked around the link's point [near], its distances from the user's fresh
     * fix [from] (S1b). Its line names the town there ("Near Erie", P6: the search asks the packs whose bounds hold the
     * point) only where an installed state's search is ([searchable]: those packs' bounds); elsewhere it has no line,
     * as "Near the map center" would name a point the search does not use (review 0.1.40).
     */
    fun linkContext(near: GeoPoint, searchable: List<Bounds>, from: GeoPoint?): SearchContext =
        SearchContext(near, if (searchable.any { it.contains(near) }) NearSource.MAP_CENTER else NearSource.NONE, distanceFrom = from)

    /** H8: the one word a log line may say about a link (its form; never its contents). */
    fun kindName(link: Handover): String = when (link) {
        is Handover.Point -> if (link.label != null) "labelled point" else "point"
        is Handover.Text -> "words"
        Handover.Unreadable -> "unreadable"
    }
}

/**
 * H3, K15: the numbers AppFlows gives the links it receives. A link's lookups (on the search thread) open what they
 * found only while its number is [live]: a newer link takes over, and the activity's end ([voidAll]) stops them all.
 */
class LinkTickets {
    private var last = 0L

    /** The number of a link just received. */
    fun next(): Long = ++last

    /** The link numbered [seq] may still open what it found. */
    fun live(seq: Long): Boolean = seq == last

    /** K15: the activity finished; no link received before now opens anything. */
    fun voidAll() {
        last++
    }
}
