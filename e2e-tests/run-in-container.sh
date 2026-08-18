#!/usr/bin/env bash
set -uo pipefail

status=0
./gradlew :e2e-tests:test --no-daemon -q || status=$?

mkdir -p /artifacts/gradle/reports /artifacts/gradle/test-results
if [[ -d e2e-tests/build/reports/tests/test ]]; then
    cp -R e2e-tests/build/reports/tests/test/. /artifacts/gradle/reports/
fi
if [[ -d e2e-tests/build/test-results/test ]]; then
    cp -R e2e-tests/build/test-results/test/. /artifacts/gradle/test-results/
fi

exit "$status"
