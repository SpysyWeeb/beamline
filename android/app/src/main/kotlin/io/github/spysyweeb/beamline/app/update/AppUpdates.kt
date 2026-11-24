package io.github.spysyweeb.beamline.app.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.spysyweeb.beamline.app.BuildConfig
import io.github.spysyweeb.beamline.download.AppUpdater
import io.github.spysyweeb.beamline.download.DownloadRules
import io.github.spysyweeb.beamline.download.net.CatalogAddress
import io.github.spysyweeb.beamline.download.net.PackHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Item 41 (docs/app-updates-design.md 4): Beamline's own updates from the app repository's GitHub
 * releases. [AppUpdater] (`:core:download`) checks and downloads; this class adds what needs Android:
 * downloads by themselves only on an unmetered network (a tap may use mobile data), the signing
 * certificate and version of the downloaded APK, the install with [PackageInstaller] (silent when
 * Android allows it: Android 12+ and Beamline the installer of record, i.e. after the first update
 * the user confirmed), never while guiding, and the "Updated to" note after a new version starts.
 * Off in builds without an update address (debug builds).
 */
class AppUpdates(private val app: android.content.Context, private val scope: CoroutineScope) {
    private val dir = File(app.cacheDir, "app-update")
    private val seenFile = File(app.filesDir, "app-update-seen.json")
    private val lock = Mutex()
    private val updater: AppUpdater? = BuildConfig.UPDATE_URL.takeIf { it.isNotEmpty() }?.let { url ->
        val address = CatalogAddress.parse(url, allowCleartext = false) ?: return@let null
        AppUpdater(address, PackHttp(address, DownloadRules()), dir, BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT,
            Build.SUPPORTED_ABIS.toList())
    }

    /** True in a build that can update itself. */
    val available: Boolean get() = updater != null

    val state: StateFlow<AppUpdater.State> = updater?.state ?: MutableStateFlow(AppUpdater.State.Idle)

    private val _updatedNote = MutableStateFlow(updatedNoteAtStart())
    /** "Updated to 0.1.26" once after the app started as a newer version than it last ran as; null after. */
    val updatedNote: StateFlow<String?> = _updatedNote.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    /** The last install problem in words (a bad file, a refused install), for Settings; null when none. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun dismissUpdatedNote() {
        _updatedNote.value = null
    }

    /** The daily check (MainActivity.onStart, when the user allows it); a found update downloads by itself on Wi-Fi only. */
    fun onAppVisible(allowed: Boolean, guiding: () -> Boolean) {
        val u = updater ?: return
        if (!allowed) return
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                u.check(force = false)
                if (u.state.value is AppUpdater.State.Available && unmetered() && !guiding()) u.download()
            }
        }
    }

    /** Settings' "Check now": asks at once and downloads on any network (the user's tap). */
    fun checkNow(guiding: () -> Boolean) {
        val u = updater ?: return
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                u.check(force = true)
                if (u.state.value is AppUpdater.State.Available && !guiding()) u.download()
            }
        }
    }

    /**
     * Installs a downloaded update: on the chip's tap ([activity] given: the first update shows Android's
     * dialogs) or when the user leaves the app ([activity] null: only when it can be silent). Never while
     * guiding.
     */
    fun install(activity: Activity?, guiding: Boolean) {
        val ready = state.value as? AppUpdater.State.Ready ?: return
        if (guiding) return
        if (activity == null && !canBeSilent()) return
        val pm = app.packageManager
        if (!pm.canRequestPackageInstalls()) {
            // Android asks once whether Beamline may install apps ("Install unknown apps")
            activity?.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")))
            return
        }
        scope.launch(Dispatchers.IO) {
            val bad = checkFile(ready.file)
            if (bad != null) {
                _problem.value = bad
                ready.file.delete()
                return@launch
            }
            _problem.value = null
            try {
                val installer = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(ready.file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { s ->
                    s.openWrite("beamline.apk", 0, ready.file.length()).use { out ->
                        ready.file.inputStream().use { it.copyTo(out) }
                        s.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java).setPackage(app.packageName), flags)
                    s.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                Log.w(TAG, "install failed", e)
                _problem.value = TEXT_INSTALL_FAILED
            }
        }
    }

    /** Silent installs need Android 12+ and Beamline as the app that installed the current version. */
    private fun canBeSilent(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName == app.packageName
        } catch (_: Exception) {
            false
        }
    }

    /** Null when the file is this app, newer, and signed like the installed one; else the problem in words. */
    private fun checkFile(file: File): String? {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return TEXT_BAD_FILE
        if (archive.packageName != app.packageName) return TEXT_BAD_FILE
        val installed = pm.getPackageInfo(app.packageName, flags)
        if (versionCode(archive) <= versionCode(installed)) return TEXT_BAD_FILE
        if (signers(archive) != signers(installed)) return TEXT_BAD_FILE
        return null
    }

    private fun versionCode(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()

    private fun signers(p: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") p.signatures
        return sigs.orEmpty().map { it.toCharsString() }.toSet()
    }

    private fun unmetered(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Reads and updates app-update-seen.json: the version code the app last started as. */
    private fun updatedNoteAtStart(): String? = try {
        val last = seenFile.takeIf { it.isFile }?.readText()?.let { Regex("\"version_code\":(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
        val now = BuildConfig.VERSION_CODE.toLong()
        if (last != now) {
            seenFile.parentFile?.mkdirs()
            seenFile.writeText("{\"version_code\":$now}")
        }
        if (last != null && now > last) "$TEXT_UPDATED ${BuildConfig.VERSION_NAME}" else null
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val TAG = "Beamline.update"
        const val TEXT_UPDATED = "Updated to"
        const val TEXT_BAD_FILE = "The downloaded update was not right and was deleted. Beamline will try again."
        const val TEXT_INSTALL_FAILED = "The update could not be installed. Try again from Settings."
    }
}
