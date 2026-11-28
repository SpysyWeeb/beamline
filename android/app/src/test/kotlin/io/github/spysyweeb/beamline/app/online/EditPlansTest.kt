package io.github.spysyweeb.beamline.app.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * 0.1.41: the rules of docs/next-round.md items 53a (conflicts per field, [EditPlans]) and 53d (Undo, [Undo]), with
 * the plan's changes OP-2 (closings and different businesses as one kind unit), OP-3 (phone and contact:phone as one
 * detail), OP-5 (the pack entry's point), OP-9 (Undo.Plan prints counts) and AP-4 (a confirmation of a closed place).
 * Made-up places only.
 */
class EditPlansTest {
    private val cafe = mapOf("amenity" to "cafe", "name" to "Test Cafe", "opening_hours" to "Mo-Fr 08:00-18:00", "phone" to "+1-814-555-0100")

    private fun node(tags: Map<String, String>, version: Int = 5, lat: Double = 42.1, lon: Double = -80.1) =
        OsmElement(OsmRef(OsmType.NODE, 42), version, tags, lat, lon, emptyList())

    private fun way(tags: Map<String, String>) = OsmElement(OsmRef(OsmType.WAY, 7), 2, tags, null, null, listOf(1L, 2L))

    /** An edit as EditSession makes it: the edit-time values of the changed keys (and of the other key of a phone or website). */
    private fun edit(base: Map<String, String>, changes: Map<String, String?>, version: Int? = 3, nodeLat: Double? = null, nodeLon: Double? = null) =
        EditData("n42", version, EditSession.editBase(base, changes), changes, PlaceEdits.comment(changes), nodeLat = nodeLat, nodeLon = nodeLon)

    @Test
    fun aTagConflictsOnlyWithAThirdValue() {
        val phone = mapOf<String, String?>("phone" to "+1-814-555-0199")
        // the table of OP 2.1, row by row
        assertEquals(EditPlan.Upload(phone), EditPlans.plan(edit(cafe, phone), node(cafe)), "phone: still the old value")
        assertEquals(EditPlan.Already, EditPlans.plan(edit(cafe, phone), node(cafe + ("phone" to "+1-814-555-0199"))), "already the edit's value")
        assertEquals(EditPlan.Review(moved = false), EditPlans.plan(edit(cafe, phone), node(cafe + ("phone" to "+1-814-555-0111"))), "a third value")
        val web = mapOf<String, String?>("website" to "https://a.example")
        assertEquals(EditPlan.Review(false), EditPlans.plan(edit(cafe, web), node(cafe + ("website" to "https://b.example"))), "an added tag someone set otherwise")
        assertEquals(EditPlan.Already, EditPlans.plan(edit(cafe, web), node(cafe + ("website" to "https://a.example"))), "... or the same")
        val noHours = mapOf<String, String?>("opening_hours" to null)
        assertEquals(EditPlan.Review(false), EditPlans.plan(edit(cafe, noHours), node(cafe + ("opening_hours" to "Mo-Fr 09:00-17:00"))), "a removal of hours someone changed")
        assertEquals(EditPlan.Already, EditPlans.plan(edit(cafe, noHours), node(cafe - "opening_hours")), "someone removed them too")
        assertEquals(EditPlan.Review(false), EditPlans.plan(edit(cafe, phone), node(cafe - "phone")), "a tag someone removed")
        // a tag the edit does not touch keeps whatever others gave it
        val other = cafe + ("website" to "https://c.example") + ("wheelchair" to "yes")
        val up = EditPlans.plan(edit(cafe, phone), node(other, version = 9))
        assertEquals(EditPlan.Upload(phone), up)
        assertEquals("Upload(1)", up.toString(), "no values printed (PV2)")
        // a check date never conflicts; ours goes only when it is later
        val dated = mapOf<String, String?>("opening_hours" to "Mo-Fr 08:00-18:00", "check_date:opening_hours" to "2026-11-28")
        assertEquals(EditPlan.Upload(mapOf("check_date:opening_hours" to "2026-11-28")), EditPlans.plan(edit(cafe, dated), node(cafe + ("check_date:opening_hours" to "2024-05-01"))))
        assertEquals(EditPlan.Already, EditPlans.plan(edit(cafe, dated), node(cafe + ("check_date:opening_hours" to "2026-11-30"))))
        assertEquals(emptySet<String>(), EditPlans.conflicts(mapOf("check_date" to "2020-01-01"), emptyMap(), mapOf("check_date" to "2026-11-28")))
        assertEquals(mapOf("phone" to "2"), EditPlans.toUpload(mapOf("phone" to "1", "website" to "w"), mapOf("phone" to "2", "website" to "w")))

        // OP-3: phone and contact:phone are one detail (and the two website keys)
        val bare = cafe - "phone"
        assertEquals(setOf("phone"), EditPlans.conflicts(bare + ("contact:phone" to "+1-814-555-0111"), EditSession.editBase(bare, phone), phone),
            "the edit adds phone; someone added contact:phone with another number")
        assertEquals(emptySet<String>(), EditPlans.conflicts(bare + ("contact:phone" to "+1-814-555-0199"), EditSession.editBase(bare, phone), phone),
            "... with the same number")
        val both = cafe + ("contact:phone" to "+1-814-555-0122")
        assertEquals(mapOf("phone" to "+1-814-555-0100", "contact:phone" to "+1-814-555-0122"), EditSession.editBase(both, phone), "the other key's value is kept with the edit")
        assertEquals(EditPlan.Upload(phone), EditPlans.plan(edit(both, phone), node(both)), "the place had both: unchanged, no conflict")
        assertEquals(EditPlan.Review(false), EditPlans.plan(edit(both, phone), node(both + ("contact:phone" to "+1-814-555-0133"))), "the other one changed")
        val cweb = mapOf<String, String?>("contact:website" to "https://a.example")
        val onlyContact = cafe + ("contact:website" to "https://old.example")
        assertEquals(EditPlan.Review(false), EditPlans.plan(edit(onlyContact, cweb), node(onlyContact + ("website" to "https://b.example"))),
            "contact:website changed; someone added website with another address")
        // contact:website still holds the old address: it is put right (the place then says the same under both keys)
        assertEquals(EditPlan.Upload(cweb), EditPlans.plan(edit(onlyContact, cweb), node(onlyContact + ("website" to "https://a.example"))))
        // the fixer's review: the detail is already there under the other key: nothing goes, no second tag with the same value
        assertEquals(EditPlan.Already, EditPlans.plan(edit(bare, phone), node(bare + ("contact:phone" to "+1-814-555-0199"))),
            "the edit adds phone; someone added the same number as contact:phone")
        assertEquals(EditPlan.Already, EditPlans.plan(edit(cafe, web), node(cafe + ("contact:website" to "https://a.example"))),
            "the edit adds website; someone added the same address as contact:website")
        assertEquals(EditPlan.Review(false), EditPlans.plan(edit(bare, phone), node(bare + ("contact:phone" to "+1-814-555-0199") + ("phone" to "+1-814-555-0100"))),
            "... but phone itself got another number meanwhile: a third value")
        assertEquals(EditPlan.Review(false), EditPlans.plan(edit(cafe, mapOf("phone" to null)), node(cafe - "phone" + ("contact:phone" to "+1-814-555-0144"))),
            "the phone removed; someone put one under contact:phone")
    }

    /**
     * The fixer's review (docs/online-design.md 4.5: "a check date never conflicts and a later one stays"; "an hours change
     * or removal also removes the hours' old check date"): at send time a check date goes with its own key.
     */
    @Test
    fun aCheckDateGoesWithItsOwnKey() {
        val dated = cafe + ("check_date:opening_hours" to "2024-05-01")
        val b = "Mo-Fr 08:00-17:00"
        // read online: the hours change takes their old date (AP-5)
        val online = PlaceEdits.changes(dated, EditFields("Test Cafe", b, cafe["phone"], null), closed = false)
        assertEquals(mapOf("opening_hours" to b, "check_date:opening_hours" to null), online)
        assertEquals(EditPlan.Upload(online), EditPlans.plan(edit(dated, online), node(dated)), "as read: both go")
        // someone set the same hours meanwhile and dated them: already there, and their newer date stays
        assertEquals(EditPlan.Already, EditPlans.plan(edit(dated, online), node(cafe + ("opening_hours" to b) + ("check_date:opening_hours" to "2026-11-27"))))
        assertEquals(EditPlan.Already, EditPlans.plan(edit(dated, online), node(cafe + ("opening_hours" to b))), "the same hours, no date")
        // the old hours dated again meanwhile: that date goes with the old hours
        assertEquals(EditPlan.Upload(online), EditPlans.plan(edit(dated, online), node(dated + ("check_date:opening_hours" to "2026-11-27"))))
        // made offline (the pack has no check date): OSM's date of the old hours goes too
        val offline = PlaceEdits.changes(cafe, EditFields("Test Cafe", b, cafe["phone"], null), closed = false)
        assertEquals(mapOf("opening_hours" to b), offline)
        assertEquals(EditPlan.Upload(mapOf("opening_hours" to b, "check_date:opening_hours" to null)), EditPlans.plan(edit(cafe, offline, version = null), node(dated)))
        // ... or a date added after the form read the place
        assertEquals(EditPlan.Upload(mapOf("opening_hours" to b, "check_date:opening_hours" to null)), EditPlans.plan(edit(cafe, offline), node(dated)))
        // the hours removed: their date with them
        val removed = PlaceEdits.changes(cafe, EditFields("Test Cafe", " ", cafe["phone"], null), closed = false)
        assertEquals(EditPlan.Upload(mapOf("opening_hours" to null, "check_date:opening_hours" to null)), EditPlans.plan(edit(cafe, removed), node(dated)))
        // another detail leaves the hours' date alone
        val phone = mapOf<String, String?>("phone" to "+1-814-555-0199")
        assertEquals(EditPlan.Upload(phone), EditPlans.plan(edit(dated, phone), node(dated)))
    }

    @Test
    fun aNodeMovedFarComesBackAndAWayNever() {
        val phone = mapOf<String, String?>("phone" to "+1-814-555-0199")
        val e = edit(cafe, phone, nodeLat = 42.1, nodeLon = -80.1)
        val dLat49 = 49.0 / 111_195.0
        val dLat51 = 51.0 / 111_195.0
        assertEquals(EditPlan.Upload(phone), EditPlans.plan(e, node(cafe, lat = 42.1 + dLat49)), "49 m")
        assertEquals(EditPlan.Review(moved = true), EditPlans.plan(e, node(cafe, lat = 42.1 + dLat51)), "51 m")
        assertTrue(EditPlans.movedFar(e, node(cafe, lat = 42.1 + dLat51)))
        assertEquals(EditPlan.Upload(phone), EditPlans.plan(edit(cafe, phone), node(cafe, lat = 43.0)), "no point kept: no move check")
        assertFalse(EditPlans.movedFar(e.copy(osm = "w7"), way(cafe)), "a way: never")
        // OP-5: an edit made offline and a confirmation keep the pack entry's point; 120 m off: back to the user
        val offline = edit(cafe, phone, version = null, nodeLat = 42.1, nodeLon = -80.1)
        assertEquals(EditPlan.Review(moved = true), EditPlans.plan(offline, node(cafe, lat = 42.1 + 120.0 / 111_195.0)))
        val t = HoursTarget("n42", "Test Cafe", 42.1, -80.1, "Mo-Fr 08:00-18:00", "n42", nodeLat = 42.1, nodeLon = -80.1)
        val confirm = HoursCheck.item(t, "c1", 1_795_000_000_000L, ZoneId.of("America/New_York"), atPlace = false).edit!!
        assertEquals(EditPlan.Review(moved = true), EditPlans.plan(confirm, node(cafe, lat = 42.1 + 120.0 / 111_195.0)))
        assertTrue(EditPlans.plan(confirm, node(cafe)) is EditPlan.Upload, "in place: it goes")
    }

    @Test
    fun anOfflineReplacementChecksTheNameOnly() {
        val old = mapOf("shop" to "clothes", "name" to "Old Threads")
        val next = mapOf("amenity" to "restaurant", "name" to "Joe's Pizza")
        val e = EditData("n42", null, mapOf("name" to "Old Threads"), BusinessTags.replace(old, next), "c", replaceWith = next)
        val now = old + ("phone" to "+1-814-555-0100") + ("addr:housenumber" to "421")
        val p = EditPlans.plan(e, node(now))
        assertTrue(p is EditPlan.Upload)
        val up = (p as EditPlan.Upload).changes
        assertEquals(null, up["phone"], "made again on the tags of now: the old business's phone goes")
        assertTrue("phone" in up && "addr:housenumber" !in up)
        assertEquals(EditPlan.Review(false), EditPlans.plan(e, node(now + ("name" to "New Threads"))), "another name: not the business it replaces")
    }

    @Test
    fun aClosingTakesWhatWasAddedMeanwhile() {
        val bakery = mapOf("shop" to "bakery", "name" to "Corner Bakery", "website" to "https://bakery.example")
        val closing = PlaceEdits.closedChanges(bakery)
        val e = EditData("n42", 3, EditSession.editBase(bakery, closing), closing, "Marked a place as closed permanently", kind = EditPlans.kindOf(bakery))
        assertTrue(EditPlans.isClosing(e))
        // hours and a phone added before it went: they go too
        val now = bakery + ("opening_hours" to "Mo-Sa 07:00-15:00") + ("phone" to "+1-814-555-0100")
        val p = EditPlans.plan(e, node(now)) as EditPlan.Upload
        assertEquals(mapOf("shop" to null, "disused:shop" to "bakery", "opening_hours" to null, "phone" to null, "website" to null), p.changes)
        assertEquals(mapOf("disused:shop" to "bakery", "name" to "Corner Bakery"), PlaceEdits.apply(now, p.changes))
        // amenity=cafe added meanwhile: no longer the place it was: back to the user
        assertEquals(EditPlan.Review(false), EditPlans.plan(e, node(bakery + ("amenity" to "cafe"))))
        // a rename meanwhile is a conflict of the name only if the edit changes it: here it does not
        assertTrue(EditPlans.plan(e, node(bakery + ("name" to "The Corner Bakery"))) is EditPlan.Upload)
        // a closing of 0.1.40 (no kind kept): its base's kind tags count
        assertTrue(EditPlans.plan(e.copy(kind = null), node(now)) is EditPlan.Upload)
    }

    @Test
    fun anUndoOfAClosingAfterARetagSendsNothing() {
        val bakery = mapOf("shop" to "bakery", "name" to "Corner Bakery", "opening_hours" to "Mo-Sa 07:00-15:00")
        val closing = PlaceEdits.closedChanges(bakery)
        val sent = OutboxItem(
            "s1", 42.1, -80.1, "Closed", "Corner Bakery", 1L, OutboxState.SENT, link = "l", sentMs = 1_000L,
            edit = EditData("n42", 3, EditSession.editBase(bakery, closing), closing, "c", changeset = 900, newVersion = 4,
                sentBefore = closing.keys.associateWith { bakery[it] }, sentAfter = closing, kind = EditPlans.kindOf(bakery)),
        )
        val u = Undo.item(sent, "u1", 2_000L)!!.edit!!
        assertEquals(mapOf("disused:shop" to "bakery"), u.kind, "the kind the closing left")
        // as the closing left it: everything back
        val closed = PlaceEdits.apply(bakery, closing)
        val back = Undo.plan(u, closed)
        assertEquals(mapOf("shop" to "bakery", "disused:shop" to null, "opening_hours" to "Mo-Sa 07:00-15:00"), back.back)
        // retagged since as another business: nothing goes back, every key kept
        val retag = mapOf("amenity" to "restaurant", "name" to "Joe's")
        val none = Undo.plan(u, retag)
        assertEquals(emptyMap<String, String?>(), none.back)
        assertEquals(u.changes.keys.toList(), none.kept)
        assertEquals("Undo.Plan(0 back, 3 kept)", none.toString())
    }

    @Test
    fun aReplacementOverAnotherBusinessComesBack() {
        val old = mapOf("shop" to "clothes", "name" to "Old Threads", "addr:housenumber" to "421")
        val next = mapOf("amenity" to "restaurant", "name" to "Joe's Pizza")
        val changes = BusinessTags.replace(old, next)
        val e = EditData("n42", 5, EditSession.editBase(old, changes), changes, "c", replaceWith = next, kind = EditPlans.kindOf(old))
        assertTrue(EditPlans.plan(e, node(old + ("phone" to "+1-814-555-0100"))) is EditPlan.Upload, "the same business, a phone added: it goes")
        assertEquals(EditPlan.Review(false), EditPlans.plan(e, node(old - "shop" + ("shop" to "shoes"))), "now another business")
        assertEquals(EditPlan.Review(false), EditPlans.plan(e, node(old - "shop" + ("disused:shop" to "clothes"))), "closed meanwhile")
        // 0.1.40: no kind kept, the main key unchanged by the swap: the safe side (review)
        val same = mapOf("amenity" to "cafe", "name" to "Old Cafe")
        val next2 = mapOf("amenity" to "cafe", "name" to "New Cafe")
        val c2 = BusinessTags.replace(same, next2)
        val old040 = EditData("n42", 5, c2.keys.mapNotNull { k -> same[k]?.let { k to it } }.toMap(), c2, "c", replaceWith = next2)
        assertEquals(EditPlan.Review(false), EditPlans.plan(old040, node(same)))
        assertTrue(EditPlans.plan(old040.copy(kind = EditPlans.kindOf(same)), node(same)) is EditPlan.Upload, "with its kind kept it goes")
    }

    @Test
    fun aConfirmationOfAClosedOrMovedPlaceComesBack() {
        val shop = mapOf("shop" to "florist", "name" to "Test Flowers", "opening_hours" to "Mo-Sa 09:00-17:00")
        val t = HoursTarget("n42", "Test Flowers", 42.1, -80.1, "Mo-Sa 09:00-17:00", "n42", nodeLat = 42.1, nodeLon = -80.1)
        val c = HoursCheck.item(t, "c1", 1_795_000_000_000L, ZoneId.of("UTC"), atPlace = true).edit!!
        assertEquals(EditPlan.Upload(mapOf("check_date:opening_hours" to "2026-11-18")), EditPlans.plan(c, node(shop)))
        assertEquals(EditPlan.Review(moved = true), EditPlans.plan(c, node(shop, lat = 42.1 + 120.0 / 111_195.0)), "moved 120 m")
        val closed = shop - "shop" + ("disused:shop" to "florist")
        assertEquals(EditPlan.Review(moved = false), EditPlans.plan(c, node(closed)), "shop -> disused:shop with its hours unchanged")
        assertEquals(EditPlan.Review(moved = false), EditPlans.plan(c, node(mapOf("name" to "Test Flowers", "opening_hours" to "Mo-Sa 09:00-17:00"))), "no kind at all")
        assertEquals(EditPlan.Review(moved = false), EditPlans.plan(c, node(shop + ("opening_hours" to "Mo-Sa 10:00-16:00"))), "other hours now")
    }

    // ---------------------------------------------------------------- 53d

    private val hour = 3_600_000L
    private val sentAt = 1_795_000_000_000L

    private fun sentEdit(id: String = "s1", before: Map<String, String?>? = mapOf("opening_hours" to "Mo-Fr 08:00-18:00"),
                         after: Map<String, String?>? = mapOf("opening_hours" to "Mo-Fr 08:00-17:00"), changeset: Long? = 900) = OutboxItem(
        id, 42.1, -80.1, "Hours", "Test Cafe", sentAt - 1000, OutboxState.SENT, link = "l", sentMs = sentAt,
        edit = EditData("n42", 3, mapOf("opening_hours" to "Mo-Fr 08:00-18:00"), mapOf("opening_hours" to "Mo-Fr 08:00-17:00"), "Updated hours",
            changeset = changeset, newVersion = 4, sentBefore = before, sentAfter = after),
    )

    @Test
    fun undoIsOfferedFor12HoursOnce() {
        val s = sentEdit()
        assertTrue(Undo.offered(s, listOf(s), sentAt + 12 * hour - 1))
        assertFalse(Undo.offered(s, listOf(s), sentAt + 12 * hour), "12 hours on")
        assertFalse(Undo.offered(s, listOf(s), sentAt - 1), "a clock before the send")
        val u = Undo.item(s, "u1", sentAt + hour)!!
        assertFalse(Undo.offered(u, listOf(u, s), sentAt + hour), "an undo")
        assertFalse(Undo.offered(s, listOf(u, s), sentAt + hour), "an undo of it exists (waiting)")
        assertFalse(Undo.offered(s, listOf(u.copy(state = OutboxState.REFUSED, status = 409), s), sentAt + hour), "... in any state")
        assertFalse(Undo.offered(s.copy(edit = s.edit!!.copy(undone = true)), listOf(s), sentAt + hour), "undone")
        assertFalse(Undo.offered(sentEdit(before = emptyMap(), after = emptyMap(), changeset = null), emptyList(), sentAt + hour), "an Already item")
        val add = OutboxItem("a1", 1.0, 2.0, "t", "x", 1L, OutboxState.SENT, sentMs = sentAt, add = AddData("cafe", mapOf("amenity" to "cafe"), "c", node = 5, changeset = 1))
        assertFalse(Undo.offered(add, listOf(add), sentAt + hour), "a new place")
        val note = OutboxItem("n1", 1.0, 2.0, "t", null, 1L, OutboxState.SENT, noteId = 3, sentMs = sentAt)
        assertFalse(Undo.offered(note, listOf(note), sentAt + hour), "a note")
        assertFalse(Undo.offered(s.copy(state = OutboxState.WAITING), listOf(s), sentAt + hour), "not sent")
        // a 0.1.40 item: no record of what went: its base is it
        val old = sentEdit(before = null, after = null)
        assertTrue(Undo.offered(old, listOf(old), sentAt + hour))
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-18:00"), Undo.before(old))
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-17:00"), Undo.after(old))
        // a 0.1.40 offline replacement (its changes were made again at send time): no Undo
        val rep = old.copy(edit = old.edit!!.copy(baseVersion = null, replaceWith = mapOf("amenity" to "cafe")))
        assertFalse(Undo.offered(rep, listOf(rep), sentAt + hour))
        assertNull(Undo.item(rep, "u2", sentAt))
    }

    @Test
    fun undoPutsBackOnlyWhatIsStillOurs() {
        val s = OutboxItem(
            "s1", 42.1, -80.1, "Hours, website", "Work", sentAt - 1000, OutboxState.SENT, link = "l", sentMs = sentAt,
            edit = EditData("n42", 3, mapOf("opening_hours" to "Mo-Fr 08:00-18:00"), mapOf("opening_hours" to "Mo-Fr 08:00-17:00", "website" to "https://a.example"),
                "Updated hours and website", changeset = 900, newVersion = 4, packOsm = "n4242", icon = "cafe", typeCode = "cafe",
                sentBefore = mapOf("opening_hours" to "Mo-Fr 08:00-18:00", "website" to null),
                sentAfter = mapOf("opening_hours" to "Mo-Fr 08:00-17:00", "website" to "https://a.example")),
            atPlace = true,
        )
        val u = Undo.item(s, "u1", sentAt + hour)!!
        val ue = u.edit!!
        assertEquals("s1", ue.undoOf)
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-17:00", "website" to "https://a.example"), ue.base, "the values the edit wrote")
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-18:00", "website" to null), ue.changes, "the ones before")
        assertEquals(OnlineTexts.undoComment(null), ue.comment, "no name kept: it is made at send time (OP-1)")
        assertEquals("Undid my change to a place", ue.comment)
        assertFalse(u.atPlace, "an undo reports nothing seen: no source")
        assertNull(u.speedMph)
        assertEquals(OutboxState.WAITING, u.state)
        assertEquals("n4242", ue.packOsm)
        assertEquals(4, ue.baseVersion)
        assertEquals(42.1, u.lat)
        assertEquals(listOf("Hours: Mon–Fri 8 AM–5 PM; Sat–Sun closed → Mon–Fri 8 AM–6 PM; Sat–Sun closed", "Website: https://a.example → (none)"), Undo.lines(s))
        assertEquals(Undo.lines(s).joinToString("\n"), u.text)
        // at send time: as the edit left it: all back
        val now = mapOf("amenity" to "cafe", "opening_hours" to "Mo-Fr 08:00-17:00", "website" to "https://a.example")
        assertEquals(Undo.Plan(mapOf("opening_hours" to "Mo-Fr 08:00-18:00", "website" to null), emptyList()), Undo.plan(ue, now))
        // one key changed to a third value: kept as they made it
        val third = Undo.plan(ue, now + ("opening_hours" to "Mo-Su 06:00-22:00"))
        assertEquals(mapOf("website" to null), third.back)
        assertEquals(listOf("opening_hours"), third.kept)
        assertEquals("Undo.Plan(1 back, 1 kept)", third.toString(), "no values printed (OP-9)")
        // a key already as before: neither
        val gone = Undo.plan(ue, now - "website")
        assertEquals(mapOf("opening_hours" to "Mo-Fr 08:00-18:00"), gone.back)
        assertEquals(emptyList<String>(), gone.kept)
        // a speed limit's undo keeps its road flag
        val road = s.copy(edit = s.edit!!.copy(osm = "w500", road = true, sentBefore = mapOf("maxspeed" to "35 mph"), sentAfter = mapOf("maxspeed" to "45 mph")), speedMph = 45)
        val ru = Undo.item(road, "u2", sentAt)!!
        assertTrue(ru.edit!!.road)
        assertNull(ru.speedMph)
        assertEquals(listOf("Speed limit: 45 mph → 35 mph"), Undo.lines(road))
        assertNotNull(ru.edit!!.undoOf)
        // the fixer's review: its public comment names the road as the limit's own did (its name, else its ref), never "a place"
        assertEquals("Undid my change to US 6", EditPlans.sendComment(ru, way(mapOf("highway" to "primary", "ref" to "US 6", "maxspeed" to "45 mph"))))
        assertEquals("Undid my change to Test Road", EditPlans.sendComment(ru, way(mapOf("highway" to "primary", "name" to "Test Road", "ref" to "US 6"))))
        assertEquals("Undid my change to a road", EditPlans.sendComment(ru, way(mapOf("highway" to "residential", "maxspeed" to "45 mph"))))
        assertEquals("Undid my change to a place", EditPlans.sendComment(u, node(mapOf("amenity" to "cafe", "ref" to "7"))), "a place's ref is no name")
    }
}
