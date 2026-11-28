#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/load-env.sh" "$ROOT_DIR/.env"
cd "$ROOT_DIR/server"
exec bash ./mvnw spring-boot:run "$@"
