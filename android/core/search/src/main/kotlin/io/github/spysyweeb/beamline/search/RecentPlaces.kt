// FILE: core/search/src/main/kotlin/io/github/spysyweeb/beamline/search/RecentPlaces.kt
package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.model.Place
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Recent places, newest first, in recents.json (format beamline-recents/1, written atomically).
 * Same place = same packId+searchId, or same name within 50 m. A broken file reads as empty.
 */
class RecentPlaces(file: File, max: Int = 30) {
    val places: StateFlow<List<Place>> get() = store.places
    fun add(place: Place): Unit = store.add(place)
    fun remove(place: Place): Unit = store.remove(place)
    fun clear(): Unit = store.clear()

    // Track S: the store behind the contract above (RecentsStore.kt), written on Dispatchers.IO (docs/architecture.md 1.2).
    private val store = RecentsStore(file, max, SearchAppRules.bundled().recentsSamePlaceM, LatestTextWriter.io)

    /** Tests: waits until the writes asked so far are on disk. */
    internal fun awaitWrites() = store.awaitWrites()
}
