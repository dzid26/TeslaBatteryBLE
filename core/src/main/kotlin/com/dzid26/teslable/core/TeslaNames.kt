// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core

import java.security.MessageDigest

object TeslaNames {

    private const val VIN_LENGTH = 17
    private val bleNamePattern = Regex("^S[0-9a-fA-F]{16}C$")

    fun bleName(vin: String): String {
        require(vin.length == VIN_LENGTH) { "VIN must be $VIN_LENGTH characters" }
        val digest = MessageDigest.getInstance("SHA-1").digest(vin.toByteArray(Charsets.US_ASCII))
        val hex = digest.take(8).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        return "S${hex}C"
    }

    fun isTeslaBleName(name: String?): Boolean =
        name != null && bleNamePattern.matches(name)
}
