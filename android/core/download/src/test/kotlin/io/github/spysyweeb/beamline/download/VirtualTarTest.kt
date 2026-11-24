package io.github.spysyweeb.beamline.download

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** VirtualTar (PackHandoff.kt): the stand-in's tar, compared with one Python's tarfile wrote (packbuilder/catalog/tests/make_tar_fixture.py). */
class VirtualTarTest {
    @TempDir
    lateinit var dir: File

    private val mtime = 1763251200L

    private fun write(name: String, bytes: ByteArray) = File(dir, name).apply {
        writeBytes(bytes)
        setLastModified(mtime * 1000)
    }

    private fun files(): List<Pair<String, File>> = listOf(
        "manifest.json" to write("manifest.json", "{\"manifest_version\":3,\"pack_id\":\"us-pa\",\"parts\":{\"map\":{\"file\":\"map.pmtiles\"},\"search\":{\"file\":\"search.sqlite\"}}}\n".toByteArray()),
        "map.pmtiles" to write("map.pmtiles", ByteArray(1000) { (it % 251).toByte() }),
        "search.sqlite" to write("search.sqlite", ByteArray(513) { ((it * 7) % 256).toByte() }),
    )

    @Test
    fun theStreamEqualsPythonsTarfileUpToTheEndBlocks() {
        val ours = VirtualTar.stream(files()).use { it.readBytes() }
        val python = javaClass.getResourceAsStream("python-ustar.tar")!!.readBytes()
        assertArrayEquals(python.copyOf(ours.size), ours)
        assertTrue(python.drop(ours.size).all { it == 0.toByte() }, "tarfile only pads with zeros after the two end blocks")
        assertEquals(3, readUstar(ours.inputStream()).size)
    }

    @Test
    fun packFilesFollowTheManifestAndSkipMissingParts() {
        files()
        File(dir, "search.sqlite").delete()
        File(dir, ".transfer-search").writeText("partial")
        File(dir, "job.json").writeText("{}")
        val list = VirtualTar.packFiles(dir)
        assertEquals(listOf("manifest.json", "map.pmtiles"), list.map { it.first })
        val entries = VirtualTar.stream(list).use { readUstar(it) }
        assertEquals(listOf("manifest.json", "map.pmtiles"), entries.map { it.name })
        assertArrayEquals(File(dir, "map.pmtiles").readBytes(), entries[1].data)
    }

    @Test
    fun namesMustBePlainFileNames() {
        val f = write("x", ByteArray(1))
        assertThrows(IllegalArgumentException::class.java) { VirtualTar.stream(listOf("../x" to f)) }
        assertThrows(IllegalArgumentException::class.java) { VirtualTar.stream(listOf("a".repeat(101) to f)) }
    }

    @Test
    fun anEmptyListIsTwoZeroBlocks() {
        assertEquals(1024, VirtualTar.stream(emptyList()).use { it.readBytes() }.size)
    }
}
