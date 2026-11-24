package io.github.spysyweeb.beamline.feature.states.model

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.feature.states.UiChoices

/**
 * The first-run picker's rule (docs/architecture-downloads.md 1.1 step 2, 1.5, GetPacks.showFirstRunPicker)
 * and the changes of the remembered choice (`first_run_picker_done` in getpacks/ui.json, 6.5), as pure
 * functions so the JVM tests of 10.2 can run them without Android.
 */
object FirstRun {
    /**
     * False until the first scan. Then true while no pack is on the phone (none installed, none among
     * the issues) and either the choice is not remembered, or a download started on the first-run
     * screen is not finished (any phase; FAILED until dismissed, because dismissing removes the job),
     * or [fileInstallFromFirstRun]: a file install started on the first-run screen is running or its
     * result has not been dismissed (it is not persisted: 2.12 "a running install from a file").
     */
    fun show(set: InstalledSet, choices: UiChoices, job: DownloadJob?, fileInstallFromFirstRun: Boolean = false): Boolean {
        if (!set.scanned) return false
        if (set.packs.isNotEmpty() || set.issues.isNotEmpty()) return false
        if (job != null && job.firstRun && job.phase != JobPhase.DONE) return true
        if (fileInstallFromFirstRun) return true
        return !choices.firstRunPickerDone
    }

    /** A scan that finds any pack folder, installed or among the issues, writes the choice (1.5). */
    fun afterScan(choices: UiChoices, set: InstalledSet): UiChoices =
        if (set.scanned && (set.packs.isNotEmpty() || set.issues.isNotEmpty())) choices.copy(firstRunPickerDone = true) else choices

    /** Not now, a download started, a file install started: remembered (1.5). */
    fun picked(choices: UiChoices): UiChoices = choices.copy(firstRunPickerDone = true)

    /** A first-run download cancelled while nothing is installed: the picker comes back (1.1 step 5, 1.5). */
    fun afterCancel(choices: UiChoices, job: DownloadJob?, set: InstalledSet): UiChoices =
        if (job?.firstRun == true && set.packs.isEmpty()) choices.copy(firstRunPickerDone = false) else choices

    /** A first-run download's failure dismissed while nothing is installed: the picker comes back (1.5). */
    fun afterDismiss(choices: UiChoices, job: DownloadJob?, set: InstalledSet): UiChoices =
        if (job?.firstRun == true && job.phase == JobPhase.FAILED && set.packs.isEmpty()) choices.copy(firstRunPickerDone = false) else choices
}
