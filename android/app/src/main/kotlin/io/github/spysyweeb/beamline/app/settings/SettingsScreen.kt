package io.github.spysyweeb.beamline.app.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.spysyweeb.beamline.app.online.OnlineSettings
import io.github.spysyweeb.beamline.app.online.OnlineTexts
import io.github.spysyweeb.beamline.settings.MapStyle
import io.github.spysyweeb.beamline.settings.Settings
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.settings.Units
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons

/**
 * The user's settings, stored by :core:settings (docs/architecture.md 5.4 item 8): map style, frame
 * rate (Screen rate or 60: the owner removed 30 on 2026-11-16), guidance sound, units; the group
 * "Places" with "Saved places" (docs/architecture-saved-places.md 5.6: "Not while guiding" in
 * GUIDING, REROUTING and ARRIVED) and States; "Online features" (docs/online-design.md section 2,
 * batch O1: the main switch, "Contribute to OpenStreetMap", "My reports"); About.
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(BeamlineIcons.Back, contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge)
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
                LinkRow("Saved places", if (driving) "Not while guiding" else null, enabled = !driving, onClick = onSavedPlaces)
                LinkRow("States", "Add, update or remove the states on this phone", onClick = onStates)
                HorizontalDivider(Modifier.padding(top = 10.dp))
                // Online features: the main switch turns every feature on; each can then be turned off
                // alone. Batch O4 adds "Find places online" under it, O2 the OSM account line.
                Heading(OnlineTexts.SETTINGS_MAIN)
                SwitchRow(OnlineTexts.SETTINGS_MAIN, if (online.online) OnlineTexts.SETTINGS_MAIN_ON else OnlineTexts.SETTINGS_MAIN_OFF,
                    online.online, enabled = true, tag = "switch-online", onChange = onOnline)
                SwitchRow(OnlineTexts.SETTINGS_OSM, OnlineTexts.SETTINGS_OSM_TEXT, online.osmAllowed, enabled = online.online,
                    tag = "switch-osm", indent = true, onChange = onContributeOsm)
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
                        enabled = online.osmAllowed && !signingIn && !driving, onClick = onSignIn)
                }
                LinkRow(OnlineTexts.MY_REPORTS, if (driving) "Not while guiding" else reportsText, enabled = !driving, onClick = onReports)
                SwitchRow(OnlineTexts.SETTINGS_MAP_UPDATES, OnlineTexts.SETTINGS_MAP_UPDATES_TEXT, online.mapUpdatesAllowed,
                    enabled = online.online, tag = "switch-map-updates", indent = true, onChange = onCheckMapUpdates)
                HorizontalDivider(Modifier.padding(top = 10.dp))
                Heading("About")
                LinkRow("About Beamline", "Version, map data, licenses and the driver notice", onClick = onAbout)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, enabled: Boolean, tag: String, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(start = if (indent) 16.dp else 0.dp, top = 6.dp, bottom = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(start = 12.dp))
    }
}

private class Option(val label: String, val detail: String?, val selected: Boolean, val onSelect: () -> Unit)

@Composable
private fun Heading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun LinkRow(label: String, detail: String?, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
