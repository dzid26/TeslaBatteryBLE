// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

/**
 * How long each VCSEC status flag has held its current value. Each duration is
 * null when the status log cannot say (see [statusDurations]) or is under a minute.
 * The flag values are the newest sample's, so a caller shows a duration only
 * while the live flag still matches.
 */
data class StatusDurations(
    val asleep: Boolean,
    val locked: Boolean,
    val userPresent: Boolean,
    val asleepForMillis: Long?,
    val lockedForMillis: Long?,
    val userPresentForMillis: Long?,
)

/**
 * The status durations for [vehicleId] at [nowMillis], from its status log.
 *
 * The status log records a reading when it differs from the previous one, so a
 * flag's value began at the first record after the last record that held a
 * different value. That record's time is the since-time. The duration is
 * unknown (null) when:
 * - no record with a different value exists, so the log does not reach back to the change;
 * - the changing reading followed the different one by more than [maxGapMillis]
 *   (the app was not watching when it happened, so the real change time is unknown);
 * - the since-time is in the future (the phone's clock moved back).
 *
 * Durations under a minute are dropped, as the text would only flicker.
 * Returns null when the vehicle has no records.
 *
 * @param samples status readings in any order; other vehicles' are ignored.
 */
fun statusDurations(
    samples: List<StatusSample>,
    vehicleId: String,
    nowMillis: Long,
    maxGapMillis: Long = STATUS_CHANGE_MAX_GAP_MILLIS,
): StatusDurations? {
    val own = samples.filter { it.vehicleId == vehicleId }.sortedBy { it.timestampMillis }
    val newest = own.lastOrNull() ?: return null

    fun forMillis(flag: (StatusSample) -> Boolean): Long? {
        val current = flag(newest)
        val differing = own.indexOfLast { flag(it) != current }
        if (differing < 0) return null
        val since = own[differing + 1]
        if (since.timestampMillis - own[differing].timestampMillis > maxGapMillis) return null
        val held = nowMillis - since.timestampMillis
        return held.takeIf { it >= MINUTE_MILLIS }
    }
    return StatusDurations(
        asleep = newest.asleep,
        locked = newest.locked,
        userPresent = newest.userPresent,
        asleepForMillis = forMillis { it.asleep },
        lockedForMillis = forMillis { it.locked },
        userPresentForMillis = forMillis { it.userPresent },
    )
}

/**
 * A compact duration: "20m", "1h 12m", "3h", "2d 4h". Null under a minute.
 * Minutes are dropped from a day on, as are hours that are zero.
 */
fun compactDuration(millis: Long): String? {
    val minutes = millis / MINUTE_MILLIS
    return when {
        minutes < 1 -> null
        minutes < 60 -> "${minutes}m"
        minutes < 24 * 60 -> "${minutes / 60}h" + (minutes % 60).takeIf { it > 0 }?.let { " ${it}m" }.orEmpty()
        else -> "${minutes / (24 * 60)}d" + (minutes / 60 % 24).takeIf { it > 0 }?.let { " ${it}h" }.orEmpty()
    }
}

/** A flag change is trusted only when the two readings around it are this close together. */
const val STATUS_CHANGE_MAX_GAP_MILLIS = 5 * 60_000L

private const val MINUTE_MILLIS = 60_000L
