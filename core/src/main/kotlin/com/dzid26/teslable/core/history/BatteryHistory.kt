// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.protocol.ChargingStateKind

/**
 * One battery reading, recorded whenever a car reports charge state.
 *
 * Holds just the fields the app reads, taken from the car's raw `ChargeState`
 * by [toBatterySample]; the raw record stays in the log (ADR-0008). Display
 * values are derived at runtime.
 */
data class BatterySample(
    val timestampMillis: Long,
    /** Displayed SOC (`battery_level`). */
    val batteryLevel: Int,
    val chargingState: ChargingStateKind?,
    val chargeLimit: Int?,
    /**
     * Which vehicle the reading came from: the advertised BLE name
     * (`S<sha1(VIN)[:8]>C`), which is stable before a VIN is known.
     */
    val vehicleId: String = "",
    /** `battery_range`: rated range in miles at this SOC. */
    val ratedRangeMiles: Float? = null,
    /** `charge_energy_added`: kWh added so far this session; 0 when idle. */
    val chargeEnergyAdded: Float? = null,
    /** `charge_miles_added_rated`: rated miles added so far this session. */
    val chargeMilesAddedRated: Float? = null,
    /** `charger_voltage`: the AC supply voltage in volts while charging; null when absent. */
    val chargerVoltage: Int? = null,
    /**
     * The phone's clock when the reply was read (the record's `device_timestamp`),
     * or null when the record has none. This is what "last read" means, and it
     * is the only time the app shows as a reading's age: [timestampMillis] is
     * the car's own clock, which places the sample on the chart but says
     * nothing reliable about when the phone last heard from the car.
     */
    val readAtMillis: Long? = null,
) {
    /** The displayed level, derived for the chart, stats, and car view. */
    val percent: Int get() = batteryLevel

    val isCharging: Boolean get() = chargingState == ChargingStateKind.Charging
}

/** Time window for the battery graph. */
enum class HistoryRange(
    val durationMillis: Long?,
) {
    SIX_HOURS(6 * 60 * 60 * 1000L),
    DAY(24 * 60 * 60 * 1000L),
    WEEK(7 * 24 * 60 * 60 * 1000L),
    ALL(null),
}

fun List<BatterySample>.within(
    range: HistoryRange,
    nowMillis: Long,
): List<BatterySample> {
    val duration = range.durationMillis ?: return this
    val cutoff = nowMillis - duration
    return filter { it.timestampMillis >= cutoff }
}

/** Summary since the last charge: the level it reached, and the drain since. */
data class ChargeStats(
    val sinceMillis: Long,
    /** The level the charge ended at (the newest charging sample). */
    val chargePercent: Float,
    val currentPercent: Float,
    val minPercent: Float,
    val maxPercent: Float,
) {
    val usedPercent: Float get() = (chargePercent - currentPercent).coerceAtLeast(0f)
}

/**
 * Stats since the last charge ended, referenced to the charge itself: its
 * newest charging sample supplies both the time and the level, and the
 * samples after it supply the drain. The first sample recorded after the
 * charge is *not* the reference: the app can see nothing for a while (the car
 * asleep, the phone away), so that sample may be days after the charge and at
 * a level the car already fell to. Returns null when nothing was charged yet
 * or while the car is still charging. The samples need not be in time order.
 */
fun chargeStats(samples: List<BatterySample>): ChargeStats? {
    val charged = samples.filter { it.isCharging }.maxByOrNull { it.timestampMillis } ?: return null
    val newest = samples.maxByOrNull { it.timestampMillis } ?: return null
    if (newest.timestampMillis <= charged.timestampMillis) return null
    val tail = samples.filter { it.timestampMillis > charged.timestampMillis }
    return ChargeStats(
        sinceMillis = charged.timestampMillis,
        chargePercent = charged.percent.toFloat(),
        currentPercent = newest.percent.toFloat(),
        minPercent = tail.minOf { it.percent.toFloat() },
        maxPercent = tail.maxOf { it.percent.toFloat() },
    )
}
