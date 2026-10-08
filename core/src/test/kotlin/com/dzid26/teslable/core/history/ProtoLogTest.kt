// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.squareup.wire.ProtoWriter
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ProtoLogTest {
    private fun record(seconds: Long) =
        ChargeState(
            charging_state = ChargeState.ChargingState(Charging = Void()),
            charge_limit_soc = 85,
            battery_range = 206.61f,
            est_battery_range = 200.5f,
            ideal_battery_range = 220.25f,
            battery_level = 77,
            usable_battery_level = 77,
            charge_energy_added = 12.3f,
            charge_miles_added_rated = 41.5f,
            charge_miles_added_ideal = 45.25f,
            charger_voltage = 240,
            charger_power = 7200,
            charge_rate_mph = 32,
            charging_amps = 30,
            charge_rate_mph_float = 32.5f,
            timestamp = Instant.ofEpochSecond(seconds),
        )

    @Test
    fun roundTripsAllFields() {
        val records = listOf(record(0), record(60))
        assertEquals(records, ProtoLog.decode(ProtoLog.encode(records), ChargeState.ADAPTER))
    }

    @Test
    fun roundTripsSparseRecords() {
        val sparse =
            ChargeState(
                battery_level = 50,
                timestamp = Instant.ofEpochSecond(1),
            )
        assertEquals(listOf(sparse), ProtoLog.decode(ProtoLog.encode(listOf(sparse)), ChargeState.ADAPTER))
    }

    @Test
    fun emptyLogRoundTrips() {
        assertEquals(
            emptyList<ChargeState>(),
            ProtoLog.decode(ProtoLog.encode(emptyList()), ChargeState.ADAPTER),
        )
    }

    @Test
    fun ignoresATruncatedTrailingRecord() {
        val bytes = ProtoLog.encode(listOf(record(0), record(60)))
        assertEquals(listOf(record(0)), ProtoLog.decode(bytes.copyOf(bytes.size - 3), ChargeState.ADAPTER))
    }

    @Test
    fun ignoresATruncatedTrailingVarint() {
        val bytes = ProtoLog.encode(listOf(record(0)))
        assertEquals(listOf(record(0)), ProtoLog.decode(bytes + byteArrayOf(0x80.toByte()), ChargeState.ADAPTER))
    }

    @Test
    fun keepsKnownFieldsWhenANewerWriterAddsAField() {
        val sparse =
            ChargeState(
                battery_level = 50,
                timestamp = Instant.ofEpochSecond(1),
            )
        val bytes = ProtoLog.encode(listOf(sparse))
        assertTrue(bytes[0].toInt() in 1..0x7F)
        val payload = bytes.copyOfRange(1, bytes.size)
        // Tag 99, varint 1: a field this reader does not know.
        val extended = payload + byteArrayOf(0x98.toByte(), 0x06, 0x01)
        val decoded = ProtoLog.decode(frame(extended), ChargeState.ADAPTER).single()
        assertEquals(sparse.battery_level, decoded.battery_level)
        assertEquals(sparse.timestamp, decoded.timestamp)
    }

    @Test
    fun framesOtherMessageTypes() {
        val drive =
            DriveState(
                speed = 60,
                power = 120,
                timestamp = Instant.ofEpochSecond(2),
            )
        assertEquals(listOf(drive), ProtoLog.decode(ProtoLog.encode(listOf(drive)), DriveState.ADAPTER))
    }

    @Test
    fun framesRecordsBeyondOneByteLengths() {
        val drive =
            DriveState(
                speed = 60,
                active_route_destination = "x".repeat(200),
                timestamp = Instant.ofEpochSecond(2),
            )
        val bytes = ProtoLog.encode(listOf(drive))
        val payloadSize = drive.encode().size
        assertTrue(payloadSize > 0x7F)
        assertEquals(payloadSize + 2, bytes.size)
        // Two-byte varint: continuation bit set on the first byte only, and
        // the two 7-bit groups reassemble to the payload size.
        assertTrue(bytes[0] < 0)
        assertTrue(bytes[1] >= 0)
        assertEquals(payloadSize, (bytes[0].toInt() and 0x7F) or ((bytes[1].toInt() and 0x7F) shl 7))
        assertEquals(listOf(drive), ProtoLog.decode(bytes, DriveState.ADAPTER))
    }

    @Test
    fun appendedFramesConcatenate() {
        // The store appends frames without re-encoding the log; separately
        // encoded frames must decode as one stream.
        val bytes = ProtoLog.encodeFrame(record(0)) + ProtoLog.encodeFrame(record(60))
        assertEquals(listOf(record(0), record(60)), ProtoLog.decode(bytes, ChargeState.ADAPTER))
    }

    @Test
    fun frameLayoutMatchesTheLibraryPrefix() {
        val payload = record(0).encode()
        val expected = Buffer()
        ProtoWriter(expected).writeVarint32(payload.size)
        expected.write(payload)
        assertEquals(expected.readByteArray().toList(), ProtoLog.encodeFrame(record(0)).toList())
    }

    @Test
    fun ignoresAGarbledPayload() {
        // A plausible length followed by bytes no message decodes from.
        val garbled = frame(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        assertEquals(emptyList<ChargeState>(), ProtoLog.decode(garbled, ChargeState.ADAPTER))
    }

    @Test
    fun skipsAnUndecodableRecordAndReadsOn() {
        // A complete frame no message decodes from (an incompatible test
        // build's record, or a damaged one) must not hide the records after it.
        val garbled = frame(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        val bytes = ProtoLog.encodeFrame(record(0)) + garbled + ProtoLog.encodeFrame(record(60))
        assertEquals(listOf(record(0), record(60)), ProtoLog.decode(bytes, ChargeState.ADAPTER))
    }

    @Test
    fun skipsEmptyFrames() {
        // Zero bytes (a zero-length frame each), as a filesystem can leave
        // after a power loss, are skipped like empty records.
        val bytes = ProtoLog.encodeFrame(record(0)) + ByteArray(3) + ProtoLog.encodeFrame(record(60))
        assertEquals(listOf(record(0), record(60)), ProtoLog.decode(bytes, ChargeState.ADAPTER))
    }

    @Test
    fun ignoresImpossibleLengths() {
        // Lengths far beyond the buffer, at Int32 max (whose end offset
        // overflows), and negative after overflow: none may throw, none
        // yields records.
        val beyondBuffer = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F) + byteArrayOf(0x01)
        val intMax = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07) + byteArrayOf(0x01)
        val negative = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F) + byteArrayOf(0x01)
        for (bytes in listOf(beyondBuffer, intMax, negative)) {
            assertEquals(emptyList<ChargeState>(), ProtoLog.decode(bytes, ChargeState.ADAPTER))
        }
    }

    private fun frame(payload: ByteArray): ByteArray {
        val frame = Buffer()
        ProtoWriter(frame).writeVarint32(payload.size)
        frame.write(payload)
        return frame.readByteArray()
    }
}
