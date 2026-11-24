package io.github.spysyweeb.beamline.routing.demo

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.args
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.target
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Latency and memory in the debug build (DemoMeasure). Run once per process (device.py measure
 * starts a fresh process for each run): the first route of the process is the "first" figure.
 * Instrumentation argument `first` = U1 (5.8 km) or I1 (479 km) picks which trip goes first.
 * The release build is measured through DemoActivity (device.py measure --release).
 */
@RunWith(AndroidJUnit4::class)
class MeasureTest {
    @Test
    fun latencyAndMemory() {
        DemoMeasure.run(target, args().getString("first") ?: "U1", "debug")
    }
}
