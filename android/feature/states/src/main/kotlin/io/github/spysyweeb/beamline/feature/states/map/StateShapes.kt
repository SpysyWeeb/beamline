// FILE: feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/map/StateShapes.kt
package io.github.spysyweeb.beamline.feature.states.map

/** Which drawing frame a state is in: Alaska and Hawaii are drawn as insets, as on US wall maps. */
enum class Inset { LOWER48, ALASKA, HAWAII }

/** One state in map units (the asset's integers divided by its `scale`; y grows downwards). */
class StateShape(
    /** USPS code, "PA". */
    val code: String,
    /** FIPS state code, 42. */
    val fips: Int,
    val name: String,
    val inset: Inset,
    val labelX: Float,
    val labelY: Float,
    /** minX, minY, maxX, maxY. */
    val box: FloatArray,
    /** Each ring as x0, y0, x1, y1, ...; closed implicitly; outer rings and holes alike (even-odd). */
    val rings: List<FloatArray>,
    /** USPS codes of the states it touches, from the unsimplified outlines. */
    val neighbours: List<String>,
)

/** One Albers equal-area conic frame of the asset (degrees; [k] map units per projected unit). */
class AlbersFrame(val lat1: Double, val lat2: Double, val lat0: Double, val lon0: Double, val k: Double, val dx: Double, val dy: Double)

/**
 * assets/states/us_states.json (format beamline-state-shapes/1), made by
 * packbuilder/catalog/build_state_shapes.py from Natural Earth 5.1.2 admin-1 (public domain).
 */
class StateShapes(
    val width: Float,
    val height: Float,
    val states: List<StateShape>,
    /** The inset frames' boxes (minX, minY, maxX, maxY), drawn as thin outlines. */
    val insetBoxes: Map<Inset, FloatArray>,
    val frames: Map<Inset, AlbersFrame>,
) {
    fun byCode(code: String): StateShape? = states.firstOrNull { it.code == code }

    /** A position in map units (x, y), with the frame chosen as the build script chooses it; null outside every frame. */
    fun project(lon: Double, lat: Double): FloatArray? = ShapesCode.project(this, lon, lat)

    companion object {
        const val FORMAT = "beamline-state-shapes/1"
        fun parse(json: String): StateShapes = ShapesCode.parse(json)
    }
}

data class HitRules(
    /** A tap outside every state still picks one whose outline is this close. */
    val nearDp: Float = 16f,
    /** A state whose box's long side is shorter than this on screen counts as small. */
    val smallDp: Float = 48f,
    val chooserMax: Int = 4,
)

sealed interface TapResult {
    /** Outside every state: the UI shows the "Tap a state" hint. */
    data object None : TapResult
    data class One(val code: String) : TapResult
    /** "Which one?": nearest first. */
    data class Choose(val codes: List<String>) : TapResult
}

/**
 * Which state a tap means (section 6.8). [x], [y] in map units; [unitsPerDp] = map units per dp at
 * the current zoom. Pure; no MapLibre, no Android.
 */
object StateHitTest {
    fun resolve(shapes: StateShapes, x: Float, y: Float, unitsPerDp: Float, rules: HitRules = HitRules()): TapResult = ShapesCode.resolve(shapes, x, y, unitsPerDp, rules)
}
