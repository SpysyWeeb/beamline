package io.github.spysyweeb.beamline.search.hours

import ch.poole.openinghoursparser.OpeningHoursParser
import ch.poole.openinghoursparser.Rule
import ch.poole.openinghoursparser.RuleModifier
import ch.poole.openinghoursparser.TimeSpan
import ch.poole.openinghoursparser.WeekDay
import java.io.StringReader
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId

/** Open, in its last hour, or closed (docs/architecture.md rule S10). */
enum class OpenState { OPEN, CLOSING_SOON, CLOSED }

/**
 * The status of a place at one moment. [nextChange]: when it closes (OPEN, CLOSING_SOON) or opens
 * (CLOSED), in the same local time as the moment asked; null when it never changes within the
 * week ahead (open around the clock, or closed every day).
 */
data class HoursStatus(val state: OpenState, val nextChange: LocalDateTime?)

/** One day of the week view: [spans] in minutes after that day's midnight ([end] may pass 24:00 = 1440). */
data class DayHours(val day: DayOfWeek, val spans: List<IntRange>)

/**
 * An OpenStreetMap `opening_hours` text, read with Simon Poole's OpeningHoursParser (MIT,
 * `ch.poole:OpeningHoursParser`) and evaluated here over the subset of the specification that
 * can be evaluated without guessing (docs/architecture.md rule S10, batch B):
 *
 *  - rules separated by `;` (a later rule that names a weekday replaces what earlier rules said of
 *    it) and `,` (adds to them); `24/7`; weekday ranges (`Mo-Fr`, `Sa,Su`); a rule with no weekday
 *    is every day; times `08:00-17:00`, several per rule, ending after midnight (`18:00-02:00`,
 *    `18:00-26:00`: the part after midnight belongs to the next morning); `off` / `closed`, for
 *    whole days or (closing only those times) for times; a rule's comment is ignored;
 *  - rules that name only public or school holidays (`PH off`, `PH 10:00-14:00`) cannot be
 *    evaluated (the app knows no holiday calendar): they are left out and [holidaysIgnored] says so;
 *    a holiday named beside weekdays (`Mo-Fr,PH 08:00-17:00`) is left out of that rule;
 *  - everything else makes the whole text unreadable ([parse] gives null, the app shows nothing):
 *    dates, months, years, week numbers, the nth weekday of a month, variable times (sunrise),
 *    open ends (`18:00+`), repeating times, `unknown`, fallback rules (`||`), a text that is only a
 *    comment, and any text the parser refuses.
 *
 * Plain JVM: the caller passes the local date and time of the phone's clock and time zone. The
 * status works on wall-clock time (local dates and minutes, no instants): a closing time inside the
 * hour a daylight-saving change skips or repeats can be off by an hour (batch C2, from the batch B
 * review; a few hours a year, and only at places that close in the middle of the night).
 */
class OpeningHours private constructor(private val rules: List<Simple>, val holidaysIgnored: Boolean) {

    private class Simple(val days: Set<DayOfWeek>?, val spans: List<IntRange>, val closed: Boolean, val additive: Boolean, val timed: Boolean)

    /** Open spans of the calendar day [date] from its own rules, minutes after its midnight (end exclusive, may pass 1440). */
    private fun own(date: LocalDate): List<IntRange> {
        val dow = date.dayOfWeek
        var cur: List<IntRange> = emptyList()
        for (r in rules) {
            if (r.days != null && dow !in r.days) continue
            // An `off` with times closes only those times (lunch: "Mo-Fr 08:00-17:00; Mo-Fr 12:00-13:00 off");
            // any other rule that names the day replaces what earlier rules said of it, unless added with ",".
            if (!r.additive && !(r.closed && r.timed)) cur = emptyList()
            cur = if (r.closed) subtract(cur, r.spans) else cur + r.spans
        }
        return merge(cur)
    }

    /** The open spans from the start of the day before [from]'s date to [days] days after it, as absolute minutes from that start. */
    private fun timeline(from: LocalDate, days: Int): List<LongRange> {
        val start = from.minusDays(1)
        val out = ArrayList<LongRange>()
        for (k in 0..days + 1) {
            val base = k * DAY
            for (s in own(start.plusDays(k.toLong()))) out.add((base + s.first)..(base + s.last))
        }
        return mergeLong(out)
    }

    /** The status at [now] (the phone's local date and time). */
    fun status(now: LocalDateTime): HoursStatus {
        val date = now.toLocalDate()
        val start = date.minusDays(1).atStartOfDay()
        val t = DAY + now.hour * 60L + now.minute
        val line = timeline(date, WINDOW_DAYS)
        val end = (WINDOW_DAYS + 2) * DAY
        fun at(m: Long) = start.plusMinutes(m)
        val open = line.firstOrNull { t in it }
        if (open != null) {
            val closesAt = open.last + 1
            if (closesAt >= end) return HoursStatus(OpenState.OPEN, null)
            val state = if (closesAt - t <= SOON_MIN) OpenState.CLOSING_SOON else OpenState.OPEN
            return HoursStatus(state, at(closesAt))
        }
        val next = line.firstOrNull { it.first > t }
        return HoursStatus(OpenState.CLOSED, next?.let { at(it.first) })
    }

    /** The status at the instant [at] on a clock in the zone [zone] (the phone's). */
    fun status(at: Instant, zone: ZoneId): HoursStatus = status(LocalDateTime.ofInstant(at, zone))

    /** Monday to Sunday of the week holding [today], each with its own open spans. */
    fun week(today: LocalDate): List<DayHours> {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return (0L until 7L).map { monday.plusDays(it) }.map { DayHours(it.dayOfWeek, own(it)) }
    }

    companion object {
        private const val DAY = 1440L
        private const val SOON_MIN = 60L
        private const val WINDOW_DAYS = 8

        /** The text read, or null when it is empty or cannot be evaluated without guessing (see the class comment). */
        fun parse(text: String?): OpeningHours? {
            val src = text?.trim().orEmpty()
            if (src.isEmpty() || src.length > 512) return null
            val parsed: List<Rule> = try {
                OpeningHoursParser(StringReader(src)).rules(false)
            } catch (_: Exception) {
                return null   // the parser's words are never kept (PV2)
            } catch (_: Error) {
                return null   // the generated token manager throws TokenMgrError on some input
            }
            if (parsed.isEmpty()) return null
            var holidays = false
            val out = ArrayList<Simple>()
            for (r in parsed) {
                if (r.isFallBack) return null
                if (!r.years.isNullOrEmpty() || !r.weeks.isNullOrEmpty() || !r.dates.isNullOrEmpty()) return null
                val modifier = r.modifier?.modifier
                if (modifier == RuleModifier.Modifier.UNKNOWN) return null
                val closed = modifier == RuleModifier.Modifier.CLOSED || modifier == RuleModifier.Modifier.OFF
                val hasHolidays = !r.holidays.isNullOrEmpty()
                val dayRanges = r.days.orEmpty()
                if (hasHolidays) {
                    holidays = true
                    if (dayRanges.isEmpty()) continue   // a holiday-only rule: cannot be evaluated, left out
                }
                val days: Set<DayOfWeek>? = if (dayRanges.isEmpty()) null else {
                    val set = HashSet<DayOfWeek>()
                    for (d in dayRanges) {
                        if (!d.nths.isNullOrEmpty() || d.offset != 0) return null
                        val a = dow(d.startDay ?: return null)
                        val b = d.endDay?.let(::dow) ?: a
                        var x = a
                        while (true) {
                            set.add(x)
                            if (x == b) break
                            x = x.plus(1)
                        }
                    }
                    set
                }
                val spans = if (r.isTwentyfourseven || r.times.isNullOrEmpty()) {
                    listOf(0 until DAY.toInt())
                } else {
                    r.times!!.map { span(it) ?: return null }
                }
                // nothing but a comment ("by appointment"): no weekday, no time, no 24/7, no open / off
                if (dayRanges.isEmpty() && !hasHolidays && r.times.isNullOrEmpty() && !r.isTwentyfourseven && modifier == null) return null
                out.add(Simple(days, spans, closed, r.isAdditive, timed = !r.isTwentyfourseven && !r.times.isNullOrEmpty()))
            }
            if (out.isEmpty()) return null
            return OpeningHours(out, holidays)
        }

        private fun span(t: TimeSpan): IntRange? {
            if (t.startEvent != null || t.endEvent != null || t.isOpenEnded || t.interval != 0) return null
            val s = t.start
            var e = t.end
            if (s == TimeSpan.UNDEFINED_TIME || e == TimeSpan.UNDEFINED_TIME || s < 0 || s >= 1440) return null
            if (e <= s) e += 1440   // past midnight: 18:00-02:00
            if (e > 2 * 1440) return null
            return s until e
        }

        private fun dow(d: WeekDay): DayOfWeek = when (d) {
            WeekDay.MO -> DayOfWeek.MONDAY
            WeekDay.TU -> DayOfWeek.TUESDAY
            WeekDay.WE -> DayOfWeek.WEDNESDAY
            WeekDay.TH -> DayOfWeek.THURSDAY
            WeekDay.FR -> DayOfWeek.FRIDAY
            WeekDay.SA -> DayOfWeek.SATURDAY
            WeekDay.SU -> DayOfWeek.SUNDAY
        }

        internal fun merge(spans: List<IntRange>): List<IntRange> {
            val sorted = spans.filter { !it.isEmpty() }.sortedBy { it.first }
            val out = ArrayList<IntRange>()
            for (s in sorted) {
                val last = out.lastOrNull()
                if (last != null && s.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, s.last)
                else out.add(s)
            }
            return out
        }

        private fun mergeLong(spans: List<LongRange>): List<LongRange> {
            val sorted = spans.sortedBy { it.first }
            val out = ArrayList<LongRange>()
            for (s in sorted) {
                val last = out.lastOrNull()
                if (last != null && s.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, s.last)
                else out.add(s)
            }
            return out
        }

        internal fun subtract(from: List<IntRange>, cut: List<IntRange>): List<IntRange> {
            var cur = from
            for (c in cut) {
                cur = cur.flatMap { s ->
                    if (c.last < s.first || c.first > s.last) listOf(s)
                    else listOfNotNull(
                        (s.first until c.first).takeIf { !it.isEmpty() },
                        ((c.last + 1)..s.last).takeIf { !it.isEmpty() },
                    )
                }
            }
            return cur
        }
    }
}
