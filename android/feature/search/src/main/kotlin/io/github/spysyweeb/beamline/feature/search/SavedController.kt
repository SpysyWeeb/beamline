package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.saved.SaveProblem
import io.github.spysyweeb.beamline.search.saved.SaveResult
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import io.github.spysyweeb.beamline.search.saved.pointText
import io.github.spysyweeb.beamline.search.saved.savedNameKey
import io.github.spysyweeb.beamline.units.UnitFormatter
import java.text.BreakIterator
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The body of [SavedPlacesFeature] (docs/architecture-saved-places.md 2.2-2.6; SP4, SP15-SP19,
 * SP23-SP25, SP27, SP28, SP30, SP45). Changes go to the store on the calling thread (its file write
 * runs on its own writer, SP51); a removed saved place's recents are removed too (SP30). Every
 * text is made here. Never logs, never builds an exception message from a value (PV2).
 */
internal class SavedController(
    private val store: SavedPlaces,
    private val recents: RecentPlaces,
    private val installed: StateFlow<InstalledSet>,
    private val units: StateFlow<UnitFormatter>,
    scope: CoroutineScope,
    private val rules: SavedRules = SavedRules.bundled(),
) {
    private val state = MutableStateFlow(SavedUi())
    val ui: StateFlow<SavedUi> = state.asStateFlow()

    // the list
    private var listOpen = false
    private var pickFor: SearchMode? = null
    private var context = SearchContext(null, NearSource.NONE)
    private var destination: Place? = null

    // the dialog on top
    private sealed interface Dialog {
        data class Menu(val savedId: String) : Dialog
        data class Save(val place: Place, val kind: SavedKind, val name: String, val edited: Boolean, val failed: String? = null) : Dialog
        data class Rename(val savedId: String, val name: String, val failed: String? = null) : Dialog
        data class Remove(val savedId: String) : Dialog
        data class Make(val savedId: String, val kind: SavedKind) : Dialog
    }
    private var dialog: Dialog? = null

    // the map pick
    private data class Pick(val target: SaveTarget, val place: Place? = null, val zoom: Double = 0.0, val failed: String? = null)
    private var pick: Pick? = null

    /** P58: the place the last Remove took away, for its Undo (only that one; cleared by the Undo). */
    private var lastRemoved: SavedPlace? = null

    init {
        scope.launch { store.places.collect { refresh() } }
        scope.launch { store.showOnMap.collect { refresh() } }
        scope.launch { store.notice.collect { refresh() } }
        scope.launch { store.writeProblem.collect { refresh() } }
        scope.launch { installed.collect { refresh() } }
    }

    // ---------------------------------------------------------------- entry points
    fun openList(pickFor: SearchMode?, context: SearchContext, destination: Place?) {
        listOpen = true
        this.pickFor = pickFor
        this.context = context
        this.destination = destination
        refresh()
    }

    fun openSave(place: Place) {
        if (place.source == PlaceSource.SAVED) {   // a saved place is changed through its Edit menu, never saved again
            place.savedId?.let { openEdit(it) }
            return
        }
        val name = if (place.source == PlaceSource.MAP_PIN) "" else cut(place.name.trim(), rules.nameMaxChars)
        dialog = Dialog.Save(place, SavedKind.OTHER, name, edited = false)
        refresh()
    }

    fun openEdit(savedId: String) {
        if (store.byId(savedId) == null || store.readOnly) return
        dialog = Dialog.Menu(savedId)
        refresh()
    }

    fun startMapPick(target: SaveTarget) {
        pick = Pick(target)
        refresh()
    }

    fun onMapTap(point: GeoPoint, poi: MapPoi?, zoom: Double) {
        val p = pick ?: return
        val place = if (poi != null) Place(poi.name, poi.point, PlaceSource.MAP_POI)
        else Place(Texts.POINT_ON_MAP, point, PlaceSource.MAP_PIN, subtitle = pointText(point))
        pick = p.copy(place = place, zoom = zoom, failed = null)
        refresh()
    }

    /**
     * P18 (0.1.40): the street line the app found at [point]: the map pick's point there and a save dialog's
     * point there (MAP_PIN) show it, and are saved with it. A place saved before is never rewritten (plan 6.2).
     */
    fun streetFound(point: GeoPoint, line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        pick?.let { p ->
            p.place?.takeIf { it.source == PlaceSource.MAP_PIN && it.point == point }?.let { pick = p.copy(place = it.copy(subtitle = text)) }
        }
        (dialog as? Dialog.Save)?.let { d ->
            if (d.place.source == PlaceSource.MAP_PIN && d.place.point == point) dialog = d.copy(place = d.place.copy(subtitle = text))
        }
        refresh()
    }

    // ---------------------------------------------------------------- events
    fun onEvent(e: SavedEvent): SavedOutput? = when (e) {
        is SavedEvent.RowTapped -> rowTapped(e.savedId)
        is SavedEvent.SlotTapped -> when (e.kind) {
            SavedKind.HOME -> SavedOutput.SearchToSave(SaveTarget.Home)
            SavedKind.WORK -> SavedOutput.SearchToSave(SaveTarget.Work)
            SavedKind.OTHER -> null
        }
        is SavedEvent.EditTapped -> { openEdit(e.savedId); null }
        SavedEvent.AddTapped -> if (canAdd()) SavedOutput.SearchToSave(SaveTarget.NewPlace) else null
        is SavedEvent.ShowOnMapChanged -> { store.setShowOnMap(e.show); refresh(); null }
        is SavedEvent.MenuChosen -> menuChosen(e.action)
        is SavedEvent.KindChosen -> { (dialog as? Dialog.Save)?.let { dialog = it.copy(kind = e.kind, failed = null) }; refresh(); null }
        is SavedEvent.NameChanged -> {
            when (val d = dialog) {
                is Dialog.Save -> dialog = d.copy(name = e.text, edited = true, failed = null)
                is Dialog.Rename -> dialog = d.copy(name = e.text, failed = null)
                else -> {}
            }
            refresh()
            null
        }
        SavedEvent.DialogConfirm -> dialogConfirm()
        SavedEvent.DialogDismiss -> { dialog = null; refresh(); null }   // a save dialog over a pick: back to the pick (SP16)
        SavedEvent.PickConfirm -> pickConfirm()
        SavedEvent.PickCancel -> {
            if (pick == null) null else {
                pick = null
                dialog = null
                refresh()
                SavedOutput.PickEnded(null)
            }
        }
        SavedEvent.NoticeDismissed -> { store.dismissNotice(); refresh(); null }
        is SavedEvent.UndoRemove -> {   // P58: the last removed place back, once
            lastRemoved?.takeIf { it.id == e.savedId }?.let { lastRemoved = null; store.restore(it) }
            refresh()
            null
        }
        SavedEvent.Back -> {
            listOpen = false
            refresh()
            SavedOutput.Closed
        }
    }

    private fun rowTapped(id: String): SavedOutput? {
        val sp = store.byId(id) ?: return null
        val mode = pickFor
        if (mode == SearchMode.SET_START && isDestination(sp)) return null
        val inside = covered(installed.value, sp.point)
        val out = when {
            mode == null || !inside -> SavedOutput.ShowPlace(sp.toPlace())
            mode == SearchMode.GO_TO -> SavedOutput.PlanTrip(sp.toPlace())
            else -> SavedOutput.Picked(sp.toPlace(), mode)
        }
        listOpen = false   // the app closes the list (and search) after a pick (SP27)
        refresh()
        return out
    }

    private fun menuChosen(a: EditAction): SavedOutput? {
        val m = dialog as? Dialog.Menu ?: return null
        val sp = store.byId(m.savedId) ?: run { dialog = null; refresh(); return null }
        dialog = when (a) {
            EditAction.RENAME -> if (sp.kind == SavedKind.OTHER) Dialog.Rename(sp.id, sp.name) else null
            EditAction.MOVE -> null
            EditAction.MAKE_HOME -> if (sp.kind == SavedKind.OTHER) Dialog.Make(sp.id, SavedKind.HOME) else null
            EditAction.MAKE_WORK -> if (sp.kind == SavedKind.OTHER) Dialog.Make(sp.id, SavedKind.WORK) else null
            EditAction.DELETE -> Dialog.Remove(sp.id)
        }
        refresh()
        return if (a == EditAction.MOVE) SavedOutput.SearchToSave(SaveTarget.Move(sp.id)) else null
    }

    private fun dialogConfirm(): SavedOutput? {
        when (val d = dialog) {
            is Dialog.Save -> {
                val why = saveDialogProblem(d)
                if (why == "") {   // P57: Save tapped before a point got a name: say what is needed
                    dialog = d.copy(edited = true)
                    refresh()
                    return null
                }
                if (why != null) return null
                val old = if (d.kind == SavedKind.OTHER) null else slot(d.kind)
                val r = store.save(d.place, d.kind, if (d.kind == SavedKind.OTHER) d.name else null)
                if (r is SaveResult.Refused) {
                    dialog = d.copy(failed = problemText(r.problem, d.name, false, d.place))
                    refresh()
                    return null
                }
                old?.let { forgetRecents(it) }
                val sp = (r as SaveResult.Done).place!!
                dialog = null
                val endedPick = pick != null && pick!!.target == SaveTarget.NewPlace
                if (endedPick) pick = null
                refresh()
                return if (endedPick) SavedOutput.PickEnded(sp) else SavedOutput.Saved(sp)
            }
            is Dialog.Rename -> {
                val p = store.checkName(d.name, d.savedId)
                if (p != null) return null
                val r = store.rename(d.savedId, d.name)
                if (r is SaveResult.Refused) {
                    dialog = d.copy(failed = problemText(r.problem, d.name, true, null, d.savedId))
                    refresh()
                    return null
                }
                dialog = null
                refresh()
                return null
            }
            is Dialog.Remove -> {
                val sp = store.byId(d.savedId)
                dialog = null
                val gone = sp != null && store.delete(sp.id) is SaveResult.Done
                if (gone) {
                    forgetRecents(sp)
                    lastRemoved = sp
                }
                refresh()
                // P58: the app offers Undo for it
                return if (gone) SavedOutput.Removed(sp) else null
            }
            is Dialog.Make -> {
                val old = slot(d.kind)
                dialog = null
                if (store.makeKind(d.savedId, d.kind) is SaveResult.Done) old?.let { forgetRecents(it) }
                refresh()
                return null
            }
            is Dialog.Menu, null -> return null
        }
    }

    private fun pickConfirm(): SavedOutput? {
        val p = pick ?: return null
        val place = p.place ?: return null
        if (pickProblem(p) != null) return null
        when (val t = p.target) {
            SaveTarget.NewPlace -> {   // the save dialog over the map; its Save ends the pick
                openSave(place)
                return null
            }
            SaveTarget.Home, SaveTarget.Work -> {
                val kind = if (t == SaveTarget.Home) SavedKind.HOME else SavedKind.WORK
                val old = slot(kind)
                val r = store.save(place, kind)
                if (r is SaveResult.Refused) {
                    pick = p.copy(failed = problemText(r.problem, "", false, place))
                    refresh()
                    return null
                }
                old?.let { forgetRecents(it) }
                pick = null
                refresh()
                return SavedOutput.PickEnded((r as SaveResult.Done).place)
            }
            SaveTarget.Start -> {   // P22: nothing is saved; the trip starts there (the app sends SetOrigin)
                pick = null
                refresh()
                return SavedOutput.Picked(place, SearchMode.SET_START)
            }
            is SaveTarget.Move -> {
                val r = store.move(t.savedId, place)
                if (r is SaveResult.Refused) {
                    pick = p.copy(failed = problemText(r.problem, "", false, place))
                    refresh()
                    return null
                }
                pick = null
                refresh()
                return SavedOutput.PickEnded((r as SaveResult.Done).place)
            }
        }
    }

    // ---------------------------------------------------------------- rules
    private fun slot(kind: SavedKind): SavedPlace? = when (kind) {
        SavedKind.HOME -> store.home()
        SavedKind.WORK -> store.work()
        SavedKind.OTHER -> null
    }

    /** SP30: every recent that is the same place as [sp] goes with it. */
    private fun forgetRecents(sp: SavedPlace) {
        for (r in recents.places.value.filter { store.same(sp, it) }) recents.remove(r)
    }

    private fun canAdd(): Boolean = listOpen && !pickOnly() && !store.readOnly &&
        store.places.value.count { it.kind == SavedKind.OTHER } < rules.maxOther

    private fun pickOnly() = pickFor == SearchMode.ADD_STOP || pickFor == SearchMode.SET_START

    private fun isDestination(sp: SavedPlace): Boolean {
        val d = destination ?: return false
        return (d.savedId ?: store.find(d)?.id) == sp.id
    }

    /** [exceptId]: the place being renamed (its own name is not "taken"). */
    private fun problemText(p: SaveProblem, name: String, renaming: Boolean, place: Place?, exceptId: String? = null): String =
        Texts.problem(p, name = if (p == SaveProblem.NAME_TAKEN) takenBy(name, exceptId) else name.trim(), renaming = renaming,
            already = place?.let { store.find(it)?.name }, maxChars = rules.nameMaxChars, maxOther = rules.maxOther)

    /** "You already have a place called {name}.": the saved place's own name ("Mom's" when "moms" is typed); S's reading of 2.3. */
    private fun takenBy(name: String, exceptId: String?): String {
        val key = savedNameKey(name)
        return store.places.value.firstOrNull { it.kind == SavedKind.OTHER && it.id != exceptId && savedNameKey(it.name) == key }?.name
            ?: name.trim()
    }

    /** Why the save dialog's Save is off now (SP17, 2.3); null when it can save. */
    private fun saveDialogProblem(d: Dialog.Save): String? {
        if (d.failed != null) return d.failed
        if (d.kind != SavedKind.OTHER) return null
        val p = store.checkName(d.name)
        if (p != null) return if (p == SaveProblem.NAME_EMPTY && !d.edited) "" else problemText(p, d.name, false, d.place)
        store.find(d.place)?.let { return Texts.alreadySavedAs(it.name) }
        if (store.places.value.count { it.kind == SavedKind.OTHER } >= rules.maxOther) return problemText(SaveProblem.LIMIT, d.name, false, d.place)
        return null
    }

    /** Why the pick's confirm is off (SP24); null when it can confirm. */
    private fun pickProblem(p: Pick): String? {
        val place = p.place ?: return ""
        if (p.failed != null) return p.failed
        if (place.source != PlaceSource.MAP_POI && p.zoom < rules.pickMinZoom) return Texts.ZOOM_IN
        if (installed.value.packs.none { it.bounds.contains(place.point) }) return Texts.ADD_THIS_STATE
        return null
    }

    // ---------------------------------------------------------------- the UI state
    private fun refresh() {
        state.value = SavedUi(list = if (listOpen) listUi() else null, dialog = dialogUi(), mapPick = pickUi())
    }

    private fun listUi(): SavedListUi {
        val set = installed.value
        val u = units.value
        val pickOnly = pickOnly()
        val places = store.places.value
        val rows = ArrayList<SavedRowUi>()
        if (!store.readOnly) {
            for (kind in listOf(SavedKind.HOME, SavedKind.WORK)) {
                val sp = places.firstOrNull { it.kind == kind }
                when {
                    sp != null -> rows.add(row(sp, set, u, pickOnly))
                    !pickOnly -> rows.add(SavedRowUi(null, kind, Icons.forKind(kind), if (kind == SavedKind.HOME) Texts.SET_HOME else Texts.SET_WORK, null, null, null))
                }
            }
            for (sp in places.filter { it.kind == SavedKind.OTHER }) rows.add(row(sp, set, u, pickOnly))
        }
        val others = places.count { it.kind == SavedKind.OTHER }
        val hint = when {
            pickOnly || store.readOnly -> null
            others == 0 -> Texts.LIST_EMPTY_HINT
            others >= rules.maxOther -> Texts.listLimit(rules.maxOther)
            else -> null
        }
        val notice = Texts.notice(store.notice.value) ?: store.writeProblem.value?.let { Texts.WRITE_FAILED }
        return SavedListUi(
            rows = rows, showOnMap = store.showOnMap.value, canAdd = canAdd(), hint = hint, notice = notice, pickOnly = pickOnly,
        )
    }

    private fun row(sp: SavedPlace, set: InstalledSet, u: UnitFormatter, pickOnly: Boolean): SavedRowUi {
        val note = when {
            pickFor == SearchMode.SET_START && isDestination(sp) -> Texts.ALREADY_DESTINATION
            !covered(set, sp.point) -> Texts.OUTSIDE
            else -> null
        }
        return SavedRowUi(
            sp.id, sp.kind, Icons.forKind(sp.kind), sp.name, sp.description, distanceText(context, sp.point, u), note,
            editLabel = if (pickOnly) null else Texts.editLabel(sp.name),
        )
    }

    private fun dialogUi(): SavedDialogUi? = when (val d = dialog) {
        null -> null
        is Dialog.Menu -> store.byId(d.savedId)?.let { sp ->
            SavedDialogUi.Menu(sp.name, when (sp.kind) {
                SavedKind.HOME -> listOf(MenuItemUi(EditAction.MOVE, Texts.CHANGE_LOCATION), MenuItemUi(EditAction.DELETE, Texts.REMOVE_HOME))
                SavedKind.WORK -> listOf(MenuItemUi(EditAction.MOVE, Texts.CHANGE_LOCATION), MenuItemUi(EditAction.DELETE, Texts.REMOVE_WORK))
                SavedKind.OTHER -> listOf(
                    MenuItemUi(EditAction.RENAME, Texts.RENAME), MenuItemUi(EditAction.MOVE, Texts.CHANGE_LOCATION),
                    MenuItemUi(EditAction.MAKE_HOME, Texts.MAKE_HOME), MenuItemUi(EditAction.MAKE_WORK, Texts.MAKE_WORK),
                    MenuItemUi(EditAction.DELETE, Texts.REMOVE),
                )
            })
        }
        is Dialog.Save -> {
            val old = slot(d.kind)
            val problem = saveDialogProblem(d)
            SavedDialogUi.Save(
                placeTitle = d.place.name, placeSubtitle = d.place.subtitle,
                kinds = listOf(SavedKind.HOME, SavedKind.WORK, SavedKind.OTHER).map { KindChoiceUi(it, Texts.kindLabel(it), it == d.kind) },
                name = if (d.kind == SavedKind.OTHER) d.name else null,
                replaceText = old?.let { Texts.replaces(d.kind, it.description ?: it.placeName) },
                problem = problem?.ifEmpty { null },
                confirmText = if (old != null) Texts.REPLACE else Texts.SAVE,
                // P57: on for a point's untouched empty name too; a tap then says "Type a name."
                canConfirm = problem.isNullOrEmpty(),
            )
        }
        is Dialog.Rename -> {
            val p = d.failed ?: store.checkName(d.name, d.savedId)?.let { problemText(it, d.name, true, null, d.savedId) }
            SavedDialogUi.Rename(d.name, p, p == null)
        }
        is Dialog.Remove -> store.byId(d.savedId)?.let { sp ->
            SavedDialogUi.Confirm(
                when (sp.kind) {
                    SavedKind.HOME -> "Remove your home?"
                    SavedKind.WORK -> "Remove your work?"
                    SavedKind.OTHER -> "Remove ${sp.name}?"
                },
                null, Texts.REMOVE, destructive = true,   // P58: red, as the menu's Remove
            )
        }
        is Dialog.Make -> store.byId(d.savedId)?.let { sp ->
            val old = slot(d.kind)
            val word = if (d.kind == SavedKind.HOME) "home" else "work"
            val called = if (d.kind == SavedKind.HOME) "It will be called Home." else "It will be called Work."
            SavedDialogUi.Confirm(
                "Make ${sp.name} your $word?",
                listOfNotNull(old?.let { "Your current $word (${it.description ?: it.placeName}) will be removed." }, called).joinToString(" "),
                if (d.kind == SavedKind.HOME) Texts.MAKE_HOME else Texts.MAKE_WORK,
            )
        }
    }

    private fun pickUi(): MapPickUi? {
        val p = pick ?: return null
        val place = p.place
        val problem = pickProblem(p)
        return MapPickUi(
            prompt = when (p.target) {
                SaveTarget.Home -> "Tap the map at your home"
                SaveTarget.Work -> "Tap the map at your work"
                SaveTarget.NewPlace -> "Tap the map at the place"
                is SaveTarget.Move -> "Tap the map at the new location"
                SaveTarget.Start -> Texts.START_PROMPT   // P22
            },
            place = place,
            title = place?.name ?: Texts.NO_POINT_YET,
            // P18: a point's street once the app found it (streetFound), else its coordinates; a POI its coordinates
            subtitle = place?.let { pl ->
                if (pl.source == PlaceSource.MAP_PIN) pl.subtitle?.takeIf { it.isNotBlank() } ?: pointText(pl.point) else pointText(pl.point)
            },
            note = if (place != null && !covered(installed.value, place.point)) Texts.OUTSIDE else null,
            confirmText = when (p.target) {
                SaveTarget.Home -> Texts.SET_HOME
                SaveTarget.Work -> Texts.SET_WORK
                SaveTarget.NewPlace -> Texts.SAVE
                is SaveTarget.Move -> Texts.USE_THIS_POINT
                SaveTarget.Start -> Texts.START_HERE   // P22: "Use as start" ("Start here" could read as start guidance)
            },
            canConfirm = problem == null,
            problem = problem?.ifEmpty { null },
        )
    }

    companion object {
        /** The first [max] characters as the eye counts them (SP17, SP26). */
        fun cut(text: String, max: Int): String {
            val it = BreakIterator.getCharacterInstance(Locale.ROOT)
            it.setText(text)
            var n = 0
            var end = 0
            while (n < max) {
                val next = it.next()
                if (next == BreakIterator.DONE) return text
                end = next
                n++
            }
            return text.substring(0, end).trim()
        }
    }
}
