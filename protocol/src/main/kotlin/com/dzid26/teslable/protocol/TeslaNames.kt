// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.SHA1

object TeslaNames {
    private const val VIN_LENGTH = 17
    private val bleNamePattern = Regex("^S[0-9a-fA-F]{16}C$")

    @OptIn(DelicateCryptographyApi::class)
    fun bleName(vin: String): String {
        require(vin.length == VIN_LENGTH) { "VIN must be $VIN_LENGTH characters" }
        // Truncated SHA-1 is mandated by the Tesla protocol, not our choice.
        val digest =
            CryptographyProvider.Default
                .get(SHA1)
                .hasher()
                .hashBlocking(vin.toByteArray(Charsets.US_ASCII))
        val hex = digest.take(8).toByteArray().toHexString()
        return "S${hex}C"
    }

    fun isTeslaBleName(name: String?): Boolean = name != null && bleNamePattern.matches(name)
}
