// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import com.dzid26.teslable.core.protocol.InfotainmentPollPolicy
import com.dzid26.teslable.core.protocol.ShiftStateKind
import com.dzid26.teslable.core.protocol.TeslaCommands
import com.dzid26.teslable.core.protocol.climateOn
import com.dzid26.teslable.core.protocol.sentryOn
import com.dzid26.teslable.core.protocol.shiftStateKind
import com.dzid26.teslable.history.HistoryStore
import java.time.Instant

/**
 * Handles the Infotainment replies that follow a charge reply (drive, closures, climate) for one
 * car: each goes to the history store verbatim (ADR-0008), feeds the poll policy its flag
 * (ADR-0009), and reaches the debug log only as a change of that flag, never as a location, route or
 * destination. One instance per vehicle link.
 *
 * @param rssi the phone's latest RSSI for the car, or null when it has none.
 * @param log writes a line to the debug log, prefixed by the caller.
 */
internal class InfotainmentStateReplies(
    private val vehicleId: String,
    private val historyStore: HistoryStore,
    private val policy: InfotainmentPollPolicy,
    private val rssi: () -> Int?,
    private val log: (String) -> Unit,
) {
    /** The last value written to the debug log for each flag, so only a change is logged again. */
    private var lastShiftState: ShiftStateKind? = null
    private var lastSentryOn: Boolean? = null
    private var lastClimateOn: Boolean? = null

    /** A DriveState reply. Only a shift-state change reaches the debug log. */
    fun onDrive(
        plaintext: ByteArray,
        deviceTimestamp: Instant,
    ) {
        val drive = runCatching { TeslaCommands.parseDriveState(plaintext) }.getOrNull()
        if (drive == null) {
            log("drive response missing data${refusal(plaintext)}")
            return
        }
        historyStore.recordDrive(vehicleId, drive, deviceTimestamp, rssi())
        val shift = drive.shiftStateKind
        policy.onDriveReading(shift)
        if (shift != lastShiftState) {
            lastShiftState = shift
            log("shift state ${shift?.name ?: "unknown"}")
        }
    }

    /** A ClosuresState reply, which holds the sentry mode state. */
    fun onClosures(
        plaintext: ByteArray,
        deviceTimestamp: Instant,
    ) {
        val closures = runCatching { TeslaCommands.parseClosuresState(plaintext) }.getOrNull()
        if (closures == null) {
            log("closures response missing data${refusal(plaintext)}")
            return
        }
        historyStore.recordClosures(vehicleId, closures, deviceTimestamp, rssi())
        val sentry = closures.sentryOn
        policy.onClosuresReading(sentry)
        if (sentry != lastSentryOn) {
            lastSentryOn = sentry
            log("sentry ${if (sentry) "on" else "off"}")
        }
    }

    /** A ClimateState reply. */
    fun onClimate(
        plaintext: ByteArray,
        deviceTimestamp: Instant,
    ) {
        val climate = runCatching { TeslaCommands.parseClimateState(plaintext) }.getOrNull()
        if (climate == null) {
            log("climate response missing data${refusal(plaintext)}")
            return
        }
        historyStore.recordClimate(vehicleId, climate, deviceTimestamp, rssi())
        val on = climate.climateOn
        policy.onClimateReading(on)
        if (on != lastClimateOn) {
            lastClimateOn = on
            log("climate ${if (on) "on" else "off"}")
        }
    }

    /** The reason a reply carried no data, for the debug log: " (RESULT)" when the car refused, else "". */
    private fun refusal(plaintext: ByteArray): String =
        runCatching { TeslaCommands.parseActionStatus(plaintext) }.getOrNull()?.let { " ($it)" } ?: ""
}
