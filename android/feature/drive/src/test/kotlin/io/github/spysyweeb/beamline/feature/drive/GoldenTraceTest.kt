package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.BannerView
import io.github.spysyweeb.beamline.api.nav.FollowingView
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * N's golden trace of the simulated exit 24 drive (android/routing/testdata/nav/exit24.json, board
 * note n-golden-trace.md: per fix the phase, the upcoming maneuver, the held distance, the chip, the
 * lane flag, gps) through DrivePresenter, fix by fix. From the trace: the primary (checked against the
 * fixture's), the shown distance, the chip's maneuver name (checked against the fixture's following
 * maneuver) and distance, the lane flag, phase, gps and remaining distance. NOT from the trace, which
 * does not carry them: the maneuver kinds, road names, towards and lane arrows and marks; those are the
 * fixtures' (Exit24, the same as N's BannerModelTest asserts for this route), keyed by the trace's
 * maneuver. So this test does not prove that N's real banners reach the right texts; an end-to-end test
 * with the real session is the integrator's (report 15, "Review and fixes").
 */
class GoldenTraceTest {

    private class Row(
        val tMs: Long, val phase: Phase, val u: Int, val primary: String, val shownM: Double,
        val chipPrimary: String?, val chipM: Double?, val lanesShown: Boolean, val gps: GpsState, val remainingM: Double,
    )

    private val rows: List<Row> by lazy {
        val root = File(System.getProperty("beamline.repoRoot") ?: error("beamline.repoRoot not set"))
        val json = Json.parseToJsonElement(File(root, "android/routing/testdata/nav/exit24.json").readText()).jsonObject
        assertEquals("beamline-golden-trace/1", json["format"]!!.jsonPrimitive.content)
        json["rows"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val chip = o["chip"]?.takeUnless { it is JsonNull }?.jsonObject
            Row(
                tMs = o.num("t_ms").toLong(),
                phase = Phase.valueOf(o.str("phase")),
                u = o["u"]!!.jsonPrimitive.int,
                primary = o.str("primary"),
                shownM = o.num("shown_m"),
                chipPrimary = chip?.str("primary"),
                chipM = chip?.num("distance_m"),
                lanesShown = o["lanes_shown"]!!.jsonPrimitive.boolean,
                gps = GpsState.valueOf(o.str("gps")),
                remainingM = o.num("remaining_m"),
            )
        }
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.num(k: String) = this[k]!!.jsonPrimitive.double

    /** The fixtures' banner for maneuver [u], with the trace's chip distance. */
    private fun view(row: Row): BannerView {
        val base = when (row.u) {
            1 -> Exit24.exitView
            2 -> Exit24.leftView.copy(primary = row.primary)   // item 22: the session adds "At the light" where the turn is at one
            3 -> Exit24.rightView
            else -> Exit24.arriveView.copy(maneuverIndex = row.u, kind = ManeuverKind.ARRIVE, primary = "Arrive")
        }
        val following = base.following?.let { f -> FollowingView(f.kind, f.primary, row.chipM ?: f.distanceM, f.timeS) }
        return base.copy(following = following)
    }

    private fun ui(row: Row, units: UnitFormatter = Exit24.miles): DriveUi =
        Exit24.present(
            Exit24.guiding(
                banner = LiveBanner(view(row), row.shownM, row.lanesShown),
                phase = row.phase,
                gps = row.gps,
                remainingM = row.remainingM,
                remainingS = row.remainingM / 13.0,
            ),
            units = units,
        )

    /** "0.3 mi" -> 482.8 m. */
    private fun metres(text: String): Double {
        val (n, unit) = text.split(" ")
        return n.toDouble() * mapOf("ft" to 0.3048, "mi" to 1609.344, "m" to 1.0, "km" to 1000.0).getValue(unit)
    }

    @Test
    fun `the trace is the one the board note describes`() {
        assertEquals(289, rows.size)
        assertEquals(listOf("Exit 24", "At the light, turn left", "Turn right", "Arrive"), rows.map { it.primary }.distinct())
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.u }.distinct())
        assertEquals(Phase.ARRIVED, rows.last().phase)
    }

    @Test
    fun `every fix - the banner, chip, lanes and status as the trace says`() {
        for (row in rows) {
            val g = ui(row).guidance!!
            if (row.phase == Phase.ARRIVED) {
                assertNull(g.banner, "t=${row.tMs}")
                assertEquals(GuidanceStatusUi.Arrived("Downs Drive", null), g.status, "t=${row.tMs}")
                continue
            }
            val b = g.banner!!
            assertEquals(row.primary, b.primary.replace(Presentation.NO_BREAK_SPACE, ' '), "t=${row.tMs}")   // D1 keeps it on one line
            // the fixture's following maneuver is the one the trace's chip names
            assertEquals(row.chipPrimary, view(row).following?.primary, "t=${row.tMs}: the chip's maneuver")
            assertEquals(Exit24.miles.distance(row.shownM), b.distanceText, "t=${row.tMs}")
            val chip = when (row.u) {
                1 -> ThenUi(ManeuverKind.LEFT, "In 0.3 mi")
                2 -> ThenUi(ManeuverKind.RIGHT, "In 0.2 mi")
                else -> null
            }
            assertEquals(chip, b.then, "t=${row.tMs}: the chip does not count down and goes when U advances")
            assertEquals(row.chipPrimary != null, b.then != null, "t=${row.tMs}")
            assertEquals(row.lanesShown && view(row).lanes != null, b.lanes != null, "t=${row.tMs}")
            assertEquals(if (row.gps == GpsState.GOOD) GuidanceStatusUi.OnRoute else Presentation.status(Exit24.guiding(gps = row.gps), null), g.status)
        }
    }

    @Test
    fun `the shown distance text never goes up for one maneuver, the remaining distance never at all`() {
        for (units in listOf(Exit24.miles, Exit24.km)) {
            var lastU = -1
            var lastBanner = Double.MAX_VALUE
            var lastRemaining = Double.MAX_VALUE
            for (row in rows.filter { it.phase == Phase.GUIDING }) {
                val g = ui(row, units).guidance!!
                val shown = metres(g.banner!!.distanceText)
                if (row.u != lastU) {
                    lastU = row.u
                    lastBanner = Double.MAX_VALUE
                }
                assertTrue(shown <= lastBanner + 1e-9, "${units.system} t=${row.tMs}: ${g.banner.distanceText} after a smaller text")
                lastBanner = shown
                val remaining = metres(g.tripBar.remainingDistanceText)
                assertTrue(remaining <= lastRemaining + 1e-9, "${units.system} t=${row.tMs}: ${g.tripBar.remainingDistanceText}")
                lastRemaining = remaining
            }
        }
    }

    @Test
    fun `the drive passes through the sketch's three frames and section 8's first row`() {
        val guided = rows.filter { it.phase == Phase.GUIDING }
        fun texts(u: Int) = guided.filter { it.u == u }.map { ui(it).guidance!!.banner!! }
        val frame1 = texts(1).first { it.distanceText == "0.5 mi" }
        assertNull(frame1.lanes)
        assertEquals("In 0.3 mi", frame1.then!!.distanceText)
        val frame2 = texts(2).first { it.distanceText == "400 ft" }
        assertEquals(listOf(LaneMark.ALLOWED, LaneMark.BEST, LaneMark.NOT_FOR_ROUTE), frame2.lanes!!.map { it.mark })
        assertEquals("In 0.2 mi", frame2.then!!.distanceText)
        val frame3 = texts(3).first { it.distanceText == "0.2 mi" }
        assertEquals(4, frame3.lanes!!.size)
        assertNull(frame3.then)
        // row 1 of section 8: "2.6 mi", trip bar "3.3 mi"; the notification "Exit 24 · 2.6 mi"
        val start = ui(guided.first()).guidance!!
        assertEquals("2.6 mi", start.banner!!.distanceText)
        assertEquals("3.3 mi", start.tripBar.remainingDistanceText)
        val first = guided.first()
        val notification = DrivePresenter.notification(
            Exit24.guiding(banner = LiveBanner(view(first), first.shownM, first.lanesShown), remainingM = first.remainingM),
            Exit24.miles,
        )
        assertEquals(GuidanceNotificationText("Exit 24 · 2.6 mi", "US 19 / Peach St"), notification)
    }
}
