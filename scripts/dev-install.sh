#!/usr/bin/env bash
# Build, install on a device/emulator, and re-bind the accessibility service.
#
# Reinstalling an app unbinds its accessibility service, and writing the same
# enabled_accessibility_services value back does not re-bind it, so the service is
# removed from the list and added again. Other enabled services are kept.
#
# Usage: scripts/dev-install.sh [serial]
set -euo pipefail
cd "$(dirname "$0")/.."
SERIAL="${1:-${ANDROID_SERIAL:-emulator-5554}}"
SERVICE="com.artemis.ondevice/com.artemis.ondevice.AgentAccessibilityService"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

./gradlew -q assembleDebug
adb -s "$SERIAL" install -r -g app/build/outputs/apk/debug/app-debug.apk

current="$(adb -s "$SERIAL" shell settings get secure enabled_accessibility_services | tr -d '\r')"
others="$(printf '%s' "$current" | tr ':' '\n' | grep -v -e '^null$' -e '^$' -e "^$SERVICE\$" | paste -sd: - || true)"
adb -s "$SERIAL" shell settings put secure enabled_accessibility_services "${others:-null}"
sleep 1
adb -s "$SERIAL" shell settings put secure enabled_accessibility_services "${others:+$others:}$SERVICE"
adb -s "$SERIAL" shell settings put secure accessibility_enabled 1
echo "Installed on $SERIAL; accessibility: $(adb -s "$SERIAL" shell settings get secure enabled_accessibility_services | tr -d '\r')"
