package io.github.spysyweeb.beamline.feature.states.model

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** docs/architecture-downloads.md 1.1 step 5: the "Notifications are off" line comes after a refusal only. */
class NotificationsLineTest {

    private fun shown(enabled: Boolean, askable: Boolean, asked: Boolean, open: Boolean) =
        StatesPresenter.notificationsOffShown(enabled, askable, asked, open)

    @Test
    fun `not before the question is asked, nor while it is on screen`() {
        // the first Download on Android 13+: the progress view comes before the system's question
        assertFalse(shown(enabled = false, askable = true, asked = false, open = false), "before the question")
        assertFalse(shown(enabled = false, askable = true, asked = true, open = true), "the question on screen")
    }

    @Test
    fun `after a refusal, and when notifications are off in the system settings`() {
        assertTrue(shown(enabled = false, askable = true, asked = true, open = false), "refused")
        assertTrue(shown(enabled = false, askable = false, asked = false, open = false), "granted but switched off, or Android 12 and older")
    }

    @Test
    fun `never while notifications can be shown`() {
        assertFalse(shown(enabled = true, askable = false, asked = true, open = false))
        assertFalse(shown(enabled = true, askable = false, asked = false, open = false))
    }
}
