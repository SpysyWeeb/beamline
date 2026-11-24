package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.search.hours.OpenState
import io.github.spysyweeb.beamline.search.hours.OpeningHours
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

/**
 * The words of rule S10 (batch B; the owner, 2026-11-17, docs/next-round.md item 11): the status
 * ("Open now", "Closing soon", "Closed now"), the next change in US 12-hour time ("until 9 PM",
 * "closes at 9 PM", "opens at 7 AM"; with the weekday when the change is a day or more away:
 * "opens Mon at 7 AM") and the week, Monday first.
 */
internal object HoursTexts {
    const val OPEN = "Open now"
    const val CLOSING_SOON = "Closing soon"
    const val CLOSED = "Closed now"
    const val OPEN_24 = "Open 24 hours"
    const val CLOSED_DAY = "Closed"
    const val HOLIDAYS = "Holiday hours may differ"

    private val cache = object : LinkedHashMap<String, OpeningHours?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, OpeningHours?>) = size > 256
    }

    /** The text read (cached, at most 256 texts); null when it cannot be evaluated. */
    @Synchronized
    fun read(text: String?): OpeningHours? {
        if (text.isNullOrBlank()) return null
        return cache.getOrPut(text) { OpeningHours.parse(text) }
    }

    /** The status of [text] at [now], with the week when [withWeek]; null when there is nothing to show (never a guess). */
    fun ui(text: String?, now: LocalDateTime, withWeek: Boolean): HoursUi? {
        val h = read(text) ?: return null
        val s = h.status(now)
        val label = when (s.state) {
            OpenState.OPEN -> OPEN
            OpenState.CLOSING_SOON -> CLOSING_SOON
            OpenState.CLOSED -> CLOSED
        }
        val at = s.nextChange
        val next = when {
            at == null && s.state != OpenState.CLOSED -> "open 24 hours"
            at == null -> null
            s.state == OpenState.OPEN -> "until ${whenText(now, at)}"
            s.state == OpenState.CLOSING_SOON -> "closes at ${clock(at.hour * 60 + at.minute)}"
            else -> "opens ${whenText(now, at, at = true)}"
        }
        val week = if (!withWeek) emptyList() else h.week(now.toLocalDate()).map { d ->
            HoursDayUi(dayName(d.day), dayText(d.spans), d.day == now.dayOfWeek)
        }
        return HoursUi(s.state, label, next, week, if (withWeek && h.holidaysIgnored) HOLIDAYS else null)
    }

    /** "9 PM", or "Tue 9 PM" when [then] is a day or more after [now]; with [at]: "at 9 PM", "Tue at 9 PM". */
    private fun whenText(now: LocalDateTime, then: LocalDateTime, at: Boolean = false): String {
        val time = clock(then.hour * 60 + then.minute)
        val far = Duration.between(now, then).toMinutes() >= 24 * 60
        return when {
            far && at -> "${dayName(then.dayOfWeek)} at $time"
            far -> "${dayName(then.dayOfWeek)} $time"
            at -> "at $time"
            else -> time
        }
    }

    fun dayText(spans: List<IntRange>): String = when {
        spans.isEmpty() -> CLOSED_DAY
        spans.size == 1 && spans[0].first == 0 && spans[0].last >= 1439 -> OPEN_24
        else -> spans.joinToString(", ") { "${clock(it.first)}–${clock(it.last + 1)}" }
    }

    /** Minutes after midnight (may pass 24:00) as "9 AM", "9:30 PM", "12 PM" (noon), "12 AM" (midnight). */
    fun clock(minutes: Int): String {
        val m = ((minutes % 1440) + 1440) % 1440
        val h24 = m / 60
        val min = m % 60
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val ampm = if (h24 < 12) "AM" else "PM"
        return if (min == 0) "$h12 $ampm" else "$h12:${min.toString().padStart(2, '0')} $ampm"
    }

    fun dayName(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "Mon"
        DayOfWeek.TUESDAY -> "Tue"
        DayOfWeek.WEDNESDAY -> "Wed"
        DayOfWeek.THURSDAY -> "Thu"
        DayOfWeek.FRIDAY -> "Fri"
        DayOfWeek.SATURDAY -> "Sat"
        DayOfWeek.SUNDAY -> "Sun"
    }

    /** The first number of OSM's `phone` ("+1 814 555 0100; +1 ..."): digits, "+", spaces and dashes only; null when none is left. */
    fun phone(raw: String?): String? {
        val first = raw?.split(';')?.firstOrNull()?.trim().orEmpty()
        val kept = first.filter { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' || it == '.' }.trim()
        return kept.takeIf { s -> s.count { it.isDigit() } >= 7 }
    }

    /** The first address of OSM's `website` as http(s); a bare "example.com" gets "https://"; anything else is dropped. */
    fun website(raw: String?): String? {
        val first = raw?.split(';')?.firstOrNull()?.trim().orEmpty()
        if (first.isEmpty() || first.any { it.isWhitespace() }) return null
        val lower = first.lowercase()
        return when {
            lower.startsWith("https://") || lower.startsWith("http://") -> first
            "://" in lower || lower.startsWith("mailto:") || lower.startsWith("javascript:") -> null
            '.' in first -> "https://$first"
            else -> null
        }
    }

    /**
     * Every number of OSM's `phone` (split by ";"), for the details height (batch G): a US-shaped
     * number (10 digits, or 11 starting with 1, written with spaces, dashes, dots, brackets or "+1")
     * is written "(814) 555-0123" and dialled as "+18145550123"; any other is shown as the pack has
     * it and dialled with its digits when it has at least 7 (else only shown).
     */
    fun phones(raw: String?): List<ContactUi> = raw.orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { one ->
        val us = usDigits(one)
        if (us != null) {
            ContactUi("(${us.substring(0, 3)}) ${us.substring(3, 6)}-${us.substring(6)}", "+1$us", "(${us.substring(0, 3)}) ${us.substring(3, 6)}-${us.substring(6)}")
        } else {
            ContactUi(one, phone(one), one)
        }
    }

    /** The 10 digits of a US-shaped number, or null (letters, extensions, other countries, wrong lengths). */
    private fun usDigits(s: String): String? {
        if (s.any { !(it.isDigit() || it == ' ' || it == '-' || it == '.' || it == '(' || it == ')' || it == '+') }) return null
        if (s.count { it == '+' } > 1 || ('+' in s && !s.trimStart().startsWith("+1"))) return null
        var d = s.filter { it.isDigit() }
        if (d.length == 11 && d[0] == '1') d = d.substring(1) else if ('+' in s || d.length != 10) return null
        return d.takeIf { it[0] in '2'..'9' && it[3] in '2'..'9' }
    }

    /** Longest web address text on the details height before it is shortened in the middle. */
    const val WEBSITE_MAX_CHARS = 40

    /**
     * Every web address of OSM's `website` (split by ";") a browser can open ([website]), for the
     * details height (batch G): written without "http://" or "https://" and a trailing "/"
     * ("example.com/menu"), longer than [WEBSITE_MAX_CHARS] shortened in the middle with "…".
     */
    fun websites(raw: String?): List<ContactUi> = raw.orEmpty().split(';').mapNotNull { website(it) }.distinct().map { url ->
        val bare = url.replaceFirst(Regex("^(?i)https?://"), "").trimEnd('/')
        ContactUi(shortened(bare, WEBSITE_MAX_CHARS), url, url)
    }

    /** [s] when at most [max] characters, else its start and end around "…" ([max] characters in all). */
    fun shortened(s: String, max: Int): String {
        if (s.length <= max) return s
        val tail = (max - 1) / 3
        val head = max - 1 - tail
        return s.take(head) + "…" + s.takeLast(tail)
    }
}
