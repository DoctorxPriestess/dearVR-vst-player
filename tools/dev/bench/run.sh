#!/bin/bash
# one benchmark run: $1=segment seconds (0=off) $2=engine count $3=timeout seconds $4=label
export MSYS_NO_PATHCONV=1
ADB=C:/adb/platform-tools/adb.exe
S=-s
DEV=192.168.101.154:5555
SEG=$1; ENG=$2; LIMIT=${3:-600}; LABEL=$4
ROOT="D:/AI Misc/vst3_player"
CONF="$ROOT/tools/dev/bench/bootrun.conf"
printf "decode.selftest=0\r\nrender.autorun=1\r\nrender.segment=%s\r\nrender.engines=%s\r\n" "$SEG" "$ENG" > "$CONF"
$ADB $S $DEV push "$CONF" /data/local/tmp/br.txt >/dev/null 2>&1
$ADB $S $DEV shell "run-as com.vstp sh -c 'cat /data/local/tmp/br.txt > files/boot.conf; rm -f files/diag.log'"
$ADB $S $DEV shell am force-stop com.vstp
$ADB $S $DEV shell am start -n com.vstp/com.vstplayer.MainActivity >/dev/null 2>&1
sleep 7
$ADB $S $DEV shell input tap 1200 2990 >/dev/null 2>&1
echo "[$LABEL] seg=$SEG eng=$ENG"
for i in $(seq 1 $((LIMIT / 5))); do
  sleep 5
  if $ADB $S $DEV exec-out run-as com.vstp sh -c "grep -c 'batch done' files/diag.log" 2>/dev/null | grep -qE "^[1-9]"; then break; fi
done
$ADB $S $DEV exec-out run-as com.vstp sh -c "grep -E 'render batch|prepared |joined |batch done|no split|autorun:' files/diag.log" | tail -20