// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.Digest
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256

interface HashContext {
    fun update(data: ByteArray)

    fun digest(): ByteArray
}

class MessageDigestContext(
    private val sha256: Digest = CryptographyProvider.Default.get(SHA256),
) : HashContext {
    private val buffer = Buffer()

    override fun update(data: ByteArray) = buffer.append(data)

    override fun digest(): ByteArray = sha256.hasher().hashBlocking(buffer.bytes())
}

class MacContext(
    key: ByteArray,
) : HashContext {
    private val hmacKey =
        CryptographyProvider.Default
            .get(HMAC)
            .keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
    private val buffer = Buffer()

    override fun update(data: ByteArray) = buffer.append(data)

    override fun digest(): ByteArray = hmacKey.signatureGenerator().generateSignatureBlocking(buffer.bytes())
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

        fun sha256(): Metadata = Metadata(MessageDigestContext())

        fun hmacSha256(key: ByteArray): Metadata = Metadata(MacContext(key))
    }
}

private class Buffer {
    private val chunks = mutableListOf<ByteArray>()
    private var size = 0

    fun append(data: ByteArray) {
        chunks.add(data)
        size += data.size
    }

    fun bytes(): ByteArray {
        val out = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }
}
