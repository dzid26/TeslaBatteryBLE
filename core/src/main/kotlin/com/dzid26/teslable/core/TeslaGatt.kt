// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core

import java.util.UUID

object TeslaGatt {
    val SERVICE_UUID: UUID = UUID.fromString("00000211-b2d1-43f0-9b88-960cebf8b91e")
    val TO_VEHICLE_UUID: UUID = UUID.fromString("00000212-b2d1-43f0-9b88-960cebf8b91e")
    val FROM_VEHICLE_UUID: UUID = UUID.fromString("00000213-b2d1-43f0-9b88-960cebf8b91e")
    val VERSION_UUID: UUID = UUID.fromString("00000214-b2d1-43f0-9b88-960cebf8b91e")
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
