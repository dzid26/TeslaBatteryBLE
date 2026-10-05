// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

// Chart-only analysis kept in `core` so the drawing code stays free of the
// rules and the rules stay unit-testable.

/**
 * One flag per consecutive sample pair: `true` when the pair spans a data gap
 * (the car was asleep or out of range between the readings). The list is
 * aligned with `samples`: flag `i` belongs to the segment from sample `i` to
 * sample `i + 1`, so a list of `n` samples yields `n - 1` flags. Assumes
 * ascending timestamps, as stored.
 *
 * Gap rule: an interval is a gap when it is larger than
 * `max(3 × median interval, 20 minutes)`. The median adapts to the sampling
 * cadence (a car that reports every few minutes vs. every couple of hours),
 * while the 20-minute floor keeps a burst of back-to-back reads from making
 * every later interval look like a gap. The median is only trusted once there
 * are at least three intervals; with one or two it would mirror the very
 * interval being tested, so shorter lists use the floor alone — two samples
 * hours apart still draw as a gap.
 */
fun List<BatterySample>.gapFlags(): List<Boolean> {
    if (size < 2) return emptyList()
    val intervals = (1 until size).map { this[it].timestampMillis - this[it - 1].timestampMillis }
    val threshold = gapThreshold(intervals.filter { it > 0 }.sorted())
    return intervals.map { it > threshold }
}

private fun gapThreshold(positiveIntervals: List<Long>): Long {
    if (positiveIntervals.size < MIN_MEDIAN_INTERVALS) return GAP_FLOOR_MILLIS
    val middle = positiveIntervals.size / 2
    val median =
        if (positiveIntervals.size % 2 == 1) {
            positiveIntervals[middle]
        } else {
            (positiveIntervals[middle - 1] + positiveIntervals[middle]) / 2
        }
    return maxOf(GAP_MEDIAN_FACTOR * median, GAP_FLOOR_MILLIS)
}

/** Where the current charge is heading, derived from the samples so far. */
data class ChargeProjection(
    /** The latest sample, where the projected line starts. */
    val from: BatterySample,
    /** The charge limit the projection aims at. */
    val targetPercent: Int,
    /** Projected wall-clock time the limit is reached. */
    val completionMillis: Long,
)

/**
 * Projects when the charge limit is reached while charging, at the average
 * rate of the current charging run: the trailing run of consecutive charging
 * samples. Returns null when the last sample is not charging, the charge limit
 * is unknown or already reached, the run has fewer than two samples, the
 * readings are less than [MIN_RATE_SPAN_MILLIS] apart, or the percent did not
 * rise — percentages move in integer steps, so short or flat runs would make
 * the rate too noisy to extrapolate from.
 */
fun chargeProjection(samples: List<BatterySample>): ChargeProjection? {
    val last = samples.lastOrNull() ?: return null
    if (!last.isCharging) return null
    val target = last.chargeLimit ?: return null
    if (target <= last.percent) return null

    val runStart = samples.indexOfLast { !it.isCharging } + 1
    val run = samples.subList(runStart, samples.size)
    if (run.size < 2) return null
    val first = run.first()
    val elapsed = last.timestampMillis - first.timestampMillis
    if (elapsed < MIN_RATE_SPAN_MILLIS) return null
    val gained = last.percent - first.percent
    if (gained <= 0) return null

    val remaining = target - last.percent
    return ChargeProjection(
        from = last,
        targetPercent = target,
        completionMillis = last.timestampMillis + remaining * elapsed / gained,
    )
}

private const val GAP_MEDIAN_FACTOR = 3L
private const val GAP_FLOOR_MILLIS = 20 * 60_000L
private const val MIN_MEDIAN_INTERVALS = 3
private const val MIN_RATE_SPAN_MILLIS = 2 * 60_000L
