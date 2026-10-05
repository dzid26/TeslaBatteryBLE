// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import android.content.Context
import com.dzid26.teslable.core.TeslaNames
import kotlin.random.Random

/** Debug wiring that makes the app talk to [FakeTeslaTransport] cars. */
class FakeCarDemo(private val context: Context) : DemoMode.Car {
    override fun createTransport(
        address: String,
        listener: TeslaTransport.Listener,
    ): TeslaTransport = FakeTeslaTransport(context, listener)

    override fun adverts(): List<TeslaAdvert> =
        listOf(
            TeslaAdvert(
                name = TeslaNames.bleName(DemoMode.DEMO_VIN),
                address = DemoMode.DEMO_ADDRESS,
                rssi = -60 + Random.nextInt(-3, 4),
            ),
            TeslaAdvert(
                name = OTHER_NAME,
                address = DemoMode.OTHER_ADDRESS,
                rssi = -78 + Random.nextInt(-3, 4),
            ),
        )

    private companion object {
        const val OTHER_NAME = "S1a2b3c4d5e6f7080C"
    }
}
