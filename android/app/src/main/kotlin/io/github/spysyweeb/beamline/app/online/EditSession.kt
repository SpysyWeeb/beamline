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
    /** 0.1.41 (docs/online-design.md 4.8, AP9): the field the form opens at; null: its top. */
    val focus: EditFocus? = null,
    /** 0.1.41 (53e, AP-7): the form was opened from a question at the place (its at-the-place test just passed). */
    val atPlace: Boolean = false,
    /** 0.1.41 (53a, OP-5): the pack entry's point of a node (the move check of an edit made offline); null: a way, or not known. */
    val nodeLat: Double? = null,
    val nodeLon: Double? = null,
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

/** 0.1.41 (docs/online-design.md 4.8, AP9): the field the edit form opens at. */
enum class EditFocus { HOURS, PHONE, WEBSITE }

/** Where the edit screen is. 0.1.41 (53a): GONE, the place was deleted from OSM. */
enum class EditPhase { LOADING, FORM, SUMMARY, UPLOADING, DONE, WAITING, REFUSED, GONE }

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
    /** 0.1.41 (OP-4): the edit went and OpenStreetMap already had every value: nothing was uploaded. */
    var already by mutableStateOf(false)
        private set
    /** 0.1.41 (AP9): the form was already moved to [EditTarget.focus] once (not Compose state: a return from the summary or a reload does not move it again). */
    var focusUsed = false

    private var element: OsmElement? = null
    /** 53a: the place was deleted while an item of it is in the outbox (the GONE screen offers Delete). */
    private var goneWithItem = false
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
            } catch (x: OsmError) {
                // 0.1.41 (53a): deleted from OSM: nothing to edit (any other error: the offline form, as before)
                if (x.status == 410) {
                    element = null
                    goneWithItem = item != null
                    phase = EditPhase.GONE
                    return@launch
                }
                null
            } catch (_: IOException) {
                null
            }
            element = e
            val looked = conflict || item?.state == OutboxState.REVIEW
            // 0.1.41 (53c): a confirmation that came back is not put on the form: it shows OSM's hours of now, and an
            // Upload is an ordinary edit that replaces the confirmation
            val confirmation = item?.edit?.confirm != null
            val mine = if (confirmation) emptyMap() else item?.edit?.changes.orEmpty()
            if (e != null) {
                base = e.tags
                val pack = target.packTags()
                // the fixer's review: a move first; a confirmation names other hours only when OSM has other hours (it also
                // comes back for a closing or another kind, AP-4, with the hours as confirmed)
                val ed = item?.edit
                val confirmed = ed?.confirm?.let { k -> e.tags[k] == ed?.base?.get(k) } == true
                notice = when {
                    looked && item?.edit?.let { EditPlans.movedFar(it, e) } == true -> OnlineTexts.EDIT_MOVED
                    looked && confirmation -> if (confirmed) OnlineTexts.CONFIRM_CHANGED else OnlineTexts.CONFIRM_CONFLICT
                    looked -> OnlineTexts.EDIT_CONFLICT
                    pack.isNotEmpty() && EditFields.of(pack).core() != EditFields.of(e.tags).core() -> OnlineTexts.EDIT_CHANGED
                    else -> null
                }
                nowOnOsm = if (mine.isNotEmpty()) mine.keys.map { k -> "${PlaceEdits.label(k)}: ${e.tags[k] ?: PlaceEdits.NONE}" }.distinct() else emptyList()
                canClose = PlaceEdits.canClose(e.tags)
            } else {
                base = item?.edit?.takeIf { !confirmation }?.base?.let { target.packTags() + it } ?: target.packTags()
                notice = OnlineTexts.EDIT_OFFLINE
                nowOnOsm = emptyList()
                canClose = false
            }
            val startF = EditFields.of(base)
            f.reset(FormValues(startF.name, startF.hours, startF.phone, startF.website, moreKey = startF.moreKey, moreHours = startF.moreHours))
            val r = item?.edit?.replaceWith?.takeIf { !confirmation }
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

    /**
     * The changeset's comment for the changes ("Updated hours"; "Replaced Primo Tailoring with restaurant Joe's").
     * 0.1.41 (OP-1 d): a replacement names the old business as OSM (or the pack) has it, never [EditTarget.name],
     * which can be a saved place's label.
     */
    val comment: String get() = replaceWith?.let { r -> OnlineTexts.replaceComment(base["name"] ?: "a place", r) } ?: PlaceEdits.comment(changes)

    /** 0.1.41 (53e, OP-6): the outbox item looked at again, as it is now (its [OutboxItem.atPlace]); null for a new edit. */
    val stored: OutboxItem? get() = itemId?.let { outbox.byId(it) }

    /** 0.1.41 (53e, OP-6): the changes the summary's Upload would put in the outbox. */
    val pendingChanges: Map<String, String?> get() = changes

    /**
     * "Upload": into the outbox (replacing the item looked at again) and sent at once when it can be (4.5).
     * 0.1.41: [atPlace] (53e, decided at the tap: [UploadChecks.atPlace]) is kept with the item; so are the node's
     * point (53a: the element read, else the item looked at again, else the pack entry's) and, for a closing or a
     * different business made with the place read, its kind tags (OP-2).
     */
    fun upload(atPlace: Boolean = false) {
        if (changes.isEmpty()) return
        val e = element
        val before = outbox.byId(id)?.edit
        val node = when {
            e != null -> if (e.ref.type == OsmType.NODE && e.lat != null && e.lon != null) e.lat to e.lon else null
            before?.nodeLat != null && before.nodeLon != null -> before.nodeLat to before.nodeLon
            target.nodeLat != null && target.nodeLon != null -> target.nodeLat to target.nodeLon
            else -> null
        }
        val closing = replaceWith == null && changes.any { (k, v) -> k.startsWith("disused:") && v != null }
        val data = EditData(
            target.osm, e?.version, editBase(base, changes), changes, comment,
            replaceWith = replaceWith, packOsm = target.packOsm, icon = target.icon, typeCode = PlaceTypes.of(base)?.code,
            nodeLat = node?.first, nodeLon = node?.second,
            kind = if (e != null && (closing || replaceWith != null)) EditPlans.kindOf(base) else null,
        )
        val item = OutboxItem(
            id = id, lat = target.lat, lon = target.lon, text = lines.joinToString("\n"), placeName = target.name,
            createdMs = now(), edit = data, atPlace = atPlace,
        )
        outbox.add(item)
        already = false
        phase = EditPhase.UPLOADING
        scope.launch {
            sender.sendWaiting(id, e)
            val now = outbox.byId(id)
            when (now?.state) {
                OutboxState.SENT -> {
                    link = now.link
                    already = now.edit?.changeset == null
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

    /** Deletes the outbox item being looked at again (0.1.41: or the one this form's Upload put there, when its place was deleted). */
    fun delete() {
        (itemId ?: id.takeIf { goneWithItem })?.let { outbox.delete(it) }
    }

    val isItem: Boolean get() = itemId != null

    /** 0.1.41 (53a): the GONE screen has an item to delete. */
    val goneItem: Boolean get() = isItem || goneWithItem

    companion object {
        /**
         * The edit-time values of the changed keys ([EditData.base]); 0.1.41 (OP-3) also the other key of the same
         * phone or website detail when the place had it, so a change of it meanwhile can be told from one it had.
         */
        fun editBase(base: Map<String, String>, changes: Map<String, String?>): Map<String, String> = buildMap {
            for (k in changes.keys) base[k]?.let { put(k, it) }
            for (k in changes.keys) {
                val other = when (k) {
                    "phone" -> "contact:phone"
                    "contact:phone" -> "phone"
                    "website" -> "contact:website"
                    "contact:website" -> "website"
                    else -> null
                } ?: continue
                if (other !in changes) base[other]?.let { put(other, it) }
            }
        }

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
