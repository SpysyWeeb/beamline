package io.github.spysyweeb.beamline.app.map

import io.github.spysyweeb.beamline.api.model.GeoPoint
import java.io.File
import java.util.Locale

/**
 * Where the map was left, for the next cold start without a position (docs/next-round.md item 5,
 * rule K1, docs/architecture.md 5.4 item 3): `last-camera.txt` in the folder of the files that can
 * name a place of the user (AppGraph.dataDir: the no-backup folder, or its `test/` folder in a test
 * session, PV4 and PV7), because a map left on the street at home names that place. One line,
 * `beamline-last-camera/1 <lat> <lon> <zoom>`, written atomically; a missing, broken or foreign file
 * reads as none. Never logged (PV2). [read] runs once on the main thread when the map is built (one
 * line, before the first frame, as the settings stores read theirs, docs/architecture.md 1.2); [write]
 * runs off the main thread (ON_STOP).
 */
class LastCameraStore(private val file: File) {

    data class Camera(val center: GeoPoint, val zoom: Double)

    fun read(): Camera? = try {
        if (!file.isFile) null else parse(file.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    fun write(camera: Camera) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.outputStream().use {
                it.write(format(camera).toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            // Only a convenience: the next start shows the state instead.
        }
    }

    companion object {
        const val FORMAT = "beamline-last-camera/1"
        const val FILE_NAME = "last-camera.txt"

        fun format(c: Camera): String =
            String.format(Locale.US, "%s %.6f %.6f %.2f\n", FORMAT, c.center.lat, c.center.lon, c.zoom)

        fun parse(text: String): Camera? {
            val parts = text.trim().split(' ')
            if (parts.size != 4 || parts[0] != FORMAT) return null
            val la = parts[1].toDoubleOrNull() ?: return null
            val lo = parts[2].toDoubleOrNull() ?: return null
            val z = parts[3].toDoubleOrNull() ?: return null
            if (la !in -90.0..90.0 || lo !in -180.0..180.0 || z !in 0.0..25.5) return null
            return Camera(GeoPoint(la, lo), z)
        }
    }
}
