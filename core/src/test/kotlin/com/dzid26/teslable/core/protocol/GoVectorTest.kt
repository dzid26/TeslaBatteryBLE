// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Vectors from `tools/go-fixtures/expected.txt`, which `main.go` there prints
 * from Tesla's Go implementation at `TESLA_COMMIT`. CI reruns the generator
 * and fails if its output differs from the file, so these are Go's values.
 */
class GoVectorTest {
    @Test
    fun `verifies the session info tag produced by the go runtime`() {
        assertNotNull(createSession())
    }

    @Test
    fun `rejects the go tag with the wrong vin`() {
        assertNull(createSession(vin = "WRONGVIN000000000"))
    }

    @Test
    fun `encrypts a request the way the go signer does`() {
        val session = createSession()!!
        val request =
            TeslaSessionRequests.buildAuthenticatedRequest(
                domain = Domain.DOMAIN_INFOTAINMENT,
                payload = hex("REQUEST_PAYLOAD"),
                routingAddress = hex("REQUEST_ROUTING_ADDRESS"),
                uuid = hex("REQUEST_UUID"),
            )

        val encrypted = session.encrypt(request, vector("REQUEST_EXPIRES_IN").toInt())!!

        // protobuf-go writes the fields outside a oneof before those inside
        // one, while Wire keeps declaration order, so the two encodings differ
        // in field order alone. Compare the decoded fields instead: all of
        // them, including the ciphertext and the signature data with its tag.
        assertEquals(RoutableMessage.ADAPTER.decode(hex("REQUEST_MESSAGE")), encrypted)
        assertArrayEquals(hex("REQUEST_ID"), session.requestId(encrypted))
    }

    @Test
    fun `decrypts a response produced by the go runtime`() {
        val session = createSession()!!
        val message = RoutableMessage.ADAPTER.decode(hex("RESPONSE_MESSAGE"))
        val plaintext = session.decrypt(message, hex("RESPONSE_REQUEST_ID"), AntiReplayWindow())
        assertArrayEquals(hex("RESPONSE_PLAINTEXT"), plaintext)
    }

    private fun createSession(vin: String = VIN): TeslaSession? =
        TeslaSession.import(
            privateKeyPkcs8 = hex("CLIENT_PRIVATE_PKCS8"),
            publicKeyRaw = hex("CLIENT_PUBLIC"),
            vin = vin,
            challenge = hex("CHALLENGE"),
            encodedInfo = hex("SESSION_INFO"),
            tag = hex("SESSION_INFO_TAG"),
            clock = { CLOCK_MS },
            nonceGenerator = { hex("REQUEST_NONCE") },
        )

    private companion object {
        const val VIN = "5YJ3E1EA7KF000001"

        // Any fixed wall clock: expiry times count from the session info's clock time.
        const val CLOCK_MS = 1_760_000_000_000L

        val vectors: Map<String, String> =
            requireNotNull(GoVectorTest::class.java.getResourceAsStream("/go-fixtures/expected.txt")) {
                "go-fixtures/expected.txt is missing from the test resources"
            }.bufferedReader().useLines { lines ->
                lines.filter { it.isNotBlank() }.associate { it.substringBefore('=') to it.substringAfter('=') }
            }

        fun vector(name: String): String = vectors.getValue(name)

        fun hex(name: String): ByteArray = vector(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
