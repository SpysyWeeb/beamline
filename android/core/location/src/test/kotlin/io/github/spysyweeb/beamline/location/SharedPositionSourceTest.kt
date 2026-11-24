package io.github.spysyweeb.beamline.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedPositionSourceTest {

    private fun fix(i: Int) = Fix(42.0 + i * 1e-4, -80.0, 5f, elapsedMs = i * 1000L, provider = "gps")

    /** Emits one fix a second while collected; counts how often it was started. */
    private class Ticking(private val failFirst: Int = 0) : PositionSource {
        var starts = 0
        override fun fixes(): Flow<Fix> = flow {
            starts++
            if (starts <= failFirst) return@flow // like AndroidPositionSource without permission: closes at once
            var i = 0
            while (true) {
                emit(Fix(42.0, -80.0, 5f, elapsedMs = (i++) * 1000L, provider = "gps"))
                delay(1000)
            }
        }
    }

    @Test
    fun `two collectors share one upstream, which stops after the last leaves`() = runTest {
        val up = Ticking()
        val shared = SharedPositionSource(up, backgroundScope, stopAfterMs = 5_000)
        val a = mutableListOf<Fix>()
        val b = mutableListOf<Fix>()
        val ja = launch { shared.fixes().collect { a += it } }
        val jb = launch { shared.fixes().collect { b += it } }
        advanceTimeBy(3_500)
        assertEquals(1, up.starts)
        assertEquals(4, a.size)
        assertEquals(a, b)
        assertEquals(a.last(), shared.latest.value)
        ja.cancel()
        jb.cancel()
        advanceTimeBy(6_000)
        launch { shared.fixes().collect { } }.also { advanceTimeBy(100); it.cancel() }
        assertEquals(2, up.starts) // restarted after the stop delay
    }

    @Test
    fun `an upstream that ends at once is collected again while a collector waits`() = runTest {
        // review finding (fit): the permission is granted after the session started collecting
        val up = Ticking(failFirst = 2)
        val shared = SharedPositionSource(up, backgroundScope, restartAfterMs = 2_000)
        val got = mutableListOf<Fix>()
        val job = launch { shared.fixes().collect { got += it } }
        runCurrent()
        assertEquals(1, up.starts)
        assertEquals(0, got.size)
        advanceTimeBy(2_001)
        assertEquals(2, up.starts)
        assertEquals(0, got.size)
        advanceTimeBy(2_000)
        assertEquals(3, up.starts)
        assertEquals(1, got.size)
        advanceTimeBy(2_000)
        assertEquals(3, got.size)
        job.cancel()
    }

    @Test
    fun `an upstream that fails is retried instead of failing the app scope`() = runTest {
        var starts = 0
        val failing = object : PositionSource {
            override fun fixes(): Flow<Fix> = flow {
                starts++
                if (starts == 1) throw SecurityException("location permission withdrawn")
                emit(fix(starts))
            }
        }
        val shared = SharedPositionSource(failing, backgroundScope, restartAfterMs = 1_000)
        val got = mutableListOf<Fix>()
        val job = launch { shared.fixes().collect { got += it } }
        advanceTimeBy(1_500)
        assertEquals(2, starts)
        assertEquals(listOf(fix(2)), got)
        job.cancel()
    }
}
