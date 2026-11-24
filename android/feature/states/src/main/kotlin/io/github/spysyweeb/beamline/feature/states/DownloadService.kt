// FILE: feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/DownloadService.kt
package io.github.spysyweeb.beamline.feature.states

import android.content.Context
import androidx.lifecycle.LifecycleService

/** The foreground service (type dataSync) that runs DownloadEngine.run(): section 5. */
class DownloadService : LifecycleService() {
    companion object {
        /** Notification actions. Cancel is not one: it deletes files and is confirmed in the app. */
        const val ACTION_PAUSE = "io.github.spysyweeb.beamline.download.PAUSE"
        const val ACTION_USE_MOBILE_DATA = "io.github.spysyweeb.beamline.download.USE_MOBILE_DATA"

        /** Starts the service from the visible app; false when Android refuses (then GetPacks runs the engine itself). */
        fun start(context: Context): Boolean = DownloadServiceBody.start(context)
    }

    // ---- Implementation (track G): the lifecycle goes to DownloadServiceBody.kt. The contract's import
    // list is fixed, so Intent carries its package name here.
    private val body = DownloadServiceBody(this)

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return body.onStartCommand(intent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) = body.onTimeout()

    override fun onDestroy() {
        body.onDestroy()
        super.onDestroy()
    }

    /** Closes the class body: the contract's last two braces end this function and the class. */
    private fun endOfBody() {
        Unit
    }
}
