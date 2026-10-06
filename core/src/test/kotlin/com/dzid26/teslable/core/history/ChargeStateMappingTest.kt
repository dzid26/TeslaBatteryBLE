// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.TeslaCommands
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ChargeStateMappingTest {
    private fun record() =
        ChargeState(
            charging_state = ChargeState.ChargingState(Charging = Void()),
            charge_limit_soc = 85,
            battery_range = 206.61f,
            est_battery_range = 200.5f,
            ideal_battery_range = 220.25f,
            battery_level = 77,
            usable_battery_level = 77,
            charge_energy_added = 12.3f,
            charge_miles_added_rated = 41.5f,
            charge_miles_added_ideal = 45.25f,
            charger_voltage = 240,
            charger_power = 7200,
            charge_rate_mph = 32,
            charging_amps = 30,
            charge_rate_mph_float = 32.5f,
            timestamp = Instant.ofEpochMilli(1_000L),
        )

    @Test
    fun rawRecordMapsOntoASample() {
        val expected =
            BatterySample(
                timestampMillis = 1_000L,
                batteryLevel = 77,
                chargingState = "Charging",
                chargeLimit = 85,
                vehicleId = "Se1f0941734830fe7C",
                usableBatteryLevel = 77,
                ratedRangeMiles = 206.61f,
                estRangeMiles = 200.5f,
                idealRangeMiles = 220.25f,
                chargeEnergyAdded = 12.3f,
                chargeMilesAddedRated = 41.5f,
                chargeMilesAddedIdeal = 45.25f,
                chargeRateMph = 32,
                chargeRateMphFloat = 32.5f,
                chargerPower = 7200,
                chargerVoltage = 240,
                chargingAmps = 30,
            )
        assertEquals(expected, record().toBatterySample("Se1f0941734830fe7C"))
    }

    @Test
    fun sampleNeedsALevelAndATimestamp() {
        assertNull(ChargeState(battery_level = 50).toBatterySample("car"))
        assertNull(ChargeState(timestamp = Instant.ofEpochSecond(1)).toBatterySample("car"))
    }

    @Test
    fun phoneFillsAMissingTimestampButKeepsTheCars() {
        val missing = ChargeState(battery_level = 50)
        assertEquals(Instant.ofEpochMilli(1_000L), missing.withTimestamp(1_000L).timestamp)
        val carTime = ChargeState(battery_level = 50, timestamp = Instant.ofEpochSecond(7))
        assertEquals(Instant.ofEpochSecond(7), carTime.withTimestamp(1_000L).timestamp)
    }

    @Test
    fun chargingStateNameReadsTheSetVariant() {
        assertEquals("Disconnected", TeslaCommands.chargingStateName(ChargeState.ChargingState(Disconnected = Void())))
        assertNull(TeslaCommands.chargingStateName(ChargeState.ChargingState()))
        assertNull(TeslaCommands.chargingStateName(null))
    }
}
