import Foundation
import CryptoKit
import Darwin

enum HandoverBroadcaster {
    static let magic: [UInt8] = [0x53, 0x53, 0x48, 0x4F] // "SSHO"
    static let udpPort: UInt16 = 54322

    /// Builds a signed handover payload:
    /// `[magic "SSHO" 4][timestampMs 8 BE][port 2 BE][hmac 32][hostIdLen 1][hostId][macNameLen 1][macName][hostIpLen 1][hostIp]`
    static func encodePacket(
        token: Data,
        port: UInt16,
        hostId: String,
        macName: String,
        hostIp: String,
        timestampMs: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000)
    ) -> Data {
        precondition(token.count == 32, "token must be 32 bytes")
        var header = Data(magic)
        var tsBE = timestampMs.bigEndian
        withUnsafeBytes(of: &tsBE) { header.append(contentsOf: $0) }
        var portBE = port.bigEndian
        withUnsafeBytes(of: &portBE) { header.append(contentsOf: $0) }

        // HMAC-SHA256 over [timestampMs 8 BE][port 2 BE] using the 32-byte token as key
        let signedSlice = header.dropFirst(4)
        let key = SymmetricKey(data: token)
        let mac = HMAC<SHA256>.authenticationCode(for: signedSlice, using: key)

        var packet = header
        packet.append(contentsOf: mac)

        let idBytes = Array(hostId.utf8.prefix(64))
        packet.append(UInt8(idBytes.count))
        packet.append(contentsOf: idBytes)

        let nameBytes = Array(macName.utf8.prefix(64))
        packet.append(UInt8(nameBytes.count))
        packet.append(contentsOf: nameBytes)

        let ipBytes = Array(hostIp.utf8.prefix(64))
        packet.append(UInt8(ipBytes.count))
        packet.append(contentsOf: ipBytes)

        return packet
    }

    /// Sends the handover UDP datagram to the LAN broadcast address(es) and any known paired tablet IPs.
    /// Sends a short burst (3 packets over 300ms) so a single dropped Wi-Fi UDP frame doesn't miss.
    static func broadcastTakeover(
        token: Data,
        port: UInt16,
        hostId: String,
        macName: String,
        knownClientAddresses: [String] = []
    ) {
        guard let hostIp = LANAddressResolver.primaryIPv4() else {
            debugLog("HandoverBroadcaster: no primary IPv4 address available")
            return
        }
        let targets = Array(Set(LANAddressResolver.broadcastAddresses() + knownClientAddresses.filter { !$0.isEmpty }))
        let packet = encodePacket(token: token, port: port, hostId: hostId, macName: macName, hostIp: hostIp)

        DispatchQueue.global(qos: .userInitiated).async {
            for attempt in 0..<3 {
                if attempt > 0 {
                    Thread.sleep(forTimeInterval: 0.12)
                }
                sendUDP(packet: packet, to: targets, port: udpPort)
            }
            debugLog("HandoverBroadcaster: sent takeover signal from \(macName) (\(hostIp):\(port)) to \(targets)")
        }
    }

    private static func sendUDP(packet: Data, to addresses: [String], port: UInt16) {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return }
        defer { close(fd) }

        var broadcastEnable: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &broadcastEnable, socklen_t(MemoryLayout<Int32>.size))

        packet.withUnsafeBytes { rawBuf in
            guard let base = rawBuf.baseAddress else { return }
            for ip in addresses {
                var addr = sockaddr_in()
                addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                addr.sin_family = sa_family_t(AF_INET)
                addr.sin_port = port.bigEndian
                guard inet_pton(AF_INET, ip, &addr.sin_addr) == 1 else { continue }

                withUnsafePointer(to: &addr) { ptr in
                    ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { saPtr in
                        _ = sendto(fd, base, packet.count, 0, saPtr, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
        }
    }
}
