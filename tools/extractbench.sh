#!/bin/bash
# 首次抽取基准：删掉 .ready 标记触发重新解压，测 env extraction 耗时与完整性
set -u
export MSYS_NO_PATHCONV=1
ADB=${ADB:-C:/adb/platform-tools/adb.exe}
DEV=${DEV:-192.168.101.154:5555}
PKG=com.vstp
ACT=$PKG/com.vstplayer.MainActivity
VER=${1:-3}

"$ADB" -s "$DEV" connect "$DEV" >/dev/null 2>&1
"$ADB" -s "$DEV" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
FILES_BEFORE=$("$ADB" -s "$DEV" shell "run-as $PKG sh -c 'cd files/glibc 2>/dev/null && find . | wc -l'" | tr -dc '0-9')

"$ADB" -s "$DEV" shell am force-stop $PKG
"$ADB" -s "$DEV" shell "run-as $PKG sh -c 'rm -f files/.ready-*'" >/dev/null 2>&1
BEFORE=$("$ADB" -s "$DEV" exec-out run-as $PKG wc -l files/diag.log 2>/dev/null | tr -dc '0-9'); BEFORE=${BEFORE:-0}
"$ADB" -s "$DEV" shell am start -n $ACT >/dev/null
T0=$(date +%s)

for _ in $(seq 1 300); do
  sleep 1
  if "$ADB" -s "$DEV" exec-out run-as $PKG cat files/diag.log 2>/dev/null | tail -n +$((BEFORE+1)) | grep -q 'env extraction done'; then break; fi
done
T1=$(date +%s)

LINES=$("$ADB" -s "$DEV" exec-out run-as $PKG cat files/diag.log 2>/dev/null | tail -n +$((BEFORE+1)))
echo "$LINES" | grep -E 'env extraction (begin|done)|params loaded|engine READY' | head -8
echo "extraction wall: $((T1-T0))s   files before=$FILES_BEFORE"
FILES_AFTER=$("$ADB" -s "$DEV" shell "run-as $PKG sh -c 'cd files/glibc 2>/dev/null && find . | wc -l'" | tr -dc '0-9')
echo "files after=$FILES_AFTER"
