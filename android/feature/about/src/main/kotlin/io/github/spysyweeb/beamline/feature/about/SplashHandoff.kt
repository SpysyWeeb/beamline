package io.github.spysyweeb.beamline.feature.about

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * What Splash.install sets up, and what SplashReveal waits for: the reveal starts its clock only
 * when the system splash has gone, so its first frame (the icon in its circle) replaces the
 * system splash's identical picture without a jump.
 */
internal object SplashHandoff {
    /** False from Splash.install until the system splash has been removed. */
    @Volatile
    var handedOver: Boolean = true
        private set

    /** Uptime when keepOnScreen first said false after the last install, or -1. */
    @Volatile
    var keepReleasedAtMs: Long = -1L
        private set

    /**
     * When no system splash is shown (an activity re-created after a rotation, a start that showed
     * no starting window) the exit callback never comes: the reveal then starts this long after
     * keepOnScreen let go.
     */
    const val NO_SPLASH_WAIT_MS = 300L

    /** Call in the activity's onCreate before super.onCreate (core-splashscreen's rule). */
    fun install(activity: ComponentActivity, keepOnScreen: () -> Boolean) {
        val splash = activity.installSplashScreen()
        handedOver = false
        keepReleasedAtMs = -1L
        splash.setKeepOnScreenCondition {
            val keep = keepOnScreen()
            if (!keep && keepReleasedAtMs < 0L) keepReleasedAtMs = SystemClock.uptimeMillis()
            keep
        }
        // No exit animation of the system's own: the reveal continues from the same picture.
        splash.setOnExitAnimationListener { provider ->
            provider.remove()
            handedOver = true
        }
    }

    /** True when the reveal may start its clock now. */
    fun mayStart(nowUptimeMs: Long): Boolean {
        if (handedOver) return true
        val released = keepReleasedAtMs
        return released >= 0L && nowUptimeMs - released >= NO_SPLASH_WAIT_MS
    }
}
