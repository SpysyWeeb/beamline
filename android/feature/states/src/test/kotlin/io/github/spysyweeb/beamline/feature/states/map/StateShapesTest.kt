package io.github.spysyweeb.beamline.feature.states.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** docs/architecture-downloads.md 10.2: the shapes asset and the tap rules, on the real asset. */
class StateShapesTest {
    private val shapes: StateShapes = StateShapes.parse(asset().readText())

    /** The fit zoom of a 393 dp card: the 1,000-unit map in 393 - 2 x 16 dp. */
    private val fit = 1000f / (393f - 32f)

    private fun asset(): File {
        val here = File("src/main/assets/states/us_states.json")
        if (here.isFile) return here
        val root = System.getProperty("beamline.repoRoot")?.let(::File) ?: error("asset not found")
        return File(root, "android/feature/states/src/main/assets/states/us_states.json")
    }

    private fun tap(lat: Double, lon: Double, unitsPerDp: Float = fit): TapResult {
        val p = shapes.project(lon, lat) ?: error("outside every frame")
        return StateHitTest.resolve(shapes, p[0], p[1], unitsPerDp)
    }

    private fun codes(r: TapResult) = when (r) {
        is TapResult.One -> listOf(r.code)
        is TapResult.Choose -> r.codes
        TapResult.None -> emptyList()
    }

    @Test
    fun theAssetHasEveryStateOnce() {
        assertEquals(51, shapes.states.size)
        assertEquals(51, shapes.states.map { it.code }.toSet().size)
        assertEquals(1000f, shapes.width)
        assertTrue(shapes.height in 600f..650f)
        val pa = shapes.byCode("PA")!!
        assertEquals(42, pa.fips)
        assertEquals("Pennsylvania", pa.name)
        assertEquals(Inset.LOWER48, pa.inset)
        assertEquals(listOf("DE", "MD", "NJ", "NY", "OH", "WV"), pa.neighbours)
        assertEquals(Inset.ALASKA, shapes.byCode("AK")!!.inset)
        assertEquals(Inset.HAWAII, shapes.byCode("HI")!!.inset)
        assertEquals(setOf(Inset.ALASKA, Inset.HAWAII), shapes.insetBoxes.keys)
        assertEquals(3, shapes.frames.size)
        for (s in shapes.states) {
            assertTrue(s.rings.isNotEmpty(), s.code)
            assertTrue(s.box[0] <= s.labelX + 1 && s.labelX - 1 <= s.box[2], s.code)
        }
    }

    @Test
    fun tapsAtTheFitZoom() {
        assertEquals(TapResult.One("PA"), tap(40.9, -77.8))
        val dc = shapes.byCode("DC")!!
        val atDc = StateHitTest.resolve(shapes, dc.labelX, dc.labelY, fit)
        assertTrue(atDc is TapResult.Choose && atDc.codes.containsAll(listOf("DC", "MD", "VA")), "$atDc")
        assertEquals("DC", (atDc as TapResult.Choose).codes.first(), "nearest first")
        val ri = shapes.byCode("RI")!!
        val atRi = StateHitTest.resolve(shapes, ri.labelX, ri.labelY, fit)
        assertTrue(atRi is TapResult.Choose && atRi.codes.containsAll(listOf("RI", "CT")), "$atRi")
        assertTrue((atRi as TapResult.Choose).codes.size <= 4)
        assertEquals(TapResult.One("MI"), tap(44.0, -87.0)) // Lake Michigan's middle: Natural Earth's MI includes its water
        assertEquals(TapResult.None, tap(35.5, -64.0)) // about 1,000 km off the Atlantic coast
        assertTrue("NJ" in codes(tap(39.6, -72.83)), "100 km off New Jersey") // 1 degree of longitude is about 86 km here
        assertEquals(TapResult.One("AK"), tap(58.30, -134.42)) // Juneau
        assertEquals(TapResult.One("HI"), tap(21.31, -157.86)) // Honolulu
    }

    /** A point inside [inside] only, at most [maxDp] dp from [other]'s outline at the fit zoom, searched within 6 dp of (lat, lon). */
    private fun nextTo(inside: String, other: String, lat: Double, lon: Double, maxDp: Float): FloatArray {
        val c = shapes.project(lon, lat)!!
        val a = shapes.byCode(inside)!!
        val o = shapes.byCode(other)!!
        val r = 6f * fit
        val step = 0.1f * fit
        var y = c[1] - r
        while (y <= c[1] + r) {
            var x = c[0] - r
            while (x <= c[0] + r) {
                if (ShapesCode.contains(a, x, y) && shapes.states.none { it !== a && ShapesCode.contains(it, x, y) } &&
                    ShapesCode.edgeDistance(o, x, y) <= maxDp * fit
                ) {
                    return floatArrayOf(x, y)
                }
                x += step
            }
            y += step
        }
        error("no point of $inside within $maxDp dp of $other")
    }

    private fun isSmallAtFit(code: String): Boolean {
        val b = shapes.byCode(code)!!.box
        return maxOf(b[2] - b[0], b[3] - b[1]) < 48f * fit
    }

    /**
     * The inside rule as built, and the reasons it differs from 6.8's wording (ShapesCode.resolve;
     * the second review asked for every difference to be stated). Each assertion below is one of
     * them; 6.8's literal rule would give the other answer.
     */
    @Test
    fun theInsideRuleAndWhyItDiffersFrom68() {
        // 1. The radius is 2 dp: central PA is within 16 dp of MD and WV, which are small, yet 10.2 wants One(PA).
        val p = shapes.project(-77.8, 40.9)!!
        assertTrue(isSmallAtFit("MD") && isSmallAtFit("WV"))
        assertTrue(ShapesCode.edgeDistance(shapes.byCode("MD")!!, p[0], p[1]) <= 16f * fit, "MD within 16 dp of central PA")
        assertEquals(TapResult.One("PA"), StateHitTest.resolve(shapes, p[0], p[1], fit))

        // 2. The tapped state must be small: Lake Michigan's middle is within 2 dp of Wisconsin's water edge, and
        //    Wisconsin is small at the fit zoom, so "another small state near" would give Choose(MI, WI) against 10.2.
        val lake = shapes.project(-87.0, 44.0)!!
        assertTrue(isSmallAtFit("WI") && !isSmallAtFit("MI"))
        assertTrue(ShapesCode.edgeDistance(shapes.byCode("WI")!!, lake[0], lake[1]) <= 2f * fit, "WI within 2 dp of the lake's middle")
        assertEquals(TapResult.One("MI"), StateHitTest.resolve(shapes, lake[0], lake[1], fit))
        //    Its cost, stated in the report: 1 dp inside Virginia next to DC gives VA (6.8's wording: the chooser);
        //    from Maryland's side, a small state, DC's chooser opens.
        val va = nextTo("VA", "DC", 38.9, -77.03, maxDp = 1f)
        assertEquals(TapResult.One("VA"), StateHitTest.resolve(shapes, va[0], va[1], fit))
        val md = nextTo("MD", "DC", 38.9, -77.03, maxDp = 1f)
        val fromMd = StateHitTest.resolve(shapes, md[0], md[1], fit)
        assertTrue(fromMd is TapResult.Choose && fromMd.codes.first() == "MD" && "DC" in fromMd.codes, "$fromMd")

        // 3. The list holds every state within 16 dp, small or not: VA is not small, and 10.2 wants it at DC's label.
        assertTrue(!isSmallAtFit("VA"))
        val dc = shapes.byCode("DC")!!
        assertTrue("VA" in (StateHitTest.resolve(shapes, dc.labelX, dc.labelY, fit) as TapResult.Choose).codes)

        // Two large states that meet give the tapped one, in 6.8 and here.
        val tx = nextTo("TX", "OK", 33.9, -97.5, maxDp = 1f)
        assertEquals(TapResult.One("TX"), StateHitTest.resolve(shapes, tx[0], tx[1], fit))
    }

    @Test
    fun zoomedInOverDcIsOneState() {
        val dc = shapes.byCode("DC")!!
        assertEquals(TapResult.One("DC"), StateHitTest.resolve(shapes, dc.labelX, dc.labelY, fit / 8))
    }

    @Test
    fun projectChoosesTheFrame() {
        val erie = shapes.project(-80.09, 42.13)!!
        val pa = shapes.byCode("PA")!!
        assertTrue(erie[0] in pa.box[0]..pa.box[2] && erie[1] in pa.box[1]..pa.box[3])
        val juneau = shapes.project(-134.42, 58.30)!!
        val ak = shapes.insetBoxes.getValue(Inset.ALASKA)
        assertTrue(juneau[0] in ak[0]..ak[2] && juneau[1] in ak[1]..ak[3])
        assertEquals(null, shapes.project(2.35, 48.86)) // Paris
    }
}
