package io.github.spysyweeb.beamline.routing.demo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.github.spysyweeb.beamline.packs.AppStyle
import io.github.spysyweeb.beamline.packs.ComposeRules
import io.github.spysyweeb.beamline.packs.PackScanner
import io.github.spysyweeb.beamline.packs.StyleComposer
import io.github.spysyweeb.beamline.packs.Theme
import io.github.spysyweeb.beamline.routing.model.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * Demo screen: the trip (Lancaster to Gettysburg unless the intent gives from_lat, from_lon, to_lat,
 * to_lon) planned with all six presets on the device tiles, drawn on the Pennsylvania map pack.
 * Not the app: display only, everything comes from :routing:core / :routing:valhalla.
 */
class DemoActivity : ComponentActivity() {
    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var styleLoaded: Style? = null
    private val status = mutableStateOf("Loading the map and the routing tiles…")
    private val legend = mutableStateOf<List<PresetRoute>>(emptyList())
    private var routes: List<PresetRoute> = emptyList()
    private var tripTitle = "Lancaster → Gettysburg, six presets"
    private var packBounds: List<Double>? = null
    private var from = DemoRoutes.DEFAULT_FROM
    private var to = DemoRoutes.DEFAULT_TO
    /** Bottom edge of the legend card in pixels: the map camera keeps the routes below it. */
    private var legendBottomPx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("measure")?.let { first -> measure(first); return }
        MapLibre.getInstance(this)
        MapLibre.setConnected(false) // offline: packs are read through file:// URLs
        val options = MapLibreMapOptions.createFromAttributes(this).textureMode(false).logoEnabled(false).attributionEnabled(false)
        mapView = MapView(this, options).apply { onCreate(savedInstanceState) }
        mapView.getMapAsync { m -> map = m; loadStyle() }
        setContent { Screen() }

        from = LatLon(intent.getDoubleExtra("from_lat", DemoRoutes.DEFAULT_FROM.lat), intent.getDoubleExtra("from_lon", DemoRoutes.DEFAULT_FROM.lon))
        to = LatLon(intent.getDoubleExtra("to_lat", DemoRoutes.DEFAULT_TO.lat), intent.getDoubleExtra("to_lon", DemoRoutes.DEFAULT_TO.lon))
        if (intent.hasExtra("from_lat")) tripTitle = "%.4f, %.4f → %.4f, %.4f".format(from.lat, from.lon, to.lat, to.lon)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { DemoRoutes.planAll(this@DemoActivity, from, to) }
            }
            result.onSuccess { (openMs, r) ->
                routes = r
                legend.value = r
                status.value = "Engine opened in $openMs ms · valhalla-mobile 0.6.4 · ${DemoFiles.tiles(this@DemoActivity).length() / 1_000_000} MB tiles"
                drawRoutes()
            }.onFailure {
                Log.e("RoutingDemo", "routing failed", it)
                status.value = "Routing failed: ${it.message}. Push the tiles with android/routing/scripts/device.py push."
            }
        }
    }

    /** Measurement run (android/routing/scripts/device.py measure --release): no map, then finish. */
    private fun measure(first: String) {
        setContent { Text("Measuring ($first first)…") }
        lifecycleScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { DemoMeasure.run(this@DemoActivity, first, "release") } }
            Log.i("RoutingDemo", "MEASURE DONE ${r.isSuccess} ${r.exceptionOrNull()?.toString() ?: ""}")
            finish()
        }
    }

    private fun loadStyle() {
        lifecycleScope.launch {
            val composed = withContext(Dispatchers.IO) {
                runCatching {
                    // The app's style and compose rules (its assets folder, added in build.gradle.kts);
                    // the map packs pushed by device.py carry no style of their own (manifest 2).
                    fun asset(path: String) = assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
                    val rules = ComposeRules.parse(asset("rules/map-compose.json"))
                    val style = AppStyle(
                        dayTemplate = asset("style/day.json"),
                        nightTemplate = asset("style/night.json"),
                        glyphsUrl = "asset://style/glyphs/{fontstack}/{range}.pbf",
                        spriteDayUrl = "asset://style/sprites/light",
                        spriteNightUrl = "asset://style/sprites/dark",
                        rules = rules,
                    )
                    val packs = PackScanner(DemoFiles.mapPacks(this@DemoActivity), rules.readers()).scan().packs
                    StyleComposer.compose(style, Theme.DAY, null, packs)
                }
            }
            composed.onSuccess { c ->
                packBounds = c.bounds
                map?.setStyle(Style.Builder().fromJson(c.styleJson)) { s -> styleLoaded = s; drawRoutes() }
            }
                .onFailure { status.value = "No map pack: ${it.message}" }
        }
    }

    /** One line per preset, offset sideways so that routes on the same road stay visible. */
    private fun drawRoutes() {
        val style = styleLoaded ?: return
        val m = map ?: return
        if (routes.isEmpty()) return
        val drawn = routes.filter { it.route != null }
        drawn.forEachIndexed { i, pr ->
            val pts = pr.route!!.shape.map { Point.fromLngLat(it.lon, it.lat) }
            val id = "route-${pr.presetId}"
            if (style.getSource(id) == null) style.addSource(GeoJsonSource(id, LineString.fromLngLats(pts)))
            if (style.getLayer(id) == null) {
                style.addLayer(LineLayer(id, id).withProperties(
                    PropertyFactory.lineColor(pr.color),
                    PropertyFactory.lineWidth(3.5f),
                    PropertyFactory.lineOffset((i - (drawn.size - 1) / 2f) * 3.5f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ))
            }
        }
        // start (green) and destination (red) where they were asked
        if (style.getSource("stops") == null) {
            style.addSource(GeoJsonSource("stops", FeatureCollection.fromFeatures(listOf(
                Feature.fromGeometry(Point.fromLngLat(from.lon, from.lat)).apply { addStringProperty("color", "#1B8A3A") },
                Feature.fromGeometry(Point.fromLngLat(to.lon, to.lat)).apply { addStringProperty("color", "#C62828") },
            ))))
            style.addLayer(CircleLayer("stops", "stops").withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.5f),
            ))
        }
        fitCamera()
    }

    /** Routes and stops inside the part of the screen below the legend, with a margin on every side. */
    private fun fitCamera() {
        val m = map ?: return
        if (styleLoaded == null || routes.isEmpty()) return
        val dp = resources.displayMetrics.density
        val side = (48 * dp).toInt()
        val top = legendBottomPx + (24 * dp).toInt()
        val bottom = (40 * dp).toInt()
        val all = routes.mapNotNull { it.route }.flatMap { it.shape }
        val b = if (all.isNotEmpty()) {
            val pts = all + listOf(from, to)
            LatLngBounds.from(pts.maxOf { it.lat }, pts.maxOf { it.lon }, pts.minOf { it.lat }, pts.minOf { it.lon })
        } else { // every preset failed: the legend says why; show the installed map
            packBounds?.let { (w, so, e, n) -> LatLngBounds.from(n, e, so, w) } ?: return
        }
        mapView.post { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, side, top, side, bottom)) }
    }

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Column(
                Modifier.statusBarsPadding().padding(10.dp)
                    .onGloballyPositioned { c ->
                        val bottom = c.boundsInRoot().bottom.toInt()
                        if (bottom != legendBottomPx) { legendBottomPx = bottom; fitCamera() }
                    }
                    .background(ComposeColor(0xEEFFFFFF), RoundedCornerShape(10.dp)).padding(10.dp),
            ) {
                Text(tripTitle, fontSize = 15.sp)
                Text(status.value, fontSize = 11.sp, color = ComposeColor(0xFF555555))
                Spacer(Modifier.size(6.dp))
                for (pr in legend.value) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 18.dp, height = 5.dp).background(ComposeColor(pr.color)))
                        Spacer(Modifier.width(8.dp))
                        val r = pr.route
                        Text(
                            if (r == null) "${pr.label}: ${pr.problem}"
                            else "${pr.label}: ${(r.timeS / 60).toInt()} min · ${"%.1f".format(r.lengthM / 1609.344)} mi · ${r.turnCount} turns · ${pr.planMs} ms",
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            Text("© OpenStreetMap contributors", fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomEnd).background(ComposeColor(0xCCFFFFFF)).padding(4.dp))
        }
    }

    private val hasMap get() = this::mapView.isInitialized
    override fun onStart() { super.onStart(); if (hasMap) mapView.onStart() }
    override fun onResume() { super.onResume(); if (hasMap) mapView.onResume() }
    override fun onPause() { if (hasMap) mapView.onPause(); super.onPause() }
    override fun onStop() { if (hasMap) mapView.onStop(); super.onStop() }
    override fun onDestroy() { if (hasMap) mapView.onDestroy(); super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); if (hasMap) mapView.onLowMemory() }
}
