// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequest
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom

object TeslaVcsec {

    private const val ADDRESS_LENGTH = 16
    private val random = SecureRandom()

    data class Status(
        val locked: Boolean,
        val asleep: Boolean,
        val userPresent: Boolean,
    )

    fun buildStatusRequest(): ByteArray =
        buildInformationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS)

    fun buildWhitelistInfoRequest(): ByteArray =
        buildInformationRequest(InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO)

    fun buildWhitelistEntryRequest(slot: Int): ByteArray =
        buildInformationRequest(
            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO,
            slot,
        )

    fun parseStatusResponse(bytes: ByteArray): Status? {
        val message = RoutableMessage.ADAPTER.decode(bytes)
        val payload = message.protobuf_message_as_bytes ?: return null
        val status = FromVCSECMessage.ADAPTER.decode(payload).vehicleStatus ?: return null
        return Status(
            locked = status.vehicleLockState != VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED,
            asleep = status.vehicleSleepStatus == VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
            userPresent = status.userPresence == UserPresence_E.VEHICLE_USER_PRESENCE_PRESENT,
        )
    }

    fun parseWhitelistInfoResponse(bytes: ByteArray): WhitelistInfo? {
        val message = RoutableMessage.ADAPTER.decode(bytes)
        val payload = message.protobuf_message_as_bytes ?: return null
        return FromVCSECMessage.ADAPTER.decode(payload).whitelistInfo
    }

    fun parseWhitelistEntryResponse(bytes: ByteArray): WhitelistEntryInfo? {
        val message = RoutableMessage.ADAPTER.decode(bytes)
        val payload = message.protobuf_message_as_bytes ?: return null
        return FromVCSECMessage.ADAPTER.decode(payload).whitelistEntryInfo
    }

    fun parseCommandStatus(payload: ByteArray): OperationStatus_E? =
        FromVCSECMessage.ADAPTER.decode(payload).commandStatus?.operationStatus

    private fun buildInformationRequest(
        type: InformationRequestType,
        slot: Int? = null,
    ): ByteArray {
        val payload = UnsignedMessage(
            VCSEC_InformationRequest = InformationRequest(
                informationRequestType = type,
                slot = slot,
            ),
        ).encode()
        return RoutableMessage(
            to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
            from_destination = Destination(routing_address = randomBytes().toByteString()),
            protobuf_message_as_bytes = payload.toByteString(),
            uuid = randomBytes().toByteString(),
            flags = 0,
        ).encode()
    }

    private fun randomBytes(): ByteArray = ByteArray(ADDRESS_LENGTH).also(random::nextBytes)
}
