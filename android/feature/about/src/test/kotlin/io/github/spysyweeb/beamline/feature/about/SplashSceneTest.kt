package io.github.spysyweeb.beamline.feature.about

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** SplashScene's numbers are the approved splash-scene.svg's (docs/design/app-icon/). */
class SplashSceneTest {

    private val design = File(System.getProperty("beamline.repoRoot") ?: "../../..", "docs/design/app-icon")

    private fun svg(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(design, name)).documentElement

    private fun Element.all(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.f(attr: String) = getAttribute(attr).toFloat()

    private fun color(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

    private val scene = svg("splash-scene.svg")

    @Test
    fun blocksAndParkAreTheDesigns() {
        val blockGroup = scene.all("g").single { it.getAttribute("fill") == color(SplashScene.BLOCK) }
        val rects = blockGroup.all("rect").map { SplashScene.R(it.f("x"), it.f("y"), it.f("width"), it.f("height")) }
        assertEquals(rects, SplashScene.blocks)
        assertTrue(blockGroup.all("rect").all { it.f("rx") == SplashScene.BLOCK_CORNER })
        val park = scene.all("rect").single { it.getAttribute("fill") == color(SplashScene.PARK) }
        assertEquals(SplashScene.R(park.f("x"), park.f("y"), park.f("width"), park.f("height")), SplashScene.park)
        assertEquals(SplashScene.PARK_CORNER, park.f("rx"))
        assertTrue(scene.all("rect").any { it.getAttribute("fill") == color(SplashScene.LAND) })
    }

    @Test
    fun riverRoadsAndRouteAreTheDesigns() {
        val paths = scene.all("path")
        fun stroked(c: Long) = paths.filter { it.getAttribute("stroke") == color(c) }

        val river = stroked(SplashScene.WATER).single()
        val r = SplashScene.river
        assertEquals("M${r[0].s()} C ${r[1].s()}, ${r[2].s()}, ${r[3].s()}", river.getAttribute("d"))
        assertEquals(SplashScene.RIVER_WIDTH, river.f("stroke-width"))
        assertEquals("round", river.getAttribute("stroke-linecap"))

        val side = stroked(SplashScene.SIDE_ROAD).single()
        assertEquals(SplashScene.SIDE_ROAD_WIDTH, side.f("stroke-width"))
        assertEquals(SplashScene.sideRoadsX, verticals(side.getAttribute("d")))
        assertEquals(SplashScene.sideRoadsY, horizontals(side.getAttribute("d")))
        val main = stroked(SplashScene.MAIN_ROAD).single()
        assertEquals(SplashScene.MAIN_ROAD_WIDTH, main.f("stroke-width"))
        assertEquals(SplashScene.mainRoadsX, verticals(main.getAttribute("d")))
        assertEquals(SplashScene.mainRoadsY, horizontals(main.getAttribute("d")))

        val routeD = "M" + SplashScene.route.joinToString(" L") { it.s() }
        val casing = stroked(SplashScene.ROUTE_CASING).single()
        val fill = stroked(SplashScene.ROUTE).single()
        assertEquals(routeD, casing.getAttribute("d"))
        assertEquals(routeD, fill.getAttribute("d"))
        assertEquals(SplashScene.ROUTE_CASING_WIDTH, casing.f("stroke-width"))
        assertEquals(SplashScene.ROUTE_WIDTH, fill.f("stroke-width"))
        assertEquals("round", casing.getAttribute("stroke-linejoin"))
    }

    @Test
    fun theRevealStartsFromTheIconsCircle() {
        val start = svg("splash-start.svg")
        val c = start.all("circle").single()
        assertEquals(SplashScene.CENTRE, c.f("cx"))
        assertEquals(SplashScene.CENTRE, c.f("cy"))
        assertEquals(SplashScene.ICON_RADIUS, c.f("r"))
        assertEquals("0 0 ${SplashScene.ICON_CANVAS.toInt()} ${SplashScene.ICON_CANVAS.toInt()}", svg("icon-foreground.svg").getAttribute("viewBox"))
    }

    /** The bitmap drawn over the scene (icon-foreground.svg) holds exactly the scene's arrow, beam and glow. */
    @Test
    fun theForegroundIsTheScenesArrowBeamAndGlow() {
        val fg = File(design, "icon-foreground.svg").readText()
        val body = fg.substringAfter("</defs>").substringBefore("</svg>").trim().lines()
        val sceneText = File(design, "splash-scene.svg").readText()
        val sceneBody = sceneText.substringAfter("</defs>").lines()
        val tail = sceneBody.dropWhile { it.trim() != body.first().trim() }.take(body.size)
        assertEquals(body.map { it.trim() }, tail.map { it.trim() })
        // ...and it is the last thing the scene draws.
        assertEquals("</svg>", sceneBody.dropWhile { it.trim() != body.first().trim() }.drop(body.size).joinToString("").trim())
    }

    private fun SplashScene.P.s(): String = "${n(x)} ${n(y)}"
    private fun n(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    private fun verticals(d: String) = Regex("""M(-?[\d.]+) -?[\d.]+V-?[\d.]+""").findAll(d).map { it.groupValues[1].toFloat() }.toList()
    private fun horizontals(d: String) = Regex("""M-?[\d.]+ (-?[\d.]+)H-?[\d.]+""").findAll(d).map { it.groupValues[1].toFloat() }.toList()
}
