// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

// Chart-only analysis kept in `core` so the drawing code stays free of the
// rules and the rules stay unit-testable.

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

private const val MIN_RATE_SPAN_MILLIS = 2 * 60_000L
