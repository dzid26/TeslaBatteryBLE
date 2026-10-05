// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.health

import com.dzid26.teslable.core.health.EnergyDeltaEstimator
import com.dzid26.teslable.core.health.HealthFusion
import com.dzid26.teslable.core.health.RatedRangeEstimator
import com.dzid26.teslable.core.history.BatterySample
import kotlin.math.roundToInt

// Battery-health glue: turns the recorded raw samples into the numbers the car
// view shows. All estimation lives in `core/health`; this file only selects
// inputs, counts sessions for the learning gate, and flags thin data.

/** Confidence in the estimates, gated on how many charge sessions were seen. */
enum class HealthConfidence { LEARNING, LOW, MEDIUM, HIGH }

/** One charge session: a run of consecutive charging samples. */
data class ChargeSession(
    val startPercent: Float,
    val endPercent: Float,
    val energyAddedKwh: Float?,
) {
    val swingPercent: Float get() = endPercent - startPercent
}

/**
 * The health card's data. [sohPercent] is null until the car's factory range
 * (and capacity) are configured, so the card never invents a baseline.
 */
data class HealthSummary(
    val sessions: Int,
    /** Sessions needed before the learning gate opens. */
    val learningTarget: Int,
    val sohPercent: Float?,
    val sohSpreadPoints: Float?,
    val sohMismatch: Boolean,
    val capacityKwh: Float?,
    val capacitySwingPercent: Float?,
    val fullRangeMiles: Float?,
    val rangeSocPercent: Float?,
    val confidence: HealthConfidence,
    /** A plain-language caveat when the inputs are thin, else null. */
    val qualityNote: String?,
)

/**
 * Charge sessions in [samples]: each run of consecutive charging samples. A run
 * still in progress counts, so the learning gate moves while charging.
 */
fun chargeSessions(samples: List<BatterySample>): List<ChargeSession> {
    val sessions = mutableListOf<ChargeSession>()
    var start: BatterySample? = null
    var last: BatterySample? = null
    for (sample in samples) {
        if (sample.isCharging) {
            if (start == null) start = sample
            last = sample
        } else if (start != null && last != null) {
            sessions += session(start, last)
            start = null
            last = null
        }
    }
    if (start != null && last != null) sessions += session(start, last)
    return sessions
}

/**
 * Builds the card's data from one car's samples. [epaRatedRangeMiles] and
 * [newCapacityKwh] are the car's factory figures; while they are unknown the
 * card shows measured capacity only and no SoH percentage.
 */
fun healthSummary(
    samples: List<BatterySample>,
    epaRatedRangeMiles: Float? = null,
    newCapacityKwh: Float? = null,
): HealthSummary {
    val sessions = chargeSessions(samples)
    val lastSession = sessions.lastOrNull()
    // The capacity estimate uses the most recent session with a meaningful
    // swing; a session still in progress would otherwise hide it.
    val capacitySession = sessions.lastOrNull { it.swingPercent >= MIN_SWING_PERCENT }
    val capacity =
        capacitySession?.let { session ->
            session.energyAddedKwh?.let { energy ->
                EnergyDeltaEstimator.estimate(
                    energyAddedKwh = energy,
                    socStartPercent = session.startPercent,
                    socEndPercent = session.endPercent,
                    newCapacityKwh = newCapacityKwh,
                )
            }
        }
    val rangeSample = samples.lastOrNull { it.ratedRangeMiles != null && it.batteryLevel > 0 }
    val ratedSoh =
        rangeSample?.let { sample ->
            epaRatedRangeMiles?.let { epa ->
                RatedRangeEstimator
                    .estimate(
                        displayedRangeMiles = sample.ratedRangeMiles!!,
                        socPercent = sample.batteryLevel.toFloat(),
                        epaRatedRangeMiles = epa,
                    )?.sohPercent
            }
        }
    val fused = HealthFusion.fuse(ratedSoh, capacity?.sohPercent)
    val fullRange =
        rangeSample?.let { sample ->
            sample.ratedRangeMiles!! / (sample.batteryLevel / 100f)
        }
    return HealthSummary(
        sessions = sessions.size,
        learningTarget = LEARNING_SESSIONS,
        sohPercent = fused?.sohPercent,
        sohSpreadPoints = fused?.spreadPoints,
        sohMismatch = fused?.mismatch == true,
        capacityKwh = capacity?.usableCapacityKwh,
        capacitySwingPercent = capacity?.socDeltaPercent,
        fullRangeMiles = fullRange,
        rangeSocPercent = rangeSample?.batteryLevel?.toFloat(),
        confidence = confidenceFor(sessions.size),
        qualityNote = qualityNote(capacitySession ?: lastSession, rangeSample),
    )
}

private fun session(
    start: BatterySample,
    last: BatterySample,
): ChargeSession =
    ChargeSession(
        startPercent = start.batteryLevel.toFloat(),
        endPercent = last.batteryLevel.toFloat(),
        energyAddedKwh = last.chargeEnergyAdded?.takeIf { it > 0f },
    )

private fun confidenceFor(sessions: Int): HealthConfidence =
    when {
        sessions < LEARNING_SESSIONS -> HealthConfidence.LEARNING
        sessions < MEDIUM_SESSIONS -> HealthConfidence.LOW
        sessions < HIGH_SESSIONS -> HealthConfidence.MEDIUM
        else -> HealthConfidence.HIGH
    }

private fun qualityNote(
    lastSession: ChargeSession?,
    rangeSample: BatterySample?,
): String? {
    val swing = lastSession?.swingPercent
    val rangeLevel = rangeSample?.batteryLevel
    return when {
        swing != null && swing < QUALITY_SWING_PERCENT ->
            "the last charge only swung ${swing.roundToInt()}% SOC"

        rangeLevel != null && rangeLevel < QUALITY_RANGE_SOC ->
            "the range reading was taken at $rangeLevel% SOC"

        else -> null
    }
}

/** The benchmark product needs about twenty sessions for a verdict; we do too. */
private const val LEARNING_SESSIONS = 3
private const val MEDIUM_SESSIONS = 10
private const val HIGH_SESSIONS = 20

/** A charge swing under this widens the energy-delta error. */
private const val QUALITY_SWING_PERCENT = 20f

/** Below this swing the estimator itself refuses to compute a capacity. */
private const val MIN_SWING_PERCENT = 5f

/** Rated-range extrapolation gets noisy below this SOC. */
private const val QUALITY_RANGE_SOC = 30
