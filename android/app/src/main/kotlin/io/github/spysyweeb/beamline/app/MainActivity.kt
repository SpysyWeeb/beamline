package io.github.spysyweeb.beamline.app

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import io.github.spysyweeb.beamline.app.online.OnlineSettings
import io.github.spysyweeb.beamline.app.online.OsmRef
import io.github.spysyweeb.beamline.app.online.OsmServer
import io.github.spysyweeb.beamline.app.online.PlaceEdits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import io.github.spysyweeb.beamline.feature.search.PlaceAction
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.app.guidance.GuidanceService
import io.github.spysyweeb.beamline.app.map.FrameRateRequest
import io.github.spysyweeb.beamline.app.ui.AppFlows
import io.github.spysyweeb.beamline.app.ui.AppRoot
import io.github.spysyweeb.beamline.app.ui.Host
import io.github.spysyweeb.beamline.app.ui.RootActions
import io.github.spysyweeb.beamline.feature.about.FIRST_RUN_NOTICE_VERSION
import io.github.spysyweeb.beamline.feature.about.Splash
import io.github.spysyweeb.beamline.settings.DisplayMode
import io.github.spysyweeb.beamline.settings.FrameRate
import io.github.spysyweeb.beamline.settings.FrameRates
import io.github.spysyweeb.beamline.settings.MapStyle
import io.github.spysyweeb.beamline.settings.Units
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app's one activity (docs/architecture.md 5.4 items 3, 7, 9, 12): the system splash, the
 * window (edge to edge, bar icons from the map's theme), the permissions asked in context, the
 * notification intents of the downloads, keep-screen-on and the guidance service while guiding,
 * and the test extras.
 */
class MainActivity : ComponentActivity(), Host {

    private val app get() = application as BeamlineApplication
    private lateinit var graph: AppGraph
    private lateinit var flows: AppFlows

    private var locationThen: ((Boolean) -> Unit)? = null
    private var askingPrecise = false
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val any = result.values.any { it }
        refreshPermissions()
        if (askingPrecise) {
            askingPrecise = false
            // Asked from the route sheet: when Android no longer shows the question (refused before),
            // the answer comes at once without a dialog; then say where precise location is allowed.
            if (!graph.positions.hasFinePermission() && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                flows.locationMessage = "Precise location is off for this app. You can allow it in the app's settings."
            }
        } else if (!any) {
            // No rationale after a refusal: Android will not show the dialog again.
            flows.locationMessage = if (!shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
                "Location is turned off for this app. You can allow it in the app's settings."
            } else {
                "Location is not allowed, so your position cannot be shown or used as the start."
            }
        }
        locationThen?.let {
            locationThen = null
            it(any)
        }
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Asked at the first Start while guidance begins: the service's first text was not shown.
        if (granted) GuidanceService.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Splash.install(this) { false } // keep it short: the reveal does the waiting for the map
        super.onCreate(savedInstanceState)
        // The data folder is chosen whenever the graph does not exist yet, also when Android recreates
        // the activity in a new process (savedInstanceState set, the original intent with
        // `private_data test` handed back): a test session must never read the real place files (PV7).
        val cold = coldStartExtras(intent, quiet = savedInstanceState != null)
        graph = app.graph
        flows = graph.flows
        flows.host = this
        if (savedInstanceState == null && cold && BuildConfig.TEST_EXTRAS && intent.getBooleanExtra(EXTRA_RESET_NOTICE, false)) {
            // L5: the phone step meets the notice again after the tests accepted it. Written at once
            // (a test extra, on a cold start before the first frame), so the map never shows first.
            graph.appState.update { it.copy(firstRunNoticeAccepted = 0) }
            Log.i(TAG, "test extras: first-run notice reset")
        }
        useDarkBars(true) // the start is the dark splash; Root sets the bars from the map's theme afterwards
        if (savedInstanceState == null) handle(intent)
        // K1 (docs/next-round.md item 5): a cold start with location allowed opens on the puck in
        // the follow mode, from the last known position, moving when the first fix comes. Without a
        // position the map shows where it was left (PackMap). Location is never asked for here.
        if (savedInstanceState == null && cold && graph.positions.hasPermission() && graph.positions.locationEnabled() && !graph.driving()) {
            graph.puck.start(graph.scope)
            graph.director.onMyLocation()
        }
        setContent { Root() }

        // The map style Automatic: the sun rule runs while the activity is started (at once when the
        // app comes to the front), every minute or at the switch when it comes sooner.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) delay(graph.recheckSun())
            }
        }

        // Keep the screen on and the guidance service running while a trip is guided (section 4).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.session.state.map { graph.driving(it.phase) }.distinctUntilChanged().collect { driving ->
                    if (driving) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        GuidanceService.start(this@MainActivity)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    @Composable
    private fun Root() {
        val dark = isSystemInDarkTheme()
        LaunchedEffect(dark) { graph.systemDark.value = dark }
        val night by graph.night.collectAsState()
        // System bar icons follow the map (a night map under a light status bar would be glaring);
        // dark bars while the reveal plays (A's README).
        LaunchedEffect(night, flows.revealDone) { useDarkBars(night || !flows.revealDone) }
        // A rotation lets the platform redraw the navigation bar with light icons (seen on the emulator:
        // invisible on the day map's light band): set its icon colour again. Navigation bar only, so the
        // drive overlay's status-bar handling (d-overlay.md) is not touched.
        val orientation = LocalConfiguration.current.orientation
        val view = LocalView.current
        LaunchedEffect(night, flows.revealDone, orientation) {
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !(night || !flows.revealDone)
        }
        val settings by graph.settings.settings.collectAsState()
        val modes = remember { displayModes() }
        // Settings no longer offers a frame rate (the owner, 2026-11-21): always the screen's fastest, also where
        // an older version stored "60 frames a second" (there is no screen left to change it back)
        val plan = FrameRates.plan(FrameRate.SCREEN, modes.first, modes.second)
        LaunchedEffect(plan) {
            // Before Android 11 there is no Surface.setFrameRate: pick the display mode instead.
            if (Build.VERSION.SDK_INT < 30) window.attributes = window.attributes.apply { preferredDisplayModeId = plan.preferredModeId }
        }
        val actions = remember {
            RootActions(
                myLocation = ::myLocation,
                openAppSettings = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                },
            )
        }
        AppRoot(graph, flows, FrameRateRequest(plan.mapMaxFps, plan.surfaceFrameRate), modes.first.maxOfOrNull { it.refreshRate } ?: 60f, actions)
    }

    /** Light icons on the dark reveal and the night map, dark ones by day (WindowCompat underneath, D's note). */
    private fun useDarkBars(dark: Boolean) {
        val clear = Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onStart() {
        super.onStart()
        graph.clock24h.value = DateFormat.is24HourFormat(this)
        refreshPermissions()
        if (graph.positions.hasPermission()) graph.puck.start(graph.scope) // foreground only
        // Nothing touches the network before the driver notice is accepted (item 3).
        if (graph.appState.state.value.firstRunNoticeAccepted >= FIRST_RUN_NOTICE_VERSION) {
            graph.getPacks.onAppVisible()
            // Item 40: the list of states at most once a day, when the user allows it (never a pack)
            if (graph.online.state.value.mapUpdatesAllowed) graph.getPacks.checkForUpdates()
        }
        // Batch O1 (docs/online-design.md 4.5): waiting notes go when the app is open and a network is
        // there; the callback answers at once with the current network and again when one appears.
        // Registering asks the system only; it sends nothing. Unregistered in onStop: no background use.
        try {
            getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(outboxNetwork)
            outboxNetworkRegistered = true
        } catch (_: RuntimeException) {
            graph.sendOutbox()
        }
    }

    private var outboxNetworkRegistered = false
    private val outboxNetwork = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread { graph.sendOutbox() }
        }
    }

    override fun onStop() {
        if (outboxNetworkRegistered) {
            outboxNetworkRegistered = false
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(outboxNetwork)
            } catch (_: RuntimeException) {
            }
        }
        graph.puck.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.TEST_EXTRAS && (intent.hasExtra(EXTRA_PRIVATE_DATA) || intent.hasExtra(EXTRA_LOCATION_LOG))) {
            Log.i(TAG, "private data: extra ignored, app already running")
        }
        handle(intent)
    }

    override fun onDestroy() {
        if (flows.host === this) flows.host = null
        super.onDestroy()
    }

    /** Precise location for D4's Start, and the browsing puck once location is allowed (also after a grant made elsewhere). */
    private fun refreshPermissions() {
        graph.preciseLocation.value = graph.positions.hasFinePermission()
        if (graph.positions.hasPermission() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) graph.puck.start(graph.scope)
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    // ------------------------------------------------------------------ permissions in context (section 4)

    override fun requestLocation(then: (granted: Boolean) -> Unit) {
        refreshPermissions()
        if (graph.positions.hasFinePermission()) {
            then(true)
            return
        }
        locationThen = then
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun openOutside(action: PlaceAction, value: String) {
        val intent = when (action) {
            PlaceAction.CALL -> Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", value, null))
            PlaceAction.WEBSITE -> Intent(Intent.ACTION_VIEW, Uri.parse(value)).addCategory(Intent.CATEGORY_BROWSABLE)
            else -> return
        }
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            flows.snackbar = if (action == PlaceAction.CALL) "No app on this phone can make calls" else "No browser on this phone can open it"
        }
    }

    override fun requestPreciseLocation() {
        refreshPermissions()
        if (graph.positions.hasFinePermission()) return
        askingPrecise = true
        locationThen = null
        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    override fun requestNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        // Refused once already: G asked it after the first Download (5.4 item 9: not asked again here).
        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIFICATIONS_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).apply()
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** My-location: asked here, when the user wants their position, never at launch (K1: BROWSE_FOLLOW). */
    private fun myLocation() {
        val follow = {
            if (!graph.positions.locationEnabled()) {
                flows.locationMessage = "Location is turned off on this phone. Turn it on in the quick settings to see your position."
            } else {
                graph.puck.start(graph.scope)
                graph.director.onMyLocation()
            }
        }
        if (graph.positions.hasPermission()) follow() else requestLocation { granted -> if (granted || graph.positions.hasPermission()) follow() }
    }

    // ------------------------------------------------------------------ intents

    private fun handle(intent: Intent) {
        when (intent.action) {
            ACTION_OPEN_STATES -> flows.openStates()
            ACTION_OPEN_MAP -> flows.closeStates()
        }
        // Batch O2 (docs/online-design.md 4.4): the end of OSM's sign-in. AppGraph accepts it only for
        // the sign-in this app started (its state, once, in time); anything else is ignored.
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && data.scheme == OAUTH_SCHEME && data.host == OAUTH_HOST) {
            intent.data = null
            flows.finishSignIn(data.toString())
        }
        if (BuildConfig.TEST_EXTRAS) readTestExtras(intent)
    }

    /**
     * The extras read once, at a cold start only (PV7): `--es private_data test`, `--ez location_log true`.
     * Returns whether this start is a cold one (the graph did not exist yet).
     */
    private fun coldStartExtras(intent: Intent, quiet: Boolean): Boolean {
        if (!BuildConfig.TEST_EXTRAS) return !app.running
        val test = intent.getStringExtra(EXTRA_PRIVATE_DATA) == "test"
        val locationLog = intent.getBooleanExtra(EXTRA_LOCATION_LOG, false)
        val cold = app.chooseColdStart(test, locationLog)
        if (!cold) {
            if (quiet) return false // the activity recreated in a running process: nothing to say
            if (intent.hasExtra(EXTRA_PRIVATE_DATA) || intent.hasExtra(EXTRA_LOCATION_LOG)) Log.i(TAG, "private data: extra ignored, app already running")
        } else if (test) {
            Log.i(TAG, "private data: test set")
        }
        return cold
    }

    /** `--es osm_map n<pack>=n<test>,...` (test builds only; see readTestExtras). */
    @SuppressLint("LogNotTimber") // O2 review: the project logs with android.util.Log (rule PV2); Timber comes only with MapLibre
    private fun osmTestMap(m: String) {
        graph.osmTestMap = graph.osmTestMap + m.split(',').mapNotNull { kv -> kv.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        Log.i(TAG, "test extras: osm map set")
    }

    /** A test object on the OSM test server (test builds only; see readTestExtras). Its id is logged: public test data. */
    @SuppressLint("LogNotTimber") // O2 review: the project logs with android.util.Log (rule PV2); Timber comes only with MapLibre
    private fun osmTestObject(b64: String) {
        val account = graph.accounts.account.value
        if (!BuildConfig.TEST_EXTRAS || graph.osmServer !== OsmServer.TEST || account == null) {
            Log.i(TAG, "test extras: osm test object refused (not a test build, not the test server, or signed out)")
            return
        }
        graph.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val o = kotlinx.serialization.json.Json.parseToJsonElement(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))) as JsonObject
                fun str(k: String) = (o[k] as? JsonPrimitive)?.content
                val tags = (o["tags"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyMap()
                val osm = graph.osm
                val cs = osm.openChangeset(account.token, mapOf("comment" to "Beamline development test object, please ignore", "created_by" to "Beamline ${BuildConfig.VERSION_NAME}"))
                try {
                    if (str("op") == "create") {
                        val id = osm.createTestNode(account.token, cs, str("lat")!!.toDouble(), str("lon")!!.toDouble(), tags.filterValues { it != null }.mapValues { it.value!! })
                        str("map")?.let { graph.osmTestMap = graph.osmTestMap + (it to "n$id") }
                        Log.i(TAG, "test extras: osm test object n$id created") // log-ok: the id of a test object on the OSM test server
                    } else if (str("op") == "create_way") {
                        // item 32: a short test road ({"op":"create_way","points":[[lat,lon],[lat,lon]],"tags":{..}})
                        val note = mapOf("note" to "Beamline development test object on the OSM test server, please ignore")
                        val ids = (o["points"] as kotlinx.serialization.json.JsonArray).map { p ->
                            val ll = p as kotlinx.serialization.json.JsonArray
                            osm.createTestNode(account.token, cs, (ll[0] as JsonPrimitive).content.toDouble(), (ll[1] as JsonPrimitive).content.toDouble(), note)
                        }
                        val id = osm.createTestWay(account.token, cs, ids, tags.filterValues { it != null }.mapValues { it.value!! } + note)
                        Log.i(TAG, "test extras: osm test object w$id created") // log-ok: the id of a test object on the OSM test server
                    } else if (str("op") == "delete") {
                        // batch O3: the test objects are deleted again at the end of the proofs
                        val e = osm.element(OsmRef.parse(str("ref"))!!)
                        osm.deleteTestNode(account.token, cs, e)
                        Log.i(TAG, "test extras: osm test object ${e.ref} deleted") // log-ok: the id of a test object on the OSM test server
                    } else {
                        val ref = OsmRef.parse(str("ref"))!!
                        val e = osm.element(ref)
                        val v = osm.putElement(account.token, e.copy(tags = PlaceEdits.apply(e.tags, tags)), cs)
                        Log.i(TAG, "test extras: osm test object $ref now version $v") // log-ok: the id and version of a test object on the OSM test server
                    }
                } finally {
                    osm.closeChangeset(account.token, cs)
                }
            } catch (_: Exception) {
                Log.w(TAG, "test extras: osm test object failed")
            }
        }
    }

    /**
     * Test extras (docs/architecture.md 5.4 item 12), read only in builds made for testing (debug
     * builds, or a release built with -Pbeamline.testExtras=true): the activity is exported, so in a
     * build for users any app could otherwise start a trip or move the camera.
     */
    private fun readTestExtras(intent: Intent) {
        // Batch O1: `--es online offline|online|ask` sets the online choice (ask: the first-run choice shows again).
        intent.getStringExtra(EXTRA_ONLINE)?.let { o ->
            when (o) {
                "offline" -> graph.online.update { it.offlineOnly() }
                "online" -> graph.online.update { it.allowOnline() }
                "ask" -> graph.online.update { OnlineSettings() }
            }
            Log.i(TAG, "test extras: online choice set")
        }
        // Batch O2 (4.6): `--es osm_map n25479855=n123` shows a pack place as a test object on the OSM test
        // server; `--es osm_test <base64 JSON>` makes or changes such a test object with the signed-in
        // test account ({"op":"create","map":"n25479855","lat":..,"lon":..,"tags":{..}} or
        // {"op":"set","ref":"n123","tags":{"k":"v" or null}}; item 32: {"op":"create_way","points":[[lat,lon],..],"tags":{..}}
        // makes a short test road). Test builds talk only to the test server.
        intent.getStringExtra(EXTRA_OSM_MAP)?.let(::osmTestMap)
        intent.getStringExtra(EXTRA_OSM_TEST)?.let { osmTestObject(it) }
        intent.getStringExtra(EXTRA_UNITS)?.let { u ->
            val units = if (u == "metric") Units.KILOMETRES else Units.MILES
            graph.updateSettings { it.copy(units = units) }
        }
        intent.getStringExtra(EXTRA_STYLE)?.let { st ->
            val style = when (st) {
                "night" -> MapStyle.NIGHT
                "day" -> MapStyle.DAY
                "phone" -> MapStyle.SAME_AS_PHONE
                else -> MapStyle.AUTOMATIC
            }
            graph.updateSettings { it.copy(mapStyle = style) }
        }
        // The clock the sun rule sees (batch A): `--es sun_at 2026-06-21T22:00-04:00` moves it there, `--es sun_at off` back.
        // The clock the opening hours are read at (batch B, rule S10): `--es hours_at 2026-11-18T21:30` (the
        // phone's local time; it runs on from there), `--es hours_at off` back to the phone's clock.
        intent.getStringExtra(EXTRA_HOURS_AT)?.let { at ->
            val start = if (at == "off") null else runCatching { java.time.LocalDateTime.parse(at) }.getOrNull()
            val setAt = android.os.SystemClock.elapsedRealtime()
            graph.search.hoursClock = if (start == null) {
                { java.time.LocalDateTime.now() }
            } else {
                { start.plusNanos((android.os.SystemClock.elapsedRealtime() - setAt) * 1_000_000L) }
            }
            graph.search.refreshHours()
            Log.i(TAG, "test extras: hours clock ${if (start != null) "moved" else "back"}") // log-ok: no time or place, only whether the test clock is moved
        }
        intent.getStringExtra(EXTRA_SUN_AT)?.let { at ->
            val target = if (at == "off") null else runCatching { java.time.OffsetDateTime.parse(at).toInstant().toEpochMilli() }.getOrNull()
            graph.sunClockOffsetMs = target?.let { it - System.currentTimeMillis() } ?: 0L
            graph.recheckSun()
            Log.i(TAG, "test extras: sun clock ${if (target != null) "moved" else "back"}") // log-ok: no time or place, only whether the test clock is moved
        }
        // A poor fix on the emulator (batch C2, rule G7): `--ef fix_accuracy_m 120` makes every fix report 120 m, 0 back.
        if (intent.hasExtra(EXTRA_FIX_ACCURACY)) {
            graph.positions.testAccuracyM = intent.getFloatExtra(EXTRA_FIX_ACCURACY, 0f).takeIf { it > 0f }
            Log.i(TAG, "test extras: fix accuracy ${if (graph.positions.testAccuracyM != null) "set" else "back"}") // log-ok: no position, only whether the accuracy is overridden
        }
        if (intent.hasExtra("lat") && intent.hasExtra("lon")) {
            flows.cameraRequest = CameraTarget(
                center = GeoPoint(intent.getFloatExtra("lat", 0f).toDouble(), intent.getFloatExtra("lon", 0f).toDouble()),
                zoom = intent.getFloatExtra("zoom", 14f).toDouble(),
            )
        }
        val dest = intent.getStringExtra(EXTRA_DEST)?.let { placeOf(it, "Destination") } ?: return
        val origin = intent.getStringExtra(EXTRA_ORIGIN)?.let { s ->
            val parts = s.split(",").map { it.trim() }
            val p = parts.take(2).mapNotNull { it.toDoubleOrNull() }
            if (p.size == 2) Origin.FromPlace(Place("Start", GeoPoint(p[0], p[1]), PlaceSource.MAP_PIN), parts.getOrNull(2)?.toDoubleOrNull()) else null
        }
        val simulate = intent.getStringExtra(EXTRA_SIMULATE)
        val addStop = intent.getStringExtra(EXTRA_ADDSTOP)?.let { placeOf(it, "Stop") }
        val addStopAtM = intent.getFloatExtra(EXTRA_ADDSTOP_AT_M, 1000f).toDouble()
        // batch A: `--ef sim_speed 1.2` drives the simulation 20 % faster (the speed badge's colours, rule D8)
        val speedFactor = intent.getFloatExtra(EXTRA_SIM_SPEED, 1f).toDouble().takeIf { it in 0.2..3.0 } ?: 1.0
        Log.i(TAG, "test extras: trip, origin=${origin != null}, simulate=${simulate ?: "none"}, addstop=${addStop != null}") // log-ok: booleans and the script name typed in adb
        flows.startTestTrip(dest, origin, simulate, addStop, addStopAtM, speedFactor)
    }

    /** "lat,lon,name" (the name may hold commas). */
    private fun placeOf(s: String, fallbackName: String): Place? {
        val parts = s.split(",", limit = 3).map { it.trim() }
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return Place(parts.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: fallbackName, GeoPoint(lat, lon), PlaceSource.MAP_PIN)
    }

    /** The display's modes and the current one, for the frame-rate setting. */
    @Suppress("DEPRECATION")
    private fun displayModes(): Pair<List<DisplayMode>, DisplayMode?> {
        val d: Display = (if (Build.VERSION.SDK_INT >= 30) display else null) ?: windowManager.defaultDisplay
        fun Display.Mode.toMode() = DisplayMode(modeId, physicalWidth, physicalHeight, refreshRate)
        return d.supportedModes.map { it.toMode() } to d.mode.toMode()
    }

    companion object {
        const val TAG = "Beamline"
        const val ACTION_OPEN_STATES = "io.github.spysyweeb.beamline.action.OPEN_STATES"
        const val ACTION_OPEN_MAP = "io.github.spysyweeb.beamline.action.OPEN_MAP"
        const val EXTRA_PRIVATE_DATA = "private_data"
        const val EXTRA_LOCATION_LOG = "location_log"
        const val EXTRA_RESET_NOTICE = "reset_notice"
        const val EXTRA_DEST = "dest"
        const val EXTRA_ORIGIN = "origin"
        const val EXTRA_SIMULATE = "simulate"
        const val EXTRA_ADDSTOP = "addstop"
        const val EXTRA_ADDSTOP_AT_M = "addstop_at_m"
        const val EXTRA_UNITS = "units"
        const val EXTRA_STYLE = "style"
        const val EXTRA_SUN_AT = "sun_at"
        const val EXTRA_HOURS_AT = "hours_at"
        const val EXTRA_FIX_ACCURACY = "fix_accuracy_m"
        const val EXTRA_SIM_SPEED = "sim_speed"
        const val EXTRA_ONLINE = "online"
        const val EXTRA_OSM_MAP = "osm_map"
        const val EXTRA_OSM_TEST = "osm_test"
        const val OAUTH_SCHEME = "io.github.spysyweeb.beamline"
        const val OAUTH_HOST = "oauth"
        private const val PREFS = "beamline_app"
        private const val KEY_NOTIFICATIONS_ASKED = "notifications_asked"
    }
}
