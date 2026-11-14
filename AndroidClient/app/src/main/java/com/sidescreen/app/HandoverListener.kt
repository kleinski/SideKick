package com.sidescreen.app

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Listens on UDP port 54322 for signed "SSHO" takeover packets sent by a paired MacBook
 * when the user clicks "Connect Tablet Here (Takeover)" in the Mac menu bar or settings window.
 *
 * Packet layout:
 *   `[magic "SSHO" 4][timestampMs 8 BE][port 2 BE][hmac 32][hostIdLen 1][hostId][macNameLen 1][macName][hostIpLen 1][hostIp]`
 */
class HandoverListener(
    private val storage: PairedHostStorage,
    private val onTakeoverVerified: (PairedHostStorage.Entry) -> Unit,
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var lastAcceptedTimestampMs: Long = 0L

    fun start() {
        if (running) return
        running = true
        thread =
            Thread({
                listenLoop()
            }, "HandoverUDPListener").apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        thread = null
    }

    private fun listenLoop() {
        try {
            val sock =
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(UDP_PORT))
                }
            socket = sock
            val buf = ByteArray(512)
            Log.i(TAG, "Listening for Mac takeover signals on UDP $UDP_PORT")

            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val senderIp = packet.address?.hostAddress
                val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val verified = verifyAndResolve(data, senderIp, storage.loadAll())
                if (verified != null) {
                    // Deduplicate the 3-packet UDP burst from the same click
                    if (abs(verified.second - lastAcceptedTimestampMs) < 1500L) {
                        continue
                    }
                    lastAcceptedTimestampMs = verified.second
                    val updatedEntry = verified.first
                    storage.save(updatedEntry)
                    Log.i(TAG, "Authenticated takeover from ${updatedEntry.macName} (${updatedEntry.host}:${updatedEntry.port})")
                    onTakeoverVerified(updatedEntry)
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "Handover UDP listener stopped: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "HandoverListener"
        const val UDP_PORT = 54322
        private val MAGIC = byteArrayOf(0x53, 0x53, 0x48, 0x4F) // "SSHO"
        private const val MIN_PACKET_LEN = 4 + 8 + 2 + 32 // 46 bytes
        private const val MAX_CLOCK_SKEW_MS = 120_000L // ±2 minutes replay window

        /**
         * Parses and authenticates a raw UDP packet against the list of paired Macs.
         * Returns the matched (and IP/port-updated) [PairedHostStorage.Entry] and packet timestamp if valid.
         */
        fun verifyAndResolve(
            data: ByteArray,
            senderIp: String?,
            pairedMacs: List<PairedHostStorage.Entry>,
            nowMs: Long = System.currentTimeMillis(),
        ): Pair<PairedHostStorage.Entry, Long>? {
            if (data.size < MIN_PACKET_LEN) return null
            for (i in 0..3) {
                if (data[i] != MAGIC[i]) return null
            }

            val bb = ByteBuffer.wrap(data, 4, 10).order(ByteOrder.BIG_ENDIAN)
            val timestampMs = bb.long
            val port = bb.short.toInt() and 0xFFFF
            if (port !in 1..65535) return null
            if (abs(nowMs - timestampMs) > MAX_CLOCK_SKEW_MS) return null

            val signedSlice = data.copyOfRange(4, 14)
            val packetHmac = data.copyOfRange(14, 46)

            var offset = 46
            fun readLenPrefixedString(): String? {
                if (offset >= data.size) return null
                val len = data[offset].toInt() and 0xFF
                offset += 1
                if (offset + len > data.size) return null
                val s = String(data, offset, len, Charsets.UTF_8)
                offset += len
                return s
            }

            val packetHostId = readLenPrefixedString() ?: ""
            val packetMacName = readLenPrefixedString() ?: ""
            val packetHostIp = readLenPrefixedString() ?: ""

            val matched =
                pairedMacs.firstOrNull { entry ->
                    val expectedHmac = hmacSha256(entry.token, signedSlice)
                    MessageDigest.isEqual(expectedHmac, packetHmac)
                } ?: return null

            val resolvedHost =
                packetHostIp.ifBlank { senderIp ?: matched.host }
            val resolvedName =
                packetMacName.ifBlank { matched.macName }
            val resolvedId =
                packetHostId.ifBlank { matched.hostId }

            return matched.copy(
                host = resolvedHost,
                port = port,
                macName = resolvedName,
                hostId = resolvedId,
                lastConnected = nowMs,
            ) to timestampMs
        }

        fun hmacSha256(
            key: ByteArray,
            message: ByteArray,
        ): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message)
        }
    }
}
