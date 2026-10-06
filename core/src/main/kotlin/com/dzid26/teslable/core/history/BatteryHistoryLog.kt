// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.tesla.generated.carserver.vehicle.ChargeState
import java.io.ByteArrayOutputStream

/**
 * Length-delimited codec for the append-only history log (ADR-0006).
 *
 * Each record is a varint byte length followed by the car's raw [ChargeState].
 * There is no envelope: the record is exactly what the car reported. Readers
 * ignore unknown fields, so new car fields never drop old rows. A truncated
 * trailing record (an interrupted append) is ignored on load; records after
 * it are not read.
 */
object BatteryHistoryLog {
    /** Encodes all [records] into one buffer, for a full rewrite. */
    fun encode(records: List<ChargeState>): ByteArray {
        val out = ByteArrayOutputStream()
        for (record in records) {
            out.write(encodeFrame(record))
        }
        return out.toByteArray()
    }

    /** Encodes one record as a length-delimited frame, for an append. */
    fun encodeFrame(record: ChargeState): ByteArray {
        val payload = record.encode()
        val out = ByteArrayOutputStream()
        writeVarint(payload.size, out)
        out.write(payload)
        return out.toByteArray()
    }

    /** Reads every complete record; stops at a truncated or unreadable tail. */
    fun decode(bytes: ByteArray): List<ChargeState> {
        val records = mutableListOf<ChargeState>()
        var offset = 0
        while (offset < bytes.size) {
            val (length, next) = readVarint(bytes, offset) ?: break
            if (length <= 0 || next + length > bytes.size) break
            val record =
                runCatching {
                    ChargeState.ADAPTER.decode(bytes.copyOfRange(next, next + length))
                }.getOrNull() ?: break
            records += record
            offset = next + length
        }
        return records
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
