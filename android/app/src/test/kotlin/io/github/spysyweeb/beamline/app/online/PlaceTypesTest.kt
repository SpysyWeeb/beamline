package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Batch O3 (docs/online-design.md 4.3, docs/next-round.md items 25 and 26): the addable types' tags
 * (against the search's categories.json), the tag swap of a new business in an old place, the spot
 * check's rules (duplicates, "Already on the map here", an address alone), and the own changes.
 */
class PlaceTypesTest {
    private fun categories(): List<JsonObject> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "packbuilder/search/categories.json").isFile) dir = dir.parentFile
        val f = File(dir!!, "packbuilder/search/categories.json")
        return ((Json.parseToJsonElement(f.readText()) as JsonObject)["categories"] as JsonArray).map { it as JsonObject }
    }

    /** One match rule of categories.json against [tags]: plain keys equal (or one of a list), "~" a regex, "!" absent or other. */
    private fun matches(rule: JsonObject, tags: Map<String, String>): Boolean = rule.all { (k, v) ->
        val values = (v as? JsonArray)?.map { (it as JsonPrimitive).content } ?: listOf((v as JsonPrimitive).content)
        when {
            k.startsWith("!") && k.endsWith("~") -> tags[k.drop(1).dropLast(1)]?.let { t -> values.none { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(t) } } ?: true
            k.startsWith("!") -> tags[k.drop(1)] !in values
            k.endsWith("~") -> tags[k.dropLast(1)]?.let { t -> values.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(t) } } ?: false
            else -> values.contains("*") && k in tags || tags[k] in values
        }
    }

    @Test
    fun everyAddableTypeIsASearchCategoryAndItsTagsMatchIt() {
        val cats = categories().associateBy { (it["id"] as JsonPrimitive).content }
        for (t in PlaceTypes.ADDABLE) {
            val c = cats[t.code]
            assertNotNull(c, "category ${t.code}")
            assertEquals((c!!["label"] as JsonPrimitive).content, t.label, "label of ${t.code}")
            assertEquals((c["group"] as JsonPrimitive).content, t.group, "group of ${t.code}")
            val rules = (c["match"] as JsonArray).map { it as JsonObject }
            assertTrue(rules.any { matches(it, t.tags) }, "the tags of ${t.code} match its category")
            assertFalse((c["umbrella"] as? JsonPrimitive)?.content == "true", "no umbrella: ${t.code}")
        }
        assertEquals(PlaceTypes.ADDABLE.size, PlaceTypes.ADDABLE.map { it.code }.toSet().size)
    }

    @Test
    fun theTypeOfAnObject() {
        assertEquals("pizza", PlaceTypes.of(mapOf("amenity" to "restaurant", "cuisine" to "pizza;italian"))?.code)
        assertEquals("restaurant", PlaceTypes.of(mapOf("amenity" to "restaurant", "cuisine" to "thai"))?.code)
        assertEquals("Hairdresser", PlaceTypes.words(mapOf("shop" to "hairdresser")))
        assertNull(PlaceTypes.of(mapOf("building" to "yes")))
    }

    @Test
    fun aNewBusinessInAnOldPlaceSwapsTheBusinessTagsAndKeepsThePlace() {
        val old = mapOf(
            "shop" to "clothes", "name" to "Old Threads", "brand" to "Threads", "brand:wikidata" to "Q1", "opening_hours" to "Mo-Sa 10:00-18:00",
            "phone" to "+1 814 555 0100", "website" to "https://threads.example", "contact:facebook" to "threads", "payment:cash" to "yes",
            "addr:housenumber" to "421", "addr:street" to "State Street", "building" to "retail", "building:levels" to "2", "wheelchair" to "yes",
            "note" to "a note for mappers", "check_date" to "2024-05-01",
        )
        val next = mapOf("amenity" to "restaurant", "cuisine" to "pizza", "name" to "Joe's Pizza", "opening_hours" to "Mo-Su 11:00-22:00")
        val ch = BusinessTags.replace(old, next)
        for (k in listOf("shop", "brand", "brand:wikidata", "phone", "website", "contact:facebook", "payment:cash", "check_date")) {
            assertTrue(ch.containsKey(k) && ch[k] == null, "$k goes")
        }
        assertEquals("Joe's Pizza", ch["name"])
        assertEquals("restaurant", ch["amenity"])
        assertEquals("Mo-Su 11:00-22:00", ch["opening_hours"])
        for (k in listOf("addr:housenumber", "addr:street", "building", "building:levels", "wheelchair", "note")) assertFalse(k in ch, "$k stays")
        val after = PlaceEdits.apply(old, ch)
        assertEquals(setOf("addr:housenumber", "addr:street", "building", "building:levels", "wheelchair", "note") + next.keys, after.keys)
        assertEquals(listOf("addr:housenumber", "addr:street", "building", "building:levels", "note", "wheelchair"), BusinessTags.kept(old, next))
        // a closed place (disused:) taken over by a new business loses the old lifecycle tag too
        assertTrue(BusinessTags.replace(mapOf("disused:shop" to "bakery", "name" to "Gone"), next).let { it.containsKey("disused:shop") && it["disused:shop"] == null })
        val lines = OnlineTexts.replaceLines(old, next, ch)
        assertEquals("Replaces Old Threads: name, type, hours, phone, website change; address and building kept.", lines[0])
    }

    @Test
    fun anAddressAloneGetsTheBusinessTagsAndLosesNothing() {
        val addr = mapOf("addr:housenumber" to "425", "addr:street" to "State Street", "addr:postcode" to "16501")
        assertTrue(BusinessTags.isBareAddress(addr))
        assertFalse(BusinessTags.isBareAddress(addr + ("shop" to "bakery")))
        val next = mapOf("amenity" to "cafe", "name" to "Corner Cafe")
        val ch = BusinessTags.replace(addr, next)
        assertEquals(next, ch)
        assertTrue(OnlineTexts.replaceLines(addr, next, ch)[0].startsWith("Goes onto the address"))
    }

    // ---------------------------------------------------------------- the spot check

    private fun p(name: String?, code: String?, m: Double, osm: String? = null, fromOsm: Boolean = false, tags: Map<String, String> = emptyMap(), inB: Boolean = false) =
        NearbyPlace(osm, name, code, code?.let { PlaceTypes.byCode(it)?.label }, 0.0, 0.0, m, fromOsm, tags, inBuilding = inB)

    @Test
    fun duplicateRules() {
        val restaurant = PlaceTypes.byCode("restaurant")!!
        val atm = PlaceTypes.byCode("atm")!!
        val places = listOf(
            p("Joe's Diner", "restaurant", 20.0, "n1"),
            p("Joes Diner", "breakfast", 40.0, "n2"),
            p("Joe's Diner", "restaurant", 80.0, "n3"),
            p("Pizza Palace", "pizza", 10.0, "n4"),
            p(null, "atm", 15.0, "n5"),
            p(null, "atm", 40.0, "n6"),
        )
        assertEquals(listOf("n1", "n2"), SpotCheck.duplicates(restaurant, "Joe's Diner", places).map { it.osm }, "same name within 50 m, any type")
        assertEquals(listOf("n1"), SpotCheck.duplicates(restaurant, "Joe's Diners", places).map { it.osm }, "same type, a similar name")
        assertEquals(emptyList<String>(), SpotCheck.duplicates(restaurant, "Sushi Bar", places).map { it.osm })
        assertEquals(listOf("n5"), SpotCheck.duplicates(atm, "", places).map { it.osm }, "an unnamed type: the same type within 25 m")
        assertEquals(listOf("n2"), SpotCheck.duplicates(restaurant, "Joe's Diner", places, decided = setOf("n1")).map { it.osm }, "already decided")
        assertTrue(SpotCheck.similar("subway", "subway 123"))
        assertTrue(SpotCheck.similar("dunkin", "dunkn"))
        assertFalse(SpotCheck.similar("arbys", "wendys"))
    }

    @Test
    fun alreadyHereAndAnAddressAlone() {
        val shop = mapOf("shop" to "clothes", "name" to "Primo")
        val places = listOf(
            p("Primo", "clothes", 12.0, "n1", fromOsm = true, tags = shop),
            p("Far Shop", "clothes", 45.0, "n2", fromOsm = true, tags = mapOf("shop" to "gift", "name" to "Far Shop")),
            p("Upstairs", null, 45.0, "n3", fromOsm = true, tags = mapOf("office" to "lawyer", "name" to "Upstairs"), inB = true),
            p(null, null, 8.0, "n4", fromOsm = true, tags = mapOf("addr:housenumber" to "421", "addr:street" to "State Street")),
        )
        assertEquals(listOf("n1", "n3"), SpotCheck.here(places).map { it.osm }, "within 30 m or in the same building")
        assertNull(SpotCheck.bareAddress(places).takeIf { SpotCheck.here(places).isEmpty() })
        assertEquals("n4", SpotCheck.bareAddress(places.drop(1).filter { it.osm == "n4" })?.osm)
        assertEquals("Primo (Clothing store)", places[0].line())
    }

    /** The owner, 2026-11-21: a monument or a statue at the spot is not a business "already on the map here". */
    @Test
    fun monumentsAndTheLikeAreNotAlreadyHere() {
        val places = listOf(
            p("Perry Monument", null, 5.0, "n1", fromOsm = true, tags = mapOf("historic" to "monument", "name" to "Perry Monument")),
            p("Bronze Frog", null, 6.0, "n2", fromOsm = true, tags = mapOf("tourism" to "artwork", "name" to "Bronze Frog")),
            p("War Memorial", "historic=memorial", 7.0, "w3"),                       // from the pack: a raw historic type
            p("Lookout", "tourism=viewpoint", 8.0, "n4"),
            p("Museum Shop", null, 9.0, "n5", fromOsm = true, tags = mapOf("tourism" to "artwork", "shop" to "gift", "name" to "Museum Shop")),
            p("Corner Cafe", "cafe", 10.0, "n6"),                                    // from the pack: a category
            p("Florist", "shop=florist", 11.0, "n7"),                                // from the pack: a raw shop type
        )
        assertEquals(listOf("n5", "n6", "n7"), SpotCheck.here(places).map { it.osm })
        assertTrue(BusinessTags.isBusiness(mapOf("disused:shop" to "yes")), "a closed business still occupies its spot")
        assertFalse(BusinessTags.isBusinessType("historic=monument"))
        assertTrue(BusinessTags.isBusinessType("restaurant"))
        // the pack's own category codes for sights, natural places and parking (the review of 2026-11-21)
        for (code in listOf("viewpoint", "attraction", "beach", "peak", "park", "parking")) assertFalse(BusinessTags.isBusinessType(code), code)
        assertFalse(p("Lookout", "viewpoint", 5.0, "n9").business)
    }

    @Test
    fun theMapAnswerAndTheBuildingOutline() {
        // a building around the point, a shop node in it, an address node beside it, a road node (left out)
        val json = """{"version":"0.6","elements":[
            {"type":"node","id":1,"lat":42.1000,"lon":-80.1000},{"type":"node","id":2,"lat":42.1000,"lon":-80.0990},
            {"type":"node","id":3,"lat":42.1010,"lon":-80.0990},{"type":"node","id":4,"lat":42.1010,"lon":-80.1000},
            {"type":"node","id":10,"lat":42.1008,"lon":-80.0992,"version":3,"tags":{"shop":"clothes","name":"Primo"}},
            {"type":"node","id":11,"lat":42.1005,"lon":-80.0996,"tags":{"addr:housenumber":"421","addr:street":"State Street"}},
            {"type":"node","id":12,"lat":42.1005,"lon":-80.0996,"tags":{"highway":"crossing"}},
            {"type":"way","id":20,"nodes":[1,2,3,4,1],"tags":{"building":"retail"}}]}"""
        val got = SpotCheck.fromMap(json, 42.1005, -80.0995)!!
        assertEquals(listOf("n11", "n10"), got.map { it.osm })
        val shop = got.first { it.osm == "n10" }
        assertTrue(shop.inBuilding)
        assertEquals(3, shop.version)
        assertTrue(shop.distanceM > SpotCheck.HERE_M, "farther than 30 m but in the building")
        assertEquals(listOf("n10"), SpotCheck.here(got).map { it.osm })
        assertNull(SpotCheck.fromMap("not json", 0.0, 0.0))
        // the pack and OSM merged by OSM id, OSM's object kept with the pack's own id
        val merged = SpotCheck.merge(listOf(p("Primo", "clothes", 50.0, "n10").copy(packOsm = "n999"), p("Other", "bank", 30.0, "n77")), got)
        assertEquals(listOf("n11", "n77", "n10"), merged.map { it.osm })
        assertEquals("n999", merged.first { it.osm == "n10" }.packOsm)
    }

    // ---------------------------------------------------------------- the own changes (item 26)

    private val pa = PackInfo(
        packId = "us-pa", name = "Pennsylvania", state = "PA", osmSnapshot = "2026-11-14", bounds = Bounds(-80.6, 39.7, -74.6, 42.3),
        attribution = "", folder = "", mapFile = "", mapMinZoom = 0, mapMaxZoom = 15, searchFile = null, addressesFile = null,
        addressesSkipped = false, searchProblem = null, searchRankingSha256 = null, inRoutingSet = true, verified = true, bytesOnDisk = 0,
    )

    private fun sentEdit(id: String, changes: Map<String, String?>, replace: Map<String, String>? = null, ms: Long = 1_795_200_000_000L) = OutboxItem(
        id, 42.129, -80.085, "", "Subway", ms, OutboxState.SENT, sentMs = ms,
        edit = EditData("n4365850187", 4, emptyMap(), changes, "c", changeset = 1, replaceWith = replace, packOsm = "n7570266250", icon = "fast_food"),
    )

    @Test
    fun ownChangesOnTheMapInSearchAndOnTheSheet() {
        assertEquals((1L shl 44) or 7570266250L, OwnChanges.tileId("n7570266250"))
        assertEquals(17599756310666L, OwnChanges.tileId("n7570266250"), "the id the us-pa tiles carry for Subway")
        assertEquals((2L shl 44) or 5L, OwnChanges.tileId("w5"))
        val added = OutboxItem("a", 42.13, -80.08, "", "Joe's", 1_795_200_000_000L, OutboxState.SENT, sentMs = 1_795_200_000_000L,
            add = AddData("pizza", mapOf("amenity" to "restaurant", "cuisine" to "pizza", "name" to "Joe's", "opening_hours" to "Mo-Su 11:00-22:00"), "c", node = 77))
        val icons = { code: String -> "icon-$code" }
        val one = OwnChanges.of(listOf(added), listOf(pa), icons).single()
        assertTrue(one.drawn)
        assertEquals("icon-pizza", one.icon)
        assertEquals("restaurant", one.kind, "the tiles' kind, which the style colours the label by")
        assertEquals("Joe's", one.ui.name)
        assertEquals("n77", one.ui.osm)
        assertEquals(mapOf("opening_hours" to "Mo-Su 11:00-22:00", "phone" to null, "website" to null), one.ui.overrides)
        // an hours edit alone: nothing on the map, the sheet's values
        val hours = OwnChanges.of(listOf(sentEdit("e", mapOf("opening_hours" to "Mo 08:00-21:00"))), listOf(pa), icons).single()
        assertFalse(hours.drawn)
        assertEquals(emptyList<Long>(), hours.hiddenTileIds)
        assertEquals("Subway", hours.ui.name)
        // a rename: the new label drawn, the tiles' old one hidden (by the pack's id, not the test object's)
        val renamed = OwnChanges.of(listOf(sentEdit("e", mapOf("name" to "Subway Downtown"))), listOf(pa), icons).single()
        assertTrue(renamed.drawn)
        assertEquals(listOf(17599756310666L), renamed.hiddenTileIds)
        assertEquals("Subway", renamed.ui.oldName)
        // a rename made from a search row (no label icon): the type and icon come from the type the form saw
        // (the stored base keeps only the changed keys)
        val fromSearch = sentEdit("e", mapOf("name" to "Subway Downtown")).let {
            it.copy(edit = it.edit!!.copy(icon = null, base = mapOf("name" to "Subway"), typeCode = "sandwich"))
        }
        val typed = OwnChanges.of(listOf(fromSearch), listOf(pa), icons).single()
        assertEquals("sandwich", typed.ui.categoryCode)
        assertEquals("icon-sandwich", typed.icon)
        assertEquals("fast_food", typed.kind)
        // a later edit made from a sheet that showed the generic icon does not take the place's icon away
        val generic = fromSearch.copy(id = "e2", createdMs = fromSearch.createdMs + 1, edit = fromSearch.edit!!.copy(icon = OwnChanges.GENERIC_ICON, typeCode = null))
        assertEquals("icon-sandwich", OwnChanges.of(listOf(fromSearch, generic), listOf(pa), icons).single().icon)
        // a closing: hidden, not drawn, closed in search and on the sheet
        val closed = OwnChanges.of(listOf(sentEdit("e", mapOf("amenity" to null, "disused:amenity" to "fast_food"))), listOf(pa), icons).single()
        assertTrue(closed.ui.closed)
        assertFalse(closed.drawn)
        assertEquals(listOf(17599756310666L), closed.hiddenTileIds)
        // a replacement: the new business's type, name and values
        val rep = OwnChanges.of(listOf(sentEdit("e", emptyMap(), mapOf("amenity" to "cafe", "name" to "Corner Cafe"))), listOf(pa), icons).single()
        assertEquals("coffee", rep.ui.categoryCode)
        assertEquals("Corner Cafe", rep.ui.name)
        assertEquals("icon-coffee", rep.icon)
        assertEquals(mapOf("opening_hours" to null, "phone" to null, "website" to null), rep.ui.overrides)
        // a note or a waiting item is not shown
        assertEquals(0, OwnChanges.of(listOf(added.copy(state = OutboxState.WAITING)), listOf(pa), icons).size)
    }

    /** OP test 15 (0.1.41, 53c and 53d): an undo, an undone edit and a confirmation of hours show nothing as the user's own. */
    @Test
    fun anUndoAnUndoneEditAndAConfirmationShowNothing() {
        val icons = { code: String -> "icon-$code" }
        val renamed = sentEdit("e", mapOf("name" to "Subway Downtown"))
        assertEquals(1, OwnChanges.of(listOf(renamed), listOf(pa), icons).size)
        // undone: the pack's value is right again
        val undone = renamed.copy(edit = renamed.edit!!.copy(undone = true))
        assertEquals(0, OwnChanges.of(listOf(undone), listOf(pa), icons).size)
        // the undo itself, its values the reverse of the edit's
        val undo = sentEdit("u", mapOf("name" to "Subway")).let { it.copy(edit = it.edit!!.copy(base = mapOf("name" to "Subway Downtown"), undoOf = "e")) }
        assertEquals(0, OwnChanges.of(listOf(undo), listOf(pa), icons).size)
        // a confirmation: the hours as they were, a check date: nothing the map shows
        val confirm = sentEdit("c", mapOf("opening_hours" to "Mo-Su 06:00-23:00", "check_date:opening_hours" to "2026-11-28"))
            .let { it.copy(edit = it.edit!!.copy(confirm = "opening_hours")) }
        assertEquals(0, OwnChanges.of(listOf(confirm), listOf(pa), icons).size)
        assertEquals(0, OwnChanges.of(listOf(undone, undo, confirm), listOf(pa), icons).size)
        // an edit OSM already had (nothing uploaded) still shows the user's value until a pack has it
        val already = renamed.copy(edit = renamed.edit!!.copy(changeset = null, sentBefore = emptyMap(), sentAfter = emptyMap()))
        assertEquals("Subway Downtown", OwnChanges.of(listOf(already), listOf(pa), icons).single().ui.name)
    }

    @Test
    fun theOverlayStepsAsideForAPackMadeAfterIt() {
        val sent = java.time.LocalDate.of(2026, 11, 20).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + 3_600_000
        val at = GeoPoint(42.129, -80.085)
        assertFalse(OwnChanges.inPack(at, sent, listOf(pa)), "a pack of 2026-11-14 cannot have it")
        assertFalse(OwnChanges.inPack(at, sent, listOf(pa.copy(osmSnapshot = "2026-11-20"))), "the same day: not sure, still shown")
        assertTrue(OwnChanges.inPack(at, sent, listOf(pa.copy(osmSnapshot = "2026-11-21"))))
        assertFalse(OwnChanges.inPack(GeoPoint(40.0, -83.0), sent, listOf(pa.copy(osmSnapshot = "2026-12-01"))), "a pack that does not cover it")
        val item = sentEdit("e", mapOf("name" to "New"), ms = sent)
        assertEquals(0, OwnChanges.of(listOf(item), listOf(pa.copy(osmSnapshot = "2026-12-01")), { null }).size)
    }
}
