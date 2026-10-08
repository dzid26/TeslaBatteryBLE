// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.squareup.wire.ofEpochSecond
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleRecordMappingTest {
    /** A charge reply the car stamped at 1000 s on its own clock. */
    private val charging =
        ChargeState(
            charging_state = ChargeState.ChargingState(Charging = Void()),
            charge_limit_soc = 85,
            battery_range = 206.61f,
            battery_level = 77,
            usable_battery_level = 77,
            charge_rate_mph_float = 32.5f,
            timestamp = ofEpochSecond(1_000, 0),
        )

    private val inUse =
        VehicleStatus(
            closureStatuses = ClosureStatuses(frontDriverDoor = ClosureState_E.CLOSURESTATE_OPEN),
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT,
        )

    private val asleep =
        VehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
        )

    @Test
    fun `envelope fields follow a reading's order on the wire`() {
        // Field numbers are the stored format (ADR-0008): time, VCSEC status,
        // charge. The first byte of each encoding is the field's tag.
        fun tag(record: BleRecord) = record.encode().first().toInt()

        val lengthDelimited = 2
        assertEquals(1 shl 3 or lengthDelimited, tag(BleRecord(acquired_at = ofEpochSecond(1, 0))))
        assertEquals(2 shl 3 or lengthDelimited, tag(BleRecord(vehicle_status = asleep)))
        assertEquals(3 shl 3 or lengthDelimited, tag(BleRecord(charge_state = charging)))
    }

    @Test
    fun `a charge record maps onto the sample its raw charge state maps onto`() {
        val sample = BleRecord(acquired_at = ofEpochSecond(1_001, 0), charge_state = charging).toBatterySample(VEHICLE)
        assertEquals(charging.toBatterySample(VEHICLE), sample)
        assertEquals(77, sample?.batteryLevel)
        assertEquals("Charging", sample?.chargingState)
    }

    @Test
    fun `a charge record keeps the car's timestamp as its timeline time`() {
        // The phone read the reply 90 s after the car stamped it: the sample
        // stays on the car's clock, and acquired_at does not move it.
        val record = BleRecord(acquired_at = ofEpochSecond(1_090, 0), charge_state = charging)
        assertEquals(1_000_000L, record.toBatterySample(VEHICLE)?.timestampMillis)
    }

    @Test
    fun `a charge record maps without acquired_at`() {
        // The charge timeline is the car's own clock, so the phone's time is not needed to place a sample.
        assertEquals(charging.toBatterySample(VEHICLE), BleRecord(charge_state = charging).toBatterySample(VEHICLE))
    }

    @Test
    fun `a charge record without a car timestamp yields no battery sample`() {
        // acquired_at is never a stand-in for the car's own time (ADR-0006).
        val acquiredAt = ofEpochSecond(1_090, 0)
        assertNull(BleRecord(acquired_at = acquiredAt, charge_state = charging.copy(timestamp = null)).toBatterySample(VEHICLE))
        assertNull(BleRecord(acquired_at = acquiredAt, charge_state = charging.copy(battery_level = null)).toBatterySample(VEHICLE))
    }

    @Test
    fun `a status record maps onto a sample at the phone's acquisition time`() {
        val record = BleRecord(acquired_at = ofEpochSecond(1, 500_000_000), vehicle_status = inUse)
        val expected =
            StatusSample(
                timestampMillis = 1_500L,
                vehicleId = VEHICLE,
                asleep = false,
                userPresent = true,
                locked = false,
            )
        assertEquals(expected, record.toStatusSample(VEHICLE))
    }

    @Test
    fun `a sleeping car maps onto asleep, locked and away`() {
        val sample = BleRecord(acquired_at = ofEpochSecond(60, 0), vehicle_status = asleep).toStatusSample("car")
        assertEquals(StatusSample(60_000L, "car", asleep = true, userPresent = false, locked = true), sample)
    }

    @Test
    fun `a status sample needs a status and acquired_at`() {
        assertNull(BleRecord(acquired_at = ofEpochSecond(1, 0)).toStatusSample("car"))
        assertNull(BleRecord(vehicle_status = asleep).toStatusSample("car"))
    }

    @Test
    fun `each payload feeds only its own sample`() {
        val charge = BleRecord(acquired_at = ofEpochSecond(1_001, 0), charge_state = charging)
        val status = BleRecord(acquired_at = ofEpochSecond(1_001, 0), vehicle_status = asleep)
        assertNull(charge.toStatusSample("car"))
        assertNull(status.toBatterySample("car"))
        assertNull(BleRecord(acquired_at = ofEpochSecond(1_001, 0)).toBatterySample("car"))
    }

    @Test
    fun `mixed records round-trip through the log codec with their payloads intact`() {
        val records =
            listOf(
                BleRecord(acquired_at = ofEpochSecond(0, 0), vehicle_status = asleep),
                BleRecord(acquired_at = ofEpochSecond(1_090, 0), charge_state = charging),
                BleRecord(acquired_at = ofEpochSecond(1_800, 0), vehicle_status = inUse),
            )
        assertEquals(records, ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER))
    }

    @Test
    fun `a payload kind from a newer version reads as a record without a known payload`() {
        // A newer writer's record: field 4, a payload kind added later, beside acquired_at.
        val fieldFour = byteArrayOf(0x22, 0x02, 0x08, 0x01)
        val record = BleRecord.ADAPTER.decode(BleRecord(acquired_at = ofEpochSecond(5, 0)).encode() + fieldFour)
        assertEquals(ofEpochSecond(5, 0), record.acquired_at)
        assertNull(record.toBatterySample("car"))
        assertNull(record.toStatusSample("car"))
    }

    private companion object {
        const val VEHICLE = "Se1f0941734830fe7C"
    }
}
