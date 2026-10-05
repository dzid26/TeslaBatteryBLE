// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import com.dzid26.teslable.core.history.BatteryHistoryCsv
import com.dzid26.teslable.core.history.BatteryHistoryLog
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.protocol.TeslaCommands
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
 * Battery history as an append-only protobuf log in app storage, cached in
 * memory and exposed as a [StateFlow]. One record per SOC read, tagged with
 * the vehicle it came from; repeated identical readings within a minute are
 * skipped so polling does not flood the file. The record schema is additive
 * (ADR-0006), so new fields never drop old rows. On first run the raw-only
 * CSV is imported once and retired; rows from older formats are skipped
 * (pre-1.0 hygiene).
 */
class BatteryHistoryStore(
    context: Context,
) {
    private val logFile = File(context.filesDir, LOG_FILE_NAME)
    private val legacyCsvFile = File(context.filesDir, LEGACY_CSV_FILE_NAME)
    private val importedCsvFile = File(context.filesDir, "$LEGACY_CSV_FILE_NAME.imported")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _samples = MutableStateFlow<List<BatterySample>>(emptyList())
    val samples: StateFlow<List<BatterySample>> = _samples.asStateFlow()

    init {
        scope.launch {
            mutex.withLock { _samples.value = load() }
        }
    }

    fun record(
        vehicleId: String,
        charge: TeslaCommands.Charge,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val batteryLevel = charge.batteryLevel ?: return
        val sample =
            BatterySample(
                timestampMillis = nowMillis,
                batteryLevel = batteryLevel,
                chargingState = charge.chargingState,
                chargeLimit = charge.chargeLimit,
                vehicleId = vehicleId,
                usableBatteryLevel = charge.usableBatteryLevel,
                ratedRangeMiles = charge.batteryRange,
                estRangeMiles = charge.estBatteryRange,
                idealRangeMiles = charge.idealBatteryRange,
                chargeEnergyAdded = charge.chargeEnergyAdded,
                chargeMilesAddedRated = charge.chargeMilesAddedRated,
                chargeMilesAddedIdeal = charge.chargeMilesAddedIdeal,
                chargeRateMph = charge.chargeRateMph,
                chargeRateMphFloat = charge.chargeRateMphFloat,
            )
        scope.launch {
            mutex.withLock {
                val current = _samples.value
                val last = current.lastOrNull { it.vehicleId == sample.vehicleId }
                if (isDuplicate(last, sample)) {
                    return@withLock
                }
                val updated = (current + sample).takeLast(MAX_SAMPLES)
                if (updated.size > current.size) {
                    logFile.appendBytes(BatteryHistoryLog.encodeFrame(sample))
                } else {
                    rewrite(updated)
                }
                _samples.value = updated
            }
        }
    }

    private fun load(): List<BatterySample> {
        if (logFile.exists()) {
            return runCatching {
                BatteryHistoryLog.decode(logFile.readBytes()).takeLast(MAX_SAMPLES)
            }.getOrDefault(emptyList())
        }
        return importLegacyCsv()
    }

    /** One-time import of the raw-only CSV; the file is then retired. */
    private fun importLegacyCsv(): List<BatterySample> {
        if (!legacyCsvFile.exists()) return emptyList()
        val samples =
            runCatching {
                BatteryHistoryCsv.parseAll(legacyCsvFile.readText()).takeLast(MAX_SAMPLES)
            }.getOrNull() ?: return emptyList()
        runCatching {
            if (samples.isNotEmpty()) {
                logFile.writeBytes(BatteryHistoryLog.encode(samples))
            }
            Files.move(legacyCsvFile.toPath(), importedCsvFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return samples
    }

    private fun rewrite(samples: List<BatterySample>) {
        val tmp = File(logFile.parentFile, "$LOG_FILE_NAME.tmp")
        tmp.writeBytes(BatteryHistoryLog.encode(samples))
        Files.move(tmp.toPath(), logFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun isDuplicate(
        last: BatterySample?,
        sample: BatterySample,
    ): Boolean =
        last != null &&
            last.percent == sample.percent &&
            last.chargingState == sample.chargingState &&
            sample.timestampMillis - last.timestampMillis < DEDUPE_WINDOW_MS

    private companion object {
        const val LOG_FILE_NAME = "battery-history.pb"
        const val LEGACY_CSV_FILE_NAME = "battery-history.csv"
        const val MAX_SAMPLES = 20_000
        const val DEDUPE_WINDOW_MS = 60_000L
    }
}
