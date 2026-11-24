package io.github.spysyweeb.beamline.location

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A point of a recorded or planned track. */
data class TrackPoint(val lat: Double, val lon: Double, val timeS: Double? = null)

/**
 * Replays a track as fixes, one every [intervalMs] (or at the track's own timing when every point
 * has a time), for tests and demos. [clock] stamps the fixes, so a test's virtual clock works.
 */
class SimulatedPositionSource(
    private val track: List<TrackPoint>,
    private val clock: Clock,
    private val intervalMs: Long = 1000,
    private val accuracyM: Float = 5f,
) : PositionSource {
    override fun fixes(): Flow<Fix> = flow {
        val timed = track.all { it.timeS != null }
        track.forEachIndexed { i, p ->
            if (i > 0) {
                val gap = if (timed) ((p.timeS!! - track[i - 1].timeS!!) * 1000).toLong() else intervalMs
                delay(gap.coerceAtLeast(0))
            }
            val next = track.getOrNull(i + 1)
            emit(
                Fix(
                    lat = p.lat,
                    lon = p.lon,
                    accuracyM = accuracyM,
                    elapsedMs = clock.elapsedMs(),
                    bearingDeg = next?.let { bearing(p, it) },
                    provider = "simulated",
                ),
            )
        }
    }

    companion object {
        /** Initial great-circle bearing from [a] to [b], degrees from true north. */
        fun bearing(a: TrackPoint, b: TrackPoint): Float {
            val f1 = Math.toRadians(a.lat)
            val f2 = Math.toRadians(b.lat)
            val dl = Math.toRadians(b.lon - a.lon)
            val y = sin(dl) * cos(f2)
            val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
            return Compass.normalize(Math.toDegrees(atan2(y, x)).toFloat())
        }
    }
}

/** A fixed list of headings, for tests. */
class SimulatedHeadingSource(
    private val degrees: List<Float>,
    private val clock: Clock,
    private val intervalMs: Long = 100,
) : HeadingSource {
    override fun headings(): Flow<Heading> = flow {
        degrees.forEachIndexed { i, d ->
            if (i > 0) delay(intervalMs)
            emit(Heading(d, clock.elapsedMs()))
        }
    }
}

/**
 * Reads the track points of a GPX 1.1 file (`trkpt`, or `rtept`/`wpt` when there is no track).
 * Small on purpose: attributes `lat`/`lon` and an optional `<time>` in UTC ISO form.
 */
object Gpx {
    private val point = Regex("""<(trkpt|rtept|wpt)\b([^>]*?)(/>|>(.*?)</\1>)""", RegexOption.DOT_MATCHES_ALL)
    private val lat = Regex("""\blat\s*=\s*["']([-+0-9.eE]+)["']""")
    private val lon = Regex("""\blon\s*=\s*["']([-+0-9.eE]+)["']""")
    private val time = Regex("""<time>\s*([^<]+?)\s*</time>""")

    fun parse(text: String): List<TrackPoint> {
        val all = point.findAll(text).map { m ->
            val attrs = m.groupValues[2]
            val body = m.groupValues.getOrNull(4).orEmpty()
            m.groupValues[1] to TrackPoint(
                lat = lat.find(attrs)?.groupValues?.get(1)?.toDouble() ?: error("point without lat"),
                lon = lon.find(attrs)?.groupValues?.get(1)?.toDouble() ?: error("point without lon"),
                timeS = time.find(body)?.groupValues?.get(1)?.let { java.time.Instant.parse(it).toEpochMilli() / 1000.0 },
            )
        }.toList()
        val kind = listOf("trkpt", "rtept", "wpt").firstOrNull { k -> all.any { it.first == k } } ?: return emptyList()
        return all.filter { it.first == kind }.map { it.second }
    }
}
