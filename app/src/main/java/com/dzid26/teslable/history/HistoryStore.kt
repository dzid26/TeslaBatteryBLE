// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import com.dzid26.teslable.core.history.AppState
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.CommandResult
import com.dzid26.teslable.core.history.ConnectionEvent
import com.dzid26.teslable.core.history.DriveSample
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.core.history.shouldLogStatus
import com.dzid26.teslable.core.history.toBatterySample
import com.dzid26.teslable.core.history.toDriveSample
import com.dzid26.teslable.core.history.toStatusSample
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.ClosuresState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.vcsec.VehicleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant

/**
 * BLE history as append-only logs of the replies the phone acquired: each is
 * the car's raw response (ADR-0006) wrapped in a [BleRecord] next to the
 * phone's acquisition time (ADR-0008), so new car fields never drop old rows.
 * One file per vehicle per kind in `filesDir/battery-history/`, cached in
 * memory and exposed as [StateFlow]s:
 * - `<vehicleId>.vcsec.pblog`: VCSEC status readings logged when
 *   [shouldLogStatus] says so, timed by `device_timestamp` ([statusSamples]);
 * - `<vehicleId>.charge.pblog`: every charge reply, raw; the chart uses the
 *   ones with the car's own `ChargeState.timestamp` and a level ([samples]);
 * - `<vehicleId>.drive.pblog`: every DriveState reply, raw; [driveSamples]
 *   holds the ones with the car's own `DriveState.timestamp`;
 * - `<vehicleId>.closures.pblog`: every ClosuresState reply (it holds the
 *   sentry mode state), raw; log only, nothing reads it back yet;
 * - `<vehicleId>.climate.pblog`: every ClimateState reply, raw; log only;
 * - `<vehicleId>.connection.pblog`: when the connection to the car became
 *   ready or an established one ended. Log only: nothing reads it back yet;
 * - `<vehicleId>.command.pblog`: the requests the app sent to the car, raw and
 *   with the reason, and the car's refusals of them (not the routine VCSEC
 *   status poll). Log only;
 * - `app.pblog`: the app's own state (screen, foreground, tracking service),
 *   one file for the whole app. Log only.
 *
 * Every record about a car also carries the phone's latest RSSI for it, when it
 * had one (`rssi`).
 *
 * Older raw `<vehicleId>.pblog` charge files and the pre-store CSV history are
 * neither read nor written (pre-1.0 reset).
 */
class HistoryStore(
    context: Context,
) {
    private val historyDir = File(context.filesDir, HISTORY_DIR_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val chargeLogs = VehicleLogs(historyDir, CHARGE_LOG_SUFFIX)
    private val statusLogs = VehicleLogs(historyDir, STATUS_LOG_SUFFIX)
    private val driveLogs = VehicleLogs(historyDir, DRIVE_LOG_SUFFIX)
    private val closuresLogs = VehicleLogs(historyDir, CLOSURES_LOG_SUFFIX)
    private val climateLogs = VehicleLogs(historyDir, CLIMATE_LOG_SUFFIX)
    private val connectionLogs = VehicleLogs(historyDir, CONNECTION_LOG_SUFFIX)
    private val commandLogs = VehicleLogs(historyDir, COMMAND_LOG_SUFFIX)
    private val appLog = AppLog(historyDir)
    private val _samples = MutableStateFlow<List<BatterySample>>(emptyList())
    val samples: StateFlow<List<BatterySample>> = _samples.asStateFlow()
    private val _statusSamples = MutableStateFlow<List<StatusSample>>(emptyList())

    /** VCSEC status readings logged for every car, oldest first. */
    val statusSamples: StateFlow<List<StatusSample>> = _statusSamples.asStateFlow()
    private val _driveSamples = MutableStateFlow<List<DriveSample>>(emptyList())

    /** DriveState readings logged for every car, oldest first. */
    val driveSamples: StateFlow<List<DriveSample>> = _driveSamples.asStateFlow()

    /** The newest status record logged per vehicle, which [shouldLogStatus] compares the next reading against. */
    private val lastLoggedStatus = mutableMapOf<String, BleRecord>()

    init {
        scope.launch {
            mutex.withLock {
                _samples.value = readSamples()
                _statusSamples.value = readStatusSamples()
                _driveSamples.value = readDriveSamples()
                // Closures, climate and connection records have no read model
                // yet. Reading the files only refreshes the record counts the
                // cap trims by, so it holds across restarts.
                runCatching { closuresLogs.readAll() }
                runCatching { climateLogs.readAll() }
                runCatching { connectionLogs.readAll() }
                runCatching { commandLogs.readAll() }
                runCatching { appLog.readAll() }
            }
        }
    }

    /**
     * Logs one charge reply. [deviceTimestamp] is the phone's clock when it
     * arrived; it goes only into the record's own `device_timestamp`, so the sample
     * stays on the car's `ChargeState.timestamp`. [rssi] is the phone's
     * latest RSSI for the car, or null when it has none.
     */
    fun record(
        vehicleId: String,
        charge: ChargeState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        // Every reply is logged as the car sent it. Chart time comes only from
        // the car's own timestamp and is never filled in, so a reply without
        // one (or without a level) stays in the log but off the chart.
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, charge_state = charge)
        val sample = record.toBatterySample(vehicleId)
        scope.launch {
            mutex.withLock {
                chargeLogs.append(vehicleId, record)
                if (sample != null) _samples.value = (_samples.value + sample).takeLast(MAX_SAMPLES)
            }
        }
    }

    /**
     * Logs one VCSEC status reading when [shouldLogStatus] says so. The reply
     * carries no time, so [deviceTimestamp] is the phone's clock at receipt
     * and the reading's time on the timeline; it goes only into the record's
     * own `device_timestamp`, never into a car field. [rssi] is the phone's latest
     * RSSI for the car, or null when it has none.
     */
    fun recordStatus(
        vehicleId: String,
        status: VehicleStatus,
        deviceTimestamp: Instant,
        rssi: Int?,
        firstAfterConnect: Boolean,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, vehicle_status = status)
        scope.launch {
            mutex.withLock {
                if (shouldLogStatus(lastLoggedStatus[vehicleId], status, deviceTimestamp, firstAfterConnect)) {
                    appendStatus(vehicleId, record)
                }
            }
        }
    }

    /**
     * Logs one DriveState reply, verbatim: every field the car sent, the
     * navigation destination and route included. [deviceTimestamp] is the
     * phone's clock when it arrived; it goes only into the record's own
     * `device_timestamp`, so the sample stays on the car's `DriveState.timestamp`.
     * [rssi] is the phone's latest RSSI for the car, or null when it has none.
     */
    fun recordDrive(
        vehicleId: String,
        drive: DriveState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        // Every reply is logged as the car sent it. Drive time comes only from
        // the car's own timestamp and is never filled in, so a reply without
        // one stays in the log but out of [driveSamples].
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, drive_state = drive)
        val sample = record.toDriveSample(vehicleId)
        scope.launch {
            mutex.withLock {
                driveLogs.append(vehicleId, record)
                if (sample != null) _driveSamples.value = (_driveSamples.value + sample).takeLast(MAX_SAMPLES)
            }
        }
    }

    /**
     * Logs one ClosuresState reply, verbatim (ADR-0008). It holds the sentry
     * mode state next to the doors, windows and lock state. [deviceTimestamp] is the
     * phone's clock when it arrived; [rssi] is the phone's latest RSSI for the
     * car, or null when it has none. No read model yet.
     */
    fun recordClosures(
        vehicleId: String,
        closures: ClosuresState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, closures_state = closures)
        scope.launch { mutex.withLock { closuresLogs.append(vehicleId, record) } }
    }

    /**
     * Logs one ClimateState reply, verbatim (ADR-0008). [deviceTimestamp] is the
     * phone's clock when it arrived; [rssi] is the phone's latest RSSI for the
     * car, or null when it has none. No read model yet.
     */
    fun recordClimate(
        vehicleId: String,
        climate: ClimateState,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, climate_state = climate)
        scope.launch { mutex.withLock { climateLogs.append(vehicleId, record) } }
    }

    /**
     * Logs a change in the phone's connection to [vehicleId] (ADR-0008):
     * [state] is `CONNECTED` when the connection became ready and
     * `DISCONNECTED` when an established one ended, so a DISCONNECTED marks
     * when watching ended. [deviceTimestamp] is when it happened, and [rssi]
     * the phone's last RSSI for the car before that, or null when it has none.
     * Nothing reads these records back yet.
     */
    fun recordConnection(
        vehicleId: String,
        state: ConnectionEvent.State,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record =
            BleRecord(
                device_timestamp = deviceTimestamp,
                rssi = rssi,
                connection_event = ConnectionEvent(state = state),
            )
        scope.launch {
            mutex.withLock {
                connectionLogs.append(vehicleId, record)
            }
        }
    }

    /**
     * Logs a request the app sent to [vehicleId]'s car, with its reason (ADR-0008). [command] holds the
     * plaintext request as built; the caller leaves out the routine VCSEC status poll. [deviceTimestamp]
     * is when it was sent and [rssi] the phone's latest RSSI for the car, or null when it has none.
     */
    fun recordCommand(
        vehicleId: String,
        command: Command,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, command = command)
        scope.launch { mutex.withLock { commandLogs.append(vehicleId, record) } }
    }

    /**
     * Logs that the car refused a command or never answered it (ADR-0008). Replies that went fine are
     * already visible as data records. [deviceTimestamp] is when the reply arrived, or when the
     * wait ended.
     */
    fun recordCommandResult(
        vehicleId: String,
        result: CommandResult,
        deviceTimestamp: Instant,
        rssi: Int?,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, rssi = rssi, command_result = result)
        scope.launch { mutex.withLock { commandLogs.append(vehicleId, record) } }
    }

    /** Logs a change in the app's own state (ADR-0008) to `app.pblog`; it belongs to no car, so it has no RSSI. */
    fun recordAppState(
        state: AppState,
        deviceTimestamp: Instant,
    ) {
        val record = BleRecord(device_timestamp = deviceTimestamp, app_state = state)
        scope.launch { mutex.withLock { appLog.append(record) } }
    }

    private fun appendStatus(
        vehicleId: String,
        record: BleRecord,
    ) {
        statusLogs.append(vehicleId, record)
        lastLoggedStatus[vehicleId] = record
        val sample = record.toStatusSample(vehicleId) ?: return
        _statusSamples.value = (_statusSamples.value + sample).takeLast(MAX_SAMPLES)
    }

    private fun readSamples(): List<BatterySample> =
        runCatching {
            chargeLogs
                .readAll()
                .flatMap { (vehicleId, records) ->
                    records.mapNotNull { it.toBatterySample(vehicleId) }
                }.sortedBy { it.timestampMillis }
                .takeLast(MAX_SAMPLES)
        }.getOrDefault(emptyList())

    /** Reads every status log and seeds [lastLoggedStatus] with each file's newest status record. */
    private fun readStatusSamples(): List<StatusSample> =
        runCatching {
            val logs = statusLogs.readAll()
            for ((vehicleId, records) in logs) {
                records.lastOrNull { it.vehicle_status != null }?.let { lastLoggedStatus[vehicleId] = it }
            }
            logs
                .flatMap { (vehicleId, records) ->
                    records.mapNotNull { it.toStatusSample(vehicleId) }
                }.sortedBy { it.timestampMillis }
                .takeLast(MAX_SAMPLES)
        }.getOrDefault(emptyList())

    private fun readDriveSamples(): List<DriveSample> =
        runCatching {
            driveLogs
                .readAll()
                .flatMap { (vehicleId, records) ->
                    records.mapNotNull { it.toDriveSample(vehicleId) }
                }.sortedBy { it.timestampMillis }
                .takeLast(MAX_SAMPLES)
        }.getOrDefault(emptyList())

    private companion object {
        const val HISTORY_DIR_NAME = "battery-history"

        /** `<vehicleId>.charge.pblog`: charge replies in [BleRecord]s, on the car's own timestamp (ADR-0008). */
        const val CHARGE_LOG_SUFFIX = ".charge.pblog"

        /** `<vehicleId>.vcsec.pblog`: VCSEC status replies in [BleRecord]s, timed by `device_timestamp` (ADR-0008). */
        const val STATUS_LOG_SUFFIX = ".vcsec.pblog"

        /** `<vehicleId>.drive.pblog`: DriveState replies in [BleRecord]s, on the car's own timestamp (ADR-0008). */
        const val DRIVE_LOG_SUFFIX = ".drive.pblog"

        /** `<vehicleId>.closures.pblog`: ClosuresState replies in [BleRecord]s, with `device_timestamp` (ADR-0008). */
        const val CLOSURES_LOG_SUFFIX = ".closures.pblog"

        /** `<vehicleId>.climate.pblog`: ClimateState replies in [BleRecord]s, with `device_timestamp` (ADR-0008). */
        const val CLIMATE_LOG_SUFFIX = ".climate.pblog"

        /** `<vehicleId>.connection.pblog`: connection events in [BleRecord]s, timed by `device_timestamp` (ADR-0008). */
        const val CONNECTION_LOG_SUFFIX = ".connection.pblog"

        /** `<vehicleId>.command.pblog`: commands sent and refused in [BleRecord]s, timed by `device_timestamp` (ADR-0008). */
        const val COMMAND_LOG_SUFFIX = ".command.pblog"
        const val MAX_SAMPLES = 20_000
    }
}

/** The logs the screens read: battery, drive and status samples, each oldest first. */
data class HistorySamples(
    val battery: List<BatterySample>,
    val drive: List<DriveSample>,
    val status: List<StatusSample> = emptyList(),
)
