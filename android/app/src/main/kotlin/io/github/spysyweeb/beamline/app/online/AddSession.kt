package io.github.spysyweeb.beamline.app.online

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where "Add a missing place" is (batch O3). DUPLICATES: the duplicate warning before the summary. */
enum class AddPhase { FORM, CHECKING, DUPLICATES, SUMMARY, UPLOADING, DONE, WAITING, REFUSED }

/** The three answers to "Already on the map here" (docs/next-round.md item 25 b). */
enum class HereChoice { REPLACED, BOTH, SAME }

/**
 * "Add a missing place" at a pressed point (batch O3, docs/online-design.md 4.3; docs/next-round.md item
 * 25 b), from the form to the upload; Compose state, read on the main thread.
 *
 * When it opens it checks the spot: the installed pack at once ([packNear]), OSM's own map of the spot as
 * soon as it arrives ([OsmHttp.map], [SpotCheck.fromMap]; offline the pack alone). A business there
 * ([SpotCheck.here]) gives the line "Already on the map here" with [HereChoice]: one must be picked before
 * Next; with several, [hereFor] is the one the answer is about. Only an address there ([SpotCheck.bareAddress])
 * and no business: the new place's tags go onto it. Next runs the duplicate check ([SpotCheck.duplicates]);
 * a duplicate is shown with "Edit that one instead" and "It's a different place". The summary says what
 * happens; Upload puts it in the outbox (a new node, or for "My place replaced it" and an address an edit
 * with [EditData.replaceWith]) and sends it at once ([OutboxSender]). Nothing here logs.
 */
class AddSession(
    val lat: Double,
    val lon: Double,
    /** An outbox item looked at again (REVIEW, WAITING, REFUSED), or null. */
    private val itemId: String?,
    private val http: OsmHttp,
    private val outbox: OutboxStore,
    private val sender: OutboxSender,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** The installed pack's named POIs within the given metres of the point (SearchFeature.poisNear). */
    private val packNear: suspend (Double) -> List<NearbyPlace>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    var phase by mutableStateOf(AddPhase.FORM)
        private set
    val f = FormFields()
    /** OSM's map of the spot was read (false: offline or still coming; the pack alone so far). */
    var osmRead by mutableStateOf(false)
        private set
    var checking by mutableStateOf(true)
        private set
    var here by mutableStateOf<List<NearbyPlace>>(emptyList())
        private set
    var bare by mutableStateOf<NearbyPlace?>(null)
        private set
    var hereFor by mutableStateOf<NearbyPlace?>(null)
    var choice by mutableStateOf<HereChoice?>(null)
    var duplicates by mutableStateOf<List<NearbyPlace>>(emptyList())
        private set
    var problem by mutableStateOf<String?>(null)
        private set
    var lines by mutableStateOf<List<String>>(emptyList())
        private set
    var link by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<Int?>(null)
        private set
    /** 0.1.41 (OP-4): "My place replaced it" went and OpenStreetMap already had every value: nothing was uploaded. */
    var already by mutableStateOf(false)
        private set

    private var places: List<NearbyPlace> = emptyList()
    private var decided: Set<String> = emptySet()
    private val id: String = itemId ?: java.util.UUID.randomUUID().toString()

    val isItem: Boolean get() = itemId != null
    val dirty: Boolean get() = (phase == AddPhase.FORM && (f.anyChanged || choice != null)) || phase == AddPhase.SUMMARY || phase == AddPhase.DUPLICATES

    /** Fills the form from an item looked at again, and checks the spot. */
    fun load() {
        outbox.byId(id)?.add?.let { a ->
            val am = MoreHours.of(a.tags)
            f.setValues(FormValues(a.tags["name"], a.tags["opening_hours"], a.tags["phone"], a.tags["website"], a.typeCode, am?.key, am?.let { a.tags[it.key] }))
            decided = a.decided
        }
        scope.launch {
            val pack = try { packNear(SpotCheck.BOX_M) } catch (_: Exception) { emptyList() }
            update(pack, null)
            val osm = try {
                withContext(io) { SpotCheck.fromMap(http.map(SpotCheck.box(lat, lon)), lat, lon) }
            } catch (_: Exception) {
                null   // offline or refused: the pack alone (the outbox checks again before it goes)
            }
            if (osm != null) osmRead = true
            update(pack, osm)
            checking = false
        }
    }

    private fun update(pack: List<NearbyPlace>, osm: List<NearbyPlace>?) {
        places = SpotCheck.merge(pack, osm)
        here = SpotCheck.here(places)
        bare = if (here.isEmpty()) SpotCheck.bareAddress(places) else null
        // the answer stays about the same place when OSM's version of it arrives
        hereFor = hereFor?.let { h -> here.firstOrNull { it.osm != null && it.osm == h.osm } } ?: here.firstOrNull().takeIf { here.size == 1 }
    }

    /** "Next": checks the form and the spot, then the duplicates or the summary. [onSame]: "It's the same place" opens its edit form. */
    fun next(onSame: (NearbyPlace) -> Unit) {
        val type = f.type
        problem = when {
            type == null -> OnlineTexts.TYPE_NEEDED
            type.named && f.name.isBlank() -> OnlineTexts.NAME_NEEDED
            f.phoneProblem != null -> f.phoneProblem
            f.websiteProblem != null -> f.websiteProblem
            f.hoursChanged && !f.hours.check() -> OnlineTexts.HOURS_CANNOT_WRITE
            EditSession.moreProblem(f) -> OnlineTexts.HOURS_CANNOT_WRITE
            here.isNotEmpty() && (choice == null || (choice != HereChoice.BOTH && hereFor == null)) -> OnlineTexts.HERE_NEEDED
            else -> null
        }
        if (problem != null || type == null) return
        if (choice == HereChoice.SAME) {
            hereFor?.let(onSame)
            return
        }
        // "My place replaced it": that object becomes the new place, so it is no duplicate of it. "Both are
        // here" leaves the others in the check: one of the same name there is still worth a warning.
        val about = if (choice == HereChoice.REPLACED) listOfNotNull(hereFor?.osm) else emptyList()
        decided = decided + about
        duplicates = SpotCheck.duplicates(type, f.name, places, decided)
        if (duplicates.isNotEmpty()) {
            phase = AddPhase.DUPLICATES
            return
        }
        toSummary()
    }

    /** "It's a different place: add mine" on the duplicate warning. */
    fun notDuplicates() {
        decided = decided + duplicates.mapNotNull { it.osm }
        duplicates = emptyList()
        toSummary()
    }

    /** The object the new place goes onto ("My place replaced it", or an address alone), or null for a new point. */
    private val onto: NearbyPlace?
        get() = when {
            choice == HereChoice.REPLACED -> hereFor
            here.isEmpty() -> bare
            else -> null
        }

    private fun toSummary() {
        val type = f.type ?: return
        val tags = EditSession.newTags(f)
        val o = onto
        lines = if (o == null) OnlineTexts.addLines(type, tags) else OnlineTexts.replaceLines(o.tags.ifEmpty { packTags(o) }, tags,
            BusinessTags.replace(o.tags.ifEmpty { packTags(o) }, tags), o.name)
        phase = AddPhase.SUMMARY
    }

    private fun packTags(o: NearbyPlace): Map<String, String> = buildMap {
        o.name?.let { put("name", it) }
        o.packHours?.let { put("opening_hours", it) }
        o.packPhone?.let { put("phone", it) }
        o.packWebsite?.let { put("website", it) }
    }

    fun backToForm() {
        phase = AddPhase.FORM
    }

    /** The changeset's comment the summary names ("Added restaurant Joe's", "Replaced Primo Tailoring with restaurant Joe's"). */
    fun commentPreview(): String {
        val type = f.type ?: return ""
        val tags = EditSession.newTags(f)
        val o = onto
        return if (o?.osm != null) OnlineTexts.replaceComment(o.name ?: o.typeWords ?: "a place", tags) else OnlineTexts.addComment(type, tags["name"])
    }

    /** 0.1.41 (53e, OP-6): the outbox item looked at again, as it is now (its [OutboxItem.atPlace]); null for a new place. */
    val stored: OutboxItem? get() = itemId?.let { outbox.byId(it) }

    /** 0.1.41 (53e, OP-6): what the summary's Upload would put in the outbox: a new place's tags, or the changes onto the place here. */
    val pendingChanges: Map<String, String?>
        get() {
            if (f.type == null) return emptyMap()
            val tags = EditSession.newTags(f)
            val o = onto
            return if (o?.osm != null) BusinessTags.replace(o.tags.ifEmpty { packTags(o) }, tags) else tags
        }

    /**
     * "Upload": into the outbox and sent at once when it can be (4.5). 0.1.41: [atPlace] (53e, decided at the tap) is
     * kept with the item; "My place replaced it" on a place read from OSM keeps its kind tags (OP-2) and, for a node,
     * its point (53a's move check).
     */
    fun upload(atPlace: Boolean = false) {
        val type = f.type ?: return
        val tags = EditSession.newTags(f)
        val o = onto
        val item = if (o?.osm != null) {
            val base = o.tags.ifEmpty { packTags(o) }
            val changes = BusinessTags.replace(base, tags)
            val node = o.fromOsm && OsmRef.parse(o.osm)?.type == OsmType.NODE
            OutboxItem(
                id = id, lat = o.lat, lon = o.lon, text = lines.joinToString("\n"), placeName = o.name ?: o.typeWords,
                createdMs = now(),
                edit = EditData(
                    o.osm, o.version, changes.keys.mapNotNull { k -> base[k]?.let { k to it } }.toMap(), changes,
                    OnlineTexts.replaceComment(o.name ?: o.typeWords ?: "a place", tags), replaceWith = tags, packOsm = o.packOsm ?: o.osm,
                    nodeLat = o.lat.takeIf { node }, nodeLon = o.lon.takeIf { node },
                    kind = if (o.fromOsm) EditPlans.kindOf(base) else null,
                ),
                atPlace = atPlace,
            )
        } else {
            OutboxItem(
                id = id, lat = lat, lon = lon, text = lines.joinToString("\n"), placeName = tags["name"] ?: type.label, createdMs = now(),
                add = AddData(type.code, tags, OnlineTexts.addComment(type, tags["name"]), decided), atPlace = atPlace,
            )
        }
        outbox.add(item)
        already = false
        phase = AddPhase.UPLOADING
        scope.launch {
            sender.sendWaiting()
            val now = outbox.byId(id)
            when (now?.state) {
                OutboxState.SENT -> {
                    link = now.link
                    already = now.edit != null && now.edit.changeset == null
                    phase = AddPhase.DONE
                }
                OutboxState.REVIEW -> {
                    // a duplicate appeared on OSM meanwhile (or the place changed): look again
                    phase = AddPhase.FORM
                    checking = true
                    problem = OnlineTexts.ADD_REVIEW
                    load()
                }
                OutboxState.REFUSED -> {
                    status = now.status
                    phase = AddPhase.REFUSED
                }
                else -> phase = AddPhase.WAITING
            }
        }
    }

    fun delete() {
        itemId?.let { outbox.delete(it) }
    }
}
