// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.dzid26.teslable.core.history.Command
import com.dzid26.teslable.core.history.CommandResult
import com.squareup.wire.Message
import com.squareup.wire.ProtoAdapter
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.carserver.server.OperationStatus_E as CarServerStatus
import com.tesla.generated.universalmessage.OperationStatus_E as MessageOperationStatus
import com.tesla.generated.vcsec.OperationStatus_E as VcsecStatus

/**
 * What the command log (ADR-0008) keeps of a request and of the car's refusal. The request builders
 * stay as they are and return bytes; these turn the bytes the app just built back into the Tesla
 * message, with the reason attached. Pure, so it ports to other platforms.
 *
 * Public keys are left out of what is logged (they are public, but the log has no use for them), and
 * so are the random routing addresses and request ids.
 */
object CommandRecords {
    /** The command log's reason for a read the poll policy made due. */
    fun reasonOf(reason: InfotainmentPollPolicy.Reason?): Command.Reason =
        when (reason) {
            InfotainmentPollPolicy.Reason.FRESH_START -> Command.Reason.FRESH_START
            InfotainmentPollPolicy.Reason.ACTIVE -> Command.Reason.POLICY_ACTIVE
            InfotainmentPollPolicy.Reason.HOLD -> Command.Reason.POLICY_HOLD
            InfotainmentPollPolicy.Reason.SAFETY -> Command.Reason.POLICY_SAFETY
            null -> Command.Reason.REASON_UNSPECIFIED
        }

    /** A VCSEC request given as the plain `UnsignedMessage` (the wake request). */
    fun vcsec(
        reason: Command.Reason,
        unsigned: ByteArray,
    ): Command = Command(reason = reason, vcsec = decode(unsigned, UnsignedMessage.ADAPTER))

    /** A VCSEC information request, as `TeslaVcsec` builds it: a `RoutableMessage` around an `UnsignedMessage`. */
    fun vcsecRoutable(
        reason: Command.Reason,
        routable: ByteArray,
    ): Command {
        val payload = decode(routable, RoutableMessage.ADAPTER)?.protobuf_message_as_bytes?.toByteArray()
        return vcsec(reason, payload ?: ByteArray(0))
    }

    /** The add-key request, as `TeslaPairing` builds it, with the key left out. */
    fun addKey(
        reason: Command.Reason,
        toVcsec: ByteArray,
    ): Command {
        val payload =
            decode(toVcsec, ToVCSECMessage.ADAPTER)
                ?.signedMessage
                ?.protobufMessageAsBytes
                ?.toByteArray()
        val unsigned = payload?.let { decode(it, UnsignedMessage.ADAPTER) }
        return Command(reason = reason, vcsec = unsigned?.withoutKeys())
    }

    /** An authenticated request to [domain], as `TeslaCommands` builds its plaintext [payload]. */
    fun authenticated(
        reason: Command.Reason,
        domain: Domain,
        payload: ByteArray,
    ): Command = if (domain == Domain.DOMAIN_VEHICLE_SECURITY) vcsec(reason, payload) else infotainment(reason, payload)

    /** An Infotainment `Action`, as `TeslaCommands` builds it. */
    fun infotainment(
        reason: Command.Reason,
        action: ByteArray,
    ): Command = Command(reason = reason, infotainment = decode(action, Action.ADAPTER))

    /** A session info request, as `TeslaSessionRequests` builds it: only the domain it is addressed to is kept. */
    fun sessionInfo(
        reason: Command.Reason,
        request: ByteArray,
    ): Command =
        Command(
            reason = reason,
            session_info =
                RoutableMessage(
                    to_destination = decode(request, RoutableMessage.ADAPTER)?.to_destination,
                    session_info_request = SessionInfoRequest(),
                ),
        )

    /** The car's refusal in a decrypted Infotainment reply, or null when the reply holds no status or it says OK. */
    fun actionRefusal(plaintext: ByteArray): ActionStatus? = decode(plaintext, Response.ADAPTER)?.actionStatus?.takeIf { it.isRefusal }

    /** The car's refusal in a decrypted VCSEC reply, or null when the reply holds no status or it says OK. */
    fun commandRefusal(plaintext: ByteArray): CommandStatus? =
        decode(plaintext, FromVCSECMessage.ADAPTER)?.commandStatus?.takeIf { it.isRefusal }

    /**
     * The result record for a decrypted reply to a command of [domain] sent for [command], or null when
     * the reply carries no refusal. Only Infotainment and VCSEC replies carry a status; a reply that
     * went fine is not logged here, it is already visible as a data record.
     */
    fun refusalIn(
        command: Command.Reason,
        domain: Domain,
        plaintext: ByteArray,
    ): CommandResult? =
        when (domain) {
            Domain.DOMAIN_INFOTAINMENT -> actionRefusal(plaintext)?.let { refused(command, domain, it) }
            Domain.DOMAIN_VEHICLE_SECURITY -> commandRefusal(plaintext)?.let { refused(command, domain, it) }
            else -> null
        }

    /** The result record for a refusal found in an Infotainment reply. */
    fun refused(
        command: Command.Reason,
        domain: Domain,
        action: ActionStatus,
    ) = CommandResult(reason = command, domain = domain, action_status = action)

    /** The result record for a refusal found in a VCSEC reply. */
    fun refused(
        command: Command.Reason,
        domain: Domain,
        status: CommandStatus,
    ) = CommandResult(reason = command, domain = domain, command_status = status)

    /** The result record for a message-level rejection. */
    fun refused(
        command: Command.Reason,
        domain: Domain,
        status: MessageStatus,
    ) = CommandResult(reason = command, domain = domain, message_status = status)

    /** The result record for a command the car never answered. */
    fun timedOut(
        command: Command.Reason,
        domain: Domain,
    ) = CommandResult(reason = command, domain = domain, timed_out = true)

    private fun UnsignedMessage.withoutKeys(): UnsignedMessage {
        val operation = VCSEC_WhitelistOperation ?: return this
        val change = operation.addKeyToWhitelistAndAddPermissions ?: return this
        return copy(
            VCSEC_WhitelistOperation =
                operation.copy(addKeyToWhitelistAndAddPermissions = change.copy(key = PublicKey())),
        )
    }

    private fun <M : Message<M, *>> decode(
        bytes: ByteArray,
        adapter: ProtoAdapter<M>,
    ): M? = runCatching { adapter.decode(bytes) }.getOrNull()
}

/** The domain a logged command was addressed to, or null when the record holds no request. */
val Command.domain: Domain?
    get() =
        when {
            vcsec != null -> Domain.DOMAIN_VEHICLE_SECURITY
            infotainment != null -> Domain.DOMAIN_INFOTAINMENT
            else -> session_info?.to_destination?.domain
        }

/** Whether the car refused an Infotainment action. */
val ActionStatus.isRefusal: Boolean
    get() = result != CarServerStatus.OPERATIONSTATUS_OK

/** Whether VCSEC refused a command. Waiting for the NFC card is not a refusal. */
val CommandStatus.isRefusal: Boolean
    get() = operationStatus == VcsecStatus.OPERATIONSTATUS_ERROR

/** Whether the message itself was rejected: an error status or any fault. */
val MessageStatus.isRefusal: Boolean
    get() =
        operation_status == MessageOperationStatus.OPERATIONSTATUS_ERROR ||
            signed_message_fault != MessageFault_E.MESSAGEFAULT_ERROR_NONE
