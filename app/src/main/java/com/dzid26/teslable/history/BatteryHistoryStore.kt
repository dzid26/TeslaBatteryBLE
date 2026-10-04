// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
import com.dzid26.teslable.core.history.BatteryHistoryCsv
import com.dzid26.teslable.core.history.BatterySample
import com.dzid26.teslable.core.protocol.TeslaCommands
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Battery history as an append-only CSV in app storage, cached in memory and
 * exposed as a [StateFlow]. One line per SOC read, tagged with the vehicle it
 * came from; repeated identical readings within a minute are skipped so polling
 * does not flood the file. First cut per ADR-0002: Room/SQLite when queries
 * outgrow this.
 */
class BatteryHistoryStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _samples = MutableStateFlow<List<BatterySample>>(emptyList())
    val samples: StateFlow<List<BatterySample>> = _samples.asStateFlow()

    init {
        scope.launch {
            mutex.withLock { _samples.value = readFile() }
        }
    }

    fun record(
        vehicleId: String,
        charge: TeslaCommands.Charge,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val percent = charge.batteryLevel ?: return
        val sample = BatterySample(
            timestampMillis = nowMillis,
            percent = percent,
            chargingState = charge.chargingState,
            chargeLimit = charge.chargeLimit,
            vehicleId = vehicleId,
        )
        scope.launch {
            mutex.withLock {
                val current = _samples.value
                val last = current.lastOrNull { it.vehicleId == sample.vehicleId }
                if (last != null &&
                    last.percent == sample.percent &&
                    last.chargingState == sample.chargingState &&
                    sample.timestampMillis - last.timestampMillis < DEDUPE_WINDOW_MS
                ) {
                    return@withLock
                }
                val updated = (current + sample).takeLast(MAX_SAMPLES)
                if (updated.size > current.size) {
                    file.appendText(BatteryHistoryCsv.encode(sample) + "\n")
                } else {
                    file.writeText(
                        updated.joinToString(separator = "\n", postfix = "\n") {
                            BatteryHistoryCsv.encode(it)
                        }
                    )
                }
                _samples.value = updated
            }
        }
    }

    private fun readFile(): List<BatterySample> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines()
                .mapNotNull(BatteryHistoryCsv::parse)
                .takeLast(MAX_SAMPLES)
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val FILE_NAME = "battery-history.csv"
        const val MAX_SAMPLES = 20_000
        const val DEDUPE_WINDOW_MS = 60_000L
    }
}
