package io.github.spysyweeb.beamline.app.online

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.feature.search.HoursPreview
import io.github.spysyweeb.beamline.feature.search.PlaceTypeGrid
import io.github.spysyweeb.beamline.feature.search.PlaceTypeGroupUi
import io.github.spysyweeb.beamline.feature.search.PlaceTypeUi
import io.github.spysyweeb.beamline.feature.search.placeTypeIcon
import io.github.spysyweeb.beamline.app.ui.SheetWindowBars
import java.util.Locale

private val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val DAY_LONG = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** A time of day in the phone's 12- or 24-hour setting ("3:00 PM" on a US phone, "15:00" otherwise). */
fun clockText(minutes: Int, h24: Boolean): String {
    val m = ((minutes % 1440) + 1440) % 1440
    val h = m / 60
    val mm = m % 60
    if (h24) return String.format(Locale.ROOT, "%02d:%02d", h, mm)
    val h12 = if (h % 12 == 0) 12 else h % 12
    return String.format(Locale.ROOT, "%d:%02d %s", h12, mm, if (h < 12) "AM" else "PM")
}

/** "Mon-Thu", "Sat, Sun", "Mon, Wed, Fri": the days of a group in words. */
fun daysText(days: List<Int>): String {
    val parts = ArrayList<String>()
    var a = 0
    while (a < days.size) {
        var b = a
        while (b + 1 < days.size && days[b + 1] == days[b] + 1) b++
        parts += if (b - a >= 2) DAY_SHORT[days[a]] + "–" + DAY_SHORT[days[b]] else (a..b).joinToString(", ") { DAY_SHORT[days[it]] }
        a = b + 1
    }
    return parts.joinToString(", ")
}

/** The addable types as the grid shows them, under the search's headings (one component, [PlaceTypeGrid]). */
fun addableGroups(): List<PlaceTypeGroupUi> {
    val heading = mapOf(
        "road" to "On the road", "car" to "For the car", "food" to "Food and drink", "shop" to "Shops", "money" to "Money",
        "health" to "Health", "lodging" to "Places to stay", "services" to "Public services", "outdoors" to "Outdoors",
        "fun" to "Things to do", "community" to "Community",
    )
    return PlaceTypes.ADDABLE.groupBy { it.group }.map { (g, list) ->
        PlaceTypeGroupUi(heading[g] ?: "More", list.map { PlaceTypeUi(it.code, it.label, placeTypeIcon(it.code) ?: "building", it.label.lowercase()) })
    }
}

/** "Changed" and its Undo under a field (item 24 e). */
@Composable
fun ChangedMark(changed: Boolean, what: String, onUndo: () -> Unit) {
    if (!changed) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Text(OnlineTexts.CHANGED, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "${OnlineTexts.UNDO} $what" }) {
            Text(OnlineTexts.UNDO)
        }
    }
}

/** A field's plain problem under it ("A phone number has 10 digits ..."). */
@Composable
private fun FieldProblem(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
}

/**
 * The one form's fields (batch O3, item 24): the type (when [withType]), the name, the hours editor,
 * phone and website, each with "Changed" and Undo; phone and website checked as typed.
 * 0.1.41 (docs/online-design.md 4.8, AP9): opened at a field ([focus]): the hours' heading brought into view, or the
 * phone or website field focused with the keyboard up; [onFocused] once that is done (the form does it once).
 */
@Composable
fun FormFieldsView(
    f: FormFields, withType: Boolean, nameLabel: String, marks: Boolean = true,
    focus: EditFocus? = null, onFocused: () -> Unit = {}, onPickType: () -> Unit,
) {
    val hoursAt = remember { BringIntoViewRequester() }
    val phoneAt = remember { BringIntoViewRequester() }
    val websiteAt = remember { BringIntoViewRequester() }
    val phoneFocus = remember { FocusRequester() }
    val websiteFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focused by rememberUpdatedState(onFocused)
    LaunchedEffect(focus) {
        if (focus == null) return@LaunchedEffect
        withFrameNanos { }   // laid out: the fields can be focused and brought into view
        when (focus) {
            EditFocus.HOURS -> hoursAt.bringIntoView()
            EditFocus.PHONE -> {
                phoneFocus.requestFocus()
                keyboard?.show()
                phoneAt.bringIntoView()
            }
            EditFocus.WEBSITE -> {
                websiteFocus.requestFocus()
                keyboard?.show()
                websiteAt.bringIntoView()
            }
        }
        focused()
    }
    if (withType) {
        Text(OnlineTexts.TYPE, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
        val t = f.type
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = OnlineTexts.CHOOSE_TYPE, onClick = onPickType).testTag("form-type")) {
            if (t != null) {
                Text(t.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(OnlineTexts.CHANGE_TYPE, color = MaterialTheme.colorScheme.primary)
            } else {
                Text(OnlineTexts.CHOOSE_TYPE, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        ChangedMark(marks && f.typeChanged && f.start.typeCode != null, OnlineTexts.TYPE) { f.undoType() }
    }
    OutlinedTextField(f.name, { f.name = it }, label = { Text(nameLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("edit-name"))
    ChangedMark(marks && f.nameChanged && !f.fresh, OnlineTexts.NAME) { f.undoName() }
    HoursEditor(f, marks, heading = Modifier.bringIntoViewRequester(hoursAt))
    OutlinedTextField(
        f.phone, { f.phone = it }, label = { Text(OnlineTexts.PHONE) }, singleLine = true, isError = f.phoneProblem != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(phoneAt).focusRequester(phoneFocus).testTag("edit-phone"),
    )
    FieldProblem(f.phoneProblem)
    // item 38: what the number will be written as, when that is not what was typed
    f.phoneNote?.let {
        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp).testTag("edit-phone-note"))
    }
    ChangedMark(marks && f.phoneChanged && !f.fresh, OnlineTexts.PHONE) { f.undoPhone() }
    OutlinedTextField(
        f.website, { f.website = it }, label = { Text(OnlineTexts.WEBSITE) }, singleLine = true, isError = f.websiteProblem != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(websiteAt).focusRequester(websiteFocus).testTag("edit-website"),
    )
    FieldProblem(f.websiteProblem)
    ChangedMark(marks && f.websiteChanged && !f.fresh, OnlineTexts.WEBSITE) { f.undoWebsite() }
}

/** Which time a picker is open for: a group's range ([group] >= 0) or the holidays' ([group] -1); [opens] or closes. */
private data class PickFor(val group: Int, val range: Int, val opens: Boolean)

/**
 * The hours editor (batch O3, item 24 a-d, f): the plain-words preview at the top (as the place sheet
 * shows the week), day groups with day chips, times picked with the platform's time picker in the
 * phone's 12/24-hour setting, "+ Add a second time", "Open 24 hours", "(next day)", "+ Add more days",
 * the days left closed, the public holidays line, and the fold-out "As OpenStreetMap stores it".
 */
@Composable
fun HoursEditor(f: FormFields, marks: Boolean = true, heading: Modifier = Modifier) {
    // 0.1.41 (AP9): [heading] on the first set's title (the form opened at the hours brings it into view), tag `edit-hours`
    HoursBlock(OnlineTexts.HOURS, f.hours, { f.hours = it }, f.hoursReadOnly, marks && f.hoursChanged && !f.fresh, { f.undoHours() }, tag = "",
        titleModifier = heading.testTag("edit-hours"))
    // item 33: a second set of hours (the office, the drive-through, the kitchen)
    val more = f.more
    if (more == null) {
        var choosing by remember { mutableStateOf(false) }
        TextButton(onClick = { choosing = !choosing }, modifier = Modifier.heightIn(min = 48.dp).testTag("hours-more-add")) { Text(OnlineTexts.MORE_HOURS_ADD) }
        if (choosing) {
            Text(OnlineTexts.MORE_HOURS_TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (kind in MoreHours.entries) {
                OutlinedButton(onClick = { f.addMore(kind); choosing = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hours-more-${kind.name.lowercase()}")) {
                    Text(kind.label)
                }
            }
        }
        ChangedMark(marks && f.moreChanged && !f.fresh, OnlineTexts.MORE_HOURS_REMOVE) { f.undoMore() }
    } else {
        HoursBlock(more.label, f.hours2, { f.hours2 = it }, f.hours2ReadOnly, marks && f.moreChanged && !f.fresh, { f.undoMore() }, tag = "-2")
        TextButton(onClick = { f.removeMore() }, modifier = Modifier.heightIn(min = 48.dp).testTag("hours-more-remove")) { Text(OnlineTexts.MORE_HOURS_REMOVE) }
    }
}

/**
 * One set of hours of the form: [title], the draft [d] and its [onChange], or the text the form cannot
 * show ([readOnly]); [tag] ends the test tags of a second set ("-2").
 */
@Composable
private fun HoursBlock(
    title: String, d: HoursDraft, onChange: (HoursDraft) -> Unit, readOnly: String?, changed: Boolean, onUndo: () -> Unit, tag: String,
    titleModifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val h24 = remember { DateFormat.is24HourFormat(ctx) }
    var picking by remember { mutableStateOf<PickFor?>(null) }
    var folded by remember { mutableStateOf(true) }
    var pasting by remember { mutableStateOf(false) }
    // item 34: after a paste whose AM or PM was worked out, until the hours are changed by hand
    var pasteNote by remember { mutableStateOf<String?>(null) }
    val change: (HoursDraft) -> Unit = {
        pasteNote = null
        onChange(it)
    }
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = titleModifier.padding(top = 8.dp).semantics { heading() })
    ChangedMark(changed, title, onUndo)
    if (readOnly != null) {
        Text(readOnly, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("edit-hours-readonly$tag"))
        Text(OnlineTexts.HOURS_READ_ONLY, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    // the preview, exactly as the place sheet will show the week
    OutlinedCard(Modifier.fillMaxWidth().testTag("hours-preview$tag")) {
        Column(Modifier.padding(12.dp)) {
            Text(OnlineTexts.PREVIEW, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HoursPreview(d.text().takeIf { it.isNotEmpty() }, OnlineTexts.NO_HOURS, Modifier.padding(top = 4.dp))
        }
    }
    pasteNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("hours-paste-note$tag")) }
    TextButton(onClick = { pasting = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("hours-paste$tag")) { Text(OnlineTexts.PASTE_HOURS) }
    d.groups.forEachIndexed { gi, g -> DayGroupCard(d, gi, g, h24, onChange = change, onPick = { picking = it }) }
    val closedDays = d.closedDays()
    if (closedDays.isNotEmpty()) {
        if (d.groups.isNotEmpty()) Text("${OnlineTexts.CLOSED_ON}: ${daysText(closedDays)}", style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 4.dp).testTag("hours-closed-days$tag"))
        OutlinedButton(
            onClick = { change(d.copy(groups = d.groups + DayGroup(closedDays, listOf(TimeRange(9 * 60, 17 * 60))))) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("hours-add-days$tag"),
        ) { Text(if (d.groups.isEmpty()) OnlineTexts.ADD_HOURS_FIRST else OnlineTexts.ADD_MORE_DAYS) }
    }
    if (d.groups.isNotEmpty()) HolidaysLine(d, h24, onChange = change, onPick = { picking = it })
    // the exact text, read-only
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { folded = !folded }.testTag("hours-as-stored$tag"),
        verticalAlignment = Alignment.CenterVertically) {
        Text((if (folded) "▸ " else "▾ ") + OnlineTexts.AS_STORED, color = MaterialTheme.colorScheme.primary)
    }
    if (!folded) Text(d.text().ifEmpty { OnlineTexts.NOTHING_STORED }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 8.dp).testTag("hours-osm-text$tag"))
    picking?.let { p ->
        val initial = if (p.group < 0) d.phRanges.getOrNull(p.range).let { r -> if (p.opens) r?.from else r?.to } ?: (9 * 60)
        else d.groups[p.group].ranges.getOrNull(p.range).let { r -> if (p.opens) r?.from else r?.to } ?: (9 * 60)
        TimePickDialog(initial % 1440, h24, onPick = { m ->
            change(setTime(d, p, m))
            picking = null
        }, onDismiss = { picking = null })
    }
    if (pasting) PasteHoursDialog(onUse = { read ->
        onChange(read.draft)
        pasteNote = if (read.guessed) OnlineTexts.PASTE_CHECK else null
        pasting = false
    }, onDismiss = { pasting = false })
}

/**
 * Item 34: hours pasted as the business writes them ([HoursText]). The text is read when "Use these hours"
 * is tapped; what it could not read stays in the box with a line saying so. The clipboard is read only on
 * the tap of "Paste from clipboard".
 */
@Composable
internal fun PasteHoursDialog(onUse: (HoursText.Read) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(OnlineTexts.PASTE_TITLE) },
        text = {
            Column {
                Text(OnlineTexts.PASTE_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // P64: the field's name for a screen reader (it had none)
                OutlinedTextField(text, { text = it; failed = false }, minLines = 3, maxLines = 8, isError = failed,
                    label = { Text(OnlineTexts.HOURS) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("hours-paste-text"))
                if (failed) Text(OnlineTexts.PASTE_NOT_READ, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("hours-paste-failed"))
                TextButton(onClick = { clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { text = it.take(400); failed = false } },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("hours-paste-clipboard")) { Text(OnlineTexts.PASTE_FROM_CLIPBOARD) }
            }
        },
        confirmButton = {
            TextButton(onClick = { HoursText.parse(text)?.let(onUse) ?: run { failed = true } }, enabled = text.isNotBlank(),
                modifier = Modifier.testTag("hours-paste-use")) { Text(OnlineTexts.PASTE_USE) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(OnlineTexts.PASTE_CANCEL) } },
    )
}

private fun setTime(d: HoursDraft, p: PickFor, m: Int): HoursDraft {
    fun one(r: TimeRange): TimeRange = if (p.opens) TimeRange(m, r.to.takeIf { it != m } ?: ((m + 60) % 1440)) else TimeRange(r.from, if (m == r.from) (m + 60) % 1440 else if (m == 0) 1440 else m)
    return if (p.group < 0) {
        d.copy(phRanges = d.phRanges.mapIndexed { i, r -> if (i == p.range) one(r) else r })
    } else {
        d.copy(groups = d.groups.mapIndexed { gi, g -> if (gi != p.group) g else g.copy(ranges = g.ranges.mapIndexed { i, r -> if (i == p.range) one(r) else r }) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayGroupCard(d: HoursDraft, gi: Int, g: DayGroup, h24: Boolean, onChange: (HoursDraft) -> Unit, onPick: (PickFor) -> Unit) {
    fun with(ng: DayGroup?): HoursDraft = d.copy(groups = d.groups.mapIndexedNotNull { i, x -> if (i == gi) ng else x })
    OutlinedCard(Modifier.fillMaxWidth().testTag("hours-group-$gi")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(daysText(g.days), style = MaterialTheme.typography.titleSmall)
            // a day picked here leaves any other group (a day has one group)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (day in 0 until 7) {
                    val on = day in g.days
                    FilterChip(
                        selected = on,
                        onClick = {
                            val days = if (on) g.days - day else (g.days + day).sorted()
                            if (days.isEmpty()) return@FilterChip
                            onChange(d.copy(groups = d.groups.mapIndexed { i, x -> if (i == gi) g.copy(days = days) else x.copy(days = x.days - day) }.filter { it.days.isNotEmpty() }))
                        },
                        label = { Text(DAY_SHORT[day]) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = DAY_LONG[day] + if (on) ", in these days" else ", not in these days" },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Switch) { onChange(with(g.copy(allDay = !g.allDay, ranges = g.ranges.ifEmpty { listOf(TimeRange(9 * 60, 17 * 60)) }))) }) {
                Text(OnlineTexts.OPEN_24, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = g.allDay, onCheckedChange = null)
            }
            if (!g.allDay) {
                g.ranges.forEachIndexed { ri, r ->
                    RangeRow(r, h24, daysText(g.days), onOpens = { onPick(PickFor(gi, ri, true)) }, onCloses = { onPick(PickFor(gi, ri, false)) },
                        onRemove = if (g.ranges.size > 1) ({ onChange(with(g.copy(ranges = g.ranges.filterIndexed { i, _ -> i != ri }))) }) else null,
                        tag = "hours-range-$gi-$ri")
                }
                if (g.ranges.size < 3) TextButton(onClick = {
                    val last = g.ranges.lastOrNull()
                    val from = last?.let { (it.end % 1440 + 60) % 1440 } ?: (9 * 60)
                    onChange(with(g.copy(ranges = g.ranges + TimeRange(from, (from + 4 * 60) % 1440))))
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("hours-add-time-$gi")) { Text(OnlineTexts.ADD_SECOND_TIME) }
            }
            TextButton(onClick = { onChange(with(null)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.REMOVE_DAYS) }
        }
    }
}

/** One opening time: "Opens 3:00 PM", "Closes 2:00 AM (next day)", each a large button that opens the time picker. */
@Composable
private fun RangeRow(r: TimeRange, h24: Boolean, days: String, onOpens: () -> Unit, onCloses: () -> Unit, onRemove: (() -> Unit)?, tag: String) {
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(OnlineTexts.OPENS, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOpens, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = "${OnlineTexts.OPENS} ${clockText(r.from, h24)}, $days. ${OnlineTexts.PICK_TIME}" }) {
                    Text(clockText(r.from, h24))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(OnlineTexts.CLOSES, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val closes = clockText(r.to, h24) + if (r.nextDay) " " + OnlineTexts.NEXT_DAY else ""
                OutlinedButton(onClick = onCloses, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = "${OnlineTexts.CLOSES} $closes, $days. ${OnlineTexts.PICK_TIME}" }) {
                    Text(closes, maxLines = 1)
                }
            }
        }
        if (onRemove != null) TextButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.REMOVE_TIME) }
    }
}

/** The public holidays line (item 24 d): Not sure (the default), Closed, Same as usual, or other hours. */
@Composable
private fun HolidaysLine(d: HoursDraft, h24: Boolean, onChange: (HoursDraft) -> Unit, onPick: (PickFor) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("hours-holidays")) {
        Text(OnlineTexts.HOLIDAYS, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        for ((mode, label) in listOf(PhMode.UNSURE to OnlineTexts.PH_UNSURE, PhMode.CLOSED to OnlineTexts.PH_CLOSED,
            PhMode.SAME to OnlineTexts.PH_SAME, PhMode.CUSTOM to OnlineTexts.PH_CUSTOM)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.RadioButton) {
                    onChange(d.copy(ph = mode, phRanges = if (mode == PhMode.CUSTOM && d.phRanges.isEmpty()) listOf(TimeRange(10 * 60, 14 * 60)) else d.phRanges))
                }) {
                RadioButton(selected = d.ph == mode, onClick = null)
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (d.ph == PhMode.SAME) Text(OnlineTexts.PH_SAME_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (d.ph == PhMode.CUSTOM) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Switch) { onChange(d.copy(phAllDay = !d.phAllDay)) }) {
                Text(OnlineTexts.OPEN_24, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = d.phAllDay, onCheckedChange = null)
            }
            if (!d.phAllDay) d.phRanges.forEachIndexed { ri, r ->
                RangeRow(r, h24, OnlineTexts.HOLIDAYS, onOpens = { onPick(PickFor(-1, ri, true)) }, onCloses = { onPick(PickFor(-1, ri, false)) },
                    onRemove = null, tag = "hours-ph-range-$ri")
            }
        }
    }
}

/**
 * The platform's time picker (Material 3): the dial, with its keyboard mode a tap away, in the phone's
 * 12- or 24-hour setting. Times are picked, never typed as text (item 24 a).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickDialog(initial: Int, h24: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = h24)
    var typing by remember { mutableStateOf(false) }
    // item 47: a small sheet (its handle drags it away), as the map style sheet
    PickerSheet(OnlineTexts.PICK_TIME, onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().testTag("time-picker")) {
            if (typing) TimeInput(state) else TimePicker(state)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { typing = !typing }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (typing) OnlineTexts.USE_DIAL else OnlineTexts.TYPE_TIME) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(OnlineTexts.CANCEL) }
            TextButton(onClick = { onPick(state.hour * 60 + state.minute) }, modifier = Modifier.heightIn(min = 48.dp).testTag("time-ok")) { Text(OnlineTexts.OK) }
        }
    }
}

/**
 * Item 47: a picker as a small sheet from the bottom (the map style sheet's kind): its handle, a
 * title, then [content]; a drag down, a tap beside it or Back dismisses it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickerSheet(title: String, onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // P41: the app's sheet colour, and no contrast band behind the navigation bar (SheetWindowBars)
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        SheetWindowBars()
        // audit F11: it scrolls when it is taller than the screen (landscape on a car mount, large text)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp).navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
            content()
        }
    }
}

/** "Discard your changes?" on leaving a form with changes (item 24 e). */
@Composable
fun DiscardDialog(onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(OnlineTexts.DISCARD_TITLE) },
        text = { Text(OnlineTexts.DISCARD_TEXT) },
        confirmButton = { TextButton(onClick = onDiscard, modifier = Modifier.testTag("discard")) { Text(OnlineTexts.DISCARD) } },
        dismissButton = { TextButton(onClick = onKeep) { Text(OnlineTexts.KEEP_EDITING) } },
    )
}

/** The type picker: the search's grid of types ([PlaceTypeGrid], one component, two uses) with the addable types. */
@Composable
fun TypePickerDialog(selected: String?, onPick: (AddableType) -> Unit, onDismiss: () -> Unit) {
    // item 47: a small sheet, as the other pickers
    PickerSheet(OnlineTexts.TYPE, onDismiss) {
        HorizontalDivider()
        PlaceTypeGrid(addableGroups(), onPick = { t -> PlaceTypes.byCode(t.code)?.let(onPick) }, selected = selected,
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp))
    }
}
