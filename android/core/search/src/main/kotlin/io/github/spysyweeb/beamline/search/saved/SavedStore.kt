package io.github.spysyweeb.beamline.search.saved

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.search.Geo
import io.github.spysyweeb.beamline.search.LatestTextWriter
import io.github.spysyweeb.beamline.search.foldText
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The body of [SavedPlaces] (rules SP50-SP56 of docs/architecture-saved-places.md): the file read
 * once, here (structure only, SP52, SP53); every change validated and applied in memory on the
 * calling thread under one lock, then the whole file written on [writer] (temporary file, fsync,
 * rename; newest state wins, SP51). Never logs; no exception message here holds a value (PV2).
 */
internal class SavedStore(
    private val file: File,
    private val rules: SavedRules,
    writer: Executor,
    private val newId: () -> String,
) {
    private val lock = Any()
    private val placesFlow: MutableStateFlow<List<SavedPlace>>
    private val showFlow: MutableStateFlow<Boolean>
    private val noticeFlow: MutableStateFlow<LoadProblem?>
    private val writeProblemFlow = MutableStateFlow<SaveProblem?>(null)
    /** BROKEN kept in the file ("notice": "broken") until dismissNotice. */
    private var noticeInFile: Boolean
    val loadProblem: LoadProblem?
    val readOnly: Boolean

    private val out = LatestTextWriter(file, writer) { ok -> writeProblemFlow.value = if (ok) null else SaveProblem.WRITE_FAILED }

    init {
        // a leftover temporary file is a write that never reached its rename: the main file is the truth
        val tmp = File(file.absoluteFile.parentFile, file.name + ".tmp")
        if (tmp.isFile) tmp.delete()
        var problem: LoadProblem? = null
        var ro = false
        var content = SavedPlacesFile.Content()
        var writeEmptyBroken = false
        if (file.exists()) {
            // A read that fails (an I/O error, a folder in the file's place, no permission) says
            // nothing about the structure: the file may be a perfectly good list. It is left exactly
            // where it is, untouched, and the store is read-only with the BROKEN notice in this
            // process only, as when a broken file cannot be moved aside (SP52's last sentence). Only
            // a text that was read and fails the structure check is moved aside.
            val text: String? = try {
                file.readBytes().toString(Charsets.UTF_8)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }
            val decoded = if (text == null) null else SavedPlacesFile.decode(text)
            when (decoded) {
                null -> {
                    problem = LoadProblem.BROKEN
                    ro = true
                }
                is SavedPlacesFile.Decoded.Ok -> content = decoded.content
                SavedPlacesFile.Decoded.Newer -> {
                    problem = LoadProblem.NEWER
                    ro = true
                }
                SavedPlacesFile.Decoded.Broken -> {
                    problem = LoadProblem.BROKEN
                    if (keepAside(file)) writeEmptyBroken = true else ro = true   // never overwrite what cannot be moved aside
                }
            }
        }
        loadProblem = problem
        readOnly = ro
        noticeInFile = writeEmptyBroken || content.notice == SavedPlacesFile.NOTICE_BROKEN
        val notice = when {
            problem != null -> problem
            noticeInFile -> LoadProblem.BROKEN
            else -> null
        }
        placesFlow = MutableStateFlow(ordered(content.places))
        showFlow = MutableStateFlow(content.showOnMap)
        noticeFlow = MutableStateFlow(notice)
        if (writeEmptyBroken) out.submit(encodeNow())
    }

    val places: StateFlow<List<SavedPlace>> get() = placesFlow.asStateFlow()
    val showOnMap: StateFlow<Boolean> get() = showFlow.asStateFlow()
    val notice: StateFlow<LoadProblem?> get() = noticeFlow.asStateFlow()
    val writeProblem: StateFlow<SaveProblem?> get() = writeProblemFlow.asStateFlow()

    fun byId(id: String): SavedPlace? = placesFlow.value.firstOrNull { it.id == id }
    fun home(): SavedPlace? = placesFlow.value.firstOrNull { it.kind == SavedKind.HOME }
    fun work(): SavedPlace? = placesFlow.value.firstOrNull { it.kind == SavedKind.WORK }

    fun same(saved: SavedPlace, place: Place): Boolean {
        if (place.savedId != null) return place.savedId == saved.id
        if (place.source !in NAMED_SOURCES) return false
        val f = foldText(place.name)
        if (f.isEmpty() || f != foldText(saved.placeName)) return false
        return distanceM(saved.point, place.point) <= rules.samePlaceM
    }

    fun find(place: Place): SavedPlace? = find(place, placesFlow.value)

    private fun find(place: Place, list: List<SavedPlace>): SavedPlace? {
        place.savedId?.let { id -> list.firstOrNull { it.id == id }?.let { return it } }
        return list.filter { same(it, place) }.minWithOrNull(
            compareBy<SavedPlace> { distanceM(it.point, place.point) }.then(STORE_ORDER),
        )
    }

    fun matching(typed: String): List<SavedPlace> = SavedMatcher.matching(placesFlow.value, typed, rules)

    fun checkName(name: String, exceptId: String? = null): SaveProblem? = checkName(name, exceptId, placesFlow.value)

    private fun checkName(name: String, exceptId: String?, list: List<SavedPlace>): SaveProblem? {
        val clean = cleanName(name)
        if (clean.isEmpty()) return SaveProblem.NAME_EMPTY
        val key = nameKeyOf(clean)
        if (key.isEmpty()) return SaveProblem.NAME_NO_LETTERS
        if (graphemeCount(clean) > rules.nameMaxChars) return SaveProblem.NAME_TOO_LONG
        if (key == RESERVED_HOME || key == RESERVED_WORK) return SaveProblem.NAME_RESERVED
        if (list.any { it.kind == SavedKind.OTHER && it.id != exceptId && nameKeyOf(it.name) == key }) return SaveProblem.NAME_TAKEN
        return null
    }

    fun dismissNotice() {
        synchronized(lock) {
            if (noticeFlow.value == null) return
            noticeFlow.value = null
            if (noticeInFile && !readOnly) {
                noticeInFile = false
                out.submit(encodeNow())
            }
        }
    }

    fun save(place: Place, kind: SavedKind, name: String?): SaveResult {
        require(place.source != PlaceSource.SAVED) { "a saved place is not saved again; use makeKind" }
        synchronized(lock) {
            if (readOnly) return SaveResult.Refused(SaveProblem.READ_ONLY)
            val list = placesFlow.value
            val copy = copyOf(place)
            val made = when (kind) {
                SavedKind.HOME, SavedKind.WORK -> SavedPlace(
                    id = freshId(list), kind = kind, name = slotName(kind), point = copy.point,
                    placeName = copy.placeName, subtitle = copy.subtitle, categoryId = copy.categoryId,
                )
                SavedKind.OTHER -> {
                    checkName(name ?: "", null, list)?.let { return SaveResult.Refused(it) }
                    if (find(place, list) != null) return SaveResult.Refused(SaveProblem.ALREADY_SAVED)
                    if (list.count { it.kind == SavedKind.OTHER } >= rules.maxOther) return SaveResult.Refused(SaveProblem.LIMIT)
                    SavedPlace(
                        id = freshId(list), kind = kind, name = cleanName(name ?: ""), point = copy.point,
                        placeName = copy.placeName, subtitle = copy.subtitle, categoryId = copy.categoryId,
                    )
                }
            }
            val kept = if (kind == SavedKind.OTHER) list else list.filterNot { it.kind == kind }
            commit(kept + made)
            return SaveResult.Done(made)
        }
    }

    fun rename(id: String, name: String): SaveResult {
        synchronized(lock) {
            if (readOnly) return SaveResult.Refused(SaveProblem.READ_ONLY)
            val list = placesFlow.value
            val p = list.firstOrNull { it.id == id } ?: return SaveResult.Refused(SaveProblem.NOT_FOUND)
            require(p.kind == SavedKind.OTHER) { "Home and Work keep their names; only other places are renamed" }
            checkName(name, id, list)?.let { return SaveResult.Refused(it) }
            val renamed = p.copy(name = cleanName(name))
            commit(list.map { if (it.id == id) renamed else it })
            return SaveResult.Done(renamed)
        }
    }

    fun move(id: String, to: Place): SaveResult {
        require(to.source != PlaceSource.SAVED) { "a saved place is not a new location" }
        synchronized(lock) {
            if (readOnly) return SaveResult.Refused(SaveProblem.READ_ONLY)
            val list = placesFlow.value
            val p = list.firstOrNull { it.id == id } ?: return SaveResult.Refused(SaveProblem.NOT_FOUND)
            if (p.kind == SavedKind.OTHER) {
                val other = find(to, list)
                if (other != null && other.id != id) return SaveResult.Refused(SaveProblem.ALREADY_SAVED)
            }
            val copy = copyOf(to)
            val moved = p.copy(point = copy.point, placeName = copy.placeName, subtitle = copy.subtitle, categoryId = copy.categoryId)
            commit(list.map { if (it.id == id) moved else it })
            return SaveResult.Done(moved)
        }
    }

    fun makeKind(id: String, kind: SavedKind): SaveResult {
        require(kind != SavedKind.OTHER) { "makeKind makes a home or a work" }
        synchronized(lock) {
            if (readOnly) return SaveResult.Refused(SaveProblem.READ_ONLY)
            val list = placesFlow.value
            val p = list.firstOrNull { it.id == id } ?: return SaveResult.Refused(SaveProblem.NOT_FOUND)
            require(p.kind == SavedKind.OTHER) { "only an other place becomes Home or Work" }
            val made = p.copy(kind = kind, name = slotName(kind))
            commit(list.filterNot { it.kind == kind }.map { if (it.id == id) made else it })
            return SaveResult.Done(made)
        }
    }

    fun delete(id: String): SaveResult {
        synchronized(lock) {
            if (readOnly) return SaveResult.Refused(SaveProblem.READ_ONLY)
            val list = placesFlow.value
            if (list.none { it.id == id }) return SaveResult.Refused(SaveProblem.NOT_FOUND)
            commit(list.filterNot { it.id == id })
            return SaveResult.Done(null)
        }
    }

    /**
     * P58 (0.1.40): Undo of [delete]: [place] back exactly as it was (id, kind, name, copies). Refused when its
     * id is in the list again or its HOME/WORK slot was filled (ALREADY_SAVED), or for an OTHER place whose name
     * was taken (NAME_TAKEN) or at the limit (LIMIT), as SP55/SP56 say.
     */
    fun restore(place: SavedPlace): SaveResult {
        synchronized(lock) {
            if (readOnly) return SaveResult.Refused(SaveProblem.READ_ONLY)
            val list = placesFlow.value
            if (list.any { it.id == place.id }) return SaveResult.Refused(SaveProblem.ALREADY_SAVED)
            when (place.kind) {
                SavedKind.HOME, SavedKind.WORK -> if (list.any { it.kind == place.kind }) return SaveResult.Refused(SaveProblem.ALREADY_SAVED)
                SavedKind.OTHER -> {
                    val key = nameKeyOf(place.name)
                    if (list.any { it.kind == SavedKind.OTHER && nameKeyOf(it.name) == key }) return SaveResult.Refused(SaveProblem.NAME_TAKEN)
                    if (list.count { it.kind == SavedKind.OTHER } >= rules.maxOther) return SaveResult.Refused(SaveProblem.LIMIT)
                }
            }
            commit(list + place)
            return SaveResult.Done(place)
        }
    }

    fun setShowOnMap(show: Boolean): SaveResult {
        synchronized(lock) {
            if (readOnly) return SaveResult.Refused(SaveProblem.READ_ONLY)
            if (showFlow.value != show) {
                showFlow.value = show
                out.submit(encodeNow())
            }
            return SaveResult.Done(null)
        }
    }

    /** Tests: waits until the writes asked so far have run. */
    fun awaitWrites() = out.awaitIdle()

    private fun commit(list: List<SavedPlace>) {
        placesFlow.value = ordered(list)
        out.submit(encodeNow())
    }

    private fun encodeNow(): String = SavedPlacesFile.encode(
        SavedPlacesFile.Content(showFlow.value, if (noticeInFile) SavedPlacesFile.NOTICE_BROKEN else null, placesFlow.value),
    )

    private fun freshId(list: List<SavedPlace>): String {
        while (true) {
            val id = newId()
            if (list.none { it.id == id }) return id
        }
    }

    /**
     * What a saved place keeps of [place] (3.1): the point rounded to 6 decimals; a MAP_PIN place by the line it
     * carries (P18, 0.1.40: the street the app found, "Near East 5th Street, Erie"), else its coordinates.
     */
    private data class Copy(val point: GeoPoint, val placeName: String, val subtitle: String?, val categoryId: String?)

    private fun copyOf(place: Place): Copy {
        val point = GeoPoint(SavedPlacesFile.round6(place.point.lat), SavedPlacesFile.round6(place.point.lon))
        // P18: a point keeps its line as its place name; no subtitle, no category (3.1)
        if (place.source == PlaceSource.MAP_PIN) {
            return Copy(point, place.subtitle?.trim()?.takeIf { it.isNotEmpty() } ?: pointText(place.point), null, null)
        }
        if (place.name.isBlank()) return Copy(point, pointText(place.point), null, null)
        return Copy(point, place.name, place.subtitle?.takeIf { it.isNotBlank() }, place.categoryId)
    }

    companion object {
        const val RESERVED_HOME = "home"
        const val RESERVED_WORK = "work"

        /** Sources whose place has a name of its own (SP55 rule 2): not MAP_PIN, not SAVED. */
        val NAMED_SOURCES = setOf(PlaceSource.SEARCH, PlaceSource.RECENT, PlaceSource.MAP_POI, PlaceSource.RESUMED)

        fun slotName(kind: SavedKind): String = when (kind) {
            SavedKind.HOME -> SavedPlacesFile.HOME_NAME
            SavedKind.WORK -> SavedPlacesFile.WORK_NAME
            SavedKind.OTHER -> error("an other place has its own name")
        }

        /** Home, Work, then the others by name key (by code point), then id. */
        val STORE_ORDER: Comparator<SavedPlace> = Comparator { a, b ->
            val k = a.kind.ordinal.compareTo(b.kind.ordinal)
            if (k != 0) return@Comparator k
            val n = compareCodePoints(nameKeyOf(a.name), nameKeyOf(b.name))
            if (n != 0) n else a.id.compareTo(b.id)
        }

        fun ordered(list: List<SavedPlace>): List<SavedPlace> = list.sortedWith(STORE_ORDER)

        fun distanceM(a: GeoPoint, b: GeoPoint): Double = Geo.haversineKm(a.lat, a.lon, b.lat, b.lon) * 1000.0

        /**
         * Moves a damaged file aside as saved-places.broken-<n>.json (n = 1 + the highest that
         * exists, so nothing is overwritten). False when it could not be moved.
         */
        fun keepAside(file: File): Boolean {
            val dir = file.absoluteFile.parentFile ?: return false
            val stem = file.name.removeSuffix(".json")
            val pattern = Regex(Regex.escape(stem) + "\\.broken-([0-9]{1,9})\\.json")
            val highest = dir.list()?.mapNotNull { pattern.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 0
            val target = File(dir, "$stem.broken-${highest + 1}.json")
            if (target.exists()) return false
            return try {
                java.nio.file.Files.move(file.toPath(), target.toPath())
                true
            } catch (_: Exception) {
                file.renameTo(target)
            }
        }
    }
}
