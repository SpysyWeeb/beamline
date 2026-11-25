package io.github.spysyweeb.beamline.app.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import io.github.spysyweeb.beamline.location.Compass
import io.github.spysyweeb.beamline.location.Heading
import io.github.spysyweeb.beamline.location.HeadingSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Compass headings from the rotation-vector sensor (gyroscope, accelerometer and magnetometer
 * fused by Android). The direction math is in :core:location ([Compass]); this class only reads
 * the sensor and turns magnetic north into true north with the declination at the last fix.
 * No sensor: no headings, and the puck has no cone.
 */
class RotationVectorHeadingSource(
    private val context: Context,
    /** SensorManager's delay: SENSOR_DELAY_UI for the puck's cone; for the compass-turned map (K1a) a period in microseconds (8000). */
    private val delay: Int = SensorManager.SENSOR_DELAY_UI,
    private val lastLocation: () -> Location?,
) : HeadingSource {

    override fun headings(): Flow<Heading> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val r = FloatArray(9)
        var declination: Float? = null
        var declinationAt: Location? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                val magnetic = Compass.azimuth(r, displayRotation()) ?: return
                val loc = lastLocation()
                if (loc != null && (declinationAt == null || declinationAt!!.distanceTo(loc) > 10_000f)) {
                    declination = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), System.currentTimeMillis()).declination
                    declinationAt = loc
                }
                trySend(Heading(Compass.normalize(magnetic + (declination ?: 0f)), SystemClock.elapsedRealtime()))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, delay)
        awaitClose { sm.unregisterListener(listener) }
    }

    // The application context has no display of its own (Context.getDisplay throws there), so ask
    // the display manager for the built-in display.
    private fun displayRotation(): Int =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
}
