package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Rule D8's colours: the sign is black on white by day and by night (MUTCD R2-1); the badge as the banner. */
internal object SpeedColours {
    val SignFace = Color(0xFFFFFFFF)
    val SignInk = Color(0xFF000000)
    val Yellow = Color(0xFFFFD54F)
    val Red = Color(0xFFFF6B6B)
}

/**
 * Rule D8: the US speed-limit sign (MUTCD R2-1: "SPEED LIMIT" over the number, black on white, a
 * black border) when [SpeedUi.limit] is known, and beside it a round badge with the current speed,
 * its number white, yellow or red ([SpeedOver]); the sign never changes colour.
 */
@Composable
internal fun SpeedCluster(speed: SpeedUi, palette: BannerPalette, modifier: Modifier = Modifier, onReport: () -> Unit = {}) {
    // item 32: with [SpeedUi.canReport] the pair is one button ("Report the speed limit"); its two readings stay as they are read
    val tap = if (speed.canReport) Modifier.clickable(onClickLabel = DriveTexts.REPORT_SPEED_LIMIT, role = Role.Button, onClick = onReport) else Modifier
    // the button is the pair itself, inside the room [modifier] keeps around it
    Box(modifier) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).then(tap).testTag(DriveTags.SPEED_PAIR), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            speed.limit?.let { SpeedLimitSign(it, speed.unit) }
            SpeedBadge(speed, palette)
        }
    }
}

@Composable
private fun SpeedLimitSign(limit: String, unit: String) {
    val label = DriveTexts.speedLimitLabel(limit, unit)
    Box(
        Modifier
            .testTag(DriveTags.SPEED_SIGN)
            .clearAndSetSemantics { contentDescription = label }
            .shadow(4.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(SpeedColours.SignFace)
            .padding(3.dp)
            .border(2.dp, SpeedColours.SignInk, RoundedCornerShape(4.dp))
            .width(52.dp)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(DriveTexts.SPEED, color = SpeedColours.SignInk, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold)
            Text(DriveTexts.LIMIT, color = SpeedColours.SignInk, fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold)
            Text(limit, color = SpeedColours.SignInk, fontSize = 26.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
            if (unit == DriveTexts.KMH) Text(unit, color = SpeedColours.SignInk, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Item 22 (docs/next-round.md): the road being driven, one line in the banner's colours ("Peach St"); a
 * long name ends in an ellipsis. A screen reader says "On Peach St".
 */
@Composable
internal fun CurrentRoadLabel(road: String, palette: BannerPalette, modifier: Modifier = Modifier) {
    Box(
        modifier
            .testTag(DriveTags.CURRENT_ROAD)
            .clearAndSetSemantics { contentDescription = DriveTexts.onRoad(road) }
            .shadow(4.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(palette.background)
            .border(1.dp, palette.line, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(road, color = palette.ink, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable
private fun SpeedBadge(speed: SpeedUi, palette: BannerPalette) {
    val label = DriveTexts.speedLabel(speed.speed, speed.unit)
    val ink = when (speed.over) {
        SpeedOver.NO -> palette.ink
        SpeedOver.YELLOW -> SpeedColours.Yellow
        SpeedOver.RED -> SpeedColours.Red
    }
    Box(
        Modifier
            .testTag(DriveTags.SPEED_BADGE)
            .clearAndSetSemantics { contentDescription = label }
            .shadow(4.dp, CircleShape)
            .size(60.dp)
            .clip(CircleShape)
            .background(palette.background)
            .border(2.dp, palette.line, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(speed.speed, color = ink, fontSize = 24.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
            Text(speed.unit, color = palette.muted, fontSize = 10.sp, lineHeight = 11.sp)
        }
    }
}
