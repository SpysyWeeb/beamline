package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.feature.drive.Exit24.downsDrive
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import io.github.spysyweeb.beamline.feature.drive.Exit24.sheetz
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Rule R3: the resume card ("Continue to …?"); play test P27: a simulated drive offered again says so. */
class ResumePresentTest {

    @Test
    fun `P27 a simulated drive offered after a restart says it is simulated`() {
        val offered = TripState(phase = Phase.RESUME_OFFERED, stops = listOf(TripStop(downsDrive)), resumeSimulated = true)
        assertEquals(ResumeUi("Downs Drive", 1, simulated = true), present(offered).resume)
        assertEquals(ResumeUi("Downs Drive", 1), present(offered.copy(resumeSimulated = false)).resume)
        val zoo = Place("Erie Zoo", GeoPoint(42.1489, -80.0869), PlaceSource.SEARCH)
        assertEquals(ResumeUi("Erie Zoo", 2, simulated = true), present(offered.copy(stops = listOf(TripStop(sheetz, reached = true), TripStop(downsDrive), TripStop(zoo)))).resume)
        // the card's words
        assertEquals("Continue the simulated drive to Erie Zoo?", DriveTexts.continueTo("Erie Zoo", true))
        assertEquals("Continue the simulated drive?", DriveTexts.continueTo(" ", true))
        assertEquals("Continue to Erie Zoo?", DriveTexts.continueTo("Erie Zoo"))
        assertEquals("Continue to Erie Zoo?", DriveTexts.continueTo("Erie Zoo", false))
        assertEquals("Continue your trip?", DriveTexts.continueTo(""))
    }
}
