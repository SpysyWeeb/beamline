package io.github.spysyweeb.beamline.app.handover

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.app.handover.HandoverRules.Act
import io.github.spysyweeb.beamline.app.handover.HandoverRules.TextAct
import io.github.spysyweeb.beamline.app.ui.AppFlows
import io.github.spysyweeb.beamline.app.ui.Layer
import io.github.spysyweeb.beamline.feature.search.NearSource
import io.github.spysyweeb.beamline.feature.search.SearchContext
import io.github.spysyweeb.beamline.feature.search.SearchMode
import io.github.spysyweeb.beamline.search.AddressInfo
import io.github.spysyweeb.beamline.search.MultiSearchResponse
import io.github.spysyweeb.beamline.search.NearbyPoi
import io.github.spysyweeb.beamline.search.PackResult
import io.github.spysyweeb.beamline.search.SearchResult
import io.github.spysyweeb.beamline.search.address.AddressNormalizer
import io.github.spysyweeb.beamline.search.address.StreetTables
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Item 52 (rules H3-H7, design/handover.md 3.6, 4.4, 5.7, 6.5 and the critiques' K1-K17): when a handed-over
 * link opens, what a point is named, which looked-up row is one clear match, which state the words name, and what
 * a place opens in each trip phase. Made-up places ("Quokka Den", "1600 Example Street") near Erie, PA.
 */
class HandoverRulesTest {

    // ---------------------------------------------------------------- 3.6: once, and when

    @Test
    fun aLinkWaitsForTheFirstRunAndTheUsersOwnWork() {
        assertTrue(HandoverRules.ready(revealDone = true, noticeAccepted = true, onlineChoiceMade = true, pickerShown = false, scanned = true, busy = false))
        assertFalse(HandoverRules.ready(false, true, true, false, true, false))
        assertFalse(HandoverRules.ready(true, false, true, false, true, false))
        assertFalse(HandoverRules.ready(true, true, false, false, true, false))
        assertFalse(HandoverRules.ready(true, true, true, true, true, false))
        assertFalse(HandoverRules.ready(true, true, true, false, false, false))
        assertFalse(HandoverRules.ready(true, true, true, false, true, true))
    }

    @Test
    fun formsDialogsAndPicksAreTheUsersOwn() {
        fun busy(stack: List<Layer>, saveDialog: Boolean = false, mapPick: Boolean = false, publicNotice: Boolean = false, saveSearch: Boolean = false) =
            HandoverRules.busy(stack, saveDialog, mapPick, publicNotice, saveSearch)
        for (l in listOf(Layer.Report(null), Layer.EditPlace, Layer.AddPlace, Layer.SpeedLimit)) assertTrue(busy(listOf(Layer.Settings, l)), "$l")
        assertTrue(busy(emptyList(), saveDialog = true))
        assertTrue(busy(emptyList(), mapPick = true))
        assertTrue(busy(emptyList(), publicNotice = true))
        // K8: a search to save a place (the save dialog's "Search", or Home and Work being set) is the user's too
        assertTrue(busy(listOf(Layer.Search(2, SearchMode.SAVE, forSave = true))))
        assertTrue(busy(listOf(Layer.Search(1, SearchMode.GO_TO, false)), saveSearch = true))
        assertFalse(busy(listOf(Layer.Settings, Layer.States, Layer.About, Layer.Search(1, SearchMode.GO_TO, false), Layer.SavedList)))
        assertFalse(busy(emptyList()))
    }

    @Test
    fun aLinkIsTakenOnceAndNeverFromRecents() {
        assertTrue(HandoverRules.takes("android.intent.action.VIEW", "geo:42.129,-80.085", fromHistory = false))
        assertFalse(HandoverRules.takes("android.intent.action.VIEW", "geo:42.129,-80.085", fromHistory = true))
        assertFalse(HandoverRules.takes("android.intent.action.MAIN", "geo:42.129,-80.085", fromHistory = false))
        assertFalse(HandoverRules.takes("android.intent.action.VIEW", null, fromHistory = false))
        assertFalse(HandoverRules.takes("android.intent.action.VIEW", "  ", fromHistory = false))
        assertFalse(HandoverRules.takes(null, "geo:42.129,-80.085", fromHistory = false))
    }

    /** K5: a link that waited too long is dropped; one that came during the first-run steps may wait longer. */
    @Test
    fun aWaitingLinkGoesStale() {
        assertEquals(120_000L, HandoverRules.LINK_WAIT_MS)
        assertEquals(600_000L, HandoverRules.FIRST_RUN_WAIT_MS)
        assertFalse(HandoverRules.expired(receivedMs = 1_000L, nowMs = 121_000L, firstRun = false))
        assertTrue(HandoverRules.expired(1_000L, 121_001L, firstRun = false))
        assertFalse(HandoverRules.expired(1_000L, 500_000L, firstRun = true))
        assertTrue(HandoverRules.expired(1_000L, 601_001L, firstRun = true))
        assertFalse(HandoverRules.expired(5_000L, 4_000L, firstRun = false))   // a clock that did not move on
    }

    // ---------------------------------------------------------------- 4.4: a point's name

    private val p = GeoPoint(42.12900, -80.08500)
    private val line = "Near 815 Example Street, Erie"
    private val quokka = NearbyPoi(7, "Quokka Den", "restaurant", "Restaurant", null, 42.12910, -80.08495, distanceM = 12.0, address = "815 Example Street")
    private val atm = NearbyPoi(8, "Corner ATM", "atm", "ATM", null, 42.12920, -80.08510, distanceM = 25.0)

    @Test
    fun aLabelFindsThePlaceOfThatName() {
        val at = GeoPoint(42.12910, -80.08495)
        assertEquals(Place("QUOKKA DEN", at, PlaceSource.MAP_POI, subtitle = "815 Example Street", categoryId = "restaurant"),
            HandoverRules.pointPlace(p, "QUOKKA DEN", listOf(quokka), line))
        // K7: a label that only starts with the pack's name keeps it as the name; the second line starts with the pack's
        // name, so the trip planner's door lookup (the first part of the second line) still finds that place
        assertEquals(Place("Quokka Den Downtown", at, PlaceSource.MAP_POI, subtitle = "Quokka Den, 815 Example Street", categoryId = "restaurant"),
            HandoverRules.pointPlace(p, "Quokka Den Downtown", listOf(quokka), line))
        assertEquals(Place("Quokka Den Downtown", at, PlaceSource.MAP_POI, subtitle = "Quokka Den", categoryId = "restaurant"),
            HandoverRules.pointPlace(p, "Quokka Den Downtown", listOf(quokka.copy(address = null)), line))
        // no address in the pack: the street line
        assertEquals(Place("Quokka Den", at, PlaceSource.MAP_POI, subtitle = line, categoryId = "restaurant"),
            HandoverRules.pointPlace(p, "Quokka Den", listOf(quokka.copy(address = null)), line))
    }

    @Test
    fun aLabelOfAnotherNameKeepsThePoint() {
        assertEquals(Place("Quokka Den", p, PlaceSource.SEARCH, subtitle = line), HandoverRules.pointPlace(p, "Quokka Den", listOf(atm), line))
        assertEquals(Place("Quokka Den", p, PlaceSource.SEARCH, subtitle = "42.12900, -80.08500"), HandoverRules.pointPlace(p, "Quokka Den", listOf(atm), null))
        assertEquals(Place("Quokka Den", p, PlaceSource.SEARCH, subtitle = line),
            HandoverRules.pointPlace(p, "Quokka Den", listOf(quokka.copy(distanceM = 55.0)), line))
    }

    @Test
    fun theOnlyPlaceNearbyNamesAnUnlabelledPoint() {
        assertEquals(Place("Quokka Den", p, PlaceSource.SEARCH, subtitle = line), HandoverRules.pointPlace(p, null, listOf(quokka), line))
        assertEquals(Place("Quokka Den", p, PlaceSource.SEARCH, subtitle = "42.12900, -80.08500"), HandoverRules.pointPlace(p, null, listOf(quokka), null))
    }

    @Test
    fun twoPlacesNearbyNameNeither() {
        assertEquals(Place(line, p, PlaceSource.MAP_PIN, subtitle = "42.12900, -80.08500"), HandoverRules.pointPlace(p, null, listOf(quokka, atm), line))
    }

    @Test
    fun aPlaceFartherThan30mDoesNotNameIt() {
        assertEquals(Place(line, p, PlaceSource.MAP_PIN, subtitle = "42.12900, -80.08500"),
            HandoverRules.pointPlace(p, null, listOf(quokka.copy(distanceM = 35.0)), line))
    }

    @Test
    fun nothingKnownGivesTheCoordinates() {
        assertEquals(Place("42.12900, -80.08500", p, PlaceSource.MAP_PIN), HandoverRules.pointPlace(p, null, emptyList(), null))
    }

    @Test
    fun pointTextIsADroppedPinsText() {
        assertEquals(AppFlows.coordinates(p), HandoverRules.pointText(p))
        assertEquals(AppFlows.coordinates(GeoPoint(42.13646, -80.08718)), HandoverRules.pointText(GeoPoint(42.13646, -80.08718)))
    }

    @Test
    fun sameNameFolds() {
        assertTrue(HandoverRules.sameName("Quokka Den", "quokka den"))
        assertTrue(HandoverRules.sameName("Quokka Den!", "Quokka Den"))
        assertTrue(HandoverRules.sameName("Quokka Den Downtown", "Quokka Den"))
        assertTrue(HandoverRules.sameName("Quokka", "Quokka Den Grill"))
        assertFalse(HandoverRules.sameName("Den", "Quokka Den"))
        assertFalse(HandoverRules.sameName("Qu", "Qu Den"))
        assertTrue(HandoverRules.sameName("Qu", "qu"))
        assertFalse(HandoverRules.sameName("", ""))
    }

    // ---------------------------------------------------------------- 5.7: words

    private val m150 = 150.0 / 111_195.0   // degrees of latitude per 150 m

    private fun row(
        id: Long, title: String, tier: String?, lat: Double = 42.1290, lon: Double = -80.0850, sub: String = "815 Example Street, Erie",
        kind: SearchResult.Kind = SearchResult.Kind.POI, address: AddressInfo? = null, pack: String = "us-pa", cat: String? = "restaurant",
    ) = PackResult(pack, SearchResult(id, kind, title, sub, cat, "Restaurant", lat, lon, 1.0, null, address, 10.0 - id * 0.1, "t", tier))

    private fun resp(vararg rows: PackResult) = MultiSearchResponse("q", rows.toList(), listOf("us-pa"), true, null, false, 1.0)

    private fun address(id: Long, acc: AddressInfo.Accuracy, locality: Boolean? = true, mismatch: Boolean = false, lat: Double = 42.1290, street: String = "Example Street",
                        tier: String = "address") =
        row(id, "1600 $street", tier, lat = lat, sub = "Erie, PA 16501", kind = SearchResult.Kind.ADDRESS, cat = "address",
            address = AddressInfo("1600", street, acc, localityMatch = locality, stateMismatch = mismatch))

    /**
     * Item 55 (review 2): an answer along the street the search shows ("along_street", no town typed or the typed town not
     * matched) is not the words' address: a link "1600 Example Street" with no town opens the search list, not a route to
     * whichever road of the name is nearest; with the typed town matched it is clear as any located address.
     */
    @Test
    fun anAnswerAlongTheStreetIsClearOnlyInTheTypedTown() {
        for (locality in listOf(null, false)) {
            val along = resp(address(1, AddressInfo.Accuracy.INTERPOLATED, locality = locality, tier = "along_street"))
            assertNull(HandoverRules.clearMatch("1600 Example Street", along), "$locality")
            assertNull(HandoverRules.clearAddress("1600 Example Street", along), "$locality")
            assertEquals(TextAct.Search, decide("1600 Example Street", along), "$locality")
            val point = resp(address(1, AddressInfo.Accuracy.POINT, locality = locality, tier = "along_street"))
            assertNull(HandoverRules.clearAddress("1600 Example Street, Erie", point), "$locality")
        }
        val inTown = resp(address(1, AddressInfo.Accuracy.INTERPOLATED, locality = true, tier = "along_street"))
        assertSame(inTown.results[0], HandoverRules.clearMatch("1600 Example Street, Erie", inTown))
        assertSame(inTown.results[0], HandoverRules.clearAddress("1600 Example Street, Erie", inTown))
        // the lookup's own answer with no town typed (not along a street): as before
        val noTown = resp(address(1, AddressInfo.Accuracy.INTERPOLATED, locality = null))
        assertSame(noTown.results[0], HandoverRules.clearMatch("1600 Example Street", noTown))
    }

    @Test
    fun anExactNameAloneIsClear() {
        val r = resp(row(1, "Quokka Den", "exact"))
        assertSame(r.results[0], HandoverRules.clearMatch("Quokka Den", r))
        assertSame(r.results[0], HandoverRules.clearMatch("quokka den, Erie, PA", r))
        val alt = resp(row(1, "Quokka Den", "exact_alt"))
        assertSame(alt.results[0], HandoverRules.clearMatch("Quokka Den", alt))
    }

    @Test
    fun aNamesakeFarAwayIsNotClear() {
        assertNull(HandoverRules.clearMatch("Quokka Den", resp(row(1, "Quokka Den", "exact"), row(2, "Quokka Den", "exact", lat = 42.1290 + 3000.0 / 111_195.0))))
        val near = resp(row(1, "Quokka Den", "exact"), row(2, "Quokka Den", "exact", lat = 42.1290 + 80.0 / 111_195.0))
        assertSame(near.results[0], HandoverRules.clearMatch("Quokka Den", near))
        // another name farther away is no namesake
        val other = resp(row(1, "Quokka Den", "exact"), row(2, "Quokka Den Annex", "prefix", lat = 42.1290 + 20 * m150))
        assertSame(other.results[0], HandoverRules.clearMatch("Quokka Den", other))
    }

    @Test
    fun aClassIsNeverClear() {
        assertNull(HandoverRules.clearMatch("gas", resp(row(1, "Quokka Gas", "category"))))
        assertNull(HandoverRules.clearMatch("sheetz", resp(row(1, "Sheetz", "brand"))))
    }

    @Test
    fun aPrefixIsNotClear() {
        assertNull(HandoverRules.clearMatch("Quokka Den", resp(row(1, "Quokka Den Annex", "prefix"))))
        // an exact tier whose title is not the words is not clear either
        assertNull(HandoverRules.clearMatch("Quokka Den", resp(row(1, "Quokka Den Annex", "exact"))))
    }

    @Test
    fun aLocatedAddressIsClear() {
        val point = resp(address(1, AddressInfo.Accuracy.POINT))
        assertSame(point.results[0], HandoverRules.clearMatch("1600 Example Street, Erie, PA 16501", point))
        val interp = resp(address(1, AddressInfo.Accuracy.INTERPOLATED))
        assertSame(interp.results[0], HandoverRules.clearMatch("1600 Example Street, Erie", interp))
        assertNull(HandoverRules.clearMatch("1600 Example Street", resp(address(1, AddressInfo.Accuracy.STREET))))
        assertNull(HandoverRules.clearMatch("1600 Example Street", resp(address(1, AddressInfo.Accuracy.POINT, locality = false))))
        assertNull(HandoverRules.clearMatch("1600 Example Street", resp(address(1, AddressInfo.Accuracy.POINT, mismatch = true))))
        // no town typed (localityMatch null) is located too
        val noTown = resp(address(1, AddressInfo.Accuracy.POINT, locality = null))
        assertSame(noTown.results[0], HandoverRules.clearMatch("1600 Example Street", noTown))
        assertNull(HandoverRules.clearMatch("1600 Example Street",
            resp(address(1, AddressInfo.Accuracy.POINT), address(2, AddressInfo.Accuracy.POINT, lat = 42.1290 + 2000.0 / 111_195.0))))
        val close = resp(address(1, AddressInfo.Accuracy.POINT), address(2, AddressInfo.Accuracy.POINT, lat = 42.1290 + 90.0 / 111_195.0))
        assertSame(close.results[0], HandoverRules.clearMatch("1600 Example Street", close))
    }

    /**
     * H-check (emulator i): the engine answers "423 West 38th Street, Erie, PA 16508" with that address first and the same
     * house number on other streets after it ("423 West 26th Street", 2 km away), so every address went to the search list.
     * Only the same street elsewhere makes an address unclear; the first row must be on the street the words name, before
     * their first comma (as rule (b) reads a name), so "Name, 12 Some Street" is left to the name-and-address step.
     */
    @Test
    fun anAddressIsClearBesideTheSameNumberOnOtherStreets() {
        val far = 42.1290 + 2000.0 / 111_195.0
        val others = resp(address(1, AddressInfo.Accuracy.POINT), address(2, AddressInfo.Accuracy.POINT, lat = far, street = "Other Street"),
            address(3, AddressInfo.Accuracy.INTERPOLATED, lat = far, street = "Example Road"))
        assertSame(others.results[0], HandoverRules.clearMatch("1600 Example Street, Erie, PA 16501", others))
        assertEquals(TextAct.Clear(others.results[0]), decide("1600 Example Street, Erie, PA 16501", others))
        // the same street far away still makes it unclear
        assertNull(HandoverRules.clearMatch("1600 Example Street, Erie",
            resp(address(1, AddressInfo.Accuracy.POINT), address(2, AddressInfo.Accuracy.POINT, lat = far, street = "Other Street"), address(3, AddressInfo.Accuracy.POINT, lat = far))))
        // the engine's first row on another street than the words name (a guess): not clear
        assertNull(HandoverRules.clearMatch("1600 Exampel Street, Erie", resp(address(1, AddressInfo.Accuracy.POINT))))
        assertNull(HandoverRules.clearMatch("1600 Example Street Extension, Erie", resp(address(1, AddressInfo.Accuracy.POINT))))
        // words that do not start with "<number> <street>" before their first comma are no address of their own
        assertNull(HandoverRules.clearMatch("Example Street 1600", resp(address(1, AddressInfo.Accuracy.POINT))))
        val named = resp(address(1, AddressInfo.Accuracy.POINT))
        assertNull(HandoverRules.clearMatch("Quokka Den, 1600 Example Street, Erie", named))
        assertEquals(TextAct.TryAddress("Quokka Den", "1600 Example Street, Erie"), decide("Quokka Den, 1600 Example Street, Erie", named))
        // no comma: the street is the start of the words after the number (with a comma it is all of them, above)
        assertSame(named.results[0], HandoverRules.clearMatch("1600 Example Street Erie PA 16501", named))
        // the address of "Name, 12 Some Street, ..." (5.4) by the same rule
        assertSame(others.results[0], HandoverRules.clearAddress("1600 Example Street, Erie, PA 16501", others))
        assertNull(HandoverRules.clearAddress("1600 Exampel Street, Erie", named))
        // the app compares streets as the address parser does: "W 38th St" is "West 38th Street"
        val key = AddressNormalizer(tables)::streetKey
        val west = resp(address(1, AddressInfo.Accuracy.POINT, street = "West 38th Street"), address(2, AddressInfo.Accuracy.POINT, lat = far, street = "West 26th Street"))
        assertSame(west.results[0], HandoverRules.clearMatch("1600 W 38th St, Erie, PA 16508", west, streetKey = key))
        assertSame(west.results[0], HandoverRules.clearAddress("1600 West 38th Street", west, streetKey = key))
        assertEquals(TextAct.Clear(west.results[0]), HandoverRules.decideText("1600 W. 38th St., Erie, PA", west, tables.states, tables.ambiguousStates, installedPa, packStates, key))
        assertNull(HandoverRules.clearMatch("1600 W 39th St, Erie", west, streetKey = key))
        // the town or ZIP the words name can rank above the address ("150 East Front Street, Erie, PA 16507" on the
        // emulator: "Erie, City" first): places before it do not hide it; a place of another kind (a POI) does
        val town = row(1, "Erie", "exact", sub = "City, Erie County, PA", kind = SearchResult.Kind.PLACE, cat = "city")
        val belowTown = resp(town, address(2, AddressInfo.Accuracy.POINT))
        assertSame(belowTown.results[1], HandoverRules.clearMatch("1600 Example Street, Erie, PA 16501", belowTown))
        assertSame(belowTown.results[1], HandoverRules.clearAddress("1600 Example Street, Erie, PA 16501", belowTown))
        assertNull(HandoverRules.clearMatch("1600 Example Street, Erie", resp(row(1, "Quokka Den", "exact"), address(2, AddressInfo.Accuracy.POINT))))
        // a name that starts with a number is still read as a name when it is no address
        val numbered = resp(row(1, "21 Quokka Den", "exact"))
        assertSame(numbered.results[0], HandoverRules.clearMatch("21 Quokka Den", numbered))
    }

    @Test
    fun nothingIsNotClear() {
        assertNull(HandoverRules.clearMatch("Quokka Den", null))
        assertNull(HandoverRules.clearMatch("Quokka Den", resp()))
        assertNull(HandoverRules.clearAddress("1600 Example Street", null))
        assertNull(HandoverRules.clearAddress("1600 Example Street", resp()))
    }

    @Test
    fun clearAddressTakesOnlyAddresses() {
        assertNull(HandoverRules.clearAddress("Quokka Den", resp(row(1, "Quokka Den", "exact"))))
        val a = resp(address(1, AddressInfo.Accuracy.POINT))
        assertSame(a.results[0], HandoverRules.clearAddress("1600 Example Street, Erie, PA 16501", a))
        // K1: with a state named, only an address of that state's pack
        assertNull(HandoverRules.clearAddress("1600 Example Street, Erie, PA 16501", resp(address(1, AddressInfo.Accuracy.POINT)), state = "oh", packStates = mapOf("us-pa" to "pa")))
        assertSame(a.results[0], HandoverRules.clearAddress("1600 Example Street, Erie, PA 16501", a, state = "pa", packStates = mapOf("us-pa" to "pa")))
    }

    @Test
    fun aClearRowIsTheSearchScreensPlace() {
        val r = row(9, "Quokka Den", "exact")
        assertEquals(Place("Quokka Den", GeoPoint(42.1290, -80.0850), PlaceSource.SEARCH, "815 Example Street, Erie", "restaurant", 9L, "us-pa"),
            HandoverRules.placeOf(r))
        assertEquals(Place("Quokka Den", GeoPoint(42.1290, -80.0850), PlaceSource.SEARCH, null, "restaurant", 9L, "us-pa"),
            HandoverRules.placeOf(row(9, "Quokka Den", "exact", sub = "")))
        assertEquals("1600 Example Street, Erie, PA 16501", HandoverRules.addressLine(address(1, AddressInfo.Accuracy.POINT)))
        assertEquals("Quokka Den", HandoverRules.addressLine(row(9, "Quokka Den", "exact", sub = "")))
    }

    @Test
    fun aNameThenAnAddress() {
        assertEquals("Quokka Den" to "1600 Example Street, Erie, PA 16501", HandoverRules.nameAndAddress("Quokka Den, 1600 Example Street, Erie, PA 16501"))
        assertEquals("Quokka Den" to "12A Example Street", HandoverRules.nameAndAddress("Quokka Den, 12A Example Street"))
        assertEquals("Quokka Den" to "12-14 Example Street", HandoverRules.nameAndAddress(" Quokka Den ,  12-14 Example Street "))
        assertNull(HandoverRules.nameAndAddress("1600 Example Street, Erie"))
        assertNull(HandoverRules.nameAndAddress("Quokka Den, Example Street, Erie"))
        assertNull(HandoverRules.nameAndAddress("Quokka Den"))
    }

    private val tables = StreetTables.load()
    private fun state(text: String) = HandoverRules.namedState(text, tables.states, tables.ambiguousStates)

    @Test
    fun theStateTheWordsName() {
        for (t in listOf("Columbus, OH 43215", "Columbus, Ohio", "Columbus Ohio", "Columbus OH 43215", "Columbus, OH, USA",
            "1600 Example Street, Columbus, OH 43215-1234", "Columbus; Ohio", "Columbus, Ohio, United States of America")) assertEquals("oh", state(t), t)
        assertEquals("pa", state("State Street, Erie, PA"))
        assertEquals("ny", state("New York, NY"))
        assertEquals("dc", state("Washington, DC"))
        assertEquals("dc", state("Example Street, District of Columbia"))
        for (t in listOf("Erie Zoo", "Pizza in Erie", "Columbus OH", "Columbus", "", "USA")) assertNull(state(t), t)
    }

    @Test
    fun aStatesName() {
        assertEquals("Ohio", HandoverRules.stateName("oh", tables.states))
        assertEquals("Pennsylvania", HandoverRules.stateName("pa", tables.states))
        assertEquals("North Carolina", HandoverRules.stateName("nc", tables.states))
        assertEquals("District of Columbia", HandoverRules.stateName("dc", tables.states))
    }

    /** K1: the town the words give (after the name, without the country, the state and its ZIP, never a street). */
    @Test
    fun theTownTheWordsGive() {
        fun town(t: String) = HandoverRules.townOf(t, tables.states, tables.ambiguousStates)
        assertEquals("erie", town("Erie Zoo, Erie, PA"))
        assertEquals("erie", town("Erie Zoo, Erie PA 16508"))
        assertEquals("erie", town("Erie Zoo, Erie"))
        assertEquals("columbus", town("Quokka Den, 12 Some Street, Columbus, OH"))
        assertEquals("erie", town("Quokka Den, 815 Example Street, Erie, PA, USA"))
        assertNull(town("Erie Zoo"))
        assertNull(town("Erie Zoo, PA"))
        assertNull(town("Quokka Den, 815 Example Street"))
    }

    private val installedPa = setOf("pa")
    private val packStates = mapOf("us-pa" to "pa", "us-oh" to "oh")
    private fun decide(text: String, r: MultiSearchResponse?, installed: Set<String> = installedPa) =
        HandoverRules.decideText(text, r, tables.states, tables.ambiguousStates, installed, packStates)

    /** K1: "Columbus, Ohio" is Ohio's, even when Pennsylvania (installed) has an exact "Columbus" of its own. */
    @Test
    fun aStateNamedElsewhereBeatsANamesakeHere() {
        val columbusPa = resp(row(1, "Columbus", "exact", sub = "Warren County", kind = SearchResult.Kind.PLACE, cat = "village"))
        assertEquals(TextAct.OutsideState("oh"), decide("Columbus, Ohio", columbusPa))
        assertEquals(TextAct.OutsideState("oh"), decide("Columbus, Ohio", null))   // asked before any lookup
        assertEquals(TextAct.OutsideState("oh"), decide("Quokka Den, 12 Some Street, Columbus, OH", resp(row(1, "Quokka Den", "exact"))))
        // the same words with Ohio installed: Pennsylvania's Columbus is not Ohio's
        assertEquals(TextAct.Search, decide("Columbus, Ohio", columbusPa, installed = setOf("pa", "oh")))
        // no state named: the namesake is the clear match (the preview shows its second line; nothing starts)
        assertEquals(TextAct.Clear(columbusPa.results[0]), decide("Columbus", columbusPa))
    }

    /** K1: a town given in the words must be the row's. */
    @Test
    fun aTownNamedMustBeTheRowsTown() {
        val zoo = resp(row(1, "Erie Zoo", "exact", sub = "423 West 38th Street, Erie"))
        assertEquals(TextAct.Clear(zoo.results[0]), decide("Erie Zoo, Erie, PA", zoo))
        val elsewhere = resp(row(1, "Erie Zoo", "exact", sub = "Main Street, Corry"))
        assertEquals(TextAct.Search, decide("Erie Zoo, Erie, PA", elsewhere))
        // a word inside another is not the town
        val inside = resp(row(1, "Erie Zoo", "exact", sub = "Eriez Road, Corry"))
        assertEquals(TextAct.Search, decide("Erie Zoo, Erie", inside))
    }

    /** K1: a state named that is installed takes a clear match only from that state's pack. */
    @Test
    fun aStateNamedHereTakesOnlyItsOwnPack() {
        val both = setOf("pa", "oh")
        val inOhio = resp(row(1, "Quokka Den", "exact", sub = "Erie", pack = "us-oh"))
        assertEquals(TextAct.Search, decide("Quokka Den, Erie, PA", inOhio, installed = both))
        val inPa = resp(row(1, "Quokka Den", "exact", sub = "Erie", pack = "us-pa"))
        assertEquals(TextAct.Clear(inPa.results[0]), decide("Quokka Den, Erie, PA", inPa, installed = both))
    }

    @Test
    fun aNameAndAddressIsTriedWhenNothingIsClear() {
        assertEquals(TextAct.TryAddress("Quokka Den", "1600 Example Street, Erie, PA 16501"),
            decide("Quokka Den, 1600 Example Street, Erie, PA 16501", resp()))
        assertEquals(TextAct.TryAddress("Quokka Den", "1600 Example Street, Erie, PA 16501"),
            decide("Quokka Den, 1600 Example Street, Erie, PA 16501", null))
        assertEquals(TextAct.Search, decide("Sheetz", resp(row(1, "Sheetz", "brand"))))
        assertEquals(TextAct.Search, decide("Erie Zoo", null))
    }

    /** PV2: a decision prints none of the words or rows. */
    @Test
    fun aDecisionPrintsNothing() {
        assertEquals("TextAct.OutsideState", TextAct.OutsideState("oh").toString())
        assertEquals("TextAct.Clear", TextAct.Clear(row(1, "Quokka Den", "exact")).toString())
        assertEquals("TextAct.TryAddress", TextAct.TryAddress("Quokka Den", "1600 Example Street").toString())
        assertEquals("Search", TextAct.Search.toString())
    }

    // ---------------------------------------------------------------- 6.5: by trip phase

    @Test
    fun whatAPlaceOpensInEachPhase() {
        assertEquals(Act.PREVIEW, HandoverRules.act(Phase.IDLE, true))
        assertEquals(Act.PREVIEW, HandoverRules.act(Phase.ARRIVED, true))
        assertEquals(Act.PREVIEW, HandoverRules.act(Phase.RESUME_OFFERED, true))
        // K2: while a trip is planned, chosen or guided the place is offered as a stop or as the new destination
        for (ph in listOf(Phase.PLANNING, Phase.CHOOSING, Phase.GUIDING, Phase.REROUTING)) {
            assertEquals(Act.STOP_CARD, HandoverRules.act(ph, true), "$ph")
            assertEquals(Act.ALREADY, HandoverRules.act(ph, true, atDestination = true), "$ph")
        }
        assertEquals(Act.NOTE, HandoverRules.act(Phase.GUIDING, false))
        assertEquals(Act.NOTE, HandoverRules.act(Phase.REROUTING, false))
        for (ph in listOf(Phase.IDLE, Phase.PLANNING, Phase.CHOOSING, Phase.ARRIVED, Phase.RESUME_OFFERED)) assertEquals(Act.WINDOW, HandoverRules.act(ph, false), "$ph")
        assertEquals(Act.PREVIEW, HandoverRules.act(Phase.IDLE, true, atDestination = true))
    }

    @Test
    fun aSearchPickGoesToTheCardDuringATrip() {
        for (ph in Phase.values()) {
            val trip = ph == Phase.PLANNING || ph == Phase.CHOOSING || ph == Phase.GUIDING || ph == Phase.REROUTING
            assertEquals(trip, HandoverRules.pickToCard(ph), "$ph")
        }
    }

    @Test
    fun addingAStateIsOfferedOnlyWithoutATrip() {
        for (ph in Phase.values()) assertEquals(ph == Phase.IDLE || ph == Phase.RESUME_OFFERED, HandoverRules.statesOffered(ph), "$ph")
    }

    @Test
    fun theAlreadyWordsFollowThePhase() {
        assertEquals("You are already on your way there.", HandoverRules.alreadyText(Phase.GUIDING))
        assertEquals("You are already on your way there.", HandoverRules.alreadyText(Phase.REROUTING))
        assertEquals("The route shown already goes there.", HandoverRules.alreadyText(Phase.PLANNING))
        assertEquals("The route shown already goes there.", HandoverRules.alreadyText(Phase.CHOOSING))
    }

    /** K16: a handed-over place is a recent once the user drives to it, not when its preview opens. */
    @Test
    fun aHandedOverPlaceIsRecentOnceGuidedTo() {
        val place = Place("Quokka Den", GeoPoint(42.13646, -80.08718), PlaceSource.SEARCH)
        assertTrue(HandoverRules.guidedTo(place, Phase.GUIDING, GeoPoint(42.13646, -80.08718)))
        assertFalse(HandoverRules.guidedTo(place, Phase.CHOOSING, GeoPoint(42.13646, -80.08718)))
        assertFalse(HandoverRules.guidedTo(place, Phase.GUIDING, GeoPoint(42.08418, -80.17321)))
        assertFalse(HandoverRules.guidedTo(place, Phase.GUIDING, null))
    }

    @Test
    fun theLogWordsAreFixed() {
        val words = listOf(
            HandoverRules.kindName(Handover.Point(p, null)),
            HandoverRules.kindName(Handover.Point(p, "Quokka Den")),
            HandoverRules.kindName(Handover.Text("Quokka Den", null)),
            HandoverRules.kindName(Handover.Unreadable),
        )
        assertEquals(listOf("point", "labelled point", "words", "unreadable"), words)
        for (w in words) assertFalse(w.any { it.isDigit() } || w.contains("quokka", ignoreCase = true), w)
    }

    @Test
    fun aLookupStillRunningWhenTheActivityFinishesOpensNothing() {
        val t = LinkTickets()
        val first = t.next()
        assertTrue(t.live(first))
        val second = t.next()   // a newer link takes over
        assertFalse(t.live(first))
        assertTrue(t.live(second))
        t.voidAll()             // K15: Back on the bare map finished the activity while "Finding the place..." ran
        assertFalse(t.live(second))
        assertTrue(t.live(t.next()))   // a link of the next launch opens as usual
    }

    /**
     * H6 (review 0.1.40): a link's words with a point open the search ranked there. Its line names the town there
     * ("Near Erie", P6) only where an installed state's search can name one; a point outside them (geo:39.9612,-82.9988?q=Pizza,
     * Columbus, Ohio, with Pennsylvania installed) gets no line, never "Near the map center" for a point the map is not on.
     */
    @Test
    fun aLinksSearchSaysNearOnlyWhereAnInstalledStateCanNameTheTown() {
        val pa = Bounds(west = -80.52, south = 39.72, east = -74.69, north = 42.27)   // made-up box about Pennsylvania
        val me = GeoPoint(42.1180, -80.0650)
        val columbus = GeoPoint(39.9612, -82.9988)
        assertEquals(SearchContext(p, NearSource.MAP_CENTER, distanceFrom = me), HandoverRules.linkContext(p, listOf(pa), from = me))
        assertEquals(SearchContext(p, NearSource.MAP_CENTER, distanceFrom = null), HandoverRules.linkContext(p, listOf(pa), from = null))
        // still ranked at the link's point, distances still from the user: only the line goes
        assertEquals(SearchContext(columbus, NearSource.NONE, distanceFrom = me), HandoverRules.linkContext(columbus, listOf(pa), from = me))
        assertEquals(SearchContext(columbus, NearSource.NONE, distanceFrom = null), HandoverRules.linkContext(columbus, listOf(pa), from = null))
        assertEquals(NearSource.NONE, HandoverRules.linkContext(p, emptyList(), from = me).source)
    }

    @Test
    fun theNotesSayWhatTheyMean() {
        assertEquals("This place is in Ohio, which is not on this phone. Add it to go there.", HandoverTexts.outsideState("Ohio", true))
        assertEquals("This place is in Ohio, which is not on this phone.", HandoverTexts.outsideState("Ohio", false))
        assertEquals("Beamline could not find a place in this link.", HandoverTexts.UNREADABLE)
        assertEquals("Beamline needs an installed state to find this place. Open the directions again once one is installed.", HandoverTexts.NEED_STATE)
        assertEquals("This place is outside the installed maps.", HandoverTexts.OUTSIDE_MAPS)
        assertEquals("You are already on your way there.", HandoverTexts.ALREADY_GOING)
        assertEquals("The route shown already goes there.", HandoverTexts.ALREADY_SHOWN)
        assertEquals("Finding the place...", HandoverTexts.FINDING)
        assertEquals("The directions open when you close this.", HandoverTexts.WAITING)
        assertEquals("Add a state", HandoverTexts.ADD_STATE)
        assertEquals("HandoverNote", HandoverNote("This place is in Ohio, which is not on this phone.", true).toString())
    }
}
