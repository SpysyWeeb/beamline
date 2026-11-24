package io.github.spysyweeb.beamline.feature.states.model

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.data.PackIssue
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.FailReason
import io.github.spysyweeb.beamline.download.JobKind
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.JobProgress
import io.github.spysyweeb.beamline.download.PauseReason
import io.github.spysyweeb.beamline.feature.states.UiChoices
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** docs/architecture-downloads.md 10.2: GetPacks.showFirstRunPicker (1.1 step 2, 1.5) and the remembered choice. */
class FirstRunTest {
    private val fresh = UiChoices()
    private val scanned = InstalledSet(generation = 1, scanned = true)

    private fun job(phase: JobPhase, firstRun: Boolean = true, pause: PauseReason? = null, fail: FailReason? = null) =
        DownloadJob("us-pa", 202611152241, "Pennsylvania", "PA", "2026-11-14", JobKind.INSTALL, true, false, firstRun, phase,
            JobProgress(238_000_000, 579_483_446, null, null), pause = pause, fail = fail)

    private val pa = PackInfo(
        "us-pa", "Pennsylvania", "PA", "2026-11-14", Bounds(-80.5, 39.7, -74.7, 42.5), "© OpenStreetMap contributors", "/p/us-pa",
        "/p/us-pa/map.pmtiles", 0, 14, null, null, false, null, null, true, true, 1_000_000_000, 202611152241,
    )

    @Test
    fun falseBeforeTheFirstScan() {
        assertFalse(FirstRun.show(InstalledSet(), fresh, null))
        assertFalse(FirstRun.show(InstalledSet(), fresh, job(JobPhase.DOWNLOADING)))
        assertTrue(FirstRun.show(scanned, fresh, null))
    }

    @Test
    fun falseWhenTheScanFindsAPackOrAnIssue() {
        val withIssue = scanned.copy(issues = listOf(PackIssue("/p/us-oh", "manifest_version 2 is an old pack format; rebuild the pack")))
        assertFalse(FirstRun.show(withIssue, fresh, null))
        assertFalse(FirstRun.show(withIssue, fresh, job(JobPhase.DOWNLOADING)))
        assertFalse(FirstRun.show(scanned.copy(packs = listOf(pa)), fresh, null))
        // ... and the choice is written then, so it never comes back for that phone.
        assertTrue(FirstRun.afterScan(fresh, withIssue).firstRunPickerDone)
        assertTrue(FirstRun.afterScan(fresh, scanned.copy(packs = listOf(pa))).firstRunPickerDone)
        assertFalse(FirstRun.afterScan(fresh, scanned).firstRunPickerDone)
        assertFalse(FirstRun.afterScan(fresh, InstalledSet(packs = listOf(pa))).firstRunPickerDone) // not scanned yet: nothing written
    }

    @Test
    fun notNowIsRemembered() {
        val after = FirstRun.picked(fresh)
        assertFalse(FirstRun.show(scanned, after, null))
    }

    @Test
    fun aFirstRunDownloadKeepsThePickerInEveryPhaseUntilDone() {
        val picked = FirstRun.picked(fresh) // a Download remembers the choice too
        for (phase in JobPhase.entries.filter { it != JobPhase.DONE }) {
            assertTrue(FirstRun.show(scanned, picked, job(phase)), "$phase")
        }
        assertTrue(FirstRun.show(scanned, picked, job(JobPhase.PAUSED, pause = PauseReason.APP_ENDED)))
        assertTrue(FirstRun.show(scanned, picked, job(JobPhase.FAILED, fail = FailReason.REJECTED)))
        assertFalse(FirstRun.show(scanned, picked, job(JobPhase.DONE)))
        assertFalse(FirstRun.show(scanned.copy(packs = listOf(pa)), picked, job(JobPhase.DONE)))
        // A job started on the States screen (not first run) does not bring the picker back.
        assertFalse(FirstRun.show(scanned, picked, job(JobPhase.DOWNLOADING, firstRun = false)))
    }

    @Test
    fun trueAgainAfterAFirstRunCancel() {
        val running = job(JobPhase.DOWNLOADING)
        val picked = FirstRun.picked(fresh)
        assertTrue(FirstRun.show(scanned, picked, running))
        val afterCancel = FirstRun.afterCancel(picked, running, scanned)
        assertEquals(fresh, afterCancel)
        assertTrue(FirstRun.show(scanned, afterCancel, null)) // the job is gone: the picker is back
        // Not for a job started on the States screen, and not once a state is installed.
        assertTrue(FirstRun.afterCancel(picked, job(JobPhase.DOWNLOADING, firstRun = false), scanned).firstRunPickerDone)
        assertTrue(FirstRun.afterCancel(picked, running, scanned.copy(packs = listOf(pa))).firstRunPickerDone)
    }

    @Test
    fun trueAgainAfterAFirstRunFailureIsDismissed() {
        val failed = job(JobPhase.FAILED, fail = FailReason.LIST_MISMATCH)
        val picked = FirstRun.picked(fresh)
        val after = FirstRun.afterDismiss(picked, failed, scanned)
        assertTrue(FirstRun.show(scanned, after, null))
        // Dismissing a DONE job writes nothing.
        assertTrue(FirstRun.afterDismiss(picked, job(JobPhase.DONE), scanned).firstRunPickerDone)
    }

    @Test
    fun aFileInstallFromTheFirstRunScreenKeepsThePickerWhileItRuns() {
        val picked = FirstRun.picked(fresh)
        assertTrue(FirstRun.show(scanned, picked, null, fileInstallFromFirstRun = true))
        assertFalse(FirstRun.show(scanned, picked, null, fileInstallFromFirstRun = false))
        assertFalse(FirstRun.show(scanned.copy(packs = listOf(pa)), picked, null, fileInstallFromFirstRun = true))
    }
}
