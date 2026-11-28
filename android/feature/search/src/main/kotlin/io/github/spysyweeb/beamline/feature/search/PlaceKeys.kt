package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.model.Place

/**
 * F42 (0.1.40, P17): what makes a place window the same place, so its height is kept while its line fills in
 * (a dropped pin's street) or it is saved (its saved id): its source, point, name and pack entry. Not its
 * subtitle, not its saved id. Compose-free, so a JVM test loads it without the views' Compose statics.
 */
internal fun placeSheetKey(p: Place): List<Any?> = listOf(p.source, p.point, p.name, p.packId, p.searchId)
