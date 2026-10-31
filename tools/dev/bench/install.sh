#!/bin/bash
# install an APK over Wi-Fi and auto-accept vivo's "unknown source" intercept dialog
export MSYS_NO_PATHCONV=1
ADB=C:/adb/platform-tools/adb.exe
S=-s
DEV=192.0.2.10:5555
APK="$1"
$ADB $S $DEV shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; svc power stayon true" >/dev/null 2>&1
$ADB $S $DEV install --no-streaming -r -d "$APK" > /tmp/inst.log 2>&1 &
IPID=$!
for i in $(seq 1 100); do
  sleep 5
  FOCUS=$($ADB $S $DEV shell "dumpsys window | grep mCurrentFocus" 2>/dev/null)
  if echo "$FOCUS" | grep -q PackageInterceptActivity; then
    $ADB $S $DEV shell uiautomator dump /sdcard/i.xml >/dev/null 2>&1
    $ADB $S $DEV shell "cat /sdcard/i.xml" 2>/dev/null | tr ">" "\n" > /tmp/ui.txt
    if grep -q "继续安装" /tmp/ui.txt; then
      B=$(grep -oE "text=\"继续安装\"[^>]*bounds=\"[^\"]*\"" /tmp/ui.txt | grep -oE "\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | head -1)
      C=$(grep -oE "text=\"已了解应用的风险检测结果\"[^>]*bounds=\"[^\"]*\"" /tmp/ui.txt | grep -oE "\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" | head -1)
      if [ -n "$C" ]; then
        CX=$(echo "$C" | sed -E "s/\[([0-9]+),([0-9]+)\].*/\1 \2/")
        $ADB $S $DEV shell input tap $(( ${CX% *} + 40 )) $(( ${CX#* } + 30 )) >/dev/null 2>&1
      else
        $ADB $S $DEV shell input tap 720 2768 >/dev/null 2>&1
      fi
      if [ -n "$B" ]; then
        BX=$(echo "$B" | sed -E "s/\[([0-9]+),([0-9]+)\].*/\1 \2/")
        $ADB $S $DEV shell input tap $(( ${BX% *} + 100 )) $(( ${BX#* } + 30 )) >/dev/null 2>&1
      else
        $ADB $S $DEV shell input tap 720 2963 >/dev/null 2>&1
      fi
      echo "  accepted intercept dialog ($i)"
    fi
  fi
  if ! kill -0 $IPID 2>/dev/null; then break; fi
done
wait $IPID 2>/dev/null
tail -2 /tmp/inst.log
$ADB $S $DEV shell "dumpsys package com.vstp | grep -m1 lastUpdateTime"
