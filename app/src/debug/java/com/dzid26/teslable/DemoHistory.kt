// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable

import android.content.Context
import com.dzid26.teslable.ble.DemoMode
import com.dzid26.teslable.core.TeslaNames
import com.dzid26.teslable.core.history.BatteryHistoryCsv
import com.dzid26.teslable.core.history.BatterySample
import java.io.File

/**
 * Seeds a day of plausible battery history for the simulated car, so demo and
 * screenshot builds open with a populated graph instead of "No samples yet".
 * Runs once, only for the demo car, and only when no history file exists.
 */
internal object DemoHistory {
    private const val FILE_NAME = "battery-history.csv"
    private const val CHARGE_LIMIT = 85

    fun seed(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) return
        val vehicleId =
            runCatching { TeslaNames.bleName(DemoMode.DEMO_VIN) }.getOrNull() ?: return
        file.writeText(
            samples(System.currentTimeMillis(), vehicleId)
                .joinToString(separator = "\n", postfix = "\n") { BatteryHistoryCsv.encode(it) },
        )
    }

    private fun samples(
        now: Long,
        vehicleId: String,
    ): List<BatterySample> {
        val minute = 60_000L
        // (minutes ago, percent, charging state): a drive, an overnight charge
        // and a slow drain since, ending where the simulated car sits at 78%.
        val points =
            listOf(
                Triple(1380, 58, "Disconnected"),
                Triple(1320, 56, "Disconnected"),
                Triple(1260, 55, "Disconnected"),
                Triple(1200, 54, "Disconnected"),
                Triple(1080, 52, "Disconnected"),
                Triple(1020, 51, "Disconnected"),
                Triple(960, 50, "Disconnected"),
                Triple(900, 51, "Charging"),
                Triple(840, 60, "Charging"),
                Triple(780, 70, "Charging"),
                Triple(720, 79, "Charging"),
                Triple(690, 84, "Charging"),
                Triple(660, 85, "Complete"),
                Triple(600, 84, "Disconnected"),
                Triple(480, 83, "Disconnected"),
                Triple(360, 82, "Disconnected"),
                Triple(240, 81, "Disconnected"),
                Triple(120, 80, "Disconnected"),
                Triple(30, 79, "Disconnected"),
                Triple(10, 78, "Disconnected"),
            )
        return points.map { (minutesAgo, percent, state) ->
            BatterySample(
                timestampMillis = now - minutesAgo * minute,
                percent = percent,
                chargingState = state,
                chargeLimit = CHARGE_LIMIT,
                vehicleId = vehicleId,
            )
        }
    }
}
