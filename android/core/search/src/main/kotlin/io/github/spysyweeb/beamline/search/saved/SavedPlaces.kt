// FILE: core/search/src/main/kotlin/io/github/spysyweeb/beamline/search/saved/SavedPlaces.kt
package io.github.spysyweeb.beamline.search.saved

import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executor

/** search_app.json "saved" (packbuilder/search/search_app.json; the copy in this module's rules folder). Field = JSON key. */
data class SavedRules(
    val maxOther: Int,                      // saved.max_other
    val nameMaxChars: Int,                  // saved.name_max_chars
    val samePlaceM: Double,                 // saved.same_place_m
    val searchMinChars: Int,                // saved.search_min_chars
    val searchMaxRows: Int,                 // saved.search_max_rows
    val pickMinZoom: Double,                // saved.pick_min_zoom
    val positionMaxAccuracyM: Double,       // saved.position_max_accuracy_m
) {
    companion object {
        /** From the whole text of search_app.json. */
        fun parse(searchAppJson: String): SavedRules = parseSavedRules(searchAppJson)
        /** From the copy copy_search_rules.py puts in the resources (search/rules/search_app.json). */
        fun bundled(): SavedRules = bundledSavedRules
    }
}

/** Why saved-places.json was not used when the store opened (rules SP52, SP53). */
enum class LoadProblem {
    /** Its structure is damaged: renamed to saved-places.broken-<n>.json; the store starts empty. */
    BROKEN,
    /** Written by a newer app: left in place; the store is empty and read-only. */
    NEWER,
}

enum class SaveProblem { NAME_EMPTY, NAME_NO_LETTERS, NAME_TOO_LONG, NAME_RESERVED, NAME_TAKEN, ALREADY_SAVED, LIMIT, NOT_FOUND, READ_ONLY, WRITE_FAILED }

sealed interface SaveResult {
    /** [place]: the saved place after the change; null after a delete or a show-on-map change. */
    data class Done(val place: SavedPlace?) : SaveResult
    data class Refused(val problem: SaveProblem) : SaveResult
}

/**
 * The user's saved places in [file] (saved-places.json, format beamline-saved-places/1; rules
 * SP50-SP56 of docs/architecture-saved-places.md). Reads the file once, here. A change is
 * validated and applied in memory on the calling thread, then written atomically (temporary file,
 * fsync, rename) on [writer], newest state wins. Thread-safe; Android-free; never logs (PV2).
 * [newId] makes ids (tests pass a sequence).
 */
class SavedPlaces(file: File, rules: SavedRules, writer: Executor, newId: () -> String = ::randomSavedId) {
    /** Home, then Work, then the OTHER places by name key (SP26), then id. */
    val places: StateFlow<List<SavedPlace>> get() = impl.places
    val showOnMap: StateFlow<Boolean> get() = impl.showOnMap
    /** What happened when this store opened; null when the file was fine or missing. */
    val loadProblem: LoadProblem? get() = impl.loadProblem
    /** The problem the user has not yet confirmed with "OK": BROKEN until dismissNotice (kept in the file), NEWER until dismissNotice in this process. */
    val notice: StateFlow<LoadProblem?> get() = impl.notice
    /** WRITE_FAILED after a failed write, until a later write succeeds; null otherwise. */
    val writeProblem: StateFlow<SaveProblem?> get() = impl.writeProblem
    val readOnly: Boolean get() = impl.readOnly

    fun byId(id: String): SavedPlace? = impl.byId(id)
    fun home(): SavedPlace? = impl.home()
    fun work(): SavedPlace? = impl.work()
    /** Whether [place] is [saved] (SP55 rule 2): the same savedId, or a place with a name of its own (not MAP_PIN) within same_place_m whose non-empty folded name equals the fold of saved.placeName. */
    fun same(saved: SavedPlace, place: Place): Boolean = impl.same(saved, place)
    /** The saved place that is [place] (SP55 rule 2): its savedId, else the nearest one [same] holds for, ties by kind, name key, id; null when none. */
    fun find(place: Place): SavedPlace? = impl.find(place)
    /** Rule SP20: saved places whose name matches [typed], best first, at most search_max_rows. */
    fun matching(typed: String): List<SavedPlace> = impl.matching(typed)
    /** Null when [name] can name an OTHER place (SP55 rule 1); [exceptId]: the place being renamed. */
    fun checkName(name: String, exceptId: String? = null): SaveProblem? = impl.checkName(name, exceptId)
    /** Drops the notice (SP52): rewrites the file without "notice" (BROKEN), or hides it for this process (NEWER). */
    fun dismissNotice(): Unit = impl.dismissNotice()

    /**
     * HOME or WORK: [place] fills that slot, replacing (deleting) the place there; [name] is ignored.
     * OTHER: a new place named [name] (required), refused by SP55 or SP56. A MAP_PIN place is kept
     * with its subtitle as placeName (the street line the app found for it, "Near East 5th Street,
     * Erie", or its coordinates), its coordinates when it has none, and no subtitle (3.1). A [place]
     * with source SAVED is a programming error (IllegalArgumentException with a fixed message).
     */
    fun save(place: Place, kind: SavedKind, name: String? = null): SaveResult = impl.save(place, kind, name)
    /** OTHER places only. */
    fun rename(id: String, name: String): SaveResult = impl.rename(id, name)
    /** New point, placeName, subtitle and categoryId from [to], as [save] takes them; id, kind and name stay (SP55 rule 2 for OTHER; [to] being this same place is fine). */
    fun move(id: String, to: Place): SaveResult = impl.move(id, to)
    /** An OTHER place becomes HOME or WORK: the place in that slot is deleted, the name becomes "Home" or "Work". */
    fun makeKind(id: String, kind: SavedKind): SaveResult = impl.makeKind(id, kind)
    fun delete(id: String): SaveResult = impl.delete(id)
    /**
     * Undo of a [delete] (0.1.40, P58): puts [place] back exactly as it was (its id, kind, name and
     * copies). Refused: READ_ONLY; ALREADY_SAVED when its id is in the list again or its HOME or WORK
     * slot was filled meanwhile; NAME_TAKEN and LIMIT for an OTHER place, as SP55 and SP56 say.
     */
    fun restore(place: SavedPlace): SaveResult = impl.restore(place)
    fun setShowOnMap(show: Boolean): SaveResult = impl.setShowOnMap(show)

    // Track S: the store behind the contract above (SavedStore.kt).
    private val impl = SavedStore(file, rules, writer, newId)

    /** Tests: waits until the writes asked so far have run on the writer. */
    internal fun awaitWrites() = impl.awaitWrites()
}

/** "s" and 16 lower-case hex digits from SecureRandom. */
fun randomSavedId(): String = newRandomSavedId()

/** The name key of rule SP26: uniqueness, reserved names, matching and order of saved names. */
fun savedNameKey(text: String): String = nameKeyOf(text)
