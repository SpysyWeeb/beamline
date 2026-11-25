package io.github.spysyweeb.beamline.app.update

import io.github.spysyweeb.beamline.app.BuildConfig
import io.github.spysyweeb.beamline.download.AppUpdater
import io.github.spysyweeb.beamline.feature.states.MapChip

/** Item 41: the words of app updates (the map's chip, Settings' line). */
object UpdateTexts {
    fun readyChip(version: String): MapChip =
        MapChip("Beamline $version is ready · Install", "Beamline $version is ready. Installs the update")

    /** Settings' line under "Check for an update now". */
    fun line(s: AppUpdater.State, problem: String?): String = problem ?: when (s) {
        AppUpdater.State.Idle -> "This is Beamline ${BuildConfig.VERSION_NAME}"
        AppUpdater.State.Checking -> "Checking…"
        is AppUpdater.State.UpToDate -> "Beamline ${BuildConfig.VERSION_NAME} is the newest"
        is AppUpdater.State.Available -> "Beamline ${s.update.versionName} is available (${mb(s.apk.size)}); it downloads on Wi-Fi, or tap"
        is AppUpdater.State.Downloading -> "Downloading Beamline ${s.update.versionName} · ${percent(s.doneBytes, s.apk.size)} %"
        is AppUpdater.State.Ready -> "Beamline ${s.update.versionName} is ready to install"
        is AppUpdater.State.Failed -> "Could not get the update. Beamline will try again."
    }

    private fun mb(bytes: Long) = "${(bytes + 524_288) / 1_048_576} MB"
    private fun percent(done: Long, total: Long) = if (total <= 0) 0 else (done * 100 / total).toInt()
}
