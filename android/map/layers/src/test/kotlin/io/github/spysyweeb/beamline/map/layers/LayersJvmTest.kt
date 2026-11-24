package io.github.spysyweeb.beamline.map.layers

import io.github.spysyweeb.beamline.api.map.AlternativeLine
import io.github.spysyweeb.beamline.api.map.MapPin
import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.map.PinKind
import io.github.spysyweeb.beamline.api.map.RouteLines
import io.github.spysyweeb.beamline.api.map.SavedMarker
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** The Android-free parts of :map:layers: route_colors.json, the route split, the tap rule, the glide. */
class LayersJvmTest {
    /** Unit tests run in the module folder (android/map/layers). */
    private val copy = File("src/main/res/raw/route_colors.json")
    private val source = File("../../../assets/route_colors.json")

    @Test
    fun `the route colours copy equals assets route_colors json`() {
        assertTrue(copy.isFile, copy.absolutePath)
        assertEquals(source.readText(Charsets.UTF_8).replace("\r\n", "\n"), copy.readText(Charsets.UTF_8), "run android/map/layers/scripts/sync_art.py")
    }

    @Test
    fun `candidate B with its traveled and alternative colours and the widths`() {
        val c = RouteColors.parse(copy.readText(Charsets.UTF_8))
        assertEquals("B", c.candidate)
        assertEquals(ThemeLines(LinePaint("#4A8CF7", "#1F5BC4"), LinePaint("#9EBAE7", "#8AA4D0"), LinePaint("#A7AFB9", "#7E8792")), c.lines(MapTheme.DAY))
        assertEquals(ThemeLines(LinePaint("#3A80F0", "#1C3F86"), LinePaint("#2B4B7D", "#1E2D4D"), LinePaint("#5B636D", "#2B3036")), c.lines(MapTheme.NIGHT))
        assertEquals(listOf(8.0 to 3.0, 12.0 to 5.0, 14.0 to 7.0, 16.0 to 10.0, 18.0 to 16.0, 20.0 to 26.0), c.fillWidths)
        assertEquals(listOf(8.0 to 4.5, 12.0 to 7.0, 14.0 to 9.5, 16.0 to 13.0, 18.0 to 20.0, 20.0 to 31.0), c.casingWidths)
        assertEquals(1.5, c.widthBase)
    }

    @Test
    fun `route colours refuse a broken file`() {
        val good = copy.readText(Charsets.UTF_8)
        for (bad in listOf(
            "{",
            good.replace("\"selected\": \"B\"", "\"selected\": \"Z\""),
            good.replace("\"#4A8CF7\"", "\"blue\""),
            good.replace("\"version\": 1", "\"version\": 2"),
            good.replace("\"type\": \"exponential\"", "\"type\": \"linear\""),
        )) {
            assertThrows<IllegalArgumentException> { RouteColors.parse(bad) }
        }
    }

    private val line = listOf(GeoPoint(42.0, -80.0), GeoPoint(42.01, -80.0), GeoPoint(42.02, -80.0), GeoPoint(42.03, -80.0))

    @Test
    fun `the route is split at the traveled cursor`() {
        val at = GeoPoint(42.015, -80.0)
        val (driven, ahead) = SceneParts.split(line, RouteCursor(1, at))
        assertEquals(listOf(line[0], line[1], at), driven)
        assertEquals(listOf(at, line[2], line[3]), ahead)
        assertEquals(emptyList<GeoPoint>() to line, SceneParts.split(line, null))
        // at the very start: a driven part of length 0
        val (d0, a0) = SceneParts.split(line, RouteCursor(0, line[0]))
        assertEquals(listOf(line[0], line[0]), d0)
        assertEquals(listOf(line[0]) + line.drop(1), a0)
        // at the end: nothing ahead
        val (d1, a1) = SceneParts.split(line, RouteCursor(3, line[3]))
        assertEquals(line + line[3], d1)
        assertTrue(a1.isEmpty())
        // a cursor index past the end is held at the last point
        assertEquals(line + line[3], SceneParts.split(line, RouteCursor(9, line[3])).first)
    }

    @Test
    fun `saved markers - icons, Home on top, drawing order`() {
        assertEquals("saved_home", SceneParts.savedIcon(SavedKind.HOME))
        assertEquals("saved_work", SceneParts.savedIcon(SavedKind.WORK))
        assertEquals("saved_place", SceneParts.savedIcon(SavedKind.OTHER))
        val p = GeoPoint(42.13646, -80.08718)
        val home = SavedMarker("s1", p, SavedKind.HOME, 10.0, 1.25)
        val work = SavedMarker("s2", p, SavedKind.WORK, 10.0, 1.25)
        val star = SavedMarker("s3", p, SavedKind.OTHER, 12.0, 1.25)
        assertEquals(listOf(star, work, home), SceneParts.savedInOrder(listOf(home, star, work)))
    }

    @Test
    fun `the tap rule - pins over saved markers over POI labels, Home over a star`() {
        val poi = MapPoi("Erie Maritime Museum", "museum", GeoPoint(42.13646, -80.08718))
        assertEquals("stop:0" to null, SceneParts.tap(listOf("stop:0" to 0), listOf("s3" to SavedKind.OTHER), poi))
        // overlapping pins: the one drawn on top (picked over the destination over a stop), whatever order MapLibre returns
        val stop = MapPin("stop:0", GeoPoint(42.0, -80.0), PinKind.STOP, number = 1)
        val dest = MapPin("stop:1", GeoPoint(42.0, -80.0), PinKind.DESTINATION)
        val picked = MapPin("picked", GeoPoint(42.0, -80.0), PinKind.PICKED)
        assertEquals("stop:1" to null, SceneParts.tap(listOf(stop, dest).map { it.id to SceneParts.pinSortKey(it) }, emptyList(), null))
        assertEquals("stop:1" to null, SceneParts.tap(listOf(dest, stop).map { it.id to SceneParts.pinSortKey(it) }, emptyList(), null))
        assertEquals("picked" to null, SceneParts.tap(listOf(stop, picked, dest).map { it.id to SceneParts.pinSortKey(it) }, emptyList(), null))
        assertEquals("saved:s1" to null, SceneParts.tap(emptyList(), listOf("s3" to SavedKind.OTHER, "s1" to SavedKind.HOME), poi))
        assertEquals("saved:s2" to null, SceneParts.tap(emptyList(), listOf("s2" to SavedKind.WORK, "s3" to SavedKind.OTHER), null))
        assertEquals(null to poi, SceneParts.tap(emptyList(), emptyList(), poi))
        assertEquals(null to null, SceneParts.tap(emptyList(), emptyList(), null))
    }

    @Test
    fun `pin images by kind, number and reached`() {
        val p = GeoPoint(42.0, -80.0)
        assertEquals(Ids.PIN_DEST, SceneParts.pinImage(MapPin("stop:2", p, PinKind.DESTINATION)))
        assertEquals(Ids.PIN_DEST_REACHED, SceneParts.pinImage(MapPin("stop:2", p, PinKind.DESTINATION, reached = true)))
        assertEquals(Ids.PIN_PICKED, SceneParts.pinImage(MapPin("picked", p, PinKind.PICKED)))
        assertEquals("beamline-pin-stop-3", SceneParts.pinImage(MapPin("stop:2", p, PinKind.STOP, number = 3)))
        assertEquals("beamline-pin-stop-1-reached", SceneParts.pinImage(MapPin("stop:0", p, PinKind.STOP, number = 1, reached = true)))
        assertEquals("beamline-pin-stop-9", SceneParts.pinImage(MapPin("stop:20", p, PinKind.STOP, number = 21)))
        assertEquals("beamline-pin-stop-0", SceneParts.pinImage(MapPin("stop:0", p, PinKind.STOP, number = null)))
    }

    @Test
    fun `the glide interpolates the point and turns the shorter way`() {
        val a = GeoPoint(42.0, -80.0)
        val b = GeoPoint(42.001, -80.002)
        assertEquals(a, SceneParts.lerp(a, b, 0.0))
        assertEquals(b, SceneParts.lerp(a, b, 1.0))
        val mid = SceneParts.lerp(a, b, 0.5)
        assertEquals(42.0005, mid.lat, 1e-12)
        assertEquals(-80.001, mid.lon, 1e-12)
        assertEquals(0.0, SceneParts.lerpBearing(350.0, 10.0, 0.5), 1e-9)
        assertEquals(355.0, SceneParts.lerpBearing(350.0, 10.0, 0.25), 1e-9)
        assertEquals(90.0, SceneParts.lerpBearing(80.0, 100.0, 0.5), 1e-9)
        assertEquals(170.0, SceneParts.lerpBearing(190.0, 150.0, 0.5), 1e-9)
    }

    @Test
    fun `a glide moves from where the marker is to the new point over its time`() {
        val g = Glide()
        assertNull(g.at(0))
        val a = GeoPoint(42.0, -80.0)
        val b = GeoPoint(42.001, -80.0)
        // the first position: drawn where it is
        g.moveTo(a, 60.0, 1_000, nowMs = 10_000)
        assertEquals(a to 60.0, g.at(10_000))
        assertFalse(g.moving(10_000))
        // the next: from a to b over 1 s, the bearing the shorter way
        g.moveTo(b, 80.0, 1_000, nowMs = 11_000)
        assertTrue(g.moving(11_000))
        assertEquals(a to 60.0, g.at(11_000))
        val half = g.at(11_500)!!
        assertEquals(42.0005, half.first.lat, 1e-12)
        assertEquals(70.0, half.second, 1e-9)
        assertEquals(500L, g.leftMs(11_500))
        assertEquals(b to 80.0, g.at(12_000))
        assertFalse(g.moving(12_000))
        assertEquals(0L, g.leftMs(12_500))
    }

    @Test
    fun `a glide keeps its time for another look of the same point and starts again for a new point`() {
        val g = Glide()
        val a = GeoPoint(42.0, -80.0)
        val b = GeoPoint(42.001, -80.0)
        val c = GeoPoint(42.002, -80.0)
        g.jump(a, 0.0)
        g.moveTo(b, 0.0, 1_000, nowMs = 0)
        // the same point again (lenses lit, a cone turned): nothing restarts; a new bearing is turned to
        g.turnTo(20.0)
        assertEquals(b, g.target)
        assertEquals(42.0005, g.at(500)!!.first.lat, 1e-12)
        assertEquals(10.0, g.at(500)!!.second, 1e-9)
        // a new point in the middle of the glide: from where the marker is now
        g.moveTo(c, 20.0, 1_000, nowMs = 500)
        assertEquals(42.0005, g.at(500)!!.first.lat, 1e-12)
        assertEquals(42.00125, g.at(1_000)!!.first.lat, 1e-12)
        assertEquals(c, g.at(1_500)!!.first)
        // 0 jumps; clear draws nothing
        g.moveTo(a, 0.0, 0, nowMs = 1_600)
        assertEquals(a to 0.0, g.at(1_600))
        assertFalse(g.moving(1_600))
        g.clear()
        assertNull(g.at(1_700))
        assertNull(g.target)
        assertEquals(3_000L, Glide.MAX_PUCK_GLIDE_MS)
    }

    private fun info(id: String, label: Boolean = false, minZoom: Double = 0.0) = StyleLayerInfo(id, label, minZoom)

    @Test
    fun `in a composed style the route goes under the first state copy of roads_oneway and every POI copy is tapped`() {
        // StyleComposer with two states and no overview: the states' copies layer by layer
        val layers = listOf(
            info("background"), info("earth@us-oh"), info("earth@us-pa"), info("roads_highway@us-oh"), info("roads_highway@us-pa"),
            info("roads_oneway@us-oh", true, 15.0), info("roads_oneway@us-pa", true, 15.0),
            info("pois@us-oh", true), info("pois@us-pa", true), info("pois_driving@us-oh", true), info("pois_driving@us-pa", true),
        )
        assertEquals(RoutePlacement("roads_oneway@us-oh", null, null), SceneParts.routePlacement(layers))
        assertEquals(listOf("pois@us-oh", "pois@us-pa", "pois_driving@us-oh", "pois_driving@us-pa"), SceneParts.poiLayersIn(layers.map { it.id }))
        // the plain style (no composer) and a style without the layer: under the first label, else on top
        val plain = listOf(info("roads_major"), info("roads_oneway", true, 15.0), info("roads_oneway_reverse", true, 15.0), info("pois", true))
        assertEquals(RoutePlacement("roads_oneway", null, null), SceneParts.routePlacement(plain))
        assertEquals(listOf("pois"), SceneParts.poiLayersIn(plain.map { it.id }))
        assertEquals(RoutePlacement("pois", null, null), SceneParts.routePlacement(listOf(info("background"), info("roads_major"), info("pois", true))))
        assertEquals(RoutePlacement(null, null, null), SceneParts.routePlacement(listOf(info("background"), info("roads_major"))))
        // our own layers never count as the style's labels
        assertEquals(RoutePlacement("pois", null, null), SceneParts.routePlacement(listOf(info("beamline-saved-over", true), info("pois", true))))
    }

    @Test
    fun `with the overview map and a state the route is there twice - under the overview's labels below the states' zoom`() {
        // StyleComposer: all the overview's copies first (its labels up to z8), then the states' copies from z8
        val layers = listOf(
            info("background"), info("earth@overview"), info("roads_highway@overview"),
            info("water_label_ocean@overview", true), info("places_country@overview", true),
            info("earth@us-pa", minZoom = 8.0), info("roads_highway@us-pa", minZoom = 8.0),
            info("roads_oneway@us-pa", true, 15.0), info("pois@us-pa", true, 8.0),
        )
        assertEquals(RoutePlacement("roads_oneway@us-pa", 8.0, "water_label_ocean@overview"), SceneParts.routePlacement(layers))
        // only the overview: under its first label, one set (roads_oneway starts at z15, the overview stops at z10)
        val overviewOnly = listOf(info("background"), info("earth@overview"), info("roads_highway@overview"), info("water_label_ocean@overview", true))
        assertEquals(RoutePlacement("water_label_ocean@overview", null, null), SceneParts.routePlacement(overviewOnly))
        // states drawn at every zoom: one set
        val ungated = listOf(info("earth@overview"), info("places@overview", true), info("earth@us-pa"), info("roads_oneway@us-pa", true))
        assertEquals(RoutePlacement("roads_oneway@us-pa", null, null), SceneParts.routePlacement(ungated))
    }

    /**
     * The real day style composed as StyleComposer composes it (its KDoc and assets/rules/map-compose.json:
     * the overview's copies first, never above overview_max_zoom, its labels only below
     * base_label_max_zoom when a state is there too; then each state's copies from state_min_zoom).
     */
    @Test
    fun `the real day style composed with the overview and Pennsylvania`() {
        val style = Json.parseToJsonElement(File("../../app/src/main/assets/style/day.json").readText(Charsets.UTF_8)).jsonObject
        val rules = Json.parseToJsonElement(File("../../app/src/main/assets/rules/map-compose.json").readText(Charsets.UTF_8)).jsonObject
        fun num(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.doubleOrNull
        val stateMin = num(rules, "state_min_zoom")!!
        val labelMax = num(rules, "base_label_max_zoom")!!
        val overviewMax = num(rules, "overview_max_zoom")!!
        val labelTypes = rules["label_layer_types"]!!.jsonArray.map { it.jsonPrimitive.content }
        data class T(val id: String, val tiles: Boolean, val label: Boolean, val min: Double, val max: Double)
        val template = style["layers"]!!.jsonArray.map { it.jsonObject }.map { o ->
            T(o["id"]!!.jsonPrimitive.content, o["source"]?.jsonPrimitive?.content == "protomaps",
                o["type"]!!.jsonPrimitive.content in labelTypes, num(o, "minzoom") ?: 0.0, num(o, "maxzoom") ?: 24.0)
        }
        val composed = mutableListOf<StyleLayerInfo>()
        for (t in template) {
            if (!t.tiles) { composed += info(t.id); continue }
            val max = minOf(t.max, if (t.label) minOf(labelMax, overviewMax) else overviewMax)
            if (t.min < max) composed += info("${t.id}@overview", t.label, t.min)
        }
        for (t in template.filter { it.tiles }) {
            val min = maxOf(t.min, stateMin)
            if (min < t.max) composed += info("${t.id}@us-pa", t.label, min)
        }
        val place = SceneParts.routePlacement(composed)
        // the overview's first label is its ocean label (address, waterway and one-way layers begin above z10)
        assertEquals(RoutePlacement("roads_oneway@us-pa", stateMin, "water_label_ocean@overview"), place)
        // every overview label comes after the overview set's anchor; every overview road before it
        val ids = composed.map { it.id }
        val at = ids.indexOf(place.overviewAnchor)
        assertTrue(composed.filter { it.id.endsWith("@overview") && it.label }.all { ids.indexOf(it.id) >= at })
        assertTrue(ids.filter { it.startsWith("roads_") && it.endsWith("@overview") && !composed.first { c -> c.id == it }.label }.all { ids.indexOf(it) < at })
        // without the overview: one set under roads_oneway@us-pa
        assertEquals(RoutePlacement("roads_oneway@us-pa", null, null), SceneParts.routePlacement(composed.filterNot { it.id.endsWith("@overview") }))
    }

    @Test
    fun `the real day style has roads_oneway below every label layer`() {
        val style = File("../../app/src/main/assets/style/day.json").readText(Charsets.UTF_8)
        val ids = Regex("\"id\":\\s*\"([^\"]+)\"").findAll(style).map { it.groupValues[1] }.toList()
        assertEquals("roads_oneway", SceneParts.routePlacement(ids.map { info(it) }).anchor)
        assertEquals(listOf("pois", "pois_driving"), SceneParts.poiLayersIn(ids))
    }

    // ---- the route cut (review finding 1: a cursor step must not send the whole line) ----------

    /** A straight line of [n] points, about 10 m apart, east from downtown Erie. */
    private fun longLine(n: Int) = (0 until n).map { GeoPoint(42.1292, -80.0851 + it * 0.0001211) }

    private fun mid(line: List<GeoPoint>, i: Int) = SceneParts.lerp(line[i], line[i + 1], 0.5)

    @Test
    fun `the four pieces join into the driven part and the part ahead of the split`() {
        val line = longLine(300)
        for (i in 0 until line.lastIndex step 7) {
            val cursor = RouteCursor(i, mid(line, i))
            val p = RouteCut.pieces(line, cursor)
            val (driven, ahead) = SceneParts.split(line, cursor)
            fun join(a: List<GeoPoint>, b: List<GeoPoint>) = when {
                a.isEmpty() -> b
                b.isEmpty() -> a
                else -> { assertEquals(a.last(), b.first(), "pieces touch at $i"); a + b.drop(1) }
            }
            assertEquals(driven, join(p.drivenFar, p.drivenNear), "driven at $i")
            assertEquals(ahead, join(p.aheadNear, p.aheadFar), "ahead at $i")
            assertTrue(p.drivenNear.size <= RouteCut.CHUNK + 2 && p.aheadNear.size <= RouteCut.CHUNK + 2, "near pieces short at $i")
        }
        // no cursor: the whole line ahead, far; at the very end: nothing ahead
        assertEquals(RoutePieces(emptyList(), emptyList(), emptyList(), line), RouteCut.pieces(line, null))
        val end = RouteCut.pieces(line, RouteCursor(line.lastIndex, line.last()))
        assertTrue(end.aheadNear.isEmpty() && end.aheadFar.isEmpty())
        assertEquals(line + line.last(), join2(end.drivenFar, end.drivenNear))
        // a line of fewer than two points draws nothing
        assertEquals(RoutePieces(emptyList(), emptyList(), emptyList(), emptyList()), RouteCut.pieces(line.take(1), RouteCursor(0, line[0])))
    }

    private fun join2(a: List<GeoPoint>, b: List<GeoPoint>) = if (a.isEmpty()) b else a + b.drop(1)

    @Test
    fun `a cursor step on a long route sends only the short near pieces`() {
        val line = longLine(5_000)
        val alternatives = listOf(AlternativeLine(2, line.take(10)))
        val s = RouteSources()
        fun route(i: Int?) = RouteLines(1, line, i?.let { RouteCursor(it, mid(line, it)) }, emptyList())
        // choosing: everything is sent once; the same lines again send nothing
        val first = s.update(RouteLines(1, line, null, alternatives))
        assertEquals(emptyList<GeoPoint>() to line, first.far)
        assertEquals(emptyList<GeoPoint>() to emptyList<GeoPoint>(), first.near)
        assertEquals(listOf(line.take(10)), first.alternatives)
        assertEquals(RouteSources.Update(null, null, null), s.update(RouteLines(1, line, null, alternatives)))
        // guiding: the first cursor sends all; then each step inside one chunk only the near pieces
        val start = s.update(route(130))
        assertTrue(start.far != null && start.near != null && start.alternatives == emptyList<List<GeoPoint>>())
        for (i in 131 until 192) {
            val u = s.update(route(i))
            assertNull(u.far, "far pieces kept at $i")
            assertNull(u.alternatives, "alternatives kept at $i")
            val (d, a) = u.near!!
            assertTrue(d.size + a.size <= RouteCut.CHUNK + 4, "near pieces short at $i: ${d.size} + ${a.size}")
        }
        // entering the next chunk sends the far pieces once
        val next = s.update(route(192))
        assertEquals(192 + 1, next.far!!.first.size)
        assertEquals(line.size - 256, next.far!!.second.size)
        assertNull(s.update(route(193)).far)
        // the same cursor again: nothing; another route: everything; after a style load: everything
        assertEquals(RouteSources.Update(null, null, null), s.update(route(193)))
        val other = s.update(RouteLines(9, line.take(500), RouteCursor(193, mid(line, 193)), emptyList()))
        assertTrue(other.far != null && other.near != null)
        s.reset()
        val again = s.update(RouteLines(9, line.take(500), RouteCursor(193, mid(line, 193)), emptyList()))
        assertTrue(again.far != null && again.near != null && again.alternatives != null)
        // the route gone: empty pieces sent
        val gone = s.update(null)
        assertEquals(emptyList<GeoPoint>() to emptyList<GeoPoint>(), gone.far)
    }

    @Test
    fun `a tap on several POI labels reports the one nearest the finger`() {
        val diner = MapPoi("Test Diner", "restaurant", GeoPoint(42.1228, -80.0894))
        val bakery = MapPoi("Test Bakery", "bakery", GeoPoint(42.1228, -80.0891))
        val far = MapPoi("Test Fuel", "fuel", GeoPoint(42.1229, -80.0890))
        assertEquals(diner, SceneParts.nearestPoi(listOf(bakery to 18.4, diner to 0.6, far to 30.0)))
        assertEquals(bakery, SceneParts.nearestPoi(listOf(diner to 18.4, far to 30.0, bakery to 1.0)))
        assertEquals(diner, SceneParts.nearestPoi(listOf(diner to 5.0, bakery to 5.0)))   // the first of equals
        assertNull(SceneParts.nearestPoi(emptyList()))
    }

    @Test
    fun `the route ahead goes over the road labels only while the arrow shows`() {
        val p = io.github.spysyweeb.beamline.api.model.GeoPoint(42.1, -80.1)
        val day = io.github.spysyweeb.beamline.api.map.MapTheme.DAY
        val arrow = io.github.spysyweeb.beamline.api.map.VehicleMarker.Arrow(p, 57.0, brakesLit = false, stale = false, glideMs = 1000)
        val puck = io.github.spysyweeb.beamline.api.map.VehicleMarker.Puck(p, coneDeg = null, stale = false)
        assertTrue(SceneParts.routeOverLabels(io.github.spysyweeb.beamline.api.map.MapScene(day, marker = arrow)))
        assertFalse(SceneParts.routeOverLabels(io.github.spysyweeb.beamline.api.map.MapScene(day, marker = puck)))
        assertFalse(SceneParts.routeOverLabels(io.github.spysyweeb.beamline.api.map.MapScene(day)))
        // above the road labels and shields, under the first state's POI layer after the route's anchor
        val ids = listOf("roads@overview", "water_label_ocean@overview", "pois@overview", "roads@us-pa", "roads_oneway@us-pa",
            "roads_labels_minor@us-pa", "roads_shields@us-pa", "roads_labels_major@us-pa", "pois@us-pa", "pois_driving@us-pa", "places_locality@us-pa")
        assertEquals("pois@us-pa", SceneParts.guideRouteAnchor(ids, "roads_oneway@us-pa"))
        assertEquals("pois", SceneParts.guideRouteAnchor(listOf("roads", "roads_oneway", "roads_labels_major", "pois"), "roads_oneway"))
        assertNull(SceneParts.guideRouteAnchor(listOf("roads", "roads_oneway"), "roads_oneway"))
    }

    @Test
    fun `guide shields - the state copies between the route's anchor and the guide anchor, and where they go back`() {
        // owner, 2026-11-18: the driven road's shields above the route line while guiding, names under it
        val ids = listOf("roads@overview", "roads_shields@overview", "pois@overview", "roads@us-oh", "roads@us-pa",
            "roads_oneway@us-oh", "roads_oneway@us-pa", "roads_labels_minor@us-oh", "roads_labels_minor@us-pa",
            "roads_shields@us-oh", "roads_shields@us-pa", "roads_labels_major@us-oh", "roads_labels_major@us-pa",
            "pois@us-oh", "pois@us-pa")
        val anchor = SceneParts.guideRouteAnchor(ids, "roads_oneway@us-oh")
        assertEquals("pois@us-oh", anchor)
        assertEquals(listOf("roads_shields@us-oh", "roads_shields@us-pa") to "roads_labels_major@us-oh",
            SceneParts.guideShields(ids, "roads_oneway@us-oh", anchor))
        // shields right under the POI layer: they go back under the route's copy itself (null)
        assertEquals(listOf("roads_shields") to null,
            SceneParts.guideShields(listOf("roads", "roads_oneway", "roads_labels_major", "roads_shields", "pois"), "roads_oneway", "pois"))
        // no shield layer, or only the overview's: nothing moves
        assertEquals(emptyList<String>() to null, SceneParts.guideShields(listOf("roads", "roads_oneway", "pois"), "roads_oneway", "pois"))
        assertEquals(emptyList<String>(), SceneParts.guideShields(ids.take(3), null, null).first)
        assertEquals("roads_shields", Ids.GUIDE_SHIELDS)
    }

    @Test
    fun `the uncertainty bubble is a closed ring of its radius around the puck, grey when stale (G7)`() {
        val c = GeoPoint(42.1292, -80.0851)
        val ring = SceneParts.bubbleRing(c, 100.0)
        assertEquals(73, ring.size)
        assertEquals(ring.first(), ring.last())
        for (p in ring) {
            val dy = (p.lat - c.lat) * 111_194.9
            val dx = (p.lon - c.lon) * 111_194.9 * kotlin.math.cos(Math.toRadians(c.lat))
            assertEquals(100.0, kotlin.math.hypot(dx, dy), 0.5)
        }
        assertTrue(ring[0].lat > c.lat && ring[18].lon > c.lon)   // north first, then east
        assertEquals("#4A8CF7", SceneParts.bubbleColour("#4A8CF7", stale = false))
        assertEquals(SceneParts.STALE_GREY, SceneParts.bubbleColour("#4A8CF7", stale = true))
    }

    @Test
    fun `layer ids - ours all start with beamline-, the anchor is roads_oneway`() {
        assertTrue((Ids.BELOW_LABELS + Ids.ON_TOP).all { it.startsWith(Ids.PREFIX) })
        assertEquals("roads_oneway", Ids.ANCHOR_LAYER)
        assertEquals(listOf("pois", "pois_driving"), Ids.POI_LAYERS.toList())
        // the saved markers' two layers: one under the route lines, one above every label
        assertEquals(Ids.L_SAVED_UNDER, Ids.BELOW_LABELS.first())
        // the uncertainty bubble under every marker (G7), then the saved markers
        assertEquals(listOf(Ids.L_BUBBLE, Ids.L_BUBBLE_EDGE, Ids.L_SAVED_OVER), Ids.ON_TOP.take(3))
        // vehicle pieces in SPEC.md order, the arrow's lit lenses last
        assertEquals(listOf(Ids.L_PUCK_CONE, Ids.L_PUCK, Ids.L_BEAM, Ids.L_GLOW, Ids.L_ARROW, Ids.L_LIT), Ids.ON_TOP.drop(5))
        assertNull(Ids.BELOW_LABELS.firstOrNull { it in Ids.ON_TOP })
        // route lines: alternatives, driven, ahead; in each kind every casing below every fill
        assertEquals(Ids.ROUTE_LINES, Ids.BELOW_LABELS.drop(1))
        for (kind in listOf(listOf(Ids.L_TRAVELED_CASING, Ids.L_TRAVELED_NEAR_CASING, Ids.L_TRAVELED, Ids.L_TRAVELED_NEAR),
            listOf(Ids.L_ROUTE_CASING, Ids.L_ROUTE_NEAR_CASING, Ids.L_ROUTE, Ids.L_ROUTE_NEAR))) {
            assertEquals(kind, Ids.ROUTE_LINES.filter { it in kind })
        }
        // the overview copies: distinct, ours, and each maps back to its layer
        // the route ahead's copy over the labels while guiding: ours, in the order of the line it copies
        assertTrue(Ids.GUIDE_ROUTE_ON_TOP.all { it.startsWith(Ids.PREFIX) })
        assertEquals(Ids.ROUTE_AHEAD, Ids.ROUTE_LINES.filter { it in Ids.ROUTE_AHEAD })
        assertEquals(listOf(Ids.L_ROUTE_CASING, Ids.L_ROUTE_NEAR_CASING, Ids.L_ROUTE, Ids.L_ROUTE_NEAR), Ids.ROUTE_AHEAD)
        val all = Ids.BELOW_LABELS + Ids.OVERVIEW_BELOW_LABELS + Ids.GUIDE_ROUTE_ON_TOP + Ids.ON_TOP
        assertEquals(all.size, all.toSet().size)
        assertTrue(Ids.OVERVIEW_BELOW_LABELS.all { it.startsWith(Ids.PREFIX) })
        assertEquals(Ids.BELOW_LABELS, Ids.OVERVIEW_BELOW_LABELS.map(Ids::original))
        assertEquals(Ids.SOURCES.size, Ids.SOURCES.toSet().size)
    }

    /** Item 22 (docs/next-round.md): the lights' layer and source, and the style layers changed while guiding. */
    @Test
    fun `the traffic lights sit above the lines and saved markers, below the pins and the vehicle`() {
        assertTrue(Ids.SRC_LIGHTS in Ids.SOURCES)
        val order = Ids.ON_TOP
        assertTrue(order.indexOf(Ids.L_SAVED_OVER) < order.indexOf(Ids.L_LIGHTS))
        assertTrue(order.indexOf(Ids.L_LIGHTS) < order.indexOf(Ids.L_PINS))
        assertTrue(order.indexOf(Ids.L_LIGHTS) < order.indexOf(Ids.L_ARROW))
        assertTrue(Ids.L_LIGHTS.startsWith(Ids.PREFIX) && Ids.LIGHT.startsWith(Ids.PREFIX))
    }

    @Test
    fun `while guiding the general place names of every state are hidden, never the driving ones`() {
        val ids = listOf("roads_labels_minor", "roads_labels_minor@us-pa", "roads_labels_major@us-pa", "pois", "pois@us-pa", "pois@us-oh",
            "pois_driving", "pois_driving@us-pa", "places_locality@us-pa", "beamline-pins")
        assertEquals(listOf("pois", "pois@us-pa", "pois@us-oh"), SceneParts.copiesOf(ids, Ids.GUIDE_HIDDEN))
        assertEquals(listOf("roads_labels_minor", "roads_labels_minor@us-pa"), SceneParts.copiesOf(ids, Ids.GUIDE_ROAD_LABELS[0]))
        assertEquals(listOf("roads_labels_major@us-pa"), SceneParts.copiesOf(ids, Ids.GUIDE_ROAD_LABELS[1]))
        assertTrue(Ids.GUIDE_ROAD_LABEL_SCALE > 1f)
    }
}
