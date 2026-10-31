#!/usr/bin/env bash
# Serialized compile gate shared by every editor working in this tree.
#
# Gradle holds an exclusive lock on the project build directory, so two
# concurrent invocations fail instead of queueing. This wrapper makes the
# wait explicit and bounded.
#
#   tools/gate.sh check    javac compile of the whole source set (default)
#   tools/gate.sh full     full standalone debug assemble (slower, packages APK)
set -uo pipefail
export MSYS_NO_PATHCONV=1

ROOT=$(cd "$(dirname "$0")/.." && pwd)
LOCK="$ROOT/tools/.gate.lock"
WAIT_MAX=${GATE_WAIT:-1800}
MODE=${1:-check}

if [ -n "${GRADLE:-}" ]; then
    GRADLE_CMD="$GRADLE"
elif [ -x "$ROOT/tools/gradle-8.14.3/bin/gradle.bat" ]; then
    GRADLE_CMD="$ROOT/tools/gradle-8.14.3/bin/gradle.bat"
elif [ -x "$ROOT/android/gradlew" ]; then
    GRADLE_CMD="$ROOT/android/gradlew"
else
    GRADLE_CMD="gradle"
fi

waited=0
until mkdir "$LOCK" 2>/dev/null; do
    holder=$(cat "$LOCK/pid" 2>/dev/null || echo '?')
    if [ "$waited" -ge "$WAIT_MAX" ]; then
        echo "gate: timeout after ${WAIT_MAX}s waiting for lock (holder pid ${holder})" >&2
        exit 3
    fi
    sleep 2
    waited=$((waited + 2))
done
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT INT TERM

case "$MODE" in
    check)
        ( cd "$ROOT/android" && "$GRADLE_CMD" :app:compileStandaloneDebugJavaWithJavac --console=plain -q )
        ;;
    full)
        ( cd "$ROOT/android" && "$GRADLE_CMD" assembleStandaloneDebug --console=plain -q )
        ;;
    *)
        echo "usage: tools/gate.sh [check|full]" >&2
        exit 2
        ;;
esac
status=$?
[ "$status" -eq 0 ] && echo "gate: $MODE OK" || echo "gate: $MODE FAILED ($status)" >&2
exit "$status"
