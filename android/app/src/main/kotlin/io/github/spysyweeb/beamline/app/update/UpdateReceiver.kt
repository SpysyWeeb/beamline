package io.github.spysyweeb.beamline.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/**
 * Item 41: the result of an update install (AppUpdates.install). When Android wants the user to
 * confirm (the first update, or whenever it insists), its dialog opens; on success Android has
 * replaced the app and this process ends.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updates = (context.applicationContext as? io.github.spysyweeb.beamline.app.BeamlineApplication)?.graph?.appUpdates
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                // Android 10+ opens no activity for an app in the background: then the session is dropped
                // and the chip offers the update again when the user is back
                val opened = updates?.foreground == true && confirm != null && try {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (e: RuntimeException) {
                    Log.w("Beamline.update", "the install dialog could not open", e)
                    false
                }
                if (!opened) {
                    val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    if (id >= 0) try { context.packageManager.packageInstaller.abandonSession(id) } catch (_: Exception) {}
                    updates?.installEnded(success = false, aborted = true)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> updates?.installEnded(success = true, aborted = false)
            PackageInstaller.STATUS_FAILURE_ABORTED -> updates?.installEnded(success = false, aborted = true)
            else -> {
                Log.w("Beamline.update", "install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                updates?.installEnded(success = false, aborted = false)
            }
        }
    }
}
