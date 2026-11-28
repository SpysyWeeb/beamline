package io.github.spysyweeb.beamline.feature.about

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The screens of AboutScreens.kt. They draw with the caller's MaterialTheme (BeamlineTheme, day or
// night as the map) and fill the window, keeping clear of the system bars and the cut-out.

@Composable
internal fun FirstRunNoticeContent(onAccept: () -> Unit, modifier: Modifier) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    AppIcon(size = 64.dp)
                    Spacer(Modifier.size(28.dp))
                    Text(
                        AboutTexts.FIRST_RUN_NOTICE,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.size(32.dp))
                // At the bottom, in reach of the thumb; tall enough for a glance and a tap.
                Button(onClick = onAccept, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(AboutTexts.FIRST_RUN_ACCEPT, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** DriverNoticeScreen (AboutScreens.kt), play test P59: a page like the licences', not the first-run step. */
@Composable
internal fun DriverNoticeScreenContent(onBack: () -> Unit, modifier: Modifier) {
    BackHandler(onBack = onBack)
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(AboutTexts.DRIVER_NOTICE_ROW, onBack)
            Text(
                AboutTexts.FIRST_RUN_NOTICE,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
internal fun AboutScreenContent(ui: AboutUi, onEvent: (AboutEvent) -> Unit, modifier: Modifier) {
    BackHandler { onEvent(AboutEvent.Back) }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(AboutTexts.ABOUT_TITLE) { onEvent(AboutEvent.Back) }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item("header") {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(size = 64.dp)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(ui.appName, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                            Detail(AboutTexts.VERSION_PREFIX + ui.versionText)
                            ui.buildText?.let { Detail(it) }
                            Detail(ui.styleText)
                        }
                    }
                }
                item("maps") { SectionTitle(AboutTexts.INSTALLED_MAPS) }
                if (ui.packs.isEmpty()) {
                    item("no-maps") { Detail(AboutTexts.NO_MAPS, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                } else {
                    items(ui.packs) { pack ->
                        Row2(pack.name, AboutTexts.OSM_SNAPSHOT_PREFIX + pack.osmSnapshot + " · " + pack.sizeText)
                    }
                }
                item("credits") { SectionTitle(AboutTexts.DATA_CREDITS) }
                items(ui.dataCredits) { credit ->
                    Text(
                        credit,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
                item("divider") { HorizontalDivider(Modifier.padding(top = 16.dp)) }
                item("licences") { Row2(AboutTexts.LICENCES_ROW, AboutTexts.LICENCES_ROW_DETAIL) { onEvent(AboutEvent.OpenLicences) } }
                item("notice") { Row2(AboutTexts.DRIVER_NOTICE_ROW, AboutTexts.DRIVER_NOTICE_ROW_DETAIL) { onEvent(AboutEvent.OpenDriverNotice) } }
            }
        }
    }
}

private const val LIST = -1
private const val ALL = -2

@Composable
internal fun LicencesScreenContent(notices: Notices, onBack: () -> Unit, modifier: Modifier) {
    var open by rememberSaveable { mutableIntStateOf(LIST) }
    if (open >= notices.entries.size) open = LIST
    BackHandler { if (open == LIST) onBack() else open = LIST }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (open) {
                LIST -> {
                    TopBar(AboutTexts.LICENCES_TITLE, onBack)
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item("all") { Row2(AboutTexts.ALL_NOTICES_ROW, null) { open = ALL } }
                        item("divider") { HorizontalDivider() }
                        itemsIndexed(notices.entries) { i, e ->
                            Row2(listOfNotNull(e.name, e.version).joinToString(" "), e.licence) { open = i }
                        }
                    }
                }
                ALL -> {
                    TopBar(AboutTexts.ALL_NOTICES_ROW) { open = LIST }
                    AssetText(notices.fullTextAsset, header = emptyList())
                }
                else -> {
                    val e = notices.entries[open]
                    TopBar(e.name) { open = LIST }
                    AssetText(
                        e.textAsset,
                        header = listOfNotNull(
                            e.version?.let { AboutTexts.VERSION_PREFIX + it },
                            AboutTexts.LICENCE_PREFIX + e.licence,
                            e.copyright,
                            e.url,
                        ),
                    )
                }
            }
        }
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data object Missing : Loaded
    data class Text(val paragraphs: List<String>) : Loaded
}

/** A licence text from the module's assets, reflowed into paragraphs for a phone's width. */
@Composable
private fun AssetText(asset: String, header: List<String>) {
    val context = LocalContext.current
    val loaded by produceState<Loaded>(Loaded.Loading, asset) {
        value = withContext(Dispatchers.IO) {
            try {
                Loaded.Text(reflow(context.assets.open(asset).bufferedReader().use { it.readText() }))
            } catch (_: java.io.IOException) {
                Loaded.Missing
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(header) { line -> Detail(line, Modifier.padding(bottom = 4.dp)) }
        item("gap") { Spacer(Modifier.size(12.dp)) }
        when (val l = loaded) {
            Loaded.Loading -> item("loading") { Detail(AboutTexts.LOADING) }
            Loaded.Missing -> item("missing") { Detail(AboutTexts.TEXT_MISSING) }
            is Loaded.Text -> items(l.paragraphs) { p ->
                Text(
                    p,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
    }
}

/**
 * Paragraphs are separated by blank lines and by rule lines (`====`, `----`). Inside one, a line of
 * [WRAPPED_LINE] characters or more is prose wrapped at the file's width and is joined with the
 * next; a shorter line (a heading, a list's line, a notice's name or licence) keeps its break.
 */
internal fun reflow(text: String): List<String> {
    val paragraphs = mutableListOf<String>()
    val current = StringBuilder()
    var joinNext = false
    fun flush() {
        if (current.isNotBlank()) paragraphs.add(current.toString().trim())
        current.clear()
        joinNext = false
    }
    for (raw in text.replace("\r\n", "\n").lines()) {
        val line = raw.trim()
        if (line.isEmpty() || RULE.matches(line)) {
            flush()
            continue
        }
        if (current.isNotEmpty()) current.append(if (joinNext) " " else "\n")
        current.append(line)
        joinNext = raw.trimEnd().length >= WRAPPED_LINE
    }
    flush()
    return paragraphs
}

private const val WRAPPED_LINE = 60
private val RULE = Regex("[=\\-_*]{3,}")

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(BeamlineIcons.Back, contentDescription = AboutTexts.BACK, tint = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.width(4.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Detail(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A list row of one or two lines, 56 dp or taller; clickable when [onClick] is given. */
@Composable
private fun Row2(title: String, detail: String?, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
        if (detail != null) Detail(detail)
    }
}
