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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
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
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.StartResult
import io.github.spysyweeb.beamline.feature.states.map.StateShapes
import io.github.spysyweeb.beamline.feature.states.map.StatesMap
import io.github.spysyweeb.beamline.feature.states.map.TapResult
import io.github.spysyweeb.beamline.feature.states.map.rememberStatesMapView
import io.github.spysyweeb.beamline.feature.states.model.Action
import io.github.spysyweeb.beamline.feature.states.model.Dialog
import io.github.spysyweeb.beamline.feature.states.model.JobUi
import io.github.spysyweeb.beamline.feature.states.model.SheetKind
import io.github.spysyweeb.beamline.feature.states.model.SheetUi
import io.github.spysyweeb.beamline.feature.states.model.StateRowUi
import io.github.spysyweeb.beamline.feature.states.model.StatesEvent
import io.github.spysyweeb.beamline.feature.states.model.StatesPresenter
import io.github.spysyweeb.beamline.feature.states.model.StatesUi
import io.github.spysyweeb.beamline.feature.states.model.Texts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** StatesScreen (6.9): "Choose your state" (FIRST_RUN) or "States" (MANAGE), from StatesPresenter's texts. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatesScreenImpl(getPacks: GetPacks, mode: StatesMode, onClose: () -> Unit, modifier: Modifier) {
    val core = getPacks.core
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
            is StatesEvent.Select -> core.select(e.code)
            is StatesEvent.ChooseOne -> core.select(e.code)
            StatesEvent.CloseSheet -> core.closeSheet()
            is StatesEvent.Query -> core.screen.update { it.copy(query = e.text) }
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
        // The first frame, before the list and the map are ready: the title and the intro at once.
        Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Text(
                    if (mode == StatesMode.FIRST_RUN) Texts.CHOOSE_TITLE else Texts.STATES_TITLE,
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading() },
                )
                if (mode == StatesMode.FIRST_RUN) Text(Texts.CHOOSE_INTRO, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }
        return
    }
    StatesBody(
        ui, core.shapes, ::on,
        onMapTap = { r ->
            when (r) {
                is TapResult.One -> on(StatesEvent.Select(r.code))
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
            confirmButton = { TextButton(onClick = { on(d.confirm.event) }) { Text(d.confirm.label) } },
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
 * The screen under its sheets and dialogs: the title, the map card and the list, and the bar fixed
 * to the bottom (6.7). Apart from GetPacks, so the device test of the reading order
 * (src/androidTest ReadingOrderTest) shows it with the presenter's texts alone.
 */
@Composable
internal fun StatesBody(ui: StatesUi, shapes: StateShapes, on: (StatesEvent) -> Unit, onMapTap: (TapResult) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Text(
                ui.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).semantics { heading(); traversalIndex = -2f },
            )
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            Box(Modifier.weight(1f)) {
                if (landscape) {
                    Row(Modifier.fillMaxSize()) {
                        MapCard(shapes, ui, on, onMapTap, Modifier.weight(1f).fillMaxHeight())
                        ListPart(ui, on, Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        ListPart(ui, on, Modifier.weight(1f), mapCard = { MapCard(shapes, ui, on, onMapTap, Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.4f).dp)) })
                    }
                }
            }
            // The bar fixed to the bottom, read right after the title (6.7). Compose orders by
            // traversalIndex only among the members of one traversal group: a Row that is not a
            // group itself is dissolved into its buttons (index 0), and TalkBack reached them last,
            // after the list. As a group with index -1 it comes right after the title (-2).
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).semantics { isTraversalGroup = true; traversalIndex = -1f },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ui.bottomBar.forEach { a -> TextButton(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
            }
        }
    }
}

@Composable
private fun MapCard(shapes: StateShapes, ui: StatesUi, on: (StatesEvent) -> Unit, onMapTap: (TapResult) -> Unit, modifier: Modifier) {
    val view = rememberStatesMapView(shapes)
    val scope = rememberCoroutineScope()
    Column(modifier.padding(horizontal = 16.dp)) {
        Card(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            StatesMap(
                shapes = shapes, status = ui.mapStatus, selected = ui.selected, onTap = onMapTap,
                // The map's own shape (1000 x 632 units plus the 16 dp margins), never taller than the card allows.
                modifier = Modifier.fillMaxWidth().aspectRatio(shapes.width / shapes.height * 0.94f),
                view = view,
                description = ui.mapDescription,
            )
        }
        // Under the map, never over it (over it, it covered labels and outlines): the "Tap a state" hint and "Whole country".
        if (ui.mapHint != null || view.zoomedIn) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(ui.mapHint ?: "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (view.zoomedIn) AssistChip(onClick = { scope.launch { view.wholeCountry() } }, label = { Text(Texts.WHOLE_COUNTRY) })
            }
        }
    }
}

@Composable
private fun ListPart(ui: StatesUi, on: (StatesEvent) -> Unit, modifier: Modifier, mapCard: (@Composable () -> Unit)? = null) {
    val list = rememberLazyListState()
    LaunchedEffect(ui.selected) {
        val code = ui.selected ?: return@LaunchedEffect
        val rows = ui.onPhone + ui.available + ui.notAvailable + ui.needsNewer
        val idx = rows.indexOfFirst { it.code == code }
        if (idx >= 0) list.animateScrollToItem(idx)
    }
    Column(modifier) {
        ui.intro?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        ui.loadingLine?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        ui.banner?.let { b -> Banner(b.text, b.detail, b.action, b.secondary, on) }
        ui.readyBanner?.let { b -> Banner(b.text, b.detail, b.action, b.secondary, on) }
        ui.job?.let { JobView(it, on) }
        ui.fileProgress?.let { JobView(it, on) }
        mapCard?.invoke()
        ui.searchHint?.let { hint ->
            OutlinedTextField(
                value = ui.query, onValueChange = { on(StatesEvent.Query(it)) }, singleLine = true,
                placeholder = { Text(hint) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list) {
            section(ui.onPhoneTitle, ui.onPhone, on)
            ui.addTitle?.let { t -> item { SectionTitle(t) } }
            section(ui.availableTitle, ui.available, on)
            section(ui.notAvailableTitle, ui.notAvailable, on)
            section(ui.needsNewerTitle, ui.needsNewer, on)
            item {
                Column(Modifier.padding(16.dp)) {
                    ui.checkedLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    ui.checkAgain?.let { a -> TextButton(onClick = { on(a.event) }) { Text(a.label) } }
                }
            }
        }
    }
}

private fun LazyListScope.section(title: String?, rows: List<StateRowUi>, on: (StatesEvent) -> Unit) {
    title?.let { t -> item(key = "title-$t") { SectionTitle(t) } }
    items(rows, key = { "${title}-${it.code}" }) { r -> RowView(r, on) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() })
}

@Composable
private fun RowView(r: StateRowUi, on: (StatesEvent) -> Unit) {
    val bg = if (r.highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background
    Surface(color = bg) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { on(StatesEvent.Select(r.code)) }
                .semantics(mergeDescendants = true) { stateDescription = r.stateDescription }
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(r.name, style = MaterialTheme.typography.bodyLarge)
            r.line?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            r.builtLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            r.houseNumbersLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            r.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (r.actions.isNotEmpty() || r.houseNumbersAction != null) {
                Row {
                    r.actions.forEach { a -> TextButton(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
                    r.houseNumbersAction?.let { a -> TextButton(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
                }
            }
        }
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
            Row {
                j.actions.forEach { a -> TextButton(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
                j.notificationsAction?.let { a -> TextButton(onClick = { on(a.event) }) { Text(a.label) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetView(s: SheetUi, on: (StatesEvent) -> Unit) {
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
            s.spaceLine?.let { Text(it, Modifier.padding(vertical = 2.dp)) }
            s.houseNumbers?.let { h ->
                Row(Modifier.fillMaxWidth().clickable { on(StatesEvent.HouseNumbers(!h.checked)) }.padding(vertical = 4.dp)) {
                    Checkbox(checked = h.checked, onCheckedChange = { on(StatesEvent.HouseNumbers(it)) })
                    Column(Modifier.padding(start = 4.dp, top = 12.dp)) {
                        Text(h.label, fontWeight = FontWeight.SemiBold)
                        Text(h.hint)
                        h.sourcesLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            s.connectionLine?.let { Text(it, Modifier.padding(vertical = 4.dp)) }
            Row(Modifier.padding(vertical = 8.dp)) {
                s.primary?.let { a -> Button(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
                s.secondary?.let { a -> TextButton(onClick = { on(a.event) }, enabled = a.enabled) { Text(a.label) } }
            }
            s.creditLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            s.privacyLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 16.dp)) }
        }
    }
}
