// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.protocol.ChargingStateKind
import com.dzid26.teslable.core.protocol.InfotainmentPollPolicy
import com.dzid26.teslable.core.protocol.asleep
import com.dzid26.teslable.core.protocol.locked
import com.dzid26.teslable.core.protocol.userPresent
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.vcsec.VehicleStatus
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
    val status: VehicleStatus? = null,
    val keySlot: Int? = null,
    val sessions: List<String> = emptyList(),
    val charge: ChargeState? = null,
    /** When [charge] was last read; null until the first charge response. */
    val chargeAtMillis: Long? = null,
    /** Pairing flow state for this car; idle unless a Pair is in flight. */
    val pairing: PairingPhase = PairingPhase.IDLE,
    val pairingKeyId: String? = null,
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
    CHECKING,
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
    /** The car the UI is focused on, by advertised name (stable across address changes). */
    val selectedBleName: String? = null,
    /** Set when a notification tap asks the UI to open a specific car. */
    val openVehicleRequest: String? = null,
    val log: List<LogEntry> = emptyList(),
)

/** One log line, tagged with the car it came from (null for app-wide lines). */
data class LogEntry(
    val vehicleId: String?,
    val message: String,
)

/** The live connection for the selected car, if it has one. */
fun BleUiState.selectedConnection(): TeslaConnection? {
    val address =
        vehicles.firstOrNull { it.bleName == selectedBleName }?.address
            ?: devices.firstOrNull { it.name == selectedBleName }?.address
            ?: return null
    return connections[address]
}

/**
 * True when an enrolled car is connected, so the tracking service should run.
 * A pure function of the UI state so Compose can observe it directly.
 */
fun BleUiState.shouldTrack(): Boolean =
    trackingEnabled &&
        connections.values.any { connection ->
            connection.phase == ConnectionPhase.READY &&
                (
                    connection.pairing == PairingPhase.OK ||
                        connection.keySlot != null ||
                        connection.sessions.isNotEmpty()
                )
        }

/** Slack on top of two read intervals for a late read (BLE retries, a busy main thread). */
const val FRESH_SLACK_MS = 5_000L

/**
 * How recent a reading must be to count as fresh: still being read. This
 * follows the poll policy's read rate: two [InfotainmentPollPolicy.READ_INTERVAL_MS]
 * plus [FRESH_SLACK_MS], so one missed or late read does not flip it, yet a
 * reading turns stale within seconds of the policy no longer reading. If the
 * policy's interval becomes adaptive, this should follow the interval in force
 * instead of the constant. Everything older is stale (gray, with its age).
 */
const val FRESH_READING_MS = 2 * InfotainmentPollPolicy.READ_INTERVAL_MS + FRESH_SLACK_MS

/**
 * How often a screen or the notification re-checks reading ages when no
 * reading is about to turn stale, so ages ("12m ago") and state durations stay
 * current on a quiet screen.
 */
const val STALENESS_TICK_MS = 60_000L

/**
 * How long to wait before the clock is read again: until the newest reading
 * turns stale while it is still fresh (so it greys right at [FRESH_READING_MS],
 * not at the next minute), otherwise [STALENESS_TICK_MS].
 */
fun stalenessTickDelayMs(
    readAtMillis: Long?,
    nowMillis: Long,
): Long {
    if (readAtMillis == null) return STALENESS_TICK_MS
    val untilStale = readAtMillis + FRESH_READING_MS - nowMillis
    return if (untilStale > 0) minOf(untilStale + 1, STALENESS_TICK_MS) else STALENESS_TICK_MS
}

/** A battery percentage with its freshness: live, or the last stored sample. */
data class BatteryPercent(
    val value: Int,
    val stale: Boolean,
    /** When the reading was taken, when known. */
    val readAtMillis: Long?,
)

/**
 * The percentage to show for a car: the live charge when there is one,
 * otherwise the newest stored sample. [BatteryPercent.stale] marks anything
 * not read within [FRESH_READING_MS], including every stored fallback.
 */
fun batteryPercent(
    connection: TeslaConnection?,
    lastKnown: BatterySample?,
    nowMillis: Long,
): BatteryPercent? {
    val live = connection?.charge?.battery_level
    if (live != null) {
        val readAt = connection.chargeAtMillis
        return BatteryPercent(
            value = live,
            stale = readAt == null || nowMillis - readAt >= FRESH_READING_MS,
            readAtMillis = readAt,
        )
    }
    return lastKnown?.let {
        BatteryPercent(value = it.percent, stale = true, readAtMillis = it.timestampMillis)
    }
}

/**
 * How long ago a reading was taken, worded the same on the notification and the
 * car view: "just now" while the reading is fresh ([FRESH_READING_MS]), "<1m ago"
 * up to a minute, then whole minutes ("7m ago") up to an hour, then whole hours
 * ("2h ago") and days ("3d ago"). A stale reading always gets one of the ages.
 */
fun readingAgeText(ageMillis: Long): String {
    val minutes = ageMillis / 60_000
    return when {
        ageMillis < FRESH_READING_MS -> "just now"
        minutes < 1 -> "<1m ago"
        minutes < 60 -> "${minutes}m ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}

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
    vehicle: Vehicle? = null,
): ConnectionDisplay {
    val title =
        vehicle?.displayName?.takeIf { it.isNotBlank() }
            ?: connection?.gattDeviceName
            ?: vehicle?.gattName
            ?: connection?.name
            ?: advert?.name
            ?: "Tesla"
    return ConnectionDisplay(
        title = title,
        stateText = connectionStateText(connection),
        // Live RSSI while connected; otherwise the scan advert's, so unpaired
        // cars show signal strength too.
        rssi = if (connection?.phase == ConnectionPhase.READY) connection.rssi ?: advert?.rssi else advert?.rssi,
    )
}

/**
 * The connection states: disconnected, connected while asleep, connected, or
 * connected with a battery percentage.
 */
private fun connectionStateText(connection: TeslaConnection?): String =
    when {
        connection == null -> "Disconnected"

        connection.phase == ConnectionPhase.READY && connection.status?.asleep == true ->
            "Connected \uD83D\uDCA4"

        connection.phase == ConnectionPhase.READY && connection.charge?.battery_level != null ->
            "Connected · ${connection.charge.battery_level}%"

        connection.phase == ConnectionPhase.READY -> "Connected"

        connection.phase == ConnectionPhase.FAILED -> "Disconnected"

        connection.phase == ConnectionPhase.DISCONNECTED -> "Disconnected"

        connection.phase == ConnectionPhase.IDLE -> "Disconnected"

        else -> "Connecting..."
    }

/** "Locked · Asleep · User away" instead of raw booleans. */
fun vehicleStatusText(status: VehicleStatus): String =
    listOf(
        if (status.locked) "Locked" else "Unlocked",
        if (status.asleep) "Asleep" else "Awake",
        if (status.userPresent) "User present" else "User away",
    ).joinToString(" · ")

/** Friendly names for the per-domain secure sessions. */
fun sessionNames(sessions: List<String>): String =
    sessions.joinToString(", ") { session ->
        when {
            session.contains("VEHICLE_SECURITY") -> "Security"
            session.contains("INFOTAINMENT") -> "Infotainment"
            session.contains("BROADCAST") -> "Broadcast"
            else -> session
        }
    }

/** Friendly charging state for the charge details line. */
fun chargingStateText(state: ChargingStateKind): String =
    when (state) {
        ChargingStateKind.Charging -> "Charging"
        ChargingStateKind.Complete -> "Charging complete"
        ChargingStateKind.Stopped -> "Charging stopped"
        ChargingStateKind.Disconnected -> "Unplugged"
        ChargingStateKind.NoPower -> "No power"
        ChargingStateKind.Starting -> "Starting"
        ChargingStateKind.Calibrating -> "Calibrating"
        ChargingStateKind.Unknown -> "Unknown"
    }
