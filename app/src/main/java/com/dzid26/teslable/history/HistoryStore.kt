// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.history.ProtoLog
import com.dzid26.teslable.core.history.toBatterySample
import com.dzid26.teslable.core.protocol.TeslaCommands
import com.tesla.generated.carserver.vehicle.ChargeState
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

/**
 * Battery history as an append-only log of raw Tesla `ChargeState` records,
 * one file per vehicle in `filesDir/battery-history/`, cached in memory and
 * exposed as a [StateFlow]. One record per SOC read. Records are the car's
 * raw response (ADR-0006), so new car fields never drop old rows. Pre-store
 * CSV history is not migrated (pre-1.0 reset).
 */
class HistoryStore(
    context: Context,
) {
    private val historyDir = File(context.filesDir, HISTORY_DIR_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _samples = MutableStateFlow<List<BatterySample>>(emptyList())
    val samples: StateFlow<List<BatterySample>> = _samples.asStateFlow()

    /** Records per vehicle file, so appends know when a file needs trimming. */
    private val recordCounts = mutableMapOf<String, Int>()

    init {
        scope.launch {
            mutex.withLock { _samples.value = readLogs() }
        }
    }

    fun record(
        vehicleId: String,
        charge: TeslaCommands.Charge,
    ) {
        // Only the car's raw response is logged, verbatim; Charge is a parsed
        // view. Time comes from the car's own timestamp and is never filled
        // in, so a record without one has no place on the timeline.
        val record = charge.raw ?: return
        val sample = record.toBatterySample(vehicleId) ?: return
        scope.launch {
            mutex.withLock {
                appendRecord(vehicleId, record)
                _samples.value = (_samples.value + sample).takeLast(MAX_SAMPLES)
            }
        }
    }

    private fun appendRecord(
        vehicleId: String,
        record: ChargeState,
    ) {
        historyDir.mkdirs()
        val file = fileFor(vehicleId)
        val count = (recordCounts[vehicleId] ?: 0) + 1
        if (count > MAX_RECORDS_PER_FILE) {
            val kept =
                (ProtoLog.decode(file.readBytes(), ChargeState.ADAPTER) + record)
                    .takeLast(MAX_RECORDS_PER_FILE - TRIM_SLACK)
            writeAtomically(file, ProtoLog.encode(kept))
            recordCounts[vehicleId] = kept.size
        } else {
            file.appendBytes(ProtoLog.encodeFrame(record))
            recordCounts[vehicleId] = count
        }
    }

    private fun readLogs(): List<BatterySample> =
        runCatching {
            readRecordFiles()
                .flatMap { (vehicleId, records) ->
                    records.mapNotNull { it.toBatterySample(vehicleId) }
                }.sortedBy { it.timestampMillis }
                .takeLast(MAX_SAMPLES)
        }.getOrDefault(emptyList())

    /** Decodes every vehicle log and refreshes [recordCounts]. */
    private fun readRecordFiles(): Map<String, List<ChargeState>> {
        val logs =
            historyDir
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(LOG_SUFFIX) }
                .orEmpty()
        val recordsByVehicle = mutableMapOf<String, List<ChargeState>>()
        for (file in logs) {
            val vehicleId = vehicleIdOf(file)
            val records = ProtoLog.decode(file.readBytes(), ChargeState.ADAPTER)
            recordCounts[vehicleId] = records.size
            recordsByVehicle[vehicleId] = records
        }
        return recordsByVehicle
    }

    private fun writeAtomically(
        file: File,
        bytes: ByteArray,
    ) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun fileFor(vehicleId: String): File = File(historyDir, "${vehicleId.ifEmpty { LEGACY_VEHICLE_STEM }}$LOG_SUFFIX")

    private fun vehicleIdOf(file: File): String = file.name.removeSuffix(LOG_SUFFIX).let { if (it == LEGACY_VEHICLE_STEM) "" else it }

    private companion object {
        const val HISTORY_DIR_NAME = "battery-history"
        const val LOG_SUFFIX = ".pblog"

        /** File stem for rows with a pre-ADR-0004 empty vehicle id. */
        const val LEGACY_VEHICLE_STEM = "legacy"
        const val MAX_SAMPLES = 20_000
        const val MAX_RECORDS_PER_FILE = 20_000

        /**
         * Records dropped below the cap on each trim, so a full file costs
         * one rewrite per thousand appends instead of one per append.
         */
        const val TRIM_SLACK = 1_000
    }
}
