// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.protocol.ChargingStateKind
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

/**
 * How recent a live reading must be to count as fresh (blue), while the link is
 * READY and the car is not asleep. An awake, idle car's charge does not move, and
 * charging or driving keeps reads coming every few seconds, so a reading stays
 * fresh for this long after it was read, and goes stale at once when the link
 * drops or the car's latest VCSEC status says asleep, whichever comes first. The
 * stored fallback is always stale.
 */
const val FRESH_READING_MS = 5 * 60_000L

/**
 * How often a screen or the notification re-checks reading ages when no
 * reading is about to turn stale, so ages ("12m ago") stay current on a quiet screen.
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
    /** The phone's clock when the reading was read, when known; never the car's clock. */
    val readAtMillis: Long?,
)

/**
 * The age shown in the status card's top-right corner for a stale percentage
 * ("12m ago"); null for a fresh reading (still being read) and for one whose
 * read time is unknown. When the car is not connected ([connection] null or not
 * READY) an age under a minute reads "<1m ago", never "now", which would look
 * like a live reading; "now" stays for a connected car whose reading greyed
 * because it fell asleep.
 */
fun BatteryPercent.ageLabel(
    nowMillis: Long,
    connection: TeslaConnection?,
): String? {
    val readAt = readAtMillis?.takeIf { stale } ?: return null
    val age = readingAgeText(nowMillis - readAt)
    val connected = connection?.phase == ConnectionPhase.READY
    return if (!connected && age == READING_NOW_TEXT) READING_UNDER_A_MINUTE_TEXT else age
}

private const val READING_NOW_TEXT = "now"
private const val READING_UNDER_A_MINUTE_TEXT = "<1m ago"

/**
 * The percentage to show for a car: the live charge when there is one,
 * otherwise the newest stored sample. [BatteryPercent.stale] marks a live
 * reading that is [FRESH_READING_MS] old or older, whose connection is not READY,
 * or whose car's latest status says asleep, and every stored fallback.
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
            stale =
                readAt == null ||
                    nowMillis - readAt >= FRESH_READING_MS ||
                    connection.phase != ConnectionPhase.READY ||
                    connection.status?.asleep == true,
            readAtMillis = readAt,
        )
    }
    return lastKnown?.let {
        // The age is the phone's read time; the sample's own timestamp is the car's clock.
        BatteryPercent(value = it.percent, stale = true, readAtMillis = it.readAtMillis)
    }
}

/**
 * How long ago a reading was taken, worded the same on the notification and the
 * car view: "now" under a minute, then whole minutes ("7m ago") up to an hour,
 * then whole hours ("2h ago") and days ("3d ago"). It does not depend on
 * freshness: a reading greyed by sleep 3 minutes after it was read says "3m ago".
 */
fun readingAgeText(ageMillis: Long): String {
    val minutes = ageMillis / 60_000
    return when {
        minutes < 1 -> READING_NOW_TEXT
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
