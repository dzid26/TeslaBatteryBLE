// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.TeslaNames

/**
 * One known Tesla.
 *
 * [bleName] is the advertised name (`S<sha1(VIN)[:8]>C`): stable for the life
 * of the car and the identity every other record hangs off. The BLE [address]
 * is mutable — Android may see the car at a new address — and is only used to
 * connect.
 */
data class Vehicle(
    val bleName: String,
    val address: String,
    val gattName: String? = null,
    val displayName: String? = null,
    val vin: String? = null,
    val keySlot: Int? = null,
    val lastSeenMillis: Long = 0L,
) {
    /** What the UI calls this car: user-set name, then the car's own name. */
    val title: String get() = displayName?.takeIf { it.isNotBlank() } ?: gattName ?: bleName

    /** Enough of the VIN to tell cars apart without showing it in full. */
    val maskedVin: String? get() = vin?.takeLast(VIN_TAIL)?.let { "\u2026$it" }

    /** True when [candidate] is the VIN that hashes to this car's advertised name. */
    fun acceptsVin(candidate: String): Boolean {
        val normalized = normalizeVin(candidate)
        return normalized.length == VIN_LENGTH &&
            runCatching { TeslaNames.bleName(normalized) == bleName }.getOrDefault(false)
    }

    /** True once the app's key is enrolled in this car's whitelist. */
    val isPaired: Boolean get() = keySlot != null

    companion object {
        const val VIN_LENGTH = 17
        private const val VIN_TAIL = 5

        fun normalizeVin(raw: String): String = raw.trim().uppercase()
    }
}
