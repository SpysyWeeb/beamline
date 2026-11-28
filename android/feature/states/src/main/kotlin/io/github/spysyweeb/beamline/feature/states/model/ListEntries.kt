package io.github.spysyweeb.beamline.feature.states.model

/**
 * P11 (audit F65, F66): what the States screen's one scrolling list holds, in order. In portrait the
 * map card is one of the entries; in landscape it is its own pane ([listEntries]'s withMap false). A
 * selection scrolls to its row by this order (the old index left out the section titles).
 */
sealed interface ListEntry {
    val key: String
    data class Line(override val key: String, val text: String, val small: Boolean = false) : ListEntry
    data class Banner(override val key: String, val banner: BannerUi) : ListEntry
    data class Progress(override val key: String, val job: JobUi) : ListEntry
    data object Map : ListEntry { override val key = "map" }
    data object Search : ListEntry { override val key = "search" }
    data class NoMatch(val text: String) : ListEntry { override val key = "no-match" }
    /** [top]: "On this phone", "Add a state", or a section of the first-run list (no "Add a state" above it). */
    data class Title(val text: String, val top: Boolean) : ListEntry { override val key = "title-$text" }
    data class Row(val section: String, val row: StateRowUi) : ListEntry { override val key = "row-$section-${row.code}" }
    data object Footer : ListEntry { override val key = "footer" }
}

fun listEntries(ui: StatesUi, withMap: Boolean): List<ListEntry> = buildList {
    ui.intro?.let { add(ListEntry.Line("intro", it)) }
    ui.loadingLine?.let { add(ListEntry.Line("loading", it, small = true)) }
    ui.banner?.let { add(ListEntry.Banner("banner", it)) }
    ui.readyBanner?.let { add(ListEntry.Banner("ready-banner", it)) }
    ui.job?.let { add(ListEntry.Progress("job", it)) }
    ui.fileProgress?.let { add(ListEntry.Progress("file-progress", it)) }
    if (withMap) add(ListEntry.Map)
    if (ui.searchHint != null) {
        add(ListEntry.Search)
        // P12: always there under the field, empty while something matches, so that "No state matches" appearing
        // is a change of an existing live region (TalkBack announces that; a new node may not be announced)
        add(ListEntry.NoMatch(ui.noMatch ?: ""))
    }
    val sub = ui.addTitle != null
    ui.onPhoneTitle?.let { add(ListEntry.Title(it, top = true)) }
    ui.onPhone.forEach { add(ListEntry.Row("on-phone", it)) }
    ui.addTitle?.let { add(ListEntry.Title(it, top = true)) }
    ui.availableTitle?.let { add(ListEntry.Title(it, top = !sub)) }
    ui.available.forEach { add(ListEntry.Row("available", it)) }
    ui.notAvailableTitle?.let { add(ListEntry.Title(it, top = !sub)) }
    ui.notAvailable.forEach { add(ListEntry.Row("not-available", it)) }
    ui.needsNewerTitle?.let { add(ListEntry.Title(it, top = !sub)) }
    ui.needsNewer.forEach { add(ListEntry.Row("needs-newer", it)) }
    add(ListEntry.Footer)
}

/** The list index of [code]'s row; -1 when it is not listed (filtered out by the search). */
fun rowIndex(entries: List<ListEntry>, code: String): Int = entries.indexOfFirst { it is ListEntry.Row && it.row.code == code }
