package io.github.spysyweeb.beamline.feature.about

import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Play test P29: while the reveal covers the window it takes every touch, but the controls under it
 * (the map's Search and Settings, the resume card) were still offered to screen readers and to
 * uiautomator, which then "tapped" them into the reveal. The reveal now stands for the whole
 * window ("Beamline is starting"), so the controls it covers are left out until it has gone.
 *
 * Reads the tree TalkBack and uiautomator get (UiAutomation.rootInActiveWindow), not Compose's
 * own test tree, which lists covered nodes too.
 *
 * Run: sh tools/round/gradle.sh a :feature:about:connectedDebugAndroidTest (emulator beamline_a up).
 */
@RunWith(AndroidJUnit4::class)
class SplashRevealSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    @Composable
    private fun Screen(reveal: Boolean) {
        BeamlineTheme(night = false) {
            Box(Modifier.fillMaxSize()) {
                Button(onClick = {}, modifier = Modifier.align(Alignment.TopEnd)) { Text(CONTROL) }
                if (reveal) SplashReveal(mapReady = false, onFinished = {}, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test
    fun coveredControlsAreNotOffered() {
        // The reveal stays on its first frame (fully opaque); with the clock running it would
        // finish and turn transparent in virtual time.
        compose.mainClock.autoAdvance = false
        compose.setContent { Screen(reveal = true) }
        val texts = windowTexts { it.any { s -> s == AboutTexts.STARTING || s == CONTROL } }
        assertTrue("the covered control is offered: $texts", CONTROL !in texts)
        assertEquals("the reveal's own node: $texts", 1, texts.count { it == AboutTexts.STARTING })
    }

    /** The control: without the reveal the same button is in the tree (the method reads Compose's nodes). */
    @Test
    fun withoutTheRevealTheControlIsOffered() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Screen(reveal = false) }
        val texts = windowTexts { CONTROL in it }
        assertTrue("$texts", CONTROL in texts)
        assertTrue("$texts", AboutTexts.STARTING !in texts)
    }

    /**
     * Every text and description in the active window's accessibility tree; polls up to 5 s until
     * [ready] says the tree is the one on show (the first query after a change can come too early).
     */
    private fun windowTexts(ready: (List<String>) -> Boolean): List<String> {
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        var texts: List<String> = emptyList()
        val until = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < until) {
            compose.waitForIdle()
            runCatching { ua.waitForIdle(200, 2_000) }
            if (Build.VERSION.SDK_INT >= 34) ua.clearCache()
            ua.rootInActiveWindow?.let { root ->
                texts = ArrayList<String>().also { out -> collect(root, out) }
                if (ready(texts)) return texts
            }
            Thread.sleep(250)
        }
        return texts
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<String>) {
        n.text?.toString()?.let { out += it }
        n.contentDescription?.toString()?.let { out += it }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out) }
    }

    private companion object {
        const val CONTROL = "Under the reveal"
    }
}
