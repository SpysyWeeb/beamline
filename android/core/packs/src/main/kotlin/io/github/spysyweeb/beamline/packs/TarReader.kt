package io.github.spysyweeb.beamline.packs

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** The stream is not a tar archive at all (as opposed to a tar that ends early). */
class TarFormatException(message: String) : IOException(message)

/**
 * Reads a POSIX (ustar) tar stream front to back, one entry at a time, without seeking.
 * Entry names lose a leading `./` (as `tar -C dir .` writes them).
 * Enough for pack archives: regular files and folders, PAX `path`/`size` records and GNU long
 * names are understood; links and devices are reported with their type so the caller can skip
 * them. Python's `tarfile` writes this format.
 */
class TarReader(private val input: InputStream) {

    data class Entry(val name: String, val size: Long, val type: Char) {
        val isFile: Boolean get() = type == '0' || type == '\u0000' || type == '7'
    }

    private var remaining = 0L
    private var padding = 0L
    private var finished = false

    /** The next entry, or null at the end of the archive. Skips what is left of the current one. */
    fun next(): Entry? {
        if (finished) return null
        skipFully(remaining + padding)
        remaining = 0
        padding = 0
        var paxName: String? = null
        var paxSize: Long? = null
        var longName: String? = null
        while (true) {
            val h = ByteArray(BLOCK)
            if (!readBlock(h)) {
                finished = true
                return null
            }
            if (h.all { it.toInt() == 0 }) {
                finished = true
                return null
            }
            checkChecksum(h)
            val type = (h[156].toInt() and 0xff).toChar()
            val size = parseSize(h)
            when (type) {
                'x', 'g' -> {
                    val pax = readBody(size)
                    if (type == 'x') {
                        parsePax(pax).let { rec ->
                            rec["path"]?.let { paxName = it }
                            rec["size"]?.toLongOrNull()?.let { paxSize = it }
                        }
                    }
                    continue
                }
                'L' -> {
                    longName = String(readBody(size), Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }
            }
            val name = paxName ?: longName ?: headerName(h)
            val realSize = paxSize ?: size
            remaining = if (type == '5') 0 else realSize
            padding = if (type == '5') 0 else (BLOCK - realSize % BLOCK) % BLOCK
            return Entry(name.removePrefix("./"), realSize, type)
        }
    }

    /** The data of the current entry; reads at most its size. Do not close it. */
    val entryStream: InputStream = object : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b < 0) throw EOFException("tar archive ends inside an entry")
            remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException("tar archive ends inside an entry")
            remaining -= n
            return n
        }
    }

    private fun readBody(size: Long): ByteArray {
        if (size > MAX_META) throw IOException("tar metadata record of $size bytes is too large")
        val b = ByteArray(size.toInt())
        readFully(b)
        skipFully((BLOCK - size % BLOCK) % BLOCK)
        return b
    }

    private fun headerName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val magic = String(h, 257, 5, Charsets.US_ASCII)
        val prefix = if (magic == "ustar") cString(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun parseSize(h: ByteArray): Long {
        if (h[124].toInt() and 0x80 != 0) {
            // base-256 (GNU/star) for files of 8 GiB and more
            var v = 0L
            for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xff)
            return v
        }
        return parseOctal(h, 124, 12)
    }

    private fun checkChecksum(h: ByteArray) {
        val stored = parseOctal(h, 148, 8)
        var sum = 0L
        for (i in 0 until BLOCK) sum += if (i in 148 until 156) 32 else (h[i].toLong() and 0xff)
        if (sum != stored) throw TarFormatException("not a tar archive (header checksum does not match)")
    }

    private fun parseOctal(h: ByteArray, off: Int, len: Int): Long {
        val s = String(h, off, len, Charsets.US_ASCII).trim('\u0000', ' ')
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: throw TarFormatException("not a tar archive (bad number '$s')")
    }

    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end].toInt() != 0) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun parsePax(b: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var i = 0
        while (i < b.size) {
            val sp = (i until b.size).firstOrNull { b[it] == ' '.code.toByte() } ?: break
            val len = String(b, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || i + len > b.size) break
            val rec = String(b, sp + 1, i + len - sp - 2, Charsets.UTF_8)
            val eq = rec.indexOf('=')
            if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
            i += len
        }
        return out
    }

    private fun readBlock(b: ByteArray): Boolean {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("tar archive ends inside a header")
            }
            off += n
        }
        return true
    }

    private fun readFully(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = input.read(b, off, b.size - off)
            if (n < 0) throw EOFException("tar archive ends early")
            off += n
        }
    }

    private fun skipFully(n: Long) {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (r < 0) throw EOFException("tar archive ends early")
            left -= r
        }
    }

    private companion object {
        const val BLOCK = 512
        const val MAX_META = 1L shl 20
    }
}
