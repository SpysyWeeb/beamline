package io.github.spysyweeb.beamline.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** VirtualTar (PackHandoff.kt): an uncompressed ustar made from files while it is read. */
internal object TarStream {

    fun header(name: String, size: Long, mtimeSeconds: Long): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val plain = !name.startsWith(".") && name.none { it == '/' || it == '\\' || it == ':' || it.code < 0x20 }
        require(nameBytes.size in 1..100 && plain) { "tar entry names must be plain file names of at most 100 bytes" }
        require(size in 0..0x1FFFFFFFFL) { "a tar entry must be smaller than 8 GiB" }
        val h = ByteArray(512)
        nameBytes.copyInto(h, 0)
        fun octal(value: Long, offset: Int, width: Int) {
            // width - 1 octal digits, zero padded, then NUL
            val digits = java.lang.Long.toOctalString(value).padStart(width - 1, '0')
            require(digits.length == width - 1)
            digits.toByteArray(Charsets.US_ASCII).copyInto(h, offset)
            h[offset + width - 1] = 0
        }
        octal(0b110100100, 100, 8) // mode 0644
        octal(0, 108, 8) // uid
        octal(0, 116, 8) // gid
        octal(size, 124, 12)
        octal(maxOf(0, mtimeSeconds), 136, 12)
        h[156] = '0'.code.toByte() // regular file
        "ustar".toByteArray(Charsets.US_ASCII).copyInto(h, 257)
        h[262] = 0
        h[263] = '0'.code.toByte()
        h[264] = '0'.code.toByte()
        // devmajor, devminor and prefix stay NUL, as Python's tarfile leaves them for a regular file
        // checksum: the header's bytes summed with the checksum field taken as eight spaces
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xff }
        val chk = Integer.toOctalString(sum).padStart(6, '0')
        chk.toByteArray(Charsets.US_ASCII).copyInto(h, 148)
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    fun stream(files: List<Pair<String, File>>): InputStream {
        // Names are checked at once, before anything is read.
        files.forEach { (name, _) -> header(name, 0, 0) }
        return Stream(files)
    }

    private class Stream(private val files: List<Pair<String, File>>) : InputStream() {
        private var index = -1
        private var chunk: ByteArray = ByteArray(0)
        private var chunkPos = 0
        private var data: InputStream? = null
        private var dataLeft = 0L
        private var padLeft = 0
        private var finished = false
        private var trailerLeft = 1024

        private fun nextEntry(): Boolean {
            index++
            if (index >= files.size) return false
            val (name, file) = files[index]
            val size = file.length()
            if (!file.isFile) throw IOException("a file of the pack is missing")
            chunk = header(name, size, file.lastModified() / 1000)
            chunkPos = 0
            data = FileInputStream(file)
            dataLeft = size
            padLeft = ((512 - (size % 512)) % 512).toInt()
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (finished) return -1
                if (chunkPos < chunk.size) {
                    val n = minOf(len, chunk.size - chunkPos)
                    chunk.copyInto(b, off, chunkPos, chunkPos + n)
                    chunkPos += n
                    return n
                }
                val d = data
                if (d != null && dataLeft > 0) {
                    val n = d.read(b, off, minOf(len.toLong(), dataLeft).toInt())
                    if (n < 0) throw IOException("a file of the pack got shorter while it was read")
                    dataLeft -= n
                    return n
                }
                if (d != null) {
                    d.close()
                    data = null
                    if (padLeft > 0) {
                        chunk = ByteArray(padLeft)
                        chunkPos = 0
                        padLeft = 0
                        continue
                    }
                }
                if (index < files.size && nextEntry()) continue
                if (trailerLeft > 0) {
                    chunk = ByteArray(trailerLeft)
                    chunkPos = 0
                    trailerLeft = 0
                    continue
                }
                finished = true
            }
        }

        override fun close() {
            data?.close()
            data = null
            finished = true
        }
    }

    fun packFiles(folder: File): List<Pair<String, File>> {
        val manifest = File(folder, "manifest.json")
        val root = Json.parseToJsonElement(manifest.readText(Charsets.UTF_8).removePrefix("﻿")) as? JsonObject
            ?: throw IOException("manifest.json is not a JSON object")
        val parts = root["parts"] as? JsonObject ?: throw IOException("manifest.json has no parts")
        val out = mutableListOf("manifest.json" to manifest)
        for ((_, part) in parts) {
            val file = ((part as? JsonObject)?.get("file") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (!CatalogParser.plainFileName(file)) continue
            val f = File(folder, file)
            if (f.isFile && out.none { it.first == file }) out += file to f
        }
        return out
    }
}
