package io.github.spysyweeb.beamline.feature.states.model

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.download.CatalogState
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.FailReason
import io.github.spysyweeb.beamline.download.NetworkState
import io.github.spysyweeb.beamline.feature.states.StatesMode
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import java.time.ZoneId
import io.github.spysyweeb.beamline.download.JobKind
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.JobProgress
import io.github.spysyweeb.beamline.download.PauseReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 1.4 "A file is gone from the server": the progress view says "A newer Pennsylvania was published.
 * Downloading that one." after the engine switched the job (EngineStateTest.aGoneFileSwitchesToTheNewerBuild
 * checks that the engine publishes the switch as these values do), and only then.
 */
class JobSwitchTest {
    private fun job(phase: JobPhase, version: Long = 202611160419, id: String = "us-pa", kind: JobKind = JobKind.INSTALL, done: Long = 100) =
        DownloadJob(id, version, "Pennsylvania", "PA", "2026-11-14", kind, true, false, true, phase,
            JobProgress(done, 548_000_000, null, null), pause = if (phase == JobPhase.PAUSED) PauseReason.USER else null,
            fail = if (phase == JobPhase.FAILED) FailReason.NO_LONGER_OFFERED else null)

    /** The flag over a sequence of job values, as GetPacksCore's collector sees them. */
    private fun run(vararg jobs: DownloadJob?): List<Boolean> {
        var prev: DownloadJob? = null
        var flag = false
        return jobs.map { j ->
            flag = JobSwitch.flag(flag, prev, j)
            prev = j
            flag
        }
    }

    @Test
    fun aSwitchSetsTheFlagUntilTheJobEnds() {
        val newer = 202611160530L
        val flags = run(
            job(JobPhase.CHECKING), job(JobPhase.DOWNLOADING, done = 90_000_000),
            job(JobPhase.CHECKING, newer, done = 0), // the engine's switch: same pack, a newer build, no end in between
            job(JobPhase.DOWNLOADING, newer, done = 5_000_000), job(JobPhase.UNPACKING, newer), job(JobPhase.INSTALLING, newer),
            job(JobPhase.DONE, newer),
        )
        assertEquals(listOf(false, false, true, true, true, true, false), flags)
        // The text the flag brings (StatesPresenter.jobUi): checked on the presenter as well.
        assertTrue(JobSwitch.isSwitch(job(JobPhase.WAITING), job(JobPhase.CHECKING, newer)))
    }

    @Test
    fun notASwitch() {
        val newer = 202611160530L
        // A finished install, then the user starts the update: a new job, not a switch.
        assertEquals(listOf(false, false, false), run(job(JobPhase.DONE), null, job(JobPhase.CHECKING, newer, kind = JobKind.UPDATE)))
        assertFalse(JobSwitch.isSwitch(job(JobPhase.DONE), job(JobPhase.CHECKING, newer)))
        assertFalse(JobSwitch.isSwitch(job(JobPhase.FAILED), job(JobPhase.CHECKING, newer)))
        // Another state, the same build, an older build, another kind.
        assertFalse(JobSwitch.isSwitch(job(JobPhase.DOWNLOADING), job(JobPhase.CHECKING, newer, id = "us-oh")))
        assertFalse(JobSwitch.isSwitch(job(JobPhase.DOWNLOADING), job(JobPhase.DOWNLOADING)))
        assertFalse(JobSwitch.isSwitch(job(JobPhase.DOWNLOADING, newer), job(JobPhase.CHECKING)))
        assertFalse(JobSwitch.isSwitch(job(JobPhase.DOWNLOADING), job(JobPhase.CHECKING, newer, kind = JobKind.ADD_OPTIONAL)))
        // A job found at the next start (recover) is never "switched".
        assertEquals(listOf(false), run(job(JobPhase.PAUSED, newer)))
    }

    @Test
    fun cancelDismissOrAnotherJobClearsIt() {
        val newer = 202611160530L
        assertEquals(listOf(false, true, true, false), run(job(JobPhase.DOWNLOADING), job(JobPhase.CHECKING, newer), job(JobPhase.PAUSED, newer), null))
        assertEquals(listOf(false, true, false), run(job(JobPhase.DOWNLOADING), job(JobPhase.CHECKING, newer), job(JobPhase.FAILED, newer)))
        assertEquals(listOf(false, true, false), run(job(JobPhase.DOWNLOADING), job(JobPhase.CHECKING, newer), job(JobPhase.CHECKING, id = "us-oh")))
    }

    @Test
    fun theProgressViewSaysIt() {
        val newer = 202611160530L
        val input = StatesInput(
            mode = StatesMode.FIRST_RUN, names = mapOf("PA" to "Pennsylvania"), catalog = CatalogState.Off,
            installed = InstalledSet(scanned = true), job = null, network = NetworkState(connected = true, unmetered = true),
            units = UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL), nowMs = 1_794_830_400_000L,
            zone = ZoneId.of("America/New_York"), freeBytes = 24_000_000_000, moves = true,
        )
        val switched = StatesPresenter.jobUi(job(JobPhase.DOWNLOADING, newer), input.copy(switched = true))
        assertEquals("A newer Pennsylvania was published. Downloading that one.", switched.message)
        assertEquals(null, StatesPresenter.jobUi(job(JobPhase.DOWNLOADING, newer), input).message)
    }
}
