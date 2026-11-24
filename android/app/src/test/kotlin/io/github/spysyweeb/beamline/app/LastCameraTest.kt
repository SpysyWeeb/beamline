package io.github.spysyweeb.beamline.app

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.app.map.LastCameraStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Where the map was left (K1, docs/next-round.md item 5): kept for the next cold start without a position. */
class LastCameraTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `the camera is kept and read back, a missing, broken or foreign file reads as none`() {
        val file = File(dir, LastCameraStore.FILE_NAME)
        val store = LastCameraStore(file)
        assertNull(store.read())
        // downtown Erie, street level
        store.write(LastCameraStore.Camera(GeoPoint(42.1292, -80.0851), 16.25))
        assertEquals(LastCameraStore.Camera(GeoPoint(42.1292, -80.0851), 16.25), store.read())
        assertEquals(false, File(dir, LastCameraStore.FILE_NAME + ".tmp").exists())
        for (text in listOf("", "{\"lat\": 42.1}", "beamline-last-camera/2 42.1 -80.1 16", "beamline-last-camera/1 42.1 -80.1",
            "beamline-last-camera/1 95 -80.1 16", "beamline-last-camera/1 42.1 -80.1 40", "beamline-last-camera/1 a b c")) {
            file.writeText(text)
            assertNull(store.read(), text)
        }
    }
}
