// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoWriter
import okio.Buffer

/**
 * Length-delimited codec for append-only logs of Tesla messages (ADR-0006).
 *
 * Each record is a varint byte length (written by Wire's [ProtoWriter],
 * which is the same encoding protobuf itself uses for tags and lengths)
 * followed by one raw Wire message. There is no envelope: the record is
 * exactly what the car reported. Readers ignore unknown fields, so new car
 * fields never drop old rows. A truncated trailing record (an interrupted
 * append) is ignored on load; records after it are not read. The same codec
 * frames every message type; charge and (later) drive records differ only in
 * the adapter passed to [decode].
 */
object ProtoLog {
    /** Encodes all [messages] into one buffer, for a full rewrite. */
    fun encode(messages: List<Message<*, *>>): ByteArray {
        val out = Buffer()
        for (message in messages) {
            out.write(encodeFrame(message))
        }
        return out.readByteArray()
    }

    /** Encodes one message as a length-delimited frame, for an append. */
    fun encodeFrame(message: Message<*, *>): ByteArray {
        val payload = message.encode()
        val frame = Buffer()
        ProtoWriter(frame).writeVarint32(payload.size)
        frame.write(payload)
        return frame.readByteArray()
    }

    /** Reads every complete record with [adapter]; stops at a truncated or unreadable tail. */
    fun <M : Message<M, *>> decode(
        bytes: ByteArray,
        adapter: ProtoAdapter<M>,
    ): List<M> {
        // Positions are tracked here, not on the buffer: Wire's ProtoReader
        // buffers ahead, so sharing one reader with direct buffer reads drops
        // records, and its readBytes() does not read standalone frames. The
        // length prefix is parsed below; the payload itself still decodes
        // through the message adapter.
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
