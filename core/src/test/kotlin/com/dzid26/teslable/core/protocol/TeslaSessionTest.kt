package com.dzid26.teslable.core.protocol

import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.signatures.Tag
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class TeslaSessionTest {

    private val vin = "5YJ3E1EA7KF000001"
    private val client = TeslaKeys.generate()
    private val vehicle = TeslaKeys.generate()
    private val challenge = ByteArray(16) { 1 }
    private val sessionKey = TeslaCrypto.sessionKey(client.privateKeyPkcs8, vehicle.publicKeyRaw)

    private fun sessionInfo(counter: Int = 7, clockTime: Int = 100): ByteArray =
        SessionInfo(
            counter = counter,
            publicKey = vehicle.publicKeyRaw.toByteString(),
            epoch = ByteArray(16) { it.toByte() }.toByteString(),
            clock_time = clockTime,
        ).encode()

    private fun createSession(clock: () -> Long = { 0 }): TeslaSession? {
        val info = sessionInfo()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        return TeslaSession.import(
            privateKeyPkcs8 = client.privateKeyPkcs8,
            publicKeyRaw = client.publicKeyRaw,
            vin = vin,
            challenge = challenge,
            encodedInfo = info,
            tag = tag,
            clock = clock,
        )
    }

    @Test
    fun `imports a session with a valid tag`() {
        assertNotNull(createSession())
    }

    @Test
    fun `rejects a session with a tampered tag`() {
        val info = sessionInfo()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        tag[0] = (tag[0] + 1).toByte()
        assertEquals(
            null,
            TeslaSession.import(client.privateKeyPkcs8, client.publicKeyRaw, vin, challenge, info, tag),
        )
    }

    @Test
    fun `rejects a session with the wrong vin`() {
        val info = sessionInfo()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        assertEquals(
            null,
            TeslaSession.import(
                client.privateKeyPkcs8,
                client.publicKeyRaw,
                "WRONGVIN000000000",
                challenge,
                info,
                tag,
            ),
        )
    }

    @Test
    fun `encrypts with an incrementing counter and decrypts the response`() {
        val session = createSession()!!
        val message = RoutableMessage(
            to_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
            from_destination = Destination(routing_address = ByteArray(16).toByteString()),
            protobuf_message_as_bytes = "hello".toByteArray().toByteString(),
        )

        val encrypted = session.encrypt(message, 5)
        assertNotNull(encrypted)
        val gcm = encrypted!!.signature_data?.AES_GCM_Personalized_data
        assertNotNull(gcm)
        assertEquals(8, gcm!!.counter)
        assertFalse(
            encrypted.protobuf_message_as_bytes!!.toByteArray()
                .contentEquals("hello".toByteArray())
        )

        val requestId = session.requestId(encrypted)!!
        val response = vehicleResponse(requestId, "world".toByteArray(), counter = 3)
        val plaintext = session.decrypt(response, requestId)
        assertNotNull(plaintext)
        assertArrayEquals("world".toByteArray(), plaintext)

        assertEquals(null, session.decrypt(response, requestId))
    }

    @Test
    fun `encrypt uses the injected nonce`() {
        val fixedNonce = ByteArray(12) { 7 }
        val info = sessionInfo()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        val session = TeslaSession.import(
            privateKeyPkcs8 = client.privateKeyPkcs8,
            publicKeyRaw = client.publicKeyRaw,
            vin = vin,
            challenge = challenge,
            encodedInfo = info,
            tag = tag,
            clock = { 0 },
            nonceGenerator = { fixedNonce },
        )!!
        val message = RoutableMessage(
            to_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
            from_destination = Destination(routing_address = ByteArray(16).toByteString()),
            protobuf_message_as_bytes = "hello".toByteArray().toByteString(),
        )
        val encrypted = session.encrypt(message, 5)!!
        assertArrayEquals(
            fixedNonce,
            encrypted.signature_data?.AES_GCM_Personalized_data?.nonce?.toByteArray(),
        )
    }

    private fun vehicleResponse(
        requestId: ByteArray,
        plaintext: ByteArray,
        counter: Int,
    ): RoutableMessage {
        val metadata = Metadata.sha256()
            .add(
                Tag.TAG_SIGNATURE_TYPE.value,
                byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE.value.toByte()),
            )
            .add(Tag.TAG_DOMAIN.value, byteArrayOf(Domain.DOMAIN_INFOTAINMENT.value.toByte()))
            .add(Tag.TAG_PERSONALIZATION.value, vin.toByteArray())
            .addUint32(Tag.TAG_COUNTER.value, counter)
            .addUint32(Tag.TAG_FLAGS.value, 0)
            .add(Tag.TAG_REQUEST_HASH.value, requestId)
            .addUint32(Tag.TAG_FAULT.value, 0)
        val nonce = ByteArray(12) { 2 }
        val (ciphertext, tag) = TeslaCrypto.encryptGcm(
            sessionKey,
            nonce,
            plaintext,
            metadata.checksum(byteArrayOf()),
        )
        return RoutableMessage(
            from_destination = Destination(domain = Domain.DOMAIN_INFOTAINMENT),
            protobuf_message_as_bytes = ciphertext.toByteString(),
            signature_data = SignatureData(
                AES_GCM_Response_data = AES_GCM_Response_Signature_Data(
                    nonce = nonce.toByteString(),
                    counter = counter,
                    tag = tag.toByteString(),
                ),
            ),
        )
    }
}
