package io.github.spysyweeb.beamline.app.online

/**
 * One opening time of a day group (batch O3, docs/next-round.md item 24): [from] in minutes after
 * midnight (0..1439), [to] (1..1440, or 0..[from] for a closing after midnight, shown "(next day)").
 * A [to] at or before [from] closes the next morning; 1440 is midnight at the end of the day.
 */
data class TimeRange(val from: Int, val to: Int) {
    init {
        require(from in 0 until 1440 && to in 0..1440) { "a time of day" }
    }

    /** The end in minutes after the group's own midnight (past 1440 for the next morning). */
    val end: Int get() = if (to <= from) to + 1440 else to

    /** It closes after midnight, on the next day ("(next day)"); not for a closing at midnight itself. */
    val nextDay: Boolean get() = end > 1440

    fun span(): IntRange = from until end
}

/**
 * Days that share their hours (batch O3, item 24): [days] (0 Monday .. 6 Sunday, sorted, at least
 * one), open [allDay] ("Open 24 hours") or in one or more [ranges] (a split day has two).
 */
data class DayGroup(val days: List<Int>, val ranges: List<TimeRange>, val allDay: Boolean = false) {
    fun spans(): List<IntRange> = if (allDay) listOf(0 until 1440) else ranges.map { it.span() }
}

/** The public holidays line (item 24 d). */
enum class PhMode {
    /** "Not sure (leave out)": no holiday rule (the default). */
    UNSURE,
    /** "Closed": `PH off`. */
    CLOSED,
    /** "Same as usual": no holiday rule either; OpenStreetMap's documentation says none is needed then. */
    SAME,
    /** Its own hours: `PH 10:00-14:00`. */
    CUSTOM,
}

/**
 * The friendlier editor's hours (batch O3, docs/next-round.md item 24): day groups instead of seven
 * day blocks, each with one or more times or "Open 24 hours"; a day in no group is closed; and the
 * public holidays line. [text] writes OSM's text through [WeekHours] (the usual short form, "Mo-Th
 * 15:00-22:00; Fr,Sa 15:00-02:00") with the holiday rule last ("; PH off"); [of] reads a text back
 * into groups, grouped the same way, or gives null when the editor cannot express it without loss
 * (then the form shows the text read-only and keeps it). [check] reads the written text back with the
 * hours parser (OpeningHours, through [WeekHours.readBack]) and compares.
 */
data class HoursDraft(
    val groups: List<DayGroup>,
    val ph: PhMode = PhMode.UNSURE,
    val phRanges: List<TimeRange> = emptyList(),
    val phAllDay: Boolean = false,
) {
    /** The week the groups give (a day in no group: closed; a day in two groups: both groups' times). */
    fun week(): WeekHours {
        val days = List(7) { ArrayList<IntRange>() }
        for (g in groups) for (d in g.days) days[d].addAll(g.spans())
        return WeekHours(days.map { WeekHours.merge(it) })
    }

    /** OSM's text; "" when no day is open (the form then removes the tag; a holiday rule alone says nothing). */
    fun text(): String {
        val w = week().text()
        if (w.isEmpty()) return ""
        val ph = when (ph) {
            PhMode.CLOSED -> "PH off"
            PhMode.CUSTOM -> phRanges.takeIf { !phAllDay && it.isNotEmpty() }
                ?.let { r -> "PH " + WeekHours.merge(r.map { it.span() }).joinToString(",") { WeekHours.span(it) } }
                ?: "PH 00:00-24:00"
            PhMode.UNSURE, PhMode.SAME -> null
        }
        return if (ph == null) w else "$w; $ph"
    }

    /** The groups as [of] gives them back from [text] (equal days together, Monday first), and SAME read as UNSURE. */
    fun normalized(): HoursDraft = of(text()) ?: this

    /** Whether [text] reads back as this draft (the writer's check, item 24 f). */
    fun check(): Boolean {
        val t = text()
        val back = of(t) ?: return false
        val wb = WeekHours.readBack(stripPh(t) ?: return false) ?: return false
        return wb == week() && back.week() == week() && back.ph == (if (ph == PhMode.SAME || t.isEmpty()) PhMode.UNSURE else ph) &&
            (back.ph != PhMode.CUSTOM || back.phSpans() == phSpans())
    }

    private fun phSpans(): List<IntRange> = if (phAllDay) listOf(0 until 1440) else WeekHours.merge(phRanges.map { it.span() })

    /** Days in no group (shown as Closed). */
    fun closedDays(): List<Int> = (0 until 7).filter { d -> groups.none { d in it.days } }

    companion object {
        val EMPTY = HoursDraft(emptyList())
        private val DAY = "(?:Mo|Tu|We|Th|Fr|Sa|Su|PH)"
        private val RULE = Regex("^($DAY(?:[-,]$DAY)*)\\s+(.+)$")
        private val TIMES = Regex("^\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2}(?:,\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2})*$")

        /** The day groups of [w]: days with the same times together, groups in the order of their first day. */
        fun groupsOf(w: WeekHours): List<DayGroup> {
            val out = ArrayList<DayGroup>()
            val done = BooleanArray(7)
            for (i in 0 until 7) {
                val spans = WeekHours.merge(w.days[i])
                if (done[i] || spans.isEmpty()) continue
                val same = (i until 7).filter { !done[it] && WeekHours.merge(w.days[it]) == spans }
                same.forEach { done[it] = true }
                out += if (spans == listOf(0 until 1440)) DayGroup(same, emptyList(), allDay = true)
                else DayGroup(same, spans.map { rangeOf(it) })
            }
            return out
        }

        /** A span (minutes after the day's midnight, end exclusive, may pass 1440) as a [TimeRange]. */
        fun rangeOf(r: IntRange): TimeRange {
            val end = r.last + 1
            return TimeRange(r.first, if (end > 1440) end - 1440 else end)
        }

        /**
         * The draft of an OSM hours [text], or null when the editor cannot show it without loss: the
         * public holiday rules it understands (`PH off`, `PH closed`, `PH 10:00-14:00`, and `PH` beside
         * weekdays: `Su,PH off`) become the holidays line, the rest must be what [WeekHours.of] reads.
         * Null or blank text: no hours (every day closed).
         */
        fun of(text: String?): HoursDraft? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return EMPTY
            if ('"' in t || "SH" in t) return null
            var ph = PhMode.UNSURE
            var phRanges: List<IntRange> = emptyList()
            val rest = ArrayList<String>()
            for (raw in t.split(';')) {
                val r = raw.trim()
                if (r.isEmpty()) continue
                if (!Regex("\\bPH\\b").containsMatchIn(r)) {
                    rest += r
                    continue
                }
                val m = RULE.matchEntire(r) ?: return null
                val parts = m.groupValues[1].split(',')
                if (parts.any { it.contains('-') && it.contains("PH") }) return null
                val spec = m.groupValues[2].trim()
                when {
                    spec == "off" || spec == "closed" -> { ph = PhMode.CLOSED; phRanges = emptyList() }
                    spec == "24/7" -> { ph = PhMode.CUSTOM; phRanges = listOf(0 until 1440) }
                    TIMES.matches(spec) -> {
                        ph = PhMode.CUSTOM
                        phRanges = spec.split(',').map { s -> WeekHours.spanOf(s.substringBefore('-'), s.substringAfter('-')) ?: return null }
                    }
                    else -> return null
                }
                val days = parts.filter { it != "PH" }
                if (days.isNotEmpty()) rest += days.joinToString(",") + " " + spec
            }
            val w = WeekHours.of(rest.joinToString("; ")) ?: return null
            val merged = WeekHours.merge(phRanges)
            val allDay = ph == PhMode.CUSTOM && merged == listOf(0 until 1440)
            return HoursDraft(groupsOf(w), ph, if (allDay) emptyList() else merged.map { rangeOf(it) }, allDay)
        }

        /** [text] without its holiday rules (for the parser's check of the week); null when it has others it cannot strip. */
        private fun stripPh(text: String): String? {
            val rest = ArrayList<String>()
            for (raw in text.split(';')) {
                val r = raw.trim()
                if (r.isEmpty()) continue
                if (!r.startsWith("PH")) rest += r else if (!r.startsWith("PH ")) return null
            }
            return rest.joinToString("; ")
        }
    }
}
