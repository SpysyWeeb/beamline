package io.github.spysyweeb.beamline.routing.banner

import io.github.spysyweeb.beamline.routing.banner.LaneMark.ALLOWED
import io.github.spysyweeb.beamline.routing.banner.LaneMark.BEST
import io.github.spysyweeb.beamline.routing.banner.LaneMark.NOT_FOR_ROUTE
import io.github.spysyweeb.beamline.routing.model.Route
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The banner model on real routes of the Pennsylvania graph (banner_cases.json, recorded with
 * pyvalhalla 3.9.1 on the PA+OH device tiles by android/routing/scripts/reference_routes.py).
 */
class BannerModelTest {
    private val rules = BannerRules.bundled()

    private val routes: Map<String, Route> by lazy {
        val text = GZIPInputStream(javaClass.getResourceAsStream("/reference/banner-routes.json.gz")!!).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        Json.parseToJsonElement(text).jsonObject["recordings"]!!.jsonArray.associate { r ->
            r.jsonObject["case"]!!.jsonPrimitive.content to ValhallaResponses.parseRoute(r.jsonObject["response"].toString()).main
        }
    }

    private fun banners(case: String) = BannerModel.build(routes.getValue(case), rules)

    private fun marks(b: Banner) = b.lanes?.map { it.mark }

    /**
     * The chip's text as the drive screen makes it (UnitFormatter.distanceIn with units.json's
     * imperial table, docs/architecture.md 2.9). A copy of that table for these tests only:
     * :routing:banner makes no distance text and does not depend on :core:units.
     */
    private fun chip(f: FollowingTurn) = "In " + imperial(f.distanceM)

    private fun imperial(m: Double): String = when {
        m < 30.48 -> "${maxOf(Math.round(m / 0.3048 / 10) * 10, 10)} ft"
        m < 152.4 -> "${maxOf(Math.round(m / 0.3048 / 50) * 50, 50)} ft"
        m < 16012.9728 -> java.math.BigDecimal(m / 1609.344).setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() + " mi"
        else -> java.math.BigDecimal(m / 1609.344).setScale(0, java.math.RoundingMode.HALF_UP).toPlainString() + " mi"
    }

    @Test
    fun `the owner's exit 24 trip`() {
        val b = banners("exit24")
        assertEquals(5, b.size)
        // On I-90: exit 24, the left turn beside it in 0.3 mi, no lane row (no lane data at the exit)
        val exit = b[1]
        assertEquals("Exit 24", exit.main.text)
        assertEquals(ManeuverIcon.FORK_RIGHT, exit.main.icon)
        assertEquals("24", exit.main.exitNumber)
        assertEquals(listOf("US 19", "Peach St"), exit.main.roads, "road_abbreviations: the sketch's 'US 19 / Peach St'")
        assertEquals(listOf("Waterford"), exit.main.towards)
        assertEquals(ManeuverIcon.LEFT, exit.following!!.icon)
        assertEquals("In 0.3 mi", exit.following!!.let(::chip))
        assertNull(exit.lanes)
        // At the end of the ramp: turn left, the right turn beside it in 0.2 mi; lanes left | left+through | right
        val left = b[2]
        assertEquals("Turn left", left.main.text)
        assertEquals(listOf("US 19", "Peach St", "General Strong Vincent Memorial Hwy"), left.main.roads)
        assertEquals(ManeuverIcon.RIGHT, left.following!!.icon)
        assertEquals("In 0.2 mi", left.following!!.let(::chip))
        assertEquals(listOf(ALLOWED, BEST, NOT_FOR_ROUTE), marks(left))
        // On Peach St: turn right onto Downs Dr, nothing beside it, the right-most of four lanes
        val right = b[3]
        assertEquals("Turn right", right.main.text)
        assertEquals(listOf("Downs Dr"), right.main.roads)
        assertNull(right.following)
        assertEquals(listOf(NOT_FOR_ROUTE, NOT_FOR_ROUTE, NOT_FOR_ROUTE, BEST), marks(right))
        assertEquals(ManeuverIcon.ARRIVE, b[4].main.icon)
        assertNull(b[4].following)
        assertNull(b[4].lanes)
    }

    @Test
    fun `roundabout without lane data`() {
        val b = banners("roundabout_park_line")
        assertEquals("In 0.3 mi", b[0].following!!.let(::chip))
        assertEquals(ManeuverIcon.ROUNDABOUT, b[0].following!!.icon)
        assertEquals("Roundabout, 2nd exit", b[1].main.text)
        assertEquals(2, b[1].main.roundaboutExit)
        assertEquals(listOf("Park Line Dr"), b[1].main.roads)
        assertEquals(ManeuverIcon.ROUNDABOUT_EXIT, b[1].following!!.icon)
        assertEquals("In 200 ft", b[1].following!!.let(::chip))
        assertEquals("In 150 ft", b[2].following!!.let(::chip))
        b.forEach { assertNull(it.lanes) }
    }

    @Test
    fun `roundabout whose lanes have none marked gets no lane row`() {
        val b = banners("roundabout_us222")
        val enter = b.first { it.main.icon == ManeuverIcon.ROUNDABOUT }
        assertNull(enter.lanes, "lanes are tagged, but the engine marked none for this maneuver")
        assertNull(b.first { it.main.icon == ManeuverIcon.ROUNDABOUT_EXIT }.lanes)
    }

    @Test
    fun `motorway forks keep both marked lanes when no turn follows`() {
        val l = banners("fork_left_i476")
        assertEquals("Exit 7", l[1].main.text)
        assertEquals("In 0.4 mi", l[0].following!!.let(::chip))
        assertNull(l[1].following, "838 m to the next fork is over 0.5 mi")
        assertEquals(listOf(NOT_FOR_ROUTE, BEST, BEST), marks(l[1]))
        assertEquals("Keep left", l[2].main.text)
        assertEquals(listOf(BEST, BEST), marks(l[2]))
        val r = banners("fork_right_us1")
        assertEquals("Exit 340B", r[1].main.text)
        assertEquals(listOf("US 1 North"), r[1].main.roads)
        assertEquals(listOf(NOT_FOR_ROUTE, NOT_FOR_ROUTE, BEST, BEST), marks(r[1]))
    }

    @Test
    fun `left exit followed by a keep-left narrows the best lane to the left one`() {
        val b = banners("left_exit_i676")
        val exit = b[2]
        assertEquals(ManeuverIcon.EXIT_LEFT, exit.main.icon)
        assertEquals("Exit 22 on the left", exit.main.text)
        assertEquals(ManeuverIcon.FORK_LEFT, exit.following!!.icon)
        assertEquals("In 0.5 mi", exit.following!!.let(::chip))
        // engine: two active lanes (769 m is over its own 0.402 km rule); banner: the left one is best
        assertEquals(listOf(BEST, ALLOWED, NOT_FOR_ROUTE, NOT_FOR_ROUTE), marks(exit))
        assertNull(b[1].following, "1261 m ramp: no following turn")
        val noLanes = banners("left_exit_red_lion")
        val e = noLanes.first { it.main.icon == ManeuverIcon.EXIT_LEFT }
        assertEquals("Take the exit on the left", e.main.text)
        assertNull(e.lanes)
        assertEquals("In 150 ft", noLanes[0].following!!.let(::chip))
    }

    @Test
    fun `left turn then left U-turn then right turns`() {
        val b = banners("uturn_left_grant")
        assertEquals(ManeuverIcon.UTURN_LEFT, b[1].following!!.icon)
        assertEquals("In 0.3 mi", b[1].following!!.let(::chip))
        assertEquals(listOf(BEST, NOT_FOR_ROUTE, NOT_FOR_ROUTE), marks(b[1]))
        assertEquals("Make a U-turn", b[2].main.text)
        assertEquals("In 350 ft", b[2].following!!.let(::chip))
        assertEquals(listOf(BEST, NOT_FOR_ROUTE, NOT_FOR_ROUTE), marks(b[2]))
    }

    @Test
    fun `right U-turn, and an arrival whose lanes have none marked`() {
        val b = banners("uturn_right_i376")
        val u = b.indexOfFirst { it.main.icon == ManeuverIcon.UTURN_RIGHT }
        assertEquals("In 0.1 mi", b[u - 1].following!!.let(::chip))
        assertEquals(ManeuverIcon.UTURN_RIGHT, b[u - 1].following!!.icon)
        assertNull(b[u].following, "arriving is not a following turn")
        assertNull(b.last().lanes)
    }

    @Test
    fun `left exit, right turn, U-turn and a ramp onto the motorway`() {
        val b = banners("ramp_onto_i76")
        assertEquals("Exit 339 on the left", b[1].main.text)
        assertNull(b[1].lanes, "lanes 'merge to right' and two without arrows, none marked")
        assertEquals("In 0.2 mi", b[1].following!!.let(::chip))
        assertEquals("In 400 ft", b[2].following!!.let(::chip))
        // the engine itself takes the U-turn into account: the left of the two right-turn lanes
        assertEquals(listOf(NOT_FOR_ROUTE, BEST, ALLOWED), marks(b[2]))
        assertEquals("In 200 ft", b[3].following!!.let(::chip))
        assertEquals(ManeuverIcon.RAMP_RIGHT, b[4].main.icon)
        assertEquals(listOf("I 76 East", "US 1 North"), b[4].main.roads, "the sign's two route numbers")
        assertEquals(listOf("Central Phila", "Roosevelt Expressway"), b[4].main.towards)
        assertNull(b[4].following)
        assertEquals(listOf(NOT_FOR_ROUTE, NOT_FOR_ROUTE, BEST, BEST), marks(b[4]))
    }

    @Test
    fun `exit then keep straight keeps both through lanes`() {
        val b = banners("keep_straight_us22")
        assertEquals("Exit 50", b[1].main.text)
        assertEquals(ManeuverIcon.FORK_STRAIGHT, b[1].following!!.icon)
        assertEquals("In 0.2 mi", b[1].following!!.let(::chip))
        assertEquals(listOf(NOT_FOR_ROUTE, NOT_FOR_ROUTE, NOT_FOR_ROUTE, BEST, BEST), marks(b[1]), "a straight has no side")
    }

    @Test
    fun `numbered exit with three route numbers`() {
        val e = banners("exit_147a_i79")[1]
        assertEquals("Exit 147A", e.main.text)
        assertEquals(listOf("US 6 East", "US 19 North", "US 322 East"), e.main.roads)
        assertEquals(listOf("Meadville"), e.main.towards)
    }

    @Test
    fun `two keep-rights in a row with a valid and an active lane`() {
        val b = banners("keep_right_aramingo")
        assertEquals("Exit 26", b[1].main.text)
        assertEquals("In 0.3 mi", b[1].following!!.let(::chip))
        assertEquals("Keep right", b[2].main.text)
        assertEquals("In 0.1 mi", b[2].following!!.let(::chip))
        assertEquals(listOf(NOT_FOR_ROUTE, ALLOWED, BEST), marks(b[2]))
    }

    @Test
    fun `arriving on the left`() {
        val b = banners("arrive_left")
        val last = b.last()
        assertEquals(ManeuverIcon.ARRIVE_LEFT, last.main.icon)
        assertNull(last.following)
        assertNull(b[b.size - 2].following, "the turn before arriving does not chain to the destination")
        assertEquals("In 250 ft", b[b.size - 3].following!!.let(::chip))
    }

    @Test
    fun `road names - the last word abbreviated, only after another word, exact words only`() {
        assertEquals("Peach St", rules.abbreviate("Peach Street"))
        assertEquals("Street Rd", rules.abbreviate("Street Road"), "last word only")
        assertEquals("Downs Drive Extension", rules.abbreviate("Downs Drive Extension"))
        assertEquals("Boulevard", rules.abbreviate("Boulevard"), "a name that is the word alone stays")
        assertEquals("US 19", rules.abbreviate("US 19"))
        assertEquals("I 90 East", rules.abbreviate("I 90 East"))
        assertEquals("Peach street", rules.abbreviate("Peach street"), "exact words, case included")
        val all = mapOf("Street" to "St", "Drive" to "Dr", "Avenue" to "Ave", "Road" to "Rd", "Boulevard" to "Blvd",
            "Highway" to "Hwy", "Parkway" to "Pkwy", "Lane" to "Ln", "Court" to "Ct", "Place" to "Pl", "Circle" to "Cir",
            "Terrace" to "Ter")
        assertEquals(all, rules.roadAbbreviations, "docs/architecture.md 5.1, N")
        for ((w, s) in all) assertEquals("Main $s", rules.abbreviate("Main $w"))
        // towards (the sign's destinations) are not road names: left as the engine gives them
        assertEquals(listOf("Central Phila", "Roosevelt Expressway"), banners("ramp_onto_i76")[4].main.towards)
    }

    @Test
    fun `banner json has no distance texts any more and refuses them`() {
        val text = javaClass.getResourceAsStream("/io/github/spysyweeb/beamline/routing/banner/banner.json")!!.readBytes().toString(Charsets.UTF_8)
        assertFalse("distance_text" in Json.parseToJsonElement(text).jsonObject)
        val e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BannerRules.parse(text.replaceFirst("{", "{\"distance_text\": {},"))
        }
        assertTrue(e.message!!.contains("units.json"))
    }

    /** Writes every banner of every case as a table (for the report); not an assertion. */
    @Test
    fun `dump banners`() {
        val out = StringBuilder("| case | # | maneuver | main | following | lanes |\n|---|---|---|---|---|---|\n")
        for ((case, route) in routes) {
            BannerModel.build(route, rules).forEach { b ->
                val m = route.maneuvers[b.maneuverIndex]
                out.append("| $case | ${b.maneuverIndex} | ${m.typeCode} ${m.instruction} (${"%.0f".format(m.lengthM)} m, ${"%.1f".format(m.timeS)} s) | ")
                    .append("${b.main.text}${if (b.main.roads.isNotEmpty()) " / " + b.main.roads.joinToString(" / ") else ""} | ")
                    .append(b.following?.let { "${it.text}, ${chip(it)}" } ?: "-").append(" | ")
                    .append(b.lanes?.joinToString(" ") { it.mark.name.lowercase() } ?: "-").append(" |\n")
            }
        }
        File("build/banner-cases.md").apply { parentFile.mkdirs() }.writeText(out.toString())
    }

    /** Item 22 (docs/next-round.md): a ramp or an exit the data gives no sign and no name for says where it leads. */
    @Test
    fun `a ramp without a sign leads toward the road of the next maneuver`() {
        val ramps = setOf(ManeuverIcon.RAMP_LEFT, ManeuverIcon.RAMP_RIGHT, ManeuverIcon.RAMP_STRAIGHT, ManeuverIcon.EXIT_LEFT, ManeuverIcon.EXIT_RIGHT)
        var bare = 0
        for ((case, route) in routes) {
            val mans = route.maneuvers
            val bs = BannerModel.build(route, rules)
            for ((i, b) in bs.withIndex()) {
                if (b.main.icon !in ramps || b.main.roads.isNotEmpty()) continue
                val sign = mans[i].sign
                if (sign != null && sign.towards.isNotEmpty()) {
                    assertEquals(sign.towards, b.main.towards, "$case $i: the sign's own destinations stay")
                    continue
                }
                val next = mans.getOrNull(i + 1)?.takeIf { !it.type.isDestination }
                val want = next?.streetNames?.take(1)?.map(rules::abbreviate) ?: emptyList()
                assertEquals(want, b.main.towards, "$case $i ${b.main.text}")
                if (want.isNotEmpty()) bare++
            }
            // nothing but a bare ramp gets a "toward" the data did not give
            for ((i, b) in bs.withIndex()) {
                if (b.main.icon !in ramps) assertEquals(mans[i].sign?.towards ?: emptyList<String>(), b.main.towards, "$case $i")
            }
        }
        // the recorded routes sign every ramp, so one of them without its sign and names: the ramp onto I 76
        val mans = routes.getValue("ramp_onto_i76").maneuvers
        val ramp = mans[4].copy(sign = null, streetNames = emptyList(), beginStreetNames = emptyList())
        val next = mans[5]
        assertTrue(next.streetNames.isNotEmpty() && !next.type.isDestination, "${next.type} ${next.streetNames}")
        val main = BannerModel.main(ramp, 0.0, next, rules)
        assertEquals("Take the ramp on the right", main.text)
        assertEquals(emptyList<String>(), main.roads)
        assertEquals(listOf(rules.abbreviate(next.streetNames[0])), main.towards)
        assertEquals(emptyList<String>(), BannerModel.main(ramp, 0.0, null, rules).towards, "nothing after it: nothing to say")
        assertTrue(bare >= 0)
    }
}
