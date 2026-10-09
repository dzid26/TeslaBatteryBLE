// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.protocol

import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.ClosuresState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus

// What the app derives from the car's raw replies, each rule defined once, here.
// The replies themselves are the live state: the parsers return the Wire message
// as the car sent it, and plain fields are read straight off it
// (`charge.battery_level`). Only a value that needs a rule, such as an enum or a
// fallback, gets a property below, and everything that wants it (the
// screens, the debug log, the history samples) uses these.

/** Locked unless fully unlocked: locked, internally locked and selectively unlocked all count. */
val VehicleStatus.locked: Boolean
    get() = vehicleLockState != VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED

/** The car says it is asleep; an unknown sleep status counts as awake. */
val VehicleStatus.asleep: Boolean
    get() = vehicleSleepStatus == VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP

/** Someone is in the car (`userPresence`), so it may be driving; an unknown presence counts as away. */
val VehicleStatus.userPresent: Boolean
    get() = userPresence == UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT

/** The car's charging state, or null when it sent none (or an empty one). */
val ChargeState.chargingStateKind: ChargingStateKind?
    get() {
        val state = charging_state ?: return null
        return when {
            state.Charging != null -> ChargingStateKind.Charging
            state.Complete != null -> ChargingStateKind.Complete
            state.Stopped != null -> ChargingStateKind.Stopped
            state.Disconnected != null -> ChargingStateKind.Disconnected
            state.NoPower != null -> ChargingStateKind.NoPower
            state.Starting != null -> ChargingStateKind.Starting
            state.Calibrating != null -> ChargingStateKind.Calibrating
            state.Unknown != null -> ChargingStateKind.Unknown
            else -> null
        }
    }

/** Charging slope in miles per hour: the float rate when plausible, else the int rate. */
val ChargeState.chargingMph: Float?
    get() =
        charge_rate_mph_float?.takeIf { it.isFinite() && it > 0f }
            ?: charge_rate_mph?.takeIf { it > 0 }?.toFloat()

/** The car's gear, or null when it sent none (or an empty one). */
val DriveState.shiftStateKind: ShiftStateKind?
    get() {
        val shift = shift_state ?: return null
        return when {
            shift.P != null -> ShiftStateKind.P
            shift.R != null -> ShiftStateKind.R
            shift.N != null -> ShiftStateKind.N
            shift.D != null -> ShiftStateKind.D
            shift.CarServer_Invalid != null -> ShiftStateKind.Invalid
            shift.SNA != null -> ShiftStateKind.SNA
            else -> null
        }
    }

/**
 * Sentry mode is on: any sentry state but Off (Idle, Armed, Aware, Panic, Quiet). A car that sent
 * none (sentry unsupported, or the field left out) counts as off.
 */
val ClosuresState.sentryOn: Boolean
    get() {
        val state = sentry_mode_state ?: return false
        return state.Idle != null ||
            state.Armed != null ||
            state.Aware != null ||
            state.Panic != null ||
            state.Quiet != null
    }

/**
 * The climate is running: the car says it is on, or a climate keeper mode other than Off is active
 * (On, Dog, Party). The keeper keeps the climate running when `is_climate_on` alone may read false.
 * Missing and unknown values count as off.
 */
val ClimateState.climateOn: Boolean
    get() {
        if (is_climate_on == true) return true
        val keeper = climate_keeper_mode ?: return false
        return keeper.On != null || keeper.Dog != null || keeper.Party != null
    }
