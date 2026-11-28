package io.github.spysyweeb.beamline.app.online

import java.time.Instant
import java.time.ZoneId

/**
 * 0.1.41, 53c: the place the user confirms the hours of, as the place window or the arrival card has it
 * ([PlaceQuestions.hoursTarget]). [toString] prints none of it (PV2).
 */
data class HoursTarget(
    /** The object to edit (AppGraph.osmFor applied: a test object in a test build). */
    val osm: String,
    /** The place's name on the map (the pack's `PoiDetails.name`), never a saved place's label; local only (OP-1): the public comment takes the element's own name at send time. */
    val name: String?,
    /** The place's own point (the pack entry's, else the window's): the item's point and the "at the place" test. */
    val lat: Double,
    val lon: Double,
    /** The hours the user saw, as OSM's text (the window's `poi.openingHours`), not the words shown. */
    val hours: String,
    /** The pack's own object (the window's `poi.osm`). */
    val packOsm: String? = null,
    val icon: String? = null,
    /** OP-5: the pack entry's point of a node (its move check at send time); null: a way, or not known. */
    val nodeLat: Double? = null,
    val nodeLon: Double? = null,
    /**
     * The fixer's review: the window's title, which names the item in My reports when the place has no name on the map
     * ([name] null). Local only: it can be a saved place's label, so no public comment is made from it.
     */
    val label: String? = null,
) {
    override fun toString(): String = "HoursTarget"
}

/**
 * 0.1.41, 53c (docs/online-design.md 4.5 "Still right?", 4.8): a confirmation of a place's hours is an edit that
 * writes only `check_date:opening_hours=<today>` (wiki Key:check_date) and goes only while OSM still has the hours the
 * user saw ([EditPlans.plan]: its `opening_hours` is "changed" to the value seen, so it conflicts exactly when someone
 * changed the hours). Asked again once the user's own check is [AGAIN_AFTER_DAYS] old ([due]). Plain Kotlin.
 */
object HoursCheck {
    const val KEY = "opening_hours"
    const val CHECK_KEY = PlaceEdits.HOURS_CHECK_DATE
    /** Asked again once the user's own check is this old (StreetComplete's interval for hours; wiki Key:check_date). */
    const val AGAIN_AFTER_DAYS = 365L
    private const val DAY_MS = 86_400_000L

    /** Today as OSM writes a check date (YYYY-MM-DD), in the phone's time zone. */
    fun date(nowMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().toString()

    /** The hours stay as seen (the confirmation goes only while OSM still has them) and the check date is set. */
    fun changes(seen: String, date: String): Map<String, String?> = linkedMapOf(KEY to seen, CHECK_KEY to date)

    /** The changeset's comment (OP-1: made at send time from the element's name; [name] null: "a place"). */
    fun comment(name: String?): String = "Confirmed the opening hours of ${name ?: "a place"}"

    /** My reports' line: "Hours checked: Mon–Fri 8 AM–6 PM". */
    fun lines(seen: String): List<String> = listOf("${PlaceEdits.label(CHECK_KEY)}: " + (WeekHours.of(seen)?.plain() ?: seen))

    /**
     * The outbox item (WAITING; [atPlace] from the on-site check). Its point is the place's, never the phone's; its local
     * name the map's, else the window's ([HoursTarget.label]), never "The point you pressed".
     */
    fun item(t: HoursTarget, id: String, nowMs: Long, zone: ZoneId, atPlace: Boolean): OutboxItem = OutboxItem(
        id = id, lat = t.lat, lon = t.lon, text = lines(t.hours).joinToString("\n"), placeName = t.name ?: t.label, createdMs = nowMs,
        edit = EditData(
            t.osm, null, mapOf(KEY to t.hours), changes(t.hours, date(nowMs, zone)), comment(t.name),
            packOsm = t.packOsm, icon = t.icon, nodeLat = t.nodeLat, nodeLon = t.nodeLon, confirm = KEY,
        ),
        atPlace = atPlace,
    )

    /** AP-8: a place the user added is matched by its new node against the object or the pack's object. */
    private fun ofPlace(node: String, osm: String, packOsm: String?) = node == osm || (packOsm != null && node == packOsm)

    /**
     * When the user last checked the hours of this place themselves: the newest of their confirmations and of their
     * edits that set the hours, waiting or sent (not undone, not an undo, not refused or in review), and (AP-8) of the
     * places they added with hours, or null. From the outbox only: "Remove from this list" forgets it.
     */
    fun lastCheckedMs(items: List<OutboxItem>, osm: String, packOsm: String? = null): Long? = items.filter { i ->
        if (i.state != OutboxState.WAITING && i.state != OutboxState.SENT) return@filter false
        val e = i.edit
        val a = i.add
        when {
            e != null -> (e.osm == osm || (packOsm != null && e.packOsm == packOsm)) && e.undoOf == null && !e.undone && e.changes[KEY] != null
            a != null -> i.state == OutboxState.SENT && a.node != null && ofPlace("n${a.node}", osm, packOsm) && a.tags[KEY] != null
            else -> false
        }
    }.maxOfOrNull { it.createdMs }

    /** "Hours still right?" may be asked: OSM has hours (the map's [hours]) and the user has not checked them for a year. */
    fun due(hours: String?, items: List<OutboxItem>, osm: String, packOsm: String?, nowMs: Long): Boolean {
        if (hours.isNullOrBlank()) return false
        val last = lastCheckedMs(items, osm, packOsm) ?: return true
        return nowMs - last >= AGAIN_AFTER_DAYS * DAY_MS
    }
}
