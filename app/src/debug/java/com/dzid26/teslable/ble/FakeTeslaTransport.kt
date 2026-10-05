// SPDX-License-Identifier: AGPL-3.0-only

package com.dzid26.teslable.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlin.random.Random

/**
 * A simulated car behind [TeslaTransport]. This class owns the Android side
 * (scheduling, RSSI jitter) and delegates the protocol to [FakeCarProtocol],
 * which is pure JVM and unit tested against the real client code.
 */
class FakeTeslaTransport(
    context: Context,
    private val listener: TeslaTransport.Listener,
) : TeslaTransport {
    private val handler = Handler(Looper.getMainLooper())
    private val vin: String =
        context
            .getSharedPreferences("teslable", Context.MODE_PRIVATE)
            .getString("vin", DemoMode.DEMO_VIN)
            ?.takeIf { it.length == 17 }
            ?: DemoMode.DEMO_VIN
    private val protocol =
        FakeCarProtocol(vin).apply {
            setEnrolledKey(PairingKeyStore(context).load()?.publicKeyRaw)
        }

    private var connected = false
    private var rssi = -70

    private val rssiTick =
        object : Runnable {
            override fun run() {
                if (!connected) return
                rssi = (rssi + Random.nextInt(-2, 3)).coerceIn(-95, -45)
                listener.onRssi(rssi)
                handler.postDelayed(this, RSSI_MS)
            }
        }

    private val dischargeTick =
        object : Runnable {
            override fun run() {
                if (!connected) return
                protocol.dischargeOnePercent()
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
        for (response in protocol.handle(payload.copyOf())) {
            handler.postDelayed(
                { if (connected) listener.onMessage(response.bytes) },
                LATENCY_MS + response.delayMs,
            )
        }
        return true
    }

    private companion object {
        const val RSSI_MS = 500L
        const val DISCHARGE_MS = 60_000L
        const val LATENCY_MS = 80L
    }
}
