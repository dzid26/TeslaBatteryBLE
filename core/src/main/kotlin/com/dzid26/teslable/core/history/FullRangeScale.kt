// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Online estimate of the car's rated-range scale: the line that turns a rated
 * range reading (miles) into a SOC on the displayed-level scale,
 * `ratedMiles = offsetMiles + milesPerPercent * soc`.
 *
 * It is fitted to the rated miles at which the integer level steps (see
 * [FullRangeScaleEstimator]), so it does not depend on how the car rounds
 * the level, and it needs no 100% charge.
 */
data class FullRangeScale(
    /** Miles per 100% of displayed SOC: the slope of the fit, times 100. */
    val fullRangeMiles: Float,
    /** Rated miles at displayed SOC 0 (the fit's intercept; absorbs rounding and any buffer). */
    val offsetMiles: Float,
    /** One standard error of [fullRangeMiles]. */
    val standardErrorMiles: Float,
    /** Level boundaries that contributed. */
    val samples: Int,
    /** True when the scale is tight enough to trust for SOC conversion. */
    val pinned: Boolean,
) {
    val lowerMiles: Float get() = fullRangeMiles - 2f * standardErrorMiles
    val upperMiles: Float get() = fullRangeMiles + 2f * standardErrorMiles

    /** Miles per displayed percent. */
    val milesPerPercent: Float get() = fullRangeMiles / 100f

    /** Sub-percent SOC from a rated range reading; null until the scale is [pinned]. */
    fun socPercent(ratedMiles: Float?): Float? {
        if (!pinned) return null
        if (ratedMiles == null || !ratedMiles.isFinite() || ratedMiles <= 0f) return null
        return ((ratedMiles - offsetMiles) / milesPerPercent).coerceIn(0f, 100f)
    }
}

/**
 * Fits the scale from the rated miles at which the integer level changes.
 *
 * Dividing a reading by its integer level bakes the car's rounding into the
 * scale (an error of up to 0.5 / level). The spacing between level steps does
 * not: where two consecutive samples differ by exactly one level, the step lies
 * between their rated miles, at `level + 0.5` on the level axis. A weighted line
 * through those boundaries gives the slope (the scale) independent of the
 * rounding mode, which only shifts the intercept.
 *
 * Each boundary is weighted by how tightly consecutive reads bracket it
 * (`1 / gap^2`), so densely sampled steps dominate and the estimate tightens as
 * more levels are seen. Only the newest [MAX_BOUNDARIES] boundaries are used, and
 * outliers (a drive between two reads) are dropped, so drift or a bad pair
 * cannot poison it for good.
 */
object FullRangeScaleEstimator {
    /** Relative standard error at or below which the scale pins. */
    private const val PIN_TOLERANCE = 0.005f

    /** Most recent boundaries kept in the fit. */
    private const val MAX_BOUNDARIES = 80

    private const val MIN_BOUNDARIES = 4

    /** sqrt(12): a uniform position inside a gap has standard deviation gap / sqrt(12). */
    private const val UNIFORM_SPREAD_DIVISOR = 3.4641016f

    /** A level step happens half a level above the lower level. */
    private const val STEP_MIDPOINT = 0.5f

    /** Distinct level steps needed so the slope is not read off one spot. */
    private const val MIN_LEVEL_SPAN = 3f

    /** Floor on a boundary's uncertainty, miles (the car's range resolution). */
    private const val MIN_SIGMA_MILES = 0.05f

    /** A boundary further than this from the fit is dropped, in sigmas (and at least [MIN_OUTLIER_MILES]). */
    private const val OUTLIER_SIGMAS = 4f
    private const val MIN_OUTLIER_MILES = 0.8f

    private class Boundary(
        val level: Float,
        val miles: Float,
        val sigma: Float,
    )

    fun estimate(samples: List<BatterySample>): FullRangeScale? {
        val boundaries = boundaries(samples).takeLast(MAX_BOUNDARIES)
        if (boundaries.size < MIN_BOUNDARIES) return null
        var fit = fit(boundaries) ?: return null
        val kept =
            boundaries.filter {
                abs(it.miles - (fit.offset + fit.slope * it.level)) <=
                    maxOf(OUTLIER_SIGMAS * it.sigma, MIN_OUTLIER_MILES)
            }
        if (kept.size < boundaries.size) {
            if (kept.size < MIN_BOUNDARIES) return null
            fit = fit(kept) ?: return null
        }
        val used = kept.size
        val span = kept.maxOf { it.level } - kept.minOf { it.level }
        val relativeError = fit.slopeError / fit.slope
        val pinned = span >= MIN_LEVEL_SPAN && relativeError <= PIN_TOLERANCE
        return FullRangeScale(
            fullRangeMiles = (fit.slope * 100.0).toFloat(),
            offsetMiles = fit.offset.toFloat(),
            standardErrorMiles = (fit.slopeError * 100.0).toFloat(),
            samples = used,
            pinned = pinned,
        )
    }

    private fun boundaries(samples: List<BatterySample>): List<Boundary> {
        val usable =
            samples.filter {
                val miles = it.ratedRangeMiles
                miles != null && miles.isFinite() && miles > 0f && it.batteryLevel in 1..100
            }
        val result = mutableListOf<Boundary>()
        for ((before, after) in usable.zipWithNext()) {
            if (abs(after.batteryLevel - before.batteryLevel) != 1) continue
            val a = before.ratedRangeMiles!!
            val b = after.ratedRangeMiles!!
            val gap = abs(b - a)
            // A uniform position inside the gap has standard deviation gap / sqrt(12).
            val sigma = maxOf(gap / UNIFORM_SPREAD_DIVISOR, MIN_SIGMA_MILES)
            val level = minOf(before.batteryLevel, after.batteryLevel) + STEP_MIDPOINT
            result += Boundary(level, (a + b) / 2f, sigma)
        }
        return result
    }

    private class Line(
        val slope: Double,
        val offset: Double,
        val slopeError: Double,
    )

    private fun fit(points: List<Boundary>): Line? {
        var sw = 0.0
        var sx = 0.0
        var sy = 0.0
        for (p in points) {
            val w = 1.0 / (p.sigma.toDouble() * p.sigma)
            sw += w
            sx += w * p.level
            sy += w * p.miles
        }
        val mx = sx / sw
        val my = sy / sw
        var sxx = 0.0
        var sxy = 0.0
        for (p in points) {
            val w = 1.0 / (p.sigma.toDouble() * p.sigma)
            sxx += w * (p.level - mx) * (p.level - mx)
            sxy += w * (p.level - mx) * (p.miles - my)
        }
        if (sxx <= 0.0) return null
        val slope = sxy / sxx
        if (slope <= 0.0) return null
        // Boundary sigmas are modelled, not measured: scale the error by the
        // fit's own reduced chi-square when it says the sigmas were too small.
        var chi = 0.0
        for (p in points) {
            val r = p.miles - (my + slope * (p.level - mx))
            chi += r * r / (p.sigma.toDouble() * p.sigma)
        }
        val dof = (points.size - 2).coerceAtLeast(1)
        val inflate = maxOf(1.0, chi / dof)
        return Line(slope, my - slope * mx, sqrt(inflate / sxx))
    }
}

/** The sample's SOC for plotting: sub-percent from the fitted [scale] when it is pinned, else the integer level. */
fun BatterySample.soc(scale: FullRangeScale?): Float = scale?.socPercent(ratedRangeMiles) ?: batteryLevel.toFloat()

const val TENTHS_PER_PERCENT = 10

/** A SOC in whole tenths of a percent: 62.4 is 624. Rounds half up, so 99.96 carries to 100.0. */
fun socTenths(percent: Float): Int = (percent * TENTHS_PER_PERCENT).roundToInt()
