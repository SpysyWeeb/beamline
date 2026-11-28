package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.feature.drive.ArrivalQuestionUi
import io.github.spysyweeb.beamline.feature.search.HoursDayUi
import io.github.spysyweeb.beamline.feature.search.HoursUi
import io.github.spysyweeb.beamline.feature.search.PlaceSheetUi
import io.github.spysyweeb.beamline.search.PoiDetails
import io.github.spysyweeb.beamline.search.hours.OpenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId

/**
 * 0.1.41 (docs/online-design.md 4.8, AP1-AP12; docs/next-round.md items 53c, 53g) with the plan's AP-2, AP-8, AP-9,
 * AP-10, AP-11 and OP-5: which one question the arrival asks, when the window offers "Hours still right?", the
 * targets the answers build and the words. Made-up places (and the public Subway of 425 State Street, Erie, by name).
 */
class PlaceQuestionsTest {
    private val day = 86_400_000L
    private val now = 1_795_878_000_000L   // 2026-11-28 15:00 UTC
    private val zone = ZoneId.of("America/New_York")
    private val weekdays = "Mo-Fr 08:00-18:00"

    private fun shown(text: String = "8 AM–6 PM", weekend: String = "Closed", note: String? = null) = HoursUi(
        OpenState.OPEN, "Open", null,
        listOf("Mon", "Tue", "Wed", "Thu", "Fri").map { HoursDayUi(it, text, false) } + listOf("Sat", "Sun").map { HoursDayUi(it, weekend, false) },
        note,
    )

    private fun window(
        osm: String? = "n77", title: String = "Test Cafe", name: String? = "Test Cafe", type: String? = "fast_food",
        hours: String? = weekdays, ui: HoursUi? = shown(), phone: String? = "+1-814-555-0100", website: String? = "https://cafe.example",
        packOsm: String? = osm, lat: Double? = 42.1, lon: Double? = -80.1,
    ) = PlaceSheetUi(
        Place(title, GeoPoint(42.1004, -80.1004), PlaceSource.MAP_POI), "fast_food", title, null, null, null, emptyList(),
        hours = ui, osm = osm, poi = PoiDetails(hours, phone, website, osm = packOsm, name = name, type = type, lat = lat, lon = lon),
    )

    private fun facts(w: PlaceSheetUi = window(), closed: Boolean = false) = PlaceQuestions.facts(w, closed)!!

    private fun sent(id: String, changes: Map<String, String?>, ageDays: Long, state: OutboxState = OutboxState.SENT, osm: String = "n77",
                     packOsm: String? = null, replace: Map<String, String>? = null, road: Boolean = false) = OutboxItem(
        id, 42.1, -80.1, "t", "Test Cafe", now - ageDays * day, state, sentMs = now - ageDays * day,
        edit = EditData(osm, 3, emptyMap(), changes, "c", changeset = 9, packOsm = packOsm, replaceWith = replace, road = road),
    )

    private fun confirmation(ageDays: Long, state: OutboxState = OutboxState.SENT) =
        HoursCheck.item(HoursTarget("n77", "Test Cafe", 42.1, -80.1, weekdays), "c$ageDays", now - ageDays * day, zone, atPlace = true).copy(state = state)

    private fun ask(w: PlaceSheetUi, items: List<OutboxItem> = emptyList(), closed: Boolean = false) =
        PlaceQuestions.arrivalAsk(facts(w, closed), items, now)

    @Test
    fun theOneArrivalQuestionInTheOwnersOrder() {
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, ask(window(hours = null, ui = null)), "no hours on the map")
        assertEquals(PlaceQuestions.Ask.CHECK_HOURS, ask(window()), "hours, no own check")
        val checked = listOf(confirmation(30))
        assertEquals(PlaceQuestions.Ask.ADD_PHONE, ask(window(phone = null), checked), "checked a month ago, no phone")
        assertEquals(PlaceQuestions.Ask.ADD_PHONE_AND_WEBSITE, ask(window(phone = null, website = null), checked))
        assertEquals(PlaceQuestions.Ask.ADD_WEBSITE, ask(window(website = null), checked))
        assertNull(ask(window(), checked), "all there, checked")
        assertEquals(PlaceQuestions.Ask.ADD_PHONE, ask(window(ui = null, phone = null)), "hours the app cannot read: skipped")
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, ask(window(hours = null, ui = null, phone = null)), "hours first")
    }

    @Test
    fun onlyBusinessesAndNeverAGuess() {
        for (t in listOf(null, "waterfall", "park", "place_of_worship", "hospital", "school", "leisure_fishing", "leisure_disc_golf_course",
            "amenity_weighbridge", "leisure=playground", "amenity=bench", "tourism=hotel")) {
            assertNull(ask(window(type = t)), "$t")
        }
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, ask(window(type = "atm", hours = null, ui = null, phone = null)))
        assertNull(ask(window(type = "atm", phone = null, website = null), listOf(confirmation(30))), "an ATM: hours only")
        assertEquals(PlaceQuestions.Ask.CHECK_HOURS, ask(window(type = "shop_florist")))
        assertEquals(PlaceQuestions.Ask.CHECK_HOURS, ask(window(type = "shop=florist")), "a raw shop type is asked (AP-9)")
        assertEquals(PlaceQuestions.Ask.CHECK_HOURS, ask(window(type = "craft=carpenter")))
        assertNull(ask(window(osm = "r12")), "a relation")
        assertNull(PlaceQuestions.facts(window(osm = null), false), "no OSM object: no facts")
        assertNull(PlaceQuestions.facts(window().copy(poi = null), false), "no pack details: never a guess")
        assertNull(ask(window(), closed = true), "closed by the user")
        assertNull(ask(window(), listOf(sent("w", mapOf("phone" to "1"), 1, OutboxState.WAITING))), "an edit waiting")
        assertNull(ask(window(), listOf(sent("r", mapOf("phone" to "1"), 1, OutboxState.REVIEW))), "in review")
        assertNull(ask(window(osm = "n500", packOsm = "n77"), listOf(sent("p", mapOf("phone" to "1"), 1, OutboxState.WAITING, osm = "n9", packOsm = "n77"))),
            "matched by the pack's object")
        assertEquals(PlaceQuestions.Ask.CHECK_HOURS, ask(window(), listOf(sent("s", mapOf("maxspeed" to "45 mph"), 1, OutboxState.WAITING, road = true))),
            "a speed limit is no edit of the place")
        assertTrue(PlaceQuestions.asksAbout("fast_food"))
        assertFalse(PlaceQuestions.asksAbout("historic=memorial"))
    }

    @Test
    fun aFieldTheUserChangedThisYearIsNotAsked() {
        val noHours = window(hours = null, ui = null)
        assertNull(ask(noHours, listOf(sent("e", mapOf("opening_hours" to null), 100))), "the user removed the hours 100 days ago")
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, ask(noHours, listOf(sent("e", mapOf("opening_hours" to null), 366))), "366 days ago")
        val checked = confirmation(30)
        assertNull(ask(window(phone = null), listOf(checked, sent("e", mapOf("contact:phone" to null), 10))), "contact:phone changed: no phone question")
        // an undo, an undone edit and a refused one do not count
        val undo = sent("u", mapOf("opening_hours" to null), 10).let { it.copy(edit = it.edit!!.copy(undoOf = "x")) }
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, ask(noHours, listOf(undo)))
        val undone = sent("d", mapOf("opening_hours" to null), 10).let { it.copy(edit = it.edit!!.copy(undone = true)) }
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, ask(noHours, listOf(undone)))
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, ask(noHours, listOf(sent("f", mapOf("opening_hours" to null), 10, OutboxState.REFUSED))))
        // a replacement counts what the user set (AP-2): its phone, not the old hours it removed by itself
        val replaced = sent("rp", mapOf("opening_hours" to null, "phone" to "+1-814-555-0101", "amenity" to "cafe"), 10,
            replace = mapOf("amenity" to "cafe", "phone" to "+1-814-555-0101"))
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, ask(noHours, listOf(replaced)), "the old hours went by themselves")
        assertEquals(now - 10 * day, PlaceQuestions.touchedMs(listOf(replaced), "n77", null, PlaceQuestions.PHONE_KEYS))
        assertNull(PlaceQuestions.touchedMs(listOf(replaced), "n77", null, PlaceQuestions.HOURS_KEYS))
        // a confirmation is no change of the hours
        assertNull(PlaceQuestions.touchedMs(listOf(checked), "n77", null, PlaceQuestions.HOURS_KEYS))
    }

    @Test
    fun theWindowOffersTheCheckOnlyAtThePlace() {
        val w = window()
        fun check(ui: PlaceSheetUi = w, allowed: Boolean = true, atPlace: Boolean = true, items: List<OutboxItem> = emptyList(), at: Long = now) =
            PlaceQuestions.withHoursCheck(ui, allowed, atPlace, items, at, zone)
        assertTrue(check().canCheckHours)
        assertFalse(check().hoursChecked)
        assertFalse(check(allowed = false).canCheckHours, "signed out, Contribute off or a trip driven")
        assertFalse(check(atPlace = false).canCheckHours, "not standing at the place")
        assertFalse(check(window(osm = "r12")).canCheckHours, "a relation")
        assertFalse(check(window(ui = null)).canCheckHours, "hours the app cannot read")
        assertFalse(check(window(hours = null)).canCheckHours, "no raw hours")
        assertFalse(check(items = listOf(sent("r", mapOf("phone" to "1"), 1, OutboxState.REVIEW))).canCheckHours, "an edit of the place in review")
        assertTrue(check(items = listOf(sent("w", mapOf("phone" to "1"), 1, OutboxState.WAITING))).canCheckHours, "a phone edit waiting: still asked")
        assertFalse(check(items = listOf(sent("h", mapOf("opening_hours" to weekdays), 1, OutboxState.WAITING))).canCheckHours, "an hours edit waiting counts")
        assertFalse(check(items = listOf(confirmation(30))).canCheckHours, "confirmed last month")
        // AP-8: the place the user added, with its hours
        val added = OutboxItem("a1", 42.1, -80.1, "t", "Test Cafe", now - 20 * day, OutboxState.SENT, sentMs = now - 20 * day,
            add = AddData("cafe", mapOf("amenity" to "cafe", "opening_hours" to weekdays), "c", node = 77, changeset = 5))
        assertFalse(check(items = listOf(added)).canCheckHours)
        // confirmed today: "Hours confirmed", for the rest of that day
        val today = confirmation(0, OutboxState.WAITING)
        val c = check(items = listOf(today))
        assertTrue(c.hoursChecked)
        assertFalse(c.canCheckHours)
        assertTrue(check(atPlace = false, items = listOf(today)).hoursChecked, "said wherever the window is")
        // the fixer's review (AP1): not while signed out, with Contribute off or while a trip is driven
        assertFalse(check(allowed = false, items = listOf(today)).hoursChecked, "AP1")
        assertFalse(check(allowed = false, atPlace = false, items = listOf(today)).hoursChecked)
        assertFalse(check(items = listOf(today), at = now + day).hoursChecked, "the next day")
        assertFalse(check(items = listOf(today.copy(edit = today.edit!!.copy(undone = true)))).hoursChecked, "undone")
        assertTrue(check() !== w, "a new copy when it changes")
        val same = check()
        assertTrue(PlaceQuestions.withHoursCheck(same, true, true, emptyList(), now, zone) === same, "the same instance when nothing changes")
    }

    @Test
    fun theConfirmationNamesThePlaceByItsMapName() = runBlocking {
        // a saved place's window: its title is the user's label
        val work = window(osm = "n7570266250", title = "Work", name = "Subway", hours = "Mo-Su 07:00-22:00", lat = 42.1291, lon = -80.0853)
        val t = PlaceQuestions.hoursTarget(work, "fast_food")!!
        assertEquals("Subway", t.name)
        assertEquals("Mo-Su 07:00-22:00", t.hours)
        assertEquals("n7570266250", t.packOsm)
        assertEquals(42.1291, t.lat, "the pack entry's own point")
        assertEquals(42.1291, t.nodeLat, "a node of the pack: its point for the move check (OP-5)")
        assertEquals("fast_food", t.icon)
        assertNull(PlaceQuestions.hoursTarget(work.copy(poi = work.poi!!.copy(openingHours = null)), null), "no hours: nothing to confirm")
        // the fixer's review: a place with no name on the map is named in My reports as its window is (local only), never
        // "The point you pressed"; the public comment still says "a place"
        val unnamed = PlaceQuestions.hoursTarget(work.copy(poi = work.poi!!.copy(name = null)), "fast_food")!!
        assertNull(unnamed.name, "no name on the map")
        val item = HoursCheck.item(unnamed, "c9", now, zone, atPlace = false)
        assertEquals("Work", item.placeName)
        assertEquals("Hours check · Work", OnlineTexts.kind(item) + " · " + OnlineTexts.where(item.lat, item.lon, item.placeName))
        val noNameOnOsm = OsmElement(OsmRef(OsmType.NODE, 7570266250), 3, mapOf("amenity" to "fast_food", "opening_hours" to "Mo-Su 07:00-22:00"), 42.1291, -80.0853, emptyList())
        assertEquals("Confirmed the opening hours of a place", EditPlans.sendComment(item, noNameOnOsm))
        assertEquals("Confirmed the opening hours of Subway", EditPlans.sendComment(item, noNameOnOsm.copy(tags = noNameOnOsm.tags + ("name" to "Subway"))))
        assertEquals("Mo-Fr 09:00-17:00", PlaceQuestions.hoursTarget(work, null, hours = "Mo-Fr 09:00-17:00")!!.hours, "the hours the question showed")
        val test = work.copy(osm = "n999")
        assertNull(PlaceQuestions.hoursTarget(test, null)!!.nodeLat, "a test object: the pack's point is not its own")
        assertNull(PlaceQuestions.nodePoint(work.copy(osm = "w5", poi = work.poi!!.copy(osm = "w5"))), "a way: none")
        assertEquals(GeoPoint(42.1004, -80.1004), PlaceQuestions.pointOf(work.copy(poi = work.poi!!.copy(lat = null, lon = null))), "no entry point: the window's")
        // AP-11: the edit form's target carries the map's name, never the label
        val e = PlaceQuestions.editTarget(work, "fast_food", EditFocus.HOURS, atPlace = true)!!
        assertEquals("Subway", e.name)
        assertEquals("Subway", e.packName)
        assertEquals("n7570266250", e.packOsm)
        assertEquals(EditFocus.HOURS, e.focus)
        assertTrue(e.atPlace)
        assertEquals(42.1291, e.nodeLat)
        assertEquals(42.1291, e.lat)
        assertNull(PlaceQuestions.editTarget(work.copy(osm = null), null))
        val noName = PlaceQuestions.editTarget(work.copy(poi = work.poi!!.copy(name = null)), null)!!
        assertEquals("Work", noName.name, "the label stays on the phone (the form's title)")
        assertNull(noName.packName)
        // offline, "This is now a different business": the public comment has no "Work"
        for (target in listOf(e, noName)) {
            val dir = File(System.getProperty("java.io.tmpdir"), "pq-" + System.nanoTime()).apply { mkdirs() }
            try {
                val store = OutboxStore(File(dir, "o.json"))
                val server = OsmServer("http://127.0.0.1:9", "https://web.test", local = true)
                val http = OsmHttp(server, "0.1.41", { false })
                val s = EditSession(target, null, http, store, OutboxSender(store, http, server, { false }, Dispatchers.IO), this, Dispatchers.IO)
                s.load()
                var n = 0
                while (s.phase == EditPhase.LOADING && n++ < 500) delay(10)
                s.switchDifferentBusiness(true)
                s.f.type = PlaceTypes.byCode("pizza")
                s.f.name = "Joe's Pizza"
                s.summary()
                assertEquals(EditPhase.SUMMARY, s.phase)
                assertFalse("Work" in s.comment, s.comment)
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun theWeekInShortLines() {
        assertEquals(listOf("Mon–Fri 8 AM–6 PM", "Sat 9 AM–1 PM", "Sun closed"), PlaceQuestions.weekLines("Mo-Fr 08:00-18:00; Sa 09:00-13:00", shown()))
        assertEquals(listOf("Mon–Sun 7 AM–10 PM"), PlaceQuestions.weekLines("Mo-Su 07:00-22:00", shown()))
        assertEquals(listOf("Open 24 hours every day"), PlaceQuestions.weekLines("24/7", shown()))
        assertEquals(listOf("Mon–Fri 8 AM–12 PM, 1 PM–5 PM", "Sat–Sun closed"), PlaceQuestions.weekLines("Mo-Fr 08:00-12:00,13:00-17:00", shown()))
        // hours the form cannot express: the window's week, then its holiday note
        assertEquals(listOf("Mon–Fri 8 AM–6 PM", "Sat–Sun closed", "Holiday hours may differ"),
            PlaceQuestions.weekLines("Mo-Fr 08:00-18:00; PH off", shown(note = "Holiday hours may differ")))
        assertEquals(listOf("Mon–Fri 8 AM–6 PM", "Sat–Sun closed", "Holiday hours may differ"),
            PlaceQuestions.weekLines(weekdays, shown(note = "Holiday hours may differ")))
        assertEquals(listOf("Mon–Fri open 24 hours", "Sat–Sun closed"), PlaceQuestions.weekLines(null, shown(text = "Open 24 hours")))
        assertEquals(emptyList<String>(), PlaceQuestions.weekLines(null, null))
    }

    @Test
    fun theCardsWords() {
        fun ui(a: PlaceQuestions.Ask, lines: List<String> = emptyList()) = PlaceQuestions.arrivalUi(ArrivalAsker.Shown(a, "n77", null, lines))
        assertEquals(ArrivalQuestionUi("This place has no hours on the map. Add them?", emptyList(), null, "Add hours", "Not now"), ui(PlaceQuestions.Ask.ADD_HOURS))
        assertEquals(ArrivalQuestionUi("Hours still right?", listOf("Mon–Fri 8 AM–6 PM"), "Yes", "Edit hours", "Not now"),
            ui(PlaceQuestions.Ask.CHECK_HOURS, listOf("Mon–Fri 8 AM–6 PM")))
        assertEquals(ArrivalQuestionUi("This place has no phone number on the map. Add it?", emptyList(), null, "Add phone", "Not now"), ui(PlaceQuestions.Ask.ADD_PHONE))
        assertEquals(ArrivalQuestionUi("This place has no website on the map. Add it?", emptyList(), null, "Add website", "Not now"), ui(PlaceQuestions.Ask.ADD_WEBSITE))
        assertEquals(ArrivalQuestionUi("This place has no phone number or website on the map. Add them?", emptyList(), null, "Add them", "Not now"),
            ui(PlaceQuestions.Ask.ADD_PHONE_AND_WEBSITE))
        assertEquals(EditFocus.HOURS, PlaceQuestions.focusOf(PlaceQuestions.Ask.ADD_HOURS))
        assertEquals(EditFocus.HOURS, PlaceQuestions.focusOf(PlaceQuestions.Ask.CHECK_HOURS))
        assertEquals(EditFocus.PHONE, PlaceQuestions.focusOf(PlaceQuestions.Ask.ADD_PHONE))
        assertEquals(EditFocus.PHONE, PlaceQuestions.focusOf(PlaceQuestions.Ask.ADD_PHONE_AND_WEBSITE))
        assertEquals(EditFocus.WEBSITE, PlaceQuestions.focusOf(PlaceQuestions.Ask.ADD_WEBSITE))
        assertEquals("PlaceFacts", facts().toString(), "prints none of it (PV2)")
        assertEquals("Shown(CHECK_HOURS)", ArrivalAsker.Shown(PlaceQuestions.Ask.CHECK_HOURS, "n77", weekdays, listOf("x")).toString())
    }

    @Test
    fun anOutboxOfZeroPointFortyCountsAsItIs(@TempDir dir: File) {
        fun file(ageDays: Long) = File(dir, "o$ageDays.json").apply {
            writeText(
                """{"format":"beamline-outbox/1","items":[{"id":"e1","kind":"edit","lat":42.1,"lon":-80.1,"text":"Hours","place":"Test Cafe",""" +
                    """"created_ms":${now - ageDays * day},"state":"sent","link":"l","sent_ms":${now - ageDays * day},""" +
                    """"edit":{"osm":"n77","base":{"opening_hours":"Mo-Fr 09:00-17:00"},"changes":{"opening_hours":"Mo-Fr 09:00-18:00"},"comment":"Updated hours","changeset":5}}]}""",
            )
        }
        val young = OutboxStore(file(200)).items.value
        assertNull(ask(window(), young), "its hours edit counts as the user's check")
        assertNull(ask(window(hours = null, ui = null), young), "and as the user's change of the hours")
        val old = OutboxStore(file(400)).items.value
        assertEquals(PlaceQuestions.Ask.CHECK_HOURS, ask(window(), old))
    }
}
