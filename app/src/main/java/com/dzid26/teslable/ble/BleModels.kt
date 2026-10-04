package com.dzid26.teslable.ble

import com.dzid26.teslable.core.protocol.TeslaVcsec
import java.util.UUID

data class TeslaAdvert(
    val name: String,
    val address: String,
    val rssi: Int,
    val connectable: Boolean,
)

data class TeslaConnection(
    val address: String,
    val name: String,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val gattDeviceName: String? = null,
    val services: List<GattServiceInfo> = emptyList(),
    val mtu: Int? = null,
    val status: TeslaVcsec.Status? = null,
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
    val advertisementsSeen: Int = 0,
    val devices: List<TeslaAdvert> = emptyList(),
    val connections: Map<String, TeslaConnection> = emptyMap(),
    val vinInput: String = "",
    val expectedBleName: String? = null,
    val log: List<String> = emptyList(),
)
