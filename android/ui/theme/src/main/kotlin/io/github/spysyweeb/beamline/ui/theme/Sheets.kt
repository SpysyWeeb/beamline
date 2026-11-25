package io.github.spysyweeb.beamline.ui.theme

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp

/**
 * The heights a bottom sheet rests at (rule D10, docs/next-round.md item 15): [LOW] leaves the map
 * free (only the handle and the sheet's one-line summary), [MIDDLE] (the results list; the place
 * sheet, batch G) and [FULL]. Each sheet says which it has ([SheetSteps]); the content of each height is the sheet's own.
 */
enum class SheetHeight { LOW, MIDDLE, FULL }

/**
 * One sheet's heights, lowest first, and the moves between them (no Compose: tested on the JVM).
 * A tap on the handle raises the sheet one height and from the highest goes back to the lowest; the
 * semantic actions raise or lower one height; a drag moves one height for every [SHEET_STEP] it
 * travels in one direction.
 */
class SheetSteps(val heights: List<SheetHeight>) {
    init {
        require(heights.isNotEmpty() && heights == heights.distinct().sorted()) { "heights lowest first, each once" }
    }

    fun raised(h: SheetHeight): SheetHeight? = heights.firstOrNull { it > h }
    fun lowered(h: SheetHeight): SheetHeight? = heights.lastOrNull { it < h }
    fun tapped(h: SheetHeight): SheetHeight = raised(h) ?: heights.first()

    /** [h] when the sheet has it, else the nearest height it has (a saved height of another sheet). */
    fun nearest(h: SheetHeight): SheetHeight = heights.minBy { kotlin.math.abs(it.ordinal - h.ordinal) }

    companion object {
        /** The results list: handle and count, about three rows, about 60 % of the screen; the place sheet: its title line, the sheet, the details height (batch G). */
        val THREE = SheetSteps(listOf(SheetHeight.LOW, SheetHeight.MIDDLE, SheetHeight.FULL))
        /** The route choice: the one-line summary, or everything. */
        val TWO = SheetSteps(listOf(SheetHeight.LOW, SheetHeight.FULL))
    }
}

/** The drag travel that moves a sheet one height. */
val SHEET_STEP: Dp = 40.dp

/** The texts of a sheet's handle (US spelling, rule D10). */
object SheetTexts {
    const val RAISE = "Raise the sheet"
    const val LOWER = "Lower the sheet"
    const val LOWEST = "Lower to the smallest height"
    fun state(h: SheetHeight): String = when (h) {
        SheetHeight.LOW -> "Lowered"
        SheetHeight.MIDDLE -> "Half open"
        SheetHeight.FULL -> "Full height"
    }
}

/**
 * Where a sheet rests and the drag in progress. A drag of [drag] moves the sheet one height for every
 * step it travels in one direction (more than one in a long drag); a drag the sheet cannot follow
 * (up at the highest, down at the lowest) is not used, so a list inside the sheet scrolls instead.
 */
@Stable
class SheetState(val steps: SheetSteps, initial: SheetHeight) {
    var height by mutableStateOf(steps.nearest(initial))
        private set

    private var travel = 0f
    private var moved = false

    val canRaise: Boolean get() = steps.raised(height) != null
    val canLower: Boolean get() = steps.lowered(height) != null

    fun raise(): Boolean = steps.raised(height)?.let { height = it; true } ?: false
    fun lower(): Boolean = steps.lowered(height)?.let { height = it; true } ?: false
    fun tap() { height = steps.tapped(height) }
    fun set(h: SheetHeight) { height = steps.nearest(h) }

    /** A drag of [dy] px (down is positive); returns the part used (all of it, or none). */
    fun drag(dy: Float, stepPx: Float): Float {
        if (dy == 0f) return 0f
        if ((dy < 0f && !canRaise) || (dy > 0f && !canLower)) {
            travel = 0f
            return 0f
        }
        if (travel != 0f && (travel < 0f) != (dy < 0f)) travel = 0f
        travel += dy
        if (travel <= -stepPx) {
            raise(); travel = 0f; moved = true
        } else if (travel >= stepPx) {
            lower(); travel = 0f; moved = true
        }
        return dy
    }

    /** The drag ended; true when it moved the sheet (its fling is then not passed on to a list). */
    fun endDrag(): Boolean {
        val m = moved
        moved = false
        travel = 0f
        return m
    }

    companion object {
        fun saver(steps: SheetSteps): Saver<SheetState, String> =
            Saver(save = { it.height.name }, restore = { SheetState(steps, SheetHeight.valueOf(it)) })
    }
}

/** A sheet's state, kept across a turn of the phone; [keys] changing starts again at [initial]. */
@Composable
fun rememberSheetState(steps: SheetSteps, initial: SheetHeight, vararg keys: Any?): SheetState =
    rememberSaveable(*keys, saver = SheetState.saver(steps)) { SheetState(steps, initial) }

/**
 * The nested scrolling of a sheet with a list: a drag up raises the sheet before the list scrolls; a
 * drag down scrolls the list to its top first and then lowers the sheet. A fling that follows a drag
 * which moved the sheet is used up, so the list does not fly on.
 */
class SheetNestedScroll(private val state: SheetState, private val stepPx: Float) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y < 0f) Offset(0f, state.drag(available.y, stepPx)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && available.y > 0f) Offset(0f, state.drag(available.y, stepPx)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity = if (state.endDrag()) available else Velocity.Zero
}

/** Test tag of a sheet's handle (with its header). */
const val SHEET_HANDLE_TAG = "sheet-handle"

/**
 * The one frame of every bottom sheet (rule D10): the handle at the top, with the sheet's [header]
 * under it, then the [content]. The whole frame drags (a list inside it through [SheetNestedScroll]);
 * a tap on the handle or the header raises one height (from the highest, back to the lowest), and a
 * screen reader finds the handle by [label] with its height and the actions "Raise the sheet" and
 * "Lower the sheet". The caller draws the surface around it and shows, for each height, its part.
 */
@Composable
fun SheetFrame(
    state: SheetState,
    label: String,
    handleColor: Color,
    modifier: Modifier = Modifier,
    header: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val stepPx = with(LocalDensity.current) { SHEET_STEP.toPx() }
    val connection = remember(state, stepPx) { SheetNestedScroll(state, stepPx) }
    val drag = rememberDraggableState { dy -> state.drag(dy, stepPx) }
    Column(
        modifier
            // item 47 (the owner, 2026-11-25: "some of the windows will need some kind of transition
            // from one view to another"): a change of height slides, without a bounce, instead of jumping
            .animateContentSize(spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            .nestedScroll(connection)
            .draggable(drag, Orientation.Vertical, onDragStopped = { state.endDrag() }),
    ) {
        val h = state.height
        val actions = buildList {
            if (state.canRaise) add(CustomAccessibilityAction(SheetTexts.RAISE) { state.raise() })
            if (state.canLower) add(CustomAccessibilityAction(SheetTexts.LOWER) { state.lower() })
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (state.canRaise) SheetTexts.RAISE else SheetTexts.LOWEST) { state.tap() }
                .semantics {
                    contentDescription = label
                    stateDescription = SheetTexts.state(h)
                    customActions = actions
                }
                .testTag(SHEET_HANDLE_TAG),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(handleColor, RoundedCornerShape(2.dp)),
            )
            header()
        }
        content()
    }
}
