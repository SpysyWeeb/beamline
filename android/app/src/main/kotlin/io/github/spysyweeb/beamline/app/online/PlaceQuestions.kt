package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.feature.drive.ArrivalQuestionUi
import io.github.spysyweeb.beamline.feature.search.HoursUi
import io.github.spysyweeb.beamline.feature.search.PlaceSheetUi
import java.time.Instant
import java.time.ZoneId

/**
 * At a place (0.1.41; docs/online-design.md 4.8, AP1-AP12; docs/next-round.md items 53c, 53g): whether the place
 * window offers "Hours still right?" and which one question the arrival card asks. The year rule of the hours is
 * [HoursCheck.due]; everything else the app passes in. Plain Kotlin: no Android, no clock, no file, no log.
 */
object PlaceQuestions {
    /** AP3: stopped this long at the destination is parked. */
    const val PARKED_MS = 15_000L
    /** AP3: once parked, moving this long in a row hides the question (a GPS jump does not). */
    const val MOVING_MS = 3_000L
    /** AP2: "standing near it" for the window: a fix within 80 m in the last 2 minutes (53e's [RecentPositions]). */
    const val AT_PLACE_NOW_MS = 2 * 60_000L
    /** How often AppRoot looks at the memory while a window is open, and the arrival tick. */
    const val AT_PLACE_POLL_MS = 2_000L
    const val ARRIVAL_TICK_MS = 1_000L
    /** AP6: an answered arrival question is not asked again for this many days. */
    const val ASKED_DAYS = 90L
    /**
     * Item 56's review (phone safety): the session arrives up to 50 m before the route's end (rule A4), so ARRIVED alone
     * can be a car waiting at a red light short of it. The car stands at the stop when it is within this of the route's
     * end, or more than [OFF_LINE_M] from the route's line (a lot, a driveway, a parking place off the road).
     */
    const val AT_END_M = 30.0
    const val OFF_LINE_M = 15.0
    /** [atStop] looks at the route's line only this far back from its end (a car at the stop is near the end). */
    const val LINE_BACK_M = 1_000.0
    private const val YEAR_MS = HoursCheck.AGAIN_AFTER_DAYS * 86_400_000L

    val HOURS_KEYS = setOf(HoursCheck.KEY)
    val PHONE_KEYS = setOf("phone", "contact:phone")
    val WEBSITE_KEYS = setOf("website", "contact:website")

    /**
     * AP4 (+ plan AP-9): kinds (the search pack's category codes, packbuilder/search/categories.json) whose hours a
     * visitor does not read at a door, or that have none to read. [BusinessTags.isBusinessType] already leaves out
     * parks, sights, natural places and parking.
     */
    val NOT_ASKED = setOf(
        "place_of_worship", "police", "fire_station", "hospital", "emergency_room", "school", "university",
        "cemetery", "airport", "train_station", "subway_station", "bus_station", "state_game_lands", "trailhead",
        "boat_launch", "rest_area", "toilets", "rv_dump", "campground", "rv_park",
        "leisure_fishing", "leisure_disc_golf_course", "amenity_weighbridge",
    )
    /** AP4: kinds asked about their hours only (no phone or website of their own). */
    val NO_CONTACT = setOf("atm", "ev_charging")
    /** AP-9: a pack type that is a raw tag ("shop=florist") is asked only for these keys. */
    val RAW_ASKED_KEYS = setOf("shop", "craft", "office", "healthcare")

    enum class Ask { ADD_HOURS, CHECK_HOURS, ADD_PHONE, ADD_WEBSITE, ADD_PHONE_AND_WEBSITE }

    /**
     * A place as its window has it ([facts]): its OSM object (after AppGraph.osmFor), the pack's object, its name on
     * the map ([name]: the pack's, never a saved place's label), the pack's type, the raw hours, phone and website (the
     * user's sent values on top), the hours as shown, and whether the user closed it. [toString] prints none of it (PV2).
     */
    data class PlaceFacts(
        val osm: String, val packOsm: String?, val name: String?, val type: String?,
        val hours: String?, val shown: HoursUi?, val phone: String?, val website: String?, val closed: Boolean,
    ) {
        override fun toString(): String = "PlaceFacts"
    }

    /** The facts of a window's data; null without an OSM object or the pack's details (never a guess). */
    fun facts(ui: PlaceSheetUi, ownClosed: Boolean): PlaceFacts? {
        val osm = ui.osm ?: return null
        val d = ui.poi ?: return null
        return PlaceFacts(osm, d.osm, d.name, d.type, d.openingHours, ui.hours, d.phone, d.website, ownClosed)
    }

    /**
     * The place's own point: the pack entry's (a node's point when the pack was built) when known, else the window's
     * (which for a name can be another POI of it). Every "at the place" test and the targets' point use it.
     */
    fun pointOf(ui: PlaceSheetUi): GeoPoint {
        val lat = ui.poi?.lat
        val lon = ui.poi?.lon
        return if (lat != null && lon != null) GeoPoint(lat, lon) else ui.place.point
    }

    /**
     * OP-5: the node's point for 53a's move check of an edit made offline or a confirmation: the pack entry's point,
     * only when the place is a node and its object is the pack's own (not a test object of a test build).
     */
    fun nodePoint(ui: PlaceSheetUi): Pair<Double, Double>? {
        val osm = ui.osm ?: return null
        val d = ui.poi ?: return null
        if (OsmRef.parse(osm)?.type != OsmType.NODE || osm != d.osm) return null
        val lat = d.lat ?: return null
        val lon = d.lon ?: return null
        return lat to lon
    }

    /** AP4: the app edits nodes and ways; a relation opens on openstreetmap.org instead (4.2). */
    fun editable(osm: String?): Boolean = OsmRef.parse(osm)?.type.let { it == OsmType.NODE || it == OsmType.WAY }

    /** AP4 (+ AP-9): a business of a kind a visitor reads hours at; a raw pack type only for shops, crafts, offices and health care. */
    fun asksAbout(type: String?): Boolean {
        if (type == null || !BusinessTags.isBusinessType(type) || type in NOT_ASKED) return false
        return !type.contains('=') || type.substringBefore('=') in RAW_ASKED_KEYS
    }

    private fun ofPlace(e: EditData, osm: String, packOsm: String?) = e.osm == osm || (packOsm != null && e.packOsm == packOsm)

    private fun addOf(a: AddData, osm: String, packOsm: String?): Boolean {
        val node = a.node?.let { "n$it" } ?: return false
        return node == osm || (packOsm != null && node == packOsm)
    }

    /** AP4, AP10: an edit of the user's for this place waits (WAITING) or is in review (REVIEW; [reviewOnly]: only that). */
    fun pending(items: List<OutboxItem>, osm: String, packOsm: String?, reviewOnly: Boolean = false): Boolean = items.any { i ->
        val e = i.edit
        e != null && !e.road && ofPlace(e, osm, packOsm) &&
            (i.state == OutboxState.REVIEW || (!reviewOnly && i.state == OutboxState.WAITING))
    }

    /**
     * AP4: when the user last set or removed one of [keys] of this place (waiting or sent; not an undo, undone or a
     * confirmation), or null. AP-2: a replacement counts only what the user set ([EditData.replaceWith], not its
     * automatic removals); AP-8: a place the user added counts by its tags once it went.
     */
    fun touchedMs(items: List<OutboxItem>, osm: String, packOsm: String?, keys: Set<String>): Long? = items.filter { i ->
        if (i.state != OutboxState.WAITING && i.state != OutboxState.SENT) return@filter false
        val e = i.edit
        val a = i.add
        when {
            e != null -> ofPlace(e, osm, packOsm) && e.undoOf == null && !e.undone && e.confirm == null &&
                (e.replaceWith?.keys ?: e.changes.keys).any { it in keys }
            a != null -> i.state == OutboxState.SENT && addOf(a, osm, packOsm) && a.tags.keys.any { it in keys }
            else -> false
        }
    }.maxOfOrNull { it.createdMs }

    private fun withinYear(ms: Long?, nowMs: Long) = ms != null && nowMs - ms < YEAR_MS

    /** AP4: the arrival's one question for [f], or null. [nowMs]: the wall clock. */
    fun arrivalAsk(f: PlaceFacts, items: List<OutboxItem>, nowMs: Long): Ask? {
        if (!editable(f.osm) || f.closed || !asksAbout(f.type) || pending(items, f.osm, f.packOsm)) return null
        val readable = f.shown?.week?.isNotEmpty() == true
        if (f.hours.isNullOrBlank()) {
            if (!withinYear(touchedMs(items, f.osm, f.packOsm, HOURS_KEYS), nowMs)) return Ask.ADD_HOURS
        } else if (readable && HoursCheck.due(f.hours, items, f.osm, f.packOsm, nowMs)) {
            return Ask.CHECK_HOURS
        }
        if (f.type in NO_CONTACT) return null
        val phone = f.phone.isNullOrBlank() && !withinYear(touchedMs(items, f.osm, f.packOsm, PHONE_KEYS), nowMs)
        val web = f.website.isNullOrBlank() && !withinYear(touchedMs(items, f.osm, f.packOsm, WEBSITE_KEYS), nowMs)
        return when {
            phone && web -> Ask.ADD_PHONE_AND_WEBSITE
            phone -> Ask.ADD_PHONE
            web -> Ask.ADD_WEBSITE
            else -> null
        }
    }

    /** AP10: the user confirmed these hours today (a confirmation waiting or sent, not undone, made today in [zone]). */
    fun confirmedToday(items: List<OutboxItem>, osm: String, packOsm: String?, nowMs: Long, zone: ZoneId): Boolean {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return items.any { i ->
            val e = i.edit
            e != null && e.confirm == HoursCheck.KEY && ofPlace(e, osm, packOsm) && !e.undone &&
                (i.state == OutboxState.WAITING || i.state == OutboxState.SENT) &&
                Instant.ofEpochMilli(i.createdMs).atZone(zone).toLocalDate() == today
        }
    }

    /**
     * AP10: the window with "Hours still right?" ([PlaceSheetUi.canCheckHours]) or "Hours confirmed"
     * ([PlaceSheetUi.hoursChecked]; AP1: both only while [allowed]). [allowed]: osmAllowed, signed in and no trip driven (GUIDING, REROUTING, ARRIVED);
     * [atPlace]: AP2's 2-minute test for the window's place ([pointOf]).
     */
    fun withHoursCheck(ui: PlaceSheetUi, allowed: Boolean, atPlace: Boolean, items: List<OutboxItem>, nowMs: Long, zone: ZoneId): PlaceSheetUi {
        val shown = ui.hours
        val osm = ui.osm
        val raw = ui.poi?.openingHours
        val packOsm = ui.poi?.osm
        val readable = shown != null && shown.week.isNotEmpty() && osm != null && raw != null
        // the fixer's review (AP1): "Hours confirmed" too only while allowed (signed in, Contribute on, no trip driven)
        val checked = readable && allowed && confirmedToday(items, osm, packOsm, nowMs, zone)
        val can = readable && !checked && allowed && atPlace && editable(osm) && !pending(items, osm, packOsm, reviewOnly = true) &&
            HoursCheck.due(raw, items, osm, packOsm, nowMs)
        return if (can == ui.canCheckHours && checked == ui.hoursChecked) ui else ui.copy(canCheckHours = can, hoursChecked = checked)
    }

    /**
     * AP8, AP12 (+ OP-1, OP-5): the pipeline's [HoursTarget] for a window's data; [hours] the raw text the user saw (the
     * window's now, or the arrival question's). Its name is the place's name on the map ([PoiDetails.name]), never the
     * window's title, which for a saved place is the user's own label; the title goes only as the local
     * [HoursTarget.label] (My reports' line of a place with no name on the map).
     */
    fun hoursTarget(ui: PlaceSheetUi, icon: String?, hours: String? = ui.poi?.openingHours): HoursTarget? {
        val osm = ui.osm ?: return null
        val h = hours?.takeIf { it.isNotBlank() } ?: return null
        val p = pointOf(ui)
        val node = nodePoint(ui)
        return HoursTarget(osm, ui.poi?.name, p.lat, p.lon, h, ui.poi?.osm, icon, node?.first, node?.second, label = ui.title)
    }

    /**
     * AP-11 (+ OP-1 e, OP-5, AP-7): the edit form's target for a window's data (AppFlows.editOnOsm): its name the map's
     * ([PoiDetails.name]; the title only when the pack has none: local, never in a public comment), the pack's values,
     * the place's own point, the node's point, [focus] and [atPlace]. Null without an OSM object.
     */
    fun editTarget(ui: PlaceSheetUi, icon: String?, focus: EditFocus? = null, atPlace: Boolean = false): EditTarget? {
        val osm = ui.osm ?: return null
        val p = pointOf(ui)
        val node = nodePoint(ui)
        return EditTarget(
            osm, ui.poi?.name ?: ui.title, p.lat, p.lon, ui.poi?.name, ui.poi?.openingHours, ui.poi?.phone, ui.poi?.website,
            packOsm = ui.poi?.osm ?: osm, icon = icon, focus = focus, atPlace = atPlace, nodeLat = node?.first, nodeLon = node?.second,
        )
    }

    /**
     * AP-10: the week of "Hours still right?" in the arrival card, in My reports' words ([WeekHours.plain]): "Mon–Fri
     * 8 AM–6 PM", "Sat–Sun closed"; for hours the form cannot express, the window's week ("Mon–Fri 8 AM–6 PM", "Sun
     * closed"); then the window's holiday note.
     */
    fun weekLines(raw: String?, shown: HoursUi?): List<String> {
        val out = ArrayList<String>()
        val plain = raw?.takeIf { it.isNotBlank() }?.let { WeekHours.of(it) }?.plain()
        if (plain != null) {
            out += plain.split("; ")
        } else {
            val week = shown?.week.orEmpty()
            fun words(t: String) = when (t) {
                "Closed" -> "closed"
                "Open 24 hours" -> "open 24 hours"
                else -> t
            }
            var a = 0
            while (a < week.size) {
                var b = a
                while (b + 1 < week.size && week[b + 1].text == week[a].text) b++
                out += (if (a == b) week[a].day else "${week[a].day}–${week[b].day}") + " " + words(week[a].text)
                a = b + 1
            }
        }
        if (out.isNotEmpty()) shown?.holidayNote?.let { out += it }
        return out
    }

    /** The card's words for [s] (texts in OnlineTexts): the edit button reads "Edit hours" beside "Yes" (AP-10). */
    fun arrivalUi(s: ArrivalAsker.Shown): ArrivalQuestionUi = when (s.ask) {
        Ask.ADD_HOURS -> ArrivalQuestionUi(OnlineTexts.ASK_HOURS, emptyList(), null, OnlineTexts.ADD_HOURS, OnlineTexts.NOT_NOW)
        Ask.CHECK_HOURS -> ArrivalQuestionUi(OnlineTexts.HOURS_STILL_RIGHT, s.lines, OnlineTexts.YES, OnlineTexts.EDIT_HOURS, OnlineTexts.NOT_NOW)
        Ask.ADD_PHONE -> ArrivalQuestionUi(OnlineTexts.ASK_PHONE, emptyList(), null, OnlineTexts.ADD_PHONE, OnlineTexts.NOT_NOW)
        Ask.ADD_WEBSITE -> ArrivalQuestionUi(OnlineTexts.ASK_WEBSITE, emptyList(), null, OnlineTexts.ADD_WEBSITE, OnlineTexts.NOT_NOW)
        Ask.ADD_PHONE_AND_WEBSITE -> ArrivalQuestionUi(OnlineTexts.ASK_PHONE_WEBSITE, emptyList(), null, OnlineTexts.ADD_THEM, OnlineTexts.NOT_NOW)
    }

    /**
     * Item 56's review: the car at [car] stands at the stop of the route [line] (the whole route, its end last): within
     * [AT_END_M] of the end, or farther than [OFF_LINE_M] from every stretch of the line's last [LINE_BACK_M]. No
     * position or no line: no. Once a second on the main thread: an index loop back from the end, ending at the
     * first stretch near the car.
     */
    fun atStop(car: GeoPoint?, line: List<GeoPoint>): Boolean {
        if (car == null || line.isEmpty()) return false
        val end = line.last()
        if (SpotCheck.metres(car.lat, car.lon, end.lat, end.lon) <= AT_END_M) return true
        var back = 0.0
        var i = line.lastIndex
        while (i > 0 && back <= LINE_BACK_M) {
            val a = line[i - 1]
            val b = line[i]
            if (toSegmentM(car, a, b) <= OFF_LINE_M) return false
            back += SpotCheck.metres(a.lat, a.lon, b.lat, b.lon)
            i--
        }
        return true
    }

    /** Metres from [p] to the stretch [a]-[b] (flat earth around [p]: stretches of a route are short). */
    private fun toSegmentM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val kx = 111_195.0 * kotlin.math.cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * 111_195.0
        val bx = (b.lon - p.lon) * kx
        val by = (b.lat - p.lat) * 111_195.0
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx
        val y = ay + t * dy
        return kotlin.math.sqrt(x * x + y * y)
    }

    /** AP9: the field the edit button opens the form at. */
    fun focusOf(a: Ask): EditFocus = when (a) {
        Ask.ADD_HOURS, Ask.CHECK_HOURS -> EditFocus.HOURS
        Ask.ADD_PHONE, Ask.ADD_PHONE_AND_WEBSITE -> EditFocus.PHONE
        Ask.ADD_WEBSITE -> EditFocus.WEBSITE
    }
}

/** What one arrival tick knows (AP1-AP6, plan AP-1). [toString]: the phase only (PV2). */
data class ArrivalInput(
    val phase: Phase,
    val motion: Motion?,
    val simulated: Boolean,
    /** AP1: online features and Contribute on, and signed in. */
    val allowed: Boolean,
    /** AP2: 53e's memory: a fix within 80 m of the destination's own point in the last 10 minutes. */
    val atPlace: Boolean,
    val facts: PlaceQuestions.PlaceFacts?,
    val items: List<OutboxItem>,
    /** The wall clock (the year rule). */
    val nowMs: Long,
    /** AP6: the place was answered in the last 90 days, or its question shown in this run ([AskedStore.recent]). */
    val askedRecently: Boolean,
    /** AP-1: the card is on screen now (the app resumed, no picker, layer, stop card or Add stop search over it). */
    val displayed: Boolean = true,
    /** Item 56's review: the car stands at the stop, not on the road short of the route's end ([PlaceQuestions.atStop]). */
    val atStop: Boolean,
) {
    override fun toString(): String = "ArrivalInput($phase)"
}

/**
 * One arrival's question (AP3-AP7), fed about once a second while the trip is ARRIVED (AppFlows.arrivalTick). Plain
 * Kotlin; its only state is this arrival's: since when the car stands, whether it counts as parked, the question
 * chosen, whether it was seen and whether it was answered. Times are monotonic milliseconds.
 */
class ArrivalAsker(private val parkedMs: Long = PlaceQuestions.PARKED_MS, private val movingMs: Long = PlaceQuestions.MOVING_MS) {
    /** The question of this arrival: [hours] the raw text it confirms (CHECK_HOURS), [lines] the week shown. [toString]: the kind only. */
    data class Shown(val ask: PlaceQuestions.Ask, val osm: String, val hours: String?, val lines: List<String>) {
        override fun toString(): String = "Shown($ask)"
    }

    private var stoppedSince: Long? = null
    private var movingSince: Long? = null
    private var parked = false
    private var chosen: Shown? = null
    private var seen = false
    private var answered = false

    /**
     * The question to show now, or null. [onFirstShown] runs once per arrival, with the place's OSM object, on the first
     * tick the chosen question is on screen ([ArrivalInput.displayed], AP-1): AskedStore.noteShown, memory only (AP-6).
     */
    fun update(i: ArrivalInput, elapsedMs: Long, onFirstShown: (String) -> Unit = {}): Shown? {
        if (i.phase != Phase.ARRIVED) {
            reset()
            return null
        }
        when (i.motion) {
            // item 56's review: standing on the road short of the route's end (a red light after an early arrival) is not
            // parked; the 15 s count only at the stop (a question already chosen stays through a GPS jump off the spot)
            Motion.STOPPED -> if (!i.atStop && chosen == null) {
                movingSince = null
                stoppedSince = null
                parked = false
            } else {
                movingSince = null
                val s = stoppedSince ?: elapsedMs.also { stoppedSince = it }
                if (elapsedMs - s >= parkedMs) parked = true
            }
            Motion.MOVING -> {
                val m = movingSince ?: elapsedMs.also { movingSince = it }
                if (!parked || elapsedMs - m >= movingMs) {
                    parked = false
                    stoppedSince = null
                }
            }
            Motion.UNKNOWN, null -> Unit
        }
        if (answered || !parked || i.simulated || !i.allowed || !i.atPlace) return null
        val shown = chosen ?: run {
            val f = i.facts ?: return null
            if (i.askedRecently) return null
            val ask = PlaceQuestions.arrivalAsk(f, i.items, i.nowMs) ?: return null
            val check = ask == PlaceQuestions.Ask.CHECK_HOURS
            Shown(ask, f.osm, f.hours.takeIf { check }, if (check) PlaceQuestions.weekLines(f.hours, f.shown) else emptyList())
                .also { chosen = it }
        }
        if (!seen && i.displayed) {
            seen = true
            onFirstShown(shown.osm)
        }
        return shown
    }

    /** An answer (Yes, the edit button, Not now): the question it answered, once; nothing more is asked this arrival. */
    fun answer(): Shown? {
        val s = chosen?.takeIf { !answered && parked }
        answered = true
        return s
    }

    fun reset() {
        stoppedSince = null
        movingSince = null
        parked = false
        chosen = null
        seen = false
        answered = false
    }
}
