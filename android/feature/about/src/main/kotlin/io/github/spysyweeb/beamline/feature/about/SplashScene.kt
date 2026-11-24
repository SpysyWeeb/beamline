package io.github.spysyweeb.beamline.feature.about

/**
 * The splash scene of the approved design (docs/design/app-icon/splash-scene.svg), in the icon's
 * own units: the adaptive icon canvas is 0..108, its visible circle is centred on (54, 54) with
 * radius 36. Everything except the arrow, its beam and its brake glow (the icon's foreground, a
 * bitmap because of its blurs) is drawn from these numbers. SplashSceneTest compares them with
 * the SVG.
 */
internal object SplashScene {
    /** A rectangle: left, top, width, height. */
    data class R(val x: Float, val y: Float, val w: Float, val h: Float)

    data class P(val x: Float, val y: Float)

    const val LAND = 0xFF1D2733
    const val WATER = 0xFF17364F
    const val PARK = 0xFF1F3D35
    const val BLOCK = 0xFF263343
    const val SIDE_ROAD = 0xFF2C3643
    const val MAIN_ROAD = 0xFF3B4756
    const val ROUTE_CASING = 0xFF2059AB
    const val ROUTE = 0xFF5CA6F8

    /** The icon's visible circle, where the reveal starts. */
    const val CENTRE = 54f
    const val ICON_RADIUS = 36f
    /** The icon canvas (the foreground bitmap covers 0..ICON_CANVAS on both axes). */
    const val ICON_CANVAS = 108f

    /** River: one cubic Bézier, round caps. */
    val river = listOf(P(-40f, -90f), P(10f, -74f), P(62f, -104f), P(150f, -80f))
    const val RIVER_WIDTH = 21f

    val park = R(64.5f, 109.5f, 42f, 49f)
    const val PARK_CORNER = 4f

    const val BLOCK_CORNER = 1.6f
    val blocks = listOf(
        R(2.7f, -65.1f, 17.6f, 12.5f), R(4.0f, -48.2f, 15.1f, 11.8f), R(25.8f, -47.8f, 15.3f, 12.0f), R(3.2f, -16.5f, 16.5f, 8.8f),
        R(3.6f, -1.0f, 15.9f, 10.7f), R(3.8f, 14.2f, 15.4f, 11.7f), R(25.4f, -17.8f, 16.3f, 11.5f), R(25.9f, -0.3f, 15.3f, 6.7f),
        R(3.8f, 49.2f, 15.4f, 12.0f), R(2.5f, 65.7f, 18.0f, 8.9f), R(4.2f, 82.7f, 14.7f, 9.1f), R(25.4f, 50.4f, 16.2f, 9.1f),
        R(25.5f, 66.1f, 16.1f, 10.7f), R(24.4f, 81.2f, 18.2f, 10.6f), R(3.4f, 110.7f, 16.2f, 9.6f), R(2.5f, 127.2f, 18.1f, 12.1f),
        R(2.8f, 145.7f, 17.4f, 9.0f), R(25.3f, 110.5f, 16.4f, 11.1f), R(24.4f, 127.2f, 18.1f, 11.4f), R(24.2f, 144.7f, 18.7f, 11.6f),
        R(2.9f, 174.8f, 17.3f, 6.3f), R(3.6f, 189.8f, 15.7f, 6.8f), R(2.9f, 202.1f, 17.1f, 8.8f), R(25.2f, 176.2f, 16.7f, 4.6f),
        R(24.6f, 189.3f, 17.8f, 6.6f), R(65.8f, -65.4f, 17.4f, 11.7f), R(66.2f, -47.1f, 16.7f, 8.9f), R(88.3f, -65.7f, 16.4f, 11.5f),
        R(88.7f, -48.0f, 15.6f, 12.2f), R(65.3f, -17.9f, 18.5f, 12.6f), R(67.1f, 15.0f, 14.8f, 9.7f), R(88.0f, -15.9f, 17.0f, 6.5f),
        R(87.4f, -0.5f, 18.1f, 8.5f), R(87.4f, 14.8f, 18.2f, 9.8f), R(66.0f, 50.5f, 17.0f, 8.2f), R(66.2f, 66.2f, 16.6f, 8.7f),
        R(66.7f, 82.8f, 15.7f, 7.2f), R(87.3f, 50.7f, 18.4f, 7.8f), R(87.8f, 66.6f, 17.4f, 9.5f), R(87.4f, 82.7f, 18.2f, 6.7f),
        R(65.3f, 176.3f, 18.4f, 3.8f), R(66.0f, 188.6f, 17.0f, 6.4f), R(66.2f, 202.1f, 16.6f, 8.0f), R(88.4f, 174.3f, 16.2f, 7.9f),
    )

    /** Streets run across the whole screen: vertical ones at these x, horizontal ones at these y. */
    val sideRoadsX = listOf(-6f, 114f)
    val sideRoadsY = listOf(-74f, -26f, 102f, 166f)
    const val SIDE_ROAD_WIDTH = 11f
    val mainRoadsX = listOf(54f)
    val mainRoadsY = listOf(38f)
    const val MAIN_ROAD_WIDTH = 17f

    /** The route: in from the left behind the car, up the main street, right two blocks ahead. */
    val route = listOf(P(-30f, 166f), P(54f, 166f), P(54f, -26f), P(114f, -26f), P(114f, -120f))
    const val ROUTE_CASING_WIDTH = 9.5f
    const val ROUTE_WIDTH = 6f
}
