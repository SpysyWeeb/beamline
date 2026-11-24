package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.search.hours.OpeningHours
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

/**
 * The edit form's week (batch O2, docs/online-design.md 4.2): for each day Monday to Sunday its open
 * spans in minutes after that day's midnight (end exclusive; past 1440 runs into the next morning,
 * "18:00-02:00"). [text] writes valid OSM hours in the usual short form ("Mo-Fr 08:00-18:00;
 * Sa 08:00-13:00"), checked with the hours parser the app already uses ([OpeningHours]).
 */
data class WeekHours(val days: List<List<IntRange>>) {
    init {
        require(days.size == 7) { "seven days" }
    }

    /** The OSM text; "" when every day is closed (the form then removes the tag). */
    fun text(): String {
        val norm = days.map { merge(it) }
        if (norm.all { it == listOf(0 until 1440) }) return "24/7"
        val rules = ArrayList<String>()
        val done = BooleanArray(7)
        for (i in 0 until 7) {
            if (done[i] || norm[i].isEmpty()) continue
            val same = (i until 7).filter { !done[it] && norm[it] == norm[i] }
            same.forEach { done[it] = true }
            rules += dayList(same) + " " + norm[i].joinToString(",") { span(it) }
        }
        return rules.joinToString("; ")
    }

    /**
     * The week in plain words for the summary a person reads before sending (the owner, 2026-11-21: "Mo-Fr
     * 09:00-17:00; Sa 10:00-14:00" is OSM's notation, not everyone's): "Mon–Fri 9 AM–5 PM; Sat 10 AM–2 PM;
     * Sun closed". Days with the same hours that follow each other are one range; "Open 24 hours every day".
     */
    fun plain(): String {
        val norm = days.map { merge(it) }
        if (norm.all { it == listOf(0 until 1440) }) return "Open 24 hours every day"
        val parts = ArrayList<String>()
        var a = 0
        while (a < 7) {
            var b = a
            while (b + 1 < 7 && norm[b + 1] == norm[a]) b++
            val names = if (a == b) DAY_NAMES[a] else DAY_NAMES[a] + "–" + DAY_NAMES[b]
            val hours = when {
                norm[a].isEmpty() -> "closed"
                // the whole day (and on into the next morning: that morning is the next day's own line or its tail)
                norm[a].size == 1 && norm[a][0].first == 0 && norm[a][0].last + 1 >= 1440 -> "24 hours"
                else -> norm[a].joinToString(", ") { clock12(it.first) + "–" + clock12(it.last + 1) }
            }
            parts += "$names $hours"
            a = b + 1
        }
        return parts.joinToString("; ")
    }

    companion object {
        val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        /** Minutes after midnight (may pass 24:00) as "9 AM", "9:30 PM", "12 PM" (noon), "12 AM" (midnight). */
        fun clock12(minutes: Int): String {
            val m = ((minutes % 1440) + 1440) % 1440
            val h12 = if ((m / 60) % 12 == 0) 12 else (m / 60) % 12
            val ampm = if (m / 60 < 12) "AM" else "PM"
            return if (m % 60 == 0) "$h12 $ampm" else "$h12:${(m % 60).toString().padStart(2, '0')} $ampm"
        }

        val DAY_CODES = listOf("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su")
        val CLOSED = WeekHours(List(7) { emptyList() })
        /** A Monday: [OpeningHours.week] of it gives Monday to Sunday. */
        private val MONDAY: LocalDate = LocalDate.of(2026, 11, 16)

        /**
         * The week of an OSM hours text, when the form can express it without losing anything:
         * readable by [OpeningHours], no holiday rules, no comments, and the text written back reads
         * as the same week. Null otherwise (the form shows the text read-only, 4.2). Null text: closed.
         */
        fun of(text: String?): WeekHours? {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return CLOSED
            if ('"' in t) return null
            val h = OpeningHours.parse(t) ?: return null
            if (h.holidaysIgnored) return null
            val w = WeekHours(h.week(MONDAY).map { d -> merge(d.spans) })
            return w.takeIf { readBack(w.text()) == w }
        }

        /** The week the parser reads from [text] (the writer's check); null when it cannot. */
        fun readBack(text: String): WeekHours? {
            if (text.isEmpty()) return CLOSED
            val h = OpeningHours.parse(text) ?: return null
            if (h.holidaysIgnored) return null
            return WeekHours(h.week(MONDAY).map { d -> merge(d.spans) })
        }

        /** "Mo-Fr", "Mo,We,Fr", "Sa,Su" (two days in a row are written with a comma, as OSM's examples do). */
        private fun dayList(idx: List<Int>): String {
            val parts = ArrayList<String>()
            var a = 0
            while (a < idx.size) {
                var b = a
                while (b + 1 < idx.size && idx[b + 1] == idx[b] + 1) b++
                parts += when (b - a) {
                    0 -> DAY_CODES[idx[a]]
                    1 -> DAY_CODES[idx[a]] + "," + DAY_CODES[idx[b]]
                    else -> DAY_CODES[idx[a]] + "-" + DAY_CODES[idx[b]]
                }
                a = b + 1
            }
            return parts.joinToString(",")
        }

        /** "08:00-18:00"; the day's end is "24:00", a span into the next morning "18:00-02:00". */
        fun span(r: IntRange): String {
            val end = r.last + 1
            return clock(r.first) + "-" + if (end == 1440) "24:00" else clock(end % 1440)
        }

        fun clock(m: Int): String = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

        /** "8:00", "08:00", "800" or "8" as minutes after midnight ("24:00" is 1440); null when not a time. */
        fun parseClock(text: String): Int? {
            val t = text.trim().replace('.', ':')
            val (h, m) = when {
                ':' in t -> t.substringBefore(':') to t.substringAfter(':')
                t.length in 3..4 -> t.dropLast(2) to t.takeLast(2)
                else -> t to "00"
            }
            val hh = h.toIntOrNull() ?: return null
            val mm = m.toIntOrNull() ?: return null
            if (m.length != 2 || hh !in 0..24 || mm !in 0..59 || (hh == 24 && mm != 0)) return null
            return hh * 60 + mm
        }

        /** A span from two clock texts: the end at or before the start runs past midnight; null when either is not a time or both are equal. */
        fun spanOf(from: String, to: String): IntRange? {
            val a = parseClock(from)?.takeIf { it < 1440 } ?: return null
            var b = parseClock(to) ?: return null
            if (b == a) return null
            if (b < a) b += 1440
            return a until b
        }

        /** Spans sorted, touching or overlapping ones joined. */
        fun merge(spans: List<IntRange>): List<IntRange> {
            val out = ArrayList<IntRange>()
            for (s in spans.filter { !it.isEmpty() }.sortedBy { it.first }) {
                val last = out.lastOrNull()
                if (last != null && s.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, s.last) else out.add(s)
            }
            return out
        }

        /** Monday's index 0 to Sunday's 6. */
        fun index(d: DayOfWeek): Int = d.value - 1
    }
}
