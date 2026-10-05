// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/** One battery reading, recorded whenever a car reports charge state. */
data class BatterySample(
    val timestampMillis: Long,
    val percent: Int,
    val chargingState: String?,
    val chargeLimit: Int?,
    /**
     * Which vehicle the reading came from: the advertised BLE name
     * (`S<sha1(VIN)[:8]>C`), which is stable before a VIN is known. Empty on
     * rows written before per-vehicle history existed.
     */
    val vehicleId: String = "",
) {
    val isCharging: Boolean get() = chargingState == "Charging"
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

/** Summary since the end of the last completed charging session. */
data class ChargeStats(
    val sinceMillis: Long,
    val startPercent: Int,
    val currentPercent: Int,
    val minPercent: Int,
    val maxPercent: Int,
) {
    val usedPercent: Int get() = (startPercent - currentPercent).coerceAtLeast(0)
}

/**
 * Stats since the last completed charge: the first sample after the last
 * charging sample. Returns null when nothing was charged yet or the car is
 * still charging.
 */
fun chargeStats(samples: List<BatterySample>): ChargeStats? {
    val lastCharging = samples.indexOfLast { it.isCharging }
    if (lastCharging < 0) return null
    val startIndex = lastCharging + 1
    if (startIndex >= samples.size) return null
    val start = samples[startIndex]
    val tail = samples.subList(startIndex, samples.size)
    return ChargeStats(
        sinceMillis = start.timestampMillis,
        startPercent = start.percent,
        currentPercent = tail.last().percent,
        minPercent = tail.minOf { it.percent },
        maxPercent = tail.maxOf { it.percent },
    )
}
