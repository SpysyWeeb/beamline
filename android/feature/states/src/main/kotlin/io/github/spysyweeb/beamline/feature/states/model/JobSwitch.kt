package io.github.spysyweeb.beamline.feature.states.model

import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.JobPhase

/**
 * When the progress view says "A newer Pennsylvania was published. Downloading that one." (1.4, "A
 * file is gone from the server"). DownloadJob has no field for it: the engine replaces the job in
 * place (EngineCore.gone: same pack id, the newer pack_version, kind and choices carried over, no
 * DONE, FAILED or null in between), so the screen recognises the switch from two job values in a
 * row. Pure, for the JVM tests of 10.2.
 */
object JobSwitch {
    /** [next] replaced [prev] as a switch to a newer build: same pack, a higher pack_version, same kind, and [prev] had not ended. */
    fun isSwitch(prev: DownloadJob?, next: DownloadJob?): Boolean =
        prev != null && next != null && prev.packId == next.packId && next.packVersion > prev.packVersion &&
            next.kind == prev.kind && prev.phase != JobPhase.DONE && prev.phase != JobPhase.FAILED

    /**
     * The flag after the job became [next]: set by a switch, kept while the new job runs, cleared
     * when the job ends (DONE, FAILED), is cancelled or dismissed (null), or another pack's job starts.
     */
    fun flag(was: Boolean, prev: DownloadJob?, next: DownloadJob?): Boolean = when {
        isSwitch(prev, next) -> true
        next == null || next.phase == JobPhase.DONE || next.phase == JobPhase.FAILED -> false
        prev == null || prev.packId != next.packId || prev.packVersion != next.packVersion -> false
        else -> was
    }
}
