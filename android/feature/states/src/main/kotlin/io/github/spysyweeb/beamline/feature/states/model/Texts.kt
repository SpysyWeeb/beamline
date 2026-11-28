package io.github.spysyweeb.beamline.feature.states.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Sizes, dates and the fixed sentences of docs/architecture-downloads.md section 1, in one place so
 * the screen, the notifications and the map chip say the same thing. Durations come from
 * UnitFormatter (rule 2.9 of docs/architecture.md); nothing else formats a duration here.
 */
object Texts {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    /** Decimal sizes (1 GB = 1,000,000,000 bytes): "less than 1 MB", "8.5 MB", "553 MB", "1.0 GB". */
    fun size(bytes: Long): String {
        if (bytes < 1_000_000) return "less than 1 MB"
        val mb = bytes / 1e6
        if (mb < 9.95) return String.format(Locale.US, "%.1f MB", mb)
        if (mb.roundToLong() < 1000) return "${mb.roundToLong()} MB"
        return String.format(Locale.US, "%.1f GB", bytes / 1e9)
    }

    /**
     * Free space and space needed, always in GB as the sheets of 1.1 step 4 write them ("Needs about
     * 1.9 GB free. This phone has 0.8 GB free.", "Free now: 24 GB."): one decimal below 10 GB, whole
     * gigabytes from 10 GB.
     */
    fun space(bytes: Long): String {
        val gb = maxOf(0L, bytes) / 1e9
        return if (gb >= 9.95) "${gb.roundToLong()} GB" else String.format(Locale.US, "%.1f GB", gb)
    }

    /** "Nov 14, 2026" from an OpenStreetMap snapshot "2026-11-14". */
    fun date(isoDate: String): String = try {
        LocalDate.parse(isoDate).format(DATE)
    } catch (_: Exception) {
        isoDate
    }

    /** "Nov 15, 2026" for an instant in [zone]. */
    fun date(epochMs: Long, zone: ZoneId): String = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().format(DATE)

    fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)

    // --- 1.1 first run
    const val CHOOSE_TITLE = "Choose your state"
    const val CHOOSE_INTRO = "Beamline downloads the maps, directions and search for your state once. After that it works without a signal."
    const val LOADING = "Getting the list of states…"
    const val MAP_HINT = "Tap a state, or pick it from the list."
    const val MAP_DESCRIPTION = "Map of the United States. The list below has every state."
    /** The same while no list of states to add is shown (downloads off). */
    const val MAP_DESCRIPTION_NO_LIST = "Map of the United States."
    const val WHOLE_COUNTRY = "Whole country"
    const val SEARCH = "Search states"
    /** P12: under the search field when nothing matches; one fixed text, so a screen reader says it once. */
    const val NO_MATCH = "No state matches your search."
    /**
     * P12: the search field's X, which empties the field: the same name as the place search field's X
     * (P44). Not "Clear search": that is the map pill's X, which ends the results.
     */
    const val CLEAR_TEXT = "Clear text"
    const val AVAILABLE = "Available"
    const val NOT_AVAILABLE = "Not available yet"
    const val NEEDS_NEWER_SECTION = "Needs a newer Beamline"
    const val NEEDS_NEWER_LINE = "Needs a newer version of Beamline"
    const val USE_FILE = "Use a file instead"
    const val NOT_NOW = "Not now"
    const val LOOK_AT_MAP = "Look at the map while you wait"
    const val WHICH_ONE = "Which one?"
    fun availableLine(download: Long, snapshot: String) = "${size(download)} download · data from ${date(snapshot)}"

    // --- the state sheet
    fun sheetIntro(name: String, snapshot: String) = "Maps, directions and search for $name, from OpenStreetMap data of ${date(snapshot)}."
    fun downloadLine(bytes: Long) = "Download: ${size(bytes)}"
    fun onPhoneLine(unpacked: Long, free: Long) = "On this phone: ${size(unpacked)}. Free now: ${space(free)}."
    const val HOUSE_NUMBERS = "House numbers"
    fun houseNumbersHint(optional: Long) = "Find addresses like \"4319 Peach St\". Adds ${size(optional)}."
    fun houseNumbersAdds(optional: Long) = "Adds ${size(optional)}."
    fun sources(credit: String) = "Sources: $credit"
    const val DOWNLOAD = "Download"
    const val CANCEL = "Cancel"
    const val CLOSE = "Close"
    const val PRIVACY = "Downloading tells the download server your IP address and which state you chose. Nothing else is sent."
    fun mobileData(download: Long) = "You're on mobile data. This download uses ${size(download)} of your data plan."
    const val WIFI_ONLY = "Download on Wi-Fi only"
    const val USE_MOBILE_DATA = "Use mobile data"
    const val OFFLINE_SHEET = "You're offline. The download starts when you're connected."
    const val WHEN_ONLINE = "Download when online"
    fun needsSpace(need: Long, free: Long) = "Needs about ${space(need)} free. This phone has ${space(free)} free."
    const val FREE_UP_SPACE = "Free up space"
    fun oneAtATime(downloading: String) = "One state at a time. $downloading is downloading."
    fun connectToSee(name: String) = "Connect to the internet to see the download for $name."
    const val TRY_AGAIN = "Try again"
    const val CANT_LOAD_SHORT = "Can't load the list of states right now."
    fun noDownload(name: String) = "There's no download for $name yet."
    fun nearby(names: List<String>) = "Nearby states you can download: ${names.joinToString(", ")}."
    const val NEEDS_NEWER_SHEET = "This download needs a newer version of Beamline."
    /** P25: what "Use a file instead" means, on the sheet of a state without a download. */
    const val FILE_HINT = "You can also install a state file (.tar) copied to this phone."

    // --- progress view (1.1 step 5)
    fun downloading(name: String) = "Downloading $name"
    fun addingHouseNumbers(name: String) = "Adding house numbers to $name"
    fun installing(name: String) = "Installing $name"
    const val GETTING_READY = "Getting ready…"
    const val CONTINUING = "Continuing the download…"
    fun ofTotal(done: Long, total: Long) = "${size(done)} of ${size(total)}"
    fun left(duration: String) = " · about $duration left"
    const val LESS_THAN_A_MINUTE = " · less than a minute left"
    const val UNPACKING_ROUTING = "Unpacking routing data…"
    const val UNPACKING_SEARCH = "Unpacking search data…"
    const val UNPACKING_HOUSE_NUMBERS = "Unpacking house numbers…"
    const val CHECKING_FILES = "Checking the files…"
    const val COMBINING = "Combining routing data…"
    const val FINISHING = "Finishing…"
    const val KEEPS_GOING = "You can leave Beamline or lock your phone. The download keeps going."
    const val KEEP_OPEN_DOWNLOAD = "Keep Beamline open until the download has finished."
    const val NOTIFICATIONS_OFF = "Notifications are off, so Beamline can't tell you when it's done."
    const val TURN_ON = "Turn on"
    const val PAUSE = "Pause"
    const val CONTINUE = "Continue"
    fun stopDownloading(name: String) = "Stop downloading $name?"
    fun deletedSoFar(done: Long) = "The ${size(done)} downloaded so far are deleted."
    const val DELETE = "Delete"
    const val KEEP_DOWNLOADING = "Keep downloading"
    fun ready(name: String) = "$name is ready."
    fun switched(name: String) = "A newer $name was published. Downloading that one."

    // --- 1.4 waits, pauses, failures
    const val WAITING_WIFI = "Waiting for Wi-Fi"
    fun waitingWifiLine(done: Long, total: Long) = "${ofTotal(done, total)}. The download goes on when you're on Wi-Fi."
    const val WAITING_CONNECTION = "Waiting for a connection"
    fun waitingConnectionLine(done: Long, total: Long) = "${ofTotal(done, total)}. The download goes on when you're online."
    const val CONNECTION_LOST = "Connection lost"
    const val TRYING_AGAIN = "Trying again in a moment."
    fun downloaded(name: String) = "$name is downloaded"
    const val AFTER_TRIP = "It will be installed when your trip ends."
    /** Not in section 1 (a draft): the installer is busy with an install the user started from a file. */
    const val AFTER_OTHER_INSTALL = "It will be installed when the other install has finished."
    /**
     * Not in section 1 (a draft): under Details of "<file> can't be installed" when the installer
     * cannot read the picked file at all (its inspect throws), so there are no installer's words.
     */
    const val FILE_NOT_A_PACK = "This file is not a Beamline state pack."
    const val DOWNLOAD_PAUSED = "Download paused"
    fun progressKept(done: Long, total: Long) = "${ofTotal(done, total)}. Your progress is kept."
    const val NOT_ENOUGH_SPACE = "Not enough space"
    fun needsFree(name: String, need: Long, free: Long) = "$name needs ${space(need)} free. This phone has ${space(free)} free."
    const val UNREACHABLE = "Couldn't reach the download server"
    const val CLOCK_DOWNLOAD = "Your phone's date or time looks wrong, so the download server can't be checked. Check it in Settings, then tap Continue."
    fun closedTwice(name: String) = "Beamline closed twice while working on $name."
    const val DOWNLOAD_STOPPED = "Download stopped"
    fun damaged(name: String) = "Part of $name arrived damaged twice. The parts that arrived whole are kept. Try again later."
    const val BAD_RESPONSE = "The download server sent something Beamline doesn't accept. Your progress is kept. Try again later."
    fun noLongerOffered(name: String) = "$name is no longer offered"
    const val LIST_CHANGED = "The list of states changed. Check the list again."
    const val DOWNLOAD_FAILED = "Download failed"
    fun disagree(name: String) = "The list of states and the download server disagree about $name. Check the list again later."
    fun cantInstall(name: String) = "$name can't be installed"
    const val NOTHING_CHANGED = "Nothing was changed on this phone."
    const val DETAILS = "Details"
    const val OK = "OK"
    const val CHECK_AGAIN = "Check again"

    // --- banners about the list
    const val OFFLINE_BANNER = "You're offline. Connect to the internet to download your state, or use a file."
    const val CANT_LOAD_BANNER = "Can't load the list of states right now. Try again later, or use a file."
    const val CLOCK_BANNER = "Can't check the list of states: your phone's date or time looks wrong. Check it in Settings, then try again."
    const val DOWNLOADS_OFF = "Downloads are off in this build. Use a file instead: a state file (.tar) copied to this phone."
    const val NEWER_FORMAT_BANNER = "This version of Beamline can't read the list of states. Update Beamline, or use a file."
    fun listFrom(date: String) = "List from $date. Couldn't check for changes."
    fun listChecked(date: String) = "List checked $date."

    // --- 1.2 the States screen
    const val STATES_TITLE = "States"
    /** P13: the top bar's Back arrow. */
    const val BACK = "Back"
    const val ON_THIS_PHONE = "On this phone"
    const val ADD_A_STATE = "Add a state"
    fun installedLine(snapshot: String, bytes: Long) = "On this phone · data from ${date(snapshot)} · ${size(bytes)}"
    /** P63: the row's line under the heading "On this phone", which it does not repeat ([installedLine] is the sheet's). */
    fun installedRowLine(snapshot: String, bytes: Long) = "Data from ${date(snapshot)} · ${size(bytes)}"
    /** P14: the window of a state on this phone when the fetched list has nothing newer. */
    fun upToDate(date: String) = "Up to date. " + listChecked(date)
    /**
     * "Built Nov 21, 2026" from a pack_version (the build time as YYYYMMDDhhmm, UTC; its date as written,
     * which is the date in the pack's release name); null for a number that is not such a time. Two
     * packs can hold the same OpenStreetMap day and differ in what was built from it (the owner,
     * 2026-11-21: a rebuilt pack looked the same as the one it replaced).
     */
    fun builtLine(packVersion: Long): String? {
        val day = packVersion / 10_000
        if (packVersion < 0 || day !in 1000_01_01L..9999_12_31L) return null
        return try {
            "Built " + LocalDate.of((day / 10_000).toInt(), (day / 100 % 100).toInt(), (day % 100).toInt()).format(DATE)
        } catch (_: Exception) {
            null
        }
    }
    const val HOUSE_NUMBERS_INCLUDED = "House numbers: included"
    const val HOUSE_NUMBERS_NOT_INCLUDED = "House numbers: not included"
    const val NOT_CHECKED = "copied outside the app, not checked"
    const val REMOVE = "Remove"
    const val KEEP = "Keep"
    fun removeTitle(name: String) = "Remove $name?"
    const val REMOVE_TEXT = "Its maps, directions and search are deleted from this phone. You can download it again."
    /** P62: the Remove dialog says what it frees, as "Remove house numbers" does; [REMOVE_TEXT] without a size. */
    fun removeText(frees: Long?) = if (frees == null || frees <= 0) REMOVE_TEXT
        else "Its maps, directions and search are deleted from this phone. This frees ${size(frees)}. You can download it again."
    const val NO_REMOVE_DURING_TRIP = "You can't remove a state during a trip."
    fun beingUpdated(name: String) = "$name is being updated. Cancel the update to remove it."
    fun couldNotRemove(name: String) = "Couldn't remove $name. Nothing was changed."
    const val SHOW_ON_MAP = "Show on map"
    fun progressDownloading(p: Int) = "Downloading · $p %"
    fun progressWaitingWifi(p: Int) = "Waiting for Wi-Fi · $p %"
    fun progressPaused(p: Int) = "Paused · $p %"
    const val PROGRESS_INSTALLING = "Installing…"
    fun addHouseNumbers(optional: Long) = "Add house numbers · ${size(optional)}"
    fun addHouseNumbersTitle(name: String) = "Add house numbers to $name"
    const val ADD_HOUSE_NUMBERS_TEXT = "Find addresses like \"4319 Peach St\"."
    fun addHouseNumbersSizes(download: Long, unpacked: Long) = "Download: ${size(download)}. On this phone: ${size(unpacked)} more."
    const val ADD = "Add"
    fun removeAndDownloadAgain(name: String) = "To add house numbers, remove $name and download it again."
    const val REMOVE_HOUSE_NUMBERS = "Remove house numbers"
    fun removeHouseNumbersTitle(name: String) = "Remove house numbers from $name?"
    fun removeHouseNumbersText(frees: Long) = "Searching for addresses like \"4319 Peach St\" stops working. This frees ${size(frees)}."

    // --- 1.3 updates
    fun updateLine(snapshot: String, download: Long) = "Update available · data from ${date(snapshot)} · ${size(download)} download"
    const val UPDATE = "Update"
    fun updateTitle(name: String) = "Update $name"
    fun updateIntro(newSnapshot: String, oldSnapshot: String) = "New OpenStreetMap data of ${date(newSnapshot)}. Yours is from ${date(oldSnapshot)}."
    fun keepsWorking(name: String) = "Your $name keeps working until the update is installed."
    const val HOUSE_NUMBERS_DASH = "House numbers — "

    // --- 1.4 using a file
    fun fromFile(file: String, snapshot: String) = "From $file, OpenStreetMap data of ${date(snapshot)}."
    fun fileNeeds(need: Long, free: Long) = "Needs ${space(need)} free. Free now: ${space(free)}."
    const val INSTALL = "Install"
    const val KEEP_OPEN_INSTALL = "Keep Beamline open until the install has finished."

    // --- 1.5 the map chip
    const val CHIP_CHOOSE = "Choose your state"
    fun chipDownloading(name: String, p: Int) = "Downloading $name · $p %"
    fun chipWaitingWifi(name: String) = "Waiting for Wi-Fi · $name"
    fun chipPaused(name: String) = "Download paused · $name"
    fun chipInstalling(name: String) = "Installing $name…"
    fun chipFailed(name: String) = "$name wasn't installed"
    fun chipDescription(text: String) = "$text. Opens States"
    /** Item 40: an installed state has newer map data (the daily check). */
    fun chipUpdates(names: List<String>) = if (names.size == 1) "${names[0]}: new map data" else "New map data for ${names.size} states"

    // --- 1.1 step 7 notifications
    const val CHANNEL = "Downloads"
    fun readyNotification(name: String) = "$name is ready"
    const val TAP_TO_OPEN_MAP = "Tap to open the map."
    fun pausedPercent(p: Int) = "Download paused · $p %"
    const val OPEN_TO_CONTINUE = "Open Beamline to continue."
    const val NO_SPACE_NOTIFICATION = "Not enough space. Open Beamline to see what's needed."
    const val UNREACHABLE_NOTIFICATION = "Couldn't reach the download server. Open Beamline to continue."

    /** Audit F8: why a download did not start (StartResult.Refused). */
    fun refused(why: io.github.spysyweeb.beamline.download.StartRefusal): String = when (why) {
        io.github.spysyweeb.beamline.download.StartRefusal.ANOTHER_JOB -> "Another state is being downloaded or installed. Try again when it has finished."
        io.github.spysyweeb.beamline.download.StartRefusal.NEEDS_NEWER_APP -> "This state needs a newer version of Beamline. Update the app first."
        io.github.spysyweeb.beamline.download.StartRefusal.NOT_SUPPORTED -> "This can't be added to the installed version. Update the state first."
        io.github.spysyweeb.beamline.download.StartRefusal.DOWNLOADS_OFF -> DOWNLOADS_OFF
    }
}
