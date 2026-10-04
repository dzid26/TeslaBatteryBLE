// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * CSV codec for the append-only battery history.
 *
 * v1 (legacy, single car):
 * `timestampMillis,percent,chargeLimit,chargingState`
 *
 * v2 (per vehicle):
 * `vehicleId,timestampMillis,percent,chargeLimit,chargingState`
 *
 * v1 rows carry no vehicle. The store attributes them to the legacy vehicle
 * when it reads them, and the next full rewrite persists them in v2 form.
 */
object BatteryHistoryCsv {

    const val HEADER = "vehicleId,timestampMillis,percent,chargeLimit,chargingState"

    fun encode(sample: BatterySample): String = listOf(
        sample.vehicleId,
        sample.timestampMillis.toString(),
        sample.percent.toString(),
        sample.chargeLimit?.toString() ?: "",
        sample.chargingState?.replace(',', ' ') ?: "",
    ).joinToString(",")

    /** Parses one data line; returns null for the header or a malformed row. */
    fun parse(line: String): BatterySample? {
        val parts = line.split(',')
        // v2 rows have five fields and start with a vehicle id; v1 rows have four.
        return if (parts.size >= 5) {
            val timestamp = parts[1].toLongOrNull() ?: return null
            val percent = parts[2].toIntOrNull() ?: return null
            BatterySample(
                timestampMillis = timestamp,
                percent = percent,
                chargingState = parts[4].takeIf { it.isNotEmpty() },
                chargeLimit = parts[3].toIntOrNull(),
                vehicleId = parts[0],
            )
        } else {
            val timestamp = parts.getOrNull(0)?.toLongOrNull() ?: return null
            val percent = parts.getOrNull(1)?.toIntOrNull() ?: return null
            BatterySample(
                timestampMillis = timestamp,
                percent = percent,
                chargingState = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
                chargeLimit = parts.getOrNull(2)?.toIntOrNull(),
            )
        }
    }

    /** Attributes a legacy (vehicle-less) row to [vehicleId]. */
    fun attribute(sample: BatterySample, vehicleId: String?): BatterySample =
        if (sample.vehicleId.isEmpty() && vehicleId != null) {
            sample.copy(vehicleId = vehicleId)
        } else {
            sample
        }
}
