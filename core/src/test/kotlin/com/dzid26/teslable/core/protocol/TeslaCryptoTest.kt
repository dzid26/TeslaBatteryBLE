// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.spec.InvalidKeySpecException

class TeslaCryptoTest {
    @Test
    fun `derives the protocol reference session key`() {
        val key = TeslaCrypto.sessionKey(REFERENCE_CLIENT_PKCS8.hex(), REFERENCE_VEHICLE_PUBLIC.hex())
        assertArrayEquals(REFERENCE_SESSION_KEY.hex(), key)
    }

    @Test
    fun `subkey matches the protocol reference hmac`() {
        val subkey = TeslaCrypto.subkey(REFERENCE_SESSION_KEY.hex(), TeslaSession.LABEL_SESSION_INFO)
        assertArrayEquals(REFERENCE_SESSION_INFO_KEY.hex(), subkey)
    }

    @Test
    fun `round trips aes gcm with a four byte nonce`() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = byteArrayOf(1, 2, 3, 4)
        val plaintext = "edge-case".toByteArray()
        val associatedData = byteArrayOf(9, 9)

        val (ciphertext, tag) = TeslaCrypto.encryptGcm(key, nonce, plaintext, associatedData)
        assertEquals(plaintext.size, ciphertext.size)
        assertEquals(TeslaCrypto.GCM_TAG_SIZE, tag.size)
        assertArrayEquals(plaintext, TeslaCrypto.decryptGcm(key, nonce, ciphertext, tag, associatedData))
        assertNull(TeslaCrypto.decryptGcm(key, byteArrayOf(1, 2, 3, 5), ciphertext, tag, associatedData))
    }

    @Test
    fun `decrypt gcm rejects tampered inputs`() {
        val key = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { 7 }
        val associatedData = byteArrayOf(1, 2, 3)
        val (ciphertext, tag) = TeslaCrypto.encryptGcm(key, nonce, "payload".toByteArray(), associatedData)

        val flippedCiphertext = ciphertext.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(TeslaCrypto.decryptGcm(key, nonce, flippedCiphertext, tag, associatedData))

        val flippedTag = tag.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(TeslaCrypto.decryptGcm(key, nonce, ciphertext, flippedTag, associatedData))

        assertNull(TeslaCrypto.decryptGcm(ByteArray(16) { 1 }, nonce, ciphertext, tag, associatedData))
        assertNull(TeslaCrypto.decryptGcm(key, nonce, ciphertext, tag, byteArrayOf(1, 2, 4)))
        assertNull(TeslaCrypto.decryptGcm(key, nonce, ciphertext, ByteArray(0), associatedData))
    }

    @Test
    fun `raw to public key rejects malformed points`() {
        val keyPair = TeslaKeys.generate()
        assertNotNull(TeslaCrypto.rawToPublicKey(keyPair.publicKeyRaw))

        val wrongPrefix = keyPair.publicKeyRaw.copyOf().also { it[0] = 0x05 }
        for (malformed in listOf(ByteArray(0), ByteArray(64), ByteArray(65), wrongPrefix)) {
            assertThrows(IllegalArgumentException::class.java) {
                TeslaCrypto.rawToPublicKey(malformed)
            }
        }
    }

    @Test
    fun `session key rejects malformed keys`() {
        val keyPair = TeslaKeys.generate()
        assertThrows(InvalidKeySpecException::class.java) {
            TeslaCrypto.sessionKey(byteArrayOf(1, 2, 3), keyPair.publicKeyRaw)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TeslaCrypto.sessionKey(keyPair.privateKeyPkcs8, ByteArray(10))
        }
    }

    @Test
    fun `random bytes respects the requested size`() {
        assertEquals(0, TeslaCrypto.randomBytes(0).size)
        assertEquals(TeslaCrypto.NONCE_SIZE, TeslaCrypto.randomBytes(TeslaCrypto.NONCE_SIZE).size)
    }

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        // Test keys and derived values from the protocol reference document
        // (teslamotors/vehicle-command, pkg/protocol/protocol.md). The private
        // key is published for debugging and must never be enrolled on a car.
        const val REFERENCE_CLIENT_PKCS8 =
            "3041020100301306072a8648ce3d020106082a8648ce3d030107042730250201010420" +
                "2538cdc29a97c19c1e99a637d6cf4f8c970c118b56ede1e6323e6d162c4b30db"
        const val REFERENCE_VEHICLE_PUBLIC =
            "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
                "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97"
        const val REFERENCE_SESSION_KEY = "1b2fce19967b79db696f909cff89ea9a"
        const val REFERENCE_SESSION_INFO_KEY =
            "fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300"
    }
}
