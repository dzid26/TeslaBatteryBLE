// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Where a parked car's battery is heading at its parked drain rate over the range. */
data class DischargeProjection(
    /** The latest sample, where the projected line starts. */
    val from: BatterySample,
    /** Percent projected one horizon after the last sample. */
    val projectedPercent: Int,
    /** Wall-clock time the projection refers to. */
    val projectedAtMillis: Long,
    /** How far ahead the projection looks: half of the selected range. */
    val horizonMillis: Long,
    /** The parked drain rate the line follows, in percentage points lost per hour. */
    val percentPerHour: Double,
    /** The level the line starts at: sub-percent from the fitted scale when it is pinned. */
    val fromSoc: Float = from.batteryLevel.toFloat(),
    /** The unrounded level one horizon ahead; [projectedPercent] is this, rounded. */
    val projectedSoc: Float = projectedPercent.toFloat(),
    /** The lower edge of the beam at the horizon: two standard errors of the rate; null when the fit is too thin. */
    val lowSoc: Float? = null,
    /** The upper edge of the beam at the horizon; null like [lowSoc]. */
    val highSoc: Float? = null,
    /** The mixed rate (asleep and awake pairs together), percent per hour, when [percentPerHour] is the deep-sleep rate; else null. */
    val mixedPercentPerHour: Double? = null,
) {
    /** The beam's two edges together; null when the fit is too thin for one. */
    val beam: Pair<Float, Float>?
        get() {
            val low = lowSoc ?: return null
            val high = highSoc ?: return null
            return low to high
        }
}

/**
 * Projects the parked drain: the drain rate of the parked stretches in the
 * selected range is carried half the range forward, matching the chart's
 * expanded x-axis.
 *
 * Only parked pairs of consecutive battery samples feed the rate. A pair is
 * parked when neither sample is charging, neither is in gear (R, N or D: a
 * car stopped at a light keeps its odometer but is mid-drive), and the
 * odometer did not move between them. Each sample's drive reading is the
 * [DriveSample] nearest to it in time, within [DRIVE_MATCH_MILLIS] (a drive
 * read follows every charge read). A sample with no such reading is not known to be parked, so
 * its pairs are left out. A long gap with no readings still counts when the
 * odometer is unchanged across it: the overnight drop up to the wake-up
 * reading is exactly the drain this projects, while a drive no phone saw
 * shows up as an odometer change and is left out.
 *
 * The rate is the summed signed change over the summed duration of the
 * parked pairs, so a rounding blip that drops and recovers while parked
 * cancels out instead of inflating the drop. The change is read on the rated
 * range through the fitted [scale] when it is pinned, so a pair of readings
 * is a fraction of a percent, not a whole level; without it the integer
 * levels stand in. The beam is two standard errors of that rate: the spread
 * of the pairs around it, never less than the readings' own resolution.
 *
 * The line follows the deep-sleep rate once the car has slept enough to tell: the
 * rate of the pairs it slept through (a VCSEC status inside the gap says asleep
 * and none says awake). The mixed rate (every parked pair, asleep or awake) is
 * reported beside it, and the beam's lower edge reaches the mixed line, so the
 * beam spans deep sleep to what the battery really did. Without enough sleep
 * the line is the mixed rate.
 *
 * While charging it still projects: the line shows where the battery would
 * head if unplugged now, next to the charge projection. Returns null once the
 * whole projection lies in the past (the last sample is older than the
 * horizon), when the range holds less than [MIN_RATE_SPAN_MILLIS] of parked
 * time, or when the summed drop is not positive: a rising or flat line would
 * make the rate meaningless.
 *
 * @param samples one vehicle's battery samples, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 * @param rangeMillis the selected range: its samples feed the rate, and the projection looks half of it ahead.
 * @param context the fitted scale and the status log that refine the rate.
 */
fun dischargeProjection(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    rangeMillis: Long,
    nowMillis: Long,
    context: ProjectionContext = ProjectionContext(),
): DischargeProjection? {
    val scale = context.scale
    val statuses = context.statuses
    val last = samples.lastOrNull() ?: return null
    val horizon = rangeMillis / 2
    if (nowMillis - last.timestampMillis > horizon) return null

    val windowed = samples.filter { it.timestampMillis >= last.timestampMillis - rangeMillis }
    val parked = parkedDrain(windowed, drives.filter { it.odometerInHundredthsOfAMile != null }, scale, statuses)
    if (parked.millis < MIN_RATE_SPAN_MILLIS || parked.drop <= 0.0) return null

    val fromSoc = last.soc(scale)
    val mixedRate = parked.drop / parked.millis
    // The line follows the deep-sleep rate when the car has slept enough to tell, else the mixed one.
    val sleepRate = parked.sleepRatePerMilli?.takeIf { it < mixedRate }
    val mainRate = sleepRate ?: mixedRate
    val projectedSoc = (fromSoc - mainRate * horizon).toFloat().coerceIn(0f, FULL_PERCENT)
    val mixedSoc = (fromSoc - mixedRate * horizon).toFloat().coerceIn(0f, FULL_PERCENT)
    val reach = 2.0 * parked.errorPerMilli * horizon
    val hasStats = parked.pairs >= MIN_BEAM_PAIRS
    val hasBeam = hasStats || sleepRate != null
    // The beam: the statistics around the line, reaching down to the mixed line when the main one is the sleep rate.
    val low = if (hasStats) minOf((projectedSoc - reach).toFloat(), mixedSoc) else mixedSoc
    val high = if (hasStats) (projectedSoc + reach).toFloat() else projectedSoc
    return DischargeProjection(
        from = last,
        projectedPercent = projectedSoc.roundToInt(),
        projectedAtMillis = last.timestampMillis + horizon,
        horizonMillis = horizon,
        percentPerHour = mainRate * HOUR,
        fromSoc = fromSoc,
        projectedSoc = projectedSoc,
        lowSoc = if (hasBeam) low.coerceIn(0f, FULL_PERCENT) else null,
        highSoc = if (hasBeam) high.coerceIn(0f, minOf(fromSoc, FULL_PERCENT)) else null,
        mixedPercentPerHour = if (sleepRate != null) mixedRate * HOUR else null,
    )
}

/**
 * What refines the parked rate beyond the raw samples: the fitted rated-range
 * [scale] (null reads the integer level) and the vehicle's VCSEC [statuses],
 * oldest first, which say when the car slept.
 */
data class ProjectionContext(
    val scale: FullRangeScale? = null,
    val statuses: List<StatusSample> = emptyList(),
)

/** The drop (percent) and duration of the pairs the car slept through. */
private class SleepSpan(
    val drop: Double,
    val millis: Long,
    val pairs: Int,
)

/** The summed signed drop (percent) and duration of the parked pairs, with their spread. */
private class ParkedDrain(
    val drop: Double,
    val millis: Long,
    val pairs: Int,
    /** Sum of each pair's squared residual from the mean rate. */
    val squaredResiduals: Double,
    /** The rounding variance of the summed drop: first minus last reading, so two readings' worth however many pairs. */
    val roundingVariance: Double = 0.0,
    /** The drop and duration of the pairs the car slept through. */
    val sleep: SleepSpan = SleepSpan(0.0, 0L, 0),
) {
    /** The drain rate of the slept-through pairs, percent per millisecond; null with too little sleep. */
    val sleepRatePerMilli: Double? get() =
        if (sleep.pairs >= MIN_SLEEP_PAIRS &&
            sleep.millis >= MIN_RATE_SPAN_MILLIS
        ) {
            sleep.drop / sleep.millis
        } else {
            null
        }

    /**
     * One standard error of the rate, percent per millisecond: the pairs' scatter
     * (sample variance, over n - 1) and the rounding of the two end readings, in quadrature.
     */
    val errorPerMilli: Double
        get() = sqrt(squaredResiduals * pairs / maxOf(pairs - 1, 1) + roundingVariance) / millis
}

/** Sums the parked pairs of [samples]; [drives] all carry an odometer and are oldest first. */
private fun parkedDrain(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    scale: FullRangeScale?,
    statuses: List<StatusSample>,
): ParkedDrain {
    val drops = mutableListOf<Double>()
    val spans = mutableListOf<Long>()
    val slept = mutableListOf<Boolean>()
    val readings = driveReadingsAt(samples, drives)
    for (index in 1 until samples.size) {
        val a = samples[index - 1]
        val b = samples[index]
        val readingA = readings[index - 1]
        val readingB = readings[index]
        val stayed =
            readingA != null &&
                readingB != null &&
                readingA.odometerInHundredthsOfAMile == readingB.odometerInHundredthsOfAMile &&
                !readingA.inGear &&
                !readingB.inGear
        if (stayed && !a.isCharging && !b.isCharging) {
            drops += (a.soc(scale) - b.soc(scale)).toDouble()
            spans += b.timestampMillis - a.timestampMillis
            slept += sleptThrough(a, b, statuses)
        }
    }
    val drop = drops.sum()
    val millis = spans.sum()
    val sleepDrop = drops.filterIndexed { index, _ -> slept[index] }.sum()
    val sleepMillis = spans.filterIndexed { index, _ -> slept[index] }.sum()
    if (millis <= 0L) return ParkedDrain(drop, millis, drops.size, 0.0)
    val rate = drop / millis
    var squared = 0.0
    for (index in drops.indices) {
        val residual = drops[index] - rate * spans[index]
        squared += residual * residual
    }
    // The pairs telescope: their drops sum to first minus last, so rounding
    // enters as two readings however many pairs there are. The residuals above
    // already carry some rounding noise, so adding it here counts it twice in part;
    // erring wide is the intended side.
    val rounding = TWO_READINGS_VARIANCE * readingResolution(scale) * readingResolution(scale)
    return ParkedDrain(drop, millis, drops.size, squared, rounding, SleepSpan(sleepDrop, sleepMillis, slept.count { it }))
}

/**
 * True when the car slept through the gap between two reads: a status sample
 * inside it says asleep and none says awake. The reads themselves are awake
 * moments (the car answers only when awake), so only the inside counts. Times
 * are the phone's clock, which the status samples share.
 */
private fun sleptThrough(
    a: BatterySample,
    b: BatterySample,
    statuses: List<StatusSample>,
): Boolean {
    val from = a.readAtMillis ?: a.timestampMillis
    val to = b.readAtMillis ?: b.timestampMillis
    // The statuses are oldest first: find the first one inside the gap, then walk it.
    var low = 0
    var high = statuses.size
    while (low < high) {
        val middle = (low + high) / 2
        if (statuses[middle].timestampMillis > from) high = middle else low = middle + 1
    }
    var sawAsleep = false
    var index = low
    while (index < statuses.size && statuses[index].timestampMillis < to) {
        if (!statuses[index].asleep) return false
        sawAsleep = true
        index++
    }
    return sawAsleep
}

/** A full battery, percent. */
private const val FULL_PERCENT = 100f

/** An asleep rate needs this many slept-through pairs: one pair's rounding can fake a rate or a zero. */
private const val MIN_SLEEP_PAIRS = 2

/** A beam needs this many parked pairs behind the rate. */
private const val MIN_BEAM_PAIRS = 3

/** Two end readings, each with the same rounding variance. */
private const val TWO_READINGS_VARIANCE = 2.0

private const val MINUTE = 60_000L

private const val HOUR = 60 * MINUTE

/** Less parked time than this makes the rate too noisy to extrapolate. */
private const val MIN_RATE_SPAN_MILLIS = 10 * MINUTE
