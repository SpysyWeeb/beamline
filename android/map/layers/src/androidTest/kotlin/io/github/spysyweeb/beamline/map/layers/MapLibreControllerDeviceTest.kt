package io.github.spysyweeb.beamline.map.layers

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.api.map.AlternativeLine
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.map.MapPin
import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.map.PinKind
import io.github.spysyweeb.beamline.api.map.RouteLines
import io.github.spysyweeb.beamline.api.map.SavedMarker
import io.github.spysyweeb.beamline.api.map.VehicleMarker
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MapLibreController on a real MapLibre map (docs/architecture.md 5.1 M; docs/architecture-saved-places.md
 * SP42-SP44): the layer order around the style's "roads_oneway" in a style named as StyleComposer
 * names it, the saved layers' switch, everything back after a style reload with the night colours,
 * the tap rule with injected taps, long press and a user pan, camera moves, and the glide of the
 * arrow and of the puck. A small offline style (no tiles, no glyphs; stand-in images for the three
 * saved icons, test code only) around a public point in downtown Erie.
 *
 * Run with `am instrument` (docs/architecture.md 5.3), see android/map/layers/README.md.
 */
@RunWith(AndroidJUnit4::class)
class MapLibreControllerDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<TestMapActivity>
    private lateinit var activity: TestMapActivity
    private lateinit var map: MapLibreMap
    private lateinit var controller: MapLibreController
    private val gestures = CopyOnWriteArrayList<MapGesture>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun <T> onMain(block: () -> T): T {
        var out: Result<T>? = null
        inst.runOnMainSync { out = runCatching(block) }
        return out!!.getOrThrow()
    }

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(TestMapActivity::class.java)
        scenario.onActivity { activity = it }
        assertTrue("map ready", activity.ready.await(30, TimeUnit.SECONDS))
        map = activity.map!!
        controller = onMain { MapLibreController(activity, map) }
        scope.launch { controller.gestures.collect { gestures += it } }
        onMain {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(CENTRE.lat, CENTRE.lon)).zoom(15.0).build()))
        }
        loadStyle(MapTheme.DAY)
    }

    @After
    fun tearDown() {
        scope.cancel()
        onMain { controller.release() }
        scenario.close()
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun loadStyle(theme: MapTheme, overview: Boolean = false, towns: List<GeoPoint> = emptyList()) {
        val done = CountDownLatch(1)
        onMain {
            // P42: the town labels' image is in the style from the start, so a label does not wait for a late image
            val builder = Style.Builder().fromJson(styleJson(theme, overview, towns)).let { if (towns.isNotEmpty()) it.withImage(TOWN_ICON, townIcon()) else it }
            map.setStyle(builder) { style ->
                for (name in listOf("saved_home", "saved_work", "saved_place")) style.addImage(name, standIn(name))
                controller.onStyleLoaded(style, theme)
                done.countDown()
            }
        }
        assertTrue("style loaded", done.await(30, TimeUnit.SECONDS))
    }

    /** A plain square per saved icon name (the style sprite's images are not in this test APK). */
    private fun standIn(name: String): Bitmap {
        val colour = when (name) {
            "saved_home" -> Color.rgb(0x1F, 0x5B, 0xC4)
            "saved_work" -> Color.rgb(0x1F, 0x8B, 0x5B)
            else -> Color.rgb(0xC4, 0x5B, 0x1F)
        }
        return Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
    }

    /** P42: the town label's stand-in, a 16 dp square on any AVD (MapLibre sizes an image by its bitmap's density). */
    private fun townIcon(): Bitmap {
        val px = dp(16f).toInt()
        return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(0x5D, 0x40, 0x37))
            density = activity.resources.displayMetrics.densityDpi
        }
    }

    /** The style's layers, without MapLibre's own (its annotations layer "org.maplibre.annotations.points", unused by the app). */
    private fun layerIds(): List<String> = onMain { map.style!!.layers.map { it.id }.filterNot { it.startsWith("org.maplibre.") } }

    private fun show(scene: MapScene) = onMain { controller.show(scene) }

    /** The view point of [p] (pixels in the MapView). */
    private fun screen(p: GeoPoint): PointF = onMain { map.projection.toScreenLocation(LatLng(p.lat, p.lon)) }

    private fun dp(v: Float): Float = v * activity.resources.displayMetrics.density

    /** Features of [layers] rendered within 12 dp of [at] (view pixels), polled until some are drawn. */
    private fun renderedNear(at: PointF, vararg layers: String, timeoutMs: Long = 5_000): List<Feature> {
        val box = RectF(at.x - dp(12f), at.y - dp(12f), at.x + dp(12f), at.y + dp(12f))
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val found = onMain { map.queryRenderedFeatures(box, *layers) }
            if (found.isNotEmpty() || SystemClock.uptimeMillis() > end) return found
            Thread.sleep(100)
        }
    }

    /** True once no feature of [layer] is rendered within 12 dp of [at], polled for up to [timeoutMs]. */
    private fun awaitNoneNear(at: PointF, layer: String, timeoutMs: Long): Boolean {
        val box = RectF(at.x - dp(12f), at.y - dp(12f), at.x + dp(12f), at.y + dp(12f))
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (onMain { map.queryRenderedFeatures(box, layer) }.isEmpty()) return true
            if (SystemClock.uptimeMillis() > end) return false
            Thread.sleep(100)
        }
    }

    /** The rendered point of the only feature of [layer] on the whole map. */
    private fun renderedPoint(layer: String): GeoPoint {
        val all = onMain {
            map.queryRenderedFeatures(RectF(0f, 0f, activity.mapView.width.toFloat(), activity.mapView.height.toFloat()), layer)
        }
        val p = all.single().geometry() as Point
        return GeoPoint(p.latitude(), p.longitude())
    }

    /** Waits until the only feature of [layer] is drawn at longitude [lon]; true when it is, within [timeoutMs]. */
    private fun awaitLon(layer: String, lon: Double, timeoutMs: Long): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            if (Math.abs(renderedPoint(layer).lon - lon) < 1e-5) return true
            if (SystemClock.uptimeMillis() > end) return false
            Thread.sleep(20)
        }
    }

    /** Injects a finger at view point [at]: a tap, or a long press when [holdMs] is long. */
    private fun touch(at: PointF, holdMs: Long = 50) {
        val loc = IntArray(2).also { a -> onMain { activity.mapView.getLocationOnScreen(a) } }
        val x = at.x + loc[0]
        val y = at.y + loc[1]
        val down = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        Thread.sleep(holdMs)
        val up = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun swipe(from: PointF, dx: Float, dy: Float) {
        val loc = IntArray(2).also { a -> onMain { activity.mapView.getLocationOnScreen(a) } }
        val x0 = from.x + loc[0]
        val y0 = from.y + loc[1]
        val down = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0, y0, 0))
        for (i in 1..12) {
            Thread.sleep(16)
            inst.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x0 + dx * i / 12, y0 + dy * i / 12, 0))
        }
        inst.sendPointerSync(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x0 + dx, y0 + dy, 0))
    }

    /** Our route line layers in the style now: the driven part ([driven]) or the part ahead. */
    private fun routeLayers(driven: Boolean): Array<String> = layerIds()
        .filter { it.startsWith("beamline-route") && !it.contains("alternatives") && it.contains("traveled") == driven }
        .toTypedArray()

    /** The colour on the screen at view point [at] (a screenshot: whatever renderer the map uses). */
    private fun pixelAt(at: PointF): Int {
        val loc = IntArray(2).also { a -> onMain { activity.mapView.getLocationOnScreen(a) } }
        val shot = inst.uiAutomation.takeScreenshot()
        val soft = shot.copy(Bitmap.Config.ARGB_8888, false)
        return soft.getPixel((at.x + loc[0]).toInt(), (at.y + loc[1]).toInt())
    }

    private fun near(a: Int, b: Int, tolerance: Int = 24): Boolean =
        Math.abs(Color.red(a) - Color.red(b)) <= tolerance &&
            Math.abs(Color.green(a) - Color.green(b)) <= tolerance &&
            Math.abs(Color.blue(a) - Color.blue(b)) <= tolerance

    /** Waits until the screen shows [colour] at [at]; returns the last colour seen. */
    private fun awaitPixel(at: PointF, colour: Int, timeoutMs: Long = 4_000): Int {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            val seen = pixelAt(at)
            if (near(seen, colour) || SystemClock.uptimeMillis() > end) return seen
            Thread.sleep(200)
        }
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private fun moveTo(p: GeoPoint, zoom: Double) = onMain {
        map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(p.lat, p.lon)).zoom(zoom).bearing(0.0).tilt(0.0).build()))
    }

    /** The first gesture of type [T] reported after [since] gestures, waited for. */
    private inline fun <reified T : MapGesture> nextGesture(since: Int, timeoutMs: Long = 5_000): T? {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            gestures.drop(since).filterIsInstance<T>().firstOrNull()?.let { return it }
            Thread.sleep(50)
        }
        return null
    }

    // ---- tests --------------------------------------------------------------------------------

    @Test
    fun routeLayersGoUnderRoadsOnewayAndMarkersOnTop() {
        val expected = listOf("background", "roads@us-pa") + Ids.BELOW_LABELS +
            listOf("roads_oneway@us-pa", "roads_shields@us-pa", "roads_labels_major@us-pa") + Ids.GUIDE_ROUTE_ON_TOP +
            listOf("pois@us-pa", "pois_driving@us-pa") + Ids.ON_TOP
        assertEquals(expected, layerIds())
    }

    /**
     * Owner, 2026-11-18 (batch A): while guiding the route ahead is drawn over the road names and
     * under the shields; when guiding ends the shields go back under the names. Read from the screen
     * too: a name circle on the line shows the route's colour, a shield circle its own.
     */
    @Test
    fun whileGuidingTheShieldsAreAboveTheRouteAndTheNamesUnderIt() {
        val browsing = listOf("background", "roads@us-pa") + Ids.BELOW_LABELS +
            listOf("roads_oneway@us-pa", "roads_shields@us-pa", "roads_labels_major@us-pa") + Ids.GUIDE_ROUTE_ON_TOP +
            listOf("pois@us-pa", "pois_driving@us-pa") + Ids.ON_TOP
        val guiding = listOf("background", "roads@us-pa") + Ids.BELOW_LABELS +
            listOf("roads_oneway@us-pa", "roads_labels_major@us-pa") + Ids.GUIDE_ROUTE_ON_TOP +
            listOf("roads_shields@us-pa", "pois@us-pa", "pois_driving@us-pa") + Ids.ON_TOP
        moveTo(CENTRE, 15.0)
        val scene = guidingScene(MapTheme.DAY)
        show(scene)
        assertEquals(guiding, layerIds())
        val routeFill = Color.parseColor("#4A8CF7")
        val shieldSeen = awaitPixel(screen(SHIELD), SHIELD_COLOUR)
        assertTrue("the shield over the route while guiding: saw ${hex(shieldSeen)}", near(shieldSeen, SHIELD_COLOUR))
        val nameSeen = awaitPixel(screen(NAME), routeFill)
        assertTrue("the route over the road name while guiding: saw ${hex(nameSeen)}", near(nameSeen, routeFill))
        // the shield layer keeps its properties after the move
        assertEquals("visible", onMain { map.style!!.getLayer("roads_shields@us-pa")!!.visibility.value })
        // guiding ends (the puck again): the style's order, the name over the route
        show(scene.copy(marker = VehicleMarker.Puck(CURSOR, null, stale = false), savedBelowRoute = false))
        assertEquals(browsing, layerIds())
        val nameAfter = awaitPixel(screen(NAME), NAME_COLOUR)
        assertTrue("the road name over the route while browsing: saw ${hex(nameAfter)}", near(nameAfter, NAME_COLOUR))
        // and guiding again moves them again
        show(scene)
        assertEquals(guiding, layerIds())
    }

    @Test
    fun onlyTheSavedLayerTheSceneNamesIsVisible() {
        fun visibility(id: String) = onMain { map.style!!.getLayer(id)!!.visibility.value }
        show(MapScene(MapTheme.DAY, saved = savedMarkers(), savedBelowRoute = false))
        assertEquals("visible", visibility(Ids.L_SAVED_OVER))
        assertEquals("none", visibility(Ids.L_SAVED_UNDER))
        assertTrue("drawn over the route", renderedNear(screen(SAVED), Ids.L_SAVED_OVER).isNotEmpty())
        show(MapScene(MapTheme.DAY, saved = savedMarkers(), savedBelowRoute = true))
        assertEquals("none", visibility(Ids.L_SAVED_OVER))
        assertEquals("visible", visibility(Ids.L_SAVED_UNDER))
        assertTrue("drawn under the route", renderedNear(screen(SAVED), Ids.L_SAVED_UNDER).isNotEmpty())
    }

    @Test
    fun aStyleReloadBringsEverythingBackInTheNewTheme() {
        val scene = guidingScene(MapTheme.NIGHT)
        show(scene)
        loadStyle(MapTheme.NIGHT)
        // guiding: the shields above the route ahead's copy, the road names under it (batch A)
        val expected = listOf("background", "roads@us-pa") + Ids.BELOW_LABELS +
            listOf("roads_oneway@us-pa", "roads_labels_major@us-pa") + Ids.GUIDE_ROUTE_ON_TOP +
            listOf("roads_shields@us-pa", "pois@us-pa", "pois_driving@us-pa") + Ids.ON_TOP
        assertEquals(expected, layerIds())
        // night route colours of candidate B (route_colors.json)
        val colour = onMain { (map.style!!.getLayer(Ids.L_ROUTE) as LineLayer).lineColor.colorInt }
        assertEquals(Color.parseColor("#3A80F0"), colour)
        // the last scene is drawn again: pin, saved marker under the route, route, driven part, arrow and beam
        assertTrue("pin", renderedNear(PointF(screen(PIN).x, screen(PIN).y - dp(19f)), Ids.L_PINS).isNotEmpty())
        assertTrue("saved under", renderedNear(screen(SAVED), Ids.L_SAVED_UNDER).isNotEmpty())
        assertTrue("route ahead", renderedNear(screen(ROUTE_AHEAD), *routeLayers(driven = false)).isNotEmpty())
        assertTrue("driven part", renderedNear(screen(ROUTE_DRIVEN), *routeLayers(driven = true)).isNotEmpty())
        assertTrue("arrow", renderedNear(screen(CURSOR), Ids.L_ARROW).isNotEmpty())
        assertTrue("night beam", renderedNear(screen(CURSOR), Ids.L_BEAM).isNotEmpty())
        // and back to day: no beam
        show(scene.copy(theme = MapTheme.DAY))
        loadStyle(MapTheme.DAY)
        assertEquals(Color.parseColor("#4A8CF7"), onMain { (map.style!!.getLayer(Ids.L_ROUTE) as LineLayer).lineColor.colorInt })
        assertTrue("arrow by day", renderedNear(screen(CURSOR), Ids.L_ARROW).isNotEmpty())
        Thread.sleep(500)
        assertTrue("no beam by day", renderedNear(screen(CURSOR), Ids.L_BEAM, timeoutMs = 0).isEmpty())
    }

    /**
     * Review finding (contract review 3): a style loaded in another theme than the last scene's is
     * drawn in the style's theme (its route colours, the beam only at night) until the next show().
     */
    @Test
    fun aStyleLoadedInAnotherThemeIsDrawnInThatTheme() {
        fun routeColour(): Int = onMain { (map.style!!.getLayer(Ids.L_ROUTE) as LineLayer).lineColor.colorInt!! }
        show(guidingScene(MapTheme.DAY))
        loadStyle(MapTheme.NIGHT)   // no new scene yet
        assertEquals(hex(Color.parseColor("#3A80F0")), hex(routeColour()))
        assertTrue("night beam on the night style", renderedNear(screen(CURSOR), Ids.L_BEAM).isNotEmpty())
        // and the other way: a night scene, then a day style: day colours and no beam
        show(guidingScene(MapTheme.NIGHT))
        loadStyle(MapTheme.DAY)
        assertEquals(hex(Color.parseColor("#4A8CF7")), hex(routeColour()))
        assertTrue("arrow", renderedNear(screen(CURSOR), Ids.L_ARROW).isNotEmpty())
        Thread.sleep(500)
        assertTrue("no beam on the day style", renderedNear(screen(CURSOR), Ids.L_BEAM, timeoutMs = 0).isEmpty())
    }

    /**
     * Review finding (contract review 2): with two POI labels under the finger, the one nearest the
     * finger is reported, whatever order MapLibre returns them in. POI2 is 7 dp east of POI, both
     * inside the 24 dp box of a tap on either.
     */
    @Test
    fun aTapOnTwoPoiLabelsReportsTheNearest() {
        assertTrue(renderedNear(screen(POI), "pois@us-pa").isNotEmpty())
        var n = gestures.size
        touch(screen(POI))
        assertEquals("Test Diner", nextGesture<MapGesture.Tap>(n)?.poi?.name)
        n = gestures.size
        touch(screen(POI2))
        assertEquals("Test Bakery", nextGesture<MapGesture.Tap>(n)?.poi?.name)
    }

    /**
     * Review finding (contract review 5): in a style composed with the overview map (its layers
     * first, its labels up to the zoom where the states begin), the route line is under the
     * overview's labels below that zoom and under the states' labels above it. Read from the screen:
     * a label circle drawn on the line shows its own colour, not the route's.
     */
    @Test
    fun withTheOverviewTheRouteStaysUnderEveryLabelAtEveryZoom() {
        loadStyle(MapTheme.DAY, overview = true)
        // the route layers twice: under the overview's first label below z8, under roads_oneway@us-pa from z8
        assertEquals(
            listOf("background", "roads@overview") + Ids.OVERVIEW_BELOW_LABELS + listOf("places@overview", "roads@us-pa") +
                Ids.BELOW_LABELS + listOf("roads_oneway@us-pa", "roads_shields@us-pa", "roads_labels_major@us-pa") +
                Ids.GUIDE_ROUTE_ON_TOP + listOf("pois@us-pa", "pois_driving@us-pa") + Ids.ON_TOP,
            layerIds(),
        )
        val zooms = onMain { map.style!!.layers.filter { it.id.startsWith(Ids.PREFIX) }.associate { it.id to (it.minZoom to it.maxZoom) } }
        for (id in Ids.OVERVIEW_BELOW_LABELS) assertEquals(id, 8f, zooms.getValue(id).second)
        for (id in Ids.BELOW_LABELS) assertEquals(id, 8f, zooms.getValue(id).first)
        // the saved markers' "under" layer and its copy switch together
        show(MapScene(MapTheme.DAY, saved = savedMarkers(), savedBelowRoute = true))
        assertEquals("visible", onMain { map.style!!.getLayer(Ids.overview(Ids.L_SAVED_UNDER))!!.visibility.value })
        show(MapScene(MapTheme.DAY, saved = savedMarkers(), savedBelowRoute = false))
        assertEquals("none", onMain { map.style!!.getLayer(Ids.overview(Ids.L_SAVED_UNDER))!!.visibility.value })
        val line = listOf(GeoPoint(CENTRE.lat, CENTRE.lon - 0.6), CENTRE, GeoPoint(CENTRE.lat, CENTRE.lon + 0.6))
        show(MapScene(MapTheme.DAY, route = RouteLines(1, line, null, emptyList())))
        val routeFill = Color.parseColor("#4A8CF7")
        // z7: only the overview draws (the states begin at z8)
        moveTo(CENTRE, 7.0)
        assertTrue("route drawn at z7", near(awaitPixel(screen(GeoPoint(CENTRE.lat, CENTRE.lon + 0.3)), routeFill), routeFill))
        val atZ7 = awaitPixel(screen(CENTRE), PLACE_COLOUR)
        assertTrue("the overview's label over the route at z7: saw ${hex(atZ7)}", near(atZ7, PLACE_COLOUR))
        // z12: the states draw; their label over the route
        moveTo(CENTRE, 12.0)
        assertTrue("route drawn at z12", near(awaitPixel(screen(GeoPoint(CENTRE.lat, CENTRE.lon + 0.02)), routeFill), routeFill))
        val atZ12 = awaitPixel(screen(CENTRE), POI_COLOUR)
        assertTrue("the state's label over the route at z12: saw ${hex(atZ12)}", near(atZ12, POI_COLOUR))
    }

    /**
     * Review finding (contract review 1): moving the traveled cursor along a long route. Logs the
     * main-thread time of show() per step (a measurement, not asserted: shared emulator) and checks
     * the cut is drawn where the cursor is.
     */
    @Test
    fun aLongRouteFollowsItsCursor() {
        val n = 5_000
        val step = 0.0001211   // about 10 m of longitude at Erie's latitude: a 50 km line
        val line = (0 until n).map { GeoPoint(CURSOR.lat, CENTRE.lon - 0.006 + it * step) }
        fun at(k: Int) = GeoPoint(CURSOR.lat, CENTRE.lon - 0.006 + (k + 0.5) * step)
        fun scene(k: Int) = MapScene(
            MapTheme.DAY,
            route = RouteLines(1, line, RouteCursor(k, at(k)), emptyList()),
            marker = VehicleMarker.Arrow(at(k), 90.0, brakesLit = false, stale = false, glideMs = 1_000),
        )
        fun timedShow(s: MapScene): Double = onMain {
            val t0 = System.nanoTime()
            controller.show(s)
            (System.nanoTime() - t0) / 1e6
        }
        val first = timedShow(scene(0))
        val steps = 150
        val ms = (1..steps).map { k ->
            Thread.sleep(30)
            timedShow(scene(k))
        }.sorted()
        val median = ms[ms.size / 2]
        val p90 = ms[(ms.size * 9) / 10]
        Log.i("MapLayersTest", "long route %d points: first show %.2f ms; %d cursor steps: median %.3f ms, p90 %.3f ms, max %.3f ms".format(n, first, steps, median, p90, ms.last()))
        onMain { controller.moveCamera(CameraTarget(center = at(steps), zoom = 15.0)) }
        assertTrue("driven part behind the cursor", renderedNear(screen(GeoPoint(CURSOR.lat, at(steps).lon - 0.002)), *routeLayers(driven = true)).isNotEmpty())
        assertTrue("route ahead of the cursor", renderedNear(screen(GeoPoint(CURSOR.lat, at(steps).lon + 0.002)), *routeLayers(driven = false)).isNotEmpty())
        assertTrue("nothing ahead drawn behind", renderedNear(screen(GeoPoint(CURSOR.lat, at(steps).lon - 0.002)), *routeLayers(driven = false), timeoutMs = 0).isEmpty())
    }

    @Test
    fun tapsReportPinsThenSavedMarkersThenPoiLabels() {
        show(MapScene(MapTheme.DAY, pins = listOf(MapPin("stop:0", PIN, PinKind.STOP, number = 1)), saved = savedMarkers()))
        assertTrue(renderedNear(screen(SAVED), Ids.L_SAVED_OVER).isNotEmpty())
        assertTrue(renderedNear(screen(POI), "pois@us-pa").isNotEmpty())

        var n = gestures.size
        touch(PointF(screen(PIN).x, screen(PIN).y - dp(19f)))   // the pin's head (anchored at its tip)
        assertEquals("stop:0", nextGesture<MapGesture.Tap>(n)?.pinId)

        // Home and a star at one point, over a POI of pois_driving: Home (on top) wins, no POI
        n = gestures.size
        touch(screen(SAVED))
        val saved = nextGesture<MapGesture.Tap>(n)
        assertEquals("saved:$HOME_ID", saved?.pinId)
        assertNull(saved?.poi)

        n = gestures.size
        touch(screen(POI))
        val poi = nextGesture<MapGesture.Tap>(n)
        assertNull(poi?.pinId)
        val hit: MapPoi? = poi?.poi
        assertEquals("Test Diner", hit?.name)
        assertEquals("restaurant", hit?.kind)
        assertEquals(POI.lat, hit!!.point.lat, 1e-4)   // the feature's own point (tile precision), not the finger's
        assertEquals(POI.lon, hit.point.lon, 1e-4)

        n = gestures.size
        touch(screen(EMPTY))
        val empty = nextGesture<MapGesture.Tap>(n)
        assertTrue(empty != null && empty.pinId == null && empty.poi == null)
    }

    /**
     * 0.1.40, P23: a tap on another route choice where it runs apart from the chosen line reports
     * "route:<its id>"; where the two share the road it is the chosen route's (nothing); item 51: the
     * trip's own route drawn under a stop preview (tripRoute) is never reported. The alternative keeps
     * its grey with the per-line colour.
     */
    @Test
    fun aTapOnAnotherRouteChoiceReportsItsIdNeverTheTripsOwnRoute() {
        moveTo(CENTRE, 15.0)
        val west = GeoPoint(42.1310, -80.0885)
        val east = GeoPoint(42.1310, -80.0815)
        val chosen = listOf(west, east)
        val other = listOf(west, ALT_MID, east)
        show(MapScene(MapTheme.DAY, route = RouteLines(1, chosen, null, listOf(AlternativeLine(2, other)))))
        assertTrue(renderedNear(screen(ALT_MID), Ids.L_ALT).isNotEmpty())
        val grey = Color.parseColor("#A7AFB9")
        val seen = awaitPixel(screen(ALT_MID), grey)
        assertTrue("the other choice in its grey: saw ${hex(seen)}", near(seen, grey))

        var n = gestures.size
        touch(screen(ALT_MID))
        assertEquals("route:2", nextGesture<MapGesture.Tap>(n)?.pinId)

        // where both lines run (their shared start): the chosen route's, so nothing
        n = gestures.size
        touch(screen(GeoPoint(west.lat, west.lon + 0.0002)))
        val shared = nextGesture<MapGesture.Tap>(n)
        assertTrue("a tap on the shared road reports no route: ${shared?.pinId}", shared != null && shared.pinId == null)

        // item 51: a stop's preview route chosen, the trip's own route grey under it: never a choice
        val preview = listOf(west, GeoPoint(42.1290, -80.0850), east)
        show(MapScene(MapTheme.DAY, route = RouteLines(9, preview, null, listOf(AlternativeLine(1, other, tripRoute = true)))))
        assertTrue(renderedNear(screen(ALT_MID), Ids.L_ALT).isNotEmpty())
        val trip = awaitPixel(screen(ALT_MID), grey)
        assertTrue("the trip's own route in trip_route's grey: saw ${hex(trip)}", near(trip, grey))
        n = gestures.size
        touch(screen(ALT_MID))
        val own = nextGesture<MapGesture.Tap>(n)
        assertTrue("the trip's own route is no choice: ${own?.pinId}", own != null && own.pinId == null)
    }

    /** 0.1.40: the route step the route sheet shows (P21), a white disc; the chosen start's pin (P22), its own image. */
    @Test
    fun theStepMarkAndTheStartPinAreDrawn() {
        moveTo(CENTRE, 15.0)
        assertTrue("the start pin's image", onMain { map.style!!.getImage(Ids.PIN_START) != null })
        show(MapScene(MapTheme.DAY, pins = listOf(MapPin("start", PIN, PinKind.START)), step = EMPTY))
        val step = renderedNear(screen(EMPTY), Ids.L_STEP)
        assertTrue("the step mark is drawn", step.isNotEmpty())
        val white = awaitPixel(screen(EMPTY), Color.WHITE)
        assertTrue("the step mark is white in the middle: saw ${hex(white)}", near(white, Color.WHITE))
        val start = renderedNear(PointF(screen(PIN).x, screen(PIN).y - dp(19f)), Ids.L_PINS)
        assertEquals(Ids.PIN_START, start.single().getStringProperty("image"))
        // no step: none drawn
        show(MapScene(MapTheme.DAY, pins = listOf(MapPin("start", PIN, PinKind.START))))
        assertTrue("the step mark is gone", awaitNoneNear(screen(EMPTY), Ids.L_STEP, timeoutMs = 3_000))
    }

    @Test
    fun longPressAndUserPanAreReported() {
        var n = gestures.size
        touch(screen(EMPTY), holdMs = 1_200)
        val lp = nextGesture<MapGesture.LongPress>(n)
        assertTrue("long press", lp != null)
        assertEquals(EMPTY.lat, lp!!.point.lat, 0.0005)
        n = gestures.size
        swipe(screen(CENTRE), dp(-150f), dp(80f))
        assertTrue("pan", nextGesture<MapGesture.CameraMovedByUser>(n) != null)
        // an app move is not a user move
        n = gestures.size
        onMain { controller.moveCamera(CameraTarget(center = CENTRE, zoom = 15.0)) }
        Thread.sleep(500)
        assertTrue(gestures.drop(n).none { it is MapGesture.CameraMovedByUser })
    }

    @Test
    fun cameraMovesCentreWithBearingAndPitchOrFitABox() {
        onMain { controller.moveCamera(CameraTarget(center = PIN, zoom = 16.5, bearingDeg = 30.0, pitchDeg = 45.0)) }
        val cam = onMain { map.cameraPosition }
        assertEquals(PIN.lat, cam.target!!.latitude, 1e-6)
        assertEquals(PIN.lon, cam.target!!.longitude, 1e-6)
        assertEquals(16.5, cam.zoom, 1e-6)
        assertEquals(30.0, cam.bearing, 1e-6)
        assertEquals(45.0, cam.tilt, 1e-6)
        // zoom null keeps the zoom
        onMain { controller.moveCamera(CameraTarget(center = CENTRE, zoom = null)) }
        assertEquals(16.5, onMain { map.cameraPosition.zoom }, 1e-6)
        // a box, top-down and north up, padded: every corner in view
        val box = Bounds(EMPTY.lon - 0.01, POI.lat - 0.01, SAVED.lon + 0.01, SAVED.lat + 0.01)
        onMain { controller.moveCamera(CameraTarget(fit = box, padding = EdgeInsetsDp(48f, 48f, 48f, 48f))) }
        val seen = onMain { map.projection.visibleRegion.latLngBounds }
        val cam2 = onMain { map.cameraPosition }
        assertEquals(0.0, cam2.tilt, 1e-6)
        assertEquals(0.0, cam2.bearing, 1e-6)
        for (corner in listOf(LatLng(box.south, box.west), LatLng(box.north, box.east))) assertTrue(seen.contains(corner))
        // an eased move ends where it was asked to
        onMain { controller.moveCamera(CameraTarget(center = CENTRE, zoom = 15.0, animateMs = 600)) }
        Thread.sleep(1_200)
        assertEquals(CENTRE.lat, onMain { map.cameraPosition.target!!.latitude }, 1e-6)
    }

    @Test
    fun theArrowGlidesOverItsGlideMs() {
        val a = GeoPoint(CENTRE.lat, CENTRE.lon - 0.003)
        val b = GeoPoint(CENTRE.lat, CENTRE.lon + 0.003)
        show(MapScene(MapTheme.DAY, marker = VehicleMarker.Arrow(a, 90.0, brakesLit = false, stale = false, glideMs = 0)))
        assertTrue(renderedNear(screen(a), Ids.L_ARROW).isNotEmpty())
        show(MapScene(MapTheme.DAY, marker = VehicleMarker.Arrow(b, 90.0, brakesLit = false, stale = false, glideMs = 2_000)))
        Thread.sleep(1_000)
        val mid = renderedPoint(Ids.L_ARROW)
        assertTrue("in between after 1 s of 2: ${mid.lon}", mid.lon > a.lon + 0.001 && mid.lon < b.lon - 0.001)
        // lenses lit on the way (same point, another look): the glide goes on, it does not restart
        show(MapScene(MapTheme.DAY, marker = VehicleMarker.Arrow(b, 90.0, brakesLit = true, stale = false, glideMs = 2_000)))
        Thread.sleep(500)
        assertTrue("still on its way 1.5 s into 2 s", renderedPoint(Ids.L_ARROW).lon < b.lon - 1e-5)
        assertTrue("at b at the end", awaitLon(Ids.L_ARROW, b.lon, 2_000))
        assertTrue("lit lenses", renderedNear(screen(b), Ids.L_LIT).isNotEmpty())
    }

    @Test
    fun thePuckGlidesOverTheTimeBetweenItsPositions() {
        val a = GeoPoint(CENTRE.lat, CENTRE.lon - 0.003)
        val b = GeoPoint(CENTRE.lat, CENTRE.lon + 0.003)
        show(MapScene(MapTheme.DAY, marker = VehicleMarker.Puck(a, coneDeg = 90.0, stale = false)))
        assertTrue(renderedNear(screen(a), Ids.L_PUCK).isNotEmpty())
        Thread.sleep(1_000)
        show(MapScene(MapTheme.DAY, marker = VehicleMarker.Puck(b, coneDeg = 90.0, stale = false)))
        // a compass turn on the way keeps the glide
        Thread.sleep(250)
        show(MapScene(MapTheme.DAY, marker = VehicleMarker.Puck(b, coneDeg = 100.0, stale = false)))
        Thread.sleep(250)
        val mid = renderedPoint(Ids.L_PUCK)
        assertTrue("in between half-way: ${mid.lon}", mid.lon > a.lon + 0.0005 && mid.lon < b.lon - 0.0005)
        assertTrue("at b at the end", awaitLon(Ids.L_PUCK, b.lon, 2_000))
        // a puck after a long gap jumps: at a on the first rendered frames, never in between
        Thread.sleep(3_200)
        show(MapScene(MapTheme.DAY, marker = VehicleMarker.Puck(a, coneDeg = 90.0, stale = false)))
        val t0 = SystemClock.uptimeMillis()
        val seen = mutableListOf<Double>()
        while (SystemClock.uptimeMillis() - t0 < 3_000) {
            val lon = renderedPoint(Ids.L_PUCK).lon
            if (seen.lastOrNull() != lon) seen += lon
            if (Math.abs(lon - a.lon) < 1e-5) break
            Thread.sleep(20)
        }
        val took = SystemClock.uptimeMillis() - t0
        Log.i("MapLayersTest", "puck jump drawn after $took ms; rendered lons $seen")
        assertTrue("jumped to a in $took ms; seen $seen", Math.abs(seen.last() - a.lon) < 1e-5 && took < 1_000)
        assertTrue("never in between: $seen", seen.none { it > a.lon + 1e-5 && it < b.lon - 1e-5 })
    }

    /**
     * P42: a place label just above the browsing dot (24 dp north, where the tester saw 'Erie') gives
     * way to the dot: the dot is always drawn, the label is hidden while the dot is on it and comes
     * back once the puck is gone. A label 60 dp north, under the cone's beam and inside its 176 dp
     * canvas but clear of the dot's box, stays: only the dot clears labels. The labels are placed by
     * screen offset from the dot (MapLibre's world is 512 dp at z0).
     */
    @Test
    fun aPlaceLabelAtThePuckGivesWayToTheDot() {
        val c = screen(CENTRE)
        fun north(dpUp: Float): GeoPoint =
            onMain { map.projection.fromScreenLocation(PointF(c.x, c.y - dp(dpUp))) }.let { GeoPoint(it.latitude, it.longitude) }
        val near = north(24f)
        val far = north(60f)
        loadStyle(MapTheme.DAY, towns = listOf(near, far))
        assertEquals("the near label 24 dp north of the dot", dp(24f), c.y - screen(near).y, dp(1f))
        assertEquals("the far label 60 dp north of the dot", dp(60f), c.y - screen(far).y, dp(1f))
        assertTrue("the near label alone", renderedNear(screen(near), TOWN_LAYER).isNotEmpty())
        assertTrue("the far label alone", renderedNear(screen(far), TOWN_LAYER).isNotEmpty())
        show(MapScene(MapTheme.DAY, marker = VehicleMarker.Puck(CENTRE, coneDeg = 0.0, stale = false)))
        assertTrue("the dot is drawn", renderedNear(c, Ids.L_PUCK).isNotEmpty())
        assertTrue("the far label is under the cone", renderedNear(screen(far), Ids.L_PUCK_CONE).isNotEmpty())
        assertTrue("the near label gives way to the dot", awaitNoneNear(screen(near), TOWN_LAYER, timeoutMs = 3_000))
        assertTrue("the cone does not clear labels", renderedNear(screen(far), TOWN_LAYER, timeoutMs = 0).isNotEmpty())
        show(MapScene(MapTheme.DAY))
        assertTrue("the label is back without the puck", renderedNear(screen(near), TOWN_LAYER).isNotEmpty())
    }

    // ---- the scene ---------------------------------------------------------------------------

    private fun savedMarkers() = listOf(
        SavedMarker(STAR_ID, SAVED, SavedKind.OTHER, 12.0, 1.25),
        SavedMarker(HOME_ID, SAVED, SavedKind.HOME, 10.0, 1.25),
    )

    /** Guiding east along a straight line through the centre: driven part west of the cursor, the arrow on it. */
    private fun guidingScene(theme: MapTheme): MapScene {
        val line = listOf(GeoPoint(CENTRE.lat - 0.002, CENTRE.lon - 0.006), CURSOR, GeoPoint(CENTRE.lat - 0.002, CENTRE.lon + 0.006))
        return MapScene(
            theme = theme,
            route = RouteLines(1, line, RouteCursor(0, CURSOR), emptyList()),
            pins = listOf(MapPin("stop:0", PIN, PinKind.DESTINATION)),
            marker = VehicleMarker.Arrow(CURSOR, 90.0, brakesLit = false, stale = false, glideMs = 0),
            saved = savedMarkers(),
            savedBelowRoute = true,
        )
    }

    companion object {
        /** Downtown Erie (a public point); at z15 0.0043 degrees of longitude and 0.0064 of latitude are about 200 and 400 dp. */
        val CENTRE = GeoPoint(42.1292, -80.0851)
        val PIN = GeoPoint(42.1356, -80.0894)
        val SAVED = GeoPoint(42.1356, -80.0808)
        val POI = GeoPoint(42.1228, -80.0894)

        /** A second POI label 0.0003 degrees (about 14 dp at z15) east of [POI]: both under a finger on either. */
        val POI2 = GeoPoint(42.1228, -80.0891)
        val EMPTY = GeoPoint(42.1228, -80.0808)
        /** P23: the tip of another route choice's V north of the chosen line (about 160 dp from it at z15). */
        val ALT_MID = GeoPoint(42.1335, -80.0850)
        val CURSOR = GeoPoint(42.1272, -80.0851)
        val ROUTE_DRIVEN = GeoPoint(42.1272, -80.0881)
        val ROUTE_AHEAD = GeoPoint(42.1272, -80.0821)

        /** A route shield and a road name label (circles here) on the route line ahead of [CURSOR]. */
        val SHIELD = GeoPoint(42.1272, -80.0836)
        val NAME = GeoPoint(42.1272, -80.0828)
        val SHIELD_COLOUR = Color.parseColor("#6A1B9A")
        val NAME_COLOUR = Color.parseColor("#00897B")
        const val HOME_ID = "s0000000000000b01"
        const val STAR_ID = "s0000000000000b03"

        /** P42: the stand-in place labels (an icon: the test style has no glyphs). */
        const val TOWN_LAYER = "places_locality@us-pa"
        const val TOWN_ICON = "test-town"

        /** The overview's place label (a circle here) and the states' POI label, both drawn on the route line at [CENTRE]. */
        val PLACE_COLOUR = Color.parseColor("#C2185B")
        val POI_COLOUR = Color.parseColor("#C26A00")

        /**
         * StyleComposer's names (one state, "@us-pa"); a GeoJSON source instead of tiles. With
         * [overview]: the overview map's copies first, its label up to z8, the states' copies from
         * z8 (as StyleComposer composes the overview with a state, map-compose.json), and a label of
         * each on the point [CENTRE].
         */
        fun styleJson(theme: MapTheme, overview: Boolean = false, towns: List<GeoPoint> = emptyList()): String {
            val bg = if (theme == MapTheme.NIGHT) "#202124" else "#EDEAE4"
            val road = if (theme == MapTheme.NIGHT) "#3C4043" else "#FFFFFF"
            fun pt(p: GeoPoint, props: String) =
                """{"type":"Feature","properties":$props,"geometry":{"type":"Point","coordinates":[${p.lon},${p.lat}]}}"""
            val features = listOfNotNull(
                """{"type":"Feature","properties":{"k":"road"},"geometry":{"type":"LineString","coordinates":[[${CENTRE.lon - 0.01},${CURSOR.lat}],[${CENTRE.lon + 0.01},${CURSOR.lat}]]}}""",
                pt(POI, """{"k":"poi","name":"Test Diner","kind":"restaurant"}"""),
                pt(POI2, """{"k":"poi","name":"Test Bakery","kind":"bakery"}"""),
                pt(SAVED, """{"k":"fuel","name":"Test Fuel","kind":"fuel"}"""),
                pt(SHIELD, """{"k":"shield"}"""),
                pt(NAME, """{"k":"name"}"""),
                if (overview) pt(CENTRE, """{"k":"place","name":"Test Town"}""") else null,
                if (overview) pt(CENTRE, """{"k":"poi","name":"Test Grill","kind":"restaurant"}""") else null,
            ).plus(towns.map { pt(it, """{"k":"town"}""") }).joinToString(",")
            // P42: a place-label layer of icons (no glyphs here), as the style's places_locality; none: the style as before
            val townLayer = if (towns.isEmpty()) "" else
                """,{"id": "$TOWN_LAYER", "type": "symbol", "source": "base", "filter": ["==", ["get", "k"], "town"], "layout": {"icon-image": "$TOWN_ICON"}}"""
            val minZ = if (overview) """"minzoom": 8, """ else ""
            val overviewLayers = if (!overview) "" else """
                  {"id": "roads@overview", "type": "line", "source": "base", "maxzoom": 10, "filter": ["==", ["get", "k"], "road"], "paint": {"line-color": "$road", "line-width": 6}},
                  {"id": "places@overview", "type": "circle", "source": "base", "maxzoom": 8, "filter": ["==", ["get", "k"], "place"], "paint": {"circle-radius": 16, "circle-color": "#C2185B"}},
            """.trimEnd()
            return """
                {"version": 8, "name": "beamline-test",
                 "sources": {"base": {"type": "geojson", "data": {"type": "FeatureCollection", "features": [$features]}}},
                 "layers": [
                  {"id": "background", "type": "background", "paint": {"background-color": "$bg"}},$overviewLayers
                  {"id": "roads@us-pa", "type": "line", "source": "base", $minZ"filter": ["==", ["get", "k"], "road"], "paint": {"line-color": "$road", "line-width": 10}},
                  {"id": "roads_oneway@us-pa", "type": "line", "source": "base", $minZ"filter": ["==", ["get", "k"], "road"], "paint": {"line-color": "#9AA0A6", "line-width": 1}},
                  {"id": "roads_shields@us-pa", "type": "circle", "source": "base", $minZ"filter": ["==", ["get", "k"], "shield"], "paint": {"circle-radius": 8, "circle-color": "#6A1B9A"}},
                  {"id": "roads_labels_major@us-pa", "type": "circle", "source": "base", $minZ"filter": ["==", ["get", "k"], "name"], "paint": {"circle-radius": 8, "circle-color": "#00897B"}},
                  {"id": "pois@us-pa", "type": "circle", "source": "base", $minZ"filter": ["==", ["get", "k"], "poi"], "paint": {"circle-radius": 10, "circle-color": "#C26A00"}},
                  {"id": "pois_driving@us-pa", "type": "circle", "source": "base", $minZ"filter": ["==", ["get", "k"], "fuel"], "paint": {"circle-radius": 10, "circle-color": "#1E8E3E"}}$townLayer
                 ]}
            """.trimIndent()
        }
    }
}
