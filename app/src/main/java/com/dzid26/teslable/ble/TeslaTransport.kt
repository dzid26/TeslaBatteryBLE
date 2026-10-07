// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

/**
 * A BLE link to one Tesla: real over GATT, fake for demos and tests.
 *
 * Call every method on the main thread.
 */
interface TeslaTransport {
    /**
     * Link events. Every callback arrives on the main thread, so a listener can
     * use main-thread state without locking, and it may call back into the
     * transport from a callback, e.g. [TeslaTransport.close] from [onPhase].
     */
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
