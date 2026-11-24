package io.github.spysyweeb.beamline.location

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One position fix. [elapsedMs] is on the monotonic clock of the device (Android's
 * `elapsedRealtime`), so the age of a fix never depends on the wall clock.
 */
data class Fix(
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy radius (68 %), metres; null if unknown. */
    val accuracyM: Float?,
    val elapsedMs: Long,
    val speedMps: Float? = null,
    /** Direction of travel, degrees from true north; null when not moving or unknown. */
    val bearingDeg: Float? = null,
    val provider: String = "",
)

/** Compass direction the phone points to, degrees clockwise from true north (0..360). */
data class Heading(val degrees: Float, val elapsedMs: Long)

/** Positions, e.g. Android's LocationManager, or a simulated drive in tests. Cold: starts on collect. */
interface PositionSource {
    fun fixes(): Flow<Fix>
}

/** Compass headings, e.g. Android's rotation-vector sensor. Cold: starts on collect. */
interface HeadingSource {
    fun headings(): Flow<Heading>
}

/** The monotonic clock the fixes use. */
fun interface Clock {
    fun elapsedMs(): Long
}

/**
 * When the puck turns grey and how the compass is smoothed. Read from the app's
 * `assets/rules/position.json` (rules live in data files); field names are the JSON keys.
 */
@Serializable
data class PositionRules(
    /** A fix older than this is old: grey puck. */
    @SerialName("stale_after_s") val staleAfterS: Double = 10.0,
    /** A fix whose accuracy radius is larger than this is poor: grey puck. */
    @SerialName("poor_accuracy_m") val poorAccuracyM: Double = 100.0,
    /** No compass reading for this long: the cone is hidden. */
    @SerialName("heading_max_age_s") val headingMaxAgeS: Double = 3.0,
    /** Weight of a new compass reading, 0..1 (low-pass on the circle). */
    @SerialName("heading_smoothing") val headingSmoothing: Double = 0.25,
    /** How often the puck is re-checked for age when nothing new arrives. */
    @SerialName("tick_s") val tickS: Double = 1.0,
    /**
     * Providers whose fixes are always taken (satellite fixes; Android calls its provider "gps").
     * A fix from any other provider replaces the last one only when it is as accurate or the last
     * one is old ([FixFilter]).
     */
    @SerialName("satellite_providers") val satelliteProviders: List<String> = listOf("gps"),
    /** The browsing position estimate and its uncertainty bubble (rule G7, [PositionEstimator]). */
    @SerialName("bubble") val bubble: BubbleRules = BubbleRules(),
) {
    companion object {
        fun parse(text: String): PositionRules = Json { ignoreUnknownKeys = true }.decodeFromString(serializer(), text)
    }
}

enum class PuckLook {
    /** Route blue: a recent, accurate fix. */
    LIVE,

    /** Grey: the fix is old or poor. */
    GREY,
}

/** What the browsing puck shows. Browsing only: the arrow of a guided trip never uses it (rule G7). */
data class PuckState(
    val lat: Double,
    val lon: Double,
    val look: PuckLook,
    /** Cone direction (degrees from true north), null to hide the cone. */
    val coneDeg: Float?,
    val accuracyM: Float?,
    /** Why the puck is grey ("old", "poor"), for logs and tests. */
    val why: String? = null,
    /**
     * The uncertainty bubble's radius in metres (rule G7); null when the position is good enough
     * that none is drawn. When it is set, [lat]/[lon] are the estimate, not the last fix.
     */
    val bubbleM: Float? = null,
)
