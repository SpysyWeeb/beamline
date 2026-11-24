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
 * Batch O2 (docs/online-design.md 4.1, 4.2, 4.4, 4.5) against a local stand-in for OSM's API: the
 * sign-in's token request, the user's name, notes under the name, an edit's exact requests, the
 * conflict (409) and the changed-meanwhile paths, the outbox for edits, Closed permanently's tags.
 */
class OsmEditTest {
    private class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    /** The node on the stand-in server. */
    @Volatile private var version = 3
    @Volatile private var tags = mapOf("amenity" to "cafe", "name" to "Test Cafe", "opening_hours" to "Mo-Fr 08:00-18:00", "phone" to "+1 814 555 0100")
    @Volatile private var putStatus = 200
    private var nextChangeset = 900L

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            val headers = ex.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") }
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.path
            seen += Seen(ex.requestMethod, path, headers, body)
            val (status, answer) = when {
                path == "/oauth2/token" -> 200 to """{"access_token":"T0KEN","token_type":"Bearer","scope":"read_prefs write_api write_notes"}"""
                path == "/oauth2/revoke" -> 200 to "{}"
                path == "/api/0.6/user/details.json" -> 200 to """{"version":"0.6","user":{"id":7,"display_name":"Test Mapper"}}"""
                path == "/api/0.6/notes.json" -> 200 to """{"type":"Feature","properties":{"id":55}}"""
                path == "/api/0.6/node/42.json" -> 200 to """{"version":"0.6","elements":[{"type":"node","id":42,"lat":42.1,"lon":-80.1,"version":$version,""" +
                    """"tags":{${tags.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}}]}"""
                path == "/api/0.6/changeset/create" -> 200 to (nextChangeset++).toString()
                path == "/api/0.6/node/42" -> if (putStatus == 200) 200 to (version + 1).toString() else putStatus to "Version mismatch"
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
    private val http = OsmHttp(local, "0.1.11", { true })

    @AfterEach
    fun stop() {
        runCatching { server.stop(0) }
    }

    private fun form(body: String): Map<String, String> = body.split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

    @Test
    fun theTokenRequestUsesTheVerifierAndNoSecret() {
        assertEquals("T0KEN", http.token("cid", "c0de", "verif", OsmClient.REDIRECT_URI))
        val s = seen.single()
        assertEquals("POST", s.method)
        assertEquals(
            mapOf("grant_type" to "authorization_code", "code" to "c0de", "redirect_uri" to "io.github.spysyweeb.beamline://oauth", "client_id" to "cid", "code_verifier" to "verif"),
            form(s.body),
        )
        assertNull(s.headers["authorization"])
        assertEquals("Test Mapper", http.userName("T0KEN"))
        assertEquals("Bearer T0KEN", seen.last().headers["authorization"])
        http.revoke("cid", "T0KEN")
        assertEquals(mapOf("token" to "T0KEN", "token_type_hint" to "access_token", "client_id" to "cid"), form(seen.last().body))
        assertTrue(seen.none { it.headers.containsKey("cookie") })
    }

    @Test
    fun everyRequestIsRefusedWhileTheSwitchIsOff() {
        val off = OsmHttp(local, "0.1.11", { false })
        assertThrows<OnlineRefused> { off.token("c", "c", "v", OsmClient.REDIRECT_URI) }
        assertThrows<OnlineRefused> { off.userName("T") }
        assertThrows<OnlineRefused> { off.revoke("c", "T") }
        assertThrows<OnlineRefused> { off.element(OsmRef(OsmType.NODE, 42)) }
        assertThrows<OnlineRefused> { off.openChangeset("T", mapOf()) }
        assertTrue(seen.isEmpty())
    }

    private fun editItem(id: String, baseVersion: Int?, changes: Map<String, String?>, base: Map<String, String> = tags) = OutboxItem(
        id, 42.1, -80.1, "summary", "Test Cafe", createdMs = 1,
        edit = EditData("n42", baseVersion, changes.keys.mapNotNull { k -> base[k]?.let { k to it } }.toMap(), changes, PlaceEdits.comment(changes)),
    )

    private fun sender(store: OutboxStore, signedIn: Boolean = true) =
        OutboxSender(store, http, local, { true }, Dispatchers.IO) { if (signedIn) OsmAccount("T0KEN", "Test Mapper") else null }

    @Test
    fun anEditGoesAsOneChangesetWithTheVersionRead(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val changes = PlaceEdits.changes(tags, EditFields("Test Cafe", "Mo-Fr 08:00-17:00", "+1 814 555 0100", "https://example.com"), closed = false)
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-17:00", "website" to "https://example.com"), changes)
        store.add(editItem("e1", 3, changes))
        val r = sender(store).sendWaiting()
        assertEquals(1, r.sent)
        assertEquals(listOf("GET /api/0.6/node/42.json", "PUT /api/0.6/changeset/create", "PUT /api/0.6/node/42", "PUT /api/0.6/changeset/900/close"),
            seen.map { "${it.method} ${it.path}" })
        val cs = seen[1].body
        assertTrue("<tag k=\"created_by\" v=\"Beamline 0.1.11\"/>" in cs, cs)
        assertTrue("k=\"source\"" !in cs)
        assertTrue("<tag k=\"comment\" v=\"Updated hours and website\"/>" in cs)
        val put = seen[2].body
        assertTrue("<node id=\"42\" version=\"3\" changeset=\"900\" lat=\"42.1000000\" lon=\"-80.1000000\">" in put, put)
        assertTrue("<tag k=\"opening_hours\" v=\"Mo-Fr 08:00-17:00\"/>" in put)
        assertTrue("<tag k=\"website\" v=\"https://example.com\"/>" in put)
        assertTrue("<tag k=\"phone\" v=\"+1 814 555 0100\"/>" in put, "the other tags kept")
        assertTrue(seen.drop(1).all { it.headers["authorization"] == "Bearer T0KEN" })
        val item = store.byId("e1")!!
        assertEquals(OutboxState.SENT, item.state)
        assertEquals("https://web.test/changeset/900", item.link)
        assertEquals(900L, item.edit!!.changeset)
        assertEquals(4, item.edit!!.newVersion)
        assertEquals(listOf(item), store.pendingEdits("n42"))
        store.markInPack("e1")
        assertTrue(store.pendingEdits("n42").isEmpty())
        assertEquals(item.edit!!.copy(inPack = true), OutboxStore(File(dir, "o.json")).byId("e1")!!.edit, "kept on disk")
    }

    @Test
    fun aConflictAsksAgainAndClosesTheChangeset(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val known = http.element(OsmRef(OsmType.NODE, 42))
        seen.clear()
        putStatus = 409   // someone saved version 4 in between
        store.add(editItem("e2", 3, mapOf("phone" to "+1 814 555 0199")))
        val r = sender(store).sendWaiting("e2", known)
        assertEquals(1, r.review)
        assertEquals(OutboxState.REVIEW, store.byId("e2")!!.state)
        assertEquals(409, store.byId("e2")!!.status)
        assertEquals(listOf("PUT /api/0.6/changeset/create", "PUT /api/0.6/node/42", "PUT /api/0.6/changeset/900/close"), seen.map { "${it.method} ${it.path}" },
            "uploaded as read, no second read; the changeset closed")
        assertTrue(store.byId("e2")!!.editable)
    }

    @Test
    fun anEditThatWaitedIsNotUploadedOverANewerVersion(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        store.add(editItem("e3", 3, mapOf("phone" to "+1 814 555 0199")))
        version = 5
        assertEquals(1, sender(store).sendWaiting().review)
        assertEquals(OutboxState.REVIEW, store.byId("e3")!!.state)
        assertEquals(listOf("GET /api/0.6/node/42.json"), seen.map { "${it.method} ${it.path}" }, "no changeset opened")
        // made offline from the pack's values (no version): only a change of the edited keys stops it
        seen.clear()
        store.add(editItem("e4", null, mapOf("website" to "https://example.org")))
        assertEquals(1, sender(store).sendWaiting().sent)
        seen.clear()
        val base = tags
        tags = tags + ("phone" to "+1 814 555 0111")
        store.add(editItem("e5", null, mapOf("phone" to "+1 814 555 0199"), base))
        assertEquals(1, sender(store).sendWaiting().review)
    }

    @Test
    fun editsWaitWhileOfflineOrSignedOut(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        store.add(editItem("e6", 3, mapOf("phone" to "+1 814 555 0199")))
        val r = sender(store, signedIn = false).sendWaiting()
        assertEquals(0, r.sent)
        assertEquals(1, r.left)
        assertTrue(seen.isEmpty(), "signed out: nothing sent")
        server.stop(0)
        val off = sender(store).sendWaiting()
        assertTrue(off.offline)
        assertEquals(OutboxState.WAITING, store.byId("e6")!!.state)
        assertEquals(OutboxState.WAITING, OutboxStore(File(dir, "o.json")).byId("e6")!!.state, "kept on disk")
    }

    @Test
    fun aNoteGoesUnderTheNameOnlyWhenAskedAndSignedIn(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        store.add(OutboxItem("n1", 42.1, -80.1, "named", null, createdMs = 1, withName = true))
        store.add(OutboxItem("n2", 42.1, -80.1, "anonymous", null, createdMs = 2, withName = false))
        assertEquals(2, sender(store).sendWaiting().sent)
        assertEquals("Bearer T0KEN", seen[0].headers["authorization"])
        assertNull(seen[1].headers["authorization"])
        seen.clear()
        store.add(OutboxItem("n3", 42.1, -80.1, "named but signed out", null, createdMs = 3, withName = true))
        assertEquals(1, sender(store, signedIn = false).sendWaiting().sent)
        assertNull(seen.single().headers["authorization"], "signed out: anonymous, never more than asked")
        assertTrue(OutboxStore(File(dir, "o.json")).byId("n1")!!.withName)
    }

    @Test
    fun closedPermanentlyFollowsTheLifecyclePrefix() {
        val t = mapOf("shop" to "bakery", "name" to "Corner Bakery", "opening_hours" to "Mo-Sa 07:00-15:00", "contact:phone" to "+1 814 555 0100",
            "website" to "https://example.com", "addr:street" to "State Street")
        val c = PlaceEdits.closedChanges(t)
        assertEquals(mapOf("shop" to null, "disused:shop" to "bakery", "opening_hours" to null, "contact:phone" to null, "website" to null), c)
        assertEquals(mapOf("disused:shop" to "bakery", "name" to "Corner Bakery", "addr:street" to "State Street"), PlaceEdits.apply(t, c))
        assertEquals(c, PlaceEdits.changes(t, EditFields.of(t), closed = true))
        assertEquals("Marked a place as closed permanently", PlaceEdits.comment(c))
        assertEquals(
            listOf("Closed permanently: shop=bakery becomes disused:shop=bakery", "Removed: opening_hours, contact:phone, website", "The name stays, so mappers see what was there."),
            PlaceEdits.summary(t, c),
        )
        assertTrue(PlaceEdits.canClose(t))
        assertFalse(PlaceEdits.canClose(mapOf("natural" to "waterfall", "name" to "Falls")))
    }

    @Test
    fun theFormChangesTheKeysThePlaceUses() {
        val t = mapOf("name" to "A", "contact:phone" to "1", "contact:website" to "https://a.example")
        assertEquals(mapOf("contact:phone" to "2", "contact:website" to null), PlaceEdits.changes(t, EditFields("A", null, "2", " "), false))
        assertEquals(emptyMap<String, String?>(), PlaceEdits.changes(t, EditFields("", null, "1", "https://a.example"), false), "a blank name keeps the name")
        // the owner, 2026-11-21: hours in plain words, not in OSM's notation
        assertEquals(listOf("Hours: Mon–Fri 8 AM–6 PM; Sat–Sun closed → Mon–Fri 8 AM–5 PM; Sat–Sun closed"),
            PlaceEdits.summary(mapOf("opening_hours" to "Mo-Fr 08:00-18:00"), mapOf("opening_hours" to "Mo-Fr 08:00-17:00")))
        assertEquals(listOf("Hours: Mo-Fr 08:00-18:00; PH off → (none)"),
            PlaceEdits.summary(mapOf("opening_hours" to "Mo-Fr 08:00-18:00; PH off"), mapOf("opening_hours" to null)), "a text the form cannot express stays as written")
        assertEquals("Mon–Sat 7:30 AM–9 PM; Sun 10 AM–2 PM, 4 PM–8 PM", WeekHours.of("Mo-Sa 07:30-21:00; Su 10:00-14:00,16:00-20:00")!!.plain())
        assertEquals("Open 24 hours every day", WeekHours.of("24/7")!!.plain())
        // open all of Friday and on until 6 AM on Saturday: "24 hours", never "12 AM–6 AM" (the review of 2026-11-21)
        assertEquals("Mon–Thu closed; Fri 24 hours; Sat–Sun closed",
            WeekHours(listOf(emptyList(), emptyList(), emptyList(), emptyList(), listOf(0 until 1800), emptyList(), emptyList())).plain())
        assertEquals("Mon–Thu 11 AM–10 PM; Fri–Sat 11 AM–2 AM; Sun closed", WeekHours.of("Mo-Th 11:00-22:00; Fr,Sa 11:00-02:00")!!.plain())
        assertEquals("Updated hours", PlaceEdits.comment(mapOf("opening_hours" to "x")))
        assertEquals(OsmRef(OsmType.WAY, 7), OsmRef.parse("w7"))
        assertNull(OsmRef.parse("x7"))
        assertEquals("n25479855", OsmRef.parse("n25479855").toString())
        assertEquals("EditData(1 changes)", EditData("n1", 1, mapOf(), mapOf("phone" to "secret"), "c").toString())
    }
}
