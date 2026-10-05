// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHistoryLogTest {
    private fun sample(minutes: Long) =
        BatterySample(
            timestampMillis = minutes * 60_000L,
            batteryLevel = 77,
            chargingState = "Charging",
            chargeLimit = 85,
            vehicleId = "Se1f0941734830fe7C",
            usableBatteryLevel = 77,
            ratedRangeMiles = 206.61f,
            estRangeMiles = 200.5f,
            idealRangeMiles = 220.25f,
            chargeEnergyAdded = 12.3f,
            chargeMilesAddedRated = 41.5f,
            chargeMilesAddedIdeal = 45.25f,
            chargeRateMph = 32,
            chargeRateMphFloat = 32.5f,
        )

    @Test
    fun roundTripsAllFields() {
        val samples = listOf(sample(0), sample(1))
        assertEquals(samples, BatteryHistoryLog.decode(BatteryHistoryLog.encode(samples)))
    }

    @Test
    fun roundTripsSparseSamples() {
        val sparse =
            BatterySample(
                timestampMillis = 1L,
                batteryLevel = 50,
                chargingState = null,
                chargeLimit = null,
            )
        assertEquals(listOf(sparse), BatteryHistoryLog.decode(BatteryHistoryLog.encode(listOf(sparse))))
    }

    @Test
    fun emptyLogRoundTrips() {
        assertEquals(emptyList<BatterySample>(), BatteryHistoryLog.decode(BatteryHistoryLog.encode(emptyList())))
    }

    @Test
    fun ignoresATruncatedTrailingRecord() {
        val bytes = BatteryHistoryLog.encode(listOf(sample(0), sample(1)))
        assertEquals(listOf(sample(0)), BatteryHistoryLog.decode(bytes.copyOf(bytes.size - 3)))
    }

    @Test
    fun ignoresATruncatedTrailingVarint() {
        val bytes = BatteryHistoryLog.encode(listOf(sample(0)))
        assertEquals(listOf(sample(0)), BatteryHistoryLog.decode(bytes + byteArrayOf(0x80.toByte())))
    }

    @Test
    fun keepsWorkingWhenANewerWriterAddsAField() {
        val sparse =
            BatterySample(
                timestampMillis = 1L,
                batteryLevel = 50,
                chargingState = null,
                chargeLimit = null,
            )
        val bytes = BatteryHistoryLog.encode(listOf(sparse))
        assertTrue(bytes[0].toInt() in 1..0x7F)
        val payload = bytes.copyOfRange(1, bytes.size)
        // Tag 99, varint 1: a field this reader does not know.
        val extended = payload + byteArrayOf(0x98.toByte(), 0x06, 0x01)
        assertEquals(listOf(sparse), BatteryHistoryLog.decode(frame(extended)))
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
