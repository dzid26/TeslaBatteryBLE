package com.dzid26.teslable.core.protocol

import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.KeyFormFactor
import com.tesla.generated.vcsec.KeyMetadata
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.PermissionChange
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.WhitelistOperation
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom

object TeslaPairing {

    enum class Result {
        OK,
        WAITING_FOR_CARD,
        ERROR,
    }

    fun buildAddKeyRequest(
        publicKeyRaw: ByteArray,
        role: Role = Role.ROLE_CHARGING_MANAGER,
        formFactor: KeyFormFactor = KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE,
    ): ByteArray {
        val payload = UnsignedMessage(
            VCSEC_WhitelistOperation = WhitelistOperation(
                addKeyToWhitelistAndAddPermissions = PermissionChange(
                    key = PublicKey(PublicKeyRaw = publicKeyRaw.toByteString()),
                    keyRole = role,
                ),
                metadataForKey = KeyMetadata(keyFormFactor = formFactor),
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

    fun parseAddKeyResponse(bytes: ByteArray): Result? {
        val message = RoutableMessage.ADAPTER.decode(bytes)
        val payload = message.protobuf_message_as_bytes ?: return null
        val status = FromVCSECMessage.ADAPTER.decode(payload).commandStatus ?: return null
        return when (status.operationStatus) {
            OperationStatus_E.OPERATIONSTATUS_OK -> Result.OK
            OperationStatus_E.OPERATIONSTATUS_WAIT -> Result.WAITING_FOR_CARD
            OperationStatus_E.OPERATIONSTATUS_ERROR -> Result.ERROR
            else -> null
        }
    }

    private fun randomBytes(): ByteArray = ByteArray(16).also(SecureRandom()::nextBytes)
}
