package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.feature.search.PlaceSheetUi
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.Fix
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 0.1.41, 53e (docs/online-design.md 4.5, "Were you there?"): where the phone was in the last [windowMs], in memory
 * only: never written to a file, a backup or a log, never sent; emptied when the process ends or "Contribute to
 * OpenStreetMap" goes off (AppGraph, through [OnSite]). Only one yes or no derived from it is ever kept: [OutboxItem.atPlace].
 * [toString] prints the count only (PV2). Plain Kotlin; nothing here logs.
 */
class RecentPositions(private val windowMs: Long = WINDOW_MS) {
    private val fixes = ArrayDeque<Fix>()

    @Synchronized
    fun add(fix: Fix) {
        val newest = fixes.lastOrNull()
        if (newest != null && fix.elapsedMs <= newest.elapsedMs) return   // the same fix again (a StateFlow repeats its value) or an older one
        fixes.addLast(fix)
        while (fix.elapsedMs - fixes.first().elapsedMs > windowMs || fixes.size > MAX_FIXES) fixes.removeFirst()
    }

    /**
     * One of the fixes of the last [sinceMs] (by their own time, the monotonic clock) was within [withinM] of the point.
     * The fixer's review: first the fixes older than [windowMs] at [nowMs] leave the memory ([prune]).
     */
    @Synchronized
    fun near(lat: Double, lon: Double, nowMs: Long, withinM: Double = NEAR_M, sinceMs: Long = windowMs): Boolean {
        prune(nowMs)
        return fixes.any { f ->
            nowMs - f.elapsedMs in 0..sinceMs && (f.accuracyM ?: 0f) <= MAX_ACCURACY_M && SpotCheck.metres(f.lat, f.lon, lat, lon) <= withinM
        }
    }

    /**
     * The fixer's review (53e: "the last 10 minutes"): the fixes older than [windowMs] at [nowMs] (the monotonic clock)
     * leave the memory now, with no new fix needed; a fix newer than [nowMs] stays.
     */
    @Synchronized
    fun prune(nowMs: Long) {
        while (fixes.isNotEmpty() && nowMs - fixes.first().elapsedMs > windowMs) fixes.removeFirst()
    }

    @Synchronized
    fun clear() = fixes.clear()

    /**
     * The fixer's review (F8): feeds the memory from [source] (AppGraph: the app's one GPS listener) and lets each fix
     * leave when it is older than [windowMs] by [clock], also when nothing asks and no new fix comes (the app in the
     * background); runs until cancelled. It wakes when the oldest fix is due, and at least once a minute.
     */
    suspend fun keep(source: Flow<Fix?>, clock: Clock): Unit = coroutineScope {
        launch {
            while (true) {
                delay(waitMs(clock.elapsedMs()))
                prune(clock.elapsedMs())
            }
        }
        source.collect { f -> if (f != null) add(f) }
    }

    /** How long until the oldest fix leaves (1 ms at least), or [IDLE_MS] while the memory is empty. */
    @Synchronized
    private fun waitMs(nowMs: Long): Long =
        fixes.firstOrNull()?.let { (it.elapsedMs + windowMs + 1 - nowMs).coerceIn(1, IDLE_MS) } ?: IDLE_MS

    @Synchronized
    override fun toString(): String = "RecentPositions(${fixes.size} fixes)"

    companion object {
        const val WINDOW_MS = 10 * 60_000L
        /** "About 80 m" of the place's point (open question 3: also for big places). */
        const val NEAR_M = 80.0
        /** A fix less sure than this (a cell-tower guess) does not show the phone was at a place (open question 4). */
        const val MAX_ACCURACY_M = 50f
        const val MAX_FIXES = 1_200
        /** [keep] looks again at least this often (a fix came while the memory was empty). */
        const val IDLE_MS = 60_000L
    }
}

/**
 * 53e, the fixer's review (F8, F10): the app's one door to its position memory. AppGraph holds the memory only inside
 * it and feeds it with [keep] while "Contribute to OpenStreetMap" is on; AppFlows and AppRoot get only yes-or-no answers
 * about a place's own point ([madeAt], [arrivedAt], [near]), never a fix, so nothing that builds an item or a file can
 * carry a position (OnSiteTest drives these same calls with canary fixes and reads every file). [toString] prints the
 * count only (PV2). Plain Kotlin; nothing here logs.
 */
class OnSite(private val clock: Clock) {
    private val memory = RecentPositions()

    /** Feeds the memory from [fixes] until cancelled (AppGraph: while Contribute is on), each fix kept 10 minutes. */
    suspend fun keep(fixes: Flow<Fix?>) = memory.keep(fixes, clock)

    /** Contribute went off (or on again): the memory starts empty. */
    fun clear() = memory.clear()

    /** A fix within 80 m of the point in the last 10 minutes, or in the last [sinceMs] (AP2's window: 2 minutes). */
    fun near(lat: Double, lon: Double, sinceMs: Long = RecentPositions.WINDOW_MS): Boolean =
        memory.near(lat, lon, clock.elapsedMs(), sinceMs = sinceMs)

    /** "Upload" on an edit's summary (AppFlows.uploadEdit): made at the place by its own point, or opened from a question there (AP-7). */
    fun madeAt(s: EditSession): Boolean = UploadChecks.atPlace(near(s.target.lat, s.target.lon), s.target.atPlace, s.stored, s.pendingChanges)

    /** "Upload" on a new place's summary (AppFlows.uploadAdd): made at its point. */
    fun madeAt(s: AddSession): Boolean = UploadChecks.atPlace(near(s.lat, s.lon), openedAtPlace = false, s.stored, s.pendingChanges)

    /** "Upload" on a speed limit's summary (AppFlows.uploadSpeed): made at the road's point. */
    fun madeAt(s: SpeedSession): Boolean = UploadChecks.atPlace(near(s.lat, s.lon), openedAtPlace = false, s.stored, s.pendingChanges)

    /** The one-tap "Still right?" (AppFlows.confirmHours): made at the place's own point. */
    fun madeAt(t: HoursTarget): Boolean = UploadChecks.atPlace(near(t.lat, t.lon), openedAtPlace = false, stored = null, changes = emptyMap())

    /** AP2 (AppFlows.arrivalTick): the destination's own point ([PlaceQuestions.pointOf]) reached, by the tick's [elapsedMs]. */
    fun arrivedAt(sheet: PlaceSheetUi?, elapsedMs: Long): Boolean =
        sheet != null && PlaceQuestions.pointOf(sheet).let { memory.near(it.lat, it.lon, elapsedMs) }

    override fun toString(): String = "OnSite" + memory.toString().removePrefix("RecentPositions")
}

/** 53e: what Beamline asks before an edit goes, one dialog at a time ([UploadChecks.next]). */
enum class UploadAsk {
    /** The public-edit notice, once ever (section 3). */
    PUBLIC,
    /** "Are you sure you checked this on-site?": an edit not made at the place, every time. */
    ON_SITE,
    /** What source=survey tells everyone, once ever, before the first edit made at the place. */
    SURVEY,
}

/** 53e: the asks before an upload and the "made at the place" decision. Plain Kotlin. */
object UploadChecks {
    /**
     * The next question, or null: go. First the public-edit notice (once ever); then, for an edit not made at the place,
     * "Are you sure you checked this on-site?" (every time); for one made there, the source=survey notice (once ever;
     * carried by the public notice when both are due, [publicWithSurvey]).
     */
    fun next(publicSeen: Boolean, surveySeen: Boolean, atPlace: Boolean, answered: Set<UploadAsk>): UploadAsk? = when {
        !publicSeen && UploadAsk.PUBLIC !in answered -> UploadAsk.PUBLIC
        !atPlace && UploadAsk.ON_SITE !in answered -> UploadAsk.ON_SITE
        atPlace && !surveySeen && UploadAsk.SURVEY !in answered -> UploadAsk.SURVEY
        else -> null
    }

    /** The public notice also says what source=survey tells (both due on the very first edit, made at the place). */
    fun publicWithSurvey(surveySeen: Boolean, atPlace: Boolean): Boolean = atPlace && !surveySeen

    /**
     * 53e, plan OP-6 / AP-7: an upload is made at the place when the phone was near it ([nearNow]: a fix within 80 m in
     * the last 10 minutes), the form was opened from a question at the place ([openedAtPlace]), or an item made there
     * ([stored]) is sent again with the values it had ([changes] all among its own). A confirmation turned into an
     * hours edit always decides again. Decided once, at the Upload tap; the item keeps only this yes or no.
     */
    fun atPlace(nearNow: Boolean, openedAtPlace: Boolean, stored: OutboxItem?, changes: Map<String, String?>): Boolean =
        nearNow || openedAtPlace || (stored != null && stored.atPlace && stored.edit?.confirm == null && changes.isNotEmpty() &&
            storedChanges(stored).let { s -> changes.all { (k, v) -> s.containsKey(k) && s[k] == v } })

    /** What an item would change: an edit's changes, a new place's tags, a limit picked while driving as its tag. */
    private fun storedChanges(i: OutboxItem): Map<String, String?> = i.edit?.changes ?: i.add?.tags
        ?: i.speedMph?.let { mapOf(SpeedLimits.KEY to SpeedLimits.tag(it)) } ?: emptyMap()
}
