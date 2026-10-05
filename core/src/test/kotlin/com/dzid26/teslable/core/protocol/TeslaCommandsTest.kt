// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.protocol

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.VehicleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeslaCommandsTest {
    @Test
    fun parsesRangeSocAndSessionFields() {
        val payload =
            Response(
                vehicleData =
                    VehicleData(
                        charge_state =
                            ChargeState(
                                battery_level = 78,
                                usable_battery_level = 77,
                                battery_range = 234.56f,
                                est_battery_range = 232.75f,
                                ideal_battery_range = 260.12f,
                                charge_limit_soc = 90,
                                charge_energy_added = 12.3f,
                                charge_miles_added_rated = 41.5f,
                                charge_miles_added_ideal = 45.25f,
                                charge_rate_mph = 32,
                                charge_rate_mph_float = 32.5f,
                                charging_state = ChargeState.ChargingState(Charging = Void()),
                            ),
                    ),
            ).encode()

        val charge = TeslaCommands.parseChargeState(payload)!!

        assertEquals(78, charge.batteryLevel)
        assertEquals(77, charge.usableBatteryLevel)
        assertEquals(234.56f, charge.batteryRange)
        assertEquals(232.75f, charge.estBatteryRange)
        assertEquals(260.12f, charge.idealBatteryRange)
        assertEquals(90, charge.chargeLimit)
        assertEquals("Charging", charge.chargingState)
        assertEquals(12.3f, charge.chargeEnergyAdded)
        assertEquals(41.5f, charge.chargeMilesAddedRated)
        assertEquals(45.25f, charge.chargeMilesAddedIdeal)
        assertEquals(32, charge.chargeRateMph)
        assertEquals(32.5f, charge.chargeRateMphFloat)
        assertEquals(32.5f, charge.chargingMph)
    }

    @Test
    fun chargingSlopeFallsBackToTheIntRate() {
        fun charge(
            rate: Int? = null,
            rateFloat: Float? = null,
        ) = TeslaCommands.Charge(
            batteryLevel = 78,
            chargeLimit = 90,
            chargingState = "Charging",
            chargeRateMph = rate,
            chargeRateMphFloat = rateFloat,
        )

        assertEquals(32f, charge(rate = 32, rateFloat = 0f).chargingMph)
        assertEquals(32f, charge(rate = 32).chargingMph)
        assertEquals(30f, charge(rate = 30, rateFloat = Float.NaN).chargingMph)
        assertNull(charge(rate = 0).chargingMph)
        assertNull(charge().chargingMph)
    }
}
