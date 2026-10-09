// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.core.protocol

import com.dzid26.teslable.core.history.Command
import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.RKEAction_E
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.tesla.generated.carserver.server.OperationStatus_E as CarServerStatus
import com.tesla.generated.universalmessage.OperationStatus_E as MessageOperationStatus
import com.tesla.generated.vcsec.OperationStatus_E as VcsecStatus

class CommandRecordsTest {
    @Test
    fun `every poll reason maps onto its own command reason`() {
        assertEquals(Command.Reason.FRESH_START, CommandRecords.reasonOf(InfotainmentPollPolicy.Reason.FRESH_START))
        assertEquals(Command.Reason.POLICY_ACTIVE, CommandRecords.reasonOf(InfotainmentPollPolicy.Reason.ACTIVE))
        assertEquals(Command.Reason.POLICY_HOLD, CommandRecords.reasonOf(InfotainmentPollPolicy.Reason.HOLD))
        assertEquals(Command.Reason.POLICY_SAFETY, CommandRecords.reasonOf(InfotainmentPollPolicy.Reason.SAFETY))
        assertEquals(Command.Reason.REASON_UNSPECIFIED, CommandRecords.reasonOf(null))
        val mapped = InfotainmentPollPolicy.Reason.entries.map { CommandRecords.reasonOf(it) }
        assertEquals(mapped.size, mapped.toSet().size, "no two poll reasons share a command reason")
    }

    @Test
    fun `a wake request is kept as the unsigned message`() {
        val command = CommandRecords.vcsec(Command.Reason.USER_WAKE, TeslaCommands.buildWakeRequest())
        assertEquals(Command.Reason.USER_WAKE, command.reason)
        assertEquals(RKEAction_E.RKE_ACTION_WAKE_VEHICLE, command.vcsec?.RKEAction)
        assertNull(command.infotainment)
        assertNull(command.session_info)
    }

    @Test
    fun `a whitelist request is kept as the unsigned message inside its routable message`() {
        val command = CommandRecords.vcsecRoutable(Command.Reason.PAIRING, TeslaVcsec.buildWhitelistInfoRequest())
        assertEquals(
            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO,
            command.vcsec?.VCSEC_InformationRequest?.informationRequestType,
        )
        val slot = CommandRecords.vcsecRoutable(Command.Reason.KEY_LOOKUP, TeslaVcsec.buildWhitelistEntryRequest(3))
        assertEquals(3, slot.vcsec?.VCSEC_InformationRequest?.slot)
    }

    @Test
    fun `an add-key request is kept without the public key`() {
        val key = ByteArray(65) { it.toByte() }
        val command = CommandRecords.addKey(Command.Reason.PAIRING, TeslaPairing.buildAddKeyRequest(key))
        val change = command.vcsec?.VCSEC_WhitelistOperation?.addKeyToWhitelistAndAddPermissions
        assertNotNull(change)
        assertEquals(0, change.key?.PublicKeyRaw?.size)
        assertFalse(
            command
                .encode()
                .toList()
                .windowed(key.size)
                .any { it == key.toList() },
            "the key is not in the record",
        )
    }

    @Test
    fun `a read is kept as the action that asked for it`() {
        val command = CommandRecords.infotainment(Command.Reason.POLICY_ACTIVE, TeslaCommands.buildClimateStateRequest())
        assertEquals(Command.Reason.POLICY_ACTIVE, command.reason)
        assertNotNull(
            command.infotainment
                ?.vehicleAction
                ?.getVehicleData
                ?.getClimateState,
        )
    }

    @Test
    fun `a session info request keeps the domain and drops the key and the ids`() {
        val key = ByteArray(65) { (it + 1).toByte() }
        val request =
            TeslaSessionRequests.buildSessionInfoRequest(
                domain = Domain.DOMAIN_INFOTAINMENT,
                publicKeyRaw = key,
                routingAddress = ByteArray(16) { 7 },
                uuid = ByteArray(16) { 9 },
            )
        val command = CommandRecords.sessionInfo(Command.Reason.SESSION_HANDSHAKE, request)
        val logged = command.session_info
        assertEquals(Domain.DOMAIN_INFOTAINMENT, logged?.to_destination?.domain)
        assertNotNull(logged?.session_info_request)
        assertEquals(0, logged.session_info_request?.public_key?.size)
        assertNull(logged.from_destination)
        assertEquals(0, logged.uuid.size)
    }

    @Test
    fun `an error status in an Infotainment reply is a refusal and an OK one is not`() {
        val refused =
            Response(actionStatus = ActionStatus(result = CarServerStatus.OPERATIONSTATUS_ERROR)).encode()
        assertEquals(CarServerStatus.OPERATIONSTATUS_ERROR, CommandRecords.actionRefusal(refused)?.result)
        assertNull(CommandRecords.actionRefusal(Response(actionStatus = ActionStatus()).encode()))
        assertNull(CommandRecords.actionRefusal(Response().encode()))
        assertNull(CommandRecords.actionRefusal(byteArrayOf(0x7F)), "garbage is no refusal")
    }

    @Test
    fun `an error status in a VCSEC reply is a refusal, waiting for the card is not`() {
        fun reply(status: VcsecStatus) = FromVCSECMessage(commandStatus = CommandStatus(operationStatus = status)).encode()
        assertEquals(
            VcsecStatus.OPERATIONSTATUS_ERROR,
            CommandRecords.commandRefusal(reply(VcsecStatus.OPERATIONSTATUS_ERROR))?.operationStatus,
        )
        assertNull(CommandRecords.commandRefusal(reply(VcsecStatus.OPERATIONSTATUS_OK)))
        assertNull(CommandRecords.commandRefusal(reply(VcsecStatus.OPERATIONSTATUS_WAIT)))
        assertNull(CommandRecords.commandRefusal(FromVCSECMessage().encode()))
    }

    @Test
    fun `a message status is a refusal on an error or any fault`() {
        assertFalse(MessageStatus().isRefusal)
        assertTrue(MessageStatus(operation_status = MessageOperationStatus.OPERATIONSTATUS_ERROR).isRefusal)
        assertTrue(MessageStatus(signed_message_fault = MessageFault_E.MESSAGEFAULT_ERROR_BUSY).isRefusal)
        assertFalse(MessageStatus(operation_status = MessageOperationStatus.OPERATIONSTATUS_WAIT).isRefusal)
    }

    @Test
    fun `a refusal is found by the domain of the command that was answered`() {
        val refusedRead = Response(actionStatus = ActionStatus(result = CarServerStatus.OPERATIONSTATUS_ERROR)).encode()
        val refusedWake = FromVCSECMessage(commandStatus = CommandStatus(operationStatus = VcsecStatus.OPERATIONSTATUS_ERROR)).encode()

        val read = CommandRecords.refusalIn(Command.Reason.POLICY_HOLD, Domain.DOMAIN_INFOTAINMENT, refusedRead)
        assertEquals(Command.Reason.POLICY_HOLD, read?.reason)
        assertEquals(CarServerStatus.OPERATIONSTATUS_ERROR, read?.action_status?.result)

        val wake = CommandRecords.refusalIn(Command.Reason.USER_WAKE, Domain.DOMAIN_VEHICLE_SECURITY, refusedWake)
        assertEquals(VcsecStatus.OPERATIONSTATUS_ERROR, wake?.command_status?.operationStatus)

        // A reply that went fine is not a refusal, and a status read as the wrong domain's is not one either.
        assertNull(CommandRecords.refusalIn(Command.Reason.POLICY_HOLD, Domain.DOMAIN_INFOTAINMENT, Response().encode()))
        assertNull(CommandRecords.refusalIn(Command.Reason.USER_WAKE, Domain.DOMAIN_BROADCAST, refusedWake))
    }

    @Test
    fun `result records carry the reason, the domain and the raw status`() {
        val action = ActionStatus(result = CarServerStatus.OPERATIONSTATUS_ERROR)
        val result = CommandRecords.refused(Command.Reason.FOLLOW_UP, Domain.DOMAIN_INFOTAINMENT, action)
        assertEquals(Command.Reason.FOLLOW_UP, result.reason)
        assertEquals(Domain.DOMAIN_INFOTAINMENT, result.domain)
        assertEquals(action, result.action_status)
        assertNull(result.timed_out)

        val silent = CommandRecords.timedOut(Command.Reason.USER_WAKE, Domain.DOMAIN_VEHICLE_SECURITY)
        assertEquals(true, silent.timed_out)
        assertNull(silent.action_status)
        assertNull(silent.command_status)
        assertNull(silent.message_status)
    }
}
