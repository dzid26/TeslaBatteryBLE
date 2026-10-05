// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object TeslaCrypto {
    const val SHARED_KEY_SIZE = 16
    const val NONCE_SIZE = 12
    const val GCM_TAG_SIZE = 16
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    fun sessionKey(
        privateKeyPkcs8: ByteArray,
        peerPublicRaw: ByteArray,
    ): ByteArray {
        val privateKey =
            KeyFactory
                .getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8)) as ECPrivateKey
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(rawToPublicKey(peerPublicRaw), true)
        val shared = agreement.generateSecret()
        return MessageDigest.getInstance("SHA-1").digest(shared).copyOf(SHARED_KEY_SIZE)
    }

    fun rawToPublicKey(raw: ByteArray): ECPublicKey {
        require(raw.size == 65 && raw[0] == 0x04.toByte()) { "expected uncompressed point" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory
            .getInstance("EC")
            .generatePublic(ECPublicKeySpec(ECPoint(x, y), p256Parameters())) as ECPublicKey
    }

    fun subkey(
        sessionKey: ByteArray,
        label: String,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(sessionKey, "HmacSHA256"))
        return mac.doFinal(label.toByteArray(Charsets.US_ASCII))
    }

    fun encryptGcm(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(associatedData)
        val combined = cipher.doFinal(plaintext)
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
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(associatedData)
            cipher.doFinal(ciphertext + tag)
        }.getOrNull()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun p256Parameters(): ECParameterSpec =
        AlgorithmParameters
            .getInstance("EC")
            .apply {
                init(ECGenParameterSpec("secp256r1"))
            }.getParameterSpec(ECParameterSpec::class.java)
}
