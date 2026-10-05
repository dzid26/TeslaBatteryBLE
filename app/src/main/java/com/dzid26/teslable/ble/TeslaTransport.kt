// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

/** A BLE link to one Tesla: real over GATT, fake for demos and tests. */
interface TeslaTransport {
    interface Listener {
        fun onPhase(phase: ConnectionPhase)

        fun onServices(services: List<GattServiceInfo>)

        fun onGattDeviceName(name: String?)

        fun onMtu(mtu: Int)

        fun onMessage(message: ByteArray)

        fun onLog(message: String)

        fun onRssi(rssi: Int) {}
    }

    fun connect(address: String)

    fun send(payload: ByteArray): Boolean

    fun close()

    fun readRssi()
}
