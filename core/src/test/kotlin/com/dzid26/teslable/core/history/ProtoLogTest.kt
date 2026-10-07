// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
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

    private fun frame(payload: ByteArray): ByteArray {
        var length = payload.size
        val prefix = mutableListOf<Byte>()
        while (length >= 0x80) {
            prefix += ((length and 0x7F) or 0x80).toByte()
            length = length ushr 7
        }
        prefix += length.toByte()
        return prefix.toByteArray() + payload
    }
}
