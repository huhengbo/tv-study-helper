#!/usr/bin/env bash
# 提交前的全部检查，CI 跑同样的内容。
set -euo pipefail
cd "$(dirname "$0")/.."

scripts/version.sh check
./gradlew --no-daemon :tv:testDebugUnitTest :tv:lintRelease :tv:assembleRelease
