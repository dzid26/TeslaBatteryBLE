// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.history

import com.dzid26.teslable.core.history.log.BatterySampleRecord
import java.io.ByteArrayOutputStream

/**
 * Length-delimited protobuf codec for the append-only history log (ADR-0006).
 *
 * Each record is a varint byte length followed by a [BatterySampleRecord].
 * The schema is additive: readers ignore unknown fields, so new fields never
 * drop old rows. A truncated trailing record (an interrupted append) is
 * ignored on load; records after it are not read.
 */
object BatteryHistoryLog {
    /** Encodes all [samples] into one buffer, for a full rewrite. */
    fun encode(samples: List<BatterySample>): ByteArray {
        val out = ByteArrayOutputStream()
        for (sample in samples) {
            out.write(encodeFrame(sample))
        }
        return out.toByteArray()
    }

    /** Encodes one sample as a length-delimited frame, for an append. */
    fun encodeFrame(sample: BatterySample): ByteArray {
        val payload = sample.toRecord().encode()
        val out = ByteArrayOutputStream()
        writeVarint(payload.size, out)
        out.write(payload)
        return out.toByteArray()
    }

    /** Reads every complete record; stops at a truncated or unreadable tail. */
    fun decode(bytes: ByteArray): List<BatterySample> {
        val samples = mutableListOf<BatterySample>()
        var offset = 0
        while (offset < bytes.size) {
            val (length, next) = readVarint(bytes, offset) ?: break
            if (length <= 0 || next + length > bytes.size) break
            val sample =
                runCatching {
                    BatterySampleRecord.ADAPTER.decode(bytes.copyOfRange(next, next + length)).toSample()
                }.getOrNull() ?: break
            samples += sample
            offset = next + length
        }
        return samples
    }

    private fun BatterySample.toRecord(): BatterySampleRecord =
        BatterySampleRecord(
            timestamp_millis = timestampMillis,
            battery_level = batteryLevel,
            charging_state = chargingState,
            charge_limit = chargeLimit,
            vehicle_id = vehicleId,
            usable_battery_level = usableBatteryLevel,
            rated_range_miles = ratedRangeMiles,
            est_range_miles = estRangeMiles,
            ideal_range_miles = idealRangeMiles,
            charge_energy_added = chargeEnergyAdded,
            charge_miles_added_rated = chargeMilesAddedRated,
            charge_miles_added_ideal = chargeMilesAddedIdeal,
            charge_rate_mph = chargeRateMph,
            charge_rate_mph_float = chargeRateMphFloat,
        )

    private fun BatterySampleRecord.toSample(): BatterySample? =
        BatterySample(
            timestampMillis = timestamp_millis ?: return null,
            batteryLevel = battery_level ?: return null,
            chargingState = charging_state,
            chargeLimit = charge_limit,
            vehicleId = vehicle_id ?: "",
            usableBatteryLevel = usable_battery_level,
            ratedRangeMiles = rated_range_miles,
            estRangeMiles = est_range_miles,
            idealRangeMiles = ideal_range_miles,
            chargeEnergyAdded = charge_energy_added,
            chargeMilesAddedRated = charge_miles_added_rated,
            chargeMilesAddedIdeal = charge_miles_added_ideal,
            chargeRateMph = charge_rate_mph,
            chargeRateMphFloat = charge_rate_mph_float,
        )

    private fun writeVarint(
        value: Int,
        out: ByteArrayOutputStream,
    ) {
        var remaining = value
        while (remaining >= VARINT_CONTINUATION) {
            out.write((remaining and VARINT_PAYLOAD) or VARINT_CONTINUATION)
            remaining = remaining ushr VARINT_SHIFT
        }
        out.write(remaining)
    }

    /** Returns the value and the offset after it, or null when truncated. */
    private fun readVarint(
        bytes: ByteArray,
        start: Int,
    ): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var offset = start
        while (offset < bytes.size && shift < VARINT_MAX_BITS) {
            val byte = bytes[offset].toInt()
            value = value or ((byte and VARINT_PAYLOAD) shl shift)
            offset++
            if (byte and VARINT_CONTINUATION == 0) return value to offset
            shift += VARINT_SHIFT
        }
        return null
    }

    private const val VARINT_CONTINUATION = 0x80
    private const val VARINT_PAYLOAD = 0x7F
    private const val VARINT_SHIFT = 7
    private const val VARINT_MAX_BITS = 32
}
