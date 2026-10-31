#!/bin/bash
# run the whole benchmark matrix into tools/dev/bench/results.log
export MSYS_NO_PATHCONV=1
ROOT="D:/AI Misc/vst3_player"
LOG="$ROOT/tools/dev/bench/results.log"
ADB=C:/adb/platform-tools/adb.exe
DEV=192.0.2.10:5555
: > "$LOG"

run_one() {
  local label="$1" seg="$2" eng="$3" mode="$4"
  : > /tmp/r.txt
  if [ "$mode" = "bg" ]; then
    bash "$ROOT/tools/dev/bench/run.sh" "$seg" "$eng" 900 "$label" >> /tmp/r.txt 2>&1 &
    local pid=$!
    sleep 30
    $ADB -s $DEV shell input keyevent KEYCODE_HOME >/dev/null 2>&1
    sleep 2
    echo "[$label] foreground service state:" >> "$LOG"
    $ADB -s $DEV shell "dumpsys activity services com.vstp | grep -E isForeground | head -2" >> "$LOG" 2>&1
    echo "[$label] notification record:" >> "$LOG"
    $ADB -s $DEV shell "dumpsys notification --noredact | grep -c com.vstp" >> "$LOG" 2>&1
    wait $pid
  else
    bash "$ROOT/tools/dev/bench/run.sh" "$seg" "$eng" 900 "$label" >> /tmp/r.txt 2>&1
  fi
  cat /tmp/r.txt >> "$LOG"
  echo "----" >> "$LOG"
}

run_one single_core 0 1 fg
run_one seg120_e1 120 1 fg
run_one seg180_e1 180 1 fg
run_one seg120_e2_bg 120 2 bg
run_one seg180_e2 180 2 fg
run_one seg300_e2 300 2 fg
run_one seg120_e3 120 3 fg
run_one seg180_e3 180 3 fg
echo ALL_DONE >> "$LOG"
