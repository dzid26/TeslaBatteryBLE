// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ui

import com.dzid26.teslable.core.health.DriveStats
import com.dzid26.teslable.core.units.DistanceUnit
import java.util.Locale

/** Percent of the battery per [unit] driven, such as "0.82"; null while the distance is too short for a steady rate. */
internal fun percentRate(
    stats: DriveStats,
    unit: DistanceUnit,
): String? = stats.percentPerMile?.let { String.format(Locale.getDefault(), "%.2f", unit.ratePerUnit(it)) }

/** Money per 100 [unit] driven at the home price, such as "$7.50"; null without a price or the energy behind it. */
internal fun costRate(
    stats: DriveStats,
    unit: DistanceUnit,
    currencyCode: String?,
): String? = stats.costAtHomePrice?.let { formatMoney(unit.ratePerUnit(it / stats.milesDriven) * DISTANCE_BLOCK, currencyCode) }

/** The cost is quoted per this many miles or km, so it reads in whole cents. */
private const val DISTANCE_BLOCK = 100f
