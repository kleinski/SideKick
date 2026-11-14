#!/bin/bash
# Creates (or imports) a persistent self-signed macOS Code Signing certificate
# ("SideKick Local Signing") so macOS TCC permissions (Screen Recording &
# Accessibility) survive across app updates without needing to be re-granted.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CERT_DIR="$ROOT_DIR/.toolchain/signing"
CERT_NAME="SideKick Local Signing"
KEYCHAIN="$HOME/Library/Keychains/login.keychain-db"
P12_PASS="sidekick"

mkdir -p "$CERT_DIR"

if security find-identity -v -p codesigning "$KEYCHAIN" 2>/dev/null | grep -q "$CERT_NAME"; then
    echo "✅ Code signing identity '$CERT_NAME' is already installed and valid in $KEYCHAIN."
    exit 0
fi

if [ ! -f "$CERT_DIR/SideKick-Local-Signing.p12" ]; then
    echo "🔐 Generating self-signed Code Signing certificate '$CERT_NAME'..."
    cat > "$CERT_DIR/openssl.cnf" << 'EOF'
[ req ]
default_bits       = 2048
distinguished_name = req_dn
x509_extensions    = codesign_ext
prompt             = no

[ req_dn ]
CN = SideKick Local Signing
O  = SideKick Local Development

[ codesign_ext ]
basicConstraints = critical, CA:TRUE
keyUsage         = critical, digitalSignature, keyCertSign
extendedKeyUsage = critical, codeSigning
subjectKeyIdentifier = hash
EOF

    openssl req -x509 -newkey rsa:2048 -nodes \
        -keyout "$CERT_DIR/sidekick_signing.key" \
        -out "$CERT_DIR/sidekick_signing.crt" \
        -days 3650 \
        -config "$CERT_DIR/openssl.cnf" \
        -extensions codesign_ext 2>/dev/null

    # Use -legacy if supported by OpenSSL 3.x so macOS `security import` can read it
    if openssl pkcs12 -help 2>&1 | grep -q -- "-legacy"; then
        openssl pkcs12 -export -legacy \
            -inkey "$CERT_DIR/sidekick_signing.key" \
            -in "$CERT_DIR/sidekick_signing.crt" \
            -out "$CERT_DIR/SideKick-Local-Signing.p12" \
            -name "$CERT_NAME" \
            -passout "pass:$P12_PASS"
    else
        openssl pkcs12 -export \
            -inkey "$CERT_DIR/sidekick_signing.key" \
            -in "$CERT_DIR/sidekick_signing.crt" \
            -out "$CERT_DIR/SideKick-Local-Signing.p12" \
            -name "$CERT_NAME" \
            -passout "pass:$P12_PASS"
    fi
fi

echo "📥 Importing '$CERT_NAME' into $KEYCHAIN..."
security import "$CERT_DIR/SideKick-Local-Signing.p12" \
    -k "$KEYCHAIN" \
    -P "$P12_PASS" \
    -T /usr/bin/codesign \
    -T /usr/bin/security 2>/dev/null || true

echo "🔒 Marking '$CERT_NAME' as trusted for Code Signing (macOS may prompt for Touch ID / password)..."
security add-trusted-cert -r trustRoot -p codeSign -k "$KEYCHAIN" "$CERT_DIR/sidekick_signing.crt" || true

echo "✅ Certificate setup finished. Checking available identities:"
security find-identity -v -p codesigning "$KEYCHAIN" || true
