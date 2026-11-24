package io.github.spysyweeb.beamline.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One APK of a release (docs/app-updates-design.md 2): its file name in the release, its size in
 * bytes and its SHA-256 (64 lower-case hex digits).
 */
data class UpdateApk(val file: String, val size: Long, val sha256: String)

/**
 * The update file of an app release, `update.json` (format `beamline-update/1`), read from
 * `releases/latest/download/update.json` of the app's repository (item 41). The file is not signed:
 * Android installs an update only when it is signed with the installed app's key, and the app checks
 * the download against [UpdateApk.sha256], the version code and the signing certificate first (3).
 */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val publishedUtc: String,
    val notes: String,
    /** By ABI ("arm64-v8a", ...) and "universal". */
    val apks: Map<String, UpdateApk>,
) {
    /** Never a downgrade nor the same version. */
    fun isNewerThan(installedVersionCode: Long): Boolean = versionCode > installedVersionCode

    /**
     * The APK for a phone whose ABIs are [supportedAbis] (Build.SUPPORTED_ABIS, preferred first): the
     * first ABI the release has a file for, else the universal one; null when there is neither.
     */
    fun apkFor(supportedAbis: List<String>): Pair<String, UpdateApk>? =
        supportedAbis.firstNotNullOfOrNull { abi -> apks[abi]?.let { abi to it } } ?: apks[UNIVERSAL]?.let { UNIVERSAL to it }

    companion object {
        const val FORMAT = "beamline-update/1"
        const val UNIVERSAL = "universal"
        /** No APK of this app comes near it; a larger size is a broken or hostile file. */
        const val MAX_APK_BYTES = 300L * 1024 * 1024
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val FILE = Regex("^[A-Za-z0-9._-]{1,120}\\.apk$")
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Reads [text] strictly where it matters (format, version, every APK entry) and ignores keys it
         * does not know, so fields can be added without a new format. Throws IllegalArgumentException
         * with the key at fault.
         */
        fun parse(text: String): AppUpdate {
            val root = try {
                json.parseToJsonElement(text) as? JsonObject
            } catch (e: RuntimeException) {
                null
            } ?: throw IllegalArgumentException("update.json: not a JSON object")
            fun str(o: JsonObject, k: String): String =
                (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("update.json: $k is not text")
            fun long(o: JsonObject, k: String): Long =
                (o[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("update.json: $k is not a whole number")
            require(str(root, "format") == FORMAT) { "update.json: format is not $FORMAT" }
            val code = long(root, "version_code")
            require(code > 0) { "update.json: version_code must be above 0" }
            val name = str(root, "version_name")
            require(name.isNotBlank() && name.length <= 40) { "update.json: version_name" }
            val minSdk = long(root, "min_sdk")
            require(minSdk in 1..1000) { "update.json: min_sdk" }
            val apksObj = root["apks"] as? JsonObject ?: throw IllegalArgumentException("update.json: apks is not an object")
            val apks = apksObj.mapValues { (abi, v) ->
                val o = v as? JsonObject ?: throw IllegalArgumentException("update.json: apks.$abi is not an object")
                val file = str(o, "file")
                require(FILE.matches(file)) { "update.json: apks.$abi.file is not a plain .apk name" }
                val size = long(o, "size")
                require(size in 1..MAX_APK_BYTES) { "update.json: apks.$abi.size" }
                val sha = str(o, "sha256")
                require(SHA256.matches(sha)) { "update.json: apks.$abi.sha256 is not 64 lower-case hex digits" }
                UpdateApk(file, size, sha)
            }
            require(apks.isNotEmpty()) { "update.json: apks is empty" }
            return AppUpdate(
                versionCode = code,
                versionName = name,
                minSdk = minSdk.toInt(),
                publishedUtc = (root["published_utc"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                notes = (root["notes"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
                apks = apks,
            )
        }
    }
}
