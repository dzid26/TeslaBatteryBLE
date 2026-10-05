// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import android.util.Base64
import com.dzid26.teslable.core.protocol.TeslaKeyPair
import com.dzid26.teslable.core.protocol.TeslaKeys
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the vehicle key pair in app-private SharedPreferences.
 *
 * The key is deliberately not wrapped by a hardware-bound Keystore key so that
 * Android's system backup can carry it to a new phone and pairing survives a
 * device change. The trade-off is documented in ADR-0005: the enrolled key is
 * CHARGING_MANAGER-scoped (charge control plus reads, no unlock or drive), other
 * apps cannot read app-private storage, and Android backup is protected by the
 * user's Google account and lock-screen secret.
 *
 * Older installs that still hold Keystore-encrypted material are decrypted and
 * migrated transparently on first load.
 */
class PairingKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): TeslaKeyPair? = loadInternal()

    fun loadOrCreate(): TeslaKeyPair {
        loadInternal()?.let { return it }
        val keyPair = TeslaKeys.generate()
        save(keyPair)
        return keyPair
    }

    private fun loadInternal(): TeslaKeyPair? {
        val publicKey = prefs.getString(KEY_PUBLIC, null) ?: return null
        val stored = prefs.getString(KEY_PRIVATE, null) ?: return null

        if (prefs.contains(KEY_IV)) {
            // Legacy Keystore-encrypted material: decrypt, then migrate.
            val legacy = decryptLegacy(stored, publicKey)
            if (legacy == null) {
                // Keystore material restored from another device cannot be
                // decrypted; drop it so a fresh key can be generated.
                prefs.edit().remove(KEY_IV).remove(KEY_PRIVATE).remove(KEY_PUBLIC).apply()
                return null
            }
            save(legacy)
            return legacy
        }

        return runCatching {
            TeslaKeyPair(
                privateKeyPkcs8 = Base64.decode(stored, Base64.NO_WRAP),
                publicKeyRaw = Base64.decode(publicKey, Base64.NO_WRAP),
            )
        }.getOrNull()
    }

    private fun save(keyPair: TeslaKeyPair) {
        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(keyPair.privateKeyPkcs8, Base64.NO_WRAP))
            .putString(KEY_PUBLIC, Base64.encodeToString(keyPair.publicKeyRaw, Base64.NO_WRAP))
            .remove(KEY_IV)
            .apply()
    }

    /** Decrypts material written by app versions that used Keystore wrapping. */
    private fun decryptLegacy(encrypted: String, publicKey: String): TeslaKeyPair? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                legacySecretKey(),
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            TeslaKeyPair(
                privateKeyPkcs8 = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                publicKeyRaw = Base64.decode(publicKey, Base64.NO_WRAP),
            )
        }.getOrNull()
    }

    private fun legacySecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IllegalStateException("Legacy Keystore key is missing")
    }

    private companion object {
        const val PREFS = "pairing_key"
        const val KEY_IV = "iv"
        const val KEY_PRIVATE = "private"
        const val KEY_PUBLIC = "public"
        const val ALIAS = "teslable-pairing-key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
