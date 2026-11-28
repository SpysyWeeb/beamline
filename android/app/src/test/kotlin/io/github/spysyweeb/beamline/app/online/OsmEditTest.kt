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
import java.time.ZoneId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Batch O2 (docs/online-design.md 4.1, 4.2, 4.4, 4.5) against a local stand-in for OSM's API: the
 * sign-in's token request, the user's name, notes under the name, an edit's exact requests, the
 * conflict (409) and the changed-meanwhile paths, the outbox for edits, Closed permanently's tags.
 * 0.1.41 (docs/next-round.md item 53): the check tag by tag at send time (a PUT on the stand-in keeps its tags as the
 * next version), a 409 read again once, a deleted place, Undo, source=survey, the confirmation of hours, the public
 * comment from the element's own name, and an outbox written by 0.1.40.
 */
class OsmEditTest {
    private class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    /** The node on the stand-in server. */
    @Volatile private var version = 3
    @Volatile private var tags = mapOf("amenity" to "cafe", "name" to "Test Cafe", "opening_hours" to "Mo-Fr 08:00-18:00", "phone" to "+1 814 555 0100")
    /** 53a: the node was deleted (GET answers 410). */
    @Volatile private var gone = false
    @Volatile private var nodeLat = 42.1
    @Volatile private var nodeLon = -80.1
    /** Each PUT of the node takes the next status, then 200. */
    private val putStatuses = ConcurrentLinkedQueue<Int>()
    /** Runs when a PUT is answered 409 (someone saved a newer version in between). */
    @Volatile private var onConflict: (() -> Unit)? = null
    private var nextChangeset = 900L
    /** The changeset of the node's last version (OSM's JSON says it), once a PUT went. */
    @Volatile private var lastChangeset: Long? = null
    /** The fixer's review: a PUT goes in, but its reply is lost (the connection drops before OSM's answer). */
    @Volatile private var losePutReply = false

    private fun nodeJson() = """{"version":"0.6","elements":[{"type":"node","id":42,"lat":$nodeLat,"lon":$nodeLon,"version":$version,""" +
        (lastChangeset?.let { "\"changeset\":$it," } ?: "") +
        """"tags":{${tags.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}}]}"""

    /** A PUT that went: the server keeps its tags as the new version. */
    private fun accept(body: String): Int {
        fun un(v: String) = v.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        tags = Regex("""<tag k="([^"]*)" v="([^"]*)"/>""").findAll(body).associate { un(it.groupValues[1]) to un(it.groupValues[2]) }
        lastChangeset = Regex("""changeset="(\d+)"""").find(body)?.groupValues?.get(1)?.toLong()
        version += 1
        return version
    }

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
                path == "/api/0.6/node/42.json" -> if (gone) 410 to "" else 200 to nodeJson()
                path == "/api/0.6/changeset/create" -> 200 to (nextChangeset++).toString()
                path == "/api/0.6/node/42" -> when (val st = putStatuses.poll() ?: 200) {
                    200 -> {
                        val v = accept(body)
                        if (losePutReply) {
                            ex.close()   // applied, but the connection drops before the answer
                            return@createContext
                        }
                        200 to v.toString()
                    }
                    else -> {
                        if (st == 409) onConflict?.invoke()
                        st to "Version mismatch"
                    }
                }
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

    // ---------------------------------------------------------------- 0.1.41, 53a: conflicts per field

    /** OP test 4 (replaces anEditThatWaitedIsNotUploadedOverANewerVersion: its old meaning is gone by design). */
    @Test
    fun anEditThatWaitedGoesOntoANewerVersionTagByTag(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        store.add(editItem("e3", 3, mapOf("phone" to "+1 814 555 0199")))
        // someone added a website meanwhile: version 5
        version = 5
        tags = tags + ("website" to "https://example.com")
        assertEquals(1, sender(store).sendWaiting().sent)
        assertEquals(listOf("GET /api/0.6/node/42.json", "PUT /api/0.6/changeset/create", "PUT /api/0.6/node/42", "PUT /api/0.6/changeset/900/close"),
            seen.map { "${it.method} ${it.path}" })
        val put = seen[2].body
        assertTrue("version=\"5\"" in put, put)
        assertTrue("<tag k=\"phone\" v=\"+1 814 555 0199\"/>" in put, put)
        assertTrue("<tag k=\"website\" v=\"https://example.com\"/>" in put, "the website someone added stays")
        assertEquals(OutboxState.SENT, store.byId("e3")!!.state)
        assertEquals(mapOf<String, String?>("phone" to "+1 814 555 0100"), store.byId("e3")!!.edit!!.sentBefore, "what the upload replaced (53d)")
        assertEquals(mapOf<String, String?>("phone" to "+1 814 555 0199"), store.byId("e3")!!.edit!!.sentAfter)
        // the phone itself changed to a third value meanwhile: back to the user, no changeset
        seen.clear()
        store.add(editItem("e4", version, mapOf("phone" to "+1 814 555 0177")))
        tags = tags + ("phone" to "+1 814 555 0111")
        version += 1
        assertEquals(1, sender(store).sendWaiting().review)
        assertEquals(OutboxState.REVIEW, store.byId("e4")!!.state)
        assertEquals(listOf("GET /api/0.6/node/42.json"), seen.map { "${it.method} ${it.path}" }, "no changeset opened")
        // made offline from the pack's values (no version): the same rule
        seen.clear()
        store.add(editItem("e5", null, mapOf("website" to "https://example.org")))
        assertEquals(1, sender(store).sendWaiting().sent)
        val base = tags
        tags = tags + ("opening_hours" to "Mo-Su 06:00-22:00")
        store.add(editItem("e6", null, mapOf("opening_hours" to "Mo-Fr 07:00-19:00"), base))
        assertEquals(1, sender(store).sendWaiting().review)
    }

    /** OP test 5 (replaces aConflictAsksAgainAndClosesTheChangeset). */
    @Test
    fun a409IsReadAgainOnce(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val known = http.element(OsmRef(OsmType.NODE, 42))
        seen.clear()
        putStatuses += 409   // someone saved version 4 in between, with an unrelated tag
        onConflict = { version = 4; tags = tags + ("wheelchair" to "yes"); onConflict = null }
        store.add(editItem("e2", 3, mapOf("phone" to "+1 814 555 0199")))
        assertEquals(1, sender(store).sendWaiting("e2", known).sent)
        assertEquals(listOf("PUT /api/0.6/changeset/create", "PUT /api/0.6/node/42", "GET /api/0.6/node/42.json", "PUT /api/0.6/node/42", "PUT /api/0.6/changeset/900/close"),
            seen.map { "${it.method} ${it.path}" }, "read again once, the same changeset, closed once")
        val put = seen[3].body
        assertTrue("version=\"4\"" in put, put)
        assertTrue("<tag k=\"wheelchair\" v=\"yes\"/>" in put && "<tag k=\"phone\" v=\"+1 814 555 0199\"/>" in put, put)
        assertEquals(OutboxState.SENT, store.byId("e2")!!.state)
        assertEquals(5, store.byId("e2")!!.edit!!.newVersion)
        // two 409s in one pass: back to the user (409), the changeset closed once
        val known2 = http.element(OsmRef(OsmType.NODE, 42))
        seen.clear()
        putStatuses += 409
        putStatuses += 409
        onConflict = { version += 1; tags = tags + ("level" to "$version") }
        store.add(editItem("e7", known2.version, mapOf("website" to "https://example.net")))
        assertEquals(1, sender(store).sendWaiting("e7", known2).review)
        assertEquals(OutboxState.REVIEW, store.byId("e7")!!.state)
        assertEquals(409, store.byId("e7")!!.status)
        assertEquals(listOf("PUT /api/0.6/changeset/create", "PUT /api/0.6/node/42", "GET /api/0.6/node/42.json", "PUT /api/0.6/node/42", "PUT /api/0.6/changeset/901/close"),
            seen.map { "${it.method} ${it.path}" })
        assertTrue(store.byId("e7")!!.editable)
    }

    /** OP test 6. */
    @Test
    fun whatOsmAlreadyHasIsNotUploadedAgain(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        store.add(editItem("e8", 3, mapOf("phone" to "+1 814 555 0199")))
        version = 4
        tags = tags + ("phone" to "+1 814 555 0199")
        assertEquals(1, sender(store).sendWaiting().sent)
        assertEquals(listOf("GET /api/0.6/node/42.json"), seen.map { "${it.method} ${it.path}" }, "no changeset")
        val i = store.byId("e8")!!
        assertEquals(OutboxState.SENT, i.state)
        assertNull(i.edit!!.changeset)
        assertEquals("https://web.test/node/42", i.link)
        assertEquals(OnlineTexts.ALREADY_THERE, OnlineTexts.state(i, sending = false))
        assertEquals(emptyMap<String, String?>(), i.edit!!.sentBefore)
        assertFalse(Undo.offered(i, store.items.value, i.sentMs!! + 1), "nothing changed: nothing to undo")
    }

    /** OP test 7. */
    @Test
    fun aDeletedPlaceComesBackToTheUser(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        store.add(editItem("e9", 3, mapOf("phone" to "+1 814 555 0199")))
        gone = true
        assertEquals(1, sender(store).sendWaiting().review)
        val i = store.byId("e9")!!
        assertEquals(OutboxState.REVIEW, i.state)
        assertEquals(410, i.status)
        assertTrue(seen.none { it.path == "/api/0.6/changeset/create" })
        assertEquals(OnlineTexts.GONE_STATE, OnlineTexts.state(i, sending = false))
        assertEquals(OnlineTexts.GONE_STATE, OnlineTexts.state(i.copy(state = OutboxState.REFUSED), sending = false), "a 0.1.40 item REFUSED with 410 reads the same")
        assertTrue(i.editable)
    }

    // ---------------------------------------------------------------- 0.1.41, 53d: Undo

    /** OP test 12. */
    @Test
    fun anUndoGoesAsItsOwnChangesetAndLeavesWhatOthersChanged(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val changes = mapOf<String, String?>("opening_hours" to "Mo-Fr 08:00-17:00", "website" to "https://example.com")
        store.add(editItem("e1", 3, changes))
        assertEquals(1, sender(store).sendWaiting().sent)
        val sent = store.byId("e1")!!
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-18:00", "website" to null), sent.edit!!.sentBefore)
        assertEquals(changes, sent.edit!!.sentAfter)
        // someone changes the hours to a third value
        tags = tags + ("opening_hours" to "Mo-Su 06:00-22:00")
        version += 1
        val at = sent.sentMs!! + 60_000
        assertTrue(Undo.offered(sent, store.items.value, at))
        store.add(Undo.item(sent, "u1", at)!!)
        seen.clear()
        assertEquals(1, sender(store).sendWaiting().sent)
        assertEquals(listOf("GET /api/0.6/node/42.json", "PUT /api/0.6/changeset/create", "PUT /api/0.6/node/42", "PUT /api/0.6/changeset/901/close"),
            seen.map { "${it.method} ${it.path}" })
        val cs = seen[1].body
        assertTrue("<tag k=\"comment\" v=\"Undid my change to Test Cafe\"/>" in cs, cs)
        assertFalse("k=\"source\"" in cs, "an undo carries no source")
        val put = seen[2].body
        assertFalse("k=\"website\"" in put, put)
        assertTrue("<tag k=\"opening_hours\" v=\"Mo-Su 06:00-22:00\"/>" in put, "left as someone changed it")
        val row = store.byId("u1")!!
        assertEquals("Undone · changeset 901 · left as changed since: Hours", OnlineTexts.state(row, sending = false))
        assertEquals(OnlineTexts.UNDONE_PART, OnlineTexts.undoResult(row))
        assertEquals("Undo", OnlineTexts.kind(row))
        val orig = store.byId("e1")!!
        assertTrue(orig.edit!!.undone)
        assertFalse(Undo.offered(orig, store.items.value, at))
        assertEquals("Uploaded · changeset 900 · undone", OnlineTexts.state(orig, sending = false))
        assertEquals(emptyList<OwnChange>(), OwnChanges.of(store.items.value, emptyList()) { null }, "the pack's value is right again")
        // kept on disk
        assertEquals(store.items.value, OutboxStore(File(dir, "o.json")).items.value)
    }

    /** OP test 13. */
    @Test
    fun anUndoOfEveryTagChangedSinceUploadsNothingAndSaysSo(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        store.add(editItem("e1", 3, mapOf("phone" to "+1 814 555 0199")))
        sender(store).sendWaiting()
        val sent = store.byId("e1")!!
        val at = sent.sentMs!! + 60_000
        tags = tags + ("phone" to "+1 814 555 0111")   // changed by someone since
        store.add(Undo.item(sent, "u1", at)!!)
        seen.clear()
        assertEquals(1, sender(store).sendWaiting().sent)
        assertEquals(listOf("GET /api/0.6/node/42.json"), seen.map { "${it.method} ${it.path}" }, "no changeset")
        val row = store.byId("u1")!!
        assertEquals(OutboxState.SENT, row.state)
        assertNull(row.edit!!.changeset)
        assertEquals(listOf("phone"), row.edit!!.kept)
        assertEquals("Not undone: someone changed it on OpenStreetMap since", OnlineTexts.state(row, sending = false))
        assertEquals(OnlineTexts.NOT_UNDONE, OnlineTexts.undoResult(row))
        assertFalse(store.byId("e1")!!.edit!!.undone)
        assertFalse(Undo.offered(store.byId("e1")!!, store.items.value, at), "an undo item of it exists")
        store.delete("u1")
        assertTrue(Undo.offered(store.byId("e1")!!, store.items.value, at), "deleting the undo offers it again")
        // the place deleted since
        store.add(Undo.item(sent, "u2", at)!!)
        gone = true
        sender(store).sendWaiting()
        val g = store.byId("u2")!!
        assertEquals("Not undone: the place was deleted from OpenStreetMap", OnlineTexts.state(g, sending = false))
        assertEquals(OnlineTexts.UNDO_GONE, OnlineTexts.undoResult(g))
        // OSM has the earlier value again: nothing to undo
        gone = false
        store.delete("u2")
        tags = tags + ("phone" to "+1 814 555 0100")
        store.add(Undo.item(sent, "u3", at)!!)
        sender(store).sendWaiting()
        val n = store.byId("u3")!!
        assertEquals("Nothing to undo: OpenStreetMap has the earlier details again", OnlineTexts.state(n, sending = false))
        assertEquals(OnlineTexts.NOTHING_TO_UNDO, OnlineTexts.undoResult(n))
        assertTrue(seen.none { it.path == "/api/0.6/changeset/create" && it.body.contains("Undid") })
    }

    /** OP test 14: an outbox written by 0.1.40 is read with its meaning; every new key survives a save. */
    @Test
    fun anItemOf0140IsReadWithItsMeaning(@TempDir dir: File) = runBlocking {
        val now = System.currentTimeMillis()
        val f = File(dir, "o.json")
        f.writeText(
            """{"format":"beamline-outbox/1","items":[""" +
                """{"id":"w1","kind":"edit","lat":42.1,"lon":-80.1,"text":"Phone","place":"Test Cafe","created_ms":1,"state":"waiting",""" +
                """"edit":{"osm":"n42","base_version":3,"base":{"phone":"+1 814 555 0100"},"changes":{"phone":"+1 814 555 0199"},"comment":"Updated phone"}},""" +
                """{"id":"s1","kind":"edit","lat":42.1,"lon":-80.1,"text":"Hours","place":"Test Cafe","created_ms":2,"state":"sent","link":"https://web.test/changeset/800",""" +
                """"sent_ms":${now - 3_600_000},"edit":{"osm":"n42","base_version":2,"base":{"opening_hours":"Mo-Fr 08:00-17:00"},""" +
                """"changes":{"opening_hours":"Mo-Fr 08:00-18:00"},"comment":"Updated hours","changeset":800,"new_version":3}},""" +
                """{"id":"r1","kind":"edit","lat":42.1,"lon":-80.1,"text":"Replaces","place":"Old Threads","created_ms":3,"state":"sent","link":"https://web.test/changeset/700",""" +
                """"sent_ms":${now - 3_600_000},"edit":{"osm":"n43","base":{"name":"Old Threads"},"changes":{"name":"Joe's Pizza","shop":null,"amenity":"restaurant"},""" +
                """"comment":"Replaced Old Threads with restaurant Joe's Pizza","changeset":700,"new_version":6,"replace_with":{"amenity":"restaurant","name":"Joe's Pizza"},"pack_osm":"n43"}}""" +
                """]}""",
        )
        val store = OutboxStore(f)
        assertEquals(3, store.items.value.size)
        assertTrue(store.items.value.none { it.atPlace }, "no source for what 0.1.40 made")
        assertTrue(Undo.offered(store.byId("s1")!!, store.items.value, now), "a sent edit of 0.1.40: Undo from its base")
        assertEquals(mapOf<String, String?>("opening_hours" to "Mo-Fr 08:00-17:00"), Undo.before(store.byId("s1")!!))
        assertFalse(Undo.offered(store.byId("r1")!!, store.items.value, now), "an offline replacement of 0.1.40: no Undo")
        // the waiting one goes onto a newer version tag by tag, without source
        version = 5
        tags = tags + ("wheelchair" to "yes")
        assertEquals(1, sender(store).sendWaiting().sent)
        assertEquals(OutboxState.SENT, store.byId("w1")!!.state)
        assertFalse("k=\"source\"" in seen.first { it.path == "/api/0.6/changeset/create" }.body)
        // every new key survives a save and a read
        val x = OutboxItem(
            "x1", 42.1, -80.1, "t", "Test Cafe", 4L, OutboxState.SENT, link = "l", sentMs = now, atPlace = true,
            edit = EditData("n42", 3, mapOf("phone" to "1"), mapOf("phone" to "2", "website" to null), "c", changeset = 5, newVersion = 6,
                nodeLat = 42.1234567, nodeLon = -80.7654321, sentBefore = mapOf("phone" to "1", "website" to null), sentAfter = mapOf("phone" to "2", "website" to null),
                undoOf = "s1", undone = true, kept = listOf("opening_hours", "phone"), confirm = "opening_hours", kind = mapOf("shop" to "bakery")),
        )
        store.add(x)
        assertEquals(x, OutboxStore(f).byId("x1"))
        val text = f.readText()
        for (k in listOf("\"at_place\":true", "\"node_at\":[42.1234567,-80.7654321]", "\"sent_before\"", "\"website\":null", "\"undo_of\":\"s1\"", "\"undone\":true", "\"kept\"", "\"confirm\"", "\"kind\"")) {
            assertTrue(k in text, k)
        }
        assertEquals("beamline-outbox/1", Regex("\"format\":\"([^\"]+)\"").find(text)!!.groupValues[1], "the format stays")
    }

    // ---------------------------------------------------------------- 0.1.41, 53e and 53c

    /** OP test 19. */
    @Test
    fun aChangesetSaysSurveyOnlyForAnEditMadeAtThePlace(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        store.add(editItem("e1", 3, mapOf("phone" to "+1 814 555 0199")).copy(atPlace = true))
        sender(store).sendWaiting()
        val made = seen.single { it.path == "/api/0.6/changeset/create" }.body
        assertTrue("<tag k=\"source\" v=\"survey\"/>" in made, made)
        assertTrue("<tag k=\"created_by\" v=\"Beamline 0.1.11\"/>" in made)
        seen.clear()
        store.add(editItem("e2", version, mapOf("website" to "https://example.com")))
        sender(store).sendWaiting()
        assertFalse("k=\"source\"" in seen.single { it.path == "/api/0.6/changeset/create" }.body, "not made at the place: no source")
        assertEquals(mapOf("comment" to "c", "created_by" to "Beamline 1"), PlaceEdits.changesetTags("c", "1"))
        assertEquals(mapOf("comment" to "c", "created_by" to "Beamline 1", "source" to "survey"), PlaceEdits.changesetTags("c", "1", survey = true))
    }

    /** OP test 25 (with OP-1's comment from the element's name). */
    @Test
    fun aConfirmationWritesOnlyTheCheckDate(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val zone = ZoneId.of("America/New_York")
        val at = 1_795_878_000_000L   // 2026-11-28 15:00 UTC, 10 AM in New York
        val t = HoursTarget("n42", "Test Cafe", 42.1, -80.1, "Mo-Fr 08:00-18:00", "n42")
        store.add(HoursCheck.item(t, "c1", at, zone, atPlace = true))
        assertEquals(1, sender(store).sendWaiting().sent)
        val cs = seen.single { it.path == "/api/0.6/changeset/create" }.body
        assertTrue("<tag k=\"comment\" v=\"Confirmed the opening hours of Test Cafe\"/>" in cs, cs)
        assertTrue("<tag k=\"source\" v=\"survey\"/>" in cs)
        val put = seen.single { it.method == "PUT" && it.path == "/api/0.6/node/42" }.body
        assertTrue("<tag k=\"check_date:opening_hours\" v=\"2026-11-28\"/>" in put, put)
        for (k in listOf("amenity\" v=\"cafe", "name\" v=\"Test Cafe", "opening_hours\" v=\"Mo-Fr 08:00-18:00", "phone\" v=\"+1 814 555 0100")) assertTrue(k in put, k)
        assertEquals(5, Regex("<tag ").findAll(put).count(), "nothing else changes")
        val c1 = store.byId("c1")!!
        assertEquals(OnlineTexts.CONFIRM_DONE, OnlineTexts.confirmResult(c1))
        assertEquals("Hours check", OnlineTexts.kind(c1))
        assertEquals(mapOf<String, String?>("check_date:opening_hours" to null), c1.edit!!.sentBefore)
        // its Undo removes the date
        store.add(Undo.item(c1, "u1", c1.sentMs!! + 1)!!)
        seen.clear()
        sender(store).sendWaiting()
        val undone = seen.single { it.method == "PUT" && it.path == "/api/0.6/node/42" }.body
        assertFalse("check_date" in undone, undone)
        assertTrue("Undid my change to Test Cafe" in seen.single { it.path == "/api/0.6/changeset/create" }.body)
        // the hours changed on OSM meanwhile: back to the user, no changeset
        tags = tags + ("opening_hours" to "Mo-Fr 09:00-17:00")
        seen.clear()
        store.add(HoursCheck.item(t, "c2", at, zone, atPlace = false))
        assertEquals(1, sender(store).sendWaiting().review)
        assertEquals(OnlineTexts.CONFIRM_REVIEW, OnlineTexts.confirmResult(store.byId("c2")!!))
        assertTrue(seen.none { it.path == "/api/0.6/changeset/create" })
        // a check date of the next day on OSM: nothing to upload
        tags = tags + ("opening_hours" to "Mo-Fr 08:00-18:00") + ("check_date:opening_hours" to "2026-11-29")
        store.delete("c2")
        store.add(HoursCheck.item(t, "c3", at, zone, atPlace = false))
        assertEquals(1, sender(store).sendWaiting().sent)
        assertNull(store.byId("c3")!!.edit!!.changeset)
        assertEquals(OnlineTexts.CONFIRM_ALREADY, OnlineTexts.confirmResult(store.byId("c3")!!))
        assertTrue(seen.none { it.path == "/api/0.6/changeset/create" })
        // an older one is replaced
        tags = tags + ("check_date:opening_hours" to "2024-05-01")
        store.add(HoursCheck.item(t, "c4", at, zone, atPlace = false))
        sender(store).sendWaiting()
        assertTrue("<tag k=\"check_date:opening_hours\" v=\"2026-11-28\"/>" in seen.last { it.method == "PUT" && it.path == "/api/0.6/node/42" }.body)
        assertEquals(OnlineTexts.CONFIRM_WAITING, OnlineTexts.confirmResult(null))
    }

    /** Plan OP-1: a confirmation and an undo name the place as OSM has it at send time, never the user's label. */
    @Test
    fun aPublicCommentNamesThePlaceAsOsmHasIt(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val zone = ZoneId.of("UTC")
        // a saved place's label as the item's name (local only)
        store.add(HoursCheck.item(HoursTarget("n42", "Work", 42.1, -80.1, "Mo-Fr 08:00-18:00", "n42"), "c1", 1_795_878_000_000L, zone, atPlace = false))
        sender(store).sendWaiting()
        val confirm = seen.single { it.path == "/api/0.6/changeset/create" }.body
        assertTrue("Test Cafe" in confirm && "Work" !in confirm, confirm)
        val c1 = store.byId("c1")!!
        assertEquals("Work", c1.placeName, "My reports still says it the user's way")
        store.add(Undo.item(c1, "u1", c1.sentMs!! + 1)!!)
        seen.clear()
        sender(store).sendWaiting()
        val undo = seen.single { it.path == "/api/0.6/changeset/create" }.body
        assertTrue("Undid my change to Test Cafe" in undo && "Work" !in undo, undo)
        // an element with no name: "a place"
        tags = tags - "name"
        seen.clear()
        store.add(editItem("e1", version, mapOf("phone" to "+1 814 555 0199")).copy(placeName = "Work"))
        sender(store).sendWaiting()
        assertFalse("Work" in seen.single { it.path == "/api/0.6/changeset/create" }.body)
        val e1 = store.byId("e1")!!
        store.add(Undo.item(e1, "u2", e1.sentMs!! + 1)!!)
        seen.clear()
        sender(store).sendWaiting()
        val noName = seen.single { it.path == "/api/0.6/changeset/create" }.body
        assertTrue("<tag k=\"comment\" v=\"Undid my change to a place\"/>" in noName, noName)
        assertEquals("Undid my change to Test Cafe", EditPlans.sendComment(Undo.item(e1, "u3", 1L)!!, OsmElement(OsmRef(OsmType.NODE, 42), 1, mapOf("name" to "Test Cafe"), 1.0, 2.0, emptyList())))
    }

    /** AP-5: an hours change or removal takes its old check date with it; Closed permanently too. */
    @Test
    fun anHoursEditTakesItsOldCheckDate() {
        val t = mapOf("amenity" to "cafe", "name" to "Test Cafe", "opening_hours" to "Mo-Fr 08:00-18:00", "check_date:opening_hours" to "2024-05-01")
        val changed = PlaceEdits.changes(t, EditFields("Test Cafe", "Mo-Fr 08:00-17:00", null, null), closed = false)
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-17:00", "check_date:opening_hours" to null), changed)
        assertEquals(listOf("Hours: Mon–Fri 8 AM–6 PM; Sat–Sun closed → Mon–Fri 8 AM–5 PM; Sat–Sun closed", "Hours checked: 2024-05-01 → (none)"),
            PlaceEdits.summary(t, changed), "visible on the summary")
        assertEquals(mapOf("opening_hours" to null, "check_date:opening_hours" to null),
            PlaceEdits.changes(t, EditFields("Test Cafe", " ", null, null), closed = false), "blanked")
        assertEquals(mapOf("phone" to "+1 814 555 0100"), PlaceEdits.changes(t, EditFields("Test Cafe", "Mo-Fr 08:00-18:00", "+1 814 555 0100", null), closed = false),
            "the hours unchanged: the date stays")
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-17:00"),
            PlaceEdits.changes(t - "check_date:opening_hours", EditFields("Test Cafe", "Mo-Fr 08:00-17:00", null, null), closed = false), "no date: nothing more")
        val closed = PlaceEdits.closedChanges(t)
        assertEquals(mapOf("amenity" to null, "disused:amenity" to "cafe", "opening_hours" to null, "check_date:opening_hours" to null), closed)
        assertEquals(mapOf("disused:amenity" to "cafe", "name" to "Test Cafe"), PlaceEdits.apply(t, closed))
    }

    /** The fixer's review: an hours edit that waited, when another mapper set the same hours and dated them: nothing goes, their date stays. */
    @Test
    fun aWaitingHoursEditLeavesANewerCheckDateAlone(@TempDir dir: File) = runBlocking {
        tags = tags + ("check_date:opening_hours" to "2024-05-01")
        val store = OutboxStore(File(dir, "o.json"))
        val changes = PlaceEdits.changes(tags, EditFields("Test Cafe", "Mo-Fr 08:00-17:00", tags["phone"], null), closed = false)
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-17:00", "check_date:opening_hours" to null), changes)
        store.add(editItem("e1", version, changes).copy(atPlace = true))
        assertEquals(0, sender(store, signedIn = false).sendWaiting().sent, "signed out: it waits")
        tags = tags + ("opening_hours" to "Mo-Fr 08:00-17:00") + ("check_date:opening_hours" to "2026-11-27")
        version += 1
        seen.clear()
        assertEquals(1, sender(store).sendWaiting().sent)
        assertTrue(seen.none { it.method == "PUT" }, "no changeset, no upload")
        assertNull(store.byId("e1")!!.edit!!.changeset, "already on OpenStreetMap")
        assertEquals(OnlineTexts.ALREADY_THERE, OnlineTexts.state(store.byId("e1")!!, sending = false))
        assertEquals("2026-11-27", tags["check_date:opening_hours"])
    }

    /**
     * The fixer's review: OSM applied a PUT whose reply was lost (the connection dropped). The next pass finds the element
     * last changed in that changeset: it went (Undo offered), not "Already on OpenStreetMap"; and an undo likewise.
     */
    @Test
    fun anUploadWhoseReplyWasLostCountsAsSent(@TempDir dir: File) = runBlocking {
        val first = OutboxStore(File(dir, "o.json"))
        first.add(editItem("e1", version, mapOf("phone" to "+1 814 555 0199")))
        losePutReply = true
        assertTrue(sender(first).sendWaiting().offline, "no answer: it counts as offline")
        assertEquals("+1 814 555 0199", tags["phone"], "but OSM has it")
        assertEquals(OutboxState.WAITING, first.byId("e1")!!.state)
        losePutReply = false
        // kept on disk before the PUT: the app may even have been closed since
        val store = OutboxStore(File(dir, "o.json"))
        assertEquals(Going(900, mapOf("phone" to "+1 814 555 0100"), mapOf("phone" to "+1 814 555 0199")), store.byId("e1")!!.edit!!.going)
        assertEquals("Going(1 changes)", store.byId("e1")!!.edit!!.going.toString(), "no values printed (PV2)")
        seen.clear()
        assertEquals(1, sender(store).sendWaiting().sent)
        assertTrue(seen.none { it.method == "PUT" && it.path == "/api/0.6/node/42" }, "not uploaded again")
        assertTrue(seen.none { it.path == "/api/0.6/changeset/create" })
        val e1 = store.byId("e1")!!
        assertEquals(OutboxState.SENT, e1.state)
        assertEquals(900L, e1.edit!!.changeset, "the changeset it went in")
        assertEquals(mapOf<String, String?>("phone" to "+1 814 555 0100"), e1.edit!!.sentBefore)
        assertEquals(mapOf<String, String?>("phone" to "+1 814 555 0199"), e1.edit!!.sentAfter)
        assertEquals("Uploaded · changeset 900", OnlineTexts.state(e1, sending = false))
        assertNull(e1.edit!!.going, "done with")
        assertTrue(Undo.offered(e1, store.items.value, e1.sentMs!! + 1))
        // its undo, whose reply is lost too: done on the next pass, and the edit is marked undone
        store.add(Undo.item(e1, "u1", e1.sentMs!! + 1)!!)
        losePutReply = true
        assertTrue(sender(store).sendWaiting().offline)
        assertEquals("+1 814 555 0100", tags["phone"])
        losePutReply = false
        seen.clear()
        sender(store).sendWaiting()
        assertTrue(seen.none { it.method == "PUT" && it.path == "/api/0.6/node/42" })
        val u1 = store.byId("u1")!!
        assertEquals(901L, u1.edit!!.changeset)
        assertEquals("Undone · changeset 901", OnlineTexts.state(u1, sending = false))
        assertTrue(store.byId("e1")!!.edit!!.undone)
        assertEquals(listOf("phone"), store.byId("u1")!!.edit!!.sentAfter!!.keys.toList())
        // a PUT that never reached OSM goes again as usual
        store.add(editItem("e2", version, mapOf("website" to "https://example.com")))
        putStatuses += 503
        assertTrue(sender(store).sendWaiting().offline)
        assertEquals(null, tags["website"])
        seen.clear()
        assertEquals(1, sender(store).sendWaiting().sent)
        assertEquals("https://example.com", tags["website"])
        assertEquals(903L, store.byId("e2")!!.edit!!.changeset, "a new changeset")
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
