package io.github.spysyweeb.beamline.app.online

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons
import java.util.Locale

/** The texts of batches O1 and O2 (docs/online-design.md sections 2, 3, 4.1, 4.2, 4.4 and 4.5). */
object OnlineTexts {
    const val CHOICE_TITLE = "Online features"
    const val CHOICE_INTRO = "Beamline works without the internet: maps, routes, search and your places stay on " +
        "this phone. You can also allow a few online features. You can change this any time in Settings."
    const val OFFLINE_ONLY = "Offline only"
    const val OFFLINE_ONLY_TEXT = "Beamline makes no internet requests except the state downloads you start."
    const val ALLOW_ONLINE = "Allow online features"
    // Batch O2 (the owner's answers after batch H): no promise of finding places online until batch O4 brings it.
    const val ALLOW_ONLINE_TEXT = "You can report map problems and fix places on OpenStreetMap. Beamline sends only " +
        "what each feature needs, when you use it, and tells you what that is first."

    const val SETTINGS_MAIN = "Online features"
    // P55 / F88: Settings' heading of the online switches; not "Online features", the main switch's own name
    const val SETTINGS_SECTION = "Internet"
    const val SETTINGS_MAIN_ON = "Beamline uses the internet only for the features below, when you use them."
    const val SETTINGS_MAIN_OFF = "Offline only: no internet requests except the state downloads you start."
    const val SETTINGS_OSM = "Contribute to OpenStreetMap"
    const val SETTINGS_OSM_TEXT = "Report map problems as public notes, fix places' details and add missing places on OpenStreetMap."
    /** Item 40. */
    /** Item 41. */
    const val SETTINGS_APP_UPDATES = "Check for app updates"
    const val SETTINGS_APP_UPDATES_TEXT = "Once a day, looks on GitHub for a newer Beamline. Downloads it on Wi-Fi and installs it when you are not driving."
    const val SETTINGS_MAP_UPDATES = "Check for map updates"
    const val SETTINGS_MAP_UPDATES_TEXT = "Once a day, tells you on the map when your states have newer map data. It never downloads a state by itself."
    const val SIGN_IN = "Sign in to OpenStreetMap"
    const val SIGN_IN_TEXT = "Needed to fix or add a place. Notes can go without it. You sign in on OpenStreetMap's own page."
    const val SIGN_OUT = "Sign out"
    const val SIGNING_IN = "Signing in..."
    fun signedIn(name: String) = "Signed in as $name"
    const val MY_REPORTS = "My reports"

    const val FORM_TITLE = "Report a problem"
    const val FORM_EDIT_TITLE = "Your note"
    const val FORM_HINT = "What is wrong here? (optional with a choice above)"
    const val FORM_SAYS = "Sent as a public OpenStreetMap note at this point, without your name. Local mappers " +
        "read it and fix the map. It ends with the line \"${NoteText.VIA}\"."
    const val FORM_SAYS_NAMED = "Sent as a public OpenStreetMap note at this point, under your OSM name, so mappers " +
        "can ask you about it. It ends with the line \"${NoteText.VIA}\"."
    const val WITH_NAME = "Post with my OSM name"
    const val SEND = "Send"
    const val SAVE = "Save"
    const val DELETE = "Delete"

    const val NOTICE_TITLE = "Notes are public"
    const val NOTICE_TEXT = "Your note goes to OpenStreetMap, where everyone can see it: its text, the point and the " +
        "time. It is sent without your name.\n\nBeamline sends the note's text and the point you chose, and the " +
        "app's name. Never your position, your places or anything about this phone."
    const val NOTICE_TEXT_NAMED = "Your note goes to OpenStreetMap, where everyone can see it: its text, your OSM " +
        "username, the point and the time.\n\nBeamline sends the note's text and the point you chose, and the " +
        "app's name. Never your position, your places or anything about this phone."
    const val NOT_NOW = "Not now"

    const val REPORTS_EMPTY = "No reports yet. Long-press the map, or open a place's details, to report a problem there."
    const val SEND_NOW = "Send now"
    const val OPEN_NOTE = "Open on OpenStreetMap"
    const val REMOVE = "Remove from this list"

    fun where(lat: Double, lon: Double, placeName: String?): String =
        placeName ?: String.format(Locale.ROOT, "The point you pressed: %.5f, %.5f", lat, lon)

    fun state(item: OutboxItem, sending: Boolean, signedIn: Boolean = true): String = when {
        sending -> "Sending..."
        item.isSpeed && !item.isEdit -> "Picked while driving: open it to check the road and send it"
        item.state == OutboxState.WAITING && (item.isEdit || item.isAdd) && !signedIn -> "Waiting: sign in to OpenStreetMap to send it"
        item.state == OutboxState.WAITING -> "Waiting to be sent"
        item.state == OutboxState.SENT && item.isEdit -> "Uploaded · changeset ${item.edit?.changeset}"
        item.state == OutboxState.SENT && item.isAdd -> "Added · node ${item.add?.node}"
        item.state == OutboxState.REVIEW && item.isAdd -> "Something like it was added meanwhile: open it to look again"
        item.state == OutboxState.SENT -> "Sent · note ${item.noteId}"
        item.state == OutboxState.REVIEW -> "Changed on OpenStreetMap meanwhile: open it to look again"
        else -> "Not accepted by OpenStreetMap (${item.status}). Change it or delete it."
    }

    /** "Edit · Test Cafe" / the note's place line in My reports. */
    fun kind(item: OutboxItem): String = if (item.isSpeed) "Speed limit" else if (item.isAdd) "New place" else if (item.isEdit) "Edit" else "Note"

    // ---- item 32: the speed limit of a road (4.7)
    const val SPEED_TITLE = "Speed limit"
    const val SPEED_LOADING = "Reading the road from OpenStreetMap..."
    const val SPEED_NO_ROAD = "OpenStreetMap has no road with a posted limit within 100 feet of this point. " +
        "You can report it as a note instead."
    const val SPEED_OFFLINE = "No connection. The road has to be read from OpenStreetMap first. Try again when you're online."
    const val SPEED_ROAD = "Road"
    const val SPEED_NOW = "On OpenStreetMap now"
    const val SPEED_POSTED = "What the sign says"
    const val SPEED_PICK_FIRST = "Choose the limit the sign shows."
    const val SPEED_SAME = "OpenStreetMap already has this limit for this stretch."
    const val SPEED_CONFLICT = "This road changed on OpenStreetMap meanwhile. Check it again."
    const val SPEED_SAYS = "This sets the limit of this stretch of the road only. Use the number on the white Speed Limit sign, " +
        "not an advisory (yellow) sign."
    const val SPEED_BUTTON = "Fix the speed limit of this road"
    const val SPEED_PICK_TITLE = "Speed limit here"
    const val SPEED_PICK_TEXT = "Tap what the sign says. It is kept in My reports; nothing is sent until you have stopped and checked it."
    const val SPEED_PICK_CANCEL = "Cancel"
    const val SPEED_SAVED = "Kept in My reports. Open it there when you've stopped."
    const val SPEED_SIGN_IN = "Sign in to OpenStreetMap in Settings to fix a speed limit."
    const val SPEED_TRY_AGAIN = "Try again"
    const val SPEED_DONE = "Uploaded to OpenStreetMap. Beamline's own speed sign shows it once a newer map of this state is installed."
    const val SPEED_NO_FIX = "No position yet: the limit was not kept."

    /** What a limit picked while driving says in My reports. */
    fun speedPicked(mph: Int): String = "Speed limit $mph mph (as the sign said)"

    /** The summary of a speed limit fix. */
    fun speedLines(r: SpeedLimits.Road, mph: Int): List<String> = listOf(
        "$SPEED_ROAD: ${r.name ?: "(no name)"}, a stretch of about ${stretch(r.lengthM)}",
        "$SPEED_TITLE: ${SpeedLimits.shown(r.tags[SpeedLimits.KEY])} → ${SpeedLimits.tag(mph)}",
    )

    /** A way's length in the words of a summary: "600 feet" (to the nearest 50, up to half a mile), "0.8 miles". */
    fun stretch(m: Double): String {
        val feet = m / 0.3048
        return if (feet < 2640) "${(Math.round(feet / 50.0) * 50).coerceAtLeast(50)} feet"
        else String.format(Locale.US, "%.1f miles", m / 1609.344)
    }

    // ---- batch O2: fixing a place (4.2), the sign-in (4.4)
    const val EDIT_TITLE = "Edit on OpenStreetMap"
    const val EDIT_LOADING = "Reading the place from OpenStreetMap..."
    const val EDIT_CHANGED = "This place changed on OpenStreetMap since your map was made. The form shows its current values."
    const val EDIT_OFFLINE = "No connection: the form shows the values of your map. Before your edit goes, Beamline " +
        "reads the place again, and if it changed meanwhile asks you to look again."
    const val EDIT_CONFLICT = "Someone changed this place on OpenStreetMap after you made your edit. Its new values are " +
        "below with your changes on them; check them and upload again."
    const val NOW_ON_OSM = "Now on OpenStreetMap"
    const val NAME = "Name"
    const val HOURS = "Opening hours"
    const val PHONE = "Phone"
    const val WEBSITE = "Website"
    const val HOURS_READ_ONLY = "These hours have parts this form cannot show (holidays, seasons or notes). They stay " +
        "as they are; they can be changed on openstreetmap.org."
    const val CLOSED_PERMANENTLY = "Closed permanently"
    const val CLOSED_TEXT = "The place stays on OpenStreetMap as a closed place, so mappers see what was there. Its " +
        "hours, phone and website are removed."
    const val CLOSED_OFFLINE = "Closed permanently needs a connection: Beamline reads the place's details from " +
        "OpenStreetMap first."
    const val NEXT = "Next"
    const val SUMMARY_TITLE = "Your changes"
    const val NOTHING_CHANGED = "Nothing is changed yet."
    const val UPLOAD = "Upload"
    const val UPLOADING = "Uploading..."
    fun summarySays(comment: String) = "This will be uploaded to OpenStreetMap under your OSM name as one change with " +
        "the comment \"$comment\". It will be public: everyone can see it."
    const val DONE = "Uploaded. Beamline shows your edit on this place until a map update brings it."
    const val OPEN_CHANGE = "Open the change on OpenStreetMap"
    const val EDIT_WAITING = "Your edit waits in My reports and goes by itself when you're online and Beamline is open."
    fun editRefused(status: Int?) = "OpenStreetMap did not accept the edit ($status). See My reports."
    const val CLOSE = "Close"
    const val BACK_TO_FORM = "Change"
    const val EDIT_NOTICE_TITLE = "Edits are public"
    const val EDIT_NOTICE_TEXT = "What you change on OpenStreetMap is public: everyone can see the change, your OSM " +
        "username, the place and the time.\n\nBeamline sends the place's OSM id, the changed details, a short " +
        "comment and the app's name. Never your position, your places or anything about this phone."
    const val SIGN_IN_NEEDED_TITLE = "Sign in to edit"
    const val SIGN_IN_NEEDED = "Fixing a place on OpenStreetMap needs your OSM account. You sign in on " +
        "OpenStreetMap's own page; Beamline never sees your password.\n\nIs the place closed? You can send a note " +
        "instead, without an account."
    const val SEND_NOTE_INSTEAD = "Send a note"
    const val CANCEL = "Cancel"

    // ---- batch O3: the friendlier form (item 24), add a missing place (4.3), a new business (item 25)
    const val TYPE = "What it is"
    const val CHOOSE_TYPE = "Choose a type"
    const val CHANGE_TYPE = "Change"
    const val TYPE_NEEDED = "Choose what the place is."
    const val NAME_NEEDED = "Give the place its name."
    const val HOURS_CANNOT_WRITE = "These hours cannot be written for OpenStreetMap. Check the times."
    const val HERE_NEEDED = "Choose one of the answers about the place already on the map here."
    const val CHANGED = "Changed"
    const val UNDO = "Undo"
    const val PREVIEW = "How Beamline will show the hours"
    // item 34: hours pasted as text
    const val PASTE_HOURS = "Paste hours"
    const val PASTE_TITLE = "Paste the hours"
    const val PASTE_HINT = "As the business writes them, for example: Mon-Fri 9:30am-6pm, Sat 9-5:30, Sun closed"
    const val PASTE_FROM_CLIPBOARD = "Paste from clipboard"
    const val PASTE_USE = "Use these hours"
    const val PASTE_CANCEL = "Cancel"
    const val PASTE_NOT_READ = "Beamline could not read these hours. Change the text, or enter the hours in the form."
    const val PASTE_CHECK = "Check AM and PM: the text did not say."
    // item 33: a second set of hours
    const val MORE_HOURS_ADD = "+ Office, drive-through or kitchen hours"
    const val MORE_HOURS_TEXT = "For a part of the place that keeps its own hours:"
    const val MORE_HOURS_REMOVE = "Remove these hours"
    const val NO_HOURS = "No opening hours"
    const val DAYS = "Days"
    const val OPEN_24 = "Open 24 hours"
    const val OPENS = "Opens"
    const val CLOSES = "Closes"
    const val NEXT_DAY = "(next day)"
    const val ADD_SECOND_TIME = "+ Add a second time"
    const val REMOVE_TIME = "Remove this time"
    const val REMOVE_DAYS = "Remove these days"
    const val ADD_MORE_DAYS = "+ Add more days"
    const val ADD_HOURS_FIRST = "+ Add opening hours"
    const val CLOSED_ON = "Closed"
    const val HOLIDAYS = "Public holidays"
    const val PH_UNSURE = "Not sure (leave out)"
    const val PH_CLOSED = "Closed"
    const val PH_SAME = "Same as usual"
    const val PH_CUSTOM = "Other hours"
    const val PH_SAME_NOTE = "OpenStreetMap needs no holiday rule for hours that stay the same."
    const val AS_STORED = "As OpenStreetMap stores it"
    const val NOTHING_STORED = "(no opening hours)"
    const val DISCARD_TITLE = "Discard your changes?"
    const val DISCARD_TEXT = "What you changed in this form is not kept."
    const val KEEP_EDITING = "Keep editing"
    const val DISCARD = "Discard"
    const val PICK_TIME = "Pick a time"
    const val TYPE_TIME = "Type the time"
    const val USE_DIAL = "Use the dial"
    const val OK = "OK"
    const val DIFFERENT_BUSINESS = "This is now a different business"
    const val DIFFERENT_BUSINESS_TEXT = "Another business is here now. Choose its type and give its name and details. The old " +
        "business's own details are removed; the address and the building stay, as OpenStreetMap usually does it."
    const val NEW_NAME = "Name of the business now here"

    const val ADD_TITLE = "Add a missing place"
    const val ADD_CHECKING = "Checking what is already on the map here..."
    const val ADD_OFFLINE = "No connection: checked with your map only. Beamline checks OpenStreetMap again before it goes."
    fun hereLine(p: NearbyPlace) = "Already on the map here: " + p.line()
    const val HERE_SEVERAL = "Already on the map here. Which one is your answer about?"
    const val HERE_REPLACED = "My place replaced it"
    const val HERE_BOTH = "Both are here"
    const val HERE_SAME = "It's the same place"
    const val HERE_REPLACED_TEXT = "Your place takes over its entry: name, type and details from this form; its address and building stay."
    const val HERE_BOTH_TEXT = "A new point is added for your place."
    const val HERE_SAME_TEXT = "Opens the edit form for it."
    fun bareLine(p: NearbyPlace) = "Only an address is on the map here" + (p.tags["addr:housenumber"]?.let { n ->
        ": $n " + (p.tags["addr:street"] ?: "") }?.trimEnd() ?: "") + ". Your place's details go onto it; the address stays."
    const val DUPLICATE_TITLE = "Is it already on the map?"
    fun duplicateLine(p: NearbyPlace) = p.line() + String.format(Locale.ROOT, ", about %d m from here", p.distanceM.toInt().coerceAtLeast(1)) +
        if (p.fromOsm) " (on OpenStreetMap)" else " (on your map)"
    const val EDIT_THAT = "Edit that one instead"
    const val NOT_DUPLICATE = "It's a different place: add mine"
    const val ADD_REVIEW = "Something like your place was added to OpenStreetMap meanwhile. Look at what is here and try again."
    const val ADD_DONE = "Uploaded. Beamline shows your place on your map until a map update brings it."
    const val ADD_WAITING = "Your new place waits in My reports and goes by itself when you're online and Beamline is open."
    const val OPEN_ADDED = "Open it on OpenStreetMap"
    const val SIGN_IN_ADD_TITLE = "Sign in to add a place"
    const val SIGN_IN_ADD = "Adding a place to OpenStreetMap needs your OSM account. You sign in on OpenStreetMap's own " +
        "page; Beamline never sees your password.\n\nOr send a note instead, without an account: local mappers read it and add the place."
    const val MISSING_PLACE = "Missing place: "

    /** The changeset's comment of a new place: "Added restaurant Joe's", "Added ATM". */
    fun addComment(type: AddableType, name: String?) = "Added ${type.label.lowercase()}" + (name?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")

    /** "Replaced Primo Tailoring with restaurant Joe's". */
    fun replaceComment(oldName: String, next: Map<String, String>): String {
        val type = PlaceTypes.of(next)?.label?.lowercase() ?: "a place"
        return "Replaced $oldName with $type" + (next["name"]?.let { " $it" } ?: "")
    }

    /** The summary of a new place (4.3): what it is, where, and every tag it gets. */
    fun addLines(type: AddableType, tags: Map<String, String>): List<String> = buildList {
        val name = tags["name"]
        add("A new ${type.label.lowercase()}" + (name?.let { " named \"$it\"" } ?: "") + " at the point you pressed (not your position).")
        // in plain words where the week can be read; else as written
        tags["opening_hours"]?.let { add("Hours: " + (WeekHours.of(it)?.plain() ?: it)) }
        MoreHours.of(tags)?.let { m -> tags[m.key]?.let { add(m.label + ": " + (WeekHours.of(it)?.plain() ?: it)) } }
        tags["phone"]?.let { add("Phone: $it") }
        tags["website"]?.let { add("Website: $it") }
        add("OpenStreetMap tags: " + tags.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }

    /** The summary of a new business in an old place (item 25): what changes in words, then every tag that goes, comes and stays. */
    fun replaceLines(old: Map<String, String>, next: Map<String, String>, changes: Map<String, String?>, oldName: String? = old["name"]): List<String> = buildList {
        val bare = BusinessTags.isBareAddress(old)
        val what = buildList {
            if (old["name"] != next["name"]) add("name")
            if (PlaceEdits.MAIN_KEYS.any { old[it] != next[it] }) add("type")
            if (old["opening_hours"] != next["opening_hours"]) add("hours")
            if ((old["phone"] ?: old["contact:phone"]) != next["phone"]) add("phone")
            if ((old["website"] ?: old["contact:website"]) != next["website"]) add("website")
        }
        val keep = BusinessTags.kept(old, next)
        val kept = listOfNotNull("address".takeIf { keep.any { it.startsWith("addr:") } }, "building".takeIf { keep.any { it.startsWith("building") } })
        add(
            if (bare) "Goes onto the address already on the map here" + (if (kept.isNotEmpty()) "; ${kept.joinToString(" and ")} kept" else "") + "."
            else "Replaces ${oldName ?: "the place"}: " + (if (what.isEmpty()) "nothing" else what.joinToString(", ")) +
                (if (what.size == 1) " changes" else " change") + (if (kept.isNotEmpty()) "; ${kept.joinToString(" and ")} kept" else "") + ".",
        )
        val goes = changes.filter { it.value == null }.keys
        if (goes.isNotEmpty()) add("Goes: " + goes.joinToString(", ") { "$it=${old[it]}" })
        val comes = changes.filter { it.value != null }
        if (comes.isNotEmpty()) add("Comes: " + comes.entries.joinToString(", ") { "${it.key}=${it.value}" })
        if (keep.isNotEmpty()) add("Stays: " + keep.joinToString(", "))
    }

    fun reportsCount(items: List<OutboxItem>): String {
        if (items.isEmpty()) return "None yet"
        val waiting = items.count { it.state != OutboxState.SENT }
        val sent = items.size - waiting
        return listOfNotNull(waiting.takeIf { it > 0 }?.let { "$it waiting" }, sent.takeIf { it > 0 }?.let { "$it sent" }).joinToString(" · ")
    }
}

/**
 * The first-run step after the driver notice (docs/online-design.md section 2), shown once, also to
 * someone updating from a version without it: two equal choices, neither selected. The choice is
 * made by a tap on one of them.
 */
@Composable
fun OnlineChoiceScreen(onChoose: (online: Boolean) -> Unit) {
    BackHandler { /* a choice is needed; Back does not skip it */ }
    Surface(Modifier.fillMaxSize().testTag("online-choice"), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(OnlineTexts.CHOICE_TITLE, style = MaterialTheme.typography.headlineSmall)
            Text(OnlineTexts.CHOICE_INTRO, style = MaterialTheme.typography.bodyLarge)
            ChoiceCard(OnlineTexts.OFFLINE_ONLY, OnlineTexts.OFFLINE_ONLY_TEXT, "choice-offline") { onChoose(false) }
            ChoiceCard(OnlineTexts.ALLOW_ONLINE, OnlineTexts.ALLOW_ONLINE_TEXT, "choice-online") { onChoose(true) }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, text: String, tag: String, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).testTag(tag)) {
        Column(Modifier.padding(16.dp).heightIn(min = 72.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The report form (docs/online-design.md 4.1): the quick choices and a text box for a new note at
 * [lat], [lon]; or, with [item], a waiting or refused outbox item to change or delete (4.5).
 */
@Composable
fun ReportScreen(
    lat: Double,
    lon: Double,
    placeName: String?,
    item: OutboxItem?,
    /** Batch O2: signed in, the form offers "Post with my OSM name", checked to start with (answer 2). */
    signedIn: Boolean,
    /** A quick choice to start with ("Closed" from the sign-in dialog of an edit, answer 6). */
    startChoice: String? = null,
    /** Batch O3: the words to start with ("Missing place: " from the sign-in dialog of Add a missing place). */
    startText: String? = null,
    onSend: (text: String, withName: Boolean) -> Unit,
    onSave: (text: String) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    /** Item 32: a new report at a point on the map can fix the road's speed limit itself; null: not offered. */
    onSpeedLimit: (() -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    var choice by rememberSaveable { mutableStateOf(startChoice) }
    var withName by rememberSaveable { mutableStateOf(item?.withName ?: true) }
    var words by rememberSaveable { mutableStateOf(item?.text ?: startText ?: "") }
    val text = NoteText.compose(choice, words)
    Surface(Modifier.fillMaxSize().testTag("report-form"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(BeamlineIcons.Back, contentDescription = "Back") }
                Text(if (item == null) OnlineTexts.FORM_TITLE else OnlineTexts.FORM_EDIT_TITLE, style = MaterialTheme.typography.titleLarge)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(OnlineTexts.where(lat, lon, placeName), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (item == null && onSpeedLimit != null && words.isBlank() && choice == null) {
                    OutlinedButton(onClick = onSpeedLimit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("report-speed-limit")) {
                        Text(OnlineTexts.SPEED_BUTTON)
                    }
                }
                if (item == null) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NoteText.CHOICES.forEach { c ->
                            FilterChip(selected = choice == c, onClick = { choice = if (choice == c) null else c }, label = { Text(c) })
                        }
                    }
                }
                OutlinedTextField(
                    value = words,
                    onValueChange = { if (it.length <= NoteText.MAX_CHARS) words = it },
                    label = { Text(if (item == null) OnlineTexts.FORM_HINT else OnlineTexts.FORM_EDIT_TITLE) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().testTag("report-text"),
                )
                if (signedIn && item == null) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { withName = !withName }.testTag("report-with-name"),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = withName, onCheckedChange = null)
                        Text(OnlineTexts.WITH_NAME, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                val named = signedIn && (item?.withName ?: withName)
                Text(if (named) OnlineTexts.FORM_SAYS_NAMED else OnlineTexts.FORM_SAYS, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item == null) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSend(text, signedIn && withName) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("report-send")) { Text(OnlineTexts.SEND) }
                } else {
                    OutlinedButton(onClick = onDelete) { Text(OnlineTexts.DELETE) }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSave(words.trim()) }, enabled = words.isNotBlank()) { Text(OnlineTexts.SAVE) }
                }
            }
        }
    }
}

/** The public-note notice before the first note (section 3): Send or Not now. */
@Composable
fun NoteNoticeDialog(withName: Boolean = false, onAnswer: (send: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        title = { Text(OnlineTexts.NOTICE_TITLE) },
        text = { Text(if (withName) OnlineTexts.NOTICE_TEXT_NAMED else OnlineTexts.NOTICE_TEXT) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(OnlineTexts.SEND) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text(OnlineTexts.NOT_NOW) } },
    )
}

/**
 * "My reports" (docs/online-design.md 4.5): the outbox, newest first. A waiting or refused note opens
 * in the form to be changed or deleted; a sent one keeps its number and a link to it on OSM.
 */
@Composable
fun ReportsScreen(
    items: List<OutboxItem>,
    sending: String?,
    canSend: Boolean,
    /** Batch O2: an edit waits for the account while signed out. */
    signedIn: Boolean,
    onOpen: (OutboxItem) -> Unit,
    onOpenLink: (String) -> Unit,
    onRemove: (OutboxItem) -> Unit,
    onSendNow: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize().testTag("reports"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(BeamlineIcons.Back, contentDescription = "Back") }
                Text(OnlineTexts.MY_REPORTS, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (canSend && items.any { it.state == OutboxState.WAITING }) {
                    TextButton(onClick = onSendNow, enabled = sending == null) { Text(OnlineTexts.SEND_NOW) }
                }
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                if (items.isEmpty()) Text(OnlineTexts.REPORTS_EMPTY, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp))
                items.forEach { i ->
                    val busy = sending == i.id
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(enabled = i.editable && !busy) { onOpen(i) }
                            .padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(i.text, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
                        Text(OnlineTexts.kind(i) + " · " + OnlineTexts.where(i.lat, i.lon, i.placeName), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(OnlineTexts.state(i, busy, signedIn), style = MaterialTheme.typography.bodyMedium,
                            color = if (i.state == OutboxState.REFUSED || i.state == OutboxState.REVIEW) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (i.state == OutboxState.SENT) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            i.link?.let { link -> TextButton(onClick = { onOpenLink(link) }) { Text(OnlineTexts.OPEN_NOTE) } }
                            TextButton(onClick = { onRemove(i) }) { Text(OnlineTexts.REMOVE) }
                        }
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}
