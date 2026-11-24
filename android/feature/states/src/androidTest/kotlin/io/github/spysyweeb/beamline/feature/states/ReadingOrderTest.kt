package io.github.spysyweeb.beamline.feature.states

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.ComponentName
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.download.Catalog
import io.github.spysyweeb.beamline.download.CatalogLicence
import io.github.spysyweeb.beamline.download.CatalogPack
import io.github.spysyweeb.beamline.download.CatalogPart
import io.github.spysyweeb.beamline.download.CatalogState
import io.github.spysyweeb.beamline.download.CatalogView
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.JobKind
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.JobProgress
import io.github.spysyweeb.beamline.download.NetworkState
import io.github.spysyweeb.beamline.download.TransferFile
import io.github.spysyweeb.beamline.feature.states.map.StateShapes
import io.github.spysyweeb.beamline.feature.states.model.StatesInput
import io.github.spysyweeb.beamline.feature.states.model.StatesPresenter
import io.github.spysyweeb.beamline.feature.states.model.StatesUi
import io.github.spysyweeb.beamline.feature.states.model.Texts
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** Test APK only: enabled by ReadingOrderTest so that an accessibility service runs, as TalkBack would. */
class ReadingOrderProbe : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

/**
 * docs/architecture-downloads.md 10.3 and 6.7: "TalkBack reaches the bottom bar right after the
 * title and reads the list rows and the progress." The emulator image has no TalkBack, so this test
 * reads what TalkBack is given: with an accessibility service enabled (ReadingOrderProbe), Compose
 * links every node a screen reader stops at to the next one (AccessibilityNodeInfo.traversalBefore),
 * and TalkBack follows those links. The test follows them from the title through UiAutomation and
 * checks the order. Not checked: TalkBack's own speech and gestures.
 *
 * Run: sh tools/round/gradle.sh g :feature:states:connectedDebugAndroidTest (emulator beamline_g up).
 * The orders read are logged under the tag BeamlineReadingOrder.
 */
@RunWith(AndroidJUnit4::class)
class ReadingOrderTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var savedServices: String? = null
    private var savedEnabled: String? = null

    private fun ua(): UiAutomation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private fun shell(cmd: String): String =
        ua().executeShellCommand(cmd).let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString().trim() }
        }

    @Before
    fun probeOn() {
        savedServices = shell("settings get secure enabled_accessibility_services")
        savedEnabled = shell("settings get secure accessibility_enabled")
        val probe = ComponentName(instrumentation.context, ReadingOrderProbe::class.java).flattenToString()
        shell("settings put secure enabled_accessibility_services $probe")
        shell("settings put secure accessibility_enabled 1")
        val am = context.getSystemService(AccessibilityManager::class.java)
        val until = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < until &&
            !(am.isEnabled && am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isNotEmpty())
        ) {
            Thread.sleep(200)
        }
        assertTrue("no accessibility service came up", am.isEnabled && am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isNotEmpty())
    }

    @After
    fun probeOff() {
        fun restore(key: String, v: String?) =
            if (v == null || v == "null" || v.isEmpty()) shell("settings delete secure $key") else shell("settings put secure $key $v")
        restore("enabled_accessibility_services", savedServices)
        restore("accessibility_enabled", savedEnabled)
    }

    // ------------------------------------------------------------------ inputs (the presenter's own texts)

    private val shapes: StateShapes by lazy {
        context.assets.open("states/us_states.json").use { StateShapes.parse(it.readBytes().decodeToString()) }
    }
    private val hex = "a".repeat(64)
    private val now = 1_794_830_400_000L // Nov 16, 2026, 12:00 UTC

    private fun part(name: String, file: String, bytes: Long, transfer: Long) =
        CatalogPart(name, file, false, bytes, hex, TransferFile("../us-pa-1/$file.gz", if (transfer == bytes) "identity" else "gzip", transfer, hex), "© OpenStreetMap contributors")

    private val pa = CatalogPack(
        "us-pa", "PA", "Pennsylvania", 202611160419, 3, "2026-11-14", "© OpenStreetMap contributors", Bounds(-80.51985, 39.7198, -74.68956, 42.51607),
        TransferFile("../us-pa-1/manifest.json", "identity", 1237, hex),
        listOf(part("search", "search.sqlite", 154_705_920, 63_346_929), part("routing", "routing.tar", 634_429_440, 240_430_781), part("map", "map.pmtiles", 244_703_514, 244_703_514)),
    )
    private val catalog = CatalogState.Ready(
        CatalogView(
            Catalog(1, "2026-11-16T08:55:21Z", CatalogLicence("ODbL-1.0", "Open Database License 1.0", "https://opendatacommons.org/licenses/odbl/1-0/", "Map data © OpenStreetMap contributors, available under the Open Database License."), listOf(pa), emptyList()),
            now, fromCache = false,
        ),
    )

    private fun ui(mode: StatesMode, job: DownloadJob? = null, installed: InstalledSet = InstalledSet(scanned = true)): StatesUi = StatesPresenter.present(
        StatesInput(
            mode = mode, names = shapes.states.associate { it.code to it.name }, neighbours = shapes.states.associate { it.code to it.neighbours },
            catalog = catalog, installed = installed, job = job, network = NetworkState(connected = true, unmetered = true),
            units = UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL), nowMs = now, zone = ZoneId.of("America/New_York"),
            freeBytes = 9_900_000_000, moves = true,
        ),
    )

    private val downloading = DownloadJob(
        "us-pa", 202611160419, "Pennsylvania", "PA", "2026-11-14", JobKind.INSTALL, false, false, true, JobPhase.DOWNLOADING,
        JobProgress(238_000_000, 548_481_239, "routing", 1_900_000),
    )

    // ------------------------------------------------------------------ reading the order

    /** What a screen reader says for a stop: its own description or text, else its descendants' that are not stops themselves. */
    private fun spoken(n: AccessibilityNodeInfo): String {
        val own = listOfNotNull(n.contentDescription?.toString(), n.text?.toString()).filter { it.isNotBlank() }
        if (own.isNotEmpty()) return own.joinToString(" ")
        val parts = ArrayList<String>()
        fun walk(c: AccessibilityNodeInfo) {
            for (i in 0 until c.childCount) {
                val k = c.getChild(i) ?: continue
                listOfNotNull(k.contentDescription?.toString(), k.text?.toString()).filter { it.isNotBlank() }.let { parts += it }
                walk(k)
            }
        }
        walk(n)
        return parts.joinToString(", ").ifEmpty { "(${n.className})" }
    }

    private fun find(n: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (match(n)) return n
        for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c, match)?.let { return it } }
        return null
    }

    /**
     * The order a screen reader moves in, from the node whose text is [first]: each stop's
     * traversalBefore names the next one (Compose sets them from its traversal order, which
     * traversalIndex and isTraversalGroup decide).
     */
    private fun readingOrder(name: String, first: String): List<String> {
        val until = SystemClock.uptimeMillis() + 10_000
        var why = "no window"
        while (SystemClock.uptimeMillis() < until) {
            compose.waitForIdle()
            val ua = ua()
            runCatching { ua.waitForIdle(200, 2_000) }
            if (Build.VERSION.SDK_INT >= 34) ua.clearCache()
            val root = ua.rootInActiveWindow
            val start = root?.let { r -> find(r) { it.text?.toString() == first } }
            if (start == null) {
                why = "no node with the text '$first'"
            } else if (start.traversalBefore == null) {
                why = "'$first' has no traversal link (Compose computes them only while an accessibility service runs)"
            } else {
                assertNull("$name: something is read before '$first'", start.traversalAfter)
                val out = ArrayList<String>()
                val seen = HashSet<AccessibilityNodeInfo>()
                var n: AccessibilityNodeInfo? = start
                while (n != null && seen.add(n) && out.size < 400) {
                    out += spoken(n)
                    n = n.traversalBefore
                }
                out.forEachIndexed { i, s -> Log.i(TAG, "$name ${i + 1}: $s") }
                return out
            }
            Thread.sleep(250)
        }
        throw AssertionError("$name: $why")
    }

    private fun List<String>.at(text: String): Int = indexOfFirst { text in it }

    // ------------------------------------------------------------------ the checks

    @Test
    fun firstRunReadsTheBarRightAfterTheTitleThenTheRows() {
        val ui = ui(StatesMode.FIRST_RUN)
        compose.setContent { BeamlineTheme(night = false) { StatesBody(ui, shapes, on = {}, onMapTap = {}) } }
        val order = readingOrder("first-run", Texts.CHOOSE_TITLE)
        assertEquals(Texts.CHOOSE_TITLE, order[0])
        assertTrue("$order", Texts.USE_FILE in order[1])
        assertTrue("$order", Texts.NOT_NOW in order[2])
        val intro = order.at(Texts.CHOOSE_INTRO)
        val map = order.at(Texts.MAP_DESCRIPTION)
        val row = order.indexOfFirst { it.startsWith("Pennsylvania") && "download" in it }
        assertTrue("$order", intro > 2 && map > intro && row > map)
        assertTrue("the list rows are read: $order", order.at(Texts.AVAILABLE) in (map + 1) until row)
    }

    @Test
    fun aDownloadReadsTheProgressAfterTheBar() {
        val ui = ui(StatesMode.FIRST_RUN, job = downloading)
        compose.setContent { BeamlineTheme(night = false) { StatesBody(ui, shapes, on = {}, onMapTap = {}) } }
        val order = readingOrder("first-run-downloading", Texts.CHOOSE_TITLE)
        assertTrue("$order", Texts.USE_FILE in order[1] && Texts.LOOK_AT_MAP in order[2])
        val title = order.at(Texts.downloading("Pennsylvania"))
        val line = order.at("238 MB of 548 MB")
        val map = order.at(Texts.MAP_DESCRIPTION)
        assertTrue("the progress is read, before the map: $order", title > 2 && line > title && map > line)
        assertTrue("$order", order.at(Texts.PAUSE) > line && order.at(Texts.CANCEL) > line)
    }

    @Test
    fun theStatesScreenReadsTheBarRightAfterTheTitle() {
        val installed = InstalledSet(
            scanned = true,
            packs = listOf(
                PackInfo(
                    "us-pa", "Pennsylvania", "PA", "2026-11-14", Bounds(-80.5, 39.7, -74.7, 42.5), "© OpenStreetMap contributors", "/p/us-pa", "/p/us-pa/map.pmtiles",
                    0, 14, "/p/us-pa/search.sqlite", null, false, null, null, true, true, 1_033_800_000, 202611160419,
                ),
            ),
        )
        val ui = ui(StatesMode.MANAGE, installed = installed)
        compose.setContent { BeamlineTheme(night = false) { StatesBody(ui, shapes, on = {}, onMapTap = {}) } }
        val order = readingOrder("states", Texts.STATES_TITLE)
        assertTrue("$order", Texts.USE_FILE in order[1])
        assertTrue("$order", order.at(Texts.MAP_DESCRIPTION) > 1)
        val onPhone = order.at(Texts.ON_THIS_PHONE)
        assertTrue("the installed row is read: $order", onPhone > 1 && order.indexOfFirst { it.startsWith("Pennsylvania") } > onPhone)
    }

    /**
     * Why the bar is a traversal group: the same layout with the bar as a plain Row carrying
     * traversalIndex = -1 (the code before the second review) is read with the bar after the list,
     * because Compose dissolves a Row that is no group into its buttons, whose index is 0.
     */
    @Test
    fun aBarThatIsNoTraversalGroupIsReadLast() {
        compose.setContent {
            BeamlineTheme(night = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize()) {
                        Text("Old layout", Modifier.semantics { heading(); traversalIndex = -2f })
                        LazyColumn(Modifier.weight(1f)) { items(5) { i -> Text("Row $i") } }
                        Row(Modifier.fillMaxWidth().semantics { traversalIndex = -1f }) {
                            TextButton(onClick = {}) { Text("Bar button") }
                        }
                    }
                }
            }
        }
        val order = readingOrder("old-layout", "Old layout")
        assertTrue("$order", order.at("Bar button") > order.at("Row 4"))
    }

    private companion object {
        const val TAG = "BeamlineReadingOrder"
    }
}
