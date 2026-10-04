package com.dzid26.teslable.core.protocol

import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

data class TeslaKeyPair(
    val privateKeyPkcs8: ByteArray,
    val publicKeyRaw: ByteArray,
) {
    val keyId: ByteArray
        get() = MessageDigest.getInstance("SHA-1").digest(publicKeyRaw).copyOf(4)
}

object TeslaKeys {

    fun generate(): TeslaKeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = generator.generateKeyPair()
        val publicKey = keyPair.public as ECPublicKey
        val raw = byteArrayOf(0x04) +
            publicKey.w.affineX.toFixed(FIELD_SIZE) +
            publicKey.w.affineY.toFixed(FIELD_SIZE)
        return TeslaKeyPair(
            privateKeyPkcs8 = keyPair.private.encoded,
            publicKeyRaw = raw,
        )
    }

    private fun BigInteger.toFixed(size: Int): ByteArray {
        val bytes = toByteArray()
        val trimmed = if (bytes.size > size) bytes.copyOfRange(bytes.size - size, bytes.size) else bytes
        return ByteArray(size - trimmed.size) + trimmed
    }

    private const val FIELD_SIZE = 32
}
