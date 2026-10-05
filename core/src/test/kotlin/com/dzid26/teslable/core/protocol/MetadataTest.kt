// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MetadataTest {
    @Test
    fun `computes the reference sha256 checksum`() {
        val expected =
            byteArrayOf(
                0xab.toByte(),
                0xab.toByte(),
                0x04,
                0xd8.toByte(),
                0x04,
                0x49,
                0x98.toByte(),
                0x13,
                0x38,
                0x2e,
                0xfd.toByte(),
                0x74,
                0xa0.toByte(),
                0x67,
                0x91.toByte(),
                0xce.toByte(),
                0x2d,
                0xe7.toByte(),
                0x77,
                0x43,
                0x96.toByte(),
                0x03,
                0x24,
                0x6d,
                0xfb.toByte(),
                0xaa.toByte(),
                0x83.toByte(),
                0x92.toByte(),
                0xca.toByte(),
                0x05,
                0x86.toByte(),
                0x8e.toByte(),
            )

        assertArrayEquals(expected, referenceMetadata("testVIN").checksum(byteArrayOf()))
    }

    @Test
    fun `changes the checksum when a vin byte changes`() {
        val vin = "testVIN"
        val expected = referenceMetadata(vin).checksum(byteArrayOf())
        for (index in vin.indices) {
            val replacement = if (vin[index] == 'x') 'y' else 'x'
            val tampered = vin.substring(0, index) + replacement + vin.substring(index + 1)
            assertFalse(
                "vin byte $index",
                expected.contentEquals(referenceMetadata(tampered).checksum(byteArrayOf())),
            )
        }
    }

    @Test
    fun `changes the checksum when any message byte changes`() {
        val message = ByteArray(32) { it.toByte() }
        val expected = referenceMetadata("testVIN").checksum(message)
        for (index in message.indices) {
            val tampered = message.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertFalse(expected.contentEquals(referenceMetadata("testVIN").checksum(tampered)))
        }
    }

    @Test
    fun `computes a stable hmac checksum for a fixed key`() {
        val metadata = Metadata.hmacSha256(ByteArray(16) { it.toByte() }).add(2, "testVIN".toByteArray())
        assertArrayEquals(HMAC_CHECKSUM.hex(), metadata.checksum(byteArrayOf()))
    }

    @Test
    fun `changes the hmac tag when the key changes`() {
        val first = Metadata.hmacSha256(ByteArray(16) { 1 }).add(2, "testVIN".toByteArray()).checksum(byteArrayOf())
        val second = Metadata.hmacSha256(ByteArray(16) { 2 }).add(2, "testVIN".toByteArray()).checksum(byteArrayOf())
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `rejects out of order tags`() {
        val metadata =
            Metadata
                .sha256()
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

    private fun referenceMetadata(vin: String): Metadata =
        Metadata
            .sha256()
            .add(0, byteArrayOf(0x05))
            .add(1, byteArrayOf(0x02))
            .add(2, vin.toByteArray())
            .add(
                3,
                byteArrayOf(
                    0xaa.toByte(),
                    0xda.toByte(),
                    0x92.toByte(),
                    0x8a.toByte(),
                    0x4f,
                    0x21,
                    0x5f,
                    0x55,
                    0xf9.toByte(),
                    0xe6.toByte(),
                    0xe4.toByte(),
                    0x5e,
                    0x66,
                    0xb6.toByte(),
                    0x52,
                    0x1e,
                ),
            ).add(4, byteArrayOf(0x00, 0x00, 0x0e, 0x74))
            .add(5, byteArrayOf(0x00, 0x00, 0x05, 0x3a))

    private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        // Regression value from this implementation. The HMAC path is used by
        // the Fleet API transport; BLE uses AES-GCM.
        const val HMAC_CHECKSUM =
            "d2c9fd6a2d8428f90dda6e03838dfb6303f591470cb46b628f642ec6a3af427a"
    }
}
