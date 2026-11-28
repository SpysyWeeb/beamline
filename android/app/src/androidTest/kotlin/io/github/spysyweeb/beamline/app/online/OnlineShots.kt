package io.github.spysyweeb.beamline.app.online

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * 0.1.41: the online screens' screenshots of the device tests (dialogs are windows of their own, so the whole
 * screen is taken), in the app's own external files folder, `screens/online/` (pulled with tools/round/adb.sh).
 */
internal object OnlineShots {
    private val dir: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens/online").apply { mkdirs() }

    fun take(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        Thread.sleep(300)   // a dialog's enter animation
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
