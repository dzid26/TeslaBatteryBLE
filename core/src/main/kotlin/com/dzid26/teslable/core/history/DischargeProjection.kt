// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.math.roundToInt

/** Where a parked car's battery is heading at its recent drain rate. */
data class DischargeProjection(
    /** The latest sample, where the projected line starts. */
    val from: BatterySample,
    /** Percent projected [HORIZON_MILLIS] after the last sample. */
    val projectedPercent: Int,
    /** Wall-clock time the projection refers to. */
    val projectedAtMillis: Long,
)

/**
 * The trailing window the rate is measured over, scaled to the selected range:
 * a 6 h chart measures the last 2 h, a day the last 6 h, a week the last day,
 * and All the last week.
 */
fun HistoryRange.projectionWindowMillis(): Long =
    when (this) {
        HistoryRange.SIX_HOURS -> 2 * HOUR
        HistoryRange.DAY -> 6 * HOUR
        HistoryRange.WEEK -> 24 * HOUR
        HistoryRange.ALL -> 7 * 24 * HOUR
    }

/**
 * Projects the parked drain: the average rate over the trailing window is
 * carried [HORIZON_MILLIS] forward from the last sample. Returns null while
 * charging (the charge projection owns that), when the last sample is too old
 * for its window, when the window has less than [MIN_RATE_SPAN_MILLIS] of
 * samples, or when the percent did not fall — a rising or flat line would make
 * the rate meaningless.
 */
fun dischargeProjection(
    samples: List<BatterySample>,
    windowMillis: Long,
    nowMillis: Long,
): DischargeProjection? {
    val last = samples.lastOrNull() ?: return null
    if (last.isCharging) return null
    if (nowMillis - last.timestampMillis > freshnessMillis(windowMillis)) return null

    val windowed = samples.filter { it.timestampMillis >= last.timestampMillis - windowMillis }
    val first = windowed.firstOrNull() ?: return null
    val elapsed = last.timestampMillis - first.timestampMillis
    if (elapsed < MIN_RATE_SPAN_MILLIS) return null
    val drop = first.percent - last.percent
    if (drop <= 0) return null

    val projected =
        (last.percent - drop.toDouble() * HORIZON_MILLIS / elapsed)
            .roundToInt()
            .coerceIn(0, 100)
    return DischargeProjection(
        from = last,
        projectedPercent = projected,
        projectedAtMillis = last.timestampMillis + HORIZON_MILLIS,
    )
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/** The projection looks this far ahead; twelve hours covers a night parked. */
private const val HORIZON_MILLIS = 12 * HOUR

/** Two readings closer than this make the rate too noisy to extrapolate. */
private const val MIN_RATE_SPAN_MILLIS = 10 * MINUTE

private const val FRESHNESS_MIN_MILLIS = 30 * MINUTE
private const val FRESHNESS_MAX_MILLIS = 6 * HOUR

/** How stale the newest sample may be before the rate is not worth showing. */
private fun freshnessMillis(windowMillis: Long): Long = (windowMillis / 4).coerceIn(FRESHNESS_MIN_MILLIS, FRESHNESS_MAX_MILLIS)
