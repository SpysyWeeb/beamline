package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/** 0.1.41 (wave 0, docs/online-design.md 4.8): the sheet's "Hours still right?" contract and its two buttons. */
class HoursCheckContractTest {

    private val sheet = PlaceSheetUi(
        place = Place("Example Diner", GeoPoint(42.1, -80.1), PlaceSource.SEARCH),
        iconName = "restaurant", title = "Example Diner", subtitle = null, distanceText = null, note = null,
        actions = listOf(PlaceAction.DIRECTIONS),
    )

    @Test
    fun `a sheet made as before offers no hours check`() {
        assertFalse(sheet.canCheckHours)
        assertFalse(sheet.hoursChecked)
        assertFalse(PlaceAction.CONFIRM_HOURS in sheet.actions)
        assertFalse(PlaceAction.EDIT_HOURS in sheet.actions)
    }

    @Test
    fun `the two buttons read Yes and Edit hours`() {
        assertEquals("Yes", placeActionLabel(PlaceAction.CONFIRM_HOURS))
        assertEquals("Edit hours", placeActionLabel(PlaceAction.EDIT_HOURS))
    }
}
