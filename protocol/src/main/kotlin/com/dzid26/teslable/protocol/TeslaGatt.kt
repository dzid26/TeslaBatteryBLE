// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

/**
 * Tesla's BLE GATT identifiers, as canonical lowercase UUID strings so `core`
 * needs no platform UUID type (ADR-0007). Platforms parse them once, e.g.
 * `java.util.UUID.fromString` on Android.
 */
object TeslaGatt {
    const val SERVICE_UUID: String = "00000211-b2d1-43f0-9b88-960cebf8b91e"
    const val TO_VEHICLE_UUID: String = "00000212-b2d1-43f0-9b88-960cebf8b91e"
    const val FROM_VEHICLE_UUID: String = "00000213-b2d1-43f0-9b88-960cebf8b91e"
    const val VERSION_UUID: String = "00000214-b2d1-43f0-9b88-960cebf8b91e"
    const val CLIENT_CHARACTERISTIC_CONFIG_UUID: String = "00002902-0000-1000-8000-00805f9b34fb"
}
