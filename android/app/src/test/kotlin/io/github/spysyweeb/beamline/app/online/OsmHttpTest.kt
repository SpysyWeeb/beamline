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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Network rule N1-N4 for OsmHttp (docs/online-design.md section 6, batch O1): the switch refuses
 * every request before any connection; the note request's exact shape; no cookies; only fixed or
 * public hosts. Also the outbox's sending (4.5) against the same local server.
 */
class OsmHttpTest {

    private class Seen(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    private var answerStatus = 200
    private var nextId = 4000L
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            seen += Seen(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery, headers, body)
            val answer = if (answerStatus == 200) {
                """{"type":"Feature","geometry":{"type":"Point","coordinates":[-80.08,42.12]},"properties":{"id":${nextId++},"status":"open"}}"""
            } else {
                "error"
            }
            ex.responseHeaders.add("Set-Cookie", "session=abc; Path=/")
            ex.responseHeaders.add("Content-Type", "application/json")
            val bytes = answer.toByteArray()
            ex.sendResponseHeaders(answerStatus, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }
    private val local = OsmServer("http://127.0.0.1:${server.address.port}", "https://web.test", local = true)

    @AfterEach
    fun stop() = server.stop(0)

    private fun http(allowed: () -> Boolean) = OsmHttp(local, "0.1.10", allowed)

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    @Test
    fun switchOffRefusesBeforeAnyConnection() {
        var on = false
        val h = http { on }
        assertThrows<OnlineRefused> { h.createNote(42.1, -80.1, "x") }
        assertTrue(seen.isEmpty(), "the server saw nothing")
        on = true
        assertEquals(4000L, h.createNote(42.1, -80.1, "x"))
        assertEquals(1, seen.size)
        on = false
        assertThrows<OnlineRefused> { h.createNote(42.1, -80.1, "y") }
        assertEquals(1, seen.size)
    }

    @Test
    fun theNoteRequestHasExactlyItsShape() {
        val h = http { true }
        val id = h.createNote(42.1301234567, -80.0851, "Closed: the shop is gone\n\nvia Beamline")
        assertEquals(4000L, id)
        val r = seen.single()
        assertEquals("POST", r.method)
        assertEquals("/api/0.6/notes.json", r.path)
        assertNull(r.query, "nothing in the URL: the text and point go in the body")
        assertEquals(
            mapOf("lat" to "42.1301235", "lon" to "-80.0851000", "text" to "Closed: the shop is gone\n\nvia Beamline"),
            form(r.body),
        )
        assertEquals("Beamline/0.1.10", r.headers["user-agent"])
        assertEquals("application/json", r.headers["accept"])
        assertEquals("identity", r.headers["accept-encoding"])
        assertEquals(
            setOf("user-agent", "accept", "accept-encoding", "content-type", "content-length", "host", "connection"),
            r.headers.keys,
            "no cookie, no authorization, no identifier",
        )
        // A second request after the server's Set-Cookie sends none back.
        h.createNote(42.0, -80.0, "x")
        assertFalse(seen[1].headers.containsKey("cookie"))
    }

    @Test
    fun anErrorStatusIsAnOsmErrorWithTheStatusOnly() {
        answerStatus = 403
        val e = assertThrows<OsmError> { http { true }.createNote(42.0, -80.0, "secret words") }
        assertEquals(403, e.status)
        assertTrue(e.final)
        assertFalse(e.message!!.contains("secret"))
        assertFalse(OsmError(429).final)
        assertFalse(OsmError(503).final)
    }

    @Test
    fun onlyTheFixedHostsAndPublicAddresses() {
        assertEquals("master.apis.dev.openstreetmap.org", OsmServer.TEST.host)
        assertEquals("api.openstreetmap.org", OsmServer.LIVE.host)
        assertEquals(OsmServer.LIVE, OsmServer.forBuild("live"))
        assertEquals(OsmServer.TEST, OsmServer.forBuild("test"))
        assertEquals(OsmServer.TEST, OsmServer.forBuild(""))
        assertEquals("https://www.openstreetmap.org/note/12", OsmServer.LIVE.noteLink(12))
        assertThrows<IllegalArgumentException> { OsmHttp(OsmServer("https://example.com", "https://example.com"), "1", { true }) }
        // The test server's name answered with a private address: refused before any connection.
        val h = OsmHttp(OsmServer.TEST, "1", { true }, resolve = { listOf(InetAddress.getByName("10.200.0.5")) })
        val e = assertThrows<IOException> { h.createNote(42.0, -80.0, "x") }
        assertFalse(e is OsmError)
    }

    @Test
    fun noteIdAndText() {
        assertEquals(17L, OsmHttp.noteId("""{"type":"Feature","properties":{"id":17}}"""))
        assertNull(OsmHttp.noteId("<osm/>"))
        assertEquals("Closed: gone", NoteText.compose("Closed", " gone "))
        assertEquals("Closed", NoteText.compose("Closed", " "))
        assertEquals("words", NoteText.compose(null, "words"))
        assertEquals("words\n\nvia Beamline", NoteText.forSending("words "))
        assertEquals("1.0000000", OsmHttp.coordinate(1.0))
    }

    @Test
    fun theOutboxSendsWaitingNotesAndKeepsTheirNumbers(@TempDir dir: File) = runBlocking {
        var on = true
        val store = OutboxStore(File(dir, OutboxStore.FILE_NAME))
        store.add(OutboxItem("a", 42.1, -80.1, "first", null, createdMs = 1))
        store.add(OutboxItem("b", 42.2, -80.2, "second", "Perry Square", createdMs = 2))
        val sender = OutboxSender(store, http { on }, local, { on }, Dispatchers.IO)
        // Switch off: nothing leaves, both wait.
        on = false
        assertEquals(SendResult(0, 0, 2, false), sender.sendWaiting())
        assertTrue(seen.isEmpty())
        on = true
        assertEquals(SendResult(2, 0, 0, false), sender.sendWaiting())
        assertEquals(listOf("first\n\nvia Beamline", "second\n\nvia Beamline"), seen.map { form(it.body)["text"] }, "oldest first")
        val a = store.byId("a")!!
        assertEquals(OutboxState.SENT, a.state)
        assertEquals(4000L, a.noteId)
        assertEquals("https://web.test/note/4000", a.link)
        assertFalse(a.editable)
        assertFalse(store.change("a", "changed"), "a sent note cannot be changed")
        // Read back from the file.
        val again = OutboxStore(File(dir, OutboxStore.FILE_NAME))
        assertEquals(store.items.value, again.items.value)
        // A refused note: kept, marked, can be changed and waits again.
        answerStatus = 400
        store.add(OutboxItem("c", 42.3, -80.3, "third", null, createdMs = 3))
        assertEquals(SendResult(0, 1, 0, false), sender.sendWaiting())
        assertEquals(OutboxState.REFUSED, store.byId("c")!!.state)
        assertEquals(400, store.byId("c")!!.status)
        assertTrue(store.change("c", "third, better"))
        assertEquals(OutboxState.WAITING, store.byId("c")!!.state)
        // No connection: the note waits.
        server.stop(0)
        val r = sender.sendWaiting()
        assertTrue(r.offline)
        assertEquals(1, r.left)
        store.delete("c")
        assertNull(store.byId("c"))
    }

    @Test
    fun anOutboxItemPrintsNeitherItsTextNorItsPoint() {
        val s = OutboxItem("id1", 42.123, -80.456, "secret words", "Somewhere", 1).toString()
        assertFalse(s.contains("secret") || s.contains("42.1") || s.contains("80.4") || s.contains("Somewhere"))
    }

    /** The audit of 2026-11-21: the same note is not put on the map twice. */
    @Test
    fun theSameNoteIsNotAddedTwice(@TempDir dir: File) {
        val store = OutboxStore(File(dir, OutboxStore.FILE_NAME))
        assertTrue(store.add(OutboxItem("a", 42.1, -80.1, "The road is closed here", null, createdMs = 1_000)))
        assertFalse(store.add(OutboxItem("b", 42.1, -80.1, " The road is closed here ", null, createdMs = 2_000)), "the caller is told")
        assertEquals(listOf("a"), store.items.value.map { it.id }, "the same words at the same point while the first waits")
        store.add(OutboxItem("c", 42.1, -80.1, "The bridge is out", null, createdMs = 3_000))
        store.add(OutboxItem("d", 42.2, -80.1, "The road is closed here", null, createdMs = 4_000))
        assertEquals(listOf("d", "c", "a"), store.items.value.map { it.id }, "other words, or another point: another note")
        // adding an item again by its own id replaces it (a change), as before
        store.add(OutboxItem("a", 42.1, -80.1, "The road is closed here", "Elm Street", createdMs = 1_000))
        assertEquals("Elm Street", store.byId("a")!!.placeName)
        // once sent: not again within ten minutes of it, again after that
        val sent = OutboxStore(File(dir, "sent.json"))
        sent.add(OutboxItem("s", 42.1, -80.1, "Pothole", null, createdMs = 1_000, state = OutboxState.SENT, sentMs = 5_000))
        assertFalse(sent.add(OutboxItem("t", 42.1, -80.1, "Pothole", null, createdMs = 5_000 + OutboxStore.SAME_NOTE_MS)))
        assertTrue(sent.add(OutboxItem("u", 42.1, -80.1, "Pothole", null, createdMs = 5_001 + OutboxStore.SAME_NOTE_MS)))
        // a refused note does not hold a new one back
        val refused = OutboxStore(File(dir, "refused.json"))
        refused.add(OutboxItem("r", 42.1, -80.1, "Pothole", null, createdMs = 1_000, state = OutboxState.REFUSED, status = 400))
        assertTrue(refused.add(OutboxItem("v", 42.1, -80.1, "Pothole", null, createdMs = 2_000)))
    }

    @Test
    fun aBrokenOutboxFileIsKeptAsideAndTheOutboxStartsEmpty(@TempDir dir: File) {
        File(dir, OutboxStore.FILE_NAME).writeText("{not json")
        assertTrue(OutboxStore(File(dir, OutboxStore.FILE_NAME)).items.value.isEmpty())
        assertTrue(File(dir, OutboxStore.FILE_NAME + ".broken").isFile)
    }
}
