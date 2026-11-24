package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.spysyweeb.beamline.api.nav.LaneMark

/**
 * The banner's colours. By day they are the sketch's (docs/design/turn-banner.html: "App specimen
 * colours are fixed: they depict the app's own banner"); the night set is this track's draft, a
 * shade darker with the night route blue for the best lane.
 */
@Immutable
internal data class BannerPalette(
    val background: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val lanePreferred: Color,
    val laneAllowed: Color,
    val laneOff: Color,
) {
    companion object {
        val Day = BannerPalette(
            background = Color(0xFF16212C), ink = Color(0xFFFFFFFF), muted = Color(0xFFA9B8C7), line = Color(0xFF2B3A49),
            lanePreferred = Color(0xFF3D8CF0), laneAllowed = Color(0xFF8FA2B6), laneOff = Color(0xFF56667A),
        )
        val Night = BannerPalette(
            background = Color(0xFF0F171F), ink = Color(0xFFE7EDF3), muted = Color(0xFF97A5B4), line = Color(0xFF243241),
            lanePreferred = Color(0xFF3A80F0), laneAllowed = Color(0xFF7D90A4), laneOff = Color(0xFF4B5B6D),
        )
    }
}

/** The screens follow the map (ui/theme): a dark Material surface means the night map. */
@Composable
internal fun isNightTheme(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** The distance's narrow face: the system's condensed sans (the sketch uses Barlow Condensed, which the app does not ship). */
internal val CondensedFamily: FontFamily = FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))

/**
 * The turn banner of the approved sketch: maneuver arrow, distance, instruction with the roads and
 * the sign's towards, the "Then" chip beside it, and the lane row (three lane states, no hint text).
 */
@Composable
internal fun TurnBanner(banner: BannerUi, modifier: Modifier = Modifier, palette: BannerPalette = if (isNightTheme()) BannerPalette.Night else BannerPalette.Day) {
    Column(
        modifier
            .fillMaxWidth()
            .background(palette.background)
            .testTag(DriveTags.BANNER),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = DriveIcons.maneuver(banner.kind),
                contentDescription = null,
                tint = palette.ink,
                modifier = Modifier.size(54.dp).testTag(DriveTags.BANNER_ICON),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = banner.distanceText,
                    color = palette.ink,
                    fontFamily = CondensedFamily,
                    fontSize = 32.sp,
                    lineHeight = 32.sp,
                    letterSpacing = 0.01.em,
                    modifier = Modifier.testTag(DriveTags.BANNER_DISTANCE),
                )
                val second = listOfNotNull(banner.roadsText, banner.towardsText).joinToString(", ")
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(banner.primary) }
                        if (second.isNotEmpty()) {
                            append(" ")
                            withStyle(SpanStyle(fontWeight = FontWeight.Normal, color = palette.muted)) { append(second) }
                        }
                    },
                    color = palette.ink,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp).testTag(DriveTags.BANNER_TEXT),
                )
            }
            banner.then?.let { ThenChip(it, palette) }
        }
        val lanes = banner.lanes
        if (lanes != null) {
            HorizontalDivider(thickness = 1.dp, color = palette.line)
            // One node for a screen reader (the description); the lane boxes stay in the unmerged tree for the tests.
            BoxWithConstraints(
                Modifier
                    .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp)
                    .testTag(DriveTags.LANES)
                    .semantics(mergeDescendants = true) { contentDescription = DriveTexts.lanesDescription(lanes) },
            ) {
                val metrics = laneMetrics(maxWidth.value, lanes.size, pxPerDp = LocalDensity.current.density)
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.gapDp.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (lane in lanes) LaneBox(lane, palette, metrics)
                }
            }
            // rule B9: a lane that ends in a turn, one line under its lane row
            banner.laneNote?.let { note ->
                Text(
                    text = note,
                    color = palette.ink,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag(DriveTags.LANE_NOTE),
                )
            }
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** The following turn: the small "THEN" label, its arrow, and the distance between the two turns (fixed, B4). */
@Composable
internal fun ThenChip(then: ThenUi, palette: BannerPalette) {
    Column(
        Modifier
            .border(1.dp, palette.line, RoundedCornerShape(12.dp))
            .padding(start = 9.dp, top = 6.dp, end = 9.dp, bottom = 7.dp)
            .testTag(DriveTags.THEN_CHIP)
            .clearAndSetSemantics { contentDescription = DriveTexts.thenDescription(then) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            DriveTexts.THEN.uppercase(),
            color = palette.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.08.em,
            lineHeight = 13.sp,
        )
        Icon(DriveIcons.maneuverSmall(then.kind), contentDescription = null, tint = palette.ink, modifier = Modifier.size(28.dp))
        Text(then.distanceText, color = palette.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, maxLines = 1)
    }
}

/** One lane: best (filled blue), also allowed (outlined), not for this route (dim arrow), as the sketch. */
@Composable
internal fun LaneBox(lane: LaneUi, palette: BannerPalette, metrics: LaneMetrics = SKETCH_LANE) {
    val shape = RoundedCornerShape(9.dp)
    val (fill, edge, ink) = when (lane.mark) {
        LaneMark.BEST -> Triple(palette.lanePreferred, palette.lanePreferred, palette.ink)
        LaneMark.ALLOWED -> Triple(Color.Transparent, palette.laneAllowed, palette.ink)
        LaneMark.NOT_FOR_ROUTE -> Triple(Color.Transparent, Color.Transparent, palette.laneOff)
    }
    Box(
        Modifier
            .size(width = metrics.widthDp.dp, height = 44.dp)
            .background(fill, shape)
            .border(1.5.dp, edge, shape)
            .testTag(DriveTags.lane(lane.mark)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(DriveIcons.lane(lane.arrows), contentDescription = null, tint = ink, modifier = Modifier.size(metrics.iconDp.dp))
    }
}
