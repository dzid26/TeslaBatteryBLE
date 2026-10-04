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
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val encrypted = prefs.getString(KEY_PRIVATE, null) ?: return null
        val publicKey = prefs.getString(KEY_PUBLIC, null) ?: return null
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

    private fun save(keyPair: TeslaKeyPair) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(keyPair.privateKeyPkcs8)
        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_PRIVATE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_PUBLIC, Base64.encodeToString(keyPair.publicKeyRaw, Base64.NO_WRAP))
            .apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
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
