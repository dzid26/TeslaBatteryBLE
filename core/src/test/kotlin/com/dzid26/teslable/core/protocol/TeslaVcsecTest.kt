package com.dzid26.teslable.core.protocol

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.KeyIdentifier
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistInfo
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaVcsecTest {

    @Test
    fun `status request targets VCSEC with an information request payload`() {
        val request = RoutableMessage.ADAPTER.decode(TeslaVcsec.buildStatusRequest())
        assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, request.to_destination?.domain)
        assertEquals(16, request.uuid.size)
        assertEquals(16, request.from_destination?.routing_address?.size)
        assertEquals(0, request.flags)

        val payload = UnsignedMessage.ADAPTER.decode(request.protobuf_message_as_bytes!!)
        assertEquals(
            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS,
            payload.VCSEC_InformationRequest?.informationRequestType,
        )
    }

    @Test
    fun `parses a vehicle status response`() {
        val vcsecPayload = FromVCSECMessage(
            vehicleStatus = VehicleStatus(
                vehicleLockState = VehicleLockState_E.VEHICLELOCKSTATE_LOCKED,
                vehicleSleepStatus = VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP,
                userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
            ),
        ).encode()
        val response = RoutableMessage(
            protobuf_message_as_bytes = vcsecPayload.toByteString(),
        ).encode()

        val status = TeslaVcsec.parseStatusResponse(response)
        assertNotNull(status)
        assertTrue(status!!.locked)
        assertTrue(status.asleep)
        assertFalse(status.userPresent)
    }

    @Test
    fun `returns null for responses without a vehicle status`() {
        val response = RoutableMessage().encode()
        assertEquals(null, TeslaVcsec.parseStatusResponse(response))
    }

    @Test
    fun `whitelist request uses the whitelist information type`() {
        val request = RoutableMessage.ADAPTER.decode(TeslaVcsec.buildWhitelistInfoRequest())
        val payload = UnsignedMessage.ADAPTER.decode(request.protobuf_message_as_bytes!!)
        assertEquals(
            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO,
            payload.VCSEC_InformationRequest?.informationRequestType,
        )
    }

    @Test
    fun `parses whitelist info responses`() {
        val keyId = byteArrayOf(1, 2, 3, 4)
        val payload = FromVCSECMessage(
            whitelistInfo = WhitelistInfo(
                numberOfEntries = 1,
                whitelistEntries = listOf(
                    KeyIdentifier(publicKeySHA1 = keyId.toByteString()),
                ),
            ),
        ).encode()
        val response = RoutableMessage(protobuf_message_as_bytes = payload.toByteString()).encode()

        val info = TeslaVcsec.parseWhitelistInfoResponse(response)
        assertNotNull(info)
        assertEquals(1, info!!.numberOfEntries)
        assertArrayEquals(keyId, info.whitelistEntries.first().publicKeySHA1.toByteArray())
    }
}
