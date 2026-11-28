package io.github.spysyweeb.beamline.feature.states.model

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.download.Catalog
import io.github.spysyweeb.beamline.download.CatalogLicence
import io.github.spysyweeb.beamline.download.CatalogPack
import io.github.spysyweeb.beamline.download.CatalogPart
import io.github.spysyweeb.beamline.download.CatalogState
import io.github.spysyweeb.beamline.download.CatalogView
import io.github.spysyweeb.beamline.download.NetworkState
import io.github.spysyweeb.beamline.download.TransferFile
import io.github.spysyweeb.beamline.feature.states.StatesMode
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * states.md P11 (audit F65, F66): the States screen's one scrolling list, in the screen's order, and
 * a selection's row found by that order (the old index left out the section titles: Ohio was 1, not 7).
 */
class ListEntriesTest {
    private val hex = "a".repeat(64)
    private val now = 1_794_830_400_000L // Nov 16, 2026, 12:00 UTC

    private fun part(name: String, file: String, bytes: Long) =
        CatalogPart(name, file, false, bytes, hex, TransferFile("../$file.gz", "identity", bytes, hex), "© OpenStreetMap contributors")

    private fun pack(id: String, state: String, name: String) = CatalogPack(
        id, state, name, 202611152241, 3, "2026-11-14", "© OpenStreetMap contributors", Bounds(-80.5, 39.7, -74.7, 42.5),
        TransferFile("../manifest.json", "identity", 2210, hex),
        listOf(part("search", "search.sqlite", 154_705_920), part("routing", "routing.tar", 634_429_440), part("map", "map.pmtiles", 244_703_514)),
    )

    private val catalog = CatalogState.Ready(
        CatalogView(
            Catalog(1, "2026-11-15T22:41:07Z", CatalogLicence("ODbL-1.0", "Open Database License 1.0", "https://opendatacommons.org/licenses/odbl/1-0/", "Map data"),
                listOf(pack("us-pa", "PA", "Pennsylvania"), pack("us-oh", "OH", "Ohio")), emptyList()),
            now, fromCache = false,
        ),
    )

    private val installedPa = PackInfo(
        "us-pa", "Pennsylvania", "PA", "2026-11-14", Bounds(-80.5, 39.7, -74.7, 42.5), "© OpenStreetMap contributors", "/p/us-pa", "/p/us-pa/map.pmtiles",
        0, 14, "/p/us-pa/search.sqlite", null, false, null, null, true, true, 1_113_841_084, 202611152241,
    )

    private fun ui(query: String = "") = StatesPresenter.present(
        StatesInput(
            mode = StatesMode.MANAGE,
            names = mapOf("PA" to "Pennsylvania", "OH" to "Ohio", "RI" to "Rhode Island", "CT" to "Connecticut", "MA" to "Massachusetts", "NY" to "New York"),
            catalog = catalog, installed = InstalledSet(scanned = true, packs = listOf(installedPa)), job = null,
            network = NetworkState(connected = true, unmetered = true), units = UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL),
            nowMs = now, zone = ZoneId.of("America/New_York"), freeBytes = 24_000_000_000, moves = true, query = query,
        ),
    )

    @Test
    fun entriesFollowTheScreenAndCountTheTitles() {
        val ui = ui()
        val entries = listEntries(ui, withMap = true)
        val keys = entries.map { it.key }
        assertEquals(
            listOf("map", "search", "no-match", "title-On this phone", "row-on-phone-PA", "title-Add a state", "title-Available", "row-available-OH", "title-Not available yet"),
            keys.take(9),
        )
        assertEquals("footer", keys.last())
        assertEquals("", (entries.single { it is ListEntry.NoMatch } as ListEntry.NoMatch).text, "empty while something matches")
        // the subsections under "Add a state" are not top-level headings
        assertEquals(listOf(true, true, false, false), entries.filterIsInstance<ListEntry.Title>().map { it.top })
        assertEquals(7, rowIndex(entries, "OH"))
        assertEquals(-1, rowIndex(entries, "TX"))
        // today's index of the same row (SCR:340-342) left the titles and the entries above the rows out
        assertEquals(1, (ui.onPhone + ui.available + ui.notAvailable + ui.needsNewer).indexOfFirst { it.code == "OH" })
        // in landscape the map is its own pane
        assertTrue(listEntries(ui, withMap = false).none { it == ListEntry.Map })
        assertEquals(6, rowIndex(listEntries(ui, withMap = false), "OH"))
        assertEquals(listOf("RI", "CT", "MA", "NY").sorted(), ui.notAvailable.map { it.code }.sorted())
    }

    @Test
    fun aSearchThatMatchesNothingPutsItsLineUnderTheField() {
        val keys = listEntries(ui("zz"), withMap = true).map { it.key }
        assertEquals(listOf("map", "search", "no-match", "footer"), keys)
        val line = listEntries(ui("zz"), withMap = true).single { it is ListEntry.NoMatch } as ListEntry.NoMatch
        assertEquals("No state matches your search.", line.text)
    }
}
