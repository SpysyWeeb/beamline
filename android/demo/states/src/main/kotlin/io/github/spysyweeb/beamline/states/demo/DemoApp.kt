package io.github.spysyweeb.beamline.states.demo

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import io.github.spysyweeb.beamline.feature.states.GetPacks
import io.github.spysyweeb.beamline.feature.states.GetPacksConfig
import io.github.spysyweeb.beamline.feature.states.GetPacksHost
import io.github.spysyweeb.beamline.feature.states.StatesMode
import io.github.spysyweeb.beamline.feature.states.StatesScreen
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** The demo's process: GetPacks over DemoInstalled, as I's AppGraph will build it over InstalledLibrary (9.4 item 1). */
class DemoApplication : Application(), GetPacksHost {
    override val getPacks: GetPacks by lazy {
        val scope = MainScope()
        val external = getExternalFilesDir(null) ?: filesDir
        val free: (File) -> Long = { it.usableSpace }
        val installed = DemoInstalled(File(external, "demo-packs"), scope, free)
        GetPacks(
            context = this,
            installed = installed,
            settings = SettingsStore(File(filesDir, "settings.json")),
            units = MutableStateFlow(UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL)),
            config = GetPacksConfig(
                catalogUrl = BuildConfig.CATALOG_URL,
                allowCleartext = BuildConfig.DEBUG,
                minManifestVersion = 2, // the demo installs the round-2 z14 archive's parts (10.3)
                maxManifestVersion = 3,
                downloadsDir = File(external, "downloads"),
                stateDir = File(filesDir, "getpacks"),
                freeBytes = free,
                routingSetBytes = { 0L },
                openStates = { Intent(this, DemoActivity::class.java).setAction(ACTION_OPEN_STATES) },
                openMap = { Intent(this, DemoActivity::class.java).setAction(ACTION_OPEN_MAP) },
            ),
            scope = scope,
        )
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // 4.2 item 5: an early warning while developing (Java sockets only).
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectUntaggedSockets().penaltyLog().build())
            // docs/architecture.md 1.2: file work belongs on Dispatchers.IO. Every disk read or write on the main
            // thread is logged (tag StrictMode) with its stack, so G's code can be checked for it on the device.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build())
        }
    }

    companion object {
        const val ACTION_OPEN_STATES = "io.github.spysyweeb.beamline.action.OPEN_STATES"
        const val ACTION_OPEN_MAP = "io.github.spysyweeb.beamline.action.OPEN_MAP"
    }
}

/** First run while GetPacks says so, then a stand-in "map" page with a button to the States screen. */
class DemoActivity : ComponentActivity() {
    private val getPacks get() = (application as GetPacksHost).getPacks
    private val screen = mutableStateOf(Screen.AUTO)

    enum class Screen { AUTO, MAP, STATES }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val night = isSystemInDarkTheme()
            // The system bars are drawn over the screen (edge to edge): their icons dark by day, light by
            // night, so the clock and battery stay readable on the screen's own background. The activity
            // handles uiMode itself (no restart), so this follows every change. I's activity does the same.
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !night
                    isAppearanceLightNavigationBars = !night
                }
            }
            BeamlineTheme(night = night) {
                val firstRun by getPacks.showFirstRunPicker.collectAsState()
                val chip by getPacks.mapChip.collectAsState()
                val show by getPacks.showOnMap.collectAsState()
                var page by remember { screen }
                when {
                    page == Screen.STATES -> StatesScreen(getPacks, StatesMode.MANAGE, onClose = { page = Screen.MAP })
                    firstRun && page == Screen.AUTO -> StatesScreen(getPacks, StatesMode.FIRST_RUN, onClose = { page = Screen.MAP })
                    else -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.padding(24.dp)) {
                            Text("States demo: the map would be here.", style = MaterialTheme.typography.titleMedium)
                            show?.let {
                                Text("${it.name} is ready.", Modifier.padding(top = 12.dp))
                                Button(onClick = { getPacks.showOnMapDone() }) { Text("OK") }
                            }
                            chip?.let { c -> Button(onClick = { page = Screen.STATES }, Modifier.padding(top = 12.dp)) { Text(c.text) } }
                            Button(onClick = { page = Screen.STATES }, Modifier.padding(top = 12.dp)) { Text("States") }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(i: Intent?) {
        when (i?.action) {
            DemoApplication.ACTION_OPEN_STATES -> screen.value = Screen.STATES
            DemoApplication.ACTION_OPEN_MAP -> screen.value = Screen.MAP
        }
    }

    override fun onStart() {
        super.onStart()
        getPacks.onAppVisible()
    }
}
