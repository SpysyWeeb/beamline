package io.github.spysyweeb.beamline.app.handover

/** Item 52 (rules H4-H7): what the app says about a link handed over by another app. */
object HandoverTexts {
    /** H2: nothing usable in the link (another form, bad numbers, an empty query). */
    const val UNREADABLE = "Beamline could not find a place in this link."
    /** H4: no state installed. */
    const val NEED_STATE = "Beamline needs an installed state to find this place. Open the directions again once one is installed."
    /** H5/H7: a point outside the installed routing tiles while guiding (the place window says it otherwise). */
    const val OUTSIDE_MAPS = "This place is outside the installed maps."
    /** H7: guiding to that place already. */
    const val ALREADY_GOING = "You are already on your way there."
    /** H7 (K2): the route being looked at already ends there. */
    const val ALREADY_SHOWN = "The route shown already goes there."
    /** H6: the plain bar while words are looked up (it goes when the answer opens). */
    const val FINDING = "Finding the place..."
    /** H4 (K5): a link arrived while something of the user's is open; it opens once that is closed. */
    const val WAITING = "The directions open when you close this."
    /** The note's action (the place window's button has the same words, SearchTexts.ADD_STATE_BUTTON). */
    const val ADD_STATE = "Add a state"

    /** H6: the words name a state that is not installed; [canAdd]: no trip runs (States is offered). */
    fun outsideState(state: String, canAdd: Boolean): String =
        "This place is in $state, which is not on this phone." + if (canAdd) " Add it to go there." else ""
}

/** The line about a link (AppFlows.handoverNote): [text], and "Add a state" beside OK when [states]. [toString] prints neither. */
data class HandoverNote(val text: String, val states: Boolean) {
    override fun toString(): String = "HandoverNote"
}
