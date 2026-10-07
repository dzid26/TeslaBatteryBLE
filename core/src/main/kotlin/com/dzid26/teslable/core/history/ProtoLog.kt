// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
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
        val messages = mutableListOf<M>()
        val reader = ProtoReader(Buffer().write(bytes))
        while (true) {
            val length = runCatching { reader.nextLengthDelimited() }.getOrNull() ?: break
            if (length == 0) break
            val payload = runCatching { reader.readBytes() }.getOrNull() ?: break
            messages += runCatching { adapter.decode(payload) }.getOrNull() ?: break
        }
        return messages
    }
}
