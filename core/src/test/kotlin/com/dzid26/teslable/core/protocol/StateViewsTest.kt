// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.protocol

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.ShiftState
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The derived values of `StateViews.kt`: each rule is defined once, so each is pinned here. */
class StateViewsTest {
    @Test
    fun `only a fully unlocked car is not locked`() {
        fun locked(state: VehicleLockState_E) = VehicleStatus(vehicleLockState = state).locked

        assertFalse(locked(VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED))
        assertTrue(locked(VehicleLockState_E.VEHICLELOCKSTATE_LOCKED))
        assertTrue(locked(VehicleLockState_E.VEHICLELOCKSTATE_INTERNAL_LOCKED))
        assertTrue(locked(VehicleLockState_E.VEHICLELOCKSTATE_SELECTIVE_UNLOCKED))
    }

    @Test
    fun `only an asleep status is asleep`() {
        fun asleep(status: VehicleSleepStatus_E) = VehicleStatus(vehicleSleepStatus = status).asleep

        assertTrue(asleep(VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP))
        assertFalse(asleep(VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE))
        assertFalse(asleep(VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_UNKNOWN))
    }

    @Test
    fun `only a present user is present`() {
        fun present(presence: UserPresence_E) = VehicleStatus(userPresence = presence).userPresent

        assertTrue(present(UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT))
        assertFalse(present(UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT))
        assertFalse(present(UserPresence_E.VEHICLE_USER_PRESENCE_UNKNOWN))
    }

    @Test
    fun `a status without any of the flags reads as unlocked, awake and away`() {
        // The proto defaults: unlocked is the lock state's zero value, unknown sleep and presence are not asleep or present.
        val empty = VehicleStatus()
        assertFalse(empty.locked)
        assertFalse(empty.asleep)
        assertFalse(empty.userPresent)
    }

    @Test
    fun `names every charging state`() {
        fun name(state: ChargeState.ChargingState) = ChargeState(charging_state = state).chargingStateName

        assertEquals("Charging", name(ChargeState.ChargingState(Charging = Void())))
        assertEquals("Complete", name(ChargeState.ChargingState(Complete = Void())))
        assertEquals("Stopped", name(ChargeState.ChargingState(Stopped = Void())))
        assertEquals("Disconnected", name(ChargeState.ChargingState(Disconnected = Void())))
        assertEquals("NoPower", name(ChargeState.ChargingState(NoPower = Void())))
        assertEquals("Starting", name(ChargeState.ChargingState(Starting = Void())))
        assertEquals("Calibrating", name(ChargeState.ChargingState(Calibrating = Void())))
        assertEquals("Unknown", name(ChargeState.ChargingState(Unknown = Void())))
    }

    @Test
    fun `an unset charging state has no name`() {
        assertNull(ChargeState(charging_state = ChargeState.ChargingState()).chargingStateName)
        assertNull(ChargeState().chargingStateName)
    }

    @Test
    fun `the charging slope falls back to the int rate`() {
        fun mph(
            rate: Int? = null,
            rateFloat: Float? = null,
        ) = ChargeState(charge_rate_mph = rate, charge_rate_mph_float = rateFloat).chargingMph

        assertEquals(32.5f, mph(rate = 32, rateFloat = 32.5f))
        assertEquals(32f, mph(rate = 32, rateFloat = 0f))
        assertEquals(32f, mph(rate = 32))
        assertEquals(30f, mph(rate = 30, rateFloat = Float.NaN))
        assertNull(mph(rate = 0))
        assertNull(mph())
    }

    @Test
    fun `names every shift state`() {
        fun name(state: ShiftState) = DriveState(shift_state = state).shiftStateName

        assertEquals("P", name(ShiftState(P = Void())))
        assertEquals("R", name(ShiftState(R = Void())))
        assertEquals("N", name(ShiftState(N = Void())))
        assertEquals("D", name(ShiftState(D = Void())))
        assertEquals("Invalid", name(ShiftState(CarServer_Invalid = Void())))
        assertEquals("SNA", name(ShiftState(SNA = Void())))
    }

    @Test
    fun `an unset shift state has no name`() {
        assertNull(DriveState(shift_state = ShiftState()).shiftStateName)
        assertNull(DriveState().shiftStateName)
    }
}
