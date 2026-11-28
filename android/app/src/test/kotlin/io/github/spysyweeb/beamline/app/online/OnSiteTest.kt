package io.github.spysyweeb.beamline.app.online

import com.sun.net.httpserver.HttpServer
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.feature.search.HoursDayUi
import io.github.spysyweeb.beamline.feature.search.HoursUi
import io.github.spysyweeb.beamline.feature.search.PlaceSheetUi
import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.search.PoiDetails
import io.github.spysyweeb.beamline.search.hours.OpenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.Continuation

/**
 * 0.1.41, 53e (docs/online-design.md 4.5, "Were you there?"): the 10-minute memory of the phone's positions, the
 * asks before an upload, the "made at the place" decision (plan OP-6, AP-7) and that nothing of the memory reaches a
 * file (plan OP-10), through [OnSite], the one door AppGraph, AppFlows and AppRoot use (the fixer's review, F8 and F10).
 * Made-up points only.
 */
class OnSiteTest {
    private val lat = 42.14
    private val lon = -80.08
    private fun north(m: Double) = lat + m / 111_195.0

    @Test
    fun aFixWithinAbout80mInTheLast10MinutesCounts() {
        val r = RecentPositions()
        r.add(Fix(north(79.0), lon, 10f, elapsedMs = 1_000))
        assertTrue(r.near(lat, lon, nowMs = 2_000), "79 m")
        assertFalse(RecentPositions().apply { add(Fix(north(81.0), lon, 10f, 1_000)) }.near(lat, lon, 2_000), "81 m")
        assertTrue(r.near(lat, lon, nowMs = 1_000 + 10 * 60_000), "10 minutes old")
        assertFalse(r.near(lat, lon, nowMs = 1_000 + 10 * 60_000 + 1), "10 minutes and 1 ms old")
        assertFalse(r.near(lat, lon, nowMs = 999), "a fix from the future of the clock asked")
        assertFalse(RecentPositions().apply { add(Fix(lat, lon, 51f, 1_000)) }.near(lat, lon, 2_000), "less sure than 50 m")
        assertTrue(RecentPositions().apply { add(Fix(lat, lon, 50f, 1_000)) }.near(lat, lon, 2_000))
        assertTrue(RecentPositions().apply { add(Fix(lat, lon, null, 1_000)) }.near(lat, lon, 2_000), "no accuracy given")
        // a shorter window ("standing near it now", 4.8); a new memory, as asking prunes it (the clock never goes back)
        val s = RecentPositions().apply { add(Fix(north(79.0), lon, 10f, elapsedMs = 1_000)) }
        assertTrue(s.near(lat, lon, nowMs = 61_000, sinceMs = 60_000))
        assertFalse(s.near(lat, lon, nowMs = 61_001, sinceMs = 60_000))
        // an older fix after a newer one, and the same fix again, are ignored
        val m = RecentPositions()
        m.add(Fix(north(500.0), lon, 5f, 5_000))
        m.add(Fix(lat, lon, 5f, 4_000))
        m.add(Fix(lat, lon, 5f, 5_000))
        assertFalse(m.near(lat, lon, 6_000))
        assertEquals("RecentPositions(1 fixes)", m.toString())
        // more than the window is trimmed by the fixes' own times
        val w = RecentPositions()
        w.add(Fix(lat, lon, 5f, 0))
        w.add(Fix(north(300.0), lon, 5f, 5 * 60_000))
        w.add(Fix(north(300.0), lon, 5f, 10 * 60_000 + 1))
        assertFalse(w.near(lat, lon, 10 * 60_000 + 1, sinceMs = Long.MAX_VALUE / 2), "the first one is gone")
        w.add(Fix(north(310.0), lon, 5f, 10 * 60_000 + 2))
        assertEquals("RecentPositions(3 fixes)", w.toString(), "the count only, no coordinate (PV2)")
        w.clear()
        assertEquals("RecentPositions(0 fixes)", w.toString())
        assertFalse(w.near(north(300.0), lon, 10 * 60_000 + 3))
        // at most MAX_FIXES are kept
        val many = RecentPositions()
        for (i in 1..(RecentPositions.MAX_FIXES + 10)) many.add(Fix(lat, lon, 5f, i.toLong()))
        assertEquals("RecentPositions(${RecentPositions.MAX_FIXES} fixes)", many.toString())
    }

    @Test
    fun theAsksComeInOrder() {
        val none = emptySet<UploadAsk>()
        // the very first edit, made at the place: the public notice carries the survey words
        assertEquals(UploadAsk.PUBLIC, UploadChecks.next(publicSeen = false, surveySeen = false, atPlace = true, answered = none))
        assertTrue(UploadChecks.publicWithSurvey(surveySeen = false, atPlace = true))
        assertNull(UploadChecks.next(false, false, true, setOf(UploadAsk.PUBLIC, UploadAsk.SURVEY)))
        // not at the place: the on-site question, every time
        assertEquals(UploadAsk.ON_SITE, UploadChecks.next(true, false, false, none))
        assertNull(UploadChecks.next(true, false, false, setOf(UploadAsk.ON_SITE)))
        assertEquals(UploadAsk.PUBLIC, UploadChecks.next(false, false, false, none))
        assertFalse(UploadChecks.publicWithSurvey(surveySeen = false, atPlace = false))
        assertEquals(UploadAsk.ON_SITE, UploadChecks.next(false, false, false, setOf(UploadAsk.PUBLIC)))
        // at the place after the public notice of 0.1.40: the survey notice once
        assertEquals(UploadAsk.SURVEY, UploadChecks.next(true, false, true, none))
        assertNull(UploadChecks.next(true, false, true, setOf(UploadAsk.SURVEY)))
        assertNull(UploadChecks.next(true, true, true, none))
        assertFalse(UploadChecks.publicWithSurvey(surveySeen = true, atPlace = true))
    }

    /** Plan OP-6 and AP-7 (OP test 20's decision). */
    @Test
    fun theAtPlaceDecision() {
        val phone = mapOf<String, String?>("phone" to "+1-814-555-0199")
        val stored = OutboxItem("e1", lat, lon, "t", "Test Cafe", 1L, OutboxState.REVIEW, atPlace = true,
            edit = EditData("n42", 3, mapOf("phone" to "+1-814-555-0100"), phone, "Updated phone"))
        assertTrue(UploadChecks.atPlace(nearNow = true, openedAtPlace = false, stored = null, changes = phone), "a fix near it")
        assertTrue(UploadChecks.atPlace(nearNow = false, openedAtPlace = true, stored = null, changes = phone), "opened from a question at the place")
        assertFalse(UploadChecks.atPlace(false, false, null, phone), "a new edit, no fix")
        assertTrue(UploadChecks.atPlace(false, false, stored, phone), "made there, sent again with the values it had")
        assertTrue(UploadChecks.atPlace(false, false, stored.copy(edit = stored.edit!!.copy(changes = phone + ("website" to "https://a.example"))), phone),
            "a part of what it had")
        assertFalse(UploadChecks.atPlace(false, false, stored, mapOf("phone" to "+1-814-555-0111")), "re-sent with a changed phone and no fix")
        assertFalse(UploadChecks.atPlace(false, false, stored, phone + ("website" to "https://a.example")), "with something more")
        assertTrue(UploadChecks.atPlace(true, false, stored, mapOf("phone" to "+1-814-555-0111")), "... unless the phone is near it now")
        assertFalse(UploadChecks.atPlace(false, false, stored.copy(atPlace = false), phone), "made elsewhere")
        val confirmation = stored.copy(edit = stored.edit!!.copy(confirm = "opening_hours", changes = mapOf("opening_hours" to "Mo-Fr 08:00-18:00")))
        assertFalse(UploadChecks.atPlace(false, false, confirmation, mapOf("opening_hours" to "Mo-Fr 08:00-18:00")), "a confirmation turned into an edit decides again")
        val add = OutboxItem("a1", lat, lon, "t", "Joe's", 1L, atPlace = true, add = AddData("pizza", mapOf("amenity" to "restaurant", "name" to "Joe's"), "c"))
        assertTrue(UploadChecks.atPlace(false, false, add, mapOf("amenity" to "restaurant", "name" to "Joe's")))
        assertFalse(UploadChecks.atPlace(false, false, add, mapOf("amenity" to "restaurant", "name" to "Joe's Pizza")))
        val pick = OutboxItem("p1", lat, lon, "t", "Main Street", 1L, OutboxState.REVIEW, speedMph = 45, atPlace = true)
        assertTrue(UploadChecks.atPlace(false, false, pick, mapOf("maxspeed" to "45 mph")), "a limit picked at the road")
    }

    /** The fixer's review (53e: "the last 10 minutes", memory only): fixes older than that leave the memory when it is asked, with no new fix. */
    @Test
    fun oldFixesLeaveTheMemoryWhenItIsAsked() {
        val r = RecentPositions()
        r.add(Fix(lat, lon, 5f, 1_000))
        r.add(Fix(north(10.0), lon, 5f, 2_000))
        // the window's 2-minute question keeps what the 10 minutes still hold
        assertFalse(r.near(lat, lon, nowMs = 1_000 + 3 * 60_000, sinceMs = 2 * 60_000))
        assertEquals("RecentPositions(2 fixes)", r.toString())
        assertTrue(r.near(lat, lon, nowMs = 1_000 + 3 * 60_000))
        // 10 minutes and 1 ms after the newest: both gone
        assertFalse(r.near(lat, lon, nowMs = 2_000 + 10 * 60_000 + 1, sinceMs = Long.MAX_VALUE / 2))
        assertEquals("RecentPositions(0 fixes)", r.toString(), "no new fix needed")
        // a fix newer than the clock asked stays
        r.add(Fix(lat, lon, 5f, 50_000))
        assertFalse(r.near(lat, lon, nowMs = 40_000))
        assertEquals("RecentPositions(1 fixes)", r.toString())
        // pruned by the app's clock alone (no question asked)
        r.prune(50_000 + 10 * 60_000)
        assertEquals("RecentPositions(1 fixes)", r.toString(), "exactly 10 minutes old: kept")
        r.prune(50_000 + 10 * 60_000 + 1)
        assertEquals("RecentPositions(0 fixes)", r.toString())
    }

    /**
     * The fixer's review (F8, "the last 10 minutes" also in the background): the feeder AppGraph runs while Contribute
     * is on ([OnSite.keep]) lets each fix go when it turns 10 minutes old, with no question asked and no new fix.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fixesLeaveTheMemoryAtTenMinutesWithNobodyAsking() = runTest {
        val onSite = OnSite { testScheduler.currentTime }
        val fixes = MutableStateFlow<Fix?>(null)
        val feed = launch { onSite.keep(fixes) }
        runCurrent()
        fixes.value = Fix(lat, lon, 5f, elapsedMs = 0)
        runCurrent()
        advanceTimeBy(30_000)
        fixes.value = Fix(north(10.0), lon, 5f, elapsedMs = 30_000)
        runCurrent()
        assertEquals("OnSite(2 fixes)", onSite.toString(), "the count only (PV2)")
        advanceTimeBy(10 * 60_000L - 30_000)
        runCurrent()
        assertEquals("OnSite(2 fixes)", onSite.toString(), "exactly 10 minutes old: kept")
        advanceTimeBy(1)
        runCurrent()
        assertEquals("OnSite(1 fixes)", onSite.toString(), "10 minutes and 1 ms old: gone, nobody asked")
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("OnSite(0 fixes)", onSite.toString())
        // empty for a long while, then a new fix: it too leaves at its own 10 minutes
        advanceTimeBy(60 * 60_000L)
        val t = testScheduler.currentTime
        fixes.value = Fix(lat, lon, 5f, elapsedMs = t)
        runCurrent()
        assertEquals("OnSite(1 fixes)", onSite.toString())
        advanceTimeBy(10 * 60_000L + 1)
        runCurrent()
        assertEquals("OnSite(0 fixes)", onSite.toString())
        // the switch off: AppGraph cancels the feeder and empties the memory; a later fix is not taken
        feed.cancel()
        onSite.clear()
        fixes.value = Fix(lat, lon, 5f, elapsedMs = testScheduler.currentTime)
        runCurrent()
        assertEquals("OnSite(0 fixes)", onSite.toString())
    }

    /**
     * The fixer's review (F10): the memory's one door gives out a yes or no, its count in words, or nothing: no member
     * of [OnSite] or [RecentPositions] can hand a fix, a point or a list to the code that builds items and files.
     */
    @Test
    fun theMemoryGivesOutOnlyYesOrNo() {
        for (c in listOf(RecentPositions::class.java, OnSite::class.java)) {
            for (m in c.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }) {
                val ok = m.returnType == java.lang.Boolean.TYPE || m.returnType == Void.TYPE ||
                    (m.name == "toString" && m.parameterCount == 0) ||
                    (m.name == "keep" && m.parameterTypes.lastOrNull() == Continuation::class.java)   // runs until cancelled
                assertTrue(ok, "${c.simpleName}.${m.name} gives out ${m.returnType.simpleName}")
            }
            assertTrue(c.fields.all { Modifier.isStatic(it.modifiers) }, "${c.simpleName}: no public field")
        }
    }

    /**
     * Plan OP-10 (OP test 22 rewritten) and the fixer's review (F10): the canary fixes go in through the feeder AppGraph
     * runs ([OnSite.keep]); every kind of item is made from a made-up window as AppFlows makes it (the targets of
     * [PlaceQuestions], "made at the place" by the same [OnSite] calls AppFlows' uploadEdit, uploadAdd, uploadSpeed,
     * confirmHours and arrivalTick make), first with the phone 2 km away, then 33 m from a second place, whose arrival
     * question is answered (arrival-asked.json); with the settings' store, no file in the folder holds a canary's
     * digits and every item's point is its place's.
     */
    @Test
    fun nothingOfTheMemoryIsWritten(@TempDir dir: File) = runBlocking {
        var now = 3_000L
        val onSite = OnSite { now }
        val fixes = Channel<Fix?>(Channel.UNLIMITED)
        val feed = launch { onSite.keep(fixes.consumeAsFlow()) }
        suspend fun holds(count: Int) {
            var n = 0
            while (onSite.toString() != "OnSite($count fixes)" && n++ < 500) delay(10)
            assertEquals("OnSite($count fixes)", onSite.toString())
        }
        suspend fun settle(busy: () -> Boolean) {
            var n = 0
            while (busy() && n++ < 500) delay(10)
        }
        val farLat = 42.123457
        val farLon = -80.098765
        fixes.send(Fix(farLat, farLon, 5f, 1_000))
        fixes.send(Fix(farLat + 0.000001, farLon - 0.000001, 4f, 2_000))
        holds(2)
        val store = OutboxStore(File(dir, OutboxStore.FILE_NAME))
        val settings = OnlineSettingsStore(File(dir, OnlineSettingsStore.FILE_NAME))
        val asked = AskedStore(File(dir, AskedStore.FILE_NAME), LocalDate.of(2026, 11, 28))
        val offline = OsmServer("http://127.0.0.1:9", "https://web.test", local = true)
        val http = OsmHttp(offline, "0.1.41", { false })   // offline: the forms from the pack's values
        val sender = OutboxSender(store, http, offline, { false }, Dispatchers.IO) { null }   // nothing goes: the items wait
        // a stand-in that has a road at the speed limit's point (Test Road, way 500 through 42.1420, -80.0800)
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { ex ->
                val answer = if (ex.requestURI.path == "/api/0.6/map.json") """{"version":"0.6","elements":[
                    {"type":"node","id":1,"lat":42.142,"lon":-80.081},{"type":"node","id":2,"lat":42.142,"lon":-80.079},
                    {"type":"way","id":500,"version":3,"nodes":[1,2],"tags":{"highway":"secondary","name":"Test Road","maxspeed":"35 mph"}}]}""" else ""
                val bytes = answer.toByteArray()
                ex.sendResponseHeaders(if (bytes.isEmpty()) 404 else 200, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
            }
            start()
        }
        try {
            val roads = OsmHttp(OsmServer("http://127.0.0.1:${server.address.port}", "https://web.test", local = true), "0.1.41", { true })
            val cafe = window("n42", "Test Cafe", lat, lon, "Mo-Fr 08:00-18:00", phone = null)
            // 1. the phone 2 km away: an edit (uploadEdit), a confirmation (confirmHours), a new place (uploadAdd), a limit (uploadSpeed)
            val t = PlaceQuestions.editTarget(cafe, "fast_food")!!
            val s = EditSession(t, null, http, store, sender, this, Dispatchers.IO)
            s.load()
            settle { s.phase == EditPhase.LOADING }
            s.f.phone = "814 555 0199"
            s.summary()
            s.upload(onSite.madeAt(s))
            settle { s.phase == EditPhase.UPLOADING }
            val h = PlaceQuestions.hoursTarget(cafe, "fast_food")!!
            store.add(HoursCheck.item(h, "c1", 1_795_878_000_000L, ZoneId.of("UTC"), onSite.madeAt(h)))
            val a = AddSession(42.1405, -80.0795, null, http, store, sender, this, Dispatchers.IO, packNear = { emptyList() })
            a.load()
            settle { a.checking }
            a.f.type = PlaceTypes.byCode("pizza")
            a.f.name = "Joe's Pizza"
            a.next { }
            assertEquals(AddPhase.SUMMARY, a.phase)
            a.upload(onSite.madeAt(a))
            settle { a.phase == AddPhase.UPLOADING }
            val sp = SpeedSession(42.1421, -80.0800, null, null, null, roads, store, sender, this, Dispatchers.IO)
            sp.load()
            settle { sp.phase == SpeedPhase.LOADING }
            assertEquals(SpeedPhase.PICK, sp.phase)
            sp.pick(45)
            sp.summary()
            sp.upload(onSite.madeAt(sp))
            settle { sp.phase == SpeedPhase.UPLOADING }
            // an undo of a sent edit (it has no question: the item is made from the sent one)
            val sent = OutboxItem("s1", lat, lon, "t", "Test Cafe", 1L, OutboxState.SENT, link = "l", sentMs = 2L,
                edit = EditData("n42", 3, mapOf("website" to "https://a.example"), mapOf("website" to "https://b.example"), "c", changeset = 9, newVersion = 4,
                    sentBefore = mapOf("website" to "https://a.example"), sentAfter = mapOf("website" to "https://b.example")))
            store.add(sent)
            store.add(Undo.item(sent, "u1", 3L)!!)
            val away = store.items.value
            assertEquals(6, away.size)
            assertTrue(away.none { it.atPlace }, "2 km away: none made at the place")
            // 2. 33 m from a second place: its arrival question (arrivalTick), answered Yes (a confirmation), and an edit
            val bakeryLat = 42.15
            val bakeryLon = -80.07
            now = 5_000
            fixes.send(Fix(42.150211, -80.070287, 5f, 4_000))
            holds(3)
            val week = HoursUi(OpenState.OPEN, "Open", null, listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat").map { HoursDayUi(it, "7 AM–3 PM", false) }, null)
            val bakery = window("n43", "Test Bakery", bakeryLat, bakeryLon, "Mo-Sa 07:00-15:00", phone = "+1-814-555-0123", shown = week)
            val asker = ArrivalAsker()
            fun tick(elapsedMs: Long): ArrivalAsker.Shown? = asker.update(
                ArrivalInput(Phase.ARRIVED, Motion.STOPPED, simulated = false, allowed = true, atPlace = onSite.arrivedAt(bakery, elapsedMs),
                    facts = PlaceQuestions.facts(bakery, ownClosed = false), items = store.items.value, nowMs = 1_795_878_000_000L,
                    askedRecently = asked.recent("n43", LocalDate.of(2026, 11, 28))),
                elapsedMs,
            ) { osm -> asked.noteShown(osm) }
            assertNull(tick(5_000))
            val q = tick(20_000)!!
            assertEquals(PlaceQuestions.Ask.CHECK_HOURS, q.ask)
            assertSame(q, asker.answer())
            asked.markAnswered(q.osm, LocalDate.of(2026, 11, 28))
            val hb = PlaceQuestions.hoursTarget(bakery, "bakery", hours = q.hours)!!
            store.add(HoursCheck.item(hb, "c2", 1_795_878_000_000L, ZoneId.of("UTC"), onSite.madeAt(hb)))
            val tb = PlaceQuestions.editTarget(bakery, "bakery")!!
            val sb = EditSession(tb, null, http, store, sender, this, Dispatchers.IO)
            sb.load()
            settle { sb.phase == EditPhase.LOADING }
            sb.f.website = "https://bakery.example"
            sb.summary()
            sb.upload(onSite.madeAt(sb))
            settle { sb.phase == EditPhase.UPLOADING }
            settings.update { it.allowOnline().copy(editNoticeSeen = true, surveyNoticeSeen = true) }
            val there = store.items.value.filter { it.id !in away.map { i -> i.id } }
            assertEquals(2, there.size)
            assertTrue(there.all { it.atPlace }, "33 m from the place: made there")
            // every item at its place's own point, never the phone's
            val points = mapOf("Test Cafe" to (lat to lon), "Test Bakery" to (bakeryLat to bakeryLon), "Joe's Pizza" to (42.1405 to -80.0795),
                "Test Road" to (42.1421 to -80.0800))
            for (i in store.items.value) assertEquals(points[i.placeName], i.lat to i.lon, "${i.placeName}: its place's point")
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            assertTrue(files.map { it.name }.containsAll(listOf(OutboxStore.FILE_NAME, OnlineSettingsStore.FILE_NAME, AskedStore.FILE_NAME)), files.joinToString { it.name })
            for (f in files) {
                val text = f.readText()
                for (canary in listOf("42.12345", "80.09876", "42.123458", "80.098766", "42.15021", "80.07028")) assertFalse(canary in text, "${f.name} holds $canary")
            }
            assertEquals("OnSite(3 fixes)", onSite.toString())
        } finally {
            feed.cancel()
            server.stop(0)
        }
    }

    /** A made-up window of a place whose pack entry is at ([pLat], [pLon]). */
    private fun window(osm: String, name: String, pLat: Double, pLon: Double, hours: String?, phone: String?, shown: HoursUi? = null) = PlaceSheetUi(
        Place(name, GeoPoint(pLat, pLon), PlaceSource.MAP_POI), "fast_food", name, null, null, null, emptyList(),
        hours = shown, osm = osm, poi = PoiDetails(hours, phone, null, osm = osm, name = name, type = "fast_food", lat = pLat, lon = pLon),
    )
}
