package io.github.spysyweeb.beamline.routing.demo

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.target
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.writeResult
import io.github.spysyweeb.beamline.routing.mobile.ValhallaEngine
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.packs.PackSource
import io.github.spysyweeb.beamline.routing.packs.RoutingPackManager
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.RoutePlanner
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The pack manager on the device: install the real PA pack (pushed to files/packs-routing/pa by
 * device.py push --routing-pack), open the engine on the result and route, remove it. Timed.
 */
@RunWith(AndroidJUnit4::class)
class PackInstallOnDeviceTest {
    @Test
    fun installRouteRemovePa() {
        val src = File(DemoFiles.root(target), "packs-routing/pa")
        assumeTrue("PA routing pack not pushed", File(src, "manifest.json").isFile)
        val out = JSONObject()
        for (verifyTar in listOf(true, false)) {
            val dir = File(target.filesDir, "routing-device-test").apply { deleteRecursively() }
            val m = RoutingPackManager(dir, ValhallaEngine.VALHALLA_VERSION, verifyTarHash = verifyTar)
            var t0 = SystemClock.elapsedRealtime()
            val r = m.install(listOf(PackSource.fromDir(src)))
            val installMs = SystemClock.elapsedRealtime() - t0
            assertEquals(286, r.tilesInPacks)
            val routeOk = ValhallaEngine.open(m.engineConfigFile).use { e ->
                RoutePlanner(e, coverage = m.coverage()).plan(RouteRequest(DeviceTestSupport.trip("I1"))) is PlanResult.Ok
            }
            t0 = SystemClock.elapsedRealtime()
            m.uninstall("pa")
            val removeMs = SystemClock.elapsedRealtime() - t0
            out.put(if (verifyTar) "with_tar_hash" else "without_tar_hash", JSONObject().put("install_ms", installMs)
                .put("remove_ms", removeMs).put("routes_after_install", routeOk).put("tileset_bytes", r.tilesetBytes))
            assertEquals(true, routeOk)
            dir.deleteRecursively()
        }
        writeResult("pack-install-device.json", out)
    }
}
