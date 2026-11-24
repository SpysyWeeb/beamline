package io.github.spysyweeb.beamline.packs

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PackLibraryTest {

    @TempDir
    lateinit var tmp: File

    private fun TestScope.library(): PackLibrary {
        val d = StandardTestDispatcher(testScheduler)
        return PackLibrary(File(tmp, "packs"), TestPacks.READERS, this, d)
    }

    @Test
    fun `install shows up in the library state, then removal`() = runTest {
        val lib = library()
        lib.refresh()
        assertTrue(lib.state.value.scanned)
        assertTrue(lib.state.value.packs.isEmpty())

        assertTrue(lib.startInstall(TestPacks.archiveSource(TestPacks.archive())))
        assertFalse(lib.startInstall(TestPacks.archiveSource(TestPacks.archive())), "one install at a time")
        advanceUntilIdle()
        assertEquals(InstallState.Done("Pennsylvania", "2026-11-14"), lib.install.value)
        assertEquals(listOf("us-pa"), lib.state.value.packs.map { it.id })

        lib.dismissInstallResult()
        assertEquals(InstallState.Idle, lib.install.value)
        lib.remove("us-pa")
        assertTrue(lib.state.value.packs.isEmpty())
    }

    @Test
    fun `a failed install is reported and leaves the library as it was`() = runTest {
        val lib = library()
        val files = TestPacks.partFiles()
        val bad = TestPacks.archive(files, TestPacks.manifestFor(files) { it.replace("\"pack_id\":\"us-pa\"", "\"pack_id\":\"US PA\"") })
        lib.startInstall(TestPacks.archiveSource(bad, "us-pa.tar"))
        advanceUntilIdle()
        val failed = lib.install.value as InstallState.Failed
        assertEquals("us-pa.tar", failed.source)
        assertTrue(failed.message.contains("pack_id"), failed.message)
        assertTrue(lib.state.value.packs.isEmpty())
        assertEquals(emptyList<String>(), File(tmp, "packs").list()!!.toList())
    }

    @Test
    fun `cancel stops the copy and nothing is left`() = runTest {
        val lib = library()
        val bytes = TestPacks.archive()
        lateinit var library: PackLibrary
        // A source that cancels the install once part of it has been read, as a user would.
        val source = PackSource.Archive("slow.tar") {
            object : InputStream() {
                val inner = bytes.inputStream()
                var read = 0
                override fun read(): Int {
                    if (++read == 3000) library.cancelInstall()
                    return inner.read()
                }
            }
        }
        library = lib
        lib.startInstall(source)
        advanceUntilIdle()
        assertEquals(InstallState.Cancelled("slow.tar"), lib.install.value)
        assertEquals(emptyList<String>(), File(tmp, "packs").list()!!.toList())
    }

    /**
     * Break-review finding: a cancel that lands after the copy is checked (the installer reads the
     * clock for install-record.json just before the rename) used to be reported as "cancelled,
     * nothing was installed" while the pack was in place.
     */
    @Test
    fun `a cancel that lands after the pack is in place still reports it as added`() = runTest {
        lateinit var lib: PackLibrary
        lib = PackLibrary(
            File(tmp, "packs"), TestPacks.READERS, this, StandardTestDispatcher(testScheduler),
            now = {
                lib.cancelInstall()
                Instant.parse("2026-11-15T00:00:00Z")
            },
        )
        lib.startInstall(TestPacks.archiveSource(TestPacks.archive()))
        advanceUntilIdle()
        assertEquals(InstallState.Done("Pennsylvania", "2026-11-14"), lib.install.value)
        assertEquals(listOf("us-pa"), lib.state.value.packs.map { it.id })
    }
}
