// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dzid26.teslable.core.protocol.TeslaKeyPair
import com.dzid26.teslable.core.protocol.TeslaKeys
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the vehicle key pair in app-private SharedPreferences.
 *
 * Default mode is device-only: the P-256 private key is encrypted with an AES
 * key held in the Android Keystore, so it cannot be read from a backup and does
 * not survive a phone change.
 *
 * The user can opt in to portable mode, which stores the private key as
 * plaintext PKCS#8 base64 so Android's system backup can restore pairing on a
 * new phone. The trade-off is documented in ADR-0005: the enrolled key is
 * CHARGING_MANAGER-scoped (charge control plus reads, no unlock or drive), other
 * apps cannot read app-private storage, and Android backup is protected by the
 * user's Google account and lock-screen secret on Android 12+.
 *
 * Older installs that still hold Keystore-encrypted material are decrypted and
 * migrated transparently on first load, then re-saved in the currently selected
 * mode.
 */
class PairingKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the key is allowed to be included in Android backup. */
    fun isBackupEnabled(): Boolean = prefs.getBoolean(KEY_BACKUP_ENABLED, false)

    /**
     * Changes the backup mode and re-saves an existing key in the new format.
     * If no key exists yet, only the preference is updated so the next
     * generated key is written in the chosen format.
     */
    fun setBackupEnabled(enabled: Boolean) {
        val current = loadInternal()
        if (current != null) {
            save(current, enabled)
        }
        prefs.edit().putBoolean(KEY_BACKUP_ENABLED, enabled).apply()
    }

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
            // Keystore-encrypted material: decrypt, then migrate to the current mode.
            val decrypted = decryptWithKeystore(stored, publicKey)
            if (decrypted == null) {
                // Keystore material restored from another device cannot be
                // decrypted; drop it so a fresh key can be generated.
                prefs.edit().remove(KEY_IV).remove(KEY_PRIVATE).remove(KEY_PUBLIC).apply()
                return null
            }
            save(decrypted)
            return decrypted
        }

        val plaintext = runCatching {
            TeslaKeyPair(
                privateKeyPkcs8 = Base64.decode(stored, Base64.NO_WRAP),
                publicKeyRaw = Base64.decode(publicKey, Base64.NO_WRAP),
            )
        }.getOrNull() ?: return null

        if (!isBackupEnabled()) {
            // A plaintext key exists but the current mode is device-only:
            // re-encrypt it under the Keystore key.
            save(plaintext, backupEnabled = false)
        }
        return plaintext
    }

    private fun save(keyPair: TeslaKeyPair, backupEnabled: Boolean = isBackupEnabled()) {
        val editor = prefs.edit()
            .putString(KEY_PUBLIC, Base64.encodeToString(keyPair.publicKeyRaw, Base64.NO_WRAP))
        if (backupEnabled) {
            editor
                .putString(KEY_PRIVATE, Base64.encodeToString(keyPair.privateKeyPkcs8, Base64.NO_WRAP))
                .remove(KEY_IV)
        } else {
            val (iv, ciphertext) = encryptWithKeystore(keyPair.privateKeyPkcs8)
            editor
                .putString(KEY_PRIVATE, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
        }
        editor.apply()
    }

    /** Encrypts bytes with the Keystore AES-GCM key, returning IV and ciphertext. */
    private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv to ciphertext
    }

    /** Decrypts material written in device-only (Keystore-wrapped) format. */
    private fun decryptWithKeystore(encrypted: String, publicKey: String): TeslaKeyPair? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            TeslaKeyPair(
                privateKeyPkcs8 = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)),
                publicKeyRaw = Base64.decode(publicKey, Base64.NO_WRAP),
            )
        }.getOrNull()
    }

    /** Returns the Keystore AES key, generating it if necessary. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        return KeyGenerator.getInstance("AES", ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val PREFS = "pairing_key"
        const val KEY_BACKUP_ENABLED = "key_backup_enabled"
        const val KEY_IV = "iv"
        const val KEY_PRIVATE = "private"
        const val KEY_PUBLIC = "public"
        const val ALIAS = "teslable-pairing-key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
