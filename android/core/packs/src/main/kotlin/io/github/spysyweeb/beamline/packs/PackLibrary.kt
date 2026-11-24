package io.github.spysyweeb.beamline.packs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** What is installed, as the screens show it. */
data class LibraryState(
    val scanned: Boolean = false,
    val packs: List<InstalledPack> = emptyList(),
    val problems: List<PackProblem> = emptyList(),
)

/** The one install that can run at a time. */
sealed interface InstallState {
    data object Idle : InstallState
    data class Running(val source: String, val copiedBytes: Long, val totalBytes: Long, val file: String?) : InstallState
    data class Done(val packName: String, val osmSnapshot: String) : InstallState
    data class Failed(val source: String, val message: String) : InstallState
    data class Cancelled(val source: String) : InstallState
}

/**
 * The installed packs of this device, for every screen (map, pack manager, later Android Auto).
 * UI-free: screens observe [state] and [install] and call the functions; nothing here knows
 * about Android. All file work runs on [io].
 */
class PackLibrary(
    private val root: File,
    readers: Readers,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    rules: InstallRules = InstallRules(),
    now: () -> Instant = Instant::now,
) {
    private val scanner = PackScanner(root, readers)
    private val installer = PackInstaller(root, readers, now, rules)
    private val lock = Mutex()

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install.asStateFlow()

    private var installJob: Job? = null

    val folder: File get() = root

    /** Scan again (cheap: no hashing). Leftovers of an interrupted install are cleaned first. */
    suspend fun refresh() {
        lock.withLock {
            val r = withContext(io) {
                root.mkdirs()
                if (installJob?.isActive != true) installer.cleanUp()
                scanner.scan()
            }
            val next = LibraryState(true, r.packs, r.problems)
            if (next != _state.value) _state.value = next
        }
    }

    /** Starts installing; progress and the result appear in [install]. One install at a time. */
    fun startInstall(source: PackSource): Boolean {
        if (installJob?.isActive == true) return false
        _install.value = InstallState.Running(source.label, 0, 0, null)
        installJob = scope.launch {
            val job = coroutineContext[Job]!!
            // Set on the IO thread the moment the pack is in place. A cancel that lands after that
            // (the installer no longer looks at it) still makes withContext throw on return; the
            // pack is then installed all the same and must be reported so.
            var installed: InstalledPack? = null
            val result = try {
                val pack = withContext(io) {
                    var lastReport = 0L
                    installer.install(
                        source,
                        progress = { p ->
                            // At most about 20 updates a second reach the screen.
                            val t = System.nanoTime()
                            if (t - lastReport > 50_000_000 || p.copiedBytes == p.totalBytes) {
                                lastReport = t
                                _install.value = InstallState.Running(source.label, p.copiedBytes, p.totalBytes, p.file)
                            }
                        },
                        isCancelled = { !job.isActive },
                    ).also { installed = it }
                }
                InstallState.Done(pack.manifest.name, pack.manifest.osmSnapshot)
            } catch (e: InstallCancelledException) {
                InstallState.Cancelled(source.label)
            } catch (e: InstallException) {
                InstallState.Failed(source.label, e.message ?: "install failed")
            } catch (e: CancellationException) {
                installed?.let { InstallState.Done(it.manifest.name, it.manifest.osmSnapshot) } ?: InstallState.Cancelled(source.label)
            } catch (e: Exception) {
                InstallState.Failed(source.label, e.message ?: e::class.simpleName ?: "install failed")
            }
            _install.value = result
            withContext(NonCancellable) { refresh() }
        }
        return true
    }

    /** Cancels a running install; its staging folder is deleted. */
    fun cancelInstall() {
        installJob?.cancel()
    }

    /** Clears a finished, failed or cancelled install from [install]. */
    fun dismissInstallResult() {
        if (_install.value !is InstallState.Running) _install.value = InstallState.Idle
    }

    suspend fun remove(folder: String) {
        withContext(io) { installer.remove(folder) }
        refresh()
    }
}
