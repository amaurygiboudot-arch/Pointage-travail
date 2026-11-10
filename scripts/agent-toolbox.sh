#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

usage() {
  cat <<'EOF'
HoraTrack agent toolbox

Usage:
  bash scripts/agent-toolbox.sh <commande>

Commandes:
  v2-tests           Tests unitaires Android V2
  android-build      Compilation Android debug
  play-build         APK + AAB Google Play
  functions-tests    Tests Firebase Functions
  update-architecture Vérification architecture de mise à jour
  ios-build          Build simulateur iOS (macOS uniquement)
  ios-tests          SwiftPM + XCTest simulateur iOS (macOS uniquement)
                     IOS_TEST_DESTINATION permet de choisir la destination Xcode
  codex-config       Validation configuration multi-agents Codex
  agent-route        Déterminer les spécialistes requis pour un diff Git
  agent-review       Lancer la revue multi-agents Codex et publier la preuve PR
  technical          codex-config + v2-tests + android-build + functions-tests
EOF
}

run_v2_tests() {
  ./gradlew :app:testDebugUnitTest --stacktrace
}

run_android_build() {
  ./gradlew :app:assembleDebug --stacktrace
}

run_play_build() {
  ./gradlew :app:assemblePlay :app:bundlePlay --stacktrace
}

run_functions_tests() {
  if [ ! -d functions/node_modules ]; then
    npm install --prefix functions
  fi
  npm test --prefix functions
}

run_update_architecture() {
  python3 scripts/verify_update_architecture.py
}

require_macos() {
  if [ "$(uname -s)" != "Darwin" ]; then
    echo "Cette commande iOS nécessite macOS/Xcode. Utiliser le workflow GitHub build-ios hors macOS." >&2
    exit 2
  fi
}

run_ios_build() {
  require_macos
  (
    cd ios/HPTravail
    command -v xcodegen >/dev/null 2>&1 || brew install xcodegen
    xcodegen generate
    xcodebuild -project HPTravail.xcodeproj -scheme HPTravail -resolvePackageDependencies
    xcodebuild \
      -project HPTravail.xcodeproj \
      -scheme HPTravail \
      -sdk iphonesimulator \
      -configuration Debug \
      CODE_SIGNING_ALLOWED=NO \
      build
  )
}

run_ios_tests() {
  require_macos
  (
    cd ios/HPTravail
    swift test
    command -v xcodegen >/dev/null 2>&1 || brew install xcodegen
    xcodegen generate
    local destination="${IOS_TEST_DESTINATION:-}"
    if [ -z "$destination" ]; then
      # Prefer the newest installed available iOS runtime; UUID breaks device ties.
      local simulator_id
      simulator_id="$(xcrun simctl list devices available --json | python3 -c '
import json, re, sys
candidates = []
for runtime, devices in json.load(sys.stdin)["devices"].items():
    match = re.fullmatch(r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-(\d+(?:-\d+)*)", runtime)
    if not match:
        continue
    version = tuple(int(part) for part in match[1].split("-"))
    if version < (16,):
        continue
    for device in devices:
        if device.get("isAvailable") and (
            "iPhone" in device.get("deviceTypeIdentifier", "")
            or device.get("name", "").startswith("iPhone")):
            candidates.append((version, device["udid"]))
if not candidates:
    sys.exit("Aucun simulateur iPhone iOS >= 16 disponible. Installer un runtime dans Xcode.")
print(sorted(candidates, key=lambda item: (item[0], item[1]), reverse=True)[0][1])
')"
      destination="platform=iOS Simulator,id=$simulator_id"
    fi
    echo "Tests XCTest sur : $destination"
    xcodebuild \
      -project HPTravail.xcodeproj \
      -scheme HPTravail \
      -configuration Debug \
      -destination "$destination" \
      CODE_SIGNING_ALLOWED=NO \
      test
  )
}

run_codex_config() {
  python3 scripts/validate_codex_agents.py
  python3 scripts/test_agent_orchestration.py
}

case "${1:-}" in
  v2-tests) run_v2_tests ;;
  android-build) run_android_build ;;
  play-build) run_play_build ;;
  functions-tests) run_functions_tests ;;
  update-architecture) run_update_architecture ;;
  ios-build) run_ios_build ;;
  ios-tests) run_ios_tests ;;
  codex-config) run_codex_config ;;
  agent-route)
    shift
    python3 scripts/agent_router.py "$@"
    ;;
  agent-review)
    shift
    bash scripts/run-agent-review.sh "$@"
    ;;
  technical)
    run_codex_config
    run_v2_tests
    run_android_build
    run_functions_tests
    ;;
  -h|--help|help|"") usage ;;
  *)
    echo "Commande inconnue: $1" >&2
    usage >&2
    exit 2
    ;;
esac
