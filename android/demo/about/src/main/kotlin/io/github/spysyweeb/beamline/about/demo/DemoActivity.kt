package io.github.spysyweeb.beamline.about.demo

import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.spysyweeb.beamline.feature.about.AboutEvent
import io.github.spysyweeb.beamline.feature.about.AboutScreen
import io.github.spysyweeb.beamline.feature.about.FIRST_RUN_NOTICE_VERSION
import io.github.spysyweeb.beamline.feature.about.FirstRunNotice
import io.github.spysyweeb.beamline.feature.about.LicencesScreen
import io.github.spysyweeb.beamline.feature.about.Notices
import io.github.spysyweeb.beamline.feature.about.Splash
import io.github.spysyweeb.beamline.feature.about.SplashReveal
import io.github.spysyweeb.beamline.feature.about.loadNotices
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Host for track A's device checks: the start-up order of docs/architecture.md 5.4 item 3 (system
 * splash, SplashReveal over a stand-in map until "map ready", the first-run notice until accepted,
 * then a menu), About, the licences, and the icon in its shapes. Not shipped.
 *
 * Test extras (am start ... --ei / --ez / --es):
 *   hold_ms       keep the system splash this long (default 0)
 *   map_ready_ms  when the stand-in map says it is ready (default 900)
 *   reset_notice  true: forget the accepted notice first
 *   screen        home | about | licences | notice | icons: open that screen (no reveal)
 *   night         true | false: dark or light screens (default: the system's setting)
 */
class DemoActivity : ComponentActivity() {

    private var holdUntil = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        holdUntil = SystemClock.uptimeMillis() + intent.getIntExtra(EXTRA_HOLD_MS, 0)
        Splash.install(this) { SystemClock.uptimeMillis() < holdUntil }
        super.onCreate(savedInstanceState)
        useDarkBars(true)   // the start is the dark splash; DemoApp sets the bars from then on
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_RESET_NOTICE, false)) {
            prefs.edit().remove(KEY_ACCEPTED).apply()
        }
        val mapReadyMs = intent.getIntExtra(EXTRA_MAP_READY_MS, 900).toLong()
        val screen = intent.getStringExtra(EXTRA_SCREEN)
        val night = if (intent.hasExtra(EXTRA_NIGHT)) intent.getBooleanExtra(EXTRA_NIGHT, false) else null
        setContent { DemoApp(prefs, mapReadyMs, screen, night, ::useDarkBars) }
    }

    /** Light bar icons on the dark reveal and the night screens, dark ones by day; no light scrim. */
    private fun useDarkBars(dark: Boolean) {
        val clear = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    companion object {
        const val PREFS = "about_demo"
        const val KEY_ACCEPTED = "first_run_notice_accepted"
        const val EXTRA_HOLD_MS = "hold_ms"
        const val EXTRA_MAP_READY_MS = "map_ready_ms"
        const val EXTRA_RESET_NOTICE = "reset_notice"
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_NIGHT = "night"
    }
}

@Composable
private fun DemoApp(prefs: SharedPreferences, mapReadyMs: Long, startScreen: String?, nightOverride: Boolean?, darkBars: (Boolean) -> Unit) {
    val night = nightOverride ?: isSystemInDarkTheme()
    BeamlineTheme(night) {
        // A named screen opens directly (screenshots); a plain start plays the start-up order.
        var revealDone by rememberSaveable { mutableStateOf(startScreen != null) }
        var mapReady by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(mapReadyMs)
            mapReady = true
        }
        var accepted by remember { mutableIntStateOf(prefs.getInt(DemoActivity.KEY_ACCEPTED, 0)) }
        var screen by rememberSaveable { mutableStateOf(startScreen ?: "home") }
        SideEffect { darkBars(night || !revealDone) }
        val context = LocalContext.current
        val notices by produceState<Notices?>(null) {
            value = withContext(Dispatchers.IO) {
                try {
                    loadNotices(context)
                } catch (_: java.io.IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            StandInMap(night)
            when {
                !revealDone -> Unit
                accepted < FIRST_RUN_NOTICE_VERSION && startScreen == null -> FirstRunNotice(onAccept = {
                    prefs.edit().putInt(DemoActivity.KEY_ACCEPTED, FIRST_RUN_NOTICE_VERSION).apply()
                    accepted = FIRST_RUN_NOTICE_VERSION
                })
                else -> when (screen) {
                    "about" -> AboutScreen(sampleAbout(), onEvent = { e ->
                        screen = when (e) {
                            AboutEvent.Back -> "home"
                            AboutEvent.OpenLicences -> "licences"
                            AboutEvent.OpenDriverNotice -> "notice"
                        }
                    })
                    "licences" -> notices?.let { LicencesScreen(it, onBack = { screen = "about" }) }
                    "notice" -> FirstRunNotice(onAccept = { screen = "about" })
                    "icons" -> IconShapes(onBack = { screen = "home" })
                    else -> Home(onOpen = { screen = it })
                }
            }
            if (!revealDone) SplashReveal(mapReady, onFinished = { revealDone = true }, Modifier.fillMaxSize())
        }
    }
}
