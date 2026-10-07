// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.health

import com.dzid26.teslable.core.history.BatterySample

/**
 * One charge session as the history saw it: from its first to its last charging
 * sample (see [chargeSessions] for how sessions are cut).
 *
 * [energyAddedKwh] and [milesAddedRated] are what the car added between those two
 * samples, so they always pair with [swingPercent]. Each is null when the car
 * reported nothing or the counter did not grow.
 */
data class ChargeSession(
    val startPercent: Float,
    val endPercent: Float,
    val energyAddedKwh: Float?,
    val milesAddedRated: Float?,
) {
    val swingPercent: Float get() = endPercent - startPercent

    /** The rated constant this session measured: kWh per rated mile. */
    val kwhPerMile: Float?
        get() {
            val energy = energyAddedKwh ?: return null
            val miles = milesAddedRated?.takeIf { it > 0f } ?: return null
            return energy / miles
        }

    /** The full-range scale the session implies: miles added over its swing. */
    val fullRangeMiles: Float?
        get() {
            val miles = milesAddedRated?.takeIf { it > 0f } ?: return null
            if (swingPercent < MIN_SWING_PERCENT) return null
            return miles / (swingPercent / 100f)
        }
}

/**
 * Splits [samples], one car's history oldest first, into charge sessions.
 *
 * Sessions are cut by `chargingState`:
 * - A session is a run of "Charging" or "Starting" samples.
 * - "Stopped" and "NoPower" samples are a pause. A pause shorter than ten minutes,
 *   counted from the last charging sample to the one that resumes charging, does
 *   not end the session. A pause of ten minutes or longer does, and the resuming
 *   sample opens the next session.
 * - "Complete", "Disconnected" and any other state (a missing, "Unknown" or
 *   "Calibrating" one included) end the session.
 * - Silence is not a pause: when the app saw nothing for a while (the link
 *   dropped), the session continues across the gap.
 *
 * A session still in progress counts, so the learning gate moves while charging.
 *
 * The car reports `charge_energy_added` and `charge_miles_added_rated` as running
 * totals since it began the session, and the first sample seen is often not that
 * start (the app was opened or came into range mid-charge). Each session therefore
 * carries the growth of those totals from its first to its last sample, which pairs
 * with the SOC swing between the same two samples. A total missing from the first
 * sample counts as zero there. A total that goes down inside the session was
 * restarted by the car (a stale value from the previous session on a "Starting"
 * sample, say); the session then carries the last value.
 */
fun chargeSessions(samples: List<BatterySample>): List<ChargeSession> {
    val runs = mutableListOf<MutableList<BatterySample>>()
    var current: MutableList<BatterySample>? = null
    var paused = false
    for (sample in samples) {
        when (sample.chargingState) {
            "Charging", "Starting" -> {
                val pausedTooLong =
                    paused && current != null && sample.timestampMillis - current.last().timestampMillis >= MAX_PAUSE_MILLIS
                if (current == null || pausedTooLong) {
                    current = mutableListOf()
                    runs.add(current)
                }
                current.add(sample)
                paused = false
            }

            "Stopped", "NoPower" -> paused = true

            else -> {
                current = null
                paused = false
            }
        }
    }
    return runs.map { it.toSession() }
}

private fun List<BatterySample>.toSession(): ChargeSession =
    ChargeSession(
        startPercent = first().batteryLevel.toFloat(),
        endPercent = last().batteryLevel.toFloat(),
        energyAddedKwh = counterGrowth { it.chargeEnergyAdded },
        milesAddedRated = counterGrowth { it.chargeMilesAddedRated },
    )

/**
 * How far one of the car's running totals grew from the first to the last sample
 * of a run. Null when the last sample has no value or the total did not grow.
 */
private fun List<BatterySample>.counterGrowth(counter: (BatterySample) -> Float?): Float? {
    val end = counter(last()) ?: return null
    val start = counter(first()) ?: 0f
    val restarted = mapNotNull(counter).zipWithNext().any { (before, after) -> after < before }
    return (if (restarted) end else end - start).takeIf { it > 0f }
}

/** Below this swing the estimator itself refuses to compute a capacity. */
const val MIN_SWING_PERCENT = 5f

/** A pause (Stopped or NoPower) shorter than this does not end a session. */
private const val MAX_PAUSE_MILLIS = 10 * 60_000L
