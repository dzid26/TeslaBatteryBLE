package com.dzid26.teslable.core.framing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
}
