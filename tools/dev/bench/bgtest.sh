#!/bin/bash
export MSYS_NO_PATHCONV=1
ADB=C:/adb/platform-tools/adb.exe; S=-s; DEV=192.168.101.154:5555
ROOT="D:/AI Misc/vst3_player"
CONF="$ROOT/tools/dev/bench/bootrun.conf"
printf "decode.selftest=0\r\nrender.autorun=1\r\nrender.segment=120\r\nrender.engines=2\r\n" > "$CONF"
$ADB $S $DEV push "$CONF" /data/local/tmp/br.txt >/dev/null 2>&1
$ADB $S $DEV shell "run-as com.vstp sh -c 'cat /data/local/tmp/br.txt > files/boot.conf; rm -f files/diag.log'"
$ADB $S $DEV shell am force-stop com.vstp
$ADB $S $DEV shell am start -n com.vstp/com.vstplayer.MainActivity >/dev/null 2>&1
sleep 7
$ADB $S $DEV shell input tap 1200 2990 >/dev/null 2>&1
sleep 20
$ADB $S $DEV shell input keyevent KEYCODE_HOME >/dev/null 2>&1
echo "HOME sent at $(date +%H:%M:%S)"
for i in $(seq 1 90); do
  sleep 10
  PID=$($ADB $S $DEV shell pidof com.vstp 2>/dev/null | tr -d "\r")
  FRZ=$($ADB $S $DEV shell "cat /sys/fs/cgroup/apps/uid_10473/pid_$PID/cgroup.freeze 2>/dev/null" | tr -d "\r")
  DONE=$($ADB $S $DEV exec-out run-as com.vstp sh -c "grep -c 'batch done' files/diag.log" 2>/dev/null | tr -d "\r")
  echo "t+$((i*10))s pid=$PID freeze=$FRZ done=$DONE"
  if [ "$DONE" != "" ] && [ "$DONE" -gt 0 ] 2>/dev/null; then break; fi
done
$ADB $S $DEV exec-out run-as com.vstp sh -c "grep -E 'render batch|params synced|joined |batch done' files/diag.log" | tail -10
