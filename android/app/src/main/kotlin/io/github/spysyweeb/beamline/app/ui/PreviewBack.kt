package io.github.spysyweeb.beamline.app.ui

import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.feature.search.ResultsUi

/**
 * Play test P46 (0.1.40): Back from a route preview that a place window's Directions planned goes back to
 * that window, and to the search results that were behind it. X still closes the route with "Directions
 * closed" and Undo. Pure, main thread only; AppFlows drives it (the decisions are here so they are tested
 * on the JVM).
 */
internal class PreviewBack {
    /**
     * Where Back from a preview goes: [place], whose window's Directions planned it; [results], the results
     * as SearchFeature.results gave them then (null: none), closed meanwhile; [fromGrid]: they were a place
     * type's from the "View all" grid (AppFlows.backFromResults returns there).
     */
    data class Way(val place: Place, val results: ResultsUi?, val fromGrid: Boolean)

    /** The way back from the preview being shown; null: a trip planned another way, or none. */
    var way: Way? = null
        private set

    /** Back was pressed: the window is open again; its results come back when the route has ended. */
    private var returning: Way? = null

    /** A trip planned from IDLE, PLANNING or CHOOSING: [way] from a window's Directions, null another way (a one-tap PlanTrip, a saved place). */
    fun planned(way: Way?) {
        this.way = way
        returning = null
    }

    /** Back at the preview's middle or lowest height: the place whose window opens again (the caller ends the route, no Undo bar); null: close with Undo as before. */
    fun back(): Place? {
        val w = way ?: return null
        way = null
        returning = w
        return w.place
    }

    /** X: no way back (the route closes with Undo as before). */
    fun forget() {
        way = null
        returning = null
    }

    /**
     * The session's phase went from [prev] to [now]: after Back, the way whose results to put back now that
     * the route has ended (IDLE); null otherwise. Only a change of phase counts (an estimate or any other
     * change in IDLE before the plan starts keeps the way); any phase but PLANNING and CHOOSING ends it
     * (Start, an arrival, a resume offer, the route ended another way).
     */
    fun onPhase(prev: Phase, now: Phase): Way? {
        if (now == prev || now == Phase.PLANNING || now == Phase.CHOOSING) return null
        val r = returning.takeIf { now == Phase.IDLE }
        way = null
        returning = null
        return r
    }
}
