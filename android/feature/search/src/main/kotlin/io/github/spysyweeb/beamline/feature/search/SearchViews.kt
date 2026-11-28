package io.github.spysyweeb.beamline.feature.search

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.spysyweeb.beamline.ui.theme.PRIMARY_BUTTON_HEIGHT
import io.github.spysyweeb.beamline.ui.theme.SHEET_CORNER
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons
import io.github.spysyweeb.beamline.ui.theme.SheetFrame
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.ui.theme.SheetHeight
import io.github.spysyweeb.beamline.ui.theme.SheetSteps
import io.github.spysyweeb.beamline.ui.theme.rememberSheetState

// ---------------------------------------------------------------------------------------------
// Colours: the map's panels (as :app's round buttons), following the theme (night = dark screens).

/**
 * [onAccent]: text on a filled [accent] button (white by day; a dark blue by night, because white on
 * the night accent is only about 2.5:1). [off]/[offText]: a filled button that is off (grey, so it
 * never reads as on, by day or night).
 */
internal class Palette(
    val panel: Color, val panelText: Color, val subtle: Color, val divider: Color, val accent: Color, val problem: Color,
    val onAccent: Color, val off: Color, val offText: Color,
)

internal fun palette(night: Boolean) = if (night) {
    // P9 (0.1.40): the panels are opaque (the place window, the pick's prompt and bar, the pill, the headers):
    // map labels never show through their text
    Palette(Color(0xFF15191E), Color(0xFFD0D4DA), Color(0xFF8E99A6), Color(0xFF2A333D), Color(0xFF5AA5FF), Color(0xFFFF8A80),
        onAccent = Color(0xFF0B1A2E), off = Color(0xFF2A333D), offText = Color(0xFF7D8894))
} else {
    Palette(Color(0xFFFFFFFF), Color(0xFF333333), Color(0xFF66717D), Color(0xFFE1E6EB), Color(0xFF2A74D8), Color(0xFFC62828),
        onAccent = Color.White, off = Color(0xFFE1E6EB), offText = Color(0xFF7D8894))
}

@Composable
internal fun isNight(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.3f

// ---------------------------------------------------------------------------------------------
// PV8: the text fields ask the keyboard not to learn what is typed (the keyboard's "incognito"
// request) and turn suggestions and autocorrect off.

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NoLearningKeyboard(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            val private = object : PlatformTextInputMethodRequest {
                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                    val ic = request.createInputConnection(outAttributes)
                    outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    outAttributes.inputType = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    outAttributes.inputType = outAttributes.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT.inv()
                    return ic
                }
            }
            next.startInputMethod(private)
        },
        content = content,
    )
}

internal val privateKeyboard = KeyboardOptions(
    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Search,
)

/**
 * A one-line text field with a hint, in the panel colours; PV8 applied. It holds its own selection (P56, 0.1.40):
 * it opens with the cursor at the end of [value], or with all of it selected ([selectAll], the Save and Rename
 * dialogs), and otherwise behaves as BasicTextField(String) (the caller's text, the last text reported).
 */
@Composable
internal fun PrivateField(
    value: String, onChange: (String) -> Unit, hint: String, night: Boolean, label: String? = null,
    focus: Boolean = false, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null, selectAll: Boolean = false,
) {
    val p = palette(night)
    val fr = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(value, if (selectAll) TextRange(0, value.length) else TextRange(value.length))) }
    val current = field.copy(text = value)   // the caller's text; the selection coerced into it, as BasicTextField(String) does
    SideEffect { if (current.selection != field.selection || current.composition != field.composition) field = current }
    // as BasicTextField(String): a change is what differs from the text last reported
    var lastText by remember(value) { mutableStateOf(value) }
    NoLearningKeyboard {
        BasicTextField(
            value = current,
            onValueChange = { v ->
                field = v
                val changed = lastText != v.text
                lastText = v.text
                if (changed) onChange(v.text)
            },
            singleLine = true,
            textStyle = TextStyle(color = p.panelText, fontSize = 17.sp),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = privateKeyboard.copy(imeAction = if (label == null) ImeAction.Search else ImeAction.Done),
            keyboardActions = if (onSearch == null) KeyboardActions.Default else KeyboardActions(onSearch = { onSearch() }),
            modifier = modifier.focusRequester(fr).semantics { contentDescription = label ?: hint },
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                    // the hint is drawn only; the field's description already says it (not read twice). The tag (P57) sits
                    // before the clearing, so tests find it while screen readers do not
                    if (value.isEmpty()) Text(hint, color = p.subtle, fontSize = 17.sp, maxLines = 1,
                        modifier = Modifier.testTag(FIELD_HINT_TAG).clearAndSetSemantics {})
                    inner()
                }
            },
        )
    }
    if (focus) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

/** Test tag of a field's drawn hint (P57); never read by a screen reader. */
internal const val FIELD_HINT_TAG = "field-hint"

// ---------------------------------------------------------------------------------------------
// SP2: the pill

@Composable
internal fun SearchPill(onClick: () -> Unit, modifier: Modifier, query: String? = null, onClear: () -> Unit = {}) {
    val night = isNight()
    val p = palette(night)
    val shown = query?.takeIf { it.isNotBlank() }
    Surface(
        shape = RoundedCornerShape(percent = 50), color = p.panel, contentColor = p.panelText, shadowElevation = 3.dp,
        modifier = modifier.heightIn(min = 48.dp).widthIn(max = 480.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            // the pill's one label is its description (SP2, SP5 item 1); the drawn words are not read a second time.
            // The label, the button role and the click are on this one node (the X beside it is its own button).
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = if (shown == null) 16.dp else 4.dp)
                .clearAndSetSemantics {
                    contentDescription = if (shown == null) Texts.SEARCH_HINT else Texts.searchAgain(shown)
                    role = Role.Button
                    semanticsOnClick(label = null) { onClick(); true }
                }) {
                MagnifierGlyph(p.panelText)
                Spacer(Modifier.size(12.dp))
                Text(shown ?: Texts.SEARCH_HINT, color = p.panelText, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (shown == null) null else androidx.compose.ui.text.font.FontWeight.Medium)
            }
            if (shown != null) {
                // ends the results on the map and empties the bar
                androidx.compose.material3.IconButton(onClick = onClear, modifier = Modifier.testTag("search-bar-clear")) {
                    CrossGlyph(p.panelText, Modifier.semantics { contentDescription = Texts.CLEAR_SEARCH })
                }
            }
        }
    }
}

/** The X of the pill and of the field (20 dp); draws only, no semantics of its own: each caller names its button. */
@Composable
private fun CrossGlyph(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(20.dp)) {
        val w = size.minDimension
        val a = w * 0.22f
        val stroke = w * 0.11f
        drawLine(color, Offset(a, a), Offset(w - a, w - a), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(w - a, a), Offset(a, w - a), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** P44 (0.1.40, audit F50): empties the search field; one button named "Clear text". */
@Composable
private fun ClearTextButton(night: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clickable(onClick = onClick)
        .semantics { contentDescription = Texts.CLEAR_TEXT; role = Role.Button }) {
        CrossGlyph(palette(night).subtle)
    }
}

/** The magnifier (24 dp), drawn by S (SP2). */
@Composable
internal fun MagnifierGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension
        val r = s * 0.30f
        val c = androidx.compose.ui.geometry.Offset(s * 0.42f, s * 0.42f)
        drawCircle(color, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = s * 0.09f))
        drawLine(color, androidx.compose.ui.geometry.Offset(c.x + r * 0.72f, c.y + r * 0.72f), androidx.compose.ui.geometry.Offset(s * 0.88f, s * 0.88f),
            strokeWidth = s * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------------------------
// Rows (SP5, SP6)

/** True when the font scale is above 1.3: rows stack (SP6). */
@Composable
internal fun stacked(): Boolean = LocalDensity.current.fontScale > 1.3f

@Composable
internal fun PlaceRow(
    iconName: String?, title: String, subtitle: String?, distance: String?, note: String?, night: Boolean,
    onClick: (() -> Unit)?, editLabel: String? = null, onEdit: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
    hours: HoursUi? = null,
    /** Item 51: the time a stop result adds ("+4 min", "On the way"): at the end above the distance, or first in the stacked line. */
    lead: String? = null,
) {
    val p = palette(night)
    val stack = stacked()
    val words = listOfNotNull(title, subtitle, lead, hours?.let(::hoursWords), distance, note).joinToString(", ")
    Row(   // SP6: when stacked, "Edit" sits at the end of the title line
        verticalAlignment = if (stack) Alignment.Top else Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp),
    ) {
        Row(
            verticalAlignment = if (stack) Alignment.Top else Alignment.CenterVertically,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                // one stop that reads exactly its texts in order (SP5 item 2); the click stays (it is outside)
                .clearAndSetSemantics { contentDescription = words; if (onClick != null) role = Role.Button }
                .padding(vertical = 8.dp),
        ) {
            // stacked (SP6): the icon stays beside the title line
            val iconTop = if (stack) Modifier.padding(top = 4.dp) else Modifier
            if (iconName != null) SpriteIcon(iconName, night, 24.dp, iconTop) else Spacer(Modifier.size(24.dp))
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = p.panelText, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, color = p.subtle, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (hours != null) HoursLine(hours, night, fontSize = 13)
                if (stack && (lead != null || distance != null || note != null)) {
                    val rest = listOfNotNull(distance, note).joinToString(" · ")
                    Text(buildAnnotatedString {
                        if (lead != null) {
                            withStyle(SpanStyle(color = p.panelText, fontWeight = FontWeight.SemiBold)) { append(lead) }
                            if (rest.isNotEmpty()) append(" · ")
                        }
                        append(rest)
                    }, color = p.subtle, fontSize = 13.sp)
                }
            }
            if (!stack && (lead != null || distance != null || note != null)) {
                Spacer(Modifier.size(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (lead != null) Text(lead, color = p.panelText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    if (distance != null) Text(distance, color = p.subtle, fontSize = 13.sp, maxLines = 1, softWrap = false)
                    if (note != null) Text(note, color = p.subtle, fontSize = 12.sp, maxLines = 2)
                }
            }
        }
        if (editLabel != null && onEdit != null) {
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = editLabel }) {
                Text(Texts.EDIT, color = p.accent, fontSize = 15.sp, softWrap = false, modifier = Modifier.clearAndSetSemantics {})   // read as "Edit {name}"
            }
        }
        trailing?.invoke()
    }
}

/** A row that acts ("Use my position", "Choose on the map", "Add a place"); P60 (0.1.40): its icon in front, the text in PlaceRow's column. */
@Composable
internal fun PlainRow(text: String, night: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val p = palette(night)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { role = Role.Button }.padding(start = 16.dp, end = 16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = p.accent, modifier = Modifier.size(24.dp).testTag(PLAIN_ROW_ICON_TAG))
        else Spacer(Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** Test tag of a plain row's icon (P60). */
internal const val PLAIN_ROW_ICON_TAG = "plain-row-icon"

@Composable
internal fun NoticeCard(text: String, night: Boolean, onOk: (() -> Unit)?) {
    val p = palette(night)
    Surface(
        shape = RoundedCornerShape(10.dp), color = p.panel, contentColor = p.panelText, border = BorderStroke(1.dp, p.divider),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text, fontSize = 15.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (onOk != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOk, modifier = Modifier.heightIn(min = 48.dp)) { Text(Texts.OK, color = p.accent) }
                }
            }
        }
    }
}

@Composable
internal fun BackButton(night: Boolean, label: String = Texts.BACK, onClick: () -> Unit) {
    val p = palette(night)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clickable(onClick = onClick).semantics { contentDescription = label; role = Role.Button },
    ) { androidx.compose.material3.Icon(BeamlineIcons.Back, contentDescription = null, tint = p.panelText) }
}

// ---------------------------------------------------------------------------------------------
// The search screen (2.1)

/**
 * The search screen (2.1). [sideInsets] (P47, 0.1.40): the sides a side navigation bar or a cutout takes in
 * landscape; the content, the field's X, the chip fades and the Undo bar stay clear of them.
 */
@Composable
internal fun SearchScreenContent(
    ui: SearchUi, onEvent: (SearchEvent) -> Unit, modifier: Modifier,
    sideInsets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
) {
    // Batch O3 (item 27): "View all" shows every place type over the search screen
    ui.allTypes?.let { groups ->
        AllTypesScreen(groups, onEvent, modifier)
        return
    }
    val night = isNight()
    val p = palette(night)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().windowInsetsPadding(sideInsets).imePadding()) {
        Surface(color = p.panel, shadowElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                val title = ui.title
                // P60 (0.1.40): a titled search has the other pages' header; a short window (landscape, the keyboard
                // up) keeps one row, so the results keep their room
                val tall = title != null && LocalConfiguration.current.screenHeightDp >= TITLED_TWO_ROWS_MIN_DP
                if (tall) {
                    // Back and the title on one row; the field under them in a box (as the dialogs' Name field), so
                    // it still reads as a field with the keyboard down
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        BackButton(night) { onEvent(SearchEvent.Close) }
                        Text(title, color = p.panelText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 16.dp).semantics { heading() })
                    }
                    Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, p.divider), color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrivateField(ui.query, { onEvent(SearchEvent.QueryChanged(it)) }, Texts.SEARCH_HINT, night,
                                focus = ui.focusField, modifier = Modifier.weight(1f), onSearch = { onEvent(SearchEvent.Submit) })
                            // P44 (audit F50): the words stay more often now; one tap empties them
                            if (ui.query.isNotEmpty()) ClearTextButton(night) { onEvent(SearchEvent.QueryChanged("")) }
                        }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    BackButton(night) { onEvent(SearchEvent.Close) }
                    // P60: a short titled window: the title on one line beside Back, then the field
                    if (title != null) Text(title, color = p.panelText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp).padding(end = 8.dp).semantics { heading() })
                    PrivateField(ui.query, { onEvent(SearchEvent.QueryChanged(it)) }, Texts.SEARCH_HINT, night,
                        focus = ui.focusField, modifier = Modifier.weight(1f), onSearch = { onEvent(SearchEvent.Submit) })
                    // P44 (audit F50): the words stay more often now; one tap empties them
                    if (ui.query.isNotEmpty()) ClearTextButton(night) { onEvent(SearchEvent.QueryChanged("")) }
                }
                // P6: town names vary in length ("Near the map center · no position yet" wraps at font 1.3)
                if (ui.nearText != null) Text(ui.nearText, color = p.subtle, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = if (tall) 28.dp else 60.dp, end = 16.dp, top = if (tall) 4.dp else 0.dp, bottom = 2.dp))
            }
        }
        if (ui.phase == SearchPhase.SEARCHING) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.savedNotice != null) item { NoticeCard(ui.savedNotice, night) { onEvent(SearchEvent.DismissSavedNotice) } }
            // why a pick in SAVE mode was refused ("This place is already saved as Gym."); NO_RESULTS and
            // NO_SEARCH_DATA show their own line below
            if (ui.message != null && ui.phase != SearchPhase.NO_RESULTS && ui.phase != SearchPhase.NO_SEARCH_DATA) item {
                Box(Modifier.padding(horizontal = 16.dp)) { Problem(ui.message, p) }
            }
            if (ui.query.isBlank()) startRows(ui, onEvent, night) else resultRows(ui, onEvent, night)
            item { Spacer(Modifier.navigationBarsPadding().size(16.dp)) }
        }
        // P45: "Recent places cleared" with Undo, above the keyboard (the screen pads for it)
        ui.undoText?.let { UndoBar(it, night, onEvent) }
    }
}

/** P60: a titled search window at least this tall (dp) puts its title on its own row beside Back, the field under it. */
private const val TITLED_TWO_ROWS_MIN_DP = 480

private fun LazyListScope.startRows(ui: SearchUi, onEvent: (SearchEvent) -> Unit, night: Boolean) {
    // P22: Change start's own words for the current position; P60: the rows' icons
    if (ui.useMyPosition) item {
        PlainRow(if (ui.mode == SearchMode.SET_START) Texts.YOUR_POSITION else Texts.USE_MY_POSITION, night, BeamlineIcons.MyLocation) { onEvent(SearchEvent.UseMyPosition) }
    }
    if (ui.chooseOnMap) item { PlainRow(Texts.CHOOSE_ON_MAP, night, BeamlineIcons.Pin) { onEvent(SearchEvent.ChooseOnMap) } }
    for (s in ui.slots) item(key = "slot:${s.kind}") {
        PlaceRow(s.iconName, s.title, s.subtitle, s.distanceText, s.note, night, onClick = { onEvent(SearchEvent.SlotTapped(s.kind)) })
    }
    if (ui.savedPlacesText != null) item {
        PlaceRow(Icons.SAVED_PLACE, Texts.SAVED_PLACES, ui.savedPlacesText, null, null, night, onClick = { onEvent(SearchEvent.OpenSavedPlaces) })
    }
    if (ui.phase == SearchPhase.NO_SEARCH_DATA) item { NoSearchData(ui.message, ui.statesOffered, night, onEvent) }
    if (ui.shortcuts.isNotEmpty()) item { Shortcuts(ui.shortcuts, night, onEvent) }
    if (ui.recents.isNotEmpty()) {
        item {
            val p = palette(night)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp)) {
                Text(Texts.RECENT, color = p.subtle, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).semantics { heading() })
                // P45 (audit F49): "Clear" says what it clears
                TextButton(onClick = { onEvent(SearchEvent.ClearRecents) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = Texts.CLEAR_RECENTS }) {
                    Text(Texts.CLEAR, color = p.accent, modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
        for (r in ui.recents) item(key = r.key) {
            PlaceRow(r.iconName, r.title, r.subtitle, r.distanceText, r.note, night, onClick = { onEvent(SearchEvent.RowTapped(r.key)) },
                trailing = { RemoveButton(night, r.title) { onEvent(SearchEvent.RemoveRecent(r.key)) } })
        }
    }
}

private fun LazyListScope.resultRows(ui: SearchUi, onEvent: (SearchEvent) -> Unit, night: Boolean) {
    for (r in ui.savedResults) item(key = r.key) {
        PlaceRow(r.iconName, r.title, r.subtitle, r.distanceText, r.note, night, onClick = { onEvent(SearchEvent.RowTapped(r.key)) })
    }
    if (ui.savedResults.isNotEmpty() && ui.results.isNotEmpty()) item { HorizontalDivider(color = palette(night).divider) }
    ui.results.forEachIndexed { i, r ->
        // P2: the name matches after the category's own, under their heading (each heading its own key, review F1)
        if (i > 0 && r.otherMatch && !ui.results[i - 1].otherMatch) item(key = "$OTHER_MATCHES_TAG:$i") { OtherMatchesHeading(night) }
        item(key = r.key) { ResultRow(r, night) { onEvent(SearchEvent.RowTapped(r.key)) } }
    }
    when (ui.phase) {
        // P47: a hint after typed words, and in GO_TO a way on; the live region stays on the first line alone
        // (TalkBack does not repeat the hint after each letter)
        SearchPhase.NO_RESULTS -> item {
            val p = palette(night)
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(ui.message ?: "", color = p.panelText, fontSize = 15.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (ui.noResultsHint != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(ui.noResultsHint, color = p.subtle, fontSize = 14.sp)
                }
                if (ui.mode == SearchMode.GO_TO && ui.statesOffered) {   // review F4: not during a trip (P30)
                    Spacer(Modifier.size(12.dp))
                    PillButton(Texts.ADD_STATE_BUTTON, night) { onEvent(SearchEvent.OpenStates) }
                }
            }
        }
        SearchPhase.NO_SEARCH_DATA -> item { NoSearchData(ui.message, ui.statesOffered, night, onEvent) }
        else -> {}
    }
}

@Composable
private fun NoSearchData(message: String?, statesOffered: Boolean, night: Boolean, onEvent: (SearchEvent) -> Unit) {
    val p = palette(night)
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        if (message != null) Text(message, color = p.panelText, fontSize = 15.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (statesOffered) {   // review F4: not during a trip (P30)
            Spacer(Modifier.size(8.dp))
            PillButton(Texts.ADD_STATE_BUTTON, night, primary = true) { onEvent(SearchEvent.OpenStates) }
        }
    }
}

@Composable
private fun Shortcuts(list: List<ShortcutUi>, night: Boolean, onEvent: (SearchEvent) -> Unit) {
    val p = palette(night)
    val scroll = rememberScrollState()
    val bg = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp).testTag(SHORTCUTS_ROW_TAG),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in list) {
                Surface(
                    onClick = { onEvent(SearchEvent.ShortcutTapped(s.id)) }, shape = RoundedCornerShape(20.dp), color = p.panel,
                    contentColor = p.panelText, border = BorderStroke(1.dp, p.divider),
                    modifier = Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = s.label; role = Role.Button },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp).clearAndSetSemantics {}) {
                        SpriteIcon(s.iconName, night, 20.dp)
                        Spacer(Modifier.size(6.dp))
                        Text(s.label, fontSize = 15.sp, softWrap = false)
                    }
                }
            }
            // Batch O3 (docs/next-round.md item 27): every place type, in a grid
            Surface(
                onClick = { onEvent(SearchEvent.ViewAll) }, shape = RoundedCornerShape(20.dp), color = p.panel,
                contentColor = p.accent, border = BorderStroke(1.dp, p.divider),
                modifier = Modifier.heightIn(min = 48.dp).testTag(VIEW_ALL_TAG)
                    .semantics(mergeDescendants = true) { contentDescription = Texts.VIEW_ALL; role = Role.Button },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).clearAndSetSemantics {}) {
                    Text(Texts.VIEW_ALL, fontSize = 15.sp, fontWeight = FontWeight.Medium, softWrap = false)
                }
            }
        }
        // P47 (0.1.40): at large text the row runs off the screen; a fade at the side with more says it scrolls
        if (scroll.canScrollForward) EdgeFade(bg, atEnd = true, Modifier.matchParentSize().testTag(SHORTCUTS_FADE_TAG))
        if (scroll.canScrollBackward) EdgeFade(bg, atEnd = false, Modifier.matchParentSize())
    }
}

/** P47: a 32 dp fade into [color] at the row's end ([atEnd]) or start; draws only, takes no touch. */
@Composable
private fun EdgeFade(color: Color, atEnd: Boolean, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = 32.dp.toPx().coerceAtMost(size.width)
        val x0 = if (atEnd) size.width - w else 0f
        val colors = if (atEnd) listOf(color.copy(alpha = 0f), color) else listOf(color, color.copy(alpha = 0f))
        drawRect(Brush.horizontalGradient(colors, startX = x0, endX = x0 + w), topLeft = Offset(x0, 0f), size = Size(w, size.height))
    }
}

internal const val SHORTCUTS_ROW_TAG = "shortcuts-row"
internal const val SHORTCUTS_FADE_TAG = "shortcuts-fade"

/** P45 (audit F49): the X of a recent says which place it removes. */
@Composable
private fun RemoveButton(night: Boolean, name: String, onClick: () -> Unit) {
    val p = palette(night)
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clickable(onClick = onClick)
        .semantics { contentDescription = Texts.removeRecent(name); role = Role.Button }) {
        Text("×", color = p.subtle, fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** P45: the bar's time without accessibility settings, as long as the End bar's (END_UNDO_MS). */
internal const val RECENTS_UNDO_MS = 10_000L
internal const val UNDO_BAR_TAG = "undo-bar"

/**
 * P45: the bar after "Clear" (the app's snackbar's colours), above the keyboard, read out once. It goes
 * after RECENTS_UNDO_MS, or the longer time the phone's accessibility setting asks for; while TalkBack
 * explores by touch it stays until Undo, typing or Back (Undo is the screen's last element).
 */
@Composable
private fun UndoBar(text: String, night: Boolean, onEvent: (SearchEvent) -> Unit) {
    val a11y = LocalAccessibilityManager.current
    val context = LocalContext.current
    val send by rememberUpdatedState(onEvent)   // the effect outlives recompositions: it sends to the current handler
    LaunchedEffect(text) {
        val touch = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
        if (touch) return@LaunchedEffect
        delay(a11y?.calculateRecommendedTimeoutMillis(RECENTS_UNDO_MS, containsIcons = false, containsText = true, containsControls = true) ?: RECENTS_UNDO_MS)
        send(SearchEvent.UndoExpired)
    }
    Surface(
        shape = RoundedCornerShape(8.dp), color = if (night) Color(0xFFE6E8EB) else Color(0xFF2B2F33),
        contentColor = if (night) Color(0xFF1B1E22) else Color.White, shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).testTag(UNDO_BAR_TAG),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 15.sp, modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = { onEvent(SearchEvent.UndoClearRecents) }, modifier = Modifier.heightIn(min = 48.dp).padding(end = 4.dp)) {
                Text(Texts.UNDO, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (night) Color(0xFF1F5FBF) else Color(0xFF8AB4F8))
            }
        }
    }
}

@Composable
internal fun PillButton(
    text: String, night: Boolean, primary: Boolean = false, enabled: Boolean = true, icon: ImageVector? = null,
    /** P58 (0.1.40): a primary that removes something is red, as the menu's Remove. */
    destructive: Boolean = false, onClick: () -> Unit,
) {
    val p = palette(night)
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(22.dp),
        // item 47's style rules: one blue for the primary; a secondary is grey-filled with dark text (10:1 by
        // day, 8.6:1 by night), never outlined; both 52 dp tall. P58: a destructive primary is red (white on
        // #C62828 by day, 5.6:1; the dark blue on #FF8A80 by night, about 7:1)
        color = when {
            primary && enabled && destructive -> p.problem
            primary && enabled -> p.accent
            else -> p.off
        },
        contentColor = when {
            !enabled -> p.offText
            primary -> p.onAccent
            else -> p.panelText
        },
        modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT).semantics { role = Role.Button },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT).padding(start = if (icon != null) 14.dp else 18.dp, end = 18.dp)) {
            // item 47: a secondary button carries its icon (the place window's outline set)
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, softWrap = false)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The place sheet (S7, SP19)

/**
 * The place sheet (S7, S10, SP19; rule D10; batch G, docs/next-round.md item 20): three heights, the
 * title line alone (the map free), the sheet of batch B (status, buttons) and the details height
 * (everything the packs say: the whole week, every number and web address written out, the address,
 * what the place is). A drag or a tap on the handle or the title line moves between them; a tap on
 * the status line raises it to the details height. A new place opens at the lowest height (item 47;
 * 0.1.40 P9/P51/P53: name, distance and where it is on one line, whether it is open, the main button and
 * Save). The details height is at most [DETAILS_MAX_SHARE] of the window and scrolls inside. [insets]
 * (P1, 0.1.40): what its content keeps clear of (its surface reaches under them).
 */
@Composable
internal fun PlaceSheetContent(ui: PlaceSheetUi, onEvent: (PlaceSheetEvent) -> Unit, modifier: Modifier, insets: WindowInsets = sheetInsets()) {
    val night = isNight()
    val p = palette(night)
    // item 47 (the owner, 2026-11-25): a place opens at peek (name, status, Directions, Save); F42 (0.1.40): the
    // same place keeps its height while its line fills in or it is saved
    val sheet = rememberSheetState(SheetSteps.THREE, SheetHeight.LOW, placeSheetKey(ui.place))
    val open = sheet.height != SheetHeight.LOW
    val details = sheet.height == SheetHeight.FULL
    val maxHeight = (LocalConfiguration.current.screenHeightDp * DETAILS_MAX_SHARE).dp
    Surface(
        shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER), color = p.panel, contentColor = p.panelText, shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().heightIn(max = maxHeight).testTag(PLACE_SHEET_TAG),
    ) {
        SheetFrame(
            sheet, Texts.PLACE_DETAILS, p.subtle, Modifier.windowInsetsPadding(insets),
            header = {
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = if (open) 0.dp else 8.dp)) {
                    // P17 (0.1.40): a dropped pin is a pin (the address row's outline pin, in the blue of the map's pin), not the town ring
                    if (ui.place.source == PlaceSource.MAP_PIN) Icon(BeamlineIcons.Pin, contentDescription = null, tint = p.accent,
                        modifier = Modifier.padding(top = 2.dp).size(28.dp).testTag(PLACE_PIN_ICON_TAG))
                    else SpriteIcon(ui.iconName, night, 28.dp, Modifier.padding(top = 2.dp))
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                        Text(ui.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = if (open) 2 else 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() })
                        // P9, P17, P51, P53 (F41), 0.1.40: where the place is, at every height: its own line, else the pack's
                        // address (a map label). From half up it has its own line (up to 3) above "distance · Saved as ...".
                        // At peek it shares the distance's line, distance first: the peek keeps its number of lines, the
                        // ellipsis never eats the distance, and a map label's address arriving late does not grow the peek
                        val where = ui.subtitle ?: ui.address
                        if (open && where != null) Text(where, color = p.subtle, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        val line = listOfNotNull(ui.distanceText, if (open) ui.savedText else where ?: ui.savedText).joinToString(" · ")
                        if (line.isNotEmpty()) Text(line, color = p.subtle, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // item 47: peek says whether it is open now (the full line is under the header from half up)
                        if (!open && ui.hours != null) HoursLine(ui.hours, night, fontSize = 14)
                        // F41: why the main button reads "Add a state", at peek too
                        if (ui.note != null) Text(ui.note, color = p.panelText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        // Batch O3 (item 26): the values shown are the user's own, until the map update has them
                        if (ui.ownTag != null) Surface(
                            shape = RoundedCornerShape(8.dp), color = p.accent.copy(alpha = 0.14f), contentColor = p.panelText,
                            modifier = Modifier.padding(top = 4.dp).testTag(PLACE_OWN_TAG),
                        ) { Text(ui.ownTag, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
                    }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clickable { onEvent(PlaceSheetEvent.Close) }
                        .semantics { contentDescription = "Close"; role = Role.Button }) { Text("×", color = p.subtle, fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {}) }
                }
            },
        ) {
            // item 47: peek keeps the main button and Save, so a place can be driven to without dragging
            if (!open) {
                val main = ui.actions.firstOrNull { it == PlaceAction.DIRECTIONS || it == PlaceAction.ADD_STOP || it == PlaceAction.OPEN_STATES }
                val keep = ui.actions.firstOrNull { it == PlaceAction.SAVE || it == PlaceAction.EDIT }
                // F41 (0.1.40): Save wraps under the main button when both do not fit (large text, a long drive time), never cut off
                if (main != null || keep != null) FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).testTag(PLACE_PEEK_ACTIONS_TAG),
                ) {
                    if (main != null) PillButton(placeActionLabel(main, ui.driveTimeText), night, primary = true) { onEvent(PlaceSheetEvent.Act(main)) }
                    if (keep != null) PillButton(placeActionLabel(keep), night, primary = false, icon = placeActionIcon(keep)) { onEvent(PlaceSheetEvent.Act(keep)) }
                }
            }
            if (open) Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            ) {
                val hours = ui.hours
                if (hours != null) {
                    // S10: the status; a tap shows the whole week (the details height). The 0.1.40 replay: the line sits right
                    // under the distance (centred in its 48 dp tap box it read as a missing row above it); at the middle
                    // height the tap box goes on below the line, at the details height (no tap) there is none
                    Column(
                        Modifier.fillMaxWidth().padding(start = 40.dp, top = 2.dp)
                            .then(if (details) Modifier else Modifier.heightIn(min = 48.dp).clickable { sheet.set(SheetHeight.FULL) })
                            .semantics(mergeDescendants = true) {
                                if (!details) role = Role.Button
                                contentDescription = hoursWords(hours) + if (details) "" else ", " + Texts.SHOW_DETAILS.lowercase()
                            }
                            .testTag(PLACE_STATUS_TAG),
                        verticalArrangement = Arrangement.Top,
                    ) {
                        HoursLine(hours, night, fontSize = 15)
                    }
                }
                // the buttons 12 dp under what is above them (at the middle height the status line's tap box is that room)
                if (hours == null || details) Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (a in ui.actions) {
                        val label = placeActionLabel(a, ui.driveTimeText)
                        val primary = a == ui.actions.firstOrNull { it == PlaceAction.DIRECTIONS || it == PlaceAction.ADD_STOP || it == PlaceAction.OPEN_STATES }
                        PillButton(label, night, primary = primary, icon = if (primary) null else placeActionIcon(a)) { onEvent(PlaceSheetEvent.Act(a)) }
                    }
                }
                // Batch O1: a long press on the map gives a dropped pin, and with it "Report a problem here";
                // batch O3 (docs/online-design.md 4.3): "Add a missing place" beside it, on a row of their own so
                // both show without scrolling the buttons above
                val pinReport = ui.canReport && ui.place.source == PlaceSource.MAP_PIN
                val pinAdd = ui.canAddPlace && ui.place.source == PlaceSource.MAP_PIN
                if (pinReport || pinAdd) Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                ) {
                    if (pinReport) PillButton(Texts.REPORT_HERE, night, primary = false, icon = BeamlineIcons.Flag) { onEvent(PlaceSheetEvent.Act(PlaceAction.REPORT)) }
                    if (pinAdd) PillButton(Texts.ADD_PLACE, night, primary = false, icon = BeamlineIcons.Add) { onEvent(PlaceSheetEvent.Act(PlaceAction.ADD_PLACE)) }
                }
                if (details) PlaceDetails(ui, night, onEvent)
            }
        }
    }
}

/** Item 47: the icon of a secondary place sheet button (one outline set), or null. */
internal fun placeActionIcon(a: PlaceAction): ImageVector? = when (a) {
    PlaceAction.SAVE -> BeamlineIcons.Star
    PlaceAction.EDIT -> BeamlineIcons.Pencil
    PlaceAction.CALL -> BeamlineIcons.Phone
    PlaceAction.WEBSITE -> BeamlineIcons.Globe
    PlaceAction.REPORT -> BeamlineIcons.Flag
    PlaceAction.ADD_STOP, PlaceAction.ADD_PLACE -> BeamlineIcons.Add
    else -> null
}

/** The words of a place sheet button; Directions with the drive time when there is one (item 47: "Directions · 6 min"). */
internal fun placeActionLabel(a: PlaceAction, driveTime: String? = null): String = when (a) {
    PlaceAction.DIRECTIONS -> if (driveTime != null) "${Texts.DIRECTIONS} · $driveTime" else Texts.DIRECTIONS
    PlaceAction.ADD_STOP -> Texts.ADD_STOP
    PlaceAction.OPEN_STATES -> Texts.ADD_STATE_BUTTON
    PlaceAction.SAVE -> Texts.SAVE
    PlaceAction.EDIT -> Texts.EDIT
    PlaceAction.CALL -> Texts.CALL
    PlaceAction.WEBSITE -> Texts.WEBSITE
    PlaceAction.REPORT -> Texts.REPORT
    PlaceAction.EDIT_OSM -> Texts.EDIT_OSM
    PlaceAction.ADD_PLACE -> Texts.ADD_PLACE
}

/** Test tag of the buttons a place shows at peek (item 47). */
internal const val PLACE_PEEK_ACTIONS_TAG = "place-peek-actions"

/** Test tag of a dropped pin's pin icon in its window's header (P17). */
internal const val PLACE_PIN_ICON_TAG = "place-pin-icon"

/**
 * P1 (0.1.40): what a bottom sheet's content keeps clear of: the navigation bar and a display cutout at the
 * bottom and on either side (its surface reaches under them), as the drive overlay's sheets.
 */
@Composable
internal fun sheetInsets(): WindowInsets =
    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

/** The highest share of the window the place sheet's details height takes (the map keeps the rest; batch G). */
internal const val DETAILS_MAX_SHARE = 0.7f

/**
 * The details height's part under the buttons (batch G): what the place is, the whole week (Monday
 * to Sunday, today in bold, "Holiday hours may differ"; nothing without readable hours), every
 * number and web address on its own line, the postal address. A tap on a number or web address
 * opens the dialer or the browser as the Call and Website buttons do; a long press copies it (and
 * the address) with a short "Copied" note. While guiding the lines show but do nothing.
 */
@Composable
private fun PlaceDetails(ui: PlaceSheetUi, night: Boolean, onEvent: (PlaceSheetEvent) -> Unit) {
    val p = palette(night)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(ui.place) { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(COPIED_NOTE_MS)
            copied = null
        }
    }
    val copy: (String, String) -> Unit = { tag, text ->
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(Texts.PLACE_DETAILS, text))) }
        copied = tag
    }
    Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag(PLACE_DETAILS_TAG)) {
        HorizontalDivider(color = p.divider)
        if (ui.category != null) Text(ui.category, color = p.panelText, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp))
        // item 47: icon rows (clock, phone, globe, pin), the icon named for screen readers as the caption was
        val hours = ui.hours
        if (hours != null && hours.week.isNotEmpty()) DetailRow(BeamlineIcons.Clock, Texts.HOURS, p) {
            HoursWeek(hours, night, Modifier.padding(start = 0.dp, top = 12.dp))
        }
        if (ui.phones.isNotEmpty()) DetailRow(BeamlineIcons.Phone, Texts.PHONE, p) {
            ui.phones.forEachIndexed { i, c ->
                ContactLine(c, "detail-phone-$i", PlaceAction.CALL, Texts.CALL, ui.canOpenOutside, copied, night, onEvent, copy)
            }
        }
        if (ui.websites.isNotEmpty()) DetailRow(BeamlineIcons.Globe, Texts.WEBSITE_CAPTION, p) {
            ui.websites.forEachIndexed { i, c ->
                ContactLine(c, "detail-website-$i", PlaceAction.WEBSITE, Texts.OPEN_WEBSITE, ui.canOpenOutside, copied, night, onEvent, copy)
            }
        }
        if (ui.address != null) DetailRow(BeamlineIcons.Pin, Texts.ADDRESS, p) {
            ContactLine(ContactUi(ui.address, null, ui.address), "detail-address", null, null, ui.canOpenOutside, copied, night, onEvent, copy)
        }
        // Batch O1 (docs/online-design.md 4.1): a public OSM note at the place's point
        if (ui.canReport && ui.place.source != PlaceSource.MAP_PIN) {
            HorizontalDivider(color = p.divider, modifier = Modifier.padding(top = 12.dp))
            Text(
                Texts.REPORT, color = p.accent, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = Texts.REPORT) {
                    onEvent(PlaceSheetEvent.Act(PlaceAction.REPORT))
                }.padding(top = 14.dp).testTag(PLACE_REPORT_TAG),
            )
        }
        // Batch O2 (docs/online-design.md 4.2): fix the place's details on OSM
        if (ui.canEditOsm && ui.osm != null) {
            Text(
                Texts.EDIT_OSM, color = p.accent, fontSize = 16.sp,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = Texts.EDIT_OSM) {
                    onEvent(PlaceSheetEvent.Act(PlaceAction.EDIT_OSM))
                }.padding(top = 14.dp).testTag(PLACE_EDIT_OSM_TAG),
            )
        }
    }
}

private const val COPIED_NOTE_MS = 2_000L

/** Item 47: one row of the details: its outline icon (named [label], a heading for screen readers), then [content]. */
@Composable
private fun DetailRow(icon: ImageVector, label: String, p: Palette, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Icon(icon, contentDescription = label, tint = p.subtle, modifier = Modifier.padding(top = 12.dp).size(24.dp).semantics { heading() })
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), content = content)
    }
}

/** One number, web address or the address: a tap opens it ([action]), a long press copies it; plain text while guiding. */
@Composable
private fun ContactLine(
    c: ContactUi, tag: String, action: PlaceAction?, actionLabel: String?, enabled: Boolean, copied: String?, night: Boolean,
    onEvent: (PlaceSheetEvent) -> Unit, copy: (String, String) -> Unit,
) {
    val p = palette(night)
    val opens = enabled && action != null && c.value != null
    val m = if (!enabled) Modifier else Modifier.combinedClickable(
        onClickLabel = if (opens) actionLabel else null,
        onLongClickLabel = Texts.COPY,
        onClick = { if (opens) onEvent(PlaceSheetEvent.OpenOutside(action, c.value)) },
        onLongClick = { copy(tag, c.copy) },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).then(m).testTag(tag),
    ) {
        Text(
            c.text, color = if (opens) p.accent else p.panelText, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (copied == tag) Text(
            Texts.COPIED, color = p.subtle, fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Test tags of the place sheet's status line and details part (batch G). */
internal const val PLACE_STATUS_TAG = "place-status"
internal const val PLACE_DETAILS_TAG = "place-details"
internal const val PLACE_REPORT_TAG = "place-report"
internal const val PLACE_EDIT_OSM_TAG = "place-edit-osm"
internal const val PLACE_OWN_TAG = "place-own-tag"

/** Test tag of the place sheet. */
internal const val PLACE_SHEET_TAG = "place-sheet"

// ---------------------------------------------------------------------------------------------
// The saved places screen (2.6)

@Composable
internal fun SavedPlacesContent(ui: SavedListUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier) {
    val night = isNight()
    val p = palette(night)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Surface(color = p.panel, shadowElevation = 2.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                BackButton(night) { onEvent(SavedEvent.Back) }
                Text(Texts.SAVED_PLACES, color = p.panelText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.notice != null) item { NoticeCard(ui.notice, night) { onEvent(SavedEvent.NoticeDismissed) } }
            // a writable full list always has its Home and Work rows (or "Set home" / "Set work"), so no rows
            // means a read-only store: the list then shows only its notice (SP53), no switch
            if (!ui.pickOnly && ui.rows.isNotEmpty()) item {
                // SP5 item 3: the whole row is one switch, labelled by its text, with its on/off state
                // (the Switch inside only draws it, so a screen reader finds one stop, not an unlabelled second one)
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                        .toggleable(value = ui.showOnMap, role = Role.Switch, onValueChange = { onEvent(SavedEvent.ShowOnMapChanged(it)) })
                        .clearAndSetSemantics { contentDescription = Texts.SHOW_ON_MAP }
                        .padding(horizontal = 16.dp)) {
                    Text(Texts.SHOW_ON_MAP, color = p.panelText, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = ui.showOnMap, onCheckedChange = null)
                }
            }
            for (r in ui.rows) item(key = r.savedId ?: "set:${r.kind}") {
                PlaceRow(
                    r.iconName, r.title, r.subtitle, r.distanceText, r.note, night,
                    onClick = { if (r.savedId != null) onEvent(SavedEvent.RowTapped(r.savedId)) else onEvent(SavedEvent.SlotTapped(r.kind)) },
                    editLabel = r.editLabel, onEdit = r.savedId?.let { id -> { onEvent(SavedEvent.EditTapped(id)) } },
                )
            }
            if (ui.hint != null) item { Text(ui.hint, color = p.subtle, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
            if (ui.canAdd) item { PlainRow(Texts.ADD_A_PLACE, night, BeamlineIcons.Add) { onEvent(SavedEvent.AddTapped) } }
            item { Spacer(Modifier.navigationBarsPadding().size(16.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The Edit menu and the dialogs (2.2-2.6)

@Composable
internal fun SavedDialogContent(ui: SavedDialogUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier) {
    val night = isNight()
    val p = palette(night)
    // a tap outside the dialog dismisses it; a plain gesture, not a screen-reader stop (the dialog has "Cancel" and Back).
    // F47 (0.1.40): the scrim stops at the keyboard (the dialog centres in what is left); the body scrolls; the problem
    // line and Cancel / Save stay at the bottom, so landscape, large text and the keyboard never push them out
    Box(modifier.fillMaxSize().background(Color(0x66000000)).pointerInput(Unit) { detectTapGestures { onEvent(SavedEvent.DialogDismiss) } }.imePadding(),
        contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, contentColor = p.panelText, shadowElevation = 8.dp,
            // taps inside the dialog stay inside it
            modifier = Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth().pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column(Modifier.padding(20.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    when (ui) {
                        is SavedDialogUi.Menu -> {
                            DialogTitle(ui.title, p)
                            for (item in ui.items) {
                                Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onEvent(SavedEvent.MenuChosen(item.action)) }
                                    .semantics { role = Role.Button }, contentAlignment = Alignment.CenterStart) {
                                    Text(item.label, fontSize = 16.sp, color = if (item.action == EditAction.DELETE) p.problem else p.panelText)
                                }
                            }
                        }
                        is SavedDialogUi.Save -> {
                            DialogTitle(Texts.SAVE_PLACE, p)
                            Text(ui.placeTitle, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (ui.placeSubtitle != null) Text(ui.placeSubtitle, color = p.subtle, fontSize = 14.sp, maxLines = 2)
                            Spacer(Modifier.size(12.dp))
                            // F47: the chips wrap, so "Other" (a point's default) is never cut off
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (k in ui.kinds) KindChip(k, night) { onEvent(SavedEvent.KindChosen(k.kind)) }
                            }
                            if (ui.name != null) {
                                Spacer(Modifier.size(12.dp))
                                Text(Texts.NAME, color = p.subtle, fontSize = 13.sp)
                                Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, p.divider), color = Color.Transparent) {
                                    // P57: a hint says what goes here; P56: the name given opens selected
                                    PrivateField(ui.name, { onEvent(SavedEvent.NameChanged(it)) }, Texts.NAME_HINT, night, label = Texts.NAME, focus = true,
                                        modifier = Modifier.fillMaxWidth(), selectAll = true)
                                }
                            }
                            if (ui.replaceText != null) Text(ui.replaceText, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                        is SavedDialogUi.Rename -> {
                            DialogTitle(Texts.RENAME, p)
                            Text(Texts.NAME, color = p.subtle, fontSize = 13.sp)
                            Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, p.divider), color = Color.Transparent) {
                                // P56: the old name opens selected, so typing replaces it
                                PrivateField(ui.name, { onEvent(SavedEvent.NameChanged(it)) }, Texts.NAME_HINT, night, label = Texts.NAME, focus = true,
                                    modifier = Modifier.fillMaxWidth(), selectAll = true)
                            }
                        }
                        is SavedDialogUi.Confirm -> {
                            DialogTitle(ui.title, p)
                            if (ui.text != null) Text(ui.text, fontSize = 15.sp)
                        }
                    }
                }
                when (ui) {
                    is SavedDialogUi.Menu -> Buttons(night, null, true, onEvent)
                    is SavedDialogUi.Save -> { Problem(ui.problem, p); Buttons(night, ui.confirmText, ui.canConfirm, onEvent) }
                    is SavedDialogUi.Rename -> { Problem(ui.problem, p); Buttons(night, Texts.SAVE, ui.canConfirm, onEvent) }
                    // P58: Remove's confirm is red, as the menu's Remove
                    is SavedDialogUi.Confirm -> Buttons(night, ui.confirmText, true, onEvent, destructive = ui.destructive)
                }
            }
        }
    }
}

@Composable
private fun DialogTitle(text: String, p: Palette) {
    Text(text, color = p.panelText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
}

@Composable
private fun Problem(text: String?, p: Palette) {
    if (text != null) Text(text, color = p.problem, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun Buttons(night: Boolean, confirm: String?, enabled: Boolean, onEvent: (SavedEvent) -> Unit, destructive: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
        PillButton(Texts.CANCEL, night) { onEvent(SavedEvent.DialogDismiss) }
        if (confirm != null) {
            Spacer(Modifier.size(8.dp))
            PillButton(confirm, night, primary = true, enabled = enabled, destructive = destructive) { onEvent(SavedEvent.DialogConfirm) }
        }
    }
}

@Composable
private fun KindChip(k: KindChoiceUi, night: Boolean, onClick: () -> Unit) {
    val p = palette(night)
    Surface(
        onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (k.selected) p.accent.copy(alpha = 0.16f) else Color.Transparent, contentColor = p.panelText,
        border = BorderStroke(if (k.selected) 2.dp else 1.dp, if (k.selected) p.accent else p.divider),
        modifier = Modifier.heightIn(min = 48.dp).semantics(mergeDescendants = true) { role = Role.RadioButton; selected = k.selected; contentDescription = k.label },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp).clearAndSetSemantics {}) {
            SpriteIcon(Icons.forKind(k.kind), night, 20.dp)
            Spacer(Modifier.size(6.dp))
            Text(k.label, fontSize = 15.sp, softWrap = false)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The map pick (SP16, SP24)

@Composable
internal fun MapPickContent(ui: MapPickUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier) {
    val night = isNight()
    val p = palette(night)
    Box(modifier.fillMaxSize()) {
        Surface(
            shape = RoundedCornerShape(24.dp), color = p.panel, contentColor = p.panelText, shadowElevation = 3.dp,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp).heightIn(min = 48.dp).widthIn(max = 480.dp).fillMaxWidth(),
        ) {
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp)) {
                Text(ui.prompt, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
            }
        }
        Surface(
            shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER), color = p.panel, contentColor = p.panelText, shadowElevation = 6.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag(MAP_PICK_BAR_TAG),
        ) {
            Column(Modifier.navigationBarsPadding().padding(16.dp)) {
                Text(ui.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (ui.subtitle != null) Text(ui.subtitle, color = p.subtle, fontSize = 14.sp)
                if (ui.note != null) Text(ui.note, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Problem(ui.problem, p)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    PillButton(Texts.CANCEL, night) { onEvent(SavedEvent.PickCancel) }
                    Spacer(Modifier.size(8.dp))
                    PillButton(ui.confirmText, night, primary = true, enabled = ui.canConfirm) { onEvent(SavedEvent.PickConfirm) }
                }
            }
        }
    }
}

/** Test tag of the map pick's bar at the bottom (P9). */
internal const val MAP_PICK_BAR_TAG = "map-pick-bar"

/** Kinds as words for a chip's semantics (SP5 item 3). */
@Suppress("unused")
internal fun kindWord(k: SavedKind) = Texts.kindLabel(k)

@Suppress("unused")
private val circle = CircleShape
