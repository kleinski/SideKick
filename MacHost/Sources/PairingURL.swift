import Foundation

enum PairingURL {
    static func build(host: String, port: UInt16, token: Data, name: String, hostId: String? = nil) -> String {
        let tokenStr = base64URLEncode(token)
        var nameAllowed = CharacterSet.urlQueryAllowed
        nameAllowed.remove(charactersIn: "&=?#")
        let nameEncoded = name.addingPercentEncoding(withAllowedCharacters: nameAllowed) ?? ""
        var url = "sidekick://\(host):\(port)?t=\(tokenStr)&name=\(nameEncoded)"
        if let hostId = hostId, !hostId.isEmpty {
            let idEncoded = hostId.addingPercentEncoding(withAllowedCharacters: nameAllowed) ?? ""
            url += "&id=\(idEncoded)"
        }
        return url
    }

    static func base64URLEncode(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
