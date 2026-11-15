package com.sidescreen.app

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end mutual authentication and AES-256-GCM transport encryption for wireless
 * SideKick sessions ("SSWE" protocol).
 */
object WirelessCrypto {
    private val ENCRYPTED_REQ_MAGIC = byteArrayOf(0x53, 0x53, 0x57, 0x45) // "SSWE"
    const val NONCE_LEN = 16
    const val PROOF_LEN = 16
    const val GCM_TAG_LEN = 16
    const val GCM_TAG_BITS = 128
    const val RECORD_HEADER_LEN = 4
    private const val MAX_RECORD_PLAINTEXT_SIZE = 32 * 1024 * 1024 + 1024

    private val secureRandom = SecureRandom()

    data class ClientHandshakeRequest(
        val wireBytes: ByteArray,
        val clientNonce: ByteArray,
    )

    private fun hmacSha256(
        key: ByteArray,
        vararg chunks: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (chunk in chunks) {
            mac.update(chunk)
        }
        return mac.doFinal()
    }

    fun encodeEncryptedRequest(
        token: ByteArray,
        deviceName: String,
    ): ClientHandshakeRequest {
        require(token.size == 32) { "token must be 32 bytes, got ${token.size}" }
        val nameBytes = deviceName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size in 1..64) { "deviceName UTF-8 length must be 1..64, got ${nameBytes.size}" }

        val clientNonce = ByteArray(NONCE_LEN)
        secureRandom.nextBytes(clientNonce)

        val fullMac =
            hmacSha256(
                token,
                "SSWE-CLIENT-AUTH".toByteArray(Charsets.UTF_8),
                clientNonce,
                nameBytes,
            )
        val clientProof = fullMac.copyOfRange(0, PROOF_LEN)

        val wireBytes =
            ENCRYPTED_REQ_MAGIC +
                clientNonce +
                clientProof +
                byteArrayOf(nameBytes.size.toByte()) +
                nameBytes
        return ClientHandshakeRequest(wireBytes = wireBytes, clientNonce = clientNonce)
    }

    fun verifyServerResponse(
        token: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
        serverProof: ByteArray,
    ): Boolean {
        if (clientNonce.size != NONCE_LEN || serverNonce.size != NONCE_LEN || serverProof.size != PROOF_LEN) {
            return false
        }
        val expectedFull =
            hmacSha256(
                token,
                "SSWE-SERVER-AUTH".toByteArray(Charsets.UTF_8),
                clientNonce,
                serverNonce,
            )
        val expectedProof = expectedFull.copyOfRange(0, PROOF_LEN)
        return MessageDigest.isEqual(serverProof, expectedProof)
    }

    private fun deriveKey(
        token: ByteArray,
        label: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): SecretKeySpec {
        val raw =
            hmacSha256(
                token,
                label.toByteArray(Charsets.UTF_8),
                clientNonce,
                serverNonce,
            )
        return SecretKeySpec(raw, "AES")
    }

    private fun deriveSalt(
        token: ByteArray,
        label: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray {
        val raw =
            hmacSha256(
                token,
                label.toByteArray(Charsets.UTF_8),
                clientNonce,
                serverNonce,
            )
        return raw.copyOfRange(0, 4)
    }

    private fun buildNonce(
        salt: ByteArray,
        seq: Long,
        outNonce: ByteArray,
    ) {
        outNonce[0] = salt[0]
        outNonce[1] = salt[1]
        outNonce[2] = salt[2]
        outNonce[3] = salt[3]
        ByteBuffer.wrap(outNonce, 4, 8).order(ByteOrder.BIG_ENDIAN).putLong(seq)
    }

    /**
     * Decrypts AES-256-GCM records sent by the Mac host (`[plainLen: 4B BE][ciphertext][tag: 16B]`).
     */
    class EncryptedInputStream(
        private val rawIn: InputStream,
        token: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ) : InputStream() {
        private val s2cKey = deriveKey(token, "SSWE-S2C-KEY", clientNonce, serverNonce)
        private val s2cSalt = deriveSalt(token, "SSWE-S2C-IV", clientNonce, serverNonce)
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val headerBuf = ByteArray(RECORD_HEADER_LEN)
        private val nonceBuf = ByteArray(12)

        private var recvSeq = 0L
        private var cipherBuf = ByteArray(65536)
        private var plainBuf = ByteArray(65536)
        private var plainOffset = 0
        private var plainLimit = 0

        override fun read(): Int {
            if (plainOffset >= plainLimit) {
                if (!readNextRecord()) return -1
            }
            return plainBuf[plainOffset++].toInt() and 0xFF
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (len == 0) return 0
            if (plainOffset >= plainLimit) {
                if (!readNextRecord()) return -1
            }
            val toCopy = minOf(len, plainLimit - plainOffset)
            System.arraycopy(plainBuf, plainOffset, b, off, toCopy)
            plainOffset += toCopy
            return toCopy
        }

        private fun readExact(
            target: ByteArray,
            length: Int,
        ): Boolean {
            var total = 0
            while (total < length) {
                val r = rawIn.read(target, total, length - total)
                if (r < 0) {
                    if (total == 0) return false
                    throw EOFException("Truncated encrypted record")
                }
                total += r
            }
            return true
        }

        private fun readNextRecord(): Boolean {
            if (!readExact(headerBuf, RECORD_HEADER_LEN)) return false
            val plainLen = ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN).int
            if (plainLen <= 0 || plainLen > MAX_RECORD_PLAINTEXT_SIZE) {
                throw IOException("Invalid encrypted record length: $plainLen")
            }
            val wirePayloadLen = plainLen + GCM_TAG_LEN
            if (cipherBuf.size < wirePayloadLen) {
                cipherBuf = ByteArray(wirePayloadLen)
            }
            if (plainBuf.size < plainLen) {
                plainBuf = ByteArray(plainLen)
            }
            if (!readExact(cipherBuf, wirePayloadLen)) {
                throw EOFException("Truncated encrypted payload")
            }

            val seq = recvSeq++
            buildNonce(s2cSalt, seq, nonceBuf)
            try {
                cipher.init(Cipher.DECRYPT_MODE, s2cKey, GCMParameterSpec(GCM_TAG_BITS, nonceBuf))
                cipher.updateAAD(headerBuf)
                val decoded = cipher.doFinal(cipherBuf, 0, wirePayloadLen, plainBuf, 0)
                if (decoded != plainLen) {
                    throw IOException("Unexpected decrypted length: $decoded != $plainLen")
                }
            } catch (e: GeneralSecurityException) {
                throw IOException("AES-GCM authentication failed on incoming frame", e)
            }

            plainOffset = 0
            plainLimit = plainLen
            return true
        }

        override fun close() {
            rawIn.close()
        }
    }

    /**
     * Encrypts client->host messages into AES-256-GCM records whenever [flush] is called.
     */
    class EncryptedOutputStream(
        private val rawOut: OutputStream,
        token: ByteArray,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ) : OutputStream() {
        private val c2sKey = deriveKey(token, "SSWE-C2S-KEY", clientNonce, serverNonce)
        private val c2sSalt = deriveSalt(token, "SSWE-C2S-IV", clientNonce, serverNonce)
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val pending = ByteArrayOutputStream(256)
        private val headerBuf = ByteArray(RECORD_HEADER_LEN)
        private val nonceBuf = ByteArray(12)
        private var sendSeq = 0L

        override fun write(b: Int) {
            pending.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            pending.write(b, off, len)
        }

        override fun flush() {
            val plainBytes = pending.toByteArray()
            pending.reset()
            if (plainBytes.isEmpty()) {
                rawOut.flush()
                return
            }

            ByteBuffer.wrap(headerBuf).order(ByteOrder.BIG_ENDIAN).putInt(plainBytes.size)
            val seq = sendSeq++
            buildNonce(c2sSalt, seq, nonceBuf)

            val sealed: ByteArray =
                try {
                    cipher.init(Cipher.ENCRYPT_MODE, c2sKey, GCMParameterSpec(GCM_TAG_BITS, nonceBuf))
                    cipher.updateAAD(headerBuf)
                    cipher.doFinal(plainBytes)
                } catch (e: GeneralSecurityException) {
                    throw IOException("AES-GCM encryption failed on outgoing message", e)
                }

            rawOut.write(headerBuf)
            rawOut.write(sealed)
            rawOut.flush()
        }

        override fun close() {
            rawOut.close()
        }
    }
}
