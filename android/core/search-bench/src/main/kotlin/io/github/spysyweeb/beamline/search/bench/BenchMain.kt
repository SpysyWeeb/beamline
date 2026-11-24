package io.github.spysyweeb.beamline.search.bench

import io.github.spysyweeb.beamline.search.SearchEngine
import io.github.spysyweeb.beamline.search.address.AddressIndex
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JVM benchmark: ./gradlew :core:search-bench:bench  (args: searchDb addressesDb queries.json out.json)
 * Memory: Java heap in use after a full GC with both databases open and warmed, and the process's
 * own memory read in-process (no child process: G's network-rule scan, docs/architecture-downloads.md
 * 4.2, allows none): peak and current resident set from /proc/self/status where it
 * exists (Linux, Android), else the committed virtual memory the JVM reports. SQLite's page cache
 * and the native library live outside the Java heap, so the process figure is the number to read.
 * (Until round 3 this read Windows' working set through a PowerShell child process.)
 */
fun main(args: Array<String>) {
    require(args.size >= 4) { "usage: BenchMain <search.sqlite> <addresses.sqlite> <search_queries.json> <out.json>" }
    val (searchDb, addressesDb, queriesFile, outFile) = args
    val t0 = System.nanoTime()
    val res = SearchBench(searchDb, addressesDb.ifEmpty { null }, File(queriesFile).readText()) { println(it) }.run()
    val wall = (System.nanoTime() - t0) / 1e9
    // memory with one engine (addresses attached) and one address index open, after use
    val eng = SearchEngine(searchDb, addressesDb.ifEmpty { null })
    val idx = if (addressesDb.isNotEmpty()) AddressIndex(addressesDb) else null
    for (q in listOf("wal", "rest stop", "sheetz", "4319 peach st", "erie city hall", "gas", "pizza near pittsburgh")) {
        eng.search(q, 42.1292, -80.0851)
    }
    idx?.geocode("626 State St Erie PA")
    val rt = Runtime.getRuntime()
    System.gc()
    Thread.sleep(200)
    val heapUsed = rt.totalMemory() - rt.freeMemory()
    // /proc/self/status: "VmHWM:  123456 kB" (peak resident) and "VmRSS: ..." (current); absent on Windows
    val status = runCatching { File("/proc/self/status").readLines() }.getOrNull()
    fun kb(key: String) = status?.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLongOrNull()
    val peakRss = kb("VmHWM")?.times(1024)
    val rss = kb("VmRSS")?.times(1024)
    val committed = runCatching {
        (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).committedVirtualMemorySize
    }.getOrNull()?.takeIf { it > 0 }
    eng.close()
    idx?.close()
    val out = buildJsonObject {
        res.forEach { (k, v) -> put(k, v) }
        put("platform", buildJsonObject {
            put("kind", "JVM")
            put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"))
            put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
            put("cpus", Runtime.getRuntime().availableProcessors())
            put("max_heap_mb", rt.maxMemory() / 1048576)
        })
        put("memory", buildJsonObject {
            put("java_heap_used_after_gc_mb", Math.round(heapUsed / 1048576.0 * 10) / 10.0)
            peakRss?.let { put("process_peak_rss_mb", Math.round(it / 1048576.0 * 10) / 10.0) }
            rss?.let { put("process_rss_mb", Math.round(it / 1048576.0 * 10) / 10.0) }
            committed?.let { put("process_committed_mb", Math.round(it / 1048576.0 * 10) / 10.0) }
            put("how", "heap: Runtime total-free after System.gc() with an engine (addresses attached) and an address " +
                "index open; process: /proc/self/status VmHWM and VmRSS where present, and the committed virtual memory " +
                "of com.sun.management.OperatingSystemMXBean (all include the JVM itself)")
        })
        put("bench_wall_s", Math.round(wall * 10) / 10.0)
    }
    File(outFile).parentFile?.mkdirs()
    File(outFile).writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), out))
    println("wrote $outFile in ${"%.1f".format(wall)} s")
}
