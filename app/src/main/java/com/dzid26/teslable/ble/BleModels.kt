// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.protocol.TeslaCommands
import com.dzid26.teslable.core.protocol.TeslaVcsec
import java.util.UUID

data class TeslaAdvert(
    val name: String,
    val address: String,
    val rssi: Int? = null,
)

data class TeslaConnection(
    val address: String,
    val name: String,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val gattDeviceName: String? = null,
    val services: List<GattServiceInfo> = emptyList(),
    val mtu: Int? = null,
    val rssi: Int? = null,
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
    val discovering: Boolean = false,
    val explicitScan: Boolean = false,
    val trackingEnabled: Boolean = true,
    val devices: List<TeslaAdvert> = emptyList(),
    val connections: Map<String, TeslaConnection> = emptyMap(),
    /** Known cars, most recently seen first. */
    val vehicles: List<Vehicle> = emptyList(),
    val selectedAddress: String? = null,
    val vinInput: String = "",
    val expectedBleName: String? = null,
    val pairingPhase: PairingPhase = PairingPhase.IDLE,
    val pairingKeyId: String? = null,
    val log: List<String> = emptyList(),
)

/** The title and status line both the app list and the notification show for a car. */
data class ConnectionDisplay(
    val title: String,
    val stateText: String,
    val rssi: Int?,
) {
    val status: String get() = rssi?.let { "$stateText · RSSI $it dBm" } ?: stateText
}

/**
 * Builds the display for a car, shared by the app list and the tracking
 * notification so the two surfaces cannot drift apart.
 */
fun connectionDisplay(
    connection: TeslaConnection?,
    advert: TeslaAdvert?,
    showHints: Boolean = false,
    vehicle: Vehicle? = null,
): ConnectionDisplay {
    val title = vehicle?.displayName?.takeIf { it.isNotBlank() }
        ?: connection?.gattDeviceName
        ?: vehicle?.gattName
        ?: connection?.name
        ?: advert?.name
        ?: "Tesla"
    return ConnectionDisplay(
        title = title,
        stateText = connectionStateText(connection, showHints),
        rssi = connection?.rssi ?: advert?.rssi,
    )
}

/**
 * The four connection states: disconnected, connected while asleep, connected
 * and reading, or connected with a battery percentage.
 */
private fun connectionStateText(connection: TeslaConnection?, showHints: Boolean): String = when {
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

/** "Locked · Asleep · User away" instead of raw booleans. */
fun vehicleStatusText(status: TeslaVcsec.Status): String = listOf(
    if (status.locked) "Locked" else "Unlocked",
    if (status.asleep) "Asleep" else "Awake",
    if (status.userPresent) "User present" else "User away",
).joinToString(" · ")

/** Friendly names for the per-domain secure sessions. */
fun sessionNames(sessions: List<String>): String = sessions.joinToString(", ") { session ->
    when {
        session.contains("VEHICLE_SECURITY") -> "Security"
        session.contains("INFOTAINMENT") -> "Infotainment"
        session.contains("BROADCAST") -> "Broadcast"
        else -> session
    }
}

/** Friendly charging state for the charge details line. */
fun chargingStateText(state: String): String = when (state) {
    "Charging" -> "Charging"
    "Complete" -> "Charging complete"
    "Stopped" -> "Charging stopped"
    "Disconnected" -> "Unplugged"
    "NoPower" -> "No power"
    "Starting" -> "Starting"
    "Calibrating" -> "Calibrating"
    else -> state
}
