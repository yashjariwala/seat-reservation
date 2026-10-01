#!/usr/bin/env sh
# Usage: ./burst.sh <BASE_URL> [requests=20000] [concurrency=1000] [seats=5000]
# Needs JDK 17+. Set ADMIN_KEY for a deployed instance.
exec java "$(dirname "$0")/Burst.java" "$@"
