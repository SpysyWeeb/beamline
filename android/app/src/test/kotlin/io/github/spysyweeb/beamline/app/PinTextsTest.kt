package io.github.spysyweeb.beamline.app

import io.github.spysyweeb.beamline.app.ui.AppFlows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A dropped pin's line once its street is known (docs/next-round.md item 31). */
class PinTextsTest {
    @Test
    fun aDroppedPinSaysItsStreetAndTown() {
        assertEquals("Near West 8th Street, Erie", AppFlows.nearStreetText("West 8th Street", "Erie, Erie County"))
        assertEquals("Near Peach Street, Millcreek Township", AppFlows.nearStreetText("Peach Street", "Millcreek Township, Erie County"))
        // a street outside every municipality carries its county only; one without either, its name
        assertEquals("Near Old Route 6, Warren County", AppFlows.nearStreetText("Old Route 6", "Warren County"))
        assertEquals("Near Old Route 6", AppFlows.nearStreetText("Old Route 6", ""))
        // with the nearest house number of that street (0.1.18)
        assertEquals("Near 10 North Park Row, Erie", AppFlows.nearStreetText("North Park Row", "Erie, Erie County", "10"))
        assertEquals("Near West 8th Street, Erie", AppFlows.nearStreetText("West 8th Street", "Erie, Erie County", " "))
    }
}
