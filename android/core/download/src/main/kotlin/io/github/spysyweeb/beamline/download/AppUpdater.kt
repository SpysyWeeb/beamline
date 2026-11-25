package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.download.net.CatalogAddress
import io.github.spysyweeb.beamline.download.net.PackHttp
import io.github.spysyweeb.beamline.download.net.PackRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Item 41 (docs/app-updates-design.md 4): finds a newer release of the app and downloads its APK,
 * through [PackHttp] and so under its rules (https only, every redirect checked, public addresses
 * only). [address] is the release's `update.json` (`.../releases/latest/download/update.json`); an
 * APK is fetched from the same folder. No Android here: the app decides when to check and download
 * (the daily check, Wi-Fi or a tap, never while guiding), checks the APK's signing certificate and
 * installs it. Blocking calls: run them on an IO thread, one at a time.
 *
 * Files in [dir]: `update-check.json` (when the last check answered) and the APK (`<file>.part` while
 * it downloads).
 */
class AppUpdater(
    private val address: CatalogAddress,
    private val http: PackHttp,
    private val dir: File,
    private val installedVersionCode: Long,
    private val sdkInt: Int,
    private val supportedAbis: List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val checkedAtMs: Long) : State
        data class Available(val update: AppUpdate, val abi: String, val apk: UpdateApk) : State
        data class Downloading(val update: AppUpdate, val apk: UpdateApk, val doneBytes: Long) : State
        /** [file] holds the APK whose size and SHA-256 match the release's update.json. */
        data class Ready(val update: AppUpdate, val file: File) : State
        data class Failed(val reason: String, val update: AppUpdate?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val checkFile = File(dir, "update-check.json")
    /** The last update.json that answered, so a new process knows what it found. */
    private val keptFile = File(dir, "update.json")
    /** Version codes refused by the app (comma-separated). */
    private val refusedFile = File(dir, "update-refused.txt")

    /** When the last check answered (epoch ms), or null. */
    fun lastCheckMs(): Long? = try {
        checkFile.takeIf { it.isFile }?.readText()?.let { Regex("\"checked_ms\":(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
    } catch (_: IOException) {
        null
    }

    /** The version codes the app refused after download (a wrong file, a failed install): never downloaded again. */
    fun refused(): Set<Long> = try {
        refusedFile.takeIf { it.isFile }?.readText()?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()
    } catch (_: IOException) {
        emptySet()
    }

    /**
     * The app refused [file] of version [versionCode] (AppUpdates: not this app, not newer, another
     * signer, or Android refused the install): the file is deleted, the version is never fetched again,
     * and the state goes back to Idle, so the next start asks again (a newer release replaces it).
     */
    fun refuse(versionCode: Long, file: File?) {
        file?.delete()
        dir.mkdirs()
        refusedFile.writeText((refused() + versionCode).sorted().joinToString(","))
        _state.value = State.Idle
    }

    /**
     * Asks for update.json when [force], when there was no check yet, when the last one is [everyMs]
     * old or more (or lies in the future: the clock was set back), or when the last attempt failed. Else
     * it keeps its state, and a new process rebuilds it from the update.json kept on disk (an update
     * found or downloaded earlier today is not forgotten when Android ends the app).
     */
    fun check(force: Boolean, everyMs: Long = DAY_MS): State {
        val now = clock()
        val last = lastCheckMs()
        val current = _state.value
        if (!force && last != null && now - last < everyMs && last <= now && current !is State.Failed) {
            if (current != State.Idle) return current
            val kept = try {
                keptFile.takeIf { it.isFile }?.readText()?.let(AppUpdate::parse)
            } catch (_: Exception) {
                null
            }
            if (kept != null) {
                val restored = decide(kept, last)
                _state.value = restored
                return restored
            }
        }
        _state.value = State.Checking
        val next = try {
            val text = http.get(PackRequest(address.url)).use { r ->
                if (r.status != 200) throw IOException("HTTP ${r.status}")
                // a bounded read by hand: InputStream.readNBytes is not on Android before 13 (minSdk 26)
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = r.body.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_UPDATE_JSON) throw IOException("update.json too large")
                }
                out.toByteArray()
            }.decodeToString()
            val update = AppUpdate.parse(text)
            dir.mkdirs()
            keptFile.writeText(text)
            checkFile.writeText("{\"checked_ms\":$now}")
            decide(update, now)
        } catch (e: IllegalArgumentException) {
            State.Failed(e.message ?: "update.json", null)
        } catch (e: Exception) {
            State.Failed(e.message ?: e.javaClass.simpleName, null)
        }
        _state.value = next
        return next
    }

    /** Downloads the [State.Available] APK; Ready when its size and SHA-256 match, else Failed (the file is deleted). */
    fun download(): State {
        val a = _state.value as? State.Available ?: return _state.value
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".apk") || f.name.endsWith(".apk.part") }?.forEach { if (it.name != a.apk.file && it.name != a.apk.file + ".part") it.delete() }
        val part = File(dir, a.apk.file + ".part")
        val next = try {
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            http.get(PackRequest(address.resolve(a.apk.file))).use { r ->
                if (r.status != 200) throw IOException("HTTP ${r.status}")
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = r.body.read(buf)
                        if (n < 0) break
                        done += n
                        if (done > a.apk.size) throw IOException("the APK is longer than update.json says")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                        _state.value = State.Downloading(a.update, a.apk, done)
                    }
                    out.fd.sync()
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (done != a.apk.size || hex != a.apk.sha256) throw IOException("the APK does not match update.json")
            val file = File(dir, a.apk.file)
            file.delete()
            if (!part.renameTo(file)) throw IOException("cannot keep the APK")
            State.Ready(a.update, file)
        } catch (e: Exception) {
            part.delete()
            State.Failed(e.message ?: e.javaClass.simpleName, a.update)
        }
        _state.value = next
        return next
    }

    /** What [update] means for this phone: nothing to do, a version refused before, an APK to get, or one already here. */
    private fun decide(update: AppUpdate, checkedMs: Long): State {
        val pick = update.apkFor(supportedAbis)
        return when {
            !update.isNewerThan(installedVersionCode) || update.minSdk > sdkInt -> State.UpToDate(checkedMs)
            update.versionCode in refused() -> State.UpToDate(checkedMs)
            pick == null -> State.Failed("no APK for this phone", update)
            else -> readyFile(update, pick.second)?.let { State.Ready(update, it) } ?: State.Available(update, pick.first, pick.second)
        }
    }

    /** An APK already downloaded for [apk] whose size and SHA-256 still match, or null. */
    private fun readyFile(update: AppUpdate, apk: UpdateApk): File? {
        val f = File(dir, apk.file).takeIf { it.isFile && it.length() == apk.size } ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return f.takeIf { digest.digest().joinToString("") { "%02x".format(it) } == apk.sha256 }
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        /** update.json is a few hundred bytes; more is not an update file. */
        const val MAX_UPDATE_JSON = 64 * 1024
    }
}
