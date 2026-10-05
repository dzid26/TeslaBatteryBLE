// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.framing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BleFramerTest {
    @Test
    fun `encode prepends big endian length and chunks`() {
        val payload = ByteArray(300) { it.toByte() }
        val chunks = BleFramer.encode(payload, 112)
        assertEquals(3, chunks.size)
        assertEquals(112, chunks[0].size)
        assertEquals(112, chunks[1].size)
        assertEquals(78, chunks[2].size)
        assertEquals(0x01, chunks[0][0].toInt() and 0xFF)
        assertEquals(0x2C, chunks[0][1].toInt() and 0xFF)
    }

    @Test
    fun `decodes a message split across chunks`() {
        val payload = ByteArray(300) { it.toByte() }
        val framer = BleFramer()
        val decoded = BleFramer.encode(payload, 112).flatMap { framer.feed(it) }
        assertEquals(1, decoded.size)
        assertArrayEquals(payload, decoded[0])
    }

    @Test
    fun `decodes multiple messages from one stream`() {
        val first = ByteArray(10) { 1 }
        val second = ByteArray(5) { 2 }
        val stream = BleFramer.encode(first, 112) + BleFramer.encode(second, 112)
        val framer = BleFramer()
        val decoded = stream.flatMap { framer.feed(it) }
        assertEquals(2, decoded.size)
        assertArrayEquals(first, decoded[0])
        assertArrayEquals(second, decoded[1])
    }

    @Test
    fun `handles a header split across chunks`() {
        val payload = ByteArray(4) { it.toByte() }
        val framed = BleFramer.encode(payload, 112).first()
        val framer = BleFramer()
        assertTrue(framer.feed(framed.copyOfRange(0, 1)).isEmpty())
        val decoded = framer.feed(framed.copyOfRange(1, framed.size))
        assertEquals(1, decoded.size)
        assertArrayEquals(payload, decoded[0])
    }

    @Test
    fun `drops oversized messages and resynchronizes`() {
        val framer = BleFramer()
        assertTrue(framer.feed(byteArrayOf(0x10, 0x00, 1, 2, 3)).isEmpty())
        val payload = byteArrayOf(9, 8, 7)
        val decoded = BleFramer.encode(payload, 112).flatMap { framer.feed(it) }
        assertEquals(1, decoded.size)
        assertArrayEquals(payload, decoded[0])
    }

    @Test
    fun `discards a partial message after the timeout`() {
        var now = 0L
        val framer = BleFramer(clock = { now })
        assertTrue(framer.feed(byteArrayOf(0x00)).isEmpty())
        now = 2000L
        val payload = byteArrayOf(5, 6, 7)
        val decoded = BleFramer.encode(payload, 112).flatMap { framer.feed(it) }
        assertEquals(1, decoded.size)
        assertArrayEquals(payload, decoded[0])
    }

    @Test
    fun `keeps a partial message within the timeout`() {
        var now = 0L
        val framer = BleFramer(clock = { now })
        val payload = ByteArray(300) { it.toByte() }
        val chunks = BleFramer.encode(payload, 112)
        assertTrue(framer.feed(chunks[0]).isEmpty())
        now = 500L
        assertTrue(framer.feed(chunks[1]).isEmpty())
        now = 900L
        val decoded = framer.feed(chunks[2])
        assertEquals(1, decoded.size)
        assertArrayEquals(payload, decoded[0])
    }

    @Test
    fun `round trips payloads at exact chunk boundaries`() {
        val chunkSize = 253 // MTU 256 minus the 3-byte ATT header.
        for (size in listOf(1, chunkSize, chunkSize + 1, chunkSize + 2, 2 * chunkSize)) {
            val payload = ByteArray(size) { it.toByte() }
            val chunks = BleFramer.encode(payload, chunkSize)
            assertTrue("chunk $size", chunks.all { it.size <= chunkSize })
            val framer = BleFramer()
            val decoded = chunks.flatMap { framer.feed(it) }
            assertEquals("payload $size", 1, decoded.size)
            assertArrayEquals("payload $size", payload, decoded[0])
        }
    }

    @Test
    fun `encodes the length prefix as two byte big endian`() {
        val cases =
            mapOf(
                0xFF to byteArrayOf(0x00, 0xFF.toByte()),
                0x100 to byteArrayOf(0x01, 0x00),
                0x102 to byteArrayOf(0x01, 0x02),
            )
        for ((size, prefix) in cases) {
            val framed = BleFramer.encode(ByteArray(size), size + BleFramer.HEADER_SIZE).single()
            assertArrayEquals("size $size", prefix, framed.copyOfRange(0, BleFramer.HEADER_SIZE))
            assertArrayEquals("size $size", ByteArray(size), BleFramer().feed(framed).single())
        }
    }

    @Test
    fun `decodes multiple frames from one feed`() {
        val first = byteArrayOf(1, 2, 3, 4)
        val second = byteArrayOf(5, 6)
        val stream = BleFramer.encode(first, 112).single() + BleFramer.encode(second, 112).single()
        val decoded = BleFramer().feed(stream)
        assertEquals(2, decoded.size)
        assertArrayEquals(first, decoded[0])
        assertArrayEquals(second, decoded[1])
    }

    @Test
    fun `buffers a frame whose declared length has not arrived`() {
        val payload = ByteArray(5) { it.toByte() }
        val framed = BleFramer.encode(payload, 112).single()
        val framer = BleFramer()
        assertTrue(framer.feed(framed.copyOfRange(0, 4)).isEmpty())
        val decoded = framer.feed(framed.copyOfRange(4, framed.size))
        assertEquals(1, decoded.size)
        assertArrayEquals(payload, decoded[0])

        val short = BleFramer()
        assertTrue(short.feed(byteArrayOf(0x00, 0x0A, 1, 2, 3)).isEmpty())
        assertEquals(1, short.feed(byteArrayOf(4, 5, 6, 7, 8, 9, 10)).size)
    }

    @Test
    fun `buffers a partial frame after a complete one`() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6, 7)
        val secondFrame = BleFramer.encode(second, 112).single()
        val framer = BleFramer()
        val decoded = framer.feed(BleFramer.encode(first, 112).single() + secondFrame.copyOfRange(0, 3))
        assertEquals(1, decoded.size)
        assertArrayEquals(first, decoded[0])
        val rest = framer.feed(secondFrame.copyOfRange(3, secondFrame.size))
        assertEquals(1, rest.size)
        assertArrayEquals(second, rest[0])
    }

    @Test
    fun `decodes a zero length frame`() {
        val empty = BleFramer().feed(byteArrayOf(0x00, 0x00))
        assertEquals(1, empty.size)
        assertEquals(0, empty[0].size)

        val payload = byteArrayOf(7, 8)
        val stream = BleFramer.encode(byteArrayOf(), 112).single() + BleFramer.encode(payload, 112).single()
        val decoded = BleFramer().feed(stream)
        assertEquals(2, decoded.size)
        assertEquals(0, decoded[0].size)
        assertArrayEquals(payload, decoded[1])
    }

    @Test
    fun `accepts the maximum message size and drops one byte over`() {
        val max = ByteArray(BleFramer.MAX_MESSAGE_SIZE) { 1 }
        val framer = BleFramer()
        val decoded = BleFramer.encode(max, 253).flatMap { framer.feed(it) }
        assertEquals(1, decoded.size)
        assertArrayEquals(max, decoded[0])

        val oversize = BleFramer()
        assertTrue(oversize.feed(byteArrayOf(0x04, 0x01)).isEmpty())
        val payload = byteArrayOf(1, 2, 3)
        assertArrayEquals(payload, oversize.feed(BleFramer.encode(payload, 112).single()).single())
    }

    @Test
    fun `drops buffered bytes only after the timeout`() {
        var now = 0L
        val boundary = BleFramer(clock = { now })
        assertTrue(boundary.feed(byteArrayOf(0x00, 0x01)).isEmpty())
        now = BleFramer.RX_TIMEOUT_MS
        assertArrayEquals(byteArrayOf(0xAA.toByte()), boundary.feed(byteArrayOf(0xAA.toByte())).single())

        var later = 0L
        val expired = BleFramer(clock = { later })
        assertTrue(expired.feed(byteArrayOf(0x00, 0x01)).isEmpty())
        later = BleFramer.RX_TIMEOUT_MS + 1
        assertTrue(expired.feed(byteArrayOf(0xAA.toByte())).isEmpty())
    }

    @Test
    fun `empty feeds leave buffered state intact`() {
        val framer = BleFramer()
        assertTrue(framer.feed(byteArrayOf(0x00, 0x02, 9)).isEmpty())
        assertTrue(framer.feed(ByteArray(0)).isEmpty())
        assertArrayEquals(byteArrayOf(9, 8), framer.feed(byteArrayOf(8)).single())
    }

    @Test
    fun `rejects non positive chunk sizes`() {
        for (size in intArrayOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                BleFramer.encode(byteArrayOf(1), size)
            }
        }
    }
}
