// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.protocol

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.ClosuresState
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
    fun `reads every charging state`() {
        fun kind(state: ChargeState.ChargingState) = ChargeState(charging_state = state).chargingStateKind

        assertEquals(ChargingStateKind.Charging, kind(ChargeState.ChargingState(Charging = Void())))
        assertEquals(ChargingStateKind.Complete, kind(ChargeState.ChargingState(Complete = Void())))
        assertEquals(ChargingStateKind.Stopped, kind(ChargeState.ChargingState(Stopped = Void())))
        assertEquals(ChargingStateKind.Disconnected, kind(ChargeState.ChargingState(Disconnected = Void())))
        assertEquals(ChargingStateKind.NoPower, kind(ChargeState.ChargingState(NoPower = Void())))
        assertEquals(ChargingStateKind.Starting, kind(ChargeState.ChargingState(Starting = Void())))
        assertEquals(ChargingStateKind.Calibrating, kind(ChargeState.ChargingState(Calibrating = Void())))
        assertEquals(ChargingStateKind.Unknown, kind(ChargeState.ChargingState(Unknown = Void())))
    }

    @Test
    fun `an unset charging state has no kind`() {
        assertNull(ChargeState(charging_state = ChargeState.ChargingState()).chargingStateKind)
        assertNull(ChargeState().chargingStateKind)
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
    fun `reads every shift state`() {
        fun kind(state: ShiftState) = DriveState(shift_state = state).shiftStateKind

        assertEquals(ShiftStateKind.P, kind(ShiftState(P = Void())))
        assertEquals(ShiftStateKind.R, kind(ShiftState(R = Void())))
        assertEquals(ShiftStateKind.N, kind(ShiftState(N = Void())))
        assertEquals(ShiftStateKind.D, kind(ShiftState(D = Void())))
        assertEquals(ShiftStateKind.Invalid, kind(ShiftState(CarServer_Invalid = Void())))
        assertEquals(ShiftStateKind.SNA, kind(ShiftState(SNA = Void())))
    }

    @Test
    fun `an unset shift state has no kind`() {
        assertNull(DriveState(shift_state = ShiftState()).shiftStateKind)
        assertNull(DriveState().shiftStateKind)
    }

    @Test
    fun `the enums have a value for each member of the cars oneofs`() {
        // The counts match the oneofs in vehicle.proto (8 charging states, 6 gears); the mappings above name every one.
        assertEquals(8, ChargingStateKind.entries.size)
        assertEquals(6, ShiftStateKind.entries.size)
    }

    @Test
    fun `every sentry state but off is sentry on`() {
        fun sentry(state: ClosuresState.SentryModeState?) = ClosuresState(sentry_mode_state = state).sentryOn

        assertFalse(sentry(ClosuresState.SentryModeState(Off = Void())))
        assertTrue(sentry(ClosuresState.SentryModeState(Idle = Void())))
        assertTrue(sentry(ClosuresState.SentryModeState(Armed = Void())))
        assertTrue(sentry(ClosuresState.SentryModeState(Aware = Void())))
        assertTrue(sentry(ClosuresState.SentryModeState(Panic = Void())))
        assertTrue(sentry(ClosuresState.SentryModeState(Quiet = Void())))
    }

    @Test
    fun `a closures state without a sentry state is sentry off`() {
        // Sentry unsupported, the field left out, or an empty message.
        assertFalse(ClosuresState().sentryOn)
        assertFalse(ClosuresState(sentry_mode_state = ClosuresState.SentryModeState()).sentryOn)
    }

    @Test
    fun `the climate is on when the car says so`() {
        assertTrue(ClimateState(is_climate_on = true).climateOn)
        assertFalse(ClimateState(is_climate_on = false).climateOn)
    }

    @Test
    fun `a climate keeper mode other than off keeps the climate on`() {
        fun keeper(mode: ClimateState.ClimateKeeperMode) = ClimateState(is_climate_on = false, climate_keeper_mode = mode).climateOn

        assertTrue(keeper(ClimateState.ClimateKeeperMode(On = Void())))
        assertTrue(keeper(ClimateState.ClimateKeeperMode(Dog = Void())))
        assertTrue(keeper(ClimateState.ClimateKeeperMode(Party = Void())))
        assertFalse(keeper(ClimateState.ClimateKeeperMode(Off = Void())))
        assertFalse(keeper(ClimateState.ClimateKeeperMode(Unknown = Void())))
        assertFalse(keeper(ClimateState.ClimateKeeperMode()))
    }

    @Test
    fun `a climate state without the flags is climate off`() {
        assertFalse(ClimateState().climateOn)
    }
}
