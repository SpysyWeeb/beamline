package io.github.spysyweeb.beamline.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import io.github.spysyweeb.beamline.location.AccelSample
import io.github.spysyweeb.beamline.location.MotionSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Accelerometer readings for the browsing position estimate (rule G7: standing still or moving;
 * the math is :core:location's MotionDetector). Read only while the puck is tracked (the map on
 * screen, the app in the foreground), at the normal sensor rate (about 5 a second). No sensor: no
 * readings, and the estimate uses its moving filter.
 */
class AccelerometerMotionSource(private val context: Context) : MotionSource {
    override fun samples(): Flow<AccelSample> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // event.timestamp is on the elapsedRealtime clock, the same as the fixes'.
                trySend(AccelSample(event.values[0], event.values[1], event.values[2], event.timestamp / 1_000_000))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { sm.unregisterListener(listener) }
    }
}
