package io.github.spysyweeb.beamline.routing.mobile

import io.github.spysyweeb.beamline.routing.engine.EngineException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The error handling of the engine adapter; no native code is loaded (plain JVM). */
class ValhallaEngineErrorsTest {
    @Test
    fun codeFromValhallaMobileMessage() {
        // ErrorResponse.toString() in valhalla-mobile 0.6.4, as seen on the emulator
        assertEquals(171, ValhallaEngine.errorCode("ValhallaError(code=171, No suitable edges near location)"))
        assertEquals(170, ValhallaEngine.errorCode("ValhallaError(code=170, Locations are in unconnected regions. Go check/edit the map at osm.org)"))
        assertNull(ValhallaEngine.errorCode("Invalid response data"))
    }

    @Test
    fun errorEnvelopeIsNeverTakenForAnAnswer() {
        try {
            ValhallaEngine.envelopeCheck("""{"code":442,"message":"No path could be found for input"}""")
            fail("envelope not recognised")
        } catch (e: EngineException) {
            assertEquals(442, e.code)
        }
        val answer = """{"trip":{"locations":[],"legs":[],"summary":{"length":1.0,"time":2.0}}}"""
        assertEquals(answer, ValhallaEngine.envelopeCheck(answer))
        // an OSRM answer also starts with "code" but has more keys
        val osrm = """{"code":"Ok","routes":[],"waypoints":[]}"""
        assertEquals(osrm, ValhallaEngine.envelopeCheck(osrm))
    }
}
