package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.download.net.CatalogAddress
import io.github.spysyweeb.beamline.download.net.PackHttp
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/** The check and the download of an app update (item 41, docs/app-updates-design.md 4). */
class AppUpdaterTest {
    @TempDir lateinit var dir: File
    private val server = TestPackServer()
    private val address = CatalogAddress.parse(server.url("/releases/latest/download/update.json"), allowCleartext = true)!!
    private val http = PackHttp(address, DownloadRules())
    private var now = 1_800_000_000_000L
    private val apk = ByteArray(200_000) { (it * 31).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    @AfterEach
    fun close() = server.close()

    private fun publish(code: Long, size: Long = apk.size.toLong(), hash: String = sha, minSdk: Int = 26) {
        server.put("/releases/latest/download/update.json", """{"format":"beamline-update/1","version_code":$code,"version_name":"0.1.$code",
            "min_sdk":$minSdk,"notes":"n","apks":{"arm64-v8a":{"file":"beamline-$code-arm64.apk","size":$size,"sha256":"$hash"}}}""".toByteArray())
        server.put("/releases/latest/download/beamline-$code-arm64.apk", apk)
    }

    private fun updater(installed: Long = 28) = AppUpdater(address, http, File(dir, "update"), installed, 36, listOf("arm64-v8a"), clock = { now })

    @Test
    fun aNewerReleaseIsFoundDownloadedAndCheckedOncePerDay() {
        publish(29)
        val u = updater()
        val a = u.check(force = false) as AppUpdater.State.Available
        assertEquals("arm64-v8a", a.abi)
        val r = u.download() as AppUpdater.State.Ready
        assertTrue(r.file.readBytes().contentEquals(apk))
        assertFalse(File(r.file.path + ".part").exists())
        // within a day: no new request; a new process finds the downloaded file again
        val asked = server.requests("/releases/latest/download/update.json").size
        assertTrue(updater().check(force = false) is AppUpdater.State.Idle, "checked less than a day ago: nothing asked")
        assertEquals(asked, server.requests("/releases/latest/download/update.json").size)
        now += AppUpdater.DAY_MS
        assertTrue(updater().check(force = false) is AppUpdater.State.Ready, "the APK on disk still matches")
    }

    @Test
    fun theSameOrAnOlderVersionOrATooNewAndroidIsNoUpdate() {
        publish(28)
        assertTrue(updater(installed = 28).check(force = true) is AppUpdater.State.UpToDate)
        publish(27)
        assertTrue(updater(installed = 28).check(force = true) is AppUpdater.State.UpToDate, "never a downgrade")
        publish(29, minSdk = 99)
        assertTrue(updater(installed = 28).check(force = true) is AppUpdater.State.UpToDate, "needs a newer Android")
    }

    @Test
    fun anApkThatDoesNotMatchUpdateJsonIsDropped() {
        publish(29, hash = "b".repeat(64))
        val u = updater()
        assertTrue(u.check(force = true) is AppUpdater.State.Available)
        val f = u.download() as AppUpdater.State.Failed
        assertTrue(f.reason.contains("does not match"), f.reason)
        assertTrue(File(dir, "update").listFiles().orEmpty().none { it.name.endsWith(".apk") || it.name.endsWith(".part") })
        publish(30, size = 1000)
        val u2 = updater()
        u2.check(force = true)
        assertTrue((u2.download() as AppUpdater.State.Failed).reason.contains("longer"))
    }
}
