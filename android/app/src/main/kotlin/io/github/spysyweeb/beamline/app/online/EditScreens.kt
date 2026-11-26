package io.github.spysyweeb.beamline.app.online

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons

/** The screen frame of both forms: Back (with the discard question when [dirty]), the title and a subtitle. */
@Composable
private fun FormFrame(title: String, subtitle: String?, dirty: Boolean, onBack: () -> Unit, tag: String, content: @Composable ColumnScope.() -> Unit) {
    var asking by remember { mutableStateOf(false) }
    val back = { if (dirty) asking = true else onBack() }
    BackHandler { back() }
    Surface(Modifier.fillMaxSize().testTag(tag), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(BeamlineIcons.Back, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            content()
        }
    }
    if (asking) DiscardDialog(onDiscard = { asking = false; onBack() }, onKeep = { asking = false })
}

@Composable
private fun Busy(text: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Result(text: String, link: String?, linkLabel: String, onOpenLink: (String) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("edit-result"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
        link?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { onOpenLink(it) }) { Text(linkLabel) }
        }
        Button(onClick = onClose) { Text(OnlineTexts.CLOSE) }
    }
}

@Composable
private fun SummaryView(lines: List<String>, says: String?, nothing: Boolean, onBack: () -> Unit, onUpload: () -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (nothing) Text(OnlineTexts.NOTHING_CHANGED, style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.testTag("edit-summary"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
        HorizontalDivider()
        if (says != null) Text(says, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.BACK_TO_FORM) }
            Spacer(Modifier.weight(1f))
            Button(onClick = onUpload, enabled = !nothing, modifier = Modifier.heightIn(min = 48.dp).testTag("edit-upload")) { Text(OnlineTexts.UPLOAD) }
        }
    }
}

/** A box with its words: the whole row is the tap target (48 dp at least). */
@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, text: String, tag: String, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * "Edit on OpenStreetMap" (batch O2, docs/online-design.md 4.2; batch O3: the friendlier form of item 24
 * and "This is now a different business" of item 25): the form, the summary with Upload, and what came of it.
 */
@Composable
fun EditPlaceScreen(
    s: EditSession,
    onUpload: () -> Unit,
    onOpenLink: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var typePicker by remember { mutableStateOf(false) }
    FormFrame(
        if (s.phase == EditPhase.SUMMARY) OnlineTexts.SUMMARY_TITLE else OnlineTexts.EDIT_TITLE, s.target.name, s.dirty && s.phase == EditPhase.FORM,
        onBack = { if (s.phase == EditPhase.SUMMARY) s.backToForm() else onClose() }, tag = "edit-place",
    ) {
        when (s.phase) {
            EditPhase.LOADING -> Busy(OnlineTexts.EDIT_LOADING)
            EditPhase.UPLOADING -> Busy(OnlineTexts.UPLOADING)
            EditPhase.FORM -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s.notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("edit-notice")) }
                if (s.nowOnOsm.isNotEmpty()) {
                    Text(OnlineTexts.NOW_ON_OSM, style = MaterialTheme.typography.titleSmall)
                    s.nowOnOsm.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
                // item 25: a new business in the old one's place
                if (!s.closed) {
                    CheckRow(s.differentBusiness, s.canClose || s.baseTags.isNotEmpty(), OnlineTexts.DIFFERENT_BUSINESS, "edit-different") {
                        s.switchDifferentBusiness(!s.differentBusiness)
                    }
                    if (s.differentBusiness) Text(OnlineTexts.DIFFERENT_BUSINESS_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!s.closed) FormFieldsView(s.f, withType = s.differentBusiness, nameLabel = if (s.differentBusiness) OnlineTexts.NEW_NAME else OnlineTexts.NAME) { typePicker = true }
                if (!s.differentBusiness) {
                    CheckRow(s.closed, s.canClose, OnlineTexts.CLOSED_PERMANENTLY, "edit-closed") { s.closed = !s.closed }
                    Text(if (s.canClose) OnlineTexts.CLOSED_TEXT else OnlineTexts.CLOSED_OFFLINE, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                s.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("form-problem")) }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.isItem) OutlinedButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.DELETE) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { s.summary() }, modifier = Modifier.heightIn(min = 48.dp).testTag("edit-next")) { Text(OnlineTexts.NEXT) }
                }
                Spacer(Modifier.size(24.dp))
            }
            EditPhase.SUMMARY -> SummaryView(s.lines, if (s.hasChanges) OnlineTexts.summarySays(s.comment) else null, !s.hasChanges,
                onBack = { s.backToForm() }, onUpload = onUpload, modifier = Modifier.weight(1f))
            EditPhase.DONE -> Result(OnlineTexts.DONE, s.link, OnlineTexts.OPEN_CHANGE, onOpenLink, onClose)
            EditPhase.WAITING -> Result(OnlineTexts.EDIT_WAITING, null, "", onOpenLink, onClose)
            EditPhase.REFUSED -> Result(OnlineTexts.editRefused(s.status), null, "", onOpenLink, onClose)
        }
    }
    if (typePicker) TypePickerDialog(s.f.type?.code, onPick = { s.f.type = it; typePicker = false }, onDismiss = { typePicker = false })
}

/**
 * "Add a missing place" (batch O3, docs/online-design.md 4.3; item 25 b): the spot's line "Already on the
 * map here" with its three answers, the form (type, name, hours, phone, website), the duplicate warning,
 * the summary with Upload, and what came of it.
 */
@Composable
fun AddPlaceScreen(
    s: AddSession,
    onUpload: () -> Unit,
    onSame: (NearbyPlace) -> Unit,
    onOpenLink: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var typePicker by remember { mutableStateOf(false) }
    val title = when (s.phase) {
        AddPhase.SUMMARY -> OnlineTexts.SUMMARY_TITLE
        AddPhase.DUPLICATES -> OnlineTexts.DUPLICATE_TITLE
        else -> OnlineTexts.ADD_TITLE
    }
    FormFrame(title, null, s.dirty && s.phase == AddPhase.FORM,
        onBack = { if (s.phase == AddPhase.SUMMARY || s.phase == AddPhase.DUPLICATES) s.backToForm() else onClose() }, tag = "add-place") {
        when (s.phase) {
            AddPhase.CHECKING -> Busy(OnlineTexts.ADD_CHECKING)
            AddPhase.UPLOADING -> Busy(OnlineTexts.UPLOADING)
            AddPhase.FORM -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HereBlock(s)
                FormFieldsView(s.f, withType = true, nameLabel = OnlineTexts.NAME, marks = false) { typePicker = true }
                s.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("form-problem")) }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.isItem) OutlinedButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.DELETE) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { s.next(onSame) }, modifier = Modifier.heightIn(min = 48.dp).testTag("add-next")) { Text(OnlineTexts.NEXT) }
                }
                Spacer(Modifier.size(24.dp))
            }
            AddPhase.DUPLICATES -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).testTag("add-duplicates"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                s.duplicates.forEach { d ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(OnlineTexts.duplicateLine(d), style = MaterialTheme.typography.bodyLarge)
                            if (d.osm != null && OsmRef.parse(d.osm)?.type != OsmType.RELATION) {
                                TextButton(onClick = { onSame(d) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.EDIT_THAT) }
                            }
                        }
                    }
                }
                Button(onClick = { s.notDuplicates() }, modifier = Modifier.heightIn(min = 48.dp).testTag("add-not-duplicate")) { Text(OnlineTexts.NOT_DUPLICATE) }
            }
            AddPhase.SUMMARY -> SummaryView(s.lines, OnlineTexts.summarySays(s.commentPreview()), false,
                onBack = { s.backToForm() }, onUpload = onUpload, modifier = Modifier.weight(1f))
            AddPhase.DONE -> Result(OnlineTexts.ADD_DONE, s.link, OnlineTexts.OPEN_ADDED, onOpenLink, onClose)
            AddPhase.WAITING -> Result(OnlineTexts.ADD_WAITING, null, "", onOpenLink, onClose)
            AddPhase.REFUSED -> Result(OnlineTexts.editRefused(s.status), null, "", onOpenLink, onClose)
        }
    }
    if (typePicker) TypePickerDialog(s.f.type?.code, onPick = { s.f.type = it; typePicker = false }, onDismiss = { typePicker = false })
}

/** The spot's line (item 25 b): checking, offline, the business(es) already here with the three answers, or an address alone. */
@Composable
private fun HereBlock(s: AddSession) {
    if (s.checking) Text(OnlineTexts.ADD_CHECKING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else if (!s.osmRead) Text(OnlineTexts.ADD_OFFLINE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    val here = s.here
    if (here.isNotEmpty()) {
        OutlinedCard(Modifier.fillMaxWidth().testTag("add-here")) {
            Column(Modifier.padding(12.dp)) {
                if (here.size == 1) {
                    Text(OnlineTexts.hereLine(here[0]), style = MaterialTheme.typography.titleSmall)
                } else {
                    Text(OnlineTexts.HERE_SEVERAL, style = MaterialTheme.typography.titleSmall)
                    here.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(role = Role.RadioButton) { s.hereFor = p }) {
                            RadioButton(selected = s.hereFor == p, onClick = null)
                            Text(p.line(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                for ((c, label, text) in listOf(
                    Triple(HereChoice.REPLACED, OnlineTexts.HERE_REPLACED, OnlineTexts.HERE_REPLACED_TEXT),
                    Triple(HereChoice.BOTH, OnlineTexts.HERE_BOTH, OnlineTexts.HERE_BOTH_TEXT),
                    Triple(HereChoice.SAME, OnlineTexts.HERE_SAME, OnlineTexts.HERE_SAME_TEXT),
                )) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .clickable(role = Role.RadioButton) { s.choice = c }.testTag("here-" + c.name.lowercase())) {
                        RadioButton(selected = s.choice == c, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    } else s.bare?.let { b ->
        Text(OnlineTexts.bareLine(b), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("add-bare"))
    }
}

/** The public-edit notice before the first upload (section 3): Upload or Not now. */
@Composable
fun EditNoticeDialog(onAnswer: (upload: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(OnlineTexts.EDIT_NOTICE_TITLE) },
        text = { Text(OnlineTexts.EDIT_NOTICE_TEXT) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(OnlineTexts.UPLOAD) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(OnlineTexts.NOT_NOW) } },
    )
}

/** Signed out, "Edit on OpenStreetMap" or "Add a missing place" (answer 6; batch O3): sign in, or send a note instead. */
@Composable
fun SignInNeededDialog(onSignIn: () -> Unit, onNote: () -> Unit, onCancel: () -> Unit, adding: Boolean = false) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (adding) OnlineTexts.SIGN_IN_ADD_TITLE else OnlineTexts.SIGN_IN_NEEDED_TITLE) },
        text = { Text(if (adding) OnlineTexts.SIGN_IN_ADD else OnlineTexts.SIGN_IN_NEEDED) },
        confirmButton = { TextButton(onClick = onSignIn) { Text(OnlineTexts.SIGN_IN) } },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text(OnlineTexts.CANCEL) }
                TextButton(onClick = onNote) { Text(OnlineTexts.SEND_NOTE_INSTEAD) }
            }
        },
    )
}

/**
 * The speed limit of a road (docs/next-round.md item 32, docs/online-design.md 4.7): the road OSM has at
 * the point and its limit now, the posted limits as large buttons, the summary with Upload, and what
 * came of it.
 */
@Composable
fun SpeedLimitScreen(
    s: SpeedSession,
    onUpload: () -> Unit,
    onOpenLink: (String) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    FormFrame(
        if (s.phase == SpeedPhase.SUMMARY) OnlineTexts.SUMMARY_TITLE else OnlineTexts.SPEED_TITLE, s.road?.name ?: s.roadName, s.dirty && s.phase == SpeedPhase.PICK,
        onBack = { if (s.phase == SpeedPhase.SUMMARY) s.backToPick() else onClose() }, tag = "speed-limit",
    ) {
        when (s.phase) {
            SpeedPhase.LOADING -> Busy(OnlineTexts.SPEED_LOADING)
            SpeedPhase.UPLOADING -> Busy(OnlineTexts.UPLOADING)
            SpeedPhase.NO_ROAD, SpeedPhase.OFFLINE -> Column(Modifier.fillMaxWidth().padding(16.dp).testTag("speed-none"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (s.phase == SpeedPhase.OFFLINE) OnlineTexts.SPEED_OFFLINE else OnlineTexts.SPEED_NO_ROAD, style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.isItem) OutlinedButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.DELETE) }
                    if (s.phase == SpeedPhase.OFFLINE) OutlinedButton(onClick = { s.load() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.SPEED_TRY_AGAIN) }
                    Button(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.CLOSE) }
                }
            }
            SpeedPhase.PICK -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val r = s.road
                if (r != null) {
                    Text("${OnlineTexts.SPEED_ROAD}: ${r.name ?: "(no name)"}", style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        modifier = Modifier.testTag("speed-road"))
                    Text("${OnlineTexts.SPEED_NOW}: ${s.current}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("speed-now"))
                }
                Text(OnlineTexts.SPEED_POSTED, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
                SpeedChoices(s.mph) { s.pick(it) }
                Text(OnlineTexts.SPEED_SAYS, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("speed-notice")) }
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s.isItem) OutlinedButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.DELETE) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { s.summary() }, modifier = Modifier.heightIn(min = 48.dp).testTag("speed-next")) { Text(OnlineTexts.NEXT) }
                }
            }
            SpeedPhase.SUMMARY -> SummaryView(s.lines, OnlineTexts.summarySays(SpeedLimits.comment(s.road?.name, s.mph ?: 0)), false,
                onBack = { s.backToPick() }, onUpload = onUpload, modifier = Modifier.weight(1f))
            SpeedPhase.DONE -> Result(OnlineTexts.SPEED_DONE, s.link, OnlineTexts.OPEN_CHANGE, onOpenLink, onClose)
            SpeedPhase.WAITING -> Result(OnlineTexts.EDIT_WAITING, null, "", onOpenLink, onClose)
            SpeedPhase.REFUSED -> Result(OnlineTexts.editRefused(s.status), null, "", onOpenLink, onClose)
        }
    }
}

/**
 * The posted limits as large buttons, [perRow] to a row (four on the screen, three in the narrower
 * dialog); the chosen one filled. A number never wraps: the buttons keep no padding of their own.
 */
@Composable
private fun SpeedChoices(chosen: Int?, perRow: Int = 4, onPick: (Int) -> Unit) {
    val none = androidx.compose.foundation.layout.PaddingValues(0.dp)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in SpeedLimits.CHOICES.chunked(perRow)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (v in row) {
                    val m = Modifier.weight(1f).heightIn(min = 64.dp).testTag("speed-$v")
                        .semantics { contentDescription = "$v miles per hour"; selected = v == chosen }
                    if (v == chosen) Button(onClick = { onPick(v) }, modifier = m, contentPadding = none) { Text("$v", style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false) }
                    else OutlinedButton(onClick = { onPick(v) }, modifier = m, contentPadding = none) { Text("$v", style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false) }
                }
            }
        }
    }
}

/**
 * Item 32, while driving: a tap on the speed sign opens this. One tap on the posted limit keeps it in
 * My reports with the place; nothing is read from or sent to OpenStreetMap until the driver has stopped
 * and opened it there.
 */
@Composable
fun SpeedPickDialog(onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    // item 47: a small sheet from the bottom, as the other pickers
    PickerSheet(OnlineTexts.SPEED_PICK_TITLE, onDismiss) {
        Column(Modifier.testTag("speed-pick"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(OnlineTexts.SPEED_PICK_TEXT, style = MaterialTheme.typography.bodyMedium)
            SpeedChoices(null, perRow = 3, onPick = onPick)
        }
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) { Text(OnlineTexts.SPEED_PICK_CANCEL) }
    }
}
