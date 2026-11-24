// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/Job.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.InstallStep

/** Where a download job is (state machine in section 6.4 of docs/architecture-downloads.md). */
enum class JobPhase {
    /** Fetching manifest.json, asking the installer (inspect), checking free space. */
    CHECKING,
    DOWNLOADING,
    /** Not moving, will go on by itself: see [DownloadJob.wait]. */
    WAITING,
    /** Inflating a gzip part into its file. */
    UNPACKING,
    /** Every chosen part is in the job folder and verified; the installer has not taken it yet. */
    READY,
    /** The installer is working on it (its InstallStatus.Running). */
    INSTALLING,
    /** Installed; the job folder is gone. */
    DONE,
    /** Stopped with its files kept; see [DownloadJob.pause]. Continued by a tap, or by itself for some reasons (section 5.2). */
    PAUSED,
    /** Ended without installing, because the files can never be installed: see [DownloadJob.fail]. Files are deleted; job.json stays until [DownloadEngine.dismiss]. */
    FAILED,
}

/** What a job installs. */
enum class JobKind {
    /** A state not on the phone. */
    INSTALL,
    /** A newer build of an installed state (it replaces the old pack atomically). */
    UPDATE,
    /** Only the optional parts (house numbers) of the installed build: InstalledData.startAddOptionalParts. */
    ADD_OPTIONAL,
}

enum class WaitReason {
    /** NetworkState.connected is false; ends after wait_for_network_max_min. */
    NO_NETWORK,
    /** Only a metered network and the user has not allowed mobile data for this job; ends after wait_for_network_max_min. */
    NOT_UNMETERED,
    /** A network error; the next attempt comes after [DownloadJob.retryAtMs]. */
    RETRYING,
    /** The installer is busy with another install (a file the user picked). */
    INSTALLER_BUSY,
    /** Changes are held (InstalledSet.changesHeldBy, "Not while guiding"). */
    TRIP_IN_PROGRESS,
}

enum class PauseReason {
    /** The user tapped Pause, or the installer reported Cancelled. */
    USER,
    /** Not enough free space ([DownloadJob.needBytes], [DownloadJob.freeBytes]), before or during the download. */
    NO_SPACE,
    /** The system stopped the service (Android 15+ six-hour limit) or a wait passed wait_for_network_max_min. Continued at the next app start. */
    SYSTEM_STOPPED,
    /** The app's process ended while the job was running, for any reason. Continued at the next app start. */
    APP_ENDED,
    /** The process ended max_deaths_without_progress times during the job without progress. Not continued by itself. */
    KEEPS_STOPPING,
    /** max_attempts_without_progress attempts (network errors, timeouts, 5xx, 429) gained no byte. Continued at the next app start. */
    SERVER_UNREACHABLE,
    /** The server broke the rules (a refused redirect, too many hops, a body longer than declared, a Content-Encoding, another 4xx). */
    BAD_RESPONSE,
    /** A part's SHA-256 was wrong after hash_retries + 1 downloads; that part's files are deleted, the verified parts kept. */
    DAMAGED,
    /** TLS failed on the certificate's dates (ClockProblem): the phone's date or time is wrong. */
    CLOCK,
}

enum class FailReason {
    /** The server no longer offers this pack and has no newer one of the same id. */
    NO_LONGER_OFFERED,
    /** The pack's manifest.json disagrees with the catalog entry, or its pack_id is installed for another state (found at CHECKING). */
    LIST_MISMATCH,
    /** The installer refused the pack ([DownloadJob.detail] has its words, shown under Details). */
    REJECTED,
    /** The pack's manifest version, a part's encoding or a format is newer than this app reads. */
    NEEDS_NEWER_APP,
}

data class JobProgress(
    /** Bytes of the chosen transfer files on disk now. */
    val doneBytes: Long,
    /** CatalogPack.downloadBytes(includeOptional). */
    val totalBytes: Long,
    /** The part being fetched or unpacked; null between parts. */
    val part: String?,
    /** Average over the last speed_window_s; null while unknown or not moving. */
    val bytesPerSecond: Long?,
)

data class DownloadJob(
    val packId: String,
    val packVersion: Long,
    val name: String,
    /** USPS code. */
    val state: String,
    val osmSnapshot: String,
    val kind: JobKind,
    val includeOptional: Boolean,
    val allowMetered: Boolean,
    /** Started from the first-run screen: the map opens on the state once it is installed. */
    val firstRun: Boolean,
    val phase: JobPhase,
    val progress: JobProgress,
    val wait: WaitReason? = null,
    /** With RETRYING: when the next attempt starts, epoch ms. */
    val retryAtMs: Long? = null,
    val pause: PauseReason? = null,
    val fail: FailReason? = null,
    /** With REJECTED: the installer's words; with BAD_RESPONSE: the rule broken. Shown under Details only: never as the main text, never logged (rule N3). */
    val detail: String? = null,
    /** With PAUSED(NO_SPACE): bytes needed in all and bytes free. */
    val needBytes: Long? = null,
    val freeBytes: Long? = null,
    /** With INSTALLING: the installer's step and its byte counts. */
    val installStep: InstallStep? = null,
    val installDoneBytes: Long = 0,
    val installTotalBytes: Long = 0,
)

enum class StartRefusal {
    /** No catalog address in this build. */
    DOWNLOADS_OFF,
    /** Another job is not finished (one job at a time). */
    ANOTHER_JOB,
    /** The entry is in Catalog.unusable. */
    NEEDS_NEWER_APP,
    /** ADD_OPTIONAL while the installer cannot add parts, or the installed build differs from the entry. */
    NOT_SUPPORTED,
}

sealed interface StartResult {
    data object Started : StartResult
    data class Refused(val why: StartRefusal) : StartResult
}
