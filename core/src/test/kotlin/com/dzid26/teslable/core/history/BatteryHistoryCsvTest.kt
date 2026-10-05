// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryHistoryCsvTest {
    private val sample =
        BatterySample(
            timestampMillis = 1_700_000_000_000L,
            batteryLevel = 78,
            chargingState = "Charging",
            chargeLimit = 80,
            vehicleId = "Se1f0941734830fe7C",
            usableBatteryLevel = 77,
            ratedRangeMiles = 234.56f,
            estRangeMiles = 232.75f,
            idealRangeMiles = 260.12f,
            chargeEnergyAdded = 12.3f,
            chargeMilesAddedRated = 41.5f,
            chargeMilesAddedIdeal = 45.25f,
        )

    @Test
    fun roundTrips() {
        assertEquals(sample, BatteryHistoryCsv.parse(BatteryHistoryCsv.encode(sample)))
    }

    @Test
    fun roundTripsWithEmptyOptionalFields() {
        val sparse =
            BatterySample(
                timestampMillis = 1L,
                batteryLevel = 50,
                chargingState = null,
                chargeLimit = null,
                vehicleId = "S1a2b3c4d5e6f7080C",
            )
        assertEquals(sparse, BatteryHistoryCsv.parse(BatteryHistoryCsv.encode(sparse)))
    }

    @Test
    fun skipsHeaderMalformedAndOldFormatRows() {
        assertNull(
            BatteryHistoryCsv.parse(
                "vehicleId,timestampMillis,batteryLevel,chargeLimit,chargingState,usableBatteryLevel," +
                    "ratedRangeMiles,estRangeMiles,idealRangeMiles,chargeEnergyAdded,chargeMilesAddedRated," +
                    "chargeMilesAddedIdeal",
            ),
        )
        assertNull(BatteryHistoryCsv.parse("Se1f0941734830fe7C,1700000000000"))
        assertNull(
            BatteryHistoryCsv.parse(
                "Se1f0941734830fe7C,not-a-timestamp,78,80,Charging,77,234.56,232.75,260.12,12.3,41.5,45.25",
            ),
        )
        // Rows written before the raw-only columns existed are dropped, not migrated.
        assertNull(
            BatteryHistoryCsv.parse(
                "Se1f0941734830fe7C,1700000000000,78,80,Charging,77.6,232.75",
            ),
        )
        // 15-column rows from the intermediate format are dropped too, not shifted
        // into this layout (their columns would land on the wrong fields).
        assertNull(
            BatteryHistoryCsv.parse(
                "Se1f0941734830fe7C,1700000000000,78,80,Charging,77.0,206.94,78,77,206.94,,206.94,38.98,159.0,159.0",
            ),
        )
    }

    @Test
    fun chargingStateWithCommasIsSanitised() {
        val messy = sample.copy(chargingState = "Starting, please wait")
        val encoded = BatteryHistoryCsv.encode(messy)
        assertEquals(12, encoded.split(',').size)
        assertEquals("Starting  please wait", BatteryHistoryCsv.parse(encoded)?.chargingState)
    }
}
