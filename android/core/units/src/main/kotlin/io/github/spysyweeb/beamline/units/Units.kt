// FILE: core/units/src/main/kotlin/io/github/spysyweeb/beamline/units/Units.kt
package io.github.spysyweeb.beamline.units

import java.time.ZoneId

enum class UnitSystem { IMPERIAL, METRIC }

/** units.json (source: packbuilder/routing/units.json, copied by android/routing/scripts/sync_data.py). */
class UnitRules private constructor(internal val table: UnitTable) {
    companion object {
        const val FORMAT = "beamline-units/1"
        fun parse(text: String): UnitRules = UnitRules(UnitTable.parse(text))
        fun bundled(): UnitRules = parse(UnitTable.bundledText())
    }
}

/**
 * Every distance, duration and clock text on screen comes from here (section 2.9). [system] follows
 * Settings.units (MILES = IMPERIAL); [clock24h] follows the phone's 12/24-hour setting, not the units.
 */
class UnitFormatter(val rules: UnitRules, val system: UnitSystem, val clock24h: Boolean = false) {
    /** "250 ft", "0.2 mi", "12 mi" / "350 m", "1.4 km"; never goes up as the distance shrinks. */
    fun distance(m: Double): String = rules.table.distance(system, m)
    /** "In 0.2 mi" (units.json following_prefix). */
    fun distanceIn(m: Double): String = rules.table.followingPrefix + distance(m)
    /** "< 1 min", "12 min", "1 h", "1 h 5 min" (rounded to the nearest minute). */
    fun duration(s: Double): String = rules.table.duration(s)
    /** "3:42 PM" (12-hour clock) or "15:42" (24-hour clock), in [zone]. */
    fun clockTime(epochMs: Long, zone: ZoneId): String = rules.table.clock(clock24h, epochMs, zone)
}
