package io.github.spysyweeb.beamline.app.online

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Batch O3 (docs/online-design.md 4.3, 4.5; docs/next-round.md item 25) against a local stand-in for OSM's
 * API: the map request, a new node in one changeset at the item's point, the outbox for additions (the
 * duplicate check again at send time, offline waiting), a replacement made again from the place as it is
 * at send time; and the O2 review's carry-overs: the revoke request, a redirect of another sign-in ignored.
 */
class OsmAddTest {
    private class Seen(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    @Volatile private var map = EMPTY_MAP
    @Volatile private var down = false
    @Volatile private var tags42 = mapOf("shop" to "clothes", "name" to "Old Threads", "brand" to "Threads", "addr:housenumber" to "421", "building" to "retail")

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.path
            seen += Seen(ex.requestMethod, path, ex.requestURI.rawQuery, headers, body)
            val (status, answer) = when {
                down -> 503 to ""
                path == "/oauth2/revoke" -> 200 to ""
                path == "/api/0.6/map.json" -> 200 to map
                path == "/api/0.6/changeset/create" -> 200 to "901"
                path == "/api/0.6/node/create" -> 200 to "4000"
                path == "/api/0.6/node/42.json" -> 200 to """{"version":"0.6","elements":[{"type":"node","id":42,"lat":42.1,"lon":-80.1,"version":5,""" +
                    """"tags":{${tags42.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}}]}"""
                path == "/api/0.6/node/42" -> 200 to "6"
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
    private val http = OsmHttp(local, "0.1.12", { true })

    @TempDir lateinit var dir: File

    @AfterEach
    fun stop() {
        runCatching { server.stop(0) }
    }

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    private fun addItem(decided: Set<String> = emptySet()) = OutboxItem(
        "add1", 42.1291546, -80.0852982, "A new pizza", "Joe's Pizza", 1L,
        add = AddData("pizza", linkedMapOf("amenity" to "restaurant", "cuisine" to "pizza", "name" to "Joe's Pizza"), "Added pizza Joe's Pizza", decided),
    )

    @Test
    fun theMapRequestAsksOnlyForTheSmallBox() {
        http.map(SpotCheck.box(42.13, -80.08))
        val s = seen.single()
        assertEquals("GET", s.method)
        assertEquals("/api/0.6/map.json", s.path)
        val bbox = s.query!!.removePrefix("bbox=").split(',').map { URLDecoder.decode(it, "UTF-8").toDouble() }
        assertTrue(bbox[2] - bbox[0] < 0.002 && bbox[3] - bbox[1] < 0.002, "about 120 m across")
        assertNull(s.headers["authorization"], "no account needed")
    }

    @Test
    fun aNewPlaceGoesAsOneChangesetWithOneNodeAtThePressedPoint() = runBlocking {
        val store = OutboxStore(File(dir, OutboxStore.FILE_NAME))
        store.add(addItem())
        val sender = OutboxSender(store, http, local, { true }, Dispatchers.IO) { OsmAccount("T0KEN", "Test Mapper") }
        val r = sender.sendWaiting()
        assertEquals(1, r.sent)
        assertEquals(listOf("/api/0.6/map.json", "/api/0.6/changeset/create", "/api/0.6/node/create", "/api/0.6/changeset/901/close"), seen.map { it.path })
        val cs = seen[1].body
        assertTrue(cs.contains("k=\"comment\" v=\"Added pizza Joe&apos;s Pizza\""), cs)
        assertTrue(cs.contains("k=\"created_by\" v=\"Beamline 0.1.12\"") && !cs.contains("k=\"source\""))
        val node = seen[2].body
        assertTrue(node.contains("lat=\"42.1291546\" lon=\"-80.0852982\""), "the item's point")
        assertTrue(node.contains("<tag k=\"amenity\" v=\"restaurant\"/>") && node.contains("<tag k=\"cuisine\" v=\"pizza\"/>"))
        assertFalse(node.contains(" id=\""), "a new node has no id")
        assertEquals("Bearer T0KEN", seen[2].headers["authorization"])
        val sent = store.byId("add1")!!
        assertEquals(OutboxState.SENT, sent.state)
        assertEquals(4000L, sent.add!!.node)
        assertEquals("https://web.test/node/4000", sent.link)
        // kept across a restart
        assertEquals(4000L, OutboxStore(File(dir, OutboxStore.FILE_NAME)).byId("add1")!!.add!!.node)
    }

    @Test
    fun aDuplicateAddedMeanwhileMakesItWaitForTheUser() = runBlocking {
        map = """{"version":"0.6","elements":[{"type":"node","id":9,"lat":42.1292,"lon":-80.0853,"version":1,"tags":{"amenity":"restaurant","cuisine":"pizza","name":"Joes Pizza"}}]}"""
        val store = OutboxStore(File(dir, OutboxStore.FILE_NAME))
        store.add(addItem())
        val sender = OutboxSender(store, http, local, { true }, Dispatchers.IO) { OsmAccount("T0KEN", "Test Mapper") }
        val r = sender.sendWaiting()
        assertEquals(1, r.review)
        assertEquals(OutboxState.REVIEW, store.byId("add1")!!.state)
        assertEquals(listOf("/api/0.6/map.json"), seen.map { it.path }, "nothing written")
        // the user answered "It's a different place" about it: then it goes
        seen.clear()
        store.add(addItem(decided = setOf("n9")))
        assertEquals(1, sender.sendWaiting().sent)
    }

    @Test
    fun offlineOrSignedOutTheAdditionWaits() = runBlocking {
        val store = OutboxStore(File(dir, OutboxStore.FILE_NAME))
        store.add(addItem())
        val signedOut = OutboxSender(store, http, local, { true }, Dispatchers.IO) { null }
        assertEquals(1, signedOut.sendWaiting().left)
        assertTrue(seen.isEmpty(), "signed out: nothing sent")
        down = true
        val sender = OutboxSender(store, http, local, { true }, Dispatchers.IO) { OsmAccount("T0KEN", "Test Mapper") }
        val r = sender.sendWaiting()
        assertTrue(r.offline)
        assertEquals(OutboxState.WAITING, store.byId("add1")!!.state)
        down = false
        assertEquals(1, sender.sendWaiting().sent, "goes by itself when the connection is back")
        val off = OutboxSender(store, http, local, { false }, Dispatchers.IO) { OsmAccount("T0KEN", "Test Mapper") }
        store.add(addItem().copy(id = "add2"))
        val before = seen.size
        off.sendWaiting()
        assertEquals(before, seen.size, "the switch off: nothing leaves")
    }

    @Test
    fun aReplacementIsMadeAgainFromThePlaceAsItIsAtSendTime() = runBlocking {
        val next = mapOf("amenity" to "restaurant", "name" to "Joe's Pizza", "cuisine" to "pizza")
        val store = OutboxStore(File(dir, OutboxStore.FILE_NAME))
        // made offline from the pack's values (no version): OSM meanwhile gained a phone of the old business
        tags42 = tags42 + ("phone" to "+1 814 555 0100")
        store.add(OutboxItem("rep", 42.1, -80.1, "Replaces Old Threads", "Old Threads", 1L,
            edit = EditData("n42", null, mapOf("name" to "Old Threads"), BusinessTags.replace(mapOf("name" to "Old Threads", "shop" to "clothes"), next),
                "Replaced Old Threads with restaurant Joe's Pizza", replaceWith = next, packOsm = "n42")))
        val sender = OutboxSender(store, http, local, { true }, Dispatchers.IO) { OsmAccount("T0KEN", "Test Mapper") }
        assertEquals(1, sender.sendWaiting().sent)
        val put = seen.first { it.path == "/api/0.6/node/42" }.body
        assertFalse(put.contains("k=\"phone\""), "the old business's phone added meanwhile goes too")
        assertFalse(put.contains("k=\"brand\"") || put.contains("k=\"shop\""))
        assertTrue(put.contains("<tag k=\"addr:housenumber\" v=\"421\"/>") && put.contains("<tag k=\"building\" v=\"retail\"/>"), "address and building stay")
        assertTrue(put.contains("<tag k=\"name\" v=\"Joe&apos;s Pizza\"/>") && put.contains("<tag k=\"amenity\" v=\"restaurant\"/>"))
        assertTrue(put.contains("version=\"5\""), "uploaded on the version read at send time")
    }

    @Test
    fun theOutboxKeepsAdditionsAndReplacementsAcrossARestart() {
        val f = File(dir, OutboxStore.FILE_NAME)
        val store = OutboxStore(f)
        store.add(addItem(decided = setOf("n9", "w3")))
        store.add(OutboxItem("rep", 1.0, 2.0, "t", "Old", 2L, edit = EditData("n42", 5, emptyMap(), mapOf("shop" to null), "c",
            replaceWith = mapOf("amenity" to "cafe"), packOsm = "n4242", icon = "cafe")))
        val again = OutboxStore(f)
        val a = again.byId("add1")!!.add!!
        assertEquals("pizza", a.typeCode)
        assertEquals(setOf("n9", "w3"), a.decided)
        assertEquals(mapOf("amenity" to "restaurant", "cuisine" to "pizza", "name" to "Joe's Pizza"), a.tags)
        val e = again.byId("rep")!!.edit!!
        assertEquals(mapOf("amenity" to "cafe"), e.replaceWith)
        assertEquals("n4242", e.packOsm)
        assertEquals("cafe", e.icon)
        assertTrue(f.readText().contains("Joe's Pizza"), "kept in the outbox file (no_backup)")
        assertEquals("OutboxItem(state=WAITING)", again.byId("add1").toString(), "toString prints none of it (PV2)")
        assertEquals("AddData(3 tags)", a.toString())
    }

    // ---------------------------------------------------------------- the O2 review's carry-overs

    @Test
    fun theRevokeRequest() {
        http.revoke("cid", "T0KEN")
        val s = seen.single()
        assertEquals("POST", s.method)
        assertEquals("/oauth2/revoke", s.path)
        assertEquals(mapOf("token" to "T0KEN", "token_type_hint" to "access_token", "client_id" to "cid"), form(s.body))
        assertNull(s.headers["authorization"], "the token goes in the body, as RFC 7009 says, not as a bearer")
        val off = OsmHttp(local, "0.1.12", { false })
        assertThrows<OnlineRefused> { off.revoke("cid", "T0KEN") }
        assertEquals(1, seen.size)
    }

    @Test
    fun aRedirectOfAnotherSignInIsIgnoredAndLeavesTheOpenOneOpen() {
        val client = OsmClient("cid", OsmClient.REDIRECT_URI)
        val s = SignIn("st4te", "verif", 1000L)
        val other = OsmClient.REDIRECT_URI + "?code=abc&state=other"
        assertFalse(s.answers(other, client), "another state: not this sign-in's answer")
        assertFalse(s.answers("https://evil.example/oauth?code=abc&state=st4te", client))
        assertTrue(s.answers(OsmClient.REDIRECT_URI + "?code=abc&state=st4te", client))
        assertTrue(s.answers(OsmClient.REDIRECT_URI + "?error=access_denied&state=st4te", client), "Deny answers it (and ends it)")
        assertEquals("abc", s.accept(OsmClient.REDIRECT_URI + "?code=abc&state=st4te", client, 2000L))
    }

    companion object {
        private const val EMPTY_MAP = """{"version":"0.6","elements":[]}"""
    }
}
