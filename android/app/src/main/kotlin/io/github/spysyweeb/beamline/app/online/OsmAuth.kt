package io.github.spysyweeb.beamline.app.online

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * OSM's sign-in (batch O2, docs/online-design.md 4.4): OAuth 2.0 authorization code with PKCE (RFC
 * 7636, S256) for a public client (no secret). The app makes a random code_verifier and state, opens
 * "<authBase>/oauth2/authorize" in the browser, and accepts the redirect `<scheme>://oauth?code=..&state=..`
 * only for the sign-in it started ([SignIn.accept]: the state must be the one it made, once, within
 * [SignIn.MAX_AGE_MS]). The app never sees the password.
 */
object Pkce {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** 32 random bytes, base64url: 43 characters of [A-Za-z0-9-_] (RFC 7636 4.1). */
    fun verifier(): String = b64.encodeToString(ByteArray(32).also(random::nextBytes))

    /** BASE64URL(SHA-256(ASCII(verifier))) (RFC 7636 4.2, S256). */
    fun challenge(verifier: String): String = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** A random state of 16 bytes, base64url. */
    fun state(): String = b64.encodeToString(ByteArray(16).also(random::nextBytes))
}

/** The app's registration with one OSM server (the client id is public; no secret is kept in the app). */
data class OsmClient(val clientId: String, val redirectUri: String, val scopes: String = SCOPES) {
    companion object {
        const val REDIRECT_URI = "io.github.spysyweeb.beamline://oauth"
        /** 4.4: the user's name, map edits and notes. */
        const val SCOPES = "read_prefs write_api write_notes"
    }
}

/** A sign-in the app started: kept (sealed) until its redirect comes back or it is older than [SignIn.MAX_AGE_MS]. */
data class SignIn(val state: String, val verifier: String, val startedMs: Long) {
    override fun toString(): String = "SignIn"

    /** The address of OSM's sign-in page for this sign-in. */
    fun authorizeUrl(server: OsmServer, client: OsmClient): String =
        server.authBase + OsmHttp.AUTHORIZE_PATH + "?" + listOf(
            "response_type" to "code",
            "client_id" to client.clientId,
            "redirect_uri" to client.redirectUri,
            "scope" to client.scopes,
            "state" to state,
            "code_challenge" to Pkce.challenge(verifier),
            "code_challenge_method" to "S256",
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }

    /**
     * The code of a redirect [uri] that answers this sign-in, or null: the address must be the
     * registered redirect, its state this one's, the sign-in no older than [MAX_AGE_MS] at [nowMs].
     * An error answer (the user chose "Deny") has no code: null too.
     */
    /**
     * Whether the redirect [uri] answers this sign-in at all: the registered redirect address with this
     * sign-in's state (batch O3, from the O2 review: a redirect with another state is ignored and does
     * not end the sign-in the user is in the middle of).
     */
    fun answers(uri: String, client: OsmClient): Boolean =
        uri.substringBefore('?').substringBefore('#') == client.redirectUri && query(uri)["state"] == state

    fun accept(uri: String, client: OsmClient, nowMs: Long): String? {
        if (nowMs - startedMs !in 0..MAX_AGE_MS) return null
        val base = uri.substringBefore('?').substringBefore('#')
        if (base != client.redirectUri) return null
        val q = query(uri)
        if (q["state"] != state) return null
        return q["code"]?.takeIf { it.isNotEmpty() && "error" !in q }
    }

    companion object {
        /** A sign-in left open longer than this is not accepted (the browser was put aside). */
        const val MAX_AGE_MS = 15 * 60_000L

        fun start(nowMs: Long) = SignIn(Pkce.state(), Pkce.verifier(), nowMs)

        fun query(uri: String): Map<String, String> {
            val q = uri.substringAfter('?', "").substringBefore('#')
            if (q.isEmpty()) return emptyMap()
            return q.split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
        }
    }
}

/**
 * Seals and opens small secrets with AES-GCM under [key] (on Android a key in the Android Keystore
 * that never leaves it, KeystoreKey; in the JVM tests a generated one). The sealed bytes are the
 * 12-byte IV and the ciphertext with its tag.
 */
class SecretBox(private val key: () -> SecretKey) {
    fun seal(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        require(iv.size == IV_BYTES) { "unexpected IV size" }
        return iv + c.doFinal(plain)
    }

    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw IOException("too short")
        val c = Cipher.getInstance(AES_GCM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return c.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    companion object {
        const val AES_GCM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** The signed-in account: the access token and the OSM display name. [toString] prints neither. */
data class OsmAccount(val token: String, val name: String) {
    override fun toString(): String = "OsmAccount"
}

/**
 * The account and the open sign-in (4.4), each in its own sealed file in the no-backup folder
 * (`osm-account.bin`, `osm-signin.bin`): AES-GCM with a key of the Android Keystore ([box]); never
 * plain text on disk, never logged, never in a backup (PV1-PV8). A file that cannot be opened (the
 * key was lost, the file broken) reads as signed out and is deleted. Sign-out deletes the account file.
 */
class OsmAccountStore(private val dir: File, private val box: SecretBox) {
    private val accountFile = File(dir, ACCOUNT_FILE)
    private val signInFile = File(dir, SIGN_IN_FILE)
    private val _account = MutableStateFlow(readAccount())
    /** The signed-in account, or null. */
    val account: StateFlow<OsmAccount?> = _account.asStateFlow()

    @Synchronized
    fun save(a: OsmAccount) {
        write(accountFile, JsonObject(mapOf("token" to JsonPrimitive(a.token), "name" to JsonPrimitive(a.name))).toString())
        _account.value = a
    }

    /** Sign-out: the token is deleted from the phone. */
    @Synchronized
    fun clear() {
        accountFile.delete()
        _account.value = null
    }

    @Synchronized
    fun startSignIn(s: SignIn) =
        write(signInFile, JsonObject(mapOf("state" to JsonPrimitive(s.state), "verifier" to JsonPrimitive(s.verifier), "started_ms" to JsonPrimitive(s.startedMs))).toString())

    /** The open sign-in, or null. */
    @Synchronized
    fun pendingSignIn(): SignIn? {
        val o = read(signInFile) ?: return null
        val state = (o["state"] as? JsonPrimitive)?.contentOrNull ?: return null
        val verifier = (o["verifier"] as? JsonPrimitive)?.contentOrNull ?: return null
        val started = (o["started_ms"] as? JsonPrimitive)?.longOrNull ?: return null
        return SignIn(state, verifier, started)
    }

    /** The sign-in is used (or given up): one redirect only. */
    @Synchronized
    fun endSignIn() {
        signInFile.delete()
    }

    private fun readAccount(): OsmAccount? {
        val o = read(accountFile) ?: return null
        val token = (o["token"] as? JsonPrimitive)?.contentOrNull ?: return null
        val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        return OsmAccount(token, name)
    }

    private fun read(f: File): JsonObject? {
        if (!f.isFile) return null
        return try {
            Json.parseToJsonElement(box.open(f.readBytes()).toString(Charsets.UTF_8)) as JsonObject
        } catch (_: Exception) {
            f.delete()
            null
        }
    }

    private fun write(f: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, f.name + ".tmp")
        tmp.outputStream().use {
            it.write(box.seal(text.toByteArray(Charsets.UTF_8)))
            it.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("cannot write the account")
        }
    }

    companion object {
        const val ACCOUNT_FILE = "osm-account.bin"
        const val SIGN_IN_FILE = "osm-signin.bin"
    }
}
