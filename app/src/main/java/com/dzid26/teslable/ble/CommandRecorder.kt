// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.CommandResult
import com.dzid26.teslable.core.protocol.CommandRecords
import com.dzid26.teslable.core.protocol.domain
import com.dzid26.teslable.history.HistoryStore
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageStatus
import java.time.Instant

/**
 * Writes one car's command log (ADR-0008): the requests the app sent, with the phone's clock and the
 * latest RSSI for the car, the car's refusals of them, and the commands it never answered. A hand-off
 * so the link only has to say what was sent and why; the kept messages come from `CommandRecords` in
 * core. One instance per vehicle link.
 *
 * @param rssi the phone's latest RSSI for the car, or null when it has none.
 * @param now the phone's clock in epoch milliseconds.
 */
internal class CommandRecorder(
    private val vehicleId: String,
    private val historyStore: HistoryStore,
    private val rssi: () -> Int?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** A command that went out and waits for its reply, by the id its reply will carry. */
    private class Outstanding(
        val reason: Command.Reason,
        val domain: Domain,
        val sentAtMs: Long,
    )

    private val outstanding = mutableMapOf<String, Outstanding>()

    /** A request went out to the car; [awaitingKey], when given, is the id its reply will carry. */
    fun sent(
        command: Command,
        awaitingKey: String? = null,
    ) {
        historyStore.recordCommand(vehicleId, command, instant(), rssi())
        if (awaitingKey != null) outstanding[awaitingKey] = Outstanding(command.reason, command.domain ?: Domain.DOMAIN_BROADCAST, now())
    }

    /**
     * The car answered the command with this [key]; whatever it said, it is no longer outstanding. A
     * message-level [fault] in the reply (which can come without any payload) is logged as its result.
     */
    fun replied(
        key: String,
        fault: MessageStatus?,
        at: Instant,
    ) {
        val pending = outstanding.remove(key)
        if (pending != null && fault != null) result(CommandRecords.refused(pending.reason, pending.domain, fault), at)
    }

    /** The car refused a command or never answered it. [at] is when the reply arrived. */
    fun result(
        result: CommandResult,
        at: Instant = instant(),
    ) {
        historyStore.recordCommandResult(vehicleId, result, at, rssi())
    }

    /**
     * Logs the commands that waited longer than [TIMEOUT_MS] for a reply, each once, or all of them
     * when the link is over ([linkEnded]), and forgets them.
     */
    fun logUnanswered(linkEnded: Boolean = false) {
        val cutoff = now() - TIMEOUT_MS
        val iterator = outstanding.values.iterator()
        while (iterator.hasNext()) {
            val pending = iterator.next()
            if (!linkEnded && pending.sentAtMs > cutoff) continue
            iterator.remove()
            result(CommandRecords.timedOut(pending.reason, pending.domain))
        }
    }

    private fun instant() = Instant.ofEpochMilli(now())

    companion object {
        /** How long a command waits for its reply before the log says it went unanswered. */
        const val TIMEOUT_MS = 15_000L
    }
}
