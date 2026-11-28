// FILE: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SavedUi.kt
package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** The saved places screen (docs/architecture-saved-places.md 2.6). Every text is made by SavedPlacesFeature. */
data class SavedListUi(
    /** Home (or "Set home"), Work (or "Set work"), then the other places by name; empty when read-only; pick-only: saved places only. */
    val rows: List<SavedRowUi>,
    /** The "Show on the map" switch. */
    val showOnMap: Boolean,
    /** The "Add a place" row (false at the limit, when read-only and when pick-only). */
    val canAdd: Boolean,
    /** "Places you save from search or the map show here." or the limit line; null otherwise. */
    val hint: String?,
    /** The notice of SP52 or SP53 (with "OK"), or the write failure of SP51; null otherwise. */
    val notice: String?,
    /** Opened from ADD_STOP or SET_START search: rows only, no Edit, no Set rows, no "Add a place", no switch (SP4). */
    val pickOnly: Boolean = false,
)

/** MOVE is "Change location" on screen; DELETE is "Remove" (2.6). */
enum class EditAction { RENAME, MOVE, MAKE_HOME, MAKE_WORK, DELETE }

/** "Rename", "Change location", "Make home", "Make work", "Remove" ("Remove home", "Remove work" for DELETE on Home, Work). */
data class MenuItemUi(val action: EditAction, val label: String)

/** A chip of the save dialog: "Home", "Work", "Other" (radio buttons to a screen reader, SP5). */
data class KindChoiceUi(val kind: SavedKind, val label: String, val selected: Boolean)

/** The one dialog on top. */
sealed interface SavedDialogUi {
    /** The Edit menu of one saved place; [title] is its name. */
    data class Menu(val title: String, val items: List<MenuItemUi>) : SavedDialogUi

    /** "Save place" (SP17, SP28). */
    data class Save(
        val placeTitle: String,
        val placeSubtitle: String?,
        val kinds: List<KindChoiceUi>,
        /** The name field's text; null when Home or Work is chosen (no field). */
        val name: String?,
        /** "This replaces your home: Erie Maritime Museum, 100 East Front Street, Erie." */
        val replaceText: String?,
        /** Why "Save" is off, or why the last save failed; null when fine (a point's empty name too, until "Save" is tapped: then "Type a name.", P57). */
        val problem: String?,
        /** "Save" or "Replace". */
        val confirmText: String,
        val canConfirm: Boolean,
    ) : SavedDialogUi

    /** "Rename". */
    data class Rename(val name: String, val problem: String?, val canConfirm: Boolean) : SavedDialogUi

    /** "Remove Mom's?", "Remove your home?", "Make Mom's your home?"; [destructive]: the confirm button is red, as the menu's "Remove" (P58). */
    data class Confirm(val title: String, val text: String?, val confirmText: String, val destructive: Boolean = false) : SavedDialogUi
}

/** Choosing a point on the map (SP16, SP24). */
data class MapPickUi(
    /** "Tap the map at your home", "… at your work", "… at the place", "… at the new location", "Tap the map where the trip starts" (P22) */
    val prompt: String,
    /** The point chosen so far (the app draws it as the PICKED pin); null before the first tap. */
    val place: Place?,
    /** "Point on the map" or the tapped POI's name; "No point chosen yet" before the first tap. */
    val title: String,
    /** A point's street once the app found it ("Near East 5th Street, Erie", [SavedPlacesFeature.streetFound]); until then, and for a POI, its coordinates "42.13646, -80.08718"; null before the first tap. */
    val subtitle: String?,
    /** "Outside the installed maps" (saving is still allowed). */
    val note: String?,
    /** "Set home", "Set work", "Save", "Use this point", "Use as start" (P22). */
    val confirmText: String,
    /** False before the first tap, below saved.pick_min_zoom and outside every state's map (SP24). */
    val canConfirm: Boolean,
    /** "Zoom in to choose the exact spot.", "Add this state to choose a point here.", or why the last confirm failed; null when fine. */
    val problem: String?,
)

data class SavedUi(
    /** Non-null while the saved places screen is open. */
    val list: SavedListUi? = null,
    /** Shown above everything (the list, the map, a place sheet, the pick bar). */
    val dialog: SavedDialogUi? = null,
    /** Non-null while a point is being chosen on the map. */
    val mapPick: MapPickUi? = null,
)

sealed interface SavedEvent {
    /** A row with a saved place (SP27). */
    data class RowTapped(val savedId: String) : SavedEvent
    /** "Set home" or "Set work" on the list. */
    data class SlotTapped(val kind: SavedKind) : SavedEvent
    data class EditTapped(val savedId: String) : SavedEvent
    data object AddTapped : SavedEvent
    data class ShowOnMapChanged(val show: Boolean) : SavedEvent
    data class MenuChosen(val action: EditAction) : SavedEvent
    data class KindChosen(val kind: SavedKind) : SavedEvent
    data class NameChanged(val text: String) : SavedEvent
    /** The dialog's confirm button. */
    data object DialogConfirm : SavedEvent
    /** The dialog's "Cancel", a tap outside it, or Back while it is open. */
    data object DialogDismiss : SavedEvent
    data object PickConfirm : SavedEvent
    /** "Cancel" on the pick bar, or Back while picking. */
    data object PickCancel : SavedEvent
    /** "OK" on the notice of SP52 or SP53. */
    data object NoticeDismissed : SavedEvent
    /** "Undo" on the app's bar after [SavedOutput.Removed] (P58): that saved place comes back as it was. */
    data class UndoRemove(val savedId: String) : SavedEvent
    /** Back on the list. */
    data object Back : SavedEvent
}

sealed interface SavedOutput {
    /** List opened from GO_TO search: plan the trip now (inside the installed maps). */
    data class PlanTrip(val place: Place) : SavedOutput
    /** Show the place's sheet on the map (outside the installed maps, or the list was opened from Settings). */
    data class ShowPlace(val place: Place) : SavedOutput
    /** List opened from ADD_STOP or SET_START search: [place] picked for [mode]; also the start chosen on the map (SaveTarget.Start, mode SET_START, P22). */
    data class Picked(val place: Place, val mode: SearchMode) : SavedOutput
    /** Open search in SAVE mode for [target] ("Set home", "Set work", "Add a place", "Change location"). */
    data class SearchToSave(val target: SaveTarget) : SavedOutput
    /** A save from the save dialog is done; the screens below rebuild themselves (SP19). */
    data class Saved(val place: SavedPlace) : SavedOutput
    /** The map pick ended: saved ([place]) or cancelled (null); the app shows again what the pick hid (SP25). */
    data class PickEnded(val place: SavedPlace?) : SavedOutput
    /** A saved place was removed by its Edit menu's "Remove" (P58): the app offers "Undo" ([SavedEvent.UndoRemove]) for a few seconds. */
    data class Removed(val place: SavedPlace) : SavedOutput
    data object Closed : SavedOutput
}

/**
 * UI-free controller of the saved places screen, the Edit menu, the save, rename and confirm
 * dialogs and the map pick (rules SP4, SP15-SP19, SP23-SP25, SP27, SP30, SP45). Changes go to
 * [store] on the calling thread (its file write runs on the store's own writer, SP51); a removed
 * saved place's recents are removed from [recents] (SP30). [ui] is read on the main thread.
 */
class SavedPlacesFeature(
    store: SavedPlaces,
    recents: RecentPlaces,
    installed: StateFlow<InstalledSet>,
    units: StateFlow<UnitFormatter>,
    scope: CoroutineScope,
) {
    val ui: StateFlow<SavedUi> get() = impl.ui
    /**
     * Opens the list. [pickFor]: the search mode a row tap picks for (opened from search; ADD_STOP
     * and SET_START make it pick-only, SP4); null: opened from Settings, a row tap shows the place.
     * [destination]: in SET_START, the trip's last stop (SP14).
     */
    fun openList(pickFor: SearchMode?, context: SearchContext, destination: Place? = null): Unit = impl.openList(pickFor, context, destination)
    /** The save dialog for [place] (place sheet "Save", SearchOutput.SaveNew). */
    fun openSave(place: Place): Unit = impl.openSave(place)
    /** The Edit menu of a saved place (place sheet "Edit"). */
    fun openEdit(savedId: String): Unit = impl.openEdit(savedId)
    /** Starts choosing a point for [target] (SearchOutput.PickOnMap). */
    fun startMapPick(target: SaveTarget): Unit = impl.startMapPick(target)
    /** A tap or long press on the map while picking; [poi]: the POI label under it; [zoom]: the camera zoom at the tap (SP24). */
    fun onMapTap(point: GeoPoint, poi: MapPoi?, zoom: Double): Unit = impl.onMapTap(point, poi, zoom)
    /**
     * P18 (0.1.40): the street line the app found at [point] ("Near East 5th Street, Erie"): the map
     * pick's point or a save dialog's point there (MAP_PIN) shows it and is saved with it as its place
     * name. Anything else is left as it is: a place saved before, still named by its coordinates, keeps them.
     */
    fun streetFound(point: GeoPoint, line: String): Unit = impl.streetFound(point, line)
    fun onEvent(event: SavedEvent): SavedOutput? = impl.onEvent(event)

    // Track S: the controller behind the contract above (SavedController.kt).
    private val impl = SavedController(store, recents, installed, units, scope)
}
