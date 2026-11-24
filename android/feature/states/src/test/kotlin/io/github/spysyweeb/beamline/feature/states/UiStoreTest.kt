package io.github.spysyweeb.beamline.feature.states

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * getpacks/ui.json (6.5) is read and written on the store's writer only, never on the caller's
 * thread (the screen's events and the service's actions come on the main thread: docs/architecture.md
 * 1.2), the read before any change and the changes in order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UiStoreTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun nothingTouchesTheFileOnTheCallersThread() = runTest {
        val f = File(dir, "getpacks/ui.json")
        f.parentFile!!.mkdirs()
        f.writeText("""{"notifications_asked":true}""")
        val writer = StandardTestDispatcher(testScheduler)
        val store = UiStore(f, backgroundScope, writer)
        store.update { it.copy(firstRunPickerDone = true) }
        // Nothing read, applied or written yet: the caller only queued the work.
        assertFalse(store.loaded.value)
        assertEquals(UiChoices(), store.state.value)
        assertEquals("""{"notifications_asked":true}""", f.readText())

        runCurrent()
        assertTrue(store.loaded.value)
        assertEquals(UiChoices(firstRunPickerDone = true, notificationsAsked = true), store.state.value) // applied after the read
        assertEquals("""{"first_run_picker_done":true,"notifications_asked":true}""", f.readText())
        assertFalse(File(f.parentFile, "ui.json.tmp").exists())

        // In the order asked for: the last one wins on disk too.
        store.update { it.copy(showOnMap = "us-pa") }
        store.update { it.copy(showOnMap = null) }
        runCurrent()
        assertEquals(null, store.state.value.showOnMap)
        assertEquals("""{"first_run_picker_done":true,"notifications_asked":true}""", f.readText())

        // Item 40: the updates seen on the States screen, per pack, survive a new process.
        store.update { it.copy(updatesSeen = mapOf("us-pa" to 202611230932L)) }
        runCurrent()
        assertEquals("""{"first_run_picker_done":true,"notifications_asked":true,"updates_seen":{"us-pa":202611230932}}""", f.readText())
        val again = UiStore(f, backgroundScope, writer)
        runCurrent()
        assertEquals(mapOf("us-pa" to 202611230932L), again.state.value.updatesSeen)
    }

    @Test
    fun aMissingOrUnreadableFileIsAFreshInstall() = runTest {
        val writer = StandardTestDispatcher(testScheduler)
        val missing = UiStore(File(dir, "none/ui.json"), backgroundScope, writer)
        val bad = File(dir, "bad.json").also { it.writeText("{not json") }
        val unreadable = UiStore(bad, backgroundScope, writer)
        runCurrent()
        assertTrue(missing.loaded.value && unreadable.loaded.value)
        assertEquals(UiChoices(), missing.state.value)
        assertEquals(UiChoices(), unreadable.state.value)
        assertFalse(File(dir, "none/ui.json").exists()) // nothing changed, nothing written
    }
}
