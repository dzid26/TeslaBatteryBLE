// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.GetChargeState
import com.tesla.generated.carserver.server.GetDriveState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.UnsignedMessage

object TeslaCommands {
    fun buildWakeRequest(): ByteArray = UnsignedMessage(RKEAction = RKEAction_E.RKE_ACTION_WAKE_VEHICLE).encode()

    fun buildChargeStateRequest(): ByteArray =
        Action(
            vehicleAction =
                VehicleAction(
                    getVehicleData = GetVehicleData(getChargeState = GetChargeState()),
                ),
        ).encode()

    /** One state category per `GetVehicleData`, as Tesla's Go SDK requests them (`pkg/vehicle/state.go`). */
    fun buildDriveStateRequest(): ByteArray =
        Action(
            vehicleAction =
                VehicleAction(
                    getVehicleData = GetVehicleData(getDriveState = GetDriveState()),
                ),
        ).encode()

    /**
     * The car's raw `ChargeState` from a vehicle-data reply, or null when the
     * reply holds none. It is returned whole: the history logs it verbatim
     * (ADR-0008), and derived values come from [chargingStateName] and the
     * other properties in `StateViews.kt`.
     */
    fun parseChargeState(payload: ByteArray): ChargeState? {
        val data = Response.ADAPTER.decode(payload).vehicleData ?: return null
        return data.charge_state
    }

    /**
     * The car's raw `DriveState` from a vehicle-data reply, or null when the
     * reply holds none. It is returned whole rather than parsed into fields:
     * the history logs it verbatim, navigation destination and route included
     * (ADR-0008).
     */
    fun parseDriveState(payload: ByteArray): DriveState? {
        val data = Response.ADAPTER.decode(payload).vehicleData ?: return null
        return data.drive_state
    }

    fun parseActionStatus(payload: ByteArray): String? =
        Response.ADAPTER
            .decode(payload)
            .actionStatus
            ?.result
            ?.name
}
