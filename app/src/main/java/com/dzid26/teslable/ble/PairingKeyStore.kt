// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import android.app.backup.BackupManager
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
 * Stores one P-256 key pair per vehicle in app-private SharedPreferences.
 *
 * A key belongs to a single car and is never re-enrolled once the car stops
 * recognizing it: pairing generates a fresh key, so a key that was removed
 * from the car (for example after a phone theft) stays dead. See ADR-0005.
 *
 * Default mode is device-only: private keys are encrypted with an AES key held
 * in the Android Keystore, so they cannot be read from a backup and do not
 * survive a phone change. The user can opt in to portable mode (one global
 * setting), which stores the private keys as plaintext PKCS#8 base64 so
 * Android's system backup can restore pairing on a new phone.
 *
 * Older installs held one global key shared by all cars; it is adopted as the
 * key of every known vehicle once, then removed.
 */
class PairingKeyStore(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when keys are allowed to be included in Android backup. */
    fun isBackupEnabled(): Boolean = prefs.getBoolean(KEY_BACKUP_ENABLED, false)

    /**
     * Changes the backup mode and re-saves every stored key in the new format.
     * If no key exists yet, only the preference is updated so the next
     * generated key is written in the chosen format.
     */
    fun setBackupEnabled(enabled: Boolean) {
        storedVehicleIds().forEach { vehicleId ->
            loadInternal(vehicleId)?.let { save(vehicleId, it, enabled) }
        }
        prefs.edit().putBoolean(KEY_BACKUP_ENABLED, enabled).apply()
        // Replacing or excluding keys changes what the next backup should
        // contain; nudge the system instead of waiting for the daily pass.
        BackupManager(appContext).dataChanged()
    }

    /** The key pair for one car, or null when the car has none. */
    fun load(vehicleId: String): TeslaKeyPair? = loadInternal(vehicleId)

    /** Generates and stores a fresh key pair for one car, replacing any previous one. */
    fun generate(vehicleId: String): TeslaKeyPair {
        val keyPair = TeslaKeys.generate()
        save(vehicleId, keyPair)
        return keyPair
    }

    /**
     * Adopts the pre-per-car global key as the key of every vehicle that does
     * not have one yet, then removes the legacy entries. Does nothing while no
     * vehicles are known, so the legacy key is not lost before they appear.
     */
    fun adoptLegacyKey(vehicleIds: Collection<String>) {
        if (vehicleIds.isEmpty()) return
        val legacy = loadLegacy() ?: return
        vehicleIds.filter { loadInternal(it) == null }.forEach { save(it, legacy) }
        prefs.edit()
            .remove(KEY_PUBLIC)
            .remove(KEY_PRIVATE)
            .remove(KEY_IV)
            .apply()
    }

    private fun loadLegacy(): TeslaKeyPair? {
        val publicKey = prefs.getString(KEY_PUBLIC, null) ?: return null
        val stored = prefs.getString(KEY_PRIVATE, null) ?: return null
        val iv = prefs.getString(KEY_IV, null)
        if (iv != null) {
            val decrypted = decryptWithKeystore(stored, publicKey, iv)
            if (decrypted == null) {
                // Keystore material restored from another device cannot be
                // decrypted; drop it so a fresh key can be generated.
                prefs.edit()
                    .remove(KEY_PUBLIC)
                    .remove(KEY_PRIVATE)
                    .remove(KEY_IV)
                    .apply()
                return null
            }
            return decrypted
        }
        return runCatching {
            TeslaKeyPair(
                privateKeyPkcs8 = Base64.decode(stored, Base64.NO_WRAP),
                publicKeyRaw = Base64.decode(publicKey, Base64.NO_WRAP),
            )
        }.getOrNull()
    }

    private fun loadInternal(vehicleId: String): TeslaKeyPair? {
        val publicKey = prefs.getString(key(vehicleId, KEY_PUBLIC), null) ?: return null
        val stored = prefs.getString(key(vehicleId, KEY_PRIVATE), null) ?: return null
        val iv = prefs.getString(key(vehicleId, KEY_IV), null)

        if (iv != null) {
            // Keystore-encrypted material: decrypt, then migrate to the current mode.
            val decrypted = decryptWithKeystore(stored, publicKey, iv)
            if (decrypted == null) {
                remove(vehicleId)
                return null
            }
            save(vehicleId, decrypted)
            return decrypted
        }

        val plaintext =
            runCatching {
                TeslaKeyPair(
                    privateKeyPkcs8 = Base64.decode(stored, Base64.NO_WRAP),
                    publicKeyRaw = Base64.decode(publicKey, Base64.NO_WRAP),
                )
            }.getOrNull() ?: return null

        if (!isBackupEnabled()) {
            // A plaintext key exists but the current mode is device-only:
            // re-encrypt it under the Keystore key.
            save(vehicleId, plaintext, backupEnabled = false)
        }
        return plaintext
    }

    private fun save(
        vehicleId: String,
        keyPair: TeslaKeyPair,
        backupEnabled: Boolean = isBackupEnabled(),
    ) {
        val editor =
            prefs
                .edit()
                .putString(key(vehicleId, KEY_PUBLIC), Base64.encodeToString(keyPair.publicKeyRaw, Base64.NO_WRAP))
        if (backupEnabled) {
            editor
                .putString(key(vehicleId, KEY_PRIVATE), Base64.encodeToString(keyPair.privateKeyPkcs8, Base64.NO_WRAP))
                .remove(key(vehicleId, KEY_IV))
        } else {
            val (iv, ciphertext) = encryptWithKeystore(keyPair.privateKeyPkcs8)
            editor
                .putString(key(vehicleId, KEY_PRIVATE), Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                .putString(key(vehicleId, KEY_IV), Base64.encodeToString(iv, Base64.NO_WRAP))
        }
        editor.apply()
        if (backupEnabled) {
            // The key is part of what Android backs up now; schedule a pass
            // rather than waiting for the next idle window.
            BackupManager(appContext).dataChanged()
        }
    }

    private fun remove(vehicleId: String) {
        prefs
            .edit()
            .remove(key(vehicleId, KEY_PUBLIC))
            .remove(key(vehicleId, KEY_PRIVATE))
            .remove(key(vehicleId, KEY_IV))
            .apply()
    }

    /** Vehicle ids that currently have a stored key. */
    private fun storedVehicleIds(): Set<String> =
        prefs.all.keys
            .mapNotNull { prefKey ->
                if (prefKey.startsWith(PREFIX) && prefKey.endsWith(".$KEY_PUBLIC")) {
                    prefKey.removePrefix(PREFIX).removeSuffix(".$KEY_PUBLIC").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }.toSet()

    private fun key(vehicleId: String, suffix: String): String = "$PREFIX$vehicleId.$suffix"

    /** Encrypts bytes with the Keystore AES-GCM key, returning IV and ciphertext. */
    private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv to ciphertext
    }

    /** Decrypts material written in device-only (Keystore-wrapped) format. */
    private fun decryptWithKeystore(
        encrypted: String,
        publicKey: String,
        iv: String,
    ): TeslaKeyPair? =
        runCatching {
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

    /** Returns the Keystore AES key, generating it if necessary. */
    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        return KeyGenerator
            .getInstance("AES", ANDROID_KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec
                        .Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(KEY_SIZE_BITS)
                        .build(),
                )
            }.generateKey()
    }

    private companion object {
        const val PREFS = "pairing_key"
        const val KEY_BACKUP_ENABLED = "key_backup_enabled"
        const val PREFIX = "key."
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
