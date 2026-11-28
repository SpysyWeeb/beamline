package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 0.1.40 (play test P6) on the PA search pack (beamline.searchDb; skipped without it, failed in strict mode):
 * the place a query's words name ("coffee in corry": Corry) and the town nearest a point ("Near Corry").
 */
class NamedPlaceTest {
    private val corry = GeoPoint(41.9203, -79.6403)

    @Test
    fun theWordsNameAPlaceAndAPointHasATown() {
        val db = TestEnv.requireSearchDb()
        val erie = TestEnv.points.getValue("erie")
        SearchEngine(db).use { e ->
            val named = e.search("coffee in corry", erie.lat, erie.lon).namedPlace!!
            assertEquals("Corry", named.name)
            assertTrue(Geo.haversineKm(named.lat, named.lon, corry.lat, corry.lon) < 1.0, "$named")
            assertEquals("NamedPlace", named.toString())                        // PV2: the place is never printed
            assertNull(e.search("coffee", erie.lat, erie.lon).namedPlace)
            assertEquals("Corry", e.placeNear(corry.lat, corry.lon, 25.0))
            assertEquals("Erie", e.placeNear(42.129, -80.085, 25.0))
            assertNull(e.placeNear(42.45, -80.30, 25.0))                       // Lake Erie: no town within 25 km
            assertNull(e.placeNear(Double.NaN, -80.0, 25.0))
        }
        val pa = PackInfo(
            packId = "us-pa", name = "Pennsylvania", state = "PA", osmSnapshot = "2026-11-14", bounds = Bounds(-80.52109, 39.71977, -74.68967, 42.51613),
            attribution = "OpenStreetMap", folder = "packs/us-pa", mapFile = "packs/us-pa/map.pmtiles", mapMinZoom = 0, mapMaxZoom = 14,
            searchFile = File(db).absolutePath, addressesFile = null, addressesSkipped = true, searchProblem = null,
            searchRankingSha256 = "r", inRoutingSet = true, verified = true, bytesOnDisk = 0,
        )
        val set = InstalledSet(generation = 1, scanned = true, packs = listOf(pa))
        SearchHub(expectedFingerprint = null, warmUp = false).use { hub ->
            assertEquals("Corry", hub.search(set, "coffee in corry", GeoPoint(erie.lat, erie.lon)).namedPlace?.name)
            assertNull(hub.search(set, "coffee", GeoPoint(erie.lat, erie.lon)).namedPlace)
            assertEquals("Corry", hub.placeNear(set, corry, 25.0))
            assertNull(hub.placeNear(set, GeoPoint(43.5, -80.0), 25.0))        // outside the pack
        }
    }
}
