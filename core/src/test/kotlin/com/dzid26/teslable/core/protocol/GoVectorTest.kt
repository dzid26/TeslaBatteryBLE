package com.dzid26.teslable.core.protocol

import com.tesla.generated.universalmessage.RoutableMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class GoVectorTest {

    @Test
    fun `verifies the session info tag produced by the go runtime`() {
        assertNotNull(createSession())
    }

    @Test
    fun `rejects the go tag with the wrong vin`() {
        val session = TeslaSession.import(
            privateKeyPkcs8 = CLIENT_PRIVATE_PKCS8.hex(),
            publicKeyRaw = CLIENT_PUBLIC.hex(),
            vin = "WRONGVIN000000000",
            challenge = CHALLENGE.hex(),
            encodedInfo = SESSION_INFO.hex(),
            tag = SESSION_INFO_TAG.hex(),
            clock = { 0 },
        )
        org.junit.Assert.assertEquals(null, session)
    }

    @Test
    fun `decrypts a response produced by the go runtime`() {
        val session = createSession()!!
        val message = RoutableMessage.ADAPTER.decode(RESPONSE_MESSAGE.hex())
        val plaintext = session.decrypt(message, RESPONSE_REQUEST_ID.hex())
        assertArrayEquals(RESPONSE_PLAINTEXT.hex(), plaintext)
    }

    private fun createSession(): TeslaSession? = TeslaSession.import(
        privateKeyPkcs8 = CLIENT_PRIVATE_PKCS8.hex(),
        publicKeyRaw = CLIENT_PUBLIC.hex(),
        vin = VIN,
        challenge = CHALLENGE.hex(),
        encodedInfo = SESSION_INFO.hex(),
        tag = SESSION_INFO_TAG.hex(),
        clock = { 0 },
    )

    private fun String.hex(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val VIN = "5YJ3E1EA7KF000001"
        const val CLIENT_PRIVATE_PKCS8 =
            "308187020100301306072a8648ce3d020106082a8648ce3d030107046d306b02010104200102030405060708090a0b0c0d0e0f" +
                "101112131415161718191a1b1c1d1e1f20a14403420004515c3d6eb9e396b904d3feca7f54fdcd0cc1e997bf375dca515ad0a6" +
                "c3b4035f4536be3a50f318fbf9a5475902a221502bef0d57e08c53b2cc0a56f17d9f9354"
        const val CLIENT_PUBLIC =
            "04515c3d6eb9e396b904d3feca7f54fdcd0cc1e997bf375dca515ad0a6c3b4035f4536be3a50f318fbf9a5475902a221502bef" +
                "0d57e08c53b2cc0a56f17d9f9354"
        const val SESSION_INFO =
            "082912410483981c490124c7d05d29c922a815e8fd3fc430904f4dc9e9a7e10fe5daa988f0da828754c10366449cafb32b95d2" +
                "65986d199411b91321c6ff068c812d23c5dc1a105a5a5a5a5a5a5a5a5a5a5a5a5a5a5a5a25393000003007"
        const val CHALLENGE = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
        const val SESSION_INFO_TAG =
            "7a9624811f1725e5821aad8841f320f8467c26a230b5fe8498478d3c1aead850"
        const val RESPONSE_MESSAGE =
            "3a02080352141c676e3b3c7b0b4f4fc8ff6176ee425fa9c876236a244a220a0c80eb1da6bfff15d92c14dea410091a10351dc3" +
                "a5c9cfdafb5921753718304a40"
        const val RESPONSE_REQUEST_ID = "1111111111111111111111111111111111"
        const val RESPONSE_PLAINTEXT = "6368617267652d73746174652d66697874757265"
    }
}
