package io.github.spysyweeb.beamline.feature.states.model

import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.continuesByItself

/**
 * How long the progress view says "Continuing the download…" (1.1 step 7, 1.4 "The process ended
 * half way") after GetPacks.onAppVisible continued a job: while the job is starting again (CHECKING,
 * or DOWNLOADING with no speed yet), then [SHOW_MS] more once bytes move. The time counts from the
 * first moving value, not from onAppVisible: after a cold start the screen's first full frame comes
 * a second or two later, and a timer started at onAppVisible ran out before the view was drawn
 * (seen on the device after `am force-stop`). Pure, for the JVM tests of 10.2.
 */
object Continuing {
    const val SHOW_MS = 3_000L

    /** [on]: the line shows; [movingSinceMs]: when bytes first moved after the continuation (0: not yet). */
    data class State(val on: Boolean = false, val movingSinceMs: Long = 0)

    /** onAppVisible continued the job. */
    fun started(): State = State(on = true, movingSinceMs = 0)

    fun next(s: State, job: DownloadJob?, nowMs: Long): State {
        if (!s.on) return s
        if (job == null) return State()
        val moving = job.phase == JobPhase.DOWNLOADING && job.progress.bytesPerSecond != null
        // The paused value from before resume() can arrive after the flag was set: it is the job being continued.
        val beingContinued = job.continuesByItself()
        return when {
            beingContinued || job.phase == JobPhase.CHECKING || (job.phase == JobPhase.DOWNLOADING && !moving) -> s
            moving && s.movingSinceMs == 0L -> s.copy(movingSinceMs = nowMs)
            moving && nowMs - s.movingSinceMs < SHOW_MS -> s
            else -> State()
        }
    }
}
