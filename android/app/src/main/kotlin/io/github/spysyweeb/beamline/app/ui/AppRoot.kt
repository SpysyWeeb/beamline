package io.github.spysyweeb.beamline.app.ui

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.spysyweeb.beamline.app.online.EditNoticeDialog
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.app.online.AddPlaceScreen
import io.github.spysyweeb.beamline.app.online.EditPlaceScreen
import io.github.spysyweeb.beamline.app.online.NoteNoticeDialog
import io.github.spysyweeb.beamline.app.online.PlaceEdits
import io.github.spysyweeb.beamline.app.online.SignInNeededDialog
import io.github.spysyweeb.beamline.feature.search.PlaceAction
import io.github.spysyweeb.beamline.app.online.OnlineChoiceScreen
import io.github.spysyweeb.beamline.app.online.OnlineTexts
import io.github.spysyweeb.beamline.app.online.ReportScreen
import io.github.spysyweeb.beamline.app.online.ReportsScreen
import io.github.spysyweeb.beamline.app.online.SpeedLimitScreen
import io.github.spysyweeb.beamline.app.online.SpeedPickDialog
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.nav.END_UNDO_MS
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.app.update.UpdateTexts
import io.github.spysyweeb.beamline.app.AppGraph
import io.github.spysyweeb.beamline.app.BuildConfig
import io.github.spysyweeb.beamline.app.MapStyleState
import io.github.spysyweeb.beamline.app.map.FrameRateRequest
import io.github.spysyweeb.beamline.app.map.PackMap
import io.github.spysyweeb.beamline.app.settings.SettingsScreen
import io.github.spysyweeb.beamline.feature.about.AboutEvent
import io.github.spysyweeb.beamline.feature.about.AboutPackUi
import io.github.spysyweeb.beamline.feature.about.AboutScreen
import io.github.spysyweeb.beamline.feature.about.AboutUi
import io.github.spysyweeb.beamline.feature.about.FIRST_RUN_NOTICE_VERSION
import io.github.spysyweeb.beamline.feature.about.FirstRunNotice
import io.github.spysyweeb.beamline.feature.about.LicencesScreen
import io.github.spysyweeb.beamline.feature.about.Notices
import io.github.spysyweeb.beamline.feature.about.SplashReveal
import io.github.spysyweeb.beamline.feature.about.loadNotices
import io.github.spysyweeb.beamline.feature.drive.DriveOverlay
import io.github.spysyweeb.beamline.feature.drive.DrivePresenter
import io.github.spysyweeb.beamline.feature.search.MapPickBar
import io.github.spysyweeb.beamline.feature.search.MapPickUi
import io.github.spysyweeb.beamline.feature.search.PlaceSheet
import io.github.spysyweeb.beamline.feature.search.ResultsSheet
import io.github.spysyweeb.beamline.feature.search.SavedDialog
import io.github.spysyweeb.beamline.feature.search.SavedPlacesScreen
import io.github.spysyweeb.beamline.feature.search.SearchBar
import io.github.spysyweeb.beamline.feature.search.SearchMode
import io.github.spysyweeb.beamline.feature.search.SearchScreen
import io.github.spysyweeb.beamline.feature.states.StatesMode
import io.github.spysyweeb.beamline.feature.states.StatesScreen
import io.github.spysyweeb.beamline.feature.states.model.Texts as StatesTexts
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** What the root asks the activity for. */
class RootActions(
    val myLocation: () -> Unit,
    val openAppSettings: () -> Unit,
)

/**
 * The app's one window (docs/architecture.md 5.4 items 3-8): the map always composed underneath (it
 * keeps its camera and style), its chrome, the place sheet, the drive overlay, the screens over the
 * map, the dialogs, then the first-run steps and the splash reveal on top.
 */
@Composable
fun AppRoot(g: AppGraph, flows: AppFlows, frameRate: FrameRateRequest, screenMaxHz: Float, actions: RootActions) {
    val night by g.night.collectAsState()
    // 0.1.34: the map style sheet, opened from its button under Settings
    var styleSheetOpen by remember { mutableStateOf(false) }
    BeamlineTheme(night) {
        val trip by g.session.state.collectAsState()
        val camera by g.director.state.collectAsState()
        val units by g.units.collectAsState()
        val precise by g.preciseLocation.collectAsState()
        val set by g.installed.installed.collectAsState()
        val savedUi by g.savedFeature.ui.collectAsState()
        val searchUi by g.search.ui.collectAsState()
        val sheetPlace by g.sheetPlace.collectAsState()
        val results by g.search.results.collectAsState()
        val detailsLoaded by g.search.detailsLoaded.collectAsState()
        val savedPlaces by g.saved.places.collectAsState()
        val appState by g.appState.state.collectAsState()
        val online by g.online.state.collectAsState()
        val outboxItems by g.outbox.items.collectAsState()
        // Batch O3 (item 26): the own changes shown as if real; a sheet is made again when they change
        val ownChanges by g.ownChanges.collectAsState()
        val outboxSending by g.outboxSender.sending.collectAsState()
        val account by g.accounts.account.collectAsState()
        val signingIn by g.signingIn.collectAsState()
        val showPicker by g.getPacks.showFirstRunPicker.collectAsState()
        val firstRunDecided by g.getPacks.firstRunDecided.collectAsState()
        val chip by g.getPacks.mapChip.collectAsState()
        // Item 41: an app update ready to install, and the note after one
        val appUpdate by g.appUpdates.state.collectAsState()
        val appUpdateProblem by g.appUpdates.problem.collectAsState()
        val updatedNote by g.appUpdates.updatedNote.collectAsState()
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        val showOnMap by g.getPacks.showOnMap.collectAsState()
        val mapStyle by g.mapStyle.collectAsState()
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                delay(500)
                value = System.currentTimeMillis()
            }
        }

        val phase = trip.phase
        val driving = phase == Phase.GUIDING || phase == Phase.REROUTING || phase == Phase.ARRIVED
        val tripActive = phase == Phase.PLANNING || phase == Phase.CHOOSING || phase == Phase.GUIDING || phase == Phase.REROUTING
        val pick = savedUi.mapPick
        val top = flows.stack.lastOrNull()
        val searchOpen = flows.stack.any { it is Layer.Search || it == Layer.SavedList }
        val noticeNeeded = appState.firstRunNoticeAccepted < FIRST_RUN_NOTICE_VERSION
        // Batch O1 (docs/online-design.md section 2): the online choice right after the notice, once (also after an update)
        val onlineChoiceNeeded = !noticeNeeded && !online.choiceMade
        val pickerShown = flows.revealDone && !noticeNeeded && !onlineChoiceNeeded && showPicker && !flows.firstRunClosed
        // S8 / SP1: the pill (and G's chip with it) while no trip exists and no search, list or pick is open.
        val pillShown = (phase == Phase.IDLE || phase == Phase.RESUME_OFFERED) && !searchOpen && pick == null
        var sheetHeight by remember { mutableStateOf(0.dp) }
        var resultsHeight by remember { mutableStateOf(0.dp) }
        var pillHeight by remember { mutableStateOf(0.dp) }
        // S11: the results list at the bottom while browsing, no sheet, screen or pick over it
        val resultsShown = results != null && sheetPlace == null && pick == null && top == null &&
            (phase == Phase.IDLE || phase == Phase.RESUME_OFFERED)
        // S10: the statuses follow the clock (each minute)
        val minute = now / 60_000L
        LaunchedEffect(minute) { g.search.refreshHours() }
        // item 47: the drive time for the place window's Directions (the session answers in IDLE only)
        LaunchedEffect(sheetPlace, trip.phase == Phase.IDLE) {
            val p = sheetPlace
            if (p != null && trip.phase == Phase.IDLE) g.session.send(NavIntent.Estimate(p))
        }
        var creditRowHeight by remember { mutableStateOf(0.dp) }
        val density = LocalDensity.current
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // What the camera pads by: the overlay, the status bar's band, and the credit row above a sheet (K2).
        val cameraInsets = AppRules.cameraInsets(flows.overlayInsets, statusBarTop.value, creditRowHeight.value + 8f, driving,
            placeSheet = when {
                sheetPlace != null && pick == null -> sheetHeight.value
                resultsShown -> resultsHeight.value
                else -> 0f
            },
            topChrome = if (resultsShown && pillShown) pillHeight.value else 0f)
        // item 47: a sheet that slides changes the insets every frame; the camera takes them once they
        // have settled (INSETS_SETTLE_MS), so the map refits after the slide, not during it
        LaunchedEffect(cameraInsets) {
            if (flows.cameraInsets != EdgeInsetsDp()) kotlinx.coroutines.delay(INSETS_SETTLE_MS)
            flows.cameraInsets = cameraInsets
        }

        // The map's short credit (item 8): shown again when the map appears, when the app comes back
        // and at each change between browsing, a route preview, guiding and ARRIVED.
        val phaseGroup = when (phase) {
            Phase.IDLE, Phase.RESUME_OFFERED -> 0
            Phase.PLANNING, Phase.CHOOSING -> 1
            Phase.GUIDING, Phase.REROUTING -> 2
            Phase.ARRIVED -> 3
        }
        LaunchedEffect(phaseGroup, flows.revealDone) { flows.showCredit() }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) flows.showCredit() }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        var mapDataOpen by remember { mutableStateOf(false) }
        val recenterShown = driving && phase != Phase.ARRIVED &&
            (camera.showRecenter || camera.mode == CameraMode.GUIDE_FREE || camera.mode == CameraMode.GUIDE_OVERVIEW)

        // The root's Back comes first so that the screens' own handlers (About, licences, States) win.
        BackHandler(enabled = flows.canGoBack()) { flows.onBack() }

        Box(Modifier.fillMaxSize()) {
            PackMap(g, flows, frameRate, Modifier.fillMaxSize())

            // The map fills the whole screen, behind the status bar and the navigation bar (the owner,
            // 2026-11-24: "We want to take up the full screen"); the bars' icons follow the map's theme.

            if (mapStyle is MapStyleState.Failed) {
                MessageCard((mapStyle as MapStyleState.Failed).message, night, "States" to flows::openStates, onDismiss = {})
            }

            // ---- the top of the map (SP2)
            if (pillShown) {
                Column(Modifier.align(Alignment.TopStart).onSizeChanged { pillHeight = with(density) { it.height.toDp() } }
                    .statusBarsPadding().padding(8.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // while a search's results show, the bar keeps its words and an X that ends them
                            SearchBar(onClick = { flows.openSearch(SearchMode.GO_TO, typed = results?.query) }, modifier = Modifier.fillMaxWidth(),
                                query = results?.query, onClear = flows::closeResults)
                            // S11b: the map dragged over results: "Search this area" (the owner, 2026-11-24)
                            results?.let { r -> flows.areaSearch?.let { a -> ChipButton(areaChip(a, r.query), night) { flows.onSearchArea() } } }
                            ProblemNotice(set.issues, night)
                            chip?.let { c -> ChipButton(c, night) { flows.openStates() } }
                            // outside every installed state, zoomed in: say why the map is plain, one tap to States
                            if (chip == null && flows.noMapHere) ChipButton(NO_MAP_HERE, night) { flows.openStates() }
                            // item 41: a downloaded update (a tap installs it; never while guiding), then "Updated to"
                            (appUpdate as? io.github.spysyweeb.beamline.download.AppUpdater.State.Ready)?.takeIf { !driving }?.let { r ->
                                ChipButton(UpdateTexts.readyChip(r.update.versionName), night) { g.appUpdates.install(activity, guiding = driving) }
                            }
                            updatedNote?.let { n -> ChipButton(io.github.spysyweeb.beamline.feature.states.MapChip(n, "$n. Tap to close"), night) { g.appUpdates.dismissUpdatedNote() } }
                        }
                        Gap(8)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // States is in Settings only (the owner, 2026-11-21: one button fewer on the map)
                            RoundButton(BeamlineIcons.Settings, "Settings", night, onClick = flows::openSettings)
                            // 0.1.34 (the owner, 2026-11-25): the map style on the map; not while guiding
                            if (!driving) RoundButton(MapStyleIcon, "Map style", night) { styleSheetOpen = true }
                        }
                    }
                }
            }

            // ---- the bottom: credit and my-location above the place sheet, the drive overlay's
            // sheets or the pick bar (the credit stays visible above the trip bar, item 4).
            val overlayBottom = flows.overlayInsets.bottom.dp
            val sheetShown = sheetPlace != null && pick == null
            val bottomPad = when {
                pick != null -> pickBarRoom(pick)
                sheetShown -> maxOf(sheetHeight, overlayBottom)
                resultsShown -> maxOf(resultsHeight, overlayBottom)
                else -> overlayBottom
            }
            Column(
                // On its side the drive overlay's panel takes the start of the window: keep clear of it.
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = flows.overlayInsets.start.dp, bottom = bottomPad)
                    .let { if (bottomPad == 0.dp) it.navigationBarsPadding() else it },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                flows.locationMessage?.let { m ->
                    Box(Modifier.padding(horizontal = 8.dp)) {
                        MessageCard(m, night, if (m.contains("app's settings")) "App settings" to actions.openAppSettings else null) { flows.locationMessage = null }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp).onSizeChanged { creditRowHeight = with(density) { it.height.toDp() } },
                    verticalAlignment = Alignment.Bottom,
                ) {
                    val credit = (mapStyle as? MapStyleState.Ready)?.map?.creditLine ?: ""
                    // While driving, D's map buttons (Overview, Recenter) sit at the bottom end: keep clear of them.
                    Credit(
                        text = AppRules.shortCredit(credit),
                        shown = AppRules.creditShown(phase, now, flows.creditUntilMs),
                        mark = AppRules.creditMark(driving, credit),
                        night = night,
                        onOpen = { mapDataOpen = true },
                        modifier = Modifier.weight(1f).padding(end = AppRules.creditEndPaddingDp(driving, recenterShown, density.fontScale).dp),
                    )
                    if (!driving) {
                        // K1/K1a: my-location holds the compass (follow -> follow turned by the compass -> follow north up)
                        val compassFollow by g.director.compassFollow.collectAsState()
                        LocationButton(
                            following = camera.mode == io.github.spysyweeb.beamline.api.map.CameraMode.BROWSE_FOLLOW,
                            compass = compassFollow,
                            bearing = g.mapBearing,
                            night = night,
                            onClick = actions.myLocation,
                        )
                    }
                }
            }

            // ---- the drive overlay (D): banner, sheets, trip bar; hidden (not closed) during a pick,
            // and while a screen covers the map (States from the notification or Add stop's search
            // while guiding): the overlay keeps light status-bar icons for its dark banner, which
            // on a light screen would be white on white; leaving, it sets them back from the map.
            if (pick == null && top == null) {
                val drive = DrivePresenter.present(
                    trip, units, now, ZoneId.systemDefault(), camera, flows.stopsEditorOpen,
                    g.cards.shown(g.clock.elapsedMs()), precise,
                ).let { d ->
                    // item 32: with Contribute to OpenStreetMap on, a tap on the speed sign reports the posted limit
                    if (online.osmAllowed) d.copy(guidance = d.guidance?.let { gu -> gu.copy(speed = gu.speed?.copy(canReport = true)) }) else d
                }
                // D8: the speed pair stays above the credit's short line while it shows, then slides down.
                val creditUp = AppRules.creditShown(phase, now, flows.creditUntilMs) &&
                    AppRules.shortCredit((mapStyle as? MapStyleState.Ready)?.map?.creditLine ?: "") != null
                DriveOverlay(drive, onEvent = flows::onDriveEvent, onInsets = flows::onInsets, modifier = Modifier.fillMaxSize(), creditShown = creditUp)
            }
            // ---- the place sheet, above the route sheet and the trip bar (a marker or POI tapped
            // while a trip exists offers "Add stop"); measured so the credit stays above it.
            if (sheetShown) {
                // SP19: rebuilt whenever the saved places change (savedPlaces is read here).
                val sheet = remember(sheetPlace, savedPlaces, phase, set.generation, detailsLoaded, minute, online, outboxItems, ownChanges, trip.estimate, units) {
                    g.search.placeSheet(sheetPlace!!, flows.searchContext(), tripActive, driving, guiding = phase == Phase.GUIDING || phase == Phase.REROUTING)
                        // Batch O1: "Report a problem" while the switch is on and no trip is being driven (section 2)
                        .let { if (online.osmAllowed && !driving) it.copy(canReport = true, canAddPlace = it.place.source == PlaceSource.MAP_PIN) else it }
                        // Batch O2 (4.2, 4.5): "Edit on OpenStreetMap" likewise, and the user's own edit until a pack has it
                        .let { ui -> ui.copy(osm = g.osmFor(ui.osm)).let { if (it.osm != null && online.osmAllowed && !driving) it.copy(canEditOsm = true) else it } }
                        // item 47: "Directions · 6 min" from the session's estimate for this very place
                        .let { ui -> trip.estimate?.takeIf { phase == Phase.IDLE && it.point == ui.place.point }?.let { ui.copy(driveTimeText = units.duration(it.timeS)) } ?: ui }
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { sheetHeight = with(density) { it.height.toDp() } }) {
                    PlaceSheet(sheet, onEvent = { e -> flows.onSheetEvent(e, sheet) })
                }
            }
            if (resultsShown) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { resultsHeight = with(density) { it.height.toDp() } }) {
                    ResultsSheet(results!!, onEvent = flows::onResultsEvent)
                }
            }
            if (pick != null) {
                MapPickBar(pick, onEvent = flows::onSavedEvent, modifier = Modifier.fillMaxSize())
            }
            if (mapDataOpen) MapDataDialog(mapDataCredits(set)) { mapDataOpen = false }
            if (styleSheetOpen && !driving) {
                val appSettings by g.settings.settings.collectAsStateWithLifecycle()
                MapStyleSheet(appSettings.mapStyle, onPick = { st -> g.updateSettings { it.copy(mapStyle = st) } }, onClose = { styleSheetOpen = false })
            }

            // ---- screens over the map (hidden while a map pick runs, SP25; none under the driver
            // notice: the States screen would fetch the catalog before it is accepted, item 3)
            if (pick == null && top != null && !noticeNeeded) {
                when (top) {
                    is Layer.Search -> SearchScreen(searchUi, onEvent = flows::onSearchEvent)
                    Layer.SavedList -> savedUi.list?.let { SavedPlacesScreen(it, onEvent = flows::onSavedEvent) }
                    Layer.Settings -> SettingsScreen(
                        store = g.settings,
                        write = g::updateSettings,
                        driving = driving,
                        onSavedPlaces = flows::openSavedPlacesFromSettings,
                        onStates = flows::openStates,
                        online = online,
                        onOnline = { on -> g.updateOnline { if (on) it.allowOnline() else it.offlineOnly() } },
                        onContributeOsm = { on -> g.updateOnline { it.withContributeOsm(on) } },
                        onCheckMapUpdates = { on -> g.updateOnline { it.withCheckMapUpdates(on) } },
                        appUpdates = g.appUpdates.available,
                        onCheckAppUpdates = { on -> g.updateOnline { it.withCheckAppUpdates(on) } },
                        appUpdateLine = UpdateTexts.line(appUpdate, appUpdateProblem),
                        onCheckAppNow = { g.appUpdates.checkNow(g::guiding) },
                        appUpdateReady = appUpdate is io.github.spysyweeb.beamline.download.AppUpdater.State.Ready,
                        onInstallApp = { g.appUpdates.install(activity, guiding = driving) },
                        reportsText = OnlineTexts.reportsCount(outboxItems),
                        accountName = account?.name,
                        signingIn = signingIn,
                        onSignIn = { flows.signIn() },
                        onSignOut = g::signOut,
                        onReports = { flows.push(Layer.Reports) },
                        onAbout = { flows.push(Layer.About) },
                        onBack = { flows.pop(Layer.Settings) },
                    )
                    Layer.About -> AboutScreen(aboutUi(LocalContext.current, g, set), onEvent = { e ->
                        when (e) {
                            AboutEvent.Back -> flows.pop(Layer.About)
                            AboutEvent.OpenLicences -> flows.push(Layer.Licences)
                            AboutEvent.OpenDriverNotice -> flows.push(Layer.DriverNotice)
                        }
                    })
                    Layer.Licences -> {
                        val context = LocalContext.current
                        val notices by produceState<Notices?>(null) {
                            value = withContext(Dispatchers.IO) {
                                try {
                                    loadNotices(context)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                        notices?.let { LicencesScreen(it, onBack = { flows.pop(Layer.Licences) }) }
                    }
                    Layer.DriverNotice -> FirstRunNotice(onAccept = { flows.pop(Layer.DriverNotice) })
                    Layer.States -> StatesScreen(g.getPacks, StatesMode.MANAGE, onClose = { flows.pop(Layer.States) })
                    // Batch O1 (docs/online-design.md 4.1, 4.5): the report form and My reports
                    is Layer.Report -> {
                        val item = top.itemId?.let { id -> outboxItems.firstOrNull { it.id == id } }
                        val t = top.target
                        if (item != null || t != null) ReportScreen(
                            lat = item?.lat ?: t!!.lat, lon = item?.lon ?: t!!.lon, placeName = item?.placeName ?: t?.placeName, item = item,
                            signedIn = account != null, startChoice = t?.startChoice, startText = t?.startText,
                            // item 32: a point on the map (no place) can fix the road's speed limit itself
                            onSpeedLimit = if (t != null && t.placeName == null && item == null && online.osmAllowed) ({ flows.speedFromReport(t) }) else null,
                            onSend = { text, withName -> if (t != null) flows.sendNote(t, text, withName) },
                            onSave = { text ->
                                if (item != null && outboxSending != item.id) g.outbox.change(item.id, text)
                                flows.pop(top)
                                flows.sendOutboxNow()
                            },
                            onDelete = {
                                if (item != null && outboxSending != item.id) g.outbox.delete(item.id)
                                flows.pop(top)
                            },
                            onBack = { flows.pop(top) },
                        ) else LaunchedEffect(top) { flows.pop(top) } // the item was sent or deleted meanwhile
                    }
                    Layer.Reports -> ReportsScreen(
                        items = outboxItems, sending = outboxSending, canSend = online.osmAllowed, signedIn = account != null,
                        onOpen = {
                            if (it.isSpeed) flows.openSpeedItem(it) else if (it.isAdd) flows.openAddItem(it) else if (it.isEdit) flows.openEditItem(it)
                            else flows.push(Layer.Report(null, it.id))
                        },
                        onOpenLink = { link -> flows.host?.openOutside(PlaceAction.WEBSITE, link) },
                        onRemove = { g.outbox.delete(it.id) },
                        onSendNow = flows::sendOutboxNow,
                        onBack = { flows.pop(Layer.Reports) },
                    )
                    // Batch O2 (docs/online-design.md 4.2): the edit form, its summary and its upload
                    Layer.EditPlace -> {
                        val e = flows.edit
                        if (e != null) EditPlaceScreen(
                            e,
                            onUpload = flows::uploadEdit,
                            onOpenLink = { link -> flows.host?.openOutside(PlaceAction.WEBSITE, link) },
                            onDelete = {
                                e.delete()
                                flows.closeEdit()
                            },
                            onClose = flows::closeEdit,
                        ) else LaunchedEffect(top) { flows.pop(top) }
                    }
                    // Item 32 (docs/online-design.md 4.7): the speed limit of a road
                    Layer.SpeedLimit -> {
                        val sp = flows.speed
                        if (sp != null) SpeedLimitScreen(
                            sp,
                            onUpload = flows::uploadSpeed,
                            onOpenLink = { link -> flows.host?.openOutside(PlaceAction.WEBSITE, link) },
                            onDelete = {
                                sp.delete()
                                flows.closeSpeed()
                            },
                            onClose = flows::closeSpeed,
                        ) else LaunchedEffect(top) { flows.pop(top) }
                    }
                    // Batch O3 (docs/online-design.md 4.3): add a missing place
                    Layer.AddPlace -> {
                        val a = flows.add
                        if (a != null) AddPlaceScreen(
                            a,
                            onUpload = flows::uploadAdd,
                            onSame = flows::editInstead,
                            onOpenLink = { link -> flows.host?.openOutside(PlaceAction.WEBSITE, link) },
                            onDelete = {
                                a.delete()
                                flows.closeAdd()
                            },
                            onClose = flows::closeAdd,
                        ) else LaunchedEffect(top) { flows.pop(top) }
                    }
                }
            }

            // ---- dialogs
            savedUi.dialog?.let { SavedDialog(it, onEvent = flows::onSavedEvent) }
            flows.notePending?.let { NoteNoticeDialog(withName = it.withName, onAnswer = flows::answerNoteNotice) }
            if (flows.editNoticeShown) EditNoticeDialog(onAnswer = flows::answerEditNotice)
            if (flows.speedPicking) SpeedPickDialog(onPick = flows::pickSpeedWhileDriving, onDismiss = { flows.speedPicking = false })
            if (flows.signInFor != null) SignInNeededDialog(
                onSignIn = { flows.answerSignIn(signIn = true, note = false) },
                onNote = { flows.answerSignIn(signIn = false, note = true) },
                onCancel = { flows.answerSignIn(signIn = false, note = false) },
            )
            if (flows.signInToAdd != null) SignInNeededDialog(
                onSignIn = { flows.answerSignInToAdd(signIn = true, note = false) },
                onNote = { flows.answerSignInToAdd(signIn = false, note = true) },
                onCancel = { flows.answerSignInToAdd(signIn = false, note = false) },
                adding = true,
            )
            flows.confirm?.let { c ->
                AlertDialog(
                    onDismissRequest = { flows.confirm = null },
                    text = { Text(c.text) },
                    confirmButton = {
                        TextButton(onClick = {
                            flows.confirm = null
                            c.onGo()
                        }) { Text("Go") }
                    },
                    dismissButton = { TextButton(onClick = { flows.confirm = null }) { Text("Cancel") } },
                )
            }

            // R4: "Trip ended" with Undo, then "Continuing the trip..." (in its place while it shows: one bar at a time)
            val endBar = AppRules.endBar(flows.tripEnded, flows.continuing, trip.phase)
            if (endBar != null) {
                Snackbar(endBar, night, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = overlayBottom + 88.dp),
                    action = if (endBar == TRIP_ENDED) UNDO to flows::undoEnd else null)
            } else {
                flows.snackbar?.let { s ->
                    Snackbar(s, night, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = overlayBottom + 88.dp))
                }
            }

            // ---- start-up order (item 3): reveal over the map, the driver notice, "Choose your state"
            if (flows.revealDone) {
                if (noticeNeeded) {
                    FirstRunNotice(onAccept = {
                        g.updateAppState { it.copy(firstRunNoticeAccepted = FIRST_RUN_NOTICE_VERSION) }
                        g.getPacks.onAppVisible()
                    })
                } else if (onlineChoiceNeeded) {
                    OnlineChoiceScreen(onChoose = { on -> g.updateOnline { if (on) it.allowOnline() else it.offlineOnly() } })
                } else if (pickerShown) {
                    StatesScreen(g.getPacks, StatesMode.FIRST_RUN, onClose = { flows.firstRunClosed = true })
                }
            } else {
                // The reveal also waits until G knows whether the first-run picker is needed (an unfinished
                // first-run download is recovered when the app becomes visible): before, the map with the
                // download chip showed for a moment before the picker (SplashReveal waits 2 s at most).
                SplashReveal(flows.mapReady && (noticeNeeded || firstRunDecided), onFinished = { flows.revealDone = true }, modifier = Modifier.fillMaxSize())
            }
        }

        // R4: the bar goes after END_UNDO_MS, or at once when a new trip begins; then the End is final
        LaunchedEffect(flows.tripEnded) {
            if (flows.tripEnded != null) {
                delay(END_UNDO_MS)
                flows.tripEnded = null
            }
        }
        LaunchedEffect(trip.phase) {
            if (trip.phase != Phase.IDLE) { flows.tripEnded = null; flows.continuing = false }
        }
        // "Continuing the trip..." ends with the phase (above); a cap in case the session never leaves IDLE
        LaunchedEffect(flows.continuing) {
            if (flows.continuing) {
                delay(CONTINUING_CAP_MS)
                flows.continuing = false
            }
        }

        LaunchedEffect(flows.snackbar) {
            if (flows.snackbar != null) {
                delay(4000)
                flows.snackbar = null
            }
        }

        // 9.4 item 5: whenever the map is shown and a pack is to be shown: close States, fit the
        // map to it, say it is ready, then tell GetPacks. "Shown" includes the activity being visible:
        // the composition lives on while Beamline is in the background, and an install that ends there
        // must wait for the next time the map opens (docs/architecture-downloads.md 1.1 step 6), or the
        // snackbar would come and go unseen.
        val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
        val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
        val mapShown = visible && flows.revealDone && !noticeNeeded && !onlineChoiceNeeded && !pickerShown && flows.mapReady
        LaunchedEffect(showOnMap, mapShown) {
            val p = showOnMap ?: return@LaunchedEffect
            if (!mapShown) return@LaunchedEffect
            flows.closeStates()
            flows.cameraRequest = CameraTarget(fit = p.bounds, padding = EdgeInsetsDp(32f, 32f, 32f, 32f))
            flows.snackbar = "${p.name} is ready."
            g.getPacks.showOnMapDone()
        }
    }
}

/** About (2.8): version, style, installed maps with their sizes, every credit. */
private fun aboutUi(context: Context, g: AppGraph, set: InstalledSet): AboutUi {
    val build = buildString {
        append(if (BuildConfig.DEBUG) "Debug build" else "Release build")
        if (!BuildConfig.DEBUG && BuildConfig.TEST_EXTRAS) append(" with test extras")
        append(", map drawn with MapLibre (${if (BuildConfig.MAP_RENDERER == "opengl") "OpenGL ES" else BuildConfig.MAP_RENDERER})")
    }
    return AboutUi(
        appName = context.applicationInfo.loadLabel(context.packageManager).toString(),
        versionText = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        buildText = build,
        styleText = "Map style ${g.styleVersion.sha256.take(12)}",
        // The date and the size as the States screen writes them ("Nov 14, 2026", "1.3 GB").
        packs = set.packs.map { AboutPackUi(it.name, StatesTexts.date(it.osmSnapshot), StatesTexts.size(it.bytesOnDisk)) },
        dataCredits = mapDataCredits(set),
    )
}

/**
 * The full list of map sources (5.4 item 4): About's "Map data" and the credit's dialog. The
 * OpenStreetMap licence with where to read it, the overview's sources, and each installed state's
 * own credit line as the map's line had it.
 */
internal fun mapDataCredits(set: InstalledSet): List<String> =
    listOf(AppRules.OSM_LICENCE, "US overview: Natural Earth; US Census Bureau") + set.packs.map { "${it.name}: ${it.attribution}" }

/**
 * Room for S's pick bar under the credit line and the my-location button (5.4 item 4: they stay above
 * the bar). MapPickBar fills the screen, so its bar cannot be measured from here; this follows its
 * layout (MapPickContent): 16 dp padding above and below, the title (17 sp, up to two lines), the
 * coordinates, note and problem lines (14 sp), 12 dp and the buttons, above the navigation bar, plus a
 * 12 dp gap. A fixed 168 dp left the credit's last line and half of the button under the bar.
 */
@Composable
private fun pickBarRoom(ui: MapPickUi): Dp {
    val fontScale = LocalDensity.current.fontScale
    val title = 24f * fontScale
    val line = 20f * fontScale
    var h = 32f + title * (if (ui.title.length > 28) 2 else 1) + 12f + 48f
    if (ui.subtitle != null) h += line
    if (ui.note != null) h += line
    if (ui.problem != null) h += 8f + 2 * line
    return h.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
}

/** Rule R4's bar (docs/architecture.md 3.10). */
/** The chip over a part of the map no installed state covers (AppFlows.noMapHere); a tap opens States. */
/** S11b: the words of "Search this area" for [state] and the results' [query]. */
internal fun areaChip(state: AppFlows.AreaSearch, query: String) = when (state) {
    AppFlows.AreaSearch.OFFERED -> io.github.spysyweeb.beamline.feature.states.MapChip("Search this area", "Search this area for $query")
    AppFlows.AreaSearch.SEARCHING -> io.github.spysyweeb.beamline.feature.states.MapChip("Searching this area…", "Searching this area for $query")
    AppFlows.AreaSearch.NONE_HERE -> io.github.spysyweeb.beamline.feature.states.MapChip("Nothing found in this area", "Nothing found for $query in this area. Move the map to search again")
}

internal val NO_MAP_HERE = io.github.spysyweeb.beamline.feature.states.MapChip("No map for this area. Add a state.", "No map for this area. Add a state. Opens States")
/** From this zoom on the plain overview is all a place outside the installed states shows. */
internal const val NO_MAP_FROM_ZOOM = 9.0
internal const val TRIP_ENDED = "Trip ended"
internal const val CONTINUING_TRIP = "Continuing the trip..."
internal const val UNDO = "Undo"
/** Longer than the session's 15 s wait for a fix before it offers the trip again. */
internal const val CONTINUING_CAP_MS = 20_000L

/** Item 47: how long the screen's insets must stay the same before the camera refits to them. */
private const val INSETS_SETTLE_MS = 180L
