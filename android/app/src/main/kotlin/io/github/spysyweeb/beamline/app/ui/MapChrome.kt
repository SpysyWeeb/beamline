package io.github.spysyweeb.beamline.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spysyweeb.beamline.api.data.PackIssue
import io.github.spysyweeb.beamline.feature.states.MapChip

/** The map's panels and round buttons, as the app shell had them (SP2: the pill uses the same panel). */
internal fun panel(night: Boolean) = if (night) Color(0xF215191E) else Color(0xF2FFFFFF)
internal fun panelText(night: Boolean) = if (night) Color(0xFFD0D4DA) else Color(0xFF333333)

@Composable
internal fun RoundButton(
    icon: ImageVector,
    label: String,
    night: Boolean,
    large: Boolean = false,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = panel(night),
        contentColor = tint ?: panelText(night),
        shadowElevation = 3.dp,
        modifier = Modifier.size(if (large) 56.dp else 46.dp).semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(if (large) 28.dp else 24.dp))
        }
    }
}

/**
 * K1a (the owner, 2026-11-24): the compass above my-location. Its needle points to north on the
 * screen whatever the map's bearing ([bearing]: the map's, frame by frame); a tap turns the map north
 * up, the next lets it follow the phone's compass ([following]: then ringed in the route's blue).
 */
@Composable
internal fun CompassButton(bearing: kotlinx.coroutines.flow.StateFlow<Float>, following: Boolean, night: Boolean, onClick: () -> Unit) {
    val deg by bearing.collectAsState()
    val label = if (following) "Compass, following your direction. Tap for north up"
    else "Compass. Tap to turn the map north up, tap again to follow your direction"
    val blue = if (night) Color(0xFF3A80F0) else Color(0xFF4A8CF7)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = panel(night),
        shadowElevation = 3.dp,
        border = if (following) androidx.compose.foundation.BorderStroke(2.dp, blue) else null,
        modifier = Modifier.size(46.dp).semantics { contentDescription = label },
    ) {
        androidx.compose.foundation.Canvas(Modifier.padding(9.dp).size(28.dp)) {
            val turn = -deg
            rotate(turn) {
                val cx = size.width / 2
                val cy = size.height / 2
                val w = size.width * 0.2f
                val north = androidx.compose.ui.graphics.Path().apply { moveTo(cx, 0f); lineTo(cx + w, cy); lineTo(cx - w, cy); close() }
                val south = androidx.compose.ui.graphics.Path().apply { moveTo(cx, size.height); lineTo(cx + w, cy); lineTo(cx - w, cy); close() }
                drawPath(north, Color(0xFFE53935))
                drawPath(south, if (night) Color(0xFFAAB4C2) else Color(0xFF7D8796))
            }
        }
    }
}

/**
 * The map's credit (docs/architecture.md 5.4 item 4, docs/next-round.md item 8): one short line
 * ([text], "© OpenStreetMap") in the corner while [shown]; it fades out, and then, while [mark], a
 * small info mark stays in its place. A tap on either opens the full list of sources ([onOpen]).
 * Nothing at all when there is neither (while guiding after the first seconds).
 */
@Composable
internal fun Credit(text: String?, shown: Boolean, mark: Boolean, night: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.BottomStart) {
        AnimatedVisibility(visible = shown && text != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(600))) {
            Surface(onClick = onOpen, shape = RoundedCornerShape(4.dp), color = panel(night), contentColor = panelText(night),
                modifier = Modifier.semantics { contentDescription = "Map data sources" }) {
                Text(text = text.orEmpty(), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
        AnimatedVisibility(visible = mark && !(shown && text != null), enter = fadeIn(tween(600)), exit = fadeOut(tween(200))) {
            // A 24 dp mark in a 48 dp touch area: findable by whoever looks for the licence, small on the map.
            Box(Modifier.size(48.dp).semantics { contentDescription = "Map data sources" }.clickable(onClick = onOpen), contentAlignment = Alignment.BottomStart) {
                // At night the dark panel alone vanished on the near-black map (part 1's review): a light ring.
                Surface(shape = CircleShape, color = panel(night), contentColor = panelText(night),
                    border = if (night) BorderStroke(1.5.dp, panelText(true)) else null,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp).size(24.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("i", fontSize = 14.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    }
                }
            }
        }
    }
}

/** The full list of map sources, from the credit's tap (the same list as About's "Map data"). */
@Composable
internal fun MapDataDialog(credits: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                credits.forEach { Text(it, fontSize = 14.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** G's chip (1.5): "Choose your state", a download's progress; a tap opens the States screen. */
@Composable
internal fun ChipButton(chip: MapChip, night: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = panel(night),
        contentColor = panelText(night),
        shadowElevation = 3.dp,
        modifier = Modifier.semantics { contentDescription = chip.description },
    ) {
        Text(chip.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

/** Installed folders that are not on the map: say so instead of leaving them out silently. */
@Composable
internal fun ProblemNotice(issues: List<PackIssue>, night: Boolean) {
    if (issues.isEmpty()) return
    Surface(shape = RoundedCornerShape(4.dp), color = panel(night), contentColor = panelText(night)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            issues.take(3).forEach { Text("Not used: ${it.folder}: ${it.reason}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (issues.size > 3) Text("and ${issues.size - 3} more", fontSize = 12.sp)
        }
    }
}

/** A line about location (refused, off) with OK, and "App settings" when Android will not ask again. */
@Composable
internal fun MessageCard(text: String, night: Boolean, action: Pair<String, () -> Unit>?, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = panel(night), contentColor = panelText(night), shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(text, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                action?.let { (label, go) -> TextButton(onClick = go) { Text(label) } }
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

/** A short line at the bottom ("Pennsylvania is ready."), with one action when [action] is given ("Trip ended" with Undo). */
@Composable
internal fun Snackbar(text: String, night: Boolean, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Surface(shape = RoundedCornerShape(8.dp), color = if (night) Color(0xFFE6E8EB) else Color(0xFF2B2F33),
        contentColor = if (night) Color(0xFF1B1E22) else Color.White, shadowElevation = 6.dp, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            action?.let { (label, go) ->
                TextButton(onClick = go, modifier = Modifier.padding(end = 4.dp)) {
                    Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        color = if (night) Color(0xFF1F5FBF) else Color(0xFF8AB4F8))
                }
            }
        }
    }
}

@Composable
internal fun Gap(dp: Int) = Spacer(Modifier.size(dp.dp))
