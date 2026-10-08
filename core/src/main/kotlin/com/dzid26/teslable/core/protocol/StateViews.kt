// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.protocol

import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus

// What the app derives from the car's raw replies, each rule defined once, here.
// The replies themselves are the live state: the parsers return the Wire message
// as the car sent it, and plain fields are read straight off it
// (`charge.battery_level`). Only a value that needs a rule, such as an enum's
// name or a fallback, gets a property below, and everything that wants it (the
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

/** The car's charging-state name (`Charging`, `Complete`, ...), or null when it sent none. */
val ChargeState.chargingStateName: String?
    get() {
        val state = charging_state ?: return null
        return when {
            state.Charging != null -> "Charging"
            state.Complete != null -> "Complete"
            state.Stopped != null -> "Stopped"
            state.Disconnected != null -> "Disconnected"
            state.NoPower != null -> "NoPower"
            state.Starting != null -> "Starting"
            state.Calibrating != null -> "Calibrating"
            state.Unknown != null -> "Unknown"
            else -> null
        }
    }

/** Charging slope in miles per hour: the float rate when plausible, else the int rate. */
val ChargeState.chargingMph: Float?
    get() =
        charge_rate_mph_float?.takeIf { it.isFinite() && it > 0f }
            ?: charge_rate_mph?.takeIf { it > 0 }?.toFloat()

/** The car's shift-state name (`P`, `R`, `N`, `D`, `Invalid`, `SNA`), or null when it sent none. */
val DriveState.shiftStateName: String?
    get() {
        val shift = shift_state ?: return null
        return when {
            shift.P != null -> "P"
            shift.R != null -> "R"
            shift.N != null -> "N"
            shift.D != null -> "D"
            shift.CarServer_Invalid != null -> "Invalid"
            shift.SNA != null -> "SNA"
            else -> null
        }
    }
