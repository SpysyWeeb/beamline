package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.download.net.PackHttp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.Locale
import javax.net.SocketFactory

/**
 * The OpenStreetMap server a build talks to (network rule N2, docs/architecture-downloads.md 4.1):
 * [TEST], OSM's development server, in debug builds and release builds made with test extras;
 * [LIVE], openstreetmap.org, only in the plain release. The hosts are fixed: no other server can be
 * made outside this file and its tests ([local] servers exist for the JVM tests only).
 */
class OsmServer internal constructor(
    /** The API's address, without a trailing slash. */
    val apiBase: String,
    /** Where a note can be seen in a browser ("<webBase>/note/<id>"). */
    val webBase: String,
    /**
     * Batch O2 (docs/online-design.md 4.4): OSM's OAuth 2 server, without a trailing slash. The sign-in
     * page "<authBase>/oauth2/authorize" opens in the browser; OsmHttp itself asks only its token and
     * revoke endpoints. On the test server the API's own host; for openstreetmap.org www.openstreetmap.org.
     */
    val authBase: String = apiBase,
    /** A loopback test server (JVM tests): its non-public address is allowed. */
    internal val local: Boolean = false,
) {
    /** The page of note [id] on this server's website. */
    fun noteLink(id: Long): String = "$webBase/note/$id"

    /** The page of changeset [id] on this server's website (batch O2: the link after an edit). */
    fun changesetLink(id: Long): String = "$webBase/changeset/$id"

    /** The page of an object on this server's website ("<webBase>/relation/123"). */
    fun elementLink(ref: OsmRef): String = "$webBase/${ref.type.word}/${ref.id}"

    val host: String get() = hostOf(apiBase)
    val authHost: String get() = hostOf(authBase)

    companion object {
        /** OSM's development server (docs/online-design.md 4.6): every development and test build. */
        val TEST = OsmServer("https://master.apis.dev.openstreetmap.org", "https://master.apis.dev.openstreetmap.org")
        /** openstreetmap.org: only the plain release build, and only with the user's switch on. */
        val LIVE = OsmServer("https://api.openstreetmap.org", "https://www.openstreetmap.org", "https://www.openstreetmap.org")
        /** The fixed hosts (N2): each server's API host and its sign-in host. */
        val HOSTS = setOf(TEST.host, TEST.authHost, LIVE.host, LIVE.authHost)

        private fun hostOf(base: String) = base.substringAfter("://").substringBefore('/').substringBefore(':')

        /** The build's server from BuildConfig.OSM_SERVER: "live" in the plain release, anything else the test server. */
        fun forBuild(name: String): OsmServer = if (name == "live") LIVE else TEST
    }
}

/** Refused before any connection: the user's switch for this feature is off (N4), or the rule refuses the address (N2). */
class OnlineRefused(message: String) : Exception(message)

/** The server answered with an error status; [status] only, never its words (N3, PV2). */
class OsmError(val status: Int) : IOException("an error status from OpenStreetMap") {
    /** 4xx other than 408 and 429: sending the same note again will not help. */
    val final: Boolean get() = status in 400..499 && status != 408 && status != 429
}

/**
 * The only code besides PackHttp that opens network connections (network rule N1): OSM's API for
 * notes (`POST /api/0.6/notes.json`, anonymous or, since batch O2, under the user's name), the
 * sign-in's token and revoke requests and the user's name (batch O2, 4.4), and the requests of an
 * edit (batch O2, 4.2): read one node or way, open a changeset, upload the element, close; since batch
 * O3 (4.3) OSM's map of a small box around the pressed point (the duplicate check) and a new node. Every
 * request first asks [allowed] (the user's "Contribute to OpenStreetMap", under the main online
 * switch) and is refused with [OnlineRefused] while it is false (N4). It sends only what docs/online-design.md
 * section 3 lists: the note's text and its point in the form body; an edit's element and changeset
 * tags; headers `User-Agent: Beamline/<version>`, `Accept`, `Accept-Encoding: identity`, with an
 * account `Authorization: Bearer <token>`, and the transport headers OkHttp adds. No cookies, no
 * cache, no redirects followed, no proxy, no identifiers.
 * Every address a host resolves to must be public (N2). On Android [socketFactory] tags the sockets
 * 0x0B1B (TaggingSocketFactory), so the device accounting tells them from PackHttp's (0x0B1A).
 * Nothing here logs; no exception carries the note's text, the point, a token, an edit or the server's answer (PV2).
 */
class OsmHttp(
    private val server: OsmServer,
    /** The app's version name: "Beamline/<version>" is the User-Agent. */
    version: String,
    private val allowed: () -> Boolean,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    val userAgent = "Beamline/$version"

    /** The app's version name, for a changeset's `created_by=Beamline <version>` (4.2). */
    val version: String = version

    init {
        require(server.local || (server.host in OsmServer.HOSTS && server.authHost in OsmServer.HOSTS)) { "not an OSM host" }
    }

    private val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cache(null)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .socketFactory(socketFactory)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val a = resolve(hostname)
                if (a.isEmpty()) throw java.net.UnknownHostException("no address")
                if (!server.local && a.any { !PackHttp.isPublic(it) }) throw IOException(REFUSED_ADDRESS)
                return a
            }
        })
        .build()

    /**
     * Posts a new note at ([lat], [lon]) with [text] (as sent: the "via Beamline" line already
     * added, [NoteText.forSending]); returns the note's number. With [token] (batch O2: "Post with my
     * OSM name") the note is posted under the signed-in user's name, without it anonymously.
     * Blocking: call on an IO thread. Throws [OnlineRefused] (switch off: nothing was sent),
     * [OsmError] (an error status) or another IOException (no network, timeout).
     */
    fun createNote(lat: Double, lon: Double, text: String, token: String? = null): Long {
        gate()
        val body = okhttp3.FormBody.Builder()
            .add("lat", coordinate(lat))
            .add("lon", coordinate(lon))
            .add("text", text)
            .build()
        val r = call(request(server.apiBase + NOTES_PATH, token).post(body))
        return noteId(r) ?: throw OsmError(200)
    }

    // ------------------------------------------------------------------ batch O2: sign-in (4.4)

    /**
     * The access token for the authorization [code] of a sign-in this app started (OAuth 2 with
     * PKCE, [verifier] the code_verifier of its S256 challenge): `POST <authBase>/oauth2/token`.
     * A public client: no secret. Never logged; no exception carries it.
     */
    fun token(clientId: String, code: String, verifier: String, redirectUri: String): String {
        gate()
        val body = okhttp3.FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .build()
        val r = call(request(server.authBase + TOKEN_PATH, null, json = true).post(body))
        return jsonString(r, "access_token") ?: throw OsmError(200)
    }

    /** Asks OSM to revoke [token] (sign-out, when online): `POST <authBase>/oauth2/revoke`. */
    fun revoke(clientId: String, token: String) {
        gate()
        val body = okhttp3.FormBody.Builder()
            .add("token", token)
            .add("token_type_hint", "access_token")
            .add("client_id", clientId)
            .build()
        call(request(server.authBase + REVOKE_PATH, null, json = true).post(body))
    }

    /** The signed-in user's display name (read_prefs): `GET /api/0.6/user/details.json`. */
    fun userName(token: String): String {
        gate()
        val r = call(request(server.apiBase + USER_PATH, token, json = true).get())
        return userNameOf(r) ?: throw OsmError(200)
    }

    // ------------------------------------------------------------------ batch O2: edits (4.2)

    /** The current version of a node or way: `GET /api/0.6/<node|way>/<id>.json` (no account needed). */
    fun element(ref: OsmRef): OsmElement {
        gate()
        if (ref.type == OsmType.RELATION) throw OsmError(400)   // relations are edited on openstreetmap.org
        val r = call(request(server.apiBase + "/api/0.6/${ref.type.word}/${ref.id}.json", null, json = true).get())
        return OsmElement.fromJson(r) ?: throw OsmError(200)
    }

    /** Opens a changeset with [tags] (comment, created_by, source): `PUT /api/0.6/changeset/create`; its number. */
    fun openChangeset(token: String, tags: Map<String, String>): Long {
        gate()
        val r = call(request(server.apiBase + "/api/0.6/changeset/create", token).put(xml(OsmXml.changeset(tags))))
        return r.trim().toLongOrNull() ?: throw OsmError(200)
    }

    /**
     * Uploads [element] (its id and the version it was read at) with its new tags in [changeset]:
     * `PUT /api/0.6/<node|way>/<id>`; the new version. 409 when the place changed since that version.
     */
    fun putElement(token: String, element: OsmElement, changeset: Long): Int {
        gate()
        val url = server.apiBase + "/api/0.6/${element.ref.type.word}/${element.ref.id}"
        val r = call(request(url, token).put(xml(OsmXml.element(element, changeset))))
        return r.trim().toIntOrNull() ?: throw OsmError(200)
    }

    /** Closes [changeset]: `PUT /api/0.6/changeset/<id>/close`. */
    fun closeChangeset(token: String, changeset: Long) {
        gate()
        call(request(server.apiBase + "/api/0.6/changeset/$changeset/close", token).put(EMPTY))
    }

    // ------------------------------------------------------------------ batch O3: adding a place (4.3)

    /**
     * OSM's objects in the box [wsen] (west, south, east, north; about 120 m across, [SpotCheck.box]):
     * `GET /api/0.6/map.json?bbox=..` (no account needed). The duplicate check and the "Already on the
     * map here" line read it ([SpotCheck.fromMap]); the answer is never kept or logged.
     */
    fun map(wsen: DoubleArray): String {
        gate()
        require(wsen.size == 4) { "a box" }
        val bbox = wsen.joinToString(",") { coordinate(it) }
        return call(request(server.apiBase + MAP_PATH + "?bbox=" + bbox, null, json = true).get())
    }

    /**
     * Adds a node at ([lat], [lon]) (the point the user pressed, never the phone's position) with [tags]
     * in [changeset]: `PUT /api/0.6/node/create`; its id.
     */
    fun createNode(token: String, changeset: Long, lat: Double, lon: Double, tags: Map<String, String>): Long {
        gate()
        val el = OsmElement(OsmRef(OsmType.NODE, -1), 0, tags, lat, lon, emptyList())
        val r = call(request(server.apiBase + "/api/0.6/node/create", token).put(xml(OsmXml.element(el, changeset, create = true))))
        return r.trim().toLongOrNull() ?: throw OsmError(200)
    }

    /**
     * Test builds only (the caller checks BuildConfig.TEST_EXTRAS; refused here for openstreetmap.org):
     * creates a node on the OSM test server for the emulator proofs ([createNode]).
     */
    fun createTestNode(token: String, changeset: Long, lat: Double, lon: Double, tags: Map<String, String>): Long {
        gate()
        if (server.host == OsmServer.LIVE.host) throw OnlineRefused("test objects only on the test server")
        return createNode(token, changeset, lat, lon, tags)
    }

    /**
     * Test builds only, test server only (item 32's emulator proof needs a road, and the test server has
     * none near the test spot): creates a way through [nodes] with [tags] (`PUT /api/0.6/way/create`); its id.
     */
    fun createTestWay(token: String, changeset: Long, nodes: List<Long>, tags: Map<String, String>): Long {
        gate()
        if (server.host == OsmServer.LIVE.host) throw OnlineRefused("test objects only on the test server")
        val el = OsmElement(OsmRef(OsmType.WAY, -1), 0, tags, null, null, nodes)
        val r = call(request(server.apiBase + "/api/0.6/way/create", token).put(xml(OsmXml.element(el, changeset, create = true))))
        return r.trim().toLongOrNull() ?: throw OsmError(200)
    }

    /**
     * Test builds only, test server only: deletes a test node made for the emulator proofs
     * (`DELETE /api/0.6/node/<id>` with its current version).
     */
    fun deleteTestNode(token: String, changeset: Long, e: OsmElement) {
        gate()
        if (server.host == OsmServer.LIVE.host) throw OnlineRefused("test objects only on the test server")
        val url = server.apiBase + "/api/0.6/node/${e.ref.id}"
        call(request(url, token).delete(xml(OsmXml.element(e, changeset))))
    }

    // ------------------------------------------------------------------ the one way out

    /** N4: refused before any connection while the user's switch is off. */
    private fun gate() {
        if (!allowed()) throw OnlineRefused("contributing to OpenStreetMap is off")
    }

    private fun request(url: String, token: String?, json: Boolean = url.endsWith(".json")): okhttp3.Request.Builder {
        val b = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", if (json) "application/json" else "*/*")
            .header("Accept-Encoding", "identity")
        if (token != null) b.header("Authorization", "Bearer $token")
        return b
    }

    /** Runs the request; the body of a 2xx answer, else [OsmError] with the status only. */
    private fun call(b: okhttp3.Request.Builder): String =
        client.newCall(b.build()).execute().use { r ->
            if (r.code !in 200..299) throw OsmError(r.code)
            r.body.string()
        }

    private fun xml(text: String): okhttp3.RequestBody = text.toByteArray(Charsets.UTF_8).toRequestBody(XML)

    companion object {
        const val NOTES_PATH = "/api/0.6/notes.json"
        const val TOKEN_PATH = "/oauth2/token"
        const val REVOKE_PATH = "/oauth2/revoke"
        const val AUTHORIZE_PATH = "/oauth2/authorize"
        const val USER_PATH = "/api/0.6/user/details.json"
        const val MAP_PATH = "/api/0.6/map.json"
        private val XML = "text/xml; charset=utf-8".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)
        private const val REFUSED_ADDRESS = "the host has an address that is not public"

        /** Seven decimals (about 1 cm), with a dot whatever the phone's language. */
        fun coordinate(v: Double): String = String.format(Locale.ROOT, "%.7f", v)

        /** The note's number from the API's GeoJSON answer (`properties.id`), or null. */
        fun noteId(json: String): Long? = try {
            val o = Json.parseToJsonElement(json) as? JsonObject
            ((o?.get("properties") as? JsonObject)?.get("id") as? JsonPrimitive)?.longOrNull
        } catch (_: Exception) {
            null
        }

        /** A string field of a JSON object answer ("access_token"), or null. */
        fun jsonString(json: String, key: String): String? = try {
            ((Json.parseToJsonElement(json) as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (_: Exception) {
            null
        }

        /** `user.display_name` of the user details answer, or null. */
        fun userNameOf(json: String): String? = try {
            val u = (Json.parseToJsonElement(json) as? JsonObject)?.get("user") as? JsonObject
            (u?.get("display_name") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}

/** The text of a note (docs/online-design.md 4.1): the quick choice, the user's words, and the app's line at the end. */
object NoteText {
    /** The quick choices of the form, in order. */
    val CHOICES = listOf("Closed", "Wrong hours", "Wrong location", "Road missing", "Turn not allowed")

    /**
     * The last line of every note: mappers then know where it came from and that the user saw the
     * map of a state pack (which may be weeks old), as StreetComplete and Organic Maps do. No
     * version and nothing else about the phone.
     */
    const val VIA = "via Beamline"

    /** What the user wrote, as kept in the outbox: "Closed: <words>", "Closed", or the words alone. */
    fun compose(choice: String?, words: String): String {
        val w = words.trim()
        return when {
            choice == null -> w
            w.isEmpty() -> choice
            else -> "$choice: $w"
        }
    }

    /** The text sent: [kept] and the [VIA] line. */
    fun forSending(kept: String): String = kept.trim() + "\n\n" + VIA

    /** OSM's limit for a note's text is 2000 characters; the app keeps room for its line. */
    const val MAX_CHARS = 1900
}
