package io.github.spysyweeb.beamline.app.online

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The speed limit of a road (docs/next-round.md item 32, docs/online-design.md 4.7) against a local
 * stand-in for OSM's API: which way is the road at a point, the fix's exact requests, a limit picked
 * while driving that waits for its confirmation, and that a road's edit is no place of the user's own.
 *
 * The stand-in's map: Main Street runs east-west through (40.0, -77.0) as way 500 (three nodes, 35 mph)
 * and way 501 (its next stretch, no limit); Oak Lane (way 502) meets it at the middle node; a service
 * road (way 503) lies nearest of all to the point asked for.
 */
class SpeedLimitTest {
    private class Seen(val method: String, val path: String, val query: String?, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    @Volatile private var wayVersion = 4
    @Volatile private var wayTags = """"highway":"secondary","name":"Main Street","maxspeed":"35 mph""""
    @Volatile private var mapStatus = 200

    private fun map() = """{"version":"0.6","elements":[
        {"type":"node","id":1,"lat":40.0,"lon":-77.0020},{"type":"node","id":2,"lat":40.0,"lon":-77.0},
        {"type":"node","id":3,"lat":40.0,"lon":-76.9980},{"type":"node","id":4,"lat":40.0,"lon":-76.9960},
        {"type":"node","id":5,"lat":40.0010,"lon":-77.0},{"type":"node","id":6,"lat":40.00002,"lon":-77.0006},
        {"type":"node","id":7,"lat":39.9995,"lon":-77.0006},
        {"type":"way","id":500,"version":$wayVersion,"nodes":[1,2,3],"tags":{$wayTags}},
        {"type":"way","id":501,"version":2,"nodes":[3,4],"tags":{"highway":"secondary","name":"Main Street"}},
        {"type":"way","id":502,"version":1,"nodes":[2,5],"tags":{"highway":"residential","name":"Oak Lane","maxspeed":"25 mph"}},
        {"type":"way","id":503,"version":1,"nodes":[6,7],"tags":{"highway":"service"}}
    ]}"""

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.path
            seen += Seen(ex.requestMethod, path, ex.requestURI.query, body)
            val (status, answer) = when {
                path == "/api/0.6/map.json" -> mapStatus to (if (mapStatus == 200) map() else "")
                path == "/api/0.6/way/500.json" -> 200 to """{"version":"0.6","elements":[{"type":"way","id":500,"version":$wayVersion,"nodes":[1,2,3],"tags":{$wayTags}}]}"""
                path == "/api/0.6/changeset/create" -> 200 to "910"
                path == "/api/0.6/way/500" -> 200 to (wayVersion + 1).toString()
                path == "/api/0.6/notes.json" -> 200 to """{"type":"Feature","properties":{"id":55}}"""
                path.endsWith("/close") -> 200 to ""
                else -> 404 to ""
            }
            val bytes = answer.toByteArray()
            ex.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
        start()
    }
    private val local = OsmServer("http://127.0.0.1:${server.address.port}", "https://web.test", local = true)
    private val http = OsmHttp(local, "0.1.19", { true })

    @AfterEach
    fun stop() {
        runCatching { server.stop(0) }
    }

    private fun sender(store: OutboxStore) = OutboxSender(store, http, local, { true }, Dispatchers.IO) { OsmAccount("T0KEN", "Test Mapper") }

    private suspend fun SpeedSession.settle(vararg busy: SpeedPhase) {
        var n = 0
        while (phase in busy && n++ < 500) delay(10)
    }

    @Test
    fun theRoadAtAPoint() {
        // on Main Street, 60 m east of the crossing: the service road's end is nearer but is no road with a posted limit
        val r = SpeedLimits.roadAt(map(), 40.00003, -77.0007, null)!!
        assertEquals("w500", r.ref.toString())
        assertEquals(4, r.version)
        assertEquals("Main Street", r.name)
        assertTrue(r.distanceM < 5.0)
        assertEquals(341.0, r.lengthM, 3.0)
        // at the corner, a little way up Oak Lane: the nearest way, unless the driver was on Main Street
        assertEquals("w502", SpeedLimits.roadAt(map(), 40.00008, -77.0, null)!!.ref.toString())
        assertEquals("w500", SpeedLimits.roadAt(map(), 40.00008, -77.0, "main street")!!.ref.toString())
        // the next stretch of the same road is its own way
        assertEquals("w501", SpeedLimits.roadAt(map(), 40.00001, -76.9970, "Main Street")!!.ref.toString())
        // 60 m off every road, and an answer that is no map
        assertNull(SpeedLimits.roadAt(map(), 40.0006, -77.0012, null))
        assertNull(SpeedLimits.roadAt("not json", 40.0, -77.0, null))
        // a way one of whose points did not come is not measured (its line would be a wrong one)
        assertNull(SpeedLimits.roadAt(map().replace("""{"type":"node","id":2,"lat":40.0,"lon":-77.0},""", ""), 40.00003, -77.0007, null)
            ?.takeIf { it.ref.toString() == "w500" })
    }

    @Test
    fun theWordsOfALimit() {
        assertEquals("45 mph", SpeedLimits.tag(45))
        assertEquals(35, SpeedLimits.mphOf("35 mph"))
        assertEquals(35, SpeedLimits.mphOf("35mph"))
        assertNull(SpeedLimits.mphOf("50"))
        assertEquals("35 mph", SpeedLimits.shown("35 mph"))
        assertEquals("50 km/h", SpeedLimits.shown("50"))
        assertEquals("not set", SpeedLimits.shown(null))
        assertEquals("signals", SpeedLimits.shown("signals"))
        assertEquals("Set the speed limit of Main Street to 45 mph", SpeedLimits.comment("Main Street", 45))
        assertEquals("Set the speed limit of a road to 45 mph", SpeedLimits.comment(null, 45))
        assertEquals("1100 feet", OnlineTexts.stretch(341.0))
        assertEquals("0.8 miles", OnlineTexts.stretch(1300.0))
        assertEquals("50 feet", OnlineTexts.stretch(6.0))
    }

    @Test
    fun aFixGoesAsOneChangesetOfThatWay(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val s = SpeedSession(40.00003, -77.0007, null, null, null, http, store, sender(store), this, Dispatchers.IO)
        s.load()
        s.settle(SpeedPhase.LOADING)
        assertEquals(SpeedPhase.PICK, s.phase)
        assertEquals("Main Street", s.road?.name)
        assertEquals("35 mph", s.current)
        // Next without a choice, and with the limit OSM already has, stays
        s.summary()
        assertEquals(OnlineTexts.SPEED_PICK_FIRST, s.notice)
        s.pick(35)
        s.summary()
        assertEquals(OnlineTexts.SPEED_SAME, s.notice)
        assertEquals(SpeedPhase.PICK, s.phase)
        s.pick(45)
        s.summary()
        assertEquals(SpeedPhase.SUMMARY, s.phase)
        assertEquals(listOf("Road: Main Street, a stretch of about 1100 feet", "Speed limit: 35 mph → 45 mph"), s.lines)
        assertTrue(seen.none { it.method == "PUT" }, "nothing is sent before Upload")
        s.upload()
        s.settle(SpeedPhase.UPLOADING)
        assertEquals(SpeedPhase.DONE, s.phase)
        s.upload()   // a second tap sends nothing more
        s.settle(SpeedPhase.UPLOADING)
        val puts = seen.filter { it.method == "PUT" }
        assertEquals(listOf("/api/0.6/changeset/create", "/api/0.6/way/500", "/api/0.6/changeset/910/close"), puts.map { it.path })
        assertTrue("Set the speed limit of Main Street to 45 mph" in puts[0].body)
        assertFalse("source" in puts[0].body)
        val way = puts[1].body
        assertTrue("""k="maxspeed" v="45 mph"""" in way, way)
        assertTrue("""k="name" v="Main Street"""" in way)
        assertTrue(Regex("""<nd ref="1"\s*/>\s*<nd ref="2"\s*/>\s*<nd ref="3"\s*/>""").containsMatchIn(way), "the way's nodes go back unchanged")
        assertTrue("""version="4"""" in way)
        val item = store.items.value.single()
        assertEquals(OutboxState.SENT, item.state)
        assertEquals(45, item.speedMph)
        assertTrue(item.edit!!.road)
        assertEquals("Speed limit", OnlineTexts.kind(item))
        // a road's edit is no place of the user's own: nothing is drawn or found for it
        assertEquals(emptyList<Any>(), OwnChanges.of(store.items.value, emptyList()) { null })
        // and it is read back from the file as it was
        val again = OutboxStore(File(dir, "o.json")).items.value.single()
        assertEquals(45, again.speedMph)
        assertTrue(again.edit!!.road)
        assertEquals(mapOf<String, String?>("maxspeed" to "45 mph"), again.edit!!.changes)
    }

    @Test
    fun aLimitPickedWhileDrivingWaitsForItsConfirmation(@TempDir dir: File) = runBlocking {
        val file = File(dir, "o.json")
        val store = OutboxStore(file)
        val pick = OutboxItem("p1", 40.00003, -77.0007, OnlineTexts.speedPicked(45), "Main Street", createdMs = 1, state = OutboxState.REVIEW, speedMph = 45)
        assertTrue(store.add(pick))
        // a second pick at the same spot is its own item (not held back as a note said twice)
        assertTrue(store.add(pick.copy(id = "p2", createdMs = 2)))
        store.delete("p2")
        assertEquals("Speed limit", OnlineTexts.kind(pick))
        assertEquals("Picked while driving: open it to check the road and send it", OnlineTexts.state(pick, sending = false))
        // nothing of it goes to OSM by itself: not as a note, not as an edit
        sender(store).sendWaiting()
        assertTrue(seen.isEmpty(), seen.joinToString { it.path })
        // not even if it stood there as waiting, and it cannot be changed into a note that waits (the review of 2026-11-21)
        assertFalse(store.change("p1", "Speed limit 45 mph"))
        assertEquals(OutboxState.REVIEW, store.byId("p1")!!.state)
        val waiting = OutboxStore(File(dir, "w.json"))
        waiting.add(pick.copy(id = "w1", state = OutboxState.WAITING))
        sender(waiting).sendWaiting()
        assertTrue(seen.isEmpty(), "a waiting pick is no note: " + seen.joinToString { it.path })
        assertEquals(OutboxState.WAITING, waiting.byId("w1")!!.state)
        val kept = OutboxStore(file).items.value.single()
        assertEquals(OutboxState.REVIEW, kept.state)
        assertEquals(45, kept.speedMph)
        assertNull(kept.edit)
        // opened when stopped: the road is read, the limit is the one picked, and Upload replaces the pick with the fix
        val s = SpeedSession(kept.lat, kept.lon, kept.placeName, kept.speedMph, kept.id, http, store, sender(store), this, Dispatchers.IO)
        s.load()
        s.settle(SpeedPhase.LOADING)
        assertEquals(45, s.mph)
        assertEquals(SpeedPhase.PICK, s.phase)
        s.summary()
        assertEquals(SpeedPhase.SUMMARY, s.phase)
        s.upload()
        s.settle(SpeedPhase.UPLOADING)
        assertEquals(SpeedPhase.DONE, s.phase)
        val item = store.items.value.single()
        assertEquals("p1", item.id)
        assertEquals(OutboxState.SENT, item.state)
        assertNotNull(item.edit)
    }

    @Test
    fun noRoadNoConnectionAndARoadChangedMeanwhile(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val off = SpeedSession(40.0006, -77.0012, null, null, null, http, store, sender(store), this, Dispatchers.IO)
        off.load()
        off.settle(SpeedPhase.LOADING)
        assertEquals(SpeedPhase.NO_ROAD, off.phase)
        mapStatus = 503
        val down = SpeedSession(40.00003, -77.0007, null, null, null, http, store, sender(store), this, Dispatchers.IO)
        down.load()
        down.settle(SpeedPhase.LOADING)
        assertEquals(SpeedPhase.OFFLINE, down.phase)
        mapStatus = 200
        // somebody changes the way between the summary and the upload: the fix comes back to be checked again
        val s = SpeedSession(40.00003, -77.0007, null, null, null, http, store, sender(store), this, Dispatchers.IO)
        s.load()
        s.settle(SpeedPhase.LOADING)
        s.pick(45)
        s.summary()
        wayVersion = 5
        wayTags = """"highway":"secondary","name":"Main Street","maxspeed":"40 mph""""
        s.upload()
        s.settle(SpeedPhase.UPLOADING, SpeedPhase.LOADING)
        assertEquals(SpeedPhase.PICK, s.phase)
        assertEquals(OnlineTexts.SPEED_CONFLICT, s.notice)
        assertEquals("40 mph", s.current)
        assertTrue(seen.none { it.method == "PUT" && it.path == "/api/0.6/way/500" }, "not uploaded over the newer version")
    }
}
