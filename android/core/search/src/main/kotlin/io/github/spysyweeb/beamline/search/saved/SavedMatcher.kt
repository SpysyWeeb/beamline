package io.github.spysyweeb.beamline.search.saved

import io.github.spysyweeb.beamline.api.model.SavedPlace

/**
 * Rule SP20 (docs/architecture-saved-places.md 2.7): saved names that match typed text. With
 * q = name key of the text and n = name key of a saved name: rank 0 when n = q, rank 1 when n
 * starts with q, rank 2 when every token of q is a prefix of a different token of n (each token
 * of q, in order, takes the first token of n not yet taken that it is a prefix of). The name only,
 * never the place name or subtitle. Order: rank, kind (HOME, WORK, OTHER), n by code point, id;
 * at most search_max_rows. Nothing while q has fewer than search_min_chars code points.
 * packbuilder/search/saved_places.py matching() is the same in Python.
 */
internal object SavedMatcher {
    fun rank(q: String, n: String): Int? {
        if (n == q) return 0
        if (n.startsWith(q)) return 1
        val have = n.split(' ').filter { it.isNotEmpty() }
        val taken = BooleanArray(have.size)
        for (t in q.split(' ').filter { it.isNotEmpty() }) {
            val i = have.indices.firstOrNull { !taken[it] && have[it].startsWith(t) } ?: return null
            taken[i] = true
        }
        return 2
    }

    fun matching(places: List<SavedPlace>, typed: String, rules: SavedRules): List<SavedPlace> {
        val q = nameKeyOf(typed)
        if (q.codePointCount(0, q.length) < rules.searchMinChars || q.isEmpty()) return emptyList()
        data class Hit(val rank: Int, val key: String, val place: SavedPlace)
        return places.mapNotNull { p ->
            val n = nameKeyOf(p.name)
            rank(q, n)?.let { Hit(it, n, p) }
        }.sortedWith { a, b ->
            when {
                a.rank != b.rank -> a.rank.compareTo(b.rank)
                a.place.kind != b.place.kind -> a.place.kind.ordinal.compareTo(b.place.kind.ordinal)
                else -> compareCodePoints(a.key, b.key).takeIf { it != 0 } ?: a.place.id.compareTo(b.place.id)
            }
        }.take(rules.searchMaxRows).map { it.place }
    }
}
