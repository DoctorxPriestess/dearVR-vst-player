#!/bin/bash
export MSYS_NO_PATHCONV=1
ROOT="D:/AI Misc/vst3_player"
LOG="$ROOT/tools/_bench/results2.log"
: > "$LOG"
for cfg in "seg180_e2 180 2" "seg300_e2 300 2" "seg120_e3 120 3" "seg180_e3 180 3"; do
  set -- $cfg
  bash "$ROOT/tools/_bench/run.sh" "$2" "$3" 900 "$1" >> "$LOG" 2>&1
  echo "----" >> "$LOG"
done
echo ALL_DONE >> "$LOG"