package io.github.spysyweeb.beamline.app.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.crypto.KeyGenerator

/**
 * Batch O2 (docs/online-design.md 4.4): PKCE S256 and the state check of the sign-in; the account
 * kept sealed (no plain text on disk), read back, deleted on sign-out.
 */
class OsmAuthTest {
    private val client = OsmClient("test-client", OsmClient.REDIRECT_URI)

    @Test
    fun pkceFollowsRfc7636() {
        // RFC 7636 appendix B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Pkce.verifier()
        assertEquals(43, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(v, Pkce.verifier())
        assertNotEquals(Pkce.state(), Pkce.state())
    }

    @Test
    fun theSignInPageAddressCarriesTheChallengeNotTheVerifier() {
        val s = SignIn("st4te", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", 0)
        val url = s.authorizeUrl(OsmServer.TEST, client)
        assertTrue(url.startsWith("https://master.apis.dev.openstreetmap.org/oauth2/authorize?"))
        val q = SignIn.query(url)
        assertEquals("code", q["response_type"])
        assertEquals("test-client", q["client_id"])
        assertEquals("io.github.spysyweeb.beamline://oauth", q["redirect_uri"])
        assertEquals("read_prefs write_api write_notes", q["scope"])
        assertEquals("st4te", q["state"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", q["code_challenge"])
        assertFalse("dBjftJeZ4CVP" in url)
        assertTrue(OsmServer.LIVE.let { s.authorizeUrl(it, client) }.startsWith("https://www.openstreetmap.org/oauth2/authorize?"))
    }

    @Test
    fun onlyTheRedirectOfThisSignInIsAccepted() {
        val s = SignIn("abc", "v", 1_000)
        assertEquals("c0de", s.accept("io.github.spysyweeb.beamline://oauth?code=c0de&state=abc", client, 2_000))
        assertNull(s.accept("io.github.spysyweeb.beamline://oauth?code=c0de&state=other", client, 2_000), "another state")
        assertNull(s.accept("io.github.spysyweeb.beamline://oauth?code=c0de", client, 2_000), "no state")
        assertNull(s.accept("io.github.spysyweeb.beamline://evil?code=c0de&state=abc", client, 2_000), "another address")
        assertNull(s.accept("https://example.com/oauth?code=c0de&state=abc", client, 2_000), "another scheme")
        assertNull(s.accept("io.github.spysyweeb.beamline://oauth?error=access_denied&state=abc", client, 2_000), "denied")
        assertNull(s.accept("io.github.spysyweeb.beamline://oauth?code=c0de&state=abc", client, 1_000 + SignIn.MAX_AGE_MS + 1), "too old")
    }

    private fun box(): SecretBox {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return SecretBox { key }
    }

    @Test
    fun theTokenIsNeverPlainOnDiskAndSignOutDeletesIt(@TempDir dir: File) {
        val b = box()
        val store = OsmAccountStore(dir, b)
        assertNull(store.account.value)
        val token = "tok_" + "A1b2C3d4".repeat(6)
        store.save(OsmAccount(token, "Mapper Name"))
        val f = File(dir, OsmAccountStore.ACCOUNT_FILE)
        val bytes = f.readBytes()
        val asText = String(bytes, Charsets.ISO_8859_1)
        assertFalse(token in asText, "token in plain text")
        assertFalse("tok_" in asText)
        assertFalse("Mapper" in asText, "name in plain text")
        assertEquals(OsmAccount(token, "Mapper Name"), OsmAccountStore(dir, b).account.value, "read back with the key")
        assertEquals("OsmAccount", store.account.value.toString())
        // another key (a restore elsewhere, a lost Keystore key): signed out, the file removed
        assertNull(OsmAccountStore(dir, box()).account.value)
        assertFalse(f.exists())
        store.save(OsmAccount(token, "Mapper Name"))
        store.clear()
        assertNull(store.account.value)
        assertFalse(f.exists())
        assertTrue(dir.listFiles()!!.none { it.name.startsWith("osm-account") })
    }

    @Test
    fun theOpenSignInIsSealedToo(@TempDir dir: File) {
        val b = box()
        val store = OsmAccountStore(dir, b)
        val s = SignIn("state-xyz", "verifier-xyz", 5)
        store.startSignIn(s)
        assertFalse("verifier-xyz" in String(File(dir, OsmAccountStore.SIGN_IN_FILE).readBytes(), Charsets.ISO_8859_1))
        assertEquals(s, OsmAccountStore(dir, b).pendingSignIn())
        store.endSignIn()
        assertNull(store.pendingSignIn())
    }
}
