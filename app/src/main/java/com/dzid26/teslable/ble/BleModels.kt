package com.dzid26.teslable.ble

import java.util.UUID

data class TeslaAdvert(
    val name: String,
    val address: String,
    val rssi: Int,
    val connectable: Boolean,
)

data class GattServiceInfo(
    val uuid: UUID,
    val characteristicUuids: List<UUID>,
)

enum class ConnectionPhase {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCOVERING,
    READY,
    FAILED,
    DISCONNECTED,
}

data class BleUiState(
    val scanning: Boolean = false,
    val devices: List<TeslaAdvert> = emptyList(),
    val vinInput: String = "",
    val expectedBleName: String? = null,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val connectedAddress: String? = null,
    val connectedAdvertisedName: String? = null,
    val gattDeviceName: String? = null,
    val services: List<GattServiceInfo> = emptyList(),
    val mtu: Int? = null,
    val log: List<String> = emptyList(),
)
