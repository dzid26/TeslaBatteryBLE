// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import com.dzid26.teslable.BuildConfig

/**
 * Build-time switch for the simulated car: `./gradlew assembleDebug -PdemoCar=true`.
 * The fake itself lives in the debug source set; release builds leave [car] null
 * and always use the real GATT transport.
 */
object DemoMode {
    const val DEMO_VIN = "5YJ3DEMO000000001"
    const val DEMO_ADDRESS = "AA:BB:CC:DD:EE:01"
    const val OTHER_ADDRESS = "AA:BB:CC:DD:EE:02"

    /** Set by the debug application; null in release builds. */
    @Volatile
    var car: Car? = null

    interface Car {
        fun createTransport(
            address: String,
            listener: TeslaTransport.Listener,
        ): TeslaTransport

        fun adverts(): List<TeslaAdvert>
    }

    fun isEnabled(): Boolean = car != null && BuildConfig.DEMO_CAR
}
