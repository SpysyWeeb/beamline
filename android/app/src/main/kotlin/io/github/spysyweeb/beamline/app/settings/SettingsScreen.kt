package io.github.spysyweeb.beamline.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.spysyweeb.beamline.app.online.OnlineSettings
import io.github.spysyweeb.beamline.app.online.OnlineTexts
import io.github.spysyweeb.beamline.settings.MapStyle
import io.github.spysyweeb.beamline.settings.Settings
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.settings.Units
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons
import kotlinx.coroutines.delay

/**
 * The user's settings, stored by :core:settings (docs/architecture.md 5.4 item 8): map style, frame
 * rate (Screen rate or 60: the owner removed 30 on 2026-11-16), guidance sound, units; the group
 * "Places" with "Saved places" (docs/architecture-saved-places.md 5.6: "Not while guiding" in
 * GUIDING, REROUTING and ARRIVED) and States; "Internet" (docs/online-design.md section 2, batch O1:
 * the main switch "Online features", "Contribute to OpenStreetMap", "My reports"); About.
 * P55 / F88 (0.1.40): a row that cannot be used now says why in place of its description, stays
 * tappable, and a tap shows the reason in a bar at the bottom (SettingsRules); nothing is switched on by it.
 */
@Composable
fun SettingsScreen(
    store: SettingsStore,
    /** A change of the settings, written off the main thread (AppGraph.updateSettings). */
    write: ((Settings) -> Settings) -> Unit,
    driving: Boolean,
    onSavedPlaces: () -> Unit,
    onStates: () -> Unit,
    /** Batch O1 (docs/online-design.md section 2): the switches, "My reports" and its count line. */
    online: OnlineSettings,
    onOnline: (Boolean) -> Unit,
    onContributeOsm: (Boolean) -> Unit,
    /** Item 40: "Check for map updates". */
    onCheckMapUpdates: (Boolean) -> Unit,
    /** Item 41: this build can update itself; the switch, the state line and "Check now". */
    appUpdates: Boolean,
    onCheckAppUpdates: (Boolean) -> Unit,
    appUpdateLine: String,
    onCheckAppNow: () -> Unit,
    /** Item 41: an update is downloaded: Install beside the line. */
    appUpdateReady: Boolean,
    onInstallApp: () -> Unit,
    reportsText: String,
    /** Batch O2 (4.4): the OSM account line: the signed-in name, or null for "Sign in to OpenStreetMap". */
    accountName: String?,
    signingIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onReports: () -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val s by store.settings.collectAsStateWithLifecycle()
    // P55: the reason a row that cannot be used now was tapped. Every tap restarts its time (the
    // key changes even when the words are the same); it goes when a switch or the trip changes,
    // because the reason may no longer hold, and with the screen.
    var reason by remember { mutableStateOf<ReasonShown?>(null) }
    val showReason: (String) -> Unit = { text -> reason = ReasonShown(text, (reason?.taps ?: 0) + 1) }
    LaunchedEffect(reason) {
        if (reason != null) {
            delay(SettingsRules.REASON_MS)
            reason = null
        }
    }
    LaunchedEffect(online, driving) { reason = null }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(BeamlineIcons.Back, contentDescription = "Back") }
                    Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                }
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Group(
                        "Map style",
                        listOf(
                            Option("Automatic", "Day or night with sunrise and sunset", s.mapStyle == MapStyle.AUTOMATIC) { write { it.copy(mapStyle = MapStyle.AUTOMATIC) } },
                            Option("Same as phone", "Day or night with the phone's dark mode", s.mapStyle == MapStyle.SAME_AS_PHONE) { write { it.copy(mapStyle = MapStyle.SAME_AS_PHONE) } },
                            Option("Day", null, s.mapStyle == MapStyle.DAY) { write { it.copy(mapStyle = MapStyle.DAY) } },
                            Option("Night", null, s.mapStyle == MapStyle.NIGHT) { write { it.copy(mapStyle = MapStyle.NIGHT) } },
                        ),
                    )
                    // "Frame rate" and "Guidance sound" are not offered (the owner, 2026-11-21): the map asks for the
                    // screen's fastest rate whatever an older version stored (MainActivity), and guidance is silent.
                    Group(
                        "Units",
                        listOf(
                            Option("Miles and feet", null, s.units == Units.MILES) { write { it.copy(units = Units.MILES) } },
                            Option("Kilometers and meters", null, s.units == Units.KILOMETRES) { write { it.copy(units = Units.KILOMETRES) } },
                        ),
                    )
                    Heading("Places")
                    // "Not while guiding" while driving is now the row's reason (P55)
                    LinkRow("Saved places", null, blocked = SettingsRules.drivingBlocked(driving), onBlocked = showReason, onClick = onSavedPlaces)
                    LinkRow("States", "Add, update or remove the states on this phone", onClick = onStates)
                    HorizontalDivider(Modifier.padding(top = 10.dp))
                    // Online features: the main switch turns every feature on; each can then be turned off
                    // alone. Batch O4 adds "Find places online" under it, O2 the OSM account line.
                    // P55 / F88: the section is "Internet", so the main switch's name is read once.
                    Heading(OnlineTexts.SETTINGS_SECTION)
                    SwitchRow(OnlineTexts.SETTINGS_MAIN, if (online.online) OnlineTexts.SETTINGS_MAIN_ON else OnlineTexts.SETTINGS_MAIN_OFF,
                        online.online, blocked = null, tag = "switch-online", onChange = onOnline)
                    SwitchRow(OnlineTexts.SETTINGS_OSM, OnlineTexts.SETTINGS_OSM_TEXT, online.osmAllowed, blocked = SettingsRules.featureBlocked(online),
                        tag = "switch-osm", indent = true, onBlocked = showReason, onChange = onContributeOsm)
                    // Batch O2 (docs/online-design.md 4.4): the account line. Signing in needs the switch on;
                    // signing out always works (the token is deleted; OSM is asked to revoke it when online).
                    if (accountName != null) {
                        Row(Modifier.fillMaxWidth().padding(start = 32.dp, end = 8.dp).heightIn(min = 56.dp).testTag("osm-account"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(OnlineTexts.signedIn(accountName), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            TextButton(onClick = onSignOut, modifier = Modifier.testTag("osm-sign-out")) { Text(OnlineTexts.SIGN_OUT) }
                        }
                    } else {
                        LinkRow(if (signingIn) OnlineTexts.SIGNING_IN else OnlineTexts.SIGN_IN, OnlineTexts.SIGN_IN_TEXT,
                            blocked = SettingsRules.signInBlocked(online, driving), busy = signingIn, onBlocked = showReason, onClick = onSignIn)
                    }
                    LinkRow(OnlineTexts.MY_REPORTS, reportsText, blocked = SettingsRules.drivingBlocked(driving), onBlocked = showReason, onClick = onReports)
                    SwitchRow(OnlineTexts.SETTINGS_MAP_UPDATES, OnlineTexts.SETTINGS_MAP_UPDATES_TEXT, online.mapUpdatesAllowed,
                        blocked = SettingsRules.featureBlocked(online), tag = "switch-map-updates", indent = true, onBlocked = showReason, onChange = onCheckMapUpdates)
                    if (appUpdates) {
                        SwitchRow(OnlineTexts.SETTINGS_APP_UPDATES, OnlineTexts.SETTINGS_APP_UPDATES_TEXT, online.appUpdatesAllowed,
                            blocked = SettingsRules.featureBlocked(online), tag = "switch-app-updates", indent = true, onBlocked = showReason, onChange = onCheckAppUpdates)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                // the status line stays ("Beamline 0.1.41 is ready to install"), the reason under it
                                LinkRow("Check for an update now", appUpdateLine, blocked = SettingsRules.checkNowBlocked(online, driving), keepDetail = true,
                                    onBlocked = showReason, onClick = onCheckAppNow)
                            }
                            // installing a downloaded update needs no internet: not blocked by the main switch
                            if (appUpdateReady && !driving) {
                                androidx.compose.material3.Button(onClick = onInstallApp, modifier = Modifier.padding(start = 8.dp)) { Text("Install") }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 10.dp))
                    Heading("About")
                    LinkRow("About Beamline", "Version, map data, licenses and the driver notice", onClick = onAbout)
                }
            }
            reason?.let { r -> ReasonBar(r.text, Modifier.align(Alignment.BottomCenter)) }
        }
    }
}

/**
 * A switch row. [blocked]: why it cannot be used now (P55): shown in place of [detail], the
 * switch dimmed, and a tap passes the reason to [onBlocked] instead of changing the setting.
 */
@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, blocked: String?, tag: String, indent: Boolean = false,
                      onBlocked: (String) -> Unit = {}, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = { on -> if (blocked == null) onChange(on) else onBlocked(blocked) })
            .padding(start = if (indent) 16.dp else 0.dp, top = 6.dp, bottom = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (blocked == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(blocked ?: detail, style = MaterialTheme.typography.bodySmall,
                color = if (blocked != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = blocked == null, modifier = Modifier.padding(start = 12.dp))
    }
}

private class Option(val label: String, val detail: String?, val selected: Boolean, val onSelect: () -> Unit)

@Composable
private fun Heading(title: String) {
    // P55 / F88: TalkBack's heading navigation stops at each section
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() })
}

/**
 * A row that opens something. [blocked] (P55): why it cannot now, shown in place of [detail] (or
 * under it with [keepDetail]) and passed to [onBlocked] on a tap instead of [onClick]. [busy]: no
 * tap at all (signing in).
 */
@Composable
private fun LinkRow(label: String, detail: String?, blocked: String? = null, keepDetail: Boolean = false, busy: Boolean = false,
                    onBlocked: (String) -> Unit = {}, onClick: () -> Unit) {
    val usable = blocked == null && !busy
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = !busy, role = Role.Button) { if (blocked != null) onBlocked(blocked) else onClick() }
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        if (blocked == null || keepDetail) detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // the reason in full contrast (the tester found the dim grey text weak)
        blocked?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun Group(title: String, options: List<Option>) {
    Heading(title)
    Column(Modifier.selectableGroup()) {
        options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = o.selected, onClick = o.onSelect, role = Role.RadioButton).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = o.selected, onClick = null)
                Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(o.label, style = MaterialTheme.typography.bodyLarge)
                    o.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(top = 10.dp))
}

/** P55: the reason on screen; [taps] makes every tap a new value, so its time starts again. */
private data class ReasonShown(val text: String, val taps: Int)

/**
 * P55: the reason a row cannot be used, after a tap on it, at the bottom of Settings. Material's
 * snackbar colours (dark by day, light by night, as the map's bar); a polite live region, so
 * TalkBack reads it when it appears (a double tap on a blocked row otherwise said nothing).
 */
@Composable
private fun ReasonBar(text: String, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface, shadowElevation = 6.dp,
        modifier = modifier.navigationBarsPadding().padding(16.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("settings-reason"),
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}
