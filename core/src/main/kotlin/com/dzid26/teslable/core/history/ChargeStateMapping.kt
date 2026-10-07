// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.TeslaCommands
import com.tesla.generated.carserver.vehicle.ChargeState

// One-way conversion: raw car ChargeState records kept in the history log
// become the app's BatterySample read model. The read model is derived on
// load; nothing ever writes a record back from parsed fields — the store logs
// the car's raw response verbatim.

/** The app-facing sample for a raw record; null when the car sent no level or no timestamp. */
fun ChargeState.toBatterySample(vehicleId: String): BatterySample? {
    val level = battery_level ?: return null
    val time = timestamp?.toEpochMilli() ?: return null
    return BatterySample(
        timestampMillis = time,
        batteryLevel = level,
        chargingState = TeslaCommands.chargingStateName(charging_state),
        chargeLimit = charge_limit_soc,
        vehicleId = vehicleId,
        usableBatteryLevel = usable_battery_level,
        ratedRangeMiles = battery_range,
        estRangeMiles = est_battery_range,
        idealRangeMiles = ideal_battery_range,
        chargeEnergyAdded = charge_energy_added,
        chargeMilesAddedRated = charge_miles_added_rated,
        chargeMilesAddedIdeal = charge_miles_added_ideal,
        chargeRateMph = charge_rate_mph,
        chargeRateMphFloat = charge_rate_mph_float,
        chargerPower = charger_power,
        chargerVoltage = charger_voltage,
        chargingAmps = charging_amps,
    )
}
