package io.github.spysyweeb.beamline.feature.states

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.download.StartResult
import io.github.spysyweeb.beamline.feature.states.map.StateShapes
import io.github.spysyweeb.beamline.feature.states.map.StatesMap
import io.github.spysyweeb.beamline.feature.states.map.StatesMapView
import io.github.spysyweeb.beamline.feature.states.map.TapResult
import io.github.spysyweeb.beamline.feature.states.map.rememberStatesMapView
import io.github.spysyweeb.beamline.feature.states.model.Action
import io.github.spysyweeb.beamline.feature.states.model.Dialog
import io.github.spysyweeb.beamline.feature.states.model.HouseNumbersUi
import io.github.spysyweeb.beamline.feature.states.model.JobUi
import io.github.spysyweeb.beamline.feature.states.model.ListEntry
import io.github.spysyweeb.beamline.feature.states.model.SheetKind
import io.github.spysyweeb.beamline.feature.states.model.SheetUi
import io.github.spysyweeb.beamline.feature.states.model.StateRowUi
import io.github.spysyweeb.beamline.feature.states.model.StatesEvent
import io.github.spysyweeb.beamline.feature.states.model.StatesUi
import io.github.spysyweeb.beamline.feature.states.model.Texts
import io.github.spysyweeb.beamline.feature.states.model.listEntries
import io.github.spysyweeb.beamline.feature.states.model.rowIndex
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** StatesScreen (6.9): "Choose your state" (FIRST_RUN) or "States" (MANAGE), from StatesPresenter's texts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatesScreenImpl(getPacks: GetPacks, mode: StatesMode, onClose: () -> Unit, modifier: Modifier) {
    val core = getPacks.core
    // P61 (audit F69): each visit starts fresh (no old search text, selection or banner). Effects run in
    // composition order on entering, so this runs before the flows below start collecting.
    DisposableEffect(core) {
        core.freshScreen()
        onDispose { }
    }
    val context = LocalContext.current
    val debug = remember { (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    LaunchedEffect(Unit) { core.screenOpened() }
    // At most every 6 h while open, and once more when a network appears while a failed list shows (N4).
    LaunchedEffect(Unit) {
        while (true) {
            delay(core.rules.catalogRefreshAfterMin * 60_000L)
            core.screenOpened()
        }
    }
    val network by core.network.collectAsState()
    val catalogState by core.catalog.collectAsState()
    LaunchedEffect(network.connected) {
        if (network.connected && catalogState is io.github.spysyweeb.beamline.download.CatalogState.Failed) core.checkAgain()
    }
    // Made off the main thread (GetPacksCore.ui): the first frame shows the title at once.
    val uiFlow = remember(mode, debug) { core.ui(mode, debug) }
    val presented by uiFlow.collectAsState(initial = null)
    val firstRunOpen by core.showFirstRunPicker.collectAsState()
    val job by core.job.collectAsState()
    val install by core.installed.install.collectAsState()
    val choices by core.choices.collectAsState()

    // The first-run screen closes by itself when the picker is no longer needed (a state installed, Not now, a file).
    LaunchedEffect(firstRunOpen, mode) {
        if (mode == StatesMode.FIRST_RUN && !firstRunOpen && core.installed.installed.value.scanned) onClose()
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { core.notificationsAnswered() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val label = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "pack.tar"
            core.fileChosen(label, PackArchive(label) { context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open") })
        }
    }

    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= 33 && !choices.notificationsAsked &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            core.notificationsAsked()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun on(e: StatesEvent) {
        when (e) {
            is StatesEvent.Select -> core.select(e.code, onMap = e.onMap)
            // the chooser only comes from a tap on the map (P25: outlined, not zoomed)
            is StatesEvent.ChooseOne -> core.select(e.code, onMap = true)
            StatesEvent.CloseSheet -> core.closeSheet()
            is StatesEvent.Query -> core.typed(e.text)
            is StatesEvent.HouseNumbers -> core.setIncludeAddresses(e.on)
            is StatesEvent.Download -> core.download(e.allowMetered, mode == StatesMode.FIRST_RUN) { r -> if (r == StartResult.Started) askNotificationsOnce() }
            StatesEvent.Pause -> core.pause()
            StatesEvent.Continue -> core.resume()
            StatesEvent.UseMobileData -> core.useMobileData()
            StatesEvent.Cancel -> core.screen.update { it.copy(dialog = Dialog.CancelJob) }
            StatesEvent.ConfirmCancel -> core.cancel()
            StatesEvent.Dismiss -> core.dismiss()
            StatesEvent.FreeUpSpace -> {
                val need = job?.needBytes ?: 0L
                val intent = Intent(StorageManager.ACTION_MANAGE_STORAGE).putExtra(StorageManager.EXTRA_REQUESTED_BYTES, need)
                runCatching { context.startActivity(intent) }
            }
            StatesEvent.CheckAgain -> core.checkAgain()
            is StatesEvent.Remove -> core.screen.update { it.copy(dialog = Dialog.Remove(e.packId)) }
            is StatesEvent.ConfirmRemove -> core.remove(e.packId)
            is StatesEvent.Update -> core.installed.installed.value.packs.firstOrNull { it.packId == e.packId }?.let { core.select(it.state, SheetKind.UPDATE) }
            is StatesEvent.AddHouseNumbers -> core.installed.installed.value.packs.firstOrNull { it.packId == e.packId }?.let { core.select(it.state, SheetKind.ADD_HOUSE_NUMBERS) }
            is StatesEvent.RemoveHouseNumbers -> core.screen.update { it.copy(dialog = Dialog.RemoveHouseNumbers(e.packId)) }
            is StatesEvent.ConfirmRemoveHouseNumbers -> core.removeHouseNumbers(e.packId)
            StatesEvent.Details -> {
                val words = job?.detail ?: core.screen.value.removeProblem?.second ?: core.screen.value.fileProblem?.second
                if (words != null) core.screen.update { it.copy(dialog = Dialog.Details(words)) }
            }
            StatesEvent.TurnOnNotifications -> runCatching {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
            StatesEvent.UseFile -> picker.launch(arrayOf("application/x-tar", "application/octet-stream"))
            StatesEvent.NotNow -> {
                core.notNow()
                onClose()
            }
            StatesEvent.OpenMap -> onClose()
            StatesEvent.TapOutside -> core.screen.update { it.copy(mapHint = true) }
            StatesEvent.CloseDialog -> core.screen.update { it.copy(dialog = null, chooser = null) }
            is StatesEvent.ShowOnMap -> {
                core.showOnMapRequest(e.packId)
                core.screen.update { it.copy(readyPackId = null) }
                onClose()
            }
            StatesEvent.CloseBanner -> core.screen.update { it.copy(readyPackId = null, removeProblem = null) }
            StatesEvent.InstallFile -> core.installFile(firstRun = mode == StatesMode.FIRST_RUN)
            StatesEvent.DismissFileProblem -> core.fileProblemDismissed()
            // P13: the top bar's Back arrow closes the screen as system Back does
            StatesEvent.Back -> onClose()
        }
    }

    // The "Tap a state" hint shows for 3 seconds.
    val hint = core.screen.collectAsState().value.mapHint
    LaunchedEffect(hint) {
        if (hint) {
            delay(3_000)
            core.screen.update { it.copy(mapHint = false) }
        }
    }
    // A file install keeps the screen on (section 4 of docs/architecture.md).
    val view = LocalView.current
    val fileInstall by core.fileInstall.collectAsState()
    val fileInstalling = install is InstallStatus.Running && fileInstall != null
    DisposableEffect(fileInstalling) {
        view.keepScreenOn = fileInstalling
        onDispose { view.keepScreenOn = false }
    }

    val ui = presented
    if (ui == null) {
        // The first frame, before the list and the map are ready: the top bar (Back works at once) and the intro.
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Header(mode, if (mode == StatesMode.FIRST_RUN) Texts.CHOOSE_TITLE else Texts.STATES_TITLE, ::on)
                if (mode == StatesMode.FIRST_RUN) Text(Texts.CHOOSE_INTRO, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        return
    }
    StatesBody(
        ui, core.shapes, ::on,
        onMapTap = { r ->
            when (r) {
                // P25: picked on the map: outlined, not zoomed (the zoom went on behind the sheet)
                is TapResult.One -> on(StatesEvent.Select(r.code, onMap = true))
                is TapResult.Choose -> core.screen.update { it.copy(chooser = r.codes) }
                TapResult.None -> on(StatesEvent.TapOutside)
            }
        },
        modifier,
    )
    ui.sheet?.let { s -> SheetView(s, ::on) }
    ui.fileSheet?.let { s -> SheetView(s, ::on) }
    ui.dialog?.let { d ->
        AlertDialog(
            onDismissRequest = { on(StatesEvent.CloseDialog) },
            title = { Text(d.title) },
            text = d.text?.let { t -> { Text(t) } },
            // P62: Remove and Delete in red
            confirmButton = { ActionButton(d.confirm, ::on) },
            dismissButton = d.dismiss?.let { b -> { TextButton(onClick = { on(b.event) }) { Text(b.label) } } },
        )
    }
    ui.chooser?.let { c ->
        AlertDialog(
            onDismissRequest = { on(StatesEvent.CloseDialog) },
            title = { Text(c.title) },
            text = {
                Column {
                    c.rows.forEach { (code, name) ->
                        Text(name, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { on(StatesEvent.ChooseOne(code)) }.padding(vertical = 12.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { on(StatesEvent.CloseDialog) }) { Text(Texts.CANCEL) } },
        )
    }
}

/**
 * The screen under its sheets and dialogs: the top bar, the map card and the list, and the bar fixed
 * to the bottom (6.7). Apart from GetPacks, so the device tests (src/androidTest ReadingOrderTest,
 * StatesScreenLayoutTest) show it with the presenter's texts alone.
 *
 * P11 (audit F65): in portrait everything but the top bar and the bottom bar is one scrolling list (the
 * map card is one of its entries; the search field stays at the top once it gets there); in landscape
 * the top bar sits over the map in the left pane, so the list on the right keeps the whole height. The
 * keyboard no longer covers the list (imePadding; the app is edge to edge), and the bottom bar hides
 * while it is up. [landscape] and [imeVisible] are parameters so the device tests can choose them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatesBody(
    ui: StatesUi, shapes: StateShapes, on: (StatesEvent) -> Unit, onMapTap: (TapResult) -> Unit, modifier: Modifier = Modifier,
    landscape: Boolean = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
    imeVisible: Boolean = WindowInsets.isImeVisible,
) {
    // Hoisted (P11, P61): the map keeps its zoom while its list entry scrolls out and back, and is new on each visit.
    val view = rememberStatesMapView(shapes)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            if (landscape) {
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    // P11: the top bar over the map, so the right pane keeps the whole height (with the keyboard up too)
                    Column(Modifier.weight(0.45f).fillMaxHeight()) {
                        Header(ui.mode, ui.title, on) // P13
                        MapCard(shapes, ui, view, onMapTap, fixedRow = false, Modifier.weight(1f))
                    }
                    StatesList(ui, on, withMap = false, mapCard = {}, Modifier.weight(0.55f).fillMaxHeight())
                }
            } else {
                Header(ui.mode, ui.title, on) // P13
                val maxMap = (LocalConfiguration.current.screenHeightDp * 0.4f).dp
                StatesList(
                    ui, on, withMap = true,
                    mapCard = { MapCard(shapes, ui, view, onMapTap, fixedRow = true, Modifier.fillMaxWidth().heightIn(max = maxMap)) },
                    Modifier.fillMaxWidth().weight(1f),
                )
            }
            // P11: hidden while typing (its actions are not needed then; the rows need the height)
            if (!imeVisible) {
                HorizontalDivider()
                // The bar fixed to the bottom, read right after the top bar (6.7). Compose orders by
                // traversalIndex only among the members of one traversal group: a Row that is not a
                // group itself is dissolved into its buttons (index 0), and TalkBack reached them last,
                // after the list. As a group with index -1 it comes right after the top bar (-2).
                // A FlowRow (F65, F71): at a large font the second button goes under the first instead of squeezing both.
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).semantics { isTraversalGroup = true; traversalIndex = -1f },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ui.bottomBar.forEach { a -> TextButton(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
                }
            }
        }
    }
}

/** P13: the States screen's top bar, as Settings has (a Back arrow, then the title); the first-run screen keeps its title alone (Not now is its way out). */
@Composable
private fun Header(mode: StatesMode, title: String, on: (StatesEvent) -> Unit) {
    if (mode == StatesMode.FIRST_RUN) {
        Text(
            title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading(); traversalIndex = -2f },
        )
        return
    }
    // One traversal group read first: Back, then the title; the bar (-1) right after (6.7).
    Row(
        Modifier.fillMaxWidth().padding(4.dp).semantics { isTraversalGroup = true; traversalIndex = -2f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { on(StatesEvent.Back) }) { Icon(BeamlineIcons.Back, contentDescription = Texts.BACK) }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    }
}

@Composable
private fun MapCard(shapes: StateShapes, ui: StatesUi, view: StatesMapView, onMapTap: (TapResult) -> Unit, fixedRow: Boolean, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    Column(modifier.padding(horizontal = 16.dp)) {
        Card(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            StatesMap(
                shapes = shapes, status = ui.mapStatus, selected = ui.selected, onTap = onMapTap,
                // The map's own shape (1000 x 632 units plus the 16 dp margins), never taller than the card allows.
                modifier = Modifier.fillMaxWidth().aspectRatio(shapes.width / shapes.height * 0.94f),
                view = view,
                description = ui.mapDescription,
                // P25: a state picked on the map is outlined, not zoomed
                zoomToSelection = !ui.selectedOnMap,
            )
        }
        // Under the map, never over it (over it, it covered labels and outlines): the "Tap a state" hint and "Whole country".
        // P25: in portrait always there, at one height (the padding, then a chip's 48 dp touch size), so "Whole country"
        // or the hint appearing does not move the list; in landscape only when needed.
        if (fixedRow || ui.mapHint != null || view.zoomedIn) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.mapHint ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (view.zoomedIn) AssistChip(onClick = { scope.launch { view.wholeCountry() } }, label = { Text(Texts.WHOLE_COUNTRY) })
            }
        }
    }
}

/**
 * P11: the one scrolling list ([listEntries]: the intro, banners, progress, the map card in portrait,
 * the search field, the no-match line, the sections and the footer).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatesList(ui: StatesUi, on: (StatesEvent) -> Unit, withMap: Boolean, mapCard: @Composable () -> Unit, modifier: Modifier) {
    val entries = remember(ui, withMap) { listEntries(ui, withMap) }
    val list = rememberLazyListState()
    var searchFocused by remember { mutableStateOf(false) }
    var searchPx by remember { mutableIntStateOf(0) } // the pinned field's height (its bottom, when pinned)
    val searchAt = entries.indexOfFirst { it is ListEntry.Search }
    // P25 (F66): only in landscape (the map has its own pane) does the list follow a selection; in portrait the
    // map is in the list, and scrolling to the row moved the map away under the finger. By the list's own order,
    // only to a row that is off screen or under the pinned search field, and to just below that field.
    if (!withMap) LaunchedEffect(ui.selected) {
        val i = rowIndex(entries, ui.selected ?: return@LaunchedEffect)
        if (i < 0) return@LaunchedEffect
        val top = if (searchAt >= 0) snapshotFlow { searchPx }.first { it > 0 } else 0 // after the first layout
        val info = list.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == i }
        if (row == null || row.offset < top || row.offset + row.size > info.viewportEndOffset) {
            list.animateScrollToItem(i, scrollOffset = -top) // a negative offset: the row ends that far below the top
        }
    }
    // P11: a focused search goes to the top (it stays there: sticky), so the rows fill the rest above the keyboard
    LaunchedEffect(searchFocused, ui.query) { if (searchFocused && searchAt >= 0) list.animateScrollToItem(searchAt) }
    BoxWithConstraints(modifier) {
        // P11: while the field is focused, room after the last entry, so the field stays at the top however few
        // results there are ("zz": the line and the footer); without it the list clamps and the field slides down
        val tail = if (searchFocused) maxHeight else 0.dp
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = tail)) {
            for (e in entries) when (e) {
                is ListEntry.Line -> item(key = e.key) {
                    Text(
                        e.text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = if (e.small) MaterialTheme.typography.bodySmall else LocalTextStyle.current,
                    )
                }
                is ListEntry.Banner -> item(key = e.key) { Banner(e.banner.text, e.banner.detail, e.banner.action, e.banner.secondary, on) }
                is ListEntry.Progress -> item(key = e.key) { JobView(e.job, on) }
                ListEntry.Map -> item(key = e.key) { mapCard() }
                ListEntry.Search -> stickyHeader(key = e.key) {
                    Surface(Modifier.onSizeChanged { searchPx = it.height }, color = MaterialTheme.colorScheme.background) {
                        OutlinedTextField(
                            value = ui.query, onValueChange = { on(StatesEvent.Query(it)) }, singleLine = true,
                            // P64 (audit F71): a label, not only a placeholder: with words typed in, the field keeps its name for a screen reader
                            label = { Text(ui.searchHint ?: "") },
                            // P12: one tap empties the field (the place search has the same)
                            trailingIcon = if (ui.query.isEmpty()) null else ({
                                IconButton(onClick = { on(StatesEvent.Query("")) }) { ClearGlyph(Texts.CLEAR_TEXT) }
                            }),
                            // P64: no top padding: the floated label's room above the outline takes its place
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                                .onFocusChanged { searchFocused = it.isFocused },
                        )
                    }
                }
                // P12: the same Text node always (an empty one has no height and no words for a screen reader)
                is ListEntry.NoMatch -> item(key = e.key) {
                    Text(
                        e.text, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
                            .then(if (e.text.isEmpty()) Modifier.height(0.dp) else Modifier.padding(horizontal = 16.dp, vertical = 12.dp)),
                    )
                }
                is ListEntry.Title -> item(key = e.key) { SectionTitle(e.text, e.top) }
                is ListEntry.Row -> item(key = e.key) { RowView(e.row, on) }
                ListEntry.Footer -> item(key = e.key) {
                    Column(Modifier.padding(16.dp)) {
                        ui.checkedLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        ui.checkAgain?.let { a -> TextButton(onClick = { on(a.event) }) { Text(a.label) } }
                    }
                }
            }
        }
    }
}

/** P12: the field's Clear button: an X drawn as the place search draws its own; :ui:theme has no X icon. */
@Composable
private fun ClearGlyph(description: String) {
    val c = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(20.dp).semantics { contentDescription = description }) {
        val w = size.minDimension
        val a = w * 0.22f
        drawLine(c, Offset(a, a), Offset(w - a, w - a), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
        drawLine(c, Offset(w - a, a), Offset(a, w - a), strokeWidth = w * 0.11f, cap = StrokeCap.Round)
    }
}

/**
 * P62: "On this phone", "Add a state" and the first run's sections ([top]) as headings; the subsections
 * under "Add a state" smaller and grey, not the primary colour (on this screen primary text is what can be
 * tapped, so blue headings would read as links).
 */
@Composable
private fun SectionTitle(text: String, top: Boolean) {
    if (top) {
        Text(
            text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
        )
    } else {
        Text(
            text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

@Composable
private fun RowView(r: StateRowUi, on: (StatesEvent) -> Unit) {
    // P62: the theme's accent for the selected row, not Material's default lilac (BeamlineTheme sets no secondaryContainer)
    val bg = if (r.highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(MaterialTheme.colorScheme.background)
        else MaterialTheme.colorScheme.background
    Surface(color = bg) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { on(StatesEvent.Select(r.code)) }
                .semantics(mergeDescendants = true) { stateDescription = r.stateDescription }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(r.name, style = MaterialTheme.typography.bodyLarge)
            r.line?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            r.builtLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            // P62: the house-number action beside its line, not beside Remove
            r.houseNumbersLine?.let { HouseNumbersLine(it, r.houseNumbersAction, on) }
            r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // P62: Update at the start, Remove alone at the end in red
            if (r.actions.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    r.actions.filterNot { it.destructive }.forEach { ActionButton(it, on) }
                    Spacer(Modifier.weight(1f))
                    r.actions.filter { it.destructive }.forEach { ActionButton(it, on) }
                }
            }
        }
    }
}

/**
 * P62: the red of a destructive action: the same as Saved places' Remove and its confirm button (P58), the
 * search palette's `problem` (feature/search SearchViews.kt). BeamlineTheme sets no `error`, and
 * Material's default is #B3261E by day and a pale pink #F2B8B5 by night. Night as SearchViews' isNight().
 */
@Composable
private fun dangerColor(): Color = if (MaterialTheme.colorScheme.background.luminance() < 0.3f) Color(0xFFFF8A80) else Color(0xFFC62828)

/** P62: a text action; a destructive one (Remove, Delete) in [dangerColor]. */
@Composable
private fun ActionButton(a: Action, on: (StatesEvent) -> Unit) {
    TextButton(
        onClick = { on(a.event) }, enabled = a.enabled,
        colors = if (a.destructive) ButtonDefaults.textButtonColors(contentColor = dangerColor()) else ButtonDefaults.textButtonColors(),
    ) { Text(a.label) }
}

/** P62/F71: "House numbers: included" with its action beside it; at a large font the button wraps under the line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HouseNumbersLine(line: String, action: Action?, on: (StatesEvent) -> Unit) {
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
        Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        action?.let { ActionButton(it, on) }
    }
}

@Composable
private fun Banner(text: String, detail: String?, action: Action?, secondary: Action?, on: (StatesEvent) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(text)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (action != null || secondary != null) {
                Row {
                    action?.let { a -> TextButton(onClick = { on(a.event) }) { Text(a.label) } }
                    secondary?.let { a -> TextButton(onClick = { on(a.event) }) { Text(a.label) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobView(j: JobUi, on: (StatesEvent) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(j.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            j.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (j.bar) {
                if (j.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp))
                else LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            Text(j.line)
            j.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            j.keepOpenLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            j.notificationsLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            // F65, F71: Pause, Cancel and Turn on wrap instead of being clipped at a large font
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                j.actions.forEach { a -> TextButton(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
                j.notificationsAction?.let { a -> TextButton(onClick = { on(a.event) }) { Text(a.label) } }
            }
        }
    }
}

/** A state's sheet or the file sheet (1.1 step 4, 1.2, 1.4); internal for the device tests (P14). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SheetView(s: SheetUi, on: (StatesEvent) -> Unit) {
    // Opened fully expanded: half expanded, its lower part (the decision buttons on mobile data, the
    // credit and privacy lines that 1.1 step 4 always shows) started under the navigation bar. The
    // content scrolls when it is taller than the screen (a large font).
    ModalBottomSheet(onDismissRequest = { on(StatesEvent.CloseSheet) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp).semantics { paneTitle = s.title },
        ) {
            Text(s.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            s.lines.forEach { Text(it, Modifier.padding(vertical = 2.dp)) }
            // P14/P62: a state on this phone: the house-number line with its action beside it, as on the row
            s.houseNumbersLine?.let { HouseNumbersLine(it, s.houseNumbersAction, on) }
            s.spaceLine?.let { Text(it, Modifier.padding(vertical = 2.dp)) }
            s.houseNumbers?.let { h -> HouseNumbersRow(h, on) }
            s.connectionLine?.let { Text(it, Modifier.padding(vertical = 4.dp)) }
            // P14/P62: [primary] [other actions] [secondary] at the start; a destructive action (Remove) alone at the far end,
            // in red, never beside Close or "Remove house numbers" (the mis-tap the play test saw on the row)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.primary?.let { a -> Button(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
                    s.actions.filterNot { it.destructive }.forEach { a -> ActionButton(a, on) } // P14, P25
                    s.secondary?.let { a -> TextButton(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
                }
                s.actions.filter { it.destructive }.forEach { a ->
                    Spacer(Modifier.width(24.dp))
                    ActionButton(a, on)
                }
            }
            s.creditLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            s.privacyLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
        }
    }
}

/** The house-numbers choice of a download sheet: one toggleable row, so the box is read with its name (audit F71, play test P64). */
@Composable
internal fun HouseNumbersRow(h: HouseNumbersUi, on: (StatesEvent) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = h.checked, role = Role.Checkbox, onValueChange = { on(StatesEvent.HouseNumbers(it)) }).padding(vertical = 4.dp)) {
        // without its own onCheckedChange the box has no 48 dp touch size: keep its place in the row
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Checkbox(checked = h.checked, onCheckedChange = null) }
        Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
            Text(h.label, fontWeight = FontWeight.SemiBold)
            Text(h.hint)
            h.sourcesLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
