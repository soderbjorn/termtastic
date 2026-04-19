#!/usr/bin/env bash
# Starts the Termtastic server on the default production port (8082).
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :server:run "$@"
