package com.dzid26.teslable.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MetadataTest {

    @Test
    fun `computes the reference sha256 checksum`() {
        val metadata = Metadata.sha256()
            .add(0, byteArrayOf(0x05))
            .add(1, byteArrayOf(0x02))
            .add(2, "testVIN".toByteArray())
            .add(
                3,
                byteArrayOf(
                    0xaa.toByte(), 0xda.toByte(), 0x92.toByte(), 0x8a.toByte(),
                    0x4f, 0x21, 0x5f, 0x55, 0xf9.toByte(), 0xe6.toByte(),
                    0xe4.toByte(), 0x5e, 0x66, 0xb6.toByte(), 0x52, 0x1e,
                ),
            )
            .add(4, byteArrayOf(0x00, 0x00, 0x0e, 0x74))
            .add(5, byteArrayOf(0x00, 0x00, 0x05, 0x3a))

        val expected = byteArrayOf(
            0xab.toByte(), 0xab.toByte(), 0x04, 0xd8.toByte(), 0x04, 0x49, 0x98.toByte(),
            0x13, 0x38, 0x2e, 0xfd.toByte(), 0x74,
            0xa0.toByte(), 0x67, 0x91.toByte(), 0xce.toByte(), 0x2d, 0xe7.toByte(), 0x77,
            0x43, 0x96.toByte(), 0x03, 0x24, 0x6d,
            0xfb.toByte(), 0xaa.toByte(), 0x83.toByte(), 0x92.toByte(), 0xca.toByte(),
            0x05, 0x86.toByte(), 0x8e.toByte(),
        )

        assertArrayEquals(expected, metadata.checksum(byteArrayOf()))
    }

    @Test
    fun `rejects out of order tags`() {
        val metadata = Metadata.sha256()
            .add(1, byteArrayOf(1))
            .add(2, byteArrayOf(2))
        assertThrows(IllegalArgumentException::class.java) {
            metadata.add(1, byteArrayOf(3))
        }
    }

    @Test
    fun `rejects fields longer than 255 bytes`() {
        val metadata = Metadata.sha256()
        assertThrows(IllegalArgumentException::class.java) {
            metadata.add(1, ByteArray(256))
        }
    }

    @Test
    fun `encodes uint32 as big endian`() {
        val metadata = Metadata.sha256().addUint32(5, 0x0E74)
        val expected = Metadata.sha256().add(5, byteArrayOf(0x00, 0x00, 0x0e, 0x74))
        assertArrayEquals(expected.checksum(byteArrayOf()), metadata.checksum(byteArrayOf()))
    }

    @Test
    fun `tags are single bytes`() {
        val metadata = Metadata.sha256().add(255, null)
        assertEquals(32, metadata.checksum(byteArrayOf()).size)
    }
}
