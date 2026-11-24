package io.github.spysyweeb.beamline.search.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_URI
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A read-only SQLite connection through androidx.sqlite's bundled driver (the same SQLite build
 * on the JVM and on Android), with a small prepared-statement cache.
 *
 * Values come back as Python's sqlite3 module would give them: INTEGER -> [Long], REAL ->
 * [Double], TEXT -> [String], BLOB -> [ByteArray], NULL -> null.
 *
 * Thread safety: the bundled SQLite is built with SQLITE_THREADSAFE=2 (no mutex on a connection),
 * and the cached statements are reused. Every use of the connection therefore runs under one lock,
 * so an engine shared by several threads gives the single-thread answers (without the lock it
 * crashed the JVM or spun forever; review of 2026-11-15). Statements of different threads
 * interleave; a query that is running cannot be interrupted (the build omits the progress
 * callback), so a stale query still finishes and the next one waits for it.
 */
class SqlDb private constructor(private val conn: SQLiteConnection, val path: String) : AutoCloseable {

    private val lock = ReentrantLock()
    private var closed = false

    private val cache = object : LinkedHashMap<String, SQLiteStatement>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SQLiteStatement>): Boolean {
            if (size > MAX_CACHED) {
                eldest.value.close()
                return true
            }
            return false
        }
    }

    /** All rows of a query. */
    fun query(sql: String, args: List<Any?> = emptyList()): List<Array<Any?>> {
        val out = ArrayList<Array<Any?>>()
        run(sql, args) { st ->
            val n = st.getColumnCount()
            Array(n) { i -> value(st, i) }.also { out.add(it) }
        }
        return out
    }

    fun query(sql: String, vararg args: Any?): List<Array<Any?>> = query(sql, args.toList())

    /** First row or null. */
    fun first(sql: String, vararg args: Any?): Array<Any?>? {
        var row: Array<Any?>? = null
        run(sql, args.toList(), limit = 1) { st -> row = Array(st.getColumnCount()) { i -> value(st, i) } }
        return row
    }

    /** Runs [sql], calling [onRow] for every row (at most [limit] rows). */
    fun run(sql: String, args: List<Any?>, limit: Int = Int.MAX_VALUE, onRow: (SQLiteStatement) -> Unit) = lock.withLock {
        val st = statement(sql)
        try {
            st.clearBindings()
            args.forEachIndexed { i, a -> bind(st, i + 1, a) }
            var n = 0
            while (n < limit && st.step()) {
                onRow(st)
                n++
            }
        } finally {
            try {
                st.reset()
            } catch (_: SQLiteException) {
                // reset repeats the error of the last step; the statement stays usable
            }
        }
    }

    fun exec(sql: String) = lock.withLock {
        check(!closed) { "database is closed: $path" }
        val st = conn.prepare(sql)
        try {
            while (st.step()) { /* drain */ }
        } finally {
            st.close()
        }
    }

    /** Caller holds [lock]. */
    private fun statement(sql: String): SQLiteStatement {
        check(!closed) { "database is closed: $path" }
        return cache[sql] ?: conn.prepare(sql).also { cache[sql] = it }
    }

    override fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        cache.values.forEach { it.close() }
        cache.clear()
        conn.close()
    }

    companion object {
        private const val MAX_CACHED = 128

        /**
         * How long a read waits for a file lock before failing with SQLITE_BUSY, as Python's
         * sqlite3.connect() default (5 s). On Windows, readers in different processes briefly
         * take SQLite's PENDING byte while they acquire a shared lock and can collide; without
         * a busy timeout such a collision fails at once ("database is locked", seen once by the
         * review while Python processes read the same pack). The bundled driver sets none.
         */
        const val BUSY_TIMEOUT_MS = 5000

        /**
         * Opens [path] read-only. The file name goes to SQLite as a percent-encoded `file:` URI
         * with `mode=ro` (as packbuilder/search/query.py does), so '#', '%', '?' and spaces in the
         * path are safe; `PRAGMA query_only` is set as well.
         */
        fun openReadOnly(path: String): SqlDb {
            val f = File(path)
            require(f.isFile) { "database not found: $path" }
            val driver = BundledSQLiteDriver()
            val conn = driver.open(readOnlyUri(f), SQLITE_OPEN_READONLY or SQLITE_OPEN_URI)
            val db = SqlDb(conn, f.absolutePath)
            try {
                db.exec("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
                db.exec("PRAGMA query_only=1")
            } catch (e: Throwable) {
                db.close()
                throw e
            }
            return db
        }

        /** `file:` URI with mode=ro; every byte outside [A-Za-z0-9/._~-] is percent-encoded. */
        fun readOnlyUri(f: File): String {
            var p = f.absoluteFile.toPath().normalize().toString().replace('\\', '/')
            if (!p.startsWith("/")) p = "/$p"          // Windows drive path: file:///C:/...
            val sb = StringBuilder("file://")
            for (b in p.toByteArray(Charsets.UTF_8)) {
                val c = b.toInt() and 0xFF
                val ch = c.toChar()
                if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '/' || ch == '.' || ch == '_' ||
                    ch == '~' || ch == '-' || (ch == ':' && sb.length < 12)
                ) sb.append(ch) else sb.append('%').append("%02X".format(c))
            }
            return sb.append("?mode=ro").toString()
        }

        private fun bind(st: SQLiteStatement, i: Int, a: Any?) {
            when (a) {
                null -> st.bindNull(i)
                is Long -> st.bindLong(i, a)
                is Int -> st.bindLong(i, a.toLong())
                is Double -> st.bindDouble(i, a)
                is Float -> st.bindDouble(i, a.toDouble())
                is String -> st.bindText(i, a)
                is Boolean -> st.bindLong(i, if (a) 1 else 0)
                is ByteArray -> st.bindBlob(i, a)
                else -> throw IllegalArgumentException("cannot bind ${a::class}")
            }
        }

        fun value(st: SQLiteStatement, i: Int): Any? = when (st.getColumnType(i)) {
            androidx.sqlite.SQLITE_DATA_INTEGER -> st.getLong(i)
            androidx.sqlite.SQLITE_DATA_FLOAT -> st.getDouble(i)
            androidx.sqlite.SQLITE_DATA_TEXT -> st.getText(i)
            androidx.sqlite.SQLITE_DATA_BLOB -> st.getBlob(i)
            else -> null
        }
    }
}
