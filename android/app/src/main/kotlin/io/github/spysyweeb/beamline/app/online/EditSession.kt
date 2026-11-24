package io.github.spysyweeb.beamline.app.online

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The place an edit is about (batch O2, docs/online-design.md 4.2): its OSM object, its name and
 * point as the sheet showed them, and the pack's values (the form's start when OSM cannot be read).
 * Batch O3: [packOsm] the pack's own object (the map tiles' feature; [osm] differs only for a test
 * object) and [icon] the sheet's icon, for the own-changes layer. [toString] prints none of them (PV2).
 */
data class EditTarget(
    val osm: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val packName: String?,
    val packHours: String?,
    val packPhone: String?,
    val packWebsite: String?,
    val packOsm: String? = null,
    val icon: String? = null,
) {
    override fun toString(): String = "EditTarget"

    /** The pack's values as tags (the form's base when offline). */
    fun packTags(): Map<String, String> = buildMap {
        packName?.let { put("name", it) }
        packHours?.let { put("opening_hours", it) }
        packPhone?.let { put("phone", it) }
        packWebsite?.let { put("website", it) }
    }
}

/** Where the edit screen is. */
enum class EditPhase { LOADING, FORM, SUMMARY, UPLOADING, DONE, WAITING, REFUSED }

/**
 * One edit of one place, from the form to the upload (docs/online-design.md 4.2, 4.5); Compose
 * state, read on the main thread. It reads the place from OSM first ([load]); offline the form starts
 * from the pack's values. [summary] gives the changes; [upload] puts the edit in the outbox and sends
 * it at once ([OutboxSender]); a conflict brings the form back with OSM's new values and the user's
 * changes on them. Batch O3: the fields are the one form's ([FormFields]: day groups, "Changed" and
 * Undo), and "This is now a different business" ([differentBusiness], docs/next-round.md item 25)
 * swaps the old business's tags for the new one's ([BusinessTags]). Nothing here logs.
 */
class EditSession(
    val target: EditTarget,
    /** An outbox item being looked at again (REVIEW, WAITING, REFUSED), or null for a new edit. */
    private val itemId: String?,
    private val http: OsmHttp,
    private val outbox: OutboxStore,
    private val sender: OutboxSender,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var phase by mutableStateOf(EditPhase.LOADING)
        private set
    /** A line above the form: the place changed since the map was made, offline, or a conflict. */
    var notice by mutableStateOf<String?>(null)
        private set
    /** The values on OSM now, shown after a conflict ("Now on OpenStreetMap"). */
    var nowOnOsm by mutableStateOf<List<String>>(emptyList())
        private set
    val f = FormFields()
    var closed by mutableStateOf(false)
    /** Batch O3 (item 25): "This is now a different business": the form's values are the new business's. */
    var differentBusiness by mutableStateOf(false)
        private set
    /** Why Next did not go on (a time, a phone, a website, a type or a name is missing), or null. */
    var problem by mutableStateOf<String?>(null)
        private set
    var canClose by mutableStateOf(false)
        private set
    var lines by mutableStateOf<List<String>>(emptyList())
        private set
    var link by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<Int?>(null)
        private set

    private var element: OsmElement? = null
    private var base: Map<String, String> = emptyMap()
    private var changes: Map<String, String?> = emptyMap()
    private var replaceWith: Map<String, String>? = null
    private var id: String = itemId ?: java.util.UUID.randomUUID().toString()

    /** The place's tags the form started from (OSM's now, or the pack's offline). */
    val baseTags: Map<String, String> get() = base

    /** Reads the place from OSM and fills the form ([conflict]: after OSM's 409 or a change found before sending). */
    fun load(conflict: Boolean = false) {
        phase = EditPhase.LOADING
        val item = itemId?.let { outbox.byId(it) } ?: outbox.byId(id)
        scope.launch {
            val ref = OsmRef.parse(target.osm)
            val e = try {
                if (ref == null) null else withContext(io) { http.element(ref) }
            } catch (_: OnlineRefused) {
                null
            } catch (_: IOException) {
                null
            }
            element = e
            val mine = item?.edit?.changes.orEmpty()
            if (e != null) {
                base = e.tags
                val pack = target.packTags()
                notice = when {
                    conflict || item?.state == OutboxState.REVIEW -> OnlineTexts.EDIT_CONFLICT
                    pack.isNotEmpty() && EditFields.of(pack).core() != EditFields.of(e.tags).core() -> OnlineTexts.EDIT_CHANGED
                    else -> null
                }
                nowOnOsm = if (mine.isNotEmpty()) mine.keys.map { k -> "${PlaceEdits.label(k)}: ${e.tags[k] ?: PlaceEdits.NONE}" }.distinct() else emptyList()
                canClose = PlaceEdits.canClose(e.tags)
            } else {
                base = item?.edit?.base?.let { target.packTags() + it } ?: target.packTags()
                notice = OnlineTexts.EDIT_OFFLINE
                nowOnOsm = emptyList()
                canClose = false
            }
            val startF = EditFields.of(base)
            f.reset(FormValues(startF.name, startF.hours, startF.phone, startF.website, moreKey = startF.moreKey, moreHours = startF.moreHours))
            val r = item?.edit?.replaceWith
            if (r != null) {
                switchDifferentBusiness(true)
                val rm = MoreHours.of(r)
                f.setValues(FormValues(r["name"], r["opening_hours"], r["phone"] ?: r["contact:phone"], r["website"] ?: r["contact:website"], PlaceTypes.of(r)?.code,
                    rm?.key, rm?.let { r[it.key] }))
            } else if (mine.isNotEmpty()) {
                val mf = EditFields.of(PlaceEdits.apply(base, mine))
                // the item's own values are complete: a second set it removed stays removed
                f.keepMore = false
                f.setValues(FormValues(mf.name, mf.hours, mf.phone, mf.website, moreKey = mf.moreKey, moreHours = mf.moreHours))
                f.keepMore = true
            }
            closed = mine.keys.any { it.startsWith("disused:") } && canClose
            phase = EditPhase.FORM
        }
    }

    /** Item 25: switches "This is now a different business"; on, the form is emptied for the new business (off brings the old values back). */
    fun switchDifferentBusiness(on: Boolean) {
        if (on == differentBusiness) return
        differentBusiness = on
        if (on) closed = false
        f.startFresh(on)
    }

    /** "Next": the changes as a summary, or the form stays with a [problem]. */
    fun summary() {
        problem = formProblem() ?: if (differentBusiness && f.type == null) OnlineTexts.TYPE_NEEDED
            else if (differentBusiness && f.type?.named == true && f.name.isBlank()) OnlineTexts.NAME_NEEDED else null
        if (problem != null) return
        if (differentBusiness) {
            val next = newTags(f)
            replaceWith = next
            changes = BusinessTags.replace(base, next)
            lines = OnlineTexts.replaceLines(base, next, changes)
        } else {
            replaceWith = null
            changes = PlaceEdits.changes(base, EditFields(f.name, f.hoursText(), f.phoneText(), f.websiteText(), f.moreKey ?: f.start.moreKey, f.moreHoursText()), closed && canClose)
            lines = PlaceEdits.summary(base, changes)
        }
        phase = EditPhase.SUMMARY
    }

    private fun formProblem(): String? = when {
        closed -> null
        f.phoneProblem != null -> f.phoneProblem
        f.websiteProblem != null -> f.websiteProblem
        f.hoursReadOnly == null && f.hoursChanged && !f.hours.check() -> OnlineTexts.HOURS_CANNOT_WRITE
        moreProblem(f) -> OnlineTexts.HOURS_CANNOT_WRITE
        else -> null
    }

    fun backToForm() {
        phase = EditPhase.FORM
    }

    val hasChanges: Boolean get() = changes.isNotEmpty()

    /** Something was changed in the form (the discard question on leaving). */
    val dirty: Boolean get() = phase == EditPhase.FORM && (f.anyChanged || closed || differentBusiness) || phase == EditPhase.SUMMARY

    /** The changeset's comment for the changes ("Updated hours"; "Replaced Primo Tailoring with restaurant Joe's"). */
    val comment: String get() = replaceWith?.let { r -> OnlineTexts.replaceComment(base["name"] ?: target.name, r) } ?: PlaceEdits.comment(changes)

    /** "Upload": into the outbox (replacing the item looked at again) and sent at once when it can be (4.5). */
    fun upload() {
        if (changes.isEmpty()) return
        val e = element
        val item = OutboxItem(
            id = id, lat = target.lat, lon = target.lon, text = lines.joinToString("\n"), placeName = target.name,
            createdMs = now(),
            edit = EditData(
                target.osm, e?.version, changes.keys.mapNotNull { k -> base[k]?.let { k to it } }.toMap(), changes, comment,
                replaceWith = replaceWith, packOsm = target.packOsm, icon = target.icon, typeCode = PlaceTypes.of(base)?.code,
            ),
        )
        outbox.add(item)
        phase = EditPhase.UPLOADING
        scope.launch {
            sender.sendWaiting(id, e)
            val now = outbox.byId(id)
            when (now?.state) {
                OutboxState.SENT -> {
                    link = now.link
                    phase = EditPhase.DONE
                }
                OutboxState.REVIEW -> load(conflict = true)
                OutboxState.REFUSED -> {
                    status = now.status
                    phase = EditPhase.REFUSED
                }
                else -> phase = EditPhase.WAITING
            }
        }
    }

    /** Deletes the outbox item being looked at again. */
    fun delete() {
        itemId?.let { outbox.delete(it) }
    }

    val isItem: Boolean get() = itemId != null

    companion object {
        /** The tags of the business the form describes: its type's tags, the name, hours, phone and website given. */
        /** The second set of hours was changed to times OSM's text cannot hold (the first set's check, for the second). */
        fun moreProblem(f: FormFields): Boolean =
            f.more != null && f.hours2ReadOnly == null && f.moreChanged && f.hours2.text().isNotEmpty() && !f.hours2.check()

        fun newTags(f: FormFields): Map<String, String> = buildMap {
            f.type?.tags?.let { putAll(it) }
            f.name.trim().takeIf { it.isNotEmpty() }?.let { put("name", it) }
            f.hoursText()?.let { put("opening_hours", it) }
            f.moreKey?.let { k -> f.moreHoursText()?.let { put(k, it) } }
            f.phoneText().trim().takeIf { it.isNotEmpty() }?.let { put("phone", it) }
            f.websiteText()?.let { put("website", it) }
        }
    }
}
