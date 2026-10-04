// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.keys.Role
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.KeyFormFactor
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.SignatureType
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaPairingTest {

    @Test
    fun `generates a p256 key pair with an uncompressed public key`() {
        val keyPair = TeslaKeys.generate()
        assertEquals(65, keyPair.publicKeyRaw.size)
        assertEquals(0x04, keyPair.publicKeyRaw[0].toInt())
        assertTrue(keyPair.privateKeyPkcs8.isNotEmpty())
        assertEquals(4, keyPair.keyId.size)
    }

    @Test
    fun `generates distinct key pairs`() {
        val first = TeslaKeys.generate()
        val second = TeslaKeys.generate()
        assertNotEquals(
            first.publicKeyRaw.toList(),
            second.publicKeyRaw.toList(),
        )
    }

    @Test
    fun `add key request is a present-key envelope with the public key and role`() {
        val keyPair = TeslaKeys.generate()
        val envelope = ToVCSECMessage.ADAPTER.decode(
            TeslaPairing.buildAddKeyRequest(keyPair.publicKeyRaw)
        )
        val signedMessage = envelope.signedMessage
        assertNotNull(signedMessage)
        assertEquals(SignatureType.SIGNATURE_TYPE_PRESENT_KEY, signedMessage!!.signatureType)

        val payload = UnsignedMessage.ADAPTER.decode(signedMessage.protobufMessageAsBytes!!)
        val operation = payload.VCSEC_WhitelistOperation
        assertNotNull(operation)
        val change = operation!!.addKeyToWhitelistAndAddPermissions
        assertNotNull(change)
        assertArrayEquals(keyPair.publicKeyRaw, change!!.key?.PublicKeyRaw?.toByteArray())
        assertEquals(Role.ROLE_CHARGING_MANAGER, change.keyRole)
        assertEquals(
            KeyFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE,
            operation.metadataForKey?.keyFormFactor,
        )
    }

    @Test
    fun `parses raw add key responses`() {
        assertEquals(
            TeslaPairing.Result.WAITING_FOR_CARD,
            TeslaPairing.parseAddKeyResponse(rawResponse(OperationStatus_E.OPERATIONSTATUS_WAIT)),
        )
        assertEquals(
            TeslaPairing.Result.OK,
            TeslaPairing.parseAddKeyResponse(rawResponse(OperationStatus_E.OPERATIONSTATUS_OK)),
        )
        assertEquals(
            TeslaPairing.Result.ERROR,
            TeslaPairing.parseAddKeyResponse(rawResponse(OperationStatus_E.OPERATIONSTATUS_ERROR)),
        )
        assertEquals(null, TeslaPairing.parseAddKeyResponse(byteArrayOf()))
    }

    @Test
    fun `parses wrapped add key responses`() {
        val payload = FromVCSECMessage(
            commandStatus = CommandStatus(operationStatus = OperationStatus_E.OPERATIONSTATUS_WAIT),
        ).encode()
        val wrapped = RoutableMessage(protobuf_message_as_bytes = payload.toByteString()).encode()
        assertEquals(TeslaPairing.Result.WAITING_FOR_CARD, TeslaPairing.parseAddKeyResponse(wrapped))
    }

    private fun rawResponse(status: OperationStatus_E): ByteArray =
        FromVCSECMessage(
            commandStatus = CommandStatus(operationStatus = status),
        ).encode()
}
