#!/bin/bash
set -e

DOMAIN="$1"

if [ -z "$DOMAIN" ]; then
  echo "Usage: $0 <domain>"
  exit 1
fi

CERTS_DIR="certs"
DOMAINS_DIR="$CERTS_DIR/domains"
DOMAIN_DIR="$DOMAINS_DIR/$DOMAIN"
ROOT_CA="$CERTS_DIR/rootCA.pem"
ROOT_KEY="$CERTS_DIR/rootCA.key"

if [ ! -f "$ROOT_CA" ] || [ ! -f "$ROOT_KEY" ]; then
  echo "Root CA not found. Please run create-root-ca.sh first."
  exit 1
fi

mkdir -p "$DOMAIN_DIR"

CONFIG="$DOMAIN_DIR/$DOMAIN.cnf"
cat > "$CONFIG" <<EOF
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = req_ext

[dn]
CN = $DOMAIN

[req_ext]
subjectAltName = @alt_names

[alt_names]
DNS.1 = $DOMAIN
EOF

openssl genrsa -out "$DOMAIN_DIR/$DOMAIN.key" 2048
openssl req -new -key "$DOMAIN_DIR/$DOMAIN.key" -out "$DOMAIN_DIR/$DOMAIN.csr" -config "$CONFIG"
openssl x509 -req -in "$DOMAIN_DIR/$DOMAIN.csr" \
  -CA "$ROOT_CA" -CAkey "$ROOT_KEY" -CAcreateserial \
  -out "$DOMAIN_DIR/$DOMAIN.crt" -days 365 \
  -extensions req_ext -extfile "$CONFIG"

echo "Certificate created: $DOMAIN_DIR/$DOMAIN.crt"
