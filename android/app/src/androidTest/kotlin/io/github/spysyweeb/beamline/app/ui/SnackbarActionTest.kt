package io.github.spysyweeb.beamline.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P58 (review 0.1.40): the map's bar with an action keeps its action whole however long its line is. "{name} removed"
 * can hold a saved name of 40 characters; the line took the whole row and Undo, the only way back, got no room.
 * A made-up saved name. Run as MapChromeLandscapeTest says (`am instrument`, never :app:connectedDebugAndroidTest).
 */
@RunWith(AndroidJUnit4::class)
class SnackbarActionTest {
    @get:Rule
    val compose = createComposeRule()

    private val line = "Grandma and Grandpa's lake house cabin removed"
    private val px = 0.5.dp

    private fun bar(w: Dp, fontScale: Float, text: String = line) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            BeamlineTheme(night = false) {
                Box(Modifier.requiredSize(w, 640.dp).testTag("screen")) {
                    Snackbar(text, night = false, action = "Undo" to {},
                        modifier = Modifier.align(Alignment.BottomCenter).snackbarPlace(start = 0.dp, bottom = 88.dp))
                }
            }
        }
    }

    private fun screen(): DpRect = compose.onNodeWithTag("screen").getUnclippedBoundsInRoot()
    private fun button(): DpRect = compose.onNode(hasText("Undo") and hasClickAction()).getUnclippedBoundsInRoot()
    private fun word(): DpRect = compose.onNode(hasText("Undo"), useUnmergedTree = true).getUnclippedBoundsInRoot()
    private fun lineAt(text: String): DpRect = compose.onNode(hasText(text)).getUnclippedBoundsInRoot()

    /** Undo whole: its button at least 48 dp wide inside the screen, its word on one line ([oneLineDp] at most) at full width. */
    private fun assertUndoWhole(w: Dp, oneLineDp: Dp, wordDp: Dp) {
        val s = screen()
        val b = button()
        val u = word()
        assertTrue("the Undo button is ${b.right - b.left} wide on a $w screen", b.right - b.left >= 48.dp - px)
        assertTrue("the Undo button lies at ${b.left - s.left}..${b.right - s.left} of $w", b.left >= s.left - px && b.right <= s.right + px)
        assertTrue("the word Undo is ${u.right - u.left} wide", u.right - u.left >= wordDp)
        assertTrue("the word Undo is ${u.bottom - u.top} tall: one line", u.bottom - u.top <= oneLineDp)
        // the bar keeps a margin from the screen's edges, as the search screen's Undo bar does (the button's own end
        // padding is 4 dp)
        val t = lineAt(line)
        assertTrue("the Undo button ends ${s.right - b.right} from the edge", s.right - b.right >= 4.dp + 8.dp - px)
        assertTrue("the line ends at ${t.right - s.left}, before Undo at ${u.left - s.left}", t.right <= u.left + px)
    }

    @Test
    fun undoStaysWholeBesideALongNameOnANarrowPhone() {
        bar(360.dp, 1f)
        assertUndoWhole(360.dp, oneLineDp = 30.dp, wordDp = 28.dp)
    }

    @Test
    fun undoStaysWholeWithLargerText() {
        bar(411.dp, 1.3f)
        assertUndoWhole(411.dp, oneLineDp = 38.dp, wordDp = 36.dp)
    }

    @Test
    fun aShortLineKeepsUndoBesideIt() {
        bar(411.dp, 1f, text = "Mom's removed")
        val t = lineAt("Mom's removed")
        val b = button()
        assertTrue("the Undo button is ${b.right - b.left} wide", b.right - b.left >= 48.dp - px)
        assertTrue("Undo starts at the line's end", b.left >= t.right - px)
        assertTrue("the short bar stays narrow: ${b.right - t.left}", b.right - t.left < 300.dp)
    }
}
