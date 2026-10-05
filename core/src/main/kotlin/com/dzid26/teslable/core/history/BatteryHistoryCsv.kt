// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * CSV codec for the append-only battery history, raw car fields only:
 * `vehicleId,timestampMillis,batteryLevel,chargeLimit,chargingState,usableBatteryLevel,`
 * `ratedRangeMiles,estRangeMiles,idealRangeMiles,chargeEnergyAdded,chargeMilesAddedRated,`
 * `chargeMilesAddedIdeal`.
 *
 * Pre-1.0 hygiene: rows from older formats are dropped on load, not migrated.
 */
object BatteryHistoryCsv {
    private const val COLUMNS = 12

    fun encode(sample: BatterySample): String =
        listOf(
            sample.vehicleId,
            sample.timestampMillis.toString(),
            sample.batteryLevel.toString(),
            sample.chargeLimit?.toString() ?: "",
            sample.chargingState?.replace(',', ' ') ?: "",
            sample.usableBatteryLevel?.toString() ?: "",
            sample.ratedRangeMiles?.toString() ?: "",
            sample.estRangeMiles?.toString() ?: "",
            sample.idealRangeMiles?.toString() ?: "",
            sample.chargeEnergyAdded?.toString() ?: "",
            sample.chargeMilesAddedRated?.toString() ?: "",
            sample.chargeMilesAddedIdeal?.toString() ?: "",
        ).joinToString(",")

    /** Parses a whole legacy CSV body; the header and any other-format rows are skipped. */
    fun parseAll(text: String): List<BatterySample> = text.lineSequence().mapNotNull(::parse).toList()

    /** Parses one data line; returns null for the header, a malformed row, or an old-format row. */
    fun parse(line: String): BatterySample? {
        val parts = line.split(',')
        if (parts.size != COLUMNS) return null
        val timestamp = parts[1].toLongOrNull() ?: return null
        val batteryLevel = parts[2].toIntOrNull() ?: return null
        return BatterySample(
            timestampMillis = timestamp,
            batteryLevel = batteryLevel,
            chargingState = parts[4].takeIf { it.isNotEmpty() },
            chargeLimit = parts[3].toIntOrNull(),
            vehicleId = parts[0],
            usableBatteryLevel = parts[5].toIntOrNull(),
            ratedRangeMiles = parts[6].toFloatOrNull(),
            estRangeMiles = parts[7].toFloatOrNull(),
            idealRangeMiles = parts[8].toFloatOrNull(),
            chargeEnergyAdded = parts[9].toFloatOrNull(),
            chargeMilesAddedRated = parts[10].toFloatOrNull(),
            chargeMilesAddedIdeal = parts[11].toFloatOrNull(),
        )
    }
}
