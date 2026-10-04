// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dzid26.teslable.core.protocol.Metadata
import com.dzid26.teslable.core.protocol.TeslaCrypto
import com.dzid26.teslable.core.protocol.TeslaKeys
import com.dzid26.teslable.core.protocol.TeslaSession
import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.signatures.AES_GCM_Response_Signature_Data
import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SessionInfo
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.signatures.Tag
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.InformationRequestType
import com.tesla.generated.vcsec.KeyIdentifier
import com.tesla.generated.vcsec.OperationStatus_E
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.UserPresence_E
import com.tesla.generated.vcsec.VehicleLockState_E
import com.tesla.generated.vcsec.VehicleSleepStatus_E
import com.tesla.generated.vcsec.VehicleStatus
import com.tesla.generated.vcsec.WhitelistEntryInfo
import com.tesla.generated.vcsec.WhitelistInfo
import java.security.MessageDigest
import kotlin.random.Random
import okio.ByteString.Companion.toByteString

/**
 * A simulated car behind [TeslaTransport]. It speaks enough of the protocol for
 * the real controller: plaintext VCSEC status and whitelist replies, the add-key
 * pairing flow, session handshakes with the core crypto, and encrypted
 * wake/charge responses.
 */
class FakeTeslaTransport(
    context: Context,
    private val listener: TeslaTransport.Listener,
) : TeslaTransport {

    private val handler = Handler(Looper.getMainLooper())
    private val vin: String = context
        .getSharedPreferences("teslable", Context.MODE_PRIVATE)
        .getString("vin", DemoMode.DEMO_VIN)
        ?.takeIf { it.length == 17 }
        ?: DemoMode.DEMO_VIN

    private val sessions = mutableMapOf<Domain, ByteArray>()
    private val epochs = mutableMapOf<Domain, ByteArray>()

    private var connected = false
    private var asleep = true
    private var locked = true
    private var batteryLevel = 78
    private var rssi = -70
    private var responseCounter = 100
    private var clientPublicKey: ByteArray? = PairingKeyStore(context).load()?.publicKeyRaw
    private var clientKeySha1: ByteArray? = clientPublicKey?.let(::sha1)

    private val rssiTick = object : Runnable {
        override fun run() {
            if (!connected) return
            rssi = (rssi + Random.nextInt(-2, 3)).coerceIn(-95, -45)
            listener.onRssi(rssi)
            handler.postDelayed(this, RSSI_MS)
        }
    }

    private val dischargeTick = object : Runnable {
        override fun run() {
            if (!connected) return
            if (!asleep && batteryLevel > 5) batteryLevel--
            handler.postDelayed(this, DISCHARGE_MS)
        }
    }

    override fun connect(address: String) {
        close()
        connected = true
        listener.onPhase(ConnectionPhase.CONNECTING)
        handler.postDelayed({ listener.onPhase(ConnectionPhase.CONNECTED) }, 200)
        handler.postDelayed({
            listener.onServices(emptyList())
            listener.onMtu(115)
            listener.onGattDeviceName("Demo Tesla")
        }, 320)
        handler.postDelayed({
            listener.onPhase(ConnectionPhase.READY)
            handler.post(rssiTick)
            handler.post(dischargeTick)
        }, 420)
    }

    override fun close() {
        connected = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun readRssi() {
        if (connected) listener.onRssi(rssi)
    }

    override fun send(payload: ByteArray): Boolean {
        if (!connected) return false
        val bytes = payload.copyOf()
        handler.postDelayed({ handle(bytes) }, LATENCY_MS)
        return true
    }

    private fun handle(bytes: ByteArray) {
        if (!connected) return
        val message = runCatching { RoutableMessage.ADAPTER.decode(bytes) }.getOrNull()
        if (message != null) {
            if (message.session_info_request != null) {
                handleSessionRequest(message)
                return
            }
            if (message.signature_data?.AES_GCM_Personalized_data != null) {
                handleAuthenticated(message)
                return
            }
            val payload = message.protobuf_message_as_bytes?.toByteArray()
            if (payload != null &&
                runCatching { UnsignedMessage.ADAPTER.decode(payload) }.getOrNull() != null
            ) {
                handleVcsec(
                    payload = payload,
                    domain = message.to_destination?.domain ?: Domain.DOMAIN_VEHICLE_SECURITY,
                    uuid = message.uuid?.toByteArray(),
                )
                return
            }
        }
        val toVcsec = runCatching { ToVCSECMessage.ADAPTER.decode(bytes) }.getOrNull()
        if (toVcsec?.signedMessage != null) {
            handlePairing(toVcsec)
        }
    }

    private fun handleVcsec(payload: ByteArray, domain: Domain, uuid: ByteArray?) {
        val request = runCatching { UnsignedMessage.ADAPTER.decode(payload) }.getOrNull() ?: return
        val info = request.VCSEC_InformationRequest ?: return
        when (info.informationRequestType) {
            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_STATUS -> {
                sendPlaintext(
                    domain,
                    FromVCSECMessage(
                        vehicleStatus = VehicleStatus(
                            vehicleLockState = if (locked) {
                                VehicleLockState_E.VEHICLELOCKSTATE_LOCKED
                            } else {
                                VehicleLockState_E.VEHICLELOCKSTATE_UNLOCKED
                            },
                            vehicleSleepStatus = if (asleep) {
                                VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_ASLEEP
                            } else {
                                VehicleSleepStatus_E.VEHICLE_SLEEP_STATUS_AWAKE
                            },
                            userPresence = UserPresence_E.VEHICLE_USER_PRESENCE_NOT_PRESENT,
                        ),
                    ).encode(),
                    uuid,
                )
            }

            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_INFO -> {
                val sha1 = clientKeySha1
                val entries = if (sha1 != null) {
                    listOf(KeyIdentifier(publicKeySHA1 = sha1.toByteString()))
                } else {
                    emptyList()
                }
                sendPlaintext(
                    domain,
                    FromVCSECMessage(
                        whitelistInfo = WhitelistInfo(
                            numberOfEntries = entries.size,
                            whitelistEntries = entries,
                            slotMask = if (entries.isEmpty()) 0 else 1,
                        ),
                    ).encode(),
                    uuid,
                )
            }

            InformationRequestType.INFORMATION_REQUEST_TYPE_GET_WHITELIST_ENTRY_INFO -> {
                val sha1 = clientKeySha1
                val publicKey = clientPublicKey
                if (sha1 != null && publicKey != null) {
                    sendPlaintext(
                        domain,
                        FromVCSECMessage(
                            whitelistEntryInfo = WhitelistEntryInfo(
                                keyId = KeyIdentifier(publicKeySHA1 = sha1.toByteString()),
                                publicKey = PublicKey(PublicKeyRaw = publicKey.toByteString()),
                                slot = 0,
                            ),
                        ).encode(),
                        uuid,
                    )
                }
            }

            else -> Unit
        }
    }

    private fun handlePairing(request: ToVCSECMessage) {
        val payload = request.signedMessage?.protobufMessageAsBytes?.toByteArray() ?: return
        val unsigned = runCatching { UnsignedMessage.ADAPTER.decode(payload) }.getOrNull() ?: return
        val addKey = unsigned.VCSEC_WhitelistOperation?.addKeyToWhitelistAndAddPermissions ?: return
        val publicKey = addKey.key?.PublicKeyRaw?.toByteArray() ?: return
        clientPublicKey = publicKey
        clientKeySha1 = sha1(publicKey)
        listener.onLog("demo car: card tapped, adding key")
        sendCommandStatus(OperationStatus_E.OPERATIONSTATUS_WAIT)
        handler.postDelayed(
            { sendCommandStatus(OperationStatus_E.OPERATIONSTATUS_OK) },
            CARD_TAP_MS,
        )
    }

    private fun handleSessionRequest(request: RoutableMessage) {
        val domain = request.to_destination?.domain ?: return
        val clientPublic = request.session_info_request?.public_key?.toByteArray() ?: return
        val challenge = request.uuid?.toByteArray() ?: return
        clientPublicKey = clientPublic
        clientKeySha1 = sha1(clientPublic)

        val carKey = TeslaKeys.generate()
        val sessionKey = TeslaCrypto.sessionKey(carKey.privateKeyPkcs8, clientPublic)
        sessions[domain] = sessionKey
        val epoch = epochs.getOrPut(domain) { TeslaCrypto.randomBytes(EPOCH_BYTES) }
        val info = SessionInfo(
            counter = SESSION_COUNTER,
            publicKey = carKey.publicKeyRaw.toByteString(),
            epoch = epoch.toByteString(),
            clock_time = (System.currentTimeMillis() / 1000).toInt(),
        ).encode()
        val tag = TeslaSession.sessionInfoHmac(sessionKey, vin, challenge, info)
        listener.onMessage(
            RoutableMessage(
                from_destination = Destination(domain = domain),
                request_uuid = challenge.toByteString(),
                session_info = info.toByteString(),
                signature_data = SignatureData(
                    session_info_tag = HMAC_Signature_Data(tag = tag.toByteString()),
                ),
            ).encode()
        )
    }

    private fun handleAuthenticated(request: RoutableMessage) {
        val domain = request.to_destination?.domain ?: return
        val key = sessions[domain] ?: return
        val gcm = request.signature_data?.AES_GCM_Personalized_data ?: return
        val ciphertext = request.protobuf_message_as_bytes?.toByteArray() ?: return
        val uuid = request.uuid?.toByteArray() ?: return

        val metadata = Metadata.sha256()
            .add(
                Tag.TAG_SIGNATURE_TYPE.value,
                byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED.value.toByte()),
            )
            .add(Tag.TAG_DOMAIN.value, byteArrayOf(domain.value.toByte()))
            .add(Tag.TAG_PERSONALIZATION.value, vin.toByteArray(Charsets.US_ASCII))
            .add(Tag.TAG_EPOCH.value, gcm.epoch.toByteArray())
            .addUint32(Tag.TAG_EXPIRES_AT.value, gcm.expires_at)
            .addUint32(Tag.TAG_COUNTER.value, gcm.counter)
        if (request.flags > 0) {
            metadata.addUint32(Tag.TAG_FLAGS.value, request.flags)
        }
        val plaintext = TeslaCrypto.decryptGcm(
            key,
            gcm.nonce.toByteArray(),
            ciphertext,
            gcm.tag.toByteArray(),
            metadata.checksum(byteArrayOf()),
        ) ?: run {
            listener.onLog("demo car: could not decrypt request")
            return
        }
        val requestTag = gcm.tag.toByteArray()

        val unsigned = runCatching { UnsignedMessage.ADAPTER.decode(plaintext) }.getOrNull()
        if (unsigned?.RKEAction != null) {
            asleep = false
            sendAuthenticated(
                domain,
                uuid,
                requestTag,
                FromVCSECMessage(
                    commandStatus = CommandStatus(
                        operationStatus = OperationStatus_E.OPERATIONSTATUS_OK,
                    ),
                ).encode(),
            )
            return
        }

        val action = runCatching { Action.ADAPTER.decode(plaintext) }.getOrNull()
        if (action?.vehicleAction?.getVehicleData?.getChargeState != null) {
            sendAuthenticated(domain, uuid, requestTag, chargeResponse())
        }
    }

    private fun chargeResponse(): ByteArray = Response(
        vehicleData = VehicleData(
            charge_state = ChargeState(
                battery_level = batteryLevel,
                charge_limit_soc = CHARGE_LIMIT,
                charging_state = ChargeState.ChargingState(Disconnected = Void()),
            ),
        ),
    ).encode()

    private fun sendAuthenticated(
        domain: Domain,
        requestUuid: ByteArray,
        requestTag: ByteArray,
        plaintext: ByteArray,
    ) {
        val key = sessions[domain] ?: return
        val counter = ++responseCounter
        val nonce = TeslaCrypto.randomBytes(TeslaCrypto.NONCE_SIZE)
        val requestId = byteArrayOf(
            SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED.value.toByte(),
        ) + requestTag
        val metadata = Metadata.sha256()
            .add(
                Tag.TAG_SIGNATURE_TYPE.value,
                byteArrayOf(SignatureType.SIGNATURE_TYPE_AES_GCM_RESPONSE.value.toByte()),
            )
            .add(Tag.TAG_DOMAIN.value, byteArrayOf(domain.value.toByte()))
            .add(Tag.TAG_PERSONALIZATION.value, vin.toByteArray(Charsets.US_ASCII))
            .addUint32(Tag.TAG_COUNTER.value, counter)
            .addUint32(Tag.TAG_FLAGS.value, 0)
            .add(Tag.TAG_REQUEST_HASH.value, requestId)
            .addUint32(Tag.TAG_FAULT.value, 0)
        val (ciphertext, tag) = TeslaCrypto.encryptGcm(
            key,
            nonce,
            plaintext,
            metadata.checksum(byteArrayOf()),
        )
        listener.onMessage(
            RoutableMessage(
                from_destination = Destination(domain = domain),
                request_uuid = requestUuid.toByteString(),
                protobuf_message_as_bytes = ciphertext.toByteString(),
                signature_data = SignatureData(
                    AES_GCM_Response_data = AES_GCM_Response_Signature_Data(
                        nonce = nonce.toByteString(),
                        counter = counter,
                        tag = tag.toByteString(),
                    ),
                ),
            ).encode()
        )
    }

    private fun sendCommandStatus(status: OperationStatus_E) {
        sendPlaintext(
            Domain.DOMAIN_VEHICLE_SECURITY,
            FromVCSECMessage(commandStatus = CommandStatus(operationStatus = status)).encode(),
            null,
        )
    }

    private fun sendPlaintext(domain: Domain, payload: ByteArray, uuid: ByteArray?) {
        val message = RoutableMessage(
            from_destination = Destination(domain = domain),
            protobuf_message_as_bytes = payload.toByteString(),
            flags = 0,
        )
        listener.onMessage(
            if (uuid == null) message.encode() else message.copy(
                request_uuid = uuid.toByteString(),
            ).encode()
        )
    }

    private fun sha1(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(bytes)

    private companion object {
        const val RSSI_MS = 500L
        const val DISCHARGE_MS = 60_000L
        const val LATENCY_MS = 80L
        const val CARD_TAP_MS = 4_000L
        const val SESSION_COUNTER = 500
        const val EPOCH_BYTES = 16
        const val CHARGE_LIMIT = 85
    }
}
