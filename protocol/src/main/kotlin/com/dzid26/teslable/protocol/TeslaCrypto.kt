// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.CryptographySystem
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA1
import dev.whyoleg.cryptography.algorithms.SHA256

@OptIn(DelicateCryptographyApi::class)
object TeslaCrypto {
    const val SHARED_KEY_SIZE = 16
    const val NONCE_SIZE = 12
    const val GCM_TAG_SIZE = 16

    private val provider = CryptographyProvider.Default
    private val ecdh = provider.get(ECDH)
    private val sha1 = provider.get(SHA1)

    fun sessionKey(
        privateKeyPkcs8: ByteArray,
        peerPublicRaw: ByteArray,
    ): ByteArray {
        val privateKey =
            ecdh
                .privateKeyDecoder(EC.Curve.P256)
                .decodeFromByteArrayBlocking(EC.PrivateKey.Format.DER.Generic, privateKeyPkcs8)
        val shared =
            privateKey
                .sharedSecretGenerator()
                .generateSharedSecretToByteArrayBlocking(rawToPublicKey(peerPublicRaw))
        // Truncated SHA-1 is mandated by the Tesla protocol, not our choice.
        return sha1.hasher().hashBlocking(shared).copyOf(SHARED_KEY_SIZE)
    }

    fun rawToPublicKey(raw: ByteArray): ECDH.PublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "expected uncompressed point" }
        return ecdh
            .publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW, raw)
    }

    fun subkey(
        sessionKey: ByteArray,
        label: String,
    ): ByteArray {
        val key =
            provider
                .get(HMAC)
                .keyDecoder(SHA256)
                .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, sessionKey)
        return key.signatureGenerator().generateSignatureBlocking(label.toByteArray(Charsets.US_ASCII))
    }

    fun encryptGcm(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        // Callers must use a fresh nonce per message: the JDK provider refuses
        // a repeated nonce under one key (as Android's GCM does).
        val combined = aesCipher(key).encryptWithIvBlocking(nonce, plaintext, associatedData)
        val ciphertext = combined.copyOf(combined.size - GCM_TAG_SIZE)
        val tag = combined.copyOfRange(combined.size - GCM_TAG_SIZE, combined.size)
        return ciphertext to tag
    }

    fun decryptGcm(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        tag: ByteArray,
        associatedData: ByteArray,
    ): ByteArray? =
        runCatching {
            aesCipher(key).decryptWithIvBlocking(nonce, ciphertext + tag, associatedData)
        }.getOrNull()

    fun randomBytes(size: Int): ByteArray = CryptographySystem.getDefaultRandom().nextBytes(size)

    private fun aesCipher(key: ByteArray) =
        provider
            .get(AES.GCM)
            .keyDecoder()
            .decodeFromByteArrayBlocking(AES.Key.Format.RAW, key)
            .cipher()
}
