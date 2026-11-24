package io.github.spysyweeb.beamline.app.online

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The values a form starts from (batch O3): what "Changed" and Undo compare with. Null: none. */
data class FormValues(
    val name: String?, val hours: String?, val phone: String?, val website: String?, val typeCode: String? = null,
    /** Item 33: the key of a second set of hours ([MoreHours]) and its text, or null: none. */
    val moreKey: String? = null, val moreHours: String? = null,
) {
    override fun toString(): String = "FormValues"
}

/**
 * A second set of hours of one place (docs/next-round.md item 33): the part of it that keeps other hours
 * than the place itself. OSM's keys are `opening_hours:<part>` (wiki Key:opening_hours:office,
 * :drive_through, :kitchen); a storage place is open for access all day while its office keeps office
 * hours, a pharmacy's drive-through closes before the shop, a pub's kitchen before the bar.
 */
enum class MoreHours(val key: String, val label: String) {
    OFFICE("opening_hours:office", "Office hours"),
    DRIVE_THROUGH("opening_hours:drive_through", "Drive-through hours"),
    KITCHEN("opening_hours:kitchen", "Kitchen hours");

    companion object {
        fun ofKey(key: String?): MoreHours? = entries.firstOrNull { it.key == key }

        /** The first second set of hours [tags] have, or null. */
        fun of(tags: Map<String, String>): MoreHours? = entries.firstOrNull { it.key in tags }
    }
}

/**
 * The one form's fields (batch O3, docs/next-round.md item 24), for "Edit on OpenStreetMap" and "Add a
 * missing place": name, type, the hours as day groups ([HoursDraft]), phone and website; each knows
 * whether it changed from [start] and can be undone on its own. Compose state, read on the main thread.
 * Hours the editor cannot express stay read-only ([hoursReadOnly]) and are kept as they were.
 */
class FormFields(start: FormValues = FormValues(null, null, null, null)) {
    var name by mutableStateOf("")
    var phone by mutableStateOf("")
    var website by mutableStateOf("")
    var type by mutableStateOf<AddableType?>(null)
    var hours by mutableStateOf(HoursDraft.EMPTY)
    var hoursReadOnly by mutableStateOf<String?>(null)
        private set
    /** Item 33: the second set of hours, or null: none (its times are [hours2]; read-only as [hoursReadOnly] is). */
    var more by mutableStateOf<MoreHours?>(null)
        private set
    var hours2 by mutableStateOf(HoursDraft.EMPTY)
    var hours2ReadOnly by mutableStateOf<String?>(null)
        private set
    var start: FormValues = start
        private set
    private var startDraft: HoursDraft = HoursDraft.EMPTY
    private var startDraft2: HoursDraft = HoursDraft.EMPTY
    /** OSM's second set is a text the editor cannot show: "Remove these hours" still removes it. */
    private var startMoreReadOnly = false

    init {
        reset(start)
    }

    /** Starts again from [values] (the form's start, or the values after a conflict). */
    fun reset(values: FormValues) {
        start = values
        name = values.name.orEmpty()
        phone = values.phone.orEmpty()
        website = values.website.orEmpty()
        type = PlaceTypes.byCode(values.typeCode)
        val d = HoursDraft.of(values.hours)
        hoursReadOnly = if (d == null) values.hours else null
        startDraft = d?.normalized() ?: HoursDraft.EMPTY
        hours = startDraft
        more = MoreHours.ofKey(values.moreKey)
        val d2 = if (more == null) HoursDraft.EMPTY else HoursDraft.of(values.moreHours)
        hours2ReadOnly = if (d2 == null) values.moreHours else null
        startMoreReadOnly = d2 == null && more != null
        startDraft2 = d2?.normalized() ?: HoursDraft.EMPTY
        hours2 = startDraft2
    }

    /** "+ Other hours": a second set of [kind] starts (only while the place has none: OSM's own one keeps its kind). */
    fun addMore(kind: MoreHours) {
        if (start.moreKey != null && !fresh) return
        more = kind
        hours2 = HoursDraft.EMPTY
        hours2ReadOnly = null
    }

    /** "Remove these hours": a set added here goes; one OSM has is emptied (the upload then removes its tag). */
    fun removeMore() {
        if (start.moreKey != null && !fresh) {
            hours2 = HoursDraft.EMPTY
            hours2ReadOnly = null
        } else more = null
    }

    /**
     * Item 25 ("This is now a different business"): on, the fields are emptied for the new business
     * (any read-only hours of the old one are not kept: the new one's hours are its own); off, the
     * values before come back.
     */
    var fresh by mutableStateOf(false)
        private set
    private var before: Array<Any?>? = null

    fun startFresh(on: Boolean) {
        if (on == fresh) return
        fresh = on
        if (on) {
            before = arrayOf(name, phone, website, type, hours, hoursReadOnly, more, hours2, hours2ReadOnly)
            name = ""
            phone = ""
            website = ""
            type = null
            hours = HoursDraft.EMPTY
            hoursReadOnly = null
            more = null
            hours2 = HoursDraft.EMPTY
            hours2ReadOnly = null
        } else before?.let { b ->
            name = b[0] as String
            phone = b[1] as String
            website = b[2] as String
            type = b[3] as AddableType?
            hours = b[4] as HoursDraft
            hoursReadOnly = b[5] as String?
            more = b[6] as MoreHours?
            hours2 = b[7] as HoursDraft
            hours2ReadOnly = b[8] as String?
        }
    }

    /** Sets the current values to [values] while keeping the start (an outbox item opened again). */
    fun setValues(values: FormValues) {
        name = values.name.orEmpty()
        phone = values.phone.orEmpty()
        website = values.website.orEmpty()
        if (values.typeCode != null) type = PlaceTypes.byCode(values.typeCode)
        if (hoursReadOnly == null) HoursDraft.of(values.hours)?.let { hours = it.normalized() }
        val kind = MoreHours.ofKey(values.moreKey)
        if (kind != null && (start.moreKey == null || start.moreKey == kind.key || fresh)) {
            more = kind
            if (hours2ReadOnly == null) HoursDraft.of(values.moreHours)?.let { hours2 = it.normalized() }
        } else if (values.moreKey == null && start.moreKey != null && !fresh && values.moreHours == null && !keepMore) {
            // the values say the place's second set was emptied (an outbox item that removes it), read-only or not
            hours2 = HoursDraft.EMPTY
            hours2ReadOnly = null
        }
    }

    /** [setValues] leaves OSM's second set alone unless told the values are complete (they are for an item opened again). */
    var keepMore = true

    val nameChanged: Boolean get() = name.trim() != start.name.orEmpty().trim()
    val phoneChanged: Boolean get() = phone.trim() != start.phone.orEmpty().trim()
    val websiteChanged: Boolean get() = website.trim() != start.website.orEmpty().trim()
    val typeChanged: Boolean get() = type?.code != start.typeCode
    val hoursChanged: Boolean get() = hoursReadOnly == null && hours.text() != startDraft.text()
    val moreChanged: Boolean get() = more?.key != start.moreKey || (hours2ReadOnly == null && hours2.text() != startDraft2.text()) ||
        (startMoreReadOnly && hours2ReadOnly == null)   // a set the editor could not show was removed
    val anyChanged: Boolean get() = nameChanged || phoneChanged || websiteChanged || typeChanged || hoursChanged || moreChanged

    fun undoName() { name = start.name.orEmpty() }
    fun undoPhone() { phone = start.phone.orEmpty() }
    fun undoWebsite() { website = start.website.orEmpty() }
    fun undoType() { type = PlaceTypes.byCode(start.typeCode) }
    fun undoHours() { hours = startDraft }
    fun undoMore() {
        more = MoreHours.ofKey(start.moreKey)
        hours2 = startDraft2
        hours2ReadOnly = if (more != null && HoursDraft.of(start.moreHours) == null) start.moreHours else null
    }

    /** The hours as OSM text: the read-only text as it was, the draft's text when changed, else the start's; null: none. */
    fun hoursText(): String? = when {
        fresh -> hours.text().takeIf { it.isNotEmpty() }
        hoursReadOnly != null -> start.hours
        !hoursChanged -> start.hours
        else -> hours.text().takeIf { it.isNotEmpty() }
    }

    /** The key of the second set of hours, or null: none. */
    val moreKey: String? get() = more?.key

    /** The second set's hours as OSM text, as [hoursText] gives the first's; null: none (its tag is removed or never written). */
    fun moreHoursText(): String? = when {
        more == null -> null
        fresh -> hours2.text().takeIf { it.isNotEmpty() }
        hours2ReadOnly != null -> start.moreHours
        !moreChanged -> start.moreHours
        else -> hours2.text().takeIf { it.isNotEmpty() }
    }

    /** The website as written to OSM: "https://" added to an address without its scheme. */
    fun websiteText(): String? = website.trim().takeIf { it.isNotEmpty() }?.let { w ->
        if (w.startsWith("http://", true) || w.startsWith("https://", true)) w else "https://$w"
    }

    /**
     * The phone as written to OSM (docs/next-round.md item 38): a number the user typed is put in the form
     * OpenStreetMap asks for ([Checks.phoneText]); a number the place already had and the user did not touch
     * stays exactly as it was (the form never changes what it was not asked to).
     */
    fun phoneText(): String = if (!fresh && !phoneChanged) phone else Checks.phoneText(phone)

    /** "Saved as +1-814-555-0123" under the field, when that is not what was typed; null otherwise. */
    val phoneNote: String? get() {
        if (phoneProblem != null || phone.isBlank() || (!fresh && !phoneChanged)) return null
        val t = phoneText()
        return if (t != phone.trim()) Checks.SAVED_AS + t else null
    }

    val phoneProblem: String? get() = Checks.phone(phone)
    val websiteProblem: String? get() = Checks.website(website)
}

/** The plain checks of phone and website as they are typed (batch O3, item 24 e). Null: fine (or empty). */
object Checks {
    const val PHONE = "A phone number has 10 digits, like 814-555-0123, or starts with + and the country code."
    const val WEBSITE = "A website looks like example.com or https://example.com/page, with no spaces."
    const val SAVED_AS = "Saved as "

    /**
     * A phone number in the form OpenStreetMap asks for (the wiki's Key:phone: the international form with
     * the country code; "+1-814-555-0123" for the United States and Canada, the only countries the app has
     * maps of). Ten digits, or eleven that begin with 1, typed in any usual way ("(814) 555-0123",
     * "814.555.0123", "8145550123", "1 814 555 0123", "+1 (814) 555-0123"), become "+1-814-555-0123".
     * Anything else stays as typed, trimmed: a number of another country (it starts with + and another
     * code), a short number, digits that cannot be a North American number (an area code or an exchange
     * never begins with 0 or 1), digits that are not the plain 0 to 9. Several numbers stay several, with
     * ";" between them; each is looked at alone.
     */
    fun phoneText(text: String): String = text.split(';').map { onePhone(it.trim()) }.filter { it.isNotEmpty() }.joinToString(";")

    private fun onePhone(t: String): String {
        if (t.any { it.isDigit() && it !in '0'..'9' }) return t
        val digits = t.filter { it in '0'..'9' }
        val ten = when {
            digits.length == 10 && !t.startsWith("+") -> digits
            digits.length == 11 && digits[0] == '1' -> digits.substring(1)
            else -> return t
        }
        if (ten[0] in "01" || ten[3] in "01") return t
        return "+1-${ten.substring(0, 3)}-${ten.substring(3, 6)}-${ten.substring(6)}"
    }

    fun phone(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        for (one in t.split(';').map { it.trim() }) {
            if (one.isEmpty()) return PHONE
            if (one.any { !(it.isDigit() || it in " +-().") }) return PHONE
            if (one.indexOf('+') > 0) return PHONE
            val digits = one.count { it.isDigit() }
            if (digits < 7 || digits > 15) return PHONE
        }
        return null
    }

    fun website(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.any { it.isWhitespace() }) return WEBSITE
        val rest = t.removePrefix("https://").removePrefix("http://").removePrefix("HTTPS://").removePrefix("HTTP://")
        val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (rest.contains("://") || '.' !in host || host.startsWith('.') || host.endsWith('.') || host.length < 4) return WEBSITE
        if (host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == ':') }) return WEBSITE
        return null
    }
}
