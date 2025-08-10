#!/bin/bash
set -e

mkdir -p certs

echo "Generating Root CA..."
openssl req -x509 -new -nodes -days 3650 \
  -subj "/CN=MyRootCA" \
  -keyout certs/rootCA.key \
  -out certs/rootCA.pem \
  -sha256

echo "Root CA generated: certs/rootCA.pem"
