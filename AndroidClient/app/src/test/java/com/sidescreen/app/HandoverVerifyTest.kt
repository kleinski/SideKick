package com.sidescreen.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HandoverVerifyTest {
    private fun buildTestPacket(
        token: ByteArray,
        timestampMs: Long,
        port: Int,
        hostId: String,
        macName: String,
        hostIp: String,
    ): ByteArray {
        val header = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN)
        header.put(byteArrayOf(0x53, 0x53, 0x48, 0x4F)) // "SSHO"
        header.putLong(timestampMs)
        header.putShort(port.toShort())
        val headerBytes = header.array()
        val signedSlice = headerBytes.copyOfRange(4, 14)
        val hmac = HandoverListener.hmacSha256(token, signedSlice)

        val idBytes = hostId.toByteArray(Charsets.UTF_8)
        val nameBytes = macName.toByteArray(Charsets.UTF_8)
        val ipBytes = hostIp.toByteArray(Charsets.UTF_8)

        return headerBytes +
            hmac +
            byteArrayOf(idBytes.size.toByte()) + idBytes +
            byteArrayOf(nameBytes.size.toByte()) + nameBytes +
            byteArrayOf(ipBytes.size.toByte()) + ipBytes
    }

    @Test
    fun `matches second paired MacBook and updates its IP and port`() {
        val tokenA = ByteArray(32) { 0x11 }
        val tokenB = ByteArray(32) { 0x22 }
        val macA = PairedHostStorage.Entry("192.168.1.10", 54321, tokenA, "MacBook Air", "id-a", 1000L)
        val macB = PairedHostStorage.Entry("192.168.1.20", 54321, tokenB, "MacBook Pro", "id-b", 900L)

        val now = 1_700_000_000_000L
        val packet = buildTestPacket(tokenB, now, 54321, "id-b", "MacBook Pro", "192.168.1.99")

        val verified = HandoverListener.verifyAndResolve(packet, "192.168.1.99", listOf(macA, macB), nowMs = now)
        assertNotNull(verified)
        assertEquals("MacBook Pro", verified!!.first.macName)
        assertEquals("192.168.1.99", verified.first.host)
        assertEquals("id-b", verified.first.hostId)
        assertArrayEquals(tokenB, verified.first.token)
    }

    @Test
    fun `rejects unknown token or expired timestamp`() {
        val tokenA = ByteArray(32) { 0x11 }
        val unknownToken = ByteArray(32) { 0x33 }
        val macA = PairedHostStorage.Entry("192.168.1.10", 54321, tokenA, "MacBook Air", "id-a", 1000L)

        val now = 1_700_000_000_000L
        val badTokenPacket = buildTestPacket(unknownToken, now, 54321, "id-x", "Rogue Mac", "192.168.1.66")
        assertNull(HandoverListener.verifyAndResolve(badTokenPacket, "192.168.1.66", listOf(macA), nowMs = now))

        val stalePacket = buildTestPacket(tokenA, now - 600_000L, 54321, "id-a", "MacBook Air", "192.168.1.10")
        assertNull(HandoverListener.verifyAndResolve(stalePacket, "192.168.1.10", listOf(macA), nowMs = now))
    }
}
