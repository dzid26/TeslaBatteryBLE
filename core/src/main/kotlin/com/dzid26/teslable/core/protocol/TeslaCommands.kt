package com.dzid26.teslable.core.protocol

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.GetChargeState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.VehicleAction
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.UnsignedMessage

object TeslaCommands {

    data class Charge(
        val batteryLevel: Int?,
        val chargeLimit: Int?,
        val chargingState: String?,
        val range: Float?,
    )

    fun buildWakeRequest(): ByteArray =
        UnsignedMessage(RKEAction = RKEAction_E.RKE_ACTION_WAKE_VEHICLE).encode()

    fun buildChargeStateRequest(): ByteArray =
        Action(
            vehicleAction = VehicleAction(
                getVehicleData = GetVehicleData(getChargeState = GetChargeState()),
            ),
        ).encode()

    fun parseChargeState(payload: ByteArray): Charge? {
        val data = Response.ADAPTER.decode(payload).vehicleData ?: return null
        val charge = data.charge_state ?: return null
        return Charge(
            batteryLevel = charge.battery_level,
            chargeLimit = charge.charge_limit_soc,
            chargingState = charge.charging_state?.let { state ->
                when {
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
            },
            range = charge.battery_range,
        )
    }

    fun parseActionStatus(payload: ByteArray): String? =
        Response.ADAPTER.decode(payload).actionStatus?.result?.name
}
