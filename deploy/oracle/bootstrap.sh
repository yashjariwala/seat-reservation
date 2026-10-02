#!/usr/bin/env bash
# cloud-init bootstrap for an Ubuntu 24.04 Oracle Always Free VM.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y docker.io docker-compose-v2 git curl
systemctl enable --now docker
usermod -aG docker ubuntu
install -d -o ubuntu -g ubuntu -m 750 /opt/seat-reservation
