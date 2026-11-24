package io.github.spysyweeb.beamline.download

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** update.json (docs/app-updates-design.md 2, item 41). */
class AppUpdateTest {
    private val sha = "a".repeat(64)
    private val good = """{"format":"beamline-update/1","version_code":28,"version_name":"0.1.25","min_sdk":26,
        "published_utc":"2026-11-27T12:00:00Z","notes":"The arrow keeps up.","later_field":true,
        "apks":{"arm64-v8a":{"file":"beamline-0.1.25-arm64.apk","size":26000000,"sha256":"$sha"},
                "universal":{"file":"beamline-0.1.25.apk","size":85000000,"sha256":"$sha"}}}"""

    @Test
    fun aReleaseFileIsReadAndPicksThePhonesApk() {
        val u = AppUpdate.parse(good)
        assertEquals(28, u.versionCode)
        assertEquals("0.1.25", u.versionName)
        assertEquals("The arrow keeps up.", u.notes)
        assertTrue(u.isNewerThan(27))
        assertFalse(u.isNewerThan(28), "the same version is not an update")
        assertFalse(u.isNewerThan(29), "never a downgrade")
        assertEquals("arm64-v8a", u.apkFor(listOf("arm64-v8a", "armeabi-v7a"))!!.first)
        assertEquals("universal", u.apkFor(listOf("x86_64"))!!.first, "no file for this ABI: the universal one")
        assertNull(u.copy(apks = mapOf("arm64-v8a" to u.apks.getValue("arm64-v8a"))).apkFor(listOf("x86_64")))
    }

    @Test
    fun aBrokenOrHostileFileIsRefusedWithTheKeyAtFault() {
        fun refused(text: String, key: String) {
            val e = assertThrows(IllegalArgumentException::class.java) { AppUpdate.parse(text) }
            assertTrue(e.message!!.contains(key), "${e.message} should name $key")
        }
        refused("[1]", "not a JSON object")
        refused(good.replace("beamline-update/1", "beamline-update/2"), "format")
        refused(good.replace("\"version_code\":28", "\"version_code\":\"28\""), "version_code")
        refused(good.replace("\"version_code\":28", "\"version_code\":0"), "version_code")
        refused(good.replace("beamline-0.1.25-arm64.apk", "../evil.apk"), "apks.arm64-v8a.file")
        refused(good.replace("beamline-0.1.25.apk", "beamline.exe"), "apks.universal.file")
        refused(good.replace("26000000", "0"), "apks.arm64-v8a.size")
        refused(good.replace("85000000", "900000000"), "apks.universal.size")
        refused(good.replace("\"sha256\":\"$sha\"}}}", "\"sha256\":\"${"A".repeat(64)}\"}}}"), "apks.universal.sha256")
        refused(good.substringBefore("\"apks\"") + "\"apks\":{}}", "apks is empty")
    }
}
