package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.model.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Item 51 (rule S12): places along the trip's route ahead. A straight made-up road going north from the Erie
 * Zoo, a point every km; made-up stores beside it.
 */
class AlongRouteTest {
    private val zoo = GeoPoint(42.0971, -80.07578)
    private val kmLat = 6371.0088 * Math.PI / 180.0
    private val kmLon = kmLat * cos(Math.toRadians(zoo.lat))
    private val rules = SearchAppRules.bundled()

    private fun road(km: Int) = (0..km).map { GeoPoint(zoo.lat + it / kmLat, zoo.lon) }
    private fun at(alongKm: Double, offKm: Double) = GeoPoint(zoo.lat + alongKm / kmLat, zoo.lon + offKm / kmLon)

    private fun row(id: Long, alongKm: Double, offKm: Double, tier: String?, score: Double = 1.0, pack: String = "us-pa"): PackResult {
        val p = at(alongKm, offKm)
        return PackResult(pack, SearchResult(id, SearchResult.Kind.POI, "Store $id", "Erie", "fuel", "Gas station", p.lat, p.lon, alongKm, null, null, score, "t", tier))
    }

    private fun answer(rows: List<PackResult>) = MultiSearchResponse("gas", rows, listOf("us-pa"), true, null, false, 1.0)

    private fun near(a: GeoPoint, b: GeoPoint) = abs(a.lat - b.lat) < 1e-5 && abs(a.lon - b.lon) < 1e-5

    @Test
    fun anchorsEverySampleUpToAheadOrTheLineEnd() {
        val long = road(70)
        val a = AlongRoute.anchors(long, 10.0, 60.0)
        assertEquals(7, a.size)
        for ((k, p) in a.withIndex()) assertTrue(near(p, long[k * 10]), "anchor $k: $p")
        // a shorter road: up to its end only
        val short = road(25)
        assertEquals(3, AlongRoute.anchors(short, 10.0, 60.0).size)
        // one point: itself; none: none
        assertEquals(listOf(zoo), AlongRoute.anchors(listOf(zoo), 10.0, 60.0))
        assertEquals(emptyList<GeoPoint>(), AlongRoute.anchors(emptyList(), 10.0, 60.0))
        // close shape points (a dense curve) do not change the spacing
        val dense = (0..700).map { GeoPoint(zoo.lat + it / (10 * kmLat), zoo.lon) }
        assertEquals(7, AlongRoute.anchors(dense, 10.0, 60.0).size)
    }

    @Test
    fun placeIsTheDistanceToTheLineAndAlongIt() {
        val (off, along) = AlongRoute.place(road(70), at(12.0, 1.0), 60.0)!!
        assertEquals(1.0, off, 0.01)
        assertEquals(12.0, along, 0.01)
        // before the start: the distance to the first point
        val (off2, along2) = AlongRoute.place(road(70), at(-3.0, 4.0), 60.0)!!
        assertEquals(5.0, off2, 0.01)
        assertEquals(0.0, along2, 0.01)
        // past the cut (60 km ahead): measured to the cut's end
        val (off3, along3) = AlongRoute.place(road(70), at(65.0, 0.0), 60.0)!!
        assertEquals(5.0, off3, 0.01)
        assertEquals(60.0, along3, 0.01)
        assertNull(AlongRoute.place(emptyList(), zoo, 60.0))
    }

    @Test
    fun aCategoryKeepsTheCorridorInOrderAlongTheRoute() {
        val asked = ArrayList<GeoPoint>()
        // every anchor answers the stations near it; the same station twice is listed once (the first answer's)
        val r = AlongRoute.search(road(70), rules, limit = 10) { p ->
            asked.add(p)
            answer(listOf(row(1, 25.0, 0.5, "category"), row(2, 4.0, 2.9, "category"), row(3, 9.0, 8.0, "category"), row(4, 2.0, -1.0, "category"),
                row(5, 65.0, 0.0, "category"), row(6, 30.0, 0.2, "prefix")))
        }
        assertEquals(7, asked.size)
        assertFalse(r.widened)
        // within 3 km of the road cut at 60 km (5 km past the cut is not): 4, 2, 1, the name match 6; ordered along the road
        assertEquals(listOf(4L, 2L, 1L, 6L), r.response.results.map { it.result.id })
        assertEquals(7.0, r.response.ms, 1e-9)
    }

    @Test
    fun aCategoryWithNoneInTheCorridorGivesTheNearestFew() {
        val r = AlongRoute.search(road(70), rules, limit = 10) { _ ->
            answer((1..8).map { row(it.toLong(), it * 3.0, 4.0 + it * 0.5, "category") })
        }
        assertTrue(r.widened)
        assertEquals((1L..5L).toList(), r.response.results.map { it.result.id })
    }

    @Test
    fun theMostNearTheRouteAreKept() {
        val many = (1..30).map { row(it.toLong(), it.toDouble(), (it % 3) * 0.5, "brand") }
        val r = AlongRoute.search(road(70), rules, limit = 10) { answer(many) }
        assertEquals(rules.alongMaxResults, r.response.results.size)
        // the 20 nearest the road: those 0 and 0.5 km off (20 of them), in order along it
        assertTrue(r.response.results.all { (it.result.id % 3L) != 2L })
        assertEquals(r.response.results.map { it.result.id }.sorted(), r.response.results.map { it.result.id })
    }

    @Test
    fun aNameKeepsScoreOrderAndItsLimitWithoutACorridor() {
        var n = 0
        val r = AlongRoute.search(road(70), rules, limit = 3) { _ ->
            n++
            // the anchors answer different rows; a name 9 km off the road still counts
            answer(listOf(row(10L + n, n * 8.0, 9.0, "exact", score = 1.0 + n), row(1, 5.0, 0.0, "exact", score = 0.5)))
        }
        assertFalse(r.widened)
        assertEquals(listOf(17L, 16L, 15L), r.response.results.map { it.result.id })
        // the same id in two packs is two places
        val two = AlongRoute.search(road(5), rules, limit = 10) { answer(listOf(row(1, 1.0, 0.0, "exact"), row(1, 1.0, 0.0, "exact", pack = "us-oh"))) }
        assertEquals(listOf("us-pa", "us-oh"), two.response.results.map { it.packId })
    }
}
