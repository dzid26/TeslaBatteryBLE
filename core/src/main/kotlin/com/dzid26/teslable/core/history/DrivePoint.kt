// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.protocol.ShiftStateKind
import kotlin.math.abs

/** One battery reading on the drive chart: placed by distance driven, not time. */
data class DrivePoint(
    val timestampMillis: Long,
    /** Miles on the odometer since the first point of the series. */
    val milesDriven: Double,
    /**
     * State of charge in percent: sub-percent from the fitted [FullRangeScale]
     * when it is pinned, else the displayed whole-percent level.
     */
    val socPercent: Float,
    /** True when the nearest drive reading shows negative power while in gear. */
    val regenerating: Boolean,
)

/**
 * The battery samples taken while driving, placed on the odometer. Each
 * sample takes the [DriveSample] nearest to it in time, within
 * [DRIVE_MATCH_MILLIS]. Samples in gear are kept, plus the parked sample
 * right before and right after each run of them, so a drive starts and ends
 * at the parked level. Charging samples and samples with no odometer reading
 * nearby are left out. Readings at one odometer value (a car left in gear)
 * all stay, stacked at the same distance.
 *
 * The series stops at the last charge before the newest drive reading, so it
 * never spans one (a charge in progress now does not hide the drive before
 * it), and [windowMiles] keeps only the last stretch of that many odometer
 * miles. Distances start at 0 at the first point kept.
 *
 * @param samples one vehicle's battery samples, oldest first.
 * @param drives the same vehicle's drive samples, oldest first.
 * @param scale the fitted full-range scale for sub-percent SOC; null uses the displayed level.
 * @param windowMiles keep only this many miles back from the newest point; null keeps everything since the charge.
 */
fun drivePoints(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
    scale: FullRangeScale? = null,
    windowMiles: Double? = null,
): List<DrivePoint> {
    val readings = driveReadingsAt(samples, drives.filter { it.odometerInHundredthsOfAMile != null })
    val known =
        samples.zip(readings).mapNotNull { (sample, reading) ->
            val odometer = reading?.odometerInHundredthsOfAMile
            if (sample.isCharging || odometer == null) null else Known(sample, reading, odometer)
        }
    val kept =
        known.filterIndexed { index, point ->
            point.reading.inGear ||
                known.getOrNull(index - 1)?.reading?.inGear == true ||
                known.getOrNull(index + 1)?.reading?.inGear == true
        }
    val window = kept.lastOrNull()?.let { newest -> kept.windowed(newest, samples, windowMiles) }.orEmpty()
    val start = window.firstOrNull()?.odometer ?: 0
    return window.map { (sample, reading, odometer) ->
        DrivePoint(
            timestampMillis = sample.timestampMillis,
            milesDriven = (odometer - start) / HUNDREDTHS_PER_MILE,
            socPercent = sample.soc(scale),
            regenerating = reading.inGear && (reading.power ?: 0) < 0,
        )
    }
}

/** The points after the last charge before [newest], and, with [windowMiles], within that many odometer miles of it. */
private fun List<Known>.windowed(
    newest: Known,
    samples: List<BatterySample>,
    windowMiles: Double?,
): List<Known> {
    val lastCharge =
        samples.filter { it.isCharging && it.timestampMillis < newest.sample.timestampMillis }.maxOfOrNull { it.timestampMillis }
    val sinceCharge = if (lastCharge == null) this else filter { it.sample.timestampMillis > lastCharge }
    val earliestOdometer = windowMiles?.let { newest.odometer - it * HUNDREDTHS_PER_MILE }
    return if (earliestOdometer == null) sinceCharge else sinceCharge.filter { it.odometer >= earliestOdometer }
}

/** A battery sample with its nearest drive reading and that reading's odometer. */
private data class Known(
    val sample: BatterySample,
    val reading: DriveSample,
    val odometer: Int,
)

/** In R, N or D: the car is being driven even if the odometer has not ticked yet. */
internal val DriveSample.inGear: Boolean
    get() = shiftState == ShiftStateKind.R || shiftState == ShiftStateKind.N || shiftState == ShiftStateKind.D

/**
 * The drive sample nearest to each battery sample, or null when none lies
 * within [DRIVE_MATCH_MILLIS]. One sweep, since both lists are oldest first.
 */
internal fun driveReadingsAt(
    samples: List<BatterySample>,
    drives: List<DriveSample>,
): List<DriveSample?> {
    var next = 0
    return samples.map { sample ->
        // Advance to the first drive sample after this battery sample; the
        // nearest one is either it or the one just before.
        while (next < drives.size && drives[next].timestampMillis <= sample.timestampMillis) next++
        listOfNotNull(drives.getOrNull(next - 1), drives.getOrNull(next))
            .minByOrNull { abs(it.timestampMillis - sample.timestampMillis) }
            ?.takeIf { abs(it.timestampMillis - sample.timestampMillis) <= DRIVE_MATCH_MILLIS }
    }
}

/** A drive sample this close to a battery sample gives its odometer; a drive read follows each charge read. */
private const val DRIVE_MATCH_MILLIS = 2 * 60_000L

private const val HUNDREDTHS_PER_MILE = 100.0
