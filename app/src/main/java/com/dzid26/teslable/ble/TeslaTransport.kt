// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

/** A BLE link to one Tesla: real over GATT, fake for demos and tests. */
interface TeslaTransport {

    fun connect(address: String)

    fun send(payload: ByteArray): Boolean

    fun close()

    fun readRssi()
}
