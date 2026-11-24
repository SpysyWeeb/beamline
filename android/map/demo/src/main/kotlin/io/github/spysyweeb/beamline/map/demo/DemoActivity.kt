package io.github.spysyweeb.beamline.map.demo

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.PuckLook
import io.github.spysyweeb.beamline.location.PuckState
import io.github.spysyweeb.beamline.map.layers.MapLibreController
import io.github.spysyweeb.beamline.map.scene.CameraDirector
import io.github.spysyweeb.beamline.map.scene.CameraRules
import io.github.spysyweeb.beamline.map.scene.MapSceneMapper
import io.github.spysyweeb.beamline.packs.AppStyle
import io.github.spysyweeb.beamline.packs.ComposeRules
import io.github.spysyweeb.beamline.packs.OverviewMap
import io.github.spysyweeb.beamline.packs.StyleComposer
import io.github.spysyweeb.beamline.packs.Theme
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.io.File

/**
 * Track M's device host (docs/architecture.md 5.1 M): the app's style with the PA z14 map file,
 * MapLibreController, MapSceneMapper and CameraDirector, driven by hand-made states on the exit 24
 * route. Not shipped. Driven from adb (tools/round/adb.sh m shell am start ...):
 *
 *   -n io.github.spysyweeb.beamline.mapdemo/io.github.spysyweeb.beamline.map.demo.DemoActivity
 *   --es scene  idle | idle_stale | follow | choosing | stops | drive | trace | moving | stopped | stale | arrived
 *               | long | long_drive        (a made-up Erie - Pittsburgh - Carlisle line of about 4,300 points)
 *   --es theme  day | night                (a change reloads the style)
 *   --ez overview true | false             (compose the overview map too, as the app does; a change reloads the style)
 *   --es saved  none | three | route | stack   (made-up saved places)
 *   --ez picked true                       (a PICKED pin)
 *   --ef at_m <metres along the route>     (stopped, stale, drive start; default 0)
 *   --ef at_s <second of the trace>        (trace start; default 0)
 *   --ef speed <factor>                    (drive and follow; default 1)
 *   --ef lat <deg> --ef lon <deg> --ef zoom <z> [--ef pitch <deg> --ef bearing <deg>]   (one camera move)
 *   --es action overview | recenter | mylocation | where | reload
 *   --ez location_log true                 (PV7 item 6: log the tapped POI's name and where the saved markers are)
 *
 * The map file: push data/map/packs-v2/pa-261114-z14.pmtiles to
 * /sdcard/Android/data/io.github.spysyweeb.beamline.mapdemo/files/pa-z14.pmtiles; for `overview`,
 * data/map/packs-v2/us-overview-ne512-tiger2026-z0-7.pmtiles to .../files/overview.pmtiles.
 * Logcat tag "MapDemo": gestures (with the pin id), camera modes, frames per second while moving.
 */
class DemoActivity : ComponentActivity() {
    private val scope = MainScope()
    private val clock = Clock { SystemClock.elapsedRealtime() }
    private val mapper = MapSceneMapper()
    private val director = CameraDirector(CameraRules.bundled(), clock)

    private lateinit var mapView: MapView
    private lateinit var label: TextView
    private lateinit var route: DemoRoute
    private lateinit var states: DemoStates
    private lateinit var trace: DemoTrace
    private var map: MapLibreMap? = null
    private var controller: MapLibreController? = null
    private var styleTheme: MapTheme? = null

    private var theme = MapTheme.DAY
    private var withOverview = false
    private var trip = TripState()
    private var puck: PuckState? = null
    private var picked: Place? = null
    private var saved: List<SavedPlace> = emptyList()
    private var sceneName = "idle"
    private var motionJob: Job? = null
    private var pendingIntent: Intent? = null

    /** PV7 item 6: lines naming a place or where a saved marker is are logged only with `--ez location_log true`. */
    private var locationLog = false

    private var frames = 0
    private var framesSince = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        MapLibre.setConnected(false)
        route = DemoRoute.load(assets)
        states = DemoStates(route)
        trace = DemoTrace.load(assets)
        val options = MapLibreMapOptions.createFromAttributes(this)
            .textureMode(false)
            .attributionEnabled(false)
            .logoEnabled(false)
            .compassEnabled(false)
        mapView = MapView(this, options)
        mapView.onCreate(savedInstanceState)
        label = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            textSize = 11f
            setPadding(12, 6, 12, 6)
        }
        val root = FrameLayout(this)
        root.addView(mapView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        setContentView(root)

        val density = resources.displayMetrics.density
        var insets = EdgeInsetsDp()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, wi ->
            val bars = wi.getInsets(WindowInsetsCompat.Type.systemBars())
            insets = EdgeInsetsDp(top = bars.top / density, bottom = bars.bottom / density, start = bars.left / density, end = bars.right / density)
            label.translationY = -bars.bottom.toFloat()
            director.onViewport(mapView.width / density, mapView.height / density, insets)
            wi
        }
        mapView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> director.onViewport(v.width / density, v.height / density, insets) }
        mapView.addOnDidFinishRenderingFrameListener { _, _, _ -> frames++ }

        pendingIntent = intent
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = true
            m.uiSettings.isTiltGesturesEnabled = true
            val c = MapLibreController(this, m)
            controller = c
            scope.launch { c.gestures.collect(::onGesture) }
            scope.launch { director.target.collect { t -> if (t != null) controller?.moveCamera(t) } }
            scope.launch { director.state.collect { s -> Log.i(TAG, "camera mode ${s.mode} recenter=${s.showRecenter}"); updateLabel() } } // log-ok: an enum name and a boolean
            m.addOnCameraIdleListener { Log.i(TAG, "camera idle z=%.2f pitch=%.0f bearing=%.0f".format(m.cameraPosition.zoom, m.cameraPosition.tilt, m.cameraPosition.bearing)) } // log-ok: zoom, pitch and bearing (numbers)
            loadStyle(theme)
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                director.tick()
                val now = SystemClock.elapsedRealtime()
                if (framesSince != 0L && motionJob?.isActive == true) {
                    Log.i(TAG, "fps frames=$frames in ${now - framesSince} ms scene=$sceneName show_max_ms=%.2f".format(showMaxNs / 1e6)) // log-ok: a frame count, milliseconds, the scene name typed in adb
                }
                showMaxNs = 0L
                frames = 0
                framesSince = now
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (styleTheme == null) pendingIntent = intent else apply(intent)
    }

    // ---- style ------------------------------------------------------------------------------

    private fun mapFile(): File = File(getExternalFilesDir(null) ?: filesDir, "pa-z14.pmtiles")

    private fun loadStyle(t: MapTheme) {
        val m = map ?: return
        val file = mapFile()
        val text = { p: String -> assets.open(p).use { it.readBytes().toString(Charsets.UTF_8) } }
        val app = AppStyle(
            dayTemplate = text("style/day.json"),
            nightTemplate = text("style/night.json"),
            glyphsUrl = "asset://style/glyphs/{fontstack}/{range}.pbf",
            spriteDayUrl = "asset://style/sprites/light",
            spriteNightUrl = "asset://style/sprites/dark",
            rules = ComposeRules.parse(text("rules/map-compose.json")),
        )
        val pack = PackInfo(
            packId = "us-pa", name = "Pennsylvania", state = "PA", osmSnapshot = "2026-11-14",
            bounds = Bounds(-80.52, 39.72, -74.69, 42.27), attribution = "© OpenStreetMap contributors",
            folder = file.parent ?: "", mapFile = file.absolutePath, mapMinZoom = 0, mapMaxZoom = 14,
            searchFile = null, addressesFile = null, addressesSkipped = true, searchProblem = null,
            searchRankingSha256 = null, inRoutingSet = false, verified = false, bytesOnDisk = file.length(),
        )
        if (!file.isFile) Log.w(TAG, "map file missing: push pa-z14.pmtiles to the demo's files folder (android/map/demo/README.md)")
        val overviewFile = File(getExternalFilesDir(null) ?: filesDir, "overview.pmtiles")
        val overview = if (withOverview && overviewFile.isFile) OverviewMap("pmtiles://" + StyleComposer.fileUrl(overviewFile), "Natural Earth; US Census Bureau") else null
        if (withOverview && overview == null) Log.w(TAG, "overview file missing: push overview.pmtiles to the demo's files folder (android/map/demo/README.md)")
        val composed = StyleComposer.compose(app, if (t == MapTheme.NIGHT) Theme.NIGHT else Theme.DAY, overview, listOf(pack))
        styleTheme = null
        m.setStyle(Style.Builder().fromJson(composed.styleJson)) { style ->
            styleTheme = t
            controller?.onStyleLoaded(style, t)
            Log.i(TAG, "style loaded ($t, overview=${overview != null}): ${style.layers.size} layers, ours ${style.layers.count { it.id.startsWith("beamline-") }}") // log-ok: the theme enum, a boolean and layer counts
            logRouteAnchors(style)
            // setStyle cancels a camera ease that was running: apply the director's move again.
            director.target.value?.let { controller?.moveCamera(it) }
            pendingIntent?.let { pendingIntent = null; apply(it) } ?: render()
        }
    }

    /**
     * Where our route line layers sit: for each run of them, the style layer right above it and the
     * run's zoom range (two runs in a style composed with the overview map and a state).
     */
    private fun logRouteAnchors(style: Style) {
        val layers = style.layers
        val runs = mutableListOf<String>()
        var run: org.maplibre.android.style.layers.Layer? = null
        for (l in layers) {
            val ours = l.id.startsWith("beamline-route")
            if (ours && run == null) run = l
            if (!ours && run != null && !l.id.startsWith("beamline-")) {
                runs += "under ${l.id} (z %.1f-%.1f)".format(run.minZoom, run.maxZoom)
                run = null
            }
        }
        if (run != null) runs += "on top (z %.1f-%.1f)".format(run.minZoom, run.maxZoom)
        Log.i(TAG, "route lines: ${runs.joinToString("; ")}") // log-ok: style layer ids and zoom numbers
    }

    // ---- scenes -----------------------------------------------------------------------------

    private fun apply(intent: Intent) {
        val wantTheme = when (intent.getStringExtra("theme")) {
            "night" -> MapTheme.NIGHT
            "day" -> MapTheme.DAY
            else -> theme
        }
        val overviewChanged = intent.hasExtra("overview") && intent.getBooleanExtra("overview", false) != withOverview
        if (overviewChanged) withOverview = !withOverview
        if (intent.hasExtra("location_log")) locationLog = intent.getBooleanExtra("location_log", false)
        intent.getStringExtra("saved")?.let { saved = states.saved(it) }
        if (intent.hasExtra("picked")) picked = if (intent.getBooleanExtra("picked", false)) states.picked else null
        val atM = intent.getFloatExtra("at_m", 0f).toDouble()
        val atS = intent.getFloatExtra("at_s", 0f).toDouble()
        val speed = intent.getFloatExtra("speed", 1f).toDouble().coerceIn(0.1, 20.0)
        intent.getStringExtra("scene")?.let { setScene(it, atM, atS, speed) }

        if (intent.getStringExtra("action") == "reload" || wantTheme != theme || overviewChanged) {
            theme = if (intent.getStringExtra("action") == "reload" && wantTheme == theme && !overviewChanged) {
                if (theme == MapTheme.DAY) MapTheme.NIGHT else MapTheme.DAY
            } else {
                wantTheme
            }
            render()
            loadStyle(theme)
        } else {
            render()
        }
        when (intent.getStringExtra("action")) {
            "overview" -> director.onOverview()
            "recenter" -> director.onRecenter()
            "mylocation" -> director.onMyLocation()
            "where" -> logWhere()
        }
        if (intent.hasExtra("lat") && intent.hasExtra("lon")) {
            val target = CameraTarget(
                center = GeoPoint(intent.getFloatExtra("lat", 0f).toDouble(), intent.getFloatExtra("lon", 0f).toDouble()),
                zoom = intent.getFloatExtra("zoom", 14f).toDouble(),
                bearingDeg = intent.getFloatExtra("bearing", 0f).toDouble(),
                pitchDeg = intent.getFloatExtra("pitch", 0f).toDouble(),
            )
            controller?.moveCamera(target)
        }
        updateLabel()
    }

    private fun setScene(name: String, atM: Double, atS: Double, speed: Double) {
        motionJob?.cancel()
        motionJob = null
        sceneName = name
        val now = clock.elapsedMs()
        puck = null
        when (name) {
            "idle" -> {
                trip = TripState()
                puck = PuckState(42.1292, -80.0851, PuckLook.LIVE, coneDeg = 35f, accuracyM = 5f)
            }
            "idle_stale" -> {
                trip = TripState()
                puck = PuckState(42.1292, -80.0851, PuckLook.GREY, coneDeg = 35f, accuracyM = 150f, why = "poor")
            }
            "follow" -> {
                trip = TripState()
                motionJob = scope.launch { followPuck(atM, speed) }
            }
            "choosing" -> trip = states.choosing()
            "stops" -> trip = states.choosing(withStop = true)
            "drive" -> {
                trip = states.choosing()
                motionJob = scope.launch { drive(atM, speed) }
            }
            "trace" -> {
                trip = states.choosing()
                motionJob = scope.launch { replayTrace(atS, speed) }
            }
            "stopped" -> trip = states.guiding(atM, now, motion = Motion.STOPPED)
            "moving" -> trip = states.guiding(atM, now, motion = Motion.MOVING)
            "stale" -> trip = states.guiding(atM, now, motion = Motion.MOVING, gps = GpsState.LOST)
            "arrived" -> trip = states.guiding(route.lengthM, now, motion = Motion.STOPPED, phase = Phase.ARRIVED)
            "long" -> trip = states.longChoosing()
            "long_drive" -> {
                trip = states.longChoosing()
                motionJob = scope.launch { longDrive(atM, speed) }
            }
            else -> Log.w(TAG, "unknown scene $name") // log-ok: the scene name typed in adb
        }
        Log.i(TAG, "scene $name at_m=$atM speed=$speed") // log-ok: the scene name typed in adb, metres along the route, a speed factor
    }

    /** The browsing puck moving along the route at 1 fix per second (BROWSE_FOLLOW at follow_zoom). */
    private suspend fun followPuck(startM: Double, speed: Double) {
        var m = startM
        director.onMyLocation()
        while (m <= route.lengthM) {
            val at = route.at(m)
            puck = PuckState(at.point.lat, at.point.lon, PuckLook.LIVE, coneDeg = at.bearingDeg.toFloat(), accuracyM = 5f)
            render()
            delay((1000 / speed).toLong())
            m += route.speedAt(m)
        }
    }

    /**
     * The exit 24 drive as section 8's `default` script: 1 fix per (simulated) second at the
     * route's own speed per stretch, a 20 s halt 15 m before maneuver 2 (the left turn), then on to
     * the end and ARRIVED.
     */
    private suspend fun drive(startM: Double, speed: Double) {
        render()
        delay(1500)
        val haltAt = route.maneuvers[2].alongM - 15.0
        var halted = startM > haltAt
        var m = startM
        var stoppedS = 0
        while (true) {
            val motion = if (stoppedS >= 1) Motion.STOPPED else Motion.MOVING
            trip = states.guiding(m, clock.elapsedMs(), motion = motion)
            render()
            delay((1000 / speed).toLong())
            if (m >= route.lengthM) break
            if (!halted && m >= haltAt) {
                halted = true
                stoppedS = 0
                repeat(20) {
                    stoppedS++
                    trip = states.guiding(m, clock.elapsedMs(), motion = if (stoppedS >= 2) Motion.STOPPED else Motion.MOVING)
                    render()
                    delay((1000 / speed).toLong())
                }
                stoppedS = 0
            }
            val next = m + route.speedAt(m)
            m = if (!halted && next > haltAt) haltAt else minOf(next, route.lengthM)
        }
        trip = states.guiding(route.lengthM, clock.elapsedMs(), motion = Motion.STOPPED, phase = Phase.ARRIVED)
        render()
    }

    /**
     * Track N's golden trace (DemoTrace): the session's vehicle and state after each fix of the
     * `default` simulation, one row per (simulated) second, from the row at [startS] seconds on.
     */
    private suspend fun replayTrace(startS: Double, speed: Double) {
        render()
        delay(1500)
        var segment = 0
        var prevT: Long? = null
        for (r in trace.rows) {
            if (r.tMs < startS * 1000) continue
            prevT?.let { delay(((r.tMs - it) / speed).toLong()) }
            prevT = r.tMs
            val (state, on) = states.traced(r, clock.elapsedMs(), segment)
            segment = on
            trip = state
            render()
        }
    }

    /**
     * Guiding along the made-up long line at 30 m/s, one position per (simulated) second: the
     * traveled cursor moves along a line of about 4,300 points. Logs once a second how long
     * show() took on the main thread (the longest of that second).
     */
    private suspend fun longDrive(startM: Double, speed: Double) {
        render()
        delay(1500)
        var m = startM
        while (m < states.long.lengthM) {
            trip = states.longGuiding(m, clock.elapsedMs())
            render()
            delay((1000 / speed).toLong())
            m += 30.0
        }
    }

    private var showMaxNs = 0L

    private fun render() {
        val c = controller ?: return
        director.onTrip(trip)
        director.onPuck(puck)
        if (styleTheme != null) {
            val scene = mapper.scene(trip, puck, theme, picked, saved)
            val t0 = System.nanoTime()
            c.show(scene)
            showMaxNs = maxOf(showMaxNs, System.nanoTime() - t0)
        }
    }

    private fun onGesture(g: MapGesture) {
        when (g) {
            is MapGesture.Tap -> if (locationLog) {
                Log.i(TAG, "gesture tap pinId=${g.pinId} poi=${g.poi?.name ?: "none"}") // private-ok: a map POI label's name and a made-up marker id, demo only, with location_log
            } else {
                Log.i(TAG, "gesture tap pinId=${g.pinId} poi=${if (g.poi != null) "a label" else "none"}") // log-ok: a pin id (stop index, picked, or a made-up marker id)
            }
            is MapGesture.LongPress -> Log.i(TAG, "gesture long-press")
            MapGesture.CameraMovedByUser -> Log.i(TAG, "gesture camera moved by user")
        }
        director.onGesture(g)
        updateLabel()
    }

    /** Where each saved marker and the vehicle are on the screen (pixels), to tap them from adb. */
    private fun logWhere() {
        val m = map ?: return
        m.style?.layers?.filter { it.id.startsWith("beamline-") }?.forEach { l ->
            val image = (l as? org.maplibre.android.style.layers.SymbolLayer)?.iconImage
            Log.i(TAG, "layer ${l.id} visibility=${l.visibility.value} icon-image=${image?.expression ?: image?.value}") // log-ok: our layer ids, their visibility and image names
        }
        if (locationLog) {
            for (p in saved) {
                val s = m.projection.toScreenLocation(LatLng(p.point.lat, p.point.lon))
                Log.i(TAG, "where saved ${p.id} ${p.kind} x=${s.x.toInt()} y=${s.y.toInt()}") // private-ok: made-up places' ids, kinds and screen pixels, with location_log
            }
        }
    }

    private fun updateLabel() {
        val s = director.state.value
        label.text = "scene $sceneName · $theme · camera ${s.mode}${if (s.showRecenter) " · Recenter" else ""} · saved ${saved.size}"
    }

    // ---- lifecycle --------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    override fun onDestroy() {
        scope.cancel()
        controller?.release()
        mapView.onDestroy()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "MapDemo"
    }
}
