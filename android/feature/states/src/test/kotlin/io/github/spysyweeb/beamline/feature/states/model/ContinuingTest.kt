package io.github.spysyweeb.beamline.feature.states.model

import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.JobKind
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.JobProgress
import io.github.spysyweeb.beamline.download.PauseReason
import io.github.spysyweeb.beamline.download.WaitReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 1.1 step 7 and 1.4: "Continuing the download…" after the app continued a job, long enough to be seen. */
class ContinuingTest {
    private fun job(phase: JobPhase, speed: Long? = null, pause: PauseReason? = null, wait: WaitReason? = null) =
        DownloadJob("us-pa", 202611160619, "Pennsylvania", "PA", "2026-11-14", JobKind.INSTALL, false, false, true, phase,
            JobProgress(60_000_000, 548_481_239, "routing", speed), wait = wait, pause = pause)

    /** The line over job values arriving at the given times (ms after onAppVisible). */
    private fun run(vararg at: Pair<Long, DownloadJob?>): List<Boolean> {
        var s = Continuing.started()
        return at.map { (t, j) ->
            s = Continuing.next(s, j, 1_000_000L + t)
            s.on
        }
    }

    @Test
    fun aColdStartStillShowsItForThreeSecondsAfterBytesMove() {
        // Cold start after `am force-stop`: recover and CHECKING take about 2 s, the screen draws later still.
        // Before the review fix the 3 s counted from onAppVisible, so it had run out by the time bytes moved.
        val shown = run(
            0L to job(JobPhase.PAUSED, pause = PauseReason.APP_ENDED), // the value from before resume(), seen late
            300L to job(JobPhase.CHECKING),
            2_400L to job(JobPhase.DOWNLOADING),                       // first bytes, no speed yet
            3_400L to job(JobPhase.DOWNLOADING, speed = 1_900_000),    // moving: 3 s from here
            5_400L to job(JobPhase.DOWNLOADING, speed = 1_900_000),
            6_500L to job(JobPhase.DOWNLOADING, speed = 1_900_000),
        )
        assertEquals(listOf(true, true, true, true, true, false), shown)
    }

    @Test
    fun anythingElseEndsIt() {
        assertEquals(listOf(true, false), run(300L to job(JobPhase.CHECKING), 900L to job(JobPhase.PAUSED, pause = PauseReason.USER)))
        assertEquals(listOf(false), run(300L to job(JobPhase.WAITING, wait = WaitReason.NO_NETWORK)))
        assertEquals(listOf(false), run(300L to null))
        assertEquals(listOf(true, false), run(300L to job(JobPhase.CHECKING), 900L to job(JobPhase.FAILED)))
        // Off stays off: nothing but onAppVisible turns it on.
        assertFalse(Continuing.next(Continuing.State(), job(JobPhase.CHECKING), 0).on)
        assertTrue(Continuing.started().on)
    }
}
