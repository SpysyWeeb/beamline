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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.feature.states.map.StateShapes
import io.github.spysyweeb.beamline.feature.states.model.HouseNumbersUi
import io.github.spysyweeb.beamline.feature.states.model.StatesEvent
import io.github.spysyweeb.beamline.feature.states.model.StatesUi
import io.github.spysyweeb.beamline.feature.states.model.Texts
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    private val shapes: StateShapes get() = StatesTestInputs.shapes

    private fun ui(mode: StatesMode, job: DownloadJob? = null, installed: InstalledSet = InstalledSet(scanned = true), query: String = ""): StatesUi =
        StatesTestInputs.ui(mode, job, installed, query)

    private val downloading get() = StatesTestInputs.downloading

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
    private fun readingOrder(name: String, first: String): List<String> = readingOrder(name, "the text '$first'") { it.text?.toString() == first }

    /** The same, from the first node (depth first) that [match] accepts; [what] names it in a failure. */
    private fun readingOrder(name: String, what: String, match: (AccessibilityNodeInfo) -> Boolean): List<String> {
        val until = SystemClock.uptimeMillis() + 10_000
        var why = "no window"
        while (SystemClock.uptimeMillis() < until) {
            compose.waitForIdle()
            val ua = ua()
            runCatching { ua.waitForIdle(200, 2_000) }
            if (Build.VERSION.SDK_INT >= 34) ua.clearCache()
            val root = ua.rootInActiveWindow
            val start = root?.let { r -> find(r, match) }
            if (start == null) {
                why = "no node with $what"
            } else if (start.traversalBefore == null) {
                why = "$what has no traversal link (Compose computes them only while an accessibility service runs)"
            } else {
                assertNull("$name: something is read before $what", start.traversalAfter)
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

    /** P13: the top bar is read first (Back, then the title), then the bar fixed to the bottom. */
    @Test
    fun theStatesScreenReadsTheBarRightAfterTheTitle() {
        val ui = ui(StatesMode.MANAGE, installed = StatesTestInputs.paInstalled())
        compose.setContent { BeamlineTheme(night = false) { StatesBody(ui, shapes, on = {}, onMapTap = {}) } }
        val order = readingOrder("states", "a Back button") { it.isClickable && spoken(it) == Texts.BACK }
        assertEquals("$order", Texts.BACK, order[0])
        assertEquals("$order", Texts.STATES_TITLE, order[1])
        assertTrue("$order", Texts.USE_FILE in order[2])
        assertTrue("$order", order.at(Texts.MAP_DESCRIPTION) > 1)
        val onPhone = order.at(Texts.ON_THIS_PHONE)
        assertTrue("the installed row is read: $order", onPhone > 1 && order.indexOfFirst { it.startsWith("Pennsylvania") } > onPhone)
    }

    /**
     * P64 (audit F71): every node a screen reader can act on has a name, also the search field with
     * words typed in (a placeholder is not drawn then; a label is).
     */
    @Test
    fun everyTappableNodeHasAName() {
        val ui = ui(StatesMode.MANAGE, installed = StatesTestInputs.paInstalled(), query = "Oio")
        compose.setContent { BeamlineTheme(night = false) { StatesBody(ui, shapes, on = {}, onMapTap = {}) } }
        compose.waitForIdle()
        val nodes = compose.onAllNodes(hasClickAction() or isToggleable() or hasSetTextAction()).fetchSemanticsNodes()
        assertTrue("no tappable node at all", nodes.isNotEmpty())
        val nameless = nodes.filter { n ->
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
            val description = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
            text.isBlank() && description.isBlank()
        }
        assertTrue("nodes without a name: ${nameless.map { it.config }}", nameless.isEmpty())
    }

    /** P64 (audit F71): the download sheet's house-number box is one stop, read with its name. */
    @Test
    fun theHouseNumbersBoxIsReadWithItsName() {
        val events = ArrayList<StatesEvent>()
        compose.setContent {
            BeamlineTheme(night = false) { HouseNumbersRow(HouseNumbersUi(true, Texts.HOUSE_NUMBERS, "Adds 132 MB", null), on = { events += it }) }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        compose.onNode(isToggleable()).assert(hasText(Texts.HOUSE_NUMBERS)).assertIsOn().performClick()
        compose.runOnIdle { assertEquals(listOf<StatesEvent>(StatesEvent.HouseNumbers(false)), events) }
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
