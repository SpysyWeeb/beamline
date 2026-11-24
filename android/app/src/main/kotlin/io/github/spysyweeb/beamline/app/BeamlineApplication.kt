package io.github.spysyweeb.beamline.app

import android.app.Application
import android.os.StrictMode
import android.util.Log
import io.github.spysyweeb.beamline.app.guidance.GuidanceService
import io.github.spysyweeb.beamline.feature.states.GetPacks
import io.github.spysyweeb.beamline.feature.states.GetPacksHost
import io.github.spysyweeb.beamline.search.AddrKeyFingerprint
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestImpl
import java.io.File

/**
 * The process: holds the one [AppGraph] (built on first use, by the activity or a service) and is
 * the [GetPacksHost] DownloadService looks for (docs/architecture.md 5.4 item 2).
 *
 * The folder of the user's place files is a constructor argument of the graph (PV7): the activity
 * sets it from the test extra `--es private_data test` before its first access to [graph]; once the
 * graph exists the choice is refused ([chooseColdStart] returns false and the activity logs
 * "private data: extra ignored, app already running").
 */
class BeamlineApplication : Application(), GetPacksHost {

    @Volatile private var built: AppGraph? = null
    private var testData = false
    private var locationLog = false

    /** Shared by every screen and service; created on first use. */
    val graph: AppGraph
        get() = built ?: synchronized(this) {
            built ?: run {
                val dir = if (testData) File(noBackupFilesDir, "test") else noBackupFilesDir
                AppGraph(this, dir, locationLog).also { built = it }
            }
        }

    override val getPacks: GetPacks get() = graph.getPacks

    /**
     * The cold-start choices of the test extras (PV7 items 1 and 6). False when the graph exists
     * already (the process was running): nothing changes then.
     */
    fun chooseColdStart(testData: Boolean, locationLog: Boolean): Boolean = synchronized(this) {
        if (built != null) return false
        this.testData = testData
        this.locationLog = locationLog
        true
    }

    /** The graph exists (the process was already running before this activity start). */
    val running: Boolean get() = built != null

    override fun onCreate() {
        super.onCreate()
        Log.i("BeamlineMap", "MapLibre renderer: ${BuildConfig.MAP_RENDERER}") // log-ok: a build constant (opengl)
        MapLibre.getInstance(this)
        // Rule N5: MapLibre gets an HTTP client that refuses every request. Right after getInstance,
        // not before it: MapLibre 13.7.1's HttpRequestImpl reads the MapLibre instance in its static
        // initialiser (MapLibreConfigurationException otherwise, seen on the emulator). No map view
        // and no request exists yet at this point.
        HttpRequestImpl.setOkHttpClient(OfflineMapHttp.client)
        // Offline-first: tell MapLibre there is no network, so it never tries one.
        // Packs are read through pmtiles://file:// and the style through asset:// URLs.
        MapLibre.setConnected(false)
        if (BuildConfig.DEBUG) {
            // docs/architecture-downloads.md 4.2 item 5: an early warning while developing (Java sockets only).
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectUntaggedSockets().penaltyLog().build())
        }
        GuidanceService.ensureChannel(this)
        // The street-key fingerprint (about 250 ms) is computed once per process; start it now, off
        // the main thread, so the graph (which needs it for the installed library) rarely waits.
        Thread({ AddrKeyFingerprint.app() }, "beamline-fingerprint").apply { isDaemon = true }.start()
    }
}
