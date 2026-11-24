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
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let {
                    try {
                        context.startActivity(it)
                    } catch (e: RuntimeException) {
                        Log.w("Beamline.update", "the install dialog could not open", e)
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Log.w("Beamline.update", "install status $status: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}
