package io.github.spysyweeb.beamline.packs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.io.InputStream

class PackInstallerTest {

    @TempDir
    lateinit var tmp: File

    private val root by lazy { File(tmp, "packs") }
    private val installer by lazy { PackInstaller(root, TestPacks.READERS) }

    /** Only the installed pack folders: nothing hidden or half-copied may be left. */
    private fun contents(): List<String> = root.list()?.sorted() ?: emptyList()

    @Test
    fun `installs an archive, verifies it and records that`() {
        val progress = mutableListOf<InstallProgress>()
        val pack = installer.install(TestPacks.archiveSource(TestPacks.archive()), progress = { progress += it })
        assertEquals("us-pa", pack.id)
        assertEquals(listOf("us-pa"), contents())
        assertEquals(
            setOf("manifest.json", "install-record.json", "map.pmtiles", "routing.tar", "routing.json", "search.sqlite", "addresses.sqlite", "future.bin"),
            File(root, "us-pa").list()!!.toSet(),
        )
        assertEquals("sha256", pack.install?.verified)
        assertEquals("archive", pack.install?.source)
        val total = TestPacks.partFiles().values.sumOf { it.size.toLong() }
        assertEquals(total, progress.last().copiedBytes)
        assertEquals(total, progress.last().totalBytes)
        assertTrue(progress.zipWithNext().all { (a, b) -> b.copiedBytes >= a.copiedBytes })
        // The installed pack passes the normal scan.
        assertEquals(listOf("us-pa"), PackScanner(root, TestPacks.READERS).scan().packs.map { it.id })
    }

    @Test
    fun `installs a folder`() {
        val src = TestPacks.sourceFolder(File(tmp, "picked"))
        val pack = installer.install(TestPacks.folderSource(src))
        assertEquals("folder", pack.install?.source)
        assertEquals(listOf("us-pa"), contents())
    }

    @Test
    fun `a damaged part leaves nothing behind`() {
        val files = TestPacks.partFiles()
        val manifest = TestPacks.manifestFor(files)
        val damaged = files.toMutableMap().apply { put("search.sqlite", TestPacks.sqlite().also { it[200] = 9 }) }
        val e = assertThrows<InstallException> { installer.install(TestPacks.archiveSource(TestPacks.archive(damaged, manifest))) }
        assertEquals("search.sqlite is damaged: its SHA-256 does not match the manifest", e.message)
        assertEquals(emptyList<String>(), contents())
    }

    @Test
    fun `wrong sizes, missing parts and a manifest not first are refused`() {
        val files = TestPacks.partFiles()
        val manifest = TestPacks.manifestFor(files)
        val shorter = files.toMutableMap().apply { put("future.bin", "short".toByteArray()) }
        assertTrue(
            assertThrows<InstallException> { installer.install(TestPacks.archiveSource(TestPacks.archive(shorter, manifest))) }
                .message!!.contains("future.bin is 5 bytes in the archive, the manifest says 11"),
        )
        val missing = files - "routing.tar"
        assertTrue(
            assertThrows<InstallException> { installer.install(TestPacks.archiveSource(TestPacks.archive(missing, manifest))) }
                .message!!.endsWith("lacks routing.tar"),
        )
        val manifestLast = TestPacks.tar(files.toList() + ("manifest.json" to manifest.toByteArray()))
        assertTrue(
            assertThrows<InstallException> { installer.install(TestPacks.archiveSource(manifestLast)) }
                .message!!.contains("first entry must be manifest.json"),
        )
        val folder = TestPacks.sourceFolder(File(tmp, "picked"), missing, manifest)
        assertTrue(assertThrows<InstallException> { installer.install(TestPacks.folderSource(folder)) }.message!!.endsWith("lacks routing.tar"))
        assertEquals(emptyList<String>(), contents())
    }

    @Test
    fun `a pack this app cannot read is refused before anything is copied`() {
        val files = TestPacks.partFiles()
        var opened = 0
        val newer = TestPacks.manifestFor(files) { it.replace("\"manifest_version\":3", "\"manifest_version\":4") }
        val src = TestPacks.sourceFolder(File(tmp, "newer"), files, newer)
        val counting = PackSource.Folder("newer") { name ->
            if (name != "manifest.json") opened++
            File(src, name).inputStream()
        }
        val e = assertThrows<InstallException> { installer.install(counting) }
        assertTrue(e.message!!.contains("newer than this app reads"), e.message)
        assertEquals(0, opened)
        assertEquals(emptyList<String>(), contents())
    }

    /** Break-review finding: a full phone (0 bytes free) skipped the check and failed mid-copy. */
    @Test
    fun `a phone without the free space is refused before anything is copied`() {
        val full = PackInstaller(root, TestPacks.READERS, freeSpace = { 0L })
        val e = assertThrows<InstallException> { full.install(TestPacks.archiveSource(TestPacks.archive())) }
        assertTrue(e.message!!.endsWith("needs 201 MB of free space, the phone has 0 MB"), e.message)
        assertEquals(emptyList<String>(), contents())
        // The margin is a rule (assets/rules/packs.json): without it, 1 MB free is enough here.
        val noMargin = PackInstaller(root, TestPacks.READERS, rules = InstallRules(freeSpaceMarginMb = 0), freeSpace = { 1_000_000L })
        assertEquals("us-pa", noMargin.install(TestPacks.archiveSource(TestPacks.archive())).id)
    }

    /** Break-review finding: these said "reading X failed" or "bad number 'worldhe'". */
    @Test
    fun `a file that is not a tar is not a pack file, and a full disk is named as such`() {
        val e = assertThrows<InstallException> {
            installer.install(TestPacks.archiveSource("worldhello".repeat(200).toByteArray(), "notes.tar"))
        }
        assertEquals("notes.tar is not a pack file: it is not a tar archive", e.message)
        assertEquals(emptyList<String>(), contents())
        assertEquals(
            "The phone ran out of storage space while copying map.pmtiles. Free some space and try again.",
            PackInstaller.writeFailure("map.pmtiles", IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals(
            "Writing map.pmtiles into the app's storage failed: EIO",
            PackInstaller.writeFailure("map.pmtiles", IOException("EIO")),
        )
    }

    /** `tar -C folder .` writes names with a leading "./"; the Python reader accepts them too. */
    @Test
    fun `entry names starting with dot-slash are accepted`() {
        val files = TestPacks.partFiles()
        val manifest = TestPacks.manifestFor(files)
        val tar = TestPacks.tar(listOf("./manifest.json" to manifest.toByteArray()) + files.map { (n, d) -> "./$n" to d })
        assertEquals("us-pa", installer.install(TestPacks.archiveSource(tar)).id)
    }

    @Test
    fun `the install rules file the app ships is read`() {
        // Gradle runs the tests in the module folder (android/core/packs).
        assertEquals(200L, InstallRules.parse(File("../../app/src/main/assets/rules/packs.json").readText()).freeSpaceMarginMb)
    }

    @Test
    fun `cancelling leaves nothing behind`() {
        var chunks = 0
        assertThrows<InstallCancelledException> {
            installer.install(TestPacks.archiveSource(TestPacks.archive()), isCancelled = { chunks++ > 1 })
        }
        assertEquals(emptyList<String>(), contents())
    }

    @Test
    fun `a read error in the middle leaves nothing behind`() {
        val bytes = TestPacks.archive()
        val failing = PackSource.Archive("usb.tar") {
            object : InputStream() {
                var pos = 0
                override fun read(): Int {
                    if (pos >= bytes.size / 2) throw IOException("device unplugged") // inside search.sqlite
                    return bytes[pos++].toInt() and 0xff
                }
            }
        }
        val e = assertThrows<InstallException> { installer.install(failing) }
        assertEquals("reading usb.tar failed: device unplugged", e.message)
        assertEquals(emptyList<String>(), contents())
    }

    @Test
    fun `installing again replaces the old copy, also one copied under another folder name`() {
        TestPacks.write(root, "pa-copied-by-adb", id = "us-pa")
        File(root, "pa-copied-by-adb/old-marker").writeText("old")
        val files = TestPacks.partFiles()
        val newer = TestPacks.manifestFor(files) { it.replace("\"osm_snapshot\":\"2026-11-14\"", "\"osm_snapshot\":\"2026-12-01\"") }
        val pack = installer.install(TestPacks.archiveSource(TestPacks.archive(files, newer)))
        assertEquals("2026-12-01", pack.manifest.osmSnapshot)
        assertEquals(listOf("us-pa"), contents())
        assertFalse(File(root, "us-pa/old-marker").exists())
    }

    @Test
    fun `leftovers of a crash are cleaned up and an old copy is put back`() {
        root.mkdirs()
        File(root, ".staging-us-pa-123/map.pmtiles").apply { parentFile.mkdirs() }.writeText("half")
        File(root, ".removing-us-oh-5").mkdirs()
        TestPacks.write(root, ".old-us-nj-77", id = "us-nj") // crash between moving aside and moving in
        installer.cleanUp()
        assertEquals(listOf("us-nj"), contents())
        assertEquals("us-nj", PackScanner(root, TestPacks.READERS).scan().packs.single().id)
    }

    @Test
    fun `remove deletes the pack folder`() {
        installer.install(TestPacks.archiveSource(TestPacks.archive()))
        installer.remove("us-pa")
        assertEquals(emptyList<String>(), contents())
        assertThrows<IllegalArgumentException> { installer.remove("../etc") }
    }

    @Test
    fun `reads tar archives written by Python's tarfile`() {
        for (name in listOf("/python-ustar.tar", "/python-pax.tar")) {
            val tar = TarReader(javaClass.getResourceAsStream(name)!!)
            val seen = mutableListOf<Pair<String, Int>>()
            while (true) {
                val e = tar.next() ?: break
                if (e.isFile) seen += e.name to tar.entryStream.readBytes().size
            }
            val expected = mutableListOf("manifest.json" to 19, "map.pmtiles" to 1280)
            if (name.contains("pax")) {
                expected += "a-very-long-file-name-${"x".repeat(120)}.bin" to 10
                expected += "café.txt" to 15
            }
            expected += "empty.bin" to 0
            assertEquals(expected, seen, name)
        }
        // Not a tar at all.
        assertThrows<IOException> { TarReader(ByteArray(1024) { 7 }.inputStream()).next() }
        assertNotNull(TarReader(TestPacks.tar(listOf("a" to ByteArray(1))).inputStream()).next())
    }
}
