package io.github.spysyweeb.beamline.app.online

/**
 * Hours as people write them, read into the editor's draft (docs/next-round.md item 34): "Mon–Fri
 * 9:30am–6pm, Sat 9–5:30, Sun closed", "Daily 6:00 am - 10:00 pm", "Weekdays 8 to 5; Sat 9-1", "Open 24
 * hours", or a sign's lines pasted one under the other. Typing a week of hours on a phone is slow; a
 * paste of what the business itself publishes is one step, and the editor then shows the week for the
 * user to check before anything is sent.
 *
 * What it reads: day names in full or short ("Monday", "Mon", "Mo", "Thurs"), day ranges ("Mon-Fri",
 * "Sat to Sun", "Fri-Mon"), day lists ("Mon, Wed & Fri"), "daily", "every day", "weekdays", "weekends";
 * times with or without minutes and am/pm ("9", "9:30", "9.30am", "6 PM", "noon", "midnight", "17:00",
 * "0930"); "closed"; "24 hours". A time range without days belongs to the days before it (a second
 * time of a split day) or, when no day was named at all, to every day. OSM's own text ("Mo-Fr
 * 09:00-17:00") is read as it is.
 *
 * AM and PM that are not written are worked out: "9-6pm" opens at 9 AM, "1-5pm" at 1 PM, "9-5:30"
 * closes at 5:30 PM, "5pm-2am" closes the next morning. Where neither time says, [Read.guessed] is
 * true and the form asks the user to check.
 *
 * Null when any part of the text is not understood: a half-read week would be worse than none. That
 * includes an order it does not read (the review of 2026-11-21): days after their times ("9am-5pm
 * Mon-Fri"), a day named twice ("Mon-Fri 9-5, Fri 9-3", "Mon-Fri 9-5, Wed closed"), days with no times,
 * and a span that runs from an evening to the next afternoon ("9pm-5pm", a slip for 9am).
 */
object HoursText {
    /** The draft of a pasted text, and whether an AM or PM in it was worked out, not read. */
    data class Read(val draft: HoursDraft, val guessed: Boolean)

    fun parse(text: String): Read? {
        val raw = text.trim()
        if (raw.isEmpty() || raw.length > MAX_CHARS) return null
        // OpenStreetMap's own text
        if (OSM_LIKE.containsMatchIn(raw)) HoursDraft.of(raw)?.takeIf { it.groups.isNotEmpty() }?.let { return Read(it, false) }
        val toks = tokens(raw) ?: return null
        val days = List(7) { ArrayList<IntRange>() }
        val named = BooleanArray(7)
        var guessed = false
        var current: List<Int> = emptyList()   // the days the next times belong to
        var anyDays = false
        var pending = false      // a list of days still waits for its times, "closed" or "24 hours"
        var impliedAll = false   // times came before any day: they are every day's, and no day may follow
        var i = 0
        var specs = 0
        while (i < toks.size) {
            val t = toks[i]
            when (t.kind) {
                Kind.SEP -> i++
                Kind.DAY, Kind.DAYS -> {
                    if (pending || impliedAll) return null
                    // a list of days and day ranges, up to the first thing that is not one
                    val list = ArrayList<Int>()
                    while (i < toks.size && (toks[i].kind == Kind.DAY || toks[i].kind == Kind.DAYS || toks[i].kind == Kind.SEP || toks[i].kind == Kind.DASH)) {
                        val d = toks[i]
                        if (d.kind == Kind.DAYS) list += d.days
                        else if (d.kind == Kind.DAY) {
                            if (i + 2 < toks.size && toks[i + 1].kind == Kind.DASH && toks[i + 2].kind == Kind.DAY) {
                                var k = d.days[0]
                                val end = toks[i + 2].days[0]
                                while (true) {
                                    list += k
                                    if (k == end) break
                                    k = (k + 1) % 7
                                }
                                i += 2
                            } else list += d.days[0]
                        } else if (d.kind == Kind.DASH) {
                            // "Weekdays - 9 to 5": a dash between the days and their times says nothing
                            if (list.isEmpty() || i + 1 >= toks.size || toks[i + 1].kind == Kind.DASH) return null
                            i++
                            break
                        }
                        i++
                    }
                    if (list.isEmpty()) return null
                    current = list.distinct().sorted()
                    if (current.any { named[it] }) return null   // a day said twice: which of its hours is meant?
                    current.forEach { named[it] = true }
                    anyDays = true
                    pending = true
                }
                Kind.CLOSED -> {
                    if (current.isEmpty()) return null
                    // closed days get no times; times after them without days of their own belong to nobody
                    current = emptyList()
                    pending = false
                    specs++
                    i++
                }
                Kind.ALL_DAY -> {
                    if (current.isEmpty()) {
                        if (anyDays) return null
                        current = ALL
                        impliedAll = true
                        current.forEach { named[it] = true }
                    }
                    for (d in current) days[d] += 0 until 1440
                    pending = false
                    specs++
                    i++
                }
                Kind.TIME -> {
                    if (i + 2 >= toks.size || toks[i + 1].kind != Kind.DASH || toks[i + 2].kind != Kind.TIME) return null
                    val (span, guess) = span(t, toks[i + 2]) ?: return null
                    if (current.isEmpty()) {
                        if (anyDays) return null
                        current = ALL
                        impliedAll = true
                        current.forEach { named[it] = true }
                    }
                    for (d in current) days[d] += span
                    guessed = guessed || guess
                    pending = false
                    specs++
                    i += 3
                }
                Kind.DASH -> return null
            }
        }
        if (pending || specs == 0 || days.all { it.isEmpty() }) return null
        val week = WeekHours(days.map { WeekHours.merge(it) })
        val draft = HoursDraft(HoursDraft.groupsOf(week))
        if (!draft.check()) return null
        return Read(draft, guessed)
    }

    // ------------------------------------------------------------------ the words

    private enum class Kind { DAY, DAYS, TIME, DASH, SEP, CLOSED, ALL_DAY }

    /** [days]: a DAY's one day or a DAYS word's days; [minutes], [meridiem] ('a', 'p' or null) and [h24] for a TIME. */
    private class Tok(val kind: Kind, val days: List<Int> = emptyList(), val minutes: Int = 0, val hour12: Int = 0,
                      val meridiem: Char? = null, val h24: Boolean = false)

    private val ALL = (0..6).toList()
    private const val MAX_CHARS = 400
    /** The longest span that may run past midnight (a bar: 4 PM to 4 AM; a diner: 6 PM to noon is no real one). */
    private const val MAX_OVERNIGHT_MIN = 16 * 60
    private val OSM_LIKE = Regex("\\b(?:Mo|Tu|We|Th|Fr|Sa|Su)\\b.*\\d{2}:\\d{2}-\\d{2}:\\d{2}|^24/7$")

    private val DAY_NAMES: Map<String, Int> = buildMap {
        listOf(
            listOf("monday", "mondays", "mon", "mo"), listOf("tuesday", "tuesdays", "tues", "tue", "tu"),
            listOf("wednesday", "wednesdays", "weds", "wed"), listOf("thursday", "thursdays", "thurs", "thur", "thu", "th"),
            listOf("friday", "fridays", "fri", "fr"), listOf("saturday", "saturdays", "sat", "sa"),
            listOf("sunday", "sundays", "sun", "su"),
        ).forEachIndexed { i, names -> names.forEach { put(it, i) } }
    }
    private val DAY_WORDS: Map<String, List<Int>> = mapOf(
        "daily" to ALL, "everyday" to ALL, "every day" to ALL, "all week" to ALL, "7 days a week" to ALL, "seven days a week" to ALL,
        "7 days" to ALL, "weekdays" to (0..4).toList(), "weekday" to (0..4).toList(), "weekends" to listOf(5, 6), "weekend" to listOf(5, 6),
    )
    private val ALL_DAY_WORDS = listOf("open 24 hours", "24 hours a day", "24 hours", "24 hrs", "24hrs", "24 hr", "24hr", "24/7", "all day", "always open")
    private val CLOSED_WORDS = listOf("closed", "close", "off")
    /** Words that say nothing: dropped. */
    private val FILLER = setOf("open", "opens", "hours", "hour", "from", "is", "are", "the", "on", "and", "store", "office", "access", "of")
    private val DASH_WORDS = setOf("to", "through", "thru", "until", "till", "til")
    private val TIME = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a|p)?$")
    private val TIME_4 = Regex("^(\\d{2})(\\d{2})$")

    /** The text as tokens; null when a word is not one this reader knows. */
    private fun tokens(raw: String): List<Tok>? {
        var s = raw.lowercase()
            .replace('–', '-').replace('—', '-').replace('−', '-').replace('‐', '-').replace('‑', '-')
            .replace("a.m.", "am").replace("p.m.", "pm").replace("a.m", "am").replace("p.m", "pm")
            .replace(Regex("\\bnoon\\b"), "12pm").replace(Regex("\\bmidnight\\b"), "12am")
            .replace('&', ',').replace('|', ';').replace('\n', ';').replace('\r', ';').replace('\t', ' ')
        // the phrases of several words, as one word each
        s = s.replace(Regex("\\bm\\s*-\\s*f\\b"), "mon-fri")
        for (w in ALL_DAY_WORDS.sortedByDescending { it.length }) s = s.replace(w, " \u0001allday ")
        for (w in DAY_WORDS.keys.sortedByDescending { it.length }) s = s.replace(Regex("\\b" + Regex.escape(w) + "\\b"), " \u0001days" + DAY_WORDS.keys.indexOf(w) + " ")
        // "9 am" -> "9am", "9:30 pm" -> "9:30pm"
        s = s.replace(Regex("(\\d)\\s+(am|pm|a|p)\\b"), "$1$2")
        val out = ArrayList<Tok>()
        val dayKeys = DAY_WORDS.keys.toList()
        for (piece in s.split(Regex("(?<=[;,-])|(?=[;,-])|\\s+"))) {
            val w = piece.trim().trimEnd(':', '.')
            if (w.isEmpty()) continue
            when {
                w == ";" || w == "," -> out += Tok(Kind.SEP)
                w == "-" || w in DASH_WORDS -> out += Tok(Kind.DASH)
                w == "\u0001allday" -> out += Tok(Kind.ALL_DAY)
                w.startsWith("\u0001days") -> out += Tok(Kind.DAYS, DAY_WORDS.getValue(dayKeys[w.removePrefix("\u0001days").toInt()]))
                w in DAY_NAMES -> out += Tok(Kind.DAY, listOf(DAY_NAMES.getValue(w)))
                w in CLOSED_WORDS -> out += Tok(Kind.CLOSED)
                w in FILLER -> Unit
                else -> out += time(w) ?: return null
            }
        }
        return out
    }

    private fun time(w: String): Tok? {
        TIME_4.matchEntire(w)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            return if (h <= 24 && min < 60 && (h < 24 || min == 0)) Tok(Kind.TIME, minutes = h * 60 + min, hour12 = h, h24 = true) else null
        }
        val m = TIME.matchEntire(w) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val mer = m.groupValues[3].firstOrNull()
        if (min >= 60) return null
        if (mer != null) {
            if (h !in 1..12) return null
            val h24 = if (mer == 'a') (if (h == 12) 0 else h) else (if (h == 12) 12 else h + 12)
            return Tok(Kind.TIME, minutes = h24 * 60 + min, hour12 = h, meridiem = mer)
        }
        if (h > 24 || (h == 24 && min != 0)) return null
        // a clock that says which half of the day by itself: 13 and later, 0, or a leading zero ("09:30")
        val clock24 = h >= 13 || h == 0 || (m.groupValues[1].length == 2 && m.groupValues[1][0] == '0')
        return Tok(Kind.TIME, minutes = h * 60 + min, hour12 = h, h24 = clock24)
    }

    /** The span of "a - b" in minutes after the day's midnight (the end may pass 1440), and whether an AM or PM was guessed. */
    private fun span(a: Tok, b: Tok): Pair<IntRange, Boolean>? {
        var from: Int
        var to: Int
        var guessed = false
        val aKnown = a.meridiem != null || a.h24
        val bKnown = b.meridiem != null || b.h24
        fun pm(t: Tok) = (if (t.hour12 == 12) 12 else t.hour12 + 12) * 60 + t.minutes % 60
        fun am(t: Tok) = (if (t.hour12 == 12) 0 else t.hour12) * 60 + t.minutes % 60
        /** A closing time that does not say its half of the day: the first such time after [start] (the next morning if need be). */
        fun after(start: Int, t: Tok) = if (am(t) > start) am(t) else if (pm(t) > start) pm(t) else am(t)
        when {
            aKnown && bKnown -> { from = a.minutes; to = b.minutes }
            !aKnown && b.meridiem != null -> {
                to = b.minutes
                if (b.meridiem == 'p') {
                    // "9-6pm" can only open in the morning. "1-5pm" and "8-9pm" fit both halves of the day: an
                    // opening at 1 to 5 is read as an afternoon, any other as a morning, and the form asks
                    val both = pm(a) < to
                    from = if (both && (a.hour12 in 1..5 || a.hour12 == 12)) pm(a) else am(a)
                    guessed = both && a.hour12 != 12
                } else {
                    // "6-11am" is a morning; "10-2am" opens the evening before
                    from = if (am(a) < to) am(a) else pm(a)
                }
            }
            a.meridiem != null && !bKnown -> {
                from = a.minutes
                if (a.meridiem == 'a' && am(b) > from && pm(b) > from) {
                    // "6am-9" fits a morning and an evening: a place closes in the evening far more often, and the form asks
                    to = pm(b)
                    guessed = true
                } else {
                    // "9am-5", "6pm-9", "6pm-2": the first such time after the start
                    to = after(from, b)
                }
            }
            !aKnown && b.h24 -> { from = if (am(a) < b.minutes) am(a) else pm(a); to = b.minutes }
            a.h24 && !bKnown -> { from = a.minutes; to = after(from, b) }
            else -> {
                // neither says: a start of 1 to 5 is an afternoon, 6 to 11 a morning, 12 noon; the end the first such time after it
                guessed = true
                from = if (a.hour12 in 1..5) pm(a) else if (a.hour12 == 12) 12 * 60 + a.minutes % 60 else am(a)
                to = after(from, b)
            }
        }
        if (to == 0 && from > 0) to = 1440                 // "9pm-12am": midnight at the end of the day
        if (from !in 0 until 1440 || to !in 0..1440) return null
        if (to == from) return null
        val end = if (to < from) to + 1440 else to         // closes the next morning
        if (end - from >= 1440) return null
        // from an evening to the next afternoon: far more likely a slip ("9pm-5pm" for 9am) than real hours
        if (end > 1440 && end - from > MAX_OVERNIGHT_MIN) return null
        return (from until end) to guessed
    }
}
