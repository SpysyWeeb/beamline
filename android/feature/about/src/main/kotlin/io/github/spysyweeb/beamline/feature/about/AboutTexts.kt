package io.github.spysyweeb.beamline.feature.about

/**
 * Every text of :feature:about's screens. The first-run notice is the text of
 * docs/architecture.md 5.1, approved by the owner as written on 2026-11-16 (owner question 1 of
 * 6.38; AboutTextsTest keeps it equal to the document). When it changes, raise FIRST_RUN_NOTICE_VERSION.
 */
internal object AboutTexts {
    const val FIRST_RUN_NOTICE =
        "Beamline gives directions from map data on this phone. The data can be out of date or wrong. " +
            "You are responsible for driving safely and following road rules, signs and signals. " +
            "Set your route before you drive, and do not handle the phone while driving."
    const val FIRST_RUN_ACCEPT = "I understand"

    const val ABOUT_TITLE = "About"
    const val BACK = "Back"
    const val VERSION_PREFIX = "Version "
    const val INSTALLED_MAPS = "Installed maps"
    const val NO_MAPS = "No state is installed."
    const val OSM_SNAPSHOT_PREFIX = "OpenStreetMap data of "
    const val DATA_CREDITS = "Map data"
    const val LICENCES_ROW = "Open-source licenses"
    const val LICENCES_ROW_DETAIL = "The software and data in this app, and their licenses"
    const val DRIVER_NOTICE_ROW = "Driver notice"
    const val DRIVER_NOTICE_ROW_DETAIL = "What you accepted on the first start"

    const val LICENCES_TITLE = "Open-source licenses"
    const val ALL_NOTICES_ROW = "All notices in one text"
    const val LICENCE_PREFIX = "License: "
    const val TEXT_MISSING = "This text is missing from the app."
    const val LOADING = "Loading…"

    /** What a screen reader says for the reveal while it covers the window (play test P29). */
    const val STARTING = "Beamline is starting"
}
