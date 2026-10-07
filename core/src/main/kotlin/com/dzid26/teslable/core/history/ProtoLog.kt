// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import java.io.ByteArrayOutputStream

/**
 * Length-delimited codec for append-only logs of Tesla messages (ADR-0006).
 *
 * Each record is a varint byte length followed by one raw Wire message. There
 * is no envelope: the record is exactly what the car reported. Readers ignore
 * unknown fields, so new car fields never drop old rows. A truncated trailing
 * record (an interrupted append) is ignored on load; records after it are not
 * read. The same codec frames every message type; charge and (later) drive
 * records differ only in the adapter passed to [decode].
 */
object ProtoLog {
    /** Encodes all [messages] into one buffer, for a full rewrite. */
    fun encode(messages: List<Message<*, *>>): ByteArray {
        val out = ByteArrayOutputStream()
        for (message in messages) {
            out.write(encodeFrame(message))
        }
        return out.toByteArray()
    }

    /** Encodes one message as a length-delimited frame, for an append. */
    fun encodeFrame(message: Message<*, *>): ByteArray {
        val payload = message.encode()
        val out = ByteArrayOutputStream()
        writeVarint(payload.size, out)
        out.write(payload)
        return out.toByteArray()
    }

    /** Reads every complete record with [adapter]; stops at a truncated or unreadable tail. */
    fun <M : Message<M, *>> decode(
        bytes: ByteArray,
        adapter: ProtoAdapter<M>,
    ): List<M> {
        val messages = mutableListOf<M>()
        var offset = 0
        while (offset < bytes.size) {
            val (length, next) = readVarint(bytes, offset) ?: break
            if (length <= 0 || next + length > bytes.size) break
            val message =
                runCatching {
                    adapter.decode(bytes.copyOfRange(next, next + length))
                }.getOrNull() ?: break
            messages += message
            offset = next + length
        }
        return messages
    }

    private fun writeVarint(
        value: Int,
        out: ByteArrayOutputStream,
    ) {
        var remaining = value
        while (remaining >= VARINT_CONTINUATION) {
            out.write((remaining and VARINT_PAYLOAD) or VARINT_CONTINUATION)
            remaining = remaining ushr VARINT_SHIFT
        }
        out.write(remaining)
    }

    /** Returns the value and the offset after it, or null when truncated. */
    private fun readVarint(
        bytes: ByteArray,
        start: Int,
    ): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var offset = start
        while (offset < bytes.size && shift < VARINT_MAX_BITS) {
            val byte = bytes[offset].toInt()
            value = value or ((byte and VARINT_PAYLOAD) shl shift)
            offset++
            if (byte and VARINT_CONTINUATION == 0) return value to offset
            shift += VARINT_SHIFT
        }
        return null
    }

    private const val VARINT_CONTINUATION = 0x80
    private const val VARINT_PAYLOAD = 0x7F
    private const val VARINT_SHIFT = 7
    private const val VARINT_MAX_BITS = 32
}
