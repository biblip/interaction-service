#!/bin/bash

set -e

# Check if Docker is already installed
if ! command -v docker &> /dev/null; then
    echo "[INFO] Installing Docker..."

    sudo dnf update -y
    sudo dnf install -y docker

    sudo systemctl enable docker
    sudo systemctl start docker

    # Add ec2-user to docker group if not already
    if ! id -nG ec2-user | grep -qw docker; then
        sudo usermod -aG docker ec2-user
        echo "[INFO] Added ec2-user to docker group"
    fi

    echo "[INFO] Docker installed and configured successfully."
else
    echo "[INFO] Docker is already installed. Skipping setup."
fi