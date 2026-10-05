// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * Online estimate of the car's full rated range: the scale that turns a rated
 * range reading into a SOC fraction (`ratedMiles = fullRange * soc / 100`).
 *
 * The car never reports it and the new-car EPA figure is not it. Each sample
 * offers a candidate `ratedRange / (batteryLevel / 100)`; the integer level
 * rounds the true SOC, so a single candidate is off by up to ~0.5 / level.
 * Under nearest rounding each sample brackets the scale and the intersection
 * across samples narrows it; [pinned] once the bracket is tight enough. Until
 * then callers keep using the raw int SOC ([socPercent] returns null).
 */
data class FullRangeScale(
    /** Midpoint of the bracket: the best estimate, pinned or not. */
    val fullRangeMiles: Float,
    val lowerMiles: Float,
    val upperMiles: Float,
    /** Samples that contributed a bracket. */
    val samples: Int,
    /** True when the bracket is tight enough to trust for SOC conversion. */
    val pinned: Boolean,
) {
    /** SOC from a rated range reading; null until the scale is [pinned]. */
    fun socPercent(ratedMiles: Float?): Float? {
        if (!pinned) return null
        if (ratedMiles == null || !ratedMiles.isFinite() || ratedMiles <= 0f) return null
        return (ratedMiles / fullRangeMiles * 100f).coerceIn(0f, 100f)
    }
}

object FullRangeScaleEstimator {
    /** Bracket width (fraction of the midpoint) at or below which the scale pins. */
    private const val PIN_TOLERANCE = 0.01f

    /** Half a displayed level: the true SOC sits within this of the int level. */
    private const val LEVEL_BRACKET = 0.5f

    /**
     * Estimates the scale from rated-range samples. Uses the displayed
     * `batteryLevel` as the SOC reference (the convention is still open; the
     * samples also log the usable level for a later fit).
     */
    fun estimate(samples: List<BatterySample>): FullRangeScale? {
        var lower = 0f
        var upper = Float.MAX_VALUE
        var used = 0
        for (sample in samples) {
            val miles = sample.ratedRangeMiles ?: continue
            val level = sample.batteryLevel
            if (!miles.isFinite() || miles <= 0f || level !in 1..100) continue
            val ratio = miles / (level / 100f)
            val levelF = level.toFloat()
            lower = maxOf(lower, ratio * levelF / (levelF + LEVEL_BRACKET))
            upper = minOf(upper, ratio * levelF / (levelF - LEVEL_BRACKET))
            used++
        }
        if (used == 0 || upper <= lower) return null
        val midpoint = (lower + upper) / 2f
        val pinned = (upper - lower) <= midpoint * PIN_TOLERANCE
        return FullRangeScale(midpoint, lower, upper, used, pinned)
    }
}
