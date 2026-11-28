package io.github.spysyweeb.beamline.feature.states

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * states.md P61 (audit F69): each visit of the States screen starts fresh: no search text,
 * selection or old banner from an earlier visit. Through the real screen and a real GetPacks (over
 * an InstalledData that holds Pennsylvania), with downloads off (catalogUrl ""): no network.
 */
@RunWith(AndroidJUnit4::class)
class StatesVisitTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var scope: CoroutineScope
    private lateinit var dir: File

    @Before
    fun setUp() {
        scope = MainScope()
        dir = File(context.cacheDir, "states-visit-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun aSecondVisitStartsFresh() {
        val getPacks = GetPacks(
            context = context,
            installed = StubInstalled(StatesTestInputs.paInstalled()),
            settings = SettingsStore(File(dir, "settings.json")),
            units = MutableStateFlow(UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL)),
            config = GetPacksConfig(
                catalogUrl = "", allowCleartext = false, minManifestVersion = 3, maxManifestVersion = 3,
                downloadsDir = File(dir, "downloads"), stateDir = File(dir, "getpacks"), freeBytes = { 9_900_000_000 },
                routingSetBytes = { 0L }, openStates = { Intent() }, openMap = { Intent() },
            ),
            scope = scope,
        )
        var shown by mutableStateOf(true)
        compose.setContent { BeamlineTheme(night = false) { if (shown) StatesScreen(getPacks, StatesMode.MANAGE, onClose = {}) } }
        compose.waitForIdle()
        compose.runOnUiThread {
            getPacks.core.screen.update { it.copy(query = "Oio", selected = "CA", readyPackId = "us-pa", fileProblem = "notes.tar" to "w") }
        }
        compose.waitForIdle()
        compose.runOnUiThread { shown = false }
        compose.waitForIdle()
        compose.runOnUiThread { shown = true }
        compose.waitForIdle()
        assertEquals(ScreenState(), getPacks.core.screen.value)
    }
}
