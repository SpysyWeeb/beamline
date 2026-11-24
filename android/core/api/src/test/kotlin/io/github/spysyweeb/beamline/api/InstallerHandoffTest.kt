package io.github.spysyweeb.beamline.api

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackFolder
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The installer hand-off added for downloads in Round G0 (docs/architecture.md 2.2,
 * docs/architecture-downloads.md 6.6): an installer written before it (only the members of
 * Round 0) still compiles, and the new members then say "not supported", so the downloader falls
 * back to the archive install and hides the house-number actions.
 */
class InstallerHandoffTest {

    /** An InstalledData with only the members Round 0 had. */
    private class RoundZeroInstaller : InstalledData {
        override val installed: StateFlow<InstalledSet> = MutableStateFlow(InstalledSet())
        override val install: StateFlow<InstallStatus> = MutableStateFlow(InstallStatus.Idle)
        val calls = mutableListOf<String>()
        override suspend fun refresh() {
            calls += "refresh"
        }
        override suspend fun inspect(archive: PackArchive): ArchiveInfo =
            ArchiveInfo("us-pa", "Pennsylvania", "PA", "2026-11-14", 1L, null, false, 2L, emptyList())
        override fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean {
            calls += "startInstall"
            return true
        }
        override fun cancelInstall() {}
        override fun dismissInstallResult() {}
        override suspend fun remove(packId: String): String? = null
        override fun holdChanges(reason: String): AutoCloseable? = AutoCloseable { }
    }

    private fun pack(packVersion: Long? = null) = PackInfo(
        packId = "us-pa", name = "Pennsylvania", state = "PA", osmSnapshot = "2026-11-14",
        bounds = Bounds(-80.52, 39.72, -74.69, 42.27), attribution = "© OpenStreetMap contributors",
        folder = "/packs/us-pa", mapFile = "/packs/us-pa/map.pmtiles", mapMinZoom = 0, mapMaxZoom = 14,
        searchFile = null, addressesFile = null, addressesSkipped = true, searchProblem = null,
        searchRankingSha256 = null, inRoutingSet = true, verified = true, bytesOnDisk = 1L,
    ).let { if (packVersion == null) it else it.copy(packVersion = packVersion) }

    @Test
    fun `an installer without the folder install says so and starts nothing`() {
        val installer = RoundZeroInstaller()
        val folder = PackFolder("Pennsylvania", "/downloads/us-pa-202611152241")
        assertFalse(installer.installsFromFolder)
        assertFalse(installer.startInstallFromFolder(folder, includeAddresses = true))
        assertFalse(installer.changesOptionalParts)
        assertFalse(installer.startAddOptionalParts(folder))
        assertEquals("This version of Beamline can't remove house numbers.", runBlocking { installer.removeOptionalParts("us-pa") })
        assertTrue(installer.calls.isEmpty(), "the defaults must not reach the archive install: ${installer.calls}")
    }

    @Test
    fun `a pack folder keeps its label and path`() {
        val folder = PackFolder("Pennsylvania", "/downloads/us-pa-202611152241")
        assertEquals("Pennsylvania", folder.label)
        assertEquals("/downloads/us-pa-202611152241", folder.dir)
    }

    @Test
    fun `a pack without a pack_version reads as null, a later build compares higher`() {
        assertNull(pack().packVersion)
        val old = pack(202611142241)
        val new = pack(202612010600)
        val oldVersion = checkNotNull(old.packVersion)
        val newVersion = checkNotNull(new.packVersion)
        assertEquals(202611142241L, oldVersion)
        assertTrue(newVersion > oldVersion)
        assertEquals(old, old.copy(), "packVersion takes part in equality like every other field")
        assertFalse(old == new)
    }
}
