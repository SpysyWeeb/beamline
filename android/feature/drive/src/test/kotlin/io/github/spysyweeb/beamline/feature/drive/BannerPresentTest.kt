package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.BannerView
import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame1
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame2
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame3
import io.github.spysyweeb.beamline.feature.drive.Exit24.guiding
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Rule D1 and the three frames of docs/design/turn-banner.html (docs/architecture.md 5.1, D "Done when"). */
class BannerPresentTest {

    private fun bannerOf(live: LiveBanner, units: io.github.spysyweeb.beamline.units.UnitFormatter = Exit24.miles): BannerUi {
        val ui = present(guiding(banner = live), units = units)
        val banner = ui.guidance?.banner
        assertNotNull(banner, "a banner while guiding")
        // the texts below are compared with plain spaces; D1's no-break spaces have their own test
        return banner!!.copy(primary = banner.primary.plain(), roadsText = banner.roadsText?.plain(), towardsText = banner.towardsText?.plain())
    }

    private fun String.plain() = replace(Presentation.NO_BREAK_SPACE, ' ')

    @Test
    fun `D1 short names never break across two lines - no-break spaces inside them, ordinary spaces between them`() {
        val b = present(guiding(banner = frame2), units = Exit24.miles).guidance!!.banner!!
        assertEquals("onto US 19 / Peach St", b.roadsText, "each short name is one unbreakable word; the separator may break")
        assertEquals("Turn left", b.primary)
        val long = "General Strong Vincent Memorial Hwy"
        assertEquals(long, Presentation.keepTogether(long), "a long name may still wrap between its words")
        assertEquals("West 26th Street", Presentation.keepTogether("West 26th Street"), "16 characters: kept together")
        assertEquals("toward Waterford", present(guiding(banner = frame1), units = Exit24.miles).guidance!!.banner!!.towardsText)
    }

    private fun marks(b: BannerUi) = b.lanes!!.map { it.mark }

    @Test
    fun `frame 1 on I-90 - Exit 24, US 19 - Peach St, toward Waterford, 0_5 mi, then In 0_3 mi, no lanes`() {
        val b = bannerOf(frame1)
        assertEquals("Exit 24", b.primary)
        assertEquals("US 19 / Peach St", b.roadsText)
        assertEquals("toward Waterford", b.towardsText)
        assertEquals("0.5 mi", b.distanceText)
        assertEquals(ThenUi(ManeuverKind.LEFT, "In 0.3 mi"), b.then)
        assertNull(b.lanes)
        assertEquals(ManeuverKind.FORK_RIGHT, b.kind)
        assertNull(b.roundaboutExit)
    }

    @Test
    fun `frame 2 on the ramp - Turn left onto US 19 - Peach St, 400 ft, then In 0_2 mi, allowed best not`() {
        val b = bannerOf(frame2)
        assertEquals("Turn left", b.primary)
        assertEquals("onto US 19 / Peach St", b.roadsText)
        assertNull(b.towardsText)
        assertEquals("400 ft", b.distanceText)
        assertEquals(ThenUi(ManeuverKind.RIGHT, "In 0.2 mi"), b.then)
        assertEquals(listOf(LaneMark.ALLOWED, LaneMark.BEST, LaneMark.NOT_FOR_ROUTE), marks(b))
        assertEquals(
            listOf(
                LaneUi(setOf(LaneArrow.LEFT), LaneMark.ALLOWED, LaneArrow.LEFT),
                LaneUi(setOf(LaneArrow.LEFT, LaneArrow.THROUGH), LaneMark.BEST, LaneArrow.LEFT),
                LaneUi(setOf(LaneArrow.RIGHT), LaneMark.NOT_FOR_ROUTE, null),
            ),
            b.lanes,
        )
    }

    @Test
    fun `frame 3 on Peach St - Turn right onto Downs Dr, 0_2 mi, no chip, not not not best`() {
        val b = bannerOf(frame3)
        assertEquals("Turn right", b.primary)
        assertEquals("onto Downs Dr", b.roadsText)
        assertNull(b.towardsText)
        assertEquals("0.2 mi", b.distanceText)
        assertNull(b.then)
        assertEquals(listOf(LaneMark.NOT_FOR_ROUTE, LaneMark.NOT_FOR_ROUTE, LaneMark.NOT_FOR_ROUTE, LaneMark.BEST), marks(b))
        assertEquals(LaneUi(setOf(LaneArrow.THROUGH, LaneArrow.RIGHT), LaneMark.BEST, LaneArrow.RIGHT), b.lanes!!.last())
    }

    @Test
    fun `metric units - banner, chip and trip bar in metres and kilometres`() {
        val ui = present(guiding(banner = frame1), units = Exit24.km)
        val b = ui.guidance!!.banner!!
        assertEquals("800 m", b.distanceText)
        assertEquals("In 400 m", b.then!!.distanceText)
        assertEquals("2.0 km", ui.guidance.tripBar.remainingDistanceText)
        val b2 = bannerOf(frame2, Exit24.km)
        assertEquals("100 m", b2.distanceText)
        assertEquals("In 350 m", b2.then!!.distanceText)
        assertEquals("300 m", bannerOf(frame3, Exit24.km).distanceText)
        // the texts that are not distances do not change
        assertEquals("Exit 24", b.primary.plain())
        assertEquals("US 19 / Peach St", b.roadsText?.plain())
    }

    @Test
    fun `the lane row only when the session shows it (B5)`() {
        assertNull(bannerOf(frame2.copy(showLanes = false)).lanes)
        assertNull(bannerOf(frame3.copy(showLanes = false)).lanes)
        // showLanes with no lane data, or an empty list: no row, nothing guessed
        assertNull(bannerOf(frame1.copy(showLanes = true)).lanes)
        assertNull(bannerOf(LiveBanner(Exit24.rightView.copy(lanes = emptyList()), 300.0, true)).lanes)
    }

    @Test
    fun `the then chip does not count down, the main distance does`() {
        val far = bannerOf(frame2.copy(distanceM = 300.0))
        val near = bannerOf(frame2.copy(distanceM = 15.0))
        assertEquals("0.2 mi", far.distanceText)
        assertEquals("50 ft", near.distanceText)
        assertEquals("In 0.2 mi", far.then!!.distanceText)
        assertEquals("In 0.2 mi", near.then!!.distanceText)
    }

    @Test
    fun `onto for turns, ramps, merges and roundabouts, not for exits, forks, depart, arrive, ferry`() {
        fun roads(kind: ManeuverKind, roads: List<String> = listOf("I 90")) =
            Presentation.roadsText(kind, roads)
        for (k in listOf(
            ManeuverKind.STRAIGHT, ManeuverKind.SLIGHT_LEFT, ManeuverKind.LEFT, ManeuverKind.SHARP_LEFT,
            ManeuverKind.SLIGHT_RIGHT, ManeuverKind.RIGHT, ManeuverKind.SHARP_RIGHT, ManeuverKind.UTURN_LEFT,
            ManeuverKind.UTURN_RIGHT, ManeuverKind.RAMP_LEFT, ManeuverKind.RAMP_RIGHT, ManeuverKind.RAMP_STRAIGHT,
            ManeuverKind.MERGE, ManeuverKind.MERGE_LEFT, ManeuverKind.MERGE_RIGHT, ManeuverKind.ROUNDABOUT,
            ManeuverKind.ROUNDABOUT_EXIT,
        )) assertEquals("onto I 90", roads(k), k.name)
        for (k in listOf(
            ManeuverKind.EXIT_LEFT, ManeuverKind.EXIT_RIGHT, ManeuverKind.FORK_LEFT, ManeuverKind.FORK_RIGHT,
            ManeuverKind.FORK_STRAIGHT, ManeuverKind.DEPART, ManeuverKind.ARRIVE, ManeuverKind.ARRIVE_LEFT,
            ManeuverKind.ARRIVE_RIGHT, ManeuverKind.FERRY, ManeuverKind.OTHER,
        )) assertEquals("I 90", roads(k), k.name)
        assertNull(roads(ManeuverKind.LEFT, emptyList()))
        assertNull(roads(ManeuverKind.LEFT, listOf(" ")))
        // at most two names: the left turn of the exit 24 trip has three
        assertEquals("onto US 19 / Peach St", roads(ManeuverKind.LEFT, Exit24.leftView.roads))
        assertEquals("I 79 North / US 19", roads(ManeuverKind.EXIT_RIGHT, listOf("I 79 North", "US 19", "PA 97")))
    }

    @Test
    fun `towards joined with commas, none when empty`() {
        assertEquals("toward Waterford", Presentation.towardsText(listOf("Waterford")))
        assertEquals("toward Erie, Pittsburgh", Presentation.towardsText(listOf("Erie", "Pittsburgh")))
        assertNull(Presentation.towardsText(emptyList()))
    }

    @Test
    fun `a roundabout keeps its exit number and kind`() {
        val view = BannerView(
            maneuverIndex = 5, kind = ManeuverKind.ROUNDABOUT, primary = "Roundabout, 2nd exit", roads = listOf("Park Line Dr"),
            towards = emptyList(), exitNumber = null, roundaboutExit = 2, approachOnHighway = false, following = null, lanes = null,
        )
        val b = bannerOf(LiveBanner(view, 150.0, false))
        assertEquals(2, b.roundaboutExit)
        assertEquals(ManeuverKind.ROUNDABOUT, b.kind)
        assertEquals("Roundabout, 2nd exit", b.primary)
        assertEquals("onto Park Line Dr", b.roadsText)
        assertEquals("500 ft", b.distanceText)
    }

    @Test
    fun `the arrival is a normal banner with a counting distance, no chip, no lanes (B7)`() {
        val b = bannerOf(LiveBanner(Exit24.arriveView, 321.8688, false))
        assertEquals("Arrive, on the right", b.primary)
        assertEquals("0.2 mi", b.distanceText)
        assertEquals(ManeuverKind.ARRIVE_RIGHT, b.kind)
        assertNull(b.roadsText)
        assertNull(b.then)
        assertNull(b.lanes)
    }

    @Test
    fun `no banner before the first progress`() {
        val ui = present(guiding(banner = null))
        assertNull(ui.guidance!!.banner)
        assertEquals(Phase.GUIDING, guiding(banner = null).phase)
    }
}
