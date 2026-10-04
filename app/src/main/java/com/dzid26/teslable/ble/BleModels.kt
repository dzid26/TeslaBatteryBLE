package com.dzid26.teslable.ble

import com.dzid26.teslable.core.protocol.TeslaCommands
import com.dzid26.teslable.core.protocol.TeslaVcsec
import java.util.UUID

data class TeslaAdvert(
    val name: String,
    val address: String,
    val rssi: Int,
)

data class TeslaConnection(
    val address: String,
    val name: String,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val gattDeviceName: String? = null,
    val services: List<GattServiceInfo> = emptyList(),
    val mtu: Int? = null,
    val status: TeslaVcsec.Status? = null,
    val keySlot: Int? = null,
    val sessions: List<String> = emptyList(),
    val charge: TeslaCommands.Charge? = null,
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

enum class PairingPhase {
    IDLE,
    SENDING,
    WAITING_FOR_CARD,
    OK,
    ERROR,
}

data class BleUiState(
    val scanning: Boolean = false,
    val devices: List<TeslaAdvert> = emptyList(),
    val connections: Map<String, TeslaConnection> = emptyMap(),
    val selectedAddress: String? = null,
    val vinInput: String = "",
    val expectedBleName: String? = null,
    val pairingPhase: PairingPhase = PairingPhase.IDLE,
    val pairingKeyId: String? = null,
    val log: List<String> = emptyList(),
)

/** A car that was paired and connected at least once, remembered for reconnects. */
data class KnownCar(
    val address: String,
    val name: String,
    val gattName: String? = null,
)

/**
 * The four connection states shown in the app and the tracking notification:
 * disconnected, connected while asleep, connected and reading, or connected
 * with a battery percentage.
 */
fun connectionStateText(connection: TeslaConnection?, showHints: Boolean = false): String = when {
    connection == null -> "Disconnected"

    connection.phase == ConnectionPhase.READY && connection.status?.asleep == true ->
        "Connected \uD83D\uDCA4"

    connection.phase == ConnectionPhase.READY && connection.status?.asleep == false ->
        connection.charge?.batteryLevel?.let { "Connected · $it%" } ?: "Connected (reading)"

    connection.phase == ConnectionPhase.READY -> "Connected (reading)"

    connection.phase == ConnectionPhase.FAILED ->
        if (showHints) "Disconnected · tap to retry" else "Disconnected"

    connection.phase == ConnectionPhase.DISCONNECTED ->
        if (showHints) "Disconnected · tap to reconnect" else "Disconnected"

    connection.phase == ConnectionPhase.IDLE -> "Disconnected"

    else -> "Connecting..."
}
