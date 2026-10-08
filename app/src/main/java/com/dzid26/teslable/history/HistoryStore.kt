// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.BleRecord
import com.dzid26.teslable.core.history.ProtoLog
import com.dzid26.teslable.core.history.StatusSample
import com.dzid26.teslable.core.history.shouldLogStatus
import com.dzid26.teslable.core.history.toBatterySample
import com.dzid26.teslable.core.history.toStatusSample
import com.dzid26.teslable.core.protocol.TeslaCommands
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * BLE history as append-only logs of the replies the phone acquired: each is
 * the car's raw response (ADR-0006) wrapped in a [BleRecord] next to the
 * phone's acquisition time (ADR-0008), so new car fields never drop old rows.
 * One file per vehicle per kind in `filesDir/battery-history/`, cached in
 * memory and exposed as [StateFlow]s:
 * - `<vehicleId>.charge.pblog`: one record per SOC read, on the car's own
 *   `ChargeState.timestamp` ([samples]);
 * - `<vehicleId>.status.pblog`: VCSEC status readings logged when
 *   [shouldLogStatus] says so, timed by `acquired_at` ([statusSamples]).
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
    private val _samples = MutableStateFlow<List<BatterySample>>(emptyList())
    val samples: StateFlow<List<BatterySample>> = _samples.asStateFlow()
    private val _statusSamples = MutableStateFlow<List<StatusSample>>(emptyList())

    /** VCSEC status readings logged for every car, oldest first. */
    val statusSamples: StateFlow<List<StatusSample>> = _statusSamples.asStateFlow()

    /** The newest logged status record per vehicle: what [shouldLogStatus] compares against. */
    private val lastStatus = mutableMapOf<String, BleRecord>()

    init {
        scope.launch {
            mutex.withLock {
                _samples.value = readSamples()
                _statusSamples.value = readStatusSamples()
            }
        }
    }

    /**
     * Logs one charge reply. [acquiredAtMillis] is the phone's clock when it
     * arrived; it goes only into the record's own `acquired_at`, so the sample
     * stays on the car's `ChargeState.timestamp`.
     */
    fun record(
        vehicleId: String,
        charge: TeslaCommands.Charge,
        acquiredAtMillis: Long,
    ) {
        // Only the car's raw response is logged, verbatim; Charge is a parsed
        // view. Time comes from the car's own timestamp and is never filled
        // in, so a record without one has no place on the timeline.
        val raw = charge.raw ?: return
        val record = BleRecord(acquired_at = Instant.ofEpochMilli(acquiredAtMillis), charge_state = raw)
        val sample = record.toBatterySample(vehicleId) ?: return
        scope.launch {
            mutex.withLock {
                chargeLogs.append(vehicleId, record)
                _samples.value = (_samples.value + sample).takeLast(MAX_SAMPLES)
            }
        }
    }

    /**
     * Logs one VCSEC status reading when [shouldLogStatus] says so. The reply
     * carries no time, so [acquiredAtMillis] is the phone's clock at receipt
     * and the reading's time on the timeline; it goes only into the record's
     * own `acquired_at`, never into a car field.
     */
    fun recordStatus(
        vehicleId: String,
        status: VehicleStatus,
        acquiredAtMillis: Long,
        firstAfterConnect: Boolean,
    ) {
        scope.launch {
            mutex.withLock {
                if (!shouldLogStatus(lastStatus[vehicleId], status, acquiredAtMillis, firstAfterConnect)) return@withLock
                val record = BleRecord(acquired_at = Instant.ofEpochMilli(acquiredAtMillis), vehicle_status = status)
                val sample = record.toStatusSample(vehicleId) ?: return@withLock
                statusLogs.append(vehicleId, record)
                lastStatus[vehicleId] = record
                _statusSamples.value = (_statusSamples.value + sample).takeLast(MAX_SAMPLES)
            }
        }
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

    /** Reads every status log and seeds [lastStatus] with each file's newest record. */
    private fun readStatusSamples(): List<StatusSample> =
        runCatching {
            val logs = statusLogs.readAll()
            for ((vehicleId, records) in logs) {
                records.lastOrNull()?.let { lastStatus[vehicleId] = it }
            }
            logs
                .flatMap { (vehicleId, records) ->
                    records.mapNotNull { it.toStatusSample(vehicleId) }
                }.sortedBy { it.timestampMillis }
                .takeLast(MAX_SAMPLES)
        }.getOrDefault(emptyList())

    private companion object {
        const val HISTORY_DIR_NAME = "battery-history"

        /** `<vehicleId>.charge.pblog`: charge replies in [BleRecord]s, on the car's own timestamp (ADR-0008). */
        const val CHARGE_LOG_SUFFIX = ".charge.pblog"

        /** `<vehicleId>.status.pblog`: VCSEC status replies in [BleRecord]s, timed by `acquired_at` (ADR-0008). */
        const val STATUS_LOG_SUFFIX = ".status.pblog"
        const val MAX_SAMPLES = 20_000
    }
}

/**
 * One kind of per-vehicle log: `<vehicleId><suffix>` files in [dir], holding
 * [BleRecord]s. Each kind (charge, status, and later drive as
 * `<vehicleId>.drive.pblog`) is one instance, and all share the same cap and
 * trim. Callers hold the store's mutex.
 */
private class VehicleLogs(
    private val dir: File,
    private val suffix: String,
) {
    /** Records per vehicle file, so appends know when a file needs trimming. */
    private val recordCounts = mutableMapOf<String, Int>()

    fun append(
        vehicleId: String,
        record: BleRecord,
    ) {
        dir.mkdirs()
        val file = fileFor(vehicleId)
        val count = (recordCounts[vehicleId] ?: 0) + 1
        if (count > MAX_RECORDS_PER_FILE) {
            val kept =
                (ProtoLog.decode(file.readBytes(), BleRecord.ADAPTER) + record)
                    .takeLast(MAX_RECORDS_PER_FILE - TRIM_SLACK)
            writeAtomically(file, ProtoLog.encode(kept))
            recordCounts[vehicleId] = kept.size
        } else {
            file.appendBytes(ProtoLog.encodeFrame(record))
            recordCounts[vehicleId] = count
        }
    }

    /** Decodes every vehicle file of this kind and refreshes the record counts. */
    fun readAll(): Map<String, List<BleRecord>> {
        val files = dir.listFiles() ?: return emptyMap()
        val recordsByVehicle = mutableMapOf<String, List<BleRecord>>()
        for (file in files) {
            val vehicleId = vehicleIdOf(file) ?: continue
            val records = ProtoLog.decode(file.readBytes(), BleRecord.ADAPTER)
            recordCounts[vehicleId] = records.size
            recordsByVehicle[vehicleId] = records
        }
        return recordsByVehicle
    }

    private fun fileFor(vehicleId: String): File = File(dir, "${vehicleId.ifEmpty { LEGACY_VEHICLE_STEM }}$suffix")

    /**
     * The vehicle a file of this kind belongs to, or null for any other file.
     * Each kind has its own suffix and none ends with another's, so a reader
     * never picks up another kind's file, nor an older raw `<vehicleId>.pblog`
     * charge file, which stays on disk unread.
     */
    private fun vehicleIdOf(file: File): String? {
        if (!file.isFile || !file.name.endsWith(suffix)) return null
        val stem = file.name.removeSuffix(suffix)
        if (stem.isEmpty()) return null
        return if (stem == LEGACY_VEHICLE_STEM) "" else stem
    }

    private fun writeAtomically(
        file: File,
        bytes: ByteArray,
    ) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        /** File stem for rows with a pre-ADR-0004 empty vehicle id. */
        const val LEGACY_VEHICLE_STEM = "legacy"
        const val MAX_RECORDS_PER_FILE = 20_000

        /**
         * Records dropped below the cap on each trim, so a full file costs
         * one rewrite per thousand appends instead of one per append.
         */
        const val TRIM_SLACK = 1_000
    }
}
