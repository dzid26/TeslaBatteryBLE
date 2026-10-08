// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.ChargingStateKind
import com.dzid26.teslable.core.protocol.ShiftStateKind
import com.squareup.wire.ofEpochSecond
import com.tesla.generated.carserver.common.LatLong
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.ShiftState
import com.tesla.generated.vcsec.ClosureState_E
import com.tesla.generated.vcsec.ClosureStatuses
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    /** A drive reply the car stamped at 2000 s on its own clock, with every field set, an active route included. */
    private val driving =
        DriveState(
            shift_state = ShiftState(D = Void()),
            speed = 42,
            power = -7,
            timestamp = ofEpochSecond(2_000, 0),
            odometer_in_hundredths_of_a_mile = 1_234_567,
            speed_float = 42.4f,
            active_route_destination = "Test destination",
            active_route_minutes_to_arrival = 12.5f,
            active_route_miles_to_arrival = 6.25f,
            active_route_traffic_minutes_delay = 1.5f,
            active_route_energy_at_arrival = 61.5f,
            last_route_update = 1_790_000_000,
            last_traffic_update = ofEpochSecond(1_990, 0),
            active_route_coordinates = LatLong(latitude = 12.5f, longitude = 34.5f),
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

    private val connected = ConnectionEvent(state = ConnectionEvent.State.CONNECTED)

    private val disconnected = ConnectionEvent(state = ConnectionEvent.State.DISCONNECTED)

    @Test
    fun `envelope fields follow a reading's order on the wire`() {
        // Field numbers are the stored format (ADR-0008): time, VCSEC status,
        // charge, drive, then the signal strength (5) and the connection event
        // (6) added later. The logs are append-only, so renumbering would orphan
        // every record already written. The first byte of each encoding is the
        // field's tag.
        fun tag(record: BleRecord) = record.encode().first().toInt()

        val varint = 0
        val lengthDelimited = 2
        assertEquals(1 shl 3 or lengthDelimited, tag(BleRecord(acquired_at = ofEpochSecond(1, 0))))
        assertEquals(2 shl 3 or lengthDelimited, tag(BleRecord(vehicle_status = asleep)))
        assertEquals(3 shl 3 or lengthDelimited, tag(BleRecord(charge_state = charging)))
        assertEquals(4 shl 3 or lengthDelimited, tag(BleRecord(drive_state = DriveState())))
        assertEquals(5 shl 3 or varint, tag(BleRecord(rssi = -60)))
        assertEquals(6 shl 3 or lengthDelimited, tag(BleRecord(connection_event = connected)))
    }

    @Test
    fun `connection event states keep their numbers on the wire`() {
        // The state is field 1 of ConnectionEvent: tag 0x08, then the enum number.
        assertContentEquals(byteArrayOf(0x08, 1), ConnectionEvent(state = ConnectionEvent.State.CONNECTED).encode())
        assertContentEquals(byteArrayOf(0x08, 2), ConnectionEvent(state = ConnectionEvent.State.DISCONNECTED).encode())
    }

    @Test
    fun `a charge record maps onto the sample its raw charge state maps onto`() {
        val sample = BleRecord(acquired_at = ofEpochSecond(1_001, 0), charge_state = charging).toBatterySample(VEHICLE)
        assertEquals(charging.toBatterySample(VEHICLE), sample)
        assertEquals(77, sample?.batteryLevel)
        assertEquals(ChargingStateKind.Charging, sample?.chargingState)
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
    fun `a drive record maps onto a sample on the car's own clock`() {
        // The phone read the reply 90 s after the car stamped it: the sample
        // stays on the car's clock, and acquired_at does not move it.
        val record = BleRecord(acquired_at = ofEpochSecond(2_090, 0), drive_state = driving)
        val expected =
            DriveSample(
                timestampMillis = 2_000_000L,
                vehicleId = VEHICLE,
                shiftState = ShiftStateKind.D,
                speed = 42,
                power = -7,
                odometerInHundredthsOfAMile = 1_234_567,
            )
        assertEquals(expected, record.toDriveSample(VEHICLE))
    }

    @Test
    fun `a drive record maps without acquired_at`() {
        // The drive timeline is the car's own clock, so the phone's time is not needed to place a sample.
        assertEquals(2_000_000L, BleRecord(drive_state = driving).toDriveSample(VEHICLE)?.timestampMillis)
    }

    @Test
    fun `a parked drive record keeps absent fields absent`() {
        val parked = DriveState(shift_state = ShiftState(P = Void()), timestamp = ofEpochSecond(5, 0))
        val sample = BleRecord(drive_state = parked).toDriveSample(VEHICLE)
        val expected =
            DriveSample(
                timestampMillis = 5_000L,
                vehicleId = VEHICLE,
                shiftState = ShiftStateKind.P,
                speed = null,
                power = null,
                odometerInHundredthsOfAMile = null,
            )
        assertEquals(expected, sample)
    }

    @Test
    fun `a drive record without a car timestamp yields no drive sample`() {
        // acquired_at is never a stand-in for the car's own time (ADR-0006).
        val record = BleRecord(acquired_at = ofEpochSecond(2_090, 0), drive_state = driving.copy(timestamp = null))
        assertNull(record.toDriveSample(VEHICLE))
    }

    @Test
    fun `each payload feeds only its own sample`() {
        val acquiredAt = ofEpochSecond(1_001, 0)
        val charge = BleRecord(acquired_at = acquiredAt, charge_state = charging)
        val status = BleRecord(acquired_at = acquiredAt, vehicle_status = asleep)
        val drive = BleRecord(acquired_at = acquiredAt, drive_state = driving)
        val connection = BleRecord(acquired_at = acquiredAt, rssi = -70, connection_event = connected)
        assertNull(charge.toStatusSample("car"))
        assertNull(charge.toDriveSample("car"))
        assertNull(status.toBatterySample("car"))
        assertNull(status.toDriveSample("car"))
        assertNull(drive.toBatterySample("car"))
        assertNull(drive.toStatusSample("car"))
        // A connection event feeds no read model.
        assertNull(connection.toBatterySample("car"))
        assertNull(connection.toStatusSample("car"))
        assertNull(connection.toDriveSample("car"))
        val empty = BleRecord(acquired_at = acquiredAt)
        assertNull(empty.toBatterySample("car"))
        assertNull(empty.toDriveSample("car"))
    }

    @Test
    fun `mixed records round-trip through the log codec with their payloads intact`() {
        val records =
            listOf(
                BleRecord(acquired_at = ofEpochSecond(0, 0), vehicle_status = asleep),
                BleRecord(acquired_at = ofEpochSecond(1_090, 0), charge_state = charging),
                BleRecord(acquired_at = ofEpochSecond(1_800, 0), vehicle_status = inUse),
                BleRecord(acquired_at = ofEpochSecond(2_090, 0), drive_state = driving),
            )
        val decoded = ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER)
        assertEquals(records, decoded)
        // The drive reply comes back whole: the route fields and the destination survive.
        assertEquals(driving, decoded.last().drive_state)
    }

    @Test
    fun `connection events and signal strength round-trip through the log codec`() {
        val records =
            listOf(
                BleRecord(acquired_at = ofEpochSecond(10, 0), rssi = -72, connection_event = connected),
                BleRecord(acquired_at = ofEpochSecond(11, 0), rssi = -58, vehicle_status = asleep),
                BleRecord(acquired_at = ofEpochSecond(12, 0), rssi = -64, charge_state = charging),
                BleRecord(acquired_at = ofEpochSecond(13, 0), rssi = -80, connection_event = disconnected),
                // The phone had no reading yet.
                BleRecord(acquired_at = ofEpochSecond(14, 0), connection_event = connected),
            )
        val decoded = ProtoLog.decode(ProtoLog.encode(records), BleRecord.ADAPTER)
        assertEquals(records, decoded)
        assertEquals(-72, decoded[0].rssi)
        assertEquals(ConnectionEvent.State.CONNECTED, decoded[0].connection_event?.state)
        assertEquals(ConnectionEvent.State.DISCONNECTED, decoded[3].connection_event?.state)
        assertNull(decoded[4].rssi)
    }

    @Test
    fun `a record without a signal strength reads it as absent`() {
        // A record from before rssi existed, or from a phone with no reading, has no field 5.
        val withoutSignal = BleRecord(acquired_at = ofEpochSecond(5, 0), vehicle_status = asleep)
        val decoded = ProtoLog.decode(ProtoLog.encode(listOf(withoutSignal)), BleRecord.ADAPTER).single()
        assertNull(decoded.rssi)
        assertEquals(withoutSignal, decoded)
        // A reading of 0 dBm is a reading, not an absence.
        assertEquals(0, BleRecord.ADAPTER.decode(BleRecord(rssi = 0).encode()).rssi)
    }

    @Test
    fun `a payload kind from a newer version reads as a record without a known payload`() {
        // A newer writer's record: field 7, a payload kind added after connection_event, beside acquired_at.
        val fieldSeven = byteArrayOf(0x3A, 0x02, 0x08, 0x01)
        val record = BleRecord.ADAPTER.decode(BleRecord(acquired_at = ofEpochSecond(5, 0)).encode() + fieldSeven)
        assertEquals(ofEpochSecond(5, 0), record.acquired_at)
        assertNull(record.toBatterySample("car"))
        assertNull(record.toStatusSample("car"))
        assertNull(record.toDriveSample("car"))
    }

    private companion object {
        const val VEHICLE = "Se1f0941734830fe7C"
    }
}
