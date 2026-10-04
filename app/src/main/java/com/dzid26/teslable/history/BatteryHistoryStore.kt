// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.history

import android.content.Context
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
 * exposed as a [StateFlow]. One line per SOC read; repeated identical readings
 * within a minute are skipped so polling does not flood the file. First cut per
 * the ADR: Room/SQLite when queries outgrow this.
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

    fun record(charge: TeslaCommands.Charge, nowMillis: Long = System.currentTimeMillis()) {
        val percent = charge.batteryLevel ?: return
        val sample = BatterySample(
            timestampMillis = nowMillis,
            percent = percent,
            chargingState = charge.chargingState,
            chargeLimit = charge.chargeLimit,
        )
        scope.launch {
            mutex.withLock {
                val current = _samples.value
                val last = current.lastOrNull()
                if (last != null &&
                    last.percent == sample.percent &&
                    last.chargingState == sample.chargingState &&
                    sample.timestampMillis - last.timestampMillis < DEDUPE_WINDOW_MS
                ) {
                    return@withLock
                }
                val updated = (current + sample).takeLast(MAX_SAMPLES)
                if (updated.size > current.size) {
                    file.appendText(sample.toLine() + "\n")
                } else {
                    file.writeText(updated.joinToString("\n") { it.toLine() } + "\n")
                }
                _samples.value = updated
            }
        }
    }

    private fun readFile(): List<BatterySample> {
        if (!file.exists()) return emptyList()
        return runCatching {
            file.readLines().mapNotNull(::parseLine).takeLast(MAX_SAMPLES)
        }.getOrDefault(emptyList())
    }

    private fun parseLine(line: String): BatterySample? {
        val parts = line.split(',')
        if (parts.size < 2) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val percent = parts[1].toIntOrNull() ?: return null
        return BatterySample(
            timestampMillis = timestamp,
            percent = percent,
            chargingState = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
            chargeLimit = parts.getOrNull(2)?.toIntOrNull(),
        )
    }

    private fun BatterySample.toLine(): String = listOf(
        timestampMillis.toString(),
        percent.toString(),
        chargeLimit?.toString() ?: "",
        chargingState?.replace(',', ' ') ?: "",
    ).joinToString(",")

    private companion object {
        const val FILE_NAME = "battery-history.csv"
        const val MAX_SAMPLES = 20_000
        const val DEDUPE_WINDOW_MS = 60_000L
    }
}
