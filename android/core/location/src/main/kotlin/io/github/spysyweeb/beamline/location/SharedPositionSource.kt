// FILE: core/location/src/main/kotlin/io/github/spysyweeb/beamline/location/SharedPositionSource.kt
package io.github.spysyweeb.beamline.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn

/**
 * One position listener for the whole app: every collector (the puck, the session) shares one
 * upstream collection, started by the first collector and stopped [stopAfterMs] after the last
 * one leaves. [latest] is the last fix any collector saw (raw, unfiltered).
 *
 * When the upstream ends or fails while collectors stay (no location permission yet, or the
 * permission was withdrawn), it is collected again after [restartAfterMs], so a permission
 * granted later reaches the collectors that are already waiting.
 */
class SharedPositionSource(
    upstream: PositionSource,
    scope: CoroutineScope,
    stopAfterMs: Long = 5_000,
    restartAfterMs: Long = 2_000,
) : PositionSource {
    private val _latest = MutableStateFlow<Fix?>(null)
    val latest: StateFlow<Fix?> = _latest.asStateFlow()
    private val shared = flow {
        while (true) {
            emitAll(upstream.fixes().catch { })
            delay(restartAfterMs)
        }
    }
        .onEach { _latest.value = it }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopAfterMs), replay = 0)

    override fun fixes(): Flow<Fix> = shared
}
