package io.github.spysyweeb.beamline.search

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * Writes [file] atomically as the stores of docs/architecture.md do: the whole text to
 * `<name>.tmp` in the same folder, `fd.sync()`, close, rename over the file (on failure: delete the
 * file and rename again). Throws [IOException] with a fixed message (the file's name only, never
 * its content) when it cannot.
 */
internal fun writeAtomically(file: File, text: String) {
    val dir = file.absoluteFile.parentFile
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create the folder of ${file.name}")
    val tmp = File(dir, file.name + ".tmp")
    try {
        tmp.outputStream().use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
    } catch (_: IOException) {
        throw IOException("cannot write ${tmp.name}")
    }
    try {
        Files.move(tmp.toPath(), file.absoluteFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        renameFallback(tmp, file)
    } catch (_: IOException) {
        renameFallback(tmp, file)
    }
}

private fun renameFallback(tmp: File, file: File) {
    if (tmp.renameTo(file)) return
    file.delete()
    if (!tmp.renameTo(file)) throw IOException("cannot replace ${file.name}")
}

/**
 * A count of writes handed to an executor and not yet finished, with a wait for it to reach zero.
 * Works whatever the executor (a direct one in tests, a single thread, or the many threads of
 * Dispatchers.IO), unlike waiting for a marker task, which on a pool can run before the writes.
 */
internal class PendingCount {
    private val lock = ReentrantLock()
    private val zero = lock.newCondition()
    private var n = 0

    fun up() = lock.withLock { n++ }

    fun down() = lock.withLock {
        n--
        if (n <= 0) zero.signalAll()
    }

    /** Blocks until the count is zero or [timeoutMs] has passed. */
    fun awaitZero(timeoutMs: Long) {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (n > 0 && left > 0) left = zero.awaitNanos(left)
        }
    }
}

/**
 * A store's writer: [submit] hands over the newest whole text; the writes run on [executor] one at
 * a time (a lock, so a pool such as Dispatchers.IO is fine) and always write the newest text
 * submitted so far; a queued write that finds that text already written does nothing
 * (docs/architecture-saved-places.md SP51). [onResult] runs after each real write with true when
 * it succeeded.
 */
internal class LatestTextWriter(
    private val file: File,
    private val executor: Executor,
    private val onResult: (Boolean) -> Unit = {},
) {
    private val lock = Any()
    private val writeLock = Any()
    private var latest: String? = null
    private var latestVersion = 0L
    private var writtenVersion = 0L
    private val pending = PendingCount()

    fun submit(text: String) {
        synchronized(lock) {
            latest = text
            latestVersion++
        }
        pending.up()
        all.up()
        try {
            executor.execute {
                try {
                    writeNewest()
                } finally {
                    pending.down()
                    all.down()
                }
            }
        } catch (e: RuntimeException) {   // a rejected task never runs: it is not pending
            pending.down()
            all.down()
            throw e
        }
    }

    private fun writeNewest() {
        synchronized(writeLock) {
            val (version, text) = synchronized(lock) { latestVersion to latest }
            if (text == null || version <= writtenVersion) return
            val ok = try {
                writeAtomically(file, text)
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
            if (ok) writtenVersion = version
            onResult(ok)
        }
    }

    /** Blocks until every write submitted to this writer so far has run (tests and shutdown). */
    fun awaitIdle(timeoutMs: Long = 10_000) = pending.awaitZero(timeoutMs)

    companion object {
        /** Every write of every store not yet finished (for [awaitStoreWrites]). */
        private val all = PendingCount()

        /**
         * The writer of the small JSON stores that take no executor (recents): Dispatchers.IO, as
         * docs/architecture.md 1.2 says for JSON stores; no thread of the stores' own.
         */
        val io: Executor by lazy { Dispatchers.IO.asExecutor() }

        /** Blocks until every write any store has submitted so far has run (tests and shutdown). */
        fun awaitAll(timeoutMs: Long = 10_000) = all.awaitZero(timeoutMs)
    }
}

/**
 * Blocks until every write the stores of this module have submitted so far is on disk (or has
 * failed): for tests that delete the folder afterwards, and for a clean shutdown.
 */
fun awaitStoreWrites(timeoutMs: Long = 10_000) = LatestTextWriter.awaitAll(timeoutMs)
