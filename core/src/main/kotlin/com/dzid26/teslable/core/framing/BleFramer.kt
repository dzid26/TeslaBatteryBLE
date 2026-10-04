package com.dzid26.teslable.core.framing

class BleFramer(
    private val maxMessageSize: Int = MAX_MESSAGE_SIZE,
    private val rxTimeoutMs: Long = RX_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var buffer = ByteArray(0)
    private var lastRx = 0L
    private var hasReceived = false

    fun feed(chunk: ByteArray): List<ByteArray> {
        val now = clock()
        if (hasReceived && now - lastRx > rxTimeoutMs) {
            buffer = ByteArray(0)
        }
        hasReceived = true
        lastRx = now
        buffer += chunk

        val messages = mutableListOf<ByteArray>()
        while (true) {
            if (buffer.size < HEADER_SIZE) break
            val length = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
            if (length > maxMessageSize) {
                buffer = ByteArray(0)
                break
            }
            if (buffer.size < HEADER_SIZE + length) break
            messages += buffer.copyOfRange(HEADER_SIZE, HEADER_SIZE + length)
            buffer = buffer.copyOfRange(HEADER_SIZE + length, buffer.size)
        }
        return messages
    }

    companion object {
        const val HEADER_SIZE = 2
        const val MAX_MESSAGE_SIZE = 1024
        const val RX_TIMEOUT_MS = 1000L

        fun encode(payload: ByteArray, chunkSize: Int): List<ByteArray> {
            require(chunkSize >= 1) { "chunkSize must be positive" }
            val framed = ByteArray(HEADER_SIZE + payload.size)
            framed[0] = ((payload.size shr 8) and 0xFF).toByte()
            framed[1] = (payload.size and 0xFF).toByte()
            payload.copyInto(framed, destinationOffset = HEADER_SIZE)
            return framed.toList().chunked(chunkSize).map { it.toByteArray() }
        }
    }
}
