// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.carserver.server.OperationStatus_E as CarServerStatus
import com.tesla.generated.universalmessage.OperationStatus_E as MessageOperationStatus
import com.tesla.generated.vcsec.OperationStatus_E as VcsecStatus

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

/**
 * Whether the car rejected the message because our session with that domain is stale: the car
 * rotated its epoch, lost our counter or its clock moved on (after sleep or an infotainment
 * reboot). The car asks the client to take new session info and try again; every later request
 * on the old session fails the same way.
 */
val MessageStatus.isStaleSession: Boolean
    get() = signed_message_fault in STALE_SESSION_FAULTS

/**
 * Whether the car does not know the key that signed the message: the owner removed this phone's
 * key (ADR-0005's theft recovery), so no session can recover and the car must be paired again.
 */
val MessageStatus.isUnknownKey: Boolean
    get() = signed_message_fault == MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID

private val STALE_SESSION_FAULTS =
    setOf(
        MessageFault_E.MESSAGEFAULT_ERROR_INVALID_SIGNATURE,
        MessageFault_E.MESSAGEFAULT_ERROR_INVALID_TOKEN_OR_COUNTER,
        MessageFault_E.MESSAGEFAULT_ERROR_INCORRECT_EPOCH,
        MessageFault_E.MESSAGEFAULT_ERROR_TIME_EXPIRED,
    )
