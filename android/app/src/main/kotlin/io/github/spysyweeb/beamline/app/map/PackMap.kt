package io.github.spysyweeb.beamline.app.map

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.app.AppGraph
import io.github.spysyweeb.beamline.app.MapStyleState
import io.github.spysyweeb.beamline.app.ui.AppFlows
import io.github.spysyweeb.beamline.map.layers.MapLibreController
import io.github.spysyweeb.beamline.map.scene.withZoomCap
import io.github.spysyweeb.beamline.packs.ComposedMap
import io.github.spysyweeb.beamline.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

private const val TAG = "BeamlineMap"

/** How the map should draw frames (from the frame-rate setting, see FrameRatePlan). */
data class FrameRateRequest(val maxFps: Int, val surfaceFrameRate: Float)

/**
 * The full-screen MapLibre map (docs/architecture.md 5.4 item 4): the style composed from the
 * installed set (P's StyleComposer overload, in AppGraph), M's MapLibreController attached on every
 * style load, CameraDirector's targets applied through the zoom cap, MapSceneMapper's scenes shown.
 * Display only: it decides nothing about places or trips.
 */
@Composable
fun PackMap(g: AppGraph, flows: AppFlows, frameRate: FrameRateRequest, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val host = remember { MapHost(context, g, flows) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> host.view.onStart()
                Lifecycle.Event.ON_RESUME -> host.view.onResume()
                Lifecycle.Event.ON_PAUSE -> host.view.onPause()
                Lifecycle.Event.ON_STOP -> {
                    host.remember()
                    host.view.onStop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val s = lifecycleOwner.lifecycle.currentState
            if (s.isAtLeast(Lifecycle.State.RESUMED)) host.view.onPause()
            if (s.isAtLeast(Lifecycle.State.STARTED)) host.view.onStop()
            host.destroy()
        }
    }

    val style by g.mapStyle.collectAsState()
    LaunchedEffect(style) { (style as? MapStyleState.Ready)?.let(host::setStyle) }
    LaunchedEffect(frameRate) { host.applyFrameRate(frameRate) }
    LaunchedEffect(flows.cameraRequest) {
        flows.cameraRequest?.let {
            host.moveFromTest(it)
            flows.cameraRequest = null
        }
    }
    LaunchedEffect(flows.cameraInsets) { host.viewport() }

    AndroidView(factory = { host.view }, modifier = modifier)
}

/** Keeps the MapView with its map and controller; logs start-up and frame timings for the measurements. */
private class MapHost(context: Context, private val g: AppGraph, private val flows: AppFlows) {
    private val density = context.resources.displayMetrics.density
    val view: MapView = MapView(
        context,
        MapLibreMapOptions.createFromAttributes(context)
            .textureMode(false)
            .attributionEnabled(false) // the credit is drawn by the screen, always visible
            .logoEnabled(false)
            .compassEnabled(false)
            // Rule K1: browsing is top-down and north up; the camera director tilts and turns the map while guiding.
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false),
    ).apply { onCreate(null) }

    private val scope = MainScope()
    private var map: MapLibreMap? = null
    private var controller: MapLibreController? = null
    private var pendingStyle: MapStyleState.Ready? = null
    private var composed: ComposedMap? = null
    private var styleLoaded = false
    private var cameraPlaced = false
    private var lastScene: MapScene? = null
    /** Batch O3 (item 26): the own-changes layer of the loaded style. */
    private var own: OwnLayerHost? = null

    /** A camera move asked for before the map and its style were ready (a "show on map" fit): applied after the style loads. */
    private var pendingRequest: CameraTarget? = null

    // Where an earlier map of this process left the camera, read before this map's first camera events.
    private val againPackIds = flows.placedPackIds
    private val againCenter = flows.cameraCenter
    private val againZoom = flows.cameraZoom

    // A cold start: where the map was left last time (K1, item 5), when there is no position to follow.
    private val remembered = if (againCenter == null) g.lastCamera.read() else null
    private var firstFrameLogged = false
    private var firstFullLogged = false
    private val frames = FrameWindow()
    private var surfaceRate = 0f
    private var surfaceCallbackAdded = false

    init {
        view.getMapAsync { m ->
            map = m
            val c = MapLibreController(view.context, m)
            controller = c
            scope.launch { c.gestures.collect(::onGesture) }
            m.addOnCameraIdleListener {
                val cp = m.cameraPosition
                // Before the camera is placed the map sits at its default view: not a place the user chose.
                if (cameraPlaced) {
                    cp.target?.let { flows.onCameraRest(GeoPoint(it.latitude, it.longitude), cp.zoom) } ?: run { flows.cameraZoom = cp.zoom }
                }
                // One line per camera stop, for the emulator checks: zoom, pitch and bearing, never where (PV2).
                Log.i(TAG, "camera idle z=%.2f pitch=%.0f bearing=%.0f".format(cp.zoom, cp.tilt, cp.bearing)) // log-ok: zoom, pitch and bearing (numbers)
            }
            m.addOnCameraMoveListener { if (cameraPlaced) flows.cameraZoom = m.cameraPosition.zoom }
            // Builds for testing: the camera MapLibre applies while guiding, once a second (in the
            // middle of the follow eases, where "camera idle" never comes): numbers only (PV2).
            if (BuildConfig.TEST_EXTRAS) {
                scope.launch {
                    while (true) {
                        delay(1000)
                        if (!g.driving(g.session.state.value.phase)) continue
                        val cp = m.cameraPosition
                        val pad = cp.padding?.joinToString(",") { "%.0f".format(it / density) } ?: "-"
                        Log.i(TAG, "camera now z=%.2f pitch=%.1f bearing=%.0f pad=%s view=%dx%d".format(cp.zoom, cp.tilt, cp.bearing, pad, (view.width / density).toInt(), (view.height / density).toInt())) // log-ok: zoom, pitch, bearing, padding and view size in dp (numbers)
                    }
                }
            }
            pendingStyle?.let {
                pendingStyle = null
                setStyle(it)
            }
            // K1: browsing stays top-down and north up (no rotate or tilt gestures); while guiding
            // the director tilts and turns the map and a user's gesture goes to GUIDE_FREE (K4).
            scope.launch {
                g.session.state.collect { s ->
                    val guiding = g.driving(s.phase)
                    m.uiSettings.isRotateGesturesEnabled = guiding
                    m.uiSettings.isTiltGesturesEnabled = guiding
                }
            }
            // The scene and the camera director's moves (M's wiring, 5.4 item 4).
            scope.launch { g.scene.collect(::show) }
            // Batch O3 (item 26): the user's own changes as if the map had them
            scope.launch { g.ownChanges.collect { list -> if (styleLoaded) m.style?.let { st -> own?.apply(st, list) } } }
            scope.launch { g.director.target.collect { t -> if (t != null) move(t) } }
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> viewport() }
        // MapLibre reports both times in seconds (RenderingStats: "Frame CPU ... time (seconds)").
        view.addOnDidFinishRenderingFrameListener { fully, encodingSeconds, renderingSeconds ->
            if (!firstFrameLogged) {
                firstFrameLogged = true
                Log.i(TAG, "first map frame ${sinceProcessStart()} ms after process start") // log-ok: milliseconds
            }
            if (fully && styleLoaded && !firstFullLogged) {
                firstFullLogged = true
                Log.i(TAG, "first fully rendered map frame ${sinceProcessStart()} ms after process start") // log-ok: milliseconds
                flows.mapReady = true
            }
            frames.add((encodingSeconds + renderingSeconds) * 1000.0)
        }
        view.addOnDidFailLoadingMapListener { Log.e(TAG, "map failed to load") }
    }

    /** The map's size and the drive overlay's insets for the camera director. */
    fun viewport() {
        if (view.width == 0 || view.height == 0) return
        g.director.onViewport(view.width / density, view.height / density, flows.cameraInsets)
    }

    fun setStyle(s: MapStyleState.Ready) {
        val m = map ?: run {
            pendingStyle = s
            return
        }
        val old = composed
        if (old != null && old.styleJson == s.map.styleJson) return
        composed = s.map
        // First the zoom limit: with only the overview the map is blank above its last zoom (K1).
        m.setMaxZoomPreference(s.map.maxZoom ?: MapLibreConstants.MAXIMUM_ZOOM.toDouble())
        // MapLibre keeps a camera that is already closer than a new limit: the last state removed
        // while the camera stays (a route preview, a map the user moved) left it blank. Go out to it.
        s.map.maxZoom?.let { limit ->
            if (m.cameraPosition.zoom > limit) m.moveCamera(CameraUpdateFactory.zoomTo(limit))
        }
        val firstState = old != null && old.packIds.isEmpty() && s.map.packIds.isNotEmpty()
        val lastStateGone = old != null && old.packIds.isNotEmpty() && s.map.packIds.isEmpty()
        styleLoaded = false
        val injected = OwnLayer.inject(s.map.styleJson)
        m.setStyle(Style.Builder().fromJson(injected.json)) { style ->
            val c = controller ?: return@setStyle
            c.onStyleLoaded(style, s.theme)
            styleLoaded = true
            own = OwnLayerHost(injected).also { it.apply(style, g.ownChanges.value) }
            Log.i(TAG, "style loaded (${s.theme}, generation ${s.generation}): ${style.layers.size} layers") // log-ok: an enum name and counts
            lastScene?.let(c::show)
            // setStyle cancels a camera ease that was running: apply the director's move again.
            val target = g.director.target.value
            // A map made again (the activity recreated by a font size or language change) over the
            // same states: the camera goes back where the user left it, not to the state's bounds.
            val again = if (!cameraPlaced && againPackIds == s.map.packIds) againCenter else null
            if (target != null) {
                move(target)
            } else if (again != null && againZoom > 0.0) {
                c.moveCamera(CameraTarget(center = again, zoom = againZoom))
            } else if (!cameraPlaced && remembered != null) {
                // A cold start without a position: where the map was left (capped outside the states).
                move(CameraTarget(center = remembered.center, zoom = remembered.zoom))
            } else if (!cameraPlaced || firstState || lastStateGone) {
                fitTo(s.map.bounds)
            }
            cameraPlaced = true
            flows.placedPackIds = s.map.packIds
            pendingRequest?.let {
                pendingRequest = null
                moveFromTest(it)
            }
        }
    }

    private fun fitTo(wsen: List<Double>) {
        if (wsen.size != 4) return
        controller?.moveCamera(CameraTarget(fit = Bounds.of(wsen), padding = EdgeInsetsDp(32f, 32f, 32f, 32f)))
    }

    fun show(scene: MapScene) {
        lastScene = scene
        if (styleLoaded) controller?.show(scene)
    }

    /**
     * Every target through rule K1's cap outside the states (M's ZoomCap). A box is capped too: its
     * fit zoom (MapLibre's camera for it) at its centre; a box outside every state (a route preview
     * after its state was removed) would otherwise fit closer than anything installed can draw.
     */
    fun move(target: CameraTarget) {
        val c = controller ?: return
        val drawn = composed
        val capped = when {
            drawn == null -> target
            target.fit != null -> fitCapped(target, drawn)
            else -> target.withZoomCap(drawn::zoomFor)
        }
        c.moveCamera(capped)
    }

    private fun fitCapped(target: CameraTarget, drawn: ComposedMap): CameraTarget {
        val m = map ?: return target
        val box = target.fit ?: return target
        val p = target.padding
        val padding = intArrayOf((p.start * density).toInt(), (p.top * density).toInt(), (p.end * density).toInt(), (p.bottom * density).toInt())
        val camera = try {
            m.getCameraForLatLngBounds(LatLngBounds.from(box.north, box.east, box.south, box.west), padding, target.bearingDeg, target.pitchDeg)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return target
        val centre = GeoPoint((box.south + box.north) / 2, (box.west + box.east) / 2)
        val zoom = drawn.zoomFor(centre.lat, centre.lon, camera.zoom)
        return if (zoom < camera.zoom) target.copy(fit = null, center = centre, zoom = zoom) else target
    }

    /** ON_STOP: the camera the user left, for the next cold start (item 5); written off the main thread. */
    fun remember() {
        if (!cameraPlaced) return
        val centre = flows.cameraCenter ?: return
        val zoom = flows.cameraZoom.takeIf { it > 0.0 } ?: return
        val store = g.lastCamera
        g.scope.launch(Dispatchers.IO) { store.write(LastCameraStore.Camera(centre, zoom)) }
    }

    /** `--ef lat/lon/zoom`: one camera move; the director leaves a follow mode as for a user's pan. */
    fun moveFromTest(target: CameraTarget) {
        val c = controller
        if (c == null || !styleLoaded) {
            pendingRequest = target // kept until the style is loaded, never dropped
            return
        }
        g.director.onGesture(MapGesture.CameraMovedByUser)
        c.moveCamera(target)
    }

    private fun onGesture(gesture: MapGesture) {
        flows.onGesture(gesture)
    }

    fun destroy() {
        scope.cancel()
        controller?.release()
        controller = null
        view.onDestroy()
    }

    /**
     * MapLibre's own cap (setMaximumFps), and on Android 11+ the frame rate of the map's surface
     * (Surface.setFrameRate), the platform's way to ask the display for a refresh rate.
     */
    fun applyFrameRate(r: FrameRateRequest) {
        view.setMaximumFps(r.maxFps)
        surfaceRate = r.surfaceFrameRate
        val sv = view.renderView as? SurfaceView
        if (sv == null) {
            Log.i(TAG, "frame rate requested: max ${r.maxFps} fps (no SurfaceView)") // log-ok: numbers
            return
        }
        if (!surfaceCallbackAdded) {
            surfaceCallbackAdded = true
            sv.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = setSurfaceRate(holder.surface)
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            })
        }
        sv.holder.surface?.takeIf { it.isValid }?.let(::setSurfaceRate)
        Log.i(TAG, "frame rate requested: max ${r.maxFps} fps, surface ${r.surfaceFrameRate} Hz") // log-ok: numbers
    }

    private fun setSurfaceRate(s: Surface) {
        if (Build.VERSION.SDK_INT < 30 || !s.isValid) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                s.setFrameRate(surfaceRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                s.setFrameRate(surfaceRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Surface.setFrameRate refused: ${e.javaClass.simpleName}") // log-ok: an exception's class name
        }
    }

    private fun sinceProcessStart() = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
}

/** Logs, once per second of drawing, how many map frames were drawn and how long they took. */
private class FrameWindow {
    private var start = 0L
    private var count = 0
    private var sum = 0.0
    private var max = 0.0

    fun add(ms: Double) {
        val now = SystemClock.uptimeMillis()
        if (count == 0) start = now
        count++
        sum += ms
        if (ms > max) max = ms
        if (now - start >= 1000) {
            Log.i(TAG, "frames: n=$count in ${now - start} ms, cpu avg=${"%.2f".format(sum / count)} ms max=${"%.2f".format(max)} ms") // log-ok: counts and milliseconds
            count = 0
            sum = 0.0
            max = 0.0
        }
    }
}

/** For the scene's theme argument in tests and screens. */
fun themeOf(night: Boolean): MapTheme = if (night) MapTheme.NIGHT else MapTheme.DAY
