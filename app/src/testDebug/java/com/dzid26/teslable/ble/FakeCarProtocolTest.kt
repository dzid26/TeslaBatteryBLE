// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import com.dzid26.teslable.core.protocol.AntiReplayWindow
import com.dzid26.teslable.core.protocol.TeslaCommands
import com.dzid26.teslable.core.protocol.TeslaCrypto
import com.dzid26.teslable.core.protocol.TeslaKeys
import com.dzid26.teslable.core.protocol.TeslaPairing
import com.dzid26.teslable.core.protocol.TeslaSession
import com.dzid26.teslable.core.protocol.TeslaSessionRequests
import com.dzid26.teslable.core.protocol.TeslaVcsec
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.OperationStatus_E
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trips the simulated car against the real client code: every request is
 * built with the core protocol helpers and every reply is parsed with the
 * production parsers.
 */
class FakeCarProtocolTest {
    private val vin = "5YJ3DEMO000000001"
    private val client = TeslaKeys.generate()
    private val protocol = FakeCarProtocol(vin)

    @Test
    fun `status starts locked and asleep`() {
        val response = protocol.handle(TeslaVcsec.buildStatusRequest()).single()
        val status = TeslaVcsec.parseStatusResponse(response.bytes)

        assertNotNull(status)
        assertTrue(status!!.locked)
        assertTrue(status.asleep)
        assertFalse(status.userPresent)
    }

    @Test
    fun `whitelist reports the enrolled key`() {
        protocol.setEnrolledKey(client.publicKeyRaw)

        val info =
            TeslaVcsec.parseWhitelistInfoResponse(
                protocol.handle(TeslaVcsec.buildWhitelistInfoRequest()).single().bytes,
            )
        assertNotNull(info)
        assertEquals(1, info!!.numberOfEntries)
        assertTrue(
            info.whitelistEntries.first().publicKeySHA1.toByteArray()
                .copyOf(client.keyId.size)
                .contentEquals(client.keyId),
        )

        val entry =
            TeslaVcsec.parseWhitelistEntryResponse(
                protocol.handle(TeslaVcsec.buildWhitelistEntryRequest(0)).single().bytes,
            )
        assertNotNull(entry)
        assertEquals(0, entry!!.slot)
        assertArrayEquals(client.publicKeyRaw, entry.publicKey?.PublicKeyRaw?.toByteArray())
    }

    @Test
    fun `empty whitelist when no key is enrolled`() {
        val info =
            TeslaVcsec.parseWhitelistInfoResponse(
                protocol.handle(TeslaVcsec.buildWhitelistInfoRequest()).single().bytes,
            )
        assertEquals(0, info!!.numberOfEntries)
    }

    @Test
    fun `pairing waits for the card then succeeds`() {
        val responses = protocol.handle(TeslaPairing.buildAddKeyRequest(client.publicKeyRaw))

        assertEquals(2, responses.size)
        assertEquals(
            TeslaPairing.Result.WAITING_FOR_CARD,
            TeslaPairing.parseAddKeyResponse(responses[0].bytes),
        )
        assertTrue(responses[1].delayMs > 0)
        assertEquals(TeslaPairing.Result.OK, TeslaPairing.parseAddKeyResponse(responses[1].bytes))
    }

    @Test
    fun `wake decrypts, replies ok, and clears asleep`() {
        val session = session(Domain.DOMAIN_VEHICLE_SECURITY)
        val plaintext =
            authenticated(
                session,
                Domain.DOMAIN_VEHICLE_SECURITY,
                TeslaCommands.buildWakeRequest(),
            )

        assertEquals(
            OperationStatus_E.OPERATIONSTATUS_OK,
            TeslaVcsec.parseCommandStatus(plaintext),
        )
        assertFalse(protocol.asleep)
    }

    @Test
    fun `charge decrypts to a charge state`() {
        val session = session(Domain.DOMAIN_INFOTAINMENT)
        val plaintext =
            authenticated(
                session,
                Domain.DOMAIN_INFOTAINMENT,
                TeslaCommands.buildChargeStateRequest(),
            )

        val charge = TeslaCommands.parseChargeState(plaintext)
        assertNotNull(charge)
        assertEquals(78, charge!!.batteryLevel)
        assertEquals(85, charge.chargeLimit)
        assertEquals("Disconnected", charge.chargingState)
    }

    @Test
    fun `discharge only happens while awake`() {
        protocol.dischargeOnePercent()
        assertEquals(78, protocol.batteryLevel)

        val session = session(Domain.DOMAIN_VEHICLE_SECURITY)
        authenticated(session, Domain.DOMAIN_VEHICLE_SECURITY, TeslaCommands.buildWakeRequest())
        protocol.dischargeOnePercent()
        assertEquals(77, protocol.batteryLevel)
    }

    private fun session(domain: Domain): TeslaSession {
        val uuid = TeslaCrypto.randomBytes(16)
        val request =
            TeslaSessionRequests.buildSessionInfoRequest(
                domain = domain,
                publicKeyRaw = client.publicKeyRaw,
                routingAddress = TeslaCrypto.randomBytes(16),
                uuid = uuid,
            )
        val response =
            RoutableMessage.ADAPTER.decode(
                protocol.handle(request).single().bytes,
            )
        return TeslaSession.import(
            privateKeyPkcs8 = client.privateKeyPkcs8,
            publicKeyRaw = client.publicKeyRaw,
            vin = vin,
            challenge = uuid,
            encodedInfo = response.session_info!!.toByteArray(),
            tag = response.signature_data!!.session_info_tag!!.tag!!.toByteArray(),
        )!!
    }

    private fun authenticated(
        session: TeslaSession,
        domain: Domain,
        payload: ByteArray,
    ): ByteArray {
        val uuid = TeslaCrypto.randomBytes(16)
        val message =
            TeslaSessionRequests.buildAuthenticatedRequest(
                domain = domain,
                payload = payload,
                routingAddress = TeslaCrypto.randomBytes(16),
                uuid = uuid,
            )
        val encrypted = session.encrypt(message, 5)!!
        val requestId = session.requestId(encrypted)!!
        val response =
            RoutableMessage.ADAPTER.decode(
                protocol.handle(encrypted.encode()).single().bytes,
            )
        return session.decrypt(response, requestId, AntiReplayWindow())!!
    }
}
