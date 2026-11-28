package io.github.spysyweeb.beamline.app.settings

import io.github.spysyweeb.beamline.app.online.OnlineSettings

/**
 * Play test P55 / audit F88: why a row of Settings cannot be used now, or null when it can. Such a
 * row shows the reason in place of its description, stays tappable, and a tap shows the reason in
 * Settings' own bar for [REASON_MS]; it never changes a setting. Free of Android for the JVM tests
 * (SettingsRulesTest).
 */
internal object SettingsRules {
    const val NEEDS_ONLINE = "Turn on Online features first"
    const val NEEDS_OSM = "Turn on Contribute to OpenStreetMap first"
    const val NOT_WHILE_GUIDING = "Not while guiding"

    /** How long the reason bar shows after a tap (each tap starts it again), as the map's snackbar. */
    const val REASON_MS = 4_000L

    /** "Contribute to OpenStreetMap", "Check for map updates", "Check for app updates": they count only under the main switch. */
    fun featureBlocked(online: OnlineSettings): String? = if (!online.online) NEEDS_ONLINE else null

    /** "Sign in to OpenStreetMap": the main switch, then Contribute to OpenStreetMap; never while guiding. */
    fun signInBlocked(online: OnlineSettings, driving: Boolean): String? = when {
        !online.online -> NEEDS_ONLINE
        !online.contributeOsm -> NEEDS_OSM
        driving -> NOT_WHILE_GUIDING
        else -> null
    }

    /** "Check for an update now". */
    fun checkNowBlocked(online: OnlineSettings, driving: Boolean): String? = when {
        !online.online -> NEEDS_ONLINE
        driving -> NOT_WHILE_GUIDING
        else -> null
    }

    /** "Saved places" and "My reports" (SP4: "Not while guiding", opens nothing). */
    fun drivingBlocked(driving: Boolean): String? = if (driving) NOT_WHILE_GUIDING else null
}
