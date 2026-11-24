package io.github.spysyweeb.beamline.app.guidance

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.app.BeamlineApplication
import io.github.spysyweeb.beamline.app.MainActivity
import io.github.spysyweeb.beamline.feature.drive.DrivePresenter
import io.github.spysyweeb.beamline.feature.drive.GuidanceNotificationText
import io.github.spysyweeb.beamline.feature.drive.NotificationPacer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process at foreground priority while a trip is guided (docs/architecture.md section 4):
 * type `location`, started from the visible activity when the trip enters GUIDING, REROUTING or
 * ARRIVED, stopped by itself when it leaves them. It holds no logic: the notification's text is
 * DrivePresenter.notification, paced by NotificationPacer (rule D6, D's note: `next` on every trip
 * state, and after a null again at `heldUntilMs`). Channel "Guidance": low importance, silent,
 * ongoing; the action "End trip" sends NavIntent.End; a tap opens the app.
 *
 * A simulated drive needs no location, and Android 14+ refuses a location-type foreground service
 * without a location grant: with no grant the service is started as a plain service that only posts
 * the notification (no foreground priority; guidance itself runs in the process, as it always does).
 */
class GuidanceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var foreground = false
    private var held: Runnable? = null
    private lateinit var pacer: NotificationPacer

    private val graph get() = (application as BeamlineApplication).graph

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END) {
            graph.session.send(NavIntent.End)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REFRESH) {
            // POST_NOTIFICATIONS was granted after the service had started (the first Start asks
            // it while guidance begins): what was posted before was not shown, and the pacer
            // posts again only when the text changes (a car still standing at the start).
            if (started) DrivePresenter.notification(graph.session.state.value, graph.units.value)?.let { notify(it) } else stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        running = true
        pacer = NotificationPacer((graph.guidanceRules.notificationMinIntervalS * 1000).toLong())
        ensureChannel(this)
        val trip = graph.session.state.value
        val first = DrivePresenter.notification(trip, graph.units.value) ?: GuidanceNotificationText("Beamline", "Guidance")
        if (intent?.action == ACTION_NOTIFY_ONLY) {
            // No location grant (a simulated drive): the notification without foreground priority.
            notify(first)
            Log.i(TAG, "guidance service started without foreground: no location grant")
        } else {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, build(first), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                foreground = true
            } catch (e: RuntimeException) {
                // The grant was taken back between the check and the start: guidance goes on in the
                // visible app, without the service.
                Log.w(TAG, "guidance service not started: ${e.javaClass.simpleName}") // log-ok: an exception's class name
                stop()
                return START_NOT_STICKY
            }
            Log.i(TAG, "guidance service started")
        }
        scope.launch {
            graph.session.state.collect { s ->
                if (!graph.driving(s.phase)) {
                    stop()
                } else {
                    post(s)
                }
            }
        }
        return START_NOT_STICKY
    }

    /** D6: at once on a maneuver change, else at most every notification.min_interval_s. */
    private fun post(trip: TripState) {
        held?.let(handler::removeCallbacks)
        held = null
        val now = graph.clock.elapsedMs()
        val text = pacer.next(trip, graph.units.value, now)
        if (text != null) {
            notify(text)
            return
        }
        pacer.heldUntilMs()?.let { at ->
            val r = Runnable { post(graph.session.state.value) }
            held = r
            handler.postDelayed(r, (at - now).coerceAtLeast(0))
        }
    }

    private fun notify(text: GuidanceNotificationText) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.notify(NOTIFICATION_ID, build(text))
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS refused: guidance still runs, the notification is not visible.
        }
    }

    private fun build(text: GuidanceNotificationText): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val end = PendingIntent.getService(
            this, 1, Intent(this, GuidanceService::class.java).setAction(ACTION_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(io.github.spysyweeb.beamline.feature.about.R.drawable.ic_launcher_monochrome)
            .setContentTitle(text.title)
            .setContentText(text.text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, "End trip", end)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun stop() {
        held?.let(handler::removeCallbacks)
        held = null
        running = false
        if (foreground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) else cancelNotification()
        stopSelf()
    }

    /** A notification posted without foreground priority goes with the service (also when Android stops it in the background). */
    private fun cancelNotification() {
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    override fun onDestroy() {
        running = false
        held?.let(handler::removeCallbacks)
        if (started && !foreground) cancelNotification()
        scope.cancel()
        Log.i(TAG, "guidance service stopped")
        super.onDestroy()
    }

    companion object {
        const val TAG = "BeamlineGuidance"
        const val CHANNEL_ID = "guidance"
        const val NOTIFICATION_ID = 4310
        const val ACTION_END = "io.github.spysyweeb.beamline.guidance.END"
        const val ACTION_REFRESH = "io.github.spysyweeb.beamline.guidance.REFRESH"
        const val ACTION_NOTIFY_ONLY = "io.github.spysyweeb.beamline.guidance.NOTIFY_ONLY"

        /** True from start until the service stops (main thread). */
        @Volatile var running = false
            private set

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Guidance", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
        }

        /** The notification permission was just granted: the running service posts its text again. */
        fun refresh(context: Context) {
            if (!running) return
            try {
                context.startService(Intent(context, GuidanceService::class.java).setAction(ACTION_REFRESH))
            } catch (e: RuntimeException) {
                Log.w(TAG, "guidance notification not refreshed: ${e.javaClass.simpleName}") // log-ok: an exception's class name
            }
        }

        /**
         * From the visible activity only (Android 12+ forbids a background start). With a location
         * grant (precise or approximate) a foreground service of type location; without one (a
         * simulated drive) a plain service that posts the notification.
         */
        fun start(context: Context) {
            if (running) return
            val located = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            try {
                val intent = Intent(context, GuidanceService::class.java)
                if (located) ContextCompat.startForegroundService(context, intent) else context.startService(intent.setAction(ACTION_NOTIFY_ONLY))
            } catch (e: RuntimeException) {
                Log.w(TAG, "guidance service refused: ${e.javaClass.simpleName}") // log-ok: an exception's class name
            }
        }
    }
}
