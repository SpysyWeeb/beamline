package io.github.spysyweeb.beamline.routing.packs

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Valhalla's tile tar: an `index.bin` first, then the tiles (`2/000/747/779.gph`, ...), as
 * valhalla_build_extract and packbuilder/routing/packs.py write it. The engine memory-maps the
 * file and finds tiles through index.bin: one 16-byte entry per tile, little-endian
 * (offset of the tile data u64, tile id u32 = level | id << 3, size u32), in file order.
 */
object TileTar {
    const val INDEX_FILE = "index.bin"
    const val INDEX_ENTRY = 16
    private const val BLOCK = 512
    private const val RECORD = 20 * BLOCK // Python tarfile's record size: the file length is a multiple of it

    data class Member(val name: String, val offset: Long, val size: Long)

    /** Members of a tar by walking its headers (ustar; pax and GNU long-name headers are honoured). */
    fun members(file: File): List<Member> {
        val out = ArrayList<Member>()
        RandomAccessFile(file, "r").use { f ->
            val header = ByteArray(BLOCK)
            var pos = 0L
            var longName: String? = null
            while (pos + BLOCK <= f.length()) {
                f.seek(pos)
                f.readFully(header)
                if (header.all { it == 0.toByte() }) break
                val size = parseOctal(header, 124, 12)
                val type = header[156].toInt().toChar()
                val dataPos = pos + BLOCK
                val name = longName ?: run {
                    val n = cString(header, 0, 100)
                    val prefix = if (cString(header, 257, 6).startsWith("ustar")) cString(header, 345, 155) else ""
                    if (prefix.isEmpty()) n else "$prefix/$n"
                }
                longName = null
                when (type) {
                    'x' -> longName = paxPath(f, dataPos, size)
                    'L' -> longName = ByteArray(size.toInt()).also { f.seek(dataPos); f.readFully(it) }.toString(Charsets.UTF_8).trimEnd('\u0000')
                    '0', '\u0000' -> out += Member(name, dataPos, size)
                    else -> Unit
                }
                pos = dataPos + (size + BLOCK - 1) / BLOCK * BLOCK
            }
        }
        return out
    }

    private fun paxPath(f: RandomAccessFile, pos: Long, size: Long): String? {
        val b = ByteArray(size.toInt())
        f.seek(pos)
        f.readFully(b)
        return b.toString(Charsets.UTF_8).lineSequence().map { it.substringAfter(' ') }
            .firstOrNull { it.startsWith("path=") }?.removePrefix("path=")
    }

    /** The tile id of a tile path, as valhalla_build_extract get_tile_id(): level | digits << 3. */
    fun tileId(path: String): Long {
        val p = path.removeSuffix(".gph")
        val level = p.substringBefore('/').toLong()
        val digits = p.substringAfter('/').replace("/", "").toLong()
        return level or (digits shl 3)
    }

    /** The Valhalla version a tile was built with (GraphTileHeader.version_, 16 chars at byte 16). */
    fun tileVersion(tileStart: ByteArray): String = cString(tileStart, 16, 16)

    /** index.bin of a tile tar, decoded: (offset, tile id, size) per entry. */
    fun readIndex(file: File): List<Triple<Long, Long, Long>> {
        val idx = members(file).firstOrNull() ?: return emptyList()
        require(idx.name == INDEX_FILE) { "${file.name}: first member is ${idx.name}, not $INDEX_FILE" }
        val bytes = ByteArray(idx.size.toInt())
        RandomAccessFile(file, "r").use { it.seek(idx.offset); it.readFully(bytes) }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until bytes.size / INDEX_ENTRY).map {
            Triple(bb.long, bb.int.toLong() and 0xffffffffL, bb.int.toLong() and 0xffffffffL)
        }
    }

    /**
     * Writes a tile tar to [out]: index.bin first, then the [tiles] in the given order (sorted by
     * the caller), then fills the index. [source] supplies each tile's bytes and may throw to abort;
     * the caller deletes [out] then. Header fields follow ustar as Python's tarfile writes them.
     */
    fun write(out: File, tiles: List<String>, mtime: Long, source: (String) -> ByteArray) {
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            val index = ByteBuffer.allocate(INDEX_ENTRY * tiles.size).order(ByteOrder.LITTLE_ENDIAN)
            f.write(header(INDEX_FILE, index.capacity().toLong(), mtime))
            val indexPos = f.filePointer
            f.write(ByteArray(pad(index.capacity().toLong()).toInt()))
            for (name in tiles) {
                val data = source(name)
                f.write(header(name, data.size.toLong(), mtime))
                val dataPos = f.filePointer
                f.write(data)
                f.write(ByteArray((pad(data.size.toLong()) - data.size).toInt()))
                index.putLong(dataPos).putInt(tileId(name).toInt()).putInt(data.size)
            }
            f.write(ByteArray(2 * BLOCK)) // end of archive
            val len = f.filePointer
            if (len % RECORD != 0L) f.write(ByteArray((RECORD - len % RECORD).toInt()))
            f.seek(indexPos)
            f.write(index.array())
            f.fd.sync()
        }
    }

    /** The length [write] gives a tar of tiles with these sizes (for the free-space check). */
    fun sizeOf(tileSizes: Collection<Long>): Long {
        var len = BLOCK + pad(INDEX_ENTRY.toLong() * tileSizes.size)
        for (s in tileSizes) len += BLOCK + pad(s)
        len += 2 * BLOCK
        return (len + RECORD - 1) / RECORD * RECORD
    }

    private fun pad(n: Long) = (n + BLOCK - 1) / BLOCK * BLOCK

    private fun header(name: String, size: Long, mtime: Long): ByteArray {
        val h = ByteArray(BLOCK)
        val nb = name.toByteArray(Charsets.US_ASCII)
        require(nb.size <= 100) { "tar name too long: $name" }
        nb.copyInto(h, 0)
        putOctal(h, 100, 8, 0b110_100_100) // mode 0644
        putOctal(h, 108, 8, 0)
        putOctal(h, 116, 8, 0)
        putOctal(h, 124, 12, size)
        putOctal(h, 136, 12, mtime)
        h[156] = '0'.code.toByte()
        "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(h, 257)
        putOctal(h, 329, 8, 0)
        putOctal(h, 337, 8, 0)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xff }
        val chk = String.format("%06o", sum).toByteArray(Charsets.US_ASCII)
        chk.copyInto(h, 148)
        h[154] = 0
        h[155] = ' '.code.toByte()
        return h
    }

    private fun putOctal(h: ByteArray, off: Int, len: Int, v: Long) {
        val s = String.format("%0${len - 1}o", v).toByteArray(Charsets.US_ASCII)
        require(s.size == len - 1) { "value $v does not fit a $len-byte tar field" }
        s.copyInto(h, off)
        h[off + len - 1] = 0
    }

    private fun parseOctal(h: ByteArray, off: Int, len: Int): Long {
        if (h[off].toInt() and 0x80 != 0) { // base-256 (GNU) for big sizes
            var v = 0L
            for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xff)
            return v
        }
        val s = String(h, off, len, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.US_ASCII)
    }
}
