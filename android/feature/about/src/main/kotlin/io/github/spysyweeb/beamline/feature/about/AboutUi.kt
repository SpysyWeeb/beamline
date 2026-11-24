// FILE: feature/about/src/main/kotlin/io/github/spysyweeb/beamline/feature/about/AboutUi.kt
package io.github.spysyweeb.beamline.feature.about

/** Raise when the first-run text changes: the notice is shown again. */
const val FIRST_RUN_NOTICE_VERSION = 1

data class AboutPackUi(val name: String, val osmSnapshot: String, val sizeText: String)

data class AboutUi(
    val appName: String,
    /** "0.1.0 (3)" */
    val versionText: String,
    val buildText: String?,
    /** "Map style 007a6f98c1f9" */
    val styleText: String,
    val packs: List<AboutPackUi>,
    /**
     * "Map data © OpenStreetMap contributors, ODbL 1.0", "US overview: Natural Earth; US Census
     * Bureau", then each installed pack's PackInfo.attribution (address sources included only where
     * the house numbers were installed).
     */
    val dataCredits: List<String>,
)

sealed interface AboutEvent {
    data object OpenLicences : AboutEvent
    data object OpenDriverNotice : AboutEvent
    data object Back : AboutEvent
}

/** One shipped component; [licence] is an SPDX expression; [textAsset] is its licence text under assets/notices/. */
data class NoticeEntry(
    val name: String,
    val version: String?,
    val licence: String,
    val copyright: String?,
    val url: String?,
    val textAsset: String,
)

/** assets/notices/notices.json; [fullTextAsset] = assets/notices/NOTICES.txt (everything in one file). */
data class Notices(val entries: List<NoticeEntry>, val fullTextAsset: String) {
    companion object {
        fun parse(json: String): Notices = NoticesJson.parse(json)
    }
}
