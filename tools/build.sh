#!/usr/bin/env bash
# Unified build entry point for the Android app.
#
#   tools/build.sh assemble [flavor]   build a debug APK          (default: standalone)
#   tools/build.sh install  [flavor]   build, then push+install   (default: standalone)
#   tools/build.sh check               compile gate: javac + native + style
#   tools/build.sh lint                Android Lint report
#   tools/build.sh clean               remove build outputs
#
# Environment:
#   DEV      adb serial of the device (default: 192.168.101.154:5555)
#   ADB      adb binary            (default: resolved from PATH)
#   GRADLE   gradle launcher       (default: tools/gradle-8.14.3, then android/gradlew)
set -euo pipefail
export MSYS_NO_PATHCONV=1

ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK_DIR="$ROOT/android/app/build/outputs/apk"
DEV=${DEV:-192.168.101.154:5555}
ADB=${ADB:-adb}

# --- gradle launcher -------------------------------------------------------
if [ -n "${GRADLE:-}" ]; then
    GRADLE_CMD="$GRADLE"
elif [ -f "$ROOT/tools/gradle-8.14.3/bin/gradle" ]; then
    GRADLE_CMD="$ROOT/tools/gradle-8.14.3/bin/gradle"
elif [ -x "$ROOT/android/gradlew" ]; then
    GRADLE_CMD="$ROOT/android/gradlew"
else
    GRADLE_CMD="gradle"
fi

gradle() {
    ( cd "$ROOT/android" && "$GRADLE_CMD" --console=plain -q "$@" )
}

apk_path() {
    local flavor
    flavor=$(echo "${1:-standalone}" | cut -c1 | tr '[:lower:]' '[:upper:]')
    flavor="$flavor$(echo "${1:-standalone}" | cut -c2- | tr '[:upper:]' '[:lower:]')"
    echo "$APK_DIR/${flavor,,}/debug/app-${flavor,,}-debug.apk"
}

cmd_assemble() {
    local flavor=${1:-standalone}
    local cap="${flavor^}"
    local t0=$SECONDS
    gradle "assemble${cap}Debug"
    local apk
    apk=$(apk_path "$flavor")
    [ -f "$apk" ] || { echo "APK missing: $apk" >&2; return 1; }
    printf 'apk: %s (%.1f MB, %ss)\n' "$apk" \
        "$(stat -c %s "$apk" | awk '{print $1/1048576}')" "$((SECONDS - t0))"
}

cmd_install() {
    local flavor=${1:-standalone}
    cmd_assemble "$flavor"
    local apk
    apk=$(apk_path "$flavor")
    "$ADB" -s "$DEV" connect "$DEV" >/dev/null 2>&1 || true
    # push + pm install is far more reliable than "adb install" for a ~280 MB APK
    "$ADB" -s "$DEV" push "$apk" /data/local/tmp/vstp.apk
    "$ADB" -s "$DEV" shell pm install -r -d /data/local/tmp/vstp.apk
    "$ADB" -s "$DEV" shell rm -f /data/local/tmp/vstp.apk
}

cmd_check() {
    "$ROOT/tools/gate.sh" check
}

cmd_lint() {
    gradle "lintStandaloneDebug" || true
    echo "report: $ROOT/android/app/build/reports/lint-results-standaloneDebug.html"
}

cmd_clean() {
    ( cd "$ROOT/android" && "$GRADLE_CMD" clean --console=plain -q )
    rm -rf "$ROOT/android/app/build" "$ROOT/android/build" "$ROOT/android/.gradle"
}

case "${1:-}" in
    assemble) shift; cmd_assemble "${1:-standalone}" ;;
    install)  shift; cmd_install  "${1:-standalone}" ;;
    check)    cmd_check ;;
    lint)     cmd_lint ;;
    clean)    cmd_clean ;;
    *)
        sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
        exit 2
        ;;
esac