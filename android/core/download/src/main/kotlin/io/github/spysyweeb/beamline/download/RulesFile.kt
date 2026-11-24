package io.github.spysyweeb.beamline.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** download_rules.json (format beamline-download-rules/1) to [DownloadRules]; a missing key keeps its default. */
internal object RulesFile {
    /**
     * Absolute, like every other module's rule file: R8 moves this class to another package in the
     * app's release build, and a path relative to the class's package then finds nothing (the release
     * APK failed at start with "download_rules.json is missing", integration 2026-11-16).
     */
    const val RESOURCE = "/io/github/spysyweeb/beamline/download/download_rules.json"

    fun parse(text: String): DownloadRules {
        val root = try {
            Json.parseToJsonElement(text.removePrefix("﻿"))
        } catch (_: Exception) {
            throw IllegalArgumentException("download rules are not JSON")
        } as? JsonObject ?: throw IllegalArgumentException("download rules are not a JSON object")
        val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(format == DownloadRules.FORMAT) { "download rules: format must be ${DownloadRules.FORMAT}" }
        val d = DownloadRules()

        fun int(key: String, default: Int, min: Int = 0): Int {
            val e = root[key] ?: return default
            val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: throw IllegalArgumentException("download rules: $key must be an integer")
            require(v >= min) { "download rules: $key must be at least $min" }
            return v
        }

        fun long(key: String, default: Long, min: Long = 0): Long {
            val e = root[key] ?: return default
            val v = (e as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("download rules: $key must be an integer")
            require(v >= min) { "download rules: $key must be at least $min" }
            return v
        }

        val userAgent = root["user_agent"]?.let {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.isNotBlank() && p.content.all { c -> c.code in 0x21..0x7e || c == ' ' } }?.content
                ?: throw IllegalArgumentException("download rules: user_agent must be a plain string")
        } ?: d.userAgent
        val backoff = root["retry_backoff_s"]?.let { e ->
            val a = e as? JsonArray ?: throw IllegalArgumentException("download rules: retry_backoff_s must be an array")
            val list = a.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: throw IllegalArgumentException("download rules: retry_backoff_s must hold integers") }
            require(list.isNotEmpty() && list.all { it >= 0 }) { "download rules: retry_backoff_s must hold at least one value, none negative" }
            list
        } ?: d.retryBackoffS

        return DownloadRules(
            userAgent = userAgent,
            connectTimeoutS = int("connect_timeout_s", d.connectTimeoutS, 1),
            readTimeoutS = int("read_timeout_s", d.readTimeoutS, 1),
            maxRedirects = int("max_redirects", d.maxRedirects),
            bufferBytes = int("buffer_bytes", d.bufferBytes, 1024),
            syncEveryBytes = long("sync_every_bytes", d.syncEveryBytes, 1),
            progressEveryMs = long("progress_every_ms", d.progressEveryMs),
            retryBackoffS = backoff,
            maxAttemptsWithoutProgress = int("max_attempts_without_progress", d.maxAttemptsWithoutProgress, 1),
            waitForNetworkMaxMin = int("wait_for_network_max_min", d.waitForNetworkMaxMin, 1),
            maxDeathsWithoutProgress = int("max_deaths_without_progress", d.maxDeathsWithoutProgress, 1),
            catalogMaxBytes = long("catalog_max_bytes", d.catalogMaxBytes, 1),
            catalogRefreshAfterMin = int("catalog_refresh_after_min", d.catalogRefreshAfterMin),
            spaceMarginBytes = long("space_margin_bytes", d.spaceMarginBytes),
            maxFileBytes = long("max_file_bytes", d.maxFileBytes, 1),
            hashRetries = int("hash_retries", d.hashRetries),
            speedWindowS = int("speed_window_s", d.speedWindowS, 1),
        )
    }

    fun bundled(): DownloadRules {
        val stream = DownloadRules::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("download_rules.json is missing from the module's resources")
        return stream.use { parse(it.readBytes().toString(Charsets.UTF_8)) }
    }
}
