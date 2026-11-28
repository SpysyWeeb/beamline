package io.github.spysyweeb.beamline.app.online

/** What the outbox does with an edit when it goes (0.1.41, docs/next-round.md item 53a). */
sealed interface EditPlan {
    /** Upload these changes onto the newest version: only the keys whose value is not there yet. [toString]: the count (PV2). */
    data class Upload(val changes: Map<String, String?>) : EditPlan {
        override fun toString(): String = "Upload(${changes.size})"
    }

    /** OSM already has every value of the edit: nothing is uploaded. */
    data object Already : EditPlan

    /** Back to the user (REVIEW): [moved] the node was moved far; else one of the edit's tags holds a third value, or the place is no longer the kind it was. */
    data class Review(val moved: Boolean) : EditPlan
}

/**
 * 0.1.41, 53a (docs/online-design.md 4.5, "Changed meanwhile, tag by tag"): each tag an edit changes keeps the value
 * it had when the edit was made ([EditData.base]; a key the edit adds is absent there, and absent counts as a value).
 * When the edit goes it is applied onto the place as OSM has it then, unless one of the SAME tags now holds a value
 * that is neither that old one nor the edit's own (`phone` and `contact:phone`, and the two website keys, count as
 * one detail), or the node was moved more than [MOVED_FAR_M] since the form read it. A closing or a different
 * business goes only while the place is still the kind of place it was ([kindOf], plan OP-2), as one unit: a closing
 * is made again on the tags of now (hours, phone and website added meanwhile go too), a replacement as
 * [BusinessTags.replace] on them. A confirmation of hours (53c) goes only while OSM still has the hours seen and the
 * place is still an open place of its kind (AP-4). Tags the edit does not touch keep whatever others gave them. A
 * check date is never a conflict, and a later one stays. Plain Kotlin; prints no values (PV2).
 */
object EditPlans {
    /** A node moved farther than this since the form read it is a conflict too (open question 2: 50 m). */
    const val MOVED_FAR_M = 50.0

    fun isCheckDate(key: String): Boolean = key == "check_date" || key.startsWith("check_date:")

    /** OP-2: the tags that say what kind of place it is: a main key ([PlaceEdits.MAIN_KEYS]) or its `disused:` or `was:` form. */
    fun isKindKey(k: String): Boolean = k in PlaceEdits.MAIN_KEYS ||
        (k.startsWith("disused:") && k.removePrefix("disused:") in PlaceEdits.MAIN_KEYS) ||
        (k.startsWith("was:") && k.removePrefix("was:") in PlaceEdits.MAIN_KEYS)

    /** OP-2: the kind tags of [tags]. */
    fun kindOf(tags: Map<String, String>): Map<String, String> = tags.filterKeys(::isKindKey)

    /** OP-3: the other key of the same detail. */
    private val SAME_DETAIL = mapOf("phone" to "contact:phone", "contact:phone" to "phone", "website" to "contact:website", "contact:website" to "website")

    /**
     * The keys of [changes] someone else changed meanwhile: [now]'s value is neither [old]'s nor the edit's. OP-3: for
     * a phone or website key, the other key of that detail counts too: it holds a value now that it did not have when
     * the edit was made ([old]) and that is not the edit's.
     */
    fun conflicts(now: Map<String, String>, old: Map<String, String>, changes: Map<String, String?>): Set<String> =
        changes.keys.filterTo(LinkedHashSet()) { k ->
            if (isCheckDate(k)) return@filterTo false
            if (now[k] != old[k] && now[k] != changes[k]) return@filterTo true
            val other = SAME_DETAIL[k]?.takeIf { it !in changes } ?: return@filterTo false
            now[other] != null && now[other] != old[other] && now[other] != changes[k]
        }

    /** The changes still to make on [now]: a value already there is left out; a check date only when ours is later. */
    fun toUpload(now: Map<String, String>, changes: Map<String, String?>): Map<String, String?> =
        changes.filterTo(LinkedHashMap()) { (k, v) -> now[k] != v && !(isCheckDate(k) && v != null && (now[k] ?: "") >= v) }

    private const val CHECK_PREFIX = "check_date:"

    /**
     * The fixer's review (docs/online-design.md 4.5, AP-5 at send time): a check date goes with its own key. The removal
     * of `check_date:<key>` in [up] goes only while `<key>` itself goes (else it would take away the date someone gave the
     * value OSM has now); and when the hours go and OSM has a check date for them that the edit does not set itself, it is
     * removed too: it dated the old hours (an edit made offline, or a date added after the form read the place).
     */
    fun withCheckDates(now: Map<String, String>, up: Map<String, String?>, changes: Map<String, String?>): Map<String, String?> {
        val out = LinkedHashMap(up)
        for ((k, v) in up) if (v == null && k.startsWith(CHECK_PREFIX) && k.removePrefix(CHECK_PREFIX) !in up) out.remove(k)
        if (HoursCheck.KEY in up && PlaceEdits.HOURS_CHECK_DATE in now && changes[PlaceEdits.HOURS_CHECK_DATE] == null) out[PlaceEdits.HOURS_CHECK_DATE] = null
        return out
    }

    /**
     * The fixer's review (OP-3, "count as one detail"): a phone or website the edit adds that OSM now has, with the same
     * value, under the detail's other key (and not under its own) is already there: no second tag with it.
     */
    fun withoutSameDetail(now: Map<String, String>, up: Map<String, String?>, changes: Map<String, String?>): Map<String, String?> =
        up.filterTo(LinkedHashMap()) { (k, v) ->
            val other = SAME_DETAIL[k]
            v == null || other == null || other in changes || k in now || now[other] != v
        }

    /** The node was moved more than [MOVED_FAR_M] since the form read it (a way, or no point kept: never). */
    fun movedFar(e: EditData, current: OsmElement): Boolean {
        if (current.ref.type != OsmType.NODE) return false
        val lat = e.nodeLat ?: return false
        val lon = e.nodeLon ?: return false
        return SpotCheck.metres(lat, lon, current.lat ?: return false, current.lon ?: return false) > MOVED_FAR_M
    }

    /** "Closed permanently" (its `disused:` moves; a replacement is no closing). */
    fun isClosing(e: EditData): Boolean = e.replaceWith == null && e.undoOf == null && e.changes.any { (k, v) -> k.startsWith("disused:") && v != null }

    /** A closing or a different business: the kind tags go as one unit (OP-2). */
    fun isSwap(e: EditData): Boolean = e.replaceWith != null || isClosing(e)

    /** AP-4: the place is no open place of a kind (no main key, or a closed or former one). */
    private fun noOpenKind(tags: Map<String, String>): Boolean =
        PlaceEdits.MAIN_KEYS.none { it in tags } || tags.keys.any { isKindKey(it) && it !in PlaceEdits.MAIN_KEYS }

    fun plan(e: EditData, current: OsmElement): EditPlan {
        val wanted = when {
            // a new business in an old place is made again on the tags as they are now (item 25)
            e.replaceWith != null -> BusinessTags.replace(current.tags, e.replaceWith)
            // a closing too: hours, phone and website added meanwhile go with it (OP-2)
            isClosing(e) -> PlaceEdits.closedChanges(current.tags)
            else -> e.changes
        }
        val swap = isSwap(e)
        // a closing or a replacement is made again on the tags of now (their check dates go with the old business)
        val up = toUpload(current.tags, wanted).let { if (swap) it else withoutSameDetail(current.tags, withCheckDates(current.tags, it, e.changes), e.changes) }
        val offlineReplacement = e.replaceWith != null && e.baseVersion == null
        val checked = when {
            offlineReplacement -> e.changes.filterKeys { it == "name" }
            // the rest of the old business goes anyway
            swap -> e.changes.filterKeys { it == "name" || isKindKey(it) }
            else -> e.changes
        }
        val conflicted = conflicts(current.tags, e.base, checked).isNotEmpty()
        // OSM already has every value of the edit (someone made the same change): nothing to upload, nothing to ask
        if (up.isEmpty() && !conflicted) return EditPlan.Already
        if (movedFar(e, current)) return EditPlan.Review(moved = true)
        // AP-4: a confirmation goes only to a place still open as its kind
        if (e.confirm != null && noOpenKind(current.tags)) return EditPlan.Review(moved = false)
        // OP-2: a closing or a new business only while the place is still the kind it was (an offline replacement knows only its name)
        if (swap && !offlineReplacement && kindOf(current.tags) != (e.kind ?: kindOf(e.base))) return EditPlan.Review(moved = false)
        if (conflicted) return EditPlan.Review(moved = false)
        return EditPlan.Upload(up)
    }

    /**
     * OP-1: the public comment, made at send time from the place as OSM has it then: a confirmation and an undo name
     * the element's own `name` (a saved place's label never goes public); every other edit keeps its comment. The
     * fixer's review: a confirmation of an element with no name keeps its own comment (made from the map's name, never
     * from [OutboxItem.placeName], which for a place with no name is its window's title); an undo of a road names it by
     * its `ref` when it has no name, as the limit's own comment did ([SpeedLimits.Road.name]).
     */
    fun sendComment(item: OutboxItem, current: OsmElement): String {
        val e = item.edit ?: return ""
        return when {
            e.confirm != null -> current.tags["name"]?.let(HoursCheck::comment) ?: e.comment
            e.undoOf != null -> OnlineTexts.undoComment(current.tags["name"] ?: current.tags["ref"]?.takeIf { e.road }, road = e.road)
            else -> e.comment
        }
    }
}

/**
 * 0.1.41, 53d (docs/online-design.md 4.5, "Undo"): the undo of a sent edit, for [WINDOW_MS] from My reports. The
 * undo is an outbox item of its own ([item]) that puts back, at send time, only what still holds the edit's value
 * ([plan]): a tag someone changed since stays as they made it. A closing or a different business is undone only
 * while the place is still as the edit left it ([EditData.kind], plan OP-2). Plain Kotlin; prints no values (PV2).
 */
object Undo {
    const val WINDOW_MS = 12 * 60 * 60_000L

    /** Per key the upload changed, the value just before it went (null: none); null when not known. */
    fun before(item: OutboxItem): Map<String, String?>? {
        val e = item.edit ?: return null
        e.sentBefore?.let { return it }
        // 0.1.40 kept no record: its base is exactly what the upload replaced, except an offline replacement (its changes were made again)
        if (e.replaceWith != null && e.baseVersion == null) return null
        return e.changes.keys.associateWith { e.base[it] }
    }

    /** Per key, the value the upload wrote (null: removed). */
    fun after(item: OutboxItem): Map<String, String?>? = item.edit?.let { it.sentAfter ?: if (before(item) == null) null else it.changes }

    /** My reports offers Undo on [item] now (the caller adds: the switch on, not guiding). */
    fun offered(item: OutboxItem, items: List<OutboxItem>, nowMs: Long): Boolean {
        val e = item.edit ?: return false
        val sent = item.sentMs ?: return false
        return item.state == OutboxState.SENT && e.undoOf == null && !e.undone && e.changeset != null &&
            nowMs - sent in 0 until WINDOW_MS && !before(item).isNullOrEmpty() && items.none { it.edit?.undoOf == item.id }
    }

    /**
     * The outbox item that undoes [of] (WAITING; no source: an undo restores, it reports nothing seen). Its comment is
     * made at send time from the element's name ([EditPlans.sendComment]); the one kept here never goes.
     */
    fun item(of: OutboxItem, id: String, nowMs: Long): OutboxItem? {
        val e = of.edit ?: return null
        val before = before(of)?.takeIf { it.isNotEmpty() } ?: return null
        val after = after(of) ?: return null
        // OP-2: a closing or a different business: the kind the edit left, which the undo needs to find
        val kind = if (EditPlans.isSwap(e)) PlaceEdits.apply(e.kind ?: EditPlans.kindOf(e.base), after.filterKeys(EditPlans::isKindKey)) else null
        return OutboxItem(
            id = id, lat = of.lat, lon = of.lon, text = lines(of).joinToString("\n"), placeName = of.placeName, createdMs = nowMs,
            edit = EditData(
                e.osm, e.newVersion, nonNull(after), before, OnlineTexts.undoComment(null, road = e.road),
                packOsm = e.packOsm, icon = e.icon, typeCode = e.typeCode, road = e.road, undoOf = of.id, kind = kind,
            ),
        )
    }

    /** What the undo puts back, in the summary's words: "Hours: Mon–Fri 8 AM–5 PM → Mon–Fri 8 AM–6 PM". */
    fun lines(of: OutboxItem): List<String> {
        val after = after(of) ?: return emptyList()
        return PlaceEdits.tagLines(nonNull(after), before(of) ?: return emptyList())
    }

    private fun nonNull(m: Map<String, String?>): Map<String, String> = buildMap { for ((k, v) in m) if (v != null) put(k, v) }

    /** At send time, on OSM's tags: [back] what still holds the edit's value goes back; [kept] the keys changed since. [toString]: counts only (OP-9, PV2). */
    data class Plan(val back: Map<String, String?>, val kept: List<String>) {
        override fun toString(): String = "Undo.Plan(${back.size} back, ${kept.size} kept)"
    }

    fun plan(u: EditData, now: Map<String, String>): Plan {
        // OP-2: a closing or a new business retagged since: nothing goes back
        if (u.kind != null && EditPlans.kindOf(now) != u.kind) return Plan(emptyMap(), u.changes.keys.toList())
        val back = LinkedHashMap<String, String?>()
        val kept = ArrayList<String>()
        for ((k, was) in u.changes) {
            when (now[k]) {
                was -> Unit                  // already as it was before the edit
                u.base[k] -> back[k] = was   // still the edit's value (absent in base: the edit removed it): put it back
                else -> kept += k            // changed by someone since: left as they made it
            }
        }
        return Plan(back, kept)
    }
}
