#!/usr/bin/env bash
# Unified build entry point for the Android app.
#
#   tools/build.sh assemble            build the debug APK
#   tools/build.sh install             build, then push+install
#   tools/build.sh check               host self checks + style + compile gate
#   tools/build.sh lint                Android Lint report
#   tools/build.sh clean               remove build outputs
#
# Environment:
#   DEV      adb serial of the device (default: 192.0.2.10:5555)
#   ADB      adb binary            (default: resolved from PATH)
#   GRADLE   gradle launcher       (default: tools/gradle-8.14.3, then android/gradlew)
set -euo pipefail
export MSYS_NO_PATHCONV=1

ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK_DIR="$ROOT/android/app/build/outputs/apk"
APK="$APK_DIR/standalone/debug/app-standalone-debug.apk"
DEV=${DEV:-192.0.2.10:5555}
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

# MSYS_NO_PATHCONV=1 is on, so native tools (python, javac) need a Windows style path.
native_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

gradle() {
    ( cd "$ROOT/android" && "$GRADLE_CMD" --console=plain -q "$@" )
}

cmd_assemble() {
    local t0=$SECONDS
    gradle assembleStandaloneDebug
    [ -f "$APK" ] || { echo "APK missing: $APK" >&2; return 1; }
    printf 'apk: %s (%.1f MB, %ss)\n' "$APK" \
        "$(stat -c %s "$APK" | awk '{print $1/1048576}')" "$((SECONDS - t0))"
}

cmd_install() {
    cmd_assemble
    "$ADB" -s "$DEV" connect "$DEV" >/dev/null 2>&1 || true
    # push + pm install is far more reliable than "adb install" for a ~280 MB APK
    "$ADB" -s "$DEV" push "$APK" /data/local/tmp/vstp.apk
    "$ADB" -s "$DEV" shell pm install -r -d /data/local/tmp/vstp.apk
    "$ADB" -s "$DEV" shell rm -f /data/local/tmp/vstp.apk
}

cmd_check() {
    echo "== host self checks =="
    "$ROOT/tools/tests/run.sh"
    echo "== style =="
    python "$(native_path "$ROOT/tools/check_style.py")"
    echo "== compile gate =="
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
    assemble) cmd_assemble ;;
    install)  cmd_install ;;
    check)    cmd_check ;;
    lint)     cmd_lint ;;
    clean)    cmd_clean ;;
    *)
        sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
        exit 2
        ;;
esac
