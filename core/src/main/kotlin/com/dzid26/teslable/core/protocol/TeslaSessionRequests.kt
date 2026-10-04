package com.dzid26.teslable.core.protocol

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.universalmessage.SessionInfoRequest
import okio.ByteString.Companion.toByteString

object TeslaSessionRequests {

    const val ENCRYPT_RESPONSE_FLAG = 2

    fun buildSessionInfoRequest(
        domain: Domain,
        publicKeyRaw: ByteArray,
        routingAddress: ByteArray,
        uuid: ByteArray,
    ): ByteArray = RoutableMessage(
        to_destination = Destination(domain = domain),
        from_destination = Destination(routing_address = routingAddress.toByteString()),
        session_info_request = SessionInfoRequest(public_key = publicKeyRaw.toByteString()),
        uuid = uuid.toByteString(),
        flags = 0,
    ).encode()

    fun buildAuthenticatedRequest(
        domain: Domain,
        payload: ByteArray,
        routingAddress: ByteArray,
        uuid: ByteArray,
    ): RoutableMessage = RoutableMessage(
        to_destination = Destination(domain = domain),
        from_destination = Destination(routing_address = routingAddress.toByteString()),
        protobuf_message_as_bytes = payload.toByteString(),
        uuid = uuid.toByteString(),
        flags = ENCRYPT_RESPONSE_FLAG,
    )
}
