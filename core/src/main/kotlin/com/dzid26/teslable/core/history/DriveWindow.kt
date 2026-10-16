// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.units.DistanceUnit

/**
 * How far back the drive chart looks, in distance driven rather than time.
 * The three distances may span charges (the chart breaks its line there);
 * [SINCE_CHARGE] stops at the last one (see [drivePoints]).
 */
enum class DriveWindow(
    private val kilometers: Double?,
    private val miles: Double?,
) {
    SHORT(SHORT_KM, SHORT_MILES),
    MEDIUM(MEDIUM_KM, MEDIUM_MILES),
    LONG(LONG_KM, LONG_MILES),
    SINCE_CHARGE(null, null),
    ;

    /** True when the window stops at the last charge. */
    val sinceLastCharge: Boolean get() = this == SINCE_CHARGE

    /** The window in odometer miles for a car shown in [unit]: round numbers in the unit the owner reads; null for no limit. */
    fun miles(unit: DistanceUnit): Double? =
        when (unit) {
            DistanceUnit.MILES -> miles
            DistanceUnit.KILOMETERS -> kilometers?.let { it / unit.fromMiles(1f) }
        }

    /** The round number in [unit] the chip shows, or null for [SINCE_CHARGE]. */
    fun label(unit: DistanceUnit): Int? =
        when (unit) {
            DistanceUnit.MILES -> miles?.toInt()
            DistanceUnit.KILOMETERS -> kilometers?.toInt()
        }
}

private const val SHORT_KM = 15.0
private const val MEDIUM_KM = 50.0
private const val LONG_KM = 150.0
private const val SHORT_MILES = 10.0
private const val MEDIUM_MILES = 30.0
private const val LONG_MILES = 100.0
