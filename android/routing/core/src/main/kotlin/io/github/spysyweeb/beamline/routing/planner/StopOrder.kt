package io.github.spysyweeb.beamline.routing.planner

import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.preset.Preset
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.routing.valhalla.Matrix
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaRequests
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses

/** A stop order and its driving time by the engine's matrix. Indices refer to the input stops. */
data class StopOrder(val order: List<Int>, val timeS: Double, val permutationsTried: Long)

/**
 * Puts the stops of a trip in the quickest order, by brute force over one all-to-all matrix
 * call. The first stop stays first; the last stays last unless keepLast is false. Brute force is
 * exact and cheap up to about 8 stops (6! = 720 orders with both ends fixed, 7! = 5,040 with a
 * free end). It is capped at [MAX_ORDERS] = 8! = 40,320 orders: 10 stops with the last one kept,
 * 9 stops with a free end ([maxStops]); 10 stops with a free end would be 9! = 362,880 orders.
 *
 * The order always minimises the matrix *time*, for every preset (the matrix is asked with the
 * preset's costing options, as the Python reference does with Fastest; a pool preset with its
 * first candidate). When the options' exclusions leave a stop unconnected (a stop on an excluded
 * road, docs/architecture.md 2.11) the matrix is asked again with the fallback options (rule C6).
 */
class StopOrderer(private val engine: RoutingEngine, private val presets: PresetSet = PresetSet.bundled()) {

    /** Throws [EngineException] when the matrix call fails or its answer cannot be read. */
    fun matrix(points: List<LatLon>, presetId: String = "fastest"): Matrix = matrix(points, presets.resolve(presetId))

    /** The matrix with [preset]'s options, or with its fallback options when [useFallback]. */
    fun matrix(points: List<LatLon>, preset: Preset, useFallback: Boolean = false): Matrix {
        val opts = when {
            preset.isPool -> (if (useFallback) preset.fallbackCandidates.firstOrNull() else null) ?: preset.candidates.first()
            useFallback -> preset.fallbackCostingOptions ?: preset.costingOptions
            else -> preset.costingOptions
        }
        val body = engine.matrix(ValhallaRequests.matrix(points, points, opts).toString())
        return try {
            ValhallaResponses.parseMatrix(body)
        } catch (e: RuntimeException) {
            throw EngineException(null, "unreadable matrix answer: $e", e)
        }
    }

    /**
     * The quickest order, or null when no order connects every stop (the matrix has no time for a
     * leg every order needs: tell the user the stops cannot all be reached from each other).
     * Throws [EngineException] when the matrix call fails, [IllegalArgumentException] for more
     * stops than [maxStops].
     */
    fun bestOrder(points: List<LatLon>, keepLast: Boolean = true, presetId: String = "fastest"): StopOrder? =
        bestOrder(points, keepLast, presets.resolve(presetId))

    /** As above with a composed preset; no connected order with the options: the fallback options' matrix (C6). */
    fun bestOrder(points: List<LatLon>, keepLast: Boolean, preset: Preset): StopOrder? {
        require(points.size <= maxStops(keepLast)) { "at most ${maxStops(keepLast)} stops can be reordered" }
        if (points.size <= 2) return StopOrder(points.indices.toList(), 0.0, 1)
        val first = bestOrder(matrix(points, preset).timesS, keepLast)
        if (first != null || (preset.fallbackCostingOptions == null && preset.fallbackCandidates.isEmpty())) return first
        return bestOrder(matrix(points, preset, useFallback = true).timesS, keepLast)
    }

    companion object {
        /** 8! orders: about 25 ms on a PC JVM (BreakCoreTest of the review), so well under a second on a phone. */
        const val MAX_ORDERS = 40_320L

        /** The most stops [bestOrder] takes: 10 with the last stop kept, 9 with a free end. */
        fun maxStops(keepLast: Boolean) = if (keepLast) 10 else 9

        /**
         * Pure brute force over a time matrix (null = no connection). Null when no order has a
         * time for every leg.
         */
        fun bestOrder(times: List<List<Double?>>, keepLast: Boolean): StopOrder? {
            val n = times.size
            require(n <= maxStops(keepLast)) { "at most ${maxStops(keepLast)} stops can be reordered" }
            if (n <= 1) return StopOrder((0 until n).toList(), 0.0, 1)
            if (n == 2) return times[0][1]?.let { StopOrder(listOf(0, 1), it, 1) }
            val middle = if (keepLast) (1 until n - 1).toMutableList() else (1 until n).toMutableList()
            var best: List<Int>? = null
            var bestT = Double.POSITIVE_INFINITY
            var tried = 0L
            fun cost(order: List<Int>): Double {
                var t = 0.0
                for (k in 0 until order.size - 1) t += times[order[k]][order[k + 1]] ?: return Double.POSITIVE_INFINITY
                return t
            }
            // lexicographic permutations (same order as Python's itertools.permutations), first minimum wins
            val perm = middle.toIntArray()
            while (true) {
                tried++
                val order = buildList { add(0); perm.forEach { add(it) }; if (keepLast) add(n - 1) }
                val c = cost(order)
                if (c < bestT) { bestT = c; best = order }
                // next permutation
                var i = perm.size - 2
                while (i >= 0 && perm[i] >= perm[i + 1]) i--
                if (i < 0) break
                var j = perm.size - 1
                while (perm[j] <= perm[i]) j--
                perm[i] = perm[j].also { perm[j] = perm[i] }
                perm.reverse(i + 1, perm.size)
            }
            return best?.let { StopOrder(it, bestT, tried) }
        }
    }
}
