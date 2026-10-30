#!/usr/bin/env bash
# ADR-0011 verification — run from repo root after `source ./env.sh`.
set -euo pipefail
cd "$(dirname "$0")/.."
source ./env.sh
./gradlew test jacocoTestCoverageVerification --no-daemon
