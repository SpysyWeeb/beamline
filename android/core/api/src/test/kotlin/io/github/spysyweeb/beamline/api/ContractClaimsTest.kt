package io.github.spysyweeb.beamline.api

import io.github.spysyweeb.beamline.api.data.RoutingSet
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.routing.banner.ManeuverIcon
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import io.github.spysyweeb.beamline.routing.banner.LaneMark as BannerLaneMark

/** The claims docs/architecture.md makes about :core:api, checked against the real modules. */
class ContractClaimsTest {

    @Test
    fun `maneuver kinds and lane marks have the banner model's names, so mapping by name works`() {
        assertEquals(ManeuverIcon.entries.map { it.name }, ManeuverKind.entries.map { it.name })
        assertEquals(BannerLaneMark.entries.map { it.name }, LaneMark.entries.map { it.name })
    }

    @Test
    fun `level-2 tile ids equal the routing module's`() {
        val points = listOf(
            42.053308 to -80.079579, // Downs Dr, Erie
            42.028694 to -80.128884, // I-90 west of exit 24
            41.947 to -80.519, // the Ohio line
            40.0 to -75.0, -89.9 to -179.9, 89.9 to 179.9, 0.0 to 0.0,
        )
        for ((lat, lon) in points) {
            assertEquals(Geo.tileId(LatLon(lat, lon), 2), RoutingSet.level2TileId(GeoPoint(lat, lon)), "$lat,$lon")
        }
        val set = RoutingSet("valhalla.json", "b", "3.9.1", listOf("PA"), setOf(RoutingSet.level2TileId(GeoPoint(42.05, -80.08))))
        assertTrue(set.covers(GeoPoint(42.06, -80.07)))
        assertFalse(set.covers(GeoPoint(41.0, -80.07)))
    }

    @Test
    fun `bounds grow on every side and enclose points`() {
        val pa = Bounds(-80.52, 39.72, -74.69, 42.27)
        val g = pa.grownByKm(50.0)
        assertEquals(50.0 / 111.32, pa.south - g.south, 1e-9)
        assertTrue(g.west < pa.west && g.east > pa.east && g.north > pa.north)
        // Conneaut, Ohio, is outside Pennsylvania but within 50 km of it
        val conneaut = GeoPoint(41.947, -80.554)
        assertFalse(pa.contains(conneaut))
        assertTrue(g.contains(conneaut))
        assertEquals(Bounds(-80.1, 42.0, -80.0, 42.1), Bounds.around(listOf(GeoPoint(42.0, -80.0), GeoPoint(42.1, -80.1))))
        assertNull(Bounds.around(emptyList()))
        assertEquals(pa, Bounds.of(listOf(-80.52, 39.72, -74.69, 42.27)))
    }
}
