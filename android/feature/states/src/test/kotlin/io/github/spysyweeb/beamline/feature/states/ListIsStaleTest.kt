package io.github.spysyweeb.beamline.feature.states

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When the list of states is fetched again: on opening States (0.1.32) and the daily map-update check (item 40). */
class ListIsStaleTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `opening States fetches the list unless it is under a minute old`() {
        val fresh = 60_000L // GetPacksCore.SCREEN_FRESH_MS
        assertTrue(listIsStale(null, now, fresh), "never fetched")
        assertFalse(listIsStale(now - 30_000, now, fresh), "half a minute old: kept")
        assertTrue(listIsStale(now - 60_000, now, fresh), "a minute old")
        assertTrue(listIsStale(now - 5 * 3_600_000L, now, fresh), "five hours old (before: kept for 6 h)")
        assertTrue(listIsStale(now + 10_000, now, fresh), "from the future: the clock moved back")
    }

    @Test
    fun `the daily check still waits a day`() {
        val day = 24 * 3_600_000L // GetPacksCore.UPDATE_CHECK_AFTER_MS
        assertFalse(listIsStale(now - 5 * 3_600_000L, now, day))
        assertTrue(listIsStale(now - day, now, day))
    }
}
