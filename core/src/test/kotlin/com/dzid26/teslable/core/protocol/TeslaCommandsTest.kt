// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.protocol

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.VehicleData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeslaCommandsTest {
    @Test
    fun parsesTheWholeChargeState() {
        val charge =
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
            )
        val payload = Response(vehicleData = VehicleData(charge_state = charge)).encode()

        // Every field comes back as the car sent it, parsed ones and unparsed alike.
        assertEquals(charge, TeslaCommands.parseChargeState(payload))
    }

    @Test
    fun aReplyWithoutAChargeStateParsesToNull() {
        val driveReply = Response(vehicleData = VehicleData(drive_state = DriveState(speed = 0))).encode()
        assertNull(TeslaCommands.parseChargeState(driveReply))
        assertNull(TeslaCommands.parseChargeState(Response().encode()))
    }
}
