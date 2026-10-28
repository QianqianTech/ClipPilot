#!/usr/bin/env bash
# One-shot local verification: Android unit tests, lint, debug/release builds and gateway tests.
# Output is mirrored to build-logs/verify-latest.log so results can be reviewed afterwards.
#
# Usage (from anywhere):  bash scripts/verify.sh            # online, resolves new dependencies
#                         bash scripts/verify.sh --offline  # use the local Gradle cache only
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
mkdir -p build-logs
LOG="build-logs/verify-latest.log"
: > "$LOG"
exec > >(tee -a "$LOG") 2>&1

GRADLE_FLAGS=()
if [[ "${1:-}" == "--offline" ]]; then
  GRADLE_FLAGS+=(--offline)
fi

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
fi
if [[ -z "${ANDROID_HOME:-}" && -d /opt/homebrew/share/android-commandlinetools ]]; then
  export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
fi

echo "== verify started $(date '+%Y-%m-%d %H:%M:%S')"
echo "JAVA_HOME=${JAVA_HOME:-<unset>}  ANDROID_HOME=${ANDROID_HOME:-<unset>}"

status=0
step() {
  local name="$1"; shift
  echo
  echo "== STEP $name: $*"
  if "$@"; then
    echo "== RESULT $name: PASS"
  else
    echo "== RESULT $name: FAIL"
    status=1
  fi
}

step android ./gradlew "${GRADLE_FLAGS[@]}" --console=plain \
  testDebugUnitTest lintDebug assembleDebug lintRelease assembleRelease
step checkstyle ./gradlew "${GRADLE_FLAGS[@]}" --console=plain checkstyle
step gateway-test bash -c 'cd gateway && npm test'
if [[ -d gateway/node_modules/eslint ]] || (cd gateway && npm install --no-audit --no-fund); then
  step gateway-lint bash -c 'cd gateway && npm run lint'
else
  echo "== RESULT gateway-lint: SKIPPED (npm install failed)"
fi

APK="app/build/outputs/apk/debug/app-debug.apk"
if [[ -f "$APK" ]]; then
  echo
  echo "== APK $(wc -c < "$APK") bytes  sha256 $(shasum -a 256 "$APK" | cut -d' ' -f1)"
fi

echo
echo "== verify finished $(date '+%Y-%m-%d %H:%M:%S') exit=$status"
exit $status
