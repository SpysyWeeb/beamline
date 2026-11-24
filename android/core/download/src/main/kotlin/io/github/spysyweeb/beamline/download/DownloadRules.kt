// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/DownloadRules.kt
package io.github.spysyweeb.beamline.download

/**
 * download_rules.json (format beamline-download-rules/1; source: packbuilder/catalog/download_rules.json,
 * copied into this module's resources by android/routing/scripts/sync_data.py). The defaults are the
 * file's values; field = JSON key in the comment. Unknown keys (such as "note") are ignored.
 */
data class DownloadRules(
    val userAgent: String = "Beamline-Packs/1",                      // user_agent
    val connectTimeoutS: Int = 15,                                   // connect_timeout_s
    val readTimeoutS: Int = 30,                                      // read_timeout_s
    val maxRedirects: Int = 5,                                       // max_redirects
    val bufferBytes: Int = 262_144,                                  // buffer_bytes
    val syncEveryBytes: Long = 67_108_864,                           // sync_every_bytes
    val progressEveryMs: Long = 500,                                 // progress_every_ms
    val retryBackoffS: List<Int> = listOf(2, 5, 10, 30, 60),         // retry_backoff_s (the last value repeats)
    val maxAttemptsWithoutProgress: Int = 8,                         // max_attempts_without_progress
    val waitForNetworkMaxMin: Int = 360,                             // wait_for_network_max_min (NO_NETWORK and NOT_UNMETERED)
    val maxDeathsWithoutProgress: Int = 2,                           // max_deaths_without_progress
    val catalogMaxBytes: Long = 2_097_152,                           // catalog_max_bytes
    val catalogRefreshAfterMin: Int = 360,                           // catalog_refresh_after_min
    val spaceMarginBytes: Long = 200_000_000,                        // space_margin_bytes
    val maxFileBytes: Long = 8_589_934_591,                          // max_file_bytes
    val hashRetries: Int = 1,                                        // hash_retries
    val speedWindowS: Int = 20,                                      // speed_window_s
) {
    companion object {
        const val FORMAT = "beamline-download-rules/1"
        fun parse(text: String): DownloadRules = RulesFile.parse(text)
        fun bundled(): DownloadRules = RulesFile.bundled()
    }
}

/**
 * The phone's connection as the downloader needs it (from ConnectivityManager on Android, by the
 * kind of catalog host: section 5.2 of docs/architecture-downloads.md).
 */
data class NetworkState(
    /**
     * Public host: a default network with NET_CAPABILITY_INTERNET and NET_CAPABILITY_VALIDATED.
     * Local host (debug): loopback always; a private address when a Wi-Fi or Ethernet network (else
     * the default network) is up, validated or not.
     */
    val connected: Boolean,
    /** Public host: that network has NET_CAPABILITY_NOT_METERED. Local host: true over loopback or a Wi-Fi or Ethernet network; the default network's NOT_METERED when neither exists. */
    val unmetered: Boolean,
    /** For the debug banner: which condition is missing ("default network not validated"); never an address. */
    val detail: String = "",
) {
    companion object {
        val NONE = NetworkState(connected = false, unmetered = false)
    }
}
