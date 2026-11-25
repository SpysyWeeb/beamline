package io.github.spysyweeb.beamline.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.app.location.AccelerometerMotionSource
import io.github.spysyweeb.beamline.app.location.AndroidPositionSource
import io.github.spysyweeb.beamline.app.location.RotationVectorHeadingSource
import io.github.spysyweeb.beamline.app.ui.AppFlows
import io.github.spysyweeb.beamline.feature.drive.EventCards
import io.github.spysyweeb.beamline.feature.search.SavedPlacesFeature
import io.github.spysyweeb.beamline.feature.search.SearchFeature
import io.github.spysyweeb.beamline.feature.states.GetPacks
import io.github.spysyweeb.beamline.feature.states.GetPacksConfig
import io.github.spysyweeb.beamline.installed.InstalledLibrary
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.PositionRules
import io.github.spysyweeb.beamline.location.PuckTracker
import io.github.spysyweeb.beamline.location.SharedPositionSource
import io.github.spysyweeb.beamline.map.scene.CameraDirector
import io.github.spysyweeb.beamline.map.scene.CameraRules
import io.github.spysyweeb.beamline.map.scene.MapSceneMapper
import io.github.spysyweeb.beamline.nav.BeamlineNavigationSession
import io.github.spysyweeb.beamline.nav.EngineOpener
import io.github.spysyweeb.beamline.nav.GeometricFollowerFactory
import io.github.spysyweeb.beamline.nav.GuidanceRules
import io.github.spysyweeb.beamline.nav.TripStore
import io.github.spysyweeb.beamline.packs.AppStyle
import io.github.spysyweeb.beamline.packs.ComposeRules
import io.github.spysyweeb.beamline.packs.ComposedMap
import io.github.spysyweeb.beamline.packs.InstallRules
import io.github.spysyweeb.beamline.packs.OverviewMap
import io.github.spysyweeb.beamline.packs.PackManifest
import io.github.spysyweeb.beamline.packs.StyleComposer
import io.github.spysyweeb.beamline.packs.StyleException
import io.github.spysyweeb.beamline.packs.Theme
import io.github.spysyweeb.beamline.routing.banner.BannerRules
import io.github.spysyweeb.beamline.routing.mobile.ValhallaEngine
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.search.AddrKeyFingerprint
import io.github.spysyweeb.beamline.search.NearStreet
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.SearchHub
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import io.github.spysyweeb.beamline.settings.AppState
import io.github.spysyweeb.beamline.app.online.KeystoreKey
import io.github.spysyweeb.beamline.app.online.NearbyPlace
import io.github.spysyweeb.beamline.app.online.OwnChange
import io.github.spysyweeb.beamline.app.online.OwnChanges
import io.github.spysyweeb.beamline.feature.search.placeTypeIcon
import io.github.spysyweeb.beamline.search.NearbyPoi
import io.github.spysyweeb.beamline.app.online.OnlineSettings
import io.github.spysyweeb.beamline.app.online.OsmAccount
import io.github.spysyweeb.beamline.app.online.OsmAccountStore
import io.github.spysyweeb.beamline.app.online.OsmClient
import io.github.spysyweeb.beamline.app.online.OnlineSettingsStore
import io.github.spysyweeb.beamline.app.online.OsmHttp
import io.github.spysyweeb.beamline.app.online.OsmServer
import io.github.spysyweeb.beamline.app.online.OutboxSender
import io.github.spysyweeb.beamline.app.online.OutboxState
import io.github.spysyweeb.beamline.app.online.OutboxStore
import io.github.spysyweeb.beamline.app.online.SendResult
import io.github.spysyweeb.beamline.app.online.SecretBox
import io.github.spysyweeb.beamline.app.online.SignIn
import io.github.spysyweeb.beamline.feature.states.TaggingSocketFactory
import io.github.spysyweeb.beamline.settings.AppStateStore
import io.github.spysyweeb.beamline.app.map.LastCameraStore
import io.github.spysyweeb.beamline.settings.Settings
import io.github.spysyweeb.beamline.settings.SunRule
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.settings.Units
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.concurrent.Executors

/** The overview map bundled in the APK and the record sync_assets.py wrote next to it. */
data class OverviewInfo(val map: OverviewMap, val source: String, val bytes: Long)

/** The style bundle's record (assets/style/bundle-version.json). */
data class StyleVersion(val sha256: String, val gitCommit: String?)

/** What the map shows: the composed style for one installed generation and theme. */
sealed interface MapStyleState {
    data object Loading : MapStyleState
    data class Ready(val map: ComposedMap, val theme: MapTheme, val generation: Long) : MapStyleState
    data class Failed(val message: String) : MapStyleState
}

/**
 * Everything the screens, the guidance service and (later) a car screen share, created once per
 * process (docs/architecture.md 5.4 item 2). The logic lives in the UI-free modules; this class only
 * wires them to Android (files, assets, sensors, threads).
 *
 * [dataDir] holds the three files that can name a place of the user (`saved-places.json`,
 * `recents.json`, `trip.json`): the no-backup folder, or its `test/` folder in a process started
 * with the test extra `--es private_data test` (docs/architecture-saved-places.md PV4, PV7).
 * [locationLog]: the puck's position line is logged (PV7 item 6, `--ez location_log true`).
 */
class AppGraph(context: Context, val dataDir: File, val locationLog: Boolean) {
    private val app = context.applicationContext

    /** Lives as long as the process: installs, downloads and guidance keep running when a screen closes. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ------------------------------------------------------------------ threads (1.2)
    private fun thread(name: String) = Executors.newSingleThreadExecutor { r -> Thread(r, name).apply { isDaemon = true } }.asCoroutineDispatcher()
    val navDispatcher = thread("beamline-nav")
    val routingDispatcher = thread("beamline-routing")
    val searchDispatcher = thread("beamline-search")

    /** Settings and app-state writes (each a write, fsync and rename) leave the main thread, in the order asked (1.2). */
    private val storeDispatcher = thread("beamline-store")

    val clock = Clock { SystemClock.elapsedRealtime() }

    private fun asset(path: String): String = app.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }

    // ------------------------------------------------------------------ settings, app state, units
    val settings = SettingsStore(File(app.filesDir, "settings.json"))
    val appState = AppStateStore(File(app.filesDir, "app-state.json"))

    /** Where the map was left, for a cold start without a position (K1; with the place files: it can name one). */
    val lastCamera = LastCameraStore(File(dataDir, LastCameraStore.FILE_NAME))

    /**
     * A change of the settings from a screen: the stores write and fsync on the calling thread, which
     * on slow flash stalls a tap (section 1.2 puts JSON stores on an IO thread). One thread keeps the
     * changes in order; the StateFlow updates when the write is done.
     */
    fun updateSettings(change: (Settings) -> Settings) {
        scope.launch(storeDispatcher) { settings.update(change) }
    }

    /** The same for the app state (the driver notice accepted). */
    fun updateAppState(change: (AppState) -> AppState) {
        scope.launch(storeDispatcher) { appState.update(change) }
    }

    /** The phone's 12/24-hour setting (decision 6.32); read again whenever the activity starts. */
    val clock24h = MutableStateFlow(DateFormat.is24HourFormat(app))
    private val unitRules = UnitRules.bundled()
    private fun formatter(units: Units, h24: Boolean) =
        UnitFormatter(unitRules, if (units == Units.KILOMETRES) UnitSystem.METRIC else UnitSystem.IMPERIAL, h24)
    val units: StateFlow<UnitFormatter> = combine(settings.settings, clock24h) { s, h -> formatter(s.units, h) }
        .stateIn(scope, SharingStarted.Eagerly, formatter(settings.settings.value.units, clock24h.value))

    // ------------------------------------------------------------------ the map style
    val composeRules: ComposeRules = ComposeRules.parse(asset("rules/map-compose.json"))
    val style: AppStyle = AppStyles.appStyle(::asset, composeRules)
    val styleVersion: StyleVersion = (Json.parseToJsonElement(asset("style/bundle-version.json")) as JsonObject).let { o ->
        StyleVersion(sha256 = o["content_sha256"]!!.jsonPrimitive.content, gitCommit = o["git_commit"]?.jsonPrimitive?.content)
    }

    /** Null in a build made without the overview map (-Pbeamline.allowNoOverview=true). */
    val overview: OverviewInfo? = try {
        val o = Json.parseToJsonElement(asset("basemap/us-base.json")) as JsonObject
        OverviewInfo(
            map = AppStyles.overview(o["attribution"]?.jsonPrimitive?.content),
            source = o["source"]?.jsonPrimitive?.content ?: "?",
            bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0,
        )
    } catch (e: java.io.IOException) {
        Log.w(TAG, "no overview map in this build")
        null
    }

    // ------------------------------------------------------------------ position
    val positionRules: PositionRules = PositionRules.parse(asset("rules/position.json"))
    val positions = AndroidPositionSource(app)

    /** One GPS listener for the app: the puck and the session share it (2.10). */
    val sharedPositions = SharedPositionSource(positions, scope)
    val puck = PuckTracker(
        positions = sharedPositions,
        headings = RotationVectorHeadingSource(app) { positions.lastLocation },
        rules = positionRules,
        clock = clock,
        motion = AccelerometerMotionSource(app),
    )

    /** Precise location is granted (an approximate-only grant counts as not allowed, D4); set by the activity. */
    val preciseLocation = MutableStateFlow(positions.hasFinePermission())

    // ------------------------------------------------------------------ installed packs
    private val external: File = app.getExternalFilesDir(null) ?: app.filesDir
    val packsDir = File(external, "packs")
    val routingDir = File(external, "routing")
    private val freeBytes: (File) -> Long = { it.usableSpace }

    /** One InstalledLibrary for the app (P's note): packs and routing on the volume of the download folders. */
    val installed = InstalledLibrary(
        packsDir = packsDir,
        routingDir = routingDir,
        readers = composeRules.readers().copy(addrKeyFingerprint = AddrKeyFingerprint.app()),
        rules = InstallRules.parse(asset("rules/packs.json")),
        scope = scope,
        io = Dispatchers.IO,
        freeBytes = freeBytes,
    )

    // ------------------------------------------------------------------ search and saved places
    val recents = RecentPlaces(File(dataDir, "recents.json"))
    val saved = SavedPlaces(File(dataDir, "saved-places.json"), SavedRules.bundled(), Dispatchers.IO.asExecutor())
    val search = SearchFeature(installed.installed, recents, saved, units, scope, searchDispatcher)
    val savedFeature = SavedPlacesFeature(saved, recents, installed.installed, units, scope)

    // ------------------------------------------------------------------ online features (docs/online-design.md, batch O1)
    /** The switches (section 2): Offline only until the user chooses. */
    val online = OnlineSettingsStore(File(app.filesDir, OnlineSettingsStore.FILE_NAME))

    fun updateOnline(change: (OnlineSettings) -> OnlineSettings) {
        scope.launch(storeDispatcher) { online.update(change) }
    }

    /** The OSM test server in debug and test-extras builds; openstreetmap.org only in the plain release (4.6). */
    val osmServer = OsmServer.forBuild(BuildConfig.OSM_SERVER)
    private val osmAllowed = { online.state.value.osmAllowed }
    /** Rule N1: with PackHttp, the only code that opens a connection; refuses everything while its switch is off (N4). */
    val osm = OsmHttp(osmServer, BuildConfig.VERSION_NAME, osmAllowed, TaggingSocketFactory(TaggingSocketFactory.OSM_TAG) { null })
    /** The outbox (4.5) lives with the saved places, in the no-backup folder (PV1-PV8). */
    val outbox = OutboxStore(File(dataDir, OutboxStore.FILE_NAME))
    /** Batch O2 (4.4): the signed-in OSM account, sealed with a Keystore key in the no-backup folder (PV1-PV8). */
    val accounts = OsmAccountStore(dataDir, SecretBox { KeystoreKey.get() })
    /** The app's public OAuth 2 client on [osmServer] (the test server's in test builds). */
    val osmClient = OsmClient(BuildConfig.OSM_CLIENT_ID, OsmClient.REDIRECT_URI)
    val outboxSender = OutboxSender(outbox, osm, osmServer, osmAllowed, Dispatchers.IO) { accounts.account.value }
    /** True while a sign-in's token and name are being fetched. */
    val signingIn = MutableStateFlow(false)
    /**
     * Test builds only (BuildConfig.TEST_EXTRAS, `--es osm_map`): pack OSM ids shown as objects made
     * on the OSM test server, where the pack's own ids do not exist (docs/online-design.md 4.6).
     */
    @Volatile var osmTestMap: Map<String, String> = emptyMap()

    /** The OSM object the sheet edits for a pack's [osm] (itself, except in a test build with a test object). */
    fun osmFor(osm: String?): String? = osm?.let { if (BuildConfig.TEST_EXTRAS) osmTestMap[it] ?: it else it }

    /** Batch O3: a pack POI near a pressed point as the spot check takes it (its OSM object as [osmFor] gives it). */
    fun nearbyOf(p: NearbyPoi) = NearbyPlace(
        osm = osmFor(p.osm), name = p.name, typeCode = p.code, typeWords = p.label, lat = p.lat, lon = p.lon, distanceM = p.distanceM,
        fromOsm = false, packHours = p.openingHours, packPhone = p.phone, packWebsite = p.website, packOsm = p.osm,
    )

    /**
     * Batch O3 (docs/next-round.md item 26): the user's own sent changes shown as if the map had them, until
     * an installed pack does ([OwnChanges]): the search rows and the place sheet (SearchFeature.ownPlaces)
     * and the own-changes layer of the map (OwnLayer). From the outbox and the installed packs only;
     * nothing is sent anywhere.
     */
    val ownChanges: StateFlow<List<OwnChange>> = combine(outbox.items, installed.installed) { items, set ->
        OwnChanges.of(items, set.packs) { code -> placeTypeIcon(code) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch { ownChanges.collect { list -> search.ownPlaces = list.map { it.ui } } }
    }

    /**
     * "Sign in to OpenStreetMap" (4.4): a new PKCE sign-in, kept sealed until its redirect; the address
     * of OSM's sign-in page to open in the browser, or null while the switch is off (N4).
     */
    fun startSignIn(): String? {
        if (!osmAllowed()) return null
        val s = SignIn.start(System.currentTimeMillis())
        accounts.startSignIn(s)
        return s.authorizeUrl(osmServer, osmClient)
    }

    /**
     * The redirect [uri] came back: accepted only for the open sign-in (its state, once, in time);
     * then the token and the user's name are fetched and kept. [then]: true when signed in; null
     * when the redirect was not one of this app's sign-ins (ignored).
     */
    fun finishSignIn(uri: String, then: (Boolean?) -> Unit) {
        val s = accounts.pendingSignIn()
        // O2 review: a redirect that does not answer the open sign-in (another state) is ignored and
        // leaves it open; only its own answer (a code, "Deny", or one too late) ends it.
        if (s == null || !s.answers(uri, osmClient)) {
            then(null)
            return
        }
        val code = s.accept(uri, osmClient, System.currentTimeMillis())
        accounts.endSignIn()
        if (code == null) {
            then(false)
            return
        }
        signingIn.value = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val token = osm.token(osmClient.clientId, code, s.verifier, osmClient.redirectUri)
                    accounts.save(OsmAccount(token, osm.userName(token)))
                    true
                } catch (_: Exception) {
                    false
                }
            }
            signingIn.value = false
            then(ok)
            if (ok) sendOutbox()
        }
    }

    /** "Sign out" (4.4): the token is deleted at once; OSM is asked to revoke it when the switch is on. */
    fun signOut() {
        val a = accounts.account.value ?: return
        accounts.clear()
        if (!osmAllowed()) return
        scope.launch(Dispatchers.IO) {
            try {
                osm.revoke(osmClient.clientId, a.token)
            } catch (_: Exception) {
                // offline or refused: the token is gone from the phone either way
            }
            if (BuildConfig.TEST_EXTRAS) revokedCheck(a.token)
        }
    }

    /**
     * Test builds only (O2 review): after sign-out, asks the user-details endpoint with the revoked token
     * and logs the status only (401 when OSM refuses it), never the token.
     */
    @android.annotation.SuppressLint("LogNotTimber") // the project logs with android.util.Log (rule PV2)
    private fun revokedCheck(token: String) {
        val status = try {
            osm.userName(token)
            200
        } catch (e: io.github.spysyweeb.beamline.app.online.OsmError) {
            e.status
        } catch (_: Exception) {
            -1
        }
        Log.i(TAG, "test extras: the revoked token is answered $status") // log-ok: an HTTP status number
    }

    /**
     * Sends the waiting notes when the app is open: after Send, when it comes to the front and when a
     * network appears while it is in front (4.5; no background service). Nothing while the switch is off.
     */
    fun sendOutbox(then: (SendResult) -> Unit = {}) {
        if (!online.state.value.osmAllowed || outbox.items.value.none { it.state == OutboxState.WAITING }) return
        scope.launch { then(outboxSender.sendWaiting()) }
    }

    // ------------------------------------------------------------------ navigation
    val guidanceRules: GuidanceRules = GuidanceRules.bundled()
    /**
     * Front doors of large stores for the trip planner (docs/next-round.md item 28): its own read-only
     * view of the installed search packs, made on first use and used on the routing thread only (the
     * search screen's hub lives on the search thread).
     */
    private val doorHub by lazy { SearchHub(warmUp = false) }
    val session = BeamlineNavigationSession(
        positions = sharedPositions,
        clock = clock,
        wallClockMs = System::currentTimeMillis,
        installed = installed.installed,
        holdPackChanges = installed::holdChanges,
        engines = EngineOpener { ValhallaEngine.open(it) },
        followers = GeometricFollowerFactory(),
        settings = settings,
        store = TripStore(File(dataDir, "trip.json")),
        rules = guidanceRules,
        presets = PresetSet.bundled(),
        bannerRules = BannerRules.bundled(),
        navDispatcher = navDispatcher,
        routingDispatcher = routingDispatcher,
        // by the place's own name, else by the name a saved place keeps as the first part of its second line
        // ("Erie Maritime Museum, 150 East Front Street, Erie": the user may have renamed it)
        doorOf = { place, set ->
            doorHub.doorAt(set, place.name, place.point)
                ?: place.subtitle?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() && it != place.name }?.let { doorHub.doorAt(set, it, place.point) }
        },
        // item 36: the packs' traffic lights for the route being guided
        signalsIn = { set, cells -> doorHub.signalsIn(set, cells) },
    )
    /** The street at a point, for a dropped pin's sheet (docs/next-round.md item 31); reads a pack: not for the main thread. */
    fun streetAt(point: GeoPoint): NearStreet? = doorHub.streetAt(installed.installed.value, point)

    val cards = EventCards((guidanceRules.stopCardS * 1000).toLong())

    // ------------------------------------------------------------------ the map surface
    val director = CameraDirector(CameraRules.bundled(), clock)
    /** K1a: the map's bearing now (PackMap, on every camera move), for the compass button's needle. */
    val mapBearing = MutableStateFlow(0f)
    val mapper = MapSceneMapper()

    /**
     * The system's dark mode, kept current by the activity; with Settings' map style it decides the
     * theme. Read from the configuration at once, so a dark phone with the Automatic style never
     * composes and loads a day style first (the activity's first composition comes later).
     */
    val systemDark = MutableStateFlow(
        (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
    )
    /** The map style Automatic's rule: night from civil dusk to civil dawn, with a margin (SunRule, settings section 2.10). */
    private val sunRule = SunRule()

    /** Test extras only (`--es sun_at`): how far the clock the sun rule sees is moved, ms. 0 in every build for users. */
    @Volatile
    var sunClockOffsetMs = 0L

    private val _sunDark = MutableStateFlow<Boolean?>(null)

    /** The sun rule's answer: true at night; null without a place to use (Automatic then follows the phone). */
    val sunDark: StateFlow<Boolean?> = _sunDark

    /** Where the sun rule looks from: the last known position, else the middle of the first installed state, else none. */
    private fun sunPlace(): GeoPoint? = positions.lastKnownPoint()
        ?: installed.installed.value.packs.firstOrNull()?.bounds?.let { GeoPoint((it.south + it.north) / 2, (it.west + it.east) / 2) }

    /**
     * Runs the sun rule now (no network; nothing of it is logged, PV2) and returns how long until it
     * should run again: a minute, or less when the switch comes sooner. The activity calls it while
     * it is started (so also when the app comes to the front); a change of the installed states too.
     */
    @Synchronized
    fun recheckSun(): Long {
        val p = sunPlace()
        if (p == null) {
            _sunDark.value = null
            return SunRule.CHECK_MS
        }
        val now = System.currentTimeMillis() + sunClockOffsetMs
        val dark = sunRule.dark(now, p.lat, p.lon, _sunDark.value)
        _sunDark.value = dark
        return sunRule.delayMs(now, p.lat, p.lon, dark)
    }

    init {
        recheckSun()
        // the middle of the installed state can be the only place there is: look again when the states change
        scope.launch { installed.installed.map { it.packs.firstOrNull()?.bounds }.distinctUntilChanged().collect { recheckSun() } }
    }

    val night: StateFlow<Boolean> = combine(settings.settings, systemDark, sunDark) { s, d, sun -> s.nightMap(d, sun) }
        .stateIn(scope, SharingStarted.Eagerly, settings.settings.value.nightMap(systemDark.value, sunDark.value))

    /** The place whose sheet is open (a PICKED pin), set by the screens. */
    val sheetPlace = MutableStateFlow<Place?>(null)

    private val _mapStyle = MutableStateFlow<MapStyleState>(MapStyleState.Loading)
    val mapStyle: StateFlow<MapStyleState> = _mapStyle

    /** What the map draws on top of the style (2.5): trip, puck, theme, the picked place, saved places, the search results (S11). */
    val scene: StateFlow<MapScene>

    // ------------------------------------------------------------------ getting packs
    val getPacks = GetPacks(
        context = app,
        installed = installed,
        settings = settings,
        units = units,
        config = GetPacksConfig(
            catalogUrl = BuildConfig.CATALOG_URL,
            allowCleartext = BuildConfig.DEBUG,
            minManifestVersion = PackManifest.SUPPORTED_VERSION,
            maxManifestVersion = PackManifest.SUPPORTED_VERSION,
            downloadsDir = File(external, "downloads"),
            stateDir = File(app.filesDir, "getpacks"),
            freeBytes = freeBytes,
            routingSetBytes = { routingDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } },
            openStates = { Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_STATES) },
            openMap = { Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_MAP) },
        ),
        scope = scope,
    )

    /** Item 41: the app's own updates from its GitHub releases (off in debug builds). */
    val appUpdates = io.github.spysyweeb.beamline.app.update.AppUpdates(app, scope)

    /** True while guiding (an app update never downloads or installs then). */
    fun guiding(): Boolean = session.state.value.phase.let { it == io.github.spysyweeb.beamline.api.nav.Phase.GUIDING || it == io.github.spysyweeb.beamline.api.nav.Phase.REROUTING }

    init {
        // Item 40: the daily map-update check runs on each start (MainActivity.onStart) and also as soon
        // as the user turns it on; the first value is onStart's to act on.
        scope.launch {
            var first = true
            online.state.map { it.mapUpdatesAllowed }.distinctUntilChanged().collect { allowed ->
                if (!first && allowed) getPacks.checkForUpdates()
                first = false
            }
        }
        // Item 41: the same for app updates
        scope.launch {
            var first = true
            online.state.map { it.appUpdatesAllowed }.distinctUntilChanged().collect { allowed ->
                if (!first && allowed) appUpdates.onAppVisible(true, ::guiding)
                first = false
            }
        }
    }

    /** Screens and flows between them (5.4 items 3-9); lives with the process like the session. */
    val flows: AppFlows by lazy { AppFlows(this) }

    init {
        scene = combine(
            combine(session.state, puck.puck, night) { t, p, n -> Triple(t, p, n) },
            combine(sheetPlace, savedFeature.ui.map { it.mapPick?.place }.distinctUntilChanged()) { sheet, pick -> pick ?: sheet },
            combine(saved.places, saved.showOnMap) { places, show -> if (show) places else emptyList() },
            search.results.map { r -> r?.places?.map { it.point } ?: emptyList() }.distinctUntilChanged(),
        ) { (trip, p, n), picked, savedShown, results ->
            mapper.scene(trip, p, if (n) MapTheme.NIGHT else MapTheme.DAY, picked, savedShown, results)
        }.stateIn(scope, SharingStarted.Eagerly, MapScene(MapTheme.DAY))

        // The style: recomposed when the installed generation or the theme changes (2.2), off the main thread.
        scope.launch {
            combine(installed.installed.filter { it.scanned }, night) { set, n -> set to n }
                .distinctUntilChanged { a, b -> a.first.generation == b.first.generation && a.first.packs == b.first.packs && a.second == b.second }
                .collect { (set, n) ->
                    val theme = if (n) MapTheme.NIGHT else MapTheme.DAY
                    _mapStyle.value = try {
                        val c = withContext(Dispatchers.Default) {
                            StyleComposer.compose(style, if (n) Theme.NIGHT else Theme.DAY, overview?.map, set.packs)
                        }
                        MapStyleState.Ready(c, theme, set.generation)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: StyleException) {
                        Log.w(TAG, "style not composed: ${e.javaClass.simpleName}") // log-ok: an exception's class name
                        MapStyleState.Failed(e.message ?: "The map style could not be made.")
                    } catch (e: Exception) {
                        // Whatever is on the device, a bad pack must not crash the app on every start.
                        Log.w(TAG, "style not composed: ${e.javaClass.simpleName}") // log-ok: an exception's class name
                        MapStyleState.Failed("The map could not be shown (${e.javaClass.simpleName}).")
                    }
                }
        }

        // The camera director follows the trip and the puck, and ticks once a second (M's wiring).
        scope.launch { session.state.collect { director.onTrip(it) } }
        scope.launch { puck.puck.collect { director.onPuck(it) } }
        // K1a: while the map turns with the compass, the sensor at 125 readings a second (every 8 ms,
        // faster than the screen redraws: 0.1.30) to the camera, which calms it (CompassFilter)
        scope.launch {
            val fast = io.github.spysyweeb.beamline.app.location.RotationVectorHeadingSource(
                app, COMPASS_SAMPLING_US) { positions.lastLocation }
            director.compassFollow.collectLatest { on -> if (on) fast.headings().collect { director.onHeading(it.degrees) } }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                director.tick()
            }
        }

        // One-shot cards (D): StopReached, Arrived, RerouteFailed on screen for stop_card_s.
        scope.launch {
            session.events.collect { e ->
                cards.onEvent(e, clock.elapsedMs())
                Log.i(TAG, "trip event ${eventName(e)}") // log-ok: the event's name, enum names and indexes (never a place)
            }
        }
        scope.launch {
            session.state.map { it.phase }.distinctUntilChanged().collect { p ->
                Log.i(TAG, "trip phase $p") // log-ok: an enum name
            }
        }
        // For the end-to-end checks of section 8 ("the step advances at the three maneuvers only",
        // "gps GOOD", off-route suspicion): each logged when it changes.
        scope.launch {
            session.state.map { it.gps }.distinctUntilChanged().collect { gps ->
                Log.i(TAG, "trip gps $gps") // log-ok: an enum name
            }
        }
        scope.launch {
            session.state.map { s -> s.guidance?.banner?.view?.maneuverIndex to (s.guidance?.offRouteSuspected == true) }
                .distinctUntilChanged().collect { (u, suspected) ->
                    Log.i(TAG, "trip banner maneuver ${u ?: -1} suspected=$suspected") // log-ok: an index and a boolean
                }
        }
        // Rule B9 (batch C1): the lane warning shown and gone (the emulator drive keys its shots on these)
        scope.launch {
            session.state.map { it.guidance?.laneWarning != null }.distinctUntilChanged().collect { shown ->
                Log.i(TAG, if (shown) "trip lane warning shown" else "trip lane warning gone") // log-ok: one of two fixed texts
            }
        }
        // Section 8 row 6 (brake lenses after 1 s stopped, off within one fix of moving) and the
        // camera column (PREVIEW, GUIDE_FOLLOW, GUIDE_FREE after a pan and back after 15 s, ARRIVED).
        scope.launch {
            session.state.map { it.guidance?.vehicle?.motion }.distinctUntilChanged().collect { m ->
                Log.i(TAG, "trip motion ${m ?: "none"}") // log-ok: an enum name
            }
        }
        scope.launch {
            director.state.map { it.mode }.distinctUntilChanged().collect { m ->
                Log.i(TAG, "camera mode $m") // log-ok: an enum name
            }
        }
        // Builds made for testing only: the shown distance (B3, "never goes up") and the lane row (B5).
        if (BuildConfig.TEST_EXTRAS) {
            scope.launch {
                session.state.map { s -> s.guidance?.banner?.let { Triple(it.view.maneuverIndex, it.distanceM.toInt(), it.showLanes) } }
                    .distinctUntilChanged().collect { b ->
                        if (b != null) Log.i(TAG, "trip shown ${b.first} ${b.second} m lanes=${b.third}") // log-ok: an index, metres to the maneuver and a boolean
                    }
            }
        }

        // One log line per install step and per 10 % moved, for the emulator measurements
        // (adb logcat -s BeamlinePacks). Never the label or the installer's words (rule N3, PV2).
        scope.launch {
            var lastTenth = -1L
            installed.install.collect { s ->
                if (s is InstallStatus.Running) {
                    val tenth = if (s.totalBytes > 0) s.doneBytes * 10 / s.totalBytes else -1
                    if (tenth != lastTenth) Log.i(PACKS_TAG, "install ${s.step}: ${s.doneBytes} of ${s.totalBytes} bytes") // log-ok: an enum name and byte counts
                    lastTenth = tenth
                } else {
                    lastTenth = -1
                    Log.i(PACKS_TAG, "install status ${statusName(s)}") // log-ok: the status's fixed name
                }
            }
        }
        scope.launch {
            installed.installed.collect { s ->
                Log.i(PACKS_TAG, "installed generation ${s.generation}: ${s.packs.size} packs, ${s.issues.size} issues, held=${s.changesHeldBy != null}") // log-ok: counts and a boolean
            }
        }

        // The puck's position: the phone kit's location measurement (location_check.py), only in a
        // process started with --ez location_log true (docs/architecture-saved-places.md PV7 item 6).
        scope.launch {
            var bubbleShown = false
            puck.puck.collect { p ->
                if (locationLog && p != null) {
                    Log.i(LOCATION_TAG, "puck %.5f %.5f %s cone=%s acc=%s bubble=%s".format(p.lat, p.lon, p.look, p.coneDeg, p.accuracyM, p.bubbleM)) // private-ok: the puck's position, logged only with --ez location_log true (PV7 item 6)
                }
                // Rule G7: when the uncertainty bubble comes and goes (its size only, never a position).
                val shown = p?.bubbleM != null
                if (shown != bubbleShown) {
                    bubbleShown = shown
                    Log.i(TAG, if (shown) "puck bubble shown" else "puck bubble gone") // log-ok: no position, no size
                }
            }
        }
    }

    /** Whether a trip is being guided (the guidance service and keep-screen-on follow it). */
    fun driving(phase: Phase = session.state.value.phase): Boolean =
        phase == Phase.GUIDING || phase == Phase.REROUTING || phase == Phase.ARRIVED

    /** Fixed names: R8 renames classes in the release build, so a class name would read "uz3". */
    private fun eventName(e: TripEvent): String = when (e) {
        is TripEvent.GuidanceStarted -> "GuidanceStarted"
        is TripEvent.RouteReplaced -> "RouteReplaced ${e.reason}"
        is TripEvent.StopReached -> "StopReached ${e.stopIndex}"
        is TripEvent.Arrived -> "Arrived"
        is TripEvent.RerouteFailed -> "RerouteFailed ${e.problem.kind}"
        is TripEvent.Announce -> "Announce ${e.maneuverIndex} ${e.stage}"
        TripEvent.GuidanceEnded -> "GuidanceEnded"
    }

    private fun statusName(s: InstallStatus): String = when (s) {
        InstallStatus.Idle -> "Idle"
        is InstallStatus.Running -> "Running"
        is InstallStatus.Done -> "Done"
        is InstallStatus.Failed -> "Failed"
        is InstallStatus.Cancelled -> "Canceled"
    }

    companion object {
        const val TAG = "Beamline"
        const val PACKS_TAG = "BeamlinePacks"
        const val LOCATION_TAG = "BeamlineLocation"

        /** K1a: the compass-turned map's sensor period, microseconds (125 readings a second; 0.1.30). */
        const val COMPASS_SAMPLING_US = 8_000
    }
}
