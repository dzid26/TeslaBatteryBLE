package com.dzid26.teslable.core.protocol

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import okio.ByteString.Companion.toByteString
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
        assertEquals(2, request.flags)

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
}
