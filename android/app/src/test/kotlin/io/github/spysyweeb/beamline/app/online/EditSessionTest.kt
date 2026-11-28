package io.github.spysyweeb.beamline.app.online

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.1.41 (docs/next-round.md items 53a, 53c, 53e): the edit form's session against a local stand-in for OSM's API
 * (driven as SpeedLimitTest does): a deleted place, a moved one, what OSM already has, the node's point and "made at
 * the place" kept with the item, a confirmation that came back. The stand-in answers 409 to a PUT of an old version,
 * as OSM does. Made-up places only.
 */
class EditSessionTest {
    private class Seen(val method: String, val path: String, val body: String)

    private val seen = CopyOnWriteArrayList<Seen>()
    @Volatile private var version = 3
    @Volatile private var tags = mapOf("amenity" to "cafe", "name" to "Test Cafe", "opening_hours" to "Mo-Fr 08:00-18:00", "phone" to "+1 814 555 0100")
    @Volatile private var gone = false
    @Volatile private var down = false
    @Volatile private var nodeLat = 42.1
    @Volatile private var nodeLon = -80.1

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex ->
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = ex.requestURI.path
            seen += Seen(ex.requestMethod, path, body)
            val (status, answer) = when {
                down -> 503 to ""
                path == "/api/0.6/node/42.json" -> if (gone) 410 to "" else 200 to
                    """{"version":"0.6","elements":[{"type":"node","id":42,"lat":$nodeLat,"lon":$nodeLon,"version":$version,""" +
                    """"tags":{${tags.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}}}]}"""
                path == "/api/0.6/changeset/create" -> 200 to "900"
                path == "/api/0.6/node/42" -> {
                    val v = Regex("version=\"(\\d+)\"").find(body)?.groupValues?.get(1)?.toInt()
                    // a deleted node has a newer version (its deletion): an upload of the version read is 409, as on OSM
                    if (gone || v != version) 409 to "Version mismatch"
                    else {
                        tags = Regex("""<tag k="([^"]*)" v="([^"]*)"/>""").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
                        version += 1
                        200 to version.toString()
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
    private val http = OsmHttp(local, "0.1.41", { true })

    @AfterEach
    fun stop() {
        runCatching { server.stop(0) }
    }

    private fun sender(store: OutboxStore, signedIn: Boolean = true) =
        OutboxSender(store, http, local, { true }, Dispatchers.IO) { if (signedIn) OsmAccount("T0KEN", "Test Mapper") else null }

    private val target = EditTarget("n42", "Test Cafe", 42.1, -80.1, "Test Cafe", "Mo-Fr 08:00-18:00", "+1 814 555 0100", null, packOsm = "n42")

    private fun session(store: OutboxStore, itemId: String? = null, t: EditTarget = target, signedIn: Boolean = true, scope: kotlinx.coroutines.CoroutineScope) =
        EditSession(t, itemId, http, store, sender(store, signedIn), scope, Dispatchers.IO)

    private suspend fun EditSession.settle(vararg busy: EditPhase) {
        var n = 0
        while (phase in busy && n++ < 500) delay(10)
    }

    /** OP test 8. */
    @Test
    fun aDeletedPlaceOpensAsGone(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        gone = true
        val s = session(store, scope = this)
        s.load()
        s.settle(EditPhase.LOADING)
        assertEquals(EditPhase.GONE, s.phase, "a new edit from the window: nothing to edit")
        assertFalse(s.goneItem)
        // an item of it in the outbox (REVIEW with 410): its form says so and can delete it
        store.add(OutboxItem("e1", 42.1, -80.1, "Phone", "Test Cafe", 1L, OutboxState.REVIEW, status = 410,
            edit = EditData("n42", 3, mapOf("phone" to "+1 814 555 0100"), mapOf("phone" to "+1 814 555 0199"), "Updated phone")))
        val again = session(store, "e1", scope = this)
        again.load()
        again.settle(EditPhase.LOADING)
        assertEquals(EditPhase.GONE, again.phase)
        assertTrue(again.goneItem)
        again.delete()
        assertNull(store.byId("e1"))
        // a new edit whose upload found the place deleted: its item can be deleted from the GONE screen too
        gone = false
        val fresh = session(store, scope = this)
        fresh.load()
        fresh.settle(EditPhase.LOADING)
        fresh.f.phone = "814 555 0199"
        fresh.summary()
        gone = true
        fresh.upload()
        fresh.settle(EditPhase.UPLOADING, EditPhase.LOADING)
        assertEquals(EditPhase.GONE, fresh.phase)
        assertTrue(fresh.goneItem)
        assertEquals(410, store.items.value.single().status)
        fresh.delete()
        assertTrue(store.items.value.isEmpty())
    }

    /** OP test 9 (with the plan's EDIT_MOVED words). */
    @Test
    fun aMovedPlaceSaysSo(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val review = OutboxItem("e1", 42.1, -80.1, "Phone", "Test Cafe", 1L, OutboxState.REVIEW,
            edit = EditData("n42", 3, mapOf("phone" to "+1 814 555 0100"), mapOf("phone" to "+1 814 555 0199"), "Updated phone", nodeLat = 42.1, nodeLon = -80.1))
        store.add(review)
        nodeLat = 42.1 + 60.0 / 111_195.0
        val s = session(store, "e1", scope = this)
        s.load()
        s.settle(EditPhase.LOADING)
        assertEquals(EditPhase.FORM, s.phase)
        assertEquals(OnlineTexts.EDIT_MOVED, s.notice)
        assertEquals("This place was moved on OpenStreetMap. Check that it is still the place you mean, then upload again.", s.notice)
        // the same item without a point: a change meanwhile, as before
        store.add(review.copy(id = "e2", edit = review.edit!!.copy(nodeLat = null, nodeLon = null)))
        val other = session(store, "e2", scope = this)
        other.load()
        other.settle(EditPhase.LOADING)
        assertEquals(OnlineTexts.EDIT_CONFLICT, other.notice)
    }

    /** OP test 20 (and the target's pack point of OP-5). */
    @Test
    fun anUploadKeepsTheNodesPointAndWhereItWasMade(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        nodeLat = 42.10002
        nodeLon = -80.10003
        // signed out: it waits, with what was decided at the tap
        val s = session(store, signedIn = false, scope = this)
        s.load()
        s.settle(EditPhase.LOADING)
        s.f.phone = "814 555 0199"
        s.summary()
        assertNull(s.stored, "a new edit")
        val changes = s.pendingChanges
        assertEquals(setOf("phone"), changes.keys)
        s.upload(atPlace = true)
        s.settle(EditPhase.UPLOADING)
        assertEquals(EditPhase.WAITING, s.phase)
        val item = store.items.value.single()
        assertTrue(item.atPlace)
        assertEquals(42.10002, item.edit!!.nodeLat)
        assertEquals(-80.10003, item.edit!!.nodeLon)
        assertEquals(42.1, item.lat, "the item's point is the place's, never the phone's")
        // looked at again offline and uploaded with the same values: both kept
        down = true
        val again = session(store, item.id, signedIn = false, scope = this)
        again.load()
        again.settle(EditPhase.LOADING)
        assertEquals(OnlineTexts.EDIT_OFFLINE, again.notice)
        again.summary()
        assertEquals(changes, again.pendingChanges)
        val at = UploadChecks.atPlace(nearNow = false, openedAtPlace = false, stored = again.stored, changes = again.pendingChanges)
        assertTrue(at, "an item made at the place, sent again with its values")
        again.upload(at)
        again.settle(EditPhase.UPLOADING)
        val kept = store.byId(item.id)!!
        assertTrue(kept.atPlace)
        assertEquals(42.10002, kept.edit!!.nodeLat)
        assertEquals(-80.10003, kept.edit!!.nodeLon)
        // a new edit made offline takes the pack entry's point from its target
        val packed = session(store, t = target.copy(nodeLat = 42.1, nodeLon = -80.1), signedIn = false, scope = this)
        packed.load()
        packed.settle(EditPhase.LOADING)
        packed.f.website = "https://example.com"
        packed.summary()
        packed.upload()
        packed.settle(EditPhase.UPLOADING)
        val p = store.items.value.first { it.id != item.id }
        assertEquals(42.1, p.edit!!.nodeLat)
        assertFalse(p.atPlace)
        assertNull(p.edit!!.baseVersion)
    }

    /** Plan OP-4 (EditSession side): the form says "Already on OpenStreetMap" when nothing needed to go. */
    @Test
    fun whatOsmAlreadyHasSaysSoOnTheForm(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val s = session(store, scope = this)
        s.load()
        s.settle(EditPhase.LOADING)
        s.f.phone = "814 555 0199"
        s.summary()
        // between the summary and Upload someone sets the same number: OSM's 409, read again, nothing to upload
        tags = tags + ("phone" to s.pendingChanges["phone"]!!)
        version += 1
        s.upload()
        s.settle(EditPhase.UPLOADING)
        assertEquals(EditPhase.DONE, s.phase)
        assertTrue(s.already)
        assertEquals("https://web.test/node/42", s.link, "the place's page")
        assertNull(store.items.value.single().edit!!.changeset)
        assertTrue(seen.none { it.method == "PUT" && it.path == "/api/0.6/node/42" && it.body.contains("version=\"4\"") })
    }

    /** OP test 26. */
    @Test
    fun aConfirmationThatCameBackOpensOnOsmsHours(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val c = HoursCheck.item(HoursTarget("n42", "Test Cafe", 42.1, -80.1, "Mo-Fr 08:00-18:00", "n42"), "c1", 1_795_878_000_000L, ZoneId.of("UTC"), atPlace = true)
        store.add(c.copy(state = OutboxState.REVIEW))
        tags = tags + ("opening_hours" to "Mo-Fr 09:00-17:00")
        version = 4
        val s = session(store, "c1", t = EditTarget("n42", "Test Cafe", 42.1, -80.1, null, null, null, null, focus = EditFocus.HOURS), scope = this)
        s.load()
        s.settle(EditPhase.LOADING)
        assertEquals(EditPhase.FORM, s.phase)
        assertEquals(OnlineTexts.CONFIRM_CONFLICT, s.notice)
        assertEquals("Mo-Fr 09:00-17:00", s.f.hoursText(), "OSM's hours of now, not the ones confirmed")
        assertFalse(s.f.anyChanged, "nothing of the confirmation is put on the form")
        // an Upload is an ordinary edit that replaces the confirmation, and decides "at the place" again
        s.f.phone = "814 555 0199"
        s.summary()
        assertFalse(UploadChecks.atPlace(false, false, s.stored, s.pendingChanges), "a confirmation turned into an edit decides again")
        s.upload()
        s.settle(EditPhase.UPLOADING)
        val e = store.byId("c1")!!
        assertNull(e.edit!!.confirm)
        assertEquals(OutboxState.SENT, e.state)
    }

    /** The fixer's review: a confirmation that came back with the hours unchanged (moved far, or closed) says why, not "other hours". */
    @Test
    fun aConfirmationThatCameBackForAnotherReasonSaysWhy(@TempDir dir: File) = runBlocking {
        val store = OutboxStore(File(dir, "o.json"))
        val h = HoursTarget("n42", "Test Cafe", 42.1, -80.1, "Mo-Fr 08:00-18:00", "n42", nodeLat = 42.1, nodeLon = -80.1)
        store.add(HoursCheck.item(h, "c1", 1_795_878_000_000L, ZoneId.of("UTC"), atPlace = true).copy(state = OutboxState.REVIEW))
        val t = EditTarget("n42", "Test Cafe", 42.1, -80.1, null, null, null, null, focus = EditFocus.HOURS)
        // moved 120 m, the hours as confirmed
        nodeLat = 42.1 + 120.0 / 111_195.0
        val moved = session(store, "c1", t = t, scope = this)
        moved.load()
        moved.settle(EditPhase.LOADING)
        assertEquals(EditPhase.FORM, moved.phase)
        assertEquals(OnlineTexts.EDIT_MOVED, moved.notice)
        // in place, but closed meanwhile with its hours kept
        nodeLat = 42.1
        tags = tags - "amenity" + ("disused:amenity" to "cafe")
        version = 4
        val closed = session(store, "c1", t = t, scope = this)
        closed.load()
        closed.settle(EditPhase.LOADING)
        assertEquals(EditPhase.FORM, closed.phase)
        assertNotNull(closed.notice)
        assertNotEquals(OnlineTexts.CONFIRM_CONFLICT, closed.notice, "OpenStreetMap has the hours confirmed")
        assertEquals(OnlineTexts.CONFIRM_CHANGED, closed.notice)
        assertEquals("This place changed on OpenStreetMap after you confirmed its hours. It is below as it is now: check it, or delete your confirmation.",
            OnlineTexts.CONFIRM_CHANGED)
        assertEquals("Mo-Fr 08:00-18:00", closed.f.hoursText())
    }
}
