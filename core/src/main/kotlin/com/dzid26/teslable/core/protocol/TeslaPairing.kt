// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.KeyFormFactor
import com.tesla.generated.vcsec.KeyMetadata
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.PermissionChange
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.SignatureType
import com.tesla.generated.vcsec.SignedMessage
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.WhitelistOperation
import okio.ByteString
import okio.ByteString.Companion.toByteString

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
        val payload =
            UnsignedMessage(
                VCSEC_WhitelistOperation =
                    WhitelistOperation(
                        addKeyToWhitelistAndAddPermissions =
                            PermissionChange(
                                key = PublicKey(PublicKeyRaw = publicKeyRaw.toByteString()),
                                keyRole = role,
                            ),
                        metadataForKey = KeyMetadata(keyFormFactor = formFactor),
                    ),
            ).encode()
        return ToVCSECMessage(
            signedMessage =
                SignedMessage(
                    protobufMessageAsBytes = payload.toByteString(),
                    signatureType = SignatureType.SIGNATURE_TYPE_PRESENT_KEY,
                ),
        ).encode()
    }

    fun parseAddKeyResponse(bytes: ByteArray): Result? {
        val wrapped = runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull()
        val payload = wrapped?.protobuf_message_as_bytes
        if (payload != null) {
            statusFrom(payload)?.let { return it }
        }
        return statusFrom(bytes.toByteString())
    }

    private fun statusFrom(payload: ByteString): Result? {
        val commandStatus =
            runCatching {
                FromVCSECMessage.ADAPTER.decode(payload).commandStatus
            }.getOrNull() ?: return null
        return when (commandStatus.operationStatus) {
            OperationStatus_E.OPERATIONSTATUS_OK -> Result.OK
            OperationStatus_E.OPERATIONSTATUS_WAIT -> Result.WAITING_FOR_CARD
            OperationStatus_E.OPERATIONSTATUS_ERROR -> Result.ERROR
            else -> null
        }
    }
}
