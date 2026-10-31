#!/bin/bash
# 构建 + 安装 standalone debug APK，并输出体积与耗时
set -euo pipefail
export MSYS_NO_PATHCONV=1
ADB=${ADB:-C:/adb/platform-tools/adb.exe}
DEV=${DEV:-192.168.101.154:5555}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK="$ROOT/android/app/build/outputs/apk/standalone/debug/app-standalone-debug.apk"

echo "== build =="
T0=$(date +%s)
set +e
( cd "$ROOT/android" && ../tools/gradle-8.14.3/bin/gradle.bat assembleStandaloneDebug -q --console=plain ) 2>&1 | tail -25
STATUS=${PIPESTATUS[0]}
set -e
echo "build: $(( $(date +%s) - T0 ))s (gradle exit $STATUS)"
if [ "$STATUS" -ne 0 ]; then echo "BUILD FAILED"; exit 1; fi

if [ ! -f "$APK" ]; then echo "APK missing: $APK"; exit 1; fi
ls -l "$APK" | awk '{printf "apk: %.1f MB\n", $5/1048576}'

echo "== install =="
T1=$(date +%s)
"$ADB" -s "$DEV" connect "$DEV" >/dev/null 2>&1
APK_WIN=$(cygpath -w "$APK")
"$ADB" -s "$DEV" install -r -d "$APK_WIN" | tail -3
echo "install: $(( $(date +%s) - T1 ))s"
