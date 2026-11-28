package io.github.spysyweeb.beamline.app.online

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException

/** Where an outbox item is (docs/online-design.md 4.5). */
enum class OutboxState(val key: String) {
    /** Confirmed by the user's Send; goes out when the connection is there and the app is open. */
    WAITING("waiting"),
    /** On OSM: [OutboxItem.noteId] is its number. */
    SENT("sent"),
    /** OSM answered with an error that sending again will not change ([OutboxItem.status]); it can be changed and sent again. */
    REFUSED("refused"),
    /**
     * Batch O2, an edit only: the place changed on OSM after the edit was made (found when it was
     * about to go, or OSM answered 409): it waits for the user to look at the new values again (4.2, 4.5).
     */
    REVIEW("review"),
}

/**
 * Batch O2 (docs/online-design.md 4.2): what an edit changes. [osm] is the object ("n123");
 * [baseVersion] the version the form read from OSM (null: the form showed the pack's values, offline);
 * [base] the edited keys' values the form started from; [changes] key to new value (null: removed).
 * After it went: [changeset] and the element's [newVersion]. [inPack]: an installed pack already has
 * the change, so the place sheet no longer marks it (4.5). [toString] prints none of the values (PV2).
 */
data class EditData(
    val osm: String,
    val baseVersion: Int?,
    val base: Map<String, String>,
    val changes: Map<String, String?>,
    val comment: String,
    val changeset: Long? = null,
    val newVersion: Int? = null,
    val inPack: Boolean = false,
    /**
     * Batch O3 (docs/next-round.md item 25): "This is now a different business" / "My place replaced it":
     * the new business's tags. At send time the changes are made again from the place as OSM has it then
     * ([BusinessTags.replace]), so an old business tag added meanwhile goes too.
     */
    val replaceWith: Map<String, String>? = null,
    /** Batch O3: the OSM object of the pack's place (the map tiles' feature; differs from [osm] only for test objects). */
    val packOsm: String? = null,
    /** Batch O3: the sprite icon of the place as the sheet showed it (the own-changes layer draws a renamed place with it). */
    val icon: String? = null,
    /** Batch O3: the place's type (PlaceTypes code) as the form saw its tags ([base] keeps only the changed keys). */
    val typeCode: String? = null,
    /** Item 32: the edit is of a road (its speed limit), not of a place: the own-changes layer shows nothing for it. */
    val road: Boolean = false,
    /** 0.1.41 (53a): the node's own point as the form (or the pack) had it; null: a way, or none known (no move check). Public OSM data, never the phone's. */
    val nodeLat: Double? = null,
    val nodeLon: Double? = null,
    /**
     * 0.1.41 (53d): what the upload really changed, set once it went: per key, the value just before ([sentBefore], null:
     * none) and after ([sentAfter], null: removed). Empty maps: OSM already had every value, nothing was uploaded.
     */
    val sentBefore: Map<String, String?>? = null,
    val sentAfter: Map<String, String?>? = null,
    /** 0.1.41 (53d): this item undoes the sent item [undoOf] ([base]: the values the edit wrote; [changes]: the ones before). */
    val undoOf: String? = null,
    /** 0.1.41 (53d): an undo of this item went (the own-changes layer and Undo leave it out). */
    val undone: Boolean = false,
    /** 0.1.41 (53d), on an undo: the keys left as someone changed them since. */
    val kept: List<String> = emptyList(),
    /** 0.1.41 (53c): a confirmation of this key's value ("opening_hours"): it writes check_date:<key> only. */
    val confirm: String? = null,
    /**
     * 0.1.41 (53a, plan OP-2): for a closing or a different business, the place's kind tags when the edit was made
     * ([EditPlans.kindOf]); on an undo of one, the kind the edit left. It goes only while the place is still of that kind.
     */
    val kind: Map<String, String>? = null,
    /**
     * The fixer's review: an upload about to go (kept just before its PUT, while the item still waits): when a later pass
     * finds the element last changed in [Going.changeset], OSM applied it though its reply was lost, and it counts as sent.
     */
    val going: Going? = null,
) {
    override fun toString(): String = "EditData(${changes.size} changes)"
}

/**
 * The fixer's review: an upload of an edit or an undo on its way: the changeset it goes in, per key the value before it
 * and the one it writes, and (an undo) the keys left as changed since. [toString] prints none of it (PV2).
 */
data class Going(val changeset: Long, val before: Map<String, String?>, val after: Map<String, String?>, val kept: List<String> = emptyList()) {
    override fun toString(): String = "Going(${after.size} changes)"
}

/**
 * Batch O3 (docs/online-design.md 4.3): a new place. [typeCode] its type ([PlaceTypes]), [tags] the new
 * node's tags, [comment] the changeset's ("Added restaurant Joe's"), [decided] the OSM objects the user
 * already answered about (an "It's a different place" to a duplicate, "Both are here"): the check at send
 * time leaves them out. After it went: the new [node] and its [changeset]. [toString] prints none of it (PV2).
 */
data class AddData(
    val typeCode: String,
    val tags: Map<String, String>,
    val comment: String,
    val decided: Set<String> = emptySet(),
    val node: Long? = null,
    val changeset: Long? = null,
) {
    override fun toString(): String = "AddData(${tags.size} tags)"
}

/**
 * One note in the outbox. Its text and point are the user's (rule PV2 of
 * docs/architecture-saved-places.md): [toString] prints neither.
 */
data class OutboxItem(
    val id: String,
    val lat: Double,
    val lon: Double,
    /** What the user wrote ([NoteText.compose]); the "via Beamline" line is added when it is sent. */
    val text: String,
    /** Where the note was made from: the place's name, or null for a point on the map. Shown in "My reports" only. */
    val placeName: String?,
    val createdMs: Long,
    val state: OutboxState = OutboxState.WAITING,
    val noteId: Long? = null,
    /** The link to the sent note on the server it went to. */
    val link: String? = null,
    /** The error status of a refused note. */
    val status: Int? = null,
    /** Batch O2: "Post with my OSM name" was checked (sent with the account's token when signed in). */
    val withName: Boolean = false,
    /** Batch O2: an edit of a place (then [text] is its summary), or null for a note. */
    val edit: EditData? = null,
    /** Batch O3: a new place (then [text] is its summary), or null. */
    val add: AddData? = null,
    /** Batch O3: when it went to OSM (the own-changes overlay steps aside for a pack made after it). */
    val sentMs: Long? = null,
    /**
     * Item 32 (docs/online-design.md 4.7): a speed limit fix: the limit in miles an hour. With no [edit]
     * yet it is a limit picked while driving, kept ([OutboxState.REVIEW]) until the driver has stopped and
     * confirms it; the confirmed fix carries its [edit] of the road.
     */
    val speedMph: Int? = null,
    /**
     * 0.1.41 (53e, docs/online-design.md 4.5): the edit was made at the place: one of the phone's positions of the 10
     * minutes before Upload was within about 80 m of it ([RecentPositions]), the form was opened from a question at the
     * place, or an item made there went again with its values ([UploadChecks.atPlace]). Its changeset then carries
     * source=survey. Only this yes or no is kept, never a position.
     */
    val atPlace: Boolean = false,
) {
    val isEdit: Boolean get() = edit != null
    val isAdd: Boolean get() = add != null
    val isSpeed: Boolean get() = speedMph != null

    /** It can still be opened, changed or deleted (until it is sent). */
    val editable: Boolean get() = state != OutboxState.SENT

    override fun toString(): String = "OutboxItem(state=$state)"
}

/**
 * The outbox (docs/online-design.md 4.5): `osm-outbox.json` in the no-backup folder beside the saved
 * places, under the same rules PV1-PV8 (never logged, never in a backup, written only there and to
 * OSM when the user sent it). Written atomically; an unreadable file is kept aside as
 * `osm-outbox.json.broken` and the outbox starts empty. Plain Kotlin; nothing here logs.
 */
class OutboxStore(private val file: File) {
    private val _items = MutableStateFlow(load())
    /** Newest first. */
    val items: StateFlow<List<OutboxItem>> = _items.asStateFlow()

    fun byId(id: String): OutboxItem? = _items.value.firstOrNull { it.id == id }

    /**
     * Adds [item], unless it is a note the outbox already holds: the same words at the same point (to the
     * sixth decimal), still waiting or sent within [SAME_NOTE_MS] (a second tap on Send, or the same
     * report made again right away, must not put two notes on the map; the audit of 2026-11-21). An edit
     * or a new place is never held back here (their own sessions check for a change already made).
     * False when it was held back (the caller says so instead of "sending").
     */
    @Synchronized
    fun add(item: OutboxItem): Boolean {
        if (item.edit == null && item.add == null && item.speedMph == null && _items.value.any { sameNote(it, item) }) return false
        write(listOf(item) + _items.value.filter { it.id != item.id })
        return true
    }

    private fun sameNote(old: OutboxItem, new: OutboxItem): Boolean =
        old.id != new.id && old.edit == null && old.add == null && old.speedMph == null && old.text.trim() == new.text.trim() &&
            Math.round(old.lat * 1e6) == Math.round(new.lat * 1e6) && Math.round(old.lon * 1e6) == Math.round(new.lon * 1e6) &&
            (old.state == OutboxState.WAITING || (old.state == OutboxState.SENT && new.createdMs - (old.sentMs ?: old.createdMs) in 0..SAME_NOTE_MS))

    /** Changes a waiting or refused item's text; it waits to be sent again. False when it was sent in the meantime. */
    @Synchronized
    fun change(id: String, text: String): Boolean {
        val old = byId(id) ?: return false
        if (!old.editable) return false
        if (old.speedMph != null && old.edit == null) return false   // a limit picked while driving is no note to change and send
        write(_items.value.map { if (it.id == id) it.copy(text = text, state = OutboxState.WAITING, status = null) else it })
        return true
    }

    @Synchronized
    fun delete(id: String) = write(_items.value.filter { it.id != id })

    @Synchronized
    internal fun markSent(id: String, noteId: Long, link: String, nowMs: Long = System.currentTimeMillis()) =
        write(_items.value.map { if (it.id == id) it.copy(state = OutboxState.SENT, noteId = noteId, link = link, status = null, sentMs = nowMs) else it })

    @Synchronized
    internal fun markAddSent(id: String, node: Long, changeset: Long, link: String, nowMs: Long = System.currentTimeMillis()) = write(
        _items.value.map {
            if (it.id == id && it.add != null) it.copy(state = OutboxState.SENT, link = link, status = null, sentMs = nowMs, add = it.add.copy(node = node, changeset = changeset)) else it
        },
    )

    @Synchronized
    internal fun markRefused(id: String, status: Int) =
        write(_items.value.map { if (it.id == id) it.copy(state = OutboxState.REFUSED, status = status) else it })

    /** 53d: [before] and [after] are what the upload really changed, per key (kept for Undo). */
    @Synchronized
    internal fun markEditSent(
        id: String, changeset: Long, newVersion: Int?, link: String,
        before: Map<String, String?>, after: Map<String, String?>, nowMs: Long = System.currentTimeMillis(),
    ) = write(
        _items.value.map {
            if (it.id == id && it.edit != null) it.copy(state = OutboxState.SENT, link = link, status = null, sentMs = nowMs,
                edit = it.edit.copy(changeset = changeset, newVersion = newVersion, sentBefore = before, sentAfter = after, going = null)) else it
        },
    )

    /** The fixer's review: just before a PUT, what goes and in which changeset (the item still waits; see [EditData.going]). */
    @Synchronized
    internal fun markGoing(id: String, going: Going) = write(
        _items.value.map { if (it.id == id && it.edit != null) it.copy(edit = it.edit.copy(going = going)) else it },
    )

    /** 53a: sent: OSM already has every value of the edit; nothing was uploaded ([link]: the place on OSM). */
    @Synchronized
    internal fun markEditAlready(id: String, link: String, nowMs: Long = System.currentTimeMillis()) = write(
        _items.value.map {
            if (it.id == id && it.edit != null) it.copy(state = OutboxState.SENT, link = link, status = null, sentMs = nowMs,
                edit = it.edit.copy(changeset = null, sentBefore = emptyMap(), sentAfter = emptyMap(), going = null)) else it
        },
    )

    /**
     * 53d: the undo [id] is done: [changeset] null when nothing was uploaded ([status] 410: the place was deleted).
     * When it uploaded, the item it undoes is marked [EditData.undone] in the same write.
     */
    @Synchronized
    internal fun markUndoDone(
        id: String, changeset: Long?, newVersion: Int?, link: String?, kept: List<String>, status: Int?,
        before: Map<String, String?>, after: Map<String, String?>, nowMs: Long = System.currentTimeMillis(),
    ) {
        val of = byId(id)?.edit?.undoOf
        write(
            _items.value.map {
                when {
                    it.id == id && it.edit != null -> it.copy(state = OutboxState.SENT, link = link, status = status, sentMs = nowMs,
                        edit = it.edit.copy(changeset = changeset, newVersion = newVersion, kept = kept, sentBefore = before, sentAfter = after, going = null))
                    changeset != null && of != null && it.id == of && it.edit != null -> it.copy(edit = it.edit.copy(undone = true))
                    else -> it
                }
            },
        )
    }

    @Synchronized
    internal fun markReview(id: String, status: Int?) =
        write(_items.value.map { if (it.id == id) it.copy(state = OutboxState.REVIEW, status = status) else it })

    /** An installed pack has the sent edit [id] (4.5): the place sheet stops marking it. */
    @Synchronized
    fun markInPack(id: String) = write(
        _items.value.map { if (it.id == id && it.edit != null && !it.edit.inPack) it.copy(edit = it.edit.copy(inPack = true)) else it },
    )

    /** The sent edits of [osm] still waiting for a pack that has them, newest first (4.5). */
    fun pendingEdits(osm: String): List<OutboxItem> =
        _items.value.filter { it.state == OutboxState.SENT && it.edit?.osm == osm && !it.edit.inPack }

    private fun write(next: List<OutboxItem>) {
        save(next)
        _items.value = next
    }

    private fun load(): List<OutboxItem> {
        if (!file.isFile) return emptyList()
        return try {
            val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
            (root["items"] as JsonArray).mapNotNull { e -> (e as? JsonObject)?.let(::read) }
        } catch (_: Exception) {
            file.renameTo(File(file.parentFile, file.name + ".broken"))
            emptyList()
        }
    }

    private fun read(o: JsonObject): OutboxItem? {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
        fun l(k: String) = (o[k] as? JsonPrimitive)?.longOrNull
        val id = s("id") ?: return null
        val lat = d("lat") ?: return null
        val lon = d("lon") ?: return null
        val text = s("text") ?: return null
        val state = OutboxState.entries.firstOrNull { it.key == s("state") } ?: OutboxState.WAITING
        val edit = if (s("kind") == "edit") readEdit(o["edit"] as? JsonObject ?: return null) ?: return null else null
        val add = if (s("kind") == "add") readAdd(o["add"] as? JsonObject ?: return null) ?: return null else null
        return OutboxItem(
            id, lat, lon, text, s("place"), l("created_ms") ?: 0L, state, l("note_id"), s("link"),
            (o["status"] as? JsonPrimitive)?.intOrNull, (o["with_name"] as? JsonPrimitive)?.booleanOrNull ?: false, edit, add, l("sent_ms"),
            (o["speed_mph"] as? JsonPrimitive)?.intOrNull,
            (o["at_place"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun readEdit(o: JsonObject): EditData? {
        fun str(e: Any?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val osm = str(o["osm"]) ?: return null
        val base = (o["base"] as? JsonObject)?.mapNotNull { (k, v) -> str(v)?.let { k to it } }?.toMap() ?: return null
        val changes = (o["changes"] as? JsonObject)?.map { (k, v) -> k to str(v) }?.toMap() ?: return null
        val nodeAt = (o["node_at"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 }
        return EditData(
            osm, (o["base_version"] as? JsonPrimitive)?.intOrNull, base, changes, str(o["comment"]) ?: "",
            (o["changeset"] as? JsonPrimitive)?.longOrNull, (o["new_version"] as? JsonPrimitive)?.intOrNull,
            (o["in_pack"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (o["replace_with"] as? JsonObject)?.mapNotNull { (k, v) -> str(v)?.let { k to it } }?.toMap(),
            str(o["pack_osm"]), str(o["icon"]), str(o["type"]), (o["road"] as? JsonPrimitive)?.booleanOrNull ?: false,
            // 0.1.41: each of these is optional (absent in a 0.1.40 file: the defaults)
            nodeLat = nodeAt?.get(0), nodeLon = nodeAt?.get(1),
            sentBefore = (o["sent_before"] as? JsonObject)?.map { (k, v) -> k to str(v) }?.toMap(),
            sentAfter = (o["sent_after"] as? JsonObject)?.map { (k, v) -> k to str(v) }?.toMap(),
            undoOf = str(o["undo_of"]),
            undone = (o["undone"] as? JsonPrimitive)?.booleanOrNull ?: false,
            kept = (o["kept"] as? JsonArray)?.mapNotNull { str(it) } ?: emptyList(),
            confirm = str(o["confirm"]),
            kind = (o["kind"] as? JsonObject)?.mapNotNull { (k, v) -> str(v)?.let { k to it } }?.toMap(),
            going = (o["going"] as? JsonObject)?.let { g ->
                val cs = (g["changeset"] as? JsonPrimitive)?.longOrNull
                val before = (g["before"] as? JsonObject)?.map { (k, v) -> k to str(v) }?.toMap()
                val after = (g["after"] as? JsonObject)?.map { (k, v) -> k to str(v) }?.toMap()
                if (cs != null && before != null && after != null) Going(cs, before, after, (g["kept"] as? JsonArray)?.mapNotNull { str(it) } ?: emptyList()) else null
            },
        )
    }

    private fun readAdd(o: JsonObject): AddData? {
        fun str(e: Any?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val type = str(o["type"]) ?: return null
        val tags = (o["tags"] as? JsonObject)?.mapNotNull { (k, v) -> str(v)?.let { k to it } }?.toMap() ?: return null
        return AddData(
            type, tags, str(o["comment"]) ?: "", (o["decided"] as? JsonArray)?.mapNotNull { str(it) }?.toSet() ?: emptySet(),
            (o["node"] as? JsonPrimitive)?.longOrNull, (o["changeset"] as? JsonPrimitive)?.longOrNull,
        )
    }

    private fun save(items: List<OutboxItem>) {
        val array = JsonArray(
            items.map { i ->
                JsonObject(
                    buildMap {
                        put("id", JsonPrimitive(i.id))
                        put("kind", JsonPrimitive(if (i.edit != null) "edit" else if (i.add != null) "add" else "note"))
                        put("lat", JsonPrimitive(i.lat))
                        put("lon", JsonPrimitive(i.lon))
                        put("text", JsonPrimitive(i.text))
                        i.placeName?.let { put("place", JsonPrimitive(it)) }
                        put("created_ms", JsonPrimitive(i.createdMs))
                        put("state", JsonPrimitive(i.state.key))
                        i.noteId?.let { put("note_id", JsonPrimitive(it)) }
                        i.link?.let { put("link", JsonPrimitive(it)) }
                        i.status?.let { put("status", JsonPrimitive(it)) }
                        if (i.withName) put("with_name", JsonPrimitive(true))
                        i.sentMs?.let { put("sent_ms", JsonPrimitive(it)) }
                        i.speedMph?.let { put("speed_mph", JsonPrimitive(it)) }
                        if (i.atPlace) put("at_place", JsonPrimitive(true))
                        i.add?.let { a ->
                            put(
                                "add",
                                JsonObject(
                                    buildMap {
                                        put("type", JsonPrimitive(a.typeCode))
                                        put("tags", JsonObject(a.tags.mapValues { JsonPrimitive(it.value) }))
                                        put("comment", JsonPrimitive(a.comment))
                                        if (a.decided.isNotEmpty()) put("decided", JsonArray(a.decided.sorted().map { JsonPrimitive(it) }))
                                        a.node?.let { put("node", JsonPrimitive(it)) }
                                        a.changeset?.let { put("changeset", JsonPrimitive(it)) }
                                    },
                                ),
                            )
                        }
                        i.edit?.let { e ->
                            put(
                                "edit",
                                JsonObject(
                                    buildMap {
                                        put("osm", JsonPrimitive(e.osm))
                                        e.baseVersion?.let { put("base_version", JsonPrimitive(it)) }
                                        put("base", JsonObject(e.base.mapValues { JsonPrimitive(it.value) }))
                                        put("changes", JsonObject(e.changes.mapValues { JsonPrimitive(it.value) }))
                                        put("comment", JsonPrimitive(e.comment))
                                        e.changeset?.let { put("changeset", JsonPrimitive(it)) }
                                        e.newVersion?.let { put("new_version", JsonPrimitive(it)) }
                                        if (e.inPack) put("in_pack", JsonPrimitive(true))
                                        e.replaceWith?.let { r -> put("replace_with", JsonObject(r.mapValues { JsonPrimitive(it.value) })) }
                                        e.packOsm?.let { put("pack_osm", JsonPrimitive(it)) }
                                        e.icon?.let { put("icon", JsonPrimitive(it)) }
                                        e.typeCode?.let { put("type", JsonPrimitive(it)) }
                                        if (e.road) put("road", JsonPrimitive(true))
                                        // 0.1.41: each only when set
                                        if (e.nodeLat != null && e.nodeLon != null) put("node_at", JsonArray(listOf(JsonPrimitive(e.nodeLat), JsonPrimitive(e.nodeLon))))
                                        e.sentBefore?.let { m -> put("sent_before", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
                                        e.sentAfter?.let { m -> put("sent_after", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
                                        e.undoOf?.let { put("undo_of", JsonPrimitive(it)) }
                                        if (e.undone) put("undone", JsonPrimitive(true))
                                        if (e.kept.isNotEmpty()) put("kept", JsonArray(e.kept.map { JsonPrimitive(it) }))
                                        e.confirm?.let { put("confirm", JsonPrimitive(it)) }
                                        e.kind?.let { m -> put("kind", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
                                        e.going?.let { g ->
                                            put(
                                                "going",
                                                JsonObject(
                                                    buildMap {
                                                        put("changeset", JsonPrimitive(g.changeset))
                                                        put("before", JsonObject(g.before.mapValues { JsonPrimitive(it.value) }))
                                                        put("after", JsonObject(g.after.mapValues { JsonPrimitive(it.value) }))
                                                        if (g.kept.isNotEmpty()) put("kept", JsonArray(g.kept.map { JsonPrimitive(it) }))
                                                    },
                                                ),
                                            )
                                        }
                                    },
                                ),
                            )
                        }
                    },
                )
            },
        )
        val text = JsonObject(mapOf("format" to JsonPrimitive("beamline-outbox/1"), "items" to array)).toString()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("cannot write the outbox")
        }
    }

    companion object {
        const val FILE_NAME = "osm-outbox.json"
        /** A note with the same words at the same point is not added again within this long of the first being sent. */
        const val SAME_NOTE_MS = 10 * 60_000L
    }
}

/** What one pass of [OutboxSender.sendWaiting] did. [review]: edits that wait for the user to look again. */
data class SendResult(val sent: Int, val refused: Int, val left: Int, val offline: Boolean, val review: Int = 0)

/**
 * Sends the outbox's waiting items, oldest first, one at a time (docs/online-design.md 4.5): after the
 * user's Send or Upload, and when the app comes to the front or a network appears while it is open. No
 * background service: nothing calls it while the app is closed. [http] refuses everything while the
 * switch is off, so then nothing leaves (N4). A network error stops the pass and the items wait.
 *
 * Batch O2: a note marked "Post with my OSM name" goes with the account's token when signed in
 * ([account]), else anonymously (less is sent, never more). An edit needs the account: signed out it
 * waits. Before an edit goes the place is read again and the edit checked tag by tag (0.1.41, 53a,
 * [EditPlans]): it goes onto the newest version unless one of its own tags now holds a third value, the
 * node was moved far or the place deleted (then it waits for the user, [OutboxState.REVIEW]); what OSM
 * already has is not uploaded again. One changeset (comment, `created_by=Beamline <version>`, and
 * `source=survey` for an edit made at the place, 53e), the element, close; OSM's 409 is read again once.
 * An undo (53d) goes by [Undo.plan].
 */
class OutboxSender(
    private val store: OutboxStore,
    private val http: OsmHttp,
    private val server: OsmServer,
    private val allowed: () -> Boolean,
    private val io: CoroutineDispatcher,
    private val account: () -> OsmAccount? = { null },
) {
    private val mutex = Mutex()
    private val _sending = MutableStateFlow<String?>(null)
    /** The id of the item going out now (it cannot be changed meanwhile). */
    val sending: StateFlow<String?> = _sending.asStateFlow()

    /**
     * One pass. [known]: the element the edit form has just read for the item [knownFor] (its Upload
     * right after the summary): uploaded as read, without reading it again, so a change made on OSM
     * in between is OSM's 409.
     */
    suspend fun sendWaiting(knownFor: String? = null, known: OsmElement? = null): SendResult = mutex.withLock {
        var sent = 0
        var refused = 0
        var review = 0
        var offline = false
        if (allowed()) {
            for (item in store.items.value.filter { it.state == OutboxState.WAITING }.sortedBy { it.createdMs }) {
                val now = store.byId(item.id)?.takeIf { it.state == OutboxState.WAITING } ?: continue
                if (now.speedMph != null && now.edit == null) continue   // a limit picked while driving is never a note: it waits for its confirmation
                val acc = account()
                if ((now.edit != null || now.add != null) && acc == null) continue   // an edit or a new place waits for the account
                _sending.value = now.id
                try {
                    if (now.add != null) {
                        when (withContext(io) { sendAdd(now, now.add, acc!!.token) }) {
                            true -> sent++
                            false -> review++
                        }
                    } else if (now.edit == null) {
                        val token = if (now.withName) acc?.token else null
                        val noteId = withContext(io) { http.createNote(now.lat, now.lon, NoteText.forSending(now.text), token) }
                        store.markSent(now.id, noteId, server.noteLink(noteId))
                        sent++
                    } else {
                        // 0.1.41 (53d): an undo has its own plan
                        val done = if (now.edit.undoOf != null) withContext(io) { sendUndo(now, now.edit, acc!!.token) }
                            else withContext(io) { sendEdit(now, now.edit, acc!!.token, if (knownFor == now.id) known else null) }
                        when (done) {
                            true -> sent++
                            false -> review++
                        }
                    }
                } catch (e: OsmError) {
                    if (e.final) {
                        store.markRefused(now.id, e.status)
                        refused++
                    } else {
                        offline = true
                        break
                    }
                } catch (_: OnlineRefused) {
                    break
                } catch (_: IOException) {
                    offline = true
                    break
                } finally {
                    _sending.value = null
                }
            }
        }
        SendResult(sent, refused, store.items.value.count { it.state == OutboxState.WAITING }, offline, review)
    }

    /**
     * Batch O3 (4.3): a new place. OSM's map of the spot is read again and the duplicate check run
     * ([SpotCheck.duplicates], leaving out what the user already decided about): a new duplicate makes it
     * wait for the user (REVIEW). Else one changeset ("Added restaurant Joe's", `created_by`)
     * with one new node at the item's point (the pressed point). True: sent; false: REVIEW. Blocking.
     */
    private fun sendAdd(item: OutboxItem, a: AddData, token: String): Boolean {
        val type = PlaceTypes.byCode(a.typeCode) ?: throw OsmError(400)
        val around = SpotCheck.fromMap(http.map(SpotCheck.box(item.lat, item.lon)), item.lat, item.lon) ?: throw OsmError(200)
        if (SpotCheck.duplicates(type, a.tags["name"].orEmpty(), around, a.decided).isNotEmpty()) {
            store.markReview(item.id, null)
            return false
        }
        val changeset = http.openChangeset(token, PlaceEdits.changesetTags(a.comment, http.version, item.atPlace))
        try {
            val node = http.createNode(token, changeset, item.lat, item.lon, a.tags)
            store.markAddSent(item.id, node, changeset, server.elementLink(OsmRef(OsmType.NODE, node)))
            return true
        } finally {
            try {
                http.closeChangeset(token, changeset)
            } catch (_: Exception) {
                // OSM closes an idle changeset by itself after an hour
            }
        }
    }

    /**
     * 0.1.41 (53a): an edit, checked tag by tag on the place as OSM has it now ([EditPlans.plan]): uploaded onto the
     * newest version, or nothing when OSM already has every value, or back to the user (REVIEW: a third value, a node
     * moved far, or 410: deleted). A 409 (changed between the read and the upload) is read again and planned again once.
     * The changeset is opened only when something goes, with the comment of [EditPlans.sendComment] and source=survey
     * for an edit made at the place. True: done (sent, or OSM already had it); false: waits for the user. Blocking.
     * The fixer's review: just before the PUT the item keeps its changeset ([EditData.going]), so a pass after a lost
     * reply that finds the element last changed in it marks it sent (with its Undo), not "Already on OpenStreetMap".
     */
    private fun sendEdit(item: OutboxItem, e: EditData, token: String, known: OsmElement?): Boolean {
        val ref = OsmRef.parse(e.osm) ?: throw OsmError(400)
        var current = known?.takeIf { e.going == null && it.ref == ref && it.version == e.baseVersion } ?: readOrGone(ref) ?: return gone(item)
        // the fixer's review: an upload of an earlier pass that OSM applied though its reply was lost: it went
        e.going?.takeIf { it.changeset == current.changeset }?.let { g ->
            store.markEditSent(item.id, g.changeset, current.version, server.changesetLink(g.changeset), before = g.before, after = g.after)
            return true
        }
        var changeset: Long? = null
        try {
            for (attempt in 1..2) {
                val up = when (val p = EditPlans.plan(e, current)) {
                    is EditPlan.Review -> {
                        store.markReview(item.id, null)
                        return false
                    }
                    EditPlan.Already -> {
                        store.markEditAlready(item.id, server.elementLink(ref))
                        return true
                    }
                    is EditPlan.Upload -> p.changes
                }
                val cs = changeset ?: http.openChangeset(token, PlaceEdits.changesetTags(EditPlans.sendComment(item, current), http.version, item.atPlace))
                    .also { changeset = it }
                val before = up.keys.associateWith { current.tags[it] }
                // kept first: a reply lost after OSM applied it is told by the element's changeset on a later pass
                store.markGoing(item.id, Going(cs, before, up))
                try {
                    val v = http.putElement(token, current.copy(tags = PlaceEdits.apply(current.tags, up)), cs)
                    store.markEditSent(item.id, cs, v, server.changesetLink(cs), before = before, after = up)
                    return true
                } catch (x: OsmError) {
                    if (x.status == 410) return gone(item)
                    if (x.status != 409) throw x
                    if (attempt == 2) {
                        store.markReview(item.id, 409)
                        return false
                    }
                    // changed between the read and the upload: read again and plan again on the newest version (53a)
                    current = readOrGone(ref) ?: return gone(item)
                }
            }
            error("not reached")
        } finally {
            changeset?.let { cs ->
                try {
                    http.closeChangeset(token, cs)
                } catch (_: Exception) {
                    // OSM closes an idle changeset by itself after an hour
                }
            }
        }
    }

    /**
     * 0.1.41 (53d): the undo [item] of a sent edit, checked at send time ([Undo.plan]): what still holds the edit's
     * value goes back, a tag someone changed since stays. No source (an undo restores, it reports nothing seen). Always
     * true (it is done, whatever it found; its row says what came of it); a second 409 in one pass is thrown on (final:
     * REFUSED(409)). Blocking.
     */
    private fun sendUndo(item: OutboxItem, u: EditData, token: String): Boolean {
        val ref = OsmRef.parse(u.osm) ?: throw OsmError(400)
        var current = readOrGone(ref) ?: return undoGone(item)
        // the fixer's review: an undo of an earlier pass that OSM applied though its reply was lost: it went
        u.going?.takeIf { it.changeset == current.changeset }?.let { g ->
            store.markUndoDone(item.id, g.changeset, current.version, server.changesetLink(g.changeset), g.kept, null, g.before, g.after)
            return true
        }
        var changeset: Long? = null
        try {
            for (attempt in 1..2) {
                val p = Undo.plan(u, current.tags)
                if (p.back.isEmpty()) {
                    store.markUndoDone(item.id, null, null, null, p.kept, null, emptyMap(), emptyMap())
                    return true
                }
                val cs = changeset ?: http.openChangeset(token, PlaceEdits.changesetTags(EditPlans.sendComment(item, current), http.version))
                    .also { changeset = it }
                val before = p.back.keys.associateWith { current.tags[it] }
                store.markGoing(item.id, Going(cs, before, p.back, p.kept))
                try {
                    val v = http.putElement(token, current.copy(tags = PlaceEdits.apply(current.tags, p.back)), cs)
                    store.markUndoDone(item.id, cs, v, server.changesetLink(cs), p.kept, null, before = before, after = p.back)
                    return true
                } catch (x: OsmError) {
                    if (x.status != 409 || attempt == 2) throw x
                    current = readOrGone(ref) ?: return undoGone(item)
                }
            }
            error("not reached")
        } finally {
            changeset?.let { cs ->
                try {
                    http.closeChangeset(token, cs)
                } catch (_: Exception) {
                    // OSM closes an idle changeset by itself after an hour
                }
            }
        }
    }

    /** The element, or null when OSM says it was deleted (410). 404 and every other error go on as before. */
    private fun readOrGone(ref: OsmRef): OsmElement? = try {
        http.element(ref)
    } catch (x: OsmError) {
        if (x.status == 410) null else throw x
    }

    /** 53a: the place was deleted: the edit waits for the user ([OutboxItem.status] 410). */
    private fun gone(item: OutboxItem): Boolean {
        store.markReview(item.id, 410)
        return false
    }

    /** 53d: the place of an undo was deleted: nothing went ([OutboxItem.status] 410). */
    private fun undoGone(item: OutboxItem): Boolean {
        store.markUndoDone(item.id, null, null, null, emptyList(), 410, emptyMap(), emptyMap())
        return true
    }
}
