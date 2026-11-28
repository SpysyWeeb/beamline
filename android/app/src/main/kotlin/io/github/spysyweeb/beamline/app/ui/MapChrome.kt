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
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
 * K1/K1a (the owner, 2026-11-24; option A of the options sheet): my-location, which also holds the
 * compass. Not following: a bold crosshair; following: the crosshair in the route's blue with a dot;
 * following turned by the phone's compass ([compass]): a needle pointing to north on the screen in a
 * blue ring. In the first two a small red mark on the rim always points to north ([bearing]: the
 * map's, frame by frame). At night a lighter panel with a thin outline, so it stands off the dark map.
 */
@Composable
internal fun LocationButton(following: Boolean, compass: Boolean, bearing: kotlinx.coroutines.flow.StateFlow<Float>, night: Boolean, onClick: () -> Unit) {
    val deg by bearing.collectAsState()
    val label = when {
        compass -> "Following your position, turned by your phone's compass. Tap for north up"
        following -> "Following your position. Tap to turn the map with your phone's compass"
        // K1a (0.1.30): a map turned by hand turns north up first, where it is
        ((deg % 360f) + 360f) % 360f in 0.5f..359.5f -> "Turn the map north up. Tap again for your position"
        else -> "Show my position"
    }
    val blue = if (night) Color(0xFF3A80F0) else Color(0xFF4A8CF7)
    val red = Color(0xFFE53935)
    val ink = if (night) Color(0xFFE4E8EE) else Color(0xFF2E3746)
    val needleTail = if (night) Color(0xFFAAB4C2) else Color(0xFF7D8796)
    val fill = if (night) Color(0xFF2B3340) else Color(0xFFFFFFFF)
    val border = when {
        compass -> androidx.compose.foundation.BorderStroke(2.2.dp, blue)
        night -> androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF46505F))
        else -> null
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = fill,
        shadowElevation = 3.dp,
        border = border,
        modifier = Modifier.size(56.dp).semantics { contentDescription = label },
    ) {
        // drawn on the options sheet's 56-unit grid: one unit is one dp
        androidx.compose.foundation.Canvas(Modifier.size(56.dp)) {
            val u = size.width / 56f
            val c = androidx.compose.ui.geometry.Offset(28f * u, 28f * u)
            if (compass) {
                rotate(-deg) {
                    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(28f * u, 15f * u); lineTo(31.4f * u, 28f * u); lineTo(24.6f * u, 28f * u); close() }, red)
                    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(28f * u, 41f * u); lineTo(31.4f * u, 28f * u); lineTo(24.6f * u, 28f * u); close() }, needleTail)
                }
            } else {
                val color = if (following) blue else ink
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.6f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(color, radius = 7.5f * u, center = c, style = stroke)
                val ticks = listOf(
                    15.5f to 20f, 36f to 40.5f,
                )
                for ((a, b) in ticks) {
                    drawLine(color, androidx.compose.ui.geometry.Offset(28f * u, a * u), androidx.compose.ui.geometry.Offset(28f * u, b * u), 2.6f * u, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(color, androidx.compose.ui.geometry.Offset(a * u, 28f * u), androidx.compose.ui.geometry.Offset(b * u, 28f * u), 2.6f * u, androidx.compose.ui.graphics.StrokeCap.Round)
                }
                if (following) drawCircle(blue, radius = 3.6f * u, center = c)
                rotate(-deg) {
                    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(28f * u, 2.5f * u); lineTo(32.2f * u, 10f * u); lineTo(23.8f * u, 10f * u); close() }, red)
                }
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
        // play test P28, P10: TalkBack says each bar when it comes ("Press Back again ...", "Stop removed")
        contentColor = if (night) Color(0xFF1B1E22) else Color.White, shadowElevation = 6.dp, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // P58 (review 0.1.40): the line takes the room the action leaves, so a long "{name} removed" wraps beside a whole Undo
            Text(text, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false).padding(horizontal = 16.dp, vertical = 12.dp))
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
