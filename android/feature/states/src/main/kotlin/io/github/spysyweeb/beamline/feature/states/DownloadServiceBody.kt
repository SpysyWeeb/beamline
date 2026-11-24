package io.github.spysyweeb.beamline.feature.states

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.feature.states.model.StatesPresenter
import io.github.spysyweeb.beamline.feature.states.model.Texts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DownloadService's work (docs/architecture-downloads.md 5.2): foreground at once (type dataSync,
 * id 4210), engine.run() on Dispatchers.IO, the notification at most once per second, a partial
 * wake lock only while bytes move, and the end: the ongoing notification replaced by a normal one
 * ("Pennsylvania is ready", "Download paused · 43 %", ...), then stopSelf(). START_NOT_STICKY: the
 * app continues a job from a visible state, never the system on its own.
 */
internal class DownloadServiceBody(private val service: LifecycleService) {
    private val core get() = (service.application as GetPacksHost).getPacks.core
    private var running: Job? = null
    private var observing: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeAt = 0L
    private var notifiedAt = 0L
    /** end() ran for this start: the run's coroutine and onTimeout must not both post the end. */
    private var ended = false

    fun onStartCommand(intent: Intent?): Int {
        val core = core
        ServiceCompat.startForeground(service, ONGOING_ID, Notifications.ongoing(service, core.config, core.job.value), foregroundType())
        ended = false
        // The notification's actions change the job on GetPacksCore.actions (they write job.json); the run below waits for them.
        val control = when (intent?.action) {
            DownloadService.ACTION_PAUSE -> core.launchAction { it.pause() }
            DownloadService.ACTION_USE_MOBILE_DATA -> core.launchAction { e ->
                if (e.job.value?.phase == JobPhase.PAUSED) e.resume(allowMetered = true) else e.allowMetered()
            }
            else -> null
        }
        val engine = core.engine
        if (engine == null) {
            end(null)
            return Service.START_NOT_STICKY
        }
        if (observing == null) {
            observing = service.lifecycleScope.launch {
                engine.job.collect { j -> onJob(j) }
            }
            // The foreground notification posted before the permission was granted (it is asked right
            // after the first Download) stays hidden until it is posted again: re-post it once
            // notifications become allowed, from the dialog or from the system settings.
            service.lifecycleScope.launch {
                var allowed = NotificationManagerCompat.from(service).areNotificationsEnabled()
                while (true) {
                    delay(2_000)
                    val now = NotificationManagerCompat.from(service).areNotificationsEnabled()
                    if (now && !allowed && engine.needsService.value) {
                        Notifications.post(service, ONGOING_ID, Notifications.ongoing(service, core.config, engine.job.value))
                    }
                    allowed = now
                }
            }
        }
        if (running?.isActive != true) {
            running = service.lifecycleScope.launch {
                control?.join()
                withContext(Dispatchers.IO) {
                    while (true) {
                        engine.run()
                        if (!engine.needsService.value) break
                        delay(1000) // a run that returned while the job still needs the service (another caller held the engine)
                    }
                }
                end(engine.job.value)
            }
        }
        return Service.START_NOT_STICKY
    }

    private fun foregroundType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

    private fun onJob(j: DownloadJob?) {
        val moving = j?.phase == JobPhase.DOWNLOADING || j?.phase == JobPhase.UNPACKING
        val now = System.currentTimeMillis()
        if (moving) {
            if (wakeLock?.isHeld != true || now - wakeAt > 60_000) {
                val pm = service.getSystemService(PowerManager::class.java)
                val lock = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beamline:download").also { it.setReferenceCounted(false) }
                lock.acquire(10 * 60_000L)
                wakeLock = lock
                wakeAt = now
            }
        } else {
            releaseWakeLock()
        }
        if (j == null || !core.engine!!.needsService.value) return
        if (now - notifiedAt < 1000 && j.phase == JobPhase.DOWNLOADING) return
        notifiedAt = now
        Notifications.post(service, ONGOING_ID, Notifications.ongoing(service, core.config, j))
    }

    private fun end(j: DownloadJob?) {
        if (ended) return
        ended = true
        releaseWakeLock()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (j != null) Notifications.post(service, END_ID, Notifications.end(service, core.config, j))
        service.stopSelf()
    }

    /**
     * Android 15+ ends a dataSync service after 6 h in 24 h (1.4): PAUSED(SYSTEM_STOPPED) and the
     * notification "Download paused" / "Open Beamline to continue.", then stopSelf() within a second.
     * The end is posted here, not left to the run's coroutine: stopSelf() destroys the service,
     * which cancels lifecycleScope, so that coroutine may never reach its end().
     */
    fun onTimeout() {
        val core = core
        service.lifecycleScope.launch {
            withContext(core.actions) { core.engine?.stopForSystem() }
            end(core.engine?.job?.value)
        }
    }

    fun onDestroy() = releaseWakeLock()

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    companion object {
        const val ONGOING_ID = 4210
        const val END_ID = 4211

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            true
        } catch (_: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
            Log.w("Beamline.states", "download service start refused; running in the app")
            false
        }
    }
}

/** The "Downloads" channel and its notifications (1.1 step 7). */
internal object Notifications {
    const val CHANNEL = "downloads"

    fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, Texts.CHANNEL, NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun activity(context: Context, intent: Intent, code: Int): PendingIntent =
        PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(context: Context, action: String, code: Int): PendingIntent =
        PendingIntent.getService(context, code, Intent(context, DownloadService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun ongoing(context: Context, config: GetPacksConfig, job: DownloadJob?): Notification {
        channel(context)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.beamline_states_download)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(activity(context, config.openStates(), 1))
        if (job == null) return b.setContentTitle(Texts.GETTING_READY).setProgress(0, 0, true).build()
        val n = StatesPresenter.notification(job)
        b.setContentTitle(n.title).setContentText(n.text)
        when (val p = n.progress) {
            null -> Unit
            -1 -> b.setProgress(0, 0, true)
            else -> b.setProgress(100, p, false)
        }
        if (n.pause) b.addAction(0, Texts.PAUSE, action(context, DownloadService.ACTION_PAUSE, 2))
        if (n.useMobileData) b.addAction(0, Texts.USE_MOBILE_DATA, action(context, DownloadService.ACTION_USE_MOBILE_DATA, 3))
        return b.build()
    }

    fun end(context: Context, config: GetPacksConfig, job: DownloadJob): Notification {
        channel(context)
        val n = StatesPresenter.notification(job)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.beamline_states_download)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(activity(context, if (n.opensMap) config.openMap() else config.openStates(), 4))
            .build()
    }

    fun post(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS refused: the download still runs; the progress view says so.
        }
    }
}
