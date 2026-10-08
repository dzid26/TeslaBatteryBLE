// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.squareup.wire.ofEpochSecond
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusLogGateTest {
    private val car = "S0123456789abcdefC"
    private val gate = StatusLogGate()

    private val asleep =
        VehicleStatus(
            vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
            vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
        )

    /** A status reading acquired [seconds] after the epoch. */
    private fun reading(
        seconds: Long,
        status: VehicleStatus = asleep,
    ) = BleRecord(acquired_at = ofEpochSecond(seconds, 0), vehicle_status = status)

    @Test
    fun `the first reading is admitted`() {
        assertEquals(reading(0), gate.admit(car, reading(0), firstAfterConnect = true))
    }

    @Test
    fun `an unchanged reading within the heartbeat is held back`() {
        gate.admit(car, reading(0), firstAfterConnect = true)
        assertNull(gate.admit(car, reading(10), firstAfterConnect = false))
    }

    @Test
    fun `losing the link hands over the newest held-back reading once`() {
        gate.admit(car, reading(0), firstAfterConnect = true)
        gate.admit(car, reading(10), firstAfterConnect = false)
        gate.admit(car, reading(20), firstAfterConnect = false)

        assertEquals(reading(20), gate.linkLost(car))
        assertNull(gate.linkLost(car))
    }

    @Test
    fun `losing the link after a logged reading adds nothing`() {
        gate.admit(car, reading(0), firstAfterConnect = true)
        assertNull(gate.linkLost(car))
    }

    @Test
    fun `the first reading after a reconnect is admitted again`() {
        gate.admit(car, reading(0), firstAfterConnect = true)
        gate.admit(car, reading(10), firstAfterConnect = false)
        gate.linkLost(car)

        assertEquals(reading(30), gate.admit(car, reading(30), firstAfterConnect = true))
    }

    @Test
    fun `a seeded record counts as the last logged one`() {
        gate.seed(car, reading(0))
        assertNull(gate.admit(car, reading(10), firstAfterConnect = false))
    }

    @Test
    fun `vehicles are gated separately`() {
        gate.admit(car, reading(0), firstAfterConnect = true)
        assertEquals(reading(10), gate.admit("S89abcdef01234567C", reading(10), firstAfterConnect = false))
    }

    @Test
    fun `a record without a status or a time is never logged`() {
        assertNull(gate.admit(car, BleRecord(acquired_at = ofEpochSecond(0, 0)), firstAfterConnect = true))
        assertNull(gate.admit(car, BleRecord(vehicle_status = asleep), firstAfterConnect = true))
        assertNull(gate.linkLost(car))
    }
}
