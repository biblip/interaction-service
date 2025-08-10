#!/bin/bash
set -e

DOMAIN="$1"
REVOKE_FILE="certs/revoked.txt"

if [ -z "$DOMAIN" ]; then
  echo "Usage: $0 <domain>"
  exit 1
fi

grep -Fxq "$DOMAIN" "$REVOKE_FILE" 2>/dev/null || echo "$DOMAIN" >> "$REVOKE_FILE"
echo "[✓] Revoked domain: $DOMAIN"

# Notify server to reload
echo "RELOAD" | nc -q1 127.0.0.1 4243
