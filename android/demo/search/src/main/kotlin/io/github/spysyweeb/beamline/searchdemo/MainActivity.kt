package io.github.spysyweeb.beamline.searchdemo

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.search.AddressInfo
import io.github.spysyweeb.beamline.search.SearchResponse
import io.github.spysyweeb.beamline.search.SearchResult
import io.github.spysyweeb.beamline.search.SearchService
import io.github.spysyweeb.beamline.search.bench.ConformanceDump
import io.github.spysyweeb.beamline.search.bench.MathParity
import io.github.spysyweeb.beamline.search.bench.SearchBench
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "BeamlineSearch"

/** The two fixed positions (packbuilder/search/tests/points.json). */
data class Place(val key: String, val label: String, val lat: Double, val lon: Double)

val PLACES = listOf(
    Place("erie", "Downtown Erie", 42.1292, -80.0851),
    Place("pittsburgh", "Pittsburgh", 40.4406, -79.9959),
)

/**
 * One worker thread for every database call. The engine is safe to share between threads, but a
 * running query cannot be interrupted, so one worker keeps the queries in order (a stale answer is
 * dropped by collectLatest) and closing waits for the query in progress.
 */
object Worker {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "search").apply { isDaemon = true } }
    val dispatcher = executor.asCoroutineDispatcher()

    /** Closes [s] on the worker, after the query in progress. */
    fun close(s: SearchService, why: String) = executor.execute {
        s.close()
        Log.i(TAG, "search service closed ($why)")
    }
}

/**
 * Search demo. Extras (for scripts): q = query text, at = erie | pittsburgh, bench = true runs the
 * latency benchmark and writes files/search/bench-device.json, dump = true answers every
 * conformance query and writes files/search/kotlin-search-device.json.
 */
class MainActivity : ComponentActivity() {
    private val query = mutableStateOf("")
    private val place = mutableStateOf(PLACES[0])

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyExtras(intent)
        val dir = File(getExternalFilesDir(null), "search")
        val bench = intent.getBooleanExtra("bench", false)
        val dump = intent.getBooleanExtra("dump", false)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    if (bench || dump) BenchScreen(dir, dump) else SearchScreen(dir, query, place)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyExtras(intent)
    }

    private fun applyExtras(i: Intent) {
        i.getStringExtra("q")?.let { query.value = it }
        i.getStringExtra("at")?.let { a -> PLACES.firstOrNull { it.key == a }?.let { place.value = it } }
    }
}

private fun searchFile(dir: File) = File(dir, "pa-search.sqlite")
private fun addressFile(dir: File) = File(dir, "pa-addresses-slim.sqlite")

@Composable
fun SearchScreen(dir: File, query: MutableState<String>, place: MutableState<Place>) {
    var service by remember { mutableStateOf<SearchService?>(null) }
    var status by remember { mutableStateOf("Opening the search pack...") }
    var response by remember { mutableStateOf<SearchResponse?>(null) }
    // The service belongs to this screen: closed when the screen leaves the composition (a rotation
    // recreates the Activity; review of 2026-11-15: each rotation used to leave one open).
    DisposableEffect(service) {
        val s = service
        onDispose { if (s != null) Worker.close(s, "screen disposed") }
    }
    LaunchedEffect(Unit) {
        var opened: SearchService? = null
        val r = try {
            withContext(Worker.dispatcher) {
                runCatching {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val s = SearchService.open(searchFile(dir).path, addressFile(dir).takeIf { it.isFile }?.path)
                    opened = s
                    val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    Triple(s, ms, s.engine.sqliteInfo().first)
                }
            }
        } catch (e: CancellationException) {
            opened?.let { Worker.close(it, "screen left while opening") }   // finished after the screen went away
            throw e
        }
        r.onSuccess { (s, ms, v) ->
            service = s
            status = "Offline · SQLite $v · packs opened in ${"%.0f".format(ms)} ms"
            Log.i(TAG, "opened in $ms ms, SQLite $v")
        }.onFailure {
            status = "Cannot open the search pack (${it.message}). Copy pa-search.sqlite and " +
                "pa-addresses-slim.sqlite to ${dir.path}"
            Log.e(TAG, "open failed", it)
        }
    }
    LaunchedEffect(service) {
        val s = service ?: return@LaunchedEffect
        snapshotFlow { query.value to place.value }.collectLatest { (q, p) ->
            response = if (q.isBlank()) null else withContext(Worker.dispatcher) { s.search(q, p.lat, p.lon) }
            response?.let { Log.i(TAG, "'${it.query}' near ${p.key}: ${it.results.size} results in ${it.ms} ms") }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Search near", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PLACES.forEach { p ->
                FilterChip(selected = place.value == p, onClick = { place.value = p }, label = { Text(p.label) })
            }
        }
        OutlinedTextField(
            value = query.value, onValueChange = { query.value = it }, singleLine = true,
            placeholder = { Text("Address, place, brand or category") },
            modifier = Modifier.fillMaxWidth(),
        )
        val resp = response
        Text(
            if (resp == null) " " else summary(resp),
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        LazyColumn(Modifier.weight(1f)) {
            items(resp?.results ?: emptyList(), key = { it.id }) { ResultRow(it) }
            if (resp != null && resp.results.isEmpty()) item { Text("Nothing found", Modifier.padding(vertical = 12.dp)) }
        }
        Text(status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 6.dp))
    }
}

private fun summary(r: SearchResponse): String {
    val parse = r.parse.joinToString("; ") { l ->
        val m = l.matches?.joinToString(", ") ?: l.match
        "${l.text} = ${l.role}" + (m?.let { " ($it)" } ?: "")
    }
    return "${r.results.size} result${if (r.results.size == 1) "" else "s"} · ${"%.1f".format(r.ms)} ms" + (if (parse.isNotEmpty()) " · $parse" else "") +
        (if (r.outOfCoverage == true) " · outside the pack" else "")
}

private fun miles(km: Double): String {
    val mi = km * 0.621371
    return if (mi < 10) String.format(Locale.US, "%.1f mi", mi) else String.format(Locale.US, "%.0f mi", mi)
}

private fun accuracyText(a: AddressInfo): String = buildString {
    append(when (a.accuracy) {
        AddressInfo.Accuracy.POINT -> "Exact address point"
        AddressInfo.Accuracy.INTERPOLATED -> "Interpolated along the street"
        AddressInfo.Accuracy.STREET -> "Number not found: street only"
    })
    if (a.localityMatch == false) append(" · not in the town typed (a guess)")
    if (a.stateMismatch) append(" · another state was typed")
}

@Composable
fun ResultRow(r: SearchResult) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(r.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(r.categoryLabel, r.subtitle.ifEmpty { null }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            r.address?.let {
                Text(accuracyText(it), style = MaterialTheme.typography.labelSmall,
                    color = if (it.accuracy == AddressInfo.Accuracy.POINT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
            }
        }
        Spacer(Modifier.width(12.dp))
        r.distanceKm?.let { Text(miles(it), style = MaterialTheme.typography.bodyMedium) }
    }
    HorizontalDivider()
}

@Composable
fun BenchScreen(dir: File, dump: Boolean) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(if (dump) "Answering the conformance queries..." else "Benchmark running (several minutes)...") }
    LaunchedEffect(Unit) {
        text = withContext(Worker.dispatcher) {
            runCatching { if (dump) runDump(ctx, dir) else runBench(ctx, dir) }
                .getOrElse { Log.e(TAG, "bench failed", it); "Benchmark failed: $it" }
        }
    }
    Text(text, Modifier.safeDrawingPadding().padding(16.dp))
}

private fun runDump(ctx: Context, dir: File): String {
    val queries = ctx.assets.open("search_queries.json").use { it.readBytes().toString(Charsets.UTF_8) }
    val t0 = SystemClock.elapsedRealtimeNanos()
    val out = io.github.spysyweeb.beamline.search.SearchEngine(searchFile(dir).path, addressFile(dir).path).use { ConformanceDump.run(it, queries) }
    val f = File(dir, "kotlin-search-device.json")
    f.writeText(Json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), out))
    val s = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
    Log.i(TAG, "DUMP_DONE ${f.path} ${out.size} queries in $s s")
    return "Answered ${out.size} queries in ${"%.0f".format(s)} s: ${f.path}"
}

private fun runBench(ctx: Context, dir: File): String {
    val queries = ctx.assets.open("search_queries.json").use { it.readBytes().toString(Charsets.UTF_8) }
    val t0 = SystemClock.elapsedRealtimeNanos()
    val res = SearchBench(searchFile(dir).path, addressFile(dir).path, queries) { Log.i(TAG, it) }.run()
    val wall = (SystemClock.elapsedRealtimeNanos() - t0) / 1e9
    // memory with the demo's state: one service (engine + address index) open after typical queries
    val mem = SearchService.open(searchFile(dir).path, addressFile(dir).path).use { s ->
        for (q in listOf("wal", "rest stop", "sheetz", "4319 peach st", "erie city hall")) s.search(q, PLACES[0].lat, PLACES[0].lon)
        System.gc()
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        buildJsonObject {
            put("total_pss_kb", mi.totalPss)
            put("native_heap_pss_kb", mi.getMemoryStat("summary.native-heap"))
            put("java_heap_pss_kb", mi.getMemoryStat("summary.java-heap"))
            put("code_pss_kb", mi.getMemoryStat("summary.code"))
            put("native_heap_allocated_kb", Debug.getNativeHeapAllocatedSize() / 1024)
            put("how", "Debug.getMemoryInfo of the app process (bench mode), service open after 5 queries, after System.gc()")
        }
    }
    val am = ctx.getSystemService(ActivityManager::class.java)
    val out = buildJsonObject {
        res.forEach { (k, v) -> put(k, v) }
        put("math_checksum", MathParity.checksum())
        put("platform", buildJsonObject {
            put("kind", "Android")
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("sdk", Build.VERSION.SDK_INT)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
            put("cpus", Runtime.getRuntime().availableProcessors())
            put("memory_class_mb", am.memoryClass)
        })
        put("memory", mem)
        put("bench_wall_s", Math.round(wall * 10) / 10.0)
    }
    val f = File(dir, "bench-device.json")
    f.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), out))
    Log.i(TAG, "BENCH_DONE ${f.path} in $wall s")
    return "Benchmark done in ${"%.0f".format(wall)} s: ${f.path}"
}
