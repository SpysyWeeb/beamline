package io.github.spysyweeb.beamline.location

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Picks between providers: a satellite fix is always taken; a fix from another provider (the
 * network, usually far less accurate) only when it is at least as accurate as the last fix or the
 * last fix is already old.
 */
object FixFilter {
    fun accept(new: Fix, last: Fix?, rules: PositionRules): Boolean {
        if (last == null || new.provider in rules.satelliteProviders || new.provider == last.provider) return true
        val lastOld = (new.elapsedMs - last.elapsedMs) / 1000.0 > rules.staleAfterS
        val asGood = new.accuracyM != null && (last.accuracyM == null || new.accuracyM <= last.accuracyM)
        return lastOld || asGood
    }
}

/**
 * Turns a position source and a heading source into the browsing puck's state. Runs only while
 * [start]ed (the app starts it when the map is on screen and location is allowed, and stops it
 * in the background: foreground location only). [motion]: the accelerometer, which tells the
 * [PositionEstimator] standing still from moving (rule G7); null: not known (the moving filter).
 */
class PuckTracker(
    private val positions: PositionSource,
    private val headings: HeadingSource?,
    private val rules: PositionRules,
    private val clock: Clock,
    private val motion: MotionSource? = null,
) {
    private val _puck = MutableStateFlow<PuckState?>(null)

    /** Null until the first fix arrives. */
    val puck: StateFlow<PuckState?> = _puck.asStateFlow()

    private val _lastFix = MutableStateFlow<Fix?>(null)
    val lastFix: StateFlow<Fix?> = _lastFix.asStateFlow()

    private var heading: Heading? = null
    private val smoother = HeadingSmoother(rules.headingSmoothing)
    private val estimator = PositionEstimator(rules.bubble)
    private val detector = MotionDetector(rules.bubble)
    private var estimate: PositionEstimate? = null
    private var jobs: List<Job> = emptyList()

    val running: Boolean get() = jobs.any { it.isActive }

    fun start(scope: CoroutineScope) {
        if (running) return
        jobs = listOfNotNull(
            scope.launch {
                positions.fixes().collect { fix ->
                    if (FixFilter.accept(fix, _lastFix.value, rules)) {
                        estimate = estimator.add(fix, detector.motion(fix.elapsedMs))
                        _lastFix.value = fix
                        publish()
                    }
                }
            },
            motion?.let { m -> scope.launch { m.samples().collect { detector.add(it) } } },
            headings?.let { h ->
                scope.launch {
                    h.headings().collect {
                        heading = Heading(smoother.add(it.degrees), it.elapsedMs)
                        publish()
                    }
                }
            },
            scope.launch {
                val tick = (rules.tickS * 1000).toLong().coerceAtLeast(100)
                while (isActive) {
                    delay(tick)
                    publish()
                }
            },
        )
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        smoother.reset()
        heading = null
        estimator.reset()
        detector.reset()
        estimate = null
    }

    private fun publish() {
        val fix = _lastFix.value ?: return
        val next = stateFor(fix, heading, clock.elapsedMs(), rules, estimate)
        if (next != _puck.value) _puck.value = next
    }

    companion object {
        /**
         * The rules in one place: grey when old or poor; the cone only with a recent heading; rule
         * G7: when the fix or [estimate] is poorer than bubble.show_above_m the puck sits on the
         * estimate, with the bubble when the estimate's radius is above it too.
         */
        fun stateFor(fix: Fix, heading: Heading?, nowMs: Long, rules: PositionRules, estimate: PositionEstimate? = null): PuckState {
            val ageS = (nowMs - fix.elapsedMs) / 1000.0
            val why = when {
                ageS > rules.staleAfterS -> "old"
                fix.accuracyM == null || fix.accuracyM > rules.poorAccuracyM -> "poor"
                else -> null
            }
            // Whole degrees: the map is redrawn only when the cone turns visibly.
            val cone = heading?.takeIf { (nowMs - it.elapsedMs) / 1000.0 <= rules.headingMaxAgeS }
                ?.let { Compass.normalize(kotlin.math.round(it.degrees)) }
            val show = rules.bubble.showAboveM
            val poorFix = fix.accuracyM == null || fix.accuracyM > show
            val use = estimate?.takeIf { poorFix || it.radiusM > show }
            val bubble = use?.radiusM?.takeIf { it > show }?.coerceAtMost(rules.bubble.maxM)
            return PuckState(
                lat = use?.lat ?: fix.lat,
                lon = use?.lon ?: fix.lon,
                look = if (why == null) PuckLook.LIVE else PuckLook.GREY,
                coneDeg = cone,
                accuracyM = fix.accuracyM,
                why = why,
                // Whole metres: the map is redrawn only when the bubble changes visibly.
                bubbleM = bubble?.let { kotlin.math.round(it).toFloat() },
            )
        }
    }
}
