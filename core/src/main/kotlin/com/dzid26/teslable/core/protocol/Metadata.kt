// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

interface HashContext {
    fun update(data: ByteArray)

    fun digest(): ByteArray
}

class MessageDigestContext(
    algorithm: String,
) : HashContext {
    private val digest = MessageDigest.getInstance(algorithm)

    override fun update(data: ByteArray) = digest.update(data)

    override fun digest(): ByteArray = digest.digest()
}

class MacContext(
    key: ByteArray,
) : HashContext {
    private val mac =
        Mac.getInstance(HMAC_SHA256).apply {
            init(SecretKeySpec(key, HMAC_SHA256))
        }

    override fun update(data: ByteArray) = mac.update(data)

    override fun digest(): ByteArray = mac.doFinal()

    private companion object {
        const val HMAC_SHA256 = "HmacSHA256"
    }
}

class Metadata(
    private val context: HashContext,
) {
    private var lastTag = -1

    fun add(
        tag: Int,
        value: ByteArray?,
    ): Metadata {
        require(tag >= lastTag) { "metadata items must be added in increasing tag order" }
        if (value == null) return this
        require(value.size <= MAX_FIELD_LENGTH) { "metadata fields can't be more than 255 bytes long" }
        lastTag = tag
        context.update(byteArrayOf(tag.toByte()))
        context.update(byteArrayOf(value.size.toByte()))
        context.update(value)
        return this
    }

    fun addUint32(
        tag: Int,
        value: Int,
    ): Metadata =
        add(
            tag,
            byteArrayOf(
                (value ushr 24).toByte(),
                (value ushr 16).toByte(),
                (value ushr 8).toByte(),
                value.toByte(),
            ),
        )

    fun checksum(message: ByteArray): ByteArray {
        context.update(byteArrayOf(TAG_END.toByte()))
        context.update(message)
        return context.digest()
    }

    companion object {
        const val TAG_END = 255
        const val MAX_FIELD_LENGTH = 255

        fun sha256(): Metadata = Metadata(MessageDigestContext("SHA-256"))

        fun hmacSha256(key: ByteArray): Metadata = Metadata(MacContext(key))
    }
}
