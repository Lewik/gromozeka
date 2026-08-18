#!/usr/bin/env bash
set -uo pipefail

root_directory="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$root_directory/e2e-tests/compose.yaml"

rm -rf "$root_directory/e2e-tests/build/e2e-artifacts"
mkdir -p "$root_directory/e2e-tests/build/e2e-artifacts"
docker compose --file "$compose_file" down --remove-orphans >/dev/null 2>&1 || true

status=0
docker compose \
    --file "$compose_file" \
    up \
    --build \
    --abort-on-container-exit \
    --exit-code-from tests || status=$?

docker compose --file "$compose_file" down --remove-orphans >/dev/null 2>&1 || true
exit "$status"
