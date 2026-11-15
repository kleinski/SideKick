import CryptoKit
import Foundation
import Security

/// End-to-end mutual authentication and AES-256-GCM transport encryption for wireless
/// SideKick sessions ("SSWE" protocol).
///
/// Security properties:
/// 1. The 32-byte pairing token is NEVER transmitted in plaintext during connect.
/// 2. Mutual HMAC-SHA256 challenge-response with fresh 128-bit client & server nonces
///    prevents replay attacks and rogue-server / evil-twin MITM attacks.
/// 3. Per-connection ephemeral AES-256-GCM keys (separate S2C and C2S keys + 96-bit TLS-style
///    sequence nonces) encrypt and authenticate every video frame, display config, and touch
///    event with hardware-accelerated AES instructions (<0.1 ms overhead per frame).
enum WirelessCrypto {
    static let encryptedRequestMagic: [UInt8] = [0x53, 0x53, 0x57, 0x45] // "SSWE"
    static let nonceLength = 16
    static let proofLength = 16
    static let gcmTagLength = 16
    static let recordHeaderLength = 4
    static let maxRecordPlaintextSize = 32 * 1024 * 1024 + 1024

    struct ParsedEncryptedHandshake {
        let clientNonce: Data
        let clientProof: Data
        let deviceName: String
        let nameBytes: Data
    }

    static func randomNonce() -> Data {
        var bytes = [UInt8](repeating: 0, count: nonceLength)
        let status = SecRandomCopyBytes(kSecRandomDefault, nonceLength, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed: \(status)")
        return Data(bytes)
    }

    static func computeClientProof(token: Data, clientNonce: Data, nameBytes: Data) -> Data {
        let key = SymmetricKey(data: token)
        var msg = Data("SSWE-CLIENT-AUTH".utf8)
        msg.append(clientNonce)
        msg.append(nameBytes)
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: key)
        return Data(mac.prefix(proofLength))
    }

    static func computeServerProof(token: Data, clientNonce: Data, serverNonce: Data) -> Data {
        let key = SymmetricKey(data: token)
        var msg = Data("SSWE-SERVER-AUTH".utf8)
        msg.append(clientNonce)
        msg.append(serverNonce)
        let mac = HMAC<SHA256>.authenticationCode(for: msg, using: key)
        return Data(mac.prefix(proofLength))
    }

    static func parseEncryptedRequest(_ data: Data) throws -> ParsedEncryptedHandshake {
        guard data.count >= HandshakeCodec.fixedPrefixLen else { throw HandshakeError.truncated }
        let bytes = Array(data)
        guard Array(bytes[0..<4]) == encryptedRequestMagic else { throw HandshakeError.invalidMagic }
        let clientNonce = Data(bytes[4..<20])
        let clientProof = Data(bytes[20..<36])
        let nameLen = Int(bytes[36])
        guard (1...64).contains(nameLen) else { throw HandshakeError.invalidName }
        guard data.count >= HandshakeCodec.fixedPrefixLen + nameLen else { throw HandshakeError.truncated }
        let nameBytes = Data(bytes[37..<(37 + nameLen)])
        guard let name = String(data: nameBytes, encoding: .utf8), !name.isEmpty else {
            throw HandshakeError.invalidName
        }
        return ParsedEncryptedHandshake(
            clientNonce: clientNonce,
            clientProof: clientProof,
            deviceName: name,
            nameBytes: nameBytes
        )
    }

    static func verifyClientHandshake(_ parsed: ParsedEncryptedHandshake, expectedToken: Data) -> Bool {
        let expectedProof = computeClientProof(
            token: expectedToken,
            clientNonce: parsed.clientNonce,
            nameBytes: parsed.nameBytes
        )
        return WirelessAuth.validate(parsed.clientProof, expected: expectedProof)
    }

    static func encodeEncryptedSuccessResponse(
        token: Data,
        clientNonce: Data,
        serverNonce: Data,
        encryptedStream: Bool = true
    ) -> Data {
        let serverProof = computeServerProof(
            token: token,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )
        let status: HandshakeStatus = encryptedStream ? .ok : .okUnencrypted
        var out = HandshakeCodec.encodeResponse(status: status)
        out.append(serverNonce)
        out.append(serverProof)
        return out
    }

    private static func deriveKey(token: Data, label: String, clientNonce: Data, serverNonce: Data) -> SymmetricKey {
        let master = SymmetricKey(data: token)
        var info = Data(label.utf8)
        info.append(clientNonce)
        info.append(serverNonce)
        let digest = HMAC<SHA256>.authenticationCode(for: info, using: master)
        return SymmetricKey(data: Data(digest))
    }

    private static func deriveSalt(token: Data, label: String, clientNonce: Data, serverNonce: Data) -> [UInt8] {
        let master = SymmetricKey(data: token)
        var info = Data(label.utf8)
        info.append(clientNonce)
        info.append(serverNonce)
        let digest = HMAC<SHA256>.authenticationCode(for: info, using: master)
        return Array(Data(digest).prefix(4))
    }

    /// Stateful per-connection AES-256-GCM cipher for Server->Client encryption and
    /// Client->Server decryption.
    final class SessionCipher {
        private let s2cKey: SymmetricKey
        private let c2sKey: SymmetricKey
        private let s2cSalt: [UInt8]
        private let c2sSalt: [UInt8]

        private var sendSeq: UInt64 = 0
        private var recvSeq: UInt64 = 0
        private var recvBuffer = Data()

        init(token: Data, clientNonce: Data, serverNonce: Data) {
            self.s2cKey = WirelessCrypto.deriveKey(
                token: token,
                label: "SSWE-S2C-KEY",
                clientNonce: clientNonce,
                serverNonce: serverNonce
            )
            self.c2sKey = WirelessCrypto.deriveKey(
                token: token,
                label: "SSWE-C2S-KEY",
                clientNonce: clientNonce,
                serverNonce: serverNonce
            )
            self.s2cSalt = WirelessCrypto.deriveSalt(
                token: token,
                label: "SSWE-S2C-IV",
                clientNonce: clientNonce,
                serverNonce: serverNonce
            )
            self.c2sSalt = WirelessCrypto.deriveSalt(
                token: token,
                label: "SSWE-C2S-IV",
                clientNonce: clientNonce,
                serverNonce: serverNonce
            )
        }

        private static func makeNonce(salt: [UInt8], seq: UInt64) throws -> AES.GCM.Nonce {
            var bytes = [UInt8](repeating: 0, count: 12)
            bytes[0] = salt[0]
            bytes[1] = salt[1]
            bytes[2] = salt[2]
            bytes[3] = salt[3]
            var be = seq.bigEndian
            withUnsafeBytes(of: &be) { ptr in
                for i in 0..<8 {
                    bytes[4 + i] = ptr[i]
                }
            }
            return try AES.GCM.Nonce(data: bytes)
        }

        /// Encrypts a plaintext payload into `[plainLen: 4B BE][ciphertext][tag: 16B]`.
        /// Caller must serialize calls to `seal` together with enqueueing onto the socket.
        func seal(_ plaintext: Data) throws -> Data {
            let seq = sendSeq
            sendSeq &+= 1
            let nonce = try Self.makeNonce(salt: s2cSalt, seq: seq)

            var lenBE = UInt32(plaintext.count).bigEndian
            let header = withUnsafeBytes(of: &lenBE) { Data($0) }
            let sealed = try AES.GCM.seal(plaintext, using: s2cKey, nonce: nonce, authenticating: header)

            var out = Data(capacity: recordHeaderLength + plaintext.count + gcmTagLength)
            out.append(header)
            out.append(sealed.ciphertext)
            out.append(sealed.tag)
            return out
        }

        /// Feeds incoming encrypted wire bytes and returns decrypted plaintext bytes.
        /// Throws if any record is tampered with, out-of-order, or has an invalid length.
        func openStreamChunk(_ chunk: Data) throws -> Data {
            recvBuffer.append(chunk)
            var plaintextOut = Data()

            while recvBuffer.count >= recordHeaderLength {
                let headerSlice = recvBuffer.prefix(recordHeaderLength)
                let plainLen = headerSlice.withUnsafeBytes { ptr -> Int in
                    Int(UInt32(bigEndian: ptr.loadUnaligned(as: UInt32.self)))
                }
                guard plainLen > 0, plainLen <= WirelessCrypto.maxRecordPlaintextSize else {
                    throw HandshakeError.invalidMagic
                }
                let totalRecordLen = recordHeaderLength + plainLen + gcmTagLength
                guard recvBuffer.count >= totalRecordLen else {
                    break
                }

                let header = Data(headerSlice)
                let cipherStart = recvBuffer.index(recvBuffer.startIndex, offsetBy: recordHeaderLength)
                let cipherEnd = recvBuffer.index(cipherStart, offsetBy: plainLen)
                let tagEnd = recvBuffer.index(cipherEnd, offsetBy: gcmTagLength)

                let ciphertext = Data(recvBuffer[cipherStart..<cipherEnd])
                let tag = Data(recvBuffer[cipherEnd..<tagEnd])
                recvBuffer.removeSubrange(recvBuffer.startIndex..<tagEnd)

                let seq = recvSeq
                recvSeq &+= 1
                let nonce = try Self.makeNonce(salt: c2sSalt, seq: seq)
                let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ciphertext, tag: tag)
                let opened = try AES.GCM.open(box, using: c2sKey, authenticating: header)
                plaintextOut.append(opened)
            }

            return plaintextOut
        }
    }
}
