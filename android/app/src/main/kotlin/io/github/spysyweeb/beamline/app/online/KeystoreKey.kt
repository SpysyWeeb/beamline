package io.github.spysyweeb.beamline.app.online

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The key that seals the OSM account (batch O2, docs/online-design.md 4.4): AES-256-GCM in the
 * Android Keystore, made on first use; it never leaves the Keystore, so the sealed token in the
 * no-backup folder cannot be opened anywhere else (nor after a restore, which has no such file anyway).
 */
object KeystoreKey {
    private const val STORE = "AndroidKeyStore"
    const val ALIAS = "beamline-osm-account"

    @Synchronized
    fun get(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
